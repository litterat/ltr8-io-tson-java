package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An enum's members are text, and {@code profile} says which kind of enumeration they spell
 * ([TSON-SCHEMA] §7.4, {@code SPEC-FEEDBACK.md} #21). {@code IDENTIFIER}, the default, is a vocabulary: every
 * member is a name, so the member set stays a named scope for [TSON-DATA] §8.2's hygiene and every member
 * has a host-safe spelling. {@code TEXT} is a value set: two strings a document may carry, nothing about
 * them a name.
 *
 * <p><b>Why the default matters here.</b> Every enum written before the facet existed keeps exactly what it
 * had -- including the hygiene it had -- and an author reaching for the value set says so once. The
 * declaration is what decides, never the shape of the members: a resolver that inferred "these look like
 * names, so police them" would switch a spoofing check on and off by accident.
 */
class EnumProfileTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static String schema(String name, String body) {
        return HEADER.formatted(name) + body;
    }

    private static String messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    // ── The profile at schema load ───────────────────────────────────────

    /** The default is the rule every enum in the series was written under, and it still bites. */
    @Test
    void aVocabularyRefusesAMemberThatIsNotAName() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("vocab", """
                { activity => !enum ["sedentary" "lightly active"] }"""));

        String messages = messages(diagnostics);
        assertTrue(messages.contains("cannot appear in an identifier"), messages);
        // The diagnostic names the one-line fix, which is the whole point of the facet.
        assertTrue(messages.contains("declares 'profile: TEXT'"), messages);
    }

    /** And the declaration lifts it -- the 33.9% of real-world enums that no identifier rule admits. */
    @Test
    void aValueSetAdmitsMembersNoIdentifierRuleWould() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("values", """
                { activity => !enum { members: ["sedentary" "lightly active" "2D"]  profile: TEXT } }""")));
    }

    /** The bracket sugar is unchanged, and so is what it means. */
    @Test
    void theVocabularySpellingIsUntouched() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("plain", """
                { status => !enum [OPEN ACTIVE DONE] }""")));
    }

    /**
     * {@code IDENTIFIER} is inside {@code TEXT}, so a refinement may withdraw the latitude and never grant
     * it -- §5.7's selector rule, over the relation {@code enum_profile} states.
     */
    @Test
    void aRefinementMayNotWidenTheProfile() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("widen", """
                { names => !enum [A B]
                  loose => !names ^ { profile: TEXT } }"""));

        assertTrue(messages(diagnostics).contains("never grant it"), messages(diagnostics));
    }

    // ── The read ─────────────────────────────────────────────────────────

    /**
     * A member is matched on the token's decoded text, which is what it always was -- the profile governs
     * which members may be <em>declared</em>, and nothing about the match. So the diagnostic an author came
     * for is the enum's own, naming the members, rather than a pattern alternation.
     */
    @Test
    void aValueSetReadsItsMembersAndNamesThemOnAMiss() {
        Tson tson = Tson.standard();
        tson.resolve(schema("read", """
                { activity => !enum { members: ["sedentary" "lightly active"]  profile: TEXT }
                  rec => { a: activity } }"""));
        String head = "!!schema:\"https://example.test/read.tn\"\n!rec ";

        assertEquals(List.of(), tson.validate(head + "{ a: \"lightly active\" }"));

        List<Diagnostic> miss = tson.validate(head + "{ a: \"very active\" }");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, miss.getFirst().code());
        assertTrue(miss.getFirst().message()
                .contains("is not a member of this enum -- expected one of [sedentary, lightly active]"),
                    miss.getFirst().message());
    }

    /** {@code boolean} is an enum like any other and stays one: no carve-out went in with the facet. */
    @Test
    void booleanIsStillAnOrdinaryEnum() {
        Tson tson = Tson.standard();
        tson.resolve(schema("bool", "{ rec => { b: boolean } }"));
        String head = "!!schema:\"https://example.test/bool.tn\"\n!rec ";

        assertEquals(List.of(), tson.validate(head + "{ b: true }"));
        assertEquals(List.of(), tson.validate(head + "{ b: \"true\" }"),
                "a typed position is read by its declared type, never by base type resolution ([TSON-DATA] §4.2)");
    }
}
