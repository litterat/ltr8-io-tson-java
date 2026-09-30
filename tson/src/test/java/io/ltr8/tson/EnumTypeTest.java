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
 * An enum is an instance of the kernel's {@code enum_type}: its members, held as text, and the {@code type} they
 * are drawn from ([TSON-SCHEMA] §7.4). {@code enum} pins {@code type: identifier} and {@code text_enum} pins
 * {@code type: text}, and {@code !enum_type { type: T  members: [...] }} names any text family. Each member must
 * be a value of {@code T}, and whether {@code T} is an identifier family decides whether the members are names
 * that [TSON-DATA] §8.2's hygiene reaches.
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
        assertTrue(messages.contains("is not a value of its type 'identifier'"), messages);
        // §7.4: the diagnostic names the spelling that admits the member.
        assertTrue(messages.contains("'!text_enum [...]'"), messages);
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
                  strict => !names ^ { type: identifier } }"""));
        assertTrue(messages(pinned).contains("'type' is fixed on 'text_enum'"), messages(pinned));

        // Through `enum_type` itself, the enum's own narrowing rule does.
        List<Diagnostic> free = Tson.standard().validateSchema(schema("retype-free", """
                { names => !enum_type { type: text  members: [OPEN DONE] }
                  strict => !names ^ { type: identifier } }"""));
        assertTrue(messages(free).contains("an enum's type is fixed where it is constructed"), messages(free));
    }

    /**
     * An ordinary schema enumerates labels of a naming vocabulary it declares itself: an author-written
     * {@code type} resolves in the author's own namespace, and every member is held to it.
     */
    @Test
    void anOrdinarySchemaEnumeratesItsOwnNamingVocabulary() {
        Tson tson = Tson.standard();
        String kebab = """
                { kebab => !identifier ^ { pattern: "[a-z]+(-[a-z]+)*" }
                  steps => !enum_type { type: kebab  members: [make-tea drink-tea] }
                  rec   => { s: steps } }""";
        tson.resolve(schema("steps", kebab));
        String head = "!!schema:\"https://example.test/steps.tn\"\n!rec ";
        assertEquals(List.of(), tson.validate(head + "{ s: make-tea }"));

        List<Diagnostic> refused = Tson.standard().validateSchema(schema("steps-bad", """
                { kebab => !identifier ^ { pattern: "[a-z]+(-[a-z]+)*" }
                  steps => !enum_type { type: kebab  members: [make-tea Drink] } }"""));
        assertTrue(messages(refused).contains("member 'Drink' is not a value of its type 'kebab'"),
                messages(refused));
    }

    /**
     * An enum's labels are drawn from a text family, so a type on another family is refused at schema load --
     * a numeric value set is {@code integer}'s own {@code members} facet.
     */
    @Test
    void aTypeThatIsNotATextFamilyIsRefused() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("numbers", """
                { ports => !enum_type { type: integer  members: ["80" "443"] } }"""));

        assertTrue(messages(diagnostics).contains("its type 'integer' is not a text family"),
                messages(diagnostics));
    }

    @Test
    void aTypeNamingNothingIsRefused() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("nothing", """
                { e => !enum_type { type: no_such_type  members: [a] } }"""));

        assertTrue(messages(diagnostics).contains("its type 'no_such_type' names nothing in scope"),
                messages(diagnostics));
    }

    /**
     * A meta layer's own tightening of {@code enum_type} needs no class of its own: it adds no field, so it binds
     * as the constructor it tightens. The pinned {@code type} resolves where the meta layer wrote it, so a
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
                { kebab      => !identifier ^ { pattern: "[a-z]+(-[a-z]+)*" }
                  kebab_enum => enum_type ^ { type?: = kebab } }
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
        assertTrue(messages(tson.validateSchema(bad)).contains("member 'Drink' is not a value of its type 'kebab'"),
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
