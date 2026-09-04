# Schema resolution and the meta-kernel bootstrap

Design notes for the resolution phase — turning grammar-layer declarations into resolved `schema.meta`
`TypeDefinition` values — and the special-cased bootstrap that resolves meta-kernel itself. Current form
only; history lives in git. `CLAUDE.md` holds the one-paragraph orientation; this file holds the detail.

## Schema resolution (`tson-compiler/.../resolver/`)

`DefinitionResolver` (package-private) turns one grammar-layer `SchemaMap.Declaration` into a resolved
`TypeDefinition` (§4, §8, values from `schema.meta`). `TsonSchemaResolver` (public, root package, a thin
wrapper over `SchemaResolver`) resolves a whole `SchemaDocument`: header-directive validation, deriving
the structure namespace from the governing `!!meta`, merging `!!import` entries into the type-name
namespace *before* any local declaration resolves. That merge is transitive and its collisions are decided
by entry identity, exactly as at link time (§2.2.3, and `docs/linking-and-compilation.md`
for the rule in full) — this is the same concept discovered one phase earlier, so the two implementations
are kept in step deliberately.

- **Three namespace dependencies are constructor-fixed, via functional interfaces**, not threaded per
  call: `DefinitionGetter getTypeDefinition(name)` (the accumulating type-name namespace — typically a
  method reference onto a caller's growing map), `DefinitionGetter metaDefinitions` (the structure
  namespace — the governing meta's entries, consulted *only* for a constructor-application target per
  §3.3.1), and `DefinitionMetaReader read(type, value)` (binds a constructor-application/atom-refinement
  value through the governing meta's compiled reader). A resolver with nothing to offer supplies always-
  throwing / always-null constants.
- **An annotation on a declaration resolves one hop and only one hop** (§3.3.3): against the governing
  meta's namespace (`metaDefinitions`), never the schema's own declarations or its `!!import`s. The name is
  checked whether or not a value was written — §6 makes bare `@T` shorthand for `@T:_`, so both forms name a
  type — and a name that misses is a `TsonSchemaValidationException` against the declaration that wrote it,
  with the near miss worded separately: a type *this* schema declares (or imports) is usable by the schema's
  **data documents** and not within the schema document itself, so the message names the remedy (move the
  declaration into a meta-schema and point `!!meta` at it). Silence here was the harmful outcome and is what
  the check replaces — the annotation used to keep its name and lose its value, so the schema loaded clean
  and the metadata was not there; §6 makes an unresolved annotation name a resolver error, the valueless
  form included (`SchemaAnnotationScopeTest`). A value that *does*
  resolve is read by that type's own compiled reader, so `@doc:"..."` arrives as a `String`. **The one
  resolver that skips the check is the meta-kernel bootstrap**, which passes no `AnnotationValueReader` at
  all: it is producing the very entries such a reader would read through, so every name would fail, and there
  the name is kept and the value dropped as before. Both annotation sets go through this — the ones after
  `=>` that land on the `TypeDefinition`, and the ones before the name that land on the entry's key — and
  `SchemaResolver` catches the second set's failures itself, since that loop runs outside the memoized getter
  that catches the first set's.
- **A restated field's annotations merge over the inherited ones, restatement first** (`resolveField`/`merged`).
  §5.8 flattens a composition's inherited fields and §5.7 lets a body entry restate one, and neither says what
  becomes of the field's annotations; a resolver's two paths gave two answers, an inherited field being absorbed
  whole while a restated one was rebuilt with only what the restatement wrote. The rule closes that: the
  restatement's own annotations in source order, then the inherited field's, one path serving refinement and
  composition alike. **Concatenation rather than replacement by name**, because [TSON-DATA] §3.1 makes a name
  repeatable on one value with every occurrence preserved — annotations are a list, not a map, so "the
  inherited `@doc`" names nothing when the source wrote two. **Restatement first**, because order *is* the
  precedence mechanism: `Annotations.get`/`value` take the first occurrence, so leading with the nearer
  declaration is what a first-occurrence lookup reads. **The ordering half has no read-side witness any more**
  — `@bytes_encoding` was the one field annotation with decode force, and the alphabet is a type selector now
  (`bytes_type.encoding`), so no annotation the meta layer declares changes how a value reads. The rule is
  unchanged; only its demonstration is now over resolved output. `RestatedFieldAnnotationsTest` covers each
  case; §5.8/§8.1 owe the rule, which is `SPEC-FEEDBACK.md` #25(c).
- **What resolves:** record construction; composition (`A & B & { ... }`, §5.8, with kind from the literal
  base-kind names in the transitive supertype chain, and tightening in the trailing body per §5.7); the
  `^` refinement operator (§5.7, copies the source's whole field set, admits no new fields); bare
  references (§8.3); constructor application (`!C value`, §5.5, binds generically via the compiled
  reader — no hand-rolled name→class table, `tson-bind`'s union resolution finds the `Top` member by
  `@Typename`; a kernel body is a leaf of the sealed hierarchy, and a meta-schema's own constructor is an
  implementation of the open `Data` branch, admitted by the same lookup); atom refinement (`!I ^ { ... }`, §5.5/§5.7); subtraction (`A & { ... } - { f }`, §5.9);
  restating a field group in a refinement or composition body (§5.11 — same member labels in the same order,
  types verbatim, state tightening OPTIONAL→REQUIRED only; only the *group's* state moves, since members
  flatten as `OPTIONAL` regardless).
- **A template closes by application, never by construction**, and naming one at a construction site is an
  author error (`DefinitionResolver.resolveInstance`). `C<...>` substitutes a template's parameters away
  (§5.10); `!C { ... }` fills a *constructor's* own vocabulary (§4.2). Different operations, and the check
  is on being a template — having parameters — not on carrying `~`, so an unmarked §5.10 template gets the
  same advice instead of the "did you mean atom refinement?" hint, which cannot help when what is missing is
  the argument list. **This is why the `RecordBody` check below it is genuinely unreachable**: its own
  comment used to claim so and was wrong, because an *open* declaration holds its body (`holdIfOpen`) and a
  parameterised `~` declaration is exactly one — so `!my_set { … }` reached an `IllegalStateException`,
  which is this project's spelling of *an internal invariant broke*, and the CLI reported an author's schema
  mistake as a library fault at exit 70. `TemplateClosesByApplicationTest` pins all of it.
- **What `!C { … }` may apply is IS-A `top` (§4.1), not the `~` marker** (`requireApplicable`). §4.1 makes
  every base kind IS-A `top` and every constructor transitively so, while IS-A stops at construction — an
  instance or a fresh record carries an empty chain — so the predicate admits every constructor and, beyond
  them, exactly the entries describing *a type* rather than a part of one. Measured over the bundled schemas:
  `constructor ⊂ IS-A top`, the difference being the four base kinds plus `reference`, and no constructor
  failing to be IS-A `top`.
  **Asking for the marker was both too narrow and inconsistent.** `reference` is deliberately unmarked (it
  describes no value) and the language needs it applicable, so it took a by-name exception in the template
  path and none in the closed one — `<T> !reference { target: T }` resolved while `!reference { target:
  int32 }` did not, one construction with two answers. The exception is gone. A base kind is now admitted and
  refuses itself through its own reader, naming the subtypes that would satisfy the position, which is the
  better message. What stays out is the component set — `record_field`, `type_ref`, `type_argument`,
  `tuple_element`, `field_group`, `integer_size`, `atom_specification`, `type_definition` — record-bodied
  with empty chains; without a check those fail anyway on `Top` being sealed, but as a `ClassCastException`
  surfaced as `NOT_IMPLEMENTED`, a non-verdict for an author error. `TsonCompiledMetaSchema.buildConstructors`
  filters on the same predicate, so a head the gate admits has a reader.
  **Admitting `reference` closed means giving it the alias's own entry**, not just letting it through:
  `!reference { target: X }` resolves to `kind: REFERENCE` with `X` as source and body, the same entry
  `name => X` denotes (§8.3), where a construction of any other head takes the head's kind and names the head
  as its source. The closed path dispatches on the *body* being a `Reference`, having already read it;
  `resolveInstanceTemplate` holds its body unread and so still needs the head's name — which is the whole of
  what its `alias` flag is now for, the eligibility half having gone.
  **Two readers of `constructor` went with it**, both replaced by a question about shape rather than about
  the marker: atom refinement asks whether the source's body is a vocabulary (an atom *instance* carries its
  bound value — `IntegerType`, `Unit` — where the family's constructor carries a record), which is §5.5's own
  question of whether there is a value to narrow; and the governed-compile factory lookup asks IS-A `top`, so
  a construction that resolved reaches a factory rather than failing "out of scope" on a narrower test.
  **The marker keeps its other jobs** — §4.2's level discipline reads it, and §8.1 records it —
  and `SPEC-FEEDBACK.md` #36 asks §3.3.1 to state applicability this way.
  `ApplicabilityIsIsATopTest` pins it.
- **§4.2's three declaration-time rules for `~`, and where each is answered.** **Placement** (a `~`
  declaration only in a schema whose own `!!meta` names the meta-kernel) is checked at the declaration.
  **Level discipline** (an entry composing with, refining, or subtracting from a constructor MUST itself be
  `~`) is checked on each operand, at two sites — `resolveComposition`'s supertype loop and
  `resolveRefinement`'s source — which is all three operations, §5.9's removal clause applying to a
  composition so its operand arrives as a supertype. The check asks only whether the *operand* is a
  constructor, because the rule is one-directional: a non-constructor operand in a `~` declaration stays
  legal, which is what lets a base kind seed the level (`record => ~product & { … }` — the base kinds are
  non-constructors) and `atom_specification` lend vocabulary. What it protects is the two indexes §7.2's
  subsumption rule reads: unchecked, an ordinary type composing with a constructor resolved to
  `constructor: false` carrying that constructor in `supertypes`, and the constructor gained a
  non-constructor `subtypes` entry. Construction is exempt and needs no marker — §5.5 transfers kind and no
  supertypes, so `!C { … }` mixes no index, and it is the remedy the refusal names.
  `ConstructorLevelDisciplineTest` pins all of it, including that no bundled schema mixes the two levels.
- **§4.2's value-route-only rule is enforced where the argument lands, not at the declaration.** A `~`
  declaration's parameter routed into a *vocabulary slot* typed `type_ref` — `my_set => <T> ~array ^ {
  element_type: = T }` — is refused when it closes, by §5.2's rule that a fixed value is available on a
  field typed by an atom or an enum and nowhere else; the legal value-routed form (`max_items: = N`, an
  atom-typed slot) closes normally. What is **not** checked is the channel §5.2 never sees, a parameter
  standing as a *field type* or a *variant*. §4.2 calls that a resolver error at the declaration, and its
  stated reason — that a type-channel parameter "could close only by rewriting the body — the materialisation
  constructors never get" — does not hold here: §5.10 materialisation rewrites held bodies and closes exactly
  this shape, which `DefinitionResolverTest.resolvesACompositionTemplateAsAHeldFlattenedRecord` pins. **It
  closes into a working type, not a tolerated one**: `ctor_box => <T> ~base & { value: T }` with
  `flagged => ctor_box<boolean>` materialises, compiles, accepts `{ value: true }` and rejects
  `{ value: banana }` with a `TYPE_MISMATCH` at `/value`; the variant channel behaves the same. That is the
  evidence `SPEC-FEEDBACK.md` #35 rests on, which proposes §4.2 delete the rule: its other two channels are
  decided by §5.2 and §5.10 anyway, and its remaining one has no legal spelling once level discipline is
  enforced beside it.
- **All six of §5.2's field-state spellings resolve**, including `field: type? = _` — `OPTIONAL_FIXED`
  carrying *no* value, so §8.1 writes a `record_field` without a `value` member and the field must be
  omitted or written `_`. Its three resolver errors are enforced: `~ _` on any field, `= _` on a REQUIRED
  one, and `type? ~ value`. **Presence comes from the entry's own `?` when it restates a type, else from the
  field it tightens** — §5.2 makes `= _` valid on a field "declared with `?` *or inherited as OPTIONAL*",
  and a modifier-only entry has no `?` to read. That is why `resolveField` takes the whole inherited
  `RecordField`, not just its type. A consequence worth knowing: a modifier-only tightening moves only the
  *mutability* axis (§5.7's "only the value state changes"), so an inherited-OPTIONAL field pinned with
  `= 0` lands in `OPTIONAL_FIXED`, not `REQUIRED_FIXED` — promoting it to always-present takes restating the
  type (`min: integer = 0`). The exception is a **parametric** modifier, which §5.7's "Open modifiers" puts
  in a REQUIRED-family state whatever the presence axis says (that is what makes a user template's
  `min_items: = MIN` mandatory), so the parameter branch sits ahead of the `OPTIONAL_FIXED` one.
- **§5.11's group presence rule is checked after every body**, refinement and composition alike: two members
  of one group both left in a REQUIRED-family state (`REQUIRED`/`REQUIRED_DEFAULT`/`REQUIRED_FIXED`) is a
  resolver error, because a group admits at most one member and nothing could satisfy the result. Only this
  declaration's own tightenings can trip it — members flatten as `OPTIONAL` when first declared, so by
  induction a source that passed hands on at most one always-present member. `= _` is deliberately *not*
  always-present (it lands in `OPTIONAL_FIXED`); forbidding one alternative's value is what §5.11 offers it
  for. The spec says "a refinement" but the paragraph is headed "Refinement and composition" and a
  composition body builds the identical unsatisfiable type, so both are checked.
- **Subtraction runs last and breaks IS-A on purpose** (§5.9). Supertypes merge, the body adds and tightens,
  *then* removals apply to the merged field set with no regard for which supertype contributed a field
  (rule 3 — the contract is already broken, so there is none left to violate). Two things are rejected:
  removing a name that isn't there (rule 2), and removing one this declaration's own body also states
  (rule 4 — adding-then-removing, or tightening-then-removing, says two incompatible things), checked in
  that order because a body-introduced field *is* in the merged set and "no such field" would be the wrong
  diagnosis. The output splits the two supertype lists §8.1 keeps apart: `type_definition.supertypes` is
  **emptied** (the contract — so §7.2's subsumption check won't let a subtracted type stand where its source
  is expected), while `record.supertypes` keeps the head's list as authorial lineage. `kind` still comes off
  the lineage chain. **Every** supertype goes, including one that contributed nothing to the removal — `A & B
  - { f }` with `f` from `A` drops IS-A with `B` too, though `B`'s fields all survive. §4.3 states the break
  at composition's own precision ("subtraction revokes IS-A for every parent while keeping lineage") and
  §5.9 gives the reason: the clause is head-level, so its effect is readable without scanning the parents'
  field sets. Subtract first and compose second where an author wants partial retention.
  Groups follow §5.11: a removed member leaves `members`, a group down to one member is
  dissolved into a plain field taking the *group's* state (members flatten as `OPTIONAL` whatever the group
  says, so the survivor would otherwise silently lose a REQUIRED group's "exactly one"), and a group with no
  members left is dropped — §5.11 runs the arity ladder to zero and states the two-member minimum as an
  invariant of resolved output.
- **Chained atom refinement merges with the source, it does not replace it** (§5.6's merge semantics):
  `bounded => !int8 ^ { min: -100 }` must still carry `int8`'s own `size`. `mergeWithSource` re-serializes the
  source's bound value via `TsonObjectWriter` and merges field-by-field (explicit values win) — no
  per-atom-class merge logic needed. **The merge runs on the wire record, before binding, and has to**: a
  constructor field that is `REQUIRED` with no schema default (`float_type.format`, `binary.encoding`) is one
  a refinement body has no reason to restate, so binding the body alone would fail `FIELD_REQUIRED`
  (`DefinitionResolverTest.atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed` pins that case). This is
  why `DefinitionResolver` still holds a `TsonObjectWriter`, and in turn why `TsonObjectReader`/
  `TsonObjectWriter` can't move to the `tson` module. **The text round-trip has no cheaper substitute**:
  `TsonObjectWriter` emits straight to a `TsonDataEmitter`, so there is no object→`DataValue` step to borrow
  that would skip it. Removing it for real would mean each constraint family owning its own wire decoding —
  duplicating number-grammar handling (`0xFF`, `_` separators, quoted-vs-unquoted) and bypassing the compiled
  reader's own defaults — which costs more than the round-trip does.
- **A refinement must narrow, and this is enforced** (§5.7). After binding, `checkNarrows` asks the
  constraint family itself — `Atom.constraintsCheck(refined)`, one rule per `schema.meta` family over the
  shared `AtomNarrowing` mechanics — whether the merged result is a valid tightening of the source's own body,
  and throws `TsonSchemaValidationException` if not (`!uint8 ^ { min: -10 max: 300 }` is rejected). Comparing
  the *merged* result rather than the refinement body is what lets an unmentioned facet tighten vacuously; a
  stated bound is judged against the source's **effective** range, folding in a derived one like an integer's
  `size` (intersecting the refinement's own bounds first would make every widening vacuous). Unchecked by
  design, each documented on its class: `pattern` against `pattern` (regular-language containment, and
  `tson-schema` has no `tson-regex` dependency) and **selector** facets
  (`component`/`format`/`encoding`/`version`) — core.tn's own prose calls a selector swap a narrowing, so
  rejecting one would reject a documented construct — §5.7 states the rule per facet kind, and a selector is
  settable where the source leaves it at the constructor's default, identity-only once bound.
- **A body must also be coherent with itself**, which is the other question about the same facets and
  needs no source to compare against. `checkCoherent` asks `Atom.coherenceCheck()` — one rule per family over
  the shared `AtomCoherence` mechanics, the `AtomNarrowing` twin — and throws `TsonSchemaValidationException`
  when a body's own facets admit nothing (`{ min_length: 10 max_length: 3 }`, `{ min: 10 max: 3 }`,
  `{ min_prefix: 40 max_prefix: 8 }`). §7.2 puts the rule and its home in one sentence — "family coherence
  between bindings (e.g. `min ≤ max`) is a **compilation** and ingest concern (§8), **not data validation**"
  — which is also why it cannot live in the atom parsers. Running it at *resolution* rather than compilation
  is deliberate and strictly earlier: the bound constraint objects first exist here, and both are schema-load
  time. It asks the **container** families the same question through `Product.coherenceCheck()`, the
  structural twin: `min_items` above `max_items` admits no value of any length, and an array and a map share
  the one rule since they carry the identical pair. Stating it on the family rather than with any one
  spelling is what makes `[text; 5..3]` and the `!array { … min_items: 5 max_items: 3 }` body it denotes get
  the same verdict — they are one type, and the rule used to live in the desugar phase, which only ever saw
  the first. `TsonSchemaLinker` asks it a second time for the entries **materialisation** mints, which
  resolution never produced: §8.2's "family coherence rules whose operands were parameters" is exactly a
  template whose bounds were `MIN`/`MAX` until an application supplied both. meta.tn's own header `@doc`
  states the same obligation from the other side: bounds are field
  groups so an inclusive/exclusive pair on one side is unrepresentable, while "value-level coherence (the
  lower bound not exceeding the upper) remains a schema-load check". `cidr4_type`'s `@doc` adds the family
  range — prefixes narrow "within the family range 0-32", and "bounds outside that range are invalid at the
  schema level" — so the CIDR pair is judged against its address width as well as against itself.
  - **Hooked in `bindAtomInstance`, not at either call site**, because that is where the `!C value` and
    `!I ^ { ... }` paths meet — one hook covers both, and a non-`Atom` body passes through. Running it after
    binding is what makes it generic: facets arrive converted to the host type their family compares on, with
    the constructor's own schema-composed defaults already filled in.
  - **Emptiness is the rule, not narrowness.** `{ min: 5 max: 5 }` pins a constant and resolves; the same
    range with either end exclusive admits nothing and does not. Integer folds its `size`-derived range in
    first, so it is the one family where a single stated bound can be incoherent on its own — the opposite of
    `constraintsCheck`, which deliberately does *not* fold the refinement side (there, intersecting first
    would make every widening compare vacuously equal).
  - **`multiple_of: 0` is the one case that was unsound rather than merely undiagnosed.** `IntegerParser` and
    `DecimalParser` validate with `value.remainder(m)`, which throws on a zero divisor — so before this check
    a valid *data* document read against such a type failed on the library's own fault code, an author error
    reported against the wrong document. `RationalParser` already guarded its
    own divisor.
  - Unchecked by design, each documented on its class and matching that family's existing narrowing gap:
    `duration_type`'s text bounds (ordering them means parsing them — `"P1M"` vs `"P30D"` does not order
    lexically, and judging them as strings would call a coherent body empty), `pattern` emptiness, and
    selector facets.
  - **The four network families check their own `within`/`excluding` entries here**, through
    `AtomCoherence.checkNetworks`: the facets are typed `[value]` in meta.tn and must stay so (they list
    networks, and meta declares no network instance to type them by — core.tn does, and core imports meta),
    so they arrive as text and the family that owns the rule is the only place that can judge them. That is
    why `schema.atom` carries `CidrNetwork` and `InternetAddress` at all: a check in the linker or the
    resolver would be a second home for one family's rule, which is what `Atom.coherenceCheck` exists to
    prevent. **The pair's own emptiness is judged there too** (`checkAdmitsAValue`): an `excluding` set
    covering every network `within` permits admits nothing, which is `{ min: 10 max: 3 }` with a different
    spelling. Cover over a prefix tree is counting rather than searching — two blocks are nested or disjoint,
    so an exclusion meeting a permitted block either contains it or lies wholly inside one half — so the rule
    is exact and total, not a partial prover. **A network family folds its prefix bounds in**, because its
    value is a block and a block is refused for *overlapping* an exclusion: `within: ["10.0.0.0/24"]
    excluding: ["10.0.0.5/32"] max_prefix: 24` admits no network while admitting almost every address. That is
    the same fold `integer` performs with its `size`-derived range. `SPEC-FEEDBACK.md` #34 asks §5.5 to state
    both halves.
  - **The three temporal families' rules are correct but not yet reachable from schema text**, for a reason
    that predates them and is nothing to do with coherence: `date_type.min`/`max` are declared `value?` in
    meta.tn (the untyped escape hatch), so a bound arrives as a `String` and the bind into `DateType`'s
    `Optional<LocalDate>` throws `ClassCastException` — surfaced as an `UnsupportedOperationException`, exit
    70, "not implemented yet". `!date ^ { min: 2020-01-01 }` and the `!date`-tagged spelling fail
    identically, so **no temporal bound can be written at all today**. `AtomCoherenceTest` reaches these
    families by direct construction; they go live at the resolver the moment the binding is fixed.
- **Two exception types, and which one is deliberate.** `UnsupportedOperationException` means *this library
  hasn't implemented that yet* — the identity-diagonal FIXED-value invariant, a generic type-ref with a
  nested or value (non-simple) argument, a parameterized supertype.
  `TsonSchemaValidationException` means *the schema is wrong*, and the spec says so: a tightening outside
  §5.7's transition table, a refinement body field (or group) that adds rather than tightens, an atom body
  whose own facets admit nothing, a
  modifier-only entry with nothing to elide toward (§5.7), a field name two supertypes both contribute or a
  body/group declares twice (§5.8/§5.11), a group restatement that reorders, retypes, changes membership or
  loosens REQUIRED→OPTIONAL (§5.11), a source or supertype whose body is a binding record and so has no
  vocabulary (§5.7's "finished"), a choice or bracketed form at a supertype position (`&` composes record
  types; §12.1 admits these only because `construction-def` draws its operands from `type-ref` where
  `refined-def` takes a name; §12.1's `supertype-ref` narrows those operands to named references), a name in
  a `!` position resolving in neither
  namespace (the plain typo, §3.3.1), a `!` form aimed at the wrong kind of target — refining a
  constructor, applying a non-constructor, refining a non-atom — each answered with the form the author
  probably meant, and **a body the constructor's own vocabulary rejects** (an unknown member, a wrong-typed
  one), which arrives from the compiled meta reader as a `TsonReadException` and is restated here rather
  than passed on in the reader's currency — the read `Diagnostic` itself is dropped, since it was produced
  against a `DataValueEvents` replay whose positions are all the `(0,0,0)` placeholder and whose `path`
  points into a synthetic body; the declaration's real position comes from `SchemaResolver`'s catch.
  Telling an author their correctly-rejected schema is
  unsupported sends them looking for the wrong fix, and now costs more than clarity: only the validation
  exception is collected into a `Diagnostic`, so a misfiled author error also aborts the run instead of
  joining the other problems. The useful test is that **a schema error's verdict doesn't change when this
  library improves; a gap's does.** The split is worth keeping honest —
  `IllegalStateException` is the third, for an invariant only a malformed `TypeDefinition` could break (a
  `constructor: true` entry with a non-record body, which §12.1's grammar makes unreachable).
  `DefinitionResolver`'s Javadoc lists the exact boundary.
- **`TypeArgument` is a sealed interface (`Ref`/`Value`), NOT a plain record — do not "simplify" it
  back.** `TypeRef`/`TypeArgument` are mutually recursive, and `tson-bind`'s record binder eagerly resolves
  every field descriptor with no cycle protection, so a plain-record `TypeArgument` deadlocks with
  `StackOverflowError` the moment a non-empty `arguments` list is bound. The sealed interface is the one
  shape that binds at all (union binding breaks the loop by member class). Re-read its Javadoc before
  touching it. The cost is a spurious `!ref`/`!value` tag on `toTson` output, documented.
- **`schema.meta` value model:** one Java type per kernel vocabulary record/enum. `Top`/`Atom`/`Product`/
  `Sum` replicate the kernel's composition chain (§4.1) as real Java subtyping — a consumer tests kind
  ancestry with `instanceof Product`. Body leaves are named `RecordBody`/`EnumBody`/etc. (not `Record`,
  which would collide with `java.lang.Record`). Multi-word fields carry `@Field("snake_case")`. **A
  `schema.meta` class used as a bind target with more than one public constructor needs `@Record` on the
  canonical one** or `DefaultRecordBinder` throws (`IntegerType`/`IntegerSize` hit this) — the annotation
  and the fallback both exist for exactly this case.
- **No hand-written writer** — resolved values serialize through `TsonObjectWriter.toTson` directly,
  deliberately, to prove the model is idiomatic Java `tson-bind` already binds. Documented textual
  divergences from the fixture (no outer `!type_definition` tag, quoted strings for enum members, empty
  lists written rather than omitted, full `TypeRef` form) are in `DefinitionResolverTest`'s Javadoc.

## Shared vocabulary: `WireForm`, `MetaRefs`, `DerivedName`

Three dependency-free leaf classes the phases share. Each owns a fact that belongs to none of them
individually, and each was previously stated inside whichever phase happened to need it first — which in two
cases meant it was stated more than once.

**`WireForm`** — how schema vocabulary is spelled as data, in both directions. The vocabulary member names;
the `scoped`/`nameField` builders every producer goes through; `refValue` and its inverse
`typeRefOf`/`argumentOf`; the held-record writers `heldRecord`/`heldEmptyRecord`; and §5.10 `substitute` over
a held body.

- **Why one class and not a producer beside one phase and a consumer beside another.** A held body is written
  by two phases (`SchemaDesugarer` lifting a sugar form, `DefinitionResolver` holding a composition or
  refinement template) and read by four (`TemplateMaterialiser` closing one, `HeldBody` answering §5.10's
  declaration-time questions, `SyntheticMerge` asking whether one holds an application, `ParameterKinds`
  walking one for parameter kinds). `isApplication`'s own contract is that a held body is written by one
  phase and read by several, so a second opinion about what an application looks like is what makes one of
  them wrong. There were three: the writer, the reader, and `ParameterKinds` matching `name`/`arguments`
  against its own string literals — the one that could have drifted silently, since nothing would have
  failed, only a parameter kind quietly not inferred.
- **`TsonObjectWriter` cannot serve any of it.** Its output is canonical-explicit and fully quoted, a
  different language from the one a held body is written in: `TemplateBody.names()` and substitution both key
  on a token being *unquoted*, so a quoted body references no parameters at all.
- **`refValue`'s `arguments().isEmpty()` branch is load-bearing**, not an optimisation — see the
  materialisation section below.
- **Every walk over a held body descends into a map slot, and three of them did not.** meta.tn's
  `scoped.schemas` is `{uri => [type_name; 1..]?; 1..}`, so core's `extern_of => <S> !scoped { scope:
  [EXTERN]  schemas: { S => _ } }` and `extern_type => <S, T> ... { S => [T] }` are the first templates
  putting a parameter inside a map — one in a key, one inside the array its value names. `substitute` left
  the parameter name standing where the argument belonged; `ParameterKinds` never observed the parameter at
  all, so its kind was never inferred and a `type_name` argument stayed on the reference channel and failed
  as an unresolved reference; and `DerivedName`'s canonical rendering — the half §8.2 keys identity on —
  rendered the whole map as the unknown-value mark, so two bindings differing only inside one hashed alike;
  the readable half masked it, which is the `startsWith` hazard inverted and why `DerivedNameTest` asserts
  `canonicalBinding` directly. A map key is a `data-value` and its
  value a `scoped-value` ([TSON-DATA] §2.6), so the two halves rebuild through their own carriers
  (`WireForm.rescope` and `WireForm.retyped`); both halves descend, because a parameter reaches either.

**`MetaRefs`** — the `schema.meta` reference walk, `mapRefs` over a definition and `mapBodyRefs` over a body.
Four callers use it and only one is closing a template: §8.3 flattening rewrites a use site, §8.2's synthetic
merge renames onto a merged entry, and §5.10's regularity check uses it as a *visitor* by returning each
reference unchanged. Which body shape carries which references is a fact about the value model, so it is
stated where the model is walked. Visiting is rewriting with the identity function deliberately: a separate
read-only walk would be a second list of body shapes to keep in step, and the one that fell behind would
silently skip a reference rather than fail.

**`DerivedName`** — §8.2's names and the renderings their hashes run over, in two families that stay apart:

- A **binding record** names a closed form (what a sugar form lifts to, what closing an open synthetic
  produces) and renders with its fields under their own names. An **application** names an instantiation and
  renders positionally. The two reuse the same tag letters in different roles, so merging them would be
  merging two questions that only look alike.
- What must *not* fork is each family's own rendering. `ofBinding` is called by **both** lift channels, and
  that shared call is exactly what makes a form written directly and the same form arriving through a
  materialised template land on one entry.
- The two families' `appendText` were character-identical and their `appendNumberAware` differed only in
  taking a `Token` or a `TokenValue`; they are one method. `MintedNames`' contract depends on the renderings
  agreeing, and a shared decision about identity kept in two places is how they stop agreeing.
- **A hash is not normative, so the conformance layer cannot see one move.** `ResolvedFixtureTest` and the
  `class2/schema/` runner both reduce a synthetic's content hash to a placeholder before comparing (see
  `ResolvedForm`) — right for a comparison against the spec's own fixtures, since §8.2 leaves the spelling to
  the implementation, and it means neither can fail on a rendering change. What guards the renderings instead
  is value-level: `DerivedNameTest` pins both channels, and `SchemaDesugarerTest` pins the binding side end to
  end. The point is not that the values are required but that a change to them is deliberate — an entry name
  is part of the resolved form, and an importing schema derives the same name for the same form.
- **Assert a derived name by value, never by `startsWith`.** The application channel had no value-level guard
  until `DerivedNameTest`: every assertion on an instantiation name checked the readable half
  (`startsWith("box_text_")`), which a change to the hashed rendering passes. Perturbing
  `canonicalApplication` alone left the whole build green.

## Materialisation (`tson-compiler/.../resolver/TemplateMaterialiser.java`)

§5.10's other half: closing a template application by substituting its arguments into the template's
recorded open form, and replacing the application with a reference to the entry that results.

- **An application's arguments are dereferenced before it is closed** (`dereferenced`). A reference is a pure
  rename — §7.2 compares "after reference flattening of both", so `user_id => uuid` makes the two
  interchangeable at every position — which means `box<user_id>` *is* `box<uuid>` and must be one entry.
  Without it the model said the arguments were the same type while the applications were not: interchangeable
  at a scalar position, refused one layer of application up. **Only a reference is dereferenced**; a refinement
  (`!uuid ^ {}`, IS-A `uuid`) and a fresh instance (`!uuid_type {}`, related to neither) are ordinary entries
  and keep their own applications, which is what makes those two spellings mean something. **Identity is
  normalised, not provenance**: the minted `source` becomes the canonical application, and the name the author
  wrote survives at the use site, which states it as written — a division that only became available once
  flattening stopped rewriting use sites. `AliasedArgumentIdentityTest` pins it. The one case this used to get
  wrong — a reference carrying an alphabet directive, which was not a pure rename — cannot arise now: the
  alphabet is `bytes_type`'s own `encoding` selector, so it is part of the type and travels with it
  (`SPEC-FEEDBACK.md` #29).
- **It runs over the resolved form, not the AST**, as a pass in `SchemaResolver` after the driving loop.
  Two reasons. An application arrives here as a `schema.meta.TypeRef` carrying `arguments` — the one thing
  that shape means, since a closed form is always an entry named by a bare reference — so substitution is a
  walk over value types rather than a second AST rewrite. And the entry it mints can record its own
  `source`, which is what §8.2 keys identity on; an injected `SchemaMap.Declaration` has no channel for one,
  which is what rules out doing this in `SchemaDesugarer` alongside the sugar hoists. It also keeps that
  phase purely syntactic.
- **Only a closed entry is scanned.** A template's own body is *open* — `chain<T>` inside `chain` awaits
  substitution and is not an application to close — so entries with a non-empty `parameters` list are
  skipped. Closing them would mint an entry per level, keyed on the literal parameter name.
- **Identity is the flattened application** recorded in `source` (§8.2), and the derived name is built from
  it, so two `box<text>` anywhere in the schema land on one entry for free. A declaration naming the
  application (`text_box => box<text>`) resolves as a `Reference` to that entry rather than a second copy —
  the compiler collapses a `Reference` body at compile time, so nothing downstream sees the hop.
- **Arguments close innermost-first**, so `box<box<text>>` builds the inner entry before the outer one names
  it, and no special case is needed for depth.
- **Substitution descends into arguments.** A parameter is always a whole ref (§5.10 admits no head
  abstraction), but it may be a whole ref *inside an argument list* — which is exactly what recursion looks
  like. Binding only the outer name leaves `T` in place inside `chain<T>` and mints an entry per level.
- **Knot-tying** is the memo, registered before the body is substituted: a recursive application reached
  during substitution finds the entry under construction and references it by name.
- **Non-regular recursion is rejected before this pass runs**, by `TemplateRegularity`, at the declaration
  — see below. What stays here is a depth **backstop**: *regular* recursion ties the knot on its first
  repeat and never nests, so nothing reaching this pass should run away, but if the static check ever has a
  hole the alternative is a `StackOverflowError`, which is neither a diagnosis nor something the exception
  policy can classify.
- **Substitution is where a routed `=` becomes fixed.** §5.7 puts a parametric `= P` in `REQUIRED` at the
  declaration ("nothing is fixed at declaration — the value does not exist yet") and defers the rest to one
  sentence, *fixation happens downstream, where values are concrete*. `bindValue` is that downstream: a bound
  field arriving as `REQUIRED` becomes `REQUIRED_FIXED` with the argument as its value, so
  `response<order, 201>` lands on exactly what the literal `status: int32 = 201` beside it lands on. Carried
  through unchanged instead, the closed entry held the right value on a field that did not enforce it — a
  constraint the author wrote, silently absent from the type it governs, with no diagnostic anywhere because
  nothing was wrong. **The two spellings are told apart by the state they arrive in**, which is the only
  reason this is recoverable at all: §5.7 sends `= P` to `REQUIRED` and `~ P` to `REQUIRED_DEFAULT`, so a
  routed default stays a default and data may still override it. §5.7 names the downstream: fixation happens
  at materialisation, where a field whose routed parameter binds to a concrete argument takes the state its
  literal spelling would have.
- **An application whose head names nothing in scope is left whole, arguments and all.** This pass gives no
  verdict on an unresolvable head — that is the linker's, as an unresolved reference — but it still has to
  hand the linker something faithful, and collapsing the application to its bare head is not that. The one
  slot where the difference showed is `source`, whose lookup falls back to the governing meta's structure
  namespace: a stripped head found a template the schema cannot name and was faulted for supplying no
  arguments, when the author had written them. Keeping the list means the linker judges what was written.
  The fallback's own half of the fix is in `docs/linking-and-compilation.md` — it does not apply to an
  argument-bearing `source` at all, a §5.10 head being resolved in the type-name namespace only (§3.3.1).
- **Kind checking falls out of substitution, for the shapes that still resolve at their declaration.** A value
  argument reaching a type position is the author's error
  — §5.10 infers a parameter's kind from its use, so the body's use and the applied argument are the two
  things being compared. Arity is checked before any of it, against the template's own `parameters`.
  - **A *held* body has no slot types, so the kind rule enforces neither half — two other rules do**, which
    is §5.10's own account: an argument is "read by the position it lands in". A literal applied where the
    body uses the parameter as
    a type is still refused, because the substituted token stands in a type position and nothing declares a
    type called `3` — the verdict arrives as an unresolved reference rather than as a kind error. Its converse,
    a type name applied where the body routes the parameter into a field's *value*, is accepted: `value` is
    §4's escape-hatch atom and takes any token. **What closes that is not the kind rule** but §5.2's own
    dependency — `record_field.value` must be the field's declared type — which catches `int32 ~ text`
    whether a parameter put it there or the author wrote it literally. It is `BACKLOG.md`'s deferred
    FIXED/DEFAULT value validation, and it subsumes this case.
- **Failures report per entry**, through the same receiver resolution uses, so two bad applications in one
  schema are both reported against their own declarations rather than the first aborting the document.
- **Two positions close on demand, during resolution, rather than waiting for the pass.** A composition
  supertype (§5.8) and a refinement source (§5.7) copy the source's *fields*, and a closed application's
  fields live on the entry it denotes — so `DefinitionResolver` closes one itself, through an
  `ApplicationCloser` hook wired to this same instance. Sharing the instance is what makes an on-demand closing and a later batch closing of
  the same application land on one entry. Each entry is *published into the namespace as it is built*,
  because absorbing its fields is the very next thing that happens: an entry visible only in this pass's own
  map would be invisible to the lookup right behind it.
  - **The cycle guard still applies.** Closing resolves the head through `SchemaResolver`'s namespace getter,
    which is also the memo the circular-composition check rides on, so a cycle reached *through* an
    application (`a => b<text> & {}`, `b => <T> a & {}`) is reported as a circular composition rather than
    recursing. Pinned, because it is the one thing this wiring could have broken.
  - **An application still naming the declaration's own parameters absorbs without closing anything.**
    `vip => <T> customer & box<T>` needs no materialisation: the operand's body is *held*, so its field set is
    known while the application is open, and substituting its parameters with the arguments as written — here
    the absorbing declaration's own — yields a held record still carrying them. Read back through the
    `record` constructor, that is an ordinary field set whose types mention a parameter, which is what a
    template's fields are anyway. Inner applications are deliberately left unclosed: they close when the
    absorbing declaration does, one pass later.
    - **The operand contributes its own supertypes and not its own name.** `box` is a template and §5.10
      makes a template no type, so nothing can be IS-A one; `box`'s own `base` is a type and its fields
      arrived, so that edge is real. The cost is one row of the substitutability table: a closed
      `vip<text>` stands where `customer` and `base` are expected but **not** where `box<text>` is, though
      the hand-written `customer & box<text>` does — the application is flattened away here, so nothing
      remains to say "close `box<text>` too, and index against the entry that mints".
      `OpenOperandCompositionTest` pins all three rows, the deliberate no included.
    - **An argument that is itself an application survives whole.** Substitution writes a bound reference
      through `WireForm.refValue` — positionally when it carries no arguments, in `type_ref`'s record
      form when it does — so `box<inner<T>>` keeps `inner<T>` and the absorbing declaration's own
      materialisation closes it. Sharing that producer is the requirement rather than an economy: two
      spellings of one form are two entries for one type, and `WireForm.refValue`'s `arguments().isEmpty()` branch is
      what §5.6's positional spelling turns on.
    - **A parameter cannot be a head.** `<T> { v: T<text> }` is refused at the declaration that writes it
      (`SchemaResolver.refuseHeadAbstraction`, over the held body's own `applications()`), because
      `type_ref.name` is a `type_name` and §5.10 admits no head abstraction. It cannot wait for the linker's
      arity check, which reads the same accessor: materialisation runs first, and by then the parameter is
      gone — leaving either an arity error against a content-derived name nobody typed, or a wire-vocabulary
      mismatch, neither of which names what the author did.
- **A held body writes as the application it holds, and names its carrier nowhere.** `HeldBody` is
  `@Transparent` (`io.ltr8.annotation.Transparent`), so `tson-bind` resolves it to the held `DataValue`'s own
  descriptor with a bridge and `TsonObjectWriter` writes no type-ref for it at a `Top` position: a template's
  body renders `!choice { variants: [T error] }`, not a wrapper naming a type nothing declares. Two things
  make that work and neither is incidental — the writer asks `value instanceof DataValue` *after* unwrapping a
  bridge, so a transparent wrapper over a parsed value still reaches `AstWriter` instead of being written as a
  faithful description of the AST; and `writeUnion` treats a transparent member as contributing no tag.
  - **Which costs the tag a reader would dispatch on**, deliberately. A transparent union member is selectable
    only where a position declares it, never by tag. Nothing depends on that here: an open entry's resolved
    form is its declaration round-tripped, no binder reads one back, and `TypeDefinition.parameters` being
    non-empty already says the body is held.
- **A held body closes by one process, whatever wrote it** (`closeHeld`). `<T> [T]` and `<T> { x: T }` are both
  an application with a parameter standing in a slot — `!array { element_type: T }` and
  `!record { fields: [ { name: x  type: T } ] }` — so both substitute by the same walk and are then bound
  through **their own constructor's compiled reader**, the same one a written `!array { … }` or `!record { … }`
  binds through. Once its parameters go concrete a held body is no longer a template at all, but the
  constructor body those bindings always described, and the entry carries an ordinary
  `ArrayBody`/`MapBody`/`RecordBody`.
  - **What differs between the shapes is only what the result *is*.** A **record** template's closure is the
    instantiation entry itself (`closeHeldRecord`): a substituted record is the type the author named by
    writing the application, so there is nothing for an extra hop to record and the entry carries the
    application in its own `source` the way §8.2 says every instantiation does. Every other held form closes
    to a **synthetic** named for the form, which the instantiation then references — a form has no
    author-written name for identity to key on. That is the whole of the divergence; everything before it is
    shared.
  - **There is no third case, and that is what deletes the old machinery.** Every open entry's body is a
    `HeldBody` or a `Reference` — an *error placeholder* included, which holds an empty record rather than
    staying the one parameterised `RecordBody` left in the system (`WireForm.heldEmptyRecord`). While
    that one shape survived, `TemplateMaterialiser` had to keep a general substitution over *resolved* bodies
    beside the held one — `substitute`/`mapFields`/`bindValue`, ~75 lines — to serve a placeholder with no
    fields to substitute into. Holding it makes `close` total on two branches and the third an
    `IllegalStateException` naming the invariant.
  - **§5.7's fixation happens here** (`fixRoutedValues`), which is what a held record body's retirement of
    the single `value` channel costs and where §5.7 says to pay it: a field routed by `= P` is held as
    `state: REQUIRED`
    with the parameter standing in `value`, and a REQUIRED field carrying a value is that and nothing else —
    a closed REQUIRED field has none, which is what `REQUIRED_FIXED` means. A `~ P` default arrives as
    `REQUIRED_DEFAULT` and stays one: data may still override it.
  - **Substitution is one rule, at every depth.** The body was never read against constructor vocabulary, so
    a parameter in a slot, one inside an application a slot holds (`tree<p0>` becoming `tree<text>`), and one
    inside a collection are the same thing here: a token in a tree, rewritten when its text resolves into the
    entry's `parameters` (§8.1's shadowing rule). Quoting does not enter into it — a token's form is a
    schemaless-data concern ([TSON-DATA] §4.4) — which is why a held body needs no `param`/`value` label
    where a typed open vocabulary did, and why §5.10 can state "substitution is one rule at any depth" with
    collection-valued slots included.
  - **Applications inside it close before the entry is named**, which is what keeps one type on one entry:
    the desugar phase lifts innermost-first, so a form it writes already names the entry its inner form
    became, and a form closed here has to agree or `[[pixel; 3]; 3]` written out and `grid<pixel, 3>` closed
    would be two entries for one type.
  - **The desugar channel cannot always reach that rule, and `SyntheticMerge` is where the two meet.** Both
    channels name a form by one function of one thing — the binding record with every inner form reduced to
    its entry name. Closing here satisfies it always; lifting innermost-first satisfies it for a nested
    *sugar* form and cannot for a nested *application*, `box<text>` having no entry until this pass runs. So
    a form lifted eagerly with an application in a slot is named from an unreduced record, and `[box<text>]`
    written directly would land apart from `[box<T>]` closed with `T := text` — §8.2's own example, and the
    split it calls the merge pass mandatory for. `SchemaResolver` re-derives each such form through
    `TemplateMaterialiser.closedFormName` after materialisation (the moment §8.2 names: "identity settles
    after Pass 2"), rewrites references onto the closed-record name and drops or moves the eager entry.
    **The closed-record name wins**, being a function of the resolved form alone — which is what makes two
    schemas reaching one form by different spellings agree on it, where the eager name or the smaller of the
    two would make an entry's name depend on what appeared beside it. Only a form whose binding held an
    application moves; every other synthetic re-derives to the name it already has.
  - **An argument is classified by the parameter it binds** (`ParameterKinds`, §5.10's "two parameter kinds,
    inferred by use"). §12.1 decides the channel by token shape, so an unquoted non-numeric argument arrives
    as a reference — the right default with nothing else known, and wrong for `e => <M> !enum { members:
    [a b M] }` applied as `e<c>`, where `c` is a member. What settles it is the **declared type of the slot
    the parameter stands in**, read from the constructor's own vocabulary: `type_ref` gives a TYPE parameter,
    a slot resolving to an `Atom` (which covers `identifier`, `value` and every enum) a VALUE parameter.
    §9 makes that general rather than a table of kernel names — a slot holding a type reference MUST be typed
    `type_ref` — so an extension meta-schema's constructors classify by the same walk.
    - **A fixed point, not one walk.** meta-kernel's own `type_argument` puts a parameter of *either* kind on
      the reference channel ("parameters ride the reference channel because a token there is always a
      reference"), so a parameter passed to another template says nothing locally: it takes the callee's kind
      at that position, and two templates may wait on each other. §5.10 anticipates the cycle and makes a
      parameter grounded only by it an error, which `BACKLOG.md` still carries.
    - **Two declaration-time verdicts fall out**, both of which used to be per-application or absent: a
      parameter standing for a whole collection or record (`<T> !enum { members: T }`) is neither a reference
      nor a scalar, and a parameter standing in both kinds of position (`<T> { v: T  w: int32 ~ T }`) has no
      argument that could satisfy both.
    - **An application closed on demand infers its own template.** A composition supertype and a refinement
      source close during resolution's driving loop, before the batch pass can run; the template in hand has
      resolved by then, which is all the walk needs, so only a parameter awaiting the cross-template fixed
      point is left undetermined there.
  - **That is where §8.2's deferred value-level check lands**, and it needs no code of its own:
    `<N> [text; N]` is a fine declaration, `<"two">` is where it stops being one, and the reader reports it
    (`'two' is not a valid integer`) exactly as it would for a written body. D7's split — binding names,
    REQUIRED coverage and concrete typing at the declaration; what substitution supplies, here — is the
    whole of it.
  - **The form is named for itself, not for the application** (§8.2). An open synthetic's own name is
    internal and derived, so keying its instantiations on it would make identity depend on an unstable name
    — and would leave `[text]` written directly and `[T]` closed to `text` on two entries for one type. Both
    go through `DerivedName.ofBinding` over the same binding record, so the two channels dedupe
    against each other and the closing usually finds the entry desugaring already injected.
  - **So the application gets a second entry**, a `Reference` to the form whose `source` is the application
    itself. One entry cannot carry two identities: the closure is a closed synthetic *and* an instantiation
    of the template, and §8.2 keys those on different things. Without it nothing in resolver output records
    that `grid<pixel, 3>` was written — the field would name the array and the template's name would vanish.
    The record shape needs no second entry, since substituting a record yields a record, structurally
    distinct from any synthetic.
    - **A generated head mints none.** Closing `array_p0_…<pixel, 3>` is an open synthetic closing its own
      intermediate form; nobody wrote that application, and an entry named for it would carry an internal
      name into identity. `SchemaResolver` tells the two apart by the plainest fact available — the
      difference between the declarations the author wrote and the ones desugaring added.
  - **Both closure paths share one memo**, so a template that applies itself (`weird => <T> [weird<T>]`) ties
    the knot on the entry under construction. An open instance used to short-circuit ahead of the memo and
    the depth backstop alike, which made that spelling a `StackOverflowError`.
- **An alias holds its body too, and closes by composing rather than minting an entry.** §5.10's *partial
  application* — `uuid_pair => <B> pair<uuid, B>` — is a declaration whose whole body is an application some
  of whose arguments name parameters it re-declares, which makes the alias itself a template. §8.1 says that
  body denotes `!reference { target: pair<uuid, B> }`, and `SchemaDesugarer` writes it there — spellable
  because `reference.target` is a `type_ref`. So it substitutes by the same token walk as every other held
  form; applying it binds the arguments into that inner argument list and closes what results, so
  `uuid_pair<int32>` *is* the entry `pair<uuid, int32>` written directly denotes.
  - **`reference` is the one head materialisation dispatches to a name rather than an entry**
    (`closeHeldAlias`). The first two steps are shared — substitute, then close the application in the slot —
    and what differs is that there is nothing left to build. That is also why `close` tells the three cases
    apart by the constructor head: the body shape no longer distinguishes them, every open entry's being held.
  - **`reference` is not a `~` constructor and its kind is not a base kind**, so `DefinitionResolver`
    dispatches the head instead of judging it by the generic `!C value` rule: §4.1 gives an alias
    `kind: REFERENCE`, which is a `type_kind` with nothing in the supertype chain to supply it, and the
    kernel leaves `reference` unmarked because it describes no value. Both facts are the kernel's own. The
    binding check still runs — `reference`'s vocabulary is a record like any other. §5.10 is explicit that this mints no intermediate entry per
  alias hop, so a chain of aliases collapses and the origin survives only in the composed entry's own
  `source`. The degenerate spelling closes the same way: `ident => <T> T` applied to `text` is `text`.
  - **A self-applying alias is the author's error, not a knot.** The knot-tying memo answers a recursive
    application with the name of the entry under construction, and this path constructs none — so
    `loop => <T> loop<T>`, applied, would hand a field a name nothing ever defines. A second set tracks
    reference-template applications in flight and reports the cycle instead — routing has no entry to tie a
    knot through. Left unapplied, the declaration is caught
    earlier still, by `TypeInhabitance`.
  - **Arity is the alias's own**, checked against its `parameters` before any composition, so
    `uuid_pair<int32, text>` names `uuid_pair`'s one parameter rather than `pair`'s two. An unused one is
    the linker's existing §5.10 check, which reads a `Reference` body like any other.
- **An argument keeps the channel it was applied on.** An argument list is the one position where a type and
  a value are equally at home, so a value parameter passed straight through (`array_p0_…<N>` inside
  `<N> { a: [text; N] }`) stays a value. A parameter anywhere else is a type by construction, and a value
  arriving there is the kind error above.
- **An application in a container position closes here too, and needs no name before it does.** An open
  binding holds a `type_ref` whole, arguments intact, so `[tree<T>; 1..]` inside `tree` lifts to a synthetic
  whose own `element_type` is `tree<p0>`. Closing runs in three steps and the order is the whole of it:
  replace the `param` bindings, bind the parameters *inside* an application a binding holds, then close what
  results. Closing first would close `tree<p0>` — an application of an argument nothing supplied.
  - **That is what ties §8's `tree` knot.** Closing `tree<text>` reaches the synthetic, whose binding reaches
    `tree<text>` again and finds it in `closing` — so the synthetic's `element_type` names the instantiation
    entry, recorded before that entry completes. The array is reached *through* the record and names it back.
  - **An application in a binding closes at the declaration when it can.** One naming none of the template's
    own parameters (`<N> !array { element_type: box<text>  min_items: N }`) is fully bound already, so
    `DefinitionResolver` closes it on the spot, the same treatment a composition supertype gets. One naming a
    parameter is carried open — closing it there recurses through the very entry being resolved, which is the
    shape `tree` takes.
  - **A closed container position takes one too** (`[box<text>]`). Its slot is written in `type_ref`'s record
    form, so the entry the desugar phase injects names something that is not an entry yet — and the batch pass
    here closes it, the same walk that closes every other ref. Nested arguments need no separate handling,
    since `close()` already builds `pair<int32>` before `box<pair<int32>>` names it. What made the wire hop
    possible was `type_argument` becoming readable, value channel included
    (`docs/linking-and-compilation.md`).

## References are hops, not rewrites (`tson-compiler/.../TsonSchemaCompiler.java`)

§8.3's use-site flattening **is gone, and `@alias` with it**. Resolved output states the chain the author
wrote: a type position naming a `REFERENCE` entry keeps that name, nothing is attached to record where it
"really" points, and the chain stays walkable through the entries themselves.

**A processor collapses the chain when it compiles readers** — after linking, once per entry, where the whole
namespace is present. `TsonSchemaCompiler`'s reference branch is that moment: a `REFERENCE` entry's reader
*is* its target's reader, resolved recursively, named for the entry doing the referring, so a use site naming
`pct` over `pct => small` reads and reports as `pct`.

- **The walk was never avoidable, which is why the rewrite was not worth its price.** §8.3 itself required
  the chain stay walkable (`reference.target` was never flattened), and several passes walk one. Rewriting the
  output as well left two representations to keep in step, and `@alias` was a *lossy* summary of the one it
  duplicated — it kept only the source-site name, so in `digest_chain => digest_alias => bytes` it recorded the
  hop that carried nothing.
- **`ReferenceChain` is that walk, stated once** (`resolver/ReferenceChain.java`). The linker's choice-variant
  distinctness and its §5.2 field-value check, `Subsumption`'s subtype naming and `DiscriminationClass`'s
  classification each had their own loop, and the one decision inside — *stop at a non-reference, at an
  **argument-bearing** target (an application, with no entry until materialisation mints one), or on a cycle*
  — was four decisions that could drift. `terminal` answers with a name, `terminalDefinition` with the entry;
  they differ only on an undeclared name and a cycle, where the first has an answer its caller wants (a type
  parameter is its own terminal) and the second has none. **`ParameterKinds` keeps its own loop deliberately**:
  it follows a chain to a slot's declared body and must *not* stop at an argument-bearing target, the template
  being the answer there. `ReferenceChainWalkTest` pins all four stops.
- **Anything that needs the chain end walks it and says so.** `TsonSchemaLinker.checkFieldValue` walks to the
  terminal before checking a `~`/`=` value, since a field typed by an alias states a value of whatever the
  alias names; `FieldValueConformanceTest` pins both directions.
- **The bootstrap route needs no special case any more.** It used to have to flatten identically or diverge
  from ordinary resolution, while binding no name-position annotations of its own — a divergence waiting to
  matter. Neither route rewrites anything now (`BootstrapReferencesTest`).
- Pinned by `ReferenceChainTest` — the chain stated as written, a read still reaching the end of it, and a
  diagnostic naming the hop the author wrote — and end to end by `ResolvedFixtureTest` against the spec's own
  `spec/m/*-resolved.tn`.

## The `@synthetic` marker (`tson-compiler/.../resolver/SchemaResolver.java`)

§8.2 puts a bare **`@synthetic` on the key of every entry the resolver materialised from a sugar form**, and
on no other. It is *derived* — attached by the resolver rather than written by an author, and discarded and
recomputed on ingest (§8.1), so it carries no decode force and cannot be forged into a resolved document to
change how it reads. It is built by name rather than resolved through the governing
meta the way an author-written annotation is: there is no author to resolve against, and the value is fixed.

- **Why a marker at all, when the names are distinctive.** A synthetic is named by derivation from its own
  content, but §8.2 makes that spelling non-normative — an implementation picks its own — so pattern-matching
  the name is not a way to recognise one. Without the marker a consumer of resolver output cannot tell a
  materialised entry from a declared one, which is what folding these entries back into the nested form the
  author wrote depends on.
- **Key position, per §6.** An annotation before a declared name is metadata *about the declaration*, and a
  resolved schema is a `{type_name => type_definition}` — so the marker lands on the map's key, which
  `TsonSchema.entries()` keeps reachable through `AnnotatedMap.getAnnotations(name)`, never on the
  `TypeDefinition` value. §6 forbids hoisting between the two positions and nothing here does.
- **Marked: exactly the synthetic entries, from both channels.** The desugar lift produces them and
  `SchemaDesugarer.lifted` names them as the document's own set difference
  (`docs/schema-grammar-and-desugaring.md`); materialisation produces more of the same kind when it closes an
  open synthetic, and `TemplateMaterialiser.syntheticNames()` reports which of its minted entries those are.
  A declaration's own sugar body is **not** one: `tag_list => [text; 1..2]` *is* the construction, not a lift
  of one (§5.3).
- **Unmarked: instantiation entries.** §8.2 draws that line itself — "the two families are distinguishable
  (an instantiation's `source` is an application; a synthetic's is a bare constructor), and only synthetics
  are the fold-back-into-display case the marker serves." Marking too much would be as wrong as marking too
  little, so `SyntheticEntryMarkerTest` asserts both halves.
- **It survives linking and the import merge**, which is where a key-side fact is easiest to lose: the linker
  rebuilds its entry map several times and re-attaches key annotations at the one point every entry passes
  through (`withNameAnnotations`), imports included, so an imported synthetic keeps the marker its own schema
  gave it.
- **The bootstrap route attaches none, deliberately.** `MetaKernelBootstrapResolver` exists to be *just*
  enough to load the real meta-kernel from its own file, and nothing in the pipeline reads this marker — it is
  informational. meta-kernel's own nine synthetics are marked anyway, because the entries anything else sees come from
  ordinary resolution: the bootstrap output stands in only as the transient governing meta for its own
  resolution.
- Cross-checked against the spec's own output by
  `ResolvedFixtureTest.theSameEntriesAreMarkedSyntheticOnBothSides` — nine keys in meta-kernel, one in
  meta.tn, none in core.tn, which writes no inline form. It reads the fixtures' marked keys from their
  *text*, because a key-position annotation is still dropped when a resolved-form document is read back
  (`BACKLOG.md`), and a bound comparison would have both sides render nothing and agree for the wrong reason.

## Template regularity (`tson-compiler/.../resolver/TemplateRegularity.java`)

§5.10's regularity boundary, checked over the resolved entries before anything materialises: **within a
template body, a recursive application — direct or mutual — must pass each parameter through unchanged.**

- **Why it is a static rule and not a runtime limit.** A template that grows its argument every level
  (`weird => <T> { next: weird<box<T>>? }`) reaches `weird<box<text>>`, then `weird<box<box<text>>>`, …, so
  every instantiation is distinct, dedup-by-identity never fires, and there is no finite set of types to
  build. Caught only while materialising it costs a depth counter — a non-portable limit, and the same
  retrofit C++ reached for after shipping templates without a regularity restriction. Caught here it is an
  ordinary schema error at the line that wrote it.
- **A template nobody applies is still rejected.** That is the difference the move buys: `weird` used to
  compile clean and fail at the first user's application, which is the pre-concepts C++ error-quality
  failure in miniature.
- **Mutual recursion needs reachability, not a self-edge** — neither template in an `a → b → a` cycle
  applies itself, so an application is checked whenever its head can reach the declaration it sits in.
- **Applications nested inside arguments are checked too** (`box<deep<box<T>>>`), so the walk recurses
  through `TypeRef.arguments`. The body walk itself is `MetaRefs.mapBodyRefs` used as a visitor,
  so one place knows the shape of a body.
- **Deliberately stricter than termination requires.** The condition that actually bounds the work is weaker
  — every argument a bare parameter reference, at any position — because arguments are only ever copied,
  never constructed, so permuting (`swap => <A, B> { x: swap<B, A> }`) or duplicating still reaches finitely
  many instantiations. Positional identity is what the cited precedent uses (ML restricts polymorphic
  recursion the same way), and an over-restriction that is simple to state can be loosened later where the
  reverse cannot. `TemplateRegularityTest.permutingParametersIsRejectedThoughItWouldTerminate` pins the
  choice so it stays visible.
- **Arity is not checked here.** A never-applied template's arity is still unverified — its own gap — so the
  comparison runs only where the arity already matches.
- **A condemned template does not reach materialisation.** `check` returns the names it rejected and
  `SchemaResolver` replaces each with the same placeholder a failed declaration leaves, in both the entry map
  and the namespace — `materialise` walks the first, an application's head resolves through the second.
  Without that, an application of a condemned template ran to `MAX_CLOSING_DEPTH` and reported the defect a
  second time, against the entry that applied it and with a 64-link chain of synthetic names attached. The
  depth guard stays: it exists for a hole in this check, not for a template this check has already caught,
  and the alternative failure it prevents is a `StackOverflowError`.

## Meta-kernel bootstrap (`tson-compiler/.../resolver/MetaKernelBootstrapResolver.java`)

Meta-kernel is special: its own `!!meta` names *itself* (§1.5's one deliberate circularity, closed by
pre-loading, not resolution). Ordinary resolution can't bootstrap it — resolving a constructor application
needs that constructor's vocabulary already known, and every constructor meta-kernel uses is defined
within meta-kernel.

`MetaKernelBootstrapResolver.getMetaKernelSchema()` (its only public method) produces the resolved
meta-kernel `TsonSchema` in **two passes** over its declarations: non-`Instance` declarations first
(ordinary `DefinitionResolver`), then the deferred `Instance` declarations (`value => !unit {}`, `boolean
=> !enum [true false]`, …) once every constructor they reference — including ones declared later in the
file — has an entry to transfer a kind from. `TsonSchemaResolver` alone is single-pass, strict source
order, so it can't handle `boolean` preceding `enum`; this two-pass ordering lives here.

- **Constructor-application binding goes through a closed `instanceBody` switch, not the generic path.**
  Meta-kernel instantiates constructors in exactly three shapes — a bare `{}` (each target's
  `UNCONSTRAINED` constant), a bare token array (`enum`, via `toEnumBody` reading `TokenValue.text()`
  directly), and the binding record `SchemaDesugarer` emits for an `array`/`set`/`map` application. No
  compiled reader is involved.
- **Why even a compiled-reader bootstrap can't read meta-kernel from its own in-progress state:**
  `integer_size => { bits: ... signed: boolean }` is a first-pass entry whose `signed` field already
  references `boolean`, which the *second* pass resolves — so there is no moment at which a reader could be
  compiled against a complete schema. Given how narrow and fixed meta-kernel's instance shapes are,
  hand-picking them is simplest — "the bootstrap can do whatever tricks it needs, including not compiling,
  just calling `new Xxx(...)`."
- **Desugaring needs no equivalent trick, and used to.** The phase once read a constructor's `parameters()`
  and its fields' parameter routing off the governing meta; for meta-kernel those would have had to come
  from the entries this class is in the middle of producing, so the routing for the three constructors it
  applies to itself was written out by hand. With the container constructors parameterless the desugar table
  is fixed by the sugar forms and nothing is looked up, so the bootstrap special case and the general case
  are one mechanism. The payoff is unchanged: meta-kernel's linked form needs no materialization either —
  its eight sugar forms are ordinary declarations by the time the linker sees them.
