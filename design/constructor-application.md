# Constructor application

What `!C { … }` builds, how its head resolves, and why a template is never one of the things it applies — the
`DefinitionResolver` rules behind §4.1/§4.2/§5.5 construction. Current form only; history lives in git.

**Invariants**

- A template closes by application (`C<...>`), never by construction (`!C { ... }`); naming one at a construction site
  is an author error (`SchemaValidationException`), never an `IllegalStateException`.
- A construction head resolves in the schema's one namespace, among the entries its closure imports
  (§3.3.1), through its alias chain first (`resolveConstructorTarget`): every question is asked of
  the entry at the end, while the entry's facts record the name the author wrote as its constructor.
- A head declared in the same document is made ready before its instance is read, in wait order (`WaitOrder`,
  `design/schema-resolution.md`), whichever is written first; the body is read through readers compiled from the
  entries ready so far. An imported head's body is read through the compiled form of the schema that declared it
  (`design/resolver-vocabulary-and-bootstrap.md`).
- An instance of a constructor meta.tn, numeric.tn, temporal.tn or net.tn declares is the schema model's body. An instance of
  any other constructor is held as a `ValueEntry` where the head composes `meta.top` and not `meta.type` (its instances are
  no types), and refused as `NOT_IMPLEMENTED` where it composes `meta.type` (`SchemaResolver.constructed`).
- `!C { … }` applies a constructor exactly when `C` IS-A `meta.top` (§4.1) — there is no constructor marker — and
  `TsonCompiledVocabulary.buildConstructors` filters on the same predicate, so every constructor head has a reader. With `C`
  any other type the entry is a value of `C` (`resolveValueEntry`, `design/namespace-types-and-value-entries.md`), never a
  refusal for want of applicability.
- A construction never writes `parameters` (`refuseWrittenParameters`): an open record's parameters are the list before
  its body, `<T> { … }` or `<T> !meta.record { … }`, and a parameter list before any other head is refused
  (`openInstance`, `design/open-records.md`).
- An atom instance is **ATOM-kinded and not itself applicable**; both halves are needed, and IS-A `meta.atom` alone runs
  backwards (true of the constructor, false of every instance).
- `!meta.alias { target: X }` resolves to the alias's own entry — `kind: ALIAS`, `X` as source and body — the same
  entry `name => X` denotes.
- Construction transfers kind and no supertypes (§5.5), so an instance carries an empty chain and is not itself a
  constructor. Any schema may declare a constructor; nothing ties it to what the document imports.
- `C<args>` over a constructor (a closed record IS-A `meta.type`) is the construction `!C { … }` over its argument
  slots (§5.3, `ConstructionSugar`), and resolves through the very path the braced form takes: a declaration through
  `resolveInstance`, a use site through the constructor's reader into the content-keyed synthetic. No verdict is the
  sugar's own but the argument count and an argument that is no reference.

Related: `design/schema-resolution.md` (the resolution phase around this), `design/atom-refinement-and-coherence.md`
(`!I ^ { ... }`), `design/template-materialisation.md` and `design/open-records.md` (how `C<...>` closes),
`design/resolver-vocabulary-and-bootstrap.md` (alias hops, the vocabulary), `design/linking-and-compilation.md`.

## Constructor application (`tson-compiler/.../resolver/DefinitionResolver.java`)

- **A template closes by application, never by construction**, and naming one at a construction site is an
  author error (`DefinitionResolver.resolveInstance`). `C<...>` substitutes a template's parameters away
  (§5.10); `!C { ... }` fills a *constructor's* own vocabulary (§4.2). Different operations, and the check
  is on being a template — having parameters — so any §5.10 template gets the
  same advice instead of the "did you mean atom refinement?" hint, which cannot help when what is missing is
  the argument list. **This check is what keeps the construction below it sound**: a parameterised declaration is
  an open record, a `RecordBody` like any constructor's, so without it `!box { … }` over an open record composing
  `meta.top` would apply a template as a constructor.
  `TemplateClosesByApplicationTest` pins all of it.
- **A construction head resolves through its alias chain first** (§8.3, `resolveConstructorTarget`).
  An alias is a hop, not a rewrite, so `alias_array => meta.array` makes `!alias_array { … }` an application of
  `meta.array`, and every question the head is then asked — is it a template, is it applicable, what kind does
  construction transfer, whose vocabulary reads the payload — is a question about the entry at the end.
  Asking the alias would answer all four from an empty supertype chain and an `ALIAS` kind, which is what
  a hop looks like rather than what it points at. The author's spelling survives where it is visible: the tag resolved output
  writes (`EntryFacts.constructor`) is the name they wrote, so the chain stays walkable from resolved output.
