# Externs

Design notes for `!meta.extern` ([TSON-SCHEMA] §5.13): one entry of another schema under a local name, a dependency
loaded with the imports whose names never enter the namespace, and a value read, bound and written through one
(§7.9). The JSON stack is the gap (`BACKLOG.md`). Current form only; history lives in git.

**Invariants**

- **An extern is found by its written head** (`Externs.written`, nested maps included), so every extern's schema
  loads right after the imports merge, before any declaration is made ready. A head reaching `meta.extern` through
  an alias is refused: only the written head is collected, and an alias that resolved would build an extern whose
  schema nothing loaded. An import-free schema's own `meta.extern` is its own entry, and loads nothing.
- **The foreign schema loads as an import does** (`resolveLinkedForExtern`): same source, policy, per-identity digest
  and cycle guard. A failure refuses the importer whole, prefixed with the extern, and keeps its classification.
- **The cycle guard knows its edges.** `TsonCompiledVocabularyRegistry.resolving` maps each identity in flight to the
  extern that reached it (null for an import), so a cycle over imports and externs together is one check, its path
  marking each extern edge.
- **One kernel is judged by identity, before a fetch.** A closure reached through an extern edge that names a kernel
  of another revision (`TsonBundledSchemas.isKernelOfAnyRevision`) is refused there, naming both kernels: this
  library pre-loads its own kernel alone, and the other would fail to resolve as an ordinary document with a message
  about its grammar.
- **What an extern lands on is a fact of the link, never of the value.** `Extern` holds what was written;
  `TsonLinkedSchema.externs` maps each extern to its `ExternTarget`, merged through imports as `entryOrigins` is. A
  refused extern stays unattached and lands nowhere, so a use of it reports nothing further.
- **Every judgement through the hop compares the landing by identity** (`ExternHops`): the declaring schema and path
  at the end of alias hops and further externs. A choice's distinct variants, IS-A, a reference's membership (the
  foreign entry's constructor or one on its chain is `of`, by identity), the disjointness class (classified in the
  namespace holding the entry) and a value name's type all go through it. `KindMembers` alone treats an extern as no
  member, since its own construction is `meta.extern`, IS-A `meta.top`: only a caller holding the link can judge it.
- **A foreign schema importing nothing writes the processor's kernel's forms** (§3.3.1), so a constructor its closure
  lacks is looked up, by identity, in the importer's namespace, which holds that kernel.
- **Sealed is one wording** (`Externs.sealed`), raised wherever the operation is first seen.

**Reading through an extern** (`ExternReader`, built in `TsonSchemaCompiler.Compilation.boundary`)

- **The body is read by a foreign schema's own compiled reader**, so nothing re-resolves a name per scope. Each
  foreign schema compiles once per identity and mode, through the read registry's cache or, for a checkpoint, the
  core's cache for that mode (`TsonSchemaCompiler.ForeignSchemas`). A miss compiles outside any lock and `putIfAbsent`s: the
  compile reaches the same cache again for each foreign schema, which `computeIfAbsent` cannot.
- **The boundary consumes the tag and the annotations in front of the value, in the enclosing scope, and hands a
  foreign reader the value untagged**, read as the entry the tag reached. Handing it a foreign name instead refuses
  what the boundary admitted wherever the target takes no tag of its own name: a choice, a member-dispatched base.
- **What a tag may name is fixed at compile, by identity** (`ExternAdmission`, which the writer's tags read too, so
  the two admit one set): any name of the enclosing scope, extern or not, that
  reaches past its aliases and externs the target, an entry IS-A it, or a variant of it where it is a choice. A name
  reaching an entry no extern reaches is read where the position's foreign schema reads it, if that namespace holds
  the entry, else in the schema declaring it. An abstract target naming no selectors is refused at the boundary, so
  the message names the subtypes this scope can write rather than the foreign schema's.
- **The scope is the cursor's, for the one call that reads the value** (`ScopeCrossing`), not a field of every
  context copy, which would be bytes on every read. The schema location restarts inside, and a schema document's
  sink hears nothing there, the names being judged by the foreign readers rather than recorded for this link.
- **A tree value at the boundary carries the outer name** (the tag written, else the extern), so the writer writes
  back a tag the enclosing scope admits.
- **Bind mode needs nothing of its own inside**: every class is asked of the binder by its entry's identity, the
  declaring schema's canonical identity and the name (`ValueReaderContext.namespaceOf`), so two schemas' `card` bind
  apart wherever the binder routes them (`DataNameBinder.byNamespace`). An annotation through an extern in a schema
  document binds where the context binds the foreign classes and is a tree where it does not, as a value entry is.
- **The annotations in front of the boundary reach a bound record through its carrier** (`ExternReader.carrying`):
  the value crossed unannotated, so the bound record is rebuilt once with them, and only where some were written. A
  class with no carrier drops them, as a record's reader does, the boundary having checked them.

**Writing through an extern** (`writer.ExternTags`, from `TsonObjectWriter.describing(linked, root)`)

- **Only a tag is the enclosing scope's**; the body is written as the class writes it, the foreign schema's names. A
  union member at a boundary is tagged with the enclosing scope's name for the entry its class is bound to: the
  extern for the target, an admitted name for a subtype or a variant. A class bound to no admitted entry is a
  `WriteException`, never the foreign name, which the boundary would refuse.
- **A class maps to its entry by asking the binder forward**, for each admitted entry, by declaring schema and name:
  the write inverts what a read binds. Built once per derived writer over every schema the externs reach; a write
  looks up its record class and component and allocates nothing.

**Decisions that lost**

- **`@Typename.namespace` naming a class's entry when writing**: a second authority beside the binder, which the read
  cannot consult (no index runs from an annotation to its class). It stays unread.
- **Handing the boundary's annotations to the foreign reader through the context**: bytes on every context copy.
- **Tags derived from `describing(uri, root)`**: a fetch, or a failure, at derivation for a schema never loaded.

- **Compiling the foreign schema inside the importer's compile**: once per importer and per checkpoint.
- **A `scope` component on `Diagnostic`**: `schemaId` already names the schema whose names were read; the chain of
  externs is how the read got there, prose for a human.
- **A target on the model record** (`@Unbound Optional<Target>` on `Extern`): it puts a link fact on a body, where
  `schema.meta`'s equality and the binder's component analysis meet it; the binder then has to be taught to skip an
  unbound component's type. The fact map on `TsonLinkedSchema` is where the other link facts live.
- **Loading the foreign schema when the importer links**: too late for a value read at a checkpoint that reaches an
  extern, and it leaves the cycle guard blind to the extern edge.
- **Comparing across the boundary by spelling**: exact only while both closures hold one kernel and share no other
  schema; identity costs one walk.
- **Wrapping a nested failure once**: a cycle through several externs reports each extern it passed through, the
  trail of how the importer reached it; nothing distinguishes an already-prefixed message without parsing it.
