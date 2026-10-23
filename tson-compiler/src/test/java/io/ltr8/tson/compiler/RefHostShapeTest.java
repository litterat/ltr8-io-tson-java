package io.ltr8.tson.compiler;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.consumer.RhKindText;
import io.ltr8.tson.compiler.consumer.RhRefText;
import io.ltr8.tson.schema.meta.Namespace;
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
 * A name at a kind- or reference-typed slot of a value body is judged whatever the host component's Java type: the
 * slot declares the spelling and the linker judges the name by the slot's declared type, so a {@code String}
 * component receives the name as text and the name is judged exactly as under a {@code TypeRef} one. What is no name
 * -- a quoted token -- is refused at the read under either host.
 */
class RefHostShapeTest {

    private static final String META = "https://example.test/ref-host-meta.tn";

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put(META, """
                !!id:"https://example.test/ref-host-meta.tn"
                !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/38/m/meta.tn"
                {
                  rh_method         => { request?: kind<record> }
                  rh_interface      => namespace & { key_type: = identifier  value_type: = rh_method }
                  rh_ref_type_ref   => { method: ref<rh_method> }
                  rh_ref_text       => { method: ref<rh_method> }
                  rh_kind_type_ref  => { request: kind<record> }
                  rh_kind_text      => { request: kind<record> }
                  rh_ref_type_refs  => namespace & { key_type: = identifier  value_type: = rh_ref_type_ref }
                  rh_ref_texts      => namespace & { key_type: = identifier  value_type: = rh_ref_text }
                  rh_kind_type_refs => namespace & { key_type: = identifier  value_type: = rh_kind_type_ref }
                  rh_kind_texts     => namespace & { key_type: = identifier  value_type: = rh_kind_text }
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

    /** A schema under the meta above declaring an interface and {@code more}. */
    private static String schema(String label, String more) {
        String id = "https://example.test/ref-host-" + label + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!meta:"https://example.test/ref-host-meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  order  => { sku: text }
                  orders => !rh_interface { place => { request: order } }
                  %s
                }
                """.formatted(id, more));
        return id;
    }

    private static List<Diagnostic> problems(String id) {
        List<Diagnostic> problems = new ArrayList<>();
        registry().resolveLinked(id, problems::add);
        return problems;
    }

    /** The value under the key {@code one} of the namespace entry {@code entry}. */
    private static Object held(String id, String entry) {
        return ((Namespace<?>) registry().resolveLinked(id).schema().entries().get(entry)).get("one");
    }

    private static void refused(String id, String... expected) {
        List<Diagnostic> problems = problems(id);
        assertEquals(1, problems.size(), problems.toString());
        for (String fragment : expected) {
            assertTrue(problems.getFirst().message().contains(fragment),
                    "expected '" + fragment + "' in: " + problems.getFirst().message());
        }
    }

    // ── A TypeRef host ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aReferenceWithATypeRefHostIsJudged() {
        refused(schema("ref-type-ref", "b => !rh_ref_type_refs { one => { method: nobody.place } }"),
                "'nobody' is no entry of this schema or its imports");
    }

    @Test
    void aKindWithATypeRefHostIsJudged() {
        refused(schema("kind-type-ref", "k => !rh_kind_type_refs { one => { request: no_such } }"),
                "'no_such' is no type declared in this schema or its imports");
    }

    // ── A String host ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aSoundReferenceWithATextHostResolves() {
        String id = schema("ref-text-sound", "b => !rh_ref_texts { one => { method: orders.place } }");
        assertEquals(List.of(), problems(id));
        RhRefText bound = assertInstanceOf(RhRefText.class, held(id, "b"));
        assertEquals("orders.place", bound.method());
    }

    @Test
    void aReferenceWithATextHostIsJudged() {
        refused(schema("ref-text", "b => !rh_ref_texts { one => { method: nobody.place } }"),
                "'b' at /one/method", "'nobody' is no entry of this schema or its imports");
    }

    @Test
    void aReferenceToTheWrongKindOfValueWithATextHostIsJudged() {
        refused(schema("ref-text-kind", "b => !rh_ref_texts { one => { method: order } }"),
                "wants a 'rh_method'", "'order' is an entry whose body is built by 'record'");
    }

    @Test
    void aQuotedReferenceWithATextHostIsRefused() {
        refused(schema("ref-text-quoted", "b => !rh_ref_texts { one => { method: \"orders.place\" } }"),
                "expected the name of a 'rh_method' value", "which is text rather than a name");
    }

    @Test
    void aSoundKindWithATextHostResolves() {
        String id = schema("kind-text-sound", "k => !rh_kind_texts { one => { request: order } }");
        assertEquals(List.of(), problems(id));
        assertEquals("order", assertInstanceOf(RhKindText.class, held(id, "k")).request());
    }

    @Test
    void aKindWithATextHostIsJudged() {
        refused(schema("kind-text", "k => !rh_kind_texts { one => { request: no_such } }"),
                "'k' at /one/request", "'no_such' is no type declared in this schema or its imports");
    }

    @Test
    void aKindNamingANamespaceEntryWithATextHostIsJudged() {
        refused(schema("kind-text-namespace", "k => !rh_kind_texts { one => { request: orders } }"),
                "'orders' is a value of the namespace type 'rh_interface'", "not a type");
    }

    @Test
    void aQuotedKindWithATextHostIsRefused() {
        refused(schema("kind-text-quoted", "k => !rh_kind_texts { one => { request: \"order\" } }"),
                "a position typed by a kind holds the name of a type");
    }
}
