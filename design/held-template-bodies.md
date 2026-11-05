# Held template bodies

What an open entry's body is, and how each shape of it closes. A **record template** is a `meta.record_template`
(`schema.meta.RecordTemplateBody`, a family base); every other open entry is a **held body**, text carried by the
kernel's `meta.template` constructor (`schema.meta.TemplateBody`), whose held tree (`HeldBody`) is what substitution
rewrites. A record template is substituted on the model instead (`RecordTemplateSubstitution`). This note also owns
§5.10's parameter types (`ParameterTypes`) and §8.2's synthetic merge as it meets closing. Current form only; history
lives in git.

**Invariants**

- Whether an entry is open, what it binds and whether it is a family base are `schema.meta.OpenEntry`'s questions. The
  two open shapes are two Java types, so a phase that wants one asks with a pattern (`TsonSchemaCompiler.build`, the
  JSON factories by `@Typename`, `TemplateMaterialiser.instantiate`), and code that builds a reader or writer from a
  `RecordBody` can never be handed a template.
- **No `RecordBody` holds a parameter**, in output or inside the resolver. A record template is built as a
  `RecordTemplateBody` where its declaration resolves, and an application of it substitutes on that model into a
  `RecordBody`; nothing writes a template out and reads it back.
- A record template is never held text: `SchemaDesugarer` writes every one as a `RecordTemplateDef`, `HeldBody.held`
  refuses a `meta.record` head with `IllegalStateException` (a caller resolving raw AST without that phase), and
  `HeldBody.of` takes a `TemplateBody` only. What §5.10's declaration-time checks ask of either shape -- the names it
  mentions, the applications it writes -- is `HeldBody.namesOf`/`applicationsOf`, which read a record template's
  labels and references (`RecordTemplateBody.references`). A held text that does not parse back is an
  `IllegalStateException`.
- A record template's field is classified once, by its source spelling, where the parameters are in scope: a bare
  parameter name in its type slot is its `type_param`, an unquoted parameter token in its value slot its
  `value_param` (§5.10's shadowing rule). After that the label is the fact: a parameter as an argument of an
  application in a `type` or a parent is the only place a parameter is still recognised by its text, and a field taken
  from a closed source, an inherited literal included, is never relabelled.
- **Recognising a parameter by its text is sound because two spellings are refused** (§5.10). A parameter spelled
  like an entry the schema's namespace holds, a type or a value entry, its own or an import's, is refused at the
  declaration, a held template
  and a record template alike (`SchemaResolver.OnDemand.refuseShadowingParameters`; a lifted form's `p0` list is the
  desugarer's and is not asked). And a record template's parameter spelled like a name or an unquoted token an
  absorbed field's type writes among an application's arguments is refused where the field is absorbed
  (`DefinitionResolver.refuseAbsorbedSpelling`): from a closed source every such name, from an open operand every name
  but the operand's own parameters, which the absorber's arguments replace. With `pair => <V> { z: two<V, T> }`, `T`
  an enum member, `t3 => <T> pair<T> & { … }` is refused naming `T` and `z: two<V, T>`; `pair` is valid. Without the
  second rule `t3`'s `z` would become `two<T, T>`, one spelling for two meanings that no `record_template_field` can
  tell apart. A literal `value` needs neither: `value`/`value_param` already says which it is. The second rule judges
  the source as it is when absorbed: a closed source of the same schema is not yet materialised and still writes its
  applications, so it is judged; an imported one is, each closed application already the bare name of its entry, so
  there is no argument to collide with and nothing to refuse.
- `HeldBody.held` is the only way a `TemplateBody` is minted, and it gives every parameter the provisional
  `meta.type_ref` (`TemplateParam.typeParameter`); `ParameterTypes` stamps the derived types later.
- A held tree is a working value: built per call, never cached or stored on the entry. Callers outside `resolver`
  ask it `names()` and `applications()` only, never for the `DataValue`.
- Substitution is one rule at every depth (`WireForm.substitute`): an **unquoted** token whose text is one of the
  entry's parameters is rewritten (§5.10, with §8.1's shadowing rule), and so is one among a reference's arguments,
  however deep; a quoted token is a literal. `HeldBody.names()` returns exactly the tokens substitution would
  rewrite.
- A declared field name that is no identifier (`"@id"`, `"first name"`) is written quoted (`WireForm.declaredName`),
  or the text does not parse back; quoting never hides a parameter, whose name is an identifier.
- Closing dispatches on the template's shape, then on a held tree's constructor head: a record template closes to a
  record, `meta.alias` to a name, anything else to a synthetic construction.
- Every parameter is substituted, inside nested applications included, before any application in the body closes.
- A parameter is a TYPE parameter exactly when its recorded type is `meta.type_ref` (§5.10); a slot typed `meta.type_ref`
  gives one, an `Atom`-typed slot gives a VALUE parameter, and anything else is refused at the declaration.
- An application in a held tree is a `ReferenceValue`, the data grammar's reference written unquoted (`result<T>`,
  [TSON-DATA] §7.4, SPEC-FEEDBACK.md #12): `WireForm.refValue` writes it, `WireForm.applicationIn` recognises it (an
  `instanceof`, nothing parsed), and `HeldBody.names()` includes the names its arguments write. A quoted token is
  never a reference, so a held body's text literal `pattern: "box<T>"` stays text.
