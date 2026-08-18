package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Cidr4Type;

/**
 * Parses and validates against meta.tn's {@code cidr4_type} constructor (§5.5's {@code cidr4} atom, RFC
 * 4632): an IPv4 address, {@code /}, and a prefix length of 0-32. The address half is {@link Ipv4Parser}'s
 * own strict RFC 3986 {@code dec-octet} grammar, reused rather than copied -- a network's address is an
 * address, and a second, drifting copy would reopen exactly the leniency gap that class documents.
 *
 * <p><b>Host type is {@link String}</b>, the same call {@link MacParser} makes and for the same reason:
 * Java has no CIDR type, and the only structured stand-in would be a pair this library invents and then has
 * to place in a module, export, and bind. Nothing here needs the decomposed form -- {@link #read} validates
 * and hands the authored text back, {@link #write} is the identity, and a round trip is exact. (For IPv6 that
 * matters more than it looks: {@link Cidr6Parser} returning a parsed address would write back through {@code
 * Inet6Address.getHostAddress()}, turning {@code 2001:db8::/32} into its uncompressed eight-group spelling.)
 * A structured host type stays open if a consumer ever needs one.
 *
 * <p>{@code min_prefix}/{@code max_prefix} <em>are</em> applied -- they are scalar facets, unlike {@code
 * within}/{@code excluding}, which stay unmodeled here for the reason {@link Ipv4Parser} records: deciding
 * subnet-of and non-overlap against a list of networks is a materially bigger piece of work than a scalar
 * bound. Whether a declared bound itself falls inside the family range ("invalid at the schema level", per
 * meta.tn) is a constraint-family coherence rule and is not checked here; an out-of-range one is inert
 * either way, since the family range is enforced regardless and so a wider bound cannot widen what this
 * accepts.
 */
public record Cidr4Parser(Cidr4Type constraints) implements AtomType<String> {

    /** §5.5's built-in annotation name -- {@code !cidr4}. */
    public static final String TYPENAME = "cidr4";

    /** {@code cidr4 => !cidr4_type {}} -- the unconstrained IPv4 network, §5.5's {@code !cidr4}. */
    public static final Cidr4Parser UNCONSTRAINED = new Cidr4Parser(Cidr4Type.UNCONSTRAINED);

    @Override
    public String read(TokenValue token) {
        String text = token.text();
        int slash = text.indexOf('/');
        if (slash < 0 || text.indexOf('/', slash + 1) >= 0) {
            throw malformed(text);
        }
        byte[] address = Ipv4Parser.tryParseOctets(text.substring(0, slash));
        int prefixLength = CidrParsing.tryParsePrefixLength(text.substring(slash + 1));
        if (address == null || prefixLength < 0) {
            throw malformed(text);
        }
        CidrParsing.validateNetwork(text, address, prefixLength, constraints.minPrefix(), constraints.maxPrefix());
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }

    private static AtomParseException malformed(String text) {
        return new AtomParseException("'" + text + "' is not a valid IPv4 network -- expected RFC 4632's CIDR "
                + "notation, a dotted-quad address followed by '/' and a decimal prefix length (§5.5)",
                "an IPv4 network in CIDR notation");
    }
}
