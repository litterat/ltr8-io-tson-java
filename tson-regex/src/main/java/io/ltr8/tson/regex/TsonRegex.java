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
 * <p>This is the parse/validate front door; matching a string against the pattern is a separate capability
 * built on {@link #ast()}. The {@code Tson} prefix disambiguates from {@code java.util.regex} and any domain
 * {@code Regex}/{@code Pattern} at a call site.
 */
public final class TsonRegex {

    private final String pattern;
    private final RegexNode ast;

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
