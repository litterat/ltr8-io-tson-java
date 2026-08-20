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
- [ ] **Record templates — `spec/tson-cr-structure-templates.md`, Tranche B.** §5.10 substitution for the
  template form whose parameters occupy *field types and field values* (`box => <T> { v: T }`,
  `<T> { a: T  b: text ~ "test" }`), rejected at the application site today
  (`SchemaDesugarer.rejectTemplateApplication`). **Deliberately excludes any sugar form inside a template**
  (`<T> { v: [T] }`, `<T, N> [T; N]`) — that is Tranche C, and it is the only part needing new vocabulary or
  new grammar. Splitting there keeps the engine and the representation as separate failure surfaces: a
  record template substitutes into `record_field.type` and `record_field.value_param`, both of which already
  exist and are already produced, so what is missing is whole-declaration open-form recording,
  materialisation, arity and kind checking, recursion with knot-tying, and the closed-entry check. Still
  this implementation's own, beyond the CR's letter:
  - **A termination guard.** Non-regular (polymorphic) recursion like
    `weird => <T> { next: weird<[T]>? }` / `use => weird<text>` grows its argument every level
    (`text` → `[text]` → `[[text]]` …). Every instantiation is structurally distinct, so
    dedup-by-identity never fires and materialisation never terminates. Distinct from
    `SPEC-FEEDBACK.md` #25 (non-*productive* recursion — no finite *data* model): this is no finite
    *type* model. The CR's §4.5 covers regular recursion (knot-tying) but is silent on this —
    arguably a sentence the CR should gain.
  - **Materialisation must replace the eager rejection, not merely remove it.** With heads resolving
    type-name-only there is no namespace check left to make, so `SchemaDesugarer.rejectTemplateApplication`
    fails on any head this document declares *or imports* — a template arriving by `!!import` included.
    Deleting that without materialisation in place regresses to a read-time failure. Tranche B retires it
    for record templates only; an application of a template containing a sugar form keeps failing until C.
- [ ] **Instance templates — `spec/tson-cr-structure-templates.md`, Tranche C (D7, D9).** A sugar form
  inside a template declaration, which needs both a grammar addition and an intermediate vocabulary.
  The obstruction is not the type slot — a `type_ref` may already name a parameter — but the *value* slots:
  `ArrayBody.minItems` is `Optional<BigInteger>`, so `!array { min_items: N }` cannot be an array body at
  all. The CR's answer is `template_instance` (a constructor plus a `{field_name => template_argument}`
  binding map) as the open counterpart of an instance, with `template_argument = param | value | type_ref`;
  an instance admits no partial bindings, so the closed bodies are untouched and `min_items: "two"` becomes
  the error at the materialising application. Take `[T]` first — the unsized inline form, whose only
  parameter rides a type slot — then the sized and nested forms, which are what actually need `param`.
  Also here: D9's `[type-params] instance` (today `TsonSchemaParser.parseTypeDef` checks `!` *before*
  `parseTypeParamsOpt`, faithful to the ABNF as written), and open-synthetic identity up to consistent
  parameter renaming.
  - **The CR's own §8 fixtures do not parse** and need respelling before they can be acceptance tests:
    `tree => <T> { value: T  children: [tree<T>; 1..] }` and `grid => <T, N> { x: [[T; 1..N]; 2..N] }` both
    put a size specifier at a *field* position, which §5.3's inline prohibition rejects — a prohibition the
    CR preserves. Whether that prohibition relaxes at a field position is the CR's own open item (§9), and
    it decides how much of this tranche is reachable: with it intact, an open synthetic can arise only from
    a declaration whose whole body is a container form. `SPEC-FEEDBACK.md` #31 is the pending entry in the
    area and is expected to carry the resolution in a later revision — though as drafted it preserves the
    prohibition rather than relaxing it, so it needs a paragraph before it settles this. Not a blocker for
    Tranche B either way.
- [ ] **The rest of §8.2's deferred value-level checks.** Materialisation "runs the value-level checks that
  open bounds deferred: family coherence rules whose operands were parameters". The array family's
  `min_items <= max_items` is one rule over the binding pair for arrays *and maps*: a resolver error where
  the bounds are literal at schema load, at materialisation where parameter-bound. The literal half is done
  for both tiers (`SchemaDesugarer.checkBounds`); the parameter-bound half needs a size specifier inside a
  template, which is Tranche C. The
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
  — "ingest" doesn't appear anywhere in the codebase. Note `spec/tson-cr-structure-templates.md` §4.7
  extends what ingest must reverify: the closed-entry rule gains "no `value_form` members", and synthetic
  entries must pass under the existing integrity checks. Note it would introduce a *second* way to build a
  `TsonSchema` — bound from a document rather than resolved from source — and the two would have to agree,
  including on where a declaration's annotations land (the name's on the map key, the definition's on the
  entry). Lower priority than the rest of this section: the spec marks this path explicitly **optional**
  ("MAY implement ingest"), not a MUST.

## Remaining Part 2 resolution gaps

§5.10 substitution exists now (`TemplateMaterialiser`), so what is left here is narrower than it was.

**Done:** a generic type-ref whose arguments are not simple names. A value argument and a nested
application both resolve, at every position that can write one — a field, a declaration body, and a
refinement source share `DefinitionResolver.typeArgument`. An *inline sugar form* as an argument
(`weird<[T]>`) is still out, but only because sugar inside a template is Tranche C.

