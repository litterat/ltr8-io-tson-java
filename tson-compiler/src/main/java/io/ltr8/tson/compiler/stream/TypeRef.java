package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/**
 * {@code "!" type-name} (§3.2): a value's type reference, preserved uninterpreted -- resolving
 * it against the built-in type vocabulary (§5) or a declared schema is a later layer's job. No
 * matching end event: a type-ref is a bare name, never a nested value.
 */
public record TypeRef(String name, Position position) implements TsonEvent {
}
