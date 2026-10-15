# `Data` and the `data` base kind

Design notes for the meta layer's extension point: where an instance of a meta-schema's own constructor lands when what it
describes is not a data type, how a consumer registers a class for one, and what a meta layer is not. Current form only;
history lives in git.

**Invariants**

- `Data` is the one open (`non-sealed`) branch of `Top`; every other branch is sealed all the way down.
- The registration is a `@Typename` and a name binder, and nothing else: no reader family, no
  `ValueReaderFactoryRegistry` entry.
- A constructor with no resolvable class is an error where it is written.
- `Data.references()` is declared, not discovered, and serves `type_ref` slots; a reference slot is typed `type_ref`,
  not `type_name`. A slot holding a value by name is typed by the value's type, and nothing declares its references:
  the resolver's read records each with its slot (`TsonSchema.valuesByName`). Both bind a `TypeRef` -- every reference
  has one host shape, and only the linker's judgement differs by slot.
- One interim rule, closed by `ref<T>` landing on the proposal branch: the host's component type selects the
  spelling at a record-typed slot (a `TypeRef` takes a name, a record class a braced value).
- The linker reads a name's slot type off the constructor's resolved fields along the path the read recorded,
  never off the Java class.
- `TypeInhabitance` and `DiscriminationClass` handle a `Data` body by their `default` arm, which is safe only while the
  linker refuses any *type reference* naming a DATA entry. A value by name may name one: it is a value, not a type.
- A meta-schema keeps a constructor this library cannot build a reader for, as an `ErrorReader`; it is never dropped.
- `!!meta` merges nothing into the type-name namespace: shared vocabulary goes in a third ordinary schema.

Related: `design/linking-and-compilation.md`, `design/class2-compilation.md`, `design/compiled-registries.md`,
`design/schema-resolution.md`.

## `Data`: an entry that is not a type (`schema.meta.Data`, §4.1's `data` base kind)

