package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonCompiledSchema;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.TypeInline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * How a compiled reader reaches another entry's reader <em>after</em> the compile has finished.
 *
 * <p><b>Rebound exactly once</b>, from the in-progress compilation to the finished schema. Handing readers
 * the compilation's own resolve would leak its mutable state past the compile -- a reader would keep a
 * reference to the half-built map, and every later lookup would go through it. The peer of {@code
 * tson-compiler}'s {@code CompiledReaders}, which carries the same hazard and the same fix.
 *
 * <p><b>No reader consults it at read time.</b> Every edge -- a child position, and every reader a dispatcher
 * can select -- is an object reference wired at compile, and an edge that closes a cycle reaches its target
 * through {@link DeferredTypeReader}. What the rebind guarantees is that a reader keeping the resolver anyway
 * never reaches the compile it came from.
 *
 * <p><b>A type written in place</b> has a reader built from its body by {@code inlineBuilder}, once per construction
 * (equal constructions share one, a private cache and no entry). The builder holds the factories and the linked
 * schema, never the compilation.
 */
public final class CompiledReaders implements TypeReaderResolver {

    private TypeReaderResolver building;
    private JsonCompiledSchema finished;
    private final Function<TypeInline, JsonTypeReader<?>> inlineBuilder;
    private final Map<TypeInline, JsonTypeReader<?>> inline = new ConcurrentHashMap<>();

    public CompiledReaders(TypeReaderResolver building) {
        this(building, null);
    }

    /** Resolving names through {@code building}, and an inline type's reader through {@code inlineBuilder}. */
    public CompiledReaders(TypeReaderResolver building, Function<TypeInline, JsonTypeReader<?>> inlineBuilder) {
        this.building = building;
        this.inlineBuilder = inlineBuilder;
    }

    /** Hands over to the finished schema. Called once, by the compiler, and never again. */
    public void bind(JsonCompiledSchema schema) {
        if (finished != null) {
            throw new IllegalStateException("already bound -- a compile hands over exactly once");
        }
        this.finished = schema;
        this.building = null;
    }

    @Override
    public JsonTypeReader<?> resolve(String name) {
        return finished != null ? finished.get(name) : building.resolve(name);
    }

    /**
     * The reader of a type written in place, built from its body the first time it is asked for, outside the map's
     * lock: an inline type's cycles pass through names, so building one never reaches itself.
     */
    @Override
    public JsonTypeReader<?> resolveInline(TypeInline type) {
        JsonTypeReader<?> built = inline.get(type);
        if (built != null) {
            return built;
        }
        if (inlineBuilder == null) {
            return TypeReaderResolver.super.resolveInline(type);
        }
        JsonTypeReader<?> fresh = inlineBuilder.apply(type);
        JsonTypeReader<?> raced = inline.putIfAbsent(type, fresh);
        return raced == null ? fresh : raced;
    }
}
