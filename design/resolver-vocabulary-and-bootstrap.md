# Resolver vocabulary, alias hops, `@meta.synthetic`, and the kernel bootstrap

The facts the resolver phases share rather than own: the three leaf classes (`WireForm`, `MetaRefs`, `DerivedName`),
how an alias chain is kept in resolved output and walked, the one derived marker, the compiled vocabulary a
resolution reads `!C` bodies and annotation values through, and the special-cased bootstrap that resolves the kernel,
meta.tn, itself. Current form only; history lives in git.

**Invariants**

- There is one `WireForm`: a held body is written by one phase and read by four, and a second opinion about what an
  application looks like is what makes one of them wrong. A record template has no held body: `SchemaDesugarer`
  writes its payload labelled, from `WireForm`'s member names, and it is substituted on the model
  (`RecordTemplateSubstitution`).
- `recordWire` builds a closed record's binding record directly, for a minted member's name — `TsonObjectWriter`
  output is fully quoted and writes every default, a different spelling from the one every other closed form is
  named by.
- Every walk over a held body descends into a map slot, key and value both.
- `MetaRefs`' switch over `Top` is exhaustive with no default arm, so a new body shape fails to compile there until
  it says which references it holds.
- `DerivedName`'s two families stay apart, `ofBinding` is called by **both** lift channels, and a derived name is
  asserted by value, never by `startsWith`.
- Resolved output states the alias chain as written; the chain collapses only where `TsonSchemaCompiler` compiles an
  `ALIAS` entry. `AliasChain` is the one walk — `ParameterTypes` keeps its own loop deliberately.
- `@meta.synthetic` goes on the **key** of exactly the sugar-form entries (both channels), never on an instantiation
  entry or an entry's value, and the linker re-attaches it (`withNameAnnotations`), imports included.
- `@meta.application` goes on the **key** of exactly the entries that close an application, whatever their kind, by
  the same path; `@meta.synthetic` is recomputed on ingest, `@meta.application` read as stated, and an author writing it
  is refused.
