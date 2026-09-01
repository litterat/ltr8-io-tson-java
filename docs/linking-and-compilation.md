# Linking, registration, and Class 2 compilation

Design notes for the back half of the schema pipeline: the registry and identity machinery, the linker,
Class 2 compilation, and the two compiled-side registries. Current form only; history lives in git.
`CLAUDE.md` holds the one-paragraph orientation; this file holds the detail.

## Schema registry and linking (`tson-compiler/TsonSchemaLinker.java`, `tson-schema/.../`, `.../registry/`)

Resolution handles one declaration at a time (references carried as unverified strings, `!!import` not
consulted). `TsonSchemaLinker`/`TsonSchemaRegistry` add the second stage. **They sit in different modules on
purpose:** the linker is a pipeline stage and lives in `tson-compiler` alongside parse/desugar/resolve/compile,
so every phase that will grow schema-side diagnostics is in one module with `Diagnostic`, and it can reach
`tson-regex` directly (what §5.4 pattern disjointness needs, with no injected-oracle seam); the registry is
storage over the `schema.meta` value model and stays in `tson-schema`, the leaf everything else depends on.

- **`TsonCanonicalIdentity.canonicalize(String)`** implements §2.2.1's canonical-identity algorithm — **not**
  general URI normalization. Exactly two reductions (strip scheme + `://`, strip query); everything else must
  already be canonical (lowercase host, no port, no dot-segments, no fragment, no percent-encoding of
  unreserved chars) or it's rejected. `http://` and `https://` resolve to the same identity; a `?sha256=`
  query is dropped, not validated. Two companions: `validate` runs the same checks and discards the result
  (so a caller checking a candidate `!!id` up front reads as such), and `sameIdentity(a, b)` canonicalizes
  both and compares — the recurring question, since a pin or a scheme never distinguishes two references.
  **Public API, not internal machinery**: `TsonSchemaLoader.load` takes a canonical identity as its
  argument, so anything implementing that seam or a `TsonSchemaSource` has to derive them the same way. It
  is the identity half of §2.2.1; `TsonContentHash` is the `?sha256=` half this one strips. Prefixed for
  the reason `TsonContentHash` is — a consumer plausibly has their own `CanonicalIdentity`.
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
  closure reaching both `/2026/32/m/core.tn` and `/2026/34/m/core.tn` is rejected here rather than surfacing
  later as a field conflict between two identically-spelled types; (2) **populate `subtypes`**
  (reverse of `supertypes`); (3) **derive `disjoint`** for every choice entry (`ChoiceDisjointness`, §5.4) —
  total and two-valued, detailed under "The disjointness derivation" below, so a linked choice always
  carries the fact;
  (4) **validate** every reference
  resolves, with a type-parameter exception (a bare name valid if it's the entry's own declared parameter);
  **a reference to a DATA-kinded entry is refused** — §8.1's schema map holds only type definitions, so an
  entry describing something other than a data value has no way to say "declare me, but let nothing name me
  as a type"; the `Data` body *is* that way, and the check applies at every position a type-ref occupies (a
  field type, a choice variant, an array element, a map value). Without it the misuse resolves, links **and**
  compiles, and fails only when a document is finally read against it (§4.1 makes naming one where a type is
  expected a resolver error). A DATA
  entry's own references are validated too, and it is the body that says which they are — see the `Data`
  note under compilation below;
  **a choice's variants are checked distinct** (§5.4) *after* §8.3 flattening, since an alias and its target
  are one type — so `(text | my_text)` with `my_text => text` is caught, which comparing the written names
  would miss and which is the only spelling an author can't see for themselves; the walk stops on a
  reference cycle rather than hanging, and an alias cycle is then caught by the inhabitance check below; **an author's
  `@disjoint` marker is checked against the derived fact** (§5.4) — `true` verifies it silently, `false` is
  an error, and there is no third outcome because §5.4's derivation is total. There is no unprovable state
  to warn about, and no severity axis to warn on: §8.1 states that a conforming processor has one.
  The marker is read from both places §6 puts it,
  the definition and the map key, which is why the check runs last, after `withNameAnnotations`;
  and a **constructor-eligibility** check with two halves, the same §2.2.2 question asked from both ends
  (§2.2.2, §4.2): a locally-declared `constructor: true` entry is valid only if the schema's
  `!!meta` is exactly meta-kernel's identity, and a schema named as this one's **`!!meta` target** is valid
  only if *its* `!!meta` is — so an ordinary type library can't govern (naming core.tn as `!!meta` is the
  `!!import` confusion, and core.tn declares no constructors to supply). The target half is judged only when
  the loader actually produced the target; an unresolvable `!!meta` is left to whoever owns fetching, which
  is also what keeps meta-kernel's self-naming `!!meta` linkable mid-registration. **The declaring half is a
  lint, not a guard**: a `~` in a user schema is *inert*, because `constructor: true` is read in exactly one
  place — resolving a `!C value` against the **governing meta's** entries — and the target half already
  refuses to let a user-level schema be named as anyone's `!!meta`, on the same predicate. Nothing can chain
  to it, so no `!xxx_type` can ever occupy a schema position, so the flag is never consulted. (In a *data*
  document `!xxx_type { ... }` is an ordinary record annotation and reads fine, which is what makes §8
  resolver-output bodies like `!record { ... }` expressible at all.) Worth keeping anyway, at one comparison:
  "you wrote something that can never do anything" is better said at the `~` than in whichever document later
  tries to name the schema as its `!!meta`. In the shipped wiring
  `TsonCompiledMetaRegistry.loadMeta` reaches that verdict a phase earlier (it must *compile* the meta to
  resolve against it) and raises the linker's own `TsonSchemaLinker.notAMetaSchema` — one wording, one module,
  and a **`TsonSchemaValidationException` rather than an `IllegalStateException`**
  because a wrong `!!meta` is an authoring error, not a library fault (which is what lets the CLI keep exit 1
  and exit 70 apart). `source`
  validation additionally falls back to the governing meta's namespace (a `source` naming a constructor is
  one of §3.3.1's constructor roles); no other reference does — **and not a `source` carrying arguments**,
  which is the one shape the fallback would reach past its own justification. Desugar rewrites every
  constructor application long before resolution, so arguments surviving into a `source` mean a §5.10
  user-template head, which §3.3.1 resolves in the type-name namespace only. Without the exclusion
  `x => tmpl<text>` against a `tmpl` its governing meta declares found the template through the fallback and
  then faulted it on *arity* — telling the author to supply arguments they had written, or that they had
  written the wrong number of them, when the real answer is the one every other reference form gives: the
  name is not in scope. Its other half is in `TemplateMaterialiser` (`docs/schema-resolution.md`): an
  application that cannot be closed keeps its argument list rather than collapsing to its bare head, so what
  the linker judges is what the author wrote. **The linker does not materialize anything** —
  `SchemaDesugarer` already turned every sugar form into a real declaration, one phase earlier and in the
  module that can bind a constructor generically. The only argument-bearing `type_ref` it ever sees is inside
  a template declaration, which the desugar phase passes through whole (`box<T>` in `box`'s own body), and
  which is validated, not rewritten.
  - **Two §5.10 rules on templates, both decidable here and neither depending on anyone applying one.**
    *Arity*, over every reference: a reference supplies exactly as many arguments as the entry it names
    declares parameters, which folds three author errors into one rule — too many, too few, and **none at
    all**. That last is the one that mattered: naming a template without applying it (`use => { u: box }`)
    linked and compiled clean, then failed at *read* time with "no usable compiled reader" and a
    library-fault exit code, because the eager-rejection discipline guarded applications and never bare
    names. *Parameter usage*: an open entry references every parameter it declares, so
    `box => <T> { v: text }` is rejected — every application of it would denote the same type, and a
    parameter list is author-written, so an unused one is a `TsonSchemaValidationException`.
    - Its old converse — §5.10's closed-entry rule, checked over `record_field.value_param` — has no sound
      form now the kernel declares one `value` slot: at a closed entry there are no parameters for a token to
      resolve into, so a token there *is* a literal and there is nothing to detect. The rule's reference half
      needs no code either — a parameter reference at a closed entry is already an unresolved one.
  - **A held body answers the arity rule for the *applications* it writes** (`checkHeldArity`). A held body
    withholds one thing — what a reference *resolves to*, which no argument settles until substitution — so
    type-kind validation and inhabitance wait for materialisation. Arity does not depend on that: it counts
    parameters the *referenced* entry declares. And nothing ever closes `chain => <T> { tail: chain<T, T>? }`,
    so deferring it would let that template ship with the mistake in it.
    - **Applications only, never bare names**, and the distinction is load-bearing.
      `TemplateBody.applications()` returns a shape nothing else in the wire tree shares;
      `TemplateBody.names()` returns *every* token — field names, states, literals and type references alike.
      Asking the zero-argument half ("this token names an unapplied template") off `names()` rejects a
      correct schema whose field happens to be called `box` beside a template of that name, which is a worse
      failure than a late verdict. So that half runs on the entry materialisation mints, and an unapplied
      template gets no verdict — the open form's own position (§5.10: "an unapplied template is checked no
      further and receives no verdict"), not a shortfall.
  - **`entryOrigins` is on `TsonLinkedSchema`, not on `TsonSchema` or `TypeDefinition`**, because it is a
    fact *linking* establishes rather than part of the resolved schema value §9 defines — and because
    `schema.meta` is a bind target with a hand-written `equals` and the `@Record` constructor-selection trap,
    which a new component would walk straight into. It keeps a declaration's identity and its line answerable
    from the same document however many schemas flattened it in — the pair a non-record reader offers as its
    own location (`ValueReaderContext.locationOf`), which is what locates a root-level `!int32` in core.tn
    rather than in whatever schema imported it. The registry stores `TsonLinkedSchema` directly, so the map
    survives registration and every later `load`.
- **`TsonSchemaRegistry.register(TsonLinkedSchema)`** computes canonical identity from `!!id`, rejects a
  duplicate identity (no overwrite — this plus `entries()` being unmodifiable *is* the "locked" guarantee)
  and any self-referential `bootstrap()==true` schema, and stores it. `get(uri)` canonicalizes internally.
  `TsonSchemaLoader` (`Optional<TsonLinkedSchema> load(id)`) is the pluggable import/meta lookup hook,
  registered-only by default (nothing fetched). `TsonSchemaLinker.linkBootstrap` is the one sanctioned way
  to link meta-kernel's raw bootstrap output without registering it.

## `Data`: an entry that is not a type (`schema.meta.Data`, §4.1's `data` base kind)

§2.2.2 calls the meta layer the format's sanctioned extension point, and a meta-schema may declare
constructors of its own. What the kernel had no answer for is where an *instance* of such a constructor
lands when the thing it describes is not a data type — `schema => {type_name => type_definition}` makes
every schema-map entry a type definition. `data => top & {}` is the fourth base kind that lets one say
otherwise, and `TypeKind.DATA` is what it resolves to. The motivating case is an HTTP operation, which must
sit at the schema layer because that is the only layer able to name request and response types *by name*.

- **`Data` is the one open branch of `Top`.** Every other branch is sealed all the way down: each leaf
  mirrors one kernel constructor, so a body's kind is decidable by inspection and each switch over them is
  exhaustive. This one is `non-sealed`, because the constructors reaching it are declared by meta-schemas
  this library has never seen and their bodies are the consumer's own classes.
- **The registration is a `@Typename` and a name binder, and nothing else.** A class carries
  `@Typename(name = "operation")` and implements `Data`; the `DataBindContext`'s `DataNameBinder` has to be
  able to find it. A consumer composes rather than copies — `SchemaMetaNameBinder.extendedWith(theirs)` asks
  the kernel's own vocabulary first and theirs only for a name it does not know, so the kernel's table is
  never duplicated and nothing shadows it. `contextExtendedWith` is that binder in a ready-made context, and
  `TsonConfig.metaNameBinder` is the same seam through the front door — the resolution core's *mode* is
  fixed (bind, always), which names it knows is not. **No
  reader family and no `ValueReaderFactoryRegistry` entry**: the ordinary record reader binds the
  `!operation { ... }` payload straight into the record, so §7.2 closure, field states and every atom
  constraint in the constructor's declaration are enforced exactly as for a written body.
- **A constructor with no resolvable class is an error where it is written**, not a value carried in some
  generic form. A schema asserting structure nothing can interpret is worth failing on.
- **`Data.references()` is how a body's own references reach the linker**, and it is *declared, not
  discovered*: a payload's Java shape says nothing about which components are references, and consulting the
  constructor's declaration would only work for slots spelled `type_ref`. A body holding a `TypeRef` returns
  it and the name is checked against the same namespace every other reference is. A body that declares none
  simply has none checked — that is the cost of the branch being open, and it is the reason to type a
  reference slot `type_ref` rather than `type_name` (§5.6's positional form keeps the author writing a bare
  name either way).
- **The silent defaults are worth knowing.** `TypeInhabitance` calls a `Data` body inhabited and
  `DiscriminationClass` gives it none, both by their `default` arm. Neither matters while the linker refuses
  to let anything name such an entry as a type — which is what makes that refusal load-bearing rather than
  a nicety.
- **Resolved output is ordinary.** §8.1's `body` carries an instance of whichever constructor built the
  entry, and a meta-schema's own constructor is not a special case: a DATA entry writes as
  `body: !operation { ... }`, formally indistinguishable from `!record { ... }`. What made that work is a
  general `tson-bind` fix (#121) — a non-sealed union branch now stands for its own implementations, where
  exact-class membership never matched them.
- **A meta-schema keeps a constructor this library cannot build a reader for**, its factory standing in as
  an `ErrorReader` carrying the real cause. Dropping it — which `TsonCompiledMetaSchema` used to do — lost
  the constructor from the scoped vocabulary silently, so a governing meta compiled and registered looking
  healthy and the complaint landed against a *different* document: the first governed schema to apply it was
  told the meta-schema does not declare it, which is both false and unactionable. Same treatment
  `extern`/`unknown_type` already get, and the reason those register a factory rather than throwing.

- **A meta layer is not a vocabulary channel, and this is the first thing an author tries.** The instinct on
  declaring `operation` in a meta layer is to put the shared types beside it — a `status_code` atom, an
  envelope template — and let the governed schemas name them. They cannot: `!!meta` says where this schema's
  *constructors* come from and merges nothing into the type-name namespace (§3.3.2), so every such name is an
  unresolved reference in a schema the layer governs, whether it is written bare, in a field, or applied with
  its arguments. **Shared vocabulary goes in a third ordinary schema**, `!!import`ed by whoever needs it,
  including the meta layer itself if it needs it too. Nor is `!!import`ing the meta layer a way round it: a
  layer chaining to meta-kernel imports meta.tn, which imports meta-kernel, and imports are transitive here
  (§2.2.3), so meta-kernel's `void` arrives alongside core.tn's and collides — correctly, and
  with a diagnostic naming both origins. The constraint is real and worth stating; what is not acceptable is
  discovering it through a message about the wrong thing, which is what the `source` fallback's
  argument-bearing case above used to give.

**`spec/m/` is a cache of the spec, with one difference: the hash pins.** The published drafts spell them
`xxhash` and compute real digests at publication, so these copies carry digests over their own bytes and
`TsonBundledSchemas` holds those rather than tson.io's.

## The inhabitance check (`TypeInhabitance`, §5.10.1's productivity rule)

**An entry no finite document can satisfy is rejected.** `x => { y: y }` with `y => { x: x }` resolves and
links cleanly otherwise, and fails at the first document as `missing required field 'x'` — blaming the data
for a defect in the schema, at a line the data's author does not control.

- **A least fixed point over the entry graph, not a search.** Every entry starts unknown; a round marks each
  one whose body is satisfied by what is already marked; rounds repeat until nothing changes, which takes at
  most one round per entry. Decidable because the graph is finite — the question is not "does this type have
  a value" but "does this recursion reach a base case".
- **Exact, total and two-valued.** The sibling derivation below had to give up exactness to stay total; this
  needs no such trade, and there is no third answer to report.
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
  but that is its constraint family's question, next to `AtomNarrowing` (`BACKLOG.md`).

## The disjointness derivation (`ChoiceDisjointness`, `reader/DiscriminationClass`)

`ChoiceDisjointness.derive` decides §5.4's question for a choice **totally and two-valued**: `disjoint` is
`true` exactly when every variant has a *discrimination class* and no class appears twice, `false`
otherwise — never absent, and §5.4 asks for exactly this: "a resolver MUST record exactly this — it MUST
NOT prove more ... or less". The question the fact exists to answer is not "do the value sets intersect" but
"can an encoding's single form-resolution pass tell the variants apart", and *that* is a total function of
the declarations. A value-set prover (interval algebra, exact I-Regexp intersection-emptiness, record
closure) was built and discarded during PR #36's review: it answered questions no conforming reader may
act on — separating same-class variants takes the type-directed second inspection [TSON-DATA] §2.4
forbids — at the price of verdicts an author cannot predict (two default-`allow_nan` floats overlap via
NaN however far apart their ranges sit) and a conformance bar no second implementation should have to
match.

**And the restriction level is refused per name, in the same pass** (`TsonUnicodePolicy`, UTS #39 §5.2). The two
are complementary rather than overlapping: the confusable check is a *relation* and needs the whole set, so
it can never fire on a lone name; the level is a *property* of one name, so it is what reaches a name nothing
else in the schema resembles. Configured by `TsonConfig.identifierPolicy` and carried on
`TsonCompiledMetaRegistry`, which is the one object every resolve and every read already passes through.
**Two axes, not a ladder** — a level and a unit — because per-segment Highly Restrictive and Moderately
Restrictive are incomparable. The default is Highly Restrictive over a whole name, which refuses
`id_пользователя`; the relaxation to reach for is the *unit*, since `perSegment()` admits that and still
refuses `аdmin`. The bootstrap links under the default rather than under a caller's policy: a configuration
should not be able to break meta-kernel.

**All three of §8.2's name-hygiene rules run in one walk, over scopes** (`checkNames`) — names that read
alike, a character outside the identifier profile, and a script combination the restriction level does not
admit. The scopes are §11.4's — the
merged namespace, which is where §2.2.3's own disjointness rule is exact equality and a confusable pair passes
it by construction; each entry's record field names, its groups' member labels arriving flattened among them;
and its enum members — plus **a template's parameters, which §11.4 does not list** (`SPEC-FEEDBACK.md` #5:
`<T, Т>` otherwise declares two parameters that render identically, and a body referencing `T` binds one of
them with nothing in the source to say which). A **choice's variants are deliberately not checked**: a variant
is a reference to a declared name, so a confusable pair is already two confusable namespace entries and a check
there could never fire.

**A minted name is ASCII and an identifier by construction, which is what lets the walk judge it like any
other** (`InternalName`). Both naming sites splice author-written content into the readable half —
`SchemaDesugarer` from a lifted binding record, `TemplateMaterialiser` from an application's head and value
arguments — so a derived name is a place where a document's own text reaches the schema namespace. Two
requirements meet there, and the second is why meeting the first is not enough:

- **[TSON-SCHEMA] §8.2's freshness MUST**, that an internal name is a valid `identifier`. Splicing raw text
  broke it outright: a `text` field holding a path put `/` in a name, and §7.7 admits only `XID_Continue` and
  `-`. An HTTP operation is the case that finds it — §4.1 names one as the motivating case for the `data`
  kind, and every realistic path carries a slash.
- **§8.2's hygiene must still be able to judge the result.** Admitting every `XID_Continue` character keeps
  the name legal and still lets author text shape it: a Cyrillic `о` in a value would sit in a namespace
  name, and a Latin head spliced with non-Latin content is mixed-script by construction, so the walk would
  refuse ordinary schemas — `operation_путь_GET_…_bef13f0c` is a valid identifier refused under §8.2's
  recommended default for containing a Russian word. Exempting minted names from the walk answers that and
  opens a worse hole: the namespace then takes on whatever a document happens to contain, unchecked.

So the rule is ASCII, in three cases. What is ASCII and admitted by §7.7 is spliced verbatim — the ordinary
case, a type name or a verb or a bound. What is ASCII but not admitted keeps its admitted characters and
gains a hash, so `"/x"` reads `x_h00000f2f` and `1.0` reads `1_0_h0000bdb3`. Anything else is the hash alone.
**Hashed rather than dropped**, because replacing it would collapse two different values onto one readable
half, where a hash keeps them visibly distinct and keeps the name inspectable — a reader holding the schema
can hash the same text and match it. Nothing identity depends on is at stake either way: that is the
structural hash at the end, computed over the binding and never over this text.

**A part is capped at 64 characters, hash included.** Nothing in the series bounds a name — §8.2 asks for
freshness, stability and a content-derived spelling, and §7.7's grammar is unbounded — but a part is spliced
from author-written content and `derivedName` walks a whole binding record, nested records and arrays
included, so an unbounded rule makes name length a function of document size: a realistic REST path already
mints 139 characters. Past the budget the readable half has stopped being readable and is only cost, at every
reference to the entry and in §8 output. Truncation appends the hash rather than simply cutting, so two long
texts sharing a prefix stay apart.

**Non-collision is decided, not assumed** (`MintedNames`). §8.2's freshness MUST asks that an internal name
collide with no declared entry and no other internal one. The first half was always caught — `SchemaDesugarer`
inserts into the document's declarations with `putIfAbsent` and raises `IllegalStateException`. The second
half is the subtle one: **deduping by name is the identity discipline working**, since two occurrences of one
form must land on one entry — it is what makes `[text]` written twice one type, what lets a form written out
and the same form arriving through a template agree, and what ties a recursive template's knot — and it is
therefore also what would hide a collision, a second arrival under a name being indistinguishable from two
different bindings that derived one.

So the derivations are compared. Both sites already render a binding canonically before hashing it, and that
rendering is injective — two are equal exactly when the bindings are — so `MintedNames.claim` states the MUST
exactly rather than trusting the name's own 32-bit hash, which is a rendering and was never load-bearing on
its own. **Per phase**: desugaring and materialisation hold one each and run either side of resolution, so a
name minted in one phase colliding with a different form in the other is not caught; the two share their
naming functions, so such a pair would have to have collided within a phase as well to exist.

**One walk rather than one check per naming position, and that is the load-bearing part.** The
restricted-character rule
(`Identifier_Status`) used to run where a name is *read* — the schema parser, `DefinitionResolver`, the atom
vocabulary — by three exceptions and three codes, and had holes at exactly the positions only some of those
reached: an enum member and a group's member labels were checked for reading alike and for script mixing,
and never for a restricted character, invisibly. A scope list
can be reviewed; three call sites cannot. What stays at the reading positions is §7.7's grammar
(`IdentifierParser.validate`), which is validity, is stable across Unicode versions, and really is a parse
error; `IdentifierParser.hygiene` returns the restricted-character rule's verdict rather than throwing,
because a refusal is not
one.

**A refusal carries a policy code, not `SCHEMA_ERROR`** (`Diagnostic.ofSchemaRefusal`): `CONFUSABLE_NAMES`
for names that read alike, `RESTRICTED_CHARACTER` for a character outside the identifier profile and
`RESTRICTED_SCRIPT` for a script the restriction level does not admit — one per rule, and the same codes
a *read* reports for the same rules, so one schema and one document that break the same rule come back
alike. §8.2 requires a refusal be distinguishable from a
validity error, and a consumer that has to read prose to tell them apart is what the code exists to prevent.
It is still a verdict: the schema must change, or the deployment must relax the policy in code.

The one scope the linker cannot reach is a Class 1 record, which has no declaration; `SchemalessTreeReader`
checks its own field set, and `DefaultTsonReadContext` applies the restricted-character and
restricted-script rules to a type-ref or annotation name. Being a *relation*, the look-alike rule never
rejects a lone name — a mixed-script `id_пользователя` collides
with nothing and passes, which is the property that keeps it switched on and the reason [TSON-DATA] §8.2
defaults it on where the restricted-script rule's level is the one to relax.

**§7.2's subsumption guard wraps every entry the rule governs** (`Subsumption`, applied at
`TsonSchemaCompiler`'s single `build` site). At a position typed `T`, a value annotated `!S` is valid iff
`S` is `T` or `T` is in `S`'s supertypes — and that was enforced only where `T` was a record with a
non-empty `subtypes()`, the one case that got a `VariantSchemaReader`. Every atom, array, map, tuple, and
every record whose type had no subtype, consumed the type-ref and discarded it, so a document could claim
any type at those positions. The guard is the same `VariantSchemaReader`, now wired wherever the rule
applies. Three things it has to get right, each a real bug found while wiring it: it **follows the body, not
`kind()`** (a hand-built entry can carry a `ChoiceBody` under `PRODUCT`, and choices and externs have their
own membership relations §7.2 excludes); it accepts an entry's **aliases as the entry itself**, since §7.2
compares "after reference flattening of both" and resolving an alias would arrive back at the same reader
and recurse; and it is **transparent to `UseSite` renaming and to bind-mode container rebinding**, both of
which look at the reader it wraps — the first or a diagnostic names the entry instead of the author's
alias, the second or a bound `Map` field silently loses its rebinding.

**The class table** (`DiscriminationClass`, in `reader/` because untagged recovery dispatches on it):
§4's four scalar classes — `null`, `boolean`, `number` (every numeric family: an `integer` and a `decimal`
are one class, so never disjoint), `string` (every text-form family: `text`, enums by their members' shared
class — so `[true false]` is boolean-class — `uuid`, `date`, `binary`, …) — plus `brace` (records **and**
maps: both are `{...}` and `{}` is ambiguous between them, so calling them distinct would promise a
discrimination the wire can't deliver) and `bracket` (arrays and tuples). A variant classifies through its
§8.3 reference chain (an alias is its target; a cycle has no terminal, so no class). No class at all —
`rational`/`complex` (whose typed forms straddle classes), `unit`, a mixed-class enum, `unknown`, a nested
choice, an extern, an unresolved name — makes the choice `false`, the conservative side. A `void` variant
never even gets that far: the linker rejects the declaration outright (`checkVariantsAreNotVoid`, after
§8.3 flattening) — `(T | void)` confuses optionality with choice, which belongs to the position (`?`, `_`),
per §5.4's "a variant MUST NOT resolve to `void`", judged after §8.3 flattening as it is here.

**`disjoint` ⇔ the tag is droppable — one fact, not two.** `ChoiceReader.untaggedRecovery` builds its
`class → variant` dispatch map through the same `DiscriminationClass.of` the derivation classifies with,
so the derived fact and the reader's separability can never disagree — one fact, not a derivation and a
dispatch rule held carefully in step. Recovery still engages only when every class is *scalar* —
a `brace`/`bracket` variant is honestly disjoint from a scalar, but recovery dispatches on a token's
resolved class and structural recovery from an opening delimiter isn't attempted yet. **The class table is
pinned twice over**: it decides which schemas load (`@disjoint` on a `false` choice is an error) and which
documents read untagged, so any change to it is a compatibility decision, not a free improvement.

## Class 2 compilation (`tson-compiler/TsonSchemaCompiler.java`, `.../reader/`)

`TsonSchemaCompiler.compile` turns a `TsonLinkedSchema` into a `TsonCompiledSchema` — one `TsonTypeReader`
per entry, wired as real Java references rather than name lookups at read time (except where
`DeferredTypeReader` closes a cycle with one lazy lookup). `TsonTypeReader<T>` is the single-method front
door a caller holds — **strictly one method**, `T read(TsonReadContext)`. Source form, document framing
and error policy are all the context's or the facades' concern, never overloads here.

**"Type", not "value", is the accurate half of that name.** A caller reaches one via
`TsonCompiledSchema.get(typeName)` — it is the reader *for that declared type*, and there is exactly one
per schema entry. What it hands back is mode-dependent (`T` is a `TsonValue` in tree mode, a bound Java
object in bind mode), so naming it for its return type would be wrong in one mode or the other. It also
keeps `TsonValue` free for `tson-tree`'s own root type (`BACKLOG.md`).

- **Eager, not lazy** — `compile` walks and resolves every entry, so a caller reading only a few types
  still gets the assurance that every entry compiles, and a broken entry surfaces at compile time.
- **`CompiledReaders` is the name→reader handle every reader is given, and it is rebound once.** Name lookup
  is needed in two phases with different rightful sources: during the walk only the in-progress `Compilation`
  can answer, but a reader that resolves at *read* time (`NamedDispatchReader`/`VariantSchemaReader`/
  `VariantBindReader` picking a variant; `AnnotationTypes` resolving the type an annotation names) should be
  asking the finished, immutable `TsonCompiledSchema`. Handing readers `Compilation::resolve` directly — a
  *bound* method reference — keeps its mutable `finished`/`building` collections reachable for as long as any
  reader is, contradicting `Compilation`'s own "never escape a single compile invocation" invariant. So
  `compileWith` binds the handle to the compiled schema as its last step, **replacing** the compile-time
  delegate rather than falling back to it, which is what actually makes that invariant true.
  `CompiledReadersTest` pins the handover; a second `bind` is rejected.
- **`ErrorReader` makes eager building survive coverage gaps.** A `RuntimeException` while building one
  entry is caught and substituted with an `ErrorReader` wrapping it — the schema still compiles, only
  *reading* that entry fails, with the original message preserved. Real causes: a constructor with no
  registered factory (the undocumented atom families), or a factory that rejects one entry.
    - **It reports `NOT_IMPLEMENTED` and skips the value**, exactly as `OpenTemplateReader` does for the
      entry it refuses — report before consuming so the position names the value, then `EventSkip.dataValue`
      so the stream stays in step. The code, not the channel, is what says this is a gap rather than a
      verdict, which is the same rule the schema pipeline settled on: throwing instead cost the whole read,
      and in a multi-document `tson validate` the whole envelope, for one unreadable field. `SchemaFailure`
      already classified a *compile* gap met during a read this way, so this was the last one travelling by
      channel. Fail-fast loses nothing — `report` raises `TsonReadException`, which carries the same
      `Diagnostic`, so `e.diagnostic().code()` is the question rather than the exception type.
    - **`TsonMissingBindingException` is the one cause that still throws, unwrapped and in every mode.** It
      is the reading application's own wiring — neither this library's gap nor a problem with the document —
      so it reaches that application as itself. Wrapping it once sent a service's missing configuration out
      as a 501.
  - A referenced-but-absent name is a stricter `TsonSchemaLinker` invariant violation and propagates
    uncaught.
- **An open entry compiles to `OpenTemplateReader`, before its body is looked at at all.** An entry
  declaring type parameters is a template, not a type (§5.10), so there is nothing a value could validate
  against; the reader reports `UNKNOWN_TYPE_REF` against the data and skips the value, like any other reader
  finding data the schema does not admit. Reaching it is **always** a data error: a *schema* naming a
  template without applying it is rejected at link time (`checkArity`'s zero-argument case), so no field,
  element or supertype routes here — only a data type-ref naming the template, `!paged` against `paged =>
  <T> { … }`, which §5.10 makes an ordinary resolver error rather than anything exceptional. Refusing the whole entry is what makes the verdict right: built, a parameterised body either
  reached the parameter (`ErrorReader`, message blaming the linker for a stray `T`) or the lifted open
  synthetic (no factory for an open body), both exiting 70 for a plainly invalid document. The message
  mirrors the linker's schema-side sentence for the same mistake and adds the route — name the application
  in the schema, write that name in the data.
- **`TsonCompiledSchema` is `sealed permits TsonCompiledMetaSchema`.** A meta-layer schema (its `!!meta` is
  meta-kernel) compiles to the `TsonCompiledMetaSchema` subtype — a compiled schema plus its governing
  constructor vocabulary — so it can go on to govern others; every other schema is a bare
  `TsonCompiledSchema`.
- **Two compile modes, both sharing one eager walk:** a **governed** compile (`compile(linked,
  TsonCompiledMetaSchema)`) dispatches each body's constructor scoped through the governing meta
  (`governedFactory`: the meta's declared vocabulary → the constructor the schema declares itself → else
  out of scope, an `IllegalStateException` deferred into an `ErrorReader`); a **standalone** compile
  (`compile(linked, ValueReaderFactoryResolver)`) dispatches through a factory set directly, no scoping —
  for reading an already-validated schema in a chosen mode.
- **Two output modes share each reader family** via a `*AbstractReader` base plus `*TreeReader`/`*BindReader`
  subclasses (`Record`/`Array`/`Map`/`Tuple`). Tree mode produces an immutable `tson-tree` `TsonValue`
  (structure-preserving, typed leaves); object-binding mode produces real bound Java objects via a
  `DataNameBinder` (`RecordBindReader` looks up each entry's `DataClass` and narrows values to the field's
  target type). `ValueReaderFactoryRegistry.tree()` /
  `.bind(DataBindContext)` are the two fixed factory tables; only `record`/`enum` (and, transitively, a
  record's container-typed fields) differ per mode. `ValueReaderFactoryResolver` (the `constructor
  name → factory` dispatch interface) lives in the unexported `reader` package — a consumer picks a mode
  by which registry they hold, never by naming it.

## Untagged labelled choices (`reader/GroupUnionBindReader`)

**A record whose fields form one REQUIRED group, bound onto a Java sealed interface whose members carry those
fields one apiece.** The kernel's `type_argument => { ( name: type_ref | value: value ) }` is the case that
forces it, and it was unreadable until this existed — `RecordBindReader.Factory` refused a union descriptor
for a record body, so no `type_ref` carrying `arguments` could be read at all.

- **The present field is the discriminator**, which is what separates this from `VariantBindReader`. There is
  no `!typeName` to dispatch on, and §5.6 makes one unavailable in principle: the kernel gives this record no
  positional form precisely because a bare token could not say which member it was. So the record is read the
  ordinary way — `RecordAbstractReader` already owns the framing — and the member is chosen by what arrived.
- **Members match fields by the member's own single component wire-name** (`@Field` where present, the
  component name otherwise). A member of a labelled choice carries exactly the field it is the label for, so
  the component *is* the field; matching on anything else would need a second table to keep in step with the
  first. `TypeArgument.Ref` carries `@Field("name")` for this reason, which also brings `toTson` closer to the
  kernel's own spelling.
- **Three conditions are checked, not assumed** — union target, one REQUIRED group covering every field, and
  every member carrying one component named for one of those fields. A near-miss falls through to the
  ordinary record path and is reported there; guessing at a partial match would bind a member to a field it
  does not carry.
- **The group rule is the whole contract**, so `validateGroups` is what guarantees exactly one member arrived
  — an empty record and a two-field one are both reported before anything is constructed.
- **A slot may want the raw token rather than the value it denotes**, and the choice has to be made *before*
  the read: `type_argument`'s value channel is typed `value`, whose reader decodes (§4), but the union member
  it fills carries a `Token` — §5.10 calls a type argument's literal a bare token rather than the value it
  denotes, and a decoded host object cannot fill one. So the factory picks `RawTokenParser` for a slot whose
  bound component is `schema.meta.Token`, by the component's own Java type — and refuses to build at all if
  two group members share a slot type and disagree, since the resolver is keyed by type name and could not
  serve both.
  - **The token reaches identity, and §4.3's equivalence is applied there** (`NumericIdentity`), so
    `vector<float32, 255>` and `vector<float32, 0xFF>` are one application rather than two entries with
    byte-identical bodies. The stake is a verdict: §5.4 can only ask "are these variants distinct types?" of
    entry names, so two names for one type admitted `( [float32; 255] | [float32; 0xFF] )` — a choice between
    two identical, non-disjoint variants no untagged read can discriminate — where two spellings of one name
    were refused. §8.2 settles it in exactly these terms — a value argument is "recorded as written" and
    compared as the value the token denotes under §4, "and no wider" — so resolved output still shows the
    author's spelling while identity sees one argument.
- **Bind mode only.** Tree mode reads into `TsonValue` and has no Java shape to satisfy.

## The registries (`tson-compiler/{TsonCompiledMetaRegistry,TsonCompiledSchemaRegistry}.java`)

Two registries over one shared resolution core, the compiled-side counterparts to `tson-schema`'s
`TsonSchemaRegistry`.

- **`TsonCompiledMetaRegistry`** is the shared **meta/resolution core**, and *is* the on-demand
  `TsonCompiledSchemaLoader`. It owns the paired `TsonSchemaRegistry`, a bind-mode resolver, a
  `TsonSchemaSource`, content-hash verification, and the meta-kernel bootstrap. It compiles and caches
  **only meta-layer schemas** (meta-kernel, meta.tn — the name is literally accurate). Its loader
  interface is two honest methods: `loadMeta(uri) → TsonCompiledMetaSchema` (a governing meta, which must
  be compiled — its `!enum`/`!integer` instances are read into `schema.meta` objects during a governed
  schema's resolution) and `resolveLinked(uri) → TsonLinkedSchema` (an `!!import` target or a user schema
  — fetched/resolved/linked/registered but **never compiled** here). `withStandardLibrary(context,
  source)` builds a core with the three bundled schemas loaded; **core.tn is not a meta** (its `!!meta` is
  meta.tn) so it is resolve-only here — its readers are compiled per mode in a read registry when a user
  schema importing it is read, never standalone in the core.
- **`TsonCompiledSchemaRegistry`** is a **per-mode registry of compiled user schemas** over a core, built
  via `TsonCompiledSchemaRegistry.dom(core)` / `bind(core, context)`. **The read mode is which registry
  you hold**, not a compile parameter. `get(uri)` resolves through the core (`resolveLinked`) and compiles
  the linked form standalone in its own mode, cached by identity; `compile(linked)` is the uncached
  primitive.
- **Resolution is always bind-anchored, so it is delegated to the core regardless of read mode.** A
  schema's own `!enum`/`!integer` instances bind to `schema.meta.Top` objects — a tree reader's `TsonValue`
  can't stand in — so every read registry shares the one bind-mode core for resolution; only the final compile
  runs in the registry's mode (standalone: the schema's constructor usage was already validated at link
  time). The bind read registry takes the *caller's own* `DataBindContext` (their user-class name binder),
  deliberately distinct from the core's internal `SchemaMetaNameBinder`-based resolution context. A user
  schema importing core.tn gets core.tn's entries flattened into its own linked form (by `link`) and
  compiled inline, which is why the core never needs core.tn compiled.
- **An import cycle is caught by what is *in flight*, not by a cache lookup** (§2.2.3). A schema is
  registered only once it has linked, so while `a.tn` is resolving it is in no registry at all and `b.tn`
  importing it back re-enters `resolveLinked` for the same identity and fetches it again — unguarded, that
  is unbounded recursion ending in a `StackOverflowError`: an `Error` raised by ordinary author input, which
  no `Diagnostic` ever sees and which the exception policy cannot classify. `resolving` holds the identities
  this thread is part-way through, and the chain closing the cycle is what the message names, so any one of
  its links is the edge to break. The same guard covers a `!!meta` chain, every link of which is reached
  through `resolveLinked`.
    - **Per thread, not per registry**, and that is not incidental: concurrent resolution of one identity by
      two threads is safe here by design (below), and a registry-wide set would make the second thread's
      ordinary in-flight entry look like a cycle to the first. Recursion through `!!import`/`!!meta` is
      strictly within one thread, which is exactly the scope of the question.
    - It **throws even with a receiver in play**, on the same footing as an unloadable `!!import` or an
      ineligible `!!meta`: what fails is the namespace itself, so carrying on would report every reference
      into the unresolvable half as a second problem. What is *not* here is the ordering half — collecting a
      schema's transitive closure and resolving it dependencies-first, so callers stop hand-sequencing
      registration (`BACKLOG.md`).
- **Content-hash verification is per identity** (§10.2): the core records an identity's content hash on
  first resolution and checks every reference's `?sha256=` pin against it, on both fetch and cache-hit
  paths, so a conflicting pin errors rather than silently resolving to the cached instance. Verify-before-
  record, so a rejected fetch can't poison a later valid one.
- **Concurrent first use of one identity is safe, and deliberately not serialized.** `loadMeta`/
  `resolveLinked` recurse into themselves and hold no lock across a fetch, so two threads reaching the same
  cold identity both do the work; the caches settle it, keeping the first entry and handing it to both
  (`TsonSchemaRegistry.registerIfAbsent`, `compileAndCache`). **What is duplicated on a race is work, never
  state** — one linked form and one compiled meta per identity, always. This is the fix for a real defect,
  not a hypothetical: the old check-then-`register` shape failed the *loser*, and on a read that surfaced
  not as a crash but as a `SCHEMA_ERROR` against a document with nothing wrong with it, on the first
  concurrent requests a process ever served (`ReadPathConcurrencyTest` pins both halves). Explicit
  registration stays strict — `register` on an identity already present is still an error, since doing that
  on purpose is a caller mistake however many threads are involved. The same shape and the same fix applied
  to `DataBindContext.getDescriptor`, the other read-path cache.
- **A hit takes no lock either.** Every data read reaches two caches — `TsonSchemaRegistry`'s identity map
  (through `resolveLinked`) and `TsonCompiledSchemaRegistry`'s compiled map — and in a process that
  registered its schemas at startup, which is what this design asks for, both hit essentially every time.
  So neither hit is allowed to serialize: `TsonSchemaRegistry` holds a `ConcurrentHashMap` and its lookups
  are plain reads where they were `synchronized` methods, and the compiled cache does a `get` before
  `computeIfAbsent`, which takes a bin lock only for a key sitting behind the first node. The
  no-overwrite rule is unaffected — it moves from "check and put under the monitor" to `putIfAbsent`, which
  is the same guarantee stated atomically, and `register` still refuses a second registration of one
  identity. **Measured, this is small on a 16-CPU machine** (~6% at 32 threads, nothing below that): the
  critical section was a map lookup, and a JVM absorbs an uncontended monitor well. It is here because a
  monitor on the read path is a ceiling that arrives with the core count rather than a cost that shows up
  in a profile, and because the section can only grow.
- **A read canonicalizes its schema URI once.** `TsonCanonicalIdentity.canonicalize` is a `new URI(...)`
  parse, and it used to run three times for one document — the compiled-schema cache's key, the resolution
  cache's key, and the schema registry's own lookup. The identity is now computed at the top and passed
  down (`resolveLinked(uri, identity, receiver)`, `TsonSchemaRegistry.getByCanonicalIdentity`), with the
  single-argument forms kept as the door for anyone holding a URI as written. **Not a shortcut past the pin
  check**: `verifyPin` runs on every reference as before, and `BundledSchemaPinTest` pins that a wrong pin
  is still rejected once the schema is compiled and cached.
- **The rest of the read path needs no locking at all.** A `Lexer`/`TsonDataStream` is built per read and
  shared with nothing, and every compiled reader is immutable — the whole `reader` package holds exactly one
  non-final instance field (`CompiledReaders.delegate`, `volatile`, rebound once at the end of a compile).
  So a `TsonCompiledSchema` is safe to share across threads, which is what makes a `Tson` worth sharing.
