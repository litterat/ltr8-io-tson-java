# Held template bodies

What an open entry's body is, and how each shape of it closes. A **record template** is a `record` with `parameters`
(`schema.meta.RecordBody`, a family base); every other open entry is a **held body**, text carried by the kernel's
`template` constructor (`schema.meta.TemplateBody`). Both are read through one held tree (`HeldBody`), which is what
substitution rewrites and the declaration-time checks inspect. This note also owns §5.10's parameter types
(`ParameterTypes`) and §8.2's synthetic merge as it meets closing. Current form only; history lives in git.

**Invariants**

- Whether an entry is open, what it binds and whether it is a family base are `schema.meta.OpenEntry`'s questions, and
  its held tree is `HeldBody.of(Top)`'s answer. Code that tests `TemplateBody` directly asks only "held text, not a
  family base" (`NameType`, `KindMembers` follow a constructor template to the head its body applies).
- An open record is never held text: `HeldBody.held` refuses a `record` head with `IllegalStateException`, and
  `HeldBody.of` writes an open record's tree through `WireForm.heldRecord`. `HeldBody.of` on a closed entry, and a held
  text that does not parse back, are `IllegalStateException`s too.
- `HeldBody.held` is the only way a `TemplateBody` is minted, and it gives every parameter the provisional
  `type_kind` (`TemplateParam.typeParameter`); `ParameterTypes` stamps the derived types later.
- A held tree is a working value: built per call, never cached or stored on the entry. Callers outside `resolver`
  ask it `names()` and `applications()` only, never for the `DataValue`.
- Substitution is one rule at every depth (`WireForm.substitute`): an **unquoted** token whose text is one of the
  entry's parameters is rewritten (§5.10, with §8.1's shadowing rule); a quoted token is a literal. `HeldBody.names()`
  returns exactly the tokens substitution would rewrite.
- A declared field name that is no identifier (`"@id"`, `"first name"`) is written quoted (`WireForm.declaredName`),
  or the text does not parse back; quoting never hides a parameter, whose name is an identifier.
- Closing dispatches on the held tree's constructor head: `reference` closes to a name, `record` to a record,
  anything else to a synthetic construction.
- Every parameter is substituted, inside nested applications included, before any application in the body closes.
- A parameter is a TYPE parameter exactly when its recorded type is `type_kind` (§5.10); a slot typed `type_kind` or
  `type_ref` gives one, an `Atom`-typed slot gives a VALUE parameter, and anything else is refused at the declaration.
- Parameter types are derived twice: before materialisation (feeding the call-site check) and after everything has
  closed (stamping minted open entries). The second pass reports nothing.

Related: `design/template-materialisation.md` (the closing pass: identity, declared applications, the expansion
rule, on-demand closing, kind, regularity), `design/desugaring-open-forms-and-templates.md` (how sugar and aliases are
normalised into held bodies, open and closed lifts, value-argument identity), `design/resolver-vocabulary-and-bootstrap.md`
(`WireForm`, `DerivedName`, `@synthetic`), `design/schema-resolution.md` (`parameterised`, `openRecord`, marks on an
open record), `design/constructor-application.md`, `design/linking-and-compilation.md`.

## The two shapes of an open entry

**Why the line falls between a record and everything else.** Every language with parameterised types draws the same
line: an *abbreviation* (a C++ alias template, a Haskell `type` synonym, an ML type abbreviation) is a λ over types or
values whose application is substitution and nothing more, so its open form denotes nothing and is not a type; a
*family* (a class template, a `data` declaration, a Scala `sealed trait`) has an open form that is a type, which its
applications are IS-A. TSON's `template` is the abbreviation and a parameterised record is the family, and only a
record can be one, because three things must survive abstracting over the parameters and all three are record
properties: a residual shape (the fields that mention no parameter are the interface the base exposes), somewhere for
ABSTRACT to live, and somewhere for the discriminators member dispatch reads. The near misses confirm it: `<N>
!integer ^ { max: N }` collapses to `integer` once the facet holding `N` is abstracted away (its applications' IS-A
edge comes from the `^`, not the parameter list); `<T> [T]` would be `List<?>`, but containers have no IS-A and
`vector<float32, 3>` and `vector<float32, 4>` erase to one host type; a choice body mints no edge to its variants. A
bare base at a position (`r: result`) reads as the existential, "some `result<X>`", well-defined because no value is
ever read against the base: a value there is a member's, selected by its tag or its discriminators, and every member
is closed (§5.10). Neither kind has variance, since construction transfers kind and not IS-A: `box<cat>` is not IS-A
`box<animal>`, `bounded<100>` not IS-A `bounded<255>`. So the kind follows the body, with no key-side spelling
(`box<T> => …`), and §8.2's identities reduce to four facts: a declaration creates identity, `^` and `&` create IS-A
edges in every application, a parameter list creates the one further edge (declared applications of a record IS-A
its base), and an application nothing declares is its expansion.

