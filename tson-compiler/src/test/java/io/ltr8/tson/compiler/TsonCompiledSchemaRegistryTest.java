package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.tree.TsonNode;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two per-mode flavors of {@link TsonCompiledSchemaRegistry} ({@link TsonCompiledSchemaRegistry#tree}
 * and {@link TsonCompiledSchemaRegistry#bind}) over one shared {@link TsonCompiledMetaRegistry} resolution
 * core. Proves the overlay the split is built for: a single bind-mode core resolves a user schema once,
 * and each registry reads the same schema in its own mode -- a queryable TsonNode tree, object-binding to
 * a caller's own Java class -- so the "read mode" is which registry you hold, not a parameter threaded
 * through compile.
 */
class TsonCompiledSchemaRegistryTest {

    /** The Java shape {@code my_record => { value: int32 }} binds to in object-binding mode -- outside {@code io.ltr8.tson.schema.meta}, so {@link SchemaMetaNameBinder}'s own convention can't find it by accident. */
    public record MyRecord(Integer value) {
    }

    private static final String SCHEMA_ID = "https://example.test/read-registry.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/read-registry.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              my_record => { value: int32 }
            }
            """;
    private static final String DATA = "{ value: 7 }";

    /** Resolves {@code my_record} to {@link MyRecord}, falling back to {@link SchemaMetaNameBinder}'s own convention for the composite constructors (record/array/...) the compile still needs. */
    private static final DataNameBinder MANUAL_BINDER = name -> "my_record".equals(name)
            ? MyRecord.class
            : SchemaMetaNameBinder.INSTANCE.resolve(name);

    /** A fresh core with the standard library loaded and the one user schema fetchable through the source. */
    private static TsonCompiledMetaRegistry core() {
        TsonSchemaSource source = uri -> {
            if (TsonSchemaRegistry.canonicalIdentity(uri).equals(TsonSchemaRegistry.canonicalIdentity(SCHEMA_ID))) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
    }

    @Test
    void treeRegistryReadsAUserSchemaIntoATree() {
        TsonCompiledSchemaRegistry tree = TsonCompiledSchemaRegistry.tree(core());
        Object value = tree.get(SCHEMA_ID).get("my_record").read(TestDocuments.document(DATA));
        TsonNode node = assertInstanceOf(TsonNode.class, value);
        assertEquals(7, node.get("value").asNumber().orElseThrow().intValue());
    }

    @Test
    void bindRegistryReadsAUserSchemaToBoundObjects() {
        DataBindContext context = TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(MANUAL_BINDER).build());
        TsonCompiledSchemaRegistry bind = TsonCompiledSchemaRegistry.bind(core(), context);
        Object value = bind.get(SCHEMA_ID).get("my_record").read(TestDocuments.document(DATA));
        assertEquals(new MyRecord(7), value);
    }

    @Test
    void oneCoreBacksBothModes() {
        TsonCompiledMetaRegistry core = core();
        TsonCompiledSchemaRegistry tree = TsonCompiledSchemaRegistry.tree(core);
        DataBindContext context = TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(MANUAL_BINDER).build());
        TsonCompiledSchemaRegistry bind = TsonCompiledSchemaRegistry.bind(core, context);

        Object treeValue = tree.get(SCHEMA_ID).get("my_record").read(TestDocuments.document(DATA));
        Object bindValue = bind.get(SCHEMA_ID).get("my_record").read(TestDocuments.document(DATA));

        assertInstanceOf(TsonNode.class, treeValue);
        assertEquals(new MyRecord(7), bindValue);
    }

    @Test
    void theCoreCompilesOnlyMetaLayerSchemas() {
        TsonCompiledMetaRegistry core = core();

        // meta-kernel and meta.tn are meta-layer (their !!meta is meta-kernel) -- compiled and cached here.
        assertTrue(core.get(TsonBundledSchemas.META_KERNEL_ID).isPresent());
        assertTrue(core.get(TsonBundledSchemas.META_ID).isPresent());

        // core.tn is not a meta (its !!meta is meta.tn) -- resolved+registered, but never compiled here.
        assertTrue(core.schemaRegistry().get(TsonBundledSchemas.CORE_ID).isPresent());
        assertTrue(core.get(TsonBundledSchemas.CORE_ID).isEmpty());
    }

    @Test
    void readingAUserSchemaResolvesItInTheCoreButDoesNotCompileItThere() {
        TsonCompiledMetaRegistry core = core();
        TsonCompiledSchemaRegistry.tree(core).get(SCHEMA_ID).get("my_record").read(TestDocuments.document(DATA));

        // The core resolved+registered the user schema -- its linked form is available -- but never
        // compiled or cached it; the read registry owns the compile, in its own mode.
        assertTrue(core.schemaRegistry().get(SCHEMA_ID).isPresent());
        assertTrue(core.get(SCHEMA_ID).isEmpty());
    }
}
