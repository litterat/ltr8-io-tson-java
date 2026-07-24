package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code atom => top & {}} base kind (Part 2 §4.1) -- every ATOM-kind {@link
 * Top} variant IS-A this. {@link Unit} (backing {@code value}/{@code token}/{@code void}, the
 * "atom with no constraint vocabulary"), {@link EnumBody} (backing {@code boolean} and the
 * kernel's other internal enumerations), {@link IntegerType} (backing {@code integer}), {@link
 * TextType}/{@link UriType}/{@link RegexType} (backing {@code text}/{@code uri}/{@code regex} --
 * all four added 2026-07-23), and {@link DecimalType}/{@link FloatType}/{@link RationalType}/
 * {@link UuidType}/{@link BinaryType}/{@link DateType}/{@link TimeType}/{@link DateTimeType}/
 * {@link DurationType} (the remaining atom constraint-vocabulary families, joined 2026-07-24).
 */
public sealed interface Atom extends Top permits Unit, EnumBody, IntegerType, TextType, UriType, RegexType,
        DecimalType, FloatType, RationalType, UuidType, BinaryType, DateType, TimeType, DateTimeType, DurationType {
}
