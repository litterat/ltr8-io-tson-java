# Class 2 compilation

Design notes for `TsonSchemaCompiler` and the compiled reader stack it wires: eager compilation, `CompiledReaders`,
`ErrorReader`, template entries, the two compile and two output modes, §7.2's subsumption guard, and untagged labelled
choices. Current form only; history lives in git.

**Invariants**

- `TsonTypeReader<T>` is strictly one method, `T read(TsonReadContext)`; framing and error policy live elsewhere.
- Compilation is eager: every entry is walked, and a `RuntimeException` building one becomes an `ErrorReader`.
- `CompiledReaders` is rebound exactly once, from the in-progress `Compilation` to the finished `TsonCompiledSchema`;
  never hand readers `Compilation::resolve`.
- `ErrorReader` reports `NOT_IMPLEMENTED` and skips the value; `MissingBindingException` is thrown from it unwrapped,
  and a `BindMismatchException` fails the compile.
- The read mode is which factory registry you hold; `ValueReaderFactoryResolver` stays in the unexported `reader` package.
- The subsumption guard follows the body, not `kind()`, admits an entry's aliases as the entry itself, and is transparent
  to `UseSite` renaming and bind-mode container rebinding.
- `Subsumption.namesMeaning` and the binding-name index are built once per compile, not per entry.
- A bind lookup redirects through an alias only for a derived entry (one with no source position).

Related: `design/linking-and-compilation.md`, `design/choice-disjointness.md`, `design/compiled-registries.md`,
`design/readers-and-diagnostics.md`, `design/schema-resolution.md`.

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
      channel. Fail-fast loses nothing — `report` raises `ReadException`, which carries the same
      `Diagnostic`, so `e.diagnostic().code()` is the question rather than the exception type.
    - **`MissingBindingException` is the one cause that still throws, unwrapped and in every mode.** It
      is the reading application's own wiring — neither this library's gap nor a problem with the document —
      so it reaches that application as itself. Wrapping it once sent a service's missing configuration out
      as a 501.
    - **A `BindMismatchException` is the other deliberate exception, and it is rethrown** so a schema and a
      class that disagree fail the compile rather than the first read.
  - A referenced-but-absent name is a stricter `TsonSchemaLinker` invariant violation and propagates
    uncaught.
- **An entry declaring type parameters becomes an `AbstractTemplateReader` where its held body carries
  `extension`** — a **family base**, which dispatches to one of its instantiations by tag or by the
  discriminators exactly as a closed abstract or sealed record does — and an `OpenTemplateReader` otherwise: a
  template with no such dispatch is not a type, so naming one in *data* is an ordinary data diagnostic (a schema
  naming one unapplied was already refused at link time).
