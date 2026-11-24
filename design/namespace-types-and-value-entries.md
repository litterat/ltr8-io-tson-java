# Namespace types and value entries

**Namespace types** (instances of `meta.namespace`, resolved to a `NamespaceBody`) and **value entries** (an entry
whose value is an instance of an ordinary type, a `ValueEntry`): how a schema puts vocabulary of its own in a schema
map, how such a value is read, bound or held, and how a qualified name walks into it ([TSON-SCHEMA] §4.1, §5.12).
`ValueEntry`, `Namespace`, `NamespaceBody`, `NamespaceInstanceReader`, `SchemaModelNameBinder` and `ValuesByName`
state their contracts in their Javadoc.

**Invariants**

- **An entry is a type exactly when its value is built by a constructor** (`TypeChain.isConstructor`). Any other tag
  makes it a `ValueEntry(type, value)`, marked by nothing but its tag; an instance of a constructor whose instances are
  no types (`step => meta.top & { … }`) is held the same way (`design/constructor-application.md`).
- **A value of `meta.schema` declares instead.** `geo => !schema { … }` is read as schema grammar, never as a value
  body; its members are entries of the document under their paths, and its `ValueEntry`'s `Namespace` is derived from
  them (`SchemaPaths.synced`). It is open: every document of a closure declaring one map path contributes to one map,
  the union of their members (`design/linking-and-compilation.md`). Every other namespace value stays a value entry.
- **`Top` is sealed all the way down, and every switch over it has explicit `ValueEntry` and `IncompleteEntry` arms and
  no `default`** (`TypeInhabitance`, `DiscriminationClass`, the linker's `validateBody`, `collectNames` and coherence
  check), so a new branch fails compilation at every site that must decide it.
- **A value entry is refused at every type position, in one sentence** (`ValuesByName.aValueNoType`): in a type
  declaration (`validateTypeRef`) and at a kind-typed slot of a value body (`judgeMember`); a data tag naming one is
  `TYPE_MISMATCH` (`ValueEntryReader`). The refusal is load-bearing: `TypeInhabitance` calls a `ValueEntry` inhabited
  and `DiscriminationClass` gives it no class, both right only because nothing may name one as a type.
- **IS-A `meta.map` is a fact about kinds, not bodies.** `NamespaceBody` and `MapBody` are distinct classes, so every
  reader, compiler and walk that dispatches on the body (`TsonSchemaCompiler`, `JsonSchemaCompiler`, `DeclaredReader`,
  `MapBindReader` through `TsonLinkedSchema.namespace`, `ValuesByName`, `TypeInhabitance`, `DiscriminationClass`) needs
  a namespace arm; the IS-A edge is read only where kind membership asks (`KindMembers`).
- **A qualified name enters a `NamespaceBody` and nothing else** (§5.12). `meta.ref<meta.map>` holds namespace types and
  plain map types alike, so the walk asks the body: `{text => method}` and a record's `meta.field_map` are never
  entered, whatever their key type. A record value's fields are entered as fields, not as a map.
- **A field step reads a bound record value through the descriptor of the bind context that bound it**, by wire name,
  never by reflection (`ValuesByName.fieldOf`): reflection would bypass binding's own naming (an `@Field` rename, an
  `Optional` component, a tagged subtype's class, a host that is no Java record). The context reaches the linker from
  `TsonCompiledVocabularyRegistry.link`. A FIXED field with no component is the one field step reported as a gap.
- **A dependent field step takes the type its sibling names in the same value** (`ValuesByName.dependentType`, §5.2),
  judged by the read path's rule (`DependentFieldReader.typeNamed`). In a schema document a dependent field's value is
  held as the written tree, walked by field name, and judged against its type at link (`HeldValues`).
- **A read delivers a `Namespace` at the four positions `NamespaceInstanceReader` lists and nowhere else**; everywhere
  else a namespace type reads as its map, so a host `Map` component receives a map, and a `Namespace` component at a
  map that is no namespace is a bind mismatch.
- **`Namespace` is no `Map`**: a binder or writer testing `instanceof Map` would lose the namespace type. Its equality
  excludes key order, which is the `ordered` facet's; an ordered owner compares in order itself.
- **One bind context serves schemas and data** (`SchemaModelNameBinder.over`, `Tson.dataBindContext()`), so
  `create_order => !operation { … }` and a data document's `!operation { … }` bind to one class. A kernel or library
  name binds only to the schema model, so a consumer cannot rebind one; a member a consumer contributes to an open map
  is the consumer's.
- **A value entry no class binds is a tree, not an error, until something binds.** `SchemaResolver` reads the payload
  in bind mode and, on `MissingBindingException`, again at a tree-mode checkpoint (`compileReady(…, tree)`), so
  `tson validate`, tree reads and `validateSchema` load the schema. The bind registry refuses a tree-held entry in the
  closure it compiles, `BIND_MISMATCH` at `/entry` (`TsonCompiledSchemaRegistry.refuseUnbound`): the narrowest point a
  consumer first obtains a schema for binding, and a missing line of wiring says nothing about the schema. The tree
  registry refuses nothing.
- **A value entry's payload is a value body** (`TsonReadContext.readsValueBody`): nothing in it is closed or written in
  place, so an application at a kind-typed slot is refused with the alias spelling named. A parametric record's body
  is a record, so there is no parametric record over an ordinary type (`design/parametric-records.md`).
- **§8.2 hygiene reaches the payload at the read, not at the link**: the linker cannot see into a value, whose fields'
  types live in the declaring schema, so the payload is read under the loader's identifier policy and judged as a data
  document is (`design/name-hygiene.md`).
- **A base kind counts only as the kernel's own entry** (`TsonBundledSchemas.isKernel`): a schema's own root `namespace`
  is an unrelated name, and kind membership walks construction to the kernel's `meta.namespace`.

Related: `design/references.md` (the slot declares a name's spelling and host shape), `design/linking-and-compilation.md`
(link order, values by name), `design/kinds-and-name-types.md`, `design/schema-resolution.md` (wait order for a local
type), `design/class2-compilation.md`, `design/compiled-registries.md`.

## Decisions

**Values at the schema layer.** The motivating case is an HTTP interface: methods naming their request and response
types must sit at the schema layer, the only one that can name a type by name; a data document can only instantiate
one. The corpus fixture `meta-service.tn` is the worked case.

**The tag, not a marker.** Reading the entry as a value of its type gives one rule for what a schema map holds and what
a data document holds: its keys judged by `key_type`, its values by `value_type` with every field state and atom
constraint, and a qualified name walking it through the schema rather than through whatever object a host built. It
keeps `Top` closed: every branch but `ValueEntry` mirrors a kernel constructor, and `ValueEntry` wraps a value whose
type the schema declares.

**A namespace type declares only its facets.** `meta.namespace`'s record reader refuses any other field (`extends:`
beside the facets is an unknown field). A construct with fields of its own and a map of members is a record holding
both, one field typed by a namespace type.

**Compiling a value entry to a refusal.** Nothing is read against a value, so `TsonSchemaCompiler` gives it a
`ValueEntryReader` and `JsonSchemaCompiler` an `ErrorReader`; a constructor with no factory compiles to an
`ErrorReader` carrying the cause, kept rather than dropped.

**One namespace, so shared vocabulary sits beside the types** (§2.2.3). An `!!import` contributes its whole namespace,
so a method schema's `status_code` or envelope record is named by every importer. The cost is reservation: every root
name of the closure is taken (`ImportedVocabularyTest`), which the kernel and libraries keep small by sitting under
their seven prefixes.
