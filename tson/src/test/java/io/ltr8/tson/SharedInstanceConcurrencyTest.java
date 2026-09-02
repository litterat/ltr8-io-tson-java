package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The concurrency contract {@link Tson}'s own Javadoc states, pinned at the surface that states it.
 *
 * <p><b>One instance, read from many threads, is the shape the design asks for</b> -- a schema compiles once
 * per instance, so a {@code Tson} per request re-bootstraps the standard library and recompiles every schema.
 * That advice is only worth giving if sharing is actually safe, and this is where a consumer reads the
 * promise. {@code ReadPathConcurrencyTest} in {@code tson-compiler} covers the two caches underneath, on a
 * cold start; this covers the warm steady state a service actually runs in, through the front door.
 *
 * <p>The other half of the contract is that <b>registering is not part of it</b>, and that is asserted too --
 * not because concurrent registration corrupts anything, but because it is a race one caller loses, which is
 * exactly why the documented shape is "resolve at startup, then read".
 */
class SharedInstanceConcurrencyTest {

    private static final int THREADS = 8;
    private static final int READS_PER_THREAD = 50;

    /** The registry keys on the canonical identity ([TSON-DATA] §2.2.1), which is this without its scheme. */
    private static final String CANONICAL_ID = "example.test/shared.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/shared.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            { person => { name: text  age: int32 } }
            """;

    private static final String DOCUMENT = """
            !!schema:"https://example.test/shared.tn"
            !person { name: "Ada" age: 36 }
            """;

    /** Runs {@code task} on {@link #THREADS} threads released together, and returns what each produced. */
    private static <T> List<T> inParallel(Callable<T> task) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                tasks.add(() -> {
                    gate.await();
                    return task.call();
                });
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(tasks)) {
                results.add(f.get());   // an ExecutionException here is the failure this test exists for
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * <b>Reads through one shared instance agree, whichever thread ran them.</b> Each thread makes its own
     * reader from the shared {@code Tson} and reads the same document repeatedly, so what is exercised is the
     * steady state: two concurrent-map hits per read and a tree of immutable readers, none of it serialized
     * and none of it per-thread state.
     */
    @Test
    void concurrentReadsThroughOneInstanceAllSucceedAndAgree() throws Exception {
        Tson tson = Tson.builder().build();
        tson.resolve(SCHEMA);

        List<String> perThread = inParallel(() -> {
            String last = null;
            for (int i = 0; i < READS_PER_THREAD; i++) {
                TsonValue value = tson.treeReader().read(DOCUMENT);
                last = value.get("name").asString().orElseThrow() + "/" + value.get("age").asInt().orElseThrow();
            }
            return last;
        });

        assertEquals(Set.of("Ada/36"), Set.copyOf(perThread), perThread::toString);
    }

    /**
     * The same for {@link Tson#validate}, which builds a reader and a collecting receiver per call -- the
     * path a service validating request bodies is on, and the one where a shared mutable receiver would
     * show up as another thread's diagnostics appearing in this thread's list.
     */
    @Test
    void concurrentValidationThroughOneInstanceKeepsEachCallersDiagnosticsToItself() throws Exception {
        Tson tson = Tson.builder().build();
        tson.resolve(SCHEMA);
        String invalid = DOCUMENT.replace("age: 36", "age: \"thirty\"");

        List<Integer> counts = inParallel(() -> {
            int worst = 0;
            for (int i = 0; i < READS_PER_THREAD; i++) {
                assertTrue(tson.validate(DOCUMENT).isEmpty(), "the valid document is valid on every thread");
                List<Diagnostic> problems = tson.validate(invalid);
                worst = Math.max(worst, problems.size());
                assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
            }
            return worst;
        });

        assertEquals(Set.of(1), Set.copyOf(counts),
                () -> "each call sees its own document's one problem and no other thread's: " + counts);
    }

    /**
     * <b>The half that is not guaranteed, asserted so the documentation stays honest.</b> Registering the
     * same identity twice is an error however many threads are involved -- so concurrent {@code resolve} is a
     * race one caller loses rather than a way to warm a cache, and "resolve every schema at startup, then
     * read" is the shape that avoids the question entirely.
     *
     * <p>Deliberately not a threaded test: what it pins is the rule, and running it on one thread makes the
     * point without asking a race to reproduce.
     */
    @Test
    void registeringOneIdentityTwiceIsAnErrorAndIsNotAWayToWarmACache() {
        Tson tson = Tson.builder().build();
        tson.resolve(SCHEMA);

        TsonSchemaValidationException thrown =
                assertThrows(TsonSchemaValidationException.class, () -> tson.resolve(SCHEMA));

        assertTrue(thrown.getMessage().contains(CANONICAL_ID), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("already registered"), thrown::getMessage);
    }
}
