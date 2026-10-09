# ltr8-net — network text formats (`io.ltr8.net`)

Native recognizers for the text forms of network identifiers, each to its RFC: `Iri` (RFC 3986 URIs, RFC 3987
IRIs), `InternetAddress` (IPv4, IPv6), the `CidrNetwork` pair (CIDR prefixes) and `MacAddress` (EUI-48). Depends
on nothing and knows nothing of TSON, so it carries no `Tson` prefix and is usable on its own, as `io.ltr8.bind`
is. A type here parses to a value or reports that the text is not one; facets, diagnostics and schema vocabulary
are `tson-atom`'s, which wraps each format.

- Nothing is rewritten: no resolution, normalisation, case folding or percent-decoding. Callers compare as written.
- Never delegate to `java.net.URI` (RFC 2396, no non-ASCII host) or `InetAddress`'s literal parsing (leading
  zeros, short forms, bare integers). A JDK type is a binding target in `tson-atom`, never the judge.
- One grammar per form: `Iri`'s IP literals read through `InternetAddress`.
