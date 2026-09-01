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
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Explicit registration into a {@link DataBindContext} is atomic: one claim on a class wins and every other
 * is refused, whether they arrive on one thread or eight.
 *
 * <p><b>The split form was the defect.</b> {@code register} checked the map and then put, so two threads
 * registering one class both passed the check and the second overwrote the first — turning an error into a
 * silent replacement, and replacing a descriptor {@code getDescriptor} may already have handed out. It is
 * the third instance of a shape this codebase has fixed twice before, in {@code TsonSchemaRegistry.register}
 * and in {@code getDescriptor} itself.
 *
 * <p>The contract is still that a context is wired up before reads run through it ({@code
 * TsonConfig.dataBindContext}). What this pins is that breaking that contract fails loudly rather than
 * quietly, which is the difference between a caller finding their mistake and inheriting it.
 *
 * <p><b>The concurrent case below is a guard, not a reproduction, and the distinction is worth stating.</b>
 * The window between the old check and its put is a few instructions wide: measured in isolation at 32
 * threads it produced a double claim in 2 attempts out of 2000, so catching it reliably would need thousands
 * of rounds and a slow, timing-dependent test. What is asserted instead is the invariant — exactly one claim
 * ever succeeds — which {@code putIfAbsent} makes unconditionally true and the split form made merely likely.
 * So it cannot fail spuriously against correct code, and would catch a regression only sometimes; {@link
 * #registeringOneClassTwiceIsRefused} is the one that fails every time.
 */
class RegistrationConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ATTEMPTS = 20;

    /** A distinct target per attempt, since the race only exists while a key is unclaimed. */
    record First(String value) {
    }

    record Second(String value) {
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

    /** One thread: the second registration of a class is refused, which is the behaviour under test. */
    @Test
    void registeringOneClassTwiceIsRefused() throws Exception {
        DataBindContext context = DataBindContext.builder().build();
        context.registerAtom(First.class);

        DataBindException thrown =
                assertThrows(DataBindException.class, () -> context.registerAtom(First.class));

        assertEquals("Class already registered: " + First.class.getTypeName(), thrown.getMessage());
    }

    /**
     * <b>Eight threads: exactly one wins and the other seven are told so.</b> The failure this guards is not
     * an exception but its <em>absence</em> — a second call succeeding — so it asserts the count rather than
     * that "some registration threw", which the broken form satisfied too.
     *
     * <p>Each attempt uses a fresh context, because the window closes as soon as the key is claimed. See the
     * class note on why this is a guard rather than a reproduction.
     */
    @Test
    void onlyOneOfManyConcurrentRegistrationsOfOneClassSucceeds() throws Exception {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            DataBindContext context = DataBindContext.builder().build();
            AtomicInteger claimed = new AtomicInteger();

            List<Boolean> outcomes = inParallel(() -> {
                try {
                    context.registerAtom(First.class);
                    claimed.incrementAndGet();
                    return true;
                } catch (DataBindException refused) {
                    return false;
                }
            });

            assertEquals(1, claimed.get(),
                    () -> "one claim on the class, not " + claimed.get() + ": " + outcomes);
            assertEquals(THREADS - 1, outcomes.stream().filter(won -> !won).count(),
                    () -> "every other thread is refused rather than silently overwriting: " + outcomes);
        }
    }

    /** And the descriptor every thread goes on to read is the one that won, not a later overwrite. */
    @Test
    void theWinningDescriptorIsTheOneEveryReaderSees() throws Exception {
        DataBindContext context = DataBindContext.builder().build();
        context.registerAtom(Second.class);
        Object registered = context.getDescriptor(Second.class);

        List<Object> seen = inParallel(() -> {
            try {
                context.registerAtom(Second.class);
            } catch (DataBindException expected) {
                // The class is already claimed, which is the point.
            }
            return context.getDescriptor(Second.class);
        });

        seen.forEach(descriptor -> assertSame(registered, descriptor,
                "a refused registration leaves the winner's descriptor in place"));
    }
}
