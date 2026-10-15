package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.suite.SuiteBindings;
import io.ltr8.tson.suite.data.Binding;
import io.ltr8.tson.suite.data.Interface;
import io.ltr8.tson.suite.data.Method;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value written by name at a product-typed slot (SPEC-FEEDBACK.md #13), end to end: a meta layer declaring an
 * interface of methods and an HTTP binding naming the method it serves, a schema under it declaring an interface,
 * and a schema importing that one binding a method -- the read that keeps the name, the linker that resolves it and
 * judges its referent, and each refusal with the step it names.
 */
class ValuesByNameTest {

    private static final String META_SERVICE = "https://example.test/meta-service.tn";

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put(META_SERVICE, """
                !!id:"https://example.test/meta-service.tn"
                !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/38/m/meta.tn"
                {
                  method_name => identifier
                  status_name => identifier
                  method      => { request?: record  response?: type_ref  errors?: [record]
                                   replies?: {status_name => record} }
                  interface   => data & { methods: {method_name => method} }
                  http_verb   => !enum [GET POST]
                  binding     => data & { method: method  verb: http_verb  route: text }
                  filed_in_references => data & { request: record  method?: method }
                  filed_nowhere       => data & { request: record }
                }
                """);
        DOCUMENTS.put("https://example.test/orders.tn", orders("https://example.test/orders.tn", ""));
    }

    private static String orders(String id, String more) {
        return """
                !!id:"%s"
                !!meta:"https://example.test/meta-service.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  order               => { sku: text  quantity: int32 }
                  place_order_request => { order: order }
                  orders => !interface { methods: {
                    place_order => { request: place_order_request  response: order }
                  } }
                  %s
                }
                """.formatted(id, more);
    }

    private static final SchemaSource SOURCE = uri -> {
        for (Map.Entry<String, String> document : DOCUMENTS.entrySet()) {
            if (CanonicalIdentity.sameIdentity(uri, document.getKey())) {
                return document.getValue();
            }
        }
        throw new IllegalStateException("unexpected fetch: " + uri);
    };

    /** The corpus's host classes and this test's own ({@code consumer}), composed onto the library's binder. */
    private static final DataNameBinder HOSTS = new DataNameBinder.DefaultDataNameBinder(
            Set.of("io.ltr8.tson.suite.data", "io.ltr8.tson.compiler.consumer"), Map.of());

    private static TsonCompiledMetaRegistry registry() {
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.contextExtendedWith(HOSTS), SOURCE);
    }

    /** A schema under the meta above, importing orders.tn and core.tn, declaring {@code declarations}. */
    private static String api(String label, String declarations) {
        String id = "https://example.test/api-" + label + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!meta:"https://example.test/meta-service.tn"
                !!import:"https://example.test/orders.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  %s
                }
                """.formatted(id, declarations));
        return id;
    }

    private static List<Diagnostic> problems(String id) {
        List<Diagnostic> problems = new ArrayList<>();
        registry().resolveLinked(id, problems::add);
        return problems;
    }

    /** The one diagnostic a refused schema reports, and that it says {@code expected}. */
    private static void refused(String id, String... expected) {
        List<Diagnostic> problems = problems(id);
        assertEquals(1, problems.size(), problems.toString());
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code(), only.toString());
        for (String fragment : expected) {
            assertTrue(only.message().contains(fragment), "expected '" + fragment + "' in: " + only.message());
        }
    }

    // ── What runs ───────────────────────────────────────────────────────────────────────────────────

    /** The interface reads: {@code request} keeps the name as written, {@code response} is a type reference. */
    @Test
    void anInterfaceHoldsItsRequestByName() {
        TsonLinkedSchema linked = registry().resolveLinked("https://example.test/orders.tn");
        Interface orders = assertInstanceOf(Interface.class, linked.schema().entries().get("orders"));
        assertEquals(new Method(TypeRef.of("place_order_request"), TypeRef.of("order"), null, null),
                orders.methods().get("place_order"));
    }

    /** {@code method: orders.methods.place_order}: an entry, a field of its body, a key of that map. */
    @Test
    void aBindingNamesAMethodThroughItsInterface() {
        String id = api("binds", """
                post_orders => !binding { method: orders.methods.place_order  verb: POST  route: "/orders" }""");
        assertEquals(List.of(), problems(id));
        Binding binding = assertInstanceOf(Binding.class,
                registry().resolveLinked(id).schema().entries().get("post_orders"));
        assertEquals(new Binding(TypeRef.of("orders.methods.place_order"), "POST", "/orders"), binding);
    }

    @Test
    void aRecordEntryIsARecord() {
        assertEquals(List.of(), problems(api("record", """
                order_ref => { id: text }
                api => !interface { methods: { m => { request: order_ref } } }""")));
    }

    @Test
    void anAliasHopIsFollowed() {
        assertEquals(List.of(), problems(api("alias", """
                order_ref => { id: text }
                req       => order_ref
                api => !interface { methods: { m => { request: req } } }""")));
    }

    @Test
    void aCompositionsBodyIsBuiltByRecord() {
        assertEquals(List.of(), problems(api("composition", """
                order_v2 => order & { note: text }
                api => !interface { methods: { m => { request: order_v2 } } }""")));
    }

    /** A list slot: each element is its own name, at its own index. */
    @Test
    void eachErrorRecordIsJudged() {
        assertEquals(List.of(), problems(api("errors", """
                not_found => { id: text }
                api => !interface { methods: { m => { errors: [not_found place_order_request] } } }""")));
        refused(api("errors-refused", """
                api => !interface { methods: { m => { errors: [order uuid] } } }"""),
                "/methods/m/errors/1", "'uuid' is an entry whose body is built by 'uuid_type'", "no 'record'");
    }

    // ── Refusals, each naming its step ──────────────────────────────────────────────────────────────

    @Test
    void anAtomEntryIsNoRecord() {
        refused(api("uuid", """
                user_id => uuid
                api => !interface { methods: { m => { request: user_id } } }"""),
                "/methods/m/request", "'user_id' (an alias of 'uuid')", "built by 'uuid_type'", "no 'record'");
    }

    @Test
    void aMethodIsNoRecord() {
        refused(api("method-as-record", """
                api => !interface { methods: { m => { request: orders.methods.place_order } } }"""),
                "'orders.methods.place_order' is a 'method'", "no 'record'");
    }

    @Test
    void aMemberIsReachedThroughItsEntry() {
        refused(api("no-entry", """
                b => !binding { method: place_order  verb: POST  route: "/" }"""),
                "'b' at /method", "'place_order' is no entry");
    }

    @Test
    void aSegmentThatIsNoFieldNamesTheFieldsThereAre() {
        refused(api("no-field", """
                b => !binding { method: orders.place_order  verb: POST  route: "/" }"""),
                "'orders' is an 'interface', which has no field 'place_order'", "it declares methods");
    }

    @Test
    void aSegmentThatIsNoKeyNamesTheKeysThereAre() {
        refused(api("no-key", """
                b => !binding { method: orders.methods.cancel_order  verb: POST  route: "/" }"""),
                "'orders.methods' has no key 'cancel_order'", "it holds place_order");
    }

    @Test
    void aRecordIsNoMethod() {
        refused(api("record-as-method", """
                b => !binding { method: place_order_request  verb: POST  route: "/" }"""),
                "wants a 'method'", "built by 'record'", "no 'method'");
    }

    @Test
    void aTypeEntryAdmitsNoFurtherSegment() {
        refused(api("type-segment", """
                b => !binding { method: order.sku  verb: POST  route: "/" }"""),
                "'order' is a type", "admits no further segment");
    }

    @Test
    void aMapValueIsANameAtItsKey() {
        assertEquals(List.of(), problems(api("replies", """
                not_found => { id: text }
                api => !interface { methods: { m => { replies: { ok => order  missing => not_found } } } }""")));
        refused(api("replies-refused", """
                api => !interface { methods: { m => { replies: { ok => uuid } } } }"""),
                "/methods/m/replies/ok", "built by 'uuid_type'");
    }

    @Test
    void anOpenEntryNamesNoValue() {
        refused(api("template", """
                box => <T> { v: T }
                api => !interface { methods: { m => { request: box } } }"""),
                "'box' is an open entry", "denotes no value until it is applied");
    }

    @Test
    void aListHasNoNamedMembers() {
        refused(api("list-segment", """
                api => !interface { methods: { m => { errors: [order] } } }
                b => !binding { method: api.methods.m.errors.x  verb: POST  route: "/" }"""),
                "'api.methods.m.errors' is a list, whose elements have no names");
    }

    /** §4.1's refusal, unchanged: a product-typed position is not a type position. */
    @Test
    void aDataEntryAtATypePositionIsStillRefused() {
        List<Diagnostic> problems = problems(api("data-as-type", """
                api => !interface { methods: { m => { response: orders } } }"""));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("describes something other than a data value"),
                problems.getFirst().message());
    }

    /** The read refuses what is no name at a slot that holds one, before the linker is reached. */
    @Test
    void aQuotedNameIsText() {
        List<Diagnostic> problems = problems(api("quoted", """
                b => !binding { method: "orders.methods.place_order"  verb: POST  route: "/" }"""));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("expected the name of a 'method' value"),
                problems.getFirst().message());
    }

    // ── The binding's side ─────────────────────────────────────────────────────────────────────────

    /** A reference component at an atom-typed slot is the class's mistake: there a token is a literal. */
    @Test
    void aReferenceIsNoAtomsComponent() {
        assertMisbound("name_at_text", "text", "is an atom");
    }

    /** The grammar is the linker's: each segment between the dots is an identifier, none empty. */
    @Test
    void aMalformedNameIsRefusedWhereItIsResolved() {
        refused(api("bad-segment", """
                b => !binding { method: orders.1x  verb: POST  route: "/" }"""),
                "'b' at /method holds 'orders.1x', which is no qualified name", "'1x' is not");
        refused(api("leading-dot", """
                b => !binding { method: .orders  verb: POST  route: "/" }"""),
                "holds '.orders', which is no qualified name", "one is empty");
    }

    private static void assertMisbound(String constructor, String fieldType, String expected) {
        String id = "https://example.test/meta-" + constructor.replace('_', '-') + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/38/m/meta.tn"
                {
                  %s => data & { a: %s }
                }
                """.formatted(id, constructor, fieldType));
        DataBindContext context = SchemaMetaNameBinder.contextExtendedWith(new DataNameBinder.DefaultDataNameBinder(
                Set.of("io.ltr8.tson.compiler.consumer"), Map.of()));
        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> TsonCompiledMetaRegistry.withStandardLibrary(context, SOURCE).loadMeta(id));
        assertTrue(thrown.getMessage().contains(expected), thrown.getMessage());
    }

    // ── Resolved output ─────────────────────────────────────────────────────────────────────────────

    /**
     * The resolver keeps the name as written, in the {@code TypeRef} every reference is held as, and resolved output
     * writes an argument-free {@code TypeRef} as its bare name (§8.1), which reads back at the by-name slot to the
     * same body.
     */
    @Test
    void resolvedOutputWritesTheNameAsWrittenAndReadsItBack() {
        TsonCompiledMetaRegistry registry = registry();
        String id = api("written", """
                post_orders => !binding { method: orders.methods.place_order  verb: POST  route: "/orders" }""");
        Object body = registry.resolveLinked(id).schema().entries().get("post_orders");

        String written = new TsonObjectWriter(SuiteBindings.context()).toTson(body);
        assertTrue(written.replaceAll("\\s+", " ").contains("method: orders.methods.place_order"), written);

        Object read = registry.loadMeta(META_SERVICE).compiledSchema().find("binding").orElseThrow()
                .read(TestDocuments.document(written));
        assertEquals(body, read);
    }

    // ── Which list a host returns a reference from changes nothing ──────────────────────────────────

    /**
     * A by-name reference is judged against its slot from what the read collected, so returning it from
     * {@code references()} -- where a {@code type_ref} slot's reference belongs -- neither excuses a wrong one nor
     * refuses a right one.
     */
    @Test
    void aReferenceByNameFiledInReferencesIsStillJudgedBySlot() {
        refused(api("filed-text", """
                a => !filed_in_references { request: text }"""), "'a' at /request", "built by 'text_type'");
        refused(api("filed-alias", """
                user_id => uuid
                a => !filed_in_references { request: user_id }"""), "'a' at /request", "built by 'uuid_type'");
        assertEquals(List.of(), problems(api("filed-right", """
                a => !filed_in_references { request: order  method: orders.methods.place_order }""")));
    }

    /** A host that declares no reference at all has its by-name ones judged the same way. */
    @Test
    void aReferenceByNameDeclaredNowhereIsStillJudgedBySlot() {
        refused(api("nowhere-text", """
                a => !filed_nowhere { request: text }"""), "'a' at /request", "built by 'text_type'");
        refused(api("nowhere-alias", """
                user_id => uuid
                a => !filed_nowhere { request: user_id }"""), "'a' at /request", "built by 'uuid_type'");
        assertEquals(List.of(), problems(api("nowhere-right", """
                a => !filed_nowhere { request: order }""")));
    }
}
