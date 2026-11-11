---
title: "TSON 2026 Revision 40 — Change Log"
against: "TSON 2026 Revision 39 (Working Draft)"
status: "Proposed, not adjudicated. The six normative artifacts and five resolved fixtures are at `/2026/40/` identities, each pin verified over the shipped bytes and listed in Part 2 §13.2 (§6). Parts 1, 2 and 3 are revised against them (§3.1, §3.2, §3.4); the Developer Guide is not revised and keeps its `/2026/39/` reference. A verification pass over the examples and the build is recorded in §7."
inputs:
  - "SPEC-FEEDBACK.md (entries open against Revision 39; #3 and #5 are the proposals this revision carries)"
  - "Revised companion artifacts at /2026/40/ identities: meta.tn (the kernel), numeric.tn, temporal.tn, net.tn, core.tn, policy.tn and five resolved fixtures (implementing #3)"
  - "tson-part3-json.md, edited directly as the proposal's steps landed (carrying the Part 3 half of #3)"
---

# TSON 2026 Revision 40 — Change Log

This document records the changes proposed for Revision 40 of the TSON specification
series. Its one input of substance is the implementation's spec-feedback register entry #3,
*templates as open records*, whose six steps are built and merged on the Revision 40 proposal
branch of the first implementation and of the conformance corpus. Its second is entry #5,
*inline types*: a type slot holds a name or a type written in place, and no entry is lifted
or minted. The register's entries #1 and #2 are carried unchanged (§2). SPEC-FEEDBACK.md remains a record against Revision 39 and
is not modified by this revision; each of its entries receives a disposition here.

Provenance markers used in the disposition table:

- **[settled]** — the resolution is stated in the register and implemented in the revised
  companion artifacts, the corpus and the first implementation.
- **[open]** — a genuine design decision deliberately left open. Collected in §5.
- **[editorial]** — a sentence the revision editor had to write that no register entry
  dictates, or a reading the editor took where the sources disagree or admit more than one.
  Each is the spec author's to confirm; collected in §5 beside the open questions.

**This revision does not change the lexer**, and the schema grammar's productions are
unchanged: `type-params` still parses before any body, and the resolver admits it before a
record body alone. It narrows the **data grammar at one position**: a reference's argument
is a reference, so a number or a quoted token in a data document's argument list is a parse
error ([TSON-DATA] §7.4).

What it changes is **templates, and with them the kernel**. Revision 39 gave a template two
representations and a parameter two kinds: a record template was its own constructor and a
family base, every other template was held as text, and a parameter's type and bound were
derived by a fixed point across templates. Revision 40 replaces all of it with three rules
(#3):

1. **A template is an open record.** `meta.record` carries `parameters?: [meta.param;
   1..]`, each a type standing at a field's type or as an argument of an application there
   or in a parent, and nowhere else. A reference names an open record only as the head of
   an application.
2. **`C<args>` over a constructor is construction sugar**: `!C { f₁: a₁ … }` over its
   required fields typed by a reference naming types, in declaration order, as `[T]` and
   `{K => V}` already were.
3. **At a reference slot of a schema document `H<args>` is a reference**, substituting
   where `H` is an open record and constructing where it is a constructor. In a data
   document a reference is a name.

What follows from them: no value parameters, no derived or inherited bounds, no template a
position names, no template over a body that is not a record, no alias template, no open
synthetic entry, and no `meta.template` or `meta.record_template`. Every bundled pin
changes; the artifact set keeps its shape.

---

## 1. Baseline: the revised artifacts

Every step of #3 arrived implemented in the companion artifacts, and Revision 40 adopts them
as its baseline. Summarising the normative effect:

- **#3, step 1 — value parameters out.** A parameter is a type; an argument is a reference
  in both grammars; a parameter at a facet, a field's default or fixed value (a pin on a
  reference-typed field included), an enum member, a size bound, a field name or a modifier
  is a resolver error at the declaration, naming the slot. A value that differs per
  application is written where the application is declared, `created => response<order> &
  { status?: = 201 }`. (Part 1 §7.4; Part 2 §5.2, §5.3, §5.7, §5.10, §8.2, §12.1; meta.)
- **#3, step 2 — a bound is written, never inherited.** `meta.param.bound` is what the
  declaration writes; a parameter passed to a bounded parameter is written with a bound
  that IS-A it, wherever it is the argument; a parameter passed to a constructor's slot
  needs none. Nothing about a parameter is derived. (Part 2 §5.10, §8.1; meta.)
