package io.ltr8.tson.parser.ast.schema;

import java.util.List;
import java.util.Optional;

/**
 * Supertype composition and/or subtraction (Part 2 §12.1, §5.8, §5.9): one or more {@code &}-joined
 * supertypes, an optional trailing {@code record-def} body, and an optional trailing {@code
 * removal-set}. {@code supertypes} always has at least one element -- a lone type-ref with neither
 * a body nor a removal is not a {@code ConstructionDef} at all, just a {@link StructuralTypeDef}
 * wrapping a plain {@link TypeRef} (§12.2's disambiguation: {@code name &}/{@code name -} enter
 * this production, bare {@code name} does not).
 *
 * <p><b>The ABNF's {@code construction-def} alternative 1 doesn't literally admit a trailing body
 * after a multi-supertype chain</b> (its {@code record-def} slot has no leading {@code "&"}), even
 * though §5.8's own worked example (and §12.2's disambiguation note) shows exactly that shape --
 * see {@code SPEC-FEEDBACK.md} #14. This type, and the parser that builds it, follow the documented
 * intent: {@code body} is reached through a {@code &} like any other supertype join, not bolted on
 * without one.
 */
public record ConstructionDef(List<TypeRef> supertypes, Optional<RecordDef> body, Optional<RemovalSet> removal)
        implements StructuralDef {

    public ConstructionDef {
        supertypes = List.copyOf(supertypes);
        if (supertypes.isEmpty()) {
            throw new IllegalArgumentException("a construction needs at least one supertype");
        }
    }
}
