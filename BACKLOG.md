# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

---

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
  "import cycle" diagnostic naming the actual cycle path. Distinct from what
  `TsonCompiledMetaRegistry.withStandardLibrary` already does, which is scoped to just the three bundled
  schemas in a known order, not a general algorithm.
- [ ] **Template construction — the `SPEC-FEEDBACK.md` #44/#45/#46 conclusions, staged.** The design review
  settled the type-constructor-vs-template question: a **partial application** (parameters only in labelled
  value channels — `array_min`, and §5.3's sized sugar) closes by *routing* and is a construction of its
  head constructor, with **no instantiation entry and no IS-A** (`supertypes: [array product top]` on sized
  closures was a category error — a constructor is not a type — and the grant was inert); a **structural
  template** (parameters in type-reference channels — `box => <T> { v: T }`) closes by *substitution* and
  materialises by *rewriting* a body rather than routing into one — the one form substitution is needed for,
  though no longer §8.2's sole materialising entry (#50); a constructor's parameters are labelled-only (#44); and
  deriving from a constructor requires a `~` result (#46). Split by what gates on a spec revision:
  - **This cycle** (each corrects or converges on agreed-wrong spec letter; no revision needed first):
    - [ ] **#44's declaration-time check**: a `~` declaration with a parameter occurrence outside a
      labelled value channel is a `TsonSchemaValidationException` at the declaration. No valid schema is
      affected (the kernel audit in #46 found zero violations among constructors); today the incoherence
      surfaces as downstream wrong-layer failures.
- [ ] **§5.10 substitution into a template *body* — the one form that materialises by substitution.** What remains of
  template application now that a partial application closes by routing: a template whose parameter appears
  as a **field type**
  (`box => <T> { v: T }`), where instantiating means rewriting the body with `T` replaced — substitution
  proper. Rejected at the application site today (`SchemaDesugarer.rejectIfTemplateApplication`), so it
  fails where it is written rather than at a read that may never happen. Unaffected by the #45 staging:
  structural templates exist in rev 32 and keep their semantics under the redesign, so this can proceed
  independently, before or after the revision.
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
  means the check moves out of the desugarer entirely and `checkBounds` goes with it. Distinct from the
  atom-body self-coherence item below, which shares that destination but has no parameter or
  materialisation dimension at all.
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
- [ ] **Derive an injected entry's name canonically, not from `Record::toString`.**
  `SchemaDesugarer.syntheticName` ends the derived name with `String.format("%08x", (head + args).hashCode())`,
  where `args` is a `List<TypeArg>` — so the hashed string is compiler-generated record `toString` output
  (`[Ref[ref=SimpleRef[name=type_name]]]`). The JDK documents that format as "subject to change", and it also
  moves whenever an AST record's components are renamed or reordered (`TypeArg.Ref.ref`, `SimpleRef.name`),
  silently renumbering every injected entry in every schema. Switching to the records' own `hashCode()` would be
  **worse**: `Record::hashCode` is explicitly permitted to differ "from one execution of an application to
  another execution of the same application", where `String.hashCode` is specified exactly — which is the only
  reason today's value is stable at all. The fix is to build the canonical string by walking the application
  (head, then each argument's kind and text, recursively) and hash that; `TsonContentHash` is in this module if
  a truncated sha-256 is preferred to 32 bits.
  - **Why it is worth doing rather than cosmetic:** under `SPEC-FEEDBACK.md` #50/#51 these names are entries in
    the resolved form, and an importing schema's own application lands on the imported entry by deriving the
    same name. §8.2's *Determinism* recommendation is what makes two resolutions of the same schema pair agree
    entry-for-entry, so the derivation should be deterministic by construction rather than by accident.
  - Not a live bug: nothing persists a resolved form today (`tson compile` reports pass/fail, not entries), so
    this is a stability guarantee ahead of need.

## Remaining Part 2 resolution gaps

- [ ] Generic type-refs whose arguments are not simple names. A non-simple argument (`weird<[T]>`) is
  rejected outright ("only simple type arguments are resolved so far") — `SchemaDesugarer` reduces an
  argument that is itself an application to the name it was hoisted to, so ordinary nesting
  (`map<text, [integer]>`) works; what is left is an argument that does not reduce to a plain name. The
  `weird<[T]>` shape additionally sits inside a *parameterized* declaration, which the phase skips
  entirely. Lifting this is part of the template-application item above, including its termination guard.

- [ ] **A parameterized supertype reference** (`vip => <T> customer & box<T> & { ... }`, §5.8's
  "Parameterized references"). The only genuine gap left in the composition path, and it is not independent:
  §5.8 says the applied form's arguments reach "the absorbed fields, which carry the parameters through
  ordinary type channels", so composing with `box<T>` means substituting into a record template's body —
  the §5.10 item above, termination guard and all. Worth doing together, not before.
- [ ] **A field/element type that is not a simple name, a generic application, or an inline array.**
  `DefinitionResolver.resolveTypeRef`'s catch-all ("only simple (non-generic) type-refs, generic
  applications of one, and inline arrays of one are resolved so far"). **Only reachable inside a
  parameterized declaration**, contrary to what this item used to claim — `SchemaDesugarer` normalizes every
  inline and generic form in an ordinary field or group-member position first, so `[[text]]`,
  `[map<text, integer>]`, `(text | integer)`, `[text, integer]` and `map<text, [integer]>` all compile
  today, in both positions. The phase skips a parameterized declaration entirely, and that is where the
  throw is live, in two shapes measured directly:
  - `box => <T> { v: [T] }` — the `InlineArrayRef` branch *does* fire and builds `array<T>`, then the linker
    rejects `array`, which a user schema's type-name namespace does not hold. The author is told their
    schema has an unresolved reference to something they never wrote.
  - `pair => <T> { v: (T | text) }` — `ChoiceRef` hits the catch-all, and an `UnsupportedOperationException`
    reaching the CLI is reported as `internal error ... This is a bug in tson` with exit 70. It is a gap,
    not a fault, so even before the feature lands the classification is wrong (see the exception policy in
    CLAUDE.md).
  Both being inside a template makes this part of the §5.10 substitution work rather than independent of
  it — desugaring a parameterized declaration is the same problem as substituting into one.

Only genuine gaps are listed above — a throw that means "your schema is wrong" is not one. Classifying the
throw sites by that test is done across the whole schema pipeline (issue #26); if a census is ever wanted
again, take it fresh rather than trusting a recorded one, since the last recorded numbers had gone stale
by a factor of six.

- [ ] **Atom-body coherence, the parts that need a parser this module doesn't have.** `Atom.coherenceCheck`
  (issue #50) now rejects an atom body whose own facets admit nothing, but three gaps are left, each
  matching that family's existing *narrowing* gap and each blocked on the same thing — `tson-schema` has no
  dependency on a parser for the values involved:
  - `duration_type`'s bounds are unparsed ISO 8601 text. `"P1M"` vs `"P30D"` does not order lexically, so
    judging them as strings would call a coherent body empty. Needs `DurationParser`/`IsoDuration`, which
    live in `tson-compiler`.
  - `pattern` emptiness — a regex matching no string at all, or none of a permitted length. Needs
    `tson-regex`, the same boundary the narrowing check's containment gap sits behind.
  - CIDR `within`/`excluding` admitting no network between them. Needs real containment arithmetic; the
    family has no CIDR parser.
  - The natural fix for all three is the same one the narrowing check would want: an injected oracle, rather
    than moving the value model's dependencies.

## Schema-side diagnostics

Parsing, desugaring, resolution and linking all report every independent problem in one pass through a
`TsonDiagnosticsReceiver` (issues #3/#28/#29), whether reached by `tson compile`/`Tson.validateSchema` or by
a *data* read whose `!!schema` names a schema that doesn't resolve — both give the same account of the same
broken schema. `docs/readers-and-diagnostics.md`'s "Schema-side diagnostics" section describes the shape and
the decisions behind it. One thing is left here; the *floor* under all of it — the lexer being fail-fast, so
a token that will not lex aborts the pass that would have reported past it — is deliberately not an item in
this list, because there is no work to do until someone decides whether lexer errors feed the `Diagnostic`
model at all. `STRUCTURED-OUTPUT.md` holds that question.

- [ ] **The read path carries `schemaPosition` but not `schemaId`/`schemaPointer`** — a reader knows the
  declaration position it stamped, not which entry of which schema produced it, so a value error reports
  `110:3:4858` with nothing saying that is core.tn's line for `int32`.
  - **It is not just a matter of threading the compiled schema's identity down the reader stack** (as this
    item used to claim): the identity a reader could reach that way is the *wrong one*. `TsonSchemaLinker`'s
    `mergeImports` copies each imported `TypeDefinition` straight into the importing schema's `entries()`
    map and keeps nothing about where it came from, so by compile time the origin is already gone. Measured:
    a 4-line `point-1.tn` declaring `{ point => { x: int32 } }` reports `schemaPosition` `110:3:4858` —
    core.tn's line for `int32`. Stamping `linkedSchema.schema().id()` alongside it would pair
    `example.test/point-1.tn` with line 110 of a 4-line document, sending a consumer to the wrong file.
    Absent is honest; wrong is not, which is why the field is empty rather than approximated.
  - So the work is upstream first: **record each merged entry's origin schema id**, either as a side map on
    `TsonSchema`/`TsonLinkedSchema` populated by `mergeImports` (preferred — leaves `schema.meta` alone,
    since the spec's own `type_definition` has no such field and it is a bind target with a hand-written
    `equals` and the `@Record` constructor-selection trap) or as a new excluded-from-equality component on
    `TypeDefinition` beside `position`. Only then does the reader-stack half apply: `withSchemaPosition`
    becomes a three-part stamp across its ~11 reader call sites, and `DefaultTsonReadContext.report`
    populates all three. `schemaPointer` is the cheap half — `ValueReaderFactory` is already handed the
    entry's own declared name, which is the `/name` pointer.
- Granularity ceiling to know before starting any of these: positions are **per declaration**, from the
  declaration's own name token. Sub-declaration positions (which field, which supertype) do not exist and
  would be their own parser work — visible today as a diagnostic pointing at `/my_type` when the problem is
  one of its fields. A *syntax* error is the one exception: it has the failing token's own position, since
  the parser reports it where it stands rather than looking it up per declaration afterwards. Its
  `schemaPointer` is still only `/my_type`.

## Miscellaneous

- [ ] General resolver-layer structural rules as reusable primitives, rather than binding-time-only
  behavior — empty-brace resolution, the absent-vs-missing distinction.
- [ ] **Annotations are still discarded at a dispatched position.** A dispatcher (`VariantBindReader` for a
  union, `VariantSchemaReader` under bind mode) must consume the leading annotations to reach the
  `!typeName` it dispatches on -- they precede it in `data-value = *annotation [type-ref] core-value` -- so
  the reader that ends up building the value never sees them. Tree mode solved this by re-attaching to the
  finished node (`TsonValue.withAnnotations`); bind mode would do the same through `DataClassAnnotated`'s
  `constructor` handle, wrapping what came back.
    - Not reachable today: a union member is not a boxed position, so its carrier is always empty rather than
      wrong. Worth closing when a boxed variant becomes expressible, not before.

## Remaining built-in types

- [ ] `unknown` — no compiled-parser factory (`ValueReaderFactoryRegistry` registers it, and `extern`, to
  `ErrorReader`), pinned down exactly by
  `CoreSchemaImportTest.exactlyTheUnknownAtomConstructorCompilesToAnErrorReader`. Not an unwritten atom
  grammar: `unknown` accepts any well-formed value of any type, so what it needs is a reader deferring to
  the document's own type-ref (or to schemaless base-type resolution when there is none) — a design
  question about where that dispatch lives.
- [ ] `extern` ([TSON-SCHEMA] §7.8) — materially bigger than the item above, and a different kind of gap
  again. `Extern` (`schema.meta`) is a record-only placeholder with no
  parsing/validation behavior at all (its own Javadoc says so explicitly: "not to add real
  cross-schema reference resolution"); the real mechanism — a value at an extern-matched position
  carrying its own scoped `!!schema` plus a mandatory `!type` tag, switching schema scope
  mid-document — doesn't exist anywhere in the reader stack.

# Lower Priority

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has only the two schemaless writers and is
missing most of the mirror.

- [ ] **No schema-aware (Class 2) writer — `TsonValueWriter`.** Only the schemaless `TsonObjectWriter`
  (object → TSON) and `TsonTreeWriter` (`TsonValue` → TSON) exist, both with documented lossy spots
  (integer width, tuple-ness). A writer symmetric to the
  compiled reader stack (`TsonSchemaCompiler`/`TsonTypeReader`) — checking output against a TSON schema
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

- [ ] Build out `ltr8-io-tson-test-suite` well beyond its current 123 vectors, spread across four buckets
  (`lexer`/`parser`/`resolver`/`vocabulary`). Still Part 1 (lexer/parser/§5 vocabulary) only — Part 2
  (resolution, linking, compilation) has no conformance-suite coverage at all yet, only this repo's own
  unit/integration tests.
- [ ] **Run the JSON front-end against the established JSON Parsing Test Suite** when it lands (the
  `TsonJsonParser` tracked in `STRUCTURED-OUTPUT.md`). JEP 540 commits to exactly this for the JDK's own
  parser — its own unit tests *plus* that external corpus, "which contains numerous edge-case inputs" —
  and the reasoning is the same one this repo's sibling suite exists for: an external, language-agnostic
  fixture set catches drift a self-authored suite agrees with. Cheap, since the corpus is pass/fail on
  parse and the front-end's whole job is RFC 8259 conformance.

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
  - `!choice { variants: [...] }` — no longer blocked: choice resolves, links, compiles and reads. But the
    reservation that always sat behind it stands and is the deciding factor here — a schema built on it
    needs a `!typeName` tag unless every variant occupies a distinct base-type class, and `core_value`'s
    variants are all records, so every one of them would be tagged on the wire.
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
  write `value` as a small nested record on the wire. `!choice` now resolves, but this was never the
  item waiting on it: a precise per-family shape needs a discriminator on `type-ref` itself — an open
  ~30-name vocabulary, not a small closed enum like `outcome`/`kind` — so it isn't the free win the
  `outcome`-discriminated shapes are.

## Documentation

- [ ] User-facing documentation on how to use the library — today only `CLAUDE.md`'s own dense,
  session-oriented internal narrative exists.
- [ ] AI skills for using the library.
- [ ] `@doc`-driven documentation generation (render a schema's own `@doc` annotations). **Unblocked**: all
  104 `@doc` strings across the three bundled schemas now survive resolution and linking, reachable as
  `schema.entries().getAnnotations(name).value("doc", String.class)` — every one of core.tn's 48
  declarations is documented. What's missing is the renderer, not the data.
- [ ] **State the streaming contrast in README positioning.** JEP 540 makes its in-memory limit an explicit
  design decision, not an omission: "We assume that input JSON documents can fit in memory, as either a
  `String` or a `char` array... if we were to allow JSON sources such as files or network connections,
  issues such as insufficient memory would be possible with large documents." That is the cleanest
  available statement of what this library's reader stack buys — `TsonDataStream` pulls events, every
  facade reader takes an `InputStream` and never fully buffers, and memory is proportional to nesting
  depth rather than document size. Worth a line, because when JSON ships in the JDK the question becomes
  "why not just use that?", and the honest answer is a short list: schema, binding, streaming, collected
  diagnostics — each of which JEP 540 names as a non-goal.

- [ ] Documentation for the *diagnostics* story specifically — `TsonDiagnosticsReceiver` is the seam a
  consumer implements to route problems anywhere (a formatter writing to stdout as they arrive, a capped
  collector, a metrics sink), and only the two built-ins are shown anywhere today. The README covers the
  collector; a worked custom receiver is the obvious missing example, and it is what
  `STRUCTURED-OUTPUT.md`'s Tier 1.5 streaming consumer will be built on.

## Front door / ergonomics

- [ ] A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy — today the only
  `TsonSchemaSource` is `TsonSchemaSource.registeredOnly()` (nothing fetched); the bundled standard
  library is served internally by `TsonCompiledSchemaRegistry` from `TsonBundledSchemas`, not through
  a source.

## Tree model (`TsonValue`)

The tree model itself is built and described in `docs/facades-and-tree.md`'s "Tree model" section. What's left:

- [ ] **Copy-on-write transforms + builders (parked).** The "new tree from old" editing half —
  `TsonRecord.with(name, value)`/`without(name)`, `TsonArray.with(i, value)`/`plus(value)`/`without(i)`,
  `TsonRecord.builder()`, and a pointer-based `set("/a/b", value) → new tree`. All pure `tson-tree`
  operations (no compiler dependency), so they belong in that module. Deferred until there's a concrete
  produce/edit use case: `TsonTreeWriter` already closes the read→edit→write loop, so these have a real
  payoff when wanted, but block nothing now.
    - **Nothing to copy from JEP 540, and that is the useful part.** It ships no transformation API and no
      builders at all — construction is static `of(...)` factories, which `tson-tree` already matches — and
      its Risks section defers the area outright: "During the incubation period, we will gather more
      information about use cases involving generating and transforming JSON documents, in order to evolve
      these areas of the API." The JDK reached the same "wait for real use cases" conclusion independently,
      which turns this item's deferral from a shrug into a decision.

## Miscellaneous

- [ ] Thread-safety — currently only `synchronized` on `TsonSchemaRegistry`/
  `TsonCompiledSchemaRegistry`'s own `register`/`get`/`getMeta` (its `load` deliberately isn't, to
  avoid serializing unrelated on-demand loads); everything else is an open design question.
- [ ] Confusable-character and bidi-formatting-character checks (§9.4-adjacent security hardening;
  opt-in, and per `SPEC-FEEDBACK.md` #42 reported as ordinary errors when enabled, not warnings) —
  the sibling gap to the numeric-literal length limit tracked in `STRUCTURED-OUTPUT.md`'s Tier 1 section;
  neither is enforced anywhere yet. `SPEC-FEEDBACK.md` #34 is the fuller treatment: which UTS #39 mechanism
  applies where, the comparison scopes TSON can actually name, and why a normative requirement would oblige
  every implementation to ship UCD data the JDK does not expose.

- [ ] **Template construction — the `SPEC-FEEDBACK.md` #44/#45/#46 conclusions, staged.** The design review
  settled the type-constructor-vs-template question: a **partial application** (parameters only in labelled
  value channels — `array_min`, and §5.3's sized sugar) closes by *routing* and is a construction of its
  head constructor, with **no instantiation entry and no IS-A** (`supertypes: [array product top]` on sized
  closures was a category error — a constructor is not a type — and the grant was inert); a **structural
  template** (parameters in type-reference channels — `box => <T> { v: T }`) closes by *substitution* and
  materialises by *rewriting* a body rather than routing into one — the one form substitution is needed for,
  though no longer §8.2's sole materialising entry (#50); a constructor's parameters are labelled-only (#44); and
  deriving from a constructor requires a `~` result (#46). Split by what gates on a spec revision:
    - **Waits for the spec revision** (grammar or kernel-document changes — implementing ahead would diverge
      from rev 32 rather than converge on the agreed design):
        - The `C<args; member ...>` application-with-bindings surface form (#45) — a normative grammar change;
          until then the sized sugar is the only spelling of a partial application of `array`.
        - The kernel respell — size templates deleted (or one kept as §5.10's worked example) and `vector`
          restated in the new form — with re-pinned content hashes, refreshed `spec/` snapshots, and updated
          resolved fixtures.
        - **#46 enforcement** (a constructor operand in `^`/`&`/subtraction requires a `~` result): enabling it
          today fails the bundled meta-kernel's own three size templates — the rule and the kernel fix must
          land together.
        - §3.3.1's constructor-gate exemption removal as spec text (the implementation's special-casing goes
          with the direct-desugar item above regardless).
        - The slot-binds-once and no-partial-application-recursion rules — meaningful only once the new form
          exists.
        - Conformance-suite vectors for any of this — blocked twice over: the suite has no Part 2 layer at all
          yet (see "Conformance test suite").
