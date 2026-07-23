package io.ltr8.tson.schema.meta;

import java.math.BigInteger;
import java.util.Objects;

/**
 * meta-kernel's {@code rational} host value -- an exact fraction, {@code numerator}/{@code
 * denominator}, {@code denominator} always strictly positive (§7.6's grammar never permits a
 * negative or zero denominator; any sign belongs to the numerator). Lives in {@code schema.meta},
 * not {@code tson-parser}, because {@code RationalType}'s own {@code min}/{@code max}/{@code
 * multipleOf} constraint fields are typed as this.
 *
 * <p><b>Not normalized, but compared by value.</b> meta.tn1's own doc for {@code rational_type} is
 * explicit: "the token is preserved as written and 2/4 round-trips as 2/4... equality and
 * constraints operate on the value (2/4 equals 1/2)." That's why this can't use the record default
 * {@code equals}/{@code hashCode} -- those are field-based ({@code 2/4} and {@code 1/2} would
 * compare unequal), so {@link #equals}/{@link #hashCode}/{@link #compareTo} are overridden to
 * compare by cross-multiplication (safe without normalizing first, since {@code denominator} is
 * always positive), while the record's own {@code numerator()}/{@code denominator()} accessors
 * still expose the exact fields as written, preserving round-trip fidelity for anything that wants
 * it (e.g. re-serializing back to TSON text).
 *
 * <p>This is deliberately a minimal value type, not a full arithmetic library (no {@code plus}/
 * {@code times}/reduction methods). <b>{@code tson-mapper}'s {@code TsonMapper} cannot bind directly
 * to this class at all</b> -- it's itself a Java record, so {@code tson-bind}'s record
 * auto-detection claims it ahead of anything atom/vocabulary-related, and a {@code !rational}
 * value's token content (not a record) fails to satisfy a record target. The supported way to bind
 * {@code !rational} to a Java field, including to this class's own shape if that's genuinely what's
 * wanted, is a {@code DataBridge<Rational, TheirType>} registered via {@code
 * DataBindContext.registerAtom(TheirType.class, bridge)} ({@code tson-bind}) -- the natural fit for
 * an application that already has a richer rational type (e.g. Apache Commons Math's {@code
 * BigFraction}), and currently the *only* fit, full stop. This class stays in {@code tson-schema},
 * which has no dependency on {@code tson-bind} at all -- see {@code RationalType}'s Javadoc.
 */
public record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {

    public Rational {
        if (denominator.signum() <= 0) {
            throw new IllegalArgumentException("denominator must be positive, was " + denominator);
        }
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Rational other
                && numerator.multiply(other.denominator).equals(other.numerator.multiply(denominator));
    }

    @Override
    public int hashCode() {
        // Reduced first -- equals() is value-based (2/4 == 1/2), so hashCode must agree for equal
        // values regardless of how they were originally written, per the equals/hashCode contract.
        if (numerator.signum() == 0) {
            return 0;
        }
        BigInteger gcd = numerator.gcd(denominator);
        return Objects.hash(numerator.divide(gcd), denominator.divide(gcd));
    }

    @Override
    public int compareTo(Rational other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
