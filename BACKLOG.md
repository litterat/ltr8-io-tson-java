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
  `TsonCompiledMetaRegistry.withStandardLibrary` already does, which is scoped to just the five bundled
  schemas in a known order, not a general algorithm. Cycle detection is available to build on:
  `resolveLinked` holds a per-thread in-flight set reporting §2.2.3's cycle by the path that closes it.
- [ ] **Ingest of resolved output** ([TSON-SCHEMA] §8.1, §10.1) — a schema is always re-resolved from source,
  so no path reads a resolved `{type_name => top}` map and verifies it. §8.1's ingest verifies rather than
  recomputes what an author may have written into a derived fact: a recorded `template_param.type` or `bound`
  must be IS-A the type the held body derives, since a written narrowing lives nowhere else; it also re-runs the
  family checks (no member minted at a use site) over the ingested map and its closure, and the §5.11 group
  declaration rules over every `field_group`. `ParameterTypes` already derives the type to check against.
- [ ] **An application is not yet unified with an imported library's entry for it** (SPEC-FEEDBACK.md #14, §8.2). A
  library declaring `int_box => box<int32>` writes `@application:{ name: box  arguments: [{ name: int32 }] }` on that
  key, and a schema importing it that writes `box<int32>` still closes the application to an entry of its own rather
  than resolving to `int_box`. The lookup runs application to entry, so it needs an index over imported keys keyed by
  `DerivedName.canonicalApplication`; `TemplateMaterialiser.ownedBy` is that index for local declarations and
  `SchemaResolver` already reads every import's `@application` into the map the applied-parent fold uses, so the
  index belongs in `ownedBy`, seeded from that map before the driving loop. Decide what a local declaration of an
  application an import already owns then is: the same entry, an alias of it, or two entries §2.2.3 unifies.

