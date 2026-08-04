package io.ltr8.tson.regex;

import java.util.List;
import java.util.OptionalInt;

/**
 * The abstract syntax tree of a parsed I-Regexp pattern (RFC 9485) -- a sealed hierarchy mirroring the RFC's
 * own grammar productions, produced by {@link TsonRegex#parse} and consumed by a matcher (and, in time, the
 * choice-disjointness and constrained-decoding backends). Nodes are pure, immutable values and carry no
 * matching behaviour.
 *
 * <p>I-Regexp has no anchors ({@code ^}/{@code $} are ordinary literal characters, never assertions), no
 * capture, and no back-references -- so there is no anchor, capture-group, or back-reference node.
 */
public sealed interface RegexNode {

    /** {@code a|b|c} -- two or more alternative branches (a single branch is never wrapped in one). */
    record Alternation(List<RegexNode> alternatives) implements RegexNode {
        public Alternation {
            alternatives = List.copyOf(alternatives);
        }
    }

    /** A concatenation of pieces (a {@code branch}); an empty sequence matches the empty string. */
    record Sequence(List<RegexNode> pieces) implements RegexNode {
        public Sequence {
            pieces = List.copyOf(pieces);
        }
    }

    /**
     * A quantified atom (a {@code piece}). {@code min} is the lower bound; an empty {@code max} means
     * unbounded. {@code *} is {@code (0, ∞)}, {@code +} is {@code (1, ∞)}, {@code ?} is {@code (0, 1)},
     * {@code {n}} is {@code (n, n)}, {@code {n,}} is {@code (n, ∞)}, {@code {n,m}} is {@code (n, m)}.
     */
    record Repeat(RegexNode atom, int min, OptionalInt max) implements RegexNode {}

    /** A parenthesised sub-expression {@code ( ... )} -- grouping only; I-Regexp has no capture. */
    record Group(RegexNode body) implements RegexNode {}

    /** {@code .} -- matches any single character except line feed (U+000A) and carriage return (U+000D). */
    record AnyChar() implements RegexNode {}

    /** A single literal code point -- a {@code NormalChar} or a {@code SingleCharEsc}. Also a {@link Member}. */
    record Literal(int codePoint) implements RegexNode, Member {}

    /** {@code [...]} / {@code [^...]} -- a character class, optionally negated, over one or more members. */
    record CharClass(boolean negated, List<Member> members) implements RegexNode {
        public CharClass {
            members = List.copyOf(members);
        }
    }

    /** {@code \p{Cat}} / {@code \P{Cat}} -- a Unicode general-category class ({@code complement} for {@code \P}). Also a {@link Member}. */
    record CategoryEscape(RegexCategory category, boolean complement) implements RegexNode, Member {}

    /** A member of a {@link CharClass}: a single {@link Literal}, a {@link ClassRange}, or a {@link CategoryEscape}. */
    sealed interface Member permits Literal, ClassRange, CategoryEscape {}

    /** {@code a-z} inside a class -- an inclusive code-point range, {@code low <= high}. */
    record ClassRange(int low, int high) implements Member {}
}
