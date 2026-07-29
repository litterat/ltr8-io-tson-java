# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

---

## Front door / ergonomics

- [x] **A single "load the standard library" entry point — landed as `TsonStandardLibrary`**
  (`io.ltr8.tson.parser.config`, new package — see "Layer boundaries" below for the rest of the
  move). `TsonStandardLibrary.builder().build()` bootstraps meta-kernel → meta.tn1 → core.tn1 in one
  call; `.resolve(schemaText)`/`.compile(linked, mode)`/`.compile(schemaText, mode)` replace the
  hand-assembled `TsonSchemaRegistry`/`TsonCompiledRegistry`/`DefaultTsonCompiledSchemaLoader`
  wiring `TinySchemaImportsCoreTn1Test`/`CoreSchemaImportTest` (and `tson-cli`'s own now-deleted
  internal `StandardLibrary` helper) used to hand-roll. `TsonStandardLibraryTest` re-proves
  `TinySchemaImportsCoreTn1Test`'s exact scenario through this front door instead. Surfaced a real,
  non-obvious pipeline constraint along the way, now documented on the class itself: resolving an
  `Instance`/`AtomRefinement` declaration (`DefinitionResolver.bindAtomInstance`) always needs a
  real, object-binding-mode governing-meta reader, regardless of what mode the *final* compiled
  schema wants — DOM-mode resolution of the standard library itself fails outright (a `Map` can't
  cast to `schema.meta.Top`). Only *compiling* an already-resolved, already-linked schema
  (`TsonCompiledMetaSchema.bootstrap`) is free to pick a different mode, since it never re-resolves
  anything — hence `resolve` takes no mode parameter at all, only `compile` does.
- [x] **A fluent/builder API — landed as the same `TsonStandardLibrary`** above; `tson-cli`'s
  `ValidateCommand`/`CompileCommand`/`DiagnosticsSchema` were refactored to use it directly in place
  of their own hand-rolled wiring, so the CLI itself is now real, working validation that the
  builder is usable, not just a design on paper. Still narrow by design (no pluggable
  `TsonSchemaSource`, no config beyond the standard library itself) — `TsonStandardLibrary.Builder`
  is exactly where a future pluggable source belongs, per its own Javadoc.
- [x] **A CLI, ajv-cli-style — v1 landed** (new `tson-cli` module: `TsonCli`/`ValidateCommand`/
  `CompileCommand`/`OutputFormat`/`DiagnosticsSchema`, now built on `TsonStandardLibrary` above
  rather than its own internal copy). `tson validate --type <name> [--output text|json|tson]
  <schema> <data...>` and `tson compile [--output text|json|tson] <schema>`, positional
  schema-then-data arguments, Unix-conventional exit codes (0 valid/compiled, 1 a real failure, 2
  usage error). `--output tson` renders the report via the plain, schemaless `TsonMapperWriter` and
  reads it straight back through a small hand-authored `diagnostics.tn1` schema's own compiled
  `validation_report` reader (bound to real `ValidationReport`/`CliDiagnostic` classes) — genuinely
  dogfooded, not just described (`OutputFormatTest
  .tsonOutputGenuinelyRoundTripsThroughTheDiagnosticsSchema` proves the round trip, not just that
  the text looks TSON-shaped). Arg-parsing is hand-rolled, resolving the open question this bullet
  used to carry — no external dependency needed for a flag set this small.
  **Still v1-scoped, not the full design**: no `--all-errors` yet — each file's own read is still
  the existing fail-fast stack, with the single caught exception formatted into a one-entry report;
  real multi-error collection needs the full `Diagnostic`/`validate(...)` API `STRUCTURED-OUTPUT.md`
  still describes, not yet built. `--type` is required (no `!!schema`-header auto-selection exists
  yet, tracked separately below).
- [ ] `--all-errors`/`-a`, once the real `Diagnostic` API lands — collect every validation failure
  in a file instead of stopping at the first.
- [ ] No `!!schema`-header auto-selection on the data side — given a data document, there's no
  "find the right compiled reader yourself" entry point; a caller always has to already know what
  schema position it's reading against.
