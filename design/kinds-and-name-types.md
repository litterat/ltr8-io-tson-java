# Kinds and name types

Design notes for two link-time facts about names: a kind's members (`KindMembers`, §4.2), and what an enum's or a
record's `name_type` obliges (`NameType`, `EnumLabels`, `FieldNames`, §7.4, §5.2). Both need the merged closure and the
governing meta at once, which is why they are the linker's. Current form only; history lives in git.

**Invariants**

- A kind is an atom whose values are type names. Its `members` are recomputed for **every** kind in the closure,
  imports included, and never trusted from a body; `KindBody`'s equality is `of` and the chain, never the members, so
  an importer re-deriving an import's kind unifies with it.
- `of` resolves in the structure namespace alone and must be `type` or `namespace`, or IS-A either
  (`KindMembers.ofRefusal`). A kind is not a bound.
- A constructor (IS-A `top`) is no member of any kind except as a namespace constructor, whose instances are maps; a
  namespace constructor is a member of `type_kind` and of the kind of every namespace constructor it is or IS-A, and
  of no other kind.
- An empty kind admits no value, and `TypeInhabitance` refuses it.
- Whether a `name_type` referent may stand in its slot is membership of the slot's kind (`NameType.admits`, reading
  the kind off the constructor's `name_type` field), never a test of the referent's body class.
- A `name_type` the constructor pinned resolves in the governing meta's namespace first (traced through a template's
  held body); one the author wrote, in the schema's first. `NameType` is the one place that says so, for enums and
  records alike, shared with materialisation.
- An enum's and a record's name type is an identifier family whose `normalization` implies NFC (`NONE` never;
  `ASCII_CASEFOLD` only over ASCII, `NameType.formRefusal`); their names are judged under its profile.
- The enum and record checks run before `checkNames`; an entry they refuse is not judged by name again.
- `FieldNames` never sees two fields of one spelling (`record.fields` is a map, and its reader refuses the repeat), so
  its duplicate check is two spellings that are one value of the record's name type, and nothing else.
- `enumForms` and `nameTypes` are recorded on `TsonLinkedSchema` because no reader can recompute them.

