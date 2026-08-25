---
title: "TSON 2026 Revision 33 — Change Log"
against: "TSON 2026 Revision 32 (Working Draft)"
status: "Adjudicated and executed 2026-08-25 — Part 1, Part 2, all six companion artifacts, and the Developer Guide updated; hash pins remain placeholders until publication"
inputs:
  - "SPEC-FEEDBACK.md (59 entries, against Revision 32)"
  - "tson-cr-structure-templates.md (CR-structure-templates, Proposed against Revision 32)"
  - "Revised companion artifacts: meta-kernel.tn, meta.tn, core.tn and resolved fixtures"
---

# TSON 2026 Revision 33 — Change Log

This document records the changes accepted into Revision 33 of the TSON specification
series, adjudicated from the implementation's spec-feedback register (59 entries) and the
change report *Removal of Cross-Namespace Template Linkage* (CR-structure-templates).
SPEC-FEEDBACK.md remains a record against Revision 32 and is not modified by this
revision; each of its entries receives a disposition here.

Provenance markers used in the disposition table:

- **[settled]** — the resolution was settled by the spec author inside the feedback
  register itself, or is already implemented in the revised companion artifacts shipped
  with this revision.
- **[settled]** — disposition proposed by this adjudication; consistent with the
  feedback's suggested resolution and the artifact evidence, pending confirmation.
- **[open]** — a genuine design decision the revision editor must make before the edit
  can be executed. Collected in §5.

---

## 1. Baseline: CR-structure-templates is accepted

Revision 33 adopts CR-structure-templates in full, together with its assumed companion
change (the array size sugar desugars directly to `!array` binding records). The revised
companion artifacts already implement it. Summarising the normative effect:

- **D1/D2 — Map sugar `{K => V}`.** The map type gains a sugar form mirroring the data
  notation, with an optional size specifier (`{K => V ; N..M}`), a simple-ref key, one
  entry, no `?` on either side, and no interior annotations. (Part 2 §5.3, §12.1, §12.2.)
- **D3 — De-parameterised constructors.** `array`, `set`, and `map` lose their parameter
  lists and `= T` routes; type slots become plain REQUIRED `type_ref` fields.
  (Part 2 §4.2, §5.6; meta-kernel.)
- **D4 — Generic heads resolve locally.** `name<args>` heads resolve through the
  type-name namespace only (parameters, locals, imports); an unresolved head is an
  unresolved-type error; a head resolving to a parameter is a resolver error. The §3.3.1
  generic-head bullet and its fallback ordering are deleted; a migration diagnostic
  (SHOULD) suggests the sugar spelling. This resolves feedback **#28** by construction.
- **D5/D6 — One lift rule, structural identity.** Every sugar form lifts at desugar —
  concrete forms to closed synthetic entries, parameter-bearing forms to open synthetic
  entries; a declaration's own body never lifts; materialisation closes open synthetics
  innermost-out. Closed synthetics are keyed on the resolved binding record (source names
  the constructor); open synthetics on the alpha-normalised body; instantiation entries
  on the flattened application in `source`; identity settles after Pass 2 and the two
  channels dedupe against each other.
- **D7/D9 — `instance_template` / `template_argument` and the `instance-template`
  production.** The open counterpart of a constructor application, resolver-produced
  (never authored as `!instance_template`), `top &` and unmarked like `reference`;
  declaration-time checking of everything the parameter list does not obscure; an
  unreferenced declared parameter is an error; regular-recursion restriction (R5) as a
  normative v1 boundary.
- **D10 — One bracket production, one map production; the positional restriction is
  dropped.** Size specifiers and element/position `?` become legal at every type-ref
  position. **This is a language change:** documents that were parse errors under
  Revision 32 become valid. Feedback **#31** is resolved by this collapse, and its
  "not a change to the language" framing is superseded as the CR records.
- **§4.1 hardening.** `!` targets resolve through the structure namespace **only**, with
  no type-name precedence and no local capture (a local `array` can no longer capture
  `!array`). A semantics change beyond the core proposal, listed per CR §6/R7.
- **Deletions.** The size-refinement templates (`array_min`/`array_max`/`array_ranged`),
  `vector`, refinement-of-application-heads (`map<text, text> ^ { … }`), the
  parameterized-heads-over-binding-records carve-outs (§5.10, §7.2), the layer-visibility
  apparatus, and the inline/declaration tier split.
- **Additions.** `token_set` (kernel-internal named entry), `instance_template`,
  `template_argument`; the `instance-template` grammar production.
- **Pipeline.** §3.4.1 becomes Parse → Desugar → Pass 1 → Pass 2, desugar purely
  syntactic and per-declaration.
