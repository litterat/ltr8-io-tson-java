package io.ltr8.tson.regex;

import io.ltr8.tson.regex.SymbolicNfa.Edge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether two I-Regexp patterns are <b>disjoint</b> -- no string matches both -- by exploring the
 * product of their {@link SymbolicNfa}s and asking whether any pair of accepting states is jointly reachable.
 * Regular-language intersection emptiness is decidable, so this is <b>exact</b>: it returns a definitive
 * yes/no, never a conservative "unknown". (The resolver's §5.4 disjointness derivation may still leave the
 * fact absent for cases it doesn't attempt; that conservatism is its choice, not a limit of this primitive.)
 *
 * <p>The alphabet is Unicode-sized, so the product is explored symbolically: at each product configuration
 * (a set of active states in each NFA, epsilon-closed), the outgoing transition label sets are partitioned
 * into elementary intervals over which every transition's firing is constant; one representative per
 * elementary interval advances both sides. A configuration is revisited at most once, so the search
 * terminates.
 */
final class RegexDisjointness {

    private RegexDisjointness() {
    }

    static boolean disjoint(RegexNode a, RegexNode b) {
        SymbolicNfa na = SymbolicNfa.build(a);
        SymbolicNfa nb = SymbolicNfa.build(b);

        Deque<Config> queue = new ArrayDeque<>();
        Set<Config> visited = new HashSet<>();
        queue.add(new Config(closure(na, na.start()), closure(nb, nb.start())));

        while (!queue.isEmpty()) {
            Config config = queue.poll();
            if (!visited.add(config)) {
                continue;
            }
            if (config.a.get(na.accept()) && config.b.get(nb.accept())) {
                return false; // a common string reaches acceptance in both -- not disjoint
            }
            for (int representative : representatives(na, config.a, nb, config.b)) {
                BitSet nextA = step(na, config.a, representative);
                if (nextA.isEmpty()) {
                    continue; // a cannot consume this character, so no common string uses it
                }
                BitSet nextB = step(nb, config.b, representative);
                if (nextB.isEmpty()) {
                    continue;
                }
                queue.add(new Config(nextA, nextB));
            }
        }
        return true; // no jointly-accepting configuration is reachable
    }

    /** A product configuration: the epsilon-closed active-state sets of each NFA. */
    private record Config(BitSet a, BitSet b) {}

    /** Epsilon-closure of a single start state. */
    private static BitSet closure(SymbolicNfa nfa, int start) {
        BitSet seed = new BitSet();
        seed.set(start);
        return closure(nfa, seed);
    }

    /** Epsilon-closure of a set of states. */
    private static BitSet closure(SymbolicNfa nfa, BitSet states) {
        BitSet closed = new BitSet();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int s = states.nextSetBit(0); s >= 0; s = states.nextSetBit(s + 1)) {
            stack.push(s);
        }
        while (!stack.isEmpty()) {
            int state = stack.pop();
            if (closed.get(state)) {
                continue;
            }
            closed.set(state);
            for (int target : nfa.epsilonOf(state)) {
                stack.push(target);
            }
        }
        return closed;
    }

    /** The states reachable from {@code states} by consuming {@code codePoint}, epsilon-closed. */
    private static BitSet step(SymbolicNfa nfa, BitSet states, int codePoint) {
        BitSet targets = new BitSet();
        for (int s = states.nextSetBit(0); s >= 0; s = states.nextSetBit(s + 1)) {
            for (Edge edge : nfa.edgesOf(s)) {
                if (edge.set().contains(codePoint)) {
                    targets.set(edge.to());
                }
            }
        }
        return closure(nfa, targets);
    }

    /**
     * One representative code point per elementary interval of all outgoing transition labels from both
     * configurations -- the minterms over which each transition fires constantly. Stepping every
     * representative covers every distinguishable next character finitely.
     */
    private static int[] representatives(SymbolicNfa na, BitSet a, SymbolicNfa nb, BitSet b) {
        TreeSet<Integer> boundaries = new TreeSet<>();
        collectBoundaries(na, a, boundaries);
        collectBoundaries(nb, b, boundaries);
        List<Integer> reps = new ArrayList<>();
        for (int boundary : boundaries) {
            if (boundary <= CodePointSet.MAX) {
                reps.add(boundary); // the low end of an elementary interval
            }
        }
        return reps.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void collectBoundaries(SymbolicNfa nfa, BitSet states, TreeSet<Integer> boundaries) {
        for (int s = states.nextSetBit(0); s >= 0; s = states.nextSetBit(s + 1)) {
            for (Edge edge : nfa.edgesOf(s)) {
                int[] intervals = edge.set().intervals();
                for (int k = 0; k < intervals.length; k += 2) {
                    boundaries.add(intervals[k]);
                    boundaries.add(intervals[k + 1] + 1);
                }
            }
        }
    }
}
