package io.ltr8.tson.parser.resolver.vocab;

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
 * base64url}, {@code base32}, {@code hex} -- four instances of one {@link BinaryType} constructor,
 * each a distinct {@code binary_encoding} value, not one generic {@code !binary} annotation,
 * matching §5.3's own "there is no generic {@code !binary} annotation." And with the temporal
 * family (§5.4) -- {@code date_type} ({@code date}), {@code time_type} ({@code time}), {@code
 * datetime_type} ({@code datetime}), {@code duration_type} ({@code duration}). And with {@code
 * uri_type} ({@code uri}, §5.5) -- see {@link UriType}'s Javadoc for why it's the one atom here that
 * doesn't validate its own shape ahead of the JDK type it delegates to. And with {@code ipv4_type}
 * ({@code ipv4}, §5.5) -- see {@link Ipv4Type}'s Javadoc for why its JDK leniency gap is a real
 * SSRF-adjacent concern, not just a spec-fidelity one, and how that's handled. And with {@code
 * ipv6_type} ({@code ipv6}, §5.5) -- a hand-rolled RFC 4291 §2.2 parser for the same reason, see
 * {@link Ipv6Type}'s Javadoc.
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
            types.put("int" + bits, new IntegerType(new IntegerSize(bits, true)));
            types.put("uint" + bits, new IntegerType(new IntegerSize(bits, false)));
        }
        types.put("positive_integer", IntegerType.ofMin(BigInteger.ONE));
        types.put("non_negative_integer", IntegerType.ofMin(BigInteger.ZERO));
        types.put("negative_integer", IntegerType.ofMax(BigInteger.valueOf(-1)));
        types.put("non_positive_integer", IntegerType.ofMax(BigInteger.ZERO));

        types.put(DecimalType.TYPENAME, DecimalType.UNCONSTRAINED);
        types.put(FloatType.FLOAT32.typeName(), FloatType.FLOAT32);
        types.put(FloatType.FLOAT64.typeName(), FloatType.FLOAT64);
        types.put(RationalType.TYPENAME, RationalType.UNCONSTRAINED);
        types.put(ComplexType.TYPENAME, ComplexType.UNCONSTRAINED);

        types.put(UuidType.TYPENAME, UuidType.UNCONSTRAINED);

        types.put(BinaryType.BASE64.typeName(), BinaryType.BASE64);
        types.put(BinaryType.BASE64URL.typeName(), BinaryType.BASE64URL);
        types.put(BinaryType.BASE32.typeName(), BinaryType.BASE32);
        types.put(BinaryType.HEX.typeName(), BinaryType.HEX);

        types.put(DateType.TYPENAME, DateType.UNCONSTRAINED);
        types.put(TimeType.TYPENAME, TimeType.UNCONSTRAINED);
        types.put(DateTimeType.TYPENAME, DateTimeType.UNCONSTRAINED);
        types.put(DurationType.TYPENAME, DurationType.UNCONSTRAINED);

        types.put(UriType.TYPENAME, UriType.UNCONSTRAINED);

        types.put(Ipv4Type.TYPENAME, Ipv4Type.UNCONSTRAINED);
        types.put(Ipv6Type.TYPENAME, Ipv6Type.UNCONSTRAINED);

        return Map.copyOf(types);
    }
}
