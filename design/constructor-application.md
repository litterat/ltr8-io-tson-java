# Constructor application

What `!C { … }` may apply, how its head resolves, and why a template is never one of the things it applies — the
`DefinitionResolver` rules behind §4.1/§4.2/§5.5 construction. Current form only; history lives in git.

**Invariants**

- A template closes by application (`C<...>`), never by construction (`!C { ... }`); naming one at a construction site
  is an author error (`SchemaValidationException`), never an `IllegalStateException`.
- A construction head resolves through its reference chain first (`resolveConstructorTarget`): every question is asked
  of the entry at the end, while `source` records the name the author wrote.
- What `!C { … }` may apply is IS-A `top` (§4.1) — there is no constructor marker — and
  `TsonCompiledMetaSchema.buildConstructors` filters on the same predicate, so a head the gate admits has a reader.
- `template` is resolver vocabulary (§8.1): IS-A `top`, yet a source declaration applying it, closed or open, is an
  author error (`requireAuthorable`).
- An atom instance is **ATOM-kinded and not itself applicable**; both halves are needed, and IS-A `atom` alone runs
  backwards (true of the constructor, false of every instance).
- `!reference { target: X }` resolves to the alias's own entry — `kind: REFERENCE`, `X` as source and body — the same
  entry `name => X` denotes.
- Construction transfers kind and no supertypes (§5.5), so an instance carries an empty chain and is not itself a
  constructor; §2.2.2 eligibility is the linker's, asked as IS-A `top`.
- A constructor's parameters are confined to no channel: nothing is checked at the declaration, and each channel is
  decided where the argument lands.

Related: `design/schema-resolution.md` (the resolution phase around this), `design/atom-refinement-and-coherence.md`
(`!I ^ { ... }`), `design/template-materialisation.md` and `design/held-template-bodies.md` (how `C<...>` closes),
`design/resolver-vocabulary-and-bootstrap.md` (reference hops), `design/linking-and-compilation.md` (eligibility).

## Constructor application (`tson-compiler/.../resolver/DefinitionResolver.java`)

- **A template closes by application, never by construction**, and naming one at a construction site is an
  author error (`DefinitionResolver.resolveInstance`). `C<...>` substitutes a template's parameters away
  (§5.10); `!C { ... }` fills a *constructor's* own vocabulary (§4.2). Different operations, and the check
  is on being a template — having parameters — so any §5.10 template gets the
  same advice instead of the "did you mean atom refinement?" hint, which cannot help when what is missing is
  the argument list. **This check is what makes the `RecordBody` check below it genuinely unreachable**: an
  *open* declaration holds its body (`holdIfOpen`) and a parameterised declaration is exactly one, so without
  it `!my_set { … }` would reach an `IllegalStateException` — this project's spelling of *an internal
  invariant broke* — and the CLI would report an author's schema mistake as a library fault at exit 70.
  `TemplateClosesByApplicationTest` pins all of it.
- **A construction head resolves through its reference chain first** (§8.3, `resolveConstructorTarget`).
  A reference is a hop, not a rewrite, so `alias_array => array` makes `!alias_array { … }` an application of
  `array`, and every question the head is then asked — is it a template, is it applicable, what kind does
  construction transfer, whose vocabulary reads the payload — is a question about the entry at the end.
  Asking the alias would answer all four from an empty supertype chain and a `REFERENCE` kind, which is what
  a hop looks like rather than what it points at. The author's spelling survives where it is visible: `source` records the
  name they
  wrote, so the chain stays walkable from resolved output.