- [ ] **Where a namespace type may be declared** (SPEC-FEEDBACK.md #23). Settle with the
  spec whether a user schema may declare a namespace type (#23's composition-operand reading): today only a
  kernel-governed one can, and a schema typing a field by it cannot also import core.tn. Lands on
  `r2026-38-proposal` before the branch goes to the reviewer.
- [ ] **A value reached through a record value's field** (SPEC-FEEDBACK.md #23, #24). A `ref<T>` whose last segments
  select fields of a record value (`p.x.primary`, a `pairing` held under a namespace key) is judged by the fields'
  declared types, every refusal included, and then reported as `NOT_IMPLEMENTED`: `ValuesByName` never reads the
  host object, so the value is neither found nor delivered into the `Ref`. Reach it through `ltr8-bind`'s class
  descriptor for the host (the component the binding wrote the field to, under its wire name), not raw record
  reflection, or bind a meta layer's values as a tree the walk can enter by field.
- [ ] **JSON bind delivers a `Namespace` at a namespace-typed slot.** The TSON reader builds one where the host
  component is a `Namespace` (`reader.BindTargets`, over a `MapBindReader` that carries the constructor's name);
  `tson-json`'s `BindTargets`/`BindMapBuilder` meet a namespace type's map with no constructor name, so a `Namespace`
  component there is a bind mismatch. Nothing in the JSON stack binds `schema.meta` today, so nothing fails yet; a
  JSON reading of resolved output, a record's `fields` among it, would.
- [ ] **Whether a kind admits an abstract type** (SPEC-FEEDBACK.md #22). `kind<record>` holds every type built with
  `record` (`KindMembers.isMember`), so it holds an abstract record (`pet => abstract { ... }`) and a record template's
  family base (`boxed => <T> { v: T }`), neither of which has instances of its own: a document writing `payload_type:
  boxed` names a type no value is read through until an application is chosen. `kind<namespace>` likewise holds the
  abstract `namespace` itself, and `kind<api>` lists a namespace entry, `myapi`, whose value is no type (#23). Settle
  it with `ref<T>`, when a kind's value becomes a resolved reference rather than a spelling.
 The choice is membership by construction alone, as running, or a
  concrete-only kind that excludes every abstract member; never an exception for record templates alone, since a family
  base is a type like any abstract record (§5.10).
- [ ] **A parameter in a record template has no channel of its own** (SPEC-FEEDBACK.md #17). An open record's
  parameter is a `type_ref` whose `name` is the parameter, or an unquoted token in `record_field.value`, told from a
  type or a literal by §5.10's shadowing rule, in resolved output as in source. Give the slots that can hold one a
  channel: `record_field` `( type: type_kind | type_param: param_name )` and `( value: value | value_param: param_name
  )`, `type_argument` a `param` member, so `type_ref.name` always names an entry. Sequenced with the `type_ref` /
  `type_name` normalisation (qualified names, `!ref`), which needs exactly that, and with what `@application` means
  on a declared entry that is no record.

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

- [ ] **A key-sorted JSON document with a `$`-named field loses its tag — [TSON-JSON] §3.3.** §3.3 requires `$type`
  (after any `$schema`) to lead its object and argues that code-point sorting preserves that; with `record.name_type`
  admitting `$ref`, `$id` and `$defs` as field names, those sort first, so RFC 8785 canonical output of a tagged
  value — `{"$ref": "#/a", "$type": "sub", …}` — is refused as a misplaced `$type`. The leading-`$type` rule itself
  is expected to be relaxed, so settle that first, then decide what the reader admits: the annotation members
  anywhere within the object's leading run of `$`-named members (a run bounded by the record's declared `$` fields,
  so the lookahead stays fixed by the schema), or the sorting claim withdrawn.

- [ ] **A record declaring `$schema`, `$type` or `$value` encodes to JSON that reads back as something else.**
  [TSON-JSON] §3.2 says a schema SHOULD NOT declare one of the three, and a record that does obscures it on read
  (`JsonFieldNameTypeTest.aRecordDeclaringATagMemberObscuresIt`); written, its value is a JSON object whose leading
  `$type` member is taken as a tag, which principle 5 ("never a silent drop") and the Class 3 equivalence rule do not
  allow. For review: make it a JSON encode error while the schema stays legal (a TSON-only schema may want the
  field), or refuse the schema outright, and make §3.2 a MUST either way.

- [ ] **How an enum whose members can begin with a digit encodes in JSON — [TSON-JSON] §5.2.** A name type may admit
  a leading digit, which makes an enum over it number-class (`ports => !enum_type { name_type: digits  members: ["80"
  "443"] }`; `(ports | integer)` with `@disjoint` is refused), and §5.2's headline (number-class encodes as a JSON
  number) contradicts its next sentence (a member beyond the identifier grammar encodes as a string); an enum whose
  members span classes is not covered. Candidate rule: an enum always encodes as a JSON string, its members being
  names, while its class stays its members' for TSON's disjointness.

- [ ] **A JSON document has no in-band way to name its schema — §3.4's second route.** The out-of-band route
  is built (`Json.withSchemas`, `treeReader().withSchema(uri).readAs(...)`, and `tson validate --schema --type`),
  and it is the one the spec calls the expected production route. What is left is the in-band one: a root value
  that is or is wrapped by an annotation object carrying `$schema` and `$type`, both REQUIRED on that route. It
  rides §3.3's annotation object and §8.5's scope push, both built for scoped positions (`DispatchScopedReader`),
  so what is left is the root: reading the leading `$schema`/`$type` before any schema is in hand. Where both
  routes supply a binding they MUST agree by canonical identity, and disagreement is a resolver error — never a
  precedence question.
  [TSON-JSON] §3.5's `TSON-Schema` field, a projection of the binding rather than an alternative to it, is the
  channel a server uses and is where this answer has to stay consistent.

**`tson-json` is a stack of its own, not a second front end over `TsonEventSource`.** Reusing the TSON event
contract would make one encoding's layering decide the other's at the two points where JSON and TSON genuinely
disagree — the same shape as the compatibility claim Revision 35 withdrew ([TSON-DATA] §1.1, §6). TSON text tells a
record from a map syntactically (`a: 1` vs `k => v`), so `TsonDataStream` emits `RecordStart`/`FieldName` or
`MapStart`/`MapArrow` and each reader asserts which it got; JSON's `{"a": 1}` is one syntax for both and §4.1 makes
the *position* decide, which a pull-only event source has no channel to say. And `null` is a value in a JSON tree and
the void sentinel under a schema (§7), so a shared `TsonEvent` forces one meaning on the layer that does not hold
it. `design/json-encoding.md` has the argument; the entries below follow it. The tree model follows
[JEP 540](https://openjdk.org/jeps/540)'s shape and names, so a consumer learns one API and a bridge to
`jdk.incubator.json` is later a mapping rather than a rewrite.

- [ ] **The choice and scoped families do not state their rules from `base.diagnostics`.** Records, arrays,
  sets, tuples and maps do. `tson-compiler` states its dispatch diagnostics in `NamedDispatchReader`,
  parameterised over a "candidate noun" so one class serves both a choice and a scoped position; `tson-json`
  words each in its own dispatcher (`DispatchChoiceReader`, `DispatchScopedReader`), and `CrossEncodingParityTest`
  compares only their codes and pointers. The rule classes want the same parameterisation, so one sentence
  serves both positions in both encodings and the parity cases can move to `sameRule`.

- [ ] **A pinned schema reference is not verified in the JSON stack.** `JsonCompiledSchemaRegistry.get`
  canonicalises before it asks its `TsonSchemaLoader`, and the loader takes a canonical identity and nothing
  else, so the `?sha256=` of a pushed `$schema` ([TSON-JSON] §8.5) or an out-of-band `withSchema(uri)` is
  dropped unchecked -- where [TSON-SCHEMA] §10.2 makes verification a MUST and a mismatch a resolver error. The
  TSON registry verifies against the bytes it fetched; the JSON side holds only linked schemas, so what it
  needs is either the digest beside each linked schema or a loader that takes the reference as written.

- [ ] **The JSON reader factories share their per-entry work, and the container loops their remaining rules.**
  Every container reads through a plan, one mode-free loop and a builder per mode
  (`design/json-schema-directed-reading.md`). What is left of that restructure:
  - **The factory layer.** Every mode registers the same constructors from one list of parts, so one added later
    cannot be missed in one of them; and each factory is handed one per-entry record (name,
    display name, definition, schema location, the names that mean it) in place of each plan recomputing
    `EntryDisplayName.of` and `locationOf` — `DispatchFactories` and `RecordPlan` both build the display name for
    one OPEN record with subtypes today.
  - **Shared rules as helpers.** The refusal pattern (report, `EventSkip`, return nothing), still written
    out in each loop and dispatcher; the "annotation members but no `$type`" message written twice
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
  it with `UseSite.named` over `EntryDisplayName`, which also renders a minted entry as the sugar, the record body
  or the application that produced it; this stack has no counterpart. Owed with the facade, which is what will seed
  the root for a real read rather than a test doing it.

- [ ] **No "can this type receive JSON" answer for a schema author.** Which of an author's types a JSON document can
  validate against is discoverable only by sending one and reading the failure. The facts that decide it are all
  available at link time — an undiscriminated `choice` in a JSON-facing position, and a position whose only admissible
  values are ones JSON cannot spell (a `REQUIRED_FIXED` field fixed to a based-integer or to `.nan`/`.inf`, a `bytes`
  value) — so this is a report over the linked namespace or a property of a linked entry, not a new mechanism. What
  needs deciding is whether it is a diagnostic, a queryable property, or both.

- [ ] **The corpus has no way to state a fact about two encodings.** `class1/`/`class2/` are TSON-processor
  conformance classes, so a vector asserting that one schema yields one verdict over both encodings has nowhere to go
  — and §6's directive half, the place TSON text and JSON deliberately differ, is exactly what a single-encoding
  corpus cannot express. Needs the upstream decision on whether that is a layer, a further conformance class, or a
  per-vector encoding axis, before any vectors are owed; `RUNNER.md` is normative for whichever shape it takes.
  There are no Part 3 vectors at all yet, so the first set is owed as soon as the shape is settled, starting with
  what `record.name_type` decided for JSON and Java tests alone pin today (`JsonFieldNameTypeTest`,
  `JsonTaggedValueReadTest`): `$ref`/`$id` read as declared fields; a declared `$type` obscured by the annotation
  member; an undeclared `$foo` as a validation error; a member outside its record's name type unknown, not refused;
  a member matched in a folding name type's form, its repeat a duplicate at its own spelling.

## Module structure

- [ ] **The encoding-neutral reader parts move into a module both stacks share — `tson-encoding` or similar, not
  `tson-base`.** With two working stacks the seam is visible (`design/json-encoding.md` deferred this until there
  were two to find it from): everything above the event level is encoding-neutral, and today it exists twice,
  guarded by `CrossEncodingParityTest` rather than by being one thing. `tson-base` is the wrong home — it is already
  the shared bottom of everything and is at risk of becoming a miscellany — so this is a module of its own, below
  `tson-json` and the TSON reader and above `tson-schema`, `tson-atom` and `ltr8-bind`. The JSON shape (a plan, one
  loop per encoding, a builder per mode) is what shares; `tson-compiler`'s base class with hooks shares only by
  making both stacks extend it. In order of value:
  - **The bind plan** — field-to-component matching, the FIXED exemption, `@Unbound`, the atom checks and
    `boundTo`, stated-value conversion, the argument map, and the union and labelled-group cases. The largest
    duplicated logic and the most correctness-sensitive, and the JSON gaps above close by moving across rather than
    by being written again.
  - **The bind builder** — construct from slots, fill a carrier, all-or-nothing, report a constructor's refusal.
  - **The field decisions** — what a stated absence and a written FIXED member become. The wording is already one
    (`base.diagnostics`), and so is what an omitted field yields (`RecordField.omitted`); the other two decisions
    are two copies, and so is the field-group judgement (each reader's `GroupPlan` and its pass over chosen options).
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
  `@synthetic` — reaches `TsonSchema.entries()` as key annotations (`Namespace.getAnnotations`) and survives
  linking and the import merge. The *document* round trip is what does not: reading a resolved-form
  `{type_name => top}` document back binds the map with no key annotations at all, and nothing
  writes them. `ResolvedFixtureTest` therefore cannot compare the marker the way it compares everything else
  — the fixtures carry `@synthetic` on the keys the resolver minted and `@ordering`/`@bounded`/`@exact` on core's,
  and the bound side renders none of them, so the entries would compare equal for the wrong reason;
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

- [ ] **The §7.2 refusal names a synthetic by its key.** `VariantSchemaReader` builds its `SubsumptionDiagnostics`
  from the entry name and keeps it through `renamed`, so beside `text_list => [text]` and `r => { t: [text] }`,
  `!r { t: !text_list [a] }` reads "'text_list' is not admissible at a 'array_text_4cc4a482' position". The position
  is to be named as `EntryDisplayName` names it everywhere else (`[text]`), in the message and in `admissible()`'s
  list; the JSON readers (`DispatchTagReader`, `RecordPlan`) already pass a display name. Pin it with a tree-mode
  test on that schema.

- [ ] **Whether a closed set of text binds to a host enum.** [TSON-SCHEMA] §7.4 lets an enum bind to "a host enum by a
  mapping the binder owns". `text_enum` is gone from the kernel, and a closed set of text that is no name is now
  `text_type`'s `members` facet (`!text ^ { members: [...] }`), an atom rather than an enum, so a schema that bound
  such a set to a Java enum may have lost the mapping. Check what `ltr8-bind` does with a `text_type` carrying
  `members` at an enum-typed component; if it refuses, decide whether the binder maps a member set as it maps an
  enum's members, and pin the answer in a bind-mode test.

- [ ] **Two `DefinitionResolver` gap messages describe a resolver that no longer exists.** Both are
  `UnsupportedOperationException` texts, so they are what `tson` prints after `not implemented yet:` and what a
  `NOT_IMPLEMENTED` diagnostic carries. `resolveTypeRef`'s, for a sugar form that reaches resolution unlifted, offers two
  causes -- the desugar phase was skipped, "or a position inside it is an application, which has no entry to name until it is
  materialised" -- and the second is not one: a container position holding an application (`[box<text>]`) lifts, its slot
  written in `type_ref`'s record form and rewritten to the entry its expansion lands on at materialisation. The comment above
  the throw says the same. `resolveTypeDef`'s fall-through lists what is "resolved so far" -- six shapes, where the method
  dispatches on everything §12.1's `type-def` produces -- so it reads as a feature gap where the only way to reach it is a
  `TypeDef` subclass the dispatch was never taught, an internal fault. What constrains the fix is the
  exception-classification policy: deciding what each site *is* (the first is reachable only by a caller that skipped a
  phase) decides whether it stays `UnsupportedOperationException` or becomes `IllegalStateException`, and with it exit 70's
  two halves. `DefinitionResolver`'s class Javadoc lists both sites and moves with them.

- [ ] **A one-position tuple is named in a diagnostic in array spelling.** `only: tuple1<text>` given `[]` reports
  `'[text]' has 1 positions, found only 0 elements`: `tuple1<text>` expands to a tuple no author named, and
  `EntryDisplayName` renders a one-element `TupleBody` as `[text]`, which is the array. Render no name for it, as a set
  gets none (`EntryDisplayName.authored`), and let `TupleDiagnostics` state the rule alone where it has no name, as
  `ArrayDiagnostics` does.

- [ ] **A tuple refused a scalar prints the event record.** `only: tuple1<text>` given `a` reports
  `expected a tuple (array-shaped) for '[text]', found TokenEvent[text=a, form=UNQUOTED, position=…]`:
  `TypeRefCheck.describe` names a record, map, array, `{}` and `_` and falls back to `String.valueOf` for anything
  else, so a scalar token arrives as its `toString`. It has 18 callers, all exposed the same way; a scalar wants its
  token text (and form, where quoting is the difference). `JsonAtoms.describe` is the JSON side's counterpart and
  `CrossEncodingParityTest` the check that the two describe one value alike.

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

- [ ] **The rest of [TSON-DATA] §9.1's resource limits, and [TSON-SCHEMA] §11.5's.** `LimitsPolicy` is the policy value
  and carries nesting depth at §9.1's own default of 64. §9.1 now states the whole set as one table with a default
  each, so nothing here is a judgement call any more — what is left is eleven document limits and five schema-side
  ones, each a component on `LimitsPolicy`, a field on `spec/m/policy.tn`'s `limits` (optional, defaulting to
  §9.1's value, then a restamp), a `CliPolicy.CliLimits` field and a `--flag`. Document side: **token length**
  (1,048,576 code points), **decoded text length** after escape processing (1,048,576), **numeric literal length**
  (4,096 digits, annotated tokens included), **decoded binary size** per `!bytes` value (16,777,216 octets),
  **document size** in bytes (16,777,216), **elements** per array or set (1,048,576), **entries** per map
  (1,048,576), **fields** per record (65,536), **annotations** on one value (64), **total values** in one document
  (16,777,216), and **foreign schemas** one document's scope pushes may load (16). Schema side (§11.5, same policy
  and same reporting surfaces): **import closure** (64), **entries** in one schema map (65,536), **reference
  chain** (64), **supertype chain** (64), and **materialisation depth** (64) — which is where
  `TemplateMaterialiser.MAX_CLOSING_DEPTH` goes, it being a bare constant with nowhere to live until now. What
  still needs deciding per limit is only *where it is counted*: the ones that bound shape are per-container state
  the stream does not keep, where depth was a counter it already had, and the two aggregates (total values,
  foreign schemas) need their own counter since §9.1 is explicit that the total is not bounded by the parts.
