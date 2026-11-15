# Externs

Design notes for `!meta.extern` ([TSON-SCHEMA] §5.13): one entry of another schema under a local name, a dependency
loaded with the imports whose names never enter the namespace. The schema side only; reading through one is a gap
(`BACKLOG.md`, "Externs"). Current form only; history lives in git.

**Invariants**

- **An extern is found by its written head.** `Externs.written` reads `!meta.extern` off the parsed document,
  nested maps' members included, so `SchemaResolver.loadExterns` resolves every extern's schema right after the
  imports merge and before any declaration is made ready. A head reaching `meta.extern` through an alias is refused
  (`DefinitionResolver.resolveConstructorTarget`): only the written head is collected, and an alias that resolved
  would build an extern whose schema nothing loaded. The head counts only where the closure holds the kernel: an
  import-free schema's own `meta.extern` is its own entry, and nothing is loaded for it.
- **The foreign schema loads as an import does**, through `TsonCompiledSchemaLoader.resolveLinkedForExtern`: the same
  source, policy, per-identity digest and in-flight cycle guard. A failure refuses the importer whole, as an import's
  does, its message prefixed with the extern that reached it, and keeps its classification: an unavailable schema
  stays a `SchemaFetchException`, a pin mismatch a `ContentHashMismatchException`, a policy refusal its code.
- **The cycle guard knows its edges.** `TsonCompiledVocabularyRegistry.resolving` maps each identity in flight to the
  extern that reached it (null for an import), so a cycle over imports and externs together is one check, its path
  marking each extern edge.
- **One kernel is judged by identity, before a fetch.** A closure reached through an extern edge that names a kernel
  of another revision (`TsonBundledSchemas.isKernelOfAnyRevision`) is refused there, naming both kernels: this
  library pre-loads its own kernel alone, and the other would fail to resolve as an ordinary document with a message
  about its grammar.
- **What an extern lands on is a fact of the link, never of the value.** `Extern` holds `schema` and `type` as
  written; `TsonLinkedSchema.externs` maps each extern of the namespace to its `ExternTarget`, merged through imports
  as `entryOrigins` is, so an importer of a schema with externs has the extern names and nothing of the foreign
  namespace. A refused extern (`TsonSchemaLinker.attachExterns`, §5.13's refused targets) stays unattached and lands nowhere,
  so a use of it reports nothing further.
- **Every judgement through the hop compares the landing by identity** (`ExternHops`): the declaring schema and path
  at the end of alias hops and further externs. A choice's distinct variants, IS-A, a reference's membership (the
  foreign entry's constructor or one on its chain is `of`, by identity), the disjointness class (classified in the
  namespace holding the entry) and a value name's type all go through it. `KindMembers` alone treats an extern as no
  member, since its own construction is `meta.extern`, IS-A `meta.top`: only a caller holding the link can judge it.
- **A foreign schema importing nothing writes the processor's kernel's forms** (§3.3.1), so a constructor its closure
  lacks is looked up, by identity, in the importer's namespace, which holds that kernel.
- **Sealed is one wording** (`Externs.sealed`), raised wherever the operation is first seen: `&`, `-`, `^` and a
  construction head in `DefinitionResolver`, an application head in the desugarer, the materialiser and the linker's
  arity check, a default or pin in `checkFieldValue`. A qualified name walking through an extern is refused in
  `ValuesByName`, as is an extern at a slot naming values.

**Decisions that lost**

- **A target on the model record** (`@Unbound Optional<Target>` on `Extern`): it puts a link fact on a body, where
  `schema.meta`'s equality and the binder's component analysis meet it; the binder then has to be taught to skip an
  unbound component's type. The fact map on `TsonLinkedSchema` is where the other link facts live.
- **Loading the foreign schema when the importer links**: too late for a value read at a checkpoint that reaches an
  extern, and it leaves the cycle guard blind to the extern edge.
- **Comparing across the boundary by spelling**: exact only while both closures hold one kernel and share no other
  schema; identity costs one walk.
- **Wrapping a nested failure once**: a cycle through several externs reports each extern it passed through, the
  trail of how the importer reached it; nothing distinguishes an already-prefixed message without parsing it.
