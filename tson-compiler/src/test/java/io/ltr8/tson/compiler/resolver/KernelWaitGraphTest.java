package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.ast.Document;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kernel's resolved form, meta.tn's, measured under {@code SPEC-FEEDBACK.md #4}'s resolution-order rule ({@link
 * WaitGraph} states it).
 *
 * <p>Rule 4 makes the pre-loaded set a measurement rather than a list: it is exactly the one cycle the kernel must
 * close, {@code meta.record} and what reading a {@code !meta.record} waits on. So this reads {@code
 * spec/fixtures/meta-resolved.tn} schemalessly, builds the wait graph from the fixture's own declarations, and
 * asserts that the graph has exactly one cycle, that it holds {@code meta.record}, and that every other entry reads in
 * an order whose longest wait chain is within rule 6's default bound of 64. The measurements go to stdout.
 */
class KernelWaitGraphTest {

    private static final int WAIT_DEPTH_LIMIT = 64;

    @Test
    void theKernelHasOneCycleAndItHoldsRecord() throws Exception {
        WaitGraph graph = WaitGraph.of(fixture());
        List<Set<String>> components = graph.components();
        List<Set<String>> cycles = components.stream().filter(graph::isCycle).toList();
        Set<String> preloaded = cycles.stream().filter(c -> c.contains("meta.record")).findFirst().orElse(Set.of());
        Map<String, Integer> depth = depths(graph, components, preloaded);

        report(graph, components, cycles, preloaded, depth);

        assertEquals(List.of(), graph.problems(), "the walk could not decide everything from the fixture");
        assertTrue(preloaded.contains("meta.record"), "no cycle holds record");
        assertEquals(List.of(preloaded), cycles, "the wait graph has a cycle other than record's");
        int longest = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(longest <= WAIT_DEPTH_LIMIT, "the longest wait chain is " + longest);
    }

    /**
     * The kernel holds what its one cycle needs to resolve and link on its own -- the cycle, what reading the document
     * itself waits on (its root tag's {@code schema}, an alias of {@code meta.schema}, the key and value types that
     * reads, the annotations on its header and on its keys, {@code @atom.ordering} and {@code @atom.bounded} among
     * them), and every entry a declaration of that set names, closed to a fixed point -- and the concepts that belong
     * with them, which nothing in the closure names: the map, tuple and choice constructors, the tuple's element and
     * the one-element tuple templates, held template bodies and the {@code application} annotation, the {@code kind}
     * and {@code ref} references with {@code field_name}, the {@code set} template, the {@code disjoint} assertion,
     * and the octet-sequence constructor with its encodings and instance. The measurement: a cycle of 26, a closure of
     * 69.
     */
    @Test
    void theKernelHoldsTheClosureOfItsCycleAndItsRelatedConcepts() throws Exception {
        WaitGraph graph = WaitGraph.of(fixture());
        Set<String> cycle = graph.components().stream().filter(graph::isCycle).filter(c -> c.contains("meta.record"))
                .findFirst().orElseThrow();
        Set<String> closure = new java.util.TreeSet<>(cycle);
        java.util.Deque<String> work = new java.util.ArrayDeque<>(cycle);
        graph.documentWaits().keySet().stream().filter(graph.entries()::contains).filter(closure::add)
                .forEach(work::add);
        while (!work.isEmpty()) {
            graph.references(work.pop()).stream().filter(closure::add).forEach(work::add);
        }
        Set<String> related = new java.util.TreeSet<>(graph.entries());
        related.removeAll(closure);
        Set<String> beyond = new java.util.TreeSet<>(closure);
        beyond.removeAll(cycle);
        long nested = graph.entries().stream().filter(name -> name.indexOf('.') >= 0).count();
        System.out.println("kernel: " + graph.entries().size() + " entries, " + nested + " under its maps and "
                + (graph.entries().size() - nested) + " at the root; the cycle's closure " + closure.size()
                + " (the cycle's " + cycle.size() + " and " + beyond.size() + " more)");
        System.out.println("  closure beyond the cycle: " + beyond);
        System.out.println("  related concepts beyond the closure: " + related);

        assertEquals(List.of(), graph.problems(), "a wait names no entry of the kernel");
        assertEquals(26, cycle.size(), "the cycle");
        assertEquals(69, closure.size(), "the cycle's closure");
        assertEquals(new java.util.TreeSet<>(List.of("bytes", "meta.application",
                "binary.bytes_encoding", "binary.bytes_type", "meta.choice", "meta.disjoint",
                "meta.field_name", "meta.kind", "meta.map", "meta.ref", "meta.template", "meta.tuple",
                "meta.tuple1", "meta.tuple_element", "meta.voidable_tuple1", "set")),
                new java.util.TreeSet<>(related.stream().filter(name -> !name.startsWith("array_")).toList()),
                "the related concepts");
    }

    // ── Measuring ────────────────────────────────────────────────────────

