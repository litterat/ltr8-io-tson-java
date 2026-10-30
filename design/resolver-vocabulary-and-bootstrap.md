# Resolver vocabulary, alias hops, `@synthetic`, and the meta-kernel bootstrap

The facts the resolver phases share rather than own: the three leaf classes (`WireForm`, `MetaRefs`, `DerivedName`),
how an alias chain is kept in resolved output and walked, the one derived marker, the compiled vocabulary a
resolution reads `!C` bodies and annotation values through, and the special-cased bootstrap that resolves meta-kernel
itself. Current form only; history lives in git.

**Invariants**

- There is one `WireForm`: a held body is written by two phases and read by four, and a second opinion about what an
  application looks like is what makes one of them wrong.
- `heldRecord` builds the wire tree directly — `TsonObjectWriter` output is fully quoted, and `HeldBody.names()` and
  substitution key on a token being *unquoted*.
- Every walk over a held body descends into a map slot, key and value both.
- `MetaRefs` visits by rewriting with the identity function; there is no separate read-only walk to fall behind.
- `DerivedName`'s two families stay apart, `ofBinding` is called by **both** lift channels, and a derived name is
  asserted by value, never by `startsWith`.
- Resolved output states the alias chain as written; the chain collapses only where `TsonSchemaCompiler` compiles an
  `ALIAS` entry. `AliasChain` is the one walk — `ParameterTypes` keeps its own loop deliberately.
- `@synthetic` goes on the **key** of exactly the sugar-form entries (both channels), never on an instantiation entry
  or an entry's value, and the linker re-attaches it (`withNameAnnotations`), imports included.
- `@application` goes on the **key** of exactly the entries that close an application, whatever their kind, by the
  same path; `@synthetic` is recomputed on ingest, `@application` read as stated, and an author writing it is refused.
- The bootstrap hand-builds exactly the pre-loaded set — the instances and the one refinement on the kernel's one
  wait cycle (`SPEC-FEEDBACK.md #4`, rule 4) — and makes every other entry ready by `WaitOrder`, the one algorithm
  every schema's resolution runs. The order is the waits', never source order. `MetaKernelBootstrapResolverTest`
  holds the set to the measured cycle and the output to the kernel's ordinary resolution in any declaration order;
  it attaches no `@synthetic`.
- A `!C { … }` body or an annotation value against an imported entry is read through the compiled form of the schema
  that declared it (`SchemaResolver.Vocabulary`, by the origin the import merge recorded), never through one compiled
  per closure; against an entry of the same document, through readers compiled from that document's entries ready so
  far (`WaitOrder.read`). The kernel's ordinary resolution alone reads every head through its pre-loaded stand-in.
- Only a resolution in progress links with references pending or compiles with entries pending
  (`TsonSchemaLinker.linkReady`, `TsonSchemaCompiler.compileReady`, through `TsonCompiledSchemaLoader.compileReady`).
  The partial link reports nothing, each entry being judged when the whole schema links; a finished schema's link
  refuses an undeclared name.

Related: `design/schema-resolution.md` (definition resolution), `design/template-materialisation.md` and
`design/held-template-bodies.md` (the callers of `WireForm` and `DerivedName`), `design/constructor-application.md`,
`design/schema-grammar-and-desugaring.md` (the desugar lift), `design/linking-and-compilation.md`.

## Shared vocabulary: `WireForm`, `MetaRefs`, `DerivedName`

Three dependency-free leaf classes the phases share. Each owns a fact that belongs to none of them
individually; stated inside whichever phase happens to need it first, such a fact ends up stated more than
once.

**`WireForm`** — how schema vocabulary is spelled as data, in both directions. The vocabulary member names;
the `plain`/`nameField` builders every producer goes through; `refValue` and its inverse
`typeRefOf`/`argumentOf`; the held-record writer `heldRecord`, which spells `fields` as a map
from each field's declared name to its `record_field` (`fieldMap`/`fieldEntry`); and §5.10 `substitute` over a
held body. Every walk over a held body descends into a map's keys and values as it does into a record or an
array, `fields` being one.

