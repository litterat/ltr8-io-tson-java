package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code atom => top & {}} base kind (Part 2 §4.1) -- every ATOM-kind {@link
 * TypeBody} variant IS-A this. {@link Unit} (backing {@code value}/{@code token}/{@code void}, the
 * "atom with no constraint vocabulary"), {@link EnumBody} (backing {@code boolean} and the
 * kernel's other internal enumerations), {@link IntegerType} (backing {@code integer}), and {@link
 * TextType}/{@link UriType}/{@link RegexType} (backing {@code text}/{@code uri}/{@code regex} --
 * all four added 2026-07-23).
 */
public sealed interface Atom extends Top permits Unit, EnumBody, IntegerType, TextType, UriType, RegexType {
}
