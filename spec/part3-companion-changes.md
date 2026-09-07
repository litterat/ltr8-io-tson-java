# Companion Change Note — Part 1 and Part 2 Edits for the Part 3 Repositioning

> **Status:** Editorial worksheet for the Revision 35 cycle · Non-normative · Accompanies the working draft of TSON Part 3: JSON Encoding, as revised against the **Revision 34 implementation feedback register** ("register #N" below). **Reconciled 2026-09-05 against Revision 35 as adjudicated** (see the change log, and the status block below): the lists A–E are kept as written for the record, and the block says what each item became.

## Status against Revision 35

| Item | Status | Where it landed, or why not |
|---|---|---|
| A1 null removal | **landed** | [TSON-DATA] §2.9, §4.1–§4.5, §6; [TSON-SCHEMA] §4.2, §7.3 (change log #7) |
| A2 `members` facet | **landed** | `integer_type.members`, `decimal_type.members`; [TSON-SCHEMA] §7.4, §9 (#24) |
| A3 `@discriminator` | **landed**, choice-based shape | `meta.tn`; [TSON-SCHEMA] §6, checked with the load checks stated (#25) |
| A4 `@rest` | **landed** | `meta.tn`; [TSON-SCHEMA] §6, with the §6 licence sentence (#25) |
| A5 `required_keys` | **deferred**, as proposed | recorded in Part 3 §1.6, §6.5 |
| A6 output/ingest consequences | **none**, as stated | — though the second pass reshaped `type_definition` itself (#5–#8): no `kind`, `parameters` or `disjoint` field; open entries as `!template`; `disjoint` on `choice`. Part 4 §9.3/§9.4 updated for it |
| queued items | **landed** | exclusive temporal bounds, `@title`/`@examples`/`@read_only`/`@write_only` (#26); `contains` withdrawn; `unique_items` on ordered arrays not taken |
| B1 §6 / principle 5 | **landed**, with one difference | the superset claim, exceptions and SHOULD are deleted; §6 is *kept under its title* as a statement of scope naming the JSON reader, so §7's numbering holds; principle 5 deleted, 6→5, 7→6 (#8) |
| B2 `\/` | **landed** | [TSON-DATA] §7.2.2 |
| B3 `\u{…}` | **landed**, as an addition | `\uXXXX` kept beside `\u{1*6HEXDIG}`, one scalar-value constraint, no surrogate pairs |
| B4 NEL/LS/PS rule | **landed** | [TSON-DATA] §7.2.2 |
| B5 BOM; `\b`/`\f` | **landed** / kept | [TSON-DATA] §7.1 |
| B6 series statements | **pending** | "Part N of 2", "two conformance classes", the architecture diagram, [TSON-SCHEMA] §7's "future encodings" — to be edited when Parts 3 and 4 enter the published set (list below) |
| B7 §7.1 media types / header fields | **pending** | cross-references to `application/tson+json`, `application/tson+cbor`, `TSON-Schema` — same batch as B6 |
| B8 §7 intro | **pending** | same batch as B6 |
| B9 null-at-void | **landed** | deleted (#7) |
| B10 §5.4 classes | **landed** | five classes; the "map one-to-one onto JSON's" clause became "every text-class encoding shares them" (#22) |
| B11 §9 table; re-pin | **landed** | re-pinned twice; final pins in [TSON-SCHEMA] §13.2 |
| register #17 (`TSON-Schema`) | **defined in Part 3 §3.5** | the change log carried it open before Part 3 existed; with Part 3 in the series the entry closes there — the log's §5/§8 rows should say so when the set is republished |
| C1 #9 field names | **landed** as adopted | [TSON-DATA] §2.5, §7.7, §8.2 |
| C2 #10 separators | **decided (b)** | a comma may follow a value; trailing comma legal series-wide (#10) |
| C3–C5 #11–#13 | **landed** as recorded decisions | [TSON-DATA] §4.2, §4.4, §2.8 |
| D converter rows | stand | nothing here changed; `X?` conflation is the model |
| D2.1 canonical set order | **declined** for Part 2 (#27) | Part 4 §4.3 states bytewise order as its own rule (§1.6 rule 6) |
| D2.2 enum member order | **declined** for Part 2 (#27) | Part 4 §5.3 assigns packed ordinals by bytewise member order, independent of resolver output (§1.6 rule 7) |
| D2.3 nameless internal entries | recorded | [TSON-SCHEMA] §8.2's internal-name rules stand; the second pass's #5 confirms open entries never reach a data stream |
| E greps | run | see the change log; the B6–B8 targets are the remaining hits |

**Part 1 / Part 2 edits still owed for the encodings to enter the series (B6–B8):** [TSON-DATA] §1.3's two-part series description and the architecture sketch; "Part 1 of 2" / "Part 2 of 2" → "of 4"; §1.5's "two conformance classes" → four, Classes 3 and 4 defined in Parts 3 and 4; §7.1's media-type paragraph gaining `application/tson+json`, `application/tson+cbor` and the `TSON-Schema` / `TSON-Accept-Schema` fields by cross-reference; §10.2's series references gaining Parts 3 and 4; [TSON-SCHEMA] §1.3's class count, §7's intro ("future encodings" → Parts 3 and 4 by name), §13.2's series references. None of these is normative for the encodings themselves.
> Five lists: **(A)** changes Part 3 normatively depends on, **(B)** edits the repositioning requires — now including the register's #8 deletions, **(C)** register decisions adopted or recorded, **(D)** converter consequences of the null removal, **(E)** consistency greps. Where this note and the register state the same change, the register's entry carries the argument and this note carries only the landing shape.


## A. Required changes (Part 3 dependencies — Part 3 §1.6)

These must land before Part 3 can be finalised. Suggested landing shapes, chosen so that **no new schema-grammar syntax is needed** — every addition is a kernel/meta vocabulary field reachable through the existing explicit constructor forms; whether any deserves sugar is a separate Part 2 call.

1. **The null removal** (register #7, with #8's consequences in B below — adopted). Part 1: delete §4.1; §4.5's order becomes boolean → number → string; drop the "distinct from … null" clauses in §2.9 and §4.4 and the "use quotes" sentence; §7.7 rule 3 then holds without qualification. Part 2: `value` (§4.2) admits boolean, integer, float, and string; `void`'s parenthetical and the §7.3 null-at-void concession paragraph go, `void` admitting `_` alone; §5.4's `(T | void)` rationale loses the absent-versus-null clause; §9's restatement goes. Nothing lexical moves — `null` was never a token class, so the frozen-lexer claim holds and the unquoted token `null` is a string, as `frobnicate` is. **This supersedes the mapping report's null-atom proposal (§E #4 there)**: JSON's null maps to absence at the reader (Part 3 §7), not to a value in the model; there is no sixth discrimination class, and no core `null` type. Register #7's own text is the drafting source — its wording covers every clause.
2. **Numeric member-set facet** (revised — supersedes literal-valued enums; the earlier "widen `enum_set` without losing §7.4's guarantees" design-work item is *dissolved*, not solved). `enum_set` keeps `element_type: identifier` and §7.4's member semantics stand verbatim; the gap (sparse numeric value sets) is filled by a `members` facet on the exact numeric constraint vocabularies — `members: [integer]?` on `integer_type` (kernel; the same already-queued category as the exclusive-bound/`multiple_of` completions), `members: [value]?` on `decimal_type` (meta; `value`-typed as its bounds are, per the bootstrap rule). Tightening is §5.7's existing member-set kind, shrink-only; identity is value equality under the atom's contract (`0x50` = `80`); the approximate tier is excluded on `multiple_of`'s grid rationale. A `numeric_enum` *constructor* was considered and declined: construction creates siblings, so it would mint a family with no IS-A into the numeric tiers and no facet composition, where `!integer ^ { members: [80 443 8080] }` inherits both. Converter: integer enums → member-set refinements; mixed-scalar enums → a choice over an identifier enum and a member-set numeric, disjoint by class and therefore tag-free in every encoding — better structure than the source, in the established strictness pattern. Nothing in Parts 3/4 consumes this; both have shed their literal-enum encoding clauses.
3. **Discriminator annotation** (`meta.tn`, **not** the kernel — revised from this note's earlier `choice.discriminator` shape, on the same classification rule as A4): `discriminator => @annotation field_name`, placed on the choice declaration (`@discriminator:pet_type (cat | dog)` — the sugar survives). The mapping mechanism is plain Part 2 already — the REQUIRED_FIXED pins (`fox => animal & { type: = fox }`) carry it, derived, drift-proof — and the annotation supplies only what pure derivation cannot: unambiguous selection where several pins qualify (derivation-only would couple the wire to unrelated pins — the well-known-field-name objection in structural clothing), and the load-time checks (REQUIRED_FIXED in every variant, never REQUIRED_DEFAULT, pairwise-distinct values), on `@disjoint`'s verified-or-error precedent. Force is confined to the member-dispatching encodings (JSON; CBOR tagged) under the §6 licence; **the text-side `!variant` demotion and cross-encoding materialization are dropped** — text keeps its native selector and its injection route, honouring the report's own "native TSON needs none of this," and materialization becomes each dispatching encoding's own rule. Parent-declared placement is deferred (the converter emits explicit choices; additive-safe).
4. **Rest-field annotation** (`meta.tn`, **not** the kernel — revised from this note's earlier `record.rest` shape): an annotation type `rest` (bare, void-targeted) placed on the designated field. Load checks (target resolves to `{text => X}`; at most one per composed chain) run on `@disjoint`'s precedent. Rationale for the demotion from kernel attribute: after Part 4's no-flatten decision the designation has force in the JSON encoding alone, making it single-encoding projection metadata — the principled line being *kernel for cross-encoding semantics (the discriminator), annotation for single-encoding metadata (rest)*. The well-known-field-name alternative was considered and rejected: it is a soft reserved word, against §7.7 rule 3 and the Revision 35 direction of deleting magic words, and it fails silently on innocent same-named map fields. Part 2 §6 gains the licence sentence Part 3 §1.6 states (annotations never carry force in the model or their own encoding; an encoding-rules document may bind projection behavior to a schema-side annotation declared for it). Declared names win; flatten/collect stays Part 3 §6.2's.
5. **`required_keys` on map — deferred** (revised; was the cycle's last kernel-constructor edit). Not redundant with existing machinery — per-entry key presence, `map.state` (entry values), and size bounds all govern entries that *appear*; this facet was the membership axis, "an entry with key `host` MUST exist" — but the rest field's landing covers its motivating pattern better: named-and-required keys become declared record fields beside an `@rest` tail, wire-identical in JSON, and the report's map line sharpens to *maps are homogeneous, full stop* — a schema that names a key has declared structure, and structure is what records are. Residue (non-identifier or non-text required keys — `"48x48"`, a required date entry) is narrow and converts drop-with-report, loudly, per the strictness policy; the facet is additive-safe to land later with corpus evidence, the `sealed` posture. **With this deferral the cycle makes no kernel-constructor changes at all**; the sole remaining kernel edit is A2's `members` facet on `integer_type`, constraint vocabulary in the same class as the bounds beside it.
6. Resolver-output and ingest consequences: none remaining. #3 and #4 ride the existing author-annotation preservation channel (§8.1 already keeps declaration and field annotations through output and ingest); #5 is deferred; A2's facets are ordinary constraint vocabulary.

Still queued from the mapping report, needed by nothing in Part 3: `contains`/`min_contains`/`max_contains`, exclusive-bound/`multiple_of` completion, `unique_items` on ordered arrays, the `@title`/`@examples`/`@read_only`/`@write_only` annotations. Land on their own schedule.


## B. Required edits (the repositioning, folded with register #8)

**Part 1**

1. **Delete §6 and principle 5** (register #8, adopted — deletion, not the demotion an earlier draft of this note suggested). Replace the JSON note under [TSON-SCHEMA] §9 with a statement of scope: a JSON document is read through the JSON encoding ([TSON-JSON]), which maps JSON `null` to absence and JSON numbers to `number`, and is not a TSON text document. §1.1's "every valid JSON document … is already valid TSON" sentence goes with it. Keep, per #8's own "what should stay" list: `"`-strings, `[ ]` arrays, `{ name: value }` records, the `\n \r \t \\ \"` escapes, base type resolution as a mechanism, `number` as the bare-numeric type. The notation stays JSON-*like*; what goes is the superset claim and the rules only it required:
2. **Drop the `\/` escape** (§7.2.2) — the table's own "(JSON compat)" label is the whole case.
3. **Replace `\uXXXX` + surrogate pairs with a scalar escape `\u{…}`** (§7.2.2; §6 exception 2). Deletes the three unpaired-surrogate MUSTs outright: well-formedness becomes a property the grammar cannot violate. A lexer change — permitted by principle 7 during the revision series, and it wants doing in the same revision as the rest of this list, before anything publishes against the frozen-lexer claim.
4. **Restate §6 exception 1 as §7.2.2's own rule** (raw NEL/LS/PS in single-line tokens) — without §6 it is not an exception to anything.
5. **Restate byte-order-mark acceptance under §7.1's own authority** (an encoding courtesy, not JSON compatibility), and review `\b`/`\f` with the escape table in front of you.
6. **§1.3 / §10.2 series statements** — "Part 1 of 2" and the two-part list become three-part; the architecture diagram gains the encodings axis (text = notation + reference encoding; JSON = second encoding, Part 3). §1.5's "two conformance classes" becomes three (Class 3 defined in Part 3).
7. **§7.1 media-type paragraph** — cross-reference `application/tson+json` (registration intent lives in Part 3 §3.1), and the `TSON-Schema` / `TSON-Accept-Schema` field registrations (Part 3 §3.5).

**Part 2**

8. **§7 intro** — "future encodings (a JSON encoding, for instance)" becomes a present-tense reference to Part 3 as the first non-reference encoding-rules document.
9. **§7.3 null-at-void concession** — deleted by A1; the JSON-shaped-data motivation now has Part 3 §5.7/§7.
10. **§5.4** — the class table stays at five (no null class — A1); the "map one-to-one onto JSON's" clause points at Part 3 §4.2, which states the mapping, the null-kind-is-absence rule, and the two leaks (float specials, pairs-form maps). No tagging-paragraph change: with A3's revised shape the text encoding's tag rule stands as written — `!variant` remains required at non-disjoint choices, discriminated or not.
11. **§9 meta-layer table** — no constructor rows: meta's new annotation types (`rest`, `discriminator`) and A2's `members` facets (`integer_type` in the kernel, `decimal_type` in meta); companion artifacts re-pinned (hash cascade per the bottom-up pinning note).

**Closed by Part 3 directly:** register **#17** — the `TSON-Schema` / `TSON-Accept-Schema` fields are defined in Part 3 §3.5, carrying all four of the entry's points (sf-string not sf-token; the §2.2.1-style agreement/conflict rule; RFC 9110 §16.3 provisional registration, no `X-`; sender's claim, never receiver's instruction). The consuming HTTP project's `TSON-Schema` implementation becomes a spec citation when Part 3 lands.


## C. Register decisions adopted or recorded (the #9–#13 set)

1. **#9 — field names are identifiers at every layer: adopt** (the register's own recommendation, and this note's). `field-name` keeps its two spellings; the decoded text matches §7.7 as an annotation name's does. All deletions: §2.5's "lexical" paragraph, §7.7's carve-outs, §8.2's field-name split. The diagnostic for a non-name key says what the author wants: a map. This also tightens Part 3's ground: its `$`-reservation argument (§3.2) already assumed record member names are identifier-shaped, and now every layer agrees.
2. **#10 — separators: decide.** The register prefers (a), drop the comma (whitespace is the separator; the trailing-separator rule dissolves; principle 4 reached by removing a rule). (b), keep the comma and permit trailing, is the lesser edit. Either beats the current shape; the choice reaches [TSON-SCHEMA] §12.1's separators too. Not taken here — an authoring-ergonomics call that is yours.
3. **#11 — keep `true`/`false`**, and say why in §4.2 in the register's terms: a boolean is a value a Class 1 read genuinely produces, and `true` vs `"true"` is the same distinction as `42` vs `"42"` — TSON's own, not JSON's. §4.5's order becomes boolean → number → string.
4. **#12 — near-miss numerics: record the decision in §4.4** either way; the register recommends keeping fallthrough ("not by default") and saying it is deliberate.
5. **#13 — brace sharing: keep**, recorded as decided rather than inherited, per the register's own entry.

Register entries outside Part 3's blast radius (#1 duration weeks, #2 hash placement, #3 open container templates, #4 open entries in §8 output, #5/#6 name-hygiene scopes and data version, #14–#16 policy reporting and artifact, #18 type_ref sugar in data, #19 namespaces, #20 ungrounded parameters) adjudicate on their own tracks. One interaction worth noting: whichever way **#2** goes (query vs fragment vs structured pin), Part 3 inherits it wholesale — `$schema` and the `TSON-Schema` field carry "a schema reference as [TSON-DATA] §2.2.1 defines one", and §3.5's sf-string choice survives all three forms (a fragment's `#` is no more a Token character than `?`).


## D. Converter consequences of the null removal

The JSON Schema → TSON mapping report's nullability rows change shape:

- `type: "null"` alone → `void` (a field whose only value is absence).
- `type: [X, "null"]` → `X` at an absence-admitting position: `x: X?` fields, `[X?]` elements, `{K => X?}` entry values. The report's old warning that `X?` "conflates absence with null" dissolves — the conflation is now the model, deliberately (register #7).
- **The residual corner is real and must be drop-with-report:** a source schema requiring a property *present* but nullable (`required: [x]` + nullable `x`). TSON's OPTIONAL admits omission the source rejects, so a bare `X?` mapping would *weaken* — the one direction the converter's invariant forbids. Report it; where the presence-carries-meaning intent is genuine, the labelled-group or enum-member modelling (Part 3 §7.3) is the strict translation.
- Heterogeneous `enum`s containing `null` map to the enum minus `null` at an absence-admitting position, same report obligation when the position cannot admit absence. Integer enums map to member-set refinements and mixed-scalar enums to class-disjoint choices, per A2's revised shape.


## D2. Part 4 (CBOR encoding) — additional dependencies on Part 2

Added with the Part 4 working draft; small, but both touch [TSON-SCHEMA] §8's serialization conventions and want landing in the same pass as A:

1. **Canonical set ordering becomes normative.** §7.5 currently leaves set element order implementation-defined and asks fixture tooling to canonicalise. Deterministic bytes (Part 4 §4.3) need the rule in the spec: bytewise-ascending order of elements' canonical encodings, adopted for resolver-output serialization too so the encodings' canonical forms agree — and it retires the fixture-tooling caveat.
2. **Enum member order becomes significant in resolver output.** Part 4's packed profile encodes enum values as member ordinals in source declaration order, which requires that order to survive resolution as canonical — a tightening of `enum_set`'s unordered reading for the output artifact only. With A2's revised shape leaving §7.4 untouched, this is now the only enum-related edit in the cycle.
3. **Recorded, not requested:** Part 4 §9.3's decision that internal entries are *nameless* on the binary wire (ordinals per stream, structural identity across streams) means no canonical synthetic-name scheme and no normative name-hash algorithm is needed anywhere in the series — worth a sentence in §8.2 when it is next edited, so the non-normative status of internal names is stated as load-bearing rather than incidental. Register #3/#4 adjudication should read Part 4 §9 first; the definition-item shape answers #4's "what does an open entry look like in output" for the binary case (it never appears — the excerpt is the closed slice).


## E. Consistency greps once A–B land

- Part 3 §1.6's gating note deletes; §6.2 and §8.4 shed their dependency caveats.
- The mapping report retires: §E superseded by A above (with the null atom explicitly *not* landing), encoding obligations by Part 3; its §B–§D tables reseed the converter docs with D's corrections applied.
- Grep targets across the series: "two conformance classes", "Part N of 2", "superset", "future encodings", "null" (every Part 1/Part 2 hit is either A1's deletion list or the boolean-enum kernel line), "§7.3" citations of the void concession, "requiredKeys", `\uXXXX`, `\/`.
