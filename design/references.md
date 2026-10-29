# References: the forms a name takes, and the two facts a reference carries

Design notes for every position in a schema that holds a name: which are references, what a reference's store and
judgement are, and which kernel slot is typed by what. Current form only; history lives in git. What extends this
account, qualified type names and one reference atom over `kind_type`, `ref_type` and the name-only `type_ref`, is
`BACKLOG.md`'s parameter-channel entry and a Revision 40 direction.

**Invariants**

- A slot that names the type of a value is typed `type_kind` (`record_field.type`, `tuple_element.element_type`,
  `array.element_type`, `map.key_type` and `value_type`, `namespace.value_type`, `choice.variants`,
  `template_param.type` and `bound`). Its value is one bare name, judged a member of the kind at link (`KindMembers`),
  never by the reader against the kernel's own members: a data document a meta layer governs describes a schema
  whose namespace it does not carry, so its kind slots are read for grammar alone and membership waits for ingest
  ([TSON-SCHEMA] §4.2, §8.1). `namespace.key_type` is typed `identifier_kind`: a
  namespace's keys are names, which is what a qualified name's segment selects, so an enum-keyed map is no namespace.
  A FIXED `key_type` is the family every key is read and judged under; a DEFAULT one stands where the value's host
  states none, and the host supplies it otherwise: `field_map`'s keys are the record's `name_type`'s, its supertypes'
  where it states none, and `field_name` where neither does.
- A constructor is no member of any kind, its instances being types, with one family excepted: a namespace
  constructor (the kernel's `namespace`, or one IS-A it) has maps for instances, so it is a type. `type_kind` holds
  `namespace`, `field_map`, `schema` and a meta layer's own namespace types; `kind<namespace>` holds every namespace
  constructor, each being a member of the kind of every namespace constructor it is or IS-A. A namespace type is a
  member of no other kind: its `!record` tag is not followed, a value of it being a map, so `kind<record>`,
  `kind<product>` and `kind<map>` exclude it. An entry that is an instance of one, `myapi => !api { … }`, is reached
  by the same walk through its recorded constructor, so `kind<api>` and `kind<namespace>` list it beside the types;
  no kind of types does, and every type position refuses it as a value (`validateTypeRef`,
  `ValuesByName.judgeApplication`).
- `type_ref` is the application form. It stands where an application may (`type_chain.parents`,
  `type_argument.name`, the `application` annotation, held text) and at `reference.target`, which may name a
  constructor. A `type_ref` slot is judged by resolution and arity alone.
- Three positions may name a constructor, and keep an IS-A `top` check in Java: `kind_type.of`, a `!C` head and a
  composition operand (constructor or record). `reference.target` admits either, an alias being applied as its target
  is (§3.3.1). No kind covers "a constructor": the series has no classifier of constructors.
- `top` is a member of `type_kind`, `kind<product>` and `kind<record>`: a fresh abstract record IS-A nothing is a type
  by the rule on `kind_type`, and the kernel's own `schema => namespace & { key_type: = type_name  value_type: = top }`
  depends on it. `EntryKind.isConstructor` answers a different question, applicability, and counts `top` by name.
- A type parameter is one whose `type` is `type_kind` (`TemplateParam.TYPE_KIND`); a parameter standing at a
  `type_kind` or a `type_ref` slot is a type parameter (`ParameterTypes.typePosition`).
- A kind-typed slot whose host component is a `TypeRef` reads through `KindSlotReader`: a bare token through the kind's own
  atom reader (grammar, hygiene; members only where the document is data), recorded with its path and the slot's declared
  type (`TsonReadContext.heldByName`, `TsonSchema.SlotType`). A braced value is read through the `type_ref` reader in a
  kernel body, where a sugar lift writes an application into the slot it fills and the closer mints its entry afterwards; in
  a value body (`TsonReadContext.readsValueBody`, a namespace type's instance) nothing lifts or mints, so it is refused and
  the alias spelling named: `order_box => box<order>`, then the bare name.
- A `type_ref` slot of a value body is recorded too (`RecordedApplicationReader`) and judged by resolution, arity and
  §4.1 (`ValuesByName.judgeApplication`), as the resolver judges a kernel body's own.
- A recorded name carries its slot's declared type from the reader that read it (`TsonSchema.SlotType`: the role and
  the kind's `of` or the reference's `T`), so the linker never walks the path back through the meta's declarations,
  and a slot only a tagged subtype declares is judged however deep the value sits.
- A name at a kind- or reference-typed slot is recorded and judged whatever the host component holds: a host other
  than `TypeRef`/`Ref` (a `String`) receives what the slot's atom reads, through a `RecordedNameReader` that records
  the name, and what is no name is refused there as a `TypeRef` host's reader refuses it.
- A slot that names a value is typed `ref<T>` (`ref_type => atom & { of: type_name }`, `ref => <T> !ref_type { of: T }`).
  Its value is a qualified name; `of` is a type of the writer's namespace, never a constructor (`ref<record>` is
  refused: that is `kind<record>`). A slot typed by a record holds the record's value inline and takes no name.
