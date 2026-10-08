# tson-net — network text formats (`io.ltr8.net`)

Native recognizers for the text forms of network identifiers, each to its RFC: `Iri` (RFC 3986 URIs, RFC 3987
IRIs). Depends on nothing and knows nothing of TSON, so it carries no `Tson` prefix and is usable on its own, as
`io.ltr8.bind` is. A type here parses to a value or throws its own syntax exception; facets, diagnostics and schema
vocabulary are `tson-atom`'s, which wraps each one.

- Nothing is rewritten: no resolution, normalisation, case folding or percent-decoding. Callers compare as written.
- Never delegate to `java.net.URI`: it implements RFC 2396, refuses valid RFC 3986 references and cannot hold a
  non-ASCII host. A JDK type is a binding target in `tson-atom`, never the judge.
