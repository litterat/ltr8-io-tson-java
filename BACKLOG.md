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
spec-required case:

- [ ] **Rename the meta-layer vocabulary the header no longer has.** `TsonCompiledMetaRegistry`,
  `TsonCompiledMetaSchema`, `TsonCompiledSchemaLoader.loadMeta` and `SchemaMetaNameBinder` name a "meta" no
  directive states: each is the compiled vocabulary of an imported schema, or
  the bind context for `schema.meta`'s classes. A rename across some 130 files, done as one IDE refactor.
- [ ] **Automatic reference-closure resolution** ([TSON-DATA] §2.2.3, [TSON-SCHEMA] §3.4.1) — no code
  collects a schema's transitive `!!import` closure, topologically orders it, and resolves it
  dependencies-first; every caller (`TinySchemaImportsCoreTn1Test` included) has to
  already know and hand-sequence the correct registration order itself. Distinct from what
  `TsonCompiledMetaRegistry.withStandardLibrary` already does, which is scoped to just the five bundled
  schemas in a known order, not a general algorithm. Cycle detection is available to build on:
  `resolveLinked` holds a per-thread in-flight set reporting §2.2.3's cycle by the path that closes it.
- [ ] **Read a resolved form by the wait order over its own entries** ([TSON-SCHEMA] §3.4.1, §8.1;
  `SPEC-FEEDBACK.md #4`, the proposal's "Resolved forms"). A resolved form is read as a value of the kernel's
  `schema` under its source's imports (`ResolvedForm.readResolved`, the Class 2 runner's `schema/` layer), so an
  entry whose tag names a constructor or a type the same form declares (`counts => !counted { … }` beside `counted
  => !record { … }`) has no reader, and a `class2/schema/valid` vector cannot state such a schema's output. Read it
  as a schema is resolved instead: the form's own entries made ready by `WaitOrder` over what its imports hold, each
  value read through readers compiled from the entries ready so far, so the fixture's readers come from the fixture.
  `parseResolvedForm`'s grammar-only kind reading stays. Then add `schema/valid` vectors for a same-document
  constructor and value entry, and check that every `-resolved.tn` re-resolves to itself.
