package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code sum => top & {}} base kind (Part 2 §4.1) -- every SUM-kind {@link
 * Top} variant IS-A this: {@link ChoiceBody} ({@code choice => ~sum & { variants: [type_ref] }},
 * §5.4), {@link UnknownType} ({@code unknown_type => ~sum & {}}, added 2026-07-24 -- an empty
 * sum, "the universe of types," record-only with no {@code tson-compiler} vocab compiler, per explicit
 * user direction), and {@link Extern} ({@code extern => ~sum & { schema: uri  types: [type_name]?
 * } }, added 2026-07-25, same record-only treatment). A future labelled-record/discriminated-union
 * shape, if one is ever modeled, would join them here too.
 */
public sealed interface Sum extends Top permits ChoiceBody, UnknownType, Extern {
}
