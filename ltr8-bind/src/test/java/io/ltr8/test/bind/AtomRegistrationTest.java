package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Registration closes when a context is built, and descriptor lookup after that is concurrent-safe.
 *
 * <p><b>The registration race is gone rather than guarded.</b> {@code registerAtom} used to be a method on a
 * built context, so two threads could claim one class and -- before {@code putIfAbsent} -- the second would
 * silently overwrite a descriptor {@code getDescriptor} may already have handed out. It is now a
 * {@link DataBindContext.Builder} method applied once, inside the constructor, on the thread that builds:
 * the API can no longer express the racing call, so the tests that guarded it have no subject left. What
 * survives is the rule they also pinned -- one claim per class -- which now fails at {@code build()}.
 *
 * <p><b>What is still a race is memoization</b>, and it is the one below. {@code getDescriptor} resolves a
 * class it was not explicitly given and caches the result, so two threads asking at once both do the work;
 * {@code putIfAbsent} settles which answer everyone then sees. That is duplicated work on a race and never
 * duplicated state, which is the guarantee {@code Tson}'s own concurrency note makes.
 */
class AtomRegistrationTest {

    private static final int THREADS = 8;
    private static final int ATTEMPTS = 20;

    /** A distinct target per attempt, since the race only exists while a key is unclaimed. */
    public record First(String value) {
    }

    public record Second(String value) {
    }

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
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /** One claim per class, refused where it is now made: at the builder, when the context is built. */
    @Test
    void registeringOneClassTwiceIsRefused() {
        DataBindContext.Builder builder = DataBindContext.builder()
                .registerAtom(First.class)
                .registerAtom(First.class);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);

        assertTrue(thrown.getCause().getMessage().contains(First.class.getTypeName()),
                () -> "the duplicate names the class: " + thrown.getCause().getMessage());
    }

    /** And re-registering something the context already binds itself -- a primitive -- is refused alike. */
    @Test
    void registeringOverABuiltInIsRefused() {
        DataBindContext.Builder builder = DataBindContext.builder().registerAtom(String.class);

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    /**
     * <b>Concurrent lookups of one class agree.</b> {@code getDescriptor} resolves and memoizes, so threads
     * arriving together may each do the work -- what must never differ is the descriptor they go on to use,
     * since two readers holding different descriptors for one class is the state divergence the cache exists
     * to prevent.
     */
    @Test
    void concurrentDescriptorLookupsAgreeOnOne() throws Exception {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            DataBindContext context = DataBindContext.builder().allowAny().build();

            List<Object> seen = inParallel(() -> context.getDescriptor(Second.class));

            Object first = seen.getFirst();
            seen.forEach(descriptor -> assertSame(first, descriptor,
                    "every thread sees one descriptor for one class"));
        }
    }
}
