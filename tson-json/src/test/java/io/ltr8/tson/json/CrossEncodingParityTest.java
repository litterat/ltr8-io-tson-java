package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.json.stream.JsonStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * One schema, one document in two encodings, one verdict.
 *
 * <p><b>This is a drift guard, and it exists because the schema-directed readers are two implementations.</b>
 * {@code tson-json} has its own compiled reader stack rather than sharing {@code tson-compiler}'s -- see
 * {@code docs/json-encoding.md} for why that is the right trade -- and the cost of the trade is that the
 * field-state rules ([TSON-SCHEMA] §5.2's six states, REQUIRED_FIXED injection, the FIXED check, closure,
 * duplicate members) are written twice and can drift apart silently.
 *
 * <p>[TSON-JSON] §9.4 makes that a <b>specification obligation</b> rather than a tidiness: this encoding
 * reports in [TSON-DATA] §8.1's four categories and adds none of its own, so one document that is wrong in
 * one encoding is wrong in the other, for the same stated reason, at the same place. What is compared is
 * therefore the {@link Diagnostic.Code} and the RFC 6901 pointer into the data -- the two components a
 * consumer routes on -- and never the message, which is each reader's own prose.
 *
 * <p>What is deliberately <em>not</em> asserted is the schema pointer. Both ends name the path the read took,
 * but the two front doors enter through different roots (a TSON document names its own root type; a JSON one
 * is bound out of band), so the pointers legitimately differ in their first step.
 */
class CrossEncodingParityTest {

