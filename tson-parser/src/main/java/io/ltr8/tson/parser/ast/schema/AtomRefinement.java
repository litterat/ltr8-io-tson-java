package io.ltr8.tson.parser.ast.schema;

import io.ltr8.tson.parser.ast.DataValue;

/**
 * {@code atom-refinement = "!" type-name ws "^" ws data-value} (Part 2 §12.1, §5.5) -- refines an
 * atom-family instance by tightening its constructor's constraint fields. {@code target} MUST
 * resolve to a non-constructor atom-family instance and {@code bindings} MUST be a braced record
 * of constraint values (both semantic-layer checks, not enforced here); this establishes IS-A with
 * {@code target}, unlike {@link Instance}.
 */
public record AtomRefinement(String target, DataValue bindings) implements TypeDef {
}
