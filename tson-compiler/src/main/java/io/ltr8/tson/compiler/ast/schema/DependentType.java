package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code kind!} (Part 2 §5.2, §12.1): a dependent field's type, each instance's value read against the type its field
 * {@code on} names. Sugar for {@code { of: !meta.dependent { on: kind } }}, which {@code SchemaDesugarer} writes in its
 * place.
 */
public record DependentType(String on) implements FieldTypeExpr {
}
