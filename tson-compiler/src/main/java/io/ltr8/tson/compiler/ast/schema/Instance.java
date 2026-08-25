package io.ltr8.tson.compiler.ast.schema;

import io.ltr8.tson.compiler.ast.DataValue;

/**
 * {@code instance = "!" type-name ws core-value} (Part 2 §12.1, §5.5) -- constructor application:
 * produces a fresh atom-family instance filled with {@code value}'s own core-value. The payload is
 * deliberately narrower than a {@code data-value} ({@code *annotation [type-ref] core-value},
 * [TSON-DATA] §2.3), which would let it carry its own further annotations and a second, competing
 * type-ref; §12.1 states that no production of the schema grammar takes the full {@code data-value}.
 *
 * <p>No separate {@code target} field -- {@link DataValue} already has exactly the right shape to
 * carry the constructor name: its own {@code typeRef}, an {@code Optional<String>}. {@link #target()}
 * is a thin accessor over {@code value.typeRef()} (always present, since it's populated from the
 * {@code "!" type-name} prefix at parse time), and {@code value.annotations()} is always empty (the
 * corrected grammar has no room for any). This is also exactly the shape {@code DefinitionResolver}'s
 * generalized constructor-application resolution needs: {@code value} can be handed straight to
 * {@code TsonObjectReader.toObject(value, Atom.class)} with no separate wrapping step to attach a
 * type-ref, since it's already there.
 *
 * <p>{@link #target()} MUST resolve to a constructor (a semantic-layer check, not enforced here).
 * Establishes no IS-A -- construction transfers only {@code target()}'s kind (§4.1, §5.5), unlike
 * {@link AtomRefinement}.
 */
public record Instance(DataValue value) implements TypeDef {

    public String target() {
        return value.typeRef().orElseThrow();
    }
}
