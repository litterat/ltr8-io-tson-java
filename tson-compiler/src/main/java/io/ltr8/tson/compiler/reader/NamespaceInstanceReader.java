package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.meta.Namespace;

/**
 * An instance of a namespace constructor read where the position holds a {@code top}: the map the constructor's own
 * reader reads, delivered as the {@link Namespace} it is an instance of. A namespace type reads as a map wherever it
 * types a position ({@code methods: method_map} binds a {@code Map}); a value standing as an entry, {@code myapi =>
 * !api { ... }}, or read back from resolved output at a {@code top}-typed position, is a value of the constructor,
 * which names the key family and the value type, so it carries the constructor's name beside the map.
 *
 * <p>Bind mode only wraps: a bound namespace type reads into an {@link AnnotatedMap}, which keeps each key's
 * annotations and the order written ({@code MapBindReader}). Anything else the wrapped reader produces -- a tree
 * value, or {@code null} for a value it refused -- passes through untouched.
 */
public final class NamespaceInstanceReader implements TsonTypeReader<Object> {

    private final String constructor;
    private final TsonTypeReader<?> map;

    /**
     * @param constructor the namespace constructor a value read here is an instance of
     * @param map         the constructor's own reader, reading the map
     */
    public NamespaceInstanceReader(String constructor, TsonTypeReader<?> map) {
        this.constructor = constructor;
        this.map = map;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        return instance(constructor, map.read(ctx));
    }

    /** {@code read}, a map an instance of {@code constructor}'s reader produced, as that instance. */
    @SuppressWarnings("unchecked")
    static Object instance(String constructor, Object read) {
        return read instanceof AnnotatedMap<?, ?> entries
                ? new Namespace<>(constructor, (AnnotatedMap<String, Object>) entries) : read;
    }
}
