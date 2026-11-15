package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code structural-def = construction-def / record-def} (Part 2 §12.1) -- the two forms a {@link StructuralTypeDef}
 * can wrap.
 */
public sealed interface StructuralDef permits ConstructionDef, RecordDef {
}
