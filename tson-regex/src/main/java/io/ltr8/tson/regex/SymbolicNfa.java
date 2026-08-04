package io.ltr8.tson.regex;

import io.ltr8.tson.regex.RegexNode.Alternation;
import io.ltr8.tson.regex.RegexNode.AnyChar;
import io.ltr8.tson.regex.RegexNode.CategoryEscape;
import io.ltr8.tson.regex.RegexNode.CharClass;
import io.ltr8.tson.regex.RegexNode.ClassRange;
import io.ltr8.tson.regex.RegexNode.Group;
import io.ltr8.tson.regex.RegexNode.Literal;
import io.ltr8.tson.regex.RegexNode.Member;
import io.ltr8.tson.regex.RegexNode.Repeat;
import io.ltr8.tson.regex.RegexNode.Sequence;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link RegexNode} AST as a Thompson NFA whose consuming transitions are labelled with concrete {@link
 * CodePointSet}s (not opaque predicates), so a product automaton can be explored symbolically -- the form
 * {@linkplain RegexDisjointness disjointness} needs. Distinct from {@link NfaProgram}, the matcher's Pike-VM
 * program: same construction, different transition labels, chosen for a different downstream operation.
 */
final class SymbolicNfa {

    record Edge(CodePointSet set, int to) {}

    private final List<List<Integer>> epsilon = new ArrayList<>();
    private final List<List<Edge>> labelled = new ArrayList<>();
    private int start;
    private int accept;

    static SymbolicNfa build(RegexNode ast) {
        SymbolicNfa nfa = new SymbolicNfa();
        int[] fragment = nfa.node(ast);
        nfa.start = fragment[0];
        nfa.accept = fragment[1];
        return nfa;
    }

    int start() {
        return start;
    }

    int accept() {
        return accept;
    }

    List<Integer> epsilonOf(int state) {
        return epsilon.get(state);
    }

    List<Edge> edgesOf(int state) {
        return labelled.get(state);
    }

    // ── Thompson construction ──────────────────────────────────────────────────

    private int newState() {
        epsilon.add(new ArrayList<>());
        labelled.add(new ArrayList<>());
        return epsilon.size() - 1;
    }

    private void addEpsilon(int from, int to) {
        epsilon.get(from).add(to);
    }

    private void addEdge(int from, CodePointSet set, int to) {
        labelled.get(from).add(new Edge(set, to));
    }

    /** Builds {@code node} into fresh states, returning {@code {start, accept}}. */
    private int[] node(RegexNode node) {
        return switch (node) {
            case Literal lit -> consuming(CodePointSet.single(lit.codePoint()));
            case AnyChar ignored -> consuming(anyChar());
            case CategoryEscape cat -> consuming(categorySet(cat));
            case CharClass cc -> consuming(charClassSet(cc));
            case Group group -> node(group.body());
            case Sequence seq -> chain(seq.pieces().stream().map(this::node).toList());
            case Alternation alt -> alternation(alt.alternatives());
            case Repeat rep -> repeat(rep);
        };
    }

    private int[] consuming(CodePointSet set) {
        int s = newState();
        int e = newState();
        addEdge(s, set, e);
        return new int[] {s, e};
    }

    private int[] alternation(List<RegexNode> alternatives) {
        int s = newState();
        int e = newState();
        for (RegexNode alt : alternatives) {
            int[] fragment = node(alt);
            addEpsilon(s, fragment[0]);
            addEpsilon(fragment[1], e);
        }
        return new int[] {s, e};
    }

    private int[] repeat(Repeat rep) {
        List<int[]> fragments = new ArrayList<>();
        for (int i = 0; i < rep.min(); i++) {
            fragments.add(node(rep.atom()));
        }
        if (rep.max().isEmpty()) {
            fragments.add(star(rep.atom()));
        } else {
            for (int i = 0; i < rep.max().getAsInt() - rep.min(); i++) {
                fragments.add(optional(rep.atom()));
            }
        }
        return chain(fragments);
    }

    /** {@code atom*} */
    private int[] star(RegexNode atom) {
        int s = newState();
        int e = newState();
        int[] fragment = node(atom);
        addEpsilon(s, fragment[0]);
        addEpsilon(s, e);
        addEpsilon(fragment[1], fragment[0]);
        addEpsilon(fragment[1], e);
        return new int[] {s, e};
    }

    /** {@code atom?} */
    private int[] optional(RegexNode atom) {
        int s = newState();
        int e = newState();
        int[] fragment = node(atom);
        addEpsilon(s, fragment[0]);
        addEpsilon(s, e);
        addEpsilon(fragment[1], e);
        return new int[] {s, e};
    }

    /** Concatenates fragments end-to-start; an empty list is a single state that matches the empty string. */
    private int[] chain(List<int[]> fragments) {
        if (fragments.isEmpty()) {
            int s = newState();
            return new int[] {s, s};
        }
        for (int k = 0; k < fragments.size() - 1; k++) {
            addEpsilon(fragments.get(k)[1], fragments.get(k + 1)[0]);
        }
        return new int[] {fragments.get(0)[0], fragments.get(fragments.size() - 1)[1]};
    }

    // ── Transition label sets ──────────────────────────────────────────────────

    private static CodePointSet anyChar() {
        // any code point except line feed and carriage return
        return CodePointSet.union(CodePointSet.single('\n'), CodePointSet.single('\r')).complement();
    }

    private static CodePointSet categorySet(CategoryEscape cat) {
        CodePointSet set = CodePointSet.ofCategory(cat.category());
        return cat.complement() ? set.complement() : set;
    }

    private static CodePointSet charClassSet(CharClass cc) {
        CodePointSet set = CodePointSet.EMPTY;
        for (Member member : cc.members()) {
            set = CodePointSet.union(set, memberSet(member));
        }
        return cc.negated() ? set.complement() : set;
    }

    private static CodePointSet memberSet(Member member) {
        return switch (member) {
            case Literal lit -> CodePointSet.single(lit.codePoint());
            case ClassRange range -> CodePointSet.of(range.low(), range.high());
            case CategoryEscape cat -> categorySet(cat);
        };
    }
}
