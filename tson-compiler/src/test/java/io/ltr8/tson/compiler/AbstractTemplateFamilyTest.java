package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @abstract} on a <b>template</b> ([TSON-SCHEMA] §5.2, §5.10): {@code result => @abstract <T>
 * { payload: T }} makes every instantiation abstract, so a value at a {@code result<text>} position is a
 * value of one of that instantiation's own subtypes and must name which.
 *
 * <p><b>Why the mark can be carried where {@code @sealed} and {@code @final} cannot.</b> {@code @abstract}
 * constrains the marked type alone -- it has no direct instances -- which is true of every instantiation
 * identically, so closing an abstract template yields an abstract entry and nothing has to be recomputed.
 * The other two are claims over a <em>set of subtypes</em>, and a template has none: an instantiation entry
 * exists only where some schema wrote that application, so the claim's subject would be assembled from
 * whichever applications a closure happens to contain ({@code SPEC-FEEDBACK.md} #11).
 *
 * <p><b>The mark travels as text, because the body does.</b> §5.10 holds an open entry's body as the
 * application written out, so the mark is stated <em>in</em> that text ({@code extension: ABSTRACT}) rather
 * than beside it, and materialisation reads it back through the {@code record} constructor's own reader like
 * every other member. Nothing in the closing path knows about it.
 *
 * <p><b>The family it is abstract over is the one {@code SubtypeTemplateFamilyTest} builds</b>: a subtype
 * template's closed application gets an IS-A edge to its base's instantiation at the same argument, so
 * {@code ok<text>} is a member of {@code result<text>}'s family and not of {@code result<int32>}'s. An
 * abstract template whose family was always empty would be a type nothing could ever stand at.
 *
 * <p><b>Every member's entry name is minted, which is what makes the alias half load-bearing.</b> §8.2 makes
 * a materialised name non-normative, so {@code ok_of => ok<text>} is the only name a document has for that
 * member -- and §7.2's "after reference flattening of both" is what lets a tag spell it.
 */
class AbstractTemplateFamilyTest {

    private static final String ID = "https://example.test/abstract-template.tn";

    private static final String FAMILY = """
              result    => @abstract <T> { payload: T }
              ok        => <T> result<T> & { note: text }
              err       => <T> result<T> & { reason: text }
              result_of => result<text>
              ok_of     => ok<text>
              err_of    => err<text>
              holder    => { r: result<text> }
            """;

    private static TsonCompiledSchemaRegistry compile(String declarations) {
        String schema = """
                !!id:"https://example.test/abstract-template.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        TsonCompiledSchemaRegistry registry = TsonCompiledSchemaRegistry.tree(core);
        registry.get(ID);
        return registry;
    }

    /** The entry a `name => head<args>` alias resolves to -- what every use site flattens to. */
    private static String target(TsonCompiledSchemaRegistry registry, String alias) {
        return registry.get(ID).schema().entries().get(alias).source().orElseThrow().name();
    }

    private static RecordBody bodyOf(TsonCompiledSchemaRegistry registry, String alias) {
        return (RecordBody) registry.get(ID).schema().entries().get(target(registry, alias)).body();
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
                registry.get(ID).schema().entries().get(target(registry, "result_int")).subtypes());
    }

    // ── Reading against it ──────────────────────────────────────────────────

    /**
     * The tag is the only selector at an abstract position, and an alias is the only name this document has
     * for the member: §8.2 makes {@code ok<text>}'s minted entry name non-normative, and §12.1 gives a
     * type annotation no argument list to write {@code !ok<text>} with.
     */
    @Test
    void aTagSpellingAnAliasOfAMemberSelectsIt() {
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
     * A tag naming the base gets its own rule, and it has to reach the alias too: the base's minted name is
     * non-normative, so {@code !result_of} is the only spelling an author has for the thing being refused.
     */
    @Test
    void aTagSpellingAnAliasOfTheBaseSelectsNothing() {
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

    /** The names a refusal offers are ones a document can write -- the aliases, beside the entries' own. */
    @Test
    void theRefusalOffersTheNamesADocumentCanWrite() {
        Diagnostic refused = refusal(read(compile(FAMILY), "holder", """
                { r: { payload: "p"  note: "n" } }
                """));

        assertTrue(refused.expected().contains("ok_of"), refused.expected());
        assertTrue(refused.expected().contains("err_of"), refused.expected());
    }

    // ── What is still refused ───────────────────────────────────────────────

    /** {@code @sealed} and {@code @final} range over a set of subtypes a template does not have. */
    @Test
    void aTemplateIsStillNeitherSealedNorFinal() {
        for (String mark : List.of("sealed", "final")) {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                    () -> compile("      box => @" + mark + " <T> { @discriminator kind: identifier  v: T }\n"),
                    mark);
            assertTrue(thrown.getMessage().contains("states a closed set of subtypes"), thrown.getMessage());
        }
    }
}