    private static final String ID = "https://example.test/parity-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/parity-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              person => {
                name:   text
                tries:  int32 ~ 0
                kind:   text = "person"
                labels: [text]
              }
              sized  => [text; 2..3]
              unique => set<text>
              pair   => [text, int32]
              bounded => {
                value: int32
                ( min: int32 | max: int32 )
              }
              counts    => { text => int32 }
              by_number => { number => text }
              by_date   => { date => number }
              point     => { x: int32  y: int32 }
              by_point  => { point => text }
            }
            """;

    private static final Tson TSON = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA)));

    private static final JsonCompiledSchema COMPILED = JsonSchemaCompiler.compile(TSON.resolve(SCHEMA));

    /** A diagnostic reduced to what a consumer routes on: which rule fired, and where in the data. */
    private record Verdict(Diagnostic.Code code, String path) {

        static List<Verdict> of(List<Diagnostic> problems) {
            return problems.stream().map(d -> new Verdict(d.code(), d.path().orElse("?"))).toList();
        }
    }

    private static List<Verdict> tson(String rootType, String body) {
        return Verdict.of(TSON.validate("!!schema:\"%s\"\n!%s %s".formatted(ID, rootType, body)));
    }

    private static List<Verdict> json(String rootType, String body) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(body)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = COMPILED.rootDeclaration(rootType).map(ctx::underDeclaration).orElse(ctx);
            COMPILED.get(rootType).read(ctx);
        }
        return Verdict.of(problems);
    }

    /**
     * Asserts the two encodings agree, and that they agreed on <em>something</em> -- an assertion that both
     * sides reported nothing would pass for a reader that had not run at all, which is how a parity guard
     * comes to hold vacuously.
     */
    private static void sameVerdict(String rootType, String tsonBody, String jsonBody) {
        List<Verdict> fromTson = tson(rootType, tsonBody);
        assertFalse(fromTson.isEmpty(), "the TSON side reported nothing, so this compares nothing");
        assertEquals(fromTson, json(rootType, jsonBody), "the two encodings disagree about this document");
    }

    /**
     * As {@link #sameVerdict}, comparing the codes alone.
     *
     * <p>For a case where the two documents genuinely have different <em>shapes</em>, the data pointer is not
     * a fact the two can agree on: §6.5's pairs form makes a compound-keyed map a JSON array of pairs, so a
     * pointer into it names an entry index where the TSON map has a key. What still must agree is which rule
     * fired.
     */
    private static void sameCodes(String rootType, String tsonBody, String jsonBody) {
        List<Diagnostic.Code> fromTson = tson(rootType, tsonBody).stream().map(Verdict::code).toList();
        assertFalse(fromTson.isEmpty(), "the TSON side reported nothing, so this compares nothing");
        assertEquals(fromTson, json(rootType, jsonBody).stream().map(Verdict::code).toList(),
                "the two encodings disagree about this document");
    }

    private static void bothAccept(String rootType, String tsonBody, String jsonBody) {
        assertEquals(List.of(), tson(rootType, tsonBody));
        assertEquals(List.of(), json(rootType, jsonBody));
    }

    @Test
    void aConformingDocumentIsAcceptedByBoth() {
        bothAccept("person", """
                { name: "Ada"  tries: 1  kind: "person"  labels: [ "x" ] }""", """
                {"name": "Ada", "tries": 1, "kind": "person", "labels": ["x"]}""");
    }

    @Test
    void aCompositeWhereAScalarIsDue() {
        sameVerdict("person", """
                { name: { a: 1 }  labels: [] }""", """
                {"name": {"a": 1}, "labels": []}""");
    }

    /**
     * <b>The one place the two encodings legitimately differ, asserted as a difference so it cannot drift
     * into one by accident.</b> JSON has six value <em>kinds</em> and TSON text has tokens: {@code 42} at a
     * {@code text} position is the unquoted token {@code 42}, whose content {@code text}'s contract accepts
     * ([TSON-DATA] §5.2), while JSON's {@code 42} is of the number kind and §5.6 admits only strings at
     * {@code text}. Neither reader is wrong and neither is the other's bug.
     *
     * <p>That difference is confined to <em>which kinds reach a family's parser</em>, which is each
     * encoding's own by §5.1. It is not a difference about the parser, the acceptance set, or the constraint
     * vocabulary -- those are one implementation ({@code tson-atom}) and cannot drift. So the parity this
     * class guards is over the rules that <em>are</em> written twice: closure, duplicates, the field states,
     * absence, group multiplicity, and container size and arity.
     */
    @Test
    void anUnquotedTokenAndAJsonNumberAreNotTheSameThingAtATextField() {
        assertEquals(List.of(), tson("person", """
                { name: 42  labels: [] }"""), "an unquoted token is text's content in TSON");
        assertEquals(List.of(new Verdict(Diagnostic.Code.TYPE_MISMATCH, "/name")), json("person", """
                {"name": 42, "labels": []}"""), "a JSON number is not of the string kind");
    }

    @Test
    void aMissingRequiredField() {
        sameVerdict("person", """
                { labels: [] }""", """
                {"labels": []}""");
    }

    @Test
    void aFieldTheTypeDoesNotDeclare() {
        sameVerdict("person", """
                { name: "Ada"  labels: []  shoe_size: 9 }""", """
                {"name": "Ada", "labels": [], "shoe_size": 9}""");
    }

    @Test
    void aFieldStatedTwice() {
        sameVerdict("person", """
                { name: "Ada"  name: "Grace"  labels: [] }""", """
                {"name": "Ada", "name": "Grace", "labels": []}""");
    }

    @Test
    void aContradictedFixedValue() {
        sameVerdict("person", """
                { name: "Ada"  kind: "robot"  labels: [] }""", """
                {"name": "Ada", "kind": "robot", "labels": []}""");
    }

    /** The absent sentinel at a field that admits none: `_` in text, null in JSON, one verdict. */
    @Test
    void theAbsentSentinelAtARequiredField() {
        sameVerdict("person", """
                { name: _  labels: [] }""", """
                {"name": null, "labels": []}""");
    }

    /** §6.1.2: at REQUIRED_DEFAULT the fix is omission, and stating absence is refused in both encodings. */
    @Test
    void theAbsentSentinelAtADefaultedField() {
        sameVerdict("person", """
                { name: "Ada"  tries: _  labels: [] }""", """
                {"name": "Ada", "tries": null, "labels": []}""");
    }

    @Test
    void aCompositeElementWhereAScalarIsDue() {
        sameVerdict("person", """
                { name: "Ada"  labels: [ "x", [ 2 ] ] }""", """
                {"name": "Ada", "labels": ["x", [2]]}""");
    }

    /** An absent element where the array admits none: `_` in text, null in JSON, one verdict at one index. */
    @Test
    void anAbsentElementInARequiredElementArray() {
        sameVerdict("person", """
                { name: "Ada"  labels: [ "x", _ ] }""", """
                {"name": "Ada", "labels": ["x", null]}""");
    }

    @Test
    void anArrayOutsideItsSizeBounds() {
        sameVerdict("sized", """
                [ "a" ]""", """
                ["a"]""");
    }

    @Test
    void aRepeatedSetElement() {
        sameVerdict("unique", """
                [ "a", "a" ]""", """
                ["a", "a"]""");
    }

    @Test
    void aTupleOfTheWrongLength() {
        sameVerdict("pair", """
                [ "a" ]""", """
                ["a"]""");
    }

    @Test
    void aRequiredGroupWithNoMemberPresent() {
        sameVerdict("bounded", """
                { value: 1 }""", """
                {"value": 1}""");
    }

    // ── §6.5 maps ────────────────────────────────────────────────────────

    @Test
    void aMapEntryValueOfTheWrongShape() {
        sameVerdict("counts", """
                { "a" => { b: 1 } }""", """
                {"a": {"b": 1}}""");
    }

    /** An entry value absent where the map admits none: `_` in text, null in JSON, one verdict at one key. */
    @Test
    void anAbsentEntryValueWhereValuesAreRequired() {
        sameVerdict("counts", """
                { "a" => _ }""", """
                {"a": null}""");
    }

    /** A member name the key type's own contract rejects -- §5.1's boundary applied to a key token. */
    @Test
    void aKeyTheContractRejects() {
        sameVerdict("by_date", """
                { "not-a-date" => 12.5 }""", """
                {"not-a-date": 12.5}""");
    }

    // §6.5 says `1` and `1.0` under a `number` key are one key, and the two encodings disagree about it:
    // `tson-compiler`'s ValueIdentity has no BigDecimal case, so the exact tier compares by scale there and
    // the TSON reader accepts both spellings as two keys. That is the TSON side's defect, filed as issue
    // #470 with its reproduction, and it reaches the FIXED check and set membership too. The case is left
    // out rather than asserted as expected divergence: pinning a defect as agreed behaviour is how it
    // becomes permanent. It joins this class when the fix lands.

    /**
     * A compound key takes §6.5's pairs form in JSON and the ordinary map form in text -- genuinely different
     * document shapes -- so the codes must agree and the pointers cannot.
     */
    @Test
    void twoSpellingsOfOneCompoundKey() {
        sameCodes("by_point", """
                { { x: 1  y: 2 } => "a"  { y: 2  x: 1 } => "b" }""", """
                [[{"x": 1, "y": 2}, "a"], [{"y": 2, "x": 1}, "b"]]""");
    }

    @Test
    void aRequiredGroupWithTwoMembersPresent() {
        sameVerdict("bounded", """
                { value: 1  min: 0  max: 9 }""", """
                {"value": 1, "min": 0, "max": 9}""");
    }
}
