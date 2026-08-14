package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.MacType;

import java.util.regex.Pattern;

/**
 * Parses and validates against meta.tn's {@code mac_type} constructor (§5.5's {@code mac} atom, EUI-48 per
 * RFC 9542). A pure format check (§5.2: "the remaining atoms are pure format checks") -- {@link MacType}
 * carries only its RFC pin, so there are no facets to apply and {@code mac => !mac_type {}} in core.tn is
 * fully unconstrained.
 *
 * <p><b>Host type is {@link String}</b>, not a byte array. Java has no MAC-address type to map onto, and
 * {@code byte[]} is already spoken for: {@code VocabularyAtoms} maps it to {@code binary}/{@code !base64},
 * so a six-byte MAC would write back as base64 and stop round-tripping. Plain {@code String} also binds
 * generically with no {@code DataBridge}, the same reasoning {@link RegexParser} records for its own host
 * type.
 *
 * <p><b>The authored form is preserved, not canonicalised.</b> core.tn admits both separators -- "six hex
 * octets, colon- or hyphen-separated... The colon form must be quoted; the hyphen form may be written
 * unquoted" -- and neither it nor RFC 9542 nominates one as canonical, so rewriting {@code AA-BB-CC-DD-EE-FF}
 * to colons on a read/write round trip would be this implementation inventing a rule. Validate, then hand
 * back the text as written; {@link #write} is the identity.
 *
 * <p>Mixing separators ({@code AA-BB:CC-DD:EE-FF}) is rejected: the two forms are alternatives, not a
 * character class, so each is matched whole rather than by a per-octet separator test.
 */
public record MacParser(MacType constraints) implements AtomType<String> {

    /** §5.5's built-in annotation name -- {@code !mac}. */
    public static final String TYPENAME = "mac";

    /** {@code mac => !mac_type {}} -- the unconstrained MAC address, §5.5's {@code !mac}. */
    public static final MacParser UNCONSTRAINED = new MacParser(MacType.UNCONSTRAINED);

    /** Six hex octets, separated consistently by {@code :} or by {@code -} -- one alternative each, so the two can't be mixed. */
    private static final Pattern EUI_48 = Pattern.compile(
            "[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}|[0-9A-Fa-f]{2}(?:-[0-9A-Fa-f]{2}){5}");

    @Override
    public String read(TokenValue token) {
        String text = token.text();
        if (!EUI_48.matcher(text).matches()) {
            throw new AtomParseException("'" + text + "' is not a valid MAC address -- expected RFC 9542's EUI-48 "
                    + "form, six hex octets separated consistently by ':' or by '-' (§5.5)");
        }
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }
}
