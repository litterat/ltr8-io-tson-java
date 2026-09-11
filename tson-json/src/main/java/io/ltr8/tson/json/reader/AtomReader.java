package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * Reads one atom-typed position: [TSON-JSON] §5, which is one rule and a per-family table of admitted JSON
 * kinds. The kind is checked, its content handed to the family's own {@link AtomType} from {@code tson-atom},
 * and a refusal reported with the code the shared vocabulary picked.
 *
 * <p><b>The split of labour is §5.1's and is the whole of the design.</b> This encoding adds no atom grammar:
 * a string's content faces the same parser, the same acceptance set and the same error split as the
 * equivalent TSON token, so the two encodings cannot disagree about whether {@code "2026-07-01"} is a
 * {@code date}. What is this encoding's own is {@link AtomForm} -- which of JSON's kinds reach the parser
 * -- and nothing else.
 *
 * <p><b>Contract rejection and constraint violation stay apart</b>, and neither is decided here: {@link
 * AtomRefusal} maps the vocabulary's own exception to {@code ATOM_FORM_INVALID} or {@code
 * ATOM_CONSTRAINT_VIOLATION}, which is §5.1's split ("contract rejection is a resolver error; constraint
 * violation after parsing is a validation error") applied by the same code that applies it for TSON text.
 */
final class AtomReader<T> implements JsonTypeReader<T> {

    /**
     * Every atom constructor but {@code unit}. The family's parser comes from the declared name and the
     * resolved body together -- {@code AtomParsers} is the one index both encodings ask, so there is never a
     * second opinion about which parser reads which body.
     */
    static final ValueReaderFactory ATOM = AtomReader::of;

    /**
     * §5.2's enums, with {@code boolean} taken off the general path so it reads a real {@code Boolean} rather
     * than the text {@code "true"}. The general rule still decides the <em>form</em>; only the host value
     * differs, exactly as it does on the TSON side.
     */
    static final ValueReaderFactory ENUM = (name, definition, context) -> "boolean".equals(name)
            ? new AtomReader<>(name, BuiltinTypeVocabulary.lookup("boolean").orElseThrow(
                    () -> new IllegalStateException("the built-in vocabulary has no 'boolean'")),
                    AtomForm.BOOLEAN, context.locationOf(name, definition))
            : of(name, definition, context);

    /**
     * The three {@code unit} instances, dispatched on the declaration's own name -- [TSON-SCHEMA] §4.2 makes
     * that dispatch normative, their resolved shapes being identical and deliberately uninformative. Two of
     * the three are the encoding's rather than the vocabulary's, and §5.7 is where JSON states its own
     * readings of them.
     */
    static final ValueReaderFactory UNIT = (name, definition, context) -> switch (name) {
        case "void" -> new VoidReader(name, context.locationOf(name, definition));
        case "value" -> new ValuePositionReader(name, context.locationOf(name, definition));
        default -> of(name, definition, context);
    };

    private final String name;
    private final AtomType<T> parser;
    private final AtomForm form;
    private final JsonSchemaLocation schemaLocation;

    private AtomReader(String name, AtomType<T> parser, AtomForm form, JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.parser = parser;
        this.form = form;
        this.schemaLocation = schemaLocation;
    }

    private static JsonTypeReader<?> of(String name, TypeDefinition definition, ValueReaderContext context) {
        AtomType<?> parser = AtomParsers.forType(name, definition.body()).orElseThrow(() -> new IllegalStateException(
                "'" + name + "' is registered as an atom but its body has no parser: " + definition.body()));
        return new AtomReader<>(name, parser, AtomForm.of(definition.body()), context.locationOf(name, definition));
    }

    @Override
    public T read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        JsonEvent event = ctx.next();
        String content = form.contentOf(event);
        if (content == null) {
            // §5: a JSON value of the wrong kind at an atom position is a validation error -- and JSON null
            // is one of them here, §7 having already spent it as the absent sentinel, which no REQUIRED
            // position admits. That is the same verdict TSON text gives `_` in the same position.
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'%s' takes %s, and this is %s"
                    .formatted(name, form.describe(), JsonAtoms.describe(event)),
                    form.describe(), JsonAtoms.describe(event));
            EventSkip.value(ctx, event);
            return null;
        }
        try {
            return parser.read(content);
        } catch (AtomTypeException e) {
            AtomRefusal refusal = AtomRefusal.of(e, content, Object.class).named(name);
            ctx.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
            return null;
        }
    }
}
