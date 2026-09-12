package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-JSON] §6.1.5's three readings of an untagged object, decided by the position's own extension fact
 * ([TSON-SCHEMA] §5.2) -- the JSON half of the discriminated-family design.
 *
 * <p><b>The point of the design is the documents that need no tag.</b> A sealed family's value is placed by
 * reading the members it already carries, so the JSON a plain consumer would write is the JSON this reads --
 * which is what makes a converted OpenAPI contract wire as itself rather than as a tagged variant.
 */
class JsonSealedFamilyReadTest {

    private static final String ID = "https://example.test/pets.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/pets.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              pet => @sealed { @discriminator pet_type: text  name: text }
              dog => pet & { pet_type: = "dog"  breed: text }
              cat => pet & { pet_type: = "cat"  indoor: boolean }

              shape => @abstract { area: int32 }
              square => shape & { side: int32 }

              frame => @sealed { @discriminator opcode: int32  payload: text }
              ping => frame & { opcode: = 0xFF  seq: int32 }

              holder => { p: pet  s: shape? }
            }
            """;

    private static Json json() {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        return Json.standard().withSchemas(tson.schemaRegistry());
    }

    private static List<Diagnostic> problems(String document, String type) {
        return json().validate(document, ID, type);
    }

    private static String message(String document, String type) {
        List<Diagnostic> diagnostics = problems(document, type);
        assertTrue(!diagnostics.isEmpty(), "expected a refusal, got none");
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    // ── SEALED: the member is the selector ───────────────────────────────

    /** No tag anywhere, in the schema or the document: the member places the value. */
    @Test
    void anUntaggedObjectIsPlacedByItsDiscriminator() {
        assertEquals(List.of(), problems("""
                {"pet_type": "dog", "name": "Rex", "breed": "corgi"}""", "pet"));
    }

    /** And the other member of the family, to show the selection is a selection and not a default. */
    @Test
    void adifferentPinSelectsTheOtherMember() {
        assertEquals(List.of(), problems("""
                {"pet_type": "cat", "name": "Tom", "indoor": true}""", "cat"));
        assertEquals(List.of(), problems("""
                {"pet_type": "cat", "name": "Tom", "indoor": true}""", "pet"));
    }

    /**
     * §6.1.6 gives member order no meaning, so the selector may arrive last -- after members that only the
     * type it selects declares. A reader that decided on the opening brace, or on the first member, cannot
     * read this, which is why the scan is a lookahead over the whole object.
     */
    @Test
    void theSelectorMayArriveAfterTheMembersItSelects() {
        assertEquals(List.of(), problems("""
                {"breed": "corgi", "name": "Rex", "pet_type": "dog"}""", "pet"));
    }

    /** The selected member validates the whole value, so its own fields are checked as they always were. */
    @Test
    void theSelectedMemberThenValidatesInFull() {
        assertTrue(message("""
                {"pet_type": "dog", "name": "Rex"}""", "pet").contains("missing required field 'breed'"));
    }

    /** A member of the family that the selected type does not declare is still §6.1.1's closure error. */
    @Test
    void theSelectedMemberIsStillClosedUnderItsType() {
        assertTrue(message("""
                {"pet_type": "dog", "name": "Rex", "breed": "corgi", "indoor": true}""", "pet")
                .contains("unknown field 'indoor'"));
    }

    @Test
    void aMissingDiscriminatorIsRefusedAndNeverFallsBackToTheTag() {
        assertTrue(message("""
                {"name": "Rex", "breed": "corgi"}""", "pet").contains("missing discriminator 'pet_type'"));
    }

    @Test
    void aDiscriminatorNoMemberPinsNamesWhatArrivedAndTheAlternatives() {
        String refusal = message("""
                {"pet_type": "dgo", "name": "Rex"}""", "pet");
        assertTrue(refusal.contains("no member of 'pet' pins"), refusal);
        assertTrue(refusal.contains("dog") && refusal.contains("cat"), refusal);
    }

    /**
     * <b>The pin is matched as a value, not as a token.</b> The schema pins {@code = 0xFF} and the document
     * writes {@code 255}: §4.3 makes those one integer, and both sides are decoded by the same parser before
     * either is compared. A reader matching spellings reads this as unmatched.
     *
     * <p><b>{@code seq} is what makes the assertion discriminating</b>, and it is worth saying why: {@code
     * frame} does not declare it, so a document carrying it validates only if the read really reached {@code
     * ping}. Without it the case passes against a reader that dispatches on nothing at all and simply
     * validates the base -- which is exactly what it did on the first attempt.
     */
    @Test
    void aPinMatchesByValueAndNotBySpelling() {
        assertEquals(List.of(), problems("""
                {"opcode": 255, "payload": "hi", "seq": 1}""", "frame"));
    }

    /** And the contrast: a value no member pins is refused, so the match above is a match and not a pass-through. */
    @Test
    void anOpcodeNoMemberPinsIsRefused() {
        assertTrue(message("""
                {"opcode": 7, "payload": "hi", "seq": 1}""", "frame").contains("no member of 'frame' pins"));
    }

    // ── SEALED: the tag can only assert ──────────────────────────────────

    @Test
    void aTagAgreeingWithTheDiscriminatorIsAdmitted() {
        assertEquals(List.of(), problems("""
                {"$type": "dog", "pet_type": "dog", "name": "Rex", "breed": "corgi"}""", "pet"));
    }

    @Test
    void aTagContradictingTheDiscriminatorIsRefused() {
        String refusal = message("""
                {"$type": "cat", "pet_type": "dog", "name": "Rex", "breed": "corgi"}""", "pet");
        assertTrue(refusal.contains("a tag may only agree"), refusal);
    }

    // ── ABSTRACT: the tag is the only selector ───────────────────────────

    @Test
    void anAbstractPositionRequiresTheTag() {
        String refusal = message("""
                {"area": 4, "side": 2}""", "shape");
        assertTrue(refusal.contains("is abstract and has no direct instances"), refusal);
        assertTrue(refusal.contains("square"), refusal);
    }

    @Test
    void anAbstractPositionReadsTheTaggedSubtype() {
        assertEquals(List.of(), problems("""
                {"$type": "square", "area": 4, "side": 2}""", "shape"));
    }

    /** The base has no direct instances, so a tag naming it is not a redundant restatement but an error. */
    @Test
    void anAbstractPositionRefusesATagNamingTheBase() {
        assertTrue(message("""
                {"$type": "shape", "area": 4}""", "shape").contains("is not a subtype of the abstract"));
    }

    // ── Nested, which is where the design earns its keep ─────────────────

    /** A sealed family at a field position, tag-free, beside an abstract one that needs its tag. */
    @Test
    void aFamilyReadsAtAFieldPositionWithNoTagAnywhere() {
        JsonValue read = json().treeReader().withSchema(ID).readAs("""
                {"p": {"pet_type": "cat", "name": "Tom", "indoor": false}}""", "holder");
        assertTrue(read.toString().contains("Tom"), read.toString());
    }
}
