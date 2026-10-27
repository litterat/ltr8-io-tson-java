# Schema resolution

The resolution phase: turning grammar-layer declarations into resolved `schema.meta` values, an entry being its
value — the two resolvers, declaration annotations, definition marks, record templates as family bases, field
facts and refinement, groups, subtraction, and the exception boundary.

**Invariants**

- An annotation on a declaration resolves one hop against the governing meta and nowhere else (§3.3.3); a name that
  misses is a resolver error, the valueless form included, and a bare mark is read as `_` against its type. Only
  the meta-kernel bootstrap, resolving `meta-kernel.tn` itself, skips the check.
- `abstract`/`final` are grammar, not annotations; `=?` is field syntax; and the definition mark is applied once, in
  `DefinitionResolver.resolve` (`withExtension`), never inside whichever `resolve*` built the body.
- A restated field's annotations concatenate over the inherited ones, restatement first — never replacement by name.
- A field's name `?`, type `?` and modifier each set one fact; the name's is never inherited, and a modifier-only
  tightening inherits the type's with the elided type. A restated group member stays a member: its name `?` may be
  dropped, never added (a `+` group's members are restated as written); it takes no default, and a pin on it is
  never supplied.
- Refinement of a field is three orders, never a state matrix (`DefinitionResolver.refines`); nothing stores what
  omission yields, so that order is asked of `RecordField.omitted`.
- An entry is its value (§8.1). Every type body, a record template's included, carries its own chain, `type`'s one
  field `chain`, a `TypeChain`: `parents` as written, `supertypes`, `subtypes`. The application an entry
  instantiates is the derived `@application` on its key (`TsonSchema.applicationOf`). What no value holds — where
  the entry was declared, the annotations on its value, its constructor, the entry a `^` names — is
  `TsonSchema.EntryFacts`, never a field of any constructor.
- `supertypes` is bare names, sorted, complete to a fixed point through applied parents
  (`SchemaResolver.appliedParentEdges`); `parents` is the one list holding applications, and a closed type that is no
  template keeps none (`closeChains`). A declaration never writes `chain`, and a constructor never declares a field
  of that name (`refuseWrittenChain`, `refuseDeclaredChain`).
- `subtypes` is linking's throughout: no resolved entry, minted or declared, carries any.
- A kind and a constructor are judged by where `top` and the base kinds are defined, never by their spelling: the
  resolver's `kernel` predicates, `EntryKind.of(value, kernel)` and `TsonLinkedSchema.isKernel`.
- An application of a family base minted at a use site is a member of nothing; only a declared one gains the edge to
  its base (`SchemaResolver.familyBaseEdges`).
- Subtraction runs last and empties the chain (every supertype goes); nothing is kept in its place.
- A field's name is its key in `record.fields` (`RecordBody.fields`, the kernel's `field_map`) and never one of its
  facts; declaration order is part of the value, and `RecordBody.equals` compares it.