- **#3, step 3 — construction sugar, and no alias template.** `meta.array<text>` is the
  entry `[text]` is; a field with a default is no argument slot; a use-site construction
  names its content-keyed entry; an argument is not followed through an alias. A parameter
  list before a bare reference is refused. (Part 2 §3.3.1, §5.3, §5.6, §8.2, §8.3.)
- **#3, step 4 — no template is a type.** No position, reference, operand or `subtypes`
  index names a template bare; an application's IS-A edges are its template's written
  parents'; `abstract` and the selectors on a template are each application's; a family is
  an abstract base, a template composing it, and members composing an application and
  pinning the selectors. (Part 2 §1.3, §4.2, §5.2, §5.8, §5.10, §8.1.)
- **#3, step 5 — `parameters` on `record`, and the kernel.** `meta.record` carries
  `parameters?: [meta.param; 1..]`; `meta.param => { name: meta.param_name  bound?:
  meta.type_ref }`. `meta.template`, `meta.template_param`, `meta.record_template`,
  `meta.record_template_field`, `meta.record_template_field_map`, `meta.tuple1` and
  `meta.voidable_tuple1` are gone; `meta.ref => meta.reference_type ^ { allow_sibling?: =
  false }` is a refined constructor and the root's `set => meta.set_type` an alias of the
  constructor, so `meta.ref<X>` and `set<text>` are constructions. A template's body is a
  record body, and inside an open record a form over a parameter is a reference
  (`[T]` is `meta.array<T>`) or a resolver error; there is no open synthetic entry. The
  kernel measures 81 entries (88 in Revision 39), one wait cycle of 25 holding
  `meta.record`, a closure of 69 with `meta.param` and `meta.param_name` in it, and six
  synthetic entries. (Part 2 §1.3, §4.1, §4.2, §5.3, §5.10, §8.1, §9; meta, fixtures.)
- **#3, step 6 — no application in a data document.** A value at a reference-typed slot
  of a data document is a name; one carrying an argument list is a validation error,
  whether or not the schema's closure closes it, in both encodings, its message naming the
  remedy, the entry the schema declares for the application. (Part 1 §2.3, §3.2, §4.1;
  Part 2 §3.3.4, §4.2, §7.2, §7.7; Part 3 §1.6, §5, §5.6.)

The artifacts also carry housekeeping the register did not raise, adopted on the same
footing:

- **Every identity moves to `/2026/40/`**, and each header comment reads "2026 Revision 40
  draft".
- **`policy.limits.max_materialisation_depth`'s doc** counts the nested applications closed
  for one application, a resource cap, regular recursion closing on its first repeat; Part 2
  §11.5's row follows.

---

## 2. Disposition summary

| # | Entry (abridged) | Disposition |
|---|---|---|
| 1 | [TSON-DATA] §2.1's worked example says untagged tokens resolve by §4's base rules under an imported schema | **Carried.** Not among this revision's changes; the entry stays open against Revision 40 as written. [open] |
| 2 | Whether a quoted token at a reference-typed position is a reference | **Carried**, narrowed by #3: an argument is a reference in both grammars, so a quoted argument is refused in a schema document and in data alike; the field-position half (`t: "int32"` at a `meta.type_ref` field in data) stays open as written. [open] |
| 5 | Inline types: a type slot holds a name or a type written in place | **Accept** — the kernel's `meta.type_inline`, `meta.type_expr` and `meta.field_type_expr` and the hand-written resolved fixtures are the baseline; Part 2 §1.3, §1.6, §2.2.3, §3.3.3, §3.4.1, §4.1, §4.2, §4.3, §5.2, §5.3, §5.4, §5.6, §5.7, §5.8, §5.10, §5.10.1, §5.12, §6, §7.2, §8.1, §8.2, §8.3, §9, §10.1, §11.5, §12.1, §12.2 and §13.2 follow (§3.2, items 34–56), and Part 3 §3.3 (§3.4). Four readings the spec author ruled on (§5). [settled — implemented] |
| 3 | Templates as open records | **Accept — the baseline above**, every step as the register states what runs. Part 2 §5.10 is rewritten around the three rules; §1.3, §3.3, §3.4.1, §4.1, §4.2, §4.3, §5.2, §5.3, §5.6, §5.7, §5.8, §5.10.1, §6, §7.2, §7.4, §8.1, §8.2, §8.3, §9, §11.5 and §12 follow (§3.2). §1.6's example drops its value parameter. **[editorial]** at five points where the sources disagree or the text needed a reading (§5). [settled — implemented] |

