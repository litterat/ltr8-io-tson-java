# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation) — that's tracked separately since it's a
vision/plan document, not a plain punch list; the JSON encoding's own outstanding work is a section
below — and the `design/` notes for the technical detail behind several of these items.

**This file is a clean list of outstanding work and nothing else.** Every entry must name something someone
could pick up and do. Three things are therefore not entries, however true they are:

- **What was done.** An item that ships comes out entirely — not annotated as complete, not kept as a record
  of how it was solved. Git history is the log.
- **What was decided against.** A won't-do is not work. It comes out too.
- **What might become work later.** A standing note to revisit something if conditions change is not an
  outstanding item; nobody can act on it today, and it sits in the list forever looking like a task.

Where any of those has to survive its entry — a won't-do someone would otherwise re-propose, the why behind
a shipped design, a condition that should trigger future work — it belongs in the `design/` note, the Javadoc,
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
both spellings, which is what `@disjoint` already does. `@disjoint` is the category's only member, and the one
thing outstanding under it is unrelated to the check itself.

- [ ] **`ResolvedForm` normalises the `position` component away by regex over rendered text.** The pattern is
  `position=Optional\[Position\[[^\]]*\]\]`, which matches the record's *default* `toString()` -- so giving
  `Position` a `toString()` of its own silently stops positions being normalised and diverges seven fixtures
  on a component §8's resolved form has no field for. The model has the component (`@Unbound`, this
  resolver's own) and the comparison should drop it structurally, before rendering, the way it already drops
  `kind`. `CLAUDE.md`'s traps list carries the hazard meanwhile.

## JSON encoding

[TSON-SCHEMA] §6 defines a representation directive — a mark whose force is confined to the encodings that claim
it — and no schema declares one, so nothing in this stack reads a schema-side annotation to decide how a value is
written. A record is closed under its type and open-ended data is a declared map-typed field, identically in both
encodings ([TSON-JSON] §6.1.1).

- [ ] **A JSON document has no in-band way to name its schema — §3.4's second route.** The out-of-band route
  is built (`Json.withSchemas`, `treeReader().withSchema(uri).readAs(...)`, and `tson validate --schema --type`),
  and it is the one the spec calls the expected production route. What is left is the in-band one: a root value
  that is or is wrapped by an annotation object carrying `$schema` and `$type`, both REQUIRED on that route. It
  needs §3.3's annotation object, so it arrives with §8 rather than before it. Where both routes supply a binding
  they MUST agree by canonical identity, and disagreement is a resolver error — never a precedence question.
  [TSON-JSON] §3.5's `TSON-Schema` field, a projection of the binding rather than an alternative to it, is the
  channel a server uses and is where this answer has to stay consistent.

