---
title: "TSON 2026 Revision 36 — Change Log"
against: "TSON 2026 Revision 35 (Working Draft)"
status: "Adjudicated 2026-09-23. Part 1, Part 2 and the six companion artifacts are updated; Part 3 is published with this revision as its first revision; the hash chain is re-stamped and Part 2 §13.2 carries the final pins."
inputs:
  - "SPEC-FEEDBACK.md (21 entries, renumbered against Revision 35: #1–#4 carried, #5–#23 new, with #6 and #9 closed directly in Part 3)"
  - "Revised companion artifacts at /2026/36/ identities: meta-kernel.tn, meta.tn, core.tn and resolved fixtures (implementing #10, #11, #12, #13, #20, #21, #22, #23)"
  - "tson-part3-json.md, 2026 Revision 36 — the JSON encoding draft, edited directly as findings arose (answering #2, #6, #9, and the Part 3 halves of #17, #20, #21 and #23)"
---

# TSON 2026 Revision 36 — Change Log

This document records the changes accepted into Revision 36 of the TSON specification
series, adjudicated from the implementation's spec-feedback register (21 entries,
renumbered from #1 against Revision 35: the four Revision 35 left open, and seventeen
raised since). SPEC-FEEDBACK.md remains a record against Revision 35 and is not modified
by this revision; each of its entries receives a disposition here.

Provenance markers used in the disposition table:

- **[settled]** — the resolution was settled by the spec author inside the feedback
  register itself, is already implemented in the revised companion artifacts shipped with
  this revision, or was decided by the revision editor at adjudication.
- **[open]** — a genuine design decision deliberately left open. Collected in §5.

This revision is the first to publish a third part. **[TSON-JSON]**, the JSON encoding,
was drafted against Revision 35 and edited directly as findings arose, so it is published
with this revision at its first revision, written against Revision 36 of the other two.
The series description in both parts changes from "two parts" to "three", and Part 3's
own §1.6 records which type-system changes it consumes and where each landed.

This revision changes the schema grammar (§12.1) in three places — the field-definition
production, the definition-mark slot, and the group-member production — and leaves the
Class 1 lexer untouched: every token the new forms use is already in [TSON-DATA] §7.2.5's
closed special-token set. Nothing published against Revision 35's frozen lexer is
disturbed.

---

## 1. Baseline: the revised artifacts

Eight of the register's proposals arrived already implemented in the companion artifacts,
and Revision 36 adopts them as its baseline. Summarising the normative effect:

