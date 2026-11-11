package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code abstract} on a <b>template</b> ([TSON-SCHEMA] §5.2, §5.10): {@code result => abstract <T>
 * { payload: T }} makes every instantiation abstract, so a value at a {@code result<text>} position is a
 * value of one of that instantiation's own subtypes and must name which.
 *
 * <p><b>The mark is each application's {@code extension}.</b> A template is no type, so {@code abstract} on one
 * says nothing of the template itself: closing carries it, with the template's selectors, to the record each
 * application denotes. {@code final} is refused on a template, which is applied to be composed (§5.2, §5.10).
 *
 * <p><b>The family it is abstract over is the one {@code SubtypeTemplateFamilyTest} builds</b>: a subtype
 * template's closed application gets an IS-A edge to its base's instantiation at the same argument, so
 * {@code ok<text>} is a member of {@code result<text>}'s family and not of {@code result<int32>}'s. An
 * abstract template whose family was always empty would be a type nothing could ever stand at.
 *
 * <p><b>Every member is the author's declaration.</b> {@code ok_of => ok<text>} is the record {@code ok<text>}
 * closes to, under the name the author wrote (§8.2), so a tag names a member as it names any declared record.
 */
class AbstractTemplateFamilyTest {

    private static final String ID = "https://example.test/abstract-template.tn";

    private static final String FAMILY = """
              result    => abstract <T> { payload: T }
              ok        => <T> result<T> & { note: text }
              err       => <T> result<T> & { reason: text }
              result_of => result<text>
              ok_of     => ok<text>
              err_of    => err<text>
              holder    => { r: result_of }
            """;

    private static TsonCompiledSchemaRegistry compile(String declarations) {
        String schema = """
                !!id:"https://example.test/abstract-template.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
        TsonCompiledSchemaRegistry registry = TsonCompiledSchemaRegistry.tree(core);
        registry.get(ID);
        return registry;
    }

    /**
     * The entry {@code alias} denotes, past one alias hop: a {@code name => head<args>} declaration is itself the
     * record its application closes to (§8.2).
     */
    private static String target(TsonCompiledSchemaRegistry registry, String alias) {
        return registry.get(ID).schema().entries().get(alias)
                instanceof io.ltr8.tson.schema.meta.Alias ref ? ref.target().name() : alias;
    }

    private static RecordBody bodyOf(TsonCompiledSchemaRegistry registry, String alias) {
        return (RecordBody) registry.get(ID).schema().entries().get(target(registry, alias));
    }

    private static List<Diagnostic> read(TsonCompiledSchemaRegistry registry, String rootType, String document) {
        List<Diagnostic> problems = new ArrayList<>();
        new TsonTreeReader(registry).withDiagnostics(problems::add).withSchema(ID).readAs(document, rootType);
        return List.copyOf(problems);
    }

    private static Diagnostic refusal(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "expected exactly one problem, got " + problems);
        return problems.getFirst();
    }

    // ── The mark, closed ────────────────────────────────────────────────────

    @Test
    void closingAnAbstractTemplateYieldsAnAbstractInstantiation() {
        TsonCompiledSchemaRegistry registry = compile(FAMILY);

        assertEquals(RecordExtensionType.ABSTRACT, bodyOf(registry, "result_of").extension());
    }

    /** Extensibility is never inherited (§5.2), and a closed subtype template is where that is easiest to lose. */
    @Test
    void aSubtypeOfAnAbstractTemplateClosesOpen() {
        TsonCompiledSchemaRegistry registry = compile(FAMILY);

        assertEquals(RecordExtensionType.OPEN, bodyOf(registry, "ok_of").extension());
        assertEquals(RecordExtensionType.OPEN, bodyOf(registry, "err_of").extension());
    }

    /** Every argument gets its own abstract base, which is the point of carrying the mark rather than the head. */
    @Test
    void eachInstantiationIsAbstractOverItsOwnFamily() {
        TsonCompiledSchemaRegistry registry = compile(FAMILY + """
                      result_int => result<int32>
                      ok_int     => ok<int32>
                """);

        assertEquals(RecordExtensionType.ABSTRACT, bodyOf(registry, "result_int").extension());
        assertEquals(List.of(target(registry, "ok_int")),
                TypeChain.subtypesOf(registry.get(ID).schema().entries().get(target(registry, "result_int"))));
    }

    // ── Reading against it ──────────────────────────────────────────────────

    /**
     * The tag is the only selector at an abstract position, and it spells the member's declared name: §12.1 gives
     * a type annotation no argument list to write {@code !ok<text>} with.
     */
    @Test
    void aTagSpellingAMembersDeclaredNameSelectsIt() {
        assertEquals(List.of(), read(compile(FAMILY), "holder", """
                { r: !ok_of { payload: "p"  note: "n" } }
                """));
    }

    /** An untagged value: §5.2's whole content, and it fails before the record's shape is consulted. */
    @Test
    void anUntaggedValueAtAnAbstractInstantiationIsRefused() {
        Diagnostic refused = refusal(read(compile(FAMILY), "holder", """
                { r: { payload: "p"  note: "n" } }
                """));

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertEquals("/r", refused.path().orElseThrow());
        assertTrue(refused.message().contains("is abstract and has no direct instances"), refused.message());
    }

    /**
     * A tag naming the base gets its own rule: the base is abstract, so {@code !result_of} selects nothing.
     */
    @Test
    void aTagSpellingTheBasesDeclaredNameSelectsNothing() {
        Diagnostic refused = refusal(read(compile(FAMILY), "holder", """
                { r: !result_of { payload: "p" } }
                """));

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertTrue(refused.message().contains("selects nothing"), refused.message());
    }

    /** A member of another argument's family is not a member of this one. */
    @Test
    void aTagNamingAnotherArgumentsMemberIsNotASubtype() {
        Diagnostic refused = refusal(read(compile(FAMILY + "      ok_int => ok<int32>\n"), "holder", """
                { r: !ok_int { payload: 1  note: "n" } }
                """));

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertTrue(refused.message().contains("is not admissible"), refused.message());
    }

    /** The names a refusal offers are ones a document can write -- the members' declared names. */
    @Test
    void theRefusalOffersTheNamesADocumentCanWrite() {
        Diagnostic refused = refusal(read(compile(FAMILY), "holder", """
                { r: { payload: "p"  note: "n" } }
                """));

        assertTrue(refused.expected().contains("ok_of"), refused.expected());
        assertTrue(refused.expected().contains("err_of"), refused.expected());
    }

    // ── What is still refused ───────────────────────────────────────────────

    /**
     * {@code abstract} <b>is</b> a claim with a subject on a template (§5.10): a
     * template carrying {@code extension} takes part in IS-A and {@code subtypes} holds its own
     * instantiations, which is the set the claim ranges over. Only {@code final} still cannot hold --
     * every application is a subtype of the template by construction, so the claim is false before an
     * author writes anything else.
     */
    @Test
    void aTemplateMayStateASelectorButNeverBeFinal() {
        assertNotNull(compile("      box => abstract <T> { kind: text =?  v: T }\n"),
                "abstract states the fact the derivation reaches anyway");

        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("      box => final <T> { kind: text =?  v: T }\n"));
        assertTrue(thrown.getMessage().contains("is refused on a template"), thrown.getMessage());
    }
}
