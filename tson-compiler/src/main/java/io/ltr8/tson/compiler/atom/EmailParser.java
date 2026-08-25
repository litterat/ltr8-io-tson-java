package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.regex.TsonRegex;
import io.ltr8.tson.schema.meta.EmailType;

import java.util.regex.Pattern;

/**
 * Parses and validates against meta.tn's {@code email_type} constructor (core.tn's {@code email}, pinned to
 * RFC 5322). Composes {@code text_type}'s length and pattern facets, checked exactly as {@link TextParser}
 * checks its own, plus an address-shape check on top -- without that last part {@code !email} would say
 * nothing {@code text} doesn't.
 *
 * <p><b>Registered in {@link BuiltinTypeVocabulary} although §5.5's table has no row for it</b> -- a known
 * departure, the same kind as the {@code int8}..{@code int256} ladder. §5.5 promotes every sibling in
 * core.tn's "Network Types" group ({@code uuid}, {@code ipv4}, {@code ipv6}, {@code cidr4}, {@code cidr6},
 * {@code mac}) and omits only this one, with no stated rationale; §5.1 invites a reader to treat core.tn as
 * the vocabulary's source of truth. Given a working parser, withholding it from the schemaless path would
 * buy nothing and would leave the two read paths disagreeing about what {@code !email} means. See {@code
 * spec/tson-rev33-changelog.md} #5.
 *
 * <p><b>The format check is a documented subset of RFC 5322, not the whole grammar.</b> Accepted is the
 * {@code dot-atom "@" dot-atom} core: one or more dot-separated atoms of RFC 5322's {@code atext} on each
 * side, no leading, trailing or doubled dot. Deliberately <em>not</em> accepted, though RFC 5322's
 * {@code addr-spec} admits them: a quoted-string local part ({@code "a b"@example.com}), a domain literal
 * ({@code user@[192.0.2.1]}), and comments or folding whitespace anywhere ({@code user(note)@example.com}).
 * Those forms are legal, essentially unused in the data-interchange setting TSON targets, and accepting
 * them would mean admitting addresses containing spaces and parentheses into a field most consumers treat
 * as a simple token. The narrower rule rejects some valid RFC 5322 addresses; that is the trade, and it is
 * stated here rather than discovered. {@code spec/tson-rev33-changelog.md} #22 raises the general question this is an
 * instance of -- whether an RFC pin is a strict conformance gate, and whether divergence must be documented.
 *
 * <p>Host type is {@link String}: an address IS-A piece of text (it composes {@code text_type}), so like
 * {@link RegexParser} it hands back the text itself rather than a parsed structure, which also lets it bind
 * generically with no {@code DataBridge}.
 */
public record EmailParser(EmailType constraints) implements AtomType<String> {

    /** core.tn's own name for this atom -- registered as a built-in despite §5.5's table, see this class's own Javadoc. */
    public static final String TYPENAME = "email";

    /** {@code email => !email_type {}} -- the unconstrained address, core.tn's own {@code email}. */
    public static final EmailParser UNCONSTRAINED = new EmailParser(EmailType.UNCONSTRAINED);

    /**
     * RFC 5322's {@code dot-atom "@" dot-atom}. {@code atext} is the RFC's own printable set minus the
     * specials; the {@code (?:\.atom)*} shape is what forbids a leading, trailing or doubled dot without a
     * second pass.
     */
    private static final String ATEXT = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final Pattern ADDR_SPEC = Pattern.compile(
            ATEXT + "(?:\\." + ATEXT + ")*@" + ATEXT + "(?:\\." + ATEXT + ")*");

    @Override
    public String read(TokenValue token) {
        String text = token.text();
        if (!ADDR_SPEC.matcher(text).matches()) {
            throw new AtomParseException("'" + text + "' is not a valid email address -- expected RFC 5322's "
                    + "dot-atom form, local@domain (quoted local parts, domain literals and comments are not "
                    + "accepted; see EmailParser)", "an RFC 5322 dot-atom address");
        }
        validate(text);
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }

    /** The same length and pattern facets {@link TextParser} applies, on the same terms -- {@code email_type} composes {@code text_type}. */
    private void validate(String text) {
        constraints.length().ifPresent(len -> {
            if (text.length() != len) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, expected exactly " + len,
                        "exactly " + len + " characters");
            }
        });
        constraints.minLength().ifPresent(min -> {
            if (text.length() < min) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, less than the minimum " + min,
                        "at least " + min + " characters");
            }
        });
        constraints.maxLength().ifPresent(max -> {
            if (text.length() > max) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, more than the maximum " + max,
                        "at most " + max + " characters");
            }
        });
        // I-Regexp (RFC 9485) via tson-regex -- linear-time and ReDoS-safe, not java.util.regex; already
        // validated well-formed when the schema resolved (see RegexParser).
        constraints.pattern().ifPresent(p -> {
            if (!TsonRegex.parse(p).matches(text)) {
                throw new AtomValidationException("'" + text + "' does not match the required pattern " + p,
                        "matching " + p);
            }
        });
    }
}