Counts: **2 accepted** (#3, in six steps, and #5), **0 declined**, **2 carried** (#1, #2). Revision
39's open remainders on templates close with #3: #13's (whether a value argument narrows
below `token`) by the removal of value arguments, #19's (a shared parent for `record` and
`record_template`) by the removal of `record_template`, and #21's (a sibling in a template
field typed by a parameter, `kind: meta.type_ref = T`) by the refusal of a parameter at a
field's value — the field is written `v?: T`. #20's head edge is reversed: no application is
IS-A its template. Revision 39's other open remainders stand as that change log records them.
Open questions are recorded here and are NOT reflected as open text in the specification.

---

## 3. Accepted changes by target document

### 3.1 Part 1 — Text Data Format

1. **Header** — Revision 40.
2. **§1.3** — the lexer-freeze paragraph records Revision 40: the lexer is unchanged, and
   the data grammar narrows a reference's argument to a reference (§7.4).
3. **§2.2.1** — the example identities at `/2026/40/`.
4. **§2.3** — a reference carrying an argument list is still one core value, kept whole by
   a schemaless read; under a schema a reference in a data document is a name, and one
   carrying arguments is a validation error ([TSON-SCHEMA] §4.2).
5. **§3.2** — an application parses where a value stands, a schemaless read keeps it, and
   under a schema it is a validation error; a generic type reaches a tag or a type-naming
   position through the name a declaration gives its application (`names => set<text>`).
6. **§4.1** — a schemaless reference's arguments are qualified names or nested
   applications, not "a reference or a token".
7. **§7.4** — `type-arg = application / qualified-name-token`; the value-argument comment
   goes, and a number or a quoted token among the arguments is a parse error.
8. **§8.1** — the parse-error list gains an argument that is no reference.
9. **§9.1** — "template materialisation depth" reads "materialisation depth".
10. **§10.2** — `TSON-SCHEMA` and `TSON-JSON` at `/2026/40/`; `TSON-GUIDE` stays at
    `/2026/39/`, the Developer Guide not being revised.

### 3.2 Part 2 — Type System and Schema

1. **Header** — Revision 40, with a one-paragraph summary of this revision.
2. **§1.3** — a resolved-output consumer meets an open record as a `!meta.record` carrying
   `parameters`, skips it when building readers, and never meets one at a closed position;
   the record-template family-base exception and the `!meta.template` paragraph go.
3. **§1.4** — a reference may be, inside an open record, one of its parameters;
   `template` leaves the list of abbreviated constructor names.
4. **§1.6** — `flagged => <T> { entry: T  priority?: priority }`, applied as
   `flagged<status>`; the example imports `/2026/40/m/core.tn`.
5. **§2.1** — the example's import at `/2026/40/`.
6. **§3, §3.3, §3.3.2** — "a parameter's bound"; the lookup that precedes the namespace is
   an open record's parameter list.
7. **§3.3.1** — `meta.template` and the record-template vocabulary leave the constructor
   list, `meta.param` joins the records that are no constructor; an alias of a constructor
   is applied by `!` and by an application alike (`set<text>`); the partial-application
   desugar target and the migration diagnostic for a parameterless head go.
8. **§3.3.4** — a reference a data document writes is a name, an application there a
   validation error.
9. **§3.4.1** — only a form naming no parameter lifts; Pass 2 checks every application's
   arguments against bounds and slots; a template's body leaves rule 3's held values.
10. **§4.1** — one constructor composes `meta.top` directly; `template` leaves the kernel
    listing; an open record is a record, kept from every position by the reference rule; a
    template over an ordinary type goes; an open record is PRODUCT-kinded.
11. **§4.2** — a constructor declares no parameters; `meta.ref` is shown as the refined
    constructor; the application bullet states substitution and construction, and that in
    a data document a reference is a name; `meta.param.bound` replaces
    `meta.template_param`; the record-template slots go.
12. **§4.3** — `meta.array<text>` is the finished-instance example; an open record is
    instantiated only through an application.
13. **§5.2** — value conformance loses its routed-parameter half; the record-template field
    slot, a template as a sibling's pin and an application at a dependent field's reading go;
    an open record's field names are judged at its declaration; the family-base sentence
    becomes "a selector on an open record is each application's".
14. **§5.3** — a size bound is decimal-natural, a parameter there a resolver error; bound
    coherence is checked at load only; `set<T>` is construction sugar over the root alias
    `set`; one-position tuples are declared, `meta.tuple1` gone; **Arguments** rewritten
    (every argument a type, a literal refused); **An application of a constructor
    constructs it** added; the sugar table's `set<T>` and `C<args>` rows become the
    construction and substitution rows; the lift rule loses its open half.
15. **§5.5** — the open `!meta.alias` goes; the value-argument identity leaves the list of
    value comparisons.
16. **§5.6** — the positional, braced and applied spellings of a one-slot constructor are
    one entry; an application is a schema's, a validation error in data; the end state has
    one open body, an open record; a declaration naming an application of an open record is
    the entry, one naming a construction the construction.
