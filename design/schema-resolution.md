# Schema resolution

The resolution phase: turning grammar-layer declarations into resolved `schema.meta` values, an entry being its
value — the two resolvers, declaration annotations, definition marks, open records and the IS-A edges of their
applications, field facts and refinement, groups, subtraction, and the exception boundary.

**Invariants**

- Every name a declaration writes resolves in the schema's one namespace, its own entries and the closure of its
  `!!import`s (§3.3). An annotation on a declaration resolves there (§3.3.3); a
  name that misses is a resolver error, the valueless form included, and a bare mark is read as `_` against its
  type. Only the kernel's bootstrap, resolving meta.tn itself, skips the check.
- **Every name resolves from the root** (§3.3). A name written inside a nested `!schema` map resolves
  as one written at the root does: a sibling is `geo.address`, never a bare `address`. There is no lexical scope.
- **A nested map's members are entries under their paths; the nested map's value is derived, never maintained by
  hand.** `TsonSchema.entries` holds `geo.address` as it holds `order`, and `geo`'s `ValueEntry` of `meta.schema` is
  rebuilt from the path entries by `SchemaPaths.synced` in `TsonSchema`'s compact constructor. A phase that edits a
  member edits its path entry: where a path entry and the nested map's own copy disagree, the path entry is the one
  held, whichever comes first (the kernel's `meta` precedes its members, and a linked schema rebuilt from a partly
  ready namespace carries a stale copy).
- **A nested map is open** (§2.2.3). A document's own `!schema` map is resolved locally, its members
  under their paths; where an import declares a map of the same path, the two are one map, the union of their
  members, and only a member path declared twice collides (§2.2.3). `SchemaResolver.mergeImports` and the local
  collision loop pass over a nested map met twice; `design/linking-and-compilation.md` has the merge.
- Every declaration is made ready in wait order (`WaitOrder`, §3.4.1), never source order: a read against an entry
  of the same document — `!C { … }` with `C` local, a value of a local type, an annotation whose type is local —
  makes that entry ready first, in either order. Entries waiting on each other are a `SchemaValidationException`
  naming the cycle; a chain past §11.5's wait depth is a `SchemaRefusalException(LIMIT_EXCEEDED)`.
