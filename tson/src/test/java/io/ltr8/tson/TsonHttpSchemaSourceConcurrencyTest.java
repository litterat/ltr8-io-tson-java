package io.ltr8.tson;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonHttpSchemaSourceConcurrencyTest {

    private static final int THREADS = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
    private static final String HOST = "schemas.example.com";

    private static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/35/app/order-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            { order => { sku: text  quantity: int32 } }""";

    private static final String BASE = """
            !!id:"https://schemas.example.com/2026/35/app/base-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            { sku_code => !text ^ { min_length: 1 } }""";

    private static final String DERIVED = """
            !!id:"https://schemas.example.com/2026/35/app/derived-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://schemas.example.com/2026/35/app/base-1.tn"
            { boxed => { sku: sku_code } }""";

    private HttpServer server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        serve("/2026/35/app/order-1.tn", SCHEMA);
        serve("/2026/35/app/base-1.tn", BASE);
        serve("/2026/35/app/derived-1.tn", DERIVED);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void serve(String path, String document) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private TsonHttpSchemaSource source() {
        return TsonHttpSchemaSource.builder().mapHost(HOST, base).timeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Many threads first-fetching one identity at once. The cache is get-then-put rather than
     * {@code computeIfAbsent}, so a race costs a duplicate fetch and nothing else -- every caller must still get
     * the right document, and none may hang.
     */
    @Test
    @Timeout(120)
    void concurrentFirstFetchesOfOneIdentityAllSucceed() throws Exception {
        String reference = "https://" + HOST + "/2026/35/app/order-1.tn";
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try (TsonHttpSchemaSource source = source();
             ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        assertEquals(SCHEMA, source.fetch(reference));
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "threads did not finish -- a deadlock, most likely");
            assertTrue(failures.isEmpty(), "failures: " + failures);
            assertTrue(source.isCached(reference));
        }

        assertTrue(requests.get() <= THREADS,
                "a duplicate fetch is the accepted cost of not holding a lock across I/O; " + requests.get());
    }

    /**
     * Pins the reason {@code computeIfAbsent} is merely slow here rather than deadlocking: the loader is not
     * re-entrant. It fetches a document, returns, and only then resolves and fetches its imports -- so
     * {@code fetch} is never called from inside {@code fetch}.
     *
     * <p>Worth pinning because the safety of the caching strategy would change if this ever stopped being true,
     * and because an earlier version of this project's own notes asserted the opposite.
     */
    @Test
    @Timeout(60)
    void fetchIsNeverReenteredByATransitiveImport() {
        AtomicInteger depth = new AtomicInteger();
        AtomicInteger maxDepth = new AtomicInteger();

        TsonSchemaSource counting = uri -> {
            int current = depth.incrementAndGet();
            maxDepth.updateAndGet(seen -> Math.max(seen, current));
            try {
                return uri.contains("base-1") ? BASE : DERIVED;
            } finally {
                depth.decrementAndGet();
            }
        };

        Tson tson = Tson.builder().schemaSource(counting).build();
        tson.resolve(DERIVED);

        assertTrue(maxDepth.get() > 0, "the import must actually have been fetched");
        assertEquals(1, maxDepth.get(), "fetch must not be re-entered while a fetch is in progress");
    }

    /** A transitive chain resolved concurrently: each thread's own source, all racing the same origin. */
    @Test
    @Timeout(120)
    void concurrentResolutionOfAnImportChainSucceeds() throws Exception {
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    // A Tson of its own per thread: resolution mutates a registry and is not concurrent-safe,
                    // which is the invariant this project states everywhere. What is shared here is the origin.
                    try (TsonHttpSchemaSource source = source()) {
                        start.await();
                        Tson tson = Tson.builder().schemaSource(source).build();
                        assertEquals(java.util.List.of(), tson.validateSchema(DERIVED));
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "threads did not finish");
            assertTrue(failures.isEmpty(), "failures: " + failures);
        }
    }
}
