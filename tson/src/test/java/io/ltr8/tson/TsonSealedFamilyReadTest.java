package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A subtype family read in TSON text: [TSON-SCHEMA] §5.2's extension fact deciding how a position is read,
 * which is the same rule [TSON-JSON] §6.1.5 spells in members. The peer of {@code JsonSealedFamilyReadTest},
 * and what closes the divergence the JSON half opened.
 *
 * <p><b>The point is the tag that is not there.</b> A sealed family places its value by the fields it already
 * carries, so the reference encoding reads the discriminator rather than declaring it and ignoring it --
 * which is what makes the fact the model's rather than one encoding's.
 */
class TsonSealedFamilyReadTest {

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

              holder => { p: pet  s: shape?  u: unpeopled? }
              unpeopled => @abstract { a: int32 }
              frame_holder => { f: frame }
            }
            """;

    private static Tson tson() {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        return tson;
    }

    private static List<Diagnostic> problems(String body) {
        return tson().validate("!!schema:\"%s\"\n%s".formatted(ID, body));
    }

    private static String message(String body) {
        List<Diagnostic> diagnostics = problems(body);
        assertTrue(!diagnostics.isEmpty(), "expected a refusal, got none");
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    // ── SEALED: the field is the selector ────────────────────────────────

    @Test
    void anUntaggedValueIsPlacedByItsDiscriminator() {
        assertEquals(List.of(), problems("!holder { p: { pet_type: dog  name: Rex  breed: corgi } }"));
    }

    @Test
    void theOtherPinSelectsTheOtherMember() {
        assertEquals(List.of(), problems("!holder { p: { pet_type: cat  name: Tom  indoor: true } }"));
    }

    /**
     * A record's fields have no significant order, so the selector may arrive after the fields it selects.
     * A reader that decided on the opening brace cannot read this, which is why the scan is a lookahead.
     */
    @Test
    void theSelectorMayArriveAfterTheFieldsItSelects() {
        assertEquals(List.of(), problems("!holder { p: { breed: corgi  name: Rex  pet_type: dog } }"));
    }

    @Test
    void theSelectedMemberThenValidatesInFull() {
        assertTrue(message("!holder { p: { pet_type: dog  name: Rex } }")
                .contains("missing required field 'breed'"));
    }

    @Test
    void aMissingDiscriminatorIsRefusedAndNeverFallsBackToTheTag() {
        assertTrue(message("!holder { p: { name: Rex  breed: corgi } }")
                .contains("missing discriminator 'pet_type'"));
    }

    @Test
    void aDiscriminatorNoMemberPinsNamesWhatArrivedAndTheAlternatives() {
        String refusal = message("!holder { p: { pet_type: dgo  name: Rex } }");
        assertTrue(refusal.contains("no member of 'pet' pins"), refusal);
        assertTrue(refusal.contains("dog") && refusal.contains("cat"), refusal);
    }

    /**
     * <b>A quoted pin and an unquoted token are one value.</b> The schema writes {@code = "dog"} and the
     * document writes {@code dog}: [TSON-DATA] §4.4 makes a quoted token a string and the text atom reads
     * both to the same one, and both sides go through that atom before either is compared.
     */
    @Test
    void aPinMatchesAcrossTokenForms() {
        assertEquals(List.of(), problems("!holder { p: { pet_type: \"dog\"  name: Rex  breed: corgi } }"));
    }

    /**
     * And across radix: the schema pins {@code = 0xFF}, the document writes {@code 255}, and §4.3 makes those
     * one integer. {@code seq} is what makes this discriminating -- {@code frame} does not declare it, so the
     * value validates only if the read really reached {@code ping}.
     */
    @Test
    void aPinMatchesByValueAndNotBySpelling() {
        assertEquals(List.of(), problems("!ping { opcode: 255  payload: hi  seq: 1 }"));
    }

    /** The same value at a sealed position, dispatched rather than named -- the pin does the placing. */
    @Test
    void aPinMatchesByValueWhenTheMemberIsDispatched() {
        assertEquals(List.of(), tson().validate(
                "!!schema:\"%s\"\n!frame_holder { f: { opcode: 255  payload: hi  seq: 1 } }".formatted(ID)));
    }

    // ── SEALED: the tag can only assert ──────────────────────────────────

    /**
     * <b>A tag naming the base is refused, and §8.1's redundant-tag rule does not save it.</b> That rule
     * admits a tag restating a position's own type, but it assumes a type with direct instances -- a sealed
     * base has none, so naming it asserts something no value satisfies and selects nothing. A document names
     * the member instead, which is also what it would have to do at an abstract position.
     */
    @Test
    void aTagNamingTheBaseIsRefused() {
        assertTrue(message("!pet { pet_type: dog  name: Rex  breed: corgi }")
                .contains("selects nothing -- it is abstract"));
    }

    /** And naming the member reads, which is how a document states a sealed root type. */
    @Test
    void namingTheMemberIsHowASealedRootIsWritten() {
        assertEquals(List.of(), problems("!dog { pet_type: dog  name: Rex  breed: corgi }"));
    }

    @Test
    void aTagAgreeingWithTheDiscriminatorIsAdmitted() {
        assertEquals(List.of(), problems("!holder { p: !dog { pet_type: dog  name: Rex  breed: corgi } }"));
    }

    @Test
    void aTagContradictingTheDiscriminatorIsRefused() {
        assertTrue(message("!holder { p: !cat { pet_type: dog  name: Rex  breed: corgi } }")
                .contains("a tag may only agree"));
    }

    // ── ABSTRACT: the tag is the only selector ───────────────────────────

    @Test
    void anAbstractPositionRequiresTheTag() {
        String refusal = message("!holder { p: { pet_type: dog  name: Rex  breed: corgi }  s: { area: 4 } }");
        assertTrue(refusal.contains("is abstract and has no direct instances"), refusal);
        assertTrue(refusal.contains("square"), refusal);
    }

    @Test
    void anAbstractPositionReadsTheTaggedSubtype() {
        assertEquals(List.of(), problems(
                "!holder { p: { pet_type: dog  name: Rex  breed: corgi }  s: !square { area: 4  side: 2 } }"));
    }

    @Test
    void anAbstractPositionRefusesATagNamingTheBase() {
        assertTrue(message(
                "!holder { p: { pet_type: dog  name: Rex  breed: corgi }  s: !shape { area: 4 } }")
                .contains("selects nothing -- it is abstract"));
    }

    /**
     * <b>An empty family is a read-time diagnostic and never a load-time refusal.</b> §3.3.4 makes {@code
     * subtypes} open across schemas, so a library declaring an abstract base for its importers to extend is
     * empty in its own closure and complete in every consumer's -- which is the shape an abstract base most
     * exists for. Refusing it at link would make the library unpublishable.
     *
     * <p>What the reader owes is a message that names the remedy. The one this replaced offered {@code one of
     * ()} -- an empty list presented as a choice, an instruction no sender can follow.
     */
    @Test
    void anEmptyFamilyNamesTheMissingImportRatherThanOfferingAnEmptyChoice() {
        String refusal = message("!holder { p: { pet_type: dog  name: Rex  breed: corgi }  u: { a: 1 } }");
        assertTrue(refusal.contains("no schema in this closure declares a subtype of 'unpeopled'"), refusal);
        assertTrue(!refusal.contains("one of ()"), refusal);
    }

    /** And the schema itself loads: the base is a library declaration, not a defect. */
    @Test
    void aBaseWithNoSubtypesStillLoads() {
        assertEquals(List.of(), Tson.standard().validateSchema(SCHEMA));
    }

    // ── The value that comes back ────────────────────────────────────────

    /** Tree mode returns the selected member's own value, so what was dispatched is observable. */
    @Test
    void theTreeCarriesTheSelectedMembersFields() {
        TsonValue read = tson().treeReader().withSchema(ID)
                .readAs("!holder { p: { pet_type: cat  name: Tom  indoor: true } }", "holder");
        assertEquals("Tom", read.at("/p/name").asString().orElseThrow());
        assertTrue(read.at("/p/indoor").asBoolean().orElseThrow());
    }


}