- Parameter types are derived twice: before materialisation (feeding the call-site check) and after everything has
  closed (stamping minted open entries). The second pass reports nothing.

Related: `design/template-materialisation.md` (the closing pass: identity, declared applications, the expansion
rule, on-demand closing, kind, regularity), `design/desugaring-open-forms-and-templates.md` (how sugar and aliases are
normalised into held bodies, open and closed lifts, value-argument identity), `design/resolver-vocabulary-and-bootstrap.md`
(`WireForm`, `DerivedName`, `@meta.synthetic`), `design/schema-resolution.md` (`parameterised`, `openRecord`, marks on a
record template), `design/constructor-application.md`, `design/linking-and-compilation.md`.

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

- **A record template is its own constructor, `record_template`, built directly.** Every slot it admits a parameter in is a
  label or a reference — a field's `type_param` or `value_param`, an application's arguments — so the template is a value of
  the vocabulary as it stands: a `RecordTemplateBody`, its chain keeping the parents as written. `SchemaDesugarer` writes a
  bare body or an explicit `<T> !meta.record { … }` as the labelled `!meta.record_template { … }` payload it denotes
  (`RecordTemplateDef`), and `DefinitionResolver.openRecord` answers the author's spelling in `meta.record`'s terms (its
  bindings, `ParameterTypes.checkSlots` confining the parameters) before binding it through `record_template`'s own reader. A
  composition or refinement template is the *flattened* record resolution builds (`DefinitionResolver.Built`) over the
  builder's own field type (`BuiltField`), its own entries labelled where they are resolved (`BuiltField.written`), a closed
  source's fields absorbed as they are, and an open operand's substituted on the model with the
  absorbing declaration's parameters as its arguments, landing as that declaration's labels. `Built.finish` makes a
  `RecordField` of each for a closed declaration and a `RecordTemplateField` for a template, so a closed declaration builds
  no template value. Either way `checked` refuses a selector typed by a parameter (`refuseParameterTypedSelector`) and a
  literal value on a field typed by one (`refuseLiteralOnOpenType`: a literal needs a closed type). An error placeholder for
  an open declaration is an empty record template (`SchemaResolver.unresolved`), so it closes like any record template.
  - **Why its own constructor and not a record with optional `parameters`.** A record is what readers and writers are
    built for, and a template is never read or written against: a position it types dispatches to a member. As one
    Java type the two could only be told apart by a runtime check at every reader, writer and dispatcher; as two, the
    dispatchers' "a template IS-A the base is no member" is the `instanceof RecordBody` they already make, and the
    compiler's choice between `AbstractTemplateReader` and a record reader is a pattern. In source,
    `!meta.record_template` is resolver vocabulary like `!meta.template`, and a construction applying it is refused
    (`requireAuthorable`). The desugarer's own `!meta.record_template` payload is a `RecordTemplateDef`, the one
    `TypeDef` no parser builds, so the type tells it from an author's `Instance` and the refusal needs no exception.
