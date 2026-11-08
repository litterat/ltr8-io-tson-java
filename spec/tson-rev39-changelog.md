---
title: "TSON 2026 Revision 39 — Change Log"
against: "TSON 2026 Revision 38 (Working Draft)"
status: "Adjudicated 2026-11-08. The six normative artifacts and five resolved fixtures are at `/2026/39/` identities, each pin verified over the shipped bytes and listed in Part 2 §13.2 (§6). Part 1, Part 2, Part 3 and the Developer Guide are revised against them in the order §3 lists; Part 3 arrived partly consumed and is completed in a second pass (§7); an independent verification pass over the four documents, the register and the artifacts is recorded in §8, and the implementation's own (§9) once run."
inputs:
  - "SPEC-FEEDBACK.md (24 entries, renumbered against Revision 38; all new — Revision 38 closed every entry open against Revision 37)"
  - "Revised companion artifacts at /2026/39/ identities: meta.tn (the kernel), numeric.tn, temporal.tn, net.tn, core.tn, policy.tn and five resolved fixtures (implementing #3, #4, #5, #6, #7, #8, #9, #10, #11, #12, #13, #14, #15, #16, #17, #18, #19 and #21)"
  - "tson-part3-json.md, edited directly as findings arose (carrying the Part 3 halves of #6, #8, #11, #12, #13, #14 and #21)"
---

# TSON 2026 Revision 39 — Change Log

This document records the changes accepted into Revision 39 of the TSON specification
series, adjudicated from the implementation's spec-feedback register (24 entries,
renumbered from #1 against Revision 38, all raised since Revision 38 closed every entry open
against Revision 37). SPEC-FEEDBACK.md remains a record against Revision 38 and is not
modified by this revision; each of its entries receives a disposition here.

Provenance markers used in the disposition table:

- **[settled]** — the resolution was settled by the spec author inside the feedback
  register itself, is already implemented in the revised companion artifacts shipped with
  this revision, or was decided by the spec author at adjudication.
- **[open]** — a genuine design decision deliberately left open. Collected in §5.
- **[editorial]** — a sentence the revision editor had to write that no register entry
  dictates, or a reading the editor took where the register admits more than one. Each is
  the spec author's to confirm; collected in §5 beside the open questions.

