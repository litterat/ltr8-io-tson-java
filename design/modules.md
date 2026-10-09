# Modules and dependency direction

One entry per module: what it holds, what it depends on, and why the boundary is where it is. Current form only;
history lives in git.

**Invariants**

- Java 25; no external runtime dependencies in main code.
- `tson-compiler` depends on `tson-schema`, not the reverse; `schema.meta` names no `tson-compiler` type.
- `tson-tree`, `ltr8-regex` and `ltr8-unicode` are leaves, `ltr8-net` depends only on `ltr8-unicode`, and
  `tson-base` depends only on `ltr8-bind`, `ltr8-net` and `ltr8-unicode`; `tson-json` has no dependency on
  `tson-compiler`.
- `Tson`/`Json` prefix only what a consumer names; unexported packages hold bare names.
- No `opens` directives; an unexported package is genuinely unreachable.
- A resolver never names a facade (`TsonObjectWriter`) — only the engine beneath it.

Related: `design/tson-base.md` (the shared vocabulary, package by package), `design/json-encoding.md` (the `tson-json`
stack and why it is separate).

Package group is `io.ltr8` (reverse-DNS identifies who *publishes* the artifact — this is one
implementation of the spec published under the `ltr8.io` banner, not *the* tson.io-blessed one). Every
module has a real `module-info.java`; module names mirror each module's root exported package.

