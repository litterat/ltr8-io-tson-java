package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.compiler.lexer.Lexer;
import io.ltr8.tson.compiler.lexer.Token;
import io.ltr8.tson.compiler.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A schema document's reading form: the same declarations, in as few tokens as the syntax allows, for a reader
 * that reads a schema rather than resolving it -- a language model given one in a prompt. It is valid syntax
 * and is <em>not</em> a loadable schema.
 *
 * <p>Four things are removed or shortened, and nothing else changes:
 * <ul>
 *   <li><b>{@code !!id}</b> goes: nothing resolves the copy, and a reading form claiming the identity of a
 *       document whose bytes it does not have would be a second document under one published name.</li>
 *   <li><b>A header reference's {@code ?sha256=} pin</b> goes. A pin is verification metadata ([TSON-DATA]
 *       §2.2.1), and the query of an identifying URI holds nothing else.</li>
 *   <li><b>A header reference to the spec's own library</b> -- meta-kernel, meta and core at any revision -- is
 *       shortened to its revision and name ({@code "37/meta"}). Any other reference keeps its URL, which is
 *       the only thing that tells a reader which schema it names.</li>
 *   <li><b>{@code @doc}</b> goes, wherever it is written. Every other annotation stays: several are checked
 *       and change what the schema means.</li>
 * </ul>
 *
 * <p><b>Whitespace is collapsed, never removed.</b> TSON has no comments, so the token stream is the whole
 * document, and the grammar reads adjacency: some tokens must touch ({@code @doc:}, {@code name?:}) and some
 * must not. So a run of whitespace between two tokens becomes one space, tokens that touched still touch, and
 * where a token was removed the two either side are separated. A multi-line token is rewritten single-line,
 * with escapes, as the same text.
 *
 * <p><b>One line per header directive and per declaration</b>, and the schema map's closing brace on its own,
 * so a reader can find an entry by its line; inside a declaration everything is on one line. A line break
 * costs what a space does, and stands only where whitespace already may.
 *
 * <p>The output is lexed again and must give the token stream this intended, then parsed as a schema
 * document; either failing is a fault here, never a verdict on the input.
 */
public final class TsonSchemaStripper {

    /** A canonical identity in the spec's library: {@code tson.io/<year>/<revision>/m/<name>.tn}. */
    private static final Pattern SPEC_LIBRARY = Pattern.compile("tson\\.io/\\d{4}/(\\d+)/m/(meta-kernel|meta|core)\\.tn");

    private TsonSchemaStripper() {
    }

    /**
     * The reading form of {@code source}, a schema document.
     *
     * @throws io.ltr8.tson.base.ParseException if {@code source} is not a well-formed schema document, or
     *         the lexer's own exception for a token that does not lex -- {@link
     *         TsonDiagnostics#ofSchemaSyntaxError(String, RuntimeException)} classifies either
     */
    public static String strip(String source) {
        new TsonSchemaParser(source).parseSchemaDocument();
        List<Token> tokens = new Lexer(ByteSource.of(source)).tokenize();

        boolean[] lineBreaks = lineBreaks(tokens);
        StringBuilder out = new StringBuilder(source.length());
        List<Token> intended = new ArrayList<>();
        Token previous = null;
        boolean removed = false;
        boolean lineBreak = false;
        for (int i = 0; i < tokens.size() && tokens.get(i).type() != TokenType.EOF; i++) {
            lineBreak |= lineBreaks[i];
            int removable = removable(tokens, i);
            if (removable > 0) {
                removed = true;
                i += removable - 1;
                continue;
            }
            Token token = tokens.get(i);
            if (previous != null && lineBreak) {
                out.append('\n');
            } else if (previous != null && (removed || !previous.adjacentTo(token))) {
                out.append(' ');
            }
            Token written = isDirectiveArgument(tokens, i) ? withText(token, shortened(token.text())) : token;
            out.append(spelling(written));
            intended.add(written);
            previous = token;
            removed = false;
            lineBreak = false;
        }
        String stripped = out.append('\n').toString();
        verify(stripped, intended);
        return stripped;
    }

    /**
     * Which tokens start a line: the token after each header directive, each declaration's first token (its
     * first annotation, or its name), and the schema map's closing brace.
     */
    private static boolean[] lineBreaks(List<Token> tokens) {
        boolean[] breaks = new boolean[tokens.size()];
        int i = 0;
        while (tokens.get(i).type() == TokenType.DIRECTIVE) {
            i += 4;   // !! name : "..."
            breaks[i] = true;
        }
        while (tokens.get(i).type() == TokenType.AT) {
            i += annotationLength(tokens, i);
        }
        int depth = 0;
        for (i++; depth > 0 || tokens.get(i).type() != TokenType.RBRACE; i++) {
            if (depth == 0) {
                int arrow = entryArrow(tokens, i);
                if (arrow > 0) {
                    breaks[i] = true;
                    i = arrow;
                    continue;
                }
            }
            switch (tokens.get(i).type()) {
                case LBRACE, LBRACKET, LPAREN -> depth++;
                case RBRACE, RBRACKET, RPAREN -> depth--;
                default -> {
                }
            }
        }
        breaks[i] = true;
        return breaks;
    }

