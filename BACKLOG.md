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
both spellings, which is what `@disjoint` already does. `@rest` is declared in meta.tn and is not checked, so
it is advisory today where §6 says it carries force; it is also re-checked on ingest (§8.1), which is a second
call site for whatever the load-time check becomes. `@discriminator` is **not** in this category — the erasure
test puts its fact in the kernel, and its work is under "Discriminated record families" below.

- [ ] **`@rest` is not checked.** Two checks: the annotated field's type resolves to a text-keyed map, and at
  most one field per composed chain carries the mark — the chain being countable since §5.8's restated-field
  rule merges annotations rather than dropping them, which this implementation already applies.

- [ ] **`ResolvedForm` normalises the `position` component away by regex over rendered text.** The pattern is
  `position=Optional\[Position\[[^\]]*\]\]`, which matches the record's *default* `toString()` -- so giving
  `Position` a `toString()` of its own silently stops positions being normalised and diverges seven fixtures
  on a component §8's resolved form has no field for. The model has the component (`@Unbound`, this
  resolver's own) and the comparison should drop it structurally, before rendering, the way it already drops
  `kind`. `CLAUDE.md`'s traps list carries the hazard meanwhile.

## Discriminated record families

`SPEC-FEEDBACK.md` #10 and #11 carry the design and the arguments; this is the build order. A record states how
it may be realised, a field of an abstract record may be a discriminator, and a position typed by such a record
recovers the subtype from the member in **both** encodings ([TSON-JSON] §6.1.5, already written). The kernel
carries both facts and meta.tn declares the three marks; nothing yet reads a mark or acts on a fact. Work lands
on `r2026-36-proposal`, the two kernel fields being what takes it off a Revision 35 `main`.

- [ ] **The three marks lower into the body.** The declarations are in meta.tn (`abstract`, `final` and
  `discriminator`, all `@annotation void`); what is left is the lowering. Each must be **consumed** by the
  resolver into `record.extension` / `record_field.discriminator` rather than preserved in §8.1's
  author-annotation channel — so resolved output carries one carrier per fact and §8.1's no-hoisting question
  does not arise. Until it is, a mark resolves, sits in the annotation channel and does nothing, which is a
  schema that says `@abstract` and is not. The three names are reserved at their positions: a schema may not
  mean something else by them. §6 honours a checked annotation at either declaration position, so the key
  spelling must lower identically to the value spelling. The annotation shape is the interim and §12.1 spells
  them eventually; what that costs is one more reason to keep the lowering in one place.

