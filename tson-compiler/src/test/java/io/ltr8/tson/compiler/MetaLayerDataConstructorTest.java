package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.consumer.Operation;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data riding along with a schema: an instance of a constructor a <b>meta-schema</b> declares, bound to the
 * consumer's own Java class ({@link Operation}).
 *
 * <p>The motivating case is an HTTP API described at the schema layer, because that is the only layer that
 * can name request and response types <em>by name</em> -- a data document can only instantiate a type. The
 * kernel's {@code data} base kind is what lets such an entry say it is not a type ([TSON-SCHEMA] §4.1,
 * {@code SPEC-FEEDBACK.md} #57).
 *
 * <p><b>The wiring, in full, is three things</b> -- there is nothing else to register:
 * <ol>
 *   <li>a meta-layer schema chaining {@code !!meta} to meta-kernel and declaring
 *       {@code operation => ~data & { ... }} ({@link #META_HTTP_SCHEMA});</li>
 *   <li>a Java class carrying {@code @Typename(name = "operation")} and implementing {@link Data}
 *       ({@link Operation});</li>
 *   <li>a {@link DataBindContext} whose {@link DataNameBinder} can find that class
 *       ({@link #consumerContext()}).</li>
 * </ol>
 * A governed schema then writes {@code search => !operation { ... }} and the resolved entry's body
 * <em>is</em> an {@link Operation}.
 */
class MetaLayerDataConstructorTest {

    private static final String META_HTTP = "https://example.test/meta-http.tn";

    /** (1) The meta-layer schema. `~data &`: an operation describes an endpoint, not a data value. */
    private static final String META_HTTP_SCHEMA = """
            !!id:"https://example.test/meta-http.tn"
            !!meta:"https://tson.io/2026/32/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/32/m/meta.tn"
            {
              operation => ~data & {
                path:     text
                method:   text
                request:  type_ref
                response: type_ref
              }
              status_code => text
              plain       => { a: text }
              envelope    => <T> { body: T }
              pair        => <A, B> { a: A  b: B }
            }
            """;

    /** The declaration under test, written by a schema governed by the meta above. */
    private static final String SEARCH = """
            search => !operation {
                path: "/search"  method: "GET"  request: search_request  response: search_response
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
     * when that vocabulary grows. {@code TsonConfig.metaNameBinder} is the same seam through the front door.
     */
    private static final DataNameBinder CONSUMER_NAMES = new DataNameBinder.DefaultDataNameBinder(
            Set.of("io.ltr8.tson.compiler.consumer"), Map.of());

    private static DataBindContext consumerContext() {
        return SchemaMetaNameBinder.contextExtendedWith(CONSUMER_NAMES);
    }

    private static TsonCompiledMetaRegistry core(DataBindContext context) {
        TsonSchemaSource source = uri -> {
            for (Map.Entry<String, String> document : DOCUMENTS.entrySet()) {
                if (TsonCanonicalIdentity.sameIdentity(uri, document.getKey())) {
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
                !!import:"https://tson.io/2026/32/m/core.tn"
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

    /** {@code ~data & { ... }} resolves to a constructor whose instances are DATA-kinded. */
    @Test
    void theMetaSchemaDeclaresOperationAsADataConstructor() {
        TypeDefinition operation =
                core(consumerContext()).resolveLinked(META_HTTP).schema().entries().get("operation");

        assertEquals(TypeKind.DATA, operation.kind());
        assertTrue(operation.constructor(), "`~` marks it applicable as `!operation { ... }`");
        assertEquals(List.of("data", "top"), operation.supertypes(),
                "the transitive chain -- `data` is itself `top & {}`");
    }

    /**
     * {@code ~} is the permission for a <em>schema</em> to write {@code !C ...} (§3.3.1/§5.6), not a claim
     * about type-ness -- so it is required here even though an operation is not a type.
     */
    @Test
    void withoutTheTildeTheConstructorCannotBeApplied() {
        DOCUMENTS.put(META_HTTP, META_HTTP_SCHEMA.replace("operation => ~data &", "operation => data &"));
        try {
            TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                    () -> linked("notilde", SEARCH));

            assertTrue(thrown.getMessage().contains("does not resolve to a constructor"), thrown.getMessage());
        } finally {
            DOCUMENTS.put(META_HTTP, META_HTTP_SCHEMA);
        }
    }

    // ── Binding the constructor to the consumer's class ──────────────────────────────────────────

    /**
     * The binding itself: {@code operation} in the schema resolves to {@link Operation} in Java, through
     * the name binder alone. No reader family, no factory registration -- the ordinary record reader binds
     * the {@code !operation { ... }} payload straight into the record.
     */
    @Test
    void theSchemaConstructorBindsToTheRegisteredJavaClass() throws Exception {
        DataNameBinder binder = SchemaMetaNameBinder.extendedWith(CONSUMER_NAMES);
        assertEquals(Operation.class, binder.resolve("operation"),
                "the name binder is the whole of the registration");
        assertEquals(io.ltr8.tson.schema.meta.RecordBody.class, binder.resolve("record"),
                "and the library's own vocabulary still resolves through it");

        // No reader family and no factory registration: the ordinary record reader reads an
        // `!operation { ... }` payload straight into the record.
        Object read = core(consumerContext()).loadMeta(META_HTTP).compiledSchema().find("operation")
                .orElseThrow()
                .read(TestDocuments.document("!operation { path: \"/p\"  method: \"GET\""
                        + "  request: a  response: b }"));

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

        TypeDefinition entry = schema.schema().entries().get("search");
        assertEquals(TypeKind.DATA, entry.kind());
        assertEquals(Optional.of(TypeRef.of("operation")), entry.source(), "§8.2 records what built it");

        Operation operation = assertInstanceOf(Operation.class, entry.body());
        assertEquals("/search", operation.path());
        assertEquals("GET", operation.method());
        assertEquals(TypeRef.of("search_request"), operation.request());
        assertEquals(TypeRef.of("search_response"), operation.response());

        // The types it names are entries of the same schema, so a consumer can follow them straight through.
        assertTrue(schema.schema().entries().containsKey(operation.request().name()));
        assertTrue(schema.schema().entries().containsKey(operation.response().name()));
    }

    /** Every operation in a schema is found by asking for the bodies that are {@link Data}. */
    @Test
    void everyOperationInASchemaIsEnumerable() {
        TsonLinkedSchema schema = linked("enumerate", SEARCH + """

                  create => !operation {
                    path: "/items"  method: "POST"  request: search_request  response: search_response
                  }""");

        Map<String, Operation> operations = new LinkedHashMap<>();
        schema.schema().entries().forEach((name, entry) -> {
            if (entry.body() instanceof Operation operation) {
                operations.put(name, operation);
            }
        });

        assertEquals(Set.of("search", "create"), operations.keySet());
        assertEquals("GET", operations.get("search").method());
        assertEquals("POST", operations.get("create").method());
    }

    // ── The payload is validated against the constructor's own declaration ───────────────────────

    @Test
    void anUnknownFieldInThePayloadIsASchemaError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> linked("badfield", SEARCH.replace("path:", "pathh:")));

        assertTrue(thrown.getMessage().contains("unknown field 'pathh' on 'operation'"), thrown.getMessage());
    }

    @Test
    void aMissingRequiredFieldInThePayloadIsASchemaError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> linked("missing", SEARCH.replace("path: \"/search\"  ", "")));

        assertTrue(thrown.getMessage().contains("missing required field 'path'"), thrown.getMessage());
    }

    @Test
    void aWrongTypedValueInThePayloadIsASchemaError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> linked("badtype", SEARCH.replace("method: \"GET\"", "method: [1 2 3]")));

        assertTrue(thrown.getMessage().contains("expected a token for 'text'"), thrown.getMessage());
    }

    /** {@link Data#references()} reaches the linker, so a name that resolves to nothing is an author error. */
    @Test
    void aDanglingReferenceInsideAnOperationIsASchemaError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> linked("dangling", SEARCH.replace("request: search_request", "request: no_such_type")));

        assertTrue(thrown.getMessage().contains("unresolved reference 'no_such_type'"), thrown.getMessage());
    }

    // ── An operation is not a type ───────────────────────────────────────────────────────────────

    /**
     * The DATA kind is what lets the linker say so. Every position a type-ref can occupy is refused at link
     * time, where against a kernel without {@code data} the misuse resolves, links and compiles and fails
     * only when a document is read against it.
     */
    @Test
    void namingAnOperationWhereATypeBelongsIsASchemaError() {
        record Position(String label, String declaration, String context) {
        }
        List<Position> positions = List.of(
                new Position("field", "holder => { s: search }", "'holder' field 's'"),
                new Position("variant", "either => (search | search_request)", "'either' variant[0]"),
                new Position("element", "many => [search]", "'many' element_type"),
                new Position("mapvalue", "byName => {text => search}", "'byName' value_type"));

        for (Position position : positions) {
            TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                    () -> linked(position.label(), SEARCH + "\n  " + position.declaration()),
                    () -> "expected " + position.label() + " to be refused");

            assertTrue(thrown.getMessage().startsWith(position.context()),
                    position.label() + ": " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("describes something other than a data value"),
                    position.label() + ": " + thrown.getMessage());
        }
    }

    /** Ordinary types declared beside an operation are unaffected -- they compile and read as always. */
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
     * <p>The applied row is the one that used to disagree. An application this schema cannot close reached
     * {@code source}, the one slot whose lookup falls back to the governing meta's structure namespace, found
     * the template there, and reported it as taking arguments the author had in fact written -- sending them
     * to check the argument list, which was never the problem. Two lookup paths disagreeing about what is in
     * scope is the defect; the arity complaint was only how it surfaced.
     */
    @Test
    void aMetaLayerNameIsUnresolvedInAGovernedSchemaWhetherOrNotItIsApplied() {
        record Reference(String label, String declaration, String unresolved) {
        }
        List<Reference> references = List.of(
                new Reference("atom", "x => { s: status_code }", "status_code"),
                new Reference("record", "x => { s: plain }", "plain"),
                new Reference("template", "x => { s: envelope }", "envelope"),
                new Reference("appliedInField", "x => { s: envelope<text> }", "envelope"),
                new Reference("applied", "x => envelope<text>", "envelope"),
                new Reference("appliedWithWrongArity", "x => pair<text>", "pair"));

        for (Reference reference : references) {
            TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
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

    /** §8.1's resolved output carries the body under the constructor that built it, like any other. */
    @Test
    void resolvedOutputCarriesTheOperationBody() {
        TypeDefinition entry = linked("write", SEARCH).schema().entries().get("search");

        String written = new TsonObjectWriter(consumerContext()).toTson(entry).replaceAll("\\s+", " ");

        assertTrue(written.contains("kind: \"DATA\""), written);
        assertTrue(written.contains("body: !operation { path: \"/search\" method: \"GET\""), written);
        assertTrue(written.contains("request: { name: \"search_request\" arguments: [] }"), written);
    }
}
