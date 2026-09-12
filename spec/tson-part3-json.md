---
title: "TSON Part 3: JSON Encoding"
draft: "2026"
status: "Working Draft"
part: 3
description: >
  The JSON encoding of the TSON schema system: how schema-governed TSON values are
  carried in RFC 8259 JSON — the reserved member namespace, the annotation object,
  atom lexical forms, container syntax, the treatment of absence, and the
  discrimination predicate over the derived disjointness fact.
---

# TSON Part 3: JSON Encoding

## 2026 Revision 36

**Status:** Working revision. The 2026 revision series is subject to change without compatibility guarantees. When finalised, this specification will be published as part of **TSON version 1** and frozen on the terms of [TSON-DATA] §1.2 principle 6. This is the first published revision of Part 3. It is written against Revision 36 of [TSON-DATA] and [TSON-SCHEMA] and published with them; every type-system change it depends on has landed in one of the two revisions those documents have had since this document was drafted, and §1.6 lists them and where each now lives.

**Series:** TSON Specification, Part 3 of 4

**Copyright:** © 2026 Litterat Pty Ltd. This document is licensed under the Creative Commons Attribution-ShareAlike 4.0 International License (CC BY-SA 4.0): https://creativecommons.org/licenses/by-sa/4.0/


## 1. Introduction


### 1.1 Purpose and Scope

TSON is a schema system with multiple encodings. The type system of [TSON-SCHEMA] is encoding-independent: schemas define values — atoms with parsing contracts, products with field states, sums with a derived disjointness fact — and each encoding states, in its own encoding-rules document, how those values are carried. [TSON-DATA] is the notation and the reference encoding. This document is the second encoding, and [TSON-CBOR] the third: it defines how schema-governed TSON values are carried in RFC 8259 JSON.

The document is shaped by [TSON-SCHEMA] §7's model of what an encoding owes: its atom lexical forms (§5), its container syntax (§6), its treatment of absence (§7), and its discrimination predicate over the resolver-derived `disjoint` fact (§8). To those four it adds the two things JSON lacks that TSON text has natively — a document header and an annotation slot — supplying both through one mechanism, a reserved member namespace no declared name can collide with (§3).

This document defines **encoding rules only**. It does not define a conversion between JSON Schema and TSON schemas; that mapping is a tooling concern, produced against this document and [TSON-SCHEMA] but normative in neither. It does not define a JSON form for schema documents: schemas are authored, published, and hash-pinned as TSON text ([TSON-SCHEMA] §10.1), and this document's subject is data.


### 1.2 Position in the Series: JSON Interoperability Lives Here

Through Revision 34, JSON interoperability was carried by [TSON-DATA] as a syntactic claim: valid JSON is (with two character-level exceptions) valid TSON text, maintained as a design principle of the text format itself. That claim taxed the text format everywhere the two designs disagree — it is why schemaless field names were lexical rather than identifier-constrained, why `null` held keyword status in base type resolution, and why the string grammar carried JSON's escape inventory — and the tax bought a weaker property than it appears to: pasting JSON into a TSON parser yielded untyped structure under base type resolution, not typed data. Revision 35 withdrew the claim ([TSON-DATA] §1.1, §6) and, with it, every rule that existed only for it: `null` is no longer a value in the notation, a field name is an identifier at every layer, the string grammar escapes scalar values and has no surrogate pairs, and a comma may follow a value. [TSON-DATA] §6 now says what is JSON-*shaped* and stays, and names this document as the reader through which a JSON document reaches the model.

From this revision, **this document is the normative JSON interoperability surface of the series.** "TSON reads and writes JSON" is answered here, as a mapping between the type system's values and JSON texts — schema-directed, lossless within its stated bounds, and stronger than the syntactic claim ever was, because what comes out of a decode is typed, validated data rather than a token tree. The notation stays JSON-like by design and is not a JSON superset; JSON compatibility is this reader's job, done properly, rather than a property the notation kept at the cost of its own consistency.

The two documents' encodings divide cleanly. TSON text is the **self-describing** encoding: a text document can be read without its schema (base type resolution, the built-in vocabulary), and schemas themselves are written in it. The JSON encoding is **schema-directed**: a JSON text is read against a schema, and without one it is just JSON. That asymmetry is deliberate and load-bearing — it is what lets the JSON encoding serve plain JSON to JSON consumers (§1.3, principle 2) instead of serving TSON syntax smuggled into strings.


### 1.3 Design Principles

In addition to the principles of [TSON-DATA] §1.2 and [TSON-SCHEMA] §1.2:

1. **The schema is the reader.** Decoding is defined only against a schema: every rule in this document reads a JSON value *at a typed position*, and what a JSON form means is determined by the position's declared type, never by inspecting the value twice. This is the JSON restatement of the once-only reading of form ([TSON-DATA] §2.4): the encoding consults the JSON value kind exactly once, where the type system's discrimination rules ask for it (§8), and nowhere else.

2. **Plain JSON out.** The common case — records of atoms and containers, no subsumption, no non-disjoint sums — encodes to JSON with no TSON-specific apparatus at all: no reserved members, no wrappers, no in-band type names. A JSON consumer with no knowledge of TSON reads the document as ordinary JSON, and an OpenAPI-style contract describes it. The reserved machinery of §3 appears on the wire only where the type system carries information JSON's own forms cannot: a schema binding, a subtype selection, a tag the discrimination predicate requires.

3. **A reserved namespace by construction.** Every TSON declared name is an identifier ([TSON-DATA] §7.7), and no identifier begins with `$` — the character is not `XID_Start` and is not in the token profile. JSON member names beginning with `$` are therefore structurally collision-free with every declared field name in every schema, and the encoding claims that namespace for its own members (§3.2). No escaping rule is needed and none is defined.

4. **JSON null is the absent sentinel.** The model has exactly one no-value concept — absence — and JSON's one gap-shaped token is its spelling: `null` is admitted exactly where the text encoding admits `_` under a schema, with the same meanings, and is an error everywhere else (§7). The notation holds no null value ([TSON-DATA] §2.9, §4.4), so there is no second gap concept to disambiguate and no position is ever read by weighing two meanings of one token.

5. **No silent loss.** Wherever the JSON encoding cannot carry something the model holds — today, an annotation (§4.3) — the encoder refuses with a categorized error. Stripping is available only as an explicit, code-level configuration, on the terms of [TSON-DATA] §8.2's relaxation rule: greppable, attributable, never ambient.

6. **Derived facts, not declarations.** The wire rules consume what the resolver derives — the `disjoint` fact in each choice body, a record's own extension fact, the value→variant mapping of a sealed family reconstructed from its `REQUIRED_FIXED` pins — and never introduce wire-only declarations that could drift from the schema. A schema fully determines its JSON encoding; there are no encoder dialects.

7. **Values, not spellings.** Equality, identity and content addressing are over value spaces ([TSON-SCHEMA] §5.5), and this encoding's forms are spellings of values: what it promises to preserve beyond the value — an exact number's digits and scale (§5.3), a rational as written (§5.5), an instant's offset (§5.6) — is a round-trip courtesy of this encoding, never a claim about the type.


### 1.4 Notation and Keywords

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in RFC 2119.

"JSON value", "object", "array", "member", "name", "number", "string", `true`, `false`, and `null` are used in the sense of RFC 8259. Where this document must distinguish, a JSON construct is italicized or prefixed ("JSON `null`") and a TSON construct is named by its series term ("the absent sentinel", "the void contract"). The **JSON value kinds** are the six syntactic categories of RFC 8259: null, boolean, number, string, array, object.

Error categorisation follows [TSON-DATA] §8.1; §9.4 maps this document's rules into those categories.


### 1.5 Conformance

This document defines the series' third conformance class.

A **Class 3 processor** (JSON-encoding processor) is a Class 2 processor ([TSON-SCHEMA] §1.3) that additionally implements this document. Decoding is schema-directed (§1.3, principle 1), so there is no JSON-encoding counterpart of Class 1: a processor that cannot resolve schemas cannot implement these rules. Such a processor:

- MUST accept exactly the JSON profile of §3.1 and reject documents outside it with categorized diagnostics;
- MUST implement the reserved member namespace (§3.2) and the annotation object (§3.3), including the recognition and escape rules at map and choice positions;
- MUST decode every JSON value by the rules of §5–§8 for its position's declared type, producing decoded output equivalent to the same value's decode from TSON text — defaults injected, fixed fields verified, rest members collected, group presence counted;
- MUST implement the discrimination predicate of §8.2 exactly — neither proving more (no structural heuristics beyond the stated class dispatch) nor less;
- MUST, when encoding, emit only documents this document's decode rules accept, and MUST refuse rather than silently drop anything §4.3 declares uncarryable;
- MUST report errors in the categories and phrasings of [TSON-DATA] §8.1, per the mapping of §9.4.

An implementation MAY be encode-only or decode-only; each side's obligations stand alone.

**Equivalence with the text encoding.** The two encodings carry the same model. For every schema and every value the JSON encoding can carry (§4.3), decode-from-JSON and decode-from-text MUST produce equivalent decoded output — same fields, same states resolved, same host values under each atom's contract. The encodings differ in what they can carry (annotations, §4.3) and in spelling latitude (radix, quote forms), never in the meaning of what both carry.


### 1.6 The Type-System Changes This Document Consumes

This document was drafted against six type-system changes: five landed in Revision 35 and the sixth, item 3, lands in Revision 36 with this document. Each is listed with where it now lives and what this document consumes; the owning documents define them, and nothing here restates a rule they carry.

1. **No `null` value in the notation** ([TSON-DATA] §2.9, §4.4, §6; [TSON-SCHEMA] §4.2, §7.3). Base type resolution is boolean → number → string and applies only in schemaless documents; `value` admits boolean, integer, float and string; `void` admits `_` alone. Consumed by: the whole of §7 — this document reads JSON `null` as the absent sentinel's spelling, which is coherent only in a model that holds no null value — and by [TSON-DATA] §6, which names this document as the JSON reader.

