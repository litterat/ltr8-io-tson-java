package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;

import java.util.Map;

/**
 * A lazy, name-keyed stand-in for a child reader still mid-construction when this was created --
 * {@code TsonSchemaCompiler}'s own cycle-breaking case: a reference back to an entry currently on
 * the build stack, directly or transitively. {@code registry} is the reader's own per-compilation
 * {@code finished} map, captured by reference, not copied -- by the time {@link #read} is ever
 * actually called, the {@code TsonSchemaCompiler#compile} call that created this has already
 * returned (nothing outside the reader can reach one of these before that call returns), and
 * compilation is eager (every entry resolved before {@code compile} returns), so {@code typeName}'s
 * own entry is guaranteed to already be in {@code registry}.
 */
public record DeferredTypeReader<T>(String typeName, Map<String, TsonTypeReader<?>> registry) implements TsonTypeReader<T> {

    /**
     * The entry this stands in for, once the compilation that created it has finished it -- {@code null}
     * while it is still building. What {@link ScopePush} asks when a scope push arrives at a recursive
     * position, since the real reader is the one that decides whether the push is admitted.
     */
    TsonTypeReader<?> resolved() {
        return registry.get(typeName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T read(TsonReadContext ctx) {
        TsonTypeReader<?> resolved = registry.get(typeName);
        if (resolved == null) {
            throw new IllegalStateException("'" + typeName + "' has no compiled reader -- a deferred lookup "
                    + "is only ever created for a name that IS in the schema (a cycle back to an "
                    + "in-progress entry), so a missing entry here means the resolve() call that started "
                    + "building '" + typeName + "' returned without ever finishing it, which is a reader "
                    + "bug, not a caller error");
        }
        return (T) resolved.read(ctx);
    }
}
