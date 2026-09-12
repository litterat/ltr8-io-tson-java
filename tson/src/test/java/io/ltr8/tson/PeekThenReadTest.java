package io.ltr8.tson;

import io.ltr8.annotation.Profile;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonDocumentPeek;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route on the header, then read the body -- <b>on one stream, with no rewind</b>. The case this exists
 * for is an HTTP receiver: a request body cannot be re-opened, and yet which binding applies is decided by
 * the {@code !!schema} the body itself declares.
 *
 * <p>The pieces are already tested apart -- {@code SchemaVersionProfileTest} pins the two-profile binding,
 * {@code TsonDocumentPeekTest} pins the peek -- so what is pinned here is that they compose over a source
 * that is read exactly once. The stream is deliberately one-shot: it refuses {@code reset()} and reports
 * {@code markSupported() == false}, so a rewind fails the test rather than passing it quietly.
 */
class PeekThenReadTest {

    private static final String V1 = "https://example.test/order-1.tn";
    private static final String V2 = "https://example.test/order-2.tn";

    private static final String V1_SCHEMA = """
            !!id:"https://example.test/order-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            { order => { sku: text  quantity: int32  code: int32 } }
            """;

    private static final String V2_SCHEMA = """
            !!id:"https://example.test/order-2.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            { order => { sku: text  quantity: int32  currency: text } }
            """;

    public record Order(String sku, int quantity, int code, String currency) {

        @Profile(value = "api-1", fields = {"sku", "quantity", "code"})
        public Order(String sku, int quantity, int code) {
            this(sku, quantity, code, "AUD");
        }

        @Profile(value = "api-2", fields = {"sku", "quantity", "currency"})
        public Order(String sku, int quantity, String currency) {
            this(sku, quantity, 0, currency);
        }
    }

    private static Tson tson(String profile, String schema) {
        String id = schema.contains(V1) ? V1 : V2;
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, id)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        DataNameBinder binder =
                name -> "order".equals(name) ? Order.class : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext context = DataBindContext.builder().nameBinder(binder).profile(profile)
                .registerAtoms(AtomContext.hostTypes()).build();
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)).withDataBindContext(context));
    }

    private static final String V1_DOC = """
            !!schema:"https://example.test/order-1.tn"
            !order { sku: "A"  quantity: 1  code: 7 }""";

    private static final String V2_DOC = """
            !!schema:"https://example.test/order-2.tn"
            !order { sku: "B"  quantity: 2  currency: "NZD" }""";

    /** A body that may be read once, forwards. Rewinding it is a test failure, not a slow path. */
    private static InputStream oneShot(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void reset() {
                throw new AssertionError("this stream was rewound; the peek is supposed to make that unnecessary");
            }
        };
    }

    /**
     * The whole point, twice over: the same code routes a v1 and a v2 body to different bind contexts,
     * having read only each body's header before choosing.
     */
    @Test
    void routesEachBodyToItsOwnVersionOnOneStream() {
        Tson v1 = tson("api-1", V1_SCHEMA);
        Tson v2 = tson("api-2", V2_SCHEMA);

        assertEquals(new Order("A", 1, 7, "AUD"), route(v1, v2, oneShot(V1_DOC)));
        assertEquals(new Order("B", 2, 0, "NZD"), route(v1, v2, oneShot(V2_DOC)));
    }

    /** What a receiver actually writes: peek, choose, read -- the body streaming through once. */
    private static Order route(Tson v1, Tson v2, InputStream body) {
        TsonDocumentPeek peek = v1.begin(body);
        Tson version = peek.header().schema().filter(V2::equals).isPresent() ? v2 : v1;
        return version.objectReader().read(peek, Order.class);
    }

    /** The header answers before anything else is read, which is what makes the choice possible at all. */
    @Test
    void theHeaderIsAvailableBeforeTheBody() {
        TsonDocumentPeek peek = tson("api-1", V1_SCHEMA).begin(oneShot(V1_DOC));

        assertEquals(V1, peek.header().schema().orElseThrow());
        assertFalse(peek.isSchemaDocument());
    }

    /** A tree read continues the same way, and keeps the header's own facts. */
    @Test
    void aTreeReadContinuesTheSameStream() {
        Tson tson = tson("api-2", V2_SCHEMA);

        TsonDocumentPeek peek = tson.begin(oneShot(V2_DOC));

        assertEquals("NZD", tson.treeReader().read(peek).get("currency").asString().orElseThrow());
    }

    /**
     * <b>The continuing reader's receiver is the one that hears about the body.</b> The peek reads the
     * header with a throwing receiver of its own, and a collecting read that continued on it would
     * otherwise throw at the first problem instead of collecting.
     */
    @Test
    void theContinuingReadersDiagnosticsAreUsed() {
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        Tson tson = tson("api-1", V1_SCHEMA);

        TsonDocumentPeek peek = tson.begin(oneShot("""
                !!schema:"https://example.test/order-1.tn"
                !order { sku: "A"  quantity: 1 }"""));
        Order order = tson.objectReader().withDiagnostics(problems).read(peek, Order.class);

        assertEquals(null, order, "bind mode is all-or-nothing");
        assertTrue(problems.diagnostics().stream().anyMatch(d -> d.code() == Diagnostic.Code.FIELD_REQUIRED),
                problems.diagnostics().toString());
    }

    /**
     * <b>A reader whose lexical policy differs is refused, not quietly obeyed.</b> §9.1's limits and §8.2's
     * token policy were applied to the tokens the header is made of, so a reader that disagrees would be
     * reading the rest of one document under a policy that never governed its start.
     */
    @Test
    void aReaderWithADifferentPolicyIsRefused() {
        Tson tson = tson("api-1", V1_SCHEMA);
        TsonDocumentPeek peek = tson.begin(oneShot(V1_DOC));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tson.objectReader().withLimits(LimitsPolicy.defaults().withMaxDepth(8))
                        .read(peek, Order.class));

        assertTrue(e.getMessage().contains("processor policy"), e.getMessage());
    }

    /**
     * A malformed header does not throw out of {@code begin} -- the peek is total -- and the read that
     * follows reports it through its own receiver, exactly as reading the document whole would have.
     */
    @Test
    void aMalformedHeaderIsReportedByTheReadRatherThanByTheBegin() throws IOException {
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        Tson tson = tson("api-1", V1_SCHEMA);

        TsonDocumentPeek peek = tson.begin(oneShot("!!schema:\n{ sku: \"A\" }"));
        Order order = tson.objectReader().withDiagnostics(problems).read(peek, Order.class);

        assertEquals(null, order);
        assertFalse(problems.diagnostics().isEmpty(),
                "the read owes a diagnostic for what begin declined to throw");
    }
}
