package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * The absent sentinel {@code _} as a node (§2.7) -- a value explicitly marked absent, distinct from {@link
 * TsonNull} (the {@code null} token) and {@link TsonMissing} (no such node at all). Carries its own
 * type-ref/annotations for the annotated case; {@link #instance()} is the bare common case.
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
