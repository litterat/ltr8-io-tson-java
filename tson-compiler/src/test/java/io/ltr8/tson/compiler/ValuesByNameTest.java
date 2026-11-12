package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.compiler.consumer.Pairing;
import io.ltr8.tson.compiler.consumer.PinnedSetting;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.suite.SuiteBindings;
import io.ltr8.tson.suite.data.Binding;
import io.ltr8.tson.suite.data.Method;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value written by name at a slot typed {@code meta.ref<T>} ([TSON-SCHEMA] §5.12), end to end: a vocabulary schema
 * declaring an interface, a namespace of methods, and a namespace of HTTP bindings each naming the method it serves, a
 * schema importing it declaring an interface, and a schema importing that one binding a method -- the read that keeps the
 * name, the linker that resolves it and judges its referent, and each refusal with the step it names.
 */
class ValuesByNameTest {

    private static final String META_SERVICE = "https://example.test/meta-service.tn";

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put(META_SERVICE, """
                !!id:"https://example.test/meta-service.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  method_name => meta.identifier
                  status_name => meta.identifier
                  method      => { request?: meta.ref<meta.record>  response?: meta.type_ref
                                   errors?: [meta.ref<meta.record>]
                                   replies?: {status_name => meta.ref<meta.record>} }
                  interface   => !meta.namespace { key_type: method_name  value_type: method }
                  http_verb   => !enum [GET POST]
                  binding     => { method: meta.ref<method>  verb: http_verb  route: text }
                  bindings    => !meta.namespace { key_type: meta.identifier  value_type: binding }
                  filed_in_references => { request: meta.ref<meta.record>  method?: meta.ref<method> }
                  filed_nowhere       => { request: meta.ref<meta.record> }
                  refs        => !meta.namespace { key_type: meta.identifier  value_type: filed_in_references }
                  nowheres    => !meta.namespace { key_type: meta.identifier  value_type: filed_nowhere }
                  pairing     => { primary: method }
                  pairings    => !meta.namespace { key_type: meta.identifier  value_type: pairing }
                  setting     => { kind: meta.type_ref  value?: kind! }
                  settings    => !meta.namespace { key_type: meta.identifier  value_type: setting }
                  pinned_setting    => { kind?: meta.type_ref = meta.identifier  value?: kind! }
                  pinned_settings   => !meta.namespace { key_type: meta.identifier  value_type: pinned_setting }
                  defaulted_setting => { kind?: meta.type_ref ~ meta.identifier  value?: kind! }
                  defaulted_settings => !meta.namespace { key_type: meta.identifier  value_type: defaulted_setting }
                  holder      => { inner?: setting }
                  holders     => !meta.namespace { key_type: meta.identifier  value_type: holder }
                  label       => { text: meta.ref<meta.identifier> }
                  labels      => !meta.namespace { key_type: meta.identifier  value_type: label }
                }
                """);
        DOCUMENTS.put("https://example.test/orders.tn", orders("https://example.test/orders.tn", ""));
    }

    private static String orders(String id, String more) {
        return """
                !!id:"%s"
                !!import:"https://example.test/meta-service.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  order               => { sku: text  quantity: int32 }
                  place_order_request => { order: order }
                  orders => !interface {
                    place_order => { request: place_order_request  response: order }
                  }
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

    private static TsonCompiledVocabularyRegistry registry() {
        return TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.contextExtendedWith(HOSTS), SOURCE);
    }

    /** A schema under the meta above, importing orders.tn and core.tn, declaring {@code declarations}. */
    private static String api(String label, String declarations) {
        String id = "https://example.test/api-" + label + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!import:"https://example.test/meta-service.tn"
                !!import:"https://example.test/orders.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
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
        Namespace<?> orders = assertInstanceOf(Namespace.class, valueOf(linked.schema().entries().get("orders")));
        assertEquals(new Method(TypeRef.of("place_order_request"), TypeRef.of("order"), null, null),
                orders.get("place_order"));
    }

    /** {@code method: orders.place_order}: an entry, then a key of the namespace value it holds. */
    @Test
    void aBindingNamesAMethodThroughItsInterface() {
        String id = api("binds", """
                routes => !bindings { post_orders => { method: orders.place_order  verb: POST  route: "/orders" } }""");
        assertEquals(List.of(), problems(id));
        Binding binding = assertInstanceOf(Binding.class,
                ((Namespace<?>) valueOf(registry().resolveLinked(id).schema().entries().get("routes"))).get("post_orders"));
        assertEquals("orders.place_order", binding.method().name());
        assertEquals("POST", binding.verb());
        assertEquals("/orders", binding.route());
    }

    @Test
    void aRecordEntryIsARecord() {
        assertEquals(List.of(), problems(api("record", """
                order_ref => { id: text }
                api => !interface { m => { request: order_ref } }""")));
    }

    @Test
    void anAliasHopIsFollowed() {
        assertEquals(List.of(), problems(api("alias", """
                order_ref => { id: text }
                req       => order_ref
                api => !interface { m => { request: req } }""")));
    }

    @Test
    void aCompositionsBodyIsBuiltByRecord() {
        assertEquals(List.of(), problems(api("composition", """
                order_v2 => order & { note: text }
                api => !interface { m => { request: order_v2 } }""")));
    }

    /** A list slot: each element is its own name, at its own index. */
    @Test
    void eachErrorRecordIsJudged() {
        assertEquals(List.of(), problems(api("errors", """
                not_found => { id: text }
                api => !interface { m => { errors: [not_found place_order_request] } }""")));
        refused(api("errors-refused", """
                api => !interface { m => { errors: [order uuid] } }"""),
                "/m/errors/1", "'uuid' is an entry whose body is built by 'net.uuid_type'", "no 'meta.record'");
    }

    // ── Refusals, each naming its step ──────────────────────────────────────────────────────────────

    @Test
    void anAtomEntryIsNoRecord() {
        refused(api("uuid", """
                user_id => uuid
                api => !interface { m => { request: user_id } }"""),
                "/m/request", "'user_id' (an alias of 'uuid')", "built by 'net.uuid_type'", "no 'meta.record'");
    }

    /**
     * A kind-typed slot holds a type's name: a qualified name there parses, and reaches into a value, which is no
     * type.
     */
    @Test
    void aMethodIsNoRecord() {
        refused(api("method-as-record", """
                api => !interface { m => { request: orders.place_order } }"""),
                "'orders.place_order'", "a value and not a type");
    }

    @Test
    void aMemberIsReachedThroughItsEntry() {
        refused(api("no-entry", """
                routes => !bindings { b => { method: place_order  verb: POST  route: "/" } }"""),
                "'routes' at /b/method", "'place_order' is no entry");
    }

    /** Past a key the value is a {@code method} record, and a segment there selects a field it declares. */
    @Test
    void aSegmentThatIsNoFieldNamesTheFieldsThereAre() {
        refused(api("no-field", """
                routes => !bindings { b => { method: orders.place_order.nope  verb: POST  route: "/" } }"""),
                "'orders.place_order' is a 'method', which has no field 'nope'", "it declares request, response");
    }

    /** A field a record value declares is followed by its declared type, which is judged against the slot. */
    @Test
    void aSegmentThroughARecordValuesFieldIsJudgedByType() {
        refused(api("through-field", """
                routes => !bindings { b => { method: orders.place_order.request  verb: POST  route: "/" } }"""),
                "wants a 'method'", "'orders.place_order.request' is a ", "which is no 'method'");
    }

    /**
     * A name whose walk passes a record value's field reads that field of the host the read bound, and the value it
     * reaches is delivered: {@code p.x.primary} is the {@code method} the {@code pairing} under {@code x} holds.
     */
    @Test
    void aValueReachedThroughARecordValuesFieldIsDelivered() {
        String id = api("through-field", """
                p      => !pairings { x => { primary: { request: order } } }
                routes => !bindings { b => { method: p.x.primary  verb: POST  route: "/" } }""");
        assertEquals(List.of(), problems(id));
        Map<String, Top> entries = registry().resolveLinked(id).schema().entries().entries();
        Binding b = assertInstanceOf(Binding.class, ((Namespace<?>) valueOf(entries.get("routes"))).get("b"));
        Pairing x = assertInstanceOf(Pairing.class, ((Namespace<?>) valueOf(entries.get("p"))).get("x"));
        assertTrue(b.method().isResolved(), "delivered at link");
        assertSame(x.primary(), b.method().get());
    }

    @Test
    void aSegmentThatIsNoKeyNamesTheKeysThereAre() {
        refused(api("no-key", """
                routes => !bindings { b => { method: orders.cancel_order  verb: POST  route: "/" } }"""),
                "'orders' has no key 'cancel_order'", "it holds place_order");
    }

    @Test
    void aRecordIsNoMethod() {
        refused(api("record-as-method", """
                routes => !bindings { b => { method: place_order_request  verb: POST  route: "/" } }"""),
                "wants a 'method'", "built by 'meta.record'", "no 'method'");
    }

    @Test
    void aTypeEntryAdmitsNoFurtherSegment() {
        refused(api("type-segment", """
                routes => !bindings { b => { method: order.sku  verb: POST  route: "/" } }"""),
                "'order' is a type", "admits no further segment");
    }

    @Test
    void aMapValueIsANameAtItsKey() {
        assertEquals(List.of(), problems(api("replies", """
                not_found => { id: text }
                api => !interface { m => { replies: { ok => order  missing => not_found } } }""")));
        refused(api("replies-refused", """
                api => !interface { m => { replies: { ok => uuid } } }"""),
                "/m/replies/ok", "built by 'net.uuid_type'");
    }

    @Test
    void anOpenEntryNamesNoValue() {
        refused(api("template", """
                box => <T> { v: T }
                api => !interface { m => { request: box } }"""),
                "'box' is an open entry", "denotes no value until it is applied");
    }

    @Test
    void aListHasNoNamedMembers() {
        refused(api("list-segment", """
                api => !interface { m => { errors: [order] } }
                routes => !bindings { b => { method: api.m.errors.x  verb: POST  route: "/" } }"""),
                "'api.m.errors' is a list, whose elements have no names");
    }

    /** §4.1's refusal: a namespace entry is a value, and a type position holds no value. */
    @Test
    void aNamespaceEntryAtATypePositionIsRefused() {
        List<Diagnostic> problems = problems(api("data-as-type", """
                api => !interface { m => { response: orders } }"""));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("'orders' is a value of the type 'interface'"),
                problems.getFirst().message());
    }

    /** The read refuses what is no name at a slot that holds one, before the linker is reached. */
    @Test
    void aQuotedNameIsText() {
        List<Diagnostic> problems = problems(api("quoted", """
                routes => !bindings { b => { method: "orders.place_order"  verb: POST  route: "/" } }"""));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("expected the name of a 'method' value"),
                problems.getFirst().message());
    }

    // ── The binding's side ─────────────────────────────────────────────────────────────────────────

    /** A reference component at an atom-typed slot is the class's mistake: there a token is a literal. */
    @Test
    void aReferenceIsNoAtomsComponent() {
        assertMisbound("name_at_text", "text", "is not a reference");
    }

    /** The grammar is the linker's: each segment between the dots is an identifier, none empty. */
    @Test
    void aMalformedNameIsRefusedWhereItIsResolved() {
        refused(api("bad-segment", """
                routes => !bindings { b => { method: orders.1x  verb: POST  route: "/" } }"""),
                "'routes' at /b/method holds 'orders.1x', which is no qualified name", "'1x' is not");
        refused(api("leading-dot", """
                routes => !bindings { b => { method: .orders  verb: POST  route: "/" } }"""),
                "holds '.orders', which is no qualified name", "one is empty");
    }

    private static void assertMisbound(String constructor, String fieldType, String expected) {
        String id = "https://example.test/meta-" + constructor.replace('_', '-') + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  %s => { a: %s }
                }
                """.formatted(id, constructor, fieldType));
        DataBindContext context = SchemaModelNameBinder.contextExtendedWith(new DataNameBinder.DefaultDataNameBinder(
                Set.of("io.ltr8.tson.compiler.consumer"), Map.of()));
        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> TsonCompiledVocabularyRegistry.withStandardLibrary(context, SOURCE).loadVocabulary(id));
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
        TsonCompiledVocabularyRegistry registry = registry();
        String id = api("written", """
                routes => !bindings { post_orders => { method: orders.place_order  verb: POST  route: "/orders" } }""");
        Object body = registry.resolveLinked(id).schema().entries().get("routes");

        String written = new TsonObjectWriter(SuiteBindings.context()).toTson(body);
        assertTrue(written.replaceAll("\\s+", " ").contains("method: orders.place_order"), written);

        Map<?, ?> read = (Map<?, ?>) registry.loadVocabulary(META_SERVICE).compiledSchema().find("meta.schema").orElseThrow()
                .read(TestDocuments.document("!schema { routes => " + written + " }"));
        assertEquals(((ValueEntry) body).value(), read.get("routes"));
    }

    // ── The class declares nothing; the slot judges ─────────────────────────────────────────────────

    /**
     * A name is judged against its slot from what the read collected: a type's name at a kind-typed slot by
     * membership, a value's name at a reference-typed slot by the referent's type.
     */
    @Test
    void aReferenceByNameFiledInReferencesIsStillJudgedBySlot() {
        refused(api("filed-text", """
                a => !refs { x => { request: text } }"""), "'a' at /x/request", "built by 'unicode.text_type'");
        refused(api("filed-alias", """
                user_id => uuid
                a => !refs { x => { request: user_id } }"""), "'a' at /x/request", "built by 'net.uuid_type'");
        assertEquals(List.of(), problems(api("filed-right", """
                a => !refs { x => { request: order  method: orders.place_order } }""")));
    }

    /**
     * A {@code ValueRef} host receives the value its name reaches when the schema links: the binding's {@code method}
     * is the interface's {@code place_order}, delivered into the cell the read created with the name.
     */
    @Test
    void aReferenceHostReceivesTheValueItNamesWhenTheSchemaLinks() {
        String id = api("delivered", """
                routes => !bindings { b => { method: orders.place_order  verb: POST  route: "/orders" } }""");

        Binding b = assertInstanceOf(Binding.class,
                ((Namespace<?>) valueOf(registry().resolveLinked(id).schema().entries().get("routes"))).get("b"));

        assertEquals("orders.place_order", b.method().name());
        assertTrue(b.method().isResolved(), "delivered at link");
        assertEquals("place_order_request", b.method().get().request().name());
    }

    /** A host that declares no reference at all has its by-name ones judged the same way. */
    @Test
    void aReferenceByNameDeclaredNowhereIsStillJudgedBySlot() {
        refused(api("nowhere-text", """
                a => !nowheres { x => { request: text } }"""), "'a' at /x/request", "built by 'unicode.text_type'");
        refused(api("nowhere-alias", """
                user_id => uuid
                a => !nowheres { x => { request: user_id } }"""), "'a' at /x/request", "built by 'net.uuid_type'");
        assertEquals(List.of(), problems(api("nowhere-right", """
                a => !nowheres { x => { request: order } }""")));
    }

    // ── Through a dependent field ───────────────────────────────────────────────────────────────────

    /**
     * A segment selecting a dependent field reaches a value of the type its sibling names in the same value: {@code
     * d.s.value} is a {@code meta.identifier} where {@code kind} names one, and an {@code int32} where it names that.
     */
    @Test
    void aNameThroughADependentFieldHasTheTypeItsSiblingNames() {
        assertEquals(List.of(), problems(api("dependent", """
                d => !settings { s => { kind: meta.identifier  value: hello } }
                l => !labels { x => { text: d.s.value } }""")));
        refused(api("dependent-typed", """
                d => !settings { s => { kind: int32  value: 12 } }
                l => !labels { x => { text: d.s.value } }"""),
                "'l' at /x/text wants a 'meta.identifier'", "'d.s.value' is an 'int32'");
    }

    /** A pinned sibling types its field by the pin, which the walk takes from the schema, not from the host. */
    @Test
    void aPinnedSiblingTypesTheFieldThoughTheHostHoldsNoComponentForIt() {
        String id = api("dependent-pinned", """
                p => !pinned_settings { x => { value: hello } }
                l => !labels { x => { text: p.x.value } }""");
        assertEquals(List.of(), problems(id));
        assertInstanceOf(PinnedSetting.class,
                ((Namespace<?>) valueOf(registry().resolveLinked(id).schema().entries().get("p"))).get("x"));
    }

    @Test
    void aDefaultedSiblingTypesTheFieldWhereTheValueOmitsIt() {
        assertEquals(List.of(), problems(api("dependent-defaulted", """
                p => !defaulted_settings { x => { value: hello } }
                l => !labels { x => { text: p.x.value } }""")));
    }

    /** The read path's rule: a template is no type a value can be read against, so the walk has none to go on with. */
    @Test
    void aSiblingNamingNoTypeIsRefusedAtTheName() {
        List<Diagnostic> problems = problems(api("dependent-template", """
                box => <T> { v: T }
                d => !settings { s => { kind: box  value: 12 } }
                l => !labels { x => { text: d.s.value } }"""));
        assertTrue(problems.stream().anyMatch(p -> p.code() == Diagnostic.Code.SCHEMA_ERROR
                && p.message().contains("'l' at /x/text names 'd.s.value', and 'd.s.value' is a dependent field, "
                        + "typed by its sibling 'kind', which holds 'box', a template")), problems.toString());
    }

    /** Past a dependent field a segment selects a field of the type its sibling names, and no other. */
    @Test
    void aSegmentPastADependentFieldIsAFieldOfTheTypeItsSiblingNames() {
        refused(api("dependent-no-field", """
                d => !settings { s => { kind: pairing } }
                routes => !bindings { b => { method: d.s.value.nope  verb: POST  route: "/" } }"""),
                "'d.s.value' is a 'pairing', which has no field 'nope' -- it declares primary");
    }

    /**
     * A dependent field the value does not hold is typed by its sibling all the same, and the name is judged by type
     * before it is refused as reaching no value; past a field the value does not hold, a free sibling has no value to
     * be read from, and the name is refused as reaching none.
     */
    @Test
    void aNameThroughAnAbsentValueIsRefusedAsReachingNone() {
        refused(api("dependent-absent", """
                d => !settings { s => { kind: pairing } }
                routes => !bindings { b => { method: d.s.value.primary  verb: POST  route: "/" } }"""),
                "'d.s' is a 'setting' whose field 'value' is not present in the value, so the name reaches no value");
        refused(api("dependent-unreached", """
                h => !holders { x => { } }
                l => !labels { x => { text: h.x.inner.value } }"""),
                "'h.x' is a 'holder' whose field 'inner' is not present in the value, so the name reaches no value");
    }

    // ── A dependent field's value held as written, judged when the schema links ────────────────────────────────────

    /** A token is judged against the type its sibling names, in the schema's own namespace. */
    @Test
    void aHeldTokenIsJudgedAgainstTheTypeItsSiblingNames() {
        assertEquals(List.of(), problems(api("held-token", """
                d => !settings { s => { kind: int32  value: 12 } }""")));
        refused(api("held-token-refused", """
                d => !settings { s => { kind: int32  value: abc } }"""),
                "'d' at /s/value is the dependent field 'value', typed by its sibling 'kind'",
                "read against 'int32'");
    }

    /** A record, a type of the schema being read, is read by that type's reader: the imported vocabulary holds none. */
    @Test
    void aHeldRecordIsReadAgainstALocalType() {
        assertEquals(List.of(), problems(api("held-record", """
                server => { port: int32  host?: text }
                d => !settings { s => { kind: server  value: { port: 8080  host: example } } }""")));
        refused(api("held-record-type", """
                server => { port: int32 }
                d => !settings { s => { kind: server  value: { port: abc } } }"""),
                "'d' at /s/value is the dependent field 'value'", "read against 'server'", "at /port");
        refused(api("held-record-field", """
                server => { port: int32 }
                d => !settings { s => { kind: server  value: { port: 1  nope: 2 } } }"""),
                "read against 'server'", "nope");
    }

    /** A list is held and judged as a record is. */
    @Test
    void aHeldListIsReadAgainstTheTypeItsSiblingNames() {
        assertEquals(List.of(), problems(api("held-list", """
                ports => [int32]
                d => !settings { s => { kind: ports  value: [1 2 3] } }""")));
        refused(api("held-list-refused", """
                ports => [int32]
                d => !settings { s => { kind: ports  value: [1 two 3] } }"""),
                "read against 'ports'", "at /1");
    }

    /** A pinned sibling's pin, and a defaulted one's default, type the value the instance does not type. */
    @Test
    void aPinnedOrDefaultedSiblingTypesTheHeldValue() {
        refused(api("held-pinned", """
                p => !pinned_settings { x => { value: "not a name" } }"""),
                "'p' at /x/value is the dependent field 'value', typed by its sibling 'kind'",
                "read against 'meta.identifier'");
        refused(api("held-defaulted", """
                p => !defaulted_settings { x => { value: "not a name" } }"""),
                "read against 'meta.identifier'");
        assertEquals(List.of(), problems(api("held-defaulted-written", """
                p => !defaulted_settings { x => { kind: int32  value: 12 } }""")));
    }

    /** A sibling refused at its own slot is refused once: the value it types is not judged on top of it. */
    @Test
    void aHeldValueWhoseSiblingIsRefusedIsNotJudged() {
        refused(api("held-template", """
                box => <T> { v: T }
                d => !settings { s => { kind: box  value: { v: 1 } } }"""),
                "'d' at /s/kind names 'box', and 'box' is an open entry");
    }

    /** A value by name goes on through a dependent field into the record it holds. */
    @Test
    void aNameGoesOnIntoAHeldRecordValue() {
        assertEquals(List.of(), problems(api("held-walk", """
                named => { name: meta.identifier }
                d => !settings { s => { kind: named  value: { name: hello } } }
                l => !labels { x => { text: d.s.value.name } }""")));
        refused(api("held-walk-absent", """
                named => { name?: meta.identifier }
                d => !settings { s => { kind: named  value: { } } }
                l => !labels { x => { text: d.s.value.name } }"""),
                "'d.s.value' is a 'named' whose field 'name' is not present in the value");
    }

    /** A name a held value holds by name is judged as any value body's, at its slot from the entry's root. */
    @Test
    void aNameInsideAHeldValueIsJudged() {
        refused(api("held-inner-name-refused", """
                d => !settings { s => { kind: label  value: { text: nobody.here } } }"""),
                "'d' at /s/value/text");
    }

    /** A value entry's payload is judged; a field's default or pin keeps its own token-level check. */
    @Test
    void aDefaultKeepsItsOwnCheck() {
        refused(api("held-default-unchanged", """
                rec => { n?: int32 ~ abc }"""),
                "'rec': field 'n' is declared 'int32', but its default abc is not a value of that type");
    }

    /** A value entry's value. */
    private static Object valueOf(Object entry) {
        return ((ValueEntry) entry).value();
    }
}
