package io.ltr8.tson.parser.ast.schema;

/**
 * {@code refined-def = type-name [ws "<" type-args ">"] ws "^" ws record-def} (Part 2 §12.1,
 * §5.7) -- record, map/array-head, or (with a preceding {@code ~}) constructor refinement.
 * {@code target} is restricted to a bare type-name optionally with type-args -- inline structural
 * forms (a choice, an inline array) cannot precede {@code ^} by grammar, so {@code target} is
 * always a {@link SimpleRef} or a {@link GenericRef}, never a {@link ChoiceRef}, {@link
 * InlineArrayRef}, or {@link InlineTupleRef}; that narrower grammar fact isn't encoded in the Java
 * type here (both legal shapes already implement the same {@link TypeRef}), so the parser is
 * responsible for never constructing this with anything else. No removal clause is admitted on a
 * refinement head (§5.7, §5.9) -- there is deliberately no field for one.
 */
public record RefinedDef(TypeRef target, RecordDef body) implements StructuralDef {
}
