---
title: "TSON Change Report Addendum: Open-Form Representation — Two Endpoints"
against: "TSON 2026 Revision 33 (Working Draft)"
status: "Proposed"
id: "CR-structure-templates-addendum-1"
revised: "2026-08-25 — rebased from the CR-as-drafted baseline onto Revision 33 as executed"
---

# Addendum: Open-Form Representation — Two Endpoints

**Against:** the structure-templates design as adopted into Revision 33 — specifically Part 2
§5.10's instance-template representation and its collection-slot boundary, §5.3's open-synthetic
lift rule, §8.1–§8.2's `template_argument`/`value_param` output records and open-synthetic
identity, and §12.1's `instance-template` production family. The base report
(CR-structure-templates) remains the design record; its decision and review numbers (D5–D9,
R4–R10) are used here as shorthand, with each anchored to the Revision 33 text that executed it:
D5 → §5.3 (the lift rule), D6 → §8.2 (identity), D7 → §5.10 (instance templates), D9 → §12.1
(grammar), R4 → §5.10 (the declaration-time checking split), R5 → §5.10.1 (regular recursion),
R8 → §1.3 (resolved-output consumers), R9(b) → §8.2 (content-derived naming). Part 1 remains
unaffected. This addendum follows the base report's own convention (§11): superseded reasoning
is preserved, not deleted, because without the argument against it the next reader re-proposes it.