- **A record template is the record it is.** Every slot a record template admits a parameter in already holds a name
  or an uninterpreted token — a field's `type` (a `type_ref` while open, §4.2), its `value`, an application's
  arguments — so the open record is a value of the vocabulary as it stands: a `RecordBody` with `parameters`, its
  chain keeping the parents as written. `DefinitionResolver.openRecord` binds the desugared `!record { … }` payload
  through `record`'s own reader once `ParameterTypes.checkSlots` has confined its parameters; a composition or
  refinement template is the *flattened* record resolution builds, with its parameters stated
  (`DefinitionResolver.parameterised`), because both absorb fields from a source. Either way a selector typed by a
  parameter is refused (`refuseParameterTypedSelector`). An error placeholder for an open declaration is an open
  empty record (`SchemaResolver.unresolved`), so it closes like any record template.
- **Every other open entry is held text.** `set` resolves to
  `!template { parameters: [{ name: T  type: type_kind }]  template: "!set_type { element_type: T }" }`. That covers
  an open instance (`<T, N> !array { … }`), an open sugar form (`<T> [T]`, `<T> ( T | error )`), and §5.10's partial
  application, `uuid_pair => <B> pair<uuid, B>`, held as the `!reference { target: pair<uuid, B> }` §8.1 says it
  denotes — spellable because `reference.target` is a `type_ref`. Its kind is `TEMPLATE` whatever its head
  (`design/template-materialisation.md`).
  - **Why text, not a value of the constructor's vocabulary.** A parameter stands wherever a token stands —
    `element_type: T` in a type slot, `min_items: N` in a value slot, `variants: [T error]` inside a collection — so a
    body carrying one is typed by no constructor's record shape until it closes. Read as one, `min_items: N` fails
    against `non_negative_integer`, and `!enum { members: [a b M] }` reads *cleanly*, binding `M` as a third member
    spelled `M`. `OpenEntryResolvedFormTest` pins that it reads back as held text.
  - **The tree is built, not held.** `HeldBody.held` writes the tree it is given (`DataClassObjectWriter`,
    vocabulary-free, token forms preserved) and keeps none of it; `HeldBody.of` parses the text back. Handing back the
    tree in hand would hide a writer/parser disagreement about §5.10's one spelling, which instead fails in
    `HeldBodyTest`. The AST is `tson-compiler`'s type, and `tson-schema` depends on nothing here, so an entry could not
    carry one without inverting the module direction.
  - **Comparison needs no channel of its own.** §5.10 compares a held body as its parsed form, so `ResolvedForm` reduces
    one to its parsed application and an open entry compares like every other entry.
- **An open record's tree is written, not parsed** (`WireForm.heldRecord`): parents as written, fields, groups,
  selectors, a non-default `name_type`, and `extension` where it is not OPEN, so closing reads one tree shape whichever
  kind of open entry it came from. A field annotation's bound value is the one leaf `WireForm` cannot unbind itself;
  `HeldBody.annotationValue` supplies it.

## How a held tree closes (`TemplateMaterialiser.close`)

The tree is parsed once per closing and dropped with it. The head decides what the result *is*; everything before
that is shared — substitute (`WireForm.substitute`), then close the applications the substituted body holds.

- **Anything but `record` or `reference`** (`closeHeldTemplate`, via `closeHeld`): the substituted wire is bound
  through **the constructor's own compiled reader**, the one a written `!array { … }` binds through, so the result is
  an ordinary `ArrayBody`/`MapBody`/`ChoiceBody`/… named for its binding record (`DerivedName.ofBinding`). A failure
  there is §8.2's deferred value-level check, with no code of its own, for whatever the call-site check (below) could
  not judge: the reader reports it exactly as it would for a written body.
- **`record`** (`closeHeldRecord`): the same `closeHeld`, read through `record`'s reader, then taken as a member
  (`asMember`: no `discriminators`, its pins being in place) with its chain completed from the parents as written
  (`Ancestry.chainOf`). Applications in the chain's `parents` are left as written
  (`closeApplicationsOutsideSupertypes`): an operand denotes no entry (§8.2).
