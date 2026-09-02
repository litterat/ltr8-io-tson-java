package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code sum => top & {}} base kind (Part 2 §4.1) -- every SUM-kind {@link Top} variant
 * IS-A this. Two of them: {@link ChoiceBody} ({@code choice => ~sum & { variants: [type_ref] }}, §5.4), the
 * closed sum that enumerates its variants, and {@link Scoped} ({@code scoped => ~sum & { scope: ...
 * schemas: ...? }}), the open one that names the namespaces its variants are drawn from. {@code disjoint} is
 * derived on the first and absent on the second (§8.1), which is the same distinction from the other side.
 */
public sealed interface Sum extends Top permits ChoiceBody, Scoped {

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
