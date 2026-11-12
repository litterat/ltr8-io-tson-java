# Linking and registration

Design notes for canonical identity, the schema registry, and `TsonSchemaLinker`'s pass 2: import merging, the order
of the link, reference validation, the two §5.10 template rules, the facts linking records on
`TsonLinkedSchema`, namespace types and values by name. Current form only; history lives in git.

The linker's other checks have notes of their own: kinds and name types in `design/kinds-and-name-types.md`, the
inhabitance check and what `record.extension` obliges in `design/inhabitance-and-record-extension.md`, choice
disjointness in `design/choice-disjointness.md`, and §8.2's name hygiene in `design/name-hygiene.md`.

**Invariants**

- The linker lives in `tson-compiler` and the registry in `tson-schema`, on purpose; the linker materializes nothing.
- `CanonicalIdentity.canonicalize` (`tson-base`) reads a *reference* in any spelling of its identity; `validate` judges
  a document's own `!!id`, which must already be written canonically. Neither rewrites an identity into validity.
- Import collisions are decided by an entry's origin schema, not by name occurrence; a local declaration may not reuse a
  name the closure already binds unless it is equal to the entry already there. A nested `!schema` map is the
  exception that is no collision: several documents declaring one map path contribute to one map, the union of their
  members (§2.2.3). Nothing is minted, so no other entry unifies across documents (§8.2).
- One namespace per schema (§3.3): its own entries and the whole closure of its `!!import`s
  (`TsonSchema.namespaceSources`, header order, deduplicated by identity). Every name the schema writes resolves
  there; no rule ties what a schema may declare to what it imports, so any schema declares a constructor, a namespace
  type or a reference, and imports any schema document.
- A reference to a value entry (a `ValueEntry`, `myapi => !api { … }`) is refused at every position a type-ref
  occupies: it is a value, and nothing is typed by it.
- A kernel slot typed `meta.type_ref` (`meta.record_field.type`, the element and container type slots,
  `meta.choice.variants`) is judged by `KindMembers` in `validateTypeRef`, after resolution: a constructor whose declaration
  is a record is a member, its value being of `meta.record`, and a value entry is refused there. An alias's target (`Alias`),
  which may name a constructor, is judged by resolution and arity alone (`constructors == null`). `design/references.md` has
  the account of every slot.
- A value by name (`TsonSchema.valuesByName`) resolves its first segment in the schema's merged namespace, alias
  hops followed; its referent's declared type must IS-A the slot's, compared by canonical identity.
  Local entries only, after `validateEntry`. A field's default or pin at a reference naming values, and a name a data
  document writes at one, are judged by the same walk (`ValuesByName.reach`).
- `entryOrigins`, `enumForms` and `nameTypes` are on `TsonLinkedSchema`, never on `TsonSchema` or an entry's value,
  and each is merged through imports.
- A namespace type resolves to a `NamespaceBody`, and a value of it is read as the map `NamespaceBody.read()` states:
  both compilers hand that map to the `map` factory, so neither factory table has a `namespace` entry.
- `checkOpenArity` is the one place an entry's own parameters are in scope: it reads an open record's references, a
  `ParamRef` among them, and judges arity alone. A closed entry holds no parameter, so `validateTypeRef` and
  `checkFieldValue` resolve every name in the schema's namespace.
- `TsonSchemaRegistry` never overwrites an identity and exposes no way to remove or enumerate one: that *is* the
  "locked" guarantee.
- Everything that asks what an entry IS-A asks its `TypeChain`: `computeSubtypes` credits every closed type and no
  open record, so a template is in no `subtypes` index, and the constructor test (`EntryKind.isConstructor`) reads an
  open record's chain as a closed one's, judging `top` by its origin.

Related: `design/namespace-types-and-value-entries.md`, `design/references.md`, `design/class2-compilation.md`,
`design/compiled-registries.md`, `design/schema-resolution.md`, `design/template-materialisation.md`.

## Why two modules

