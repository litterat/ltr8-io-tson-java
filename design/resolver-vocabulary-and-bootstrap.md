# Resolver vocabulary, reference hops, `@synthetic`, and the meta-kernel bootstrap

The facts the resolver phases share rather than own: the three leaf classes (`WireForm`, `MetaRefs`, `DerivedName`),
how a reference chain is kept in resolved output and walked, the one derived marker, and the special-cased bootstrap
that resolves meta-kernel itself. Current form only; history lives in git.

**Invariants**

- There is one `WireForm`: a held body is written by two phases and read by four, and a second opinion about what an
  application looks like is what makes one of them wrong.
- `heldRecord` builds the wire tree directly — `TsonObjectWriter` output is fully quoted, and `HeldBody.names()` and
  substitution key on a token being *unquoted*.
- Every walk over a held body descends into a map slot, key and value both.
- `MetaRefs` visits by rewriting with the identity function; there is no separate read-only walk to fall behind.
- `DerivedName`'s two families stay apart, `ofBinding` is called by **both** lift channels, and a derived name is
  asserted by value, never by `startsWith`.
- Resolved output states the reference chain as written; the chain collapses only where `TsonSchemaCompiler` compiles a
  `REFERENCE` entry. `ReferenceChain` is the one walk — `ParameterKinds` keeps its own loop deliberately.
- `@synthetic` goes on the **key** of exactly the sugar-form entries (both channels), never on an instantiation entry
  or a `TypeDefinition` value, and the linker re-attaches it (`withNameAnnotations`), imports included.
- The bootstrap is two passes over a closed `instanceBody` switch with no compiled reader, and attaches no `@synthetic`.

Related: `design/schema-resolution.md` (definition resolution), `design/template-materialisation.md` and
`design/held-template-bodies.md` (the callers of `WireForm` and `DerivedName`), `design/constructor-application.md`,
`design/schema-grammar-and-desugaring.md` (the desugar lift), `design/linking-and-compilation.md`.

## Shared vocabulary: `WireForm`, `MetaRefs`, `DerivedName`

Three dependency-free leaf classes the phases share. Each owns a fact that belongs to none of them
individually; stated inside whichever phase happens to need it first, such a fact ends up stated more than
once.

**`WireForm`** — how schema vocabulary is spelled as data, in both directions. The vocabulary member names;
the `scoped`/`nameField` builders every producer goes through; `refValue` and its inverse
`typeRefOf`/`argumentOf`; the held-record writers `heldRecord`/`heldEmptyRecord`; and §5.10 `substitute` over
a held body.

- **Why one class and not a producer beside one phase and a consumer beside another.** A held body is written
  by two phases (`SchemaDesugarer` lifting a sugar form, `DefinitionResolver` holding a composition or
  refinement template) and read by four (`TemplateMaterialiser` closing one, `HeldBody` answering §5.10's
  declaration-time questions, `SyntheticMerge` asking whether one holds an application, `ParameterKinds`
  walking one for parameter kinds). `isApplication`'s own contract is that a held body is written by one
  phase and read by several, so a second opinion about what an application looks like is what makes one of
  them wrong. The writer, the reader and `ParameterKinds` all go through it; a walker matching
  `name`/`arguments` against its own string literals is the copy that drifts silently, since nothing fails,
  only a parameter kind quietly not inferred.
- **`TsonObjectWriter` cannot *build* a held body, though it is what emits one.** Writing a resolved `Top`
  gives canonical-explicit, fully quoted output — a different language from the one a held body is written in:
  `HeldBody.names()` and substitution both key on a token being *unquoted*, so a quoted body references no
  parameters at all. That is why `heldRecord` builds the wire tree directly. Once built, the tree is an
  AST, and `TsonObjectWriter` writes an AST as syntax rather than as a description of itself
  (`AstWriter`) — which is how `HeldBody.held` turns it into `TemplateBody.template`.
- **`refValue`'s `arguments().isEmpty()` branch is load-bearing**, not an optimisation — see the
  materialisation note (`design/template-materialisation.md`).