- **Why one class and not a producer beside one phase and a consumer beside another.** A held tree is written
  by two phases (`SchemaDesugarer` lifting a sugar form, `HeldBody.of` writing an open record's) and read by
  four (`TemplateMaterialiser` closing one, `HeldBody` answering §5.10's
  declaration-time questions, `SyntheticMerge` asking whether one holds an application, `ParameterTypes`
  walking one for parameter kinds). `isApplication`'s own contract is that a held body is written by one
  phase and read by several, so a second opinion about what an application looks like is what makes one of
  them wrong. The writer, the reader and `ParameterTypes` all go through it; a walker matching
  `name`/`arguments` against its own string literals is the copy that drifts silently, since nothing fails,
  only a parameter kind quietly not inferred.
- **`TsonObjectWriter` cannot *build* a held body, though it is what emits one.** Writing a resolved `Top`
  gives canonical-explicit, fully quoted output — a different language from the one a held body is written in:
  `HeldBody.names()` and substitution both key on a token being *unquoted*, so a quoted body references no
  parameters at all. That is why `heldRecord` builds the wire tree directly, for an open record's held tree. A
  held body of any other constructor is built by the desugar phase the same way. Once built, the tree is an
  AST, and `TsonObjectWriter` writes an AST as syntax rather than as a description of itself
  (`AstWriter`) — which is how `HeldBody.held` turns it into `TemplateBody.template`.
- **`refValue`'s `arguments().isEmpty()` branch is load-bearing**, not an optimisation — see the
  materialisation note (`design/template-materialisation.md`).
- **Every walk over a held body descends into a map slot.** A constructor with a map-typed field lets a template
  put a parameter inside a map — in a key, or inside the array its value names. Each of the three walks fails
  differently if it
  skips one: `substitute` leaves the parameter name standing where the argument belongs; `ParameterTypes`
  never observes the parameter, so its kind is never inferred and a `type_name` argument stays on the
  reference channel and fails as an unresolved reference; and `DerivedName`'s canonical rendering — the half
  §8.2 keys identity on — renders the whole map as the unknown-value mark, so two bindings differing only
  inside one hash alike while the readable half masks it, which is the `startsWith` hazard inverted and why
  `DerivedNameTest` asserts `canonicalBinding` directly. A map key and its value are each a `data-value`
  ([TSON-DATA] §2.6), rebuilt through `WireForm.rewritten` and `WireForm.retyped`; both halves descend, because a
  parameter reaches either.

**`MetaRefs`** — the `schema.meta` reference walk, `mapRefs` over a definition and `mapBodyRefs` over a body.
Three callers use it and only one is closing a template: materialisation rewrites an application onto the entry it
closed, §8.2's synthetic merge renames onto a merged entry, and §5.10's regularity check uses it as a
*visitor* by returning each reference unchanged. No pass rewrites a use site onto the end of an alias
chain — see "References are hops" below. Which body shape carries which references is a fact about the value model, so it is
stated where the model is walked. Visiting is rewriting with the identity function deliberately: a separate
read-only walk would be a second list of body shapes to keep in step, and the one that fell behind would
silently skip a reference rather than fail.

**`DerivedName`** — §8.2's names and the renderings their hashes run over, in two families that stay apart:

- A **binding record** names a closed form (what a sugar form lifts to, what closing an open synthetic
  produces) and renders with its fields under their own names. An **application** names an instantiation and
  renders positionally. The two reuse the same tag letters in different roles, so merging them would be
  merging two questions that only look alike.
- What must *not* fork is each family's own rendering. `ofBinding` is called by **both** lift channels, and
  that shared call is exactly what makes a form written directly and the same form arriving through a
  materialised template land on one entry.
- The two families share one `appendText` and one `appendNumberAware` (a `Token` and a `TokenValue` render
  alike). `MintedNames`' contract depends on the renderings agreeing, and a shared decision about identity
  kept in two places is how they stop agreeing.
- **A hash is not normative, so the conformance layer cannot see one move.** `ResolvedFixtureTest` and the
  `class2/schema/` runner both reduce a synthetic's content hash to a placeholder before comparing (see
  `ResolvedForm`) — right for a comparison against the spec's own fixtures, since §8.2 leaves the spelling to
  the implementation, and it means neither can fail on a rendering change. What guards the renderings instead
  is value-level: `DerivedNameTest` pins both channels, and `SchemaDesugarerTest` pins the binding side end to
  end. The point is not that the values are required but that a change to them is deliberate — an entry name
  is part of the resolved form, and an importing schema derives the same name for the same form.