- A bundled document's root holds only the vocabulary an author writes; its machinery is a member of a nested map
  and named by its path (§9): the kernel's root is `void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`,
  `iri_reference`, `regex`, `doc`, `enum`, `set` and `schema`, and everything else it declares is under one of six maps,
  `meta` (the type system), `atom` (the datatype properties and `atom.specification`), `numeric`, `unicode`, `net` and
  `binary` (the kernel's atom constructors by family); each data-format library holds its instances at its root and its
  constructors under its map (`numeric`, `temporal`, `net`, numeric.tn and net.tn adding to the kernel's), and core.tn's
  root holds only the author annotations. A Java literal naming a member is its path, `KernelNames.of("record")` for
  `"meta.record"`, `KernelNames.of(KernelNames.UNICODE, "identifier_type")`, never the bare member; one naming a root entry
  is bare (`"text"`). The minted names' readable half reads a `meta.` path as its member (`InternalName.part`:
  `array_type_ref_…`, `record_…`); a path under the kernel's other maps keeps its first segment
  (`numeric_integer_type_…`).
- The bootstrap hand-builds exactly the pre-loaded set — the instances and the two refinements on the kernel's one
  wait cycle (§3.4, §3.4.1 rule 4) — and makes every other entry ready by `WaitOrder`, the one algorithm
  every schema's resolution runs. The order is the waits', never source order. `KernelBootstrapResolverTest`
  holds the set to the measured cycle and the output to the kernel's ordinary resolution in any declaration order;
  it attaches no `@meta.synthetic`.
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

**`WireForm`** — how schema vocabulary is spelled as data, in both directions. The vocabulary member names; the
`plain`/`nameField` builders every producer goes through; `refValue`, which writes a reference as a bare token or,
carrying arguments, as a `ReferenceValue`, the data grammar's reference ([TSON-DATA] §7.4), and
its inverse `applicationIn`, an `instanceof ReferenceValue`; `substituted`, which walks an application's argument tree,
rewrites a parameter named among its arguments at any depth, and writes the application again as a `ReferenceValue`;
the closed-record writer `recordWire`, which spells `fields` as a map from each field's declared name to its
`meta.record_field` (`fieldMap`/`fieldEntry`) for a minted record's name; and §5.10 `substitute` over a held body. Every
walk over a held body descends into a map's keys and values as it does into a record or an array, `fields` being one.

- **Why one class and not a producer beside one phase and a consumer beside another.** A held tree is written
  by one phase (`SchemaDesugarer` lifting a sugar form) and read by
  four (`TemplateMaterialiser` closing one, `HeldBody` answering §5.10's
  declaration-time questions, `SyntheticMerge` asking whether one holds an application, `ParameterTypes`
  walking one for parameter kinds). `applicationIn`'s own contract is that a held body is written by one
  phase and read by several, so a second opinion about what an application looks like is what makes one of
  them wrong. The writer, the reader and `ParameterTypes` all go through it; a walker recognising
  applications on its own is the copy that drifts silently, since nothing fails, only a parameter kind quietly
  not inferred. A quoted token is never an application, so a held body's text literal (`pattern: "box<T>"`) is
  text whatever it spells.
- **`TsonObjectWriter` cannot *build* a held body, though it is what emits one.** Writing a resolved `Top`
  gives canonical-explicit, fully quoted output — a different language from the one a held body is written in:
  `HeldBody.names()` and substitution both key on a token being *unquoted*, so a quoted body references no
  parameters at all. A held body is built by the desugar phase directly, and a minted record's binding record by
  `recordWire`. Once built, the tree is an
  AST, and `TsonObjectWriter` writes an AST as syntax rather than as a description of itself
  (`AstWriter`) — which is how `HeldBody.held` turns it into `TemplateBody.template`.
- **`refValue`'s `arguments().isEmpty()` branch is load-bearing**, not an optimisation — see the
  materialisation note (`design/template-materialisation.md`).
- **Every walk over a held body descends into a map slot.** A constructor with a map-typed field lets a template
  put a parameter inside a map — in a key, or inside the array its value names. Each of the three walks fails
  differently if it
  skips one: `substitute` leaves the parameter name standing where the argument belongs; `ParameterTypes`
  never observes the parameter, so its kind is never inferred and a type argument stays on the
  reference channel and fails as an unresolved reference; and `DerivedName`'s canonical rendering — the half
  §8.2 keys identity on — renders the whole map as the unknown-value mark, so two bindings differing only
  inside one hash alike while the readable half masks it, which is the `startsWith` hazard inverted and why
  `DerivedNameTest` asserts `canonicalBinding` directly. A map key and its value are each a `data-value`
  ([TSON-DATA] §2.6), rebuilt through `WireForm.rewritten` and `WireForm.retyped`; both halves descend, because a
  parameter reaches either.

**`MetaRefs`** — the `schema.meta` reference walk: `mapRefs` maps an entry's chain too,
`mapRefsKeepingChain` leaves the chain as written. Two callers: materialisation rewrites an application onto the
entry it closed, and §8.2's synthetic merge renames onto a merged entry. No pass rewrites a use site onto the end of
an alias chain — see "References are hops" below. Which body shape carries which references is a fact about the
value model, so it is stated once, where the model is walked:

- A record's field types (a record template's only where they name none of its parameters), an array's element, a
  map's and a namespace type's `key_type` and `value_type`, a tuple's elements, a choice's variants, and an alias's
  target, each a `TypeRef`, arguments and all.