- **`tson-base`** — how a problem is stated (`Diagnostic`, the receivers, `SourcePosition`, `CanonicalIdentity`, the
  processor's exceptions), what a processor admits and spends (`policy`), where a schema comes from (`source`), the
  host atom values (`atom`), what a deployment binds with (`bind`), what a rule says when broken (`diagnostics`), byte
  I/O (`io`), plus `ProcessorConfig`. A pure leaf but for
  `ltr8-bind`, `ltr8-net` and `ltr8-unicode`.
  `design/tson-base.md` has each package and its rationale.
- **`ltr8-annotation`** — `@Typename`/`@Field`/`@Record`, the binding annotations, plus `Annotations`/
  `Annotation`, the wire-annotation carrier a bound class declares a component of. The carrier lives here
  rather than with the engine because it is the one module `ltr8-bind` (which analyses classes),
  `tson-schema` (whose `schema.meta` model is itself a bind target) and consumer code all see.
- **`ltr8-bind`** — the generic `DataValue`↔Java-object binding engine (`DataBindContext`, `DataClass`
  descriptors, `DataNameBinder`, bridges). Depends only on `ltr8-annotation`, whose annotations and carrier
  types it reads off a class under analysis. A context may name a **binding profile**
  (`DataBindContext.Builder.profile`), selecting among a class's `@Profile` constructors so one class binds
  several shapes — one context per schema version, descriptors still cached per context. The name is opaque
  here: matched by equality, with nothing in the module knowing what it stands for, which is what keeps
  selection out of the schema layer. **A cyclic type graph resolves** — a record reaching itself, directly or
  through others: `getDescriptor` hands a re-entrant call a deferred supplier and each holder keeps it in a
  final `Memoized`, so laziness is confined to the cyclic edge and every other component still resolves
  eagerly. The AST is the case that needs it (`DataValue` → `CoreValue` → `RecordValue` → `ScopedValue` →
  `DataValue`), which is what lets a held template body be written at all.
- **`tson-schema`** — `io.ltr8.tson.schema.meta` (the resolved-schema *value* model — pure
  records/sealed interfaces/enums, §8's `TypeDefinition` et al.; `Top` is sealed except for its one
  deliberately open branch, `Data`, which a consumer's own class implements — see below). **The host value
  types are not here**: `Rational` and `Complex` are `base.atom`'s and the `CidrNetwork` pair `ltr8-net`'s, because
  *what do I get back from `!rational`?* is a question about the type system rather than about §8's model,
  and they depend on nothing. `schema.meta` reads them structurally — `RationalType`'s
  `min`/`max`/`multiple_of` are `Rational` values — a pull from above rather than a reason to live above. Plus the schema
  registry (`TsonSchemaRegistry`/`TsonLinkedSchema`/`TsonSchemaLoader`) and
  `TsonBundledSchemas`. **The linker is not here** — it is an engine, not a value model, so
  `TsonSchemaLinker`/`ChoiceDisjointness` live in `tson-compiler` with the rest of the pipeline; what stays is storage
  and the identity algorithm lookups compare by. Depends on `ltr8-annotation`, `tson-base` (`requires
  transitive`) and `ltr8-regex` — the last so that `text_type`'s member-against-pattern coherence sits on the
  family with the length checks it shares a rule with, rather than being split across modules by which engine
  each half needs. The engine is an internal library like any other; the boundary worth keeping is the one
  that stops a value model depending on the *pipeline*, and `ltr8-regex` is a leaf.
  **`tson-compiler` depends on `tson-schema`, not the reverse** — the opposite of what the names suggest, deliberately
  so the compiler's resolver can hold and consult `schema.meta` types directly. `schema.meta` names no `tson-compiler`
  type; where it needs
  one structurally it declares a local stand-in (`schema.meta.Token` mirrors `ast.TokenValue`/`TokenForm`;
  `tson-base`'s `SourcePosition` is an interface `tson-compiler`'s `Position` implements), converted at the
  one spot that needs it.
- **`tson-atom`** — the built-in atom vocabulary: which tokens each family accepts and what host value results (§5.2's
  parsing contracts). **A module rather than a package inside an engine, because the vocabulary is not an engine's** —
  [TSON-JSON] §5.1 hands a JSON string's content to the atom's own parser exactly as a TSON quoted token's text would be, so
  which families a reader binds, and what they read to, is a property of the type system and not of the encoding that carried
  them. Three packages, split by who touches them: `io.ltr8.tson.atom` is what a caller names — `AtomType`, the two indices
  over it (`BuiltinTypeVocabulary` by name, `HostAtoms` by host class), `AtomParsers` from a resolved body, `VocabularyAtoms`
  for the write direction, `IdentifierGrammar` (§7.7's name grammar, built from the kernel's `identifier`), and the
  exceptions a refusal arrives as; `io.ltr8.tson.atom.number` is §4's number production and
  the narrowing over it, exported because base type resolution stays with the text encoding and reads it;
  `io.ltr8.tson.atom.parser` is the 23 family implementations and is **unexported**, on the same terms as `tson-compiler`'s
  own `lexer` and `reader`. Depends on `tson-schema` (a parser holds its constraint record), `tson-base` and `ltr8-regex`.
  **What deliberately stayed behind is everything that depends on *how* a token was written**: `AtomType` takes a `String`,
  and the two atoms needing the lexical form — the kernel's `value`, whose §4.4 rule is that a quoted token is a string, and
  `Token`, which records the spelling §8's resolved form carries — stay in `tson-compiler` with `TokenValue` and
  `BaseTypeResolver`. That those two are exactly where the encodings legitimately differ is no coincidence: JSON has no token
  forms and reads a `value` position by [TSON-JSON] §5.7's own rule.
- **`tson-tree`** — **only** `io.ltr8.tson.tree` (the data-document *value* model — `TsonValue` and its
  pure immutable node types, structure-preserving and query-ergonomic, the read output of tree mode). A
  true leaf: depends on **nothing** (not even `ltr8-annotation` — the nodes aren't bind targets, they're
  assembled by hand-written readers). The data-tree counterpart to `tson-schema`'s `schema.meta`: same
  "pure value model in its own module, engine depends on it not the reverse" shape, so JPMS keeps the tree
  from ever coupling to compiler internals. `tson-compiler` depends on it; it names no `tson-compiler` type.
- **`ltr8-regex`** — **only** `io.ltr8.regex`: a native RFC 9485 I-Regexp engine — `IRegex.parse`
  builds a `RegexNode` AST (or `IRegexSyntaxException`), `IRegex.matches` runs a Thompson-NFA/Pike-VM
  simulation (linear-time, no backtracking → ReDoS-safe; `\p{…}` via JDK `Character.getType`), and
  `IRegex.isDisjointFrom` decides whether two patterns share any string (exact — a symbolic product-NFA
  emptiness check over a `CodePointSet` interval algebra; §5.4 decides choice disjointness by class, never by pattern).
  A true leaf — depends on **nothing**, I-Regexp being an external standard, not TSON-specific. The
  *engine* counterpart to `ltr8-bind` (a general dependency-free engine), not a value model like
  `tson-tree`; TSON pins its `regex` atom to I-Regexp (`regex_type`'s fixed `spec = rfc9485`), so
  this owns I-Regexp semantics rather than delegating to `java.util.regex` (a laxer superset).
  `tson-schema`, `tson-atom` and `tson-compiler` require it; it names no TSON type.
- **`ltr8-net`** — **only** `io.ltr8.net`: native recognizers for network text formats, each to its RFC. `Iri.parse`
  reads an RFC 3986 URI-reference or RFC 3987 IRI-reference into its components as written (or throws
  `IriSyntaxException`), never resolving, normalising or percent-decoding. A library with no `Tson` prefix, as
  `io.ltr8.bind` has none: it knows nothing of TSON and is usable on its own. Beside `Iri`: `InternetAddress` (RFC
  3986's IPv4 and RFC 4291's IPv6 text forms, to octets and back, which `Iri`'s IP literals read through), the
  `CidrNetwork` pair (RFC 4632 / RFC 4291 §2.3 prefixes, with containment and the host-bits rule), `MacAddress` (RFC
  9542's EUI-48), `HostName` (a domain name in either label form, IDNA2008, over `Punycode`) and `Host` (a `HostName`
  or a `Host.Address`). Each exists because the JDK's answer is another grammar: `java.net.URI` implements RFC 2396
  and cannot hold a host beyond US-ASCII, `InetAddress` admits leading zeros, short forms and bare integers, and
  `java.net.IDN` is IDNA2003. Its one dependency is `ltr8-unicode`, for IDNA2008's derived property and the Bidi rule,
  which belong with the Unicode tables at their one version. `tson-base` requires it transitively
  (`CanonicalIdentity`, `SchemaReference`, and its values as host types), `tson-schema`'s coherence checks judge facet
  entries with it, and `tson-atom` wraps each format in an `AtomTypeParser` that adds the facets.
- **`ltr8-unicode`** — **only** `io.ltr8.unicode`: Unicode Character Database properties and the algorithms over them,
  each to its Unicode standard. The tables: `Xid` (UAX #31's `XID_Start`/`XID_Continue`, exact, and `UNICODE_VERSION`,
  the one version every table is checked against), `Nfc` (UAX #15, allocation-free on text already in NFC),
  `NfkcCasefold`, `IdentifierStatus`, `Confusables` (`skeleton`) and `IdnaProperty` (RFC 5892's derived property,
  computed by the RFC's rules and checked against IANA's last table). The algorithms over them: `Normalization` (the
  text forms), `IdentifierProfile` (a UAX #31 R1 profile), `JoiningControls` (UTS #39's limited contexts for ZWNJ and
  ZWJ), `ConfusableNames` (skeleton distinctness over a set), `RestrictionLevel` (UTS #39 §5.2's levels) and
  `BidiRule` (RFC 5893). A true leaf that knows nothing of TSON, named for the publisher rather than the format. The
  boundary is **mechanism versus choice**: what the Unicode standards define lives here, and what a format chooses —
  its own profile, the policy it applies and the words of a refusal — stays with the format (`tson-atom`'s
  `IdentifierGrammar`, `tson-base`'s `ScriptPolicy` and `IdentifierPolicy`). One version for every table is the reason
  it is one module: a JDK whose Unicode version moves re-derives them together. `tson-base` requires it transitively,
  so the engines above read `Nfc`, `Normalization` and `IdentifierProfile` without naming a second module, and a
  consumer naming a level names `RestrictionLevel`, since the levels are Unicode's.
- **`tson-compiler`** — the engine: lexer, both grammars, base type resolution, the token-side atom glue
  (`atom`: `RawTokenParser`, `TokenAtomType`, `ValueParser` — the vocabulary itself is `tson-atom`'s),
  schema resolution, Class 2 compilation, the compiled reader stack, the schema-aware read facades
  (`TsonTreeReader`/`TsonObjectReader`) over their schemaless `reader`-package engines
  (`SchemalessTreeReader`/`DataClassObjectReader`), the `TsonTreeWriter`/`TsonObjectWriter` writers over
  their own `writer`-package engines (`TreeValueWriter`/`DataClassObjectWriter`), and
  config/wiring. Everything here is tightly coupled to the shared lexer/token-stream machinery, so it's
  one module. Root package `io.ltr8.tson.compiler`; exports the packages with real cross-module callers
  and keeps `reader`/`atom`/`base`/`lexer` internal.
- **`tson`** — the front door, and **one class**: `Tson`, over `tson-compiler`, the way Retrofit sits on
  OkHttp. Declares `tson-compiler`/`tson-schema`/`ltr8-bind`/`tson-tree` as `api` so a caller sees the real
  classes underneath. **`ProcessorConfig` is not here** — a configuration is a value stating what a deployment
  chose, so it sits in `tson-base` with the values it holds and is shared by every encoding; what cannot
  follow it is construction, which names the compiler's own registry. Hence `Tson.of(config)`, and
  `Tson.standard()` for the unconfigured case.
- **`tson-json`** — the JSON encoding ([TSON-JSON]): its own lexer, structural layer, tree (aligned with JEP 540's
  value model and nothing else of it), readers, writers and schema-directed reader stack, with no dependency on
  `tson-compiler`. Parity with the TSON stack is guarded by `CrossEncodingParityTest` rather than by shared code.
  `design/json-encoding.md` has the design.
- **`tson-cli`** — the `tson` command-line application. Depends on nothing depending on it (exports
  nothing).

**JPMS enforcement is real, not just convention.** An unexported package is genuinely unreachable from
other modules (verified by scratch-importing across a boundary and watching it fail). Internal dispatch
types kept in unexported packages but referenced by a public method signature produce an accepted
`-Xlint:exports` warning (e.g. `ValueReaderFactoryResolver`); this is deliberate, not a defect. No `opens`
directives — binding only ever touches public constructors/methods.

