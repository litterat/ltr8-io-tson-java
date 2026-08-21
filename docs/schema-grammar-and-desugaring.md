# Schema grammar and desugaring

Design notes for the first two schema-pipeline phases: parsing a schema document into a `SchemaDocument`,
and the AST→AST desugaring rewrite that runs before resolution. Current form only; history lives in git.
`CLAUDE.md` holds the one-paragraph orientation; this file holds the detail.

## Schema grammar (`tson-compiler/TsonSchemaParser.java`, `.../ast/schema/`)

`TsonSchemaParser` parses a schema document body (Part 2 §2.1, §5, §12.1) into a `SchemaDocument` — the
schema-grammar analogue of `Document`. It is **grammar-only**: no namespace resolution, no
materialization, no validation (those are the resolver's/linker's jobs).

- **`extends TsonDataParser`, same package** — §12.1 imports Part 1's `annotation`/`data-value`/directive
  grammar directly, so `TsonSchemaParser` calls straight into package-private methods rather than
  re-implementing them, adding only the schema-only tokens (`~ ^ & | ( ) < > ? ; -`).
- **`SchemaMap.declarations` is a `Map<String, Declaration>`** (a `LinkedHashMap`, insertion order
  preserved) — §3.4.1's Pass 1 shape and the schema's own `{type_name => type_definition}`. A duplicate
  name overwrites, same "grammar layer doesn't dedupe" treatment the data grammar gives duplicate fields.
