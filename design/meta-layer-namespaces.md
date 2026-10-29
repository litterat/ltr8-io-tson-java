# A meta layer's vocabulary: records under a namespace constructor

Design notes for the meta layer's extension point: how a schema declares vocabulary of its own that the schemas
importing it apply, how a consumer binds it, how the names it holds are judged, and what a meta layer is not. A meta
layer is any schema declaring constructors; nothing else sets it apart (SPEC-FEEDBACK.md #7).

**Invariants**

- A meta layer's own vocabulary is a record plus a namespace constructor of it: the layer declares `method => { … }`
  and `api => namespace & { key_type: = identifier  value_type: = method }`, and a schema importing it holds an
  instance, `myapi => !api { … }`. That entry is a `Namespace`, a value and never a type (`EntryKind.NAMESPACE`).
- `Top` is sealed all the way down (`permits Type, Reference, TemplateBody, Namespace`). A switch that dispatches on a
  body's kind is exhaustive with an explicit `Namespace` arm and no `default`: `TypeInhabitance`, `DiscriminationClass`,
  and the linker's `validateBody`, `collectNames` and coherence check.
- A host implements no interface. Each value of the map binds to a plain class the `DataNameBinder` finds
  (`SchemaMetaNameBinder.extendedWith`, `ProcessorConfig.withMetaNameBinder`); `@Typename` is needed only where the
  binder's convention would not find the class. There is no reader family and no `ValueReaderFactoryRegistry` entry.
- A record with no bound class is a `BIND_MISMATCH` at the entry that applies its namespace type.
- A host declares none of its references; the slot declares the spelling. A slot naming a type is typed by a kind
  (`request: kind<record>`), a slot naming a value by a reference (`method: ref<method>`), and a record-typed slot
  holds the record's value inline, a `TypeRef` or `Ref` component there being a bind mismatch. The read records each
  name with its path and the slot's declared type (`TsonSchema.valuesByName`, `TsonSchema.SlotType`), which the
  recording reader was built from, and the linker judges it by that type; nothing walks the path back through the
  meta's declarations, so a slot only a tagged subtype declares is judged at any depth. A name at a kind- or
  reference-typed slot is recorded and judged whatever the host component is; the hosts that receive the name as one
  are `TypeRef` and, at a reference-typed slot, `Ref<T>` (the name, and the value delivered at link), and any other
  (a `String`) receives what the slot's atom reads.
- A value by name is entered through namespace keys only. Its first segment resolves in the schema's one namespace,
  its own entries and its import closure; a further segment selects a key of a namespace value or a field of a record
  value; a map that is no namespace, `{text => method}`, is not entered whatever its key type.
- A segment selecting a field of a record value reads that field of the host object the read bound, through the
  descriptor of the bind context it was bound under, by wire name, never by reflection over the class; the registry
  links with that context (`TsonCompiledMetaRegistry.link`). An optional field the value does not hold is refused as
  not present, after the referent's type is judged.
- A read delivers a `Namespace` at four places and nowhere else: the meta's constructor reader for a namespace type
  (an `!api { … }` entry), `top`'s tag dispatcher, a namespace-typed slot whose host component is a `Namespace`
  (`BindTargets`), and a root read asked for `Namespace.class`. Everywhere else a namespace type reads as its map.
- The model holds every value of a namespace type as a `Namespace`: an entry applying one, the schema map
  (`TsonSchema.entries`, typed `schema`) and a record's fields (`RecordBody.fields`, typed `field_map`). `Namespace`
  has its map's read API and is no `Map`, and its equality is `type`, the entries as a map and each key's
  annotations, never order.
- An entry built by a constructor this library has no factory for compiles to an `ErrorReader`; it is never dropped.
- A `!!meta` target contributes its namespace exactly as an `!!import` does, so a layer's shared types sit beside its
  constructors and every schema reaching the layer names them; one name declared twice in a closure is §2.2.3's
  collision, by identity.
- A namespace type IS-A `top`, and any schema declares one, types a field by one, or holds a value of one as an
  entry. The entry applying it sits in a schema importing the one declaring it: a constructor applied in its own
  document is a gap, refused as `NOT_IMPLEMENTED` (`design/constructor-application.md`).
- A namespace type declares nothing but its facets, `key_type`, `value_type` and `ordered` (`MapBody.NAMESPACE_FACETS`):
  a value of it is the map alone, so the linker refuses a field beside them (`readAsMaps`), abstract or not, blaming
  the namespace type that declares it. A construct with fields is a record holding them, under a namespace type's key.

Related: `design/linking-and-compilation.md` (the namespace map and values by name), `design/references.md`,
`design/class2-compilation.md`, `design/compiled-registries.md`, `design/schema-resolution.md`.

## The extension point (§4.1)

The meta layer is the format's sanctioned extension point, and any schema may declare constructors of its own. The
question that needs an answer is where a meta layer's own vocabulary lands when what it describes is not a
data type. The motivating case is an HTTP interface: a set of methods, each naming its request and response types,
which must sit at the schema layer because that is the only layer able to name a type *by name* — a data document can
only instantiate one.

`top` has two families of constructors, `type`'s, whose instances are types, and `namespace`'s, whose instances are
maps; `reference` and `template` compose `top` directly beside them. The meta layer uses the second: it declares a
record for one item of its vocabulary and a namespace type holding it, and an importing schema's entry is an instance
of that namespace. The corpus fixture `meta-service.tn` is the worked case:

```
method    => { request?: kind<record>  response?: type_kind  errors?: [kind<record>] }
interface => namespace & { key_type: = method_name  value_type: = method }
binding   => { method: ref<method>  verb: http_verb  route: text }
bindings  => namespace & { key_type: = identifier  value_type: = binding }
```

and a schema importing it writes `orders => !interface { place_order => { request: place_order_request  response: order
} }` and `routes => !bindings { post_orders => { method: orders.place_order  verb: POST  route: "/orders" } }`.

**Why a namespace and not a marker kind.** The entry is a map the schema describes: its keys are judged by `key_type`,
its values by `value_type` with every field state and atom constraint the record declares, and a qualified name walks
it through the schema rather than through whatever object a host built. A marker kind for "an entry that is not a
type" would leave the entry's value opaque, its shape the host's, and `Top` with an open branch the consumer's classes
implement. Under a namespace constructor every branch of `Top` mirrors a kernel constructor, so a body's kind is
decidable by inspection and every switch over it is exhaustive.

**A namespace type declares only its facets.** A value of `api` is the map `key_type` and `value_type` describe and
nothing else, so `api => namespace & { key_type: = identifier  value_type: = method  extends?: [type_name] }` declares
a field no value can carry: `!api { extends: [a] … }` is a record field where the map wants a key, and `extends => …`
is just a key. The linker refuses the declaration rather than leave the field dead, and refuses it on an abstract
namespace type too, since every subtype would inherit it; a subtype is not blamed again for a field it inherits from
a namespace type. A construct that has fields of its own and a map of members, a consumer's `interface` or `api` with
`extends` or `implements`, is a record holding both, held under a key of a namespace type:

```
method_map => namespace & { key_type: = method_name  value_type: = method }
interface  => { extends?: [type_name]  methods: method_map }
interfaces => namespace & { key_type: = identifier  value_type: = interface }
```

**A kind is judged by origin.** Kind derivation counts `namespace` only as the meta-kernel's own entry. Every
closure reaching the kernel holds that entry, so a schema declaring a `namespace` of its own meets §2.2.3's collision
first.

## Binding: a plain class and a name binder

Each value of the map is an ordinary record value, read by the ordinary record reader straight into the host's class,
so §7.2 closure, field states and every atom constraint in the record's declaration are enforced exactly as for a
written body. The class implements nothing; the `DataBindContext`'s `DataNameBinder` has to be able to find it by the
record's name. A consumer composes rather than copies: `SchemaMetaNameBinder.extendedWith(theirs)` asks the kernel's
own vocabulary first and theirs only for a name it does not know, so the kernel's table is never duplicated and nothing
shadows it. `contextExtendedWith` is that binder in a ready-made context, and `ProcessorConfig.withMetaNameBinder` is
the same seam through the front door — the resolution core's *mode* is fixed (bind, always), which names it knows is
not. `@Typename` states the name a class binds under where the binder's convention would not find it.

**A record with no resolvable class is an error where it is written**, not a value carried in some generic form: a
schema asserting structure nothing can interpret is worth failing on. It is a `BIND_MISMATCH` against the entry that
applies the namespace type (`/pings` for `pings => !notifications { ping => … }`), since a missing line of wiring says
nothing about the schema (`design/schema-side-diagnostics.md`).

## Names inside a value: the slot declares the spelling (§4.2, §5.12)

A slot naming a type is typed by a kind, `request: kind<record>`, and a slot naming a value by a reference,
`method: ref<method>`; a slot typed by a record holds the record's value inline. The read records every name it
produces at a kind- or reference-typed slot with the path it descended (`["post_orders", "method"]`) and the slot's
declared type (`TsonSchema.SlotType`: the role, kind, reference or `type_ref` application, and the kind's `of` or the
reference's `T`, in the schema's one namespace). `SchemaResolver` keeps them per entry in
`TsonSchema.valuesByName`, and the linker judges each by that type: a member of the kind (`ValuesByName.judgeMember`),
or a value IS-A the reference's `T` (`ValuesByName.judge`). The type comes from the reader that read the name, which
was built from the slot's declaration, so it is right however the value was reached -- under a key, behind a record
field, through the subtype a tag selected -- and the path serves only to say where. A payload's Java shape says nothing
about which components are references, so nothing asks it.

**One host shape for every reference.** A `type_ref` slot binds a `TypeRef`, a kind-typed slot a `TypeRef`, and a
reference-typed slot a `Ref<T>` or a `TypeRef`; `BindTargets` sets a `KindSlotReader` or `ReferenceNameReader` in
place of the slot's atom reader for them, and a `Ref` receives the value the name reaches when the schema links
(`Ref.deliver`), so a host reads `binding.method().get()` after the link and the name before it. Any schema declares
a reference (`ReferenceDeclarationTest`, `design/references.md`).

**The slot judges the name, never the host.** A host may hold a kind- or reference-typed slot as a `String`: the slot's
atom reader binds it as any atom, and `BindTargets` wraps it in a `RecordedNameReader`, which records the name it read
with the slot's declared type, so `method: nobody.place` and `request: no_such` are refused under a `String` exactly as
under a `TypeRef`. The spelling is the slot's too: what is no name (a quoted token, a braced value) is refused there by
the reader a `TypeRef` host would have, at a reference-typed slot in any schema document and at a kind-typed slot in a
value body.

**A value body lifts and mints nothing.** A namespace type's payload is a value body (`SchemaResolver.isValueBody`,
`TsonReadContext.readsValueBody`): a kind-typed slot there holds one bare name, and a braced application is refused
with the alias spelling named (`order_box => box<order>`, then `request: order_box`), since no closer will mint an
entry for it. A `type_ref` slot there is judged by resolution, arity and §4.1 (`ValuesByName.judgeApplication`).

