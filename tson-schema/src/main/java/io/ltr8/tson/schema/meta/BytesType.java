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
 * <p><b>The alphabet is a selector on the type, and one type is what there is.</b> A spelling is not a kind
 * of value -- the same octets are {@code "3q2+7w=="}, {@code "deadbeef"} and {@code "3WV37Q======"} -- so
 * there is one {@code bytes} value space and {@link #encoding} picks which of RFC 4648's alphabets a *text*
 * encoding writes it in. It is a **selector** facet ([TSON-SCHEMA] §5.7): a refinement may neither set nor
 * change it, since an alphabet narrows nothing and an IS-A carrying no narrowing would claim a subtype at
 * positions no base64 reader could honour. Another alphabet is another **instance** --
 * {@code hexdigest => !bytes_type { encoding: HEX  length: 4 }} -- and refining for length inherits the
 * alphabet. An encoding whose values are octets ignores the selector entirely and writes them raw.
 *
 * <p><b>Why the type rather than an annotation</b> ([TSON-SCHEMA] §5.5): a container element has no
 * annotation position, so {@code [hexdigest]} works only if the element's own type carries the alphabet.
 * A reader must be told which one is in force because {@code "abcd"} is well-formed hex *and* well-formed
 * base64 decoding to different octets -- and the type is the only place every position can be told.
 *
 * <p>Pure constraint values, no parsing or validation behaviour: {@code tson-compiler}'s {@code
 * BytesParser} holds one of these and does the work, reading the alphabet off it.
 */
@Typename(name = "bytes_type")
public record BytesType(Encoding encoding, Optional<Integer> length,
                         @Field("min_length") Optional<Integer> minLength,
                         @Field("max_length") Optional<Integer> maxLength) implements Atom {

    /**
     * The RFC 4648 base encodings the text class of encodings may spell a {@code bytes} value in --
     * meta.tn's {@code bytes_encoding}, mirrored as a nested enum the way {@link ComplexType.Component}
     * mirrors {@code complex_component}.
     *
     * <p><b>A selector, and part of the type</b> ([TSON-SCHEMA] §5.7). It picks a spelling and never
     * changes what two values compare as: the value is the octets, and {@code "3q2+7w=="} and
     * {@code "deadbeef"} are one value written twice. But {@code "abcd"} is well-formed hex <em>and</em>
     * well-formed base64 decoding to different octets, so a text reader must be told which alphabet is in
     * force -- and the only place it can be told for a container element is the element's own type, an
     * element having no annotation position of its own.
     */
    public enum Encoding {
        BASE64, BASE64URL, BASE32, HEX
    }

    /** The unconstrained octet sequence -- {@code bytes => !bytes_type {}}, base64 by the selector's default. */
    public static final BytesType UNCONSTRAINED =
            new BytesType(Encoding.BASE64, Optional.empty(), Optional.empty(), Optional.empty());

    /** {@code UNCONSTRAINED} in {@code encoding} -- what a refinement stating only an alphabet denotes. */
    public static BytesType in(Encoding encoding) {
        return new BytesType(encoding, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The length bounds are the ordered facets. <b>{@code encoding} may not be refined at all</b>, and the
     * reason is what tells it from {@link ComplexType}'s {@code component}, which is also a selector and may
     * be: {@code component} narrows the value space (complex numbers over float64 components are a subset of
     * those over number components), where {@code encoding} narrows nothing -- every octet string is writable
     * in every alphabet. A refinement means IS-A <em>and narrower</em>, so a refinement that only respells
     * gives a subtype relationship carrying no narrowing: {@code hexbytes ^ bytes} would say every hexbytes
     * is a bytes, and a hex-spelled document is not readable at a base64 position. That degenerate IS-A is
     * the defect the four sibling {@code base64}/{@code hex} types were removed for, and permitting the
     * refinement would let an author rebuild it by hand.
     *
     * <p>The remedy names itself in the message: a different alphabet is a different type, declared as a
     * fresh instance of this constructor ({@code hexbytes => !bytes_type { encoding: HEX }}) rather than as a
     * refinement of one.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof BytesType other)) {
            return List.of("refines a bytes with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        if (encoding != other.encoding) {
            violations.add("encoding " + other.encoding + " replaces the source's own " + encoding
                    + "; an alphabet is a spelling and not a narrowing, so it is not refinable -- declare a "
                    + "new type instead (!bytes_type { encoding: " + other.encoding + " })");
        }
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
