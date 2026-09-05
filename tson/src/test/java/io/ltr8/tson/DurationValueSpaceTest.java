package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.schema.meta.DurationType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.5's duration value space, at the phase boundaries a parser test cannot reach: a bound written in a
 * schema, a facet refused at schema load, and a document judged against both.
 *
 * <p>The value is a signed <b>exact decimal</b> number of seconds — no non-terminating fraction is writable,
 * the lexical form putting a fraction on the seconds component and nowhere else — with both ends fixed at a
 * signed 64-bit count of nanoseconds ([TSON-SCHEMA] §5.5, [TSON-DATA] §5.4). What these pin is that they are
 * the same ends everywhere, rather than whatever {@code java.time.Duration} happens to reach.
 */
class DurationValueSpaceTest {

    private static final AtomicInteger NEXT = new AtomicInteger();

    private record Fixture(Tson tson, String id) {
        List<Diagnostic> read(String body) {
            return tson.validate("!!schema:\"" + id + "\"\n" + body);
        }
    }

    private static String schemaDeclaring(String declarations) {
        return """
                !!id:"https://example.test/duration-%d.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { %s }
                """.formatted(NEXT.incrementAndGet(), declarations);
    }

    private static Fixture governing(String declarations) {
        String schema = schemaDeclaring(declarations);
        Tson tson = Tson.builder().build();
        tson.resolve(schema);
        return new Fixture(tson, schema.split("\"")[1]);
    }

    private static List<Diagnostic> loading(String declarations) {
        return Tson.builder().build().validateSchema(schemaDeclaring(declarations));
    }

    // ── the two ends, through a document ─────────────────────────────────

    @Test
    void aValueAtEachEndOfTheSpaceIsAdmittedAndOneBeyondIsNot() {
        Fixture fixture = governing("box => { d: duration }");

        assertEquals(List.of(), fixture.read("!box { d: PT0.000000001S }"));
        assertEquals(List.of(), fixture.read("!box { d: PT9223372036.854775807S }"));
        assertEquals(List.of(), fixture.read("!box { d: -PT9223372036.854775807S }"));

        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                fixture.read("!box { d: PT0.0000000001S }").getFirst().code());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                fixture.read("!box { d: P400000D }").getFirst().code());
    }

    @Test
    void theCeilingIsAVerdictOnTheDocumentNotAHostLimit() {
        // `java.time.Duration` would take P400000D happily, so nothing here is reporting a representation
        // problem: it is a rule about the format, and the sender still holds the fix.
        Diagnostic refused = governing("box => { d: duration }").read("!box { d: P400000D }").getFirst();
        assertTrue(refused.code().verdict(), "the ceiling is a rule about the document");
        assertTrue(refused.message().contains("a period"), refused.message());
    }

    // ── precision measures the value ─────────────────────────────────────

    @Test
    void precisionAdmitsAnAdmittedValueHoweverItIsSpelled() {
        Fixture fixture = governing("""
                tenths => !duration ^ { precision: 1 }
                  box => { d: tenths }""");

        assertEquals(List.of(), fixture.read("!box { d: PT0.5S }"));
        assertEquals(List.of(), fixture.read("!box { d: PT0.50S }"));
        assertEquals(List.of(), fixture.read("!box { d: PT0.500000000S }"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                fixture.read("!box { d: PT0.51S }").getFirst().code());
    }

    @Test
    void precisionFinerThanTheValueSpaceIsASchemaError() {
        // There is no tenth fractional digit for a schema to ask about, so the facet names nothing.
        List<Diagnostic> reported = loading("t => !duration ^ { precision: 12 }");
        assertEquals(1, reported.size(), reported.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, reported.getFirst().code());
        assertTrue(reported.getFirst().message().contains("precision 12"), reported.getFirst().message());
    }

    // ── a bound is a value of the space too ──────────────────────────────

    @Test
    void aBoundPastTheCeilingIsRefusedWhereverItComesFrom() {
        // From a schema, the bound goes through the parser like any other duration token.
        List<Diagnostic> reported = loading("t => !duration ^ { min: P400000D }");
        assertEquals(1, reported.size(), reported.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, reported.getFirst().code());

        // Built in Java it does not, so the coherence rule is what keeps the invariant true -- and what
        // keeps `isMultiple`'s own `toNanos` from overflowing on a step nothing parsed.
        DurationType overlong = new DurationType(Optional.of(Duration.ofDays(400_000)), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(1, overlong.coherenceCheck().size(), overlong.coherenceCheck().toString());
        assertTrue(overlong.coherenceCheck().getFirst().contains("longer than"),
                overlong.coherenceCheck().toString());

        DurationType finer = new DurationType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(BigInteger.valueOf(12)), Optional.empty());
        assertTrue(finer.coherenceCheck().stream().anyMatch(v -> v.contains("precision 12")),
                finer.coherenceCheck().toString());
    }

    @Test
    void aBoundStillCompilesAndStillJudges() {
        // The value space changed; the facets it is stated in terms of still work end to end.
        Fixture fixture = governing("""
                slot => !duration ^ { min: PT30M  max: PT2H  multiple_of: PT15M }
                  box => { d: slot }""");

        assertEquals(List.of(), fixture.read("!box { d: PT45M }"));
        assertEquals(List.of(), fixture.read("!box { d: P0DT5400S }"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                fixture.read("!box { d: PT10M }").getFirst().code());
    }
}
