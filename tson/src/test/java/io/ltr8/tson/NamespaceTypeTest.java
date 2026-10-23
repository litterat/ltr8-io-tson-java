package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A namespace type -- a constructor IS-A the kernel's {@code namespace}, pinning its {@code key_type} and {@code
 * value_type} by composition -- is read as the map it describes, never as a record of those facets. It IS-A {@code
 * top}, so a meta layer declares it, and a schema importing that layer types a field by it.
 */
class NamespaceTypeTest {

    private static final String META = "https://example.test/meta-headers.tn";
    private static final String USER = "https://example.test/request.tn";

    private static final String META_TEXT = """
            !!id:"https://example.test/meta-headers.tn"
            !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/38/m/meta.tn"
            {
              header_name => !identifier_type { continue_add: "-" }
              headers     => namespace & { key_type: = header_name  value_type: = text }
              %s
            }
            """;

    private static final String USER_TEXT = """
            !!id:"https://example.test/request.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://example.test/meta-headers.tn"
            {
              request => { headers: headers }
            }
            """;

    private static Tson tson(String more) {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of(META, META_TEXT.formatted(more), USER, USER_TEXT)))));
    }

    private static List<Diagnostic> read(String request) {
        return tson("").validate("!!schema:\"" + USER + "\"\n!request " + request);
    }

    @Test
    void aValueIsReadAsAMap() {
        assertEquals(List.of(), read("{ headers: { accept => \"text/plain\"  user-agent => \"probe\" } }"));
    }

    @Test
    void aKeyIsJudgedByTheKeyType() {
        List<Diagnostic> problems = read("{ headers: { \"1st\" => \"text/plain\" } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of("/headers/1st"), problems.getFirst().path().map(Object::toString),
                problems::toString);
    }

    @Test
    void aValueIsJudgedByTheValueType() {
        assertEquals(1, read("{ headers: { accept => [text] } }").size());
    }

    /** The record of facets is never what is read: a field named for one is a key like any other. */
    @Test
    void theFacetsAreNoFields() {
        assertEquals(List.of(), read("{ headers: { key_type => \"x\" } }"));
    }

    @Test
    void theLinkedSchemaRecordsTheMapAndTheKind() {
        TsonLinkedSchema meta = tson("").bindRegistry().core().resolveLinked(META);

        assertEquals(Optional.of(new MapBody.Namespace(new MapBody(TypeRef.of("header_name"), TypeRef.of("text"),
                false, false, Optional.empty(), Optional.empty()), true)), meta.namespace("headers"));
        assertEquals(EntryKind.NAMESPACE, meta.kindOf("headers"));
        assertEquals(Optional.empty(), meta.namespace("header_name"));
    }

    /** {@code namespace} IS-A {@code top} alone: a chain reaching it and a base kind describes nothing. */
    @Test
    void aNamespaceTypeIsNoBaseKindBesides() {
        List<Diagnostic> problems = tson("").validateSchema(META_TEXT.formatted("""
                  both => namespace & data & { key_type: = header_name  value_type: = text }
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("reaches 2 base kinds"), problems::toString);
    }

    /**
     * The kernel's {@code field_map} states its key family by default, the host record supplying it: its keys are
     * read by spelling and judged by the record's {@code name_type}, so the map it is read as takes any key.
     */
    @Test
    void aDefaultKeyFamilyIsTheHostsAndIsReadBySpelling() {
        MapBody.Namespace fields = tson("").bindRegistry().core().resolveLinked(META).namespace("field_map")
                .orElseThrow();

        assertEquals(TypeRef.of("field_name"), fields.map().keyType());
        assertEquals(false, fields.keysPinned());
        assertEquals(TypeRef.of(MapBody.Namespace.HOST_KEYS), fields.read().keyType());
    }

    /** A namespace type IS-A {@code top}, so only a schema the meta-kernel governs declares one. */
    @Test
    void aNamespaceTypeIsDeclaredUnderTheMetaKernel() {
        List<Diagnostic> problems = tson("").validateSchema("""
                !!id:"https://example.test/governed.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/meta.tn"
                {
                  hs => namespace & { key_type: = identifier  value_type: = text }
                }
                """);

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("composes with the kernel's 'namespace', a constructor"),
                problems::toString);
    }

    /** A namespace type leaving a facet open describes no map, unless it is abstract. */
    @Test
    void anOpenFacetIsRefusedUnlessAbstract() {
        List<Diagnostic> problems = tson("").validateSchema(META_TEXT.formatted("""
                  keyed          => namespace & { key_type: = header_name }
                  abstract_keyed => abstract namespace & { key_type: = header_name }
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("'keyed' is a namespace type"), problems::toString);
    }
}
