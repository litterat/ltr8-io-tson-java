# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

**This file is a clean list of outstanding work and nothing else.** Every entry must name something someone
could pick up and do. Three things are therefore not entries, however true they are:

- **What was done.** An item that ships comes out entirely — not annotated as complete, not kept as a record
  of how it was solved. Git history is the log.
- **What was decided against.** A won't-do is not work. It comes out too.
- **What might become work later.** A standing note to revisit something if conditions change is not an
  outstanding item; nobody can act on it today, and it sits in the list forever looking like a task.

Where any of those has to survive its entry — a won't-do someone would otherwise re-propose, the why behind
a shipped design, a condition that should trigger future work — it belongs in the `docs/` note, the Javadoc,
or the test that owns the area, where the person who trips over it will actually be looking. Not here.

Prose inside a live entry follows the same rule: say what is left to do and what constrains it. Recounting
which halves already work turns an item into a status report, and it goes stale silently.

**And an entry filed as a gap must be one.** A throw that means *your schema is wrong* is not a gap however
it is spelled, and neither is one that means *an invariant broke*. The test is the same one the exception
policy uses: a schema error's verdict does not change when this library improves; a gap's does. It is worth
stating here rather than over one section, because the CLI's exit 1 against its exit 70 rides on the
distinction, and a misfiled entry is how a wrong classification gets adopted rather than noticed.

---

## Resolution & linking generality

Every real schema resolved so far (meta-kernel, meta.tn, core.tn, and hand-built test fixtures)
happens to fit a narrow shape this pipeline already handles — declared in dependency order, with
callers hand-sequencing registration themselves. These items are what's missing for the *general*,
spec-required case, found by re-auditing Part 2 against the current source rather than CLAUDE.md's
own prose (which had gone stale on at least one of them):