- **What `!C { … }` may apply is IS-A `top` (§4.1)** (`requireApplicable`), asked of that terminal entry. §4.1 makes
  every base kind IS-A `top` and every constructor transitively so, while IS-A stops at construction — an
  instance or a fresh record carries an empty chain — so the predicate admits every constructor and, beyond
  them, exactly the entries describing *a type* rather than a part of one. Over the bundled schemas that is
  every vocabulary constructor plus the four base kinds and `reference`.
  **A per-entry constructor marker would be both too narrow and inconsistent.** `reference` describes no
  value, so it would go unmarked, and the language needs it applicable — which takes a by-name exception that
  the open and closed paths must then both remember, or `<T> !reference { target: T }` resolves while
  `!reference { target: int32 }` does not, one construction with two answers. IS-A `top` needs no exception.
  A base kind is admitted and refuses itself through its own reader, naming the subtypes that would satisfy the position,
  which is the
  better message. What stays out is the component set — `record_field`, `type_ref`, `type_argument`,
  `tuple_element`, `field_group`, `integer_size`, `atom_specification`, `type_definition` — record-bodied
  with empty chains; without a check those fail anyway on `Top` being sealed, but as a `ClassCastException`
  surfaced as `NOT_IMPLEMENTED`, a non-verdict for an author error. `TsonCompiledMetaSchema.buildConstructors`
  filters on the same predicate, so a head the gate admits has a reader.
  **Admitting `reference` closed means giving it the alias's own entry**, not just letting it through:
  `!reference { target: X }` resolves to `kind: REFERENCE` with `X` as source and body, the same entry
  `name => X` denotes (§8.3), where a construction of any other head takes the head's kind and names the head
  as its source. The closed path dispatches on the *body* being a `Reference`, having already read it;
  `resolveInstanceTemplate` holds its body unread and so goes by the head's name.
  **`template` is admitted by the predicate and refused by name** (`requireAuthorable`, on both paths). It is IS-A
  `top` because resolved output is typed by it, but it is resolver vocabulary (§8.1): an open entry's body, derived
  from a declaration's `<…>` and never applied by a source declaration. Admitted, `!template { parameters: [T]
  template: "…" }` would mint an open entry with a hand-written held body, skipping every declaration-time check
  §5.10 makes of a template. Tested at the chain's end, so an alias of it is refused too.
  **Two more questions are asked in the same terms.** Atom refinement asks §5.5's question — *is this an atom instance?* —
  as **ATOM-kinded and not itself applicable**,
  which is exactly what an instance is: §4.1's "IS-A does not extend below construction" is what separates the
  pair, `!T {}` transferring kind and not supertypes, so `integer` carries an empty chain where
  `integer_type => atom & { … }` carries `[atom, top]`.
  **Both halves are needed, and the obvious single test runs backwards.** IS-A `atom` is true of the
  *constructor* and false of every instance — measured, it disagrees with the truth on 103 of the 211 bundled
  entries, selecting precisely the wrong side — while kind alone cannot separate them either, an atom
  constructor being ATOM-kinded exactly like its instances. Together they agree on all 211. The construction
  hint in the refusal rides on the same applicability question, so `!top ^ { … }` gets the plain answer rather
  than advice that would fail in turn. And the governed-compile factory lookup asks IS-A `top`, so a
  construction that resolved reaches a factory rather than failing "out of scope" on a narrower test.
  **There is no marker.** §12.1's grammar has no constructor sigil and §8.1's `type_definition` no
  `constructor` field: what makes an entry a constructor is that it IS-A `top`, which its supertype chain
  records. **§2.2.2 eligibility** — who may declare one —
  asks the linker whether the entry IS-A `top`, so an ordinary type library cannot reach constructor
  level, by composing its way there or otherwise. **There is no separate level discipline**: composition
  propagates the chain, so an entry deriving from a constructor *is* one, and there is nothing to refuse
  beyond eligibility — which refuses the declaration outright in an ordinary schema, and in a meta-schema,
  where extending a vocabulary is the point, simply allows it. `ApplicabilityIsIsATopTest` and
  `ConstructorLevelDisciplineTest` pin both halves; §3.3.1 and §4.2 are the spec side.
- **§4.2's remaining declaration-time rule is placement** — a constructor declared only in a schema whose own
  `!!meta` names the meta-kernel — which is the eligibility rule above, checked in the linker.
  **Construction is exempt**: §5.5 transfers kind and no supertypes, so `!C { … }` yields an
  entry with an empty chain, which is why an instance is not itself a constructor.
- **A constructor's parameters are confined to no channel**, which is §4.2's own rule: "an argument is
  substituted as a token and read by the position it lands in, so a slot typed `type_ref` takes a type where
  an atom-typed slot takes a value". Nothing is checked at the declaration, and each channel is decided where
  the argument lands. A parameter routed into a *vocabulary slot* typed `type_ref` — `my_set => <T> array ^ {
  element_type: = T }` — is refused when it closes, by §5.2's rule that a fixed value is available on a field
  typed by an atom or an enum and nowhere else; the legal value-routed form (`max_items: = N`, an atom-typed
  slot) closes normally. A parameter standing as a *field type* or a *variant* closes into a working type
  rather than a tolerated one: `ctor_box => <T> base & { value: T }` with `flagged => ctor_box<boolean>`
  materialises, compiles, accepts `{ value: true }` and rejects `{ value: banana }` with a `TYPE_MISMATCH` at
  `/value`, and the variant channel behaves the same
  (`DefinitionResolverTest.resolvesACompositionTemplateAsAHeldFlattenedRecord`).
