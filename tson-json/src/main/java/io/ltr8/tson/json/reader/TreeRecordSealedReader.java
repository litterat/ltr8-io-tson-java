package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.diagnostics.RecordExtensionDiagnostics;
import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A SEALED record position: [TSON-JSON] §6.1.5's third reading, and the one the discriminated-family design
 * exists for. The base declares one or more discriminator fields, each member of the family pins them, and a
 * value here is placed by reading those members rather than by carrying a tag.
 *
 * <p><b>The mapping is built when the schema compiles and never at read time.</b> Each subtype's pins are
 * decoded once, at the fields' declared types <em>in the base</em> -- the one set of types known before a
 * subtype is selected -- and keyed by what they compare as ({@link ValueIdentity}), so a read is one map
 * lookup. §4.3 makes {@code 255} and {@code 0xFF} one pin and §5.5 makes {@code 1} and {@code 1.0} one, and
 * the same rule decides both sides here: the schema's token and the document's literal are decoded by the
 * same parser before either is compared, so a document writing {@code 0xFF}'s value as {@code 255} selects
 * the member pinned {@code 0xFF}.
 *
 * <p><b>One scan, not two.</b> §6.1.6 gives member order no meaning, so the selectors may sit anywhere and
 * the opening brace settles nothing -- and §8.1 admits a tag beside them. {@link ReservedMembers#scanFor}
 * answers both in the pass this position was going to make anyway.
 *
 * <p><b>The tag can only assert.</b> {@code $type} MAY be present and MUST name the dispatched member or a
 * subtype of it; there is one selector per position, and the pin's own FIXED check is what makes the dispatch
 * read and the validation read agree by construction -- the selected member re-reads the whole object,
 * including the member that selected it.
 */
final class TreeRecordSealedReader implements JsonTypeReader<JsonValue> {

    /** One discriminator field of the base: the member name, and the parser its declared type gives. */
    private record Selector(String name, AtomType<?> parser, AtomForm form) {
    }

    private final String displayName;

    /**
     * Every written name that means the base, which a tag may restate: §8.1 admits a redundant tag at any
     * typed position, and §7.2 compares after flattening, so an alias restates it too.
     */
    private final Set<String> selfNames;

    private final List<Selector> selectors;
    private final Set<String> selectorNames;

    /** The pins, as they compare, to the member that states them -- §5.7's mapping, derived and never declared. */
    private final Map<List<Object>, String> members;

    /** What each dispatched member admits a deeper tag to name: itself and its own subtypes (§6.1.5). */
    private final Map<String, Set<String>> deeper;

    private final TypeReaderResolver readerFor;
    private final JsonSchemaLocation schemaLocation;
    private final RecordExtensionDiagnostics extension;
    private final RecordDiagnostics rules;
    private final String pinned;

    TreeRecordSealedReader(Set<String> selfNames, String displayName, RecordBody body, Set<String> subtypes,
                           ValueReaderContext context, JsonSchemaLocation schemaLocation,
                           RecordDiagnostics rules) {
        this.selfNames = Set.copyOf(selfNames);
        this.displayName = displayName;
        this.readerFor = context.readers();
        this.schemaLocation = schemaLocation;
        this.rules = rules;
        TsonSchema schema = context.schema();
        Map<String, TypeDefinition> entries = schema.entries();
        this.selectors = body.fields().stream().filter(RecordField::discriminator)
                .map(field -> selectorOf(field, schema)).toList();
        this.selectorNames = selectors.stream().map(Selector::name).collect(LinkedHashSet::new,
                Set::add, Set::addAll);
        this.members = new LinkedHashMap<>();
        this.deeper = new LinkedHashMap<>();
        for (String subtype : subtypes) {
            TypeDefinition definition = entries.get(subtype);
            if (definition == null || !(definition.body() instanceof RecordBody record)) {
                continue;
            }
            List<Object> key = pinsOf(record);
            if (key != null) {
                members.putIfAbsent(key, subtype);
            }
            Set<String> under = new LinkedHashSet<>();
            under.add(subtype);
            under.addAll(definition.subtypes());
            deeper.put(subtype, context.admitting(under));
        }
        this.pinned = members.keySet().stream().map(TreeRecordSealedReader::render).reduce((a, b) -> a + " | " + b)
                .orElse("(none)");
        this.extension = new RecordExtensionDiagnostics(displayName, String.join(" | ", subtypes));
    }

    private static Selector selectorOf(RecordField field, TsonSchema schema) {
        ReferenceChain.Resolved target = ReferenceChain.terminal(schema, field.type().name()).orElseThrow(
                () -> new IllegalStateException("a discriminator typed '" + field.type().name()
                        + "' names nothing this schema declares"));
        AtomType<?> parser = AtomParsers.forType(target.name(), target.definition().body()).orElseThrow(
                () -> new IllegalStateException("a discriminator typed '" + field.type().name()
                        + "' reached a reader; the linker refuses a selector that is not an atom or an enum"));
        return new Selector(Nfc.of(field.name()), parser, AtomForm.of(target.definition().body()));
    }

    /** One member's pins in the base's declaration order, or null where it does not pin them all. */
    private List<Object> pinsOf(RecordBody record) {
        List<Object> key = new ArrayList<>(selectors.size());
        for (Selector selector : selectors) {
            Optional<RecordField> field = record.fields().stream()
                    .filter(f -> Nfc.of(f.name()).equals(selector.name())).findFirst();
            if (field.isEmpty() || field.get().state() != FieldState.REQUIRED_FIXED
                    || field.get().value().isEmpty()) {
                return null; // the linker already refused this; the family is read without it
            }
            Token pin = field.get().value().get();
            try {
                key.add(ValueIdentity.of(selector.parser().read(pin.text())));
            } catch (AtomTypeException notThisType) {
                return null;
            }
        }
        return key;
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        ctx = ctx.inRecord(schemaLocation);
        if (!(ctx.peek() instanceof JsonEvent.ObjectStart)) {
            JsonEvent found = ctx.next();
            ctx.report(rules.notARecord(JsonAtoms.describe(found)));
            EventSkip.value(ctx, found);
            return JsonNull.INSTANCE;
        }
        ReservedMembers.Scan scan = ReservedMembers.scanFor(ctx, selectorNames);
        ReservedMembers.Tag tag = scan.tag();
        JsonValue refused = Tags.refuseMisuse(ctx, tag, displayName);
        if (refused != null) {
            return refused;
        }
        // §3.3's wrapper puts the record inside `$value`, so the selectors are not at this level to read and
        // the tag is the only thing that can place it -- the ordinary wrapper rule, not a second dispatch.
        if (tag.wrapper()) {
            return wrapped(ctx, tag);
        }
        List<Object> key = new ArrayList<>(selectors.size());
        for (Selector selector : selectors) {
            JsonEvent value = scan.selectors().get(selector.name());
            if (value == null) {
                // Never a fallback to the tag: the selector is a REQUIRED field of the base, so a value
                // without it is invalid on §5.2's ordinary terms, and reading the tag instead would make one
                // family dispatch two ways.
                ctx.field(selector.name()).report(extension.discriminatorMissing(selector.name()));
                EventSkip.nextValue(ctx);
                return JsonNull.INSTANCE;
            }
            Object decoded = decode(selector, value);
            if (decoded == null) {
                ctx.field(selector.name()).report(extension.unmatchedDiscriminator(
                        selector.name(), JsonAtoms.describe(value), pinned));
                EventSkip.nextValue(ctx);
                return JsonNull.INSTANCE;
            }
            key.add(decoded);
        }
        String selected = members.get(key);
        if (selected == null) {
            ctx.report(extension.unmatchedDiscriminator(named(), render(key), pinned));
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (tag.type() != null && selfNames.contains(tag.type())) {
            // §8.1 admits a redundant tag restating a position's own type, but that rule assumes a type with
            // direct instances, and a sealed base has none: naming it selects nothing.
            ctx.report(extension.tagNamesTheBase(ReservedMembers.TYPE));
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (tag.type() != null && !deeper.getOrDefault(selected, Set.of()).contains(tag.type())) {
            // Located at the value and not at `/$type`, though the member is right there. §9.4 holds both
            // encodings to one pointer for a rule, and TSON's tag is an annotation with no pointer step of
            // its own -- so a rule they share can only be located where they both have a location. §3.3's
            // reserved members are apparatus rather than data in any case, which is the same conclusion.
            ctx.report(extension.tagContradictsDiscriminator(ReservedMembers.TYPE, tag.type(), selected));
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        // A deeper tag wins over the dispatched member, which is §6.1.5's "deeper than one level": the pins
        // are inherited and §5.7 forbids changing them, so a family dispatches one level by member and every
        // level below it by tag. The selected reader re-reads the whole object and re-verifies each pin as an
        // ordinary FIXED check, which is what makes the two reads agree by construction.
        String effective = tag.type() != null ? tag.type() : selected;
        return (JsonValue) readerFor.resolve(effective).read(ctx);
    }

    /** §3.3's wrapper form at a sealed position: `$type` places the value and `$value` holds it. */
    private JsonValue wrapped(JsonReadContext ctx, ReservedMembers.Tag tag) {
        if (tag.type() == null) {
            ctx.report(extension.tagRequired(ReservedMembers.TYPE));
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (!members.containsValue(tag.type()) && deeper.values().stream().noneMatch(s -> s.contains(tag.type()))) {
            if (!NameHygiene.refuses(ctx, tag.type())) {
                ctx.field(ReservedMembers.TYPE).report(Diagnostic.Code.TYPE_MISMATCH,
                        "'$type' names '%s', which is not a member of the sealed '%s'"
                                .formatted(tag.type(), displayName),
                        extension.members(), tag.type());
            }
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        return ReservedMembers.readWrapped(ctx, readerFor.resolve(tag.type()));
    }

    private Object decode(Selector selector, JsonEvent value) {
        String content = selector.form().contentOf(value);
        if (content == null) {
            return null;
        }
        try {
            return ValueIdentity.of(selector.parser().read(content));
        } catch (AtomTypeException notThisType) {
            return null;
        }
    }

    /** The selectors named as a document's author would read them back -- one field, or the tuple of them. */
    private String named() {
        return selectors.size() == 1 ? selectors.get(0).name()
                : "(" + selectors.stream().map(Selector::name).reduce((a, b) -> a + ", " + b).orElse("") + ")";
    }

    /** A pin, or a tuple of pins, rendered for a diagnostic. */
    private static String render(List<Object> key) {
        return key.size() == 1 ? String.valueOf(key.get(0))
                : "(" + key.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("") + ")";
    }
}
