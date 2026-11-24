# Linking and registration

`TsonSchemaLinker` (pass 2: import merge, reference validation, §5.10's link-time rules, values by name, held values),
`TsonLinkedSchema`, `TsonSchemaRegistry` and `CanonicalIdentity`. The linker's other checks have notes of their own:
`design/kinds-and-name-types.md`, `design/inhabitance-and-record-extension.md`, `design/choice-disjointness.md`,
`design/name-hygiene.md` and `design/externs.md`. Namespace types, value entries and the walk a value's name takes
are `design/namespace-types-and-value-entries.md`'s.

**Invariants**

- The linker lives in `tson-compiler`, a pipeline stage beside resolve and compile; the registry in `tson-schema`,
  storage over the value model that everything depends on. **The linker materializes nothing**: desugaring and
  `ParametricMaterialiser` have closed every closable application a phase earlier, so the only arguments it meets are
  inside parametric records, and an application whose head names nothing keeps its argument list
  (`design/parametric-materialisation.md`). What the linker judges is what the author wrote.
- `CanonicalIdentity.canonicalize` reads a *reference* in any spelling of its identity; `validate` judges a
  document's own `!!id`, which must already be canonical. Neither rewrites an identity into validity.
- One namespace per schema ([TSON-SCHEMA] §2.2.3, §3.3): its own entries plus the whole transitive closure of its
  `!!import`s (`TsonSchema.namespaceSources`). An imported entry is carried as its own schema resolved it and never
  re-validated against the importer's. No rule ties what a schema may declare to what it imports. An extern's
  schema is resolved and registered with the imports, and none of its names merges.
- **Collisions are decided by an entry's origin**, never by name occurrence or by form. One schema reached by several
  routes unifies (its copies differ only in `subtypes`, which `unified` unions). Two different schemas declaring one
  name is an error naming both; since identities carry the revision, a closure reaching two revisions of core.tn
  fails at the merge rather than later as a conflict between identically spelled types. Two documents writing
  `[text]` declare nothing between them: nothing is minted, so nothing unifies by form (§8.2). A local entry reusing
  a closure name is refused and dropped, keeping the import's, which is already-linked material. An entry's
  origin is the import's own `originOf`, so an entry two hops away keeps its author.
- **A nested `!schema` map is open** (§2.2.3): several documents declaring one map path contribute to one map, the
  union of their members, each member's origin recorded per path and a member path two documents declare being the
  ordinary collision. `carryOver` takes no import's annotations onto the map's entry, and a contributed member is
  its contributor's, never a kernel entry (`TsonBundledSchemas.isKernel` judges by origin).
- `entryOrigins`, `enumForms`, `nameTypes` and `externs` are on `TsonLinkedSchema`, never on `TsonSchema` or a body
  (where `schema.meta`'s hand-written `equals` and the `@Record` trap would meet them), each merged through imports.
- A value entry (`ValueEntry`) is refused at every position a type-ref occupies: it is a value, and nothing is typed
  by it.
- An incomplete entry (`IncompleteEntry`, §5.5) is refused at every type-ref position, judged past alias hops, and is
  no kind's member; an alias's own target may name one. The names its fields hold are judged in its own schema's
  link, an imported one skipped (`validateHeld`). Where a schema map is written (`DataClassObjectWriter`) an
  incomplete entry and an alias whose chain ends at one are left out at any depth: a resolved form holds complete
  entries only.
- A kernel slot typed `meta.type_ref` is judged by membership (`KindMembers`, in `validateTypeRef`), after
  resolution: a constructor whose declaration is a record is a member; a value entry, a parametric record and an
  alias stopping at an application are not. An alias's target is judged by resolution and arity alone.
  `design/references.md` has what a reference's `of` decides.
- A choice's variants are distinct at the end of each §8.3 alias chain (`AliasChain.terminal`): `(text | my_text)`
  with `my_text => text` is the one duplicate an author cannot see.
- A namespace type is read as the map `NamespaceBody.read()` states: both compilers hand that map to the `map`
  factory, so neither factory table has a `namespace` entry.
- **A parametric record's verdict is known before any closed entry is judged, and reported once, where it
  stands**, keeping diagnostics in declaration order. An unresolved name in a record an application closes to is
  sent to the parametric record that wrote it, keyed on the name (`heldDeclarationNaming`); an application of a
  refused parametric record is not judged again.
- `TypeChain` answers every IS-A question: `computeSubtypes` credits closed types only, so a parametric record is in
  no `subtypes` index, and `TypeChain.isConstructor` reads a parametric record's chain as a closed one's.
- A value by name (§5.12) is judged over local entries only, after `validateEntry`: its first segment resolves in the
  merged namespace, alias hops followed, and nowhere else; its referent's declared type must IS-A the slot's,
  compared by canonical identity. One walk (`ValuesByName.reach`) judges a value entry's name, a field's default or
  pin at a reference naming values (`checkFieldValue`), and a name a data document writes there (the
  `RefBody.Values` lookup over the final namespace), so one rule decides what a name reaches wherever it is written.
- Dependent values held as written (§5.2, `HeldValues`) are judged last, only over a link otherwise clean and with
  nothing pending, by reading each through the tree-mode compile of the linked schema; local value entries only.
  The held value stays the schemaless tree: no default of the named type is injected into it.
- `TsonSchemaRegistry` never overwrites an identity and exposes no way to remove or enumerate one: that *is* the
  "locked" guarantee. It refuses a `bootstrap() == true` schema; `linkBootstrap` is the one way to link the kernel's
  bootstrap output, and its result is never registered.
- `linkReady` is the one partial link (§3.4.1): a reference to a pending declaration passes there and nowhere
  else, every pending name is a member of each reference naming types, and no reference gets a value lookup
  (`design/resolver-vocabulary-and-bootstrap.md`).

Related: `design/namespace-types-and-value-entries.md`, `design/references.md`, `design/class2-compilation.md`,
`design/compiled-registries.md`, `design/schema-resolution.md`, `design/parametric-materialisation.md`.

## The order of the link

`linkWith`'s order is load-bearing, each step reading what an earlier one wrote; its comments give most reasons.
Two they do not: `checkDisjointAssertions` runs after `withNameAnnotations`, since §6 puts `@meta.disjoint` both
on the definition and on the map key; `checkHeldValues` is last because it compiles the linked schema.

## Decisions that lost

- **Deferring §5.10's checks to an application.** Nothing ever closes `chain => <T> { tail: chain<T, T>? }`, so
  deferring would ship the mistake. Whatever no argument changes is judged at the declaration (§5.10, §5.10.1):
  arity of every reference, bare ones included, every name that is no parameter, literals under a type naming no
  parameter, productivity with the parameters assumed inhabited, the selectors, and a changed pin.
- **Judging what depends on a parameter at the declaration**, with stand-in arguments: a choice's distinct variants,
  a reference's `of`, a slot's kind. It would refuse parametric records correct for every argument anyone passes,
  so those are judged on the record an application closes to.
- **Reporting a parametric record's mistake per application.** Each application closes to its own record and fails
  on the same name; reporting each would say one mistake many times, away from where it was written.
