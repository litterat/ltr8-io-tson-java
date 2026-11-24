package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every value a schema document reads is read at a checkpoint of that document -- its imports and its entries ready so
 * far -- in its own namespace, and a read that reaches what its readers lack stops and reruns once that entry is ready
 * ([TSON-SCHEMA] §3.3.3, §3.4.1, §5.2).
 */
class CheckpointReadTest {

    private static final String META = "!!import:\"https://tson.io/2026/40/m/meta.tn\"\n";
    private static final String CORE = "!!import:\"https://tson.io/2026/40/m/core.tn\"\n";
    private static final String LIB_IMPORT = "!!import:\"https://example.test/lib.tn\"\n";

    private static final String LIB = """
            !!id:"https://example.test/lib.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              api    => { h: text }
              route  => { kind: text =?  path: text }
              get    => route & { kind?: = "get" }
              routed => { r: route }
              coded  => { code: integer =?  note?: text }
              tagged => { c: coded }
            }
            """;

    private static String schema(String id, String imports, String declarations) {
        return "!!id:\"https://example.test/" + id + ".tn\"\n" + imports + "!schema {\n" + declarations + "}\n";
    }

    /** The host's classes for the records whose values an annotation holds: an annotation's value is bound. */
    public record Routed(Route r) {
    }

    public sealed interface Route permits Get, Put {
    }

    public record Get(String path) implements Route {
    }

    public record Put(String path) implements Route {
    }

    public record Tagged(Coded c) {
    }

    public sealed interface Coded permits Ff {
    }

    public record Ff(String note) implements Coded {
    }

    public record A(java.math.BigInteger f) {
    }

    public record Holder(BoxOfText b) {
    }

    public record BoxOfText(String v) {
    }

