package io.ltr8.tson.parser.ast.schema;

/** {@code type-name} alone (Part 2 §12.1) -- a bare reference with no type arguments. */
public record SimpleRef(String name) implements TypeRef {
}
