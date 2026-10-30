# Namespace types and value entries

Design notes for **namespace types**, the instances of the kernel's `namespace` constructor, and **value entries**,
an entry whose value is an instance of an ordinary type rather than a type. Together they are how a schema puts
vocabulary of its own in a schema map: any schema declares a record type or a namespace type,
and any schema importing it holds values of them. Covers how such a value is read, bound or held, how the names inside
it are judged, and how a qualified name walks into it ([TSON-SCHEMA] §4.1, §4.2, §5.12; SPEC-FEEDBACK.md #9).

**Invariants**

- **An entry is a type exactly when its value is built by a constructor** (IS-A `top`, `EntryKind.isConstructor`).
  `name => !T { … }` with `T` a constructor builds a type; with `T` any other type of the closure -- a record type, a
  namespace type -- the entry is a value of `T` (`ValueEntry(type, value)`, `EntryKind.VALUE`), read against `T` as a
  data value is and marked by nothing but its tag. A record component, `!record_field { … }`, is a value entry too.
- `Top` is sealed all the way down (`permits Type, Reference, TemplateBody, ValueEntry`). A switch over a body's kind
  is exhaustive with an explicit `ValueEntry` arm and no `default`: `EntryKind.of`, `TypeInhabitance`,
  `DiscriminationClass`, and the linker's `validateBody`, `collectNames` and coherence check.
- **`namespace` is a constructor under `product`**, and a namespace type is an instance of it, resolved to a
  `NamespaceBody` (`key_type` optional, `value_type`, `ordered`): `schema => !namespace { key_type: type_name
  value_type: declared }`, `field_map => !namespace { value_type: record_field  ordered: true }`, a schema's own
  `api => !namespace { key_type: identifier  value_type: method }`. A value of one is the map its facets describe
  (`NamespaceBody.read()`), and nothing else: a field beside the facets is refused by `namespace`'s own record reader.
- A stated `key_type` is a member of `identifier_kind`, judged at link (`validateBody`). An absent one means the host
  of a value supplies the family: keys read as `text` (`NamespaceBody.HOST_KEYS`) and are judged by the host, as a
  record's `fields` are by its `name_type`.
- A namespace type is a member of `type_kind`, `kind<product>` and `kind<namespace>`, and of no `kind<record>` or
  `kind<map>`; `namespace` itself is a constructor whose declaration is a record, so `type_kind` and `kind<record>`
  hold it. **A value entry is a member of no kind** (no reference over a constructor), nothing is typed by it, and a
  reference over a type that is no constructor names it where its type IS-A `of`: `ref<api>` and `kind<api>` are one
  entry, and either names `myapi`.
- A value entry is refused at every type position, in one sentence (`ValuesByName.aValueNoType`): a reference in a
  type declaration (`validateTypeRef`), a kind-typed slot in a value body (`judgeMember`). A data tag naming one
  compiles to a `ValueEntryReader` and is `TYPE_MISMATCH`.
- **One bind context serves schemas and data** (`Tson.dataBindContext()`, `SchemaMetaNameBinder.over`): a name
  meta-kernel.tn or meta.tn declares binds to the schema model, every other to the consumer's binder, so a value entry
  binds to the consumer's class by the type's name. **A value entry no class binds is held as a `TsonValue`**, read in
  tree mode (the declaring schema's `TsonCompiledSchemaLoader.treeVocabularyOf`, or for a type of the same document
  `compileReady(…, tree)`), so the schema loads and validates. The bind registry refuses such an entry in the closure it
  compiles, `BIND_MISMATCH` at `/entry` (`TsonCompiledSchemaRegistry.refuseUnbound`); the tree registry refuses nothing.
- A host declares none of its references; the slot declares them, by the reference's `of`. A slot naming a type is
  typed by a reference over a constructor (`request: kind<record>`), a slot naming a value by a reference over any
  other type (`method: ref<method>`), and a record-typed slot holds the record's value inline. In a schema document
  the kind's or reference's reader (`AtomTypeReader`) reads the name's grammar and records it with its path and the
  slot's declared type (`TsonSchema.valuesByName`, `TsonSchema.SlotType`); the linker judges it by that type, whatever
  the host component is.
- A value by name is entered through a value entry only. Its first segment resolves in the schema's one namespace; a
  further segment selects a key of a value of a named product, a namespace value's key or a record value's field
  (bound or tree-held), the record being a namespace whose keys its type fixes; a map that is no namespace, `{text =>
  method}`, is not entered whatever its key type.
- A field step through a bound record value reads the host object through the descriptor of the bind context it was
  bound under, by wire name, never by reflection (`TsonCompiledMetaRegistry.link`). An optional field the value does
  not hold is refused as not present, after the referent's type is judged.
- A read delivers a `Namespace` at four places and nowhere else: a value entry's payload (`SchemaResolver`'s read), a
  `declared` position whose tag selects a namespace type (`DeclaredReader`, which is how resolved output's entries
  read), a namespace-typed slot whose host component is a `Namespace` (`BindTargets`), and a root read asked for
  `Namespace.class`. Everywhere else a namespace type reads as its map. Tree mode delivers the tree.
