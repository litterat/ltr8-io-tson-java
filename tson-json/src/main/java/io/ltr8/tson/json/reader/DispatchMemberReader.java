package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.diagnostics.RecordExtensionDiagnostics;
import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.base.diagnostics.Refusal;
import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
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
 * <p><b>It reads the leading members and no others.</b> §6.1.5 puts the discriminators first, after any
 * reserved members (§3.3), in any order among themselves, so {@link ReservedMembers#lead} answers both the tag
 * and the selectors from as many members as the family declares discriminators -- a count the schema fixes,
 * whatever the document holds (§10.1).
 *
 * <p><b>Every reader it can select was wired when the schema compiled</b> ({@link Route}), and so was every
 * name a tag may carry to reach one -- the members and, for each, the deeper names it admits. It builds
 * nothing itself, so one class serves every mode.
 *
 * <p><b>The tag can only assert, and the selected reader is what holds it to that.</b> {@code $type} MAY be
 * present and MUST name the dispatched member or a subtype of it. The discriminators are still required with
 * it, and the value goes where the tag names; the selected reader re-reads the whole object and re-verifies
 * each pin as an ordinary FIXED check, so a tag disagreeing with the discriminator meets a pinned field the
 * document contradicts. One selector per position, and the dispatch read and the validation read agree by
 * construction.
 */
final class DispatchMemberReader implements JsonTypeReader<Object>, ExactReader {

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

    /** Where a value goes once selected, by every name a member or a deeper tag may reach it under. */
    private final Map<String, Route> routes;
    private final JsonSchemaLocation schemaLocation;
    private final RecordExtensionDiagnostics extension;
    private final RecordDiagnostics rules;
    private final String pinned;

    /**
     * <b>The selectors arrive already chosen, from either kind of base</b> ({@code FamilySelectors}) -- a
     * closed record declares them, and a family-base template's are recovered from its members at the base's
     * own declared type. Taking the fields rather than a {@code RecordBody} is what lets one dispatcher serve
     * both, and is the same signature its {@code tson-compiler} peer takes.
     */
    DispatchMemberReader(Set<String> selfNames, String displayName, List<RecordField> selectorFields,
                           Set<String> subtypes,
                           ValueReaderContext context, JsonSchemaLocation schemaLocation,
                           RecordDiagnostics rules) {
        this.selfNames = Set.copyOf(selfNames);
        this.displayName = displayName;
        this.schemaLocation = schemaLocation;
        this.rules = rules;
        TsonSchema schema = context.schema();
        Map<String, TypeDefinition> entries = schema.entries();
        this.selectors = selectorFields.stream().map(field -> selectorOf(field, schema)).toList();
        this.selectorNames = selectors.stream().map(Selector::name).collect(LinkedHashSet::new,
                Set::add, Set::addAll);
        this.members = new LinkedHashMap<>();
        // What each dispatched member admits a deeper tag to name: itself and its own subtypes (§6.1.5).
        Map<String, Set<String>> deeper = new LinkedHashMap<>();
        Map<String, Route> table = new LinkedHashMap<>();
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
            for (String written : deeper.get(subtype)) {
                table.computeIfAbsent(written, ignored -> Route.to(context.readers().resolve(written)));
            }
        }
        this.routes = Map.copyOf(table);
        this.pinned = members.keySet().stream().map(DispatchMemberReader::render).reduce((a, b) -> a + " | " + b)
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
    public Object read(JsonReadContext ctx) {
        ctx = ctx.inRecord(schemaLocation);
        if (!(ctx.peek() instanceof JsonEvent.ObjectStart)) {
            JsonEvent found = ctx.next();
            ctx.report(rules.notARecord(JsonAtoms.describe(found)));
            EventSkip.value(ctx, found);
            return null;
        }
        return dispatch(ctx, ReservedMembers.lead(ctx, selectorNames));
    }

    /** Reached by a tag naming this base from an enclosing position: placed again, from the leading members. */
    @Override
    public Object readExact(JsonReadContext ctx, JsonTypeReader<?> wrapped) {
        return read(ctx);
    }

    private Object dispatch(JsonReadContext ctx, ReservedMembers.Lead tag) {
        if (Tags.refusesScope(ctx, tag, displayName, Tags.RECORD)) {
            return null;
        }
        // §3.3's wrapper puts the record inside `$value`, so the selectors are not at this level to read and
        // the tag is the only thing that can place it -- the ordinary wrapper rule, not a second dispatch.
        if (tag.wrapper()) {
            return wrapped(ctx, tag);
        }
        List<Object> key = new ArrayList<>(selectors.size());
        for (Selector selector : selectors) {
            JsonEvent value = tag.selectors().get(selector.name());
            if (value == null) {
                // Never a fallback to the tag: the selector is a REQUIRED field of the base, so a value
                // without it is invalid on §5.2's ordinary terms, and reading the tag instead would make one
                // family dispatch two ways. Not leading is not there, for the purpose of placing the value.
                ctx.field(selector.name()).report(notLeading(selector.name()));
                EventSkip.nextValue(ctx);
                return null;
            }
            Object decoded = decode(selector, value);
            if (decoded == null) {
                ctx.field(selector.name()).report(extension.unmatchedDiscriminator(
                        selector.name(), JsonAtoms.describe(value), pinned));
                EventSkip.nextValue(ctx);
                return null;
            }
            key.add(decoded);
        }
        String selected = members.get(key);
        if (selected == null) {
            ctx.report(extension.unmatchedDiscriminator(named(), render(key), pinned));
            EventSkip.nextValue(ctx);
            return null;
        }
        if (tag.type() != null && selfNames.contains(tag.type())) {
            // §8.1 admits a redundant tag restating a position's own type, but that rule assumes a type with
            // direct instances, and a sealed base has none: naming it selects nothing.
            ctx.report(extension.tagNamesTheBase(ReservedMembers.TYPE));
            EventSkip.nextValue(ctx);
            return null;
        }
        if (tag.type() == null) {
            return routes.get(selected).read(ctx, tag);
        }
        // A tag goes where it names, which is §6.1.5's "deeper than one level": the pins are inherited and
        // §5.7 forbids changing them, so a family dispatches one level by member and every level below it by
        // tag. Whether the tag agrees with the discriminator is the selected reader's to say -- it re-reads
        // the whole object and re-verifies each pin as an ordinary FIXED check, so a tag naming anything but
        // the dispatched member or a subtype of it meets a pinned field the document contradicts.
        Route route = routes.get(tag.type());
        return route != null ? route.read(ctx, tag) : notAMember(ctx, tag.type());
    }

    /**
     * A discriminator the leading members did not supply. JSON's own wording of {@code discriminatorMissing}:
     * §6.1.5 puts the discriminators first, so one written after another member is missing to the reader, and
     * the message says where it has to be rather than only that it was not found.
     */
    private Refusal notLeading(String field) {
        Refusal missing = extension.discriminatorMissing(field);
        return new Refusal(missing.code(), "missing discriminator '%s' for '%s' -- a sealed family selects its "
                .formatted(field, displayName) + "member by reading it, so its discriminators lead the object, "
                + "after any '$type' (§6.1.5), and '" + field + "' is absent or follows another member",
                "'" + field + "' as a leading member", missing.actual());
    }

    /** A tag naming a type outside the family -- nothing here admits it, whatever the discriminator says. */
    private Object notAMember(JsonReadContext ctx, String type) {
        if (!NameHygiene.refuses(ctx, type)) {
            ctx.field(ReservedMembers.TYPE).report(Diagnostic.Code.TYPE_MISMATCH,
                    "'$type' names '%s', which is not a member of the sealed '%s'".formatted(type, displayName),
                    extension.members(), type);
        }
        EventSkip.nextValue(ctx);
        return null;
    }

    /** §3.3's wrapper form at a sealed position: `$type` places the value and `$value` holds it. */
    private Object wrapped(JsonReadContext ctx, ReservedMembers.Lead tag) {
        if (tag.type() == null) {
            ctx.report(extension.tagRequired(ReservedMembers.TYPE));
            EventSkip.nextValue(ctx);
            return null;
        }
        Route route = routes.get(tag.type());
        return route != null ? route.read(ctx, tag) : notAMember(ctx, tag.type());
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
