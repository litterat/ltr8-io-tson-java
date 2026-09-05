package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A constraint field typed {@code value} is read under the atom of the position it stands in.
 *
 * <p>[TSON-SCHEMA] §7.4 types a constructor's constraint fields {@code value}, and the bootstrap ordering
 * behind it leaves no alternative: {@code duration_type} is what defines a duration, and {@code duration =>
 * !duration_type {}} lives a layer up in core.tn, so meta.tn cannot write {@code min: duration}. A {@code
 * value} slot is decoded by [TSON-DATA] §4 and by nothing else, which resolves boolean, number and string --
 * so every non-numeric bound in the meta layer arrives as a {@code String}, and without this it reaches a
 * component that cannot hold one.
 *
 * <p>These are written at the front door rather than against the parser because the question is a phase
 * boundary: a bound has to survive resolution, reach the compiled reader, and refuse a document. A unit test
 * over {@code ValueParser} can only show the first inch of that.
 */
class ValueTypedFacetTest {

    private static final AtomicInteger NEXT = new AtomicInteger();

    /** A one-declaration schema under a fresh identity, so each case registers cleanly in one {@link Tson}. */
    private static String schemaDeclaring(String declarations) {
        return """
                !!id:"https://example.test/facet-%d.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { %s }
                """.formatted(NEXT.incrementAndGet(), declarations);
    }

    private static List<Diagnostic> loading(String declarations) {
        return Tson.builder().build().validateSchema(schemaDeclaring(declarations));
    }

    // ── every family's bound binds ───────────────────────────────────────

    @Test
    void everyNonNumericBoundInTheMetaLayerLoads() {
        // Each of these reported NOT_IMPLEMENTED -- a library gap, over an ordinary schema -- because the
        // token arrived as the String base type resolution made of it.
        assertEquals(List.of(), loading("t => !date ^ { min: 2020-01-01 }"));
        assertEquals(List.of(), loading("t => !time ^ { min: \"09:00:00Z\" }"));
        assertEquals(List.of(), loading("t => !datetime ^ { min: \"2020-01-01T00:00:00Z\" }"));
        assertEquals(List.of(), loading("t => !duration ^ { min: PT30M  max: PT2H  multiple_of: PT15M }"));
        assertEquals(List.of(), loading("t => !period ^ { min: P1M }"));
        assertEquals(List.of(), loading("t => !rational ^ { min: \"1/2\" }"));
    }

    @Test
    void aBoundThatLoadsAlsoRefusesADocument() {
        // The point of the fix, and the half a resolution test cannot show: the bound reaches the compiled
        // reader and decides a read. `multiple_of` ignores sign, so the magnitude is what is tested.
        Tson tson = Tson.builder().build();
        String schema = schemaDeclaring("""
                slot => !duration ^ { min: PT30M  max: PT2H  multiple_of: PT15M }
                  box => { d: slot }""");
        // A fresh instance for the load check: validateSchema registers what it validated, and resolve
        // refuses a second registration under one identity.
        assertEquals(List.of(), Tson.builder().build().validateSchema(schema));
        tson.resolve(schema);
        String id = schema.split("\"")[1];

        assertEquals(List.of(), tson.validate("!!schema:\"" + id + "\"\n!box { d: PT45M }"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate("!!schema:\"" + id + "\"\n!box { d: PT10M }").getFirst().code());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate("!!schema:\"" + id + "\"\n!box { d: PT3H }").getFirst().code());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate("!!schema:\"" + id + "\"\n!box { d: PT40M }").getFirst().code());
    }

    @Test
    void oneValueWrittenTwoWaysIsOneBound() {
        // The bound compares the value and never the token (§5.5), which is only testable now that a bound
        // binds at all: PT1H30M and PT90M are the same duration and the same ceiling.
        Tson tson = Tson.builder().build();
        String schema = schemaDeclaring("""
                slot => !duration ^ { max: PT1H30M }
                  box => { d: slot }""");
        tson.resolve(schema);
        String id = schema.split("\"")[1];

        assertEquals(List.of(), tson.validate("!!schema:\"" + id + "\"\n!box { d: PT90M }"));
        assertEquals(List.of(), tson.validate("!!schema:\"" + id + "\"\n!box { d: P0DT5400S }"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate("!!schema:\"" + id + "\"\n!box { d: PT5401S }").getFirst().code());
    }

    // ── the author's error is the author's ───────────────────────────────

    @Test
    void aBoundTheAtomRefusesIsASchemaErrorNotAGap() {
        // The classification test: the verdict on `min: "abc"` does not change when this library improves,
        // so it must not arrive as NOT_IMPLEMENTED -- which is what the cast failure used to produce, and
        // what told an author their correct reading of the spec was this library's fault.
        for (String bad : List.of("t => !number ^ { min: \"abc\" }",
                                  "t => !duration ^ { min: P1Y }",
                                  "t => !date ^ { min: \"not-a-date\" }")) {
            List<Diagnostic> reported = loading(bad);
            assertEquals(1, reported.size(), reported.toString());
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, reported.getFirst().code(), bad);
        }
    }

    @Test
    void aRefusedBoundIsNamedByTheFieldTheAuthorWrote() {
        // Not by `value`, which is the entry the slot is typed as and names the escape hatch rather than
        // anything in the author's document.
        Diagnostic reported = loading("t => !duration ^ { min: P1Y }").getFirst();
        assertTrue(reported.message().contains("'min'"), reported.message());
        assertTrue(reported.message().contains("a month is a period"), reported.message());
    }

    // ── additive: nothing that read before reads differently ─────────────

    @Test
    void aNumericBoundKeepsEverySpellingItAlreadyAdmitted() {
        // `decimal_type`'s bounds worked already, through the caller's own numeric narrowing, and a based
        // integer is the case that would have broken had the token simply been re-read under `number`, whose
        // own grammar admits no based-integer form (§5.6). The rebind is reached only by a value the
        // component could not have held under any narrowing.
        assertEquals(List.of(), loading("t => !number ^ { min: 1  max: 10 }"));
        assertEquals(List.of(), loading("t => !number ^ { min: 0x10 }"));
        assertEquals(List.of(), loading("t => !number ^ { min: 1.5  multiple_of: 0.05 }"));
        assertEquals(List.of(), loading("t => !float64 ^ { min: 1  max: 2.5 }"));
        assertEquals(List.of(), loading("t => !int32 ^ { min: 1  max: 0xFF }"));
    }

    @Test
    void aBoundOutsideItsSourceIsStillATighteningError() {
        // The narrowing rules run on the bound values, so they only mean anything once the values are real.
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
