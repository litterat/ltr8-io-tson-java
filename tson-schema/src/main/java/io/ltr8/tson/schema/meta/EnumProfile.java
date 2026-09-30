package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code enum_profile} enum -- which lexical profile an enum's members lie in.
 * {@link #IDENTIFIER} is the default and is omitted from resolver output text ("Fields at their default
 * values are omitted", {@code meta-kernel-resolved.tn}'s own conventions note).
 *
 * <p><b>The two say what kind of enumeration this is</b>, which the member list alone cannot: a vocabulary
 * of names, or a set of values. Under {@link #IDENTIFIER} every member matches [TSON-DATA] §7.7's grammar,
 * so members are a named scope for all three of §8.2's hygiene mechanisms, every member is writable
 * unquoted, and a host enum constant exists for each. Under {@link #TEXT} the members are data: §8.2's
 * per-name rules do not reach them, and the binding is host text.
 *
 * <p><b>{@link #IDENTIFIER} is inside {@link #TEXT}</b>, which is the narrowing relation §5.7 requires a
 * selector facet's family to state: a refinement may move {@link #TEXT} to {@link #IDENTIFIER} and never the
 * reverse. [TSON-DATA] §7.1's unquoted-token profile sits between the two and is deliberately not a member
 * here -- it would buy spelling and neither binding nor hygiene, and {@link #TEXT} already admits its
 * population at the cost of quotes. That it *could* be added along the same relation is why this is an enum
 * rather than a boolean.
 */
public enum EnumProfile {
    IDENTIFIER, TEXT
}
