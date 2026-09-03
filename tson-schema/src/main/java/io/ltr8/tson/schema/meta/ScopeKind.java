package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * Which namespace a value's type may be drawn from -- meta.tn's {@code scope_kind} enum, the element type
 * of {@link Scoped#scope}.
 *
 * <p>The two cells are independent questions about a position, which is why they are a set rather than a
 * three-valued selector: a position may admit either, both, or -- unrepresentably, {@code scope} carrying
 * {@code min_items: 1} -- neither.
 */
@Typename(name = "scope_kind")
public enum ScopeKind {

    /** The governing namespace: the schema's own declarations and its imports ([TSON-SCHEMA] §2.2.3). */
    LOCAL,

    /** A foreign schema, named by a nested {@code !!schema} on the value itself ([TSON-DATA] §2.3). */
    EXTERN
}
