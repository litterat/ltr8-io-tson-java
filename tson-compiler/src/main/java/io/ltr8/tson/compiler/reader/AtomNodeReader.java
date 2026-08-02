package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.tree.AtomNode;
import io.ltr8.tson.compiler.tree.NullNode;
import io.ltr8.tson.compiler.tree.TsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Tree mode: wraps a leaf reader (an atom/enum reader, which produces a host value or {@code null}) so it
 * yields a {@link TsonNode} instead -- an {@link AtomNode} carrying the value and this leaf's declared
 * type-ref, or a {@link NullNode} for {@code null} (the {@code null} token, or a soft-failed read in
 * collecting mode -- the diagnostic carries the real problem). This is how atoms produce nodes uniformly, so
 * a container reader's children are always nodes, and reading an atom at the root is a node too.
 */
final class AtomNodeReader implements TsonValueReader<TsonNode> {

    private final TsonValueReader<?> delegate;
    private final Optional<String> typeRef;

    AtomNodeReader(TsonValueReader<?> delegate, String typeRef) {
        this.delegate = delegate;
        this.typeRef = Optional.of(typeRef);
    }

    @Override
    public TsonNode read(TsonReadContext ctx) {
        Object value = delegate.read(ctx);
        return value == null ? NullNode.instance() : new AtomNode(value, typeRef, List.of());
    }
}
