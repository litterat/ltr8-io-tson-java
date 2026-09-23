# Schema desugaring

Design notes for the AST→AST desugaring rewrite that runs between schema parsing and resolution: the sugar table,
injected entries and their names, and how the phase reports. Current form only; history lives in git.
The schema grammar itself is in `design/schema-grammar.md`.

**Invariants**

- Every node not being rewritten is returned by identity — `declarationPositions()` is an `IdentityHashMap` — and a
  declaration that is rebuilt has its position re-registered against the node replacing it (`SchemaDesugarerTest`).
- `TypeRef.arguments` non-empty means an open form, a template application; everything closed is an entry referenced by
  a bare name, so `DefinitionResolver` only ever sees a bare reference or `!C value`.
- The phase is purely syntactic and consults no governing meta: the sugar table is fixed, so meta-kernel's bootstrap
  needs no special case.
- An injected name derives from the resolved binding record, never the spelling and never the AST's own `toString` or
  `hashCode`; `SchemaDesugarerTest` pins two derived names to exact strings.
- A form an `!!import` already declares is referenced, not redeclared — and is not marked `@synthetic` by this document.
- An element, position or map-value `?` binds `state` directly; an unmarked one states nothing; a map key takes no `?`.
- An invalid sugar form is reported per declaration through `DesugarFailureReporter`, not thrown.

Related: `design/schema-grammar.md` (the parser producing the AST this rewrites),
`design/desugaring-open-forms-and-templates.md` (open lifts, parameterised aliases, record templates),
`design/template-materialisation.md` (materialisation, the synthetic merge), `design/schema-side-diagnostics.md`.

## Desugaring (`tson-compiler/.../resolver/SchemaDesugarer.java`)

An AST→AST rewrite between parsing and resolution. Every sugar form — `[T]` and the sized forms, `[T, U]`,
`{K => V}` (§5.3), `(A | B)` (§5.4) — becomes the `!C value` construction it denotes: at declaration position
it simply *is* that construction, and anywhere else (a field, an element, a variant, a map value) it becomes
an **injected declaration plus a bare reference to it**. So `DefinitionResolver` only ever sees two shapes: a
bare reference or `!C value`. §5.3/§5.6 already *describe* these forms as desugarings and §3.3.1 calls their
targets "the implicit desugar targets of the sugar forms" — this implements that literally, in one phase,
rather than splitting it across the resolver (declaration position) and the linker (field position).

**The injected-entry half is the spec's own rule.** §8.2: "**Every application materialises** ... Nothing is
carried structurally in place: a use site holds a bare reference to its entry", and the materialised entries
merge under `!!import` by the same structural identities they have within a schema. Two rules make it so:
§4.2's `array`/`set`/`map` constructors are parameterless, so a container at a use site cannot be an
application at all — nothing in meta-kernel takes type parameters, `map` holding `key_type`/`value_type` as
ordinary fields — and §5.3 states one lift rule, every sugar form lifting at desugar, a concrete form to a
closed synthetic entry. `ResolvedFixtureTest` asserts the resolved fixtures and this output agree entry for
entry.

**The rule this settles on:** `TypeRef.arguments` non-empty means an **open** form — a template application,
whose arguments are what materialisation substitutes. Everything closed is an entry, referenced by a bare
name. Its counterpart at the body is a held body present ⟹ open entry, and together the two make the closed-entry
rule checkable structurally, with no vocabulary needed to read a `type_ref`. One direction only: a partial
application (`<B> pair<uuid, B>`) is a template that holds nothing, keeping the `type_ref` with arguments it
already resolves to.

The alternative — inline sugar riding as a structural `type_ref` rather than as an injected entry, with the
compiler building readers from those refs — is **deliberately not implemented**. Four arguments stand for it
and none holds:

- *An entry set wider than the declaration set is untidy.* It is already normal — `subtypes` and `disjoint`
  are resolver-derived too, so §8 output has never been the author's declarations and nothing else.
- *It would avoid needing a `@synthetic` marker.* That marker is an optional display hint for tooling
  folding entries back into nested form; needing one is not a reason to restructure the representation.
- *Ingest gets simpler.* Speculation about code that does not exist yet, against machinery that works.
- *Derived names leak across `!!import`.* They must be stable **within** an implementation, including across
  that boundary — which is exactly what the naming below guarantees — never agreed **between** them; §8.2
  disclaims the names, and a comparison tool canonicalises. Nor do they reach an author: a read diagnostic
  reports the path taken (`/holder/xs`), never the leaf it resolves to.

Two arguments run the other way. A second representation of a nested form forces every consumer to walk two
representations, which is precisely what a structural `type_ref` would impose on every container. And the
deduplication would not disappear, only relocate: `[text]` in five records must not
compile five readers, so the compiler would need a memo keyed on ref structure, which is the naming below
rebuilt and called a cache.

