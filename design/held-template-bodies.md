# Held template bodies

What an open entry's body is, and how each shape of it closes. A **record template** is a `meta.record_template`
(`schema.meta.RecordTemplateBody`); every other open entry is a **held body**, text carried by the
kernel's `meta.template` constructor (`schema.meta.TemplateBody`), whose held tree (`HeldBody`) is what substitution
rewrites. A record template is substituted on the model instead (`RecordTemplateSubstitution`). This note also owns
§5.10's parameters -- where one may stand, and its bound (`ParameterTypes`) -- and §8.2's synthetic merge as it meets
closing. Current form only; history lives in git.

**Invariants**

- Whether an entry is open and what it binds are `schema.meta.OpenEntry`'s questions; neither shape is a type (§5.10). The
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
- **A parameter is a type** (§5.10). It stands only where a type is named -- a slot whose declared type is
  `meta.type_ref` or a refinement of it, which in a record template is a field's type -- or as an argument of an
  application there or in a parent. Anywhere else (a facet, a field's default or fixed value, an enum member, a size
  bound, a field name, a group member, `optional`, `voidable`, `role`, `extension`, `discriminators`, `name_type`) it is
  refused at the declaration, naming the slot (`ParameterTypes.at`/`atValue`). An argument is always a reference, a
  name or an application; a literal token is no argument, refused by the grammar.
- A record template's field is classified once, by its source spelling, where the parameters are in scope: a bare
  parameter name in its type slot is its `type_param` (§5.10's shadowing rule). After that the label is the fact: a
  parameter as an argument of an application in a `type` or a parent is the only place a parameter is still recognised
  by its text, and a field taken from a closed source, an inherited literal included, is never relabelled.
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
  tell apart. A literal `value` needs neither, a field's value never being a parameter. The second rule judges
  the source as it is when absorbed: a closed source of the same schema is not yet materialised and still writes its
  applications, so it is judged; an imported one is, each closed application already the bare name of its entry, so
  there is no argument to collide with and nothing to refuse. With every argument a reference, a name an absorbed
  argument writes is a namespace entry, so the first rule already refuses a parameter spelled like it; the second
  stays as the statement of why.
- `HeldBody.held` is the only way a `TemplateBody` is minted, its parameters by name; the bounds a declaration
  writes are put on where it resolves (`DefinitionResolver.withBounds`), for either shape. A bound is never inferred.
- A held tree is a working value: built per call, never cached or stored on the entry. Callers outside `resolver`
  ask it `names()` and `applications()` only, never for the `DataValue`.
- Substitution is one rule at every depth (`WireForm.substitute`): an **unquoted** token whose text is one of the
  entry's parameters is rewritten with its argument, a reference (§5.10, with §8.1's shadowing rule), and so is one
  among a reference's arguments, however deep; a quoted token is a literal. `HeldBody.names()` returns exactly the
  tokens substitution would rewrite.
- A declared field name that is no identifier (`"@id"`, `"first name"`) is written quoted (`WireForm.declaredName`),
  or the text does not parse back; quoting never hides a parameter, whose name is an identifier.
- Closing dispatches on the template's shape, then on a held tree's constructor head: a record template closes to a
  record, `meta.alias` to a name, anything else to a synthetic construction.
