package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * The result of navigating to something that isn't in the tree -- a query artifact, not a real value, so
 * {@link #get}/{@link #at} keep returning it and a deep chain never throws. Distinct from {@link TsonNull}
 * (the {@code null} token) and {@link TsonAbsent} (the {@code _} sentinel), which are real present values. A
 * singleton via {@link #instance()}.
 */
public record TsonMissing() implements TsonValue {

    private static final TsonMissing INSTANCE = new TsonMissing();

    public static TsonMissing instance() {
        return INSTANCE;
    }

    @Override
    public Optional<String> typeRef() {
        return Optional.empty();
    }

    @Override
    public List<TsonAnnotation> annotations() {
        return List.of();
    }

    @Override
    public boolean isMissing() {
        return true;
    }

    @Override
    public TsonValue get(String name) {
        return this;
    }

    @Override
    public TsonValue get(int index) {
        return this;
    }
}