- The exception's class picks the diagnostic code (`SchemaResolver.Problems.report`): `SchemaValidationException` →
  `SCHEMA_ERROR` (a `SchemaRefusalException` keeps its §8.2 rule's code), `UnsupportedOperationException` →
  `NOT_IMPLEMENTED`, `BindMismatchException` → `BIND_MISMATCH`.

Related: `design/constructor-application.md` (what `!C { … }` may apply and how its head resolves),
`design/atom-refinement-and-coherence.md` (atom refinement's merge, narrowing and coherence),
`design/template-materialisation.md` and `design/held-template-bodies.md` (§5.10 closing, applied-parent edges),
`design/resolver-vocabulary-and-bootstrap.md` (`WireForm`/`MetaRefs`/`DerivedName`, reference hops, `@synthetic`, the
meta-kernel bootstrap), `design/meta-layer-namespaces.md` (namespace-type payloads),
`design/schema-grammar-and-desugaring.md`, `design/linking-and-compilation.md`.

## The two resolvers

`SchemaResolver` resolves a whole `SchemaDocument` (`TsonSchemaResolver` is its public wrapper): header directives,
the structure namespace from the governing `!!meta`, and the `!!import` merge into the type-name namespace *before*
any local declaration resolves. That merge mirrors `TsonSchemaLinker.mergeImports` — transitive, collisions decided
by entry identity, each import edge judged by `TsonSchemaLinker.checkImportLevel` (§2.2.3) — because it is the same
concept met one phase earlier, and the two are kept in step deliberately. Local declarations resolve on demand
(`OnDemand`), so a supertype or source may be declared later; a cycle through those edges is the author's error.

`DefinitionResolver` (package-private) resolves one `SchemaMap.Declaration` and never sees a document. Everything it
consults is fixed at construction: the type-name namespace and the structure namespace (`DefinitionGetter`s — the
second consulted only for a construction head and an annotation name, §3.3.1), the meta's compiled reader
(`DefinitionMetaReader`) and annotation reader (`AnnotationValueReader`), the `ApplicationCloser` onto the
schema's one `TemplateMaterialiser`, and the `EntryFacts` map it shares with `SchemaResolver` and the materialiser,
so an atom refinement finds the constructor of a source another schema declared. Its class Javadoc lists the
shapes it dispatches and the exact exception boundary.

## Annotations on a declaration

Both annotation sets go through `annotationsFor`: those after `=>` land on the entry's facts (on its value), those
before the name on the entry's key (§6 — a resolver does not hoist key to value). `SchemaResolver` catches the
key set's failures itself, since that loop runs outside the memoized getter that catches the value set's.

The name is checked whether or not a value was written — §6 makes `@T` shorthand for `@T:_` — so silence is never
the outcome: an annotation keeping its name and losing its value would let the schema load clean with the
metadata gone (`SchemaAnnotationScopeTest`). The near miss gets its own wording: a type the schema itself declares
or imports is usable by its *data documents*, not inside the schema document, so the message names the remedy
(move it into a meta-schema). A resolved value is read by that type's own compiled reader, a bare mark as a
synthetic `_` (`BareAnnotationTest`). The bootstrap passes no `AnnotationValueReader`, because it is producing the
entries such a reader would read through; there the name is kept and the value dropped.

## Definition marks and selectors

`abstract` and `final` are words the parser reads at the type-def head (`design/schema-grammar.md`);
`DefinitionMarks` lowers them onto `record.extension`. They are grammar because both change which values a
position admits, which §6 makes the line between a kernel fact and an annotation. No resolver code knows
`abstract`, `final`, `sealed` or `discriminator` as annotation names: the meta declares none, so each written as
one is the ordinary unknown-annotation error.

**The mark is applied once, in `withExtension`.** A fresh record, a composition and a refinement each mint their
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

**A record template also derives selectors from parametric pins** (`SchemaDesugarer.parametricallyPinned`): in a
*fresh* record template a field pinned to a value parameter takes one value per application, so
`<T, V> { type: text = T  value: V }` is a labelled sum with no mark written. A field whose *type* is a parameter
is passed over, and a selector whose type names a parameter is refused (`refuseParameterTypedSelector`) — a
position typed by the base reads the selector before it knows the member. A refinement or composition template
derives none: `array ^ { element_type: = T }` pins a constructor's facet and says nothing about a family.

**On a template**, `abstract` is admitted and `final` refused: every application is a subtype of the template by
construction, so the claim is false before anything else is written (§5.2, §5.10). On a record template the mark
sets the open record's own `extension`, which is each application's — the base itself is ABSTRACT by construction
— and `WireForm.heldRecord` writes it into the held tree that closing reads back. The `=?`-forces-ABSTRACT rule is
a closed record's alone. An open body applying anything but `record` has no `extension` and is refused, named by
the constructor it applies.

## Record templates as family bases

A record template is a `RecordBody` with `parameters`, ABSTRACT by construction (`OpenEntry.isFamilyBase`), and
takes part in IS-A (§5.10). That is safe by *elimination*: no value is read against the template; a value at a
position typed by it is a value of some member, selected by tag or discriminators, each member closed with its
arguments fixed (`AbstractTemplateReader`, `design/class2-compilation.md`). A container, reference or
constructor-application template is a `TemplateBody` with no such dispatch, and stays no type until applied.

**Member dispatch does not travel to a member** (`TemplateMaterialiser.closedExtension`): closing pins the
selectors and the member names none, so it is an ordinary concrete record. ABSTRACT without selectors does travel,
which is how `abstract` reaches every instantiation (`AbstractTemplateFamilyTest`). `FamilySelectors.of` derives
the selector fields from the base's own fields for either kind of base; a record template that IS-A a
base is no member of it, so `RecordExtension`'s pin checks and both encodings' member dispatchers skip an open subtype.

**Two edges populate a family's `subtypes`, from two mechanisms.**

- **§5.8's reference-valued parents.** With `result => abstract <T> { payload: T }` and
  `ok => <T> result<T> & { note: text }`, `result<text>` closes to an ABSTRACT entry whose `subtypes` holds
  `ok<text>` and not `ok<int32>` — the edge folded into `ok<text>`'s chain by `appliedParentEdges`
  (`design/template-materialisation.md`) and inverted by the linker. A template's own `supertypes` holds only the
  closed entries its parents reach, never the template it applies, so `ok` is in no instantiation's `subtypes`
  (`SubtypeTemplateFamilyTest`, `OpenOperandCompositionTest`). Base and members here are all minted, and §8.2
  makes a minted name non-normative, which is why both record dispatchers follow aliases
  (`design/record-dispatch.md`).
- **Membership in the base itself.** A *declared* application of a family base (`dogpet => pet<"dog", dog_type>`)
  carries the base in its own `supertypes` (§8.2's entry shape), added by `familyBaseEdges` and inverted into
  `pet.subtypes`. A *minted* use-site application (`k: pet<"dog", dog_type>`) gets no edge: it is a type read where
  it was written, never a candidate at a `pet` position, so no diagnostic asks a document for its content-derived
  name. A lifted synthetic is never a member either.

A resolved entry's `subtypes` is empty, minted or declared: linking computes it one phase later, which keeps two
schemas closing one application agreeing on the entry §2.2.3 unifies them by (`MintedEntryUnificationTest`).

## Namespace-type payloads

`myapi => !api { … }`, with `api` IS-A the kernel's `namespace`, resolves to a `Namespace` value of kind `NAMESPACE`,
`api` recorded as the constructor (`design/meta-layer-namespaces.md` has the read: `NamespaceInstanceReader`,
`SchemaResolver.payloadNames`, `isValueBody`). The resolver's part: a payload that is no such map is refused
against the entry as no value of the namespace type, with the read's path (`bindAtomInstance`), and
`!namespace { … }` itself is refused as abstract (`refuseAbstractHead`).

## Fields

**A resolved field is four facts, not a state** (`RecordField`, the kernel's `record_field`), held under its name in
`record.fields`. §5.2's `name?: type? ~ value` has one mark per fact: the name's `?` is `optional`, the type's `?` is
`voidable`, and `~`/`=` give the `role` (DEFAULT or FIXED) and its `value`. `FieldModifiers.of` maps the marks one to
one and adds only refusals — a default on an unmarked name, a pin on a voidable type, `= _` and `~ _` (spell
"omitted or `_`" as `a?: void?`), and `=?` on anything but an unmarked name over a non-voidable type.
`SchemaDesugarer` asks the same table for a template body, so the two cannot drift. A parameter in `value` rides
the same slot as a literal and the role is the modifier's: `= P` is FIXED where written, and closing substitutes
the value without changing a fact (§5.7's "Open modifiers").

A tightening replaces the inherited field in place, keeping its position — supertypes' fields first, in their
order, then the body's. A modifier-only entry has no type slot, so it inherits the tightened field's type and with
it its voidability; its name's `?` is always its own. That is why `resolveField` takes the whole inherited
`RecordField`.

**Refinement is three orders** (`refines`), each least to most determined: what omission yields, absent →
missing-field error → injected (`RecordField.omitted`, asked of a plain field); voidable → not; role FREE → DEFAULT
→ FIXED. A restatement refines its source when no order moves backwards, which reproduces §5.7's transition matrix
cell for cell without the matrix existing in code. No rule compares a restated type with its source's here, so
`nickname?: void?` restating `nickname?: text?` narrows the field to `_` and keeps it in the contract.

**A restated field's annotations are the restatement's, in source order, then the inherited field's** (`merged`,
§5.8), one path for refinement and composition. Concatenation, because [TSON-DATA] §3.1 makes an annotation name
repeatable — "the inherited `@doc`" names nothing when two were written. Restatement first, because
`Annotations.get`/`value` take the first occurrence, so the nearer declaration wins at every lookup. No annotation
the meta layer declares changes how a value reads, so the ordering is pinned over resolved output
(`RestatedFieldAnnotationsTest`).

## Field groups

A group is options of members (`GroupDef`, the kernel's `field_group`; §5.11): an option is chosen when any member is
present, a chosen option holds every member `optional_members` does not name, and `field_group.optional` decides
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

- **No schema construct reaches `NOT_IMPLEMENTED`.** The `UnsupportedOperationException` sites left are the
  catch-alls around the compiled meta reader (an exception none of the classified ones), a failed re-serialisation
  of an atom refinement's source, a closed application with no `ApplicationCloser` (a resolver built standalone),
  and the bootstrap's closed switch over `meta-kernel.tn`'s own declarations.
- **A body the constructor's vocabulary rejects** arrives as a `ReadException` and is restated as a schema error
  (`bodyIsNotValidData`, `payloadFailure`). The read `Diagnostic` is dropped: it was produced against a
  `DataValueEvents` replay whose positions are placeholders and whose path points into a synthetic body. The real
  position comes from `SchemaResolver`'s catch.
- **`IllegalStateException`** is for an invariant only a malformed resolved value or a skipped phase could break —
  an applicable head with a non-record body, a constructor not record-shaped (§7.2), and a container sugar form
  reaching resolution unlifted (`DefinitionResolver.unlifted`). `TypeDef`, `StructuralDef` and the AST `TypeRef`
  are sealed and `resolveTypeDef`/`resolveTypeRef` switch over them exhaustively, so the sugar case is the only
  arm left, and only a caller resolving raw AST without `SchemaDesugarer` reaches it
  (`DefinitionResolverTest.anUnliftedSugarFormIsABrokenInvariantNotAGap`).
- **The header is the author's too.** A document with no `!!id` (optional in the grammar, [TSON-DATA] §2.2; required
  of a schema registered under its own name, §2.2.1) and a malformed `!!id` are refused up front as
  `SchemaValidationException`, before any declaration resolves, and `Tson.validateSchema` reports either as a schema
  error against the document as a whole (`SchemaWithoutIdTest`). The refusal is this library's, not the corpus's:
  §10.1 lets an id-less schema be registered under an application-supplied identity, a route no front door here
  offers, so a conforming processor may load the same document cleanly and no vector states the refusal.

**No gap reaches a read either:** every constructor `meta-kernel.tn` and `meta.tn` declare builds a real reader, and
`CoreSchemaImportTest` asserts no `core.tn` entry compiles to an `ErrorReader`. The machinery stays because §2.2.2's
extension point can still reach it — a meta-layer constructor this library has no factory for — and it rides in
the report, located at the value it could not read (`TsonCli.exitCodeFor` lifts the run to 70).

## The `schema.meta` value model

One Java type per kernel record or enum; `Top`/`Type`/`Atom`/`Product`/`Sum` replicate §4.1's composition chain as
Java subtyping, and an entry's kind is `EntryKind.of(value, kernel)`, derived and never stored. Every `Type` record
carries `TypeChain chain` as its first component, mirroring the constructor's flattened shape (`type`'s field first);
`Reference`, `TemplateBody` and `Namespace` carry none. `TypeArgument` is a sealed interface (`Ref`/`Value`), the
kernel's labelled choice, because a record of two `Optional`s would not say that exactly one is present.
Resolved values serialise through `TsonObjectWriter.toTson` with no hand-written writer, which is the proof the
model is idiomatic Java; the textual differences from the `*-resolved.tn` fixtures are listed in
`DefinitionResolverTest`'s Javadoc.

## Deferred

The identity-diagonal FIXED-value invariant — a restated FIXED field MUST NOT change its pinned value (§5.7) — is
unchecked: `resolveTighteningField` does not compare the two values.
