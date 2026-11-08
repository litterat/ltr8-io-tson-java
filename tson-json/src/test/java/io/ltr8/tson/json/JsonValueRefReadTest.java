package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.ResolverBindContext;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.Ref;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [TSON-JSON] §5's reading of a position typed by a reference that names values ([TSON-SCHEMA] §4.2, §5.12): a JSON
 * string holding the qualified name of a value of the governing schema, judged by what it reaches as a TSON read
 * judges it, and bound into the host shapes a TSON read binds -- a {@code Ref} receiving the value, a {@code TypeRef}
 * the name carrying it, a {@code String} the name.
 */
class JsonValueRefReadTest {

    private static final String ID = "https://example.test/json-picks.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-picks.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              order     => { id: text }
              customer  => { name: text }
              orders    => !meta.namespace { key_type: meta.identifier  value_type: order }
              order_ref => meta.ref<order>
              pick      => { which: order_ref }
              o1        => !order { id: A-1 }
              c1        => !customer { name: Ada }
              book      => !orders { first => { id: B-1 } }
            }
            """;

    public record Order(String id) {
    }

    public record Customer(String name) {
    }

    public record Pick(Ref<Order> which) {
    }

    public record NamedPick(TypeRef which) {
    }

    public record TextPick(String which) {
    }

    /**
     * The schema resolved under a context binding its value entries to this test's records, so a {@code Ref} names
     * an {@code Order}; the JSON reader binds the document's own records under a context of its own.
     */
    private static Json json(Class<?> pick) {
        SchemaSource source = uri -> SCHEMA;
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(SchemaModelNameBinder.contextExtendedWith(DataNameBinder.ofMap(
                        Map.of("order", Order.class, "customer", Customer.class)))));
        assertEquals(List.of(), tson.validateSchema(SCHEMA));
        DataBindContext binding = ResolverBindContext.registerDefaults(DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("pick", pick)))).build();
        return Json.of(ProcessorConfig.defaults().withDataBindContext(binding)).withSchemas(tson.schemaRegistry());
    }

    private static <T> T bind(Class<T> pick, String source) {
        return json(pick).objectReader().withSchema(ID).readAs(source, "pick", pick);
    }

    @Test
    void aNameIsJudgedByWhatItReaches() {
        Json json = json(Pick.class);
        assertEquals(List.of(), json.validate("{\"which\": \"o1\"}", ID, "pick"));
        assertEquals(List.of(), json.validate("{\"which\": \"book.first\"}", ID, "pick"));
        for (String refused : List.of("nope", "c1", "order", "book.second")) {
            List<Diagnostic> problems = json.validate("{\"which\": \"" + refused + "\"}", ID, "pick");
            assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                    problems.stream().map(Diagnostic::code).toList(), refused);
            assertEquals("/which", problems.getFirst().path().orElseThrow(), refused);
        }
    }

    @Test
    void aRefHostReceivesTheValueTheNameReaches() {
        Pick pick = bind(Pick.class, "{\"which\": \"o1\"}");

        assertEquals("o1", pick.which().name());
        assertEquals(new Order("A-1"), pick.which().get());
    }

    @Test
    void aTypeRefHostReceivesTheNameCarryingItsReferent() {
        NamedPick pick = bind(NamedPick.class, "{\"which\": \"book.first\"}");

        assertEquals(TypeRef.of("book.first"), pick.which());
        assertEquals(new Order("B-1"), pick.which().referent().orElseThrow());
    }

    @Test
    void aTextHostReceivesTheName() {
        assertEquals(new TextPick("o1"), bind(TextPick.class, "{\"which\": \"o1\"}"));
    }
}
