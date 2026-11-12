# Status

← back to the [README](README.md)

Built against TSON Part 1 (lexer + data format), a working draft: https://tson.io/raw/2026/39/tson-part1-data.md,
and Part 2 (schema grammar + type system), also a working draft: https://tson.io/raw/2026/39/tson-part2-schema.md

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
      `cidr6`, `mac`, `hostname`, `host`, `media_type`, `email`, `text`, `regex` (RFC 9485 I-Regexp); `text`, `iri`,
      `iri_reference` and `regex` are the kernel's, the rest `net.tn`'s, a library core.tn imports
      (Part 1 §5.5, §5.6; Part 2 §9), `hostname` is IDNA2008's, in either label form, and `media_type` is RFC 6838's,
      with RFC 9110 parameters
- [x] Binary type — `bytes`, with its encoding (`base64`, `base64url`, `base32`, `hex`) chosen by the type
- [x] Temporal types — `date`, `time`, `datetime`, `duration`, `period`
- [x] Object binding — Java records, hand-written immutable classes, `Map<K, V>`, tuples, plain
      enums, sealed interfaces/unions
- [x] Typed field names — `meta.record.name_type` names the identifier family a record's field names belong to, as
      `meta.enum_type.name_type` does an enum's members, so a record declares `@id`, `$ref` or `first name` as written,
      judged under its own name type in both encodings (Part 2 §5.2)
- [x] References (Part 2 §4.2) — one atom, `!meta.reference_type { of: T }`, spelled
      `meta.ref<T>` whatever `T` is, admits a name exactly when the named entry's value, past
      its alias hops, is of a type IS-A `T`. Over a constructor (`meta.ref<net.uuid_type>`) its values are the names of
      the types in the schema's closure built with it: computed at link, never listed or written, and judged by
      membership in both encodings. Over any other type its values are qualified names of values. The kernel types
      `meta.enum_type.name_type` and `meta.record.name_type` by its `meta.identifier_ref`, a refinement of
      `meta.type_ref`, and judges a name type by membership (Part 2 §4.2). A refinement narrows `of` to the source's or
      a type IS-A it, judged at link (Part 2 §5.7)
- [x] A reference is a name (Part 2 §4.2, §5.10) — an application is schema grammar, the construction `meta.apply`
      written in place, and an argument list is no data form ([TSON-DATA] §7.2.5): `t: set<text>` in a data document or
      a schema value's payload is a parse error, the grammar's ordinary one, whether or not the schema closes the
      application. A name reads to a `TypeRef` whose `referent()` is the entry's value. In JSON a reference is the string
      of a qualified name, and `"set<text>"` is refused as a string outside its grammar
- [x] A dependent field's type is a sum written in place (Part 2 §5.2, `SPEC-FEEDBACK.md` #6) —
      `{ of: !meta.dependent { on: kind } }` at a record field's type, `kind!` in schema-grammar sugar, which parses at
      a field's or group member's type and nowhere else; a `meta.dependent` declared or at any other slot is refused at
      link. `kind!` is no data form in either encoding
- [x] A record written in place (Part 2 §5.2) — a record body at a field's type, a group member's, an array's
      element, a map's value, a tuple's element or a choice's variant (`at: { x: int32 }`) is the record an
      application of an open record closes to in place, read untagged in tree and bind mode in both encodings; one over
      a parameter of its open record is `NOT_IMPLEMENTED`
- [x] Dependent fields (Part 2 §5.2) — `v?: kind!` is read per instance
      against the type its sibling's value names, in tree and bind mode (an `Object` component) and in JSON. The
      sibling's rules are checked at the declaration against the flattened record: declared first, typed by a reference
      to types, there to read whenever it is (a field group's member only beside an optional dependent field, which is
      refused where it is read without it), its pin or default no template; the dependent field never voidable, carrying
      no value, restated only as itself. A pinned sibling gives the field a fixed reader, a discriminator a member's, and a
      free sibling is written before its dependent field. A value's qualified name going on through one takes the
      type its sibling names in that value (Part 2 §5.12). The kernel's `record_field.value` and
      `record_template_field.value` are `type!`. A value entry's dependent value, of any form, is held as written and
      judged when the schema links against the type its sibling names in the schema's own namespace
