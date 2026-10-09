package io.ltr8.net;

import java.util.Optional;

/**
 * RFC 3986 Appendix A's {@code URI-reference}, and RFC 3987 §2.2's {@code IRI-reference} where {@link #iri} is set,
 * recognised in one left-to-right pass.
 *
 * <p>The grammar needs no backtracking once the delimiters are found, because RFC 3986 chose them so: a scheme is
 * the leading run of scheme characters exactly when a {@code :} ends it ({@code path-noscheme} forbids a {@code :}
 * in a relative reference's first segment, §4.2), an authority is what follows a leading {@code //} up to the first
 * {@code /}, {@code ?} or {@code #}, userinfo is what precedes its first {@code @} (userinfo holds none), a
 * registered name's port follows its first {@code :} (a registered name holds none), and the query and fragment begin
 * at the first {@code ?} and {@code #}. What is left is each component's own character set, checked over its span,
 * and the two host forms with structure, IPv6 and IPv4, which are {@link InternetAddress}'s grammars: an IP
 * literal and an address are one production wherever they appear.
 */
final class IriGrammar {

    private static final String SUB_DELIMS = "!$&'()*+,;=";

    private final String text;
    private final boolean iri;

    IriGrammar(String text, boolean iri) {
        this.text = text;
        this.iri = iri;
    }

    Iri reference() {
        int end = text.length();
        int fragmentAt = text.indexOf('#');
        int afterQuery = fragmentAt < 0 ? end : fragmentAt;
        int queryAt = text.indexOf('?');
        if (queryAt > afterQuery) {
            queryAt = -1;
        }
        int hierEnd = queryAt >= 0 ? queryAt : afterQuery;

        int schemeEnd = schemeEnd(hierEnd);
        Optional<String> scheme = schemeEnd < 0 ? Optional.empty() : Optional.of(text.substring(0, schemeEnd));
        int at = schemeEnd < 0 ? 0 : schemeEnd + 1;

        Optional<Iri.Authority> authority = Optional.empty();
        if (text.startsWith("//", at)) {
            int authorityEnd = text.indexOf('/', at + 2);
            authorityEnd = authorityEnd < 0 || authorityEnd > hierEnd ? hierEnd : authorityEnd;
            authority = Optional.of(authority(at + 2, authorityEnd));
            at = authorityEnd;
        }
        String path = path(at, hierEnd, scheme.isPresent(), authority.isPresent());

        Optional<String> query = Optional.empty();
        if (queryAt >= 0) {
            characters(queryAt + 1, afterQuery, ":@/?", true, "the query");
            query = Optional.of(text.substring(queryAt + 1, afterQuery));
        }
        Optional<String> fragment = Optional.empty();
        if (fragmentAt >= 0) {
            characters(fragmentAt + 1, end, ":@/?", false, "the fragment");
            fragment = Optional.of(text.substring(fragmentAt + 1));
        }
        return new Iri(text, scheme, authority, path, query, fragment);
    }

    /**
     * The index of the {@code :} ending a scheme, or {@code -1} where the text has none: {@code ALPHA *( ALPHA /
     * DIGIT / "+" / "-" / "." )} and then {@code :}, before anything that ends the hierarchical part.
     */
    private int schemeEnd(int limit) {
        if (limit == 0 || !isAlpha(text.charAt(0))) {
            return -1;
        }
        int i = 1;
        while (i < limit && isSchemeChar(text.charAt(i))) {
            i++;
        }
        return i < limit && text.charAt(i) == ':' ? i : -1;
    }

    /** {@code [ userinfo "@" ] host [ ":" port ]} over {@code [from, to)}. */
    private Iri.Authority authority(int from, int to) {
        Optional<String> userinfo = Optional.empty();
        int hostFrom = from;
        int at = text.indexOf('@', from);
        if (at >= 0 && at < to) {
            characters(from, at, ":", false, "the userinfo");
            userinfo = Optional.of(text.substring(from, at));
            hostFrom = at + 1;
        }
        Iri.Host host;
        int hostTo;
        if (hostFrom < to && text.charAt(hostFrom) == '[') {
            int close = text.indexOf(']', hostFrom);
            if (close < 0 || close >= to) {
                throw fail("has an IP literal opened at index " + hostFrom + " and never closed", hostFrom);
            }
            host = ipLiteral(hostFrom + 1, close);
            hostTo = close + 1;
            if (hostTo < to && text.charAt(hostTo) != ':') {
                throw fail(at(hostTo) + ", where only a port may follow an IP literal", hostTo);
            }
        } else {
            int colon = text.indexOf(':', hostFrom);
            hostTo = colon >= 0 && colon < to ? colon : to;
            host = isIpv4(hostFrom, hostTo)
                    ? new Iri.Host(Iri.Host.Kind.IPV4, text.substring(hostFrom, hostTo))
                    : registeredName(hostFrom, hostTo);
        }
        Optional<String> port = Optional.empty();
        if (hostTo < to) {
            for (int i = hostTo + 1; i < to; i++) {
                if (!isDigit(text.charAt(i))) {
                    throw fail(at(i) + " in the port, which is digits only", i);
                }
            }
            port = Optional.of(text.substring(hostTo + 1, to));
        }
        return new Iri.Authority(userinfo, host, port);
    }

    private Iri.Host registeredName(int from, int to) {
        characters(from, to, "", false, "the host");
        return new Iri.Host(Iri.Host.Kind.REG_NAME, text.substring(from, to));
    }

