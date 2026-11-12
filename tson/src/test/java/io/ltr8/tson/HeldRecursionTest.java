package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An open record holding, where it closes in place, an application of a recursive open record whose recursion does not
 * pass back through it could never be closed, and is refused at its own declaration ([TSON-SCHEMA] §5.10.1): whether or
 * not anything applies it, once, and never again at an applier.
 */
class HeldRecursionTest {

    private static final String ID = "https://example.test/held-recursion.tn";

    private static final String NODE = "  node => <T> { value: T  next?: node<T> }\n";

    private static String schema(String declarations) {
        return """
                !!id:"%s"
                !!import:"%s"
                !!import:"%s"
                !schema {
                %s
                }""".formatted(ID, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations);
    }

    private static List<Diagnostic> problems(String declarations) {
        return Tson.standard().validateSchema(schema(declarations));
    }

    /** One schema error at each of {@code entries}, in order, the first's message holding each fragment. */
    private static void refusedAt(String declarations, List<String> entries, String... fragments) {
        List<Diagnostic> found = problems(declarations);
        assertEquals(entries.stream().map(entry -> Optional.of("/" + entry)).toList(),
                found.stream().map(Diagnostic::schemaPointer).toList(), found::toString);
        found.forEach(problem -> assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code(), found::toString));
        for (String fragment : fragments) {
            assertTrue(found.getFirst().message().contains(fragment), found.getFirst().message());
        }
    }

    private static void refusedAt(String declarations, String entry, String... fragments) {
        refusedAt(declarations, List.of(entry), fragments);
    }

    // ── Refused at its own declaration ─────────────────────────────────────────────────────────

    /** Applied nowhere, it is refused all the same, naming the held application, the record and the remedy. */
    @Test
    void anOpenRecordHoldingARecursiveOneIsRefusedWhereItIsDeclared() {
        refusedAt(NODE + "  wrap => <T> { label: text  n: node<T> }", "wrap",
                "'wrap' holds 'node<T>' at 'n', an application of the recursive open record 'node'",
                "a recursion ties only to a declaration closing that record itself (§5.10.1)",
                "'wrap' could never be closed", "write 'n: N', 'N' a parameter of 'wrap'",
                "a declared application of 'node' ('my_node => node<...>', passed for 'N')");
    }

    /** Applied, it is still reported once, at the open record, in either declaration order; the applier says nothing. */
    @Test
    void itsApplierReportsNothingInEitherOrder() {
        String wrap = "  wrap => <T> { label: text  n: node<T> }\n";
        String applied = "  node_text => node<text>\n  wrap_text => wrap<text>\n";
        refusedAt(NODE + wrap + applied, "wrap");
        refusedAt(applied + wrap + NODE, "wrap");
        refusedAt(NODE + wrap + "  r => { f: wrap<text> }", "wrap");
        refusedAt("  r => { f: wrap<text> }\n" + wrap + NODE, "wrap");
    }

    /** Whether the held application's arguments name a parameter does not matter. */
    @Test
    void aHeldApplicationNamingNoParameterIsRefusedTheSameWay() {
        refusedAt(NODE + "  wrap => <T> { v: T  n: node<text> }", "wrap",
                "'wrap' holds 'node<text>' at 'n'", "('node_text => node<text>', passed for 'N')");
    }

    /** Held inside a container form, it closes in place at the container's type slot. */
    @Test
    void aHeldApplicationInsideAContainerIsRefused() {
        refusedAt(NODE + "  list => <T> { items: [node<T>] }", "list", "'list' holds 'node<T>' at 'items'",
                "write a parameter 'N' of 'list' where 'node<T>' stands");
        refusedAt(NODE + "  index => <T> { m: {text => node<T>} }", "index", "'index' holds 'node<T>' at 'm'");
        refusedAt(NODE + "  list => <T> { items: meta.array<node<T>> }", "list", "'list' holds 'node<T>'");
        refusedAt(NODE + "  list => <T> { v: T  items: [node<text>; 1..] }", "list", "'list' holds 'node<text>'");
    }

    /**
     * An applied parent's fields are the composing record's own, so {@code labelled} holds {@code next: node<T>}; the
     * parent is absorbed and closes nowhere, so {@code labelled} is on no cycle of {@code node}'s and could never be
     * closed: closing {@code labelled<text>} closes {@code node<text>} in place, which reaches itself.
     */
    @Test
    void aRecursionTakenThroughAnAppliedParentIsRefused() {
        refusedAt(NODE + "  labelled => <T> node<T> & { label: text }", "labelled",
                "'labelled' holds 'node<T>' at 'next'");
        refusedAt(NODE + "  labelled => <T> node<T> ^ { value: T }", "labelled", "'labelled' holds 'node<T>'");
    }

    /**
     * Two hops out, {@code outer} reaches {@code node} only through {@code wrap}, which is refused for it: the one
     * report is {@code wrap}'s, whose remedy is the whole fix, and {@code outer} and its applier close against the
     * refused record's placeholder.
     */
    @Test
    void twoHopsOutTheOneReportIsTheRecordHoldingTheRecursion() {
        String declarations = "  outer => <T> { w: wrap<T> }\n  wrap => <T> { label: text  n: node<T> }\n" + NODE;
        refusedAt(declarations, "wrap", "'wrap' holds 'node<T>'");
        refusedAt(declarations + "  outer_text => outer<text>", "wrap");
        refusedAt("  outer_text => outer<text>\n" + declarations, "wrap");
    }

    /**
     * A record on one cycle of a recursive record but not another is refused: closing {@code a<text>} ties {@code
     * b}'s {@code a<text>}, but {@code b<text>} closes in place and reaches itself through {@code c}. {@code b} lies on
     * every cycle it reaches, and closes.
     */
    @Test
    void aRecordOffOneOfACyclesLoopsIsRefused() {
        refusedAt("""
                  a => <T> { v: T  b: b<T> }
                  b => <T> { c: c<T>  a?: a<T> }
                  c => <T> { b?: b<T> }
                  b_text => b<text>""", List.of("a", "c"), "'a' holds 'b<T>' at 'b'");
    }

    /** An imported recursive open record holds the same way. */
    @Test
    void anImportedRecursiveOpenRecordIsHeldTheSameWay() {
        String nodes = """
                !!id:"https://example.test/nodes.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                %s}""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, NODE);
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(
                SchemaSource.ofMap(Map.of("https://example.test/nodes.tn", nodes)))));
        String importing = schema("  wrap => <T> { label: text  n: node<T> }\n  wrap_text => wrap<text>")
                .replace("!schema", "!!import:\"https://example.test/nodes.tn\"\n!schema");
        List<Diagnostic> found = tson.validateSchema(importing);
        assertEquals(List.of(Optional.of("/wrap")), found.stream().map(Diagnostic::schemaPointer).toList(),
                found::toString);
        assertTrue(found.getFirst().message().contains("recursive open record 'node'"), found::toString);
        assertEquals(List.of(), tson.validateSchema(schema("  node_text => node<text>")
                .replace("!schema", "!!import:\"https://example.test/nodes.tn\"\n!schema")));
    }

    // ── What still closes ──────────────────────────────────────────────────────────────────────

    /** Recursion itself, direct and mutual, is on its own cycle, applied or not. */
    @Test
    void recursiveAndMutuallyRecursiveRecordsAreAccepted() {
        assertEquals(List.of(), problems(NODE + "  node_text => node<text>"));
        assertEquals(List.of(), problems(NODE));
        String mutual = "  a => <T> { v: T  b: b<T> }\n  b => <T> { a?: a<T> }\n";
        assertEquals(List.of(), problems(mutual));
        assertEquals(List.of(), problems(mutual + "  a_text => a<text>\n  b_text => b<text>"));
    }

    /** A closed record holding the application keeps its own refusal, whose remedy it can follow. */
    @Test
    void aClosedRecordHoldingTheApplicationKeepsItsRefusal() {
        refusedAt(NODE + "  r => { f: node<text> }", "r", "'node<text>' applies the recursive open record 'node'",
                "'node_text => node<text>'");
    }

    /** The remedy: the closed type taken as a parameter and applied to a declared one, which reads data. */
    @Test
    void theParameterWorkaroundClosesAndReads() {
        String declarations = NODE + """
                  wrap      => <N> { label: text  n: N }
                  node_text => node<text>
                  wrap_text => wrap<node_text>""";
        assertEquals(List.of(), problems(declarations));
        String text = schema(declarations);
        SchemaSource source = uri -> text;
        TsonValue read = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))).treeReader()
                .read("!!import:\"" + ID + "\"\n!wrap_text { label: l  n: { value: a  next: { value: b } } }");
        assertEquals("b", read.get("n").get("next").get("value").asString().orElseThrow(), read::toString);
    }
}
