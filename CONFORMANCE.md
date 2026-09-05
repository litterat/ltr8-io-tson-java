# Conformance

← back to the [README](README.md)

A handful of implementation choices are worth calling out on their own — not *what's* implemented (see
[STATUS.md](STATUS.md)), but *how* it behaves at the edges, where a well-known JDK parser and the
RFC/ISO standard the spec cites don't quite agree.

**Stricter than the underlying JDK default, matching the cited RFC exactly.** Several built-in atoms
delegate to a JDK type for the bulk of parsing, but only after an explicit shape check of their own —
because the relevant JDK parser, checked empirically in each case rather than assumed, is consistently
*more lenient* than the RFC/ISO grammar the spec cites:

- `!uuid` requires RFC 9562's canonical 8-4-4-4-12 grouping; `UUID.fromString` alone accepts unpadded
  groups (`"1-2-3-4-5"` succeeds, silently reinterpreting where the groups fall).
- `!base64`/`!base64url` require padding; `Base64.getDecoder()` alone accepts it missing.
- `!date`/`!datetime`/`!time` reject ISO 8601's "extended year" form (a leading sign, more than 4 digits);
  `LocalDate`/`OffsetDateTime`/`OffsetTime.parse()` alone accept it, even though RFC 3339's `full-date`
  grammar requires exactly 4 digits and no sign.
- `!duration` requires uppercase designators and no leading sign; `Duration.parse`/`Period.parse` alone
  accept both.

See the relevant class's Javadoc for the specific check in each case.

**`!ipv4` doesn't delegate text parsing to the JDK at all, for a security reason, not just a
spec-fidelity one.** `InetAddress.ofLiteral` — the modern, no-DNS, literal-only entry point, confirmed
empirically before deciding this — is still far more lenient than RFC 3986's `IPv4address`/`dec-octet`
grammar: it accepts a leading zero (`"0177.0.0.1"`), the legacy BSD short/class-based form (`"1.2.3"`
→ `1.2.0.3`), and even a bare 32-bit integer literal (`"3232235521"` → `192.168.0.1`). That's not merely
looser than the cited RFC, it's the same leniency class behind real-world SSRF-filter-bypass techniques
(a validator and the actual network stack disagreeing about what address a string denotes). `Ipv4Parser`
validates the token against the RFC 3986 grammar itself, extracts the four octets directly from the
regex match, and constructs the address from raw bytes via `InetAddress.getByAddress(byte[])` — a pure
bytes-to-object call, never handing the original text to any JDK parser.

**`!ipv6` parses RFC 4291 §2.2's text representation itself too, for the same reason, plus a second,
unrelated JDK quirk.** Handing the token text to a JDK parser would reintroduce `!ipv4`'s exact
leniency gap through RFC 4291's IPv4-mapped alternative form (`x:x:x:x:x:x:d.d.d.d`, e.g.
`"::ffff:192.0.2.1"`), which embeds a dotted-quad tail. So `Ipv6Parser` parses the full grammar itself
— the 8-group preferred form, at most one `::` compression, and a dotted-quad tail checked against
the same strict `dec-octet` grammar `!ipv4` uses — and builds the address from raw bytes. Separately:
`InetAddress.getByAddress(byte[16])` itself was confirmed empirically to silently return an
`Inet4Address`, not an `Inet6Address`, for any 16-byte value in the IPv4-mapped range — the same
value ending up as a different, mutually non-`equals` Java type depending on which narrow sub-range
it falls in. `Ipv6Parser` uses `Inet6Address.getByAddress(String, byte[], int)` with `scope_id = -1`
instead (confirmed to behave like "no scope" and match the generic method's result for every
non-mapped address tried) to guarantee `!ipv6` always returns `Inet6Address`, regardless of the
address's value.

**`!cidr4`/`!cidr6` validate a network but hand back the authored text.** Java has no CIDR type, so the
host value is the token's own text rather than an invented address/prefix pair — validated, never
rewritten, so a round trip is exact (which for IPv6 also avoids expanding `2001:db8::/32` into its
uncompressed eight-group spelling on the way out). The address half reuses `Ipv4Parser`/`Ipv6Parser`
whole, so both inherit the strictness above; the prefix half must be a canonical decimal, rejecting `/08`
for the same reason a `dec-octet` rejects `010`. §5.5's own split between the two failure categories is
followed exactly: a token that isn't CIDR-shaped is a parse error, while a prefix outside the family
range and an address with nonzero host bits are validation errors. `min_prefix`/`max_prefix` are applied;
`within`/`excluding` are not, on the same deferral as `ipv4_type`'s (deciding subnet-of and non-overlap
against a list of networks is a materially bigger piece of work than a scalar bound). Whether a *declared*
bound itself falls inside the family range — "invalid at the schema level", per meta.tn — is a
constraint-family coherence rule tracked in `BACKLOG.md`, not enforced by the parser; an out-of-range
bound is inert, since the family range is enforced regardless.

