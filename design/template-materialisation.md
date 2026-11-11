# Template materialisation and regularity

§5.10's closing pass and §5.10.1's regularity check: how `TemplateMaterialiser` turns a template application into an
entry — identity, declared applications, operands and on-demand closing, the IS-A edges closing feeds, how `kind` is
derived — and the static check that runs before any of it.

**Invariants**

- The pass order in `SchemaResolver` is fixed: the driving loop resolves every declaration, then
  `TemplateRegularity.check`, then `ParameterTypes.check` and `checkDeferred`, then `materialise`, then
  `appliedParentEdges` and `closeChains`. Every step reads what the one before it settled.
- **Nothing is minted.** An application is closed where it stands, or closes into the declaration that names it, or
  is reported; no entry is added under a name derived from content or from the application.
- A template that fails the regularity check, or whose parameters stand where none may, is replaced with the
  error placeholder in **both** the entry map (which `materialise` walks) and the namespace (through which an
  application's head resolves).
- The pass runs over the **resolved** form through `MetaRefs`, whose `Rewrite` tells a type slot (`atSlot`) from a
  slot that holds a name (`atName`). In an open record it closes only the field types that name none of its
  parameters (`OpenEntry.parameterIn`).
- An application over a **constructor** (`construction`, `design/constructor-application.md`) is the construction
  written in place at a type slot: construction sugar builds the binding record the braced form writes, the
  constructor's reader reads it, the body is walked so each argument closes where it landed (a nested constructor's
  application, which an open record holds for `{text => [T]}`, in place too), and the slot holds `TypeInline` of it,
  under the head past its alias hops (`pair_of<int32>` under `pair_of`, `set<text>` under `meta.set_type`).
  Only a container, a tuple, a choice or a reference is written in place; any other construction is refused, to be
  declared.
- **Where only a name may stand an application closes nowhere** (§4.2): at a slot `MetaRefs` walks with `atName` — a
  map's key, a reference's `of`, an alias's target — a construction and an application of an open record are both
  refused (`closeName`, `nameRequired`), and so is an application at a parameter's bound (`ParameterTypes.requireType`).
  Each is a `SchemaValidationException` at the declaration that wrote it, naming the declaration to write.
- An application of an **open record** closes into the declaration that names it (`closeApplicationInto`): the
  record itself, recorded with its application. A nested application equal to the one a declaration is closing names
  that declaration (`closingInto`), so a recursive open record closes into its declaration. Any other application of
  an open record at a type slot — of a closed record, or left in another record's body by closing it — would close in
  place, and is reported as not yet supported (`UnsupportedOperationException`, `NOT_IMPLEMENTED`), as is one at a
  refinement source. Two are refused first, being errors whatever is supported (`instantiate`): one whose record in
  place would be IS-A a declared type, through its template's written parents or an applied parent a declaration
  names (`refuseMembership`, §5.2), and one of a recursive open record (`recursive`, §5.10.1). Held in an open record,
  either is reported at the declaration whose application closed it.
