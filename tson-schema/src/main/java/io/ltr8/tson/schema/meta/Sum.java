package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The kernel's {@code sum => type & {}} base kind (Part 2 §4.1) -- every SUM-kind {@link Top} variant
 * IS-A this. Three of them: {@link ChoiceBody} ({@code choice => ~sum & { variants: [meta.type_ref] }}, §5.4), the
 * closed sum that enumerates its variants; {@link DeclaredType} ({@code declared_type => sum & {}}), the open
 * one whose variants are the types of the governing closure, chosen by the value's tag; and {@link DependentBody}
 * ({@code dependent => sum & { on: text }}), the same open sum chosen by a field of the enclosing record. {@code
 * disjoint} is derived on the first and absent on the others (§8.1).
 */
public sealed interface Sum extends Type permits ChoiceBody, DeclaredType, DependentBody {

    /**
     * Reports how this body's own facets contradict <em>each other</em> -- an empty list means it is
     * internally coherent. {@link Atom#coherenceCheck} and {@link Product#coherenceCheck}'s third sibling,
     * asking the identical question of the third base kind.
     *
     * <p>The default admits everything, which is correct for a family with nothing to contradict: a choice's
     * variants are checked for distinctness and disjointness by the linker, which needs the entry graph and
     * so cannot be a question a body answers about itself.
     */
    default List<String> coherenceCheck() {
        return List.of();
    }
}
