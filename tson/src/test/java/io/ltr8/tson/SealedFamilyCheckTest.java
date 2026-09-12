package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each {@code record.extension} member obliges of the rest of a closure, through the front door
 * ([TSON-SCHEMA] §5.2, §5.7, §5.9; {@code SPEC-FEEDBACK.md} #10, #11). {@code RecordExtension} is the pass;
 * this is the evidence it is reachable from a schema an author could write.
 *
 * <p><b>Written against the real pipeline rather than hand-built entries</b>, because half of what is under
 * test is not expressible without one: a discriminator's type has to resolve through core to an atom, a pin
 * has to be parsed at that atom to be compared as a value, and the cross-schema cases need an import that
 * really merges a namespace. A hand-built {@code TypeDefinition} can state none of those.
 */
class SealedFamilyCheckTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static String schema(String name, String body) {
        return HEADER.formatted(name) + body;
    }

    /** Every diagnostic a schema produced, as one string -- what a message assertion reads. */
    private static String problems(Tson tson, String name, String body) {
        List<Diagnostic> diagnostics = tson.validateSchema(schema(name, body));
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    private static void isClean(Tson tson, String name, String body) {
        assertEquals(List.of(), tson.validateSchema(schema(name, body)), name);
    }

    // ── The shape the design exists for ──────────────────────────────────

    @Test
    void aWellFormedSealedFamilyLoads() {
        isClean(Tson.standard(), "happy", """
                {
                  pet => @sealed { @discriminator pet_type: text  name: text }
                  dog => pet & { pet_type: = "dog"  breed: text }
                  cat => pet & { pet_type: = "cat"  indoor: boolean }
                }""");
    }

    /**
     * A subtype pins the selector, which restates a field the base marks -- so the mark travels with the
     * field under §5.8's flattening and the subtype's copy carries it too. The rule that a discriminator
     * requires {@code @sealed} is about the mark a declaration <em>makes</em>, and reading it otherwise
     * refuses every subtype of every sealed family.
     */
    @Test
    void aSubtypeIsNotRequiredToBeSealedForInheritingTheSelector() {
        isClean(Tson.standard(), "inherit", """
                {
                  pet => @sealed { @discriminator pet_type: text  name: text }
                  dog => pet & { pet_type: = "dog" }
                }""");
    }

    /** §5.10's 2x2: the pins are distinct as tuples, in the base's declaration order. */
    @Test
    void aMatrixFamilyIsDistinctAsTuples() {
        isClean(Tson.standard(), "matrix", """
                {
                  cell => @sealed { @discriminator row: text  @discriminator col: text  v: int32 }
                  a1 => cell & { row: = "a"  col: = "1" }
                  a2 => cell & { row: = "a"  col: = "2" }
                  b1 => cell & { row: = "b"  col: = "1" }
                }""");
    }

    // ── The two marks are each other's condition ─────────────────────────

    @Test
    void aSealedRecordWithNoDiscriminatorIsRefused() {
        assertTrue(problems(Tson.standard(), "nodisc", "{ pet => @sealed { name: text } }")
                .contains("is @sealed but no field of it carries @discriminator"));
    }

    @Test
    void anAbstractRecordWithADiscriminatorIsRefused() {
        assertTrue(problems(Tson.standard(), "absdisc",
                "{ pet => @abstract { @discriminator pet_type: text  name: text } }")
                .contains("@abstract is the tag-dispatched case and admits no discriminator"));
    }

    @Test
    void anOpenRecordWithADiscriminatorIsRefused() {
        assertTrue(problems(Tson.standard(), "opendisc",
                "{ pet => { @discriminator pet_type: text  name: text } }")
                .contains("must be @sealed"));
    }

    // ── FINAL, and the one operation it does not constrain ───────────────

    @Test
    void composingOntoAFinalRecordIsRefused() {
        assertTrue(problems(Tson.standard(), "compose", """
                {
                  base => @final { x: int32 }
                  sub  => base & { y: int32 }
                }""").contains("is @final and admits none"));
    }

    @Test
    void refiningAFinalRecordIsRefused() {
        assertTrue(problems(Tson.standard(), "refine", """
                {
                  base => @final { x: int32 }
                  sub  => base ^ { x: int32 = 1 }
                }""").contains("is @final and admits none"));
    }

    /**
     * §5.9 empties the contract index and mints no IS-A edge, which is the only thing FINAL constrains -- so
     * subtracting from a final record is admissible where composing and refining are not. The check reads
     * {@code TypeDefinition.supertypes} rather than the body's authorial lineage for exactly this case.
     */
    @Test
    void subtractingFromAFinalRecordIsAdmissible() {
        isClean(Tson.standard(), "subtract", """
                {
                  base => @final { x: int32  y: int32 }
                  cut  => base - { y }
                }""");
    }

    // ── The selector's own conditions ────────────────────────────────────

    @Test
    void aSelectorMustBeRequired() {
        assertTrue(problems(Tson.standard(), "optsel",
                "{ pet => @sealed { @discriminator pet_type: text?  name: text } }")
                .contains("is OPTIONAL, and must be REQUIRED"));
    }

    /**
     * §5.2 grants a value only to a field <em>carrying</em> one, and the base's selector carries none -- so
     * nothing else asks whether its type could hold a pin at all, and a family whose selector is a record
     * would be found only by its subtypes failing one at a time.
     */
    @Test
    void aSelectorMustBeAnAtomOrAnEnum() {
        assertTrue(problems(Tson.standard(), "recsel", """
                {
                  inner => { q: text }
                  pet => @sealed { @discriminator pet_type: inner  name: text }
                }""").contains("which is not an atom or an enum"));
    }

    /**
     * A group member is uniformly OPTIONAL and mutually exclusive with its siblings (§5.11), so a selector
     * there may be absent and selects nothing. <b>The declaration can express this directly</b> -- §12.1
     * gives {@code group-member} its own annotation position -- so the mark is lowered there too rather than
     * being dropped, which is what makes this reachable at all.
     */
    @Test
    void aSelectorMayNotBeAGroupMember() {
        assertTrue(problems(Tson.standard(), "grpsel",
                "{ pet => @sealed { name: text  ( @discriminator pet_type: text | other: text ) } }")
                .contains("is a member of a field group"));
    }

    // ── The closure ──────────────────────────────────────────────────────

    @Test
    void aSubtypeThatDoesNotPinTheSelectorIsRefused() {
        assertTrue(problems(Tson.standard(), "nopin", """
                {
                  pet => @sealed { @discriminator pet_type: text  name: text }
                  bird => pet & { wings: int32 }
                }""").contains("does not pin its discriminator 'pet_type'"));
    }

    /** A default is omissible, so it cannot dispatch -- REQUIRED_FIXED and nothing else (§5.2). */
    @Test
    void aDefaultIsNotAPin() {
        assertTrue(problems(Tson.standard(), "defpin", """
                {
                  pet => @sealed { @discriminator pet_type: text  name: text }
                  dog => pet & { pet_type: text ~ "dog" }
                }""").contains("It is REQUIRED_DEFAULT here"));
    }

    @Test
    void twoSubtypesPinningOneValueAreRefused() {
        assertTrue(problems(Tson.standard(), "dup", """
                {
                  pet => @sealed { @discriminator pet_type: text  name: text }
                  dog => pet & { pet_type: = "dog"  breed: text }
                  hound => pet & { pet_type: = "dog"  scent: text }
                }""").contains("to the same value"));
    }

    /**
     * <b>Pins compare as values, not as tokens.</b> §4.3 makes {@code 255} and {@code 0xFF} one integer, so a
     * comparison of spellings would accept a schema whose dispatch table is not a function -- two subtypes a
     * decoder cannot tell apart. {@code ValueIdentity} is the one answer the series' three other
     * value-comparison rules already use.
     */
    @Test
    void pinsThatDifferOnlyInRadixCollide() {
        assertTrue(problems(Tson.standard(), "radix", """
                {
                  code => @sealed { @discriminator id: int32  name: text }
                  a => code & { id: = 255  x: text }
                  b => code & { id: = 0xFF  y: text }
                }""").contains("to the same value"));
    }

    /** And the same for scale, §5.5 making {@code 1} and {@code 1.0} one number. */
    @Test
    void pinsThatDifferOnlyInScaleCollide() {
        assertTrue(problems(Tson.standard(), "scale", """
                {
                  code => @sealed { @discriminator id: number  name: text }
                  a => code & { id: = 1  x: text }
                  b => code & { id: = 1.0  y: text }
                }""").contains("to the same value"));
    }

    // ── Across schemas ───────────────────────────────────────────────────

    private static final String BASE = """
            !!id:"https://example.test/base.tn"
            !!meta:"%s"
            !!import:"%s"
            {
              pet => @sealed { @discriminator pet_type: text  name: text }
              dog => pet & { pet_type: = "dog"  breed: text }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static String importer(String name, String body) {
        return """
                !!id:"https://example.test/%s.tn"
                !!meta:"%s"
                !!import:"https://example.test/base.tn"
                %s""".formatted(name, TsonBundledSchemas.META_ID, body);
    }

    /**
     * §3.3.4 makes {@code subtypes} open across schemas, so an importer really can add a family member -- and
     * the obligation travels with it. This is what makes the mark constitutive rather than an assertion the
     * declaring schema alone can keep.
     */
    @Test
    void anImporterAddingAnUnpinnedSubtypeIsRefused() {
        Tson tson = Tson.standard();
        tson.resolve(BASE);

        List<Diagnostic> diagnostics = tson.validateSchema(importer("nopin", "{ bird => pet & { wings: int32 } }"));

        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("does not pin its discriminator")),
                diagnostics.toString());
    }

    /** And a family is re-judged whenever any part of it is local: the new sibling can collide with an imported one. */
    @Test
    void anImporterCollidingWithAnImportedPinIsRefused() {
        Tson tson = Tson.standard();
        tson.resolve(BASE);

        List<Diagnostic> diagnostics = tson.validateSchema(
                importer("collide", "{ hound => pet & { pet_type: = \"dog\"  scent: text } }"));

        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("to the same value")),
                diagnostics.toString());
    }

    /** An importer that adds a well-formed member is not refused, and neither is the family it joins. */
    @Test
    void anImporterAddingAProperlyPinnedSubtypeLoads() {
        Tson tson = Tson.standard();
        tson.resolve(BASE);

        assertEquals(List.of(), tson.validateSchema(
                importer("bird", "{ bird => pet & { pet_type: = \"bird\"  wings: int32 } }")));
    }
}
