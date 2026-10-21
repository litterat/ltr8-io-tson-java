# Template materialisation and regularity

§5.10's closing pass: how `TemplateMaterialiser` turns a template application into an entry — identity, declared
applications, on-demand closing, the IS-A edges closing mints, how `kind` is derived — and the static regularity check
that runs before it. Current form only; history lives in git.

**Invariants**

- The pass runs over the **resolved** form after the driving loop. In an open record it closes only the field types that
  name none of its parameters (`OpenEntry.parameterIn`, applied in `MetaRefs`); a held body is not scanned.
- An application's arguments are dereferenced before it is closed, and only a reference is dereferenced — a refinement
  and a fresh instance keep their own applications.
- A declaration naming an application **is** that entry (`closeApplicationInto`): no internal name is derived or
  claimed and nothing is published beside it; `ownedBy`, keyed on the canonical application, is what a use site reuses.
- The memo is registered before the body is substituted (knot-tying), arguments close innermost-first, and
  substitution descends into arguments.
- On-demand closing (a composition supertype, a refinement source) shares this same instance and publishes each entry
  into the namespace as it is built.
- Which parents were applications is read off the **held** tree's chain `parents`, not off the closed one, whose
  `supertypes` are bare names; a closed type keeps no `parents` once the fold has run, and an open record keeps its
  parents as written.
- An application nothing declares is its expansion: the use site names the closed synthetic closing produced, named
  for its binding record — a record (`mintedRecord`) like any other form — and no instantiation entry is minted. The
  exception is an expansion that names its own application (the knot was tied on it, `TemplateMaterialiser.tied`),
  which keeps an instantiation entry recording the application: the record itself, or a `Reference` to the synthetic.
- A minted record is a member of nothing: one whose chain names a declared type is refused when it is minted
  (`refuseMintedMember`), and the applied-parent fold gives it no edge.
- An instantiation of any kind — a record member, a DATA entry, a self-reaching `Reference` hop — records the
  application it closes in the map `SchemaResolver` shares with the materialiser, which writes it on the entry's key
  as `@application`; that is what §8.2's identity and the applied-parent fold key on.
- Kind is derived (`TypeKind.of`, judged by where each base kind is defined) and never written; a held template is
  `TEMPLATE`. A record, open or closed, takes the base kind its chain reaches; every other entry takes the branch of
  `Top` its body occupies.
- A template `TemplateRegularity` condemns is replaced with a placeholder in both the entry map and the namespace; the
  depth guard stays as a backstop for a hole in that check.

Related: `design/held-template-bodies.md` (what a held body is and how each shape closes, the synthetic merge,
parameter kinds), `design/resolver-vocabulary-and-bootstrap.md` (`WireForm`, `DerivedName`, `MetaRefs`, `@synthetic`),
`design/schema-resolution.md` (marks and template families), `design/constructor-application.md`,
`design/linking-and-compilation.md`.

## Materialisation (`tson-compiler/.../resolver/TemplateMaterialiser.java`)

§5.10's other half: closing a template application by substituting its arguments into the template's
recorded open form, and replacing the application with a reference to the entry that results.

- **An application's arguments are dereferenced before it is closed** (`dereferenced`). A reference is a pure
  rename — §7.2 compares "after reference flattening of both", so `user_id => uuid` makes the two
  interchangeable at every position — which means `box<user_id>` *is* `box<uuid>` and must be one entry.
  Without it the model said the arguments were the same type while the applications were not: interchangeable
  at a scalar position, refused one layer of application up. **Only a reference is dereferenced**; a refinement
  (`!uuid ^ {}`, IS-A `uuid`) and a fresh instance (`!uuid_type {}`, related to neither) are ordinary entries
  and keep their own applications, which is what makes those two spellings mean something. **Identity is
  normalised, not provenance**: the canonical application is what the entry's key records (`@application`),
  and the name the author wrote survives at the use site, which states it as written — a division available because no
  pass rewrites use sites (§8.3). `AliasedArgumentIdentityTest` pins it. **Every reference is a pure rename**, which is what
  the rule rests on: nothing that changes how a value reads rides on a reference — a `bytes` alphabet is
  `bytes_type`'s own `encoding` selector (§5.5), so it is part of the type and travels with it.
