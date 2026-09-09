package io.ltr8.tson.json.perf;

import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.JsonObjectReader;
import io.ltr8.tson.json.JsonTreeReader;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.perf.AllocationProbe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a JSON read allocates, stage by stage -- the counterpart to {@code AllocationHarnessTest} on the TSON
 * side, and the thing this encoding had no answer from.
 *
 * <p><b>The question this encoding has and the text one does not.</b> A schema-driven TSON read settles each
 * field once, when the reader is compiled: which slot it fills, which family reads it, what host type it
 * binds to. [TSON-JSON] §4.1 makes the position's own type decide and there is no compile to settle it in, so
 * a JSON read does that work as it goes -- once per value, on the hottest path the encoding has.
 *
 * <p><b>Which is why the figures here are differences rather than totals.</b> Per-value work is tens of bytes
 * against a read's thousands; a whole-read number cannot see it, and two of them cannot be compared. Reading
 * the same document with 4 records and with 64 and dividing the difference cancels every flat cost -- the
 * lexer's decode buffers above all -- and leaves what one record actually costs. {@link #whereAJsonReadsBytesGo}
 * reports the totals anyway, because a stage that moves says which one to look at.
 *
 * <p>Thresholds are loose in both directions, and for the reasons {@code AllocationProbe} sets out: they are a
 * ratchet against a gross regression, not a budget. A tight number would break on a JDK upgrade and teach
 * everyone to raise it.
 *
 * <p>Run it alone, with the report on stdout:
 * <pre>{@code ./gradlew :tson-json:allocationReport}</pre>
 */
class JsonAllocationHarnessTest {

    public record Line(String sku, int quantity, double price) {
    }

    public record Order(UUID id, String customer, OffsetDateTime placed, List<Line> lines, String note) {
    }

    private static final String DOCUMENT = order(3);

    /** Hoisted out of every measured loop: encoding a document is not part of reading it. */
    private static final byte[] BYTES = DOCUMENT.getBytes(StandardCharsets.UTF_8);

    private static JsonObjectReader reader;

    @BeforeAll
    static void startUp() {
        assumeTrue(AllocationProbe.supported(), "needs HotSpot's per-thread allocation counter");

        reader = JsonObjectReader.standard();
        // Everything a first read builds -- the bind descriptors above all -- is startup state under this
        // design, so it is built here rather than measured as a read's cost.
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
     * Where a read's bytes go, reported and asserted on only as a shape: each stage of the stack over the
     * same document, so a number that moves says <em>which</em> stage moved it. Lexing dominates -- a read
     * builds a fresh {@code JsonLexer} whose decode buffers are a fixed cost per document however short the
     * document is -- and the tree and bind stages add their own work on top of that floor.
     */
    @Test
    void whereAJsonReadsBytesGo() {
        double events = AllocationProbe.allocatedPerOperation(20_000, () -> {
            JsonStream stream = new JsonStream(new ByteArrayInputStream(BYTES),
                    ProcessorPolicy.defaults(), DiagnosticsReceiver.throwing());
            while (stream.hasNext()) {
                AllocationProbe.sink = stream.next();
            }
        });
        double tree = AllocationProbe.allocatedPerOperation(20_000, () ->
                AllocationProbe.sink = JsonTreeReader.standard().read(DOCUMENT));
        double bind = AllocationProbe.allocatedPerOperation(20_000, () ->
                AllocationProbe.sink = reader.read(DOCUMENT, Order.class));

        report("  event stream only (lex + parse)", events, "bytes");
        report("  tree read", tree, "bytes");
        report("  bind read", bind, "bytes");

        assertTrue(events > 0 && bind >= events * 0.5,
                "the stack's own stages should not undercut the token stream they all run on");
    }

    /**
     * What one bound record costs, reported per record so that per-record work shows up as itself.
     *
     * <p>Two hash tables used to be built here per record, each worth about 200 bytes for a three-field one:
     * the member-name-to-slot map, which belongs to the descriptor and is built once per class
     * ({@code DataClassRecord.fieldIndex}), and the set tracking §3.1's repeats, which {@code seen} already
     * answers for every declared member. Together they were roughly a fifth of this figure.
     *
     * <p><b>The assertion here is not what guards either.</b> A ceiling tight enough to catch a tenth is a
     * budget that the next JDK breaks, so the exact properties are pinned where they are cheap to state --
     * {@code DataClassRecordFieldIndexTest} in {@code tson-bind} for the index, and
     * {@code JsonObjectReaderTest} for the repeat a set is still needed to catch. This is the
     * gross-regression ratchet the rest of the harness is: work that returns per field rather than per
     * record, or a descriptor lookup that stopped being cached, moves it by a multiple.
     */
    @Test
    void bindingARecordCostsItsOwnValuesAndLittleElse() {
        double perLine = perLine(order(4), order(64), d -> reader.read(d, Order.class));

        report("allocated per record bound (JSON)", perLine, "bytes");
        assertTrue(perLine < 4_000, "binding one three-field record allocated " + perLine + " bytes, which is "
                + "not the shape of three values and the slots to hold them");
    }

    /** Bytes per line of the order, the flat per-read cost cancelling out. */
    private static double perLine(String few, String many, java.util.function.Function<String, Object> read) {
        double atMany = AllocationProbe.allocatedPerOperation(2_000, () ->
                AllocationProbe.sink = read.apply(many));
        double atFew = AllocationProbe.allocatedPerOperation(2_000, () ->
                AllocationProbe.sink = read.apply(few));
        return (atMany - atFew) / 60;
    }

    /** An order carrying {@code lines} lines -- each one a record for a reader to bind. */
    private static String order(int lines) {
        StringBuilder document = new StringBuilder("{ \"id\": \"9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09\",")
                .append(" \"customer\": \"Ada Lovelace\", \"placed\": \"2026-08-24T10:00:00Z\", \"lines\": [");
        for (int i = 0; i < lines; i++) {
            document.append(i == 0 ? "" : ",")
                    .append(" { \"sku\": \"A-1\", \"quantity\": ").append(i == 2 ? 12 : 2)
                    .append(", \"price\": 9.99 }");
        }
        return document.append(" ], \"note\": \"leave with the neighbour\" }").toString();
    }

    private static void report(String what, double value, String unit) {
        System.out.printf("  %-46s %10.2f %s%n", what, value, unit);
    }
}
