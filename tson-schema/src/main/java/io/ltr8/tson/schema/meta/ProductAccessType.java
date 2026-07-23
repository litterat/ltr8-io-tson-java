package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code product_access_type} enum (Part 2 §4.1, §8.1) -- {@code record}'s own
 * {@code access_pattern} is fixed to {@code NAMED} and {@code array}/{@code map}/{@code tuple}'s to
 * {@code INDEX}; neither ever varies per instance, which is why {@link RecordBody}/{@link
 * ArrayBody}/{@link MapBody}/{@link TupleBody} don't carry this value at all -- it's implied by
 * which body type is in play, not a field on any of them.
 */
public enum ProductAccessType {
    INDEX, NAMED
}
