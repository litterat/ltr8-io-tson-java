---
title: "TSON 2026 Revision 40 — Change Log"
against: "TSON 2026 Revision 39 (Working Draft)"
status: "Proposed, not adjudicated. The six normative artifacts and five resolved fixtures are at `/2026/40/` identities, each pin verified over the shipped bytes and listed in Part 2 §13.2 (§6). Parts 1, 2 and 3 are revised against them (§3.1, §3.2, §3.4); the Developer Guide is not revised and keeps its `/2026/39/` reference. A verification pass over the examples and the build is recorded in §7."
inputs:
  - "SPEC-FEEDBACK.md (entries open against Revision 39; #3, #5 and #6 are the proposals this revision carries)"
  - "Revised companion artifacts at /2026/40/ identities: meta.tn (the kernel), numeric.tn, temporal.tn, net.tn, core.tn, policy.tn and five resolved fixtures (implementing #3)"
  - "tson-part3-json.md, edited directly as the proposal's steps landed (carrying the Part 3 half of #3)"
---

# TSON 2026 Revision 40 — Change Log

This document records the changes proposed for Revision 40 of the TSON specification
series. Its one input of substance is the implementation's spec-feedback register entry #3,
*templates as open records*, whose six steps are built and merged on the Revision 40 proposal
branch of the first implementation and of the conformance corpus. Its second is entry #5,
*inline types*: a type slot holds a name or a type written in place, and no entry is lifted
or minted. Its third is entry #6, *a dependent field's type is a sum in place*: the sibling
leaves the reference, and `kind!` leaves the data grammar. The register's entries #1 and #2
are carried unchanged (§2). SPEC-FEEDBACK.md remains a record against Revision 39 and is not
modified by this revision; each of its entries receives a disposition here.

Provenance markers used in the disposition table:

- **[settled]** — the resolution is stated in the register and implemented in the revised
  companion artifacts, the corpus and the first implementation.
- **[open]** — a genuine design decision deliberately left open. Collected in §5.
- **[editorial]** — a sentence the revision editor had to write that no register entry
  dictates, or a reading the editor took where the sources disagree or admit more than one.
  Each is the spec author's to confirm; collected in §5 beside the open questions.