- **It runs over the resolved form, not the AST**, as a pass in `SchemaResolver` after the driving loop.
  Two reasons. An application arrives here as a `schema.meta.TypeRef` carrying `arguments` — the one thing
  that shape means, since a closed form is always an entry named by a bare reference — so substitution is a
  walk over value types rather than a second AST rewrite. And the entry it mints records its application
  on its key (`@application`), which is what §8.2 keys identity on; an injected
  `SchemaMap.Declaration` has no channel for one, which is what rules out doing this in `SchemaDesugarer` alongside
  the sugar hoists. It also keeps that
  phase purely syntactic.
- **An open body closes only what names none of its parameters.** `chain<T>` inside `chain` awaits substitution and is
  not an application to close: closing it would mint an entry per level, keyed on the literal parameter name. A field
  type that names no parameter, at any depth (`kind: bounded<9>`, `tags: [box<text>]`), means the same in every
  application, so an open record's is closed in place and the base names the entry each member names — which is what
  `FamilySelectors` reads a selector's type from, and what keeps the synthetic merge's renames reaching the base. A held
  body maps nothing: its references are tokens until substitution.
- **A minted entry's identity is its content** (§8.2): its name is derived from its binding record, so two
  `box<text>` anywhere in the schema land on one entry for free, and so do `box<int32>` and `crate<int32>` over two
  templates with one field list. A declared entry's identity is its name, and its key records the canonical
  application (`@application`), so an ingested schema's declared applications unify with a later schema's use sites
  (`ownedBy`).
- **A declaration naming the application *is* that entry, and nothing is minted beside it**
  (§8.2). `text_box => box<text>` resolves to the closed record itself, its key
  recording the canonical application — not a `Reference` to a content-named entry sitting beside it.
  `DefinitionResolver` reaches `closeApplicationInto`, which builds the body **under the declared name**: it
  derives no internal name and claims none (`MintedNames.claim` is never called) and publishes nothing, so
  the synthetic is never created rather than created and then collapsed. What it records instead is
  `ownedBy`, keyed on the canonical application — which serves twice over, as the knot-tie for recursion and
  as what a **use site** writing that same application reuses, so one entry per application still serves the
  schema and §8.2's "two fully-bound applications denote the same entry" holds within it.
  - **Two declarations of one application are two entries**, each closing into its own name. §8.2 gives a
    declared entry its name as its identity, so there is nothing to collapse and no privileged first: the
    pair is two entries with one structure, exactly as two hand-written records with identical fields are.
    Where they are family members §5.2's **pin-distinctness** rule refuses them — the same verdict the
    hand-written pair gets. The duplication is authored rather than derived, and is that rule's to catch.
  - **The parent edge is derived after the fact, order-independently** (`SchemaResolver.appliedParentEdges`).
    A composition absorbing an application takes its fields while the entry is still open, so the IS-A edge
    to the owning declaration cannot be written at close time — that declaration may not have resolved yet.
    The pass indexes entries by their own canonical application and folds each owner and its ancestors into
    `supertypes` to a fixed point, so `dogs` reaches `pet` whether it is declared before or after it. A minted
    expansion records no application, so the materialiser's `expansionsByApplication` stands in for it: a
    declaration's `& box<int32>` reaches the entry `box<int32>` written at a use site expanded to. A minted record
    gains no edge itself (above).
  - **A synthetic still mints, and must.** Only a *declared* application closes into a name; a use-site
    application, like a sugar form, has no author-written name for identity to key on, so it keeps a
    content-derived one (§8.2). One whose chain names a declared type — a supertype, or a parent application a
    declaration owns — would be an unnameable family member, and `refuseMintedMember` refuses it at minting, with
    the application in hand to name and against the declaration that wrote it. The linker's check
    (`RecordExtension`, `design/linking-and-compilation.md`) is left for the instantiation entry a self-reaching
    expansion keeps.
- **Arguments close innermost-first**, so `box<box<text>>` builds the inner entry before the outer one names
  it, and no special case is needed for depth.
- **Substitution descends into arguments.** A parameter is always a whole ref (§5.10 admits no head
  abstraction), but it may be a whole ref *inside an argument list* — which is exactly what recursion looks
  like. Binding only the outer name leaves `T` in place inside `chain<T>` and mints an entry per level.
- **Knot-tying** is the memo, registered before the body is substituted: a recursive application reached
  during substitution finds the entry under construction and references it by name.
- **Non-regular recursion is rejected before this pass runs**, by `TemplateRegularity`, at the declaration
  — see below. What stays here is a depth **backstop**: *regular* recursion ties the knot on its first
  repeat and never nests, so nothing reaching this pass should run away, but if the static check ever has a
  hole the alternative is a `StackOverflowError`, which is neither a diagnosis nor something the exception
  policy can classify.
