# Schema resolution

Turning grammar-layer declarations into resolved `schema.meta` values, an entry being its value (§8.1):
`SchemaResolver` for a document, `DefinitionResolver` for one declaration. Their class Javadoc, `WaitOrder`'s,
`DependentFields`' and `Restatements`' carry the mechanism.

**Invariants**

- **One namespace, every name from the root** (§3.3, §3.3.3): a name written inside a nested `!schema` map is resolved
  as at the root (`geo.address`, never a bare `address`); there is no lexical scope. An annotation's name is checked
  even with no value written: a name kept with its value lost would load clean with the metadata gone
  (`SchemaAnnotationScopeTest`). Only the kernel's bootstrap skips it, building the entries the check reads.
- **A nested map's members are entries under their paths, and the map's own value is derived** (`SchemaPaths.synced`,
  in `TsonSchema`'s compact constructor). A phase edits the path entry; where it and the map's copy disagree the path
  entry is held, whichever comes first — the kernel's `meta` precedes its members, and a linked schema rebuilt from a
  partly ready namespace carries a stale copy. The open-map merge across imports is
  `design/linking-and-compilation.md`'s, and `SchemaResolver.mergeImports` mirrors the linker's deliberately.
- **A judgement that walks a name to what it names waits for everything** (§5.8's one name type,
  `requireOneNameType`): run during resolution it would make a composition wait on what it only names, so
  `DefinitionResolver.judgeDeferred` runs it once every entry is ready.
- **An application written inline at a field type is not closed during resolution**; materialisation closes it after
  every entry is ready. Its reader meanwhile is an `UnclosedApplicationReader`, never the head's (a parametric record
  refuses every value): a held value binds as written and is judged at link, and a value entry's read reaching it is
  `NOT_IMPLEMENTED`, a gap that stays a gap (`resolveValueEntry`), never a payload refused as no value of its type.
- **`abstract`/`final` are grammar, applied once, in `DefinitionResolver.withExtension`.** Both change which values a
  position admits, §6's line between a kernel fact and an annotation, so no resolver code knows `abstract`, `final`,
  `sealed` or `discriminator` as annotation names. A fresh record and a composition build their own `RecordBody`; a
  mark read in each is a mark they can disagree on, and applying it once is why extensibility is never inherited.
- **A selector is `=?`, and only the source's `=?` derives ABSTRACT.** A `!meta.record` stating `discriminators`
  beside any `extension` but ABSTRACT — a missing one's default OPEN included — is refused, at a declaration
  (`withExtension`) and in place (`TypeInline.refusal`). Member dispatch is read where needed
  (`FamilySelectors.dispatchesOnMembers`), never stored as a second fact.
- **On a parametric record `final` is refused** (§5.2, §5.10): it is applied to be composed, and the mark would
  forbid that of every application. A selector typed by a parameter is refused (`refuseParameterTypedSelector`): a
  selector means the same in every application. A field's value is never a parameter (`ParameterTypes.atValue`).
- **No entry is IS-A a parametric record** (§5.10): an application's edges are its head's written parents'. An
  applied parent is folded into the edge to the declaration naming that application, at any depth, by
  `SchemaResolver.appliedParentEdges`; the walk is the fold's, because copying a head's parents into the composing
  record's `parents` would change a parametric record's resolved form, which states its parents as written. A
  use-site application whose record in place would be IS-A a declared type is refused
  (`ParametricMaterialiser.refuseMembership`, §5.2), so every family member is declared.
- **What no value holds is `TsonSchema.EntryFacts`**, never a constructor's field. `supertypes` is paths, sorted,
  complete to a fixed point; `parents` is the one list holding applications and a closed type keeps none
  (`closeChains`); no resolved entry carries `subtypes` (linking's); a written or declared `chain` is refused
  (`refuseWrittenChain`, `refuseDeclaredChain`).
- **A base kind and a constructor are judged by where `top` and the base kinds are defined, never by spelling**
  (`TypeChain.isConstructor(name, value, kernel)`, `TsonLinkedSchema.isKernel`). No entry carries a kind (§5.5).
- **A resolved field is four facts, not a state** (`RecordField`), and a restatement moves along three orders, never
  a state matrix (`DefinitionResolver.refines`); nothing stores what omission yields, so it is asked of
  `RecordField.omitted`. A modifier-only tightening inherits the type and with it the voidability; the name's `?` is
  always its own.
- **A restated type narrows, judged at link** (`TsonSchemaLinker.checkRestatedFieldTypes`, §5.8): the resolver binds
  a restatement before the names it holds are ready. Every restatement no closed record on the chain holds (over an
  applied operand, or under a subtraction) is carried to link (`TsonSchema.restatements`, `Restatements`), so the
  verdict never turns on whether a declaration names the application. A changed pin is judged there too
  (`changedPin`, by `ValueIdentity`, so `= 255` restates `= 0xFF`): reading a value needs its type ready.
- **A restated field's annotations concatenate, restatement first** (§5.8): an annotation name is repeatable
  ([TSON-DATA] §3.1), so "the inherited `@doc`" names nothing, and `Annotations.get` takes the first, so the nearer
  declaration wins (`RestatedFieldAnnotationsTest`).
- **A restated group member stays a member**: its name `?` may be dropped, never added; it takes no default, and a
  pin on it is never supplied (`RecordField.omitted` answers NOTHING), since an injected member would be present and
  presence selects the option. So no member is always present, and §5.11's rule against two has no check.
- **A dependent field's rules are the link's, over the flattened record** (`DependentFields`). Everything else that
  walks field types skips one (`TypeInhabitance`, `checkFieldValue`, `NameType`, `ParameterTypes`). Nothing refuses
  a sibling and its dependent field in two options of one group, where the field can be written in no document.
- **The exception's class picks the code** (`SchemaResolver.Problems.report`; `DefinitionResolver`'s Javadoc is the
  boundary). The one schema construct reaching `NOT_IMPLEMENTED` is an instance of a user constructor of types
  (`SchemaResolver.unmodelledType`): composing `meta.atom`/`product`/`sum` makes a form this library backs with no
  reader, a capability verdict; composing an existing constructor with an added field is refused the same way only
  because no model body holds the facet (`BACKLOG.md`). Around the compiled reader nothing is a catch-all: relabelled
  a gap, a broken invariant would tell the author to wait for a release.
- **A schema without `!!id`, or with a malformed one, is refused up front** (`SchemaWithoutIdTest`). The refusal is
  this library's: §10.1 lets an id-less schema register under an application-supplied identity, a route no front door
  here offers, so no vector states it.

**Decisions that lost**

- Deriving ABSTRACT over a stated `extension`: it made `extension: FINAL` mean ABSTRACT.
- Judging a parametric record's restatements at its declaration: it refuses `wrap => <T> box<T> & { v: int32 }`,
  whose `wrap<int32>` is sound.

Related: `design/constructor-application.md`, `design/atom-refinement-and-coherence.md`,
`design/parametric-materialisation.md` and `design/parametric-records.md` (§5.10 closing, applied-parent edges),
`design/resolver-vocabulary-and-bootstrap.md` (checkpoints, alias hops, `@meta.application`, the bootstrap),
`design/namespace-types-and-value-entries.md` (value entries), `design/schema-grammar-and-desugaring.md`,
`design/linking-and-compilation.md`.