- **Assert a derived name by value, never by `startsWith`.** An assertion on an instantiation name's
  readable half (`startsWith("box_text_")`) passes a change to the hashed rendering — perturbing
  `canonicalApplication` alone leaves such a build green. `DerivedNameTest` is the value-level guard for the
  application channel.

## References are hops, not rewrites (`tson-compiler/.../TsonSchemaCompiler.java`)

**There is no use-site flattening pass and no `@alias` marker.** Resolved output states the chain the author
wrote: a type position naming an `ALIAS` entry keeps that name, nothing is attached to record where it
"really" points, and the chain stays walkable through the entries themselves.

**A processor collapses the chain when it compiles readers** — after linking, once per entry, where the whole
namespace is present. `TsonSchemaCompiler`'s alias branch is that moment: an `ALIAS` entry's reader
*is* its target's reader, resolved recursively, named for the entry doing the referring, so a use site naming
`pct` over `pct => small` reads and reports as `pct`.

- **The walk is unavoidable, which is why a rewrite is not worth its price.** §8.3 requires the chain stay
  walkable (`alias.target` is never flattened), and several passes walk one. Rewriting the output as well
  would leave two representations to keep in step, and an `@alias` marker recording where a rewritten site
  pointed is a *lossy* summary of the one it duplicates — it keeps only the source-site name, so in
  `digest_chain => digest_alias => bytes` it records the hop that carries nothing.
- **`AliasChain` is that walk, stated once** (`resolver/AliasChain.java`). The linker's choice-variant
  distinctness and its §5.2 field-value check, `Subsumption`'s subtype naming and `DiscriminationClass`'s
  classification all use it, because the one decision inside — *stop at a non-reference, at an
  **argument-bearing** target (an application, with no entry until materialisation mints one), or on a cycle*
  — kept in four loops is four decisions that can drift. `terminal` answers with a name, `terminalDefinition` with the entry;
  they differ only on an undeclared name and a cycle, where the first has an answer its caller wants (a type
  parameter is its own terminal) and the second has none. **`ParameterTypes` keeps its own loop deliberately**:
  it follows a chain to a slot's declared body and must *not* stop at an argument-bearing target, the template
  being the answer there. `AliasChainWalkTest` pins all four stops.
- **Anything that needs the chain end walks it and says so.** `TsonSchemaLinker.checkFieldValue` walks to the
  terminal before checking a `~`/`=` value, since a field typed by an alias states a value of whatever the
  alias names; `FieldValueConformanceTest` pins both directions.
