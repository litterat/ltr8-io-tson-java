package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.ComplexForm;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberForms;
import io.ltr8.tson.parser.resolver.NumberGrammar;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * meta-kernel's {@code complex_type} constructor (§5.6's {@code complex} atom), {@code component}
 * fixed at its default ({@code NUMBER}, exact {@link BigDecimal}) -- see {@link Complex}'s Javadoc
 * for why the other {@code complex_component} members aren't modeled yet. No fields to configure as
 * a result (unlike every other atom type here): {@code complex_type} itself has none besides {@code
 * component}, and {@code complex} has no useful total order to bound (meta.tn1: "{@code
 * @ordered:NONE}"), so this is a stateless singleton rather than a record with constraint fields.
 *
 * <p>Accepts {@code complex}/{@code float}/{@code integer} forms (§7.6, §5.6) -- not {@code
 * based-integer}, not hex-float, not the special values. A bare {@code integer}/{@code float} token
 * (no {@code imag-unit}) is a real-only complex number, imaginary part zero.
 *
 * <p>Has exactly one legitimate host representation ({@link Complex} itself), so like {@link
 * RationalType} this doesn't override {@link #read(TokenValue, Class)} -- {@link AtomType}'s default
 * already covers the {@code target == Complex.class} case a {@code tson-mapper} {@code DataBridge}
 * registration relies on (see {@link Complex}'s Javadoc).
 */
public record ComplexType() implements AtomType<Complex> {

    /** §5.6's built-in annotation name -- {@code !complex}. */
    public static final String TYPENAME = "complex";

    public static final ComplexType UNCONSTRAINED = new ComplexType();

    @Override
    public Complex read(TokenValue token) {
        String text = token.text();

        Optional<ComplexForm> complexForm = NumberGrammar.tryComplex(text);
        if (complexForm.isPresent()) {
            ComplexForm f = complexForm.get();
            BigDecimal real = f.realMagnitude()
                    .map(mag -> applySign(f.realSign(), toBigDecimal(reparseMagnitude(mag))))
                    .orElse(BigDecimal.ZERO);
            BigDecimal imaginary = applySign(f.imaginarySign(), toBigDecimal(reparseMagnitude(f.imaginaryMagnitude())));
            return new Complex(real, imaginary);
        }

        NumberForm form = NumberGrammar.tryParse(text)
                .filter(f -> f instanceof NumberForm.IntegerForm || f instanceof NumberForm.FloatForm)
                .orElseThrow(() -> new AtomParseException("'" + text + "' is not a valid complex number -- "
                        + "only complex, integer, and float forms are accepted (§5.6)"));
        return new Complex(toBigDecimal(form), BigDecimal.ZERO);
    }

    /**
     * {@code [sign] magnitude sign magnitude i} -- the middle sign is always written explicitly
     * (§7.6 requires it on read too), the real part's own leading sign only when negative ({@link
     * BigDecimal#toPlainString()} already supplies it). {@code toPlainString()}, not {@code
     * toString()}, to avoid landing an {@code E}-notation form inside a grammar that permits it but
     * doesn't need it here.
     */
    @Override
    public String write(Complex value) {
        String sign = value.imaginary().signum() < 0 ? "-" : "+";
        return value.real().toPlainString() + sign + value.imaginary().abs().toPlainString() + "i";
    }

    /** {@link ComplexForm}'s magnitude substrings are unsigned integer/float text -- re-parsed via {@link NumberGrammar#tryParse} rather than duplicating digit extraction (see {@code ComplexForm}'s Javadoc). */
    private static NumberForm reparseMagnitude(String magnitude) {
        return NumberGrammar.tryParse(magnitude)
                .orElseThrow(() -> new IllegalStateException("magnitude substring failed to re-parse: " + magnitude));
    }

    private static BigDecimal toBigDecimal(NumberForm form) {
        return (form instanceof NumberForm.IntegerForm intForm)
                ? new BigDecimal(NumberForms.toBigInteger(intForm))
                : NumberForms.toBigDecimal((NumberForm.FloatForm) form);
    }

    private static BigDecimal applySign(Optional<NumberForm.Sign> sign, BigDecimal magnitude) {
        return sign.filter(s -> s == NumberForm.Sign.MINUS).isPresent() ? magnitude.negate() : magnitude;
    }
}
