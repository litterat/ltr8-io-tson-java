---
title: "TSON 2026 Revision 40 — Change Log"
against: "TSON 2026 Revision 39 (Working Draft)"
status: "Adjudicated 2026-11-24. The six normative artifacts and five resolved fixtures are at `/2026/40/` identities, each pin verified over the shipped bytes and listed in Part 2 §13.2 (§6). Parts 1, 2 and 3 are revised against them (§3.1, §3.2, §3.4); the Developer Guide is revised with them and referenced at `/2026/40/`. A verification pass over the examples and the build is recorded in §7."
inputs:
  - "SPEC-FEEDBACK.md, the register against Revision 39, published with this revision as its close-out record (§2): entries #1–#9 registered before the revision answered them, and #10–#21 written up at its close for the design changes made in its text without a register entry"
  - "Revised companion artifacts at /2026/40/ identities: the six bundled schemas and five resolved fixtures (§1)"
  - "tson-part3-json.md, edited directly"
  - "The open-construction design, worked out in the proposal's text and written up at the close as register entries #10–#12 (§3.2 items 105–109)"
  - "The extern design, worked out in the proposal's text and written up at the close as register entry #13 (§3.2 items 114–116)"
---

# TSON 2026 Revision 40 — Change Log

This document records the changes proposed for Revision 40 of the TSON specification
series. **§4 is the net change from Revision 39 to Revision 40**, arranged by what an
implementer touches, with migration notes for Revision 39 documents (§4.10) and what is lost
(§4.11); §3 holds each change in detail, item by item, and §5 the rulings and the
alternatives not taken.

Five designs carry the revision. Register entry #3, *parametric records as records with
parameters*, in six steps. Entry #5, *inline types*: a type slot holds a name or a type
written in place, and no entry is lifted or minted. Entry #6, *a dependent field's type is a
sum in place*: the sibling leaves the reference, and `kind!` leaves the data grammar. *Open
construction*, worked out in the proposal's text and registered only at the close, as
entries #10–#12: a construction may be left open as an incomplete entry, which the kernel's
`enum` and `set` become, and record refinement goes, `^` following `!name` and `&` being the
one way to derive a record (§3.2 items 105–109). And the *extern*, likewise entry #13: a
schema names one entry of another schema under a name of its own, and a value so typed is
read under its own schema (§3.2 items 114–116). The register's entries #1, #7, #8 and #9 are
accepted as they propose, each a sentence or a paragraph stating what the series already
holds; #2 and #4 are accepted by the spec author's rulings (§2). The register,
SPEC-FEEDBACK.md, is the record against Revision 39 and is published with this revision as
its close-out record. It keeps its nine entries whole and adds twelve, #10–#21: the design
changes this revision made in its text without registering them first, written up in the
register's form when the revision closed so that the record is whole. Each entry receives
its disposition in §2.

Provenance markers used in the disposition table:

- **[settled]** — the resolution is stated in the register and implemented in the revised
  companion artifacts, the corpus and the first implementation.
- **[open]** — a genuine design decision deliberately left open. Collected in §5.
- **[editorial]** — a sentence the revision editor had to write that no register entry
  dictates, or a reading the editor took where the sources disagree or admit more than one.
  Each is the spec author's to confirm; collected in §5 beside the open questions.

**This revision does not change the lexer.** It takes **both of Revision 39's value forms
out of the data grammar**: the argument list, so `<` and `>` in a data value are parse errors
as they were in Revision 38, and the sibling form, so `kind!` in a data value is a parse error
([TSON-DATA] §2.4, §7.2.5, §7.4). A value that names something is a token. The schema
grammar's productions change where §4's grammar row says: `refined-def` goes, so `^` follows
`!name` alone; the sibling form survives only as `dependent-type`, a record field's or a group
member's whole type; a brace at a type slot opens a map type or a record body; and an
argument is a type expression, never a literal ([TSON-SCHEMA] §12.1).

What it changes most is **parametric records, and with them the kernel**. Revision 39 gave a
template two representations and a parameter two kinds: a record template was its own
constructor and a family base, every other template was held as text, and a parameter's type
and bound were derived by a fixed point across templates. Revision 40 replaces all of it with
three rules (#3):

1. **A parametric record is a record with parameters.** `meta.record` carries `parameters?:
   [meta.param; 1..]`, each a type standing at a field's type, at a slot of a type written in
   place there, or as an argument of an application at either or in a parent, and nowhere
   else. A reference names a parametric record only as the head of an application.
2. **`C<args>` over a constructor is construction sugar**: `!C { f₁: a₁ … }` over its
   argument slots, the required fields with no default or pin that hold a type, in
   declaration order, as `[T]` and `{K => V}` already were. Over an incomplete entry it
   completes it (`set<text>`).
3. **`H<args>` is schema grammar**, sugar for the construction `meta.apply`, substituting
   where `H` is a parametric record and constructing where it is a constructor. A reference is
   a name, and no value carries an argument list.

What follows from them: no value parameters, no derived or inherited bounds, no parametric
record a position names, no parametric record over a body that is not a record, no
parametric alias, and no `meta.template` or `meta.record_template`. Every bundled pin
changes; the artifact set keeps its shape.

---

## 1. Baseline: the revised artifacts

Revision 40 adopts the companion artifacts as they stand as its baseline: the six normative
documents, `meta.tn` (the kernel), `numeric.tn`, `temporal.tn`, `net.tn`, `core.tn` and
`policy.tn`, and the five resolved fixtures, each at a `/2026/40/` identity, each header
reading "2026 Revision 40 draft", each pin Part 2 §13.2's (§6). §4.3 lists every kernel entry
removed, added and changed against Revision 39. In summary, the artifacts carry:

- **#3, parametric records, in six steps.** (1) A parameter is a type and an argument a name;
  a parameter at a facet, a field's default or fixed value, an enum member, a size bound, a
  field name or a modifier is a resolver error at the declaration, naming the slot, and a
  value that differs per application is written where the application is declared,
  `created => response<order> & { status?: = 201 }`. (2) A bound is written, never inherited:
  `meta.param.bound` is what the declaration writes, and a parameter passed to a bounded
  parameter is written with a bound that IS-A it. (3) `meta.array<text>` is the construction
  `[text]` is; a field with a default is no argument slot; an argument is not followed through
  an alias; there is no parametric alias. (4) No position, reference, operand or `subtypes`
  index names a parametric record bare; an application's IS-A edges are its head's written
  parents'; a family is an abstract base, a parametric record composing it, and members
  composing an application and pinning the selectors. (5) `meta.record` carries
  `parameters?: [meta.param; 1..]`, `meta.param => { name: meta.param_name  bound?:
  meta.type_ref }`, and a parametric record's body is a record body; `meta.template`,
  `meta.template_param`, `meta.record_template`, its two field records, `meta.tuple1` and
  `meta.voidable_tuple1` are gone. (6) A value in a data document that names something is a
  name: an argument list there is a parse error, and a JSON string holding one is outside a
  reference's grammar. (Part 1 §2.3, §3.2, §7.2.5, §7.4; Part 2 §1.3, §4.1, §4.2, §5.2, §5.3,
  §5.8, §5.10, §8.1, §9; Part 3 §1.6, §5.6.)
