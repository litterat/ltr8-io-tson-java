package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The kernel's {@code alias} constructor's own vocabulary, resolved (Part 2 §4.1, §8.1): a {@code kind: ALIAS} entry's
 * body, {@code !meta.alias { target: E }} -- the kernel's aliasing shape, used by
 * {@code meta.type_name}/{@code meta.field_name}/{@code meta.param_name} (aliasing {@code meta.identifier}), the
 * annotation markers {@code annotation}/{@code doc}/{@code synthetic}, and the root's {@code set}, an alias of
 * {@code meta.set_type}. It composes {@code top} directly and so carries no IS-A chain: a hop is not a type of its
 * own.
 *
 * <p><b>{@code target} is a reference, typed {@code meta.type_ref}.</b> A resolved alias never carries arguments:
 * resolution closes {@code text_box => box<text>} into the entry it denotes under the declared name, and an
 * application at a target closes to the entry it denotes as at any type position. No alias has parameters: a
 * template's body is a record ([TSON-SCHEMA] §5.10). §8.3 flattens a use site past an ALIAS entry but never this slot:
 * the chain has to stay walkable.
 */
@Typename(name = "meta.alias")
public record Alias(TypeRef target) implements Top {
}
