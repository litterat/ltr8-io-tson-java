# Schema grammar

Design notes for the first schema-pipeline phase: parsing a schema document into a `SchemaDocument`. Current form only;
history lives in git.

**Invariants**

- `TsonSchemaParser` is grammar-only: no namespace resolution, no materialization, no validation.
- It `extends TsonDataParser` in the same package and calls its package-private methods rather than re-implementing
  Part 1's grammar.
- `type-name = identifier`: every declared name, type parameter, referenced name and `!` head is matched against
  `IdentifierProfile`; field names are the one naming position the parser leaves alone.
- `SchemaMap.declarations` is a `LinkedHashMap` and a duplicate name overwrites — the grammar layer doesn't dedupe.
- No production of the schema grammar takes the full `data-value`: `instance` takes a `core-value`, `atom-refinement` a
  braced `record-def`.
- `{` at a type position dispatches on one consumed token plus one of lookahead (§12.2); everywhere except type-def
  position a `{` is a map and only a map.
- `abstract` and `final` are marks at the type-def head and ordinary identifiers everywhere else — the parser decides
  on the word alone, never on what follows it.
- A mismatch names the construct the position admits, in the author's voice, not the token class.

Related: `design/schema-grammar-and-desugaring.md` (the desugaring phase that runs next),
`design/desugaring-open-forms-and-templates.md`, `design/lexer-and-data-parsing.md` (the data grammar this imports),
`design/schema-side-diagnostics.md` (schema-side diagnostics and the recovering parse).

## Schema grammar (`tson-compiler/TsonSchemaParser.java`, `.../ast/schema/`)

`TsonSchemaParser` parses a schema document body (Part 2 §2.1, §5, §12.1) into a `SchemaDocument` — the
schema-grammar analogue of `Document`. It is **grammar-only**: no namespace resolution, no
materialization, no validation (those are the resolver's/linker's jobs).

- **`extends TsonDataParser`, same package** — §12.1 imports Part 1's `annotation`/`data-value`/directive
  grammar directly, so `TsonSchemaParser` calls straight into package-private methods rather than
  re-implementing them, adding only the schema-only tokens (`~ ^ & | ( ) < > ? ; -`).
- **`type-name = identifier`, and that one rule replaces §12.1's number rule rather than joining it.**
  `expectTypeName` matches every declared name, type parameter and referenced name against
  `IdentifierProfile`, and `parseAtomRefinementOrInstance` matches the `!` head. §12.1 states separately that
  "numbers are not declarable names"; identifier-Start is `XID_Start`, and every spelling the number grammar
  admits begins with a digit, a sign or a dot — all in token-Start only so a *number* can be an unquoted
  token — so the profile subsumes it and also catches the names that merely *begin* like a number (`42x`,
  `-foo`), which a number rule alone lets through. Field names are the one naming position the parser leaves
  alone: `field-name` stays lexical for the Class 1 reason (`design/lexer-and-data-parsing.md`), and
  `DefinitionResolver.requireIdentifier` applies the contract to the ones a declaration actually binds.
  §12.1's `type-name = identifier` states it, and its note carries the field-name half.
- **`SchemaMap.declarations` is a `Map<String, Declaration>`** (a `LinkedHashMap`, insertion order
  preserved) — §3.4.1's Pass 1 shape and the schema's own `{type_name => type_definition}`. A duplicate
  name overwrites, same "grammar layer doesn't dedupe" treatment the data grammar gives duplicate fields.
- **No production of the schema grammar takes the full `data-value`**, which §12.1 states.
  `instance`'s payload is a `core-value` (`Instance` wraps a `DataValue` with `typeRef` pre-set, no separate
  `target`); `construction-def` admits the implicit `&` before its trailing `record-def`; `field-modifier`'s
  value is a bare token or the absent sentinel.
  - **`atom-refinement` is `"!" type-name ws "^" ws record-def`**, so the `^` branch requires a brace:
    `!integer ^ 5`, `!integer ^ !foo { … }` and `!integer ^ @doc:"d" { … }` are syntax errors, reported at the
    offending token, per declaration like every other schema syntax error.
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
  - A parameterized **atom refinement** is no form at all: §12.1 gives `atom-refinement` no parameter
    list, a refinement of an atom instance having no parameter to take, and the parser says so where the
    `^` is read.
- **`[definition-mark]` at the type-def head** (§12.1) — `abstract` or `final`, the words that say how the type may
  be realised, read by `parseDefinitionMarkOpt` before the parameter list and lowered by the resolver into
  `record.extension`. An unmarked declaration is OPEN, so the slot is optional and OPEN has no spelling.
  - **One slot, not two flags.** The three ways a record may be realised are alternatives, so `x => abstract final
    { … }` is ungrammatical rather than a rule the resolver has to state and diagnose.
  - **The words are marks here unconditionally, and that is the whole of the reservation.** They stay ordinary
    identifiers at every other position — a declaration name, a field name, a field's type, an annotation name, a type
    argument — so no name leaves the namespace and [TSON-DATA] §7.4's "no reserved words" holds. What a schema
    declaring `abstract => { … }` gives up is naming it as a whole declaration body: `pet => abstract` is a
    declaration missing its definition, and `parseDefinitionMarkOpt` says so rather than leaving the type-ref parse to
    fail two tokens later.
  - **Deciding on the word alone is what keeps the marked composition writable.** `mid => abstract base & { … }` and
    `pet => abstract` differ only in what follows the head, so a conditional reading would have to give one of them
    up, and an abstract link in a chain is worth more than an alias to a type called `abstract`.
- **Two entry points, one grammar.** `parseSchemaDocument()` is fail-fast; `parseSchemaDocument(receiver)`
  reports each *declaration's* syntax error and resynchronises to the next, handing back no document at all
  if it reported anything. The mechanics, the resync rule and the two failures that stay fail-fast are in
  `design/schema-side-diagnostics.md` under "Schema-side diagnostics", with the rest of the diagnostics model.
- **A mismatch names the construct the position admits, not the token class** — `expect` takes that
  construct in the author's voice, and every call site here is phrased that way (`"a record field's ':'"`,
  `"a choice type's closing ')'"`), never as the enclosing construct. One position goes further and names
  the *fix*: an inline atom refinement or constructor application (`quantity: !integer ^ { min: 1 }`),
  rejected at a type-ref position with the "declare a named type and reference it by name" correction
  (§5.3). An element `?` and a size specifier are legal at a type-ref position, so neither has a diagnostic
  of that kind.
- **One production per container, reachable from `type-ref`** — `ArrayRef`, `TupleRef`, `MapRef`, each
  admitting a size specifier after `;` and an element `?` at *every* position. §12.1 has one bracket
  production and one map production: every form lifts to an entry (§5.3), so a sized form needs no
  declaration-level tier to carry it, and `type-def` reaches each container through `type-ref` like anything
  else, with no tie-break between a declaration-level and an inline spelling to state.
  - **Nesting is the recursion in `ElementType`**, which holds a plain `TypeRef` — `[[T; 2]; 3]` and
    `{text => [order; 1..]}` and `{text => {text => integer}}` need no second node family.
  - **An element's `?` and a field's own `?` cannot collide**: a field is `field-name ":" type-ref ["?"]`,
    so in `xs: [T?]?` the inner belongs to `element-type` and the outer to the field.
  - A map key stays `type-name ["<" type-args ">"]` and nothing else — not a paren type, not a bracket form
    — which is what holds the brace dispatch below to its lookahead budget; a composite key earns a named
    declaration and the explicit `!map { key_type: … }` form.
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
