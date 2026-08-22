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
by entry identity, exactly as at link time (`SPEC-FEEDBACK.md` #55, and `docs/linking-and-compilation.md`
for the rule in full) — this is the same concept discovered one phase earlier, so the two implementations
are kept in step deliberately.

- **Three namespace dependencies are constructor-fixed, via functional interfaces**, not threaded per
  call: `DefinitionGetter getTypeDefinition(name)` (the accumulating type-name namespace — typically a
  method reference onto a caller's growing map), `DefinitionGetter metaDefinitions` (the structure
  namespace — the governing meta's entries, consulted *only* for a constructor-application target per
  §3.3.1), and `DefinitionMetaReader read(type, value)` (binds a constructor-application/atom-refinement
  value through the governing meta's compiled reader). A resolver with nothing to offer supplies always-
  throwing / always-null constants.
- **What resolves:** record construction; composition (`A & B & { ... }`, §5.8, with kind from the literal
  base-kind names in the transitive supertype chain, and tightening in the trailing body per §5.7); the
  `^` refinement operator (§5.7, copies the source's whole field set, admits no new fields); bare
  references (§8.3); constructor application (`!C value`, §5.5, binds generically via the compiled
  reader — no hand-rolled name→class table, `tson-bind`'s sealed-union resolution finds the `Top` leaf by
  `@Typename`); atom refinement (`!I ^ { ... }`, §5.5/§5.7); subtraction (`A & { ... } - { f }`, §5.9);
  restating a field group in a refinement or composition body (§5.11 — same member labels in the same order,
  types verbatim, state tightening OPTIONAL→REQUIRED only; only the *group's* state moves, since members
  flatten as `OPTIONAL` regardless).
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
  - { f }` with `f` from `A` drops IS-A with `B` too, though `B`'s fields all survive. That is §5.9's letter
  against §4.3's "composition grants IS-A per parent"; `SPEC-FEEDBACK.md` #37 has the per-ancestor
  alternative, and the workaround (subtract first, compose second) that makes an author's intent explicit.
  Groups follow §5.11: a removed member leaves `members`, a group down to one member is
  dissolved into a plain field taking the *group's* state (members flatten as `OPTIONAL` whatever the group
  says, so the survivor would otherwise silently lose a REQUIRED group's "exactly one"), and a group with no
  members left is dropped — the one arity §5.11 doesn't legislate (`SPEC-FEEDBACK.md` #36).
- **Chained atom refinement merges with the source, it does not replace it** (`SPEC-FEEDBACK.md` #17):
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
  `tson-schema` has no `tson-regex` dependency), `duration_type`'s text bounds, and **selector** facets
  (`component`/`format`/`encoding`/`version`) — core.tn's own prose calls a selector swap a narrowing, so
  rejecting one would reject a documented construct (`SPEC-FEEDBACK.md` #27).
- **An atom body must also be coherent with itself**, which is the other question about the same facets and
  needs no source to compare against. `checkCoherent` asks `Atom.coherenceCheck()` — one rule per family over
  the shared `AtomCoherence` mechanics, the `AtomNarrowing` twin — and throws `TsonSchemaValidationException`
  when a body's own facets admit nothing (`{ min_length: 10 max_length: 3 }`, `{ min: 10 max: 3 }`,
  `{ min_prefix: 40 max_prefix: 8 }`). §7.2 puts the rule and its home in one sentence — "family coherence
  between bindings (e.g. `min ≤ max`) is a **compilation** and ingest concern (§8), **not data validation**"
  — which is also why it cannot live in the atom parsers. Running it at *resolution* rather than compilation
  is deliberate and strictly earlier: the bound constraint objects first exist here, and both are schema-load
  time. meta.tn's own header `@doc` states the same obligation from the other side: bounds are field
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
    lexically, and judging them as strings would call a coherent body empty), `pattern` emptiness, selector
    facets, and CIDR `within`/`excluding` overlap (containment arithmetic this family has no parser for).
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
  `refined-def` takes a name — `SPEC-FEEDBACK.md` #38), a name in a `!` position resolving in neither
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

## Materialisation (`tson-compiler/.../resolver/TemplateMaterialiser.java`)

§5.10's other half: closing a template application by substituting its arguments into the template's
recorded open form, and replacing the application with a reference to the entry that results.

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
- **Kind checking falls out of substitution.** A value argument reaching a type position, or a type argument
  reaching a `value_param` route, is the author's error — §5.10 infers a parameter's kind from its use, so
  the body's use and the applied argument are the two things being compared. Arity is checked before any of
  it, against the template's own `parameters`.
- **Failures report per entry**, through the same receiver resolution uses, so two bad applications in one
  schema are both reported against their own declarations rather than the first aborting the document.
- **Two positions close on demand, during resolution, rather than waiting for the pass.** A composition
  supertype (§5.8) and a refinement source (§5.7) copy the source's *fields*, and an open application has no
  field set to copy — so `DefinitionResolver` closes one itself, through an `ApplicationCloser` hook wired to
  this same instance. Sharing the instance is what makes an on-demand closing and a later batch closing of
  the same application land on one entry. Each entry is *published into the namespace as it is built*,
  because absorbing its fields is the very next thing that happens: an entry visible only in this pass's own
  map would be invisible to the lookup right behind it.
  - **The cycle guard still applies.** Closing resolves the head through `SchemaResolver`'s namespace getter,
    which is also the memo the circular-composition check rides on, so a cycle reached *through* an
    application (`a => b<text> & {}`, `b => <T> a & {}`) is reported as a circular composition rather than
    recursing. Pinned, because it is the one thing this wiring could have broken.
  - **An application still naming the declaration's own parameters is refused** (`vip => <T> box<T> & { … }`)
    — it cannot close until `vip` itself materialises, and deferring composition that far is a different
    feature. Before this, refinement did not refuse: it copied the template's body with parameters unbound
    and reported an unresolved reference to a parameter the author never wrote.
- **An open *instance* closes on a second path, and produces an ordinary body.** A template whose body is an
  `instance_template` — what a sugar form over a parameter lifts to — is not substituted-and-kept like a
  record template: once its bindings go concrete it is no longer a template at all, but the constructor body
  those bindings always described. So it is bound through **that constructor's own compiled reader**, the
  same one a written `!array { … }` binds through, and the entry carries an ordinary `ArrayBody`/`MapBody`.
  - **That is where §8.2's deferred value-level check lands**, and it needs no code of its own:
    `<N> [text; N]` is a fine declaration, `<"two">` is where it stops being one, and the reader reports it
    (`'two' is not a valid integer`) exactly as it would for a written body. D7's split — binding names,
    REQUIRED coverage and concrete typing at the declaration; what substitution supplies, here — is the
    whole of it.
  - **The form is named for itself, not for the application** (§8.2). An open synthetic's own name is
    internal and derived, so keying its instantiations on it would make identity depend on an unstable name
    — and would leave `[text]` written directly and `[T]` closed to `text` on two entries for one type. Both
    go through `SchemaDesugarer.internalName` over the same binding record, so the two channels dedupe
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
  through `TypeRef.arguments`. The body walk itself is `TemplateMaterialiser.mapBodyRefs` used as a visitor,
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
  and its fields' `value_param` routing off the governing meta; for meta-kernel those would have had to come
  from the entries this class is in the middle of producing, so the routing for the three constructors it
  applies to itself was written out by hand. With the container constructors parameterless the desugar table
  is fixed by the sugar forms and nothing is looked up, so the bootstrap special case and the general case
  are one mechanism. The payoff is unchanged: meta-kernel's linked form needs no materialization either —
  its eight sugar forms are ordinary declarations by the time the linker sees them.
