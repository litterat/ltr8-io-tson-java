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

**D5 — One lift rule.** Every sugar form lifts at desugar: a **concrete** form to a closed synthetic entry, a **parameter-bearing** form — one mentioning a type parameter of the enclosing declaration — to an open synthetic entry capturing those parameters in declaration order. A declaration's own body never lifts; it *is* the declaration (`ids => [order]` is the construction in place, not a reference to one). Materialisation creates no synthetic entries at all — it closes open ones, innermost-out.

Stated this way the rule needs no case for whether the enclosing declaration has parameters, which is what the two-moments version got wrong: a concrete `[order]` inside `<T> { a: T  b: [order] }` lifts **closed**, at desugar, like any other concrete form. Lifting it open instead would produce an open synthetic with no parameters, contradicting D7's `instance_template` ⟺ open invariant; refusing to lift it at all — the previous rule — rejected a template needing nothing the phase cannot already do. Eagerly-lifted closed entries merge correctly under D6's identity-settles-after-Pass-2 paragraph, so the eager moment costs nothing.

**D6 — A synthetic must be closed to be usable.** A synthetic entry exists in one of two states. An **open** synthetic carries parameters and an `instance_template` body (D7); it is an intermediate form, referenced only from within the template that produced it, and no data value ever has it as its type. A **closed** synthetic carries no parameters and an ordinary constructor body; only these are usable as types. Materialisation is the transition, and it is total: closing an application closes every open synthetic it reaches, innermost-out.

All internal entries carry structural identity. Instantiation entries: structural equality of the flattened, fully-bound application recorded in `source` (§8.2, unchanged). Closed synthetic entries: structural equality of the resolved binding record, one entry per distinct concrete form schema-wide. Their `source` names the **constructor** they build, not the application that produced them — an open synthetic's name is internal, so keying a closed entry on it would make identity depend on an unstable name and would prevent the cross-channel dedup below. Open synthetic entries: structural equality **up to consistent renaming of parameters** — `<T, N>` and `<A, B>` over the same shape are the same template — most simply by normalising parameters to positional indices before comparing. The two channels dedupe against each other's products: `[order; 1..]` written directly in a plain declaration and the same form arising inside a materialised template land on the same synthetic entry, because both comparisons occur after names have meaning, over resolved structure. The moment is normative: desugar-time lifting *creates* a synthetic entry, but its identity is settled after Pass 2, when references have resolved — eagerly-lifted synthetics that become structurally identical under resolution merge into one entry, so the one-entry-per-form rule holds schema-wide regardless of which moment produced each candidate.

**D7 — Structural instance templates.** Resolver output serialises template entries, so the open representation is normative, and the Revision 32 vocabulary cannot express one. The obstruction is not the type slot — a `type_ref` may already name a parameter — but the **value** slots: `array`'s `min_items` is declared `integer?`, so a body carrying `min_items: N` cannot be an `!array` body at all, whatever is done to `type_ref`.

The answer is a distinct intermediate vocabulary rather than a widened one. `instance_template` records the constructor it will build and the bindings it will build it from; `template_argument` is the labelled three-way choice a binding may hold:

```tson
template_argument => { ( param: param_name | value: value | type_ref: type_ref ) }
instance_template => top & {
  target:   type_name
  bindings: {field_name => template_argument}
}
```

**`top &`, not `~product`, and no `~`.** `reference => top & { target: type_name }` is the precedent, and it holds for both halves. `product` obliges an entry to supply `access_pattern` and `size_type`, and an `instance_template` never describes a value — no data value ever has one as its type — so both would be meaningless filler. The missing `~` matters for the same reason `reference` has none: a constructor is what a *schema* applies through `!C value`, gated on `constructor: true`, and nobody writes `foo => !instance_template { … }`. Under D9 the author writes `<T> !array { … }`, where the `!` names `array`; the resolver *produces* the `instance_template` body, exactly as it produces `!reference { target: token }`.

`target`, not `constructor`: `type_definition.constructor` is already a boolean flag, and `reference` already uses `target` for the thing an entry points at. `instance_template`, not `template_instance`: the latter reads as an *instance of a template*, which is what a closed instantiation is — this is a template *of* an instance. And not `data_template`: the payload is not data, which is the entire reason the type exists.

An open entry's body is an `instance_template`; a closed entry's body is an ordinary constructor body (`!array`, `!map`, …). The two never mix: **an instance admits no partial bindings**, which is why `ArrayBody.min_items` keeps its declared `integer?` type and needs no param channel.

