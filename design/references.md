# References: the forms a name takes, and the two facts a reference carries

Design notes for every position in a schema that holds a name: which are references, what a reference's store and
judgement are, and which kernel slot is typed by what. Current form only; history lives in git. The running shape,
one reference atom with a constructor an ordinary record type, is SPEC-FEEDBACK.md #11's; an application is a value
of that atom, a reference carrying an argument list and written as data grammar ([TSON-DATA] §7.4), SPEC-FEEDBACK.md
#12's. A record template's field names a whole-type or whole-value parameter under its own label (`type_param`,
`value_param`, `meta.record_template_field`), so only an argument of an application is a parameter recognised by its
text.

**Invariants**

- A name held in a slot is a value of the kernel's one atom of names, `meta.reference_type => meta.atom & { of:
  meta.type_ref  allow_sibling?: boolean ~ true }` (`ReferenceBody`). It admits a name exactly when the type of the
  named entry's value, its tag, past its alias hops, IS-A `of`: `order => { … }` holds a value of `meta.record`,
  `int32 => !integer ^ { … }` one of `numeric.integer_type`, `orders => !api { … }` one of `api`. The kernel's
  `meta.ref => <T> !meta.reference_type { of: T  allow_sibling: false }` is its one spelling, whatever `of` names, and
  an application through an alias of it applies the template (`TemplateMaterialiser.templateHead`);
  `meta.field_type_ref` is `!meta.reference_type { of: meta.type }`, `meta.type_ref` its refinement `^ {
  allow_sibling: false }`, and `meta.identifier_ref` a refinement of that to `unicode.identifier_type`, a refinement
  narrowing `of` to a type IS-A the source's (`ReferenceBody.ofNarrowing`). A field's type position,
  `meta.ref<meta.record>` and `meta.ref<method>` differ only in `of` and the facet.
- **What `of` names decides what the slot holds, never the spelling** (`KindMembers.namesTypes`). Over a constructor,
  `meta.top` or IS-A it, the values admitted are types: the slot holds a type's name, its path from the root (`order`,
  `geo.address`), judged a member over the governing closure (`KindMembers`) and delivered to a host as a `TypeRef`. Over any
  other type the values admitted are values: the slot holds a value's qualified name, judged at link (`ValuesByName`) and
  delivered as the referent (`Ref<T>`). So `meta.ref<bar>` over a record type names values of `bar`, `meta.ref<meta.record>`
  names record types, and `meta.ref<api>` names `myapi`. These notes call a reference over a constructor a *kind*, the
  concept of Part 2 §4.2: the set of types a constructor builds.
- **Every name resolves from the root** (SPEC-FEEDBACK.md #14). A name's first segment names an entry of the root
  namespace, the root's entries and the import closure, and each further segment selects in what it reached: a member
  of a nested `!schema` map, a key of a namespace value, a field of a record value. Inside `geo`, a sibling is
  `geo.address`, and a bare `address` is the root's or unresolved. There is no lexical scope, so no shadowing rule.
- The linker stores that answer in `ReferenceBody`'s derived `@Unbound` `index` (`Index(namesTypes, members,
  applications, entries, shown, values)`: the entry each application the closure closed denotes, an
  `@meta.application` or an expansion's `EntryFacts.closes`; the final namespace's entries, which a reference read here
  names; the members as a message names them, a minted one by its application; and, for a reference naming values in
  a whole link, `values`, a lookup (`ReferenceBody.Values`) that judges a qualified name over the final namespace by
  `ValuesByName`'s walk, empty in a link of a resolution in progress), and `ValuesByName` re-derives it from `of` over the
  final namespace whatever the read guessed; `TsonSchema.SlotType`'s `KIND` and `REFERENCE`
  roles are only how the read read the name. The index is never written: a resolved form states
  `!meta.reference_type { of: … }` alone, a written `members:` is refused as an unknown field, and equality and `toString`
  exclude it (`ReferenceBody.memberNames()` is the processor's own).
- `of` names any type or constructor of the writer's namespace. Refused are a name resolving to nothing (an unresolved
  reference), an open entry and a value entry (`KindMembers.ofRefusal`, from `validateBody`). `meta.ref<meta.top>` holds
  every type. `meta.ref<meta.alias>` admits nothing, no entry past its hops being an alias, and a required field typed by it
  is refused by §5.10.1's productivity check (`TypeInhabitance`). A reference is not a bound: `meta.template_param.bound`
  still refuses a constructor (`ParameterTypes.requireType`), a bound being IS-A, which no type argument is of a constructor.
- **A reference may carry an argument list** (SPEC-FEEDBACK.md #12), and that is what an application is in a slot.
  It is data grammar ([TSON-DATA] §7.4): `reference = qualified-name "<" ws type-args ws ">"`, the `<` adjacent to the
  name (§7.5), arguments separated by whitespace or a comma, each a reference (a qualified name, with or without
  arguments) or any other token, a number or a quoted token, which is a value. **A quoted token is never a
  reference**: a reference is written unquoted wherever it stands, `!meta.alias { target: result<T> }`,
  `@meta.application:response<order 201>`, `parents: [result<T>]`, and a reference without arguments is one token. The
  stream assembles one into a single `ReferenceEvent` and the AST into a `ReferenceValue`, each holding the parsed
  `TypeRef` (`design/lexer-and-data-parsing.md`); JSON, which has no unquoted form, holds the same text in a string,
  read by `io.ltr8.tson.atom.ReferenceGrammar`. The canonical text (`TypeRef.text()`) has no whitespace and one space
  between arguments, no comma, `map<text integer>`. Identity is the parsed form, never the text, so `result< T >` is
  `result<T>`. A qualified head (`geo.box<T>`) names a member of a nested schema map as a bare head names a root
  entry. The grammar classifies an argument, a reference first; the head's parameters judge whether it binds a type or a
  value (§5.10), so `TypeArgument` compares by its text (`Ref(red)` equals an unquoted `Value(red)`).
- **A reference may be a sibling reference** (SPEC-FEEDBACK.md #21), `kind!`: a record field's type chosen per instance
  by the value of the field `kind` of the same record. It is data grammar as well, `sibling-ref = unquoted-token "!"`,
  the `!` adjacent to one identifier and followed by whitespace ([TSON-DATA] §7.4, §7.5); JSON holds the same text in
  a string, read by the same production (`ReferenceGrammar.parseReference`). The reference atom reads one wherever a
  reference stands, and **whether a slot admits one is the facet `allow_sibling`**, a permission as `allow_nan` is of
  `float_type`: true by default, withdrawn by a refinement and never granted back (`ReferenceBody.constraintsCheck`).
  Only `meta.field_type_ref` keeps it, the type of `record_field.type` and `record_template_field.type`, the two model
  slots typed `Reference`, and each record's `value` is typed `type!` against it; `meta.type_ref` and `meta.ref<T>`
  withdraw it. Everywhere else a sibling reference is refused, naming the slot: in sugar by the desugar phase, in an
  explicit instance by the slot reader (`TypeNameSlotReader`, admitting one only for a `Reference` component at a
  reference that allows one), in an argument list by the grammar, and in a tree read by the atom's parser
  (`TypeNameRefParser`), whose refusal is a constraint the value breaks, `ATOM_CONSTRAINT_VIOLATION`. A reference over a
  type that is no constructor names values, and never holds one (`RefParser`). A sibling reference names a field and no
  type, so nothing records it for a member's judgement (`AtomTypeReader`). Identity is the variant and its components:
  `kind`, `kind<T>` and `kind!` are three references. The rules a sibling and its dependent field answer to, and the
  reading, are `design/schema-resolution.md`'s and `design/readers-and-diagnostics.md`'s.
- A reference carries arguments only where an application is still open: a record template's `chain.parents` and
  field `type`s, held text (`!meta.alias { target: pair<uuid B> }` inside `meta.template.template`), and the `application`
  annotation. A closed application lifts to the entry it denotes and is named bare (§5.3). Its head is judged by
  resolution and arity, and its arguments by the head's parameters: only an argument at a type parameter is judged
  as a type's name (`TsonSchemaLinker.typeArguments`).
- A slot that names the type of a value is typed `meta.type_ref` (`meta.tuple_element.element_type`,
  `meta.array.element_type`, `meta.map.key_type` and `value_type`,
  `meta.namespace.value_type`, `meta.choice.variants`, `meta.template_param.type` and `bound`, `meta.alias.target`,
  `meta.type_chain.parents`, and the `application` annotation), and so are `meta.reference_type.of` and the
  `meta.type_chain.supertypes` and `subtypes` indexes. Its value is a type's name, its path from the root, or, where an
  application is still open, a reference with arguments. `meta.type_name`, one identifier, is the schema map's key type and
  types no reference. In a schema document (a construction's or a value entry's payload, or a resolved form read as data) the
  reader reads its grammar alone and the name is judged a member where that schema links, or at ingest ([TSON-SCHEMA] §4.2,
  §8.1); in a data document the reader judges membership over the reading document's closure. `meta.namespace.key_type` is
  typed `meta.identifier_ref`: a namespace's keys are names, which is what a qualified name's segment selects, so an
  enum-keyed map is no namespace. `key_type` is required, the family every key is read and judged under. A record's fields
  are no namespace: `meta.field_map` is an ordered map keyed by `text`, each key judged by the record's `name_type`, its
  supertypes' where it states none, and `meta.field_name` where neither does.
- **A constructor is an ordinary record type** (SPEC-FEEDBACK.md #11). A constructor whose declaration is a record
  (`net.uuid_type => meta.atom & { … }`) holds a value of `meta.record`, so `meta.type_ref`, `meta.ref<meta.record>` and
  `meta.ref<meta.product>` hold it, and `meta.record`, `meta.top`, `meta.alias`, `meta.template`, `meta.namespace` and
  `meta.reference_type` themselves. A field typed `spec: net.uuid_type` holds a declaration body as data (`!holder { spec: {
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
- A type parameter is one whose `type` is `meta.type_ref` (`TemplateParam.TYPE_REF`); a parameter standing at a
  `meta.type_ref` slot is a type parameter (`ParameterTypes.typePosition`).
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
  entries, against every application the closure closed, `@meta.application`s and the `EntryFacts.closes` the resolver
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
- A slot that names a value is typed by a reference over a type that is no constructor, `meta.ref<method>`. Its value
  is a qualified name. A slot typed by a record holds the record's value inline and takes no name.
- Any schema declares a reference (SPEC-FEEDBACK.md #7): the kernel's `meta.ref` is in every closure,
  and `!meta.reference_type { of: order }` applied directly is no different
  (`ReferenceDeclarationTest`). A value's qualified name is judged by what it reaches, which a link judges: a
  reference over a type that is no constructor reads its grammar alone in a schema document
  (`AtomParsers.inSchemaDocument`, `RefParser.namesOnly`), the schema's own link judging the name; in a data document
  its atom (`RefParser`) asks the lookup the governing schema's link left on the reference (`ReferenceBody.Values`),
  so the name is judged by the walk a value body's names take and refused as `ATOM_CONSTRAINT_VIOLATION` where it
  reaches nothing, a type, or a value of another type. A field's default or pin at one is judged at link the same way
  (`checkFieldValue`, through `ValuesByName.reach`), refused against the entry declaring the field.
- A recorded name at a kind-typed slot of a value body is judged a member by `ValuesByName.judgeMember`; a qualified
  name there is refused where it reaches a value, and where it goes on past a type, which admits no further segment.
  `ValuesByName.judge` resolves a name's first segment in the root namespace, collapses each segment that selects a
  member of a nested `!schema` map onto that member's path entry, and reads each further one, at a slot over a type
  that is no constructor, as a key of a namespace value or a field of a record value (`geo.home.at`), never into a
  map that is no namespace; the referent's type IS-A `of`, and the value reached is delivered into the host's `Ref<T>`
  (`Ref.deliver`), a `TypeRef` host taking the name alone. A field step reads the record value the read bound,
  through its binding's descriptor, so a value reached through a field is delivered as one reached by key.
- One host shape for every reference: a `TypeRef`, whatever the slot, in a tree read as in a bound one (a `String`
  host receives its canonical text). Only the judgement differs by slot, and it is the slot's, never the host's.
- **A reference read in data finds what it names.** `TypeRef.referent()` is the value of the entry it denotes in the
  closure it was read under: `KindParser` attaches `ReferenceBody.closure()`, which resolves through `entryOf` over
  the index's `entries`, and every nested argument carries the same closure, so each level's referent resolves. Read
  in data at a slot naming values, its referent is the value its name reached (`RefParser`), the governing schema's
  own value, bound or tree-held as the value entry is. The closure is a component excluded from `equals`, `hashCode`
  and `toString`. A reference read in a schema document, or built by hand, carries `Closure.NONE` and names nothing.
  A schemaless read has no closure at all and keeps an application or a sibling reference as a `TsonReference`
  (`design/tree-model.md`), with no referent.

Related: `design/linking-and-compilation.md` (the judgements), `design/held-template-bodies.md` (parameter positions),
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
| **Application** | a reference carrying an argument list: names an open entry and closes it with arguments; a use site then holds a reference to the minted entry | the head, and the arguments |
| **Tag** | names the type of the value it is written on, `!T v` | store and judgement, as a reference, and the payload is read under the referent |
| **Index** | computed at link and written out: `supertypes`, `subtypes`, the `application` annotation | none; never read as a reference on ingest |

**Stores**, positional: the writer's namespace, its own entries and its import closure (every type a declaration
names, every `!C` and `!I ^` head, `meta.reference_type.of`, a tag in a schema map, an annotation, the first segment of a
qualified name; SPEC-FEEDBACK.md #7); and the enclosing collection (`discriminators` and group members in `fields`, a
parameter in held text, the later segments of a qualified name, each selecting in the nested `!schema` map, namespace
value or record value reached). Neither store is lexical: a name written inside a nested map resolves from the root
as one written anywhere else (SPEC-FEEDBACK.md #14).

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
| schema-map key, `meta.field_map` key, `meta.template_param.name` | key | — | spelling under the key type; `fields`, keyed by `text`, by the record's `name_type` after resolution | `checkNames`, `checkFieldNameTypes` |
| `meta.enum_type.members` | values of a set | — | each a value of `name_type`, distinct under its equality | `checkEnumTypes` |
| `meta.type_chain.parents`, `application` | reference, `meta.type_ref`, with arguments where open | namespace | a constructor or a record type for an operand; an open entry or a parameter for a head; an argument at a type parameter a type's name | resolver, `checkArity`, `typeArguments` |
| `meta.type_chain.supertypes`, `subtypes` | index, `meta.type_ref` | — | — | link |
| `meta.alias.target` | reference, `meta.type_ref` | namespace | any entry, type or constructor, by resolution; arguments only in an open alias's held text | `validateTypeRef` |
| `meta.template_param.type`, `bound` | reference | namespace | `meta.type_ref`; the argument is judged IS-A `bound` at the application | `ParameterTypes`, `TemplateMaterialiser.checkBound` |
| `meta.template.template` | parameters and applications as parsed | `parameters`, then namespace | §5.10's shadowing rule | `HeldBody` |
| `meta.record_field.type`, `meta.record_template_field.type` | reference, or a sibling reference | namespace; enclosing `fields` for a sibling | `meta.field_type_ref`; a sibling by §5.2's rules | `validateTypeRef`, `DependentFields` |
| `meta.tuple_element.element_type`, `meta.array.element_type`, `meta.map.key_type`, `meta.map.value_type`, `meta.namespace.value_type`, `meta.choice.variants` | reference | namespace | `meta.type_ref`; variants distinct and none void | `validateTypeRef`, `checkVariants*` |
| `meta.record_field.value`, `meta.record_template_field.value` at a kind-typed slot, a namespace type's `key_type` and `value_type` among them | reference (typed `type!`, so read against the field's own `type`) | namespace | member of the slot's kind | `checkKindValue` |
| `meta.record.discriminators`, `meta.field_group.members` | reference by key | enclosing `fields` | a key of it | linker |
| `meta.record.name_type`, `meta.enum_type.name_type` | reference | namespace | `meta.identifier_ref` | `NameType.admits` |
| `meta.reference_type.of` | reference, `meta.type_ref` | namespace | any type or constructor, never a value entry or an open entry | `validateBody`, `KindMembers.ofRefusal` |
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
