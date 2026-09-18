# Linking and registration

Design notes for the registry and identity machinery and for `TsonSchemaLinker`'s pass 2: import merging, reference
validation, the two §5.10 template rules, the inhabitance check and what `record.extension` obliges. Current form only;
history lives in git.

**Invariants**

- The linker lives in `tson-compiler` and the registry in `tson-schema`, on purpose; the linker materializes nothing.
- `CanonicalIdentity.canonicalize` (`tson-base`) is exactly two reductions (strip scheme, strip query); anything else not
  already canonical is rejected.
- Import collisions are decided by an entry's origin schema, not by name occurrence; a local declaration may not reuse a
  name the closure already binds.
- A reference to a DATA-kinded entry is refused at every position a type-ref occupies.
- `entryOrigins` is on `TsonLinkedSchema`, never on `TsonSchema` or `TypeDefinition`.
- `checkHeldArity` asks `HeldBody.applications()` only, never `HeldBody.names()`.
- `TsonSchemaRegistry.register` never overwrites: that plus unmodifiable `entries()` *is* the "locked" guarantee.
- `RecordExtension`'s FINAL check reads `TypeDefinition.supertypes`, never `RecordBody.supertypes`.

Related: `design/meta-layer-data-kind.md`, `design/choice-disjointness.md`, `design/name-hygiene-and-minted-names.md`,
`design/class2-compilation.md`, `design/compiled-registries.md`, `design/schema-resolution.md`.

## Schema registry and linking (`tson-compiler/TsonSchemaLinker.java`, `tson-schema/.../`, `tson-base`)

Resolution handles one declaration at a time (references carried as unverified strings, `!!import` not
consulted). `TsonSchemaLinker`/`TsonSchemaRegistry` add the second stage. **They sit in different modules on
purpose:** the linker is a pipeline stage and lives in `tson-compiler` alongside parse/desugar/resolve/compile,
so every phase that will grow schema-side diagnostics is in one module with `Diagnostic`, and it can reach
`tson-regex` directly (what §5.4 pattern disjointness needs, with no injected-oracle seam); the registry is
storage over the `schema.meta` value model and stays in `tson-schema`, the leaf everything else depends on.

- **`CanonicalIdentity.canonicalize(String)`** (`tson-base`) implements §2.2.1's canonical-identity algorithm — **not**
  general URI normalization. Exactly two reductions (strip scheme + `://`, strip query); everything else must
  already be canonical (lowercase host, no port, no dot-segments, no fragment, no percent-encoding of
  unreserved chars) or it's rejected. `http://` and `https://` resolve to the same identity; a `?sha256=`
  query is dropped, not validated. Two companions: `validate` runs the same checks and discards the result
  (so a caller checking a candidate `!!id` up front reads as such), and `sameIdentity(a, b)` canonicalizes
  both and compares — the recurring question, since a pin or a scheme never distinguishes two references.
  **Public API, not internal machinery**: `TsonSchemaLoader.load` takes a canonical identity as its
  argument, so anything implementing that seam or a `SchemaSource` has to derive them the same way. It
  is the identity half of §2.2.1; `TsonContentHash` is the `?sha256=` half this one strips. It sits in
  `tson-base`'s root package, unprefixed like the rest of that module, because how a schema is named is one
  algorithm across every encoding.
