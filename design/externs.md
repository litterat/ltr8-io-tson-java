# Externs

`!meta.extern` ([TSON-SCHEMA] §5.13, §7.9): one entry of another schema under a local name, loaded with the imports,
and a value read, bound and written through it in both encodings.

**Invariants**

- **An extern is found by its written head** (`Externs.written`, nested maps included), so every extern's schema
  loads right after the imports merge, before any declaration is made ready. A head reaching `meta.extern` through
  an alias is refused: only the written head is collected, and an alias that resolved would build an extern whose
  schema nothing loaded. An import-free schema's own `meta.extern` is its own entry, and loads nothing.
- **The foreign schema loads as an import does** (`resolveLinkedForExtern`): same source, policy, per-identity digest
  and cycle guard. A failure refuses the importer whole, prefixed with the extern, and keeps its classification.
- **The cycle guard knows its edges.** `TsonCompiledVocabularyRegistry.resolving` maps each identity in flight to the
  extern that reached it (null for an import), so a cycle over imports and externs together is one check, its path
  marking each extern edge. A document held as text is in flight under its own `!!id` (`resolveAndLink`): a
  reference reaching it is the cycle, never a fetch.
- **One kernel is judged by identity, before a fetch.** A closure reached through an extern edge that names a kernel
  of another revision (`TsonBundledSchemas.isKernelOfAnyRevision`) is refused there, naming both kernels: this
  library pre-loads its own kernel alone, and the other would fail as an ordinary document, about its grammar.
- **What an extern lands on is a fact of the link, never of the value.** `Extern` holds what was written;
  `TsonLinkedSchema.externs` maps each extern to its `ExternTarget`, merged through imports as `entryOrigins` is. A
  refused extern stays unattached and lands nowhere, so a use of it reports nothing further.
- **Every judgement through the hop compares the landing by identity** (`ExternHops`): the declaring schema and path
  at the end of alias hops and further externs. A choice's distinct variants, IS-A, a reference's membership, the
  disjointness class (classified in the namespace holding the entry) and a value name's type all go through it.
  `KindMembers` alone treats an extern as no member, since its own construction is `meta.extern`, IS-A `meta.top`:
  only a caller holding the link can judge it.
- **A foreign schema importing nothing writes the processor's kernel's forms** (§3.3.1), so a constructor its closure
  lacks is looked up, by identity, in the importer's namespace, which holds that kernel.

**Reading through an extern** (`ExternReader`, built in `TsonSchemaCompiler.Compilation.boundary`)

- **The body is read by a foreign schema's own compiled reader**, so nothing re-resolves a name per scope. Each
  foreign schema compiles once per identity and mode, through the read registry's cache (in JSON,
  `JsonCompiledSchemaRegistry`'s) or, for a checkpoint, the core's cache for that mode (`ForeignSchemas`). A miss
  compiles outside any lock and `putIfAbsent`s: the compile reaches the cache again, which `computeIfAbsent` cannot.
- **The boundary consumes the tag and the annotations in front of the value, in the enclosing scope, and hands a
  foreign reader the value untagged**, read as the entry the tag reached. Handing it a foreign name instead refuses
  what the boundary admitted wherever the target takes no tag of its own name: a choice, a member-dispatched base.
- **What a tag may name is fixed at compile, by identity** (`tson-schema`'s `ExternAdmission`, a link fact both
  encodings' boundaries and the writer's tags read): any name of the enclosing scope, extern or not, reaching past its
  aliases and externs the target, an entry IS-A it, or, where it is a choice, a variant or an entry IS-A exactly one.
  A choice with an extern variant tallies that variant's names from the same admission, in one place for both
  encodings (`ChoiceSelection`): reaching the target names the variant, IS-A it is a subtype, and selecting a variant
  of a choice target is neither, IS-A being no relation to a choice (§5.4). A name reaching an entry no extern
  reaches is read where the position's foreign schema reads it, if that namespace holds the entry, else in the schema
  declaring it. An abstract target naming no selectors is refused at the boundary, so the message names the subtypes
  this scope can write rather than the foreign schema's.
- **The scope is the cursor's, for the one call that reads the value** (`ScopeCrossing`, in both encodings), not a
  field of every context copy, which would be bytes on every read. The schema location restarts inside, and a schema
  document's sink hears nothing there, the names being judged by the foreign readers rather than recorded for this
  link.
- **A tree value at the boundary carries the outer name** (the tag written, else the extern), so the writer writes
  back a tag the enclosing scope admits.
- **Bind mode needs nothing of its own inside**: every class is asked of the binder by its entry's identity
  (`ValueReaderContext.namespaceOf`), so two schemas' `card` bind apart (`DataNameBinder.byNamespace`). An annotation
  through an extern in a schema document binds where the foreign classes are bound and is a tree where not.
- **The annotations in front of the boundary reach a bound record through its carrier** (`ExternReader.carrying`),
  rebuilt once where some were written; a class with no carrier drops them, the boundary having checked them.

**Reading through an extern in JSON** (`tson-json`'s `reader.ExternReader`, [TSON-JSON] §8.6)

- **One object carries both scopes, so the boundary takes its `$type` off it.** The inline form crosses with its
  leading `$type` consumed and its opening put back (`JsonReadContext.CROSSING`), the foreign reader reading the rest
  untagged; the wrapper's `$value` crosses as it stands, so a `$type` inside it is the foreign schema's.
- **A dispatcher that matched an extern's name hands it the whole annotation object** (`ExactReader.takesWrapper`):
  handed the `$value`, the boundary would read its leading `$type` in the enclosing scope.
- **A question about a type's shape follows the extern** (`AliasChain.landed`): a choice's class table, a map key's
  form, an identifier element. Asked of the `Extern` body, each answers as for no type.

**Writing through an extern** (`writer.ExternTags`, from `TsonObjectWriter.describing(linked, root)`)

- **Only a tag is the enclosing scope's**; the body is written as the class writes it, the foreign schema's names. A
  union member at a boundary is tagged with the enclosing scope's name for the entry its class is bound to: the
  extern for the target, an admitted name for a subtype or a variant. A class bound to no admitted entry is a
  `WriteException`, never the foreign name, which the boundary would refuse.
- **A class maps to its entry by asking the binder forward**, for each admitted entry, by declaring schema and name:
  the write inverts what a read binds.

**Decisions that lost**

- **Admission stated in each encoding**: two copies of a judgement made of the link alone, which names no reader.
- **Teaching the foreign record reader to skip an outer `$type`**: it would have to know which scope it is in.
- **`@Typename.namespace` naming a class's entry when writing**: a second authority beside the binder, which the read
  cannot consult (no index runs from an annotation to its class).
- **Handing the boundary's annotations to the foreign reader through the context**: bytes on every context copy.
- **Tags derived from `describing(uri, root)`**: a fetch, or a failure, at derivation for a schema never loaded.
- **Compiling the foreign schema inside the importer's compile**: once per importer and per checkpoint.
- **A `scope` component on `Diagnostic`**: `schemaId` already names the schema whose names were read.
- **A target on the model record** (`@Unbound Optional<Target>` on `Extern`): a link fact on a body, which
  `schema.meta`'s equality and the binder's component analysis then meet. Link facts live on `TsonLinkedSchema`.
- **Loading the foreign schema when the importer links**: too late for a value read at a checkpoint that reaches an
  extern, and it leaves the cycle guard blind to the extern edge.
- **Comparing across the boundary by spelling**: exact only while both closures hold one kernel and share no other
  schema; identity costs one walk.
