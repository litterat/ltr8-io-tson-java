# Kinds and name types

Design notes for two link-time facts about names: a kind's members (`KindMembers`, §4.2), and what an enum's or a
record's `name_type` obliges (`NameType`, `EnumLabels`, `FieldNames`, §7.4, §5.2). Both need the merged closure, which
is why they are the linker's. Current form only; history lives in git.

**Invariants**

- A kind is the kernel's `meta.reference_type` atom (`ReferenceBody`; §4.2) over a constructor, `meta.top`
  or IS-A it, so its values are type names. What `of` names decides it (`KindMembers.namesTypes`), never the spelling,
  which is one, `meta.ref<T>`: `meta.ref<meta.record>` holds type names, and `meta.ref<api>`, over a type that is no
  constructor, names values (`ValuesByName`, `design/references.md`).
- Every reference's derived `index` (`Index(namesTypes, members, entries, shown, values)`: the members sorted; the
  final namespace's entries, which a name read in data finds through `ReferenceBody.closure()`; and the members as a
  message lists them, the names an author writes, a minted entry, `@meta.synthetic`, left out, since data names no
  application and never a name nobody wrote) is recomputed for **every** reference in the
  closure, imports included (`TsonSchemaLinker.computeKinds`), and never trusted from a body. It is `@Unbound` and
  never written: a resolved form states `!meta.reference_type { of: … }` alone, and a written `members:` is refused as an
  unknown field.
  `ReferenceBody`'s equality is `of` and the chain, never the index, so an importer re-deriving an import's kind
  unifies with it.
- `of` resolves in the schema's one namespace and names any type or constructor; refused are a name resolving to
  nothing, an open entry and a value entry (`KindMembers.ofRefusal`). A kind is not a bound.
- A constructor is a member where the walk says so, as any type is: one whose declaration is a record (`net.uuid_type
  => meta.atom & { … }`) holds a value of `meta.record`, so `meta.type_ref`, `meta.ref<meta.record>` and
  `meta.ref<meta.product>` hold it, `meta.namespace` and `meta.reference_type` among them. A namespace type is built with
  `meta.namespace`, a refinement of `meta.map`, so it is a member of `meta.type_ref`, `meta.ref<meta.product>`,
  `meta.ref<meta.map>` and `meta.ref<meta.namespace>`, and of no `meta.ref<meta.record>`. A value entry is a member of
  no kind: its tag names an ordinary type, which no walk reaches a constructor through.
- A refinement of a reference (`!meta.type_ref ^ { of: … }`) narrows `of` to the source's or a type IS-A it, judged
  at link (`ReferenceBody.ofNarrowing`), and is IS-A its source: `meta.identifier_ref` is `meta.type_ref` narrowed to
  `unicode.identifier_type`, its chain listing `meta.type_ref`. That edge is what admits `meta.namespace`'s restating
  `meta.map`'s `key_type` as `meta.identifier_ref` (`TsonSchemaLinker.checkRestatedFieldTypes`, §5.7).
- A kind-typed position is judged by membership in every data document, over the reading document's closure; in a
  schema document it reads its grammar alone and the link (or ingest) judges membership.
- An empty kind admits no value, and `TypeInhabitance` refuses a required field typed by it: `meta.ref<meta.alias>` is one,
  no entry past its hops being an alias.
- Whether a `name_type` referent may stand in its slot is membership of the slot's kind (`NameType.admits`, reading
  the kind off the constructor's `name_type` field), never a test of the referent's body class.
