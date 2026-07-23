package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.schema.meta.IntegerSize;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The built-in type vocabulary's name -&gt; {@link AtomType} table (§5) -- a hardcoded
 * transliteration of the relevant {@code core.tn1} instances, since the vocabulary is a fixed,
 * closed set (§5.1) that a Class 1 processor never resolves via schema machinery.
 *
 * <p>Seeded with the {@code integer_type} family (see {@code SPEC-FEEDBACK.md} #6): §5.6 as
 * published lists just {@code int32}/{@code int64}/{@code uint32}/{@code uint64}, but {@code
 * core.tn1} defines the same constructor applied across the full {@code int8}..{@code int256}/
 * {@code uint8}..{@code uint256} width ladder plus the {@code positive_integer} / {@code
 * non_negative_integer} / {@code negative_integer} / {@code non_positive_integer} bound-only
 * refinements, and omitting the other eight widths and the refinement family from §5.6's table was
 * confirmed as an oversight, not deliberate scoping -- this implementation exposes the full family
 * {@code core.tn1} defines, not just the four the current table happens to list.
 *
 * <p>Also seeded with {@code decimal_type} ({@code number}), {@code float_type} ({@code float32}/
 * {@code float64}), {@code rational_type} ({@code rational}), and {@code complex_type} ({@code
 * complex}) -- all fully published in §5.6's table as-is, unlike the integer family. And with {@code
 * uuid_type} ({@code uuid}, §5.5) -- deliberately *not* {@code text_type}, despite existing in
 * meta-kernel.tn1, since {@code !text} never appears in §5's published table at all (see {@code
 * SPEC-FEEDBACK.md} #9). And with the full {@code binary} family (§5.3) -- {@code base64}, {@code
 * base64url}, {@code base32}, {@code hex} -- four instances of one {@link BinaryParser} constructor,
 * each a distinct {@code binary_encoding} value, not one generic {@code !binary} annotation,
 * matching §5.3's own "there is no generic {@code !binary} annotation." And with the temporal
 * family (§5.4) -- {@code date_type} ({@code date}), {@code time_type} ({@code time}), {@code
 * datetime_type} ({@code datetime}), {@code duration_type} ({@code duration}). And with {@code
 * uri_type} ({@code uri}, §5.5) -- see {@link UriParser}'s Javadoc for why it's the one atom here that
 * doesn't validate its own shape ahead of the JDK type it delegates to. And with {@code ipv4_type}
 * ({@code ipv4}, §5.5) -- see {@link Ipv4Parser}'s Javadoc for why its JDK leniency gap is a real
 * SSRF-adjacent concern, not just a spec-fidelity one, and how that's handled. And with {@code
 * ipv6_type} ({@code ipv6}, §5.5) -- a hand-rolled RFC 4291 §2.2 parser for the same reason, see
 * {@link Ipv6Parser}'s Javadoc.
 */
public final class BuiltinTypeVocabulary {

    private static final int[] INTEGER_WIDTHS = {8, 16, 32, 64, 128, 256};

    private static final Map<String, AtomType<?>> TYPES = buildVocabulary();

    private BuiltinTypeVocabulary() {
    }

    public static Optional<AtomType<?>> lookup(String typeRef) {
        return Optional.ofNullable(TYPES.get(typeRef));
    }

    private static Map<String, AtomType<?>> buildVocabulary() {
        Map<String, AtomType<?>> types = new HashMap<>();
        for (int bits : INTEGER_WIDTHS) {
            types.put("int" + bits, new IntegerParser(new IntegerSize(bits, true)));
            types.put("uint" + bits, new IntegerParser(new IntegerSize(bits, false)));
        }
        types.put("positive_integer", IntegerParser.ofMin(BigInteger.ONE));
        types.put("non_negative_integer", IntegerParser.ofMin(BigInteger.ZERO));
        types.put("negative_integer", IntegerParser.ofMax(BigInteger.valueOf(-1)));
        types.put("non_positive_integer", IntegerParser.ofMax(BigInteger.ZERO));

        types.put(DecimalParser.TYPENAME, DecimalParser.UNCONSTRAINED);
        types.put(FloatParser.FLOAT32.typeName(), FloatParser.FLOAT32);
        types.put(FloatParser.FLOAT64.typeName(), FloatParser.FLOAT64);
        types.put(RationalParser.TYPENAME, RationalParser.UNCONSTRAINED);
        types.put(ComplexParser.TYPENAME, ComplexParser.UNCONSTRAINED);

        types.put(UuidParser.TYPENAME, UuidParser.UNCONSTRAINED);

        types.put(BinaryParser.BASE64.typeName(), BinaryParser.BASE64);
        types.put(BinaryParser.BASE64URL.typeName(), BinaryParser.BASE64URL);
        types.put(BinaryParser.BASE32.typeName(), BinaryParser.BASE32);
        types.put(BinaryParser.HEX.typeName(), BinaryParser.HEX);

        types.put(DateParser.TYPENAME, DateParser.UNCONSTRAINED);
        types.put(TimeParser.TYPENAME, TimeParser.UNCONSTRAINED);
        types.put(DateTimeParser.TYPENAME, DateTimeParser.UNCONSTRAINED);
        types.put(DurationParser.TYPENAME, DurationParser.UNCONSTRAINED);

        types.put(UriParser.TYPENAME, UriParser.UNCONSTRAINED);

        types.put(Ipv4Parser.TYPENAME, Ipv4Parser.UNCONSTRAINED);
        types.put(Ipv6Parser.TYPENAME, Ipv6Parser.UNCONSTRAINED);

        return Map.copyOf(types);
    }
}
