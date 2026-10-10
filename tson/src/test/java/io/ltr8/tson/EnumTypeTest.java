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
 * they are drawn from ([TSON-SCHEMA] §7.4). {@code enum} pins {@code name_type: identifier} and {@code text_enum}
 * pins {@code name_type: text}, and {@code !enum_type { name_type: T  members: [...] }} names any text family. Each
 * member must be a value of {@code T}, and whether {@code T} is an identifier family decides whether the members
 * are names that [TSON-DATA] §8.2's hygiene reaches.
 *
 * <p><b>The declaration decides, never the shape of the members.</b> {@code !enum [...]} keeps exactly what it
 * had, hygiene included, and an author reaching for a value set of arbitrary text says so by writing
 * {@code !text_enum}: a resolver that inferred "these look like names, so police them" would switch a spoofing
 * check on and off by accident.
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
        // §7.4: the diagnostic names the spelling that admits the member.
        assertTrue(messages.contains("'!text_enum [...]'"), messages);
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

    /** {@code text_enum}'s type is {@code text}, which admits what no identifier rule would. */
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

    /** A refinement narrows the member set; the type is fixed where the enum was constructed. */
    @Test
    void aRefinementNarrowsTheMembersAndNeverTheType() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("narrow", """
                { cities => !text_enum ["new york" "los angeles"]
                  east   => !cities ^ { members: ["new york"] } }""")));

        // Through a tightening that pins the type, the constructor itself refuses another value.
        List<Diagnostic> pinned = Tson.standard().validateSchema(schema("retype-pinned", """
                { names => !text_enum [OPEN DONE]
                  strict => !names ^ { name_type: identifier } }"""));
        assertTrue(messages(pinned).contains("'name_type' is fixed on 'text_enum'"), messages(pinned));

        // Through `enum_type` itself, the enum's own narrowing rule does.
        List<Diagnostic> free = Tson.standard().validateSchema(schema("retype-free", """
                { names => !enum_type { name_type: text  members: [OPEN DONE] }
                  strict => !names ^ { name_type: identifier } }"""));
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
     * An enum's labels are drawn from a text family, so a type on another family is refused at schema load --
     * a numeric value set is {@code integer}'s own {@code members} facet.
     */
    @Test
    void aTypeThatIsNotATextFamilyIsRefused() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("numbers", """
                { ports => !enum_type { name_type: integer  members: ["80" "443"] } }"""));

        assertTrue(messages(diagnostics).contains("its name_type 'integer' is not a text family"),
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

    /**
     * An enum whose members are texts is string-class whatever they spell ([TSON-SCHEMA] §7.4), so beside an
     * enum of names that are booleans the choice is disjoint and a value goes to the variant of its own class.
     * The schema imports nothing: {@code text_enum}'s {@code text} is reached only through the governing meta,
     * where the constructor pinned it.
     */
    @Test
    void aTextEnumIsStringClassWhereItsTypeIsReachedOnlyThroughTheMeta() {
        Tson tson = Tson.standard();
        tson.resolve("""
                !!id:"https://example.test/answers.tn"
                !!meta:"%s"
                { reply  => !text_enum ["true" "false"]
                  bit    => !enum [true false]
                  either => ( reply | bit )
                  rec    => { e: either } }""".formatted(TsonBundledSchemas.META_ID));
        String head = "!!schema:\"https://example.test/answers.tn\"\n!rec ";

        assertEquals(List.of(), tson.validate(head + "{ e: \"true\" }"));
        assertEquals(List.of(), tson.validate(head + "{ e: true }"));
    }

    /** The class is judged where the enum is declared and travels with it through {@code !!import}. */
    @Test
    void anImportedTextEnumKeepsItsClass() {
        String libraryId = "https://example.test/answer-library.tn";
        String userId = "https://example.test/answer-user.tn";
        String library = """
                !!id:"%s"
                !!meta:"%s"
                { reply => !text_enum ["true" "false"] }
                """.formatted(libraryId, TsonBundledSchemas.META_ID);
        String user = """
                !!id:"%s"
                !!meta:"%s"
                !!import:"%s"
                !!import:"%s"
                { either => ( reply | boolean )
                  rec    => { e: either } }
                """.formatted(userId, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, libraryId);
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of(libraryId, library, userId, user)))));
        String head = "!!schema:\"" + userId + "\"\n!rec ";

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