- **Purely syntactic, and per declaration — no governing meta.** The sugar set is closed and
  grammar-supplied, so the head each form desugars to and the vocabulary field each argument fills are a
  fixed table:

  | Source form | Binding record |
  |---|---|
  | `[T]` | `!array { element_type: T }` |
  | `[T; N]` / `[T; N..M]` / `[T; N..]` / `[T; ..M]` | the same, plus `min_items`/`max_items` |
  | `[T?]`, `[T?; …]` | the corresponding form with `state: OPTIONAL` bound directly |
  | `[T, U, …]` | `!tuple { elements: [{ element_type: T } { element_type: U }] }` |
  | `(A \| B)` | `!choice { variants: [A B] }` |
  | `{K => V}` | `!map { key_type: K  value_type: V }` |
  | `{K => V?}`, `{K => V?; …}` | the corresponding form with `state: OPTIONAL` bound directly |
  | `{K => V; N..M}` | the same, plus `min_items`/`max_items` |

  The constructors being parameterless (§4.2), the table above is the whole rule: `SchemaResolver` threads no
  governing-meta entries into the phase, and **meta-kernel's bootstrap needs no special case** — routing read
  off the governing meta would, for the three constructors meta-kernel applies to itself, have to come from
  the very entries it is in the middle of producing.
- **A generic application is a user template, and this phase mostly leaves it alone.** `name<args>` resolves
  its head through the type-name namespace only (§3.3.1) — parameters, then locals, then imports — so
  `map<text, text>` finds nothing and is an ordinary unresolved reference for the linker to report, and
  anything that *does* resolve is a §5.10 template. Substitution happens over the **resolved** form
  (`TemplateMaterialiser`, `design/template-materialisation.md`), not over the AST, so an application passes through
  here with its head and arguments intact. `checkTemplateApplication` refuses exactly one thing: a local
  head declaring *no* parameters, the author's error — nothing there takes type arguments. A head this
  document neither declares nor imports is the linker's unresolved reference, and a template whose body writes
  a container sugar form over one of its own parameters lifts open
  (`design/desugaring-open-forms-and-templates.md`).
