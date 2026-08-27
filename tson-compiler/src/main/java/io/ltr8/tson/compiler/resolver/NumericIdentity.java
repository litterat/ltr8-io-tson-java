package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.base.NumberForm;
import io.ltr8.tson.compiler.base.NumberForms;
import io.ltr8.tson.compiler.base.NumberGrammar;

/**
 * [TSON-DATA] §4.3's numeric equivalence, applied where an entry's identity is derived.
 *
 * <p>[TSON-SCHEMA] §8.2 keys an instantiation on the flattened application recorded in {@code source}, and a
 * lifted sugar form on the binding record it writes. Both are derived from what the author spelled, so
 * without this {@code vector<float32, 255>} and {@code vector<float32, 0xFF>} are two entries for one type
 * -- §4.3 making the two one number, and their bodies coming out byte-identical because the slot decodes
 * through {@code integer} either way. The consequence is a wrong verdict rather than a redundant entry:
 * §5.4 requires a choice's variants to resolve to distinct types, and two names for one type pass a check
 * two spellings of one name fail, so {@code ( [float32; 255] | [float32; 0xFF] )} is admitted where
 * {@code ( [float32; 255] | [float32; 255] )} is refused.
 *
 * <p><b>The equivalence applied is exactly the one §4.3 states, and no wider.</b> Radix, digit separators
 * and a redundant sign fall away ({@code 255}/{@code 0xFF}/{@code 0b1111_1111}/{@code +255}); a float's
 * written scale does too ({@code .5}/{@code 0.5}, {@code 1.0}/{@code 1e0}). What does <em>not</em> fall away
 * is the base type: {@code 1} is an integer and {@code 1.0} a float under §4's own resolution order, so
 * {@link Canonical#kind} keeps them apart even though one magnitude covers both. Merging across that line
 * would be this implementation inventing an equivalence rather than applying one.
 *
 * <p><b>Identity only.</b> The token itself is recorded as written -- §5.10 describes a type argument's
 * literal as a bare token, and {@code RawTokenParser} preserves it -- so resolved output still shows the
 * author's spelling. What changes is only what two spellings are compared as. Where two spellings do meet,
 * one entry results and it carries whichever reached the resolver first.
 *
 * <p>A quoted token is never a number (§4.4), which is why every entry point takes the form alongside the
 * text rather than guessing from the text alone.
 */
final class NumericIdentity {

    private NumericIdentity() {
    }

    /**
     * A number's identity: the base-type {@code kind} it resolves to, and the one {@code text} every
     * spelling of its magnitude reduces to.
     *
     * <p>The two are written as separate length-prefixed fields by the callers that hash, so a token whose
     * own text happens to read like a kind tag cannot collide with a tagged number.
     */
    record Canonical(String kind, String text) {
    }

    /**
     * The canonical form of {@code text}, or {@code null} when it is not a number and identity should use
     * the text as written.
     */
    static Canonical of(String text, boolean unquoted) {
        if (!unquoted) {
            return null;
        }
        NumberForm form = NumberGrammar.tryParse(text).orElse(null);
        return switch (form) {
            case null -> null;
            case NumberForm.IntegerForm f -> new Canonical("#i", NumberForms.toBigInteger(f).toString());
            case NumberForm.BasedIntegerForm f -> new Canonical("#i", NumberForms.toBigInteger(f).toString());
            case NumberForm.FloatForm f -> new Canonical("#f", floatText(f));
            case NumberForm.SpecialValueForm f -> new Canonical("#s", special(f));
        };
    }

    /** {@code text} reduced to its canonical spelling where it is a number, and returned unchanged where it is not. */
    static String textOf(String text, boolean unquoted) {
        Canonical canonical = of(text, unquoted);
        return canonical == null ? text : canonical.text();
    }

    /**
     * One spelling per magnitude whether it was written with a scale or an exponent -- {@code 1.0},
     * {@code 1.00} and {@code 1e0} are one number, so {@code stripTrailingZeros} runs before the text is
     * taken. The point is then put back where stripping removed it, which the equivalence does not need but
     * the readable half of an entry's name does: without it a float reads as {@code box_float64_1} beside an
     * integer argument's {@code box_float64_1}, two names that differ only in their hash.
     */
    private static String floatText(NumberForm.FloatForm form) {
        String text = NumberForms.toBigDecimal(form).stripTrailingZeros().toPlainString();
        return text.contains(".") ? text : text + ".0";
    }

    /**
     * {@code .inf} and {@code .infinity} are one value, and a {@code +} on either is redundant -- §4.3's
     * special values carry the same equivalence the magnitudes do. {@code .nan} is never signed by the
     * grammar, so it needs no case of its own.
     */
    private static String special(NumberForm.SpecialValueForm form) {
        if (form.kind() == NumberForm.SpecialValueForm.Kind.NAN) {
            return "nan";
        }
        return form.sign().filter(s -> s == NumberForm.Sign.MINUS).isPresent() ? "-inf" : "inf";
    }
}