- **`reference`** (`closeHeldAlias`): an alias composes rather than mints. Applying it binds the arguments into the
  target's argument list and closes what results, so `uuid_pair<int32>` *is* the entry `pair<uuid, int32>` denotes,
  and the alias's `target` must close to a bare name or it is an `IllegalStateException`. §5.10 mints no intermediate
  entry per alias hop, so a chain of aliases collapses and the origin survives only in the composed entry's
  `@application`. `ident => <T> T` applied to `text` is `text`.
  - **Arity is the alias's own**, checked against its `parameters` before composing, so `uuid_pair<int32, text>` names
    `uuid_pair`'s one parameter rather than `pair`'s two.
  - **A self-applying alias is a cycle, not a knot.** The knot-tying memo answers a recursive application with the
    entry under construction, and this path constructs none, so `loop => <T> loop<T>` would hand a field a name
    nothing defines. `aliasClosing` tracks reference-template applications in flight and reports the cycle (`reference
    template whose own body applies it again`) at the declaration that applied it. Unapplied, the declaration
    compiles: a template nobody applies receives no verdict (§5.10), and `TypeInhabitance` treats every open entry as
    inhabited.
- **Every shape goes through one memo and the depth backstop**, so `weird => <T> [weird<T>]` ties the knot on the
  entry under construction rather than overflowing the stack. What the closure is then *named* — the expansion
  (nothing minted for the application), the declared name, or an instantiation entry for an expansion that names its
  own application — is `design/template-materialisation.md`'s.
- **A generated head mints no instantiation.** Closing `array_p0_…<pixel, 3>` is an open synthetic closing its own
  intermediate form; nobody wrote that application. `SchemaResolver` tells generated heads from authored ones by the
  names desugaring added (`SchemaDesugarer.lifted`).

### Applications inside a held body

- **They close before the entry is named**, which keeps one type on one entry: desugar lifts innermost-first, so a
  form it writes already names the entry its inner form became, and a form closed here must agree, or
  `[[pixel; 3]; 3]` written out and `grid<pixel, 3>` closed would be two entries for one type (§5.10).
- **A container position holding an application needs no name before it closes.** An open binding holds the
  `type_ref` whole, arguments intact, so `[tree<T>; 1..]` inside `tree` lifts to a synthetic whose `element_type` is
  `tree<p0>`. Substitution rewrites `p0` inside that application before anything closes; closing first would close
  `tree<p0>`, an application of an argument nothing supplied. That is what ties §8's `tree` knot: closing
  `tree<text>` reaches the synthetic, whose binding reaches `tree<text>` again and finds it in `closing`, so the
  array names the instantiation entry recorded before that entry completes.
- **An application in a held body closes when the body does**, whether or not it names a parameter: the held text is
  not scanned at the declaration. Only the two field-absorbing positions, a composition operand and a refinement
  source, close during resolution (`DefinitionResolver.closedApplication`; `design/template-materialisation.md`).
- **A closed container position takes one too** (`[box<text>]`): its slot is written in `type_ref`'s record form, and
  the batch pass closes it like every other ref; `close()` builds `pair<int32>` before `box<pair<int32>>` names it.
  The wire hop rests on `type_argument`, an untagged labelled choice, being readable (`design/class2-compilation.md`).
- **The synthetic merge is where the two lift channels meet** (§8.2; `SyntheticMerge`, whose Javadoc carries the
  rationale). A form the desugar phase lifted *closed* with an application in a slot was named from an unreduced
  record, so `[box<text>]` written directly lands apart from `[box<T>]` closed with `T := text`. After
  materialisation `SchemaResolver` re-derives each such form through `TemplateMaterialiser.closedFormName`, rewrites
  references onto that name, and drops the eager entry where the other channel already published the form or moves
  it where it did not. **The closed-record name wins**, being a function of the resolved form alone; only a form whose
  binding held an application moves.
- **An argument keeps the channel it was applied on.** An argument list is the one position where a type and a value
  are equally at home, so a value parameter passed straight through (`array_p0_…<N>` inside `<N> { a: [text; N] }`)
  stays a value: substitution moves a literal bound at a `type_ref`'s `name` to its `value` member
  (`WireForm.substituteField`). What a held body admits beyond that — collection-valued slots, value arguments and
  their numeric identity — is `design/desugaring-open-forms-and-templates.md`'s.

## Parameter types (`ParameterTypes`)

Each parameter records the type an argument for it is read as (`template_param.type`, §5.10), derived from the
positions it stands in; the kind follows from it. §12.1 decides an argument's channel by token shape, so an unquoted
non-numeric argument arrives as a reference — wrong for `e => <M> !enum { members: [a b M] }` applied as `e<c>`, where
`c` is a member. What settles it is the declared type of the slot the parameter stands in, read from the constructor's
own vocabulary: `array.element_type` is `type_kind`, `enum.members` is `enum_set` (of `text`), `record_field.value` is
`value`. §4.2 types every slot that names a type `type_kind` and every application position `type_ref`, so an
extension meta-schema's constructors classify by the same walk. The class Javadoc lists the four sources of a use type.
Beyond those:

