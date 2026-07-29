# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

---

## Front door / ergonomics

- [ ] `--all-errors`/`-a`, once the real `Diagnostic` API lands — collect every validation failure
  in a file instead of stopping at the first.
- [ ] No `!!schema`-header auto-selection on the data side — given a data document, there's no
  "find the right compiled reader yourself" entry point; a caller always has to already know what
  schema position it's reading against.
- [ ] A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy — only
  `BundledSchemaSource` and `TsonSchemaSource.registeredOnly()` exist today.

## Layer boundaries / schema registry

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

## Atom-refinement constraint validation

- [ ] **Atom-refinement merging never checks that a refinement actually narrows its source** —
  `DefinitionResolver.mergeWithSource` (chained atom refinement, `!I ^ { values }`) re-serializes
  `I`'s own already-bound constraint object to wire form via `TsonMapperWriter`, then merges it with
  the new refinement's own `values` field by field: `merged.put(field.name(), field)` — a plain map
  override, explicit values simply win, with **no check that the new value is actually a valid
  narrowing** of what it replaces. Concretely: `!uint8 ^ { min: -10 max: 300 }` is not rejected, even
  though `uint8`'s own real range is 0..255 — the "refinement" *widens* rather than narrows, directly
  contradicting §5.7's whole premise (refinement tightens, it never loosens), and nothing catches it
  today. The right fix, per the user's own direction: each constraint-vocabulary class (`IntegerType`,
  `TextType`, `DecimalType`, `FloatType`, ...) should own a method like `constraintsCheck(A, B)`
  returning whether `B` is a valid narrowing of `A` — the type itself is the only thing that actually
  knows what "more constrained" means for its own fields (an integer's `min`/`max`, a text's
  `min_length`/`max_length`/`pattern`, and so on) — called during merge instead of the current blind,
  generic override.
- [ ] **Related cleanup, once the above lands**: `DefinitionResolver`'s own dependency on
  `TsonMapperWriter` (`private final TsonMapperWriter writer`, used only for this merge's own
  re-serialization step) should go away — a real narrowing check wouldn't need to round-trip through
  the generic mapper at all. This is also *why* `TsonMapperReader`/`TsonMapperWriter` still live in
  `tson-compiler.mapper` rather than the new `tson` front-door module (see "Front door" above) — moving
  them today would create a cycle, since `tson-compiler`'s own resolution engine genuinely depends on
  them. Once this dependency is gone, revisit moving `TsonMapperReader`/`TsonMapperWriter` into `tson`
  (or their own module) — noted directly on `Tson`'s own class Javadoc too, so it isn't lost.

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
- [ ] **`!choice { variants: [...] }` construction (§5.4) fails to resolve — a genuine bug, not just
  an unimplemented case, confirmed empirically (2026-07-29) while designing the sibling
  `ltr8-io-tson-test-suite` repo's own sidecar-format schemas (see "Conformance test suite" below):
  a tagged union would have modeled `core_value`/`base_value`/each layer's own `outcome`-discriminated
  sidecar shape far more precisely than the "one record, kind enum, every other field optional"
  design those schemas actually ended up using, so this was worth pinning down exactly rather than
  left as a guess. `DefinitionResolver.bindAtomInstance` throws `UnsupportedOperationException`
  wrapping a `NullPointerException` ("Cannot invoke `Collection.isEmpty()` because `coll` is null"),
  from `TypeRef`'s own constructor via `List.copyOf`, reached through
  `RecordBindReader.read`/`ArrayBindReader.read` while binding `choice`'s own `variants: [type_ref]`
  field — each variant is written as a bare, unadorned type name (`!choice { variants: [text
  integer] }`), and something in that array-of-bare-type-ref binding path isn't defaulting
  `TypeRef.arguments` before construction the way the equivalent single-field case already does
  elsewhere. Narrow, likely a short fix once someone's in that code path — but real, and blocks any
  schema (this project's own or a consumer's) from declaring a genuine tagged union at all today, not
  just from getting `disjoint` computed for one (see "Choice disjointness derivation" under
  "Resolution & linking generality" above, which assumes a choice already resolved).

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

## Streaming

