package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The meta-kernel's {@code reference} constructor's own vocabulary, resolved (Part 2 §4.1, §8.1):
 * a {@code kind: REFERENCE} entry's body, {@code !reference { target: E }} -- the kernel's aliasing
 * shape used directly by {@code type_name}/{@code field_name}/{@code param_name} (aliasing {@code
 * token}), the annotation markers {@code annotation}/{@code documentation}/{@code doc}/{@code
 * alias}, and (later) materialised template instantiations (§5.10, §8.2). For a simple alias
 * {@code target} equals the entry's own {@code source}; see {@link TypeDefinition#reference}.
 *
 * <p><b>{@code target} is a {@link TypeRef}, so an alias to an application states its own arguments.</b>
 * [TSON-SCHEMA] §5.10's partial application, {@code uuid_pair => <B> pair<uuid, B>}, is an alias whose target
 * carries an argument list; a name-typed slot had nowhere to put one, so the arguments had to be read back
 * from the entry's {@code source} -- which §8.2 keys identity on, giving one component two jobs and letting
 * an alias exist with no way to say what it aliased. The body says it now, and {@code source} is provenance
 * only.
 *
 * <p><b>A closed alias never carries arguments.</b> Materialisation rewrites {@code text_box => box<text>} to
 * name the entry it minted, so an argument-bearing target appears only where an application is still open --
 * inside a template. §8.3 flattens a use site past a REFERENCE entry but never this slot: the chain has to
 * stay walkable, and the walk stops at an argument-bearing target, which is an application rather than a
 * further hop.
 */
@Typename(name = "reference")
public record Reference(TypeRef target) implements Top {
}
