package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonCompiledSchemaRegistry;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.CanonicalIdentity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MapBindReader} against a real bound Java {@code Map}, where the tree's own no-value node is not
 * available and an entry with no value has to survive as a {@code null} the target map actually holds.
 */
class MapBindReaderTest {

    private static final String ID = "https://example.test/catalogue.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/catalogue.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              catalogue => { entries: {text => text?} }
              ranked    => !map { key_type: text  value_type: int32  ordered: true }
              league    => { table: ranked }
            }
            """;

    /** The Java shape {@code catalogue} binds to. */
    public record Catalogue(Map<String, String> entries) {
    }

    private static Catalogue read(String document) {
        return (Catalogue) read(Catalogue.class, "catalogue", document);
    }

    /** {@code document} read as {@code entry}, which is bound to {@code type}. */
    private static Object read(Class<?> type, String entry, String document) {
        DataNameBinder binder = schemaTypeName -> entry.equals(schemaTypeName)
                ? type
                : SchemaMetaNameBinder.INSTANCE.resolve(schemaTypeName);
        DataBindContext context =
                DataBindContext.builder().nameBinder(binder).registerAtoms(AtomContext.hostTypes()).build();
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledSchema compiled = TsonCompiledSchemaRegistry.bind(
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source),
                context).get(ID);
        return compiled.get(entry).read(TestDocuments.document(document));
    }

    /**
     * Under {@code {text => text?}} an entry's value may be the void sentinel, so the entry is present with
     * a void value ([TSON-DATA] §2.9) -- which on the bind side means the key is in the map and maps to
     * {@code null}, distinguishable from a key the document never stated.
     */
    @Test
    void aVoidEntryValueBindsAsAKeyPresentWithNoValue() {
        Catalogue catalogue = read("{ entries: { \"a\" => _  \"b\" => \"two\" } }");

        assertEquals(2, catalogue.entries().size());
        assertTrue(catalogue.entries().containsKey("a"));
        assertNull(catalogue.entries().get("a"));
        assertEquals("two", catalogue.entries().get("b"));
    }

    // ── Ordered maps ─────────────────────────────────────────────────────
    // An ordered map needs a component that keeps insertion order, the order the document wrote; an unordered
    // map takes either kind, since keeping its order loses nothing.

    public record League(SequencedMap<String, Integer> table) {
    }

    public record LeagueAsMap(Map<String, Integer> table) {
    }

    public record LinkedCatalogue(LinkedHashMap<String, String> entries) {
    }

    private static final String LEAGUE = "{ table: { zeta => 1  alpha => 2  mid => 3 } }";

    @Test
    void anOrderedMapKeepsTheOrderTheDocumentWrote() {
        League league = (League) read(League.class, "league", LEAGUE);
        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(league.table().keySet()));
    }

    @Test
    void anOrderedMapBoundToAComponentThatDoesNotKeepOrderFailsTheCompile() {
        BindMismatchException e = assertThrows(BindMismatchException.class,
                () -> read(LeagueAsMap.class, "league", LEAGUE));
        assertTrue(e.getMessage().contains("field 'table' is an ordered map, and component 'table' binds "
                + "java.util.Map, which does not keep insertion order -- declare a SequencedMap"), e.getMessage());
    }

    @Test
    void anUnorderedMapBindsToAComponentThatKeepsOrder() {
        LinkedCatalogue catalogue = (LinkedCatalogue) read(LinkedCatalogue.class, "catalogue",
                "{ entries: { b => two  a => one } }");
        assertEquals(List.of("b", "a"), List.copyOf(catalogue.entries().keySet()));
    }

    /** Read directly, with no component to declare a class, an ordered map is built as one that keeps its order. */
    @Test
    void anOrderedMapReadDirectlyKeepsItsOrder() {
        Object table = read(Map.class, "ranked", "{ zeta => 1  alpha => 2  mid => 3 }");
        assertInstanceOf(LinkedHashMap.class, table);
        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(((Map<?, ?>) table).keySet()));
    }
}
