package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.consumer.Operation;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A meta layer's own vocabulary riding along with a schema: a record a <b>meta-schema</b> declares, held in a
 * namespace type of it, each value bound to the consumer's own Java class ({@link Operation}).
 *
 * <p>The motivating case is an HTTP API described at the schema layer, because that is the only layer that
 * can name request and response types <em>by name</em> -- a data document can only instantiate a type. An entry
 * built with a namespace type is a value and never a type ([TSON-SCHEMA] §4.1), which is what lets it sit in a
 * schema map beside the types it names.
 *
 * <p><b>The wiring, in full, is three things</b> -- there is nothing else to register:
 * <ol>
 *   <li>a meta-layer schema chaining {@code !!meta} to meta-kernel and declaring a record
 *       {@code operation => { ... }} and a namespace of it, {@code operations => namespace & { ... }}
 *       ({@link #META_HTTP_SCHEMA});</li>
 *   <li>a Java class for {@code operation} ({@link Operation}), a plain record;</li>
 *   <li>a {@link DataBindContext} whose {@link DataNameBinder} can find that class
 *       ({@link #consumerContext()}).</li>
 * </ol>
 * A governed schema then writes {@code api => !operations { search => { ... } }} and each value of the resolved
 * entry's map <em>is</em> an {@link Operation}.
 */
class MetaLayerVocabularyTest {

    private static final String META_HTTP = "https://example.test/meta-http.tn";

    /** (1) The meta-layer schema: an operation record, and the namespace type whose values are operations. */
    private static final String META_HTTP_SCHEMA = """
            !!id:"https://example.test/meta-http.tn"
            !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/38/m/meta.tn"
            {
              operation => {
                path:     text
                method:   text
                request:  type_kind
                response: type_kind
              }
              operations => namespace & { key_type: = identifier  value_type: = operation }
              webhook => {
                path:     text
                delivers?: [type_kind]?
              }
              webhooks    => namespace & { key_type: = identifier  value_type: = webhook }
              status_code => text
              plain       => { a: text }
              envelope    => <T> { body: T }
              pair        => <A, B> { a: A  b: B }
            }
            """;

    /** The declaration under test, written by a schema governed by the meta above. */
    private static final String SEARCH = """
            api => !operations {
                search => { path: "/search"  method: "GET"  request: search_request  response: search_response }
              }""";

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put(META_HTTP, META_HTTP_SCHEMA);
    }

    /**
     * (3) The bind context, composed rather than copied. {@link SchemaMetaNameBinder#INSTANCE} already
     * resolves the kernel's own vocabulary; a consumer wants that <em>plus</em> their own, so {@link
     * SchemaMetaNameBinder#contextExtendedWith} asks the library's binder first and this one only for a
     * name it does not know. Nothing here duplicates the kernel's table, and nothing needs keeping in sync
     * when that vocabulary grows. {@code ProcessorConfig.metaNameBinder} is the same seam through the front door.
     */
    private static final DataNameBinder CONSUMER_NAMES = new DataNameBinder.DefaultDataNameBinder(
            Set.of("io.ltr8.tson.compiler.consumer"), Map.of());

    private static DataBindContext consumerContext() {
        return SchemaMetaNameBinder.contextExtendedWith(CONSUMER_NAMES);
    }

    private static TsonCompiledMetaRegistry core(DataBindContext context) {
        SchemaSource source = uri -> {
            for (Map.Entry<String, String> document : DOCUMENTS.entrySet()) {
                if (CanonicalIdentity.sameIdentity(uri, document.getKey())) {
                    return document.getValue();
                }
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledMetaRegistry.withStandardLibrary(context, source);
    }

    /** An API document against the meta above, named for the case so each test gets its own identity. */
    private static String api(String label, String declarations) {
        String id = "https://example.test/api-" + label + ".tn";
        DOCUMENTS.put(id, """
                !!id:"%s"
                !!meta:"https://example.test/meta-http.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  search_request  => { q: text }
                  search_response => { hits: [text] }
                  %s
                }
                """.formatted(id, declarations));
        return id;
    }

    private static TsonLinkedSchema linked(String label, String declarations) {
        return core(consumerContext()).resolveLinked(api(label, declarations));
    }

    // ── The meta-layer declaration ───────────────────────────────────────────────────────────────

    /**
     * {@code operations => namespace & { ... }} is a namespace type: a constructor, IS-A {@code top} through the
     * kernel's {@code namespace}. The record its values are is an ordinary record type, and no constructor.
     */
    @Test
    void theMetaSchemaDeclaresANamespaceOfOperations() {
        TsonLinkedSchema meta = core(consumerContext()).resolveLinked(META_HTTP);
        Top operations = meta.schema().entries().get("operations");

        assertEquals(EntryKind.NAMESPACE, meta.kindOf("operations"));
        assertEquals(List.of("namespace", "top"), TypeChain.supertypesOf(operations),
                "the transitive chain, and IS-A `top` is what makes `!operations { ... }` applicable");
        assertEquals(EntryKind.PRODUCT, meta.kindOf("operation"));
        assertFalse(meta.isConstructor("operation", meta.schema().entries().get("operation")),
                "the record its values are is a type, applied by no tag of its own in a governed schema");
    }

    /**
     * The other side of the same predicate: a record-bodied entry with an empty chain is a <em>part</em> of a
     * type ({@code record_field}, {@code type_ref}, {@code integer_size}, …) and is refused where it is
     * written. Without the check it fails anyway, on {@code Top} being sealed -- but as a {@code
     * ClassCastException} surfaced as {@code NOT_IMPLEMENTED}, a non-verdict, for an author's mistake.
     */
    @Test
    void aComponentOfATypeIsNotApplicable() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> linked("component", "  bad => !record_field { name: x  type: text }"));

        assertTrue(thrown.getMessage().contains("not IS-A 'top'"), thrown.getMessage());
    }

    // ── Binding the record to the consumer's class ───────────────────────────────────────────────

    /**
     * The binding itself: {@code operation} in the schema resolves to {@link Operation} in Java, through
     * the name binder alone. No reader family, no factory registration -- the ordinary record reader binds
     * an operation value straight into the record.
     */
    @Test
    void theMetaRecordBindsToTheRegisteredJavaClass() throws Exception {
        DataNameBinder binder = SchemaMetaNameBinder.extendedWith(CONSUMER_NAMES);
        assertEquals(Operation.class, binder.resolve("operation"),
                "the name binder is the whole of the registration");
        assertEquals(io.ltr8.tson.schema.meta.RecordBody.class, binder.resolve("record"),
                "and the library's own vocabulary still resolves through it");

        Object read = core(consumerContext()).loadMeta(META_HTTP).compiledSchema().find("operation")
                .orElseThrow()
                .read(TestDocuments.document("{ path: \"/p\"  method: \"GET\"  request: a  response: b }"));

        assertEquals(new Operation("/p", "GET", TypeRef.of("a"), TypeRef.of("b")), read);
    }

    /**
     * <b>The value is retrievable from the linked schema.</b> A consumer holding a {@link TsonLinkedSchema}
     * reads an operation out of it as an ordinary Java object -- which is the point of the whole exercise:
     * the operations are addressable, and their request/response types resolve in the same registry as the
     * types they name.
     */
    @Test
    void anOperationValueIsRetrievedFromTheLinkedSchema() {
        TsonLinkedSchema schema = linked("retrieve", SEARCH);

        Namespace<?> api = assertInstanceOf(Namespace.class, schema.schema().entries().get("api"));
        assertEquals(EntryKind.NAMESPACE, schema.kindOf("api"));
        assertEquals(Optional.of("operations"), schema.schema().constructorOf("api"), "§8.2 records what built it");

        Operation operation = assertInstanceOf(Operation.class, api.get("search"));
        assertEquals("/search", operation.path());
        assertEquals("GET", operation.method());
        assertEquals(TypeRef.of("search_request"), operation.request());
        assertEquals(TypeRef.of("search_response"), operation.response());

        // The types it names are entries of the same schema, so a consumer can follow them straight through.
        assertTrue(schema.schema().entries().containsKey(operation.request().name()));
        assertTrue(schema.schema().entries().containsKey(operation.response().name()));
    }

    /** Every operation in a schema is found by asking for the entries built with {@code operations}. */
    @Test
    void everyOperationInASchemaIsEnumerable() {
        TsonLinkedSchema schema = linked("enumerate", SEARCH + """

                  more => !operations {
                    create => { path: "/items"  method: "POST"  request: search_request  response: search_response }
                  }""");

        Map<String, Operation> operations = new LinkedHashMap<>();
        schema.schema().entries().forEach((name, entry) -> {
            if (entry instanceof Namespace<?> namespace && namespace.type().equals("operations")) {
                namespace.entries().forEach((key, value) -> operations.put(name + "." + key, (Operation) value));
            }
        });

        assertEquals(Set.of("api.search", "more.create"), operations.keySet());
        assertEquals("GET", operations.get("api.search").method());
        assertEquals("POST", operations.get("more.create").method());
    }

    // ── A value is validated against the record's own declaration ────────────────────────────────

    @Test
    void anUnknownFieldInAValueIsASchemaError() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> linked("badfield", SEARCH.replace("path:", "pathh:")));

        assertTrue(thrown.getMessage().contains("unknown field 'pathh' on 'operation'"), thrown.getMessage());
    }

    @Test
    void aMissingRequiredFieldInAValueIsASchemaError() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> linked("missing", SEARCH.replace("path: \"/search\"  ", "")));

        assertTrue(thrown.getMessage().contains("missing required field 'path'"), thrown.getMessage());
    }

    @Test
    void aWrongTypedValueInAValueIsASchemaError() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> linked("badtype", SEARCH.replace("method: \"GET\"", "method: [1 2 3]")));

        assertTrue(thrown.getMessage().contains("expected a token for 'text'"), thrown.getMessage());
    }

    /** A value whose optional list of names is written links: the class declares nothing, so there is nothing to guard. */
    @Test
    void aValueThatNamesItsReferencesLinks() {
        Namespace<?> hooks = assertInstanceOf(Namespace.class, linked("withrefs", """
                hooks => !webhooks { hook => { path: "/hook"  delivers: [ search_request ] } }""")
                .schema().entries().get("hooks"));
        assertNotNull(hooks.get("hook"));
    }

    /**
     * <b>A declared application mints no name, so §8.2's hygiene walk has nothing to judge.</b> {@code getOrder =>
     * fetch<search_request>} <em>is</em> the entry the application denotes (§8.2), its facts recording the
     * application -- so the author's punctuation ({@code "/x"}) and the author's Cyrillic never reach a derived name,
     * because no name is derived. The schema loads.
     *
     * <p><b>What still mints, and is still judged, is a name nobody wrote</b>: a sugar lift, or an
     * application some <em>use site</em> closes. {@code InternalName} keeps its rule for those -- admitted
     * ASCII spliced, other ASCII truncated beside a hash, anything else the hash alone -- and this schema
     * has one, {@code search_response}'s own {@code [text]}. So the claim below is the stronger one: every name in
     * the schema is ASCII, and none carries the author's text.
     */
    @Test
    void aDeclaredApplicationMintsNoNameForNameHygieneToJudge() {
        for (String path : new String[] {"/x", "путь"}) {
            String label = "minted" + Math.abs(path.hashCode());
            TsonLinkedSchema resolved = assertDoesNotThrow(() -> linked(label, """
                    fetch    => <T> !operations {
                        get => { path: "%s"  method: "GET"  request: T  response: T }
                      }
                    getOrder => fetch<search_request>""".formatted(path)),
                    () -> "a declared application mints nothing, so no name of its own can be refused");

            assertEquals(EntryKind.NAMESPACE, resolved.kindOf("getOrder"), "the declaration is the value it denotes");
            assertEquals("fetch", resolved.schema().applicationOf("getOrder").orElseThrow().name(),
                    "and records the application, which is what §8.2 keys its identity on");

            String own = CanonicalIdentity.canonicalize("https://example.test/api-" + label + ".tn");
            resolved.schema().entries().forEach((name, definition) -> {
                if (!resolved.originOf(name).equals(own)) {
                    return;
                }
                assertFalse(name.contains(path),
                        () -> "no entry name carries the author's own text: '" + name + "'");
                assertTrue(name.chars().allMatch(character -> character < 0x80),
                        () -> "§8.2: every name here is ASCII, which is what lets the walk judge it: '"
                                + name + "'");
            });
        }
    }

    /** A name at a kind-typed slot is recorded by the read and judged at link: one resolving to nothing is an error. */
    @Test
    void aDanglingReferenceInsideAnOperationIsASchemaError() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> linked("dangling", SEARCH.replace("request: search_request", "request: no_such_type")));

        assertTrue(thrown.getMessage().contains("'no_such_type' is no type declared in this schema or its imports"),
                thrown.getMessage());
    }

    // ── A namespace entry is not a type ──────────────────────────────────────────────────────────

    /**
     * An entry built with a namespace type is a value, and the linker says so: every position a type-ref can occupy
     * refuses it at link time, where otherwise the misuse would resolve, link and compile and fail only when a
     * document is read against it.
     */
    @Test
    void namingANamespaceEntryWhereATypeBelongsIsASchemaError() {
        record Position(String label, String declaration, String context) {
        }
        List<Position> positions = List.of(
                new Position("field", "holder => { s: api }", "'holder' field 's'"),
                new Position("variant", "either => (api | search_request)", "'either' variant[0]"),
                new Position("element", "many => [api]", "'many' element_type"),
                new Position("mapvalue", "byName => {text => api}", "'byName' value_type"));

        for (Position position : positions) {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                    () -> linked(position.label(), SEARCH + "\n  " + position.declaration()),
                    () -> "expected " + position.label() + " to be refused");

            assertTrue(thrown.getMessage().startsWith(position.context()),
                    position.label() + ": " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("a value of the namespace type 'operations'"),
                    position.label() + ": " + thrown.getMessage());
        }
    }

    /** Ordinary types declared beside the operations are unaffected -- they compile and read as always. */
    @Test
    void ordinaryTypesInTheSameSchemaStillRead() {
        TsonCompiledSchema compiled =
                TsonCompiledSchemaRegistry.tree(core(consumerContext())).get(api("read", SEARCH));

        TsonValue value = (TsonValue) compiled.get("search_request")
                .read(TestDocuments.document("{ q: \"hello\" }"));

        assertEquals(Optional.of("hello"), value.get("q").asString());
    }

    // ── A meta layer's own vocabulary is not in the governed schema's namespace ──────────────────

    /**
     * <b>Every reference form gives the same verdict, applied or not.</b> {@code !!meta} says where this
     * schema's constructors come from; it merges nothing into the type-name namespace (§3.3.2), so a name the
     * meta layer declares -- atom, record, or template -- is simply unresolved in a schema it governs.
     *
     * <p>The applied row is the one a second lookup path would get wrong: an application this schema cannot close
     * is unresolved like any other reference, never found in the governing meta's structure namespace and
     * faulted for arguments the author had in fact written.
     */
    @Test
    void aMetaLayerNameIsUnresolvedInAGovernedSchemaWhetherOrNotItIsApplied() {
        record Reference(String label, String declaration, String unresolved) {
        }
        List<Reference> references = List.of(
                new Reference("atom", "x => { s: status_code }", "status_code"),
                new Reference("record", "x => { s: plain }", "plain"),
                new Reference("valueRecord", "x => { s: operation }", "operation"),
                new Reference("template", "x => { s: envelope }", "envelope"),
                new Reference("appliedInField", "x => { s: envelope<text> }", "envelope"),
                new Reference("applied", "x => envelope<text>", "envelope"),
                new Reference("appliedWithWrongArity", "x => pair<text>", "pair"));

        for (Reference reference : references) {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                    () -> linked("scope" + reference.label(), SEARCH + "\n  " + reference.declaration()),
                    () -> "expected " + reference.label() + " to be refused");

            assertTrue(thrown.getMessage().contains("unresolved reference '" + reference.unresolved() + "'"),
                    reference.label() + ": " + thrown.getMessage());
        }
    }

    /** The control: the same template declared locally applies exactly as it always has. */
    @Test
    void theSameTemplateDeclaredLocallyStillApplies() {
        TsonLinkedSchema schema = linked("localtemplate",
                SEARCH + "\n  envelope => <T> { body: T }\n  x => envelope<text>");

        assertTrue(schema.schema().entries().containsKey("x"));
    }

    /** §8.1's resolved output writes the entry as written: the constructor's tag, then the map of operations. */
    @Test
    void resolvedOutputCarriesTheOperations() {
        Top entry = linked("write", SEARCH).schema().entries().get("api");

        String written = new TsonObjectWriter(consumerContext()).toTson(entry).replaceAll("\\s+", " ");

        // `kind` is not written: it is derived at resolution and §8's output has no field for it. What
        // identifies a namespace entry in output is its value -- the constructor's instance the entry is.
        assertFalse(written.contains("kind:"), written);
        assertTrue(written.startsWith("!operations { search => "), written);
        assertTrue(written.contains("{ path: \"/search\" method: \"GET\" request: search_request "
                + "response: search_response }"), written);
    }
}
