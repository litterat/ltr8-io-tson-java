---
title: "TSON Change Report: Removal of Cross-Namespace Template Linkage"
against: "TSON 2026 Revision 32 (Working Draft)"
status: "Proposed"
id: "CR-structure-templates"
---

# Change Report: Removal of Cross-Namespace Template Linkage

**Against:** TSON Part 2 (Type System and Schema), 2026 Revision 32; companion artifacts `meta-kernel.tn`, `meta.tn`, `core.tn`. Part 1 (Text Data Format) is unaffected: the lexer is unchanged and no new tokens, lexer modes, or character classifications are introduced. `{`, `}`, `=>`, and `;` are existing tokens; the brace dispatch this change adds to the schema grammar is the dispatch Part 1 §2.8 already mandates for data.

**Companion change (assumed baseline):** the array size sugar desugars directly to `!array` binding records rather than routing through size-refinement templates. This report incorporates that change where the two interact (desugar table, kernel deletions) and does not restate its independent rationale.

---

## 1. Summary

Revision 32 resolves generic-application heads (`map<text, text>`, `set<text>`) through the structure namespace when the head is not otherwise in scope (§3.3.1). This is the only position in the grammar where an *unmarked* token in a type-ref grammar position resolves against the structure namespace; every other crossing is either explicitly marked (the `!` prefix) or grammar-supplied (the implicit desugar targets of the sugar forms). The fallback ordering is additionally a shadowing hazard: a schema that later declares or imports a local `map` template silently changes the meaning of every `map<text, text>` within it.

This change removes the linkage. The kernel's container constructors (`array`, `set`, `map`) become parameterless; the map type gains a sugar form `{K => V}` mirroring the data notation; generic-application heads resolve through the type-name namespace only, making `<>` application a purely schema-local feature (user templates are retained in full); and nested declaration-level container forms are handled by synthesis of internal ("synthetic") entries — at desugar time for parameter-free declarations, and at materialisation time for template applications, so templates themselves never produce synthetic entries.

After this change the namespace rule is statable in one sentence: *the structure namespace is reached by `!` and by sugar; bare names and `<>` heads never leave the type-name namespace.*

## 2. Motivation

Three defects in Revision 32 motivate the change. First, the unmarked crossing: §3.3.1's generic-application-head rule is the sole exception to the invariant that structure-namespace access is syntactically visible, and its "when the name is not otherwise in scope" fallback makes the meaning of a use site depend on distant declarations. Second, the machinery cost: the parameterized kernel constructors require the `= T` routing spelling in constructor declarations, the `value_param` carve-outs of §5.10 and §7.2 for data annotation by parameterized heads, and the layer-visibility apparatus of §5.3 governing which templates each layer can name. Third, an expressiveness asymmetry: deriving parameterized shapes over containers (`<T, N> array<T> ^ …`) is possible only in layers where `array` is nameable as a type-ref, which excludes every ordinary schema.

The change removes all three at once. Constructors become plain record vocabularies; the carve-outs delete because no annotation head is parameterized; and because the sugar reaches the constructors from every layer while templates are ordinary local entries, any schema can now declare and export parameterized container shapes (`string_map => <V> {text => V}`), which was previously a kernel-adjacent privilege.

## 3. Design Decisions

**D1 — Map sugar `{K => V}`.** The type notation for maps mirrors the data notation, completing the existing symmetry (`[a b]` / `[text]`; `{k: v}` / `{name: text}`; `{k => v}` / `{text => text}`). The schema grammar adopts Part 1 §2.8's consume-one-then-inspect brace dispatch verbatim: after `{`, a name followed by `:` is a record, followed by `=>` (or `<`, opening a generic key's arguments) is a map. At declaration level the form admits a size specifier after `;`, desugaring to `min_items`/`max_items` bindings, replacing the one use case of refinement-of-application-heads.

