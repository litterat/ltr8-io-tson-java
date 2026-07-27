package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;

import java.util.Map;

/**
 * A lazy, name-keyed stand-in for a child reader still mid-construction when this was created --
 * {@code TsonSchemaCompiler}'s own cycle-breaking case: a reference back to an entry currently on
 * the build stack, directly or transitively. {@code registry} is the compiler's own per-compilation
 * {@code finished} map, captured by reference, not copied -- by the time {@link #read} is ever
 * actually called, the {@code TsonSchemaCompiler#compile} call that created this has already
 * returned (nothing outside the compiler can reach one of these before that call returns), and
 * compilation is eager (every entry resolved before {@code compile} returns), so {@code typeName}'s
 * own entry is guaranteed to already be in {@code registry}.
 */
record DeferredValueReader<T>(String typeName, Map<String, TsonValueReader<?>> registry) implements TsonValueReader<T> {

    @Override
    @SuppressWarnings("unchecked")
    public T read(DataValue value) {
        TsonValueReader<?> resolved = registry.get(typeName);
        if (resolved == null) {
            throw new IllegalStateException("'" + typeName + "' has no compiled parser -- a deferred lookup "
                    + "is only ever created for a name that IS in the schema (a cycle back to an "
                    + "in-progress entry), so a missing entry here means the resolve() call that started "
                    + "building '" + typeName + "' returned without ever finishing it, which is a compiler "
                    + "bug, not a caller error");
        }
        return (T) resolved.read(value);
    }
}
