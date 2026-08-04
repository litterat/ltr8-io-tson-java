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
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * A {@link RegexNode} AST compiled to a Thompson NFA and matched by a Pike-VM simulation -- a set of active
 * threads advanced in lockstep over the input, so matching is <b>O(input × program)</b> with no backtracking
 * and hence no catastrophic-backtracking (ReDoS) blow-up. This is the payoff of I-Regexp being a true regular
 * language (no back-references or lookaround): a pattern like {@code (a+)+b} that hangs a backtracking engine
 * runs in linear time here.
 *
 * <p>Matching is <b>full-match</b> (the whole input must be consumed reaching an accepting state), per RFC
 * 9485 §3's XSD semantics. Code-point addressed throughout. Package-private; reached via {@link
 * TsonRegex#matches}.
 */
final class NfaProgram {

    // Opcodes. CONSUME advances one input code point if its predicate holds (implicit fall-through to pc+1);
    // SPLIT/JUMP are epsilon transitions; MATCH accepts.
    private static final int CONSUME = 0;
    private static final int SPLIT = 1;
    private static final int JUMP = 2;
    private static final int MATCH = 3;

    private final int[] op;
    private final IntPredicate[] consume; // non-null only for CONSUME
    private final int[] x;
    private final int[] y;

    private NfaProgram(int[] op, IntPredicate[] consume, int[] x, int[] y) {
        this.op = op;
        this.consume = consume;
        this.x = x;
        this.y = y;
    }

