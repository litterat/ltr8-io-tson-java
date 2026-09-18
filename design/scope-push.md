# The scope push: `ScopedReader` and `ScopePush`

How a `scoped` position ([TSON-SCHEMA] §7.8) is read, and who may open a scope. Current form only; history lives in
git.

**Invariants**

- One reader serves all five scoped instances; what separates them is two constraint values and not a shape.
- The value's own shape picks the cell: a nested `!!schema` is EXTERN, a bare type-ref is LOCAL, and a value carrying
  neither is a **validation** error, not a resolver one.
- LOCAL is fixed at compile time; EXTERN is looked up through `ForeignSchemas` as the value arrives, and a schema
  nothing would supply is one of the five `SCHEMA_*` codes, never a verdict.
- There is no scope stack: the scope pops by returning.
- The containers leave a `SchemaRef` where it stands and ask `ScopePush.notAdmitted`; a position whose reader is not a
  scoped one has it consumed and refused.
- A schemaless document opens no scope at all (`ScopePush.refuseSchemaless`); both refusals report and keep reading.
- Tree mode keeps the push (`TsonScopedValue`); bind mode hands the object back as it is.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/record-dispatch.md`,
`design/name-hygiene-read-path.md`, `design/diagnostic-model.md`, `design/diagnostic-rules-and-messages.md`,
`design/processor-policy.md`, `design/schema-side-diagnostics.md`.

## The scope push ([TSON-SCHEMA] §7.8): `ScopedReader`, and `ScopePush` deciding who may open one

meta.tn's `scoped` constructor is the open sum: the value names its own type, and the instance's `scope`
names the namespaces that name may be drawn from (`LOCAL`, the governing schema and its imports; `EXTERN`, a
foreign schema the value names for itself). `schemas` narrows the foreign side. Core's three named instances
— `declared`, `extern`, `dynamic` — are the three subsets that have a name, and `extern_of<S>`/
`extern_type<S, T>` are per-use narrowings a schema applies rather than declares. **One reader serves all
five**, because what separates them is two constraint values and not a shape.

**The value's own shape picks the cell.** A value carrying a nested `!!schema` is EXTERN; one carrying a
bare type-ref is LOCAL; one carrying neither names no type, and a position that is open has nothing to infer
one from — a **validation** error, not a resolver one, and §7.8 says so outright of the extern cell ("the
discriminant is required"), the same holding at the other. A cell the instance's `scope` does not hold
refuses the value it would have taken, which is how `declared` refuses a push and `extern` requires one from
the one reader rather than from three.

**LOCAL is fixed at compile time and EXTERN is not.** A `scoped` entry belongs to exactly one schema, so
"the governing namespace" is that schema's and resolves through the same `TsonTypeReaderResolver` every other
dispatch uses. Which foreign schema an EXTERN value names is the document's choice, so it is looked up as the
value arrives, through `ForeignSchemas` — a read registry hands its own `get` to every compile it performs,
so a pushed schema shares that cache, that loader and that read mode with the schema that admitted it. A
schema nothing would supply is one of the five `SCHEMA_*` codes and never a verdict: it was never read.

**There is no scope stack, because the scope pops by returning.** The reader for the foreign type *is* the
foreign schema's compiled reader, wired to that schema's entries, so everything below the pushed value
resolves there by construction and everything after it resolves in the governing namespace again. Nothing
tracks a current scope, and nothing can leak one.

**Who may open a scope is `ScopePush`, and it is one decision rather than four.** `TsonDataStream` emits a
`SchemaRef` ahead of a record field value, a map entry value and an array element — the three positions
[TSON-DATA] §2.3 admits a directive at — and each of those containers used to consume it and throw it away,
which is how a document could push a scope its schema never opted into and be read as though it had not. They
now leave the event where it stands and ask `ScopePush.notAdmitted` on behalf of the position's own reader:
a scoped one keeps it, and everything else has it consumed and refused (§7.8's typed-position restriction —
"cross-schema acceptance is authored intent, not accident"). The check costs a document nothing, everything
in it being guarded by "is the next event a directive at all", which for every value in almost every document
it is not. The refusal is reported against the position's own context rather than the container's, so it
names the field or the index; `notAdmitted` and `refuse` are split for exactly that reason, so a scoped copy
of the context is built only where there is something to locate.

**A schemaless document opens no scope at all** (`ScopePush.refuseSchemaless`), which is §7.8's own rule: "a
nested `!!schema` in a document with no `!!schema` of its own is a validation error naming the directive". The
typed-position restriction exists because acceptance is authored intent, and a
Class 1 document states no intent to opt in to anything; honouring the directive there turns a Class 1 read
into a Class 2 read halfway down a document with nothing on the document saying so. Both refusals report and
keep reading — the directive is consumed and the value read as it would have been without one — so a stray
directive costs one diagnostic rather than a value.

**Tree mode keeps the push and bind mode cannot.** `scoped` is where a document says which schema a value
belongs to, and a tree that dropped it could not be written back, so tree mode wraps an EXTERN value in a
`TsonScopedValue`. A bound object has nowhere to carry a URI and inventing somewhere would change what a
consumer's own class means, so bind mode hands the object back as it is — the same asymmetry `TsonAbsent`
already makes for [TSON-DATA] §2.9.
