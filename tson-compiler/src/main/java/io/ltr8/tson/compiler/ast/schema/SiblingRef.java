package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code sibling-ref = unquoted-token "!"} (Part 2 §12.1, [TSON-DATA] §7.4) -- a reference to the field {@code field}
 * of the same record instance, whose value names the type: {@code kind!}. It parses wherever a type-ref does, and
 * stands only at a record field's type; {@code SchemaDesugarer} refuses it anywhere else (§5.2).
 */
public record SiblingRef(String field) implements TypeRef {
}
