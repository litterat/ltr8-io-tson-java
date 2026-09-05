package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code structural-def = refined-def / construction-def / record-def} (Part 2 §12.1) -- the three forms a
 * {@link StructuralTypeDef} can wrap.
 */
public sealed interface StructuralDef permits RefinedDef, ConstructionDef, RecordDef {
}
