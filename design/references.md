# References: the forms a name takes, and the two facts a reference carries

Design notes for every position in a schema that holds a name: which are references, what a reference's store and
judgement are, and which kernel slot is typed by what. Current form only; history lives in git. The running shape
is one reference atom with a constructor an ordinary record type (§4.1, §4.2). A reference is a name: an application
is no value of that atom but the construction `meta.apply` (`ApplyBody`) written in place (§5.10). Inside an open record
a reference may also be one of the record's parameters, written as its name and looked up in `parameters` before the
namespace (`ParamRef`, `design/open-records.md`), at a type slot or among an application's arguments.

**Invariants**

- A name held in a slot is a value of the kernel's one atom of names, `meta.ref => meta.atom & { of: meta.type_ref }`
  (`RefBody`). It admits a name exactly when the type of the named entry's value, its tag, past its alias hops,
  IS-A `of`: `order => { … }` holds a value of `meta.record`, `int32 => !integer ^ { … }` one of
  `numeric.integer_type`, `orders => !api { … }` one of `api`. `meta.ref<T>` is the construction `!meta.ref { of: T }`
  (§5.3's construction sugar), whatever `of` names, and a refinement of `meta.ref` is a constructor IS-A it, so a
  question of whether a body is a reference goes by the constructor's chain, never its name;
  `meta.type_ref` is `!meta.ref { of: meta.type }`, and `meta.identifier_ref` a refinement of that to
  `unicode.identifier_type`, a refinement narrowing `of` to a type IS-A the source's (`RefBody.ofNarrowing`).
  `meta.type_ref`, `meta.ref<meta.record>` and `meta.ref<method>` differ only in `of`.
- **What `of` names decides what the slot holds, never the spelling** (`KindMembers.namesTypes`). Over a constructor,
  `meta.top` or IS-A it, the values admitted are types: the slot holds a type's name, its path from the root (`order`,
  `geo.address`), judged a member over the governing closure (`KindMembers`) and delivered to a host as a `TypeRef`.
  Over any other type the values admitted are values: the slot holds a value's qualified name, judged at link
  (`ValuesByName`) and delivered to a host as a `ValueRef<T>` holding the referent. So `meta.ref<bar>` over a record
  type names values of `bar`, `meta.ref<meta.record>` names record types, and `meta.ref<api>` names `myapi`. These
  notes call a reference over a constructor a *kind*, the concept of Part 2 §4.2: the set of types a constructor
  builds.
- **Every name resolves from the root** (§3.3, §5.12). A name's first segment names an entry of the root
  namespace, the root's entries and the import closure, and each further segment selects in what it reached: a member
  of a nested `!schema` map, a key of a namespace value, a field of a record value. Inside `geo`, a sibling is
  `geo.address`, and a bare `address` is the root's or unresolved. There is no lexical scope, so no shadowing rule.
