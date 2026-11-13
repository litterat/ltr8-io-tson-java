# Desugaring: forms over a parameter, and template bodies

Design notes for the half of `SchemaDesugarer` that deals with type parameters: what a sugar form over a parameter
becomes inside an open record, and which template bodies are refused before anything resolves (`refuseTemplateBody`).
Current form only; history lives in git. The sugar table and the naming rule these bullets refer to are in
`design/schema-grammar-and-desugaring.md`; what an open record is, in `design/open-records.md`.

**Invariants**

- **A template's body is a record body** ([TSON-SCHEMA] §5.10): braces, a composition or a refinement, which pass through
  as they would without parameters, for `DefinitionResolver` to build as an open record. A parameter list before a
  sugar form or a bare reference is refused here, naming what to write instead (`refuseTemplateBody`); before a
  construction (`<T> !C { … }`) it passes through, `DefinitionResolver.openInstance` admitting `!meta.record` alone,
  since only the resolver knows what the head is.
- **Inside an open record a form is its construction in place, whether or not it names a parameter.** The desugarer
  never asks whether a position names one: a parameter is a name at a type slot, which `RecordBody` scopes as a
  `ParamRef` at any depth and `Substitution` replaces when the record is applied (`design/template-materialisation.md`).
  So `[T; 1..]`, `[T?]`, `[T, text]`, `( T | int32 )`, `{T => int32}` and `{ cursor: T }` are each the construction the
  table gives, with no rule of their own.
- **A size bound naming one of the declaration's parameters is refused** (`refuseParameterBound`), naming the slot: a
  bound is a count, never a type, and substitution reaches type slots alone.
- **A sugar form as an argument is refused, over a parameter as over a name** (`applicationArguments`): `box<[T]>` and
  `meta.array<[T]>` alike. An argument is a name or a parameter (§5.10); declare the form and pass its name.
- `SchemaDesugarer.slotValue` is the single place a type slot's wire shape is decided -- a name, an application
  through `WireForm.refValue`, or a `{ of: … }` wrapper -- one producer, one spelling.
- A refused declaration is replaced with the absorbing stand-in, an open empty record keeping its parameters
  (`absorbed`), so an application of it closes against an empty body and says nothing further.

Related: `design/schema-grammar-and-desugaring.md` (the sugar table, reporting),
`design/schema-grammar.md`, `design/template-materialisation.md` (substitution and closing),
`design/open-records.md`, `design/references.md` (a reference carrying arguments),
`design/constructor-application.md` (construction sugar).

## Why a form over a parameter is not special

- **The alternative that lost: spell the form as its constructor's application.** `[T]` written as `meta.array<T>`
  and closed when the record is applied needs no substitution inside a type in place, but an argument list carries
  type slots only. Every form with another part -- a size, a `?`, a tuple's positions, a choice's variants, a record's
  fields -- then needs a rule of its own, refused or argued form by form, and a record in place over a parameter has
  no application to spell at all.
- **Substitution into a type in place removes the whole class.** One walk over type slots (`schema.meta.TypeSlots`)
  scopes, substitutes and closes, so the sugar means here what it means everywhere else, and the resolved form of an
  open record writes the construction it denotes.
- **A choice over a parameter is judged where it is applied.** Its variants are known only then, so `( T | int32 )`
  loads at the open record and `pick<int32>` is refused at its own declaration for listing `int32` twice (§5.4), as
  the closed choice would be.
- **An explicit constructor's application over a parameter is its construction too, one phase later**
  (`meta.array<T>`, `set<T>`, `meta.ref<T>`): the desugarer cannot tell a constructor from an open record, so it
  passes the application through, and the resolver rewrites it to its construction where it is first met, before it
  is read (`ConstructionRewrite`). The sugar and the explicit spelling are one resolved form, and an application
  left in resolved output always applies an open record.

## Template bodies (`refuseTemplateBody`)

- **Before a sugar form**: `<T> [T]` and `<V> {text => V}` are constructions, written where they are used
  (`meta.array<T>`, `[T]`) or declared as a refined constructor and applied; `<T> ( T | error )` is a discriminated
  record family or a field group; `<A, B> [A, B]` an open record's fields, or one tuple per type.
- **Before a bare reference** -- an alias template, `uuid_pair => <B> pair<uuid, B>` or `ident => <T> T` -- the body is
  no record, and names a partial application nothing could close (§5.10: there is no partial application). The
  remedy is restating the body as a record or writing the application at each use.
- **A record body is not rewritten.** `<T> { x: T }` leaves this phase as the `StructuralTypeDef` it was parsed as, its
  forms written in place, and the resolver builds it the way it builds a closed record
  (`DefinitionResolver.resolveRecordBody`). The explicit `<T> !meta.record { … }` passes through as the `Instance` it
  is. §5.11's uniqueness rule and the modifier-only refusal are therefore asked once, by the
  resolver, for a template and the closed record beside it alike (`FieldModifiers`).
- **An application passes through.** `box<text>` keeps its head and arguments for materialisation; the one thing
  asked here is that a local head declaring no parameters, with a body no constructor has, takes no arguments
  (`checkTemplateApplication`).
