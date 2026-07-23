package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code [type-params] ["~"] structural-def} (Part 2 §12.1, §4.2, §5.10) -- a refinement,
 * composition/subtraction, or fresh record, optionally parameterized and optionally marked as a
 * constructor. {@code constructor} is {@code true} only when the source carried a literal {@code
 * ~} -- the sole signal for {@code constructor: true} in resolver output (§5.8: "Constructor
 * marker is independent of supertypes"); a bare record or composition with no {@code ~} is an
 * ordinary (non-constructor) type even though it uses the same {@link StructuralDef} shapes.
 */
public record StructuralTypeDef(List<String> typeParams, boolean constructor, StructuralDef body) implements TypeDef {

    public StructuralTypeDef {
        typeParams = List.copyOf(typeParams);
    }
}
