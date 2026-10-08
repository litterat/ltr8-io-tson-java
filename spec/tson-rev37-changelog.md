---
title: "TSON 2026 Revision 37 — Change Log"
against: "TSON 2026 Revision 36 (Working Draft)"
status: "Adjudicated 2026-10-07. Part 1, Part 2, Part 3 and the Developer Guide are updated; five companion artifacts are as received and `meta-kernel.tn` carries four doc edits made after its pin was stamped; the hash chain is re-stamped over the shipped bytes and Part 2 §13.2 carries the final pins (§6). Part 3 and the guide were completed in a second pass (§7)."
inputs:
  - "SPEC-FEEDBACK.md (21 entries, renumbered against Revision 36: #1–#4 carried, #5–#21 new)"
  - "Revised companion artifacts at /2026/37/ identities: meta-kernel.tn, meta.tn, core.tn and resolved fixtures (implementing #7–#21)"
  - "tson-part3-json.md, edited directly as findings arose (carrying the Part 3 halves of #5, #7, #13, #14 and #18)"
---

# TSON 2026 Revision 37 — Change Log

This document records the changes accepted into Revision 37 of the TSON specification
series, adjudicated from the implementation's spec-feedback register (21 entries,
renumbered from #1 against Revision 36: the four Revision 36 left open, and seventeen
raised since). SPEC-FEEDBACK.md remains a record against Revision 36 and is not modified
by this revision; each of its entries receives a disposition here.

Provenance markers used in the disposition table:

- **[settled]** — the resolution was settled by the spec author inside the feedback
  register itself, is already implemented in the revised companion artifacts shipped with
  this revision, or was decided by the spec author at adjudication.
- **[open]** — a genuine design decision deliberately left open. Collected in §5.