**Still open**, and each has its own item below or above: composing with a template application, now
blocked on ordering rather than on substitution; and the field/element catch-all, whose two live shapes are
both inside a template. The second has measured detail worth keeping:

- [ ] **Composing with a template application** (`vip => box<text> & { ... }`, and its parameterized twin
  `vip => <T> customer & box<T>`, §5.8's "Parameterized references"). Substitution is no longer the blocker;
  **ordering** is. Composition resolves its supertypes during resolution, in `DefinitionResolver`, while
  materialisation runs after the driving loop — so the entry `box<text>` denotes does not exist yet when
  composition needs to absorb its fields. Closing it on demand from `SchemaResolver`'s namespace getter is
  the obvious shape and is not obviously safe: that getter is the memo the circular-composition check rides
  on. The message today is accurate about the symptom and wrong about the cause — it says "parameterized
  supertype" for a fully-bound one.
- [ ] **A field/element type that is not a simple name, a generic application, or an inline array.**
  `DefinitionResolver.resolveTypeRef`'s catch-all ("only simple (non-generic) type-refs, generic
  applications of one, and inline arrays of one are resolved so far"). **Only reachable inside a
  parameterized declaration** — `SchemaDesugarer` normalizes every inline form in an ordinary field or
  group-member position first, so `[[text]]`, `[{text => integer}]`, `(text | integer)`, `[text, integer]`
  and `{text => [integer]}` all compile today, in both positions. The phase skips a parameterized
  declaration entirely, and that is where the
  throw is live, in two shapes measured directly:
  - `box => <T> { v: [T] }` — the `InlineArrayRef` branch *does* fire and builds `array<T>`, then the linker
    rejects `array`, which a user schema's type-name namespace does not hold. The author is told their
    schema has an unresolved reference to something they never wrote. Under the CR this failure mode
    disappears by design: a template body's `[T]` becomes an open `!array` form carried via `value_form`
    (D5/D7), never a type-name reference to `array`.
  - `pair => <T> { v: (T | text) }` — `ChoiceRef` hits the catch-all, and an `UnsupportedOperationException`
    reaching the CLI is reported as `internal error ... This is a bug in tson` with exit 70. It is a gap,
    not a fault, so even before the feature lands the classification is wrong (see the exception policy in
    CLAUDE.md).
  Both live shapes are inside a template, which is what makes this Tranche B work: the CR's open-form
  recording (D5) is precisely "desugar a parameterized declaration without lifting", the thing the phase
  skips today.

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

## Schema-side diagnostics

Parsing, desugaring, resolution and linking all report every independent problem in one pass through a
`TsonDiagnosticsReceiver` (issues #3/#28/#29), whether reached by `tson compile`/`Tson.validateSchema` or by
a *data* read whose `!!schema` names a schema that doesn't resolve — both give the same account of the same
broken schema. `docs/readers-and-diagnostics.md`'s "Schema-side diagnostics" section describes the shape and
the decisions behind it. **What has shipped is good enough for now**, which is why this whole section sits
here: the two items below are refinements of a working two-ended diagnostic — how finely it locates itself,
and whether it should carry more than one location at all — not gaps in what it reports. The *floor* under
all of it — the lexer being fail-fast, so a token that will not lex aborts the pass that would have reported
past it — is deliberately not an item in this list, because there is no work to do until someone decides
whether lexer errors feed the `Diagnostic` model at all. `STRUCTURED-OUTPUT.md` holds that question.

- [ ] **`schemaPosition` is one level coarser than the pointer beside it.** A read diagnostic locates
  `/person/age` but positions it at `person`'s own declaration line, because positions are per declaration
  (from the declaration's own name token) and `RecordField` carries none. Closing it means giving
  `TsonSchemaParser` per-field positions and threading them onto `RecordField` — a `schema.meta` bind target,
  so the `@Record` constructor-selection trap and the hand-written `equals` both apply. Nothing in the reader
  stack changes: `SchemaLocation` already carries the pointer that names the field. Same gap for a supertype
  or a choice variant. A *syntax* error is the one exception — it has the failing token's own position, since
  the parser reports it where it stands rather than looking it up per declaration afterwards.
- [ ] **A `caused by` frame, for when the author's location is not the whole story.** A read diagnostic now
  locates the rule where the author can act on it (`/person/age` in their own schema) rather than at the leaf
  the constraint came from (`/int32` in core.tn). That is the right primary frame, but the leaf is genuinely
  informative for a *confusing* error — a deep composition, a refinement chain, a type whose bound is not
  obvious from the field's own line — and it is currently recoverable only from `message`/`expected` prose.
  - The shape to explore is a chain rather than a second flat pair: the primary location, then zero or more
    `caused by` frames each carrying the same four location components, the way rustc's `MultiSpan` and JSON
    Schema's nested `errors` both do. `Diagnostic`'s own Javadoc already cites the first of those as the
    model this type follows.
  - **Which suggests an extended output mode**, rather than making every diagnostic bigger: the default stays
    one frame, and a caller that finds an error confusing asks for the chain. That is a CLI surface question
    (`--explain`? a verbosity flag?) as much as a model one, and it interacts with `diagnostics.tn` being a
    versioned schema — a new frame list is a shape change, so §10's immutability rule means `diagnostics-2.tn`.
  - **The input already exists and is deliberately kept for this.** `TsonLinkedSchema.entryOrigins` answers
    "which document declared this entry", and every reader is already handed its own declaration's location
    (`ValueReaderContext.locationOf`) — today only used as the seed for a value nothing encloses. A caused-by
    frame is what would consume it in the ordinary nested case.

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
