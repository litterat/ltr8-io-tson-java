package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code refined-def = type-ref ws "^" ws record-def} (Part 2 §12.1, §5.7) -- record, map/array-head, or constructor
 * refinement. Inline structural forms (a choice, an inline array) cannot precede {@code ^} by grammar, so the target is
 * a {@link TypeRef}. No removal clause is admitted on a refinement head (§5.7, §5.9) -- there is deliberately no field
 * for one.
 */
public record RefinedDef(TypeRef target, RecordDef body) implements StructuralDef {
}
