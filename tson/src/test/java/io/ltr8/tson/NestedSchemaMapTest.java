package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.tree.TsonRecord;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema map nested as an entry, {@code geo => !schema { … }}: its members are declarations of the document under
 * their paths, every name resolves from the root, the nested map's value holds its members by their own names, and
 * resolved output writes the map nested. A tag in data is a qualified name, resolved by the same walk, and kept as
 * written where nothing resolves it.
 */
class NestedSchemaMapTest {

    private static final String ID = "https://example.test/geo.tn";

    private static final String GEO = """
            !!id:"https://example.test/geo.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              geo => !schema {
                address  => { street: text }
                location => { at: geo.address  tags?: [text] }
                inner    => !schema { deep => { x: int32 } }
              }
              place => { name: text  where?: geo.location }
            }""";

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, GEO)))));
    }

    @Test
    void aNestedMapsValueHoldsItsMembersByTheirOwnNames() {
        TsonSchema schema = tson().resolve(GEO).schema();
        Namespace<?> geo = assertInstanceOf(Namespace.class,
                assertInstanceOf(ValueEntry.class, schema.entries().get("geo")).value());
        assertEquals(List.of("address", "location", "inner"), List.copyOf(geo.keySet()));
        assertSame(schema.entries().get("geo.address"), geo.get("address"));
        Namespace<?> inner = assertInstanceOf(Namespace.class,
                assertInstanceOf(ValueEntry.class, geo.get("inner")).value());
        assertSame(schema.entries().get("geo.inner.deep"), inner.get("deep"));
    }

    /** Resolved output writes a member inside its map, never under its path at the root. */
    @Test
    void resolvedOutputWritesTheNestedMapNested() {
        Tson tson = tson();
        TsonSchema schema = tson.resolve(GEO).schema();
        Map<String, Top> own = new LinkedHashMap<>();
        schema.entries().forEach((name, value) -> {
            if (name.startsWith("geo") || name.equals("place")) {
                own.put(name, value);
            }
        });
        String written = tson.objectWriter().toTson(Namespace.of(TsonSchema.SCHEMA, own)).replaceAll("\\s+", " ");
        assertTrue(written.startsWith("!schema { geo => !schema { address => !record "), written);
        assertTrue(written.contains("inner => !schema { deep => !record "), written);
        assertTrue(written.contains("at => { type: geo.address "), written);
        assertFalse(written.contains("geo.address =>"), written);
        assertFalse(written.contains("\"geo."), written);
    }

    /** A qualified tag names a nested member, and the read names the value's type by its path. */
    @Test
    void aQualifiedTagReadsAgainstTheNestedMember() {
        Tson tson = tson();
        tson.resolve(GEO);
        TsonValue read = tson.treeReader().read("!!import:\"" + ID + "\"\n"
                + "!place { name: home  where: { at: { street: Main } } }");
        TsonRecord where = assertInstanceOf(TsonRecord.class, ((TsonRecord) read).get("where"));
        assertEquals(Optional.of("geo.location"), where.typeRef());
        assertEquals(Optional.of("geo.address"), where.get("at").typeRef());
        assertEquals(List.of(), tson.validate("!!import:\"" + ID + "\"\n!geo.inner.deep { x: 1 }"));
        List<Diagnostic> missing = tson.validate("!!import:\"" + ID + "\"\n!geo.region { x: 1 }");
        assertEquals(List.of(Diagnostic.Code.UNKNOWN_TYPE), missing.stream().map(Diagnostic::code).toList());
    }

    /** Where nothing resolves a tag, a dotted one is kept as written, as any tag is ([TSON-DATA] §4). */
    @Test
    void aSchemalessReadKeepsAQualifiedTag() {
        TsonValue read = tson().treeReader().preservingUnknownTypeRefs()
                .readWithoutSchema("!geo.address { street: Main }");
        assertEquals(Optional.of("geo.address"), read.typeRef());
    }
}
