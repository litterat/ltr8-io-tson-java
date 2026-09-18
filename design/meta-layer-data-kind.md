# `Data` and the `data` base kind

Design notes for the meta layer's extension point: where an instance of a meta-schema's own constructor lands when what it
describes is not a data type, how a consumer registers a class for one, and what a meta layer is not. Current form only;
history lives in git.

**Invariants**

- `Data` is the one open (`non-sealed`) branch of `Top`; every other branch is sealed all the way down.
- The registration is a `@Typename` and a name binder, and nothing else: no reader family, no
  `ValueReaderFactoryRegistry` entry.
- A constructor with no resolvable class is an error where it is written.
- `Data.references()` is declared, not discovered; a reference slot is typed `type_ref`, not `type_name`.
- `TypeInhabitance` and `DiscriminationClass` handle a `Data` body by their `default` arm, which is safe only while the
  linker refuses any reference naming a DATA entry.
- A meta-schema keeps a constructor this library cannot build a reader for, as an `ErrorReader`; it is never dropped.
- `!!meta` merges nothing into the type-name namespace: shared vocabulary goes in a third ordinary schema.

Related: `design/linking-and-compilation.md`, `design/class2-compilation.md`, `design/compiled-registries.md`,
`design/schema-resolution.md`.

## `Data`: an entry that is not a type (`schema.meta.Data`, §4.1's `data` base kind)

§2.2.2 calls the meta layer the format's sanctioned extension point, and a meta-schema may declare
constructors of its own. What the kernel had no answer for is where an *instance* of such a constructor
lands when the thing it describes is not a data type — `schema => {type_name => type_definition}` makes
every schema-map entry a type definition. `data => top & {}` is the fourth base kind that lets one say
otherwise, and `TypeKind.DATA` is what it resolves to. The motivating case is an HTTP operation, which must
sit at the schema layer because that is the only layer able to name request and response types *by name*.

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
- **The silent defaults are worth knowing.** `TypeInhabitance` calls a `Data` body inhabited and
  `DiscriminationClass` gives it none, both by their `default` arm. Neither matters while the linker refuses
  to let anything name such an entry as a type — which is what makes that refusal load-bearing rather than
  a nicety.
- **Resolved output is ordinary.** §8.1's `body` carries an instance of whichever constructor built the
  entry, and a meta-schema's own constructor is not a special case: a DATA entry writes as
  `body: !operation { ... }`, formally indistinguishable from `!record { ... }`. What made that work is a
  general `tson-bind` fix (#121) — a non-sealed union branch now stands for its own implementations, where
  exact-class membership never matched them.
- **A meta-schema keeps a constructor this library cannot build a reader for**, its factory standing in as
  an `ErrorReader` carrying the real cause. Dropping it — which `TsonCompiledMetaSchema` used to do — lost
  the constructor from the scoped vocabulary silently, so a governing meta compiled and registered looking
  healthy and the complaint landed against a *different* document: the first governed schema to apply it was
  told the meta-schema does not declare it, which is both false and unactionable. **This is now the only
  route to an `ErrorReader` at all**: every constructor the kernel and meta.tn declare builds a
  real reader, `scoped` having been the last, and `CoreSchemaImportTest` asserts that no entry of core.tn
  compiles to one.

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
  discovering it through a message about the wrong thing, which is what the `source` fallback's
  argument-bearing case (`design/class2-compilation.md`) used to give.

**`spec/m/` is a cache of the spec, with one difference: the hash pins.** The published drafts spell them
`xxhash` and compute real digests at publication, so these copies carry digests over their own bytes and
`TsonBundledSchemas` holds those rather than tson.io's.