- [x] Every `!schema` value declares (Part 2 §4.1) — `geo => !schema { address => { … }
      }` is a schema map nested as an entry, its members declarations that resolve, link and compile as root
      entries do, each named by its path from the root (`geo.address`). A nested map is open: several documents of a closure
      declaring `geo` contribute to one map, the union of their members, and only a member path declared twice
      collides.
      Every name resolves from the root, with no lexical scope, and a qualified name stands at every type position,
      an application's head (`geo.box<text>`) and a reference's `of` (`meta.ref<geo.address>`) included; a value's name
      walks through nested maps (`geo.home.at`). A data tag is a qualified name (`!geo.address { … }`, `"$type":
      "geo.address"` in JSON), and resolved output writes a nested map nested
- [x] A bundled document's root holds only what an author writes (Part 2 §9) — the
      one kernel document, meta.tn, pre-loaded and importing nothing, is its wait cycle's closure plus the map, tuple,
      choice, template, reference and octet-sequence vocabulary and the classifying annotations; its root is `void`,
      `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`, `iri_reference`, `regex`, `doc`, `enum` (`!enum [A B]`),
      `set<T>` and `schema`, the alias of `meta.schema` every schema map's tag names, and all its machinery sits under
      the open namespace `meta` (`meta.record`, `meta.identifier`, `meta.type_ref`, `meta.ref<meta.record>`,
      `binary.bytes_type`, `@atom.ordering:TOTAL`, `@meta.application`).
      The data-format libraries numeric.tn, temporal.tn and net.tn each import the kernel alone, hold their
      instances at their root (`int32`, `date`, `uuid`) and their constructors under a map of their own
      (`numeric.decimal_type`, `temporal.date_type`, `net.uuid_type`). core.tn imports the three and its root holds
      only the author annotations (`@title`, `@deprecated`, …). A schema importing core reserves `meta`, `atom`,
      `numeric`, `unicode`, `net`, `binary`, `temporal` and the root names of the kernel, the libraries and core, so
      `record`, `identifier`, `type_ref` and `kind` stay free; one importing only temporal.tn sees `date` and no `int32`.
      Any schema may contribute a member to a nested map, which stays its own
- [x] A constructor is an ordinary record type (Part 2 §4.1) — `net.uuid_type`'s value is
  a record, so `meta.type_ref` and `meta.ref<meta.record>` hold it and a field typed `net.uuid_type` holds a declaration
  body as data, in TSON tree and bind mode and in JSON
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
      the spec's own real `meta.tn`/`numeric.tn`/`temporal.tn`/`net.tn`/`core.tn` fixtures
- [x] Part 2 schema resolution — composition (`&`), refinement (`^`) including tightening, bare and
      generic type references, field modifiers/defaults/fixed values, type parameters, array sugar,
      and generalized constructor-application/atom-refinement resolution (`!C value`), every declaration
      made ready in wait order whatever order it is written in, so a constructor, a type or an annotation
      type may be used in the document that declares it; every declaration in `meta.tn`, `numeric.tn`,
      `temporal.tn`, `net.tn`, `core.tn` and `policy.tn` resolves end to end (see [BACKLOG.md](BACKLOG.md) for the
      specific constructs still out of scope)
- [x] Part 2 schema linking and registration — validates a document's own `!!id`/`!!import` header
      directives during resolution, closes each argument-bearing type reference into its declaration or in place
      (a construction, or the record an open record's application closes to, with no name), merges every
      `!!import`'s namespace into one per schema
      (Part 2 §2.2.3, §3.3), validates every reference in a schema actually resolves, and
      locks a schema into a registry keyed by its canonical `!!id`
- [x] Value entries (Part 2 §4.1) — an entry whose tag names an ordinary type
      (`create_order => !operation { … }`, `orders => !api { … }` over a namespace type `api => !meta.namespace { … }`)
      is a value of it, no type and in no kind, named by a reference over its type and walked by key and field;
      bound through the one bind context, or held as a tree where no class binds its type, so the schema loads and
      validates
- [x] A compiled, schema-validating data reader (Class 2, §1.5) — compiles a linked schema into real
      Java object references between per-type parsers (DOM or object-binding mode) for fast repeated
      reads against real TSON data documents (see the README's [Schema pipeline](README.md#schema-pipeline))
- [x] The full pipeline verified end-to-end, three schemas deep — an ordinary, user-defined schema
      can `!!import` `core.tn` (itself importing `numeric.tn`, `temporal.tn` and `net.tn`, each importing the kernel,
      `meta.tn`) and compile cleanly with real, manually-registered Java classes bound against records composed
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
