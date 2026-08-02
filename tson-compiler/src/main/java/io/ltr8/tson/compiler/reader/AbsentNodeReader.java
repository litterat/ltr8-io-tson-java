package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.tree.AbsentNode;
import io.ltr8.tson.compiler.tree.TsonNode;

/**
 * Tree mode: reads the {@code void} unit instance -- the absent sentinel {@code _} -- consuming it via a
 * delegate {@link VoidReader} and yielding {@link AbsentNode}. Distinct from a null-yielding leaf ({@link
 * AtomNodeReader} → {@link io.ltr8.tson.compiler.tree.NullNode}), since {@code _} is "absent", not "null".
 */
final class AbsentNodeReader implements TsonValueReader<TsonNode> {

    private final TsonValueReader<?> delegate;

    AbsentNodeReader(TsonValueReader<?> delegate) {
        this.delegate = delegate;
    }

    @Override
    public TsonNode read(TsonReadContext ctx) {
        delegate.read(ctx); // consume the `_` (and let the delegate report any shape mismatch)
        return AbsentNode.instance();
    }
}
