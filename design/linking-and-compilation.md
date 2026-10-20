# Linking and registration

Design notes for the registry and identity machinery and for `TsonSchemaLinker`'s pass 2: import merging, reference
validation, the two §5.10 template rules, the inhabitance check, what `record.extension` obliges and values by name.
Current form only; history lives in git.

**Invariants**

- The linker lives in `tson-compiler` and the registry in `tson-schema`, on purpose; the linker materializes nothing.
- `CanonicalIdentity.canonicalize` (`tson-base`) reads a *reference* in any spelling of its identity; `validate` judges
  a document's own `!!id`, which must already be written canonically. Neither rewrites an identity into validity.
- Import collisions are decided by an entry's origin schema, not by name occurrence; a local declaration may not reuse a
  name the closure already binds.
- A reference to a DATA-kinded entry is refused at every position a type-ref occupies.
- A value by name (`TsonSchema.valuesByName`, collected by the read) resolves its first segment in the schema's own
  merged namespace, alias hops followed, never the meta's; each further segment selects in the bound value; the
  referent's declared type must IS-A the slot's, compared by canonical identity. Local entries only, after
  `validateEntry`, which skips a by-name reference a body also returns from `references()` (matched by identity).
- `entryOrigins`, `enumForms` and `nameTypes` are on `TsonLinkedSchema`, never on `TsonSchema` or an entry's value.
- An enum's and a record's `name_type` is an identifier family whose `normalization` implies NFC (`NONE` never;
  `ASCII_CASEFOLD` only over ASCII — `NameType.formRefusal`); their names are judged under its profile, never the
  kernel's identifier's.
- `FieldNames` never sees two fields of one spelling — `record.fields` is a map, and its reader refuses the repeat —
  so its duplicate check is two spellings that are one value of the record's name type, and nothing else.
- `checkHeldArity` asks `HeldBody.applications()` only, never `HeldBody.names()`.
- `TsonSchemaRegistry.register` never overwrites: that plus unmodifiable `entries()` *is* the "locked" guarantee.
- `RecordExtension`'s FINAL check reads the entry's chain (`Chained.supertypes`), a template's included, which a §5.9
  subtraction leaves empty.
- Everything that asks what an entry IS-A asks `Chained` — `computeSubtypes` credits a template as it does a type,
  and the constructor test (`TypeKind.isConstructor`) reads a template's chain as a closed one's, judging `top` by
  its origin.
