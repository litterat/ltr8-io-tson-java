# Template materialisation and regularity

§5.10's closing pass and §5.10.1's regularity check: how `TemplateMaterialiser` turns a template application into an
entry — identity, declared applications, operands and on-demand closing, the IS-A edges closing feeds, how `kind` is
derived — and the static check that runs before any of it.

**Invariants**

- The pass order in `SchemaResolver` is fixed: the driving loop resolves every declaration, then
  `TemplateRegularity.check`, then parameter stamping and `recheckEarly`, then `materialise`, then `familyBaseEdges`,
  `appliedParentEdges` and `closeChains`, then the synthetic merge. Every step reads what the one before it settled.
- A template that fails the regularity check, or whose parameter kinds cannot be inferred, is replaced with the
  error placeholder in **both** the entry map (which `materialise` walks) and the namespace (through which an
  application's head resolves). The depth guard stays as a backstop for a hole in the check.
- The pass runs over the **resolved** form. In an open record it closes only the field types that name none of its
  parameters (`OpenEntry.parameterIn`, applied in `MetaRefs`); a held body maps nothing.
- An application's arguments are dereferenced before it is closed, and only a reference is dereferenced — a
  refinement and a fresh instance keep their own applications.
- A declaration naming an application **is** that entry (`closeApplicationInto`): no internal name is derived or
  claimed and nothing is published beside it; `ownedBy`, keyed on the canonical application, is what a use site
  reuses.
- The memo is registered before the body is substituted (knot-tying), arguments close innermost-first, and
  substitution descends into arguments.
- An application nothing declares is its expansion: the use site names the closed synthetic, named for its binding
  record (`mintedRecord` for a record, `closeHeldTemplate` for every other form). The exception is an expansion that
  reaches its own application (`tied`), which keeps an instantiation entry: the record itself, or a `Reference` to
  the synthetic.
- Every instantiation — a declared one, a self-reaching one — records its application in the map `SchemaResolver`
  shares with the materialiser, which writes it on the entry's key as `@application`; §8.2's identity and the
  applied-parent fold key on it.
- A composition operand never closes, open or closed: it is absorbed by value and mints nothing. A closed refinement
  source is closed on demand, through the same materialiser instance.
- Which parents were applications is read off the **held** tree's chain `parents`, not off the closed one, whose
  `supertypes` are bare names; a closed type keeps no `parents` once `closeChains` has run, and an open record keeps
  its parents as written.
- A minted record is a member of nothing: one whose chain names a declared type is refused when it is minted
  (`refuseMintedMember`), and the applied-parent fold skips synthetics.
- Kind is derived (`EntryKind.of`, judged by where each base kind is defined) and never written; a held template is
  `TEMPLATE`. A record, open or closed, takes the base kind its chain reaches; every other entry takes the branch of
  `Top` its body occupies.

Related: `design/held-template-bodies.md` (what a held body is, how each shape closes, the synthetic merge, parameter
kinds and argument checks), `design/schema-resolution.md` (chains, `familyBaseEdges`, the two family edges),
`design/resolver-vocabulary-and-bootstrap.md` (`WireForm`, `DerivedName`, `MetaRefs`, `@synthetic`),
`design/constructor-application.md`, `design/linking-and-compilation.md`.

## Materialisation (`tson-compiler/.../resolver/TemplateMaterialiser.java`)

- **Arguments are dereferenced before an application closes** (`dereferenced`). A reference is a pure rename (§8.3:
  "a reference is the same type under another name"), so `box<user_id>` over `user_id => uuid` *is* `box<uuid>`
  and must be one entry; otherwise the arguments would be interchangeable at a scalar position and the
  applications refused one layer up. A refinement (`!uuid ^ {}`) and a fresh instance (`!uuid_type {}`) are
  ordinary entries and keep their own applications, which is what makes those spellings mean something. **Identity
  is normalised, provenance is not**: the canonical application is what `@application` records, and the use site
  still names what the author wrote, since no pass rewrites use sites (§8.3). It rests on nothing that changes how
  a value reads riding on a reference — a `bytes` alphabet is `bytes_type`'s own selector (§5.5).
  `AliasedArgumentIdentityTest` pins it. `closeApplicationInto` dereferences the same way, so the declared and the
  minted path agree on the name, the ownership key and the recorded application.
- **It runs over the resolved form, not the AST.** An application arrives as a `schema.meta.TypeRef` carrying
  `arguments`, which is all that shape means (a closed form is always an entry named by a bare reference), so
  substitution is a walk over values; and the entry records its application on its key, which an injected
  `SchemaMap.Declaration` has no channel for. That rules out doing this in `SchemaDesugarer` and keeps that phase
  purely syntactic.
- **An open body closes only what names none of its parameters.** `chain<T>` inside `chain` awaits substitution;
  closing it would mint an entry per level keyed on the literal parameter name. A field type that names no
  parameter at any depth (`kind: bounded<9>`, `tags: [box<text>]`) means the same in every application, so it is
  closed in place and the base names the entry each member names — which is what `FamilySelectors` reads a
  selector's type from, and what keeps the synthetic merge's renames reaching the base.
- **A minted entry's identity is its content** (§8.2): its name derives from its binding record, so two `box<text>`
  anywhere land on one entry, and so do `box<int32>` and `crate<int32>` over two templates with one field list. A
  declared entry's identity is its name, and its key records the canonical application, so an imported schema's
  declared applications unify with a later schema's use sites (`ownedBy`).
- **A declaration naming the application *is* that entry** (§8.2). `text_box => box<text>` resolves to the closed
  record under `text_box`, its key recording the application — not a `Reference` to an entry beside it.
  `DefinitionResolver` reaches `closeApplicationInto`, which builds the body under the declared name, calls
  `MintedNames.claim` for none of it and publishes nothing, so the synthetic is never created rather than created and
  collapsed. It records `ownedBy` instead, which serves twice: as the knot-tie for recursion inside the closure, and
  as what a use site writing the same application reuses. It returns `null` — the caller keeps the reference form —
  for an unresolved or parameterless head, an arity mismatch (both the ordinary path's to report) and a partial
  application (§5.10's alias hop mints no entry, so there is none to own).
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
    declaration that wrote it. A constructor (a record IS-A the kernel's `top`) is exempt. The linker's check
    (`RecordExtension`, `design/inhabitance-and-record-extension.md`) covers the instantiation entry a self-reaching
    expansion keeps.
- **Arguments close innermost-first**, so `box<box<text>>` builds the inner entry before the outer one names it.
- **Substitution descends into arguments.** A parameter is always a whole ref (§5.10 admits no head abstraction),
  but it may be one *inside an argument list*, which is what recursion looks like; binding only the outer name
  leaves `T` in `chain<T>` and mints an entry per level.
- **Knot-tying** is the memo, registered before the body is substituted under the application-derived name: a
  recursive application reached during substitution finds the entry under construction, is recorded in `tied`, and
  references it by name. A self-applying alias has no entry under construction to name, so `aliasClosing` reports it
  as a cycle instead.
- **The depth guard (`MAX_CLOSING_DEPTH`, 64) is a backstop.** Regular recursion ties the knot on its first repeat
  and never nests, so nothing reaching this pass should run away; the guard exists so a hole in `TemplateRegularity`
  ends as a diagnosis named for the outermost head rather than a `StackOverflowError`, which the exception policy
  cannot classify.
- **Substitution changes no field fact.** A routed `= P` is FIXED where it is written and `~ P` DEFAULT, `optional`
  as the name was marked (§5.7), so `response<order, 201>` lands on exactly what the literal `status?: int32 = 201`
  lands on. What closing changes is the record-level selector list: the member names none (`asMember`), its pins
  being in place, and an ABSTRACT base that dispatches on members closes to OPEN (`closedExtension`).
- **A closed record is minted with no subtypes.** `subtypes` is linking's, derived over the closed namespace. Taking
  the template's list would hand each instantiation the whole family and, for a template imported already linked,
  itself — and an entry shaped by *which* schema closed it is not a function of its form, so two schemas closing one
  application would collide instead of unifying (§2.2.3, §8.2).
- **An application whose head names nothing in scope is left whole, arguments and all.** The verdict is the linker's,
  as an unresolved reference; a head stripped of its arguments would instead be faulted for supplying none, the
  opposite of what the author wrote (`'box<some_typo>' field 'v' has an unresolved reference 'some_typo'`).
- **Kind checking.** Arity is checked first, against the template's own `parameters`. Each argument is then
  classified by the kind of the parameter it binds and checked against it (`byParameterKind`, `checkArguments`;
  `design/held-template-bodies.md` has the rule): a type argument must IS-A the parameter's bound, a value argument
  must be a value of the parameter's `type`. The two misapplications §5.10's argument-kind rule names land as
  follows, and `RecordTemplateTest` pins both:
  - **A literal applied where the body uses the parameter as a type** (`box<3>`) passes the argument check — a type
    parameter's check reads only a reference — and is refused after substitution, where the body is read against
    the kernel's vocabulary: `type_ref.name` is an `identifier`, so `'3': U+0033 at index 0 cannot start an
    identifier`. That is sharper than an unresolved reference, which would imply a type called `3` could be declared.
  - **A type name applied where the body routes the parameter into a field's value** (`retry<text>` over
    `<N> { attempts?: int32 ~ N }`) is refused at the application: `N`'s recorded type is the field's `int32`, so
    `text` is no value of it. A value written literally in that slot (`int32 ~ text`) is §5.2's value conformance,
    `TsonSchemaLinker.checkFieldValue` (`FieldValueConformanceTest`).
- **Failures report per entry**, through the receiver resolution uses, so two bad applications in one schema are
  both reported against their own declarations.

## Operands and on-demand closing

A composition operand (§5.8) and a refinement source (§5.7) both need the source's *fields* while a declaration
resolves, before the batch pass runs. They are handled differently.

- **A composition operand never closes, open or closed** (`DefinitionResolver.openOperand`). §8.2: "an application
  there is subsumed as an operand and mints nothing". The operand's held body is substituted with the arguments as
  written and read back through its constructor; the fields are absorbed, the operand contributes its template's
  ancestors (`Ancestry.ofTemplate`) and not its own name, and the application is kept in the declaration's
  `parents`, arguments and all. Minting an entry for it would give a form nothing names an entry with one subtype
  and no referent. The arguments still need §5.10's kinds, which closing would have given them, so `openOperand`
  calls `ApplicationCloser.byParameterKind`; without it `t<red>` against `t => <M> { a: st ~ M }` writes a type
  reference into a value slot.
  - **Open** (`vip => <T> customer & box<T>`): the held body writes `box<T>` as a parent, and it closes with
    everything else when `vip` does. Closing builds the chain from those parents (`Ancestry.chainOf`, in
    `closeHeldRecord`), the fold turns `box<text>` into the edge to the entry that application denotes, and
    `closeChains` drops the parents. A closed `vip<text>` therefore stands where `customer`, `base` and `box<text>`
    are expected, the same entry the hand-written `customer & box<text>` reaches. The parent is carried as a
    reference rather than as the head name §5.8 describes because the edge is to `box<text>` and not to
    `box<int32>`. `OpenOperandCompositionTest` pins it.
  - **Closed, on a declaration with no parameters** (`dog => pet<"dog"> & { breed: text }`): nothing later closes
    the declaration, so the edge to the family base is stated where the composition resolves. A record template is
    a family base (`OpenEntry.isFamilyBase`), ABSTRACT by construction (§5.10), so `dog` lands in `pet.subtypes` and
    a position typed `pet` dispatches to it. The application in the chain is folded like any other. A removal
    empties the chain whatever was put in it (§5.9). `ClosedOperandFamilyTest` pins it.
- **A closed refinement source is closed on demand** (`DefinitionResolver.closedApplication`, through the
  `ApplicationCloser` hook): `^` re-emits and tightens the source's whole field set, which lives on the entry the
  application denotes. The closing goes through `close`, so it reuses a declaration that owns the application or
  mints the expansion. The hook is wired to the batch pass's own instance, so an on-demand closing and a later
  closing of the same application land on one entry, and each entry is **published into the namespace as it is
  built**: the source's fields are read through the namespace the very next step. A source applied to the
  declaration's own parameters is absorbed by `openOperand` instead, as composition is.
- **The cycle guard still applies.** An operand's or source's head resolves through `SchemaResolver`'s namespace
  getter, the memo the circular-composition check rides on, so a cycle reached through an application
  (`a => b<text> & { x: text }`, `b => <T> a & { y: T }`) is reported as a circular composition rather than
  recursing (`RecordTemplateTest.aCycleThroughAnApplicationIsStillCaughtAsACircularComposition`).
- **An argument that is itself an application survives whole.** Substitution writes a bound reference through
  `WireForm.refValue` — positionally with no arguments, in `type_ref`'s record form with them — so `box<inner<T>>`
  keeps `inner<T>` for the absorbing declaration's own materialisation to close. Sharing that producer is the
  requirement: two spellings of one form would be two entries for one type, since a name derives from what is
  written.
- **What an operand may not be.** Each is refused at the declaration that writes it:
  - a parameter as a head, `<T> { v: T<text> }` (`SchemaResolver.refuseHeadAbstraction`, over the held body's
    `applications()`): §5.10 admits no head abstraction. The linker's arity check reads the same accessor, but
    materialisation runs first and removes the parameter, leaving a verdict against a name nobody typed;
  - a parameter as an operand, `<T> T & { … }` or `<T> T ^ { … }` (`DefinitionResolver.refuseParameterOperand`,
    §5.8): a parameter has no field set until applied. The check runs before the name is looked up, so a parameter
    shadows an entry of the same name;
  - a template named without arguments, `x => box & { … }` (`DefinitionResolver.refuseUnappliedTemplate`): a
    template has fields only once its parameters are bound.

## Kind (`schema.meta.EntryKind`)

- **`kind` is derived, never stored.** `EntryKind.of(value, kernel)` reads it off the value — a record's chain
  (`ofChain`), otherwise its branch of `Top` — so no slot in the model carries it. A base kind in the chain counts
  only as the kernel's own entry: a schema's own `namespace => { … }` composed into `payload => namespace & { … }`
  leaves `payload` a PRODUCT. The reader stack branches on the body itself.
  - **The atom-refinement test rests on it** (`DefinitionResolver`): an atom *instance's* body is an `Atom`
    (`integer` carries `!integer_type {}`), where its constructor's is the vocabulary record describing one
    (`integer_type` carries a `!record { ... }`). So `body instanceof Atom` separates the pair, where neither the
    kind nor the chain does alone: `integer_type` is ATOM-kinded exactly like its instances.
- **A held template is `TEMPLATE`, and says nothing about what applying it produces.** §5.10 makes it not a type, so
  `set` is `TEMPLATE` rather than PRODUCT, and an open alias is `TEMPLATE` rather than REFERENCE — a template whose
  closure is a reference. It is not a base kind (§4.1). A record template is a type, the family base, and takes a
  record's kind: PRODUCT, or the base kind its chain reaches.
  - **A closed entry's kind is its substituted value's**, §4.1's "construction transfers kind" asked of the
    construction — not the constructor's *name*.
  - **A template composing onto a base kind closes to a constructor.** `listed => <T> atom & { members: [T] }` makes
    `listed<text>` a constructor whose `!record` body describes its instances, so its kind is the base kind its chain
    reaches (ATOM), as for a declared entry (`EntryKind.ofChain`, which `DefinitionResolver.determineKind` guards);
    construction then transfers it to `!listed_text [...]`. `payloads => <T> namespace & { … }` closes to a
    namespace type the same way.
  - **This keeps the derivation total**: every entry's kind follows from what it states, where an open entry
    claiming its application's kind would need a lookup outside itself. `OpenEntryResolvedFormTest` covers both
    templates above and asserts the rule over every entry of every schema.

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
  `TypeRef.arguments`. The applications come from the held tree's `applications()` (`HeldBody`), the accessor
  `refuseHeadAbstraction` and the linker's arity check also read.
- **Deliberately stricter than termination requires.** What bounds the work is weaker — every argument a bare
  parameter reference, at any position — since arguments are only copied, so permuting
  (`swap => <A, B> { x: swap<B, A> }`) or duplicating still reaches finitely many instantiations. Positional
  identity is the standard restriction §5.10.1 names, and an over-restriction simple to state can be loosened later
  where the reverse cannot. `TemplateRegularityTest.permutingParametersIsRejectedThoughItWouldTerminate` pins it.
- **Arity is not checked here**; the linker checks it over a held body's applications (`TsonSchemaLinker`, §5.10), so
  this compares positionally only where the arity already matches.
- **A condemned template does not reach materialisation.** `check` returns the names it rejected and
  `SchemaResolver.condemn` replaces each with the placeholder a failed declaration leaves, in both maps. Otherwise an
  application of it would run to `MAX_CLOSING_DEPTH` and report the defect a second time, against whichever entry
  applied it, with a 64-link chain of synthetic names attached.
