/**
 * Native recognizers for the text forms of network identifiers, each to its RFC and none delegated to the JDK.
 *
 * <ul>
 *   <li>{@link io.ltr8.net.Iri} -- RFC 3986 URI-references and RFC 3987 IRI-references.</li>
 *   <li>{@link io.ltr8.net.InternetAddress} -- RFC 3986's IPv4 and RFC 4291's IPv6 text forms, to octets and back.</li>
 *   <li>{@link io.ltr8.net.CidrNetwork} -- CIDR networks, one type per family, with containment and the host-bits
 *       rule.</li>
 *   <li>{@link io.ltr8.net.MacAddress} -- RFC 9542's EUI-48 text form.</li>
 *   <li>{@link io.ltr8.net.HostName} -- a domain name in either label form, judged by IDNA2008, over
 *       {@link io.ltr8.net.Punycode}.</li>
 *   <li>{@link io.ltr8.net.Host} -- a host name or an IPv4 or IPv6 address, RFC 3987's {@code ihost} with a DNS
 *       name.</li>
 * </ul>
 *
 * <p>Every one exists because the JDK's answer is a different grammar: {@code java.net.URI} implements RFC 2396
 * and cannot hold a host beyond US-ASCII, and {@code java.net.InetAddress}'s literal parsing admits leading zeros,
 * short forms and bare integers. JDK types are where values may be held, never what judges text.
 *
 * <h2>URIs and IRIs</h2>
 *
 * <p>{@link io.ltr8.net.Iri#parse} decides whether text is an RFC 3986 URI-reference or an RFC 3987 IRI-reference and
 * splits it into scheme, authority (userinfo, host, port), path, query and fragment, each exactly as written.
 *
 * <p><b>The grammar is implemented here, not delegated.</b> {@code java.net.URI} implements RFC 2396 as amended by
 * RFC 2732, not RFC 3986: it refuses references RFC 3986 admits ({@code https://} with an empty host, {@code a:} with
 * an empty path, an IPvFuture literal) and admits one it refuses ({@code http://a:b/}, whose port is not digits), and
 * it cannot hold a host beyond US-ASCII at all. Wrapping it would make "is this a URI" a question about the JDK.
 *
 * <p><b>What is recognised is exactly RFC 3986 Appendix A's {@code URI-reference}</b>, and under {@link
 * io.ltr8.net.Iri.Grammar#IRI} RFC 3987 §2.2's {@code IRI-reference}: the same productions with {@code
 * ucschar} admitted wherever {@code unreserved} is, and {@code iprivate} in the query. An IP literal stays US-ASCII
 * in both, as RFC 3987 leaves it. ABNF literals are case-insensitive, so the {@code v} of an IPvFuture literal may be
 * written {@code V}.
 *
 * <p><b>Nothing is rewritten.</b> No component is resolved against a base, normalised, case-folded or
 * percent-decoded; a component is the substring the text holds, and two references are equal exactly when their
 * texts are -- RFC 3986 §6.2.1's simple string comparison. A caller that wants a comparison ladder rung higher
 * builds it on the components. RFC 3987 §4's bidirectional-text rules are a SHOULD and are not checked.
 */
package io.ltr8.net;
