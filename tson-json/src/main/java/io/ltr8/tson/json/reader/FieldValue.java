package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.json.tree.JsonBoolean;
import io.ltr8.tson.json.tree.JsonNumber;
import io.ltr8.tson.json.tree.JsonString;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * A field's schema-stated value, resolved once at compile time: the host value it denotes, and the JSON node
 * that spells it.
 *
 * <p>Both are needed and neither substitutes for the other. The <b>host value</b> is what a stated member is
 * compared against ([TSON-JSON] §6.1.3: a present member at a FIXED field MUST be verified against the pin),
 * and it has to be the decoded value rather than the text because §5.3 makes {@code 1} and {@code 1.0} one
 * value -- comparing spellings would refuse a document that conforms. The <b>node</b> is what an omitted
 * REQUIRED_DEFAULT or REQUIRED_FIXED member injects, and it has to be JSON because §6.1.3 wants decoded
 * output fully populated: "a JSON document read by schema-less JSON consumers is exactly the document that
 * must state its defaults".
 *
 * <p>[TSON-SCHEMA] §5.2 confines a {@code ~}/{@code =} value to an atom- or enum-typed field, which is what
 * makes one parser and one form enough here.
 */
record FieldValue(AtomType<?> parser, AtomForm form, Object pinned, JsonValue node, String text) {

    /**
     * Resolves {@code token} against the field's declared type. Raises if the type is not one an atom parser
     * answers for -- the kernel's {@code value} and {@code void}, which have no content grammar of their own
     * -- so the entry becomes a gap rather than compiling a check it could not perform.
     */
    static FieldValue of(TsonSchema schema, String fieldTypeName, Token token) {
        String name = fieldTypeName;
        Top body = null;
        for (int hops = 0; hops < MAX_REFERENCE_HOPS; hops++) {
            TypeDefinition definition = schema.entries().get(name);
            if (definition == null) {
                throw new IllegalStateException("'" + name + "' is not declared -- linking should have refused this");
            }
            if (definition.body() instanceof Reference reference) {
                name = reference.target().name();
                continue;
            }
            body = definition.body();
            break;
        }
        if (!(body instanceof Atom atom)) {
            throw new IllegalStateException("'" + fieldTypeName + "' carries a schema-stated value but is not an "
                    + "atom -- [TSON-SCHEMA] §5.2 admits one only on an atom- or enum-typed field");
        }
        AtomType<?> parser = AtomParsers.forType(name, atom).orElseThrow(() -> new IllegalStateException(
                "'" + fieldTypeName + "' carries a schema-stated value but has no parser to read it with"));
        AtomForm form = AtomForm.of(atom);
        Object pinned = parser.read(token.text());
        return new FieldValue(parser, form, pinned, node(form, pinned, token.text()), token.text());
    }

    /** How many reference hops before this gives up; linking has already refused a cycle, so this is a guard. */
    private static final int MAX_REFERENCE_HOPS = 64;

    /**
     * The JSON spelling of a schema-stated value -- §5's table run backwards.
     *
     * <p><b>It is rendered from the decoded value, not from the token.</b> §4.3 makes radix and digit
     * separators text-encoding spellings that do not survive, so a field defaulted {@code = 0xFF} injects
     * {@code 255} and one defaulted {@code = 1_000} injects {@code 1000}. A string-content family is the
     * exception and keeps its token: the token <em>is</em> the spelling there, which is what a {@code bytes}
     * default in base64 needs.
     */
    private static JsonValue node(AtomForm form, Object value, String text) {
        return switch (form) {
            case BOOLEAN -> JsonBoolean.of(Boolean.parseBoolean(text));
            case NUMBER -> new JsonNumber(String.valueOf(value));
            // §5.4: the specials have no JSON number spelling and ride as strings holding [TSON-DATA] §7.6's
            // own productions -- so a `float64 ~ .nan` default injects `".nan"`, not the word NaN.
            case NUMBER_OR_STRING -> special(value) != null
                    ? new JsonString(special(value))
                    : new JsonNumber(String.valueOf(value));
            case STRING -> new JsonString(text);
            // §5.2: an enum member's form is the form of its own lexical class, so only the two boolean
            // literals are booleans and every other member is an identifier, hence a string.
            case ENUM -> "true".equals(text) || "false".equals(text)
                    ? JsonBoolean.of(Boolean.parseBoolean(text))
                    : new JsonString(text);
        };
    }

    /** [TSON-DATA] §7.6's spelling of a non-finite approximate value, or null for one on the finite grid. */
    private static String special(Object value) {
        double d = value instanceof Number number ? number.doubleValue() : 0d;
        if (Double.isNaN(d)) {
            return ".nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? ".inf" : "-.inf";
        }
        return null;
    }
}
