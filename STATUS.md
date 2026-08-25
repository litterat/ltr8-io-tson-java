# Status

← back to the [README](README.md)

Built against TSON Part 1 (lexer + data format), a working draft: https://tson.io/raw/2026/33/tson-part1-data.md,
and Part 2 (schema grammar + type system), also a working draft: https://tson.io/raw/2026/33/tson-part2-schema.md

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
      (e.g. the integer family's exact width isn't recoverable schemaless; see [CONFORMANCE.md](CONFORMANCE.md))
- [x] Streaming reads throughout — the lexer reads from an `InputStream`, and both the schemaless
      binder (`TsonObjectReader`) and the schema-validating reader (`TsonTypeReader`) pull events one
      at a time rather than materializing a whole document tree first
- [x] Streaming writes to match — both writers take an `OutputStream`/`Appendable` and emit as they go,
      so a large or open-ended document is never built as a `String` first (`toTson` is that call over
      a buffer)
- [x] Self-describing output — a writer can emit the `!!schema`/`!!id` header the readers already
      honour (`describing(…)`/`identifiedBy(…)`, off by default), so a document this library reads it
      can also reproduce, and a response body says what governs it without anything out of band
- [x] Reading a self-describing document with nothing named up front — `tson.treeReader().read(text)`
      resolves the document's own `!!schema`, picks the type from its root type-ref (`!person`) and
      validates against it; `tson.validate(text)` is the same read returning only the problems
- [x] Consumable as an ordinary dependency — `./gradlew publishToMavenLocal` installs every module as
      `io.ltr8:<module>`, with sources and javadoc, usable on the class path or the module path (see
      the README's [Use it from another project](README.md#use-it-from-another-project))
- [x] Part 2 schema grammar — full schema document parsing into a faithful AST (records, compositions,
      refinements, generic/array-sugar type-refs, field groups, and more), verified end-to-end against
      the spec's own real `meta-kernel.tn`/`meta.tn`/`core.tn` fixtures
- [x] Part 2 schema resolution — composition (`&`), refinement (`^`) including tightening, bare and
      generic type references, field modifiers/defaults/fixed values, type parameters, array sugar,
      and generalized constructor-application/atom-refinement resolution (`!C value`); `meta-kernel.tn`
      resolves all 49 of its own declarations, `meta.tn` all 31, and `core.tn` all 48, all
      end-to-end (see [BACKLOG.md](BACKLOG.md) for the specific constructs still out of scope)
- [x] Part 2 schema linking and registration — validates a document's own `!!id`/`!!import` header
      directives during resolution, flattens argument-bearing type references into real named
      entries, merges `!!import`s, validates every reference in a schema actually resolves, and
      locks a schema into a registry keyed by its canonical `!!id`
- [x] A compiled, schema-validating data reader (Class 2, §1.5) — compiles a linked schema into real
      Java object references between per-type parsers (DOM or object-binding mode) for fast repeated
      reads against real TSON data documents (see the README's [Schema pipeline](README.md#schema-pipeline))
- [x] The full pipeline verified end-to-end, three schemas deep — an ordinary, user-defined schema
      can `!!import` `core.tn` (itself governed by `meta.tn`, governed by `meta-kernel.tn`) and
      compile cleanly with real, manually-registered Java classes bound against records composed
      from its imported vocabulary, reading real TSON data through the whole chain

**Not yet implemented:**

See [BACKLOG.md](BACKLOG.md) for the actively-tracked engineering backlog, and
[STRUCTURED-OUTPUT.md](STRUCTURED-OUTPUT.md) for the target-use-case plan (LLM structured-output
validation, JSON compatibility).

See [CLAUDE.md](CLAUDE.md) for orientation, [docs/](docs/) for the per-area design notes, and
[CONFORMANCE.md](CONFORMANCE.md) for edge-case behavior worth knowing about.
