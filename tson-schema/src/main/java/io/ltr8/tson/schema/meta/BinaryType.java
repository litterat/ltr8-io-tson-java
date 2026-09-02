package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * meta.tn's {@code binary} constructor (§5.3's four binary atoms, RFC 4648) -- one class, not one
 * per encoding: {@code binary}'s only field beyond the RFC pin is {@code encoding: binary_encoding},
 * a closed four-value enum, exactly the same shape as {@link IntegerType}'s {@code size} or {@link
 * FloatType}'s {@code format} -- a single constructor parameterized by one of its own fields, not
 * four different constructors. Pure constraint values, no parsing/validation behavior -- {@code
 * tson-compiler}'s {@code BinaryParser} holds one of these and does the actual reading/writing.
 *
 * <p>Named {@code BinaryType} here despite meta.tn's constructor being spelled {@code binary}, not
 * {@code binary_type} like every other constructor. The
 * {@code @Typename} below is {@code "binary"} to match, not {@code "binary_type"}.
 *
 * <p>{@code minLength}/{@code maxLength} are modeled for structural fidelity (meta.tn defines
 * them on the constructor) but unexercised by any built-in instance, the same as {@link
 * FloatType}'s bounds -- {@code base64 => !binary BASE64} and its three siblings in core.tn are all
 * unconstrained beyond {@code encoding}.
 *
 * <p>Also an {@link Atom} variant: {@code base64 => !binary BASE64} and its
 * three siblings are constructor-application instances (§5.5) whose resolved bodies are exactly
 * {@link #BASE64}/{@link #BASE64URL}/{@link #BASE32}/{@link #HEX} -- each a positional-form
 * instance (§5.6: a bare token filling {@code binary}'s sole {@code REQUIRED} field, {@code
 * encoding}), not a braced body.
 */
@Typename(name = "binary_type")
public record BinaryType(Encoding encoding, Optional<Integer> length,
                          @Field("min_length") Optional<Integer> minLength,
                          @Field("max_length") Optional<Integer> maxLength) implements Atom {

    public enum Encoding {
        BASE64("base64"), BASE64URL("base64url"), BASE32("base32"), HEX("hex");

        private final String typeName;

        Encoding(String typeName) {
            this.typeName = typeName;
        }

        /** §5.3's built-in annotation name for this encoding, e.g. {@code !base64}. */
        public String typeName() {
            return typeName;
        }
    }

    /**
     * Carries {@code @Record} because the convenience constructor below is a second public one, and
     * {@code tson-bind}'s constructor selection fails outright without it (see {@link IntegerSize}).
     */
    @Record
    public BinaryType {
    }

    /** A spelled instance with length bounds and no exact length -- the common shape. */
    public BinaryType(Encoding encoding, Optional<Integer> minLength, Optional<Integer> maxLength) {
        this(encoding, Optional.empty(), minLength, maxLength);
    }

    /** {@code bytes => !binary_type {}} -- the unrefined instance, which takes the constructor's default. */
    public static final BinaryType UNSPELLED = of(Encoding.BASE64);

    /** {@code base64 => !bytes ^ { encoding: BASE64 }}, and so on for the other three. */
    public static final BinaryType BASE64 = of(Encoding.BASE64);
    public static final BinaryType BASE64URL = of(Encoding.BASE64URL);
    public static final BinaryType BASE32 = of(Encoding.BASE32);
    public static final BinaryType HEX = of(Encoding.HEX);

    private static BinaryType of(Encoding encoding) {
        return new BinaryType(encoding, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** §5.5's built-in annotation name for this instance's {@link #encoding}, e.g. {@code !base64}. */
    public String typeName() {
        return encoding.typeName();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only the length bounds are ordered facets. {@link #encoding} is a selector -- see {@link
     * ComplexType} for why selectors are left unchecked -- and in practice unreachable by a
     * refinement anyway, since every core.tn instance already fixes it.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof BinaryType other)) {
            return List.of("refines a binary with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkAtLeast(violations, "min_length", minLength, other.minLength);
        AtomNarrowing.checkAtMost(violations, "max_length", maxLength, other.maxLength);
        if (length.isPresent() && !length.equals(other.length)) {
            violations.add("length " + other.length.map(String::valueOf).orElse("(unset)")
                    + " replaces the source's own " + length.get() + "; an exact length is not re-settable");
        }
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same two length facets, on the same terms as {@link TextType#coherenceCheck} -- minus
     * its {@code length}, which this family does not carry. Lengths count decoded bytes rather than
     * code points, which changes what the numbers mean but not whether a floor above a ceiling admits
     * anything.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkNonNegative(violations, "min_length", minLength);
        AtomCoherence.checkNonNegative(violations, "max_length", maxLength);
        AtomCoherence.checkOrdered(violations, "min_length", minLength, "max_length", maxLength);
        AtomCoherence.checkNonNegative(violations, "length", length);
        // An exact length and a range are individually legal and jointly empty whenever the one falls
        // outside the other -- the same shape as a member set against its family's bounds.
        length.ifPresent(exact -> {
            minLength.filter(min -> exact < min).ifPresent(min ->
                    violations.add("length " + exact + " is below min_length " + min));
            maxLength.filter(max -> exact > max).ifPresent(max ->
                    violations.add("length " + exact + " is above max_length " + max));
        });
        return List.copyOf(violations);
    }
}
