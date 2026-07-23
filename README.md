# tson-java

A Java implementation of [TSON](https://tson.io) (Typed Schema Object Notation) — a schema system with
its own text notation, extending JSON with richer structural types, optional annotations and type
annotations, and a layered resolution model that separates structural parsing from semantic
interpretation.

This is one implementation of an open specification, not the canonical one — anyone can implement TSON.
Published under the [litterat](https://github.com/litterat) org, group id `io.ltr8`.

## Status

Built against TSON Part 1 (lexer + data format), a working draft: https://tson.io/raw/2026/32/tson-part1-data.md

This is the spec's first implementation. Issues and ambiguities found in the spec while implementing are
tracked in [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md).

**Implemented:**

- [x] Lexer and structural parser — records, maps, arrays, annotations, directives (`!!id`/
      `!!schema`/`!!meta` arguments are validated as URIs, not just single-line tokens)
- [x] Base types — null, boolean, string, numbers (integer, float, hex-float, based-integer)
- [x] Integer types — `int8`–`int256`, `uint8`–`uint256`, `positive_integer` and siblings
- [x] Decimal/float types — `number`, `float32`, `float64`, `rational`, `complex`
- [x] Identifier/network types — `uuid`, `uri`, `ipv4`, `ipv6`
- [x] Binary types — `base64`, `base64url`, `base32`, `hex`
- [x] Temporal types — `date`, `time`, `datetime`, `duration`
- [x] Object binding — Java records, hand-written immutable classes, `Map<K, V>`, tuples, plain
      enums, sealed interfaces/unions
- [x] Wire-format annotation access — a bound record's own `@name[:value]` annotations, via an
      opt-in carrier component
- [x] Full document binding — TSON text straight to Java objects, dispatching into all of the above,
      and back again (`toTson`) — mainly a debugging tool, not a guaranteed-lossless round trip
      (e.g. the integer family's exact width isn't recoverable schemaless; see [Conformance](#conformance))

See [CLAUDE.md](CLAUDE.md#architecture) for architecture and design notes, and
[Conformance](#conformance) below for edge-case behavior worth knowing about.

**Not yet implemented:**

- [ ] Network/identifier types — `cidr4`, `cidr6`, `mac`
- [ ] General resolver-layer structural rules as reusable primitives, rather than binding-time-only
      behavior — empty-brace resolution, absent-vs-missing distinction
- [ ] Annotation access on individual fields, array/tuple elements, and map keys/values — only a
      whole bound record's own annotations are reachable today, not its children's
- [ ] Header/value directive interpretation — `!!id` verification, `!!schema` loading
- [ ] Multi-error reporting — currently fail-fast on the first lex/parse error
- [ ] Security hardening — numeric-literal length limits, confusable-character and
      bidi-formatting-character warnings
- [ ] Anything from Part 2 (schema grammar, type system)

## Conformance

A handful of implementation choices are worth calling out on their own — not *what's* implemented (the
checklists above), but *how* it behaves at the edges, where a well-known JDK parser and the RFC/ISO
standard the spec cites don't quite agree.

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
(a validator and the actual network stack disagreeing about what address a string denotes). `Ipv4Type`
validates the token against the RFC 3986 grammar itself, extracts the four octets directly from the
regex match, and constructs the address from raw bytes via `InetAddress.getByAddress(byte[])` — a pure
bytes-to-object call, never handing the original text to any JDK parser.

**`!ipv6` parses RFC 4291 §2.2's text representation itself too, for the same reason, plus a second,
unrelated JDK quirk.** Handing the token text to a JDK parser would reintroduce `!ipv4`'s exact
leniency gap through RFC 4291's IPv4-mapped alternative form (`x:x:x:x:x:x:d.d.d.d`, e.g.
`"::ffff:192.0.2.1"`), which embeds a dotted-quad tail. So `Ipv6Type` parses the full grammar itself
— the 8-group preferred form, at most one `::` compression, and a dotted-quad tail checked against
the same strict `dec-octet` grammar `!ipv4` uses — and builds the address from raw bytes. Separately:
`InetAddress.getByAddress(byte[16])` itself was confirmed empirically to silently return an
`Inet4Address`, not an `Inet6Address`, for any 16-byte value in the IPv4-mapped range — the same
value ending up as a different, mutually non-`equals` Java type depending on which narrow sub-range
it falls in. `Ipv6Type` uses `Inet6Address.getByAddress(String, byte[], int)` with `scope_id = -1`
instead (confirmed to behave like "no scope" and match the generic method's result for every
non-mapped address tried) to guarantee `!ipv6` always returns `Inet6Address`, regardless of the
address's value.

**One accepted, unfixable gap.** RFC 3339's grammar permits `time-second` up to `60` (leap-second
accommodation), but `java.time` has no leap-second concept at all — `!time`/`!datetime` reject a
spec-legal leap-second token as a parse error. There's no reasonable fix short of a from-scratch time
representation built solely for this one case, so it's documented (`TimeType`'s Javadoc) rather than
solved.

**One accepted, different-revision gap.** `!uri` (§5.5) is the one atom here that does *not* get an
extra shape check ahead of the JDK type it delegates to — the opposite situation from the atoms above.
§5.5 cites RFC 3986, but `java.net.URI`'s own Javadoc states it implements RFC 2396 (as amended by RFC
2732), an older revision of the same standard, not a looser/stricter variant of the same grammar. There's
no simple shape to shim in front of `URI`'s constructor the way a four-group hex pattern works for UUID,
and writing an RFC 3986 validator from scratch isn't worth it at this stage, so `java.net.URI`'s behavior
is accepted as `!uri`'s actual contract for now. See `UriType`'s Javadoc.

**One open question.** Whether `!duration` accepts ISO 8601's alternative `PnW` week form is genuinely
ambiguous — §5.4's table shows only `PnYnMnDTnHnMnS`. This implementation rejects `PnW` as the more
conservative of the two readings, not a confident call — see [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md) #12.

**`toTson`'s round trip is intentionally lossy in a few specific, documented ways.** It's a debugging
tool, not a guaranteed-lossless serializer: a `!typeName` type-ref is only re-emitted where a value
wouldn't read back correctly without one (the built-in vocabulary's JDK-backed host types); anything
default value resolution (§4) already recovers on its own — the whole integer family, plain
`number`/`float32`/`float64` — is written bare, so a field bound from `!uint8 42` writes back as plain
`42`, indistinguishable from one that was never `!uint8`-typed at all. A schemaless writer has no
annotation to reach for any more than a schemaless reader has one to validate against. `byte[]` values
always write back as `!base64`, regardless of which of `base64`/`base64url`/`base32`/`hex` they were
originally decoded from — that information doesn't survive decoding, so `!base64` is an arbitrary but
reasonable default. Tuples write as plain arrays, with nothing marking them as tuples at all. Wire-format
annotations captured via `@Annotated` (see above) aren't re-emitted yet.

Ambiguities, inconsistencies, and errors in the spec text itself — as opposed to this implementation's own
behavior at the edges — are tracked separately in [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md).

## Requirements

- Java 25
- No external runtime dependencies. JUnit (Jupiter) is used for tests only.

## Build and test

```
./gradlew build
./gradlew test
```

## Related

- [ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite) — language-agnostic
  conformance test vectors for any TSON implementation, including this one. If checked out as a sibling
  directory (`../ltr8-io-tson-test-suite`), `ConformanceSuiteTest` runs every vector against this
  implementation's real lexer and parser; it's skipped, not failed, if the sibling isn't present (CI
  doesn't check it out).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
