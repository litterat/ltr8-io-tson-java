package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.tree.AbsentNode;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Tree mode: reads the {@code void} unit instance -- the absent sentinel {@code _} -- consuming it via a
 * delegate {@link VoidReader} and yielding {@link AbsentNode}. Distinct from a null-yielding leaf ({@link
 * AtomNodeReader} → {@link io.ltr8.tson.tree.NullNode}), since {@code _} is "absent", not "null".
 */
final class AbsentNodeReader implements TsonValueReader<TsonNode> {

    private final TsonValueReader<?> delegate;

    AbsentNodeReader(TsonValueReader<?> delegate) {
        this.delegate = delegate;
    }

    /** Captures the annotations before delegating -- see {@link AtomNodeReader#read} for why that ordering is what works. */
    @Override
    public TsonNode read(TsonReadContext ctx) {
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx);
        delegate.read(ctx); // consume the `_` (and let the delegate report any shape mismatch)
        return annotations.isEmpty() ? AbsentNode.instance() : new AbsentNode(Optional.empty(), annotations);
    }
}
