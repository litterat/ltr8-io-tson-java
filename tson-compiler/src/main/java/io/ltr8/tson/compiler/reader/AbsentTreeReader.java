package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;
import java.util.Optional;

/**
 * Tree mode: reads the {@code void} unit instance -- the absent sentinel, spelled {@code _} or {@code null}
 * -- consuming it via a delegate {@link VoidReader} and yielding {@link TsonAbsent}.
 */
final class AbsentTreeReader implements TsonTypeReader<TsonValue> {

    private final TsonTypeReader<?> delegate;
    private final AnnotationTypes annotationTypes;

    AbsentTreeReader(TsonTypeReader<?> delegate, AnnotationTypes annotationTypes) {
        this.delegate = delegate;
        this.annotationTypes = annotationTypes;
    }

    /** Captures the annotations before delegating -- see {@link AtomTreeReader#read} for why that ordering is what works. */
    @Override
    public TsonValue read(TsonReadContext ctx) {
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        delegate.read(ctx); // consume the `_` (and let the delegate report any shape mismatch)
        return annotations.isEmpty() ? TsonAbsent.instance() : new TsonAbsent(Optional.empty(), annotations);
    }
}
