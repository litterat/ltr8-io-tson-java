package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [TSON-JSON] §6.5: a map in one of its two JSON forms, the form selected by the key type and never by
 * inspecting the value.
 */
class JsonMapReadTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/maps-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              counts    => { text => int32 }
              by_date   => { date => number }
              by_number => { number => text }
              optional  => { text => int32? }
              sized     => !map { key_type: text  value_type: int32  min_items: 1  max_items: 2 }
              point     => { x: int32  y: int32 }
              by_point  => { point => text }
              pair      => [text, int32]
              by_pair   => { pair => text }
            }
            """;

    private static final JsonCompiledSchema COMPILED = JsonSchemaCompiler.compile(Tson.standard().resolve(SCHEMA));

    private record Read(JsonValue value, List<Diagnostic> problems) {

        JsonValue accepted() {
            assertEquals(List.of(), problems.stream().map(Diagnostic::message).toList(), "expected a clean read");
            return value;
        }

        Diagnostic refusal() {
            assertEquals(1, problems.size(), () -> "expected exactly one problem, got " + problems);
            return problems.getFirst();
        }
    }

    private static Read read(String typeName, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = COMPILED.rootDeclaration(typeName).map(ctx::underDeclaration).orElse(ctx);
            return new Read((JsonValue) COMPILED.get(typeName).read(ctx), List.copyOf(problems));
        }
    }

    // ── Object form ──────────────────────────────────────────────────────

    @Test
    void aScalarKeyedMapIsAJsonObject() {
        assertEquals("""
                {"a":1,"b":2}""", read("counts", """
                {"a": 1, "b": 2}""").accepted().toString());
    }

    /** §6.5: the member name's string content faces `K`'s contract exactly as §5.1 hands value strings. */
    @Test
    void aMemberNameFacesTheKeyTypesOwnContract() {
        read("by_date", """
                {"2026-07-01": 12.5}""").accepted();
        Diagnostic refusal = read("by_date", """
                {"not-a-date": 12.5}""").refusal();
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, refusal.code());
        assertEquals("/not-a-date", refusal.path().orElseThrow());
    }

    /** §3.2: nothing is reserved at a map position -- keys are data, not names. */
    @Test
    void aDollarInitialMemberIsAnOrdinaryKey() {
        assertEquals("""
                {"$schema":1}""", read("counts", """
                {"$schema": 1}""").accepted().toString());
    }

    /**
     * §6.5: identity under a declared key type is over its value space, so {@code "1"} and {@code "1.0"}
     * under a {@code number} key are one key -- and the repeat is the duplicate error.
     */
    @Test
    void keysCompareAsValuesAndNotAsSpellings() {
        Diagnostic refusal = read("by_number", """
                {"1": "a", "1.0": "b"}""").refusal();
        assertEquals(Diagnostic.Code.DUPLICATE_MAP_KEY, refusal.code());
        assertEquals("/1.0", refusal.path().orElseThrow());
    }

    @Test
    void aRepeatedKeyKeepsOneEntryAndTheLaterValue() {
        Read read = read("by_number", """
                {"1": "a", "1.0": "b"}""");
        assertEquals("""
                {"1":"b"}""", read.value().toString(), "one entry, under the first spelling, with the later value");
    }

    /** §6.5: `{K => V?}` admits null as an entry's absent value; under `{K => V}` it is a validation error. */
    @Test
    void anEntryValueIsAbsentOnlyWhereTheMapAdmitsOne() {
        assertEquals("""
                {"a":null}""", read("optional", """
                {"a": null}""").accepted().toString());
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, read("counts", """
                {"a": null}""").refusal().code());
    }

    /** §6.5: size facets count entries -- and an entry with an absent value is an entry. */
    @Test
    void sizeFacetsCountEntries() {
        read("sized", """
                {"a": 1}""").accepted();
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("sized", "{}").refusal().code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("sized", """
                {"a": 1, "b": 2, "c": 3}""").refusal().code());
    }

    /** [TSON-DATA] §2.8: an empty object is the empty container of the position's own type. */
    @Test
    void anEmptyObjectIsAMapOfNoEntries() {
        assertEquals("{}", read("counts", "{}").accepted().toString());
    }

    @Test
    void anEntryValueOfTheWrongTypeIsRefusedAtItsOwnKey() {
        assertEquals("/a", read("counts", """
                {"a": "x"}""").refusal().path().orElseThrow());
    }

    // ── Pairs form ───────────────────────────────────────────────────────

    /** §6.5: a compound key forces the pairs form -- a JSON array of two-element arrays. */
    @Test
    void aCompoundKeyedMapIsAJsonArrayOfPairs() {
        assertEquals("""
                [[{"x":1,"y":2},"origin-ish"]]""", read("by_point", """
                [[{"x": 1, "y": 2}, "origin-ish"]]""").accepted().toString());
    }

    @Test
    void aTupleKeyForcesThePairsFormToo() {
        assertEquals("""
                [[["a",1],"v"]]""", read("by_pair", """
                [[["a", 1], "v"]]""").accepted().toString());
    }

    /** §6.5: a pairs-form element that is not a two-element array is a validation error. */
    @Test
    void aPairsFormElementMustBeATwoElementArray() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("by_point", """
                [{"x": 1, "y": 2}]""").refusal().code());
        assertEquals(Diagnostic.Code.WRONG_ARITY, read("by_point", """
                [[{"x": 1, "y": 2}]]""").refusal().code());
        assertEquals(Diagnostic.Code.WRONG_ARITY, read("by_point", """
                [[{"x": 1, "y": 2}, "a", "b"]]""").refusal().code());
    }

    /** The object form is not admitted where the key type forces pairs: the schema selects, not the value. */
    @Test
    void theFormIsTheSchemasChoiceAndNotTheDocumentsOffer() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("by_point", """
                {"a": "b"}""").refusal().code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("counts", """
                [["a", 1]]""").refusal().code());
    }

    /**
     * A compound key compares as a value too: member order carries no meaning (§6.1.6) and a number keeps
     * no scale (§5.3), so these two keys are one key.
     */
    @Test
    void compoundKeysCompareAsValues() {
        assertEquals(Diagnostic.Code.DUPLICATE_MAP_KEY, read("by_point", """
                [[{"x": 1, "y": 2}, "a"], [{"y": 2, "x": 1}, "b"]]""").refusal().code());
    }

    @Test
    void aPairsFormEntryValueFacesItsOwnType() {
        assertEquals("/0/1", read("by_point", """
                [[{"x": 1, "y": 2}, 9]]""").refusal().path().orElseThrow());
    }
}
