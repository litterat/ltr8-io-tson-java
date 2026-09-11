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
 * [TSON-JSON] §8.2's discrimination predicate and §8.3's class stability.
 *
 * <p>Two routes and no third. Route 1 is a declared {@code @discriminator} (§8.4) and is unbuilt, so every
 * case here is route 2 — {@code disjoint: true} with class-stable variants, dispatching on the JSON value
 * kind — or the tagged form, or the refusal that follows when neither recovers the variant.
 */
class JsonChoiceReadTest {

    /**
     * TSON defines no comment syntax ([TSON-DATA] §2.10 — metadata is annotations), so what each declaration
     * is for is said here instead.
     *
     * <p>{@code scalar_or_list} is disjoint with class-stable variants: one per JSON kind, so route 2 holds.
     * {@code shape}'s two record variants share the brace class, so it is not disjoint and the tag is
     * required. {@code unit_circle} is a subtype of a variant, reachable by tag alone. {@code loose} carries
     * §8.3's first leak — a {@code float64} still admitting {@code .nan} and the infinities has string-class
     * values — and {@code tight} is the same choice over a variant that narrowed both facets away.
     */
    private static final String SCHEMA = """
            !!id:"https://example.test/choice-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              scalar_or_list => ( text | int32 | boolean | [text] )

              circle      => { radius: float64 }
              square      => { side: float64 }
              shape       => ( circle | square )
              unit_circle => circle & { fixed: boolean }

              loose  => ( float64 | text )
              strict => !float_type { format: BINARY64  allow_nan: false  allow_infinity: false }
              tight  => ( strict | text )

              holder => { pick: scalar_or_list }
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

    // ── §8.2 route 2: the kind selects ───────────────────────────────────

    @Test
    void aDisjointChoiceDispatchesOnTheJsonValueKind() {
        assertEquals("\"hi\"", read("scalar_or_list", "\"hi\"").accepted().toString());
        assertEquals("42", read("scalar_or_list", "42").accepted().toString());
        assertEquals("true", read("scalar_or_list", "true").accepted().toString());
        assertEquals("""
                ["a"]""", read("scalar_or_list", """
                ["a"]""").accepted().toString());
    }

    /** The variant is selected by kind, then validated as itself -- dispatch is not acceptance. */
    @Test
    void theSelectedVariantIsThenValidated() {
        Diagnostic refusal = read("scalar_or_list", """
                ["a", 2]""").refusal();
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal.code());
        assertEquals("/1", refusal.path().orElseThrow());
    }

    /** A kind no variant bears is refused, and the message names the kinds that are taken. */
    @Test
    void aKindNoVariantBearsIsRefused() {
        Diagnostic refusal = read("scalar_or_list", """
                {"a": 1}""").refusal();
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal.code());
        assertTrue(refusal.message().contains("BOOLEAN") || refusal.message().contains("STRING"),
                refusal.message());
    }

    /** §7: null carries no class at all -- it is the absent sentinel, and a choice admits no absence. */
    @Test
    void nullAtAChoiceIsAnAbsenceAndNotAnUnknownKind() {
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, read("scalar_or_list", "null").refusal().code());
    }

    @Test
    void routeTwoWorksAtANestedPosition() {
        assertEquals("""
                {"pick":42}""", read("holder", """
                {"pick": 42}""").accepted().toString());
    }

    // ── §8.2: where neither route holds, the tag is REQUIRED ─────────────

    /**
     * Two record variants share the brace class, so the choice is not disjoint and route 2 is unavailable.
     * §8.2's last clause: the tag is REQUIRED, and the message says so rather than guessing a variant.
     */
    @Test
    void aNonDisjointChoiceRequiresATag() {
        Diagnostic refusal = read("shape", """
                {"radius": 1.0}""").refusal();
        // UNKNOWN_TYPE_REF, matching the TSON reader for the same document -- §9.4 gives both encodings one
        // vocabulary, and the enum has no member for "a required tag is missing" (BACKLOG).
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, refusal.code());
        assertTrue(refusal.message().contains("$type"), refusal.message());
        assertTrue(refusal.message().contains("circle"), refusal.message());
    }

    /** And the predicate is not extended: no member-shape matching among record variants. */
    @Test
    void nothingMatchesARecordVariantByItsMembers() {
        // `{"side": 1.0}` is unambiguous to a human -- only `square` declares `side` -- and §8.2 forbids
        // recovering the variant that way, because that is the cleverness the closed predicate excludes.
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, read("shape", """
                {"side": 1.0}""").refusal().code());
    }

    // ── §8.1: the tagged form, admitted at every choice position ─────────

    @Test
    void aTagSelectsTheVariantWhereNoRouteCould() {
        assertEquals("""
                {"radius":1.0}""", read("shape", """
                {"$type": "circle", "radius": 1.0}""").accepted().toString());
    }

    /** "A tag is never wrong": it is accepted where route 2 would have selected the same variant anyway. */
    @Test
    void aTagIsAdmittedWhereItCouldHaveBeenOmitted() {
        assertEquals("""
                {"radius":1.0}""", read("shape", """
                {"$type": "circle", "radius": 1.0}""").accepted().toString());
        assertEquals("42", read("scalar_or_list", """
                {"$type": "int32", "$value": 42}""").accepted().toString());
    }

    /** §8.4's note: a `$type` naming a proper subtype of a variant validates as that subtype. */
    @Test
    void aTagMayNameASubtypeOfAVariant() {
        assertEquals("""
                {"radius":1.0,"fixed":true}""", read("shape", """
                {"$type": "unit_circle", "radius": 1.0, "fixed": true}""").accepted().toString());
    }

    @Test
    void aTagNamingSomethingThatIsNoVariantIsRefused() {
        Diagnostic refusal = read("shape", """
                {"$type": "holder", "pick": 1}""").refusal();
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, refusal.code());
        assertTrue(refusal.expected().contains("circle"), refusal.expected());
    }

    /** §8.5 admits `$schema` at a scoped position; a choice is the closed sum, so it is refused here. */
    @Test
    void aSchemaMemberIsRefusedAtAChoicePosition() {
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, read("shape", """
                {"$schema": "https://example.test/other.tn", "$type": "circle", "radius": 1.0}""")
                .refusal().code());
    }

    // ── §8.3 class stability ─────────────────────────────────────────────

    /**
     * §8.3's first leak: a `float64` admitting `.nan` and the infinities has values that encode as JSON
     * strings, so it is not class-stable and route 2 is unavailable however disjoint the choice looks.
     */
    @Test
    void anApproximateVariantAdmittingSpecialsIsNotClassStable() {
        Diagnostic refusal = read("loose", "1.5").refusal();
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, refusal.code());
        assertTrue(refusal.message().contains("$type"), "route 2 must be unavailable: " + refusal.message());
    }

    /** Narrowing both facets to false confines the family to JSON numbers and restores stability. */
    @Test
    void narrowingTheFacetsRestoresStabilityAndWithItRouteTwo() {
        assertEquals("1.5", read("tight", "1.5").accepted().toString());
        assertEquals("\"hi\"", read("tight", "\"hi\"").accepted().toString());
    }

    /** A tag still reads at an unstable choice: stability gates route 2, never the tagged form. */
    @Test
    void anUnstableChoiceStillTakesATaggedValue() {
        assertEquals("1.5", read("loose", """
                {"$type": "float64", "$value": 1.5}""").accepted().toString());
    }
}