- [ ] **The load-time checks, over the linked closure.** One pass, in `TsonSchemaLinker` beside
  `ChoiceDisjointness`. On the record: composing or refining onto a **FINAL** record is a resolver error, in the
  declaring schema and in any that imports it — while §5.9 subtraction is admissible, minting no IS-A edge; and
  `@abstract` with `@final` on one declaration is an error. On the annotated field: its declared type resolves,
  after its reference chain, to an atom-family instance or an enum (§5.2 grants that only to a field *carrying*
  a value, and the base's field carries none, so it must be checked here); its state is exactly REQUIRED, not
  OPTIONAL, FIXED or DEFAULT; it is **not a group member**, checked against the resolved `groups` list rather
  than the source, since §5.11 refinement is what reaches that state and the declaration cannot express it; and
  a record carrying a discriminator field MUST be ABSTRACT, §5.7's identity diagonal forbidding the base pinning
  it. Over the closure: every entry in `subtypes`, transitively, pins each discriminator `REQUIRED_FIXED`, and
  the pins are **pairwise distinct as tuples** in the base's declaration order. Distinctness is under the field
  type's own equality contract and not token equality — `= 255` and `= 0xFF` are one pin (§4.3), `= 1` and
  `= 1.0` are one (§5.5), text pins compare NFC-normalised — so `ValueIdentity` is what answers it and a
  comparison of tokens accepts a schema whose dispatch table is not a function.

- [ ] **SEALED is derived, and two facts the existing machinery must learn.** `extension` is SEALED exactly when
  the record is ABSTRACT and some field is a discriminator, computed from the body alone and recorded there, in
  the manner of `choice.disjoint` (§5.4) — so a reader has one lookup rather than a scan. Inhabitance follows:
  a FINAL or OPEN record is inhabited as any record is, an ABSTRACT or SEALED one exactly when at least one
  subtype is, which needs `TypeInhabitance`'s least fixed point to learn the union rather than gain an
  exemption. And `extension` participates in §8.2 identity — an abstract, sealed, concrete and final `pet` admit
  different values and are different types.

- [ ] **The dispatch reader, in both stacks.** At a SEALED position the reader takes the discriminator members
  at the fields' declared types **in the base** — the one set known before dispatch — forms the value or the
  tuple, and selects; the matched subtype then validates the whole value, re-verifying each pin as an ordinary
  FIXED check so the dispatch read and the validation read agree by construction. A missing member is a
  validation error, never a fallback to the tag; an unmatched value names what arrived and should name the
  pinned alternatives. At an ABSTRACT position the tag is REQUIRED and the failure lands before the members are
  read. Structurally this is a choice reader keyed on a value rather than a type name, so it sits beside
  `ChoiceReader`/`TreeChoiceReader` rather than being a new kind; the tag is optional-and-asserting at SEALED in
  **both** encodings, which is what a shared diagnostics family in `base.diagnostics` should state once. A
  family dispatches one level — a sub-subtype inherits its parent's pin and §5.7 forbids changing it — so
  deeper types dispatch to the parent and rely on the tag.

- [ ] **Corpus vectors, in the same session as the resolver work.** `class2/schema/` for the resolved output of
  each `extension` member and a discriminator field; `class2/link/` for the closure checks, the FINAL refusal
  and the subtraction that is *not* refused; `class2/validate/` for the dispatch, the missing member, the
  unmatched value and the disagreeing tag. The corpus's own sidecar schemas need no change — these are ordinary
  vectors — and the JSON side is covered by `CrossEncodingParityTest`, which §9.4 makes obligatory here since
  both encodings state the same refusals.

## JSON encoding

[TSON-SCHEMA] §6 makes this a spec obligation rather than an interop nicety, and `meta.tn` states it directly: "No
encoding is privileged — TSON text is one member of the text class, beside JSON — so a directive binds every encoding
in its class, and a document in a directed encoding may not be readable without it." `@rest` is declared on those
terms and has no consumer, because TSON text never flattens — so it cannot exercise the directive at all, and a JSON
front end is what puts that half of §6 under test. Expect it to move: a directive with no consumer has never had its
shape checked against one. `@discriminator` was declared beside it and left the category — the same test showed its
force reaches every encoding, which is what put the fact in the kernel instead (`SPEC-FEEDBACK.md` #11).

- [ ] **A JSON document has no in-band way to name its schema — §3.4's second route.** The out-of-band route
  is built (`Json.withSchemas`, `treeReader().withSchema(uri).readAs(...)`, and `tson validate --schema --type`),
  and it is the one the spec calls the expected production route. What is left is the in-band one: a root value
  that is or is wrapped by an annotation object carrying `$schema` and `$type`, both REQUIRED on that route. It
  needs §3.3's annotation object, so it arrives with §8 rather than before it. Where both routes supply a binding
  they MUST agree by canonical identity, and disagreement is a resolver error — never a precedence question.
  `SPEC-FEEDBACK.md` #2's interpretation, the `TSON-Schema` header as a projection of the directive (§3.5), is the
  channel a server uses and is where this answer has to stay consistent.

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

- [ ] **No schema-directed decode of the open sum — §8.5.** §8.5's scoped positions are the open sum and what
  will finally admit a `$schema` member: the cell read off the members present, EXTERN needing both `$schema`
  and `$type`, LOCAL taking `$type` alone, and a bare value a validation error in every mode. `scoped` compiles
  to a `NOT_IMPLEMENTED` reader meanwhile. §8.2's predicate is one condition and is built whole; the member
  dispatch that used to be its second route is §6.1.5's and belongs to a record family, tracked under
  "Discriminated record families". **The stack is `tson-json`'s own all the way up** — `JsonTypeReader`,
  `JsonCompiledSchema`,
  `JsonSchemaCompiler`, its own factory registries — and `docs/json-encoding.md` carries why that is a deferral
  rather than a conclusion: the two disagreements that keep the *event* layers apart both dissolve above the
  schema, where the reader is the position, so one compiled schema over an encoding-neutral context stays a real
  option and is simply not an abstraction worth designing from one implementation. **It gains no dependency on
  `tson-compiler`**, contrary to what this entry used to predict: `TsonLinkedSchema` is a `tson-schema` record and
  `tson-atom` already re-exports that module, so what crosses is a value model and the pipeline producing it stays
  where it is.

- [ ] **The choice family does not state its rules from `base.diagnostics`, and is waiting on §8.5.** Records,
  arrays, sets, tuples and maps do. Choices are entangled: `tson-compiler` states its dispatch diagnostics in
  `NamedDispatchReader`, parameterised over a "candidate noun" so one class serves both a choice and a scoped
  position, while `tson-json` has choice-specific wording and no scoped reader at all. Aligning now would
  align against a shape about to change, so it waits for [TSON-JSON] §8.5 — at which point both stacks have
  the same two positions and the shared class can be parameterised the same way.

- [ ] **`Diagnostic.Code` has no member for "a required tag is missing", and both encodings overload
  `UNKNOWN_TYPE_REF`.** [TSON-JSON] §9.4 lists the condition in its own right ("missing required tags (§8.2)",
  a validation error) and the closed enum has nothing for it, so `tson-compiler`'s choice reader reports a
  value with no tag as `UNKNOWN_TYPE_REF` — accurate about the category and wrong read literally, since
  nothing unknown was written and the tag is absent rather than unresolvable. `TreeChoiceReader` matches
  it, because §9.4 gives both encodings one vocabulary and the incumbent settles which member. A code of its
  own touches `tson-base` and both readers together; the parity test is what stops them drifting meanwhile.

- [ ] **The look-alike rule reaches no JSON position, and whether it should is now a real question rather
  than a settled one.** [TSON-DATA] §8.2's two per-name rules run at the schema-directed record and `$type`
  positions, so the realistic attack — a homoglyph in a name that matches no declared field — is refused. The
  third rule, `CONFUSABLE_NAMES`, is a property of a *set*, and `CLAUDE.md` records it as not reaching JSON
  because a JSON object's members are keys until a position says otherwise. **A schema-directed record position
  does say otherwise**, which is the fact that changed: its unmatched members are field names, so the set rule
  could run over them as `SchemalessTreeReader` runs it for TSON. What it would add over the two per-name rules
  is narrow — two unmatched members that read alike as a pair, where neither is confusable with a declared
  name — so this is a decision to take deliberately, not a gap to close by reflex.

- [ ] **Every schema-directed record read scans its object twice.** Recognising [TSON-JSON] §3.3's
  annotation object means seeing member names, and §6.1.6 gives member order no meaning — so
  `TreeRecordReader` runs `ReservedMembers.scan` before every record, and the events it looked past
  are replayed from a buffer rather than re-lexed. Correct, and unmeasured: `JsonAllocationHarnessTest` reads
  schemalessly, so nothing says what the second pass costs per bound record. No shortcut is sound — peeking
  the first member concludes nothing when order is free, and a redundant tag is admissible at any typed
  position (§8.1) — so what is owed is the measurement first, and only then a decision about whether the
  common case deserves a different shape.

- [ ] **Bind mode has no schema-directed reader.** Tree mode validates and hands back the JSON; the other door
  — an HTTP service accepting both encodings and getting a Java object back — needs the same containers over a
  `DataBindContext`, with the bind-agreement machinery `tson-compiler` carries (`BindMismatchException` at
  compile, `MissingBindingException` deferred to first read). The factory registry already takes a mode
  (`ValueReaderFactoryRegistry.tree()` beside `atoms()`); what is owed is the second set of container
  factories and the front-door surface that selects it.

- [ ] **`@rest` still has no consumer, and the JSON record reader is the one that will judge it.** §6.2's
  flatten is deliberately unbuilt: an undeclared member is §6.1.1's closure error and lands nowhere, which is
  the strict reading the annotation would later relax. Now that a JSON record reader exists, the question the
  directive was always waiting on can be asked — whether a rest field's stated shape (`{text => X}`, one field
  per composed chain, declared names winning, `$`-initial names never collected, one source per field) survives
  a consumer. Its three load-time checks are tracked under "Checked annotations".

- [ ] **A JSON diagnostic names the type at the end of a reference chain, not the alias the author wrote.**
  `JsonSchemaCompiler` collapses a `REFERENCE` entry onto its target's reader ([TSON-SCHEMA] §8.3 permits
  exactly that when compiling for reading), and the target's reader carries the target's name — so a refusal at
  `day => date` says `date`. The schema *location* is already right, because the entry point seeds the root
  declaration (`JsonCompiledSchema.rootDeclaration`), but the name in the message is not. `tson-compiler` solves
  it with `UseSite.named` over `EntryDisplayName`, which also renders a minted entry as the sugar or application
  that produced it; this stack has no counterpart. Owed with the facade, which is what will seed the root for a
  real read rather than a test doing it.

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
  untagged-union dispatch as undesigned and wanting a new meta.tn vocabulary addition — which the sealed record
  family answers, and the section should say so: the mechanism is a discriminator field on an abstract base
  (`SPEC-FEEDBACK.md` #10, #11), not a choice-level mark, so the section's own dependent-typing proposal (an enum
  member carrying a per-member type association) is the shape that was *not* taken and the reason belongs beside
  it — a sibling tag needs the value of one field to type another. The engineering items above
  stay only here.

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has the two schemaless writers and the
push emitter (`TsonDataEmitter`, the write-direction peer of `TsonDataStream`) and is missing the rest of
the mirror. What is left below is the schema-aware writer and diagnostics.