- **#23 — three slots per field, four facts per `record_field`.** A field is spelled
  `name?: type? ~ default` or `name?: type? = fixed`: `?` on the *name* answers whether the
  key may be omitted, `?` on the *type* whether a written `_` is admitted, and the modifier
  what a written value may be. The kernel's `record_field` becomes `{ name  type  optional?
  ~ false  voidable? ~ false  role? ~ FREE  value? }` over `field_role => !enum [FREE
  DEFAULT FIXED]`; `field_state` is gone. What omission yields is derived and never stored:
  a field that is not optional is missing, an optional one with a value injects it. Four
  cells the six states could not spell become spellable (`a: T?`, `a?: T`, `a?: T? ~ v`,
  `a: T = v`), three are refused as rules (a pin on a voidable type), `= _` is gone (its
  meaning is `a?: void?`, or `a: void?` at a restated group member), and refinement is three
  orders rather than a matrix. Every artifact is re-spelled. (Part 1 §2.9; Part 2 §5.2,
  §5.6, §5.7, §5.10, §5.11, §7.6, §8.1, §9, §12.1; all six artifacts.)
- **#10 / #11 — record extension, and the discriminator as field syntax.** The kernel's
  `record` gains `extension?: record_extension_type ~ OPEN` over `record_extension_type =>
  !enum [ABSTRACT FINAL OPEN]` and `discriminators?: [field_name]`. An author writes one of
  the words `abstract` or `final` between `=>` and the type definition, and `=?` on each
  field the members pin; `=?` implies ABSTRACT. How a subtype is selected is whether
  `discriminators` is empty — the tag where it is, the marked fields where it is not — and
  is no member of the enum. `@discriminator` is retired: it stood on a choice, where the
  mechanism it names cannot reach. Both facts are grammar rather than annotations because
  erasing either changes which values conform, which §6 says no annotation may do. (Part 2
  §4.1, §5.2, §5.4, §5.7, §5.8, §5.9, §6, §7.2, §8.1, §8.2, §9, §12.1, §12.2; kernel,
  meta, fixtures.)
- **#12 — `record.supertypes` is `[type_ref]`.** A supertype may be written as an
  application (`ok => <T> result<T> & { … }`), and a name cannot carry the arguments that
  say which instantiation is meant. On the reference channel the parent is substituted and
  closed with the rest of the held body, so `ok<text>` is IS-A `result<text>` and not
  `result<int32>`. `type_definition.supertypes` stays `[type_name]`, computed once every
  parent is a type; a closed supertype writes as a bare token, so every schema with no open
  parent produces byte-identical output. (Part 2 §5.8, §5.9, §8.1; kernel, fixtures.)
- **#13 — a record-bodied template is a family base.** The kernel's `template` gains
  `extension?: record_extension_type` and `discriminators?: [field_name]`: present, the
  extension is always ABSTRACT and derived — a base has no direct instances and its
  applications are subtypes by construction — and the selectors are whichever survive
  erasure of the parameters, including a field pinned to a value parameter. Such a template
  may be named at a type position, since no value is ever read against it; a reference,
  container, constructor-application or atom template may not. §1.3's resolved-output
  consumer tier gains its one deliberate exception. An application at a composition operand
  is subsumed where it stands and mints nothing. (Part 2 §1.3, §5.8, §5.10, §8.1, §9;
  kernel, fixtures.)
- **#20 — `@rest` is retired.** `rest => @annotation void` is gone from meta.tn. A record
  is closed under its type with no exception, and open-ended data is a map-typed field
  written the same way in every encoding. The representation-directive category stays in §6
  with no member. (Part 2 §6, §7.2, §9; meta, fixtures.)
- **#21 — an enum has a profile.** `enum_set` is `!set_type { element_type: text }` and
  `enum` carries `profile?: enum_profile ~ IDENTIFIER` over `enum_profile => !enum
  [IDENTIFIER TEXT]`. IDENTIFIER constrains every member to the identifier grammar and
  carries the name-hygiene rules that follow from it; TEXT admits any text and carries
  none. IDENTIFIER is inside TEXT, which is the narrowing relation a refinement follows.
  Every existing enum survives untouched in source and in output, the default being
  omitted at its own value. Numbers are never enums: the numeric tiers' `members` are the
  spelling. (Part 1 §7.1, §7.7; Part 2 §5.4, §5.7, §7.4, §9, §11.4; kernel.)
- **#22 — `text_type.members`.** The kernel declares `text_member_set => !set_type {
  element_type: text }` and `text_type` gains `members?: text_member_set`, reached by
  `uri_type`, `regex_type` and `email_type` through the composition they already use. The
  pattern MUST admit every member. `pattern` and `members` are each **settable once** — a
  facet kind §5.7 lacked — set where unset, restated verbatim, never changed. (Part 2
  §5.7, §7.4, §9; kernel.)
- **`boolean` in Part 1's vocabulary (#8).** Core's `boolean => !enum [true false]` is the
  type [TSON-DATA] §5 had no annotation for; `!boolean` joins the table. (Part 1 §5.5.)

The artifacts also carry housekeeping the register did not raise, adopted here on the same
footing:

- **`/2026/36/` identities** on all six artifacts, with the hash chain recomputed
  bottom-up (§6). The register's held-over entries were built against these identities,
  which is what let every built proposal above land on a published basis.
- **meta.tn's header** states why it declares no directive and no extension annotation:
  the erasure rule holds there without exception, and both marks are §12.1 syntax.
- **The kernel's `field_role`** sits beside `product_access_type`, `product_size_type` and
  `element_state` as an internal enum; `element_state` is unchanged, since an element slot
  cannot be omitted and has only the voidable question to answer.

---

## 2. Disposition summary

| # | Entry (abridged) | Disposition |
|---|---|---|
| 1 | §8.2's policy has no artifact; the deployment descriptor | **Open (carried)** — as Revision 35 §5; reduced to its artifact half. §8.2's closing sentence stands as the placeholder it is. Recorded in §5 with the second constraint (no `!!import` of a descriptor, no document able to name one). |
| 2 | A document whose encoding has no directive syntax has no way to name its schema | **Accept — defined in [TSON-JSON] §3.5.** The `TSON-Schema` field is an RFC 9651 structured field whose value is a String Item, defined for every TSON-carrying body, a projection of the binding and not an alternative to it; where the field and an in-band binding are both present they MUST agree by canonical identity ([TSON-DATA] §2.2.1), and disagreement is an error, never a precedence question; the field is a sender's claim and no licence to validate an unmarked document; `TSON-Accept-Schema` carries the reverse direction. All four of the register's points are carried. Part 1 §7.1 gains the pointer: the media-type paragraph names the field as the out-of-band channel and leaves its definition to Part 3. [settled — Part 3] |
| 3 | A namespace should be a value | **Open (carried)** — deliberately, per the register: held over a second cycle, the shape being the open question. Recorded in §5. |
| 4 | §7.5's implementation-defined set order and comparison MUST | **Accept (a) — source declaration order is canonical.** In resolved output a set-typed field's elements appear in the order the source declared them, for `enum.members`, `integer_type.members`, `decimal_type.members`, `text_type.members` and `scoped.scope` alike; the comparison MUST and the fixture-tooling SHOULD are deleted, and two resolved documents are compared as §8 writes them. "Sets are unordered" stays true of the *value* — uniqueness and set equality are unchanged — and becomes a statement about semantics rather than bytes. Option (b), bytewise-ascending canonical encodings, is recorded as considered and declined: it costs the author's declaration order, which §7.4 gives a reader a reason to care about and which an ordinal-assigning encoding wants. The register's divergence is thereby closed. [settled] |
| 5 | A JSON member name that is not an identifier has no home | **Open (the annotation); accept the class definition.** The projection annotation (`@json_name`) is not taken this revision. §6 gains what the entry says is owed before the category has a member again: a representation directive's **class** is named by the encoding-rules documents that declare consumption of it, each such document stating so by name, and the "text class" example is deleted; the category currently has no member. [TSON-JSON] §6.1.1 already states the boundary — a member name that is not an identifier belongs at a map-typed position — which is the narrower ask. [settled / open] |
| 7 | §4.1 has no term for a position typed by a host type | **Accept** — Part 1 §4.1's applicability clause is restated over what types the position rather than over the header: base type resolution applies to a token that carries no built-in annotation at a position *nothing* types — a document with no `!!schema` being read into no declared type, as when a processor produces a document-shaped value. Where the position is typed — by a schema, by a tag, or by a declared host type a processor is reading into that names a family §5 defines — the type's own parsing contract reads the token. §4.2 and §4.4 follow unchanged, both being statements about base type resolution. A target naming no family the series defines leaves the position untyped. [settled] |
| 8 | `boolean` is missing from §5's vocabulary | **Accept** — the §5.5 table gains `!boolean`: the tokens `true` and `false`, case-sensitive; any other token is a validation error, as every enum's member set gives. A typed position does not consult the form, so `!boolean "true"` and `!boolean true` are one value — the reading #21 disputes is not taken, §4.2's special status being a base-resolution rule a typed position never reaches. The omission is recorded as an oversight, not a design. [settled] |
| 10 | The discriminator belongs to a subtype family, and it is field syntax | **Accept — the baseline above.** §5.2 spells `=?` beside `~` and `=` and states the reading outright (*pinned, but not here — the members pin it*); the check list is stated for the family — the base is ABSTRACT, derived from the selector; the marked field's type resolves to an atom-family instance or an enum; its name is unmarked and its type non-voidable; it is no group member and carries no value; every entry in `subtypes`, transitively, pins each marked field FIXED; the pins are pairwise distinct **as values under the field type's own equality**, and as tuples where there is more than one — with §5.7's arrangement (the base cannot pin what its members each pin differently) said in prose. A family discriminates one level. The word "discriminator" stays in the prose and the index; `abstract` beside a selector is an admitted assertion, `final` a refusal. §6's `@discriminator` and its validity claim are deleted, and §5.4 gains the pointer. [settled — implemented] |
| 11 | A record cannot say how it may be realised | **Accept — the baseline above.** The three members' meanings; that selection is `discriminators` and not a fourth member; that a family is open across schemas (§3.3.4), so a host closed-set construct is relative to a closure; the FINAL check over composition and refinement; the subtraction exemption and why it is not one; that inhabitance gains no case (an abstract base with no local subtype is a library's ordinary shape, and an empty family is a read-time diagnostic naming the missing import); that the member is never inherited and there is no transition table; that the marks are consumed and their names reserved at exactly one position ([TSON-DATA] §7.7 stands: no reserved words); that a template may be abstract and not final (#13's reason). The field and enum names are settled as the register proposed: `extension`, `record_extension_type`. [settled — implemented] |
| 12 | §5.8's name-level supertype edge cannot place a closed subtype application | **Accept — the baseline above.** §5.8's *Parameterized references* paragraph is replaced and the "two-part check" sentence deleted; §5.9 gains the interaction — a removal drops an open application from the lineage it keeps for names. [settled — implemented] |
| 13 | A record-bodied template is the family base | **Accept — the baseline above**, and **the §1.3 amendment**: a resolved-output consumer MUST support naming a template entry at a type position and dispatching on its `extension` and `discriminators`, and never reads a held body. The two levels of `extension` — the template's, derived and ABSTRACT; an instantiation's, stated by `abstract` inside the held text — and the condition on a selector (its declared type contains no type parameter). Minting is keyed on naming: an instantiation entry is minted where an application is named at a type position, never for a composition operand. #11's blanket refusal of member dispatch on a template is corrected; FINAL stays refused for the better reason. [settled — implemented] |
| 14 | §8.2 says nothing about what a consumer may do with a derived name | **Accept** — §8.2 gains the two consequences: no consumer contract may require a derived name, and a processor holding both SHOULD show and accept the declared alias; the applications of one template are recoverable from `source`. A derived name written in data is not refused, as the register recommends. [settled] |
| 15 | A declaration that denotes a type is that type's entry | **Accept — the rule; open — the merge key.** A declaration whose body is a fully-bound application **is** the instantiation entry: `source` the canonical application, the substituted binding record its body, no parallel entry minted and no `!reference` hop; a use-site application resolves to a declaration owning it where one exists. Two declarations naming one application are two entries, neither privileged, exactly as two hand-written records with one field list are; a discriminated family catches the duplicate by pin distinctness. A use-site sugar form keeps minting a content-keyed synthetic, and §5.3 says why the channels differ. What is given up — cross-schema unification of a declared application with the same form written elsewhere — is stated; keeping the content-derived name as a merge key is carried open (§5), since no implementation has an import-merge path exercising it. [settled / open] |
| 16 | §4.3's operand rule contradicts itself for a record template's instantiation | **Accept** — "a template instantiation (§8.2)" is dropped from the finished list in §4.3 and §5.7; the body test is the whole rule. A record instantiation composes and refines; `vector<text, 3>`, a declared map and a choice are refused for having no fields. [settled — implemented] |
| 17 | §5.4 makes `disjoint` sufficient to omit a tag, and in JSON it is not | **Accept** — §5.4's no-class list gains the two straddling kinds: an approximate atom whose `allow_nan` or `allow_infinity` is true, and a map whose key type, after following its reference chain, is not an atom-family instance or an enum. `disjoint` then carries class stability itself, and a choice's untagged values are one set in every encoding. **A language change** on the text side: `( float64 | text )` and a choice over a compound-keyed map now need a tag in text too, payable by narrowing `allow_nan`/`allow_infinity` or by tagging. [TSON-JSON] §8.3 reduces to a note. [settled] |
| 18 | Value-space identity is defined for atoms, and a compound key or set element can only have the host's | **Accept** — §7.5, §7.7 and Part 1 §2.6 take the qualifier: for an element or key type that is not an atom, two values are duplicates if the processor's host representation of them is equal, which pairs that relates being implementation-defined — at least [TSON-DATA] §2.6's textual identity in a tree the processor builds, and the bound types' own equality where values are bound. Portable duplicate detection over a compound key is keyed by an atom. [settled] |
| 19 | A schema the processor cannot obtain has no outcome | **Accept** — Part 1 §8.1's fifth outcome widens from *refused* to **not judged**, with two members: a refusal (§8.2, §9.1) and an **unavailable schema** ([TSON-SCHEMA] §10.1, §11.2). §10.1's "reports an error" becomes "reports the schema as unavailable", distinguishable from the four categories, in the same report, located at the reference; §10.2's pin mismatch stays a resolver error, and the section says why — a mismatch is a finding about obtained bytes. [settled] |
| 20 | `@rest` cannot define its class | **Accept — the baseline above.** §6's declaration and paragraph deleted; §7.2 states that closure has no exception. [settled — implemented] |
| 21 | An enum models a vocabulary and a value set | **Accept — the baseline above**, with two things settled: the field is named **`profile`**, as the artifact declares it; and under TEXT a member's discrimination class is not read off its token — the enum is string-class whatever its members' spellings, so `!enum [80 443]` under TEXT is a string-class enum whose members are the texts `80` and `443` (and IDENTIFIER members can never be number-class, an identifier not beginning with a digit). §7.4 is rewritten around the profile and answers how an enum binds — to the host type of the natural parse of its members, host enum generation guaranteed under IDENTIFIER and withdrawn under TEXT — and the three-way split (vocabulary; text value set; value set on a constrained family) is stated in one sentence each. [settled — implemented] |
| 22 | `text_type` has no member set | **Accept — the baseline above.** §5.7's facet-kind table gains **settable once**; the asymmetry against the numeric tiers' shrinking member sets is stated with its reason (text is the one tier whose member set shares its position with a pattern, and the pattern is the half that cannot be narrowed). [settled — implemented] |
| 23 | A field state answers three questions | **Accept — the baseline above**, including every rule the entry states: the three refusals; the positional form over unmarked names; the voidable field as a recursion guard; group members taking the type's `?` and no modifier, restated members staying members; `void` refining every type at a voidable position; the three refinement orders; the encoder MUSTs binding a schema-directed encoder, a schema-less encoder omitting an absent value; Part 1 §2.9's second sentence replaced. **A language change:** every `a: T?` re-spells as `a?: T?` or `a?: T`; every injecting `~ v` and `= v` takes `?` on its name; `= _` is written `a?: void?`. The bundled schemas are re-spelled. [settled — implemented] |

Counts: **18 accepted** (2, 4, 7, 8, 10–23 — of which 2 is defined in Part 3, 5's class
sentence is taken with its annotation open, and 15's merge key is open), **0 declined**,
and **3 open questions carried** (1, 3, 5), plus the open remainders of 15 and of Revision
35's #4 and #25 (§5). Open questions are recorded here and are NOT reflected as open text
in the specification.

---

## 3. Accepted changes by target document

### 3.1 Part 1 — Text Data Format

1. **Header, §1.3** — "Part 1 of 3"; the series is published in three parts, and
   [TSON-JSON] is listed as the JSON encoding, defined against the same model; the
   architecture diagram's "reference encoding" line gains the JSON reader beside it.
2. **§2.6** — the type-aware key-identity sentence takes #18's qualifier: under a schema
   identity is over an *atom* key type's value space; a compound key compares by the
   processor's host representation, at least textually in a tree it builds itself.
3. **§2.9** — the second sentence replaced: `_` versus omission is a serialisation choice
   where the field's declaration gives both the same meaning, and the declaration decides
   everywhere else ([TSON-SCHEMA] §5.2, §7.6) (#23).
4. **§4.1** — applicability restated over what types the position (#7): base type
   resolution applies to a token carrying no built-in annotation at a position nothing
   types; a position typed by a schema, a tag, or a declared host type naming a family this
   series defines is read by that type's contract. The `true`/`false` sentence and §4.4's
   fall-through follow unchanged.
5. **§5.5** — the `!boolean` row and its paragraph (#8): the two tokens, case-sensitive;
   the form not consulted at a typed position; the omission recorded as such.
6. **§6** — the JSON reader is [TSON-JSON]; the section points at it.
7. **§7.1** — the identifier-profile sentence's list of governed positions makes "enum
   member" conditional on the enum's profile (#21). The media-type paragraph names
   `TSON-Schema` as the out-of-band channel, defined in [TSON-JSON] §3.5, with its
   agreement rule (#2).
8. **§7.7** — rule 3's `boolean` mention unchanged; the identifier grammar itself is
   unchanged.
9. **§8.1** — the fifth outcome becomes *not judged*, with its two members (#19); the
   Class 1 diagnostics unchanged.
10. **§8.2** — unchanged in rule; the closing placeholder sentence stands (#1).
11. **§10** — [TSON-JSON] added to the series references; identities to `/2026/36/`.

### 3.2 Part 2 — Type System and Schema

1. **Header, §1, §1.1** — "Part 2 of 3"; [TSON-JSON] in the series list; the
   introduction's "exactly two points" sentence updated for the field-modifier value
   (a bare token, `?`, or nothing — never the absent sentinel).
2. **§1.3** — the resolved-output consumer tier amended (#13): a consumer of closed
   entries never meets a held body, but MUST support a template entry named at a type
   position and dispatch on its `extension` and `discriminators`, never reading its body.
3. **§1.6** — the example re-spelled under #23 (`priority?: priority ~ 3`, `due?: date`,
   `tags?: [text]`, `history?: […]`); the template's `priority: priority ~ N` stays unmarked
   (§5.7's parametric rule); identities to `/2026/36/`.
4. **§2.1** — the example's `!!meta`/`!!import` identities; declaration order note
   unchanged.
5. **§3.3.1** — `template` beside `reference` in the list of applicable kernel entries
   (housekeeping from Revision 35's second pass).
6. **§3.3.3** — meta.tn's annotation list loses `discriminator` and `rest` (#10, #20).
7. **§4.1** — the DATA-kind paragraph unchanged; the derived-kind paragraph unchanged.
8. **§4.2** — the `~` paragraph rewritten: the three field modifiers `~`, `=` and `=?`
   (#10, #23); the `void` bullet says what `a: void` and `a?: void` declare (#23).
9. **§4.3** — "a template instantiation (§8.2)" dropped from the finished list (#16); the
   table's Composition and Refinement rows note the FINAL refusal (#11).
10. **§5.2** — rewritten. *Three questions, three slots:* the name's `?`, the type's `?`,
    the modifier; the sixteen-cell table replaced by the spelled forms and the three
    refusals; injection derived (an optional field with a value injects; an unmarked name
    with a pin is a marker the document states); the positional form over unmarked names;
    the FIXED check; encoder MUSTs for the two declarations a schema-less encoder cannot
    write (#23). *Definition marks:* `abstract` and `final`, the three members, the FINAL
    check, the subtraction exemption, inhabitance, no inheritance, identity (#11). *The
    selector `=?`:* the reading, the shape it is admitted on, the family check list, one
    level, the tuple case, the word "discriminator" (#10). *Resolution:* the four-fact
    `record_field`, `extension` and `discriminators` in the body.
11. **§5.3** — `field_state` reference in the tuple paragraph replaced by `field_role`;
    the synthetic-entry paragraph gains why a use-site sugar form mints where a declared
    application does not (#15).
12. **§5.4** — the no-class list gains the two straddling kinds (#17); the enum-class
    sentence reads the profile (#21); a pointer to §5.2 for member dispatch (#10).
13. **§5.6** — the positional form counts unmarked names (#23); a declaration whose body
    is a fully-bound application resolves as the instantiation in place (#15).
14. **§5.7** — the transition table replaced by three orders (#23); the identity diagonal
    restated (a restatement may change a default and never a pin); the facet-kind table
    gains **settable once** (#22); the finished list loses the instantiation (#16); the
    open-modifier paragraph restated over `optional`/`role` — a parametric `= P` or `~ P`
    is written on an unmarked name and closes optional and FIXED or DEFAULT; the
    fix-to-absent sentence goes (#23).
15. **§5.8** — *Parameterized references* replaced: a supertype is a `type_ref`,
    substituted and closed with the held body; the derived index records names; no second
    check (#12). A restated group member stays a member (#23). Composition onto a FINAL
    record is refused (#11). The restated-field annotation rule loses its `@rest` example.
16. **§5.9** — rule 5 restated for `a?: void?` (#23); a removal drops an open application
    from the lineage (#12); subtraction off a FINAL record admissible (#11).
17. **§5.10** — a record-bodied template is a family base; the two levels of
    `extension`; the selector condition; minting keyed on naming; the *Fully-bound
    references* paragraph replaced by the instantiation-in-place rule (#13, #15).
18. **§5.10.1** — a voidable field guards recursion (#23); an abstract base with no local
    subtype is not a productivity error (#11).
19. **§5.11** — group members take the type's `?` and no name mark or modifier; a
    voidable member written `_` selects its alternative; restated members (#23); the
    two-always-present rule has nothing left to refuse and is restated as a consequence.
20. **§6** — the `@discriminator`/`@rest` paragraph deleted, the checked category left
    with `@disjoint`, the representation-directive category defined with no member and its
    class rule stated (#5, #10, #20); the validity sentence corrected (#11); a paragraph
    on the two definition marks and the selector as consumed syntax whose names are
    reserved at one position.
21. **§7.2** — closure has no exception (#20); subsumption at an ABSTRACT position and a
    member-dispatched one, the tag optional and required to agree (#10, #11).
22. **§7.4** — enum member semantics rewritten around `profile` (#21); how an enum binds;
    the three-way split; `text_type.members` beside the numeric tiers with the pattern
    coherence rule (#22).
23. **§7.5** — element order: source declaration order canonical; the comparison MUST and
    the fixture SHOULD deleted (#4); the compound-element qualifier (#18).
24. **§7.6** — the record-field row reads *permitted when the field is voidable*; the
    field-modifier row reads *never* (#23).
25. **§7.7** — the compound-key qualifier (#18).
26. **§8.1** — `record_field` with its four facts; `record.extension`,
    `record.discriminators`, `record.supertypes: [type_ref]`, `template.extension`,
    `template.discriminators` (#11, #12, #13, #23); `source` for a declared application
    (#15); the body-patterns table's instantiation and reference rows; ingest re-checks
    lose the two retired annotations and gain the family checks.
27. **§8.2** — a declaration owning an application is its entry; two declarations, two
    entries; the sugar channel keeps minting (#15); the two consumer consequences of a
    derived name (#14); the set-order sentence (#4).
28. **§8.3** — the *Fully-bound template application* bullet replaced (#15).
29. **§9** — the kernel row: `field_role`, `record_extension_type`, `enum_profile`,
    `text_member_set`, the new `record`/`template`/`enum`/`text_type` fields, no
    `field_state`; the meta row: no `discriminator`, no `rest`; the JSON paragraph points
    at [TSON-JSON]; the set-order convention.
30. **§10.1** — an unobtainable schema is reported as unavailable, not as an error (#19);
    §10.2 says why a mismatch differs.
31. **§11.4** — the enum-member scope is conditional on IDENTIFIER (#21).
32. **§12.1 / §12.2 / §12.3** — `schema-map-entry` gains `[ definition-mark ws ]`;
    `field-def` becomes `field-name ["?"] ws ":" ws …` with `field-modifier = ws ("~" /
    "=") ws token / ws "=" ws "?"`; `group-member` gains `["?"]`; `absent` leaves the
    imports; the disambiguation summary gains the mark line and the `=?` case; the
    adjacency table's `=?` row (#10, #11, #23).
33. **§13** — [TSON-JSON] in the series references; RFC 9651 informative; identities and
    pins to `/2026/36/`.

### 3.3 Companion artifacts

The revised artifacts shipped with this revision already carry the baseline of §1 and every
kernel- and meta-level change in this log, at `/2026/36/` identities with the hash chain
recomputed bottom-up. Two doc edits remain and are listed in §6.

### 3.4 Part 3 — JSON Encoding

[TSON-JSON] is published with this revision, at its first revision, written against
Revision 36. It was edited directly as findings arose rather than adjudicated here, and
its §1.6 lists the four type-system changes it consumes and where each lives, the third of
them — record extension and the discriminator field — landing with this revision. The
register's #6 (§9.4's name-hygiene reach) and #9 (§6.5's map-form test) were closed in
Part 3 directly; the Part 3 halves of #17 (§8.2, §8.3), #20 (§6.1.1), #21 (§5.2) and #23
(§6.1.2, §7, §7.2, §7.3) are in the document as published. Part 3 §3.5 defines the
`TSON-Schema` and `TSON-Accept-Schema` fields, which is #2's answer.

---

## 4. Notable normative changes (reader's digest)

Changes a Revision 35 implementer must act on:

1. **A field is three slots.** `name?: type? ~ v` / `name?: type? = v`: `?` on the name
   says the key may be omitted, `?` on the type says `_` is admitted, the modifier says
   what a value may be. `a: T?` (key required, `_` admitted), `a?: T` (key optional, `_`
   refused), `a?: T? ~ v` (omission injects, `_` clears) and `a: T = v` (a marker the
   document must write) are all spellable. A default needs `?` on the name; a pin on a
   voidable type is refused; `= _` is gone (`a?: void?`).
2. **`record_field` stores four facts** — `optional`, `voidable`, `role` (FREE, DEFAULT,
   FIXED) and `value` — and `field_state` is gone. Omission's meaning is derived. Every
   `T?` field in the kernel, meta and core is re-spelled.
3. **Refinement is three orders**, not a matrix: omission absent → required → injected,
   voidable true → false, role FREE → DEFAULT → FIXED; a refinement may move no question
   backwards.
4. **Records state how they may be realised**: `pet => abstract { … }`, `leaf => final
   { … }`, or neither (OPEN). An ABSTRACT position admits exactly its subtypes and the tag
   is required there; nothing may compose or refine onto a FINAL record; subtraction is
   unaffected.
5. **The discriminator is `=?`.** `pet => abstract { pet_type: text =?  name: text }`,
   `dog => pet & { pet_type: = "dog"  breed: text }`. `=?` implies `abstract`; every
   subtype pins each selector; pins are distinct as values, as tuples where there are
   several; at such a position the tag is optional in every encoding and MUST agree where
   written. `@discriminator` is gone.
6. **`@rest` is gone.** A record is closed with no exception; open-ended data is a map.
7. **`record.supertypes` is `[type_ref]`**: `ok => <T> result<T> & { … }` makes
   `ok<text>` IS-A `result<text>`. Output for schemas with no open parent is unchanged.
8. **A record-bodied template can be named at a type position**, as a family base:
   ABSTRACT by derivation, `discriminators` whatever survives erasure. A resolved-output
   consumer dispatches on the entry's `extension` and `discriminators` and never parses
   the held text.
9. **A declaration naming an application is the instantiation entry.** `bx => box<text>`
   resolves to the closed record itself, `source` the application, no minted `box_text_…`
   beside it and no `!reference` hop; two such declarations are two entries.
10. **A derived name is never a consumer's key**; the applications of a template are
    recovered from `source`.
11. **A record instantiation composes and refines**; the "finished" list is constructor
    applications and their aliases.
12. **Enums have a profile.** `!enum ["sedentary" "lightly active"]` under `profile: TEXT`
    is legal; IDENTIFIER is the default and every existing enum is unchanged. Under TEXT
    the enum is string-class and name hygiene does not reach the members.
13. **`text_type.members`**, settable once beside `pattern`, which is also settable once.
14. **`disjoint` carries class stability.** A choice over an approximate atom still
    admitting NaN or infinities, or over a map with a compound key, is not disjoint in any
    encoding; `( float64 | text )` needs a tag in text too.
15. **Set-typed fields are emitted in source declaration order**, and resolved output is
    compared as written; the set comparison MUST is gone.
16. **A compound set element or map key compares by the host's equality**; portable
    duplicate detection keys by an atom.
17. **An unobtainable schema is *not judged***, not invalid: reported as unavailable,
    beside the four categories and the refusals, located at the reference. A pin
    mismatch stays a resolver error.
18. **Base type resolution applies where nothing types the position** — a host-typed
    target naming a §5 family reads by that family's contract, exactly as a schema
    declaring it would.
19. **`!boolean`** is in the schemaless vocabulary.
20. **`TSON-Schema`** is the out-of-band schema channel, defined in Part 3, for every
    TSON-carrying body; it must agree with an in-band binding by canonical identity.

---

## 5. Open questions carried by this change log

Adjudicated 2026-09-23. The following remain deliberately open. They live in this change
log only — the specification text carries no open questions.

| Ref | Question | Status |
|---|---|---|
| Rev 35 #4 (→ —) | Should template parameters be typed with the kind of slot they stand in? | Open — named as the direction; not taken. |
| Rev 35 #25 (→ —) | Annotation cardinality as the means of per-name replacement. | Open — the field-based discriminator half is now taken (#10); the cardinality half is recorded as considered. |
| #1 | A third artifact kind — the deployment descriptor: data, not a schema; named at the call site, never discovered; never resolvable by identity, so no `!!import` of one and no document able to name one; a `.well-known` projection for discovery. | Open — §8.2 says only what the policy is *not*. |
| #3 | A namespace as a value — the kernel's 2×2 (keys names/data × values data/declarations) has an empty cell; `&`, `^`, `-` acquire obvious meanings; `data` may then have nothing left to do; a third grammar recursion point; qualified names. | Open — carried deliberately; held over a second cycle by the register's own account. |
| #5 | A projection annotation (`@json_name:"…"`) letting a declared field carry a member name that is not an identifier in the encodings that claim it; the first member of §6's representation-directive category, with the load checks the register lists. | Open — the class definition it needs is in §6 now; the annotation itself is not taken, and [TSON-JSON] §6.1.1's map-typed position is the current answer. |
| #15 (remainder) | Keep a declared application's content-derived name as a merge key beside the declared entry, so §8.2's determinism SHOULD keeps its subject across the import merge. | Open — no implementation has an import-merge path exercising it; the property given up is stated in §8.2. |

Decisions taken 2026-09-23: #2 defined in Part 3; #4 option (a); #5 the class sentence
without the annotation; #8 form not consulted at a typed position; #15 the rule without the
merge key; #17 the two straddling kinds; #19 *not judged* with two members; #21 `profile`,
and TEXT enums string-class; #23 as built, with a parametric modifier written on an
unmarked name.

---

## 6. Artifact work

1. **Received at Revision 36** — all six artifacts carry "2026 Revision 36 draft" and
   `/2026/36/` identities with the hash chain recomputed bottom-up over the shipped bytes:
   kernel `e778e463…`, meta `ede51d23…`, core `d924ef91…`. Part 2 §13.2 carries them. Hash
   *values* remain non-normative; only the pin's shape is.
2. **Done: the re-spelling** — every `T?` field, every injecting default and pin, and
   every `= _` in the kernel, meta and core is in the three-slot form; `field_state` is
   gone and `field_role`, `record_extension_type`, `enum_profile` and `text_member_set` are
   declared; `rest` and `discriminator` are gone from meta; `record`, `template`, `enum`
   and `text_type` carry their new fields.
3. **Done: two stale docs.** meta.tn's `disjoint` doc names the fact in the `!choice` body
   and states its class stability (#17); the kernel's `type_ref` doc says a declaration
   naming a fully-bound application is that entry, and only an application no declaration
   names mints one (#15). Neither changes a byte of resolved output; both moved the hash
   chain, re-stamped above.
4. **Not verified**, carried as a note: #15's cross-schema unification is stated as given
   up, and no bundled schema or corpus vector exercises an import that would need it.

---

## 7. Developer Guide

tson-guide.md ships with Revision 36, realigned on the same two principles as before (it
describes the design as it stands; history lives in the change logs). §1.4 and §1.5 name
[TSON-JSON] as the JSON reader and record how the one apparent divergence between the two
encodings moved into Part 2 as class stability; §1.6 lists three documents; §2.4 and §6.1
spell the fix-to-absent case as `a?: void?`; §2.6 points at the sealed family as the third
shape of alternative; §2.7 records the declaration-is-the-entry rule, why sugar keeps
minting, and the record-bodied template as a family base; a new §2.9 carries the rationale
for the three field slots and four facts, with the census that motivated them; a new §2.10
carries record extension and the `=?` selector, the sealed-hierarchy reading and the OpenAPI
conversion; §3.5 describes the enum profile; §5 restates refinement as three orders and
composition's FINAL refusal and `type_ref` parents; §6.2 adds `text_type.members` and
settable-once; §7's worked example is re-spelled, its resolved output shows the four facts
and `flagged` as an abstract base; §8.1 adds the two consumer rules for minted names; §8.3
adds the *not judged* outcome; §9.3 adds the two encoder MUSTs; identities are bumped to
`/2026/36/`. The Revision 33 guide candidates remain outstanding.

### 7.1 Part 3

tson-part3-json.md is edited for the as-published reading: "Part 3 of 3", the anticipated
binary encoding named without a part number or identity (no fourth part is published);
§1.6 gains the Revision 36 changes it consumes (three slots, class stability in `disjoint`,
the enum profile, the compound-identity qualifier) and loses `REQUIRED_FIXED`; §3.3 states
that a declaration naming an application is that entry; §4.1 and §4.2 read base
resolution's applicability and the no-class list from Parts 1 and 2; §5.2 encodes a `TEXT`
enum's members as strings; §5.7 reads `value` over the marks; §6.2 and §6.4 take the
compound-identity qualifier and source order for resolver output; §8.2's predicate is
`disjoint` alone and §8.3 is an informative note; §9.4 reports an unavailable `$schema` as
*not judged*.