**This revision changes the lexer.** [TSON-DATA] §1.3 said the lexer was frozen within
the 2026 series from Revision 35 and that a later revision touching it would say so. This
one does: a bare `+` becomes the fifteenth special token (§7.2.4, §7.2.5), reserved by the
schema grammar for the at-least-one field group (#18). Every token a Revision 36 data
document lexes to is unchanged — `+5` and `+0.5` begin unquoted tokens as before — and a
bare `+` in a data value moves from a lexer error to a parse error.

It also changes the schema grammar (§12.1) in two places — the parameter list, which
admits a written type, and the field-group productions — and the vocabulary of both
documents: the sentinel `_` is the **void sentinel** and a slot holding it has a **void
value**, where Revision 36 said "absent" (#21).

---

## 1. Baseline: the revised artifacts

Fifteen of the register's proposals arrived already implemented in the companion
artifacts, and Revision 37 adopts them as its baseline. Summarising the normative effect:

- **#7 — `identifier` is a text family, and an enum states its type.** The kernel declares
  `identifier_type => text_type & atom_specification & { … }`, a UAX #31 identifier profile
  as data (`start`, `continue`, `start_add`, `continue_add`, `medial`, `exclude`, over
  `identifier_base => !enum [XID ID NONE]`), and `identifier => !identifier_type {
  continue_add: "-" }`, which is [TSON-DATA] §7.7's profile exactly. An identifier is
  string-class, inherits the text facets, and every value typed by an identifier family is
  a name: [TSON-DATA] §8.2's per-name mechanisms reach it, and the keys of a map keyed by
  one and the elements of a set of one are look-alike scopes. `enum.profile` and
  `enum_profile` are gone: `enum_type => atom & { type: type_name  members: enum_set }` is
  the constructor, with `enum => enum_type ^ { type?: = identifier }` and `text_enum =>
  enum_type ^ { type?: = text }` as its two pins. (Part 1 §2.6, §7.1, §7.7, §8.2; Part 2
  §4.2, §5.4, §5.7, §7.4, §9, §11.4; kernel, fixtures.)
- **#8 — `value` and `void` each have a constructor.** `value_type => atom & {}` with
  `value => !value_type {}`, and `void_type => atom & {}` with `void => !void_type {}`;
  core's sibling is `void => !void_type {}`. `unit` is gone, and with it the one place the
  series identified a type by its name. (Part 2 §4.1, §4.2, §5.4, §7.3, §9; kernel, core.)
- **#9 — a template parameter carries its type.** `template.parameters` is
  `[template_param]` over `template_param => { name: param_name  type: type_ref  bound?:
  type_ref }`. The type is derived from the positions the parameter stands in, a
  declaration may narrow it by writing `<T: text, N: int8>`, and an application is checked
  against the parameter list at the call site. (Part 2 §1.3, §5.2, §5.3, §5.10, §8.1,
  §12.1; kernel, all three fixtures.)
- **#10 — order is a facet every container states.** `array.unordered ~ false` becomes
  `ordered ~ true`, `set_type` pins `ordered: = false`, and `map` gains `ordered ~ false`.
  (Part 2 §5.1, §5.3, §5.6, §7.5, §9; kernel.)
- **#11 — a set's bounds are an array's.** `set_type` loses its `min_items` default of 1;
  the kernel's member sets say `min_items: 1`, and meta gains `scope_set` and
  `decimal_member_set` as named entries. (Part 2 §5.3, §7.4, §7.5, §7.8, §9; kernel, meta.)
- **#12 — `tuple1<T>` and `voidable_tuple1<T>`** in core, the one-position tuple the
  bracket sugar cannot spell. (Part 2 §5.3, §9; core.)
- **#13 — `uri` is RFC 3986's URI beside a `uri_reference`.** `uri_type` gains
  `allow_relative ~ true` and `allow_fragment ~ true`, and `scheme: text` becomes `schemes:
  scheme_set`; the kernel's `uri` is `!uri_type { allow_relative: false }`, and core
  declares `uri_reference => !uri_type {}` with `uri => !uri_reference ^ { allow_relative:
  false }`. (Part 1 §3.3, §5.2, §5.5, §8.1; Part 2 §5.4, §5.5, §5.7, §5.8, §9; kernel,
  core.)
- **#14 — `iri_type` for RFC 3987.** Meta declares `iri_type` with `uri_type`'s facets,
  core declares `iri_reference` and `iri`, a URI is US-ASCII, and a directive argument is
  an IRI-reference. (Part 1 §2.2.1, §3.3, §5.5, §10; Part 2 §5.4, §5.5, §9, §13; meta,
  core.)
- **#15 — core holds only what a schema cannot do without.** `positive_integer`,
  `non_negative_integer`, `negative_integer`, `non_positive_integer`, `non_empty_text`,
  `annotation` and `documentation` leave core (48 entries remain), the kernel's
  `documentation` goes with them so that `doc` is `@annotation text` in both, and the four
  sign bounds leave [TSON-DATA] §5.6's built-in vocabulary. (Part 1 §5.6; Part 2 §1.6,
  §3.3.3, §7.4, §8.3, §9; kernel, core.)
- **#16 — meta's annotation vocabulary.** `todo`, `since` and `lang` leave meta;
  `deprecated` becomes `@annotation void`; `comment => @annotation text` joins; `examples`
  becomes `@annotation [text]`. (Part 2 §3.3.3, §6, §9, §13; meta.)
- **#17 — `@doc`'s text is CommonMark 0.31.2**, with no extensions and raw HTML never
  executed. (Part 2 §6, §13; kernel, core.)
- **#18 — a field group's option may hold several fields.** `field_group` becomes `{
  members: [[field_name; 1..]; 1..]  optional_members?: [field_name; 1..]  optional?:
  boolean ~ false }`; `|` separates options, a `?` on a member's name marks it optional
  within its option, and `( a: A | b: B )+` is sugar for "at least one of". (Part 1 §1.3,
  §4.4, §7.1, §7.2, §7.2.4–§7.2.6, §7.3; Part 2 §5.9, §5.10.1, §5.11, §8.1, §12; kernel,
  fixtures.)
- **#19 — `normalization` is `text_type`'s.** It is the form a value is *put into*, over
  `normalization => !enum [NONE NFC NFKC NFKC_CASEFOLD ASCII_CASEFOLD]`, defaulting to
  `NONE` on `text_type` and `NFC` on `identifier_type`, and fixed to `NONE` on `regex_type`,
  `uri_type`, `iri_type` and `email_type`. `uri_type.schemes` is a set of the kernel's
  `scheme_name`, an `ASCII_CASEFOLD` identifier. (Part 1 §2.6, §7.2.1; Part 2 §5.2, §5.5,
  §5.7, §7.4, §7.5, §7.7, §9; kernel, meta.)
- **#20 — a leap second, and the `precision` sentence.** Core's `time` and `datetime` docs
  state that second 60 is refused, and meta's `time_type` doc separates reading from
  writing. (Part 1 §5.4; Part 2 §5.5; meta, core.)
- **#21 — two kinds of nothing, one name each.** `optional` is a slot that may be missing
  (a record field, a field group); `voidable` is a slot whose value may be void (a record
  field, an array's element, a map's value, a tuple position). `element_state` is retired
  and each fact is a boolean. (Part 1 and Part 2 throughout; kernel, core, fixtures.)

The artifacts also carry housekeeping the register did not raise, adopted here on the same
footing:

- **`@bounded` has a stated meaning, and seven core types change their value.** Meta's
  `bounded` doc now reads "true when the value space has a finite least and greatest
  value". Under it `date`, `datetime` and `duration` become `@bounded:true`, and `uuid`,
  `mac`, `cidr4` and `cidr6` become `@bounded:false` — the first two having no order to
  have a least value in, the networks being partially ordered with no least element. No
  rule in either part reads the annotation, so no text changes.
- **`bytes => !bytes_type {}`** in core, relying on the constructor's `BASE64` default
  rather than restating it. Part 2 §5.5's example follows.
- **`record.discriminators` and `template.discriminators` are `[field_name; 1..]`**, so
  the list is non-empty by type and "whether it is present" replaces "whether it is empty"
  as what says how a family is dispatched. (Part 2 §5.2, §5.10, §8.1.)
- **The hash input is stated in the kernel's header**: sha256 over every byte of a
  document past its `!!id` line, which is [TSON-DATA] §2.2.1's rule.
- **The artifact docs are shortened.** Rationale the specifications or the guide already
  carry is removed from entry docs, and the resolved fixtures omit entry docs by a stated
  convention. Every base kind, role and internal enum in the kernel gains a one-line doc.
- **Meta's `set` template no longer types a meta field**: `scoped.scope` is `scope_set`
  and `decimal_type.members` is `decimal_member_set`, so the template serves meta-layer
  extensions only.

---

## 2. Disposition summary

| # | Entry (abridged) | Disposition |
|---|---|---|
| 1 | §8.2's policy has no artifact; the deployment descriptor | **Open (carried)** — unchanged from Revision 36 §5, with both constraints recorded. [TSON-DATA] §8.2's closing sentence stands as the placeholder it is. |
| 2 | A namespace should be a value | **Open (carried)** — held over a third cycle. The register's reading against #6 and #7 is recorded in §5: the empty cell can be filled by reference (a bounded type slot, a projection type, a route table as data) without a namespace body kind. |
| 3 | A JSON member name that is not an identifier | **Open (carried)** — the projection annotation (`@json_name`) is not taken; [TSON-JSON] §6.1.1's map-typed position remains the answer. |
| 4 | A family member applied at a use site has no name | **Accept, both halves.** §5.2 and §8.2 gain the rule: a template application at a use site whose result composes onto a record — and so is a member of that record's family — is a resolver error, reported at the declaration that wrote the application and naming the fix (`my_name => dog_of<text>`); a family member is declared. It is drawn at membership rather than at member dispatch, since a minted member of a tag-dispatched family is unreachable too, and it leaves every other use-site application alone. §3.3.4 gains the second half: a family is judged over the closure that holds it, so two members brought together only by an import merge are the importing schema's error, with both origins named. Revision 36's open remainder of #15 — keeping a content-derived name as a merge key — is **declined**: with members always declared, §8.2's duplicate forms are never family members, and the key's one use is gone. [settled] |
| 5 | §7.8 gives a scope push at a `declared` position two categories | **Accept** — the cell rule decides at every `scoped` position, so a nested `!!schema` at a position whose `scope` does not hold `EXTERN` is a validation error, named in §7.8 as covering the push at a `declared` position; the typed-position restriction reaches only positions whose type is not a `scoped` instance or a container of one, where the push is a resolver error. §7.1 and §7.8 are restated; [TSON-JSON] §3.3 and §8.5 already carry the reading. [settled] |
| 6 | A type slot cannot be bounded, and a field cannot depend on another field's type | **Open — the field half; the template half is #9's.** `<T: text>` at a template is taken through #9 and recorded as `template_param.bound`. A bounded, binding parameter at a *field's* type (`type: <T: text>  members: set<T>`), the dependent record it makes, and a bound that names a base kind are deferred, by the register's own account, until a bundled schema writes a typed parameter and proves the shape. Recorded in §5. [open] |
| 7 | `identifier` should be a text family | **Accept — the baseline above, all three proposals**, with these settled in text. *Proposal 1:* [TSON-DATA] §8.2's split moves from position to type — a value is a name exactly where its type is an identifier family — which reverses §7.4's "`identifier` is not used in data values", and a document Revision 36 admitted can be refused. `( identifier \| int32 )` becomes disjoint. *Proposal 2:* §7.4's profile table becomes four rows derived from `type`; `type` names a text family, each member is a value of it, and no two are one value under its equality; a pinned `type` resolves in the governing meta and an author-written one in the schema's own namespace; `type` is fixed at construction; the binding row says the members are names and leaves host safety to the binder. *Proposal 3:* the profile facets are fixed where the profile is constructed; a profile with an empty Start set, or a medial character that is also Start or Continue, is refused; join controls keep §7.7 rule 2's contexts under every profile; a profile's own additions are exempt from the `Identifier_Status` rule; a per-segment unit divides a name at its profile's own separators; every profile is its own type and all are string-class. [settled — implemented] |
| 8 | `value` and `void` should each have a constructor | **Accept — the baseline above.** §4.2's name-dispatch MUST and its SHOULD NOT against further `unit` instances are deleted; §5.4's no-class list names `value`. [settled — implemented] |
| 9 | A template parameter should carry its type | **Accept — the recorded type, the restriction syntax and the call-site check; open — the constructor bound.** §5.10 states the derivation (a type slot, a value slot, a routed default, an argument to another template as a fixed point, a positional payload), that the kind follows from the type, that several uses must agree by IS-A, and that a `type` may name an earlier parameter. A written type narrows a value parameter's `type` or is a type parameter's `bound`; a bound is inherited through another template's argument list; a bound names a type and never a constructor. Three things settled at adjudication: **(a)** two same-named entries with the same resolved body — a core sibling and its kernel original — are one type for the parameter-type and bound checks, and for those two checks only; **(b)** ingest verifies a recorded `type` rather than recomputing it, since a written narrowing lives nowhere else; **(c)** the constructor bound `<T: !C>` is not taken. "Parameters carry no bounds" is no longer a v1 boundary. [settled — implemented / open] |
| 10 | Order should be a facet every container states | **Accept — the baseline above.** §7.5's output rule covers maps as it covers sets: `ordered` says whether two values differing only in order are one value, never changes what a document may write, and output keeps the order written either way. [settled — implemented] |
| 11 | A set's bounds should be array's | **Accept — the baseline above.** §5.3, §7.4, §7.5 and §9 drop the default; a bounded set at a field is a named entry. **A language change:** `set<T>` admits `[]`. [settled — implemented] |
| 12 | A one-position tuple has no spelling | **Accept — the baseline above.** `[T]` remains an array and the sugar's two-position minimum stands. [settled — implemented] |
| 13 | `uri` should be RFC 3986's URI beside a `uri_reference` | **Accept — the baseline above.** A relative reference under `!uri` is a **validation error** — inside the family's lexical space, outside the atom's value space. `schemes` narrows as a member set and the two permissions as permissions. **A language change:** `!uri "foo/bar"` is refused; write `!uri_reference`. [settled — implemented] |
| 14 | `iri_type` for RFC 3987 | **Accept — the baseline above.** A character beyond US-ASCII under `!uri` or `!uri_reference` is a **resolver error** — outside the URI grammar. An IRI is judged through the URI it maps to (RFC 3987 §3.1) and compared as written. Canonical identity is untouched. **A language change** for a processor that read `!uri` through a lenient parser. [settled — implemented] |
| 15 | Core should hold only what a schema cannot do without | **Accept — the baseline above**, with one reading settled at adjudication: the register's `count: !integer ^ { min: 0 }` "at a field" is a slip, §5.2's prohibition on an inline atom refinement stands, and a bound is a named declaration of the schema's own. §1.6's example declares `title_text` in place of core's `non_empty_text`. **A language change:** a schema using any of the seven removed core names declares it. [settled — implemented] |
| 16 | Meta's annotation vocabulary | **Accept — the baseline above.** **A language change:** `@deprecated` is written bare; `@since`, `@todo` and `@lang` no longer resolve under meta. [settled — implemented] |
| 17 | `@doc`'s text has no stated format | **Accept — the baseline above.** `@title` stays plain text and `@comment` is left unstated. The rule refuses nothing. [settled — implemented] |
| 18 | A field group's option should hold several fields | **Accept — the baseline above**, with every rule the entry states: the three validity rules, `+` as sugar lowering to a one-option group whose members are all marked, the declaration rules that refuse any group restating plain fields or another group, and the refinement and removal rules. `+` is [TSON-DATA]'s fifteenth special token. [settled — implemented] |
| 19 | `normalization` should be `text_type`'s | **Accept — the baseline above.** §5.5 states that a text value is its token's text put into the type's form and that every facet and every comparison judges the value; §5.7 gains the facet kind **fixed at construction**; a round trip writes the value, and a refusal quotes the token as written and then the value it was judged as. §7.4 drops "rejects non-NFC text". [settled — implemented] |
| 20 | A leap second has no stated value; §5.5's `precision` sentence | **Accept** — second 60 is refused under `!time` and `!datetime`, and settled at adjudication as a **resolver error**: the atom's contract rejects the token, as it rejects hour 25, the admitted form being RFC 3339's without second 60. §5.5's sentence is reworded as the register proposes. [settled — implemented] |
| 21 | The series' two kinds of nothing should each have one name | **Accept — the baseline above.** The prose follows the type: the token is the **void sentinel**, the value is **void**, a key is **missing** or present. [TSON-DATA] §2.9 and [TSON-SCHEMA] §7.6 are retitled, and the grammar's `absent` and `absent-token` productions are renamed `void` and `void-token` (decided by the revision editor; the register names the prose and not the productions). "Absent" survives only in its everyday sense, of a field or facet a body does not state. [settled — implemented] |

Counts: **17 accepted** (4, 5, 7–21 — of which 9's constructor bound is open), **0
declined**, and **4 open questions carried** (1, 2, 3, 6), plus the open remainder of
Revision 35's #25 (§5). Revision 36's open remainder of #15 is declined under #4, and
Revision 35's #4 — typed template parameters — is taken as #9. Open questions are recorded
here and are NOT reflected as open text in the specification.

---

## 3. Accepted changes by target document

### 3.1 Part 1 — Text Data Format

1. **Header, §1.1, §2.1, §2.2** — "void sentinel" for "absent sentinel" (#21).
2. **§1.3** — the lexer-freeze sentence states this revision's change: a bare `+` is a
   special token (#18).
3. **§2.2.1** — a directive argument is read as an IRI-reference, so an identity may carry
   characters beyond US-ASCII as themselves, compared as written (#14).
4. **§2.3, §7.3, §7.4** — the `absent` and `absent-token` productions are renamed `void`
   and `void-token` (#21).
5. **§2.4** — the trailing-comma paragraph speaks of a void value occupying a slot (#21).
6. **§2.6** — a key typed by an identifier family is a name, by its type's statement; a
   text key type's `normalization` can make more keys equal (#7, #19).
7. **§2.9** — retitled *The Void Sentinel* and restated in the new vocabulary: present
   with a void value, distinct from a missing key (#21).
8. **§3.3** — a directive argument is an IRI-reference (RFC 3987); the table's Argument
   column follows (#13, #14).
9. **§4.4** — bare `-` and `+` are special tokens and a bare `.` a lexer error (#18).
10. **§5.2** — the `!uri` and `!iri` scheme requirement joins the range constraints as a
    value rule (#13).
11. **§5.4** — second 60 is refused, as a resolver error (#20).
12. **§5.5** — the table gains `!uri_reference`, `!iri` and `!iri_reference`; `!uri`'s
    row says the scheme is required and the text US-ASCII; a paragraph states the four
    atoms' categories (#13, #14).
13. **§5.6** — the sign-bound row leaves the table, and the paragraph says where a sign
    bound is written now (#15). **`!integer` joins the table**: the arbitrary-precision
    integer was never in the vocabulary, though core declares it and every width refines
    it, and with the sign bounds gone no built-in annotation named one. An oversight, as
    `!boolean` was in Revision 36. [settled]
14. **§6** — JSON `null` maps to void (#21).
15. **§7.1** — the identifier profile is the kernel's `identifier` instance; an enum's
    members are names where its `type` is an identifier family; bare `+` has a role (#7,
    #18).
16. **§7.2** — rule 5 is the void sentinel; rule 7 lists fifteen special tokens (#18,
    #21).
17. **§7.2.1** — under a schema a text type's `normalization` generalises the
    identifier-position rule (#19).
18. **§7.2.4, §7.2.5, §7.2.6** — `+` takes `-`'s boundary rule; fifteen special tokens,
    thirteen reserved by the schema grammar; a bare `+` in a data value is a parse error
    (#18).
19. **§7.7** — the grammar is the `identifier` instance's profile; a map key typed by an
    identifier family is judged as a value of it, under a schema (#7).
20. **§8.1** — the resolver and validation lists follow §5.5 and the renamed sentinel
    (#13, #21).
21. **§8.2** — the per-name mechanisms reach every identifier position and every value
    typed by an identifier family, under the family's own profile; a profile's additions
    are exempt from `Identifier_Status`; a per-segment unit divides at the profile's own
    separators; two data scopes are named, by reference to [TSON-SCHEMA] §11.4 (#7).
22. **§9.4** — a value is a name where a schema types it by an identifier family (#7).
23. **§10** — RFC 3987 is a normative reference (#14).

### 3.2 Part 2 — Type System and Schema

1. **§1.3** — a resolved-output consumer checks an application against the template
   entry's typed parameter list and never reads the held body (#9).
2. **§1.6** — the example declares `title_text => !text ^ { min_length: 1 }` (#15).
3. **§3.3.1** — `template_param` joins the supporting records that are not constructors
   (#9).
4. **§3.3.3** — meta's annotation list is `deprecated`, `title`, `comment`, `examples`,
   `read_only`, `write_only`, `ordering`, `bounded`, `exact`, `numeric`, `disjoint`; the
   kernel carries `doc` and `annotation`, and core declares `doc` alone (#15, #16).
5. **§3.3.4** — a family is open across schemas and judged over the closure that holds it
   (#4). The paragraph also gives §5.2's existing references to this section a target.
6. **§4.1, §4.2** — `value_type` and `void_type` replace `unit`; the name-dispatch rule
   goes; core's `void` is `!void_type {}`; identifier is a text family (#7, #8).
7. **§5.1** — the constructor-refinement example is `set_type => array ^ { ordered?: =
   false }` (#10).
8. **§5.2** — void vocabulary throughout (#21); a selector's text pins compare in the
   field type's normalization form (#19); a family member is declared (#4); the
   discriminator list is present or not (housekeeping).
9. **§5.3** — `voidable` for `state: OPTIONAL` at an element, a map value and a tuple
   position; `ordered` on `array` and `map`; `set_type` without a default bound, and a
   bounded set at a field as a named entry; `tuple1<T>` and `voidable_tuple1<T>`; an
   argument read as its parameter's type (#9, #10, #11, #12, #21).
10. **§5.4** — an enum's class is read from its `type`; identifier families and the URI
    and IRI atoms are string-class; `value` is the atom with no class (#7, #8, #13, #14).
11. **§5.5** — `bytes => !bytes_type {}`; new clauses for text and `normalization`, for
    the URI and IRI families, and for the leap second; the `precision` sentence reworded
    (#13, #14, #19, #20).
12. **§5.6** — the end-state example reads `set_type`'s `ordered: = false` (#10).
13. **§5.7** — the permission and member-set kinds name `uri_type`'s facets; **fixed at
    construction** is a facet kind (`normalization`, the identifier profile facets, an
    enum's `type`); `profile` leaves settable-once (#7, #13, #19).
14. **§5.8** — the `uri_type` example is the kernel's current declaration; a restated
    group member may drop its `?` (#13, #18, #19).
15. **§5.9** — rule 7 is the new removal rule for groups (#18).
16. **§5.10** — a parameter's type is derived and recorded; `<T: X>`; agreement of
    several uses; the earlier-parameter rule; bounds and their inheritance; the same-name
    same-body sentence; the call-site check; "Parameters carry no bounds" is replaced by
    what remains deferred (#9). `bounded_set`'s head is corrected to `!set_type`
    (housekeeping).
17. **§5.10.1** — guards read `voidable` and chosen options (#18, #21).
18. **§5.11** — rewritten: options, the in-option `?`, `+`, the three validity rules, the
    declaration rules, resolution to `members`/`optional_members`/`optional`, refinement,
    composition and removal (#18, #21).
19. **§6** — the advisory list follows meta; `@doc` is CommonMark 0.31.2; `@examples`
    are text; the form example is `@title:"Order"` (#16, #17).
20. **§7.1, §7.8** — the cell rule and the typed-position restriction no longer overlap
    (#5); `scoped.scope` is `scope_set`; a key's void value (#11, #21).
21. **§7.3** — `void` is a `void_type` instance and admits the void sentinel (#8, #21).
22. **§7.4** — enum member semantics rewritten around `enum_type.type`; identifier
    families replace "the `identifier` primitive"; text members are values in the type's
    form; identifier-profile coherence joins the coherence list (#7, #19).
23. **§7.5** — a set may be empty; `ordered` and the output rule for arrays, sets and
    maps; `uri_type.schemes` among the set-typed fields (#10, #11, #13).
24. **§7.6** — retitled *The Void Sentinel Under a Schema*; the table reads `voidable`
    (#21).
25. **§7.7** — typed key equality reads the key type's normalization form (#19).
26. **§8.1** — `template_param`; `field_group`'s shape; `voidable` on `tuple_element`,
    `array` and `map`; ingest verifies a recorded parameter type and re-runs the family
    checks over the closure; the body-patterns table (#4, #9, #18, #21).
27. **§8.2** — a family member is declared; the merge key is declined (#4).
28. **§8.3** — the chain example is `user_id → id → uuid` (#15).
29. **§9** — both table rows; core's contents and the principle behind them; the
    `type_name` exception for `enum_type.type` in the extension guidance (#7–#19).
30. **§11.4** — the enum scope is conditional on an identifier family; two data scopes;
    values, defaults, pins, `data` bodies and annotation values typed by an identifier
    family; profile additions and the per-segment unit (#7).
31. **§12.1, §12.2, §12.3** — `type-param` with an optional written type; `group-def`,
    `group-option` and `group-member`; the `+` suffix; notes and the adjacency table
    (#9, #18).
32. **§13** — RFC 3987, UAX #15, UAX #31 and CommonMark 0.31.2 added; RFC 5646 removed
    with `lang`; pins to the artifacts as received.
33. **§5.7, §1.6, §5.10** — **a parametric modifier takes the name mark its literal
    spelling takes.** `w?: int32 ~ N` is a default, `w?: int32 = N` an injected pin, and
    `w: int32 = N` a marker; `w: int32 ~ N` is refused as `w: T ~ v` is. Revision 36 had
    the modifier written on an unmarked name with the mark supplied at closing; the `?`
    is the field's presence statement and says nothing about the value, so it is written
    where it is meant. §1.6's `flagged` is re-spelled `priority?: priority ~ N`. Not in
    the register; settled by the spec author on review, and what the register's own
    examples and the kernel's `template_param` doc already write. [settled]

### 3.3 Companion artifacts

The revised artifacts shipped with this revision carry the baseline of §1 and every
kernel-, meta- and core-level change in this log. Their entries are not edited by this
adjudication; §6 lists two edits to prose: one unpinned fixture's conventions note, and
four doc lines of `meta-kernel.tn`, edited after its pin was stamped and re-stamped since.

### 3.4 Part 3 — JSON Encoding

[TSON-JSON] is edited directly as findings arise, and arrived with the Part 3 halves of
five entries already in place: #5 (§3.3, §8.5, §9.4), #7 (§1.6 item 7, §5.2, §9.4), #13
and #14 (§5, §5.6) and #18 (§6.1.4). The second pass completed it for the rest, and §7.1
lists the edits. Part 3 defines no type-system rule, so each is a spelling of a rule §3.1
or §3.2 owns; the one place it adds processor requirements of its own is the ordered map
(§7.1).

---

## 4. Notable normative changes (reader's digest)

Changes a Revision 36 implementer must act on:

1. **A bare `+` is a special token.** It takes `-`'s boundary rule: followed by a
   continuation character it begins an unquoted token, otherwise it is emitted alone. In a
   data value it is a parse error, where it was a lexer error.
2. **A field group has options.** `( host: H  port: P | socket: S )` chooses one option; a
   `?` on a member's name makes it optional within its option; `( email: E | phone: P )+`
   is at least one. `field_group` is `{ members: [[…]]  optional_members?  optional? }`,
   so every schema with a group resolves differently, with the same text and value set.
3. **`optional` and `voidable` everywhere.** `state: OPTIONAL` on an array, map or tuple
   position is `voidable: true`; on a field group it is `optional: true`. `element_state`
   is gone. The sentinel is the void sentinel.
4. **`identifier` is a text family.** It is string-class, so `( identifier | int32 )` is
   disjoint; a value typed by an identifier family meets name hygiene, and an
   identifier-keyed map's keys and a set of identifiers are look-alike scopes. A schema
   that wants one declares it: `identifier => !identifier_type { continue_add: "-" }`.
5. **An enum states its type.** `!enum [OPEN DONE]` is unchanged in source and records
   `source: enum`; `profile: TEXT` is written `!text_enum [...]`; `!enum_type { type:
   kebab  members: [...] }` enumerates labels of a schema's own vocabulary.
6. **`unit` is gone.** `value` is `!value_type {}` and `void` is `!void_type {}`, and a
   processor recognises both by constructor.
7. **Template parameters are typed.** Output carries `parameters: [{ name: T  type:
   type_ref } { name: N  type: non_negative_integer }]`; `<T: text>` bounds a type
   parameter and `<N: int8>` narrows a value parameter; an argument is checked at the
   application, against the parameter list.
8. **`ordered` replaces `unordered`**, and `map` has it too, defaulting to `false`.
9. **A set may be empty.** `set<text>` admits `[]`; a non-empty set says `min_items: 1`.
10. **`uri` requires a scheme and is US-ASCII.** `uri_reference`, `iri` and
    `iri_reference` are new atoms, in core and in the built-in vocabulary. `scheme` is
    `schemes`, a set.
11. **`normalization` is a text facet.** A text value is its token's text put into the
    type's form, and every facet and comparison judges that value. A round trip writes
    the value.
12. **Core is smaller.** The four sign-bound integers, `non_empty_text`, `annotation` and
    `documentation` are gone from core, and the sign bounds from [TSON-DATA] §5.6.
13. **Meta's annotations change.** `@deprecated` is bare; `@comment` is new; `@examples`
    takes text; `@since`, `@todo` and `@lang` are gone. `@doc` is CommonMark 0.31.2.
14. **A family member is declared.** A use-site template application that composes onto a
    record is a resolver error, and a family is judged over the whole closure.
15. **A `!!schema` at a `declared` position is a validation error**, by the cell rule; at
    a position that is not scoped it remains a resolver error.
16. **A leap second is refused**, as a resolver error, under `time` and `datetime`.
17. **`tuple1<T>` and `voidable_tuple1<T>`** are in core.
18. **`@bounded` changes on seven core types** (§1).
19. **A parametric default is written `a?: T ~ N`**, with the `?`, as a literal one is;
    the unmarked Revision 36 spelling `a: T ~ N` is refused.
20. **`!integer`** is in the built-in vocabulary.

---

## 5. Open questions carried by this change log

Adjudicated 2026-10-07. The following remain deliberately open. They live in this change
log only — the specification text carries no open questions.

| Ref | Question | Status |
|---|---|---|
| Rev 35 #25 (→ —) | Annotation cardinality as the means of per-name replacement. | Open — recorded as considered. |
| #1 | A third artifact kind — the deployment descriptor: data, not a schema; named at the call site, never discovered; never resolvable by identity; a `.well-known` projection for discovery. | **Closed** (§8.1 item 4, §8.4 item 5) — `policy.tn` declares the policy's vocabulary, and no document selects its own policy. |
| #2 | A namespace as a value. The register's later reading fills the cell by reference rather than by containment: the unchecked reference is #6 alone; an interface is a record type reached by a projection type (`orders.create`); a route table is data whose leaves are bounded references. What stays open under that reading is the `data` kind's remaining use and the anonymous inline member. | Open — carried deliberately; step 1 waits on #6. |
| #3 | A projection annotation (`@json_name:"…"`), the first member of §6's representation-directive category. | Open — [TSON-JSON] §6.1.1's map-typed position is the current answer. |
| #6 | A bounded, binding parameter at a field's type (`type: <T: text>  members: set<T>`): a dependent record, a field-order requirement, a resolved form, and whether a bound may name a base kind. | **Withdrawn** (§8.2 item 13) — not the time to introduce a dependent record; `enum_type.type`'s member conformance (§7.4) and §5.2's value conformance stay rules stated in prose. |
| #9 (remainder) | A bound on the constructor, `<T: !text_type>`, recorded as `template_param.constructor`, for "any text-valued atom" and "any scalar". | **Declined** (§8.2 item 12) — a type parameter names a local type, so its bound is local; a constructor bound judges a schema's type by meta vocabulary. |
| #10 (Part 3) | The two MUSTs [TSON-JSON] §6.4 states for an ordered map — written in order, delivered in the order read — which no register entry spells (§7.1 item 9). | Open — written as the facet's direct consequence; the spec author's to confirm. |
| #9 (remainder) | An edge between a core sibling and its kernel original, in place of the same-name same-body sentence, so that a recorded `type` reads the same in either namespace. | **Closed** (§8.2 item 11) — a recorded type reads where its slot is defined, which the template's form states. |

Decisions taken 2026-10-07: #4 both halves, and the merge key declined; #5 the cell rule
decides at every scoped position; #9 the narrow same-type sentence, ingest verifying a
recorded type, and the constructor bound left open; #15 the inline prohibition stands; #20 a
leap second is a resolver error; #21 the grammar productions are renamed with the prose.
Two further decisions by the spec author on review of the first pass: a parametric field
modifier takes the name mark its literal spelling takes (§3.2 item 33), and `!integer`
joins the built-in vocabulary (§3.1 item 13).

---

## 6. Artifact work

1. **Received at Revision 37** — all six artifacts carry "2026 Revision 37 draft" and
   `/2026/37/` identities, and each pin was checked against sha256 over the bytes past the
   `!!id` line of the copy received: kernel `cfbc5c47…`, meta `001ce80d…`, core
   `0315cb4d…`. Item 3's edit moved them, and Part 2 §13.2 carries the re-stamped values.
   Hash *values* remain non-normative; only the pin's shape is.
2. **One fixture edited.** `meta-kernel-resolved.tn` is unpinned, and its conventions
   note is corrected in three places: set-typed fields are compared as written, in source
   declaration order (Revision 36 §7.5), where the note still had comparison tools
   canonicalising them; a parametric modifier takes the name mark as written (§3.2 item
   33), where the note had `= P` staying a required field until closing; and "REQUIRED
   field" is "required field". No entry changes.
3. **`meta-kernel.tn` edited, and re-stamped.** Four doc edits, by the spec author's
   direction; none changes an entry or resolved output:
   - The `type_argument` doc said "The group is REQUIRED". Under #21 a group is optional
     or it is not, and it now reads "The group is not optional".
   - The `record_field` doc said a parametric `= P` "is a required FREE field … and
     becomes optional and FIXED when substitution makes the value concrete". It now
     states §3.2 item 33: a parametric modifier takes the name mark its literal spelling
     takes, the parameter stands in `value` until substitution, and closing changes
     neither `optional` nor `role`.
   - The `type_ref` and `array_type` docs said "REQUIRED field", a Revision 35 state
     name; both read "required field", as the `record_field` doc beside them does.

   **The hash chain is re-stamped over the shipped bytes.** Because meta pins the kernel
   and core pins meta, the edit moved all three, in that order, with the pinned
   references in `meta.tn` and `core.tn` and the three rows of Part 2 §13.2. §8.4's edits
   moved them again: kernel `f2c2b278…`, meta `568b589b…`, core `4845f0c1…`.
4. **Not verified**, carried as a note: #9's ingest check of a recorded parameter type is
   stated in §8.1 and is not running in the implementation, which re-resolves from source;
   and #10's `ordered` facet is consulted by bind mode, which binds an ordered map to an
   order-keeping host map, while tree mode keeps every map's order, which honours both
   values. The first is implementation work rather than a question for the specification.

---

## 7. Second pass: Part 3 and the Developer Guide

Both ship with Revision 37. Neither carries a decision that §2 does not record; where a
sentence had to be written that no register entry dictates, it is marked **[editorial]**
and is the spec author's to confirm.

### 7.1 Part 3

tson-part3-json.md is the copy received, completed for the entries it had not yet
consumed. Header and status read Revision 37; references move to `/2026/37/` and gain
RFC 3987.

1. **§1.6** gains items 8 to 14 — the identifier family, `normalization`, the URI and IRI
   atoms, field-group options, `optional`/`voidable` and the void vocabulary, the order
   facet and the empty set, and the `value`/`void` constructors with #5's cell rule — each
   with where it lives and which sections here consume it.
2. **Void vocabulary (#21) throughout.** §6.1.2 is retitled "Missing and void", §7 "Void
   and Null", and §7.2 is rewritten over the marks; §1.1 and §1.5 no longer speak of field
   states. JSON `null` is the void sentinel's spelling, a member not written is missing,
   and "absent" is used for neither.
3. **§2's example** declared a pack size as an atom refinement written inline at a field,
   which #15's standing prohibition refuses. It now declares
   `pack => !integer ^ { min: 1 }` and writes `pack_size: pack`. **[editorial]**
4. **§3.1, §6.4 — key identity.** Duplicate member names at a map position are judged
   after the key type's `normalization` has been applied (#19), so two spellings one form
   apart are one key in both encodings.
5. **§5, §5.2, §5.6 — atoms.** Identifier families are string-class and take the
   family's profile and normalization (#7); an enum member is matched in the member
   type's form; `text` values are put into their declared form before facets are judged
   (#19); `uri` and `iri` refuse a relative reference and `uri` anything outside US-ASCII
   (#13, #14); a leap second is refused as in the notation (#20).
6. **§5.7** reads `value` and `void` as constructors (#8) and a scoped position under
   #5's cell rule.
7. **§6.1.4** states group counting in #18 and #21's words: a group that is not optional
   admits exactly one option, an optional group at most one, and the `+` form any
   non-empty subset of its members.
8. **§6.2, §6.3** — a set may be empty unless its type says otherwise (#11); an array's
   `ordered` facet is read from the type (#10); `tuple1<T>` is a one-element JSON array
   (#12).
9. **§6.4 — an ordered map (#10). [editorial]** The register gives a map an `ordered`
   facet and says nothing of JSON, whose object members are unordered by RFC 8259. The
   paragraph added here states the consequence: where the type states `ordered: true`
   an encoder MUST write the entries in the value's order and a decoder MUST deliver
   them in the order read, in object form and pairs form alike, with a note that an
   ordered map in object form keeps its value only through intermediaries that keep
   member order. This is the only place the second pass adds requirements
   that no register entry spells, and it is listed in §5 for confirmation.
10. **§8.3, §8.5, §9.1, §9.3, §9.4** — identifier families join the class-stable list;
    the scoped-position errors follow #5 (a cell-rule failure is a validation error, a
    non-scoped position a resolver error); the round-trip latitude names the order of an
    unordered map's entries and the spelling of a text value its type normalises; the
    error list gains a relative reference, scheme or fragment a URI or IRI type does not
    admit.
11. **Cross-references.** Seven bare section numbers that meant Part 1 or Part 2 and
    resolved, read literally, to a section of Part 3 are qualified — among them the MUST
    in §9.4 that a look-alike member name is refused under [TSON-DATA] §8.2. Present in
    the copy received; no change of meaning.

### 7.2 Developer Guide

tson-guide.md is realigned on the same two principles as before: it describes the design
as it stands, and history lives in the change logs.

1. **§2.6** — field-group options, the `+` form, the one-spelling rules, and the census
   that motivated them (#18).
2. **§2.7** — why a parameter carries a type, and what the call-site check buys (#9).
3. **§2.9** — "two kinds of nothing" (missing against void, #21), and the rule that a
   parametric modifier takes the name mark its literal spelling takes (§3.2 item 33).
4. **§2.10** — a family is judged over its closure and a member is declared (#4).
5. **§3.1, §3.5** — bare `+` as a special token; the enum type as data
   (`enum_type { type members }`, `enum` and `text_enum`), and `identifier` as a type
   with a profile rather than a lexer category (#7).
6. **§4.4, new §4.5** — the meta annotation vocabulary and `@doc` as CommonMark (#16,
   #17); "Why core is small", the restraint rule and the names that left (#15).
7. **§6.1** is retitled "`value`, `void`, and `identifier`" (#8); **§6.2, §6.4** take
   the named member sets, `scope_set` (#11) and #5's errors.
8. **New §6.6**, "Text: a value is put into its form" — `normalization`, URIs against
   IRIs and references against absolute forms, the leap second (#13, #14, #19, #20).
9. **New §6.7**, "Containers: order, emptiness, and the one-position tuple" (#10, #11,
   #12).
10. **§7's worked example** follows Part 2 §1.6 as revised (`title_text`, typed use of
    `priority ~ N`), and its resolved output shows `template_param` entries with their
    types and the held body. The held field is written `optional: true  role: DEFAULT
    value: N`, which follows from §3.2 item 33 and is not copied from a fixture — no
    shipped fixture holds a parametric default. **[editorial]** The section now says it
    extends §1.6's schema with two fields, which it has done since Revision 35.
11. **§8.1, §9.3** in the void vocabulary; references gain RFC 3987 and CommonMark;
    identities are `/2026/37/`. Three small corrections ride along: §2.6's example names
    `datetime`, which core declares, where it named `timestamp`; §4.3 no longer lists
    `identifier` among the kernel types meta's declarations use; §6.2 no longer says
    `integer_type`'s step is typed `integer` (it is `non_negative_integer`).

The Revision 33 guide candidates remain outstanding.

### 7.3 An example re-spelled

`{text => value}` stood at a type position in Part 3 §6.1.1 and Part 2 §7.2, in schemas
that import core. `value` is a kernel entry and core declares no sibling of that name, so
under Part 2 §3.3.2 the reference did not resolve as written. Both fragments predate this
revision. Both now read `{text => text}`, which is what Part 3's JSON example beside it
already carried; core gains nothing, in keeping with #15.

---

## 8. Third pass: verification against the implementation

Each accepted entry was checked against the text of all three parts and the artifacts, and
the implementation run where an entry states what it does. Where the text and the running
code disagreed on wording alone, the text now says what runs; what remains open is
SPEC-FEEDBACK.md's, renumbered against this revision.

### 8.1 Part 1

1. **§2.2.1** — the `!!id` argument is an IRI-reference (§3.3), where the opening said a
   URI (#14).
2. **§7.2.1** — two string values differing only in composition stay distinct as written
   and compare in NFC wherever they are compared ([TSON-SCHEMA] §5.5); the example's
   "decomposed" spelling is written `"cafe\u0301"`, where both spellings were precomposed.
3. **§2.2.1** — an identity without a host has an absolute path, so that path-only and
   hosted identities are disjoint: a relative `tson.io/2026/37/m/core.tn` would be the
   identity `https://tson.io/2026/37/m/core.tn` reduces to. **[settled]** by the spec
   author.

