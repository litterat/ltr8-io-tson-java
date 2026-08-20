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

**D5 — Synthesis of nested forms, in two moments.** Nested declaration-level forms that carry declaration-only syntax (size specifiers, element/position `?`) cannot be represented as inline `type_ref` structures and are lifted into synthetic entries. For parameter-free declarations, lifting happens at desugar time, before Pass 1, so name population and body resolution never see nesting. For template declarations, nothing lifts: the desugared structure — head, bindings, parameter references, nesting — is the template's recorded open form, and synthesis happens at materialisation, when an application closes and each nested form becomes concrete. A blanket rule applies: if the declaration has parameters, no subform lifts eagerly, even a parameter-free one; deduplication at materialisation makes the outcomes converge and no entries are created for templates never instantiated. A template declaration's own nested forms lift to **open** synthetic entries — see D7.

**D6 — A synthetic must be closed to be usable.** A synthetic entry exists in one of two states. An **open** synthetic carries parameters and an `instance_template` body (D7); it is an intermediate form, referenced only from within the template that produced it, and no data value ever has it as its type. A **closed** synthetic carries no parameters and an ordinary constructor body; only these are usable as types. Materialisation is the transition, and it is total: closing an application closes every open synthetic it reaches, innermost-out.

All internal entries carry structural identity. Instantiation entries: structural equality of the flattened, fully-bound application recorded in `source` (§8.2, unchanged). Closed synthetic entries: structural equality of the resolved binding record, one entry per distinct concrete form schema-wide. Their `source` names the **constructor** they build, not the application that produced them — an open synthetic's name is internal, so keying a closed entry on it would make identity depend on an unstable name and would prevent the cross-channel dedup below. Open synthetic entries: structural equality **up to consistent renaming of parameters** — `<T, N>` and `<A, B>` over the same shape are the same template — most simply by normalising parameters to positional indices before comparing. The two channels dedupe against each other's products: `[order; 1..]` written directly in a plain declaration and the same form arising inside a materialised template land on the same synthetic entry, because both comparisons occur after names have meaning, over resolved structure. The moment is normative: desugar-time lifting *creates* a synthetic entry, but its identity is settled after Pass 2, when references have resolved — eagerly-lifted synthetics that become structurally identical under resolution merge into one entry, so the one-entry-per-form rule holds schema-wide regardless of which moment produced each candidate.

**D7 — Structural instance templates.** Resolver output serialises template entries, so the open representation is normative, and the Revision 32 vocabulary cannot express one. The obstruction is not the type slot — a `type_ref` may already name a parameter — but the **value** slots: `array`'s `min_items` is declared `integer?`, so a body carrying `min_items: N` cannot be an `!array` body at all, whatever is done to `type_ref`.

The answer is a distinct intermediate vocabulary rather than a widened one. `instance_template` records the constructor it will build and the bindings it will build it from; `template_argument` is the labelled three-way choice a binding may hold:

```
template_argument => { ( param: param_name | value: value | type_ref: type_ref ) }
instance_template => ~product & {
  target:   type_name
  bindings: {field_name => template_argument}
}
```

`target`, not `constructor`: `type_definition.constructor` is already a boolean flag, and `reference => top & { target: type_name }` already uses `target` for the thing an entry points at. `instance_template`, not `template_instance`: the latter reads as an *instance of a template*, which is what a closed instantiation is — this is a template *of* an instance.

An open entry's body is an `instance_template`; a closed entry's body is an ordinary constructor body (`!array`, `!map`, …). The two never mix: **an instance admits no partial bindings**, which is why `ArrayBody.min_items` keeps its declared `integer?` type and needs no param channel.

