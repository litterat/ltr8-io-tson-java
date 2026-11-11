package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The kernel's {@code alias} constructor's own vocabulary, resolved (Part 2 §4.1, §8.1): a {@code kind: ALIAS} entry's
 * body, {@code !meta.alias { target: E }} -- the kernel's aliasing shape, used by
 * {@code meta.type_name}/{@code meta.field_name}/{@code meta.param_name} (aliasing {@code meta.identifier}), the
 * annotation markers {@code annotation}/{@code doc}, and the root's {@code set}, an alias of
 * {@code meta.set_type}. It composes {@code top} directly and so carries no IS-A chain: a hop is not a type of its
 * own.
 *
 * <p><b>{@code target} is a reference, typed {@code meta.type_ref}, and only a name stands there</b> ([TSON-SCHEMA]
 * §4.2). A declaration that names an application, {@code text_box => box<text>}, is no alias: it is the record the
 * application closes to, under the declared name. An application written at a target closes nowhere and is refused.
 * No alias has parameters: a template's body is a record ([TSON-SCHEMA] §5.10). §8.3 flattens a use site past an
 * ALIAS entry but never this slot: the chain has to stay walkable.
 */
@Typename(name = "meta.alias")
public record Alias(TypeRef target) implements Top {
}
