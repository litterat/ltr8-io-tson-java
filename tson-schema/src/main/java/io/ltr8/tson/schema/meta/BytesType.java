package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * meta.tn's {@code bytes_type} constructor: an octet sequence, and the only binary type there is.
 * Instance is {@code bytes} in core.
 *
 * <p><b>The value is the octets, and the facets are over octets.</b> Equality, identity, content
 * addressing and the length bounds all are -- {@code length: 32} is a 32-byte digest whether it arrives
 * as 64 hex characters, 44 base64 characters or 32 raw bytes -- so a round trip through any encoding
 * preserves the value.
 *
 * <p><b>The alphabet is not here, and that is the design.</b> A spelling is not a kind of value: the same
 * octets are {@code "3q2+7w=="}, {@code "deadbeef"} and {@code "3WV37Q======"}. Carrying the alphabet as a
 * facet made four types over one value space, related by an IS-A that narrowed nothing; carrying it as a
 * type-ref made a lexical fact into a type claim. It is a directive instead -- meta.tn's
 * {@code @bytes_encoding}, resolved nearest-first (the field, then the field's type walking its supertypes,
 * then base64) and read only by encodings whose values are character sequences. This type says nothing
 * about how octets are spelled, as {@link TextType} says nothing about UTF-8.
 *
 * <p>Pure constraint values, no parsing or validation behaviour: {@code tson-compiler}'s {@code
 * BytesParser} holds one of these, plus the alphabet it was told to read in, and does the work.
 */
@Typename(name = "bytes_type")
public record BytesType(Optional<Integer> length,
                         @Field("min_length") Optional<Integer> minLength,
                         @Field("max_length") Optional<Integer> maxLength) implements Atom {

    /** The unconstrained octet sequence -- {@code bytes => !bytes_type {}}. */
    public static final BytesType UNCONSTRAINED =
            new BytesType(Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>Only the length bounds are ordered facets, and they are all this family has: the alphabet a text
     * encoding spells the octets in is not a facet of the type at all but the {@code @bytes_encoding}
     * directive's, so there is no selector here to narrow.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof BytesType other)) {
            return List.of("refines a bytes with " + refined.getClass().getSimpleName());
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