2. **`members` on the exact numeric tiers** ([TSON-SCHEMA] §7.4, §9): `integer_type.members` and `decimal_type.members`, a sparse-value-set facet under §5.7's member-set tightening, compared by the family's value identity. Listed for the record rather than consumed: member-set-constrained numerics encode as ordinary numbers (§5.3), and JSON Schema's mixed-scalar enums convert to a choice over an identifier enum and a member-set numeric, disjoint by class and therefore tag-free here by §8.2's ordinary route.

3. **Record extension, and the discriminator field** ([TSON-SCHEMA] §5.2, §8.1, §9) — the Revision 36 change, and the one this document was drafted ahead of. A record states how it may be realised — OPEN, ABSTRACT, SEALED or FINAL — and a field of a sealed record is a discriminator. Both are facts of the resolved body, not annotations: the author writes `@sealed` and `@discriminator`, the resolver consumes them, and resolved output carries the body fields alone. A **sealed family** is the abstract case carrying one or more such fields: the base declares them unpinned, every subtype pins them `REQUIRED_FIXED`, the pins are pairwise distinct (as tuples, where there is more than one), and the value→variant mapping is reconstructed from those pins and never declared. Consumed by: §6.1.5, which reads the members to recover the subtype, and §8.2, whose predicate is one route shorter for it. **This document maps a type-system capability rather than defining a JSON mechanism**: the base's instantiability and the selector's identity are facts of the model, so both encodings read them and every rule here is a spelling. That both encodings read them is also why neither fact is an annotation — a mark no encoding can erase without changing what documents are valid is not confined to the encodings that claim it, which is the test §6 sets for the directive category. Revision 35 stated `@discriminator` over choice declarations, where the mechanism it names cannot reach; the retargeting to record families, and the two kernel fields it needs, are what Revision 36 landed.

4. **The `@rest` annotation** ([TSON-SCHEMA] §6): `rest => @annotation void`, a checked annotation on a record field whose type resolves to a text-keyed map, at most one per composed chain. The record stays closed in the model; the designation has force in this encoding alone — the text and CBOR encodings write the map nested — which is why it is an annotation and not a kernel attribute. Consumed by: §6.2.

5. **The value-space clause** ([TSON-SCHEMA] §5.5): a type denotes a value space, an encoding a lexical space over it, and equality, ordering, refinement, disjointness and content addressing are over values only. Consumed by: §5.3, §5.5, §5.6 and §6.5, which distinguish what this encoding *preserves* from what the type *is*.

Two things this document once anticipated did not land as drafted and are recorded so that no reader looks for them. A `required_keys` facet on `map` is deferred: named required keys are declared record fields beside an `@rest` tail (§6.5), and maps stay homogeneous. And `extern`/`unknown` were replaced by the `scoped` constructor ([TSON-SCHEMA] §7.8) — core's `declared`, `extern`, `dynamic`, `extern_of<S>` and `extern_type<S, T>` — under a rule this encoding consumes directly: *a value at a scoped position must name its type* (§5.7, §8.5).


### 1.7 Terminology

