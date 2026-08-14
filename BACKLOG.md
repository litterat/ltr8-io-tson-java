# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

---

## Tree model (`TsonNode`)

The `TsonNode` tree — an immutable, queryable, structure-preserving document model in its own pure-leaf
`tson-tree` module (`io.ltr8.tson.tree`) — is **built**: the node model + query API, both producers (the
schema-driven tree readers and the schemaless `TsonTreeReader`), the `TsonTreeWriter` back to text, and
the removal of the old throwaway `Map`/`List` DOM mode all landed. What's left:

- [ ] **Copy-on-write transforms + builders (parked).** The "new tree from old" editing half —
  `RecordNode.with(name, node)`/`without(name)`, `ArrayNode.with(i, node)`/`plus(node)`/`without(i)`,
  `RecordNode.builder()`, and a pointer-based `set("/a/b", node) → new tree`. All pure `tson-tree`
  operations (no compiler dependency), so they belong in that module. Deferred until there's a concrete
  produce/edit use case: `TsonTreeWriter` already closes the read→edit→write loop, so these have a real
  payoff when wanted, but block nothing now.

## Front door / ergonomics

- [ ] A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy — today the only
  `TsonSchemaSource` is `TsonSchemaSource.registeredOnly()` (nothing fetched); the bundled standard
  library is served internally by `TsonCompiledSchemaRegistry` from `TsonBundledSchemas`, not through
  a source.