- **Stamped twice.** A held body is built before anything can be typed. `SchemaResolver` runs
  `ParameterTypes.inferAll` once every declaration has resolved and nothing has closed, stamping the declared
  templates and feeding `TemplateMaterialiser.parameterKinds`; and again after materialisation and the merge, over
  every local open entry, declared and minted, with a reporter that discards — its failures are the first pass's,
  already reported.
- **A fixed point, not one walk.** meta-kernel's `type_argument` puts a parameter of either kind on the reference
  channel, so a parameter passed to another template says nothing locally: it takes the callee's recorded type at that
  position, and two templates may wait on each other. A parameter the fixed point leaves ungrounded is a TYPE
  parameter (`type_kind`), as §5.10 states, so `loop => <T> loop<T>` is judged on what is wrong with it — it applies
  itself forever. An imported template is taken as recorded, since its held body does not carry what its author wrote.
- **Several uses must agree**: the type is the use type that IS-A every other, uses unordered by IS-A are refused at
  the declaration, and a parameter standing in both kinds of position keeps its own message. A core copy and its
  kernel original (same name, same body) count as one type. A parameter standing for a whole collection or record
  (`<T> !enum { members: T }`) is refused as neither a kind of type nor a scalar.
- **A routed default or fixed value** (a parameter at `record_field.value`) is read as the field's declared type, from
  the sibling `type` slot: `w?: T ~ N` gives `N` the type `T`, an earlier parameter. A payload in §5.6's positional form
  (`!enum [a b M]`) is walked as the one unmarked field it binds.
- **The template's form decides where value types are read** (`ParameterTypes.readsInStructure`, §5.10). A template
  whose held head is a meta constructor other than `record` reads its value parameters' types, written ones included,
  in the structure namespace: a schema's own `non_negative_integer` never stands in for the meta's at `min_items: N`,
  and `kind<T>`'s `T` is the kernel's `type_name`. A record template reads in the schema's namespace. A template whose
  head is another open template takes that template's form; a deferred use takes its callee's. An alias's head is
  `reference`, a meta constructor, so a written value type on an alias is checked in the structure namespace
  (`rb => <N: int8> box<N>` is refused, `int8` not being a meta type). A type parameter's bound is the schema's in
  every form.
- **A written type narrows, read by the derived kind** (`<T: text, N: int8>`, from
  `SchemaMap.Declaration.parameterTypes`): on a value parameter it replaces `type` and must IS-A the derived one; on a
  type parameter it is `template_param.bound` and must IS-A every bound the uses inherit (`rebox => <T> boxed<T>`
  inherits `boxed`'s). A written bound must resolve in the type-name namespace (`requireType`): the governing-meta
  fallback a slot's declared type needs would otherwise let `<T: text_type>` bind to a constructor, which no type
  argument can IS-A.
- **The call-site check** (`TemplateMaterialiser.checkArguments`, through `byParameterKind`): a type argument must IS-A
  the bound, following reference chains; a value argument must parse as the parameter's type, or as the type an
  earlier parameter's argument names. An application as an argument, or an unresolved name, falls to the substituted
  body.
  - **An application closed during resolution is checked twice.** A declaration naming one (`held => boxed<int32>`), a
    composition operand, a refinement source and an argument nested in any of them close in the driving loop, before
    anything is stamped, so their first check sees no bound. The materialiser records each check that passed, with the
    declaration whose resolution ran it, and `recheckEarly` replays them once the parameters are stamped — one verdict
    per declaration, none for a declaration that already failed.
  - **An application closed on demand infers its own template** (`ParameterTypes.inferOne`): the template has resolved
    by then, so only a parameter awaiting the cross-template fixed point is left undetermined.
- **A record template confines its parameters to three places** (§5.10, `checkSlots`): a field's type, a field's
  default or fixed value, and an argument of an application. A whole-token parameter anywhere else in a body headed
  `record` — a field name, a group member, `optional`, `voidable`, `role`, `extension`, `name_type`,
  `discriminators`, a bare parent — is refused naming the slot (`parameter 'K' stands at a key of record.fields`),
  since each would leave the family base without a shape its applications share. The general kind check runs first,
  so `<T> !record { fields: T }` keeps its "neither a kind of type nor a scalar" verdict. A template over any other
  constructor keeps every scalar slot (`<V> !tuple { … voidable: V }`).
