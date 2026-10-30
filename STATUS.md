# Status

← back to the [README](README.md)

Built against TSON Part 1 (lexer + data format), a working draft: https://tson.io/raw/2026/38/tson-part1-data.md,
and Part 2 (schema grammar + type system), also a working draft: https://tson.io/raw/2026/38/tson-part2-schema.md

This is the spec's first implementation. Issues and ambiguities found in the spec while implementing are
tracked in [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md).

**Implemented:**

- [x] Lexer and structural parser — records, maps, arrays, annotations, and the one header grammar for every
      document, an optional `!!id` then any number of `!!import`s (arguments validated as URIs, not just
      single-line tokens); a document is a schema exactly when its root is tagged `!schema`
- [x] Base types — boolean, string, numbers (integer, float, hex-float, based-integer), and the absent
      sentinel `_`
- [x] Integer types — `int8`–`int256`, `uint8`–`uint256`, and `integer` with its bounds
- [x] Decimal/float types — `number`, `float32`, `float64`, `rational`, `complex`
- [x] Identifier/network/text types — `uuid`, `uri`, `uri_reference`, `iri`, `iri_reference`, `ipv4`, `ipv6`, `cidr4`,
      `cidr6`, `mac`, `hostname`, `host`, `media_type`, `email`, `text`, `regex` (RFC 9485 I-Regexp); the network
      types, `hostname`, `host` and `media_type` are `net.tn`'s, a library a schema imports beside core
      (Part 1 §5.5, §5.6; Part 2 §9), `hostname` is IDNA2008's, in either label form, and `media_type` is RFC 6838's,
      with RFC 9110 parameters
- [x] Binary type — `bytes`, with its encoding (`base64`, `base64url`, `base32`, `hex`) chosen by the type
- [x] Temporal types — `date`, `time`, `datetime`, `duration`, `period`
- [x] Object binding — Java records, hand-written immutable classes, `Map<K, V>`, tuples, plain
      enums, sealed interfaces/unions
- [x] Typed field names — `record.name_type` names the identifier family a record's field names belong to, as
      `enum_type.name_type` does an enum's members, so a record declares `@id`, `$ref` or `first name` as written,
      judged under its own name type in both encodings (Part 2 §5.2)
- [x] Kinds — `kind<uuid_type>`, or `!kind_type { of: uuid_type }`, is the atom whose values are the names of the
      types in the schema's closure built with a constructor: its members are computed at link, never listed, and a
      value is a type name that must name one, in both encodings. The kernel types `enum_type.name_type` and
      `record.name_type` by its `identifier_kind`, and judges a name type by membership (Part 2 §4.2)
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
- [x] Self-describing output — a writer can emit the `!!id`/`!!import` header the readers already
      honour (`describing(…)`/`identifiedBy(…)`, off by default), so a document this library reads it
      can also reproduce, and a response body says what governs it without anything out of band
- [x] Reading a self-describing document with nothing named up front — `tson.treeReader().read(text)`
      resolves the document's own `!!import`s (several read as one namespace), picks the type from its root
      type-ref (`!person`) and validates against it; `tson.validate(text)` is the same read returning only the
      problems
- [x] Consumable as an ordinary dependency — `./gradlew publishToMavenLocal` installs every module as
      `io.ltr8:<module>`, with sources and javadoc, usable on the class path or the module path (see
      the README's [Use it from another project](README.md#use-it-from-another-project))
- [x] Part 2 schema grammar — full schema document parsing into a faithful AST (records, compositions,
      refinements, generic/array-sugar type-refs, field groups, and more), verified end-to-end against
      the spec's own real `meta-kernel.tn`/`meta.tn`/`core.tn` fixtures
- [x] Part 2 schema resolution — composition (`&`), refinement (`^`) including tightening, bare and
      generic type references, field modifiers/defaults/fixed values, type parameters, array sugar,
      and generalized constructor-application/atom-refinement resolution (`!C value`), every declaration
      made ready in wait order whatever order it is written in, so a constructor, a type or an annotation
      type may be used in the document that declares it; every declaration
      in `meta-kernel.tn`, `meta.tn` and `core.tn` resolves end to end (see [BACKLOG.md](BACKLOG.md) for
      the specific constructs still out of scope)
- [x] Part 2 schema linking and registration — validates a document's own `!!id`/`!!import` header
      directives during resolution, flattens argument-bearing type references into real named
      entries, merges every `!!import`'s namespace into one per schema
      ([SPEC-FEEDBACK.md](SPEC-FEEDBACK.md) #7), validates every reference in a schema actually resolves, and
      locks a schema into a registry keyed by its canonical `!!id`
- [x] Value entries ([SPEC-FEEDBACK.md](SPEC-FEEDBACK.md) #9) — an entry whose tag names an ordinary type
      (`create_order => !operation { … }`, `orders => !api { … }` over a namespace type `api => !namespace { … }`)
      is a value of it, no type and in no kind, named by `ref<T>` and walked by key and field; bound through the
      one bind context, or held as a tree where no class binds its type, so the schema loads and validates
- [x] A compiled, schema-validating data reader (Class 2, §1.5) — compiles a linked schema into real
      Java object references between per-type parsers (DOM or object-binding mode) for fast repeated
      reads against real TSON data documents (see the README's [Schema pipeline](README.md#schema-pipeline))
- [x] The full pipeline verified end-to-end, three schemas deep — an ordinary, user-defined schema
      can `!!import` `core.tn` (itself importing `meta.tn`, which imports `meta-kernel.tn`) and
      compile cleanly with real, manually-registered Java classes bound against records composed
      from its imported vocabulary, reading real TSON data through the whole chain
- [x] Resource limits (§9.1, §11.5) — nesting depth is bounded (64 by default, `ProcessorConfig.withLimits`,
      `tson validate --max-depth`), and so is a schema's wait depth (64 by default, `--max-wait-depth`); a
      refusal is reported as `LIMIT_EXCEEDED` rather than as a verdict on the document or schema
- [x] The JSON encoding ([spec/tson-part3-json.md](spec/tson-part3-json.md), drafted here) — a stack of its
      own in `tson-json`: lexer, event stream, `JsonValue` tree, schemaless binding, and schema-directed
      tree reads against the same TSON schemas; `tson validate` reads `.json` files with `--schema`/`--type`

**Not yet implemented:**

See [BACKLOG.md](BACKLOG.md) for the actively-tracked engineering backlog, and
[STRUCTURED-OUTPUT.md](STRUCTURED-OUTPUT.md) for the target-use-case plan (LLM structured-output
validation, JSON compatibility).

See [CLAUDE.md](CLAUDE.md) for orientation, [design/](design/) for the per-area design notes, and
[CONFORMANCE.md](CONFORMANCE.md) for edge-case behavior worth knowing about.
