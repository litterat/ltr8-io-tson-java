package io.ltr8.tson.json;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.json.reader.JsonValueReaderFactoryRegistry;
import io.ltr8.tson.json.reader.JsonValueReaderFactoryResolver;
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
 * <p><b>The read mode is which factory set this holds</b>, exactly as it is on the TSON side: today tree
 * mode, which validates and hands back the JSON.
 *
 * <p>Safe for concurrent reads. Two threads racing to compile one identity may both compile it; the map
 * keeps one and the loser's copy is discarded, which is cheaper than holding a lock across a resolve. A
 * cache hit -- every read, in an application that named its schemas at startup -- takes no lock at all.
 */
public final class JsonCompiledSchemaRegistry {

    private final TsonSchemaLoader loader;
    private final JsonValueReaderFactoryResolver factories;
    private final Map<String, JsonCompiledSchema> compiled = new ConcurrentHashMap<>();

    private JsonCompiledSchemaRegistry(TsonSchemaLoader loader, JsonValueReaderFactoryResolver factories) {
        this.loader = loader;
        this.factories = factories;
    }

    /** Tree mode over {@code loader}: a read validates and hands back the document. */
    public static JsonCompiledSchemaRegistry tree(TsonSchemaLoader loader) {
        return new JsonCompiledSchemaRegistry(Objects.requireNonNull(loader, "loader"),
                JsonValueReaderFactoryRegistry.tree());
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
            JsonCompiledSchema built = JsonSchemaCompiler.compile(linked, factories);
            JsonCompiledSchema raced = compiled.putIfAbsent(identity, built);
            return raced != null ? raced : built;
        });
    }
}
