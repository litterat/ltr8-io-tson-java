# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation) — that's tracked separately since it's a
vision/plan document, not a plain punch list; the JSON encoding's own outstanding work is a section
below — and `CLAUDE.md`'s own "Not yet implemented" section for the technical detail behind several of
these items.

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

- [ ] **A source declaration may apply `!template` directly, and §8.1 says it must not** — the kernel's
  `template` constructor is resolver vocabulary: "nothing is ever typed by it, and a source declaration
  applying it directly is a resolver error — the parameter list `<…>` is the authored spelling of an open
  entry". This resolver accepts one. `sneaky => !template { parameters: [T]  template: "!array { element_type:
  T }" }` resolves without complaint, minting an entry whose held body was hand-written rather than derived
  from a `<T>` declaration, which sidesteps every declaration-time check §5.10 makes about a template (the
  unreferenced-parameter rule, regularity, arity against the constructor's own vocabulary). The refusal
  belongs with the other constructor-eligibility checks, where `requireApplicable` already asks what `!C`
  may be applied to.

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

- [ ] **`ResolvedForm` normalises the `position` component away by regex over rendered text.** The pattern is
  `position=Optional\[Position\[[^\]]*\]\]`, which matches the record's *default* `toString()` -- so giving
  `Position` a `toString()` of its own silently stops positions being normalised and diverges seven fixtures
  on a component §8's resolved form has no field for. The model has the component (`@Unbound`, this
  resolver's own) and the comparison should drop it structurally, before rendering, the way it already drops
  `kind`. `CLAUDE.md`'s traps list carries the hazard meanwhile.

## JSON encoding

[TSON-SCHEMA] §6 makes this a spec obligation rather than an interop nicety, and `meta.tn` states it directly: "No
encoding is privileged — TSON text is one member of the text class, beside JSON — so a directive binds every encoding
in its class, and a document in a directed encoding may not be readable without it." Two representation directives are
declared on those terms and neither has a consumer, because TSON text tags its choice variants with `!variant` and
never flattens — so it cannot exercise `@discriminator` or `@rest` at all. A JSON front end is what puts that half of
§6 under test, and is expected to move both: a directive with no consumer has never had its shape checked against one.

- [ ] **A JSON document cannot name the schema that governs it, so the front door and the CLI need a surface that
  does.** `!!schema` is TSON text syntax (`SPEC-FEEDBACK.md` #2, open): a JSON body has no in-band channel, so
  `Tson.validate(text)` and `tson validate`'s auto-classification — both of which read a header to decide what a
  document is — have nothing to read. Reading against a named schema already works
  (`withSchema(uri).readAs(text, typeName)`), so what is owed is the surface: which front-door and CLI forms take the
  schema identity and root type out of band, and what a JSON document naming neither gets. #2's own interpretation —
  the `TSON-Schema` header as a projection of the directive — is the channel a server would use, and is where this
  answer has to stay consistent.

**`tson-json` is a stack of its own, not a second front end over `TsonEventSource`.** Reusing the TSON event
contract would make one encoding's layering decide the other's at the two points where JSON and TSON genuinely
disagree — the same shape as the compatibility claim Revision 35 withdrew ([TSON-DATA] §1.1, §6). TSON text tells a
record from a map syntactically (`a: 1` vs `k => v`), so `TsonDataStream` emits `RecordStart`/`FieldName` or
`MapStart`/`MapArrow` and each reader asserts which it got; JSON's `{"a": 1}` is one syntax for both and §4.1 makes
the *position* decide, which a pull-only event source has no channel to say. And `null` is a value in a JSON tree and
the absent sentinel under a schema (§7), so a shared `TsonEvent` forces one meaning on the layer that does not hold
it. `CLAUDE.md`'s "Not yet implemented" already said this; the entries below follow it. The tree model follows
[JEP 540](https://openjdk.org/jeps/540)'s shape and names, so a consumer learns one API and a bridge to
`jdk.incubator.json` is later a mapping rather than a rewrite.

- [ ] **The identifier policy reaches no JSON name.** `JsonStream` applies the token policy to every token
  ([TSON-DATA] §8.2's second surface), and nothing applies the identifier policy at all — so a JSON member
  name that reads alike, carries a restricted character or mixes scripts is admitted where the same name in
  TSON text is refused, and the two encodings disagree about a rule §8.2 states once. The semantics to build:
  **the identifier policy applies to a record's member names and not to a map's keys** — §4.1 makes the
  position decide which a `{...}` is, so this cannot be answered where the name is read and has to wait for
  the schema-directed decode to say what the position is — and **the token policy applies to every JSON
  token and overrides the identifier policy where both reach one**, which is the rule the text encoding
  already follows. A map key is data, not a name, which is why it is exempt and why a JSON-Schema conversion
  does not hit a name rule on `additionalProperties`. The look-alike rule is a property of a *set*, so it
  belongs where `SchemalessTreeReader` puts it on the text side: over one record's member names, once.

- [ ] **No schema-directed decode — §5–§8.** The whole of what Part 3 actually specifies: atoms by their parsing
  contracts (§5), containers by their constructors (§6), JSON `null` as the absent sentinel (§7), and the
  discrimination predicate over the derived `disjoint` fact (§8). This is where `tson-json` gains its dependency on
  `tson-compiler` and where `@discriminator` and `@rest` get their first consumer. The reserved member namespace
  (`$schema`/`$type`/`$value`, §3.2/§3.3) and the annotation object come with it.

- [ ] **`Diagnostic.ofBaseSyntaxError` cannot classify another encoding's syntax failure.** Its switch covers
  `TsonParseException`/`LexException`/`TsonUnsupportedDocumentException` and rethrows the rest, so a JSON syntax error
  would escape as a fault — the please-report-it banner, exit 70 — where §8.1 makes it a verdict the sender can act on
  and `TsonCli.exitCodeFor` owes it exit 1. `TsonParseException` is `final`, so a front end cannot ride the
  classification by subclassing it, and `JsonParseException` is a separate stack's exception that deliberately does
  not extend it. What is left is choosing between a fourth case and a seam each encoding contributes to, and making
  the `expected: "well-formed TSON"` default name the encoding that actually refused.

- [ ] **Nothing dispatches a choice on `@discriminator`, and the JSON reader is what settles its shape.** meta.tn
  declares it as naming "the field a member-dispatching encoding selects a choice's variant on", with force in that
  class of encodings and none in the model, and states three load-time checks — but no encoding in the class exists,
  so neither the semantics nor the checks have been exercised by anything. A `choice`-typed position is unreachable
  from JSON without it, which makes the JSON reader the annotation's first consumer and the first real test of
  whether dispatch on a flat `REQUIRED_FIXED` field carries the cases JSON actually presents. **A change to the
  annotation is an expected output of this work, not a failure of it** — it goes to `SPEC-FEEDBACK.md` against the
  current revision, the same way anything else this implementation is first to exercise does. What the reader owes
  beyond the dispatch is the verdict for a JSON-facing choice carrying no mark. The three load-time checks are a
  prerequisite, tracked under "Checked annotations".

- [ ] **Nothing flattens on `@rest`, unexercised for the same reason.** meta.tn declares it for "an encoding that
  flattens" — the map-typed field a record's undeclared entries live in, a record being closed under its type
  ([TSON-SCHEMA] §7.2) — which TSON text never is. A JSON reader is where an undeclared member either lands in the
  marked field or stays `UNRECOGNIZED_FIELD`, and is the first thing able to say whether the directive's stated shape
  survives a consumer. Its load-time check is likewise tracked under "Checked annotations".

- [ ] **No "can this type receive JSON" answer for a schema author.** Which of an author's types a JSON document can
  validate against is discoverable only by sending one and reading the failure. The facts that decide it are all
  available at link time — an undiscriminated `choice` in a JSON-facing position, and a position whose only admissible
  values are ones JSON cannot spell (a `REQUIRED_FIXED` field fixed to a based-integer or to `.nan`/`.inf`, a `bytes`
  value) — so this is a report over the linked namespace or a property on `TypeDefinition`, not a new mechanism. What
  needs deciding is whether it is a diagnostic, a queryable property, or both.

- [ ] **The corpus has no way to state a fact about two encodings.** `class1/`/`class2/` are TSON-processor
  conformance classes, so a vector asserting that one schema yields one verdict over both encodings has nowhere to go
  — and §6's directive half, the place TSON text and JSON deliberately differ, is exactly what a single-encoding
  corpus cannot express. Needs the upstream decision on whether that is a layer, a further conformance class, or a
  per-vector encoding axis, before any vectors are owed; `RUNNER.md` is normative for whichever shape it takes.

- [ ] **`STRUCTURED-OUTPUT.md`'s JSON section predates `@discriminator` and the void-variant rule, and holds items
  belonging here.** It asks what a position typed `(T | void)` does with JSON `null`, which
  `TsonSchemaLinker.checkVariantsAreNotVoid` answers by refusing that position outright (§5.4). It also records
  untagged-union dispatch as undesigned and wanting a new meta.tn vocabulary addition — which is **not** simply
  superseded: `@discriminator` is the mechanism in place, the section's own dependent-typing proposal (an enum member
  carrying a per-member type association) is the alternative it is being tested against, and the annotation is
  unexercised, so the section should record that pairing rather than drop either half. The engineering items above
  stay only here.

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
  problem, with nothing symmetric to the read side's `DiagnosticsReceiver`. The `TsonValueWriter`
  above especially needs it, to report every schema violation in one pass the way the reader does — and
  the seam already exists and is write-direction-agnostic (`Diagnostic` carries a data path and both
  positions; nothing about `void report(Diagnostic)` assumes reading), so this is a matter of threading a
  receiver through the emitter, not designing a second error model.

## Documentation

- [ ] User-facing documentation on how to use the library — today only `CLAUDE.md`'s own dense,
  session-oriented internal narrative exists.

## Miscellaneous

- [ ] **The rest of [TSON-DATA] §9.1's resource limits, and [TSON-SCHEMA] §11.5's.** `LimitsPolicy` is
  the policy value and carries nesting depth at §9.1's own default of 64. §9.1 now states the whole set as one
  table with a default each, so nothing here is a judgement call any more — what is left is eleven document
  limits and five schema-side ones, each a component on `LimitsPolicy`, a `CliPolicy.CliLimits` field and a
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

- [ ] **`TsonConfig` is a builder and a configuration value in one class, and only the value belongs in
  `tson-base`.** `tson-base` holds what a deployment constrains this processor with — `base.policy` (what it
  will admit and spend) beside `base.source` (where it will obtain a schema), siblings rather than one
  nested in the other, because a `ProcessorPolicy` is a value the CLI renders in every envelope's `policy`
  field and a `SchemaSource` has no rendering. What is missing is the type that collects them, so an
  encoding other than TSON text can be handed one object rather than reassembling the set. `TsonConfig`
  cannot become it as it stands: it imports `io.ltr8.tson.compiler.*`, `TsonCompiledMetaRegistry`,
  `SchemaMetaNameBinder` and `TsonAtomContext`, and its `build()` resolves meta-kernel/meta.tn/core.tn and
  returns a `Tson` — the compiler's assembly point, not a value. The work is the extraction: which of its
  fields are constraints (the policies, the source, `requireContentHashPin`, `lenientBinding`) against which
  are assembly (the registries, the bind context, the name binder), and whether the binding travels with the
  constraints or stays behind. The name is free — `Config` in `io.ltr8.tson.base` — and `Tson.builder()`
  keeps its shape either way, taking one where it takes several today.

- [ ] **`scripts/restamp-bundled-schemas.sh` does not cover the spec's own §13.2 table.** The script moves
  every pin in the repo bottom-up — the three `spec/m/*.tn` headers, `TsonBundledSchemas`, `InitCommand`,
  `README.md` and the getting-started example — and `--check` reports staleness across all of them. It does
  not know about `spec/tson-part2-schema.md` §13.2, which pins the same three digests, so that table is the
  one pin a schema edit leaves behind and the only one whose drift nothing reports. It drifted once already.
  Teaching the script to stamp it (or at least to `--check` it, leaving the write to the spec author) is a
  few lines against the same digest computation, and makes CI able to catch what a hand edit currently must.
  The wrinkle worth deciding first: `spec/` is a cache this repo otherwise only reads, so writing into it is
  a small change to what the script is for — `--check` alone may be the honest scope.

- [ ] **`class2/schema/` carries no vector declaring a template, and the reason it could not is gone.**
  [TSON-SCHEMA] §8.1 now says an open entry is a `type_definition` like any other — `parameters` non-empty,
  `body` the held application in wire form under §5.10's one-spelling rule, typed by the kernel's `schema`
  without a second value shape — which is exactly the shape this resolver holds (`TemplateBody`/`HeldBody`).
  The two sides no longer disagree as values, so the layer can compare a template the way it compares
  everything else and the corpus can state what one resolves to directly rather than indirectly at `link/`.
  `ResolvedForm.heldBodies` is the comparison to keep — §8.1 makes wire form what a held body *is* on both
  sides, not a compromise — and what is owed is the vectors, upstream, plus the note in `CONFORMANCE.md` that
  currently explains the absence.
