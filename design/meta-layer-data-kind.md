# `Data` and the `data` base kind

Design notes for the meta layer's extension point: where an instance of a meta-schema's own constructor lands when what it
describes is not a data type, how a consumer registers a class for one, and what a meta layer is not. Current form only;
history lives in git.

**Invariants**

- `Data` is the one open (`non-sealed`) branch of `Top`; every other branch is sealed all the way down.
- The registration is a `@Typename` and a name binder, and nothing else: no reader family, no
  `ValueReaderFactoryRegistry` entry.
- A constructor with no resolvable class is an error where it is written.
- A host declares none of its references. A slot naming a type is typed by a kind (`request: kind<record>`) and a
  slot naming a value by a reference (`method: ref<method>`); the read records each name with its slot
  (`TsonSchema.valuesByName`) and the linker judges it by the slot's type, membership of the kind or IS-A the
  reference's `of`. The hosts are `TypeRef` (the name) and, at a reference-typed slot, `Ref<T>` (the name and the
  value delivered at link); the host shape is one, and only the judgement differs by slot.
- The slot declares the spelling: a value by name stands at a slot typed `ref<T>` only, held by a `Ref<T>` (the name,
  and the value delivered at link) or a `TypeRef` (the name alone); a record-typed slot holds the record's value
  inline, and a `TypeRef` component there is a bind mismatch.
- The linker reads a name's slot type off the constructor's resolved fields along the path the read recorded,
  never off the Java class.
- `TypeInhabitance` and `DiscriminationClass` handle a `Data` body and a `Namespace` by their `default` arm, which is
  safe only while the linker refuses any *type reference* naming a DATA or namespace entry. A value by name may name
  either: it is a value, not a type.
- A meta-schema keeps a constructor this library cannot build a reader for, as an `ErrorReader`; it is never dropped.
- `!!meta` merges nothing into the type-name namespace: shared vocabulary goes in a third ordinary schema.
- A namespace type is a meta layer's own vocabulary: it IS-A `top`, so only a kernel-governed schema declares one
  (§2.2.2), and a value of it is the map linking records for it (`TsonLinkedSchema.namespace`), never a `Data` body.
- An entry applying a namespace type, `myapi => !api { … }`, is a `Namespace<V>`, a sealed `Top` branch: `type` names
  the constructor it is an instance of (it carries no `@Typename`, so `TsonSchema.constructorOf` and
  `TsonCompiledMetaSchema.typenameOf` read `type`), `entries` is the map with each key's annotations and the order
  written, and `EntryKind.of` gives `NAMESPACE`. It is a value and never a type.
- A qualified name's further segment selects a key of a `Namespace` entry's `entries()` through the schema, the value
  reached being of the constructor's `value_type`; it reads a `Data` body's record components through the host's
  object, and that hop is the one `data`'s retirement removes.

Related: `design/linking-and-compilation.md`, `design/class2-compilation.md`, `design/compiled-registries.md`,
`design/schema-resolution.md`.

## `Data`: an entry that is not a type (`schema.meta.Data`, §4.1's `data` base kind)

