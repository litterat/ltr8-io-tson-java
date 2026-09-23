package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code text_type.members} -- the sparse case on the text tier, as {@code integer_type.members} is on the
 * integer one ({@code SPEC-FEEDBACK.md} #22). It is not the spelling for a bare text value set, which is an
 * enum under {@code profile: TEXT}; it is for a value set on a type whose <em>other</em> facets are also
 * wanted, where the family's own parsing still applies.
 *
 * <p><b>The two rules that are not read off the declaration.</b> Every member must satisfy the other facets
 * on the same body, the pattern included -- and {@code pattern} and {@code members} are each settable once,
 * because they occupy one logical position and the pattern half cannot be narrowed without deciding
 * regular-language containment.
 */
class TextMemberSetTest {

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

    // ── Coherence: the members answer to the facets beside them ──────────

    @Test
    void aMemberSetComposesWithTheFacetsBesideIt() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("ok", """
                { code => !text ^ { length: 2  members: ["AU" "NZ"] } }""")));
    }

    @Test
    void aMemberTheLengthFacetsExcludeIsASchemaLoadError() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("len", """
                { code => !text ^ { length: 2  members: ["AU" "NZL"] } }"""));

        assertTrue(messages(diagnostics).contains("member 'NZL' is 3 characters, and length is 2"),
                messages(diagnostics));
    }

    /**
     * The pattern half of the same rule, checked where the regex engine is: {@code tson-schema} carries no
     * dependency on {@code tson-regex}, so this one sits in the resolver rather than on the family beside
     * the length checks. Same diagnostic shape either way, which is the point of putting it there rather
     * than leaving it unchecked.
     */
    @Test
    void aMemberThePatternExcludesIsASchemaLoadError() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("pat", """
                { code => !text ^ { pattern: "[A-Z]{2}"  members: ["AU" "nz"] } }"""));

        assertTrue(messages(diagnostics).contains("member 'nz' does not match pattern [A-Z]{2}"),
                messages(diagnostics));
    }

    /** The families composing {@code text_type} inherit the facet, and the rule that polices it. */
    @Test
    void theComposingFamiliesInheritTheMemberSet() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("uri", """
                { endpoint => !uri ^ { members: ["https://a.test/" "https://b.test/"] } }""")));
    }

    // ── Refinement: each of the pair is settable once ────────────────────

    /**
     * The narrowing this arrangement exists to keep. A member set may be <em>added</em> to a patterned text,
     * because the members half was unset -- and the members then answer to the inherited pattern.
     */
    @Test
    void aPatternedTextMayBeNarrowedToAMemberSet() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("narrow", """
                { country_code => !text ^ { pattern: "[A-Z]{2}" }
                  nordic => !country_code ^ { members: ["SE" "NO" "DK"] } }""")));
    }

    @Test
    void aMemberSetAlreadySetMayNotBeShrunk() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("shrink", """
                { country_code => !text ^ { members: ["SE" "NO" "DK"] }
                  core => !country_code ^ { members: ["SE"] } }"""));

        assertTrue(messages(diagnostics).contains("may be restated but not changed"), messages(diagnostics));
    }

    @Test
    void aPatternAlreadySetMayNotBeReplaced() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("repat", """
                { country_code => !text ^ { pattern: "[A-Z]{2}" }
                  lower => !country_code ^ { pattern: "[a-z]{2}" } }"""));

        assertTrue(messages(diagnostics).contains("may be restated but not changed"), messages(diagnostics));
    }

    // ── The read ─────────────────────────────────────────────────────────

    @Test
    void aValueOutsideTheMemberSetIsNamedAgainstIt() {
        Tson tson = Tson.standard();
        tson.resolve(schema("read", """
                { code => !text ^ { length: 2  members: ["AU" "NZ"] }
                  rec => { c: code } }"""));
        String head = "!!schema:\"https://example.test/read.tn\"\n!rec ";

        assertEquals(List.of(), tson.validate(head + "{ c: AU }"));

        List<Diagnostic> miss = tson.validate(head + "{ c: GB }");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, miss.getFirst().code());
        assertTrue(miss.getFirst().message().contains("is not a member of this type -- expected one of [AU, NZ]"),
                miss.getFirst().message());
    }
}