- An enum's `name_type` resolves in the governing meta's namespace for the value its constructor pinned (traced through a
  template's held body), and otherwise in the schema's; the enum checks run before the name checks, and an enum they
  refuse is not judged by name again.

Related: `design/meta-layer-data-kind.md`, `design/choice-disjointness.md`, `design/name-hygiene-and-minted-names.md`,
`design/class2-compilation.md`, `design/compiled-registries.md`, `design/schema-resolution.md`.

## Schema registry and linking (`tson-compiler/TsonSchemaLinker.java`, `tson-schema/.../`, `tson-base`)

Resolution handles one declaration at a time (references carried as unverified strings, `!!import` not
consulted). `TsonSchemaLinker`/`TsonSchemaRegistry` add the second stage. **They sit in different modules on
purpose:** the linker is a pipeline stage and lives in `tson-compiler` alongside parse/desugar/resolve/compile,
so every phase that will grow schema-side diagnostics is in one module with `Diagnostic`, and it can reach
`ltr8-regex` directly (what §5.4 pattern disjointness needs, with no injected-oracle seam); the registry is
storage over the `schema.meta` value model and stays in `tson-schema`, the leaf everything else depends on.

- **`CanonicalIdentity.canonicalize(String)`** (`tson-base`) implements §2.2.1's canonical identity over the IRI
  (SPEC-FEEDBACK.md #4) — **not** general URI normalization. Scheme and query are stripped (`http://` and `https://`
  are one identity, a `?sha256=` pin is dropped, not validated); the host is read as an `ltr8-net` `Host` value
  (lowercase U-labels, a dotted-quad, RFC 5952 in brackets — so `Example.COM`, A-labels and an uncompressed IPv6
  address name the identity their canonical form does); the path is read back from its URI spelling (percent-encoded
  UTF-8 of a character beyond US-ASCII decoded, hex case ignored, reserved encodings kept in uppercase hex) and must
  be NFC. A port, userinfo, fragment, dot-segment, encoded unreserved character, or encoding with no IRI form is
  rejected. Three companions: `validate(id)` judges a document's own name, which must be written in that canonical
  form — `SchemaResolver` applies it to `!!id` and `canonicalize` to each `!!import`; `toUri` gives a reference's URI
  spelling (A-labels, percent-encoded UTF-8) for a fetch; and `sameIdentity(a, b)` canonicalizes both and compares.
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
  closure reaching both `/2026/32/m/core.tn` and `/2026/38/m/core.tn` is rejected here rather than surfacing
  later as a field conflict between two identically-spelled types; (2) **populate `subtypes`**
  (reverse of `supertypes`, over every chained value, a template's included); (3) **derive `disjoint`** for every choice
  entry (`ChoiceDisjointness`, §5.4) — total and two-valued, detailed in `design/choice-disjointness.md`, so a linked
  choice always carries the fact;
  (3a) **check what `record.extension` obliges** (`RecordExtension`, §5.2) — that nothing composes onto a
  FINAL record, that a sealed family's selectors are usable and its members pin them distinctly, and that
  every family member is declared;
  detailed below. It is listed here for what it reads, and runs later:
  `link` goes subtypes → disjointness → `checkNames` → validate → inhabitance → `checkRecordExtension` →
  `withNameAnnotations` → the `@disjoint` check;
  (4) **validate** every reference
  resolves, with a type-parameter exception (a bare name valid if it's the entry's own declared parameter);
  **a reference to a DATA-kinded entry is refused** — every schema-map entry is a name a type-ref can reach, so an
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
  and exit 70 apart). A chain's names additionally fall back to the governing meta's namespace: a chain is the
  residue of a constructor role (§3.3.1) rather than an author-written reference, and a refinement of a kernel
  constructor reaches one (`set_type => array ^ {...}` carries `[array, product, top, type]`). No other reference
  does. An application whose head names nothing in scope keeps its argument list rather than collapsing to its
  bare head (`design/template-materialisation.md`), so what the linker judges is what the author wrote.
  **The linker does not materialize anything** —
  `SchemaDesugarer` already turned every sugar form into a real declaration, one phase earlier and in the
  module that can bind a constructor generically. The only argument-bearing `type_ref` it ever sees is inside
  a template declaration, which the desugar phase passes through whole (`box<T>` in `box`'s own body), and
  which is validated, not rewritten.
  - **Two §5.10 rules on templates, both decidable here and neither depending on anyone applying one.**
    *Arity*, over every reference: a reference supplies exactly as many arguments as the entry it names
    declares parameters, which folds three author errors into one rule — too many, too few, and **none at
    all**. That last is the one worth guarding here: a template named without being applied otherwise links
    and compiles clean and fails only at *read* time, against a document with nothing wrong with it. It is
    conditional, and the condition is `OpenEntry.isFamilyBase`: a **family base** — a record template — may be named
    bare (`use => { u: box }` resolves to `box`, and a value there is a value of one of its instantiations), while
    a container, a reference and a constructor-application template have no dispatch to eliminate their parameters,
    and are refused (§5.10). *Parameter usage*: an open entry references
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
  - **`entryOrigins` is on `TsonLinkedSchema`, not on `TsonSchema` or an entry's value**, because it is a
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
- **The base cases, and nothing else**: a field that admits `_` or may be omitted with no value of its own, an
  optional tuple position, a container whose `min_items` is zero or absent, and a choice variant that does not
  recur. `void`'s only value is `_`, so a `void` field that does not admit `_` is stated by no document:
  `a: void` empties its record, where `a?: void` empties only the field (`TypeInhabitance.satisfiable`). A
  choice is the one place the walk **branches** rather than conjoins — one good variant is enough, where a
  product needs every part.
  - **Field groups are walked separately**, because §5.11 makes their members uniformly optional in `fields`
    with the requirement carried by the group's own state — reading the field list alone would find nothing
    required and call every group satisfied.
  - **Every other field counts, one carrying a value included**: a fixed or default value of a type nothing
    can satisfy does not exist either.
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
- **The FINAL check reads the entry's chain**, a template's as a closed type's: a template composing or refining a
  final record is refused at its own declaration, whether or not anything applies it, since it is IS-A its parents
  and so is every application. §5.9's subtraction leaves the chain empty, minting no IS-A edge — which is the
  only thing FINAL constrains — so subtracting from a final type is admitted. No *invalid* case would show a
  regression there; the corpus carries a `valid` vector for exactly that reason.
- **A marked field is the declaration's own only if no sealed supertype declares it.** §5.8 flattens an
  inherited field whole, the mark included, so a subtype's copy of its base's selector is indistinguishable
  here from one the subtype wrote. Without the distinction every subtype of every sealed family would be read
  as declaring a family of its own.
- **Every family in the closure is judged**, not only one with a part declared here. §3.3.4 makes `subtypes`
  open across schemas, so an importer can add a member that collides with an imported one, and two imports
  that each link cleanly can each add a member that collides with the other's. The second is the merge's own
  fault and is reported against the schema (`""`), naming both origins. A collision inside one import cannot
  reach here: that import was refused when it linked, so judging the whole closure never re-reports one.
- **Extending a family is a declaration.** A record a use-site template application minted that composes onto
  another (`k: dog_of<text>`, or `dog_of<T>` in a template body) is refused, reported at the declaration that
  wrote it (`reportedAgainst`). A member is what a read reports, a tag names and a binding maps, all by name,
  and a minted name is unwritable (§8.2). It is also what keeps the content-derived duplicates of §8.2 harmless:
  two schemas minting one form hold two entries no value can tell apart, and with members declared, no duplicate
  is ever a family member, so none can collide. A minted member is skipped by the collision check, so one
  mistake gets one verdict.
- **Pins compare as values through `ValueIdentity`**, which is why that class is visible outside its own
  package. §4.3 makes `= 255` and `= 0xFF` one pin and §5.5 makes `= 1` and `= 1.0` one, so a comparison of
  tokens accepts a schema whose dispatch table is not a function. They are read **at the base's declared
  type**, which is [TSON-JSON] §6.1.5's dispatch rule: those are the types known before a subtype is
  selected, so distinctness is judged in the terms a decoder will compare in.
- **Group membership is reported instead of the state rule, not beside it.** §5.11 forces a member OPTIONAL,
  so both would fire and the second would name the symptom. One mistake, one verdict.

## What an enum's `name_type` obliges (`EnumLabels`, §7.4)

An enum is an instance of `enum_type` or a tightening of it (`enum` pins `name_type: identifier`), and `EnumBody` holds
`name_type` as a name beside members held as text. What the name denotes — which family, which parser, which equality,
which profile — lives in a namespace the body cannot see, so the checks are the linker's: `name_type` names an
identifier family (an atom-family instance whose constructor IS-A `identifier_type`, one hop through the constructor its body
applies), every
member parses as a value of it (`AtomParsers`), and no two members are one value (`ValueIdentity`). `checkNames` then
judges the members by §8.2's per-name rules under that type's profile (`NameType.profile`), whose added characters —
a `medial: " "` among them — are its own. A closed set of text that is no name is `text_type`'s `members` facet, an
ordinary text atom, string-class like any other (`design/choice-disjointness.md`).

- **Two namespaces, by who wrote the value.** An author-written `name_type` resolves in the schema's own namespace, so an
  ordinary schema enumerates a vocabulary it declares (`!enum_type { name_type: kebab … }`). A value the constructor pinned
  was written in the governing meta and resolves there **first**, so `!enum [A B]` names the kernel's `identifier`
  whatever the schema declares — a schema's own `identifier` is a different type and does not displace it (core
  declares none, leaving the name free). Pinned is found through the constructor the entry's value applies (its recorded
  tag, `TsonSchemaLinker.constructorOf`), and through a template's held body to the constructor it applies:
  `named => names<c>` is built by `names`, not `enum`.
