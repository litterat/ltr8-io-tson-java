# Schema resolution

Design notes for the resolution phase — turning grammar-layer declarations into resolved `schema.meta`
`TypeDefinition` values: namespaces, declaration annotations and marks, template families, composition, refinement,
field states, groups, subtraction, and the exception boundary. Current form only; history lives in git.

**Invariants**

- An annotation on a declaration resolves one hop against the governing meta and nowhere else (§3.3.3); a name that
  misses is a resolver error, the valueless form included. Only the meta-kernel bootstrap skips the check.
- `abstract`/`final` are grammar, not annotations; `=?` is field syntax; and the definition mark is
  applied once, in `resolve`, not inside whichever `resolve*` built the body.
- A restated field's annotations concatenate over the inherited ones, restatement first — never replacement by name.
- A field's name `?`, type `?` and modifier each set one fact; the name's is never inherited, and a modifier-only
  tightening inherits the type's with the elided type. A restated group member stays a member: no name `?`, no
  default, and a pin that is never supplied.
- Refinement of a field is three orders, never a state matrix (`DefinitionResolver.refines`); nothing stores what
  omission yields, so the order on it is checked on `RecordField.omitted`.
- Subtraction runs last, empties `type_definition.supertypes` (every supertype goes) and keeps `record.supertypes` as
  lineage.
- `subtypes` is linking's throughout: a closed entry's own `subtypes` is empty when it is minted.
- A schema error's verdict doesn't change when this library improves; a gap's does. `SchemaResolver` collects three
  exception types into a `Diagnostic`, and the type picks the code: `SchemaValidationException` → `SCHEMA_ERROR`,
  `UnsupportedOperationException` → `NOT_IMPLEMENTED`, `BindMismatchException` → `BIND_MISMATCH`.
- `TypeArgument` stays a sealed interface (`Ref`/`Value`), on the modelling argument (exactly one is present); a
  `schema.meta` bind target with more than one public
  constructor needs `@Record` on the canonical one.

