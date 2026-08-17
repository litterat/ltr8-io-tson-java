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
- **The bracket form is parsed twice, per the spec** — `ArrayContainerDef`/`TupleContainerDef` at
  declaration position, `InlineArrayRef`/`InlineTupleRef` at type-ref position, with `[` at type-def
  position hard-coded to the container path (§12.1's prose tie-break; the two productions overlap and
  `type-def` is genuinely ambiguous without it). Four node types for two concepts, and `SchemaDesugarer`
  walks both to the same output — implemented per letter, and argued against in `SPEC-FEEDBACK.md` #31.

## Desugaring (`tson-compiler/.../resolver/SchemaDesugarer.java`)

An AST→AST rewrite between parsing and resolution. Every sugar form (`[T]`, `[T; N..M]`, §5.3), the choice
sugar (`(A | B)`, §5.4) and every generic application (`map<K, V>`, §5.6) becomes a `!C value` construction:
at declaration position it simply *is* that construction, and anywhere else (a field, an element, a variant)
it becomes an **injected declaration plus a bare reference to it**. **The second half is a known conformance
divergence** — §8.2 says a constructor application *never* materialises an entry and is carried structurally
at the use site; declaration position, where the spec agrees, is correct. It stands because nothing
downstream reads `TypeRef.arguments()`, so the structural form would have nothing to compile against;
`BACKLOG.md` has the full account, including the `!!import` visibility that rides on it. So `DefinitionResolver` only ever sees two shapes: a bare
reference or `!C value`. §5.3/§5.6 already *describe* these forms as desugarings and §3.3.1 calls their
targets "the implicit desugar targets of the sugar forms" — this implements that literally instead of
splitting it across the resolver (declaration position) and the linker (field position), which is what it
replaces. A **sized** form desugars only as far as the size template it stands for (`[T; 1..5]` →
`array_ranged<T, 1, 5>`, `[T; 2..]` → `array_min`, `[T; ..9]` → `array_max`, `[T; 3]` → `array_ranged` with
the bound twice) — purely a change of spelling, which is why it belongs here, but those targets are
*templates*, not constructors, so the result stays an application. What it then resolves to is §5.10
substitution, which this phase does not answer.

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
  what names *resolve to*, after §8.3 flattening, which this phase can't answer (`BACKLOG.md`).
- **Both bracket positions desugar, tuple as well as array.** At declaration position the form *is* the
  construction (`pair => [integer, text]` becomes `!tuple { ... }`, like `ids => [text]` and
  `contact => (A | B)`); inline it is hoisted into a `tuple_<...>_<hash>` declaration and referenced. A
  position holding a *nested* bracket form is left unexpanded, the same limit the array side has.
- **A template application over a constructor is instantiated** (§8.2's one materialising form). §5.3's
  sized sugar is the case that matters: `[T; 1..5]` → `array_ranged<T, 1, 5>`, and `array_ranged` is a
  template (declared without `~`) whose resolved vocabulary carries the same `value_param` channels a
  constructor's does — so the *same* routing code handles it, with one difference: the emitted binding record
  is headed at the nearest `~` constructor in the source chain (`!array`, §5.6), not at the template. The
  result is a `TemplateInstance` AST node — no surface syntax corresponds to it — which `DefinitionResolver`
  completes with the one thing a construction doesn't carry: §8.2's `source`, the flattened application.
  §8.2's "the template's supertypes, unchanged by substitution" is **not** implemented (`SPEC-FEEDBACK.md`
  #45): a size template's chain begins at the constructor it refines, and a constructor is not a type
  anything can be a subtype of, so a sized array records empty `supertypes` exactly as `[T]` and
  `vector<T, N>` do. The template's *own* entry keeps its chain — this phase walks it to find the head.
  §8.2's deferred
  `min_items <= max_items` check runs here too, at the materialising application. So does the rejection of
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
  local-vs-import collision).
- **Structural sharing is load-bearing, not an optimization.** Every node not being rewritten is returned
  by identity, because `TsonSchemaParser.declarationPositions()` is an `IdentityHashMap` — an
  equal-but-rebuilt `Declaration` silently loses its position, and the diagnostics that report against it.
  `SchemaDesugarerTest` asserts `assertSame` for exactly this reason.
- **A parameterized declaration is left entirely alone** — its body legitimately references its own
  parameters, and rewriting `array<T>` inside `array`'s own declaration would be nonsense.
- **The meta-kernel runs this phase too**, supplying its own hand-written routing table
  (`MetaKernelBootstrapResolver.BOOTSTRAP_CONSTRUCTORS`), since its governing meta is itself.