4. **§8.2 — a policy has a vocabulary, and no document selects its own.** The policy's home was "an artifact
   of a kind this series does not yet define". It is now a data document of the `policy` type the companion
   artifact `policy.tn` declares — the identifier and token policies and the resource limits — which is also
   the shape a processor states its policy in, and §8.2 adds the constraint the paragraph's own reasons
   imply: no document may name, import or otherwise select the policy it is judged under. Fetch allow-lists
   and host mappings stay deployment-internal. **[settled]** by the spec author.

### 8.2 Part 2

1. **§2.2**, **§2.2.3**, **§7.1** — a directive value is an IRI-reference ([TSON-DATA]
   §3.3), where three sentences said a URL string (#14).
2. **§5.2** — the family-member error is located at the declaration whose closing minted
   the member where the application sits in a template's held body (`kt` for
   `kt => kennel_of<text>`) (#4).
3. **§5.4** — "a unit placeholder" is "a slot that only ever holds the void value", `unit`
   being gone (#8).
4. **§5.7**, **§7.4** — a facet fixed at construction may be pinned by a refinement of the
   constructor, which is how `enum` and `text_enum` state `type`, and an instance
   refinement restates it verbatim; §7.4's "never restates the type" is "never changes"
   (#7, #19).
5. **§5.10** — the value-parameter example is `<N: int8> { w?: integer ~ N }`; the one it
   replaced, `<N: int8>` at `max_items`, was refused by its own rule, `int8` not being IS-A
   `non_negative_integer` (#9).
6. **§5.10** — two same-named entries with the same resolved body are one type wherever
   §5.10 compares parameter types, the agreement of several uses included, where the text
   named two checks of the four (#9).
7. **§7.8** — a container is a position whose own type is not scoped, its elements and
   values being positions of their own, where "or a container of one" left a `!!schema` on
   `[declared]` itself in neither branch (#5).
8. **§5.5 — no comparison goes below NFC (#19).** Two text values are one when, each in its
   type's form, they are NFC-equal, under every `normalization` member: `NONE` and `NFC`
   share one equality and differ in the value, which is what the other facets judge and a
   round trip writes. The text had said a composed and a decomposed `É` stay two values
   under `NONE` and `ASCII_CASEFOLD`, which [TSON-DATA] §2.6 contradicted for map keys —
   one pair, one key in a map and two members of a set. NFC can carry identity where name
   hygiene cannot: the normalization stability policy keeps the verdict fixed across
   Unicode versions, and no identifier or token policy relaxes it. **[settled]** by the
   spec author.

9. **§5.5, §5.8, §7.8, §9 — `iri_type` is the kernel's and identities are
   `schema_identity`.** `scoped.schemas` was typed by the kernel's `uri`, which requires a
   scheme and US-ASCII and admits a fragment, so a schema whose identity is an IRI or
   path-only could not be named at a scoped position, and a fragment could. Meta's
   `schema_identity => !iri_type { allow_fragment: false }` now types the keys; since meta
   cannot construct an instance of a constructor it declares (§3.3.1), the IRI family
   moves to the kernel, where its `iri` types `atom_specification.spec`, and `uri_type`
   moves to meta. `extern_of` and `extern_type` record `S: schema_identity`. **[settled]**
   by the spec author.

10. **§5.2, §5.10, §8.1, §8.2 — a family's members are the applications a declaration names.** §5.2's
    "a family member is declared" exempted applications of a family's base, but a record-bodied template's
    applications were its members, so `k: box<text>` at a use site minted a member of `box`'s family under a
    content-derived name, and a value at a position typed `box` was then told to name it. A use-site
    application is now a type read where it is written and no member: an instantiation of a family base
    carries the template in its `supertypes` only where a declaration names it. Nothing is refused that loaded
    before. **[settled]** by the spec author.

11. **§5.10 — where a parameter's type is read is decided by the template's form.** A recorded `type` was a
    bare name read in the schema's namespace first, so a schema declaring its own `non_negative_integer` with
    another body changed what `vec => <N> !array { … min_items: N }` admitted: `vec<0>` was refused under a
    `min: 1` shadow, and `vec<"abc">` passed the call-site check under a text one. A template applying a meta
    constructor other than `record` now reads its value parameters' types, written ones included, in the
    structure namespace; a record template reads them in the schema's; a bound is the schema's in every form.
    The entry's `source` says which form it is, so resolved output needs no new field. **[settled]** by the
    spec author.

12. **§5.10 — the constructor bound is declined, and the rule is stated per slot.** A value conforms to where
    it is defined: a value parameter's type is read where the slot it binds into is defined, the meta's for a
    constructor's own slot and the schema's for a field the schema types; a type parameter names a local type,
    so its bound is local. A constructor bound (`<T: text_type> set<T>`) would judge a schema's type by meta
    vocabulary, the crossing item 11 removed. "Any text-valued atom" is not a type — a construction founds a
    family of its own, and an author joins `text`'s by refining it — and "any scalar" is a style a schema
    states with an element or key type. **[settled]** by the spec author.

13. **The bounded type slot at a field is withdrawn, not deferred.** The field half of Revision 36's #6 —
    `type: <T: text>  members: set<T>`, a field binding a type that a later field's type depends on — would
    introduce a dependent record: a field order a streaming reader must rely on, a resolved form for the
    binding, and a kernel change to `record_field` and `enum_type`. Now is the wrong time to introduce such a
    feature: the template half has only just landed, no bundled schema writes a typed parameter to prove its
    shape, and what the field half would make structural — an enum's members against its `type` (§7.4), a
    field's value against its type (§5.2) — is already stated and enforced as prose rules. It is not carried
    as an open question; a later revision that wants it raises it afresh. **[settled]** by the spec author.

### 8.3 Part 3

1. **§1.6 item 14** cites §8.5 and §9.4 for a `$schema` at a `declared` position, where it
   cited §3.3, which does not state it.
2. **§6.1.4** — the `+` form lowers to one option holding every member, each marked
   optional, where the sentence said one option per member, which is how it is written.
3. **§3.1** — a text map key is compared in NFC after its type's form (§5.5's floor).

### 8.4 Companion artifacts

1. **`@ordered` is `@ordering`.** Meta's ordering classification over `[NONE PARTIAL
   TOTAL]` shared its name with the container facet #10 introduced, which says something
   unrelated: a path type is `@ordering:NONE` (no order relation on its values) and
   `ordered: true` (element order is part of a value). The annotation is renamed, its doc
   says which is which, and core's forty markers, both resolved fixtures and Part 2
   §3.3.3, §6 and §9 follow. No rule in either part reads it.
2. **Doc lines.** `meta-kernel.tn`: `template_param`'s type is "never required of the
   author", where it said "never written" beside the paragraph on writing one; `template`'s
   `parameters` lists `template_param` records, where it said names; `void` binds no host
   value, where it said "the host value is absent". `meta.tn`: the float bounds are
   "optional", where a Revision 35 state name stood. `meta-kernel-resolved.tn`: "plain
   required `type_ref`-typed fields", the one "REQUIRED" §6 item 2 missed.
3. **`iri_type` and `uri_type` change places** (§8.2 item 9): the kernel declares
   `iri_type` and `iri`, meta `uri_type` and `schema_identity`; core is unchanged but for
   `extern_of`'s doc.
4. **Re-stamped**, as §6 item 3 records, and Part 2 §13.2 carries the pins.
5. **`policy.tn` joins the companion artifacts** (§8.1 item 4): the processor policy's vocabulary at
   `/2026/37/m/policy.tn`, pinned to meta and core, with `restriction_level`, `script_policy`,
   `identifier_policy`, `limits` and `policy`. A script is named by its Unicode Script property value
   alias; `limits` states nesting depth, §9.1's other limits joining as processors expose them; a policy's
   `unicode_data_version` is optional, a report stating it and a deployment's document leaving it out.
   Part 2 §1.5 and §13.2 list it.