- The model holds every bound value of a namespace type as a `Namespace`: a value entry's value, the schema map
  (`TsonSchema.entries`) and a record's fields (`RecordBody.fields`). `Namespace` has its map's read API and is no
  `Map`; its equality is `type`, the entries as a map and each key's annotations, never order.
- A value entry of a type declared in the same document, `!T { … }` with `T` local, is read once `T` is ready, in
  wait order, whichever is written first (`design/schema-resolution.md`). An instance of a constructor whose
  instances are no types (`!step { … }` over `step => top & { … }`) is held as a `ValueEntry` too, on the same
  terms (`design/constructor-application.md`).

Related: `design/linking-and-compilation.md` (link order and values by name), `design/references.md`,
`design/kinds-and-name-types.md`, `design/class2-compilation.md`, `design/compiled-registries.md`,
`design/schema-resolution.md`.

## Types and values in one map (§4.1)

The schema map is a value of the kernel's namespace type `schema`, whose `value_type` is `declared`: each entry's
value names its type by its tag. `declared_type => sum & {}` and `declared => !declared_type {}` are the kernel's, so
the schema map's own type is closed over the kernel alone (`design/declared-positions.md`). A tag naming a
constructor builds a type; a tag naming any other type builds a value of it. `top` is the root of the constructors
only, never of what an entry holds.

The motivating case is an HTTP interface: a set of methods, each naming its request and response types, which must
sit at the schema layer because that is the only layer able to name a type *by name* -- a data document can only
instantiate one. A schema declares the record and a namespace type holding it, and a schema importing it holds a
value:

```
method    => { request?: kind<record>  response?: type_kind  errors?: [kind<record>] }
interface => !namespace { key_type: method_name  value_type: method }
binding   => { method: ref<method>  verb: http_verb  route: text }
bindings  => !namespace { key_type: identifier  value_type: binding }
```

`orders => !interface { place_order => { request: place_order_request  response: order } }` and `routes => !bindings
{ post_orders => { method: orders.place_order  verb: POST  route: "/orders" } }`. A single record value is as good:
`create_order => !operation { … }`. The corpus fixture `meta-service.tn` is the worked case.

**Why the tag and not a marker.** The entry is a value the schema describes: its keys are judged by `key_type`, its
values by `value_type` with every field state and atom constraint the record declares, and a qualified name walks it
through the schema rather than through whatever object a host built. Reading it as a value of its type gives one
rule for what a schema map holds and what a data document holds, and keeps `Top` closed: every branch but
`ValueEntry` mirrors a kernel constructor, and `ValueEntry` wraps a value whose type the schema declares.

