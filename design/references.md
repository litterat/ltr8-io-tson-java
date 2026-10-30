# References: the forms a name takes, and the two facts a reference carries

Design notes for every position in a schema that holds a name: which are references, what a reference's store and
judgement are, and which kernel slot is typed by what. Current form only; history lives in git. The running shape,
one reference atom with a constructor an ordinary record type, is SPEC-FEEDBACK.md #11's; an application is a value
of that atom, a reference carrying an argument list and written as data grammar ([TSON-DATA] §7.4), SPEC-FEEDBACK.md
#12's. What extends this account, a channel of
its own for a record template's parameter, is `BACKLOG.md`'s parameter-channel entry.

**Invariants**

- A name held in a slot is a value of the kernel's one atom of names, `reference => atom & { of: type_name }`
  (`ReferenceBody`). It admits a name exactly when the type of the named
  entry's value, its tag, past its alias hops, IS-A `of`: `order => { … }` holds a value of `record`, `int32 =>
  !integer ^ { … }` one of `integer_type`, `orders => !api { … }` one of `api`. `kind => <T> !reference { of: T }`
  and `ref => <T> !reference { of: T }` are its two spellings, so `kind<order>` and `ref<order>` mint one entry;
  `identifier_kind`, `atom_kind` and `type_kind` are `!reference { of: … }`. A field's type position, `kind<record>`
  and `ref<method>` differ only in `of`.
- **What `of` names decides what the slot holds, never the spelling** (`KindMembers.namesTypes`). Over a constructor,
  `top` or IS-A it, the values admitted are types: the slot holds a type's name, one segment, judged a member over the
  governing closure (`KindMembers`) and delivered to a host as a `TypeRef`. Over any other type the values admitted
  are values: the slot holds a value's qualified name, judged at link (`ValuesByName`) and delivered as the referent
  (`Ref<T>`). So `kind<bar>` over a record type names values of `bar`, `ref<record>` is `kind<record>`, and
  `kind<api>` names `myapi`. These notes call a reference over a constructor a *kind*, whichever spelling wrote it.
- The linker stores that answer in `ReferenceBody`'s derived `@Unbound` `index` (`Index(namesTypes, members,
  applications, entries, shown)`: the entry each application the closure closed denotes, an `@application` or an
  expansion's `EntryFacts.closes`; the final namespace's entries, which a reference read here names; and the members
  as a message names them, a minted one by its application), and `ValuesByName` re-derives it from `of` over the
  final namespace whatever the read guessed; `TsonSchema.SlotType`'s `KIND` and `REFERENCE`
  roles are only how the read read the name. The index is never written: a resolved form states
  `!reference { of: … }` alone, a written `members:` is refused as an unknown field, and equality and `toString`
  exclude it (`ReferenceBody.memberNames()` is the processor's own).
- `of` names any type or constructor of the writer's namespace. Refused are a name resolving to nothing (an unresolved
  reference), an open entry and a value entry (`KindMembers.ofRefusal`, from `validateBody`). `kind<top>` holds every
  type. `kind<alias>` admits nothing, no entry past its hops being an alias, and a required field typed by it is
  refused by §5.10.1's productivity check (`TypeInhabitance`). A reference is not a bound: `template_param.bound`
  still refuses a constructor (`ParameterTypes.requireType`), a bound being IS-A, which no type argument is of a constructor.
- **A reference may carry an argument list** (SPEC-FEEDBACK.md #12), and that is what an application is in a slot.
  It is data grammar ([TSON-DATA] §7.4): `reference = qualified-name "<" ws type-args ws ">"`, the `<` adjacent to the
  name (§7.5), arguments separated by whitespace or a comma, each a reference (a qualified name, with or without
  arguments) or any other token, a number or a quoted token, which is a value. **A quoted token is never a
  reference**: a reference is written unquoted wherever it stands, `!alias { target: result<T> }`,
  `@application:response<order 201>`, `parents: [result<T>]`, and a reference without arguments is one token. The
  stream assembles one into a single `ReferenceEvent` and the AST into a `ReferenceValue`, each holding the parsed
  `TypeRef` (`design/lexer-and-data-parsing.md`); JSON, which has no unquoted form, holds the same text in a string,
  read by `io.ltr8.tson.atom.ReferenceGrammar`. The canonical text (`TypeRef.text()`) has no whitespace and one space
  between arguments, no comma, `map<text integer>`. Identity is the parsed form, never the text, so `result< T >` is
  `result<T>`. A qualified head parses (`geo.address<T>`); resolving a qualified name at a type position is this
  library's gap (`NOT_IMPLEMENTED`, `SchemaDesugarer.unqualified`, `KindParser`). The grammar classifies an argument,
  a reference first; the head's parameters judge whether it binds a type or a value (§5.10), so `TypeArgument`
  compares by its text (`Ref(red)` equals an unquoted `Value(red)`).
- A reference carries arguments only where an application is still open: a record template's `chain.parents` and
  field types, held text (`!alias { target: pair<uuid B> }` inside `template.template`), and the `application`
  annotation. A closed application lifts to the entry it denotes and is named bare (§5.3). Its head is judged by
  resolution and arity, and its arguments by the head's parameters: only an argument at a type parameter is judged
  as a type's name (`TsonSchemaLinker.typeArguments`).
- A slot that names the type of a value is typed `type_kind` (`record_field.type`, `tuple_element.element_type`,
  `array.element_type`, `map.key_type` and `value_type`, `namespace.value_type`, `choice.variants`,
  `template_param.type` and `bound`, `alias.target`, `type_chain.parents`, and the `application` annotation). Its
  value is one bare name, or, where an application is still open, a reference with arguments. In a schema
  document (a construction's or a value
  entry's payload, or a resolved form read as data) the reader reads its grammar alone and the name is judged a
  member where that schema links, or at ingest ([TSON-SCHEMA] §4.2, §8.1); in a data document the reader judges
  membership over the reading document's closure. `namespace.key_type` is typed `identifier_kind`: a namespace's keys
  are names, which is what a qualified name's segment selects, so an enum-keyed map is no namespace. A stated
  `key_type` is the family every key is read and judged under; an absent one is the value's host's to supply:
  `field_map`'s keys are the record's `name_type`'s, its supertypes' where it states none, and `field_name` where
  neither does.
- **A constructor is an ordinary record type** (SPEC-FEEDBACK.md #11). A constructor whose declaration is a record
  (`uuid_type => atom & { … }`) holds a value of `record`, so `type_kind`, `kind<record>` and `kind<product>` hold
  it, and `record`, `top`, `alias`, `template`, `namespace` and `reference` themselves. A field typed `spec:
  uuid_type` holds a declaration body as data (`!holder { spec: { version: 4 } }`), in a TSON tree, bound to the
  constructor's model class (`UuidType`) and read from JSON; `boxed<uuid_type>` and `[uuid_type]` close as any
  application does (`ConstructorAsRecordTypeTest`). A declaration read as data is judged by its record shape alone:
  it is not resolved, so a derived field such as `chain` is admitted as written.
- A namespace type is an instance of `namespace` (`field_map`, `schema`, a schema's own `api`), so `type_kind`,
  `kind<product>` and `kind<namespace>` hold it and `kind<record>` and `kind<map>` do not. A value entry, `myapi =>
  !api { … }`, is a member of no kind: its tag names an ordinary type, which no walk reaches a constructor through.
  `kind<api>` (one entry with `ref<api>`) names it, and every type position refuses it (`validateTypeRef`,
  `judgeMember`).
- Two positions single out a constructor, and keep an IS-A `top` check in Java: a `!C` head (a constructor builds a
  type, any other type a value entry) and a composition operand (constructor or record). `alias.target` admits
  either, an alias being applied as its target is (§3.3.1). No reference covers "a constructor" alone: its value is a
  record, as a record type's is, and the series has no classifier of constructors.
- `top` is a member of `type_kind`, `kind<product>` and `kind<record>`: a fresh abstract record IS-A nothing holds a
  value of `record` by the one predicate. `EntryKind.isConstructor` answers a different question, applicability, and
  counts `top` by name.
- A type parameter is one whose `type` is `type_kind` (`TemplateParam.TYPE_KIND`); a parameter standing at a
  `type_kind` slot is a type parameter (`ParameterTypes.typePosition`).
- A kind-typed slot whose host component is a `TypeRef` reads through `KindSlotReader`: a bare token through the
  kind's own atom reader (grammar, hygiene; members only where the document is data), recorded with its path and the
  slot's declared type (`TsonReadContext.heldByName`, `TsonSchema.SlotType`). A `ReferenceEvent` is an application,
  its `TypeRef` taken as the stream parsed it; a malformed one (`box<text`) is a parse error, and a quoted token,
  `"box<text>"`, is text, a `TYPE_MISMATCH` at a reference-typed position. In a kernel body an application is a
  value: resolved output holds one where an application is open, and
  a sugar lift writes one into the slot it fills, which the closer mints an entry for afterwards. In a value body
  (`TsonReadContext.readsValueBody`, a value entry's payload) nothing lifts or mints, so it is refused and the alias
  spelling named: `order_box => box<order>`, then the bare name. In a data document the kind's reader (`KindParser`)
  judges an application through the entry it denotes, resolved bottom-up by structural identity
  (`ReferenceBody.entryOf`): each argument that is itself an application first, then the application over those
  entries, against every application the closure closed, `@application`s and the `EntryFacts.closes` the resolver
  records (`TemplateMaterialiser.closedApplications`, generated heads skipped). A schema loads only when every
  application in it closes, so data never creates one: an application nothing in the closure closed is refused
  (`ATOM_CONSTRAINT_VIOLATION`, "names no type this schema closes"), as a name no member is. The name itself is
  recorded by the kind's `AtomTypeReader`, in a schema document only, and name hygiene judges each name an
  application writes.
- A recorded name carries its slot's declared type from the reader that read it (`TsonSchema.SlotType`: the role and
  the reference's `of`), so the linker never walks the path back through the meta's declarations, and a slot only a
  tagged subtype declares is judged however deep the value sits.
- A name at a reference-typed slot is recorded and judged whatever the host component holds, since the
  slot's `AtomTypeReader` records it: a host other than `TypeRef`/`Ref` (a `String`) receives what the slot's atom
  reads, through a `RecordedNameReader` that refuses what is no name as a `TypeRef` host's reader refuses it.
- A slot that names a value is typed by a reference over a type that is no constructor, `ref<method>` by convention
  and `kind<method>` alike. Its value is a qualified name. A slot typed by a record holds the record's value inline
  and takes no name.
- Any schema declares a reference (SPEC-FEEDBACK.md #7): the kernel's `kind` and `ref` are in every closure reaching
  meta.tn or core.tn, and `!reference { of: order }` applied directly is no different (`ReferenceDeclarationTest`). A
  value's qualified name is judged by what it reaches, which only a link can judge: a reference over a type that is
  no constructor reads its grammar in a schema document (`AtomParsers.inSchemaDocument`), and in a data document
  `AtomTypeReader` reports it as a gap (`NOT_IMPLEMENTED`), a data document never being linked.
- A recorded name at a kind-typed slot of a value body is judged a member by `ValuesByName.judgeMember`; a qualified
  name there is refused where it reaches a value, and is a gap where it would reach a type through a namespace of
  types. A recorded name at a slot over a type that is no constructor resolves its first segment in the schema map
  and each further one as a key of a namespace value or a field of a record value, never into a map that is no
  namespace; the referent's type IS-A `of`, and the value reached is delivered into the host's `Ref<T>`
  (`Ref.deliver`), a `TypeRef` host taking the name alone. A field step reads the record value the read bound,
  through its binding's descriptor, so a value reached through a field is delivered as one reached by key.
- One host shape for every reference: a `TypeRef`, whatever the slot, in a tree read as in a bound one (a `String`
  host receives its canonical text). Only the judgement differs by slot, and it is the slot's, never the host's.
- **A reference read in data finds what it names.** `TypeRef.referent()` is the value of the entry it denotes in the
  closure it was read under: `KindParser` attaches `ReferenceBody.closure()`, which resolves through `entryOf` over
  the index's `entries`, and every nested argument carries the same closure, so each level's referent resolves. The
  closure is a component excluded from `equals`, `hashCode` and `toString`. A reference read in a schema document,
  built by hand, or at a slot naming values (`RefParser`) carries `Closure.NONE` and names nothing. A schemaless read
  has no closure at all and keeps an application as a `TsonReference` (`design/tree-model.md`), with no referent.

Related: `design/linking-and-compilation.md` (the judgements), `design/held-template-bodies.md` (parameter
positions), `design/meta-layer-namespaces.md` (value entries' slots), `design/record-dispatch.md` (the positional form).

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
| **Application** | a reference carrying an argument list: names an open entry and closes it with arguments; a use site then holds a reference to the minted entry | the head, and the arguments |
| **Tag** | names the type of the value it is written on, `!T v` | store and judgement, as a reference, and the payload is read under the referent |
| **Index** | computed at link and written out: `supertypes`, `subtypes`, the `application` annotation | none; never read as a reference on ingest |

**Stores**, positional: the writer's namespace, its own entries and its import closure (every type a declaration
names, every `!C` and `!I ^` head, `reference.of`, a tag in a schema map, an annotation, the first segment of a
qualified name; SPEC-FEEDBACK.md #7); and the enclosing collection (`discriminators` and group members in `fields`, a
parameter in held text, the later segments of a qualified name).

**Judgement.** One predicate covers every reference row: *the referent's value is a value of `T`*. For a constructor
`T` the referent is a type built with `T` (`KindMembers`), a constructor whose declaration is a record being built
with `record`; for any other `T` it is a value entry, a key of a namespace value or a field of a record value whose
declared type IS-A `T`, which is §5.12's by-name slot (`ValuesByName`). The two relations behind it are distinct and
both recorded: IS-A (`supertypes`, inverse `subtypes`) and instance-of (the body's tag, inverse the names a kind
admits, derived at link and never written). Folding instance-of
into the chain would claim a value of `uuid` is a value of `uuid_type`, whose values are types, which it is not. A
namespace type is no exception: `api => !namespace { … }` is an instance of `namespace`, and a value of `api` is a map.

## The kernel's slots

| Position | Form | Store | Judgement | Where |
|---|---|---|---|---|
| schema-map key, `field_map` key, `template_param.name` | key | — | spelling under the key type; `fields` by the record's `name_type` after resolution | `checkNames`, `checkFieldNameTypes` |
| `enum_type.members` | values of a set | — | each a value of `name_type`, distinct under its equality | `checkEnumTypes` |
| `type_chain.parents`, `application` | reference, `type_kind`, with arguments where open | namespace | a constructor or a record type for an operand; an open entry or a parameter for a head; an argument at a type parameter a type's name | resolver, `checkArity`, `typeArguments` |
| `type_chain.supertypes`, `subtypes` | index | — | — | link |
| `alias.target` | reference, `type_kind` | namespace | any entry, type or constructor, by resolution; arguments only in an open alias's held text | `validateTypeRef` |
| `template_param.type`, `bound` | reference | namespace | `type_kind`; the argument is judged IS-A `bound` at the application | `ParameterTypes`, `TemplateMaterialiser.checkBound` |
| `template.template` | parameters and applications as parsed | `parameters`, then namespace | §5.10's shadowing rule | `HeldBody` |
| `record_field.type`, `tuple_element.element_type`, `array.element_type`, `map.key_type`, `map.value_type`, `namespace.value_type`, `choice.variants` | reference | namespace | `type_kind`; variants distinct and none void | `validateTypeRef`, `checkVariants*` |
| `record_field.value` at a kind-typed slot, a namespace type's `key_type` and `value_type` among them | reference | namespace | member of the slot's kind | `checkKindValue` |
| `record.discriminators`, `field_group.members` | reference by key | enclosing `fields` | a key of it | linker |
| `record.name_type`, `enum_type.name_type` | reference | namespace | `identifier_kind` | `NameType.admits` |
| `reference.of` | reference | namespace | any type or constructor, never a value entry or an open entry | `validateBody`, `KindMembers.ofRefusal` |
| a slot typed by a reference over a type that is no constructor | reference by key, a path | schema map, then namespace values and record fields | the referent's type IS-A `of` | `ValuesByName.judge` |
| a schema-map value (`schema.value_type: declared`) | tag | namespace | a constructor builds a type; any other type a value of it | `DefinitionResolver` |
| `myapi => !api { … }` | tag | namespace | a type that is no constructor; the payload a value of `api`, here a map of its keys and values | `DefinitionResolver` |
| `!I ^ { … }` head | reference | namespace | an atom instance, never the family's constructor | resolver |
| `A & B` operand, `A - { … }` source | reference | namespace | a constructor or a record type | resolver |

The schema map and a record's `fields` are values of the kernel's two namespace types, `schema` and `field_map`,
and in Java each is a `Namespace` (`TsonSchema.entries`, `RecordBody.fields`). A
namespace type is an instance of `namespace` stating its facets (`field_map => !namespace { value_type: record_field
ordered: true }`), resolved to a `NamespaceBody`, and a value of one is written and read as a map from its `key_type`
to its `value_type` (`NamespaceBody.read`). Its keys are the key rows above: of the stated family where `key_type` is
given, and where it is absent, of the family the host supplies, read by spelling and judged once the host resolves (a
record's field names by its `name_type`). Its values are judged by `value_type`.

An entry may itself be a value of a namespace type: `myapi => !api { get_orders => { response: order_list } }`,
in a schema importing one declaring `api => !namespace { key_type: identifier  value_type: method }`. Its tag is the
type, its keys the key rows above under `api`'s family, its values judged by `method`, and it is what a qualified
name's further segment enters (`myapi.get_orders` at a `ref<method>` slot). In Java it is a `ValueEntry` holding a
`Namespace`, and its kind is `VALUE`; a value of a record type, `create_order => !operation { … }`, is one too, and a
further segment selects its fields.

An annotation use `@name: v` is a reference by key into the annotating schema's namespace (§3.3.3). In a data
document a tag resolves in its schema's namespace (or the family's `subtypes` index, or the closure a `declared` position
admits), and a qualified name at a vocabulary-typed slot is a path of keys (§5.12).
