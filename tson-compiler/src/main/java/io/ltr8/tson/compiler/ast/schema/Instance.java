package io.ltr8.tson.compiler.ast.schema;

import io.ltr8.tson.compiler.ast.DataValue;

import java.util.List;

/**
 * {@code instance = [type-params] "!" type-name ws core-value} (Part 2 §12.1, §5.5) -- constructor
 * application: produces a fresh atom-family instance filled with {@code value}'s own core-value. The payload
 * is deliberately narrower than a {@code data-value} ({@code *annotation [type-ref] core-value},
 * [TSON-DATA] §2.3), which would let it carry its own further annotations and a second, competing
 * type-ref; §12.1 states that no production of the schema grammar takes the full {@code data-value}.
 *
 * <p><b>A parameter list makes it a template, and changes nothing else.</b> {@code <T> !array
 * { element_type: T }} is this same production with {@code typeParams} non-empty, and its payload is
 * unrestricted for the same reason the closed form's is: an open entry's body is held rather than read
 * against its constructor's vocabulary until materialisation has substituted the parameters away, so a
 * collection payload -- {@code <T> !choice { variants: [T error] }} -- is as ordinary here as a scalar one.
 * A parameterized {@link AtomRefinement} is not a form at all (§12.1 gives {@code atom-refinement} no
 * parameter list, a refinement of an atom instance having no parameter to take).
 *
 * <p>No separate {@code target} field -- {@link DataValue} already has exactly the right shape to
 * carry the constructor name: its own {@code typeRef}, an {@code Optional<String>}. {@link #target()}
 * is a thin accessor over {@code value.typeRef()} (always present, since it's populated from the
 * {@code "!" type-name} prefix at parse time), and {@code value.annotations()} is always empty (the
 * corrected grammar has no room for any). This is also exactly the shape {@code DefinitionResolver}'s
 * generalized constructor-application resolution needs: {@code value} can be handed straight to
 * {@code TsonObjectReader.toObject(value, Atom.class)} with no separate wrapping step to attach a
 * type-ref, since it's already there -- and, when {@code typeParams} is non-empty, straight into a
 * {@code HeldBody} instead, unread.
 *
 * <p>{@link #target()} MUST resolve to a constructor (a semantic-layer check, not enforced here).
 * Establishes no IS-A -- construction transfers only {@code target()}'s kind (§4.1, §5.5), unlike
 * {@link AtomRefinement}.
 */
public record Instance(List<String> typeParams, DataValue value) implements TypeDef {

    public Instance {
        typeParams = List.copyOf(typeParams);
    }

    /** The closed form -- every caller that builds an instance with no parameters of its own. */
    public Instance(DataValue value) {
        this(List.of(), value);
    }

    public String target() {
        return value.typeRef().orElseThrow();
    }
}