- An argument of an open record's application is a name or a parameter (§5.3): a construction or an application
  there is refused (`closeArgument`, `argumentNotAName`), to be declared under a name and passed by it. Inside an open
  record the held applications are judged at its declaration (`ParameterTypes`' `requireNames`): every argument of an
  open record's application, and every argument of a constructor's that lands at a slot holding only a name
  (`ConstructionSugar.slotsInPlace`); one at a constructor's type slot is held and closes there. An application's
  arguments are dereferenced before it is closed, and only a reference is dereferenced.
- Every instantiation records its application in the map `SchemaResolver` shares with the materialiser, in its reduced
  form, which the applied-parent fold keys on; the entry's key carries it as `@meta.application` **as written**
  (`asWritten`, `writtenApplications`).
- Every application the pass closes into a declaration is recorded against it (`closedApplications`), and
  `SchemaResolver` stores the list on that entry as `TsonSchema.EntryFacts.closes`: held in memory, never written.
- A composition operand never closes, open or closed: it is absorbed by value. Which parents were applications is
  read off the open record's chain `parents`; a closed type keeps no `parents` once `closeChains` has run.
- No application is IS-A its template (§5.10): its edges are what its template's written parents reach, each closed
  parent through `Ancestry`, and an applied parent through the fold to the declaration that application denotes.
- Kind is derived (`EntryKind.of`, judged by where each base kind is defined) and never written.

**Not yet swept.** Where the sections below speak of a minted expansion, a synthetic, the merge or `ownedBy`, the
invariants above govern: each is refused or gone, and those passages are due for the sweep that removes the naming
code.

Related: `design/open-records.md` (what an open record is, where a parameter stands, bounds and argument
checks), `design/schema-resolution.md` (chains, applied parents),
`design/resolver-vocabulary-and-bootstrap.md` (`WireForm`, `DerivedName`, `MetaRefs`, `@meta.application`),
`design/constructor-application.md`, `design/linking-and-compilation.md`.

## Materialisation (`tson-compiler/.../resolver/TemplateMaterialiser.java`)

- **Arguments are dereferenced before an application closes** (`dereferenced`). A reference is a pure rename (§8.3:
  "a reference is the same type under another name"), so `box<user_id>` over `user_id => uuid` *is* `box<uuid>`
  and must be one entry; otherwise the arguments would be interchangeable at a scalar position and the
  applications refused one layer up. A refinement (`!uuid ^ {}`) and a fresh instance (`!net.uuid_type {}`) are
  ordinary entries and keep their own applications, which is what makes those spellings mean something. **Identity
  is normalised, provenance is not**: the canonical application is what identity keys on, `@meta.application` states
  the application as written past its alias hops, and the use site still names what the author wrote, since no
  pass rewrites use sites (§8.3). It rests on nothing that changes how
  a value reads riding on a reference — a `bytes` alphabet is `binary.bytes_type`'s own selector (§5.5).
  `AliasedArgumentIdentityTest` pins it. `closeApplicationInto` dereferences the same way, so the declared and the
  minted path agree on the name, the ownership key and the recorded application.
- **It runs over the resolved form, not the AST.** An application arrives as a `schema.meta.TypeRef` carrying
  `arguments`, which is all that shape means (a closed form is always an entry named by a bare reference), so
  substitution is a walk over values; and the entry records its application on its key, which an injected
  `SchemaMap.Declaration` has no channel for. That rules out doing this in `SchemaDesugarer` and keeps that phase
  purely syntactic.
- **An open body closes only what names none of its parameters.** `chain<T>` inside `chain` awaits substitution;
  closing it would mint an entry per level keyed on the literal parameter name. A field type that names no
  parameter at any depth (`tag: box<text>`, `tags: [box<text>]`) means the same in every application, so it is
  closed in place and the base names the entry each member names — which is what `FamilySelectors` reads a
  selector's type from, and what keeps the synthetic merge's renames reaching the base.
- **A minted entry's identity is its content** (§8.2): its name derives from its binding record, so two `box<text>`
  anywhere land on one entry, and so do `box<int32>` and `crate<int32>` over two templates with one field list. A
  declared entry's identity is its name, and its key records its application, so an imported schema's
  declared applications unify with a later schema's use sites (`ownedBy`).
- **A declaration naming the application *is* that entry** (§8.2). `text_box => box<text>` resolves to the closed
  record under `text_box`, its key recording the application — not an `Alias` to an entry beside it.
  `DefinitionResolver` reaches `closeApplicationInto`, which builds the body under the declared name, calls
  `MintedNames.claim` for none of it and publishes nothing, so the synthetic is never created rather than created and
  collapsed. It records `ownedBy` instead, which serves twice: as the knot-tie for recursion inside the closure, and
  as what a use site writing the same application reuses. It returns `null` — the caller keeps the reference form —
  for an unresolved or parameterless head and an arity mismatch, both the ordinary path's to report.
  - **Two declarations of one application are two entries**, each closing into its own name, with no privileged
    first — as two hand-written records with identical fields are. Where they are family members §5.2's
    pin-distinctness rule refuses them, the same verdict the hand-written pair gets.
  - **The parent edge is derived after the fact, order-independently** (`SchemaResolver.appliedParentEdges`). The
    entry an applied parent denotes is whichever declaration names that application, and asking during closing
    would make the answer depend on declaration order. The fold indexes entries by their recorded canonical
    application, plus `expansionsByApplication` for the minted expansions that record none (`& box<int32>` reaches
    the entry `box<int32>` at a use site expanded to), and folds each owner and its ancestors into `supertypes` to a
    fixed point.
  - **A use-site application still mints, and must** — it has no author-written name for identity to key on. One
    whose chain names a declared type, through a supertype or a parent application a declaration owns, would be an
    unnameable family member, and `refuseMintedMember` refuses it at minting, naming the application, against the
    declaration that wrote it. A constructor (a record IS-A the kernel's `meta.top`) is exempt. The linker's check
    (`RecordExtension`, `design/inhabitance-and-record-extension.md`) covers the instantiation entry a self-reaching
    expansion keeps.
- **Arguments close innermost-first**, so `box<box<text>>` builds the inner entry before the outer one names it.
- **Substitution descends into arguments.** A parameter is always a whole ref (§5.10 admits no head abstraction),
  but it may be one *inside an argument list*, which is what recursion looks like; binding only the outer name
  leaves `T` in `chain<T>` and mints an entry per level.
- **Knot-tying** is the memo, registered before the body is substituted under the application-derived name: a
  recursive application reached during substitution finds the entry under construction, is recorded in `tied`, and
  references it by name. Only an open record is substituted, so every closing has an entry under construction to
  name.
- **The depth guard (`MAX_CLOSING_DEPTH`, 64) is a backstop.** Regular recursion ties the knot on its first repeat
  and never nests, so nothing reaching this pass should run away; the guard exists so a hole in `TemplateRegularity`
  ends as a diagnosis named for the outermost head rather than a `StackOverflowError`, which the exception policy
  cannot classify.
- **Substitution changes no field fact.** A field's value is never a parameter (§5.10), so replacing each
  `ParamRef` with its argument is the whole of it (`Substitution`, in `closeOpenRecord`): every field's type is then
  closed, the applications in it -- a held `meta.array<T>` or `box<T>` among them -- closed now that the parameter is
  bound, and nothing is written out or read back. The result is a `RecordBody` without `parameters`; one left holding
  a `ParamRef` would be refused by the record's constructor. A value that differs per application is pinned where
  the application is declared (`created => response<order> & { status?: = 201 }`), by composition. The record-level
  facts are kept as written too: `extension`, `discriminators`, groups and name type are each application's, so an
  abstract template's application is an abstract record, the base of the declarations composing it (§5.10).
- **A closed record is minted with no subtypes.** `subtypes` is linking's, derived over the closed namespace. Taking
  the template's list would hand each instantiation the whole family and, for a template imported already linked,
  itself — and an entry shaped by *which* schema closed it is not a function of its form, so two schemas closing one
  application would collide instead of unifying (§2.2.3, §8.2).
- **An application whose head names nothing in scope is left whole, arguments and all.** The verdict is the linker's,
  as an unresolved reference; a head stripped of its arguments would instead be faulted for supplying none, the
  opposite of what the author wrote (`'box<some_typo>' field 'v' has an unresolved reference 'some_typo'`).
- **Argument checking.** Arity is checked first, against the template's own `parameters`. Each argument is then
  checked against the parameter it binds (`checkArguments`; `design/open-records.md` has the rule): it must
  name a type that IS-A the parameter's bound. A literal argument (`box<3>`) never reaches here: an argument is a
  reference, and the grammar refuses a literal token where it is written (`TsonSchemaParser`, `ReferenceGrammar`,
  `TsonDataStream`).
- **Failures report per entry**, through the receiver resolution uses, so two bad applications in one schema are
  both reported against their own declarations.

## Operands and on-demand closing

A composition operand (§5.8) and a refinement source (§5.7) both need the source's *fields* while a declaration
resolves, before the batch pass runs. They are handled differently.

- **A composition operand never closes, open or closed** (`DefinitionResolver.openOperand`). §8.2: "an application
  there is subsumed as an operand and mints nothing". The operand, an open record, is substituted on the model with
  the arguments as written (`Substitution.fields`), an argument naming the absorbing declaration's own parameter
  landing as that declaration's name for it, which its own record holds as its parameter in turn; the fields are
  absorbed, the operand contributes its template's
  ancestors (`Ancestry.ofTemplate`) and not its own name, and the application is kept in the declaration's
  `parents`, arguments and all. Minting an entry for it would give a form nothing names an entry with one subtype
  and no referent. The arguments still need §5.10's bound check, which closing would have given them, so
  `openOperand` calls `ApplicationCloser.checkArguments`, deferred like every check asked for during resolution
  (`design/open-records.md`, "The call-site check").
  - **Open** (`vip => <T> customer & box<T>`): the open record writes `box<T>` as a parent, and it is substituted
    with everything else when `vip` is applied. Closing builds the chain from those parents (`Ancestry.chainOf`, in
    `closeOpenRecord`), the fold turns `box<text>` into the edge to the entry that application denotes, and
    `closeChains` drops the parents. A closed `vip<text>` therefore stands where `customer`, `base` and `box<text>`
    are expected, the same entry the hand-written `customer & box<text>` reaches. The parent is carried as a
    reference rather than as the head name §5.8 describes because the edge is to `box<text>` and not to
    `box<int32>`. `OpenOperandCompositionTest` pins it.
  - **Closed, on a declaration with no parameters** (`dog => pet_of<dog_type> & { kind?: = "dog" }`): the operand's
    template's ancestors are the declaration's (`pet_of => <T> pet & { … }` makes `dog` IS-A `pet`, so a position
    typed `pet` dispatches to it), and the application kept in the chain is folded like any other, to the entry
    `pet_of<dog_type>` denotes where one exists. No edge is stated to the template: a template is no type (§5.10).
    A removal empties the chain whatever was put in it (§5.9). `ClosedOperandFamilyTest` pins it.
- **A closed refinement source is closed on demand** (`DefinitionResolver.closedApplication`, through the
  `ApplicationCloser` hook): `^` re-emits and tightens the source's whole field set, which lives on the entry the
  application denotes. The closing goes through `close`, so it reuses a declaration that owns the application or
  mints the expansion. The hook is wired to the batch pass's own instance, so an on-demand closing and a later
  closing of the same application land on one entry, and each entry is **published into the namespace as it is
  built**: the source's fields are read through the namespace the very next step. A source applied to the
  declaration's own parameters is absorbed by `openOperand` instead, as composition is.
- **The cycle guard still applies.** An operand's or source's head resolves through the resolution's `WaitOrder`,
  where an entry waiting on itself is refused, so a cycle reached through an application
  (`a => b<text> & { x: text }`, `b => <T> a & { y: T }`) is reported as a wait cycle (`'a' waits on 'b' waits on
  'a'`) rather than recursing (`RecordTemplateTest.aCycleThroughAnApplicationIsStillCaughtAsAWaitCycle`).
- **An argument that is itself an application survives whole.** Substitution writes a bound reference through
  `WireForm.refValue` — a bare token with no arguments, a `ReferenceValue` with them — so `box<inner<T>>`
  keeps `inner<T>` for the absorbing declaration's own materialisation to close. Sharing that producer is the
  requirement: two spellings of one form would be two entries for one type, since a name derives from what is
  written.
- **What an operand may not be.** Each is refused at the declaration that writes it:
  - a parameter as a head, `<T> { v: T<text> }` (`SchemaResolver.refuseHeadAbstraction`, over the open record's
    applications, `OpenEntry.applications`): §5.10 admits no head abstraction. The linker's arity check reads the
    same references, but
    materialisation runs first and removes the parameter, leaving a verdict against a name nobody typed;
  - a parameter as an operand, `<T> T & { … }` or `<T> T ^ { … }` (`DefinitionResolver.refuseParameterOperand`,
    §5.8): a parameter has no field set until applied. The check runs before the name is looked up, so a parameter
    shadows an entry of the same name;
  - a constructor applied to a parameter, `<T> customer & meta.array<T>` (`openOperand`): a construction is a
    binding record with no fields to contribute, the verdict its closed spelling gets once it closes;
  - a template named without arguments, `x => box & { … }` (`DefinitionResolver.refuseUnappliedTemplate`): a
    template is no type, and has fields only once its parameters are bound. Every other bare naming of a template --
    a field, element, map key or value, tuple position, choice variant or argument -- is the linker's arity check
    (`TsonSchemaLinker.checkArity`), refused against the declaration that wrote it.

## Kind (`schema.meta.EntryKind`)

- **`kind` is derived, never stored.** `EntryKind.of(value, kernel)` reads it off the value — a record's chain
  (`ofChain`), otherwise its branch of `Top` — so no slot in the model carries it. A base kind in the chain counts
  only as the kernel's own entry: a schema's own `atom => { … }` composed into `payload => atom & { … }` leaves
  `payload` a PRODUCT. The reader stack branches on the body itself.
  - **The atom-refinement test rests on it** (`DefinitionResolver`): an atom *instance's* body is an `Atom`
    (`integer` carries `!numeric.integer_type {}`), where its constructor's is the vocabulary record describing one
    (`numeric.integer_type` carries a `!meta.record { ... }`). So `body instanceof Atom` separates the pair, where neither
    the kind nor the chain does alone: `numeric.integer_type` is ATOM-kinded exactly like its instances.
- **An open record takes the kind its chain reaches, as a closed record does**: PRODUCT, or the base kind its
  written parents reach. It is no type (§5.10), and its kind says what each application's is.
  - **A closed entry's kind is its substituted value's**, §4.1's "construction transfers kind" asked of the
    construction — not the constructor's *name*.
  - **A template composing onto a base kind closes to a constructor.** `listed => <T> atom & { members: [T] }` makes
    `listed<text>` a constructor whose `!meta.record` body describes its instances, so its kind is the base kind its chain
    reaches (ATOM), as for a declared entry (`EntryKind.ofChain`, which `DefinitionResolver.determineKind` guards);
    construction then transfers it to `!listed_text [...]`. An open record is never constructed, whatever its chain
    reaches: `ConstructionSugar.constructs` excludes it, so `listed<text>` substitutes.
  - **This keeps the derivation total**: every entry's kind follows from what it states, where an open record
    claiming its application's kind would need a lookup outside itself. `OpenEntryResolvedFormTest` covers the
    template above and asserts the rule over every entry of every schema.

## Template regularity (`tson-compiler/.../resolver/TemplateRegularity.java`)

§5.10.1's regularity rule, checked over the resolved entries before anything materialises: **within a template body,
a recursive application — direct or mutual — must pass each parameter through unchanged.**

- **A static rule, not a runtime limit** (§5.10.1: "the check is static, at declaration time"). A template that grows
  its argument every level (`weird => <T> { next: weird<box<T>>? }`) reaches `weird<box<text>>`,
  `weird<box<box<text>>>`, …, so every instantiation is distinct, dedup never fires, and there is no finite set of
  types to build; no portable depth limit tells that from legitimate depth. Checked at the declaration, a template
  nobody applies is still rejected, at the line that wrote it.
- **Mutual recursion needs reachability, not a self-edge**: neither template in an `a → b → a` cycle applies
  itself, so an application is checked whenever its head can reach the declaration it sits in.
- **Applications nested inside arguments are checked too** (`box<deep<box<T>>>`), so the walk recurses through
  `TypeRef.arguments`. The applications come from the open record's references (`OpenEntry.applications`), the ones
  `refuseHeadAbstraction` and the linker's arity check also read.
- **Deliberately stricter than termination requires.** What bounds the work is weaker — every argument a bare
  parameter reference, at any position — since arguments are only copied, so permuting
  (`swap => <A, B> { x: swap<B, A> }`) or duplicating still reaches finitely many instantiations. Positional
  identity is the standard restriction §5.10.1 names, and an over-restriction simple to state can be loosened later
  where the reverse cannot. `TemplateRegularityTest.permutingParametersIsRejectedThoughItWouldTerminate` pins it.
- **Arity is not checked here**; the linker checks it over an open record's references (`TsonSchemaLinker`, §5.10), so
  this compares positionally only where the arity already matches.
- **A condemned template does not reach materialisation.** `check` returns the names it rejected and
  `SchemaResolver.condemn` replaces each with the placeholder a failed declaration leaves, in both maps. Otherwise an
  application of it would run to `MAX_CLOSING_DEPTH` and report the defect a second time, against whichever entry
  applied it, with a 64-link chain of synthetic names attached.