- A `name_type`, pinned by the constructor (traced through a template's held body) or written by the author,
  resolves in the schema's one namespace. `NameType` is the one place that resolves it, for enums and records alike,
  shared with materialisation.
- An enum's and a record's name type is an identifier family whose `normalization` implies NFC (`NONE` never;
  `ASCII_CASEFOLD` only over ASCII, `NameType.formRefusal`); their names are judged under its profile.
- The enum and record checks run before `checkNames`; an entry they refuse is not judged by name again.
- `FieldNames` never sees two fields of one spelling (`meta.record.fields` is a map, and its reader refuses the repeat), so
  its duplicate check is two spellings that are one value of the record's name type, and nothing else.
- `enumForms` and `nameTypes` are recorded on `TsonLinkedSchema` because no reader can recompute them.

Related: `design/linking-and-compilation.md` (the link order), `design/references.md` (which slot is typed by a kind),
`design/name-hygiene-and-minted-names.md` (the per-name rules a name type's profile feeds),
`design/namespace-types-and-value-entries.md`.

**What a kind is not.** Eight shapes were tried before the atom of names, and each fell for one reason, so none is
worth re-proposing: a refined `schema` type carrying the checks (§5.7 gives entry-membership only and tightens no
field to a choice of subtypes); per-slot checked annotations (`@fields_of`, `@typed_by`), which put the referent
predicate on every slot; naming roles (`meta.type_name`, `method_name` as instances of one constructor, "a role-keyed map
declares, any other slot references"), which fused token family, store and referent and broke at `meta.field_name`, whose
family is the record's; a namespace kind with a role lattice, the same fusion plus an alias rule letting a family
widen a role against §5.7; `name_type` with `target` and `relation` facets, where `relation` was two stores wearing
one enum; the predicate as facets on the name atom; `meta.ref<T>` with `of` resolving in the writer's namespace, which
sent `of` to two namespaces depending on who wrote it; and the kind as a sum over its members' values, which admitted
an identifier token where a family's name was wanted. A kind is an atom whose values are names, `of` resolves in the
schema's one namespace (§3.3), and the members are computed.

## The types built with a constructor (`KindMembers`, §4.2)

A kind (`!meta.reference_type { of: net.uuid_type }`, or `meta.ref<net.uuid_type>` through the kernel's template) is an atom
whose values are the names of the closure's types built with one constructor: a computed set of names, as an enum's is a
listed one. The kernel declares `meta.type_ref` and its refinement `meta.identifier_ref`, and types
`meta.enum_type.name_type` and `meta.record.name_type` by the second. `computeKinds` derives every reference's index
(`ReferenceBody.linked`) onto its body in the merged map after `computeSubtypes` and before the name-type checks; a reference
that names values gets `namesTypes` false and no members.

- **Membership is one walk over resolved entries.** `KindMembers`' class Javadoc states the walk: alias hops to the
  type, an alias stopping at an application, `meta.template` bodies and value entries excluded, then the constructor the
  value applies (its recorded tag, `EntryFacts.constructor`, else its body's `@Typename`) followed through a constructor
  template's held body until `of` or a constructor IS-A it is reached. So a record template is a member of
  `meta.ref<meta.record_template>` and not of `meta.ref<meta.record>`, being built with `record_template`; its
  declared applications are records. `set<text>` is a member of `meta.ref<meta.set_type>` while the kernel's template
  `set` is not, `meta.top` of `meta.type_ref`, `meta.ref<meta.product>` and `meta.ref<meta.record>`, and so is a constructor
  whose declaration is a record: membership is not `EntryKind.isConstructor`, which answers applicability.
- **A kind is a member of itself where the walk says so.** `meta.type_ref` is built with `meta.reference_type`, which IS-A
  `meta.type`, so it lists itself. Nothing loops: the walk reads construction chains and never a kind's members.
- **A constructor is read in the schema's one namespace.** `KindMembers` and `NameType` take a `structure` lookup beside
  the entry's own, read first; the linker passes the merged namespace for both, and the second serves a caller holding a
  narrower first one (materialisation's imported entries). A schema's own root `uuid_type` captures no hop: the constructor
  is `net.uuid_type`, a path of the closure, and a schema contributing `uuid_type` to `net` collides with net.tn's
  member of that path under §2.2.3. The closure
  walked is the merged map, the kernel's own types included, so `meta.ref<meta.record>` lists the kernel's records beside
  the schema's, and `meta.ref<meta.namespace>` holds a schema's own namespace type `api` beside `meta.schema`, and
  `meta.ref<meta.map>` holds those and `meta.field_map`, which `meta.ref<meta.namespace>` does not.
- **`of` is checked in `validateBody`**, through its alias hops in the one namespace: a name resolving to nothing is
  an unresolved reference, and an open entry or a value entry is refused (`KindMembers.ofRefusal`), against the
  declaration that wrote the application (`reportedAgainst`). A refinement's `of` is then judged against its source's
  (`checkReferenceNarrows`): the source's or IS-A it, by the chain of the entry `of` names past its alias hops, so a
  widening or an unrelated `of` is refused. The resolver binds a refinement before the names `of` holds are ready
  (they wait on nothing, `design/resolver-vocabulary-and-bootstrap.md`), so the IS-A is the link's to ask, and the
  family's rule (`ReferenceBody.ofNarrowing`) takes it as a predicate; `constraintsCheck` asks only that a reference
  refines a reference. Anything else is admitted: `meta.ref<meta.top>` holds every
  type, `meta.ref<meta.alias>` holds nothing, and `meta.ref<stock_id>` or `meta.ref<api>` names values of that type.
- **Recomputed everywhere, trusted nowhere.** Members are computed over each linking schema's closure, so the
  kernel's `meta.identifier_ref` lists a user schema's own `text_identifier` at that schema's link, and `members` is
  no field an author can write.
- **A value at a kind-typed field is a name.** `checkFieldValue` hands a `~`/`=` value at a kind-typed field to
  `checkKindValue`, which resolves it in the schema's namespace and asks the walk. So
  the kernel's `enum => meta.enum_type ^ { name_type?: = meta.identifier }` is a member at the kernel's link, a schema's pin
  of its own `text_identifier` at that schema's, and a pin of a text family is refused where it is written.
- **Readers judge membership where the names resolve.** A kind compiles as an atom (`AtomParsers.forRead`'s
  `TypeNameRefParser`, an `AtomTypeParser<Reference>`): a name token, or a JSON string holding one, read into the
  `TypeRef` it is and naming a member of the linked index. In a data document a reference is a name, so a reference
  with arguments, unquoted in text or in a JSON string, is refused whether or not the closure closes it
  (`design/references.md`). A name is judged as one name, its path from the root
  (`geo.address`), each segment read into its normalization form as `meta.type_name` reads an identifier. The value is the
  `TypeRef`, carrying the closure its `referent()` resolves in, in a tree read as in a bind; a `String` host receives
  its canonical text. That is every data document's reading, whatever it imports: a data document importing only temporal.tn
  that writes `!meta.record_field { type: int32 }` is refused, `int32` being numeric.tn's. In a **schema document** a name
  resolves in the schema being resolved, which the reading closure does not hold, so the reader checks the grammar alone
  (`AtomParsers.inSchemaDocument`, `TypeNameRefParser.namesOnly`), an application included, and `AtomTypeReader`
  records the name for that schema's link to judge. A schema document is a construction's or a value entry's
  payload, which the resolver reads (`TsonReadContext.readsSchemaDocument`), or a value of the kernel's `meta.schema`
  read as data, a resolved form (`readsSchemaNames`, set by the reader `TsonSchemaCompiler` builds for the kernel's
  `meta.schema` entry), whose names ingest judges. That is what lets a resolved form naming a user's own name type read back.

## What a name type is (`NameType`)

An enum is an instance of `meta.enum_type` or a tightening of it (`enum` pins `name_type: meta.identifier`); a record carries
an optional `name_type` for its field names. Each body holds the name only, and what it denotes (which family, which parser,
which equality, which profile) lives in a namespace the body cannot see.

- **One namespace, whoever wrote the value.** An author-written `name_type` names a vocabulary the schema declares
  or imports (`!meta.enum_type { name_type: kebab … }`); one the constructor pinned (`!enum [A B]` names the kernel's
  `meta.identifier`) resolves in the same namespace, and since the closure holds the kernel's `meta.identifier`, no schema
  declares one of its own to displace it. Pinned is found through the constructor the entry's value applies
  (`TsonSchemaLinker.constructorOf`) and through a template's held body to the constructor it applies: `named =>
  names<c>` is built by `names`, not `enum`. A record stating no name type takes `meta.field_name`, the kernel's alias
  of its `meta.identifier`.
- **Membership, not body class.** `NameType.admits` reads the slot's kind off the `name_type` field of the constructor
  the entry applies, and asks `KindMembers.isMember` of the referent. Where the slot's kind is out of reach (a
  closure holding no kernel, as a hand-built schema's may), `meta.identifier_ref`'s `of`, `unicode.identifier_type`, is
  used. An application of a template over `unicode.identifier_type` expands to a `unicode.identifier_type` instance, and is
  admitted (`KindMembersTest`).
- **The profile is the type's.** `NameType.profile` reads the profile of an admitted type only; `checkNames` judges
  an enum's members and a record's field names by §8.2's per-name rules under it, so a `medial: " "` among its added
  characters is its own. An entry with no admitted profile and not refused falls back to the identifier grammar's.

## What an enum's `name_type` obliges (`EnumLabels`, §7.4)

`name_type` names an identifier family, every member parses as a value of it (`AtomParsers`), and no two members are
one value (`ValueIdentity`). A closed set of text that is no name is `unicode.text_type`'s `members` facet, an ordinary text
atom, string-class like any other (`design/choice-disjointness.md`).

- **Before the name checks, and one verdict.** A member that is not a value of `name_type` is the more basic error,
  which the per-name rules would report as a restricted character; an enum refused here, an unresolved `name_type`
  among them, is not judged by name.
- **The form is recorded, because no reader can recompute it.** An enum matches its members in its name type's
  `normalization` ([TSON-SCHEMA] §5.5). No reader resolves a name type, so the linker records each enum whose form is
  not `NONE` in `TsonLinkedSchema.enumForms` (`EnumLabels.labelForm`), each judged in the schema that declares it. Every
  site that builds an enum parser passes the form to `AtomParsers.forType(body, form)`; the linker's own field-value
  check asks `labelForm`, and `RecordExtension`'s pin check reads the map.

## What a record's `name_type` obliges (`FieldNames`, §5.2)

`EnumLabels`' peer over field names: the type must be an identifier family, every name the record states (in `fields`,
`discriminators` and its groups) must be a value of it, and no two fields may be one value under its equality. Where
no kernel is in scope, the identifier grammar the default stands for applies. A record template is judged the same
way, once, its field names being every application's. The facts read are the ones both bodies state at the record
level, read as one value (`schema.meta.RecordHead`, `RecordHead.of`), so `FieldNames`, `NameType.of`, the linker's
field-name scope and `FamilySelectors.namesOf` ask one question of either shape. It is a value each body produces and
no parent type: a record never names a template.

- **Supertypes decide.** A record with supertypes takes their one type, refuses two that disagree, and refuses a
  stated type that is not theirs; two are one when their alias chains end at one entry (`sameNameType`), so an
  alias is its target and `meta.field_name` is `meta.identifier`. A refinement keeps its source's. The rule is composition's
  (`resolveComposition`): a declaration never writes a chain (§8.1), so an explicit `!meta.record { … }` has none to take a
  type from, which is why `meta.record.name_type` has no `~` default. It is also how a brace form states one: a
  fieldless ABSTRACT base carries the type and the records composing it take it.
- **A schema's own references to a field match its declared spelling exactly**, under a folding type too: a group
  member, a refinement's or removal's restated field, a discriminator. Only a document's names are matched in the
  type's form.
- **Recorded for the readers.** Each record or record template whose body states a name type is recorded in
  `TsonLinkedSchema.nameTypes` with the definition it resolves to (`FieldNames.readBy`), because its readers judge a
  document's names by it and cannot resolve it themselves. A template is read against by no reader, but its member
  dispatch matches its selectors' keys in that form, as its members do; a composition takes an applied operand's
  name type as it takes a closed supertype's.
- **A duplicate field is refused by whichever layer can see it, and only by that one.** One spelling written twice
  in the explicit `!meta.record { fields: { … } }` form is `meta.field_map`'s duplicate key, `DUPLICATE_MAP_KEY` where the
  form is read as data and restated against the declaration where a schema writes it; the brace form's repeat is the
  resolver's. What no map can see, two spellings that are one value of the name type (`name` and `NAME` under a case fold),
  is `FieldNames`'. `meta.field_map` is an ordered map keyed by `text`, no namespace: the map's own uniqueness compares the
  text as written, and each key is then read as a value of the record's name type and judged here, as `discriminators`
  and group members are, and as `meta.enum_set` is for an enum's members.
