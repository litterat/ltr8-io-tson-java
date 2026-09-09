package io.ltr8.tson.atom.parser;

import java.util.Optional;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;

/**
 * meta-kernel's {@code boolean => !enum [true false]}, read as a genuine {@code Boolean}.
 *
 * <p><b>An enum whose two members stand for host values</b>, which is what separates it from every other
 * enum instance: {@link EnumParser} hands back the member's own text, exactly right for a user-defined
 * label and exactly wrong here, where a consumer's component is declared {@code boolean} and not
 * {@code String}. The compiled reader stack already draws that line by name ({@code AtomTypeReader}'s
 * {@code boolean} case); this is the same line drawn in the schemaless vocabulary, so a token reads to the
 * same host value with a schema and without one.
 *
 * <p><b>{@code true} and {@code false} only, lowercase, case-sensitive</b> ([TSON-DATA] §4.2: "No other
 * representations (yes, no, on, off, True, FALSE) are recognised"). Anything else is an
 * {@link AtomValidationException} rather than a parse failure, matching {@link EnumParser}: an enum's member
 * set is a range and not a grammar, so a token outside it is a value the type does not admit.
 *
 * <p><b>The form the token took is not consulted</b>, as for every {@link AtomType}: at a position typed
 * {@code boolean}, {@code true} and {@code "true"} are one value. That is the general rule for a typed
 * position ([TSON-SCHEMA] §4.2) rather than anything this family decides, and it is why [TSON-DATA] §4.2's
 * special status for the two tokens is a *base type resolution* rule, which a typed position never reaches.
 *
 * <p>Registered in {@link BuiltinTypeVocabulary} under {@code boolean} -- see {@code SPEC-FEEDBACK.md} #8
 * for why §5's own table omits it and why that reads as an oversight rather than a decision.
 */
public record BooleanParser() implements AtomType<Boolean> {

    /** §5's built-in annotation name. */
    public static final String TYPENAME = "boolean";

    /** The kernel declares one {@code boolean} and there is nothing to parameterise. */
    public static final BooleanParser INSTANCE = new BooleanParser();

    @Override
    public Boolean read(String text) {
        return switch (text) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new AtomValidationException(
                    "'" + text + "' is not a member of boolean -- expected one of [true, false]",
                    "one of (true, false)");
        };
    }

    @Override
    public String write(Boolean value) {
        return value ? "true" : "false";
    }

    /** §4.2's two tokens and nothing else, so the one target is the boolean they denote. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return natural(Boolean.class, target);
    }

}