**Every open entry uses it, including ones that would not need it.** `array_t => <T> !array { element_type: T }` binds only a *type* slot, and `ArrayBody.element_type` is a `type_ref`, which may already name a parameter — so a plain `!array` body would serve. Using one would be locally simpler and globally worse: the unsized form would carry an ordinary body and the sized form an `instance_template`, leaving two open representations and no way to tell an open entry from a closed one by looking. Uniform use is what makes **`instance_template` present ⟺ open entry** hold, and that is the property the closed-entry rule is checked against. Materialisation is where the conversion — and the type check — happens: substituting `N := "two"` yields `!array { element_type: text  min_items: "two" }`, and *that* is the error, reported at the materialising application (§8.2's deferred value-level checks, now with a single home).

`param` earns its place because a value slot has no other way to hold a parameter. To keep body identity well-defined it is also **canonical for a type slot**: a binding whose value is a parameter is always `param`, whatever the slot's declared type, so `param` means "unbound" uniformly and two spellings of one binding cannot arise. `type_argument` is unchanged and keeps its own convention — a parameter there rides the *reference* channel (`{ name: T }`), because a token in that position is always a reference and it has only two channels to distinguish. The two spellings of "a parameter" are a deliberate divergence, not an oversight: one vocabulary has three channels and the other two.

**Checking splits by what the parameter list obscures, and nothing more.** At the **declaration**: binding keys against the target's vocabulary (`<T> !array { elemen_type: T }` is a typo, not a template), REQUIRED-without-default coverage (`<T> !array { min_items: 2 }` binds no `element_type`, so no application of it could produce a valid array), and the typing of every *concrete* binding (`min_items: "two"` is not an integer whatever `T` becomes). At **materialisation**, only what substitution supplies: a `param`-bound slot's value, which is §8.2's deferred value-level check as drafted.

The split is not merely a quality preference. A *closed* instance is already checked in full where it is written — its body binds through the constructor's own reader, so §7.2's closure rule catches all three — and so is a record template. Deferring everything would make `instance-template` the one form in the language validated at use rather than at declaration, and a broken template would ship and fail at its first user's application site rather than at the typo. §13 names the precedent: pre-concepts C++ checked templates only at instantiation.

**A declared parameter the body never references is an error.** `<T> !array { element_type: text }` is not a degenerate-but-legal template that collapses to a closed entry; it is a mistake, and the same rule holds for every template form (`box => <T> { v: text }` likewise). This removes the open/closed ambiguity at its source rather than deciding it: there is no such thing as an `instance_template` with no parameter-bearing binding, so D6's identity question about whether unused parameters participate in open-synthetic normalisation does not arise.

Both types are **resolved-form vocabulary, not grammar**. No schema source spells a tag: an author writes `<T> !array { element_type: T }` and the resolver, knowing `T` is in the declaration's parameter list, emits `{ param: T }`. D9 gives that source form its production.

**D9 — `instance-template`, a production of its own.** Revision 32's `type-def` places `instance` outside the `[type-params]` alternatives, so a constructor application can never carry parameters. Every other alternative can be templated; this one cannot, which leaves a targeted open template with no source spelling — the only grammatical route to one is `refined-def` (`array ^ { min_items: = S }`), which is the size-template shape §6 deletes and which §5.7 admits only over a `~` result.

It is a production of its own rather than an optional parameter list on `instance`: the surface syntax is the same, but the two resolve against different vocabulary — `instance` binds its payload through the *constructor's* own reader, `instance-template` yields an `instance_template` (D7) — and an ABNF should make that visible. `instance` itself stays unparameterised. (§11 has the fuller argument.)

**The payload is a production of its own, narrower than `core-value`.** A `core-value` would admit `<T> !array [1 2 3]`, `<T> !array "x"`, and a nested record in a binding — none of which a `template_argument` can carry, since it is `param | value | type_ref` with no collection case (`<T> !choice { variants: [T text] }`, a parameter inside a collection-typed slot, has no resolved form at all). The grammar refuses what the vocabulary cannot hold. The tagged form (`{ element_type => { param: T } }`) is the *resolved* shape, defined by meta-kernel, not something an author writes:

```tson
vector => <T, N> !array { element_type: T  min_items: N  max_items: N }
```

Parameterhood comes from the declaration's own `<T, N>`, exactly as it does for a record template (`box => <T> { v: T }` marks nothing at the use site either) — one rule, not two. A sigil at the binding would be more locally legible, inconsistent with the form beside it, and would need retrofitting to record templates to stay coherent.

**It is the fallback spelling, not the primary one.** For the four sugared constructors the compact form already exists and is already grammatical: `vector => <T, N> [T; N]` is `[type-params] type-ref` over a `bracket-type`. `instance-template` is the route to a constructor with *no* sugar — `set`, and whatever a meta layer adds (`bounded_set => <N> !set { element_type: text  min_items: N }`) — and the target the sugar desugars *into*. Its ergonomics matter less than its existence.

**One limitation, enforced rather than discovered.** `template-arg` admits a name, a name with arguments, or a literal — not the bracket or paren forms — so `<T> !array { element_type: [T] }` is a parse error rather than a form that reads `[T]` as a data array holding the token `T`. Nesting goes through a second named template, consistent with §5.2's instinct that a composite shape earns a declaration. Admitting the sugar forms here instead would mean `template_argument` grows a case and the closing cascade has to lift from *inside* a binding; worth reviewing once the basic form is working, and deliberately out for this iteration.

**`template-def` requires at least one binding.** An empty payload is a template that binds nothing, which no constructor application needs and which the closed form has no counterpart for — §2.1 refuses `{}` for a schema map on the same footing.

**D10 — One bracket production, one map production, and no positional restriction.** Revision 32 spells each container twice: `container-def` at a declaration body, admitting a size specifier and an element/position `?`, and `inline-array`/`inline-map` at a type-ref position, admitting neither — with a prose tie-break in §12.1 because `type-def` is otherwise ambiguous between them. The two collapse into one production reachable from `type-ref`, and the restriction is **dropped**, not relocated.

*Why the split existed, and why it no longer does.* §5.3's own account is representational: the `?` forms "desugar directly and become **the declaration's body**", which a nested or inline one cannot do. Once every form lifts to an entry (D5) there is no becoming-the-declaration's-body to protect — an inline `[T; 1..5]` lifts exactly as `[T]` already does. The rule outlived its reason, and §11 is where that reason went.

*The alternative considered.* Collapsing while keeping the restriction leaves the language unchanged, which is what `SPEC-FEEDBACK.md` #31 proposed. But the restriction then has to move from the grammar into a "declaration-reachable" flag threaded through the parser — true at a type-def body, propagating through element positions and declaration-level map values, false through a record field type — and a flag can be wrong where two productions cannot. Dropping the restriction removes the flag along with it, so the relaxing half makes the parser *simpler*, not merely smaller.

*What it costs.* Sized and `?`-bearing forms become legal at a field, so they become common **anonymous** synthetic entries where today every one of them is a name an author chose. That is the same trade `[text]` already makes, but it raises the stakes on internal naming rather than lowering them. This is a **language change**: documents that are errors today become valid, which is what #31 explicitly disclaimed, so that entry wants a paragraph saying the reasoning has moved on. A style preference for keeping field types simple is legitimate and survives as a style preference; it is no longer a grammar rule.

## 4. Normative Changes to Part 2

### 4.1 §3.3.1 The Structure Namespace

Delete the generic-application-heads bullet. The constructor roles reduce to two, both marked or grammar-supplied:

> The structure namespace is consulted at exactly two roles: **constructor-application targets** — the name after `!` when no `^` follows, resolved through the structure namespace **only** and gated on `constructor: true`: a miss is an unresolved-constructor error, a hit whose entry is not a constructor is an error, and local and imported declarations never participate, so no declaration can capture a `!` target. The kernel's self-hosted case needs no ordering rule: when a schema is its own meta, the two namespaces are the same entry set. The direction of service is the invariant: a schema's own `~` declarations serve the layers it governs; its `!` targets come from the meta that governs it. And **the implicit desugar targets of the sugar forms** — `[T]` and the sized forms to `array`, `[T, U]` to `tuple`, `(A | B)` to `choice`, `{K => V}` to `map` — which are grammar-supplied and never author-written. Bare names and generic-application heads never consult the structure namespace: `name<args>` resolves its head through the type-name namespace only (parameters, then locals, then imports), and an unresolved head is an unresolved-type error.

Add a migration diagnostic (SHOULD): when a generic head fails type-name resolution but matches a parameterless constructor in the structure namespace, the diagnostic suggests the sugar spelling or the `!C { … }` form (e.g. `map<text, text>` → "did you mean `{text => text}`?").

### 4.2 §5.3 Type Expressions

**Delete the inline/declaration-level tier distinction.** There is one bracket form and one map form, legal at every type-ref position, each admitting a size specifier after `;` and an element/position `?` wherever it appears (D10). §5.3's paragraphs separating the two tiers, and the sentence confining the `?` forms to "the declaration's body", go with it.

Add the map forms alongside the bracket ones: `{K => V}` and `{K => V ; size-spec}`, with the size specifier desugaring to `min_items`/`max_items` under the same grammar, bound-coherence, and diagnostic rules as arrays. The N ≤ M coherence check is restated once as a rule on the `min_items`/`max_items` binding pair, applying identically to arrays and maps: resolver error where the bounds are literal at schema load, at materialisation where parameter-bound.

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

Add a new subsection, **Nested forms and synthetic entries**, carrying: D5's lift rule and its dividing line, which is *closed vs open* — a concrete form lifts to a closed synthetic entry, a form mentioning one of the enclosing declaration's parameters to an open one, and a declaration's own body never lifts; synthetic naming (reusing §8.2's internal-name rules — fresh by construction, disjoint from declared names, unreachable from source); and a note that the inline prohibitions it used to have to work around are gone (D10) — a size spec or `?` is legal wherever the form is.

Delete the structural-representation paragraph rather than updating it. There is no structural representation of an inline form to describe: every sugar form is an entry referenced by a bare name, and `type_ref.arguments` means an open form and nothing else (§11).

### 4.3 §5.6 Canonical Constructor Form

The end-state statement simplifies: with the size templates and `vector` deleted and constructors parameterless, the "nearest `~` constructor in the source chain" for every container closure is the container constructor itself. The pins-defaults-routes taxonomy loses its constructor-declared-route case (`element_type: = T`): routed values in binding records now arise only from user-template parameters. Top-level constructor applications resolve as constructions exactly as in Revision 32.

### 4.4 §5.7 Refinement

Delete refinement-of-application-heads (`map<text, text> ^ { min_items: 1 }`). The kernel-container case becomes unreachable (the head no longer resolves at a refinement source) and its use case is covered by the `;` size specifier on the map sugar. The `refined-def` grammar keeps its optional `<type-args>` head, which now serves user-template heads only; the existing vocabulary-body requirement on refinement sources continues to govern which materialised template entries admit `^`.

The open-modifiers paragraph is updated: parametric `= P` and `~ P` remain the routing spellings, now exclusively a user-template feature; the `value_param` recording rules are unchanged.

### 4.5 §5.10 Templates and Parameters

User templates are retained unchanged in declaration, application, arity checking, partial application, recursion, kind inference, and the v1 boundaries (no head abstraction, no parameter bounds). Changes:

