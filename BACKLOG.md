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

Every real schema resolved so far (the six bundled schemas, meta.tn to policy.tn, and hand-built test fixtures)
happens to fit a narrow shape this pipeline already handles — declared in dependency order, with
callers hand-sequencing registration themselves. What follows is what's missing for the *general*,
spec-required case:

- [ ] **A tree read does not read a dependent value against a sibling written in place** ([TSON-SCHEMA] §5.2).
  `setting => { kind: meta.type_expr  v?: kind! }` with `kind: { of: !meta.array { element_type: int32 } }` reads `v`
  against the array in bind mode, where the sibling binds to its `TypeInline`; a tree read holds the sibling as the
  record it is written as and reports `v` `NOT_IMPLEMENTED` (`RecordAbstractReader.Dependents.heldInPlace`, and
  `tson-json`'s `RecordReader`). Build the `TypeInline` a tree's wrapper record denotes (its `of`'s tag and fields,
  read against the kernel's model) and read the value through its reader, in both encodings. Where the sibling holds a
  dependent type no reader is needed: §5.2 refuses it as naming no type a value can be read against, and a tree read
  reports `NOT_IMPLEMENTED` there too (`!meta.record_field { type: { of: !meta.dependent { on: kind } }  role: DEFAULT
  value: 5 }` read as data).
- [ ] **A syntax error in a data document read under a schema is filed as a validation error** ([TSON-DATA] §8.1).
  `!cell { kind: text  v: kind! }` read against its schema reports the separation error under the code
  `VALIDATION_ERROR` ("expected well-formed TSON"), which the conformance runner maps to `validation`; §8.1 files
  adjacency and separator errors under `parser`. No validate vector states `parser`, so nothing pins it. Give the
  read a code the runner maps to `parser`, then add the vector.
- [ ] **A literal under a closed type in an open record is judged only when the template closes**
  ([TSON-SCHEMA] §5.10). `t => <T> { a: T  n?: int8 ~ 500 }` loads, the default held until an application closes the
  field; the type is closed, so its verdict cannot vary by application and §5.10 judges it at the declaration, as
  §5.2 judges any default or pin. Judge a literal whose field type mentions no parameter when the template is
  declared, and add a `schema/invalid` vector for the out-of-range default.
- [ ] **An open record's own references are judged only where it is applied** ([TSON-SCHEMA] §5.10, §3.3). An open
  record is read at its declaration, and a name its fields or parents write that is neither one of its parameters nor
  an entry is an unresolved reference there; the linker checks only arity over an open record
  (`TsonSchemaLinker.checkOpenArity`), so `box => <T> { v: T  w: no_such_type }` gets its verdict at each application,
  retargeted to `box` (`heldDeclarationNaming`), and none while nothing applies it. Judge the references that are no
  `ParamRef` with `validateTypeRef` at the declaration, and add a `schema/invalid` vector for the unapplied case.
- [ ] **An application through an alias is untested** ([TSON-SCHEMA] §8.3). An application through an alias of an
  open record applies the open record (`TemplateMaterialiser.templateHead`: `bx<text>` over `bx => box` closes to the
  record `box<text>` closes to), and one through an alias of a constructor constructs (`ref_of<order>` over `ref_of =>
  meta.ref`); no test or vector pins either. Add a `schema/valid` vector for each, with the expected resolved form.
- [ ] **An aliased application across imports is untested at the IS-A edge fold.** An imported schema's
  `@meta.application` is the application as written (`box<user_id>`), and reaches the importer's edge fold in that
  form, where the fold compares canonical applications ([TSON-SCHEMA] §8.2), arguments past their aliases. Add a link
  vector with an aliased application declared in an import and composed as a parent in the importer, and reduce on
  ingest if it misses.
- [ ] **Automatic reference-closure resolution** ([TSON-DATA] §2.2.3, [TSON-SCHEMA] §3.4.1) — no code
  collects a schema's transitive `!!import` closure, topologically orders it, and resolves it
  dependencies-first; every caller (`TinySchemaImportsCoreTn1Test` included) has to
  already know and hand-sequence the correct registration order itself. Distinct from what
  `TsonCompiledVocabularyRegistry.withStandardLibrary` already does, which is scoped to just the six bundled
  schemas in a known order, not a general algorithm. Cycle detection is available to build on:
  `resolveLinked` holds a per-thread in-flight set reporting §2.2.3's cycle by the path that closes it.
- [ ] **Read a resolved form by the wait order over its own entries** ([TSON-SCHEMA] §3.4.1, §8.1's
  "Reading a resolved form"; the proposal's "Resolved forms"). A resolved form is read as a value of the kernel's
  `meta.schema` under its source's imports (`ResolvedForm.readResolved`, the Class 2 runner's `schema/` layer), so an
  entry whose tag names a constructor or a type the same form declares (`counts => !counted { … }` beside `counted
  => !meta.record { … }`) has no reader, and a `class2/schema/valid` vector cannot state such a schema's output. Read it
  as a schema is resolved instead: the form's own entries made ready by `WaitOrder` over what its imports hold, each
  value read through readers compiled from the entries ready so far, so the fixture's readers come from the fixture.
  `parseResolvedForm`'s grammar-only kind reading stays. Then add `schema/valid` vectors for a same-document
  constructor and value entry, check that every `-resolved.tn` re-resolves to itself, and drop
  `ResolvedFixtureTest`'s reading of a fixture beside its source, which each library's needs because its instances
  (net.tn's `ipv4 => !net.ipv4_type {}`) name constructors the form declares.
- [ ] **An instance of a constructor composing `record` is refused** ([TSON-SCHEMA] §4.1, §5.7). `c => record & {
  extra: text }` is a constructor whose instances are record types carrying one facet more, and `x => !c { fields: {
  id => { type: text } }  extra: "hello" }` should follow record composition: `x` is a record type read by the record
  reader (`!x { id: abc }` validates, in tree and bind mode) and its body holds `extra`. Today `x` is refused as
  `NOT_IMPLEMENTED` at `/x`, in either declaration order and with `extra?: text` left unwritten: no class binds `c`, so
  `vocabulary.instance` throws `MissingBindingException` and `SchemaResolver.constructed` sends it to `unmodelledType`.
  A refinement adding no field already binds to its source's body (`my_array => array ^ { ordered?: = false }`,
  `ConstructorLevelDisciplineTest`). Decide where an added facet lives in the schema model and in the resolved form,
  then read the instance as its source's body with the facet held. An instance of a constructor composing `atom`,
  `product` or `sum` directly stays refused: it is a type of a new form, which a processor backs with readers and
  writers of its own.
- [ ] **Ingest of resolved output** ([TSON-SCHEMA] §8.1, §10.1) — a schema is always re-resolved from source,
  so no path reads a resolved `{type_name => top}` map and verifies it. §8.1's ingest re-runs the
  family checks (no record written in place a member of anything) over the ingested map and its closure, the §5.11 group
  declaration rules over every `meta.field_group`, and every kind-typed slot's name against its kind, which a resolved
  form's read checks for grammar alone ([TSON-SCHEMA] §4.2, `TsonReadContext.readsSchemaNames`). A resolved
  form is read today only where a caller says it is one (`TsonDataParser.parseResolvedForm`, a reader's
  `readAs(text, "schema")`), with nothing verified.

- [ ] **An entry reached by two import routes keeps only the first route's `subtypes`** ([TSON-SCHEMA] §3.3.4, §8.2).
  `SchemaResolver.mergeImports` and `TsonSchemaLinker.mergeImports` keep the first copy of an entry several imports
  bring, carrying the `subtypes` that route's own link derived; a subtype only the second route's link credited is
  dropped, so the importer's view of the entry is incomplete and its chain facts depend on import order. No bundled
  closure hits it. Union the `subtypes` of the copies (the entry's body otherwise agreeing, as the identity check
  already requires), or derive `subtypes` once over the importer's whole closure rather than carrying them in.

- [ ] **A value named through a FIXED field** ([TSON-SCHEMA] §5.12). A qualified name whose segment selects a
  FIXED field of a record value is reported as `NOT_IMPLEMENTED`: bind agreement exempts a FIXED field from having a
  component, so the host holds no value for it and `ValuesByName.fieldOf` finds no component to read. The value is the
  schema's own, the field's pinned value in the record type, so the walk should take it from the declared
  `RecordField` and continue; no test covers the case yet.
- [ ] **JSON bind delivers a `Namespace` at a namespace-typed slot.** The TSON reader builds one where the host
  component is a `Namespace` (`reader.BindTargets`, over a `MapBindReader` that carries the constructor's name);
  `tson-json`'s `BindTargets`/`BindMapBuilder` meet a namespace type's map with no constructor name, so a `Namespace`
  component there is a bind mismatch. Nothing in the JSON stack binds `schema.meta` today, so nothing fails yet; a
  JSON reading of resolved output, its schema map among it, would.
- [ ] **A JSON read naming a value entry reports a gap, not the document's mistake.** A TSON read whose tag names a
  value entry (`!orders { … }`) is a `TYPE_MISMATCH` (`ValueEntryReader`): a value is no type. `tson-json`'s
  `JsonSchemaCompiler` dispatches a `ValueEntry` to the factory of its type's name, finds none, and compiles an
  `ErrorReader`, so the same document in JSON reports `NOT_IMPLEMENTED`. Give the JSON stack the same refusal and a
  `CrossEncodingParityTest` case.
- [ ] **§5.7: a field restated where no closed record is on the chain is not judged for its type.** The link
  judges a restated type against the nearest closed records on the chain (`TsonSchemaLinker.checkRestatedFieldTypes`).
  `dog => pet<dog_type> & { pet: cat_type }`, where no entry names `pet<dog_type>`, has an edge only to
  the open family base `pet`, whose `pet` is `T`, so it links; so does `wrap<text>` from `wrap => <T> box<T> & { v:
  int32 }` where nothing denotes `box<text>`. A subtraction's tightening entries, which §5.9's rule 4 puts under
  §5.7's rules, are not judged either: its chain is empty. The resolver holds the inherited field
  (`resolveTighteningField`'s `inherited`, substituted) but not the IS-A; a deferred judgement there, or the inherited
  fields carried to link, would close all three without reporting a case the link already judges twice.

- [ ] **A dependent field whose sibling names an unbound type fails each read, not the bind-mode compile.** With
  `payload: kind!` and `kind` admitting a type no class binds, a document naming that type throws
  `MissingBindingException` from `RecordBindReader.descriptorFor` during the read. A consumer turns that into a server
  error that any sender can trigger. The method's own contract is that a missing binding is a misconfiguration
  reported when the schema compiles in bind mode, but a dependent field's target is chosen per document, so it is
  reached lazily. When a record holding a dependent field compiles in bind mode, check every type the sibling's
  reference admits (narrowed by a pin or by a discriminator's members) and refuse the schema there, naming the field
  and the first unbound type. The schema narrows that set by refining the reference (`meta.ref<meta.record>` under
  `meta.ref<meta.type>`). A free `meta.type_ref` sibling admits the kernel's records and every declared type, so it
  compiles in bind mode only where it is refined. The same set, as an accessor on the compiled schema, is what a
  consumer checking it at startup assembles by hand today (`ltr8-io-tson-java-http`'s `ChosenTypes`). Do the same in
  `tson-json`'s bind path.

- [ ] **A reference inside an annotation's value is not judged** ([TSON-SCHEMA] §5.12, §6). With `binds =>
  @meta.annotation [meta.ref<protocol>]`, a schema writing `@binds:[nothing_declares_this]` loads: the annotation
  value's shape is checked, but no name in it, so the name arrives unresolved. An annotation's value is judged against
  its declared type at load, and a name in it is judged as one in a field value is. So run the link's field-value check
  (`TsonSchemaLinker.checkFieldValues`, with `ValuesByName.reach` for a reference over values) over every annotation
  value in a schema document, the name resolved in the annotating document's namespace (§3.3.3) and reported at the
  annotation. Add `link/invalid` vectors for a type reference and a value reference that name nothing, and a
  `link/valid` one. A consumer pins today's behaviour (`ltr8-io-tson-java-http`,
  `UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged`).

- [ ] **An annotation inside a value read through an imported type's reader resolves in the declaring schema's
  namespace.** [TSON-SCHEMA] §3.3.3 resolves an annotation in the annotating document's namespace, and a value
  entry's payload (or any value) read through a reader its type's declaring schema compiled looks the annotation up
  in that schema's closure: `@title` on a value inside `!api { … }`, where `api` is declared by a schema
  importing meta.tn alone, is refused though the annotating schema imports core, which declares `title`. The read
  needs
  the annotating document's annotation scope beside the reader's own (`AnnotationTypes`), consulted where the reader's
  misses ([TSON-SCHEMA] §3.3.3, §9).

- [ ] **A restatement that changes a pin is accepted** ([TSON-SCHEMA] §5.7, §5.2). With `base => { pet_type: text =
  "pet"  name: text }`, `dog => base & { pet_type: = "dog" }` loads; §5.7's restatement "MUST NOT change a pin", and a
  discriminated family's arrangement (§5.2) rests on it — a subtype of a member that could re-pin its parent's
  selector would break the family's one-level dispatch. Refuse a FIXED restatement whose value differs, under the
  field type's own equality (§5.5, so `= 255` over `= 0xFF` is the identity case), in composition and refinement
  bodies alike, at the restating declaration. Add `schema/invalid` vectors for both operators and a `schema/valid` one
  for the identity restatement.
- [ ] **A nested schema map declared twice in one document is accepted** ([TSON-SCHEMA] §2.1, §2.2.3;
  [TSON-DATA] §2.6). `geo => !schema { a => … }` beside `geo => !schema { b => … }` in one schema map loads, its
  members merged. The schema body is a map "in syntax and in semantics" (§2.1), so the repeated key is a duplicate
  (a resolver error); §2.2.3's union of a nested map is across the documents of a closure, each contributing by
  declaring the map once. Add a `schema/invalid` vector.

- [ ] **A loop of aliases is reported as unproductive recursion** ([TSON-SCHEMA] §3.4.1 rule 4, §8.3). `a => b`
  beside `b => a` is refused with §5.10.1's message ("can never be satisfied by any document … nothing in that chain
  can be left out"); §3.4.1 says a loop of aliases is not a wait cycle and §8.3 refuses it as an alias chain with no
  terminal. The verdict is right; report the chain (`'a' is an alias of 'b', an alias of 'a', which names no type`)
  once, at the first entry, before the productivity check sees it.
- [ ] **A tag naming a nested member by its bare name is a mismatch, not an unresolved name** ([TSON-SCHEMA] §3.3,
  §7.2). Under `geo => !schema { address => { … } }` and `order => { shipping: geo.address }`, `!order { shipping:
  !address { … } }` reports `TYPE_MISMATCH` ("'address' is not admissible at a 'geo.address' position"), though
  `address` resolves to nothing at the root — a bare name "names the root's `address` or fails to resolve" (§3.3).
  That is `UNKNOWN_TYPE_REF`, the resolver category, as the same tag already gets at a `meta.declared` position.
  Resolve the tag before judging admissibility at every typed position, and add a `class2/validate` vector.

## Checked annotations

[TSON-SCHEMA] §6 defines the category and §5.4's `@meta.disjoint` is the precedent both follow: an annotation with
**no** decode force and load-time force, verified against a fact the resolver derives, two outcomes and no
third — verified silently, or a resolver error at schema load. §6 also settles what this implementation had to
guess at: a checked annotation is an assertion in *either* declaration position and a processor MUST consult
both spellings, which is what `@meta.disjoint` already does. `@meta.disjoint` is the category's only member, and the one
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
  to lead its object and argues that code-point sorting preserves that; with `meta.record.name_type` admitting `$ref`,
  `$id` and `$defs` as field names, those sort first, so RFC 8785 canonical output of a tagged value —
  `{"$ref": "#/a", "$type": "sub", …}` — is refused as a misplaced `$type`. Decided: the reader admits the annotation
  members anywhere within the object's leading run of `$`-named members, a run bounded by the record's declared `$`
  fields so the lookahead stays fixed by the schema. Implement it in the JSON readers (`RecordPlan`,
  `DispatchChoiceReader`, `DispatchDeclaredReader`) and edit §3.3 with it, for Revision 40; Revision 39 keeps
  the strict lead.

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
  it with `UseSite.named` over `EntryDisplayName`, which also renders a type written in place as the form written;
  this stack has no counterpart. Owed with the facade, which is what will seed the root for a real read rather than
  a test doing it.

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
  what `meta.record.name_type` decided for JSON and Java tests alone pin today (`JsonFieldNameTypeTest`,
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
  through: §6's name-position channel — `@doc` before a declared name, and the resolver's own
  `@meta.application` — reaches `TsonSchema.entries()` as key annotations (`Namespace.getAnnotations`) and survives
  linking and the import merge. The *document* round trip is what does not: reading a resolved-form
  `{type_name => top}` document back binds the map with no key annotations at all, and nothing writes them, so
  `ResolvedFixtureTest` cannot compare the fixtures' `@meta.application` and `@atom.ordering`/`@atom.bounded`/
  `@atom.exact` key annotations and the entries compare equal for the wrong reason. Fixing the read side lets that
  test read those keys like anything else, which is the whole of the payoff — `ResolvedFixtureTest` is the only
  consumer, and the emit side behind it has none. §8.1 settles the shape: `@meta.application` read as stated,
  author-written key annotations preserved as data.

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

- [ ] **`policy.script_policy` is the token policy under another name** ([TSON-DATA] §8.2). Part 1 names the two
  policies "so that two implementations reporting them agree on what they are called", and `policy.processor` writes
  `token_policy: policy.script_policy` beside `identifier_policy: policy.identifier_policy`. Rename the record
  `policy.token_policy`, with `identifier_policy`'s doc reference and the Java `ScriptPolicy` following it. Then
  restamp `policy.tn` and the schemas that pin it. The Revision 39 change log keeps the received name, with the
  policy keeping its name in prose, so this waits for a revision to carry it.

- [ ] **Schemaless `!integer` and `!regex` are refused** ([TSON-DATA] §5.5, §5.6). `{ a: !integer 5 }` and
  `{ a: !regex "^a+$" }` with no `!!import` report `UNKNOWN_TYPE_REF` ("not a built-in type"); both are rows of the
  built-in vocabulary (the kernel's `integer` and `regex`), and `!int64`, the other kernel row, is accepted. Add the
  two rows to the schemaless vocabulary, check the rest of §5.3–§5.6's table against it, and add `class1` vectors for
  each.
- [ ] **A `precision` above 9 loads** ([TSON-SCHEMA] §5.5, §7.4's coherence paragraph). `!temporal.time_type {
  precision: 10 }` and `!time ^ { precision: 10 }` resolve; a precision above 9 is a schema-load coherence error on
  `time_type`, `datetime_type` and `duration_type`. Add the check beside the families' other coherence rules (so it
  runs again at materialisation) and `schema/invalid` vectors.
- [ ] **A float format this library does not implement crashes as a schema error.** `!numeric.float_type { format:
  BINARY16 }` reports `SCHEMA_ERROR` wrapping `IllegalArgumentException: No enum constant …FloatType.Format.BINARY16`:
  `numeric.ieee_format` declares `BINARY16`, `BINARY128` and `BINARY256`, and `FloatType.Format` holds only
  `BINARY32` and `BINARY64`. The schema is valid, so this is a gap: either implement the three grids in `FloatParser`
  or bind the three members and refuse a body naming one as `NOT_IMPLEMENTED`, never through the enum's lookup.
- [ ] **`tson compile` and `tson validate` refuse a schema with no `!!id`** ([TSON-SCHEMA] §2.2.1, §10.1).
  `compile` on an id-less schema reports `SCHEMA_ERROR` "!!id is required to register this schema", and a data file
  importing it by a relative reference (`!!import:"noid.tn"`) reports "schema noid.tn has no !!id". §10.1 lets an
  id-less schema be registered under an application-supplied identity — it has no published identity and cannot be
  hash-pinned — so the CLI, which holds the file, can register it under its file URI and resolve a relative import
  against the importing file's. A schema error is the wrong outcome either way; `SchemaResolver` throws it.

- [ ] **A float's bounds skip NaN and the infinities** ([TSON-SCHEMA] §5.5, §5.7). `FloatParser`'s
  validation returns before the bound checks for a NaN `allow_nan` admits and an infinity `allow_infinity` admits, so
  `!float64 ^ { max: 100 }` accepts `+.inf` and `.nan`. An infinity is ordered, so judge it against the bounds like any
  value. A body with any bound admits no NaN, `allow_nan` deciding only where no bound is written (§5.5).
  The parser is `tson-atom`'s and both encodings read through it, so one fix serves both. Add `class2/validate` vectors
  for `+.inf` above a `max`, `-.inf` below a `min` and `.nan` under a bound, and a `CrossEncodingParityTest` case.

- [ ] **A float's bounds are compared after rounding** ([TSON-SCHEMA] §5.5). §5.5 validates a float's bounds
  against the value as written, before rounding; `FloatParser` compares the rounded `double` with
  `bound.doubleValue()`, so `!float32 ^ { max: 1.0 }` accepts a written `1.00000001`, which rounds to `1.0`. Compare
  the written decimal with the exact `number` bound, with a `class2/validate` vector at a bound's edge and a
  `CrossEncodingParityTest` case.

- [ ] **No test pins [TSON-SCHEMA] §5.9 rule 8.** A subtracted construction whose head is a constructor
  (`my_record => record - { groups }`) resolves with an empty chain, so it is no constructor, and `!my_record {
  … }` is read as a value of it, a value entry, rather than building a type; nothing in the suite or the corpus
  exercises it. Add a vector stating the outcome §5.9 rule 8 gives.
- [ ] **No test pins a tag at a position typed in place** ([TSON-SCHEMA] §7.2). Beside `text_list => [text]` and
  `r => { t: [text] }`, `!r { t: !text_list [a] }` is a validation error at `r.t`: a type written in place admits no
  tag, and the message names the position by its field path and its form (`[text]`), never a name it does not have.
  Pin it with a tree-mode, a bind-mode and a JSON test (`"$type": "text_list"`), and a `class2/data/invalid` vector.

- [ ] **Whether a closed set of text binds to a host enum.** [TSON-SCHEMA] §7.4 lets an enum bind to "a host enum by a
  mapping the binder owns". `text_enum` is gone from the kernel, and a closed set of text that is no name is now
  `unicode.text_type`'s `members` facet (`!text ^ { members: [...] }`), an atom rather than an enum, so a schema that bound
  such a set to a Java enum may have lost the mapping. Check what `ltr8-bind` does with a `unicode.text_type` carrying
  `members` at an enum-typed component; if it refuses, decide whether the binder maps a member set as it maps an
  enum's members, and pin the answer in a bind-mode test.


- [ ] **A map key's pointer step is the key's Java rendering.** `MapAbstractReader.readInto` replaces the `?`
  placeholder for any key that is not a single token with `ValueIdentity.nameOf(key)` or `String.valueOf(key)`, so
  `counts: { @doc:"x" 0x10 => oops }` reports `/counts/16` where the unannotated key reports `/counts/0x10`, and a
  compound key `grid: { {x: 1 y: 2} => oops }` reports `/grid/TsonRecord[fields={x=1, y=2}, …]` in tree mode and
  `/grid/Point[x=1, y=2]` when bound, naming the reading application's class; the duplicate-key message quotes the same
  rendering. A schemaless duplicate whose key is a reference (`set<text>` beside `set< text >`) is refused correctly
  and printed as `duplicate key '?'`; its spelling is the application's canonical text. An annotated single-token key
  takes its step from the token's spelling, which means keeping the key token's text across the annotations before it;
  a compound key keeps `?` until it has a pointer form. The JSON encoding writes such a map as pairs and reports
  `/grid/0/1`, so the two encodings disagree about the step too.

- [ ] **A tuple refused a scalar prints the event record.** `only: one_text` over `one_text => !meta.tuple [{
  element_type: text }]` given `a` reports `expected a tuple (array-shaped) for 'one_text', found TokenEvent[text=a,
  form=UNQUOTED, position=…]`: `TypeRefCheck.describe`
  names a record, map, array, `{}` and `_` and falls back to `String.valueOf` for anything else, so a scalar token
  arrives as its `toString`. It has 18 callers, all exposed the same way, and a constructor body's reference-typed slot
  given a quoted token (`!meta.array { element_type: "text" }`) prints it the same way through another path; a scalar
  wants its token text (and form, where quoting is the difference). `JsonAtoms.describe` is the JSON side's counterpart
  and `CrossEncodingParityTest` the check that the two describe one value alike.

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
  stated: `spec/m/policy.tn`'s `policy.limits` declares all sixteen, optional with the spec's defaults, and `LimitsPolicy`
  and `CliPolicy.CliLimits` carry each one, so every limit is configured and reported. Nesting depth and wait depth
  are counted.
  What is left, per limit, is the counter, a `LIMIT_EXCEEDED` refusal naming the limit and the configured threshold,
  and a `--max-…` flag beside `--max-depth` in `PolicyOptions`. Document side: **token length**, **decoded text
  length** after escape processing, **numeric literal length** (annotated tokens included), **decoded binary size**
  per `!bytes` value, **document size**, **elements** per array or set, **entries** per map, **fields** per record,
  **annotations** on one value, and **total values**. Schema side: **import closure**, **entries** in one schema map,
  **alias chain** and **supertype chain**. (Wait depth is counted, by `WaitOrder`, with its `--max-wait-depth` flag.) What
  still needs deciding per limit is *where it is counted*: the ones that bound shape are per-container state the
  stream does not keep, where depth was a counter it already had, and the aggregate (total values) needs its own
  counter since §9.1 is explicit that the total is not bounded by the parts.