- [ ] **Nothing measures the write path.** `AllocationHarnessTest` and `JsonAllocationHarnessTest` both read
  and neither writes, so the write side has no number attached to it at all. That matters now rather than in
  principle: the byte path encodes UTF-8 itself through `Utf8Sink` instead of an `OutputStreamWriter`, and
  `ByteSink.block()` is a tuning knob with nothing to tune against — a deployment asking "what should my
  block be?" has no way to answer, and a regression in the encoder would be invisible. The shape is settled
  by the read side rather than open: bytes per document written, split by stage the way `whereAReadsBytesGo`
  splits a read, plus a per-record difference measured between a small document and a large one so the
  figure is not a fixed cost in disguise. `AllocationProbe` is already shared from `tson-base/src/testShared`
  and needs nothing new.

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
- [ ] **Writers are fail-fast only, no diagnostics — on both encodings.** Every writer throws
  `WriteException` at the first problem, with nothing symmetric to the read side's `DiagnosticsReceiver`,
  where both encodings' *readers* have carried one for a while. The `TsonValueWriter`
  above especially needs it, to report every schema violation in one pass the way the reader does — and
  the seam already exists and is write-direction-agnostic (`Diagnostic` carries a data path and both
  positions; nothing about `void report(Diagnostic)` assumes reading), so this is a matter of threading a
  receiver through the emitter, not designing a second error model.