**Every open entry uses it, including ones that would not need it.** `array_t => <T> !array { element_type: T }` binds only a *type* slot, and `ArrayBody.element_type` is a `type_ref`, which may already name a parameter — so a plain `!array` body would serve. Using one would be locally simpler and globally worse: the unsized form would carry an ordinary body and the sized form an `instance_template`, leaving two open representations and no way to tell an open entry from a closed one by looking. Uniform use is what makes **`instance_template` present ⟺ open entry** hold, and that is the property the closed-entry rule is checked against. Materialisation is where the conversion — and the type check — happens: substituting `N := "two"` yields `!array { element_type: text  min_items: "two" }`, and *that* is the error, reported at the materialising application (§8.2's deferred value-level checks, now with a single home).

`param` earns its place because a value slot has no other way to hold a parameter. To keep body identity well-defined it is also **canonical for a type slot**: a binding whose value is a parameter is always `param`, whatever the slot's declared type, so `param` means "unbound" uniformly and two spellings of one binding cannot arise. `type_argument` is unchanged and keeps its own convention — a parameter there rides the *reference* channel (`{ name: T }`), because a token in that position is always a reference and it has only two channels to distinguish. The two spellings of "a parameter" are a deliberate divergence, not an oversight: one vocabulary has three channels and the other two.

Both types are **resolved-form vocabulary, not grammar**. No schema source spells a tag: an author writes `<T> !array { element_type: T }` and the resolver, knowing `T` is in the declaration's parameter list, emits `{ param: T }`. D9 gives that source form its production.

**D8 — Inline representation grounded in the desugar table.** *(Not adopted — see §11.)* With constructors parameterless, positional `type_ref.arguments` for inline sugar forms take their meaning from the desugar table itself: the sugar set is closed and grammar-supplied, so `array` arguments map to `element_type`, `map` arguments to `key_type`, `value_type`, and `tuple`/`choice` variadically onto `elements`/`variants`. Since sizes never appear inline and the value-parameter size templates are deleted, inline constructor arguments are always pure type references; the reference/literal split of `type_argument` remains necessary only for user-template applications. This restores §8.2's structural carrying for inline forms and resolves the objection recorded against it (`SPEC-FEEDBACK.md` #50/#51: the structural channel cannot carry a state or a size) by prohibition rather than by uniform entry injection — with state and size confined to declaration-level syntax, the channel is sufficient for everything the grammar admits inline, and the injected-entry divergence those entries argued for is superseded.

**D9 — `instance-template`, a production of its own.** Revision 32's `type-def` places `instance` outside the `[type-params]` alternatives, so a constructor application can never carry parameters. Every other alternative can be templated; this one cannot, which leaves a targeted open template with no source spelling — the only grammatical route to one is `refined-def` (`array ^ { min_items: = S }`), which is the size-template shape §6 deletes and which §5.7 admits only over a `~` result.

The new alternative is **not** `[type-params] instance`. The surface syntax is the same — a `!` head over an ordinary record payload — but the two resolve against different vocabulary: `instance` binds its payload through the *constructor's* own reader and yields that constructor's body, while `instance-template` yields an `instance_template` (D7). Same brace, different destination, and the ABNF should say so rather than leaving it to prose. `instance` itself stays unparameterised, which also means nothing gains an optional parameter list that could be silently dropped.

**The payload stays an ordinary `core-value`.** The tagged form (`{ element_type => { param: T } }`) is the *resolved* shape, defined by meta-kernel, not something an author writes:

```
vector => <T, N> !array { element_type: T  min_items: N  max_items: N }
```

Parameterhood comes from the declaration's own `<T, N>`, exactly as it does for a record template (`box => <T> { v: T }` marks nothing at the use site either) — one rule, not two. A sigil at the binding would be more locally legible, inconsistent with the form beside it, and would need retrofitting to record templates to stay coherent.

**It is the fallback spelling, not the primary one.** For the four sugared constructors the compact form already exists and is already grammatical: `vector => <T, N> [T; N]` is `[type-params] container-def`, in the ABNF today. `instance-template` is the route to a constructor with *no* sugar — `set`, and whatever a meta layer adds (`bounded_set => <N> !set { element_type: text  min_items: N }`) — and the target the sugar desugars *into*. Its ergonomics matter less than its existence.

**One limitation, stated rather than discovered.** The payload being a `core-value` means a nested sugar form inside it is not expressible: `!array { element_type: [T] }` reads `[T]` as a data array holding the token `T`, not as an array type. Nesting goes through a second named template, consistent with §5.2's instinct that a composite shape earns a declaration.

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

A template declaration may now be an **instance** as well as a record, a container or a reference (D9), so `<T, N> !array { element_type: T  min_items: 1  max_items: N }` is a well-formed type-def.

The **open-body representation** is `instance_template` (D7). A sugar form inside a template declaration desugars to the same construction it would outside one — the desugar table of §4.2 is used unchanged — except that a binding whose value is a parameter is recorded as `param` rather than as a concrete `value` or `type_ref`, which is what makes the body an `instance_template` rather than an instance. Nesting needs no special member: an inner form lifts to its own **open** synthetic entry, and the outer binding holds an ordinary `type_ref` applying it. Worked, at its smallest — `box => <T> { a: [T] }`. The inline `[T]` lifts to an **open** synthetic whose source form is D9's production, and the field applies it:

```
; desugared source form
array_t => <T> !array { element_type: T }
box     => <T> { a: array_t<T> }

; resolved form of the synthetic
array_t => !type_definition {
  kind:        PRODUCT
  parameters:  [T]
  body: !instance_template {
    target:   array
    bindings: { element_type => { param: T } }
  }
}
```

`array_t` is an internal name, and the application in `box`'s field rides `type_ref.arguments` — the channel that already means "an application", and now means nothing else. Note the two spellings of "a parameter" meeting here: `{ param: T }` inside the bindings, `{ name: T }` inside `box`'s `type_ref.arguments`. That is D7's deliberate divergence, not a slip.

Closing `box<text>` substitutes `T := text`, which makes every binding of `array_t<text>` concrete, so the `instance_template` collapses to an ordinary constructor body — and lands on the very entry a directly written `[text]` produces (D6's cross-channel dedup):

```
array_text_<hash> => !type_definition { kind: PRODUCT  source: array  body: !array { element_type: text } }
```

The same shape scales to the nested and sized forms, one open synthetic per form, closing innermost-out — subject to §9's open item, since a size specifier at a field position is a parse error today.

**Materialisation** closes innermost-out. Closing `grid<pixel, 3>` substitutes, and each open synthetic becomes a closed one as its bindings go concrete:

```
c1 => !array { element_type: pixel  min_items: 1  max_items: 3 }   ; source: array
c2 => !array { element_type: c1     min_items: 2  max_items: 3 }   ; source: array
c3 => !record { fields: [ { name: x  type: c2 } ] }
      ; source: { name: grid  arguments: [{name: pixel} {value: 3}] }
```

`c1` and `c2` are closed synthetics keyed on body structure, so `c1` is the same entry an independently written `[pixel; 1..3]` produces anywhere in the schema. `c3` is an instantiation entry keyed on `source`, whose head is the author's own `grid` and therefore comparable. A user declaration naming the application (`pixel_grid => grid<pixel, 3>`) is an alias to `c3` under §8.3, not a second entry.

**Knot-tying**: a recursive reference inside a nested form denotes, at materialisation, the instantiation entry under construction; the open synthetic's binding references that entry by its internal name before the entry is complete.

**Closed-entry rule** extends: an entry whose `parameters` list is empty MUST contain no parameter references at any depth *and no `instance_template` body at any depth* — `instance_template` present ⟺ open entry, a checkable integrity property. The §7.2 data-annotation carve-out for parameterized heads is deleted (see §4.6); the corresponding sentence here deletes with it.

### 4.6 §7.2 Validation

Delete the **parameterized heads over binding records** section and the carve-out it grants. Resolver-output bodies (`!array { element_type: person }`, `!map { key_type: … }`) are now annotations by ordinary parameterless constructors, validated by ordinary record validation: each field against its declared type, a type slot against `type_ref`. No special rule remains; a template with any parameter is a resolver error as a data annotation, without exception.

### 4.7 §8 Resolver Output

**§8.1**: `instance_template` and `template_argument` join the vocabulary (kernel diff, §5 below); `record_field` is unchanged. Synthetic entries resolve under the existing top-level-construction rule (`kind` from the constructor, `source: array`/`map`/`tuple`/`choice`, binding-record body, no supertypes) and appear in the output schema map, passing ingest under the existing integrity checks plus the extended closed-entry rule. The output SHOULD mark synthetic entries with an annotation in the kernel's existing diagnostic style (e.g. a `@synthetic` marker, same posture as `@alias`: no decode force) so tooling can fold them back into nested display.

**§8.2** retitles to cover template instantiation and synthetic entries together, stating the unified structural-identity rule (D6) and the cross-channel deduplication guarantee. Internal names remain non-normative; consumers compare by `source` (instantiations) or body structure (closed synthetics, up to parameter renaming for open ones), never by name.

**§3.4.1** pipeline: the per-schema sequence becomes **Parse → Desugar → Pass 1 → Pass 2**, with desugar defined as purely syntactic and per-declaration: sugar rewrites to canonical constructor applications; nested declaration-only forms in parameter-free declarations lift to synthetic declarations entering Pass 1 alongside declared names; template declarations contribute exactly one name each.

### 4.8 §12 Grammar

**§12.1 ABNF.** Restructure `container-def`, add the map productions, and add the templated-instance alternative to `type-def` (D9); `element-type`, `size-spec`, `size-bound`, `type-args`, `type-arg` are unchanged (`map-def` arrives through the existing `[type-params] container-def` alternative, so `<V> {text => V}` requires no new production):

```abnf
type-def = atom-refinement
         / instance                                    ; unchanged; never parameterised
         / instance-template                           ; NEW
         / [type-params] ["~"] structural-def
         / [type-params] container-def
         / [type-params] type-ref

instance-template = type-params ws "!" type-name ws core-value   ; NEW
```

Decidable on one token after the optional parameter list: `!` with no parameter list opens an `instance`, `!` with one an `instance-template`, and `~`/`{`/`(`/`[`/a name the remaining alternatives as before. `<` only ever starts `type-params`, so consuming it first costs no lookahead. Inside the `!` branch a following `^` continues to separate `atom-refinement` from `instance`, which keeps its unparameterised form — a refinement of an atom instance has no parameter to take.

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

**§12.3 adjacency.** Two context additions, no new rules: `=>` gains "map type sugar"; `;` gains "map size spec (map-def, §5.3)". `=>` is already a compound lexer token with optional surrounding whitespace. The `bindings` map of an `instance_template` is resolver output, not source, so it introduces no adjacency case of its own.

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

record_field => {          ; unchanged
  name:   field_name
  type:   type_ref
  state:  field_state ~ REQUIRED
  ( value: value | value_param: param_name )?
}

template_argument => {     ; NEW — one binding of an open form (D7)
  ( param: param_name | value: value | type_ref: type_ref )
}

instance_template => ~product & {   ; NEW — the open counterpart of an instance
  access_pattern:  product_access_type = NAMED
  size_type:       product_size_type = VARIABLE
  target:          type_name
  bindings:        {field_name => template_argument}
}

schema => {type_name => type_definition}         ; was map<type_name, type_definition>
```

Delete `array_min`, `array_max`, `array_ranged`. The `token` primitive's doc comment ("core declares no sibling") is unaffected; `token_set` is a kernel-internal named entry required because inline `!` forms remain prohibited at field positions (§5.2) — a rule this change deliberately preserves. The `type_ref`/`type_argument` doc comments are updated per D8. The constructors' shared doc comment describing `= T` type-slot routing rewrites to describe plain REQUIRED `type_ref` slots.

**`meta.tn`.** Delete the `vector` constructor and its doc entry; update the header prose enumerating the constructor families. Fixed arity is expressed with the exact-size sugar (`[T; N]`) or a user template where the intent deserves a name.

**`core.tn`.** No structural changes identified; any occurrences of the deleted spellings in doc prose are updated. Resolver-output fixtures (`*-resolved.tn`) are regenerated: bodies previously headed by deleted templates re-emerge as `!array`/`!map` binding records, and the kernel's own `schema` and `enum` entries change shape as above.

## 6. Deletions Summary

Removed outright by this change: the generic-application-head structure-namespace rule and its fallback ordering (§3.3.1); the size-refinement templates and their routing (§5.3, kernel); `vector` (meta); constructor parameter lists and `= T` slot routing (§4.2, kernel); refinement of application heads (§5.7); the parameterized-heads-over-binding-records carve-out (§5.10, §7.2); the layer-visibility apparatus and the three-dials characterisation of `~` (§5.3); and the "no template route for element/position `?`" special case (§5.3), subsumed by uniform desugar plus synthesis.

Added rather than removed, and worth listing beside them: `instance_template` and `template_argument` in the kernel (D7), and the `instance-template` production in §12.1 (D9).

## 7. Compatibility and Migration

Revision 32 is not deployed; migrations are mechanical. `map<K, V>` → `{K => V}`; `set<T>` → a named declaration `x => !set { element_type: T }`; `vector<T, S>` → `[T; S]` or a named user template; `array_min`/`array_max`/`array_ranged` applications (kernel-importing layers only) → the size sugar; `map<…> ^ { min_items: … }` → `{… => … ; N..}`. The §4.1 migration diagnostic converts the most common breakage (`map<text, text>` at a type-ref) into a suggested fix at first parse.

## 8. Test Fixtures to Add

Two fixtures exercise the seams where the syntactic and semantic halves meet. First, `grid => <T, N> [[T; N]; N]` closed via `grid<pixel, 3>` from two different declarations MUST yield exactly one instantiation entry and one synthetic entry; a third declaration independently writing `[pixel; 3]` MUST land on that same synthetic (cross-channel dedup, D6). Second, `tree => <T> { value: T  children: [tree<T>; 1..] }` closed via `tree<text>` MUST tie the knot through the synthetic: the synthetic array entry's `element_type` references the `tree<text>` instantiation entry by internal name, recorded before that entry completes (§4.5). Additional parser fixtures: the brace-dispatch matrix of §4.8 (including `{ pair<text> => integer }` and the record-body `=>` error), the single-entry error, and inline `{name: text}` rejection with the distinguishing diagnostic.

## 9. Open Items

Deliberately unresolved here and flagged for the revision editor: whether the map sugar's key restriction (simple refs) should be stated as a grammar fact only or additionally motivated normatively (this report treats it as both a dispatch necessity and a design judgment); whether `@synthetic` output marking is a new meta annotation or reuse of an existing diagnostic convention; and whether §12.2's lookahead-budget prose should be reworded globally now that two productions (schema brace form, data brace form) share the consume-then-inspect idiom.

**Whether the inline prohibition holds at a field position inside a template.** §5.3 confines size specifiers and element/position `?` to declaration level, and this report preserves that (§4.2). It follows that `<T> { children: [tree<T>; 1..] }` is a parse error, and so is `<T, N> { x: [[T; 1..N]; 2..N] }` — a nested open form cannot occur at a record field at all, only inside another declaration-level form. Both of §8's fixtures as drafted rely on the field spelling and are ungrammatical as written. Either they respell through `[type-params] container-def` at declaration level, or the prohibition relaxes at a field position. The choice is narrow but it decides how much of D7's machinery is reachable: with the prohibition intact, an open synthetic can only ever arise from a declaration whose *whole body* is a container form.

`SPEC-FEEDBACK.md` #31 is the pending entry in this area and is expected to carry the resolution, but as drafted it does not: it collapses `inline-array` and `container-def` into one production reachable from `type-ref` while keeping the restriction as a note ("valid only where the bracket form is a declaration body or nested within one"), and says outright that "every shape rejected today stays rejected — this is a simplification of how the rule is written, not a change to the language". Relaxing the field position is that change, so #31 wants a paragraph saying so, or a companion entry.

## 10. Implementation Plan (`ltr8-io-tson-java`)

The change splits into three independently landable tranches. Tranche A needs no template machinery: user templates keep failing at the application site exactly as in the current implementation, so it can merge alone. Tranche B is §5.10 for **record** templates only — the form whose parameters occupy field types, and which needs no intermediate vocabulary at all. Tranche C adds **instance** templates: sugar forms inside a template declaration, `instance_template`/`template_argument`, and D9's grammar.

The B/C split is deliberate and load-bearing. A record template (`<T> { a: T  b: text ~ "test" }`) substitutes into `record_field.type` and `record_field.value`, both of which already exist and already admit a parameter; it needs open-form recording, substitution, arity and kind checking, recursion and knot-tying, and materialisation — the whole engine — but no new vocabulary and no grammar change. Everything D7 and D9 introduce exists solely to let a *constructor application* be templated. Building the engine first against the form that needs nothing new keeps the two failure surfaces apart.

**Tranche A — sugar, namespaces, kernel.**

1. **Spec artifacts.** Rewrite `spec/m/meta-kernel.tn` and `spec/m/meta.tn` per §5; regenerate the `*-resolved.tn` fixtures; update `TsonBundledSchemas` texts and published digests (`core.tn` changes only if its doc prose does, but any byte moves its digest).
2. **Parser.** `TsonSchemaParser` gains the map productions (§4.8) — a new inline-map `TypeRef` variant and the declaration-level `map-def` — using the §12.2 dispatch, which reuses the Part 1 §2.8 brace machinery the data parser already has. Add the three diagnostics: record-body `=>`, single-entry, and inline `{name: text}` with the two-brace-meanings message. `GenericRef` parsing is unchanged.
3. **Desugarer.** `SchemaDesugarer` adds the map routes; the fixed desugar table (§4.2) replaces parameter-zip routing, deleting the `metaEntries` dependency outright — the phase becomes purely syntactic and per-declaration. Per D8, entry injection for pure inline sugar stops: inline forms ride as structural `type_ref` arguments, and only declaration-only syntax in parameter-free declarations lifts to synthetic entries. Template declarations are passed through whole (no eager lift); the head-vs-structure-namespace checks (`rejectIfTemplateApplication` and the arity/constructor gates at generic heads) delete — but a template *application* must keep failing eagerly at the site that writes it (as a not-yet-implemented gap, including a head arriving by `!!import`) until Tranche B lands, not regress to a read-time failure.
4. **Bootstrap.** `MetaKernelBootstrapResolver` tracks the new kernel text (`token_set`, the `enum`/`schema` respellings); `BOOTSTRAP_CONSTRUCTORS` dissolves into the same fixed table — the bootstrap special case and the general case become one mechanism.
5. **Resolution and linking.** `!` heads resolve structure-namespace-only with the `constructor: true` gate as a loud error (§4.1); the application-side half of the §2.2.2 constructor-eligibility check deletes.
6. **Compilation.** The compiler builds readers from structural inline container refs at field positions (a `type_ref` with arguments, interpreted by the desugar table), not only from entries; synthetic entries compile as ordinary entries.

**Tranche B — record templates (§5.10, no new vocabulary).**

Scope: a template declaration whose body is a record, a reference, or a composition/refinement — parameters occupying field types and field values. Explicitly **out of scope**: any sugar form inside a template declaration (`<T> { v: [T] }`, `<T, N> [T; N]`), which keeps failing eagerly at the application site as it does today.

7. **Resolution.** `DefinitionResolver` records a parameterised declaration's open form. `record_field.type` naming a parameter and `record_field.value_param` already exist and are already produced; what is missing is the whole-declaration handling and the closed-entry check that an entry with no parameters carries no parameter reference at any depth.
8. **Materialisation.** Substitution of an application's arguments into the recorded open form; arity and kind checking against the applied signature; the deferred value-level checks (§8.2) at the materialising application; recursion with knot-tying to the instantiation entry under construction; a termination guard for non-regular recursion (`weird => <T> { next: weird<[T]>? }`), which no section of this report covers and which dedup-by-identity cannot catch.
9. **Identity.** Instantiation entries keyed on the flattened application in `source` (D6, unchanged), with `pixel_grid => grid<pixel, 3>` resolving as an alias to the entry rather than a second one.
10. **Eager rejection retires here, and only here.** `SchemaDesugarer` currently fails any application whose head this document declares or imports. Materialisation replaces that for record templates; an application of a template containing a sugar form must keep failing until Tranche C.

**Tranche C — instance templates (D7, D9).**

Scope: sugar forms inside a template declaration, and the intermediate vocabulary they need. Take `[T]` first — the unsized inline form, whose only parameter rides a type slot — and only then the sized and nested forms, which are what actually require `template_argument`'s `param` channel.

**Stage one is the grammar alone**, with `box => <T> { a: [T] }` as the whole target — the smallest form that needs any of this.

11. **Grammar.** `TsonSchemaParser.parseTypeDef` parses `[type-params]` before dispatching on `!`, and a `!` behind a non-empty parameter list is an `instance-template` rather than an `instance` (D9). Today the `!` check precedes `parseTypeParamsOpt`, faithful to the ABNF as written. A new AST node, not a widened `Instance`.
12. **Value model.** `schema.meta` gains `InstanceTemplate` and `TemplateArgument` (a sealed interface over `param`/`value`/`type_ref` — mind the `TypeArgument` cycle trap, and the multi-public-constructor `@Record` trap). `RecordField` is unchanged.
13. **Desugarer.** A sugar form inside a parameterised declaration lifts to an **open** synthetic entry, using the same §4.2 table as everywhere else, with a parameter-valued binding recorded as `param`. The enclosing binding holds an ordinary `type_ref` applying it. The blanket no-eager-lift rule of D5 narrows accordingly: nothing lifts to a *closed* entry, but open lifting is exactly how nesting is represented.
14. **Materialisation cascade.** Closing an application closes every open synthetic it reaches, innermost-out; `instance_template` becomes an ordinary constructor body as its bindings go concrete, and a binding that does not type-check against the slot (`min_items: "two"`) is the error, reported at the materialising application.
15. **Identity.** Closed synthetics keyed on body structure and deduped cross-channel with directly written forms; open synthetics keyed up to consistent parameter renaming (D6).
16. **Fixtures.** `grid` (cross-channel dedup) and `tree` (knot-tying) land as JUnit tests — both need respelling first, since a size specifier at a field position is a parse error under §5.3's inline prohibition, which this report preserves (§9). The conformance suite has no Part 2 layer, so the unit suite is their home.

**Across all three.** `docs/schema-grammar-and-desugaring.md`, `docs/schema-resolution.md`, `docs/linking-and-compilation.md` and `CLAUDE.md` update in the same session as the code they describe; `SPEC-FEEDBACK.md` #28, #32, #45, #46 gain resolutions citing this report.

---

## 11. Superseded within this report

**D8 is not adopted.** It proposed that inline sugar ride as a structural `type_ref` with arguments rather than as an injected entry, claiming to resolve `SPEC-FEEDBACK.md` #50/#51 by prohibition. Four arguments were weighed and none holds. An entry set wider than the declaration set is already normal — `subtypes` and `disjoint` are resolver-derived too, so §8 output has never been the author's declarations and nothing else. The `@synthetic` marker it would avoid is an optional display hint. "Ingest gets simpler" is a claim about unwritten code. And a derived name has to be stable *within* an implementation, including across `!!import`, never agreed *between* them — §8.2 disclaims the names and a comparison tool canonicalises.

Two arguments run the other way. D7's own reasoning rejects a second representation of a nested form because it "forces every consumer to walk two representations", which is exactly what D8 imposes on every container. And the deduplication does not disappear, only relocates: a form used in five records must not compile five readers, so the compiler needs a memo keyed on ref structure — the naming rule rebuilt and called a cache.

The rule kept instead, and relied on throughout §4.5: **`type_ref.arguments` non-empty means an open form — a template application — and everything closed is an entry referenced by a bare name.** That pairs with D7's own invariant (`instance_template` present ⟺ open entry) and lets the closed-entry rule be checked structurally, with no vocabulary needed to read a `type_ref`. `SPEC-FEEDBACK.md` #49/#50/#51 therefore stay open, as discussion points for the revision rather than as items this report closes.
