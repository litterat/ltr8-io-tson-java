package io.ltr8.tson.schema;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.ValueEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A nested schema map's value and its members' path entries, kept in step wherever a schema is built. */
class SchemaPathsTest {

    private static final Top ADDRESS = RecordBody.of(Namespace.<RecordField>of("field_map", Map.of()));
    private static final Top DEEP = new ValueEntry("deep_type", "a value");

    private static ValueEntry map(Map<String, Top> members) {
        return new ValueEntry(TsonSchema.SCHEMA, Namespace.of(TsonSchema.SCHEMA, members));
    }

    @Test
    void aPathNamesItsMapAndItsMember() {
        assertTrue(SchemaPaths.isPath("geo.address"));
        assertFalse(SchemaPaths.isPath("geo"));
        assertEquals("geo.inner", SchemaPaths.parent("geo.inner.deep"));
        assertEquals("", SchemaPaths.parent("geo"));
        assertEquals("deep", SchemaPaths.member("geo.inner.deep"));
        assertEquals("geo", SchemaPaths.member("geo"));
    }

    /** A schema built from path entries rebuilds each nested map from them, innermost first. */
    @Test
    void aSchemaRebuildsEachNestedMapFromItsPathEntries() {
        AnnotatedMap<String, Top> entries = new AnnotatedMap<>();
        entries.put("geo.address", ADDRESS);
        entries.put("geo.inner.deep", DEEP);
        entries.put("geo.inner", map(Map.of()));
        entries.put("geo", map(Map.of()));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn", List.of(), entries, false);
        Namespace<?> geo = assertInstanceOf(Namespace.class, ((ValueEntry) schema.entries().get("geo")).value());
        assertEquals(List.of("address", "inner"), List.copyOf(geo.keySet()));
        assertSame(ADDRESS, geo.get("address"));
        Namespace<?> inner = assertInstanceOf(Namespace.class, ((ValueEntry) geo.get("inner")).value());
        assertSame(DEEP, inner.get("deep"));
    }

    /** A schema built from a nested map alone, as a resolved form reads back, keys each member under its path. */
    @Test
    void aSchemaKeysANestedMapsMembersUnderTheirPaths() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("geo", map(Map.of("address", ADDRESS, "inner", map(Map.of("deep", DEEP)))));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn", List.of(), entries);
        assertSame(ADDRESS, schema.entries().get("geo.address"));
        assertSame(DEEP, schema.entries().get("geo.inner.deep"));
        assertTrue(SchemaPaths.isNestedMap(schema.entries().get("geo.inner")));
    }

    /** A resolved form read as data holds a nested map as a bare namespace; read back, it is keyed by path too. */
    @Test
    void aReadBackFormIsKeyedByPath() {
        Map<String, Object> read = new LinkedHashMap<>();
        read.put("geo", Namespace.of(TsonSchema.SCHEMA, Map.of("address", ADDRESS)));
        Map<String, Object> keyed = SchemaPaths.withPaths(read);
        assertEquals(List.of("geo", "geo.address"), List.copyOf(keyed.keySet()));
        assertInstanceOf(ValueEntry.class, keyed.get("geo"));
        assertSame(ADDRESS, keyed.get("geo.address"));
    }
}
