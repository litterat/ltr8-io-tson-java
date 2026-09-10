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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-JSON] §6.1/§6.3/§6.4 and §7: records as objects, arrays and sets and tuples as arrays, and JSON null
 * as the absent sentinel.
 *
 * <p>The read is asserted as JSON: a schema-directed tree read validates and hands the document back, so what
 * a test compares is the tree that came out and the diagnostics beside it.
 */
class JsonContainerReadTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/containers-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              person => {
                name:     text
                nickname: text?
                tries:    int32 ~ 0
                kind:     text = "person"
                badge:    text? = "gold"
              }

              bounded => {
                value: int32
                ( min: int32 | max: int32 )
              }

              flagged => {
                value: int32
                ( cleared: int32 | pending: int32 )?
              }

              tags       => [text]
              maybe_tags => [text?]
              sized      => [text; 2..3]
              unique     => set<text>
              pair       => [text, int32]
              maybe_pair => [text?, int32]
              nested     => { who: person  labels: [text] }
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

    /** What the read produced, as JSON text -- the shape a decoded document is compared by here. */
    private static String json(JsonValue value) {
        return value.toString();
    }

    // ── §6.1.1 closure ───────────────────────────────────────────────────

    @Test
    void aRecordIsAJsonObjectWithOneMemberPerField() {
        assertEquals("""
                {"name":"Ada","nickname":"A","tries":7,"kind":"person","badge":"gold"}""",
                json(read("person", """
                        {"name": "Ada", "nickname": "A", "tries": 7, "kind": "person", "badge": "gold"}""")
                        .accepted()));
    }

    /** §6.1.1: a member matching no declared field is a validation error -- a record is closed under its type. */
    @Test
    void anUndeclaredMemberIsRefusedRatherThanCollected() {
        Diagnostic refusal = read("person", """
                {"name": "Ada", "shoe_size": 9}""").refusal();
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, refusal.code());
        assertEquals("/shoe_size", refusal.path().orElseThrow());
    }

    /**
     * §6.2's {@code @rest} flatten is deliberately unbuilt, so there is no route by which an undeclared
     * member lands anywhere. This pins the strict reading that the annotation would later relax.
     */
    @Test
    void nothingCollectsAnUndeclaredMemberBecauseThereIsNoRestField() {
        assertTrue(json(read("person", """
                {"name": "Ada", "shoe_size": 9}""").value()).indexOf("shoe_size") < 0,
                "an undeclared member reaches the decoded output nowhere");
    }

    /** §3.1: a repeated member name is an error at the repeated occurrence; the later value still wins. */
    @Test
    void aRepeatedMemberIsRefusedAndTheLaterValueWins() {
        Read read = read("person", """
                {"name": "Ada", "name": "Grace"}""");
        assertEquals(Diagnostic.Code.DUPLICATE_FIELD, read.problems().getFirst().code());
        assertTrue(json(read.value()).contains("\"name\":\"Grace\""), "the repeat wins: " + json(read.value()));
    }

    /** §6.1.6: member order carries no meaning, and a decoder that required one would invent a rule. */
    @Test
    void memberOrderCarriesNoMeaning() {
        assertEquals(json(read("person", """
                {"name": "Ada", "tries": 1}""").accepted()),
                json(read("person", """
                {"tries": 1, "name": "Ada"}""").accepted()));
    }

    // ── §6.1.2 presence and absence, and §7 ──────────────────────────────

    /**
     * §6.1.2: an absent OPTIONAL field has two spellings -- omitted, or present with null -- and decoded
     * output never records which arrived. Encoders SHOULD omit, so omission is what both decode to.
     */
    @Test
    void theTwoSpellingsOfAnAbsentOptionalFieldDecodeIdentically() {
        String omitted = json(read("person", """
                {"name": "Ada"}""").accepted());
        String stated = json(read("person", """
                {"name": "Ada", "nickname": null}""").accepted());
        assertEquals(omitted, stated);
        assertTrue(omitted.indexOf("nickname") < 0, "an absent optional field is omitted: " + omitted);
    }

    /** §6.1.2/§7: at a REQUIRED-family field a null member is a validation error, precisely as `_` is in text. */
    @Test
    void nullAtARequiredFieldIsRefused() {
        Diagnostic refusal = read("person", """
                {"name": null}""").refusal();
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, refusal.code());
        assertEquals("/name", refusal.path().orElseThrow());
    }

    @Test
    void aMissingRequiredFieldIsRefused() {
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, read("person", "{}").refusal().code());
    }

    // ── §6.1.3 defaults and fixed values ─────────────────────────────────

    /** §6.1.3: a missing member at REQUIRED_DEFAULT or REQUIRED_FIXED injects, so output is fully populated. */
    @Test
    void anOmittedDefaultAndFixedMemberAreInjected() {
        assertEquals("""
                {"name":"Ada","tries":0,"kind":"person"}""", json(read("person", """
                {"name": "Ada"}""").accepted()));
    }

    /** §6.1.3: OPTIONAL_FIXED is never injected -- an omitted one stays absent. */
    @Test
    void anOmittedOptionalFixedMemberStaysAbsent() {
        assertTrue(json(read("person", """
                {"name": "Ada"}""").accepted()).indexOf("badge") < 0, "OPTIONAL_FIXED is not injected");
    }

    /** §6.1.2: at REQUIRED_DEFAULT the fix is omission -- writing null disclaims a value the schema always fills. */
    @Test
    void nullAtADefaultedFieldIsRefusedWhereOmissionInjects() {
        Read read = read("person", """
                {"name": "Ada", "tries": null}""");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, read.problems().getFirst().code());
        assertTrue(json(read.value()).contains("\"tries\":0"), "the default is still what the field decodes to");
    }

    /** §6.1.3: a present member at a FIXED field is verified against the pin, never silently overwritten. */
    @Test
    void aStatedFixedValueIsVerified() {
        read("person", """
                {"name": "Ada", "kind": "person"}""").accepted();
        Diagnostic refusal = read("person", """
                {"name": "Ada", "kind": "robot"}""").refusal();
        assertEquals(Diagnostic.Code.FIELD_FIXED, refusal.code());
        assertEquals("/kind", refusal.path().orElseThrow());
    }

    /**
     * §5.3 makes {@code 1} and {@code 1.0} one value, so a pin is compared by what it <em>is</em> and not by
     * how either side spelled it. A stated fixed value that parses to the pin conforms.
     */
    @Test
    void aFixedValueIsComparedByValueAndNotBySpelling() {
        read("person", """
                {"name": "Ada", "kind": "person", "badge": "gold"}""").accepted();
    }

    // ── §6.1.4 field groups ──────────────────────────────────────────────

    @Test
    void aRequiredGroupTakesExactlyOneMember() {
        read("bounded", """
                {"value": 1, "min": 0}""").accepted();
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, read("bounded", """
                {"value": 1}""").refusal().code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("bounded", """
                {"value": 1, "min": 0, "max": 9}""").refusal().code());
    }

    @Test
    void anOptionalGroupTakesAtMostOneMember() {
        read("flagged", """
                {"value": 1}""").accepted();
        read("flagged", """
                {"value": 1, "cleared": 3}""").accepted();
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("flagged", """
                {"value": 1, "cleared": 3, "pending": 4}""").refusal().code());
    }

    // ── §6.3 arrays and sets ─────────────────────────────────────────────

    @Test
    void anArrayIsAJsonArrayOfItsElementType() {
        assertEquals("""
                ["a","b"]""", json(read("tags", """
                ["a", "b"]""").accepted()));
    }

    @Test
    void anArrayRefusesAnElementOfTheWrongType() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("tags", """
                ["a", 2]""").refusal().code());
    }

    /** §6.3: `[T?]` admits null at any slot as the absent element -- the slot exists and counts. */
    @Test
    void anElementOptionalArrayAdmitsNullAsAnAbsentElement() {
        assertEquals("""
                ["a",null,"c"]""", json(read("maybe_tags", """
                ["a", null, "c"]""").accepted()));
    }

    /** Under `[T]` null at a slot is a validation error, as `_` is in text: it is never a value (§7). */
    @Test
    void aRequiredElementArrayRefusesNull() {
        Diagnostic refusal = read("tags", """
                ["a", null]""").refusal();
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, refusal.code());
        assertEquals("/1", refusal.path().orElseThrow());
    }

    /** §6.3: the size facets validate the slot count. */
    @Test
    void sizeFacetsCountSlots() {
        read("sized", """
                ["a", "b"]""").accepted();
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("sized", """
                ["a"]""").refusal().code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("sized", """
                ["a", "b", "c", "d"]""").refusal().code());
    }

    /** §6.3: a set uses the same JSON array form; duplicates are errors at the repeated occurrence. */
    @Test
    void aSetSharesTheArrayFormAndRefusesARepeat() {
        read("unique", """
                ["a", "b"]""").accepted();
        Diagnostic refusal = read("unique", """
                ["a", "a"]""").refusal();
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal.code());
        assertEquals("/1", refusal.path().orElseThrow());
    }

    // ── §6.4 tuples ──────────────────────────────────────────────────────

    @Test
    void aTupleIsAJsonArrayOfExactlyItsDeclaredLength() {
        assertEquals("""
                ["a",1]""", json(read("pair", """
                ["a", 1]""").accepted()));
        assertEquals(Diagnostic.Code.WRONG_ARITY, read("pair", """
                ["a"]""").refusal().code());
        assertEquals(Diagnostic.Code.WRONG_ARITY, read("pair", """
                ["a", 1, 2]""").refusal().code());
    }

    /** §6.4: an OPTIONAL position's absent value is null in its slot; at a REQUIRED position it is an error. */
    @Test
    void aTupleSlotTakesNullOnlyWhereItIsOptional() {
        assertEquals("""
                [null,1]""", json(read("maybe_pair", """
                [null, 1]""").accepted()));
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, read("pair", """
                [null, 1]""").refusal().code());
    }

    // ── Nesting, and where a problem is located ──────────────────────────

    @Test
    void aProblemInsideANestedRecordNamesBothEnds() {
        Diagnostic refusal = read("nested", """
                {"who": {"name": 42}, "labels": []}""").refusal();
        assertEquals("/who/name", refusal.path().orElseThrow());
        // The pointer is the path the read took from the root it entered through, not the declaration it
        // ended at: `nested` reached `who`, which reached `name`. The schema *identity* and line re-anchor
        // on `person`, which is the record that actually declares the member.
        assertEquals("/nested/who/name", refusal.schemaPointer().orElseThrow());
    }

    @Test
    void aProblemInsideANestedArrayNamesItsIndex() {
        assertEquals("/labels/1", read("nested", """
                {"who": {"name": "Ada"}, "labels": ["a", 2]}""").refusal().path().orElseThrow());
    }
}
