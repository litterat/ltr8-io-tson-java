---
title: "TSON 2026 Revision 38 — Change Log"
against: "TSON 2026 Revision 37 (Working Draft)"
status: "Adjudicated 2026-10-26. The five normative artifacts and four resolved fixtures are at `/2026/38/` identities, re-stamped after adjudication for the doc lines §6 lists, each pin verified over the shipped bytes, and Part 2 §13.2 carries them (§6). Part 1, Part 2, Part 3 and the Developer Guide are revised against them in the order §3 lists; Part 3 arrived partly consumed and is completed in a second pass (§7); an independent verification pass over the four documents, the register and the artifacts is recorded in §8, and the implementation's own, which folded eleven findings into the text, in §9."
inputs:
  - "SPEC-FEEDBACK.md (25 entries, renumbered against Revision 37; all new — Revision 37 closed every entry open against Revision 36)"
  - "Revised companion artifacts at /2026/38/ identities: meta-kernel.tn, meta.tn, core.tn, net.tn, policy.tn and four resolved fixtures (implementing #2, #5, #6, #8, #9, #10, #12, #14, #15, #17, #22, #23 and #24)"
  - "tson-part3-json.md, edited directly as findings arose (carrying the Part 3 halves of #1, #4, #10, #17 and #22)"
---

# TSON 2026 Revision 38 — Change Log

