package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A namespace type -- an instance of the kernel's {@code namespace}, stating its {@code key_type} and {@code
 * value_type} -- is read as the map it describes. Any schema declares one; a schema importing it holds a value of it
 * as an entry, and types a field by it as by any imported type.
 */
class NamespaceTypeTest {

    private static final String META = "https://example.test/meta-headers.tn";

    private static final String META_TEXT = """
            !!id:"https://example.test/meta-headers.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              header_name => !unicode.identifier_type { continue_add: "-" }
              headers     => !meta.namespace { key_type: header_name  value_type: text }
              %s
            }
            """;

    private static final String USER_TEXT = """
            !!id:"https://example.test/request.tn"
            !!import:"https://example.test/meta-headers.tn"
            !schema {
              defaults => !headers %s
            }
            """;

    private static Tson tson(String more) {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of(META, META_TEXT.formatted(more))))));
    }

    /** The problems with a governed schema whose one entry is {@code !headers} applied to {@code map}. */
    private static List<Diagnostic> read(String map) {
        return tson("").validateSchema(USER_TEXT.formatted(map));
    }

    @Test
    void aValueIsReadAsAMap() {
        assertEquals(List.of(), read("{ accept => \"text/plain\"  user-agent => \"probe\" }"));
    }

    @Test
    void aKeyIsJudgedByTheKeyType() {
        List<Diagnostic> problems = read("{ \"1st\" => \"text/plain\" }");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("1st"), problems::toString);
    }

    @Test
    void aValueIsJudgedByTheValueType() {
        assertEquals(1, read("{ accept => [text] }").size());
    }

    /** The record of facets is never what is read: a field named for one is a key like any other. */
    @Test
    void theFacetsAreNoFields() {
        assertEquals(List.of(), read("{ key_type => \"x\" }"));
    }

    /** A schema importing the layer types a field by its namespace type, as by any imported type. */
    @Test
    void aSchemaImportingTheLayerTypesAFieldByIt() {
        List<Diagnostic> problems = tson("").validateSchema("""
                !!id:"https://example.test/request.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://example.test/meta-headers.tn"
                !schema {
                  request => { headers: headers }
                }
                """);

        assertEquals(List.of(), problems);
    }

    @Test
    void theLinkedSchemaRecordsTheMapAndTheKind() {
        TsonLinkedSchema meta = tson("").bindRegistry().core().resolveLinked(META);

        assertEquals(Optional.of(NamespaceBody.of(TypeRef.of("header_name"), TypeRef.of("text"), false)),
                meta.namespace("headers"));
        assertEquals(EntryKind.PRODUCT, meta.kindOf("headers"));
        assertEquals(Optional.of("meta.namespace"), meta.schema().constructorOf("headers"));
        assertEquals(Optional.empty(), meta.namespace("header_name"));
    }

    private static final String SMALL = "small => !meta.namespace { key_type: header_name  value_type: text  "
            + "min_items: 1  max_items: 2 }";

    /** The problems with a governed schema holding {@code !small} applied to {@code map}. */
    private static List<Diagnostic> readSmall(String map) {
        return tson(SMALL).validateSchema("""
                !!id:"https://example.test/request.tn"
                !!import:"https://example.test/meta-headers.tn"
                !schema {
                  defaults => !small %s
                }
                """.formatted(map));
    }

    /**
     * {@code namespace} refines {@code map}, so its size bounds are a map's: a value entry of a namespace type holds
     * no fewer than {@code min_items} entries and no more than {@code max_items}.
     */
    @Test
    void aNamespaceTypesSizeBoundsAreAMapsAndAValueMeetsThem() {
        assertEquals(List.of(), readSmall("{ accept => \"text/plain\" }"));
        assertEquals(List.of(), readSmall("{ accept => \"text/plain\"  user-agent => \"probe\" }"));
        assertTrue(readSmall("{}").toString().contains("fewer than the minimum 1"));
        List<Diagnostic> over = readSmall("{ a => \"x\"  b => \"y\"  c => \"z\" }");
        assertEquals(1, over.size(), over::toString);
        assertTrue(over.toString().contains("more than the maximum 2"), over::toString);
    }

    /** And a data document's value at a field typed by one meets them too. */
    @Test
    void aDataValueOfABoundedNamespaceTypeMeetsItsBounds() {
        Tson tson = tson(SMALL + "\n  request => { headers: small }");
        String data = """
                !!import:"https://example.test/meta-headers.tn"
                !request { headers: %s }
                """;

        assertEquals(List.of(), tson.validate(data.formatted("{ accept => \"text/plain\" }")));
        List<Diagnostic> problems = tson.validate(data.formatted("{}"));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("fewer than the minimum 1"), problems::toString);
    }

    /** A namespace type whose floor is above its ceiling admits no value, as a map type's does. */
    @Test
    void aNamespaceTypeWhoseBoundsCrossIsRefused() {
        List<Diagnostic> problems = tson("").validateSchema("""
                !!id:"https://example.test/crossed.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  hs => !meta.namespace { key_type: meta.identifier  value_type: text  min_items: 3  max_items: 1 }
                }
                """);

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("min_items"), problems::toString);
    }

    /**
     * The kernel's {@code field_map} is an ordered map keyed by {@code text}, not a namespace type: a field name is
     * text within its own record, judged by the record's {@code name_type}, and no qualified name selects one.
     */
    @Test
    void aRecordsFieldMapIsAnOrderedMapKeyedByText() {
        TsonLinkedSchema meta = tson("").bindRegistry().core().resolveLinked(META);

        assertEquals(Optional.empty(), meta.namespace("meta.field_map"));
        MapBody fields = assertInstanceOf(MapBody.class, meta.schema().entries().get("meta.field_map"));
        assertEquals(TypeRef.of("text"), fields.keyType());
        assertEquals(TypeRef.of("meta.record_field"), fields.valueType());
        assertTrue(fields.ordered());
    }

    /**
     * A namespace type IS-A {@code top}, and any schema declares one: the kernel's {@code namespace} is in the
     * closure of every schema reaching meta.tn ([TSON-SCHEMA] §4.1).
     */
    @Test
    void anySchemaDeclaresANamespaceType() {
        List<Diagnostic> problems = tson("").validateSchema("""
                !!id:"https://example.test/governed.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  hs => !meta.namespace { key_type: meta.identifier  value_type: text }
                }
                """);

        assertEquals(List.of(), problems);
    }

    /** A namespace type states the type of its values: an instance of {@code namespace} leaving it out is none. */
    @Test
    void aNamespaceTypeWithoutAValueTypeIsRefused() {
        List<Diagnostic> problems = tson("").validateSchema(META_TEXT.formatted("""
                  keyed => !meta.namespace { key_type: header_name }
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("value_type"), problems::toString);
    }

    /** A namespace type states the family of its keys: an instance of {@code namespace} leaving it out is none. */
    @Test
    void aNamespaceTypeWithoutAKeyTypeIsRefused() {
        List<Diagnostic> problems = tson("").validateSchema(META_TEXT.formatted("""
                  keyed => !meta.namespace { value_type: text }
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("key_type"), problems::toString);
    }
}