Resolution handles one declaration at a time, references carried as unverified strings. `TsonSchemaLinker` and
`TsonSchemaRegistry` add the second stage, in different modules: the linker is a pipeline stage and lives in
`tson-compiler` beside parse, desugar, resolve and compile, with `Diagnostic` and with `ltr8-regex` in direct reach;
the registry is storage over the `schema.meta` value model and stays in `tson-schema`, the leaf everything depends on.

**The linker materializes nothing.** `SchemaDesugarer` and `TemplateMaterialiser` have turned every sugar form and
closable application into a declaration one phase earlier, in the module that can bind a constructor generically. The
references carrying arguments the linker sees are inside open records, validated and never rewritten, every argument
judged as a type's name; an application
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
3. **`computeSubtypes`**, the reverse of `supertypes` over every closed `Type`, an open record left out.
4. **`enumForms`** for each local enum (`EnumLabels.labelForm`).
5. **`computeKinds`**, every reference's derived index, whether `of` names a constructor and, for a kind, its
   members (`design/kinds-and-name-types.md`).
6. **`computeDisjointness`**, every choice's `disjoint` (`design/choice-disjointness.md`).
7. **`checkEnumTypes`**, **`checkFieldNameTypes`**, then **`nameTypes`** for each record not refused.
8. **`checkNames`**, §8.2 over scopes (`design/name-hygiene.md`).
9. **Validate** every entry (`validateEntry`).
10. **`checkValuesByName`**, **`checkEveryEntryIsInhabited`**, **`checkRecordExtension`**.
11. **`withNameAnnotations`**, then **`checkDisjointAssertions`**: an author's `@meta.disjoint` is read from both places §6
    puts it, the definition and the map key, so it runs last.
12. **`checkHeldValues`**, only over a link otherwise clean and with nothing pending: each dependent field's value a
    local value entry holds as written, read through the schema compiled in tree mode (`HeldValues`, below).

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
  Two *different* schemas declaring one name is an error naming both, the bundled documents' root names included:
  every closure reaching them reserves their names. Because identities carry the spec revision, a
  closure reaching both `/2026/37/m/core.tn` and `/2026/40/m/core.tn` is refused here rather than surfacing later as a
  field conflict between two identically spelled types. Listing one schema twice, or under two spellings of one
  identity, is redundant rather than an error. An unregistered import is a `SchemaValidationException`.
- **No entry unifies by its form.** A type written in place travels inside the entry that holds it, so two documents
  writing `[text]` hold two equal constructions and declare nothing between them; one name declared by two documents
  is the collision above, whatever the bodies.
- **A local entry equal by value to the closure's entry of its name is passed over** (`TsonSchemaLinker.link`); any
  other local reuse of a closure name is refused and the local entry dropped, keeping the import's, which is
  already-linked material.
