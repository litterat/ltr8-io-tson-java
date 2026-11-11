# Resolver vocabulary, alias hops, `@meta.application`, and the kernel bootstrap

The facts the resolver phases share rather than own: the leaf classes (`WireForm`, `MetaRefs`, `DerivedName`), how an
alias chain is kept in resolved output and walked, the one derived key annotation, the compiled vocabulary a
resolution reads `!C` bodies and annotation values through, and the special-cased bootstrap that resolves the kernel,
meta.tn, itself. Current form only; history lives in git.

**Invariants**

- There is one `WireForm`: a binding record is written by the desugarer and by construction sugar, and a second
  opinion about what a reference or an application in a slot looks like is what makes one of them wrong. An open
  record is no binding record: it is built on the model and substituted there (`Substitution`).
- `MetaRefs`' switch over `Top` is exhaustive with no default arm, so a new body shape fails to compile there until
  it says which references it holds. It tells a type slot (`TypeExpr`: may take a construction in place) from a slot
  that holds a name alone (a map's key, a reference's `of`, an alias's target, a parent), and walks into a type
  written in place, which stays in place.
- Nothing is named by its content: every entry has an author-written name. `DerivedName` is one method,
  `canonicalApplication`, the comparison §8.2 keys an application on (the applied-parent fold, a recursion closing into
  its declaration).
- Resolved output states the alias chain as written; the chain collapses only where `TsonSchemaCompiler` compiles an
  `ALIAS` entry. `AliasChain` is the one walk — `ParameterTypes` keeps its own loop deliberately.
- `@meta.application` goes on the **key** of exactly the declarations that close an application of an open record,
  and the linker re-attaches it (`withNameAnnotations`), imports included; it is read as stated on ingest, and an author
  writing it is refused.
- A bundled document's root holds only the vocabulary an author writes; its machinery is a member of a nested map
  and named by its path (§9): the kernel's root is `void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`,
  `iri_reference`, `regex`, `doc`, `enum`, `set` and `schema`, and everything else it declares is under one of six maps,
  `meta` (the type system), `atom` (the datatype properties and `atom.specification`), `numeric`, `unicode`, `net` and
  `binary` (the kernel's atom constructors by family); each data-format library holds its instances at its root and its
  constructors under its map (`numeric`, `temporal`, `net`, numeric.tn and net.tn adding to the kernel's), and core.tn's
  root holds only the author annotations. A Java literal naming a member is its path, `KernelNames.of("record")` for
  `"meta.record"`, `KernelNames.of(KernelNames.UNICODE, "identifier_type")`, never the bare member; one naming a root entry
  is bare (`"text"`).
- The bootstrap hand-builds exactly the pre-loaded set — the instances and the two refinements on the kernel's one
  wait cycle (§3.4, §3.4.1 rule 4) — before anything is read, readies `meta.type_inline` with it, and makes every other
  entry ready by `WaitOrder`, the one algorithm every schema's resolution runs. The order is the waits', never source
  order. `KernelBootstrapResolverTest` holds the set to the measured cycle and the output to the kernel's ordinary
  resolution in any declaration order.
- A `!C { … }` body or an annotation value against an imported entry is read through the compiled form of the schema
  that declared it (`SchemaResolver.Vocabulary`, by the origin the import merge recorded), never through one compiled
  per closure; against an entry of the same document, through readers compiled from that document's entries ready so
  far (`WaitOrder.read`). The kernel's ordinary resolution alone reads every head through its pre-loaded stand-in.
- Only a resolution in progress links with references pending or compiles with entries pending
  (`TsonSchemaLinker.linkReady`, `TsonSchemaCompiler.compileReady`, through `TsonCompiledSchemaLoader.compileReady`).
  The partial link reports nothing, each entry being judged when the whole schema links; a finished schema's link
  refuses an undeclared name.

Related: `design/schema-resolution.md` (definition resolution), `design/template-materialisation.md` and
`design/open-records.md` (the callers of `WireForm` and `DerivedName`), `design/constructor-application.md`,
`design/schema-grammar-and-desugaring.md` (sugar written in place), `design/linking-and-compilation.md`.

## Shared vocabulary: `WireForm`, `MetaRefs`, `DerivedName`

Leaf classes the phases share. Each owns a fact that belongs to none of them individually; stated inside whichever
phase happens to need it first, such a fact ends up stated more than once.

**`WireForm`** — how schema vocabulary is spelled as data: the vocabulary member names, the `plain`/`nameField`
builders every producer goes through, and `refValue`, which writes a reference as a bare token or, carrying arguments,
as a `ReferenceValue`, the data grammar's reference ([TSON-DATA] §7.4). Its producers are `SchemaDesugarer`, writing a
sugar form's binding record (a nested construction in its `{ of: … }` wrapper, `SchemaDesugarer.slotValue`), and
`ConstructionSugar`, writing an applied constructor's arguments at its slots. A quoted token is never a reference, so
a text slot holding `"box<T>"` is text whatever it spells. `TsonObjectWriter` cannot build a binding record: its
output is canonical-explicit and fully quoted, a different language from the one a binding is written in.

**`MetaRefs`** — the `schema.meta` reference walk: `mapRefs` maps an entry's chain too, `mapRefsKeepingChain` leaves
the chain as written. Its one caller is materialisation, closing an application where it stands. No pass rewrites a
use site onto the end of an alias chain — see "References are hops" below. A walk is a `Rewrite` with two arms:
`atSlot` for a reference at a type slot, which may come back as a construction written in place, and `atName` for one
at a slot that holds a name alone. Which body shape carries which references is a fact about the value model, stated
once, where the model is walked:

- A record's field types (an open record's only where they name none of its parameters), an array's element, a map's
  and a namespace type's `value_type`, a tuple's elements and a choice's variants are type slots; a type written in
  place there is walked into, each slot inside it mapped the same way.
- A map's and a namespace type's `key_type`, a reference's `of`, an alias's target and a chain's parents hold a name:
  a construction has nowhere to stand there.
- Nothing in any other atom (its facets are values), a declared type, or a value entry.

§5.10's regularity check does not use the walk: every entry with parameters is an open record, and reads its
applications off its references (`OpenEntry.applications`).

**`DerivedName`** — §8.2's canonical rendering of an application, `canonicalApplication`, which compares two
applications after their arguments' alias chains are followed: the applied-parent fold keys on it, and the
materialiser asks it whether a nested application is the one a declaration is closing. Nothing is named by it.

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
  **argument-bearing** target (an application, which names no entry), or on a cycle*
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
  applied as its target wherever it heads a body or an application (§3.3.1): over `labels => enum`, `!labels [a b]` applies
  `enum` (the definition reader follows the chain and retags the value), and the root's `set => meta.set_type` makes
  `set<text>` the construction `!meta.set_type { element_type: text }`.
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

## The derived key annotation: `@meta.application` (`tson-compiler/.../resolver/SchemaResolver.java`)

**`@meta.application`** (§6, §8.1) goes on the key of every declaration that closes an application of an open
record, the application it is the instantiation of, `@meta.application:box<int32>`, a `meta.type_ref` reference
carrying arguments, written unquoted and stated as written: nested arguments unreduced, a bare-name argument past its
alias hops (`TemplateMaterialiser.asWritten`). A construction carries none, its value stating its constructor and its
arguments. It is a fact about the entry and not about its chain -- the record does not state the head it was
substituted from -- which is why it is no field of `meta.type_chain`. The materialiser records each application in a
map it shares with `SchemaResolver` (the applied-parent fold keys on it), and `SchemaResolver` writes it where it
assembles the entry map; `TsonSchema.applicationOf` is the one accessor. **It is read as stated on ingest**: it is the
one fact the entry's value does not state. An author writing it is a resolver error
(`DefinitionResolver.refuseWrittenApplication`), as writing `chain` is.

- **Key position, per §6.** An annotation before a declared name is metadata *about the declaration*, and a resolved
  schema is a `{type_name => top}` — so the marker lands on the map's key, which `TsonSchema.entries()` keeps
  reachable through `Namespace.getAnnotations(name)`, never on the entry's value.
- **It survives linking and the import merge**: the linker rebuilds its entry map several times and re-attaches key
  annotations at the one point every entry passes through (`withNameAnnotations`), imports included.
- **It is the one derived key annotation.** A sugar form is written in place at its slot and an application of an open
  record closes only into a declaration, so every entry has an author-written name and nothing else marks a key.

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
  under the loader's identifier policy, and closes nothing; a construction's binding record is read under no policy
  (`SchemaResolver`'s `ConstructionReader`, `design/namespace-types-and-value-entries.md`). The value is bound where the
  context binds its type, and otherwise read in tree mode (the declaring schema's `treeVocabularyOf`, or a tree-mode compile
  of the entries ready so far) and held as a tree.
- **The kernel reads through itself.** Its resolution is handed the pre-loaded stand-in, which answers for every
  name, the one cycle being closed by pre-loading (§3.4).

## Kernel bootstrap (`tson-compiler/.../resolver/KernelBootstrapResolver.java`)

The kernel, meta.tn (`TsonBundledSchemas.META_ID`), is special: its header is its `!!id` alone. It holds the kernel's
one wait cycle, every name the cycle's declarations reference, closed to a fixed point, so it resolves and links on its
own, and the concepts that belong with them: `meta.map`, `meta.tuple`, `meta.choice` and `meta.tuple_element`,
`meta.application`, `meta.ref`, `meta.field_name`, `meta.disjoint`, `set`, the octet-sequence constructor
`binary.bytes_type` with `binary.bytes_encoding` and
`bytes`, and the classifying annotations `atom.ordering`, `atom.bounded`, `atom.exact` and `atom.numeric` the
data-format libraries state their types with. It is the one kernel document. Its root is the thirteen names an author
writes (`void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`, `iri_reference`, `regex`, `doc`, `enum`, `set`
and `schema`; `enum => meta.enum_type ^ { name_type?: = meta.identifier }`, so resolved output tags `!enum`, and `schema
=> meta.schema`, the alias every schema map's tag names, and `set => meta.set_type` its alias); everything else is a
member of `meta => !schema { … }`, named by its path (§9), `meta.identifier` among them. It imports
nothing, applies only its own constructors and declares no open record (§3.4's one cycle, closed by pre-loading, not
resolution); `meta.param` and `record.parameters` describe an open record and are applied by none. It is never resolved
from a document, and its names reach any other schema only by import. Every name resolves from the root (#14), so an entry
naming a sibling writes its path, and its sugar is written in place at the fields that write it. A schema importing it
alone has every sugar form and `meta.ref`. numeric.tn, temporal.tn and net.tn each import it alone and declare their
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
  kernel's one wait cycle, the one holding `meta.record`: `boolean`, `meta.field_role`, `meta.record_extension_type`,
  `meta.enum_set`, `integer`, `numeric.count`, `text`, `void`, `meta.field_type_ref`, `meta.type_ref`,
  `meta.field_map`, and the two choices a type slot is typed by, `meta.type_expr` and `meta.field_type_expr`
  (`PRE_LOADED`). A choice's own `variants` is `[meta.type_expr]`, so reading `type_expr`'s declaration reaches
  `type_expr`; the hand-built choice states the `disjoint` its `@meta.disjoint` asserts, a slot of the records on the
  cycle being read against it before the whole kernel links. `meta.field_type_ref => !meta.reference_type { of:
  meta.type }` is on the cycle because reading its `of` waits on `meta.type_ref`, its own refinement.
  `record_field.value` is typed `type!`, a dependent field, whose value a schema document holds as written through a
  reader of its own (`DependentFieldReader`), so the record waits on no entry for it. `instanceBody` covers the shapes
  those apply — a bare `{}` (each target's unconstrained constant, `void` among them), a bare token array (`enum`), a
  record of tokens (`meta.reference_type` with its `of` and an `allow_sibling` defaulting to true, `meta.map` for
  `meta.field_map`, `meta.set_type` for `meta.enum_set`, with its `min_items`/`max_items`), and a record holding a token
  array (`meta.choice`) — and refuses any other target; `refinedBody` merges the integer family's `min`, `max` and
  `size` and a reference's `of` and `allow_sibling`. The set is built before anything is read, and
  `meta.type_inline`, a record read from its grammar that reads nothing, is readied with it: a record's field written in
  place is read against `meta.type_expr`, whose untagged recovery classifies both its variants. Once every entry is
  ready the chains are closed transitively, as `SchemaResolver` closes a schema's (`completeChains`).
  `KernelBootstrapResolverTest` measures the cycle with `WaitGraph` from `spec/fixtures/meta-resolved.tn` (the six
  maps read as their members under their paths; a type written in place at a slot is no entry, and a read against it
  waits on what reading its construction reaches) and asserts the hand-built names equal it, so a kernel edit that
  moves the cycle fails there. `KernelWaitGraphTest` prints the measurement — 77 entries (64 members of the six maps
  and 13 at the root; the maps themselves make the kernel's 83 resolved entries), one cycle of 32, longest wait chain
  3 — and asserts the kernel contains the cycle's closure (68 entries) and the related concepts. The cycle grew with
  the type slots: a record field's type is read against `meta.field_type_expr`, a choice asserted disjoint, and a type
  written in place reaches every constructor's record through `meta.type`, so the choice constructor, the disjoint
  assertion, `void` and `meta.record_extension_type` are on it. Of the 32, 13 are hand-built; the other 19 are records
  and compositions resolved from their grammar.
- **A declaration no read produces is resolved from its grammar** by `DefinitionResolver` — a record, a composition,
  a refinement of a record (`meta.ref` among them), an alias — as `SchemaResolver` resolves one. The bootstrap readies these
  and
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
- **Parameters are checked after every entry is ready**, through `ParameterTypes.check` as `SchemaResolver` checks a
  schema's; a failure is a broken kernel. The kernel declares no open record, so the check finds nothing to judge.
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
  is fixed by the sugar forms and nothing is looked up in another schema. The kernel's sugar forms are the
  constructions written in place at the fields that write them, and every sugar target is the kernel's own.
- **Cost.** The two partial links and compiles make a warm bootstrap about 11 ms, and it runs once per registry:
  `loadVocabulary` builds the stand-in on the first lookup of meta.tn's identity and reuses it until the ordinarily
  resolved kernel is registered (`registerBundled`), after which the kernel's vocabulary is that registered schema's
  compile. The whole of `Tson.standard()`, all six bundled schemas resolved and registered, is about 440 ms as a
  fresh JVM's first call and 65 ms warm.
