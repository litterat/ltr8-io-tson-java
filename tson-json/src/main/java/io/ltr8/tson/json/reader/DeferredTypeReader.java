package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.TsonLinkedSchema;

import java.util.Map;

/**
 * The reader handed to a compile that has re-entered an entry still being built -- a cyclic type graph, which
 * the model reaches the moment a record can contain itself. It resolves by name against the finished map on
 * the first read, by which time the entry it names is complete.
 *
 * <p>Laziness is confined to the cyclic edge and nothing else: every other child reader is wired as a real
 * object reference at compile time, so a read never consults a name-keyed map except at the edge that closes
 * a loop.
 */
public final class DeferredTypeReader implements JsonTypeReader<Object>, ExactReader {

    private final String name;
    private final Map<String, JsonTypeReader<?>> finished;
    private final boolean keyed;

    /** The edge closing a cycle at {@code name}, an entry of {@code linked}, read from {@code finished}. */
    public DeferredTypeReader(TsonLinkedSchema linked, String name, Map<String, JsonTypeReader<?>> finished) {
        this.name = name;
        this.finished = finished;
        this.keyed = TagMembers.keyed(linked, name);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        return target().read(ctx);
    }

    /** As {@link Route#read} treats a target it reached directly: exact where it can be, a wrapper otherwise. */
    @Override
    public Object readExact(JsonReadContext ctx, JsonTypeReader<?> wrapped) {
        return target() instanceof ExactReader exact
                ? exact.readExact(ctx, wrapped)
                : TagMembers.readWrapped(ctx, wrapped, keyed);
    }

    @Override
    public boolean takesWrapper() {
        return target() instanceof ExactReader exact && exact.takesWrapper();
    }

    private JsonTypeReader<?> target() {
        JsonTypeReader<?> target = finished.get(name);
        if (target == null) {
            throw new IllegalStateException("'" + name + "' closes a cycle but never finished compiling -- "
                    + "the compile should have completed every entry before any read began");
        }
        return target;
    }
}
