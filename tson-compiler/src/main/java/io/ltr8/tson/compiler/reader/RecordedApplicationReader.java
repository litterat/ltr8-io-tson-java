package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A {@code type_ref} slot bound to a {@link TypeRef}, read as it always was and, in a value body, recorded with its
 * slot ({@link TsonReadContext#heldByName}) so the linker judges the name or application it holds by resolution and
 * arity -- what the resolver does for a kernel body's own {@code type_ref} slots, and what a value body, whose
 * payload the resolver never walks, would otherwise miss.
 */
final class RecordedApplicationReader implements TsonTypeReader<Object> {

    private final TsonTypeReader<?> inner;

    RecordedApplicationReader(TsonTypeReader<?> inner) {
        this.inner = inner;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Object value = inner.read(ctx);
        if (value instanceof TypeRef reference && ctx.readsValueBody()) {
            ctx.heldByName(reference, null);
        }
        return value;
    }
}