**A namespace type declares only its facets.** `namespace`'s own record reader refuses any other field, so
`api => !namespace { key_type: identifier  value_type: method  extends: [a] }` fails at the read ("unknown field").
A construct with fields of its own and a map of members is a record holding both, under a namespace type's key:

```
method_map => !namespace { key_type: method_name  value_type: method }
interface  => { extends?: [type_name]  methods: method_map }
interfaces => !namespace { key_type: identifier  value_type: interface }
```

**Not a refinement of `map`.** §5.7 forbids a refinement to make a required field optional, and `map`'s `key_type`
is required, so `namespace` is `map`'s sibling under `product`: `kind<map>` does not hold a namespace type, and a map
that is no namespace is never entered by a qualified name. Both compilers read a namespace type through the map
factory with the map `NamespaceBody.read()` states (`design/linking-and-compilation.md`).

**A kind is judged by origin.** `EntryKind.of` counts the base kinds only as the kernel's own entries, and kind
membership walks construction to the kernel's `namespace`; a schema declaring a `namespace` of its own meets §2.2.3's
collision first, since every closure reaching the kernel holds its entry.

## Value entries (`DefinitionResolver.resolveValueEntry`)

`resolveInstance` resolves `T` in the schema's one namespace, refuses a template head (it closes by application),
and asks `EntryKind.isConstructor`. A constructor builds a type; anything else goes to `resolveValueEntry`, which
refuses a head that is itself a value (a value has no instances) and a braced payload at an atom type (almost always
an atom refinement missing its `^`), then reads the payload against `T` (`SchemaResolver`'s `value`) and records `T`
as the entry's constructor fact, so resolved output writes the tag. A payload that is no value of `T` is refused
against the entry with the read's path.

A value entry's payload is a **value body** (`TsonReadContext.readsValueBody`): nothing in it lifts or mints, so a
kind-typed slot holds one bare name and an application (`request: "box<order>"`) is refused with the alias spelling
named (`order_box => box<order>`, then `request: order_box`). A template over an ordinary type closes to a value entry
(`fetch => <T> !operations { … }`, `get_order => fetch<order>`).

**§8.2 reaches the payload at the read, not at the link.** The linker cannot see into a value -- its fields' types are
left in the declaring schema -- so the payload is read under the loader's identifier policy (`SchemaResolver`'s `value`)
and judged as a data document is: the keys of the namespace and of every identifier-keyed map in it are scopes, and an
identifier-typed value is a name (`design/name-hygiene-and-minted-names.md`).

## Binding: one context, and a tree where nothing binds

`Tson.of` builds one `DataBindContext` from the configured one (`SchemaMetaNameBinder.over`): the consumer's binder,
profile and atoms (`DataBindContext.toBuilder`, `nameBinder`), composed with `SchemaMetaNameBinder.INSTANCE`, which
answers for exactly the declarations of meta-kernel.tn and meta.tn (read from the bundled sources) -- so a kernel name
can never be rebound, and a consumer's `token` is the consumer's although `schema.meta` has a `Token` -- and the
schema model's own registrations added where absent (`ResolverBindContext.registerAbsent`). The resolution core
resolves schemas through it and the bind registry reads data through it, so `create_order => !operation { … }` and a
data document's `!operation { … }` bind to the same class. A class implements nothing; `@Typename` is needed only
where the binder's convention would not find it. `SchemaMetaNameBinder.extendedWith`/`contextExtendedWith` build the
same composition for a caller assembling a context themselves.

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

A slot naming a type is typed by a reference over a constructor, `request: kind<record>`, and a slot naming a value
by a reference over any other type, `method: ref<method>`; the spelling is a convention, `of` decides
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
the value the name reaches when the schema links (`Ref.deliver`): the bound object, the `Namespace`, or the tree. A host
holding the slot as a `String` gets the atom's reader wrapped in a `RecordedNameReader`, which refuses what is no name
as a `TypeRef` host's reader would; the name is recorded by the atom reader either way, so `method: nobody.place` is
refused under a `String` exactly as under a `TypeRef`.