- **#5, inline types.** `meta.type_inline => { of: meta.type }` and `meta.type_expr =>
  @meta.disjoint ( meta.type_ref | meta.type_inline )`; every type slot that may hold a type
  written in place is typed `meta.type_expr`, and `meta.synthetic` is gone. The resolved
  fixtures write container sugar, a record body and an application closed at a slot where
  they stand. (Part 2 §4.2, §5.3, §8.2.)
- **#6, a dependent field's type in place.** `allow_sibling` and `meta.field_type_ref` are
  gone; `meta.dependent => final meta.sum & { on: text }` is new; `meta.record_field.type` is
  a `meta.type_expr`; the kernel's own `record_field.value` is typed `type!`, resolving to
  `{ of: !meta.dependent { on: type } }`. (Part 1 §7.4; Part 2 §4.2, §5.2, §12.1; Part 3 §5.6.)
- **References and applications.** `meta.ref => meta.atom & { of: meta.type_ref }` is the one
  reference constructor, `meta.reference_type` gone, and `meta.type_ref => !meta.ref { of:
  meta.type }`; `meta.apply => final meta.type & { head: meta.type_ref  arguments:
  [meta.type_ref; 1..] }` is new, `@meta.application` its value as an annotation, and
  `type_chain.parents` a `[meta.type_expr]`. (§3.2 items 84–100.)
- **Open construction.** `meta.set_type` and the root's `enum` are incomplete entries,
  `meta.completion` is new, `meta.namespace` is the composition `meta.map & { … }`, and
  `type_chain`'s `supertypes` and `subtypes` are sets. (§3.2 items 103–107.)
- **The extern.** `meta.extern => meta.top & { schema: iri_reference  type: text }` is new.
  (§3.2 items 114–116.)
- **Housekeeping.** `meta.field_name` is removed (§3.2 item 123); `policy.script_policy` is
  `policy.token_policy` (§3.1 item 23); the temporal families' `precision` is
  `fraction_digits` (§3.2 item 113); `policy.limits.max_materialisation_depth` goes, with the
  clause of the `limits` doc that described it, closing in place holding each parametric
  record of the closure at most once on its path, and Part 2 §11.5's row and Part 1 §9.1's
  mention follow; every artifact's documentation is rewritten (§3.3).

---

## 2. Disposition summary

| # | Entry (abridged) | Disposition |
|---|---|---|
| 1 | [TSON-DATA] §2.1's worked example says untagged tokens resolve by §4's base rules under an imported schema | **Accept** — the sentence is rewritten for a document under a schema: its tags name types of the imported namespace and each untagged token is read by its position's declared type, with what the same tags and tokens mean in a document with no `!!import` (§3.1 item 20). No rule changes. **[settled]** |
| 2 | Whether a quoted token at a reference-typed position is a reference | **Accept**, the other way from the entry's proposal: a quoted token at a position typed by a reference is the reference its text spells, each segment a name of the family of what it enters, and no family a segment is matched against admits `.` (§3.1 item 22, §3.2 items 117–118). The argument half closed with #3: an argument is schema grammar and stays unquoted. **[settled]** |
| 5 | Inline types: a type slot holds a name or a type written in place | **Accept** — the kernel's `meta.type_inline` and `meta.type_expr` and the hand-written resolved fixtures are the baseline; Part 2 §1.3, §1.6, §2.2.3, §3.3.3, §3.4.1, §4.1, §4.2, §4.3, §5.2, §5.3, §5.4, §5.6, §5.7, §5.8, §5.10, §5.10.1, §5.12, §6, §7.2, §8.1, §8.2, §8.3, §9, §10.1, §11.5, §12.1, §12.2 and §13.2 follow (§3.2, items 33–57, 70 and 71–76), and Part 3 §3.3 (§3.4). Its readings are the spec author's rulings, and one implementation remainder is open (§5). [settled — implemented] |
| 3 | Parametric records as records with parameters | **Accept — the baseline above**, every step as the register states what runs. Part 2 §5.10 is rewritten around the three rules; §1.3, §3.3, §3.4.1, §4.1, §4.2, §4.3, §5.2, §5.3, §5.6, §5.7, §5.8, §5.10.1, §6, §7.2, §7.4, §8.1, §8.2, §8.3, §9, §11.5 and §12 follow (§3.2). §1.6's example drops its value parameter. **[editorial]** at three points where the sources disagree or the text needed a reading, and one implementation remainder is open (§5). [settled — implemented] |
| 6 | A dependent field's type is a sum in place | **Accept** — the kernel's `meta.dependent`, the retyped `meta.record_field.type` and the hand-written resolved fixture are the baseline (§1); Part 1 §1.3, §1.5, §2.3, §2.5, §2.8, §4.1, §7.2, §7.2.4, §7.2.5, §7.3, §7.4, §7.5, §7.7 and §8.1 follow (§3.1, items 6–13), Part 2 §1.4, §4.2, §5.2, §5.3, §5.5, §5.6, §5.7, §5.10, §5.11, §7.4, §8.1, §8.2, §9 and §12 (§3.2, items 58–69), and Part 3 §1.6 and §5.6 (§3.4). Its readings are the spec author's rulings (§5). [settled — implemented] |
| 4 | A tag at a choice names a variant; a choice with an abstract variant has no values | **Accept**, the entry's first resolution: a tag at a choice names a variant, or a type that IS-A exactly one variant, and the value is read at that variant as at a position typed by it, so an abstract variant admits its family; a tag that IS-A two variants and names neither is refused at the data (§3.2 item 119). **[settled]** |
| 7 | A free sibling hands the choice of a dependent field's type to the document's author | **Accept** — one paragraph in Part 2 §11.1, and no change to §5.2's rules (§3.2 item 110). **[settled]** |
| 8 | A name inside an annotation's value: that it is judged, where, and when | **Accept** — Part 2 §5.12's "Where the rule stands" names an annotation's value among the slots the rule reaches, with its namespace and its time, and §6 points at it (§3.2 item 111). No rule changes; the first implementation judges a data document's annotation value and not yet a schema document's (§5). **[settled]** |
| 9 | `net.uuid_type.version` has no stated meaning | **Accept** — Part 2 §5.5 states it, a UUID whose RFC 9562 version field equals `version`, and §5.7 lists it among the settable-once facets (§3.2 item 112), which is what runs. **[settled]** |
| 10 | A construction may be left open; `enum` and `set` are incomplete entries | **Recorded at the close** — made in the text and not registered first: `!C ^ { values }` declares an incomplete entry, completed into the plain construction, and the kernel's `meta.set_type` and `enum` are two (§3.2 items 106, 107 and 109; §3.4 item 11). Its readings are the spec author's rulings, and two implementation remainders are open (§5). [settled — implemented] |
| 11 | `^` follows `!name` and takes values; `&` is the one way to derive a record | **Recorded at the close** — made in the text on the spec author's ruling: record refinement and `refined-def` go, a restated field's rules stand in §5.8, and `meta.namespace` is a composition (§3.2 items 105, 108, 109 and 127). [settled — implemented] |
| 12 | `^` refines a collection type, a namespace type among them | **Recorded at the close** — made in the text with #10: a type `meta.array`, `meta.map` or a constructor IS-A either built is refined by its facets' kinds, and a type-holding facet is restated by the entry its name reaches (§3.2 items 106, 120 and 121). [settled — implemented] |
| 13 | An extern, and the scope of a foreign value | **Recorded at the close** — made in the text on the spec author's rulings: one entry of another schema under a local name, a hop judged by identity, sealed, read with the imports, and a value at its position read in the foreign schema's scope (§3.1 item 21; §3.2 items 114–116 and 124; §3.4 items 13 and 16). Five implementation remainders are open. [settled — implemented] |
| 14 | One reference constructor, `meta.ref` | **Recorded at the close** — made in the text: `meta.reference_type` and `meta.ref` are one constructor (§3.2 items 97–100; §3.4 item 10). [settled — implemented] |
| 15 | A nested choice is disjoint; a choice over a parameter is judged where it closes | **Recorded at the close** — made in the text on the spec author's rulings, with a tag naming a variant by the entry it reaches (§3.2 items 79, 125 and 126). [settled — implemented] |
| 16 | An entry has no derived kind | **Recorded at the close** — made in the text: §8.1's kind label and §4.1's membership sentence go (§3.2 item 132). [settled — implemented] |
| 17 | `extension` is the record definition's: its selectors and its mark | **Recorded at the close** — made in the text on the spec author's rulings: `discriminators` requires `extension: ABSTRACT`, and a definition mark stands before a record definition alone (§3.2 items 133 and 136). [settled — implemented] |
| 18 | An alias of a parametric record is another name for it | **Recorded at the close** — made in the text on the spec author's ruling (§3.2 item 135). [settled — implemented] |
| 19 | A value a family places by its pins waits on each declaration that may be its member | **Recorded at the close** — made in the text: §3.4.1's rule 1 gains the sentence (§3.2 item 101). [settled — implemented] |
| 20 | A record with parameters is a parametric record | **Recorded at the close** — made in the text on the spec author's ruling (§3.2 item 131, also among #3's). [settled] |
| 21 | The kernel and the libraries: one name for each thing | **Recorded at the close** — made in the text, `fraction_digits` on the spec author's ruling: `policy.token_policy`, the two loosest levels over a token, `meta.field_name` removed, a chain's lists as sets, and `fraction_digits` (§3.1 items 23 and 24; §3.2 items 103, 113 and 123). [settled — implemented] |

Counts: **9 accepted** (#3, in six steps, #5, #6, #2 and #4; and #1, #7, #8 and #9, which change no rule), **0 declined**, **0 carried**; and **12 recorded at the close** (#10–#21), design changes made in the text without a register entry, written up in the register's form when the revision closed and disposed of by no verdict. Revision
39's open remainders on parametric records close with #3: #13's (whether a value argument narrows
below `token`) by the removal of value arguments, #19's (a shared parent for `record` and
`record_template`) by the removal of `record_template`, and #21's (a sibling in a parametric record
field typed by a parameter, `kind: meta.type_ref = T`) by the refusal of a parameter at a
field's value — the field is written `v?: T`. #20's head edge is reversed: no application is
IS-A its parametric record. Revision 39's other open remainders stand as that change log records them.
Open questions are recorded here and are NOT reflected as open text in the specification.

---

## 3. Accepted changes by target document

### 3.1 Part 1 — Text Data Format

1. **Header** — Revision 40.
2. **§2.2.1** — the example identities at `/2026/40/`.
3. **§3.2** — a generic type reaches a tag or a type-naming position through the name a
   declaration gives its application (`names => set<text>`).
4. **§9.1** — the schema limits it names lose "template materialisation depth", the limit
   being gone (§11.5).
5. **§10.2** — `TSON-SCHEMA`, `TSON-JSON` and `TSON-GUIDE` at `/2026/40/`, the Developer Guide
   being revised with the series.

Items 6–13 carry #6, a dependent field's type in place. Each names the rule before and after.

6. **§1.3** — the lexer-freeze paragraph's Revision 40 sentence takes the unquoted token
   with an adjacent `!` out of the data grammar; Revision 39's clause describes that form
   without naming it "a sibling reference".
7. **§2.3** — the sibling reference, one of the two scalar forms Revision 39 set beside the
   token, goes.
8. **§2.5** — the field-order exception is unchanged; its example `value?: type!` is marked
   as the schema grammar's.
9. **§2.8** — the sentence that a field name is no token where it is "a reference carrying
   arguments or a sibling reference" goes.
10. **§4.1** — a schemaless read kept a sibling reference "as its field name and the mark";
    the sentence goes.
11. **§7.2, §7.2.4, §7.2.5, §7.3** — `!` was the type prefix "or the suffix of a sibling
    reference", told apart by adjacency; it is the type prefix. The suffix goes from the
    token-position paragraph, the directive rule, the special-character table and the
    `special-char` comment, and the paragraph on `!`'s postfix role goes. The lexer is
    unchanged: `!` is the token it was.
12. **§7.4, §7.5** — `sibling-ref` and its comment go, and so do its adjacency row and the
    paragraph on the two `!` roles. `kind!` in a data value is a parse error by the separator
    rule (§2.4), and no rule names the mark.
13. **§7.7, §8.1** — the positions the identifier grammar holds at a parse error lose
    `sibling-ref` and "the token of a sibling reference"; the parse-error list loses "a
    sibling reference's `!` not followed by whitespace".

Items 14–19 take the argument list out of the data grammar. With items 6–13, both of
Revision 39's value forms, the argument list and the sibling form, are gone from it: a value
that names something is a token, a qualified name, and `<` and `>` have no role in a data
value, as in Revision 38. Each names the rule before and after.

14. **§1.3** — the lexer-freeze paragraph records Revision 40: the lexer is unchanged, and
    both of Revision 39's value forms leave the data grammar, so `<` and `>` in a data value
    are parse errors again.
15. **§1.5** — a Class 1 processor MUST read "a reference carrying an argument list as the
    structured value §4.1 describes"; the requirement goes.
16. **§2.3, §2.6** — `core-value` loses `reference`: a value that names something is a
    token, a qualified name, and the argument list is no data syntax. A map key "may be a
    reference" goes.
17. **§3.2, §4.1** — an application, which Revision 39 parsed where a value stands and a
    schemaless read kept, is a parse error, as after `!`. A Class 1 value is one of three
    things again, and a schemaless read reads a qualified name as the token it is.
18. **§7.2, §7.2.5, §7.3** — `<` and `>` had a grammar role in data values, the argument
    list; they rejoin the characters the schema grammar reserves, thirteen again, each a
    parse error in a data value. The token-position paragraph loses `<`'s adjacency. The
    lexer is unchanged: `<` and `>` are the tokens they were, for the schema grammar.
19. **§7.4, §7.5, §7.7, §8.1** — `reference`, `application`, `type-args` and `type-arg` go,
    and so does the adjacency row for `<`; the identifier grammar's positions lose "the head
    of a reference"; the parse-error list loses the argument-list cases and gains a special
    token the data grammar gives no role, `<` and `>` among them.

One sentence of the worked example follows the register's #1:

20. **§2.1** — the paragraph after the example said its type annotations "invoke the built-in
    vocabulary" and its unannotated tokens "resolve by the base rules of §4", under an
    `!!import`, where §4 and §5.1 confine both to a document with none. It says the tags name
    types of the imported namespace, `order.tn`'s closure supplying `uuid`, `date` and
    `number`, and each unannotated token is read by the type its position declares
    ([TSON-SCHEMA] §7.3); the base rules' reading is given for a document with no `!!import`.

Four sentences follow the extern (§3.2 items 114–116). No production and no token changes.

21. **§2.1, §2.2.1, §3.2, §3.3, §9.1** — a document's names came from "the one namespace its imports
    bind", without exception. §2.1 and §3.2 except the names inside a value at an extern
    position, which are that value's own schema's. §3.3 keeps its rule whole — a directive at
    a value position is a parse error, and a value cannot open a namespace — and says who
    does: where the names inside a value are another schema's, the schema that types the
    value's position says so and the data does not. §2.2.1 names an extern's `schema` among
    the references its rule covers. §9.1 counts a schema reached through an extern among the
    schemas that are untrusted input.

One rule follows the ruling on #2 (§3.2 items 117–118). No production and no token changes.

22. **§2.4, §7.1, §7.4, §7.7** — a quoted token at a position typed by a reference. §7.1
    said "a quoted token is never a reference" where §2.4 says form is consulted once and
    identity is text, and the two answered a reference-typed field oppositely. §2.4's rule
    stands whole: `orders.place` and `"orders.place"` are the same reference at a position
    typed by one, and §7.1 says when a reference is quoted, exactly when one of its segments
    holds a character outside the token profile. §7.4's `qualified-name-token` stays the
    spelling of a tag and an annotation name, and says a reference in a value is no
    production of the grammar: a token of either form, read by its position. §7.7's
    identifier profile governs the segments of a tag, an annotation name and a type's name;
    a segment of a reference in a value is a name of the family of what it enters.

One name follows the section's own. No rule changes.

23. **§8.2** — the token policy's type carries the policy's name. §8.2 names the two
    policies, the identifier policy and the token policy, "so that two implementations
    reporting them agree on what they are called", and said `policy.tn` states the second as
    `policy.script_policy`, the `token_policy` field of a `policy.processor`: one policy
    under two names. The type is `policy.token_policy`, beside `policy.identifier_policy`,
    and its shape is unchanged (§3.3).
24. **§8.2** — over a token the two loosest levels judge alike, and the text says so:
    Minimally Restrictive differs from Unrestricted by the identifier profile alone, and no
    token is read under one. `policy.restriction_level` keeps both, the levels being
    UTS #39's and one enumeration serving both policies. Nothing a processor does changes.
25. **§2.4, §2.6** — two clauses left from Revision 39's data argument list go: a trailing
    comma "in a reference's argument list", and a reference's key identity as "its parsed
    form, head and arguments". A reference in data is a token (items 14–19), compared as
    any token is. No rule changes.
26. **§2.8, §7.1, §9.1** — three more such clauses go: a field name's dispatch said "a
    reference carrying arguments is no token"; the quoting procedure called `box<T>` "text
    spelled like a reference", where it is text holding a character outside the profile;
    and the limits counted "a reference's arguments" toward the total. No rule changes.

### 3.2 Part 2 — Type System and Schema

1. **Header** — Revision 40, with a one-paragraph summary of this revision.
2. **§1.3** — a resolved-output consumer meets a parametric record as a `!meta.record` carrying
   `parameters`, skips it when building readers, and never meets one at a closed position;
   the record template's family-base exception and the `!meta.template` paragraph go.
3. **§1.4** — a reference may be, inside a parametric record, one of its parameters;
   `template` leaves the list of abbreviated constructor names.
4. **§1.6** — `flagged => <T> { entry: T  priority?: priority }`, applied as
   `flagged<status>`; the example imports `/2026/40/m/core.tn`.
5. **§2.1** — the example's import at `/2026/40/`.
6. **§3, §3.3, §3.3.2** — "a parameter's bound"; the lookup that precedes the namespace is
   a parametric record's parameter list.
7. **§3.3.1** — `meta.template` and the record-template vocabulary leave the constructor
   list, `meta.param` joins the records that are no constructor; the partial-application
   desugar target and the migration diagnostic for a parameterless head go.
8. **§3.3.4** — a reference a data document writes is a name, and an application there a
   parse error.
9. **§3.4.1** — Pass 2 checks every application's arguments against bounds and slots; a
   parametric record's body leaves rule 3's held values.
10. **§4.1** — `template` leaves the kernel listing; a parametric record is a record, kept
    from every position by the reference rule; a parametric record over an ordinary type
    goes.
11. **§4.2** — a constructor declares no parameters; the application bullet states
    substitution and construction, and that in a data document a reference is a name;
    `meta.param.bound` replaces `meta.template_param`; the record-template slots go.
12. **§4.3** — `meta.array<text>` is the finished-instance example; a parametric record is
    instantiated only through an application.
13. **§5.2** — value conformance loses its routed-parameter half; the record template's field
    slot, a parametric record as a sibling's pin and an application at a dependent field's reading go;
    a parametric record's field names are judged at its declaration; the family-base sentence
    becomes "a selector on a parametric record is each application's".
14. **§5.3** — a size bound is decimal-natural, a parameter there a resolver error; bound
    coherence is checked at load only; one-position tuples are declared, `meta.tuple1` gone;
    **Arguments** rewritten (every argument a type, a literal refused); **An application of a
    constructor constructs it** added; the sugar table's `C<args>` row becomes the
    construction and substitution rows.
15. **§5.5** — the open `!meta.alias` goes; the value-argument identity leaves the list of
    value comparisons.
16. **§5.6** — the end state has one body holding parameters, a parametric record; a
    declaration naming an application of a parametric record is the entry, one naming a
    construction the construction.
17. **§5.7, §5.8** — "Open modifiers" is replaced by one sentence, **Values in a parametric
    record**, standing in §5.8.
18. **§5.8** — an operand may be an application, never a parameter or an applied
    constructor; an application at an operand reaches its parametric record's written parents and
    never its head; `ok_of => ok<text>` IS-A `ok` goes.
19. **§5.9** — a parameterised subtraction declares a parametric record.
20. **§5.10** — rewritten: a parametric record; where a parameter stands; the body is a
    record body; forms over a parameter; application; a bound is written; no parametric
    record is a type a position names, a family declared with records; scoping; what a
    parameter does not carry; output.
21. **§5.12** — the example's import at `/2026/40/`; a parametric record is no referent; an
    application in a value body names a declaration of it.
22. **§6** — `@meta.application` is carried by a declared application and by no
    construction.
23. **§7.2** — a tag naming a parametric record is a validation error.
24. **§7.4, §7.5** — a parametric record's held body, a name type written as an
    application and the coherence rules' re-run at materialisation go.
25. **§8.1** — **Parametric records** replaces **Open entries**; `!meta.alias`'s target is
    a bare name; a record field's `type` may name a parameter, no label saying which; a
    parameter appears only inside a parametric record; `subtypes` never names a head; a
    resolved-form read checks a parametric record's parameters as stated and re-resolves no
    held text; the body-patterns table loses the instance-template and record-template rows
    and gains the construction and parametric record rows.
26. **§8.2** — the entry-shape example is `text_pair => pair<text, text>`.
27. **§8.3** — an alias body is a bare name; an application is the instantiation or the
    construction in place; the alias-template bullet goes; no `!meta.alias` carries
    `@meta.application`.
28. **§9** — the kernel row lists `meta.param` and drops the seven removed entries.
29. **§11.5** — the materialisation-depth row goes, and §3.4.1 rule 7 says what bounds
    closing instead: each parametric record of the closure at most once on a path, a repeat tied
    or refused (§5.10.1).
30. **§12.1** — `type-arg = type-expr`, a number or a quoted token being no argument;
    `value-literal` goes; `size-bound` is decimal-natural; the `type-param` comment reads "the
    written type is the parameter's bound"; `type-def` and `instance` note that the resolver
    admits a parameter list before a record body alone; the `type-params` note loses the
    family-base exception. **§12.2** — the dispatch comments follow.
31. **§10.3** — the canonical-identity examples at `/2026/40/`.
32. **§13.2** — `TSON-DATA`, `TSON-JSON` and `TSON-GUIDE` at `/2026/40/`; the six artifact
    rows carry the Revision 40 pins (§6).

Items 33–57 carry #5, inline types. Each names the rule before and after.

33. **Header** — the summary gains a sentence: a type slot holds a name or a type written in
    place, nothing is lifted or minted, and `@meta.synthetic` is gone.
34. **§1.3** — a resolved-output consumer builds the reader of a type written in place from
    the construction where it stands; before, every slot held a name it looked up.
35. **§1.6** — the application closed "to its expansion … once per distinct application";
    `flagged<status>` closes in place, a record with no name, and `tags`' resolved form shows
    the wrapper.
36. **§2.2.3** — "a synthetic entry's [identity] is its form" and "one synthetic form minted
    by two documents is one entry of a closure holding both" go: a type written in place is
    no entry and takes no part in collisions.
37. **§3.3.3** — `meta.synthetic` leaves the kernel's annotation list.
38. **§3.4.1** — Desugar lifted every nested form naming no parameter to a closed synthetic
    declaration entering Pass 1; it rewrites each form in place and lifts nothing. Pass 1's
    "synthetic declarations included" and step 4's freshness and import-re-derivation
    parenthesis go; rule 7 reads "closing an application".
39. **§4.1** — a value entry's payload "lifts and mints nothing" becomes a reference-typed
    slot there holding a name; a nested map's members "lift, mint" becomes "resolve", and
    "synthetics mint at the root" goes; the container constructors' type slots hold a name
    or a type written in place, not `meta.type_ref`.
40. **§4.2** — new paragraph **A type slot holds a name or a type written in place**: the
    kernel's `type_inline` and `type_expr`, the wrapper untagged and the construction tagged
    by its constructor, what `of` holds, and a type written in place as no entry: no name, no
    chain, IS-A nothing, named by no tag, a member of no kind. The bullet "every slot that
    names the type of a value is typed `meta.type_ref` and, in a closed entry, holds a bare
    name" becomes the slots typed by the choice, every other type-naming slot
    `meta.type_ref`, and where only a name may stand an application closes nowhere: one at a
    map's key, a reference's `of`, a bound or an alias's target is refused. The application
    bullet "every application denotes an entry: a closed one lifts to the entry it denotes"
    becomes "a closed application at a type slot is a type written in place"; an argument of
    a parametric record's application is a name or a parameter.
41. **§4.3** — a parametric record's application is cited to §5.10.
42. **§5.2** — a value modifier is admitted on an atom or an enum, a reference written in
    place included (`handler?: meta.ref<method> ~ …` stands), and refused on a container,
    tuple or choice whether named or written in place (before: named types only). A
    dependent field's sibling may hold a type written in place, and the field is read against
    it as against a named one. "A minted record is a member of nothing" becomes **a record
    written in place is a member of nothing**, the `vip => customer & box<int32>` sentence on
    a synthetic gone. **Inline refinements and bare records are prohibited** becomes **What
    may be written in place**: a container, tuple, choice or reference, or a refining
    constructor's construction; the line the resolver's, `of` being typed `meta.type`.
43. **§5.3** — the intro's "everything else lifts to a synthetic entry" becomes a
    construction standing in place; `[T; N]` and `[T; N..N]` are one type, not "the same
    entry". The map sugar's key admitted "a qualified name optionally carrying type
    arguments"; it is a name, an application there refused and declared. **Arguments**
    admitted any type expression, sugar nesting (`box<[integer]>`); an argument of a
    parametric record's application is a name or a parameter, and a sugar form, an applied
    constructor or an application of another parametric record there is refused and declared
    first. A constructor's argument slots include fields typed `meta.type_expr`. **Nested
    forms and synthetic entries** is replaced by **Nested forms are written in place**.
44. **§5.4** — an inline choice was "a *synthetic entry* … referenced by name"; it is a type
    written in place carrying `disjoint`. A variant is a `meta.type_expr`. New rule: a choice
    that is not disjoint names every variant, `( [text] | [int32] )` being a resolver error, a
    tag naming a variant by its name; a disjoint choice may hold variants written in place.
45. **§5.6** — the positional form keeps a bare token at a field typed by the type-slot
    choice as at an atom-typed one (before: atom-typed only, which `record_field.type` no
    longer is); the applied spellings are "one construction", not "one entry"; a value
    body's application is refused as no name, not for lacking an entry; the end state's type
    slot holds a bare token or a `{ of: … }` record, "after lifting" gone; two declarations
    of one construction are two entries, and neither is that construction written in place.
46. **§5.7, §5.8** — a type written in place narrows only to an equal one, and a name never
    to a construction or the reverse (`tags: text_list` over `text_list => [text]` is not
    restated as `tags: [text]`).
47. **§5.8** — an operand's application "mints no entry" becomes "is no entry"; an applied
    parent's edge names "the entry a declaration gives that application", and a parent
    application nothing declares gives no edge, the minted-record clause gone.
48. **§5.10** — new paragraph **Where an application closes**: declared, absorbed at an
    operand, or closed in place at a type slot, never looked up, `f: page<order>` and
    `f: page_order` two types of one shape. The remedy for a form as an argument,
    `page<[uuid; 1..]>`, is a declared `uuids`. An abstract parametric record applied in
    place has no member, members reaching a family through a declared application.
49. **§5.10.1** — **Template materialisation** (the self-reaching instantiation entry under
    an internal name) becomes **Recursion ties to a declaration**: a nested application equal
    to the one a declaration is closing names it, and a recursive application with no
    declaration to tie to is refused. Productivity judges types written in place; the
    regularity example reorders parameters, sugar being no argument.
50. **§5.12** — the table's "an application in a value body lifts nothing" reads "a
    reference-typed slot holds a name".
51. **§6** — `@meta.synthetic` goes from the key-position markers and the resolver-attached
    paragraph, which now describes `@meta.application` alone, carried by a declared
    application only; "what lets an imported schema's instantiations unify" goes.
52. **§7.2** — new paragraph **A type written in place is named by no tag**: a value at a
    position typed in place carries no tag naming it and admits none, and a diagnostic
    locates such a type by its field path. A type slot is read against the type-slot choice.
53. **§8.1** — `record_field.type` is a `meta.type_expr` value, and the positional form
    rests on the type-slot choice; a type written in place is written `{ of: !C { … } }`.
    `@meta.application` is carried by a declared application alone and is what the
    applied-parent edge consults. The resolved-form read no longer recomputes
    `@meta.synthetic` and checks each type written in place against §5.2. The body-patterns
    table: the use-site row is "written in place", the application row "declared
    application". Entries are declared entries alone.
54. **§8.2** — retitled **Instantiation and Identity** and rewritten, from 17 KB to 6 KB: no
    entry is lifted, minted or merged; a declared application; when two types are one: a name
    and a name naming one entry, past alias hops; two equal types written in place; and a
    name and a type written in place never; canonical applications over names; resolved forms compared as
    values, "up to renaming of internal entries" gone; diagnostics by field path; no consumer
    contract names a type written in place. Gone: trigger positions, the expansion and the
    self-reaching exception, the merge pass, internal names and freshness, the derived name
    written in data, and the import-merge unification of synthetics. **§8.3** — a declared
    instantiation is PRODUCT-kinded, not "its expansion's kind".
55. **§9, §10.1, §11.5, §12** — the kernel row lists `meta.type_inline` and
    `meta.type_expr` and drops `meta.synthetic`; the wait cycle holds the type slots, not "the
    arrays their declarations lift"; §10.1's recomputed list loses `@meta.synthetic`; §11.5's
    entries row loses "synthetic and instantiation entries"; §12.1's `map-key`,
    `element-type` and `type-arg` comments state the new rules and the `type-name` note on
    internal names goes; §12.2's generic-key dispatch notes the refusal. **§13.2** — the six
    artifact pins follow the re-stamped kernel (§6).
56. **§5.10, §5.10.1** — a parametric record holding an application of a recursive
    parametric record it is not on a cycle of (`wrap => <T> { n: node<T> }`) is a resolver
    error at its own declaration, applied or not: the held applications that would close in
    place are followed, the fields absorbed through an applied parent among them, and a
    recursive record reached on a cycle the declaration is not on means no application of it
    could close. The remedy is the closed type taken as a parameter, `wrap => <N> { n: N }`
    applied to `node_text`.
57. **§4.2, §5.2, §5.10** — a namespace type is declared and named, as a record and an enum
    are: a namespace value carries its type's name as its tag, and a type written in place has
    none, so one written in place is a resolver error at the declaration that wrote it.
    §5.10's example of a parameter passed to a constructor's slot reads
    `<K> { m: {K => text} }`.

Items 58–69 carry #6, a dependent field's type in place. Each names the rule before and after.

58. **Header** — the summary gains a sentence: a dependent field's type is a sum written in
    place, `kind!` its sugar at a record field's type alone, and `allow_sibling` and
    `meta.field_type_ref` are gone.
59. **§1.4** — a reference was "a type's name, a value's qualified name, an application, a
    sibling reference, or … a parameter"; the sibling reference goes.
60. **§4.2** — the reference listing loses `allow_sibling` and `field_type_ref`. The bullet
    **A value may be a sibling reference** goes, and the type-slot bullet's "a record field's
    `type` admits a sibling reference too". What `of` holds gains a dependent type,
    `!meta.dependent { on: kind }`, directly at a record field's `type` and at no other slot;
    a namespace type and a declared sum are never written in place, a dependent type is never
    declared, and each is a resolver error at the declaration. A type written in place's
    identity is structural "but for a dependent type's, which is its field's".
61. **§5.2** — **Dependent fields**: a field's type was "a sibling reference, `kind!`"; it
    is a dependent type, `dependent => final meta.sum & { on: text }` written
    `{ of: !meta.dependent { on: kind } }`, `kind!` its schema-grammar sugar, the sum in
    which a field of the record names the type as `meta.declared` is the one in which a tag
    does. **The position rule** (the permission kept at `meta.field_type_ref`, the list of
    refused slots, the facet refusing a sibling reference in data, the quoted name) becomes:
    the sugar parses only at a field's or a group member's type, `kind!?` included among the
    parse errors; the canonical form declared, or in place at another slot, is a resolver
    error at the declaration; the form is no data grammar; and a dependent type has no
    identity apart from the field holding it. The sibling's typing loses
    `meta.field_type_ref`; "and so is a sibling reference" leaves the pin-or-default rule; a
    sibling naming no type reads "a dependent type, an open record"; a restatement's body
    keeps "the dependent type", not `type: kind!`, beside the pin. Field names: "a sibling
    reference" becomes "a dependent type's `on`". Resolution: `type` is a `meta.type_expr`
    value, a dependent type among the types written in place. **What may be written in
    place** gains the dependent type at a record field's type and states that a declared sum
    is never written in place.
62. **§5.3** — the sugar table gains `kind!`, a record field's type: `!meta.dependent { on:
    kind }`, written in place at the field.
63. **§5.5** — the example of a refined reference instance was `meta.type_ref =>
    !meta.field_type_ref ^ { allow_sibling: false }`; it is `meta.identifier_ref =>
    !meta.type_ref ^ { of: unicode.identifier_type }`.
64. **§5.6** — the positional form keeps a bare token at a `meta.type_expr` slot.
65. **§5.7, §5.8** — `meta.reference_type`'s `allow_sibling` leaves the permission facets; a
    restated dependent field's body keeps "the dependent type", not `type: kind!`.
66. **§5.10, §5.11** — what a slot can say of a name it holds loses "which sibling's value
    names it", a field instead taking its type from a sibling's value; a group member was a
    `field-name`/`type-expr` pair whose type "may be a sibling reference", and is a
    `field-name`/`field-type` pair, its type a type expression or a dependent type.
67. **§7.4** — "a sibling reference is refused by the facet where `allow_sibling` is false"
    goes.
68. **§8.1, §8.2** — `record_field.type` is a `meta.type_expr` value, a dependent type
    among the types written in place. "A sibling" leaves what a field's type holds, and "a
    sibling reference where `allow_sibling` admits it" the positional-form paragraph.
    **§8.2** gains: a dependent type is the type of its field and of no other, so two records
    holding equal `{ of: !meta.dependent { on: kind } }` hold two types.
69. **§9, §12** — the kernel row's references lose `meta.field_type_ref`, and
    `meta.dependent` joins; the guidance's first rule loses "a slot that holds a sibling
    reference by `meta.field_type_ref`". §12's introduction: the reference production no
    longer carries the sibling reference, and `dependent-type` is the schema grammar's alone.
    **§12.1** — `sibling-ref` leaves the imported productions, `refined-def`,
    `supertype-ref`, `type-expr`, `map-key` and the comments of `field-type`, `element-type`
    and `type-arg`; `field-type = type-expr ["?"] / dependent-type`, `dependent-type =
    unquoted-token "!"`, and `group-member` takes a `field-type`. The `sibling-ref` note,
    under which `[ kind! ]`, `box< kind! >`, `( kind! | text )` and `x => kind!` parsed and
    the resolver refused them, becomes the `dependent-type` note, under which each is a parse
    error. **§12.2** — `name!` at a declaration's body and `name !` at a type expression are
    parse errors but as a field's or a member's whole type. **§12.3** — a row for the suffix
    `!`: adjacent to the token before it and followed by whitespace, `a!b`, `kind!}` and
    `kind!?` parse errors.

Item 70 states what a type written in place may not carry.

70. **§4.2, §5.10.1** — a record written in place takes no `parameters`, and `parameters`,
    or a `chain` on any type written in place, is a resolver error at the declaration. An
    abstract record written in place is empty, no schema being able to name it as a parent,
    so a required field typed by it is refused by the productivity rule, as one typed by
    `meta.ref<meta.alias>` is, and an optional one can only be missing; a declared abstract
    record stays open to its importers' members.

Items 71–76 carry #5's record written at a type slot. Each names the rule before and after.

71. **§5.2** — "bare records (`{ name: text }`) MUST be introduced via named declarations":
    a record body may be written at a type slot. New paragraph **A record written in place**:
    a record body at a field's type, a group member's, an array's element, a map's value, a
    tuple's element or a choice's variant is one form with the record an application closes
    to — structural, no name, no `chain`, no `parameters`, a member of nothing and named by
    no tag, two with equal fields one type. It holds what a declared body holds and is judged
    as one is; a selector makes it abstract, and so empty. It is not written where only a
    name stands, and composition, refinement and subtraction are declared, `at: point & { z:
    int32 }` a parse error. An enum, every other atom and a namespace type stay declared.
72. **§4.2** — what `of` holds includes a record, written at the slot or closed to by an
    application.
73. **§5.3** — the sugar table's `{ fields }` row is the construction in place at a type
    slot; **Nested forms are written in place** shows `at: { x: int32 }`.
74. **§5.10** — the record an application closes to is the record written in place with
    the same fields, one type with it. **Forms over a parameter** gains the record written in
    place over a parameter, a record body substituted with the parametric record holding it
    when that record is applied.
75. **§12.1, §12.2** — `type-expr` admitted `map-type` where a brace opens a type; it admits
    `brace-type = map-type / record-def`, and §12.2 states one brace dispatch for a
    declaration's body and a type slot, where a type slot's `{` was "map-type, `{` name `:` a
    parse error — bare records must be declared". `{` `{` and `{` `[` are parse errors, a
    map's key being a name; `&`, `^` or `-` after a field's type is a parse error. The
    `map-key` and `type-arg` comments name the brace forms.
76. **Header, §8.1** — the summary's list of what stays where it is written gains a record
    body, and §8.1's row for a type at a slot gains `at: { x: int32 }` among its examples.

Items 77–83 make a parameter a leaf at any slot of a type written in place inside a
parametric record, replaced when the record is applied.

77. **§5.10** — **Forms over a parameter**: a form is written in place whether or not it
    names a parameter, the parameter a name at its slot, nested to any depth, a container
    with a size or a `?`, a tuple, a choice and a record written in place included; each is
    substituted when the parametric record is applied and judged as the same type written
    with the argument in place. A size bound naming a parameter, and an application at a slot
    that holds only a name, are resolver errors at the declaration.
78. **§5.10, §5.3, §8.1** — a constructor's application over a parameter, `meta.array<T>`,
    `set<T>`, `meta.ref<T>`, is its construction in place at once, so `meta.array<T>` and
    `[T]` are one resolved form, and an application in resolved output applies a parametric
    record. **Where an application closes** and **In output** say so; §8.1's row for a type
    at a slot follows.
79. **§5.4, §5.10** — a choice over a parameter, `( T | int32 )`, loads at the parametric
    record, and each application is judged where it closes, its variants' distinctness and
    its disjointness alike: `pick<text>` loads, and `p => pick<int32>` is a resolver error at
    `p`. The parametric record's own choice states `disjoint: false`, a parameter having no
    class. New paragraph in §5.4, **A choice over a parameter is judged where it is
    applied**.
80. **§5.10** — **Where a parameter stands** names the slots of a type written in place, at
    any depth; the field that takes no value is one whose type names a parameter anywhere,
    the parametric record's or a record written in place inside it. **A bound is written** names
    a type written in place among the places a parameter is passed on. **Scoping**: a
    closed record holds no parameter but where it is written in place inside a parametric
    record; anywhere else a name that would resolve only as a parameter is an unresolved
    reference (§8.1).
81. **§4.2, §5.2, §5.3, §5.6** — §4.2's two statements of where a parameter or an
    application stands, §5.2's record written in place, §5.3's **Arguments** (a sugar form
    as an argument is refused whatever the head, `meta.array<[T]>` included, and an
    application as an argument of a constructor's application closes at its slot) and
    **Nested forms are written in place** (a size or `?` is legal over a parameter too), and
    §5.6's end state ("a binding record never holds a parameter") are restated.
82. **Header, §1.3, §8.1, §12.1** — the summary, the resolved-output consumers' paragraph,
    §8.1's **Parametric records** and **Reading parameter references**, and the `type-arg` comment
    follow.
83. **Kernel** — the documentation of `meta.param`, `meta.reference_type` and
    `meta.type_inline` states where a parameter stands as above; `meta-resolved.tn`'s
    conventions note follows. Every pin changes (§6).

Items 84–92 take the application out of the reference: a reference is a name, and an
application is the construction `meta.apply` written in place. Each names the rule before
and after.

84. **§4.2, §1.4** — "a reference is a name, an application or a parameter", and "a value
    may be an application", `geo.address<T>`, read as a reference value. A reference is a
    name or, inside a parametric record, a parameter.
85. **§4.1, §4.2, Kernel** — new constructor `apply => final meta.type & { head:
    meta.type_ref  arguments: [meta.type_ref; 1..] }`. It composes `meta.type` and no base
    kind: an application stands for a type that exists only once its parametric record is applied,
    so it has no kind and nothing reads a value against it. What `of` holds gains it, inside
    a parametric record only.
86. **§5.10, §5.3, §8.1** — a parametric record's application over a parameter is the
    construction written in place, `{ of: !meta.apply { head: box  arguments: [T] } }`, at a
    field's type, at a slot of a type written there or among `chain.parents`, and closes
    when the record is applied. The explicit spelling means what `box<T>` means and closes
    the same way. In resolved output an application stands only inside a parametric record,
    over one of its parameters, applying a parametric record; reading a resolved form checks
    it, as a dependent type's position is checked.
87. **§4.1, §5.8, Kernel** — `type_chain.parents` was `[meta.type_ref]`, holding `result<T>`
    with its arguments; it is `[meta.type_expr]`, a name or an application in place.
    `supertypes` and `subtypes` stay names.
88. **§6, §8.1, §8.2, Kernel** — `@meta.application:pair<text integer>`, a reference with
    arguments, is `@meta.application:{ head: pair  arguments: [text integer] }`, a value of
    `meta.apply`; `application => @meta.annotation meta.apply`. An applied parent's edge and
    a recursion tying to its declaration compare applications as values, head and
    arguments, never as text.
89. **§5.3, §5.6, §5.12, §12.1** — "in resolved output the application is the reference
    value [TSON-DATA] §7.4 reads" and the `type-ref` comment "[TSON-DATA] §7.4's production"
    are restated: the schema grammar's application is its own, sugar for `meta.apply`. A
    reference over a constructor holds a name, never an argument list.
90. **§5.2, Kernel** — `dependent => final meta.sum & { on: text }`: nothing composes or
    refines it, so the position rule is the one constructor's.
91. **§5.10, Kernel** — `meta.apply` is final for the same reason, so no constructor refining
    it can be written in place under the allow-list's "a constructor refining an admitted
    one".
92. **Header, §1.3, §13.2** — the summary and the kernel's inventory gain `meta.apply`;
    every pin changes (§6).

Items 93–96 follow the argument list out of the data grammar (§3.1 items 14–19).

93. **§4.2** — a value carrying an argument list is a parse error wherever a value stands,
    a construction's payload, a value entry's and a resolved form read as data included: the
    data grammar's ordinary error for a character it reserves, as for `kind!`, the grammar
    not knowing the form.
94. **§3.3.4, §7.2, §7.4** — an application in a data document, at a reference-typed
    position or where a tag would be, is a parse error ([TSON-DATA] §7.2.5).
95. **§12.1, §12.3, §5.2** — the `instance` comment: "an application inside it is the
    reference production of [TSON-DATA] §7.4, written unquoted"; an argument list in a
    payload is a parse error, and a type slot there writes an application as `{ of:
    !meta.apply { … } }`. The application's `<` adjacency, which cited [TSON-DATA] §7.5's
    row, is a row of §12.3's table, the argument list being this grammar's alone. §5.2's
    "the form is no data grammar" cites the separator rule ([TSON-DATA] §2.4).
96. **Header, Kernel** — the summary says a value carrying an argument list is a parse
    error, and the kernel's documentation of a reference names no argument list. Every pin
    changes (§6).

Items 97–100 make `meta.ref` the one reference constructor. Each names the rule before and
after.

97. **§4.2, Kernel** — Revision 39's `reference_type => meta.atom & { of: meta.type_ref
    allow_sibling?: boolean ~ true }` and its `ref`, applied over it, become one constructor,
    `ref => meta.atom & { of: meta.type_ref }`, and `type_ref => !meta.ref { of: meta.type }`.
    `meta.ref<T>` is the construction `!meta.ref { of: T }`, and whether a reference names
    types or values is read from `of`. The kernel's documentation of the two merges onto
    `meta.ref`. A schema naming `meta.reference_type` names an unresolved entry.
98. **§4.2, §9, §13** — every other mention: `meta.ref.of` is among the slots typed
    `meta.type_ref`, the example of an atom constructor and the kernel inventory name
    `meta.ref`, the wait cycle holds `meta.ref`, and §4.2's type-slot paragraph tags
    `meta.ref<method>` written in place `!meta.ref`.
99. **Header** — `meta.ref` is the one reference constructor, `meta.reference_type`
    gone.
100. **§13.2** — every pin changes (§6).
101. **§3.4.1** — rule 1 named the parts of a value a read waits on, a field's type, an
     element's, a nested tag's and an annotation's, and was silent on a value a family places by
     its pins (§5.2), whose member nothing in the value names. Rule 1 gains: a value read at a
     family's selectors whose pins no ready member states waits on each declaration not yet
     ready that may be that member, judged from what it writes: its written operands reach the
     family's base, and the nearest pin written along them does not differ from the value's.
102. **§5.10** — `x => !meta.apply { … }` is a resolver error, a declared application
     being written `x => H<args>`, whose resolved value is the record it closes to (§8.2).
103. **§4.1** — `meta.type_chain`'s `supertypes` and `subtypes` were arrays, and an array's
     order is part of its value, so one chain in two orders was two values. Each is a set,
     `meta.set_type<meta.type_ref>`; `supertypes` is still written sorted (§8.1).
104. **§13.2** — every pin changes (§6), for item 103 and for the artifacts' documentation
     (§3.3).
105. **§4.1, §5.7, §13.2** — `meta.namespace` was written `meta.map ^ { key_type:
     meta.identifier_ref  voidable?: = false }`, a refinement. It restates a field under a
     narrower type and pins another, which declares a constructor and narrows no value, so it
     is the composition `meta.map & { … }`, restating by §5.8. The entry's resolved form, its
     chain and every namespace type are unchanged; `meta.tn`'s pin changes (§13.2).
106. **§3.3.1, §5.3, §5.5, §5.7, §8.1, §12.1, §12.2** — `!C ^ { … }` over a constructor was no form, and
     a type a container constructor built was finished. `!C ^ { values }` now constructs `C`
     with a required field left absent, declaring an **incomplete entry**: no type and no
     value, written once field by field through `!X ^ { values }`, and completed by
     `!X { … }`, `!X value` or `X<A …>` into the plain construction `!C { … }`, which carries no
     chain. It is refused wherever a type is named, is checked whole where it is completed,
     and is not written in resolver output. A type `meta.array` or `meta.map` built is refined
     by `!T ^ { values }`, its facets taking §5.7's kinds, and the result IS-A `T`.
107. **Header, §1.6, §3.3.1, §3.3.3, §3.4, §4.1, §4.2, §5.1, §5.2, §5.3, §5.4, §5.6, §5.7, §6,
     §7.4, §7.5, §8.1, §8.3, §9, §13.2** — the kernel's
     `meta.set_type` and root `enum` were constructors refining `meta.array` and
     `meta.enum_type`, and a processor bound their instances as the constructor each refined.
     Each is now an incomplete entry (item 106): `meta.set_type => !meta.array ^ { voidable:
     false  ordered: false  unique_items: true }` and `enum => !meta.enum_type ^ { name_type:
     meta.identifier }`, so `set<text>` is `!meta.array { element_type: text  ordered: false
     unique_items: true }` and `!enum [A B]` is `!meta.enum_type { name_type: meta.identifier
     members: [A B] }`. Neither is a type, so a field whose value is an enum *type*, `a: enum`,
     is written `a: meta.enum_type` (declaring an enum, `status => !enum [OPEN DONE]`, and
     typing a field by it are unchanged), `meta.set_type & { … }` is `meta.array & { … }` restating the set's facets, and
     `!meta.ref { of: meta.set_type }` is `of: meta.array`. Both, and the root's `set`, leave
     `meta.tn`'s resolved form. A declared completion carries the new derived key annotation
     `@meta.completion:{ of: [X …] }`, the incomplete entries it closed, nearest first and past
     alias hops; an author writing it is a resolver error, and it is read as stated. The
     kernel's wait cycle loses `meta.set_type` and gains `meta.identifier_ref`, which `enum`'s
     `name_type` is read by where it is written, and holds 31 entries (§4.3). `meta.tn`'s pin
     and every pin above it change (§13.2). **Lost:** a field admitting only enums over
     identifiers, `a: meta.enum_type` admitting any name type; a reference admitting only
     sets, `of: meta.array` being wider and nothing narrower existing; a set's
     `voidable: false` as a pin, which held only through `meta.set_type`; and a resolved form
     listing every name its source offers, what a schema offers to be completed being its
     source's (§8.1, §10.1).
108. **Header, §1, §4.1, §4.2, §4.3, §5.1, §5.2, §5.5, §5.7, §5.8, §5.9, §5.10, §5.10.1, §5.11,
     §8.1, §8.2, §12.1, §12.2, §12.3** — record refinement goes. `T ^ { … }` refined a record, a constructor among
     them, and declared a refined record or a refined constructor beside `&`'s composition. The
     operator now decides the body: `^` follows `!name` and its body is values, and it never
     declares a record or a constructor; `&` follows a bare name and its body is field
     definitions, the one way to derive a record and so the one way to declare a constructor.
     `refined-def` leaves §12.1, so a bare `name ^ {`, `name<args> ^ {` and `<T> name<T> ^ {` at a
     type-def, or `^` after a name at a type slot, is a parse error for a record and a
     constructor alike, its diagnostic naming `name & { … }`; `!R ^ { … }` over a record is a
     resolver error naming `R & { … }`, a record stating no rule for a body of values (§4.3). The
     re-spellings: `employee => person ^ { age: adult }` is `person & { age: adult }`,
     `vip => person ^ {}` is `person & {}`, `x => box<text> ^ { … }` is `box<text> & { … }`, and
     `minlist => meta.array ^ { min_items?: = 1 }` is `!meta.array ^ { min_items: 1 }`, its
     construction left open (item 106), or `meta.array & { min_items?: = 1 }` for a new
     constructor; over a constructor with no required field, a type is constructed and refined
     (`t => !unicode.text_type {}`, then `!t ^ { max_length: 4 }`). A restated field in an `&` body
     follows the rules it followed in a refinement body, unchanged, worded as the rules for a
     restated field and standing in §5.8 (item 127). The removal clause's refinement-head
     exclusion goes with the head, and §8.1's table loses the refinement and constructor-refinement
     rows. **Lost:** `^` refused a new field and `&` does not, so `person & { agee: adult }` adds a
     field where `person ^ { agee: adult }` was refused. A modifier-only entry naming no inherited
     field is still refused. There is no replacement.
109. **Header, §1, §1.3, §3.3.1, §3.4.1, §4.1, §4.2, §4.3, §5, §5.1, §5.3, §5.4, §5.5, §5.6,
     §5.7, §5.10, §5.12, §6, §7.2, §7.4, §7.5, §8, §8.1, §8.2, §10.1, §11.4, §12.1, §12.3** —
     the text read end to end against items 106–108; no rule changes, each sentence being
     what those items imply and the first implementation runs. Where a sentence listed what
     the name after `!` or the head of an application resolves to, what an entry is, or what
     resolver output holds, it names the incomplete entry or sets it aside: §3.3.1's roles,
     §3.4.1's read of a declaration against its head, §4.1's entries and nested maps, §4.2's
     `of`, §4.3's two families and its table, which gains rows for open construction and
     completion, §5.1's forms, §5.3's arguments, §8's and §8.2's statement of what output
     holds, §10.1's reasons a resolved form is no source, and §12.1's and §12.3's comments
     and rows. The refusals §5.5 states once are stated where each position is defined: a
     bound and a parameter shadowing one (§5.10), a referent (§5.12), an annotation (§6) and
     a tag in data (§7.2). An incomplete entry is imported like any entry (§5.5), and a
     completion over a parameter is its construction in place (§5.10). `enum` and
     `meta.set_type` *write* the facets the text said they pinned (§7.4, §7.5); an enum's
     `name_type` alone stands among the facets fixed at construction, a record's being
     composition's (§5.2, §5.7); and §4.1 and §8.1 said the operator that minted an IS-A edge
     is unrecorded, which a record's edge being `&`'s and every other `^`'s makes untrue, a
     declared completion's `@meta.completion` being the one provenance output carries.

Three register entries are accepted as they propose. None changes a rule: each states what the series already holds and the first implementation
runs, a schema document's annotation value aside (§5).

110. **§11.1** (#7) — new paragraph **A free sibling gives the document's author the choice
     of a type**: a dependent field's free sibling typed `meta.type_ref` or `meta.type_expr`
     lets a document's author choose the type a value is read against, the kernel's
     vocabulary included and, for `meta.type_expr`, a type written in place; a reference
     narrows by constructor and never to one record's subtypes; a schema for untrusted input
     SHOULD pin the sibling, make it a selector or narrow the reference. §5.2 is unchanged.
111. **§5.12, §6** (#8) — "Where the rule stands" listed a value entry's field, a field's
     default or pin and a data document's field, and never an annotation's value, which §6
     validates against its type. It gains **The rule reaches an annotation's value**: a name
     at a reference-typed slot there resolves in the annotating document's namespace
     (§3.3.3) and is judged by the same walk, when a data document is read and when a schema
     document links. §6's `@T:value` bullet points at it.
112. **§5.5, §5.7** (#9) — `net.uuid_type.version` had no stated meaning. §5.5's list gains:
     it admits a UUID whose RFC 9562 version field equals it, any other version a validation
     error. §5.7 lists it among the settable-once facets, two versions admitting disjoint
     values, so `!v4 ^ { version: 7 }` over `v4 => !uuid ^ { version: 4 }` is refused.

One facet takes one name, on the spec author's ruling. The rule is unchanged.

113. **§5.5, §7.4, §13.2, Temporal** — the fraction-digit grid was one facet under two
     names: `numeric.decimal_type` called it `fraction_digits`, and `temporal.time_type`,
     `temporal.datetime_type` and `temporal.duration_type` called it `precision`, §5.5
     explaining it as "`fraction_digits` on the seconds count". Each is `fraction_digits`.
     §5.5's bullet, §7.4's list of facets typed `numeric.count` and its coherence paragraph
     name it so, and the three constructors declare it so. Its meaning, a whole number of
     10⁻ᴺ seconds under `fraction_digits: N`, and its cap of 9 on the temporal families, a
     larger value a schema-load coherence error, are unchanged. `temporal.tn`'s and
     `core.tn`'s pins change (§6).

A schema could address only the namespace it had. Revision 39 removed Revision 38's external
forms and its scope push and put nothing in their place, §7.8 saying that a schema meaning
to admit a type from a document it does not import "has no way to say so, by design". Two
schemas that both declare `order` could therefore be named by no one document: a
description of a service serving two versions of one schema named one of them. The spec
author's ruling is that the schema layer needs an external reference, in a form under which
the data still names no schema. It is the extern, in three items; no register entry carries
it.

114. **§5.13 (new), §1.4, §2.2.3, §3, §3.3, §3.5, §4.1, §5.12, §6, §8.1, §8.3, §9** — the
     form. `order_v1 => !meta.extern { schema: "https://example.com/order-1.tn"  type: order }`
     brings one entry of another schema into a document under a name the document chooses,
     and none of that schema's names enter the document's namespace. The kernel gains
     `meta.extern => meta.top & { schema: iri_reference  type: text }` beside `meta.alias` (§6).
     - **A hop.** An extern is another name for its target, across a schema boundary: its
       identity is the target's, and a reference's membership, a choice's distinct variants
       and its disjointness are judged of the target, by identity. Its entry is no type
       and carries no chain, as an alias's (§4.1, §8.1); resolver output writes it as
       written and each use by the extern's name.
     - **What it names.** A type a value can have, anywhere in the foreign schema's
       namespace. A parametric record, an incomplete entry, a constructor, a value entry, a
       schema map and an unknown name are each refused. An extern names a type and nothing
       else: a schema that wants another's value or constructor imports it.
     - **Where it stands, and sealed.** It stands where a type's name stands and at a
       reference over a constructor. Nothing is derived from it: `&`, `-`, `^`, an
       application head and a constructor head are refused, and so are a default or a pin
       at a field typed by it. No qualified name selects through it (§5.12): the boundary
       is absolute.
     - **`type` is `text`.** The name is judged in the foreign schema's namespace, so
       `meta.type_ref`, judged in the declaring document's, would state what is not so.
     §3.5 gains the consequence it left unsaid: two versions that declare a name in common
     cannot share an import closure, and a schema names types of both through externs, or
     the versions declare their names under maps that differ.
115. **§5.13, §3.3.1, §3.4.1, §10.1, §10.2, §11.2, §11.5** — an extern is a dependency,
     read with the imports. It is recognised by its written head, `!meta.extern`, an alias
     of the constructor at the head refused, so a document's externs are found before any
     name resolves, and each foreign schema joins the reference closure on an import's
     terms: the same library and policy, one digest per identity across imports and
     externs, resolved first. Three requirements refuse the declaring document: every
     foreign schema loads and holds an admitted target; a kernel the foreign closure holds
     is the declaring document's, so a schema of another revision is not externed; and the
     reference relation over imports and externs together is acyclic. §11.5's import
     closure counts a schema reached by either, and Revision 38's foreign-schemas limit
     stays retired: the schema fixes which foreign schemas a document reaches, and the data
     adds none.
116. **§7.9 (new), §7, §7.2, §7.8, §11.4** — the scope of a foreign value. A value at a
     position typed by an extern is read against the target under the foreign schema, and
     every name inside it — a tag, an annotation, a name at a reference-typed slot, a type
     named at a declared position — resolves in the foreign schema's namespace and no
     other. The tag and annotations in front of it are the enclosing scope's: at a choice
     the tag selects the variant before any scope is known, and `(order_v1 | order_v2)` is
     `order` against `order` in the variants' own schemas. A tag there is admitted as §7.2
     admits one at a position of the target, the names followed to the entries they reach
     and compared by identity: the extern, a name of the enclosing scope reaching the target
     or a subtype of it, or a variant where the target is a choice; a tag naming the
     position's extern says no more than the position does. A foreign value is part of its
     document, so limits, policies and name hygiene run across the boundary. Scopes nest as values do, and a
     name means one type throughout a scope; a document's scopes are where its schema says
     and never where its data does, so [TSON-DATA] §3.3's parse error stands. §7.8's
     closing paragraph, which gave the absence of a form as the design, states what a
     declared position admits and points here. §11.4 judges a foreign schema's names in
     that schema's namespace: a look-alike pair with one name in each scope is a pair in
     none.
117. **§1.3, §5.12, §12.1** — a reference reaches every name (#2). A qualified name was "one
     unquoted token", each segment a [TSON-DATA] §7.7 identifier, so a key of a namespace
     under a wider `key_type`, and a field of a record value under a wider `name_type`,
     could be written by no name: the text conceded the first in a sentence and was silent
     on the second. A qualified name is the text of one token of either form, split at each
     `.`, and each segment is a name of the family of what it enters — `meta.type_name` at
     the root and at a schema map, the namespace's `key_type`, the record's `name_type` —
     matched there as a document's own key or field name is, in the family's form. A name
     holding a segment outside the token profile is quoted whole, `"main.public api.ping"`,
     and `main."public api".ping` stays three tokens. "A quoted token is text, never a
     name" leaves §5.12 with the sentence on keys no segment could write. A body a schema
     document reads as data follows the one rule, so `!meta.array { element_type: "text" }`
     constructs `[text]`; a tag, an annotation name and every type position §12.1 reads name
     types through schema maps alone and stay unquoted. §1.3's bullet separates the two
     judgements.
118. **§4.2, §5.2** — no family a segment is matched against admits `.`. A namespace's
     `key_type`, and a record's `name_type`, whose profile holds U+002E in its Start,
     Continue or Medial set is a resolver error where it is named, a record taking the type
     from a supertype included, so item 117's split is the same whatever is entered and
     every key of a namespace value and every field of a record value can be written as a
     segment.
     `unicode.identifier_type` is unchanged: a dotted family types a map's key, an enum's
     `name_type` and any other value, and `net.scheme_name` keeps its `.`. §5.2's rule that
     a schema's own references to its fields match exactly stands and says what it does not
     cover: a segment selects a field of a record value, and is matched as a document's
     name is.
119. **§5.4, §7.2** — a tag at a choice reaches a variant's subtypes (#4). The tag "selecting
     the variant" was read as naming one exactly, so at `( text | shape )`, `shape` abstract,
     `!circle` was refused as no variant and `!shape` as abstract, and the choice held no
     record; a concrete variant refused the subtypes a field typed by it admits. The tag
     names a variant, or a type that IS-A exactly one variant, and the value is read at that
     variant as at a position typed by it, §7.2's subsumption with the variant as the
     position's type. A named variant is selected before one the type only IS-A, and a tag
     whose type IS-A two variants and names neither selects none, a validation error and
     not a refusal of the choice, a schema importing the choice's being free to declare
     such a type. `disjoint` is derived over the variants' classes as before, a subtype
     having its variant's class.
120. **§4.2, §4.3, §5.7** — a namespace type is refined. The source of `^` is a type built
     by `meta.array`, by `meta.map` or by a constructor that IS-A either, so the kernel's
     `meta.namespace => meta.map & { … }` builds types `^` refines. The rules are the fields',
     kept through composition, and a field the composing constructor adds is fixed at
     construction: `small => !api ^ { max_items: 3 }` is a namespace type IS-A `api`, and
     another `value_type` is refused as it is over a map. A record, a tuple, a choice and a
     namespace value stay finished to `^`.

121. **§5.7** — a facet fixed at construction that holds a type's name is restated as the
     type it names. Such a facet was "restated verbatim or left alone", which read an alias
     as another type at this one position: over `label => text`, `!tags ^ { element_type:
     label }` was refused. A collection's `element_type`, `key_type` and `value_type` and
     an enum's `name_type` are one by the entry a name resolves to and not by spelling, as
     §5.2 already states for a record's `name_type`, and the resolved value states the
     facet as the source wrote it.

122. **§4.2** — a type value written as data is held to the kernel's types alone.
     `meta.type_inline.of` is typed `meta.type`, and §4.2's list of what is written in place
     is the resolver's rule, so a data document typed by the kernel reads
     `!meta.array { element_type: { of: !meta.dependent { on: kind } } }`, as it reads
     crossed bounds or a chain no resolver writes. The text says so once: such a value
     declares nothing, nothing is read against it, and a document whose type values are to
     be types is loaded as a schema document. Typing `of` by a narrower kernel type would
     state one resolver rule of many, and bring back the slot types #6 removed.

123. **§4.2, §5.2, §5.8, §7.4, §8.1, §9** — `meta.field_name` is removed. It aliased `meta.identifier`
     and typed no slot of the kernel: `meta.field_map`'s key is `text`, judged by the
     record's name type, and `meta.record.name_type` carries no default. It named the name
     type of a record that states none, which an enum's default already writes as
     `meta.identifier`, and it did not cover its role, a record over a wider name type
     having field names outside it. A record that states no name type has
     `meta.identifier`'s, and a schema that typed a field by the role writes
     `meta.identifier` or an alias of its own. `meta.type_name` and `meta.param_name` stay,
     each typing a slot.

124. **§5.13** — a family across an extern's boundary. The boundary's tag is the enclosing
     scope's, so a subtype or a variant is tagged there only by a name that scope holds. §5.13
     says what follows: a family read through an extern names selectors, a value then placed
     by its members under the foreign schema; without them each tagged type is an extern of
     its own. The target stays any type a value can have, an abstract record and a choice
     among them: refusing those would refuse the selector-dispatched family, and a record
     that is not abstract has subtypes too.

125. **§5.4** — a tag names a variant by the entry it reaches. "A variant the tag names is
     selected before one its type only IS-A" did not say what naming is where a variant is
     an alias or an extern: it is the entry the tag reaches, past its alias chain and an
     extern, being the variant's, as every judgement through a hop is made (§5.13, §8.3).

126. **§5.4** — a variant that is a choice is disjoint. A nested choice has no class, so the
     choice holding it takes a tag, and the tag names the nested choice; the value is then
     read there with its one tag spent. Where the nested choice is disjoint that reads:
     `!narrow 5` at `( boolean | narrow )` over `narrow => ( int32 | text )`. Where it is
     not, `( text | either )` over `either => ( a | b )`, two records, a value of `a` or of
     `b` had no spelling in either encoding, and the schema loaded. The choice is a resolver
     error at the declaration, naming the variant, and is written with the variants listed,
     `( text | a | b )`. The rule is the one that refuses a variant written in place in a
     choice that is not disjoint, one level down: there no tag names the variant, here the
     tag is spent naming it. A choice holding a choice is never disjoint, so a choice nests
     one deep. The variant is judged at the entry it reaches, through an extern as through
     an alias, every judgement through an extern being made of its target (§5.13): over
     `either_x`, an extern of such a choice, `( text | either_x )` is refused the same, and
     is written with an extern of each variant. No value a document could write at the
     nested choice is lost (§5).
127. **§5.7, §5.8** — the rules for a restated field are stated in §5.8, with the
     composition body that writes one, and §5.7 states refinement alone. Revision 39's §5.7
     held both, from when `^` restated a record's fields; `^` has a body of values now and a
     record's field is restated only under `&`, so the section named for one operator
     carried the other's rules. Moved whole and unchanged: the three orders a field's facts
     move along, a restated type narrowing by IS-A, a default changing where a pin does not,
     narrowing to `void`, the elided type, a value in a parametric record, and body
     materialisation. §5.7 keeps what `^` refines and the facet kinds. Each citation of
     §5.7 for a restated field names §5.8 (§4.2, §4.3, §5.2, §5.5, §5.9, §5.11, §8.2;
     [TSON-JSON] §6.1.5). No rule changes, and the section numbers stand.
128. **§7.2** — IS-A is declared, and promises one thing. An edge is written, by a
     composition, a refinement or a constructor's chain, and never inferred from value
     sets; what it promises is subsumption read as a property, a value tagged `!S` admitted
     at every position typed `T` for `S` IS-A `T`, and it is a property of values after
     injection. The text gave the rule and nowhere the property. Stated, not changed.
129. **§8.2** — a choice's variants compare in order: `( int32 | text )` and
     `( text | int32 )` are two types, `variants` being an array. §8.2's comparison of a
     type written in place implied it. Stated, not changed.
130. **§5.10** — instantiation terminates because an argument is a name: no application is
     an argument of a parametric record's application, so the applications a schema reaches are
     drawn from its names, and a recursive one ties to a declaration. The text relied on
     it and gave no reason. Stated, not changed. §5.10.1's regularity rule stands with its
     reason corrected: it said an irregular recursion "could demand an unbounded family of
     distinct instantiations", which name-only arguments already exclude; what the rule buys
     is one verdict at the parametric record, every recursive application tying to the declaration
     closing it, where `dup<A, A>` inside `dup` would close for some arguments and not
     others, refused at whichever declaration applied it.
131. **Throughout, §5.10's title** — a record with parameters is a **parametric record**.
     Revision 39 called it a template, and this revision's text defined "an open record,
     a template" and wrote the first. Neither stands. A template carries what other
     languages mean by one — specialisation, value parameters, expansion — and none of
     it holds here. "Open" already names a record's extension, a schema map and a family
     across schemas, and an open record reads outside the series as a record admitting
     further fields. The term says what the record is and nothing more. An application
     still **closes** a parametric record, and a record without parameters is **closed**.
     §5.10 is **Parametric Records**, §8.1's paragraph has the same head, and the
     kernel's `@doc` text uses the term.
132. **§4.1, §5.5, §8.1** — an entry has no derived kind. §8.1 gave a consumer of resolver
     output a rule labelling each entry ATOM, PRODUCT, SUM or ALIAS, and §4.1 said every
     entry has exactly one. No rule of the series turns on the label, and its default was
     wrong: `meta.top`, `meta.type`, `meta.alias`, `meta.atom` and `meta.sum` came out
     PRODUCT, as did any constructor reaching no base kind. The derivation and the
     membership sentence are deleted. What stays is the base kinds themselves and §5.5's
     statement that a constructor's chain reaches at most one, which it now gives its reason
     for: two are a diamond over `meta.type`, refused by §5.8, and no rule of §5.5's own.
     What a consumer read from the label it reads from the entry: an alias or an extern by
     its tag, the base kind of a type by whether its constructor IS-A `meta.atom`,
     `meta.product` or `meta.sum`. "-kinded" is reworded wherever it stood (§5.2, §5.4, §5.5,
     §7.8, §8.1's table, §8.3), and the glossary's **kind** is a reference's admitted set
     alone.
133. **§5.2** — `discriminators` stands beside `extension: ABSTRACT`. §5.2 ruled on the
     source marks alone, `=?` implying ABSTRACT and `final` beside it refused, and said
     nothing of a record written as `!meta.record`: `{ … extension: FINAL  discriminators:
     [x] }` had no verdict, and could load as a record no value names. Such a record,
     declared or in place, is a resolver error unless its `extension` is `ABSTRACT`, a
     missing one being `OPEN` by the field's default. Deriving ABSTRACT over what is
     stated was the alternative, and made a stated field mean something else.
134. **§1, §4.1** — §1 says the data grammar has no argument list, `<` and `>` being parse
     errors in a data value; §4.1 names two kernel constructors composing `meta.top`
     directly, `meta.alias` and `meta.extern`, and its listing states both.
135. **§5.10, §8.3** — an alias of a parametric record is another name for it. `bx => box`
     is applied as `box` is, `bx<text>` being the application `box<text>` and one type with
     it, and `bx` is named bare where `box` is, at no position. The text had not said: §5.10
     listed every slot typed `meta.type_ref`, an alias's `target` among them, as naming no
     parametric record, while §8.3 followed an application's head through an alias.
     Refusing `bx => box` was the alternative, a rule for one target of an alias where a
     constructor and an incomplete entry have none.
136. **§5.2, §5.10, §12.1** — a definition mark stands before a record definition alone.
     §12.1 read the mark before any `type-def` and the resolver refused it on a definition
     that was no record, so before the kernel's form it was admitted and overrode a stated
     field: `abstract !meta.record { … extension: FINAL }` loaded as an abstract record, and
     `final` before a stated `extension: ABSTRACT` was refused as though FINAL were written.
     The mark is sugar for a record definition's `extension`, `!meta.record { … }` what it
     lowers to, and the grammar has it in that alternative alone, `[ definition-mark ws ]
     [type-params] structural-def`: before `!`, a name, a declared application, a sugar form
     or a choice it is a parse error at the mark, and a resolved form, whose entries are
     constructions, holds none. A declared application is substitution and nothing more, its
     `extension` its head's, so `f: x` and `f: box<text>` stay two types of one shape
     (§5.10); every application abstract is the parametric record marked, and one
     application marked alone is a record composing it, `x => abstract box<text> & {}`, as a
     value differing for one application is a restatement where it is declared. Admitting
     the mark before `!meta.record` where `extension` is unwritten or agrees, and refusing a
     contradiction, was the alternative: two cases where the grammar needs none.
137. **§5.8** — a body entry naming a field absorbed from an application at an operand
     restates it, under §5.8's rules, whether or not a declaration names the application:
     `dog => pet_of<dog_type> & { pet: cat_type }` over `pet_of => <T> pet & { pet: T }` is a
     resolver error, as composing a record holding `pet: dog_type` with `{ pet: cat_type }` is,
     and `pet: puppy_type`, a subtype of `dog_type`, narrows. In a parametric record the
     restatement is judged with each application's arguments wherever the application closes
     or is absorbed, so `wrap => <T> box<T> & { v: int32 }` admits `wrap<int32>` and refuses
     `wrap<text>`. The text said an operand is absorbed by value and that a body entry naming
     an inherited field restates it, and not that an absorbed field is an inherited one, so
     the verdict could be read as turning on whether a declaration names the application, the
     one place a chain edge reaches a record holding the field. §5.9 rule 4's
     tightening entries are judged on the same terms, a subtraction's chain being empty.
     Reading the body entry as a new field that replaces the absorbed one was the alternative,
     and made the verdict on one declaration turn on whether another exists.
138. **§5.8, §5.10** — an application at an operand reaches the entry a declaration gives the
     application itself, where one exists, beside its head's written parents' edges:
     `dog => pet_of<dog_type> & { kind?: = "dog" }` is IS-A `dog_pet` where `dog_pet =>
     pet_of<dog_type>` is declared. §8.1 and §8.2 stated the edge and the running behaviour
     gives it, while §5.8 and §5.10 listed only the head's parents. Stated, not changed.
139. **§12.1** — `construction-def`'s first alternative wrote the body after two or more
     parents as `[ws record-def]`, so it derived `a & b { … }` and not `a & b & { … }`, the
     form §5.8 writes throughout and the one that parses. It is `[ws "&" ws record-def]`.
     The defect is Revision 39's. No rule changes.
140. **§1.6, §8.1, §8.3, §12.1** — wording. §1.6's data document is titled for this
     revision; §8.1's "alias-kind entries" is "an alias", an entry having no derived kind
     (item 132), and the kernel's resolved fixture's note follows; a verb in §8.3 and an
     article in §12.1's `type-arg` comment are corrected. No rule changes.

### 3.3 Companion artifacts

The revised artifacts carry the baseline of §1; §4.3 and §4.4 list every entry removed, added,
renamed and changed against Revision 39, each with the item that carries it. Every artifact's
documentation is rewritten for a reader looking at one entry who has not read this text: what
the entry is, how it is written in the basic case, the facts about its values that its
declaration does not show, and the section to read further. A rule this text states in full
is cited and no longer restated, which is what the six entries whose documentation had
drifted from it restated (`meta.top`, `meta.sum`, `meta.type_chain`, `meta.apply`,
`meta.annotation`, `meta.record`). `void`, `integer`, `text` and `regex` gain documentation.
§6 lists the pins.

### 3.4 Part 3 — JSON Encoding

[TSON-JSON] is edited directly. Beside the items below, §1.6's item 16 says a `meta.ref`'s
instances are kinds and references naming values alike, §5's table gives a kind's string as
a member's type name with no application, and the example imports at `/2026/40/`:

1. **Header and status** — Revision 40, the fifth published revision of Part 3, written
   against Revision 40 of [TSON-DATA] and [TSON-SCHEMA].
2. **§1.6** — the item list names items 23 and 24 as Revision 40's; item 16's sentence on an
   application in data moves to the new **item 23**, *a parametric record is a record with parameters, and a
   reference in data is a name*, which names the sections consuming it.
3. **§3.3** — a `$type` naming a parametric record is a validation error.
4. **§3.4** — a root type supplied out of band naming a parametric record is refused on the same
   terms.
5. **§11.2** — `TSON-DATA`, `TSON-SCHEMA` and `TSON-GUIDE` at `/2026/40/`.

6. **§3.3** (#5) — "an entry a resolver *minted* for an application no declaration names
   also has a name … It is not refused" becomes: a type written in place has no name, no
   `$type` names it, and a value at a position it types admits none.

7. **§1.6** (#6) — item 16 loses "with `allow_sibling` pinned false". Item 21: the kernel's
   `allow_sibling` sentence and its consumption by §5.6, "where a sibling reference is a
   string holding the text encoding's `kind!`", become: the field's type is a sum written in
   place and no reference, so no value of this encoding spells it as a string. §6.1.6's
   consumption, the member-order exception, stands.
8. **§5.6** (#6) — a sibling reference was a string holding the text encoding's `kind!`,
   admitted at a record field's type and refused elsewhere by the facet; the string form is
   gone, and a reference naming values loses "and no sibling reference".
9. **§1.6, §5.6** — item 23 and §5.6: a reference's string is a qualified name, so
   `"set<text>"`, like `"kind!"`, is a string outside the kind's grammar and a resolver
   error, as any string outside an atom's grammar is. The text encoding refuses the unquoted
   form earlier, as a parse error, and its quoted token as JSON does.
10. **§1.6, §5.6** — item 16 and §5.6 name `meta.ref`, the one reference constructor,
    where they named `meta.reference_type` (§3.2 items 97–100).
11. **§1.6** (§3.2 item 107) — item 13's set is described by its facets, `ordered: false` and
    `unique_items: true`, `set<T>` writing no bound of its own, where it named
    `meta.set_type`; item 23's construction example is `meta.array<text>`.
12. **§6.3** — the one-position tuple was "the kernel's `meta.tuple1<T>` and
    `meta.voidable_tuple1<T>`", which §3.2 item 14 removed; it is a one-position tuple as
    declared, `!meta.tuple [{ element_type: text }]`. The encoding is unchanged.
13. **§1.6, §3.3, §6.4, §8.2, §8.6 (new), §9.2** (§3.2 items 114–116) — a foreign value in JSON. Text
    keeps the boundary's tag and the body apart; one JSON object carries both, so §8.6 says
    which is which: the `$type` leading the object at an extern position, or its wrapper,
    is the enclosing scope's and names the extern, and every other name in the value is the
    foreign schema's. An `$import` inside a foreign value is the resolver error it is at
    every position but the document's top. Where a choice or a declared position selects an
    extern, the selecting `$type` is the boundary's and its wrapper's `$value` the foreign
    value; a position whose extern's target is a map is a map-typed position. §6.4's key
    form and §8.2's class follow an extern to its target. §3.3's rule for where `$type`
    resolves, §9.2's encoder obligation, §1.6's item 24 and §8's opening, which names §8.6,
    follow.
14. **§5.6** (§3.2 item 117) — a reference naming values read its string as "segments between
    dots, each an identifier"; each is a name of the family of what it enters. JSON has one
    string form, so nothing else moves.
15. **§3.3, §8.1** (§3.2 item 119) — the `$type` at a choice position names a variant, or a
    type that IS-A exactly one variant.
16. **§3.3, §8.6** — one value, one type name. §3.3 read a wrapper's `$value` "at the type
    `$type` selects", so an object there could lead with a `$type` of its own: one value
    with two type names, which text's one tag cannot write. `$value` is the annotated value
    itself and no annotation object; a subtype across an extern's boundary is named as it is
    in text, by a name of the enclosing scope ([TSON-SCHEMA] §5.13). A second tag in text,
    `!order_v1 !rush { … }`, was the alternative, and gives one value two spellings in both
    encodings.
17. **§5.7, §8.3.1** — the escape names the variant. §8.3.1 has an encoder wrap a map
    holding an annotation member's name as a key, `{ "$type": …, "$value": { … } }`, and
    §5.7's arbitrary-JSON declaration wrote its map variant in place, which has no name
    for the `$type` to hold: such an object had no encoding at a `json` position. The
    declaration names the variant, `json_object => {text => json?}`; §8.3.1 says a variant
    written in place cannot carry such a key and the encoder refuses it; and §5.7's "every
    JSON value" excepts the object holding one, written wrapped, and read as an annotation
    object where it leads with one. A wrapper with no `$type`, `{ "$value": { … } }`, read
    by class, was the alternative: a wire form for a case a name already covers.
18. **§3.3** — sorting does not always put the annotation members first. §3.3 argued that
    code-point sorting produces the leading order, `$` preceding every character an
    identifier can begin with. A record's name type may admit `$ref`, `$id` and `$defs`
    (§1.6 item 15), which sort ahead of `$type`, so sorted output of a tagged value of such
    a record is refused as a misplaced `$type`. The sentence is held to records of
    `meta.identifier` names and says what an encoder does for the others. Reading the
    annotation members anywhere in an object's leading run of `$`-named members is not
    taken in this revision: at a choice or a declared position no record bounds the run.

Part 3 defines no type-system rule, so each is a spelling of a rule §3.2 owns.

---

## 4. The net change from Revision 39

This section states Revision 40 as a change to Revision 39, by what an implementer touches,
and nothing in it depends on the order in which §3's items arrived. Each line cites the items
that carry it, a bare "item" being §3.2's and §3.1's and §3.4's named so, and the spec
sections; §3 holds the detail and §5 the rulings. §4.10 says what a Revision 39 document
rewrites, and §4.11 what is lost.

### 4.1 Data grammar (Part 1)

- **The lexer is unchanged** (§3.1 items 6, 14; [TSON-DATA] §1.3).
- **No argument list in a data value.** `core-value` loses `reference`; `reference`,
  `application`, `type-args` and `type-arg` go; `<` and `>` rejoin the characters reserved
  for the schema grammar, thirteen again, each a parse error wherever a value stands, a
  schema document's payload and a resolved form read as data included (§3.1 items 3,
  14–19, 25; items 93–96, 134; [TSON-DATA] §2.3, §2.4, §2.6, §3.2, §4.1, §7.2.5, §7.4, §8.1;
  [TSON-SCHEMA] §1, §4.2).
- **No sibling form.** `kind!` in a data value is a parse error by the separator rule, and `!`
  is the type prefix alone (§3.1 items 7–13; [TSON-DATA] §2.4, §7.2, §7.4, §7.5, §7.7).
- **A Class 1 processor reads no structured reference**: the requirement goes, and a
  schemaless read reads a qualified name as the token it is (§3.1 items 15, 17;
  [TSON-DATA] §1.5, §4.1).
- **A quoted token at a position typed by a reference is the reference its text spells**,
  quoted exactly when a segment holds a character outside the token profile (§3.1 item 22;
  [TSON-DATA] §2.4, §7.1, §7.4, §7.7).
- **The names inside a value at an extern position are its own schema's**, and the schema
  that types the position, never the data, says so (§3.1 item 21; [TSON-DATA] §2.1, §2.2.1,
  §3.2, §3.3, §9.1).
- §2.1's worked example is read under its import (§3.1 item 20). The token policy's type is
  `policy.token_policy`, and the two loosest restriction levels judge a token alike (§3.1
  items 23–24; [TSON-DATA] §8.2). Identities and references move to `/2026/40/`, and the
  materialisation-depth limit leaves §9.1 (§3.1 items 1, 2, 4, 5).

### 4.2 Schema grammar ([TSON-SCHEMA] §12)

- **`refined-def` goes.** `^` follows `!name` alone; a bare `name ^ {`, `name<args> ^ {` or
  `^` after a name at a type slot is a parse error naming `name & { … }` (item 108;
  §12.1–§12.3).
- **The sibling form is `dependent-type` alone**: `field-type = type-expr ["?"] /
  dependent-type`, `dependent-type = unquoted-token "!"`, and `group-member` takes a
  `field-type`; `sibling-ref` leaves `supertype-ref`, `type-expr` and `map-key`, so
  `[ kind! ]`, `box< kind! >`, `( kind! | text )` and `x => kind!` are parse errors; §12.3
  gains the suffix `!` row (items 59, 61–62, 66, 69).
- **A brace at a type slot opens `brace-type = map-type / record-def`**: a record body may
  stand at a type slot; `{` `{` and `{` `[` are parse errors, and so is `&`, `^` or `-` after
  a field's type (items 71, 75–76).
- **An argument is a type expression**: `type-arg = type-expr`, `value-literal` goes, a
  number or a quoted token being no argument; `size-bound` is decimal-natural; `map-key =
  type-ref` (items 14, 30, 43, 55).
- **The application is this grammar's own**, sugar for `meta.apply`; its `<` adjacency is a
  row of §12.3 (items 89, 95). `type-params` parses before any body, the resolver admitting
  it before a record body alone (item 30). `!C ^ { values }` over a constructor is a form
  (item 106). The comments and §12.2's dispatch follow (items 55, 69, 109).
- **A definition mark stands before a record definition alone**: `type-def`'s `[
  definition-mark ws ] [type-params] structural-def`, `schema-map-entry` writing none of its
  own; before `!`, a type expression or a declared application it is a parse error (item
  136).
- **`construction-def` writes `&` before its body after two or more parents**, `a & b &
  { … }`, where the production derived `a & b { … }`; the form that parses is unchanged
  (item 139).

### 4.3 The kernel (`meta.tn`)

| | Revision 39 | Revision 40 |
|---|---|---|
| Entries in `meta.tn` | 87: 6 maps, 68 members, 13 root names | 83: 6 maps, 64 members, 13 root names |
| Entries its resolved form writes | 87, and 7 synthetic entries | 80, and no synthetic entry: `enum`, `set` and `meta.set_type` are not written |
| The wait cycle around `meta.record` | 25 entries | 31 entries |
| The cycle's closure | 66 entries | 66 entries |

The counts are of the declarations in `meta.tn` at each revision, a schema map and the root's
names each one entry, as §6 counts them; the cycle and its closure are measured over
`meta-resolved.tn` under [TSON-SCHEMA] §3.4.1.

- **Removed (11):** `meta.template`, `meta.template_param`, `meta.record_template`,
  `meta.record_template_field`, `meta.record_template_field_map` (#3, items 7, 10, 11, 28);
  `meta.tuple1`, `meta.voidable_tuple1` (item 14); `meta.synthetic` (items 37, 55);
  `meta.field_type_ref` and `allow_sibling` (items 58, 60, 69); `meta.reference_type`
  (items 97–99); `meta.field_name` (item 123).
- **Added (7):** `meta.param` (#3); `meta.type_inline`, `meta.type_expr` (items 40, 55);
  `meta.dependent`, final (items 58, 61, 90); `meta.apply`, final (items 85, 91–92);
  `meta.completion` (item 107); `meta.extern` (item 114).
- **Changed (14):** `meta.record` gains `parameters` (#3); `meta.record_field.type` is a
  `meta.type_expr` (items 53, 68); `meta.array.element_type`, `meta.map.value_type`,
  `meta.tuple_element.element_type` and `meta.choice.variants` are typed by `meta.type_expr`
  (items 39, 40, 44); `meta.ref` is the one reference constructor, `meta.atom & { of:
  meta.type_ref }`, and `meta.type_ref` is `!meta.ref { of: meta.type }` (items 97–98);
  `meta.application` is `@meta.annotation meta.apply` (item 88); `meta.type_chain.parents` is
  `[meta.type_expr]` and `supertypes` and `subtypes` are sets (items 87, 103);
  `meta.namespace` is `meta.map & { … }` (item 105); `meta.set_type` and `enum` are
  incomplete entries and `set` an alias of `meta.set_type` (item 107).
- **Documentation:** every entry's is rewritten (§3.3; items 83, 96, 104).

### 4.4 The other bundled schemas

- `temporal.tn`: `temporal.time_type`, `temporal.datetime_type` and
  `temporal.duration_type` declare `fraction_digits` in place of `precision` (item 113).
- `policy.tn`: `policy.script_policy` is `policy.token_policy` (§3.1 item 23), and
  `policy.limits.max_materialisation_depth` is removed (§1; item 29).
- `numeric.tn`: no declaration changes; its two enums and its set are completions of
  incomplete entries, which `numeric-resolved.tn` writes as constructions carrying
  `@meta.completion` (item 107).
- `net.tn` and `core.tn`: identities and documentation. Every artifact moves to `/2026/40/`
  and every pin changes (§3.3; §6).

### 4.5 Type-system rules ([TSON-SCHEMA] §4, §5)

**Parametric records** (#3; §5.10, §5.10.1).
- A parametric record is a `meta.record` with `parameters`, and nothing else takes
  parameters: its body is a record body, so there is no parametric alias, no partial
  application and no parametric record over a container, choice, tuple, value or bare
  reference, and no value argument or open `!meta.alias` (§1; items 7, 10, 15, 19, 20, 102;
  §3.3.1, §4.1, §5.5). A record with parameters is called a **parametric record** throughout,
  the text and the kernel's documentation alike (item 131).
- A parameter is a type, standing at a field's type, at any slot of a type written in place
  there, or as an argument of an application at either or in a parent; anywhere else it is a
  resolver error naming the slot. A field whose type names a parameter takes no value and is
  no selector (items 3, 6, 13, 77, 80–82; §1.4, §3.3.2, §5.2).
- A bound is written and never inherited, names a type and never a constructor or an
  incomplete entry, and a parameter passed to a bounded one is written with a bound that IS-A
  it (§1; items 9, 11, 14; §3.4.1, §4.2).
- No parametric record is a type a position names, no application is IS-A its head, `final`
  on one is refused, and a family is an abstract base, a parametric record composing it and
  members composing a declared application (items 2, 12, 17, 18, 21, 27; §1.3, §4.3, §5.8,
  §5.12, §8.3). An alias of one is another name for it, applied as it is (item 135).
- Forms over a parameter are written in place to any depth, a constructor's application over
  one being its construction in place; a choice over a parameter is judged where each
  application closes (items 77–79, 81; §5.3, §5.4).
- A recursive application ties to the declaration closing it and is refused where none does;
  a parametric record that could never be closed is refused at its declaration; regularity
  stands with its reason corrected, and instantiation terminates because an argument is a
  name (items 49, 56, 130; §5.10.1).

**Applications** (§5.3, §5.10, §8.2).
- `C<args>` over a constructor constructs it over its argument slots, not following an
  argument through an alias; over an incomplete entry it completes it (items 7, 14, 16, 24,
  43; §5.5).
- `H<args>` over a parametric record is `meta.apply` written in place: declared, it is the
  substituted record under that name; at an operand it is absorbed; at a type slot it closes
  in place, never looked up; over a parameter it stays `meta.apply` until the holder is
  applied, at a field's type, a slot inside one or `chain.parents` (items 46–48, 78, 85–87;
  §4.1, §5.8).
- An argument is a name or a parameter: a sugar form, an applied constructor or an
  application as an argument is refused and declared first (items 40, 43, 81; §4.2).
  `x => !meta.apply { … }` is refused (item 102).
- `@meta.application` is a `meta.apply` value on a declared application's key alone, and
  applications compare as values, head and arguments (items 22, 51, 88; §6, §8.2).

**Inline types** (#5; §4.2, §5.2, §5.3, §8.2).
- A type slot holds a name or a type written in place; nothing is lifted, minted or merged;
  `@meta.synthetic`, internal names and comparison up to renaming are gone (items 33–39, 41,
  43, 50, 54, 55).
- A type written in place has no name, no chain and no `parameters`, IS-A nothing, is named
  by no tag and is a member of nothing; an abstract record written in place is empty (items
  40, 42, 52, 70).
- A record body may be written at a type slot, one type with the record an application
  closes to; composition, refinement and subtraction stay declared, and so do an enum, every
  other atom, a namespace type and a declared sum (items 57, 60, 71–74).
- A name and a type written in place are never one type; two equal types written in place
  are one; a type written in place narrows only to an equal one (items 45, 46, 54).
- A choice that is not disjoint names every variant (item 44; §5.4). A value modifier is
  admitted on an atom or an enum, a reference written in place included, and refused on a
  container, tuple or choice, named or written in place (item 42).

**Dependent fields** (#6; §5.2).
- A dependent field's type is a sum written in place, `{ of: !meta.dependent { on: kind }
  }`, whose sugar `kind!` stands at a record field's or a group member's type alone;
  declared, or at another slot, it is a resolver error; its identity is its field's; `on` is
  `text` (items 58–69, 90; §5.11, §8.1, §8.2).
- Its sibling is typed `meta.type_ref` or `meta.type_expr`, and a free one hands the choice
  of type to the document's author, which §11.1 says (items 42, 61, 110).

**References and qualified names** (§4.2, §5.12).
- A reference is a name or, inside a parametric record, a parameter; `meta.ref` is the one
  reference constructor, and `of` says whether it names types or values (items 84, 89,
  97–98; §1.4).
- A qualified name is the text of one token of either form, split at `.`, each segment a name
  of the family of what it enters; a namespace's `key_type` or a record's `name_type`
  admitting `.` is a resolver error (items 117–118; §1.3, §5.2).
- A name in an annotation's value is judged, in the annotating document's namespace (item
  111; §6). A record stating no name type has `meta.identifier`'s (item 123; §5.2).

**Open construction and record derivation** (§5.5, §5.7, §5.8).
- `!C ^ { values }` over a constructor declares an incomplete entry: no type and no value,
  written field by field through `!X ^ { values }`, completed by `!X { … }`, `!X value` or
  `X<A …>`, refused wherever a type is named, imported like any entry, and not written in
  resolver output; a declared completion carries `@meta.completion` (items 106, 109).
- The kernel's `meta.set_type` and `enum` are incomplete entries, so `enum` is no type and a
  slot holding an enum type is `meta.enum_type` (item 107; §7.4, §7.5).
- `^` follows `!name` and its body is values; `&` is the one way to derive a record, and so
  to declare a constructor; `!R ^ { … }` over a record is a resolver error; a restated
  field's rules stand in §5.8 (items 105, 108, 127).
- `!T ^ { values }` refines a type built by `meta.array`, `meta.map` or a constructor that
  IS-A either, namespace types included, and a facet holding a type's name is restated by
  the entry it reaches (items 106, 120–121).

**Choices and tags** (§5.4, §7.2).
- A tag at a choice names a variant, or a type that IS-A exactly one, by the entry it reaches;
  a tag IS-A two variants and naming neither is a validation error (items 119, 125).
- A choice listed as a variant is disjoint, so a choice nests one deep (item 126).
- Variants compare in order (item 129). IS-A is declared, and its promise is subsumption
  (item 128).

**Externs** (§5.13, §7.9).
- `!meta.extern { schema: …  type: … }` names one entry of another schema under a name of the
  declaring document: a hop judged by its target's identity, naming a type a value can have,
  sealed against derivation, defaults, pins and qualified names (items 114, 124).
- A foreign schema is a dependency read with the imports, on the same library, policy and
  kernel, the reference relation acyclic (item 115; §3.3.1, §3.4.1, §10.1, §10.2, §11.2).
- A value at an extern position is read under its own schema, the tag in front of it the
  enclosing scope's (items 116, 124; §7.8). §3.5 states what two versions sharing a name do
  (item 114).

**Other rules.**
- No entry has a derived kind; a constructor's chain reaches at most one base kind (item
  132; §4.1, §5.5, §8.1).
- `discriminators` stands beside `extension: ABSTRACT` in the kernel's form (item 133;
  §5.2).
- A value placed by its pins waits on each declaration that may be its member (item 101;
  §3.4.1).
- A type value written as data is held to its shape alone (item 122; §4.2).
- `net.uuid_type.version` means RFC 9562's version field and is settable once (item 112;
  §5.5, §5.7).
- The fraction-digit grid is `fraction_digits` on every family that has one (item 113;
  §5.5, §7.4).
- `meta.type_chain`'s `supertypes` and `subtypes` are sets (item 103; §4.1).

### 4.6 Validation, reading and resolver output ([TSON-SCHEMA] §1.3, §7, §8)

- A declared application is the substituted record under its name, the entry-shape example
  `text_pair => pair<text, text>` (items 26, 54; §8.2).
- In a data document a reference is a name, an argument list a parse error, and a tag naming
  a parametric record a validation error (items 8, 23, 94; §3.3.4, §7.2).
- A value at a position typed in place carries no tag naming the type, and a diagnostic
  locates such a type by its field path (item 52; §7.2).
- Resolver output writes a parametric record as `!meta.record { parameters: … }`, a type
  written in place as `{ of: !C { … } }`, `meta.apply` only inside a parametric record over
  one of its parameters, no synthetic entry, no incomplete entry, and no kind label;
  resolved forms compare as values (items 2, 25, 34, 53, 54, 86, 107, 132; §8.1–§8.3).
- An alias body is a bare name, and no `!meta.alias` carries `@meta.application` (item 27;
  §8.3). §1.6's example closes `flagged<status>` in place (items 4, 35).
- §3.4.1's read of a declaration names the incomplete entry, rule 7 says what bounds closing,
  and rule 1 says what a pin-placed value waits on (items 29, 38, 101, 109).

### 4.7 The JSON encoding ([TSON-JSON])

- Revision 40 is Part 3's fifth revision, against Revision 40 of Parts 1 and 2; §1.6's item
  23 records what it consumes (§3.4 items 1, 2, 5).
- A string at a reference holds a qualified name, each segment a name of its family;
  `"set<text>"` and `"kind!"` are strings outside the kind's grammar (§3.4 items 7–10, 14;
  §1.6, §5.6).
- A `$type`, or a root type supplied out of band, naming a parametric record is refused; a
  type written in place has no `$type` (§3.4 items 3, 4, 6; §3.3, §3.4).
- `$type` at a choice names a variant or a type IS-A exactly one (§3.4 item 15; §3.3, §8.1).
- One value, one type name: a wrapper's `$value` carries no `$type` of its own (§3.4 item
  16; §3.3, §8.6).
- A foreign value: the leading `$type` is the enclosing scope's and every other name the
  foreign schema's (§3.4 item 13; §1.6, §3.3, §6.4, §8.2, §8.6, §9.2).
- The map escape names the variant, and an encoder refuses a variant written in place that
  needs it (§3.4 item 17; §5.7, §8.3.1). Sorting leads with the annotation members only for
  records of `meta.identifier` names (§3.4 item 18; §3.3).
- A set is described by its facets, and a one-position tuple is declared (§3.4 items 11, 12;
  §1.6, §6.3).

### 4.8 Policy and limits

- The materialisation-depth limit goes; closing holds each parametric record of the closure
  at most once on a path (§3.1 item 4; item 29; [TSON-SCHEMA] §3.4.1, §11.5).
- The import closure counts a schema reached by an extern, and Revision 38's foreign-schemas
  limit stays retired (item 115; §11.5). Limits, policies and name hygiene run across an
  extern's boundary, §11.4 judging a foreign schema's names in its own namespace (item 116).
- §11.1 states what a free sibling hands the document's author (item 110). §11.5's entries
  row loses synthetic and instantiation entries (item 55). The token policy keeps its levels
  under its new name (§3.1 items 23–24).

### 4.9 Conformance and artifacts

- Every identity is at `/2026/40/`, `TSON-GUIDE`'s included (§3.1 items 2, 5;
  items 1, 5, 21, 31, 32; §3.4 item 5).
- The six pins and the five resolved fixtures are §6's (items 32, 55, 92, 100, 104); the
  examples and the build are verified as §7 records.

### 4.10 Migration notes

What a Revision 39 document rewrites to mean the same under Revision 40. Each is a change a
Revision 40 processor refuses or reads differently; a form only Revision 40 admits needs no
note.

**Data documents.**
- *An argument list in a value.* `t: set<text>`, `!box<text> { … }` and the JSON string
  `"set<text>"` are refused. The schema declares the application, `names => set<text>`, and
  the document writes `t: names`, `!names { … }`, `"names"` (§3.1 items 14–19; §3.4 item 9).
- *`kind!` in data*, a schema value's payload or a document typed by the kernel: `{ type:
  kind! }` is `{ type: { of: !meta.dependent { on: kind } } }` (§3.1 items 11–12; item 61).
- *A tag naming a type a resolver derived for an application*: the schema declares the
  application and the document tags it by that name (item 54; [TSON-SCHEMA] §7.2).
- *A JSON wrapper with a second type name*: `{ "$type": "shape", "$value": { "$type":
  "circle", … } }` is `{ "$type": "circle", … }` (§3.4 item 16).

**Parametric records.**
- *A value parameter*: `flagged => <T, N> { entry: T  priority?: priority ~ N }` with
  `flagged<status, 2>` is `flagged => <T> { entry: T  priority?: priority }` with
  `status_flag => flagged<status> & { priority?: ~ 2 }`, the value written where the
  application is declared. A facet or size bound taking a parameter is rewritten the same
  way (§1; [TSON-SCHEMA] §5.10).
- *A template over a body that is not a record*: `nonempty => <T> [T; 1..]` is a
  construction left open, `nonempty => !meta.array ^ { min_items: 1 }`, then
  `nonempty<text>`; a template over a value, `<T> !api { … }`, is one value per
  instantiation (§5.10).
- *An alias template or a partial application*: `<B> pair<uuid, B>` is written as the
  application at each use, or as a record restating the body (§5.10).
- *A family base named bare*: over `pet => abstract <T> { kind: text =?  name: text  pet:
  T }`, `holder => { p: pet }` is rewritten with an abstract base, `pet => abstract { kind:
  text =?  name: text }`, a parametric record `pet_of => <T> pet & { pet: T }`, and members
  `dog => pet_of<dog_type> & { kind?: = "dog" }`; `holder` is unchanged (§5.10).
- *An inherited bound*: `rebox => <T> { b: labelled<T> }` over `labelled => <T: text> { … }`
  writes the bound, `rebox => <T: text> { … }` (§5.10).
- *A sugar form or an application as an argument*: `box<[integer]>` is `ints => [integer]`
  then `box<ints>`; `pair<box<text> int32>` is `box_text => box<text>` then `pair<box_text
  int32>` (items 43, 81).
- *An application at a map's key*: `{box<text> => int32}` is `{box_text => int32}` over a
  declared `box_text` (item 43).
- *A recursive application nothing declares*: `f: node<text>` is `node_text =>
  node<text>` then `f: node_text`; `wrap => <T> { n: node<T> }` is `wrap => <N> { n: N }`
  applied to `node_text` (items 49, 56).
- *A one-position tuple*: `meta.tuple1<text>` is declared, `one_text => !meta.tuple [{
  element_type: text }]`, and `meta.voidable_tuple1<text>` adds `voidable: true` to the
  element (item 14; §5.3).
- *A choice that is not disjoint holding a form*: `( [text] | [int32] )` names declared
  variants, `( texts | ints )` (item 44).

**Derivation, the kernel's names and the libraries.**
- *Record refinement*: `employee => person ^ { age: adult }` is `person & { age: adult }`,
  `vip => person ^ {}` is `person & {}`, and `x => box<text> ^ { … }` is `box<text> & { … }`
  (item 108).
- *Constructor refinement*: `minlist => meta.array ^ { min_items?: = 1 }` is the incomplete
  entry `!meta.array ^ { min_items: 1 }`, or the new constructor `meta.array & { min_items?:
  = 1 }` (item 108).
- *`a: enum`*: a field whose value is an enum *type*, a meta-level slot, is typed
  `a: meta.enum_type`, which admits enums over any name type. Declaring an enum and using
  it are unchanged: `status => !enum [OPEN DONE]` declares one, and `s: status` types a field
  by it, as in Revision 39 (item 107; [TSON-SCHEMA] §7.4).
- *A reference over sets*: `meta.ref<meta.set_type>` is `meta.ref<meta.array>`, which admits
  every array type (item 107).
- *A constructor narrowing a set*: `meta.set_type & { … }` is `meta.array & { … }` restating
  the set's facets, `voidable?: = false  ordered?: = false  unique_items?: = true` (item 107).
- *`meta.reference_type`*: `!meta.reference_type { of: X }` and `meta.reference_type & { … }`
  are `!meta.ref { of: X }` (or `meta.ref<X>`) and `meta.ref & { … }`; `meta.field_type_ref`
  and `allow_sibling` are gone, a slot naming a type being `meta.type_ref` and a field typed
  by a sibling's value `kind!` (items 60, 97).
- *`meta.field_name`* is `meta.identifier`, or an alias of the schema's own (item 123).
- *`discriminators` in the kernel's form*: `!meta.record { … discriminators: [kind] }` states
  `extension: ABSTRACT` beside it (item 133).
- *A mark before the kernel's form*: `abstract !meta.record { … }` is `!meta.record { …
  extension: ABSTRACT }`, and `final` likewise `extension: FINAL` (item 136).
- *A mark before a declared application*: `x => abstract box<text>` is the parametric record
  marked, `box => abstract <T> { … }`, where every application is to be abstract, or
  `x => abstract box<text> & {}`, a record composing the application (item 136).
- *A record field or a namespace key holding `.`*: the name is changed, or the vocabulary is
  a map keyed by text, which no reference enters (item 118).
- *A choice holding a choice that is not disjoint*: `( text | either )` over `either => ( a |
  b )` lists the variants, `( text | a | b )` (item 126).
- *A member named `extern` contributed to the kernel's `meta` map* now collides, and is
  renamed (item 114).
- *`policy.script_policy`* is `policy.token_policy` (§3.1 item 23); *`precision`* on a
  temporal type is `fraction_digits`, `!time ^ { fraction_digits: 3 }` (item 113).
- *Resolved forms*: `!meta.template` and `!meta.record_template` are `!meta.record {
  parameters: … }`; `@meta.application:pair<text integer>` is `@meta.application:{ head:
  pair  arguments: [text integer] }`; a synthetic entry is a type written in place where it
  is used; and a consumer reads an entry's kind from its constructor (items 25, 53, 88,
  132).

### 4.11 What is lost, and its replacement

- **The typo check `^` gave.** `person ^ { agee: adult }` was refused, `agee` naming no
  field of `person`; `person & { agee: adult }` adds a field and loads. There is no
  replacement. A modifier-only entry naming no inherited field is still refused (item 108).
- **A field admitting only enums over identifiers**: `a: meta.enum_type` admits any name type,
  and nothing narrower exists (item 107).
- **A reference admitting only sets**: `meta.ref<meta.array>` is wider, and nothing narrower
  exists (item 107).
- **A set's `voidable: false` as a pin**: it held only through `meta.set_type`; a constructor
  composing `meta.array` pins it itself (item 107).
- **A resolved form listing every name its source offers**: what a schema offers to be
  completed is its source's (item 107; [TSON-SCHEMA] §8.1, §10.1).
- **A record field or namespace key holding `.`**: renamed in translation, or a map keyed by
  text (item 118).
- **Abstracting over a facet value or a size, and a parametric record over a value**: the
  value is written where the application is declared, or one value per instantiation (§1;
  [TSON-SCHEMA] §5.10).
- **The inline one-position tuple**: declared (item 14).
- **An alias naming a partial application**: the application written at each use (§5.10).
- **A family base named bare**: an abstract base beside the parametric record (§5.10).
- **A non-disjoint choice as a variant**: its variants listed in the outer choice (item 126).
- **A mark on one declared application**: `x => abstract box<text>` made that declaration's
  record ABSTRACT, or FINAL, in place of the head's `extension`. The replacement, `x =>
  abstract box<text> & {}`, is a new record composing the application, not the application's
  declaration: its key carries no `@meta.application`, and where the application is declared
  elsewhere it is a subtype of that declaration (item 136; [TSON-SCHEMA] §5.8, §5.10).

---

## 5. Open questions carried by this change log

The following remain deliberately open, or are the revision editor's readings awaiting the
spec author's confirmation. They live in this change log only — the specification text
carries no open questions.

| Ref | Question | Status |
|---|---|---|
| #1 | [TSON-DATA] §2.1's example under an `!!import`. | **[settled]** — §3.1 item 20. |
| #2 | A quoted token at a field typed by a reference in data. | **[settled]** — the spec author's ruling, §3.1 item 22 and §3.2 items 117–118. |
| #2 (ruled) | One rule for data: a constructor body's reference slot takes the quoted form, `!meta.array { element_type: "text" }`. A second rule refusing quotes at slots naming types, where none is needed, was the alternative. | **[settled]** — the spec author's ruling. |
| #2 (ruled) | Under a case-folding family a segment is matched in the family's form, as a document's key is, so `get` selects `GET`. Exact spelling, as a schema's references to a field declaration are matched, was the alternative. | **[settled]** — the spec author's ruling. |
| #2 (lost) | A record field or a namespace key holding `.`. | Recorded — item 118: the name is changed in translation, or the vocabulary is a map keyed by text, which no reference enters. |
| #2 (considered) | A reference as an unquoted token everywhere, names outside §7.7 unreachable; a namespace keyed by `meta.identifier` alone; a quoted segment, `main."public api".ping`; `.` refused in every identifier family; a dotted name admitted and unreachable. | Recorded — the first gives up the reach, the second leaves a record's wide field name on the path, the third gives adjacency a meaning where separators are optional, the fourth costs `net.scheme_name`, and the fifth leaves names no reference can write. |
| #4 | A tag at a choice naming a subtype of a variant, and a choice with an abstract variant. | **[settled]** — the spec author's ruling, §3.2 item 119: the tag selects the one variant its type IS-A. |
| #4 (ruled) | A tag whose type IS-A two variants is refused at the data. Refusing the choice at its declaration was the alternative, and a schema importing the choice's could then make it invalid. | **[settled]** — the spec author's ruling. |
| #4 (considered) | The tag kept exact, and an abstract variant refused at the declaration. | Recorded — it gives up "text or any shape" as an open family, the members listed and the choice edited for each new one. |
| #3 (reading) | A number or a quoted token in a *schema* document's argument list: a grammar error, as the register and the proposal have it: Part 2 §12.1 is `type-arg = type-expr`, and the data grammar has no argument list at all ([TSON-DATA] §7.2.5). The corpus records the schema-document case under the `resolver` category, which every schema-document error carries, so it does not tell a grammar error from a resolver one. | **[settled]** — the running behaviour. |
| #3 (reading) | The proposal narrows §12.1's `type-def` alternative `[type-params] type-expr` to a record body. A parameter list before a sugar form or a bare reference is a resolver error naming the remedy, so the production keeps the alternative and a comment says the resolver admits a record body alone. | **[editorial]** — the running behaviour. |
| #3 (reading) | §4.1 makes a parametric record a type by its constructor, `meta.record`, while §5.10's rule is that no parametric record is a type a position names. The text keeps §4.1's predicate and states the refusal on references, as the proposal's §1.3 does; §5.10's heading says "no parametric record is a type a position names". | **[editorial]** — the spec author's confirmation. |
| #3 (reading) | A tag naming a parametric record in data is a validation error (Revision 39: a resolver error as a data annotation). | **[editorial]** — the running behaviour. |
| #5 (ruled) | A value modifier on a field typed by a reference written in place (`handler?: meta.ref<method> ~ …`) is admitted, read against the reference as against a declared one; on a container, tuple or choice written in place it is refused, as on a declared one. | **[settled]** — the spec author's ruling. |
| #5 (ruled) | A choice that is not disjoint names every variant: one holding a variant written in place is a resolver error, a tag naming a variant by its name. | **[settled]** — the spec author's ruling. |
| #5 (ruled) | A name and a type written in place are never the same type, even where the name's construction is identical; a name is a name's type past alias hops, and a type written in place an equal one's. | **[settled]** — the spec author's ruling. |
| #5 (ruled) | An author's explicit `!C { … }` at a field stays refused: §5.6's constructor application is valid only as a declaration's body, and §5.3 admits an applied constructor at a field where a `!` construction is not. | **[settled]** — the existing rule. |
| #6 (ruled) | `kind!` in a data document is refused by the separator rule's parse error, the token before it read as the position reads any token. A targeted rule naming the mark would put the form back in the data grammar. | **[settled]** — the spec author's ruling. |
| #6 (ruled) | `meta.dependent.on` is `text`, naming a field of the record by its declared spelling, declared before the dependent field, as a `discriminators` entry names one. | **[settled]** — the spec author's ruling. |
| #6 (ruled) | A declared sum written in place was refused only by its absence from Part 2 §4.2's list of what `of` holds. §4.2 states it, beside a namespace type. | **[settled]** — the spec author's ruling. |
| #6 (ruled) | A data document typed by the kernel can write a type in place that §4.2's list refuses (`!meta.array { element_type: { of: !meta.dependent { on: kind } } }` reads with no diagnostic): `meta.type_inline.of` is typed `meta.type`, and the list is a resolver rule. It predates `meta.dependent`, which adds one entry to the list. | **[settled]** — the spec author's ruling, §3.2 item 122: the shape alone, the resolver's rules being a declaration's. |
| Items 77–83 (ruled) | A choice over a parameter, `( T \| int32 )`, is disjoint for some arguments and not others. Refusing it at the declaration, or requiring disjointness for every argument, were the alternatives; it loads at the parametric record, and each application is judged where it closes. | **[settled]** — the spec author's ruling. |
| #8 (remainder) | A name in a schema document's annotation value is judged at link (Part 2 §5.12). The first implementation checks the value's shape and delivers the name unjudged; a data document's is judged at the read. | Open — outstanding work in the implementation; the text states the rule. |
| #5 (remainder) | A dependent field whose sibling holds a type written in place is read against that type (Part 2 §5.2). The first implementation does so in bind mode; a tree read, in either encoding, reports the value as not implemented. | Open — outstanding work in the implementation; the text states the rule. |
| #10 (remainder) | Two points on an incomplete entry (Part 2 §5.5). A JSON `$type` naming one reports a gap, where the text encoding reports a mismatch. A field of one typed by a type written in place is read only where the entry is completed, so a wrong value there is reported at the completion, and a name it holds is not judged where the entry is never completed. | Open — outstanding work in the implementation; the text states the rule. |
| #13 (remainder) | Five points on the extern (Part 2 §5.13, §7.9, §11.5; Part 3 §8.6). In TSON text a root typed by an extern, and an extern reached through an alias of a container, write no boundary tag. A tag naming an extern at a position no extern types is refused in both encodings. A map key typed by an extern reports outside the foreign scope in JSON. No JSON writer writes a `$type`, a boundary's included. Nothing counts the import closure limit, externs included. | Open — outstanding work in the implementation; the text states the rule. |
| #12, item 120 (ruled) | A type built by a constructor composed from `meta.array` or `meta.map`, a namespace type, takes `^` under the collection's rules: §5.7's source of `^` is a type built by `meta.array`, by `meta.map` or by a constructor that IS-A either. Keeping the source to the two constructors, so that a namespace type is bounded only where it is constructed, was the alternative. | **[settled]** — the spec author's ruling, the general rule, with no case for a namespace. |
| #10, items 106–107 (ruled) | Over an incomplete entry nothing is replaced: a field written again is refused, whatever the value, and to narrow, the entry is completed and the type refined. One rule covers required fields: `^` writes any unwritten field and leaves one required field absent. An alias of an incomplete entry is allowed and not written. An incomplete entry is imported like any entry. | **[settled]** — the spec author's ruling. |
| #11, item 108 (ruled) | `^` always follows `!name` and its body is values; `&` always follows a bare name and its body is field definitions, the only way to derive a record. | **[settled]** — the spec author's ruling. |
| #10, #11, items 107–108 (lost) | `a: enum` typing a field whose value is an enum type, enums over identifiers alone; a reference over sets alone; a set's `voidable: false` as a pin; a resolved form listing every name its source offers; `^`'s refusal of a new field. | Recorded — items 107 and 108 state each, and the replacement where there is one. |
| #13, items 114–116 (ruled) | The extern is singular and a scope change; the tag at its boundary is the enclosing scope's; `type` is `text`; its schema loads with the imports and the declaring document is refused unless every foreign schema loads, holds the same kernel and closes no cycle; the head is written `meta.extern`; the boundary is absolute, no value entry brought in and no name selecting through it; a default or pin at a field typed by it is refused; an annotation through it is admitted; the import closure limit counts it. | **[settled]** — the spec author's rulings. |
| #13, item 114 (ruled) | `meta.extern.schema` is `iri_reference`, what an `!!import`'s value is ([TSON-DATA] §3.3), so an extern names every schema an import may, a path-only reference to a library entry among them. `iri`, which requires a scheme, was the alternative, and refuses `/local/orders.tn` where `file:/local/orders.tn`, the same identity, passes. | **[settled]** — the spec author's ruling. |
| #13, item 114 (reading) | A schema declares no value entry of an extern type: `!order_v1 { … }` at a declaration is refused with the other heads, beside the refused default and pin, so no foreign value is written in the declaring schema but an annotation's. | **[settled]** — the spec author's ruling: confirmed as written. |
| #13, item 116 (reading) | Which tags the boundary admits. The text applies §7.2's subsumption with each name followed to the entry it reaches, by identity, so a name of the enclosing scope that is no extern is admitted where it reaches the target or a subtype of it (the scope importing the schema that declares it), and a variant's name is admitted where the target is a choice; the narrower reading admits externs alone. A tag naming the position's own extern is read as no tag, so an extern of a member-dispatched base may be written at a choice and its value placed by its selectors. | **[settled]** — the spec author's ruling, §3.2 item 124: by identity, as written. |
| #13, items 114–116 (ruled) | A wrapper's inner `$type` at an extern's boundary named a foreign subtype by the foreign schema's name, which text cannot write. The encodings align on one type name for a value, the boundary's. A second tag in text was the alternative. | **[settled]** — the spec author's ruling, §3.4 item 16. |
| #15, item 126 (ruled) | A choice that is a variant of a choice. The text refuses the one case no document can write, a nested choice that is not disjoint, and keeps a disjoint one, tagged by its name. A nested choice standing for its variants, so that a tag names a variant's variant and disjointness is judged over all of them, is the question of how sums are expressed and is left to the next revision; it lifts this refusal and refuses nothing this text admits. Refusing every nested choice was the alternative, and would refuse `( boolean | narrow )`, which reads. | **[settled]** — the spec author's ruling. |
| #18, item 135 (ruled) | An alias of a parametric record, `bx => box`, applied as `bx<text>`. Admitted, another name as an alias of a constructor or of an incomplete entry is; refusing it was the alternative. | **[settled]** — the spec author's ruling. |
| #11, #20, items 127 and 131 (ruled) | Two matters of arrangement. A restated field's rules stand with composition (§5.8) and refinement has §5.7 to itself; retitling §5.7 to cover both was the alternative. A record with parameters is a parametric record; "open record" was the alternative, and "generic record" was not taken. | **[settled]** — the spec author's rulings. |
| §3.4 item 17 (ruled) | A map holding an annotation member's name as a key, at a choice whose map variant is written in place, had no encoding. The variant is named; a wrapper with no `$type`, read by class, was the alternative. | **[settled]** — the spec author's ruling. |
| §5.12 (ruled) | A qualified name's segment selecting a dependent field. The walk stands, taking the type the sibling names, the one §5.2 judges the held value by at link; refusing the segment was the alternative, and left a value inside a dependent field unnameable. | **[settled]** — the spec author's ruling; no text changes. |
| #17, item 133 (ruled) | `discriminators` on a `!meta.record` whose `extension` is not `ABSTRACT`: refused, a missing `extension` being `OPEN`. Deriving ABSTRACT was the alternative. | **[settled]** — the spec author's ruling. |
| #17, item 136 (ruled) | A definition mark stands before a record definition alone — braces, a composition or a subtraction — and before anything else, `!C { … }` and a declared application included, is a parse error. Admitting it before `!meta.record` where `extension` is unwritten or agrees, and refusing a contradiction, was the alternative: two cases where the grammar needs none. A mark on one declared application is written as a composition. | **[settled]** — the spec author's ruling. |
| #13, items 114–116 (considered) | An open sum over a foreign schema, the place deciding the mapping; a namespace entry reached by a prefix, with or without a list of admitted types; an extern admitted at reference slots alone; a `!!schema` directive in the value; a type carried in the data; a versioned layout in place of any form. | **Not taken** — a sum gives a place where the schema layer takes names, and whoever declares the place would have to know the foreign schema; a prefix written in data gives one value two spellings; the layout reaches only schemas whose authors can still choose their names, and stays as guidance in Part 2 §3.5. |
| #3 (considered) | A `template` constructor wrapping a body; named construction slots; a construction spelling of its own; a label for a parameter on `record_field`; arguments read as the elements of a list field; parameters on any constructor but `record`. | **Not taken** — the proposal's §9 records each. |
| #3 (lost) | Abstracting over a facet value or a size; a parametric record over a value (one value per instantiation instead); the inline one-position tuple; an alias naming a partial application; a family base named bare. | Recorded — each has a stated replacement in Part 2 §5.10. |

---

## 6. Artifact work

1. **Received at Revision 40** — all eleven artifacts carry "2026 Revision 40 draft" and
   `/2026/40/` identities, and each normative pin was checked against sha256 over the bytes
   past the `!!id` line and **matched** the row in Part 2 §13.2: kernel (`meta.tn`)
   `291ddaf3…` (Revision 39 `c9b069b6…`), numeric `0337c188…` (`dfa66c5d…`), temporal
   `3d516776…` (`fb4ada8f…`), net `80b8d1c1…` (`371fc20c…`), core `00eaf217…`
   (`4e8d9c43…`), policy `ffcecb04…` (`e9cb5b93…`). The chain is consistent — numeric,
   temporal, net and policy each pin the kernel; core pins the three libraries.
2. **The kernel changes shape, the artifact set does not.** `meta.tn` holds 83 entries, six
   schema maps, 64 members and 13 root names, where Revision 39's held 87, its members 68:
   eleven entries are removed, seven added and fourteen changed (§4.3). Its one wait cycle
   holds 31 entries, Revision 39's 25, and the cycle's closure 66. `meta-resolved.tn` writes
   80 entries and no synthetic entry, leaving out the incomplete entries `enum` and
   `meta.set_type` and `set`, an alias of one; it writes `meta.extern` among `meta.top`'s
   subtypes. `numeric-resolved.tn` writes its two enums and its set as the constructor's
   construction with `@meta.completion`, `temporal-resolved.tn` names `fraction_digits` on
   its three constructors (§3.2 item 113), `net-resolved.tn` writes its arrays in place
   where it held synthetic entries, and `core-resolved.tn` changes only its identities.

---

## 7. Verification

1. **The examples.** Every Part 2 example written or changed by this revision was run
   through the first implementation's command line against the bundled `/2026/40/` schemas:
   §1.6's schema and data document validate; §5.10's declarations, the family and its data,
   `outcome<text>`, `rebox`, `<K> { m: {K => text} }`,
   `created`, `text_pair`, `nested`, the one-position tuples (`[a]` admitted, `[_]` refused,
   and admitted with `voidable: true`), `nonempty<uuid>`, `meta.array<user_id>`,
   `x => box<text> & { … }`, `<T> !meta.record { … }` and a parameterised subtraction
   resolve; and each refusal the text names — a bare parametric record at a field or an operand, an
   argument outside its bound, an unbounded or mis-bounded parameter passed on, a bound
   naming a constructor, a literal on a parameter-typed field, a parameter at a value or a
   size bound, the six refused bodies, a parameter
   as an operand or a head, an applied constructor at an operand, `meta.namespace<int32
   text>`, `meta.enum_type<meta.identifier>`, `final` on a parametric record, a shadowing parameter,
   a written `parameters` binding, an unreferenced parameter, irregular recursion, a minted
   family member, an application in data and a tag naming a parametric record — is refused with the
   diagnostic the text describes.
   For Part 1: an argument list in a data value is a parse error at a field, an element and
   a map key alike, `{ t: box<text> }` and `[ pair<box<text> int32> ]` among them, and under a
   schema `t: names` over `names => set<text>` is admitted.
   For Part 3: a string holding an application at a reference-typed position, a `$type`
   naming a parametric record, and a root type supplied out of band naming one are each refused.
2. **The pins** — §6 item 1.
3. **#5's examples** — the examples #5 writes or changes run: §1.6's schema, its `history`
   closing `flagged<status>` in place, and its data document; §5.10's `f: page<order>` beside
   `page_order` (a tag naming `page_order` refused at `f`) and `cursor: box<T>` closed in place;
   §5.10.1's `node_text` and `tree_text`, the refusal of an unnamed recursive application,
   `wrap` refused at its declaration applied or not, and the parameter remedy;
   §8.2's `pair<text, text>` at a field; sugar in place, a sugar form refused as an argument and
   the widened positional form.
4. **Items 77–83's examples** — `[T; 1..]`, `[T?]`, `[T, text]`, `( T | int32 )`, `{T =>
   int32}`, `{ cursor: T }` and `[{ at: T  tags: {text => T} }]` load and read through a
   declared application and one at a slot, each equal to the type written by hand;
   `meta.array<T>`, `meta.map<text meta.array<T>>`, `set<T>` and `meta.ref<T>` resolve to the
   same form as their sugar; `pick<int32>` is refused at its declaration; `{box<T> => text}`,
   `m: { w?: T ~ 5 }`, `meta.array<[T]>`, an irregular recursion inside a type in place and
   `f: [node<text>]` are refused.
5. **Items 105–109's examples** — §5.5's `nonempty`, `array_min`, `max_things` and `by_name`
   load; `tags => nonempty<text>`, `by_name<int32>`, `!nonempty { element_type: uuid }` and
   `!nonempty text` resolve, and `max_things<text>` is refused, `min_items` 10 above
   `max_items` 5. An incomplete entry is refused at a field's type, a variant, a bound, an
   operand of `&`, a reference's `of`, a reference-typed slot, an annotation and a
   parameter's name, and its name as a tag in a data document is refused; a field written
   twice and `!meta.array ^ { element_type: text }`, which leaves nothing open, are refused.
   `<T> { s: set<T> }` applied and `set<meta.array<text>>` resolve, and `page<set<text>>` is
   refused. `person & { age: adult }`, `person & {}`, `box<text> & { … }` and
   `!tags ^ { min_items: 1 }` over `tags => [text]` resolve; `person ^ {}` and
   `box<text> ^ { … }` are parse errors naming the composition, `!person ^ {}` a resolver
   error naming it, and `!tags ^ { element_type: uuid }` is refused.
6. **Items 110–112's examples** — `!v4 ^ { version: 7 }` over `v4 => !uuid ^ { version: 4 }`
   is refused and `!v4 ^ { version: 4 }` loads; `!tags ^ { max_items: 3 }` and
   `!lookup ^ { max_items: 3 }` over a declared array and map load, and so does
   `small => !api ^ { max_items: 3 }` over a `!meta.namespace` type, IS-A `api` (item 120).
7. **Item 113's examples** — `!time ^ { fraction_digits: 3 }` loads, admits
   `12:00:00.500Z` and refuses `12:00:00.5004Z`, not a whole number of milliseconds;
   `fraction_digits: 10` is refused at load on `time`, `datetime` and `duration`, and on
   `!temporal.datetime_type { … }`; `!time ^ { precision: 3 }` is refused, `precision` being
   no field of `temporal.time_type`.
8. **Items 114–116's examples** — §5.13's `shipping.tn` loads beside `order-1.tn` and
   `order-2.tn`, each declaring `order`, where importing both is refused; so do an extern of
   a subtype, of a nested map's member (`geo.address`), a nested map of externs as a prefix,
   and an extern at a `meta.ref<meta.record>` slot. Each refusal the text names is refused
   with the diagnostic it describes: `&`, `-`, `^`, a construction head and an application
   head on an extern; a default at a field typed by one; a qualified name through one; a
   target that is a parametric record, an incomplete entry, a constructor, a value entry, a
   schema map or a name the foreign closure lacks; an alias of `meta.extern` at the head;
   two externs of one entry as variants of one choice; an extern of a record at a
   reference over `meta.enum_type`; a cycle through an extern; a foreign schema over
   another revision's kernel; and a pin that does not match. §7.9's data document validates,
   its `!card` read as `order-2.tn`'s; `!order_v1` and a root tagged with an extern of a subtype are admitted; an
   untagged value at `(order_v1 | order_v2)`, the foreign name `!order` at the boundary and a
   name of the reading document inside the value are refused; and a diagnostic inside the
   value names the foreign schema and the extern it was read through. A name the enclosing
   scope imports is admitted at the boundary where it reaches the target or a subtype of it.
   The first implementation reads a foreign value as a tree and in validation; binding one
   to host classes, and the JSON encoding of one, it reports as not implemented and not as a
   verdict.
9. **The build** — the implementation's full build, with the conformance corpus's Revision
   40 branch required, passes over the revised text.
