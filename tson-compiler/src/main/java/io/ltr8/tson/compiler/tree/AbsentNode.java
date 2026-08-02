package io.ltr8.tson.compiler.tree;

import java.util.List;
import java.util.Optional;

/**
 * The absent sentinel {@code _} as a node (§2.7) -- a value explicitly marked absent, distinct from {@link
 * NullNode} (the {@code null} token) and {@link MissingNode} (no such node at all). Carries its own
 * type-ref/annotations for the annotated case; {@link #instance()} is the bare common case.
 */
public record AbsentNode(Optional<String> typeRef, List<TsonAnnotation> annotations) implements TsonNode {

    private static final AbsentNode BARE = new AbsentNode(Optional.empty(), List.of());

    public AbsentNode {
        annotations = List.copyOf(annotations);
    }

    public static AbsentNode instance() {
        return BARE;
    }

    @Override
    public boolean isAbsent() {
        return true;
    }
}