§2.2.2 calls the meta layer the format's sanctioned extension point, and a meta-schema may declare constructors of its
own. The question that needs an answer is where an *instance* of such a constructor lands when the thing it describes
is not a data type — `schema`, a namespace of `top` keyed by `type_name`, admits any value, and an entry is a type
exactly when its value is one. `data => abstract top & {}` is the fourth base kind that lets one say otherwise, and
`EntryKind.DATA` is what it resolves to. The kernel names the other side:
`type => abstract top & { chain?: type_chain }`, which `atom`, `product` and `sum` compose with (SPEC-FEEDBACK.md #9),
so an entry is a type exactly when it IS-A `type`, and `data` IS-A `top` alone, as `namespace` does
(SPEC-FEEDBACK.md #23). `type` is not a base kind — kind derivation reads `atom`, `product`, `sum`, `data` and
`namespace` off the chain and skips `type` as it skips `top`, each counted only as the kernel's own entry, so a
schema's own `data` is an ordinary record, and a chain reaching two of them is refused. All seven are abstract
(SPEC-FEEDBACK.md #15): a `data`-typed position admits only a meta layer's own data constructors' instances. The Java
model mirrors it: `schema.meta.Type` is the sealed interface `Atom`, `Product` and `Sum` extend, between them and
`Top`, so a body is a type exactly when it is an `instanceof Type`; `Data` and `Reference` implement `Top` directly,
and so does `TemplateBody`, which carries no chain. A namespace type resolves to a `RecordBody` of the facets it states,
kind `NAMESPACE`, and its values are read through the `MapBody` linking derives from it; an entry that is one of those
values is a `Namespace`, which implements `Top` directly too (below). The motivating case is an
HTTP operation, which must sit at the schema layer because that is the only layer able to name request and response
types *by name*.

- **`Data` is the one open branch of `Top`.** Every other branch is sealed all the way down: each leaf
  mirrors one kernel constructor, so a body's kind is decidable by inspection and each switch over them is
  exhaustive. This one is `non-sealed`, because the constructors reaching it are declared by meta-schemas
  this library has never seen and their bodies are the consumer's own classes.
- **The registration is a `@Typename` and a name binder, and nothing else.** A class carries
  `@Typename(name = "operation")` and implements `Data`; the `DataBindContext`'s `DataNameBinder` has to be
  able to find it. A consumer composes rather than copies — `SchemaMetaNameBinder.extendedWith(theirs)` asks
  the kernel's own vocabulary first and theirs only for a name it does not know, so the kernel's table is
  never duplicated and nothing shadows it. `contextExtendedWith` is that binder in a ready-made context, and
  `ProcessorConfig.withMetaNameBinder` is the same seam through the front door — the resolution core's *mode* is
  fixed (bind, always), which names it knows is not. **No
  reader family and no `ValueReaderFactoryRegistry` entry**: the ordinary record reader binds the
  `!operation { ... }` payload straight into the record, so §7.2 closure, field states and every atom
  constraint in the constructor's declaration are enforced exactly as for a written body.
- **A constructor with no resolvable class is an error where it is written**, not a value carried in some
  generic form. A schema asserting structure nothing can interpret is worth failing on.
- **A host declares nothing; the slot declares the spelling** (SPEC-FEEDBACK.md #13, #24). A slot naming a type is
  typed by a kind, `request: kind<record>`, and a slot naming a value by a reference, `method: ref<method>`; a slot
  typed by a record holds the record's value inline, and a `TypeRef` or `Ref` component there is a
  `BindMismatchException`. The read records every name it produces at a kind- or reference-typed slot with the
  path it descended (`["methods", "place_order", "request"]`), which `SchemaResolver` keeps per entry in
  `TsonSchema.valuesByName`, and the linker judges each by the slot's declared type: a member of the kind
  (`ValuesByName.judgeMember`), or a value IS-A the reference's `of` (`ValuesByName.judge`). A payload's Java shape
  says nothing about which components are references, so nothing asks it; the host class is opaque by design.
- **One host shape for every reference.** A `type_ref` slot binds a `TypeRef`, a kind-typed slot a `TypeRef`, and a
  reference-typed slot a `Ref<T>` or a `TypeRef`; `BindTargets` sets a `KindSlotReader` or `ReferenceNameReader` in
  place of the slot's atom reader for them, and a `Ref` receives the value the name reaches when the schema links
  (`Ref.deliver`), so a host reads `binding.method().get()` after the link and the name before it. A reference in a
  data document is not yet read (`NOT_IMPLEMENTED`).
- **A segment's type walk is schema-directed; so is its value walk, except inside a `Data` body.** The linker follows
  the declared types (a record's fields to a field's type, a namespace type to its pinned `value_type`, read off the
  map linking records for it) and enters a map only where it is a value of a namespace type. A `Namespace` entry is
  entered by key: the first further segment selects in `entries()` and the value reached is of the constructor's
  `value_type`, so `handler: myapi.get_orders` reaches the `method` value and is delivered into the host's `Ref`. A
  `Data` body is opaque to the type system, so there the value at each step is read off the host's record components,
  a tagged subtype followed through the value's own class where a record family base lacks the field; that hop goes
  with `data` (BACKLOG). A segment past a namespace key selects a field of the record value reached, read off its
  host's components as in a `Data` body. A kind-typed slot of a value body holds
  one bare name: a braced application there is refused and the alias spelling named, since nothing lifts or mints
  in a value body; a `type_ref` slot there is judged by resolution, arity and §4.1.
- **§8.2 reaches a body's values at the read, not at the link.** The linker's name walk sees a bound `Data`
  body as an opaque object, so `SchemaResolver` reads a `data` constructor's payload under the registry's
  identifier policy, and a namespace type's payload under the same policy (`payloadNames`, kind `DATA` or
  `NAMESPACE`), and the readers judge it as they judge a data document: the keys of an identifier-keyed map
  are one scope, and an identifier-typed value is a name (`design/name-hygiene-and-minted-names.md`). A
  `type_ref` in the body is judged there too, being one; it names a declaration the walk judges anyway, so the
  only difference is that a name refused at both is reported once, against the body that references it.
- **The silent defaults are worth knowing.** `TypeInhabitance` calls a `Data` body or a `Namespace` inhabited and
  `DiscriminationClass` gives it none, both by their `default` arm. Neither matters while the linker refuses
  to let anything name such an entry as a type — which is what makes that refusal load-bearing rather than
  a nicety. A value by name may reach a DATA or namespace entry (`orders.methods.place_order` starts at one,
  `myapi.get_orders` at the other), and that is no exception: a slot holding a value is not a type position, so
  `response: orders` at a `type_ref` slot is still §4.1's refusal, `request: orders` at a `kind<record>` slot is
  refused as no member of the kind, and `response: myapi` at a `type_kind` slot of a value body is refused the same
  way. A namespace entry is a member of `kind<api>` and `kind<namespace>`, the construction walk reaching `api` from
  its recorded constructor, and of no kind of types.
- **Resolved output is ordinary.** §8.1's `body` carries an instance of whichever constructor built the entry, and a
  meta-schema's own constructor is not a special case: a DATA entry writes as `body: !operation { ... }`, formally
  indistinguishable from `!record { ... }`. A value by name is a `TypeRef` and is written as any `TypeRef` is; the
  name in it is the one written, as a reference chain is (§8.3). What makes that work is a general `ltr8-bind` rule
  (`DataClassUnion`) — a non-sealed union branch stands for its own implementations, which exact-class membership
  would never match. A namespace entry writes as written, `myapi => !api { get_orders => { response: order_list } }`:
  the tag is the value's `type`, which no Java class names, so `DataClassObjectWriter` writes a `Namespace` through
  `Top`'s descriptor and emits the map itself, each key with its annotations, bare where it is an identifier and
  quoted otherwise, each value tagged only where its class carries `@Typename`. It reads back through `top`'s tag
  dispatcher, which delivers a `Namespace` where the tag selects a namespace type (`design/record-dispatch.md`).
- **A namespace entry is read in bind mode, and two readers build the `Namespace`.** A namespace type's own reader is
  its map's (`design/linking-and-compilation.md`), and in bind mode it yields an `AnnotatedMap`, keeping each key's
  annotations and order (`MapBindReader`). Where the position is the entry itself, the map becomes an instance of
  the constructor: the meta's constructor reader for a namespace type is that map reader wrapped in
  `NamespaceInstanceReader`, which is what `!api { … }` as an entry reads through; and resolved output read at `top`
  wraps the same way in `RecordTagDispatchReader`. A position typed by a namespace type (`methods: method_map`, a
  record's `fields`) still binds the plain map; the schema map and `fields` becoming `Namespace` is BACKLOG.
- **A meta-schema keeps a constructor this library cannot build a reader for**, its factory standing in as
  an `ErrorReader` carrying the real cause (`TsonCompiledMetaSchema`'s `unbuildable`). Dropping it would lose
  the constructor from the scoped vocabulary silently, so a governing meta would compile and register looking
  healthy and the complaint would land against a *different* document: the first governed schema to apply it
  is told the meta-schema does not declare it, which is both false and unactionable. **This is the only
  constructor that reaches an `ErrorReader` for want of a reader**: every constructor the kernel and meta.tn
  declare builds a real one, and `CoreSchemaImportTest` asserts that no entry of core.tn compiles to an
  `ErrorReader`. A governed schema's DATA or namespace entry compiles to an unbuildable `ErrorReader` in both stacks:
  its body names a meta layer's constructor, which has no factory there, and nothing is typed by such an entry.

- **A meta layer is not a vocabulary channel, and this is the first thing an author tries.** The instinct on
  declaring `operation` in a meta layer is to put the shared types beside it — a `status_code` atom, an
  envelope template — and let the governed schemas name them. They cannot: `!!meta` says where this schema's
  *constructors* come from and merges nothing into the type-name namespace (§3.3.2), so every such name is an
  unresolved reference in a schema the layer governs, whether it is written bare, in a field, or applied with
  its arguments. **Shared vocabulary goes in a third ordinary schema**, `!!import`ed by whoever needs it,
  including the meta layer itself if it needs it too. Nor is `!!import`ing the meta layer a way round it: a
  layer chaining to meta-kernel imports meta.tn, which imports meta-kernel, and imports are transitive here
  (§2.2.3), so meta-kernel's `void` arrives alongside core.tn's and collides — correctly, and
  with a diagnostic naming both origins. A namespace type meets both walls at once: it IS-A `top`, so it cannot go
  in the third schema, and a schema typing a field by it imports the meta layer that declares it and so cannot import
  core.tn too (SPEC-FEEDBACK.md #23). The constraint is real and worth stating; what is not acceptable is
  discovering it through a message about the wrong thing, which is why an application's head resolves in the
  type-name namespace only (§3.3.1) and an unresolvable one is reported as written
  (`design/template-materialisation.md`).