**D2 — Map keys are simple refs; one entry; no `?`; no interior annotations.** The sugar's key position accepts a `type-name` optionally carrying type arguments — not `paren-type`, not bracket forms. This keeps the record/map brace dispatch within two tokens (a `(` inside a brace remains unambiguously a field group) and reflects a semantic judgment: composite map key types deserve a named declaration, and the explicit `!map { key_type: … }` form remains available for them. The sugar takes exactly one `key => value` entry — a map *type* has one key type and one value type; the sugar mirrors the data's shape, not its arity. Neither side of `=>` admits `?`: the kernel's `map` has no `state` field and absence has no defined meaning for map values (absent keys are already a resolver error in Part 1). Annotations inside the sugar braces are a parse error; the declaration is the annotation anchor.

**D3 — De-parameterised constructors.** `array`, `set`, and `map` lose their parameter lists and their `= T` value routes; type slots become plain REQUIRED `type_ref`-typed fields filled by the construction (or by the sugar's desugaring) like any required field. The `= T` routing spelling disappears from constructor declarations entirely: where a parameter must route into a slot, the route now arises at the application site inside a user-template body, not in the constructor's own declaration.

**D4 — Generic heads resolve locally.** `name<args>` heads resolve through the type-name namespace only — parameters, then locals, then imports. An unresolved head is an unresolved-type error. A head that resolves to a *parameter* is likewise an error — §5.10's no-head-abstraction boundary — a case this lookup order makes reachable (`weird => <map> map<text, text>`) and which MUST be diagnosed, not applied. User templates (§5.10) are retained unchanged in declaration, application, partial application, recursion, and materialisation.

**D5 — Synthesis of nested forms, in two moments.** Nested declaration-level forms that carry declaration-only syntax (size specifiers, element/position `?`) cannot be represented as inline `type_ref` structures and are lifted into synthetic entries. For parameter-free declarations, lifting happens at desugar time, before Pass 1, so name population and body resolution never see nesting. For template declarations, nothing lifts: the desugared structure — head, bindings, parameter references, nesting — is the template's recorded open form, and synthesis happens at materialisation, when an application closes and each nested form becomes concrete. A blanket rule applies: if the declaration has parameters, no subform lifts eagerly, even a parameter-free one; deduplication at materialisation makes the outcomes converge and no entries are created for templates never instantiated. Templates therefore never produce synthetic entries; every synthetic entry is born closed.

**D6 — One identity rule for internal entries.** Because synthetics only exist closed, all internal entries carry structural identity. Instantiation entries: structural equality of the flattened, fully-bound application recorded in `source` (§8.2, unchanged). Synthetic entries: structural equality of the resolved binding record, one entry per distinct concrete form schema-wide. The two channels dedupe against each other's products: `[order; 1..]` written directly in a plain declaration and the same form arising inside a materialised template land on the same synthetic entry, because both comparisons occur after names have meaning, over resolved structure. The moment is normative: desugar-time lifting *creates* a synthetic entry, but its identity is settled after Pass 2, when references have resolved — eagerly-lifted synthetics that become structurally identical under resolution merge into one entry, so the one-entry-per-form rule holds schema-wide regardless of which moment produced each candidate.

**D7 — Open bodies represent nesting via `value_form`.** Resolver output serialises template entries, so the open representation is normative, and the Revision 32 vocabulary cannot express a nested form in a slot (`type_ref` carries no bindings; the value group offers only concrete `value` or bare `value_param`). `record_field`'s value group gains a third member, `value_form: top`, holding the nested form at the vocabulary level — an `!record` body under the same conventions as the template's own, recursively. Materialisation collapses it: substitute innermost-out, lift the now-concrete form to a synthetic entry, replace the `value_form` with an ordinary `value` holding the entry's name as a `type_ref`. The closed-entry rule extends by one clause — a closed entry contains no `value_form` members — making "templates don't get synthetic entries" a checkable integrity property (`value_form` present ⟺ pending synthesis ⟺ open entry). `value_form` is resolver-writable only: it has no source spelling (guaranteed by grammar) and never appears in schema source. A dedicated parallel form record (head plus labelled bindings) was considered and rejected: it duplicates what vocabulary-level `!record` bodies already express and forces every consumer to walk two representations; the `value_form: top` route reuses the existing body machinery and the existing subsumption rule admitting `!record` at `top`-typed positions.

**D8 — Inline representation grounded in the desugar table.** With constructors parameterless, positional `type_ref.arguments` for inline sugar forms take their meaning from the desugar table itself: the sugar set is closed and grammar-supplied, so `array` arguments map to `element_type`, `map` arguments to `key_type`, `value_type`, and `tuple`/`choice` variadically onto `elements`/`variants`. Since sizes never appear inline and the value-parameter size templates are deleted, inline constructor arguments are always pure type references; the reference/literal split of `type_argument` remains necessary only for user-template applications. This restores §8.2's structural carrying for inline forms and resolves the objection recorded against it (`SPEC-FEEDBACK.md` #50/#51: the structural channel cannot carry a state or a size) by prohibition rather than by uniform entry injection — with state and size confined to declaration-level syntax, the channel is sufficient for everything the grammar admits inline, and the injected-entry divergence those entries argued for is superseded.

## 4. Normative Changes to Part 2

### 4.1 §3.3.1 The Structure Namespace

Delete the generic-application-heads bullet. The constructor roles reduce to two, both marked or grammar-supplied:

> The structure namespace is consulted at exactly two roles: **constructor-application targets** — the name after `!` when no `^` follows, resolved through the structure namespace **only** and gated on `constructor: true`: a miss is an unresolved-constructor error, a hit whose entry is not a constructor is an error, and local and imported declarations never participate, so no declaration can capture a `!` target. The kernel's self-hosted case needs no ordering rule: when a schema is its own meta, the two namespaces are the same entry set. The direction of service is the invariant: a schema's own `~` declarations serve the layers it governs; its `!` targets come from the meta that governs it. And **the implicit desugar targets of the sugar forms** — `[T]` and the sized forms to `array`, `[T, U]` to `tuple`, `(A | B)` to `choice`, `{K => V}` to `map` — which are grammar-supplied and never author-written. Bare names and generic-application heads never consult the structure namespace: `name<args>` resolves its head through the type-name namespace only (parameters, then locals, then imports), and an unresolved head is an unresolved-type error.

Add a migration diagnostic (SHOULD): when a generic head fails type-name resolution but matches a parameterless constructor in the structure namespace, the diagnostic suggests the sugar spelling or the `!C { … }` form (e.g. `map<text, text>` → "did you mean `{text => text}`?").

### 4.2 §5.3 Type Expressions

Add the map forms to the inline and declaration-level tiers: inline `{K => V}` at any type-ref position; declaration-level `{K => V ; size-spec}` with the size specifier desugaring to `min_items`/`max_items` under the same grammar, bound-coherence, and diagnostic rules as arrays. The N ≤ M coherence check is restated once as a rule on the `min_items`/`max_items` binding pair, applying identically to arrays and maps: resolver error where the bounds are literal at schema load, at materialisation where parameter-bound.

Replace the desugar table:

| Source form | Desugaring |
|---|---|
| `[T]` | `!array { element_type: T }` |
| `[T; N]` | `!array { element_type: T  min_items: N  max_items: N }` |
| `[T; N..]` | `!array { element_type: T  min_items: N }` |
| `[T; ..M]` | `!array { element_type: T  max_items: M }` |
| `[T; N..M]` | `!array { element_type: T  min_items: N  max_items: M }` |
| `[T?]`, `[T?; …]` | the corresponding form with `state: OPTIONAL` bound directly |
| `[T, U, …]` | `!tuple { elements: […] }` |
| `(A \| B)` | `!choice { variants: [A B] }` |
| `{K => V}` | `!map { key_type: K  value_type: V }` |
| `{K => V ; spec}` | `!map { key_type: K  value_type: V  min_items/max_items: … }` |
| `C<args>` | user-template application (§5.10, §8.2); `C` resolves in the type-name namespace only |

Delete: the size-refinement-template routing paragraph; the "element- and position-`?` forms have no template route" paragraph (everything now desugars uniformly and nesting is handled by synthesis); and the layer-visibility paragraph, including the `vector` rationale and the "the `~` flag sets three dials" statement (now two: annotation head and entry weight).

Add a new subsection, **Nested forms and synthetic entries**, carrying: the lift rule and its dividing line (declaration-only syntax lifts; inline-legal forms remain structural in place); the parameter-free-only scope of desugar-time lifting and the blanket no-eager-lift rule for template declarations; synthetic naming (reusing §8.2's internal-name rules — same lexeme class as `type_name`, fresh by construction, disjoint from declared names by construction rather than by an error case, unreachable from source); and an explicit note that synthesis does not relax the inline prohibitions — a size spec or `?` at an inline position remains a parse error.

Update the structural-representation paragraph per D8: positional arguments of inline sugar forms take their meaning from the desugar table; inline constructor arguments are always type references.

### 4.3 §5.6 Canonical Constructor Form

The end-state statement simplifies: with the size templates and `vector` deleted and constructors parameterless, the "nearest `~` constructor in the source chain" for every container closure is the container constructor itself. The pins-defaults-routes taxonomy loses its constructor-declared-route case (`element_type: = T`): routed values in binding records now arise only from user-template parameters. Top-level constructor applications resolve as constructions exactly as in Revision 32.

### 4.4 §5.7 Refinement

Delete refinement-of-application-heads (`map<text, text> ^ { min_items: 1 }`). The kernel-container case becomes unreachable (the head no longer resolves at a refinement source) and its use case is covered by the `;` size specifier on the map sugar. The `refined-def` grammar keeps its optional `<type-args>` head, which now serves user-template heads only; the existing vocabulary-body requirement on refinement sources continues to govern which materialised template entries admit `^`.

The open-modifiers paragraph is updated: parametric `= P` and `~ P` remain the routing spellings, now exclusively a user-template feature; the `value_param` recording rules are unchanged.

### 4.5 §5.10 Templates and Parameters

User templates are retained unchanged in declaration, application, arity checking, partial application, recursion, kind inference, and the v1 boundaries (no head abstraction, no parameter bounds). Changes:

The **open-body representation** admits nesting via `value_form` (D7): a template whose recorded structure contains a nested declaration-level form carries that form at the vocabulary level as a `value_form` member on the routed slot, recursively, alongside the existing `value_param` routing.

**Materialisation** gains the synthesis cascade: when an application closes, substitution rewrites the recorded structure innermost-out; each nested form, once concrete, lifts to a synthetic entry under D6 identity, and the enclosing binding record references it by name, collapsing `value_form` to `value`. Example: `grid => <T, N> [[T; N]; N]` is a single template entry; closing `grid<pixel, 3>` produces one synthetic entry `!array { element_type: pixel  min_items: 3  max_items: 3 }` and one instantiation entry whose binding record references it, with `source: { name: grid  arguments: [{name: pixel} {value: 3}] }`.

**Knot-tying inside synthesis**: a recursive reference located inside a nested form — `tree => <T> { value: T  children: [tree<T>; 1..] }` — denotes, at materialisation, the instantiation entry under construction; the synthetic entry's binding record references that entry by its internal name before the entry is complete. This is the existing knot-tying principle extended into synthetic lifting, and it is the case implementations are most likely to get wrong first (see §8, fixtures).

**Closed-entry rule** extends: an entry whose `parameters` list is empty MUST contain no parameter references at any depth *and no `value_form` members*. The §7.2 data-annotation carve-out for parameterized heads is deleted (see §4.6); the corresponding sentence here deletes with it.

### 4.6 §7.2 Validation

Delete the **parameterized heads over binding records** section and the carve-out it grants. Resolver-output bodies (`!array { element_type: person }`, `!map { key_type: … }`) are now annotations by ordinary parameterless constructors, validated by ordinary record validation: each field against its declared type, a type slot against `type_ref`. No special rule remains; a template with any parameter is a resolver error as a data annotation, without exception.

### 4.7 §8 Resolver Output

**§8.1**: `record_field` gains the `value_form` member (kernel diff, §5 below); `value_form` is documented as resolver-writable only, with no source spelling. Synthetic entries resolve under the existing top-level-construction rule (`kind` from the constructor, `source: array`/`map`/`tuple`/`choice`, binding-record body, no supertypes) and appear in the output schema map, passing ingest under the existing integrity checks plus the extended closed-entry rule. The output SHOULD mark synthetic entries with an annotation in the kernel's existing diagnostic style (e.g. a `@synthetic` marker, same posture as `@alias`: no decode force) so tooling can fold them back into nested display.

**§8.2** retitles to cover template instantiation and synthetic entries together, stating the unified structural-identity rule (D6) and the cross-channel deduplication guarantee. Internal names remain non-normative; consumers compare by `source` (instantiations) or body structure (synthetics), never by name.

**§3.4.1** pipeline: the per-schema sequence becomes **Parse → Desugar → Pass 1 → Pass 2**, with desugar defined as purely syntactic and per-declaration: sugar rewrites to canonical constructor applications; nested declaration-only forms in parameter-free declarations lift to synthetic declarations entering Pass 1 alongside declared names; template declarations contribute exactly one name each.

### 4.8 §12 Grammar

**§12.1 ABNF.** Restructure `container-def` and add the map productions; `type-def`, `element-type`, `size-spec`, `size-bound`, `type-args`, `type-arg` are unchanged (`map-def` arrives through the existing `[type-params] container-def` alternative, so `<V> {text => V}` requires no new production):

```abnf
type-ref = paren-type
         / inline-array
         / inline-map                                  ; NEW
         / type-name "<" type-args ">"
         / type-name

inline-map = "{" ws map-key ws "=>" ws type-ref ws "}" ; NEW

container-def = array-def / tuple-def / map-def

array-def = "[" element-type [ ws ";" ws size-spec ] ws "]"
tuple-def = "[" element-type 1*(separator element-type) "]"
map-def   = "{" ws map-key ws "=>" ws map-value
            [ ws ";" ws size-spec ] ws "}"             ; NEW

map-key   = type-name [ "<" type-args ">" ]
map-value = container-def / type-ref
```

**§12.2 dispatch.** The brace form adopts the data grammar's consume-one-then-inspect dispatch; the prose should state explicitly that an implementation reuses its Part 1 §2.8 machinery, and that the choice is made by one consumed token plus one token of lookahead, preserving the stated lookahead budget in the same sense the data grammar does:

```
; type-def position (after =>):
;   {              → brace form; consume "{" and dispatch on content:
;       "}"          → empty record ({}, top's shape)
;       "("          → record-def (leading field group)
;       "@"          → record-def (annotations precede field names,
;                      §6; the map sugar admits no interior
;                      annotations — D2 — so "@" commits to a record)
;       name ":"     → record-def (field)
;       name "=>"    → map-def
;       name "<"     → map-def (generic key; consume args, expect "=>")
;       name (other) → parse error

; type-ref position:
;   {              → inline-map: "{" name … "=>" required;
;                    "{" name ":" remains a parse error (bare records
;                    must be declared, §5.2) — the diagnostic SHOULD
;                    say so and distinguish the two brace meanings
```

Record bodies (refinement bodies, composition tails, constructor vocabularies) are unaffected by grammar — entries remain `name ":"` — so `config ^ {text => text}` fails at the `=>`; add a diagnostic ("record body expected; `=>` begins a map type only at type positions"). Add the single-entry diagnostic for the map sugar ("a map type is a single `key => value` entry"), anticipating authors carrying the data grammar's multi-entry habit.

**§12.3 adjacency.** Two context additions, no new rules: `=>` gains "map type sugar"; `;` gains "map size spec (map-def, §5.3)". `=>` is already a compound lexer token with optional surrounding whitespace.

## 5. Companion Artifact Changes

**`meta-kernel.tn`.** De-parameterise the container constructors and delete the size templates:

```
array => ~product & {
  access_pattern:  product_access_type = INDEX
  size_type:       product_size_type = VARIABLE
  element_type:    type_ref
  state:           element_state ~ REQUIRED
  unordered:       boolean ~ false
  unique_items:    boolean ~ false
  min_items:       integer?
  max_items:       integer?
}

set => ~array ^ {
  state:        = REQUIRED
  unordered:    = true
  unique_items: = true
}

map => ~product & {
  access_pattern:  product_access_type = NAMED
  size_type:       product_size_type = VARIABLE
  key_type:        type_ref
  value_type:      type_ref
  min_items:       integer?
  max_items:       integer?
}

token_set => !set { element_type: token }        ; NEW
enum      => ~atom & { members: token_set }      ; was set<token>

record_field => {
  name:   field_name
  type:   type_ref
  state:  field_state ~ REQUIRED
  ( value: value | value_param: param_name | value_form: top )?   ; value_form NEW
}

schema => {type_name => type_definition}         ; was map<type_name, type_definition>
```

Delete `array_min`, `array_max`, `array_ranged`. The `token` primitive's doc comment ("core declares no sibling") is unaffected; `token_set` is a kernel-internal named entry required because inline `!` forms remain prohibited at field positions (§5.2) — a rule this change deliberately preserves. The `type_ref`/`type_argument` doc comments are updated per D8. The constructors' shared doc comment describing `= T` type-slot routing rewrites to describe plain REQUIRED `type_ref` slots.

**`meta.tn`.** Delete the `vector` constructor and its doc entry; update the header prose enumerating the constructor families. Fixed arity is expressed with the exact-size sugar (`[T; N]`) or a user template where the intent deserves a name.

**`core.tn`.** No structural changes identified; any occurrences of the deleted spellings in doc prose are updated. Resolver-output fixtures (`*-resolved.tn`) are regenerated: bodies previously headed by deleted templates re-emerge as `!array`/`!map` binding records, and the kernel's own `schema` and `enum` entries change shape as above.

## 6. Deletions Summary

Removed outright by this change: the generic-application-head structure-namespace rule and its fallback ordering (§3.3.1); the size-refinement templates and their routing (§5.3, kernel); `vector` (meta); constructor parameter lists and `= T` slot routing (§4.2, kernel); refinement of application heads (§5.7); the parameterized-heads-over-binding-records carve-out (§5.10, §7.2); the layer-visibility apparatus and the three-dials characterisation of `~` (§5.3); and the "no template route for element/position `?`" special case (§5.3), subsumed by uniform desugar plus synthesis.

## 7. Compatibility and Migration

Revision 32 is not deployed; migrations are mechanical. `map<K, V>` → `{K => V}`; `set<T>` → a named declaration `x => !set { element_type: T }`; `vector<T, S>` → `[T; S]` or a named user template; `array_min`/`array_max`/`array_ranged` applications (kernel-importing layers only) → the size sugar; `map<…> ^ { min_items: … }` → `{… => … ; N..}`. The §4.1 migration diagnostic converts the most common breakage (`map<text, text>` at a type-ref) into a suggested fix at first parse.

## 8. Test Fixtures to Add

Two fixtures exercise the seams where the syntactic and semantic halves meet. First, `grid => <T, N> [[T; N]; N]` closed via `grid<pixel, 3>` from two different declarations MUST yield exactly one instantiation entry and one synthetic entry; a third declaration independently writing `[pixel; 3]` MUST land on that same synthetic (cross-channel dedup, D6). Second, `tree => <T> { value: T  children: [tree<T>; 1..] }` closed via `tree<text>` MUST tie the knot through the synthetic: the synthetic array entry's `element_type` references the `tree<text>` instantiation entry by internal name, recorded before that entry completes (§4.5). Additional parser fixtures: the brace-dispatch matrix of §4.8 (including `{ pair<text> => integer }` and the record-body `=>` error), the single-entry error, and inline `{name: text}` rejection with the distinguishing diagnostic.

## 9. Open Items

Deliberately unresolved here and flagged for the revision editor: whether the map sugar's key restriction (simple refs) should be stated as a grammar fact only or additionally motivated normatively (this report treats it as both a dispatch necessity and a design judgment); whether `@synthetic` output marking is a new meta annotation or reuse of an existing diagnostic convention; and whether §12.2's lookahead-budget prose should be reworded globally now that two productions (schema brace form, data brace form) share the consume-then-inspect idiom.

## 10. Implementation Plan (`ltr8-io-tson-java`)

The change splits into two independently landable tranches. Tranche A needs no template machinery: user templates keep failing at the application site exactly as in the current implementation, so it can merge alone. Tranche B is the retained §5.10 implemented in full.

**Tranche A — sugar, namespaces, kernel.**

1. **Spec artifacts.** Rewrite `spec/m/meta-kernel.tn` and `spec/m/meta.tn` per §5; regenerate the `*-resolved.tn` fixtures; update `TsonBundledSchemas` texts and published digests (`core.tn` changes only if its doc prose does, but any byte moves its digest).
2. **Parser.** `TsonSchemaParser` gains the map productions (§4.8) — a new inline-map `TypeRef` variant and the declaration-level `map-def` — using the §12.2 dispatch, which reuses the Part 1 §2.8 brace machinery the data parser already has. Add the three diagnostics: record-body `=>`, single-entry, and inline `{name: text}` with the two-brace-meanings message. `GenericRef` parsing is unchanged.
3. **Desugarer.** `SchemaDesugarer` adds the map routes; the fixed desugar table (§4.2) replaces parameter-zip routing, deleting the `metaEntries` dependency outright — the phase becomes purely syntactic and per-declaration. Per D8, entry injection for pure inline sugar stops: inline forms ride as structural `type_ref` arguments, and only declaration-only syntax in parameter-free declarations lifts to synthetic entries. Template declarations are passed through whole (no eager lift); the head-vs-structure-namespace checks (`rejectIfTemplateApplication` and the arity/constructor gates at generic heads) delete — but a template *application* must keep failing eagerly at the site that writes it (as a not-yet-implemented gap, including a head arriving by `!!import`) until Tranche B lands, not regress to a read-time failure.
4. **Bootstrap.** `MetaKernelBootstrapResolver` tracks the new kernel text (`token_set`, the `enum`/`schema` respellings); `BOOTSTRAP_CONSTRUCTORS` dissolves into the same fixed table — the bootstrap special case and the general case become one mechanism.
5. **Resolution and linking.** `!` heads resolve structure-namespace-only with the `constructor: true` gate as a loud error (§4.1); the application-side half of the §2.2.2 constructor-eligibility check deletes.
6. **Compilation.** The compiler builds readers from structural inline container refs at field positions (a `type_ref` with arguments, interpreted by the desugar table), not only from entries; synthetic entries compile as ordinary entries.

**Tranche B — user templates (§5.10 retained in full).**

7. **Value model.** `schema.meta.RecordField` gains `value_form` (mind the multi-public-constructor `@Record` trap); `TypeArgument` stays, with its existing sealed-interface cycle guard.
8. **Template engine.** Open-form recording for template declarations; materialisation with the synthesis cascade — innermost-out substitution, lifting each now-concrete form to a synthetic entry, collapsing `value_form` to `value`; knot-tying through synthetics; D6 identity with the post-Pass-2 merge of eagerly-lifted synthetics.
9. **Fixtures.** The two §8 fixtures (`grid` cross-channel dedup, `tree` knot-tying) plus the parser dispatch matrix land as JUnit tests; the conformance suite has no Part 2 layer, so the unit suite is their home for now.
10. **Documentation.** `docs/schema-grammar-and-desugaring.md`, `docs/schema-resolution.md`, `docs/linking-and-compilation.md`, and `CLAUDE.md` update in the same sessions as the code they describe; `SPEC-FEEDBACK.md` #28, #32, #45, #46, #49, #50, #51 gain resolutions citing this report.
