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

- [ ] **§4.2's value-route-only rule is not enforced** — a `~` constructor's parameters may occur only as value
  routes, and a type-channel one is a resolver error at the declaration. Nothing checks it, so a parameterized
  container constructor resolves, links and compiles, and the first symptom is a *read* failing with "'set' is
  a template taking 1 type argument": a diagnostic about a data document, for a mistake in a schema. A check
  written against the held body alone false-positives, `element_type: = T` and `max_items: = N` being spelled
  identically where the first is a type channel and the second a legal value route. **The distinction does
  exist in the pipeline**: `ParameterKinds` resolves the constructor head and walks each written slot against
  the field the constructor declares for it, classifying a parameter in `element_type` as `TYPE` and one in
  `max_items` as `VALUE`. What needs establishing is whether that walk reaches the declarations this rule is
  about — it starts from a held body, where §4.2 speaks about a `~` constructor's own declaration — and what a
  head it cannot resolve should mean.

## Checked annotations

[TSON-SCHEMA] §5.4's `@disjoint` is the precedent both follow: an annotation with **no** decode force and
load-time force, checked at schema load, two outcomes and no third — verified silently, or the schema fails to
load. Each is declared in meta.tn and neither is checked, so both are advisory today where the design says
they carry force. §6 owes the category itself a description (`SPEC-FEEDBACK.md` #25(a)) and owes which of its
two declaration positions honours one (#25(b)); this implementation consults both for `@disjoint` and should do
the same here.

- [ ] **`@rest` is not checked.** Two checks, both ordinary now: the annotated field's type resolves to a
  text-keyed map, and at most one field per composed chain carries the mark — the chain being countable since a
  restatement merges annotations rather than dropping them.

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
  — the fixtures carry `@synthetic` on the keys the resolver minted and `@doc` on many more, and the bound
  side renders none of them, so the entries would compare equal for the wrong reason;
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

## Miscellaneous

- [ ] **[TSON-DATA] §9.1's resource limits — and the `StackOverflowError` that escapes for want of them**
  (`SPEC-FEEDBACK.md` #33, which asks §9.1 for one policy of limits with defaults, reported the way §8.2's is,
  and for the shape limits it does not name at all — elements per container, fields per record, annotations
  per value, values per document, foreign schemas loaded per document).
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
