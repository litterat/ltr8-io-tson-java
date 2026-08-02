package io.ltr8.tson.compiler.tree;

import java.util.List;
import java.util.Optional;

/**
 * The result of navigating to something that isn't in the tree -- a query artifact, not a real value, so
 * {@link #get}/{@link #at} keep returning it and a deep chain never throws. Distinct from {@link NullNode}
 * (the {@code null} token) and {@link AbsentNode} (the {@code _} sentinel), which are real present values. A
 * singleton via {@link #instance()}.
 */
public record MissingNode() implements TsonNode {

    private static final MissingNode INSTANCE = new MissingNode();

    public static MissingNode instance() {
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
    public TsonNode get(String name) {
        return this;
    }

    @Override
    public TsonNode get(int index) {
        return this;
    }
}
