package io.ltr8.tson.compiler.ast.schema;

/**
 * A reference as written: a {@link TypeRef}, naming a type by its name and any arguments, or a {@link SiblingRef},
 * naming the field of the same record whose value names the type (Part 2 §12.1, [TSON-DATA] §7.4). The shape mirrors
 * the resolved model's {@code io.ltr8.tson.schema.meta.Reference}.
 *
 * <p>A reference parses wherever a {@link TypeExpr} does, and wherever the grammar admits a reference alone: a map's
 * key, a refinement's source, a composition's first operand. A sibling reference stands only at a record field's type;
 * {@code SchemaDesugarer} refuses it everywhere else (§5.2's position rule).
 */
public sealed interface Reference extends TypeExpr permits TypeRef, SiblingRef {
}
