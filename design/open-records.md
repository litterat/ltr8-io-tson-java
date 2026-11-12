# Open records and their parameters

What a template is -- an open record, a `meta.record` carrying `parameters` -- where its parameters may stand, how
they are held as references, and what each parameter's bound is checked against (`ParameterTypes`). How an
application of one closes is `design/template-materialisation.md`'s. Current form only; history lives in git.

**Invariants**

- **A template is an open record** ([TSON-SCHEMA] §5.10): a `schema.meta.RecordBody` whose `parameters` (a list of
  `schema.meta.Param`) is not empty. There is no other open shape: `record` is the only constructor that carries
  parameters, and the kernel declares no open record. Whether an entry is open and what it binds are
  `schema.meta.OpenEntry`'s questions, a facade over `RecordBody.isOpen`/`parameters`, asked of any `Top`.
- **No closed position reaches an open record, and no member set over `meta.ref<meta.record>` holds one.** A reference
  names an open record only as the head of an application; `KindMembers` refuses it as a member and as an `of`, the
  linker leaves it out of every `subtypes` index, `FamilySelectors` treats it as no base, `DiscriminationClass` gives it
  no class, and both compilers build `OpenTemplateReader` for it (`TsonSchemaCompiler.build`,
  `DispatchFactories.over` in `tson-json`). Code building a reader or writer from a `RecordBody` asks `isOpen` first.
- **A parameter is a reference value** (`schema.meta.ParamRef`, a `TypeRef` with no arguments and no closure). Inside
  an open record a name its `parameters` declare is that parameter, looked up before the namespace (§3.3).
  `RecordBody`'s constructor turns every such `NameRef` among its fields' types, at any depth of types in place
  (`TypeSlots`), and its parents' arguments into a `ParamRef`, from source and from a bound resolved form alike, and
  refuses a `ParamRef` its parameters do not declare (`IllegalArgumentException`). **A closed record judges none**: one
  written in place inside an open record holds that record's parameters, and only the open record can tell them from a
  stray one. An application substitutes every one away, so a closed entry holds none.
- **A parameter stands at a field's `type`, at a slot of a type written in place there (a type slot or a name slot:
  `[T]`, `{T => int32}`, `{ cursor: T }`, at any depth), or as an argument of an application at any of those or in a
  parent, and nowhere else.** A field's `value` is never one (`ParameterTypes.atValue`, at the source spelling in
  `DefinitionResolver.resolveFieldEntry`); a field name or a group member named like one, and a `name_type` naming
  one, are refused by `DefinitionResolver.checked` (`ParameterTypes.at`); a size bound naming one by
  `SchemaDesugarer.refuseParameterBound`; a parameter as an operand of `&`/`^` by `refuseParameterOperand`; a
  parameter applied to arguments (`T<text>`) by `SchemaResolver.refuseHeadAbstraction`.
- **A template's body is a record body**: braces, a composition (`base & { … }`, an applied parent included) or a
  refinement. A parameter list before anything else is refused at the declaration with the remedy in the message --
  before a sugar form or a bare reference by `SchemaDesugarer.refuseTemplateBody`, before a construction by
  `DefinitionResolver.openInstance` (a constructor: write the application or declare a refined constructor; an
  ordinary type: one value per instantiation). The one construction admitted is `<T> !meta.record { … }`
  (`DefinitionResolver.openRecord`).
- **Recognising a parameter by its name is sound because the shadowing rule refuses the other reading**
  (`SchemaResolver.OnDemand.refuseShadowingParameters`): a parameter spelled like an entry the schema's namespace
  holds, a type or a value entry, its own or an import's, is refused at the declaration. With every argument a
  reference, a name an absorbed field's type writes is an entry, so this one rule covers absorbed fields too.
- **An application where a type in place holds only a name is refused at the declaration** (`ParameterTypes`'
  `requireNamesInPlace`): `{box<T> => text}` is refused at the open record, as `meta.map<box<T> text>` is, rather than
  only at each application.
- **A bound is written, never inherited.** `<T: text>` puts `Param.bound` on where the declaration resolves
  (`DefinitionResolver.withBounds`); nothing about a parameter is inferred. `ParameterTypes.check` runs once every
  declaration is ready and before anything closes: each written bound names a type, and each parameter passed to a
  bounded parameter of another open record is written with a bound that IS-A it.

Related: `design/template-materialisation.md` (the closing pass: identity, declared applications, closing in place,
operands, kind, regularity), `design/desugaring-open-forms-and-templates.md` (forms over a parameter inside an
open record, and the bodies refused), `design/schema-resolution.md` (`openRecord`, marks on an open record),
`design/constructor-application.md` (construction sugar, which applies a constructor by constructing it),
`design/references.md`.

## Why the template is a record

- **A template abstracts over types, and only a record needs a written parameter list.** `meta.array<text>` and
  `result_of<text>` share a spelling and not an operation: the first is construction sugar, `!meta.array {
  element_type: text }`, nothing substituted (`design/constructor-application.md`); the second substitutes `text` for
  `T` in a record the author wrote. What the two share is where the argument lands -- a reference-typed slot of an
  instance -- so a constructor needs no parameters to be applied, and a record is the only body that does.
- **Three things survive abstracting over the parameters, and all three are record properties**: a residual shape
  (the fields naming no parameter are what every application shares), somewhere for ABSTRACT to live, and somewhere
  for the discriminators member dispatch reads. A family is declared with records -- an abstract base with the
  selectors, an open record composing it, and members composing an application and pinning them -- and no
  application is IS-A its template (§5.10).
