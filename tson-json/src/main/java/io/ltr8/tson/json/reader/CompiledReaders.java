package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonCompiledSchema;
import io.ltr8.tson.json.JsonTypeReader;

/**
 * How a compiled reader reaches another entry's reader <em>after</em> the compile has finished.
 *
 * <p><b>Rebound exactly once</b>, from the in-progress compilation to the finished schema. Handing readers
 * the compilation's own resolve would leak its mutable state past the compile -- a reader would keep a
 * reference to the half-built map, and every later lookup would go through it. The peer of {@code
 * tson-compiler}'s {@code CompiledReaders}, which carries the same hazard and the same fix.
 *
 * <p>Only the edges that need a name at read time consult this: a subtype named by {@code $type} (§6.1.5),
 * and whatever §8's dispatch reaches. Every other child reader is a real object reference wired at compile.
 */
public final class CompiledReaders implements TypeReaderResolver {

    private TypeReaderResolver building;
    private JsonCompiledSchema finished;

    public CompiledReaders(TypeReaderResolver building) {
        this.building = building;
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
}
