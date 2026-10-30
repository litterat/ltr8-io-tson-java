package io.ltr8.tson.compiler;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.consumer.SbOp;
import io.ltr8.tson.compiler.consumer.SbResource;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A kind-typed slot that only a tagged subtype declares is judged wherever the value sits: held directly under a
 * namespace key, or one record field further down. {@code sb_op} declares {@code request: type_kind}; its base {@code
 * sb_endpoint} declares nothing, and {@code sb_resource.endpoints} holds the family by its base. The slot's declared
 * type comes with the name from the reader that read it, so nothing walks the declared types back to the slot.
 */
class SubtypeBehindFieldTest {

    private static final String META = "https://example.test/sb-meta.tn";

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put(META, """
                !!id:"https://example.test/sb-meta.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  sb_endpoint  => abstract {}
                  sb_op        => sb_endpoint & { request?: type_kind }
                  sb_ops       => !namespace { key_type: identifier  value_type: sb_endpoint }
                  sb_resource  => { endpoints: {identifier => sb_endpoint} }
                  sb_resources => !namespace { key_type: identifier  value_type: sb_resource }
                }
                """);
    }

    private static final SchemaSource SOURCE = uri -> {
        for (Map.Entry<String, String> document : DOCUMENTS.entrySet()) {
            if (CanonicalIdentity.sameIdentity(uri, document.getKey())) {
                return document.getValue();
            }
        }
        throw new IllegalStateException("unexpected fetch: " + uri);
    };

    private static final DataNameBinder HOSTS = new DataNameBinder.DefaultDataNameBinder(
            Set.of("io.ltr8.tson.compiler.consumer"), Map.of());

    private static TsonCompiledMetaRegistry registry() {
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.contextExtendedWith(HOSTS), SOURCE);
    }

    private static List<Diagnostic> problems(String label, String entry) {
        String id = "https://example.test/sb-" + label + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!import:"https://example.test/sb-meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  order => { sku: text }
                  %s
                }
                """.formatted(id, entry));
        List<Diagnostic> problems = new ArrayList<>();
        registry().resolveLinked(id, problems::add);
        return problems;
    }

    private static String underAKey(String name) {
        return "r => !sb_ops { get => !sb_op { request: " + name + " } }";
    }

    private static String behindAField(String name) {
        return "r => !sb_resources { o => { endpoints: { get => !sb_op { request: " + name + " } } } }";
    }

    // ── The value held under a namespace key ────────────────────────────────────────────────────────

    @Test
    void aSoundNameUnderAKeyResolves() {
        assertEquals(List.of(), problems("key-sound", underAKey("order")));
    }

    @Test
    void anUnsoundNameUnderAKeyIsJudged() {
        List<Diagnostic> problems = problems("key-unsound", underAKey("no_such"));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("'no_such' is no type declared"),
                problems.getFirst().message());
    }

    // ── The same value behind a record field ────────────────────────────────────────────────────────

    @Test
    void aSoundNameBehindARecordFieldResolves() {
        assertEquals(List.of(), problems("field-sound", behindAField("order")));
        Namespace<?> resources = assertInstanceOf(Namespace.class, ((ValueEntry) registry()
                .resolveLinked("https://example.test/sb-field-sound.tn").schema().entries().get("r")).value());
        SbResource resource = assertInstanceOf(SbResource.class, resources.get("o"));
        assertEquals("order", assertInstanceOf(SbOp.class, resource.endpoints().get("get")).request().name());
    }

    @Test
    void anUnsoundNameBehindARecordFieldIsJudged() {
        List<Diagnostic> problems = problems("field-unsound", behindAField("no_such"));
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems::toString);
        assertTrue(problems.getFirst().message().contains("'r' at /o/endpoints/get/request"),
                problems.getFirst().message());
        assertTrue(problems.getFirst().message().contains("'no_such' is no type declared"),
                problems.getFirst().message());
    }
}