**`tson-json` is a stack of its own, not a second front end over `TsonEventSource`.** Reusing the TSON event
contract would make one encoding's layering decide the other's at the two points where JSON and TSON genuinely
disagree — the same shape as the compatibility claim Revision 35 withdrew ([TSON-DATA] §1.1, §6). TSON text tells a
record from a map syntactically (`a: 1` vs `k => v`), so `TsonDataStream` emits `RecordStart`/`FieldName` or
`MapStart`/`MapArrow` and each reader asserts which it got; JSON's `{"a": 1}` is one syntax for both and §4.1 makes
the *position* decide, which a pull-only event source has no channel to say. And `null` is a value in a JSON tree and
the absent sentinel under a schema (§7), so a shared `TsonEvent` forces one meaning on the layer that does not hold
it. `design/json-encoding.md` has the argument; the entries below follow it. The tree model follows
[JEP 540](https://openjdk.org/jeps/540)'s shape and names, so a consumer learns one API and a bridge to
`jdk.incubator.json` is later a mapping rather than a rewrite.

- [ ] **No schema-directed decode of the open sum — §8.5.** §8.5's scoped positions are the open sum and what
  will finally admit a `$schema` member: the cell read off the members present, EXTERN needing both `$schema`
  and `$type`, LOCAL taking `$type` alone, and a bare value a validation error in every mode. `scoped` compiles
  to a `NOT_IMPLEMENTED` reader meanwhile. The cell is read off the leading members (§8.5: `$schema` first, then
  `$type`), which `ReservedMembers.lead` already answers. §8.2's predicate is one condition and is built whole;
  the member dispatch that used to be its second route is §6.1.5's and belongs to a record family, not to this
  entry. **The stack is `tson-json`'s own all the way up** — `JsonTypeReader`,
  `JsonCompiledSchema`,
  `JsonSchemaCompiler`, its own factory registries — and `design/json-encoding.md` carries why that is a deferral
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

- [ ] **The look-alike rule reaches no JSON position, and whether it should is now a real question rather
  than a settled one.** [TSON-DATA] §8.2's two per-name rules run at the schema-directed record and `$type`
  positions, so the realistic attack — a homoglyph in a name that matches no declared field — is refused. The
  third rule, `CONFUSABLE_NAMES`, is a property of a *set*, and `CLAUDE.md` records it as not reaching JSON
  because a JSON object's members are keys until a position says otherwise. **A schema-directed record position
  does say otherwise**, which is the fact that changed: its unmatched members are field names, so the set rule
  could run over them as `SchemalessTreeReader` runs it for TSON. What it would add over the two per-name rules
  is narrow — two unmatched members that read alike as a pair, where neither is confusable with a declared
  name — so this is a decision to take deliberately, not a gap to close by reflex.

- [ ] **The JSON reader factories share their per-entry work, and the container loops their remaining rules.**
  Every container reads through a plan, one mode-free loop and a builder per mode
  (`design/json-schema-directed-reading.md`). What is left of that restructure:
  - **The factory layer.** Every mode registers the same constructors from one list of parts, so one added later
    (`scoped`, §8.5) cannot be missed in one of them; and each factory is handed one per-entry record (name,
    display name, definition, schema location, the names that mean it) in place of each plan recomputing
    `EntryDisplayName.of` and `locationOf` — `DispatchFactories` and `RecordPlan` both build the display name for
    one OPEN record with subtypes today.
  - **Shared rules as helpers.** The refusal pattern (report, `EventSkip`, return nothing), still written
    out in each loop and dispatcher; the "reserved members but no `$type`" message written twice
    (`RecordPlan.admitsTag`, `DispatchChoiceReader`); the absence spelling `"null"` declared once per family
    (`RecordPlan`, `ArrayReader`, `TupleReader`, `MapEntries`); and failure detected by `ctx.reported() > before`
    (`TreeAtomReader`, `TreeAtomKeyedReader`, `RecordReader.verifyFixed`, `MapPairsReader`) taken from what the
    child returns -- a tree-mode child returns null only when it was refused.

- [ ] **`RecordReader.verifyFixed` parses a FIXED member twice.** The field's reader parses it, then the pin's
  parser parses the same content again for the comparison, because tree mode's atom reader answers only the node.
  `TreeAtomReader.keyed` already answers a set element's node with its parsed value's identity; the tree record
  factory reading a FIXED field at that reader lets `verifyFixed` compare `ValueIdentity.of(written)` and drop the
  second parse. Bind mode's bridged components (an enum class) answer the component's class, not the wire value,
  so the comparison needs the wire value there.

- [ ] **`tson-compiler`'s readers adopt the JSON dispatch design once it settles.** `RecordTagDispatchReader`,
  `RecordMemberDispatchReader`, `Subsumption.dispatching`, `AbstractTemplateReader` and the choice's
  `NamedDispatchReader` still select by name at read time, and a concrete record reader still accepts a tag
  naming a subtype. The port: dispatchers wrapping object references resolved at compile, chains flattened
  through the family, concrete readers that only accept a tag restating themselves, and the JSON side's
  `Dispatch*Reader` names. Its diagnostics are the drift to close: every divergence the JSON restructure pins in
  `CrossEncodingParityTest` comes out of that test as the TSON side matches.

- [ ] **A JSON diagnostic names the type at the end of a reference chain, not the alias the author wrote.**
  `JsonSchemaCompiler` collapses a `REFERENCE` entry onto its target's reader ([TSON-SCHEMA] §8.3 permits
  exactly that when compiling for reading), and the target's reader carries the target's name — so a refusal at
  `day => date` says `date`. The schema *location* is already right, because the entry point seeds the root
  declaration (`JsonCompiledSchema.rootDeclaration`), but the name in the message is not. `tson-compiler` solves
  it with `UseSite.named` over `EntryDisplayName`, which also renders a minted entry as the sugar or application
  that produced it; this stack has no counterpart. Owed with the facade, which is what will seed the root for a
  real read rather than a test doing it.

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