- **R8 (conformance tiering)** is accepted into Part 2 §1.3 as a normative clause: a
  consumer ingesting only resolved output is fully conforming with zero §5.10 support —
  the closed-entry rule guarantees the wire carries no template machinery. [settled]
- **R9(b) (content-derived synthetic names)** is accepted as a SHOULD; the resolved
  fixtures already use content-hash-derived names. Names remain non-normative between
  implementations. [settled]
- **R10** is moot for this revision (the tranche question is an implementation-sequencing
  concern; the spec text lands whole).

---

## 2. Disposition summary

| # | Entry (abridged) | Disposition |
|---|---|---|
| 1 | Trailing whitespace after closing `"""` | **Accept** — permitted and ignored, symmetric with the opening rule (P1 §7.2.3). [settled] |
| 2 | Prefix stripping vs short/mismatched blank lines | **Accept** — remove the matching portion only; never an error (P1 §7.2.3). [settled] |
| 3 | `@a:@b:val target` cannot stand alone | **Accept** — extend the example (`… target extra`) and note why (P1 §3.1). [settled] |
| 4 | Custom type-ref matching undefined in Part 1 | **No spec change** — Part 2 defines resolution; binding heuristics are implementation policy. Guide note candidate. |
| 5 | `!email` missing from P1 §5.5 | **Accept** — add the row *and* scope the RFC 5322 pin to the `dot-atom "@" dot-atom` core (no quoted local parts, domain literals, or comments) (P1 §5.5; meta `email_type` doc). [settled] |
| 6 | §5.6 lists 4 of 16 `integer_type` instances | **Accept** — list the full family (int8–int256, uint8–uint256, the four bound-only refinements); confirmed oversight. [settled] |
| 7 | What a typed-binding consumer does with unresolved markers | **No spec change** — implementer guidance (report by default, preserve on request). Guide note candidate. |
| 8 | "is a parse error" for atom-format violations collides with §8.1 | **Accept** — restate as "is a resolver error" in P1 §5.2 (and align §5.3–§5.5 phrasing); §8.1 resolver-category description gains atom-contract failures. [settled] |
| 9 | `!text` absent from the built-in vocabulary | **Accept** — the absence was an omission; `!text` is added to the Part 1 built-in vocabulary (P1 §5). [settled] |
| 10 | Base64 padding requirement unstated | **Accept** — padding is REQUIRED for `!base64`/`!base64url`/`!base32` (canonical padding *bits* stay MAY per RFC 4648 §3.5) (P1 §5.3). [settled] |
| 11 | `binary` breaks the `_type` suffix convention | **Accept (keep the name)** — `binary` keeps its name for this revision; the naming/bucketing question is expected to be revisited in a later revision. No edit. [settled] |
| 12 | `!duration` and ISO 8601 `PnW` | **Open (carried)** — deliberately left open; likely revisited in a later revision. No spec change this revision; the question stays recorded here, not in the specification. |
| 13 | Annotation attachment has no host-binding equivalent at scalar positions | **No spec change** — binding-layer concern. Guide note candidate. |
| 14 | `construction-def` can't parse §5.8's own example | **Accept** — `[ws "&" ws record-def]`; fold with #38's operand fix (P2 §12.1). [settled] |
| 15 | §12.1 lead overstates `field-modifier` = `data-value` | **Accept** — correct the sentence (P2 §12.1). [settled] |
| 16 | `instance`/`atom-refinement` payloads too wide | **Accept** — `instance = "!" type-name ws core-value`; `atom-refinement = "!" type-name ws "^" ws record-def`; applied on top of D9's restructured `type-def` (P2 §12.1). [settled] |
| 17 | Chained atom refinement: retarget-replaces vs merge | **Accept** — merge semantics stated explicitly, analogous to §5.7 body materialisation; add a chained worked example (P2 §5.6). [settled] |
| 18 | `unit` instances distinguishable only by prose | **Accept** — state normatively that `value`/`token`/`void` MUST be dispatched by declared name: `!unit {}` instances are internal, machine-recognised primitives whose contracts are fixed by this specification. A machine-readable contract home for third-party unit instances is deferred. [settled] |
| 19 | `~` declarations restricted to kernel-governed schemas? | **Accept** — `~` is not allowed in user schemas: a constructor declaration (`constructor: true`) is valid only in a meta-schema — a schema document whose own `!!meta` names the meta-kernel; elsewhere it is a resolver error (P2 §2.2.2, §4.2). Coherent with #57: extended meta-schemas declaring `data`-kind constructors are kernel-governed. [settled] |
| 20 | `.tn1` used before v1 exists | **Accept** — the artifacts have moved to the unversioned `.tn` extension. §7.1 gains the rule: `.tn` carries no stability claim and is the extension of the 2026 revision series; `.tn1` is reserved for the version 1 freeze; note that the rename at freeze changes `!!id` canonical identities and re-pins the chain. [settled — artifacts renamed] |
| 21 | Validation of shadowed duplicate occurrences | **Dissolved by #41** — duplicates are rejected; the second occurrence is the error. |
| 22 | RFC 9485 pin: subset gate? divergence documentation? | **Accept** — the pin is a strict gate (non-I-Regexp `regex` value is a resolver error) and implementations MUST document divergences (constructs unenforced, host-engine delegation, Unicode version) (P2 §9/meta docs; P1 §7.1 convention mirrored). [settled] |
| 23 | Untagged same-class value-disjoint choices | **Dissolved by #47** — same class ⇒ `disjoint: false` ⇒ tag required. |
| 24 | `disjoint` two-valued prose vs refuted state | **Dissolved by #47** — derivation is total and two-valued. |
| 25 | Non-productive recursive types | **Accept** — a type with no finite model is a resolver error (MUST); "guarded" defined: OPTIONAL field/position, container with zero/absent floor, non-recurring choice variant; a REQUIRED group needs one terminating member; every entry judged; templates judged with parameters assumed inhabited; atom satisfiability a separate axis (P2 §5.10.1 + new prose). [settled] |
| 26 | Hash pin in URI query | **Open (carried)** — the query form is kept for now; no spec change this revision. Whether the pin moves to a fragment or a structured directive stays an open question in this change log. |
| 27 | "Tightens" undefined for selector facets | **Accept** — state the refinement rule per facet kind: ordered facets (tighten only), permission flags (withdraw only), member sets (shrink only), selectors (substitution permitted — say whether IS-A survives), fixed facets (restate only). Reconcile core's `complex` doc with the outcome (P2 §5.7; core.tn). [settled] |
| 28 | Generic-head lookup ordering/shadowing/parameters/gate | **Resolved by CR D4** — heads resolve type-name-only; the four sub-questions dissolve. |
| 29 | §6 has no conformance force | **Accept** — add a Class 2 bullet: annotations MUST be resolved one hop against the governing target and their values validated against the named type's contract; resolver output preserves them. Failure mode per #56. Fix Part 1 §2.1's example (see §4.1 below). [settled] |
| 30 | No optional-valued annotations | **Resolved — declined** — an annotation has exactly the two §6 forms: bare `@T` (void-targeted, shorthand for `@T:_`) or `@T:value` (typed value). Annotation values cannot be optional; §6 stands as written. `meta.tn`'s text-targeted `deprecated`/`since`/`todo`/`lang` are unchanged, and Part 1 §2.1's example is corrected to conform (#29 item 3). [settled] |
| 31 | Two bracket productions, ambiguous `type-def` | **Resolved by CR D10** — collapsed; restriction dropped; recorded as a language change. |
| 32 | Instantiation supertypes unreachable from the carrying schema | **Accept (residual)** — the forcing case is gone (no supertype transfer; size templates deleted), but state once that a resolved entry's *derived* references (`source`, supertypes) are checked against the namespace of their derivation, not the author's (P2 §8.1). [settled] |
| 33 | Sized array IS-A `array`, unsized not | **Resolved by CR/#45** — all container spellings record empty `supertypes`; delete the three IS-A passages (§5.6 "IS-A `array`…", §8.1's `array_ranged` closure sentence, §8.2's entry-shape supertype transfer and example). [settled] |
| 34 | §9.4 names the one UTS #39 mechanism that needs context | **Open (carried)** — needs further investigation; no spec change this revision. The proposed restructure (Identifier_Status at the §7.1 profile; scoped comparison sets; on-detection action; quoted names) stays recorded here for a later revision. |
| 35 | "Metadata must follow `=>`" vs 104 fixture `@doc`s | **Accept (reading 2)** — delete the sentence; an annotation before the name is metadata about the declaration; §8.1 states that schema-map keys carry annotations in resolver output; §10.1 states ingest treatment; `@alias` classified as derived — discarded and recomputed on ingest (P2 §6, §8.1, §10.1). [settled — fixtures round-trip name-position `@doc`] |
| 36 | Group reduced to zero members | **Accept** — state the two-member minimum as an output invariant and the full arity ladder: ≥2 survives, 1 dissolves into a field with the group's state, 0 removes the group (P2 §5.11). [settled] |
| 37 | Subtraction revokes IS-A for all parents | **Accept (flat rule, justified)** — keep the flat break; replace rule 3's circular justification with the head-level-legibility rationale; give §4.3's "revokes it" the same per-parent precision; note the subtract-then-compose idiom for partial retention (P2 §5.9, §4.3). [settled] |
| 38 | Composition operands drawn from `type-ref` | **Accept** — `supertype-ref = type-name [ws "<" type-args ">"]`; generalize §5.7's vocabulary-body requirement to composition (natural home §4.3); state whether choice bodies are deliberately excluded (they are: variants, not fields) (P2 §12.1, §4.3, §5.8). [settled] |
| 39 | OPTIONAL_FIXED injection unstated | **Accept** — not injected (omission leaves the field absent); state the state's purpose (presence is the payload); a written FIXED value MUST be verified, mismatch is a validation error, never overwritten; retire the "two independent axes" framing (P2 §5.2). [settled] |
| 40 | Closure unstated for constructor bodies in schemas | **Accept** — a construction/refinement body is validated as an ordinary closed record against the constructor's vocabulary; an undeclared member is a resolver error; stated at §5.5/§5.7 with the "record-shaped type" sentence duplicated out of §7.2 (P2 §5.5, §5.7). [settled] |
| 41 | Duplicate fields/keys are SHOULD-level | **Accept** — MUST NOT: duplicate record field names and textually identical map keys are rejected; both last-value-wins rules deleted; type-aware duplicates under a schema are Class 2 validation errors (P1 §2.5, §2.6; P2 §7.7). Dissolves #21. [settled — implemented ahead of spec] |
| 42 | The series never needs a warning severity | **Accept** — state once: *a conforming TSON processor has one severity; this specification never asks for a warning*, and resolve the inventory: `_` at REQUIRED_DEFAULT → validation error (omission still injects); set-position duplicates → validation error (delete first-wins); vacuous `0..` → resolver error (suggest `[T]`); unused parameter → resolver error; parameter shadowing a schema type → resolver error; non-productive recursion → resolver error (#25); `@disjoint` outcomes → verified/error (#47); inline-nesting MAY-warn → deleted; diagnostic-wording and encoder-style SHOULDs stay. **Item 9 (root annotation MUST under `!!schema`) is a separate normative change — accepted**, at minimum for validating processors, so "valid" can never mean "checked nothing" (P1 §2.5/§2.6; P2 §5.2, §5.3, §5.4, §5.10, §5.10.1, §7.1, §7.5, §7.6, §7.7). [settled in direction; item 9 proposed] |
| 43 | Map-key identity for the schemaless reader | **Accept** — name the third layer: a processor that decodes values compares decoded values (textual identity is the parser's minimum); a declared key type may only make more keys equal; a key's type-ref and annotations do not participate in identity. With #41 this is a rejection rule (P1 §2.6; P2 §7.7). [settled] |
| 44 | Shadow-channel parameters on constructors rejected nowhere | **Accept** — a `~` declaration whose parameter occurs in any type-reference channel is a resolver error at the declaration; cross-referenced from §5.10's shadowing/label rule (P2 §4.2, §5.10). [settled] |
| 45 | Size templates: `^` over an application misapplies IS-A | **Largely resolved by the CR baseline** (templates deleted; sugar desugars direct; defect (a)'s three passages corrected per #33). The `C<args; member …>` named-partial-application syntax is **deferred** — nothing in the kernel needs it now; record as a design note. [settled/deferred] |
| 46 | Level rule: constructor operand ⇒ `~` result | **Accept** — state in §4.2 as a resolver error; one-directional (kinds and mixins may feed a `~` declaration); cross-reference §5.7/§5.8. The kernel's only violations were the deleted size templates (P2 §4.2). [settled] |
| 47 | Disjointness as discrimination-class distinctness | **Accept** — total, two-valued, normative: classes boolean / number / string / brace / bracket; enum class = members' shared class; classless variant ⇒ `false`; the tag is REQUIRED unless `disjoint`; `@disjoint` outcomes verified/error; class table beside §5.5's atoms; kernel prose amended so a choice always records the field. Dissolves #23/#24 and #42 case 7 (P2 §5.4; meta-kernel doc). [settled — implemented] |
| 48 | `void` as a choice variant | **Accept** — a variant MUST NOT resolve to `void` (judged after flattening); diagnostic states "optionality is not choice" (P2 §5.4). [settled — implemented] |
| 49 | Nested `?` has no structural channel | **Resolved by CR/#50** — every form lifts to an entry, whose body carries the state. |
| 50 | Constructor applications must materialise | **Accept** — every application materialises an entry at desugar; a use site is a bare reference; declaration bodies remain constructions-in-place; delete §5.3's "Resolution is structural" paragraph and §5.4's inline-choice-no-entry sentence; `type_ref.arguments` survives only in `source` (P2 §5.3, §5.4, §8.2). **Artifact fix required:** the revised meta-kernel's `type_ref`/`type_argument` doc strings still describe structural carriage — stale against the fixtures, which materialise (see §6). [settled — fixtures implement it] |
| 51 | Non-exposure contradicts identity across `!!import` | **Accept** — restate: materialised entries are resolver-named and unspellable, merge under `!!import` like any entry, and an importing schema's identical application denotes the merged entry; note that content-derived naming is what makes merged namespaces agree (P2 §8.2). [settled — implemented] |
| 52 | Brace dispatch makes D2's key-`?` rule unreachable | **Accept** — state the interaction at the dispatch; improve the record-field diagnostic ("expected `:`; if you meant a map type, `?` is not permitted on a map key") (P2 §12.2). [settled] |
| 53 | Parameters inside collection-valued slots | **Declined (author)** — `template_argument` keeps three channels; scope D5's lift rule to constructors whose slots are all scalar and state the exclusion; classify both spellings of the refusal identically (author error at the declaration) (P2 §5.10/CR D5 text). [settled] |
| 54 | Type-argument literal: token or value? | **Open (carried)** — the structure-templates CR is accepted but will go through another revision; the identity question (token / value / normalise-before-compare) is deferred to it. No spec change this revision. |
| 55 | Shallow `!!import` vs transitive `!!meta` | **Accept** — imports are transitive (whole namespace); collisions decided by entry identity (re-arrival unifies; two different schemas declaring one name is a hard error; locals may not reuse closure names — no hiding); pin is verification metadata, never identity; delete the `uuid` hiding example; reword §3.3.1's "one hop"; state the two accepted costs (name reservation; upstream-only conflicts) and the revision-skew consequence (P2 §2.2.3, §3.3.1). [settled — implemented] |
| 56 | Unresolved annotation name: failure mode | **Accept** — a resolver error, valueless form included; the near-miss diagnostic (declared locally / via `!!import` → usable in data documents, not here) and remedy stated; kernel-bootstrap exception noted. The larger "why can't a schema's own declarations serve its annotations" question stays a recorded design question (P2 §6, §3.3.3). [settled — implemented] |
| 57 | Nowhere for a non-type in the schema map | **Accept (resolution 2)** — the `data` base kind and `kind: DATA` are already in the revised kernel: `data => top & {}`, `type_kind` gains `DATA`; §4.1 adds `data` to kind determination; naming a DATA entry where a type is expected is a resolver error; §10.1 gains the ingest sentence (a DATA body's vocabulary is meta-supplied, `extern`-like); §9 gains the extension-author guidance to type reference slots `type_ref`, not `type_name` (P2 §4.1, §8.1, §9, §10.1, §3.3.1; kernel). [settled — in artifacts] |
| 58 | Parametric `=` fixation on template materialisation | **Accept** — on materialisation a field whose `value_param` binds to a concrete argument takes its literal spelling's state: `= P` → REQUIRED_FIXED, `~ P` → REQUIRED_DEFAULT (P2 §5.7). [settled] |
| 59 | Invalid byte sequences | **Accept** — a byte sequence invalid in the document's encoding is a lexer error; decoders MUST NOT substitute replacement characters; overlong forms, encoded surrogates, and values above U+10FFFF named as invalid UTF-8 (P1 §7.1, §8.1). [settled] |

Counts: **39 accepted** (now including 9, 11, 18, 19), **8 resolved/dissolved by the CR
baseline or by another accepted entry** (21, 23, 24, 28, 31, 33, 45, 49), **5 declined or
no-spec-change** (4, 7, 13 — guide candidates — 30, and 53's core, both declined by the
author with a documenting edit), and **4 open questions carried in this change log
only** (12, 26, 34, 54). Open questions are recorded here and are NOT reflected as open
text in the specification.

---

## 3. Accepted changes by target document

### 3.1 Part 1 — Text Data Format

1. **§2.1 worked example** — reconcile the flagship example with annotation typing
   (#29/#30): `@deprecated`/`@expires` must resolve against the governing schema. Either
   the example's schema is said to declare them, or the annotations are replaced with
   core-reachable ones (`@doc`), and the bare-vs-valued form must match the declaration.
   Exact edit depends on the #30 decision.
2. **§2.5 / §2.6** — duplicates: MUST NOT; delete both last-value-wins rules; textual
   identity stated as the parser's *minimum*, decoded-value identity for readers (#41,
   #43); key type-ref/annotations excluded from identity (#43).
3. **§3.1** — fix the `@a:@b:val target` example (#3).
4. **§5.2** — atom-contract failures are "a resolver error" (#8); §8.1's resolver
   category description extended to match.
5. **§5.3** — padding REQUIRED for the padded encodings (#10).
6. **§5.5** — add `!email` with the scoped RFC 5322 subset (#5).
7. **§5.6** — full `integer_type` family (#6).
8. **§5 (vocabulary)** — `!text` added as a built-in (#9). The `PnW` question (#12) is
   carried open in this change log; the §5.4 table is unchanged.
10. **§7.1** — the `.tn` extension rule for the 2026 series; `.tn1` reserved for the v1
    freeze; identity consequences at the rename noted (#20). Encoding: invalid byte
    sequences are lexer errors; no replacement characters (#59).
11. **§7.2.3** — closing-delimiter trailing whitespace permitted (#1); best-effort
    prefix removal (#2).
12. **§8.1** — resolver category covers atom contracts (#8); encoding errors are lexer
    errors (#59).
13. **§9.4** — unchanged this revision; the restructure (#34) is carried open in this
    change log.

### 3.2 Part 2 — Type System and Schema

1. **§1.3** — Class 2 bullet for annotations (#29); the resolved-output conformance tier
   (CR R8).
2. **§2.2.3 / §3.3.1** — transitive imports, identity-based collisions, pin-vs-identity
   sentence, `uuid` example deleted, "one hop" reworded (#55). Structure-namespace-only
   `!` resolution (CR §4.1); generic heads type-name-only (CR D4).
3. **§3.3.3 / §6** — annotation failure mode (#56); "must follow `=>`" deleted and
   name-position annotations defined as declaration metadata (#35); §6 normative force
   (#29). Per #30's resolution, §6's two-form rule stands unchanged.
4. **§4.1** — the `data` base kind; kind determination extended; DATA entries not
   nameable as types (#57).
5. **§2.2.2 / §4.2** — constructor prose rewritten for parameterless containers (CR D3);
   `~` declarations valid only in meta-schemas (documents whose `!!meta` names the
   meta-kernel) — elsewhere a resolver error (#19); labelled-only parameters for `~`
   declarations (#44); the level rule (#46); `unit`-instance dispatch-by-name stated
   normatively (#18).
6. **§4.3** — vocabulary-body requirement generalised to construction (#38); "revokes
   it" precision (#37).
7. **§5.2** — OPTIONAL_FIXED not injected; fixed-value verification; framing (#39);
   inline-nesting MAY-warn deleted (#42).
8. **§5.3** — CR D1/D2/D10 rewrite: one bracket form, the map form, the new desugar
   table, the *Nested forms and synthetic entries* subsection; the structural-
   representation paragraph deleted (#50); `0..` a resolver error (#42).
9. **§5.4** — discrimination-class disjointness rewrite (#47); `void` variants forbidden
   (#48); tagging restated over the new fact.
10. **§5.5 / §5.7** — closure for constructor/refinement bodies (#40); per-facet-kind
    tightening (#27); chained atom-refinement merge semantics (#17, lands in §5.6's
    desugaring text); materialisation fixation rule (#58).
11. **§5.9** — flat-break justification; §4.3 wording (#37).
12. **§5.10 / §5.10.1** — CR D5–D7/D9 integration; regular recursion (CR R5);
    productivity MUST with "guarded" defined (#25); unused/shadowing parameters are
    errors (#42); collection-slot exclusion stated (#53).
13. **§5.11** — group arity ladder to zero (#36).
14. **§7.1** — root annotation MUST under `!!schema` for validating processors (#42.9).
15. **§7.2** — parameterized-heads carve-out deleted (CR §4.6).
16. **§7.5 / §7.6 / §7.7** — set duplicates, `_` at REQUIRED_DEFAULT, typed key
    duplicates: errors (#41/#42/#43).
17. **§8.1 / §8.2** — synthetic entries and the unified identity rule (CR §4.7);
    every-application-materialises (#50); non-exposure restated over the import merge
    (#51); key annotations in output and ingest (#35); `@alias` and the new `@synthetic`
    classified derived — discarded and recomputed on ingest (#35, CR §9); resolver
    output marks each materialised entry with `@synthetic` at the key. The #54 identity
    question is carried open in this change log; §8.2's identity text stays as the CR
    wrote it.
18. **§9** — meta-layer table updated (no `vector`; `instance_template`,
    `template_argument`, `token_set`, `data`); extension-author guidance (`type_ref`
    slots) (#57).
19. **§10.1** — DATA-body ingest sentence (#57).
20. **§12.1 / §12.2 / §12.3** — CR §4.8 grammar; #14/#15/#16 ABNF fixes;
    `supertype-ref` (#38); dispatch note for the key-`?` case (#52).
21. **§11 / references** — regex-pin gate and divergence documentation (#22).

### 3.3 Companion artifacts

The revised artifacts shipped with this revision already carry: parameterless
`array`/`set`/`map`; the map sugar in `schema` and `instance_template.bindings`;
`token_set`; `instance_template`/`template_argument`; the deleted size templates and
`vector`; the `data` base kind and `DATA` type_kind; `.tn` file identities; fixtures with
content-derived synthetic-entry names. Remaining artifact work is in §6.

### 3.4 CR-structure-templates (reference edits)

Per its own review items: D5 scoped to scalar-slot constructors (#53); the #31 paragraph
recording the language change stands (already present, R3); open items in CR §9 resolved
by this revision's decisions (`@synthetic` marking — see §5; lookahead-budget wording —
folded into §12.2's rewrite).

---

## 4. Notable normative changes (reader's digest)

Changes a Revision 32 implementer must act on, beyond the CR baseline:

1. Duplicate record fields and map keys are now **rejected** (were last-value-wins).
2. The series has **one severity**: every former warning is an error or is deleted.
3. A value governed by `!!schema` **must be annotated** with its type (validating
   processors).
4. Choice disjointness is a **total, two-valued discrimination-class fact**; `void` is
   not a valid variant.
5. Imports are **transitive**, with identity-based collision rules.
6. Non-productive recursive types are **rejected at schema load**.
7. `!` targets resolve **structure-namespace-only**; generic heads
   **type-name-namespace-only**.
8. Size specifiers and element/position `?` are legal at **every** type-ref position
   (language extension).
9. Annotations are **normatively resolved and validated**; an unresolvable annotation
   name is a resolver error.
10. Every sugar-form application **materialises an entry**; resolver output carries
    synthetic entries.
11. The file extension for the 2026 series is **`.tn`**; `.tn1` is reserved for v1.
12. Constructor declarations (`~`) are valid **only in meta-schemas** (documents whose
    `!!meta` names the meta-kernel); a user schema declaring one is rejected.
13. `!text` joins the Part 1 built-in vocabulary.

---

## 5. Open questions carried by this change log

Adjudicated 2026-08-25. The following remain deliberately open. They live in this change
log only — the specification text carries no open questions.

| Ref | Question | Status |
|---|---|---|
| #12 | Does `!duration` accept ISO 8601's `PnW` week form? | Open — likely revised in a later revision; the §5.4 table is unchanged this revision. |
| #26 | Should the content-hash pin move out of the URI query (fragment, or a structured directive value)? | Open — the query form is kept for now. |
| #34 | UTS #39 restructure of §9.4 (Identifier_Status at the §7.1 profile, scoped comparison sets, on-detection action, quoted names) and its normative level | Open — needs further investigation. |
| #54 | Instantiation-identity comparison of a type argument's literal: token text, denoted value, or normalise-before-compare | Open — deferred to the next revision of the structure-templates design. |

Decisions taken 2026-08-25 (previously open): #9 add `!text`; #11 keep `binary`'s name;
#18b defer the machine-readable contract home (name-dispatch MUST stated now); #19 `~`
only in meta-schemas; #30 declined — annotations are never optional-valued; CR §9 — a new
`@synthetic` kernel annotation marks resolver-materialised entries (name-position, on the
schema-map key, classified derived like `@alias`: discarded and recomputed on ingest).

---

## 6. Artifact inconsistencies to resolve before release

1. **meta-kernel.tn `type_ref`/`type_argument` doc strings** still say inline sugar forms
   "are carried structurally and materialise no entries" — contradicts the resolved
   fixtures (which materialise synthetics) and the accepted #50/CR §11 position. Rewrite
   the two doc strings.
2. **Revision markers and identities.** All six artifacts still carry "2026 Revision 32
   draft" and `/2026/32/` identities. At release: bump to `/2026/33/`, recompute the hash
   chain bottom-up (kernel body → kernel `!!id` digest → meta's pins → meta digest →
   core's pin → core digest), regenerate fixtures.
3. **Fixture placeholder names** (`array_type_name_xxhash`, …) carry a literal `xxhash`
   placeholder — regenerate with real content-derived hashes once §8 canonicalisation
   decisions (default omission, annotation handling) are pinned.
4. **meta.tn `deprecated`/`since`/`todo`/`lang`** must agree with the #30 outcome, and
   Part 1 §2.1's example with both.
5. **core.tn `complex` doc** ("a narrowing may set `component`") must agree with the #27
   facet-kind rule.
6. **`@synthetic` marker** — DONE: `synthetic => @annotation void` added to meta-kernel.tn
   beside the annotation markers; the kernel fixture gains the resolved `synthetic` entry,
   and every synthetic entry's key in the fixtures now carries `@synthetic`. (meta.tn's
   `binary` bucketing (#11) is left unchanged this revision — expected to be revisited
   later.)

**Executed 2026-08-25 (artifacts).** All six artifacts bumped to Revision 33 with
`/2026/33/` identities. The kernel's stale `type_ref`/`type_argument` doc strings are
rewritten to the every-application-materialises model (#50). Fixture conventions notes
updated (no `type_ref`-typed pins; bodies headed by the applied constructor; open
instance-form templates carry `instance_template`; `@synthetic` convention stated).
§5.7's selector-facet rule (#27) refined so a constructor-defaulted selector (e.g.
`complex.component`) may be set while a source-bound one (`int8.size`) is identity-only —
core.tn's `complex` doc now agrees with the rule as stated.

**Post-review corrections (executed 2026-08-25).** Verification against the spec text —
rather than this table — found two accepted dispositions that had not fully reached the
documents; both are now applied. **#15:** the "`data-value` at exactly three points"
sentence stood uncorrected in the P2 §12.1 lead *and* had been added to the §1
introduction, so the overstatement existed in two places; both are rewritten, and the
count is re-derived against #16's executed grammar — the schema grammar now imports
`core-value` at exactly one point (the `instance` payload) and uses the full `data-value`
at none (refinement bodies are braced `record-def`s; field-modifier values are
`( token / absent )`), so §12.1's import list also corrects `data-value` → `core-value`.
**#42:** the warn-level inventory had fully landed (neither document contains a normative
warning), but the property held only by exhaustion — the one-severity sentence the
disposition called for is now stated once, in P1 §8.1 beside the canonical error
phrasings, which P2 §1.3's diagnostics bullet already inherits by reference. Both table
rows stand as accepted; #42's residual was exactly that sentence. The same pass caught one
more instance of the #15/#16 defect class: §5.5/§5.6's prose still called the constructor
payload "the data-value after `!C`" — three occurrences now read "core value", matching
the executed `instance` production. The remaining `data-value` mentions in P2 (annotation
values, §6; the absent sentinel's data-value positions, §7.6) are correct uses of the
[TSON-DATA] production and stand.

**Developer Guide (executed 2026-08-25).** tson-guide.md ships with Revision 33, revised
on two principles: it describes the design as it stands, and it does not hold the full
history of decisions — per-revision history lives in the revision change logs, and the
guide's front matter now says so. §2 "Design History" (fourteen subsections of decision
archaeology) is replaced by §2 "Design Rationale" (seven subsections of standing,
present-tense rationale): the still-true rationale for no comments, no anchors, the
`!`/`^` split, subtraction semantics, elided modifiers, and field groups/labelled sums is
kept in condensed form; the superseded histories (`!!include` deletion, braces
restoration, size-templates design, pin-coherence relaxation, and the now-reversed "type
slots ride the value channel") are removed, with a new §2.7 stating the Revision 33
template/sugar/synthetic-entry model. §7's worked example and §8.1 are rewritten to the
every-application-materialises model (`tags` and `history` now reference `@synthetic`
entries with `_xxhash` placeholder names; the "structural carriage" bullet and the
"materialisation line" essay are gone); §6.1's `void` no longer offers choice variants
(#48); §4.3 notes transitive imports (#55); §8.3's diagnostic example drops the deleted
size templates; identities and extensions are bumped throughout. Guide candidates from
the dispositions (#4, #7, #13) remain open items for a future guide pass — they add
content rather than correct it.

**Hash placeholders (decided 2026-08-25).** Hash *values* are not normative — only the
shape of the data. Source-artifact pins are spelled `?sha256=xxhash` and each source
header documents the placeholder; fixture synthetic names keep the `…_xxhash` suffix and
the fixture notes state that the placeholder is deliberately not a real digest. Real
digests are computed bottom-up at publication (kernel body → kernel `!!id` digest → meta
pins → meta digest → core pin → core digest); nothing in the drafts presents a
plausible-but-stale value that could be mistaken for a verifiable pin.

---

## 7. Out-of-scope items recorded for the Developer Guide

Entries #4, #7, and #13 are implementer guidance, not spec defects; #45's named-partial-
application syntax and #56's "annotations from the declaring schema" question are design
notes for a future revision. **Note:** the Developer Guide (tson-guide.md) was not part
of this revision's input set — confirm whether it ships with Revision 33 (several §2
dispositions reference it, and its §2.11/§8.1 design-history sections are invalidated by
the CR's deletion of the size templates and the every-application-materialises rule).
