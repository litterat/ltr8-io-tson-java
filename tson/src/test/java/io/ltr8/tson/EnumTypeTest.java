package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An enum is an instance of the kernel's {@code enum_type}: its members, held as text, and the {@code name_type}
 * they are drawn from ([TSON-SCHEMA] §7.4). {@code enum} pins {@code name_type: identifier}, and {@code !enum_type {
 * name_type: T  members: [...] }} names any identifier family. Each member must be a value of {@code T}, and is a
 * name that [TSON-DATA] §8.2's hygiene reaches under {@code T}'s profile.
 *
 * <p><b>An enum is a set of names, and nothing else is.</b> Names a profile beyond the kernel's identifier admits,
 * such as words separated by single spaces, are an enum over the author's own identifier type; a closed set of
 * text that is no name is the text family's own {@code members} facet, {@code !text_type { members: [...] }}.
 */
class EnumTypeTest {

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

    // ── The type at schema load ──────────────────────────────────────────

    /** {@code enum}'s type is {@code identifier}, and a member that is not one is refused -- once. */
    @Test
    void aVocabularyRefusesAMemberThatIsNotAName() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("vocab", """
                { activity => !enum ["sedentary" "lightly active"] }"""));

        String messages = messages(diagnostics);
        assertEquals(1, diagnostics.size(), messages);
        assertTrue(messages.contains("is not a value of its name_type 'identifier'"), messages);
        // §7.4: the diagnostic names the spelling that admits the member as a value.
        assertTrue(messages.contains("'!text ^ { members: [...] }'"), messages);
    }

    /**
     * The {@code identifier} {@code enum} pins was written in the governing meta, and names the kernel's there -- a
     * schema's own {@code identifier}, however narrow, is a different type and does not take its place.
     */
    @Test
    void aSchemasOwnIdentifierDoesNotRetypeAnEnum() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("own-identifier", """
                { identifier => !identifier_type { pattern: "[a-z]+" }
                  shout      => !enum [LOUD QUIET] }""")));
    }

    /** An {@code enum} applied in a template's held body is typed as one applied directly. */
    @Test
    void anEnumAppliedThroughATemplateNamesTheKernelsIdentifier() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("held-enum", """
                { names => <M> !enum [a b M]
                  named => names<c> }""")));
        List<Diagnostic> refused = Tson.standard().validateSchema(schema("held-enum-bad", """
                { names => <M> !enum [a b M]
                  named => names<"not a name"> }"""));
        assertTrue(messages(refused).contains("is not a value of its name_type 'identifier'"), messages(refused));
    }

    /** An author's own identifier type admits names the kernel's does not, and an enum over it has them. */
    @Test
    void anEnumOverItsOwnIdentifierTypeAdmitsItsNames() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("words", """
                { words    => !identifier_type { continue_add: "-"  medial: " " }
                  activity => !enum_type { name_type: words  members: [sedentary "lightly active"] } }""")));
    }

    /** {@code text} is a text family and no identifier family, so an enum over it is refused. */
    @Test
    void anEnumOverTextIsRefused() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("over-text", """
                { cities => !enum_type { name_type: text  members: ["new york" "los angeles"] } }"""));

        assertTrue(messages(diagnostics).contains("its name_type 'text' is not an identifier family"),
                messages(diagnostics));
    }

    /** The bracket sugar is unchanged, and so is what it means. */
    @Test
    void theVocabularySpellingIsUntouched() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("plain", """
                { status => !enum [OPEN ACTIVE DONE] }""")));
    }

    /** A refinement narrows the member set; the type is fixed where the enum was constructed. */
    @Test
    void aRefinementNarrowsTheMembersAndNeverTheType() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("narrow", """
                { status => !enum [OPEN ACTIVE DONE]
                  live   => !status ^ { members: [OPEN ACTIVE] } }""")));

        // Through a tightening that pins the type, the constructor itself refuses another value.
        List<Diagnostic> pinned = Tson.standard().validateSchema(schema("retype-pinned", """
                { upper  => !identifier_type { pattern: "[A-Z]+" }
                  names  => !enum [OPEN DONE]
                  strict => !names ^ { name_type: upper } }"""));
        assertTrue(messages(pinned).contains("'name_type' is fixed on 'enum'"), messages(pinned));

        // Through `enum_type` itself, the enum's own narrowing rule does.
        List<Diagnostic> free = Tson.standard().validateSchema(schema("retype-free", """
                { upper  => !identifier_type { pattern: "[A-Z]+" }
                  names  => !enum_type { name_type: identifier  members: [OPEN DONE] }
                  strict => !names ^ { name_type: upper } }"""));
        assertTrue(messages(free).contains("an enum's name_type is fixed where it is constructed"), messages(free));
    }

    /**
     * An ordinary schema enumerates labels of a naming vocabulary it declares itself: an author-written
     * {@code name_type} resolves in the author's own namespace, and every member is held to it.
     */
    @Test
    void anOrdinarySchemaEnumeratesItsOwnNamingVocabulary() {
        Tson tson = Tson.standard();
        String kebab = """
                { kebab => !identifier_type { continue_add: "-"  pattern: "[a-z]+(-[a-z]+)*" }
                  steps => !enum_type { name_type: kebab  members: [make-tea drink-tea] }
                  rec   => { s: steps } }""";
        tson.resolve(schema("steps", kebab));
        String head = "!!schema:\"https://example.test/steps.tn\"\n!rec ";
        assertEquals(List.of(), tson.validate(head + "{ s: make-tea }"));

        List<Diagnostic> refused = Tson.standard().validateSchema(schema("steps-bad", """
                { kebab => !identifier_type { continue_add: "-"  pattern: "[a-z]+(-[a-z]+)*" }
                  steps => !enum_type { name_type: kebab  members: [make-tea Drink] } }"""));
        assertTrue(messages(refused).contains("member 'Drink' is not a value of its name_type 'kebab'"),
                messages(refused));
    }

    /**
     * An enum's members are drawn from an identifier family, so a type on another family is refused at schema
     * load -- a numeric value set is {@code integer}'s own {@code members} facet.
     */
    @Test
    void aTypeThatIsNoIdentifierFamilyIsRefused() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("numbers", """
                { ports => !enum_type { name_type: integer  members: ["80" "443"] } }"""));

        assertTrue(messages(diagnostics).contains("its name_type 'integer' is not an identifier family"),
                messages(diagnostics));
    }

    @Test
    void aTypeNamingNothingIsRefused() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("nothing", """
                { e => !enum_type { name_type: no_such_type  members: [a] } }"""));

        assertTrue(messages(diagnostics).contains("its name_type 'no_such_type' names nothing in scope"),
                messages(diagnostics));
    }

    /**
     * A meta layer's own tightening of {@code enum_type} needs no class of its own: it adds no field, so it binds
     * as the constructor it tightens. The pinned {@code name_type} resolves where the meta layer wrote it, so a
     * governed schema that never declares {@code kebab} still has its members checked against it.
     */
    @Test
    void aMetaLayerTighteningBindsWithNoMappingOfItsOwn() {
        String metaId = "https://example.test/kebab-meta.tn";
        String userId = "https://example.test/kebab-user.tn";
        String meta = """
                !!id:"%s"
                !!meta:"%s"
                !!import:"%s"
                { kebab      => !identifier_type { continue_add: "-"  pattern: "[a-z]+(-[a-z]+)*" }
                  kebab_enum => enum_type ^ { name_type?: = kebab } }
                """.formatted(metaId, TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID);
        String user = """
                !!id:"%s"
                !!meta:"%s"
                { steps => !kebab_enum [make-tea drink-tea]
                  rec   => { s: steps } }
                """.formatted(userId, metaId);
        String bad = """
                !!id:"https://example.test/kebab-bad.tn"
                !!meta:"%s"
                { steps => !kebab_enum [make-tea Drink] }
                """.formatted(metaId);
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of(metaId, meta, userId, user, "https://example.test/kebab-bad.tn", bad)))));

        assertEquals(List.of(), tson.validateSchema(user));
        assertEquals(List.of(), tson.validate("!!schema:\"" + userId + "\"\n!rec { s: drink-tea }"));
        assertTrue(messages(tson.validateSchema(bad)).contains("member 'Drink' is not a value of its name_type 'kebab'"),
                messages(tson.validateSchema(bad)));
    }

    // ── The read ─────────────────────────────────────────────────────────

    /**
     * A member is matched on the token's decoded text -- the type governs which members may be <em>declared</em>.
     * So the diagnostic an author came for is the enum's own, naming the members, rather than a pattern
     * alternation.
     */
    @Test
    void anEnumReadsItsMembersAndNamesThemOnAMiss() {
        Tson tson = Tson.standard();
        tson.resolve(schema("read", """
                { words    => !identifier_type { continue_add: "-"  medial: " " }
                  activity => !enum_type { name_type: words  members: [sedentary "lightly active"] }
                  rec => { a: activity } }"""));
        String head = "!!schema:\"https://example.test/read.tn\"\n!rec ";

        assertEquals(List.of(), tson.validate(head + "{ a: \"lightly active\" }"));

        List<Diagnostic> miss = tson.validate(head + "{ a: \"very active\" }");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, miss.getFirst().code());
        assertTrue(miss.getFirst().message()
                .contains("is not a member of this enum -- expected one of [sedentary, lightly active]"),
                    miss.getFirst().message());
    }

    /**
     * A closed set of text values is the text family's own {@code members} facet, string-class whatever its members
     * spell ([TSON-SCHEMA] §5.4), so beside an enum of names that are booleans the choice is disjoint and a value
     * goes to the variant of its own class.
     */
    @Test
    void aTextValueSetIsStringClassBesideAnEnumOfTheSameSpellings() {
        Tson tson = Tson.standard();
        tson.resolve("""
                !!id:"https://example.test/answers.tn"
                !!meta:"%s"
                { reply  => !text_type { members: ["true" "false"] }
                  bit    => !enum [true false]
                  either => ( reply | bit )
                  rec    => { e: either } }""".formatted(TsonBundledSchemas.META_ID));
        String head = "!!schema:\"https://example.test/answers.tn\"\n!rec ";

        assertEquals(List.of(), tson.validate(head + "{ e: \"true\" }"));
        assertEquals(List.of(), tson.validate(head + "{ e: true }"));
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
