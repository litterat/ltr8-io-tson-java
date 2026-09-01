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
the decisions behind it. The item below is a refinement of a working two-ended diagnostic — how finely it
locates itself — not a gap in what it reports, which is why it sits low. The lexer's fail-fast floor is not
on the list either: nothing is actionable there until someone decides whether lexer errors feed the
`Diagnostic` model at all, and `STRUCTURED-OUTPUT.md` holds that question.

- [ ] **A supertype and a choice variant still have no position of their own.** A record field carries one
  now (`RecordField.position`, `@Unbound`, threaded through `SchemaPositions`), so a diagnostic against
  `/person/age` lands on `age`'s line. A supertype and a choice variant are bare names in a `List<String>`
  with nowhere to hang one, so a problem against either is still located at the enclosing declaration.
  - The parser side is a third identity-keyed table in `SchemaPositions`, which is the shape it was given
    for this; the awkward half is the destination, since neither is a record with somewhere to put it.
  - Whatever carries it must be carried across a rebuild the way `RecordField.withType` is — §8.3's
    flattening walk rebuilds bodies wholesale, and a dropped position is invisible to every test, position
    being excluded from equality.

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has the two schemaless writers and the
push emitter (`TsonDataEmitter`, the write-direction peer of `TsonDataStream`) and is missing the rest of
the mirror. What is left below is the schema-aware writer and diagnostics.

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
## Documentation

- [ ] User-facing documentation on how to use the library — today only `CLAUDE.md`'s own dense,
  session-oriented internal narrative exists.

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

- [ ] **[TSON-DATA] §9.1's resource limits — and the `StackOverflowError` that escapes for want of them.**
  Nothing bounds nesting depth, token length or document size. A document about 5,000 containers deep
  overflows the stack inside `TsonDataStream.fill`, and a `StackOverflowError` is an `Error`: it passes
  through every `catch (RuntimeException)` in the reader stack and in `TsonCli.run` alike, so `tson validate`
  on one prints a bare JVM stack trace to stderr, nothing to stdout, and **exits 1** — the code that means
  *your document is invalid*, which is the one verdict this case must not get. Depth is the half that is
  reachable from a request body and wants doing first; §9.1 asks for all three and asks that they be
  configurable, which puts the knob on `TsonConfig` beside the two Unicode policies and on the readers as a
  derivation, the way `withTokenPolicy` already is. A document past the limit must be refused with a
  diagnostic carrying a position, never a host `Error`. The numeric-literal length limit named in
  `CLAUDE.md`'s "Not yet implemented" is the fourth limit of the same section and comes with it.
