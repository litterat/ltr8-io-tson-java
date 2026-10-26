# Linking and registration

Design notes for canonical identity, the schema registry, and `TsonSchemaLinker`'s pass 2: import merging, the order
of the link, reference validation, constructor eligibility, the two §5.10 template rules, the facts linking records on
`TsonLinkedSchema`, namespace types and values by name. Current form only; history lives in git.

The linker's other checks have notes of their own: kinds and name types in `design/kinds-and-name-types.md`, the
inhabitance check and what `record.extension` obliges in `design/inhabitance-and-record-extension.md`, choice
disjointness in `design/choice-disjointness.md`, and §8.2's name hygiene in `design/name-hygiene-and-minted-names.md`.

**Invariants**

- The linker lives in `tson-compiler` and the registry in `tson-schema`, on purpose; the linker materializes nothing.
- `CanonicalIdentity.canonicalize` (`tson-base`) reads a *reference* in any spelling of its identity; `validate` judges
  a document's own `!!id`, which must already be written canonically. Neither rewrites an identity into validity.
- Import collisions are decided by an entry's origin schema, not by name occurrence; a local declaration may not reuse a
  name the closure already binds unless it is equal to the entry already there.
- An import stands at the importer's governing level: importer and imported both name the meta-kernel as their
  `!!meta`, or neither (`TsonSchemaLinker.checkImportLevel`, §2.2.3). It is judged once per edge, against the import's
  own `!!meta`, before the import's entries merge; `SchemaResolver` asks it a phase earlier in the same wording.
- A reference (`ref_type`) is declared only in a kernel-governed schema, the one level where an entry holds a value.
  `ref<T>` is out of an ordinary schema's reach (core.tn declares no `ref`), so the linker's level check meets only
  the constructor applied directly, `!ref_type { … }`, and refuses it (`referenceOutsideTheKernelLevel`); a compiled
  reader therefore meets a `ref_type` body only under a schema that `governsSchemas` (`design/references.md`).
- A reference to a namespace entry (a `Namespace`, `myapi => !api { … }`) is refused at every position a type-ref
  occupies: it is a value, and nothing is typed by it.
- A kernel slot typed `type_kind` (`record_field.type`, the element and container type slots, `choice.variants`) is
  judged by `KindMembers` in `validateTypeRef`, after resolution: a constructor named as a type is refused there. An
  alias's target (`Reference`), which may name a constructor, is judged by resolution and arity alone
  (`constructors == null`). `design/references.md` has the account of every slot.
- A value by name (`TsonSchema.valuesByName`) resolves its first segment in the schema's own merged namespace, alias
  hops followed, never the meta's; its referent's declared type must IS-A the slot's, compared by canonical identity.
  Local entries only, after `validateEntry`.
- `entryOrigins`, `enumForms`, `nameTypes` and `namespaces` are on `TsonLinkedSchema`, never on `TsonSchema` or an
  entry's value, and each is merged through imports.
- A value of a namespace type is read as the map `TsonLinkedSchema.namespace` gives for it, never as the record of
  facets the type resolves to: both compilers hand `MapBody.Namespace.read()` to the `map` factory.
- `checkHeldArity` asks `HeldBody.applications()` only, never `HeldBody.names()`.
- `TsonSchemaRegistry` never overwrites an identity and exposes no way to remove or enumerate one: that *is* the
  "locked" guarantee.
- Everything that asks what an entry IS-A asks its `TypeChain`: `computeSubtypes` credits a record template as it
  does a type, and the constructor test (`EntryKind.isConstructor`) reads a template's chain as a closed one's,
  judging `top` by its origin.

Related: `design/meta-layer-namespaces.md`, `design/references.md`, `design/class2-compilation.md`,
`design/compiled-registries.md`, `design/schema-resolution.md`, `design/template-materialisation.md`.

## Why two modules