- **`!C { … }` applies a constructor exactly when `C` IS-A `meta.top` (§4.1)** (`EntryKind.isConstructor`), asked of that
  entry. §4.1 makes every base kind IS-A `meta.top` and every constructor transitively so, while IS-A stops at
  construction — an instance or a fresh record carries an empty chain — so the predicate admits every constructor and
  nothing else. Over the bundled schemas that is every vocabulary constructor plus `meta.top`, `meta.type`, the three base
  kinds, `meta.namespace`, `meta.alias`, `meta.reference_type` and its refinement `meta.ref` — judged by where `meta.top`
  is defined (one predicate the resolver, the compiled vocabulary's reader table and the linker all ask), and over an
  open record's chain as over a closed one's. Every other head is an ordinary type, and the entry is a value of it
  (`resolveValueEntry`): a component record, `!meta.record_field { … }`, a namespace type, `!api { … }`, and a consumer's
  record type, `!operation { … }`, alike. **A
  per-entry constructor marker would be both too narrow and inconsistent.** `meta.alias` describes no value, so it would go
  unmarked, and the language needs it applicable — which takes a by-name exception. IS-A `meta.top` needs none. `meta.top`,
  `meta.type` and the base kinds are admitted and then
  refused as abstract (§4.1, `refuseAbstractHead`): each is `extension: ABSTRACT`, so its reader is the abstract dispatch
  every abstract record has, and a tag naming the base itself is refused naming the subtypes that would satisfy the position,
  which is the better message. **Admitting `meta.alias` closed means giving it the alias's own entry**, not just letting it
  through: `!meta.alias { target: X }` resolves to `kind: ALIAS` with `X` as its body, the same entry `name => X` denotes
  (§8.3), where a construction of any other head takes the head's kind and records the head as its constructor
  (`design/schema-resolution.md`); the path dispatches on the *body* being an `Alias`, having already read it. **A
  construction of `meta.record` writing `parameters` is refused** (`refuseWrittenParameters`, tested at the head's chain
  end): admitted, it would mint an open record whose parameters no `<…>` put in scope, skipping every declaration-time
  check §5.10 makes of a template. **Two more questions are asked in the same terms.** Atom refinement asks §5.5's question —
  *is this an atom instance?* — as **ATOM-kinded and not itself applicable**, which is exactly what an instance is: §4.1's
  "IS-A
  does not extend below construction" is what separates the pair, `!T {}` transferring kind and not supertypes, so `integer`
  carries an empty chain where `numeric.integer_type => meta.atom & { … }` carries `[meta.atom, meta.top]`. **Both halves are
  needed, and the obvious single test runs backwards.** IS-A `meta.atom` is true of the *constructor* and false of every
  instance — over the bundled entries it selects precisely the wrong side — while kind alone cannot separate them either,
  an atom constructor being ATOM-kinded exactly like its instances. Together they agree on every bundled entry. The
  construction hint in the refusal rides on the same applicability question, so
  `!meta.top ^ { … }` gets the plain answer rather than advice that would fail in turn. **There is no marker.** §12.1's
  grammar has no constructor sigil and §8.1's `meta.type` no `constructor` field: what makes an entry a constructor is that
  it IS-A `meta.top`, which its supertype chain records. **There is no level discipline**: composition propagates the chain,
  so an entry deriving from a constructor *is* one, and any schema may declare one (§4.2).
  **Construction is exempt**: §5.5 transfers kind and no supertypes, so `!C { … }` yields an entry with an empty chain,
  which is why an instance is not itself a constructor. `ApplicabilityIsIsATopTest` and `ConstructorLevelDisciplineTest` pin
  both halves; §3.3.1 and §4.2 are the spec side.
- **What an instance of a schema's own constructor holds** (`SchemaResolver.constructed`). The constructors meta.tn,
  numeric.tn, temporal.tn and net.tn declare bind to the schema model, so their instances are `Top` bodies. Any other
  constructor's instance builds no body this library models. Where its head composes `meta.top` and not the kernel's
  `meta.type` (`s => !step { … }` over `step => meta.top & { … }`), the instance is no type, so it is held as the value it
  is, the way a value entry's payload is: `ValueEntry(step, object)` where the bind context binds `step` to a class,
  `ValueEntry(step, tree)` where none does, so the schema loads and validates without host classes, and a schema obtained to
  bind refuses the tree-held entry with `BIND_MISMATCH` (`design/namespace-types-and-value-entries.md`). Where the head
  composes `meta.type` (`c => meta.product & { … }`), the instance would be a type, whose body must be one of the schema
  model's, and it is refused as `NOT_IMPLEMENTED` (`unmodelledType`): a gap, since a generic body for such an instance is
  open work.
- **A template over a constructor is refused; the constructor is applied instead** (§5.10): `<T> !meta.array {
  element_type: T  min_items: 1 }` has no home for `parameters`, so `openInstance` refuses it, naming the remedy -- the
  application where it is used (`meta.array<T>`, `[T]`), or a refined constructor declared once and applied
  (`nonempty_type => meta.array ^ { min_items?: = 1 }`, then `nonempty_type<T>`). A pin is a field's value, so a
  refinement template pinning a slot to its parameter -- `my_set => <T> meta.array ^ { element_type?: = T }` -- is refused
  with every other parameter at a value (`ParameterTypes.atValue`).

## Construction sugar (`resolver/ConstructionSugar.java`)

- **`C<a₁ … aₙ>` over a constructor is `!C { f₁: a₁ … fₙ: aₙ }`** (§5.3), the arguments landing in `C`'s argument
  slots: its required fields typed by a reference naming types and not pinned by its chain, in declaration order. A
  field is a slot where it is not optional, its role is `FREE` (a pin or a default supplies its value, so the
  construction needs none), and its type, past its alias hops, is a reference whose `of` names types
  (`KindMembers.namesTypes`). `meta.array` has one (`element_type`), `meta.map` two (`key_type value_type`),
  `meta.namespace` the same two, `meta.reference_type` one (`of`), `meta.enum_type` one (`name_type`, `members`
  being a set of values), and `meta.tuple` and the root's `enum` none. Nothing is written on a constructor to permit
  it, and it is the rule `[T]` and `{K => V}` already follow.
- **A constructor here is a closed record IS-A `meta.type`** (`ConstructionSugar.constructs`), read past the head's
  alias hops: its instances are types. A head composing `meta.top` alone builds values and is no constructor of this
  rule, and an open record substitutes (§5.10) whatever its chain reaches -- `listed => <T> meta.atom & { … }` composes
  `meta.top` and is still a template. The check runs before the template path in both places an application resolves,
  so a head is asked once which operation it stands for.
- **The kernel applies its own constructors this way**: the root's `set => meta.set_type` is an alias, so `set<text>`
  constructs `!meta.set_type { element_type: text }` through it, and `meta.ref => meta.reference_type ^ {
  allow_sibling?: = false }` is a refined constructor, so `meta.ref<method>` is `!meta.ref { of: method }`. A refined
  constructor's construction is an instance of the refinement, a subtype constructor, so a question about what a body
  is goes by the constructor's chain (`meta.ref` IS-A `meta.reference_type`, `meta.set_type` IS-A `meta.array`).
- **A declaration is the construction** (`DefinitionResolver.construction`): the payload is built and handed to
  `resolveInstance` as `!C { … }`, so `text_list => meta.array<text>` gets every check and fact the braced form does,
  its tag the head as written, and records no application. **A use site names the synthetic**
  (`TemplateMaterialiser.construction`): the payload, each application among its arguments closed first, is named by
  `DerivedName.ofBinding` over the terminal constructor and read by its reader, so it lands on the entry `[text]` lifts
  to and an application through an alias of the constructor on the same one. Arguments are written as the author
  wrote them, not dereferenced, as the sugar forms write theirs. Nothing is owned: a use site names the content-keyed
  entry whatever a declaration elsewhere constructs, as `[text]` does.
- **The sugar decides where an argument lands and nothing else.** The arity (`requireArity`, also the linker's
  `checkArity` over a held reference such as `meta.array<T>` in an open record) is refused here; a literal
  argument (`meta.array<3>`) never arrives, the grammar refusing it where it is written (§5.10); an argument a slot's
  `of` refuses is refused at link as the braced form's is; a required field no
  slot fills leaves the construction incomplete and the constructor's reader refuses it. A refined constructor's
  instance is its own kind and is not normalised to its source's: `nonempty_type<uuid>` and `[uuid; 1..]` are two
  entries. `ConstructionSugarTest` pins all of it.
- **The desugarer cannot tell a constructor from a record**, so `checkTemplateApplication` refuses an argument list
  only on a local head whose body says it is none: a fresh record, a construction or an atom refinement. A
  composition, a refinement or an alias passes through for resolution to judge.
