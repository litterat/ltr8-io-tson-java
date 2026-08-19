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
  wrapper that is a compile-time proof linking ran): (1) **merge `!!import`s** — each import's entries
  copied in as-is, keeping their home namespace, name collisions rejected, and **each merged entry's origin
  recorded** (`TsonLinkedSchema.entryOrigins`, name → the canonical identity of the schema that *declared*
  it, taken from the import's own `originOf` so an entry two hops away keeps its author rather than the
  intermediary); (2) **populate `subtypes`**
  (reverse of `supertypes`); (3) **derive `disjoint`** for every choice entry (`ChoiceDisjointness`, §5.4) —
  total and two-valued, detailed under "The disjointness derivation" below, so a linked choice always
  carries the fact;
  (4) **validate** every reference
  resolves, with a type-parameter exception (a bare name valid if it's the entry's own declared parameter);
  **a choice's variants are checked distinct** (§5.4) *after* §8.3 flattening, since an alias and its target
  are one type — so `(text | my_text)` with `my_text => text` is caught, which comparing the written names
  would miss and which is the only spelling an author can't see for themselves; the walk stops on a
  reference cycle rather than hanging, cycle diagnosis being its own unimplemented concern; **an author's
  `@disjoint` marker is checked against the derived fact** (§5.4) — `true` verifies it silently, `false` is
  an error, and there is no third outcome because the derivation is total (`SPEC-FEEDBACK.md` #47, which
  resolves #42's "pin the decision procedure" case: §5.4's warn-on-unprovable can never arise, and there is
  no severity axis and none coming). The marker is read from both places §6 puts it,
  the definition and the map key, which is why the check runs last, after `withNameAnnotations`;
  and a **constructor-eligibility** check with two halves, the same §2.2.2 question asked from both ends
  (see `SPEC-FEEDBACK.md` #19): a locally-declared `constructor: true` entry is valid only if the schema's
  `!!meta` is exactly meta-kernel's identity, and a schema named as this one's **`!!meta` target** is valid
  only if *its* `!!meta` is — so an ordinary type library can't govern (naming core.tn as `!!meta` is the
  `!!import` confusion, and core.tn declares no constructors to supply). The target half is judged only when
  the loader actually produced the target; an unresolvable `!!meta` is left to whoever owns fetching, which
  is also what keeps meta-kernel's self-naming `!!meta` linkable mid-registration. In the shipped wiring
  `TsonCompiledMetaRegistry.loadMeta` reaches that verdict a phase earlier (it must *compile* the meta to
  resolve against it) and raises the linker's own `TsonSchemaLinker.notAMetaSchema` — one wording, one module,
  and a **`TsonSchemaValidationException` rather than an `IllegalStateException`**
  because a wrong `!!meta` is an authoring error, not a library fault (which is what lets the CLI keep exit 1
  and exit 70 apart). `source`
  validation additionally falls back to the governing meta's namespace (a `source` naming a constructor is
  one of §3.3.1's constructor roles); no other reference does. **The linker does not materialize anything** —
  `SchemaDesugarer` already turned every sugar form into a real declaration, one phase earlier and in the
  module that can bind a constructor generically. The only argument-bearing `type_ref` it ever sees is inside
  a template declaration, which the desugar phase passes through whole (`box<T>` in `box`'s own body), and
  which is validated, not rewritten.
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

## The disjointness derivation (`ChoiceDisjointness`, `reader/DiscriminationClass`)

`ChoiceDisjointness.derive` decides §5.4's question for a choice **totally and two-valued**: `disjoint` is
`true` exactly when every variant has a *discrimination class* and no class appears twice, `false`
otherwise — never absent. This is a deliberate departure from §5.4's written value-set derivation (bound
intervals, MAY-prove record and pattern cases, "MUST leave absent when it cannot"), recorded as
`SPEC-FEEDBACK.md` #47: the question the fact exists to answer is not "do the value sets intersect" but
"can an encoding's single form-resolution pass tell the variants apart", and *that* is a total function of
the declarations. A value-set prover (interval algebra, exact I-Regexp intersection-emptiness, record
closure) was built and discarded during PR #36's review: it answered questions no conforming reader may
act on — separating same-class variants takes the type-directed second inspection [TSON-DATA] §2.4
forbids — at the price of verdicts an author cannot predict (two default-`allow_nan` floats overlap via
NaN however far apart their ranges sit) and a conformance bar no second implementation should have to
match. #47 carries the full account.

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
per `SPEC-FEEDBACK.md` #48.

**`disjoint` ⇔ the tag is droppable — one fact, not two.** `ChoiceReader.untaggedRecovery` builds its
`class → variant` dispatch map through the same `DiscriminationClass.of` the derivation classifies with,
so the derived fact and the reader's separability can never disagree (`SPEC-FEEDBACK.md` #23's two
carefully-held-apart facts collapse into one). Recovery still engages only when every class is *scalar* —
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
  registered factory (the undocumented atom families), or a factory that rejects one entry. `ErrorReader`
  throws unconditionally even in collecting mode (it's a library/schema-compile gap a caller can't fix by
  correcting data). A referenced-but-absent name is a stricter `TsonSchemaLinker` invariant violation and
  propagates uncaught.
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
- **Content-hash verification is per identity** (§10.2): the core records an identity's content hash on
  first resolution and checks every reference's `?sha256=` pin against it, on both fetch and cache-hit
  paths, so a conflicting pin errors rather than silently resolving to the cached instance. Verify-before-
  record, so a rejected fetch can't poison a later valid one.
