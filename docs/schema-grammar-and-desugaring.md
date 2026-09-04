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
- **`type-name = identifier`, and that one rule replaces §12.1's number rule rather than joining it.**
  `expectTypeName` matches every declared name, type parameter and referenced name against
  `IdentifierParser`, and `parseAtomRefinementOrInstance` matches the `!` head. §12.1 states separately that
  "numbers are not declarable names"; identifier-Start is `XID_Start`, and every spelling the number grammar
  admits begins with a digit, a sign or a dot — all in token-Start only so a *number* can be an unquoted
  token — so the profile subsumes it and also catches the names that merely *begin* like a number (`42x`,
  `-foo`) which the number rule let through. Field names are the one naming position the parser leaves
  alone: `field-name` stays lexical for the Class 1 reason (`docs/lexer-and-data-parsing.md`), and
  `DefinitionResolver.requireIdentifier` applies the contract to the ones a declaration actually binds.
  §12.1's `type-name = identifier` states it, and its note carries the field-name half.
- **`SchemaMap.declarations` is a `Map<String, Declaration>`** (a `LinkedHashMap`, insertion order
  preserved) — §3.4.1's Pass 1 shape and the schema's own `{type_name => type_definition}`. A duplicate
  name overwrites, same "grammar layer doesn't dedupe" treatment the data grammar gives duplicate fields.
- **Three ABNF defects were implemented per intent before the spec caught up, and it since has.**
  `instance`'s payload is a `core-value`, not the full `data-value` (`Instance` wraps a `DataValue` with
  `typeRef` pre-set, no separate `target`); `construction-def` admits the implicit `&` before its trailing
  `record-def`; `field-modifier`'s value is a bare token or the absent sentinel. §12.1 now spells all three
  that way, and states that no production of the schema grammar takes the full `data-value`.
  - **`atom-refinement` followed late.** Its production is `"!" type-name ws "^" ws record-def`, and the
    `^` branch took a full `data-value` for a revision longer than the `instance` branch did — so
    `!integer ^ 5`, `!integer ^ !foo { … }` and `!integer ^ @doc:"d" { … }` all parsed. The branch now
    requires a brace and reports at the offending token, per declaration like every other schema syntax
    error.
  - An unquoted non-numeric type-argument always parses as a type reference, never a value literal — a
    deliberate grammar-layer deferral, classified at a later semantic layer.
