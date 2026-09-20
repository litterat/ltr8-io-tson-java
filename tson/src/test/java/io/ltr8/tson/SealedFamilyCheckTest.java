package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ── The retired spelling ─────────────────────────────────────────────

    /**
     * Nothing knows the names. meta.tn declares neither {@code abstract} nor {@code final}, so the annotation
     * spelling is the ordinary unresolved-name error of §3.3.3 -- the footing {@code @sealed} and {@code
     * @discriminator} are already on, and the reason there is no name left to reserve once the mark is a word
     * the grammar reads.
     *
     * <p>Asserted here rather than against the meta-kernel, whose bootstrap resolves no annotation at all: a
     * schema governed by the kernel would ignore the name instead, and the kernel governs only the three
     * bundled schemas.
     */
    @Test
    void theAnnotationSpellingNamesNoType() {
        assertTrue(problems(Tson.standard(), "retired", """
                {
                  pet => @abstract { name: text }
                }""").contains("does not name a type"));
    }

    // ── The shape the design exists for ──────────────────────────────────

    @Test
    void aWellFormedSealedFamilyLoads() {
        isClean(Tson.standard(), "happy", """
                {
                  pet => abstract { pet_type: text =?  name: text }
                  dog => pet & { pet_type: = "dog"  breed: text }
                  cat => pet & { pet_type: = "cat"  indoor: boolean }
                }""");
    }

    /**
     * <b>A member does not carry the mark at all.</b> The mark says which field a family dispatches
     * <em>on</em>, which is the base's statement; a member restates the selector to pin it, and what it
     * carries is the value. So the subtype resolves clean without needing an exemption from "a discriminator
     * requires {@code abstract}" -- that rule now reads exactly as written, because every mark it sees is one
     * the declaration made.
     */
    @Test
    void aSubtypePinningTheSelectorDoesNotCarryTheMark() {
        Tson tson = Tson.standard();
        String schema = schema("inherit", """
                {
                  pet => abstract { pet_type: text =?  name: text }
                  dog => pet & { pet_type: = "dog" }
                }""");
        // validateSchema registers a schema that reports nothing, so asking for it again would be a second
        // registration under one identity -- the entries are already there to read.
        assertEquals(List.of(), tson.validateSchema(schema), "inherit");

        RecordBody member = (RecordBody) tson.schemaRegistry()
                .get("https://example.test/inherit.tn").orElseThrow()
                .schema().entries().get("dog").body();
        RecordField pinned = member.fields().stream()
                .filter(f -> f.name().equals("pet_type")).findFirst().orElseThrow();

        assertEquals(FieldState.REQUIRED_FIXED, pinned.state(), "the member pins the selector");
        assertTrue(member.discriminators().isEmpty(),
                () -> "and the member names no selector of its own, the base having stated it: " + member);
    }

    /** §5.10's 2x2: the pins are distinct as tuples, in the base's declaration order. */
    @Test
    void aMatrixFamilyIsDistinctAsTuples() {
        isClean(Tson.standard(), "matrix", """
                {
                  cell => abstract { row: text =?  col: text =?  v: int32 }
                  a1 => cell & { row: = "a"  col: = "1" }
                  a2 => cell & { row: = "a"  col: = "2" }
                  b1 => cell & { row: = "b"  col: = "1" }
                }""");
    }

    // ── The two marks are each other's condition ─────────────────────────

    @Test
    void anAbstractRecordWithNoDiscriminatorIsTagDispatched() {
        // No selector is no defect: the members are selected by the tag, which is ABSTRACT's own reading.
        isClean(Tson.standard(), "nodisc", "{ pet => abstract { name: text } }");
    }

    @Test
    void anAbstractRecordWithADiscriminatorIsTheFamilyBase() {
        // The spelling the design settles on: 'abstract' states that the record has no values of its own,
        // and the '=?' field states what its members are selected by. There is no second mark.
        isClean(Tson.standard(), "absdisc", "{ pet => abstract { pet_type: text =?  name: text }"
                + "  dog => pet & { pet_type: = \"dog\" } }");
    }

    @Test
    void aSelectorImpliesAbstractAndTheMarkIsAnAssertion() {
        // The record's members pin the selector, so it is the base they are selected from and has no values
        // of its own: ABSTRACT is derived, and 'abstract' beside it asserts what the body already says.
        isClean(Tson.standard(), "opendisc",
                "{ pet => { pet_type: text =?  name: text }  dog => pet & { pet_type: = \"dog\" } }");
        isClean(Tson.standard(), "markeddisc",
                "{ pet => abstract { pet_type: text =?  name: text }  dog => pet & { pet_type: = \"dog\" } }");
    }

    /** `final` says nothing may extend it, where a selector says its members do. */
    @Test
    void aFinalRecordWithASelectorIsRefused() {
        assertTrue(problems(Tson.standard(), "finaldisc",
                "{ pet => final { pet_type: text =?  name: text } }")
                .contains("could never exist"));
    }

    // ── FINAL, and the one operation it does not constrain ───────────────

    @Test
    void composingOntoAFinalRecordIsRefused() {
        assertTrue(problems(Tson.standard(), "compose", """
                {
                  base => final { x: int32 }
                  sub  => base & { y: int32 }
                }""").contains("is final and admits none"));
    }

    @Test
    void refiningAFinalRecordIsRefused() {
        assertTrue(problems(Tson.standard(), "refine", """
                {
                  base => final { x: int32 }
                  sub  => base ^ { x: int32 = 1 }
                }""").contains("is final and admits none"));
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
                  base => final { x: int32  y: int32 }
                  cut  => base - { y }
                }""");
    }

    // ── The selector's own conditions ────────────────────────────────────

    @Test
    void aSelectorMustBeRequired() {
        assertTrue(problems(Tson.standard(), "optsel",
                "{ pet => abstract { pet_type: text? =?  name: text } }")
                .contains("is a discriminator ('=?') and optional"));
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
                  pet => abstract { pet_type: inner =?  name: text }
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
                "{ pet => abstract { name: text  ( pet_type: text =? | other: text ) } }")
                .contains("a field group member takes no value modifier"));
    }

    // ── The closure ──────────────────────────────────────────────────────

    @Test
    void aSubtypeThatDoesNotPinTheSelectorIsRefused() {
        assertTrue(problems(Tson.standard(), "nopin", """
                {
                  pet => abstract { pet_type: text =?  name: text }
                  bird => pet & { wings: int32 }
                }""").contains("does not pin its discriminator 'pet_type'"));
    }

    /** A default is omissible, so it cannot dispatch -- REQUIRED_FIXED and nothing else (§5.2). */
    @Test
    void aDefaultIsNotAPin() {
        assertTrue(problems(Tson.standard(), "defpin", """
                {
                  pet => abstract { pet_type: text =?  name: text }
                  dog => pet & { pet_type: text ~ "dog" }
                }""").contains("It is REQUIRED_DEFAULT here"));
    }

    @Test
    void twoSubtypesPinningOneValueAreRefused() {
        assertTrue(problems(Tson.standard(), "dup", """
                {
                  pet => abstract { pet_type: text =?  name: text }
                  dog => pet & { pet_type: = "dog"  breed: text }
                  hound => pet & { pet_type: = "dog"  scent: text }
                }""").contains("to the same value"));
    }

    /**
     * <b>A colliding member is named the way the author wrote it.</b> Both members here are declared
     * applications, so each declaration <em>is</em> its instantiation ({@code SPEC-FEEDBACK.md} #15) and the
     * names that collide are {@code a} and {@code b} -- names an author can open and edit.
     *
     * <p>The rule the message applies is unchanged; what changed is that there is no derived name left to
     * render for this shape. While the declaration was a hop, the collision was between two content-named
     * entries and the message depended on {@code EntryDisplayName} rendering each as the application that
     * produced it. The negative assertion stays either way: no content-derived name reaches the author.
     */
    @Test
    void collidingMembersAreNamedByTheDeclarationsThatCollide() {
        String refusal = problems(Tson.standard(), "displayed", """
                {
                  pet => abstract { pet_type: text =? }
                  pet_of => <T, V> pet & { pet_type: = T  pet: V }
                  a => pet_of<"cat", text>
                  b => pet_of<"cat", int32>
                }""");

        assertTrue(refusal.contains("'a'"), refusal);
        assertTrue(refusal.contains("'b'"), refusal);
        assertFalse(refusal.matches("(?s).*pet_of_cat_[a-z0-9]+_[0-9a-f]{8}.*"),
                "no content-derived entry name reaches the author: " + refusal);
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
                  code => abstract { id: int32 =?  name: text }
                  a => code & { id: = 255  x: text }
                  b => code & { id: = 0xFF  y: text }
                }""").contains("to the same value"));
    }

    /** And the same for scale, §5.5 making {@code 1} and {@code 1.0} one number. */
    @Test
    void pinsThatDifferOnlyInScaleCollide() {
        assertTrue(problems(Tson.standard(), "scale", """
                {
                  code => abstract { id: number =?  name: text }
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
              pet => abstract { pet_type: text =?  name: text }
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
