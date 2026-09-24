package io.ltr8.tson.json;

import io.ltr8.annotation.Tuple;
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
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arrays and sets read against their schema into bound classes: the element loop is tree mode's ({@code
 * ArrayReader}), and a component's declared collection or array class is what the elements are built into, each
 * element bound to the component's element class.
 */
class JsonBindContainerReadTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/bind-containers-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              address => { street: text  city: text }
              names   => [text]
              bag     => {
                tags:      [text]
                scores:    [int32]
                counts:    [int32]
                maybe:     [text?]
                ids:       set<uuid>
                addresses: [address]
              }
              optional_ints => { values: [int32?] }
              pair          => [text, int32]
              holder        => { pair: [text, int32]  loose: [text, int32?] }
              point         => { x: int32  y: int32 }
              scores        => {text => int32}
              weights       => {
                byDate:   {date => number}
                counts:   {text => int32}
                maybe:    {text => text?}
                byPoint:  {point => text}
              }
            }
            """;

    public record Address(String street, String city) {
    }

    public record Bag(List<String> tags, long[] scores, List<Long> counts, List<String> maybe, Set<UUID> ids,
                      List<Address> addresses) {
    }

    public record OptionalInts(int[] values) {
    }

    @Tuple
    public record Pair(String label, long count) {
    }

    /** An optional position binds to a boxed element, which can hold the absence. */
    @Tuple
    public record Loose(String label, Integer count) {
    }

    public record Holder(Pair pair, Loose loose) {
    }

    private static final TsonLinkedSchema LINKED = Tson.standard().resolve(SCHEMA);

    public record Point(int x, int y) {
    }

    public record Weights(Map<LocalDate, BigDecimal> byDate, Map<String, Long> counts, Map<String, String> maybe,
                          Map<Point, String> byPoint) {
    }

    private static final Map<String, Class<?>> BINDINGS = Map.of("address", Address.class, "bag", Bag.class,
            "holder", Holder.class, "point", Point.class, "weights", Weights.class);

    private static JsonCompiledSchema compile(Map<String, Class<?>> bindings) {
        DataBindContext binding = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bindings))
                .registerAtoms(AtomContext.hostTypes()).build();
        return JsonSchemaCompiler.compile(LINKED, ValueReaderFactoryRegistry.bind(binding));
    }

    private static final JsonCompiledSchema COMPILED = compile(BINDINGS);

    private record Read(Object value, List<Diagnostic> problems) {
    }

    private static Read read(String type, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            return new Read(COMPILED.get(type).read(ctx), List.copyOf(problems));
        }
    }

    private static final String ID = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";

    private static final String BAG = """
            {"tags": ["a", "b"], "scores": [1, 2147483647], "counts": [3, 4], "maybe": ["x", null],
             "ids": ["%s"], "addresses": [{"street": "1 Way", "city": "London"}]}""".formatted(ID);

    @Test
    void eachComponentReceivesTheCollectionOrArrayItDeclares() {
        Read read = read("bag", BAG);
        assertEquals(List.of(), read.problems());
        Bag bag = (Bag) read.value();
        assertEquals(List.of("a", "b"), bag.tags());
        assertArrayEquals(new long[] {1, 2147483647L}, bag.scores());
        assertEquals(Arrays.asList("x", null), bag.maybe());
        assertEquals(Set.of(UUID.fromString(ID)), bag.ids());
        assertEquals(List.of(new Address("1 Way", "London")), bag.addresses());
    }

    /** The element is bound to the component's element class: a {@code List<Long>} holds {@code Long}s. */
    @Test
    void anElementIsReadIntoTheClassItsCollectionHolds() {
        Bag bag = (Bag) read("bag", BAG).value();
        assertEquals(List.of(3L, 4L), bag.counts());
        assertEquals(Long.class, bag.counts().getFirst().getClass());
    }

    @Test
    void anArrayNothingMoreSpecificDeclaresBindsToAList() {
        Read read = read("names", """
                ["a", "b"]""");
        assertEquals(List.of(), read.problems());
        assertEquals(List.of("a", "b"), read.value());
    }

    /**
     * A set's duplicates are judged on the element's value: two spellings of one UUID are one element. Bind mode
     * compares the host values its elements decode to.
     */
    @Test
    void aSetRefusesTwoSpellingsOfOneValue() {
        Read read = read("bag", BAG.replace("\"ids\": [\"" + ID + "\"]",
                "\"ids\": [\"" + ID + "\", \"" + ID.toUpperCase() + "\"]"));
        assertNull(read.value());
        assertTrue(read.problems().getFirst().message().contains("more than once"),
                read.problems().getFirst().message());
    }

    /** All-or-nothing: an element that fails leaves the whole array, and the record holding it, unbuilt. */
    @Test
    void aRefusedElementLeavesTheArrayAndItsRecordUnbuilt() {
        Read read = read("bag", BAG.replace("[1, 2147483647]", "[1, \"two\"]"));
        assertNull(read.value());
        assertEquals("/scores/1", read.problems().getFirst().path().orElseThrow());
    }

    // ── The collection against the schema, checked when the reader is built ──

    public record TagsAsText(String tags, long[] scores, List<Long> counts, List<String> maybe, Set<UUID> ids,
                             List<Address> addresses) {
    }

    public record TagsAsInts(List<Integer> tags, long[] scores, List<Long> counts, List<String> maybe,
                             Set<UUID> ids, List<Address> addresses) {
    }

    @Test
    void anArrayBoundToAnAtomComponentFailsTheCompile() {
        Map<String, Class<?>> bindings = new HashMap<>(BINDINGS);
        bindings.put("bag", TagsAsText.class);
        BindMismatchException e = assertThrows(BindMismatchException.class, () -> compile(bindings));
        assertTrue(e.getMessage().contains("field 'tags' is an array"), e.getMessage());
    }

    @Test
    void anElementTheFamilyCannotProduceFailsTheCompile() {
        Map<String, Class<?>> bindings = new HashMap<>(BINDINGS);
        bindings.put("bag", TagsAsInts.class);
        BindMismatchException e = assertThrows(BindMismatchException.class, () -> compile(bindings));
        assertTrue(e.getMessage().contains("field 'tags''s element cannot produce"), e.getMessage());
    }

    /** {@code [int32?]} admits an absent element, which an {@code int[]} has nowhere to hold. */
    @Test
    void optionalElementsCannotReachAPrimitiveArray() {
        Map<String, Class<?>> bindings = new HashMap<>(BINDINGS);
        bindings.put("optional_ints", OptionalInts.class);
        BindMismatchException e = assertThrows(BindMismatchException.class, () -> compile(bindings));
        assertTrue(e.getMessage().contains("admits absent elements"), e.getMessage());
    }

    // ── Tuples ───────────────────────────────────────────────────────────

    /**
     * An inline tuple has a minted entry name no binding map can hold, so it binds by the component that holds it:
     * an {@code @Tuple} class, each position bound to its element -- the {@code int32} into a {@code long}.
     */
    @Test
    void aTupleBindsToTheTupleClassItsComponentDeclares() {
        Read read = read("holder", """
                {"pair": ["a", 3], "loose": ["b", null]}""");
        assertEquals(List.of(), read.problems());
        Holder holder = (Holder) read.value();
        assertEquals(new Pair("a", 3L), holder.pair());
        assertEquals(new Loose("b", null), holder.loose());
    }

    @Test
    void aTupleNothingMoreSpecificDeclaresBindsToAList() {
        assertEquals(List.of("a", 3), read("pair", """
                ["a", 3]""").value());
    }

    @Test
    void aTupleOfTheWrongLengthBindsToNothing() {
        Read read = read("holder", """
                {"pair": ["a"], "loose": ["b", 1]}""");
        assertNull(read.value());
        assertEquals("/pair", read.problems().getFirst().path().orElseThrow());
    }

    @Tuple
    public record Triple(String label, long count, String extra) {
    }

    public record HolderOfTriple(Triple pair, Loose loose) {
    }

    public record HolderOfText(String pair, Loose loose) {
    }

    @Test
    void aTupleClassOfAnotherArityFailsTheCompile() {
        Map<String, Class<?>> bindings = new HashMap<>(BINDINGS);
        bindings.put("holder", HolderOfTriple.class);
        BindMismatchException e = assertThrows(BindMismatchException.class, () -> compile(bindings));
        assertTrue(e.getMessage().contains("has 2 positions, and Triple has 3"), e.getMessage());
    }

    @Test
    void aTupleBoundToAnAtomComponentFailsTheCompile() {
        Map<String, Class<?>> bindings = new HashMap<>(BINDINGS);
        bindings.put("holder", HolderOfText.class);
        BindMismatchException e = assertThrows(BindMismatchException.class, () -> compile(bindings));
        assertTrue(e.getMessage().contains("field 'pair' is a tuple"), e.getMessage());
    }

    // ── Maps ─────────────────────────────────────────────────────────────

    private static final String WEIGHTS = """
            {"byDate": {"2026-07-01": 12.5}, "counts": {"a": 1}, "maybe": {"x": null},
             "byPoint": [[{"x": 1, "y": 2}, "here"]]}""";

    /** Both forms, each key and value bound to what the component's map declares. */
    @Test
    void aMapBindsItsKeysAndValuesToTheClassesItsComponentDeclares() {
        Read read = read("weights", WEIGHTS);
        assertEquals(List.of(), read.problems());
        Weights weights = (Weights) read.value();
        assertEquals(Map.of(LocalDate.of(2026, 7, 1), new BigDecimal("12.5")), weights.byDate());
        assertEquals(Map.of("a", 1L), weights.counts());
        assertEquals(Long.class, weights.counts().get("a").getClass());
        assertTrue(weights.maybe().containsKey("x"));
        assertNull(weights.maybe().get("x"));
        assertEquals(Map.of(new Point(1, 2), "here"), weights.byPoint());
    }

    @Test
    void aMapNothingMoreSpecificDeclaresBindsToAMap() {
        assertEquals(Map.of("a", 1, "b", 2), read("scores", """
                {"a": 1, "b": 2}""").value());
    }

    /** A repeated key is judged on the key's value, and a map that repeats one builds nothing. */
    @Test
    void aRepeatedKeyLeavesTheMapUnbuilt() {
        Read read = read("weights", WEIGHTS.replace("{\"a\": 1}", "{\"a\": 1, \"a\": 2}"));
        assertNull(read.value());
        assertEquals(Diagnostic.Code.DUPLICATE_MAP_KEY, read.problems().getFirst().code());
    }

    public record WeightsAsList(List<String> byDate, Map<String, Long> counts, Map<String, String> maybe,
                                Map<Point, String> byPoint) {
    }

    public record WeightsWithIntKeys(Map<LocalDate, BigDecimal> byDate, Map<Integer, Long> counts,
                                     Map<String, String> maybe, Map<Point, String> byPoint) {
    }

    @Test
    void aMapBoundToAListComponentFailsTheCompile() {
        Map<String, Class<?>> bindings = new HashMap<>(BINDINGS);
        bindings.put("weights", WeightsAsList.class);
        BindMismatchException e = assertThrows(BindMismatchException.class, () -> compile(bindings));
        assertTrue(e.getMessage().contains("field 'byDate' is a map"), e.getMessage());
    }

    @Test
    void aKeyTheFamilyCannotProduceFailsTheCompile() {
        Map<String, Class<?>> bindings = new HashMap<>(BINDINGS);
        bindings.put("weights", WeightsWithIntKeys.class);
        BindMismatchException e = assertThrows(BindMismatchException.class, () -> compile(bindings));
        assertTrue(e.getMessage().contains("field 'counts''s key cannot produce"), e.getMessage());
    }
}