**What changed under the rebase.** Two things, both raising the stakes. First, the base
report's Tranche B/D split and R10's demand experiment are overtaken: Revision 33 shipped
*both* mechanisms (`value_param` record templates and `instance_template` instance templates)
as normative draft text, so the question is no longer which tranche to land but whether the
landed representation is the right one. Second, feedback #53 was not parked but **declined**
(REV33-CHANGELOG #53, author disposition): Revision 33 surfaces the gap as an explicit,
diagnosed boundary — *"a parameter inside a collection-typed slot … has no open
representation, and a declaration writing one is a resolver error at the declaration; this is
a deliberate boundary of this revision"* (§5.10) — and adds a defense of uniform quotation on
body-identity grounds (§8.1's `template_argument` rationale). This addendum accepts the
diagnosis, credits the honesty of the boundary, and contests the boundary itself: it is an
artifact of the representation choice, not of the problem, and both coherent completions
remove it. It is submitted as a Revision 34 change proposal.

**Reading order:** §2 states the defect; §3 the diagnosis it reduces to; §4 and §5 the two
coherent completions — **Outcome A: complete the typed quotation** (grow the template
vocabulary until it covers what the constructors can express) and **Outcome B: held form**
(remove typed open representation and bind once, at materialisation). §8 is the
recommendation. §7 is severable and can land before the A/B decision. §11 records a third
design (externalised value routes) considered and superseded during this addendum's drafting.

---

## 1. Summary

Revision 33's `instance_template` cannot represent a parameter inside a collection-valued
slot (`tuple.elements`, `choice.variants`, `enum.members`, `record.fields` in instance
position) — no longer a silent contradiction, as in the CR draft, but a stated resolver error
and a declined feedback item (#53). This addendum finds the boundary is a symptom rather than
a scope decision: `instance_template` is a *partial* typed quotation of the constructor
vocabulary, and a quotation typed slot-by-slot is incomplete wherever the vocabulary it
quotes recurses. The driver of the mechanism was narrower than the mechanism itself — the
value-slot case (`<T> !array { min_items: T }`, a body that cannot inhabit `integer?`); type
slots were never blocked, because the kernel's `type_ref` already licenses a parameter at
every name position, at any depth, including inside collections (meta-kernel, `type_ref`
doc: "the referenced type — or, within a template body, a parameter of either kind").

The cost of the boundary is now normative: `result => <T> ( T | error )` — a parameter in
`choice.variants`, the sum-typed result envelope that is the likeliest headline use of
generic schemas — is inexpressible in Revision 33, by rule rather than by accident.

Two coherent endpoints exist, and the shipped design is the unstable midpoint between them.
**Outcome A** finishes what `instance_template` started: the quotation vocabulary grows (a
recursive channel on `template_argument`, and the shadow records that channel immediately
demands) until every constructor form has a typed open counterpart. **Outcome B** abandons
typed open representation: an open entry holds its desugared source body as an uninterpreted
tree in a `( body | template )` field group on `type_definition`, bound against constructor
vocabulary once, at materialisation.

Both outcomes resolve `<T> ( T | error )`. Both preserve everything Revision 33 got right:
the declaration-time checking split (§5.10), the regularity rule (§5.10.1), closed-side
identity and cross-channel deduplication (§8.2), knot-tying, the resolved-output conformance
tier (§1.3), content-derived naming (§8.2). They differ in what they spend and where: A
spends vocabulary to keep open entries typed and self-hosted; B spends the typed-open
property to delete most of the mechanism. The recommendation (§8) is Outcome B.

## 2. The Defect, Restated at Its Root

**AD1 — The boundary lands on the flagship, before the demand evidence existed.** The base
report's R10 made the REST surface the deciding vote on the template tranches — `request<T>`,
`paged<T>`, the error envelope — and a sum-typed result envelope, `result => <T> ( T | error )`,
is *precisely* a parameter in `choice.variants`. Revision 33 shipped both tranches without
running that experiment, and §5.10's boundary excludes the envelope by rule: the pattern most
likely to decide the demand question is the one the shipped mechanism cannot express. The
declared workaround ("nesting goes through a second named template instead") does not reach
it — there is no second template to name when the parameter *is* a variant; the sum must be
monomorphised by hand at every use.

**AD2 — The obstruction was value slots only, and the shipped defense of uniform quotation
purchases the boundary it defends.** The base report diagnosed this correctly ("the
obstruction is not the type slot… but the **value** slots") and quoted type slots anyway;
Revision 33 keeps that choice and adds its reason (§8.1, `template_argument` doc): a type
slot *could* carry a parameter through `type_ref` — the license is acknowledged — "but then a
single binding would have two spellings and body identity would depend on the choice," so
`param` is made canonical for every slot kind, and §5.10's uniformity clause ("every open
instance body uses `instance_template` … uniform use is what makes `instance_template`
present ⟺ open entry hold") completes the closed-entry rule. Three answers, in ascending
order of weight:

1. The identity concern is answerable structurally, without uniform quotation: make the body
   form a *function of the body* — an open entry carries an ordinary constructor body when
   every parameter occurrence sits at a `type_ref` position, an `instance_template` exactly
   when a value slot is parameter-bound. No entry ever has two spellings, because the
   spelling is determined, not chosen. Open-vs-closed remains decidable on the node where
   `parameters` already sits — the field §5.10 itself declares equivalent to the body shape
   ("imply each other").
2. §8.2 already normalises open-synthetic identity "up to consistent renaming of parameters,
   most simply by normalising parameters to positional indices before comparing." The same
   normalisation that erases parameter *names* erases the channel difference; the identity
   machinery needed no help from the quotation.
3. The purchase price of the uniform spelling is the collection boundary itself:
   `choice.variants` is `[type_ref]` — every element position licensed to hold a parameter by
   the reference channel — and the only rule forbidding `!choice { variants: [T error] }` is
   the uniformity clause that forces the body into a vocabulary (`template_argument`) with no
   collection case. The defense trades the flagship pattern for a property obtainable by
   construction.

**AD3 — The kernel spells "a parameter" three ways for one problem.** (1) `type_ref`'s
reference channel — free, works everywhere names go ("parameters ride the reference channel,"
meta-kernel). (2) `record_field.value_param` — the record vocabulary widened in place, "legal
only in template bodies" (§8.1). (3) `template_argument.param` — quotation of the
application (§8.1). Mechanisms 2 and 3 solve the same obstruction (a value slot needs a
parameter spelling) by different means; mechanism 3 additionally re-covers mechanism 1's
ground, which is where the collection hole comes from. Record templates unquoted, instance
templates quoted, collections excluded by rule: a midpoint, condemned by the base report's
own strongest recurring argument — "do not make every consumer walk two representations,"
used to reject both D8 and D7's first form.

## 3. The Principle, and the Axis It Defines

**Any slot that holds names can hold a parameter for free, because a parameter is a name.
Any slot that holds immediate values cannot, and needs widening, quotation, or delayed
binding.** Type slots ride `type_ref` — name-indirect, late-bound by construction. Value
slots (`min_items`, `format`, `unordered`, atom constraint fields, enum members) are
immediate. The template problem, in every design, is only ever about the immediate slots.

The designs then differ along one axis — *how the immediate slots get their parameter
spelling* — and Revision 33's `instance_template` sits between the two coherent endpoints:
quote **everything, completely** (Outcome A), or quote **nothing, and bind late** (Outcome
B). The shipped design chose "quote everything" and stopped early; `record_field.value_param`
is a fragment of Outcome A applied to one node, and the §5.10 boundary is the unquoted
remainder, now with a fence around it.

## 4. Outcome A — Complete the Typed Quotation

**Design intent.** `instance_template` was the right idea executed partially: a typed,
self-hosted quotation of constructor applications. Outcome A finishes it, so that every form
the constructor vocabulary can express has an open counterpart, and the open counterpart is
ordinary kernel vocabulary validated by ordinary record validation — preserving §5.10's shape
invariant (`instance_template` present ⟺ open) and the base report's §13 contribution in
full.

**The minimal spelling, and why it does not stay minimal.** Declined feedback #53 named it:
a recursive fourth channel on `template_argument`:

```tson
template_argument => {
  ( param: param_name | value: value | type_ref: type_ref
  | list: [template_argument] )
}
```

This covers `choice.variants` — `[type_ref]` is flat, so
`variants => { list: [ {param: T} {type_ref: error} ] }` — and it covers nothing else that
was missing. `tuple.elements` is `[tuple_element]`, a collection of *records*
(`element_type` plus `state`), so the channel's elements must be able to carry a quoted
`tuple_element`, which is a fifth channel and a shadow record:

```tson
template_tuple_element => {
  element:  template_argument
  state:    element_state ~ REQUIRED
}
```

`enum.members` is a `token_set` (a parametric member is a value inside a collection);
`record.fields`, were record templates migrated onto the same representation, is
`[record_field]` with its own value group. Each recursive position in the constructor
vocabulary demands its quoted counterpart, and the endpoint of that demand is the **shadow
family**: `template_array`, `template_map`, `template_tuple`, `template_choice`,
`template_enum` — one open counterpart per constructor, each widening its value slots to
`( value | param )` and its collections element-wise. The slope from "one more channel" to
the full family is short and one-way, and Outcome A is honest about walking it to the end: a
partially completed quotation is the Revision 33 boundary again, one layer down.

**What the completed form buys.** Every open entry is typed vocabulary: resolved fixtures
containing templates validate, diff, canonicalise, and self-describe exactly like closed
entries. Declaration-time checking becomes *validation* — binding keys, coverage,
concrete-slot typing all fall out of reading the body against its shadow type, with no
masked-walk procedure. Open-vs-closed remains decidable by body shape (§5.10's invariant,
now watertight because the shadows exist for every constructor). §12.1's `instance-template`
production and its rationale stand unchanged. `value_param` can retire *into* the system
rather than out of it: record templates move onto `template_record`, unifying the three
parameter spellings of AD3 into one — quotation everywhere.

**What it costs.** The kernel roughly doubles at the constructor layer, and the cost recurs:
every meta-layer constructor with immediate slots needs a shadow
(`<F> !float_type { format: F }` is a legitimate template, so `template_float_type` follows,
and so on for each atom vocabulary meta adds — the format's sanctioned extension point now
ships in pairs). Every consumer that walks open entries walks a second representation of
every form — the sin the base report invokes against D8 and against D7's first form, now
adopted as the price of the typed-open property. §5.3's desugar table gains a template
column (each sugar form over a parameter desugars to its shadow). And the maintenance
invariant — shadow stays congruent with constructor — is a discipline where B has a theorem.

**Costs contained.** §1.3 confines all of it to the authoring tier: closed entries reference
no shadow, so data consumers and resolved-output consumers meet none of the new vocabulary.
The shadow family is mechanical enough to generate rather than hand-write, and a conformance
test (shadow ≅ constructor with widened slots) makes the congruence checkable.

## 5. Outcome B — Held Form

**Design.** An open entry carries no constructor body at all. It holds its desugared source
body as an uninterpreted tree — quoted code as data — bound against constructor vocabulary
exactly once, at materialisation, the only moment binding is decidable. Two kernel changes:

```tson
@doc:"""
  Held form primitive. Instance of the unit atom constructor. The
  canonical desugared source form of an open entry's body — held,
  not bound: its parsing contract is any well-formed annotated value,
  read into a tree and never resolved against constructor vocabulary
  until materialisation. Parameter tokens within it are read against
  the enclosing entry's `parameters` list.
  """
form => !unit {}

type_definition => {
  source:       type_ref?
  kind:         type_kind
  parameters:   [param_name]?
  constructor:  boolean ~ false
  supertypes:   [type_name]?
  subtypes:     [type_name]?
  disjoint:     boolean?
  ( body: top | template: form )
}
```

The REQUIRED field group is the load-bearing move — the same discrimination-by-shape trick
`type_argument` and `template_argument` already use. It recovers what §5.10's uniformity rule
bought, at none of its cost: open-vs-closed is decided by which member is present,
structurally, on the node where `parameters` also lives, under a three-way invariant
(`parameters` non-empty ⟺ `template` present ⟺ `body` absent) checkable in one place.
Holding the tree *outside* `body` keeps `top` honest — every body is a constructor body,
construction transfers kind, no non-value intermediate enters the subtype lattice — and the
kernel keeps its no-carve-outs shape: the reading rule for the held tree lives where the
kernel already puts such contracts, in a unit atom's prose — exactly as `value` and `token`
do for their own "dependencies the schema language does not express."

**Why the tree is the better tree to walk.** Substitution over the held form is *one rule* —
rewrite tokens that resolve into `parameters` — uniform across type slots, value slots,
collection elements, and nesting depth. Every per-channel mechanism, in the shipped design
and in Outcome A, is compensation for binding too early: once a form is bound into typed
vocabulary, each slot kind needs its own spelling of a hole. Held as a tree, a hole is a
free token. Bind once.

**The deletion cascade** (Revision 33 targets named per item):

- `instance_template` and `template_argument` delete from the meta-kernel, and with them the
  resolved fixture's `map_field_name_template_argument_xxhash` synthetic.
- **§12.1's production family deletes, and the base report's §11-rejected first form becomes
  correct.** The rejection argued the two forms "resolve against different vocabulary" and an
  ABNF should show it; under B the parameterised payload resolves against *no* vocabulary at
  parse time — it is held — so `[type-params]` on the ordinary instance alternative is again
  the right grammar: the `instance-template` / `template-def` / `template-bind` productions
  delete, and the instance form joins §12.1's `[type-params]`-prefixed alternatives. The
  payload restrictions vanish (the payload is arbitrary held form), and the atom-refinement
  hazard inverts: `<N> !integer ^ { min: N }` stops being a form the grammar must forbid and
  becomes a candidate feature, since a held refinement materialises like anything else.
- **Open synthetics delete as a category.** They existed because `instance_template`
  bindings could not nest, forcing inner parameter-bearing forms to lift out (§5.3). A tree
  nests natively; a template holds its whole body. **§5.3's lift rule restates:** concrete
  forms lift closed at desugar; a template declaration holds one tree and lifts nothing;
  materialisation substitutes, then desugars-and-lifts the now-concrete forms closed,
  innermost-out. §8.2 loses its trickiest identity clause (open-synthetic equality up to
  consistent renaming); parameter alpha-normalisation survives only inside §8.2's
  content-derived naming, if template identity for tooling is wanted. §8.1's
  "Reading parameter references" paragraph simplifies to the shadowing rule alone.
- **`value_param` retires.** Record templates hold trees too; `record_field`'s
  `( value | value_param )?` group returns to plain `value?` (§8.1, meta-kernel). The base
  report's Tranche B dispensation — "no new vocabulary because `record_field` already admits
  parameters" — generalises to every constructor for the same reason, and the historical B/D
  tranche split dissolves into one mechanism.
- **REV33-CHANGELOG #53's declined boundary dissolves rather than being re-litigated**:
  `<T> ( T | error )`, `<T> [T, text]`, nested sized forms, parametric enum members, and
  parameterised atom vocabularies are all just trees; §5.10's boundary sentence and its
  "second named template" workaround delete.

**Fixture recount** (§5.10/§8.2's own worked examples). `box => <T> { a: [T] }`: one closed
synthetic, one instantiation — the `array_t` open synthetic never exists. `grid`: two closed
synthetics (`c1`, `c2`), one instantiation (`c3`) — from either spelling, since the
whole-body form's extra reference entry existed only to keep an open synthetic's internal
name out of identity, and there are no open synthetics. Reuse becomes self-evident
(substitution reads a tree it never writes); the tree fixture's knot ties as before, through
the closed synthetic minted at materialisation (§8.2's recursive-reference rule, verbatim).

**Costs, honestly.** Open bodies are opaque to generic schema tooling — a `form` is a blob
until the reading rule is applied — mitigated by declaration-time checking having already
run, and confined by §1.3 to the resolver/authoring tier, the one tier that holds an AST
regardless. The held form's canonical spelling must be pinned (recommended: the desugared
canonical form, sugar expanded per §5.3's table) because §8.2's naming determinism and
fixture diffing now hash the tree. And the base report's §13 novelty claim is deliberately
abandoned — §12 below.

**Checking.** §5.10's split survives procedurally: the declaration-time walk — binding keys
against the target's vocabulary, REQUIRED-without-default coverage, typing of concrete
bindings, the unreferenced-parameter error, §5.10.1's regularity check — runs over the tree
with parameter positions masked; materialisation checks what substitution supplies (§8.2's
deferred value-level checks, unchanged). This is the same engine A runs declaratively and
Revision 33 runs partially; **the engine is identical under every option — only the
serialised artifact differs.**

## 6. Invariant Under Both Outcomes

Preserved verbatim, because none of it ever depended on which open representation exists —
only on open forms being recorded and closed innermost-out: the declaration-time /
materialisation checking split (§5.10); the regularity rule (§5.10.1, and its role in
keeping §8.2's identity decidable); closed-entry identity, constructor-keyed synthetics, and
cross-channel deduplication (§8.2); instantiation entries keyed on the flattened application
in `source` (§8.2); §8.3 aliasing; knot-tying (§8.2); the conformance tiering (§1.3) — under
both outcomes strengthened, since closed entries carry no shadow vocabulary and no
`template`, so a resolved-output consumer needs zero template machinery; and content-derived
naming (§8.2).

Worked under either outcome, `something => <T> ( T | error )` closed via `something<text>`
yields one instantiation entry — `source: { name: something  arguments: [{name: text}] }`,
body `!choice { variants: [text error] }`, `disjoint` computed by the ordinary
discrimination-class machinery (§5.4), which consumes constructor bodies and under both
outcomes has nothing to look through.

## 7. Severable Interim Fix

If the A/B decision is deferred, one scoped edit resolves the flagship case against Revision
33 as shipped: **restate §5.10's uniformity rule and collection boundary — an open entry
carries an ordinary constructor body whenever every parameter occurrence sits at a
`type_ref` position; `instance_template` is required exactly where a value slot is
parameter-bound; the collection-slot resolver error narrows to parameters at *value*
positions inside collections (enum members and their kin).** Choice, tuple, `[T]`, and
`{K => V}` templates fall out immediately (AD2's licensed channel), and the body-identity
concern is answered by determinism: the body form is a function of the body, so no entry has
two spellings, and open-entry identity compares constructor bodies with parameters
normalised positionally — the discipline §8.2 already applies. The closed-entry rule's
integrity check gains one clause (an ordinary body in an open entry must contain a parameter
reference). Under A this is subsumed once the shadows exist; under B it is subsumed
entirely. It is compatible with both, and with neither.

## 8. Recommendation and Decision Procedure

**Recommendation: Outcome B**, on proportionality and on the base report's own principles.
The immediate slots the whole mechanism exists for are a handful of scalars; A prices them
at a shadow per constructor, recurring with every future meta layer, while B prices them at
one unit atom and one field group. A adopts the two-representations cost the report twice
rejected; B deletes more of the shipped design than it adds (§5.10's instance-template
machinery, §12.1's production family, open synthetics, `value_param`, the payload
restrictions, the collection boundary). A's congruence between shadow and constructor is a
maintained discipline; B's single-source-of-truth is structural.

**The deciding question is the fixture story, and it is a fair fight.** A is the only
outcome under which open entries are typed, validating, self-hosted vocabulary — fixtures
containing templates check like everything else, and the base report's §13 contribution
stands complete rather than partial. If that property is a requirement of the *proof*
capability, not merely of debugging, choose A and generate the shadows rather than
hand-writing them. If — the working position — resolved output's normative surface is its
closed entries (post-change the kernel and meta fixtures contain zero open entries, and
§1.3 keeps every conforming consumer from meeting one), the typed-open property is
load-bearing for nothing, and B's opaque `template` payload, comparing canonically as a
tree, is sufficient. In the R10 spirit: write the flagship schema's resolved fixture both
ways before deciding; the diff is the evidence.

## 9. Changes to Revision 33 (and to the Base Report's Record)

Under either outcome, targeting Revision 34:

- **Part 2 §5.10**: the "Instance templates" and "Open bodies" paragraphs respell (A:
  shadow-typed open bodies; B: held trees); the collection-slot boundary sentence deletes
  (A: replaced by the shadow family's coverage; B: replaced by the held form's); the
  closed-entry rule restates — an entry with empty `parameters` contains no parameter
  reference at any depth and (A) no shadow-typed body, (B) carries `body`, not `template`.
- **Part 2 §5.3**: the lift rule's open case (A) extends per shadow, (B) deletes — template
  declarations hold one tree; the desugar table (A) gains a template column, (B) is
  unchanged and runs at materialisation.
- **Part 2 §8.1–§8.2**: `template_argument`/`value_param` output records (A) extend with the
  collection forms and shadow family, (B) delete; §8.2's open-synthetic identity bullet (A)
  extends element-wise, (B) deletes; the worked `array_t`/`grid` passages respell per §5's
  recount.
- **Part 2 §12.1**: (A) `template-arg` extends with the collection forms; (B) the
  `instance-template`/`template-def`/`template-bind` productions delete and `[type-params]`
  is reinstated on the instance alternative.
- **Meta-kernel artifact**: (A) the shadow family lands beside the constructors, generated,
  with the congruence conformance test; (B) `instance_template` and `template_argument`
  delete, `record_field` returns to `value?`, `form` and the `( body | template )` group
  land on `type_definition`; the resolved fixtures update accordingly (B removes the
  `map_field_name_template_argument_xxhash` synthetic).
- **REV33-CHANGELOG #53**: gains this addendum as its resolution (A) or its dissolution (B),
  superseding the declined disposition.
- **Base report record**: D7 and D9 are superseded to its §11 with this addendum as the
  argument (A supersedes their *scope*, retaining their intent; B supersedes both outright);
  the fixture counts in its §8 update per §5.

## 10. Prior Art, Updated

The two outcomes are the two shipped traditions, now correctly labelled. **Outcome A is the
Template Haskell position**: a complete typed quotation AST mirroring the object vocabulary —
`Exp`/`Pat`/`Dec` for every syntactic category, maintained congruent with the language it
quotes, with exactly A's cost profile (the quotation grammar grows whenever the object
grammar does). Its presence in the base report's §13 as precedent for D7 was accurate; what
the shipped design misses is that the precedent is a *family* of types, and stopping at one
node is where the hole came from. **Outcome B is the Lisp position, arrived at by
elimination**: the template body is code held as data, parameters are free symbols,
materialisation is substitute-then-eval, where eval is the constructor reader run at the one
moment its types can hold the result. Zig `comptime` remains B's closest shipped analogue
(generics held until instantiation, memoised on argument identity), with the R4 caveat
intact: Zig reports most generic errors at the instantiation site, which is why B's
declaration-time masked walk is normative, not optional. CDDL marks the line B must not
cross — held textual substitution with no declaration-time checking at all: hold the *tree*,
check the *declaration*. The empirical finding of this design cycle, either way: **a
quotation typed slot-by-slot is a second grammar, and it will be incomplete wherever the
first grammar recurses** — so it must be finished (A) or not begun (B); the midpoint is the
defect, and Revision 33's fence around the midpoint changes its honesty, not its shape.

## 11. Considered and Superseded Within This Addendum

**Externalised value routes.** *Proposed* during this addendum's drafting, as a third point
between the endpoints: open entries carry ordinary constructor bodies (parameters at name
positions riding `type_ref`, per AD2), and a parameter-bound value slot is recorded as
field-absent-from-body plus an entry-level side map —
`value_routes: {field_name => param_name}?` on `type_definition` — the
`( value | value_param )` idiom of `record_field` hoisted to the entry level, with
`field_group`-beside-`fields` as the structural precedent. It resolves #53's name-position
cases for free and the value-slot cases with one map, deletes `instance_template` and
§12.1's production, and keeps open bodies typed.

*Superseded by Outcome B*, which dominates it once the serialisation constraint is
recognised as policy rather than necessity. Routes' typed open bodies still require a
masking rule (a routed REQUIRED slot is absent from a body that must nonetheless validate),
so the typed-open property arrives compromised — prose where A has shapes and B has honesty
about having neither. Routes retains open synthetics and their identity clause (its bodies
are flat, so nesting still lifts), where B deletes the category. And the flat side map is
strictly a special case of B's tree: a route is a free token at a value position, which the
held form represents with no additional field. Routes was the right repair to the shipped
design *in place*, and the design a reader repairing §5.10 without leaving it will produce
first; the argument that it is a waypoint rather than a destination is the useful part.

## 12. Withdrawn from the Base Report's §13

The claimed contribution — "the open form as **ordinary self-hosted vocabulary**… with no
expander-privileged representation" — is withdrawn under Outcome B and stands, completed,
under Outcome A. Recorded per the base report's own practice, because the reasoning is the
useful part: under B the property is real, novel, and *load-bearing for nothing* — no
conformance tier consumed it (§1.3), no derived index read through it (discrimination
classes and subtyping consume constructor bodies, §5.4, §8.1), and its carrier type is where
the expressiveness gap lived. Under A the claim should be restated more carefully than the
original draft made it: the novelty is not that a typed quotation exists (Template
Haskell's does) but that it is non-privileged — validated by the same record validation,
resident in the same schema map — and that claim is only earned when the quotation is
*complete*. Either way the epitaph is the design lesson of this addendum: self-hosting made
it possible to give the quotation a type; proportionality decides whether one should; and a
quotation half-typed is worse than either answer.