- Any schema declares a reference (SPEC-FEEDBACK.md #7): the kernel's `ref` is in every closure reaching meta.tn or
  core.tn, and `!ref_type { of: order }` applied directly is no different (`ReferenceDeclarationTest`). A reference's
  value is judged by what its name reaches, which only a link can judge: `AtomParsers` reads a `ref_type` position
  only in a document whose schema `governsSchemas` (a schema, which links), and refuses one in a data document as a
  gap (`UnsupportedOperationException`).
- A recorded name at a kind-typed slot of a value body is judged a member by `ValuesByName.judgeMember`; a qualified
  name there is refused where it reaches a value, and is a gap where it would reach a type through a namespace of
  types. A recorded name at a reference-typed slot resolves its first segment in the schema map and each further
  one as a key of a namespace value or a field of a record value, never into a map that is no namespace; the
  referent IS-A `of`, and the value reached is delivered into the host's `Ref<T>` (`Ref.deliver`), a `TypeRef` host
  taking the name alone. A field step reads the record value the read bound, through its binding's descriptor, so
  a value reached through a field is delivered as one reached by key.
- One host shape for every reference: a `TypeRef`, whatever the slot. Only the judgement differs by slot, and it is
  the slot's, never the host's.

Related: `design/linking-and-compilation.md` (the judgements), `design/held-template-bodies.md` (parameter
positions), `design/meta-layer-namespaces.md` (a meta's own slots), `design/record-dispatch.md` (the positional form).

**Why two facts, and why the store is positional.** Store and judgement are Strachey's environment and typed
pointer: under immutable, alias-free data the environment maps a name to an address, `(namespace, key)`, and a
reference is a pointer whose type names what the address must hold, so resolution is a load and equality is by
address. The systems that let a schema slot hold the name of a schema element (Xtext, Smithy, LinkML, TypeSpec,
Protobuf) all grew the same three constraints, token grammar, store and referent predicate, and differ only in where
they write them. The lesson they settle is to name the predicate on a reusable type, never per slot: LinkML and Xtext
repeat it at every slot, and a per-slot annotation was this design's own `bound` mistake. A symbol-space system (XSD,
LinkML, Xtext) selects the store from the referent's kind, which needs a closed metamodel; a meta-declared namespace
has no kind to key on, so here the store is positional, the kind is the slot's, and no slot states where its first
segment resolves. A kind is a predicate on type codes, a set of *names* and not a sum of types: the sum reading
(`kind<uuid_type>` as a computed choice) admitted an identifier token where a family's name was wanted, and the two
are indistinguishable on the wire. Deferred, not decided against: a `scope` facet stating a reference's store on
the slot (a reference into a sibling's referent), which no kernel row wants; a named value of atom type
(`max_items: default_limit`), where the token is ambiguous between a literal and a name and would need a marker on
that case alone; and the value-level case, a choice whose variants are computed over the entries of one atom type.

## The five forms

A name stands in a schema in one of five forms. Two are not references.

| Form | What the name does | Facts it carries |
|---|---|---|
| **Key** | declares: the name an entry, field, parameter or namespace value is addressed by | the map's key type, which judges spelling and equality |
| **Reference** | names something declared elsewhere, by its key | **store** (where the first segment resolves) and **judgement** (what the referent must be) |
| **Application** | names an open entry and closes it with arguments; a use site then holds a reference to the minted entry | the head, and the arguments |
| **Tag** | names the type of the value it is written on, `!T v` | store and judgement, as a reference, and the payload is read under the referent |
| **Index** | computed at link and written out: `supertypes`, `subtypes`, `members`, the `application` annotation | none; never read as a reference on ingest |

**Stores**, positional: the writer's namespace, its own entries and its import closure (every type a declaration
names, every `!C` and `!I ^` head, `kind_type.of`, a tag in a schema map, an annotation, the first segment of a
qualified name; SPEC-FEEDBACK.md #7); and the enclosing collection (`discriminators` and group members in `fields`, a
parameter in held text, the later segments of a qualified name).

**Judgement.** One predicate covers every reference row: *the referent's value is a value of `T`*. For a constructor
`T` that is `kind<T>`, the referent being a type built with `T`, less the constructors whose instances are types
(`KindMembers`); for
a type `T` it is a value entry or a key of a namespace value whose declared type IS-A `T`, which is §5.12's by-name
slot (`ValuesByName`). The two relations behind it are distinct and both recorded: IS-A (`supertypes`, inverse
`subtypes`) and instance-of (the body's tag, inverse `kind_type.members`). Folding instance-of into the chain would
claim a value of `uuid` is a value of `uuid_type`, whose values are types, which it is not. A namespace type is the
other way round: `api => namespace & { … }` is a composition, so `api` IS-A `namespace`, and a value of `api` is a
map.

## The kernel's slots

| Position | Form | Store | Judgement | Where |
|---|---|---|---|---|
| schema-map key, `field_map` key, `template_param.name` | key | — | spelling under the key type; `fields` by the record's `name_type` after resolution | `checkNames`, `checkFieldNameTypes` |
| `enum_type.members` | values of a set | — | each a value of `name_type`, distinct under its equality | `checkEnumTypes` |
| `type_chain.parents`, `type_argument.name`, `application` | application | namespace | a constructor or a record type for an operand; an open entry or a parameter for a head | resolver, `checkArity` |
| `type_chain.supertypes`, `subtypes`, `kind_type.members` | index | — | — | link |
| `reference.target` | reference | namespace | any entry, type or constructor; `type_ref`, by resolution | `validateTypeRef` |
| `template_param.type`, `bound` | reference | namespace | `type_kind`; the argument is judged IS-A `bound` at the application | `ParameterTypes`, `TemplateMaterialiser.checkBound` |
| `template.template` | parameters and applications as parsed | `parameters`, then namespace | §5.10's shadowing rule | `HeldBody` |
| `record_field.type`, `tuple_element.element_type`, `array.element_type`, `map.key_type`, `map.value_type`, `namespace.value_type`, `choice.variants` | reference | namespace | `type_kind`; variants distinct and none void | `validateTypeRef`, `checkVariants*` |
| `record_field.value` at a kind-typed slot, a namespace type's `key_type` and `value_type` among them | reference | namespace | member of the slot's kind | `checkKindValue` |
| `record.discriminators`, `field_group.members` | reference by key | enclosing `fields` | a key of it | linker |
| `record.name_type`, `enum_type.name_type` | reference | namespace | `identifier_kind` | `NameType.admits` |
| `kind_type.of` | reference | namespace | a constructor, `type` or `namespace`, or IS-A either | `KindMembers.ofRefusal` |
| `ref_type.of` | reference | namespace | a type or a namespace type, never a constructor of types | `validateBody` |
| a slot typed `ref<T>` | reference by key, a path | schema map, then namespace values and record fields | `ref<T>`: the referent IS-A `T` | `ValuesByName.judge` |
| `schema.value_type: = top` | tag | namespace | IS-A `top` | `DefinitionResolver` |
| `myapi => !api { … }` | tag | namespace | an instance of a namespace constructor, IS-A `top`; the payload a map of `api`'s keys and values | `DefinitionResolver` |
| `!I ^ { … }` head | reference | namespace | an atom instance, never the family's constructor | resolver |
| `A & B` operand, `A - { … }` source | reference | namespace | a constructor or a record type | resolver |

The schema map and a record's `fields` are values of the kernel's two namespace types, `schema` and `field_map`,
and in Java each is a `Namespace` (`TsonSchema.entries`, `RecordBody.fields`). A
namespace type is a constructor composed over `namespace` stating its facets (`field_map => namespace & { key_type?: ~
field_name  value_type: = record_field  ordered?: = true }`), and a value of one is written and read as a map from its
`key_type` to its `value_type`, never as a record of those facets (`TsonLinkedSchema.namespace`). Its keys are the key
rows above: of the pinned family where `key_type` is FIXED, and where it is a DEFAULT, of the family the host supplies,
read by spelling and judged once the host resolves (a record's field names by its `name_type`). Its values are judged
by `value_type`.

An entry may itself be a value of a namespace type: `myapi => !api { get_orders => { response: order_list } }`,
in a schema importing one declaring `api => namespace & { key_type: = identifier  value_type: = method }`. Its tag is the
constructor, its keys the key rows above under `api`'s family, its values judged by `method`, and it is what a
qualified name's further segment enters (`myapi.get_orders` at a `ref<method>` slot). In Java it is a `Namespace`,
the constructor's name beside the map, and its kind is `NAMESPACE`.

An annotation use `@name: v` is a reference by key into the annotating schema's namespace (§3.3.3). In a data
document a tag resolves in its schema's namespace (or the family's `subtypes` index, or the closure a `declared` position
admits), and a qualified name at a vocabulary-typed slot is a path of keys (§5.12).
