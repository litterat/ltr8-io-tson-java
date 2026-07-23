package io.ltr8.tson.schema.meta;

import io.ltr8.tson.parser.ast.TokenValue;

/**
 * The meta-kernel's {@code type_argument} record (Part 2 §8.1, §9): one positional argument of a
 * resolved {@link TypeRef}, a labelled choice between a reference and a concrete literal -- {@code
 * { name: ... }} for a reference (including, in an open template body, a parameter of either
 * kind), {@code { value: ... }} for a literal. Not yet produced by {@code SchemaResolver} (no
 * example resolved so far carries type arguments); modeled now so {@link TypeRef}'s shape doesn't
 * need revisiting once one does.
 */
public sealed interface TypeArgument {

    record Ref(TypeRef ref) implements TypeArgument {
    }

    record Value(TokenValue value) implements TypeArgument {
    }
}