- [ ] A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy — only
  `BundledSchemaSource` and `TsonSchemaSource.registeredOnly()` exist today.
- [ ] API-surface pass, now that a real front door (`TsonStandardLibrary`) exists — a lot of what's
  `public` in `compiler`/`resolver`/`config` today is public only because tests needed cross-package
  access mid-refactor, not because it's meant to be part of a consumer-facing API.

## Layer boundaries / schema registry

- [x] **Configuration/wiring classes moved out of `compiler` into a new `io.ltr8.tson.parser.config`
  package** — `TsonCompiledRegistry`, `SchemaMetaNameBinder`, `ValueReaderFactoryResolver` (plus the
  new `TsonStandardLibrary`, see "Front door" above). These read as "how a caller configures a
  working environment," not compiler mechanics, and none of the three touched a package-private
  compiler class, so the move was clean. `ValueReaderFactoryRegistry` deliberately stayed in
  `compiler` despite being asked about in the same review — it's the literal wiring table binding
  constructor names to concrete reader implementations (`AtomValueReader`, `BooleanReader`,
  `ChoiceReader`, `VariantBindReader`, `VariantSchemaReader`, `VoidReader`, `ErrorReader`), all
  deliberately package-private; moving it out would force every one of those public just so it could
  keep referencing them — a real, unwanted expansion of the public surface, not a free move.
- [ ] Distinguish "has constructor vocabulary, eligible to govern (a valid `!!meta` target)" from
  "ordinary consumer schema" at the type level. Right now `TsonCompiledMetaSchema` wraps *any*
  compiled schema, constructors or not — nothing stopped a zero-constructor consumer schema (like
  `TinySchemaImportsCoreTn1Test`'s own tiny schema) from being handed to another `register(...)`
  call as a `governingMeta`, exactly the way meta.tn1's own compiled form is.
- [ ] Add the missing other half of `TsonSchemaLinker`'s existing "constructor eligibility" check.
  That check already restricts *where* `constructor: true` can be declared (only a
  meta-kernel-governed schema, per §2.2.2) — the missing half is restricting *where a schema can be
  used as a `!!meta` target* the same way, so an ordinary consumer schema can't accidentally govern
  another one.

## Content addressing / hash-pinned references

A real, spec-mandated (MUST-level) integrity mechanism — [TSON-DATA] §2.2.1, [TSON-SCHEMA] §10.2 —
that's currently entirely unimplemented, not just incomplete: `CanonicalIdentity.of` already strips
a URI's query component to compute canonical identity, but the dropped query is never validated or
checked against anything. Worth its own section since it's a coherent, well-specified area, not a
handful of loose gaps.

- [ ] **SHA256 verification of fetched/imported schema content** against a `?sha256=<hash>` query
  parameter declared on a `!!id`/`!!meta`/`!!import`/`!!schema` URI — the spec's own words: "A
  consumer holding a hashed reference MUST verify the content against the declared hash before use
  and MUST NOT silently use mismatched content: a mismatch is an error, never a fallback."
  `CanonicalIdentity.of` currently just discards the query outright — no rejection of an
  unrecognized query-parameter name either (the spec requires this: a query MUST consist solely of
  recognized hash-algorithm parameters, or it's an error, "never silently retained").
- [ ] **Correct content-hash computation**, per the spec's own precise rule: the hash input is every
  byte after the `!!id` line's own line terminator — the id line itself (up to and including its
  terminator) is excluded, specifically so a document can embed its own hash without circularity.
  Document bytes MUST be UTF-8. Needed both to *verify* a fetched document and to *compute* the hash
  for a schema being published/pinned by this implementation.
- [ ] **Per-identity hash aggregation within one resolution's reference closure** ([TSON-SCHEMA]
  §10.2): an identity with no declared digest anywhere in the closure resolves unverified; a single
  declared digest is verified once, and every reference to that identity — pinned or plain alike —
  then resolves to the verified content; two *different* declared digests for one identity is a
  resolver error (a real conflict — at most one can describe the real bytes — never a "pick one").
