package io.ltr8.tson.parser.ast.schema;

/**
 * {@code structural-def = refined-def / construction-def / record-def} (Part 2 §12.1) -- the
 * three forms a (possibly {@code ~}-marked) {@link StructuralTypeDef} can wrap; see {@link
 * StructuralTypeDef#constructor()} for where the {@code ~} marker itself is recorded.
 */
public sealed interface StructuralDef permits RefinedDef, ConstructionDef, RecordDef {
}