- **Every other open entry is held text.** `set` resolves to `!meta.template { parameters: [{ name: T  type: meta.type_ref
  }]  template: "!meta.set_type { element_type: T }" }`. That covers an open instance (`<T, N> !meta.array { … }`), an
  open sugar form (`<T> [T]`, `<T> ( T | error )`), and §5.10's partial application, `uuid_pair => <B> pair<uuid, B>`,
  held as the `!meta.alias { target: pair<uuid B> }` §8.1 says it denotes — spellable because `meta.alias.target` is a
  reference, which may carry arguments. Its kind is `TEMPLATE` whatever its head (`design/template-materialisation.md`).
  - **Why text, not a value of the constructor's vocabulary.** A parameter stands wherever a token stands —
    `element_type: T` in a type slot, `min_items: N` in a value slot, `variants: [T error]` inside a collection — so a
    body carrying one is typed by no constructor's record shape until it closes. Read as one, `min_items: N` fails
    against `numeric.count`, and `!enum { members: [a b M] }` reads *cleanly*, binding `M` as a third member
    spelled `M`. `OpenEntryResolvedFormTest` pins that it reads back as held text.
  - **The tree is built, not held.** `HeldBody.held` writes the tree it is given (`DataClassObjectWriter`,
    vocabulary-free, token forms preserved) and keeps none of it; `HeldBody.of` parses the text back. Handing back the
    tree in hand would hide a writer/parser disagreement about §5.10's one spelling, which instead fails in
    `HeldBodyTest`. The AST is `tson-compiler`'s type, and `tson-schema` depends on nothing here, so an entry could not
    carry one without inverting the module direction.
  - **Comparison needs no channel of its own.** §5.10 compares a held body as its parsed form, so `ResolvedForm` reduces
    one to its parsed application and an open entry compares like every other entry.

## How an open entry closes (`TemplateMaterialiser.close`)

A record template closes on the model; a held tree is parsed once per closing and dropped with it, and its head
decides what the result *is* — substitute (`WireForm.substitute`), then close the applications the substituted body
holds.

- **A record template** (`closeRecordTemplate`): its fields and parents substituted on the model
  (`RecordTemplateSubstitution`) — a `type_param` takes its argument's type, a `value_param` its argument's token, and a
  parameter in an application's arguments is replaced where it stands — then each field closed to a `RecordField`, the
  applications in its type closed in field order, and taken as a member: no `discriminators`, its pins being in place,
  member dispatch not travelling (`closedExtension`), its chain completed from the substituted parents
  (`Ancestry.chainOf`). Parents are substituted and not closed: an operand denotes no entry (§8.2). A member's field
  carries no source position. The argument's kind is checked by the label it lands at: a value where a type parameter
  stands (`box<3>`) and an application where a value parameter stands are refused at the application; whether a
  value argument is a value of the field's type is `checkArguments`' and the linker's `checkFieldValue`'s. A minted
  member is named from its binding record as every closed form is (`WireForm.recordWire`, `DerivedName.ofBinding`),
  so its identity is the closed record's content and does not depend on how it was closed.

- **Anything but `meta.record` or `meta.alias`** (`closeHeldTemplate`, via `closeHeld`): the substituted wire is bound
  through **the constructor's own compiled reader**, the one a written `!meta.array { … }` binds through, so the result is
  an ordinary `ArrayBody`/`MapBody`/`ChoiceBody`/… named for its binding record (`DerivedName.ofBinding`). A failure
  there is §8.2's deferred value-level check, with no code of its own, for whatever the call-site check (below) could
  not judge: the reader reports it exactly as it would for a written body.