## Module structure

- [ ] **The encoding-neutral reader parts move into a module both stacks share — `tson-encoding` or similar, not
  `tson-base`.** With two working stacks the seam is visible (`design/json-encoding.md` deferred this until there
  were two to find it from): everything above the event level is encoding-neutral, and today it exists twice,
  guarded by `CrossEncodingParityTest` rather than by being one thing. `tson-base` is the wrong home — it is already
  the shared bottom of everything and is at risk of becoming a miscellany — so this is a module of its own, below
  `tson-json` and the TSON reader and above `tson-schema`, `tson-atom` and `tson-bind`. The JSON shape (a plan, one
  loop per encoding, a builder per mode) is what shares; `tson-compiler`'s base class with hooks shares only by
  making both stacks extend it. In order of value:
  - **The bind plan** — field-to-component matching, the FIXED exemption, `@Unbound`, the atom checks and
    `boundTo`, stated-value conversion, the argument map, and the union and labelled-group cases. The largest
    duplicated logic and the most correctness-sensitive, and the JSON gaps above close by moving across rather than
    by being written again.
  - **The bind builder** — construct from slots, fill a carrier, all-or-nothing, report a constructor's refusal.
  - **The field decisions** — what a stated absence and a written FIXED member become. The wording is already one
    (`base.diagnostics`), and so is what an omitted field yields (`RecordField.omitted`); the other two decisions
    are two copies.
  - **The dispatch tables built at compile** — a sealed family's pin table keyed by `ValueIdentity`, the deeper
    names, the alias index (`ReferenceChain.namesMeaning` beside `Subsumption.admitting`), and
    `DiscriminationClass`. The lookahead that reads them stays with each encoding.
  - **The same-named pairs** — `ValueIdentity`, `ReferenceChain`, `DeferredTypeReader`, `ErrorReader`,
    `CompiledReaders`, and the compile loop itself (resolve, cycle, gap reader, the bind-exception order), which is
    one algorithm over two reader types.
  What stays per encoding is the event loop — TSON's positional records, `_`, annotations and scope pushes have no
  JSON counterpart — the tree builders, and the tag carriers.

- [ ] **The TSON encoding moves out of `tson-compiler` into `tson`, and `tson-compiler` imports it.** `tson-json`
  holds its whole encoding — lexer, stream, readers, writers — and depends on no schema engine; the TSON encoding is
  instead part of `tson-compiler`, beside the schema pipeline that parses, resolves and links. The end state mirrors
  JSON: the TSON lexer, event stream, readers and writers live in `tson`, and `tson-compiler` — the schema engine —
  depends on `tson` for the lexer and data parsing a schema document needs, rather than `tson` depending on
  `tson-compiler`. What constrains it: the `Tson` front door resolves schemas and so needs the engine, which puts it
  above both rather than in `tson`; the schema grammar parses TSON text and has to reach the lexer without the
  readers reaching the resolver; and `design/modules.md`'s boundaries are rewritten with it. Best done after the
  TSON records adopt the JSON shape and the shared module exists, so the move carries code already in its final
  form.

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
    - It is the only writer that can serve every field declaration. A bound object's null component has one
      spelling to choose between omission and `_`, and the declaration chooses: the schemaless writers omit,
      which is right at `a?: T` and `a?: T?` and wrong at `a: T?` (the re-read document misses a required key)
      and at `a?: T? ~ v` (it reads back as `v`). §5.2 makes writing `_` there an encoder
      MUST for an encoder that has the schema.
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

- [ ] User-facing documentation on how to use the library, in `docs/` — `README.md` and the `tson-java` skill are the
  only consumer-facing prose; `design/` is internal.

## Miscellaneous