- The linker stores that answer in `RefBody`'s derived `@Unbound` `index` (`Index(namesTypes, members, entries,
  shown, values)`: the final namespace's entries, which a name read here finds; the members as a message lists them, the
  names an author writes; and, for a reference naming values in a whole link, `values`, a lookup
  (`RefBody.Values`) that judges a qualified name over the final namespace by `ValuesByName`'s walk, empty in a link of
  a resolution in progress), and `ValuesByName` re-derives it from `of` over the final namespace whatever the read guessed;
  `TsonSchema.SlotType`'s `KIND` and `REFERENCE` roles are only how the read read the name. The index is never written: a
  resolved form states `!meta.ref { of: … }` alone, a written `members:` is refused as an unknown field, and
  equality and `toString` exclude it (`RefBody.memberNames()` is the processor's own).
- `of` names any type or constructor of the writer's namespace. It is a `TypeRef` at a slot that holds a name alone
  (§4.2): an application there, `!meta.ref { of: box<text> }` or `meta.ref<box<text>>`, is refused, to be
  declared under a name and named (`TypeSlots`' reference arm, `TemplateMaterialiser.nameRequired`), so a resolved `of`
  is a name. Refused too are a name resolving to nothing (an unresolved reference), an open record and a value entry
  (`KindMembers.ofRefusal`, from `validateBody`). `meta.ref<meta.top>` holds every type. `meta.ref<meta.alias>`
  admits nothing, no entry past its hops being an alias, and a required field typed by it is refused by §5.10.1's
  productivity check (`TypeInhabitance`). A reference is not a bound: `meta.param.bound` still refuses a constructor
  (`ParameterTypes.requireType`), a bound being IS-A, which no type argument is of a constructor.
- **A reference is a name, and an argument list is no data form** ([TSON-DATA] §7.2.5): in data and in a schema
  value alike, `set<text>` is a parse error at its `<`, the grammar's ordinary one for a reserved character. A
  reference's text is a qualified name (`ReferenceGrammar`), so a JSON string or a quoted token holding an argument
  list fails that grammar, a resolver error, the message saying the same (SPEC-FEEDBACK.md #2 holds whether a quoted
  token is a reference at all).
- **An application is `meta.apply` written in place** (`ApplyBody`): `head` a name, `arguments` names or parameters,
  exactly as the kernel types them (`[meta.type_ref; 1..]`). The schema grammar's `head<args>` at a type slot is its
  sugar, and the explicit `{ of: !meta.apply { … } }` means the same. A constructor's application is its construction
  before anything reads it (`ConstructionRewrite`, `design/constructor-application.md`), so an argument written in
  place is either a construction at a type slot or refused. The model is no wider than the kernel because a wider one
  is a shape the kernel's reader cannot produce, and carrying one means reading a payload with names standing in for the
  arguments and putting them back after: two opinions of what an application holds. In resolved output one stands only
  inside an open record, over one of its parameters, applying an open record; the linker refuses one anywhere else
  (`TsonSchemaLinker.validateBody`'s apply arm, `requireOpen`), which is how a resolved form read as output is held to
  it. `meta.apply` composes `meta.type` alone and is final: it has no kind (`EntryKind.of` refuses one), and no
  constructor refines it. Identity is the head and the arguments (`ApplyBody.equals`), so `CanonicalApplication` and
  the applied-parent fold compare structure, never text.
- **A dependent field's type is no reference** (§5.2): `kind!` is schema-grammar sugar for `{ of: !meta.dependent { on:
  kind } }`, a type written in place at the field's type (`design/schema-resolution.md`). No reference production,
  data or JSON, reads a marked name, so `kind`, `kind<T>` and a type written in place are the only shapes a type slot
  holds. A slot typed by a reference refuses `kind!` as the syntax error it is in data, and `"kind!"` in JSON as a
  string spelling no name.
- An application stays in place only where it is still open: an open record's `chain.parents` and field types, at
  any depth, and the `application` annotation's value. A closed application at a type slot is a type written in place
  there, and where only a name may stand an application is refused (§4.2, §5.3). Its head is judged by resolution and
  arity, and every argument is judged as a type's name.
- **An application whose head is a constructor constructs** (§5.3's construction sugar, `ConstructionSugar`): past
  its alias hops, a closed record IS-A `meta.type` is applied by building `!C { f₁: a₁ … fₙ: aₙ }`, its **argument
  slots** `f₁ … fₙ` being its required fields (not optional, role `FREE`) typed by a reference naming types
  (`KindMembers.namesTypes` over the field type's `of`), in declaration order. `meta.array<uuid>` is the construction
  `[uuid]` spells, written in place the same way; `meta.map<text int32>` is `{text => int32}`'s; a declared
  `uuid_list => meta.array<uuid>` *is* `!meta.array { element_type: uuid }`, tag `meta.array`, no `@meta.application`.
  The arity is the slot count (`ConstructionSugar.requireArity`) and every argument a reference by the grammar; every
  other verdict is the construction's own, so `meta.namespace<int32 text>` is refused at link as `key_type: int32` is,
  and `meta.enum_type<meta.identifier>` by the constructor's reader for the `members` it leaves unwritten. A refined
  constructor is applied the same way and its instance is its own kind: `nonempty_type<uuid>` is `!nonempty_type {
  element_type: uuid }`, a different entry from `[uuid; 1..]`. An application whose head is an open record
  substitutes (§5.10) -- an open record is never constructed, whatever its chain reaches -- and a head that is neither
  takes no arguments.
- A slot that names the type of a value is typed `meta.type_ref` (`meta.tuple_element.element_type`,
  `meta.array.element_type`, `meta.map.key_type` and `value_type`, `meta.namespace.value_type`, `meta.choice.variants`,
  `meta.param.bound`, `meta.alias.target`, and `meta.apply.head` and `arguments`), and so are `meta.ref.of`
  and the `meta.type_chain.supertypes` and `subtypes` indexes. Its value is a type's name, its path from the root.
  `meta.type_chain.parents` is typed by the type-slot choice, a parent being a name or an application in place, and the
  `application` annotation by `meta.apply`. `meta.type_name`, one identifier, is the schema map's key type and types no
  reference. In a schema document (a construction's or a value entry's payload, or a resolved form read as data) the
  reader reads its grammar alone and the name is judged a member where that schema links, or at ingest ([TSON-SCHEMA]
  §4.2, §8.1); in a data document the reader judges membership over the reading document's closure.
  `meta.namespace.key_type` is typed `meta.identifier_ref`: a namespace's keys are names, which is what a qualified
  name's segment selects, so an enum-keyed map is no namespace. `key_type` is required, the family every key is read and
  judged under. A record's fields are no namespace: `meta.field_map` is an ordered map keyed by `text`, each key judged
  by the record's `name_type`, its supertypes' where it states none, and `meta.field_name` where neither does.
- **A constructor is an ordinary record type** (§4.1). A constructor whose declaration is a record
  (`net.uuid_type => meta.atom & { … }`) holds a value of `meta.record`, so `meta.type_ref`, `meta.ref<meta.record>` and
  `meta.ref<meta.product>` hold it, and `meta.record`, `meta.top`, `meta.alias`, `meta.namespace`,
  `meta.ref` itself. An open record is built with `meta.record` too, and no kind holds it:
  a reference names it only as the head of an application (`KindMembers.isMember`). A field typed `spec: net.uuid_type` holds
  a declaration body as data (`!holder { spec: {
  version: 4 } }`), in a TSON tree, bound to the constructor's model class (`UuidType`) and read from JSON;
  `boxed<net.uuid_type>` and `[net.uuid_type]` close as any
  application does (`ConstructorAsRecordTypeTest`). A declaration read as data is judged by its record shape alone:
  it is not resolved, so a derived field such as `chain` is admitted as written.
- A namespace type is an instance of `meta.namespace` (`meta.schema`, a schema's own `api`), a refinement of `meta.map`,
  so `meta.type_ref`, `meta.ref<meta.product>`, `meta.ref<meta.map>` and `meta.ref<meta.namespace>` hold it and
  `meta.ref<meta.record>` does not. A value entry, `myapi => !api { … }`, is a member of no kind: its tag names an
  ordinary type, which no walk reaches a constructor through. `meta.ref<api>` names
  it, and every type position refuses it (`validateTypeRef`, `judgeMember`).
- Two positions single out a constructor, and keep an IS-A `meta.top` check in Java: a `!C` head (a constructor builds
  a type, any other type a value entry) and a composition operand (constructor or record). `meta.alias.target` admits
  either, an alias being applied as its target is (§3.3.1). No reference covers "a constructor" alone: its value is a
  record, as a record type's is, and the series has no classifier of constructors.
- `meta.top` is a member of `meta.type_ref`, `meta.ref<meta.product>` and `meta.ref<meta.record>`: a fresh abstract record
  IS-A nothing holds a value of `meta.record` by the one predicate. `EntryKind.isConstructor` answers a different question,
  applicability, and counts `meta.top` by name and origin.
- Every parameter is a type parameter, recording its name and the bound it is written with (`Param`); one stands only
  at a field's type or as an argument of an application there or in a parent (`design/open-records.md`).
- A kind-typed slot whose host component is a `TypeRef` reads through `TypeRefSlotReader`: a bare token through the
  kind's own atom reader (grammar, hygiene; members only where the document is data), recorded with its path and the
  slot's declared type (`TsonReadContext.heldByName`, `TsonSchema.SlotType`). An application is never a value
  there: in a kernel body the desugarer writes one as `meta.apply` in place for the materialiser to close. The name
  itself is recorded by the kind's `AtomTypeReader`, in a schema document only, and name hygiene judges each name an
  application writes.
- **In a data document a reference is a name** (§4.2). The kind's parser (`TypeRefParser`, shared by both
  encodings) refuses an application, `ATOM_CONSTRAINT_VIOLATION` at the slot, whether or not the closure closes it:
  `set<text>` is refused where the schema declares `names => set<text>`, and the document names `names`. The grammar
  reads it, so it is no parse error. Nothing in data is resolved against the applications a schema closed: the index
  holds no application map and no alias map, and a dependent field whose sibling holds an application is refused at
  the sibling (`DependentFieldReader.typeNamed`), which the sibling's own read has already done. Which mode a read is
  in is the reader's, never the document's: a schema document and a resolved form read through
  `AtomParsers.inSchemaDocument`'s grammar-only parser, so an application stays a value there (`readsSchemaNames`).
- A recorded name carries its slot's declared type from the reader that read it (`TsonSchema.SlotType`: the role and
  the reference's `of`), so the linker never walks the path back through the meta's declarations, and a slot only a
  tagged subtype declares is judged however deep the value sits.
- A name at a reference-typed slot is recorded and judged whatever the host component holds, since the
  slot's `AtomTypeReader` records it: a host other than `TypeRef`/`ValueRef` (a `String`) receives what the slot's atom
  reads, through a `RecordedNameReader` that refuses what is no name as a `TypeRef` host's reader refuses it.
- A slot that names a value is typed by a reference over a type that is no constructor, `meta.ref<method>`. Its value
  is a qualified name. A slot typed by a record holds the record's value inline and takes no name.
- Any schema declares a reference (§4.2): the kernel's `meta.ref` is in every closure,
  and `!meta.ref { of: order }` applied directly is no different
  (`ReferenceDeclarationTest`). A value's qualified name is judged by what it reaches, which a link judges: a
  reference over a type that is no constructor reads its grammar alone in a schema document
  (`AtomParsers.inSchemaDocument`, `ValueRefParser.namesOnly`), the schema's own link judging the name; in a data document
  its atom (`ValueRefParser`) asks the lookup the governing schema's link left on the reference (`RefBody.Values`),
  so the name is judged by the walk a value body's names take and refused as `ATOM_CONSTRAINT_VIOLATION` where it
  reaches nothing, a type, or a value of another type. A field's default or pin at one is judged at link the same way
  (`checkFieldValue`, through `ValuesByName.reach`), refused against the entry declaring the field.
- A recorded name at a kind-typed slot of a value body is judged a member by `ValuesByName.judgeMember`; a qualified
  name there is refused where it reaches a value, and where it goes on past a type, which admits no further segment.
  `ValuesByName.judge` resolves a name's first segment in the root namespace, collapses each segment that selects a
  member of a nested `!schema` map onto that member's path entry, and reads each further one, at a slot over a type
  that is no constructor, as a key of a namespace value or a field of a record value (`geo.home.at`), never into a
  map that is no namespace; the referent's type IS-A `of`, and the value reached is delivered into the read's `ValueRef<T>`
  (`ValueRef.deliver`), a `String` host taking the name alone. A field step reads the record value the read bound,
  through its binding's descriptor, so a value reached through a field is delivered as one reached by key.
- One host shape per slot, in a tree read as in a bound one: a `TypeRef` at a kind, a `ValueRef` at a reference naming
  values, each the other's bind mismatch, and a `String` host receives the name at either. `ValueRef` is a cell
  delivered once and compared by name; it shares no parent with `TypeRef`, a value being no type.
- **A reference read in data finds what it names.** A type's `TypeRef.referent()` is the value of the entry its name
  names in the closure it was read under: `TypeRefParser` attaches `RefBody.closure()`, which looks the name
  up in the index's `entries`. A value's `ValueRef` is delivered the value its name reached (`ValueRefParser`, over
  `RefBody.Values`), the governing schema's own value, bound or tree-held as the value entry is. The closure is
  a component excluded from `equals`, `hashCode` and `toString`. A reference read in a schema document, or built by
  hand, carries `Closure.NONE` and names nothing. A schemaless read has no closure at all and keeps a name as a
  `TsonAtom` holding its text (`design/tree-model.md`), with no referent.

Related: `design/linking-and-compilation.md` (the judgements), `design/open-records.md` (parameter positions),
`design/namespace-types-and-value-entries.md` (value entries' slots), `design/record-dispatch.md` (the positional form).

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
(`meta.ref<net.uuid_type>` as a computed choice) admitted an identifier token where a family's name was wanted, and the two
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
| **Application** | `meta.apply` written in place, no reference: names an open record and closes it with arguments, or names a constructor and constructs it over its argument slots; at a type slot it closes in place, and a declaration naming it is the closed entry | the head, and the arguments |
| **Tag** | names the type of the value it is written on, `!T v` | store and judgement, as a reference, and the payload is read under the referent |
| **Index** | computed at link and written out: `supertypes`, `subtypes`, the `application` annotation | none; never read as a reference on ingest |

**Stores**, positional: the writer's namespace, its own entries and its import closure (every type a declaration
names, every `!C` and `!I ^` head, `meta.ref.of`, a tag in a schema map, an annotation, the first segment of a
qualified name; §3.3); and the enclosing collection (`discriminators` and group members in `fields`, a
parameter in an open record's `parameters`, the later segments of a qualified name, each selecting in the nested
`!schema` map, namespace
value or record value reached). Neither store is lexical: a name written inside a nested map resolves from the root
as one written anywhere else (§3.3).

**Judgement.** One predicate covers every reference row: *the referent's value is a value of `T`*. For a constructor
`T` the referent is a type built with `T` (`KindMembers`), a constructor whose declaration is a record being built
with `meta.record`; for any other `T` it is a value entry, a key of a namespace value or a field of a record value whose
declared type IS-A `T`, which is §5.12's by-name slot (`ValuesByName`). The two relations behind it are distinct and
both recorded: IS-A (`supertypes`, inverse `subtypes`) and instance-of (the body's tag, inverse the names a kind
admits, derived at link and never written). Folding instance-of
into the chain would claim a value of `uuid` is a value of `net.uuid_type`, whose values are types, which it is not.
A namespace type is no exception: `api => !meta.namespace { … }` is an instance of `meta.namespace`, and a value of
`api` is a map.

## The kernel's slots

| Position | Form | Store | Judgement | Where |
|---|---|---|---|---|
| schema-map key, `meta.field_map` key, `meta.param.name` | key | — | spelling under the key type; `fields`, keyed by `text`, by the record's `name_type` after resolution | `checkNames`, `checkFieldNameTypes` |
| `meta.enum_type.members` | values of a set | — | each a value of `name_type`, distinct under its equality | `checkEnumTypes` |
| `meta.type_chain.parents`, `application` | reference, `meta.type_ref`, with arguments where open | namespace | a constructor or a record type for an operand; an open record or a constructor for a head; an argument at a type parameter a type's name | resolver, `checkArity`, `typeArguments` |
| `meta.type_chain.supertypes`, `subtypes` | index, `meta.type_ref` | — | — | link |
| `meta.alias.target` | reference, `meta.type_ref` | namespace | any entry, type or constructor, by resolution; never arguments in a resolved alias | `validateTypeRef` |
| `meta.param.bound` | reference | namespace | `meta.type_ref`; the argument is judged IS-A `bound` at the application | `ParameterTypes`, `TemplateMaterialiser.checkBound` |
| `meta.record_field.type` | reference, a parameter of an open record, or a type in place, `meta.dependent` among them | the record's `parameters`, then namespace; enclosing `fields` for a dependent type's `on` | `meta.type_expr`; a parameter by §5.10's shadowing rule; `on` by §5.2's rules | `validateTypeRef`, `checkOpenArity`, `DependentFields` |
| `meta.tuple_element.element_type`, `meta.array.element_type`, `meta.map.key_type`, `meta.map.value_type`, `meta.namespace.value_type`, `meta.choice.variants` | reference | namespace | `meta.type_ref`; variants distinct and none void | `validateTypeRef`, `checkVariants*` |
| `meta.record_field.value` at a kind-typed slot, a namespace type's `key_type` and `value_type` among them | reference (typed `type!`, so read against the field's own `type`) | namespace | member of the slot's kind | `checkKindValue` |
| `meta.record.discriminators`, `meta.field_group.members` | reference by key | enclosing `fields` | a key of it | linker |
| `meta.record.name_type`, `meta.enum_type.name_type` | reference | namespace | `meta.identifier_ref` | `NameType.admits` |
| `meta.ref.of` | reference, `meta.type_ref`; an application is refused | namespace | any type or constructor, never a value entry or an open record | `validateBody`, `KindMembers.ofRefusal` |
| a slot typed by a reference over a type that is no constructor | reference by key, a path | root namespace, then nested `!schema` members, namespace values and record fields | the referent's type IS-A `of` | `ValuesByName.judge` |
| a schema-map value (`meta.schema.value_type: declared`) | tag | namespace | a constructor builds a type; any other type a value of it | `DefinitionResolver` |
| `geo => !schema { … }` | tag, a spelling of the pre-loaded kernel's `schema` (an alias of `meta.schema`) or `meta.schema` | pre-loaded kernel | none: the payload is schema grammar, its members declarations keyed by path | `TsonSchemaParser`, `SchemaPaths` |
| `myapi => !api { … }` | tag | namespace | a type that is no constructor; the payload a value of `api`, here a map of its keys and values | `DefinitionResolver` |
| `!I ^ { … }` head | reference | namespace | an atom instance, never the family's constructor | resolver |
| `A & B` operand, `A - { … }` source | reference | namespace | a constructor or a record type | resolver |

The schema map is a value of the kernel's one namespace type, `meta.schema`, and in Java a `Namespace`
(`TsonSchema.entries`); a record's `fields` is a value of `meta.field_map`, an ordered map keyed by `text`, and in Java an
`AnnotatedMap` (`RecordBody.fields`). A namespace type is an instance of `meta.namespace` stating its facets (`api =>
!meta.namespace { key_type: meta.identifier  value_type: method }`), resolved to a `NamespaceBody`, and a value of one is
written and read as a map from its `key_type` to its `value_type` (`NamespaceBody.read`). Its keys are of the family
`key_type` states, and its values are judged by `value_type`.

An entry may itself be a value of a namespace type: `myapi => !api { get_orders => { response: order_list } }`,
in a schema importing one declaring `api => !meta.namespace { key_type: meta.identifier value_type: method }`. Its tag is the
type, its keys the key rows above under `api`'s family, its values judged by `method`, and it is what a qualified name's
further segment enters (`myapi.get_orders` at a `meta.ref<method>` slot). In Java it is a `ValueEntry` holding a `Namespace`,
and its kind is `VALUE`; a value of a record type, `create_order => !operation { … }`, is one too, and a further segment
selects its fields.

A value of `meta.schema` is the one namespace value that declares: `geo => !schema { address => { … } }` is a schema map
nested as an entry, and its members are entries of the document under their paths, so `geo.address` is a type, a
member of `meta.type_ref`, and `meta.ref<geo.address>` is a reference over it (`design/schema-resolution.md`).

An annotation use `@name: v` is a reference by key into the annotating schema's namespace (§3.3.3). In a data
document a tag resolves in its schema's namespace (or the family's `subtypes` index, or the closure a `declared` position
admits), and a qualified name at a vocabulary-typed slot is a path of keys (§5.12).
