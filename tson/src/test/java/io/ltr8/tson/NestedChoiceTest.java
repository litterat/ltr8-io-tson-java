package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A variant that is a choice is disjoint, and a tag naming it is spent selecting it ([TSON-SCHEMA] §5.4): the value
 * carries one tag ([TSON-DATA] §3.2), so it is read at the nested choice untagged.
 */
class NestedChoiceTest {

    private static final String ID = "https://example.test/nested-choice.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/nested-choice.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              narrow       => ( int32 | text )
              narrow_alias => narrow
              outer        => ( boolean | narrow )
              by_alias     => ( boolean | narrow_alias )
              inner        => ( int32 | [text] )
              mid          => ( text | inner )
              h => { o?: outer  al?: by_alias  f?: ( boolean | narrow )  m?: mid  os?: [outer] }
            }
            """;

    private static final Tson TSON = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA)));

    private static List<Diagnostic> validate(String body) {
        return TSON.validate("!!import:\"" + ID + "\"\n!h " + body);
    }

    private static TsonValue read(String body) {
        return TSON.treeReader().read("!!import:\"" + ID + "\"\n!h " + body);
    }

    private static void valid(String body) {
        assertEquals(List.of(), validate(body).stream().map(Diagnostic::message).toList(), body);
    }

    private static Diagnostic refusal(String body) {
        List<Diagnostic> problems = validate(body);
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    // ── the schema (§5.4) ──

    private static List<Diagnostic> schemaProblems(String declarations) {
        return Tson.standard().validateSchema("""
                !!id:"https://example.test/nested-choice-refused.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                  a      => { x: text }
                  b      => { y: text }
                  either => ( a | b )
                %s
                }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations));
    }

    private static Diagnostic schemaRefusal(String declarations) {
        List<Diagnostic> found = schemaProblems(declarations);
        assertEquals(1, found.size(), found::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, found.getFirst().code(), found::toString);
        return found.getFirst();
    }

    @Test
    void aVariantThatIsAChoiceNotDisjointIsRefusedAtTheDeclaration() {
        Diagnostic refused = schemaRefusal("  outer => ( text | either )");

        assertTrue(refused.message().startsWith("'outer': the choice '( text | either )' has a variant [either]"),
                refused.message());
        assertTrue(refused.message().contains("'( text | a | b )'"), refused.message());
        assertTrue(refused.message().contains("§5.4"), refused.message());
        assertEquals(Optional.of("/outer"), refused.schemaPointer());
        assertTrue(refused.schemaPosition().isPresent(), refused::toString);
    }

    @Test
    void theVariantIsJudgedAtTheEndOfItsAliasChain() {
        Diagnostic refused = schemaRefusal("  either_alias => either\n  outer => ( text | either_alias )");

        assertTrue(refused.message().contains("[either_alias]"), refused.message());
        assertTrue(refused.message().contains("'( text | a | b )'"), refused.message());
    }

    @Test
    void aChoiceWrittenInPlaceAtAFieldIsJudged() {
        Diagnostic refused = schemaRefusal("  r => { f: ( text | either ) }");

        assertTrue(refused.message().startsWith("'r': the choice '( text | either )'"), refused.message());
        assertTrue(schemaRefusal("  r => { f: [( text | either )] }").message().startsWith("'r':"));
    }

    /** A choice holding a choice is not disjoint, so one holding it in turn is refused. */
    @Test
    void aChoiceTwoDeepIsRefused() {
        Diagnostic refused = schemaRefusal("""
                  inner => ( int32 | [text] )
                  mid   => ( text | inner )
                  deep  => ( boolean | mid )""");

        assertTrue(refused.message().startsWith("'deep':"), refused.message());
        assertTrue(refused.message().contains("'( boolean | text | inner )'"), refused.message());
    }

    @Test
    void aFailedDisjointAssertionKeepsItsOwnDiagnostic() {
        assertTrue(schemaRefusal("  outer => @meta.disjoint ( text | either )").message()
                .contains("asserts @meta.disjoint"));
    }

    @Test
    void aDisjointNestedChoiceLoads() {
        assertEquals(List.of(), Tson.standard().validateSchema(SCHEMA));
    }

    /** Refused at load, the schema reaches neither read mode. */
    @Test
    void neitherReadModeReadsAgainstARefusedSchema() {
        String refused = """
                !!id:"https://example.test/nested-choice-refused.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  a      => { x: text }
                  b      => { y: text }
                  either => ( a | b )
                  outer  => ( text | either )
                  h      => { o: outer }
                }
                """;
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> refused)));
        String document = "!!import:\"https://example.test/nested-choice-refused.tn\"\n!h { o: !either !a { x: \"1\" } }";
        List<Diagnostic> problems = tson.validate(document);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(problem -> problem.message().contains("has a variant [either]")),
                problems::toString);
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> tson.objectReader().read(document, Object.class)).getMessage().contains("has a variant [either]"));
    }

    // ── through an extern (§5.13) ──

    private static final String FOREIGN = "https://example.test/nested-choice-foreign.tn";

    private static final String EXTERNS = "https://example.test/nested-choice-externs.tn";

    private static String externed(String declarations) {
        return """
                !!id:"%s"
                !!import:"%s"
                !!import:"%s"
                !schema {
                  a      => { x: text }
                  b      => { y: text }
                  either => ( a | b )
                %s
                }""".formatted(EXTERNS, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations);
    }

    private static String extern(String entry, String type) {
        return "  " + entry + " => !meta.extern { schema: \"" + FOREIGN + "\"  type: " + type + " }\n";
    }

    /** A processor serving {@code subject} and a foreign schema whose choices it names through externs. */
    private static Tson withForeign(String subject) {
        Map<String, String> documents = Map.of(FOREIGN, """
                !!id:"%s"
                !!import:"%s"
                !schema {
                  a      => { x: text }
                  b      => { y: text }
                  either => ( a | b )
                  held   => either
                  narrow => ( int32 | text )
                }""".formatted(FOREIGN, TsonBundledSchemas.CORE_ID), EXTERNS, subject);
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of((SchemaSource) documents::get)));
    }

    private static Diagnostic externRefusal(String declarations) {
        String subject = externed(declarations);
        List<Diagnostic> found = withForeign(subject).validateSchema(subject);
        assertEquals(1, found.size(), found::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, found.getFirst().code(), found::toString);
        return found.getFirst();
    }

    @Test
    void aVariantThatIsAnExternOfAChoiceNotDisjointIsRefusedAtTheDeclaration() {
        Diagnostic refused = externRefusal(extern("either_x", "either") + "  pick => ( text | either_x )");

        assertTrue(refused.message().startsWith("'pick': the choice '( text | either_x )' has a variant [either_x]"),
                refused.message());
        assertTrue(refused.message().endsWith("name each of its variants by an extern of its own and list those in "
                + "its place"), refused.message());
        assertEquals(Optional.of("/pick"), refused.schemaPointer());
    }

    /** A local alias, an extern and a foreign alias are hops alike, followed in any order. */
    @Test
    void aLocalAliasOfAnExternOfAForeignAliasIsFollowedToTheChoice() {
        Diagnostic refused = externRefusal(extern("held_x", "held") + "  mine => held_x\n  pick => ( text | mine )");

        assertTrue(refused.message().startsWith("'pick': the choice '( text | mine )' has a variant [mine]"),
                refused.message());
        assertTrue(refused.message().contains("by an extern of its own"), refused.message());
    }

    /** The local choice's variants are names here, and the foreign one's are not: each takes its own advice. */
    @Test
    void aLocalAndAForeignNestedChoiceEachTakeTheirOwnAdvice() {
        Diagnostic refused = externRefusal(extern("either_x", "either") + "  pick => ( text | either | either_x )");

        assertTrue(refused.message().endsWith("list its variants in its place, '( text | a | b | either_x )', naming "
                + "each variant of [either_x] by an extern of its own"), refused.message());
    }

    @Test
    void aVariantThatIsAnExternOfADisjointChoiceLoadsAndReads() {
        String subject = externed(extern("narrow_x", "narrow") + "  outer => ( boolean | narrow_x )\n"
                + "  h => { o?: outer }");
        Tson tson = withForeign(subject);

        assertEquals(List.of(), tson.validateSchema(subject));
        String document = "!!import:\"" + EXTERNS + "\"\n!h { o: !narrow_x 5 }";
        assertEquals(List.of(), tson.validate(document).stream().map(Diagnostic::message).toList());
        assertEquals(5, tson.treeReader().read(document).get("o").asInt().orElseThrow());
    }

    // ── the text reader, tree mode ──

    @Test
    void aTagNamingANestedChoiceSelectsItAndTheValueReadsThereUntagged() {
        valid("{ o: !narrow 5 }");
        valid("{ o: !narrow \"s\" }");
        valid("{ o: !boolean true }");
        valid("{ al: !narrow_alias 5  f: !narrow \"s\" }");
        valid("{ os: [ !narrow 5  !narrow \"s\"  !boolean false ] }");
        valid("{ o: @doc:\"kept\" !narrow 5 }");

        TsonValue number = read("{ o: !narrow 5 }").get("o");
        assertEquals(Optional.of("int32"), number.typeRef());
        assertEquals(5, number.asInt().orElseThrow());
        TsonValue text = read("{ o: !narrow \"s\" }").get("o");
        assertEquals(Optional.of("text"), text.typeRef());
        assertEquals(Optional.of("s"), text.asString());
        assertEquals(1, read("{ o: @doc:\"kept\" !narrow 5 }").get("o").annotations().size());
    }

    @Test
    void aTagNamingTheNestedChoicesVariantIsRefused() {
        Diagnostic refused = refusal("{ o: !int32 5 }");

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertEquals(Optional.of("/o"), refused.path());
        assertTrue(refused.message().contains("'int32' is not a declared variant of 'outer'"), refused.message());
    }

    @Test
    void anUntaggedValueAtTheOuterChoiceIsRefused() {
        Diagnostic refused = refusal("{ o: 5 }");

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertEquals(Optional.of("/o"), refused.path());
        assertTrue(refused.message().contains("requires an explicit type annotation"), refused.message());
    }

    /** One tag names the outer variant; the nested choice reads the value untagged, so a second tag is the value's. */
    @Test
    void theValueAtTheNestedChoiceIsJudgedThere() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal("{ o: !narrow true }").code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal("{ o: !narrow [1] }").code());
    }

    /** At {@code mid}, one deep, a tag naming {@code inner} is spent as at any choice. */
    @Test
    void aNestedChoiceOneDeepIsReachedFromAChoiceThatIsNotDisjoint() {
        valid("{ m: !inner 5 }");
        valid("{ m: !inner [\"a\"] }");
        valid("{ m: !text \"s\" }");
        assertEquals(Optional.of("int32"), read("{ m: !inner 5 }").get("m").typeRef());
    }

    // ── bind mode ──

    /** A choice of atoms binds to {@code Object}, which the context registers as an atom so the binder takes it whole. */
    public record H(Object o, Object al, Object f, Object m, List<Object> os) {
    }

    private static H bind(String body) {
        Tson bound = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA))
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("h", H.class)).orElse(SchemaModelNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).registerAtom(Object.class).build()));
        return bound.objectReader().read("!!import:\"" + ID + "\"\n!h " + body, H.class);
    }

    @Test
    void aBoundNestedChoiceReceivesTheValueItsVariantReads() {
        H read = bind("{ o: !narrow 5  al: !narrow_alias \"s\"  f: !boolean true  m: !inner 7  os: [ !narrow \"t\" ] }");

        assertEquals(5, read.o());
        assertEquals("s", read.al());
        assertEquals(true, read.f());
        assertEquals(7, read.m());
        assertEquals(List.of("t"), read.os());
    }
}
