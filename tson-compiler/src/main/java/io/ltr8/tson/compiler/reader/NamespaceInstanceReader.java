package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.meta.Namespace;

/**
 * An instance of a namespace constructor: the map the constructor's own reader reads, delivered as the {@link
 * Namespace} it is an instance of, which carries the constructor's name beside the map. Four positions deliver
 * one: a value standing as an entry, {@code myapi => !api { ... }} (the meta's constructor reader); a value read back
 * from resolved output at a {@code top}-typed position, selected by its tag ({@code RecordTagDispatchReader}); a
 * position typed by a namespace type whose host component is a {@code Namespace}, a record's {@code fields} ({@link
 * BindTargets}); and a root read asked for a {@code Namespace} ({@code TsonObjectReader}). Anywhere else a namespace
 * type reads as the map itself, so a host declaring {@code Map<String, Method> methods} receives a map.
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