- [ ] **Two `DefinitionResolver` gap messages describe a resolver that no longer exists.** Both are
  `UnsupportedOperationException` texts, so they are what `tson` prints after `not implemented yet:` and what a
  `NOT_IMPLEMENTED` diagnostic carries. `resolveTypeRef`'s, for a sugar form that reaches resolution unlifted, offers two
  causes -- the desugar phase was skipped, "or a position inside it is an application, which has no entry to name until
  it is materialised" -- and the second is not one: a container position holding an application (`[box<text>]`) lifts,
  its slot written in `type_ref`'s record form and rewritten to the instantiation entry at materialisation. The comment
  above the throw says the same. `resolveTypeDef`'s fall-through lists what is "resolved so far" -- six shapes, where the
  method dispatches on everything §12.1's `type-def` produces -- so it reads as a feature gap where the only way to
  reach it is a `TypeDef` subclass the dispatch was never taught, an internal fault. What constrains the fix is the
  exception-classification policy: deciding what each site *is* (the first is reachable only by a caller that skipped a
  phase) decides whether it stays `UnsupportedOperationException` or becomes `IllegalStateException`, and with it exit
  70's two halves. `DefinitionResolver`'s class Javadoc lists both sites and moves with them.

- [ ] **A base-syntax diagnostic does not say whether it is a lexer error or a parse error.** [TSON-DATA] §8.1 makes
  them two categories, and [TSON-JSON] §9.4's table sorts JSON's failures into them (malformed text, invalid UTF-8 and
  ill-formed strings are lexer errors; grammar violations are parse errors). Both classifiers collapse the pair:
  `TsonDiagnostics.ofBaseSyntaxError`/`ofSchemaSyntaxError` and `JsonDiagnostics.ofBaseSyntaxError` report every case as
  `VALIDATION_ERROR`. On the TSON side the fact survives only on the thrown channel, as `LexException` against
  `ParseException` -- which is what `ConformanceSuiteTest` reads the category from -- so a collecting read, and every CLI
  envelope, loses it. The JSON stack has lost it on both channels, its lexer and stream raising the one shared
  `ParseException`. What a consumer routes on is the `Code`, so the fix is a code per category rather than a component
  beside it; what constrains it is that the JSON lexer has to state which kind it raised, and that
  `CrossEncodingParityTest` compares codes, so the two encodings must sort one malformed input the same way.

- [ ] **The look-alike check recomputes every skeleton per record, and ignores the identifier policy.**
  `SchemalessTreeReader.reportConfusableFields` calls `ConfusableNames.firstCollision` on every record of
  every schemaless tree read, which builds a `HashMap` and a UTS #39 skeleton per field name. Field names
  repeat across the records of a document, so the same skeletons are built again for each one; measured,
  the whole check is ~1,300 bytes per read of the harness document even after `Confusables.skeleton` stopped
  allocating for a name that maps nothing. A cache would take most of that, and the design question is its
  bound: names are attacker-controlled, so a per-read cache is the safe shape and a process-wide one is not.
  Separately, the check consults **no policy**, which is a conformance gap: a deployment that stated
  `withIdentifierPolicy(unrestricted())` still gets `CONFUSABLE_NAMES`, where the two per-name rules honour
  it, and [TSON-DATA] §8.2 says a processor "MUST allow a deployment to relax any of the three". What is
  left to decide is the policy's shape for the set rule -- a switch of its own, or implied by the level.

- [ ] **The shared corpus states nothing about [TSON-DATA] §2.2.1's content-hash pins.** No vector anywhere
  in `ltr8-io-tson-test-suite` mentions `sha256`, so three MUSTs go unmeasured across implementations: a
  reference whose pin does not match its target's bytes is refused, a query parameter that is not a
  recognized hash algorithm is an error rather than silently retained, and a hashed reference whose target
  carries no id line is refused (the hash input having no boundary). All three are expressible at the
  `class2/validate` layer, where a subject's own `!!schema` names a corpus fixture the runner serves: a
  wrong pin is portable without pinning any fixture's bytes, since no conforming processor may accept it.
  What is *not* expressible is which registration route recorded the hash — a corpus subject always reaches
  its schema through the runner's `SchemaSource`, where a host application registering a schema from text it
  holds is the case this repo covers in `TsonValidateTest`. The category is settled for all three: [TSON-SCHEMA]
  §10.2 makes a mismatch a resolver error, and the other two are §2.2.1 errors with no category of their own, which
  [TSON-DATA] §8.1 gives to the layer that detects them -- the resolver, following the reference.

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