Related: `design/constructor-application.md` (what `!C { … }` may apply and how its head resolves),
`design/atom-refinement-and-coherence.md` (atom refinement's merge, narrowing and coherence),
`design/template-materialisation.md` and `design/held-template-bodies.md` (§5.10 closing),
`design/resolver-vocabulary-and-bootstrap.md` (`WireForm`/`MetaRefs`/`DerivedName`, reference hops, `@synthetic`, the
meta-kernel bootstrap), `design/schema-grammar-and-desugaring.md`, `design/linking-and-compilation.md`.

## Schema resolution (`tson-compiler/.../resolver/`)

`DefinitionResolver` (package-private) turns one grammar-layer `SchemaMap.Declaration` into a resolved
`TypeDefinition` (§4, §8, values from `schema.meta`). `TsonSchemaResolver` (public, root package, a thin
wrapper over `SchemaResolver`) resolves a whole `SchemaDocument`: header-directive validation, deriving
the structure namespace from the governing `!!meta`, merging `!!import` entries into the type-name
namespace *before* any local declaration resolves. That merge is transitive and its collisions are decided
by entry identity, exactly as at link time (§2.2.3, and `design/linking-and-compilation.md`
for the rule in full) — this is the same concept discovered one phase earlier, so the two implementations
are kept in step deliberately.

- **Three namespace dependencies are constructor-fixed, via functional interfaces**, not threaded per
  call: `DefinitionGetter getTypeDefinition(name)` (the accumulating type-name namespace — typically a
  method reference onto a caller's growing map), `DefinitionGetter metaDefinitions` (the structure
  namespace — the governing meta's entries, consulted *only* for a constructor-application target per
  §3.3.1), and `DefinitionMetaReader read(type, value)` (binds a constructor-application/atom-refinement
  value through the governing meta's compiled reader). A resolver with nothing to offer supplies always-
  throwing / always-null constants.
- **An annotation on a declaration resolves one hop and only one hop** (§3.3.3): against the governing
  meta's namespace (`metaDefinitions`), never the schema's own declarations or its `!!import`s. The name is
  checked whether or not a value was written — §6 makes bare `@T` shorthand for `@T:_`, so both forms name a
  type — and a name that misses is a `SchemaValidationException` against the declaration that wrote it,
  with the near miss worded separately: a type *this* schema declares (or imports) is usable by the schema's
  **data documents** and not within the schema document itself, so the message names the remedy (move the
  declaration into a meta-schema and point `!!meta` at it). Silence is the harmful outcome the check exists
  to prevent — an annotation keeping its name and losing its value lets the schema load clean with the
  metadata not there; §6 makes an unresolved annotation name a resolver error, the valueless
  form included (`SchemaAnnotationScopeTest`). A value that *does*
  resolve is read by that type's own compiled reader, so `@doc:"..."` arrives as a `String`. **The one
  resolver that skips the check is the meta-kernel bootstrap**, which passes no `AnnotationValueReader` at
  all: it is producing the very entries such a reader would read through, so every name would fail, and there
  the name is kept and the value dropped. Both annotation sets go through this — the ones after
  `=>` that land on the `TypeDefinition`, and the ones before the name that land on the entry's key — and
  `SchemaResolver` catches the second set's failures itself, since that loop runs outside the memoized getter
  that catches the first set's.
- **The definition mark is grammar, and `DefinitionMarks` is the bridge.** `abstract` and `final` are words
  the parser reads at the type-def head (`design/schema-grammar.md`); this phase maps the parsed mark onto
  `record.extension`, so the surface and the resolved model keep the fact in the same place. §6 fixes the home
  of a fact — an annotation is the right home exactly when the mark changes no value's validity — and both
  marks change it: erase `abstract` and a direct instance becomes readable at that position, erase `final` and
  an importing schema may compose a subtype whose values that position then admits.
  - Nothing in the resolver knows the names `abstract`, `final`, `sealed` or `discriminator` as annotations:
    meta.tn declares none of them, so any of the four written as one is the ordinary unknown-annotation
    error (§3.3.3). There is no name to reserve once the spelling is a word the grammar reads. The one place
    that is not true is a schema governed by the **meta-kernel**, whose bootstrap resolves no annotation at
    all and so ignores the name — which reaches the three bundled schemas and nothing else.
- **A selector is field syntax, `=?`** (`FieldModifiers`), and the record it is written in is the family base:
  the field stays REQUIRED and unpinned — §5.7's identity diagonal forbids a base pinning what its members
  each pin differently — and its name lowers into the enclosing `record.discriminators`. **ABSTRACT is derived
  from it** (`withExtension`): the members pin the selector, so the record is the base they are selected from
  and has no values of its own. `abstract` beside it asserts what the body says and is admitted, `final`
  claims the opposite and is refused. That the family is member-dispatched rather than tag-dispatched is the
  second derivation (ABSTRACT plus a non-empty `discriminators`), read where it is needed
  (`FamilySelectors.dispatchesOnMembers`) in the manner of `choice.disjoint` and never stored as a second fact.
  A group member cannot be a selector: §5.11 makes a value modifier a parse error there.
- **A template derives its selectors from parametric pins too** (`SchemaDesugarer.parametricallyPinned`). In
  a *fresh* record template a field pinned to a value parameter takes one value per application, which is one
  per member, so `<T, V> { type: text = T  value: V }` is a labelled sum with no mark written. A field whose
  declared *type* is a parameter is passed over — nothing could read it before dispatch — and a refinement or
  composition template derives none: `array ^ { element_type: = T }` pins a constructor's own facet and
  states nothing about a family. The
  definition mark is applied in `resolve`, after the body is built and not inside whichever `resolve*` built
  it: a fresh record, a composition and a refinement each mint their own `RecordBody`, and a mark read three
  times is a mark two of them can disagree about — applying it once is also what makes "extensibility is never
  inherited" fall out rather than need stating, a composition's body arriving OPEN from its operands. A mark on
  a non-record is the author's error. A **template** takes `abstract`, whose subject is the template's own
  instantiations (its `subtypes`, next bullet), while `final` is a schema error, every application being a subtype of
  the template by construction, so the claim
  is false before the author writes anything else. **The mark is spliced into the held body rather than set
  on a `RecordBody`** (`WireForm.heldWithExtension`), because by the time a declaration's annotations are read the body is
  text:
  §5.2's `{ x: T }` is rewritten to `!record { … }` at desugar and a composition or refinement template is held
  by `holdIfOpen` one phase later, so neither producer has the mark in hand. Stating `extension: ABSTRACT` in that text
  is enough — materialisation reads the closed body back through the `record` constructor's own
  reader, so nothing in the closing path knows the member exists. An open body applying anything but `record`
  has no such member and is refused, named by the constructor it applies. A restated field keeps a
  discriminator it does not repeat, on the annotation-merge rule's own logic below.
- **A template carrying `extension` is a family base, and takes part in IS-A as one** (§5.10).
  `template.extension` is derived and is always ABSTRACT — a parent has no direct instances — and its
  presence is the test: `TsonSchemaLinker.isFamilyBase`. Such a template is credited under its own supertypes
  (so `base.subtypes` holds `box` beside `box<text>`), its `subtypes` holds its instantiations, and a type
  position naming it compiles to `AbstractTemplateReader` rather than to `OpenTemplateReader`'s refusal. What
  makes that safe is *elimination*: no value is read against the template — a value there is a value of some
  member, selected by a tag or by the discriminators, each member closed with its arguments fixed. A
  container, a reference and a constructor-application template have no such dispatch, carry no `extension`,
  and stay "not a type until applied". Only `final` is refused there, its applications being
  subtypes by construction. **Member dispatch does not travel to a member**
  (`TemplateMaterialiser.closedExtension`): §5.7 fixation pins the selectors and clears their marks, so a
  member is an ordinary concrete record, where ABSTRACT does travel and is how `abstract` reaches every
  instantiation. One dispatcher serves both kinds of base — `RecordMemberDispatchReader` takes the selector
  *fields* rather than a body, and `FamilySelectors.of` (`schema.meta`) derives them — a closed base from its own
  fields, a template base from its members' fields of those names, its own body being held text.
  `RecordExtension` checks the family against that same derivation.
- **Two different edges populate a family's `subtypes`, and they are minted by two different mechanisms.**
  The first is §5.8's reference-valued `supertypes`: `result => abstract <T> { payload: T }` with `ok => <T>
  result<T> & { note: text }` closes at `result<text>` to an ABSTRACT entry whose `subtypes` holds `ok<text>`
  and not `ok<int32>` — the edge being to the instantiation the arguments name, minted by `contractOf` and
  inverted by the linker's ordinary supertype walk. The second is **membership in the family base itself**,
  which for a marked template is the template: every instantiation indexes under the head it closes,
  `pet<"dog", dog_type>` under `pet`,
  read off `source` by `TsonSchemaLinker.indexUnderItsTemplate` and credited only where the template's held
  body carries an `extension` (a container, a constructor application and a reference template have no parent,
  so their applications index nowhere). A closed entry's own `subtypes` is empty when it is minted:
  `subtypes` is linking's throughout, one phase after resolution, which is what keeps two schemas closing one
  application agreeing on the entry §2.2.3 unifies them by (`MintedEntryUnificationTest`). Marking a template
  whose family could never be populated would be marking a type nothing can ever stand at, which is why the
  mark and the family edges are one feature (`AbstractTemplateFamilyTest`).
  **Every entry in such a family is minted**, so §8.2 makes every name in it non-normative and an alias is the
  only spelling a document has for a member *or* for the base — which is what makes §7.2's flattening
  load-bearing at both record dispatchers rather than only at the concrete record readers (`RecordDispatch`).
- **A restated field's annotations merge over the inherited ones, restatement first** (`resolveField`/`merged`).
  §5.8 flattens a composition's inherited fields and §5.7 lets a body entry restate one; absorbing an
  inherited field whole while rebuilding a restated one from only what the restatement wrote would give one
  field two answers. The rule is the restatement's own annotations in source order, then the inherited
  field's, one path serving refinement and composition alike. **Concatenation rather than replacement by name**, because
  [TSON-DATA] §3.1 makes a name
  repeatable on one value with every occurrence preserved — annotations are a list, not a map, so "the
  inherited `@doc`" names nothing when the source wrote two. **Restatement first**, because order *is* the
  precedence mechanism: `Annotations.get`/`value` take the first occurrence, so leading with the nearer
  declaration is what a first-occurrence lookup reads. **The ordering half has no read-side witness**, because
  no annotation the meta layer declares changes how a value reads: everything that decides a spelling belongs
  to the type, the alphabet a `bytes` value is written in included (`bytes_type.encoding`, §5.5). So the
  ordering is demonstrated over resolved output, and §5.8 gives it read-side force wherever an annotation
  directs reading. `RestatedFieldAnnotationsTest` covers each
  case, and §5.8 states the rule: the restatement's own annotations in source order, then the inherited
  field's, adding and never removing.
- **What resolves:** record construction; composition (`A & B & { ... }`, §5.8, with kind from the literal
  base-kind names in the transitive supertype chain, and tightening in the trailing body per §5.7); the
  `^` refinement operator (§5.7, copies the source's whole field set, admits no new fields); bare
  references (§8.3); constructor application (`!C value`, §5.5, binds generically via the compiled
  reader — no hand-rolled name→class table, `tson-bind`'s union resolution finds the `Top` member by
  `@Typename`; a kernel body is a leaf of the sealed hierarchy, and a meta-schema's own constructor is an
  implementation of the open `Data` branch, admitted by the same lookup); atom refinement (`!I ^ { ... }`, §5.5/§5.7);
  subtraction (`A & { ... } - { f }`, §5.9);
  restating a field group in a refinement or composition body (§5.11 — same member labels in the same order,
  types verbatim, state tightening OPTIONAL→REQUIRED only; only the *group's* state moves, since members
  flatten as optional regardless).
- **A resolved field is four facts, not a state** (`RecordField`, the kernel's `record_field`), and §5.2's
  spelling `name?: type? ~ value` has one mark per fact: the name's `?` is `optional` (the key may be omitted),
  the type's `?` is `voidable` (a written `_` is admitted), and `~`/`=` give the `role` (DEFAULT or FIXED) and
  its `value`. `FieldModifiers.of` maps the marks one to one and adds only its refusals: a default on an
  unmarked name (a value omission never reaches), a pin on a voidable type (the written-`_` decision would
  admit what the pin refuses), `= _` and `~ _` in every reading (`_` is no value of a type — "omitted or `_`,
  never a value" is spelled `a?: void?`), and `=?` on anything but an unmarked name over a non-voidable type.
  **A modifier-only entry has no type slot of its own**, so it inherits the tightened field's voidability
  with its type; its name's `?` is always its own. That is why `resolveField` takes the whole inherited
  `RecordField`. A **parametric** `= P` is FREE with the parameter in `value` (§5.7's "Open modifiers") until
  materialisation closes it to FIXED, keeping the name's mark, so the parameter branch sits ahead of the
  literal pin.
- **A restated group member stays a member** (`resolveTighteningField`). Its presence is the group's, so the
  restatement takes no name `?` and the member stays optional whatever it writes; a default is refused; and a
  pin is admitted and **never supplied** (`RecordField.omitted` answers NOTHING for a member), since an
  injected member would be present and presence is what selects the alternative. So no member is ever always
  present, and §5.11's rule against two always-present members has nothing left to refuse: there is no check
  for it.
- **Refinement is three orders** (`refines`), one per question a field answers, each least to most
  determined: what omission yields, absent → missing-field error → injected (`RecordField.omitted`, asked of
  a plain field); voidable → not; role FREE → DEFAULT → FIXED. A restatement refines its source when no order
  moves backwards. That reproduces §5.7's transition matrix cell for cell, and the matrix exists nowhere in
  the code. No rule compares a restated type with its source's here, so `nickname?: void?` restating
  `nickname?: text?` narrows the field to `_` alone and keeps it in the contract.
- **Subtraction runs last and breaks IS-A on purpose** (§5.9). Supertypes merge, the body adds and tightens,
  *then* removals apply to the merged field set with no regard for which supertype contributed a field
  (rule 3 — the contract is already broken, so there is none left to violate). Two things are rejected:
  removing a name that isn't there (rule 2), and removing one this declaration's own body also states
  (rule 4 — adding-then-removing, or tightening-then-removing, says two incompatible things), checked in
  that order because a body-introduced field *is* in the merged set and "no such field" would be the wrong
  diagnosis. The output splits the two supertype lists §8.1 keeps apart: `type_definition.supertypes` is
  **emptied** (the contract — so §7.2's subsumption check won't let a subtracted type stand where its source
  is expected), while `record.supertypes` keeps the head's list as authorial lineage. `kind` still comes off
  the lineage chain. **Every** supertype goes, including one that contributed nothing to the removal — `A & B
  - { f }` with `f` from `A` drops IS-A with `B` too, though `B`'s fields all survive. §4.3 states the break
  at composition's own precision ("subtraction revokes IS-A for every parent while keeping lineage") and
  §5.9 gives the reason: the clause is head-level, so its effect is readable without scanning the parents'
  field sets. Subtract first and compose second where an author wants partial retention.
  Groups follow §5.11: a removed member leaves `members`, a group down to one member is
  dissolved into a plain field taking the *group's* state (members flatten as optional whatever the group
  says, so the survivor would otherwise silently lose a REQUIRED group's "exactly one"), and a group with no
  members left is dropped — §5.11 runs the arity ladder to zero and states the two-member minimum as an
  invariant of resolved output.
- **Two exception types, and which one is deliberate.** `UnsupportedOperationException` means *this library
  hasn't implemented that yet*. No schema construct reaches one: the sites left in `DefinitionResolver` are the
  catch-alls around the compiled meta reader and the re-serialisation of a body (a failure that is not a
  `ReadException`), and shape guards an ordinary pipeline never trips — a grammar-layer `TypeDef` shape the
  dispatch does not know, a container sugar form arriving unlifted, a missing recorded constructor or
  application closer — each the library's fault rather than a verdict. The identity-diagonal FIXED-value
  invariant (a restated FIXED field MUST NOT change its pinned value) is not a throw site at all: it is
  unchecked, and is the one deferred design question below.
  `SchemaValidationException` means *the schema is wrong*, and the spec says so: a tightening outside
  §5.7's transition table, a refinement body field (or group) that adds rather than tightens, an atom body
  whose own facets admit nothing, a
  modifier-only entry with nothing to elide toward (§5.7), a field name two supertypes both contribute or a
  body/group declares twice (§5.8/§5.11), a group restatement that reorders, retypes, changes membership or
  loosens REQUIRED→OPTIONAL (§5.11), a source or supertype whose body is a binding record and so has no
  vocabulary (§5.7's "finished"), a choice or bracketed form at a supertype position (`&` composes record
  types; §12.1 admits these only because `construction-def` draws its operands from `type-ref` where
  `refined-def` takes a name; §12.1's `supertype-ref` narrows those operands to named references), a name in
  a `!` position resolving in neither
  namespace (the plain typo, §3.3.1), a `!` form aimed at the wrong kind of target — refining a
  constructor, applying a non-constructor, refining a non-atom — each answered with the form the author
  probably meant, and **a body the constructor's own vocabulary rejects** (an unknown member, a wrong-typed
  one), which arrives from the compiled meta reader as a `ReadException` and is restated here rather
  than passed on in the reader's currency — the read `Diagnostic` itself is dropped, since it was produced
  against a `DataValueEvents` replay whose positions are all the `(0,0,0)` placeholder and whose `path`
  points into a synthetic body; the declaration's real position comes from `SchemaResolver`'s catch.
  Telling an author their correctly-rejected schema is
  unsupported sends them looking for the wrong fix, and costs more than clarity: both are collected, but a
  misfiled author error is reported as `NOT_IMPLEMENTED`, which the CLI exits 70 on — a library fault rather
  than a verdict on the schema. The useful test is that **a schema error's verdict doesn't change when this
  library improves; a gap's does.** The split is worth keeping honest —
  `IllegalStateException` is the third, for an invariant only a malformed `TypeDefinition` could break (an
  applicable head — an entry that IS-A `top` — with a non-record body, a constructor being record-shaped,
  §7.2).
  `DefinitionResolver`'s Javadoc lists the exact boundary.
- **`TypeArgument` is a sealed interface (`Ref`/`Value`), NOT a plain record — do not "simplify" it
  back.** It is the labelled choice the kernel declares (`type_argument`, a reference or a value), and a plain
  record with two `Optional`s would be a worse model: nothing in the type would say exactly one is present.
  The shape rests on that modelling argument alone — `TypeRef`/`TypeArgument` are mutually recursive, but
  `DataBindContext` resolves a cyclic type graph (a re-entrant `getDescriptor` gets a deferred supplier held
  in a `Memoized`; `RecursiveModelTest`), so binding does not constrain it. Re-read its Javadoc before
  touching it. The cost is a spurious `!ref`/`!value` tag on `toTson` output, documented.
- **`schema.meta` value model:** one Java type per kernel vocabulary record/enum. `Top`/`Atom`/`Product`/
  `Sum` replicate the kernel's composition chain (§4.1) as real Java subtyping — a consumer tests kind
  ancestry with `instanceof Product`. Body leaves are named `RecordBody`/`EnumBody`/etc. (not `Record`,
  which would collide with `java.lang.Record`). Multi-word fields carry `@Field("snake_case")`. **A
  `schema.meta` class used as a bind target with more than one public constructor needs `@Record` on the
  canonical one** or `DefaultRecordBinder` throws (`IntegerType`/`IntegerSize` hit this) — the annotation
  and the fallback both exist for exactly this case.
- **No hand-written writer** — resolved values serialize through `TsonObjectWriter.toTson` directly,
  deliberately, to prove the model is idiomatic Java `tson-bind` already binds. Documented textual
  divergences from the fixture (no outer `!type_definition` tag, quoted strings for enum members, empty
  lists written rather than omitted, full `TypeRef` form) are in `DefinitionResolverTest`'s Javadoc.

## The current boundary: what resolves, and what a gap still is

**No `NOT_IMPLEMENTED` is reachable from a schema**, the pipeline reporting `SCHEMA_ERROR` (or `BIND_MISMATCH`, for a
class that cannot bind a type) for everything it refuses. A parameterized supertype resolves (`vip => <T> customer &
box<T>` absorbs the operand's fields while the application is open, the operand contributing its own supertypes but not
its name to the open entry's contract index, a template being no type -- while `record.supertypes`, typed `[type_ref]`,
keeps the application itself, so closing mints the edge to `box<text>` and not to `box<int32>`), and so does an argument
that is itself an application (`box<inner<T>>` — substitution writes a bound reference through `WireForm.refValue`,
which spells one carrying arguments in `type_ref`'s record form). **A template may be `abstract`**, the mark being
stated in the held body's own text (`extension: ABSTRACT`) and read back by the `record` constructor's reader when the
body closes, so every instantiation is abstract over the family the edge above builds. **A template dispatches on
members** where a selector survives erasure — a `=?` field, or one pinned to a value parameter — its `subtypes`
holding the instantiations the dispatch ranges over; that does not travel to a member, §5.7 fixation having pinned
the selectors and cleared them. Only
`final` stays a resolver error there: every application is a subtype of the template by construction, so
the claim is false before an author writes anything else (§5.10, §5.2).
`OpenOperandCompositionTest` pins the substitutability table, `SubtypeTemplateFamilyTest` the family a base template and
its subtype templates close into, and `AbstractTemplateFamilyTest` the mark over that family. `DefinitionResolver`'s
Javadoc is the exact current boundary. The `UnsupportedOperationException` sites left in the resolver are not constructs
a schema can write: the catch-alls around the compiled meta reader, shape guards an ordinary pipeline never trips, and
the bootstrap's closed switch over meta-kernel's own declarations.

**No gap reaches a read either**: every constructor
meta-kernel.tn and meta.tn declare builds a real reader, and `CoreSchemaImportTest` asserts that no entry
of core.tn — the whole standard library — compiles to an `ErrorReader`. `ErrorReader` stays, and so does
`NOT_IMPLEMENTED`'s machinery: §2.2.2's extension point still reaches one, a meta-layer constructor this
library has never seen having no factory to dispatch to. It **rides in the report**, located at the value
it could not read, and costs that value a verdict and nothing else's — so a gap and an ordinary error in
one document both get reported, and `TsonCli.exitCodeFor` lifts the run to 70.

**One design question is deferred**: the identity-diagonal FIXED-value invariant.