17. **§5.7** — "Open modifiers" is replaced by one sentence, **Values in a template**; an
    applied open record is a refinement source, a bare one refused.
18. **§5.8** — an operand may be an application, never a parameter or an applied
    constructor; an application at an operand reaches its template's written parents and
    never its head; `ok_of => ok<text>` IS-A `ok` goes.
19. **§5.9** — a parameterised subtraction declares an open record.
20. **§5.10** — rewritten (113 lines, 41 KB → 43 lines, 10 KB): an open record; where a
    parameter stands; the body is a record body; forms over a parameter; application; a
    bound is written; no template is a type a position names, a family declared with
    records; scoping; what a parameter does not carry; output.
21. **§5.10.1** — a recursive reference is a held application; a self-reaching expansion
    keeps its closed record under an application-derived name, carrying
    `@meta.application`, in place of a `!meta.alias` to a synthetic; the example is a record.
22. **§5.12** — the example's import at `/2026/40/`; an open record is no referent; an
    application in a value body names a declaration of it.
23. **§6** — `@meta.application` is carried by a declared application and a self-reaching
    instantiation entry, and by no construction.
24. **§7.2** — an application in data is a validation error; a tag naming an open record is
    a validation error.
25. **§7.4, §7.5** — a template's held body, a name type written as an application and the
    coherence rules' re-run at materialisation go; an application at a reference over a
    constructor in data is a validation error; `set<T>` is applied through the root alias.
26. **§8.1** — **Open records** replaces **Open entries**; the kind derivation loses
    TEMPLATE; `!meta.alias`'s target is a bare name; a record field's `type` may name a
    parameter, no label saying which; a parameter appears only inside an open record;
    `@meta.application` is a substitution's; `subtypes` never names a head; a resolved-form
    read checks an open record's parameters as stated and re-resolves no held text; the
    body-patterns table loses the instance-template and record-template rows and gains the
    construction and open-record rows.
27. **§8.2** — an application nothing declares is its expansion, a self-reaching one a
    closed record; following alias chains is a substitution's rule, a construction's
    arguments compared as written; the value-argument and open-synthetic identity bullets
    go; the merge pass is restated for a held `meta.array<box<T>>`; the entry-shape example
    is `text_pair => pair<text, text>`.
28. **§8.3** — an alias body is a bare name; an application is the instantiation or the
    construction in place; the alias-template bullet goes; no `!meta.alias` carries
    `@meta.application`.
29. **§9** — the kernel row lists `meta.param` and drops the seven removed entries.
30. **§11.5** — the materialisation-depth row counts nested applications.
31. **§12.1** — `type-arg = type-expr`, a number or a quoted token being no argument in
    either grammar; `value-literal` goes; `size-bound` is decimal-natural; the `type-param`
    comment reads "the written type is the parameter's bound"; `type-def` and `instance`
    note that the resolver admits a parameter list before a record body alone; the
    `type-params` note loses the family-base exception. **§12.2** — the dispatch comments
    follow.
32. **§10.3** — the canonical-identity examples at `/2026/40/`.
33. **§13.2** — `TSON-DATA` and `TSON-JSON` at `/2026/40/`, `TSON-GUIDE` left at
    `/2026/39/`; the six artifact rows carry the Revision 40 pins (§6).

Items 34–56 carry #5, inline types. Each names the rule before and after.

34. **Header** — the summary gains a sentence: a type slot holds a name or a type written in
    place, nothing is lifted or minted, and `@meta.synthetic` is gone.
35. **§1.3** — a resolved-output consumer builds the reader of a type written in place from
    the construction where it stands; before, every slot held a name it looked up.
36. **§1.6** — `flagged<status>` closed "to its expansion … once per distinct application";
    it closes in place, a record with no name, and `tags`' resolved form shows the wrapper.
37. **§2.2.3** — "a synthetic entry's [identity] is its form" and "one synthetic form minted
    by two documents is one entry of a closure holding both" go: a type written in place is
    no entry and takes no part in collisions.
38. **§3.3.3** — `meta.synthetic` leaves the kernel's annotation list.
39. **§3.4.1** — Desugar lifted every nested form naming no parameter to a closed synthetic
    declaration entering Pass 1; it rewrites each form in place and lifts nothing. Pass 1's
    "synthetic declarations included" and step 4's freshness and import-re-derivation
    parenthesis go; rule 7 reads "closing an application".