- [ ] **`tson validate` renders a library fault as a per-file `VALIDATION_ERROR`.**
  `ValidateCommand`'s read loop catches `RuntimeException | IOException` around `Tson.validate` and turns
  either into a `VALIDATION_ERROR` verdict for that file. The `IOException` half is right (an unreadable
  file *is* that file's problem); the `RuntimeException` half is not, because `Tson.validate` deliberately
  rethrows anything that isn't a base-syntax failure — see `Diagnostic.ofBaseSyntaxError` — precisely so a
  bug in this library doesn't come back as "your document is invalid". The CLI puts that verdict back on.
  Splitting the catch would let a genuine fault surface as a crash with its stack trace, which is the
  honest outcome, and leaves the file-level diagnostics alone.

## Layer boundaries / schema registry

- [ ] Add the missing other half of `TsonSchemaLinker`'s existing "constructor eligibility" check —
  a resolve/link-time *diagnostic*. That check already restricts *where* `constructor: true` can be
  declared (only a meta-kernel-governed schema, per §2.2.2); the missing half is restricting *where a
  schema can be used as a `!!meta` target* the same way, so an ordinary consumer schema can't
  accidentally govern another one, surfaced by the linker (with the spec's own wording) at
  resolve/link time. The 2026-08-02 `TsonCompiledSchemaRegistry` cleanup arc closed the *type-level*
  half of this — the meta/non-meta split, so a non-meta schema won't type-check where a governing
  meta is required — and now rejects a non-meta `!!meta` target at load time via
  `loadMeta`, but the linker-level validation itself is still absent.

## Resolution & linking generality

Every real schema resolved so far (meta-kernel, meta.tn, core.tn, and hand-built test fixtures)
happens to fit a narrow shape this pipeline already handles — declared in dependency order, with
callers hand-sequencing registration themselves. These items are what's missing for the *general*,
spec-required case, found by re-auditing Part 2 against the current source rather than CLAUDE.md's
own prose (which had gone stale on at least one of them):

- [ ] **Automatic reference-closure resolution and import-cycle detection** ([TSON-DATA] §2.2.3,
  [TSON-SCHEMA] §3.4.1) — no code collects a schema's transitive `!!meta`/`!!import` closure,
  topologically orders it, and resolves it dependencies-first; every caller (including this
  session's own `TinySchemaImportsCoreTn1Test`) has to already know and hand-sequence the correct
  registration order itself. A real import cycle is only caught incidentally today, as an opaque
  "not registered" error from `TsonSchemaLinker.mergeImports` — never with the spec's own required
  "import cycle" diagnostic naming the actual cycle path. Distinct from the "single load-stdlib
  entry point" item above, which is scoped to just the three bundled schemas, not a general
  algorithm.
- **Choice disjointness derivation and untagged reading** ([TSON-SCHEMA] §5.4, §8.1) — landed in part:
  - [ ] **Two §5.4 "MAY" cases left absent:** record-set disjointness under composition, and pattern
    disjointness over `regex`-constrained atoms. The view on how far to go (recorded so it isn't
    relitigated): the `disjoint` *fact* is encoding-independent, but §5.4's Tagging rule makes TSON text
    recover a variant only by the single §4 base-type-resolution pass — class-level (null/boolean/
    number/string), never a type-directed inspection — so **two variants of the same base-type class are
    never TSON-text-discriminable, regardless of value-set disjointness** (`(email | uri)` is the spec's
    example). Same-family numeric-bound and regex-pattern disjointness both produce a same-base-class
    `true`, so neither buys TSON-text *untagged reading* anything; the cheap different-class rules
    (family/kind) are the only scalar disjointness the reader can use. Pattern disjointness is therefore
    **not** wired into the reading-path derivation — but it is *safe* to compute and belongs in the
    `@disjoint` checker below (an earlier "an innocuous pattern edit silently flips tag-requiredness"
    worry is void: schemas are immutable and hash-pinned, so a revision is a new content identity —
    existing data pins the old, still-disjoint one, and a disjointness-losing revision is a load-time
    error under `@disjoint`, never a silent flip). Wiring `isDisjointFrom` into the fact needs a
    dependency-inversion seam, since `tson-schema` deliberately doesn't depend on `tson-regex` — a
    pattern-disjointness oracle injected into the linker, supplied by `tson-compiler`. See
    `SPEC-FEEDBACK.md` #23 for the load-bearing ambiguity underneath all of this.
  - [ ] **The `@disjoint` assertion check** — an author's `@disjoint` marker checked against the derived
    fact: proved (silent), refuted / provably-not (resolver error), unprovable (warning), absent (no
    check). This is where exact regex-pattern disjointness (`isDisjointFrom`, via the seam above) pays
    off — turning an otherwise-"unprovable" pattern choice into a proved-or-refuted one. **No longer
    blocked**: a declaration's annotations now reach the resolved model, on `TypeDefinition` when written
    after `=>` and on the schema map's key when written before the name, so the linker can read a
    `@disjoint` marker where `disjoint` is derived. Nothing in the bundled schemas actually writes one yet,
    so the checker's own fixtures have to supply it.
- [ ] **§5.10 substitution into a template *body*.** Half of template application works. A template that
  refines a constructor — `array_ranged => <T, MIN, MAX> array<T> ^ { min_items: = MIN  max_items: = MAX }`,
  and therefore §5.3's sized sugar — instantiates per §8.2, because its resolved vocabulary carries the same
  `value_param` channels a constructor's does, so the arguments route by the same mechanism and the binding
  record is headed at the nearest `~` constructor. What is left is a template whose parameter appears as a
  **field type** (`box => <T> { v: T }`): instantiating that means rewriting the body with `T` replaced,
  which is substitution proper. Rejected at the application site today
  (`SchemaDesugarer.rejectIfTemplateApplication`), so it fails where it is written rather than at a read
  that may never happen.
  - Belongs in the same phase, as another `TemplateInstance`-producing path: the body comes from the
    template's own AST with parameters replaced, rather than from a vocabulary.
  - **Requires a termination guard.** Non-regular (polymorphic) recursion like
    `weird => <T> { next: weird<[T]>? }` / `use => weird<text>` grows its argument every level
    (`text` → `[text]` → `[[text]]` …). Every instantiation is structurally distinct, so the
    dedup-by-derived-name never fires and the walk never terminates. Distinct from `SPEC-FEEDBACK.md` #25
    (non-*productive* recursion — no finite *data* model): this is no finite *type* model.
  - **The rejection is narrower than the feature.** A head declared by the current document or present in
    the structure namespace is caught; a template declared by an **`!!import`** still slips through to the
    old read-time failure, because catching it needs the imported entries' resolved definitions rather
    than the name set the phase currently takes. Worth closing when substitution lands, if not before.
  - The scoping questions around generic heads — how precedence is worded, silent cross-namespace
    shadowing, whether parameters are eligible at a head, and when the `constructor: true` gate applies —
    are `SPEC-FEEDBACK.md` #28, which also records the answers this implementation currently gives.
- [ ] **The rest of §8.2's deferred value-level checks.** Materialisation "runs the value-level checks that
  open bounds deferred: family coherence rules whose operands were parameters". The array family's
  `min_items <= max_items` is done, in `SchemaDesugarer.checkBounds`, because it is the only rule the
  kernel's own templates route parameters into and the sugar (`[T; 5..3]`) is how an author reaches it. The
  kin §8.2 gestures at — "bounds within a width-derived range, and their kin" — belong with the constraint
  families that own them, next to `AtomNarrowing`, not in a syntax rewrite. Doing that properly probably
  means the check moves out of the desugarer entirely and `checkBounds` goes with it.
- [ ] **Resolved-form ingest** ([TSON-SCHEMA] §8.1/§10.1) — bringing an already-resolved
  `!type_definition` document into the library (not source text), with its own integrity checks:
  `subtypes`/`disjoint` recomputed and verified, the closed-entry parameter-free rule reverified, an
  instantiation entry checked against its own `source` by recomputation, a construction's binding
  record checked for parameter-slot agreement with its `source` application. Entirely unimplemented
  — "ingest" doesn't appear anywhere in the codebase. Note it would introduce a *second* way to build a
  `TsonSchema` — bound from a document rather than resolved from source — and the two would have to agree,
  including on where a declaration's annotations land (the name's on the map key, the definition's on the
  entry). Lower priority than the rest of this section: the spec marks this path explicitly **optional**
  ("MAY implement ingest"), not a MUST.

## Atom-refinement constraint validation

- [ ] **`DefinitionResolver`'s `TsonObjectWriter` dependency stays — the premise this item used to
  carry was wrong.** It read "a real narrowing check wouldn't need to round-trip through the generic
  binder at all"; the check landed and the round-trip is still load-bearing, because **the check and
  the merge are separate concerns**. The check compares two already-bound constraint objects; the
  merge is what *produces* the second one, and it has to run on the wire record *before* binding.
  The blocker is concrete: an object-level merge would have to bind the refinement body on its own
  first, and `float_type.format` (`format: ieee_format`, `REQUIRED` with no schema default) and
  `binary.encoding` have nothing to fall back on, so `!float32 ^ { min: 0.0 max: 1.0 }` would fail
  `FIELD_REQUIRED` on a field its source already fixes.
  `DefinitionResolverTest.atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed` pins that case
  so the constraint isn't rediscovered the hard way. Nor is there a cheaper substitution:
  `TsonObjectWriter` writes straight to a `TsonDataEmitter`, so there is no object→`DataValue` step
  to borrow that would skip the text round-trip. Removing it for real needs each family to own its
  own wire decoding (duplicating number-grammar handling — `0xFF`, `_` separators, quoted-vs-unquoted
  — and bypassing the compiled reader's own defaults), which costs more than the round-trip does.
  This is still *why* `TsonObjectReader`/`TsonObjectWriter` live in `tson-compiler`'s root package
  rather than the `tson` front-door module (see "Front door" above) — moving them to a module that
  depends *on* `tson-compiler` would create a cycle, since the resolution engine genuinely depends on
  the writer. So the "revisit moving them into `tson`" note on `Tson`'s own class Javadoc is blocked
  on a different, larger change than this item once assumed.

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
- [ ] Generic type-refs whose arguments are not simple names. A non-simple argument (`weird<[T]>`) is
  rejected outright ("only simple type arguments are resolved so far") — `SchemaDesugarer` reduces an
  argument that is itself an application to the name it was hoisted to, so ordinary nesting
  (`map<text, [integer]>`) works; what is left is an argument that does not reduce to a plain name. The
  `weird<[T]>` shape additionally sits inside a *parameterized* declaration, which the phase skips
  entirely. Lifting this is part of the template-application item above, including its termination guard.
- [ ] `= _` (absent) field modifier, and any `~`/`=` modifier on an already-`OPTIONAL` field —
  `DefinitionResolver.resolveField` rejects both today.
- [ ] Closed-entry parameter-free check (§5.10) — nothing validates that an entry with an empty
  `parameters` list truly contains no parameter references (`value_param` members, or a reference
  name resolving to a parameter) anywhere in its body, at any depth. Distinct from `value_param`
  *substitution* (tracked in `STRUCTURED-OUTPUT.md`) — this is a rejection rule for a malformed
  "closed" entry, not the substitution mechanism itself.

(All already named in `DefinitionResolver`'s own Javadoc and `CLAUDE.md`; carried here so
everything outstanding is tracked in one place.)

## Schema-side diagnostics

The read path now reports through a `TsonDiagnosticsReceiver`; the *schema* path does not. Everything from
parsing a schema document to compiling it is fail-fast, and a consumer sees the result flattened.

- [ ] **Report schema problems as `Diagnostic`s, through the same receiver.** Today a failure anywhere in
  parse → desugar → resolve → link → compile throws, and the one caller that must not throw
  (`Tson.validate`) catches it and emits a single `SCHEMA_ERROR` carrying `e.getMessage()` — no path, no
  position in the *schema* document, and only the first problem, because the pipeline stopped there. A
  schema author gets one error per run. What's wanted is the reader treatment: several problems, each with
  its own position, from one pass.
  - `Diagnostic` already has the field for it. `schemaPosition` exists and is populated today only from
    `TypeDefinition.position()` on the *read* side; schema-side diagnostics are what it was shaped for.
  - There is a partial precedent to follow rather than invent around: `TsonSchemaCompiler` already
    substitutes an `ErrorReader` for an entry that fails to build, so the schema still compiles and only
    *reading* that entry fails. That is the "keep going, record the problem" instinct applied at compile
    time; generalizing it means the resolver and linker doing the same.
  - Comparable in size to the reader-side receiver work but spread wider, and with one real difference:
    the readers had `TsonReadContext` already threaded everywhere to hang `report` off, while the schema
    phases have no such shared object. That, not the receiver, is the actual design work.
  - Payoff beyond error quality: `tson compile` could report like `tson validate` does, and a
    schema-authoring loop (LLM or human) gets the same localized feedback `STRUCTURED-OUTPUT.md` Tier 1
    specifies for data.

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has only the two schemaless writers and is
missing most of the mirror.

- [ ] **No schema-aware (Class 2) writer — `TsonValueWriter`.** Only the schemaless `TsonObjectWriter`
  (object → TSON) and `TsonTreeWriter` (`TsonNode` → TSON) exist, both with documented lossy spots
  (integer width, tuple-ness). A writer symmetric to the
  compiled reader stack (`TsonSchemaCompiler`/`TsonValueReader`) — checking output against a TSON schema
  and reporting what's wrong — is a whole missing half of the pipeline, and the natural home for
  round-tripping or producing guaranteed-conformant documents.
- [ ] **Writers are fail-fast only, no diagnostics.** They throw `TsonWriteException` at the first
  problem, with nothing symmetric to the read side's `TsonDiagnosticsReceiver`. The `TsonValueWriter`
  above especially needs it, to report every schema violation in one pass the way the reader does — and
  the seam already exists and is write-direction-agnostic (`Diagnostic` carries a data path and both
  positions; nothing about `void report(Diagnostic)` assumes reading), so this is a matter of threading a
  receiver through the emitter, not designing a second error model.
- [ ] **Writers materialize a `String`, not a stream.** `toTson(...)` returns the whole document in
  memory — asymmetric with the readers, which accept an `InputStream` and never fully buffer. Writers
  should also accept an `OutputStream`/`Writer`/`Appendable` and emit incrementally; the internal
  `TsonDataEmitter` already builds into a `StringBuilder` and could target any `Appendable` instead.
  (Same streaming theme as `Tson.validate(InputStream)` buffering the whole document to a `String`.)
- [ ] **No public push/event writer.** The read side exposes a pull `TsonDataStream` (→ `TsonEvent`);
  the only emitter, `TsonDataEmitter`, is internal. A public event-driven writer would let a caller emit
  TSON without first building a whole tree or object — the write-direction peer of `TsonDataStream`.
- [ ] A JSON writer (TSON data → valid JSON text) — the write-direction companion to
  `STRUCTURED-OUTPUT.md`'s "JSON compatibility" section, tracked here alongside the general writer
  since it's the same underlying gap (no schema-aware writer exists at all yet).

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
- [ ] `@doc`-driven documentation generation (render a schema's own `@doc` annotations). **Unblocked**: all
  104 `@doc` strings across the three bundled schemas now survive resolution and linking, reachable as
  `schema.entries().getAnnotations(name).value("doc", String.class)` — every one of core.tn's 48
  declarations is documented. What's missing is the renderer, not the data.
- [ ] Documentation for the *diagnostics* story specifically — `TsonDiagnosticsReceiver` is the seam a
  consumer implements to route problems anywhere (a formatter writing to stdout as they arrive, a capped
  collector, a metrics sink), and only the two built-ins are shown anywhere today. The README covers the
  collector; a worked custom receiver is the obvious missing example, and it is what
  `STRUCTURED-OUTPUT.md`'s Tier 1.5 streaming consumer will be built on.

## Miscellaneous

- [ ] `Tson.objectReader()`/`treeReader()` each construct a *fresh* `TsonCompiledSchemaRegistry` instead of
  reusing the eagerly-built `this.tree`/`this.bind`, so a facade reader doesn't share the compiled-schema
  cache with `treeRegistry()`/`bindRegistry()` and a schema can be compiled twice in one `Tson`. The
  `withDiagnostics`/`withSchema` derivations deliberately *share* rather than rebuild, so the leak is
  confined to the two accessors.
- [ ] The three `TestDocuments` test helpers (one each in `tson-compiler`, `tson`, `tson-cli`) are
  near-duplicates, because this build has no `java-test-fixtures` plugin. Either wire that up or accept the
  repetition; it is a few lines, so this is a build-hygiene call rather than a real cost.
- [ ] `ContentHashMismatchException` is the one unprefixed member of an otherwise consistent
  `Tson*Exception` family (`TsonParseException`/`TsonReadException`/`TsonWriteException`/
  `TsonUnsupportedDocumentException`). A rename, whenever the naming pass happens.

- [ ] Thread-safety — currently only `synchronized` on `TsonSchemaRegistry`/
  `TsonCompiledSchemaRegistry`'s own `register`/`get`/`getMeta` (its `load` deliberately isn't, to
  avoid serializing unrelated on-demand loads); everything else is an open design question.
- [ ] General resolver-layer structural rules as reusable primitives, rather than binding-time-only
  behavior — empty-brace resolution, the absent-vs-missing distinction.
- [ ] **Annotations are still discarded at a dispatched position.** A dispatcher (`VariantBindReader` for a
  union, `VariantSchemaReader` under bind mode) must consume the leading annotations to reach the
  `!typeName` it dispatches on -- they precede it in `data-value = *annotation [type-ref] core-value` -- so
  the reader that ends up building the value never sees them. Tree mode solved this by re-attaching to the
  finished node (`TsonNode.withAnnotations`); bind mode would do the same through `DataClassAnnotated`'s
  `constructor` handle, wrapping what came back.
  - Not reachable today: a union member is not a boxed position, so its carrier is always empty rather than
    wrong. Worth closing when a boxed variant becomes expressible, not before.
- [ ] Confusable-character and bidi-formatting-character warnings (§9.4-adjacent security hardening) —
  the sibling gap to the numeric-literal length limit tracked in `STRUCTURED-OUTPUT.md`'s Tier 1 section;
  neither is enforced anywhere yet. `SPEC-FEEDBACK.md` #34 is the fuller treatment: which UTS #39 mechanism
  applies where, the comparison scopes TSON can actually name, and why a normative requirement would oblige
  every implementation to ship UCD data the JDK does not expose.