- **Every walk over a held body descends into a map slot.** meta.tn's
  `scoped.schemas` is `{uri => [type_name; 1..]?; 1..}`, so core's `extern_of => <S> !scoped { scope:
  [EXTERN]  schemas: { S => _ } }` and `extern_type => <S, T> ... { S => [T] }` put a parameter inside a
  map — one in a key, one inside the array its value names. Each of the three walks fails differently if it
  skips one: `substitute` leaves the parameter name standing where the argument belongs; `ParameterKinds`
  never observes the parameter, so its kind is never inferred and a `type_name` argument stays on the
  reference channel and fails as an unresolved reference; and `DerivedName`'s canonical rendering — the half
  §8.2 keys identity on — renders the whole map as the unknown-value mark, so two bindings differing only
  inside one hash alike while the readable half masks it, which is the `startsWith` hazard inverted and why
  `DerivedNameTest` asserts `canonicalBinding` directly. A map key is a `data-value` and its
  value a `scoped-value` ([TSON-DATA] §2.6), so the two halves rebuild through their own carriers
  (`WireForm.rescope` and `WireForm.retyped`); both halves descend, because a parameter reaches either.

**`MetaRefs`** — the `schema.meta` reference walk, `mapRefs` over a definition and `mapBodyRefs` over a body.
Three callers use it and only one is closing a template: materialisation rewrites an application onto the entry it
closed, §8.2's synthetic merge renames onto a merged entry, and §5.10's regularity check uses it as a
*visitor* by returning each reference unchanged. No pass rewrites a use site onto the end of a reference
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
wrote: a type position naming a `REFERENCE` entry keeps that name, nothing is attached to record where it
"really" points, and the chain stays walkable through the entries themselves.

**A processor collapses the chain when it compiles readers** — after linking, once per entry, where the whole
namespace is present. `TsonSchemaCompiler`'s reference branch is that moment: a `REFERENCE` entry's reader
*is* its target's reader, resolved recursively, named for the entry doing the referring, so a use site naming
`pct` over `pct => small` reads and reports as `pct`.

- **The walk is unavoidable, which is why a rewrite is not worth its price.** §8.3 requires the chain stay
  walkable (`reference.target` is never flattened), and several passes walk one. Rewriting the output as well
  would leave two representations to keep in step, and an `@alias` marker recording where a rewritten site
  pointed is a *lossy* summary of the one it duplicates — it keeps only the source-site name, so in
  `digest_chain => digest_alias => bytes` it records the hop that carries nothing.
- **`ReferenceChain` is that walk, stated once** (`resolver/ReferenceChain.java`). The linker's choice-variant
  distinctness and its §5.2 field-value check, `Subsumption`'s subtype naming and `DiscriminationClass`'s
  classification all use it, because the one decision inside — *stop at a non-reference, at an
  **argument-bearing** target (an application, with no entry until materialisation mints one), or on a cycle*
  — kept in four loops is four decisions that can drift. `terminal` answers with a name, `terminalDefinition` with the entry;
  they differ only on an undeclared name and a cycle, where the first has an answer its caller wants (a type
  parameter is its own terminal) and the second has none. **`ParameterKinds` keeps its own loop deliberately**:
  it follows a chain to a slot's declared body and must *not* stop at an argument-bearing target, the template
  being the answer there. `ReferenceChainWalkTest` pins all four stops.
- **Anything that needs the chain end walks it and says so.** `TsonSchemaLinker.checkFieldValue` walks to the
  terminal before checking a `~`/`=` value, since a field typed by an alias states a value of whatever the
  alias names; `FieldValueConformanceTest` pins both directions.
- **§8.3 states both halves, and the walkers are several.** A processor MAY collapse after linking, when it
  compiles for reading, and MUST NOT collapse in resolved output. The compiler, `DiscriminationClass`,
  `TypeInhabitance` and the linker each walk a chain. A directive on an alias is applied where the alias compiles
  (`UseSite.named`, applied by the
  reference entry's own compile).
- **The bootstrap route needs no special case.** Neither it nor ordinary resolution rewrites a reference, so
  the two cannot diverge on one (`BootstrapReferencesTest`).
- Pinned by `ReferenceChainTest` — the chain stated as written, a read still reaching the end of it, and a
  diagnostic naming the hop the author wrote — and end to end by `ResolvedFixtureTest` against the spec's own
  `spec/m/*-resolved.tn`.

## The `@synthetic` marker (`tson-compiler/.../resolver/SchemaResolver.java`)

§8.2 puts a bare **`@synthetic` on the key of every entry the resolver materialised from a sugar form**, and
on no other. It is *derived* — attached by the resolver rather than written by an author, and discarded and
recomputed on ingest (§8.1), so it carries no decode force and cannot be forged into a resolved document to
change how it reads. It is built by name rather than resolved through the governing
meta the way an author-written annotation is: there is no author to resolve against, and the value is fixed.

- **Why a marker at all, when the names are distinctive.** A synthetic is named by derivation from its own
  content, but §8.2 makes that spelling non-normative — an implementation picks its own — so pattern-matching
  the name is not a way to recognise one. Without the marker a consumer of resolver output cannot tell a
  materialised entry from a declared one, which is what folding these entries back into the nested form the
  author wrote depends on.
- **Key position, per §6.** An annotation before a declared name is metadata *about the declaration*, and a
  resolved schema is a `{type_name => type_definition}` — so the marker lands on the map's key, which
  `TsonSchema.entries()` keeps reachable through `AnnotatedMap.getAnnotations(name)`, never on the
  `TypeDefinition` value. §6 forbids hoisting between the two positions and nothing here does.
- **Marked: exactly the synthetic entries, from both channels.** The desugar lift produces them and
  `SchemaDesugarer.lifted` names them as the document's own set difference
  (`design/schema-grammar-and-desugaring.md`); materialisation produces more of the same kind when it closes an
  open synthetic, and `TemplateMaterialiser.syntheticNames()` reports which of its minted entries those are.
  A declaration's own sugar body is **not** one: `tag_list => [text; 1..2]` *is* the construction, not a lift
  of one (§5.3).
- **`SchemaResolver` attaches it where it assembles the entry map**, from those two mint sites — the desugar lift
  (`SchemaDesugarer.lifted`, the document's own set difference) and materialisation closing an open synthetic
  (`TemplateMaterialiser.syntheticNames`). It is the one derived marker (§8.1).
- **Unmarked: instantiation entries.** §8.2 draws that line itself — "the two families are distinguishable
  (an instantiation's `source` is an application; a synthetic's is a bare constructor), and only synthetics
  are the fold-back-into-display case the marker serves." Marking too much would be as wrong as marking too
  little, so `SyntheticEntryMarkerTest` asserts both halves.
- **It survives linking and the import merge**, which is where a key-side fact is easiest to lose: the linker
  rebuilds its entry map several times and re-attaches key annotations at the one point every entry passes
  through (`withNameAnnotations`), imports included, so an imported synthetic keeps the marker its own schema
  gave it.
- **The bootstrap route attaches none, deliberately.** `MetaKernelBootstrapResolver` exists to be *just*
  enough to load the real meta-kernel from its own file, and nothing in the pipeline reads this marker — it is
  informational. meta-kernel's own eight synthetics are marked anyway, because the entries anything else sees come from
  ordinary resolution: the bootstrap output stands in only as the transient governing meta for its own
  resolution.
- Cross-checked against the spec's own output by
  `ResolvedFixtureTest.theSameEntriesAreMarkedSyntheticOnBothSides` — eight keys in meta-kernel, five in
  meta.tn, none in core.tn, which writes no inline form. It reads the fixtures' marked keys from their
  *text*, because a key-position annotation is still dropped when a resolved-form document is read back
  (`BACKLOG.md`), and a bound comparison would have both sides render nothing and agree for the wrong reason.

## Meta-kernel bootstrap (`tson-compiler/.../resolver/MetaKernelBootstrapResolver.java`)

Meta-kernel is special: its own `!!meta` names *itself* (§1.5's one deliberate circularity, closed by
pre-loading, not resolution). Ordinary resolution can't bootstrap it — resolving a constructor application
needs that constructor's vocabulary already known, and every constructor meta-kernel uses is defined
within meta-kernel.

`MetaKernelBootstrapResolver.getMetaKernelSchema()` (its only public method) produces the resolved
meta-kernel `TsonSchema` in **two passes** over its declarations: non-`Instance` declarations first
(ordinary `DefinitionResolver`), then the deferred `Instance` declarations (`value => !unit {}`, `boolean
=> !enum [true false]`, …) once every constructor they reference — including ones declared later in the
file — has an entry to transfer a kind from. `TsonSchemaResolver` alone is single-pass, strict source
order, so it can't handle `boolean` preceding `enum`; this two-pass ordering lives here.

- **Constructor-application binding goes through a closed `instanceBody` switch, not the generic path.**
  Meta-kernel instantiates constructors in exactly three shapes — a bare `{}` (each target's
  `UNCONSTRAINED` constant), a bare token array (`enum`, via `toEnumBody` reading `TokenValue.text()`
  directly), and the binding record `SchemaDesugarer` emits for an `array`/`set`/`map` application. No
  compiled reader is involved.
- **Why even a compiled-reader bootstrap can't read meta-kernel from its own in-progress state:**
  `integer_size => { bits: ... signed: boolean }` is a first-pass entry whose `signed` field already
  references `boolean`, which the *second* pass resolves — so there is no moment at which a reader could be
  compiled against a complete schema. Given how narrow and fixed meta-kernel's instance shapes are,
  hand-picking them is simplest — "the bootstrap can do whatever tricks it needs, including not compiling,
  just calling `new Xxx(...)`."
- **Desugaring needs no equivalent trick.** The container constructors are parameterless (§4.2), so the
  desugar table is fixed by the sugar forms and nothing is looked up in the governing meta — which for
  meta-kernel would be the entries this class is in the middle of producing. The bootstrap and the general
  case are one mechanism, and meta-kernel's linked form needs no materialization either: its eight sugar
  forms are ordinary declarations by the time the linker sees them.