    /** {@code IPv6address / IPvFuture} between the brackets, which stay US-ASCII under either grammar. */
    private Iri.Host ipLiteral(int from, int to) {
        String literal = text.substring(from, to);
        if (!literal.isEmpty() && (literal.charAt(0) == 'v' || literal.charAt(0) == 'V')) {
            int dot = literal.indexOf('.');
            boolean version = dot > 1;
            for (int i = 1; version && i < dot; i++) {
                version = isHex(literal.charAt(i));
            }
            boolean tail = version && dot + 1 < literal.length();
            for (int i = dot + 1; tail && i < literal.length(); i++) {
                char c = literal.charAt(i);
                tail = isUnreserved(c) || SUB_DELIMS.indexOf(c) >= 0 || c == ':';
            }
            if (!tail) {
                throw fail("has '[" + literal + "]', which is not an IPvFuture literal (RFC 3986 §3.2.2)", from);
            }
            return new Iri.Host(Iri.Host.Kind.IP_FUTURE, literal);
        }
        if (InternetAddress.ipv6(literal) == null) {
            throw fail("has '[" + literal + "]', which is not an IPv6 address (RFC 3986 §3.2.2)", from);
        }
        return new Iri.Host(Iri.Host.Kind.IPV6, literal);
    }

    /**
     * The path over {@code [from, to)}, by the production its context selects: {@code path-abempty} after an
     * authority, and otherwise absolute, rootless or empty -- with {@code path-noscheme}'s rule that a relative
     * reference's first segment holds no {@code :}, which is what keeps it from reading as a scheme.
     */
    private String path(int from, int to, boolean hasScheme, boolean hasAuthority) {
        if (!hasScheme && !hasAuthority) {
            int firstSegmentEnd = text.indexOf('/', from);
            firstSegmentEnd = firstSegmentEnd < 0 || firstSegmentEnd > to ? to : firstSegmentEnd;
            int colon = text.indexOf(':', from);
            if (colon >= 0 && colon < firstSegmentEnd) {
                throw fail("has ':' at index " + colon + " in its first segment, which a relative reference may not "
                        + "hold (RFC 3986 §4.2) and a scheme may not precede", colon);
            }
        }
        characters(from, to, ":@/", false, "the path");
        return text.substring(from, to);
    }

    /**
     * Every character of {@code [from, to)} is unreserved, a sub-delimiter, one of {@code extra}, or a whole {@code
     * pct-encoded} triplet -- with {@code ucschar} beside unreserved under the IRI grammar, and {@code iprivate}
     * where {@code privateUse} says the component is the query.
     */
    private void characters(int from, int to, String extra, boolean privateUse, String component) {
        for (int i = from; i < to; ) {
            int c = text.codePointAt(i);
            if (c == '%') {
                if (i + 2 >= to || !isHex(text.charAt(i + 1)) || !isHex(text.charAt(i + 2))) {
                    throw fail("has a '%' at index " + i + " not followed by two hex digits", i);
                }
                i += 3;
                continue;
            }
            if (c < 0x80) {
                if (!isUnreserved((char) c) && SUB_DELIMS.indexOf(c) < 0 && extra.indexOf(c) < 0) {
                    throw fail(at(i) + ", which " + (iri ? "an IRI" : "a URI") + " may not hold in " + component, i);
                }
            } else if (!iri) {
                throw fail("has U+" + hex(c) + " at index " + i + ", beyond the US-ASCII of a URI (RFC 3986 §2)", i);
            } else if (!isUcschar(c) && !(privateUse && isIprivate(c))) {
                throw fail("has U+" + hex(c) + " at index " + i + (isIprivate(c)
                        ? ", a private-use character RFC 3987 admits only in the query"
                        : ", which RFC 3987 admits nowhere in an IRI"), i);
            }
            i += Character.charCount(c);
        }
    }

    /** RFC 3986's {@code IPv4address}, by {@link InternetAddress}'s grammar, which the IPv4 family shares. */
    private boolean isIpv4(int from, int to) {
        return InternetAddress.ipv4(text.substring(from, to)) != null;
    }

    /** RFC 3987 §2.2's {@code ucschar}: the BMP's letters and marks, then each supplementary plane but the last two. */
    static boolean isUcschar(int c) {
        if (c >= 0xA0 && c <= 0xD7FF || c >= 0xF900 && c <= 0xFDCF || c >= 0xFDF0 && c <= 0xFFEF) {
            return true;
        }
        int plane = c >>> 16;
        return plane >= 1 && plane <= 14 && (c & 0xFFFF) <= 0xFFFD && (plane != 14 || c >= 0xE1000);
    }

    /** RFC 3987 §2.2's {@code iprivate}: the private-use area and planes 15 and 16. */
    static boolean isIprivate(int c) {
        return c >= 0xE000 && c <= 0xF8FF || c >= 0xF0000 && (c & 0xFFFF) <= 0xFFFD;
    }

    private static boolean isUnreserved(char c) {
        return isAlpha(c) || isDigit(c) || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static boolean isSchemeChar(char c) {
        return isAlpha(c) || isDigit(c) || c == '+' || c == '-' || c == '.';
    }

    private static boolean isAlpha(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static String hex(int c) {
        return String.format("%04X", c);
    }

    private String at(int i) {
        return "has '" + Character.toString(text.codePointAt(i)) + "' at index " + i;
    }

    private IriSyntaxException fail(String reason, int index) {
        return new IriSyntaxException(reason, text, index);
    }
}
