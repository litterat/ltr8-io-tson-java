package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The kernel's {@code alias} constructor's own vocabulary, resolved (Part 2 §4.1, §8.1): a {@code kind: ALIAS} entry's
 * body, {@code !meta.alias { target: E }} -- the kernel's aliasing shape, used by
 * {@code meta.type_name}/{@code meta.field_name}/{@code meta.param_name} (aliasing {@code meta.identifier}), the
 * annotation markers {@code annotation}/{@code doc}/{@code synthetic}, and the instantiation entry of a non-record
 * template application whose expansion names itself (§5.10, §8.2). It composes {@code top} directly and so carries no
 * IS-A chain: a hop is not a type of its own.
 *
 * <p><b>{@code target} is a reference, typed {@code meta.type_ref}, so an alias to an application states its own
 * arguments.</b> A reference may carry an argument list (SPEC-FEEDBACK.md #12): [TSON-SCHEMA] §5.10's partial
 * application, {@code uuid_pair => <B> pair<uuid, B>}, is an alias whose target is the reference {@code
 * pair<uuid B>}, written unquoted ([TSON-DATA] §7.4).
 *
 * <p><b>A closed alias never carries arguments.</b> Materialisation rewrites {@code text_box => box<text>} to
 * name the entry it minted, so an argument-bearing target appears only where an application is still open --
 * inside a template. §8.3 flattens a use site past an ALIAS entry but never this slot: the chain has to
 * stay walkable, and the walk stops at an argument-bearing target, which is an application rather than a
 * further hop.
 */
@Typename(name = "meta.alias")
public record Alias(TypeRef target) implements Top {
}