    /**
     * The index of the {@code =>} of the declaration starting at {@code i} -- its annotations, then its name --
     * or 0 if no declaration starts there.
     */
    private static int entryArrow(List<Token> tokens, int i) {
        while (tokens.get(i).type() == TokenType.AT) {
            i += annotationLength(tokens, i);
        }
        boolean named = tokens.get(i).type() == TokenType.UNQUOTED;
        return named && tokens.get(i + 1).type() == TokenType.MAP_ARROW ? i + 1 : 0;
    }

    /** How many tokens from {@code i} are removed -- an {@code !!id} directive or a {@code @doc} annotation -- or 0. */
    private static int removable(List<Token> tokens, int i) {
        Token token = tokens.get(i);
        if (token.type() == TokenType.DIRECTIVE && named(tokens, i + 1, "id")) {
            return 4;   // !! id : "..."
        }
        if (token.type() == TokenType.AT && named(tokens, i + 1, "doc")) {
            return annotationLength(tokens, i);
        }
        return 0;
    }

    /** The tokens of the annotation starting at {@code i}: {@code @name}, then {@code :value} if it has one. */
    private static int annotationLength(List<Token> tokens, int i) {
        Token colon = tokens.get(i + 2);
        if (colon.type() != TokenType.COLON || !tokens.get(i + 1).adjacentTo(colon)) {
            return 2;
        }
        return 3 + valueLength(tokens, i + 3);
    }

    /** The tokens of the value starting at {@code i}: a type-ref and then one token, or one bracketed group. */
    private static int valueLength(List<Token> tokens, int i) {
        int start = i;
        if (tokens.get(i).type() == TokenType.BANG) {
            i += 2;
        }
        int depth = 0;
        do {
            switch (tokens.get(i).type()) {
                case LBRACE, LBRACKET, LPAREN -> depth++;
                case RBRACE, RBRACKET, RPAREN -> depth--;
                default -> {
                }
            }
            i++;
        } while (depth > 0);
        return i - start;
    }

    private static boolean named(List<Token> tokens, int i, String name) {
        return i < tokens.size() && tokens.get(i).type() == TokenType.UNQUOTED && tokens.get(i).text().equals(name);
    }

    /** Whether the token at {@code i} is a header directive's argument -- {@code !! name : "..."}. */
    private static boolean isDirectiveArgument(List<Token> tokens, int i) {
        return i >= 3 && tokens.get(i - 3).type() == TokenType.DIRECTIVE && tokens.get(i - 1).type() == TokenType.COLON;
    }

    /** {@code reference} with its pin removed, and shortened to {@code <revision>/<name>} if the spec's library names it. */
    private static String shortened(String reference) {
        String unpinned = withoutQuery(reference);
        try {
            Matcher library = SPEC_LIBRARY.matcher(CanonicalIdentity.canonicalize(unpinned));
            if (library.matches()) {
                return library.group(1) + "/" + library.group(2);
            }
        } catch (SchemaValidationException e) {
            // Not an identity at all, so not the library's: the reference keeps its spelling.
        }
        return unpinned;
    }

    /** {@code reference} without its query component -- which, in an identifying URI, is only ever hash pins. */
    private static String withoutQuery(String reference) {
        int query = reference.indexOf('?');
        if (query < 0) {
            return reference;
        }
        int fragment = reference.indexOf('#', query);
        return reference.substring(0, query) + (fragment < 0 ? "" : reference.substring(fragment));
    }

    private static Token withText(Token token, String text) {
        return new Token(token.type(), text, token.startLine(), token.startColumn(), token.startByteOffset(),
                token.endLine(), token.endColumn(), token.endByteOffset());
    }

    /** The token as written: a lexeme as it was, a quoted token re-quoted single-line from its decoded text. */
    private static String spelling(Token token) {
        return switch (token.type()) {
            case SINGLE_LINE_STRING, MULTI_LINE_STRING -> {
                StringBuilder quoted = new StringBuilder();
                new TsonDataEmitter(quoted).quotedString(token.text());
                yield quoted.toString();
            }
            default -> token.text();
        };
    }

    /** The output lexes to {@code intended}, every quoted token single-line, and parses as a schema document. */
    private static void verify(String stripped, List<Token> intended) {
        List<Token> relexed = new Lexer(ByteSource.of(stripped)).tokenize();
        boolean same = relexed.size() == intended.size() + 1;
        for (int i = 0; same && i < intended.size(); i++) {
            TokenType expected = intended.get(i).type() == TokenType.MULTI_LINE_STRING
                    ? TokenType.SINGLE_LINE_STRING : intended.get(i).type();
            same = relexed.get(i).type() == expected && relexed.get(i).text().equals(intended.get(i).text());
        }
        if (!same) {
            throw new IllegalStateException("the stripped schema does not lex to the tokens it was written from");
        }
        try {
            new TsonSchemaParser(stripped).parseSchemaDocument();
        } catch (RuntimeException e) {
            throw new IllegalStateException("the stripped schema does not parse: " + e.getMessage(), e);
        }
    }
}