- A judgement that walks a name to what it names (§5.8's one name type, `requireOneNameType`) is deferred and run by
  `DefinitionResolver.judgeDeferred` once every entry is ready, so it never makes an entry wait.
- `abstract`/`final` are grammar, not annotations; `=?` is field syntax; and the definition mark is applied once, in
  `DefinitionResolver.resolve` (`withExtension`), never inside whichever `resolve*` built the body.
- A restated field's annotations concatenate over the inherited ones, restatement first — never replacement by name.
- A field's name `?`, type `?` and modifier each set one fact; the name's is never inherited, and a modifier-only
  tightening inherits the type's with the elided type. A restated group member stays a member: its name `?` may be
  dropped, never added (a `+` group's members are restated as written); it takes no default, and a pin on it is
  never supplied.
- Refinement of a field is three orders, never a state matrix (`DefinitionResolver.refines`); nothing stores what
  omission yields, so that order is asked of `RecordField.omitted`.
- An entry is its value (§8.1). Every type body, an open record's included, carries its own chain, `type`'s one
  field `chain`, a `TypeChain`: `parents` as written, `supertypes`, `subtypes`. The application an entry
  instantiates is the derived `@meta.application` on its key (`TsonSchema.applicationOf`). What no value holds — where
  the entry was declared, the annotations on its value, its constructor, the entry a `^` names — is
  `TsonSchema.EntryFacts`, never a field of any constructor.
- `supertypes` is names without arguments, each a path from the root, sorted, complete to a fixed point through applied
  parents (`SchemaResolver.appliedParentEdges`); `parents` is the one list holding applications, and a closed type keeps
  none (`closeChains`). A declaration never writes `chain`, and a constructor never declares a field of that
  name (`refuseWrittenChain`, `refuseDeclaredChain`).
- `subtypes` is linking's throughout: no resolved entry carries any.
- A kind and a constructor are judged by where `top` and the base kinds are defined, never by their spelling: the
  resolver's `kernel` predicates, `EntryKind.of(value, kernel)` and `TsonLinkedSchema.isKernel`.
- No entry is IS-A a template (§5.10): an application's edges are its template's written parents'. A use-site
  application whose record in place would be IS-A a declared type is refused (`TemplateMaterialiser.refuseMembership`,
  §5.2).
- Subtraction runs last and empties the chain (every supertype goes); nothing is kept in its place.
- **A dependent field's rules are the link's, over the flattened record** (`DependentFields`, [TSON-SCHEMA] §5.2): the
  sibling declared before it, typed by a reference to types, there to read whenever it is, its pin or default no template;
  the dependent field never voidable, carrying no value, restated only as itself. Each is a `SchemaValidationException`.
- A field's name is its key in `meta.record.fields` (`RecordBody.fields`, the kernel's `meta.field_map`) and never one of its
  facts; declaration order is part of the value, and `RecordBody.equals` compares it.
- The exception's class picks the diagnostic code (`SchemaResolver.Problems.report`): `SchemaValidationException` →
  `SCHEMA_ERROR` (a `SchemaRefusalException` keeps its rule's code, §8.2's or `LIMIT_EXCEEDED`),
  `UnsupportedOperationException` → `NOT_IMPLEMENTED`, `BindMismatchException` → `BIND_MISMATCH`.

Related: `design/constructor-application.md` (what `!C { … }` may apply and how its head resolves),
`design/atom-refinement-and-coherence.md` (atom refinement's merge, narrowing and coherence),
`design/template-materialisation.md` and `design/open-records.md` (§5.10 closing, applied-parent edges),
`design/resolver-vocabulary-and-bootstrap.md` (`WireForm`/`TypeSlots`/`CanonicalApplication`, alias hops,
`@meta.application`, the kernel's bootstrap), `design/namespace-types-and-value-entries.md` (value entries),
`design/schema-grammar-and-desugaring.md`, `design/linking-and-compilation.md`.

## The two resolvers

`SchemaResolver` resolves a whole `SchemaDocument` (`TsonSchemaResolver` is its public wrapper): header directives,
and the merge of every `!!import`'s namespace (`TsonSchema.namespaceSources`) *before* any
local declaration resolves, recording each imported entry's origin. That merge mirrors `TsonSchemaLinker.mergeImports`
— transitive, collisions decided by entry identity (§2.2.3) — because it is the same concept met one phase earlier,
and the two are kept in step deliberately. Every value the document reads is read at a checkpoint of it, its imports
and its entries ready so far (`design/resolver-vocabulary-and-bootstrap.md`). Local declarations are made ready in wait
order (below), `OnDemand` being the `Readiness` that resolves one declaration and reports its failure.

`DefinitionResolver` (package-private) resolves one `SchemaMap.Declaration` and never sees a document. Everything it
consults is fixed at construction: two `DefinitionGetter`s, `namespaceDefinitions` the whole namespace and
`vocabularyDefinitions` where a construction head and an annotation name resolve — the same wait order, except in the
kernel's ordinary resolution, which resolves heads through its pre-loaded stand-in (§3.4) — the vocabulary's
compiled reader (`ConstructionReader`) and annotation reader (`AnnotationValueReader`), the `ApplicationCloser` onto the
schema's one `TemplateMaterialiser`, and the `EntryFacts` map it shares with `SchemaResolver` and the materialiser,
so an atom refinement finds the constructor of a source another schema declared. Its class Javadoc lists the
shapes it dispatches and the exact exception boundary.

## The wait order (`WaitOrder`, §3.4.1)

One algorithm for every schema; the kernel's bootstrap and `SchemaResolver` both drive it, and what differs is only
what is ready before it starts — the kernel's hand-built pre-loaded set, or the import closure
(`design/resolver-vocabulary-and-bootstrap.md`). `WaitOrder` is the `DefinitionGetter` every lookup of a resolution
goes through, so an entry is made ready the first time anything asks for it, and `readyAll` drives the rest.

- **What waits.** A declaration waits on its head (`C` in `!C { … }`), its refinement source and `&` operands, the
  types of its annotations, and every type a read of its value reaches. A name at a type position (a field's, a
  variant's, an element's) is carried as a name for the linker and orders nothing, as a held value (a default or pin)
  does, which a read reaching a pending declaration binds as written.
- **Reads restart.** Every value goes through `WaitOrder.read`, at a checkpoint; a read reaching what its readers lack
  throws `NotReady`, that entry is made ready as a wait of the one being made ready, and the read starts over
  (`design/resolver-vocabulary-and-bootstrap.md`).
- **An application written inline at a field type is not yet closed there.** A use site names the entry an
  application closes to once materialisation runs, after every declaration is ready, so a record ready so far may still
  have a field typed `meta.ref<order>` or `box<text>`. Its compiled reader is an `UnclosedApplicationReader`, never
  the head's (a template, which refuses every value): a held value binds as written, as at a pending declaration, and
  is judged against the closed entry at link; a value entry's read reaching the field is `NOT_IMPLEMENTED`, since the
  application does not yet wait in the order as a named one does (`BACKLOG.md`). A tree-mode record wraps either
  held-value reader in an atom node (`RecordTreeReader`). A gap the payload read reports stays a gap: the value entry
  is refused as `NOT_IMPLEMENTED`, never as a payload that is no value of its type (`resolveValueEntry`).
- **Cycles are the author's error.** An entry reached again while it is being made ready is a
  `SchemaValidationException` naming the cycle in wait order: `'a' waits on 'b' waits on 'a': entries waiting on each
  other are never ready -- …`, or `'step' waits on 'step'`. A composition or refinement cycle is this same rule,
  reached through an application too (`design/template-materialisation.md`). An alias loop is §8.3's reference-chain
  refusal, not a wait (`WaitOrderTest.anAliasLoopIsRefusedAsAnAliasChainNotAWaitCycle`).
- **Wait depth is counted here**, order-independently, and a chain past `LimitsPolicy.maxWaitDepth` refuses its head
  with `LIMIT_EXCEEDED` (`design/processor-policy.md`).
- **Rule 6: a judgement that walks a name waits for everything.** §5.8's one name type over a composition's
  supertypes (`requireOneNameType`) walks a name type to its terminal (`meta.field_name` → `meta.identifier`); run during
  resolution it would make the composition wait on what it only names. `DefinitionResolver` defers it, and
  `judgeDeferred` runs it once every entry is ready, a failure reported against its declaration
  (`DefinitionResolverTest.aCompositionsNameTypesAreJudgedOnceEveryEntryIsReady`).

`ResolutionOrderTest` resolves each bundled schema, and a schema whose entries read each other, with its declarations
reversed and shuffled, and gets identical output; `KernelBootstrapResolverTest.theBootstrapIsTheSameInAnyOrder`
does the same for the bootstrap.

## Annotations on a declaration

Both annotation sets go through `annotationsFor`: those after `=>` land on the entry's facts (on its value), those
before the name on the entry's key (§6 — a resolver does not hoist key to value). `SchemaResolver` catches the
key set's failures itself, since that loop runs outside the wait order that catches the value set's.

The name is checked whether or not a value was written — §6 makes `@T` shorthand for `@T:_` — so silence is never
the outcome: an annotation keeping its name and losing its value would let the schema load clean with the
metadata gone (`SchemaAnnotationScopeTest`). A type the schema itself declares is made ready before the value is
read against it, and a type annotated with itself (`note => @note:"x" text`) is a wait cycle of one. A resolved
value is read by that type's own compiled reader, a bare mark as a
synthetic `_` (`BareAnnotationTest`). The bootstrap passes no `AnnotationValueReader`, because it is producing the
entries such a reader would read through; there the name is kept and the value dropped.

## Definition marks and selectors

`abstract` and `final` are words the parser reads at the type-def head (`design/schema-grammar.md`);
`DefinitionMarks` lowers them onto `record.extension`. They are grammar because both change which values a
position admits, which §6 makes the line between a kernel fact and an annotation. No resolver code knows
`abstract`, `final`, `sealed` or `discriminator` as annotation names: the meta declares none, so each written as
one is the ordinary unknown-annotation error.

**The mark is applied once, in `withExtension`.** A fresh record, a composition and a refinement each build their
own `RecordBody`, and a mark read three times is a mark two of them can disagree about. Applying it there is also
why extensibility is never inherited: a composition's body arrives OPEN from its operands. A mark on a non-record
is the author's error.

**A selector is `=?`** (`FieldModifiers.discriminates`), and the record it is written in is the family base: the
field stays required and unpinned (§5.7's identity diagonal forbids a base pinning what each member pins
differently) and its name goes into `record.discriminators`. ABSTRACT is derived from it — the members pin the
selector, so the base has no values of its own; `abstract` beside it is admitted, `final` refused. Member
dispatch is ABSTRACT plus non-empty `discriminators`, read where needed (`FamilySelectors.dispatchesOnMembers`)
and never stored as a second fact. A group member cannot be a selector: §5.11 makes a value modifier a parse
error there.

**An open record states its selectors as a record does**, with `=?`, and they are each application's: with
`tpet => abstract <B> { kind: text =?  extra?: B }`, `tpet<text>` is an abstract record dispatching on `kind`, and its
members are the declarations composing it and pinning `kind` (`tdog => tpet<text> & { kind?: = "dog" }`). A field's
value is never a parameter (§5.10), so no pin is derived from one. A selector whose type names a parameter is refused
(`refuseParameterTypedSelector`): a selector means the same in every application.

**On a template**, `abstract` and a selector are each application's and `final` is refused (§5.2, §5.10). The mark,
or ABSTRACT derived from a selector as on a record, sets the template's own `extension`, and closing carries it and
`discriminators` to the record each application denotes (`TemplateMaterialiser.closeOpenRecord`). `final` is
refused because a template is applied to be composed, each member of a family composing an application, and the mark
would forbid that of every application; a final type is a declaration of the application, marked as itself.

## Open records: no type

A template is an open record, a `RecordBody` carrying `parameters` (`design/open-records.md`). It is not a type
(§5.10): no position names it bare (`TsonSchemaLinker.checkArity` at a position,
`DefinitionResolver.refuseUnappliedTemplate` at an operand), it is in no `subtypes` index, no reference holds it, no
`FamilySelectors` question treats it as a base, and it compiles to `OpenTemplateReader`, the refusal a data tag naming
it gets. It is built as a closed record is (`resolveRecordBody`, `resolveComposition`, `resolveRefinement`, then
`Built.finish` with the declaration's parameters), its explicit spelling `<T> !meta.record { … }` through `openRecord`;
a parameter in it is the `ParamRef` the record's constructor makes of a name its parameters declare. `checked` refuses
a field or `name_type` named like a parameter, a selector typed by one and a literal value on a parameter-typed field;
a parameter spelled like a schema entry is refused by the shadowing rule. A template over any other body is refused
at its declaration (`SchemaDesugarer.refuseTemplateBody`, `DefinitionResolver.openInstance`).

**A family is declared with records.** An abstract record states the selectors and the fields every member shares,
a template composes it, and each member composes an application and pins the selectors:

```
pet    => abstract { kind: text =?  name: text }
pet_of => <T> pet & { pet: T }
dog    => pet_of<dog_type> & { kind?: = "dog" }
holder => { p: pet }
```

**An application's IS-A edges are its template's written parents', and never the template.** Two mechanisms give
them:

- **A closed parent** reaches every application through the template's own chain (`Ancestry.ofTemplate`): `dog`
  IS-A `pet` because `pet_of` writes `pet`, at a closed operand and at a declared application alike.
- **An applied parent** (§5.8's reference-valued parents). With `result => abstract <T> { payload: T }` and
  `ok => <T> result<T> & { note: text }`, `ok<text>`'s chain keeps `result<text>` in `parents` until
  `appliedParentEdges` folds it into the edge to the declaration naming that application, where one exists
  (`design/template-materialisation.md`); the linker inverts it, so `result<text>`'s `subtypes` holds `ok<text>` and
  not `ok<int32>` (`SubtypeTemplateFamilyTest`, `OpenOperandCompositionTest`).

A use-site application whose record in place would be IS-A a declared type is refused (`refuseMembership`, §5.2),
so every family member is declared.

A resolved entry's `subtypes` is empty: linking computes it one phase later, over the closure that reads it.

## Value entries

`name => !T { … }` with `T` no constructor (not IS-A `top`, `EntryKind.isConstructor`) is a value entry: `resolveInstance`
hands it to `resolveValueEntry`, which reads the payload against `T` as a data value is read and returns a `ValueEntry(T,
value)` of kind `VALUE`, `T` recorded as the entry's constructor fact for the tag
(`design/namespace-types-and-value-entries.md` has the read, the binding and the tree fallback). The resolver's part: a head
that is itself a value is refused (a value has no instances), a braced payload at an atom type is refused with the `^` it is
missing, and a payload that is no value of `T` is refused against the entry with the read's path. A namespace type is the
other side of the line: `api => !meta.namespace { … }` applies the constructor `meta.namespace` and builds a type, a
`NamespaceBody`.

## Nested schema maps

`geo => !schema { address => { … } }` declares: every value of `meta.schema` does, as the root does. The tag is
`!schema`, the kernel root's alias the root's tag names, or `!meta.schema`, its target, as resolved output writes a
nested map (`TsonSchemaParser` takes either, matched as a spelling, so no declaration captures it). The kernel's
machinery is one such map, `meta`; the data-format libraries' constructors are three more, numeric.tn's `numeric`,
temporal.tn's `temporal` and net.tn's `net`. `TsonSchemaParser` puts each member into the document's one declaration map
under its path (`geo.address`, `geo.inner.deep`), and the entry `geo` itself is a `SchemaMapDef` holding its members'
own names. So every phase reads a member as a root entry: the desugar phase rewrites its sugar in place, the wait order
readies it, and a reference to `geo.address` anywhere is a lookup of that key. `DefinitionResolver` resolves the
`SchemaMapDef` to `ValueEntry("meta.schema", Namespace("meta.schema", {}))`, waiting on nothing, and
`SchemaPaths.synced` fills it from the path entries wherever a `TsonSchema` is built, innermost map first, the members'
key annotations kept. A schema built from a resolved form read back holds the nested map alone, and `synced` keys its
members under their paths (`SchemaPaths.withPaths` does the same for a resolved form read as a plain map).

A member's identity is its declaring document plus its path: two maps may each declare `address`, and a diagnostic, a
kind's member list, a chain and an `@meta.application` name it `geo.address`. A map of one path that several documents of a
closure declare is one map whose members are the union of theirs; the annotations on each `geo => !schema { … }`
entry are its own document's, and the merged map carries none of an import's. A value of any other namespace type,
`!api { … }`, stays a value entry (above).

## Fields

**A resolved field is four facts, not a state** (`RecordField`, the kernel's `meta.record_field`), held under its name in
`record.fields`. §5.2's `name?: type? ~ value` has one mark per fact: the name's `?` is `optional`, the type's `?` is
`voidable`, and `~`/`=` give the `role` (DEFAULT or FIXED) and its `value`. `FieldModifiers.of` maps the marks one to
one and adds only refusals — a default on an unmarked name, a pin on a voidable type, `= _` and `~ _` (spell
"omitted or `_`" as `a?: void?`), and `=?` on anything but an unmarked name over a non-voidable type.
An open record's fields are built by the same path, so the two cannot drift. A field's value is never a parameter
(§5.10): an unquoted token naming one of the declaration's parameters in `value` is refused where the field resolves
(`ParameterTypes.atValue`). A value on a field typed by a type written in place that is no reference — a container, a
tuple or a choice — is refused where the field resolves too (`resolveFieldEntry`, §5.2), as a declared container's is
at link (`TsonSchemaLinker.notAScalarType`); one on an inline reference (`handler?: meta.ref<method> ~
orders.place_order`) is admitted and judged at link as a declared reference's is (`checkFieldValue`).

A tightening replaces the inherited field in place, keeping its position — supertypes' fields first, in their
order, then the body's. A modifier-only entry has no type slot, so it inherits the tightened field's type and with
it its voidability; its name's `?` is always its own. That is why `resolveField` takes the whole inherited
`RecordField`.

**Refinement is three orders** (`refines`), each least to most determined: what omission yields, absent →
missing-field error → injected (`RecordField.omitted`, asked of a plain field); voidable → not; role FREE → DEFAULT
→ FIXED. A restatement refines its source when no order moves backwards, which reproduces §5.7's transition matrix
cell for cell without the matrix existing in code.

**A restated type narrows, and the link judges it** (`TsonSchemaLinker.checkRestatedFieldTypes`, §5.7, §5.8). The
restated type is the inherited one or IS-A it by §7.2's chain, alias hops followed on both sides, or `void` at a
voidable position: `nickname?: void?` restating `nickname?: text?` narrows the field to `_`. The resolver binds a
restatement before the names it holds need be ready, so the IS-A is asked at link, as a reference's `of` is; the
kernel's `meta.namespace` narrowing `meta.map`'s `key_type: meta.type_ref` to `meta.identifier_ref` passes by
`meta.identifier_ref`'s refinement of `meta.type_ref`. The relation is nominal and nothing more (§5.7): `[int8]`
restating `[int32]` and a variant restating its choice are refused, no IS-A edge joining either pair.
Each record is judged against the nearest closed records on its chain, so an applied operand is judged through the
entry its application denotes. One denoting none is judged against the closed parents its template writes, and a
subtraction's tightening entry is not judged, its chain being empty (`BACKLOG.md`).

**A restated field's annotations are the restatement's, in source order, then the inherited field's** (`merged`,
§5.8), one path for refinement and composition. Concatenation, because [TSON-DATA] §3.1 makes an annotation name
repeatable — "the inherited `@doc`" names nothing when two were written. Restatement first, because
`Annotations.get`/`value` take the first occurrence, so the nearer declaration wins at every lookup. No annotation
the kernel declares changes how a value reads, so the ordering is pinned over resolved output
(`RestatedFieldAnnotationsTest`).

## Dependent fields (§5.2)

A field whose type is `{ of: !meta.dependent { on: kind } }`, `v?: kind!` in sugar, is a **dependent field**: each
instance's value is read against the type that instance's `kind` names (`RecordField.sibling()`). It is a type written
in place like any other, so it flows through `BuiltField`, flattening, substitution and closing unchanged, and a pinned
sibling's refinement still holds it. A `meta.dependent` anywhere but directly at a field's type — declared, or in place
at any other slot — is refused at link (`TsonSchemaLinker.validateBody`): nothing else binds its `on`.

The rules are checked when the record links, against its flattened fields (`DependentFields.check`, called from
`TsonSchemaLinker.validateBody` before any field's type reference is validated), so an open record's member is
judged once its application closes it:

- **Declared before it.** The fields are a telescope, each depending only on those before it in `fields`' order, so
  no cycle can form and none is looked for; flattening puts inherited fields first. An `on` naming no field of the
  record is refused; it never names an entry.
- **Typed by a reference to types**: past its alias hops a `RefBody` whose `of` is a constructor or `top`
  (`KindMembers.namesTypes`), or `meta.type_expr`.
- **There to read whenever the dependent field is**: required, defaulted, pinned, or a field group's member with the
  dependent field optional; never voidable, and never optional with no default. A group's members are declared
  `optional`, so a dependent field that is a member counts as optional, and a refinement making one required is judged
  on the flattened record. A member is absent when another option is chosen, so a dependent field written without it is
  refused where it is read (`design/readers-and-diagnostics.md`); nothing refuses a sibling and its dependent field
  in two options of one group, where the field can be written in no document.
- **Its pin or default names a type a value can be read against**: a template is refused. Membership
  of the sibling's reference is `checkKindValue`'s, as for any field value.
- **The dependent field** is never voidable and carries no default or pin: its type is not fixed at its declaration.
  It may be restated only as itself, its marks moving along §5.7's orders as any field's do; restating it with a named
  type, or a typed field as a dependent one, is refused (`DependentFields.restates`, asked by
  `checkRestatedFieldTypes`). A sibling may be a discriminator: the family rules ask of it what they ask of any
  required atom-typed field, and a dependent field named as a discriminator is no atom-typed field and is refused.

Everything else that walks field types skips a dependent field: it is satisfiable for inhabitance whatever its sibling names
(`TypeInhabitance`), carries no value to check (`checkFieldValue`), contributes no name to a name type or a parameter's type
(`NameType`, `ParameterTypes`). A value's qualified name going on through one takes the type its sibling names in the value
walked (`ValuesByName.dependentType`, `design/namespace-types-and-value-entries.md`). A value entry's dependent value is held
as written at the read and judged against that type when the schema links (`HeldValues`,
`design/linking-and-compilation.md`).

**A literal in an open record is judged on the closed type it needs.** A field typed by a parameter, whole or as an
argument, takes no literal (`refuseLiteralOnOpenType`; in the explicit spelling `refuseLiteralOnParameter` refuses one
beside `type: T` before the payload is bound, so the author reads §5.10's reason and not the reader's). A literal on a
closed type is judged when the template closes, against the closed field's type (`checkFieldValue`), its §8.2 hygiene
on the template (`TsonSchemaLinker.checkFieldValues`).

## Field groups

A group is options of members (`GroupDef`, the kernel's `meta.field_group`; §5.11): an option is chosen when any member is
present, a chosen option holds every member `optional_members` does not name, and `meta.field_group.optional` decides
whether no option may be chosen. `GroupDef.fieldGroup` is the one lowering, for a fresh body and a held one: `+`
becomes the non-optional group of one option whose members are all optional within it, so a one-option non-optional group is
always a `+` group. Members flatten as optional fields whatever the group says. The parser refuses the shapes that
restate plain fields or another group (`TsonSchemaParser.checkGroupShape`); a group restatement in a refinement or
composition body is checked by `restatesInheritedGroup`.

**A restated member stays a member** (`resolveTighteningField`, `restateMemberMark`). Its presence is the group's, so
the field stays optional whatever it writes. Its name `?` speaks for its option and is never inherited, so leaving it
off removes the member from `optional_members`, and adding one where the group has none is refused as a loosening.
A default is refused; a pin is admitted and **never supplied** (`RecordField.omitted` answers NOTHING for a member),
since an injected member would be present and presence is what selects the option. So no member is ever always
present, and §5.11's rule against two always-present members has nothing to refuse: there is no check for it.

## Subtraction (§5.9)

Supertypes merge, the body adds and tightens, *then* removals apply to the merged field set regardless of which
supertype contributed a field (`applyRemovals`). Rule 4 (removing a name the body also states) is checked before
rule 2 (removing a name that is not there), because a body-introduced field *is* in the merged set and "no such
field" would be the wrong diagnosis.

The chain is **emptied** — every supertype, including one that contributed nothing to the removal (`A & B - { f }`
with `f` from `A` drops IS-A with `B` too) — so §7.2's subsumption cannot place a subtracted type where its source is
expected. §5.9 makes the clause head-level so its effect is readable without scanning the parents; an author wanting
partial retention subtracts first and composes second.

Groups: a removed member leaves its option, an emptied option leaves the group, and a member left alone in its
option loses its `?`. A group down to one option no schema could write (`keepsOneOption`) dissolves into the plain
fields it equals (`dissolveInto`); a group with no members left is dropped.

## The exception boundary

The classification is the repo-wide policy (`design/process.md`): *a schema error's verdict doesn't change when this
library improves; a gap's does.* Misfiling an author error as `NOT_IMPLEMENTED` sends the author after the wrong
fix and lifts the CLI to exit 70. All three kinds are collected per declaration, each leaving a placeholder
(`SchemaResolver.unresolved`) that keeps the failed declaration's type parameters.

- **One schema construct reaches `NOT_IMPLEMENTED`: an instance of a user constructor of types**
  (`SchemaResolver.unmodelledType`). A constructor composing the kernel's `meta.type` builds types, and a type's body is
  one of the schema model's, built only by the bundled constructors (meta.tn's, numeric.tn's, temporal.tn's and net.tn's).
  Composing `meta.atom`, `meta.product` or `meta.sum` directly (`c => meta.product & { … }`) makes a new form, which a
  processor backs with readers and writers of its own; this library backs none beyond the bundled ones, so the refusal is a
  capability verdict, and stays one. Composing an existing constructor (`c => meta.record & { extra: text }`) makes no new
  form, and is refused the same way today only because no body in the schema model holds the added facet (`BACKLOG.md`). An
  instance of a constructor composing `top` alone is no type, and is held as a `ValueEntry` — the bound object, or a tree
  where no class binds the head — as a value entry's payload is (`design/namespace-types-and-value-entries.md`). The other
  `UnsupportedOperationException` sites are the catch-alls around the compiled reader (an exception none of the classified
  ones), a failed re-serialisation of an atom refinement's source, a closed application with no `ApplicationCloser` (a
  resolver built standalone), and the bootstrap's closed switch over the kernel's own declarations.
- **A body the constructor's vocabulary rejects** arrives as a `ReadException` and is restated as a schema error
  (`bodyIsNotValidData`, `payloadFailure`). The read `Diagnostic` is dropped: it was produced against a
  `DataValueEvents` replay whose positions are placeholders and whose path points into a synthetic body. The real
  position comes from `SchemaResolver`'s catch.
- **`IllegalStateException`** is for an invariant only a malformed resolved value or a skipped phase could break —
  an applicable head with a non-record body, a constructor not record-shaped (§7.2), and a container sugar form
  reaching resolution unlifted (`DefinitionResolver.unlifted`). `TypeDef`, `StructuralDef` and the AST `TypeExpr`
  are sealed and `resolveTypeDef`/`resolveTypeRef` switch over them exhaustively, so the sugar case is the only
  arm left, and only a caller resolving raw AST without `SchemaDesugarer` reaches it
  (`DefinitionResolverTest.anUnliftedSugarFormIsABrokenInvariantNotAGap`).
- **The header is the author's too.** A document with no `!!id` (optional in the grammar, [TSON-DATA] §2.2; required
  of a schema registered under its own name, §2.2.1) and a malformed `!!id` are refused up front as
  `SchemaValidationException`, before any declaration resolves, and `Tson.validateSchema` reports either as a schema
  error against the document as a whole (`SchemaWithoutIdTest`). The refusal is this library's, not the corpus's:
  §10.1 lets an id-less schema be registered under an application-supplied identity, a route no front door here
  offers, so a conforming processor may load the same document cleanly and no vector states the refusal.

**No gap reaches a read either:** every constructor meta.tn, numeric.tn, temporal.tn and net.tn declare builds a real reader,
and `CoreSchemaImportTest` asserts no `core.tn` entry compiles to an `ErrorReader`. The machinery stays because the extension
point can still reach it — a schema's own constructor this library has no factory for — and it rides in the report, located
at the value it could not read (`TsonCli.exitCodeFor` lifts the run to 70).

## The schema value model

One Java type per kernel record or enum, in the package of the namespace that declares its entry (`schema.meta`,
`schema.unicode`, `schema.numeric`, `schema.net`, `schema.binary`, `schema.temporal`), so the binder reaches it by
path (`design/front-door-and-config.md`); a class no entry declares stays in `schema.meta`.
`Top`/`Type`/`Atom`/`Product`/`Sum` replicate §4.1's composition chain as Java subtyping, and an entry's kind is
`EntryKind.of(value, kernel)`, derived and never stored. Every `Type` record carries `TypeChain chain` as its first
component, mirroring the constructor's flattened shape (`type`'s field first);
`Alias` and `ValueEntry` carry none. `TypeRef` is the parsed form of a reference value, a name: sealed over `NameRef`
and `ParamRef`, which only an open record makes of a name its parameters declare. An application is no reference but
the construction `meta.apply` (`ApplyBody`), written in place, whose arguments are names (§5.10). Every slot holding a
reference is typed `TypeRef`, and every type slot `TypeExpr`, a `TypeRef` or a `TypeInline`. A dependent field's type
is a `TypeInline` over a `DependentBody`: a consumer that needs a field's own type asks `RecordField.ownType()` (empty
for a dependent field), and `RecordField.typeExpr()` on one is a broken invariant (`IllegalStateException`). `TypeRef`
and each variant are
registered as atom host types, since a value written by its own class is described by the variant
(`TypeRefNameBridge` and its nested bridges, in `ResolverBindContext`). A context without those registrations
describes `TypeRef` as a union of two records,
so the schema model's classes bind only through a context that carries them (`ResolverBindContext.registerAbsent`, which
`SchemaModelNameBinder.over` applies).
Resolved values serialise through `TsonObjectWriter.toTson` with no hand-written writer, which is the proof the
model is idiomatic Java; the textual differences from the `*-resolved.tn` fixtures are listed in
`DefinitionResolverTest`'s Javadoc.

## Deferred

The identity-diagonal FIXED-value invariant — a restated FIXED field MUST NOT change its pinned value (§5.7) — is
unchecked: `resolveTighteningField` does not compare the two values.
