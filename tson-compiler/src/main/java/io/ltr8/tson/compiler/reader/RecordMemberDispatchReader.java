package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.base.diagnostics.FamilyDiagnostics;
import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.TokenAtomType;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A SEALED record position ([TSON-SCHEMA] §5.2): the base declares one or more discriminator fields, each
 * member of the family pins them, and a value here is placed by reading those fields rather than by carrying
 * a tag. The text encoding reads a sealed position exactly as [TSON-JSON] §6.1.5 has JSON read one -- the tag
 * is not required and MUST agree where written -- so the discriminator is a field the reference encoding
 * reads rather than one it declares and ignores.
 *
 * <p><b>The mapping is built when the schema compiles and never at read time.</b> Each member's pins are
 * decoded once, at the fields' declared types <em>in the base</em> -- the one set known before a member is
 * selected -- and keyed by what they compare as ({@link ValueIdentity}). §4.3 makes {@code 255} and {@code
 * 0xFF} one pin and §5.5 makes {@code 1} and {@code 1.0} one, and the same rule decides both sides: the
 * schema's token and the document's are decoded by the same parser before either is compared.
 *
 * <p><b>The scan is a lookahead and rewinds</b> ({@link TsonReadContext#lookingAhead}), because [TSON-DATA]
 * gives a record's fields no significant order: the selector may arrive after the fields it selects, so the
 * opening brace settles nothing. The selected member then re-reads the whole value, which re-verifies each
 * pin as an ordinary FIXED check and makes the dispatch read and the validation read agree by construction.
 *
 * <p>One reader for both modes, and named for the dispatch rather than the member -- {@link
 * RecordTagDispatchReader}'s own Javadoc has both reasons.
 */
final class RecordMemberDispatchReader implements TsonTypeReader<Object>, Subsumption.Applied {

    /** How this encoding spells a tag, for the {@code actual} of a rule whose message may not name it. */
    private static final String TAG = "!type annotation";

    /** One discriminator field of the base: the field name, and the parser its declared type gives. */
    private record Selector(String name, AtomType<?> parser) {
    }

    /** The base's own name, which a tag may restate: §8.1 admits a redundant tag at any typed position. */
    private final String baseName;

    private final List<Selector> selectors;
    private final Set<String> selectorNames;
    private final Map<List<Object>, String> members;
    private final Map<String, Set<String>> deeper;
    private final TsonTypeReaderResolver readerFor;
    private final FamilyDiagnostics family;
    private final String pinned;

    RecordMemberDispatchReader(String name, String displayName, RecordBody body, Set<String> subtypes,
                                Map<String, TypeDefinition> entries, TsonTypeReaderResolver readerFor) {
        this.baseName = name;
        this.readerFor = readerFor;
        this.selectors = body.fields().stream().filter(RecordField::discriminator)
                .map(field -> selectorOf(field, entries)).toList();
        this.selectorNames = new LinkedHashSet<>(selectors.stream().map(Selector::name).toList());
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
            deeper.put(subtype, under);
        }
        this.pinned = members.keySet().stream().map(RecordMemberDispatchReader::render)
                .reduce((a, b) -> a + " | " + b).orElse("(none)");
        this.family = new FamilyDiagnostics(displayName, String.join(" | ", subtypes));
    }

    private static Selector selectorOf(RecordField field, Map<String, TypeDefinition> entries) {
        String terminal = ReferenceChain.terminal(field.type().name(), entries);
        TypeDefinition target = entries.get(terminal);
        AtomType<?> parser = AtomParsers.forType(terminal, target.body()).orElseThrow(
                () -> new IllegalStateException("a discriminator typed '" + field.type().name()
                        + "' reached a reader; the linker refuses a selector that is not an atom or an enum"));
        return new Selector(Nfc.of(field.name()), parser);
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
            Object value = decode(selector, pin.text(), TokenForm.valueOf(pin.form().name()));
            if (value == null) {
                return null;
            }
            key.add(value);
        }
        return key;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> tag = EventSkip.typeRefAhead(ctx);
        if (tag.filter(baseName::equals).isPresent()) {
            // §8.1 admits a redundant tag restating a position's own type, but that rule assumes a type with
            // direct instances. A sealed base has none, so naming it selects nothing -- and letting it through
            // would hand the member's reader a tag its own §7.2 guard must refuse, one position too late.
            ctx.report(family.tagNamesTheBase(TAG));
            EventSkip.dataValue(ctx);
            return null;
        }
        Map<String, TokenEvent> found = TsonReadContext.lookingAhead(ctx, this::scan);
        List<Object> key = new ArrayList<>(selectors.size());
        for (Selector selector : selectors) {
            TokenEvent value = found.get(selector.name());
            if (value == null) {
                // Never a fallback to the tag: the selector is a REQUIRED field of the base, so a value
                // without it is invalid on §5.2's ordinary terms, and reading the tag instead would make one
                // family dispatch two ways.
                ctx.field(selector.name()).report(family.discriminatorMissing(selector.name()));
                EventSkip.dataValue(ctx);
                return null;
            }
            Object decoded = decode(selector, value.text(), value.form());
            if (decoded == null) {
                ctx.field(selector.name())
                        .report(family.unmatchedDiscriminator(selector.name(), value.text(), pinned));
                EventSkip.dataValue(ctx);
                return null;
            }
            key.add(decoded);
        }
        String selected = members.get(key);
        if (selected == null) {
            ctx.report(family.unmatchedDiscriminator(named(), render(key), pinned));
            EventSkip.dataValue(ctx);
            return null;
        }
        if (tag.isPresent() && !deeper.getOrDefault(selected, Set.of()).contains(tag.get())) {
            ctx.report(family.tagContradictsDiscriminator(TAG, tag.get(), selected));
            EventSkip.dataValue(ctx);
            return null;
        }
        // A deeper tag wins over the dispatched member ([TSON-JSON] §6.1.5's "deeper than one level"): the
        // pins are inherited and §5.7 forbids changing them, so a family dispatches one level by member and
        // every level below it by tag.
        return readerFor.resolve(tag.orElse(selected)).read(ctx);
    }

    /**
     * The selectors' tokens, read across the whole record and rewound. Values that are not tokens are left
     * out: a selector is typed by an atom or an enum ([TSON-SCHEMA] §5.2, checked at schema load), so one
     * token holds the whole value and anything else meets the selected member's own field rules instead.
     */
    private Map<String, TokenEvent> scan(TsonReadContext ctx) {
        Map<String, TokenEvent> found = new LinkedHashMap<>();
        EventSkip.annotationsAndTypeRef(ctx);
        if (!(ctx.peek() instanceof RecordStart)) {
            return found;
        }
        ctx.next();
        while (!(ctx.peek() instanceof RecordEnd)) {
            if (!(ctx.next() instanceof FieldName field)) {
                return found;
            }
            if (selectorNames.contains(field.name()) && ctx.peek() instanceof TokenEvent token) {
                found.putIfAbsent(field.name(), token);
            }
            EventSkip.scopedValue(ctx);
        }
        return found;
    }

    private Object decode(Selector selector, String text, TokenForm form) {
        try {
            Object value = selector.parser() instanceof TokenAtomType<?> formSensitive
                    ? formSensitive.read(new TokenValue(text, form))
                    : selector.parser().read(text);
            return ValueIdentity.of(value);
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
