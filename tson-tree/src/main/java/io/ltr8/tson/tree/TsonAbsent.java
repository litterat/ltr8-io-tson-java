package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * The absent sentinel as a node (§2.7) -- a position that was written but holds no value, spelled {@code _},
 * the notation's only no-value spelling. Distinct from {@link TsonMissing} (no such node at all): this one was
 * written. Carries its own type-ref/annotations for the annotated case; {@link #instance()} is the bare
 * common case.
 *
 * <p>Also the placeholder a tree-mode reader leaves where a value failed to read in collecting mode -- what
 * went wrong is carried by the diagnostic, not by the node standing in for it.
 */
public record TsonAbsent(Optional<String> typeRef, List<TsonAnnotation> annotations) implements TsonValue {

    private static final TsonAbsent BARE = new TsonAbsent(Optional.empty(), List.of());

    public TsonAbsent {
        annotations = List.copyOf(annotations);
    }

    public static TsonAbsent instance() {
        return BARE;
    }

    @Override
    public boolean isAbsent() {
        return true;
    }
}