- **Three spec defects implemented per intent, not per letter** (each with an AST reshape and a
  `SPEC-FEEDBACK.md` entry): `instance`'s ABNF says `data-value` but the intended production is the
  narrower `core-value` (#16 — `Instance` wraps a `DataValue` with `typeRef` pre-set, no separate
  `target`); `construction-def`'s ABNF doesn't admit the implicit `&` before its trailing `record-def`
  (#14); `field-modifier`'s value is a bare token or the absent sentinel, not a full `data-value` (#15).
  `atom-refinement` has the same `data-value`-vs-`core-value` defect as `instance` but is left as-is (its
  own note in #16). An unquoted non-numeric type-argument always parses as a type reference, never a value
  literal — a deliberate grammar-layer deferral, classified at a later semantic layer.
- **A `!` head behind a parameter list is an `instance-template`, a production of its own** (§12.1) --
  `vector => <T, N> !array { element_type: T  min_items: N }`. Not `[type-params] instance`: the surface
  syntax is the same, but the two payloads resolve against different vocabulary, an `Instance` binding
  through the *constructor's* own reader while this yields an `instance_template`. `Instance` stays
  unparameterised, so nothing gains an optional parameter list that could be silently dropped, and
  `parseTypeDef` reads the parameter list *before* dispatching on `!` — one token then decides.
  - **The payload is narrower than a `core-value`**, mirroring `template_argument` one-for-one: a name, a
    name with arguments, or a literal. A `core-value` would admit an array payload, a scalar payload and a
    nested record in a binding, none of which the resolved form can carry — it has no collection case, so a
    parameter inside a collection-typed slot has no resolved shape at all. `element_type: [T]` is therefore
    a parse error, not a form that quietly reads `[T]` as a data array of one token.
  - The **resolved** form does not exist yet, so `DefinitionResolver` refuses one by name rather than
    dropping its parameters into an ordinary construction.
- **Two entry points, one grammar.** `parseSchemaDocument()` is fail-fast; `parseSchemaDocument(receiver)`
  reports each *declaration's* syntax error and resynchronises to the next, handing back no document at all
  if it reported anything. The mechanics, the resync rule and the two failures that stay fail-fast are in
  `docs/readers-and-diagnostics.md` under "Schema-side diagnostics", with the rest of the diagnostics model.
- **A mismatch names the construct the position admits, not the token class** — `expect` takes that
  construct in the author's voice, and every call site here is phrased that way (`"a record field's ':'"`,
  `"a choice type's closing ')'"`), never as the enclosing construct. One position goes further and names
  the *fix*: an inline atom refinement or constructor application (`quantity: !integer ^ { min: 1 }`),
  rejected at a type-ref position with the "declare a named type and reference it by name" correction
  (§5.3). It used to have two companions, for an element `?` and a size specifier at a type-ref position;
  both are legal there now and the diagnostics went with the restriction.
- **One production per container, reachable from `type-ref`** — `ArrayRef`, `TupleRef`, `MapRef`, each
  admitting a size specifier after `;` and an element `?` at *every* position. The grammar used to spell
  each twice, a declaration-level form admitting both and an inline form admitting neither, with a prose
  tie-break in §12.1 because `type-def` was otherwise ambiguous between them. The split existed because a
  sized form had no inline representation to carry it; every form lifts to an entry, so it protected
  nothing (`SPEC-FEEDBACK.md` #31, and the change report's D10). `type-def` reaches both through `type-ref`
  like anything else, so the tie-break disappeared rather than being reworded.
  - **Nesting is the recursion in `ElementType`**, which holds a plain `TypeRef` — `[[T; 2]; 3]` and
    `{text => [order; 1..]}` need no second node family, which is what `ElementType.Expr.Nested` used to be.
  - **An element's `?` and a field's own `?` cannot collide**: a field is `field-name ":" type-ref ["?"]`,
    so in `xs: [T?]?` the inner belongs to `element-type` and the outer to the field.
  - A map key stays `type-name ["<" type-args ">"]` and nothing else — not a paren type, not a bracket form
    — which is what holds the brace dispatch below to its lookahead budget; a composite key earns a named
    declaration and the explicit `!map { key_type: … }` form.
- **The map sugar is parsed twice for the same reason** — `MapContainerDef` at declaration position (which
  admits a `; size-spec`), `InlineMapRef` at type-ref position (which does not). `map-value = container-def
  / type-ref`, the same pair an array element position takes, so it reuses `ElementType.Expr` and the
  declaration-level tier nests inside a map value to any depth (`{text => [order; 1..]}`,
  `{text => {text => integer}}`). The key is `type-name ["<" type-args ">"]` and nothing else — not a paren
  type, not a bracket form — which is what holds the dispatch below to its lookahead budget; a composite key
  earns a named declaration and the explicit `!map { key_type: … }` form.
- **`{` at a type position dispatches by consuming one token and inspecting** (`braceTypeDef`/
  `braceOpensMap`) — [TSON-DATA] §2.8's record/map idiom, imported wholesale into the schema grammar as
  §12.2 asks. `}`, `(` (a leading field group) and `@` (annotations, which the map sugar admits nowhere
  inside its braces) commit to a record; a name followed by `=>`, or by `<` opening a generic key's
  arguments, commits to a map; a name followed by anything else is a record whose field is missing its `:`.
  Everywhere *except* type-def position a `{` is a map and only a map, since a bare record body is not
  spellable at a type position (§5.2) — `requireMapBrace` says which of the two constructs the author
  reached for rather than reporting an unexpected token.
  - **A record body stays a record body wherever the grammar already fixed one** — a refinement body, a
    composition tail, a constructor vocabulary — so `config ^ {text => text}` fails at the `=>`, named as
    "a record body's entries are `name: type`; `=>` begins a map type only where a type is expected".
  - **One consequence of the dispatch is worth knowing before you write the test.** `{text? => integer}` is
    `name` followed by `?`, so it commits to a *record* before the `=>` is read, and the author gets a
    record-field diagnostic — while `{pair<text>? => integer}`, whose `<` commits to a map first, gets the
    map rule. Both are rejected; only one of them mentions maps. `SPEC-FEEDBACK.md` #52 argues the change
    report should state this where it states the dispatch, and why buying the better message with a third
    token of lookahead is a bad trade.

## Desugaring (`tson-compiler/.../resolver/SchemaDesugarer.java`)

An AST→AST rewrite between parsing and resolution. Every sugar form — `[T]` and the sized forms, `[T, U]`,
`{K => V}` (§5.3), `(A | B)` (§5.4) — becomes the `!C value` construction it denotes: at declaration position
it simply *is* that construction, and anywhere else (a field, an element, a variant, a map value) it becomes
an **injected declaration plus a bare reference to it**. So `DefinitionResolver` only ever sees two shapes: a
bare reference or `!C value`. §5.3/§5.6 already *describe* these forms as desugarings and §3.3.1 calls their
targets "the implicit desugar targets of the sugar forms" — this implements that literally instead of
splitting it across the resolver (declaration position) and the linker (field position), which is what it
replaces.

**The injected-entry half is a deliberate divergence** — §8.2 says a constructor application never
materialises an entry and is carried structurally at the use site; declaration position, where the spec
agrees, is correct. It is argued as spec feedback rather than tracked as debt (`SPEC-FEEDBACK.md` #49/#50,
and #51 for the `!!import` visibility that rides on it), and those entries stay open on purpose, as
discussion points for the revision.

**The rule this settles on:** `TypeRef.arguments` non-empty means an **open** form — a template application,
whose arguments are what materialisation substitutes. Everything closed is an entry, referenced by a bare
name. Its counterpart at the body is `instance_template` present ⟺ open entry (D7), and together the two
make the closed-entry rule checkable structurally, with no vocabulary needed to read a `type_ref`.

`spec/tson-cr-structure-templates.md` D8 proposed the opposite — inline sugar riding as a structural
`type_ref` rather than as an injected entry, with the compiler building readers from those refs — and is
**deliberately not implemented**. Four arguments were weighed for it and none holds:

- *An entry set wider than the declaration set is untidy.* It is already normal — `subtypes` and `disjoint`
  are resolver-derived too, so §8 output has never been the author's declarations and nothing else.
- *It would avoid needing a `@synthetic` marker.* That marker is an optional display hint for tooling
  folding entries back into nested form; needing one is not a reason to restructure the representation.
- *Ingest gets simpler.* Speculation about code that does not exist yet, against machinery that works.
- *Derived names leak across `!!import`.* They must be stable **within** an implementation, including across
  that boundary — which is exactly what the naming below guarantees — never agreed **between** them; §8.2
  disclaims the names, and a comparison tool canonicalises. Nor do they reach an author: a read diagnostic
  reports the path taken (`/holder/xs`), never the leaf it resolves to.

Two arguments run the other way. The change report's own D7 rejects a second representation of a nested form
because it "forces every consumer to walk two representations" — precisely what D8 would impose on every
container. And the deduplication would not disappear, only relocate: `[text]` in five records must not
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
  | `{K => V; N..M}` | the same, plus `min_items`/`max_items` |

  The phase used to read that routing off the governing meta — constructors carried parameter lists and each
  vocabulary field named the parameter it drew from (`element_type: type_ref = T`), so `map<K, V>` zipped
  arguments against `map`'s own `parameters()`. With the constructors parameterless (the change report's D3)
  the table above is the whole rule, `SchemaResolver` no longer threads the meta's entries in, and
  **meta-kernel's bootstrap needs no special case**: the routing table it used to hand-write for the three
  constructors it applies to itself would have had to come from the very entries it is in the middle of
  producing.
- **A generic application is a user template, and this phase mostly leaves it alone.** `name<args>` resolves
  its head through the type-name namespace only (§3.3.1) — parameters, then locals, then imports — so
  `map<text, text>` finds nothing and is an ordinary unresolved reference for the linker to report, and
  anything that *does* resolve is a §5.10 template. Substitution happens over the **resolved** form
  (`TemplateMaterialiser`, `docs/schema-resolution.md`), not over the AST, so an application passes through
  here with its head and arguments intact. `checkTemplateApplication` refuses exactly one thing: a local
  head declaring *no* parameters, the author's error — nothing there takes type arguments. A template whose
  body writes a container sugar form over one of its own parameters used to be refused here too; that form
  now lifts open, so what was the refusal is the mechanism.
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
  is `REQUIRED_DEFAULT` (`state: element_state ~ REQUIRED`), so a `REQUIRED` one is spelled by omitting it,
  as every other defaulted vocabulary field is. Nothing rides on trust: the emitted body binds through the
  governing meta's compiled reader, where an undeclared member is `UNRECOGNIZED_FIELD` under §7.2's closure.
  §5.4's "each variant resolves to a distinct type" is deliberately not checked here — it is a question about
  what names *resolve to*, after §8.3 flattening, which this phase can't answer (`BACKLOG.md`'s
  reference-closure item).
- **Both declaration-level tiers desugar in place.** At declaration position the form *is* the construction
  (`pair => [integer, text]` becomes `!tuple { … }`, like `ids => [text]`, `entries => {text => integer}` and
  `contact => (A | B)`); inline, each is hoisted into its own declaration and referenced.
- **A nested form desugars innermost-first**, and needs no machinery of its own: an element holds a
  `TypeRef`, so `typeRef` recurses into it and the inner form is already a plain name by the time the
  enclosing one is built. That reaches every nesting position alike — an array's element (`[[T]; 3]`), a
  tuple's positions (`[[T; 2], U]`) and a map's value (`{text => [order; 1..]}`) — to any depth, with no
  per-depth case and no second walk; the `hoistNested`/`exprRef` pair this replaces existed only because
  the declaration-level tier was a separate node family.
  Because identity is structural, the injected entry is shared: one `array_integer_<hash>` serves the nested
  position, the flat declaration `[integer]` and an inline field's `[integer]` alike. An injected **tuple**'s
  name derives from its positions' *states* as well as their types, or `[T, U?]` and `[T, U]` would land on
  one entry.
- **The element `?` binds `state` directly.** `[T?]` becomes `!array { element_type: T  state: OPTIONAL }` —
  §5.3's "elements at any position MAY be the absent sentinel `_`; absent elements occupy positional slots".
  It has no parameter to route through and never did, which is why §5.3 gives the `?` forms no template
  route. An unmarked element states nothing and lets §5.2's REQUIRED_DEFAULT injection supply `REQUIRED`,
  exactly as a REQUIRED tuple position omits its own `state`. The state reaches the derived name too, or
  `[T?]` and `[T]` collide on one injected entry. `[T?; 3]` — the form §5.3 states the rule through — puts
  the state and both bounds on one binding record, which is the shape the whole table is now written in. The
  read side needed nothing: `ArrayAbstractReader` already admitted `_` under `ElementState.OPTIONAL` and
  already counted it toward the bounds. Neither side of a map's `=>` takes a `?`: `map` declares no `state`
  field, and an absent key is already a Part 1 resolver error.
- **The size specifier is one rule over the `min_items`/`max_items` pair, for arrays and maps alike.** There
  is no template in between: the kernel's `array_min`/`array_max`/`array_ranged` are deleted, and each of the
  four spellings binds the pair directly, an exact `N` pinning both. §5.3's bound coherence (`min <= max`) is
  checked here, where the bounds are literal at schema load; a bound naming a value parameter is
  materialisation's question. So is the rejection of a **vacuous `[T; 0..]`**: §5.3 calls the form vacuous
  and asks for a warning while desugaring it anyway, and `SPEC-FEEDBACK.md` #42 rejects the spelling instead
  — §5.3's own sentence says why, since structural identity (§8.2) makes it an entry *distinct from* `[T]`
  that means the same thing. Only a literal `0` is caught.
- **An invalid sugar form is reported per declaration, not thrown**, when a `DesugarFailureReporter` is
  supplied — `SchemaResolver` always supplies one on its reporting overload, so the phase joins resolution
  and linking in reporting every independent problem in one pass. The reportable forms are
  `TsonSchemaValidationException`s and declaration-position-only (a size specifier at an inline type-ref
  position is a parse error): a vacuous `[T; 0..]`, an incoherent size range, and an application of something
  that takes no type arguments. The template-application `UnsupportedOperationException` keeps propagating —
  a gap is not a verdict on the author's schema. See `docs/readers-and-diagnostics.md` for the placeholder
  and the no-rollback rule.
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
- **Which entry a form lifts to is D5's one rule, and the enclosing declaration's parameters do not enter
  it.** A form naming none of them lifts *closed*, template or not; a form naming one lifts *open* — an
  `InstanceTemplate` over just the parameters it uses, with the position that held it applying them straight
  back (`<T> { a: [T] }` injects `array_p0_… => <p0> !array { element_type: p0 }` and the field becomes
  `array_p0_…<T>`). So `<T> { a: [T]  b: [order] }` injects one of each, and only the first waits for
  materialisation.
  - **The parameters are renamed positionally**, because two forms alike up to a consistent renaming are one
    template (§8.2) and the name is derived from the record — so normalising the record is what normalises
    the name. The prefix grows (`p`, `pp`, …) until it collides with nothing the record already names: a
    binding may hold a concrete reference to a type genuinely called `p0`.
  - **A declaration's own sugar body is the open construction**, one tier up from the same rule:
    `vector => <T> [T]` *is* the `InstanceTemplate`, and its parameters are the declaration's list as
    written, not the subset the body names — a declared parameter the body never uses is an error the linker
    reports, and dropping it here would hide the very thing it looks for.
  - **A binding's channel follows §12.1's own `type-arg` rule**, not the slot: a quoted or number-shaped
    token is a literal, every other token rides the reference channel, and resolution settles what it turns
    out to be. Deciding it here instead would make `[text; N]` bind the *literal* `"N"`.
  - **A scalar type slot may hold an application**, not just a name — which is what makes `[tree<T>; 1..]`
    and `[[T]]` lift at all. The table keeps both renderings of such a slot: the wire field a closed
    construction would write (`type_ref`'s record form, `{ name: tree  arguments: [ … ] }`, which is what
    `internalName` hashes) and the reference *as written*, which is what an open binding holds. Only
    `element_type`/`key_type`/`value_type` are reached this way; `tuple` and `choice` put their positions
    inside a collection and are unchanged.
    - **A closed construction writes the record form and lets materialisation close it.** Its body goes
      through the constructor's own reader, so the application has to survive a wire hop, and the resulting
      entry names something that is not an entry yet — for exactly the window an ordinary forward reference
      lives in, since `close()` walks every closed entry's references after the driving loop. What made this
      possible was teaching the bind readers to read an untagged labelled choice, which is what
      `type_argument` is (`docs/linking-and-compilation.md`).
    - **A *value* argument still cannot make that trip.** `type_argument`'s value channel is typed `value`,
      whose reader decodes a token to its host type, so `<3>` and `<"3">` would arrive indistinguishable —
      and the form is exactly what identity needs. Refused at the form rather than guessed at.
  - **Two of the four sugar forms have no open representation at all.** `tuple` and `choice` bind a
    collection (`elements`, `variants`), and a `template_argument` is `param | value | type_ref` with no
    collection case — so `<T> { v: (T | text) }` is refused at the declaration that writes it, as a gap
    rather than an author error. `SPEC-FEEDBACK.md` #53 has the account; array and map bind only scalar
    slots and are unaffected.
- **The meta-kernel runs this phase too, with no accommodation at all** — its governing meta is itself, and
  with the table fixed there is nothing to look up.
