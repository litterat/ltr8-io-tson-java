package io.ltr8.tson.perf;

import io.ltr8.tson.compiler.TsonUnicodePolicy;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.TsonDataEmitter;
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonTreeReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the <b>bind</b> read path allocates, and what of it survives -- the harness this repo had no answer
 * from, and the reason a per-character {@code Matcher} sat in the write path unnoticed.
 *
 * <p>The shape under test is a server's: one {@link Tson}, one schema resolved and compiled at startup, then
 * the same document read thousands of times. Two questions, deliberately separate:
 *
 * <ul>
 *   <li><b>Does anything survive?</b> A read is transient by design -- the compiled schema, the reader graph
 *       and the bind descriptors are built once and meant to live for the process, and nothing a read
 *       <em>produces</em> should join them. {@link #manyReadsOfOneSchemaRetainNothing} measures settled heap
 *       across thousands of reads, and {@link #everyReadResultBecomesCollectable} asks the same question
 *       with weak references, where no measurement noise can soften the answer. A cache keyed per document
 *       -- the classic accidental leak -- fails both.
 *   <li><b>How much garbage?</b> {@link #transientAllocationPerReadIsBounded} reports bytes per read and
 *       fails only on a gross regression. It is a ratchet against a 50x mistake, not a budget: a tight
 *       number here would break on a JDK upgrade and teach everyone to raise it.
 * </ul>
 *
 * <p>Thresholds are loose on purpose in both directions -- see {@link AllocationProbe} for what each number
 * can and cannot tell you, and for the Flight Recorder flags when a number here says "something changed" and
 * the next question is "where".
 *
 * <p>Run it alone, with the report on stdout:
 * <pre>{@code ./gradlew :tson:allocationReport}</pre>
 */
class AllocationHarnessTest {

    private static final String ID = "https://example.test/orders-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/orders-1.tn"
            !!meta:"https://tson.io/2026/33/m/meta.tn"
            !!import:"https://tson.io/2026/33/m/core.tn"
            {
              order => { id: uuid  customer: text  placed: datetime  lines: [line]  note: text }
              line => { sku: text  quantity: int32  price: float64 }
            }
            """;

    private static final String DOCUMENT = """
            !!schema:"https://example.test/orders-1.tn"
            !order {
              id: "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09"
              customer: "Ada Lovelace"
              placed: "2026-08-24T10:00:00Z"
              lines: [
                { sku: "A-1"  quantity: 2  price: 9.99 }
                { sku: "B-7"  quantity: 1  price: 129.5 }
                { sku: "C-3"  quantity: 12  price: 0.75 }
              ]
              note: "leave with the neighbour"
            }""";

    public record Line(String sku, int quantity, double price) {
    }

    public record Order(UUID id, String customer, OffsetDateTime placed, List<Line> lines, String note) {
    }

    private static Tson tson;
    private static TsonObjectReader reader;

    @BeforeAll
    static void startUp() {
        assumeTrue(AllocationProbe.supported(), "needs HotSpot's per-thread allocation counter");

        TsonSchemaSource source = uri -> SCHEMA;
        tson = Tson.builder().schemaSource(source)
                .bindings(Map.of("order", Order.class, "line", Line.class))
                .build();
        reader = tson.objectReader();

        // Everything a first read builds -- the compiled schema, the reader graph, the bind descriptors --
        // is startup state under this design, so it is built here rather than measured as a read's cost.
        for (int i = 0; i < 2_000; i++) {
            AllocationProbe.sink = reader.read(DOCUMENT, Order.class);
        }
        AllocationProbe.sink = null;
    }

    @Test
    void theDocumentBindsAsExpected() {
        Order order = reader.read(DOCUMENT, Order.class);

        assertEquals("Ada Lovelace", order.customer());
        assertEquals(3, order.lines().size());
        assertEquals(12, order.lines().get(2).quantity());
    }

    /**
     * The retention question, measured: thousands of reads of one schema through one long-lived reader must
     * leave the heap where they found it. A per-read leak of even 100 bytes is 2 MB across this loop and
     * fails; the collector's own noise is well inside the allowance.
     */
    @Test
    void manyReadsOfOneSchemaRetainNothing() {
        int reads = 20_000;

        double retained = AllocationProbe.retainedPerOperation(reads, () ->
                AllocationProbe.sink = reader.read(DOCUMENT, Order.class));

        report("retained per read (one long-lived reader)", retained, "bytes");
        assertTrue(retained < 64, "each read left " + retained + " bytes of live heap behind -- a cache "
                + "keyed per document or per read would look exactly like this");
    }

    /**
     * The same question in the shape a server actually writes: a reader derived per request. Derivation is
     * cheap and the derived reader shares the compiled-schema registry -- what it must not do is register
     * something per request.
     */
    @Test
    void aReaderDerivedPerReadRetainsNothing() {
        int reads = 10_000;

        double retained = AllocationProbe.retainedPerOperation(reads, () ->
                AllocationProbe.sink = tson.objectReader().withSchema(ID).readAs(DOCUMENT, "order", Order.class));

        report("retained per read (reader derived per read)", retained, "bytes");
        assertTrue(retained < 64, "deriving a reader per read left " + retained + " bytes behind per read");
    }

    /**
     * Weak references make the same point without a measurement: hold one to every object a batch of reads
     * produced, drop the strong references, settle the collector. Anything still reachable is held by the
     * library, and for a read's own output there is no legitimate holder.
     */
    @Test
    void everyReadResultBecomesCollectable() {
        List<WeakReference<Order>> results = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            results.add(new WeakReference<>(reader.read(DOCUMENT, Order.class)));
        }

        assertTrue(AllocationProbe.allCollected(results),
                "a read result was still reachable after the collector ran -- something retains it");
    }

    /**
     * Transient garbage per read, reported and loosely bounded. The ceiling is a ratchet against a gross
     * regression (a per-character {@code Matcher}, a document buffered twice), not a budget to tune against
     * -- it sits at about twice what this measures today, which is far inside a real regression and far
     * outside the measurement's own variance. Most of what it allows is fixed cost rather than the
     * document's: see {@link #whereAReadsBytesGo} and the decoder item in {@code BACKLOG.md}.
     */
    @Test
    void transientAllocationPerReadIsBounded() {
        double perRead = AllocationProbe.allocatedPerOperation(20_000, () ->
                AllocationProbe.sink = reader.read(DOCUMENT, Order.class));

        report("allocated per read (" + DOCUMENT.length() + "-char document)", perRead, "bytes");
        assertTrue(perRead < 125_000, "a read of a " + DOCUMENT.length() + "-char document allocated "
                + perRead + " bytes");
    }

    /**
     * <b>A raised {@code tokenPolicy} is not a per-token allocation.</b> UTS #39 §5.2 runs on every token a
     * read pulls, so a policy that materialised anything per token would put the cost of the whole document
     * on the one feature a deployment turns on for safety -- and would make the honest advice "leave it off".
     *
     * <p>The conforming path therefore scans and returns {@code Optional.empty()}: no split array, no script
     * set, no stream, and no capturing lambda at the call. Only a genuinely mixed-script token builds
     * anything, and that one is a diagnostic. What is left is the decorator itself, once per read.
     *
     * <p>The ceiling is loose enough to survive a JDK upgrade and tight enough to catch the shape this
     * guards: the first cut of this code allocated ~2.3 KB per read of this document at {@code asciiOnly},
     * two orders of magnitude over what it now costs.
     */
    @Test
    void aRaisedTokenPolicyCostsAlmostNothingPerRead() {
        double unrestricted = AllocationProbe.allocatedPerOperation(20_000, () ->
                AllocationProbe.sink = reader.read(DOCUMENT, Order.class));
        double restricted = AllocationProbe.allocatedPerOperation(20_000, () -> AllocationProbe.sink =
                reader.withTokenPolicy(TsonUnicodePolicy.highlyRestrictive()).read(DOCUMENT, Order.class));
        double overhead = restricted - unrestricted;

        report("allocated per read, tokenPolicy raised to highlyRestrictive", restricted, "bytes");
        report("  overhead over the unrestricted default", overhead, "bytes");
        assertTrue(overhead < 1_000, "a raised token policy added " + overhead + " bytes per read, which is "
                + "per-token allocation rather than the one decorator a read should cost");
    }

    /**
     * Where a read's bytes go, reported and asserted on only as a shape: each stage of the stack over the
     * same document, so a number that moves says <em>which</em> stage moved it. Lexing and parsing dominate
     * -- a read builds a fresh {@code Lexer} over a fresh {@code InputStreamReader}, whose decoder buffers
     * are a fixed cost per document regardless of how short the document is -- and the schema-driven stages
     * add their validation on top of that floor.
     */
    @Test
    void whereAReadsBytesGo() {
        double events = AllocationProbe.allocatedPerOperation(20_000, () -> {
            TsonDataStream stream = new TsonDataStream(DOCUMENT);
            while (stream.hasNext()) {
                AllocationProbe.sink = stream.next();
            }
        });
        TsonTreeReader schemaless = new TsonTreeReader().preservingUnknownTypeRefs();
        double schemalessTree = AllocationProbe.allocatedPerOperation(20_000, () ->
                AllocationProbe.sink = schemaless.read(DOCUMENT));
        double schemaTree = AllocationProbe.allocatedPerOperation(20_000, () ->
                AllocationProbe.sink = tson.treeReader().read(DOCUMENT));
        double bind = AllocationProbe.allocatedPerOperation(20_000, () ->
                AllocationProbe.sink = reader.read(DOCUMENT, Order.class));

        report("  event stream only (lex + parse)", events, "bytes");
        report("  schemaless tree read", schemalessTree, "bytes");
        report("  schema-driven tree read", schemaTree, "bytes");
        report("  schema-driven bind read", bind, "bytes");

        assertTrue(events > 0 && bind >= events * 0.5,
                "the stack's own stages should not undercut the token stream they all run on");
    }

    /**
     * The read path's per-character question, and the guard for the fix that came out of profiling this
     * harness: {@code Lexer} pulled its input one character at a time, and {@code Reader.read()} allocates a
     * {@code char[]} and wraps it in a {@code CharBuffer} on every call -- about 40 bytes of garbage per
     * character of input, 47% of everything a read allocated, and proportional to the document rather than
     * fixed. Reading in blocks leaves the per-character cost as the token text itself.
     */
    @Test
    void lexingDoesNotAllocatePerCharacterOfInput() {
        String document = "{ note: \"" + "x".repeat(20_000) + "\" }";

        double perLex = AllocationProbe.allocatedPerOperation(2_000, () -> {
            TsonDataStream stream = new TsonDataStream(document);
            while (stream.hasNext()) {
                AllocationProbe.sink = stream.next();
            }
        });
        double perChar = perLex / document.length();

        report("allocated per character of input (lexed)", perChar, "bytes/char");
        assertTrue(perChar < 8, "lexing cost " + perChar + " bytes per character of input -- the token's own "
                + "text is 2 of that and the builder it accumulates in most of the rest. This ceiling is "
                + "tighter than the others here (about 40% over what it measures) because the regression it "
                + "guards is only 1.8x: decoding a token that holds no escape copies its text a second time. "
                + "Reading the input a character at a time, the other regression, is far above it");
    }

    /**
     * A diagnostic's RFC 6901 pointer is built when a diagnostic is built, not while descending -- so the
     * cost of nesting is flat per level rather than growing with depth.
     *
     * <p>Concatenating each step onto the last is <b>quadratic in depth</b>: every level copies the whole
     * prefix again. A level's own structural cost (events, tokens, a node, a context) is flat and large
     * enough to hide that, so this measures the cost of a level in a shallow part of the document and in a
     * deep one and compares the two. Flat is the answer laziness gives; eager building makes the deeper
     * level cost hundreds of bytes more, and more still at greater depth. Every read of a valid document
     * throws the pointer away unbuilt, which is nearly all of them, and a port descends the same way.
     */
    @Test
    void nestingCostsTheSameAtEveryDepth() {
        double shallowLevel = perLevelBetween(4, 32);
        double deepLevel = perLevelBetween(32, 60);

        report("allocated per level of nesting (shallow)", shallowLevel, "bytes");
        report("allocated per level of nesting (deep)", deepLevel, "bytes");
        assertTrue(deepLevel - shallowLevel < 150, "a level 32 deep cost " + (deepLevel - shallowLevel)
                + " bytes more than a level 4 deep -- a pointer built while descending copies the whole "
                + "prefix at every level, so the cost of a level grows with how deep it is");
    }

    /** Bytes per level of nesting between two depths, the flat per-read cost cancelling out. */
    private static double perLevelBetween(int shallow, int deep) {
        double atDeep = AllocationProbe.allocatedPerOperation(2_000, () ->
                AllocationProbe.sink = new TsonTreeReader().read(nested(deep)));
        double atShallow = AllocationProbe.allocatedPerOperation(2_000, () ->
                AllocationProbe.sink = new TsonTreeReader().read(nested(shallow)));
        return (atDeep - atShallow) / (deep - shallow);
    }

    /** {@code { deeply: { deeply: ... { leaf: 1 } } }} at the given depth. */
    private static String nested(int depth) {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            document.append("{ deeply: ");
        }
        document.append("{ leaf: 1 }");
        for (int i = 0; i < depth; i++) {
            document.append(" }");
        }
        return document.toString();
    }

    /**
     * The write path's own per-character question, and the regression guard for the fix that prompted this
     * harness: {@code quotedString} asked "is this a control character?" with a {@code Pattern}, costing a
     * {@code String}, a {@code Matcher} and the matcher's internals for every character of every string
     * written -- 56 bytes per character against 0 for the comparison that replaced it. A quoted string must
     * cost the characters it writes and nothing per character beyond them.
     */
    @Test
    void writingAQuotedStringDoesNotAllocatePerCharacter() {
        String text = "the quick brown fox jumps over the lazy dog, ".repeat(80);   // 3600 chars, nothing to escape

        double perWrite = AllocationProbe.allocatedPerOperation(2_000, () ->
                AllocationProbe.sink = new TsonDataEmitter().quotedString(text).toString());
        double perChar = perWrite / text.length();

        report("allocated per character written (quoted string)", perChar, "bytes/char");
        assertTrue(perChar < 8, "writing a quoted string cost " + perChar + " bytes per character -- the "
                + "characters themselves are 2, so anything approaching the regex's 56 is per-character work "
                + "that should not be there");
    }

    private static void report(String what, double value, String unit) {
        System.out.printf("  %-46s %10.2f %s%n", what, value, unit);
    }
}
