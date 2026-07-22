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
 * doesn't validate its own shape ahead of the JDK type it delegates to.
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

        types.put("number", DecimalType.UNCONSTRAINED);
        types.put("float32", FloatType.FLOAT32);
        types.put("float64", FloatType.FLOAT64);
        types.put("rational", RationalType.UNCONSTRAINED);
        types.put("complex", ComplexType.UNCONSTRAINED);

        types.put("uuid", UuidType.UNCONSTRAINED);

        types.put("base64", BinaryType.BASE64);
        types.put("base64url", BinaryType.BASE64URL);
        types.put("base32", BinaryType.BASE32);
        types.put("hex", BinaryType.HEX);

        types.put("date", DateType.UNCONSTRAINED);
        types.put("time", TimeType.UNCONSTRAINED);
        types.put("datetime", DateTimeType.UNCONSTRAINED);
        types.put("duration", DurationType.UNCONSTRAINED);

        types.put("uri", UriType.UNCONSTRAINED);

        return Map.copyOf(types);
    }
}
