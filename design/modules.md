# Modules and dependency direction

One entry per module: what it holds, what it depends on, and why the boundary is where it is. Current form only;
history lives in git.

**Invariants**

- Java 25; no external runtime dependencies in main code.
- `tson-compiler` depends on `tson-schema`, not the reverse; `schema.meta` names no `tson-compiler` type.
- `tson-tree`, `tson-regex` and `tson-base` (bar `tson-bind`) are leaves; `tson-json` has no dependency on `tson-compiler`.
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
  I/O (`io`) and the UCD tables (`unicode`), plus `ProcessorConfig`. A pure leaf but for `tson-bind`.
  `design/tson-base.md` has each package and its rationale.
- **`tson-annotation`** — `@Typename`/`@Field`/`@Record`, the binding annotations, plus `Annotations`/
  `Annotation`, the wire-annotation carrier a bound class declares a component of. The carrier lives here
  rather than with the engine because it is the one module `tson-bind` (which analyses classes),
  `tson-schema` (whose `schema.meta` model is itself a bind target) and consumer code all see.
- **`tson-bind`** — the generic `DataValue`↔Java-object binding engine (`DataBindContext`, `DataClass`
  descriptors, `DataNameBinder`, bridges). Depends only on `tson-annotation`, whose annotations and carrier
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
  types are not here**: `Rational`, `Complex`, the `CidrNetwork` pair and `InternetAddress` are `base.atom`'s, because
  *what do I get back from `!rational`?* is a question about the type system rather than about §8's model,
  and they depend on nothing. `schema.meta` reads them structurally — `RationalType`'s
  `min`/`max`/`multiple_of` are `Rational` values — a pull from above rather than a reason to live above. Plus the schema
  registry (`TsonSchemaRegistry`/`TsonLinkedSchema`/`TsonSchemaLoader`) and
  `TsonBundledSchemas`. **The linker is not here** — it is an engine, not a value model, so
  `TsonSchemaLinker`/`ChoiceDisjointness` live in `tson-compiler` with the rest of the pipeline; what stays is storage
  and the identity algorithm lookups compare by. Depends on `tson-annotation` and `tson-base` (`requires transitive`).
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
  for the write direction, and the exceptions a refusal arrives as; `io.ltr8.tson.atom.number` is §4's number production and
  the narrowing over it, exported because base type resolution stays with the text encoding and reads it;
  `io.ltr8.tson.atom.parser` is the 23 family implementations and is **unexported**, on the same terms as `tson-compiler`'s
  own `lexer` and `reader`. Depends on `tson-schema` (a parser holds its constraint record), `tson-base` and `tson-regex`.
  **What deliberately stayed behind is everything that depends on *how* a token was written**: `AtomType` takes a `String`,
  and the two atoms needing the lexical form — the kernel's `value`, whose §4.4 rule is that a quoted token is a string, and
  `Token`, which records the spelling §8's resolved form carries — stay in `tson-compiler` with `TokenValue` and
  `BaseTypeResolver`. That those two are exactly where the encodings legitimately differ is no coincidence: JSON has no token
  forms and reads a `value` position by [TSON-JSON] §5.7's own rule.
- **`tson-tree`** — **only** `io.ltr8.tson.tree` (the data-document *value* model — `TsonValue` and its
  pure immutable node types, structure-preserving and query-ergonomic, the read output of tree mode). A
  true leaf: depends on **nothing** (not even `tson-annotation` — the nodes aren't bind targets, they're
  assembled by hand-written readers). The data-tree counterpart to `tson-schema`'s `schema.meta`: same
  "pure value model in its own module, engine depends on it not the reverse" shape, so JPMS keeps the tree
  from ever coupling to compiler internals. `tson-compiler` depends on it; it names no `tson-compiler` type.
- **`tson-regex`** — **only** `io.ltr8.tson.regex`: a native RFC 9485 I-Regexp engine — `TsonRegex.parse`
  builds a `RegexNode` AST (or `TsonRegexSyntaxException`), `TsonRegex.matches` runs a Thompson-NFA/Pike-VM
  simulation (linear-time, no backtracking → ReDoS-safe; `\p{…}` via JDK `Character.getType`), and
  `TsonRegex.isDisjointFrom` decides whether two patterns share any string (exact — a symbolic product-NFA
  emptiness check over a `CodePointSet` interval algebra, the building block for §5.4 pattern disjointness).
  A true leaf — depends on **nothing**, I-Regexp being an external standard, not TSON-specific. The
  *engine* counterpart to `tson-bind` (a general dependency-free engine), not a value model like
  `tson-tree`; TSON pins its `regex` atom to I-Regexp (`regex_type`'s `REQUIRED_FIXED spec = rfc9485`), so
  this owns I-Regexp semantics rather than delegating to `java.util.regex` (a laxer superset).
  `tson-atom` and `tson-compiler` require it; it names no TSON type.
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
  OkHttp. Declares `tson-compiler`/`tson-schema`/`tson-bind`/`tson-tree` as `api` so a caller sees the real
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

