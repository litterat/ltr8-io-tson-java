package io.ltr8.tson.perf;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;

/**
 * Measures what a piece of work allocates, and what of that <em>survives</em> -- the two questions an
 * allocation harness asks, and they are not the same question.
 *
 * <p><b>Transient bytes</b> ({@link #allocatedPerOperation}) come from {@code
 * ThreadMXBean.getCurrentThreadAllocatedBytes}, which counts every byte the thread's allocator handed out
 * whether or not it lived past the next collection. It is exact and repeatable, and it is what catches "this
 * loop builds a {@code Matcher} per character".
 *
 * <p><b>Retained bytes</b> ({@link #retainedPerOperation}) are heap still in use after the work has finished
 * and the collector has run: nothing else can distinguish garbage the JVM is happy to drop from a cache that
 * grows once per read. This is the number a long-lived server actually cares about, and the one this repo's
 * "resolve every schema during startup" design is a claim about. It is measured, not derived, so it carries
 * real noise -- a few hundred KB of it -- and thresholds over it belong in the tens of KB, not the bytes.
 * {@link #assertCollectable} is the sharp instrument beside it: a weak reference either cleared or it did
 * not, and no noise enters.
 *
 * <p>Everything here is HotSpot-specific ({@code com.sun.management}) and single-threaded on purpose --
 * {@link #supported()} says whether the JVM offers the counter, and a harness that cannot measure skips
 * rather than asserting on numbers it did not get.
 *
 * <p>For a deeper cut than this gives -- allocation by call site, or what an {@code OldObjectSample} traces
 * back to -- run the harness under Flight Recorder:
 *
 * <pre>{@code
 * ./gradlew :tson:allocationReport -Dtson.alloc.jfr=build/alloc.jfr
 * jfr summary build/alloc.jfr
 * jfr print --events ObjectAllocationSample,OldObjectSample build/alloc.jfr | less
 * }</pre>
 */
final class AllocationProbe {

    /** Keeps the work's result reachable to the end of the loop, so nothing measured is optimised away. */
    static volatile Object sink;

    private AllocationProbe() {
    }

    static boolean supported() {
        return ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean && bean.isThreadAllocatedMemoryEnabled();
    }

    private static long threadAllocatedBytes() {
        return ((ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /**
     * Bytes {@code work} allocates per run, net of the harness's own loop -- an empty run of the same shape
     * is measured and subtracted, so what is left is the work's.
     */
    static double allocatedPerOperation(int iterations, Runnable work) {
        Runnable empty = () -> sink = null;
        long overhead = allocatedBy(iterations, empty);
        long measured = allocatedBy(iterations, work);
        return Math.max(0, measured - overhead) / (double) iterations;
    }

    private static long allocatedBy(int iterations, Runnable work) {
        long before = threadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            work.run();
        }
        long after = threadAllocatedBytes();
        return after - before;
    }

    /**
     * Heap still in use per run of {@code work} once the collector has had its say -- the leak number.
     *
     * <p>Measured twice and the <em>smaller</em> reading kept: a single reading catches whatever the JIT
     * happened to compile, or a code cache that grew, in with the work's own retention, and those settle
     * where a real leak does not.
     */
    static double retainedPerOperation(int iterations, Runnable work) {
        double first = measureRetention(iterations, work);
        double second = measureRetention(iterations, work);
        return Math.min(first, second);
    }

    private static double measureRetention(int iterations, Runnable work) {
        settle();
        long before = heapUsed();
        for (int i = 0; i < iterations; i++) {
            work.run();
        }
        sink = null;
        settle();
        return (heapUsed() - before) / (double) iterations;
    }

    /**
     * Runs the collector until it demonstrably ran -- a sentinel's weak reference clearing is the proof,
     * where a bare {@code System.gc()} is only a request. Bounded, so a JVM that ignores the request (an
     * {@code -XX:+DisableExplicitGC} run) leaves the harness reporting noise rather than hanging.
     */
    static void settle() {
        for (int round = 0; round < 3; round++) {
            WeakReference<Object> sentinel = new WeakReference<>(new Object());
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (sentinel.get() != null && System.nanoTime() < deadline) {
                System.gc();
                Thread.yield();
            }
        }
    }

    /**
     * Whether every one of {@code references} has been collected -- the retention question asked so that no
     * measurement noise can enter the answer. A reference that survives a settled collector is held by
     * something, and for a read's own output that something can only be the library.
     */
    static boolean allCollected(Iterable<? extends WeakReference<?>> references) {
        settle();
        for (WeakReference<?> reference : references) {
            if (reference.get() != null) {
                return false;
            }
        }
        return true;
    }
}