- A **JSON document** (in this document's sense) is a single RFC 8259 JSON text carrying a TSON value under a schema binding (§3.4).
- The **reserved members** are the JSON member names this encoding claims: `$schema`, `$type`, `$value` (§3.2). The set is closed.
- An **annotation object** is a JSON object using reserved members to carry what TSON text carries out-of-band: a schema scope, a type annotation (§3.3). It has an **inline form** (reserved members beside a record's own members) and a **wrapper form** (reserved members plus `$value`).
- A **tag** is an in-band record of a variant or subtype selection — the JSON counterpart of TSON text's `!name` annotation, carried by `$type` or by a discriminator member.
- A position's **effective type** is the position's declared type after following its reference chain ([TSON-SCHEMA] §8.3), or the type a tag at that position selects, once admitted.
- A **scoped position** is one whose effective type is a `scoped` instance ([TSON-SCHEMA] §7.8) — core's `declared`, `extern` or `dynamic`, or an application of `extern_of`/`extern_type`.
- **Class-stable** describes a type every one of whose values encodes in that type's own discrimination class (§8.3).


## 2. A Complete Example

A schema exercising a sealed record family, defaults, optional fields, and a non-text-keyed map:

```
!!id:"https://example.com/pets.tn"
!!meta:"https://tson.io/2026/36/m/meta.tn"
!!import:"https://tson.io/2026/36/m/core.tn"
{
  pet => @sealed { @discriminator pet_type: text  name: text  nickname: text? }
  cat => pet & { pet_type: = cat  hunting_skill: text ~ lazy }
  dog => pet & { pet_type: = dog  pack_size: positive_integer }
  registration => {
    id:      uuid
    pet:     pet
    tags:    [text]?
    weights: {date => number}
  }
}
```

The same value in the two encodings. TSON text:

```
!!schema:"https://example.com/pets.tn"
!registration {
  id:  9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
  pet: { pet_type: dog  name: Rex  pack_size: 3 }
  weights: { 2026-07-01 => 12.5  2026-08-01 => 13.1 }
}
```

JSON encoding:

```json
{
  "$schema": "https://example.com/pets.tn",
  "$type": "registration",
  "id": "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09",
  "pet": { "pet_type": "dog", "name": "Rex", "pack_size": 3 },
  "weights": { "2026-07-01": 12.5, "2026-08-01": 13.1 }
}
```

Everything TSON text carries out-of-band has moved in-band or into the schema. The root's `$schema` and `$type` are the inline annotation object (§3.3) standing in for the header directive and root annotation; below the root, no reserved member appears. The `pet` value is **tag-free in both encodings**, and for one reason rather than two: `pet` is a sealed family — no direct instances, one discriminator field — so at a `pet` position the `pet_type` member selects the subtype, matched against the variants' pinned values to yield `dog` (§6.1.5). Neither document needs a tag; either MAY write one (`!dog`, `"$type": "dog"`), and a written tag MUST name the dispatched subtype. Note what the schema does *not* contain: no choice enumerating `cat` and `dog`, and no mapping table. The family is the composition, and the selector values are the pins. `nickname` is absent — omitted here, though a member with value `null` would have spelled the same absence, because JSON null is the absent sentinel (§7); the decoded value records neither spelling. `hunting_skill` never appears — the value is a `dog` — and `tags`, optional, is absent. `weights` is a date-keyed map in object form: member names are parsed by the key type's contract, so `"2026-07-01"` is a `date` key, not text (§6.5). Decoding this JSON document and decoding the TSON text produce the same value.


## 3. Documents and Schema Binding


### 3.1 The Accepted JSON Profile

A document under this encoding is a single JSON text conforming to RFC 8259, with the following requirements resolving what RFC 8259 leaves open — each an error, in the category noted, never implementation latitude:

- **Encoding.** The document MUST be UTF-8. RFC 8259 §8.1 exempts exchanges within closed ecosystems from its UTF-8 rule; this encoding defines interchange, so the exemption does not apply here. A byte order mark MUST NOT be emitted; a decoder MUST accept and discard a single leading U+FEFF. Invalid byte sequences are lexer errors on [TSON-DATA] §7.1's terms — no replacement characters, no continuation.
- **String well-formedness.** An unpaired surrogate escape, and any escape or raw sequence that would decode to a surrogate code point, is a lexer error. TSON values are Unicode scalar sequences ([TSON-DATA] §7.2.2, whose own escape names a scalar value or nothing); JSON texts that encode data no string can hold are rejected, not repaired. This is the I-JSON (RFC 7493) reading, which RFC 8259 permits. A JSON `\uD83D\uDE00` pair is one character here, as RFC 8259 defines it — the surrogate-pair mechanism is JSON's, and this profile accepts JSON's grammar; only the ill-formed halves are refused.
- **Duplicate member names.** RFC 8259 leaves duplicate names to the implementation; this encoding does not. A repeated member name within one object is an error at the repeated occurrence — the category follows the position's type, per the same layering as [TSON-DATA] §2.5/§2.6: a resolver error at record positions (duplicate field) and at map positions under textual or decoded-key identity, a validation error where only the declared key type relates the pair ([TSON-SCHEMA] §7.7). Name identity for this rule is the NFC-normalized decoded string, matching [TSON-DATA]'s identity rules.
- **Numbers.** JSON's number grammar imposes no precision limit and neither does this encoding. A decoder MUST preserve a number's digits into the host value the position's atom contract defines; an implementation that cannot represent the digits MUST error, never round silently. Length limits are the configurable resource bounds of [TSON-DATA] §9.1, reported as such.

Nothing else is added and nothing is taken away: any JSON text within this profile is structurally acceptable, and everything beyond structure — what the values mean — is the schema's, per the rest of this document.

Documents use the media type **`application/tson+json`** (intended for IANA registration, structured-syntax suffix per RFC 6839). No file extension of its own is defined: a JSON encoding of TSON data is a JSON file, and `.json` is its extension — the TSON contract travels as the schema binding, not the filename.


### 3.2 The Reserved Member Namespace

Member names beginning with U+0024 DOLLAR SIGN (`$`) are reserved to this encoding **at every position where member names are field names** — that is, wherever this document reads an object as a record or as an annotation object. The reservation is sound by construction: declared field names are identifiers ([TSON-DATA] §7.7), no identifier begins with `$`, so no schema can mint a colliding field ([TSON-SCHEMA] §12.1). Three reserved members exist:

| Member | Value | Carries |
|---|---|---|
| `$schema` | string (URL) | A schema binding — the JSON counterpart of the `!!schema` directive ([TSON-SCHEMA] §7.1) |
| `$type` | string (identifier) | A type annotation — the JSON counterpart of `!name` ([TSON-DATA] §3.2) |
| `$value` | any JSON value | The annotated value, in the annotation object's wrapper form (§3.3) |

**The set is closed**, on the same terms as the directive name set ([TSON-DATA] §3.3): a member beginning with `$` whose name is not in the table, appearing at any position where the namespace is reserved, is a resolver error. There is no unknown-reserved-member category and no extension mechanism; new capability arrives through the type system, not through the wire vocabulary.

**Where the namespace is *not* reserved.** Map keys are data, not names ([TSON-DATA] §2.6): at a map-typed position, every member name — `$`-initial or not — is an ordinary key, parsed by the declared key type's contract (§6.5). The one place the two readings could collide — a map standing as a choice variant, where the decoder must first decide whether the object is a tagged form — is settled by the escape rule of §8.3.1. Everywhere else the split is total: records and annotation objects own the `$` namespace; maps never see it as reserved.


### 3.3 The Annotation Object

TSON text attaches schema scope and type annotations *beside* a value (`!!schema:"…" !claim { … }`); JSON has no beside. The **annotation object** is the JSON carrier for both, in two forms:

**Wrapper form.** An object whose members are reserved members only, with `$value` present:

```json
{ "$type": "age", "$value": 42 }
{ "$schema": "https://tson.io/2026/insurance/claim.tn", "$type": "insurance_claim",
  "$value": { "claim_id": "CLM-5678", "amount": 450.00 } }
```

The annotated value is the `$value` member, read at the type `$type` selects (under `$schema`'s scope, when present). In wrapper form, any member other than the three reserved names is a resolver error — the wrapper is apparatus, not a record, and admits nothing else. The wrapper form carries an annotation for a value of **any** shape: atoms, arrays, tuples, maps, and records alike.

**Inline form.** When the selected type reads the value as a record, the reserved members MAY instead stand beside the record's own members, and `$value` is absent:

```json
{ "$type": "employee", "name": "Ada", "department": "Engines" }
```

The record is the object minus its reserved members. The two forms denote the same value; the inline form exists because wrapping every subtyped record would bury the common case (§1.3, principle 2), and it is unambiguous because no record field is spelled with `$` (§3.2). Encoders SHOULD emit the reserved members **first**, ahead of a record's own, so that a decoder recognises the form from the opening members rather than from the whole object (§8.2). A SHOULD and not a MUST because a decoder may not require it: §6.1.6 gives member order no meaning, and a decoder rejecting a late `$type` would have invented a rule this document does not contain. The obligation is the encoder's, and it costs nothing — the reserved members are apparatus, and apparatus goes first. Encoders SHOULD use the inline form for record-shaped values and MUST use the wrapper form for everything else — a map's members are data and cannot share an object with reserved members (§3.2), so a map, like every non-record value, always rides in `$value`.

**Recognition.** An object is read as an annotation object exactly where a tag or scope is *meaningful*, and nowhere else:

- at any position, when the position's rules require a tag (§8) or the position is scoped (§8.5) — there the annotation form is mandatory, not merely recognized;
- at any typed position other than a map-typed one, when the object carries reserved members — this is what admits voluntary tags: a redundant `$type` restating the position's own type, or a subtype selection under subsumption (§6.1.5);
- **never** at a map-typed position: the members are keys, and an enclosing choice's escape rule (§8.3.1) is the sole route by which a map value can coexist with the reserved namespace.

`$type` MUST resolve in the active schema's type-name namespace and MUST be admissible at the position under [TSON-SCHEMA] §7.2's subsumption rule (the position's type, or a variant of it at choice positions, or a type the scoped instance admits at scoped positions — §8.5); the admitted value then validates in full against the selected type. `$schema` follows the scoped-directive rules of [TSON-SCHEMA] §7.1 and §7.8: it opens a scope for the annotated value alone, is a resolver error at any position whose effective type is not a `scoped` instance admitting `EXTERN` (or a container of one), and at such a position both members are REQUIRED for a foreign value — the same visibility rule as text, in the only slot JSON has.

The annotation object nests the way scoped values do: `$value`'s content is an ordinary value at the selected type, and may itself contain annotation objects at its own positions. Reserved members do not cascade — each annotation object's members scope to that object alone.


### 3.4 Root Binding and the Validation Posture

A JSON document's binding — which schema, which root type — arrives by exactly one of two routes:

1. **Out-of-band.** The application supplies the schema and root type (an API contract, a registered media-type profile, a function argument). The document is then a bare value: its root is read directly at the supplied type, and reserved members appear only where the value's own rules put them. This is the expected production route, and it is why principle 2 holds — a contracted endpoint's payloads carry no TSON apparatus at all. For HTTP, §3.5's header field is this route's standard carrier.
2. **In-band.** The root value is or is wrapped by an annotation object carrying `$schema` and `$type`: the document names its own binding, as a TSON text document's header and root annotation do. Both are REQUIRED on this route: under a schema the root names its type or the document is invalid ([TSON-SCHEMA] §7.1), in every mode — there is no vocabulary-only reading, since a value read by no type is a schemaless value and this encoding has none.

Where both routes supply a binding, they MUST agree — canonical-identity agreement for the schema ([TSON-DATA] §2.2.1), name agreement for the type; disagreement is a resolver error, never a precedence question. Schema references resolve through the schema library under [TSON-SCHEMA] §10 unchanged: URLs are logical identifiers, fetching is opt-in, hash-pinned references (`?sha256=`) verify per [TSON-DATA] §2.2.1.

**JSON documents are not content-addressed artifacts.** The encoding defines no `$id`, no id-line convention, and no hash-input rule: JSON tooling re-serializes freely (member order, whitespace, number spelling), so byte identity is not a property this encoding can promise, and a hash convention that most tooling would silently break is worse than none. Identity and pinning belong to TSON text documents, where [TSON-DATA] §2.2.1 defines them; a system that needs a pinned artifact pins the text encoding of the value.


### 3.5 The `TSON-Schema` Header Field

When a document travels over HTTP, the out-of-band route of §3.4 has a standard carrier: the **`TSON-Schema`** header field, defined here for every TSON-carrying body and closing the question [TSON-DATA] and [TSON-SCHEMA] left to the encodings. The field is an RFC 9651 structured field whose value is a single Item of type String, holding a schema reference exactly as `!!schema` and `$schema` hold one, resolved and verified on their terms ([TSON-SCHEMA] §10; [TSON-DATA] §2.2.1). The String type is load-bearing, not style: a hash-pinned reference carries `?` and `=`, which RFC 9651's unquoted Token grammar cannot hold — a Token-typed field would parse in every unpinned test and fail on exactly the references the series recommends most strongly.

```
Content-Type: application/tson+json
TSON-Schema: "https://example.com/pets.tn?sha256=c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5"
```

Four rules, each mirroring one the series already has:

- **Projection, not alternative.** The field projects the document's binding into the transport, so that intermediaries — gateways, routers, caches, none of which parse bodies and some of which cannot (`Content-Encoding`) — can route by schema without opening the message. It binds the document exactly as §3.4's out-of-band route does, and it is defined for any TSON-carrying body: `application/tson+json`, where it is the only possible out-of-band channel a wire message has, and `application/tson`, where it accompanies the directive.
- **Agreement.** Where the field and an in-band binding (`$schema`, or `!!schema` on a text body) are both present, they MUST agree by canonical identity ([TSON-DATA] §2.2.1) — scheme and hash pin excluded from the comparison as always. Disagreement is an error, never a precedence question: the same posture as [TSON-DATA] §2.2.1's two-digest conflict rule, and for the same reason — silent precedence is how a document gets validated against a schema nobody chose.
- **A sender's claim, never a receiver's instruction.** The field states what the sender says governs the body. It is not license to validate an unmarked document against a schema of the receiver's choosing — that applies a contract nobody agreed to. A body naming no schema through any channel is simply unbound (§3.4); whether an endpoint accepts one is endpoint policy, not a property of the media type or of this field.
- **The reverse direction.** A client states which schemas it can read back with **`TSON-Accept-Schema`**: an RFC 9651 List of Strings, each optionally weighted with a `q` parameter, standing to `TSON-Schema` as `Accept` stands to `Content-Type` — a second field rather than a second meaning, because one message routinely asks both at once.

Field names follow RFC 9110 §16.3's registry (provisional registration on expert review suits the working-revision status) and RFC 6648's rule against `X-` prefixes; registration is intended alongside the media types.


## 4. The Encoding Model


### 4.1 Schema-Directed Reading

Every rule in §5–§8 has one shape: *at a position whose effective type is T, JSON form F carries value V.* The decoder walks the JSON value and the schema together, and each JSON value is read exactly once, by the contract of its position's type — atoms by their parsing contracts (§5), containers by their constructors' shapes (§6), sums by the discrimination rules (§8). Nothing is ever read speculatively: where two readings could apply to one form (object as record, map, annotation object, or tagged variant), a stated rule — the position's type, the reserved-member test, the discrimination predicate — selects one before the value's content is touched.

**A stated rule is not the same as a rule answered by one token.** Three of the tests above read an object's *member names* — the reserved-member test (§3.3), a sealed family's discriminator members (§6.1.5), and a choice's `$type` (§8.2) — and §6.1.6 gives member order no meaning, so none of them can be answered from the opening brace. A decoder therefore reads member names, skipping their values, before it knows which reader owns the object; the names are bounded by the object, which §10.1's limits already bound, and a value need not be parsed to be skipped past. Each such test is single in that it is decided once and never revisited, not in that one event of lookahead answers it — a decoder designed on the second reading cannot recover when the selector arrives late, which §6.1.6 permits it to.

The base type resolution of [TSON-DATA] §4 has **no role** in this encoding: it applies only in schemaless text documents ([TSON-DATA] §4.1), and there are no schemaless JSON documents in this encoding's sense — a JSON text without a binding is outside this document (§3.4). Positions typed by the kernel's `value` escape hatch, whose inhabitants are a boolean, an integer, a float or a string ([TSON-SCHEMA] §4.2), take the reading of §5.7, which is this encoding's own and invokes nothing from §4.


### 4.2 JSON Value Kinds and Discrimination Classes

The resolver's discrimination classes ([TSON-SCHEMA] §5.4) exist so that every encoding can state how much of a value's class its own forms recover. JSON has six value kinds and the type system five classes; the null kind carries no class at all — it is the absent sentinel (§7), spent before any class question arises — and the remaining five nearly map one-to-one:

| Discrimination class | JSON value kind(s) |
|---|---|
| `boolean` | true / false |
| `number` | number — **and string, for the approximate atoms' special values (§5.4)** |
| `string` | string |
| `brace` | object — **and array, for maps in pairs form (§6.5)** |
| `bracket` | array |

The two bolded leaks are the whole story of §8's class-stability rule: the mapping is one-to-one except where an atom family's values overflow JSON's forms (IEEE special values have no JSON number spelling) or a container's keys overflow JSON's member names (non-scalar map keys have no string form). Everywhere else, the JSON value kind *is* the discrimination class, which is what makes tag-free disjoint choices decodable from the wire alone (§8.2).


### 4.3 What the Encoding Does Not Carry

Two things the model holds have no JSON carrier, by design. Each is an encode-side refusal under principle 5 — a categorized error, never a silent drop — and a bound worth knowing before choosing this encoding for a document:

- **Annotations.** TSON data values carry ordered `@name` annotations ([TSON-DATA] §3.1); this encoding defines no channel for them. A wire channel was considered and declined for v1: annotations are ordered, repeatable, and positioned (map keys, values, fields), and a faithful carrier would bolt a second document structure onto every value — the apparatus principle 2 exists to keep off the wire. Encoding a value that carries annotations is an **encode error**; an implementation MAY offer stripping as an explicit code-level configuration ([TSON-DATA] §8.2's relaxation terms), and documents whose annotations are content belong in the text encoding.
- **Spelling.** Radix (`0xFF`), digit separators (`1_000`), quote form, multi-line form, and layout are text-encoding spellings, not information content; they are not preserved across encodings, and their loss is not an error. What is preserved is each atom's information content on [TSON-DATA] §5.2's terms — a `number`'s digits and scale, a `uuid`'s 128 bits.

Type annotations are *partially* carried, and deliberately so: a tag reaches the JSON wire exactly where it carries information — subsumption, non-tag-free choices, extern — and is recoverable nowhere else (an unannotated value at a typed position is exactly the position's type, [TSON-SCHEMA] §7.2). A redundant tag is never wrong (§3.3 admits it) but is not owed preservation: round-tripping a text document that annotates redundantly MAY lose the redundant annotations.


## 5. Atoms

Each atom family's JSON form is stated against its parsing contract ([TSON-DATA] §5, [TSON-SCHEMA] §7.4). One rule covers decode for every family: where a family's rules below admit a **string**, the string's content is handed to the atom's own parser exactly as a TSON quoted token's text would be — the contracts of [TSON-DATA] §5 apply verbatim, escape-processed content in, host value out; where they admit a **number or boolean**, the form is the value. JSON null never reaches an atom's reading at all: it is the absent sentinel, judged by the position's own absence rules (§7) before any family rule applies. Which JSON kinds a family admits is stated per family; a kind a family does not admit is the wrong-form validation error below. Validation (range, length, pattern, and the rest of the constraint vocabulary) is unchanged from [TSON-SCHEMA] §7.4: it applies to the parsed host value and knows nothing of the encoding.

A JSON value of the wrong kind at an atom position — an object where a number is due, a number where the contract wants string content — is a validation error (*value of the wrong form*, the [TSON-SCHEMA] §7.7 category), except that an object carrying reserved members is first given the annotation-object reading of §3.3.

The families:

| Type(s) | JSON form (encode) | Notes |
|---|---|---|
| `boolean` | true / false | §5.2 |
| enums | form of each member's lexical class | §5.2 |
| `integer` and the fixed-width/bounded family | number, no fraction or exponent | §5.3 |
| `number` (exact tier) | number | digits and scale preserved by this encoding, §5.3 |
| `float32` / `float64` | number; strings for special values | §5.4 |
| `rational`, `complex` | string (`"2/3"`, `"3+4i"`) | §5.5 |
| `text` and refinements | string | §5.6 |
| `uri`, `regex`, `email`, `uuid`, `mac` | string | §5.6 |
| `date`, `time`, `datetime`, `duration`, `period` | string | §5.6 |
| `ipv4`, `ipv6`, `cidr4`, `cidr6` | string | §5.6 |
| `bytes` and its instances | string, in the type's `encoding` alphabet | §5.6 |
| `void` | null (the absent sentinel — its sole value) | §5.7 |
| `value` | boolean, number, or string | §5.7 |
| scoped positions (`dynamic`, `extern`, …) | an annotation object naming the type | §5.7, §8.5 |


### 5.1 The Contract Boundary

The string rule above is the whole interface: this encoding adds no atom grammar of its own. `"2026-07-01"` at a `date` position, `"9f1c8e2a-…"` at a `uuid` position, `"AQID"`… at a `bytes` position — each string's content faces the same parser, the same acceptance set, and the same error split (contract rejection is a resolver error; constraint violation after parsing is a validation error, [TSON-DATA] §5.2) as the equivalent TSON token. Quoting questions do not arise: JSON strings are the only string form, so the text encoding's always-quote content kinds ([TSON-DATA] §7.1) need no counterpart rule here.

Two text-side lexical concessions do not carry over, because they were properties of the TSON lexer, not of the atoms: based-integer and separator spellings are not part of any atom's *content* grammar reachable from JSON (a JSON number has one grammar), and there is no unquoted form to profile. The atoms' content grammars that live above the lexer — the rational, complex, hex-float, and special-value productions of [TSON-DATA] §7.6 — remain reachable, as string content, exactly where the family's rules below invoke them.


### 5.2 Booleans and Enums

`boolean` — the kernel's `!enum [true false]` — encodes as JSON true/false. This is the general enum rule applied, not an exception to it:

**An enum member's JSON form is the form of its lexical class.** Each member token has a base-resolution class ([TSON-SCHEMA] §5.4 already reads it for discrimination): an identifier member encodes as a JSON string of the member's NFC text (`"ACTIVE"`); `true`/`false` encode as JSON booleans. (Sparse *numeric* value sets are not enums but member-set-constrained numeric atoms — [TSON-SCHEMA] §7.4 — and encode as ordinary numbers under §5.3.) Decode inverts by the same rule: the arriving JSON value's kind and content are matched against the member set — a string against identifier members by NFC text identity, booleans against their literals. A value matching no member is a validation error.

Uniqueness and the at-least-one-member rule are schema-load facts ([TSON-SCHEMA] §7.4) and need nothing from the encoding.


### 5.3 Exact Numerics

The exact tier — `integer` and its fixed-width and bounded refinements, and `number` — encodes as JSON numbers. JSON's number grammar is a subset of the tier's value spellings and a superset of none of them, so both directions are total:

- **Encode.** An integer encodes with no fraction and no exponent, in decimal — based-form spellings do not survive (§4.3), the leading `+` is dropped (JSON admits none), and separators are dropped. A `number` encodes preserving its **digits and scale**: `199.90` encodes as `199.90`, not `199.9`. Scale is *not* part of the value — `199.90` and `199.9` are one `number`, and every comparison, bound and `members` check is over that one value ([TSON-SCHEMA] §5.5) — so this is a promise of the encoding, not of the type: JSON's grammar carries the digits as written, and this encoding carries them through, because a document read by schema-less JSON consumers should show what was sent. An encoder MUST NOT re-spell an exact value through a binary intermediate; the digits are what is preserved.
- **Decode.** A JSON number with no fraction or exponent part decodes at integer-family positions by the integer contract; one with either part is not an integer — `1.0` at an `integer` position is a resolver error (contract rejection), exactly as the token `1.0` is in text. At `number` positions any JSON number is accepted and its spelling retained for re-encoding, exponent form included (`6.02e23` is a fine `number`); width, range and `members` validation then apply to the value per the constraint record, and `1` and `1.0` are one member of a set and one key of a map (§6.5).

Precision is the profile rule of §3.1: arbitrary, preserved, and error-over-round. Interoperability with ecosystems that funnel JSON numbers through IEEE 754 binary64 (I-JSON, RFC 7493, and every stock JavaScript parser) is a real deployment concern and is addressed as one in §10.3 — by warning, not by weakening: this encoding does not stringify large integers, because a rule that moves `uint64` to strings re-types the wire by consumer limitation, forks every consumer's reading of one schema, and helps only consumers that could as easily use a digit-preserving parser.


### 5.4 Approximate Numerics

`float32` and `float64` values divide into the finite grid and the special values, and the JSON forms follow the divide:

- **Finite values** (signed zeros and subnormals included) encode as JSON numbers. `-0.0` is a valid JSON number and MUST round-trip with its sign, per the family's value set ([TSON-DATA] §5.6). Decode accepts any JSON number and rounds it onto the format's grid — roundTiesToEven, loss of precision expected, per the family's contract; integer-form numbers are accepted, as the text atoms accept integer tokens.
- **Special values** — the two infinities and NaN — have no JSON number spelling and encode as JSON **strings** holding the special-value production of [TSON-DATA] §7.6: `".inf"`, `"-.inf"`, `".nan"`. Decode at an approximate position hands string content to the family's parser, which accepts exactly the special-value forms (and, as in text, the hex-float production); any other string content is a resolver error. The TSON spellings are used rather than an IEEE-flavored `"NaN"`/`"Infinity"` so that the two encodings share one grammar and one parser for the same content — no third spelling of infinity enters the series.

The string-form specials are the first of the two class leaks (§4.2): a type that admits them has values in JSON's string kind, and §8.3 accounts for it. A schema that narrows `allow_nan` and `allow_infinity` to false confines the family to JSON numbers and restores class stability — which gives API authors a concrete, checkable reason to narrow.


### 5.5 Rational and Complex

`rational` and `complex` encode as JSON strings holding the corresponding [TSON-DATA] §7.6 production (`"2/3"`, `"3+4i"`, `"-1.5-2i"`), decoded by the same atoms' contracts. Rational tokens are string-shaped in the text encoding already (the `/`); complex gains quotes it did not need there, which is the cost of JSON having no second unquoted class. As in text, this encoding preserves a `rational`'s spelling (`"2/4"` round-trips unreduced) while the value is the fraction — `"2/4"` and `"1/2"` are one value for every comparison ([TSON-SCHEMA] §5.5).


### 5.6 String-Content Atoms

The remaining families — `text` and its refinements, `uri`, `regex`, `email`, `uuid`, `mac`, the temporal families, the network families, and `bytes` — encode as JSON strings whose content is exactly the token content the family's [TSON-DATA] §5 contract defines: RFC 3339 forms for the temporal atoms, RFC 4648 with required padding for `bytes`, and so on. Nothing family-specific is added; §5.1's boundary rule is the entire specification, and every MUST in the text contracts (padding, the email dot-atom core, nonzero host bits under a CIDR prefix) binds identically here. Three families want a sentence on what the value is, since the value-space clause ([TSON-SCHEMA] §5.5) decides what this encoding compares and what it merely preserves:

- **`bytes`** is an octet sequence, and the string is a spelling of it in the alphabet the type's `encoding` selector names — base64 for core's `bytes`, another RFC 4648 alphabet for an instance that selects one (`hexdigest => !bytes_type { encoding: HEX  length: 4 }`). An encoder writes the selected alphabet; a decoder reads it; length facets count octets. Two spellings of one octet string are one value wherever values are compared.
- **`time` and `datetime`** are instants: the offset is a spelling, `"2026-01-01T10:00:00+01:00"` and `"2026-01-01T09:00:00Z"` are one value, and both families are totally ordered. This encoding preserves the offset as written, as the text encoding does; an encoder re-emitting a decoded value MAY normalise to `Z` and is not required to. The fraction is at most nine digits.
- **`duration` and `period`** are a signed count of seconds and a signed count of months, in RFC 3339 Appendix A's `duration` grammar split as [TSON-DATA] §5.4 splits it: `"PT36H"`, `"P2W"`, `"-PT0.5S"` at `duration`; `"P3M"`, `"P1Y6M"` at `period`. An encoder writing a `duration` emits `PTnHnMnS`, the week and day designators being accepted and not produced.

`text` accepts every JSON string. A JSON string is a sequence of Unicode scalar values by §3.1's profile, which is precisely the `text` value set; content that the text encoding could carry only in a multi-line token (raw newlines, mixed quotes) is ordinary escaped content in JSON, and no information distinguishes the forms.


### 5.7 Void, Value, and Scoped Positions

**`void`** — the atom whose sole value is absence — needs almost no rule of its own here: at a `void`-typed position the absent sentinel is the only conforming value, and JSON null is its spelling (§7). A REQUIRED `void`-typed field is encoded as a member with value null; decode at a `void` position accepts null and nothing else. The rule is one instance of §7's general one, not a concession: [TSON-SCHEMA] §7.3 admits `_` and nothing else at `void`, and this document is where JSON-shaped data meets that rule.

**`value`** — the escape hatch, admitting boolean, integer, float, and string ([TSON-SCHEMA] §4.2) — accepts true, false, a number, or a string. The decode is this encoding's own reading of the token's inhabitants, and needs none of base type resolution because JSON's grammar has already done the classifying: booleans → booleans; a number without fraction or exponent → an integer, with either → a float; a string → a string, its content uninspected — `"null"` is the four-character string, with no quoting dance, because JSON's forms are not overloaded the way unquoted tokens are. An object or array at a `value` position is a validation error, as in text ([TSON-SCHEMA] §4.2 — `value` admits scalars); JSON null follows §7, so at a REQUIRED `value` field it is an error and at an absence-admitting position it is the absence. A `value` position is a single token and not a scope: `$schema` there is a resolver error ([TSON-SCHEMA] §7.8).

**Scoped positions.** No type in the series admits an untyped JSON value of any shape. `unknown` is gone; its successor, core's `dynamic`, is a `scoped` instance ([TSON-SCHEMA] §7.8), and *a value at a scoped position must name its type*: in this encoding it MUST be an annotation object (§3.3) — `$type` alone for a type of the governing namespace (the LOCAL cell), `$schema` and `$type` for a foreign one (the EXTERN cell), inline when the named type reads the value as a record and wrapper otherwise — and a bare JSON value there is a validation error in every mode. The rules are §8.5's; what this section records is the consequence for the case `unknown` used to serve. A contract that must carry *arbitrary JSON* declares it, and the declaration is short:

```
json => ( text | number | boolean | [json?] | {text => json?} )
```

The choice is `disjoint: true` by construction — string, number, boolean, bracket, and brace, one variant per class — so every JSON value of §3.1's profile decodes at a `json` position tag-free by §8.2's route 2, nulls inside arrays and objects landing as absences, and re-encodes to the JSON it was. That is `unknown`'s old decoded form, obtained from an ordinary declaration the schema controls rather than from a permissive type the encoding had to interpret.


## 6. Containers


### 6.1 Records

A record encodes as a JSON object: one member per present field, member name = field name (the identifier's NFC text), member value = the field's value at the field's declared type. Everything [TSON-SCHEMA] defines about records binds unchanged; the encoding adds only the member mapping. Point by point:

**6.1.1 Closure.** Records are closed under their type ([TSON-SCHEMA] §7.2): a member whose name matches no declared field is a validation error — unless the record declares a rest field, in which case §6.2's collect rule takes it, or the name begins with `$`, in which case §3.2's reserved rules take it. Member names are NFC-normalized before matching, per [TSON-DATA] §7.2.1's resolver rule; two members whose normalized names collide are the §3.1 duplicate error.

**6.1.2 Presence and absence.** An absent OPTIONAL field has two spellings — the member omitted, or present with value null — exactly as text has omission and `_`, and on the same license: [TSON-DATA] §2.9 rules the choice a serialisation concern, not a document property. Encoders SHOULD omit; decoded output never records which spelling arrived. At a REQUIRED-family field a null member is a validation error, precisely as `_` is in text ([TSON-SCHEMA] §7.6) — at REQUIRED_DEFAULT the fix is omission, which injects the default. The one field state where the null spelling is the *point* is `OPTIONAL_FIXED … = _` (value forbidden, presence meaningful — the group-member state, [TSON-SCHEMA] §5.2): presence is the information, so when present the field appears as a member with value null, and omission remains its absent form.

**6.1.3 Defaults and fixed values.** Unchanged from [TSON-SCHEMA] §5.2, restated against members: a missing member at a REQUIRED_DEFAULT or REQUIRED_FIXED field injects on decode, so decoded output is fully populated; a present member at a FIXED field MUST be verified against the pin — **against the decoded value and not the spelling**, since §5.3 makes `1` and `1.0` one number, so comparing the JSON literal to the schema's token would refuse a conforming document — a contradiction being a validation error, never a silent overwrite. An injected value's JSON form is the form §5 gives its type, taken from the **value** the schema states and not from the token that states it: §4.3 makes radix and digit separators text-encoding spellings that do not survive, so a field declared `= 0xFF` injects `255` and one declared `~ 1_000` injects `1000`, while a string-content family injects its token, the token being the spelling there (§5.6). Encoders SHOULD write defaulted fields' values — a JSON document read by schema-less JSON consumers is exactly the document that must state its defaults — and MAY omit a member whose value equals the field's default as a size optimisation, lossless because decode injects. A discriminator field is the exception: it is never omitted, in either encoding (§6.1.5).

**6.1.4 Field groups.** No wire form: grouping is invisible in the instance ([TSON-SCHEMA] §5.11), so members of a group encode and decode as ordinary fields, and the validator counts present members per group after field validation — REQUIRED groups admit exactly one, OPTIONAL at most one, identically to text.

**6.1.5 Subsumption.** At a position whose declared type is `T`, an object MAY carry `$type` naming `S` where `S` IS-A `T` under [TSON-SCHEMA] §7.2 — the inline annotation form (§3.3) — and the value then validates as `S` in full. This is the JSON spelling of `!employee` at a `person` field. **What an untagged object at such a position means is decided by `T`'s own extension fact** ([TSON-SCHEMA] §5.2) and not by this encoding, so the three readings below are one Part 2 rule spelled in members:

- **OPEN or FINAL** — the value is exactly `T`. There is no structural recovery of `S`: an object whose members happen to match a subtype is a `T`, and a subtype's own members are §6.1.1 closure violations. The tag is the only selector, and is required exactly when text's annotation would be informative.
- **ABSTRACT** — `T` has no direct instances, so there is no value for an untagged object to be, and `$type` is **REQUIRED**. A missing tag is a validation error naming `T` and SHOULD name its subtypes. Nothing about the object's shape is consulted: an abstract position with no tag fails before its members are read, which is the same refusal text gives a missing `!variant`.
- **SEALED** — `T` is abstract and declares one or more discriminator fields ([TSON-SCHEMA] §8.1). The decoder reads those members, takes their values **at the fields' declared types in `T`** — the one set of types known before dispatch, `T` declaring them and every subtype inheriting them — and matches the value, or the tuple of values in declaration order where there is more than one, against the mapping derived from the subtypes' pins. The matched subtype becomes the effective type and the whole object validates against it, which re-verifies each pin as an ordinary FIXED-field check ([TSON-SCHEMA] §5.2) and makes the dispatch read and the validation read agree by construction. A **missing** discriminator member is a validation error (*discriminator missing*), never an occasion to fall back to the tag; an **unmatched** value or tuple is a validation error naming what was received and SHOULD name the pinned alternatives.

**The wrapper form places the value by its tag, at an abstract position and a sealed one alike.** §3.3's wrapper is apparatus rather than a record — the value is the `$value` member — so a sealed position's discriminator members are inside it and not at the level being read, and there is nothing there to dispatch on. `$type` is therefore REQUIRED in the wrapper form wherever it is required in the inline one, and at a sealed position it becomes required rather than asserting: it MUST name a member of the family, and the value read at that member re-verifies the pin as an ordinary FIXED check exactly as the inline reading does. This is the ordinary wrapper rule and not a second dispatch — a family still has one selector per position, and which one it is follows from where the members are.

**A sealed position's tag can only assert.** `$type` MAY be present — the object is then the inline annotation form, and reserved-member recognition ran first (§3.3) — and the named type MUST be the dispatched subtype or a subtype of it, a mismatch being a validation error and never a precedence question. There is one selector per position and the FIXED check is what forces the two to agree wherever both appear. **The text encoding reads a sealed position the same way**: `!variant` is not required there and MUST agree where written, so the discriminator is a field the reference encoding reads rather than one it declares and ignores, and the two encodings differ in spelling alone.

**Encoders.** At a sealed position every discriminator member MUST be written: §6.1.3's elision latitude does not reach them and neither does the REQUIRED_FIXED injection route, since selection here happens by reading the member and injection would put the selector behind the selection. At an abstract position the tag MUST be written. Everywhere else §6.1's ordinary rules apply.

**Deeper than one level.** A record whose `$type` names a proper subtype of a dispatched subtype validates as that type; its discriminator members carry the pins inherited, refinement being unable to change a FIXED value ([TSON-SCHEMA] §5.7). So a sealed family dispatches one level by member and every level below it by tag — which is not a limitation this encoding imposes but the pins' own arithmetic, and an intermediate type meaning to dispatch its own subtypes declares a discriminator of its own.

**6.1.6 Member order.** Encoders SHOULD emit declared fields in declaration order (rest members after, §6.2); decoders MUST NOT ascribe meaning to member order. Order is presentation, and a decoder that requires an order has invented a rule this document does not contain.


### 6.2 The Rest Field

A record one of whose declared fields carries the `@rest` marker ([TSON-SCHEMA] §6) — the **rest field**, an ordinary field whose declared type resolves to `{text => X}` — is **flattened** in this encoding, and in this encoding alone: the model's record stays closed, and the other encodings write the map nested.

**Encode.** The rest map's entries are emitted as members of the record's own object, after the declared fields: key → member name, value → the value at `X`. Three refusals guard the flatten, each an encode error: a rest key equal (NFC) to a declared field name of the composed record — the flattened form could not be read back; a rest key beginning with `$` — the reserved namespace (§3.2) is not spellable from data; and a rest key that is not a well-formed member name candidate under §3.1's profile (an unpaired-surrogate-bearing string, which no conforming value contains anyway). The text encoding has no flatten: there the rest field is written as the honestly nested map it is, and these refusals have no counterpart — which is the intended asymmetry, not an inconsistency.

**Decode.** Members are bound in this order, each name consulting the next rule only if the previous declined it: reserved names (`$`-initial) go to §3.2's rules; names matching declared fields (NFC) bind those fields — **declared names win**; every remaining member is collected into the rest field as an entry, name parsed by the rest map's key type (text — total, so collection cannot fail on the key) and value decoded at `X`. **Collection precedes name hygiene** (§9.4), and the order is load-bearing rather than incidental: it is what lets a converted schema's rest tail carry ordinary foreign JSON, whose member names were never declared anywhere, without meeting an identifier rule at all. Hygiene before collection would refuse ordinary JSON under a rule that exists only where names are declared. A record type with no rest field skips the collect rule and lands in §6.1.1's closure error. `$`-initial members are never collected, on either side of the wire — §3.2's namespace is not spellable from data, so foreign material whose keys use `$` belongs in a map-typed position, where nothing is reserved. The rest field itself, as a declared member name on the wire, is also bound by the declared-names rule — an incoming document may state the map explicitly *or* flatly, but a document that does both, or whose explicit map and flat members would both populate the field, is a validation error at the second contributor: one field, one source.

Decoded output populates the rest field with the collected map — possibly empty, which is the decode of a document with no unmatched members; the rest field is thereby always present in decoded output, and its OPTIONAL/REQUIRED state in the declaration is advisory for this encoding's decode direction.

**Dispatch.** A rest-field record absorbs arbitrary members, so its *members* can prove nothing about its type; nothing in §8 ever dispatches on member shape, so no additional rule is needed — but the fact is recorded here because it is why member-structural dispatch is absent from §8 by design and not by omission.


### 6.3 Arrays and Sets

An array type `[T]` encodes as a JSON array, elements at `T`, in order; size facets validate the slot count. An element-optional array `[T?]` admits JSON null at any slot as the **absent element** — the slot exists and counts ([TSON-DATA] §2.9). Under `[T]` (element REQUIRED), null at a slot is a validation error, as `_` is in text ([TSON-SCHEMA] §7.6); it is never a value (§7).

A set-typed position uses the same JSON array form. Duplicates under the element type's equality contract are validation errors at the repeated occurrence ([TSON-SCHEMA] §7.5); element order on the wire is meaningless, encoders SHOULD emit a stable order for diff-friendliness, and comparisons are as sets.


### 6.4 Tuples

A tuple encodes as a JSON array of exactly its declared length — short or long arrays are validation errors regardless of trailing-optional positions, matching [TSON-SCHEMA] §5.3. Each slot decodes at its position's element type; an OPTIONAL position's absent value is JSON null in its slot (the `_` of the text form); at a REQUIRED position null is a validation error (§7).


### 6.5 Maps

A map type `{K => V}` has two JSON forms, selected by the schema — by `K`, never by inspecting the value:

**Object form** — when `K` resolves, after following its reference chain, to a type whose **parsing contract reads token content**: every atom family and every enum, which are the types a single scalar token denotes directly, the same line [TSON-SCHEMA] §5.2 draws for value modifiers. The test is the contract and not the position in the type hierarchy, because two of the kernel's `unit` instances sit in the atom family and have no such contract: `value` is read by *kind* rather than by content (§5.7), and a member name is only ever a string, so object form would flatten its four kinds to one and make `1` and `"1"` one key — which the decoded-key identity below forbids; and `void`'s sole value is absence, which [TSON-DATA] §2.9 excludes from a key outright. Both therefore take the pairs form, where a key is an ordinary value position and its kind survives. `identifier`, the third `unit` instance, has a content grammar and takes object form like any other family. The map is a JSON object; each member name is a **key token**: its string content is handed to `K`'s parsing contract exactly as §5.1 hands value strings — `{"2026-07-01": 12.5}` under `{date => number}` carries a date key; `{"42": "a"}` under `{integer => text}` carries the key 42. A member name `K`'s contract rejects is a resolver error. Encoders MUST emit each key's canonical content — for the numeric families, the plain decimal spelling — so that textual and decoded key identity coincide on the wire; decoders MUST apply decoded-key identity regardless — identity under a declared key type is over its value space ([TSON-SCHEMA] §5.5): `"42"` and `"042"`… the second fails the integer grammar, but `"1"` and `"1.0"` under a `number` key are one key, two spellings of one octet string under a `bytes` key are one key, and the duplicate is the [TSON-SCHEMA] §7.7 error. Member names at map positions are never reserved (§3.2): `"$schema"` under `{text => text}` is a key, with the single carve-out of §8.3.1.

**Pairs form** — when `K` is anything else (a record, tuple, array, map, or choice — the compound keys [TSON-DATA] §2.6 admits): the map is a JSON array of two-element arrays, `[[k₁, v₁], [k₂, v₂], …]`, each `kᵢ` encoded at `K` and each `vᵢ` at `V`. A pairs-form element that is not a two-element array is a validation error. Duplicate keys under `K`'s equality contract are errors as above. The pairs form is the second class leak of §4.2 — a brace-class type wearing bracket clothing — and §8.3 charges it accordingly.

An empty JSON object at an object-form map position, and an empty array at a pairs-form one, is a map of **no entries**, and faces the size facets as any other count does. [TSON-DATA] §2.6's "at least one entry" and the empty-brace reading of §2.8 are text-grammar rules with no counterpart here: JSON's `{}` and `[]` are unambiguous, the position having already fixed which of them a map is spelled as.

In both forms: an entry-value-optional map `{K => V?}` admits JSON null as an entry's **absent value** — the entry is present, counts toward size bounds, and carries no value; under `{K => V}` a null entry value is a validation error, as with array elements (§7). Size facets count entries. There is no per-key presence facet: a schema that must name required keys declares them as record fields beside an `@rest` tail (§6.2) — named keys are structure, and maps stay homogeneous. There is no inline form of the annotation object at a map position and no `$`-reservation; a map that must carry a tag rides in a wrapper's `$value` (§3.3).


## 7. Absence and Null

JSON has one gap-shaped token, and so does the model ([TSON-DATA] §2.9): the absent sentinel. **JSON null is this encoding's spelling of `_`.** There is no null value for it to also mean, so this section is one rule and its corollaries rather than a precedence scheme.

**The rule.** JSON null is admitted exactly where [TSON-SCHEMA] §7.6 admits the absent sentinel under a schema, with the same meanings: at an OPTIONAL element slot (`[T?]`) — the slot exists and counts; at an OPTIONAL tuple position; at an OPTIONAL entry value (`{K => V?}`) — the entry is present with an absent value; at a record field whose state admits absence (OPTIONAL, or `OPTIONAL_FIXED = _`); and at a `void`-typed position, whose contract admits nothing else. At every other position — every REQUIRED-family field, every REQUIRED element, entry value, or tuple position, every atom slot — JSON null is a **validation error**, precisely as `_` is in text. One table, [TSON-SCHEMA] §7.6's, answers both encodings; absence is a model concept, and each encoding merely spells it.

**7.1 Fields have two spellings.** A record field, unlike a slot, can also be omitted, so an absent optional field has two equivalent forms — member omitted, member null — per §6.1.2, on [TSON-DATA] §2.9's serialisation-concern license. Encoders SHOULD omit.

**7.2 Round-tripping absence.** `_` in text and null-or-omission in JSON decode to the same absence; decoded output never records the spelling, in either encoding (§9.1).

**7.3 What became of JSON's null value.** A JSON document's nulls, read through this encoding, are absences, and each position's declared state decides whether an absence is admitted there — the JSON-reader posture [TSON-DATA] §6 states and this document carries out. A contract that needs a *distinguished*, in-band no-value marker — "explicitly cleared", "checked and empty" — models it as data, because it is data: an enum member, or a labelled group alternative ([TSON-SCHEMA] §5.11), each of which survives every encoding and reads back as the fact it is. For converted JSON Schema, `type: "null"` alone maps to `void`, and a nullable union `[X, "null"]` maps to `X` at an absence-admitting position; the residual corner — a member that must be *present* yet null — is not a TSON concept, precisely because present-with-absent-value versus omitted is a spelling and not a fact, and a converter meeting it reports rather than invents.


## 8. Sums and Discrimination

This section is the encoding's discrimination predicate — the rule [TSON-SCHEMA] §5.4 requires each encoding to state over the resolver-derived `disjoint` fact — together with the wire forms of the tag. It governs choice-typed positions; scoped positions, the open sum, follow in §8.5.


### 8.1 The Tag and Where It Lives

In TSON text the tag is the out-of-band `!variant` annotation. In JSON it is in-band, and at a choice position it has exactly one carrier: **the annotation object** (§3.3), `$type` naming the variant — inline for record variants, wrapper form for everything else. It is available at every choice position and required where §8.2 says so, and a tag is never wrong: a decoder MUST accept a `$type`-tagged value at any choice position, including positions where the tag could have been omitted. **Member dispatch is not a choice mechanism** — it belongs to a sealed record family (§6.1.5), where the position has an expected record type whose discriminator fields the decoder knows before it reads anything. A choice position has no such type, so the tag is the whole of its in-band selection, and the converted OpenAPI contract that wires as plain JSON does so as a family and not as a choice (§6.1.5).


### 8.2 The Discrimination Predicate

A value at a choice-typed position MAY omit the tag **if and only if** one condition recovers the variant from the schema and the JSON form alone:

- **The choice is `disjoint: true` and every variant is class-stable (§8.3).** Selection is by the JSON value kind: the arriving value's kind names a discrimination class (§4.2), and the variant bearing that class is selected. The `disjoint` fact guarantees at most one variant per class ([TSON-SCHEMA] §5.4); class stability guarantees the value's kind actually lands in its variant's class. Enum-classed variants dispatch by kind like any other — content matching against the member set is that variant's ordinary validation, not a second dispatch.

Where it does not hold, the tag is REQUIRED, and a value without one is a validation error at the position. The predicate consumes the derived `disjoint` fact and the schema's declared facets; it MUST NOT be extended by implementation cleverness — no member-shape matching among record variants, no value-set separation (disjoint bounds, disjoint patterns), no trying variants in order. [TSON-SCHEMA] §5.4's closed derivation has an exact counterpart here: a decoder proves neither more nor less than this one condition.

**A choice of records is therefore always tagged**, every record sharing the brace class. That is not a gap in the predicate but a signpost: alternatives an author expects to dispatch on a member are a sealed family (§6.1.5), and alternatives told apart by label are a single-group record ([TSON-SCHEMA] §5.11, whose labelled sum reads here as an ordinary record and reaches this predicate not at all). A choice is the construct for alternatives told apart by *type*, and its selector is the tag.

Decode order at a choice position, restated as the decoder walks it: an object carrying reserved members is the tagged form — read `$type`, dispatch, validate as the named type; otherwise, if the condition above holds, dispatch on the value kind; otherwise the tag is missing, a validation error. Each step is a single test, and neither tries a variant: no step reads a value at a candidate type to see whether it fits, which is the thing this predicate exists to forbid. The first step reads member names and so carries §4.1's lookahead consequence.

For an encoder the rule is one sentence, the counterpart of text's: *if the choice is disjoint with class-stable variants, a tag is optional; otherwise tag every value.*


### 8.3 Class Stability

A type is **class-stable** when every JSON encoding of every one of its values is of its own discrimination class's value kind — when the §4.2 mapping does not leak for it. Instability comes from exactly the two leaks §4.2 bolded, and the unstable set is closed:

1. **Approximate atoms with special values enabled.** A `float_type` instance with `allow_nan` or `allow_infinity` still true has values (`.nan`, the infinities) that encode as JSON strings (§5.4) — number-class values in string clothing. Narrowing both facets to `false` restores stability.
2. **Maps in pairs form.** A map whose key type forces the pairs form (§6.5) encodes as a JSON array — a brace-class value in bracket clothing.

Every other type is class-stable: booleans, the exact numeric families, every string-content family (§5.6), enums (each member's form is its own class's form, §5.2), records — rest-field records included, since absorption changes members, never the object kind — object-form maps, arrays, sets, and tuples. Types with no discrimination class (`rational`, `complex`, the unit instances `value` and `identifier`, nested choices, the `scoped` instances) need no stability verdict: a choice containing one is not disjoint ([TSON-SCHEMA] §5.4), so the untagged route never consults them.

Class stability is derivable from the schema at load time, and an implementation SHOULD compute the predicate's verdict per choice at schema load, in the manner of [TSON-SCHEMA] §5.11's compiled group lookup — the wire decision is then a table hit, not a per-value derivation.


#### 8.3.1 The Map Escape Rule

The untagged route can put a bare map on the wire (a disjoint choice whose brace-class variant is an object-form map), and a map's keys are data that may legitimately spell a reserved name (§3.2). One rule closes the ambiguity, stated from both sides:

- **Decoder:** at a choice-typed or scoped position, an object carrying any member of the closed reserved set (§3.2) is read as an annotation object — always, before any other reading.
- **Encoder:** a map value emitted at such a position MUST use the tagged wrapper form if any of its keys' encoded member names is a reserved name; bare emission is permitted otherwise.

The wrapper always exists (`{ "$type": "…", "$value": { … } }` — the map's keys ride inside `$value`, where nothing is reserved), so the rule costs a wrapper on a vanishing case and buys determinism on every case. Keys beginning with `$` that are *not* in the closed set trigger nothing: recognition tests the closed set only.


### 8.4 Why a Choice Has No Discriminator

A choice carries no member-dispatch mechanism, and the reason is structural rather than a restraint this document chose. Member dispatch needs an expected record type whose selector fields are known before the value is read; a choice-typed position has no such type, its expected type being a set of alternatives. Recovering one would mean computing the variants' common supertype, which [TSON-SCHEMA] does not derive and this document may not invent. So the discriminator is a property of a record family and lives with the records (§6.1.5), and §8.2's predicate is one condition rather than two.

What that leaves an author who wants the plain untagged object with a `"kind"` member — the converted OpenAPI shape — is the sealed family, which gives exactly it: `pet => @sealed { @discriminator pet_type: text … }` with each subtype pinning `pet_type`, and every position typed `pet` reading the member. **The choice is not the construct for that shape, and the difference is not cosmetic:** a family names an open set, extensible by an importing schema under the pinning obligation, where a choice names a closed set that only its own declaration may change. An author choosing the closed spelling has said something, and the tag is what a named set is selected by.

A choice whose variants happen to be members of one sealed family is still a choice: its position is a choice position, its variants are records, and §8.2 requires the tag. Typing the position by the family instead is the edit that buys member dispatch, and it also removes a declaration.

### 8.5 Scoped Positions and Schema Scope Changes

A scoped position ([TSON-SCHEMA] §7.8) — one whose effective type is a `scoped` instance: core's `declared` (`[LOCAL]`), `extern` (`[EXTERN]`), `dynamic` (`[LOCAL EXTERN]`), or an application of `extern_of<S>` / `extern_type<S, T>` — is the open sum: the instance names the namespaces a value's type may be drawn from, and *the value names its own type*. In text a value there carries `!type`, and a foreign one a scoped `!!schema` before it; in JSON both ride the annotation object (§3.3), and the cell is read off the members present:

- an annotation object carrying **`$schema`** is the **EXTERN** cell: `$schema`'s canonical identity MUST be a key of the instance's `schemas` map, or any schema if `schemas` is absent; `$type` MUST be present, resolves in that schema's namespace, and MUST be in the key's list where one is given. The scope opens for the annotated value alone, and the value validates in full against the foreign type.
- an annotation object carrying **`$type` alone** is the **LOCAL** cell: `$type` resolves in the governing namespace, as at any other position.
- a JSON value that is **not an annotation object** — a bare scalar, array, or object with no reserved member — is a **validation error** in every mode: the position promises no type for an untagged value to be read as, and there is no permissive reading of it.

A cell the instance's `scope` does not hold refuses the value it would have taken, as a validation error — a `$schema` at a `declared` position, a bare `$type` at an `extern` one. The annotation object is inline when the named type reads the value as a record and wrapper otherwise (§3.3). A missing `$type` on the EXTERN cell is the same validation error text gives it; a missing `$schema` would leave the scope change invisible, which the model forbids — schema scope changes are always visible in the data, in every encoding — so the foreign value MUST carry both.

**The typed-position restriction, derived.** `$schema` is admitted at a position exactly when the position's effective type is a `scoped` instance whose `scope` holds `EXTERN`, or a container of one — [TSON-SCHEMA] §7.8's rule, which is a fact of the type and not a list of permissive names; anywhere else it is a resolver error. A `value` position admits none (§5.7). A heterogeneous `[extern]` array is an array of annotation objects, each opening its own scope:

```json
"attachments": [
  { "$schema": "https://tson.io/2026/insurance/claim.tn?sha256=f8b2…",
    "$type": "insurance_claim",
    "claim_id": "CLM-5678", "amount": 450.00, "provider": "City Medical" },
  { "$schema": "https://tson.io/2026/radiology/report.tn?sha256=d4e9…",
    "$type": "radiology_report",
    "study_id": "RAD-9012", "modality": "MRI", "findings": "Normal" }
]
```

Where the schema means exactly those two, the field is declared over a narrowed instance listing both — `claim_or_report => !scoped { scope: [EXTERN]  schemas: { "https://…/claim.tn" => _  "https://…/report.tn" => _ } }` — and the same JSON is the only JSON it admits. Hash-pinned `$schema` references verify per [TSON-DATA] §2.2.1 and [TSON-SCHEMA] §10.2, unchanged; a pinned `$schema` and an unpinned key match by canonical identity, each pin verified on its own.


## 9. Processor Contracts


### 9.1 Decoding

The decode pipeline, assembled from the rules above: parse the JSON text under §3.1's profile; establish the binding (§3.4); walk value and schema together (§4.1), at each position applying the reserved-member recognition of §3.3, then the position's family rule (§5–§8). Decoded output is populated on the same terms as the text encoding's: defaults and fixed values injected (§6.1.3), rest members collected (§6.2), absences normalized (a decoded value never records whether an absence was spelled by omission or by null), group presence counted (§6.1.4), set and key uniqueness enforced (§6.3, §6.5). The Class 3 equivalence rule (§1.5) is the acceptance test: for any value both encodings carry, the two decodes are indistinguishable to a consumer of decoded output.

A decoder MUST be built on JSON parsing it can hold to §3.1 — in particular, a stock parser that silently keeps one of two duplicate members, rounds long numbers through binary64, or repairs ill-formed strings cannot underlie a conforming implementation, however convenient (§10.2).


### 9.2 Encoding

Encoding is decode's inverse over the carryable subset (§4.3), with the latitude and obligations already stated, gathered here:

- **MUST**: refuse the uncarryable (§4.3) with categorized errors; write discriminator members always and tag at an abstract position (§6.1.5); tag wherever §8.2 requires; use wrapper form for non-record annotated values and for the map escape (§8.3.1); emit canonical key content in object-form maps (§6.5); preserve exact-tier digits and scale (§5.3); emit UTF-8 without a byte order mark (§3.1).
- **SHOULD**: inline annotation objects for record-shaped values, reserved members first (§3.3); write defaulted fields' values (§6.1.3); emit declared fields in declaration order (§6.1.6); omit tags the predicate makes optional (principle 2 — the plain document is the better document); emit set elements in a stable order (§6.3).
- **MAY**: omit members equal to their field's default (§6.1.3); write redundant tags (§8.1).

An encoder's output MUST be accepted by this document's decode rules against the same schema, and MUST decode to the value encoded — the round-trip is the conformance test, not a separate rule set.


### 9.3 Round-Trip Guarantees

**JSON → model → JSON** (one schema, one implementation) is stable up to the stated latitude: insignificant whitespace, member order, presence of optional tags, defaulted-member elision, string escape choices, and set element order. Digits, scale, and all value content are fixed points.

**Text → model → JSON → model → text** preserves values exactly over the carryable subset: every atom's information content ([TSON-DATA] §5.2), every structural relationship, every absence. Lost by design: annotations (refused, not dropped — §4.3), spelling (radix, separators, quote and multi-line forms, layout), and redundant type annotations. A document that must round-trip its annotations or its spelling lives in the text encoding; that sentence is the whole trade.


### 9.4 Errors

The four categories of [TSON-DATA] §8.1 apply; nothing here adds a category or a severity. The mapping:

| Rule violated | Category |
|---|---|
| Malformed JSON text; invalid UTF-8; ill-formed strings (§3.1) | lexer error |
| JSON grammar violations (unclosed structures, bad tokens) | parse error |
| Duplicate member names at record positions; duplicate map keys (textual/decoded identity); unknown reserved members (§3.2); wrapper-form objects with extra members (§3.3); unresolvable `$schema`/`$type`; binding disagreement, in-band or header (§3.4, §3.5); map member names rejected by the key contract (§6.5); string content rejected by an atom's parsing contract (§5.1) | resolver error |
| Wrong-kind values at typed positions; JSON null where absence is not admitted (§7); constraint violations after parsing; closure violations (§6.1.1); FIXED-field contradictions; missing or unmatched discriminators (§6.1.5); tag/assertion mismatches; missing required tags at an abstract position (§6.1.5) or a choice (§8.2); duplicate keys only a declared type relates; group presence violations; set duplicates; size and length facet violations | validation error |

Positions in error reports are JSON source positions (line, column, byte offset), per [TSON-DATA] §8.1's reporting requirements; a decoder SHOULD additionally report the schema position (type and field) whose rule fired. The name-hygiene layer ([TSON-DATA] §8.2) applies to this encoding on its existing terms, and its reach is narrower than "every name" because most names inherit a verdict already given. The **identifier policy** reaches every `$type`, and every member name read as a field name that matches no declared field of the position's type and is not collected by a rest field (§6.2) — a member name matching a declared field carries that declaration's verdict, judged when the schema loaded, and a collected member is a map key, under the token policy. The **token policy**, when a deployment sets one, reaches map keys and string values.

**A look-alike member name MUST be refused under §8.2 and MUST NOT be reported as a closure violation.** The distinction is not presentational. Take a record declaring `password` and a document sending `pаssword` with U+0430 CYRILLIC SMALL LETTER A: the name matches no declared field, so the closure rule (§6.1.1) would make it a validation error — one of [TSON-DATA] §8.1's four categories, which §8.2 forbids outright for these rules, because they read data the UCD does not freeze and so may not decide validity. A decoder that reaches for §6.1.1 before §8.2 refuses a document under a verdict category for a policy rule, in exactly the case the look-alike rule exists for. So the order at a record position is: declared fields, then rest collection (§6.2), then hygiene over what is left — and only the last reaches the identifier policy.

A JSON document read with **no** schema binding is outside this document (§3.4) and applies neither policy: its member names are data rather than names, and there is no declaration for them to be look-alikes of. A refusal under either — or under the limits policy of §10.1 — is [TSON-DATA] §8.1's fifth outcome: reported in the same report as the four categories, distinguishable from them, never a verdict on the document, with the report carrying the UCD version and the policies it was judged under.


## 10. Security Considerations

The considerations of [TSON-DATA] §9 and [TSON-SCHEMA] §11 apply. This encoding adds the following.


### 10.1 Resource Bounds

JSON nesting depth, member and element counts, string lengths, number lengths, and decoded binary sizes are the limits of [TSON-DATA] §9.1's **limits policy** in JSON clothing, and the same policy applies with the same defaults: every limit configurable or documented, its threshold named on refusal, a refusal that is a fifth outcome and never a validity error. The number-length limit applies to JSON numbers exactly as to text numeric literals; the decoded-binary limit counts a `bytes` value's octets; the foreign-schemas limit counts distinct `$schema` bindings a document opens (§8.5). Schema-directed decode adds one amplifier: default injection (§6.1.3) means a small document against a default-heavy schema decodes to a larger value; implementations bounding decoded-output size SHOULD count injected content.


### 10.2 Parser Substrate

Most deployed JSON parsers are RFC 8259-lenient in ways §3.1 forbids: last-duplicate-wins member handling, binary64 number funnels, replacement-character repair of ill-formed input. A Class 3 processor built over such a parser inherits silent divergence from this specification at exactly the points attackers probe — duplicate-member smuggling (two `$type` members, two discriminator members, one seen by a security filter and the other by the decoder) is the canonical case, and the §3.1 duplicate rule exists to close it. Implementations MUST verify their substrate's behavior on these points rather than assume it; a conforming processor cannot be assembled from an unconforming parser plus intentions.


### 10.3 Numeric Interoperability

This encoding transmits exact numbers at full precision (§5.3). Consumers that funnel JSON numbers through IEEE 754 binary64 — stock JavaScript, and any I-JSON-constrained (RFC 7493) pipeline — will corrupt integers beyond 2⁵³ and long decimal fractions *before any TSON rule runs*. Deployments exposing such consumers SHOULD constrain the relevant schema positions to types the consumers can hold (`int32`, `float64`, bounded refinements) — stating the limitation in the contract, where it is visible and validated — rather than relying on wire-level workarounds this encoding deliberately omits (§5.3). The schema is the right place for a precision commitment; a stringified integer is a precision commitment hidden in a type change.


### 10.4 In-Band Bindings

A root or scoped `$schema` is the directive control channel of [TSON-DATA] §9.3 in member form, and inherits its posture: references resolve through the schema library, fetching is opt-in and off by default, and applications processing untrusted input SHOULD restrict which bindings are honoured — for in-band roots, the out-of-band contract of §3.4 route 1 is the restriction, since a supplied binding must agree with any in-band one. `$type` admits only types the position admits (§3.3): subsumption bounds it at fields, variant membership at choices, the instance's `scope` and `schemas` at scoped positions — an attacker naming a type cannot widen what a position accepts, only select within what the schema already offered. The reserved namespace itself is not spoofable from schema content (§3.2, by construction) and not extensible from documents (§3.2, closed set).


## 11. References


### 11.1 Normative References

| Reference | Title | URL |
|-----------|-------|-----|
| RFC 2119 | Key words for use in RFCs to Indicate Requirement Levels | https://www.rfc-editor.org/rfc/rfc2119 |
| RFC 8259 | The JavaScript Object Notation (JSON) Data Interchange Format | https://www.rfc-editor.org/rfc/rfc8259 |
| RFC 6839 | Additional Media Type Structured Syntax Suffixes | https://www.rfc-editor.org/rfc/rfc6839 |
| RFC 9110 | HTTP Semantics | https://www.rfc-editor.org/rfc/rfc9110 |
| RFC 9651 | Structured Field Values for HTTP | https://www.rfc-editor.org/rfc/rfc9651 |
| RFC 3986 | Uniform Resource Identifier (URI): Generic Syntax | https://www.rfc-editor.org/rfc/rfc3986 |
| UAX #15 | Unicode Normalization Forms (NFC) | https://www.unicode.org/reports/tr15/ |
| RFC 4648 | The Base16, Base32, and Base64 Data Encodings (the alphabets `bytes` selects, §5.6) | https://www.rfc-editor.org/rfc/rfc4648 |
| RFC 3339 | Date and Time on the Internet: Timestamps (Appendix A for `duration`/`period`, §5.6) | https://www.rfc-editor.org/rfc/rfc3339 |

The atom parsing contracts this document reaches through §5.1 carry their own normative references in [TSON-DATA] §10.1 and [TSON-SCHEMA] §13.1; they are not repeated here.

### 11.2 Series References

| Reference | Title | URL |
|-----------|-------|-----|
| TSON-DATA | TSON Part 1: Text Data Format | https://tson.io/2026/36/tson-part1-data |
| TSON-SCHEMA | TSON Part 2: Type System and Schema | https://tson.io/2026/36/tson-part2-schema |
| TSON-CBOR | TSON Part 4: CBOR Encoding | https://tson.io/2026/36/tson-part4-cbor |
| TSON-GUIDE | TSON Developer Guide (non-normative) | https://tson.io/2026/36/tson-guide |

### 11.3 Informative References

| Reference | Title | URL |
|-----------|-------|-----|
| RFC 7493 | The I-JSON Message Format | https://www.rfc-editor.org/rfc/rfc7493 |
| RFC 6648 | Deprecating the "X-" Prefix in Application Protocols | https://www.rfc-editor.org/rfc/rfc6648 |
| JSON Schema 2020-12 | JSON Schema: A Media Type for Describing JSON Documents | https://json-schema.org/specification |
| OpenAPI 3.1 | OpenAPI Specification | https://spec.openapis.org/oas/v3.1.1 |


## Authors

- David Ryan