    /**
     * Each entry's wait depth: the longest chain of waits from it to a pre-loaded entry. A pre-loaded entry is 0;
     * every entry of any other component shares that component's depth, so a second cycle still gets a number.
     */
    private static Map<String, Integer> depths(WaitGraph graph, List<Set<String>> components, Set<String> preloaded) {
        Map<String, Set<String>> componentOf = new HashMap<>();
        components.forEach(c -> c.forEach(e -> componentOf.put(e, c)));
        Map<String, Integer> depth = new HashMap<>();
        for (Set<String> component : components) { // dependencies first
            int d = 0;
            if (component != preloaded) {
                for (String entry : component) {
                    for (String target : graph.waits(entry).keySet()) {
                        if (depth.containsKey(target) && componentOf.get(target) != component) {
                            d = Math.max(d, depth.get(target) + 1);
                        }
                    }
                }
            }
            for (String entry : component) {
                depth.put(entry, d);
            }
        }
        return depth;
    }

    private static List<String> longestChain(WaitGraph graph, Map<String, Integer> depth) {
        int deepest = depth.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        String start = depth.keySet().stream().filter(e -> depth.get(e) == deepest).sorted().findFirst().orElseThrow();
        List<String> chain = new ArrayList<>();
        String current = start;
        chain.add(current);
        while (depth.get(current) > 0) {
            int want = depth.get(current) - 1;
            String from = current;
            current = graph.waits(from).keySet().stream()
                    .filter(t -> depth.containsKey(t) && depth.get(t) == want).sorted().findFirst().orElseThrow();
            chain.add(current + " [" + graph.waits(from).get(current).rule() + "]");
        }
        return chain;
    }

    // ── Reporting ────────────────────────────────────────────────────────

    private static void report(WaitGraph graph, List<Set<String>> components, List<Set<String>> cycles,
                               Set<String> preloaded, Map<String, Integer> depth) {
        System.out.println("== Kernel wait graph (SPEC-FEEDBACK #4), meta-resolved.tn ==");
        int edges = graph.entries().stream().mapToInt(e -> graph.waits(e).size()).sum();
        System.out.println("entries: " + graph.entries().size() + ", wait edges: " + edges);
        Map<String, Long> heads = graph.entries().stream()
                .collect(Collectors.groupingBy(graph::head, TreeMap::new, Collectors.counting()));
        System.out.println("entries by head constructor: " + heads);
        System.out.println("document read waits on: " + graph.documentWaits().values());

        System.out.println();
        System.out.println("pre-loaded set (the cycle holding record): " + preloaded.size() + " of "
                + graph.entries().size() + " entries");
        System.out.println("  " + preloaded);
        System.out.println("  why each is on the cycle (its waits inside the set):");
        for (String entry : preloaded) {
            List<String> inside = graph.waits(entry).values().stream()
                    .filter(w -> preloaded.contains(w.target())).map(WaitGraph.Wait::toString).toList();
            System.out.println("    " + entry + " (!" + graph.head(entry) + ") -> " + inside);
        }

        System.out.println();
        System.out.println("cycles (non-trivial SCCs): " + cycles.size());
        for (Set<String> cycle : cycles) {
            if (cycle == preloaded) {
                continue;
            }
            System.out.println("  OTHER CYCLE " + cycle + ", its edges:");
            for (String entry : cycle) {
                graph.waits(entry).values().stream().filter(w -> cycle.contains(w.target()))
                        .forEach(w -> System.out.println("    " + entry + " -> " + w));
            }
        }

        System.out.println();
        int longest = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        System.out.println("longest wait chain: " + longest + " (limit " + WAIT_DEPTH_LIMIT + ")");
        System.out.println("  " + String.join(" -> ", longestChain(graph, depth)));

        System.out.println();
        System.out.println("choice-position over-approximations: " + graph.overapproximations().size());
        graph.overapproximations().forEach(o -> System.out.println("  " + o));
        System.out.println("problems: " + graph.problems().size());
        graph.problems().forEach(p -> System.out.println("  " + p));

        System.out.println();
        System.out.println("entries outside the pre-loaded set, dependencies first (d = wait depth; * = pre-loaded):");
        for (Set<String> component : components) {
            if (component == preloaded) {
                continue;
            }
            for (String entry : component) {
                String waits = graph.waits(entry).keySet().stream()
                        .map(t -> preloaded.contains(t) ? t + "*" : t).collect(Collectors.joining(" "));
                System.out.printf("  d=%-2d %-38s !%-13s %s%n", depth.get(entry), entry, graph.head(entry), waits);
            }
        }
    }

    // ── The fixture ──────────────────────────────────────────────────────

    /** {@code meta-resolved.tn}, read schemalessly as the resolved form it is. */
    static Document fixture() throws Exception {
        return new TsonDataParser(Files.readString(specDirectory().resolve("meta-resolved.tn"))).parseResolvedForm();
    }

    /** {@code spec/fixtures}, found by walking up rather than assumed relative to a working directory. */
    private static Path specDirectory() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("spec/fixtures"))) {
            directory = directory.getParent();
        }
        assertNotNull(directory, "no spec/fixtures above " + Path.of("").toAbsolutePath());
        return directory.resolve("spec/fixtures");
    }
}
