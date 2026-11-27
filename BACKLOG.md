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
- [ ] **A value entry is not read against a field typed by an application written in place** ([TSON-SCHEMA] §4.1,
  §5.10). With `box => <T> { v: T }` and `holder => { b: box<text> }`, `h1 => !holder { b: { v: "x" } }` is refused as
  `NOT_IMPLEMENTED` (`UnclosedApplicationReader`): the application closes in place once every declaration is ready, and
  the value entry is read before then. Inside a container written in place, `holder => { b: [box<text>] }`, the entry
  is refused even for `b: []` ("no ValueReaderFactory registered for constructor 'meta.apply'"), and the same holds
  inside a namespace value. A data document and a JSON document read both types. Read a value entry after the
  applications its type holds have closed, or hold it as a dependent value is held until link. Add `schema/valid`
  vectors for the field and the container form.
- [ ] **A record-typed annotation on a declaration needs a host class** ([TSON-SCHEMA] §6). With `note =>
  @meta.annotation { b: text }`, `page => @note:{ b: "x" } { title: text }` fails `tson compile` with `BIND_MISMATCH`
  ("no bound Java class for 'note'", exit 78), on a declaration or a field; an atom, enum or array annotation type
  loads. `SchemaResolver`'s annotation read falls back to a tree for a foreign annotation alone, where a value entry's
  payload always falls back. Read a local annotation's value as a tree where no class binds its type.
- [ ] **A record written in place carries its fields' annotations beside its payload** ([TSON-SCHEMA] §3.3.3).
  The desugarer keeps the annotations on a field of `r => { at: { @note:"hi" a: int32 } }` off the `meta.record_field`
  payload it writes, in `SchemaPositions.WrittenField`, for `DefinitionResolver` to resolve in the schema's namespace;
  a payload's annotations resolve there now, so write them on the payload and keep only the position beside it.
- [ ] **A value at an abstract record in place is refused with a reason that cannot apply** ([TSON-SCHEMA] §5.10.1,
  §7.2). Under `r => { at?: { kind: text =?  x: int32 } }`, `!r { at: { kind: a  x: 1 } }` reports "no schema in this
  closure declares a subtype of '{ kind: text  x: int32 }', and it is abstract, so nothing can stand here -- the schema
  that declares its subtypes is not among those this document's own schema imports"
  (`RecordExtensionDiagnostics`). No schema can declare a subtype of a record nothing can name; the same message
  follows an abstract application closed in place (`s?: shape<circle>`). Say that a record written in place that is
  abstract is empty, at both encodings' readers.
- [ ] **A syntax error in a data document read under a schema is filed as a validation error** ([TSON-DATA] §8.1).
  `!cell { kind: text  v: kind! }` read against its schema reports the separation error under the code
  `VALIDATION_ERROR` ("expected well-formed TSON"), which the conformance runner maps to `validation`; §8.1 files
  adjacency and separator errors under `parser`. The validate vectors that state `parser` (an argument list in data)
  are checked by the subject failing a Class 1 parse instead (`Class2ConformanceSuiteTest.checkValidateVector`). Give
  the read a code the runner maps to `parser`, and check those vectors by it. The token before the error is judged
  first, so the report leads with a diagnostic of another category: `t: box<text>` at a `meta.type_ref` field reports
  `ATOM_CONSTRAINT_VIOLATION` for `box`, and `v: kind!` at an `int32` reports `ATOM_FORM_INVALID` for `kind`, each
  ahead of the separation error. Report the syntax error as the document's verdict, in a collecting read too.
- [ ] **An application through an alias of a parametric record is pinned at a composition operand alone** ([TSON-SCHEMA]
  §8.3). It applies the parametric record (`ParametricMaterialiser.parametricHead`: `bx<text>` over `bx => box` closes to the
  record `box<text>` closes to), and the corpus holds that at an operand, `box_of<text> & { … }`. Add a
  `schema/valid` vector for one at a declaration and one at a field's type, with the expected resolved form.
