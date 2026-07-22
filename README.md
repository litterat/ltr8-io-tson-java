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

- [x] Lexer (§7) — `tson-parser`
- [x] Structural parser (§2, §3, §7.4 — records, maps, arrays, augmentation, directives) — `tson-parser`
- [x] Base type resolution, identification (§4 — which of null/boolean/number/string a token is, and for
      numbers, which of the four §7.6 grammar forms) — `tson-parser`
- [x] Base type resolution, binding to Java host types (`int`/`long`/`double`/`BigInteger`/`BigDecimal`/...)
      — `tson-mapper`'s `AtomBinder`
- [x] Built-in type vocabulary (§5), `integer_type`/`decimal_type`/`float_type`/`rational_type`/
      `complex_type`/`uuid_type`/`binary`/`date_type`/`time_type`/`datetime_type`/`duration_type`/
      `uri_type` families — `int8`..`int256`, `uint8`..`uint256`, `positive_integer` and siblings
      (§5.6, extended beyond the four currently published — see [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md)
      #6), `number`, `float32`/`float64` (including `hex-float`), `rational`, `complex` (§7.6's
      `hex-float`/`rational`/`complex` extended grammar forms, only reachable through these atoms),
      `uuid`/`uri` (§5.5), `base64`/`base64url`/`base32`/`hex` (§5.3), `date`/`time`/`datetime`/
      `duration` (§5.4) — `tson-parser`'s `resolver.vocab` package. Binding `!rational`/`!complex`/
      `!duration` to a Java field requires a `DataBridge` registered on the `DataBindContext`
      (`Rational`/`Complex`/`IsoDuration` are themselves Java records, so `tson-bind`'s record
      auto-detection claims them ahead of the vocabulary path — direct binding to them doesn't
      work, by design; see their Javadoc). `!uuid`, `!uri`, the binary atoms, and `!date`/`!time`/
      `!datetime` bind directly to `java.util.UUID`/`java.net.URI`/`byte[]`/`LocalDate`/
      `OffsetTime`/`OffsetDateTime` — `TsonMapper`'s default `DataBindContext` pre-registers all of
      them (none can self-declare `@Atom`, being JDK classes; `byte[].isArray()` is `true`, so array
      auto-detection would otherwise claim it the same way records claim `Rational`/`Complex`, but
      unlike those there's no competing richer type to defer to), TSON-specific defaults kept out
      of `tson-bind` itself deliberately (see `TsonMapper.defaultContext()`) — see
      [Conformance](#conformance) below for a few edge-case behaviors worth knowing about
- [x] Object binding library (`tson-annotation` + `tson-bind`) — reflection/`MethodHandle`-based Java
      object ↔ data binding, including hand-written (pre-record) immutable class support via the
      `java.lang.classfile` API
- [x] `tson-mapper` — binds parsed TSON documents directly to Java objects/records, including dispatch
      into the built-in type vocabulary

See [CLAUDE.md](CLAUDE.md#architecture) for the current architecture and design notes.

**Not yet implemented:**

- [ ] Built-in type vocabulary (§5), remaining families:
  - [ ] Network/identifier types — `ipv4`/`ipv6`/`cidr4`/`cidr6`/`mac` (§5.5, `uuid`/`uri` done)
- [ ] Resolver-layer structural rules: record/map "last value wins" deduplication (§2.5/§2.6), `EmptyBrace`
      resolution (§2.8), Absent Sentinel semantics (§2.9)
- [ ] `Map<K, V>` support in `tson-bind` (no `DataClass` currently recognizes a map target)
- [ ] Auto-detect plain Java `enum`s in `tson-bind` the way real records/arrays already are — today
      `DefaultAtomBinder` only wires `EnumStringBridge` when the enum type itself carries `@Atom`;
      a bare `enum` with no annotation fails to bind at all
- [ ] General `@`-annotation binding to user classes (only the `!typeName` type-ref is consumed today;
      arbitrary `@`-annotations parse but aren't exposed to bound objects)
- [ ] Header/value directive interpretation — `!!id` content-addressing verification, `!!schema` loading
- [ ] Multi-error reporting (§8.1) — currently fail-fast on the first lex/parse error
- [ ] Security hardening enforcement (§9) — numeric-literal length limit (§9.1), confusable-character and
      bidi-formatting-character warnings (§9.4/§9.5)
- [ ] Anything from Part 2 (schema grammar, type system) — not started

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
