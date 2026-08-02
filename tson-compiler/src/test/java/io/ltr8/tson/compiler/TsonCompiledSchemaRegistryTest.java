package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The two per-mode flavors of {@link TsonCompiledSchemaRegistry} ({@link TsonCompiledSchemaRegistry#dom}
 * and {@link TsonCompiledSchemaRegistry#bind}) over one shared {@link TsonCompiledMetaRegistry} resolution
 * core. Proves the overlay the split is built for: a single bind-mode core resolves a user schema once,
 * and each registry reads the same schema in its own mode -- DOM to a plain {@code Map}, object-binding to
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
    void domRegistryReadsAUserSchemaToPlainMaps() {
        TsonCompiledSchemaRegistry dom = TsonCompiledSchemaRegistry.dom(core());
        Object value = dom.get(SCHEMA_ID).get("my_record").read(DATA);
        Map<?, ?> map = assertInstanceOf(Map.class, value);
        assertEquals(7, ((Number) map.get("value")).intValue());
    }

    @Test
    void bindRegistryReadsAUserSchemaToBoundObjects() {
        DataBindContext context = TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(MANUAL_BINDER).build());
        TsonCompiledSchemaRegistry bind = TsonCompiledSchemaRegistry.bind(core(), context);
        Object value = bind.get(SCHEMA_ID).get("my_record").read(DATA);
        assertEquals(new MyRecord(7), value);
    }

    @Test
    void oneCoreBacksBothModes() {
        TsonCompiledMetaRegistry core = core();
        TsonCompiledSchemaRegistry dom = TsonCompiledSchemaRegistry.dom(core);
        DataBindContext context = TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(MANUAL_BINDER).build());
        TsonCompiledSchemaRegistry bind = TsonCompiledSchemaRegistry.bind(core, context);

        Object domValue = dom.get(SCHEMA_ID).get("my_record").read(DATA);
        Object bindValue = bind.get(SCHEMA_ID).get("my_record").read(DATA);

        assertInstanceOf(Map.class, domValue);
        assertEquals(new MyRecord(7), bindValue);
    }
}
