package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.tree.AtomNode;
import io.ltr8.tson.tree.NullNode;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonNode;

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
    private final AnnotationTypes annotationTypes;

    AtomNodeReader(TsonValueReader<?> delegate, String typeRef, AnnotationTypes annotationTypes) {
        this.delegate = delegate;
        this.typeRef = Optional.of(typeRef);
        this.annotationTypes = annotationTypes;
    }

    /**
     * Captures this value's own annotations before delegating, rather than after: the delegate consumes the
     * whole {@code annotation* type-ref?} framing itself and discards it, so taking the annotations first
     * leaves its own call with nothing to consume and no behaviour change. That is what gets them onto the
     * node without the leaf reader -- shared with bind mode -- having to hand anything back.
     */
    @Override
    public TsonNode read(TsonReadContext ctx) {
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        Object value = delegate.read(ctx);
        if (value == null) {
            return annotations.isEmpty() ? NullNode.instance() : new NullNode(Optional.empty(), annotations);
        }
        return new AtomNode(value, typeRef, annotations);
    }
}
