package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * The {@code null} token as a node -- a present value that is null (§4.1), distinct from {@link TsonAbsent}
 * (the {@code _} sentinel) and {@link TsonMissing} (no such node at all). Carries its own type-ref/annotations
 * for the annotated case; {@link #instance()} is the bare, un-annotated common case.
 */
public record TsonNull(Optional<String> typeRef, List<TsonAnnotation> annotations) implements TsonValue {

    private static final TsonNull BARE = new TsonNull(Optional.empty(), List.of());

    public TsonNull {
        annotations = List.copyOf(annotations);
    }

    public static TsonNull instance() {
        return BARE;
    }

    @Override
    public boolean isNull() {
        return true;
    }
}