- A reference's `of`, a `TypeRef`, arguments and all: an application written in the body (`!meta.reference_type {
  of: box<text> }`) closes here, as `meta.ref<T>` closes its argument before substituting, so `of` names an entry, a
  lifted synthetic among them (`meta.ref<[box<text>]>`), which the merge renames like any other reference.
- Nothing in any other atom (its facets are values), a declared type, a held body (its tokens are unresolved until
  substitution), or a value entry (data writes no sugar form, and its names are judged at link by `ValuesByName`).

§5.10's regularity check does not use the walk: every entry with parameters is open, and reads its applications off
its held tree (`HeldBody.applications`).

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
- **Three positions walk a chain where a declared entry might stand.** An application through an alias of a template
  applies the template (`TemplateMaterialiser.templateHead`, `checkArity`), its identity the template's, so over
  `ref_of => meta.ref` the applications `ref_of<order>` and `meta.ref<order>` close to one entry; an alias naming a
  template bare is no use of the template without arguments (`validateBody`'s `Alias` arm). An atom refinement's source
  written as an alias refines the instance at the end of the chain, and its chain names that instance
  (`resolveAtomRefinement`: `!count ^ { … }` over `count => integer` IS-A `integer`). An annotation is identified by the
  entries on its alias chain, not by its end (`AliasChain.annotates`/`passesThrough`): `@meta.disjoint` reaches
  `meta.disjoint` and then `void`, and the linker asks whether `meta.disjoint` is on the way. An alias of a constructor is
  applied as its target wherever it heads a body, a held template's included (§3.3.1): over `labels => enum`, `!labels [a b]`
  and `<T> !labels [a T]` apply `enum` (the definition reader follows the chain and retags the value). No bundled document
  relies on it.
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
  `spec/fixtures/*-resolved.tn`.

## The derived key annotations: `@meta.synthetic` and `@meta.application` (`tson-compiler/.../resolver/SchemaResolver.java`)

§8.2 puts a bare **`@meta.synthetic` on the key of every entry the resolver materialised from a sugar form**, and
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
- **`@meta.application` is the second derived key annotation** (§6, §8.1): on the key of every entry that
  closes a template application, the application it is the instantiation of, `@meta.application:box<int32>`, a
  `meta.type_ref` reference carrying arguments, written unquoted and stated as written: nested arguments unreduced
  (`@meta.application:pair<box<text> int32>`), a bare-name argument past its alias hops, an argument at a value parameter
  a value token (`TemplateMaterialiser.asWritten`). An expansion carries none; what a message needs
  of it is `TsonSchema.EntryFacts.closes`, held in memory and never written. It is about the entry, not about a
  type — a record template's member, a value entry (`order_api => orders<order>` over `orders => <T> !interface {
  get => { response: T } }`), the `Alias` a non-record template's application closes to — which is why it is no
  field of `meta.type_chain`. The materialiser records
  each application in a map it shares with `SchemaResolver` (the applied-parent fold and the family-base edges key on
  it, an import's read off its keys), and `SchemaResolver` writes it where it attaches `@meta.synthetic`;
  `TsonSchema.applicationOf` is the one accessor. **Unlike `@meta.synthetic` it is read as stated on ingest**, not
  recomputed: it is the one fact the entry's value does not state, and what an imported schema's instantiations unify
  by. An author writing it is a resolver error (`DefinitionResolver.refuseWrittenApplication`), as writing `chain` is.
- **It survives linking and the import merge**, which is where a key-side fact is easiest to lose: the linker
  rebuilds its entry map several times and re-attaches key annotations at the one point every entry passes
  through (`withNameAnnotations`), imports included, so an imported synthetic keeps the marker its own schema
  gave it.
- **The bootstrap route attaches none, deliberately.** `KernelBootstrapResolver` exists to be *just*
  enough to load the real kernel from its own file, and nothing in the pipeline reads this marker — it is
  informational. The kernel's own seven synthetics, minted at meta.tn's root, are marked anyway, because the entries
  anything else sees come from ordinary resolution: the bootstrap output stands in only as the transient vocabulary the
  kernel's own ordinary resolution reads through.
- Cross-checked against the spec's own output by `ResolvedFixtureTest.theSameEntriesAreMarkedSyntheticOnBothSides` — six keys
  in meta.tn, two in net.tn, none in numeric.tn, temporal.tn or core.tn, which write no inline form the kernel has not
  lifted. It reads the fixtures' marked keys from their *text*, because a key-position annotation is still dropped when a
  resolved-form document is read back (`BACKLOG.md`), and a bound comparison would have both sides render nothing and agree
  for the wrong reason.

## The compiled vocabulary (`SchemaResolver.Vocabulary`)

One namespace per schema (§3.3) puts every construction head and annotation type in the closure, and
a head's instance is read by a compiled reader. `SchemaResolver` records, for each imported entry, the canonical
identity of the schema that declared it, and its private `Vocabulary` reads `!C { … }` through
`TsonCompiledSchemaLoader.vocabularyOf(origin of C).reader(C)` and an annotation value through that schema's entry
reader. **Why the declaring schema's compile suffices**: an entry's reader was compiled over its declaring schema's
closure, which is inside the importer's, so it reads everything its body can name. Nothing is compiled per closure;
each declaring schema is compiled once and cached by identity (`design/compiled-registries.md`). A member of a nested map
is read through the compile of the document that declared it — `!meta.record` through meta.tn's,
`!numeric.decimal_type` through numeric.tn's, a member a user schema contributes to `meta` through that schema's.

- **A local head or annotation type is read through the entries ready so far.** `Vocabulary` tells a local name
  from an import by the recorded origins, and reads a local one through `WaitOrder.read`: readers compiled by
  `loader.compileReady(readySoFar(…), pending, tree)` from this document's ready entries over the same imports, each
  declaration not yet ready a `PendingTypeReader`. A read reaching one throws `NotReady`, the order makes that entry
  ready, and the read restarts; each declaration the read reaches is a wait of the entry being made ready
  (`TsonReadContext.HeldByName.reached`), which is what wait depth counts (`design/processor-policy.md`).
- **A payload is a value body exactly when its head is no constructor**: a value entry's payload is read against the type,
  under the loader's identifier policy, and lifts nothing; a construction's binding record is read under no policy
  (`SchemaResolver`'s `ConstructionReader`, `design/namespace-types-and-value-entries.md`). The value is bound where the
  context binds its type, and otherwise read in tree mode (the declaring schema's `treeVocabularyOf`, or a tree-mode compile
  of the entries ready so far) and held as a tree.
- **The kernel reads through itself.** Its resolution is handed the pre-loaded stand-in, which answers for every
  name, the one cycle being closed by pre-loading (§3.4).

## Kernel bootstrap (`tson-compiler/.../resolver/KernelBootstrapResolver.java`)

The kernel, meta.tn (`TsonBundledSchemas.META_ID`), is special: its header is its `!!id` alone. It holds the kernel's
one wait cycle, every name the cycle's declarations reference, closed to a fixed point, so it resolves and links on its
own, and the concepts that belong with them: `meta.map`, `meta.tuple`, `meta.choice` and `meta.tuple_element`,
`meta.template`, `meta.application`, `meta.ref`, `meta.field_name`, `meta.disjoint`, `meta.tuple1`,
`meta.voidable_tuple1`, `set`, the octet-sequence constructor `binary.bytes_type` with `binary.bytes_encoding` and
`bytes`, and the classifying annotations `atom.ordering`, `atom.bounded`, `atom.exact` and `atom.numeric` the
data-format libraries state their types with. It is the one kernel document. Its root is the thirteen names an author
writes (`void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`, `iri_reference`, `regex`, `doc`, `enum`, `set`
and `schema`; `enum => meta.enum_type ^ { name_type?: = meta.identifier }`, so resolved output tags `!enum`, and `schema
=> meta.schema`, the alias every schema map's tag names); everything else is a
member of `meta => !schema { … }`, named by its path (§9), `meta.identifier` among them. It imports
nothing and applies only its own constructors (§3.4's one cycle, closed by pre-loading, not resolution). It is never resolved
from a document, and its names reach any other schema only by import. Every name resolves from the root (#14), so an entry
naming a sibling writes its path, and the synthetics its sugar lifts are minted at its root. A schema importing it alone has
every sugar form and `meta.ref`. numeric.tn, temporal.tn and net.tn each import it alone and declare their
constructors under a map of their own, `numeric`, `temporal` and `net`, contributing nothing to `meta`
(`design/namespace-types-and-value-entries.md` has the open-namespace merge); core.tn imports the three. Ordinary resolution
can't bootstrap the kernel: an instance waits on its constructor being ready, and the instances on the one wait cycle wait on
each other, so they are built by hand (§3.4), and only the wait order over what that leaves is the algorithm every schema
runs.

`KernelBootstrapResolver.kernelSchema()` (its only public method) produces the resolved kernel under
§3.4.1's wait order: reading a value against a type waits on the type being ready, and on each type a part
of the value is read against as the read reaches it; a reference and a held value (a default or pin) order nothing.
Its output is what ordinary resolution of the kernel against it produces, entry for entry and constructor for
constructor, positions included (the bootstrap resolves with the parser's positions).

- **The pre-loaded set is hand-built, and is exactly the cycle.** The instances and the two refinements on the
  kernel's one wait cycle, the one holding `meta.record`: `boolean`, `meta.field_role`, `meta.enum_set`, `integer`,
  `numeric.count`, `text`, `meta.field_type_ref`, `meta.type_ref`, `meta.field_map` (`PRE_LOADED`), and the arrays
  lifted over `meta.type_ref`, `meta.field_group` and `text` and arrays of those (`PRE_LOADED_ELEMENTS` — a lifted
  array is named by its content, so it is designated by what it holds; an entry the source declares is no lift, so a
  declaration whose body is `[text]` is read, not built). `meta.field_type_ref => !meta.reference_type { of: meta.type
  }` is on the cycle because reading its `of` waits on `meta.type_ref`, its own refinement. `record_field.value` and
  `record_template_field.value` are typed `type!`, a dependent field, whose value a schema document holds as written
  through a reader of its own (`DependentFieldReader`), so neither record waits on any entry for it. `instanceBody`
  covers the shapes those apply — a bare `{}` (each target's unconstrained constant), a bare token array (`enum`, the
  kernel's own enumeration constructor), and a record of tokens (`meta.reference_type` with its `of` and an
  `allow_sibling` defaulting to true, `meta.map` for `meta.field_map`, the `meta.array`/`meta.set_type` binding records
  the desugar phase lifts, with their `min_items`/`max_items`) — and refuses any other target; `refinedBody` merges
  the integer family's `min`, `max` and `size` (`numeric.count`'s `size: { bits: 64  signed: true }`) and a
  reference's `of` and `allow_sibling` (`meta.type_ref`'s `allow_sibling: false`). Once every entry is ready the
  chains are closed transitively, as `SchemaResolver` closes a schema's (`completeChains`), so `meta.identifier_ref`,
  a refinement of `meta.type_ref`, lists `meta.field_type_ref` too. `KernelBootstrapResolverTest` measures the
  cycle with `WaitGraph` from `spec/fixtures/meta-resolved.tn` (the six maps read as their members under their paths)
  and asserts the hand-built names equal it, so a kernel edit that moves the cycle fails there. `KernelWaitGraphTest`
  prints the measurement — 88 entries (68 members of the six maps, 13 at the root and 7 synthetics; the maps
  themselves make the kernel's 94 resolved entries), one cycle of 25, longest wait chain 4 — and
  `theKernelHoldsTheClosureOfItsCycleAndItsRelatedConcepts` asserts the kernel contains the cycle's closure (66
  entries, the root tag's `schema` among them: reading the document waits on the alias its tag names) and the related
  concepts, pinning all three. Of the 25, 13 are hand-built (the 9 of
  `PRE_LOADED` and 4 lifted arrays); the other 12 are records and compositions resolved from their grammar, and the
  63 entries beyond the cycle are made ready in wait order.
- **A declaration no read produces is resolved from its grammar** by `DefinitionResolver` — a record, a composition,
  a refinement of a record, an alias, a template — as `SchemaResolver` resolves one. The bootstrap readies these and
  the pre-loaded set first, so the first compile holds every head a read applies. Two refinements wait on the cycle
  and stay off it: `meta.namespace => meta.map ^ { … }` waits on its source `meta.map`, and `meta.identifier_ref =>
  !meta.type_ref ^ { … }` on `meta.type_ref`, hand-built; the names their `key_type` and `of` hold order nothing, so
  neither is a wait of the cycle's entries and the measurements above stand.
- **Every other instance is read through the ordinary path.** `DefinitionResolver.resolveInstance` binds through a
  `ConstructionReader` that reads through `WaitOrder.read`, against readers compiled from the entries ready now:
  `TsonSchemaLinker.linkReady(ready, null, pending)` then `TsonSchemaCompiler.compileReady(linked, factories,
  pending)`, `pending` naming the kernel's declarations not yet ready. A pending name compiles to a
  `PendingTypeReader`. A read that reaches one throws `PendingTypeReader.NotReady`; the order makes that entry
  ready, drops the compile, and reads again. A held value reached through one
  binds as written — what base type resolution gives the token, carried to its component by the field's bridge — and
  is judged when the whole kernel links. That is how `iri` is read against `net.iri_type`, whose `spec` pin is typed by
  `iri` itself: rule 3 says the pin orders nothing, and binding it as written is what makes that true here.
- **The order is computed.** The bootstrap is `WaitOrder`'s `Readiness` (`handBuilt` for the pre-loaded set,
  `DefinitionResolver` for the rest), and the order is the `DefinitionGetter` every lookup goes through, making an
  entry ready the first time anything asks for it. An entry waiting on itself is the `SchemaValidationException`
  every schema gets, naming the cycle; the bootstrap rethrows every failure, since only a kernel edit can put a cycle
  outside the pre-loaded set. The kernel is resolved under the default wait depth. The driving loop's order changes
  how many compiles run, never the output (`theBootstrapIsTheSameInAnyOrder`): an instance whose read reaches an
  entry not yet ready (`net.scheme_name` reaching `unicode.identifier_base` and `unicode.normalization`) forces another
  compile. Its output's nested map `meta` is derived from the members, so the order test compares its names.
- **Parameter types are derived after every entry is ready**, through `ParameterTypes.inferAll` as `SchemaResolver`
  derives a schema's, so `meta.ref` carries `T: meta.type_ref`, a type parameter, as their ordinary
  resolution does.
- **The partial link is contained.** It passes over a reference to a pending name, and an entry's walk stops at its
  first one, so the rest of that entry is judged when the whole kernel links, which every bootstrap is followed by
  (`TsonCompiledVocabularyRegistry.loadVocabulary` links and compiles the output into the stand-in).
- **Every name the kernel holds is the kernel's own**, so `DefinitionResolver` judges kinds and constructors with a
  predicate that admits every name (`name -> true`); ordinary resolution judges by the origin of each name in the
  merged namespace instead, `TsonBundledSchemas.isKernel` admitting meta.tn alone, so a member a user schema
  contributes to `meta` is no kernel entry. The same document is the pre-loaded one: the bootstrap, the stand-in every
  head of its ordinary resolution is read through and the registry's fetch override are its. In both, a head declared
  beside its instance is made ready in wait order.
- **Desugaring needs no equivalent trick.** The container constructors are parameterless (§4.2), so the desugar table
  is fixed by the sugar forms and nothing is looked up in another schema. The kernel's six sugar forms are
  ordinary declarations at its root by the time the linker sees them, and every sugar target is the kernel's own.
- **Cost.** The two partial links and compiles make a warm bootstrap about 11 ms, and it runs once per registry:
  `loadVocabulary` builds the stand-in on the first lookup of meta.tn's identity and reuses it until the ordinarily
  resolved kernel is registered (`registerBundled`), after which the kernel's vocabulary is that registered schema's
  compile. The whole of `Tson.standard()`, all six bundled schemas resolved and registered, is about 440 ms as a
  fresh JVM's first call and 65 ms warm.