- [ ] `TsonDataStream` (Tier 2, `tson-compiler/.../stream/`) is driven off `Lexer.nextToken()`, one
  token at a time, but `Lexer` itself is constructed over a complete in-memory `String` — so even
  the streaming path is still O(document size) at the character-buffer level. A `Reader`-based
  lexer (pulling characters incrementally rather than indexing into a fully-loaded `String`) is the
  next natural tier down if truly-large (multi-GB) documents are ever a real requirement; today's
  split only bounds the *parsed-representation* memory (record/map/array nesting depth), not the
  raw source text itself.
- [ ] No consumer bypasses Tier 3 yet to actually realize the low-memory benefit end to end --
  `TsonDataParser` (Tier 3) still materializes a full `Document` AST from `TsonDataStream`'s events,
  by design (it's a tree builder, not a streaming consumer). A real streaming use -- e.g. a
  schema-validating pass or a direct-to-Java-object binder that consumes `TsonEvent`s without ever
  building a `Document` -- is the natural next thing to build on top of `TsonDataStream` directly,
  and would be the first real-world proof that the flat, bounded-memory event model is worth having
  independent of `TsonDataParser`.

## Conformance test suite

- [ ] Build out `ltr8-io-tson-test-suite` well beyond its current 110 vectors (grown from the 38 this
  note originally cited — it's picked up a fourth `vocabulary` bucket alongside `lexer`/`parser`/
  `resolver` since). Still Part 1 (lexer/parser/§5 vocabulary) only — Part 2 (resolution, linking,
  compilation) has no conformance-suite coverage at all yet, only this repo's own unit/integration
  tests.
- [ ] **Retrofit the ~110 existing sidecars with a real `!!schema` directive** pointing at the new
  per-layer schemas above, and fix whatever real shape mismatches that validation surfaces —
  explicitly *not* done in the same pass that wrote the schemas (see the sibling README's own note
  on this), so today the schemas are resolver-verified documentation, not live validation of the
  actual fixtures. **Worth doing sooner rather than later, per the user's own explicit reasoning**
  (2026-07-29): only ~110 files exist today, cheap to migrate; that stops being true once the suite
  grows into the thousands, so if the wire format itself is going to change (see the next item), now
  is the cheap moment to do it, not after the corpus is large.
- [ ] **A precise, `outcome`/`kind`-correlated sidecar shape needs a real design decision first,
  not just a retrofit** — the flat, all-optional modeling these schemas use today (`core_value`'s
  `kind` plus every variant's fields all `OPTIONAL` side by side) doesn't enforce that `kind: token`
  implies `form`/`text` present and `fields`/`entries`/`elements` absent, and so on. Three ways to
  actually get that enforcement, discussed with the user 2026-07-29, in order of how much the real
  wire format would have to change:
  - `!choice { variants: [...] }` — doesn't resolve at all today (see the new item above); even once
    fixed, a schema built on it typically still needs a `!variant` tag or a discriminator convention
    to disambiguate on read (`ChoiceReader`'s own "no structural-recovery path" gap, "Resolution &
    linking generality" above) — not free once the construction bug itself is fixed.
  - **Field groups** (§5.11) — confirmed empirically to work today, including with a record-typed
    member (`core_value => { ( token: core_value_token | record: core_value_record | ... ) }`,
    resolves cleanly, each member individually `OPTIONAL`, mutual exclusivity captured in the
    entry's own `groups`). The real cost: the group member's own *name* is what discriminates, so
    each variant's fields would have to move under a keyed sub-object (`{ token: { form: ...
    text: ... } }`) instead of today's flat `{ kind: token form: ... text: ... }` — a genuine wire
    format break for every existing sidecar, not just a schema change.
  - **Dependent typing at the meta-schema level — the user's own proposed direction, already on
    their own "not yet" list before this discussion, and the one that best fits this specific case**:
    `kind` stays exactly as it is today (a plain enum field, flat, no wire change at all) — the new
    mechanism is a meta-schema-level association from an enum's own *member* to a type, so a
    companion field's actual type is resolved from whichever member `kind` currently holds, rather
    than from the field's own static declared type or its own name. Lets a schema designer decouple
    "which type validates this value" from "what this field happens to be named" — relevant well
    beyond this one schema, including for JSON-facing discriminated unions (see
    `STRUCTURED-OUTPUT.md`'s own "The real sharp edge: untagged unions" item, extended with this
    same mechanism). This is real, unstarted design work at the meta-kernel/meta.tn vocabulary level,
    not a `DefinitionResolver` bug fix — tracked here and in `STRUCTURED-OUTPUT.md` so it isn't lost,
    not scoped or planned yet.
- [ ] **`vocabulary-sidecar.tn`'s own `value` field is a known simplification** — typed as plain
  optional `text`, which doesn't capture the two atom families (`complex`, `duration`) that actually
  write `value` as a small nested record on the wire. Revisit once `!choice` construction resolves
  (a precise per-family shape would need a discriminator on `type-ref` itself — an open ~30-name
  vocabulary, not a small closed enum like `outcome`/`kind`, so even with `!choice` working this one
  isn't a completely free win the way the `outcome`-discriminated shapes are).

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


## Done

- [x] **A new `tson` module — the developer front door, sitting on top of `tson-compiler` (named
  `tson-parser` at the time) the way Retrofit sits on OkHttp or Apache HttpClient5 sits on HttpCore5**
  (superseded an earlier `tson-parser` → `tson-core` rename idea — simpler to add a small module on
  top than rename the engine underneath it; `tson-parser` was renamed to `tson-compiler` later
  anyway, for a different, better reason — see the "renamed to `tson-compiler`" item below). Holds
  `Tson` (a real, immutable, instance-based object — `resolve`/
  `compile`/`mapperReader`/`mapperWriter`/`dataBindContext`, plus the underlying registries/loader
  for a caller who needs to reach past the front door) and `TsonConfig` (`Tson`'s own builder,
  reached via `Tson.builder()`; moved here from `io.ltr8.tson.compiler.config`, since it's purely a
  caller-facing convenience with zero internal consumers inside `tson-compiler` itself — confirmed
  before moving it, the same way the three classes in "Layer boundaries" below were checked).
  `tson-compiler`/`tson-schema`/`tson-bind` are `api` (not `implementation`) dependencies, so a caller
  depending on just `tson` still sees the real classes underneath directly.
  **`TsonMapperReader`/`TsonMapperWriter` deliberately did *not* move here** — `tson-compiler`'s own
  `DefinitionResolver` has a real, current dependency on `TsonMapperWriter` (atom-refinement
  merging), so moving them to a module that depends *on* `tson-compiler` would recreate the exact
  module cycle that caused `tson-mapper` to be merged *into* `tson-compiler` in the first place,
  historically. See "Atom-refinement constraint validation" below for the plan to remove that
  dependency and revisit the move once it's gone — noted directly on `Tson`'s own class Javadoc too.
  `tson-cli` now depends on `tson` as well (alongside its existing direct deps, still needed for DOM
  mode/custom binders) and was re-verified end to end against it.
- [x] **A single "load the standard library" entry point — landed as `Tson`/`TsonConfig`**
  (`io.ltr8.tson`, the new front-door module above). `Tson.builder().build()` bootstraps meta-kernel
  → meta.tn1 → core.tn1 in one call and returns a ready `Tson`; its `.resolve(schemaText)`/
  `.compile(linked, mode)`/`.compile(schemaText, mode)` replace the hand-assembled
  `TsonSchemaRegistry`/`TsonCompiledRegistry`/`DefaultTsonCompiledSchemaLoader` wiring
  `TinySchemaImportsCoreTn1Test`/`CoreSchemaImportTest` (and `tson-cli`'s own now-deleted internal
  `StandardLibrary` helper) used to hand-roll. `TsonTest` re-proves `TinySchemaImportsCoreTn1Test`'s
  exact scenario through this front door instead. Surfaced a real, non-obvious pipeline constraint
  along the way, now documented on `Tson`'s own class itself: resolving an `Instance`/
  `AtomRefinement` declaration (`DefinitionResolver.bindAtomInstance`) always needs a real,
  object-binding-mode governing-meta reader, regardless of what mode the *final* compiled schema
  wants — DOM-mode resolution of the standard library itself fails outright (a `Map` can't cast to
  `schema.meta.Top`). Only *compiling* an already-resolved, already-linked schema
  (`TsonCompiledMetaSchema.bootstrap`) is free to pick a different mode, since it never re-resolves
  anything — hence `resolve` takes no mode parameter at all, only `compile` does. `TsonConfig` itself
  was split back out of an earlier single class that mixed builder config with these operations,
  once `mapperReader()`/`mapperWriter()` needed to be non-static instance methods bound to a real,
  configurable `DataBindContext` — see "Front door module" in `CLAUDE.md` for the full reasoning.
- [x] **A fluent/builder API — landed as `TsonConfig`** above; `tson-cli`'s
  `ValidateCommand`/`CompileCommand`/`DiagnosticsSchema` were refactored to use `Tson.builder().build()`
  directly in place of their own hand-rolled wiring, so the CLI itself is now real, working validation
  that the builder is usable, not just a design on paper. Still narrow by design (no pluggable
  `TsonSchemaSource`, no config beyond `dataBindContext` and the standard library itself) — `TsonConfig`
  is exactly where a future pluggable source belongs, per its own Javadoc.
- [x] **A CLI, ajv-cli-style — v1 landed** (new `tson-cli` module: `TsonCli`/`ValidateCommand`/
  `CompileCommand`/`OutputFormat`/`DiagnosticsSchema`, now built on `Tson`/`TsonConfig` above
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
- [x] **Sibling repo's own vector naming migrated from `.tn1`/`.tson` to `.tn`/`-expected.tn`**
  (2026-07-29, same rename as the "Documentation" section's pre-release file-extension item above) —
  all 110 subject files renamed `<slug>.tn1` → `<slug>.tn`; all 110 sidecars renamed `<slug>.tson` →
  `<slug>-expected.tn` (own `!!id` updated to match); `scripts/check_vectors.py`'s pairing logic
  rewritten for the new same-extension, suffix-distinguished scheme (was extension-distinguished);
  `README.md`'s layout diagram and prose updated throughout. `-expected` chosen over the user's own
  initial `-check` suggestion — clearer to an unfamiliar reader (any implementation in any language is
  meant to be able to pick this suite up) than this project's own internal "sidecar" jargon would be.
  `ConformanceSuiteTest` (this repo) updated to match and re-verified against the real, renamed sibling
  checkout: 110/110 vectors still pass, not just recompiles.
- [x] **Dynamic `!!meta`/`!!import` splicing, and the sibling repo's sidecar format described as real
  TSON schemas instead of ad hoc BNF** (2026-07-29) — a sidecar can now declare `meta`/`import` by
  short, unversioned name (`meta: "meta.tn"`, `import: ["core.tn"]`); `ConformanceSuiteTest`'s new
  `resolvedRaw` splices the real, current bundled identity (off `TsonBundledSchemas`, so a spec
  revision bump touches one class, not every vector) into the subject's own header before parsing,
  right after `!!id` per the schema grammar's own fixed directive order — a no-op today (no vector
  uses it yet), plumbing for whenever schema-governed vectors actually get added. Separately,
  `ltr8-io-tson-test-suite/schemas/{lexer,parser,resolver,vocabulary}-sidecar.tn` now formally
  describe each layer's sidecar shape as real, resolver-verified schemas (`SidecarSchemasTest`, this
  repo, resolves all four against the live bundled chain every run) — replacing the README's own
  `document = { ... } / { ... }` BNF-style blocks. Modeled as one record per layer with an `outcome`
  discriminator and every other field `OPTIONAL`, not a tagged union, since `!choice` construction
  doesn't resolve yet (see the new item under "Remaining Part 2 resolution gaps" above) — the real,
  named cost of that gap: nothing today stops a `valid` sidecar from also carrying `category`, or an
  `error` one from carrying `document`. Field names match the real sidecar spelling exactly, hyphens
  included (`base-value`, `type-ref`, `schema-ref`, `integer-part`, `fraction-digits`) — confirmed
  empirically that a hyphenated schema field name parses and resolves fine, not assumed.
- [x] **Pre-release file-extension convention decided and applied: `.tn`, unversioned, for as long
  as the spec stays a 2026-revision-series draft** (see `SPEC-FEEDBACK.md` #20 for the full finding
  this decision responds to). Landed 2026-07-29, on the user's own explicit direction, once it was
  confirmed the live spec site itself would be updated to match — the earlier assumption that the
  spec's own bundled `meta-kernel.tn1`/`meta.tn1`/`core.tn1` couldn't be renamed (a fixed, external
  identity) turned out not to hold, since the spec is still genuinely editable at this stage, not
  something this project has to treat as immutable. Renamed throughout: `spec/m/*.tn1` → `*.tn` (and
  every `!!id`/`!!meta`/`!!import` directive value inside them); `tson-compiler`'s `processResources`
  copy list; `BundledSchemaSource`'s `META_KERNEL_ID`/`META_ID`/`CORE_ID` constants (the latter two
  also renamed from `META_TN1_ID`/`CORE_TN1_ID`, dropping the now-stale "TN1" from the identifier
  itself) and its `RESOURCES` map; `TsonSchemaLinker.META_KERNEL_ID`; `tson-cli`'s own
  `diagnostics.tn1` → `diagnostics.tn`; every test fixture whose `!!meta`/`!!import` header must
  actually resolve against the real bundled schemas (found by compiling, then running the full suite,
  and fixing exactly what broke, rather than a blind grep-and-replace); and the "Project-owned schema
  `!!id` convention" section above. Deliberately *not* swept: fictional/placeholder test URLs
  (`https://example.test/...`) whose extension is incidental to what they test, and informal Javadoc
  prose mentions scattered through `schema.meta.*` classes and `CLAUDE.md`'s own historical
  narrative — cosmetic staleness, not functional, and out of scope for this pass. The sibling
  `ltr8-io-tson-test-suite` repo's own `.tn1`/`.tson` vector files are a separate, not-yet-done step
  (that repo also needs a naming decision for the sidecar half — see the "Conformance test suite"
  section below).
- [x] **`BundledSchemaSource` deleted outright, the same day, a follow-up** — once `TsonBundledSchemas`
  already held the one canonical copy of all three identities, the user pointed out
  `BundledSchemaSource` was "exactly the same thing sitting in config of the wrong package": its
  `fetch` method and the bundled `.tn` resource files themselves (previously copied into
  `tson-compiler`'s own classpath by its `build.gradle.kts`) moved into `TsonBundledSchemas`
  (`tson-schema`) too, and `tson-compiler`'s own `BundledSchemaSource` class and its
  `processResources` copy step were deleted, not just relocated again. `fetch` deliberately doesn't
  implement `tson-compiler`'s own `TsonSchemaSource` (a dependency `tson-schema` doesn't have), but its
  shape already matches that interface's single method exactly, so every `tson-compiler`/`tson` call
  site that needs a real `TsonSchemaSource` now passes the method reference `TsonBundledSchemas::fetch`
  directly — no adapter class needed on either side. Verified the resources genuinely moved, not just
  the Java code: `unzip -l` on the built jars confirms `meta-kernel.tn`/`meta.tn`/`core.tn` are present
  in `tson-schema`'s own jar and absent from `tson-compiler`'s. Full `./gradlew clean build` green, plus
  the installed CLI binary still runs a real schema end to end.
- [x] **API-surface pass — `compiler`'s eight `*DomReader`/`*BindReader` classes
  (`Array`/`Map`/`Record`/`Tuple` × Dom/Bind) and `ValueReaderResolver` narrowed to package-private.**
  Checked every public class/method in `compiler`/`resolver`/`config` against real cross-package (same
  module) and cross-module usage before touching anything, not just by inspection — these nine were
  the only ones referenced *exclusively* from within `compiler`'s own package (main + same-package
  tests), confirming they'd been left `public` purely as a leftover of an earlier refactor stage, not
  because any real caller (the `tson` front door, `tson-cli`, or a cross-package test) ever names them
  directly. `TsonValueReader`'s own Javadoc (`tson-compiler`'s root package) referenced `RecordDomReader`
  via `{@link}` — switched to `{@code}` (plain text, no cross-package accessibility requirement) and
  dropped the now-invalid `import`. Everything else already checked out: `TsonCompiledMetaSchema`/
  `TsonCompiledSchema`/`TsonSchemaCompiler`/`ValueReaderFactory`/`ValueReaderFactoryRegistry` (real,
  used-elsewhere API), and every public class in `resolver`/`config`, all have at least one genuine
  cross-package or cross-module caller (`Tson`/`TsonConfig`, `tson-cli`'s `DiagnosticsSchema`, or
  another `tson-compiler` package) — `MetaKernelBootstrapResolver.getMetaKernelSchema()` in particular
  stays public because `compiler`-package tests call it directly, not just `resolver`-package ones.
  Full `./gradlew clean build` stayed green throughout (no cross-package caller was missed).
- [x] **`module-info.java` added to `tson-schema`/`tson-compiler`/`tson`/`tson-cli`** (`tson-bind`/
  `tson-annotation` already had one) — done right after the API-surface pass above specifically
  because it's the input a real export list needs (exports are package-, not class-grained, so
  exporting a not-yet-trimmed package would have exported mid-refactor leftovers too). Full detail in
  `CLAUDE.md`'s new "Module system (JPMS)" section — highlights: `tson-schema.registry`'s own
  "internal-by-convention" split is now genuinely JPMS-enforced (verified with a real scratch-file
  compile failure, not just reasoned about); `tson-schema/build.gradle.kts` needed `implementation`
  promoted to `api` for its own `tson-annotation` dependency purely for module-path resolution (a real
  Gradle/JPMS friction point, not a design choice); `.atom` stayed unexported (no real external
  caller). Verified both at compile time (`./gradlew clean build`, every module) and at runtime (the
  installed CLI binary still runs end to end on its classpath-based `application`-plugin distribution).
- [x] **`.lexer`/`.base` locked down too, same day, a follow-up pass** — both were exported for
  exactly one accidental reason each, both fixed at the source rather than left exported: `Position`
  moved out of `.lexer` into the root package (it leaks through `TsonParseException`/
  `TsonUnsupportedDocumentException`'s own public constructors, caught by a genuine
  `javac -Xlint:exports` warning when `.lexer` was first left unexported — `Lexer`/`Token`/
  `TokenType`/`LexException` themselves have no real external caller), and `TsonAtomContext` moved
  from `.base` to `config` (it was `.base`'s only genuine external caller; the rest is §4
  base-type-resolution machinery). Both re-verified with a real scratch-file compile failure before
  being deleted, same technique as the `.registry` check above.
- [x] **A facade over `tson-compiler`'s own pipeline stages, in its own root package** — done, though
  not quite via the originally-speculated shape (moving the lexer/structural parser into their own
  `io.ltr8.tson.compiler.parser` sub-package never happened; `.ast`/`.lexer` stayed where they were).
  What actually landed instead: the pipeline-stage classes a caller reasons about directly now sit in
  the root `io.ltr8.tson.compiler` package itself — `TsonDataParser`/`TsonSchemaParser` (parse),
  `TsonSchemaResolver` (resolve, a thin public wrapper over `resolver.SchemaResolver` now that
  `.resolver` itself is unexported), `TsonSchemaCompiler`/`TsonCompiledSchema`/`TsonCompiledMetaSchema`
  (compile), `TsonCompiledSchemaLoader`/`TsonSchemaSource` (the fetch/bootstrap hook), and
  `TsonValueReader` (read) — so a caller reasoning about `tson-compiler` as its own library sees every
  stage without needing to know the package layout underneath, without a separate facade *class*
  being built on top. The two open design questions from the original plan resolved differently than
  guessed: `.reader` (the compiled-reader stage) did *not* fold into root — it stays its own
  sub-package, holding only reader implementations, once `TsonSchemaCompiler`/`TsonCompiledSchema`/
  `TsonCompiledMetaSchema` moved out of it; `.mapper` remains unresolved, still blocked on the same
  "Atom-refinement constraint validation" item as before.
  Remaining, lower-priority cleanup identified along the way, not blocking and not yet started —
  pick up as other work happens to touch this area: removing the `config` package entirely (folding
  `SchemaMetaNameBinder`/`TsonAtomContext`/`TsonCompiledRegistry`/`ValueReaderFactoryResolver`
  somewhere that isn't its own separate "configuration" layer); and further polish on the facade
  interfaces themselves (`TsonCompiledSchemaLoader`/`TsonSchemaSource`/`TsonValueReaderResolver`) once
  it's clearer what a caller actually needs from them in practice.
- [x] **Configuration/wiring classes moved out of `compiler` into a new `io.ltr8.tson.compiler.config`
  package** — `TsonCompiledRegistry`, `SchemaMetaNameBinder`, `ValueReaderFactoryResolver`. These
  read as "how a caller configures a working environment," not compiler mechanics, and none of the
  three touched a package-private compiler class, so the move was clean. (An earlier version of
  `Tson`'s own builder, built the same day, briefly lived here too before moving one step further out
  to the new `tson` front-door module itself, see "Front door" above — a config-package class it was,
  at the time.)
  `ValueReaderFactoryRegistry` deliberately stayed in
  `compiler` despite being asked about in the same review — it's the literal wiring table binding
  constructor names to concrete reader implementations (`AtomValueReader`, `BooleanReader`,
  `ChoiceReader`, `VariantBindReader`, `VariantSchemaReader`, `VoidReader`, `ErrorReader`), all
  deliberately package-private; moving it out would force every one of those public just so it could
  keep referencing them — a real, unwanted expansion of the public surface, not a free move.
- [x] **`BundledSchemaSource` moved into `config` too, from `resolver`** — same "how a caller
  configures a working environment" reasoning as above, once its only real in-`tson-compiler` consumer
  (`MetaKernelBootstrapResolver.getMetaKernelSchema`) was confirmed to be its sole caller here.
  `MetaKernelBootstrapResolver`/`DefaultTsonCompiledSchemaLoader` (both stay in `resolver`) now reach
  into `config` for it directly, the same named layering exception already documented for
  `TsonCompiledSchemaLoader`. Deliberately did *not* also change `getMetaKernelSchema()` to accept an
  injected `TsonSchemaSource` — that would reopen exactly what removing this class's own earlier
  `parse(String source)` overload closed off (a caller supplying text that isn't genuinely meta-kernel
  under the well-known meta-kernel identity); package placement changed, the zero-argument lock-down
  didn't.
- [x] **`META_KERNEL_ID`/`META_ID`/`CORE_ID` consolidated into a new `TsonBundledSchemas` class in
  `tson-schema`** (2026-07-29, on the user's own explicit direction) — previously split across two
  homes: `META_KERNEL_ID` lived on `tson-schema`'s own `TsonSchemaLinker` (needed there for
  `isMetaKernelGoverned`), with `BundledSchemaSource` (`tson-compiler`) defining its own copy in terms
  of that one; `META_ID`/`CORE_ID` had no canonical source at all, only `BundledSchemaSource`'s own
  literals. `tson-schema` is the only module both a `tson-compiler`-side consumer and `tson-schema`'s
  own `TsonSchemaLinker` can share a source with (no dependency back on `tson-compiler`), so a shared
  home had to live there regardless. A fresh, narrow class rather than adding fields to
  `TsonSchemaLinker` (a verb in this project's own pipeline vocabulary, not a constants holder) or
  `BundledSchemaSource` (fetch capability, not identity — the same split this project draws
  everywhere else, e.g. `TsonLinkedSchema`/`TsonSchemaLinker`). Named `TsonBundledSchemas`, not
  `TsonMetaSchemas` — spec §9 ("The Meta Layer") is explicit only *two* of the three (meta-kernel,
  meta) make up "the meta layer"; core is a separate type library, so a "meta schemas" name would
  misname it. Also considered and rejected: `TsonIdentity` (collides conceptually with the existing,
  unrelated `CanonicalIdentity`), `TsonVersion` (doesn't convey "document identities" at all),
  `TsonPrelude` (evocative but borrows outside jargon and is slightly inaccurate — these three are
  pre-loaded by the library, not auto-imported into a consumer's own namespace). Every consumer (13
  files across `tson-compiler`/`tson`, main and test) updated and re-verified: full `./gradlew clean
  build` green, plus the installed CLI binary still runs a real schema end to end.