Resolution handles one declaration at a time, references carried as unverified strings. `TsonSchemaLinker` and
`TsonSchemaRegistry` add the second stage, in different modules: the linker is a pipeline stage and lives in
`tson-compiler` beside parse, desugar, resolve and compile, with `Diagnostic` and with `ltr8-regex` in direct reach;
the registry is storage over the `schema.meta` value model and stays in `tson-schema`, the leaf everything depends on.

**The linker materializes nothing.** `SchemaDesugarer` and `TemplateMaterialiser` have turned every sugar form and
closable application into a declaration one phase earlier, in the module that can bind a constructor generically. The
argument-bearing `type_ref`s the linker sees are inside open entries, validated and never rewritten; an application
whose head names nothing in scope keeps its argument list rather than collapsing to its bare head
(`design/template-materialisation.md`), so what the linker judges is what the author wrote.

## Canonical identity (`CanonicalIdentity`, [TSON-DATA] §2.2.1)

§2.2.1's identity over the IRI, not general URI normalization: scheme and query stripped, the host read as an
`ltr8-net` `Host` value, the path read back from its URI spelling and required NFC. Its class Javadoc has the rules.
`SchemaResolver` applies `validate` to `!!id` and `canonicalize` to each `!!import`; `toUri` gives a reference's
US-ASCII spelling for a fetch; `sameIdentity` compares two references. It is **public API**: `TsonSchemaLoader.load`
and a `SchemaSource` take a canonical identity, so anything implementing either derives it the same way. It sits in
`tson-base` because how a schema is named is one algorithm across every encoding; `TsonContentHash` is the `?sha256=`
half this one strips.

## The link, in order (`TsonSchemaLinker.link`)

`link` returns a `TsonLinkedSchema`, a thin wrapper that is a compile-time proof linking ran. The order is
load-bearing, each step reading what an earlier one wrote:

1. **Merge imports** (`mergeImports`), each edge's level judged first.
2. **Load the governing meta** through the loader and judge that it may govern (`checkMayGovern`); its entries are the
   structure namespace for every later step. Absent when the loader cannot produce it.
3. **Add the local entries**: a collision with the closure is refused unless the two are equal; a local constructor
   is judged for eligibility.
4. **`computeSubtypes`**, the reverse of `supertypes` over every `Type`, a record template included.
5. **`enumForms`** for each local enum (`EnumLabels.labelForm`).
6. **`computeKinds`**, every kind's `members` (`design/kinds-and-name-types.md`).
7. **`computeDisjointness`**, every choice's `disjoint` (`design/choice-disjointness.md`).
8. **`checkEnumTypes`**, **`checkFieldNameTypes`**, then **`nameTypes`** for each record not refused.
9. **`checkNames`**, §8.2 over scopes (`design/name-hygiene-and-minted-names.md`).
10. **`readAsMaps`**, the namespace types' maps.
11. **Validate** every entry (`validateEntry`), with the reference-level check beside it.
12. **`checkValuesByName`**, **`checkEveryEntryIsInhabited`**, **`checkRecordExtension`**.
13. **`withNameAnnotations`**, then **`checkDisjointAssertions`**: an author's `@disjoint` is read from both places §6
    puts it, the definition and the map key, so it runs last.

## Import merge (`mergeImports`, §2.2.3)

Each import's **whole namespace** is copied in as-is, transitively (its own imports included: "an `!!import`
contributes the imported schema's entire namespace"), keeping its home namespace: an imported entry is never
re-validated against the importer's. **Each merged entry's origin is recorded** (`entryOrigins`), taken from the
import's own `originOf`, so an entry two hops away keeps its author rather than the intermediary.

- **The level and not one `!!meta`.** A kernel-governed schema imports only kernel-governed ones and an ordinary schema
  only ordinary ones, so meta.tn imports the kernel and a schema a meta layer governs imports core.tn: an API
  description must import the records it describes. Judged before the edge's entries are taken, so the refusal names
  the two documents and their `!!meta`s rather than the `void` collision the mixed closure would reach.
