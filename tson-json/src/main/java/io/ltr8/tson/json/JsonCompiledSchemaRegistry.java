package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.json.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.json.reader.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaLoader;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiled JSON readers, cached per schema identity -- what a schema-aware read goes through, and the peer
 * of {@code tson-compiler}'s {@code TsonCompiledSchemaRegistry}.
 *
 * <p><b>Schemas are obtained, never authored.</b> A schema document is TSON text, so resolving one is the
 * TSON engine's job; what crosses into this module is a {@link TsonLinkedSchema}, which is a value model in
 * {@code tson-schema}. That is why naming a schema here costs no dependency on {@code tson-compiler} -- the
 * same argument that keeps the compiled reader stack free of it. An application typically hands
 * {@code tson.schemaRegistry()}, or its own loader over one.
 *
 * <p><b>The read mode is which factory set this holds</b>, exactly as it is on the TSON side: {@link Mode#TREE}
 * validates and hands back the JSON, {@link Mode#BIND} builds the classes a {@link DataBindContext} binds. A
 * schema compiled for one mode is cached apart from the other's, the readers differing all the way down.
 *
 * <p>Safe for concurrent reads. Two threads racing to compile one identity may both compile it; the map
 * keeps one and the loser's copy is discarded, which is cheaper than holding a lock across a resolve. A
 * cache hit -- every read, in an application that named its schemas at startup -- takes no lock at all.
 */
public final class JsonCompiledSchemaRegistry {

    /** Which read mode a registry's readers are compiled for. */
    public enum Mode {
        /** Readers that validate and hand back the document as a {@code JsonValue}. */
        TREE,
        /** Readers that build the classes a {@code DataBindContext} binds. */
        BIND
    }

    private final TsonSchemaLoader loader;
    private final ValueReaderFactoryResolver factories;
    private final Mode mode;
    private final Map<String, JsonCompiledSchema> compiled = new ConcurrentHashMap<>();

    private JsonCompiledSchemaRegistry(TsonSchemaLoader loader, ValueReaderFactoryResolver factories, Mode mode) {
        this.loader = loader;
        this.factories = factories;
        this.mode = mode;
    }

    /** Tree mode over {@code loader}: a read validates and hands back the document. */
    public static JsonCompiledSchemaRegistry tree(TsonSchemaLoader loader) {
        return new JsonCompiledSchemaRegistry(Objects.requireNonNull(loader, "loader"),
                ValueReaderFactoryRegistry.tree(), Mode.TREE);
    }

    /**
     * Bind mode over {@code loader}, building the classes {@code binding} resolves for each schema type. A schema
     * whose types the bound classes do not match fails its compile with {@code BindMismatchException}, from {@link
     * #get}, the first time it is asked for.
     */
    public static JsonCompiledSchemaRegistry bind(TsonSchemaLoader loader, DataBindContext binding) {
        return new JsonCompiledSchemaRegistry(Objects.requireNonNull(loader, "loader"),
                ValueReaderFactoryRegistry.bind(Objects.requireNonNull(binding, "binding")), Mode.BIND);
    }

    /** The read mode this registry's readers are compiled for. */
    public Mode mode() {
        return mode;
    }

    /**
     * The compiled schema for {@code uri}, compiling it on first use, or empty when the loader has none.
     *
     * <p>The identity is canonicalised first ([TSON-DATA] §2.2.1: scheme stripped, query stripped), so the
     * cache and the loader agree about what one schema is however a caller spelled it.
     */
    public Optional<JsonCompiledSchema> get(String uri) {
        String identity = CanonicalIdentity.canonicalize(uri);
        JsonCompiledSchema hit = compiled.get(identity);
        if (hit != null) {
            return Optional.of(hit);
        }
        return loader.load(identity).map(linked -> {
            // A value's own `$schema` resolves back through here (§8.5), in this registry's mode and cache.
            JsonCompiledSchema built = JsonSchemaCompiler.compile(linked, factories, this::get);
            JsonCompiledSchema raced = compiled.putIfAbsent(identity, built);
            return raced != null ? raced : built;
        });
    }
}
