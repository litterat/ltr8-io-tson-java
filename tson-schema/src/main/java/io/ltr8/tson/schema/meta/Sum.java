package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code sum => top & {}} base kind (Part 2 §4.1) -- every SUM-kind {@link
 * TypeBody} variant IS-A this. Currently just {@link ChoiceBody} ({@code choice => ~sum & {
 * variants: [type_ref] }}, §5.4); a future labelled-record/discriminated-union shape, if one is
 * ever modeled as its own {@code TypeBody} variant, would join it here too.
 */
public sealed interface Sum extends Top permits ChoiceBody {
}