**This revision does not change the lexer.** [TSON-DATA] §1.3's lexer-freeze paragraph
stands as Revision 38 left it: `<` and `>` were already single-character special tokens, a
qualified name was already one unquoted token, and a sibling reference (`kind!`, #21) is an
unquoted token followed by an adjacent `!`, which the lexer already emits as a special token.
Every change below is in what the grammars make of tokens the lexer already produces.

It does change the **data grammar at four positions**: `<` and `>` gain a grammar role in
data values, a reference carrying an argument list being a core value (#13); `!` gains a
postfix role, a sibling reference (#21); a type annotation and an annotation name are
qualified names (#14, #15); and the `scoped-value` production is gone, so a directive at a
value position is a parse error (#6). It changes the **document header** for both document
kinds: a header is an optional `!!id` followed by any number of `!!import`s, `!!meta` and
`!!schema` are parse errors naming their replacement, and a document is a schema document
exactly when its root is tagged `!schema` (#8).

What this revision changes most is the **schema chain, and with it the kernel**. The ladder
of governing relations — `!!schema` → user schema → `!!meta` → meta-schema → meta-kernel —
and the four rules that kept each document on one rung are replaced by **one namespace per
schema**: a schema's own entries and every entry of its import closure, in which every name
resolves (#7). The two pre-loaded documents become one, `meta.tn`, the kernel, whose root
holds the thirteen names an author writes bare and whose machinery sits under six nested
schema maps named by path — `meta.record`, `unicode.text_type`, `numeric.integer_type`,
`net.iri_type`, `binary.bytes_type`, `atom.ordering` (#14, #15). Three data-format
libraries stand beside it, `numeric.tn`, `temporal.tn` and `net.tn`, each importing the
kernel alone; `core.tn` imports the three and declares the author annotations; `policy.tn`
imports the kernel alone. Within the kernel: an entry is a type exactly when its value is
built by a constructor IS-A `meta.type`, and any other entry is a value (#9); `namespace`
is a refinement of `map` (#9); one atom, `meta.reference_type`, replaces `kind_type` and
`ref_type`, and an application is a value of it (#11, #12, #13); a record template is its
own constructor, `meta.record_template`, its fields labelled (#19); a field may be typed
by a sibling field of its own record, `value?: type!` (#21); `value` and `value_type` are
gone, every facet typed by its family's own instance (#17, #21); every count is a
`numeric.count`, the non-negative half of `int64` (#16); `scoped` and the scope push are
gone, `meta.declared` being the open sum (#6); and resolution within a schema runs in a
stated wait order (#4). Every bundled pin changes, and the artifact set changes shape.

---

## 1. Baseline: the revised artifacts

Eighteen of the register's proposals arrived already implemented in the companion
artifacts, and Revision 39 adopts them as its baseline. Summarising the normative effect:

- **#3 — the emitter's form at a disjoint choice.** The schemaless writers quote every
  host string and write every number and boolean bare, so a value this implementation
  writes round-trips through a disjoint choice to the variant it came from. Text-only
  otherwise ([TSON-DATA] §2.4; [TSON-SCHEMA] §5.4).
- **#4 — resolution order within a schema.** Rules 1–7: reading a value against a type
  waits on the type being ready and on each type a part of the value is read against; a
  reference orders nothing; a held value orders nothing; a wait cycle is a resolver error;
  every order consistent with rule 1 gives one output and source order is not consulted;
  everything closes at load; `max_wait_depth` is a limit, default 64. The kernel's one
  cycle is the 25 entries holding `meta.record`, pre-loaded; every other entry is read by
  the rules. The kernel's `meta.annotation` is `void`'s alias and annotates nothing.
  (Part 2 §3.4, §3.4.1, §5.10.1, §6, §11.5; meta, policy.)
- **#5 — `!` is an instance and `^` refines under the type's rules** — implemented for
  construction, atom refinement, record refinement and the kernel's own refinements of
  the reference and map constructors (`type_ref => !field_type_ref ^ { allow_sibling:
  false }`, `identifier_ref => !type_ref ^ { of: unicode.identifier_type }`, `namespace =>
  map ^ { … }`). The container constructors' tightening rules are each that constructor's
  to state. (Part 2 §5.5, §5.7, §12.1; meta.)
- **#6 — the scope push is gone.** The kernel declares `meta.declared_type => sum & {}`
  and `meta.declared => !meta.declared_type {}`; `scoped`, `scope_kind`, `scope_set`,
  `schema_identity`, `extern`, `dynamic`, `extern_of` and `extern_type` are in no bundled
  schema; a directive at a value position is a parse error; the foreign-schemas limit is
  gone. (Part 1 §2.3, §3.3, §7.4, §9.1; Part 2 §7.1, §7.8; meta, policy.)
- **#7 — one namespace per schema.** A schema's namespace is its own entries and its
  import closure; every name resolves there — `!` heads, refinement sources, type
  positions, a reference's `of`, a bound, an annotation. Placement, direction of service,
  the import-level rule, governing-target eligibility, the structure/type-name split and
  the sibling allowance go. `core.tn` imports `numeric.tn`, `temporal.tn` and `net.tn` and
  declares no copy of any kernel entry. (Part 2 §2.2.2, §2.2.3, §3, §4.2, §6, §9; all
  artifacts.)
- **#8 — one directive, and the root says the kind.** A header is `[!!id] *!!import` for
  every document; a document is a schema exactly when its root is tagged `!schema`; a data
  document imports as a schema does, several imports read under one namespace; a resolved
  form carries its source's header and nothing is supplied at ingest; the kernel's header
  is its `!!id` alone. (Part 1 §2.2, §3.3, §7.1, §7.4; Part 2 §2.2, §3.4, §7.1, §8.1,
  §12.1; all artifacts.)
- **#9 — value entries are ordinary values; `namespace` refines `map`.**
  `namespace => meta.map ^ { key_type: meta.identifier_ref  voidable?: = false }`;
  `schema => !meta.namespace { key_type: meta.type_name  value_type: meta.declared }`;
  `field_map => !meta.map { key_type: text  value_type: meta.record_field  ordered: true }`,
  a plain map keyed by text that no qualified name enters; an entry whose value is an
  instance of an ordinary type is a value, marked by nothing but its tag, and a schema
  may hold `create_order => !operation { … }`. (Part 2 §4.1, §4.2, §5.12, §7.4, §8.1;
  meta.)
- **#10 — the alias body is `meta.alias`**, `!meta.alias { target: T }`, and "reference"
  keeps one meaning, a name held in a slot. `max_alias_chain` replaces the reference-chain
  limit. (Part 2 §4.1, §8, §11.5, §12.1; meta, policy.)
- **#11 — one reference atom.** `meta.reference_type => meta.atom & { of: meta.type_ref
  allow_sibling?: boolean ~ true }` admits a name exactly when the named entry's value,
  past its alias hops, has a type IS-A `of`; `meta.ref<T>` is its one spelling; a slot
  over a constructor holds a type's name and any other a value's qualified name; a
  constructor is an ordinary record type at every name slot; `members` is derived and
  never written. `kind_type`, `ref_type` and core's `kind` are gone; `type_kind` and
  `identifier_kind` are `meta.type_ref` and `meta.identifier_ref`. (Part 2 §4.1, §4.2,
  §5.12, §7.4, §8.1; meta.)
- **#12 — an application is a reference value.** The `type_ref` and `type_argument`
  records are gone; `alias.target`, `type_chain.parents` and `meta.application` are typed
  `meta.type_ref`; an open application is written unquoted in resolved output,
  `!meta.alias { target: pair<uuid B> }`; identity is the parsed form. (Part 2 §4.2,
  §5.10, §8.1, §8.2; meta, fixtures.)
- **#13 — a reference in data is grammar.** `core-value` gains `reference = qualified-name
  "<" ws type-args ws ">"`, `<` adjacent to the name; a quoted token is never a reference;
  a schemaless read keeps an application as a structured value. (Part 1 §4, §7.2.5, §7.4,
  §7.5; Part 2 §12.1, §12.3; Part 3 §5.6.)
- **#14 — every `!schema` value declares, from the root.** `geo => !schema { … }` is a
  schema map nested as an entry whose members are entries named by path, `geo.address`;
  every name resolves from the root; a map of one path declared by several documents is
  the union of their members; a tag is `"!" qualified-name`; synthetics mint at the root.
  (Part 1 §4, §7.4; Part 2 §2.2.3, §4.1, §5.12, §12.1; all artifacts.)
- **#15 — the roots hold the common vocabulary.** The kernel's root: `void`, `boolean`,
  `integer`, `int64`, `text`, `bytes`, `iri`, `iri_reference`, `regex`, `doc`, `enum`, `set`
  and `schema`; its machinery under `meta`, `atom`, `numeric`, `unicode`, `net` and
  `binary`. `numeric.tn`, `temporal.tn` and `net.tn` each declare their instances at the
  root and their constructors under a map of their own name; `core.tn` declares the author
  annotations; `policy.tn` declares `policy`. An annotation name is a qualified name
  (`@atom.ordering:TOTAL`, `@meta.disjoint`). (Part 1 §3.1, §5.5, §5.6, §7.4; Part 2 §3.4,
  §4.1, §9, §13.2; all artifacts.)
- **#16 — a count is a fixed 64-bit range.** `int64` at the kernel's root, `numeric.count
  => !int64 ^ { min: 0 }` typing every count facet; `integer_type.multiple_of` and
  `integer_size.bits` are `integer`, positive by coherence; `policy.limit => !int64 ^ { min:
  1 }`. (Part 1 §9.1; Part 2 §5.3, §5.10, §5.11, §9, §11.5, §13.2; meta, numeric, policy.)
- **#17 — a facet is typed by its family's instance.** `decimal_type`'s bounds, step and
  members are `number`; `float_type`'s bounds are `number`; `rational_type`'s are
  `rational`; each temporal constructor's are its own instance; the network lists are
  `[cidr4]` and `[cidr6]`. Nothing in the libraries names `meta.value`. (Part 2 §4.2,
  §5.2, §5.5, §7.4, §13.2; numeric, temporal, net.)
- **#18 — a restated field's type narrows by IS-A.** (Part 2 §5.7, §5.8.)
- **#19 — a record template is its own constructor.** `meta.record` is closed;
  `meta.record_template` carries `parameters: [meta.template_param; 1..]` and
  `fields: meta.record_template_field_map`, whose fields label a whole parameter as
  `type_param` or `value_param`; a literal value needs a closed type; a parameter may not be
  spelled like a name written among an absorbed field type's arguments. (Part 2 §1.3,
  §4.2, §5.7, §5.8, §5.10, §8.1, §13.2; meta.)
- **#21 — dependent fields.** `kind!` is a sibling reference, data grammar and schema
  grammar alike; `meta.reference_type` carries `allow_sibling`; `meta.field_type_ref`
  types a record field's `type`; `record_field.value?: type!`. (Part 1 §2.5, §4, §7.2.5,
  §7.4, §7.5; Part 2 §4.2, §5.2, §5.7, §5.8, §5.11, §5.12, §8.1, §12; Part 3 §5.6, §6.1.6;
  meta.)

The artifacts also carry housekeeping the register did not raise, adopted here on the same
footing:

- **`uuid`, `uri`, `uri_reference` and `email`, with `uuid_type`, `uri_type` and
  `email_type`, are `net.tn`'s** (`net.uuid_type`, `net.uri_type`, `net.email_type`), not
  core's; `iri_type` stays the kernel's, as `net.iri_type`, with `net.scheme_set` and
  `net.scheme_name` beside it. Part 1 §5.5's library column follows.
- **The kernel's `iri` refines its `iri_reference`** (`iri => !iri_reference ^ {
  allow_relative: false }`), so `iri` IS-A `iri_reference` as `uri` IS-A `uri_reference`.
- **The classifying annotations are `atom.ordering`, `atom.bounded`, `atom.exact` and
  `atom.numeric`**; `meta.disjoint`, `meta.synthetic` and `meta.application` are the
  kernel's; the author annotations `deprecated`, `title`, `comment`, `examples`,
  `read_only` and `write_only` are core's; `doc` is the kernel's root.
- **`meta.declared` carries no classifying annotations**, being no atom.
- **The resolved fixtures' conventions note** is rewritten for the new shape: a map
  nested and tagged `!meta.schema`, a record template as `!meta.record_template`, an
  application written unquoted, `type_param`/`value_param`, `@meta.synthetic` and
  `@meta.application`.
- **`policy.tn`'s record of the token policy is named `policy.script_policy`**, its field
  on `policy.processor` `token_policy`. Part 1 §8.2 keeps "token policy" as the policy's
  name; the record's name stands as received. **[editorial]**

---

## 2. Disposition summary

| # | Entry (abridged) | Disposition |
|---|---|---|
| 1 | A closed refinement source: "mints nothing" against "IS-A the source" | **Accept (a).** A refinement source is a trigger position: the application is materialised, or unified with the declaration that owns it in either declaration order, and the refinement IS-A the entry it denotes, as §5.7 promises. §8.2's trigger list gains refinement sources, and its operand sentence narrows to composition operands, which contribute fields and their head's ancestry and owe no edge to themselves (§5.8). Order independence is what §8.2's content identity is for; the declaration-order dependence the register reports is to be closed by the implementation. **[editorial]** — the register leaves (a)/(b) to the author. |
| 2 | An alias of a schema-namespace template: which namespace its value parameters read in | **Closed by #7.** With one namespace per schema there is no structure namespace for a form to select: a written type on any value parameter resolves in the schema's namespace, and is held to the ordinary rule that it IS-A the type the uses derive (§5.10) — `<N: int8>` over a `count`-typed slot is refused for `int8` not being a `numeric.count`, and over an `integer`-typed default admitted. §5.10's "a value conforms to where it is defined" paragraph is rewritten to say so; the alias refusal the register reports is to go. [settled] |
| 3 | At a disjoint choice the form selects the variant, with no emitter rule | **Accept.** §5.4's tagging paragraph gains the twin sentence: at a disjoint choice an emitter writes each untagged value in the form of its variant's class — a text value whose content would resolve to another class quoted, a boolean or number variant's value bare — and a tag is never wrong. [TSON-DATA] §2.4 gains the clause after "consulted exactly once": at a disjoint choice that consultation selects the variant, so the form an emitter chooses there is its tag. No reader rule changes. [settled — implemented] |
| 4 | Resolution order within a schema is unstated | **Accept — the baseline above.** Part 2 §3.4 states the pre-loaded set as rule 4's; §3.4.1's Pass 2 runs in rule 1's order and its forward-reference sentence is replaced by rule 5; §5.10.1's cycle sentence cites rule 4; §6's "one exception is structural" goes; §11.5 gains the wait-depth row. Two things are not running (§6): a resolved form is read under its source's imports rather than over its own entries, and a constructor composing a base kind directly is a new form this implementation does not back — a processor's extension point, not a rule of the series. [settled — implemented] |
| 5 | `!` marks an instance, and `^` refines any value under its type's rules | **Accept.** §5.5: `!C { … }` is a value of `C` in a schema as in data, and whether the entry is a type follows from `C`'s chain; §5.7: `^` refines a value under the rules its type states, field by field, record refinement being the rule with `meta.record` as the type and atom refinement with an atom constructor; a type stating no rule for a field is refused at `^`, naming the type, and a namespace value is refused. §12.1's `atom-refinement` head generalises to any value whose type states refinement rules, with no new token. Kept out, as the register asks: a type as the right operand, a parameterised refinement, and the container constructors' tightening rules. [settled — implemented] |
| 6 | The scope push names a foreign type in data | **Accept — the baseline above.** [TSON-SCHEMA] §7.8 reduces to `declared`, stated of `meta.declared_type`; §7.1 loses the scoped-value paragraphs; §4.1's sum bullet names `choice` and `declared_type`. [TSON-DATA] §2.3 has one value rule; §2.5–§2.7 lose their directive sentences; §2.1's example its field-level `!!schema`; §9.1 its foreign-schemas row. **A language change:** a `!!schema` on a value is a parse error. A foreign type is used by importing its schema; no external form is proposed. [settled — implemented] |
| 7 | One namespace per schema: the ladder's rules go | **Accept — the baseline above.** §2.2.2 goes with `!!meta`; §2.2.3 loses the level rule; §3 is rewritten around one namespace, §3.3.1 keeping the applicability rule (IS-A `meta.top`) and the desugar targets; §3.3.3 resolves an annotation in the annotating document's namespace; §3.3.5 goes; §3.4's "not automatically available as type-refs" goes. **A language change:** every name in a closure is reserved — a schema importing core reserves the roots and the seven map names, and `record`, `identifier`, `field_name`, `bounded`, `value`, `kind` and `core` are free. [settled — implemented] |
| 8 | One directive, the root says the kind | **Accept — the baseline above.** [TSON-DATA] §2.2's header is `[id-directive] *import-directive`, the directive table `id` and `import`, and a document a schema when its root is tagged `!schema`; §7.1's classification reads the header, the root's leading annotations and its tag, never the root's value — which is wider than Revision 38's "two directives of lookahead" by the annotations that stand before the tag, and is the reading adopted. [TSON-SCHEMA] §2.2.3 states imports for every document, every target a schema document, two pins of one identity refused; §3.4 the kernel's header; §7.1 a data document's `!!import`s; §8.1 a resolved form's header; §12.1 `schema-document`. **Language changes:** `!!meta` and `!!schema` are parse errors naming `!!import`; a schema document's root is tagged `!schema`. [settled — implemented] |
| 9 | Value entries are ordinary values; `namespace` builds map types | **Accept — the baseline above.** §4.1: the kinds are `atom`, `product` and `sum` under `type`, beside `alias` and `template` under `top`; `namespace` is a refinement of `map`, `key_type` required; an entry is a type exactly when its value is built by a constructor IS-A `type`, any other a value; `schema`'s `value_type` is `declared`; `field_map` is an ordered map keyed by `text`. §4.2 the membership of a namespace type; §5.12 a further segment selects a key of a namespace value or a field of a record value, and no segment selects a field declaration; §7.4 and §8.1 the membership judgement by document kind. **§5.7's constructor-refinement exception goes**: no bundled constructor refinement replaces a pin (`set_type`, `namespace` and `enum` each move a default or a required field to FIXED, which §5.7 admits), and with no meta level there is nothing for the exception to be about. **[editorial]** [settled — implemented] |
| 10 | The alias body is `alias`; a reference is a name in a slot | **Accept — the baseline above.** §4.1's fourth entry kind is Alias; §8.3 is "Aliases"; every "reference chain" is "alias chain" (§4.3, §5.2, §5.4, §5.7, §5.10, §5.12, §6, §7.2, §8.1–§8.3, §11.5); "reference template" is "alias template". [settled — implemented] |
| 11 | One reference atom, and a constructor is a record type | **Accept — the baseline above.** §4.2: one atom, its one spelling, `of` any type or constructor of the writer's namespace, a slot's meaning from `of`; `type_kind` and `identifier_kind` renamed; `members` no longer written. §4.1: a constructor is a type like any other at a name slot, its declaration a record. §5.12: "the rule does not reach a data document" goes — a name at a `meta.ref<T>` slot in data is judged by the same walk over the namespace its imports bind and delivered with its referent, and a field's default or pin at one is a name judged at link. §7.4, §8.1: "a kind-typed position" reads "a position typed by a reference over a constructor". Unchanged: a bound refuses a constructor. [settled — implemented] |
| 12 | An application is a reference value | **Accept — the baseline above.** §4.2, §8.1, §8.2, §5.10 as the register proposes; `@meta.application` states the application as written, nested arguments unreduced. **Open — one remainder** (§5): whether an expansion states `@meta.application` too. [settled — implemented / open] |
| 13 | A reference in data is grammar | **Accept — the baseline above.** [TSON-DATA] §7.2.5: `<` and `>` join `!` and `@` as the characters with a grammar role in data values; §7.4 gains `reference`, `type-args`, `type-arg` and `qualified-name` as the register writes them; §7.5 the adjacency row; §4 the schemaless reading. [TSON-SCHEMA] §12.1's `type-ref` is the data production with the schema's wider `type-arg`; `type-name` becomes `qualified-name` at every reference position. **A language change:** `t: set<text>` is a data value; `!int32!` and `box< text>` are parse errors. **Open — one remainder** (§5): whether a value argument may be any token (running) or only `value-literal`'s. [settled — implemented / open] |
| 14 | Every `!schema` value declares, from the root | **Accept — the baseline above.** §4.1's "nothing in it lifting or minting" holds of every namespace type but `schema`; §2.2.3 and §10 the entry's identity (declaring document plus path, and a synthetic's its form); §3.3.2 and §5.12 every name resolving from the root; §4.2 and §8.1 `meta.reference_type.of`, `supertypes` and `subtypes` typed `meta.type_ref`; §12.1 `schema-map-entry` admitting `"!schema" ws schema-map`; [TSON-DATA] §7.4 `type-ref = "!" qualified-name`. **Open — three remainders** (§5): lexical scope, synthetics homed by the innermost map, and who may contribute to a map. [settled — implemented / open] |
| 15 | The roots hold the common vocabulary | **Accept — the baseline above**, the placement table stated once in Part 2 §9 with its convention; §13.2 lists the six artifacts; §3.4 the kernel as one pre-loaded document; §2.2.3's reservation covers each root and the map names; §3.3.1 an alias of a constructor applied as its target, in a held body as anywhere; §8.1–§8.3 output tagging a body by its constructor's path; [TSON-DATA] §3.1 and §7.4 `annotation = "@" qualified-name`. **Open — four remainders** (§5): the placement list is a judgement per entry; which `schema` a nested map's tag names beside a local `schema`; where the author annotations live; who may contribute to `meta`. [settled — implemented / open] |
| 16 | A count is a fixed 64-bit range | **Accept — the baseline above.** §9, §13.2, §5.10's derived count type, §5.11's `multiple_of` and `bits`, §5.3's "a bound past what a processor's values can reach is met or never reached, never a schema error", and [TSON-DATA] §9.1 and §11.5's limits as positive `int64`, a processor holding one in a narrower type capping it. [settled — implemented] |
| 17 | A facet is typed by its family's instance | **Accept — the baseline above.** §7.4's "Constraint fields typed as `value`" and the eager-conversion paragraph go; §5.2's eager-resolution paragraph drops the `value`-typed default; §5.5's network coherence rule is left the pair's emptiness; §4.2's `value` entry goes with #21. [settled — implemented] |
| 18 | A restated field's type narrows by IS-A | **Accept** — the one sentence in §5.7 beside the elided type-ref paragraph, and §5.8's tightening entries under it; `void` at a voidable position the one non-nominal case. A structural narrowing between containers or a variant restating its choice is each constructor's rule to state, with #5. [settled — implemented] |
| 19 | A record template is its own constructor, its fields labelled | **Accept — the baseline above**, with the two declaration-time rules (a literal value needs a closed type; a parameter not spelled like a name among an absorbed field type's arguments) and the two shadowing refusals. §5.10, §5.8, §8.1, §1.3, §4.2, §5.7 and §13.2 as the register proposes. **Open — one remainder** (§5): a shared parent for `record` and `record_template`'s record-level fields. [settled — implemented / open] |
| 20 | An applied parent reaches the head at a closed operand, not through a template | **Accept the expansion rule.** A declared application of a template whose parent is an application of another record template is IS-A that template too, as its hand-written expansion is, and joins its family under the pinning obligation, its own pins or its arguments supplying the outer family's discriminators; §8.1's "never a head name" narrows to "a parent application contributes the entry its arguments name, and a declared member additionally the head". The asymmetry runs today and is to be closed by the implementation. **[editorial]** — decided at adjudication. |
| 21 | Dependent fields | **Accept — the baseline above**, every rule as the register lists it: the data and schema productions, the position rule, the sibling's rules and the declaration-order rule, the reading in each of the three ways a type is known, schema documents holding a dependent field's value as written, the `type!` slots, and §5.12's walk through a dependent field. `value` and `value_type` leave the kernel. **Open — seven remainders** (§5). [settled — implemented / open] |
| 22 | "type-ref", "type reference", "sibling" | **Accept.** §12.1's `type-ref` is renamed `type-expr` at every use and `type-ref` kept for `qualified-name ["<" type-args ">"]`, which `map-key`, `supertype-ref` and `refined-def` then use; "type reference" is kept for `type-ref` and `meta.type_ref`, a `!name` is a tag, an alias's body a reference; "sibling" is #21's typing field alone, the five informal uses reworded. No meaning changes. [settled] |
| 23 | A float carries bounds, though §5.7 asks a bound's family to be totally ordered | **Accept the proposal.** §5.7 reads "ordered" for "totally ordered"; a bound excludes NaN, so a float body with any bound admits no NaN and `allow_nan` decides NaN only where no bound is written; ±infinity are ordered values a bound judges as any value. The NaN rule and the infinity half are to be closed by the implementation, the latter a recorded defect. **[editorial]** — decided at adjudication. |
| 24 | A document is identified by its bytes, an entry by its structure | **Accept** — the one sentence in §8.2's "Two identities", cited from §10.2. [settled] |

Counts: **24 accepted** (1–24 — #2 closed by #7; #12, #13, #14, #15, #19 and #21 carry
open remainders), **0 declined**, and **17 open questions carried** (#12's one, #13's one,
#14's three, #15's four, #19's one, #21's seven), plus the open remainders of Revision 35's
#25 and Revision 37's Part 3 ordered-map confirmation (§5). Revision 38's open remainder on
`kind_type.of` — the higher kind the series lacked — is closed by #11: `meta.reference_type.of`
is typed `meta.type_ref`, a constructor being an ordinary record type, and the admitted set
is derived. Revision 38's three considered-and-not-taken items (an identity atom, `allow_zone`,
`uri_template`) stand recorded. Open questions are recorded here and are NOT reflected as
open text in the specification.

---

## 3. Accepted changes by target document

### 3.1 Part 1 — Text Data Format

1. **§1.1, §1.3** — the series architecture: one directive, a chain of imports grounded in
   the pre-loaded kernel, a document a schema by its root's tag; the lexer-freeze paragraph
   records that Revision 39 leaves the lexer alone and names the four data-grammar
   positions it changes (#6, #8, #13, #21).
2. **§1.5** — a Class 1 processor recognises a schema document by its root's `!schema` tag,
   past the root's leading annotations, and rejects it with the categorised diagnostic;
   "preserve `schema` directives" becomes "preserve the header's `!!import`s"; a reference
   with arguments and a sibling reference are kept as structured values (#8, #13, #21).
3. **§2.1** — the example's header is `!!id` and `!!import`, its `shipping` field loses its
   `!!schema`, and the walk-through follows (#6, #8).
4. **§2.2** — the header is `[id-directive] *(import-directive)`; kind dispatch reads the
   header, the root's leading annotations and its tag; a pure-metadata document stands as
   before (#8).
5. **§2.2.1** — the references the canonical-identity rule covers are `!!import`, a schema
   library's keys and the `TSON-Schema` field (#8).
6. **§2.3** — one value rule, `data-value`; the table's "scoped values" column goes (#6).
7. **§2.4** — after "consulted exactly once": at a disjoint choice that consultation
   selects the variant, so the form an emitter chooses there is its tag, with a pointer
   to [TSON-SCHEMA] §5.4's emitter sentence (#3).
8. **§2.5** — the directive sentence goes (#6); a record's field order is insignificant
   except that a dependent field's free sibling is written before it (#21).
9. **§2.6, §2.7** — the directive sentences go (#6).
10. **§3** — directives stand in the header alone; the augmentation intro follows (#6).
11. **§3.1** — `annotation = "@" qualified-name [ ":" data-value ]`; a dotted name resolves
    from the root under a schema and is kept as written schemaless; an empty segment is a
    parse error (#15).
12. **§3.2** — `type-ref = "!" qualified-name`, no arguments; a type annotation names a
    member of a nested map by its path; the "type expression syntax is not available in
    data values" paragraph is restated: an argument list is data grammar at a reference
    value and never on a tag (#13, #14).
13. **§3.3** — the closed positional set is `id` then `import`; the table has two rows;
    `!!meta` and `!!schema` are parse errors naming `!!import` (#6, #8).
14. **§4, §4.1, §4.4** — a schemaless read keeps a reference with arguments, a dotted tag
    and a sibling reference as structured values, resolving nothing (#13, #14, §21).
15. **§5.1, §5.5, §5.6** — the library column names the kernel (`meta.tn`) for `!void`,
    `!boolean`, `!text`, `!bytes`, `!regex`, `!iri`, `!iri_reference`, `!integer` and
    `!int64`; `numeric` for `!number`, `!rational`, `!complex`, the other widths and the
    floats; `temporal` for the five temporal rows; `net` for `!uuid`, `!uri`,
    `!uri_reference`, `!email` and the network rows; "a schema that imports core alone"
    sentences are rewritten for the four-library layout (#15).
16. **§6** — the JSON reader's binding is `$import` (#8).
17. **§7.1** — classification reads the header, the root's leading annotations and its
    tag; the identifier profile's `.` sentence cites the qualified name; the media-type
    paragraph compares the field with `!!import` (#8, #14).
18. **§7.2.5** — `<` and `>` join `!` and `@` as the characters with a grammar role in data
    values, the other eleven reserved; `!` keeps its prefix role and gains a postfix one
    (#13, #21).
19. **§7.4** — `document = [id-directive ws] *(import-directive ws) data-value ws`;
    `scoped-value` goes; `core-value` gains `reference`; `reference`, `sibling-ref`,
    `type-args`, `type-arg` and `qualified-name` as the register writes them; `type-ref =
    "!" qualified-name`; `annotation = "@" qualified-name […]` (#6, #8, #13, #14, #15, #21).
20. **§7.5** — two rows: `<` (reference arguments) adjacent to the preceding unquoted
    token, whitespace free inside the list, `>>` two tokens; `!` (suffix, sibling
    reference) adjacent to the preceding token and followed by whitespace (#13, #21).
21. **§7.7** — the separator sentence cites §5.12's qualified name as the kernel's grammar
    reads it; the kernel's profile entry is `meta.identifier` (#14, #15).
22. **§8.1** — the parse errors gain a directive outside the header, `!!meta`/`!!schema`,
    `<` with whitespace before it, an unclosed argument list and a sibling reference not
    followed by whitespace; the schema-document diagnostic names the root's tag (#6, #8,
    #13, #21).
23. **§8.2** — the policy document is a data document of `policy.processor`; the token
    policy's record is `policy.script_policy` (#16, #15).
24. **§9.1** — the foreign-schemas row goes; a limit is a positive `int64`, a processor
    holding one in a narrower type capping it (#6, #16).
25. **§9.3** — the one directive that selects a vocabulary is `!!import` (#8).
26. **§10.2** — series references at `/2026/39/`.

### 3.2 Part 2 — Type System and Schema

1. **§1** — the schema grammar imports the data grammar's `core-value`, a reference with
   arguments now among its values; the "every rung" sentence is rewritten for the import
   chain (#8, #13).
2. **§1.2** — principle 1 reads over `!!import`; "type reference (`!name`)" reads "tag"
   (#8, #22).
3. **§1.3** — a Class 2 processor pre-loads the kernel alone and SHOULD pre-load the
   libraries; resolves every name in the schema's one namespace; recognises a record
   template by its constructor; a resolved-output consumer never meets a `!meta.template`
   or a `!meta.record_template` at a closed position (#7, #15, #19).
4. **§1.4** — terminology: a schema document is one whose root is tagged `!schema`; a
   schema value is a value of `meta.schema`; "kind" is the set a reference over a
   constructor admits; "namespace" is a schema's one namespace and `meta.namespace` the
   map constructor; the meta-schema entry goes (#7, #8, #9, #11).
5. **§1.5** — eleven companion artifacts: six normative, five fixtures (#15).
6. **§1.6** — the example's header and root tag; `!!import` on the data document (#8).
7. **§2.1** — a schema document's structure: `[!!id] *!!import`, annotations, `!schema`,
   the map; the `!schema` tag names the kernel's root `schema` and classifies the document
   (#8, #15).
8. **§2.2** — two operations; §2.2.2 retired (its number kept, marked so) (#7, #8).
9. **§2.2.3** — imports for every document, every target a schema document; the level
   rule goes; an entry's identity is its declaring document and its path, a synthetic's its
   form; a map of one path declared by several documents is the union of its members; two
   pins of one identity refused; "imports populate only the type-name namespace" goes
   (#7, #8, #14).
10. **§3** — rewritten around one namespace: §3.1 the chain of imports grounded in the
    kernel; §3.2 schema-value separation restated over `!!import`; §3.3 one namespace;
    §3.3.1 the constructor-role applicability rule and the desugar targets (`meta.record`,
    `meta.array`, `meta.map`, `meta.tuple`, `meta.choice`, `meta.alias`) resolved in that
    namespace, an alias of a constructor applied as its target in a held body as anywhere;
    §3.3.2 lookup order (parameters, locals, the closure); §3.3.3 an annotation resolves in
    the annotating document's namespace; §3.3.4 a data document's namespace is its
    imports' closure; §3.3.5 retired (#7, #15).
11. **§3.4** — the kernel, `meta.tn`, pre-loaded, header `!!id` alone, its one wait cycle
    the pre-loaded set; §3.4.1 Pass 2 in rule 1's order, rule 5 replacing the
    forward-reference sentence, closure collection following `!!import` alone (#4, #8, #15).
12. **§4.1** — rewritten: `meta.top`, `meta.type`, the three base kinds, `meta.alias` and
    `meta.template` under `top`, `meta.namespace` a refinement of `meta.map`; an entry a
    type exactly when its value is built by a constructor IS-A `type`, otherwise a value;
    a constructor an ordinary record type; "nothing in it lifting or minting" of every
    namespace type but `schema`; the kind derivation (#9, #10, #11, #14).
13. **§4.2** — rewritten: `meta.reference_type` with `of` and `allow_sibling`, `meta.ref`,
    `meta.field_type_ref`, `meta.type_ref`, `meta.identifier_ref`; the atom with no
    vocabulary is `meta.void_type` alone; `meta.identifier` and its three aliases (#11,
    #12, #17, #21).
14. **§4.3** — the operations table: refinement generalised to any value under its type's
    rules; "alias chain" (#5, #10).
15. **§5.2** — the dependent field: the sibling reference at a record field's type, the
    position rule, the sibling's rules, the declaration-order rule and the reading; the
    eager-resolution paragraph without the `value`-typed default; `field_map` keyed by
    `text`, two keys one value under the name type a resolver error; `name_type` a
    `meta.identifier_ref`; the default `meta.identifier` (#9, #17, #21).
16. **§5.3** — a count facet admits any `numeric.count`, a bound past what a processor's
    values reach met or never reached; `set<T>` the kernel's root `set`; the desugar
    targets by path (#15, #16).
17. **§5.4** — the emitter sentence for a disjoint choice; `meta.declared` classless; a
    reference string-class (#3, #6, #11).
18. **§5.5** — `!C { … }` is an instance of `C`, a type where `C` IS-A `meta.type`;
    atom refinement one case of value refinement; construction creates peers; the
    family-by-family value-space clauses with the facets typed by their families (#5, #17,
    #22).
19. **§5.6** — the end state over `meta.record`, `meta.record_template`, `meta.template`,
    `meta.alias` and a namespace value; the positional form's bare-token rule restated
    over `meta.ref<T>`; an application sugar is data grammar (#9, #12, #13, #19).
20. **§5.7** — `^` refines a value under its type's rules, a type stating none refused;
    a restated field's type IS-A the inherited one or `void` at a voidable position;
    "ordered" for "totally ordered", a bound excluding NaN, infinities judged; the
    constructor-refinement exception goes; a restated dependent field only as itself; open
    modifiers over `value_param` (#5, #9, #18, #19, #21, #23).
21. **§5.8** — a tightening entry under §5.7's IS-A rule; a declared application of a
    template whose parent is an application IS-A that template too; `<T> T & { … }` left
    to the shadowing rule; a sibling as a discriminator (#18, #19, #20, #21).
22. **§5.9** — rule 8 reads over one namespace (#7).
23. **§5.10** — rewritten in its template-forms, held-bodies, parameter-type, form and
    family-base paragraphs: a record template is a `meta.record_template`, its fields
    labelled; the three places a parameter stands; a literal value needs a closed type;
    the absorbed-argument refusal; a value parameter's type resolved in the schema's one
    namespace, held to the uses it derives; a template whose body applies another takes
    that template's form and an alias is never a form; an argument a reference or a token
    by the grammar; `type_param`/`value_param` closing to `type`/`value` (#2, #7, #12, #13,
    #19, #22).
24. **§5.10.1** — the cycle sentence cites §3.4.1's rule 4 (#4).
25. **§5.11** — `integer_type.multiple_of` is `integer`; a member may be a sibling (#16,
    #21).
26. **New §5.12 rewritten — Values by name** — the first segment names an entry of the root
    namespace, a further segment a member of a `!schema` map, a key of a namespace value or
    a field of a record value; a dependent field's type is the type its sibling names in
    the same value; the rule reaches a data document; a type entry admits no further
    segment (#11, #14, #21).
27. **§6** — annotations resolve in the annotating document's namespace; the kernel's
    `meta.annotation` is `void`'s alias and annotates nothing, the "one exception is
    structural" sentence going; the author annotations are core's, the classifying ones
    `atom.*`, `meta.disjoint` checked, `meta.synthetic` and `meta.application` derived;
    `@meta.application`'s value an application as written (#4, #7, #12, #15).
28. **§7.1** — a data document's `!!import`s bind it, several in one namespace, the root
    naming its type; the scoped-value paragraphs go; the schemaless case cites the
    libraries by name (#6, #8, #15).
29. **§7.2** — "after alias flattening of both"; a tag is a qualified name; resolver output
    admitted at `schema`'s `declared`-typed values by every entry's tag (#9, #10, #14).
30. **§7.4** — `enum` the kernel's root, `meta.enum_type` the constructor, `name_type` a
    `meta.identifier_ref`; "Constraint fields typed as `value`" and the eager-conversion
    paragraph go; `unicode.identifier_type` and the profile by path; a position typed by a
    reference over a constructor judged by membership in a data document (#9, #11, #15,
    #17).
31. **§7.5** — `scoped.scope` leaves the list of set-typed positions (#6).
32. **§7.7** — unchanged in substance; "alias chain" (#10).
33. **§7.8** — reduces to `declared`: `meta.declared_type`, `meta.declared`, the LOCAL
    rule, a tag naming no type of the closure a resolver error; the table, the cell rule,
    the push, the typed-position restriction and the schemaless-document paragraph go
    (#6).
34. **§8.1** — rewritten: an entry is its value, a type or a value by its tag; a type's
    chain with `parents`, `supertypes` and `subtypes` typed `meta.type_ref`; open entries
    `!meta.record_template` and `!meta.template`; the kind derivation with ALIAS; the
    `meta.alias`, `meta.record_field` (`type: meta.field_type_ref`, `value?: type!`),
    `meta.record_template_field` and `meta.field_map` records; the derived indexes
    `subtypes` and `disjoint`, `members` gone; a resolved form's header its source's and
    nothing supplied at ingest; the ingest checks; the body-patterns table (#8, #9, #10,
    #11, #12, #14, #17, #19, #21).
35. **§8.2** — a refinement source among the trigger positions; identity the parsed form
    of a reference, its arguments past alias chains; the bytes/structure sentence;
    `@meta.application` on every entry that closes an application, nested arguments
    unreduced (#1, #12, #24).
36. **§8.3 — Aliases** — `!meta.alias { target: T }`, an open target the application as
    written; "an alias is a hop, not a rewrite"; the instantiation and alias-template
    sentences (#10, #12).
37. **§9** — rewritten: the six bundled documents, the placement table and its convention,
    the kernel's root and six maps, the libraries' roots and maps, core as the bundle,
    `policy`; the family pairs by path; the extension guidance over `meta.ref<T>`,
    `meta.declared` and nested maps (#7, #11, #14, #15, #17).
38. **§10.1** — the library's keys and sources over `!!import`; a resolved form read as the
    caller's choice under its own header (#8).
39. **§10.2** — a reference to §8.2's identity sentence; the kernel's self-reference goes,
    the kernel pinned by the libraries alone (#8, #24).
40. **§11.3** — `!!import` the one sensitive directive (#8).
41. **§11.4** — each schema map a scope; a value entry's keys and a qualified name's
    segments as before (#14).
42. **§11.5** — the alias-chain and wait-depth rows; limits as positive `int64` (#4, #10,
    #16).
43. **§12.1** — `schema-document = [id-directive ws] *(import-directive ws) *(annotation
    ws) "!schema" ws schema-map`; `schema-map-entry` admitting `"!schema" ws schema-map`;
    `type-ref` renamed `type-expr` at every use and `type-ref` kept for `qualified-name
    ["<" type-args ">"]`, with `sibling-ref`; `qualified-name` at every reference position;
    `atom-refinement`'s head generalised; the `instance` comment naming `meta.alias`;
    `definition-mark` before `parameters` unchanged (#5, #8, #10, #13, #14, #21, #22).
44. **§12.2** — the dispatch summary follows: `!schema` at the root, a nested map's tag,
    `<` and `!` after a name (#8, #13, #14, #21).
45. **§12.3** — the `<` and `!` (suffix) rows (#13, #21).
46. **§13.1** — unchanged but for `meta.identifier`'s UAX #31 citation; **§13.2** carries
    the six normative artifacts with the pins as received and §13.3 the five fixtures
    (#15).

### 3.3 Companion artifacts

The revised artifacts shipped with this revision carry the baseline of §1 and every
kernel-, library- and policy-level change in this log. Their entries are not edited by this
adjudication; §6 lists the doc lines that disagree with the text and are left as received,
with the pins they would move.

### 3.4 Part 3 — JSON Encoding

[TSON-JSON] is edited directly as findings arise, and arrived with the Part 3 halves of
seven entries already in place: #6 (§1.6 item 14, §1.7, §5.7, §8.3, §8.3.1, §8.5, §9.4,
§10.1), #8 (§1.3 principle 3, §1.6 item 20, §1.7, §2, §3.2–§3.5, §4.1, §6.4, §9.2, §9.4,
§10.4), #11 and #12 (§1.6 item 16, §5's table, §5.6), #13 (§5.6), #14 (§3.3's `$type` as a
qualified name) and #21 (§5.6's sibling string, §6.1.6's dependent-member order). The second
pass completes it for the rest, and §7.1 lists the edits. Part 3 defines no type-system
rule, so each is a spelling of a rule §3.1 or §3.2 owns.

---

## 4. Notable normative changes (reader's digest)

Changes a Revision 38 implementer must act on:

1. **One directive.** A header is `[!!id] *!!import`. `!!meta` and `!!schema` are gone,
   parse errors naming `!!import`; a `!!schema` on a field, entry or element is a parse
   error.
2. **A schema document's root is tagged `!schema`.** The tag classifies the document and
   names the pre-loaded kernel's root `schema`, whatever the document imports;
   classification reads the header, the root's leading annotations and its tag.
3. **One namespace per schema.** A schema's namespace is its own entries and its import
   closure, and every name resolves there: constructor heads, refinement sources, type
   positions, a reference's `of`, annotations. There is no meta-schema, no placement rule,
   no import level, no structure namespace. Any schema may declare a constructor.
4. **The kernel is `meta.tn`, one pre-loaded document**, header `!!id` alone, importing
   nothing. Its root holds `void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`,
   `iri_reference`, `regex`, `doc`, `enum`, `set` and `schema`; everything else is named by
   path under `meta`, `atom`, `numeric`, `unicode`, `net` and `binary`.
5. **Four libraries, one bundle.** `numeric.tn`, `temporal.tn` and `net.tn` each import the
   kernel alone; `core.tn` imports the three and declares `@deprecated`, `@title`,
   `@comment`, `@examples`, `@read_only` and `@write_only`. `uuid`, `uri`, `uri_reference`
   and `email` are `net.tn`'s. A schema importing a library reserves its roots and map
   names.
6. **Nested schema maps.** `geo => !schema { … }` declares; its members are entries named
   `geo.address`; a map of one path declared by several documents is the union of their
   members; every name resolves from the root, with no lexical scope.
7. **Qualified names everywhere a type is named**: `!geo.address { … }` in data,
   `@atom.ordering:TOTAL`, `"$type": "geo.address"`, `meta.ref<meta.record>`.
8. **An entry is a type exactly when its value is built by a constructor IS-A
   `meta.type`**; otherwise it is a value — `orders => !api { … }`, `create_order =>
   !operation { … }` — marked by nothing but its tag. `schema`'s values are `declared`.
9. **`namespace` is a refinement of `map`**, a namespace type an instance of it;
   `field_map` is a plain ordered map keyed by `text`, and no qualified name selects a
   field declaration.
10. **One reference atom.** `meta.reference_type { of  allow_sibling? }`, spelled
    `meta.ref<T>`: over a constructor it holds a type's name, over any other type a value's
    qualified name; a constructor is an ordinary record type at a name slot; `kind_type`,
    `ref_type`, core's `kind` and the derived `members` are gone; `type_kind` and
    `identifier_kind` are `meta.type_ref` and `meta.identifier_ref`.
11. **An application is a reference value**, written unquoted in data (`t: set<text>`,
    `target: pair<uuid B>`), identity its parsed form; the `type_ref` and `type_argument`
    records are gone. A tag carries no arguments.
12. **A record template is `meta.record_template`**, its fields `record_template_field`s
    labelling a parameter as `type_param` or `value_param`; `meta.record` is closed. A
    literal value needs a closed type.
13. **Dependent fields.** `value?: type!` types a field by its sibling `type`'s value; a
    sibling is required, defaulted, pinned or a group member, never voidable; a free sibling
    is written before the dependent field.
14. **`value` and `value_type` are gone.** Every facet is typed by its family's instance
    (`decimal_type.min: number`, `date_type.min: date`, `ipv4_type.within: [cidr4]`); a
    field's default or pin is read against the field's own type.
15. **A count is `numeric.count`**, 0 to 2⁶³−1; `int64` is the kernel's;
    `integer_type.multiple_of` and `integer_size.bits` are `integer`; a limit is a positive
    `int64`.
16. **The scope push is gone.** The open sum is `meta.declared`, admitting every type of
    the governing closure by its tag; `scoped`, `extern`, `dynamic`, `extern_of` and
    `extern_type` are gone, and so is the foreign-schemas limit.
17. **Resolution within a schema runs in wait order**, source order never consulted; a wait
    cycle is a resolver error; `max_wait_depth` is a limit.
18. **`!` is an instance of the named type, and `^` refines any value under its type's
    rules**; a restated field's type narrows by IS-A; a float's bound excludes NaN and
    judges the infinities.
19. **The alias body is `!meta.alias { target: T }`**; "alias chain" replaces "reference
    chain"; `max_alias_chain` is the limit.
20. **A refinement source is a trigger position**, and a declared application of a template
    whose parent is an application IS-A that template too.
21. **The emitter's form at a disjoint choice is its tag**: quote a text value whose
    content would resolve to another class.
22. **Every bundled pin changes** (§6), and the artifact set is six documents and five
    fixtures.

---

## 5. Open questions carried by this change log

Adjudicated 2026-11-08. The following remain deliberately open, or are the revision
editor's readings awaiting the spec author's confirmation. They live in this change log
only — the specification text carries no open questions.

| Ref | Question | Status |
|---|---|---|
| Rev 35 #25 (→ —) | Annotation cardinality as the means of per-name replacement. | Open — recorded as considered. |
| Rev 37 #10 (Part 3) | The two MUSTs [TSON-JSON] §6.4 states for an ordered map. | Open — still the spec author's to confirm; not raised in the register. |
| Rev 38 #11 (outstanding) | The `tson-schema` skill's sections; now also the header, the libraries, nested maps and references. | Outstanding — not among this revision's inputs. |
| #1 (reading) | A refinement source is a trigger position and the refinement IS-A the entry the application denotes, order-independently — the register's (a). | **[editorial]** — for confirmation; the implementation runs order-dependently until closed. |
| #9 (reading) | §5.7's constructor-refinement exception goes: no bundled constructor refinement replaces a pin, and there is no meta level for the exception to be about. | **[editorial]** — for confirmation. |
| #12 (remainder) | Whether an expansion — an application nothing declares — states `@meta.application` too, so every entry an application closed to names the reference that closed it; two applications closing to one record would give one entry two. | Open — the resolver keeps it in memory only. |
| #13 (remainder) | Whether a value argument may be any token (running) or only those §12.1's `value-literal` admits. | Open — the text states the running grammar. |
| #14 (remainder) | Lexical scope: a bare name inside a map resolving in that map first, then outward, with a way to say "from the root" and a shadowing rule. | Open — every name resolves from the root. |
| #14 (remainder) | Synthetics homed by the innermost map, identity by path. | Open — waits on lexical scope; synthetics mint at the root. |
| #14 (remainder) | Who may contribute to a map: any document may, and a library cannot keep a map's unclaimed paths its own. | Open — the open rule is stated. |
| #15 (remainder) | The placement list is a judgement per entry; "what an author writes" is the intent and not yet a test. | Open — the table is stated as a convention. |
| #15 (remainder) | Which `schema` a nested map's tag names in a schema that imports nothing and declares its own `schema`: running, the pre-loaded kernel's, as for the root. | Open — the text states the running answer. **[editorial]** |
| #15 (remainder) | Where the author annotations live: core.tn, the kernel, or a library of their own. | Open — core.tn for now. |
| #15 (remainder) | Who may contribute to `meta` and the other kernel maps: any schema, the collision rule guarding only members already declared. | Open — stated with #14's. |
| #19 (remainder) | A shared parent for `record` and `record_template`'s five record-level facts, a `meta.product` refinement with `fields` left to each. | Open — the kernel duplicates the fields. |
| #20 (reading) | The expansion rule governs: a declared application of a template whose parent is an application IS-A that template too and joins its family. | **[editorial]** — decided at adjudication; the asymmetry runs until closed. |
| #21 (remainder) | Whether the end of the document counts as following whitespace after a sibling reference (running: it does). | Open — the text states the running rule. |
| #21 (remainder) | Whether a field group member's type takes a sibling reference (running: it does). | Open — the text states the running rule. |
| #21 (remainder) | Whether a sibling and its dependent field in two options of one group is refused (running: admitted, the dependent field then writable in no document). | Open. |
| #21 (remainder) | Whether a literal under a closed type is judged on a template nothing applies (running: hygiene only; the value is judged when an application closes the field). | Open. |
| #21 (remainder) | A field whose name needs quoting cannot be a sibling; `"name"!` is refused. | Open — recorded. |
| #21 (remainder) | Which host component a bound dependent field takes (running: `Object`, registered as an atom). | Open — a processor's choice. |
| #21 (remainder) | A sibling in a template field typed by a parameter (`kind: meta.type_ref = T`), waiting on whether a pin on a `meta.type_ref` slot may carry a type parameter. | Open. |
| #23 (reading) | A bound excludes NaN and judges the infinities; "ordered" for "totally ordered". | **[editorial]** — decided at adjudication; the implementation's NaN rule and infinity defect are to be closed. |
| #8 (reading) | Classification reads past the root's leading annotations, wider than Revision 38's "two directives of lookahead and no value parsing". | **[editorial]** — the running reading; the alternative, a schema's annotations after its tag, was declined. |
| §1 (naming) | `policy.tn`'s `policy.script_policy` beside Part 1 §8.2's "token policy". | **[editorial]** — the record's name stands as received; the policy keeps its name in prose. |
| Rev 38 #4, #6, #8 (considered) | An identity atom; `allow_zone`; `uri_template`. | **Not taken** — stand recorded. |

Decisions taken 2026-11-08: #1 the register's (a); #2 closed by #7; #3 the two sentences;
#9 the exception gone; #20 the expansion rule; #22 the renames; #23 the proposal in full;
#24 the sentence; every other entry as the register proposes and the artifacts build.

---

## 6. Artifact work

1. **Received at Revision 39** — all eleven artifacts carry "2026 Revision 39 draft" and
   `/2026/39/` identities. The six normative pins are carried as received: kernel
   (`meta.tn`) `c9b069b6…`, numeric `6e36be00…`, temporal `fb4ada8f…`, net `371fc20c…`,
   core `7cc227d2…`, policy `e9cb5b93…`; Part 2 §13.2 carries the six rows. Each pin was
   checked against sha256 over the bytes past the `!!id` line of the copy received and
   **matched** (§8). The chain is consistent — numeric, temporal, net and policy each pin
   the kernel at `c9b069b6…`; core pins numeric, temporal and net at the digests above; the
   kernel pins nothing, importing nothing. Hash *values* remain non-normative; only the
   pin's shape is.
2. **The artifact set changes shape.** `meta-kernel.tn` is gone, merged into `meta.tn`,
   the kernel; `numeric.tn` and `temporal.tn` are new; `meta-kernel-resolved.tn` is gone and
   `numeric-resolved.tn` and `temporal-resolved.tn` are new; `policy.tn` has no fixture.
3. **Doc lines that disagree with the text, left as received** (each would move the
   kernel's pin and every pin above it, so they are recorded for the next re-stamp):
   - `meta.tn`'s `unicode.identifier_type` doc still says `"." is reserved as a future
     identifier separator`; Revision 38 made it the separator of a qualified name, and
     Revision 39 reads one at every reference position.
   - `meta.tn`'s `meta.top` doc says a constructor transitively IS-A a base kind "except
     `alias` and `template`, which compose with `top` directly"; `meta.declared_type`
     composes `sum` and is fine, but the doc does not say a namespace type is a map type
     under `product`, which §4.1 now does.
   - `meta-resolved.tn`'s conventions note says a slot that names a type "is typed
     `meta.type_ref`, the kind of the types" and calls the application production "data
     grammar ([TSON-DATA] §7.4)" — both right — but still describes `supertypes` as "every
     element a bare name" where `type_chain` types it `meta.type_ref`; a supertype is a
     name without arguments, which the doc of `type_chain` says and the note should.
   - `numeric.tn`'s header cites `numeric.integer_type`'s `multiple_of` among the steps
     under "one rule"; `meta.tn` types it `integer`, which is the rule (#16).
   - `core-resolved.tn`'s header says "Core's entries are the author annotations, at its
     root" and nothing else — right, and the one fixture whose map count is zero.
4. **Not verified**, carried as notes: a resolved form is read under its source's imports
   rather than over its own entries (#4, #15), so a library's fixture holding instances of
   constructors its own source declares reads back only beside its source; #1's
   order-independent closing, #20's head edge and #23's NaN and infinity rules are stated
   in the text and not running (§2); the annotation-in-an-imported-reader gap (#15) is
   this implementation's.

---

## 7. Second pass: Part 3 and the Developer Guide

Both ship with Revision 39. Neither carries a decision that §2 does not record; where a
sentence had to be written that no register entry dictates, it is marked **[editorial]**
and is the spec author's to confirm.

### 7.1 Part 3

tson-part3-json.md is the copy received, completed for what it had not yet consumed.

1. **Header and status** read Revision 39, "the fourth published revision of Part 3",
   written against Revision 39 of [TSON-DATA] and [TSON-SCHEMA]; §11.2's series references
   move to `/2026/39/`.
2. **§1.6** — the opening sentence is rewritten ("items 15 to 19 are Revision 38's, and
   items 20 to 22 Revision 39's"); item 14 names `meta.declared_type`; item 16's
   references and applications stand; item 20 stands; **new item 21 — dependent fields**
   (§5.6's sibling string, §6.1.6's member-order exception); **new item 22 — qualified
   names** (§3.3's `$type` as a path, §5.6's reference strings, `$import` as a list under
   #8). **[editorial]** — the numbering.
3. **§2** — the example stands (`!!import`, `!schema`, `$import` as a one-element list);
   the walk-through's "standing in for the header's `!!import` and the root annotation"
   stands.
4. **§3.5** — the example field stands as a one-element List, which RFC 9651 serialises as
   the single String; the sentence saying so was already in the received copy, and no edit
   was needed.
5. **§5's table** — the two reference rows and the declared-position row stand; "kinds"
   stays the concept's name (#22).
6. **§6.1.6** — the dependent-member exception stands as received.
7. **Cross-references.** Every `[TSON-SCHEMA]` section Part 3 cites is checked against the
   renumbered Part 2 (§2.2.2 and §3.3.5 retired, numbers kept); `§5.12`, `§7.8` and `§8.1`
   keep their numbers and change their content, and the citing sentences are checked
   against the new content.

### 7.2 Developer Guide

tson-guide.md is realigned on the same two principles as before: it describes the design
as it stands, and history lives in the change logs.

1. **§1.1** — the chain, walked once: `!!import` on the data document, `!schema` on the
   schema, the kernel pre-loaded and importing nothing; "the kernel's `!!meta` names
   itself" goes (#7, #8).
2. **§1.3** — schemas as data under the new output shape: `person => !meta.record { fields:
   { … } }`, `age => !numeric.integer_type { … }`; "the meta layer is the sanctioned
   extension point" becomes "any schema declares constructors, under the names it
   reserves" (#7, #15).
3. **§1.6** — three documents and eleven artifacts; the reading order (#15).
4. **§2.3** — "`!` takes constructors only" becomes "`!` is an instance": `!C { … }` is a
   value of `C`, and whether the line declares a type follows from `C`'s chain (#5).
5. **§2.7** — a record template as `meta.record_template` with labelled fields; an
   application as a reference value; a refinement source as a trigger position; the head
   edge of an applied parent (#1, #12, #19, #20).
6. **§2.8, §2.9, §2.10, §2.11** — `!!import` for `!!schema`; `field_map` keyed by `text`;
   `name_type` a `meta.identifier_ref`; the JSON-LD conversion by path (`unicode.
   identifier_type`) (#9, #11, #15).
7. **§3.3, §3.4** — references are `!!import`s; the kernel pinned by the libraries, the
   self-reference gone; the publication discipline over the four-library chain (#8, #15).
8. **§3.5** — the policy document is a `policy.processor`; "every naming position" by
   path (#15, #16).
9. **§4 — "The Schema Ladder, Narratively" is rewritten as "One Namespace"**: what
   schema-value separation still buys under `!!import`; why one namespace replaced one hop
   and import-what-you-expose; where annotations live (the annotating document's
   namespace, so the asymmetry §4.4 explained is gone); why the kernel is one document and
   the roots hold the common vocabulary (#7, #15); the library principle restated over
   four libraries (#15).
10. **§5** — the tour: construction as instantiation of a constructor; refinement of any
    value under its type's rules; alias for reference; the three ways to name a type after
    another (#5, #10).
11. **§6.1** — retitled "`void`, `meta.identifier` and the reference atom"; `value` gone
    (#11, #17, #21).
12. **§6.2** — "Constraint fields typed as `value`" rewritten: a facet is typed by its
    family's instance, the bootstrap ordering dissolved by one namespace and wait order
    (#4, #17).
13. **§6.3** — a float's bounds exclude NaN and judge the infinities (#23).
14. **§6.4** — "Open sums" rewritten over `meta.declared`; the scope push's history noted
    once; `namespace` as a refinement of `map` (#6, #9).
15. **§6.7** — `meta.tuple1` and `meta.voidable_tuple1` the kernel's (#15).
16. **§6.8** — rewritten: one reference atom, applications as values, qualified names
    through nested maps, the meta-service example end to end under one namespace (#11,
    #12, #13, #14).
17. **New §6.10 — "Dependent fields"** — the sibling reference, the three ways a type is
    known, the ordering rule and its JSON cost, why reaching a type through a value is
    absent (#21).
18. **§7's worked example** rewritten: `!!import`, `!schema`, `flagged` as a
    `!meta.record_template` with `type_param` and `value_param`, `task`'s fields by path,
    the synthetics under `@meta.synthetic` (#8, #12, #15, #19).
19. **§8.1, §8.2** — the two identities with #24's sentence; `@meta.application` as the
    application written; `chain.supertypes` typed `meta.type_ref`; the ALIAS kind (#10,
    #12, #24).
20. **§8.3** — "alias chain" in diagnostics; a minted entry named by its application (#10,
    #12).
21. **§9.2** — the library over `!!import`; a resolved form read as the caller's choice;
    the policy document's type (#8, #16).
22. **New §9.5 — "Choosing libraries"** — importing temporal.tn alone, numeric.tn and
    net.tn together, or core.tn; what each reserves (#15).

The Revision 33 guide candidates remain outstanding.

---

## 8. Third pass: verification

Run after the parts and the guide were revised, against the register and the eleven
artifacts. The six normative pins were recomputed as sha256 over the bytes past each
`!!id` line and match §13.2 (kernel `c9b069b6…`, numeric `6e36be00…`, temporal
`fb4ada8f…`, net `371fc20c…`, core `7cc227d2…`, policy `e9cb5b93…`), and the cross-pins
agree: the four importers of the kernel carry `c9b069b6…`, and core carries the three
library digests. The kernel's root holds exactly the thirteen names Part 2 §9 lists and
nests exactly the six maps (`numeric`, `unicode`, `net`, `atom`, `meta`, `binary`); the
kernel fixture's `meta.top.subtypes` holds `meta.alias` and `meta.template` and its
`meta.type.subtypes` excludes them, with `enum`, `binary.bytes_type`, `meta.namespace`
and `meta.record_template` in both, which is §4.1's derivation; `meta.namespace`'s chain
is `[meta.map meta.product meta.top meta.type]`, `meta.identifier_ref`'s
`[meta.field_type_ref meta.type_ref]`, and `numeric.count`'s `[int64 integer]`, each as
§4.1, §4.2 and §9 state. Each of the 24 entries' resolution items was found in the
sections §3 names. No Revision 38 vocabulary remains in Parts 1 and 3; the eight lines
in Part 2 and the thirteen in the guide that match the stale-vocabulary scan are each a
sentence naming an earlier revision, or the ordinary map facet `value_type`. §2.2.2 and
§3.3.5 are retired with their numbers kept, and §5.12, §7.8 and §8.1 keep their numbers.
What the pass found, and what was done:

1. The received Part 3 already carried §3.5's one-element-List sentence; §7.1 item 4's
   **[editorial]** mark is withdrawn.
2. The guide's §3.4 publication paragraph kept "core's own `!!meta` carries meta's digest"
   after the section splice; corrected to the four-library chain.
3. Part 1 §5.5's library column names the kernel for `!boolean`, `!text`, `!bytes`,
   `!regex`, `!iri`, `!iri_reference`, `!integer` and `!int64`, `net` for `!uuid`, `!uri`,
   `!uri_reference`, `!email` and the network rows, matching the artifacts' roots; the
   change log's §1 housekeeping note on `uuid`, `uri`, `uri_reference` and `email` is
   confirmed against `net.tn`.
4. `policy.tn`'s `policy.processor.token_policy` is a `policy.script_policy`, as §1 and
   Part 1 §8.2 now say; the §5 naming row stands.
5. Part 2 §13.2's two example pins (`people.tn`) are illustrative and unchanged.

---

## 9. Fourth pass: the implementation's verification

To be run by the implementer against the adjudicated text, entry by entry, before
publication, with each of §5's editorial readings checked against running code. Findings
are recorded here when the pass runs; the register then opens against Revision 39 with
whatever the pass raises, together with what §2 records as not yet running (#1's
order-independent closing, #2's alias refusal to go, #20's head edge, #23's NaN and
infinity rules).