- [ ] **No vector pins a parameter at a facet** ([TSON-SCHEMA] §5.10). §5.10 refuses a parameter at a facet as at a
  size bound, a default or a field's own slots, and the corpus has a `schema/invalid` vector for each of those
  (`a-parameter-at-a-size-bound` among them) but none for a facet. `t => <T> !meta.record { fields: { a => { type: {
  of: !meta.array { element_type: text  unique_items: T } } } } }` is refused ("parameter 'T' stands at
  meta.array.unique_items"); add it as a vector.
- [ ] **An aliased application across imports is untested at the IS-A edge fold.** An imported schema's
  `@meta.application` is the application as written (`{ head: box  arguments: [user_id] }`), and reaches the importer's
  edge fold in that form, where the fold compares canonical applications ([TSON-SCHEMA] §8.2), arguments past their
  aliases. Add a link vector with an aliased application declared in an import and composed as a parent in the
  importer, and reduce on ingest if it misses.
- [ ] **Automatic reference-closure resolution** ([TSON-SCHEMA] §2.2.3, §3.4.1) — no code
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
- [ ] **An instance of a constructor composing `record` is refused** ([TSON-SCHEMA] §4.1, §5.8). `c => record & {
  extra: text }` is a constructor whose instances are record types carrying one facet more, and `x => !c { fields: {
  id => { type: text } }  extra: "hello" }` should follow record composition: `x` is a record type read by the record
  reader (`!x { id: abc }` validates, in tree and bind mode) and its body holds `extra`. Today `x` is refused as
  `NOT_IMPLEMENTED` at `/x`, in either declaration order and with `extra?: text` left unwritten: no class binds `c`, so
  `vocabulary.instance` throws `MissingBindingException` and `SchemaResolver.constructed` sends it to `unmodelledType`.
  Decide where an added facet lives in the schema model and in the resolved form,
  then read the instance as its source's body with the facet held. A constructor adding a field to a collection
  constructor is the same gap: `my_map => meta.map & { label?: text }` and `my_ns => meta.namespace & { label?: text }`
  load, and `!my_ns { key_type: meta.identifier  value_type: op }` reports it as `!my_map { … }` does, the instance
  then refinable by `^` as its source constructor's are (§5.7). An instance of a constructor composing `atom`,
  `product` or `sum` directly stays refused: it is a type of a new form, which a processor backs with readers and
  writers of its own. A composition of `record` adding no field (`strict => meta.record & { extension?: = FINAL }`)
  is refused the same way: `TsonLinkedSchema.composedOver` leaves `meta.record` out, since `refuseWrittenParameters`
  and `parametricInstance` ask their questions of a `!meta.record` head by name, and `!strict { parameters: … }`
  would skip them.
- [ ] **A field of an incomplete entry typed by a type written in place is read only where the entry is completed**
  ([TSON-SCHEMA] §5.5). §5.5 reads each written field by its own type where it is written and judges the names it
  holds at link. A constructor's field whose type is written in place (`meta.choice.variants`, a `[meta.type_expr]`;
  `meta.tuple.elements`) has no name to find a compiled reader by: the resolver's reads look one up by a type's name
  at a checkpoint keyed by that name, and a reader for a type written in place exists only inside the reader of the
  record declaring it. So a wrong value there is reported at the completion, and a name it holds is not judged when
  an entry never completed links. It needs a compiled reader for a `TypeExpr` the resolver can ask for, with a
  checkpoint not keyed by a name. Add `schema/invalid` and `link/invalid` vectors for a field so typed.
