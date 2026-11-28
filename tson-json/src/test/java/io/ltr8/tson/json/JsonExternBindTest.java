package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.EntryKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JSON value bound through an extern ([TSON-JSON] §8.6, [TSON-SCHEMA] §7.9): its body bound by the foreign schema's
 * readers, each class asked of the binder by its entry's identity, so {@code card} of two foreign schemas binds two
 * classes; and a bound value written back and read again.
 */
class JsonExternBindTest {

    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String ORDER_1 = "https://example.test/order-1.tn";
    static final String ORDER_2 = "https://example.test/order-2.tn";
    static final String NUMS = "https://example.test/nums.tn";
    static final String SHIP = "https://example.test/ship.tn";
    static final String PETS = "https://example.test/pets.tn";
    static final String HOME = "https://example.test/home.tn";

    static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        schema(ORDER_1, "order => { id: text  payment: card }\ncard => { number: text }\n");
        schema(ORDER_2, "order => { ref: text  lines: [line]  payment: card }\nline => { sku: text  qty: int32 }\n"
                + "card => { pan: text }\n");
        schema(NUMS, "count => !integer ^ { min: 0 }\n");
        schema(SHIP, extern("order_v1", ORDER_1, "order") + extern("order_v2", ORDER_2, "order")
                + extern("count_x", NUMS, "count") + "envelope => { id: text  a: order_v1  b: order_v2  n: count_x }\n");
        schema(PETS, "pet => abstract { name: text }\ndog => pet & { barks: boolean }\ncat => pet & { purrs: boolean }\n");
        schema(HOME, extern("pet_x", PETS, "pet") + extern("dog_x", PETS, "dog") + extern("cat_x", PETS, "cat")
                + "home => { owner: text  pet: pet_x  pets: [pet_x] }\n");
    }

    static void schema(String id, String body) {
        DOCUMENTS.put(id, "!!id:\"" + id + "\"\n!!import:\"" + CORE + "\"\n!schema {\n" + body.indent(2) + "}\n");
    }

    static String extern(String entry, String schema, String type) {
        return entry + " => !meta.extern { schema: \"" + schema + "\"  type: " + type + " }\n";
    }

    public record CardOne(String number) {
    }

    public record OrderOne(String id, CardOne payment) {
    }

    public record CardTwo(String pan) {
    }

    public record Line(String sku, int qty) {
    }

    public record OrderTwo(String ref, List<Line> lines, CardTwo payment) {
    }

    public record Envelope(String id, OrderOne a, OrderTwo b, int n) {
    }

    public sealed interface Pet permits Dog, Cat {
    }

    public record Dog(String name, boolean barks) implements Pet {
    }

    public record Cat(String name, boolean purrs) implements Pet {
    }

    public record Home(String owner, Pet pet, List<Pet> pets) {
    }

    private static String identity(String uri) {
        return CanonicalIdentity.canonicalize(uri);
    }

    private static final DataNameBinder BINDER = DataNameBinder.byNamespace(Map.of(
            identity(SHIP), DataNameBinder.ofMap(Map.of("envelope", Envelope.class)),
            identity(ORDER_1), DataNameBinder.ofMap(Map.of("order", OrderOne.class, "card", CardOne.class)),
            identity(ORDER_2), DataNameBinder.ofMap(Map.of("order", OrderTwo.class, "card", CardTwo.class,
                    "line", Line.class)),
            identity(HOME), DataNameBinder.ofMap(Map.of("home", Home.class)),
            identity(PETS), DataNameBinder.ofMap(Map.of("pet", Pet.class, "dog", Dog.class, "cat", Cat.class))),
            DataNameBinder.ofMap(Map.of()));

    private static final DataBindContext BINDING = DataBindContext.builder().nameBinder(BINDER)
            .registerAtoms(AtomContext.hostTypes()).build();

    private static ProcessorConfig config() {
        SchemaSource source = uri -> DOCUMENTS.entrySet().stream()
                .filter(document -> CanonicalIdentity.sameIdentity(uri, document.getKey()))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                        "no document is served under '" + uri + "'", null));
        return ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)).withDataBindContext(BINDING);
    }

    private static final Tson TSON = Tson.of(config());
    private static final Json JSON = Json.of(config()).withSchemas(TSON.schemaRegistry());

    static {
        DOCUMENTS.values().forEach(TSON::resolve);
    }

    private static final String ENVELOPE = """
            {"id": "e", "a": {"id": "x", "payment": {"number": "n"}},
             "b": {"$type": "order_v2", "ref": "r", "lines": [{"$type": "line", "sku": "s", "qty": 2}],
                   "payment": {"pan": "p"}}, "n": 7}""";

    private static <T> T bind(String schema, String root, String json, Class<T> type) {
        List<Diagnostic> problems = new ArrayList<>();
        T read = JSON.objectReader().withSchema(schema).withDiagnostics(problems::add).readAs(json, root, type);
        assertEquals(List.of(), problems.stream().map(Diagnostic::message).toList(), json);
        return read;
    }

    @Test
    void cardOfTwoForeignSchemasBindsToTwoClasses() {
        assertEquals(new Envelope("e", new OrderOne("x", new CardOne("n")),
                new OrderTwo("r", List.of(new Line("s", 2)), new CardTwo("p")), 7),
                bind(SHIP, "envelope", ENVELOPE, Envelope.class));
    }

    /**
     * A compiled schema joins its binding and its readers by entry identity: its own entries, and through its externs
     * the foreign schemas' readers, the compiled schemas its registry shares.
     */
    @Test
    void aCompiledSchemaReadsAnEntryByItsKeyInTheSchemaHoldingIt() {
        JsonCompiledSchemaRegistry registry = JsonCompiledSchemaRegistry.bind(TSON.schemaRegistry(), BINDING);
        JsonCompiledSchema ship = registry.get(SHIP).orElseThrow();
        EntryKey order = new EntryKey(identity(ORDER_2), "order");

        assertSame(ship.get("envelope"), ship.reader(new EntryKey(identity(SHIP), "envelope")).orElseThrow());
        assertSame(registry.get(ORDER_2).orElseThrow().get("order"), ship.reader(order).orElseThrow());
        assertEquals(OrderTwo.class, ship.binding().orElseThrow().require(order).dataClass().typeClass());
        assertTrue(ship.reader(new EntryKey(identity(SHIP), "absent")).isEmpty());
    }

    @Test
    void aSubtypeNamedAtTheBoundaryBindsToItsOwnClass() {
        Home read = bind(HOME, "home", """
                {"owner": "o", "pet": {"$type": "dog_x", "name": "rex", "barks": true},
                 "pets": [{"$type": "cat_x", "$value": {"name": "tom", "purrs": false}}]}""", Home.class);
        assertEquals(new Home("o", new Dog("rex", true), List.of(new Cat("tom", false))), read);
    }

    @Test
    void aBoundForeignValueWritesBackAndReadsAgainAndTheOutputValidates() {
        Envelope value = bind(SHIP, "envelope", ENVELOPE, Envelope.class);
        String written = JSON.objectWriter().toJson(value);
        assertEquals(value, bind(SHIP, "envelope", written, Envelope.class));
        assertEquals(List.of(), JSON.validate(written, SHIP, "envelope"));
    }
}
