package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonCompiledSchemaRegistry;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MapBindReader} against a real bound Java {@code Map}, where the tree's own no-value node is not
 * available and an entry with no value has to survive as a {@code null} the target map actually holds.
 */
class MapBindReaderTest {

    private static final String ID = "https://example.test/catalogue.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/catalogue.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              catalogue => { entries: {text => text?} }
            }
            """;

    /** The Java shape {@code catalogue} binds to. */
    public record Catalogue(Map<String, String> entries) {
    }

    private static Catalogue read(String document) {
        DataNameBinder binder = schemaTypeName -> "catalogue".equals(schemaTypeName)
                ? Catalogue.class
                : SchemaMetaNameBinder.INSTANCE.resolve(schemaTypeName);
        DataBindContext context =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledSchema compiled = TsonCompiledSchemaRegistry.bind(
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source),
                context).get(ID);
        return (Catalogue) compiled.get("catalogue").read(TestDocuments.document(document));
    }

    /**
     * Under {@code {text => text?}} an entry's value may be the absent sentinel, so the entry is present with
     * an absent value ([TSON-DATA] §2.9) -- which on the bind side means the key is in the map and maps to
     * {@code null}, distinguishable from a key the document never stated.
     */
    @Test
    void anAbsentEntryValueBindsAsAKeyPresentWithNoValue() {
        Catalogue catalogue = read("{ entries: { \"a\" => _  \"b\" => \"two\" } }");

        assertEquals(2, catalogue.entries().size());
        assertTrue(catalogue.entries().containsKey("a"));
        assertNull(catalogue.entries().get("a"));
        assertEquals("two", catalogue.entries().get("b"));
    }
}
