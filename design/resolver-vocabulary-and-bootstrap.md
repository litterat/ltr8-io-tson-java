# Resolver vocabulary, alias hops, derived key annotations, checkpoints and the kernel bootstrap

The facts the resolver phases share rather than own: `WireForm`, `CanonicalApplication` and the model's `TypeSlots`;
how an alias chain is kept and walked; `@meta.application` and `@meta.completion`; the checkpoints every schema
document's values are read at; and `KernelBootstrapResolver`, which resolves meta.tn itself. The class Javadoc of each,
and of `WaitOrder` and `SchemaResolver.Vocabulary`, carries the mechanism.

**Invariants**

- **One `WireForm`**: a binding record is written by the desugarer and by construction sugar, and a second opinion
  about what a reference or an application looks like in a slot is what makes one of them wrong. A parametric record
  is no binding record: it is built on the model and substituted there (`Substitution`).
- **Nothing is named by its content**: every entry has an author-written name. A sugar form is written in place at
  its slot and an application of a parametric record closes into the declaration naming it or in place, so
  `@meta.application` and `@meta.completion` are the only derived key annotations. `CanonicalApplication.of` compares
  an application as a value, head and arguments past their alias hops, never as text.
- **Resolved output states the alias chain as written** (§8.3): no pass rewrites a use site onto the end of a chain,
  and the chain collapses only where `TsonSchemaCompiler` compiles an alias entry, its reader its target's named for
  the alias, so `pct` over `pct => small` reads and reports as `pct`. Anything needing the end walks it and says so
  (`TsonSchemaLinker.checkFieldValue` before a `~`/`=` value). `AliasChain` is the one walk; `ParameterTypes` keeps
  its own loop deliberately. The bootstrap rewrites no reference either, so the two routes cannot diverge on one
  (`BootstrapReferencesTest`); `AliasChainTest` and `ResolvedFixtureTest` pin the output.
- **Where a declared entry might stand, a chain is walked to it.** An application through an alias of a parametric
  record applies the parametric record, so over `bx => box` the applications `bx<text>` and `box<text>` are one
  (`ParametricMaterialiser.parametricHead`); an alias naming a parametric record bare is no unapplied use of it. An
  atom refinement's source written as an alias refines the instance at the end (`resolveAtomRefinement`). An
  annotation is identified by every entry on its chain, not its end (`AliasChain.annotates`/`passesThrough`):
  `@meta.disjoint` reaches `void`, and the linker asks whether `meta.disjoint` is on the way. An alias of a
  constructor applies its target (§3.3.1); an alias of an incomplete entry is another name for it, so the root's
  `set => meta.set_type` makes `set<text>` the completion of `meta.set_type`.
- **`@meta.application` goes on the key of exactly the declarations closing an application of a parametric record,
  `@meta.completion` on the key of exactly those completing an incomplete entry** (§6, §8.1). Key, because an
  annotation before a declared name is about the declaration; not a `meta.type_chain` field, because it is a fact
  about the entry, the record not stating the head it was substituted from. The linker re-attaches both at the one
  point every entry passes through (`withNameAnnotations`), imports included; each is read as stated on ingest, and an
  author writing either is refused (`DefinitionResolver.refuseDerivedAnnotation`). `@meta.completion`'s names are
  text, not `meta.type_ref`: the incomplete entries are in no resolved form, and a reference would dangle at link.
- **A bundled document's root holds only the vocabulary an author writes; its machinery is a member of a nested map,
  named by its path** (§9). A Java literal naming a member is its path, `KernelNames.of("record")` for
  `"meta.record"`, never the bare member; one naming a root entry is bare (`"text"`).
- **Every value a schema document reads is read at a checkpoint of that document** — its imports and its entries
  ready so far, each pending declaration a `PendingTypeReader` — in its own namespace, annotation names included
  (§3.3.3). A read is atomic within one checkpoint: one reaching what its readers lack stops
  (`PendingTypeReader.NotReady`) and reruns once the entry is ready, so its verdict is its verdict in the full closure
  (`CheckpointSoundnessTest`). A reference and a held value order nothing (§3.4.1 rule 3): held as written, judged
  at link.
- **The stops are choke points, not refusal sites**: a declaration by position at its `PendingTypeReader`, a tag at
  `EventSkip.typeRefAhead`, an annotation's name in `AnnotationCapture`, a pin no held member states in
  `RecordMemberDispatchReader` (waiting on every declaration `FamilyJoiners` says may be the member). A hook before
  each refusal could not be shown complete, and a new reader would have to remember it.
- **Readers outlive the checkpoint they were compiled at**, because every fact they take from the closure is reached
  by a name or a pin at which a read stops; they are dropped once every entry is ready (`WaitOrder.settled`), when
  materialisation starts rewriting entries in place. A checkpoint's compile defers what may depend on what it lacks:
  a bind mismatch is the read's that reaches the entry, and so is a class its context cannot describe, the
  consumer's class failing no resolution. The restart state is `WaitOrder`'s, one per `resolveSchema`
  call; nothing is cached in the loader.
- **Only a resolution in progress links with references pending or compiles with entries pending**
  (`TsonSchemaLinker.linkReady`, `TsonSchemaCompiler.compileReady`). The partial link reports nothing, each entry
  being judged when the whole schema links; a finished schema's link refuses an undeclared name.
- **The bootstrap hand-builds exactly the pre-loaded set** (`KernelBootstrapResolver.PRE_LOADED`: the instances and
  the two refinements on the kernel's one wait cycle, §3.4, §3.4.1 rule 4) and makes every other entry ready by
  `WaitOrder`, the algorithm every schema runs. `KernelBootstrapResolverTest` measures the cycle with `WaitGraph` from
  `spec/fixtures/meta-resolved.tn` and holds the set to it, so a kernel edit that moves the cycle fails there, and
  `theBootstrapIsTheSameInAnyOrder` holds the output to the kernel's ordinary resolution. A pre-loaded completion
  (`boolean => !enum […]`) is merged on the written form, since resolving its incomplete entry waits on the cycle.
- **The kernel judges kinds and constructors with a predicate admitting every name; every other schema by each name's
  origin** (`TsonBundledSchemas.isKernel`, meta.tn alone), so a member a user schema contributes to `meta` is no
  kernel entry. The kernel's ordinary resolution alone reads every head through its pre-loaded stand-in.

**Decisions that lost**

- A use-site flattening pass, or an `@alias` marker recording where a rewritten site pointed: §8.3 keeps the chain
  walkable anyway, so a rewrite is a second representation to keep in step, and the marker is a lossy summary of the
  chain (in `digest_chain => digest_alias => bytes` it records the hop that carries nothing).
- Dropping the readers on every join: it needs no argument about what readers take from the closure, and it is
  quadratic, 21 s against 39 ms on 2,000 payloads.
- Reading through the declaring schema's compile: a snapshot of another closure, where a position admitting
  subtypes admits fewer (§3.3.4's open `subtypes`) and an annotation names fewer types.
- A `@meta.completion` chain naming the incomplete entry: it made one type two entries by spelling
  (`design/constructor-application.md`).

Related: `design/schema-resolution.md` (definition resolution, wait order), `design/parametric-materialisation.md`
and `design/parametric-records.md` (the callers of `WireForm` and `CanonicalApplication`),
`design/constructor-application.md`, `design/schema-grammar-and-desugaring.md` (sugar written in place),
`design/linking-and-compilation.md`, `design/compiled-registries.md` (the kernel's cached stand-in).
