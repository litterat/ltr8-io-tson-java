package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.tree.TsonAbsent;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;
import java.util.Optional;

/**
 * Tree mode: wraps a leaf reader (an atom/enum reader, which produces a host value or {@code null}) so it
 * yields a {@link TsonValue} instead -- a {@link TsonAtom} carrying the value and this leaf's declared
 * type-ref, or a {@link TsonAbsent} when the delegate produced no value (a soft-failed read in collecting
 * mode -- the diagnostic carries the real problem). This is how atoms produce nodes uniformly, so a
 * container reader's children are always nodes, and reading an atom at the root is a node too.
 */
final class AtomTreeReader implements TsonTypeReader<TsonValue> {

    private final TsonTypeReader<?> delegate;
    private final Optional<String> typeRef;
    private final AnnotationTypes annotationTypes;

    AtomTreeReader(TsonTypeReader<?> delegate, String typeRef, AnnotationTypes annotationTypes) {
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
    public TsonValue read(TsonReadContext ctx) {
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        Object value = delegate.read(ctx);
        if (value == null) {
            return annotations.isEmpty() ? TsonAbsent.instance() : new TsonAbsent(Optional.empty(), annotations);
        }
        return new TsonAtom(value, typeRef, annotations);
    }
}