- [ ] **Caching semantics**: a verified entry, once cached under its canonical identity, is
  immutable; a *failed* verification must not overwrite or poison the canonical-identity cache entry
  (the spec allows a separate negative cache keyed on the full reference string instead).
- [ ] **Verifiable pins to the pre-loaded bootstrap schemas** — meta-kernel.tn1/meta.tn1/core.tn1
  are pre-loaded as in-memory structures (`MetaKernelBootstrapResolver`, `BundledSchemaSource`), but
  the spec still requires a pinned reference to one of them to be verifiable: an implementation MUST
  hold each pre-loaded schema's own published digest (or its canonical document bytes) to check a
  pin against. One identity can never carry a pin at all — meta-kernel's own self-referencing
  `!!meta`, whose hash input would have to contain the pin itself.

## Resolution & linking generality

Every real schema resolved so far (meta-kernel, meta.tn1, core.tn1, and hand-built test fixtures)
happens to fit a narrow shape this pipeline already handles — declared in dependency order, with
callers hand-sequencing registration themselves. These items are what's missing for the *general*,
spec-required case, found by re-auditing Part 2 against the current source rather than CLAUDE.md's
own prose (which had gone stale on at least one of them):

- [ ] **General forward-reference resolution within a schema** ([TSON-SCHEMA] §3.4.1's Pass 1/Pass
  2) — `DefinitionResolver`/`TsonSchemaResolver.resolveSchema` are single-pass, strict source order;
  a declaration referencing another declared *later* in the same schema fails to resolve, outside
  `MetaKernelBootstrapResolver`'s own hand-built two-pass ordering, which is specific to
  meta-kernel's own bootstrap and not something any other schema can rely on. `DefinitionResolver`'s
  own Javadoc already says as much ("real forward references... need the full namespace population
  of §3.3.2/§3.4.1's Pass 1, not implemented here") but this had never been carried into a tracked
  item.
- [ ] **Automatic reference-closure resolution and import-cycle detection** ([TSON-DATA] §2.2.3,
  [TSON-SCHEMA] §3.4.1) — no code collects a schema's transitive `!!meta`/`!!import` closure,
  topologically orders it, and resolves it dependencies-first; every caller (including this
  session's own `TinySchemaImportsCoreTn1Test`) has to already know and hand-sequence the correct
  registration order itself. A real import cycle is only caught incidentally today, as an opaque
  "not registered" error from `TsonSchemaLinker.mergeImports` — never with the spec's own required
  "import cycle" diagnostic naming the actual cycle path. Distinct from the "single load-stdlib
  entry point" item above, which is scoped to just the three bundled schemas, not a general
  algorithm.
- [ ] **Choice disjointness derivation and untagged reading** ([TSON-SCHEMA] §5.4, §8.1) —
  `TypeDefinition.disjoint` is a real `Optional<Boolean>` field in the model, but every
  `DefinitionResolver` construction site passes `Optional.empty()` for it — nothing computes it, at
  all. `ChoiceReader` correspondingly always requires an explicit `!variant` type-ref tag to
  disambiguate a union member, with no structural-recovery path for a choice a schema author has
  declared (or that could be proven) disjoint. The `@disjoint` author-assertion annotation
  (proved/refuted/unprovable/absent) has nothing to check against until this lands.
- [ ] **Resolved-form ingest** ([TSON-SCHEMA] §8.1/§10.1) — bringing an already-resolved
  `!type_definition` document into the library (not source text), with its own integrity checks:
  `subtypes`/`disjoint` recomputed and verified, the closed-entry parameter-free rule reverified, an
  instantiation entry checked against its own `source` by recomputation, a construction's binding
  record checked for parameter-slot agreement with its `source` application. Entirely unimplemented
  — "ingest" doesn't appear anywhere in the codebase. Lower priority than the three items above:
  the spec marks this path explicitly **optional** ("MAY implement ingest"), not a MUST.

## Remaining built-in types

- [ ] `cidr4`/`cidr6`/`email`/`mac`/`unknown` — no compiled-parser factory yet
  (`ValueReaderFactoryRegistry` registers these constructors to `ErrorReader`). Pinned down exactly
  by `CoreSchemaImportTest.exactlyTheFiveUndocumentedAtomConstructorsCompileToErrorReaders`.
- [ ] `uri_type`/`regex_type` — don't bind correctly in object-binding mode. Their RFC-citation
  field is nested inside `specification: AtomSpecification` rather than flat, so it never receives
  a schema-composed default the way `email_type`'s own flat `spec` field does.
- [ ] `extern` ([TSON-SCHEMA] §7.8) — materially bigger than the four items above, which just need
  an ordinary atom parser. `Extern` (`schema.meta`) is a record-only placeholder with no
  parsing/validation behavior at all (its own Javadoc says so explicitly: "not to add real
  cross-schema reference resolution"); the real mechanism — a value at an extern-matched position
  carrying its own scoped `!!schema` plus a mandatory `!type` tag, switching schema scope
  mid-document — doesn't exist anywhere in the reader stack.

## Remaining Part 2 resolution gaps

- [ ] Subtraction.
- [ ] Elided field types outside a tightening entry.
- [ ] Restating a field group in a refinement body.
- [ ] Generic type-refs beyond a bare two-argument `map<K, V>` application or a refinement source.
- [ ] `= _` (absent) field modifier, and any `~`/`=` modifier on an already-`OPTIONAL` field —
  `DefinitionResolver.resolveField` rejects both today.
- [ ] Closed-entry parameter-free check (§5.10) — nothing validates that an entry with an empty
  `parameters` list truly contains no parameter references (`value_param` members, or a reference
  name resolving to a parameter) anywhere in its body, at any depth. Distinct from `value_param`
  *substitution* (tracked in `STRUCTURED-OUTPUT.md`) — this is a rejection rule for a malformed
  "closed" entry, not the substitution mechanism itself.

(All already named in `DefinitionResolver`'s own Javadoc and `CLAUDE.md`; carried here so
everything outstanding is tracked in one place.)

## Write side

- [ ] No schema-aware (Class 2) writer exists at all. Only `TsonMapperWriter` does (Part-1-only,
  generic, with documented lossy spots: integer width, tuple-ness, `@Annotated`-captured wire-format
  annotations). A validating writer symmetric to the compiled reader stack (`TsonSchemaCompiler`/
  `TsonValueReader`) is a whole missing half of the pipeline if round-tripping or producing
  conformant documents is ever a goal.
- [ ] A JSON writer (TSON data → valid JSON text) — the write-direction companion to
  `STRUCTURED-OUTPUT.md`'s "JSON compatibility" section, tracked here alongside the general writer
  since it's the same underlying gap (no schema-aware writer exists at all yet).

## Conformance test suite

- [ ] Build out `ltr8-io-tson-test-suite` well beyond its current 38 vectors. Those cover Part 1
  (lexer/parser) only — Part 2 (resolution, linking, compilation) has no conformance-suite coverage
  at all yet, only this repo's own unit/integration tests.

## Documentation

- [ ] User-facing documentation on how to use the library — today only `CLAUDE.md`'s own dense,
  session-oriented internal narrative exists.
- [ ] AI skills for using the library.
- [ ] `@doc` annotations aren't carried through resolution into `TypeDefinition` at all right now —
  worth preserving if user docs/tooling will ever want to generate documentation from a schema,
  rather than bolting it on later and revisiting every resolution path again.

## Miscellaneous

- [ ] Thread-safety — currently only `synchronized` on `TsonSchemaRegistry`/`TsonCompiledRegistry`'s
  own `register`/`get`; everything else is an open design question.
- [ ] General resolver-layer structural rules as reusable primitives, rather than binding-time-only
  behavior — empty-brace resolution, the absent-vs-missing distinction.
- [ ] Annotation access on individual fields, array/tuple elements, and map keys/values — only a
  whole bound record's own annotations are reachable today, not its children's.
- [ ] Confusable-character and bidi-formatting-character warnings (§9.4-adjacent security
  hardening) — the sibling gap to the numeric-literal length limit tracked in
  `STRUCTURED-OUTPUT.md`'s Tier 1 section; neither is enforced anywhere yet.