- [ ] **Ingest of resolved output** ([TSON-SCHEMA] §8.1, §10.1) — a schema is always re-resolved from source,
  so no path reads a resolved `{type_name => top}` map and verifies it. §8.1's ingest re-runs the
  family checks (no record written in place a member of anything) over the ingested map and its closure, the §5.11 group
  declaration rules over every `meta.field_group`, §5.2's `discriminators` beside `extension: ABSTRACT` at a declared
  record (one in place is held to it as it is read, `TypeInline.refusal`), and every kind-typed slot's name against its
  kind, which a resolved form's read checks for grammar alone ([TSON-SCHEMA] §4.2, `TsonReadContext.readsSchemaNames`).
  A resolved form is read today only where a caller says it is one (`TsonDataParser.parseResolvedForm`, a reader's
  `readAs(text, "schema")`), with nothing verified.


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
- [ ] **A JSON `$type` naming an incomplete entry reports a gap, not the document's mistake** ([TSON-SCHEMA] §5.5).
  Under `nonempty => !meta.array ^ { min_items: 1 }` and `envelope => { local: meta.declared }`, TSON's `!envelope {
  local: !nonempty [a] }` is a `TYPE_MISMATCH` ("'nonempty' is an incomplete 'meta.array' with [element_type] absent,
  not a type", `IncompleteEntryReader`); `{"local": {"$type": "nonempty", "$value": ["a"]}}` reports `NOT_IMPLEMENTED`
  ("has no usable compiled reader"), and so does a JSON root read at `nonempty`: `JsonSchemaCompiler.constructorOf`
  throws `IllegalStateException` for an `IncompleteEntry`, which the compile loop turns into an `ErrorReader`. Give the
  JSON stack `IncompleteEntryReader`'s refusal and a `CrossEncodingParityTest` case.
- [ ] **JSON bind fails every read under the schema-model bind context.** With
  `SchemaModelNameBinder.defaultContext()` a JSON bind read of any type, `names => [text]` as a `List` included,
  throws at compile: "'net.iri_type' and … IriType do not agree: field 'allow_relative' states 'true', which boolean
  cannot hold". `tson-json`'s `BindRecordBuilder.statedValue` asks the atom for the component's class, and
  `BooleanParser.boundTo` answers for `Boolean` and not the primitive. The TSON stack reads the same configuration.
  Bind a stated value to a primitive component through its box, with a `CrossEncodingParityTest` case.
- [ ] **A host class of the wrong shape is a deferred gap in TSON and a bind mismatch in JSON.** A record type bound
  to a class that is not record-shaped throws `IllegalArgumentException` from `RecordBindReader`, which
  `TsonSchemaCompiler`'s compile loop turns into an `ErrorReader`, so the read reports `NOT_IMPLEMENTED`; JSON's
  `BindRecordBuilder` throws `BindMismatchException` at compile. `ArrayBindReader`, `MapBindReader` and
  `TupleBindReader` check their hosts the same way. The wiring is the deployment's: throw `BindMismatchException`
  in the TSON readers, with a parity case. `CheckpointCompileTest` binds a record to a sealed interface on a
  checkpoint path and needs the same verdict there.
- [ ] **The compile loops turn any failure to build a reader into a deferred gap.** `TsonSchemaCompiler` and
  `JsonSchemaCompiler` each catch `RuntimeException` around a factory and stand an `ErrorReader` in, which a read
  reports as `NOT_IMPLEMENTED`. A checkpoint compile relies on it (an application not yet closed, an entry not yet
  present), and `JsonSchemaCompiler` uses it for a value entry and an incomplete entry; in a finished compile a
  fault in a factory reads as a gap. Stand the `ErrorReader` in for the cases that are gaps and let the rest
  propagate.
- [ ] **A dependent field whose sibling names an unbound type fails each read, not the bind-mode compile.** With
  `payload: kind!` and `kind` admitting a type no class binds, a document naming that type throws
  `MissingBindingException` from `RecordBindReader.descriptorFor` during the read. A consumer turns that into a server
  error that any sender can trigger. The method's own contract is that a missing binding is a misconfiguration
  reported when the schema compiles in bind mode, but a dependent field's target is chosen per document, so it is
  reached lazily. When a record holding a dependent field compiles in bind mode, check every type the sibling's
  reference admits (narrowed by a pin or by a discriminator's members) and refuse the schema there, naming the field
  and the first unbound type. The schema narrows that set by refining the reference (`meta.ref<meta.record>` under
  `meta.ref<meta.type>`). A free `meta.type_ref` sibling admits the kernel's types and every declared type, so it
  compiles in bind mode only where each of them has a class or the reference is refined; a free `meta.type_expr`
  sibling also admits a type written in place, which no class binds, so it never does ([TSON-SCHEMA] §5.2). The same
  set, as an accessor on the compiled schema, is what a consumer checking it at startup assembles by hand today
  (`ltr8-io-tson-java-http`'s `ChosenTypes`). Do the same in `tson-json`'s bind path.

- [ ] **A reference inside an annotation's value is not judged** ([TSON-SCHEMA] §5.12, §6). With `binds =>
  @meta.annotation [meta.ref<protocol>]`, a schema writing `@binds:[nothing_declares_this]` loads: the annotation
  value's shape is checked, but no name in it, so the name arrives unresolved. An annotation's value is judged against
  its declared type at load, and a name in it is judged as one in a field value is. So run the link's field-value check
  (`TsonSchemaLinker.checkFieldValues`, with `ValuesByName.reach` for a reference over values) over every annotation
  value in a schema document, the name resolved in the annotating document's namespace (§3.3.3) and reported at the
  annotation. Add `link/invalid` vectors for a type reference and a value reference that name nothing, and a
  `link/valid` one. A consumer pins today's behaviour (`ltr8-io-tson-java-http`,
  `UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged`).

- [ ] **A record written as `!meta.record` is not judged against the `meta.record_field` invariants**
  ([TSON-SCHEMA] §8.1). The field syntax cannot write a field that breaks them; the construction can, and each of
  these loads in `r => !meta.record { fields: { a => { … } } }`: `{ type: text  optional: true  role: DEFAULT }` and
  `{ type: text  optional: true  value: "x" }` (`value` is present exactly when `role` is not `FREE`), `{ type: int32
  role: DEFAULT  value: 5 }` (`DEFAULT` requires `optional: true`; `!r {}` then fails as a missing required field),
  and `{ type: int32  optional: true  voidable: true  role: FIXED  value: 5 }` (`FIXED` requires `voidable: false`).
  Verify the three per field wherever a `meta.record` value is resolved, at the declaration, with a `schema/invalid`
  vector for each.
- [ ] **`extension: FINAL` on a parametric record written as `!meta.record` is accepted** ([TSON-SCHEMA] §5.2).
  `box => final <T> { x: T }` is refused, the mark forbidding the composition every application exists for;
  `box => <T> !meta.record { fields: { v => { type: T } }  extension: FINAL }` is the same record and loads. Refuse
  it on the lowered form, so both spellings meet one check, and add a `schema/invalid` vector.
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
- [ ] **A tag that names nothing is a mismatch below the root, not an unresolved name** ([TSON-DATA] §8.1;
  [TSON-SCHEMA] §3.3, §7.2). Under `outer => { inner: inner }` and `inner => { a: text }`, `!outer { inner: !nosuch {
  a: "x" } }` reports `TYPE_MISMATCH` ("'nosuch' is not admissible at a 'inner' position"), as does the tag at an atom
  position and as `"$type": "nosuch"` in JSON, where the same tag at the root is `UNKNOWN_TYPE` ("'nosuch' is not in
  this compiled schema"). So is a nested member's bare name: under `geo => !schema { address => { … } }` and `order =>
  { shipping: geo.address }`, `!order { shipping: !address { … } }` is a mismatch, though a bare name "names the
  root's `address` or fails to resolve" (§3.3). §8.1 makes an unresolved type name a resolver error: `UNKNOWN_TYPE_REF`,
  as the same tag already gets at a `meta.declared` position. Resolve the tag before judging admissibility at every
  typed position, in both encodings, and add `class2/validate` vectors for both names. A member-dispatched base
  judges the tag later still: under `pet => abstract { kind: text =?  name: text }`, `pet: !nosuch { kind: dog … }`
  reports "the discriminator of 'pet' selects 'dog', but this value is tagged 'nosuch'". The same step settles a
  tag naming a parametric record or an incomplete entry, which is a validation error in the right category today
  and is told what it names only at the root and at a `meta.declared` position (§5.5, §5.10).
- [ ] **`meta.declared` as a choice's variant loads, and no value reaches it** ([TSON-SCHEMA] §5.4, §7.8;
  [TSON-JSON] §3.3). Deferred past Revision 40 by the spec author. Under `c => ( int32 | meta.declared )` and `r =>
  { v: c }`, `!r { v: !text hello }` is refused ("'text' is not a declared variant of 'c'") and `!r { v: !c !int32
  5 }` is a parse error, a value carrying one tag. §7.8 says such a value "carries the choice's tag and its own",
  which no value can in either encoding. Refuse the choice at its declaration, as §5.4 refuses a nested choice that
  is not disjoint, the tag being spent naming the variant; or rule that a tag naming no variant selects the declared
  one. Then state the rule in §7.8 and add `schema/invalid` or `validate` vectors for it.
- [ ] **The order of a chain's `supertypes` and `subtypes` is stated three ways** ([TSON-SCHEMA] §4.1, §7.5, §8.1,
  §8.3). Deferred past Revision 40 by the spec author. Both are `meta.set_type<meta.type_ref>`. §4.1 and §8.1 say
  `supertypes` is written sorted and give `subtypes` no order; §7.5 says a set is written in source declaration
  order with no canonicalisation step, and a chain has no source; `meta-resolved.tn`'s note says both are sorted
  and that comparison tools canonicalise them. State one rule in §8.1 for both, sorted being what the fixtures
  write, except a derived set in §7.5, and bring the fixture notes into line.

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

## Externs

A value through an extern ([TSON-SCHEMA] §7.9) is read, bound and written in TSON text, and read and bound in JSON
([TSON-JSON] §8.6). No JSON writer writes a `$type` at all, a boundary's included: listed under "JSON encoding".

- [ ] **Write the boundary tag at every position an extern types, in TSON text.** `ExternTags` names a subtype or a
  variant at a boundary where the extern types a record field, directly or through an array or a map written in
  place. A root typed by an extern, and an extern reached through an alias of a container, write no boundary tag, so
  an instance of a subtype there writes text that does not read back as that subtype ([TSON-SCHEMA] §7.9,
  "Writing").
- [ ] **A tag naming an extern at a position no extern types.** Where a schema imports another and also names one
  of its entries by an extern, `!rush_x` at a position typed by the imported supertype `order` is refused in both
  encodings (`'rush_x' is not admissible at a 'order' position`), though the extern reaches a subtype of `order` and
  [TSON-SCHEMA] §8.3 follows an extern where it follows an alias, subsumption included. The readers that judge a tag
  at an ordinary position (`VariantSchemaReader`, JSON's `DispatchTagReader`) follow the alias chain and stop at the
  extern.
- [ ] **A dependent value in a value entry is not read across an extern into a third schema** ([TSON-SCHEMA] §5.2,
  §7.9). `f.tn` declares `order`; `g.tn` imports it and declares `rush => order & { fast: boolean }`; a schema
  importing `g.tn` with `o_ext => !meta.extern { schema: "…/f.tn"  type: order }`, `later => { o: o_ext }` and
  `setting => { kind: meta.type_ref  v?: kind! }` refuses `s1 => !setting { kind: later  v: { o: !rush { … } } }` as
  `NOT_IMPLEMENTED` ("a compile with no registry cannot read in its own schema's scope"): `HeldValues` compiles the
  sibling's type with no registry, so the foreign scope has no schemas to read in. The same value reads as a plain
  value entry, as data and as JSON. Give the held read the registry the link has.
- [ ] **A map key typed by an extern, in JSON, reports outside the foreign scope.** The key is parsed by the
  target's parser, and a diagnostic on it carries neither the foreign schema nor the scope suffix
  ([TSON-SCHEMA] §7.9, "Diagnostics").

## JSON encoding

[TSON-SCHEMA] §6 defines a representation directive — a mark whose force is confined to the encodings that claim
it — and no schema declares one, so nothing in this stack reads a schema-side annotation to decide how a value is
written. A record is closed under its type and open-ended data is a declared map-typed field, identically in both
encodings ([TSON-JSON] §6.1.1).

- [ ] **No JSON writer writes a `$type`, so a value only its tag places has no JSON encoder** ([TSON-JSON] §9.2).
  `JsonObjectWriter` is directed by the class, which states no tag, and refuses a union; a schema-directed tree read
  drops every tag, so `JsonTreeWriter` writes the value back untagged. A value at an abstract position, a
  non-disjoint choice, a declared position, or a boundary naming a subtype or a variant ([TSON-JSON] §8.6) therefore
  writes JSON that does not read back. The work is a schema-directed JSON object writer, the peer of
  `TsonObjectWriter.describing(linked, root)`, writing each `$type` in the scope it is read in, the boundary's by
  the enclosing scope's name (`tson-schema`'s `ExternAdmission` says which), and one per value, a subtype by its
  own name and never inside a wrapper's `$value` ([TSON-JSON] §3.3).
- [ ] **Relax [TSON-JSON] §3.3's leading-member rule, for Revision 41.** §3.3 requires `$type` (after any `$import`)
  to lead its object; with `meta.record.name_type` admitting `$ref`, `$id` and `$defs` as field names, those sort
  first, so RFC 8785 canonical output of a tagged value — `{"$ref": "#/a", "$type": "sub", …}` — is refused as a
  misplaced `$type`, which §3.3 states. The reader would admit the annotation members anywhere within the object's
  leading run of `$`-named members. Open, and why it waits for the sum types: at a choice or a declared position no
  record bounds the run, a subtype may declare `$` fields the position's type does not, and a map variant's
  `{"$ref": 1, "$type": 2}` is a map today (§8.3.1). The readers are `RecordPlan`, `DispatchChoiceReader` and
  `DispatchDeclaredReader`.

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
  sets, tuples and maps do. Each stack words them in its own dispatchers (`NamedDispatchReader` and
  `DeclaredReader`; `DispatchChoiceReader` and `DispatchDeclaredReader`), and `CrossEncodingParityTest` compares only
  their codes and pointers. A rule class for each, stated once, serves both encodings and lets the parity cases move
  to `sameRule`.

- [ ] **A pinned schema reference is not verified in the JSON stack.** `JsonCompiledSchemaRegistry.get`
  canonicalises before it asks its `TsonSchemaLoader`, and the loader takes a canonical identity and nothing
  else, so the `?sha256=` of an out-of-band `withSchema(uri)` is
  dropped unchecked -- where [TSON-SCHEMA] §10.2 makes verification a MUST and a mismatch a resolver error. The
  TSON registry verifies against the bytes it fetched; the JSON side holds only linked schemas, so what it
  needs is either the digest beside each linked schema or a loader that takes the reference as written.

- [ ] **The JSON reader factories share their per-entry work, and the container loops their remaining rules.**
  Every container reads through a plan, one mode-free loop and a builder per mode (`RecordReader`, `MapPlan`,
  `BindTargets`). What is left of that restructure:
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
  `RecordMemberDispatchReader`, `Subsumption.dispatching` and the choice's `NamedDispatchReader` still select by
  name at read time, and a concrete record reader still accepts a tag naming a subtype. The port: dispatchers
  wrapping object references resolved at compile, chains flattened through the family, concrete readers that only
  accept a tag restating themselves, and the JSON side's `Dispatch*Reader` names. Its diagnostics are the drift to
  close: every divergence the JSON restructure pins in `CrossEncodingParityTest` comes out of that test as the TSON
  side matches.

- [ ] **A JSON diagnostic names the type at the end of an alias chain, not the alias the author wrote.**
  `JsonSchemaCompiler` collapses an alias entry onto its target's reader ([TSON-SCHEMA] §8.3 permits
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

## Binding (`ltr8-bind`)

- [ ] **Which members an annotation is read from differs by class kind.** `ComponentAnnotations` reads `@Field`
  from a record's accessor on the canonical path and from its parameter on a `@Profile` path; `@Union` from a
  record's accessor and a constructor class's members, and from no member of a `@Profile` record, a public field or
  a getter/setter pair; and nothing but `@Unbound` from a member merged into a constructor component
  (`ComponentAnnotationPrecedenceTest`). So `@Field("label")` on the setter of a constructor component, a record
  header's `@Field` or `@Union` beside an explicit accessor, and `@Field(bridge = …)` on a record bound through
  `@Profile` are each dropped, and `GetSetFinder` reads a private field of the described class alone, never one a
  super class declares behind a pair the class inherits. Read each annotation from every member of a component,
  taking each `@Field` attribute from whichever member states it and refusing two members that state one
  differently, with a test of each annotation on each member of each class kind.
- [ ] **A collection or a map of generic arrays is refused.** `List<List<String>[]>` is refused with "Unrecognized
  parameterized type" and `Map<String, List<String>[]>` with "…for map key/value": `ArrayBinder`'s collection
  branch and `MapBinder.resolveTypeArgument` take a class or a parameterized type as an element, where a record
  component takes a generic array too (`GenericComponentTypeTest.anArrayOfAParameterizedType`). Take the same types
  as an element, a key and a value, with a round trip of each.
- [ ] **`Annotated` of anything but a class or a parameterized type throws `ClassCastException`.**
  `ClassBinder.annotatedValueDescriptor` casts the argument to `Class`, so `Annotated<List<String>[]>` and
  `Annotated<? extends X>` throw unchecked. Read, not run. Describe a generic array as a component is described and
  refuse the rest with a `DataBindException` naming the type, with a case of each.
- [ ] **A refusal reached by name still carries an identity hash.** `DataBindContext` lists each message on a
  refusal's cause chain, and an unreflected member's `IllegalAccessException` prints its type by identity: a private
  record bound by name is refused with "…Failed to access accessor for record component 'text' on '…': class is not
  public: …HiddenRecord.text[Ljava.lang.Object;@38f57b3d/…" from `RecordComponentFinder`, a different hash in each
  context. `AtomBinder` and `TransparentBinder` unreflect too (read, not run). Look each member up by name and type
  through `Members`, as `RecordBinder` does its constructor, and assert each whole message in `RecordRefusalTest`.
- [ ] **A sealed class that is not abstract is no member of its own union.** `UnionBinder.collectSealedMembers`
  lists the leaves of a sealed hierarchy, so a concrete sealed class at the root or between is no member and its
  own instances cannot be written as union values. An enum with a constant body is such a class: its union lists the
  anonymous body class alone, and its other constants are no member. Read, not run, beyond a probe of the JDK's
  answer for the enum. List a sealed class that can be instantiated beside the classes it permits, with a round trip
  of a concrete sealed root and of each constant of such an enum.
- [ ] **`TupleBinder` reaches a record through `ltr8-bind`'s own lookup.** It uses `MethodHandles.lookup()` where
  every other binder uses the public lookup, so what a `@Tuple` record may hide differs from what a record may.
  Read, not run. Reach its constructor and accessors through `Members`, with a case in `RecordRefusalTest` for a
  `@Tuple` record the public lookup cannot reach.
- [ ] **A `@Field` bridge inheriting a generic `toData` may be refused.** `RecordFields.fieldBridge` looks up
  `toData` and `toObject` on the bridge class at the resolved types, and a bridge that inherits both from a generic
  super class (`IntegerBridge extends AbstractBridge<Integer>`) declares only the erased signatures. Read, not run.
  Reproduce it, find the methods at their erased types and adapt the handles, with a round trip in
  `BridgedFieldTest`.
- [ ] **`ArrayMapper.toArray` casts its result to an array.** An atom at the root throws `ClassCastException`
  ("Cannot cast java.lang.String to [Ljava.lang.Object;") from both overloads. Refuse it naming the class, as
  `MapMapper.toMap(Object)` refuses a root that is no map (`MapMapperRootTest`).
- [ ] **The two mappers are examples shipped as API.** `MapMapper` and `ArrayMapper` show two ways to walk the
  descriptors, a plain descent and `MethodHandle`s built over them; nothing outside `ltr8-bind` uses them, and its
  own tests use them as the round-trip harness. They sit in `src/main` and the module exports `io.ltr8.bind.mapper`.
  Move them out of the main sources, to the test sources or an examples source set the tests depend on, drop the
  export, and correct the `tson-java` skill's `api.md`, which lists it.
- [ ] **The bind readers compute the `@Unbound` components again, from fewer members.**
  `RecordBindReader.unboundComponents` (`tson-compiler`) and `BindRecordBuilder.unboundComponents` (`tson-json`)
  reflect over a record component and its accessor, where `ltr8-bind` reads the marker from every member, and both
  loop over `descriptor.fields()`, which holds no unbound component. Read, not run. Take the answer from the
  descriptor, or delete the sets if nothing reaches them, with a bind read of an `@Unbound` constructor parameter
  in each stack.
- [ ] **Prose outside `ltr8-bind` states rules the binder has changed.** `FieldOrder`'s Javadoc (`ltr8-annotation`)
  says it has no effect on a `@Tuple`, a `@Transparent` class or one bridged through `ToData`, where it is refused.
  `tson-schema/CLAUDE.md`, `skills/tson-java/references/bindings.md` and `IntegerType`'s Javadoc (`tson-schema`) say a
  class with more than one public constructor needs `@Record` on the canonical one, which a Java record does not. A
  comment in `tson-compiler`'s `DataClassObjectReader` says a class joins its union as it is analysed, where it joins
  once its component is cached. Correct each.

## Documentation

- [ ] User-facing documentation on how to use the library, in `docs/` — `README.md` and the `tson-java` skill are the
  only consumer-facing prose; `design/` is internal.

## Miscellaneous

- [ ] **A bind read does not judge an annotation on an atom, a container or an element** ([TSON-SCHEMA] §6). With
  `feed => { url: text  n: int32  tags: [text] }` bound to a host record, `url: @nosuch:"x" "/"`, `n: @nosuch:"x" 1`,
  `tags: @nosuch:"x" [a]`, `tags: [@nosuch:"x" a]` and `url: @title:{ a: 1 } "/"` all bind, in a data document and in
  a value entry's payload alike; a tree read refuses the first four as `UNKNOWN_TYPE_REF` and the last as
  `TYPE_MISMATCH`. An annotation on a record value or on the root is judged in both modes. Only the record, boxing
  and type-reference bind readers resolve an annotation (`AnnotationCapture.bound`); every other bind reader skips
  it unread. Judge the name and the value at each of them as the tree readers do, discarding what no component
  receives, add a `CrossEncodingParityTest`-style case per position, and check `tson-json`'s bind path for the same.
- [ ] **A name carrying an annotation is admitted at a reference over values** ([TSON-SCHEMA] §5.12). §5.12 refuses a
  framed name, "a name's type is its referent's, and an annotation on it would have nowhere to go". Under `call =>
  { m: meta.ref<method> }`, `!call { m: @doc:"x" orders.place_order }` reads with no diagnostic, in a data document
  and in a value entry; an undeclared annotation there is refused, and a tag is refused as inadmissible at the
  position. Refuse the annotated name in both encodings, with `class2/validate` and `schema/invalid` vectors. An
  annotation on a type's name at a type slot is kept and written back (`TypeRefAnnotationTest`), and §8.1 preserves
  author-written annotations at value positions, so settle first whether §5.12's sentence reaches a name at a type
  slot; if the text does not say, that is an entry for `SPEC-FEEDBACK.md`.
- [ ] **A choice's variant bound through a bridge delivers its wire value** ([TSON-SCHEMA] §7.4). With `color =>
  !enum [RED GREEN]` bound to a Java enum `Color implements Shade`, `size => { n: int32 }` to a record `Size implements
  Shade`, `shade => ( color | size )` and `holder => { s: shade }` to `Holder(Shade s)`, `!holder { s: RED }`, tagged or
  not, fails each read as `BIND_MISMATCH` ("Holder rejected the value the schema admits: java.lang.ClassCastException:
  Cannot cast java.lang.String to …Shade"), and JSON's `{"s": "RED"}` the same; a field typed `color` binds
  `Color.RED`, and the `size` variant binds. Both stacks' `BindTargets` wrap a field's reader in the target's bridge
  and read a choice as it is, so the enum variant arrives as its label. Bind each variant to its class through its
  bridge when the reader is built, refusing at compile a variant whose class the component cannot hold, with a
  bind-mode test per encoding.
- [ ] **A map keyed by a record type admits a token key** ([TSON-SCHEMA] §4.2, §5.3; [TSON-DATA] §2.5). §4.2 says
  "a map's keys may be records, tuples or arrays, each declared and named", and a key is a data value read at the
  key type. Under `order => { id: text }` and `r => { keyed: {order => int32} }`, `!r { keyed: { o => 1 } }` reads
  with no diagnostic, though `o` is no value of `order`. Read each key against the key type as an entry's value is
  read against the value type, in both stacks, and add a `class2/validate` invalid vector beside a valid one keyed
  by `{ id: a }`.

- [ ] **Schemaless `!integer` and `!regex` are refused** ([TSON-DATA] §5.5, §5.6). `{ a: !integer 5 }` and
  `{ a: !regex "^a+$" }` with no `!!import` report `UNKNOWN_TYPE_REF` ("not a built-in type"); both are rows of the
  built-in vocabulary (the kernel's `integer` and `regex`), and `!int64`, the other kernel row, is accepted. Add the
  two rows to the schemaless vocabulary, check the rest of §5.3–§5.6's table against it, and add `class1` vectors for
  each.
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
  form=UNQUOTED, position=…]`: `TypeTagCheck.describe`
  names a record, map, array, `{}` and `_` and falls back to `String.valueOf` for anything else, so a scalar token
  arrives as its `toString`. It has 18 callers, all exposed the same way; a scalar
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
  **annotations** on one value, and **total values**. Schema side: **import closure** (every schema document reachable
  from one header by `!!import` or by an extern, [TSON-SCHEMA] §5.13), **entries** in one schema map,
  **alias chain** and **supertype chain**. (Wait depth is counted, by `WaitOrder`, with its `--max-wait-depth` flag.) What
  still needs deciding per limit is *where it is counted*: the ones that bound shape are per-container state the
  stream does not keep, where depth was a counter it already had, and the aggregate (total values) needs its own
  counter since §9.1 is explicit that the total is not bounded by the parts.

- [ ] **The schema parser reads at the default limits, and an argument list's depth is unbounded** ([TSON-DATA] §9.1;
  [TSON-SCHEMA] §11.5, §5.2). §9.1 wants a refusal naming the limit and its threshold "rather than … a stack
  overflow", and §11.5 applies its counters to a schema document as written. The schema parser reads at the default
  limits whatever is configured (`new TsonSchemaParser(schemaText)` in `Tson`), so `compile --max-depth 100000`
  refuses at 64. An argument list opens no container, so nothing counts it: `meta.array<…<text>…>` 1,000 deep
  compiles, `set<…>` 1,000 deep overflows the stack where the resolver reads the construction (`TypeInlineReader`
  through `RecordBindReader`), and either at 5,000 overflows in `TsonSchemaParser.parseTypeArgs`; the
  `StackOverflowError` escapes the CLI, which exits 1, the code for a rejected document. Hand the parser the
  configured `LimitsPolicy`, and bound an argument list's depth (§5.2's nesting limit on inline forms) so that no
  schema reaches a host fault.
