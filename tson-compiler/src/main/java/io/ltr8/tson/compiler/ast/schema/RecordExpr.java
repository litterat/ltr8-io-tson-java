package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code record-def} at a type expression position (Part 2 §12.1, §5.2) -- a record written where it is used, {@code
 * at: { x: int32  y: int32 }}: structural, with no name, no chain, a member of nothing. It stands wherever a container
 * may, and nowhere a type expression is held to a reference ({@link MapExpr#keyType}, {@link RefinedDef#target}).
 */
public record RecordExpr(RecordDef body) implements TypeExpr {
}
