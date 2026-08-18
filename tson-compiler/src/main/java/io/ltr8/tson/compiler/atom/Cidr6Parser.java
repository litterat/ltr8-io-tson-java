package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Cidr6Type;

/**
 * Parses and validates against meta.tn's {@code cidr6_type} constructor (§5.5's {@code cidr6} atom, RFC 4291
 * §2.3) -- {@link Cidr4Parser}'s exact IPv6 counterpart: same shape, same host type and the reasoning behind
 * it, same treatment of {@code min_prefix}/{@code max_prefix} and of {@code within}/{@code excluding}, and a
 * prefix range of 0-128 rather than 0-32. See {@link Cidr4Parser}'s own Javadoc for all of it.
 *
 * <p>The address half is {@link Ipv6Parser}'s own RFC 4291 §2.2 parse, reused whole, so a zone identifier
 * ({@code fe80::1%eth0}) is excluded here for the reason it is excluded there, and the IPv4-tail form
 * ({@code ::ffff:192.0.2.0/120}) is admitted on the same terms.
 */
public record Cidr6Parser(Cidr6Type constraints) implements AtomType<String> {

    /** §5.5's built-in annotation name -- {@code !cidr6}. */
    public static final String TYPENAME = "cidr6";

    /** {@code cidr6 => !cidr6_type {}} -- the unconstrained IPv6 network, §5.5's {@code !cidr6}. */
    public static final Cidr6Parser UNCONSTRAINED = new Cidr6Parser(Cidr6Type.UNCONSTRAINED);

    @Override
    public String read(TokenValue token) {
        String text = token.text();
        int slash = text.indexOf('/');
        if (slash < 0 || text.indexOf('/', slash + 1) >= 0) {
            throw malformed(text);
        }
        byte[] address = Ipv6Parser.tryParseBytes(text.substring(0, slash));
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
        return new AtomParseException("'" + text + "' is not a valid IPv6 network -- expected RFC 4291 §2.3's "
                + "CIDR notation, an RFC 4291 §2.2 address followed by '/' and a decimal prefix length (§5.5)",
                "an IPv6 network in CIDR notation");
    }
}