- **Substitution is where a routed `=` becomes fixed.** §5.7 settles the name's mark at the declaration and the
  value at materialisation: *fixation happens at materialisation, where values are concrete*. `fixRoutedValues` is
  that moment: a bound field arriving FREE with a value becomes FIXED with the argument as its value, its
  `optional` as the name was marked, so
  `response<order, 201>` lands on exactly what the literal `status?: int32 = 201` beside it lands on. Carried
  through unchanged instead, the closed entry held the right value on a field that did not enforce it — a
  constraint the author wrote, silently absent from the type it governs, with no diagnostic anywhere because
  nothing was wrong. **The two spellings are told apart by the role they arrive in**, which is the only
  reason this is recoverable at all: §5.7 sends `= P` to FREE and `~ P` to DEFAULT, so a
  routed default stays a default and data may still override it. §5.7 names the downstream: fixation happens
  at materialisation, where a field whose routed parameter binds to a concrete argument takes the state its
  literal spelling would have.
- **An application whose head names nothing in scope is left whole, arguments and all.** This pass gives no
  verdict on an unresolvable head — that is the linker's, as an unresolved reference — but it still has to
  hand the linker something faithful, and collapsing the application to its bare head is not that: a stripped
  head is faulted for supplying no arguments when the author wrote them. Keeping the list means the linker judges
  what was written (`'box<some_typo>' field 'v' has an unresolved reference 'some_typo'`).
- **Kind checking falls out of substitution.** A value
  argument reaching a type position is the author's error
  — §5.10 infers a parameter's kind from its use, so the body's use and the applied argument are the two
  things being compared. Arity is checked before any of it, against the template's own `parameters`.
  - **§5.10's argument-kind rule is answered by two other rules, not by the kind rule.** A held body has no
    slot types — that is what it is for — so it can never say *this slot expected a value*. Neither half needs
    it to: a literal applied where the body uses the parameter as a **type** is refused because `3` is not an
    identifier at all — `type_ref.name` is typed `identifier`, so it fails where the substituted body is read
    against the kernel's own vocabulary (`'3': U+0033 at index 0 cannot start an identifier`), which is sharper
    than an unresolved-reference verdict — that one would imply an author could go and declare a type called
    `3` — and a type name routed into a field's **value** is refused because §5.2 makes `record_field.value` a value of the
    field's declared type — which catches
    `int32 ~ text` whether a parameter put it there or the author wrote it literally (`TsonSchemaLinker`'s
    `checkFieldValue`, `FieldValueConformanceTest`). §5.10 states the same division — an argument is "read by
    the position it lands in" — and §5.2's value conformance is the half named there.
    `RecordTemplateTest` pins both refusals.
- **Failures report per entry**, through the same receiver resolution uses, so two bad applications in one
  schema are both reported against their own declarations rather than the first aborting the document.