- **Read at its declaration, as any record is.** An open record is a value of `record`'s own vocabulary, so it is
  checked where it is written: its field names, groups, marks and literal values; the references it holds are judged
  for arity at link (`TsonSchemaLinker.checkOpenArity`). What waits for an application is what a reference resolves
  to once its parameter is supplied -- an argument a slot refuses (`meta.namespace<int32 text>`), a recursion that
  never terminates (`TypeInhabitance`).
- **A literal needs a closed type, and a selector one closed in every application.** `checked` refuses a literal
  `value` on a field whose type names a parameter, whole, as an argument or at a slot in place, the open record's own
  or one of a record written in place inside it (`refuseLiteralOnOpenType`), and a selector
  typed by one (`refuseParameterTypedSelector`). A value that differs per application is written where the
  application is declared, `created => response<order> & { status?: = 201 }`.
- **`abstract` and the selectors are each application's.** `result => abstract <T> { ok: boolean =? }` makes
  `result<text>` an abstract record dispatching over the declarations composing it; `final` on a template is refused
  (`DefinitionResolver.withExtension`).

## How an open record is built

- **A fresh record body** (`<T> { x: T }`) reaches `DefinitionResolver.resolveRecordBody` as a closed one does, with
  the declaration's parameter names; `Built.finish` makes the `RecordBody` with `parameters` (each unbounded until
  `withBounds`) and runs `checked`. The fields are the builder's own `BuiltField`s, a field's four facts; no label says
  a type is a parameter -- the record's constructor does that by scope.
- **A composition or refinement** (`<T> base & { v: T }`, `<T> box<T> ^ { … }`) is the flattened record, built over a
  closed source's fields as they are and an open operand's substituted on the model (`openOperand`, `Substitution`)
  with the absorbing declaration's arguments, written as its names, which its own record then holds as its
  parameters. The chain keeps the parents as written beside the closed entries they reach (`Ancestry.ofTemplate`).
- **The explicit spelling** `<T> !meta.record { … }` is read through `record`'s own reader (`openRecord`): first the
  author's terms -- a binding `record` does not declare (`checkTemplateBindings`), a parameter at a slot that names no
  type (`ParameterTypes.checkSlots`, the one walk left over a payload; a positional field `item => T` is its
  `record_field`'s type; a `{ of: !C { … } }` wrapper is walked against `C`'s vocabulary, where a parameter at a slot
  holding a type or a name stands as at a field's type, and one at a scalar slot is refused as anywhere else; only a
  wrapper whose constructor cannot be read yet leaves the slots untold, a gap), a literal beside a parameter-typed
  field (`refuseLiteralOnParameter`) -- then the
  declaration's parameters are added as `parameters` and the payload bound. A construction writing `parameters`
  itself is refused (`refuseWrittenParameters`): the list before the body is what puts them in scope and what every
  check reads.
- **An error placeholder for an open declaration is an empty open record** (`SchemaResolver.unresolved`), so an
  application of it closes by the one process and reports nothing further.

## Parameters (`ParameterTypes`)

Each parameter records its name and its written bound (`meta.param`, §5.10). A parameter is a type, so where it
stands is only ever checked, never derived from.

- **Checked once, at the declaration.** `SchemaResolver` runs `ParameterTypes.check` once every declaration is ready
  and nothing has closed; a refused template is condemned to a placeholder, as an irregular one is. The kernel's
  bootstrap runs the same check, over nothing: the kernel declares no open record.
- **A bound names a type** (`requireType`): it resolves in the schema's namespace and names no constructor
  (`<T: unicode.text_type>` names one, IS-A `top`, which no argument can IS-A).
- **A parameter passed on is written with a bound that IS-A the callee's** (`Callees.passedOn`, over each open
  record's applications, `OpenEntry.applications`): `rebox => <T> { b: boxed<T> }` over `boxed => <T: text> { v: T }`
  is refused at `rebox`, naming `boxed`'s `T: text`, and `<T: int32>` for not being IS-A `text`. This holds wherever the
  parameter is an argument -- a field's type, a nested application, an applied parent -- and each declaration is
  judged against the bounds its callees *wrote*, so no order among templates is needed and nothing iterates. An
  imported template's recorded bound is what its author wrote, and binds the same way.
- **A constructor's slot needs no bound.** A head that is no open record -- construction sugar, `meta.map<K
  text>`, a form in place such as `{K => text}` -- has no parameters to bound; its slot judges the argument that
  arrives when the record is applied, and the applications nested in its arguments are judged in turn.
- **The call-site check** (`TemplateMaterialiser.checkArguments`): each argument must name a type that IS-A its
  parameter's bound, following alias chains. An application as an argument, or an unresolved name, falls to the
  substituted record.
  - **An application closed during resolution is checked once every declaration is ready.** A declaration naming one
    (`held => boxed<int32>`), a composition operand, a refinement source and an argument nested in any of them close
    in the driving loop. Judging an argument reads its chain, and an argument is a name at a type position, which
    orders nothing (§3.4.1): checking it then would make the declaration wait on it, and `a => boxed<b> & { … }`
    beside `b => boxed<a> & { … }` would become a wait cycle. So the materialiser defers each such check, with the
    declaration whose resolution ran it, and `checkDeferred` runs them after `ParameterTypes.check` -- one verdict
    per declaration, none for a declaration that already failed or a template already condemned.
