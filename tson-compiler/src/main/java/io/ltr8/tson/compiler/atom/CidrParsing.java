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
     * The decimal prefix length after the {@code /}, or {@code -1} if the text is not one. Leading zeros are
     * rejected for the same reason {@link Ipv4Parser} rejects them in a {@code dec-octet}: {@code /8} and
     * {@code /08} would otherwise be two spellings of one network, which is the confusable-input class that
     * strictness exists to shut down.
     */
    static int tryParsePrefixLength(String text) {
        if (text.isEmpty() || text.length() > MAX_PREFIX_DIGITS) {
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
     * §5.5's two validation rules plus {@code cidr4_type}/{@code cidr6_type}'s own prefix facets, in that
     * order: the family range first (a prefix the family cannot express makes the host-bits question
     * meaningless), then host bits, then the schema's own narrowing.
     */
    static void validateNetwork(String text, byte[] address, int prefixLength, Optional<Integer> minPrefix,
            Optional<Integer> maxPrefix) {
        int addressBits = address.length * 8;
        if (prefixLength > addressBits) {
            throw new AtomValidationException("'" + text + "' has prefix length " + prefixLength
                    + ", outside the family range 0-" + addressBits, ">= 0 and <= " + addressBits);
        }
        if (!hostBitsAreZero(address, prefixLength)) {
            throw new AtomValidationException("'" + text + "' has nonzero host bits under prefix length "
                    + prefixLength + " -- the value is a network, so every bit beyond the prefix must be zero",
                    "zero host bits beyond the prefix");
        }
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

    /** Bit-at-a-time rather than byte-masked: at most 128 iterations, and no boundary case to get wrong. */
    private static boolean hostBitsAreZero(byte[] address, int prefixLength) {
        for (int bit = prefixLength; bit < address.length * 8; bit++) {
            if ((address[bit / 8] & (0x80 >> (bit % 8))) != 0) {
                return false;
            }
        }
        return true;
    }
}
