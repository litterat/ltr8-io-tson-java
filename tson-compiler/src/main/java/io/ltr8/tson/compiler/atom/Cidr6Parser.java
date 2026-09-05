package io.ltr8.tson.compiler.atom;

import java.util.List;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.atom.CidrNetwork;
import io.ltr8.tson.schema.meta.Cidr6Type;

/**
 * Parses and validates against meta.tn's {@code cidr6_type} constructor (§5.5's {@code cidr6} atom, RFC
 * 4632): an IPv6 address, {@code /}, and a prefix length of 0-128.
 *
 * <p><b>Host type is {@link CidrNetwork}</b>, a value type in {@code schema.atom} beside {@code Rational}.
 * The grammar and the family-range and host-bits rules live on it, so a network is a value here rather than
 * the text that carried it -- which is what lets {@code within} and {@code excluding} be judged by the family
 * that declares them rather than by a check bolted onto the resolver.
 *
 * <p><b>A round trip is exact in value, not in spelling.</b> {@link #write} renders from the octets, so
 * {@code 2001:db8::/32} comes back as its uncompressed eight-group form -- the same trade {@code Ipv6Parser}
 * already makes for a bare address, and legal under the grammar either way.
 *
 * <p>{@code min_prefix}/{@code max_prefix} are applied here, being this constructor's own facets; the family
 * range and host-bits rules are {@code CidrNetwork.parse}'s, since a value failing either is not a network at
 * all. Whether a declared bound itself falls inside the family range is a coherence rule and is not checked
 * here; an out-of-range one is inert either way, the family range being enforced regardless.
 */
public record Cidr6Parser(Cidr6Type constraints) implements AtomType<CidrNetwork> {

    /** §5.5's built-in annotation name -- {@code !cidr6}. */
    public static final String TYPENAME = "cidr6";

    /** {@code cidr6 => !cidr6_type {}} -- the unconstrained IPv6 network, §5.5's {@code !cidr6}. */
    public static final Cidr6Parser UNCONSTRAINED = new Cidr6Parser(Cidr6Type.UNCONSTRAINED);

    @Override
    public CidrNetwork read(TokenValue token) {
        String text = token.text();
        CidrNetwork network = CidrNetwork.parse(text, 128);
        if (network == null) {
            CidrParsing.checkFamilyRange(text, 128);
            throw malformed(text);
        }
        if (!network.hostBitsAreZero()) {
            throw new AtomValidationException("'" + text + "' has nonzero host bits under prefix length "
                    + network.prefixLength() + " -- the value is a network, so every bit beyond the prefix "
                    + "must be zero", "zero host bits beyond the prefix");
        }
        CidrParsing.checkPrefixBounds(text, network.prefixLength(),
                constraints.minPrefix(), constraints.maxPrefix());
        checkNetworks(text, network);
        return network;
    }

    /**
     * §5.5's {@code within}/{@code excluding} for a <em>network</em> value, which are not the address
     * family's rules. {@code within}: the value must be a <b>subnet</b> of at least one listed network.
     * {@code excluding}: the value must not <b>overlap</b> any -- overlap rather than containment, so a value
     * wider than an excluded block cannot smuggle it through, which is what meta.tn's own {@code @doc} asks
     * for.
     *
     * <p>The lists are text here because the facets are typed {@code [value]}; every entry has already been
     * checked to be a network at schema load ({@code Cidr6Type.coherenceCheck}), so a null from {@code parse}
     * is unreachable rather than an author error.
     */
    private void checkNetworks(String text, CidrNetwork value) {
        List<String> within = constraints.within();
        if (!within.isEmpty() && within.stream()
                .noneMatch(entry -> subnetOf(entry, value))) {
            throw new AtomValidationException("'" + text + "' is a subnet of none of this type's permitted "
                    + "networks", "a subnet of one of " + within.size() + " permitted network"
                    + (within.size() == 1 ? "" : "s"));
        }
        if (constraints.excluding().stream().anyMatch(entry -> overlaps(entry, value))) {
            throw new AtomValidationException("'" + text + "' overlaps a network this type excludes",
                    "a network overlapping none of the excluded ones");
        }
    }

    private static boolean subnetOf(String entry, CidrNetwork value) {
        CidrNetwork network = CidrNetwork.parse(entry, 128);
        return network != null && network.contains(value);
    }

    private static boolean overlaps(String entry, CidrNetwork value) {
        CidrNetwork network = CidrNetwork.parse(entry, 128);
        return network != null && network.overlaps(value);
    }

    @Override
    public String write(CidrNetwork value) {
        return value.text();
    }

    private static AtomParseException malformed(String text) {
        return new AtomParseException("'" + text + "' is not a valid IPv6 network -- expected RFC 4632's CIDR "
                + "notation, an address followed by '/' and a prefix length of 0-128, with zero host bits "
                + "beyond the prefix (§5.5)", "an IPv6 network in CIDR notation");
    }
}