- **A nested schema map is open** (§2.2.3). `geo => !schema { … }` declared by several documents of
  one closure is one map, merged exactly as the root namespace merges across imports: its members are the union, each
  merged under its path, and a member path two different documents declare is the ordinary collision above. A
  member's identity is its declaring document plus its path, so its origin is recorded per path. Both the import loop
  and the local loop pass over a nested map met twice (`SchemaPaths.isNestedMap`), the map's entry being derived from
  its members, and `carryOver` takes no import's annotations onto it: the annotations written on a `geo => !schema
  { … }` entry are that document's contribution alone. This is how
  any schema contributes a member (`meta => !schema { code_type => unicode.text_type ^ { max_length?: = 4 } }`, then
  `code => !meta.code_type {}`); a contributed member is the contributor's, never a kernel entry
  (`TsonBundledSchemas.isKernel` judges by origin).

## Reference validation (`validateEntry`, `validateBody`, `validateTypeRef`)

Every reference must resolve in the merged namespace, or be one of the entry's own declared parameters. Three rules
are worth their place here, each catching what would otherwise link, compile, and fail only when a document is read:

- **A value entry is refused at every type-ref position** (a field type, a variant, an element, a map key or
  value), naming the type it is a value of. A value entry's payload was judged by the read against its type and its
  names by `ValuesByName`, so `validateBody` asks nothing of it.
- **A `meta.type_ref` slot is judged by membership** of `meta.type_ref` (`KindMembers.isMember`), where `type` is in reach:
  an alias stopping at an application, an open record and a value entry are no type a value can have. A constructor
  is one, its declaration a record (`spec: net.uuid_type` holds a declaration body as data; §4.1).
- **A choice's variants are distinct at the end of each §8.3 alias chain** (`AliasChain.terminal`), since an
  alias and its target are one type: `(text | my_text)` with `my_text => text` is caught, the only spelling an author
  cannot see. No variant may resolve to `void` (`checkVariantsAreNotVoid`).

**A chain's names resolve in the same namespace.** A refinement of a kernel constructor reaches one (`meta.set_type =>
meta.array ^ {...}` carries `[meta.array, meta.product, meta.top, meta.type]`), and the defining schema's closure is inside
the importer's, so every name on it is there. A `meta.ref` body's `of` names any type or constructor, an application
there having closed to its entry during resolution, never a value entry or an open record (`KindMembers.ofRefusal`); what it
names decides whether the slot holds a type's name or a value's. A namespace type's stated `key_type` must be a member of
`meta.identifier_ref`: its keys are names.

**Two narrowings are judged here, both by `isA`** (§7.2's chain IS-A, alias hops followed): a reference refinement's
`of` (`checkReferenceNarrows`), and a record's restated field types against the nearest closed records on its chain
(`checkRestatedFieldTypes`, §5.7, §5.8). The resolver binds each before the names it holds need be ready, which is why
neither runs there; `design/schema-resolution.md` has the field rule.

**Every body is asked its family's coherence rule** (`checkCoherent`), which catches the forms an application closes
to; a declared body was already asked at resolution, and one refused there never reaches
the linker (`design/atom-refinement-and-coherence.md`).

## The two §5.10 template rules

Both are decidable at link and neither depends on anyone applying a template.

- **Arity, over every reference** (`checkArity`): a reference supplies exactly as many arguments as the entry it names
  declares parameters. Too many, too few, and **none at all**: a template named bare otherwise links and compiles and
  fails at read time against a document with nothing wrong with it. A template is not a type (§5.10), so a bare
  template is refused at every position -- a field, element, map key or value, tuple position, choice variant or
  argument -- the message naming the remedy: the application, or the abstract record a
  family's applications compose. An operand is `DefinitionResolver.refuseUnappliedTemplate`'s, one phase earlier. A
  type parameter written with arguments (`T<text>`) is refused: §5.10 admits no head abstraction.
- **Parameter usage** (`checkOpenEntryUsesEveryParameter`): an open record references every parameter it declares, so
  `box => <T> { v: text }` is refused. The converse needs no check: at a closed entry a token in a value slot *is* a
  literal, and a parameter reference is already an unresolved one.
- **An open record answers arity for every reference it holds** (`checkOpenArity`), bare ones included: its field
  types and parents are references of the model, so a template named bare inside a template is refused at the
  template's own declaration, and a field named `box` beside a template of that name is told apart, a field name being
  no reference. What a reference *resolves to* waits for an application, so type-kind validation and inhabitance run
  on the record an application closes to; arity counts the parameters the referenced entry declares and does not.
  Nothing ever closes `chain => <T> { tail: chain<T, T>? }`, so deferring it would ship the mistake.

## The facts linking records (`TsonLinkedSchema`)

Three maps, each a fact only linking can establish and none part of the resolved schema value §9 defines, so none is
on `TsonSchema` or a body (where `schema.meta`'s hand-written `equals` and the `@Record` trap would meet it). Each is
merged through imports with the import's entries.

- **`entryOrigins`**: name to the canonical identity of the schema that declared it. It keeps a declaration's identity
  and its line answerable from the same document however many schemas merged it in: the pair a non-record reader
  offers as its location (`ValueReaderContext.locationOf`), which locates a root-level `!int32` in numeric.tn rather than
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
`TsonSchemaLinker.linkBootstrap` is the one sanctioned way to link the kernel's raw bootstrap output without
registering it. `linkReady(ready, loader, pending)` is the one partial link: a resolution in progress links its
document's entries ready so far to compile readers for the next read (`WaitOrder`), a reference to a pending
declaration is passed over there and nowhere else, and it reports nothing, each entry being judged when the whole
schema links. In a partial link every pending name is a member of each reference naming types (`computeKinds`), so a
pin naming a declaration not yet ready (enum's `name_type: = meta.identifier` mid-bootstrap) is judged then too;
`design/resolver-vocabulary-and-bootstrap.md` has the mode.

## Namespace types (§4.1)

A namespace type is an instance of the kernel's `meta.namespace` constructor, a refinement of `meta.map`,
`!meta.namespace { key_type: …  value_type: … ordered: … }` (`meta.schema` in the kernel, `headers => !meta.namespace {
key_type: header_name value_type: text }` in any schema), and resolves to a `NamespaceBody`. A value of it is a map from
`key_type` to `value_type`.

- **The map is what the facets state.** `NamespaceBody.read()` gives the `MapBody`: keys of `key_type`, values of
  `value_type`, ordered where `ordered` is `true`, values never void, bounded by `min_items`/`max_items` where stated
  (`NamespaceBody.coherenceCheck` judges the pair as `MapBody`'s does). `key_type` is required: the family
  every key is read and judged under, a member of `meta.identifier_ref` (`validateBody`). A record's `fields` is no
  namespace but `meta.field_map`, an ordered map keyed by `text` whose keys the record's `name_type` judges (`FieldNames`).
- **Nothing beside the facets.** `namespace`'s own record reader refuses any other field at the construction, so the
  linker has no facet check of its own.
- **The reader is the map's.** `TsonSchemaCompiler` and `JsonSchemaCompiler` hand `read()` to the `map` factory;
  `ParameterTypes` reads a namespace type as its map too, where a written value faces one. How a value binds and when
  it is delivered as a `Namespace` is `design/namespace-types-and-value-entries.md`'s.
- **Any schema's vocabulary.** Any schema declares a namespace type or types a field by it, and a schema importing the
  declaring one holds values of it as entries. Such an entry (`myapi => !api { … }`) is a `ValueEntry`: refused at
  every type position (above), a member of no kind, named by `meta.ref<api>` (`api` being no
  constructor), and compiled to a `ValueEntryReader`.

## Values by name (`ValuesByName`, §5.12)

A value entry's payload may hold a name, `request: place_order_request` at a slot typed `meta.ref<meta.record>` or
`method: orders.place_order` at a slot typed `meta.ref<method>`. The read keeps the name as written with its path and the
slot's declared type (`TsonSchema.SlotType`), and `checkValuesByName` judges each one over local entries after
`validateEntry`, by what the slot's `of` names over `merged` (`KindMembers.namesTypes`), whatever the read guessed:
over a constructor the name as a member (`judgeMember`), over any other type by the walk, the value it reaches
delivered into the read's `ValueRef` cell. The walk itself, by key and by field through the binding's
descriptor, is `design/namespace-types-and-value-entries.md`'s; what is the linker's is below.

The same walk judges two more names, so one rule decides what a value's name reaches wherever it is written:

- **A field's default or pin at a reference naming values** (`checkFieldValue`, from `validateEntry`): the token is
  judged by `ValuesByName.reach` over `merged`, and a refusal is a `SCHEMA_ERROR` against the entry declaring the field,
  opening `'rpick': field 'which''s default …`. A FIXED-field step reported as a gap is `NOT_IMPLEMENTED` against that
  entry too.
