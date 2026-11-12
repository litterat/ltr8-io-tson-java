# Desugaring: forms over a parameter, and template bodies

Design notes for the half of `SchemaDesugarer` that deals with type parameters: what a sugar form over a parameter
becomes inside an open record (`openForm`), and which template bodies are refused before anything resolves
(`refuseTemplateBody`). Current form only; history lives in git. The sugar table and the naming rule these bullets refer
to are in `design/schema-grammar-and-desugaring.md`; what an open record is, in `design/open-records.md`.

**Invariants**

- **A template's body is a record body** ([TSON-SCHEMA] §5.10): braces, a composition or a refinement, which pass through
  as they would without parameters, for `DefinitionResolver` to build as an open record. A parameter list before a
  sugar form or a bare reference is refused here, naming what to write instead (`refuseTemplateBody`); before a
  construction (`<T> !C { … }`) it passes through, `DefinitionResolver.openInstance` admitting `!meta.record` alone,
  since only the resolver knows what the head is.
- **Inside an open record a form over a parameter is admitted only where it is a reference** (`openForm`): `[T]` is
  written as the application `meta.array<T>` and `{K => V}` as `meta.map<K V>`, held as written until the record is
  applied. A size or a `?` element (`[T; 1..]`, `{K => V; ..10}`, `[T?]`), a tuple (`[T, text]`) and a choice (`( T |
  error )`) over a parameter are refused at the declaration, naming the form; a choice's message points at field
  groups.
- **A form over a parameter is a reference or an error.** A form naming none of the declaration's parameters is
  written in place as its construction, as it would be outside a template (§5.3); a form naming one is held as the
  application it spells, or refused.
- **A size bound naming one of the declaration's parameters is refused** (`refuseParameterBound`), naming the slot,
  before the form is built with a parameter in it: a bound is a count, never a type.
- `SchemaDesugarer.slotValue` is the single place a type slot's wire shape is decided -- a name, an application
  through `WireForm.refValue`, or a `{ of: … }` wrapper -- one producer, one spelling.
- A refused declaration is replaced with the absorbing stand-in, an open empty record keeping its parameters
  (`absorbed`), so an application of it closes against an empty body and says nothing further.

Related: `design/schema-grammar-and-desugaring.md` (the sugar table, reporting),
`design/schema-grammar.md`, `design/template-materialisation.md` (how a held reference closes),
`design/open-records.md`, `design/references.md` (a reference carrying arguments),
`design/constructor-application.md` (construction sugar).

## Forms over a parameter (`openForm`)

- **The test is whether the form is a reference.** `container` expands a form's positions first, innermost-first, then
  asks whether any of them names a parameter of the enclosing declaration (`parameterIn`, through application
  arguments at any depth). If none does, the form is written in place by the table. If one does, `openForm` decides.
- **`[T]` and `{K => V}` are the grammar's spelling of a construction** (§5.3's sugar table, construction sugar), so
  over a parameter they are written as the construction's own application: `[box<T>]` becomes `meta.array<box<T>>`,
  `[[T]]` becomes `meta.array<meta.array<T>>` (the inner form already an application by the time the outer is built),
  `{text => T}` becomes `meta.map<text T>`. The open record holds that reference as written, a `ParamRef` among its
  arguments, and when the record is applied the materialiser constructs it in place -- the same construction the
  sugar written over the argument is (`design/template-materialisation.md`).
- **A size or a `?` sets a field no argument list carries.** `meta.array<T>` fills `element_type` alone; admitting
  `[T; 1..]` would take a refined constructor nobody wrote, and would leave the line between it, `{text => T}` and `( T
  | text )` to be argued form by form. The author writes the constructor (`nonempty_type => meta.array ^ { min_items?:
  = 1 }`, then `nonempty_type<T>`) or moves the container into the argument; the message says both.
- **A tuple or a choice is no constructor over reference fields.** Their positions are a list, so construction sugar
  does not reach them, and an untagged sum over a parameter cannot be judged disjoint until it is applied (§5.4). A
  labelled alternative inside the record is a field group, `( body: T | problem: problem )`.
- **A record in place over a parameter is a gap, not a reference** (`recordInPlace`): §5.10 substitutes it when its
  open record is applied, and substitution does not yet reach inside a type written in place, so it is reported
  `NOT_IMPLEMENTED`, naming the open record to declare instead. One naming no parameter is closed, as anywhere. As an
  argument it is refused before it is expanded, so the verdict is the argument rule's, never the gap.

## Template bodies (`refuseTemplateBody`)

- **Before a sugar form**: `<T> [T]` and `<V> {text => V}` are constructions, written where they are used
  (`meta.array<T>`, `[T]`) or declared as a refined constructor and applied; `<T> ( T | error )` is a discriminated
  record family or a field group; `<A, B> [A, B]` an open record's fields, or one tuple per type.
- **Before a bare reference** -- an alias template, `uuid_pair => <B> pair<uuid, B>` or `ident => <T> T` -- the body is
  no record, and names a partial application nothing could close (§5.10: there is no partial application). The
  remedy is restating the body as a record or writing the application at each use.
- **A record body is not rewritten.** `<T> { x: T }` leaves this phase as the `StructuralTypeDef` it was parsed as, its
  closed forms written in place and its forms over a parameter written as references, and the resolver builds it the way it
  builds a closed record (`DefinitionResolver.resolveRecordBody`). The explicit `<T> !meta.record { … }` passes through
  as the `Instance` it is. §5.11's uniqueness rule and the modifier-only refusal are therefore asked once, by the
  resolver, for a template and the closed record beside it alike (`FieldModifiers`).
- **An application passes through.** `box<text>` keeps its head and arguments for materialisation; the one thing
  asked here is that a local head declaring no parameters, with a body no constructor has, takes no arguments
  (`checkTemplateApplication`).
