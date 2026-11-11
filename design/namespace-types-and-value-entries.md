# Namespace types and value entries

Design notes for **namespace types**, the instances of the kernel's `meta.namespace` constructor, and **value entries**,
an entry whose value is an instance of an ordinary type rather than a type. Together they are how a schema puts
vocabulary of its own in a schema map: any schema declares a record type or a namespace type,
and any schema importing it holds values of them. Covers how such a value is read, bound or held, how the names inside
it are judged, and how a qualified name walks into it ([TSON-SCHEMA] §4.1, §4.2, §5.12).

**Invariants**

- **An entry is a type exactly when its value is built by a constructor** (IS-A `top`, `EntryKind.isConstructor`).
  `name => !T { … }` with `T` a constructor builds a type; with `T` any other type of the closure -- a record type, a
  namespace type -- the entry is a value of `T` (`ValueEntry(type, value)`, `EntryKind.VALUE`), read against `T` as a
  data value is and marked by nothing but its tag. A record component, `!meta.record_field { … }`, is a value entry too.
- **A value of `meta.schema` declares instead** (§4.1). `geo => !schema { … }` is a schema map nested
  as an entry: the parser reads its payload as schema grammar, never as a value body, and its members are declarations
  of the document under their paths. It is held as a `ValueEntry` of `meta.schema` whose `Namespace` is derived from
  those path entries (`SchemaPaths.synced`, `design/schema-resolution.md`). It is open: several documents of a closure
  declaring one map path contribute to one map, the union of their members (`design/linking-and-compilation.md`).
  Every other namespace type's value stays a value entry.
- `Top` is sealed all the way down (`permits Type, Alias, ValueEntry`). A switch over a body's kind
  is exhaustive with an explicit `ValueEntry` arm and no `default`: `EntryKind.of`, `TypeInhabitance`,
  `DiscriminationClass`, and the linker's `validateBody`, `collectNames` and coherence check.