- **Two positions close on demand, during resolution, rather than waiting for the pass.** A composition
  supertype (§5.8) and a refinement source (§5.7) copy the source's *fields*, and a closed application's
  fields live on the entry it denotes — so `DefinitionResolver` closes one itself, through an
  `ApplicationCloser` hook wired to this same instance. Sharing the instance is what makes an on-demand
  closing and a later batch closing of
  the same application land on one entry. Each entry is *published into the namespace as it is built*,
  because absorbing its fields is the very next thing that happens: an entry visible only in this pass's own
  map would be invisible to the lookup right behind it.
  - **The cycle guard still applies.** Closing resolves the head through `SchemaResolver`'s namespace getter,
    which is also the memo the circular-composition check rides on, so a cycle reached *through* an
    application (`a => b<text> & {}`, `b => <T> a & {}`) is reported as a circular composition rather than
    recursing. Pinned, because it is the one thing this wiring could have broken.
  - **An application still naming the declaration's own parameters absorbs without closing anything.**
    `vip => <T> customer & box<T>` needs no materialisation: the operand is an open record, so its field set is
    known while the application is open, and substituting its parameters with the arguments as written — here
    the absorbing declaration's own — yields a held tree still carrying them. Read back through the
    `record` constructor, that is an ordinary field set whose types mention a parameter, which is what a
    template's fields are anyway. Inner applications are deliberately left unclosed: they close when the
    absorbing declaration does, one pass later.
    - **The operand contributes its ancestors and not its name.** An operand is flattened into the declaration,
      not named by it, so `vip`'s own chain is its written parents, `customer` and `box<T>`, in `parents`
      (`[type_ref]`), and the closed entries they reach, `customer` and `base`, in `supertypes`. The held body
      writes the same parents, substituted and closed with everything else in it. Closing builds the chain from
      those parents (`Ancestry.chainOf`, in `closeHeldRecord`): `customer` contributes itself and its own chain,
      `box<text>` its template's ancestors and the application itself, which `SchemaResolver`'s
      `appliedParentEdges` folds into the entry that application denotes, closing every chain to a fixed point;
      `closeChains` then drops the parents of every closed type, keeping an open record's. A closed `vip<text>`
      therefore stands where `customer`, `base` **and** `box<text>` are expected, on the same entry the hand-written
      `customer & box<text>` reaches. **Which parents were applications is read off the held body, not off the
      closed one**, since a closed chain's supertypes are bare names. `OpenOperandCompositionTest` pins all three rows
      and the argument specificity: the edge is to `box<text>` and not to `box<int32>`, which is why the parent is
      carried as a reference rather than as the head name §5.8 describes.
    - **A closed operand on a declaration with no parameters of its own states the edge at resolution.**
      `dog => pet<"dog"> & { breed: text }` is never held, so nothing later closes the application kept in
      its chain and no instantiation ever arrives to carry the edge — which is the same "no later
      materialisation of this body" that makes §5.7's fixation run here rather than at closing. So the edge is
      stated where the composition resolves, and to the *family base* rather than to the application: a
      record template is a family base (`OpenEntry.isFamilyBase`) — ABSTRACT by construction, with its
      `discriminators` where a selector survives — which is what makes it a type a member can be IS-A (§5.10).
      That is what puts the member in the base's `subtypes` and lets a position typed `pet` dispatch to it, by
      the discriminators or by a tag. The application in between still mints nothing, and §5.9 is untouched: a
      removal empties the chain whatever was put in it. `ClosedOperandFamilyTest` pins it,
      `OpenOperandCompositionTest` the boundary against the open case above.
    - **An argument that is itself an application survives whole.** Substitution writes a bound reference
      through `WireForm.refValue` — positionally when it carries no arguments, in `type_ref`'s record
      form when it does — so `box<inner<T>>` keeps `inner<T>` and the absorbing declaration's own
      materialisation closes it. Sharing that producer is the requirement rather than an economy: two
      spellings of one form are two entries for one type, and `WireForm.refValue`'s `arguments().isEmpty()` branch is
      what §5.6's positional spelling turns on.
    - **A parameter cannot be a head.** `<T> { v: T<text> }` is refused at the declaration that writes it
      (`SchemaResolver.refuseHeadAbstraction`, over the held body's own `applications()`), because
      `type_ref.name` is a `type_name` and §5.10 admits no head abstraction. It cannot wait for the linker's
      arity check, which reads the same accessor: materialisation runs first, and by then the parameter is
      gone — leaving either an arity error against a content-derived name nobody typed, or a wire-vocabulary
      mismatch, neither of which names what the author did.
    - **A parameter is never an operand.** `<T> T & { … }` and `<T> T ^ { … }` are refused at the declaration
      (`DefinitionResolver.refuseParameterOperand`, SPEC-FEEDBACK.md #16): both operators read the operand's field
      set to flatten, and a parameter has none until applied. The check runs before the name is looked up, so a
      parameter shadows an entry of the same name here as everywhere; an operand *applied to* a parameter
      (`result<T>`) is the open-operand case above.
    - **A template named without arguments is no operand either.** `x => box & { … }` is refused
      (`DefinitionResolver.refuseUnappliedTemplate`): a record template is a type a position may name, but an
      operand is flattened, and a template has fields only once its parameters are bound.
- **`kind` is derived, never stored.** `TypeKind.of(value, kernel)` reads it off an entry's value — its chain and
  its branch of `Top` — so writing it would restate what the value already carries; the kernel declares no
  `type_kind`, and the model has no slot for one. A base kind in the chain counts only as the kernel's own entry
  (`kernel` tests where the name is defined): a schema's own `data => { … }` composed into `payload => data & { … }`
  leaves `payload` a PRODUCT. The reader stack branches on the body itself.
  - **Which is what the atom-refinement test rests on** (`DefinitionResolver`): an atom *instance's* body
    IS an atom (`integer` carries `!integer_type {}`), where its constructor's body is the vocabulary record
    describing one (`integer_type` carries a `!record { ... }`). So `body instanceof Atom` separates the pair
    and establishes atom-ness at once, where neither the kind nor the supertype chain does alone — a plain
    record has no supertypes either, and `integer_type` is ATOM-kinded exactly like its instances.
- **A held template's `kind` is `TEMPLATE`, and says nothing about what applying it produces.** §5.10 makes such a
  template not a type, so the entry that cannot validate anything does not claim the kind an application of
  it would take: `set` is `TEMPLATE` rather than PRODUCT, and an open alias is `TEMPLATE` rather than
  REFERENCE — it is a template whose closure is a reference, not a reference that happens to have parameters.
  Like `REFERENCE` it is a derived kind and not a base kind (§4.1). A record template is a type, the family base,
  so it takes a record's kind like any record: PRODUCT, or the base kind its chain reaches.
  - **The closed entry's kind is its substituted value's**: the branch of `Top` it occupies, §4.1's
    "construction transfers kind" asked of the construction. Not the constructor's *name* — a held body's head
    is structure-namespace vocabulary the governing meta declares, and this pass holds only the type-name
    namespace (§3.3.1 keeps them apart).
  - **A template composing onto a base kind closes to a constructor.** `listed => <T> atom & { members: [T] }`
    makes `listed<text>` a constructor: its `!record` body describes its instances, so the body's branch
    (PRODUCT) is the wrong answer, and its kind is the base kind its chain reaches (ATOM), by the rule a
    declared entry follows (`TypeKind.ofChain`, which `DefinitionResolver.determineKind` guards). Construction then
    transfers that kind to `!listed_text [...]`. `OpenEntryResolvedFormTest` covers `atom` and `data`.
  - **And it makes the derivation total.** Every other entry's kind follows from what it already states —
    the base-kind name in its own `supertypes` for a constructor, its body's branch otherwise — and an open
    entry claiming its application's kind would be the one case needing a lookup outside itself.
    `OpenEntryResolvedFormTest` asserts the whole rule over every entry of every schema.

## Template regularity (`tson-compiler/.../resolver/TemplateRegularity.java`)

§5.10's regularity boundary, checked over the resolved entries before anything materialises: **within a
template body, a recursive application — direct or mutual — must pass each parameter through unchanged.**

- **Why it is a static rule and not a runtime limit.** A template that grows its argument every level
  (`weird => <T> { next: weird<box<T>>? }`) reaches `weird<box<text>>`, then `weird<box<box<text>>>`, …, so
  every instantiation is distinct, dedup-by-identity never fires, and there is no finite set of types to
  build. Caught only while materialising it costs a depth counter — a non-portable limit, and the same
  retrofit C++ reached for after shipping templates without a regularity restriction. Caught here it is an
  ordinary schema error at the line that wrote it.
- **A template nobody applies is still rejected.** That is what checking at the declaration buys: caught
  only at closing, `weird` would compile clean and fail at the first user's application, which is the
  pre-concepts C++ error-quality failure in miniature.
- **Mutual recursion needs reachability, not a self-edge** — neither template in an `a → b → a` cycle
  applies itself, so an application is checked whenever its head can reach the declaration it sits in.
- **Applications nested inside arguments are checked too** (`box<deep<box<T>>>`), so the walk recurses
  through `TypeRef.arguments`. The body walk itself is `MetaRefs.mapBodyRefs` used as a visitor,
  so one place knows the shape of a body.
- **Deliberately stricter than termination requires.** The condition that actually bounds the work is weaker
  — every argument a bare parameter reference, at any position — because arguments are only ever copied,
  never constructed, so permuting (`swap => <A, B> { x: swap<B, A> }`) or duplicating still reaches finitely
  many instantiations. Positional identity is what the cited precedent uses (ML restricts polymorphic
  recursion the same way), and an over-restriction that is simple to state can be loosened later where the
  reverse cannot. `TemplateRegularityTest.permutingParametersIsRejectedThoughItWouldTerminate` pins the
  choice so it stays visible.
- **Arity is not checked here.** A never-applied template's arity is still unverified — its own gap — so the
  comparison runs only where the arity already matches.
- **A condemned template does not reach materialisation.** `check` returns the names it rejected and
  `SchemaResolver` replaces each with the same placeholder a failed declaration leaves, in both the entry map
  and the namespace — `materialise` walks the first, an application's head resolves through the second.
  Without that, an application of a condemned template would run to `MAX_CLOSING_DEPTH` and report the
  defect a second time, against the entry that applied it and with a 64-link chain of synthetic names attached. The
  depth guard stays: it exists for a hole in this check, not for a template this check has already caught,
  and the alternative failure it prevents is a `StackOverflowError`.
