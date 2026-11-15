package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The kernel's {@code extern} constructor's vocabulary ([TSON-SCHEMA] §5.13): one entry of another schema under a local
 * name, {@code order_v1 => !meta.extern { schema: "https://example.com/order-1.tn"  type: order }}. Like {@link Alias}
 * it composes {@code top} directly and carries no chain: it is a hop, and its identity is the foreign entry's (§8.3).
 *
 * <p>{@code type} is text and not a {@link TypeRef}: a reference is judged in the declaring document's namespace, which
 * holds none of the foreign schema's names. What it reaches there is a fact of the link
 * ({@link io.ltr8.tson.schema.TsonLinkedSchema#externs()}), never of this value.
 */
@Typename(name = "meta.extern")
public record Extern(String schema, String type) implements Top {
}
