package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code record_extension_type} enum (Part 2 §5.2, §8.1) -- how a record may be realised.
 * {@link #OPEN} is the default and is omitted from resolver output text ("Fields at their default values are
 * omitted", {@code meta-kernel-resolved.tn}'s own conventions note).
 *
 * <p>{@link #OPEN} is the ordinary case: the record has instances of its own and may be composed onto or
 * refined. {@link #ABSTRACT} has none of its own, so a value at a position typed by it is a value of some
 * subtype. {@link #SEALED} is {@link #ABSTRACT} with at least one {@code RecordField#discriminator()} field,
 * which makes the subtype recoverable from the value's own members; it is <b>derived from the body</b> rather
 * than stated, as {@code choice.disjoint} is (§5.4). {@link #FINAL} has instances and admits no subtype: no
 * composition or refinement may name it as a source. Subtraction stays admissible against a {@link #FINAL}
 * record -- §5.9 empties {@code supertypes} and mints no IS-A edge, which is the only thing {@link #FINAL}
 * constrains.
 *
 * <p><b>Extensibility is never inherited</b>, so there is no transition table: a subtype of an {@link
 * #ABSTRACT} or {@link #SEALED} record is {@link #OPEN} unless it says otherwise. Carrying abstractness
 * downward would leave a family with no concrete member at all.
 */
public enum RecordExtensionType {
    ABSTRACT, SEALED, FINAL, OPEN
}
