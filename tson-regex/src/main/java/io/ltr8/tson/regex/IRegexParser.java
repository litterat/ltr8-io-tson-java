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
import java.util.OptionalInt;

/**
 * A recursive-descent parser for the RFC 9485 I-Regexp grammar (§3), producing a {@link RegexNode} tree.
 * Code-point addressed (surrogate pairs are single atoms), so supplementary-plane characters parse. The
 * grammar itself is the subset gate: anything the productions don't admit -- {@code \d}/{@code \w}/{@code
 * \s}, character-class subtraction, capture/back-references, lookaround, Unicode blocks, non-greedy
 * quantifiers -- is a {@link TsonRegexSyntaxException}, no separate rejection pass needed. Package-private;
 * {@link TsonRegex#parse} is the entry point.
 */
final class IRegexParser {

    private final String source;
    private final int[] cp;
    private int pos;

    IRegexParser(String source) {
        this.source = source;
        this.cp = source.codePoints().toArray();
    }

    /** {@code i-regexp} to end of input. */
    RegexNode parse() {
        RegexNode node = iRegexp();
        if (!atEnd()) {
            throw error("unexpected '" + display(current()) + "'");
        }
        return node;
    }

    // ── Grammar productions ──────────────────────────────────────────────────

    /** {@code i-regexp = branch *( "|" branch )} */
    private RegexNode iRegexp() {
        List<RegexNode> branches = new ArrayList<>();
        branches.add(branch());
        while (peek('|')) {
            advance();
            branches.add(branch());
        }
        return branches.size() == 1 ? branches.get(0) : new Alternation(branches);
    }

    /** {@code branch = *piece} -- stops at {@code |}, {@code )}, or end. */
    private RegexNode branch() {
        List<RegexNode> pieces = new ArrayList<>();
        while (!atEnd() && !peek('|') && !peek(')')) {
            pieces.add(piece());
        }
        return pieces.size() == 1 ? pieces.get(0) : new Sequence(pieces);
    }

    /** {@code piece = atom [ quantifier ]} */
    private RegexNode piece() {
        RegexNode atom = atom();
        Quant q = quantifier();
        return q == null ? atom : new Repeat(atom, q.min(), q.max());
    }

    /** {@code atom = NormalChar / charClass / ( "(" i-regexp ")" )} */
    private RegexNode atom() {
        int c = current();
        if (c == '(') {
            advance();
            RegexNode body = iRegexp();
            expect(')');
            return new Group(body);
        }
        if (c == '.') {
            advance();
            return new AnyChar();
        }
        if (c == '\\') {
            return escape();
        }
        if (c == '[') {
            return charClassExpr();
        }
        if (isNormalChar(c)) {
            advance();
            return new Literal(c);
        }
        throw error("unexpected '" + display(c) + "' where an atom was expected");
    }

    /** {@code SingleCharEsc} (a literal) or {@code charClassEsc} ({@code \p}/{@code \P}). */
    private RegexNode escape() {
        advance(); // the backslash
        if (atEnd()) {
            throw error("trailing '\\' escape");
        }
        int c = current();
        if (c == 'p' || c == 'P') {
            return categoryEscape();
        }
        return new Literal(singleCharEsc());
    }

    /** {@code catEsc}/{@code complEsc} -- {@code \p{Cat}}/{@code \P{Cat}}; current is {@code p}/{@code P}. */
    private CategoryEscape categoryEscape() {
        boolean complement = current() == 'P';
        advance(); // 'p' or 'P'
        expect('{');
        StringBuilder name = new StringBuilder();
        while (!atEnd() && current() != '}') {
            name.appendCodePoint(current());
            advance();
        }
        expect('}');
        return new CategoryEscape(category(name.toString()), complement);
    }

    /** {@code charClassExpr = "[" [ "^" ] ( "-" / CCE1 ) *CCE1 [ "-" ] "]"} */
    private CharClass charClassExpr() {
        advance(); // '['
        boolean negated = !atEnd() && current() == '^';
        if (negated) {
            advance();
        }
        List<Member> members = new ArrayList<>();
        boolean first = true;
        while (true) {
            if (atEnd()) {
                throw error("unterminated character class");
            }
            int c = current();
            if (c == ']') {
                if (first) {
                    throw error("empty character class");
                }
                break;
            }
            if (c == '-' && (first || peekAt(pos + 1) == ']')) {
                // A '-' is a literal only as the first member or immediately before the closing ']'.
                members.add(new Literal('-'));
                advance();
            } else if (c == '\\' && (peekAt(pos + 1) == 'p' || peekAt(pos + 1) == 'P')) {
                advance(); // '\'
                members.add(categoryEscape());
            } else {
                int lo = ccChar();
                if (!atEnd() && current() == '-' && peekAt(pos + 1) != -1 && peekAt(pos + 1) != ']') {
                    advance(); // '-'
                    int hi = ccChar();
                    if (hi < lo) {
                        throw error("character range '" + display(lo) + "-" + display(hi) + "' is out of order");
                    }
                    members.add(new ClassRange(lo, hi));
                } else {
                    members.add(new Literal(lo));
                }
            }
            first = false;
        }
        advance(); // ']'
        return new CharClass(negated, members);
    }

