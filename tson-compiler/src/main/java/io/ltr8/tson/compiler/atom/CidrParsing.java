package io.ltr8.tson.compiler.atom;

import java.util.Optional;

/**
 * The CIDR mechanics {@link Cidr4Parser} and {@link Cidr6Parser} share -- prefix-length grammar, the
 * family-range and host-bits rules, and the {@code min_prefix}/{@code max_prefix} facets. Neither family
 * owns them -- unlike {@link Ipv4Parser#IPV4_ADDRESS}, which {@link Ipv6Parser} genuinely reaches into
 * because RFC 4291 §2.2 embeds the IPv4 grammar -- so they sit here rather than on one of the two parsers
 * with the other reaching across for them.
 *
 * <p>The split between a parse failure and a validation failure is [TSON-DATA] §5.5's own, not a choice made
 * here: "A token that does not match the named format is a parse error; a CIDR prefix length outside the
 * address family's range is a validation error, as is an address whose host bits are nonzero under the
 * stated prefix length." So a malformed prefix is {@link AtomParseException} and an out-of-range one is
 * {@link AtomValidationException}, even though both concern the same three characters.
 */
final class CidrParsing {

    /**
     * The largest prefix length any family admits is 128, so a prefix is one to three digits. A longer run
     * is a shape failure rather than an out-of-range value -- the line has to fall somewhere, and putting it
     * at the widest spelling either family can use keeps every plausible authoring slip ({@code /33} on
     * IPv4, {@code /129} on IPv6) inside §5.5's validation category, where the spec puts it.
     */
    private static final int MAX_PREFIX_DIGITS = 3;

    private CidrParsing() {
    }

    /**
     * §5.5's family-range rule, reported where {@code CidrNetwork.parse} could only say "not a network".
     *
     * <p>The two refusals are different in kind and the messages have always said so: a prefix of 999 on an
     * IPv4 network is a well-formed thing naming an impossible one, where {@code 10.0.0/8} is not
     * well-formed at all. The value model returns one null for both, so the reader asks this before
     * concluding the text was malformed.
     */
    static void checkFamilyRange(String text, int familyBits) {
        int slash = text.indexOf('/');
        if (slash < 0) {
            return;
        }
        int prefixLength = tryParsePrefixLength(text.substring(slash + 1));
        if (prefixLength > familyBits) {
            throw new AtomValidationException("'" + text + "' has prefix length " + prefixLength
                    + ", outside the family range 0-" + familyBits, ">= 0 and <= " + familyBits);
        }
    }

    /**
     * The decimal prefix length after the {@code /}, or {@code -1} if the text is not one. Leading zeros are
     * rejected because {@code /8} and {@code /08} would otherwise be two spellings of one network.
     */
    static int tryParsePrefixLength(String text) {
        if (text.isEmpty() || text.length() > 3) {
            return -1;
        }
        if (text.length() > 1 && text.charAt(0) == '0') {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /**
     * {@code cidr4_type}/{@code cidr6_type}'s own prefix facets. The family range and the host-bits rule are
     * not here: a value failing either is not a network at all, so {@code CidrNetwork.parse} refuses it and
     * the caller reports a malformed value rather than a violated constraint.
     */
    static void checkPrefixBounds(String text, int prefixLength, Optional<Integer> minPrefix,
            Optional<Integer> maxPrefix) {
        minPrefix.ifPresent(min -> {
            if (prefixLength < min) {
                throw new AtomValidationException("'" + text + "' has prefix length " + prefixLength
                        + ", less than the minimum " + min, ">= " + min);
            }
        });
        maxPrefix.ifPresent(max -> {
            if (prefixLength > max) {
                throw new AtomValidationException("'" + text + "' has prefix length " + prefixLength
                        + ", more than the maximum " + max, "<= " + max);
            }
        });
    }
}
