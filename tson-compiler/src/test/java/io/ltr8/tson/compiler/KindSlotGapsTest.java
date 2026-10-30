package io.ltr8.tson.compiler;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.consumer.GapOp;
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
 * Names in a namespace value at the slots tson-java-http's meta layer ({@code meta-http.tn}) uses, each judged by its
 * slot's declared type.
 *
 * <ol>
 *   <li>An application at a kind-typed slot of a value body is refused with the alias spelling named: nothing lifts
 *       or mints in a value body, so an application there would have no entry to be the name of.
 *   <li>A slot that only the subtype the value's tag selected declares, below a record family base, is judged by
 *       its declared type ({@code SubtypeBehindFieldTest} has the same one record field further down).
 *   <li>A slot's pointer writes the key for a map key that carries annotations.
 * </ol>
 */
class KindSlotGapsTest {

    private static final String META = "https://example.test/gap-meta.tn";

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put(META, """
                !!id:"https://example.test/gap-meta.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  gap_status        => !integer_type { min: 100  max: 599 }
                  gap_reply         => { status: gap_status  body: type_kind }
                  gap_operation     => { request: type_kind  replies?: [gap_reply] }
                  gap_operations    => !namespace { key_type: identifier  value_type: gap_operation }
                  gap_endpoint      => { status?: gap_status ~ 200 }
                  gap_op            => gap_endpoint & { request: type_kind }
                  gap_api           => !namespace { key_type: identifier  value_type: gap_endpoint }
                  gap_replies       => !namespace { key_type: identifier  value_type: gap_reply }
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

    private static final DataNameBinder HOSTS =
            new DataNameBinder.DefaultDataNameBinder(Set.of("io.ltr8.tson.compiler.consumer"), Map.of());

    private static TsonCompiledMetaRegistry registry() {
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.contextExtendedWith(HOSTS), SOURCE);
    }

    /** A schema under the meta above declaring {@code order}, the record template {@code box}, and {@code more}. */
    private static String schema(String label, String more) {
        String id = "https://example.test/gap-" + label + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!import:"https://example.test/gap-meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  order => { sku: text }
                  box   => <T> { v: T }
                  other => !gap_operations { o => { request: order } }
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

    private static void refused(String id, String... expected) {
        List<Diagnostic> problems = problems(id);
        assertEquals(1, problems.size(), problems.toString());
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code(), only.toString());
        for (String fragment : expected) {
            assertTrue(only.message().contains(fragment), "expected '" + fragment + "' in: " + only.message());
        }
    }

    // ── 1. An application at a kind-typed slot ──────────────────────────────────────────────────────────

    /** The bare form is judged: an undeclared name is refused at the slot, as no type declared. */
    @Test
    void baselineABareNameAtAKindSlotIsJudged() {
        refused(schema("bare", "op => !gap_operations { o => { request: no_such } }"), "/o/request", "'no_such'",
                "no type declared");
    }

    /**
     * A value body is never lifted or minted, so an application at a kind-typed slot has no entry to be the name
     * of: it is refused, sound or not, and the alias spelling named ({@code order_box => box<order>}).
     */
    @Test
    void anApplicationAtAKindSlotIsRefusedNamingTheAlias() {
        for (String application : List.of("box<order>", "box<no_such>", "no_box<order>", "box<order order>",
                "box< other >")) {
            refused(schema("applied-" + application.hashCode(),
                            "op => !gap_operations { o => { request: " + application + " } }"),
                    "a position typed by a kind holds the name of a type", "declare an alias");
        }
    }

    /** The same one level down, in a record value inside the body. */
    @Test
    void anApplicationInANestedRecordIsRefused() {
        refused(schema("nested", """
                op => !gap_operations { o => {
                  request: order
                  replies: [ { status: 200  body: box<no_such> } ]
                } }"""),
                "a position typed by a kind holds the name of a type");
    }

    /** The alias gives the application an entry, and the bare name then names it and is judged. */
    @Test
    void anAliasNamesTheApplicationAtAKindSlot() {
        assertEquals(List.of(), problems(schema("alias", """
                order_box => box<order>
                op => !gap_operations { o => { request: order_box } }""")));
        refused(schema("alias-bad", """
                bad_box => box<no_such>
                op => !gap_operations { o => { request: bad_box } }"""), "'no_such'");
    }

    // ── 2. A slot a tagged subtype declares ──────────────────────────────────────────────────────────────

    /**
     * The namespace's value type is the base; the value is the subtype its tag names, which declares {@code request}.
     */
    @Test
    void aSlotOnATaggedSubtypeResolves() {
        String id = schema("family", "api => !gap_api { GET => !gap_op { request: order } }");
        assertEquals(List.of(), problems(id));
        Namespace<?> api = assertInstanceOf(Namespace.class,
                valueOf(registry().resolveLinked(id).schema().entries().get("api")));
        assertEquals("order", assertInstanceOf(GapOp.class, api.get("GET")).request().name());
    }

    @Test
    void anUndeclaredNameOnATaggedSubtypeIsRefused() {
        refused(schema("family-bad", "api => !gap_api { GET => !gap_op { request: no_such } }"),
                "/GET/request", "'no_such'");
    }

    // ── 3. The pointer through an annotated key ──────────────────────────────────────────────────────────

    @Test
    void baselineAPlainKeyIsWrittenInThePointer() {
        refused(schema("key", "r => !gap_replies { ok => { status: 200  body: no_such } }"), "/ok/body");
    }

    @Test
    void anAnnotatedKeyIsWrittenInThePointer() {
        refused(schema("annotated-key",
                "r => !gap_replies { @doc:\"the success reply\" ok => { status: 200  body: no_such } }"), "/ok/body");
    }

    /** A value entry's value. */
    private static Object valueOf(Object entry) {
        return ((ValueEntry) entry).value();
    }
}
