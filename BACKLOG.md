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

## Open form: the held template body

An open entry's body is the constructor application as written, held unread until materialisation
substitutes its parameters away — §5.10's "Held bodies", with §8.1 giving the output form and §8.3 the open
alias. `docs/schema-resolution.md` describes the implementation. What is left below are consequences of
holding, not shapes outside it.

- [ ] **A parametric enum member is classified as a type argument and fails.** `e => <M> !enum { members:
  [a b M] }` applied as `e<c>` reports `'e<c>' source has an unresolved reference 'c'`: an unquoted
  non-numeric argument rides the reference channel (§12.1's own `type-arg` rule) and `c` is an enum member,
  not a type. §5.10 answers it outright — an argument is "substituted as a token and read by the position it
  lands in", and "in an enum's member list it is a member", with this exact application as the spec's own
  example — so the fix is to stop deciding an argument's channel at the application and let the position it
  lands in decide, which is what a held body already makes possible. The one position where holding gives a
  *wrong* verdict rather than a late one.
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
  would reach into the value channel §8.2 keeps as written. **§8.2 makes the merge required, not an
  optimisation**, and names this exact split — `[box<text>]` written directly against `[box<T>]` closed with
  `T := text` — so this is no longer a judgement call. The simple case does agree and is pinned
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

- [ ] **A name-hygiene refusal reports as `SCHEMA_ERROR` on the schema side and `CONFUSABLE_NAMES` on the data
  side.** `Diagnostic.Code.CONFUSABLE_NAMES` is emitted for exactly one scope — a Class 1 record's own field
  names, where `SchemalessTreeReader` checks them because no declaration stands behind them. Every schema-side
  equivalent, the confusable check and the `TsonUnicodePolicy` restriction level alike, goes through
  `TsonSchemaLinker`'s `report` and comes out `SCHEMA_ERROR`. So one defect carries two codes depending on
  whether a schema governs the document, and on the schema side a caller cannot tell a spoofing refusal from an
  ordinary schema error. Both halves want a code that says which rule fired — the confusable relation and the
  restriction level are different rules with different remedies, and neither is a statement that the schema is
  malformed. [TSON-DATA] §8.1 now makes the distinction normative: a policy refusal is "a fifth,
  distinguishable outcome" and MUST NOT be reported in any of the four error categories, and §8.2 requires the
  refusal to name the UTS #39 data version. So this is a conformance gap, not a polish item.

- [ ] **A supertype and a choice variant still have no position of their own.** A record field carries one
  now (`RecordField.position`, `@Unbound`, threaded through `SchemaPositions`), so a diagnostic against
  `/person/age` lands on `age`'s line. A supertype and a choice variant are bare names in a `List<String>`
  with nowhere to hang one, so a problem against either is still located at the enclosing declaration.
  - The parser side is a third identity-keyed table in `SchemaPositions`, which is the shape it was given
    for this; the awkward half is the destination, since neither is a record with somewhere to put it.
  - Whatever carries it must be carried across a rebuild the way `RecordField.withType` is — §8.3's
    flattening walk rebuilds bodies wholesale, and a dropped position is invisible to every test, position
    being excluded from equality.

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
    versioned schema — a new frame list is a shape change, so it lands in place if no release has published
    the current `!!id`, and under the next version if one has.
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
  — the fixtures carry `@synthetic` on nine keys and `@doc` on many more, and the bound side
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

## Conformance test suite

The corpus states its own contract now: `schemas/*.tn` (field groups, so an outcome cannot appear
without its payload), `RUNNER.md` (normative for runners), and a `class1/`/`class2/` split. Both
implementations pin it to a commit, and this repo runs it as a gating CI step. `class1/` covers the
lexer, the parser, base type resolution, the built-in vocabulary and the reader; `class2/` covers schema
resolution, linking and validation, one layer each. `COVERAGE.md` is generated and diff-checked, so what
is thin is a query against the corpus rather than a tally kept here.

- [ ] **The `class2/link/` cases that need a second schema document.** §2.2.3's collision rules are the
  half the layer cannot state today: one schema reached by several routes unifying, two schemas declaring
  one name being an error, and nothing shadowing a name the closure already binds. Each needs the subject
  to `!!import` a schema that is not one of the bundled three, and the corpus's layout admits exactly two
  files per vector — a subject and its sidecar — so there is nowhere to put the imported document. Either
  the layout gains a per-layer fixture directory the sidecar's `import` short names resolve against, or
  the corpus publishes a small schema of its own beside the sidecar schemas and the short-name table
  learns it.
- [ ] **§8.2's mechanism 2 reports a policy refusal as a validity error.** `IdentifierParser` applies
  §7.7's grammar and §8.2's `Identifier_Status` check in one pass and fails both the same way, so
  `@aĲb` — a well-formed identifier carrying a `Identifier_Status=Restricted` character — comes back as a
  parse failure and reaches a reader as `VALIDATION_ERROR`. §8.2 says outright that a refusal MUST NOT be
  reported in any of §8.1's four categories, and being distinguishable is the entire reason the outcome
  exists: the check reads data the UCD does not freeze, so it may never decide validity. The two checks
  have to separate, and the refusal needs a channel out of `TsonDataStream`, which throws rather than
  reporting — that is the work, and it is why the corpus's `identifier-status` mechanism has no vector.
- [ ] **§8.2's mechanism 3 is not applied to Class 1 names.** The restriction level rides `withTokenPolicy`,
  whose default is Unrestricted — right for tokens, which §8.2 says default to Unrestricted, and wrong for
  names, which it says default to Highly Restrictive. So a mixed-script annotation or type-ref name is
  accepted by default where §8.2 refuses it. The schema layer already gets it right
  (`TsonCompiledMetaRegistry.identifierPolicy`); what is missing is the same policy applied where Class 1
  data carries a name. Blocks the corpus's `restriction-level` mechanism, which likewise has no vector.

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

- [ ] **A record field written `_` reads identically to one never written.** Under a schema, `{ x: _  y: "h" }`
  and `{ y: "h" }` against `x: text?` both produce a tree with no `x` at all; the same pair read schemalessly
  gives `TsonAbsent` and `TsonMissing`. [TSON-DATA] §2.9 makes the distinction normative — "A field or entry set
  to `_` is **present with an absent value** — distinct from not appearing at all" — and an array element and a
  tuple slot already keep it, as a `TsonAbsent` placeholder that round-trips back through `TsonTreeWriter`. So
  the record is the one container of the four that drops it: `valueForAbsentField`'s `OPTIONAL` case answers
  `null` for both readings and `RecordTreeReader.putField` omits a `null`. Bind mode needs its own answer first
  — a Java component has no third state between "null" and "not there" — since the tree's answer should not be
  the one that happens to be reachable.

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