- **A `!` head behind a parameter list is the same production as one without** (§12.1's `instance =
  [type-params] "!" type-name ws core-value`) -- `vector => <T, N> !array { element_type: T  min_items: N }`.
  §12.1 has one production for both, and its own note says so: "`!` opens an `instance`, with or without a
  preceding `<…>`". `Instance` carries
  the parameter list, and `parseTypeDef` reads that list *before* dispatching on `!` -- one token then
  decides.
  - **The payload is a `core-value`, and nothing narrower.** An open entry's body is held rather than read
    against its constructor's vocabulary until materialisation substitutes, so there is no per-slot quotation
    to constrain it and a collection payload is as ordinary as a scalar one: `<T> !choice { variants:
    [T error] }` parses and resolves, which is §5.10's "collection-valued slots are parameterizable". The
    kernel declares no `template_argument` or `instance_template` for a quotation to need.
  - **What it cannot spell is an application**, and neither can a closed instance: `!array { element_type:
    box<text> }` is not a `core-value`, in either form. That line falls where the grammars already divide --
    a *type* position is schema grammar and takes `box<text>` directly, while `!C value` takes data, so an
    application inside one is written in `type_ref`'s record form, which is what the sugar expands to anyway.
  - A parameterized **atom refinement** is still no form at all: §12.1 gives `atom-refinement` no parameter
    list, a refinement of an atom instance having no parameter to take, and the parser says so where the
    `^` is read.
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
  nothing; §12.1 now has one bracket production, and size specifiers and element/position `?` are legal at
  every type-ref position. `type-def` reaches both through `type-ref`
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
    map rule. Both are rejected (a `?` marks a map's *value*, never its key); only one mentions maps. §5.3
    states the interaction at the dispatch, and buying the better message with a third token of lookahead is
    a bad trade §12.2's stated budget — one consumed token plus one of lookahead — declines on the same
    terms.

## Desugaring (`tson-compiler/.../resolver/SchemaDesugarer.java`)

An AST→AST rewrite between parsing and resolution. Every sugar form — `[T]` and the sized forms, `[T, U]`,
`{K => V}` (§5.3), `(A | B)` (§5.4) — becomes the `!C value` construction it denotes: at declaration position
it simply *is* that construction, and anywhere else (a field, an element, a variant, a map value) it becomes
an **injected declaration plus a bare reference to it**. So `DefinitionResolver` only ever sees two shapes: a
bare reference or `!C value`. §5.3/§5.6 already *describe* these forms as desugarings and §3.3.1 calls their
targets "the implicit desugar targets of the sugar forms" — this implements that literally instead of
splitting it across the resolver (declaration position) and the linker (field position), which is what it
replaces.

**The injected-entry half is the spec's own rule.** §8.2: "**Every application materialises** ... Nothing is
carried structurally in place: a use site holds a bare reference to its entry", and the materialised entries
merge under `!!import` by the same structural identities they have within a schema. The structure-templates
CR, now the baseline, is where that came from: **D3** de-parameterises
`array`/`set`/`map`, so a container at a use site cannot be an application at all — nothing in meta-kernel
takes type parameters, `map` holding `key_type`/`value_type` as ordinary fields — and **D5** states one lift
rule, "every sugar form lifts at desugar: a concrete form to a closed synthetic entry". The resolved
fixtures were written against the older shape and were brought onto this one; `ResolvedFixtureTest` now
asserts the two agree entry for entry.

**The rule this settles on:** `TypeRef.arguments` non-empty means an **open** form — a template application,
whose arguments are what materialisation substitutes. Everything closed is an entry, referenced by a bare
name. Its counterpart at the body is a held body present ⟹ open entry, and together the two make the closed-entry
rule checkable structurally, with no vocabulary needed to read a `type_ref`. One direction only: a partial
application (`<B> pair<uuid, B>`) is a template that holds nothing, keeping the `type_ref` with arguments it
already resolves to.

The structure-templates change report proposed the opposite — inline sugar riding as a structural
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
  | `{K => V?}`, `{K => V?; …}` | the corresponding form with `state: OPTIONAL` bound directly |
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
  what names *resolve to*, after §8.3 flattening, which runs at the end of resolution and so cannot have
  happened yet when this phase runs (`ReferenceFlattener`, `docs/schema-resolution.md`).
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
  already counted it toward the bounds. **A map's value takes the same `?`** and binds the same field —
  `map` carries an `element_state` for it (§5.3's `{K => V?}` row) — so `{K => V}` means what `[T]` means
  and an author who wants absence writes it. The *key* takes
  none and never will: §2.9 forbids an absent key outright, so there is no state for a marker to bind.
- **The size specifier is one rule over the `min_items`/`max_items` pair, for arrays and maps alike.** There
  is no template in between: the kernel's `array_min`/`array_max`/`array_ranged` are deleted, and each of the
  four spellings binds the pair directly, an exact `N` pinning both. §5.3's bound coherence (`min <= max`) is
  checked here, where the bounds are literal at schema load; a bound naming a value parameter is
  materialisation's question. So is the rejection of a **vacuous `[T; 0..]`**: §5.3 calls the form vacuous
  and rejects it: §5.3 makes `0..` a resolver error, because structural identity (§8.2) makes it an entry
  *distinct from* `[T]` that means the same thing, and the diagnostic SHOULD say so. Only a literal `0` is
  caught.
- **An invalid sugar form is reported per declaration, not thrown**, when a `DesugarFailureReporter` is
  supplied — `SchemaResolver` always supplies one on its reporting overload, so the phase joins resolution
  and linking in reporting every independent problem in one pass. The reportable forms are
  `TsonSchemaValidationException`s and declaration-position-only (a size specifier at an inline type-ref
  position is a parse error): a vacuous `[T; 0..]`, an incoherent size range, and an application of something
  that takes no type arguments. **A template-application `UnsupportedOperationException` is reported too**,
  as `NOT_IMPLEMENTED` rather than as an author error — thrown, it took every other declaration's verdict
  with it. See `docs/readers-and-diagnostics.md` for the code split, the placeholder and the no-rollback
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
- **Which entry a form lifts to is D5's one rule, and the enclosing declaration's parameters do not enter
  it.** A form naming none of them lifts *closed*, template or not; a form naming one lifts *open* — an
  `Instance` carrying just the parameters it uses, with the position that held it applying them straight
  back (`<T> { a: [T] }` injects `array_p0_… => <p0> !array { element_type: p0 }` and the field becomes
  `array_p0_…<T>`). So `<T> { a: [T]  b: [order] }` injects one of each, and only the first waits for
  materialisation.
  - **The parameters are renamed positionally**, because two forms alike up to a consistent renaming are one
    template (§8.2) and the name is derived from the record — so normalising the record is what normalises
    the name. The prefix grows (`p`, `pp`, …) until it collides with nothing the record already names: a
    binding may hold a concrete reference to a type genuinely called `p0`.
  - **A declaration's own sugar body is the open construction**, one tier up from the same rule:
    `vector => <T> [T]` *is* the open construction, and its parameters are the declaration's list as
    written, not the subset the body names — a declared parameter the body never uses is an error the linker
    reports, and dropping it here would hide the very thing it looks for.
  - **A binding's channel follows §12.1's own `type-arg` rule**, not the slot: a quoted or number-shaped
    token is a literal, every other token rides the reference channel, and resolution settles what it turns
    out to be. Deciding it here instead would make `[text; N]` bind the *literal* `"N"`.
  - **A scalar type slot may hold an application**, not just a name — which is what makes `[tree<T>; 1..]`
    and `[[T]]` lift at all. The table keeps both renderings of such a slot: the wire field a closed
    construction would write (`type_ref`'s record form, `{ name: tree  arguments: [ … ] }`, which is what
    `DerivedName.ofBinding` hashes) and the reference *as written*, which is what an open binding holds. Only
    `element_type`/`key_type`/`value_type` are reached this way, a named slot being the one an open binding
    can address; `tuple` and `choice` put their positions inside a collection, so they keep only the first
    rendering — written through the same `WireForm.refValue` producer, since a `[type_ref]` holds what a `type_ref`
    holds, and rewritten a pass later by `MetaRefs.mapBodyRefs`, which maps a choice's variants
    and a tuple's elements like any other reference.
    - **A slot that refuses an application does not fail where it decided.** `choiceBinding` required a bare
      name per variant, so `( box<text> | int32 )` left the *whole* choice unlifted and reached
      `DefinitionResolver` as a `ChoiceRef` it has no case for — the author of a closed, ordinary type being
      told that only "fresh record constructions, composition, simple type references … are resolved so far".
      Inside a template it was worse: with the choice unlifted, holding the body handed the `ChoiceRef` to
      `refValue`, whose two documented inputs are `SimpleRef` and `GenericRef`, and the schema died on a
      `ClassCastException`.
    - **A closed construction writes the record form and lets materialisation close it.** Its body goes
      through the constructor's own reader, so the application has to survive a wire hop, and the resulting
      entry names something that is not an entry yet — for exactly the window an ordinary forward reference
      lives in, since `close()` walks every closed entry's references after the driving loop. What made this
      possible was teaching the bind readers to read an untagged labelled choice, which is what
      `type_argument` is (`docs/linking-and-compilation.md`).
    - **A *value* argument makes the trip intact.** `type_argument`'s value channel binds a raw `Token` —
      §5.10 calls a type argument's literal a bare token rather than the value it denotes — so the slot reads
      the token rather than decoding it (`RawTokenParser`). The spelling is therefore what reaches identity,
      and `NumericIdentity` applies [TSON-DATA] §4.3's equivalence there, so `<255>` and `<0xFF>` are one
      application — radix, digit separators and a redundant sign falling away, and a float's written scale
      with them, while the base-type line does not (`1` is an integer and `1.0` a float under §4, so those
      stay two). §8.2 states exactly that split: recorded as written, compared as the value the token
      denotes under §4, "and no wider".
  - **All four sugar forms lift open, collections included** — §5.10's "collection-valued slots are
    parameterizable". `tuple` and `choice` bind a collection (`elements`, `variants`); a held body is not
    read against the constructor's vocabulary at all until materialisation substitutes, so a parameter
    inside a collection is a token inside an array and lifts like any other, `result => <T> ( T | error )`
    being the spec's own example.
  - **The open form is the closed form**, which is what removed the per-slot analysis: one binding record
    serves both, since a parameter in a slot is simply the token standing there. `instance(binding,
    typeParams)` builds either, and the phase needs no rule for how to quote a parameter — only for whether
    the declaration around it has one.
- **A parameterised alias is normalised here as well** (§5.10's partial application). `uuid_pair => <B>
  pair<text, B>` leaves this phase as `<B> !reference { target: pair<text, B> }` — §8.1's own reading of what
  an alias body is, spellable because the kernel's `reference.target` is a `type_ref` rather than a bare name.
  It was the last open form that was not a constructor application; with it, §12.1's
  `[type-params] "!" type-name ws core-value` covers every template and one walk closes them all.
  - Only a *parameterised* one. A closed alias (`text_box => box<text>`) resolves to a `REFERENCE` entry
    directly, the way a closed record resolves to a `RecordBody`: nothing about it is deferred, so there is
    nothing to hold.
- **A record template is normalised here too, and for the same reason the sugar forms are** (`recordBinding`).
  §5.2 says a bare record body denotes `!record { fields: [ … ] }`, so `test => <T> { x: T }` leaves this
  phase as `test => <T> !record { fields: [ { name: x  type: T } ] }` — a held application like `<T> [T]`, and
  closed by the same process. The rule is as fixed and as closed as the sugar table: §5.2's six field
  spellings decide `state` and `value` from the two marks the author wrote, and nothing else is consulted.
  - **Only a *template*.** A closed record still resolves at its declaration into a `RecordBody`, because
    nothing about it is deferred. **Every** template takes it, `~`-marked or not: a marked one used to skip
    this phase and be held by `DefinitionResolver.holdIfOpen` instead, which wraps an open `RecordBody` into
    the same `!record { … }`, so the marker chose only which phase did identical work. The two paths share the §5.2 state table (`FieldModifiers`) so the six
    spellings and the errors around them cannot drift apart between a template and the closed record beside it.
  - **Only what the author wrote is written.** `access_pattern` and `size_type` are `REQUIRED_FIXED` on the
    `record` constructor, and an unmarked field's `REQUIRED` is that constructor's own default, so none of the
    three is stated — the same economy `arrayBinding` makes with an unmarked element's `state`, and what keeps
    the held form the one the author would recognise.
  - **The rewrite has to be here rather than in the resolver**, and that is the finding the earlier attempt
    turned on. Resolving the body and writing the resolved form back out puts a *second producer* in front of
    a wire form two later phases read, and they disagree: `TsonObjectWriter` states a no-argument `type_ref`
    in the explicit record form (`{ name: N  arguments: [] }`) where this phase states it positionally (`N`).
    That makes a `type_argument` indistinguishable from a `type_ref` application to a walk that reads neither
    against a vocabulary — and since `DerivedName.ofBinding` hashes what is written, a second spelling is also
    a second entry for one type. One producer, one spelling: `WireForm.refValue` is the single place a type
    slot's shape is decided.
  - **A parameter rides the ordinary `value` slot**, with §8.1's shadowing rule to tell it from a literal,
    which is why the kernel declares one `value` slot and no labelled group. §5.7's fixation then happens at
    materialisation
    (`TemplateMaterialiser.fixRoutedValues`), where the value is concrete.
  - **A composition or refinement template is held too, but from one phase later** (`WireForm.heldRecord`, called by
    `DefinitionResolver.holdIfOpen`). Both absorb fields from a source, and the form to hold is the
    *flattened* one — a §5.7 tightening entry states a modifier and no type-ref, so it is not a `record_field`
    at all until the inherited field supplies one. That needs a namespace this phase does not have, so the
    rewrite happens in the resolver; the **spelling** is `WireForm`'s, which is the whole point.
    `WireForm.heldRecord` and this phase's `recordBinding` are two producers of the wire form and one
    producer of its spelling — both going through `WireForm.refValue` and `WireForm.nameField` is what makes
    that true by construction rather than by two authors agreeing.
    - **`TsonObjectWriter` cannot serve as that second producer**, which is why `WireForm.heldRecord` exists rather
      than a round-trip. Measured against the desugar spelling it differs five ways: `{ name: "text"
      arguments: [] }` for a bare `text`, `!ref { … }` for a `type_argument`, every token quoted, `state:
      REQUIRED` written where the default covers it, and the retired `value_param` channel emitted. The
      first two are the
      two-spellings problem; the third is fatal on its own, since `TemplateBody.names()` and substitution
      both key on a token being *unquoted*, so a fully-quoted body references no parameters at all. Its
      output is canonical-explicit — a different language from the one a held body is written in.
    - **The writer is still used for exactly one leaf**: a resolved annotation carries its value as a *bound
      object* (`Annotation.value` is `Optional<Object>`), and unbinding one is what an object writer is for.
      That is a self-contained value rather than part of the spelling, so it goes through
      `DefinitionResolver.annotationWireValue` and nothing structural does.
  - **§5.11's uniqueness rule is asked here as well**, and asking it twice is not duplication: the resolver's
    copy sees a closed record body and this one sees a template's, which after normalisation are two
    different phases. Left to the constructor's own reader instead, the wire form carries two `record_field`
    records in an array, where repetition is not an error at all — so `bl => <T> { v: T  v: T }` would ship
    with no verdict.
- **Every entry it lifts is a *synthetic* entry, and is marked as one.** `SchemaDesugarer.lifted(original,
  desugared)` is the set difference between the two documents, and that set is exactly what §8.2's derived
  `@synthetic` marker goes on — attached at the schema-map key by the caller, not here, since this phase
  deals in AST and the marker belongs to resolved output (`docs/schema-resolution.md`). A set difference
  rather than a field on the pass, because `hoist` deliberately does *not* inject a form an `!!import`
  already declares: that entry is the same form resolved by the schema that owns it, and marking it here
  would put this document's derived marker on someone else's key.
- **The meta-kernel runs this phase too, with no accommodation at all** — its governing meta is itself, and
  with the table fixed there is nothing to look up.