- **Before the name checks, and one verdict.** A member that is not a value of `name_type` is the more basic error, and
  the per-name rules would otherwise report it as a restricted character; an enum refused here is not judged by name.
- **An unresolved `name_type`** is judged under the kernel's identifier profile — the stricter reading — and is
  reported on its own.
- **The form is recorded, because no reader can recompute it.** An enum matches its members in its name type's
  `normalization` ([TSON-SCHEMA] §5.5). The readers hold only `TsonSchema.entries()`, where a pinned `name_type` may
  not resolve, so the linker records each enum whose form is not `NONE` in `TsonLinkedSchema.enumForms`
  (`EnumLabels.labelForm`). Each enum is judged in the schema that declares it, and an import's map is merged with its
  entries, as `entryOrigins` is. Every site that builds an enum parser passes the form to `AtomParsers.forType(body,
  form)`; the linker's own default and pin checks ask `labelForm` or the map directly. Where `name_type` resolves is
  `resolver.NameType`'s, for enums and records alike, shared with materialisation, which checks a template's value
  argument of an enum type before linking has recorded anything.
- **A record's `name_type` is the same contract over its field names** (SPEC-FEEDBACK.md #10). `FieldNames` is
  `EnumLabels`' peer: the type must be an identifier family, every name the record states — in `fields`,
  `discriminators` and its groups — must be a value of it, and no two fields may be one value under its equality. Both
  checkers refuse a type whose `normalization` does not imply NFC (`NameType.formRefusal`): a reader matches names in
  the type's form, and under `NONE` two canonically equivalent spellings would be two names. An absent name type is the
  kernel's `field_name`, resolved in the structure namespace; one the record states was written by its author and
  resolves in the schema's own namespace first, as an enum's does (`resolver.NameType`), so `RecordBody.nameType` is
  optional and keeps the two apart; where no kernel is in scope, the identifier grammar the default stands for applies.
  A refused record's names are not judged again by the per-name rules, and those rules run under the type's own profile.
  A record with supertypes takes their one type — the default is a type like any other, set where a record is declared —
  refuses two that disagree, and refuses a stated type that is not theirs — two being one when their reference chains
  end at one entry (`sameNameType`), so an alias is its target and `field_name` is `identifier`; a refinement keeps its
  source's. The rule is composition's (`resolveComposition`) — a declaration never writes a chain (§8.1), so an explicit
  `!record { … }` has no supertypes to take one from — which is why the kernel's `name_type` has no `~` default. That rule
  is also how a brace form states one: a fieldless ABSTRACT base carries the type, and the records that compose it take
  it; there is no brace-form syntax for it. A schema's own references to a field — a group member, a refinement's or
  removal's restated field, a discriminator — match its declared spelling exactly, under a folding type too; only a
  document's names are matched in the type's form. Each record with a type other than the default is recorded in
  `TsonLinkedSchema.nameTypes`, with the definition it resolves to, merged through imports like `enumForms`, because its
  readers judge a document's names by it and cannot resolve it themselves.
