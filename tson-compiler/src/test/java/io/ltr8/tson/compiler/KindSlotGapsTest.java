package io.ltr8.tson.compiler;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.consumer.GapApi;
import io.ltr8.tson.compiler.consumer.GapOp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names in a DATA body that the link does not judge, or cannot reach -- reported from tson-java-http, which meets
 * all three through its meta layer ({@code meta-http.tn}) and its meta-service experiment.
 *
 * <ol>
 *   <li>A braced application at a kind-typed slot of a value body is refused, the alias spelling named: nothing lifts
 *       or mints in a value body, so an application there would have no entry to be the name of.
 *   <li>A name at a {@code type_ref}-typed slot of a DATA body is judged by resolution, arity and §4.1.
 *   <li>A slot reached through a record family base is followed through the subtype the value's tag selected, and a
 *       slot the walk cannot follow arrives at a collecting resolve as {@code NOT_IMPLEMENTED}.
 *   <li>A slot's pointer writes the key for a map key that carries annotations.
 * </ol>
 */
class KindSlotGapsTest {

    private static final String META = "https://example.test/gap-meta.tn";

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put(META, """
                !!id:"https://example.test/gap-meta.tn"
                !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/38/m/meta.tn"
                {
                  gap_status        => !integer_type { min: 100  max: 599 }
                  gap_reply         => { status: gap_status  body: type_kind }
                  gap_operation     => data & { request: type_kind  replies?: [gap_reply] }
                  gap_ref_operation => data & { request: type_ref }
                  gap_endpoint      => { status?: gap_status ~ 200 }
                  gap_op            => gap_endpoint & { request: type_kind }
                  gap_api           => data & { endpoints: {text => gap_endpoint} }
                  gap_reply_map     => data & { replies: {text => gap_reply} }
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
                !!meta:"https://example.test/gap-meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  order => { sku: text }
                  box   => <T> { v: T }
                  other => !gap_operation { request: order }
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

    // ── 1. A braced application at a kind-typed slot ────────────────────────────────────────────────────

    /** The bare form is judged: an undeclared name is refused at the slot, as no type declared. */
    @Test
    void baselineABareNameAtAKindSlotIsJudged() {
        refused(schema("bare", "op => !gap_operation { request: no_such }"), "/request", "'no_such'",
                "no type declared");
    }

    /**
     * A value body is never lifted or minted, so a braced application at a kind-typed slot has no entry to be the
     * name of: it is refused, sound or not, and the alias spelling named ({@code order_box => box<order>}).
     */
    @Test
    void aBracedApplicationAtAKindSlotIsRefusedNamingTheAlias() {
        for (String application : List.of("{ name: box  arguments: [ { name: order } ] }",
                "{ name: box  arguments: [ { name: no_such } ] }",
                "{ name: no_box  arguments: [ { name: order } ] }",
                "{ name: box  arguments: [ { name: order } { name: order } ] }",
                "{ name: box  arguments: [ { name: other } ] }")) {
            refused(schema("braced-" + application.hashCode(), "op => !gap_operation { request: " + application + " }"),
                    "a position typed by a kind holds the name of a type", "declare an alias");
        }
    }

    /** The same one level down, in a record value inside the body. */
    @Test
    void aBracedApplicationInANestedRecordIsRefused() {
        refused(schema("nested", """
                op => !gap_operation {
                  request: order
                  replies: [ { status: 200  body: { name: box  arguments: [ { name: no_such } ] } } ]
                }"""),
                "a position typed by a kind holds the name of a type");
    }

    /** The alias gives the application an entry, and the bare name then names it and is judged. */
    @Test
    void anAliasNamesTheApplicationAtAKindSlot() {
        assertEquals(List.of(), problems(schema("alias", """
                order_box => box<order>
                op => !gap_operation { request: order_box }""")));
        refused(schema("alias-bad", """
                bad_box => box<no_such>
                op => !gap_operation { request: bad_box }"""), "'no_such'");
    }

    // ── 2. A type_ref-typed slot of a DATA body ──────────────────────────────────────────────────────────

    /**
     * {@code design/references.md}: "A {@code type_ref} slot is judged by resolution and arity alone", and
     * {@code design/meta-layer-data-kind.md} gives {@code response: orders} at a {@code type_ref} slot as §4.1's
     * refusal. Neither happens in a DATA body: a meta layer written before #747 loses every check, silently.
     */
    @Test
    void anUndeclaredNameAtATypeRefSlotIsRefused() {
        refused(schema("ref-undeclared", "op => !gap_ref_operation { request: no_such }"), "'no_such'");
    }

    @Test
    void aDataEntryAtATypeRefSlotIsRefused() {
        refused(schema("ref-data", "op => !gap_ref_operation { request: other }"), "'other'");
    }

    // ── 3. A slot reached through a record family base ───────────────────────────────────────────────────

    /** The map's value type is the base; the value is the subtype its tag names, which declares {@code request}. */
    @Test
    void aSlotOnATaggedSubtypeResolves() {
        String id = schema("family", "api => !gap_api { endpoints: { GET => !gap_op { request: order } } }");
        assertEquals(List.of(), problems(id));
        GapApi api = assertInstanceOf(GapApi.class, registry().resolveLinked(id).schema().entries().get("api"));
        assertEquals("order", assertInstanceOf(GapOp.class, api.endpoints().get("GET")).request().name());
    }

    @Test
    void anUndeclaredNameOnATaggedSubtypeIsRefused() {
        refused(schema("family-bad", "api => !gap_api { endpoints: { GET => !gap_op { request: no_such } } }"),
                "/endpoints/GET/request", "'no_such'");
    }

    /** Whatever the walk does not follow, a collecting resolve reports it rather than throwing. */
    @Test
    void anUnfollowedSlotIsReportedNotThrown() {
        String id = schema("family-collect", "api => !gap_api { endpoints: { GET => !gap_op { request: order } } }");
        List<Diagnostic> problems = assertDoesNotThrow(() -> problems(id));
        assertTrue(problems.stream().allMatch(d -> d.code() == Diagnostic.Code.NOT_IMPLEMENTED), problems.toString());
    }

    // ── 4. The pointer through an annotated key ──────────────────────────────────────────────────────────

    @Test
    void baselineAPlainKeyIsWrittenInThePointer() {
        refused(schema("key", "r => !gap_reply_map { replies: { ok => { status: 200  body: no_such } } }"),
                "/replies/ok/body");
    }

    @Test
    void anAnnotatedKeyIsWrittenInThePointer() {
        refused(schema("annotated-key",
                "r => !gap_reply_map { replies: { @doc:\"the success reply\" ok => { status: 200  body: no_such } } }"),
                "/replies/ok/body");
    }
}