- **`TsonSchemaLinker.link(schema, loader)`** is the pass-2 engine returning a `TsonLinkedSchema` (a thin
  wrapper that is a compile-time proof linking ran): (1) **merge `!!import`s** — each import's *whole
  namespace* copied in as-is (transitive, its own imports included — §2.2.3: "an `!!import` contributes the
  imported schema's entire namespace"), keeping their home namespace, and **each merged
  entry's origin recorded** (`TsonLinkedSchema.entryOrigins`, name → the canonical identity of the schema
  that *declared* it, taken from the import's own `originOf` so an entry two hops away keeps its author
  rather than the intermediary). **Collisions are decided by that origin, not by name occurrence**: one
  schema reached by several routes unifies (the diamond every schema importing core.tn forms — the two
  copies differ only in the `subtypes` each route's own linking credited, so they union), two *different*
  schemas declaring one name is an error naming both, and a local declaration may not reuse a name the
  closure already binds — no hiding, no redefinition. Listing one schema twice, or under two spellings of
  one canonical identity, is redundant rather than an error. Because identities carry the spec revision, a
  closure reaching both `/2026/32/m/core.tn` and `/2026/36/m/core.tn` is rejected here rather than surfacing
  later as a field conflict between two identically-spelled types; (2) **populate `subtypes`**
  (reverse of `supertypes`); (3) **derive `disjoint`** for every choice entry (`ChoiceDisjointness`, §5.4) —
  total and two-valued, detailed in `design/choice-disjointness.md`, so a linked choice always
  carries the fact;
  (3a) **check what `record.extension` obliges** (`RecordExtension`, §5.2) — that nothing composes onto a
  FINAL record, that a sealed family's selectors are usable and its members pin them distinctly, and that
  `@sealed` and `@discriminator` agree; detailed below;
  (4) **validate** every reference
  resolves, with a type-parameter exception (a bare name valid if it's the entry's own declared parameter);
  **a reference to a DATA-kinded entry is refused** — §8.1's schema map holds only type definitions, so an
  entry describing something other than a data value has no way to say "declare me, but let nothing name me
  as a type"; the `Data` body *is* that way, and the check applies at every position a type-ref occupies (a
  field type, a choice variant, an array element, a map value). Without it the misuse resolves, links **and**
  compiles, and fails only when a document is finally read against it (§4.1 makes naming one where a type is
  expected a resolver error). A DATA
  entry's own references are validated too, and it is the body that says which they are — see the `Data`
  note in `design/meta-layer-data-kind.md`;
  **a choice's variants are checked distinct** (§5.4) at the *end of each variant's §8.3 reference chain*
  (`ReferenceChain.terminal`), since an alias and its target are one type — so `(text | my_text)` with
  `my_text => text` is caught, which comparing the written names would miss and which is the only spelling
  an author can't see for themselves; the walk stops on a reference cycle rather than hanging, and an alias
  cycle is then caught by the inhabitance check below; **an author's
  `@disjoint` marker is checked against the derived fact** (§5.4) — `true` verifies it silently, `false` is
  an error, and there is no third outcome because §5.4's derivation is total. There is no unprovable state
  to warn about, and no severity axis to warn on: §8.1 states that a conforming processor has one.
  The marker is read from both places §6 puts it,
  the definition and the map key, which is why the check runs last, after `withNameAnnotations`;
  and a **constructor-eligibility** check with two halves, the same §2.2.2 question asked from both ends
  (§2.2.2, §4.2): a locally-declared constructor — an entry that IS-A `top` — is valid only if the schema's
  `!!meta` is exactly meta-kernel's identity, and a schema named as this one's **`!!meta` target** is valid
  only if *its* `!!meta` is — so an ordinary type library can't govern (naming core.tn as `!!meta` is the
  `!!import` confusion, and core.tn declares no constructors to supply). The target half is judged only when
  the loader actually produced the target; an unresolvable `!!meta` is left to whoever owns fetching, which
  is also what keeps meta-kernel's self-naming `!!meta` linkable mid-registration. **The declaring half is a
  lint, not a guard**: an entry that IS-A `top` in a user schema is *inert*, because applicability is read in
  exactly one place — resolving a `!C value` against the **governing meta's** entries — and the target half
  already refuses to let a user-level schema be named as anyone's `!!meta`, on the same predicate. Nothing
  can chain to it, so no `!xxx_type` can ever occupy a schema position. (In a *data*
  document `!xxx_type { ... }` is an ordinary record annotation and reads fine, which is what makes §8
  resolver-output bodies like `!record { ... }` expressible at all.) Worth keeping anyway, at one comparison:
  "you wrote something that can never do anything" is better said at the declaration than in whichever
  document later tries to name the schema as its `!!meta`. In the shipped wiring
  `TsonCompiledMetaRegistry.loadMeta` reaches that verdict a phase earlier (it must *compile* the meta to
  resolve against it) and raises the linker's own `TsonSchemaLinker.notAMetaSchema` — one wording, one module,
  and a **`SchemaValidationException` rather than an `IllegalStateException`**
  because a wrong `!!meta` is an authoring error, not a library fault (which is what lets the CLI keep exit 1
  and exit 70 apart). `source`
  validation additionally falls back to the governing meta's namespace (a `source` naming a constructor is
  one of §3.3.1's constructor roles); no other reference does — **and not a `source` carrying arguments**,
  which is the one shape the fallback would reach past its own justification. Desugar rewrites every
  constructor application long before resolution, so arguments surviving into a `source` mean a §5.10
  user-template head, which §3.3.1 resolves in the type-name namespace only. Without the exclusion
  `x => tmpl<text>` against a `tmpl` its governing meta declares would find the template through the fallback
  and then fault it on *arity* — telling the author to supply arguments they had written, or that they had
  written the wrong number of them, when the real answer is the one every other reference form gives: the
  name is not in scope. Its other half is in `TemplateMaterialiser` (`design/template-materialisation.md`): an
  application that cannot be closed keeps its argument list rather than collapsing to its bare head, so what
  the linker judges is what the author wrote. **The linker does not materialize anything** —
  `SchemaDesugarer` already turned every sugar form into a real declaration, one phase earlier and in the
  module that can bind a constructor generically. The only argument-bearing `type_ref` it ever sees is inside
  a template declaration, which the desugar phase passes through whole (`box<T>` in `box`'s own body), and
  which is validated, not rewritten.
  - **Two §5.10 rules on templates, both decidable here and neither depending on anyone applying one.**
    *Arity*, over every reference: a reference supplies exactly as many arguments as the entry it names
    declares parameters, which folds three author errors into one rule — too many, too few, and **none at
    all**. That last is the one worth guarding here: a template named without being applied otherwise links
    and compiles clean and fails only at *read* time, against a document with nothing wrong with it. It is
    conditional, and the condition is `template.extension`: a **family base** may be named bare
    (`use => { u: box }` resolves to `box`, and a value there is a value of one of its instantiations), while
    a container, a reference and a constructor-application template carry no `extension`, have no dispatch to
    eliminate their parameters, and are refused (`SPEC-FEEDBACK.md` #13). *Parameter usage*: an open entry references
    every parameter it declares, so
    `box => <T> { v: text }` is rejected — every application of it would denote the same type, and a
    parameter list is author-written, so an unused one is a `SchemaValidationException`.
    - The converse — a parameter used at a *closed* entry — needs no check of its own. The kernel declares one
      `value` slot, and at a closed entry there are no parameters for a token to resolve into, so a token
      there *is* a literal and there is nothing to detect; a parameter *reference* at a closed entry is
      already an unresolved one.
  - **A held body answers the arity rule for the *applications* it writes** (`checkHeldArity`). A held body
    withholds one thing — what a reference *resolves to*, which no argument settles until substitution — so
    type-kind validation and inhabitance wait for materialisation. Arity does not depend on that: it counts
    parameters the *referenced* entry declares. And nothing ever closes `chain => <T> { tail: chain<T, T>? }`,
    so deferring it would let that template ship with the mistake in it.
    - **Applications only, never bare names**, and the distinction is load-bearing.
      `HeldBody.applications()` returns a shape nothing else in the wire tree shares;
      `HeldBody.names()` returns *every* token — field names, states, literals and type references alike.
      Asking the zero-argument half ("this token names an unapplied template") off `names()` rejects a
      correct schema whose field happens to be called `box` beside a template of that name, which is a worse
      failure than a late verdict. So that half runs on the entry materialisation mints, and an unapplied
      template gets no verdict — the open form's own position (§5.10: "an unapplied template is checked no
      further and receives no verdict"), not a shortfall.
  - **`entryOrigins` is on `TsonLinkedSchema`, not on `TsonSchema` or `TypeDefinition`**, because it is a
    fact *linking* establishes rather than part of the resolved schema value §9 defines — and because
    `schema.meta` is a bind target with a hand-written `equals` and the `@Record` constructor-selection trap,
    which a new component would walk straight into. It keeps a declaration's identity and its line answerable
    from the same document however many schemas merged it in — the pair a non-record reader offers as its
    own location (`ValueReaderContext.locationOf`), which is what locates a root-level `!int32` in core.tn
    rather than in whatever schema imported it. The registry stores `TsonLinkedSchema` directly, so the map
    survives registration and every later `load`.
- **`TsonSchemaRegistry.register(TsonLinkedSchema)`** computes canonical identity from `!!id`, rejects a
  duplicate identity (no overwrite — this plus `entries()` being unmodifiable *is* the "locked" guarantee)
  and any self-referential `bootstrap()==true` schema, and stores it. `get(uri)` canonicalizes internally.
  `TsonSchemaLoader` (`Optional<TsonLinkedSchema> load(id)`) is the pluggable import/meta lookup hook,
  registered-only by default (nothing fetched). `TsonSchemaLinker.linkBootstrap` is the one sanctioned way
  to link meta-kernel's raw bootstrap output without registering it.

## The inhabitance check (`TypeInhabitance`, §5.10.1's productivity rule)

**An entry no finite document can satisfy is rejected.** `x => { y: y }` with `y => { x: x }` resolves and
links cleanly otherwise, and fails at the first document as `missing required field 'x'` — blaming the data
for a defect in the schema, at a line the data's author does not control.

- **A least fixed point over the entry graph, not a search.** Every entry starts unknown; a round marks each
  one whose body is satisfied by what is already marked; rounds repeat until nothing changes, which takes at
  most one round per entry. Decidable because the graph is finite — the question is not "does this type have
  a value" but "does this recursion reach a base case".
- **Exact, total and two-valued.** The sibling derivation (`design/choice-disjointness.md`) had to give up
  exactness to stay total; this needs no such trade, and there is no third answer to report.
- **The base cases, and nothing else**: an optional field or tuple position, a container whose `min_items` is
  zero or absent, and a choice variant that does not recur. A choice is the one place the walk **branches**
  rather than conjoins — one good variant is enough, where a product needs every part.
  - **Field groups are walked separately**, because §5.11 makes their members uniformly OPTIONAL in `fields`
    with the requirement carried by the group's own state — reading the field list alone would find nothing
    required and call every group satisfied.
  - **Every REQUIRED-family field counts, the two carrying a value included**: a fixed or default value of a
    type nothing can satisfy does not exist either.
- **Every local entry is judged, referenced or not** — same footing as a declared type parameter the body
  never uses (§5.10). So an uninhabited *variant* is rejected even where the choice around it still works.
  Imported entries are skipped: they were judged when their own schema linked, and repeating the verdict
  would report one defect once per importer.
- **A template with a *resolved* body is judged too, with its parameters assumed inhabited** — a record or
  composition template, which still resolves at its declaration. The assumption is sound in the direction
  that matters: a body that cannot be satisfied even when every argument can has no application that can be.
- **A template with a *held* body is not judged here, and its closure is judged instead.** Its element types
  and bounds are tokens meaning nothing until an application supplies the arguments, so there is nothing to
  read; the closure materialisation mints is an ordinary entry in this map by the time linking runs, and is
  judged like any other. §8's own `tree` fixture is therefore caught the moment anything applies it, and a
  template nobody applies is judged nowhere — the same answer §5.10's deferred checking gives everywhere
  else. `TypeInhabitanceTest` pins both halves.
- **An unresolved reference is inhabited by fiat.** `validateEntry` has already reported it against this very
  entry; calling it uninhabited too would report one defect twice, the second time in words naming a
  different problem. The check runs after that validation for exactly this reason.
- **Scope is structural.** An atom whose own facets admit nothing (`int8 ^ { min: 300 }`) is uninhabited too,
  but that is its constraint family's question, answered at schema load by `Atom.coherenceCheck` (over
  `AtomCoherence`) next to `AtomNarrowing` — asked by the resolver of each written body and again by the linker
  of every entry materialisation mints.

## What `record.extension` obliges (`RecordExtension`, §5.2, §5.7, §5.9)

A checker rather than a derivation, and `ChoiceDisjointness`'s peer in shape: it takes the merged namespace
and the local names and hands back violations, leaving reporting to the linker. The fact itself is written
by the author's mark and lowered by the resolver (`design/schema-resolution.md`); what is left is whether the
rest of the closure agrees with it.

- **Why the linker and not the resolver.** Every rule needs a namespace the declaration does not have. FINAL
  constrains whoever composes onto it, which may be another schema; the family rules range over `subtypes`,
  which nothing populates until linking; and a selector's type has to be followed to the end of its
  reference chain. What is local — two definition marks on one declaration, a mark carrying a value, a mark
  on a non-record — the resolver already refused while lowering.
- **The FINAL check reads `TypeDefinition.supertypes`, never `RecordBody.supertypes`.** §5.9's subtraction
  empties the contract index and leaves the body's authorial lineage in place, minting no IS-A edge — which
  is the only thing FINAL constrains. Reading the body's list instead refuses the one operation §5.9 admits
  against a final type, and no *invalid* case would show it; the corpus carries a `valid` vector for exactly
  that reason.
- **A marked field is the declaration's own only if no sealed supertype declares it.** §5.8 flattens an
  inherited field whole, the mark included, so a subtype's copy of its base's selector is indistinguishable
  here from one the subtype wrote. Without the distinction the rule "a discriminator requires `@sealed`"
  refuses every subtype of every sealed family.
- **A family is re-judged whenever any part of it is local**, base or subtype, which is not the same as
  judging local entries. §3.3.4 makes `subtypes` open across schemas, so an importer really can add a
  member: the new sibling can collide with an imported one, and only a closure holding both can see it.
- **Pins compare as values through `ValueIdentity`**, which is why that class is visible outside its own
  package. §4.3 makes `= 255` and `= 0xFF` one pin and §5.5 makes `= 1` and `= 1.0` one, so a comparison of
  tokens accepts a schema whose dispatch table is not a function. They are read **at the base's declared
  type**, which is [TSON-JSON] §6.1.5's dispatch rule: those are the types known before a subtype is
  selected, so distinctness is judged in the terms a decoder will compare in.
- **Group membership is reported instead of the state rule, not beside it.** §5.11 forces a member OPTIONAL,
  so both would fire and the second would name the symptom. One mistake, one verdict.
