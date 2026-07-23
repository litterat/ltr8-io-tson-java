package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code product_size_type} enum (Part 2 §4.1, §8.1) -- like {@link
 * ProductAccessType}, fixed per product constructor ({@code record}/{@code tuple} are {@code
 * FIXED}; {@code array}/{@code map} are {@code VARIABLE}) and so, like it, implied by the body
 * type rather than carried as a field on {@link RecordBody}/{@link ArrayBody}/{@link MapBody}/
 * {@link TupleBody}.
 */
public enum ProductSizeType {
    FIXED, VARIABLE
}
