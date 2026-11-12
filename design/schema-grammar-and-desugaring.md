# Schema desugaring

Design notes for the AST→AST desugaring rewrite that runs between schema parsing and resolution: the sugar table,
the construction each form is written in place as, and how the phase reports. Current form only; history lives in git.
The schema grammar itself is in `design/schema-grammar.md`.

**Invariants**

- Every node not being rewritten is returned by identity — `declarationPositions()` is an `IdentityHashMap` — and a
  declaration that is rebuilt has its position re-registered against the node replacing it (`SchemaDesugarerTest`).
- **No declaration is added.** The declarations out are the declarations in: a sugar form is the construction it
  denotes, written in place where it stands, and nothing is lifted, named by its content or marked synthetic.
- At a declaration's own body a sugar form *is* the construction (`!meta.array { … }`, an `Instance`); at a field's or
  group member's type it is an `InlineConstruction` holding that binding record; nested inside a binding record it is
  the `{ of: !C { … } }` wrapper (`meta.type_inline`), written untagged, a type slot's choice being disjoint by class.
- `TypeRef.arguments` non-empty means an application, an open record's or a constructor's, which materialisation
  closes: a constructor's to the construction in place, an open record's into the declaration that names it.
- An argument of an application is a name or a parameter ([TSON-SCHEMA] §5.10): a sugar form there is refused, to be
  declared under a name and passed by it. A map's key is a name; the grammar admits no sugar form there.
- The phase is purely syntactic and consults no other schema: the sugar table is fixed, its targets the kernel's
  `meta.array`, `meta.map`, `meta.tuple` and `meta.choice` whatever a document imports, so the kernel's bootstrap needs
  no special case.
- An element, position or map-value `?` binds `voidable` directly; an unmarked one states nothing.
- An invalid sugar form is reported per declaration through `DesugarFailureReporter`, not thrown.
- `kind!` reaches this phase only at a field's or group member's type, the grammar admitting it nowhere else, and is
  written there as `!meta.dependent { on: kind }` in place, the same `InlineConstruction` a container is.

Related: `design/schema-grammar.md` (the parser producing the AST this rewrites),
`design/desugaring-open-forms-and-templates.md` (forms over a parameter, template bodies refused),
`design/template-materialisation.md` (materialisation), `design/open-records.md`, `design/schema-side-diagnostics.md`.

## Desugaring (`tson-compiler/.../resolver/SchemaDesugarer.java`)

An AST→AST rewrite between parsing and resolution. Every sugar form — `[T]` and the sized forms, `[T, U]`, `{K => V}`
(§5.3), `(A | B)` (§5.4) — becomes the `!C value` construction it denotes, and stays where it is written. So
`DefinitionResolver` sees three shapes at a use site: a bare reference, an application, or an `InlineConstruction`,
which it reads through its constructor's compiled reader as a declaration's `!C { … }` is (`inlineType`) and holds at
the field as a `TypeInline`. Reading it waits on the constructor and on what the read reaches, as reading any value
does ([TSON-SCHEMA] §3.4.1).

- **Purely syntactic, and per declaration — no other schema consulted.** The sugar set is closed and grammar-supplied,
  so the head each form desugars to and the vocabulary field each argument fills are a fixed table:

  | Source form | Binding record |
  |---|---|
  | `[T]` | `!meta.array { element_type: T }` |
  | `[T; N]` / `[T; N..M]` / `[T; N..]` / `[T; ..M]` | the same, plus `min_items`/`max_items` |
  | `[T?]`, `[T?; …]` | the corresponding form with `voidable: true` bound directly |
  | `[T, U, …]` | `!meta.tuple { elements: [{ element_type: T } { element_type: U }] }` |
  | `(A \| B)` | `!meta.choice { variants: [A B] }` |
  | `{K => V}` | `!meta.map { key_type: K  value_type: V }` |
  | `{K => V?}`, `{K => V?; …}` | the corresponding form with `voidable: true` bound directly |
  | `{K => V; N..M}` | the same, plus `min_items`/`max_items` |
  | `kind!`, at a field's type alone | `!meta.dependent { on: kind }` |

  A slot in the table holds what a type slot holds (`slotValue`): a bare token for a name, an application as the data
  grammar's reference (`WireForm.refValue`), and a nested form as `{ of: <its construction> }`. One spelling per shape,
  produced in one place.