**One accepted, unfixable gap.** RFC 3339's grammar permits `time-second` up to `60` (leap-second
accommodation), but `java.time` has no leap-second concept at all — `!time`/`!datetime` reject a
spec-legal leap-second token as a parse error. There's no reasonable fix short of a from-scratch time
representation built solely for this one case, so it's documented (`TimeParser`'s Javadoc) rather than
solved.

**One accepted, different-revision gap.** `!uri` (§5.5) is the one atom here that does *not* get an
extra shape check ahead of the JDK type it delegates to — the opposite situation from the atoms above.
§5.5 cites RFC 3986, but `java.net.URI`'s own Javadoc states it implements RFC 2396 (as amended by RFC
2732), an older revision of the same standard, not a looser/stricter variant of the same grammar. There's
no simple shape to shim in front of `URI`'s constructor the way a four-group hex pattern works for UUID,
and writing an RFC 3986 validator from scratch isn't worth it at this stage, so `java.net.URI`'s behavior
is accepted as `!uri`'s actual contract for now. See `UriParser`'s Javadoc.

**The vocabulary is the published one.** §5.6 lists the full `int8`..`int256`/`uint8`..`uint256` ladder plus
the four bound-only refinements, and §5.5 carries `!email` beside its "Network Types" siblings, so what is
seeded here matches both the tables and core.tn — the two read paths agree by construction rather than by
this implementation choosing core.tn over a shorter table.

**`!email` implements a subset of RFC 5322, not the whole grammar.** `email_type`'s `spec` is
`REQUIRED_FIXED` to RFC 5322, but `EmailParser` accepts only the `dot-atom "@" dot-atom` core; quoted local
parts (`"a b"@example.com`), domain literals (`user@[192.0.2.1]`) and embedded comments are rejected though
the RFC admits them. Accepting them would admit spaces, brackets and parentheses into a scalar consumers
treat as a token — and §5.5 scopes the pin to exactly that core, so this is the contract rather than a
narrowing of it. Pinned by `EmailParserTest`.

**`RegexParser` is a real RFC 9485 (I-Regexp) validator, not `java.util.regex`.** The `tson-regex` module
parses I-Regexp to its own AST and matches with a Thompson-NFA/Pike-VM — linear-time, so ReDoS-safe — which
means this implementation defines I-Regexp behaviour rather than inheriting the JVM's Perl-derived superset.
`TextParser`/`UriParser` match their `pattern` constraint through the same engine. [TSON-SCHEMA] §9 makes
the pin a strict gate and requires an implementation to document any divergence it cannot avoid; that
statement is `io.ltr8.tson.regex`'s own `package-info`. The short version: the grammar admits exactly RFC
9485 §3's, matching is whole-string, and the one divergence is not a departure from the RFC but a limit of
its silence — it pins no Unicode version, so `\p{...}` answers against the running JDK's, and two
conforming implementations can disagree about a code point assigned between two Unicode releases.

**`!duration` accepts the `PnW` week form, standing alone.** RFC 3339 Appendix A's production is an
alternation — `duration = "P" (dur-date / dur-time / dur-week)` — so `P3W` is a duration, `P1W2D` and
`P1WT1H` are not, and a week is exactly 7 days, making `P2W`, `P14D` and `PT336H` one value. §5.4 states all
three rules and gives `!duration` and `!period` a row each. A canonical write emits the `PTnHnMnS` form, so
`PnW` and `PnD` are reading conveniences the way `0x50` is for an integer — the value is a count of seconds
and carries no memory of how it was spelled.

**`toTson`'s round trip is intentionally lossy in a few specific, documented ways.** It's a debugging
tool, not a guaranteed-lossless serializer: a `!typeName` type-ref is only re-emitted where a value
wouldn't read back correctly without one (the built-in vocabulary's JDK-backed host types); anything
default value resolution (§4) already recovers on its own — the whole integer family, plain
`number`/`float32`/`float64` — is written bare, so a field bound from `!uint8 42` writes back as plain
`42`, indistinguishable from one that was never `!uint8`-typed at all — the width lived in the wire
annotation, and a bound `int` no longer carries it. (The read direction isn't symmetric here: a schemaless
read *does* hold a wire annotation to account, checking `!uint8 300` against `uint8`'s own range. What the
writer lacks is the annotation, not the vocabulary.) `byte[]` values
always write back as `!base64`, regardless of which of `base64`/`base64url`/`base32`/`hex` they were
originally decoded from — that information doesn't survive decoding, so `!base64` is an arbitrary but
reasonable default. Tuples write as plain arrays, with nothing marking them as tuples at all. Wire-format annotations do
round trip on both paths: `TsonTreeWriter` re-emits a node's own, and `TsonObjectWriter` re-emits those a
bound class captured by declaring an `Annotations` component.

Ambiguities, inconsistencies, and errors in the spec text itself — as opposed to this implementation's own
behavior at the edges — are tracked separately in [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md).