- **An open entry compiles to `OpenTemplateReader`, before its body is looked at at all.** An entry whose
  `kind` is `TEMPLATE` is a template, not a type (§5.10), so there is nothing a value could validate
  against; the dispatch is on `kind`, like every other entry's, rather than on a list being non-empty. The
  reader reports `TYPE_MISMATCH` against the data and skips the value, like any other reader
  finding data the schema does not admit. Reaching it is **always** a data error: a *schema* naming a
  template without applying it is rejected at link time (`checkArity`'s zero-argument case), so no field,
  element or supertype routes here — only a data type-ref naming the template, `!paged` against `paged =>
  <T> { … }`, which §5.10 makes an ordinary resolver error rather than anything exceptional. Refusing the
  whole entry is what makes the verdict right: built, a parameterised body either
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
- **A bind lookup asks under the name the author wrote.** An entry a template application materialised is
  named by content (§8.2 — resolver-chosen, fresh, unreachable from source), so a binding map cannot be keyed
  on one: the hash is not knowable when the map is written, and a generator emitting bindings cannot invent
  it. `ValueReaderContext.bindingNamesFor` inverts §8.3's alias hop — the entries that name a target through
  a `REFERENCE` body, in declaration order, then the entry's own name — and `RecordBindReader` and
  `TupleBindReader` try them in that order, so `ping => msg_of<"ping", ping_body>` binds under `ping`.
  **Only a derived entry is reached this way**, which is the test `EntryDisplayName` already applies: an entry
  with a source position was declared, so its own name is the one the author wrote and an alias naming it must
  never redirect its binding. The index is built once per compile beside `namesMeaning`, for that one's
  reason — a property of the schema, not of the entry being looked up. A failure keeps the **first**
  candidate's cause, the author-written name's, so a class that was mapped and then failed analysis is not
  masked by "the minted name is unbound"; `MissingBindingException` carries that cause rather than only its
  text, which is what makes an erased component (`no valid data conversion for class java.lang.Object`)
  visible from an ordinary read.
- **A family check names its members the same way.** `RecordExtension`'s pin-collision message renders each
  colliding member through `EntryDisplayName`, so a family whose members are template applications reports
  `pet_of<cat, int32>` rather than `pet_of_cat_int32_1c52dc45` — a name that points at no declaration an
  author can open. The blame still rides the real entry name, which is what locates the violation.

## §7.2's subsumption guard (`Subsumption`, `VariantSchemaReader`)

**§7.2's subsumption guard wraps every entry the rule governs** (`Subsumption`, applied at
`TsonSchemaCompiler`'s single `build` site). At a position typed `T`, a value annotated `!S` is valid iff
`S` is `T` or `T` is in `S`'s supertypes — and that was enforced only where `T` was a record with a
non-empty `subtypes()`, the one case that got a `VariantSchemaReader`. Every atom, array, map, tuple, and
every record whose type had no subtype, consumed the type-ref and discarded it, so a document could claim
any type at those positions. The guard is the same `VariantSchemaReader`, now wired wherever the rule
applies. Three things it has to get right, each a real bug found while wiring it: it **follows the body, not
`kind()`** (a hand-built entry can carry a `ChoiceBody` under `PRODUCT`, and choices and scoped instances have their
own membership relations §7.2 excludes); it accepts an entry's **aliases as the entry itself**, since §7.2
compares "after reference flattening of both" and resolving an alias would arrive back at the same reader
and recurse; and it is **transparent to `UseSite` renaming and to bind-mode container rebinding**, both of
which look at the reader it wraps — the first or a diagnostic names the entry instead of the author's
alias, the second or a bound `Map` field silently loses its rebinding.

**"Both" is both ends of the comparison, and the subtype end is the one that matters most.** The aliases of
the position's own type and the aliases of each of its subtypes are flattened by one function
(`Subsumption.admitting`), reached by one dispatcher builder (`Subsumption.dispatching`) that both routes to
a `VariantSchemaReader` go through — the guard's, and the record factories' for a type that has subtypes.
They did not: the guard's route flattened and the factories' did not, so the same alias was admitted at a
leaf record and refused at one that happened to have a subtype, decided by which construction site the entry
reached. The subtype end is where it is load-bearing rather than tidy: a materialised entry's name is
implementation-chosen and non-normative (§8.2), so an alias is the *only* name a document has for a template
instantiation, and without it a subtype-template family exists in the index with no member anything can
write. **The alias is admitted rather than reduced to its target** — a reference entry compiles to its
target's reader named for the referring entry, so dispatching on the written name runs the same reader and
reports under the name the author typed.

**Which names mean an entry is one index, built once per compile** (`Subsumption.namesMeaning`, held by
`Compilation`). It is a property of the schema, not of the entry being guarded — the names whose *chain*
ends at that entry, transitively, so a two-hop alias counts. Answering it per entry meant scanning every
entry for every entry compiled, each scan walking a chain: the schema's size squared, recomputing a fact
that cannot change between calls. An entry with no aliases is absent from the index rather than present with
a singleton, which is the overwhelming majority of them.
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