**This revision does not change the lexer.** The schema grammar's productions change at one
point: a record field's or a group member's type may be a `dependent-type`, `kind!`, which
no other production admits (#6, [TSON-SCHEMA] §12.1); `type-params` still parses before any
body, and the resolver admits it before a record body alone. It takes **both of Revision
39's value forms out of the data grammar**: the argument list, so `<` and `>` in a data value
are parse errors as they were in Revision 38; and `sibling-ref`, so `kind!` in a data value
is a parse error ([TSON-DATA] §7.2.5, §7.4). A value that names something is a token.

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
3. **`H<args>` is schema grammar**, sugar for the construction `meta.apply`, substituting
   where `H` is an open record and constructing where it is a constructor. A reference is a
   name, and no value carries an argument list.

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
- **#6 — a dependent field's type is a sum written in place.** `allow_sibling`,
  `meta.field_type_ref` and `meta.field_type_expr` are gone; `meta.dependent => meta.sum &
  { on: text }` is new; `meta.record_field.type` is a `meta.type_expr`, as every type slot
  is; `meta.type_ref` is `!meta.reference_type { of: meta.type }`; and `meta.ref` is the
  empty refinement `meta.reference_type ^ {}`, kept for its spelling. The kernel's own
  `record_field.value` is typed `type!`, resolving to
  `{ of: !meta.dependent { on: type } }`. (Part 1 §7.4; Part 2 §4.2, §5.2, §8.1, §12.1;
  Part 3 §5.6; meta, fixtures.)

The artifacts also carry housekeeping the register did not raise, adopted on the same
footing:

- **Every identity moves to `/2026/40/`**, and each header comment reads "2026 Revision 40
  draft".
- **`policy.limits.max_materialisation_depth` goes**, with the clause of the `limits` doc
  that described it: closing in place holds each open record of the closure at most once on
  its path, so the limit guarded nothing. Part 2 §11.5's row and Part 1 §9.1's mention
  follow.

---

## 2. Disposition summary

| # | Entry (abridged) | Disposition |
|---|---|---|
| 1 | [TSON-DATA] §2.1's worked example says untagged tokens resolve by §4's base rules under an imported schema | **Carried.** Not among this revision's changes; the entry stays open against Revision 40 as written. [open] |
| 2 | Whether a quoted token at a reference-typed position is a reference | **Carried**, narrowed by #3: an argument is a reference in both grammars, so a quoted argument is refused in a schema document and in data alike; the field-position half (`t: "int32"` at a `meta.type_ref` field in data) stays open as written. [open] |
| 5 | Inline types: a type slot holds a name or a type written in place | **Accept** — the kernel's `meta.type_inline`, `meta.type_expr` and `meta.field_type_expr` and the hand-written resolved fixtures are the baseline; Part 2 §1.3, §1.6, §2.2.3, §3.3.3, §3.4.1, §4.1, §4.2, §4.3, §5.2, §5.3, §5.4, §5.6, §5.7, §5.8, §5.10, §5.10.1, §5.12, §6, §7.2, §8.1, §8.2, §8.3, §9, §10.1, §11.5, §12.1, §12.2 and §13.2 follow (§3.2, items 34–58, 71 and 72–77), and Part 3 §3.3 (§3.4). Four readings the spec author ruled on (§5). [settled — implemented] |
| 3 | Templates as open records | **Accept — the baseline above**, every step as the register states what runs. Part 2 §5.10 is rewritten around the three rules; §1.3, §3.3, §3.4.1, §4.1, §4.2, §4.3, §5.2, §5.3, §5.6, §5.7, §5.8, §5.10.1, §6, §7.2, §7.4, §8.1, §8.2, §8.3, §9, §11.5 and §12 follow (§3.2). §1.6's example drops its value parameter. **[editorial]** at five points where the sources disagree or the text needed a reading (§5). [settled — implemented] |
| 6 | A dependent field's type is a sum in place | **Accept** — the kernel's `meta.dependent`, the retyped `meta.record_field.type`, `meta.type_ref` and `meta.ref`, and the hand-written resolved fixture are the baseline (§1); Part 1 §1.3, §1.5, §2.3, §2.5, §2.8, §4.1, §7.2, §7.2.4, §7.2.5, §7.3, §7.4, §7.5, §7.7 and §8.1 follow (§3.1, items 11–19), Part 2 §1.4, §4.2, §5.2, §5.3, §5.5, §5.6, §5.7, §5.10, §5.11, §7.4, §8.1, §8.2, §9 and §12 (§3.2, items 59–70), and Part 3 §1.6 and §5.6 (§3.4). Four readings the spec author ruled on; one point stays open (§5). [settled — implemented] |

Counts: **3 accepted** (#3, in six steps, #5 and #6), **0 declined**, **2 carried** (#1, #2). Revision
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
9. **§9.1** — the schema limits it names lose "template materialisation depth", the limit
   being gone (§11.5).
10. **§10.2** — `TSON-SCHEMA` and `TSON-JSON` at `/2026/40/`; `TSON-GUIDE` stays at
    `/2026/39/`, the Developer Guide not being revised.

Items 11–19 carry #6, a dependent field's type in place. Each names the rule before and after.

11. **§1.3** — the lexer-freeze paragraph's Revision 40 sentence narrows the data grammar at
    two positions, the second being an unquoted token with an adjacent `!`, which is no core
    value; Revision 39's clause describes that form without naming it "a sibling reference".
12. **§1.5** — a Class 1 processor MUST read "a reference carrying an argument list, and a
    sibling reference" as structured values; it reads the reference alone.
13. **§2.3** — "two scalar forms stand beside the token", a reference and a sibling
    reference; one does, the reference.
14. **§2.5** — the field-order exception is unchanged; its example `value?: type!` is marked
    as the schema grammar's.
15. **§2.8** — a field name is no token where it is "a reference carrying arguments or a
    sibling reference"; the sibling reference goes.
16. **§4.1** — a schemaless read kept a sibling reference "as its field name and the mark";
    the sentence goes.
17. **§7.2, §7.2.4, §7.2.5, §7.3** — `!` was the type prefix "or the suffix of a sibling
    reference", told apart by adjacency; it is the type prefix. The suffix goes from the
    token-position paragraph, the directive rule, the special-character table and the
    `special-char` comment, and the paragraph on `!`'s postfix role goes. The lexer is
    unchanged: `!` is the token it was.
18. **§7.4, §7.5** — `reference = sibling-ref / application` becomes `reference =
    application`; `sibling-ref` and its comment go, and so do its adjacency row and the
    paragraph on the two `!` roles. `kind!` in a data value is a parse error by the
    separator rule (§2.4), and no rule names the mark.
19. **§7.7, §8.1** — the positions the identifier grammar holds at a parse error lose
    `sibling-ref` and "the token of a sibling reference"; the parse-error list loses "a
    sibling reference's `!` not followed by whitespace".

Items 20–25 take the argument list out of the data grammar. With items 11–19, both of
Revision 39's value forms, the argument list and the sibling form, are gone from it: a value
that names something is a token, a qualified name, and `<` and `>` have no role in a data
value, as in Revision 38. Items 2 and 4–8 are superseded where they describe a reference
carrying an argument list. Each names the rule before and after.

20. **§1.3** — the lexer-freeze paragraph's Revision 40 sentence narrowed a reference's
    argument to a reference; it takes both of Revision 39's value forms out of the data
    grammar, so `<` and `>` in a data value are parse errors again. The lexer is unchanged.
21. **§1.5** — a Class 1 processor MUST read "a reference carrying an argument list as the
    structured value §4.1 describes"; the requirement goes.
22. **§2.3, §2.6** — `core-value` loses `reference`, and "one scalar form stands beside the
    token", a reference carrying an argument list, becomes: a value that names something is
    a token, a qualified name, and the argument list is no data syntax. A map key "may be a
    reference" goes.
23. **§3.2, §4.1** — an application "parses where a value stands", kept by a schemaless read
    and a validation error under a schema; it is a parse error, as after `!`. A Class 1 value
    is one of three things again, and a schemaless read reads a qualified name as the token
    it is.
24. **§7.2, §7.2.5, §7.3** — `<` and `>` had a grammar role in data values, the argument
    list; they rejoin the characters the schema grammar reserves, thirteen again, each a
    parse error in a data value. The token-position paragraph loses `<`'s adjacency. The
    lexer is unchanged: `<` and `>` are the tokens they were, for the schema grammar.
25. **§7.4, §7.5, §7.7, §8.1** — `reference`, `application`, `type-args` and `type-arg` go,
    and so does the adjacency row for `<`; the identifier grammar's positions lose "the head
    of a reference"; the parse-error list loses the argument-list cases and gains a special
    token the data grammar gives no role, `<` and `>` among them.

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
30. **§11.5** — the materialisation-depth row goes, and §3.4.1 rule 7 says what bounds
    closing instead: each open record of the closure at most once on a path, a repeat tied
    or refused (§5.10.1).
31. **§12.1** — `type-arg = type-expr`, a number or a quoted token being no argument in
    either grammar; `value-literal` goes; `size-bound` is decimal-natural; the `type-param`
    comment reads "the written type is the parameter's bound"; `type-def` and `instance`
    note that the resolver admits a parameter list before a record body alone; the
    `type-params` note loses the family-base exception. **§12.2** — the dispatch comments
    follow.
32. **§10.3** — the canonical-identity examples at `/2026/40/`.
33. **§13.2** — `TSON-DATA` and `TSON-JSON` at `/2026/40/`, `TSON-GUIDE` left at
    `/2026/39/`; the six artifact rows carry the Revision 40 pins (§6).

Items 34–58 carry #5, inline types. Each names the rule before and after.

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
57. **§5.10, §5.10.1** — an open record holding an application of a recursive open record
    it is not on a cycle of (`wrap => <T> { n: node<T> }`) was accepted, and the refusal fell
    on whoever applied it (`wrap_text => wrap<text>`), where its remedy, declare
    `node_text` and write it, could not be followed. It is a resolver error at its own
    declaration, applied or not: the held applications that would close in place are
    followed, the fields absorbed through an applied parent or a refinement source among
    them, and a recursive record reached on a cycle the declaration is not on means no
    application of it could close. The remedy is the closed type taken as a parameter,
    `wrap => <N> { n: N }` applied to `node_text`.
58. **§4.2, §5.2, §5.10** — a namespace type could be written in place
    (`f: meta.namespace<meta.identifier method>`), the kernel's `type_inline` doc naming
    `namespace` among the constructors admitted there. A namespace value carries its type's
    name as its tag, and a type written in place has none, so a namespace type is declared
    and named, as a record and an enum are; one written in place is a resolver error at the
    declaration that wrote it. §5.10's example of a parameter passed to a constructor's slot
    reads `<K> { m: {K => text} }`.

Items 59–70 carry #6, a dependent field's type in place. Each names the rule before and after.

59. **Header** — the summary gains a sentence: a dependent field's type is a sum written in
    place, `kind!` its sugar at a record field's type alone, and `allow_sibling`,
    `meta.field_type_ref` and `meta.field_type_expr` are gone.
60. **§1.4** — a reference was "a type's name, a value's qualified name, an application, a
    sibling reference, or … a parameter"; the sibling reference goes.
61. **§4.2** — the reference listing loses `allow_sibling` and `field_type_ref`:
    `reference_type => meta.atom & { of: meta.type_ref }`, `ref => meta.reference_type ^ {}`,
    `type_ref => !meta.reference_type { of: meta.type }`. The bullet **A value may be a
    sibling reference** goes, and the type-slot bullet's "a record field's `type` admits a
    sibling reference too". The type-slot listing loses `field_type_expr`. What `of` holds
    gains a dependent type, `!meta.dependent { on: kind }`, directly at a record field's
    `type` and at no other slot; a namespace type and a declared sum are never written in
    place (before: a declared sum was refused only by its absence from the list), a
    dependent type is never declared, and each is a resolver error at the declaration. A
    type written in place's identity is structural "but for a dependent type's, which is its
    field's".
62. **§5.2** — **Dependent fields**: a field's type was "a sibling reference, `kind!`"; it
    is a dependent type, `dependent => meta.sum & { on: text }` written
    `{ of: !meta.dependent { on: kind } }`, `kind!` its schema-grammar sugar, the sum in
    which a field of the record names the type as `meta.declared` is the one in which a tag
    does. **The position rule** (the permission kept at `meta.field_type_ref`, the list of
    refused slots, the facet refusing a sibling reference in data, the quoted name) becomes:
    the sugar parses only at a field's or a group member's type, `kind!?` included among the
    parse errors; the canonical form declared, or in place at another slot, is a resolver
    error at the declaration; the form is no data grammar; and a dependent type has no
    identity apart from the field holding it. The sibling's typing loses
    `meta.field_type_ref` and `meta.field_type_expr`; "and so is a sibling reference" leaves
    the pin-or-default rule; a sibling naming no type reads "a dependent type, an open
    record"; a restatement's body keeps "the dependent type", not `type: kind!`, beside the
    pin. Field names: "a sibling reference" becomes "a dependent type's `on`". Resolution:
    `type` is a `meta.type_expr` value, a dependent type among the types written in place.
    **What may be written in place** gains the dependent type at a record field's type and
    states that a declared sum is never written in place.
63. **§5.3** — the sugar table gains `kind!`, a record field's type: `!meta.dependent { on:
    kind }`, written in place at the field.
64. **§5.5** — the example of a refined reference instance was `meta.type_ref =>
    !meta.field_type_ref ^ { allow_sibling: false }`; it is `meta.identifier_ref =>
    !meta.type_ref ^ { of: unicode.identifier_type }`.
65. **§5.6** — the positional form keeps a bare token at a `meta.type_expr` slot;
    "or `meta.field_type_expr`" goes.
66. **§5.7** — `meta.reference_type`'s `allow_sibling` leaves the permission facets; a
    restated dependent field's body keeps "the dependent type", not `type: kind!`.
67. **§5.10, §5.11** — what a slot can say of a name it holds loses "which sibling's value
    names it", a field instead taking its type from a sibling's value; a group member was a
    `field-name`/`type-expr` pair whose type "may be a sibling reference", and is a
    `field-name`/`field-type` pair, its type a type expression or a dependent type.
68. **§7.4** — "a sibling reference is refused by the facet where `allow_sibling` is false"
    goes.
69. **§8.1, §8.2** — `record_field.type` was "a `meta.field_type_expr` value — a type's
    name, a sibling reference `kind!` written as written, or a type written in place"; it is
    a `meta.type_expr` value, a dependent type among the types written in place. "A sibling"
    leaves what a field's type holds, and "a sibling reference where `allow_sibling` admits
    it" the positional-form paragraph. **§8.2** gains: a dependent type is the type of its
    field and of no other, so two records holding equal
    `{ of: !meta.dependent { on: kind } }` hold two types.
70. **§9, §12** — the kernel row's references lose `meta.field_type_ref`, its type slots
    `meta.field_type_expr`, and `meta.dependent` joins; the wait cycle loses both; the
    guidance's first rule loses "a slot that holds a sibling reference by
    `meta.field_type_ref`". §12's introduction: the reference production no longer carries
    the sibling reference, and `dependent-type` is the schema grammar's alone. **§12.1** —
    `sibling-ref` leaves the imported productions, `refined-def`, `supertype-ref`,
    `type-expr`, `map-key` and the comments of `field-type`, `element-type` and `type-arg`;
    `field-type = type-expr ["?"] / dependent-type`, `dependent-type = unquoted-token "!"`,
    and `group-member` takes a `field-type`. The `sibling-ref` note, under which `[ kind! ]`,
    `box< kind! >`, `( kind! | text )` and `x => kind!` parsed and the resolver refused them,
    becomes the `dependent-type` note, under which each is a parse error. **§12.2** —
    `name!` at a declaration's body and `name !` at a type expression are parse errors but as
    a field's or a member's whole type. **§12.3** — a row for the suffix `!`: adjacent to the
    token before it and followed by whitespace, `a!b`, `kind!}` and `kind!?` parse errors.

Item 71 completes #5's line on a record written in place. It names the rule before and after.

71. **§4.2, §5.10.1** — §4.2 said a type written in place carries no `chain` but not what
    stating one is, nor that a record written in place takes no `parameters`, and nothing
    said what an abstract record written in place denotes. A record written in place takes no
    `parameters`, and `parameters`, or a `chain` on any type written in place, is a resolver
    error at the declaration. An abstract record written in place is empty, no schema being
    able to name it as a parent, so a required field typed by it is refused by the
    productivity rule, as one typed by `meta.ref<meta.alias>` is, and an optional one can
    only be missing; a declared abstract record stays open to its importers' members.

Items 72–77 carry #5's record written at a type slot. Each names the rule before and after.

72. **§5.2** — "bare records (`{ name: text }`) MUST be introduced via named declarations" and
    "the one record in place is the one an application of an open record closes to": a
    record body may be written at a type slot. New paragraph **A record written in place**:
    a record body at a field's type, a group member's, an array's element, a map's value, a
    tuple's element or a choice's variant is one form with the record an application closes
    to — structural, no name, no `chain`, no `parameters`, a member of nothing and named by
    no tag, two with equal fields one type. It holds what a declared body holds and is judged
    as one is; a selector makes it abstract, and so empty. It is not written where only a
    name stands, and composition, refinement and subtraction are declared, `at: point & { z:
    int32 }` a parse error. An enum, every other atom and a namespace type stay declared.
73. **§4.2** — what `of` holds named "the record an application of an open record closes to";
    it is a record, written at the slot or closed to by an application.
74. **§5.3** — the sugar table's `{ fields }` row is the construction in place at a type
    slot; **Nested forms are written in place** shows `at: { x: int32 }`.
75. **§5.10** — "Only an application produces a record in place: an author's bare record at a
    slot stays refused" becomes: the record an application closes to is the record written
    in place with the same fields, one type with it. **Forms over a parameter** gains the
    record written in place over a parameter, a record body substituted with the open record
    holding it when that record is applied.
76. **§12.1, §12.2** — `type-expr` admitted `map-type` where a brace opens a type; it admits
    `brace-type = map-type / record-def`, and §12.2 states one brace dispatch for a
    declaration's body and a type slot, where a type slot's `{` was "map-type, `{` name `:` a
    parse error — bare records must be declared". `{` `{` and `{` `[` are parse errors, a
    map's key being a name; `&`, `^` or `-` after a field's type is a parse error. The
    `map-key` and `type-arg` comments name the brace forms.
77. **Header, §8.1** — the summary's list of what stays where it is written gains a record
    body, and §8.1's row for a type at a slot gains `at: { x: int32 }` among its examples.

Items 78–84 make a parameter a leaf at any slot of a type written in place inside an open
record, replaced when the record is applied. Each names the rule before and after.

78. **§5.10** — **Forms over a parameter**: "admitted only where it is a reference: `[T]` is
    `meta.array<T>` and `{K => V}` is `meta.map<K V>`"; a size or a `?`, a tuple and a choice
    over a parameter were resolver errors. A form is written in place whether or not it names
    a parameter, the parameter a name at its slot, nested to any depth, a record written in
    place included; each is substituted when the open record is applied and judged as the
    same type written with the argument in place. A size bound naming a parameter, and an
    application at a slot that holds only a name, stay resolver errors at the declaration.
79. **§5.10, §5.3, §8.1** — a constructor's application over a parameter, `meta.array<T>`,
    `set<T>`, `meta.ref<T>`, was held as written until the record was applied. It is its
    construction in place at once, so `meta.array<T>` and `[T]` are one resolved form, and an
    application in resolved output applies an open record. **Where an application closes**
    and **In output** say so; §8.1's row for a type at a slot follows.
80. **§5.4, §5.10** — a choice over a parameter, `( T | int32 )`, was refused because its
    disjointness "cannot be judged until it is applied". It loads at the open record, and
    each application is judged where it closes, its variants' distinctness and its
    disjointness alike: `pick<text>` loads, and `p => pick<int32>` is a resolver error at
    `p`. The open record's own choice states `disjoint: false`, a parameter having no class.
    New paragraph in §5.4, **A choice over a parameter is judged where it is applied**.
81. **§5.10** — **Where a parameter stands** names the slots of a type written in place, at
    any depth; the field that takes no value is one whose type names a parameter anywhere,
    the open record's or a record written in place inside it. **A bound is written** names
    a type written in place among the places a parameter is passed on. **Scoping**: a
    closed record holds no parameter but where it is written in place inside an open
    record; anywhere else a name that would resolve only as a parameter is an unresolved
    reference (§8.1).
82. **§4.2, §5.2, §5.3, §5.6** — §4.2's two statements of where a parameter or an
    application stands, §5.2's record written in place, §5.3's **Arguments** (a sugar form
    as an argument is refused whatever the head, `meta.array<[T]>` included, and an
    application as an argument of a constructor's application closes at its slot) and
    **Nested forms are written in place** (a size or `?` is legal over a parameter too), and
    §5.6's end state ("a binding record never holds a parameter") are restated.
83. **Header, §1.3, §8.1, §12.1** — the summary, the resolved-output consumers' paragraph,
    §8.1's **Open records** and **Reading parameter references**, and the `type-arg` comment
    follow.
84. **Kernel** — the documentation of `meta.param`, `meta.reference_type` and
    `meta.type_inline` states where a parameter stands as above; `meta-resolved.tn`'s
    conventions note follows. Every pin changes (§6).

Items 85–93 take the application out of the reference: a reference is a name, and an
application is the construction `meta.apply` written in place. Each names the rule before
and after.

85. **§4.2, §1.4** — "a reference is a name, an application or a parameter", and "a value
    may be an application", `geo.address<T>`, read as a reference value. A reference is a
    name or, inside an open record, a parameter. A value carrying an argument list, which
    the data grammar still parses, is refused at every reference-typed slot: a validation
    error in data and a resolver error in a schema document, a construction's payload and a
    value entry's included, the diagnostic naming how an application is written.
86. **§4.1, §4.2, Kernel** — new constructor `apply => final meta.type & { head:
    meta.type_ref  arguments: [meta.type_ref; 1..] }`. It composes `meta.type` and no base
    kind: an application stands for a type that exists only once its open record is applied,
    so it has no kind and nothing reads a value against it. What `of` holds gains it, inside
    an open record only.
87. **§5.10, §5.3, §8.1** — an open record's application over a parameter "held as written"
    as a reference with arguments is the construction written in place, `{ of: !meta.apply {
    head: box  arguments: [T] } }`, at a field's type, at a slot of a type written there or
    among `chain.parents`, and closes when the record is applied. The explicit spelling
    means what `box<T>` means and closes the same way. In resolved output an application
    stands only inside an open record, over one of its parameters, applying an open record;
    reading a resolved form checks it, as a dependent type's position is checked.
88. **§4.1, §5.8, Kernel** — `type_chain.parents` was `[meta.type_ref]`, holding `result<T>`
    with its arguments; it is `[meta.type_expr]`, a name or an application in place.
    `supertypes` and `subtypes` stay names.
89. **§6, §8.1, §8.2, Kernel** — `@meta.application:pair<text integer>`, a reference with
    arguments, is `@meta.application:{ head: pair  arguments: [text integer] }`, a value of
    `meta.apply`; `application => @meta.annotation meta.apply`. An applied parent's edge and
    a recursion tying to its declaration compare applications as values, head and
    arguments, never as text.
90. **§5.3, §5.6, §5.12, §12.1** — "in resolved output the application is the reference
    value [TSON-DATA] §7.4 reads" and the `type-ref` comment "[TSON-DATA] §7.4's production"
    are restated: the schema grammar's application is its own, sugar for `meta.apply`. A
    reference over a constructor holds a name, never an argument list.
91. **§5.2, Kernel** — `dependent => final meta.sum & { on: text }`: nothing composes or
    refines it, so the position rule is the one constructor's.
92. **§5.10, Kernel** — `meta.apply` is final for the same reason, so no constructor refining
    it can be written in place under the allow-list's "a constructor refining an admitted
    one".
93. **Header, §1.3, §13.2** — the summary and the kernel's inventory gain `meta.apply`;
    every pin changes (§6).

Items 94–97 follow the argument list out of the data grammar (§3.1 items 20–25).

94. **§4.2** — "the data grammar still parses an argument list after a name", and a value
    carrying one is "a validation error in a data document and a resolver error in a
    schema document". It is a parse error wherever a value stands, a construction's payload,
    a value entry's and a resolved form read as data included: the data grammar's ordinary
    error for a character it reserves, as for `kind!`, the grammar not knowing the form.
    Item 85's "which the data grammar still parses" is
    superseded.
95. **§3.3.4, §7.2, §7.4** — an application in a data document, at a reference-typed
    position or where a tag would be, was a validation error; it is a parse error ([TSON-DATA]
    §7.2.5).
96. **§12.1, §12.3, §5.2** — the `instance` comment: "an application inside it is the
    reference production of [TSON-DATA] §7.4, written unquoted"; an argument list in a
    payload is a parse error, and a type slot there writes an application as `{ of:
    !meta.apply { … } }`. The application's `<` adjacency, which cited [TSON-DATA] §7.5's
    row, is a row of §12.3's table, the argument list being this grammar's alone. §5.2's
    "the form is no data grammar" cites the separator rule ([TSON-DATA] §2.4).
97. **Header, Kernel** — the summary's "a value carrying an argument list is refused
    wherever a reference is read" names the parse error; `meta.reference_type`'s
    documentation drops the argument list the data grammar parsed. Every pin changes (§6).

Items 98–101 make `meta.ref` the one reference constructor. Each names the rule before and
after.

98. **§4.2, Kernel** — `reference_type => meta.atom & { of: meta.type_ref }` and `ref =>
    meta.reference_type ^ {}`, a refined constructor kept so `meta.ref<T>` kept its spelling,
    become one constructor, `ref => meta.atom & { of: meta.type_ref }`, and `type_ref =>
    !meta.ref { of: meta.type }`. `meta.ref<T>` is still the construction `!meta.ref { of: T }`,
    and whether a reference names types or values is still read from `of`. The kernel's
    documentation of the two merges onto `meta.ref`. A schema naming `meta.reference_type` names
    an unresolved entry.
99. **§4.2, §9, §13** — every other mention: `meta.reference_type.of` is `meta.ref.of` among
    the slots typed `meta.type_ref`, the example of an atom constructor and the kernel inventory
    name `meta.ref`, the wait cycle's entries hold `meta.ref` in its place, and the
    refined-constructor example of §4.2's type slot keeps `!meta.set_type` alone.
100. **Header** — "`meta.ref` is a refined constructor" becomes "`meta.ref` is the one
     reference constructor, `meta.reference_type` gone".
101. **§13.2** — every pin changes (§6); the kernel measures 82 entries.
102. **§3.4.1** — rule 1 named the parts of a value a read waits on, a field's type, an
     element's, a nested tag's and an annotation's, and was silent on a value a family places by
     its pins (§5.2), whose member nothing in the value names. Rule 1 gains: a value read at a
     family's selectors whose pins no ready member states waits on each declaration not yet
     ready that may be that member, judged from what it writes: its written operands reach the
     family's base, and the nearest pin written along them does not differ from the value's.

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

7. **§1.6** (#6) — item 16: `meta.ref` refines `meta.reference_type`, "with `allow_sibling`
   pinned false" gone. Item 21: the kernel's `allow_sibling` sentence and its consumption by
   §5.6, "where a sibling reference is a string holding the text encoding's `kind!`", become:
   the field's type is a sum written in place and no reference, so no value of this encoding
   spells it as a string. §6.1.6's consumption, the member-order exception, stands.
8. **§5.6** (#6) — a sibling reference was a string holding the text encoding's `kind!`,
   admitted at a record field's type and refused elsewhere by the facet; the string form is
   gone, and `"kind!"` at a kind's position is no reference, refused as the production
   refuses it. A reference naming values loses "and no sibling reference".
9. **§1.6, §5.6** — item 23 and §5.6: a string holding an application was a validation
   error, read by [TSON-DATA] §7.4's reference production so that the two encodings refused
   one document for one reason; the production is gone ([TSON-DATA] items 20–25). A
   reference's string is a qualified name, so `"set<text>"`, like `"kind!"`, is a string
   outside the kind's grammar and a resolver error, as any string outside an atom's grammar
   is. The text encoding refuses the unquoted form earlier, as a parse error, and its quoted
   token as JSON does. Item 8's "refused as the production refuses it" is superseded.
10. **§1.6, §5.6** — item 16 and §5.6 named "the kernel's `meta.reference_type`, or its
    refinement `meta.ref`"; they name `meta.ref`, the one reference constructor (§3.2 items
    98–101).

Part 3 defines no type-system rule, so each is a spelling of a rule §3.2 owns.

---

## 4. Notable normative changes (reader's digest)

Changes a Revision 39 implementer must act on:

1. **A template is an open record.** `meta.record` carries `parameters`; `meta.template`,
   `meta.template_param`, `meta.record_template` and its field records are gone, and so are
   held text bodies.
2. **A parameter is a type**, standing at a field's type, at a slot of a type written in
   place there, or as an argument of an application at either or in a parent. At a value, facet, size bound, enum member, field
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
7. **An application is no reference** (items 85–90): it is `meta.apply` written in place,
   which resolved output holds only inside an open record over one of its parameters, and a
   value carrying an argument list is refused wherever a reference is read.
   **Inside an open record** a form over a parameter is written in place like any other
   (items 78–80): `[T; 1..]`, `[T?]`, `[T, text]`, `( T | int32 )` and `{ cursor: T }` load,
   a constructor's application over a parameter is its construction in place, and a choice
   over a parameter is judged where each application closes. There is no open synthetic
   entry.
8. **A field whose type names a parameter takes no value and is no selector.**
9. **`meta.tuple1` and `meta.voidable_tuple1` are gone**: a one-position tuple is declared,
   `!meta.tuple [{ element_type: text }]`.
10. **In a data document a reference is a name**: an application there is a parse error,
    and the document names the entry a declaration gives it.
11. **A type slot holds a name or a type written in place** (#5): `tags: [text]` resolves to
    `type: { of: !meta.array { element_type: text } }`. Nothing is lifted, minted or merged;
    `@meta.synthetic`, internal names and comparison up to renaming are gone. An application
    of an open record at a type slot closes in place to a record with no name; a recursive
    one ties to the declaration closing it, and is refused where none does; an open record
    holding a recursive one it is not on a cycle of could never close, and is refused at its
    declaration. An argument of
    an open record's application is a name or a parameter, and a map's key is a name. A
    record body may be written at a type slot, `at: { x: int32 }`, the same form an
    application closes to; composition and refinement stay declared.
12. **Every bundled pin changes** (§6).
13. **A dependent field's type is a sum written in place** (#6): `payload: kind!` is
    schema-grammar sugar for `type: { of: !meta.dependent { on: kind } }`, admitted at a
    record field's type alone. `kind!` is no reference value, no data grammar and no JSON
    string; `allow_sibling`, `meta.field_type_ref` and `meta.field_type_expr` are gone, and
    `meta.record_field.type` is a `meta.type_expr`.
14. **The data grammar is tokens and structures again** (§3.1 items 20–25): Revision 39's
    argument list and sibling form are both gone from it. `<` and `>` in a data value are
    parse errors, as in Revision 38, in a data document and a schema value's payload alike,
    and a JSON string at a reference holds a qualified name and nothing else.
15. **One reference constructor** (§3.2 items 98–101): `meta.ref => meta.atom & { of:
    meta.type_ref }`; `meta.reference_type` is gone, and a schema writing it names nothing.
    `meta.ref<T>` keeps its spelling and its meaning.
16. **A value placed by its pins waits on the member it may be** (§3.2 item 102): read at a
    family's selectors where no ready member states its pins, it waits on each declaration
    not yet ready whose written operands reach the base and whose nearest written pin does not
    differ, so a value selecting a member declared after it loads, and one whose member waits
    on the reading entry is a wait cycle.

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
| #6 (ruled) | `kind!` in a data document is refused by the separator rule's parse error, the token before it read as the position reads any token. A targeted rule naming the mark would put the form back in the data grammar. | **[settled]** — the spec author's ruling. |
| #6 (ruled) | `meta.ref` withdraws nothing once `allow_sibling` is gone. It stays, as the empty refinement `meta.reference_type ^ {}`, so `meta.ref<T>` keeps its spelling. | **[settled]** — the spec author's ruling. |
| #6 (ruled) | `meta.dependent.on` is `text`, naming a field of the record by its declared spelling, declared before the dependent field, as a `discriminators` entry names one. | **[settled]** — the spec author's ruling. |
| #6 (ruled) | A declared sum written in place was refused only by its absence from Part 2 §4.2's list of what `of` holds. §4.2 states it, beside a namespace type. | **[settled]** — the spec author's ruling. |
| #6 (open) | A data document typed by the kernel can write a type in place that §4.2's list refuses (`!meta.array { element_type: { of: !meta.dependent { on: kind } } }` reads with no diagnostic): `meta.type_inline.of` is typed `meta.type`, and the list is a resolver rule. It predates `meta.dependent`, which adds one entry to the list. | Open — carried. |
| Items 78–84 (ruled) | A choice over a parameter, `( T \| int32 )`, is disjoint for some arguments and not others. Refusing it at the declaration, or requiring disjointness for every argument, were the alternatives; it loads at the open record, and each application is judged where it closes. | **[settled]** — the spec author's ruling. |
| #3 (considered) | A `template` constructor wrapping a body; named construction slots; a construction spelling of its own; a label for a parameter on `record_field`; arguments read as the elements of a list field; parameters on any constructor but `record`. | **Not taken** — the proposal's §9 records each. |
| #3 (lost) | Abstracting over a facet value or a size; a template over a value (one value per instantiation instead); the inline one-position tuple; an alias naming a partial application; a family base named bare. | Recorded — each has a stated replacement in Part 2 §5.10. |

---

## 6. Artifact work

1. **Received at Revision 40** — all eleven artifacts carry "2026 Revision 40 draft" and
   `/2026/40/` identities, and each normative pin was checked against sha256 over the bytes
   past the `!!id` line and **matched** the row in Part 2 §13.2: kernel (`meta.tn`)
   `5d3259d6…` (Revision 39 `c9b069b6…`), numeric `b0f26fd6…` (`dfa66c5d…`), temporal
   `64cb1d31…` (`fb4ada8f…`), net `f60bed4e…` (`371fc20c…`), core `a0e317b3…`
   (`4e8d9c43…`), policy `85f90a35…` (`e9cb5b93…`), each re-stamped for #5's kernel, its
   declared namespace type and its record written at a slot and for #6's, policy again for
   the materialisation depth's removal, and the kernel and its dependants again for items
   78–84's documentation of a parameter in a type written in place, for items 85–93's
   `meta.apply` and final constructors, and for items 94–97's documentation of a reference
   with no argument list, and for items 98–101's one reference constructor. The chain is
   consistent — numeric, temporal, net and policy each pin the kernel; core pins the three
   libraries.
2. **The kernel changes shape, the artifact set does not.** `meta.tn` loses seven entries
   and gains `meta.param`; `meta.ref` and the root's `set` change form; the documentation of
   `meta.record`, `meta.record_field`, `meta.reference_type`, `meta.alias`, `meta.array`
   and `meta.application` is rewritten for the open record. #6 removes `allow_sibling`,
   `meta.field_type_ref` and `meta.field_type_expr`, adds `meta.dependent`, and rewrites the
   documentation of `meta.record_field`, `meta.reference_type`, `meta.ref`, `meta.type_ref`,
   `meta.type_inline` and `meta.type_expr`; #5's record written at a slot rewrites
   `meta.type_inline`'s again, a record no longer declared and named. Items 85–93 add
   `meta.apply`, retype `type_chain.parents` and `application`, mark `meta.dependent` final,
   and rewrite the documentation of `meta.type`, `meta.reference_type`, `meta.type_inline`,
   `meta.type_expr` and `meta.type_chain`: the kernel measures 83 entries. Items 94–97
   rewrite `meta.reference_type`'s again. Items 98–101 merge `meta.reference_type` into
   `meta.ref`: the kernel measures 82 entries. `meta-resolved.tn` follows; the four library fixtures change only their identities.

---

## 7. Verification

1. **The examples.** Every Part 2 example written or changed by this revision was run
   through the first implementation's command line against the bundled `/2026/40/` schemas:
   §1.6's schema and data document validate; §5.10's declarations, the family and its data,
   `outcome<text>`, `rebox`, `<K> { m: {K => text} }`, `page<[uuid; 1..]>`,
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
   §5.10.1's `node_text` and `tree_text`, the refusal of an unnamed recursive application,
   `wrap` refused at its declaration applied or not, and the parameter remedy;
   §8.2's `pair<text, text>` at a field; sugar in place, a sugar form refused as an argument and
   the widened positional form.
5. **Items 78–84's examples** — `[T; 1..]`, `[T?]`, `[T, text]`, `( T | int32 )`, `{T =>
   int32}`, `{ cursor: T }` and `[{ at: T  tags: {text => T} }]` load and read through a
   declared application and one at a slot, each equal to the type written by hand;
   `meta.array<T>`, `meta.map<text meta.array<T>>`, `set<T>` and `meta.ref<T>` resolve to the
   same form as their sugar; `pick<int32>` is refused at its declaration; `{box<T> => text}`,
   `m: { w?: T ~ 5 }`, `meta.array<[T]>`, an irregular recursion inside a type in place and
   `f: [node<text>]` are refused.
4. **The build** — the implementation's full build, with the conformance corpus's Revision
   40 branch required, passes over the revised text.