- **`meta.alias`** (`closeHeldAlias`): an alias composes rather than mints. Applying it binds the arguments into the
  target's argument list and closes what results, so `uuid_pair<int32>` *is* the entry `pair<uuid, int32>` denotes,
  and the alias's `target` must close to a bare name or it is an `IllegalStateException`. §5.10 mints no intermediate
  entry per alias hop, so a chain of aliases collapses and the origin survives only in the composed entry's
  `@meta.application`. `ident => <T> T` applied to `text` is `text`.
  - **Arity is the alias's own**, checked against its `parameters` before composing, so `uuid_pair<int32, text>` names
    `uuid_pair`'s one parameter rather than `pair`'s two.
  - **A self-applying alias is a cycle, not a knot.** The knot-tying memo answers a recursive application with the
    entry under construction, and this path constructs none, so `loop => <T> loop<T>` would hand a field a name
    nothing defines. `aliasClosing` tracks alias-template applications in flight and reports the cycle (`alias
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
  reference whole, arguments intact, so `[tree<T>; 1..]` inside `tree` lifts to a synthetic whose `element_type` is
  `tree<p0>`. Substitution rewrites `p0` inside that application before anything closes; closing first would close
  `tree<p0>`, an application of an argument nothing supplied. That is what ties §8's `tree` knot: closing
  `tree<text>` reaches the synthetic, whose binding reaches `tree<text>` again and finds it in `closing`, so the
  array names the instantiation entry recorded before that entry completes.
- **An application in a held body closes when the body does**, whether or not it names a parameter: the held text is
  not scanned at the declaration. Only the two field-absorbing positions, a composition operand and a refinement
  source, close during resolution (`DefinitionResolver.closedApplication`; `design/template-materialisation.md`).
- **A closed container position takes one too** (`[box<text>]`): its slot is written as the reference, and
  the batch pass closes it like every other ref; `close()` builds `pair<int32>` before `box<pair<int32>>` names it.
- **The synthetic merge is where the two lift channels meet** (§8.2; `SyntheticMerge`, whose Javadoc carries the
  rationale). A form the desugar phase lifted *closed* with an application in a slot was named from an unreduced
  record, so `[box<text>]` written directly lands apart from `[box<T>]` closed with `T := text`. After
  materialisation `SchemaResolver` re-derives each such form through `TemplateMaterialiser.closedFormName`, rewrites
  references onto that name, and drops the eager entry where the other channel already published the form or moves
  it where it did not. **The closed-record name wins**, being a function of the resolved form alone; only a form whose
  binding held an application moves.
- **An argument keeps what it was applied as.** An argument list is the one position where a type and a value are
  equally at home, so a value parameter passed straight through (`array_p0_…<N>` inside `<N> { a: [text; N] }`)
  stays a value: substitution puts the bound `TypeArgument` itself where the parameter stood, walking the reference's
  argument tree, and writes the application again as a `ReferenceValue` (`WireForm.substituted`). What a held body
  admits beyond that — collection-valued slots, value arguments and their numeric identity — is
  `design/desugaring-open-forms-and-templates.md`'s.

## Parameter types (`ParameterTypes`)

Each parameter records the type an argument for it is read as (`meta.template_param.type`, §5.10), derived from the
positions it stands in; the kind follows from it. §12.1 decides an argument's channel by token shape, so an unquoted
non-numeric argument arrives as a reference — wrong for `e => <M> !enum { members: [a b M] }` applied as `e<c>`, where
`c` is a member. What settles it is the declared type of the slot the parameter stands in, read from the constructor's
own vocabulary: `array.element_type` is `meta.type_ref`, `enum.members` is `meta.enum_set` (of `text`),
`meta.record_field.value` is `value`. A record template is read off the model (`ParameterTypes.walk`): a `type_param`
is a type position, a `value_param` takes the field's declared type, and an argument of an application in a field's
type or a parent is deferred to the head's parameter. §4.2 types every slot that names a type `meta.type_ref`, an
application included, so a schema's own constructors classify by the same walk. The class Javadoc lists the four
sources of a use type. Beyond those:

- **Stamped twice.** A held body is built before anything can be typed. `SchemaResolver` runs
  `ParameterTypes.inferAll` once every declaration has resolved and nothing has closed, stamping the declared
  templates and feeding `TemplateMaterialiser.parameterKinds`; and again after materialisation and the merge, over
  every local open entry, declared and minted, with a reporter that discards — its failures are the first pass's,
  already reported.
- **A fixed point, not one walk.** An argument list holds a parameter of either kind, a reference by the grammar
  until the head's parameter judges it, so a parameter passed to another template says nothing locally: it takes the
  callee's recorded type at that position, and two templates may wait on each other. A parameter the fixed point leaves
  ungrounded is a TYPE parameter (`meta.type_ref`), as §5.10 states, so `loop => <T> loop<T>` is judged on what is wrong
  with it — it applies itself forever. An imported template is taken as recorded, since its held body does not carry
  what its author wrote.
- **Several uses must agree**: the type is the use type that IS-A every other, uses unordered by IS-A are refused at
  the declaration, and a parameter standing in both kinds of position keeps its own message. A parameter standing for a
  whole collection or record (`<T> !enum { members: T }`) is refused as neither a kind of type nor a scalar.
- **A routed default or fixed value** (a parameter at `meta.record_field.value`) is read as the field's declared type, from
  the sibling `type` slot: `w?: T ~ N` gives `N` the type `T`, an earlier parameter. A payload in §5.6's positional form
  (`!enum [a b M]`) is walked as the one unmarked field it binds.
- **The template's form decides where value types are read** (`ParameterTypes.readsInStructure`, §5.10). A template
  whose held head is a constructor other than `meta.record` reads its value parameters' types, written ones included,
  among the schema's imported entries: the constructor's compiled reader reads the argument, and its slot types are
  in the closure that declared it, so `min_items: N` makes `N` the kernel's `numeric.count`. A record template
  reads in the schema's whole namespace. A template whose head is
  another open template takes that template's form; a deferred use takes its callee's. An alias's head is
  `meta.alias`, a constructor, so a written value type on an alias must name an imported type too. A written type
  must also IS-A the derived one: `<N: int8> !meta.array { … max_items: N }` is refused, numeric.tn's `int8` not being a
  `numeric.count` (`TemplateParamTypeTest`). A type parameter's bound is the schema's in every form.
- **A written type narrows, read by the derived kind** (`<T: text, N: int8>`, from
  `SchemaMap.Declaration.parameterTypes`): on a value parameter it replaces `type` and must IS-A the derived one; on a
  type parameter it is `meta.template_param.bound` and must IS-A every bound the uses inherit (`rebox => <T> boxed<T>`
  inherits `boxed`'s). A written bound must resolve in the schema's namespace and name a type (`requireType`):
  `<T: unicode.text_type>` names a constructor (IS-A `top`), which no type argument can IS-A, and is refused as "a
  constructor, not a type".
- **The call-site check** (`TemplateMaterialiser.checkArguments`, through `byParameterKind`): a type argument must IS-A
  the bound, following alias chains; a value argument must parse as the parameter's type, or as the type an
  earlier parameter's argument names. An application as an argument, or an unresolved name, falls to the substituted
  body.
  - **An application closed during resolution is checked twice.** A declaration naming one (`held => boxed<int32>`), a
    composition operand, a refinement source and an argument nested in any of them close in the driving loop, before
    anything is stamped, so their first check sees no bound. The materialiser records each check that passed, with the
    declaration whose resolution ran it, and `recheckEarly` replays them once the parameters are stamped — one verdict
    per declaration, none for a declaration that already failed.
  - **An application closed on demand infers its own template** (`ParameterTypes.inferOne`): the template has resolved
    by then, so only a parameter awaiting the cross-template fixed point is left undetermined.
- **A record template confines its parameters to three places** (§5.10, `checkSlots`): a field's type (`type_param`), a
  field's default or fixed value (`value_param`), and an argument of an application. A whole-token parameter anywhere else in
  a body headed `meta.record` — a field name, a group member, `optional`, `voidable`, `role`, `extension`, `name_type`,
  `discriminators`, a bare parent — is refused naming the slot (`parameter 'K' stands at a key of meta.record.fields`), since
  each would leave the family base without a shape its applications share. The labelled payload is walked as the
  `meta.record` construction the author wrote, so a slot is named in `record`'s and `record_field`'s terms, a label is where
  a parameter belongs, and the stated parameter list is no slot of `record`'s. A positional field written with a parameter
  (`a => T`) arrives labelled `type_param`, as `a => { type: T }` and the sugar `a: T` do. The general kind check runs
  first, so `<T> !meta.record { fields: T }` keeps its "neither a kind of type nor a scalar" verdict. A template over any
  other constructor keeps every scalar slot (`<V> !meta.tuple { … voidable: V }`).
