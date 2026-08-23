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
 * <p><b>{@code target} is a name, not a {@link TypeRef}</b>, because the kernel declares it {@code target:
 * type_name} -- a bare token, with no argument list. The distinction is not cosmetic: an application
 * ({@code pair<uuid, B>}) is a thing a reference body has no channel to hold, so a model that typed this as
 * a {@code TypeRef} admitted a state no conforming document can carry. Where an alias names an application
 * -- [TSON-SCHEMA] §5.10's partial application, {@code uuid_pair => <B> pair<uuid, B>} -- the arguments live
 * in the entry's own {@code source}, which <em>is</em> a {@code type_ref}, and this holds the head alone.
 */
@Typename(name = "reference")
public record Reference(String target) implements Top {
}