- [ ] **Automatic reference-closure resolution** ([TSON-DATA] §2.2.3, [TSON-SCHEMA] §3.4.1) — no code
  collects a schema's transitive `!!meta`/`!!import` closure, topologically orders it, and resolves it
  dependencies-first; every caller (including this session's own `TinySchemaImportsCoreTn1Test`) has to
  already know and hand-sequence the correct registration order itself. Distinct from what
  `TsonCompiledMetaRegistry.withStandardLibrary` already does, which is scoped to just the three bundled
  schemas in a known order, not a general algorithm. Cycle detection is available to build on:
  `resolveLinked` holds a per-thread in-flight set reporting §2.2.3's cycle by the path that closes it.
- [ ] **The rest of §8.2's deferred value-level checks.** Materialisation "runs the value-level checks that
  open bounds deferred: family coherence rules whose operands were parameters". The array family's
  `min_items <= max_items` is one rule over the binding pair for arrays *and maps*, and nothing compares a
  *pair* of bounds once both go concrete at materialisation. The kin §8.2 gestures at — "bounds within a
  width-derived range, and their kin" — belong with the constraint families that own them, next to
  `AtomNarrowing`, not in a syntax rewrite. Doing that properly probably
  means the check moves out of the desugarer entirely and `checkBounds` goes with it. Distinct from the
  atom-body self-coherence item below, which shares that destination but has no parameter or
  materialisation dimension at all.

## Open form: the held template body

An open entry's body is the constructor application as written, held unread until materialisation
substitutes its parameters away — `docs/schema-resolution.md` describes it, `SPEC-FEEDBACK.md` #5 and #7 are
the proposals it answers. What is left below are consequences of holding, not shapes outside it.

- [ ] **A parametric enum member is classified as a type argument and fails.** `e => <M> !enum { members:
  [a b M] }` applied as `e<c>` reports `'e<c>' source has an unresolved reference 'c'`: an unquoted
  non-numeric argument rides the reference channel (§12.1's own `type-arg` rule) and `c` is an enum member,
  not a type. §5.10 settles a parameter's kind from its *use*, and an enum member position is a use nothing
  recognises as a value channel. Same root as the argument-kind finding above — a held body has no slot
  types — so the two want settling together. `SPEC-FEEDBACK.md` #5 now carries it as the one position where
  holding gives a *wrong* verdict rather than a late one, and names the two spec-side answers: make
  `enum.members` a value channel in §5.10's kind table, or require the quoted spelling `e<"c">`. Which one
  lands decides what is built here, so this waits on the revision rather than on effort.
- [ ] **Two entries for one type, where both lift channels produce the same form.** A closed lift hashes the
  *unclosed* binding record at desugar; the open lift hashes the *closed* one at materialisation — so
  `[box<text>]` written directly and `[box<T>]` closed with `T := text` land on different names. D6
  anticipates exactly this ("identity is settled after Pass 2 ... eagerly-lifted synthetics that become
  structurally identical under resolution merge into one entry") and that merge pass is not implemented; it
  never had to be, because every form lifted before `[box<text>]` was already concrete at desugar. Down here
  on the spec author's own call: two entries are easier to debug than a merge firing at the wrong moment, and
  it is reachable only when both spellings appear in one schema. Doing it properly means a pass at the end of
  resolution that re-derives each synthetic's name from its resolved record and merges collisions — not a
  patch to naming. **Re-derive from resolved references only, leaving value tokens as written**: the two
  splits live in different channels of one derived name, so a pass that normalised the whole resolved record
  would reach into the value channel `SPEC-FEEDBACK.md` #4 owns. **Disclosed in `SPEC-FEEDBACK.md` #5**, which asks Revision 34 to say whether D6's merge
  is required or incidental — an implementation reading it as an optimisation skips it and gets the second
  entry. The simple case does agree and is pinned
  (`ContainerSugarEndToEndTest.aFormClosedFromATemplateIsTheSameEntryADirectOneProduces`): only an element
  that is itself an application splits, the closed lift hashing the binding record before its inner
  application is rewritten.

## Built-in types

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
- [ ] **`precision` and `require_timezone` are carried but not enforced** (`datetime`/`time`). The bodies
  declare them — a field with no component is one this model silently loses — and the parsers refuse to
  *read* against a schema that sets either, so the facet is a stated gap rather than a constraint quietly
  not applied. The refusal is at read, not at load: such a schema resolves, links and compiles clean, and
  the first document to reach the field gets `ErrorReader`'s gap and exit 70 — so it lands on whoever sends
  data, not on the author who wrote the facet. What
  remains is enforcement, and both halves wait on a spec answer rather than on effort: `SPEC-FEEDBACK.md` #9
  asks what `precision` bounds (exactly N fractional digits or at most N, at the token or the value, reject
  or truncate) and whether `require_timezone` can mean anything beside a `spec` fixed to RFC 3339, which
  requires the offset on every value these atoms accept. Which answers land decide what is built here.

## Schema-side diagnostics

Parsing, desugaring, resolution and linking all report every independent problem in one pass through a
`TsonDiagnosticsReceiver` (issues #3/#28/#29), whether reached by `tson compile`/`Tson.validateSchema` or by
a *data* read whose `!!schema` names a schema that doesn't resolve — both give the same account of the same
broken schema. `docs/readers-and-diagnostics.md`'s "Schema-side diagnostics" section describes the shape and
the decisions behind it. The items below are refinements of a working two-ended diagnostic — how finely it
locates itself, and whether it should carry more than one location at all — not gaps in what it reports,
which is why they sit low. The lexer's fail-fast floor is not among them: nothing is actionable there until
someone decides whether lexer errors feed the `Diagnostic` model at all, and `STRUCTURED-OUTPUT.md` holds
that question.

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
    versioned schema — a new frame list is a shape change, so §10's immutability rule means the next version
    under a new name, never an edit in place.
  - **The input already exists and is deliberately kept for this.** `TsonLinkedSchema.entryOrigins` answers
    "which document declared this entry", and every reader is already handed its own declaration's location
    (`ValueReaderContext.locationOf`) — today only used as the seed for a value nothing encloses. A caused-by
    frame is what would consume it in the ordinary nested case.

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has only the two schemaless writers and is
missing most of the mirror. What is left below is the schema-aware writer, diagnostics, and a public event
surface.

- [ ] **Key-position annotations are lost on the resolved-form round trip.** A schema *source* carries them
  through now: §6's name-position channel — `@doc` before a declared name, and the resolver's own derived
  `@alias`/`@synthetic` — reaches `TsonSchema.entries()` as key annotations (`AnnotatedMap`) and survives
  linking and the import merge. The *document* round trip is what does not: reading a resolved-form
  `{type_name => type_definition}` document back binds the map with no key annotations at all, and nothing
  writes them. `ResolvedFixtureTest` therefore cannot compare the marker the way it compares everything else
  — the Revision 33 fixtures carry `@synthetic` on nine keys and `@doc` on many more, and the bound side
  renders none of them, so the entries would compare equal for the wrong reason;
  `theSameEntriesAreMarkedSyntheticOnBothSides` scans the fixture text instead. Fixing the read side lets that
  test read those keys like anything else, which is the whole of the payoff — `ResolvedFixtureTest` is the
  only consumer, and the emit side behind it has none. §8.1 settles the shape either way: derived markers
  discarded and recomputed, author-written key annotations preserved as data.

- [ ] **No schema-aware (Class 2) writer — `TsonValueWriter`.** Only the schemaless `TsonObjectWriter`
  (object → TSON) and `TsonTreeWriter` (`TsonValue` → TSON) exist, both with documented lossy spots
  (integer width, tuple-ness). A writer symmetric to the
  compiled reader stack (`TsonSchemaCompiler`/`TsonTypeReader`) — checking output against a TSON schema
  and reporting what's wrong — is a whole missing half of the pipeline, and the natural home for
  round-tripping or producing guaranteed-conformant documents.
    - It is also where `describing(schemaUri, rootType)` stops needing its arguments. A bind-mode registry
      already holds the compiled schema and the class→type binding, so a schema-aware writer could derive
      both facts instead of having the caller name what the library already knows. The explicit form stays
      either way — a caller writing against a schema it did not compile here has nothing to derive from.
- [ ] **Writers are fail-fast only, no diagnostics.** They throw `TsonWriteException` at the first
  problem, with nothing symmetric to the read side's `TsonDiagnosticsReceiver`. The `TsonValueWriter`
  above especially needs it, to report every schema violation in one pass the way the reader does — and
  the seam already exists and is write-direction-agnostic (`Diagnostic` carries a data path and both
  positions; nothing about `void report(Diagnostic)` assumes reading), so this is a matter of threading a
  receiver through the emitter, not designing a second error model.
- [ ] **No public push/event writer.** The read side exposes a pull `TsonDataStream` (→ `TsonEvent`);
  the only emitter, `TsonDataEmitter`, is internal. A public event-driven writer would let a caller emit
  TSON without first building a whole tree or object — the write-direction peer of `TsonDataStream`. Closer
  than it was: the emitter now writes into any `Appendable`, so what is missing is the decision to make it
  (or an event-shaped facade over it) public API, not the streaming underneath.
- [ ] A JSON writer (TSON data → valid JSON text) — the write-direction companion to
  `STRUCTURED-OUTPUT.md`'s "JSON compatibility" section, tracked here alongside the general writer
  since it's the same underlying gap (no schema-aware writer exists at all yet).

## Conformance test suite

- [ ] Build out `ltr8-io-tson-test-suite` well beyond its current 127 vectors, spread across four buckets
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
- [ ] `@doc`-driven documentation generation (render a schema's own `@doc` annotations). The renderer is the
  whole of it — the data is reachable as
  `schema.entries().getAnnotations(name).value("doc", String.class)`, and core.tn documents every declaration.

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

- [ ] **General resolver-layer structural rules as reusable primitives**, rather than binding-time-only
  behaviour — empty-brace resolution, the absent-vs-missing distinction. §2.8's "the empty container of that
  type" is still a rule each container reader applies for itself: the map reader's own zero-entry case was
  silently exempt from `min_items` until it was fixed one reader at a time, and nothing structural stops the
  next container from repeating it. What a primitive would buy is the rule stated once, where "how many
  entries does this value have" has one answer whatever spelled it.

- [ ] Thread-safety **outside a read**. Concurrent *reads* through one `Tson` are safe and tested
  (`ReadPathConcurrencyTest`): the compiled readers are immutable, a `Lexer`/`TsonDataStream` is per read,
  and the two on-demand caches — the schema registry and `DataBindContext`'s descriptors — now settle a race
  by keeping one entry instead of failing the loser. A read now also takes **no lock on the caches it hits**
  (`TsonSchemaRegistry` is a `ConcurrentHashMap` read without a monitor; the compiled cache does a `get`
  before `computeIfAbsent`), which measured small here — ~6% at 32 threads on 16 CPUs, nothing below that —
  and matters at a core count this machine does not have. What is still open is deliberate mutation while others
  read: `Tson.resolve`/`TsonSchemaRegistry.register`/`registerAtom` stay strict about duplicates (right for
  a caller error, wrong if two threads are legitimately warming the same registry), nothing defines whether
  a `DataBindContext` may be extended after first use, and neither shipped fetching `TsonSchemaSource` states
  what it promises a concurrent caller. None of it is hypothetical-only: the read-path half was two real defects, found by
  auditing and reproduced first try on 8 threads.
- [ ] Confusable-character and bidi-formatting-character checks (§9.4-adjacent security hardening;
  opt-in, and reported as ordinary errors when enabled — §8.1 gives a conforming processor one severity) —
  the sibling gap to the numeric-literal length limit tracked in `STRUCTURED-OUTPUT.md`'s Tier 1 section;
  neither is enforced anywhere yet. `SPEC-FEEDBACK.md` #3 is the fuller treatment: which UTS #39 mechanism
  applies where, the comparison scopes TSON can actually name, and why a normative requirement would oblige
  every implementation to ship UCD data the JDK does not expose.
