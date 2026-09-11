package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.Top;

/**
 * Which JSON value kinds an atom family admits, and how the admitted kind becomes the text its parser reads
 * -- [TSON-JSON] §5's per-family rules, reduced to the only thing the reader has to decide.
 *
 * <p><b>§5.1 is why this is so small.</b> "Where a family's rules admit a string, the string's content is
 * handed to the atom's own parser exactly as a TSON quoted token's text would be" -- so this encoding adds no
 * atom grammar of its own, and every family's acceptance set, error split and constraint checking are the
 * shared vocabulary's ({@code tson-atom}). What is genuinely this encoding's is only which of JSON's kinds
 * reach the parser at all, which is what these five members say.
 *
 * <p>A number reaches the parser as its <b>literal</b>, never through a host numeric type: §3.1 requires a
 * decoder preserve a number's digits and §5.3 preserves an exact value's digits and scale, so {@code 199.90}
 * must arrive at the parser as it was written.
 */
enum AtomForm {

    /** §5.2's boolean half -- the kernel's {@code boolean}, read as the member text {@code true}/{@code false}. */
    BOOLEAN,

    /** §5.3's exact tier: {@code integer} and its refinements, and {@code number}. The literal is the content. */
    NUMBER,

    /** §5.5, §5.6 and {@code identifier}: every family whose content grammar lives above the lexer. */
    STRING,

    /**
     * §5.4's approximate families: a JSON number for the finite grid, and a JSON string for the specials
     * ({@code ".inf"}, {@code "-.inf"}, {@code ".nan"}) and the hex-float production, which JSON's number
     * grammar cannot spell. The first of §8.3's two class-stability leaks.
     */
    NUMBER_OR_STRING,

    /**
     * §5.2's general rule: an enum member's JSON form is the form of its <em>lexical class</em>, so an
     * identifier member arrives as a string and a boolean member as a JSON boolean. A number never does --
     * a sparse numeric value set is a member-constrained numeric atom, not an enum.
     */
    ENUM;

    /**
     * §5's table, read off the <em>resolved body</em> rather than off a name -- so a schema's own refinement
     * of a family ({@code price => !decimal ^ { min: 0 }}) takes its parent's form without being listed
     * anywhere, and a family added to the vocabulary lands in the right branch by its body's own type.
     */
    static AtomForm of(Top body) {
        return switch (body) {
            case EnumBody ignored -> ENUM;
            case IntegerType ignored -> NUMBER;
            case DecimalType ignored -> NUMBER;
            case FloatType ignored -> NUMBER_OR_STRING;
            case Atom ignored -> STRING;
            default -> throw new IllegalStateException(
                    "an atom form was asked of a non-atom body: " + body.getClass().getSimpleName());
        };
    }

    /**
     * The text {@code event} carries for this form's parser, or null when the kind is one this form does not
     * admit -- §5's <em>value of the wrong form</em>, which the caller reports.
     *
     * <p>JSON null is never admitted here and returns null for every form: §5 spends it as the absent
     * sentinel under §7's rules before any family rule applies, and the one position whose contract admits
     * it has a reader of its own ({@link VoidReader}).
     */
    String contentOf(JsonEvent event) {
        return switch (event) {
            case JsonEvent.BooleanValue bool when this == BOOLEAN || this == ENUM -> String.valueOf(bool.value());
            case JsonEvent.NumberValue number when this == NUMBER || this == NUMBER_OR_STRING -> number.literal();
            case JsonEvent.StringValue string when this == STRING || this == NUMBER_OR_STRING || this == ENUM ->
                    string.value();
            default -> null;
        };
    }

    /** What this form admits, for a diagnostic's {@code expected}. */
    String describe() {
        return switch (this) {
            case BOOLEAN -> "a JSON boolean";
            case NUMBER -> "a JSON number";
            case STRING -> "a JSON string";
            case NUMBER_OR_STRING -> "a JSON number, or a string for .inf/-.inf/.nan";
            case ENUM -> "a JSON string or boolean naming a member";
        };
    }
}
