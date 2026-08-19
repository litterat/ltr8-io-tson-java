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
- **Two entry points, one grammar.** `parseSchemaDocument()` is fail-fast; `parseSchemaDocument(receiver)`
  reports each *declaration's* syntax error and resynchronises to the next, handing back no document at all
  if it reported anything. The mechanics, the resync rule and the two failures that stay fail-fast are in
  `docs/readers-and-diagnostics.md` under "Schema-side diagnostics", with the rest of the diagnostics model.
- **A mismatch names the construct the position admits, not the token class** — `expect` takes that
  construct in the author's voice, and every call site here is phrased that way (`"a record field's ':'"`,
  `"a choice type's closing ')'"`), never as the enclosing construct. Three positions go further and name
  the *fix*, all in the same shape: an inline atom refinement or constructor application (`quantity:
  !integer ^ { min: 1 }`), an element `?`, and a size specifier, each rejected at a type-ref position with
  the "declare a named type and reference it by name" correction (§5.3).
- **The bracket form is parsed twice, per the spec** — `ArrayContainerDef`/`TupleContainerDef` at
  declaration position, `InlineArrayRef`/`InlineTupleRef` at type-ref position, with `[` at type-def
  position hard-coded to the container path (§12.1's prose tie-break; the two productions overlap and
  `type-def` is genuinely ambiguous without it). Four node types for two concepts, and `SchemaDesugarer`
  walks both to the same output — implemented per letter, and argued against in `SPEC-FEEDBACK.md` #31.
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
name. That pairs with the `value_form` invariant a template body's nested forms will carry
(`value_form` present ⟺ pending synthesis ⟺ open entry) and makes the closed-entry rule checkable
structurally, with no vocabulary needed to read a `type_ref`.

`spec/tson-cr-structure-templates.md` D8 proposed the opposite — inline sugar riding as a structural
`type_ref` rather than as an injected entry — and is **not implemented**. Its case does not hold up: an entry
set wider than the declaration set is already normal (`subtypes` and `disjoint` are resolver-derived too), a
derived name only has to be stable *within* an implementation rather than agreed between them, and the
report's own D7 rejects a second representation of a nested form for precisely the reason D8 would impose one
on containers. The dedup would not disappear either, only relocate into a compile-time memo keyed on ref
structure. `BACKLOG.md` carries the full account.

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
- **A generic application is a user template, and applying one is not implemented.** `name<args>` resolves
  its head through the type-name namespace only (§3.3.1) — parameters, then locals, then imports — so
  `map<text, text>` finds nothing and is an ordinary unresolved reference for the linker to report, and
  anything that *does* resolve is a §5.10 template. Substitution is unimplemented, and leaving the
  application alone produced a schema that linked and compiled and then failed on the first read reaching the
  field, so `rejectTemplateApplication` fails at the site that writes it. Three outcomes, classified: a local
  declaration with parameters is the gap (`UnsupportedOperationException`); an **imported** head is the gap
  too, by the conservative reading, since the phase is handed only the imported names — which is what the old
  parameter-list-driven check could not do, so an imported template used to slip through to a read-time
  failure; a local declaration with *no* parameters is the author's error
  (`TsonSchemaValidationException` — nothing there takes type arguments); anything else is left alone.
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
- **A nested declaration-level form desugars innermost-first** (`elementRef`/`exprRef`). §5.3's
  declaration-level container syntax nests inside itself and the inner form desugars first, so the inner
  container is built as its own binding record, injected under its derived name, and the position that held
  it becomes a bare reference. That is the bottom-up hoist the type-ref walk already does, one tier down, and
  it reaches every nesting position alike — an array's element (`[[T]; 3]`), a tuple's positions
  (`[[T; 2], U]`) and a map's value (`{text => [order; 1..]}`) — to any depth, with no per-depth case.
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
- **A parameterized declaration is passed through whole.** Its desugared structure is the template's
  recorded open form, and every nested form inside it becomes concrete only at materialisation, so lifting
  one eagerly would mint an entry for a template that may never be instantiated. The blanket rule (the change
  report's D5) is that a declaration with parameters lifts nothing, not even a parameter-free subform;
  deduplication at materialisation makes the outcomes converge.
- **The meta-kernel runs this phase too, with no accommodation at all** — its governing meta is itself, and
  with the table fixed there is nothing to look up.