- **A name a data document writes** at one: `computeKinds` gives every reference naming values a lookup
  (`ReferenceBody.Values`, `valuesOf`) over a `ValuesByName` built once the link has assembled its final namespace
  (the `reached` holder, set beside `closure`), so the governing schema's compiled readers judge the name at the read
  (`design/references.md`). A link of a resolution in progress (`linkReady`) gives none: its pending entries hold no
  value, and a reference there reads its grammar alone, a held value at one being judged when the whole schema links.

- **The grammar is judged here.** `ValuesByName.segments` splits the name at each `.` and requires each segment to be
  a [TSON-DATA] §7.7 identifier, none empty and no arguments, so `orders.1x`, `.orders` and `orders.` are refused
  before anything is looked up. An application in a value body is refused where it is read, the alias spelling
  named (`design/references.md`).
- **One namespace for the slot's type and the name.** The first segment resolves in `merged`, a segment naming a
  member of a nested `!schema` map collapses onto that member's path entry, alias hops are followed (§8.3), and only
  there, so `method: place_order` is unresolved even though `orders` holds that key. Found first,
  judged after: nothing is a choice among candidates. A type entry admits no further segment, an open record is no
  referent, a list has no named members, and a map that is no namespace is not entered whatever its key type.
- **The judgement is §7.2's IS-A, by canonical identity.** An entry's declared type is the constructor that built its
  body: its recorded tag where that names a constructor, else the kernel constructor its body's class mirrors
  (`@Typename`). A field's declared type is its `type`, a dependent field's the one its sibling names in the value
  walked, and a map value's the map's `value_type`. An imported referent's constructor is in its declaring schema's
  closure, which this one contains, so each is named in the one namespace
  (`ValuesByName.Space`), and two types are one when their terminal entries share origin and name.
