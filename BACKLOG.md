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
callers hand-sequencing registration themselves. What follows is what's missing for the *general*,
spec-required case, found by re-auditing Part 2 against the current source rather than CLAUDE.md's
own prose (which had gone stale on it):

- [ ] **Automatic reference-closure resolution** ([TSON-DATA] §2.2.3, [TSON-SCHEMA] §3.4.1) — no code
  collects a schema's transitive `!!meta`/`!!import` closure, topologically orders it, and resolves it
  dependencies-first; every caller (including this session's own `TinySchemaImportsCoreTn1Test`) has to
  already know and hand-sequence the correct registration order itself. Distinct from what
  `TsonCompiledMetaRegistry.withStandardLibrary` already does, which is scoped to just the three bundled
  schemas in a known order, not a general algorithm. Cycle detection is available to build on:
  `resolveLinked` holds a per-thread in-flight set reporting §2.2.3's cycle by the path that closes it.

## Checked annotations

[TSON-SCHEMA] §6 defines the category and §5.4's `@disjoint` is the precedent both follow: an annotation with
**no** decode force and load-time force, verified against a fact the resolver derives, two outcomes and no
third — verified silently, or a resolver error at schema load. §6 also settles what this implementation had to
guess at: a checked annotation is an assertion in *either* declaration position and a processor MUST consult
both spellings, which is what `@disjoint` already does. Each of the two below is declared in meta.tn and
neither is checked, so both are advisory today where §6 says they carry force. Both are also re-checked on
ingest (§8.1), which is a second call site for whatever the load-time check becomes.

- [ ] **`@discriminator` is not checked.** Three checks at schema load, over the choice the annotation marks
  (§6): every variant is a record declaring the named field; that field is `REQUIRED_FIXED` in every variant,
  never `REQUIRED_DEFAULT`; and the fixed values are pairwise distinct. The annotation's own type does the
  fourth — `field_name` is an `identifier`, so a non-name spelling already fails where the annotation value is
  read. Nothing about a value's validity moves: a discriminated choice admits exactly the variants it admitted.
- [ ] **`@rest` is not checked.** Two checks: the annotated field's type resolves to a text-keyed map, and at
  most one field per composed chain carries the mark — the chain being countable since §5.8's restated-field
  rule merges annotations rather than dropping them, which this implementation already applies.

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has the two schemaless writers and the
push emitter (`TsonDataEmitter`, the write-direction peer of `TsonDataStream`) and is missing the rest of
the mirror. What is left below is the schema-aware writer and diagnostics.

- [ ] **Key-position annotations are lost on the resolved-form round trip.** A schema *source* carries them
  through now: §6's name-position channel — `@doc` before a declared name, and the resolver's own derived
  `@synthetic` — reaches `TsonSchema.entries()` as key annotations (`AnnotatedMap`) and survives
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

- [ ] **The rest of [TSON-DATA] §9.1's resource limits, and [TSON-SCHEMA] §11.5's.** `TsonLimitsPolicy` is
  the policy value and carries nesting depth at §9.1's own default of 64. §9.1 now states the whole set as one
  table with a default each, so nothing here is a judgement call any more — what is left is eleven document
  limits and five schema-side ones, each a component on `TsonLimitsPolicy`, a `CliPolicy.CliLimits` field and a
  `--flag`. Document side: **token length** (1,048,576 code points), **decoded text length** after escape
  processing (1,048,576), **numeric literal length** (4,096 digits, annotated tokens included), **decoded
  binary size** per `!bytes` value (16,777,216 octets), **document size** in bytes (16,777,216), **elements**
  per array or set (1,048,576), **entries** per map (1,048,576), **fields** per record (65,536),
  **annotations** on one value (64), **total values** in one document (16,777,216), and **foreign schemas** one
  document's scope pushes may load (16). Schema side (§11.5, same policy and same reporting surfaces):
  **import closure** (64), **entries** in one schema map (65,536), **reference chain** (64), **supertype
  chain** (64), and **materialisation depth** (64) — which is where `TemplateMaterialiser.MAX_CLOSING_DEPTH`
  goes, it being a bare constant with nowhere to live until now. What still needs deciding per limit is only
  *where it is counted*: the ones that bound shape are per-container state the stream does not keep, where
  depth was a counter it already had, and the two aggregates (total values, foreign schemas) need their own
  counter since §9.1 is explicit that the total is not bounded by the parts.

- [ ] **`scripts/restamp-bundled-schemas.sh` does not cover the spec's own §13.2 table.** The script moves
  every pin in the repo bottom-up — the three `spec/m/*.tn` headers, `TsonBundledSchemas`, `InitCommand`,
  `README.md` and the getting-started example — and `--check` reports staleness across all of them. It does
  not know about `spec/tson-part2-schema.md` §13.2, which pins the same three digests, so that table is the
  one pin a schema edit leaves behind and the only one whose drift nothing reports. It drifted once already.
  Teaching the script to stamp it (or at least to `--check` it, leaving the write to the spec author) is a
  few lines against the same digest computation, and makes CI able to catch what a hand edit currently must.
  The wrinkle worth deciding first: `spec/` is a cache this repo otherwise only reads, so writing into it is
  a small change to what the script is for — `--check` alone may be the honest scope.

- [ ] **`time` and `datetime` compare by offset, where [TSON-SCHEMA] §5.5 makes them instants.** The
  value-space clause settles the equality contract the series used to delegate without defining, and it decides
  this family against what is running: a `datetime` is the instant on the UTC timeline and a `time` is the time
  of day in UTC, so `2026-01-01T10:00:00+01:00` and `2026-01-01T09:00:00Z` are **one value**, `-00:00` is `Z`,
  and `23:30:00-02:00` is `01:30:00Z`. `ValueIdentity.of` falls through to Java equality for both, and
  `OffsetDateTime.equals`/`OffsetTime.equals` compare the offset — so all three rules that delegate to value
  identity are wrong here at once: a map admits both spellings as two keys (§2.6), a set admits both as two
  elements (§7.5), and a `REQUIRED_FIXED` field written in another offset is rejected (§5.2). Ordering is
  already right and needs nothing: both host types' `compareTo` compares the instant. The fix is one case each
  in `ValueIdentity.of` — normalise to the instant before comparing — and it must not touch what a reader hands
  back, since §5.5 has TSON text preserve the offset as written.

- [ ] **`class2/schema/` carries no vector declaring a template, and the reason it could not is gone.**
  [TSON-SCHEMA] §8.1 now says an open entry is a `type_definition` like any other — `parameters` non-empty,
  `body` the held application in wire form under §5.10's one-spelling rule, typed by the kernel's `schema`
  without a second value shape — which is exactly the shape this resolver holds (`TemplateBody`/`HeldBody`).
  The two sides no longer disagree as values, so the layer can compare a template the way it compares
  everything else and the corpus can state what one resolves to directly rather than indirectly at `link/`.
  `ResolvedForm.heldBodies` is the comparison to keep — §8.1 makes wire form what a held body *is* on both
  sides, not a compromise — and what is owed is the vectors, upstream, plus the note in `CONFORMANCE.md` that
  currently explains the absence.