**§8.2 reaches the payload at the read, not at the link.** The linker sees a bound value as an opaque object, so
`SchemaResolver` reads a namespace type's payload under the loader's identifier policy (`payloadNames`), and the
readers judge it as they judge a data document: the keys of the namespace and of every identifier-keyed map in it are
scopes, and an identifier-typed value is a name (`design/name-hygiene-and-minted-names.md`).

## The walk: by key and by field (`ValuesByName`)

The types are walked through the schema: a record's fields to a field's type, a namespace type to its pinned
`value_type`, read off the map linking records for it (`TsonLinkedSchema.namespace`). The value is walked beside them.
A namespace entry is the `Namespace` the read built; a key selects a value of the constructor's `value_type`, and a
value of a namespace type held under a key or in a field is the map the read built for it, entered the same way
(`ValuesByName.asMap`, which accepts the host's `Map` there). So `handler: myapi.get_orders` reaches the `method`
value held under `get_orders`, `method: orders.place_order` the one under `place_order`, and `method:
shop.orders.methods.place_order` the one under `place_order` in the interface the `orders` service holds in its
`methods` field, each delivered into the host's `Ref`.

**A field step reads the host through its binding.** A segment past a key that selects a field of the record value
reached follows the record's declared fields: no such field is refused, listing the fields declared. The value is the
host object the resolver's read bound, so the field is read through the descriptor that bound it: the bind context's
`getDescriptor` of the host's class, its `fieldIndex` by the field's wire name, and that component's `get`. Raw
reflection over record components would bypass binding's own naming -- a component renamed with `@Field`, an
`Optional` component, a tagged subtype's class, a host that is no Java record -- and the descriptor carries all of
it. The context is the one the registry's resolver binds with: `TsonCompiledMetaRegistry.link` passes it to
`TsonSchemaLinker.link`, and `Tson` links through the registry. The resolver reads every namespace value in bind mode,
so a value in hand is a host object, never a `TsonValue`; a record no class binds is a `BIND_MISMATCH` at its entry
before any walk. An optional field the value does not hold names nothing, and is refused as not present after the
referent's type is judged, so a name wrong by type is told so whatever the value holds. A FIXED field may have no
component, its value being the schema's, and naming a value through one is the one field step reported as a gap.

**Refusals and kinds.** A value by name may name a namespace entry: it is a value, and a slot holding a value is not
a type position. A namespace entry at a type position is refused in every form: at a `type_ref` slot by
`validateTypeRef` in a type declaration and `judgeApplication` in a value body, and `request: orders` at a
`kind<record>` slot or `response: orders` at a `type_kind` slot as no member of the kind. A namespace entry is a
member of `kind<interface>` and `kind<namespace>`, the construction walk reaching `interface` from its recorded
constructor, and of no kind of types.
A namespace entry named where a type is wanted is refused in one sentence wherever it is met, `judgeApplication`'s and
`judgeMember`'s alike: a value of the namespace type, a map of names, and nothing can be typed by it.
`TypeInhabitance` calls a `Namespace` inhabited and `DiscriminationClass` gives it no class, and both are right only
because nothing may name one as a type — which is what makes that refusal load-bearing rather than a nicety.

## Reading and writing a namespace value

**Four places build the `Namespace`.** A namespace type's own reader is its map's
(`design/linking-and-compilation.md`), and in bind mode it yields an `AnnotatedMap`, keeping each key's annotations and
order (`MapBindReader`, which also carries the name of the constructor it reads). The map becomes an instance of the
constructor, wrapped in `NamespaceInstanceReader`, where the position asks for one: the meta's constructor reader for a
namespace type, which is what `!api { … }` as an entry reads through; `top`'s tag dispatcher
(`RecordTagDispatchReader`), for resolved output; a namespace-typed slot whose host component is a `Namespace`, where
`BindTargets` rebinds the map reader to the component's `entries` and wraps it, which is how a record's `fields` binds;
and a root read asked for `Namespace.class` (`TsonObjectReader`). A host declaring a `Map` at a namespace-typed slot
receives the map: the declared class is the host's statement of what it wants, and only a declared `Namespace` names
the constructor. A `Namespace` component at a map that is no namespace has no constructor to name and is a bind
mismatch.

**`Namespace` is no `Map`.** A binder or writer testing `instanceof Map` would lose the constructor, so it has its map's read
API and nothing more. Code that held `RecordBody.fields()` as a `Map` holds a `Namespace<RecordField>`:
`values().iterator().next()` replaces `firstEntry()`, and iteration is over `entries()`. Its `type` names the constructor it
is an instance of; it carries no `@Typename`, so `TsonSchema.constructorOf` and `TsonCompiledMetaSchema.typenameOf` read
`type`. Order is the constructor's `ordered` facet and not part of equality, so `RecordBody` compares its `field_map` in
order itself.

**Resolved output is written as written.** `myapi => !api { get_orders => { response: order_list } }`: the tag is the
value's `type`, which no Java class names, so `DataClassObjectWriter` writes a `Namespace` through `Top`'s descriptor
and emits the map itself, each key with its annotations, bare where it is an identifier and quoted otherwise, each
value tagged only where its class carries `@Typename`. The tag is written where the position admits more than one
constructor (`top`, a union); at a slot whose host types it as a `Namespace` the slot's type already names the
constructor, so the map is written untagged (`fields: { name => … }`). A value by name is a `TypeRef` and is written as
the name written, as a reference chain is (§8.3). It reads back through `top`'s tag dispatcher, which delivers a
`Namespace` where the tag selects a namespace type (`design/record-dispatch.md`).

## A constructor with no factory

**An entry built by a constructor this library has no factory for compiles to an `ErrorReader`** carrying the real
cause (`TsonCompiledMetaSchema.compile`'s `unbuildable`, naming the constructor and the schema that declared it). It is
kept, not dropped: the schema compiles, and only reading a value against that entry fails, with a message naming the
gap rather than the document that ran into it. Every constructor the kernel and meta.tn declare builds a real reader,
and `CoreSchemaImportTest` asserts that no entry of core.tn or net.tn compiles to an `ErrorReader`. A namespace entry
(`myapi => !api { … }`) compiles to an unbuildable `ErrorReader` in both stacks: `typenameOf` and
`JsonSchemaCompiler.constructorOf` read its `type`, a constructor with no factory, and nothing is typed by such an
entry. The reader `!api { … }` itself is read through is another thing: the instance reader compiled from `api`'s own
declaration, a map, which needs no factory (`design/compiled-registries.md`).

## What a meta layer is not

**A namespace is a constructor, not a map type or a base kind.** `Top` is the union of the bodies an entry can
hold, so a namespace value is one of its branches only as the instance of a constructor, as a `RecordBody` is an
instance of `record`. That decided the shape against three alternatives: a map type (`namespace => map & {}`, an
entry a value of it) would have made `myapi` a value with no constructor to be an instance of and needed a grammar
sentence of its own for the value entry; a base kind with a `members` field would have kept the kernel's own
`schema` and `fields` as maps beside the new kind and put a `.members` hop in every path; and `data` with a map field
was the interim that did the same with a `.methods` hop and a third family of constructors. The constructor gives
one reading rule, a value of a type IS-A `namespace` is written and read as a map from `key_type` to `value_type`,
and makes the kernel's namespaces and a meta layer's one type.

**One namespace, so shared vocabulary sits beside the constructors.** The instinct on declaring `method` in a meta
layer is to put the shared types beside it -- a `status_code` atom, an envelope template -- and let the schemas
importing the layer name them, and they can: a `!!meta` target contributes its whole namespace as an `!!import` does
(SPEC-FEEDBACK.md #7), whether a name is written bare, in a field, or applied with its arguments. The cost is
reservation: every name in the closure is taken, so a schema importing core.tn cannot declare `identifier`, `record`,
`namespace` or `title` of its own (`MetaLayerVocabularyTest`). The one thing a layer's own document cannot do is
apply its constructors to itself: `!api { … }` beside `api => namespace & { … }` waits on `api` being resolved
first, and outside the kernel's bootstrap that is a gap, refused as `NOT_IMPLEMENTED`; the instance goes in a schema
importing the layer.
