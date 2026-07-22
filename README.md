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
      `complex_type`/`uuid_type`/`binary` families — `int8`..`int256`, `uint8`..`uint256`,
      `positive_integer` and siblings (§5.6, extended beyond the four currently published — see
      [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md) #6), `number`, `float32`/`float64` (including
      `hex-float`), `rational`, `complex` (§7.6's `hex-float`/`rational`/`complex` extended grammar
      forms, only reachable through these atoms), `uuid` (§5.5), `base64`/`base64url`/`base32`/
      `hex` (§5.3) — `tson-parser`'s `resolver.vocab` package. Binding `!rational`/`!complex` to a
      Java field requires a `DataBridge` registered on the `DataBindContext` (`Rational`/`Complex`
      are themselves Java records, so `tson-bind`'s record auto-detection claims them ahead of the
      vocabulary path — direct binding to them doesn't work, by design; see their Javadoc). `!uuid`
      and the binary atoms bind directly to `java.util.UUID`/`byte[]` — `TsonMapper`'s default
      `DataBindContext` pre-registers both (`UUID` can't self-declare `@Atom`, being a JDK class;
      `byte[].isArray()` is `true`, so array auto-detection would otherwise claim it the same way
      records claim `Rational`/`Complex`, but unlike those two there's no competing richer type to
      defer to), TSON-specific defaults kept out of `tson-bind` itself deliberately (see
      `TsonMapper.defaultContext()`)
- [x] Object binding library (`tson-annotation` + `tson-bind`) — reflection/`MethodHandle`-based Java
      object ↔ data binding, including hand-written (pre-record) immutable class support via the
      `java.lang.classfile` API
- [x] `tson-mapper` — binds parsed TSON documents directly to Java objects/records, including dispatch
      into the built-in type vocabulary

See [CLAUDE.md](CLAUDE.md#architecture) for the current architecture and design notes.

**Not yet implemented:**

- [ ] Built-in type vocabulary (§5), remaining families:
  - [ ] Temporal types — `date`/`datetime`/`time`/`duration` (§5.4)
  - [ ] Identifier/network types — `uri`/`ipv4`/`ipv6`/`cidr4`/`cidr6`/`mac` (§5.5, `uuid` done)
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
