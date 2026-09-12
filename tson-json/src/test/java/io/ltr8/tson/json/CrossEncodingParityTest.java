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
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
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
              employee  => person & { department: text }
              robot     => { serial: text }
              holder    => { who: person  labels: [text] }
              scalars   => ( text | int32 | boolean )
              circle    => { radius: float64 }
              square    => { side: float64 }
              shape     => ( circle | square )
              picked    => { pick: scalars }
              shaped    => { outline: shape }
              pet       => @sealed { @discriminator pet_type: text  name: text }
              dog       => pet & { pet_type: = "dog"  breed: text }
              cat       => pet & { pet_type: = "cat"  indoor: boolean }
              figure    => @abstract { area: int32 }
              disc      => figure & { side: int32 }
              kennel    => { p: pet }
              gallery   => { f: figure }
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
        return Verdict.of(jsonDiagnostics(rootType, body));
    }

    private static List<Diagnostic> jsonDiagnostics(String rootType, String body) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(body)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = COMPILED.rootDeclaration(rootType).map(ctx::underDeclaration).orElse(ctx);
            COMPILED.get(rootType).read(ctx);
        }
        return List.copyOf(problems);
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

    /**
     * The strongest comparison, for a rule both encodings now state from one place
     * ({@code base.diagnostics}): the code, the data pointer, the machine-readable {@code expected}, <b>and
     * the prose</b>.
     *
     * <p>Comparing the message is what makes the shared rule load-bearing rather than decorative — without
     * it the two readers could hold the same class and still phrase a refusal two ways. {@code actual} is
     * deliberately excluded: it echoes what the document literally held, so {@code _} there and {@code null}
     * here is right, that component being data rather than prose.
     */
    private static void sameRule(String rootType, String tsonBody, String jsonBody) {
        List<Diagnostic> fromTson = TSON.validate("!!schema:\"%s\"\n!%s %s".formatted(ID, rootType, tsonBody));
        assertFalse(fromTson.isEmpty(), "the TSON side reported nothing, so this compares nothing");
        List<Diagnostic> fromJson = jsonDiagnostics(rootType, jsonBody);
        assertEquals(fromTson.stream().map(Rule::of).toList(), fromJson.stream().map(Rule::of).toList(),
                "the two encodings state this rule differently");
    }

    /** A rule as both encodings must state it: which rule, where in the data, the constraint, and the prose. */
    private record Rule(Diagnostic.Code code, String path, String expected, String message) {

        static Rule of(Diagnostic d) {
            return new Rule(d.code(), d.path().orElse("?"), d.expected(), d.message());
        }
    }

    // ── Subtype families ([TSON-SCHEMA] §5.2, [TSON-JSON] §6.1.5) ────────

    /**
     * <b>The headline, and the reason the design exists.</b> A sealed family's value is placed by the members
     * it already carries, so neither encoding needs a tag -- the TSON is the TSON anyone would write and the
     * JSON is the JSON anyone would write, and they are the same value.
     *
     * <p>Read at a <em>field</em> position deliberately. TSON names a document's root type with its own
     * type-ref, so a sealed root necessarily carries {@code !pet} where JSON's root type arrives out of band
     * (§3.4) and carries nothing -- comparing those would be comparing the two encodings' root conventions
     * rather than their reading of a family.
     */
    @Test
    void aSealedFamilyIsTagFreeInBothEncodings() {
        bothAccept("kennel", "{ p: { pet_type: dog  name: Rex  breed: corgi } }",
                """
                        {"p": {"pet_type": "dog", "name": "Rex", "breed": "corgi"}}""");
    }

    /** And the selection is a selection: the other pin reaches the other member's fields. */
    @Test
    void theOtherPinSelectsTheOtherMemberInBoth() {
        bothAccept("kennel", "{ p: { pet_type: cat  name: Tom  indoor: true } }",
                """
                        {"p": {"pet_type": "cat", "name": "Tom", "indoor": true}}""");
    }

    @Test
    void aMissingDiscriminatorIsOneRuleInBoth() {
        sameRule("kennel", "{ p: { name: Rex  breed: corgi } }",
                """
                        {"p": {"name": "Rex", "breed": "corgi"}}""");
    }

    @Test
    void aDiscriminatorNoMemberPinsIsOneRuleInBoth() {
        sameRule("kennel", "{ p: { pet_type: dgo  name: Rex } }",
                """
                        {"p": {"pet_type": "dgo", "name": "Rex"}}""");
    }

    /**
     * The tag's <em>spelling</em> is the one thing that legitimately differs here -- {@code !cat} against
     * {@code "$type": "cat"} -- which is why {@code FamilyDiagnostics} keeps it out of the message and spends
     * it in {@code actual}, the component this comparison excludes.
     */
    @Test
    void aTagContradictingTheDiscriminatorIsOneRuleInBoth() {
        sameRule("kennel", "{ p: !cat { pet_type: dog  name: Rex  breed: corgi } }",
                """
                        {"p": {"$type": "cat", "pet_type": "dog", "name": "Rex", "breed": "corgi"}}""");
    }

    @Test
    void anAbstractPositionRequiresItsTagInBoth() {
        sameRule("gallery", "{ f: { area: 4 } }", """
                {"f": {"area": 4}}""");
    }

    /** The base has no direct instances, so naming it is an error in both -- not a redundant restatement. */
    @Test
    void aTagNamingTheAbstractBaseIsOneRuleInBoth() {
        sameRule("gallery", "{ f: !figure { area: 4 } }", """
                {"f": {"$type": "figure", "area": 4}}""");
    }

    @Test
    void anAbstractPositionTakesTheTaggedSubtypeInBoth() {
        bothAccept("gallery", "{ f: !disc { area: 4  side: 2 } }", """
                {"f": {"$type": "disc", "area": 4, "side": 2}}""");
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
        sameRule("person", """
                { labels: [] }""", """
                {"labels": []}""");
    }

    @Test
    void aFieldTheTypeDoesNotDeclare() {
        sameRule("person", """
                { name: "Ada"  labels: []  shoe_size: 9 }""", """
                {"name": "Ada", "labels": [], "shoe_size": 9}""");
    }

    @Test
    void aFieldStatedTwice() {
        sameRule("person", """
                { name: "Ada"  name: "Grace"  labels: [] }""", """
                {"name": "Ada", "name": "Grace", "labels": []}""");
    }

    @Test
    void aContradictedFixedValue() {
        sameRule("person", """
                { name: "Ada"  kind: "robot"  labels: [] }""", """
                {"name": "Ada", "kind": "robot", "labels": []}""");
    }

    /** The absent sentinel at a field that admits none: `_` in text, null in JSON, one verdict. */
    @Test
    void theAbsentSentinelAtARequiredField() {
        sameRule("person", """
                { name: _  labels: [] }""", """
                {"name": null, "labels": []}""");
    }

    /** §6.1.2: at REQUIRED_DEFAULT the fix is omission, and stating absence is refused in both encodings. */
    @Test
    void theAbsentSentinelAtADefaultedField() {
        sameRule("person", """
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
        sameRule("person", """
                { name: "Ada"  labels: [ "x", _ ] }""", """
                {"name": "Ada", "labels": ["x", null]}""");
    }

    @Test
    void anArrayOutsideItsSizeBounds() {
        sameRule("sized", """
                [ "a" ]""", """
                ["a"]""");
    }

    @Test
    void aRepeatedSetElement() {
        sameRule("unique", """
                [ "a", "a" ]""", """
                ["a", "a"]""");
    }

    @Test
    void aTupleOfTheWrongLength() {
        sameRule("pair", """
                [ "a" ]""", """
                ["a"]""");
    }

    @Test
    void aRequiredGroupWithNoMemberPresent() {
        sameRule("bounded", """
                { value: 1 }""", """
                {"value": 1}""");
    }

    // ── §8.2 discrimination: the derived `disjoint` fact, dispatched twice ──

    // A choice is exercised at a *nested* position, not as a root type, and the reason is a genuine
    // asymmetry rather than an oversight. In text the root type-ref is both the binding and, at a choice
    // position, the variant tag -- so `!scalars "hi"` is refused ("'scalars' is not a declared variant of
    // 'scalars'") and the document names `text` instead, leaving "the root is a scalars" with no carrier.
    // JSON binds out of band (§3.4), so a root type and a tag are separate statements and `readAs(json,
    // "scalars")` is ordinary. The *values* still agree; only the way the root type is stated differs, which
    // is §3.4's business and differs there by design. A field position has a position in both, so that is
    // where the predicate itself can be compared.

    /**
     * A disjoint, class-stable choice dispatches untagged in both encodings -- on [TSON-SCHEMA] §5.4's
     * discrimination class in text, on the JSON value kind here. The class derivation is written twice (the
     * TSON one lives in an unexported package), so this is the case where that duplication would show.
     */
    @Test
    void aDisjointChoiceDispatchesUntaggedInBoth() {
        bothAccept("picked", """
                { pick: "hi" }""", """
                {"pick": "hi"}""");
        bothAccept("picked", """
                { pick: 42 }""", """
                {"pick": 42}""");
        bothAccept("picked", """
                { pick: true }""", """
                {"pick": true}""");
    }

    /** The variant is selected, then validated as itself -- so an out-of-range value is refused in both. */
    @Test
    void theSelectedVariantIsValidatedInBoth() {
        sameVerdict("picked", """
                { pick: 99999999999 }""", """
                {"pick": 99999999999}""");
    }

    /**
     * Two record variants share the brace class, so the choice is not disjoint and neither encoding may
     * recover the variant from the form: §8.2 forbids member-shape matching as explicitly as [TSON-SCHEMA]
     * §5.4 does. `{"side": 1.0}` names only `square`'s field and is refused all the same.
     */
    @Test
    void aNonDisjointChoiceIsRefusedUntaggedInBoth() {
        sameCodes("shaped", """
                { outline: { side: 1.0 } }""", """
                {"outline": {"side": 1.0}}""");
    }

    /**
     * A tag naming something that is no variant: checked rather than assumed to agree, and the codes do.
     *
     * <p>The pointers legitimately differ, which is why this compares codes alone: in JSON the tag <b>is a
     * member</b> and has a location of its own, so the refusal lands at {@code /outline/$type}; in text it is
     * an annotation beside the value, and the value's own pointer is the nearest thing there is. JSON's is
     * the more useful of the two and names exactly what a sender would change.
     */
    @Test
    void aTagNamingANonVariantIsRefusedByBoth() {
        sameCodes("shaped", """
                { outline: !person { name: "Ada"  labels: [] } }""", """
                {"outline": {"$type": "person", "name": "Ada", "labels": []}}""");
    }

    /** And the tag makes it read: `!circle` in text, `$type` in JSON. */
    @Test
    void aTaggedVariantReadsInBoth() {
        bothAccept("shaped", """
                { outline: !circle { radius: 1.0 } }""", """
                {"outline": {"$type": "circle", "radius": 1.0}}""");
    }

    // ── §6.1.5 subsumption: `!employee` in text, `$type` in JSON ────────

    @Test
    void aSubtypeSelectedAtAFieldPosition() {
        bothAccept("holder", """
                { who: !employee { name: "Ada"  department: "Engines"  labels: [] }  labels: [] }""", """
                {"who": {"$type": "employee", "name": "Ada", "department": "Engines", "labels": []},\
 "labels": []}""");
    }

    /** Without a tag the value is exactly the position's type -- no structural recovery, in either encoding. */
    @Test
    void anUntaggedSubtypeShapeIsRefusedByBoth() {
        sameRule("holder", """
                { who: { name: "Ada"  department: "Engines"  labels: [] }  labels: [] }""", """
                {"who": {"name": "Ada", "department": "Engines", "labels": []}, "labels": []}""");
    }

    /** The selected type validates in full, so a field it adds and the document omits is still missing. */
    @Test
    void aTaggedSubtypeIsValidatedInFull() {
        sameVerdict("holder", """
                { who: !employee { name: "Ada"  labels: [] }  labels: [] }""", """
                {"who": {"$type": "employee", "name": "Ada", "labels": []}, "labels": []}""");
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
        sameRule("counts", """
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

    /**
     * §6.5: identity is over the key type's value space, so {@code 1} and {@code 1.0} under a {@code number}
     * key are one key in both encodings.
     *
     * <p>This case was held out of the suite while the TSON reader disagreed — its {@code ValueIdentity} had
     * no {@code BigDecimal} case, so the exact tier compared by scale there (issue #470). Pinning that as
     * expected divergence is how a defect becomes permanent, so it waited instead, and it is here now that
     * both encodings answer alike.
     */
    @Test
    void twoSpellingsOfOneKey() {
        sameRule("by_number", """
                { 1 => "a"  1.0 => "b" }""", """
                {"1": "a", "1.0": "b"}""");
    }


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
        sameRule("bounded", """
                { value: 1  min: 0  max: 9 }""", """
                {"value": 1, "min": 0, "max": 9}""");
    }
}