- **A duplicate field is refused by whichever layer can see it, and only by that one** (SPEC-FEEDBACK.md #12).
  `record.fields` is a map keyed by the name (`field_map`, `ordered: true`, keys `text`), so one spelling written
  twice in the explicit `!record { fields: { … } }` form is the map's duplicate key: `DUPLICATE_MAP_KEY` where the
  form is read as data, and that refusal restated against the declaration where a schema writes it. The brace form's
  repeat is the resolver's, which says whether a field or a group member repeated it. What no map can see — two
  spellings that are one value of the record's name type, `name` and `NAME` under a case fold — is `FieldNames`',
  since only a resolved name type has an equality to ask. The key type is `text` rather than `field_name` for the
  reason `discriminators` is: the record's `name_type`, not the kernel's identifier, decides which names it may
  declare.

## Values by name (`ValuesByName`, SPEC-FEEDBACK.md #13)

A meta layer's `data` body may hold a value written by name — `request: place_order_request` at a `record`-typed
slot, `method: orders.methods.place_order` at a slot typed by the meta's own `method` record. The read keeps the name
as written and records it with the slot it fills (`design/record-dispatch.md`); `SchemaResolver` keeps that record
per entry, `TsonSchema.valuesByName`, so what is judged is what was read and nothing the host class declares
(`design/meta-layer-data-kind.md`). This is the lookup and the judgement, run by `checkValuesByName` over local
entries after `validateEntry`, so a body's unresolved `type_ref` is reported first and on its own. A by-name
reference the class also returns from `references()` is skipped there, matched by identity: it is judged by its slot
here, never resolved as a type, so which list a host files it in changes nothing.

- **The name is a `TypeRef`, and its grammar is judged here.** A value by name is held as every reference is, a
  `TypeRef` whose `name` is the token as written; `ValuesByName.segments` splits it at each `.` and requires each
  segment to be a [TSON-DATA] §7.7 identifier, none empty, and no arguments, refusing `orders.1x`, `.orders` and
  `orders.` as no qualified name before anything is looked up. At a `type_ref` slot the same host shape is one
  segment naming a type, judged by `validateTypeRef` as always: `type_ref` is `ref<type>`.
- **The slot's type is the meta's, the name is the schema's.** The slot's declared type is read off the entry's
  constructor (`TsonSchemaLinker.constructorOf`, in the governing meta's namespace) by walking the recorded path
  through the resolved fields: a field name at a record, any key at a map, any index at an array. A path through
  anything else (a choice) is a gap this library has not filled (`UnsupportedOperationException`). The name's first
  segment resolves in `merged` — the schema's own entries and imports — with alias hops followed (§8.3,
  `ReferenceChain`), and never in the meta's namespace (§3.3.2); a member is reached through its entry, so `method:
  place_order` is unresolved even though `orders` holds a key of that name. Nothing is looked up lexically, and nothing
  is a choice among candidates: found first, judged after.
- **Each further segment selects in the value reached**, never in a declaration: a field of a record (read through
  the bound body's record components, named as binding names them, `Optional` unwrapped), then a key of a map. A
  type entry admits no further segment (`order.sku` is refused; projecting into a record type's fields is a
  different capability, left with `discriminators` and `scope`), an open entry is no referent (a template denotes
  no value until applied), and a list has no named members (`…errors.x` is refused: a segment never selects a
  position). A map is entered only where its key type is an
  identifier family or an enum, whose keys are names; a `text`-keyed map cannot be entered. A body whose class is no
  Java record cannot be entered at all, which is this library's gap (`UnsupportedOperationException`). **Entering a
  data body is interim**: `data` is opaque to the type system and the host's to interpret, so a segment selects in
  the host's object and nothing in the schema walks the body's values; the namespace kind replaces the hop.
- **The judgement is §7.2's IS-A between two types, by canonical identity.** An entry's declared type is the
  constructor that built its body — its recorded constructor tag where that is a constructor of the *declaring*
  schema's meta (IS-A `top`), otherwise the kernel constructor its body's class mirrors (`@Typename`): a record
  declaration and a composition are built by `record`, `uuid` by `uuid_type`. A field's declared type is its `type`, a
  map value's the map's `value_type`. Each type is named in the namespace of the meta layer that governs the schema
  declaring it — for an imported referent, the import's own meta, found through the loader — and two types are one when their
  terminal entries share origin and name; a type IS-A another when it is that one or lists it among its resolved
  `supertypes`. So `record` written in the meta and the `record` that built `place_order_request` meet as one kernel
  entry, and `uuid_type` meets nothing a `record` slot accepts.
- **Each refusal is its own `SCHEMA_ERROR`, naming the step**, against the entry holding the name, at the slot's
  RFC 6901 pointer: no entry for the first segment; an entry (after any alias hop) built by another constructor; a
  segment that is no field (listing the fields declared) or no key (listing the keys held); a selected value of
  another type; a type entry given a further segment; an open entry; a segment into a list; a map whose keys are not
  names. §4.1's refusal of a DATA entry
  at a type position is unchanged and not this pass's: `response: orders` at a `type_ref` slot is a reference, and
  `validateTypeRef` refuses it.
- **Local entries only.** An imported body's names were judged when its own schema linked, in its own namespace; a
  second judgement here would be in the wrong one.