- **`meta.namespace` is a refinement of `meta.map`** (`meta.map ^ { key_type: meta.identifier_ref voidable?: = false
  }`), IS-A `meta.map` and `meta.product`, and a namespace type is an instance of it, resolved to a `NamespaceBody`
  (`key_type`, `value_type`, `ordered`, `min_items`, `max_items`; `voidable` is pinned false): the kernel's one,
  `meta.schema => !meta.namespace { key_type: meta.type_name value_type: meta.declared }`, and a schema's own `api =>
  !meta.namespace { key_type: meta.identifier value_type: method }`. A value of one is the map its facets describe
  (`NamespaceBody.read()`), its size bounds judged as a map's are, and nothing else: a field beside the facets is
  refused by `meta.namespace`'s own record reader.
- **IS-A `meta.map` is a fact about kinds, not about bodies.** `NamespaceBody` and `MapBody` are distinct classes, so
  every reader, compiler and walk that dispatches on the body (`TsonSchemaCompiler`, `JsonSchemaCompiler`,
  `DeclaredReader`, `MapBindReader` through `TsonLinkedSchema.namespace`, `ValuesByName`, `TypeInhabitance`,
  `DiscriminationClass`) takes the namespace path for a namespace type and the map path for a plain map; the
  IS-A edge is read where kind membership asks for it (`KindMembers`' construction walk).
- `key_type` is required, and is a member of `meta.identifier_ref`, judged at link (`validateBody`): a namespace's
  keys are always judged by its own family, never a host's.
- **A record's fields are no namespace.** `meta.field_map => !meta.map { key_type: text value_type: meta.record_field
  ordered: true }` is an ordered map keyed by text (`RecordBody.fields`, an `AnnotatedMap`): a field name is text within
  its own record, judged by the record's `name_type` as `discriminators` and the groups are (`FieldNames`), and no
  qualified name selects a field declaration (§4.1's segments select in values only).
- A namespace type is a member of `meta.type_ref`, `meta.ref<meta.product>`, `meta.ref<meta.map>` and
  `meta.ref<meta.namespace>`, and of no `meta.ref<meta.record>`; `meta.namespace` itself is a constructor whose
  declaration is a record, so `meta.type_ref` and `meta.ref<meta.record>` hold it. **A value entry is a member of no
  kind** (no reference over a constructor), nothing is typed by it, and a reference over a type that is no constructor
  names it where its type IS-A `of`: `meta.ref<api>` names `myapi`.
- A value entry is refused at every type position, in one sentence (`ValuesByName.aValueNoType`): a reference in a
  type declaration (`validateTypeRef`), a kind-typed slot in a value body (`judgeMember`). A data tag naming one
  compiles to a `ValueEntryReader` and is `TYPE_MISMATCH`.
- **One bind context serves schemas and data** (`Tson.dataBindContext()`, `SchemaModelNameBinder.over`): a name meta.tn
  declares (a root name or a path under one of its six maps) or a library's member under its map (`numeric.*`,
  `temporal.*`, `net.*`) binds to the schema model, every other to the consumer's binder, a member a consumer contributes
  to an open map included, so a
  value entry binds to the consumer's class by the type's name. **A value entry no class binds is held as a `TsonValue`**,
  read in tree mode (the declaring schema's `TsonCompiledSchemaLoader.treeVocabularyOf`, or for a type of the same document
  `compileReady(…, tree)`), so the schema loads and validates. The bind registry refuses such an entry in the closure it
  compiles, `BIND_MISMATCH` at `/entry` (`TsonCompiledSchemaRegistry.refuseUnbound`); the tree registry refuses nothing.
- A host declares none of its references; the slot declares them, by the reference's `of`. A slot naming a type is
  typed by a reference over a constructor (`request: meta.ref<meta.record>`), a slot naming a value by a reference over any
  other type (`method: meta.ref<method>`), and a record-typed slot holds the record's value inline. In a schema document
  the kind's or reference's reader (`AtomTypeReader`) reads the name's grammar and records it with its path and the
  slot's declared type (`TsonSchema.valuesByName`, `TsonSchema.SlotType`); the linker judges it by that type, whatever
  the host component is.
- A value by name is entered through a value entry only. Its first segment resolves in the schema's one namespace,
  each segment naming a member of a nested `!schema` map collapsing onto that member's path entry (`geo.home`); a
  further segment selects a key of a namespace value or a field of a record value (bound or tree-held); a map that is
  no namespace, `{text => method}` or a record value's `meta.field_map`, is not entered whatever its key type, though
  `meta.ref<meta.map>` holds namespace types and plain map types alike: the walk asks the body, a `NamespaceBody`.
- A field step through a bound record value reads the host object through the descriptor of the bind context it was
  bound under, by wire name, never by reflection (`TsonCompiledVocabularyRegistry.link`). An optional field the value does
  not hold is refused as not present, after the referent's type is judged.
- A read delivers a `Namespace` at four places and nowhere else: a value entry's payload (`SchemaResolver`'s read), a
  `declared` position whose tag selects a namespace type (`DeclaredReader`, which is how resolved output's entries
  read), a namespace-typed slot whose host component is a `Namespace` (`BindTargets`), and a root read asked for
  `Namespace.class`. Everywhere else a namespace type reads as its map. Tree mode delivers the tree.
- The model holds every bound value of a namespace type as a `Namespace`: a value entry's value and the schema map
  (`TsonSchema.entries`). `Namespace` has its map's read API and is no
  `Map`; its equality is `type`, the entries as a map and each key's annotations, never order.
- A value entry of a type declared in the same document, `!T { … }` with `T` local, is read once `T` is ready, in
  wait order, whichever is written first (`design/schema-resolution.md`). An instance of a constructor whose
  instances are no types (`!step { … }` over `step => meta.top & { … }`) is held as a `ValueEntry` too, on the same
  terms (`design/constructor-application.md`).

Related: `design/linking-and-compilation.md` (link order and values by name), `design/references.md`,
`design/kinds-and-name-types.md`, `design/class2-compilation.md`, `design/compiled-registries.md`,
`design/schema-resolution.md`.

## Types and values in one map (§4.1)

The schema map is a value of the kernel's namespace type `meta.schema`, whose `value_type` is `declared`: each entry's
value names its type by its tag. `meta.declared_type => meta.sum & {}` and `declared => !meta.declared_type {}` are the
kernel's, so
the schema map's own type is closed over the kernel alone (`design/declared-positions.md`). A tag naming a
constructor builds a type; a tag naming any other type builds a value of it. `meta.top` is the root of the constructors
only, never of what an entry holds.

The motivating case is an HTTP interface: a set of methods, each naming its request and response types, which must
sit at the schema layer because that is the only layer able to name a type *by name* -- a data document can only
instantiate one. A schema declares the record and a namespace type holding it, and a schema importing it holds a
value:

```
method    => { request?: meta.ref<meta.record>  response?: meta.type_ref  errors?: [meta.ref<meta.record>] }
interface => !meta.namespace { key_type: method_name  value_type: method }
binding   => { method: meta.ref<method>  verb: http_verb  route: text }
bindings  => !meta.namespace { key_type: meta.identifier  value_type: binding }
```

`orders => !interface { place_order => { request: place_order_request  response: order } }` and `routes => !bindings
{ post_orders => { method: orders.place_order  verb: POST  route: "/orders" } }`. A single record value is as good:
`create_order => !operation { … }`. The corpus fixture `meta-service.tn` is the worked case.

**Why the tag and not a marker.** The entry is a value the schema describes: its keys are judged by `key_type`, its
values by `value_type` with every field state and atom constraint the record declares, and a qualified name walks it
through the schema rather than through whatever object a host built. Reading it as a value of its type gives one
rule for what a schema map holds and what a data document holds, and keeps `Top` closed: every branch but
`ValueEntry` mirrors a kernel constructor, and `ValueEntry` wraps a value whose type the schema declares.

**A namespace type declares only its facets.** `meta.namespace`'s own record reader refuses any other field, so
`api => !meta.namespace { key_type: meta.identifier  value_type: method  extends: [a] }` fails at the read ("unknown field").
A construct with fields of its own and a map of members is a record holding both, under a namespace type's key:

```
method_map => !meta.namespace { key_type: method_name  value_type: method }
interface  => { extends?: [meta.type_name]  methods: method_map }
interfaces => !meta.namespace { key_type: meta.identifier  value_type: interface }
```

**A refinement of `meta.map`.** `meta.namespace` narrows `map`'s `key_type` from `meta.type_ref` to
`meta.identifier_ref`, the refinement of `meta.type_ref` to the identifier families, and pins `voidable` false;
`ordered` and the size bounds are `map`'s and bound a value as a map's do. So `meta.ref<meta.map>` holds every namespace
type, and a map that is no namespace is still never entered by a qualified name. Both compilers read a namespace type
through the map factory with the map `NamespaceBody.read()` states (`design/linking-and-compilation.md`).

**A kind is judged by origin.** `EntryKind.of` counts the base kinds only as the kernel's own entries (declared by
meta.tn, `TsonBundledSchemas.isKernel`), and kind membership walks construction to the kernel's
`meta.namespace`. A schema's own root `namespace` is an unrelated name, and a schema contributing `namespace` to `meta`
meets §2.2.3's collision with the kernel's member of that path.

## Value entries (`DefinitionResolver.resolveValueEntry`)

`resolveInstance` resolves `T` in the schema's one namespace, refuses a template head (it closes by application),
and asks `EntryKind.isConstructor`. A constructor builds a type; anything else goes to `resolveValueEntry`, which
refuses a head that is itself a value (a value has no instances) and a braced payload at an atom type (almost always
an atom refinement missing its `^`), then reads the payload against `T` (`SchemaResolver`'s `value`) and records `T`
as the entry's constructor fact, so resolved output writes the tag. A payload that is no value of `T` is refused
against the entry with the read's path.

A value entry's payload is a **value body** (`TsonReadContext.readsValueBody`; a nested `!schema` map's is none, its
members being declarations): nothing in it is closed or written in place, so a
kind-typed slot holds one bare name and an application (`request: box<order>`) is refused with the alias spelling
named (`order_box => box<order>`, then `request: order_box`). There is no template over an ordinary type: a template's
body is a record, so `fetch => <T> !operations { … }` is refused at the declaration, and a value is declared once per
instantiation (`design/open-records.md`).

**§8.2 reaches the payload at the read, not at the link.** The linker cannot see into a value -- its fields' types are
left in the declaring schema -- so the payload is read under the loader's identifier policy (`SchemaResolver`'s `value`)
and judged as a data document is: the keys of the namespace and of every identifier-keyed map in it are scopes, and an
identifier-typed value is a name (`design/name-hygiene.md`).

## Binding: one context, and a tree where nothing binds

`Tson.of` builds one `DataBindContext` from the configured one (`SchemaModelNameBinder.over`): the consumer's binder, profile
and atoms (`DataBindContext.toBuilder`, `nameBinder`), composed with `SchemaModelNameBinder.INSTANCE`, which answers for
exactly every entry of meta.tn, its root names and its members of its six maps, and the members numeric.tn, temporal.tn and
net.tn declare under `numeric`, `temporal` and `net` (read from the bundled sources), each by its path into the package of
its namespace (`numeric.integer_type` to `io.ltr8.tson.schema.numeric.IntegerType`) -- so a kernel name can never be
rebound, a member a consumer contributes to an open map is the consumer's, and a consumer's `meta.token` is the consumer's
although `schema.meta` has a `Token` -- and the schema model's own registrations added where absent
(`ResolverBindContext.registerAbsent`). The resolution core resolves schemas through it and the bind registry reads data
through it, so `create_order => !operation { … }` and a data document's `!operation { … }` bind to the same class. A class
implements nothing; `@Typename` is needed only where the binder's convention would not find it.
`SchemaModelNameBinder.extendedWith`/`contextExtendedWith` build the same composition for a caller assembling a context
themselves.

**No class is a tree, not an error, until something binds.** `SchemaResolver` reads the payload in bind mode; a
`MissingBindingException` there reads it again through `treeVocabularyOf(origin)`, the declaring schema compiled in
tree mode, and the entry holds the `TsonValue`. So `tson validate`, tree reads, `Tson.resolve` and `validateSchema`
load a schema whose value entries no Java class describes. A consumer binding under the schema expects every value
of its closure as the class its context names, so `TsonCompiledSchemaRegistry` in bind mode -- `get(uri)`,
`get(uri, receiver)`, `get(imports, receiver)` and `compile(linked)` -- refuses a tree-held entry anywhere in the
closure it compiles: `MissingBindingException` (a `BIND_MISMATCH`) naming the entry, or one `BIND_MISMATCH` per entry
through the receiver, `schemaPointer` `/entry`. That is the narrowest point a consumer first obtains a schema for
binding; a missing line of wiring says nothing about the schema (`design/schema-side-diagnostics.md`).

## Names inside a value: the slot declares the spelling (§4.2, §5.12)

A slot naming a type is typed by a reference over a constructor, `request: meta.ref<meta.record>`, and a slot naming a value
by a reference over any other type, `method: meta.ref<method>`; the spelling is one, and `of` decides
(`KindMembers.namesTypes`), and a slot typed by a record holds the record's value inline. In a schema document
(`TsonReadContext.readsSchemaNames`) the kind's or reference's `AtomTypeReader` reads the name's grammar alone
(`AtomParsers.inSchemaDocument`) and records it with the path it descended (`["post_orders", "method"]`) and the
slot's declared type (`TsonSchema.SlotType`: the role the read read it in, and the reference's `of`).
`SchemaResolver` keeps them per entry in `TsonSchema.valuesByName`, and the linker judges each by what `of` names
over the final namespace, whatever role the read recorded: a member of the kind (`ValuesByName.judgeMember`), or a
value whose type IS-A `of` (`ValuesByName.judge`). The type comes from the
reader built from the slot's declaration, so it is right however the value was reached -- under a key, behind a
record field, through the subtype a tag selected -- and the path serves only to say where.

**One host shape for every reference.** A kind-typed slot binds a `TypeRef`, and any other reference-typed slot a
`Ref<T>` or a `TypeRef`; `BindTargets` sets a `KindSlotReader` or `ReferenceNameReader` for them, and a `Ref` receives
the value the name reaches when the schema links (`Ref.deliver`): the bound object, the `Namespace`, or the tree. In a
data document the same reader hands the name to the reference's atom bound to the component, which judges it and
delivers at once. A host
holding the slot as a `String` gets the atom's reader wrapped in a `RecordedNameReader`, which refuses what is no name
as a `TypeRef` host's reader would; the name is recorded by the atom reader either way, so `method: nobody.place` is
refused under a `String` exactly as under a `TypeRef`.

**In a data document a kind is judged by membership**, over the reading document's closure, so a data document
importing only temporal.tn that writes `!meta.record_field { type: int32 }` is refused, `int32` being numeric.tn's
(`design/kinds-and-name-types.md`). **A value's qualified name is judged by what it reaches in the governing schema**,
by the same walk (`ValuesByName`): the link that indexed the reference leaves it a lookup over its final namespace
(`ReferenceBody.Values`), and the reference's atom (`RefParser`) asks it. So `which: o2` at `meta.ref<order>` reads
where `o2` is a value of `order` or a subtype, through a nested map, a namespace value's key or a record value's field
included, and a name reaching nothing, a type, or a value of another type is `ATOM_CONSTRAINT_VIOLATION`. What a host
receives is the slot's one shape: a `TypeRef` whose `referent()` is the value reached (a tree read's `TsonAtom` holds
it), a `Ref` delivered with that value at the read, a `String` the name; JSON reads the same name from a string and
binds the same shapes (`design/json-facades-binding-writing.md`). The value is the schema's own, bound or tree-held as
the schema's value entry is, never a copy read again under the data document's context.

## The walk: by key and by field (`ValuesByName`)

The types are walked through the schema: a record's fields to a field's type, a namespace type to its `value_type`
(`TsonLinkedSchema.namespace`, a `NamespaceBody`). The value is walked beside them. A key selects a value of the
namespace type's `value_type` in the `Namespace` the read built, in a host's `Map` where a component held one, or in
the `TsonMap` of a tree-held value (`ValuesByName.keyed`). So `handler: myapi.get_orders` reaches the `method` value
under `get_orders`, and `method: shop.orders.methods.place_order` the one under `place_order` in the interface the
`orders` service holds in its `methods` field, each delivered into the host's `Ref`.

**A field step reads the host through its binding.** A segment selecting a field follows the record's declared
fields: no such field is refused, listing those declared. A bound value is read through the descriptor that bound
it -- the bind context's `getDescriptor` of the host's class, its `fieldIndex` by wire name, that component's `get`
-- since raw reflection would bypass binding's own naming (a component renamed with `@Field`, an `Optional`
component, a tagged subtype's class, a host that is no Java record). The context is the one the resolution core binds
with, passed by `TsonCompiledVocabularyRegistry.link` to `TsonSchemaLinker.link`. A tree-held value is read by field name.
A FIXED field may have no component, its value being the schema's, and naming a value through one is the one field
step reported as a gap.

**A dependent field step takes the type its sibling names in the same value** ([TSON-SCHEMA] §5.2,
`ValuesByName.dependentType`): the sibling's pin from the declared `RecordField` (so a host holding no component for
a pinned sibling is never asked), else what the record value holds there, else the sibling's default, and the walk
goes on with that type. What the sibling holds is judged by the read path's rule, `DependentFieldReader.typeNamed`: a
sibling reference, a template or an application the closure does not hold names no type, and so does a value entry
(`aValueNoType`); each, and a sibling the value does not hold, is a `SCHEMA_ERROR` naming the name, the dependent field
and the sibling. Past a field the value does not hold there is no value to read the sibling from: a pin or default
still types the field and the name is refused as reaching no value after its type is judged, as any such name is, and
without one it is refused as reaching no value at once. In a schema document a dependent field's value is held as
written, the schemaless tree, so a name going on past one walks that tree by field name (`d.s.value.port` reaches the
`8080` that `value: { port: 8080 }` holds), and the value itself is judged against the type its sibling names when the
schema links (`HeldValues`, `design/linking-and-compilation.md`).

**Refusals and kinds.** A value by name may name a value entry: a slot holding a value is not a type position. A
value entry named where a type is wanted is refused in one sentence wherever it is met: a value of its type, and
nothing can be typed by it. `TypeInhabitance` calls a `ValueEntry` inhabited and `DiscriminationClass` gives it no
class, and both are right only because nothing may name one as a type -- which is what makes that refusal
load-bearing rather than a nicety.

## Reading and writing

**Four places build the `Namespace`.** A namespace type's own reader is its map's, and in bind mode it yields an
`AnnotatedMap`, keeping each key's annotations and order (`MapBindReader`); `NamespaceInstanceReader` wraps it where
the position asks for the instance: the resolver's read of a value entry's payload (`SchemaResolver.asValue`), a
`declared` position whose tag selects a namespace type (`DeclaredReader`, the kernel `meta.schema` map's values), a
namespace-typed slot whose host component is a `Namespace` (`BindTargets`), and a root read asked for
`Namespace.class` (`TsonObjectReader`). A host declaring a `Map` at a namespace-typed slot receives the map; a
`Namespace` component at a map that is no namespace has no type to name and is a bind mismatch.

**`Namespace` is no `Map`.** A binder or writer testing `instanceof Map` would lose the type, so it has its map's read
API and nothing more: iteration is over `entries()`, and an owner whose namespace is ordered compares it in order
itself, order being the `ordered` facet and not part of equality. Its `type` names the namespace type; it carries no
`@Typename`.

**Resolved output is written as written.** `DataClassObjectWriter` writes a `ValueEntry` as its `type` for the tag
and then the value: a `Namespace` as its map, each key with its annotations, bare where it is an identifier and
quoted otherwise; a bound object through its descriptor; a tree through the tree writer. A `Namespace` at a slot its
host types as one is written untagged (`methods: { place_order => … }`). A record's `fields`, a plain map keyed by
text, is written as one, each key quoted as text and preceded by the annotations its `AnnotatedMap` holds. A value
by name is a `TypeRef` and is written as the name written (§8.3). The kernel's `meta.schema` reader marks its read as a
schema document's (`readsSchemaNames`), so a resolved form read as data reads its names by grammar alone, and their
membership is ingest's
(`design/kinds-and-name-types.md`).

**A value entry compiles to a refusal.** Nothing is read against a value, so `TsonSchemaCompiler` gives a value entry
a `ValueEntryReader`, which reports `TYPE_MISMATCH` (write `!api` for a value of that type) and skips the value;
`JsonSchemaCompiler.constructorOf` names the value's type, which has no factory, and the entry compiles to an
`ErrorReader`. A constructor this library has no factory for compiles to an `ErrorReader` carrying the cause
(`TsonCompiledVocabulary`'s `unbuildable`), kept rather than dropped; `CoreSchemaImportTest` asserts no entry of
core.tn or net.tn compiles to one.

## One namespace, so shared vocabulary sits beside the types

A schema declaring `method` puts its shared types beside it -- a `status_code` atom, an envelope template -- and every
schema importing it names them: an `!!import` contributes its whole namespace (§2.2.3). The cost is
reservation: every root name in the closure is taken, so a schema importing core.tn cannot declare `text`, `enum`,
`set`, `uuid` or `title` of its own, nor `meta`, `atom`, `numeric`, `unicode`, `net`, `binary` or `temporal`
(`ImportedVocabularyTest`). The machinery is under those seven prefixes, so `record`, `identifier`, `map`, `namespace`,
`template`, `type_ref`, `field_name`, `bounded`, `value` and `kind` are free; a schema importing only the libraries it wants
leaves the rest's root names free too (one importing temporal.tn alone may declare `title` or `int32`). The one thing a
schema cannot do is hold a value of a type it declares itself: `!api { … }` beside `api => !meta.namespace { … }` waits on
`api` being resolved first, which outside the kernel's bootstrap is a gap.