- **Collisions are decided by origin.** One schema reached by several routes unifies (the diamond every schema
  importing core.tn forms; the copies differ only in the `subtypes` each route credited, so they union, `unified`).
  Two *different* schemas declaring one name is an error naming both. Because identities carry the spec revision, a
  closure reaching both `/2026/37/m/core.tn` and `/2026/38/m/core.tn` is refused here rather than surfacing later as a
  field conflict between two identically spelled types. Listing one schema twice, or under two spellings of one
  identity, is redundant rather than an error. An unregistered import is a `SchemaValidationException`.
- **A local entry equal to the closure's is the same entry.** §8.2 names a minted instantiation by its resolved form
  alone, so an application this schema closed and one an import closed agree by construction; any other local reuse
  of a closure name is refused and the local entry dropped, keeping the import's, which is already-linked material.

## Reference validation (`validateEntry`, `validateBody`, `validateTypeRef`)

Every reference must resolve in the merged namespace, or be one of the entry's own declared parameters. Three rules
are worth their place here, each catching what would otherwise link, compile, and fail only when a document is read:

- **A namespace entry is refused at every type-ref position** (a field type, a variant, an element, a map key or
  value), naming the namespace type it is a value of. A namespace entry's keys and values were judged by the read and
  its names by `ValuesByName`, so `validateBody` asks nothing of it.
- **A `type_kind` slot is judged by membership** of `type_kind` (`KindMembers.isMember`), where `type` is in reach:
  a constructor named as a field's type is refused as applied-not-typed (§4.1).
- **A choice's variants are distinct at the end of each §8.3 reference chain** (`ReferenceChain.terminal`), since an
  alias and its target are one type: `(text | my_text)` with `my_text => text` is caught, the only spelling an author
  cannot see. No variant may resolve to `void` (`checkVariantsAreNotVoid`).

**A chain's names fall back to the governing meta's namespace**, and no other reference does: a chain is the residue of
a constructor role (§3.3.1), not an author-written reference (§3.3.2), and a refinement of a kernel constructor reaches
one (`set_type => array ^ {...}` carries `[array, product, top, type]`). A `ref_type` body's `of` resolves in the
writer's namespace and must name a type or a namespace type, never a constructor (`kind<record>` is that spelling).

**Every body is asked its family's coherence rule** (`checkCoherent`), which catches the bounds an application closed
onto parameters (`sized<10, 3>`); a declared body was already asked at resolution, and one refused there never reaches
the linker (`design/atom-refinement-and-coherence.md`).

## Constructor eligibility and the governing level (§2.2.2, §4.2)

