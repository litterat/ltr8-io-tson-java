package io.ltr8.tson.regex;

import java.util.Objects;

/**
 * A parsed I-Regexp pattern (RFC 9485, "An Interoperable Regular Expression Format") -- this library's own
 * regex engine, so I-Regexp semantics are defined here rather than delegated to {@code java.util.regex} (which
 * accepts a large superset and matches shared constructs differently). {@link #parse} validates a pattern
 * against the RFC 9485 grammar and builds its {@link #ast() AST}, rejecting anything outside the interoperable
 * subset -- {@code \d}/{@code \w}/{@code \s}, character-class subtraction, capture/back-references, lookaround,
 * Unicode blocks, and so on. (I-Regexp treats {@code ^} and {@code $} as ordinary literal characters, not
 * anchors, so a pattern using them parses -- as literals.)
 *
 * <p>{@link #matches(String)} tests a whole string against the pattern in guaranteed linear time (a Thompson
 * NFA, no backtracking -- no ReDoS). The {@code Tson} prefix disambiguates from {@code java.util.regex} and
 * any domain {@code Regex}/{@code Pattern} at a call site.
 */
public final class TsonRegex {

    private final String pattern;
    private final RegexNode ast;
    private NfaProgram program; // compiled lazily on first match, then reused

    private TsonRegex(String pattern, RegexNode ast) {
        this.pattern = pattern;
        this.ast = ast;
    }

    /**
     * Parses {@code pattern} as I-Regexp, returning its AST.
     *
     * @throws TsonRegexSyntaxException if {@code pattern} is not valid I-Regexp
     */
    public static TsonRegex parse(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        return new TsonRegex(pattern, new IRegexParser(pattern).parse());
    }

    /**
     * Whether {@code input} matches this pattern in its entirety (RFC 9485 §3's full-match semantics). Runs a
     * Thompson-NFA simulation in time linear in the input length -- no backtracking, so no catastrophic
     * blow-up on adversarial patterns. The NFA is compiled on the first call and reused.
     */
    public boolean matches(String input) {
        Objects.requireNonNull(input, "input");
        NfaProgram compiled = program;
        if (compiled == null) {
            compiled = NfaProgram.compile(ast, pattern);
            program = compiled;
        }
        return compiled.matches(input.codePoints().toArray());
    }

    /**
     * Whether this pattern and {@code other} are <b>disjoint</b> -- no string matches both. Exact (regular
     * languages have a decidable intersection-emptiness), so this is a definitive yes/no, never "unknown".
     * The building block for a schema resolver's §5.4 pattern-disjointness derivation over {@code
     * regex}-constrained atoms, where two variants whose patterns are disjoint may drop their discriminating
     * tag.
     */
    public boolean isDisjointFrom(TsonRegex other) {
        Objects.requireNonNull(other, "other");
        return RegexDisjointness.disjoint(ast, other.ast);
    }

    /** The parsed syntax tree. */
    public RegexNode ast() {
        return ast;
    }

    /** The source pattern text this was parsed from. */
    public String pattern() {
        return pattern;
    }

    @Override
    public String toString() {
        return "TsonRegex[" + pattern + "]";
    }
}