- **§8.3 states both halves, and the walkers are several.** A processor MAY collapse after linking, when it
  compiles for reading, and MUST NOT collapse in resolved output. The compiler, `DiscriminationClass`,
  `TypeInhabitance` and the linker each walk a chain. A directive on an alias is applied where the alias compiles
  (`UseSite.named`, applied by the
  alias entry's own compile).
- **The bootstrap route needs no special case.** Neither it nor ordinary resolution rewrites a reference, so
  the two cannot diverge on one (`BootstrapReferencesTest`).
- Pinned by `AliasChainTest` — the chain stated as written, a read still reaching the end of it, and a
  diagnostic naming the hop the author wrote — and end to end by `ResolvedFixtureTest` against the spec's own
  `spec/m/*-resolved.tn`.

## The derived key annotations: `@synthetic` and `@application` (`tson-compiler/.../resolver/SchemaResolver.java`)

§8.2 puts a bare **`@synthetic` on the key of every entry the resolver materialised from a sugar form**, and
on no other. It is *derived* — attached by the resolver rather than written by an author, and discarded and
recomputed on ingest (§8.1), so it carries no decode force and cannot be forged into a resolved document to
change how it reads. It is built by name rather than resolved in the schema's namespace the way an author-written
annotation is: there is no author to resolve against, and the value is fixed.

- **Why a marker at all, when the names are distinctive.** A synthetic is named by derivation from its own
  content, but §8.2 makes that spelling non-normative — an implementation picks its own — so pattern-matching
  the name is not a way to recognise one. Without the marker a consumer of resolver output cannot tell a
  materialised entry from a declared one, which is what folding these entries back into the nested form the
  author wrote depends on.
- **Key position, per §6.** An annotation before a declared name is metadata *about the declaration*, and a
  resolved schema is a `{type_name => top}` — so the marker lands on the map's key, which
  `TsonSchema.entries()` keeps reachable through `Namespace.getAnnotations(name)`, never on the
  entry's value. §6 forbids hoisting between the two positions and nothing here does.
- **Marked: exactly the synthetic entries, from both channels.** The desugar lift produces them and
  `SchemaDesugarer.lifted` names them as the document's own set difference
  (`design/schema-grammar-and-desugaring.md`); materialisation produces more of the same kind when it closes an
  open synthetic, and `TemplateMaterialiser.syntheticNames()` reports which of its minted entries those are.
  A declaration's own sugar body is **not** one: `tag_list => [text; 1..2]` *is* the construction, not a lift
  of one (§5.3).
- **`SchemaResolver` attaches it where it assembles the entry map**, from those two mint sites — the desugar lift
  (`SchemaDesugarer.lifted`, the document's own set difference) and materialisation closing an open synthetic
  (`TemplateMaterialiser.syntheticNames`).
- **Unmarked: instantiation entries.** §8.2 draws that line itself: only synthetics are the
  fold-back-into-display case the marker serves; an instantiation is a type the author named by its
  application. Marking too much would be as wrong as marking too
  little, so `SyntheticEntryMarkerTest` asserts both halves.
- **`@application` is the second derived key annotation** (§6, §8.1): on the key of every entry that
  closes a template application, the application it is the instantiation of, `@application:{ name: box  arguments:
  [{ name: int32 }] }`. It is about the entry, not about a type — a record template's member, a value entry
  (`order_api => orders<order>` over `orders => <T> !interface { get => { response: T } }`), the `Alias` a
  non-record template's application closes to — which is why it is no field of `type_chain`. The materialiser records
  each application in a map it shares with `SchemaResolver` (the applied-parent fold and the family-base edges key on
  it, an import's read off its keys), and `SchemaResolver` writes it where it attaches `@synthetic`;
  `TsonSchema.applicationOf` is the one accessor. **Unlike `@synthetic` it is read as stated on ingest**, not
  recomputed: it is the one fact the entry's value does not state, and what an imported schema's instantiations unify
  by. An author writing it is a resolver error (`DefinitionResolver.refuseWrittenApplication`), as writing `chain` is.
- **It survives linking and the import merge**, which is where a key-side fact is easiest to lose: the linker
  rebuilds its entry map several times and re-attaches key annotations at the one point every entry passes
  through (`withNameAnnotations`), imports included, so an imported synthetic keeps the marker its own schema
  gave it.
- **The bootstrap route attaches none, deliberately.** `MetaKernelBootstrapResolver` exists to be *just*
  enough to load the real meta-kernel from its own file, and nothing in the pipeline reads this marker — it is
  informational. meta-kernel's own eight synthetics are marked anyway, because the entries anything else sees come from
  ordinary resolution: the bootstrap output stands in only as the transient vocabulary the kernel's own ordinary
  resolution reads through.
- Cross-checked against the spec's own output by
  `ResolvedFixtureTest.theSameEntriesAreMarkedSyntheticOnBothSides` — eight keys in meta-kernel, five in
  meta.tn, none in core.tn, which writes no inline form. It reads the fixtures' marked keys from their
  *text*, because a key-position annotation is still dropped when a resolved-form document is read back
  (`BACKLOG.md`), and a bound comparison would have both sides render nothing and agree for the wrong reason.

## The compiled vocabulary (`SchemaResolver.Vocabulary`)

One namespace per schema (SPEC-FEEDBACK.md #7) puts every construction head and annotation type in the closure, and
a head's instance is read by a compiled reader. `SchemaResolver` records, for each imported entry, the canonical
identity of the schema that declared it, and its private `Vocabulary` reads `!C { … }` through
`TsonCompiledSchemaLoader.vocabularyOf(origin of C).reader(C)` and an annotation value through that schema's entry
reader. **Why the declaring schema's compile suffices**: an entry's reader was compiled over its declaring schema's
closure, which is inside the importer's, so it reads everything its body can name. Nothing is compiled per closure;
each declaring schema is compiled once and cached by identity (`design/compiled-registries.md`), and a schema applying
only the kernel's and meta.tn's constructors reaches exactly those two.

- **A local head or annotation type is read through the entries ready so far.** `Vocabulary` tells a local name
  from an import by the recorded origins, and reads a local one through `WaitOrder.read`: readers compiled by
  `loader.compileReady(readySoFar(…), pending, tree)` from this document's ready entries over the same imports, each
  declaration not yet ready a `PendingTypeReader`. A read reaching one throws `NotReady`, the order makes that entry
  ready, and the read restarts; each declaration the read reaches is a wait of the entry being made ready
  (`TsonReadContext.HeldByName.reached`), which is what wait depth counts (`design/processor-policy.md`).
- **A payload is a value body exactly when its head is no constructor**: a value entry's payload is read against
  the type, under the loader's identifier policy, and lifts nothing; a construction's binding record is read under
  no policy (`SchemaResolver`'s `DefinitionMetaReader`, `design/meta-layer-namespaces.md`). The value is bound where
  the context binds its type, and otherwise read in tree mode (the declaring schema's `treeVocabularyOf`, or a
  tree-mode compile of the entries ready so far) and held as a tree.
- **The kernel reads through itself.** Its resolution is handed the pre-loaded stand-in, which answers for every
  name, the one cycle being closed by pre-loading (§3.4).

## Meta-kernel bootstrap (`tson-compiler/.../resolver/MetaKernelBootstrapResolver.java`)

Meta-kernel is special: its header is its `!!id` alone and its root `!schema`, so it imports nothing and applies only
its own constructors (§3.4's one cycle, closed by pre-loading, not resolution). It is never resolved from a document,
and its names reach any other schema only by import. Ordinary resolution can't bootstrap it: an instance waits on its
constructor being ready, and the instances on the kernel's one wait cycle wait on each other, so they are built by
hand (§3.4), and only the wait order over what that leaves is the algorithm every schema runs.

`MetaKernelBootstrapResolver.getMetaKernelSchema()` (its only public method) produces the resolved kernel under
`SPEC-FEEDBACK.md #4`'s order: reading a value against a type waits on the type being ready, and on each type a part
of the value is read against as the read reaches it; a reference and a held value (a default or pin) order nothing.
Its output is what ordinary resolution of the kernel against it produces, entry for entry and constructor for
constructor, positions included (the bootstrap resolves with the parser's positions).

- **The pre-loaded set is hand-built, and is exactly the cycle.** The instances and the one refinement on the kernel's
  one wait cycle, the one holding `record`: `value`, `boolean`, `field_role`, `enum_set`, `integer`,
  `non_negative_integer`, `text`, `identifier`, `type_kind`, `field_map` (`PRE_LOADED`), and the arrays lifted over
  `type_name`, `field_group` and `text` and arrays of those (`PRE_LOADED_ELEMENTS` — a lifted array is named by its
  content, so it is designated by what it holds). `instanceBody` covers the four shapes those apply — a bare `{}` (each
  target's unconstrained constant), `identifier`'s profile (checked against the constant the lexer already holds), a
  bare token array (`enum`), and a record of tokens (`kind_type`, `namespace` for `field_map`, the `array`/`set_type`
  binding records the desugar phase lifts, with their `min_items`/`max_items`) — and refuses any other target;
  `refinedBody` merges the integer family's `min` and `max`. `MetaKernelBootstrapResolverTest` measures the cycle with
  `WaitGraph` from `meta-kernel-resolved.tn` and asserts the hand-built names equal it, so a kernel edit that moves the
  cycle fails there. `KernelWaitGraphTest` prints the measurement: one cycle, 30 of the kernel's 79 entries (70
  declarations and 9 desugared arrays), longest wait chain 4.
- **A declaration no read produces is resolved from its grammar** by `DefinitionResolver` — a record, a composition,
  a refinement of a record, an alias, a template — as `SchemaResolver` resolves one. The bootstrap readies these and
  the pre-loaded set first, so the first compile holds every head a read applies.
- **Every other instance is read through the ordinary path.** `DefinitionResolver.resolveInstance` binds through a
  `DefinitionMetaReader` that reads through `WaitOrder.read`, against readers compiled from the entries ready now:
  `TsonSchemaLinker.linkReady(ready, null, pending)` then `TsonSchemaCompiler.compileReady(linked, factories,
  pending)`, `pending` naming the kernel's declarations not yet ready. A pending name compiles to a
  `PendingTypeReader`. A read that reaches one throws `PendingTypeReader.NotReady`; the order makes that entry
  ready, drops the compile, and reads again. A held value reached through one
  binds as written — what base type resolution gives the token, carried to its component by the field's bridge — and
  is judged when the whole kernel links. That is how `iri` is read against `iri_type`, whose `spec` pin is typed by
  `iri` itself: rule 3 says the pin orders nothing, and binding it as written is what makes that true here.
- **The order is computed.** The bootstrap is `WaitOrder`'s `Readiness` (`handBuilt` for the pre-loaded set,
  `DefinitionResolver` for the rest), and the order is the `DefinitionGetter` every lookup goes through, making an
  entry ready the first time anything asks for it. An entry waiting on itself is the `SchemaValidationException`
  every schema gets, naming the cycle; the bootstrap rethrows every failure, since only a kernel edit can put a cycle
  outside the pre-loaded set. The kernel is resolved under the default wait depth. The driving loop's order changes
  how many compiles run, never the output (`theBootstrapIsTheSameInAnyOrder`): an instance whose read reaches an
  entry not yet ready (`scheme_name` reaching `identifier_base` and `normalization`) forces another compile.
- **Parameter types are derived after every entry is ready**, through `ParameterTypes.inferAll` as `SchemaResolver`
  derives a schema's, so `kind` and `ref` carry `T: type_name` as their ordinary resolution does.
- **The partial link is contained.** It passes over a reference to a pending name, and an entry's walk stops at its
  first one, so the rest of that entry is judged when the whole kernel links, which every bootstrap is followed by
  (`TsonCompiledMetaRegistry.loadMeta` links and compiles the output into the stand-in).
- **Every name the kernel holds is the kernel's own**, so `DefinitionResolver` judges kinds and constructors with a
  predicate that admits every name (`name -> true`); ordinary resolution judges by the origin of each name in the
  merged namespace instead. In both, a head declared beside its instance is made ready in wait order.
- **Desugaring needs no equivalent trick.** The container constructors are parameterless (§4.2), so the desugar table
  is fixed by the sugar forms and nothing is looked up in another schema. Meta-kernel's eight sugar forms are
  ordinary declarations by the time the linker sees them.
- **Cost.** The two partial links and compiles make a warm bootstrap about 11 ms, and it runs once per registry:
  `loadMeta` builds the stand-in on the first lookup of the kernel's identity and reuses it until the ordinarily
  resolved kernel is registered (`registerBundled`), after which the kernel's vocabulary is that registered schema's
  compile.