40. **§4.1** — a value entry's payload "lifts and mints nothing" becomes a reference-typed
    slot there holding a name; a nested map's members "lift, mint" becomes "resolve", and
    "synthetics mint at the root" goes; the container constructors' type slots hold a name
    or a type written in place, not `meta.type_ref`; an open record's application is
    PRODUCT-kinded, not "its expansion's kind".
41. **§4.2** — new paragraph **A type slot holds a name or a type written in place**: the
    kernel's `type_inline`, `type_expr` and `field_type_expr`, the wrapper untagged and the
    construction tagged by its constructor (a refined one's own tag), what `of` holds, and a
    type written in place as no entry: no name, no chain, IS-A nothing, named by no tag, a
    member of no kind. The bullet "every slot that names the type of a value is typed
    `meta.type_ref` and, in a closed entry, holds a bare name" becomes the five slots typed by
    the choice, every other type-naming slot `meta.type_ref`, and where only a name may stand
    an application closes nowhere: one at a map's key, a reference's `of`, a bound or an
    alias's target is refused. The application bullet "every application denotes an entry: a
    closed one lifts to the entry it denotes" becomes "a closed application at a type slot is
    a type written in place"; an argument of an open record's application is a name or a
    parameter.
42. **§4.3** — an open record's application is cited to §5.10.
43. **§5.2** — a value modifier is admitted on an atom or an enum, a reference written in
    place included (`handler?: meta.ref<method> ~ …` stands), and refused on a container,
    tuple or choice whether named or written in place (before: named types only). The
    sibling-reference position rule names `meta.field_type_expr`, and "before any lift" goes.
    A dependent field's sibling may be typed `meta.type_expr` or `meta.field_type_expr`
    (before: a reference only), and the field is read against a type its sibling holds
    written in place as against a named one. "A minted record is a member of nothing" becomes **a record written
    in place is a member of nothing**, the `vip => customer & box<int32>` sentence on a
    synthetic gone. **Inline refinements and bare records are prohibited** becomes **What may
    be written in place**: a container, tuple, choice or reference, or a refining
    constructor's construction; a record, enum or other atom declared; the one record in
    place an application's; the line the resolver's, `of` being typed `meta.type`.
    Resolution: `type` is a `meta.field_type_expr` value.
44. **§5.3** — the intro's "everything else lifts to a synthetic entry" becomes a
    construction standing in place; `[T; N]` and `[T; N..N]` are one type, not "the same
    entry". The map sugar's key admitted "a qualified name optionally carrying type
    arguments"; it is a name, an application there refused and declared. **Arguments**
    admitted any type expression, sugar nesting (`box<[integer]>`); an argument of an open
    record's application is a name or a parameter, and a sugar form, an applied constructor
    or an application of another open record there is refused and declared first, a form
    over a parameter at a constructor's application inside an open record excepted. A
    constructor's argument slots include fields typed `meta.type_expr`. "A use-site
    construction names its content-keyed entry" becomes "is written in place". **Nested forms
    and synthetic entries** and **A use-site application never resolves in place** are
    replaced by **Nested forms are written in place**.
45. **§5.4** — an inline choice was "a *synthetic entry* … referenced by name"; it is a type
    written in place carrying `disjoint`. A variant is a `meta.type_expr`. New rule: a choice
    that is not disjoint names every variant, `( [text] | [int32] )` being a resolver error, a
    tag naming a variant by its name; a disjoint choice may hold variants written in place.
46. **§5.6** — the positional form keeps a bare token at a field typed by the type-slot
    choice as at an atom-typed one (before: atom-typed only, which `record_field.type` no
    longer is); the applied spellings are "one construction", not "one entry"; a value
    body's application is refused as no name, not for lacking an entry; the end state's type
    slot holds a bare token or a `{ of: … }` record, "after lifting" gone; two declarations
    of one construction are two entries, and neither is that construction written in place.
47. **§5.7** — a type written in place narrows only to an equal one, and a name never to a
    construction or the reverse (`tags: text_list` over `text_list => [text]` is not restated
    as `tags: [text]`); a refinement source's application is absorbed as an operand's, IS-A the
    template's written parents and a declared entry for it.
48. **§5.8** — an operand's application "mints no entry" becomes "is no entry"; an applied
    parent's edge names "the entry a declaration gives that application", and a parent
    application nothing declares gives no edge, the minted-record clause gone.
49. **§5.10** — new paragraph **Where an application closes**: declared, absorbed at an
    operand, or closed in place at a type slot, never looked up, `f: page<order>` and
    `f: page_order` two types of one shape; an application over a parameter held whatever
    its head and closing in place when the record is applied. "Resolves at once, to the
    declaration that names it or to its expansion" goes. The forms-over-a-parameter remedy
    `page<[uuid; 1..]>` becomes a declared `uuids`; "no open synthetic entry" goes. An
    abstract template applied in place has no member, members reaching a family through a
    declared application.
50. **§5.10.1** — **Template materialisation** (the self-reaching instantiation entry under
    an internal name) becomes **Recursion ties to a declaration**: a nested application equal
    to the one a declaration is closing names it, and a recursive application with no
    declaration to tie to is refused. Productivity judges types written in place; the
    regularity example reorders parameters, sugar being no argument.
51. **§5.12** — the table's "an application in a value body lifts nothing" reads "a
    reference-typed slot holds a name".
52. **§6** — `@meta.synthetic` goes from the key-position markers and the resolver-attached
    paragraph, which now describes `@meta.application` alone, carried by a declared
    application only; "what lets an imported schema's instantiations unify" goes.
53. **§7.2** — new paragraph **A type written in place is named by no tag**: a value at a
    position typed in place carries no tag naming it and admits none, and a diagnostic
    locates such a type by its field path. A type slot is read against the type-slot choice.
54. **§8.1** — `record_field.type` is `meta.field_type_expr`; the positional form rests on
    the choice; a type written in place is written `{ of: !C { … } }`. `@meta.application` is
    carried by a declared application alone and is what the applied-parent edge consults.
    The resolved-form read no longer recomputes `@meta.synthetic` and checks each type written
    in place against §5.2. The body-patterns table: the use-site row is "written in place",
    the application row "declared application". Entries are declared entries alone.
55. **§8.2** — retitled **Instantiation and Identity** and rewritten, from 17 KB to 6 KB: no
    entry is lifted, minted or merged; a declared application; when two types are one: a name
    and a name naming one entry, past alias hops; two equal types written in place; and a
    name and a type written in place never; canonical applications over names; resolved forms compared as
    values, "up to renaming of internal entries" gone; diagnostics by field path; no consumer
    contract names a type written in place. Gone: trigger positions, the expansion and the
    self-reaching exception, the merge pass, internal names and freshness, the derived name
    written in data, and the import-merge unification of synthetics. **§8.3** — a declared
    instantiation is PRODUCT-kinded, not "its expansion's kind".
56. **§9, §10.1, §11.5, §12** — the kernel row lists `meta.type_inline`, `meta.type_expr`
    and `meta.field_type_expr` and drops `meta.synthetic`; the wait cycle holds the type
    slots, not "the arrays their declarations lift"; §10.1's recomputed list loses
    `@meta.synthetic`; §11.5's entries row loses "synthetic and instantiation entries";
    §12.1's `map-key`, `element-type` and `type-arg` comments state the new rules and the
    `type-name` note on internal names goes; §12.2's generic-key dispatch notes the refusal.
    **§13.2** — the six artifact pins follow the re-stamped kernel (§6).

### 3.3 Companion artifacts

The revised artifacts carry the baseline of §1. Their entries are not edited by this
revision of the text; §6 lists the pins.

### 3.4 Part 3 — JSON Encoding

[TSON-JSON] is edited directly as findings arise, and arrived with part of #3's Part 3 half
in place: §1.6 item 16 (`meta.ref` refines `meta.reference_type`, and its instances are kinds
and references naming values alike), §5's table (a kind's string is a member's type name,
no application), §5.6 (a string holding an application is a validation error whether or not
the closure closes it), and the example's import at `/2026/40/`. This revision completes it:

1. **Header and status** — Revision 40, the fifth published revision of Part 3, written
   against Revision 40 of [TSON-DATA] and [TSON-SCHEMA].
2. **§1.6** — the item list names item 23 as Revision 40's; item 16's sentence on an
   application in data moves to the new **item 23**, *templates are open records, and a
   reference in data is a name*, which names the sections consuming it.
3. **§3.3** — a `$type` naming a template is a validation error.
4. **§3.4** — a root type supplied out of band naming a template is refused on the same
   terms.
5. **§11.2** — `TSON-DATA` and `TSON-SCHEMA` at `/2026/40/`; `TSON-GUIDE` stays at
   `/2026/39/`.

6. **§3.3** (#5) — "an entry a resolver *minted* for an application no declaration names
   also has a name … It is not refused" becomes: a type written in place has no name, no
   `$type` names it, and a value at a position it types admits none.

Part 3 defines no type-system rule, so each is a spelling of a rule §3.2 owns.

---

## 4. Notable normative changes (reader's digest)

Changes a Revision 39 implementer must act on:

1. **A template is an open record.** `meta.record` carries `parameters`; `meta.template`,
   `meta.template_param`, `meta.record_template` and its field records are gone, and so are
   held text bodies.
2. **A parameter is a type**, standing at a field's type or as an argument of an
   application there or in a parent. At a value, facet, size bound, enum member, field
   name or modifier it is a resolver error. There are no value parameters, and an argument
   is a reference.
3. **A bound is written.** A parameter passed to a bounded parameter is written with a
   bound that IS-A it; nothing is inferred.
4. **`C<args>` over a constructor constructs it**: `meta.array<text>` is `[text]`,
   `set<text>` is `!meta.set_type { element_type: text }`, `meta.ref<method>` is
   `!meta.ref { of: method }`. The root's `set` is an alias of `meta.set_type`, and
   `meta.ref` a refined constructor.
5. **No template is a type a position names**, and no application is IS-A its template. A
   family is an abstract base, a template composing it, and members composing an
   application and pinning the selectors.
6. **A template's body is a record body.** No template over a container, an atom, a
   choice, a tuple, a value or a bare reference; no alias template, no partial application.
7. **Inside an open record**, `[T]` and `{K => V}` over a parameter are the references
   `meta.array<T>` and `meta.map<K V>`; a sized or voidable container, a choice or a tuple
   over a parameter is a resolver error. There is no open synthetic entry.
8. **A field whose type names a parameter takes no value and is no selector.**
9. **`meta.tuple1` and `meta.voidable_tuple1` are gone**: a one-position tuple is declared,
   `!meta.tuple [{ element_type: text }]`.
10. **In a data document a reference is a name**: an application there is a validation
    error, and the document names the entry a declaration gives it.
11. **A type slot holds a name or a type written in place** (#5): `tags: [text]` resolves to
    `type: { of: !meta.array { element_type: text } }`. Nothing is lifted, minted or merged;
    `@meta.synthetic`, internal names and comparison up to renaming are gone. An application
    of an open record at a type slot closes in place to a record with no name; a recursive
    one ties to the declaration closing it, and is refused where none does. An argument of
    an open record's application is a name or a parameter, and a map's key is a name.
12. **Every bundled pin changes** (§6).

---

## 5. Open questions carried by this change log

The following remain deliberately open, or are the revision editor's readings awaiting the
spec author's confirmation. They live in this change log only — the specification text
carries no open questions.

| Ref | Question | Status |
|---|---|---|
| #1 | [TSON-DATA] §2.1's example under an `!!import`. | Open — carried as written. |
| #2 | A quoted token at a field typed by a reference in data. | Open — carried; the argument half closed by #3. |
| #3 (reading) | A number or a quoted token in a *schema* document's argument list: a grammar error, as the register and the proposal have it: Part 2 §12.1 is `type-arg = type-expr`, and [TSON-DATA] §7.4's data argument is narrowed the same way. The corpus records the schema-document case under the `resolver` category, which every schema-document error carries, so it does not tell a grammar error from a resolver one. | **[settled]** — the running behaviour. |
| #3 (reading) | The proposal narrows §12.1's `type-def` alternative `[type-params] type-expr` to a record body. A parameter list before a sugar form or a bare reference is a resolver error naming the remedy, so the production keeps the alternative and a comment says the resolver admits a record body alone. | **[editorial]** — the running behaviour. |
| #3 (reading) | §4.1 makes an open record a type by its constructor, `meta.record`, while §5.10's rule is that no template is a type a position names. The text keeps §4.1's predicate and states the refusal on references, as the proposal's §1.3 does; §5.10's heading says "no template is a type a position names". | **[editorial]** — for confirmation. |
| #3 (reading) | A self-reaching expansion kept its closed record under a name derived from the canonical application. | **Superseded by #5**: a recursive application ties to the declaration closing it, and one with none is refused. |
| #3 (reading) | A tag naming an open record in data is a validation error (Revision 39: a resolver error as a data annotation). | **[editorial]** — the running behaviour. |
| #3 (remainder) | An open record is read at its declaration against `record`'s vocabulary, but a name in it that resolves to nothing is reported per application, and not at all while nothing applies the record. The proposal's reading at the declaration asks for more. | Open — outstanding work in the implementation; the text states the declaration-time checks it runs. |
| #5 (ruled) | An application at a refinement source (`x => box<text> ^ { … }`) is absorbed as one at a composition operand is, IS-A the template's written parents and a declaration of the application where one exists. | **[settled]** — the spec author's ruling; the first implementation closes a declared one to its declaration and reports one nothing declares as not implemented. |
| #5 (ruled) | A value modifier on a field typed by a reference written in place (`handler?: meta.ref<method> ~ …`) is admitted, read against the reference as against a declared one; on a container, tuple or choice written in place it is refused, as on a declared one. | **[settled]** — the spec author's ruling. |
| #5 (ruled) | A choice that is not disjoint names every variant: one holding a variant written in place is a resolver error, a tag naming a variant by its name. | **[settled]** — the spec author's ruling. |
| #5 (ruled) | A name and a type written in place are never the same type, even where the name's construction is identical; a name is a name's type past alias hops, and a type written in place an equal one's. | **[settled]** — the spec author's ruling. |
| #5 (ruled) | An author's explicit `!C { … }` at a field stays refused: §5.6's constructor application is valid only as a declaration's body, and §5.3 admits an applied constructor at a field where a `!` construction is not. | **[settled]** — the existing rule. |
| #3 (considered) | A `template` constructor wrapping a body; named construction slots; a construction spelling of its own; a label for a parameter on `record_field`; arguments read as the elements of a list field; parameters on any constructor but `record`. | **Not taken** — the proposal's §9 records each. |
| #3 (lost) | Abstracting over a facet value or a size; a template over a value (one value per instantiation instead); the inline one-position tuple; an alias naming a partial application; a family base named bare. | Recorded — each has a stated replacement in Part 2 §5.10. |

---

## 6. Artifact work

1. **Received at Revision 40** — all eleven artifacts carry "2026 Revision 40 draft" and
   `/2026/40/` identities, and each normative pin was checked against sha256 over the bytes
   past the `!!id` line and **matched** the row in Part 2 §13.2: kernel (`meta.tn`)
   `c3d71e31…` (Revision 39 `c9b069b6…`), numeric `4ff6ac2f…` (`dfa66c5d…`), temporal
   `07864e40…` (`fb4ada8f…`), net `9285bfb1…` (`371fc20c…`), core `e669ac24…`
   (`4e8d9c43…`), policy `d34d967e…` (`e9cb5b93…`), each re-stamped for #5's kernel. The chain is consistent — numeric,
   temporal, net and policy each pin the kernel; core pins the three libraries.
2. **The kernel changes shape, the artifact set does not.** `meta.tn` loses seven entries
   and gains `meta.param`; `meta.ref` and the root's `set` change form; the documentation of
   `meta.record`, `meta.record_field`, `meta.reference_type`, `meta.alias`, `meta.array`
   and `meta.application` is rewritten for the open record. `meta-resolved.tn` follows; the
   four library fixtures change only their identities.

---

## 7. Verification

1. **The examples.** Every Part 2 example written or changed by this revision was run
   through the first implementation's command line against the bundled `/2026/40/` schemas:
   §1.6's schema and data document validate; §5.10's declarations, the family and its data,
   `outcome<text>`, `rebox`, `<K> { m: meta.namespace<K text> }`, `page<[uuid; 1..]>`,
   `created`, `text_pair`, `nested`, the one-position tuples (`[a]` admitted, `[_]` refused,
   and admitted with `voidable: true`), `nonempty<uuid>`, `meta.array<user_id>`,
   `x => box<text> ^ { … }`, `<T> !meta.record { … }` and a parameterised subtraction
   resolve; and each refusal the text names — a bare template at a field or an operand, an
   argument outside its bound, an unbounded or mis-bounded parameter passed on, a bound
   naming a constructor, a literal on a parameter-typed field, a parameter at a value or a
   size bound, the six refused bodies, the four refused forms over a parameter, a parameter
   as an operand or a head, an applied constructor at an operand, `meta.namespace<int32
   text>`, `meta.enum_type<meta.identifier>`, `final` on a template, a shadowing parameter,
   a written `parameters` binding, an unreferenced parameter, irregular recursion, a minted
   family member, an application in data and a tag naming a template — is refused with the
   diagnostic the text describes.
   For Part 1: a schemaless `{ t: pair<box<text> int32> … }` reads; `maxint<10>` and
   `box<"a b">` are refused at the argument; under a schema `t: set<text>` at a
   `meta.type_ref` field is refused and `t: names` over `names => set<text>` is admitted.
   For Part 3: a string holding an application at a reference-typed position, a `$type`
   naming a template, and a root type supplied out of band naming one are each refused.
2. **The pins** — §6 item 1.
3. **#5's examples** — the examples #5 writes or changes run: §1.6's schema, its `history`
   closing `flagged<status>` in place, and its data document; §5.10's `f: page<order>` beside
   `page_order` (a tag naming `page_order` refused at `f`) and `cursor: box<T>` closed in place;
   §5.10.1's `node_text` and `tree_text`, and the refusal of an unnamed recursive application;
   §8.2's `pair<text, text>` at a field; sugar in place, a sugar form refused as an argument and
   the widened positional form.
4. **The build** — the implementation's full build, with the conformance corpus's Revision
   40 branch required, passes over the revised text.