**In a data document a kind is judged by membership**, over the reading document's closure, and a value's qualified
name at a reference over any other type is `NOT_IMPLEMENTED` at read (nothing links a data document). So a data
document importing only meta.tn that writes `!record_field { type: int32 }` is refused, `int32` being core's
(`design/kinds-and-name-types.md`).

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
with, passed by `TsonCompiledMetaRegistry.link` to `TsonSchemaLinker.link`. A tree-held value is read by field name.
A FIXED field may have no component, its value being the schema's, and naming a value through one is the one field
step reported as a gap.

**Refusals and kinds.** A value by name may name a value entry: a slot holding a value is not a type position. A
value entry named where a type is wanted is refused in one sentence wherever it is met: a value of its type, and
nothing can be typed by it. `TypeInhabitance` calls a `ValueEntry` inhabited and `DiscriminationClass` gives it no
class, and both are right only because nothing may name one as a type -- which is what makes that refusal
load-bearing rather than a nicety.

## Reading and writing

**Four places build the `Namespace`.** A namespace type's own reader is its map's, and in bind mode it yields an
`AnnotatedMap`, keeping each key's annotations and order (`MapBindReader`); `NamespaceInstanceReader` wraps it where
the position asks for the instance: the resolver's read of a value entry's payload (`SchemaResolver.asValue`), a
`declared` position whose tag selects a namespace type (`DeclaredReader`, the kernel `schema` map's values), a
namespace-typed slot whose host component is a `Namespace` (`BindTargets`, which is how a record's `fields` binds),
and a root read asked for `Namespace.class` (`TsonObjectReader`). A host declaring a `Map` at a namespace-typed slot
receives the map; a `Namespace` component at a map that is no namespace has no type to name and is a bind mismatch.

**`Namespace` is no `Map`.** A binder or writer testing `instanceof Map` would lose the type, so it has its map's read
API and nothing more: iteration is over `entries()`, and `RecordBody` compares its `field_map` in order itself, order
being the `ordered` facet and not part of equality. Its `type` names the namespace type; it carries no `@Typename`.

**Resolved output is written as written.** `DataClassObjectWriter` writes a `ValueEntry` as its `type` for the tag
and then the value: a `Namespace` as its map, each key with its annotations, bare where it is an identifier and
quoted otherwise; a bound object through its descriptor; a tree through the tree writer. A `Namespace` at a slot its
host types as one is written untagged (`fields: { name => … }`). A value by name is a `TypeRef` and is written as the
name written (§8.3). The kernel's `schema` reader marks its read as a schema document's (`readsSchemaNames`), so a
resolved form read as data reads its names by grammar alone, and their membership is ingest's
(`design/kinds-and-name-types.md`).

**A value entry compiles to a refusal.** Nothing is read against a value, so `TsonSchemaCompiler` gives a value entry
a `ValueEntryReader`, which reports `TYPE_MISMATCH` (write `!api` for a value of that type) and skips the value;
`JsonSchemaCompiler.constructorOf` names the value's type, which has no factory, and the entry compiles to an
`ErrorReader`. A constructor this library has no factory for compiles to an `ErrorReader` carrying the cause
(`TsonCompiledMetaSchema`'s `unbuildable`), kept rather than dropped; `CoreSchemaImportTest` asserts no entry of
core.tn or net.tn compiles to one.

## One namespace, so shared vocabulary sits beside the types

A schema declaring `method` puts its shared types beside it -- a `status_code` atom, an envelope template -- and every
schema importing it names them: an `!!import` contributes its whole namespace (SPEC-FEEDBACK.md #7). The cost is
reservation: every name in the closure is taken, so a schema importing core.tn cannot declare `identifier`, `record`,
`namespace` or `title` of its own (`MetaLayerVocabularyTest`). The one thing a schema cannot do is hold a value of a
type it declares itself: `!api { … }` beside `api => !namespace { … }` waits on `api` being resolved first, which
outside the kernel's bootstrap is a gap.
