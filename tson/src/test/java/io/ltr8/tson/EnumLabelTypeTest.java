package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An enum's members are labels drawn from a naming vocabulary, its <b>label type</b> ([TSON-SCHEMA] §7.4): an
 * enum is an application of the kernel's {@code enum_of<T>}, {@code enum} is {@code enum_of<identifier>} and
 * {@code text_enum} is {@code enum_of<text>}. Members conform to {@code T} structurally, through {@code set<T>},
 * and whether {@code T} is an identifier family decides which of [TSON-DATA] §8.2's rules reach them.
 *
 * <p><b>The declaration decides, never the shape of the members.</b> {@code !enum [...]} keeps exactly what it
 * had, hygiene included, and an author reaching for a value set of arbitrary text says so by writing
 * {@code !text_enum}: a resolver that inferred "these look like names, so police them" would switch a spoofing
 * check on and off by accident.
 */
class EnumLabelTypeTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    /** A meta layer: governed by the kernel, and importing meta so the kernel's {@code enum_of} is in scope. */
    private static final String META_HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID);

    private static String schema(String name, String body) {
        return HEADER.formatted(name) + body;
    }

    private static String meta(String name, String body) {
        return META_HEADER.formatted(name) + body;
    }

    private static String messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    // ── The label type at schema load ────────────────────────────────────

    /** {@code enum}'s label type is {@code identifier}, and a member that is not one is refused. */
    @Test
    void aVocabularyRefusesAMemberThatIsNotAName() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("vocab", """
                { activity => !enum ["sedentary" "lightly active"] }"""));

        String messages = messages(diagnostics);
        assertTrue(messages.contains("cannot appear in an identifier"), messages);
        // §7.4: the diagnostic names the spelling that admits the member.
        assertTrue(messages.contains("'!text_enum [...]'"), messages);
    }

    /** {@code text_enum}'s label type is {@code text}, which admits what no identifier rule would. */
    @Test
    void aTextEnumAdmitsMembersNoIdentifierRuleWould() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("values", """
                { activity => !text_enum ["sedentary" "lightly active" "2D"] }""")));
    }

    /** The bracket sugar is unchanged, and so is what it means. */
    @Test
    void theVocabularySpellingIsUntouched() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("plain", """
                { status => !enum [OPEN ACTIVE DONE] }""")));
    }

    /** A refinement narrows the member set and keeps the label type, which the application fixed. */
    @Test
    void aRefinementNarrowsTheMembersAndKeepsTheLabelType() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("narrow", """
                { cities => !text_enum ["new york" "los angeles"]
                  east   => !cities ^ { members: ["new york"] } }""")));

        List<Diagnostic> widened = Tson.standard().validateSchema(schema("widen", """
                { cities => !text_enum ["new york" "los angeles"]
                  more   => !cities ^ { members: ["new york" "boston"] } }"""));
        assertTrue(messages(widened).contains("boston"), messages(widened));
    }

    // ── enum_of's argument ───────────────────────────────────────────────

    /**
     * A meta layer declares an enum over its own naming vocabulary in one line: a refinement of
     * {@code identifier} is an identifier family, so its members are names.
     */
    @Test
    void aMetaLayerDeclaresAnEnumOverItsOwnNamingVocabulary() {
        assertEquals(List.of(), Tson.standard().validateSchema(meta("kebab", """
                { kebab      => !identifier ^ { pattern: "[a-z]+(-[a-z]+)*" }
                  kebab_enum => enum_of<kebab> }""")));
    }

    /**
     * An enum's labels are drawn from a text family, so {@code enum_of<integer>} is refused at schema load --
     * a numeric value set is {@code integer}'s own {@code members} facet -- rather than resolving and failing
     * later inside a consumer's bind.
     */
    @Test
    void enumOfRefusesAnArgumentThatIsNotATextFamily() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(meta("numbers", """
                { int_enum => enum_of<integer> }"""));

        String messages = messages(diagnostics);
        assertTrue(messages.contains("'int_enum' applies enum_of<integer>"), messages);
        assertTrue(messages.contains("constructor IS-A text_type"), messages);
    }

    /** A text family that is not a naming vocabulary in the §7.7 sense still qualifies: {@code uri} is text. */
    @Test
    void enumOfAdmitsAnyTextFamily() {
        assertEquals(List.of(), Tson.standard().validateSchema(meta("uris", """
                { uri_enum => enum_of<uri> }""")));
    }

    // ── The read ─────────────────────────────────────────────────────────

    /**
     * A member is matched on the token's decoded text -- the label type governs which members may be
     * <em>declared</em>, and nothing about the match. So the diagnostic an author came for is the enum's own,
     * naming the members, rather than a pattern alternation.
     */
    @Test
    void aTextEnumReadsItsMembersAndNamesThemOnAMiss() {
        Tson tson = Tson.standard();
        tson.resolve(schema("read", """
                { activity => !text_enum ["sedentary" "lightly active"]
                  rec => { a: activity } }"""));
        String head = "!!schema:\"https://example.test/read.tn\"\n!rec ";

        assertEquals(List.of(), tson.validate(head + "{ a: \"lightly active\" }"));

        List<Diagnostic> miss = tson.validate(head + "{ a: \"very active\" }");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, miss.getFirst().code());
        assertTrue(miss.getFirst().message()
                .contains("is not a member of this enum -- expected one of [sedentary, lightly active]"),
                    miss.getFirst().message());
    }

    /** {@code boolean} is an enum like any other and stays one. */
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
