# Constructor application

What `!C { … }` builds, how its head resolves, and why a template is never one of the things it applies — the
`DefinitionResolver` rules behind §4.1/§4.2/§5.5 construction. Current form only; history lives in git.

**Invariants**

- A template closes by application (`C<...>`), never by construction (`!C { ... }`); naming one at a construction site
  is an author error (`SchemaValidationException`), never an `IllegalStateException`.
- A construction head resolves in the schema's one namespace, among the entries its closure imports
  (SPEC-FEEDBACK.md #7), through its reference chain first (`resolveConstructorTarget`): every question is asked of
  the entry at the end, while the entry's facts record the name the author wrote as its constructor.
- A head declared in the same document is made ready before its instance is read, in wait order (`WaitOrder`,
  `design/schema-resolution.md`), whichever is written first; the body is read through readers compiled from the
  entries ready so far. An imported head's body is read through the compiled form of the schema that declared it
  (`design/resolver-vocabulary-and-bootstrap.md`).
- An instance of a kernel or meta.tn constructor is the schema model's body. An instance of any other constructor
  is held as a `ValueEntry` where the head composes `top` and not `type` (its instances are no types), and refused
  as `NOT_IMPLEMENTED` where it composes `type` (`SchemaResolver.constructed`).
- `!C { … }` applies a constructor exactly when `C` IS-A `top` (§4.1) — there is no constructor marker — and
  `TsonCompiledMetaSchema.buildConstructors` filters on the same predicate, so every constructor head has a reader.
  With `C` any other type the entry is a value of `C` (`resolveValueEntry`, `design/meta-layer-namespaces.md`), never
  a refusal for want of applicability.
- `template` is resolver vocabulary (§8.1): IS-A `top`, yet a source declaration applying it, closed or open, is an
  author error (`requireAuthorable`).
- An atom instance is **ATOM-kinded and not itself applicable**; both halves are needed, and IS-A `atom` alone runs
  backwards (true of the constructor, false of every instance).
- `!reference { target: X }` resolves to the alias's own entry — `kind: REFERENCE`, `X` as source and body — the same
  entry `name => X` denotes.
- Construction transfers kind and no supertypes (§5.5), so an instance carries an empty chain and is not itself a
  constructor. Any schema may declare a constructor; nothing ties it to what the document imports.
- A constructor's parameters are confined to no channel: nothing is checked at the declaration, and each channel is
  decided where the argument lands.

Related: `design/schema-resolution.md` (the resolution phase around this), `design/atom-refinement-and-coherence.md`
(`!I ^ { ... }`), `design/template-materialisation.md` and `design/held-template-bodies.md` (how `C<...>` closes),
`design/resolver-vocabulary-and-bootstrap.md` (reference hops, the vocabulary), `design/linking-and-compilation.md`.

## Constructor application (`tson-compiler/.../resolver/DefinitionResolver.java`)

- **A template closes by application, never by construction**, and naming one at a construction site is an
  author error (`DefinitionResolver.resolveInstance`). `C<...>` substitutes a template's parameters away
  (§5.10); `!C { ... }` fills a *constructor's* own vocabulary (§4.2). Different operations, and the check
  is on being a template — having parameters — so any §5.10 template gets the
  same advice instead of the "did you mean atom refinement?" hint, which cannot help when what is missing is
  the argument list. **This check is what keeps the `RecordBody` check below it sound**: a parameterised
  declaration is either held (a `TemplateBody`) or an open record, so without it `!my_set { … }` would reach an
  `IllegalStateException` — this project's spelling of *an internal invariant broke*, which the CLI reports as a
  library fault at exit 70 — and `!box { … }` over a record template would apply a template as a constructor.
  `TemplateClosesByApplicationTest` pins all of it.
- **A construction head resolves through its reference chain first** (§8.3, `resolveConstructorTarget`).
  A reference is a hop, not a rewrite, so `alias_array => array` makes `!alias_array { … }` an application of
  `array`, and every question the head is then asked — is it a template, is it applicable, what kind does
  construction transfer, whose vocabulary reads the payload — is a question about the entry at the end.
  Asking the alias would answer all four from an empty supertype chain and a `REFERENCE` kind, which is what
  a hop looks like rather than what it points at. The author's spelling survives where it is visible: the tag resolved output
  writes (`EntryFacts.constructor`) is the name they wrote, so the chain stays walkable from resolved output.
- **`!C { … }` applies a constructor exactly when `C` IS-A `top` (§4.1)** (`EntryKind.isConstructor`), asked of that
  entry. §4.1 makes every base kind IS-A `top` and every constructor transitively so, while IS-A stops at
  construction — an instance or a fresh record carries an empty chain — so the predicate admits every constructor and
  nothing else. Over the bundled schemas that is every vocabulary constructor plus `top`, `type`, the three base kinds,
  `namespace`, `reference` and `template` — judged by where `top` is defined (one predicate the resolver, the
  compiled vocabulary's reader table and the linker all ask), and over a template's chain as over a closed one's.
  Every other head is an ordinary type, and the entry is a value of it (`resolveValueEntry`): a component record,
  `!record_field { … }`, a namespace type, `!api { … }`, and a consumer's record type, `!operation { … }`, alike. **A
  per-entry constructor marker would be both too narrow and inconsistent.** `reference` describes no value, so it
  would go unmarked, and the language needs it applicable — which takes a by-name exception that the open and closed
  paths must then both remember, or `<T> !reference { target: T }` resolves while `!reference { target: int32 }`
  does not, one construction with two answers. IS-A `top` needs no exception. `top`, `type` and the base kinds are
  admitted and then refused as abstract (§4.1, `refuseAbstractHead`): each is `extension: ABSTRACT`, so its reader
  is the abstract dispatch every abstract record has, and a tag naming the base itself is refused naming the subtypes
  that would satisfy the position, which is the better message.
  **Admitting `reference` closed means giving it the alias's own entry**, not just letting it through: `!reference
  { target: X }` resolves to `kind: REFERENCE` with `X` as its body, the same entry `name => X` denotes (§8.3),
  where a construction of any other head takes the head's kind and records the head as its constructor
  (`design/schema-resolution.md`). The closed
  path dispatches on the *body* being a `Reference`, having already read it; `resolveInstanceTemplate` holds its
  body unread and so goes by the head's name. **`template` is admitted by the predicate and refused by name**
  (`requireAuthorable`, on both paths). It is IS-A `top` because resolved output is typed by it, but it is resolver
  vocabulary (§8.1): an open entry's body, derived from a declaration's `<…>` and never applied by a source
  declaration. Admitted, `!template { parameters: [T] template: "…" }` would mint an open entry with a hand-written
  held body, skipping every declaration-time check §5.10 makes of a template. Tested at the chain's end, so an
  alias of it is refused too. **Two more questions are asked in the same terms.** Atom refinement asks §5.5's
  question — *is this an atom instance?* — as **ATOM-kinded and not itself applicable**, which is exactly what an
  instance is: §4.1's "IS-A does not extend below construction" is what separates the pair, `!T {}` transferring
  kind and not supertypes, so `integer` carries an empty chain where `integer_type => atom & { … }` carries `[atom,
  top]`. **Both halves are needed, and the obvious single test runs backwards.** IS-A `atom` is true of the
  *constructor* and false of every instance — measured, it disagrees with the truth on 103 of the 211 bundled
  entries, selecting precisely the wrong side — while kind alone cannot separate them either, an atom constructor
  being ATOM-kinded exactly like its instances. Together they agree on all 211. The construction hint in the
  refusal rides on the same applicability question, so `!top ^ { … }` gets the plain answer rather than advice that
  would fail in turn. **There is no marker.** §12.1's grammar
  has no constructor sigil and §8.1's `type` no `constructor` field: what makes an entry a constructor is that it
  IS-A `top`, which its supertype chain records. **There is no level discipline**: composition propagates the
  chain, so an entry deriving from a constructor *is* one, and any schema may declare one (SPEC-FEEDBACK.md #7).
  **Construction is exempt**: §5.5 transfers kind and no supertypes, so `!C { … }` yields an entry with an empty
  chain, which is why an instance is not itself a constructor. `ApplicabilityIsIsATopTest` and
  `ConstructorLevelDisciplineTest` pin both halves; §3.3.1 and §4.2 are the spec side.
- **What an instance of a schema's own constructor holds** (`SchemaResolver.constructed`). The kernel's and
  meta.tn's constructors bind to the schema model, so their instances are `Top` bodies. Any other constructor's
  instance builds no body this library models. Where its head composes `top` and not the kernel's `type`
  (`s => !step { … }` over `step => top & { … }`), the instance is no type, so it is held as the value it is, the
  way a value entry's payload is: `ValueEntry(step, object)` where the bind context binds `step` to a class,
  `ValueEntry(step, tree)` where none does, so the schema loads and validates without host classes, and a schema
  obtained to bind refuses the tree-held entry with `BIND_MISMATCH` (`design/meta-layer-namespaces.md`). Where the
  head composes `type` (`c => product & { … }`), the instance would be a type, whose body must be one of the schema
  model's, and it is refused as `NOT_IMPLEMENTED` (`unmodelledType`): a gap, since a generic body for such an
  instance is open work.
- **A constructor's parameters are confined to no channel**, which is §4.2's own rule: "an argument is
  substituted as a token and read by the position it lands in, so a slot typed `type_kind` takes a type where
  an atom-typed slot takes a value". Nothing is checked at the declaration, and each channel is decided where
  the argument lands. A parameter routed into a *vocabulary slot* typed `type_kind` — `my_set => <T> array ^ {
  element_type: = T }` — pins the element type when it closes, `type_kind` being an atom whose value is a type's
  name, so §5.2 admits the fixed value and judges it a member where it is written; the value-routed form
  (`max_items: = N`, an atom-typed
  slot) closes normally. A parameter standing as a *field type* or a *variant* closes into a working type
  rather than a tolerated one: `ctor_box => <T> base & { value: T }` with `flagged => ctor_box<boolean>`
  materialises, compiles, accepts `{ value: true }` and rejects `{ value: banana }` with a `TYPE_MISMATCH` at
  `/value`, and the variant channel behaves the same
  (`DefinitionResolverTest.resolvesACompositionTemplateAsAHeldFlattenedRecord`).
