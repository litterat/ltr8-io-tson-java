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
 * <p>Also seeded with {@code decimal_type} ({@code number}) and {@code float_type} ({@code float32}/
 * {@code float64}), both fully published in §5.6's table as-is, unlike the integer family.
 *
 * <p>{@code rational} and {@code complex} (backed by {@code rational_type}/{@code complex_type}) are
 * not yet in this table -- separate constructors, separate work.
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

        return Map.copyOf(types);
    }
}