§2.2.2 calls the meta layer the format's sanctioned extension point, and a meta-schema may declare constructors of its
own. The question that needs an answer is where an *instance* of such a constructor lands when the thing it describes
is not a data type — `schema => {type_name => type_definition}` makes every schema-map entry a type definition. `data
=> top & {}` is the fourth base kind that lets one say otherwise, and `TypeKind.DATA` is what it resolves to. The
kernel names the other side: `type => top & {}`, which `atom`, `product` and `sum` compose with (SPEC-FEEDBACK.md #9),
so an entry is a type exactly when it IS-A `type`, and `data` IS-A `top` alone. `type` is not a base kind — kind
derivation reads `atom`, `sum` and `data` off the chain and skips `type` as it skips `top` — so adding it moved every
type's complete supertype list and no kind. The Java model mirrors it: `schema.meta.Type` is the sealed interface
`Atom`, `Product` and `Sum` extend, between them and `Top`, so a body is a type exactly when it is an `instanceof
Type`; `Data`, `Reference` and `TemplateBody` implement `Top` directly. The motivating case is an HTTP operation,
which must sit at the schema layer because that is the only layer able to name request and response types *by name*.

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
- **`Data.references()` is how a body's own references reach the linker**, and it is *declared, not
  discovered*: a payload's Java shape says nothing about which components are references, and consulting the
  constructor's declaration would only work for slots spelled `type_ref`. A body holding a `TypeRef` returns
  it and the name is checked against the same namespace every other reference is. A body that declares none
  simply has none checked — that is the cost of the branch being open, and it is the reason to type a
  reference slot `type_ref` rather than `type_name` (§5.6's positional form keeps the author writing a bare
  name either way). §9's guidance for extension meta-schemas is the other half: a slot holding a type reference
  MUST be typed `type_ref`, which is what makes it participate in reference walking and identity.
- **A value by name is recorded by the read, not declared by the host** (SPEC-FEEDBACK.md #13). A slot typed by a
  constructor or a record (`request?: record`, `method: method`) whose component is a `TypeRef` reads an unquoted
  qualified name (`orders.methods.place_order`) as written, into the `TypeRef`'s `name`, and the read records it with
  the path it descended (`["methods", "place_order", "request"]`), which `SchemaResolver` keeps per entry in
  `TsonSchema.valuesByName`. **One host shape for every reference** is the point: `type_ref`, a qualified name and the
  coming `ref<T>` are each a reference that must resolve, and differ only in the judgement — one segment naming a type
  at a `type_ref` slot (`type_ref` is `ref<type>`, types living only in the schema map), segments reaching a value
  IS-A the slot's type here. With one Java type, a host declaring its by-name references would decide their judgement
  by which list it put them in — `request: text` returned from `references()` would resolve as a type and pass — so
  nothing asks it to: the read's record is judged against the slot whatever the class returns, and a by-name
  reference it also returns from `references()` is recognised by identity and not resolved as a type
  (`design/linking-and-compilation.md`). This walks no data body and interprets nothing; it records what the reader
  itself produced at a vocabulary-typed slot. Under `ref<T>` the two lists merge into one judgement by slot type.
- **Which slot reads a `TypeRef` by name is decided when the reader is built** (`BindTargets`): a slot read by a
  record whose bare token is not positional. At `type_ref` the bare token fills `name` (§5.6) and is bound as it
  always was; at an atom a token is a literal and a `TypeRef` is a `BindMismatchException`. The component holds a
  reference and nothing else: a braced value at the slot is refused at the read, which is the binding's verdict —
  a class wanting a record value inline declares that record's class instead. That the host decides is the interim
  rule above.
- **A segment reaches into a data body only as an interim.** `data` is opaque to the type system and is the host's
  to interpret, so the linker selects a field of a bound body through the host's record components and nothing in
  the schema walks the body's values. That hop is what the namespace kind replaces.
- **§8.2 reaches a body's values at the read, not at the link.** The linker's name walk sees a bound `Data`
  body as an opaque object, so `SchemaResolver` reads a `data` constructor's payload under the registry's
  identifier policy and the readers judge it as they judge a data document: the keys of an identifier-keyed map
  are one scope, and an identifier-typed value is a name (`design/name-hygiene-and-minted-names.md`). A
  `type_ref` in the body is judged there too, being one; it names a declaration the walk judges anyway, so the
  only difference is that a name refused at both is reported once, against the body that references it.
- **The silent defaults are worth knowing.** `TypeInhabitance` calls a `Data` body inhabited and
  `DiscriminationClass` gives it none, both by their `default` arm. Neither matters while the linker refuses
  to let anything name such an entry as a type — which is what makes that refusal load-bearing rather than
  a nicety. A value by name may reach a DATA entry (`orders.methods.place_order` starts at one), and that is
  no exception: a slot holding a value is not a type position, so `response: orders` at a `type_ref` slot is
  still §4.1's refusal and `request: orders` at a `record` slot is refused as no `record`.
- **Resolved output is ordinary.** §8.1's `body` carries an instance of whichever constructor built the entry, and a
  meta-schema's own constructor is not a special case: a DATA entry writes as `body: !operation { ... }`, formally
  indistinguishable from `!record { ... }`. A value by name is a `TypeRef` and is written as any `TypeRef` is; the
  name in it is the one written, as a reference chain is (§8.3). What makes that work is a general `ltr8-bind` rule
  (`DataClassUnion`) — a non-sealed union branch stands for its own implementations, which exact-class membership
  would never match.
- **A meta-schema keeps a constructor this library cannot build a reader for**, its factory standing in as
  an `ErrorReader` carrying the real cause (`TsonCompiledMetaSchema`'s `unbuildable`). Dropping it would lose
  the constructor from the scoped vocabulary silently, so a governing meta would compile and register looking
  healthy and the complaint would land against a *different* document: the first governed schema to apply it
  is told the meta-schema does not declare it, which is both false and unactionable. **This is the only
  constructor that reaches an `ErrorReader` for want of a reader**: every constructor the kernel and meta.tn
  declare builds a real one, and `CoreSchemaImportTest` asserts that no entry of core.tn compiles to an
  `ErrorReader`.

- **A meta layer is not a vocabulary channel, and this is the first thing an author tries.** The instinct on
  declaring `operation` in a meta layer is to put the shared types beside it — a `status_code` atom, an
  envelope template — and let the governed schemas name them. They cannot: `!!meta` says where this schema's
  *constructors* come from and merges nothing into the type-name namespace (§3.3.2), so every such name is an
  unresolved reference in a schema the layer governs, whether it is written bare, in a field, or applied with
  its arguments. **Shared vocabulary goes in a third ordinary schema**, `!!import`ed by whoever needs it,
  including the meta layer itself if it needs it too. Nor is `!!import`ing the meta layer a way round it: a
  layer chaining to meta-kernel imports meta.tn, which imports meta-kernel, and imports are transitive here
  (§2.2.3), so meta-kernel's `void` arrives alongside core.tn's and collides — correctly, and
  with a diagnostic naming both origins. The constraint is real and worth stating; what is not acceptable is
  discovering it through a message about the wrong thing, which is why the `source` fallback excludes a
  `source` carrying arguments (`design/linking-and-compilation.md`).