- Every parameter is substituted, inside nested applications included, before any application in the body closes.
- An application in a held tree is a `ReferenceValue`, the data grammar's reference written unquoted (`result<T>`,
  [TSON-DATA] §7.4): `WireForm.refValue` writes it, `WireForm.applicationIn` recognises it (an
  `instanceof`, nothing parsed), and `HeldBody.names()` includes the names its arguments write. A `ReferenceValue`
  holding a sibling reference (`kind!`, at a record template field's type) is no application: every walk that
  rewrites one matches `ReferenceValue(TypeRef applied)`, and a sibling falls through as written. A quoted token is
  never a reference, so a held body's text literal `pattern: "box<T>"` stays text.
- Parameters are checked once per declaration, after every declaration is ready and before anything closes
  (`ParameterTypes.check`); nothing is stamped on them afterwards.

Related: `design/template-materialisation.md` (the closing pass: identity, declared applications, the expansion
rule, on-demand closing, kind, regularity), `design/desugaring-open-forms-and-templates.md` (how sugar and aliases are
normalised into held bodies, open and closed lifts), `design/resolver-vocabulary-and-bootstrap.md`
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
ABSTRACT to live, and somewhere for the discriminators member dispatch reads. The near misses confirm it: `<T> [T]`
would be `List<?>`, but containers have no IS-A and `vector<float32>` and `vector<int32>` erase to one host type; a
choice body mints no edge to its variants. A
bare base at a position (`r: result`) reads as the existential, "some `result<X>`", well-defined because no value is
ever read against the base: a value there is a member's, selected by its tag or its discriminators, and every member
is closed (§5.10). Neither kind has variance, since construction transfers kind and not IS-A: `box<cat>` is not IS-A
`box<animal>`. So the kind follows the body, with no key-side spelling
(`box<T> => …`), and §8.2's identities reduce to four facts: a declaration creates identity, `^` and `&` create IS-A
edges in every application, a parameter list creates the one further edge (declared applications of a record IS-A
its base), and an application nothing declares is its expansion.

- **A record template is its own constructor, `record_template`, built directly.** Every slot it admits a parameter in is a
  label or a reference — a field's `type_param`, an application's arguments — so the template is a value of
  the vocabulary as it stands: a `RecordTemplateBody`, its chain keeping the parents as written. `SchemaDesugarer` writes a
  bare body or an explicit `<T> !meta.record { … }` as the labelled `!meta.record_template { … }` payload it denotes
  (`RecordTemplateDef`), and `DefinitionResolver.openRecord` answers the author's spelling in `meta.record`'s terms (its
  bindings, `ParameterTypes.checkSlots` confining the parameters) before binding it through `record_template`'s own reader. A
  composition or refinement template is the *flattened* record resolution builds (`DefinitionResolver.Built`) over the
  builder's own field type (`BuiltField`), its own entries labelled where they are resolved (`BuiltField.written`), a closed
  source's fields absorbed as they are, and an open operand's substituted on the model with the
  absorbing declaration's parameters as its arguments, landing as that declaration's labels. `Built.finish` makes a
  `RecordField` of each for a closed declaration and a `RecordTemplateField` for a template, so a closed declaration builds
  no template value. A parameter written at a field's value is refused where the entry resolves (`ParameterTypes.atValue`).
  Either way `checked` refuses a selector typed by a parameter (`refuseParameterTypedSelector`) and a literal value on a
  field typed by one (`refuseLiteralOnOpenType`: a literal needs a closed type). An error placeholder for
  an open declaration is an empty record template (`SchemaResolver.unresolved`), so it closes like any record template.
  - **Its own Java type.** A record is what readers and writers are built for, and a template is never read or written
    against, being no type; as one Java type the two could only be told apart by a runtime check at every reader and
    writer, and as two a reader factory taking a `RecordBody` cannot be handed a template. In source,
    `!meta.record_template` is resolver vocabulary like `!meta.template`, and a construction applying it is refused
    (`requireAuthorable`). The desugarer's own `!meta.record_template` payload is a `RecordTemplateDef`, the one
    `TypeDef` no parser builds, so the type tells it from an author's `Instance` and the refusal needs no exception.
- **Every other open entry is held text.** `set` resolves to `!meta.template { parameters: [{ name: T }]  template:
  "!meta.set_type { element_type: T }" }`. That covers an open instance (`<T> !meta.array { … }`), an
  open sugar form (`<T> [T]`, `<T> ( T | error )`), and §5.10's partial application, `uuid_pair => <B> pair<uuid, B>`,
  held as the `!meta.alias { target: pair<uuid B> }` §8.1 says it denotes — spellable because `meta.alias.target` is a
  reference, which may carry arguments. Its kind is `TEMPLATE` whatever its head (`design/template-materialisation.md`).
  - **Why text, not a value of the constructor's vocabulary.** A parameter stands where a type is named —
    `element_type: T` in a type slot, `variants: [T error]` inside a collection — so a body carrying one is typed by no
    constructor's record shape until it closes: `element_type: T` is a reference to a type nothing declares. Held text
    is also what lets the declaration-time walk refuse a parameter where none may stand, `!enum { members: [a b M] }`,
    which read as the constructor would bind `M` *cleanly* as a third member spelled `M`. `OpenEntryResolvedFormTest`
    pins that a held body reads back as held text.
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
  (`RecordTemplateSubstitution`) — a `type_param` takes its argument as its type, and a parameter in an application's
  arguments is replaced where it stands — then each field closed to a `RecordField`, the
  applications in its type closed in field order, the template's `extension`, `discriminators`, groups and name type
  kept as written -- an abstract template's application is an abstract record -- and its chain completed from the
  substituted parents (`Ancestry.chainOf`). Parents are substituted and not closed: an operand denotes no entry
  (§8.2). A closed application's field carries no source position. Whether an argument names a type that IS-A the
  parameter's bound is `checkArguments`'; a literal argument (`box<3>`) never reaches here, the grammar having refused
  it. A minted member is named from its binding record as every closed form is (`WireForm.recordWire`,
  `DerivedName.ofBinding`), so its identity is the closed record's content and does not depend on how it was closed.

- **Anything but `meta.record` or `meta.alias`** (`closeHeldTemplate`, via `closeHeld`): the substituted wire is bound
  through **the constructor's own compiled reader**, the one a written `!meta.array { … }` binds through, so the result is
  an ordinary `ArrayBody`/`MapBody`/`ChoiceBody`/… named for its binding record (`DerivedName.ofBinding`). A failure
  there is §8.2's deferred check, with no code of its own, for whatever the call-site check (below) could not judge --
  a key type that names no identifier family, say: the reader reports it exactly as it would for a written body.
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
- **A generated head mints no instantiation.** Closing `array_p0_3_3_…<pixel>` is an open synthetic closing its own
  intermediate form; nobody wrote that application. `SchemaResolver` tells generated heads from authored ones by the
  names desugaring added (`SchemaDesugarer.lifted`).

### Applications inside a held body

- **They close before the entry is named**, which keeps one type on one entry: desugar lifts innermost-first, so a
  form it writes already names the entry its inner form became, and a form closed here must agree, or
  `[[pixel; 3]; 3]` written out and `grid<pixel>` closed would be two entries for one type (§5.10).
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
- **An argument keeps what it was applied as.** Substitution puts the bound reference itself where the parameter stood,
  walking the reference's argument tree, and writes the application again as a `ReferenceValue`
  (`WireForm.substituted`). What a held body admits beyond that — collection-valued slots — is
  `design/desugaring-open-forms-and-templates.md`'s.

## Parameters (`ParameterTypes`)

Each parameter records its name and its bound (`meta.template_param`, §5.10). Nothing about it is derived from where it
stands: a parameter is a type, so where it stands is only ever checked. What settles whether a token in a held body is
admitted is the declared type of the slot it stands in, read from the constructor's own vocabulary:
`array.element_type` is `meta.type_ref`, `array.min_items` `numeric.count`, `enum.members` a set of `text`. A slot
names a type when its declared type is `meta.type_ref` or a reference type refining it (`meta.identifier_ref`, so
`namespace.key_type` takes one); a reference naming values (`meta.ref<method>`) names none. §4.2 types every slot that
names a type that way, so a schema's own constructors are judged by the same walk, which also yields the applications
written at those slots. A record template is read off the model (`RecordTemplateBody.references`): its labels need no
check, and its applications are those in a field's type or a parent.

- **One refusal.** A parameter at any slot that names no type is refused at the declaration, naming the slot as
  `constructor.field` (`parameter 'N' stands at meta.array.min_items`, `… at a key of meta.record.fields`). In a record
  template the one whole-token slot is a field's type. A field's value is refused by name wherever it is written: in
  the payload (`meta.record_field.value`, whose own type is its sibling's and so declares nothing to walk against), in
  a composition or refinement entry (`DefinitionResolver.resolveFieldEntry`, `ParameterTypes.atValue`), a pin on a
  slot typed `meta.type_ref` (`<T> meta.array ^ { element_type?: = T }`) included. A size bound naming a parameter is
  refused by the desugarer (`SchemaDesugarer.refuseParameterBound`), before the lift renames it. A payload in §5.6's
  positional form (`!enum [a b M]`) is walked as the one unmarked field it binds, so its member is refused too.
- **Checked once, at the declaration.** A declaration's written bounds are on its parameters from the moment it
  resolves (`DefinitionResolver.withBounds`, from `SchemaMap.Declaration.parameterTypes`). `SchemaResolver` runs
  `ParameterTypes.check` once every declaration is ready and nothing has closed: where each parameter stands, and each
  bound below. A refused template is condemned to a placeholder, as an irregular one is. The kernel's bootstrap runs
  the same check, a failure there being a broken kernel.
- **A bound is written, never inherited.** One is written after the name (`<T: text>`); it must resolve in the schema's
  namespace and name a type (`requireType`: `<T: unicode.text_type>` names a constructor, IS-A `top`, which no argument
  can IS-A). A parameter passed as an argument to a bounded parameter of another template must itself be written with a
  bound that IS-A that one (`Callees.requireBound`): `rebox => <T> { b: boxed<T> }` over `boxed => <T: text> { v: T }`
  is refused at `rebox`, naming `boxed`'s `T: text`, and `<T: int32>` is refused for not being IS-A `text`. This holds
  wherever the parameter is an argument: a field's type, a nested application, an applied parent, an alias template's
  target, a held construction's type slot. Each declaration is judged against the bounds its callees *wrote*, so no
  order among templates is needed and nothing iterates. An imported template's recorded bound is what its author
  wrote, and binds the same way.
  - **A lifted form is looked through.** An open synthetic's parameters are the lift's (`p0`), which no author wrote and
    which carry no bound, and one synthetic serves every template writing the same form, so it cannot carry any one
    declaration's bound. `[boxed<T>]` lifts to an open synthetic applied to `T`, which holds `boxed<p0>`: the check
    maps the synthetic's parameters to the arguments it was given and judges the applications it holds at the
    declaration that wrote the form. A synthetic is never a callee, and is not itself checked for passing a parameter
    on.
  - **A constructor's slot needs no bound.** A head that is not open (construction sugar, `meta.namespace<K text>`) has
    no parameters to bound, and its slot judges the argument that arrives when the record is applied.
- **The call-site check** (`TemplateMaterialiser.checkArguments`): each argument must name a type that IS-A its
  parameter's bound, following alias chains. An application as an argument, or an unresolved name, falls to the
  substituted body.
  - **An application closed during resolution is checked once every declaration is ready.** A declaration naming one
    (`held => boxed<int32>`), a composition operand, a refinement source and an argument nested in any of them close
    in the driving loop. Judging an argument reads its chain, and an argument is a name at a type position, which
    orders nothing (§3.4.1): checking it then would make the declaration wait on it, and `a => boxed<b> & { … }`
    beside `b => boxed<a> & { … }` would become a wait cycle. So the materialiser defers each such check, with the
    declaration whose resolution ran it, and `checkDeferred` runs them after `ParameterTypes.check` — one verdict per
    declaration, none for a declaration that already failed or a template already condemned.