    static NfaProgram compile(RegexNode ast, String source) {
        Builder builder = new Builder(source);
        builder.node(ast);
        builder.emit(new Inst(MATCH, null));
        int n = builder.prog.size();
        int[] op = new int[n];
        IntPredicate[] consume = new IntPredicate[n];
        int[] x = new int[n];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            Inst inst = builder.prog.get(i);
            op[i] = inst.op;
            consume[i] = inst.consume;
            x[i] = inst.x;
            y[i] = inst.y;
        }
        return new NfaProgram(op, consume, x, y);
    }

    /** Whether {@code input} (the whole of it) matches. */
    boolean matches(int[] input) {
        int n = op.length;
        int[] seen = new int[n];
        IntBag current = new IntBag(n);
        IntBag next = new IntBag(n);
        IntBag stack = new IntBag(n);
        int gen = 1;
        addThreads(current, 0, seen, gen, stack);
        for (int ch : input) {
            if (current.isEmpty()) {
                return false; // no live threads and input remains -- cannot full-match
            }
            gen++;
            for (int k = 0; k < current.size(); k++) {
                int pc = current.get(k);
                if (op[pc] == CONSUME && consume[pc].test(ch)) {
                    addThreads(next, pc + 1, seen, gen, stack);
                }
            }
            IntBag swap = current;
            current = next;
            next = swap;
            next.clear();
        }
        for (int k = 0; k < current.size(); k++) {
            if (op[current.get(k)] == MATCH) {
                return true;
            }
        }
        return false;
    }

    /** Adds a thread and its epsilon-closure to {@code list}, deduped by {@code seen}/{@code gen}. */
    private void addThreads(IntBag list, int start, int[] seen, int gen, IntBag stack) {
        stack.clear();
        stack.add(start);
        while (!stack.isEmpty()) {
            int pc = stack.removeLast();
            if (seen[pc] == gen) {
                continue;
            }
            seen[pc] = gen;
            switch (op[pc]) {
                case JUMP -> stack.add(x[pc]);
                case SPLIT -> {
                    stack.add(x[pc]);
                    stack.add(y[pc]);
                }
                default -> list.add(pc); // CONSUME or MATCH -- a stopping instruction
            }
        }
    }

    // ── Thompson construction ─────────────────────────────────────────────────

    private static final class Builder {

        private static final int MAX_INSTRUCTIONS = 200_000;

        private final List<Inst> prog = new ArrayList<>();
        private final String source;

        Builder(String source) {
            this.source = source;
        }

        int emit(Inst inst) {
            if (prog.size() >= MAX_INSTRUCTIONS) {
                throw new TsonRegexSyntaxException("pattern expands to too many states to compile", source, 0);
            }
            prog.add(inst);
            return prog.size() - 1;
        }

        void node(RegexNode node) {
            switch (node) {
                case Literal lit -> emit(new Inst(CONSUME, cp -> cp == lit.codePoint()));
                case AnyChar ignored -> emit(new Inst(CONSUME, cp -> cp != '\n' && cp != '\r'));
                case CategoryEscape cat -> emit(new Inst(CONSUME, categoryPredicate(cat)));
                case CharClass cc -> emit(new Inst(CONSUME, charClassPredicate(cc)));
                case Group group -> node(group.body());
                case Sequence seq -> seq.pieces().forEach(this::node);
                case Alternation alt -> alternation(alt.alternatives());
                case Repeat rep -> repeat(rep);
            }
        }

        /** {@code a|b|c}: each non-final branch guarded by a SPLIT, each branch jumping to a shared end. */
        private void alternation(List<RegexNode> alternatives) {
            List<Integer> jumpsToEnd = new ArrayList<>();
            for (int i = 0; i < alternatives.size(); i++) {
                boolean last = i == alternatives.size() - 1;
                int split = last ? -1 : emit(new Inst(SPLIT, null));
                if (!last) {
                    prog.get(split).x = prog.size(); // branch body starts next
                }
                node(alternatives.get(i));
                if (!last) {
                    jumpsToEnd.add(emit(new Inst(JUMP, null)));
                    prog.get(split).y = prog.size(); // the "try the next branch" path
                }
            }
            int end = prog.size();
            for (int jump : jumpsToEnd) {
                prog.get(jump).x = end;
            }
        }

        private void repeat(Repeat rep) {
            for (int i = 0; i < rep.min(); i++) {
                node(rep.atom());
            }
            if (rep.max().isEmpty()) {
                star(rep.atom());
            } else {
                optional(rep.atom(), rep.max().getAsInt() - rep.min());
            }
        }

        /** {@code atom*}: {@code L: split BODY, END; BODY: <atom>; jmp L; END:} */
        private void star(RegexNode atom) {
            int split = emit(new Inst(SPLIT, null));
            prog.get(split).x = prog.size();
            node(atom);
            int jump = emit(new Inst(JUMP, null));
            prog.get(jump).x = split;
            prog.get(split).y = prog.size();
        }

        /** {@code count} optional copies of {@code atom}, each SPLIT skipping to a shared end ({@code {n,m}}). */
        private void optional(RegexNode atom, int count) {
            List<Integer> splits = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int split = emit(new Inst(SPLIT, null));
                splits.add(split);
                prog.get(split).x = prog.size();
                node(atom);
            }
            int end = prog.size();
            for (int split : splits) {
                prog.get(split).y = end;
            }
        }
    }

    // ── Predicates ─────────────────────────────────────────────────────────────

    private static IntPredicate categoryPredicate(CategoryEscape cat) {
        IntPredicate base = cp -> UnicodeCategories.matches(cat.category(), cp);
        return cat.complement() ? base.negate() : base;
    }

    private static IntPredicate charClassPredicate(CharClass cc) {
        IntPredicate union = cp -> false;
        for (Member member : cc.members()) {
            union = union.or(memberPredicate(member));
        }
        return cc.negated() ? union.negate() : union;
    }

    private static IntPredicate memberPredicate(Member member) {
        return switch (member) {
            case Literal lit -> cp -> cp == lit.codePoint();
            case ClassRange range -> cp -> cp >= range.low() && cp <= range.high();
            case CategoryEscape cat -> categoryPredicate(cat);
        };
    }

    // ── Small helpers ──────────────────────────────────────────────────────────

    /** A mutable instruction during construction; targets {@code x}/{@code y} are patched as forward refs resolve. */
    private static final class Inst {
        final int op;
        final IntPredicate consume;
        int x = -1;
        int y = -1;

        Inst(int op, IntPredicate consume) {
            this.op = op;
            this.consume = consume;
        }
    }

    /** A minimal growable int list, used both as a thread list and (LIFO) as the closure stack -- no boxing. */
    private static final class IntBag {
        private int[] a;
        private int size;

        IntBag(int capacity) {
            a = new int[Math.max(capacity, 8)];
        }

        void add(int value) {
            if (size == a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[size++] = value;
        }

        int get(int i) {
            return a[i];
        }

        int removeLast() {
            return a[--size];
        }

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void clear() {
            size = 0;
        }
    }
}