Related: `design/linking-and-compilation.md` (the link order), `design/references.md` (which slot is typed by a kind),
`design/name-hygiene-and-minted-names.md` (the per-name rules a name type's profile feeds),
`design/meta-layer-namespaces.md`.

## The types built with a constructor (`KindMembers`, §4.2)

A `kind_type` instance (`!kind_type { of: uuid_type }`, or `kind<uuid_type>` through the kernel's template) is an atom
whose values are the names of the closure's types built with one constructor: a computed set of names, as an enum's
is a listed one. The kernel declares `identifier_kind`, `atom_kind` and `type_kind`, and types `enum_type.name_type`
and `record.name_type` by the first. `computeKinds` writes `members` (sorted, `KindBody.linked`) onto every kind body
in the merged map after `computeSubtypes` and before the name-type checks.

- **Membership is one walk over resolved entries.** `KindMembers`' class Javadoc states the walk: alias hops to the
  type, constructors, references and `template` bodies excluded, the namespace-constructor exception, then the
  constructor the value applies (its recorded tag, `EntryFacts.constructor`, else its body's `@Typename`) followed
  through a constructor template's held body until `of` or a constructor IS-A it is reached. So a record template is
  a member of `kind<record>` (it is a type, §5.10), `set<text>` of `kind<set_type>` while core's `set` is not, and
  `top` of `type_kind`, `kind<product>` and `kind<record>`: membership is not `EntryKind.isConstructor`, which answers
  applicability.
- **A kind is a member of itself where the walk says so.** `type_kind` is built with `kind_type`, which IS-A `type`,
  so it lists itself. Nothing loops: the walk reads construction chains and never a kind's members.
- **A constructor is read in the structure namespace first**, so a schema's own `uuid_type => text` never captures a
  hop (§3.3.1). The kernel links itself with no structure namespace loaded, so a constructor not found there is read in
  the entry's own namespace. The closure walked is the merged map only: the governing meta's own instances (the
  kernel's `text`, `identifier`) are never members of a user schema's kind.
- **`of` is checked in `validateBody`**, through its alias hops in the structure namespace only: `kind<data>`,
  `kind<top>`, `kind<template>`, `kind<reference>` and a type of the schema's own (`kind<stock_id>`) are refused,
  against the declaration that wrote the application (`reportedAgainst`). With no governing meta loaded nothing is
  judged, as `checkMayGovern` judges nothing it cannot load.
- **Recomputed everywhere, trusted nowhere.** Members are computed over the governing schema's closure, so the
  kernel's `identifier_kind`, reached through meta.tn's import, lists a user meta layer's own `text_identifier`
  there, and an author's hand-written `members` are discarded.
- **A value at a kind-typed field is a name.** `checkFieldValue` hands a `~`/`=` value at a kind-typed field to
  `checkKindValue`, which resolves it in the schema's namespace, then the structure namespace, and asks the walk. So
  the kernel's `enum => enum_type ^ { name_type?: = identifier }` is a member at the kernel's link, a meta layer's pin
  of its own `text_identifier` at that layer's, and a pin of a text family is refused where it is written.
- **Readers judge membership where the names resolve.** A kind compiles as an atom (`AtomParsers`' `KindParser`): a
  type-name token, or a JSON string, naming a member of the linked index. Under a meta layer
  (`TsonLinkedSchema.governsSchemas`) the document is a schema whose names resolve in itself, so the reader checks the
  grammar alone (`KindParser.namesOnly`) and that schema's link judges membership, which is what lets a resolved form
  naming a user's own name type read back through meta.tn.

## What a name type is (`NameType`)

An enum is an instance of `enum_type` or a tightening of it (`enum` pins `name_type: identifier`); a record carries an
optional `name_type` for its field names. Each body holds the name only, and what it denotes (which family, which
parser, which equality, which profile) lives in a namespace the body cannot see.

- **Two namespaces, by who wrote the value.** An author-written `name_type` resolves in the schema's own namespace
  first, so an ordinary schema enumerates a vocabulary it declares (`!enum_type { name_type: kebab … }`). A value the
  constructor pinned was written in the governing meta and resolves there first, so `!enum [A B]` names the kernel's
  `identifier` whatever the schema declares; a schema's own `identifier` is a different type and does not displace
  it. Pinned is found through the constructor the entry's value applies (`TsonSchemaLinker.constructorOf`) and through
  a template's held body to the constructor it applies: `named => names<c>` is built by `names`, not `enum`. A record
  stating no name type takes the kernel's `field_name`, resolved in the structure namespace first.
- **Membership, not body class.** `NameType.admits` reads the slot's kind off the `name_type` field of the constructor
  the entry applies, in the structure namespace first, and asks `KindMembers.isMember` of the referent. Where the
  slot's kind is out of reach (no meta loaded, or a meta layer reaching the kernel only through its own `!!meta`),
  `identifier_kind`'s `of`, `identifier_type`, is used. An application of a template over `identifier_type` expands to
  an `identifier_type` instance, and is admitted (`KindMembersTest`).
- **The profile is the type's.** `NameType.profile` reads the profile of an admitted type only; `checkNames` judges
  an enum's members and a record's field names by §8.2's per-name rules under it, so a `medial: " "` among its added
  characters is its own. An entry with no admitted profile and not refused falls back to the identifier grammar's.

## What an enum's `name_type` obliges (`EnumLabels`, §7.4)

`name_type` names an identifier family, every member parses as a value of it (`AtomParsers`), and no two members are
one value (`ValueIdentity`). A closed set of text that is no name is `text_type`'s `members` facet, an ordinary text
atom, string-class like any other (`design/choice-disjointness.md`).

- **Before the name checks, and one verdict.** A member that is not a value of `name_type` is the more basic error,
  which the per-name rules would report as a restricted character; an enum refused here, an unresolved `name_type`
  among them, is not judged by name.
- **The form is recorded, because no reader can recompute it.** An enum matches its members in its name type's
  `normalization` ([TSON-SCHEMA] §5.5). The readers hold only `TsonSchema.entries()`, where a pinned `name_type` may
  not resolve, so the linker records each enum whose form is not `NONE` in `TsonLinkedSchema.enumForms`
  (`EnumLabels.labelForm`), each judged in the schema that declares it. Every site that builds an enum parser passes
  the form to `AtomParsers.forType(body, form)`; the linker's own field-value check asks `labelForm`, and
  `RecordExtension`'s pin check reads the map.

## What a record's `name_type` obliges (`FieldNames`, §5.2)

`EnumLabels`' peer over field names: the type must be an identifier family, every name the record states (in `fields`,
`discriminators` and its groups) must be a value of it, and no two fields may be one value under its equality. Where
no kernel is in scope, the identifier grammar the default stands for applies.

- **Supertypes decide.** A record with supertypes takes their one type, refuses two that disagree, and refuses a
  stated type that is not theirs; two are one when their reference chains end at one entry (`sameNameType`), so an
  alias is its target and `field_name` is `identifier`. A refinement keeps its source's. The rule is composition's
  (`resolveComposition`): a declaration never writes a chain (§8.1), so an explicit `!record { … }` has none to take a
  type from, which is why the kernel's `name_type` has no `~` default. It is also how a brace form states one: a
  fieldless ABSTRACT base carries the type and the records composing it take it.
- **A schema's own references to a field match its declared spelling exactly**, under a folding type too: a group
  member, a refinement's or removal's restated field, a discriminator. Only a document's names are matched in the
  type's form.
- **Recorded for the readers.** Each record whose body states a name type is recorded in `TsonLinkedSchema.nameTypes`
  with the definition it resolves to (`FieldNames.readBy`), because its readers judge a document's names by it and
  cannot resolve it themselves.
- **A duplicate field is refused by whichever layer can see it, and only by that one.** One spelling written twice
  in the explicit `!record { fields: { … } }` form is `field_map`'s duplicate key, `DUPLICATE_MAP_KEY` where the form is
  read as data and restated against the declaration where a schema writes it; the brace form's repeat is the
  resolver's. What no map can see, two spellings that are one value of the name type (`name` and `NAME` under a case
  fold), is `FieldNames`'. `field_map` states `key_type?: ~ field_name`, a default: the key family is the host
  record's name type, so a key is read by its spelling and judged here, and `discriminators` and group members are
  `text` for the same reason.