- [ ] **An instance of a constructor of types no reader factory backs is a gap.** A schema may declare a
  constructor composing `product` or `atom` (`c => product & { … }`), and the instance `x => !c { … }` would be a
  type, but a type's body is one of the schema model's (`schema.meta`), so `SchemaResolver` refuses it as
  `NOT_IMPLEMENTED` ("builds types, and this library models the body of a type built only by the kernel's and
  meta.tn's constructors"); a constructor composing `top` alone is held as a value. This is what keeps rule 2's
  example pair (`decimal_type => atom & { min?: number }` beside `number => !decimal_type {}`) kernel-only. What a
  generic body for such an instance is, and how data is read against it, is open.
- [ ] **Ingest of resolved output** ([TSON-SCHEMA] §8.1, §10.1) — a schema is always re-resolved from source,
  so no path reads a resolved `{type_name => top}` map and verifies it. §8.1's ingest verifies rather than
  recomputes what an author may have written into a derived fact: a recorded `template_param.type` or `bound`
  must be IS-A the type the held body derives, since a written narrowing lives nowhere else; it also re-runs the
  family checks (no member minted at a use site) over the ingested map and its closure, the §5.11 group
  declaration rules over every `field_group`, and every kind-typed slot's name against its kind, which a resolved
  form's read checks for grammar alone ([TSON-SCHEMA] §4.2, `TsonReadContext.readsSchemaNames`). A resolved
  form is read today only where a caller says it is one (`TsonDataParser.parseResolvedForm`, a reader's
  `readAs(text, "schema")`), with nothing verified.
  `ParameterTypes` already derives the type to check against.
- [ ] **An application is not yet unified with an imported library's entry for it** ([TSON-SCHEMA] §8.1, §8.2). A
  library declaring `int_box => box<int32>` writes `@application:{ name: box  arguments: [{ name: int32 }] }` on that
  key, and a schema importing it that writes `box<int32>` still closes the application to an entry of its own rather
  than resolving to `int_box`. The lookup runs application to entry, so it needs an index over imported keys keyed by
  `DerivedName.canonicalApplication`; `TemplateMaterialiser.ownedBy` is that index for local declarations and
  `SchemaResolver` already reads every import's `@application` into the map the applied-parent fold uses, so the
  index belongs in `ownedBy`, seeded from that map before the driving loop. Decide what a local declaration of an
  application an import already owns then is: the same entry, an alias of it, or two entries §2.2.3 unifies.

- [ ] **A value named through a FIXED field** ([TSON-SCHEMA] §5.12). A qualified name whose segment selects a
  FIXED field of a record value is reported as `NOT_IMPLEMENTED`: bind agreement exempts a FIXED field from having a
  component, so the host holds no value for it and `ValuesByName.fieldOf` finds no component to read. The value is the
  schema's own, the field's pinned value in the record type, so the walk should take it from the declared
  `RecordField` and continue; no test covers the case yet.
- [ ] **A data document's value at a reference-typed position is not read** ([TSON-SCHEMA] §4.2, §5.12). Any schema
  may declare `ref<T>` (SPEC-FEEDBACK.md #7), and a data document's read reaches `AtomTypeReader`, which reports
  `NOT_IMPLEMENTED` there (and `tson-json`'s `AtomReader` compiles the position to an `ErrorReader`): a reference is
  judged by what its name reaches when a schema links, and a data document is never linked. The name is to resolve in
  the governing schema's namespace and
  be judged as a value body's names are (`ValuesByName`), against the schema's value entries.
- [ ] **A value a schema writes at a reference-typed field is not judged** ([TSON-SCHEMA] §4.2, §5.12). In any
  schema, `rpick => { m: ref<order> = os.o1 }` (or `~`) reaches `AtomParsers.forType`'s token-level reading from the
  linker's field-value check, which a reference has none of, and the `UnsupportedOperationException` escapes
  `validateSchema` rather than arriving as a `NOT_IMPLEMENTED` diagnostic. The name is to be recorded with the field's
  slot type and judged as a value body's names are (`ValuesByName.judge`), or the default refused if a reference
  admits none.
- [ ] **JSON bind delivers a `Namespace` at a namespace-typed slot.** The TSON reader builds one where the host
  component is a `Namespace` (`reader.BindTargets`, over a `MapBindReader` that carries the constructor's name);
  `tson-json`'s `BindTargets`/`BindMapBuilder` meet a namespace type's map with no constructor name, so a `Namespace`
  component there is a bind mismatch. Nothing in the JSON stack binds `schema.meta` today, so nothing fails yet; a
  JSON reading of resolved output, a record's `fields` among it, would.
- [ ] **A JSON read naming a value entry reports a gap, not the document's mistake.** A TSON read whose tag names a
  value entry (`!orders { … }`) is a `TYPE_MISMATCH` (`ValueEntryReader`): a value is no type. `tson-json`'s
  `JsonSchemaCompiler` dispatches a `ValueEntry` to the factory of its type's name, finds none, and compiles an
  `ErrorReader`, so the same document in JSON reports `NOT_IMPLEMENTED`. Give the JSON stack the same refusal and a
  `CrossEncodingParityTest` case.
- [ ] **`namespace` is `map`'s sibling, not its refinement** ([TSON-SCHEMA] §4.1, §5.7; `SPEC-FEEDBACK.md #9`).
  Its form is a map's, but `namespace => product & { key_type?: … }` leaves `kind<map>` without the namespace types:
  §5.7 refuses `map ^ { … }` making the required `key_type` optional, and it is optional only because `field_map`'s
  key family is its record's `name_type`, a value depending on its host. Once dependent fields exist
  (a Revision 39 proposal), make it `namespace => map ^ { key_type: identifier_kind … }` with `key_type`
  required, `field_map` a template applied to its record's `name_type`, and add a vector that `kind<map>` holds a
  namespace type.
- [ ] **A parameter in a record template has no channel of its own** ([TSON-SCHEMA] §5.10). An open record's
  parameter is a `type_ref` whose `name` is the parameter, or an unquoted token in `record_field.value`, told from a
  type or a literal by §5.10's shadowing rule, in resolved output as in source. Give the slots that can hold one a
  channel: `record_field` `( type: type_kind | type_param: param_name )` and `( value: value | value_param: param_name
  )`, `type_argument` a `param` member, so `type_ref.name` always names an entry. Sequenced with the collapse of
  `kind_type`, `ref_type` and the name-only `type_ref` into one reference atom, a Revision 39 direction, which needs
  exactly that, and with what `@application` means on a declared entry that is no record.

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

- [ ] **Relax [TSON-JSON] §3.3's leading-member rule, for Revision 39.** §3.3 requires `$type` (after any `$import`)
  to lead its object and argues that code-point sorting preserves that; with `record.name_type` admitting `$ref`,
  `$id` and `$defs` as field names, those sort first, so RFC 8785 canonical output of a tagged value —
  `{"$ref": "#/a", "$type": "sub", …}` — is refused as a misplaced `$type`. Decided: the reader admits the annotation
  members anywhere within the object's leading run of `$`-named members, a run bounded by the record's declared `$`
  fields so the lookahead stays fixed by the schema. Implement it in the JSON readers (`RecordPlan`,
  `DispatchChoiceReader`, `DispatchDeclaredReader`) and edit §3.3 with it, as a Revision 39 proposal; Revision 38
  keeps the strict lead.

- [ ] **A JSON document has no in-band way to name its schemas — §3.4's second route.** The out-of-band route
  is built (`Json.withSchemas`, `treeReader().withSchema(uri).readAs(...)`, and `tson validate --schema --type`),
  and it is the one the spec calls the expected production route. What is left is the in-band one: a root value
  that is or is wrapped by an annotation object led by `$import` — a non-empty array of schema references — and
  `$type`, both REQUIRED on that route. Today a root led by `$import` is declined as `NOT_IMPLEMENTED`
  (`InBandBinding`). The work: read the leading `$import`/`$type` before any schema is in hand, refuse an `$import`
  that is not a non-empty array of strings (a resolver error), read the root with the `$import` member consumed,
  and check agreement with an out-of-band binding — the two as **sets** of canonical identities, and the type by
  name (§3.4, §3.5); disagreement is a resolver error, never a precedence question.
  **Several imports need a linking seam first.** A read under several schemas is one namespace over their closures
  (§3.4), which takes the linker: `TsonCompiledSchemaRegistry.get(List, receiver)` links one on the TSON side, but
  `tson-json` holds only a `TsonSchemaLoader` of already-linked schemas and cannot depend on `tson-compiler`. The
  JSON registry needs a loader that also links an import set (say a `TsonSchemaLoader` method taking several
  identities, implemented where `Tson` wires it), and the same seam gives the out-of-band route a `withImports`
  beside `withSchema`. One import needs none of this.

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

- [ ] **The choice and declared families do not state their rules from `base.diagnostics`.** Records, arrays,
  sets, tuples and maps do. `tson-compiler` states its dispatch diagnostics in `NamedDispatchReader`,
  parameterised over a "candidate noun" so one class serves both a choice and a declared position; `tson-json`
  words each in its own dispatcher (`DispatchChoiceReader`, `DispatchDeclaredReader`), and `CrossEncodingParityTest`
  compares only their codes and pointers. The rule classes want the same parameterisation, so one sentence
  serves both positions in both encodings and the parity cases can move to `sameRule`.

- [ ] **A pinned schema reference is not verified in the JSON stack.** `JsonCompiledSchemaRegistry.get`
  canonicalises before it asks its `TsonSchemaLoader`, and the loader takes a canonical identity and nothing
  else, so the `?sha256=` of an out-of-band `withSchema(uri)` is
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

- [ ] **A JSON diagnostic names the type at the end of an alias chain, not the alias the author wrote.**
  `JsonSchemaCompiler` collapses an `ALIAS` entry onto its target's reader ([TSON-SCHEMA] §8.3 permits
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
    names, the alias index (`AliasChain.namesMeaning` beside `Subsumption.admitting`), and
    `DiscriminationClass`. The lookahead that reads them stays with each encoding.
  - **The same-named pairs** — `ValueIdentity`, `AliasChain`, `DeferredTypeReader`, `ErrorReader`,
    `CompiledReaders`, and the compile loop itself (resolve, cycle, gap reader, the bind-exception order), which is
    one algorithm over two reader types.
  What stays per encoding is the event loop — TSON's positional records, `_` and annotations have no
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

- [ ] **No test pins [TSON-SCHEMA] §5.9 rule 8.** A subtracted construction whose head is a constructor
  (`my_record => record - { groups }`) resolves with an empty chain, so it is no constructor, and `!my_record {
  … }` is read as a value of it, a value entry, rather than building a type; nothing in the suite or the corpus
  exercises it. Add a vector stating the outcome §5.9 rule 8 gives.
- [ ] **A kind's member list names a minted application.** The refusal of a name at a `kind<type>` slot lists the
  kind's members, and the list holds `kind_type_type_c430c650`, the entry minted for the application `kind<type>`
  itself: a `kind_type` instance IS-A `type`, so the construction walk admits it. A minted name is no spelling an
  author can write (`InternalName`), so `KindMembers` should pass over `@synthetic` entries, in the message's list
  and in `expected` alike, or the walk should exclude an application's minted entry from every kind.
- [ ] **A linker refusal of an atom application names its minted entry.** `pick => { m: ref<record> }` reads
  "'ref_type_record_3034e1a1' is a reference whose 'of' names 'record', a constructor …", and an unresolved
  `ref<nope>` or `kind<nope>` the same way: an application nothing declares is its expansion, which
  `EntryDisplayName` gives no authored name, so `TsonSchemaLinker.validateEntry`'s subject falls back to the key.
  Name it by the application the key's facts carry (`ref<record>`), or state the refusal without a subject, since
  the diagnostic is already located at the declaration that wrote it.
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

- [ ] **A one-position tuple is named in a diagnostic in array spelling.** `only: tuple1<text>` given `[]` reports
  `'[text]' has 1 positions, found only 0 elements`: `tuple1<text>` expands to a tuple no author named, and
  `EntryDisplayName` renders a one-element `TupleBody` as `[text]`, which is the array. Render no name for it, as a set
  gets none (`EntryDisplayName.authored`), and let `TupleDiagnostics` state the rule alone where it has no name, as
  `ArrayDiagnostics` does.

- [ ] **A map key's pointer step is the key's Java rendering.** `MapAbstractReader.readInto` replaces the `?`
  placeholder for any key that is not a single token with `ValueIdentity.nameOf(key)` or `String.valueOf(key)`, so
  `counts: { @doc:"x" 0x10 => oops }` reports `/counts/16` where the unannotated key reports `/counts/0x10`, and a
  compound key `grid: { {x: 1 y: 2} => oops }` reports `/grid/TsonRecord[fields={x=1, y=2}, …]` in tree mode and
  `/grid/Point[x=1, y=2]` when bound, naming the reading application's class; the duplicate-key message quotes the
  same rendering. An annotated single-token key takes its step from the token's spelling, which means keeping the key
  token's text across the annotations before it; a compound key keeps `?` until it has a pointer form. The JSON
  encoding writes such a map as pairs and reports `/grid/0/1`, so the two encodings disagree about the step too.

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

- [ ] **The shared corpus states little about [TSON-DATA] §2.2.1's content-hash pins.** One vector pins a fixture
  wrongly (`class2/link/invalid/one-identity-imported-under-two-pins`); two MUSTs go unmeasured across
  implementations: a query parameter that is not a recognized hash algorithm is an error rather than silently
  retained, and a hashed reference whose target carries no id line is refused (the hash input having no boundary).
  Both are expressible at the `class2/validate` layer, where a subject's own `!!import` names a corpus fixture the
  runner serves.
  What is *not* expressible is which registration route recorded the hash — a corpus subject always reaches
  its schema through the runner's `SchemaSource`, where a host application registering a schema from text it
  holds is the case this repo covers in `TsonValidateTest`. The category is settled for all three: [TSON-SCHEMA]
  §10.2 makes a mismatch a resolver error, and the other two are §2.2.1 errors with no category of their own, which
  [TSON-DATA] §8.1 gives to the layer that detects them -- the resolver, following the reference.

- [ ] **Counting the rest of [TSON-DATA] §9.1's resource limits, and [TSON-SCHEMA] §11.5's.** The whole table is
  stated: `spec/m/policy.tn`'s `limits` declares all seventeen, optional with the spec's defaults, and `LimitsPolicy`
  and `CliPolicy.CliLimits` carry each one, so every limit is configured and reported. Nesting depth and wait depth
  are counted.
  What is left, per limit, is the counter, a `LIMIT_EXCEEDED` refusal naming the limit and the configured threshold,
  and a `--max-…` flag beside `--max-depth` in `PolicyOptions`. Document side: **token length**, **decoded text
  length** after escape processing, **numeric literal length** (annotated tokens included), **decoded binary size**
  per `!bytes` value, **document size**, **elements** per array or set, **entries** per map, **fields** per record,
  **annotations** on one value, and **total values**. Schema side: **import closure**, **entries** in one schema map,
  **alias chain**, **supertype chain**, and **materialisation depth** — which `TemplateMaterialiser` enforces as
  the bare constant `MAX_CLOSING_DEPTH` at the default, and which moves to reading `maxMaterialisationDepth`. (Wait
  depth, the sixth §11.5 limit, is counted, by `WaitOrder`, with its `--max-wait-depth` flag.) What
  still needs deciding per limit is *where it is counted*: the ones that bound shape are per-container state the
  stream does not keep, where depth was a counter it already had, and the aggregate (total values) needs its own
  counter since §9.1 is explicit that the total is not bounded by the parts.
