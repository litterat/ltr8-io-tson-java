package io.ltr8.tson.parser.ast.schema;

import io.ltr8.tson.parser.ast.DataValue;

/**
 * {@code instance = "!" type-name ws data-value} (Part 2 §12.1, §5.5) -- constructor application:
 * produces a fresh atom-family instance filled with {@code value}. {@code target} MUST resolve to
 * a constructor (a semantic-layer check, not enforced here). Establishes no IS-A -- construction
 * transfers only {@code target}'s kind (§4.1, §5.5), unlike {@link AtomRefinement}.
 */
public record Instance(String target, DataValue value) implements TypeDef {
}