One §2.2.2 question asked from both ends. A schema named as this one's **`!!meta`** must itself name the meta-kernel
as its `!!meta` (`checkMayGovern`): an ordinary type library cannot govern, and naming core.tn as `!!meta` is the
`!!import` confusion. Judged only when the loader produced the target; an unresolvable `!!meta` is left to whoever
owns fetching, which also keeps meta-kernel's self-naming `!!meta` linkable mid-registration.
`TsonCompiledMetaRegistry.loadMeta` reaches the verdict a phase earlier, since it must compile the meta to resolve
against it, and raises the linker's own `notAMetaSchema`: one wording, and a `SchemaValidationException`, because a
wrong `!!meta` is an authoring error (which keeps the CLI's exit 1 and exit 70 apart).

A locally declared **constructor** (an entry that IS-A `top`, a namespace type included) is valid only where the
schema's `!!meta` is exactly the meta-kernel's identity. This half is a lint at the declaration, not a guard:
applicability is read only in the governing meta's entries, the `!!meta` half already refuses to let such a schema
govern, and an ordinary schema imports only ordinary ones, so its closure holds no base kind to compose with. It
says "you wrote something that can never do anything" where it was written. The entry is still merged, so references
to it check normally instead of each becoming an unresolved one. (In a *data* document `!xxx_type { … }` is an
ordinary record annotation, which is what makes §8 resolved-output bodies expressible.)

## The two §5.10 template rules

Both are decidable at link and neither depends on anyone applying a template.

- **Arity, over every reference** (`checkArity`): a reference supplies exactly as many arguments as the entry it names
  declares parameters. Too many, too few, and **none at all**: a template named bare otherwise links and compiles and
  fails at read time against a document with nothing wrong with it. The exception is a **family base**
  (`OpenEntry.isFamilyBase`, a record template), which may be named bare (`use => { u: box }`): a value there is a
  value of one of its members, the parameters eliminated by dispatch. A template with a held body has no dispatch
  and is refused bare. A type parameter written with arguments (`T<text>`) is refused: §5.10 admits no head
  abstraction.
- **Parameter usage** (`checkOpenEntryUsesEveryParameter`): an open entry references every parameter it declares, so
  `box => <T> { v: text }` is refused. The converse needs no check: at a closed entry a token in a value slot *is* a
  literal, and a parameter reference is already an unresolved one.
- **A held body answers arity for the applications it writes** (`checkHeldArity`). A held body withholds what a
  reference *resolves to*, so type-kind validation and inhabitance wait for materialisation; arity counts the
  parameters the referenced entry declares and does not. Nothing ever closes `chain => <T> { tail: chain<T, T>? }`,
  so deferring it would ship the mistake. **Applications only**: `HeldBody.names()` returns every token, field names
  and literals among them, so asking the zero-argument half of `names()` would refuse a correct schema whose field is
  called `box` beside a template of that name. That half runs on the entry materialisation mints, and an unapplied
  template gets no verdict (§5.10: "an unapplied template is checked no further and receives no verdict").

## The facts linking records (`TsonLinkedSchema`)

Four maps, each a fact only linking can establish and none part of the resolved schema value §9 defines, so none is
on `TsonSchema` or a body (where `schema.meta`'s hand-written `equals` and the `@Record` trap would meet it). Each is
merged through imports with the import's entries.

- **`entryOrigins`**: name to the canonical identity of the schema that declared it. It keeps a declaration's identity
  and its line answerable from the same document however many schemas merged it in: the pair a non-record reader
  offers as its location (`ValueReaderContext.locationOf`), which locates a root-level `!int32` in core.tn rather than
  in the schema that imported it. The registry stores `TsonLinkedSchema`, so the map survives every later `load`.
- **`enumForms`** and **`nameTypes`**: what an enum's and a record's name type decide, which a reader holding only
  `TsonSchema.entries()` cannot resolve (`design/kinds-and-name-types.md`).
- **`namespaces`**: the map each namespace type is read as (below).

## The registry (`TsonSchemaRegistry`)

`register(TsonLinkedSchema)` keys on the canonical identity of `!!id`, refuses a duplicate (`putIfAbsent`: no
overwrite) and any self-referential `bootstrap() == true` schema. `registerIfAbsent` is the idempotent form for the
on-demand resolve path, where two threads resolving one document both arrive with equivalent results; it overwrites
nothing either. `get(uri)` canonicalizes; `getByCanonicalIdentity` trusts its caller to have. `TsonSchemaLoader` is the
pluggable import and `!!meta` lookup, registered-only by default (nothing fetched). `TsonSchemaLinker.linkBootstrap` is
the one sanctioned way to link meta-kernel's raw bootstrap output without registering it.

## Namespace types (`readAsMaps`, §4.1)

A namespace type is a constructor IS-A the kernel's `namespace`, declared by composition stating its facets
(`field_map` and `schema` in the kernel, `headers => namespace & { key_type: = header_name  value_type: = text }` in
a meta layer). It resolves to a `RecordBody` of those facets, and a value of it is a map from `key_type` to
`value_type`, never that record. Turning one into the other is linking's: whether a chain's `namespace` is the
kernel's is judged by where the name is defined, in the schema's own merged namespace where it holds the name (a
composition operand resolves there, §3.3.2) and in the governing meta otherwise, which needs the closure and the meta.

- **The map is what the facets state.** `MapBody.ofNamespace` gives a `MapBody.Namespace`: keys of `key_type`, an
  identifier family (`namespace.key_type` is typed `identifier_kind`), values of the pinned `value_type`, ordered where
  `ordered` is pinned `true`, values never void, no size bounds. A FIXED `key_type` is the family every key is read and
  judged under. A DEFAULT one stands where the host states none (`field_map`'s host is the record), so `read()` reads
  keys by spelling (`Namespace.HOST_KEYS`, `text`) and the host's family judges them once resolved (`FieldNames`).
- **Refused here**: a concrete namespace type leaving `key_type` or `value_type` neither FIXED nor DEFAULT, since it
  describes no map (an abstract one is admitted and has none); and a field beside `MapBody.NAMESPACE_FACETS`, abstract
  or not, blamed on the namespace type that declares it, since the map is the whole value.
- **The reader is the map's.** `TsonSchemaCompiler` and `JsonSchemaCompiler` hand `linked.namespace(name)`'s `read()`
  to the `map` factory, so neither factory table has a `namespace` entry. Before linking, `ParameterTypes` reads the
  governing meta's namespace types as their maps directly. How a value binds and when it is delivered as a `Namespace`
  is `design/meta-layer-namespaces.md`'s.
- **Meta vocabulary.** A namespace type IS-A `top`, so only a kernel-governed schema declares it (§2.2.2), and since
  an ordinary schema cannot import that layer, a field typed by one stands only in a kernel-governed document; the
  schemas the layer governs hold values of it as entries. Such an entry (`myapi => !api { … }`) is a `Namespace`,
  refused at every type position (above), reached by the kind walk through its recorded constructor
  (`kind<api>` lists it), and compiled to an unbuildable `ErrorReader`.

## Values by name (`ValuesByName`, §5.12)

A namespace entry's values may hold a name, `request: place_order_request` at a slot typed `kind<record>` or
`method: orders.place_order` at a slot typed `ref<method>`. The read keeps the name as written with its path and the
slot's declared type (`TsonSchema.SlotType`), and `checkValuesByName` judges each one over local entries after
`validateEntry`: a kind-typed slot's name as a member (`judgeMember`), a reference-typed slot's by the walk, the value
it reaches delivered into the host's `Ref` cell. The walk itself, by key and by field through the binding's
descriptor, is `design/meta-layer-namespaces.md`'s; what is the linker's is below.

- **The grammar is judged here.** `ValuesByName.segments` splits the name at each `.` and requires each segment to be
  a [TSON-DATA] §7.7 identifier, none empty and no arguments, so `orders.1x`, `.orders` and `orders.` are refused
  before anything is looked up. A `type_ref` slot of a value body holds an application, judged by resolution, arity
  and §4.1 (`judgeApplication`).
- **The slot's type is the meta's, the name is the schema's.** The first segment resolves in `merged`, alias hops
  followed (§8.3), never in the meta's namespace (§3.3.2), so `method: place_order` is unresolved even though
  `orders` holds that key. Found first, judged after: nothing is a choice among candidates. A type entry admits no
  further segment, an open entry is no referent, a list has no named members, and a map that is no namespace is not
  entered whatever its key type.
- **The judgement is §7.2's IS-A, by canonical identity.** An entry's declared type is the constructor that built its
  body: its recorded tag where that is a constructor of the *declaring* schema's meta, else the kernel constructor its
  body's class mirrors (`@Typename`). A field's declared type is its `type`, a map value's the map's `value_type`.
  Each is named in the namespace of the meta governing the schema that declares it (an import's own meta, through the
  loader), and two types are one when their terminal entries share origin and name.
- **Each refusal is its own `SCHEMA_ERROR` naming the step**, against the entry holding the name, at the slot's RFC
  6901 pointer. A qualified name at a kind-typed slot is refused where it reaches into a value. A field step needs the
  bind context its values were bound under (`TsonCompiledMetaRegistry.link` passes it); an overload without one throws
  `IllegalStateException` if a walk reaches a field. Two steps are gaps (`NOT_IMPLEMENTED`): a qualified name at a
  kind-typed slot reaching a type through a namespace of types, and a FIXED field with no component.
- **Local entries only**: an imported body's names were judged in its own namespace when its schema linked.
