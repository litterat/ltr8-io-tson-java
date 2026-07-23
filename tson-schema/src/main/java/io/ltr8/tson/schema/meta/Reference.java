package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The meta-kernel's {@code reference} constructor's own vocabulary, resolved (Part 2 §4.1, §8.1):
 * a {@code kind: REFERENCE} entry's body, {@code !reference { target: E }} -- the kernel's aliasing
 * shape used directly by {@code type_name}/{@code field_name}/{@code param_name} (aliasing {@code
 * token}), the annotation markers {@code annotation}/{@code documentation}/{@code doc}/{@code
 * alias}, and (later) materialised template instantiations (§5.10, §8.2). For a simple alias
 * {@code target} equals the entry's own {@code source}; see {@link TypeDefinition#reference}.
 */
@Typename(name = "reference")
public record Reference(TypeRef target) implements TypeBody, Top {
}