    /** {@code CCchar} -- a bare class character or a {@code SingleCharEsc}. */
    private int ccChar() {
        int c = current();
        if (c == '\\') {
            advance();
            if (atEnd()) {
                throw error("trailing '\\' in character class");
            }
            return singleCharEsc();
        }
        if (isCCchar(c)) {
            advance();
            return c;
        }
        throw error("'" + display(c) + "' is not valid inside a character class");
    }

    /** A {@code SingleCharEsc} body (the char after {@code \}); returns the literal code point it denotes. */
    private int singleCharEsc() {
        int c = current();
        int decoded = switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> {
                if (isSingleCharEscMeta(c)) {
                    yield c;
                }
                throw error("'\\" + display(c) + "' is not a valid I-Regexp escape");
            }
        };
        advance();
        return decoded;
    }

    /** {@code quantifier} -- {@code * + ?} or a {@code {n}}/{@code {n,}}/{@code {n,m}} range; null if none. */
    private Quant quantifier() {
        if (atEnd()) {
            return null;
        }
        return switch (current()) {
            case '*' -> { advance(); yield new Quant(0, OptionalInt.empty()); }
            case '+' -> { advance(); yield new Quant(1, OptionalInt.empty()); }
            case '?' -> { advance(); yield new Quant(0, OptionalInt.of(1)); }
            case '{' -> rangeQuantifier();
            default -> null;
        };
    }

    /** {@code range-quantifier = "{" QuantExact [ "," [ QuantExact ] ] "}"} */
    private Quant rangeQuantifier() {
        advance(); // '{'
        int min = quantExact();
        OptionalInt max;
        if (!atEnd() && current() == ',') {
            advance();
            if (!atEnd() && current() == '}') {
                max = OptionalInt.empty(); // {n,}
            } else {
                int m = quantExact();
                if (m < min) {
                    throw error("quantifier {" + min + "," + m + "} is out of order");
                }
                max = OptionalInt.of(m);
            }
        } else {
            max = OptionalInt.of(min); // {n}
        }
        expect('}');
        return new Quant(min, max);
    }

    /** {@code QuantExact = 1*DIGIT} */
    private int quantExact() {
        if (atEnd() || !isDigit(current())) {
            throw error("expected a decimal quantity");
        }
        long value = 0;
        while (!atEnd() && isDigit(current())) {
            value = value * 10 + (current() - '0');
            if (value > Integer.MAX_VALUE) {
                throw error("quantifier is too large");
            }
            advance();
        }
        return (int) value;
    }

    // ── Character-class predicates (verbatim from the RFC's code-point ranges) ─

    /** {@code NormalChar} -- every code point except the twelve metacharacters and the surrogate range. */
    private static boolean isNormalChar(int c) {
        return (c >= 0x00 && c <= 0x27)          // up to '
            || c == 0x2C || c == 0x2D            // , -
            || (c >= 0x2F && c <= 0x3E)          // / .. > (excludes '.')
            || (c >= 0x40 && c <= 0x5A)          // @ A-Z
            || (c >= 0x5E && c <= 0x7A)          // ^ _ ` a-z
            || (c >= 0x7E && c <= 0xD7FF)        // ~ .. (before surrogates)
            || (c >= 0xE000 && c <= 0x10FFFF);   // (after surrogates)
        // excluded: ( ) * + . ? [ \ ] { | } and D800-DFFF
    }

    /** {@code CCchar} -- a bare character permitted inside a class (excludes {@code - [ \ ]} and surrogates). */
    private static boolean isCCchar(int c) {
        return (c >= 0x00 && c <= 0x2C)          // up to ',' (excludes '-')
            || (c >= 0x2E && c <= 0x5A)          // . .. Z (excludes '[')
            || (c >= 0x5E && c <= 0xD7FF)        // ^ .. (excludes '\' and ']')
            || (c >= 0xE000 && c <= 0x10FFFF);
    }

    /** The metacharacters a {@code SingleCharEsc} may escape (besides {@code \n \r \t}). */
    private static boolean isSingleCharEscMeta(int c) {
        return (c >= 0x28 && c <= 0x2B)          // ( ) * +
            || c == '-' || c == '.' || c == '?'
            || (c >= 0x5B && c <= 0x5E)          // [ \ ] ^
            || (c >= 0x7B && c <= 0x7D);         // { | }
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private RegexCategory category(String name) {
        try {
            return RegexCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw error("'\\p{" + name + "}' is not a valid I-Regexp Unicode category");
        }
    }

    // ── Cursor ───────────────────────────────────────────────────────────────

    private boolean atEnd() {
        return pos >= cp.length;
    }

    private int current() {
        return cp[pos];
    }

    private boolean peek(int ch) {
        return !atEnd() && cp[pos] == ch;
    }

    private int peekAt(int index) {
        return index >= 0 && index < cp.length ? cp[index] : -1;
    }

    private void advance() {
        pos++;
    }

    private void expect(int ch) {
        if (atEnd() || cp[pos] != ch) {
            throw error("expected '" + display(ch) + "'");
        }
        pos++;
    }

    private TsonRegexSyntaxException error(String message) {
        return new TsonRegexSyntaxException(message, source, pos);
    }

    private static String display(int codePoint) {
        return codePoint < 0 ? "<end>" : new String(Character.toChars(codePoint));
    }

    /** A parsed quantifier's bounds; {@code max} empty means unbounded. */
    private record Quant(int min, OptionalInt max) {}
}
