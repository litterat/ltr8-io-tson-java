# Linking and registration

Design notes for canonical identity, the schema registry, and `TsonSchemaLinker`'s pass 2: import merging, the order
of the link, reference validation, the two §5.10 template rules, the facts linking records on
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
- One namespace per schema (SPEC-FEEDBACK.md #7): its own entries and the whole closure of its `!!import`s
  (`TsonSchema.namespaceSources`, header order, deduplicated by identity). Every name the schema writes resolves
  there; no rule ties what a schema may declare to what it imports, so any schema declares a constructor, a namespace
  type or a reference, and imports any schema document.
- A reference to a value entry (a `ValueEntry`, `myapi => !api { … }`) is refused at every position a type-ref
  occupies: it is a value, and nothing is typed by it.
- A kernel slot typed `type_kind` (`record_field.type`, the element and container type slots, `choice.variants`) is
  judged by `KindMembers` in `validateTypeRef`, after resolution: a constructor named as a type is refused there. An
  alias's target (`Reference`), which may name a constructor, is judged by resolution and arity alone
  (`constructors == null`). `design/references.md` has the account of every slot.
- A value by name (`TsonSchema.valuesByName`) resolves its first segment in the schema's merged namespace, alias
  hops followed; its referent's declared type must IS-A the slot's, compared by canonical identity.
  Local entries only, after `validateEntry`.
- `entryOrigins`, `enumForms` and `nameTypes` are on `TsonLinkedSchema`, never on `TsonSchema` or an entry's value,
  and each is merged through imports.
- A namespace type resolves to a `NamespaceBody`, and a value of it is read as the map `NamespaceBody.read()` states:
  both compilers hand that map to the `map` factory, so neither factory table has a `namespace` entry.
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

1. **Merge the closure** (`mergeImports`): every `!!import`.
2. **Add the local entries**: a collision with the closure is refused unless the two are equal. The merged map is
   the one namespace every later step reads.
3. **`computeSubtypes`**, the reverse of `supertypes` over every `Type`, a record template included.
4. **`enumForms`** for each local enum (`EnumLabels.labelForm`).
5. **`computeKinds`**, every kind's `members` (`design/kinds-and-name-types.md`).
6. **`computeDisjointness`**, every choice's `disjoint` (`design/choice-disjointness.md`).
7. **`checkEnumTypes`**, **`checkFieldNameTypes`**, then **`nameTypes`** for each record not refused.
8. **`checkNames`**, §8.2 over scopes (`design/name-hygiene-and-minted-names.md`).
9. **Validate** every entry (`validateEntry`).
10. **`checkValuesByName`**, **`checkEveryEntryIsInhabited`**, **`checkRecordExtension`**.
11. **`withNameAnnotations`**, then **`checkDisjointAssertions`**: an author's `@disjoint` is read from both places §6
    puts it, the definition and the map key, so it runs last.

## Import merge (`mergeImports`, §2.2.3)

Each import contributes its **whole namespace**, copied in as-is, transitively (its own imports
included: "an `!!import` contributes the imported schema's entire namespace"), keeping its home namespace: an imported
entry is never re-validated against the importer's. **Each merged entry's origin is recorded** (`entryOrigins`), taken
from the import's own `originOf`, so an entry two hops away keeps its author rather than the intermediary.

- **An unregistered `!!import` is refused**: resolution already loaded every import, so a schema linked from a
  registry always has them. The kernel's bootstrap imports nothing, so it links its own entries with no loader.
  Every target is a schema document: one whose root is not tagged `!schema` fails to parse as a schema, so its
  importer fails to load before linking is reached.
- **Collisions are decided by origin.** One schema reached by several routes unifies (the diamond every schema
  importing core.tn forms; the copies differ only in the `subtypes` each route credited, so they union, `unified`).
  Two *different* schemas declaring one name is an error naming both, an entry of the kernel or meta.tn included:
  every closure reaching them reserves their names. Because identities carry the spec revision, a
  closure reaching both `/2026/37/m/core.tn` and `/2026/39/m/core.tn` is refused here rather than surfacing later as a
  field conflict between two identically spelled types. Listing one schema twice, or under two spellings of one
  identity, is redundant rather than an error. An unregistered import is a `SchemaValidationException`.
- **A local entry equal to the closure's is the same entry.** §8.2 names a minted instantiation by its resolved form
  alone, so an application this schema closed and one an import closed agree by construction; any other local reuse
  of a closure name is refused and the local entry dropped, keeping the import's, which is already-linked material.

## Reference validation (`validateEntry`, `validateBody`, `validateTypeRef`)

Every reference must resolve in the merged namespace, or be one of the entry's own declared parameters. Three rules
are worth their place here, each catching what would otherwise link, compile, and fail only when a document is read:

- **A value entry is refused at every type-ref position** (a field type, a variant, an element, a map key or
  value), naming the type it is a value of. A value entry's payload was judged by the read against its type and its
  names by `ValuesByName`, so `validateBody` asks nothing of it.
- **A `type_kind` slot is judged by membership** of `type_kind` (`KindMembers.isMember`), where `type` is in reach:
  a constructor named as a field's type is refused as applied-not-typed (§4.1).
- **A choice's variants are distinct at the end of each §8.3 reference chain** (`ReferenceChain.terminal`), since an
  alias and its target are one type: `(text | my_text)` with `my_text => text` is caught, the only spelling an author
  cannot see. No variant may resolve to `void` (`checkVariantsAreNotVoid`).

**A chain's names resolve in the same namespace.** A refinement of a kernel constructor reaches one (`set_type =>
array ^ {...}` carries `[array, product, top, type]`), and the defining schema's closure is inside the importer's, so
every name on it is there. A `ref_type` body's `of` must name a type, a namespace type included, never a
constructor (`kind<record>` is that spelling). A namespace type's stated `key_type` must be a member of
`identifier_kind`: its keys are names.

**Every body is asked its family's coherence rule** (`checkCoherent`), which catches the bounds an application closed
onto parameters (`sized<10, 3>`); a declared body was already asked at resolution, and one refused there never reaches
the linker (`design/atom-refinement-and-coherence.md`).

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

Three maps, each a fact only linking can establish and none part of the resolved schema value §9 defines, so none is
on `TsonSchema` or a body (where `schema.meta`'s hand-written `equals` and the `@Record` trap would meet it). Each is
merged through imports with the import's entries.

- **`entryOrigins`**: name to the canonical identity of the schema that declared it. It keeps a declaration's identity
  and its line answerable from the same document however many schemas merged it in: the pair a non-record reader
  offers as its location (`ValueReaderContext.locationOf`), which locates a root-level `!int32` in core.tn rather than
  in the schema that imported it. The registry stores `TsonLinkedSchema`, so the map survives every later `load`.
- **`enumForms`** and **`nameTypes`**: what an enum's and a record's name type decide, which a reader holding only
  `TsonSchema.entries()` cannot resolve (`design/kinds-and-name-types.md`).

`namespace(name)` is no map: it answers from the entry itself, the `NamespaceBody` a namespace type resolved to.

## The registry (`TsonSchemaRegistry`)

`register(TsonLinkedSchema)` keys on the canonical identity of `!!id`, refuses a duplicate (`putIfAbsent`: no
overwrite) and any `bootstrap() == true` schema, the kernel's pre-loaded form. `registerIfAbsent` is the idempotent
form for the on-demand resolve path, where two threads resolving one document both arrive with equivalent results;
it overwrites nothing either. `get(uri)` canonicalizes; `getByCanonicalIdentity` trusts its caller to have.
`TsonSchemaLoader` is the pluggable import lookup, registered-only by default (nothing fetched).
`TsonSchemaLinker.linkBootstrap` is the one sanctioned way to link meta-kernel's raw bootstrap output without
registering it. `linkReady(ready, loader, pending)` is the one partial link: a resolution in progress links its
document's entries ready so far to compile readers for the next read (`WaitOrder`), a reference to a pending
declaration is passed over there and nowhere else, and it reports nothing, each entry being judged when the whole
schema links; `design/resolver-vocabulary-and-bootstrap.md` has the mode.

## Namespace types (§4.1)

A namespace type is an instance of the kernel's `namespace` constructor, `!namespace { key_type: …  value_type: …
ordered: … }` (`field_map` and `schema` in the kernel, `headers => !namespace { key_type: header_name  value_type:
text }` in any schema), and resolves to a `NamespaceBody`. A value of it is a map from `key_type` to `value_type`.

- **The map is what the facets state.** `NamespaceBody.read()` gives the `MapBody`: keys of `key_type`, values of
  `value_type`, ordered where `ordered` is `true`, values never void, no size bounds. A stated `key_type` is the family
  every key is read and judged under, and must be a member of `identifier_kind` (`validateBody`). An absent one is the
  host's (`field_map`'s host is the record), so `read()` reads keys by spelling (`NamespaceBody.HOST_KEYS`, `text`) and
  the host's family judges them once resolved (`FieldNames`).
- **Nothing beside the facets.** `namespace`'s own record reader refuses any other field at the construction, so the
  linker has no facet check of its own.
- **The reader is the map's.** `TsonSchemaCompiler` and `JsonSchemaCompiler` hand `read()` to the `map` factory;
  `ParameterTypes` reads a namespace type as its map too, where a written value faces one. How a value binds and when
  it is delivered as a `Namespace` is `design/meta-layer-namespaces.md`'s.
- **Any schema's vocabulary.** Any schema declares a namespace type or types a field by it, and a schema importing the
  declaring one holds values of it as entries. Such an entry (`myapi => !api { … }`) is a `ValueEntry`: refused at
  every type position (above), a member of no kind (`kind<api>` is refused at the declaration, `api` being no
  constructor), named by `ref<api>`, and compiled to a `ValueEntryReader`.

## Values by name (`ValuesByName`, §5.12)

A value entry's payload may hold a name, `request: place_order_request` at a slot typed `kind<record>` or
`method: orders.place_order` at a slot typed `ref<method>`. The read keeps the name as written with its path and the
slot's declared type (`TsonSchema.SlotType`), and `checkValuesByName` judges each one over local entries after
`validateEntry`: a kind-typed slot's name as a member (`judgeMember`), a reference-typed slot's by the walk, the value
it reaches delivered into the host's `Ref` cell. The walk itself, by key and by field through the binding's
descriptor, is `design/meta-layer-namespaces.md`'s; what is the linker's is below.

- **The grammar is judged here.** `ValuesByName.segments` splits the name at each `.` and requires each segment to be
  a [TSON-DATA] §7.7 identifier, none empty and no arguments, so `orders.1x`, `.orders` and `orders.` are refused
  before anything is looked up. A `type_ref` slot of a value body holds an application, judged by resolution, arity
  and §4.1 (`judgeApplication`).
- **One namespace for the slot's type and the name.** The first segment resolves in `merged`, alias hops followed
  (§8.3), and only there, so `method: place_order` is unresolved even though `orders` holds that key. Found first,
  judged after: nothing is a choice among candidates. A type entry admits no further segment, an open entry is no
  referent, a list has no named members, and a map that is no namespace is not entered whatever its key type.
- **The judgement is §7.2's IS-A, by canonical identity.** An entry's declared type is the constructor that built its
  body: its recorded tag where that names a constructor, else the kernel constructor its body's class mirrors
  (`@Typename`). A field's declared type is its `type`, a map value's the map's `value_type`. An imported referent's
  constructor is in its declaring schema's closure, which this one contains, so each is named in the one namespace
  (`ValuesByName.Space`), and two types are one when their terminal entries share origin and name.
- **Each refusal is its own `SCHEMA_ERROR` naming the step**, against the entry holding the name, at the slot's RFC
  6901 pointer. A qualified name at a kind-typed slot is refused where it reaches into a value. A field step needs the
  bind context its values were bound under (`TsonCompiledMetaRegistry.link` passes it); an overload without one throws
  `IllegalStateException` if a walk reaches a field. Two steps are gaps (`NOT_IMPLEMENTED`): a qualified name at a
  kind-typed slot reaching a type through a namespace of types, and a FIXED field with no component.
- **Local entries only**: an imported body's names were judged in its own namespace when its schema linked.