A template declaration may now be an **instance** as well as a record, a container or a reference (D9), so `<T, N> !array { element_type: T  min_items: 1  max_items: N }` is a well-formed type-def.

The **open-body representation** is `instance_template` (D7). A sugar form inside a template declaration desugars to the same construction it would outside one — the desugar table of §4.2 is used unchanged — except that a binding whose value is a parameter is recorded as `param` rather than as a concrete `value` or `type_ref`, which is what makes the body an `instance_template` rather than an instance. Nesting needs no special member: an inner form lifts to its own **open** synthetic entry, and the outer binding holds an ordinary `type_ref` applying it. Worked, at its smallest — `box => <T> { a: [T] }`. The inline `[T]` lifts to an **open** synthetic whose source form is D9's production, and the field applies it.

The desugared source form:

```tson
array_t => <T> !array { element_type: T }
box     => <T> { a: array_t<T> }
```

and the synthetic's resolved form:

```tson
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

```tson
array_text_<hash> => !type_definition { kind: PRODUCT  source: array  body: !array { element_type: text } }
```

The same shape scales to the nested and sized forms, one open synthetic per form, closing innermost-out — subject to §9's open item, since a size specifier at a field position is a parse error today.

**Materialisation** closes innermost-out. Closing `grid<pixel, 3>` substitutes, and each open synthetic becomes a closed one as its bindings go concrete:

```tson
c1 => !array { element_type: pixel  min_items: 1  max_items: 3 }
c2 => !array { element_type: c1     min_items: 2  max_items: 3 }
c3 => !record { fields: [ { name: x  type: c2 } ] }
```

`c1` and `c2` are closed synthetics keyed on body structure, each recording `source: array`, so `c1` is the same entry an independently written `[pixel; 1..3]` produces anywhere in the schema. `c3` is an instantiation entry keyed on `source` — `{ name: grid  arguments: [{name: pixel} {value: 3}] }`, the application it closes — whose head is the author's own `grid` and therefore comparable. A user declaration naming the application (`pixel_grid => grid<pixel, 3>`) is an alias to `c3` under §8.3, not a second entry.

**Knot-tying**: a recursive reference inside a nested form denotes, at materialisation, the instantiation entry under construction; the open synthetic's binding references that entry by its internal name before the entry is complete.

**Closed-entry rule** extends: an entry whose `parameters` list is empty MUST contain no parameter references at any depth *and no `instance_template` body at any depth* — `instance_template` present ⟺ open entry, a checkable integrity property. The §7.2 data-annotation carve-out for parameterized heads is deleted (see §4.6); the corresponding sentence here deletes with it.

### 4.6 §7.2 Validation

Delete the **parameterized heads over binding records** section and the carve-out it grants. Resolver-output bodies (`!array { element_type: person }`, `!map { key_type: … }`) are now annotations by ordinary parameterless constructors, validated by ordinary record validation: each field against its declared type, a type slot against `type_ref`. No special rule remains; a template with any parameter is a resolver error as a data annotation, without exception.

### 4.7 §8 Resolver Output

**§8.1**: `instance_template` and `template_argument` join the vocabulary (kernel diff, §5 below); `record_field` is unchanged. Synthetic entries resolve under the existing top-level-construction rule (`kind` from the constructor, `source: array`/`map`/`tuple`/`choice`, binding-record body, no supertypes) and appear in the output schema map, passing ingest under the existing integrity checks plus the extended closed-entry rule. The output SHOULD mark synthetic entries with an annotation in the kernel's existing diagnostic style (e.g. a `@synthetic` marker, same posture as `@alias`: no decode force) so tooling can fold them back into nested display.

**§8.2** retitles to cover template instantiation and synthetic entries together, stating the unified structural-identity rule (D6) and the cross-channel deduplication guarantee. Internal names remain non-normative; consumers compare by `source` (instantiations) or body structure (closed synthetics, up to parameter renaming for open ones), never by name.

**§3.4.1** pipeline: the per-schema sequence becomes **Parse → Desugar → Pass 1 → Pass 2**, with desugar defined as purely syntactic and per-declaration: sugar rewrites to canonical constructor applications, and every nested form lifts to a synthetic declaration — closed or open per D5 — entering Pass 1 alongside declared names. A template declaration contributes its own name plus one per open form it holds.

### 4.8 §12 Grammar

**§12.1 ABNF.** Collapse each container to a single production reachable from `type-ref` (D10), add the map forms, and add the templated-instance alternative to `type-def` (D9). `size-spec`, `size-bound`, `type-args` and `type-arg` are unchanged; `container-def`, `inline-array` and `element-type` are replaced. (The `;` in these `abnf` blocks is ABNF's own comment syntax, RFC 5234 §3.9. TSON defines none — Part 1 §2.4 — so the `tson` blocks throughout this report carry no comments, and `;` in one is the size-specifier token.)

```abnf
type-def = atom-refinement
         / instance                                    ; unchanged; never parameterised
         / instance-template                           ; NEW
         / [type-params] ["~"] structural-def
         / [type-params] type-ref                      ; CHANGED: container-def alternative deleted

instance-template = type-params ws "!" type-name ws template-def  ; NEW