This document records the changes accepted into Revision 38 of the TSON specification
series, adjudicated from the implementation's spec-feedback register (25 entries,
renumbered from #1 against Revision 37, all raised since Revision 37 closed every entry open
against Revision 36). SPEC-FEEDBACK.md remains a record against Revision 37 and is not
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
stands as Revision 37 left it: a qualified name (#13) is a parse of a token the lexer
already emits, performed by the slot's type, and the `.` the identifier profile reserved
is now spoken for without entering the identifier grammar.

It does change the **data grammar at one position**: a field name is matched against the
identifier grammar only in a document that names no schema, and the match moves from the
parser to the resolver, so a schemaless `{ "first name": 1 }` is a resolver error where it
was a parse error (#10). It changes the **schema grammar** at the same position — a
declared field name is any single-line token, judged at link by its record's name type —
and otherwise leaves §12.1 alone.

What this revision changes most is the **kernel**, and with it the shape of resolved
output: `type_definition` is gone and an entry is its value (#14); `type`, `namespace`,
`kind_type` and `ref_type` join the kernel and `data` and `text_enum` leave it (#9, #22,
#23, #24, #10); a record's fields are a map keyed by name (#12) and its names have a type
(#10); a record template is a `record` with `parameters` (#17); and the five network
families leave core for a new library, `net.tn`, beside three atoms new to the series
(#2, #5, #6, #8). Every bundled pin changes.

---

## 1. Baseline: the revised artifacts

Thirteen of the register's proposals arrived already implemented in the companion
artifacts, and Revision 38 adopts them as its baseline. Summarising the normative effect:

- **#2 — the network types are a library of their own.** `net.tn`
  (`https://tson.io/2026/38/m/net.tn`), governed by meta and importing nothing, holds
  `ipv4`, `ipv6`, `cidr4`, `cidr6` and `mac`, moved from core unchanged, and the three
  atoms below. Core no longer declares the five, so a schema importing core alone may
  declare `mac` or `hostname` for itself. Meta's network constructors say "Instance is
  `ipv4` in net". (Part 1 §5.5, §5.6, §10; Part 2 §1.5, §9, §13.2; core, meta, net.)
- **#5 — `hostname_type`.** Meta declares `hostname_type => atom & atom_specification & {
  spec?: = "…rfc5890"  allow_idn?: boolean ~ true }` and net instances `hostname`: a domain
  name in LDH, A-label or U-label form, in any mix, `A`–`Z` read as `a`–`z` and nothing
  else mapped, each label at most 63 octets and the name at most 253 in A-label form, no
  trailing dot, the last label not beginning with a digit; the value is the name, so
  `Example.COM`, `example.com`, `xn--bcher-kva.example` and `bücher.example` are two
  values, and the canonical text is the lowercase U-label form. (Part 1 §5.5, §10; Part 2
  §5.5, §9, §13; meta, net.)
- **#6 — `host_type`.** Meta declares `host_type => atom & atom_specification & { spec?:
  = "…rfc3987" }` and net instances `host`: RFC 3987's `ihost` profiled to DNS — a
  `hostname`, an IPv4 address, or an IPv6 address with or without brackets, no zone
  identifier — one string-class atom whose value is the member's with the member's own
  equality, an IPv4 address never equal to an IPv6 one. (Part 1 §2.2.1, §5.5; Part 2 §5.5,
  §9; meta, net.)
- **#8 — `media_type_type`.** Meta declares `media_type_type => atom & atom_specification
  & { spec?: = "…rfc6838"  allow_parameters?: boolean ~ false  types?: [text]  suffixes?:
  [text] }` and net instances `media_type`: an RFC 6838 media type with RFC 9110 §8.3.1
  parameters where an instance admits them, the type, subtype and parameter names and a
  `charset` value compared without ASCII case, parameters a map, the canonical text
  lowercase with parameters sorted by name. (Part 1 §5.5, §10; Part 2 §5.5, §5.7, §9;
  meta, net.)
- **#9 — the `type` kind.** The kernel declares `type => abstract top & { chain?:
  type_chain }`, and `atom`, `product` and `sum` compose it; `reference`, `template` and
  `namespace` compose `top` directly, and `data` is gone. An entry is a type exactly when
  it IS-A `type`; IS-A `top` is still the constructor predicate. Every constructor under a
  base kind gains `type` in its chain. (Part 2 §4.1, §4.2, §8.1, §9; kernel, fixtures.)
- **#10 — a record's field names have a type.** `record` gains `name_type?:
  identifier_kind`, and `enum_type.type` is renamed `name_type` and retyped
  `identifier_kind`: an enum's members and a record's field names are names drawn from an
  identifier family, held to it by the same three rules. `text_enum` is removed, a closed
  set of text being `text_type`'s `members` facet; `identifier_base` gains `ASCII`, and
  `scheme_name` is restated over it (`start: ASCII  continue: ASCII  continue_add: "+-."
  exclude: "_"  normalization: ASCII_CASEFOLD`, the same set). `record.discriminators` and
  `field_group`'s member slots are `text`, each judged by the record's name type. (Part 1
  §1.5, §2.5, §2.8, §7.1, §7.4, §7.7, §8.1, §8.2; Part 2 §5.2, §5.4, §5.8, §5.11, §7.4,
  §7.7, §8.1, §9, §11.4, §12.1; Part 3 §1.3, §3.2, §6.1.1, §8.3.1, §9.4; kernel, fixtures.)
- **#12 — a record's fields are a map keyed by name.** `record_field` loses `name`;
  `field_map => namespace & { key_type?: ~ field_name  value_type: = record_field
  ordered?: = true }` joins the kernel and `record.fields` is `field_map`. The explicit
  form is `!record { fields: { x => { type: int32 } … } }`; the brace form an author writes
  is unchanged. (Part 2 §5.2, §5.6, §8.1; kernel, fixtures.)
- **#14 — `type_definition` goes; a type carries its own chain.** `type_chain => {
  parents?: [type_ref]  supertypes?: [type_name]  subtypes?: [type_name] }` is `type`'s one
  field; `record.supertypes` is removed; `application => @annotation type_ref` is a
  resolver-attached key annotation, read as stated on ingest; `schema => namespace & {
  key_type: = type_name  value_type: = top }`. An entry resolves to its value, and output
  is source with sugar expanded, bodies flattened and chains computed. (Part 2 §1.3, §1.6,
  §4.1, §5.5, §5.7–§5.9, §6, §7.2, §8.1–§8.3, §9, §10.1; kernel, every fixture.)
- **#15 — `top`, `type`, the base kinds and `namespace` are `abstract`.** Each states
  `extension: ABSTRACT` in resolved output, and a direct application of one is refused as
  any abstract record's is, naming the subtypes that would satisfy it. §3.3.1's "base
  kind's own reader" sentence is retired. (Part 2 §3.3.1, §4.1, §12.1; kernel, fixture.)
- **#17 — a record template is a `record` with `parameters`.** `record` gains
  `parameters?: [template_param]`; `template` holds every other open body and loses
  `extension` and `discriminators`. A record template is a family base, ABSTRACT by
  construction, its own `extension` each application's, and its `chain.parents` the
  parents as written, one of which may be an application. (Part 2 §4.1, §5.10, §8.1, §9;
  kernel, fixture.)
- **#22 — kinds.** `kind_type => atom & { of: type_name  members?: [type_name] }` is the
  atom whose values are the names of the types in the closure built with `of`, with
  `kind => <T> !kind_type { of: T }` as its inline spelling and `members` a resolver-derived
  index. The kernel declares `identifier_kind`, `atom_kind` and `type_kind`, types both
  `name_type` slots by `identifier_kind`, and retypes every slot that names the type of a
  value — `record_field.type`, `tuple_element.element_type`, `array.element_type`,
  `map.key_type` and `value_type`, `choice.variants`, `template_param.type` and `bound` —
  as `type_kind`; `type_ref` stands only where an application may. Core declares a `kind`
  sibling. (Part 2 §4.2, §5.3, §5.10, §7.4, §8.1, §9, §13; Part 3 §5, §5.6; kernel, core,
  fixtures.)
- **#23 — `namespace`.** `namespace => abstract top & { key_type: identifier_kind
  value_type: type_kind  ordered?: boolean ~ false }` is the constructor whose instances
  are maps keyed by names; `schema` and `field_map` are the kernel's two. A value of a
  namespace type is read as the map it describes, never as a record of its facets; a
  meta-schema declares one and a schema it governs holds a value of it as an entry
  (`orders => !interface { … }`), which is a value and never a type. (Part 2 §2.2.2,
  §2.2.3, §4.1, §5.2, §8.1, §9; kernel, fixture.)
- **#24 — `ref_type`.** `ref_type => atom & { of: type_name }` with `ref => <T> !ref_type
  { of: T }` is the atom whose values are qualified names of values; `of` names a type of
  the writer's own namespace, never a constructor. Kernel vocabulary only: core declares no
  `ref`, a reference naming an entry that holds a value, which only a kernel-governed
  schema has. (Part 2 §4.2, §5.12, §9; kernel.)

The artifacts also carry housekeeping the register did not raise, adopted here on the same
footing:

- **`identifier_kind`'s members are listed.** The kernel's resolved fixture writes
  `members: [field_name identifier param_name scheme_name type_name]` — the three roles
  are aliases of a member and listed beside it — and `atom_kind` and `type_kind` list
  theirs, `type_kind`'s including `top`, `namespace`, `field_map`, `schema` and the
  synthetic arrays.
- **`record_extension_type`'s doc** no longer cites §5.9's emptied `supertypes`: "a
  subtracted record's chain is empty, so it mints no IS-A edge".
- **`core.tn`'s reference list** drops RFC 4291, RFC 4632 and RFC 9542 with the families
  that left; `net.tn`'s carries them with RFC 1123, RFC 3492, RFC 5890–5893, RFC 5952,
  RFC 6838, RFC 6839 and RFC 9110 §8.3.1.
- **The resolved fixtures' conventions note** is rewritten for the new output shape: an
  entry is its value, a type body carries `chain`, a composition's fields are flattened
  with `chain` first, and a slot that names a type is `type_kind` and holds a bare name.

---

## 2. Disposition summary

| # | Entry (abridged) | Disposition |
|---|---|---|
| 1 | A refusal is a rejection by this processor, not "not judged" | **Accept.** [TSON-DATA] §8.1's fifth outcome becomes two: **refused** — this processor declined the document under its own configuration (its §8.2 policy and data version, its §9.1 limits, or a schema it does not hold and will not fetch or one past its size limit) — a rejection here and never a finding about validity, MUST be distinguishable from the four categories and MUST name the rule or limit; and **undetermined** — this processor could not obtain what it would judge against (nothing at the reference, no answer), and nothing was judged. The closing sentence reads "two conforming processors may legitimately disagree on whether they accept a document, while never disagreeing on validity". §8.2 and §9.1 cite "refused"; [TSON-SCHEMA] §10.1 and §11.2 split "its policy or its network did not supply" along the same line. One sentence added at adjudication: a refusal under a fetch policy SHOULD NOT disclose the allowlist. **[editorial]** [TSON-JSON] §9.4 and §10.1 already carry the outcome. [settled] |
| 2 | The network types are a library of their own, `net.tn` | **Accept — the baseline above.** Part 2 §1.5 counts nine companion artifacts and §13.2 carries the five normative pins, the four fixtures listed in §13.3; §9 states the principle: a family most schemas never use belongs in a library outside core. [TSON-DATA] §5.5's table gains a column naming the library each row's name comes from, and §5.6's rule is restated: a schemaless name denotes the type its library declares under that name, so a document moving under a schema that imports the library keeps its meaning. **A language change:** a schema importing core and using `ipv4`, `ipv6`, `cidr4`, `cidr6` or `mac` imports `net.tn` as well. [settled — implemented] |
| 3 | A type annotation outside the built-in vocabulary is a resolver error | **Accept**, on the register's four grounds: the vocabulary does not grow, gradual adoption runs through the built-ins, the verdict is portable, and the removed sign bounds protect no deployed document. [TSON-DATA] §1.5, §3.2, §5.1, §5.6 and §8.1 and [TSON-SCHEMA] §7.1 read as the register proposes; §3.1 and [TSON-SCHEMA] §6 are untouched, the preserved-uninterpreted treatment belonging to `@` annotations alone. A host-typed read is no exception, and host types as a schema is a caller's explicit choice outside the series' verdict. **A language change:** `!nonesuch` in a schemaless document is refused. [settled] |
| 4 | An identity is an IRI whose host is a `host` value | **Accept.** [TSON-DATA] §2.2.1: an identity's host is a `host` value as §5.5's row defines it (an `ireg-name` a `hostname`, an `IPv4address` an `ipv4`, an `IP-literal` an `ipv6`; IPvFuture and zone identifiers refused); canonical identity is the host's canonical text plus the path, the path NFC; `!!id` is written in canonical form only, and a reference may use any spelling of the same identity — A-labels, ASCII case, an uncompressed or bracketed IPv6 address, the URI spelling with percent-encoded UTF-8 read back and hex case ignored — and is read to canonical form before comparison; what has no IRI form stays an error. The Unicode-version consequence is stated beside §7.1's. The identity atom the HTTP layer asked for is **recorded as considered and not taken** (§5), for the register's reasons. [TSON-JSON] §3.5 already carries a reference beyond US-ASCII in its URI spelling. [settled] |
| 5 | `hostname` is a domain name in either label form | **Accept — the baseline above.** [TSON-DATA] §5.5 gains the row; the four boundaries an operator will meet (uppercase beyond ASCII, a trailing dot, a digit-initial last label, percent-encoding) are stated beside it. [settled — implemented] |
| 6 | `host`: a host name or an IP address, one string-class atom | **Accept — the baseline above.** [TSON-DATA] §5.5 gains the row; `[::1]` and `::1` are one value, the canonical text unbracketed, and `ipv6` itself still refuses the brackets. `allow_zone` is **recorded as the likely first facet** and not taken (§5). [settled — implemented] |
| 7 | §7.7 says identifier validity is the same at every Unicode version; §7.1 says it grows | **Accept** — §7.7 claims the weaker property §7.1 states: a text valid at one Unicode version is valid at every later one, a content-addressed schema's validity can be lost to no UCD refresh, and a processor on an earlier version may refuse a name a later one accepts, its report naming the version. [settled] |
| 8 | `media_type`: a media type and its parameters | **Accept — the baseline above.** [TSON-DATA] §5.5 gains the row. `types` and `suffixes` are member sets under §5.7 and `allow_parameters` a permission; the `charset` fold is the one value fold, the list closed. `uri_template` is **not proposed and not taken**. [settled — implemented] |
| 9 | The `type` kind | **Accept — the baseline above.** §4.1 is rewritten around `top`, `type`, the three base kinds and `namespace`; kind determination reads a base kind off the chain excluding `top` and `type`, with `namespace` read the same way. [settled — implemented] |
| 10 | A record's field names have a type: `record.name_type` | **Accept — the baseline above, and every reading the register settles as built**: one field for one mechanism on both constructors; a name type is an identifier family whose `normalization` implies NFC (`NONE` refused, `ASCII_CASEFOLD` admitted only over an all-ASCII profile); a document's name is matched in the type's form and an undeclared name is a closure error whether or not it is a value of the type; inheritance keeps one name type, as the constructor's rule, and supertypes with different ones do not compose; the brace form states a type by composition onto a fieldless abstract base and has no syntax of its own; a profile may not add a Default_Ignorable_Code_Point, a control or a space other than U+0020; a schema's own references to its fields match exactly; a lookahead does not judge a name; a document that names a schema has its field names judged by that schema alone, and a Class 1 processor reading it holds field names to no name rule. **Language changes:** `enum_type.type` is `name_type`; `!text_enum [...]` is `!text ^ { members: [...] }` or an enum over the author's own identifier type; a field-name position admits any single-line token. [settled — implemented] |
| 11 | The guide and the schema skill should say what `name_type` makes possible | **Accept for the guide** — a new section on the register's seven-point outline (§7.2). The `tson-schema` skill is published with the series but is not among this revision's inputs; its edits are recorded as outstanding (§5). [settled] |
| 12 | A record's fields are a map keyed by name | **Accept — the baseline above**, with the map ordered, the key family the record's `name_type`, and a duplicate refused by the layer that can see it: a spelling written twice is the map's duplicate key, two spellings that are one value of the name type are the linker's resolver error. [settled — implemented] |
| 13 | A value written by name: a qualified name | **Accept, as #24 refines it.** A new [TSON-SCHEMA] §5.12 states the qualified name — segments joined by `.`, the first a type name resolved as a `type_ref` is, each further one selecting a key of a namespace value or a field of a record value, every hop written, no index and no quoted segment — and its judgement: found first, judged after, never a choice among candidates, each refusal naming its own step. Under #24 a name stands at a slot typed `ref<T>` and nowhere else, so #13's bare-name-at-a-product-position rule is subsumed: §5.6's positional form keeps a bare token where the positional field is atom-typed, and in a schema document a product- or sum-typed positional fill is written as the explicit record (the register's decision 1); a data document reads every bare token as before (decision 3). Decisions 2, 4–11 are taken as built. [TSON-DATA] §7.1's `.` is the separator of [TSON-SCHEMA] §5.12. One consequence is flagged for confirmation (§5): `record_field.type` is now `type_kind`, an atom, so by the rule as stated `fields: { p => x }` is a positional fill and not the withdrawn spelling the register's vector pins. **[editorial]** [settled] |
| 14 | `type_definition` goes | **Accept — the baseline above.** §8.1 is retitled and rewritten around `type_ref` and `type_argument`; an entry resolves to its value; an entry's kind is its value's type's kind, judged by origin; `source` is gone and the sentences naming it in §5.5, §5.7, §5.8 and §5.10 read the tag; §5.9's lineage sentences and rule 10 go, a subtracted record's chain being empty; §7.2's "never the body's lineage" goes; `@application` is a key annotation beside `@synthetic`, derived, never author-written, read as stated on ingest; `chain` is never written by a declaration and never declared by a constructor. The in-memory fact the implementation keeps for a tightened constructor's one parent is noted and asks nothing of the text. [settled — implemented] |
| 15 | `top`, `type` and the base kinds are `abstract` | **Accept — the baseline above.** An annotation typed by a base kind is a schema error in either §6 position. [settled — implemented] |
| 16 | A record template's parameters stand in three places; a parameter is never an operand | **Accept.** §5.10 "Held bodies": in a `!record` body a parameter stands only at a field's type, a field's default or fixed value, or an argument of an application, anywhere else a resolver error naming the slot; §5.8: an operand may carry arguments naming the parameters, and an operand that is itself a parameter is a resolver error, under the shadowing rule. Templates over other constructors are unchanged. [settled] |
| 17 | A record template is a `record` with `parameters` | **Accept — the baseline above.** A record template is not held; the base's ABSTRACT is by construction and the record's own `extension` is each application's; `template` is not a type and an application of it is its expansion; a record template named bare at an operand of `&` or `^` is a resolver error. [settled — implemented] |
| 18 | An application nothing declares is its expansion | **Accept.** §8.2: a fully-bound application of a template materialises its expansion, a synthetic entry keyed on its content, and mints no instantiation entry unless the expansion names the application itself, where the knot is tied on the application; a minted record is a member of nothing, and one whose chain would name a declared type is a resolver error at the declaration that wrote the application; a declaration names only itself; a diagnostic names what the author wrote. The `vector_pixel_af3` example is rewritten. **A verdict change**, stated in §4: a minted record composing an application only a use site mints now loads. [settled] |
| 19 | A selector's type may contain no parameter, value parameters included | **Accept** — §5.10: a selector's declared type mentions none of the template's parameters, at any depth; a parameter-free application satisfies it. [settled] |
| 20 | A routed `= P` is FIXED where it is written | **Accept** — §5.7 "Open modifiers": the role is the modifier's, as beside a literal, and materialisation substitutes the argument into `value` without changing a fact; a FREE field never carries a value, open or closed. The kernel's `record_field` doc already states it. [settled — implemented] |
| 21 | A template's parameter pin is a selector whatever its name's mark | **Accept** — §5.7 and §5.10 drop "on an unmarked name"; the base reads the selector required with the pin dropped, and each member is FIXED to its argument, optional as the name was marked. [settled] |
| 22 | The types built with a constructor are a kind: `kind_type` | **Accept — the baseline above**, with `of` resolving in the structure namespace and naming `type` or `namespace` or a constructor below either (#23), membership one walk over resolved entries, a kind no bound, members computed at link over the governing closure, and the inline `kind<T>` sugar. **Open — two remainders** (§5): whether a kind admits an abstract type (running: membership by construction, which the text states), and the higher kind `kind_type.of` would want, which the series has none of. [settled — implemented / open] |
| 23 | A namespace is a constructor whose instances are maps | **Accept — the baseline above**, with the reading rule, membership (a namespace constructor is a type and a member of `type_kind` and the namespace kinds and of no other), only a namespace entered by a qualified name, a namespace type declared only by a meta-schema, and `data` removed. **Open — one remainder** (§5): whether a namespace *entry* (`myapi => !api { … }`) is listed by `kind<api>` and `kind<namespace>` as running, or only where its value is a type; the text states the running behaviour, marked for confirmation. **[editorial]** A namespace keyed by text and `&` over namespace values are not proposed and not taken. [settled — implemented / open] |
| 24 | A reference to a value: `ref_type` | **Accept — the baseline above.** The slot declares the spelling: a slot typed `ref<T>` holds the name and nothing else, one typed `T` the value inline; a kind-typed slot of a value body holds one bare name and refuses a braced application; a reference is kernel vocabulary and core declares none. [settled — implemented] |
| 25 | An import stands at the importer's governing level | **Accept** — §2.2.3: an imported schema MUST stand at the importer's governing level, both named the meta-kernel as their `!!meta` or neither, judged per import edge; a meta-schema declares the vocabulary its constructors use, and an ordinary schema reaches a meta-schema's constructors only through its `!!meta`. The bundled chain already imports at one level throughout. **A language change:** a schema governed by `meta.tn` importing `meta-kernel.tn` or a meta layer, and a kernel-governed schema importing `core.tn`, are refused. [settled] |

Counts: **25 accepted** (1–25 — of which #22 and #23 carry open remainders), **0
declined**, and **3 open questions carried** (#22's two, #23's one), plus the open
remainder of Revision 35's #25 and Revision 37's Part 3 ordered-map confirmation (§5).
Revision 37's open #2 — a namespace as a value — is closed by #13, #22, #23 and #24
together, and its open #3 — a JSON member name that is not an identifier — by #10. Open
questions are recorded here and are NOT reflected as open text in the specification.

---

## 3. Accepted changes by target document

### 3.1 Part 1 — Text Data Format

1. **§1.5** — the name MUST reads "every type-annotation and annotation name, and every
   field name of a document that names no schema" (#10); a type annotation outside the
   vocabulary MUST be reported as a resolver error (#3); the reporting bullets speak of a
   refusal as a rejection by this processor (#1).
2. **§2.2.1** — an identity's host is a `host` value (§5.5) and its path NFC; canonical
   identity is the host's canonical text plus the path; `!!id` is written in canonical
   form only; a reference may use any spelling of one identity and is read to canonical
   form — host spellings, URI spelling with percent-encoded UTF-8 read back and hex case
   ignored — before comparison; what has no IRI form stays an error; an identity's
   validity grows with the Unicode version as §7.1's does (#4).
3. **§2.5** — a field name is a value of its record's name type ([TSON-SCHEMA] §5.2), an
   identifier where no schema says otherwise and always in a schemaless document; the
   duplicate rule compares in the name type's form; the remedy "a key that is not a name
   belongs in a map" stays (#10).
4. **§2.8** — a record's first field name is a bare unquoted or single-line token; the
   identifier match moves to §2.5's schemaless rule (#10).
5. **§3.2** — a type annotation whose name is not in the built-in vocabulary, in a
   document with no schema in scope, is a resolver error (#3).
6. **§5.1, §5.6** — the marker sentences become resolver errors (#3); §5.6's rule is
   restated by library: a schemaless name denotes the type its library declares under
   that name (#2).
7. **§5.5** — the table gains a column naming the library each row comes from, and three
   rows: `!hostname`, `!host` and `!media_type`, each with its value space, equality and
   canonical text; the paragraph on the string-class atoms counts them (#2, #5, #6, #8).
8. **§7.1** — `_id` is a resolver error in a schemaless document and a parse error
   unquoted (#10); `.` is the identifier separator of [TSON-SCHEMA] §5.12, no longer
   "future", and the identifier profile is unchanged by it: a qualified name is a token
   the slot's type parses (#13).
9. **§7.4** — the `field-name` note: an identifier position in a schemaless document,
   judged by the record's name type under a schema (#10).
10. **§7.7** — the Unicode-version sentence (#7); a token at a naming position whose
    decoded text fails the grammar is a parse error at annotation and type-annotation
    names, and a resolver error at a schemaless field name (#10).
11. **§8.1** — the resolver errors gain a type annotation outside the vocabulary (#3) and
    a schemaless field name that is no identifier (#10); "not judged is a fifth outcome"
    becomes "refused and undetermined are two outcomes beside the four categories", each
    defined (#1).
12. **§8.2** — "the fifth outcome" reads "refused" (#1); a record's field names and an
    enum's members are names under their type's profile, and a profile may not add a
    Default_Ignorable_Code_Point, a control or a space other than U+0020 (#10).
13. **§9.1** — a limit refusal is the refused outcome (#1).
14. **§10** — RFC 3492, RFC 5890–5893, RFC 5952, RFC 6838, RFC 6839 and RFC 9110 join the
    normative references (#5, #6, #8), and RFC 5322, cited by §5.5 since Revision 36 and
    never listed, gets its row **[editorial]**.

### 3.2 Part 2 — Type System and Schema

1. **§1.3** — a Class 2 processor produces a schema value of the kernel's `schema`, a
   namespace from name to value; a resolved-output consumer dispatches on a record
   template's `parameters`, `extension` and `discriminators` and never reads held text
   (#14, #17); a refusal and an unavailable schema are the two outcomes of #1.
2. **§1.4** — a schema value is a value of `schema`, a namespace (#14, #23).
3. **§1.5** — nine companion artifacts, `net.tn` among the normative five (#2).
4. **§1.6** — the resolved-output sentence and the companion-fixture pointer follow the
   new shape (#14).
5. **§2.2.2** — a namespace type is a constructor, declared only by a meta-schema, which
   then owns the vocabulary its constructors use (#23).
6. **§2.2.3** — an imported schema stands at the importer's governing level (#25).
7. **§3.3.1** — the base-kind reader sentence goes (#15); `reference.target` and
   `kind_type.of` are the two slots beside a `!` head that may name a constructor (#22);
   the namespace types (`field_map` and `schema` among them) join what is applicable and
   `type_chain` and `template_param` what is not, the kernel's `!schema { … }` being the
   application resolver output writes (#23).
8. **§4.1** — rewritten: `top => abstract {}`, `type`, the three base kinds composing
   `type`, `namespace` composing `top` with its reading rule, `reference` and `template`;
   no `data`; the IS-A order and `type_chain`; a kind derived by origin (#9, #14, #15,
   #23).
9. **§4.2** — `kind_type`, `kind`, `identifier_kind`, `atom_kind` and `type_kind`;
   `ref_type` and `ref`, kernel vocabulary; a type slot is `type_kind` (#22, #24).
10. **§4.3** — the operations table: subtraction empties the chain and keeps no lineage
    (#14).
11. **§5.2** — `name_type` and its rules — the default, inheritance, the brace form by
    composition, the form implying NFC, the profile floor, matching in the type's form,
    a document's name judged by the record that reads it; `fields` as a map and the
    duplicate layers; `discriminators` `[text; 1..]`; "a family member is declared"
    rewritten for #18, a minted record being a member of nothing (#10, #12, #18).
12. **§5.3** — a type slot is `type_kind` and holds a bare name; an argument is read as
    its parameter's type (#22).
13. **§5.4** — an enum's class is read off its members under its `name_type`, an
    identifier family; the `text_enum` sentence goes; a kind and a reference are
    string-class (#10, #22, #24).
14. **§5.5** — three clauses for `hostname`, `host` and `media_type`'s value spaces (#5,
    #6, #8).
15. **§5.6** — the positional form keeps a bare token where the positional field is
    atom-typed; in a schema document a product- or sum-typed positional fill is the
    explicit record; the end state has no `source` (#13, #14).
16. **§5.7** — the role is the modifier's where it is written; a parameter pin is a
    selector whatever its mark (#20, #21); `types` and `suffixes` among the member sets
    and `allow_parameters` among the permissions (#8).
17. **§5.8** — a parameter is never an operand (#16); supertypes with different name
    types do not compose (#10); the resolution paragraph reads `chain` (#14).
18. **§5.9** — the lineage sentences and rule 10 go; the resolution paragraph: the chain
    is empty (#14).
19. **§5.10** — rewritten in its template-forms, held-bodies and family-base paragraphs:
    a record template is a `record` with `parameters` and only a template over another
    constructor is held; a parameter stands in three places in a record body; a selector
    mentions no parameter; the base's ABSTRACT is by construction; a record template
    named bare at an operand is refused; an application nothing declares is its
    expansion; `type_kind` marks a type parameter (#16, #17, #18, #19, #22).
20. **§5.11** — member slots are `text`, judged by the record's name type (#10, #12).
21. **New §5.12 — Values by name** — the qualified name, `ref<T>`, the judgement, the
    refusal table, and what the rule does not decide (#13, #24).
22. **§6** — `application` joins `synthetic` among the resolver-attached annotations
    (#14).
23. **§7.2** — subsumption reads `chain.supertypes`; the lineage sentence goes; `!schema`
    marks a namespace value (#14, #23).
24. **§7.4** — `enum_type.name_type: identifier_kind`; `text_enum` removed and a closed
    text set pointed at `text_type.members`; `identifier_base` with `ASCII` and
    `scheme_name` restated; a name type's form implies NFC; the profile floor (#10).
25. **§7.7** — a document's field names are matched in the record's name type's form,
    stated as a paragraph of its own since §7.2 and Part 1 §2.5 cite §7.7 for it (#10).
26. **§8.1** — retitled "Output Records: `type_ref` and `type_argument`" and rewritten:
    an entry is its value; `type_chain` and where `parents` holds an application; kind
    derived by origin; where `type_ref` stands and where `type_kind` does;
    `record_field` without `name`; `field_map`; `@application`; the ingest rules —
    `chain` read as stated, `members` and `subtypes` recomputed, `@application` kept;
    the body-patterns table (#9, #10, #12, #14, #17, #22, #23).
27. **§8.2** — two identities restated without `source`; an application nothing declares
    is its expansion; the self-reaching case; a minted record is a member of nothing; the
    `vector<pixel, 1920>` example (#14, #18).
28. **§8.3** — the `source` sentences go; an alias's `target` may name a constructor
    (#14, #22).
29. **§9** — the kernel and meta rows rewritten; a `net.tn` row; core's contents and the
    library principle; the extension guidance reads "a slot that names a type is typed by
    a kind, a slot that names a value by a reference, and `type_ref` is for a slot that
    holds an application" (#2, #9, #10, #12, #14, #17, #22, #23, #24).
30. **§10.1, §11.2, §11.5** — the two outcomes of #1; a fetch refusal does not disclose
    the allowlist **[editorial]**; an allowlist matches a host as a `host` value (#4, #5).
34. **§7.8** — `scoped.schemas` is written keyed by `schema_identity`, as `meta.tn`
    declares it and as the same paragraph already said; Revision 37's `{uri => …}` was a
    slip **[editorial]**.
35. **§5.9** — rule 8: a subtracted construction whose head is a constructor is no longer
    one, its chain being empty and IS-A `top` the constructor predicate; Revision 37 kept
    it a "constructor with lineage and no contract", and with lineage gone (#14) the text
    follows the predicate **[editorial]** — for the spec author to confirm.
31. **§11.4** — a record's field names and an enum's members are scopes under their name
    type's profile (#10).
32. **§12.1** — the `type-name` note's field-name clause: any single-line token, judged
    at link by the record's name type (#10); the abstract note gains the six kernel
    entries (#15).
33. **§13** — RFC 3492, RFC 5890–5893, RFC 5952, RFC 6838, RFC 6839 and RFC 9110 added;
    §13.2 carries the five normative artifacts with the pins as received, and §13.3 the
    four fixtures.

### 3.3 Companion artifacts

The revised artifacts shipped with this revision carry the baseline of §1 and every
kernel-, meta-, core- and net-level change in this log. Their entries are not edited by
this adjudication; §6 lists three doc lines that disagree with the text and are left as
received, with the pins they would move.

### 3.4 Part 3 — JSON Encoding

[TSON-JSON] is edited directly as findings arise, and arrived with the Part 3 halves of
five entries already in place: #1 (§1.6 item 19, §9.4, §10.1), #4 (§1.6 item 18, §3.5),
#10 (§1.3 principle 3, §1.6 item 15, §3.2, §3.3, §6.1.1, §8.3.1, §9.4), #17's library
(§1.6 item 17, §5, §5.6) and #22 (§1.6 item 16, §5, §5.6). The second pass completed it
for the rest, and §7.1 lists the edits. Part 3 defines no type-system rule, so each is a
spelling of a rule §3.1 or §3.2 owns.

---

## 4. Notable normative changes (reader's digest)

Changes a Revision 37 implementer must act on:

1. **Resolved output has a new shape.** `type_definition` is gone: an entry is its value
   (`person => !record { … }`, `age => !integer_type { chain: { supertypes: [integer] }
   min: 0 }`), a type carries `chain: { parents? supertypes? subtypes? }`, a record's
   `fields` is a map from name to `record_field` with no `name` inside, and a record
   template is `!record { fields: { … }  parameters: [ … ] }`. Every fixture changes.
2. **A record's field names have a type.** `record.name_type` names an identifier family,
   `field_name` by default; `enum_type.type` is `name_type`; `text_enum` is gone. A
   schema translated from JSON-LD or OpenAPI declares `json_name => !identifier_type {
   start_add: "@$" }` on a fieldless abstract base and composes it.
3. **A field-name position admits any single-line token.** Schemaless, a name that is no
   identifier is a resolver error; under a schema the record's name type judges it, and
   an undeclared name is a closure error.
4. **A document that names a schema is judged by that schema alone.** A Class 1 processor
   reading it holds its field names to no name rule.
5. **`type` is a kernel kind.** `atom`, `product` and `sum` compose it; `data` is gone;
   `top`, `type`, the base kinds and `namespace` are abstract.
6. **`namespace` is a constructor whose instances are maps.** `schema` and `field_map`
   are compositions over it; a meta-schema declares its own, and a governed schema holds
   a value of it as an entry.
7. **Kinds.** `kind<C>` is an atom whose values are the names of the types built with
   `C`; every slot that names the type of a value is `type_kind` and holds a bare name;
   `type_ref` stands only where an application may. Core declares `kind`, so a schema
   importing core may no longer declare one.
8. **References.** `ref<T>` is an atom whose values are qualified names, `orders.place_order`,
   kernel vocabulary only; a slot typed `ref<T>` holds a name and a slot typed `T` a
   value inline.
9. **An import stands at the importer's level.** A `meta.tn`-governed schema may not
   import the kernel or a meta layer; a kernel-governed schema may not import core.
10. **The network types are in `net.tn`.** `ipv4`, `ipv6`, `cidr4`, `cidr6` and `mac`
    leave core; `hostname`, `host` and `media_type` join beside them, in the library and
    in the schemaless vocabulary.
11. **An identity's host is a `host` value.** `!!id` is written in canonical form; a
    reference may use any spelling and is read to canonical form before comparison.
12. **An unknown schemaless type annotation is a resolver error.** `!nonesuch` is refused
    where it was a preserved marker.
13. **Refused and undetermined.** A refusal is a rejection by this processor and never a
    finding about validity; a schema that could not be obtained leaves the document
    undetermined; a schema this processor will not fetch is a refusal.
14. **A record template's parameters stand in three places**, and never as an operand of
    `&` or `^`; a selector's type mentions no parameter; a routed `= P` is FIXED where
    written; a parameter pin is a selector whatever the name's mark.
15. **An application nothing declares is its expansion**, and mints no instantiation
    entry; a minted record is a member of nothing. One verdict changes: `u: vip<text>`
    over `vip => <T> box<T> & { … }` beside `w: box<text>` now loads.
16. **`identifier_base` gains `ASCII`**, and `scheme_name` is written over it.
17. **`@application`** is a derived key annotation on an entry that closes an application,
    read as stated on ingest; a declaration never writes it, nor `chain`.
18. **Every bundled pin changes** (§6).

---

## 5. Open questions carried by this change log

Adjudicated 2026-10-26. The following remain deliberately open, or are the revision
editor's readings awaiting the spec author's confirmation. They live in this change log
only — the specification text carries no open questions.

| Ref | Question | Status |
|---|---|---|
| Rev 35 #25 (→ —) | Annotation cardinality as the means of per-name replacement. | Open — recorded as considered. |
| Rev 37 #10 (Part 3) | The two MUSTs [TSON-JSON] §6.4 states for an ordered map. | Open — still the spec author's to confirm; not raised in the register. |
| #22 (remainder) | Whether a kind admits an abstract type: membership by construction, as running (`kind<record>` holds `pet => abstract { … }` and a family base; `kind<namespace>` holds `namespace` itself), or a concrete-only kind. | **Confirmed** (§9): membership by construction, as the text states; a record template is listed, and naming it at a kind-typed slot is refused as open (§4.2, §5.12). |
| #22 (remainder) | `kind_type.of` is typed `type_name` and not by a kind: its values are names of constructors, which no kind covers, so it would want a classifier of constructors the series has none of. | Open — the store is stated in prose, as every constructor role's is. |
| #23 (remainder) | Whether a namespace *entry* (`myapi => !api { … }`) is listed by `kind<api>` and `kind<namespace>`, as running, or a kind's members are entries IS-A `type` or namespace constructors only. | **Confirmed** (§9): a namespace entry is listed by the kinds of its constructor and by no kind of types; the sentence was missing and §4.2 now carries it. |
| #13 (reading) | `record_field.type` is `type_kind` under #22, an atom; by §5.6's rule as #13 states it, `!record { fields: { p => x } }` is then an atom-typed positional fill of `type`, not the withdrawn product-typed spelling the register's vector pins. The text states the rule; the vector may be stale. | **Confirmed** (§9): the text is right and the implementation admits the fill; the register's vector was stale. |
| #1 (addition) | A refusal under a fetch policy SHOULD NOT disclose the allowlist ([TSON-SCHEMA] §11.2). | **Confirmed** (§9). The reference implementation discloses the list at adjudication and is to change. |
| §5.9 rule 8 (reading) | A subtracted construction headed by a constructor is not itself a constructor: its chain is empty, and IS-A `top` is the predicate (§4.2). Revision 37 said "a constructor with lineage and no contract". | **Confirmed** (§9): the chain is empty and the application is refused as not applicable. |
| §8.1 (consequence of #22) | `record_field.type` is `type_kind`, an atom, so `record_field` has a positional form and a data document may write `fields: { x => text }`; canonical output writes the explicit record. | **Confirmed** (§9), in a schema document as in a data document (§5.6). |
| #4 (considered) | An identity atom whose equality is canonical identity. | **Not taken** — every atom pins an external standard, `identity` is a name core should not take, and canonical text in `text` meets the need. |
| #6 (considered) | `allow_zone` on `host_type`, for a listener binding a link-local address. | **Not taken** — recorded as the likely first facet. |
| #8 (considered) | `uri_template`. | **Not taken** — a pattern over values is not a value. |
| #11 (outstanding) | The `tson-schema` skill's "Records and fields", "Enums", conversion and pitfall sections. | Outstanding — not among this revision's inputs; the guide's new section is the model. |

Decisions taken 2026-10-26: #1 the two outcomes, and a fetch refusal naming no allowlist;
#3 the resolver error, with host-typed reads no exception; #4 the host as a `host` value and
the identity atom not taken; #13 subsumed by #24 and the positional rule as stated; #16, #19,
#20 and #21 as the register proposes; #18 the self-reaching exception and the one verdict
change; #22 and #23 with their remainders open and the running behaviour stated; #25 the
level rule per import edge.

---

## 6. Artifact work

1. **Received at Revision 38** — all nine artifacts carry "2026 Revision 38 draft" and
   `/2026/38/` identities, and each normative pin was checked against sha256 over the
   bytes past the `!!id` line of the copy received and **matched**: kernel `44c9268c…`,
   meta `1c1d87b4…`, core `11e69386…`, net `765628e6…`, policy `bd594937…`. The chain is
   consistent — meta pins the kernel, core, net and policy pin meta, policy pins core.
   Hash *values* remain non-normative; only the pin's shape is.
2. **Re-stamped before publication** (§9). The doc lines below disagreed with the text
   and are corrected, so every pin moves: kernel `9cc191d3…`, meta `62dde2f3…`, core
   `95f66505…`, net `06a939fa…`, policy `fc9208c7…`; Part 2 §13.2 carries the five rows.
3. **The doc lines corrected:**
   - The kernel's `application` doc wrote its application of a namespace entry as "a
     DATA entry", a kind #23 removes; it now says "a namespace entry".
   - The kernel's `kind_type` doc said `of` "is `type` or at or below it", where #23
     admits `namespace` or a constructor below either, and said a kind is "not IS-A
     `top`" with no exception for a namespace constructor; it now states §4.2's rule,
     membership by construction included.
   - The kernel's `type` doc said `type_kind` holds "the one constructor"; its `top` doc
     omitted the namespace types; its `type_chain` doc said a template's `parents` are
     "kept after it closes" — each now says what §4.1 and §4.2 say.
   - `core.tn`'s header listed `uri` among core's same-named siblings of kernel and meta
     entries; the sibling is `iri`.
   - `meta-kernel-resolved.tn`'s conventions note said a parametric `= P` or `~ P`
     "fixes nothing until materialisation", counted "the four base kinds", and described
     every open entry as held; it now states #20's rule, three base kinds and
     `namespace`, and a record template as a `record` with `parameters`. `core-resolved.tn`'s
     note cited §8.1 for the shadowing rule, which is §5.10's.
4. **Not verified**, carried as a note: ingest of resolved output is unimplemented, so
   #14's "read `chain` as stated" and "keep `@application`" and #22's recomputed `members`
   are stated in §8.1 and not running; a later schema's application is not yet unified
   with an imported library's declared one for the same reason.

---

## 7. Second pass: Part 3 and the Developer Guide

Both ship with Revision 38. Neither carries a decision that §2 does not record; where a
sentence had to be written that no register entry dictates, it is marked **[editorial]**
and is the spec author's to confirm.

### 7.1 Part 3

tson-part3-json.md is the copy received, completed for the entries it had not yet
consumed. Header and status read Revision 38 and drop "proposed and not yet published";
series references move to `/2026/38/`.

1. **§1.2** — "a field name is an identifier at every layer" reads "a field name is a
   value of its record's name type, an identifier by default" (#10).
2. **§1.6** — items 15 to 19 are Revision 38's, published; item 16 says `ref_type` stands
   in schema documents only, which have no JSON form, and is listed for the record.
3. **§3.2** — the `$type` row reads "string (a type name)", a type name being what the
   kind-typed slot holds (#22); the sentence after the table that a schema SHOULD NOT
   declare the three stands (#10).
4. **§6.4, §10.4** — "never reserved", "no `$`-reservation" and "the reserved namespace
   itself is not spoofable … and not extensible" are restated over the three annotation
   members, used and not reserved (#10). **[editorial]** No change of meaning.
5. **§9.4** — the outcome paragraph reads refused and undetermined as #1 states them,
   already in place; the series references in §11.2 follow.
6. **Cross-references.** `[TSON-SCHEMA] §8.1`'s title changes, and the three places
   Part 3 cites it for `record_field`'s facts are checked against the renumbered
   section; no section number moves.

### 7.2 Developer Guide

tson-guide.md is realigned on the same two principles as before: it describes the design
as it stands, and history lives in the change logs.

1. **§1.3, §1.6, §4.3** — schemas as data under the new output shape (`person => !record
   { fields: { … } }`), nine artifacts, and `type` among the kernel entries meta's
   declarations use (#9, #12, #14).
2. **§2.7** — a record template as a `record` with `parameters`, an application nothing
   declares as its expansion, and the three places a parameter stands (#16, #17, #18).
3. **§2.9** — a routed `= P` FIXED where written (#20) and a parameter pin a selector
   whatever the mark (#21).
4. **§2.10** — a minted record is a member of nothing (#18).
5. **New §2.11 — "A name has a type"** — the register's seven-point outline for #10 and
   #11: what a name is; what breaking out makes possible (JSON vocabularies, words as
   names, names compared without case, a house style); when it is the right tool and
   when it is not; what it costs; the safety that stays on; a worked JSON-LD conversion.
6. **§3.3, §3.5** — an identity's host as a `host` value and the spellings a reference
   may use (#4); the unknown-annotation rule (#3); the Unicode-version property stated
   as §7.1 states it (#7).
7. **§3.5** — where the policy lives: `policy.tn` (Revision 37's answer, which the guide
   still described as undefined), and refused against undetermined (#1).
8. **§4.5** — the network library beside "why core is small" (#2).
9. **§6.1** — retitled "`value`, `void`, `identifier` and the kinds" (#22); **§6.4** —
   `namespace` beside `scoped` as the kernel's other open construct (#23).
10. **New §6.8 — "Kinds, namespaces and references"** — the proto-schema reading of
    `kind<C>` (a universe's code type, a subuniverse carved by construction), why a kind
    is not a sum, `namespace` as the constructor whose instances are maps, `ref<T>` and
    the qualified name, and the meta-service example end to end (#13, #22, #23, #24).
11. **New §6.9 — "Host names, hosts and media types"** (#5, #6, #8).
12. **§7's worked example** is rewritten in the new output shape: entries as values, a
    `chain` on `priority`, `title_text` and the instantiation, `flagged` as a `!record`
    with `parameters` and `extension` unstated (the base's ABSTRACT being by
    construction), `fields` as maps, and `history`'s array naming the synthetic the
    expansion lands on. **[editorial]** — no shipped fixture holds a record template or
    an instantiation; the shape follows §8.1 and §8.2 as revised.
13. **§8.1** — two identities restated without `source`; `@application` on a declared
    entry that closes an application (#14, #18).
14. **§8.3, §9.2** — refused against undetermined in diagnostics and at the boundary
    (#1); an import at the importer's level (#25).
15. **References** gain RFC 3492, 5890–5893, 5952, 6838, 6839 and 9110; identities are
    `/2026/38/`.

The Revision 33 guide candidates remain outstanding.

---

## 8. Third pass: verification

Run after the parts and the guide were revised, by a reader who had not produced them,
against the register and the nine artifacts. Each of the 25 entries' suggested-resolution
items was found in the sections it names in Parts 1, 2 and 3; the five pins were
recomputed over the shipped bytes and match §13.2; the kernel's declarations (`record`,
`record_field`, `template_param`, `kind_type`, `ref_type`, `namespace`, `field_map`,
`schema`, `type_chain`, `enum_type`, `identifier_base`, `scheme_name`), core's `kind` and
absent `ref`, net's eight entries and the fixture's `type_kind` membership (`top`,
`namespace`, `field_map`, `schema` and the synthetic arrays in; `type`, `atom`, `record`
out) agree with §4.2 and §9; no Revision 37 vocabulary remains outside sentences that name
an earlier revision; §5.12's nine citations resolve and §6–§8 keep their numbers. What the
pass found, and what was done:

1. Part 2 §7.7 was cited by §7.2 and by Part 1 §2.5 for the name-type matching rule and did
   not state it — stated now (§3.2 item 25).
2. Part 2 §7.8's `scoped` was written `{uri => …}` where `meta.tn` keys it by
   `schema_identity`, as the same paragraph said; corrected in Part 2 and the guide (§3.2
   item 34).
3. Part 2 §5.2 and §9 counted `type` among `record_field`'s "four facts"; the kernel counts
   four beside `type`, and the text now does.
4. Part 2 §4.1 said a record template's `parents` are kept "after the template closes",
   which read as the instantiation's; it now says the template entry keeps them and a
   closed instantiation keeps none, as §5.8 says.
5. Part 2 §1.6's example still shipped "revision 37"; Part 3 §3.2's `$type` row still read
   "string (identifier)"; both corrected.
6. This log claimed RFC 1123 among the added references and nine rows in §13.2; neither
   was so, and the log now says what the parts do. Part 1 §10.1 gains the RFC 5322 row
   §5.5 has cited since Revision 36, and the guide the RFC 5891 row its range implied.
7. The kernel fixture's conventions note disagrees with #20 and with the kernel's own
   `record_field` doc; recorded in §6.3 beside the three doc lines already there.
8. The guide's §2.11 lacked one of the register's four stated costs (a host binding names
   a relaxed name through the name the record states); added.


---

## 9. Fourth pass: the implementation's verification

Run by the implementer against the adjudicated text, entry by entry, before publication,
with each of the five editorial readings of §5 checked against running code and confirmed
as the table now records. The text carried every entry's substance; what it did not carry,
or carried against itself, was raised as eleven findings and folded into Parts 1 and 2 in
this pass, so the register opens against Revision 38 empty:

1. Part 2 §5.5 carried no value-space clause for `hostname`, `host` or `media_type`,
   though §3.2 item 14 above claimed them and six citations pointed there; the three
   clauses are written, `types` and `suffixes` compared without ASCII case.
2. "Refused" being a term, Part 1 §8.1's validity-at-every-processor claim is qualified by
   the Unicode version the text needs, an earlier-version processor's report is a lexer
   or resolver error, and twenty-four loose uses of "refuse" for a validity error now name
   the error.
3. Part 1 §7.7 named the joining-control contexts' properties as stabilised; they are
   not, and the monotone guarantee now states its one exception, for identifiers and for
   host names alike (§2.2.1, §5.5, §7.1, §8.1, §8.3).
4. Part 2 §5.2 and §11.4 put an undeclared name at closure before hygiene, against
   [TSON-JSON] §9.4 and the readers; a value of the name type now meets §8.2 first.
5. Part 2 §4.2 states that a namespace entry is listed by its constructor's kinds, the
   namespace-type carve-out in the membership walk, where `kind<namespace>` holds
   `namespace` itself, `top` as a constructor and a `type_kind` member, and why
   `ref_type.of` is `type_name`; §9's "the one slot typed `type_name`" is corrected.
6. §5.12 refused a record template a kind lists; both facts are now stated together.
7. §5.6 and §5.12 overstated which bare tokens are refused; §4.2's placement paragraph
   said a subtraction off a constructor IS-A `top`, against §5.9 rule 8.
8. Seven sentences from the held-template reading survived for record templates: the
   base's `extension` "stated", "ABSTRACT does travel", the `type_ref` slot lists,
   §12.1's `instance` comment, §5.2's "same checks", a deferred-diagnostic example and
   §5.8's alias-to-instantiation sentence.
9. §8.2's `vector<pixel, 1920>` use site beside `hd_row`, its identity bullet, §8.3's
   "the one `!reference`", §5.10's `c3` and §5.10.1 disagreed on instantiation identity
   and `@application`; and #18's verdict change is now stated in §8.2.
10. "Empty chain" where the fixture has `subtypes`, "four facts" counting `type`,
    `extension` unmarked in §9, and the canonical `!record_field` tag.
11. A dozen wrong examples and cross-references, the §5.10 selector example, `{ _id: 1 }`
    in Part 1 §2.5 and a literal U+00AD in §8.2 among them.

What remains open is §5's, and the `tson-schema` skill's edits.
