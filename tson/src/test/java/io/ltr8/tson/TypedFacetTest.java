package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A constructor's bound, step or member is typed by its family's instance -- {@code temporal.duration_type}'s
 * {@code min} is a {@code duration}, {@code numeric.decimal_type}'s {@code members} a set of {@code number} --
 * so the token is read by that instance's reader like any other value of it.
 *
 * <p>These are written at the front door rather than against the parser because the question is a phase
 * boundary: a bound has to survive resolution, reach the compiled reader, and refuse a document.
 */
class TypedFacetTest {

    private static final AtomicInteger NEXT = new AtomicInteger();

    /** A one-declaration schema under a fresh identity, so each case registers cleanly in one {@link Tson}. */
    private static String schemaDeclaring(String declarations) {
        return """
                !!id:"https://example.test/facet-%d.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema { %s }
                """.formatted(NEXT.incrementAndGet(), declarations);
    }

    private static List<Diagnostic> loading(String declarations) {
        return Tson.standard().validateSchema(schemaDeclaring(declarations));
    }

    // ── every family's bound binds ───────────────────────────────────────

    @Test
    void everyFamilysBoundLoads() {
        assertEquals(List.of(), loading("t => !date ^ { min: 2020-01-01 }"));
        assertEquals(List.of(), loading("t => !time ^ { min: \"09:00:00Z\" }"));
        assertEquals(List.of(), loading("t => !datetime ^ { min: \"2020-01-01T00:00:00Z\" }"));
        assertEquals(List.of(), loading("t => !duration ^ { min: PT30M  max: PT2H  multiple_of: PT15M }"));
        assertEquals(List.of(), loading("t => !period ^ { min: P1M  multiple_of: P3M }"));
        assertEquals(List.of(), loading("t => !rational ^ { min: \"1/2\"  multiple_of: \"1/4\" }"));
        assertEquals(List.of(), loading("t => !number ^ { min: 1.5  multiple_of: 0.05  members: [1.5 2 2.50] }"));
        assertEquals(List.of(), loading("t => !float64 ^ { min: 1  max: 2.5 }"));
    }

    @Test
    void aBoundThatLoadsAlsoRefusesADocument() {
        // The half a resolution test cannot show: the bound reaches the compiled reader and decides a read.
        // `multiple_of` ignores sign, so the magnitude is what is tested.
        Tson tson = Tson.standard();
        String schema = schemaDeclaring("""
                slot => !duration ^ { min: PT30M  max: PT2H  multiple_of: PT15M }
                  box => { d: slot }""");
        // A fresh instance for the load check: validateSchema registers what it validated, and resolve
        // refuses a second registration under one identity.
        assertEquals(List.of(), Tson.standard().validateSchema(schema));
        tson.resolve(schema);
        String id = schema.split("\"")[1];

        assertEquals(List.of(), tson.validate("!!import:\"" + id + "\"\n!box { d: PT45M }"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate("!!import:\"" + id + "\"\n!box { d: PT10M }").getFirst().code());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate("!!import:\"" + id + "\"\n!box { d: PT3H }").getFirst().code());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate("!!import:\"" + id + "\"\n!box { d: PT40M }").getFirst().code());
    }

    @Test
    void oneValueWrittenTwoWaysIsOneBound() {
        // The bound compares the value and never the token (§5.5): PT1H30M and PT90M are the same duration and
        // the same ceiling.
        Tson tson = Tson.standard();
        String schema = schemaDeclaring("""
                slot => !duration ^ { max: PT1H30M }
                  box => { d: slot }""");
        tson.resolve(schema);
        String id = schema.split("\"")[1];

        assertEquals(List.of(), tson.validate("!!import:\"" + id + "\"\n!box { d: PT90M }"));
        assertEquals(List.of(), tson.validate("!!import:\"" + id + "\"\n!box { d: P0DT5400S }"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate("!!import:\"" + id + "\"\n!box { d: PT5401S }").getFirst().code());
    }

    // ── the author's error is the author's ───────────────────────────────

    @Test
    void aBoundTheAtomRefusesIsASchemaErrorNotAGap() {
        // The classification test: the verdict on `min: "abc"` does not change when this library improves, so
        // it must not arrive as NOT_IMPLEMENTED.
        for (String bad : List.of("t => !number ^ { min: \"abc\" }",
                                  "t => !duration ^ { min: P1Y }",
                                  "t => !date ^ { min: \"not-a-date\" }",
                                  "t => !number ^ { members: [1 abc] }")) {
            List<Diagnostic> reported = loading(bad);
            assertEquals(1, reported.size(), reported.toString());
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, reported.getFirst().code(), bad);
        }
    }

    @Test
    void aRefusedBoundCarriesItsFamilysOwnVerdict() {
        // The bound is read by `duration`'s reader, so the refusal is that reader's, as it is for a document's
        // `!duration P1Y`.
        Diagnostic reported = loading("t => !duration ^ { min: P1Y }").getFirst();
        assertTrue(reported.message().contains("'P1Y' is not a valid duration"), reported.message());
        assertTrue(reported.message().contains("a month is a period"), reported.message());
    }

    @Test
    void aBoundIsSpelledAsAValueOfItsFamily() {
        // `number` admits no based-integer form (§5.6), as a bound or as data; `integer` does.
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, loading("t => !number ^ { min: 0x10 }").getFirst().code());
        assertEquals(List.of(), loading("t => !int32 ^ { min: 1  max: 0xFF }"));
    }

    @Test
    void twoSpellingsOfOneMemberAreADuplicate() {
        List<Diagnostic> reported = loading("t => !number ^ { members: [1 1.0] }");
        assertEquals(1, reported.size(), reported.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, reported.getFirst().code());
    }

    // ── a refinement of a refinement ─────────────────────────────────────

    @Test
    void aRefinementOfARefinementReadsItsBoundAsTheBaseInstance() {
        // `-5` is a `number`, so it reads; that it widens `positive`'s floor is the tightening check's to refuse,
        // not the read's.
        List<Diagnostic> reported = loading("""
                positive => !number ^ { min: 0 }
                  wider => !positive ^ { min: -5 }""");
        assertEquals(1, reported.size(), reported.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, reported.getFirst().code());
        assertTrue(reported.getFirst().message().contains("min"), reported.getFirst().message());

        assertEquals(List.of(), loading("""
                positive => !number ^ { min: 0 }
                  narrower => !positive ^ { min: 5 }"""));
    }

    @Test
    void aBoundOutsideItsSourceIsStillATighteningError() {
        List<Diagnostic> reported = loading("""
                narrow => !duration ^ { min: PT1H }
                  wider => !narrow ^ { min: PT30M }""");
        assertEquals(1, reported.size(), reported.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, reported.getFirst().code());
    }

    @Test
    void anIncoherentRangeIsStillRefused() {
        List<Diagnostic> reported = loading("t => !duration ^ { min: PT2H  max: PT1H }");
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.getFirst().message().contains("contradict"), reported.getFirst().message());
    }
}
