package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * The {@code null} token as a node -- a present value that is null (§4.1), distinct from {@link AbsentNode}
 * (the {@code _} sentinel) and {@link MissingNode} (no such node at all). Carries its own type-ref/annotations
 * for the annotated case; {@link #instance()} is the bare, un-annotated common case.
 */
public record NullNode(Optional<String> typeRef, List<TsonAnnotation> annotations) implements TsonNode {

    private static final NullNode BARE = new NullNode(Optional.empty(), List.of());

    public NullNode {
        annotations = List.copyOf(annotations);
    }

    public static NullNode instance() {
        return BARE;
    }

    @Override
    public boolean isNull() {
        return true;
    }
}
