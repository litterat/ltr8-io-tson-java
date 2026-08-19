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
  preserved) — §3.4.1's Pass 1 shape and the schema's own `map<type_name, type_definition>`. A duplicate
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

## Desugaring (`tson-compiler/.../resolver/SchemaDesugarer.java`)

An AST→AST rewrite between parsing and resolution. Every sugar form (`[T]`, `[T; N..M]`, §5.3), the choice
sugar (`(A | B)`, §5.4) and every generic application (`map<K, V>`, §5.6) becomes a `!C value` construction:
at declaration position it simply *is* that construction, and anywhere else (a field, an element, a variant)
it becomes an **injected declaration plus a bare reference to it**. **The second half is a deliberate
divergence** — §8.2 says a constructor application *never* materialises an entry and is carried structurally
at the use site; declaration position, where the spec agrees, is correct. It is argued as spec feedback
rather than tracked as debt: §8.2's `type_argument` channel binds positionally against the head's declared
parameters, so it cannot carry a vocabulary field no parameter routes — an element/position `state` today
(#49), and a size too once #45's redesign removes the size templates, at which point `[T]`, `[T?]`, `[T; 3]`
and `[T?; 3]` would all carry as the same `type_ref`. `SPEC-FEEDBACK.md` #50 has that argument and #51 the
`!!import` visibility that rides on it — which is not a violation to repair but what §8.2's own identity rule
requires once imports merge into one namespace. So `DefinitionResolver` only ever sees two shapes: a bare
reference or `!C value`. §5.3/§5.6 already *describe* these forms as desugarings and §3.3.1 calls their
targets "the implicit desugar targets of the sugar forms" — this implements that literally instead of
splitting it across the resolver (declaration position) and the linker (field position), which is what it
replaces. A **sized** form goes the whole way too: the spelling first becomes the size template it stands for
(`[T; 1..5]` → `array_ranged<T, 1, 5>`, `[T; 2..]` → `array_min`, `[T; ..9]` → `array_max`, `[T; 3]` →
`array_ranged` with the bound twice), and that application then *closes by routing* into the same `!array`
construction — see the partial-application bullet below.

- **It runs with the governing meta in hand, not context-free.** `SchemaResolver` calls it after acquiring
  `metaParser`, because turning `map<text, X>` into `!map { key_type: text  value_type: X }` needs `map`'s
  own `parameters()` to zip the arguments positionally and each vocabulary field's `value_param` to know
  which field a given argument fills. That routing is what makes it work for *every* constructor rather
  than the ones someone hand-wrote an assembler for.
- **The head is looked up in the structure namespace only** (the governing meta's entries) and must be
  `constructor: true` with matching arity. The precedence/shadowing consequences are `SPEC-FEEDBACK.md` #28.
- **§5.3's variadic pair, `choice` and `tuple`, takes a second path** (`choiceInstance`/`tupleInstance` over
  a shared `variadicField`), because per-parameter routing structurally cannot express either: neither
  `choice => ~sum & { variants: [type_ref] }` nor `tuple => ~product & { elements: [tuple_element] }` declares
  parameters, and the field each fills is a *collection* with no `value_param` to route through. §5.3 names
  the shape instead — for "the variadic pair, `tuple` and `choice`, arguments map positionally onto `elements`
  and `variants`" — so every variant/position becomes one element of that one field. Only the head is fixed
  here (§5.6's desugaring table fixes it, exactly as `[T]` fixes `array`); the *field* is still read off the
  governing meta as its **sole bare-`REQUIRED` field** (§5.6's own positional-form rule), so routing stays
  vocabulary-derived. The two halves differ in what one position *is*: a variant is a bare `type_ref`, an
  element a `tuple_element` record carrying a type **and** its own `ElementState`, so each tuple position
  needs a record built for it. `state` is written only for an `OPTIONAL` position — the member is
  `REQUIRED_DEFAULT` (`state: element_state ~ REQUIRED`), so a `REQUIRED` one is spelled by omitting it, as
  `instanceFor` omits every defaulted field. Its two member names are meta-kernel-fixed constants rather than
  a second vocabulary lookup one level down, and nothing rides on trust: the emitted body binds through the
  governing meta's compiled reader, where an undeclared member is `UNRECOGNIZED_FIELD` under §7.2's closure.
  §5.4's "each variant resolves to a distinct type" is deliberately not checked here: it is a question about
  what names *resolve to*, after §8.3 flattening, which this phase can't answer (`BACKLOG.md`'s
  reference-closure item).
- **Both bracket positions desugar, tuple as well as array.** At declaration position the form *is* the
  construction (`pair => [integer, text]` becomes `!tuple { ... }`, like `ids => [text]` and
  `contact => (A | B)`); inline it is hoisted into a `tuple_<...>_<hash>` declaration and referenced.
- **A nested bracket form desugars innermost-first** (`elementRef`/`hoistNested`). §5.3's declaration-level
  container syntax nests inside itself and the spec fixes the order outright — `grid => <T, N> [[T; N]; N]`
  is `array_ranged<array_ranged<T, N, N>, N, N>`, "the inner form desugaring first" — so the inner container
  is built as its own instance, injected under its derived name, and the position that held it becomes a bare
  reference. That is the bottom-up hoist the type-ref walk already does for `map<text, [integer]>`, one tier
  down, and it reaches every element position: an array's element (`[[T]; 3]`) and a tuple's positions
  (`[[T; 2], U]`) alike, to any depth, with no per-depth case. Because identity is application-structural
  (§8.2), the injected entry is shared — the one `array_integer_<hash>` serves the nested position, the flat
  declaration `[integer]` and an inline field's `[integer]` alike. Nothing here needs §5.10 substitution: a
  nested form carries no parameter its flat sibling does not. An injected **tuple**'s name derives from its
  positions' *states* as well as their types, or `[T, U?]` and `[T, U]` would land on one entry — and that
  form is exactly the one §8.2's structural representation has no channel for, so nesting it is only
  representable at all *because* of the entry-materialising divergence (`SPEC-FEEDBACK.md` #49, #50).
- **The element `?` is a *direct* binding, not a routed one.** `[T?]` becomes `!array { element_type: T
  state: OPTIONAL }` — §5.3's "elements at any position MAY be the absent sentinel `_`; absent elements occupy
  positional slots". `array`'s `state` carries no `value_param` (`state: element_state ~ REQUIRED`), so no
  application argument can reach it, which is §5.3's own reason the `?` forms "have no template route ... and
  desugar directly". `instanceFor` therefore takes a list of fields bound by name against the vocabulary and
  emits them in the vocabulary's own field order; a binding naming a field the vocabulary doesn't declare
  yields empty rather than being dropped, since silently binding nothing is the `UriType`/`RegexType` trap one
  layer up. An unmarked element states nothing and lets §5.2's REQUIRED_DEFAULT injection supply `REQUIRED`,
  exactly as a REQUIRED tuple position omits its own `state`. The state reaches the derived name too, or
  `[T?]` and `[T]` collide on one injected entry. **`[T?; 3]` — the form §5.3 states the rule through — is
  what forced the sized forms onto the bullet below**: the element state and both bounds have to land on one
  binding record, and while the size half routed through an application, `[T; 3]` and `[T?; 3]` recorded the
  same `source` (an argument list has no channel for an element state, #49). The read side needed nothing:
  `ArrayAbstractReader` already admitted `_` under `ElementState.OPTIONAL` and already counted it toward the
  bounds — dead code until a schema could carry the state.
- **A partial application closes by routing, into a construction of its constructor** — it does *not*
  materialise an entry. §5.3's sized sugar is the case that matters: `[T; 1..5]` → `array_ranged<T, 1, 5>`,
  and `array_ranged` is declared without `~` with its parameters only in labelled *value* channels, so
  applying it is **evaluation**: the arguments route through the same `value_param` channels a constructor's
  own vocabulary uses, and the emitted binding record is headed at the nearest `~` constructor in the source
  chain (`!array`, §5.6), not at the template. So `[T; 1..5]` and `[T]` land on the same shape, one bound
  apart, and a sized array's `source` is plain `array`. This is `SPEC-FEEDBACK.md` #45's taxonomy, and a
  **deliberate divergence from §8.2's worked example**, which prints an `array_ranged_pixel_af3`
  instantiation entry with `supertypes: [array product top]`: a size template's chain begins at the
  constructor it refines, a constructor is a factory rather than a type anything can be a subtype of, and the
  grant was inert besides. The spec author has confirmed the example wrong; no bundled schema or fixture
  writes a sized form. The size templates' *own* entries keep their chain — this phase walks it to find the
  head. §8.2's *substitution*-driven materialisation is left for the form that genuinely needs it, a
  **structural** template (`box => <T> { v: T }`) — distinct from the entries an application materialises
  here (#50), which are routed, not rewritten — and is rejected at the application site until §5.10 is
  implemented — so there is no `TemplateInstance` node any more, and `DefinitionResolver` has no
  instantiation completion. §8.2's deferred
  `min_items <= max_items` check still runs here, where the bindings become concrete. So does the rejection of
  a **vacuous `[T; 0..]`**: §5.3 calls the form vacuous and asks for a warning while desugaring it anyway,
  and `SPEC-FEEDBACK.md` #42 rejects the spelling instead — §5.3's own sentence says why it is worth
  rejecting rather than tolerating, since application-structural identity (§8.2) makes it an entry
  *distinct from* `[T]` that means the same thing. Only a literal `0` is caught; a bound naming a value
  parameter isn't concrete here.
- **Applying a *record* template is still rejected here**
  (`UnsupportedOperationException`) — `box => <T> { v: T }` puts its parameter in a *field type*, so
  instantiating it means rewriting the body, which is real §5.10 substitution and unimplemented. Rejecting at
  the application site beats the alternative: passing it through produced a schema that linked, compiled, and
  then failed on the first read reaching the field. Both namespaces a template can live in are checked — a
  head this document declares (via its grammar-layer `TypeDef`, the only place its parameters exist this
  early) and one in the structure namespace (via its resolved definition). Still uncaught: a template
  declared by an `!!import`, which needs the imported entries' resolved definitions rather than the name set
  the phase takes.
- **An invalid sugar form is reported per declaration, not thrown**, when a `DesugarFailureReporter` is
  supplied — `SchemaResolver` always supplies one on its reporting overload, so the phase joins resolution and
  linking in reporting every independent problem in one pass. Two forms are reportable, both
  `TsonSchemaValidationException` and both declaration-position-only (a size specifier is rejected at parse
  time at an inline type-ref position): a vacuous `[T; 0..]` and a sized application binding `min_items` above
  `max_items`. The record-template `UnsupportedOperationException` keeps propagating — a gap is not a verdict
  on the author's schema. See `docs/readers-and-diagnostics.md` for the placeholder and the no-rollback rule.
- **Bottom-up, so nesting needs no special case:** an inner application is hoisted first and the outer one
  is built from the already-flattened name (`map<text, [integer]>` works at any depth). The injected name
  is `head_args_hash`, derived from the application itself, so two structurally identical applications
  anywhere in the document collapse to one declaration for free (§8.2's structural-equality rule) — and an
  application an `!!import` already declares is **referenced, not redeclared**, which is why the phase takes
  the imported name set (meta.tn repeats several of meta-kernel's applications; redeclaring would be a
  local-vs-import collision). The hash half runs over a rendering the phase builds itself — one tag per
  argument shape, author text written length-first, nested references recursed — and **never over the AST's
  own `toString`**, whose format the JDK documents as subject to change and which moves whenever an
  `ast.schema` record's components are renamed; the records' `hashCode` is worse still, being free to differ
  between two runs of the same application. `SchemaDesugarerTest` pins two derived names to exact strings,
  because a change to them is a change to the resolved form of every schema.
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
- **A parameterized declaration is left entirely alone** — its body legitimately references its own
  parameters, and rewriting `array<T>` inside `array`'s own declaration would be nonsense.
- **The meta-kernel runs this phase too**, supplying its own hand-written routing table
  (`MetaKernelBootstrapResolver.BOOTSTRAP_CONSTRUCTORS`), since its governing meta is itself.