- **Identity is the resolved binding record, not the spelling.** The injected name is
  `head_value_value_hash`, derived from the record the form desugars to, so `[T; 3]` and `[T; 3..3]` land on
  the same entry and any two structurally identical forms anywhere in the document collapse to one
  declaration (§8.2's structural-equality rule). A form an `!!import` already declares is **referenced, not
  redeclared**, which is why the phase takes the imported name set (meta.tn repeats several of meta-kernel's
  forms; redeclaring would be a local-vs-import collision). The hash half runs over a rendering the phase
  builds itself — one tag per value shape, records and arrays recursed, author text written length-first —
  and **never over the AST's own `toString`**, whose format the JDK documents as subject to change and which
  moves whenever a record's components are renamed; the records' `hashCode` is worse still, being free to
  differ between two runs of the same application. `SchemaDesugarerTest` pins two derived names to exact
  strings, because a change to them is a change to the resolved form of every schema.
- **The variadic pair, `choice` and `tuple`, differ in what one position *is*.** A variant is a bare
  `type_ref`; an element is a `tuple_element` record carrying a type **and** its own `ElementState`, so each
  tuple position needs a record built for it. `state` is written only for an `OPTIONAL` position — the member
  is defaulted (`state: element_state ~ REQUIRED`), so a `REQUIRED` one is spelled by omitting it,
  as every other defaulted vocabulary field is. Nothing rides on trust: the emitted body binds through the
  governing meta's compiled reader, where an undeclared member is `UNRECOGNIZED_FIELD` under §7.2's closure.
  §5.4's "each variant resolves to a distinct type" is deliberately not checked here — it is a question about
  what names *resolve to*, at the end of each variant's reference chain, which has no answer until the whole
  namespace exists, imports merged. `TsonSchemaLinker.checkVariantsAreDistinct` asks it
  (`design/schema-resolution.md`).
- **Both declaration-level tiers desugar in place.** At declaration position the form *is* the construction
  (`pair => [integer, text]` becomes `!tuple { … }`, like `ids => [text]`, `entries => {text => integer}` and
  `contact => (A | B)`); inline, each is hoisted into its own declaration and referenced.
- **A nested form desugars innermost-first**, and needs no machinery of its own: an element holds a
  `TypeRef`, so `typeRef` recurses into it and the inner form is already a plain name by the time the
  enclosing one is built. That reaches every nesting position alike — an array's element (`[[T]; 3]`), a
  tuple's positions (`[[T; 2], U]`) and a map's value (`{text => [order; 1..]}`) — to any depth, with no
  per-depth case and no second walk, there being one node family for a container wherever it stands.
  Because identity is structural, the injected entry is shared: one `array_integer_<hash>` serves the nested
  position, the flat declaration `[integer]` and an inline field's `[integer]` alike. An injected **tuple**'s
  name derives from its positions' *states* as well as their types, or `[T, U?]` and `[T, U]` would land on
  one entry.
- **The element `?` binds `state` directly.** `[T?]` becomes `!array { element_type: T  state: OPTIONAL }` —
  §5.3's "elements at any position MAY be the absent sentinel `_`; absent elements occupy positional slots".
  It has no parameter to route through, which is why §5.3 gives the `?` forms no template route. An unmarked
  element states nothing and lets §5.2's default injection supply `REQUIRED`, exactly as a REQUIRED
  tuple position omits its own `state`. The state reaches the derived name too, or `[T?]` and `[T]` collide on
  one injected entry. `[T?; 3]` — the form §5.3 states the rule through — puts the state and both bounds on
  one binding record, which is the shape the whole table is written in. On the read side `ArrayAbstractReader`
  admits `_` under `ElementState.OPTIONAL` and counts it toward the bounds. **A map's value takes the same
  `?`** and binds the same field — `map` carries an `element_state` for it (§5.3's `{K => V?}` row) — so
  `{K => V}` means what `[T]` means and an author who wants absence writes it. The *key* takes none: §2.9
  forbids an absent key outright, so there is no state for a marker to bind.
- **The size specifier is one rule over the `min_items`/`max_items` pair, for arrays and maps alike.** There
  is no template in between — the kernel declares no size template — and each of the
  four spellings binds the pair directly, an exact `N` pinning both. §5.3's bound coherence (`min <= max`) is
  checked here, where the bounds are literal at schema load; a bound naming a value parameter is
  materialisation's question. The rejection of a **vacuous `[T; 0..]`** is here too: §5.3 makes `0..` a
  resolver error, because structural identity (§8.2) makes it an entry
  *distinct from* `[T]` that means the same thing, and the diagnostic SHOULD say so. Only a literal `0` is
  caught.
- **An invalid sugar form is reported per declaration, not thrown**, when a `DesugarFailureReporter` is
  supplied — `SchemaResolver` always supplies one on its reporting overload, so the phase joins resolution
  and linking in reporting every independent problem in one pass. The reportable forms are
  `SchemaValidationException`s: a vacuous `[T; 0..]`, an incoherent size range, and an application of something
  that takes no type arguments. **A library gap (`UnsupportedOperationException`) is reported too**, as
  `NOT_IMPLEMENTED` rather than as an author error — thrown, it would take every other declaration's verdict
  with it. See `design/schema-side-diagnostics.md` for the code split, the placeholder and the no-rollback
  rule.
- **Structural sharing is load-bearing, not an optimization.** Every node not being rewritten is returned
  by identity, because `TsonSchemaParser.declarationPositions()` is an `IdentityHashMap` — an
  equal-but-rebuilt `Declaration` silently loses its position, and the diagnostics that report against it.
  `SchemaDesugarerTest` asserts `assertSame` for exactly this reason.
  - **Sharing alone is not enough, and `schemaMap` carries positions across the rewrites it cannot avoid.**
    A declaration that genuinely contains sugar *is* rebuilt — any record with a single `[T]` field is
    rewritten whole, which is the common case, not a corner — so the phase re-registers the original's
    position against the node replacing it. Without that half, a read diagnostic anchored on the enclosing
    record (which is every read diagnostic about one of its fields) loses its line. The identity-keyed
    position map is threaded in and mutated in place rather than rebuilt by the caller, so there is one map
    and no two-hop lookup; `SchemaDesugarerTest.aRewrittenDeclarationKeepsItsSourcePosition` is the guard.
    An *injected* declaration still has no position, correctly — it has no source text of its own.
- **Every entry it lifts is a *synthetic* entry, and is marked as one.** `SchemaDesugarer.lifted(original,
  desugared)` is the set difference between the two documents, and that set is exactly what §8.2's derived
  `@synthetic` marker goes on — attached at the schema-map key by the caller, not here, since this phase
  deals in AST and the marker belongs to resolved output (`design/resolver-vocabulary-and-bootstrap.md`). A set difference
  rather than a field on the pass, because `hoist` deliberately does *not* inject a form an `!!import`
  already declares: that entry is the same form resolved by the schema that owns it, and marking it here
  would put this document's derived marker on someone else's key.
- **The meta-kernel runs this phase too, with no accommodation at all** — its governing meta is itself, and
  with the table fixed there is nothing to look up.