    private static final DataBindContext HOST = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(Map.of(
            "routed", Routed.class, "route", Route.class, "get", Get.class, "put", Put.class,
            "tagged", Tagged.class, "coded", Coded.class, "ff", Ff.class,
            "a", A.class, "holder", Holder.class))).build();

    private static Tson tson(String... documents) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("https://example.test/lib.tn", LIB);
        for (String document : documents) {
            String id = document.substring(document.indexOf('"') + 1, document.indexOf('"', document.indexOf('"') + 1));
            sources.put(id, document);
        }
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(HOST)
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(sources))));
    }

    private static List<Diagnostic> validateSchema(String document) {
        return tson(document).validateSchema(document);
    }

    private static Top entry(String document, String name) {
        String id = document.substring(document.indexOf('"') + 1, document.indexOf('"', document.indexOf('"') + 1));
        return tson(document).treeRegistry().core().resolveLinked(id).schema().entries().get(name);
    }

    // ── An annotation's name, in the reading document's namespace ─────────

    /** {@code api}'s declaring schema imports meta.tn alone; the annotating schema imports core.tn, which has it. */
    @Test
    void anAnnotationInsideAValueUnderAnImportedTypeResolvesInTheReadingDocument() {
        assertEquals(List.of(), validateSchema(schema("titled", LIB_IMPORT + CORE,
                "  orders => !api { h: @title:\"a title\" \"v\" }\n")));
    }

    @Test
    void anAnnotationInAConstructionsPayloadNamesTheDocumentsOwnType() {
        String note = "  note => text\n";
        String record = "  r => !meta.record { fields: { a => @note:\"hi\" { type: int32 } } }\n";
        for (String declarations : List.of(note + record, record + note)) {
            assertEquals(List.of(), validateSchema(schema("noted", CORE, declarations)), declarations);
        }
        String sugar = schema("sugared", CORE, "  note => text\n  r => { @note:\"hi\" a: int32 }\n");
        assertEquals(ResolvedForm.rendered(entry(sugar, "r")),
                ResolvedForm.rendered(entry(schema("noted", CORE, note + record), "r")));
    }

    // ── A member selected by its pin ──────────────────────────────────────

    /** The value is read while {@code holder} is made ready, before {@code put}: the read stops at the pin and waits. */
    @Test
    void aFamilyMemberDeclaredAfterTheValueSelectingItByPinIsWaitedOn() {
        assertEquals(List.of(), validateSchema(schema("pinned", LIB_IMPORT,
                "  holder => @routed:{ r: { kind: put  path: \"/\" } } { a: text }\n"
                        + "  put => route & { kind?: = \"put\" }\n")));
    }

    /**
     * {@code early}'s annotation compiles readers before {@code put} joins; {@code posts} is read later through them,
     * and its pin stops the read at {@code put}, ready and not held, rather than refusing the value.
     */
    @Test
    void aMemberThatJoinedAfterTheReadersWereCompiledIsSelected() {
        assertEquals(List.of(), validateSchema(schema("joined", LIB_IMPORT + CORE,
                "  early => @title:\"t\" { a: text }\n"
                        + "  posts => !routed { r: { kind: put  path: \"/\" } }\n"
                        + "  put => route & { kind?: = \"put\" }\n")));
    }

    /** An annotation naming a declaration not yet ready stops the read, which waits on it. */
    @Test
    void anAnnotationNamingADeclarationNotYetReadyIsWaitedOn() {
        assertEquals(List.of(), validateSchema(schema("later", CORE,
                "  r => !meta.record { fields: { a => @note:5 { type: int32 } } }\n"
                        + "  note => !integer ^ { min: 0 }\n")));
    }

    /** The pin is compared as a value of the selector's type, so {@code 255} selects the member pinning {@code 0xFF}. */
    @Test
    void aPinIsComparedByValueNotSpelling() {
        assertEquals(List.of(), validateSchema(schema("hex", LIB_IMPORT,
                "  holder => @tagged:{ c: { code: 255 } } { a: text }\n"
                        + "  ff => coded & { code?: = 0xFF }\n")));
    }

    /** A member whose pin differs is never waited on: the value is refused at its checkpoint as in the full closure. */
    @Test
    void aMemberWhosePinDiffersIsNotWaitedOn() {
        List<Diagnostic> problems = validateSchema(schema("unpinned", LIB_IMPORT,
                "  holder => @routed:{ r: { kind: patch  path: \"/\" } } { a: text }\n"
                        + "  put => route & { kind?: = \"put\" }\n"));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("no member of 'route' pins kind to patch"),
                problems::toString);
    }

    // ── Cycles ────────────────────────────────────────────────────────────

    /** A held value (a default) orders nothing, whatever it is tagged with. */
    @Test
    void aTagInsideAHeldValueMakesNoCycle() {
        assertEquals(List.of(), validateSchema(schema("held", META,
                "  a => !meta.record { fields: { f => { type: b  optional: true  role: DEFAULT  value: !b 5 } } }\n"
                        + "  b => @a:{} !integer ^ { min: 0 }\n")));
    }

    /** {@code holder}'s annotation selects {@code put}, which composes {@code holder}: each waits on the other. */
    @Test
    void aMemberSelectedByPinThatWaitsOnTheReaderIsAWaitCycle() {
        List<Diagnostic> problems = validateSchema(schema("cycle", LIB_IMPORT,
                "  holder => @routed:{ r: { kind: put  path: \"/\" } } { a: text }\n"
                        + "  put => route & holder & { kind?: = \"put\" }\n"));
        assertTrue(problems.stream().anyMatch(problem -> problem.code() == Diagnostic.Code.SCHEMA_ERROR
                        && problem.message().startsWith("'holder' waits on 'put' waits on 'holder'")),
                problems::toString);
    }

    // ── Readers kept across a join ───────────────────────────────────────

    /**
     * A field typed by an application written in place closes only at materialisation; a name annotation is read after
     * it, through readers compiled then, though a checkpoint's readers were compiled earlier and kept across joins.
     */
    @Test
    void aValueReadAfterMaterialisationSeesTheEntriesItRewrote() {
        assertEquals(List.of(), validateSchema(schema("derived", META,
                "  box    => <T> { v: T }\n"
                        + "  holder => { b: box<text> }\n"
                        + "  n      => !integer ^ { min: 0 }\n"
                        + "  @holder:{ b: { v: \"x\" } } r => { a: text }\n")));
    }
}