template-def  = "{" ws template-bind *( separator template-bind ) ws "}"
template-bind = field-name ws ":" ws template-arg
template-arg  = type-name [ "<" type-args ">" ] / value-literal
```

`template-def` mirrors `template_argument` one-for-one: a bare name — a parameter or a type, decided at resolution rather than by the grammar — a name carrying arguments, or a literal. Flat, one binding per slot, at least one binding, and no bracket or paren form (see D9).

Decidable on one token after the optional parameter list: `!` with no parameter list opens an `instance`, `!` with one an `instance-template`, and `~`/`{`/`(`/`[`/a name the remaining alternatives as before. `<` only ever starts `type-params`, so consuming it first costs no lookahead. Inside the `!` branch a following `^` continues to separate `atom-refinement` from `instance`, which keeps its unparameterised form — a refinement of an atom instance has no parameter to take.

```abnf
type-ref = paren-type
         / bracket-type                                ; CHANGED: was inline-array
         / map-type                                    ; NEW
         / type-name "<" type-args ">"
         / type-name

bracket-type = "[" element-type [ ws ";" ws size-spec ] ws "]"
             / "[" element-type 1*(separator element-type) "]"

map-type     = "{" ws map-key ws "=>" ws element-type
               [ ws ";" ws size-spec ] ws "}"          ; NEW

map-key      = type-name [ "<" type-args ">" ]
element-type = type-ref [ "?" ]
```

One production per container, at every position. `type-def` reaches both through `type-ref` like anything else, so §12.1's prose tie-break for a leading `[` disappears rather than needing rewording — there is nothing left to disambiguate. Nesting is the recursion already present in `element-type`, not a second reference to a declaration-level production, so `[[T; N]; N]` and `{text => [order; 1..]}` work by the same rule that makes `[[T]]` work.

An element's `?` and a field's own `?` do not collide: a field is `field-name ":" type-ref [ "?" ]`, and the element's belongs to `element-type` inside the brackets, so `xs: [T?]?` is unambiguous — element optional, field optional.

**§12.2 dispatch.** The brace form adopts the data grammar's consume-one-then-inspect dispatch; the prose should state explicitly that an implementation reuses its Part 1 §2.8 machinery, and that the choice is made by one consumed token plus one token of lookahead, preserving the stated lookahead budget in the same sense the data grammar does:

```text
type-def position (after =>):
  {              → brace form; consume "{" and dispatch on content:
      "}"          → empty record ({}, top's shape)
      "("          → record-def (leading field group)
      "@"          → record-def (annotations precede field names,
                     §6; the map sugar admits no interior
                     annotations — D2 — so "@" commits to a record)
      name ":"     → record-def (field)
      name "=>"    → map-def
      name "<"     → map-def (generic key; consume args, expect "=>")
      name (other) → parse error

type-ref position:
  {              → inline-map: "{" name … "=>" required;
                   "{" name ":" remains a parse error (bare records
                   must be declared, §5.2) — the diagnostic SHOULD
                   say so and distinguish the two brace meanings
```

Record bodies (refinement bodies, composition tails, constructor vocabularies) are unaffected by grammar — entries remain `name ":"` — so `config ^ {text => text}` fails at the `=>`; add a diagnostic ("record body expected; `=>` begins a map type only at type positions"). Add the single-entry diagnostic for the map sugar ("a map type is a single `key => value` entry"), anticipating authors carrying the data grammar's multi-entry habit.

**§12.3 adjacency.** Two context additions, no new rules: `=>` gains "map type sugar"; `;` gains "map size spec (map-def, §5.3)". `=>` is already a compound lexer token with optional surrounding whitespace. The `bindings` map of an `instance_template` is resolver output, not source, so it introduces no adjacency case of its own.

## 5. Companion Artifact Changes

**`meta-kernel.tn`.** De-parameterise the container constructors and delete the size templates:

```tson
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

token_set => !set { element_type: token }
enum      => ~atom & { members: token_set }

record_field => {
  name:   field_name
  type:   type_ref
  state:  field_state ~ REQUIRED
  ( value: value | value_param: param_name )?
}

template_argument => {
  ( param: param_name | value: value | type_ref: type_ref )
}

instance_template => top & {
  target:    type_name
  bindings:  {field_name => template_argument}
}

schema => {type_name => type_definition}
```

Entry by entry: `token_set`, `template_argument` and `instance_template` are new; `enum`'s `members` was `set<token>` and `schema` was `map<type_name, type_definition>`; `record_field` is unchanged and is shown only for context. `template_argument` is one binding of an open form (D7). `instance_template` is the open counterpart of an instance, written `top &` and without `~` for the same reason `reference` is: it never describes a value, so it carries no `access_pattern`/`size_type`, and it is never a `!` target in a schema (D7).

Delete `array_min`, `array_max`, `array_ranged`. The `token` primitive's doc comment ("core declares no sibling") is unaffected; `token_set` is a kernel-internal named entry required because inline `!` forms remain prohibited at field positions (§5.2) — a rule this change deliberately preserves. The `type_ref` doc comment's "applications of constructors are carried structurally and materialise no entries" sentence is replaced: every sugar form is an entry, and `arguments` marks an open form (§11). The constructors' shared doc comment describing `= T` type-slot routing rewrites to describe plain REQUIRED `type_ref` slots.

**`meta.tn`.** Delete the `vector` constructor and its doc entry; update the header prose enumerating the constructor families. Fixed arity is expressed with the exact-size sugar (`[T; N]`) or a user template where the intent deserves a name.

**`core.tn`.** No structural changes identified; any occurrences of the deleted spellings in doc prose are updated. Resolver-output fixtures (`*-resolved.tn`) are regenerated: bodies previously headed by deleted templates re-emerge as `!array`/`!map` binding records, and the kernel's own `schema` and `enum` entries change shape as above.

## 6. Deletions Summary

Removed outright by this change: the generic-application-head structure-namespace rule and its fallback ordering (§3.3.1); the size-refinement templates and their routing (§5.3, kernel); `vector` (meta); constructor parameter lists and `= T` slot routing (§4.2, kernel); refinement of application heads (§5.7); the parameterized-heads-over-binding-records carve-out (§5.10, §7.2); the layer-visibility apparatus and the three-dials characterisation of `~` (§5.3); and the "no template route for element/position `?`" special case (§5.3), subsumed by uniform desugar plus synthesis.

Added rather than removed, and worth listing beside them: `instance_template` and `template_argument` in the kernel (D7), and the `instance-template` production in §12.1 (D9).

Also removed, and a **language change** rather than a restatement: the inline/declaration-level tier split (§5.3) and the positional restriction on size specifiers and element/position `?` (D10). Documents that are parse errors today become valid.

Also a change to Revision 32 semantics beyond this report's core proposal, and easy to miss inside §4.1: **`!` resolves through the structure namespace only**, with no type-name precedence and no local capture. Under Revision 32 a local declaration named `array` could capture `!array`; it can no longer.

## 7. Compatibility and Migration

Revision 32 is not deployed; migrations are mechanical. `map<K, V>` → `{K => V}`; `set<T>` → a named declaration `x => !set { element_type: T }`; `vector<T, S>` → `[T; S]` or a named user template; `array_min`/`array_max`/`array_ranged` applications (kernel-importing layers only) → the size sugar; `map<…> ^ { min_items: … }` → `{… => … ; N..}`. The §4.1 migration diagnostic converts the most common breakage (`map<text, text>` at a type-ref) into a suggested fix at first parse.

## 8. Test Fixtures to Add

Four fixtures exercise the seams where the syntactic and semantic halves meet.

**Counting entries.** Every count below distinguishes three kinds, because D6 gives them different identity rules and a bare total says nothing: an **open synthetic** carries parameters and an `instance_template` body; a **closed synthetic** carries an ordinary constructor body and is keyed on the resolved binding record; an **instantiation entry** is keyed on the flattened application recorded in `source`. Declared entries are counted separately again.

**1. The smallest form (`box`).** `box => <T> { a: [T] }` closed via `box<text>` MUST derive exactly one open synthetic (`<p0> !array { element_type: p0 }`), one closed synthetic (`!array { element_type: text }`), and one instantiation entry for `box<text>`. This is the least a sugar form over a parameter can be, and the target the grammar and the vocabulary were introduced for.

**2. The grid (primary: record form).** `grid => <T, N> { x: [[T; 1..N]; 2..N] }` closed via `grid<pixel, 3>` from two different declarations MUST derive **six entries in all**: `grid` itself, two open synthetics (one per container level), two closed synthetics, and one instantiation entry. A third declaration independently writing the inner form `[pixel; 1..3]` MUST land on that same closed synthetic — cross-channel dedup (D6), since a form written directly and the same form arising inside a materialised template are one type.

**3. Reuse.** Closing `grid<pixel, 4>` after `grid<pixel, 3>` MUST leave `grid` and **both open synthetics exactly as they were**, producing three fresh closed entries beside the first three. Templates are consulted, never modified, by closure. The property is not tidiness: substitution walks a template's recorded open form, and a walk that rewrote what it read would make the second closure depend on the first.

**4. The knot (`tree`).** `tree => <T> { value: T  children: [tree<T>; 1..]? }` closed via `tree<text>` MUST tie the knot through the synthetic: the synthetic array entry's `element_type` references the `tree<text>` instantiation entry by internal name, recorded before that entry completes (§4.5).

**The whole-body grid is the fallback spelling, and it needs one entry more to say the same thing.** `grid => <T, N> [[T; N]; N]` derives one open synthetic, two closed entries, **and an instantiation** — but the instantiation cannot be the closure itself, as it is in the record form. That closure is a closed *synthetic*, and D6 requires such an entry's `source` to name the constructor it builds: keying it on the application would tie identity to the internal name of the open synthetic that produced it, and would split `[pixel; 3]` written directly from the same form arriving through a template. So the instantiation is a **separate reference entry** whose `source` is the application and whose body points at the form.

The record form needs only one entry because substituting a record yields a record — structurally distinct from any synthetic, so it can carry the application itself. Both spellings therefore record the application exactly once, and §8.2's "instantiation entries are keyed on the flattened application recorded in `source`" holds of both.

**A generated head mints none.** Closing `array_p0_p1_p1_…<pixel, 3>` — an open synthetic closing its own intermediate form — records no application, because nobody wrote one. An entry named for that head would carry an internal name into identity, which is what the rule above exists to prevent.

**Additional parser fixtures:** the brace-dispatch matrix of §4.8 (including `{ pair<text> => integer }` and the record-body `=>` error), the single-entry error, and inline `{name: text}` rejection with the distinguishing diagnostic.

## 9. Open Items

Deliberately unresolved here and flagged for the revision editor: whether the map sugar's key restriction (simple refs) should be stated as a grammar fact only or additionally motivated normatively (this report treats it as both a dispatch necessity and a design judgment); whether `@synthetic` output marking is a new meta annotation or reuse of an existing diagnostic convention; and whether §12.2's lookahead-budget prose should be reworded globally now that two productions (schema brace form, data brace form) share the consume-then-inspect idiom.

*(The inline-prohibition question that stood here is resolved by D10: the restriction is dropped, §8's fixtures become grammatical as drafted — including the record-form grid it now takes as primary — and `SPEC-FEEDBACK.md` #31 wants a paragraph recording that its "not a change to the language" framing no longer holds.)*

## 10. Implementation Plan (`ltr8-io-tson-java`)

The change splits into four independently landable tranches. **A** needs no template machinery: user templates keep failing at the application site exactly as in the current implementation, so it can merge alone. **B** is §5.10 for *record* templates only — the form whose parameters occupy field types and values, and which needs no intermediate vocabulary at all. **C** is D10's grammar collapse, which touches no template machinery either and is worth landing on its own because it *shrinks* what D has to handle. **D** adds *instance* templates: sugar forms written over a parameter, `instance_template`/`template_argument`, and D9's production.

The B/D split is deliberate and load-bearing. A record template (`<T> { a: T  b: text ~ "test" }`) substitutes into `record_field.type` and `record_field.value`, both of which already exist and already admit a parameter; it needs open-form recording, substitution, arity and kind checking, recursion and knot-tying, and materialisation — the whole engine — but no new vocabulary and no grammar change. Everything D7 and D9 introduce exists solely to let a *constructor application* be templated. Building the engine first against the form that needs nothing new keeps the two failure surfaces apart.

C sits between them rather than before B because it is independent of both: it is a parser and AST change with no resolution consequences, and its only effect on D is to widen where an open form may appear — which D handles by one mechanism however many positions feed it.

**Tranche A — sugar, namespaces, kernel.**

1. **Spec artifacts.** Rewrite `spec/m/meta-kernel.tn` and `spec/m/meta.tn` per §5; regenerate the `*-resolved.tn` fixtures; update `TsonBundledSchemas` texts and published digests (`core.tn` changes only if its doc prose does, but any byte moves its digest).
2. **Parser.** `TsonSchemaParser` gains the map productions (§4.8) — a new inline-map `TypeRef` variant and the declaration-level `map-def` — using the §12.2 dispatch, which reuses the Part 1 §2.8 brace machinery the data parser already has. Add the three diagnostics: record-body `=>`, single-entry, and inline `{name: text}` with the two-brace-meanings message. `GenericRef` parsing is unchanged.
3. **Desugarer.** `SchemaDesugarer` adds the map routes; the fixed desugar table (§4.2) replaces parameter-zip routing, deleting the `metaEntries` dependency outright — the phase becomes purely syntactic and per-declaration. Entry injection is **unchanged**: every sugar form lifts, as it already did, which is what §11 leaves standing. The head-vs-structure-namespace checks (`rejectIfTemplateApplication` and the arity/constructor gates at generic heads) delete — but a template *application* must keep failing eagerly at the site that writes it (as a not-yet-implemented gap, including a head arriving by `!!import`) until Tranche B lands, not regress to a read-time failure.
4. **Bootstrap.** `MetaKernelBootstrapResolver` tracks the new kernel text (`token_set`, the `enum`/`schema` respellings); `BOOTSTRAP_CONSTRUCTORS` dissolves into the same fixed table — the bootstrap special case and the general case become one mechanism.
5. **Resolution and linking.** `!` heads resolve structure-namespace-only with the `constructor: true` gate as a loud error (§4.1); the application-side half of the §2.2.2 constructor-eligibility check deletes.

The compiler is untouched: it builds readers from entries, and every sugar form is an entry (§11).

**Tranche B — record templates (§5.10, no new vocabulary).**

Scope: a template declaration whose body is a record, a reference, or a composition/refinement — parameters occupying field types and field values. Explicitly **out of scope**: any sugar form inside a template declaration (`<T> { v: [T] }`, `<T, N> [T; N]`), which keeps failing eagerly at the application site as it does today.

7. **Resolution.** `DefinitionResolver` records a parameterised declaration's open form. `record_field.type` naming a parameter and `record_field.value_param` already exist and are already produced; what is missing is the whole-declaration handling and the closed-entry check that an entry with no parameters carries no parameter reference at any depth.
8. **Materialisation.** Substitution of an application's arguments into the recorded open form; arity and kind checking against the applied signature; the deferred value-level checks (§8.2) at the materialising application; recursion with knot-tying to the instantiation entry under construction; a termination guard for non-regular recursion (`weird => <T> { next: weird<[T]>? }`), which no section of this report covers and which dedup-by-identity cannot catch.
9. **Identity.** Instantiation entries keyed on the flattened application in `source` (D6, unchanged), with `pixel_grid => grid<pixel, 3>` resolving as an alias to the entry rather than a second one.
10. **Eager rejection retires here, and only here.** `SchemaDesugarer` currently fails any application whose head this document declares or imports. Materialisation replaces that for record templates; an application of a template containing a sugar form must keep failing until Tranche C.

**Tranche C — one bracket production, one map production (D10).**

Scope: the grammar collapse and the dropped restriction. No template machinery, no new vocabulary, and no resolution change — every form this legalises lifts to a synthetic entry by the path D5 already describes.

11. **Grammar and AST.** `bracket-type` and `map-type` replace `inline-array`/`inline-map` and `container-def`; `type-def` loses its `container-def` alternative and reaches both through `type-ref`. Six AST types collapse to two plus `element-type`: `InlineArrayRef`/`ArrayContainerDef`/`InlineTupleRef`/`TupleContainerDef` and the two map nodes become one bracket node and one map node. `TsonSchemaParser`'s three "not permitted at an inline type-ref position" diagnostics delete outright — there is no position where the form is not permitted.
12. **Desugarer.** The two parallel walks merge. `SchemaDesugarer` currently reaches the same output through `typeRef` for the inline family and `binding`/`containerDef`/`exprRef` for the declaration-level one; with one node family there is one walk. **The distinction that survives is positional, not structural**: a declaration's own body is the construction in place, every other occurrence lifts (D5), and the walk already knows which it is in.
13. **Fixtures.** `xs: [text; 1..5]` and `xs: [T?]` at a field position, `{text => order; 1..}` inline, and `xs: [T?]?` — element optional *and* field optional — which is the one place the two `?` positions meet.

**Tranche D — instance templates (D7, D9).**

Scope: sugar forms inside a template declaration, and the intermediate vocabulary they need. Take `[T]` first — the unsized inline form, whose only parameter rides a type slot — and only then the sized and nested forms, which are what actually require `template_argument`'s `param` channel.

**Stage one is the grammar alone**, with `box => <T> { a: [T] }` as the whole target — the smallest form that needs any of this. **Stage two is everything else in this tranche**, and lands together because the pieces do not stand apart: an open synthetic with no vocabulary to record it is unrepresentable, and a vocabulary nothing closes is unusable.

14. **Grammar.** `TsonSchemaParser.parseTypeDef` parses `[type-params]` before dispatching on `!`, and a `!` behind a non-empty parameter list is an `instance-template` rather than an `instance` (D9). Today the `!` check precedes `parseTypeParamsOpt`, faithful to the ABNF as written. A new AST node, not a widened `Instance`.
15. **Value model.** `schema.meta` gains `InstanceTemplate` and `TemplateArgument` (a sealed interface over `param`/`value`/`type_ref` — mind the `TypeArgument` cycle trap, and the multi-public-constructor `@Record` trap). `RecordField` is unchanged.
16. **Desugarer.** A sugar form inside a parameterised declaration lifts to an **open** synthetic entry, using the same §4.2 table as everywhere else, with a parameter-valued binding recorded as `param`. The enclosing binding holds an ordinary `type_ref` applying it. The blanket no-eager-lift rule of D5 narrows accordingly: nothing lifts to a *closed* entry, but open lifting is exactly how nesting is represented.
17. **Materialisation cascade.** Closing an application closes every open synthetic it reaches, innermost-out; `instance_template` becomes an ordinary constructor body as its bindings go concrete, and a binding that does not type-check against the slot (`min_items: "two"`) is the error, reported at the materialising application.
18. **Identity.** Closed synthetics keyed on body structure and deduped cross-channel with directly written forms; open synthetics keyed up to consistent parameter renaming (D6).
19. **Fixtures.** §8's four fixtures land as JUnit tests in the spelling it writes them (`ApplicationInContainerPositionTest`); the conformance suite has no Part 2 layer, so the unit suite is their home. A container position holding an *application* needs no name after all, for the open case: the binding keeps the `type_ref` whole and materialisation closes it once the parameters are bound. The *closed* case (`[box<text>]`) still has none, and is refused at the form — it has to write the application to the wire, and the only wire form carrying arguments is `type_ref`'s record form, which this implementation cannot read.

**One correction to §8 itself, now applied above.** As first written, `children: [tree<T>; 1..]` pinned a floor of one on the recursive position, so every node required a child and **no finite document validated against it**. The `?` is the whole fix: an optional field is a base case, where a possibly-empty container is the other one the `1..` had removed. The type-level structure the fixture tests is unaffected — the knot is the point, and it ties either way. `SPEC-FEEDBACK.md` #25 has the resolution, now that non-productive types are rejected at link time: the original spelling is no longer a schema that compiles and cannot be satisfied, it is a schema that does not compile.

**What Tranche D leaves for a later tranche.** Two shapes, both narrower than the mechanism that reaches them. A parameter inside a **collection-valued** slot (`tuple`'s `elements`, `choice`'s `variants`) has no open form at all, because `template_argument` has no collection case — D5 states the lift rule over every sugar form and D9 concedes this case has none, which is a contradiction inside this report; `SPEC-FEEDBACK.md` #53 puts it to the spec with both resolutions (scope the rule, or give `template_argument` a recursive fourth channel). And the *closed* container-position application above. Everything else works: `<T> { a: [T] }`, `<T> [T]`, `<N> { a: [text; N] }` and the explicit `<N> !set { element_type: text  min_items: N }` all resolve, close, compile and read, and `<"two">` is rejected by the target constructor's own reader — §8.2's deferred value-level check, needing no code of its own.

**Across all four.** `docs/schema-grammar-and-desugaring.md`, `docs/schema-resolution.md`, `docs/linking-and-compilation.md` and `CLAUDE.md` update in the same session as the code they describe; `SPEC-FEEDBACK.md` #28, #32, #45, #46 gain resolutions citing this report.

---

## 11. Superseded within this report

Proposals from earlier drafts that do not survive. They are collected here rather than deleted because the *reasoning* is the useful part — each was plausible enough to be written down once, and without the argument against it the next reader re-proposes it. The sections above describe only the surviving design; nothing here is normative.

The design decisions in §3 keep their original numbering, so **D8 is absent from that list** — it is below. Renumbering would invalidate every cross-reference in this report and in the implementation that cites it.

---

**D8 — Inline forms carried structurally rather than lifted.** *Proposed:* with constructors parameterless, positional `type_ref.arguments` take their meaning from the desugar table itself — `array` arguments map to `element_type`, `map` arguments to `key_type`/`value_type`, `tuple`/`choice` variadically — so an inline sugar form rides as a structural `type_ref` and materialises no entry. Since sizes never appear inline and the size templates are deleted, inline arguments would always be pure type references, which was claimed to resolve `SPEC-FEEDBACK.md` #50/#51 by prohibition rather than by uniform injection.

*Rejected.* Four arguments were weighed and none holds. An entry set wider than the declaration set is already normal — `subtypes` and `disjoint` are resolver-derived too, so §8 output has never been the author's declarations and nothing else. The `@synthetic` marker it would avoid is an optional display hint. "Ingest gets simpler" is a claim about unwritten code. And a derived name has to be stable *within* an implementation, including across `!!import`, never agreed *between* them — §8.2 disclaims the names and a comparison tool canonicalises them, so the interop problem it solved was not one the format has.

Two arguments run the other way. D7's own reasoning rejects a second representation of a nested form because it "forces every consumer to walk two representations", which is exactly what D8 imposes on every container. And the deduplication does not disappear, only relocates: a form used in five records must not compile five readers, so the compiler needs a memo keyed on ref structure — the naming rule rebuilt and called a cache.

*Consequences of the rejection, relied on throughout:* **`type_ref.arguments` non-empty means an open form — a template application — and everything closed is an entry referenced by a bare name.** That pairs with D7's own invariant (`instance_template` present ⟺ open entry) and lets the closed-entry rule be checked structurally, with no vocabulary needed to read a `type_ref`. `SPEC-FEEDBACK.md` #49/#50/#51 therefore stay open, as discussion points for the revision rather than as items this report closes.

---

**D7's first form — `value_form: top` on `record_field`.** *Proposed:* `record_field`'s value group gains a third member holding a nested declaration-level form at the vocabulary level, an `!record` body recursively, alongside the existing `value_param` routing; materialisation collapses it to an ordinary `value`. A dedicated parallel form record — head plus labelled bindings — was considered and rejected in the same breath, as duplicating what vocabulary-level `!record` bodies already express.

*Rejected*, for three reasons and one irony.

The obstruction was **misdiagnosed**. It is not the type slot: `array`'s `element_type` is a `type_ref`, and a `type_ref` may already name a parameter, so `<T> !array { element_type: T }` needs nothing new. It is the **value** slots — `min_items` is declared `integer?`, so a body carrying `min_items: N` cannot be an `!array` body however `record_field` is widened.

It was on the **wrong node**. The nested case it exists for never reaches a `record_field` at all: a field type is an inline position, and §5.3 admits no size specifier there, so a nested declaration-only form can only occur inside another declaration-level form — a declaration's *body*, not a field.

It was the **wrong channel**. `value` and `value_param` say what a field's *value* is; `value_form` would put a *type* there.

And the irony: the "dedicated parallel form record" it rejected is essentially what `instance_template` is. Its own rejection argument — do not make consumers walk two representations — was the argument against itself, since `value_form` is an optional member every consumer of every field must test for, where the phase distinction (open body vs closed body) is checkable in one place.

---

**D9's first form — `[type-params] instance`.** *Proposed:* the existing `instance` alternative simply moves inside the optional parameter list, so `<T, N> !array { … }` becomes a type-def with no new production.

*Rejected.* The surface syntax is right and survives, but the two forms resolve against **different vocabulary**: `instance` binds its payload through the constructor's own reader and yields that constructor's body, while a parameterised one yields an `instance_template`. Folding them into one alternative hides that the payload's *type* changes, which is exactly what an ABNF should make visible. It also required an optional parameter list on `atom-refinement`, which nothing consumes — a silent-drop hazard for `<N> !integer ^ { min: N }`. And the payload needed its own production regardless: `core-value` admits `<T> !array [1 2 3]`, a scalar payload, and nested records in a binding, none of which `template_argument` can carry.

---

**D5's first form — synthesis in two moments.** *Proposed:* nested declaration-level forms lift at desugar for parameter-free declarations and at materialisation for template declarations, under a blanket rule that a declaration with parameters lifts nothing, not even a parameter-free subform.

*Rejected.* It needed a case for whether the enclosing declaration has parameters, and got that case wrong in both directions. Refusing to lift a *concrete* form inside a template rejected templates needing nothing the phase cannot already do — `<T> { a: T  b: [order] }` mentions no parameter in `[order]` and was refused along with `<T> { a: [T] }`, which genuinely blocks. Lifting it *open* instead would produce an open synthetic with no parameters, contradicting D7's `instance_template` ⟺ open invariant. D5's replacement needs no such case: concrete lifts closed, parameter-bearing lifts open, a declaration's own body never lifts.

## 12. Review Recommendations

Recorded from review of this draft. Where a recommendation conflicts with a section above, the recommendation is the intended direction and the text is the artifact to fix.

**Applied:** R6 (§8 rebuilt, with the counts split open/closed/instantiation and the whole-body grid's missing instantiation entry stated), R1 (the D8 sweep — the sections above describe only the surviving design, and §11 collects what does not survive), R2 (D5 rewritten as the uniform lift rule, and implemented), R3 (adopted as D10 and scheduled as Tranche C; §9's open item is settled and both §8 fixtures become grammatical), R4 (folded into D7, with the degenerate case decided: an unreferenced parameter is an error), R5 (implemented as a declaration-time regularity check), R7 (folded into §6). **Outstanding:** R8, R9(b), R10.

**R1 — Sweep the §11 rejection of D8 through the document.** *(Applied.)* §11 rejects D8, but five passages still implement it, and one contradicts itself:

1. **D5** — its body states "for template declarations, nothing lifts" and the blanket no-eager-lift rule, while its own last sentence (and Tranche C item 13, and §4.5's worked `box`) has nested forms lifting to open synthetics at desugar. The last sentence is the current model; the body is two models stale. Rewrite per R2.
2. **§4.2, new subsection** — retains "declaration-only syntax lifts; inline-legal forms remain structural in place". Under §11 everything lifts; the dividing line is closed-vs-open, not lifted-vs-structural.
3. **§4.2, closing line** — "Update the structural-representation paragraph per D8" becomes a deletion: there is no structural inline representation to describe.
4. **§4.7, §3.4.1 note** — "nested declaration-only forms in parameter-free declarations lift" is stale on both qualifiers.
5. **Tranche A items 3 and 6** — item 3 stops entry injection for inline sugar and item 6 teaches the compiler to read structural refs; both implement D8. Under §11 both reverse — and the sweep *shrinks* Tranche A: injection already exists in the implementation, so item 3 reduces to swapping parameter-zip routing for the fixed §4.2 table, and item 6 deletes (the compiler keeps building readers from entries only).

**R2 — Rewrite D5 as the uniform lift rule.** *(Applied; the closed half is implemented, the open half is Tranche C.)* Replacement text: *every sugar form lifts at desugar — concrete forms to closed synthetic entries, parameter-bearing forms to open synthetic entries capturing the free parameters of the enclosing declaration in declaration order; a declaration's own body never lifts (it is the declaration); materialisation creates no synthetic entries — it closes open ones, innermost-out.* This also resolves the wrinkle item 13 papers over ("nothing lifts to a closed entry" inside templates): a concrete `[order]` inside `<T> { a: T  b: [order] }` lifts **closed** at desugar — an open synthetic with no parameters would contradict D7's `instance_template ⟺ open` invariant — and D6's identity-settles-after-Pass-2 paragraph already exists to make eagerly-lifted closed entries merge correctly.

**R3 — Resolve §9 by relaxing the field-position prohibition, and let §11 be the argument.** *(Applied as D10; Tranche C.)* The inline/declaration tier distinction existed because sized forms had no inline *representation*. With D8 rejected there is no inline representation of anything: every form is an entry referenced by a bare name, so the prohibition protects nothing — it is surface conservatism whose motive §11 deleted. Relaxing it legalizes both §8 fixtures as written, legalizes the record-form grid (`<T, N> { x: [[T; 1..N]; 2..N] }`), and gives `SPEC-FEEDBACK.md` #31 its production collapse together with the honest paragraph that it *is* a language change. If the prohibition is kept instead: Tranche C stage one survives untouched (`box`'s `[T]` is inline-legal today), but tree, both grids, and the cross-channel-dedup fixture must respell through whole-body declarations — and the "third declaration writing `[pixel; 3]`" leg of the grid fixture becomes unreachable as written, since a whole-body `x => [pixel; 3]` is a *nominal* entry with a structurally equal body, not the synthetic.

**R4 — Split instance-template checking between declaration and materialisation.** *(Applied to D7; unimplemented — the form does not exist yet.)* D7 and Tranche C item 14 defer type-checking to the materialising application, which is right for `param`-bound slots and wrong for everything else: binding *keys* against the target's vocabulary, REQUIRED-without-default coverage, and the typing of *concrete* bindings are all decidable at declaration time, and deferring them means a broken, never-instantiated template ships silently (`<T> !array { elemen_type: T }` should fail at the declaration, not at the first user's application). One sentence in D7: declaration-time checks everything the parameter list does not obscure; materialisation checks substituted values (§8.2's deferred checks, as drafted).

**R5 — Promote the recursion guard to a normative rule.** *(Applied and implemented; the implementation is stricter than termination requires — see `BACKLOG.md`.)* Tranche B item 8's "termination guard" for non-regular recursion (`weird => <T> { next: weird<[T]>? }`) is correct that no section covers the case and that dedup-by-identity cannot catch it — but an implementation guard means a non-portable depth limit. The standard fix is statically checkable at declaration time: **within a template body, a recursive application (direct or mutual) MUST pass each parameter through unchanged.** This is the regular-recursion restriction (§13, prior art); it belongs beside §5.10's other v1 boundaries (no head abstraction, no parameter bounds), and it turns item 8's guard into an assertion that never fires.

**R6 — Rebuild §8 from the worked examples.** *(Applied; §8 above is the rebuild.)* The fixture section predated D7/D9: the grid fixture's entry counts are ambiguous now that synthetics come in open and closed flavours (the record-form grid closes to two open synthetics, three closed entries, and one instantiation entry); `box => <T> { a: [T] }` — Tranche D's declared stage-one target — is not a fixture at all; and the reuse assertion is absent (closing `grid<pixel, 4>` after `grid<pixel, 3>` MUST reuse both open synthetics and the template entry untouched while producing three fresh closed entries — templates are consulted, never modified, by closure). Adopt the six-entry record-grid derivation as the primary fixture, contingent on R3, with the whole-body spelling as fallback. (The degenerate form this used to raise — an `instance-template` whose bindings are all concrete — is settled in D7: an unreferenced parameter is an error, so the case cannot arise and D6 needs no rule about whether unused parameters participate in normalisation.)

**R7 — List §4.1's hardening in §6.** *(Applied.)* Structure-namespace-only `!` resolution (no type-name precedence, no local capture) is a change to Revision 32 semantics beyond this report's core proposal — under Rev 32 a local `array` declaration could capture `!array`. Someone diffing revisions will want it in the deletions summary, not discovered mid-§4.1.

**R8 — Add conformance tiering, and make it normative.** A consumer that ingests only resolved output is fully conforming with **zero** §5.10 support: the closed-entry rule guarantees no `param`, no `instance_template`, and bare names at every type position, so the wire never carries a template. State this as a conformance clause rather than an observation — it is what confines the engine's cost to authoring tools by construction, and it is the clean seam at which template support could be deferred to a later revision without any redesign.

**R9 — Synthetic naming: reserved lexeme, content-derived names.** *(a) declined; (b) outstanding.* Two independent properties, in order of force:

> **(a) is not available.** A reserved lexeme excluded from the source `type-name` production would also be
> excluded from the kernel's `type_name` atom, because they are the same lexical class — and §8 resolver
> output is a *data document* whose schema-map keys are typed `type_name`. A name the grammar cannot spell
> is a name the resolver cannot write out, so the schema could not be serialised at all. Collision safety
> stays a property of the derivation (fresh by construction, and checked), not of the lexeme.

*(a) Unspellable, not hidden.* Synthetics stay in the one schema map — closed entries reference them by bare name, and a second namespace would split resolution and validation (the "two representations" sin §11 rejects, relocated). Instead, draw internal names from a lexeme space the **source** grammar's `type-name` production excludes (a reserved leading marker), while the kernel's `type_name` atom — which types the map keys in *data* — continues to admit it. One grammar line buys: collisions with declared names impossible by grammar rather than by freshness discipline; references to synthetics unspellable from source, killing the Hyrum's-law capture in which an observed internal name becomes load-bearing API; and imports resolved for free — synthetics travel transitively (an imported type's closed body keeps resolving) but are never nameable at an import site, because import references are source. Pairs with `@synthetic` (§4.7): the annotation for tools, the lexeme for the grammar.

*(b) Content-derived, not provenance-derived.* Readable names such as `grid_x_array_array_T_N` are not merely unnecessary — after cross-channel dedup they are **false**: the entry is shared with every independently written `[pixel; 1..3]`, and a name claiming grid's field `x` as parent lies about an entry that has no owner. The honest name is derived from the entry's only true identity: closed synthetics named by a hash of the canonical body; open synthetics by a hash of the alpha-normalised body — the same normalisation D6 already requires for their identity, so the name falls out of a comparison the resolver computes anyway. This also buys **determinism**: resolver output becomes a pure function of input (stable diffs, stable golden fixtures, stable digests — §10 item 1), and name aligns with identity so dedup is literal (same form, same hash, same key). Names remain non-normative between implementations (§8.2 unchanged); *within* an implementation, naming SHOULD be a deterministic function of canonical content — which makes the §8 canonicalisation decisions (default materialisation, annotation dropping) load-bearing and to be pinned first. Truncation: truncate freely, lengthen deterministically on collision; the names never cross an implementation boundary, so this is a paragraph, not a protocol. Retire the provenance-bearing names from all worked examples.

**R10 — Sequencing: let the target schema cast the deciding vote on Tranches B/C.** Tranche A is the complete no-templates system and merges alone under both outcomes of the keep/strip question. Before committing to B, write the intended flagship schema (the REST surface: `request<T>`, `paged<T>`, the error envelope) twice — templated and hand-monomorphised. If the duplicated version is unacceptable, the demand question is answered by the first real user; if it is acceptable, the strongest keep argument was theoretical and the annex-and-reserve option (`<` reserved at type positions with a "future revision" diagnostic) is available at zero cost. Either way the decision is made on evidence while the design context is still hot.

---

## 13. Prior Art and Implementation References

The mechanisms in this report have close precedents; nearly every hard decision above has a named counterpart with decades of field experience. Keyed to the design, for the implementor to lean on — and, where a precedent failed, to be warned by.

**Hygienic macro systems — Common Lisp `gensym`, Scheme `syntax-rules` (R7RS §4.3), Racket.** The synthetic-entry naming rules *are* gensym: machine-generated names that cannot collide with or be captured by user code. The Lisp lineage's thirty-year path from `defmacro`'s capture bugs to hygienic expansion is the case law for R9(a)'s "impossible by grammar, not by discipline". Racket's binding model (Flatt, *Binding as Sets of Scopes*, POPL 2016) is the fully worked theory of machine-generated bindings coexisting with user bindings in one namespace, and Racket's **phase separation** — expansion runs strictly before evaluation and may not ask semantic questions — is the Parse → Desugar → Pass 1 → Pass 2 ordering, including the reason desugar must stay purely syntactic (§4.7).

**Quasiquotation — Lisp `` `…,x ``, Template Haskell typed quotations.** `instance_template` is a quoted constructor application; `param:` is the comma. "A body is literal except at explicitly marked holes" is quasiquote's entire design, and the materialisation cascade is splice-and-normalise. Template Haskell's typed quotes are the precedent for D7's stronger property: the quoted form is itself typed vocabulary, not raw syntax.

**Dhall.** The nearest architectural relative of the whole design: a typed, *total* configuration language whose expressions **beta-normalise to a canonical first-order form** — abstraction fully evaluated away — identified by **semantic hash** over the normal form. That is materialisation, resolved output, and structural identity as one shipped, battle-tested trio, and it validates R8's tiering (consumers of normal forms need no evaluator) and R9(b)'s content addressing. Pin the canonical form before hashing, as Dhall's semantic-integrity checks require.

**Unison.** Every definition named by the hash of its AST; human names are metadata over content-addressed identity. The direct precedent for R9(b), including the truncation-and-lengthen collision posture.

**Zig `comptime`.** Generics as memoised functions from types and values to types, evaluated at compile time, **keyed on argument identity** — D6's `source`-keyed dedup as a language's core semantics. Also the honest preview of the diagnostics future under §8.2's deferred checks: most generic errors report at the instantiation site, which is why R4 moves everything decidable to the declaration.

**ML module systems — applicative vs. generative functors.** The named axis under D6: same arguments, same result (applicative — SML/OCaml applicative functors, C++/Rust monomorphisation) versus every application fresh (generative — Ada generics, OCaml generative functors). Closed-synthetic body identity and `source`-keyed instantiation identity are both applicative choices; the literature names the trade so the spec does not re-derive it.

**C++ templates and concepts; Rust trait bounds.** Two warnings and a direction. Pre-concepts C++ checked templates only at instantiation — the industry's canonical error-quality failure, and the argument for R4's split. C++ also shipped without a regularity restriction, discovered accidental Turing-completeness (Unruh's compile-time primes, 1994), and retrofitted `-ftemplate-depth` — the non-portable guard R5 replaces with a static rule. Rust/ML-style declared bounds are the future direction if parameter-dependent bindings ever want declaration-time checking; §5.10's "no parameter bounds" boundary is where that door is.

**Equirecursive types and polymorphic recursion.** R5's rule — recursive applications pass parameters through unchanged — is the standard **regularity** condition from recursive-type theory, the same restriction under which ML limits polymorphic recursion to keep inference decidable. The condition has a name and a proof tradition; cite it rather than restating it.

**Wire-schema precedents.** *Cap'n Proto* ships real generics in a production serialisation format — the existence proof that parameterised envelopes are buildable and wanted at this layer. *CDDL* (RFC 8610 §3.10) is the minimal design — essentially textual substitution, hygiene risks accepted — showing what the feature costs when everything in D7/D9 is skipped. *ASN.1 parameterisation* (ITU-T X.683) is the cautionary tale: the corner of ASN.1 that tools implemented partially or not at all, and the interop-divergence outcome R8's tiering exists to prevent. *CUE* rejected parameterisation on principle (composition and unification over abstraction) — the considered counter-position the templates annex should cite if Tranches B/C are deferred. *GraphQL's connections convention* and *OpenAPI's `allOf` generic workarounds* are the demand evidence from the target domain: the parameterised envelope pattern, hand-reimplemented in codegen and preprocessors wherever the schema language cannot express it.

**Compiler-generated member conventions — JVM `ACC_SYNTHETIC`, `javac` `$`-names, C's reserved `__` space.** The marker-plus-lexeme pairing of R9(a): a flag for tooling to fold synthetic members out of view, a reserved lexeme space so source can never collide with or claim them. The JVM's flag is `@synthetic` (§4.7); the reserved identifier space is the `type-name` restriction.

**de Bruijn indices.** The standard normalisation for comparing binders structurally — D6's "up to consistent renaming of parameters, most simply by normalising to positional indices" — and the input to R9(b)'s open-synthetic hash.

One mechanism has no precedent to lean on, and is worth writing up as a contribution rather than a rediscovery: the open form as **ordinary self-hosted vocabulary** — `instance_template` as a plain kernel type, validated by the same record validation as everything else, in the same schema map, with no expander-privileged representation. Lisp's code-as-data is untyped; Racket and Template Haskell wrap their quotations in privileged types; none of them get "the template's open body is ordinary data of an ordinary type in the system it extends." That property is downstream of TSON's self-hosting being real, and it is the reason §4.6 ends with no carve-outs at all.
