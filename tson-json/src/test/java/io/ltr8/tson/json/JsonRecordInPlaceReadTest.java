package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record written in place read through the JSON encoding ([TSON-SCHEMA] §5.10): in tree mode by a reader built from
 * the record's body, with the open record's field states, admitting no {@code $type} (§7.2), and in bind mode into the
 * record class the component holding it declares, no binding map being able to name it (§8.2).
 */
class JsonRecordInPlaceReadTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/json-records-in-place.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              order      => { id: text }
              page       => <T> { items: [T]  size?: int32 ~ 10 }
              order_page => page<order>
              holder => {
                p:       page<order>
                ps?:     [page<order>]
                byName?: {text => page<order>}
              }
            }
            """;

    public record Order(String id) {
    }

    public record Page(List<Order> items, int size) {
    }

    public record Holder(Page p, List<Page> ps, Map<String, Page> byName) {
    }

    public record Flat(String p, List<String> ps, Map<String, String> byName) {
    }

    private static final TsonLinkedSchema LINKED = Tson.standard().resolve(SCHEMA);

    private static JsonCompiledSchema bound(Map<String, Class<?>> bindings) {
        DataBindContext binding = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bindings))
                .registerAtoms(AtomContext.hostTypes()).build();
        return JsonSchemaCompiler.compile(LINKED, ValueReaderFactoryRegistry.bind(binding));
    }

    private record Read(Object value, List<Diagnostic> problems) {
    }

    private static Read read(JsonCompiledSchema compiled, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            return new Read(compiled.get("holder").read(ctx), List.copyOf(problems));
        }
    }

    private static Read tree(String json) {
        return read(JsonSchemaCompiler.compile(LINKED), json);
    }

    @Test
    void aValueReadsInTreeModeWithItsDefault() {
        Read read = tree("{\"p\": {\"items\": [{\"id\": \"a\"}]}}");
        assertEquals(List.of(), read.problems());
        JsonObject p = assertInstanceOf(JsonObject.class, ((JsonObject) read.value()).get("p"));
        assertEquals(10, p.get("size").asInt(), "the default supplied");
    }

    @Test
    void aMissingFieldAndAnUnknownOneAreLocatedByTheirPath() {
        assertEquals(Optional.of("/p/items"), only(tree("{\"p\": {\"size\": 3}}")).path());
        assertEquals(Optional.of("/ps/0/colour"),
                only(tree("{\"p\": {\"items\": []}, \"ps\": [{\"items\": [], \"colour\": \"red\"}]}")).path());
    }

    /**
     * A {@code $type} at a record in place is refused, whatever it names: a declared record of the same shape, or the
     * application's own text, which is no type's name (§7.2).
     */
    @Test
    void aTypeMemberAtARecordInPlaceIsRefused() {
        for (String tag : List.of("order_page", "page<order>")) {
            Diagnostic refused = only(tree("{\"p\": {\"$type\": \"" + tag + "\", \"items\": []}}"));
            assertEquals(Optional.of("/p"), refused.path(), refused::toString);
        }
    }

    @Test
    void aValueBindsToTheComponentsRecordClass() {
        Read read = read(bound(Map.of("order", Order.class, "holder", Holder.class)), """
                {"p": {"items": [{"id": "a"}]}, "ps": [{"items": [], "size": 2}], "byName": {"k": {"items": []}}}""");
        assertEquals(List.of(), read.problems());
        assertEquals(new Holder(new Page(List.of(new Order("a")), 10), List.of(new Page(List.of(), 2)),
                Map.of("k", new Page(List.of(), 10))), read.value());
    }

    @Test
    void aRefusedValueBindsNothing() {
        Read read = read(bound(Map.of("order", Order.class, "holder", Holder.class)), "{\"p\": {\"size\": 3}}");
        assertNull(read.value());
        assertEquals(Optional.of("/p/items"), only(read.problems()).path());
    }

    @Test
    void aComponentThatIsNoRecordClassIsAMismatch() {
        BindMismatchException mismatch = assertThrows(BindMismatchException.class,
                () -> bound(Map.of("order", Order.class, "holder", Flat.class)));
        assertTrue(mismatch.getMessage().contains("is a record written in place"), mismatch.getMessage());
    }

    // ── A record body written at a type slot (§5.2) ─────────────────────────────────────────────

    private static final TsonLinkedSchema WRITTEN = Tson.standard().resolve("""
            !!id:"https://example.test/json-records-written-in-place.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              shipment => {
                at:      { x: int32  y: int32 }
                lines?:  [{ sku: text  qty: int32 }]
                byName?: {text => { n: int32 }}
                by?:     ( text | { name: text  email: text } )
              }
            }
            """);

    private static Read shipment(String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            return new Read(JsonSchemaCompiler.compile(WRITTEN).get("shipment").read(ctx), List.copyOf(problems));
        }
    }

    /** A record written as sugar at a field, an element, a map's value and a choice's variant reads untagged. */
    @Test
    void aRecordWrittenAtEachSlotReads() {
        Read read = shipment("""
                {"at": {"x": 1, "y": 2}, "lines": [{"sku": "a", "qty": 1}], "byName": {"k": {"n": 3}},
                 "by": {"name": "n", "email": "e"}}""");
        assertEquals(List.of(), read.problems());
        JsonObject at = assertInstanceOf(JsonObject.class, ((JsonObject) read.value()).get("at"));
        assertEquals(2, at.get("y").asInt());
        assertEquals(List.of(), shipment("{\"at\": {\"x\": 1, \"y\": 2}, \"by\": \"b\"}").problems());
    }

    @Test
    void aRefusalInsideARecordWrittenInPlaceIsLocatedByItsPath() {
        assertEquals(Optional.of("/at/x"), only(shipment("{\"at\": {\"x\": \"one\", \"y\": 2}}")).path());
        assertEquals(Optional.of("/lines/0/qty"),
                only(shipment("{\"at\": {\"x\": 1, \"y\": 2}, \"lines\": [{\"sku\": \"a\"}]}")).path());
        assertEquals(Optional.of("/byName/k/n"),
                only(shipment("{\"at\": {\"x\": 1, \"y\": 2}, \"byName\": {\"k\": {\"n\": \"z\"}}}")).path());
    }

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    private static Diagnostic only(Read read) {
        return only(read.problems());
    }
}
