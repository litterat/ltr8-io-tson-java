package io.ltr8.tson.compiler;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.KindBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.suite.SuiteBindings;
import io.ltr8.tson.suite.data.Method;
import io.ltr8.tson.suite.data.Route;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * A value of a namespace type standing as an entry: {@code myapi => !api { get_orders => { ... } }} under a meta
 * layer declaring {@code api => namespace & { key_type: = identifier  value_type: = method }}. The payload reads as
 * the map {@code api} describes, each key under its key family and each value under its value type, and the entry is
 * a {@link Namespace} built by {@code api}: a value, never a type, and what a qualified name's segment selects a key
 * of ({@code handler: myapi.get_orders}).
 */
class NamespaceEntryTest {

    private static final String META = "https://example.test/meta-api.tn";

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put(META, """
                !!id:"https://example.test/meta-api.tn"
                !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/38/m/meta.tn"
                {
                  status_name => identifier
                  method    => { request?: kind<record>  response?: type_kind  errors?: [kind<record>]
                                 replies?: {status_name => kind<record>} }
                  api       => namespace & { key_type: = identifier  value_type: = method }
                  http_verb => !enum [GET POST]
                  route     => { verb: http_verb  path: text  handler: ref<method> }
                  router    => namespace & { key_type: = identifier  value_type: = route }
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

    private static TsonCompiledMetaRegistry registry() {
        return TsonCompiledMetaRegistry.withStandardLibrary(SuiteBindings.context(), SOURCE);
    }

    /** A schema under the meta above declaring {@code order}, {@code order_list}, {@code myapi} and {@code more}. */
    private static String schema(String label, String more) {
        String id = "https://example.test/api-" + label + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!meta:"https://example.test/meta-api.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  order      => { sku: text }
                  order_list => [order]
                  myapi      => !api {
                    get_orders  => { response: order_list }
                    place_order => !method { request: order  response: order }
                  }
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

    /** The one diagnostic a refused schema reports, a schema error saying each of {@code expected}. */
    private static Diagnostic refused(String id, String... expected) {
        List<Diagnostic> problems = problems(id);
        assertEquals(1, problems.size(), problems.toString());
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code(), only.toString());
        for (String fragment : expected) {
            assertTrue(only.message().contains(fragment), "expected '" + fragment + "' in: " + only.message());
        }
        return only;
    }

    // ── The entry form ────────────────────────────────────────────────────────────────────────────────

    /**
     * The payload reads as {@code api}'s map: keys in the order written, each value bound to the meta's host for
     * {@code method}, an untagged one read as the value type and a tagged one as the type its tag names.
     */
    @Test
    void anInstanceOfANamespaceConstructorIsANamespaceValue() {
        String id = schema("reads", "");
        assertEquals(List.of(), problems(id));
        TsonLinkedSchema linked = registry().resolveLinked(id);

        Namespace<?> myapi = assertInstanceOf(Namespace.class, linked.schema().entries().get("myapi"));
        assertEquals("api", myapi.type());
        assertEquals(List.of("get_orders", "place_order"), List.copyOf(myapi.entries().keySet()));
        assertEquals(new Method(null, TypeRef.of("order_list"), null, null), myapi.entries().get("get_orders"));
        assertEquals(new Method(TypeRef.of("order"), TypeRef.of("order"), null, null),
                myapi.entries().get("place_order"));
        assertEquals(Optional.of("api"), linked.schema().factsOf("myapi").constructor());
        assertEquals(EntryKind.NAMESPACE, linked.kindOf("myapi"));
    }

    /** A key's annotations are the entry's, kept beside the key as a schema map's are. */
    @Test
    void aKeyKeepsItsAnnotations() {
        String id = schema("annotated", """
                documented => !api { @doc:"lists the orders" list => { response: order_list } }""");
        assertEquals(List.of(), problems(id));
        Namespace<?> documented = assertInstanceOf(Namespace.class,
                registry().resolveLinked(id).schema().entries().get("documented"));
        AnnotatedMap<String, ?> entries = documented.entries();
        assertTrue(entries.getAnnotations("list").has("doc"), entries.annotatedKeys().toString());
    }

    // ── The walk ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code handler: myapi.get_orders}: the first segment names the entry, the second a key of the namespace value,
     * whose type is {@code api}'s value type, {@code method} -- and the {@code Ref} receives the value at the link.
     */
    @Test
    void aQualifiedNameSelectsAKeyOfANamespaceEntry() {
        String id = schema("walk", """
                routes => !router { orders => { verb: GET  path: "/orders"  handler: myapi.get_orders } }""");
        assertEquals(List.of(), problems(id));
        TsonLinkedSchema linked = registry().resolveLinked(id);

        Namespace<?> routes = assertInstanceOf(Namespace.class, linked.schema().entries().get("routes"));
        Route orders = assertInstanceOf(Route.class, routes.entries().get("orders"));
        assertEquals("myapi.get_orders", orders.handler().name());
        assertTrue(orders.handler().isResolved(), "delivered at link");
        Namespace<?> myapi = (Namespace<?>) linked.schema().entries().get("myapi");
        assertSame(myapi.entries().get("get_orders"), orders.handler().get());
    }

    @Test
    void aKeyTheNamespaceDoesNotHoldIsRefused() {
        refused(schema("walk-no-key", """
                routes => !router { orders => { verb: GET  path: "/"  handler: myapi.cancel_order } }"""),
                "'routes' at /orders/handler", "'myapi' has no key 'cancel_order'", "it holds get_orders, place_order");
    }

    /** A namespace value's value is a {@code method}; a route is none. */
    @Test
    void aSelectedValueOfAnotherTypeIsRefused() {
        refused(schema("walk-wrong-type", """
                routes  => !router { orders => { verb: GET  path: "/"  handler: myapi.get_orders } }
                again   => !router { back => { verb: GET  path: "/"  handler: routes.orders } }"""),
                "'again' at /back/handler", "wants a 'method'", "'routes.orders' is a 'route'");
    }

    /** The entry alone is a value of {@code api}, which is no {@code method}. */
    @Test
    void theEntryItselfIsNoValueOfItsValueType() {
        refused(schema("walk-entry", """
                routes => !router { orders => { verb: GET  path: "/"  handler: myapi } }"""),
                "'routes' at /orders/handler", "wants a 'method'", "built by 'api'");
    }

    // ── Refusals at the entry ─────────────────────────────────────────────────────────────────────────

    @Test
    void aPayloadThatIsNoMapIsRefused() {
        refused(schema("list", "bad => !api [ ]"), "'bad'", "no value of the namespace type 'api'",
                "expected a map for 'api', found an array");
        refused(schema("atom", "bad => !api 42"), "'bad'", "no value of the namespace type 'api'",
                "expected a map for 'api'");
    }

    /** {@code api}'s keys are identifiers. */
    @Test
    void aKeyTheFamilyRefusesIsRefused() {
        refused(schema("key", "bad => !api { \"1x\" => { response: order } }"), "'bad'", "at /1x",
                "cannot start an identifier");
    }

    @Test
    void aValueTheValueTypeRefusesIsRefused() {
        refused(schema("value", "bad => !api { m => { nope: order } }"), "'bad'", "at /m/nope",
                "unknown field 'nope' on 'method'");
        refused(schema("value-list", "bad => !api { m => [order] }"), "'bad'", "at /m",
                "expected a record for 'method'");
    }

    /** A tagged value is the value type or IS-A it (§7.2); a route is neither. */
    @Test
    void aValueTaggedWithAnotherTypeIsRefused() {
        refused(schema("value-tag", "bad => !api { m => !route { verb: GET  path: \"/\"  handler: myapi.get_orders } }"),
                "'bad'", "at /m", "'route' is not admissible at a 'method' position");
    }

    /** The kernel's {@code namespace} is abstract: its application is refused, naming what would satisfy it. */
    @Test
    void theAbstractNamespaceIsNotApplied() {
        refused(schema("abstract", "bad => !namespace { key_type: identifier  value_type: text }"),
                "'!namespace' names an abstract entry", "api | router");
    }

    // ── The entry is no type ──────────────────────────────────────────────────────────────────────────

    /** {@code type_kind} holds the names of types; a namespace value is a value, so a value body's slot refuses it. */
    @Test
    void aNamespaceEntryAtATypeKindSlotIsRefused() {
        refused(schema("type-slot", "other => !api { m => { response: myapi } }"),
                "'other' at /m/response", "'myapi'", "built by 'api'");
    }

    @Test
    void aNamespaceEntryAtARecordKindSlotIsRefused() {
        refused(schema("record-slot", "other => !api { m => { request: myapi } }"),
                "'other' at /m/request", "'myapi'", "no 'record'");
    }

    /** A field of a type declaration is a {@code type_kind} slot too, judged by the linker. */
    @Test
    void aNamespaceEntryTypesNoField() {
        refused(schema("field", "holder => { s: myapi }"), "'myapi'", "namespace type 'api'", "not a type");
    }

    /** Nothing lifts or mints in a value body: a braced application at a kind slot is refused, the alias named. */
    @Test
    void aBracedApplicationInANamespaceValueIsRefused() {
        refused(schema("braced", """
                box   => <T> { v: T }
                other => !api { m => { response: { name: box  arguments: [ { name: order } ] } } }"""),
                "a position typed by a kind holds the name of a type", "declare an alias");
    }

    // ── Kinds ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Construction is the walk a kind's members take: {@code myapi} is built with {@code api}, so {@code kind<api>}
     * holds it, and {@code kind<namespace>}, {@code api} being IS-A {@code namespace}. No kind of types holds it.
     */
    @Test
    void theKindsOfItsConstructorHoldANamespaceEntry() {
        TsonLinkedSchema linked = registry().resolveLinked(schema("kinds", """
                apis       => kind<api>
                namespaces => kind<namespace>
                types      => kind<type>
                records    => kind<record>"""));

        assertTrue(members(linked, "apis").contains("myapi"), members(linked, "apis").toString());
        assertTrue(members(linked, "namespaces").contains("myapi"), members(linked, "namespaces").toString());
        assertFalse(members(linked, "types").contains("myapi"), members(linked, "types").toString());
        assertFalse(members(linked, "records").contains("myapi"), members(linked, "records").toString());
    }

    private static List<String> members(TsonLinkedSchema linked, String kind) {
        String terminal = linked.schema().entries().get(kind) instanceof Reference reference
                ? reference.target().name() : kind;
        return assertInstanceOf(KindBody.class, linked.schema().entries().get(terminal)).members().orElseThrow();
    }

    // ── Resolved output ───────────────────────────────────────────────────────────────────────────────

    /**
     * Written as written: the constructor's tag, then the map, each key bare and each value as its host writes it;
     * read back under the meta, a {@code !api} entry is the same {@code Namespace}.
     */
    @Test
    void resolvedOutputWritesTheEntryAsWrittenAndReadsItBack() {
        TsonCompiledMetaRegistry registry = registry();
        Object myapi = registry.resolveLinked(schema("written", "")).schema().entries().get("myapi");

        String written = new TsonObjectWriter(SuiteBindings.context()).toTson(myapi);
        String flat = written.replaceAll("\\s+", " ");
        assertTrue(flat.startsWith("!api {"), written);
        assertTrue(flat.contains("get_orders => { response: order_list }"), written);
        assertTrue(flat.contains("place_order => { request: order response: order }"), written);

        Object read = registry.loadMeta(META).compiledSchema().find("top").orElseThrow()
                .read(TestDocuments.document(written));
        assertEquals(myapi, read);
    }
}
