package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code product => top & { access_pattern: ... size_type: ... } }} base kind
 * (Part 2 §4.1) -- every PRODUCT-kind {@link TypeBody} variant IS-A this: {@link RecordBody},
 * {@link ArrayBody} (and its closures {@code set}/{@code array_min}/{@code array_max}/{@code
 * array_ranged}), {@link MapBody}, and {@link TupleBody} -- exactly {@code record}/{@code array}/
 * {@code set}/{@code map}/{@code tuple}, the kernel's own structural-type family (Part 2 §4.1: "record,
 * array, set, map, and tuple compose with product, fixing access_pattern and size_type").
 */
public sealed interface Product extends Top permits RecordBody, ArrayBody, MapBody, TupleBody {
}