## Documentation

- [ ] User-facing documentation on how to use the library — today only `CLAUDE.md`'s own dense,
  session-oriented internal narrative exists.

## Miscellaneous

- [ ] **The look-alike check recomputes every skeleton per record, and ignores the identifier policy.**
  `SchemalessTreeReader.reportConfusableFields` calls `ConfusableNames.firstCollision` on every record of
  every schemaless tree read, which builds a `HashMap` and a UTS #39 skeleton per field name. Field names
  repeat across the records of a document, so the same skeletons are built again for each one; measured,
  the whole check is ~1,300 bytes per read of the harness document even after `Confusables.skeleton` stopped
  allocating for a name that maps nothing. A cache would take most of that, and the design question is its
  bound: names are attacker-controlled, so a per-read cache is the safe shape and a process-wide one is not.
  Separately, the check consults **no policy** -- a deployment that stated
  `withIdentifierPolicy(unrestricted())` still gets `CONFUSABLE_NAMES`, where the two per-name rules honour
  it. §8.2 requires a deployment be able to relax any of the three rules, so either that is a conformance
  gap or the rule is deliberately unconditional and should say so; the two per-name rules gate themselves,
  which makes the silence here look like an oversight rather than a decision.

- [ ] **The shared corpus states nothing about [TSON-DATA] §2.2.1's content-hash pins.** No vector anywhere
  in `ltr8-io-tson-test-suite` mentions `sha256`, so three MUSTs go unmeasured across implementations: a
  reference whose pin does not match its target's bytes is refused, a query parameter that is not a
  recognized hash algorithm is an error rather than silently retained, and a hashed reference whose target
  carries no id line is refused (the hash input having no boundary). All three are expressible at the
  `class2/validate` layer, where a subject's own `!!schema` names a corpus fixture the runner serves: a
  wrong pin is portable without pinning any fixture's bytes, since no conforming processor may accept it.
  What is *not* expressible is which registration route recorded the hash — a corpus subject always reaches
  its schema through the runner's `SchemaSource`, where a host application registering a schema from text it
  holds is the case this repo covers in `TsonValidateTest`. Adding the vectors needs a `refused`-style
  decision on §8.1's category for a pin failure, which the corpus does not yet state.

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