- **Each refusal is its own `SCHEMA_ERROR` naming the step**, against the entry holding the name, at the slot's RFC
  6901 pointer. A qualified name at a kind-typed slot is refused where it reaches into a value or goes on past a
  type. A field step needs the bind context its values were bound under (`TsonCompiledVocabularyRegistry.link` passes it);
  an overload without one throws `IllegalStateException` if a walk reaches a field. One step is a gap
  (`NOT_IMPLEMENTED`): a FIXED field with no component.

## Dependent values held as written (`HeldValues`, §5.2)

A value entry's payload is read by the governing layer's readers, and the type a dependent field's sibling names
(`kind: server`) is one of the reading schema's namespace, which those readers do not hold. So the read holds the value
as written, whatever its form, and the record reader hands its events, with what the sibling holds in that instance
(pin, written value or default) and the sibling's name, to the read's sink (`TsonReadContext.heldAsWritten`,
`TsonSchema.HeldValue`). The resolver keeps them for value entries only (`TsonSchema.heldValues`); a construction's,
the kernel's `record_field.value` among them, is judged as a default or pin by `checkFieldValue`.

- **Judged last, by a read.** Once the rest of the link has reported nothing, the linked schema is compiled in tree mode
  (once, and only where a local value entry holds one) and each value is read again through the reader of the type its
  sibling names, as a schema document's value. The type is `ValuesByName.typeNamed`, the read path's
  `DependentFieldReader.typeNamed` with a value entry naming no type either. The first refusal of each value is a
  `SCHEMA_ERROR` at the entry naming the slot, the dependent field, the sibling and the type; a `NOT_IMPLEMENTED` the
  read reports stays a gap. The names the read records are judged by `ValuesByName`, and a dependent field inside the
  value is held again and judged in turn, each slot from the entry's root.
- **The held value stays as written.** The model holds the schemaless tree (`DependentFieldReader`), so a value by
  name going on through the field walks it by field name; a default of the named type is not injected there. A meta
  layer's host class takes it in an `Object` component where the context registers `Object` as an atom
  (`ResolverBindContext.registerDefaults` does, a consumer's context where they register it); a `Token`
  component (the kernel's `RecordField.value`) holds a token alone, and a compound value there is refused at the read
  as no token.
- **Opaque to the model.** `HeldValue.written` is an `Object` holding the read's `List<TsonEvent>`, as `ValueEntry`'s
  payload is an `Object`: `tson-schema` names no compiler type.
- **Local entries only**: an imported body's names were judged in its own namespace when its schema linked.
