package io.ltr8.tson.parser.ast.schema;

import io.ltr8.tson.parser.ast.DataValue;

/**
 * {@code instance = "!" type-name ws core-value} (Part 2 §12.1, §5.5, corrected -- the spec's own
 * literal grammar says {@code data-value}, i.e. {@code *annotation [type-ref] core-value}
 * [TSON-DATA] §2.3, which would let a constructor-application payload carry its own further
 * annotations and a second, competing type-ref; see {@code SPEC-FEEDBACK.md} for the full writeup)
 * -- constructor application: produces a fresh atom-family instance filled with {@code value}'s
 * own core-value.
 *
 * <p>No separate {@code target} field -- {@link DataValue} already has exactly the right shape to
 * carry the constructor name: its own {@code typeRef}, an {@code Optional<String>}. {@link #target()}
 * is a thin accessor over {@code value.typeRef()} (always present, since it's populated from the
 * {@code "!" type-name} prefix at parse time), and {@code value.annotations()} is always empty (the
 * corrected grammar has no room for any). This is also exactly the shape {@code SchemaResolver}'s
 * generalized constructor-application resolution needs: {@code value} can be handed straight to
 * {@code TsonMapperReader.toObject(value, Atom.class)} with no separate wrapping step to attach a
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