- **A generic application passes through this phase.** `name<args>` resolves its head in the schema's one namespace
  (§3.3.1) — parameters, then locals, then the closure. A head that resolves to an open record is a §5.10
  application; one that resolves to a constructor is construction sugar (§5.3), so `meta.map<text, text>` is the
  construction `{text => text}` spells. Both are decided over the **resolved** form (`TemplateMaterialiser`,
  `design/template-materialisation.md`), where a head's chain is known. `checkTemplateApplication` refuses exactly one
  thing: a local head declaring *no* parameters whose body says it is no constructor. A head this document neither
  declares nor imports is the linker's unresolved reference, and a container sugar form over one of the enclosing
  template's own parameters is the application it spells, or refused (`design/desugaring-open-forms-and-templates.md`).
- **Identity is structural.** Two constructions written in place are one type when their constructor and fields are
  equal, compared as values (`TypeInline.equals`, which leaves out the application a record in place is named by and
  the annotations on its field keys), so `[T; 3]` and `[T; 3..3]`, and a radix-spelled bound and
  its decimal, are one construction. Nothing is named, so nothing is hashed. The constructor is held on the
  `TypeInline` beside its body (`TypeInline.constructor`, `@Unbound`: the tag on `of`, no field of the wrapper), the
  entry the construction applies past its alias hops, so `nonempty_type<uuid>` and `[uuid; 1..]` are two types though
  their bodies are equal, and `set<text>` is `meta.set_type`'s.
- **A name and a construction are never one type** (§8.2), even a name for the very construction written: a
  restated field's narrowing (`TsonSchemaLinker.narrows`) holds two constructions to equality and two names to IS-A,
  and refuses a name restated as its construction or the reverse, naming the inherited spelling to write; variant
  distinctness compares the same way. Nothing follows a name to its construction to compare it.
- **The variadic pair, `choice` and `tuple`, differ in what one position *is*.** A variant is a type slot
  (`variants: [meta.type_expr]`); an element is a `meta.tuple_element` record carrying a type **and** its own
  `voidable`, so each tuple position needs a record built for it. `voidable` is written only for a marked position.
  Nothing rides on trust: the emitted body binds through the constructor's compiled reader, where an undeclared member
  is `UNRECOGNIZED_FIELD`. §5.4's "each variant resolves to a distinct type" is the linker's
  (`TsonSchemaLinker.checkVariantsAreDistinct`, `design/choice-disjointness.md`), a variant written in place compared
  by its body.
- **A nested form desugars innermost-first**: `typeRef` recurses into each position, so the inner form is already its
  construction by the time the enclosing one is built, and the enclosing binding writes it in its wrapper. That reaches
  every nesting position alike — an array's element (`[[T]; 3]`), a tuple's positions (`[[T; 2], U]`) and a map's
  value (`{text => [order; 1..]}`) — to any depth.
- **The element `?` binds `voidable` directly.** `[T?]` becomes `!meta.array { element_type: T  voidable: true }`
  (§5.3); an unmarked element states nothing and lets §5.2's default injection supply `false`. A map's value takes the
  same `?` and binds the same field; the key takes none, §2.9 forbidding a void key.
- **The size specifier is one rule over the `min_items`/`max_items` pair, for arrays and maps alike**, each of the four
  spellings binding the pair directly, an exact `N` pinning both. §5.3's bound coherence (`min <= max`) is checked where
  the construction binds; a bound naming a parameter is refused here, naming the slot. A vacuous `[T; 0..]` is
  rejected here too: §5.3 makes `0..` a resolver error, the form meaning what the unbounded one means.
- **An invalid sugar form is reported per declaration, not thrown**, when a `DesugarFailureReporter` is supplied — the
  reportable forms are `SchemaValidationException`s (a vacuous `[T; 0..]`, an incoherent size range, an application of
  something that takes no type arguments, a form as an argument), and a library gap (`UnsupportedOperationException`)
  is reported too, as `NOT_IMPLEMENTED`. See `design/schema-side-diagnostics.md`.
- **Structural sharing is load-bearing, not an optimization.** Every node not being rewritten is returned by identity,
  because `TsonSchemaParser.declarationPositions()` is an `IdentityHashMap`. A declaration that contains sugar *is*
  rebuilt — any record with a single `[T]` field is rewritten whole — so the phase re-registers the original's position
  against the node replacing it, and a rebuilt field carries its own (`positions.carry`).
  `SchemaDesugarerTest.aRewrittenDeclarationKeepsItsSourcePosition` is the guard.
- **The kernel runs this phase too, with no accommodation at all** — it reads through itself, and with the table
  fixed there is nothing to look up.
