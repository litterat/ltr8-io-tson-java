---
title: "TSON Part 1: Text Data Format"
draft: "2026"
status: "Working Draft"
part: 1
description: >
  The notation and reference encoding of the TSON schema system: the lexer, structural
  grammar, absent sentinel, augmentation syntax (annotations, type annotations, directives),
  and base type resolution — everything needed to losslessly read and write schemaless
  TSON data.
---

# TSON Part 1: Text Data Format

## 2026 Revision 35

**Status:** Working revision. The 2026 revision series is subject to change without compatibility guarantees. When finalised, this specification will be published as **TSON version 1** and frozen (§1.2, principle 6); until then, revisions are released under the 2026 series. This revision removes the claim that TSON is a JSON superset, and with it the rules that existed only for that claim: `null` is no longer a base value (§4), the `\/` escape and the surrogate-pair rules are gone and a braced `\u{…}` escape is added (§7.2.2), a field name is an identifier at every layer (§2.5, §7.7), and a comma may follow a value (§2.4). It restates base type resolution as applying only in schemaless documents (§4.1), replaces the four spelled binary annotations with one `!bytes` (§5.3), splits the temporal `!duration` into `!duration` and `!period` (§5.4), makes the name-hygiene policy and the resource limits reportable properties of a processor rather than of a refusal (§8.1, §8.2, §9.1), and says why the content-hash pin rides in the URI query (§2.2.1). This is the first revision to change the lexer since the Class 1 freeze was declared (§1.3); it does so under principle 6, and the freeze holds from this revision.

**Series:** TSON Specification, Part 1 of 2

**Copyright:** © 2026 Litterat Pty Ltd. This document is licensed under the Creative Commons Attribution-ShareAlike 4.0 International License (CC BY-SA 4.0): https://creativecommons.org/licenses/by-sa/4.0/


## 1. Introduction and Design Principles


### 1.1 Purpose and Scope


TSON is a schema system with its own notation. At its centre is a type system ([TSON-SCHEMA]): immutable, hash-pinned schemas whose definitions are themselves data, resolving down a verified chain — document → schema → meta-schema → kernel — so that one hash authenticates a document together with its entire contract. The TSON text format is that system's notation and its reference encoding. Schemas are the point; the text format is how they are written down, and the first of the encodings that carry them.

TSON (Typed Schema Object Notation) is a Unicode text-based data interchange format in the JSON family: braces, brackets, quoted strings and `name: value` fields, extended with richer structural types; optional annotations, type annotations, and directives; and a layered resolution model that separates structural parsing from semantic interpretation. It is JSON-*like* by design and not a JSON superset: a JSON document is read through a JSON reader, which is one encoding of the same model (§6).

This document defines the TSON **text data format**: the lexer, the structural grammar, the absent sentinel, augmentation syntax, base type resolution, and the built-in type vocabulary. It stands alone: a processor implementing this document — and nothing else — can losslessly read and write any schemaless TSON data document, typed or untyped. [TSON-SCHEMA] defines the type system this notation carries; nothing in this document depends on it.


### 1.2 Design Principles


1. **Structural simplicity** — The data grammar handles structure only. Value interpretation is deferred to base type resolution (§4) and, in higher parts, to the type system.

2. **Layered extensibility** — TSON operates in layers: lexer, structural parser, resolver, base type resolver, type vocabulary, and optionally the schema layer. Each layer adds capability without requiring the layers above.

3. **Unicode foundation** — Character classification, identifier rules, and normalization are defined in terms of Unicode character properties (UAX #31, UAX #15). Field names and values work in all scripts without quoting. All structural operators use ASCII characters.

4. **Minimal required syntax** — Whitespace separates values; a comma may follow a value (§2.4). Double quotes are needed only where content demands them (§7.1).

5. **Locality** — A TSON data value is fully local. The format provides no data-level reuse mechanisms (no anchors, references, or merge operators): what appears at a position is the complete value.

6. **Permanent stability** — TSON version 1 is a permanent specification. The grammar and resolution rules are frozen at the version 1 release. There is no TSON 1.1 or TSON 2. New types are added through the type system, not through changes to this document. Errata may clarify ambiguities but MUST NOT change the grammar or the behaviour of conforming implementations. The permanence guarantee attaches to the version 1 release: 2026-series revisions of this document, including this one, may change anything.


### 1.3 The TSON Specification Series


The TSON specification is published in two parts:

- **Part 1: Text Data Format** (this document) — the notation and reference encoding: the lexer, the data grammar, base type resolution, and the built-in type vocabulary.
- **Part 2: Type System and Schema** [TSON-SCHEMA] — the centre of the series: the schema grammar, the type system, the schema chain, and the operations of the `schema`, `meta`, and `import` directives.

The architecture of the series places the type system at the centre, with the text format as its notation and reference encoding:

```
data document ──!!schema──▶ user schema ──!!meta──▶ meta-schema ──!!meta──▶ meta-kernel
                                                                           (pre-loaded)

  the chain is the type system — every rung an immutable, hash-pinnable
  TSON document, so one hash verifies a document together with its
  entire contract chain (§2.2.1)                            [TSON-SCHEMA]

  the notation that writes every rung, and the reference encoding
  that carries the type system's values                    [this document]
```

Reading order runs the other way: this document stands alone, and a Class 1 processor (§1.5) needs nothing from [TSON-SCHEMA]. Each part adds capability without modifying the parts below it. The lexer defined in this document is complete: higher parts introduce no new tokens, no new lexer modes, and no changes to character classification. Within the 2026 series the lexer is frozen from this revision — Revision 35 changed the escape production (§7.2.2) under principle 6, and nothing was published against the earlier form; a later revision that touched it again would say so here.

[TSON-SCHEMA] defines a second document kind, the **schema document**, recognised by the header dispatch of §2.2. A schema document is parsed by a sibling grammar — the schema grammar — that shares this document's lexer and core value rules: its declaration grammar gives the reserved special tokens (§7.2.5) their meaning, and [TSON-SCHEMA] defines the operations of the directives (§3.3). None of that syntax appears in data documents: in the data grammar, a reserved special token is a parse error, and a `!!` token whose name is not followed by an adjacent `:` is a parse error.


### 1.4 Notation and Keywords


The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in RFC 2119.

The normative grammar for this document is §7.3–§7.7. Grammar excerpts appearing elsewhere in the body are illustrative.

Error categorisation — which processing layer rejects a violation and the canonical phrasing this series uses to mark it — is defined in §8.1.


### 1.5 Conformance


This series defines two conformance classes.

A **Class 1 processor** (data-format processor) implements the lexer (§7.2), the header and structural grammar (§2), the augmentation syntax (§3), base type resolution (§4), and the built-in type vocabulary (§5) as defined in this document. Such a processor:

- MUST parse every well-formed data document and reject every ill-formed one, reporting errors per §8.1;
- MUST recognise every type-annotation name in the built-in vocabulary and resolve annotated tokens per the named atom's contract (§5) — the vocabulary is implemented as a unit, so two conforming processors never disagree on whether a built-in name is meaningful;
- MUST preserve annotations, type annotations outside the vocabulary, and `schema` directives it does not act on (§3);
- MUST treat a directive token (`!!`) whose name is not followed by an adjacent `:` as a parse error (§1.3), and a directive name outside the closed positional set — or inside it but outside its position — as a parse error (§3.3);
- MUST match every name — field names, annotation names, and type-annotation names — against the identifier grammar (§7.7), after NFC normalisation, and reject a name that fails it as a parse error;
- MUST implement the name-hygiene checks of §8.2 over every named scope at this layer (each record's own field names), enforce them by default, and report a refusal as §8.1's fifth outcome — in the same report as the four categories, distinguishable from them, and never as a verdict on the document;
- MUST enforce the resource limits of §9.1 at their defaults or at a configured or documented value, reporting a limit refusal on the same terms;
- MUST be able to state, with any report that carries a refusal and SHOULD be able to state with no document in hand, the identifier policy, token policy, limits policy and UCD version under which it judges (§8.2, §9.1);
- MUST reject a schema document — a document whose header contains `!!meta` (§2.2) — reporting it as a schema document per §8.1, not as a malformed data document;
- is NOT REQUIRED to implement the schema layer of [TSON-SCHEMA].

A **Class 2 processor** (schema-aware processor) implements [TSON-SCHEMA] in addition and MUST also conform to this document.


## 2. Documents and Structure


### 2.1 A Complete Example

A data document exercising the header, the three structural types, augmentation, the type vocabulary, and the absent sentinel:

```
!!id:"https://example.com/orders/1042.tn"
!!schema:"https://example.com/order.tn"
@doc:"Order record exported 2026-07-03"
!order {
  order_id:  1042
  reference: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
  customer: {
    name:  "Ada Lovelace"
    email: "ada@example.com"
    tier:  @deprecated GOLD
  }
  placed:  !date 2026-07-01
  total:   !number 199.90
  flags:   0b0110
  items: [
    { sku: A-100 qty: 2 price: 49.95 discount: .5 }
    { sku: B-205 qty: 1 price: 100.00 discount: _ }
  ]
  discounts: { @expires:"2026-12-31" WELCOME10 => "10%" loyalty => _ }
  shipping: !!schema:"https://example.com/address.tn" !address {
    street: "12 Byron Rd"
    city:   London
  }
  notes: """
    Leave the parcel with the concierge.
    Gift wrap — no prices on the slip.
    """
}
```

The two header directives name the document and bind its schema; the root value's `!order` names which of that schema's types the document instantiates. The field-level `!!schema` on `shipping` scopes a different schema to that one value, paired with `!address` naming the type within it, so schema scope changes are always visible in the data (§2.2, §3.3). Annotations attach metadata at three levels: `@doc` on the document, the valueless `@deprecated` on a field's value, and `@expires` on a map key (§3.1). Under a bound schema, annotation names resolve against the governing schema's namespace and their values are validated against the named types ([TSON-SCHEMA] §3.3.3, §6): `order.tn` here imports the core type library (supplying `doc`) and declares `deprecated` as a bare, void-targeted marker and `expires` with a text value — a bare annotation is valid only against a void-targeted declaration, and a valued one only against a non-void target. Type annotations invoke the built-in vocabulary — `!uuid`, `!date`, and `!number` parse their tokens by atom contract (§3.2, §5) — while unannotated tokens resolve by the base rules of §4: `1042` and `0b0110` are integers, `.5` is a float, and `GOLD` and `A-100` are strings. Tokens are quoted only where content demands it (§7.1), `notes` is a multi-line token whose common indentation is stripped (§7.2.3), and the absent sentinel `_` marks a field and a map entry that are present with explicitly no value (§2.9).


### 2.2 Document and Header


A TSON document is the outermost structure: a **header** followed by a body. The header is a fixed sequence of directives — names, order, and cardinality are enforced by the grammar (§3.3, §7.4) — and it determines the document kind:

```
document   = [ id-directive ] ws ( data-doc / schema-doc )

data-doc   = [ schema-directive ws ] data-value ws
schema-doc = meta-directive ws *( import-directive ws ) schema-map ws
           ; schema-map — the schema document's annotated, braced
           ; declaration map — is defined normatively in [TSON-SCHEMA]

id-directive     = "!!" "id"     ":" single-line-token
schema-directive = "!!" "schema" ":" single-line-token
meta-directive   = "!!" "meta"   ":" single-line-token
import-directive = "!!" "import" ":" single-line-token
```

**Kind dispatch.** A parser consumes the `!!id` directive if present; if the next token is the directive `!!meta`, the document is a **schema document** — its header continues with any `!!import` directives, per the grammar above, and its body is a `schema-map`, defined normatively in [TSON-SCHEMA] — otherwise it is a **data document**, defined by this document. Classification therefore requires at most two directives of lookahead, no value parsing, and no backtracking. `!!id` is optional in the grammar for both kinds; publication and hash-pinning of a schema require it ([TSON-SCHEMA]). A Class 1 processor rejects schema documents with a categorized diagnostic (§1.5, §8.1).

Header directives are properties of the document, not of the body's root value. The root value of a data document is an ordinary data value: it carries annotations and a type annotation like any other value — a type annotation preceding the root core value identifies the expected type of the document's contained value — but never directives. A document with an empty header and no augmentation is simply a value.

Because a document contains exactly one value and directives do not produce content, a pure-metadata document uses the absent sentinel (§2.9) as its value:

```
!!id:"https://example.com/reserved.tn"
_
```


#### 2.2.1 Identity and Content Addressing


The `!!id` directive names the document: its argument is a URI identifying the document as a published artifact. `!!id` is optional in the grammar for both document kinds (§2.2). Publishing a schema — registering it for reference by other documents under its own name, or pinning it by content hash — requires it ([TSON-SCHEMA]); an id-less schema is a development artifact. Identity gives diagnostics, imports, and registries a stable way to refer to the document independent of its storage location.

When the identifying URI carries a content hash (the convention is defined below), the document is **content-addressed** and immutable: any change to its bytes changes its hash, which the canonical identity (defined below) binds to the document. A content-addressed document MUST be encoded in UTF-8. The grammar places the `id-directive` at the very start of the document (§2.2); a content-addressed document MUST follow it with a line terminator (any `line-term` of §7.3; for CR LF the hash input begins after the LF). The hash input is every byte after that terminator — well-defined because a directive argument is a single-line token (§3.3), so the id line is exactly one physical line — the id line, up to and including its terminator, is excluded so a document can carry its own hash without the circularity of hashing it. A byte order mark, if present, is stripped before parsing (§7.1) and never enters a hash input. The target of a hashed reference MUST carry an id line: the hash input is then always well-defined, and the embedded identity is available for the cross-check below.

**The hash-parameter URI convention.** A content hash rides on the identifying URI as a query parameter named for its algorithm: `?sha256=<hash>`, with the value in lowercase hexadecimal at full length — a truncated hash is an error. `sha256` is the algorithm of this revision; future algorithms use their own parameter names. The hash parameter is **verification metadata, not identity** (canonical identity is defined below); a query component, when present, MUST consist solely of hash parameters, and a query parameter whose name is not a recognized hash algorithm is an error — never silently retained, so identity never depends on which algorithms a given reader happens to recognize.

The pin rides in the query, rather than in a fragment or a structured directive value, so that it *reaches the origin and the cache*: a query is sent with the request, and an origin that stores revisions content-addressably can answer a pinned reference with the exact bytes it names long after the unpinned URL has moved on, while a cache can treat a pinned URL as immutable and keep it forever. A fragment is never sent, and a structured `{ url, hash }` value separates locator from integrity so cleanly that the integrity cannot reach the party able to satisfy it — either leaves the origin holding the bare locator, able to detect drift and never to repair it. Canonical identity strips the pin because the two answer different questions: the pin identifies a byte sequence where the rest of the URI identifies a resource (RFC 3986 §3.4's query is "non-hierarchical data that … serves to identify a resource", and *these exact bytes* is an identification). The **fragment is reserved**: it is no part of a schema identity, an identifying URI MUST NOT carry one (below), and a later revision may spell an intra-document reference there without a compatibility question.

**Canonical identity.** A reference's **canonical identity** is a documented profile of RFC 3986 §6.2.1 (simple string comparison), reached by two reductions: (1) remove the scheme and its `://` delimiter, and (2) remove the query component. What remains — **lowercase host plus path** — is the identity; two references name the same document if and only if their canonical identities are byte-for-byte identical. The scheme is a *transport hint*, not part of the name: `http://tson.io/2026/35/m/core.tn` and `https://tson.io/2026/35/m/core.tn` are the same document, and a consumer MAY fetch by whichever scheme its policy allows. The host is part of the identity — two hosts serving a like-named path are different documents — so a fetch-endpoint substitution can never silently redirect a name. A reference with no authority component (a local `file:`-style or path-only reference) has an empty host; its canonical identity is the path alone, and such references are resolved only against an application-supplied library entry ([TSON-SCHEMA] §10.1), never fetched.

Canonical identity stays at RFC 3986's cheapest rung by *restricting the input* rather than normalizing it, in the manner of the unquoted-token profile (§7.1): an identifying URI MUST already be in canonical form apart from scheme and hash query — lowercase host, no userinfo, no port (default or otherwise), no percent-encoding of unreserved characters, no dot-segments (`.`/`..`), and no fragment. An identifier that is not in this form is an error, not a candidate for normalization; no case folding, path resolution, or percent-decoding is ever performed at comparison time (rationale in [TSON-GUIDE]).

A consumer holding a hashed reference MUST verify the content against the declared hash before use and MUST NOT silently use mismatched content: a mismatch is an error, never a fallback. The authenticating hash always comes from the referencing side or another trusted source, never from the document alone: a body verified against its own embedded id-line hash is self-consistent, not authentic — an attacker who can rewrite the body can rewrite the id line to match. An embedded id whose canonical identity differs from the reference under which the document was obtained is likewise an error. Because the hash attaches to the *canonical identity* and not to the reference string, references sharing a canonical identity combine rather than compete: two that declare different hashes are in conflict — at most one describes the real bytes — and a consumer that observes both MUST report an error rather than choosing between them; a pinned and a plain reference to one identity are NOT in conflict — the declared hash governs the identity, the plain reference resolves to the verified content, and a verification failure fails both ([TSON-SCHEMA] §10.2 defines the schema-library treatment).

Content addressing composes: a data document may reference its schema by hashed URI, that schema its meta-schema, and so on to the pre-loaded bootstrap ([TSON-SCHEMA]), so a consumer holding a single identifier can verify a document together with its entire contract chain. Ordering, consensus, and mutability policy are application concerns outside this series.


### 2.3 Values


The data grammar has two closely related value rules. A **scoped value** is an optional `schema` directive followed by a data value; it occurs in exactly three positions — record field values, map entry values, and array elements. A **data value** is zero or more annotations, an optional type reference, and a core value; it occurs everywhere a value does.

```
scoped-value     = [ schema-directive ws ] data-value

schema-directive = "!!" "schema" ":" single-line-token

data-value       = *annotation [type-ref] core-value

type-ref         = "!" identifier

core-value       = record / map / array
                 / empty-brace
                 / absent / token
```

A `schema` directive affects how the value that follows it is interpreted and scopes to the value it prefixes (§3.3). The data grammar is closed: no directive produces content, and the directive name set is fixed (§3.3).

There are three structural types for contained data; the document is the implicit outermost structure.

| Type   | ASCII Syntax | Separator | Purpose                          |
|--------|-------------|-----------|----------------------------------|
| Record | `{ : }`     | `:`       | Fixed named fields               |
| Map    | `{ => }`    | `=>`      | Variable key-value associations  |
| Array  | `[ ]`       | whitespace| Variable-length sequence         |

Field values, map entry values, and array elements are scoped values; map keys are data values.


### 2.4 Tokens


A token is the atom of TSON data: **text plus form**. The text is the token's content after escape processing (and, for multi-line tokens, whitespace stripping); the form is one of three:

- **Unquoted** — `name`, `42`, `0xFF`, `2025-03-13`, `名前`, `v1.2.3`, `snake_case`, `A-100`. Available when every character is in the unquoted-token profile (§7.1).
- **Single-line quoted** — `"has spaces"`, `"alice@example.com"`, `"42"`. Any single-line content, with escape sequences (§7.2.2).
- **Multi-line quoted** — `"""` blocks for multi-line content with indentation stripping (§7.2.3).

The two quoted forms are distinct token kinds in the stream (§7.4): the grammar discriminates them where form matters — a directive argument admits only the single-line kind (§3.3) — and the unqualified term *quoted token* refers to either. The kind split is grammatical, never semantic: it governs which positions admit which form, and two tokens with the same text denote the same value regardless of form — identity is text.

**Form is not meaning.** Throughout this series, a token's form is consulted exactly once: by base type resolution (§4), where quoting is the author's way to say "the string `42`, not the number". Everywhere else only the text matters. Type contracts operate on text — `!number 10.2`, `!number "10.2"`, and `!number """10.2"""` are the same value (§5.2) — and identity is form-blind: `name` and `"name"` are the same field name (§2.5), and `Alice` and `"Alice"` are duplicate map keys (§2.6). Quoting is a lexical necessity, not a semantic signal: content containing characters outside the profile — spaces, colons (times, datetimes, URLs with a scheme), `@` (email addresses), `/` (paths, networks, rationals), `%` and currency symbols — MUST be quoted; content inside it may be written in any form. §7.1 lists the content kinds whose quoting rule is *always*.

**Separators.** Within structural types, adjacent values MUST be separated by at least one whitespace character, a comma, or both, and **a comma may follow a value**: `[1 2 3]`, `[1,2,3]`, `[1, 2, 3]` and `[1, 2, 3,]` are equivalent, as are `{ x: 1 }` and `{ x: 1, }`. Zero-width separation is a parse error. A comma that follows nothing — `[, 1]` — or follows a comma — `[1, , 2]` — is a parse error, and needs no rule of its own: a comma is not a value, so each fails as a missing one. One clause therefore decides every case, and the rule applies throughout the series, so a trailing comma is equally legal in the element and argument lists of [TSON-SCHEMA] §12.1. A trailing comma is safe here where it is not in JSON because TSON has no elision: absence is spellable and occupies a slot — `[1, 2,]` is two elements and `[1 2 _]` is three — so there is nothing for a stray comma to be confused with, where a grammar with holes (`[1, , 2]` as three elements in JavaScript) cannot tell two elements from three. A doubled comma is far more likely a lost element than deliberate noise, and in a format whose purpose is validating generated output, reading it as two elements is exactly the silent failure the format exists to catch. Note that only the *comma* is constrained: a whitespace-only separator before a closing delimiter — `[1 2 ]` — has always been legal. Structural delimiters inherently create token boundaries, so no separator is required between a delimiter and adjacent content: `{name:Alice}` is valid. Any non-zero amount of whitespace (including line breaks) between tokens is equivalent to any other; indentation is not significant. Within quoted tokens, whitespace is content, subject to each form's character rules (§7.2.2, §7.2.3).

TSON does not define a comment syntax. Metadata is expressed through annotations (`@`), which are preserved by the parser and available to consuming applications.


### 2.5 Record


A record is an ordered collection of named fields enclosed in curly braces. Field names are separated from values by a colon. Fields are separated by whitespace or an optional comma, and a comma may follow the last (§2.4).

```
record     = "{" ws field *( separator field ) [ ws "," ] ws "}"
field      = field-name ws ":" ws scoped-value
field-name = unquoted-token / single-line-token
```

A field's value is a scoped value, so a `schema` directive may prefix it, paired with a type annotation naming a type from the scoped schema:

```
{ database: !!schema:"https://example.com/db-config.tn" !db_config { host: db1 port: 5432 } }
```

Field names are bare tokens — unquoted or single-line quoted; the multi-line form is not admitted in name position — and directives, annotations, and type annotations MUST NOT precede a field name. Metadata concerning a field is expressed as annotations on the field's value — `{ name: @deprecated Alice }` — which attach to the value per §3.1.

A field name is an **identifier** (§7.7) at every layer, schemaless or governed. The production admits two spellings — unquoted, or single-line quoted — and they are two spellings of one set of names: what quoting buys is relief from the lexical accidents of the unquoted form (a name that would otherwise resolve as a number, `{ "007": x }`), never a different set of names. The decoded text of a field name is NFC-normalised (§7.2.1) and then matched in full against the identifier grammar, exactly as an annotation name's is; a token in name position whose decoded text is not an identifier is a parse error. So `{ "first name": 1 }`, `{ _id: 1 }` and `{ 42x: 2 }` are parse errors, and the remedy is the one the format already has: a record's fields are the named members of a shape, which is what makes them declarable, and *a key that is not a name belongs in a map* — `{ "Content-Type" => "text/plain" }` is the honest spelling of that data. A diagnostic SHOULD say so. Under a schema a field name matches a declared one or the value is invalid ([TSON-SCHEMA] §7.7), and a declared field name is an identifier by the schema grammar, so nothing further is asked of a governed document.

A record MUST contain at least one field. An empty `{}` is parsed as an `empty-brace` (§2.8).

Field names within a record MUST be unique. A record containing the same field name more than once is a resolver error (§8.1), with the diagnostic at the repeated occurrence's position. Two field names are identical if they produce the same NFC-normalized string after escape processing — `name` and `"name"` are the same field name, and a decomposed spelling of a name is the *same* name as its precomposed spelling rather than a malformed one: normalisation runs before the identifier match, so the pair is a duplicate-field error, never a parse error. Name identity is case-sensitive.


### 2.6 Map


A map is a collection of key-value associations enclosed in curly braces. Keys are separated from values by the arrow operator `=>`. Entries are separated by whitespace or an optional comma, and a comma may follow the last (§2.4).

```
map       = "{" ws map-entry *( separator map-entry ) [ ws "," ] ws "}"
map-entry = data-value ws "=>" ws scoped-value
```

Map keys are data values — they may carry annotations and a type reference but not directives. Keys are not restricted to strings, and a key is not a name: a map is where a key that is not an identifier lives (§2.5).

Duplicate keys MUST NOT be present: a map containing two identical keys is a resolver error (§8.1), with the diagnostic at the repeated occurrence's position — one category for the rule at this layer, whether the textual or the decoded-value identity below detects a given pair. Key identity is layered, and each layer detects at least what the one below it does. **Textual identity** is the parser's minimum: scalar keys are identical if they produce the same NFC-normalized string after escape processing (`Alice` and `"Alice"` are duplicates); compound keys are identical if they have the same structure with textually identical elements at every position. **A processor that decodes values compares decoded values**: from base type resolution (§4) onward, different spellings of one value are one key (`0xFF` and `255`, `1_000` and `1000`), so a reader producing decoded output rejects keys the parser's textual rule could not relate. A declared key type may make *more* keys equal — `1` and `1.0` are two keys with no schema and one under a `number`-keyed schema, and two spellings of one octet string are one key under a `bytes`-keyed one — because under a schema identity is over the key type's *value space*, never its lexical space ([TSON-SCHEMA] §5.5); a type-aware duplicate under a schema is a Class 2 validation error ([TSON-SCHEMA] §7.7), and a declared type never makes fewer keys equal. A key's annotations and type annotation do not participate in identity at any layer: `!text a` and `a` are the same key.

A map MUST contain at least one entry. An empty `{}` is parsed as an `empty-brace` (§2.8).


### 2.7 Array


An array is an ordered, variable-length collection of values enclosed in square brackets. Values are separated by whitespace or an optional comma, and a comma may follow the last (§2.4). Array elements are scoped values (§2.3): a `schema` directive may prefix an individual element and scopes to that element alone (§3.3, [TSON-SCHEMA] §7.8).

```
array = "[" ws [ scoped-value *( separator scoped-value ) [ ws "," ] ] ws "]"
```

In a whitespace-separated array a directive binds unambiguously to the element it prefixes, but for readability encoders SHOULD place each directive-carrying element on its own line or separate elements with commas.


### 2.8 Brace Disambiguation and Empty Braces


The parser determines what a curly-brace structure is by its content:

1. If the opening brace is followed by `}` (with only whitespace between), the structure is an **empty-brace**.
2. Otherwise, the parser consumes one data-value and inspects the next token:
   - `:` → the structure is a **record**. The consumed data-value MUST be a bare token — carrying no annotations and no type reference, with a core value that is an unquoted or single-line token whose decoded text is an identifier (§2.5, §7.7) — and becomes the first field's name. Anything else is a parse error: field names are identifiers.
   - `=>` → the structure is a **map**. The consumed data-value becomes the first entry's key.
   - anything else → parse error. A value inside curly braces MUST be followed by `:` (record) or `=>` (map).

The bare-token check applies only to the first field name; once the structure is known to be a record, subsequent fields are parsed by the `field` production, which admits only a token in name position and matches it the same way. The lookahead budget is one consumed value plus one token; what the dispatch does with the token it already holds is an identifier match, and nothing more.

Records and maps share the brace form deliberately, and the sharing stands on its own merits rather than on where it came from: `{ k => v }` reads well beside `{ k: v }`, the dispatch is one token deep and stated as such, and an empty brace resolving by expected type is exactly right under a schema, which is the mode the format is for. A second bracket pair for maps would cost every map in every document and schema to save a parser one token.

**Empty braces** are deferred to the resolver. In the absence of declared type information, an empty-brace resolves to an empty record. When a higher part supplies an expected type ([TSON-SCHEMA] §7.7), it resolves to the empty record or the empty map according to that type — the two containers that share the brace form and cannot be told apart when empty. An array or tuple has its own empty spelling (`[]`), and an empty brace at such a position is not an empty container of that type but a value of the wrong form ([TSON-SCHEMA] §7.7).

```
empty-brace = "{" ws "}"
```


### 2.9 The Absent Sentinel


The underscore token `_` represents an explicitly absent value, distinct from any typed value. It is the format's one spelling of absence: there is no `null` value, and a JSON reader maps JSON `null` to absence in the model (§6).

```
absent = "_"
```

The absent sentinel is a structural concept, not a type. The interpretation of absence (a host null, unset, default, removal) is determined by the consuming application or by higher parts of this series.

Because `absent` is a core-value alternative, `_` may occupy any data-value position: record field values, map entry values, array elements, and the document's top-level value. The single restriction is that the absent sentinel MUST NOT appear as a map key — a resolver-layer constraint, not a grammar constraint: the `map-entry` production accepts any value in key position, and the resolver rejects absent keys.

A field or entry set to `_` is **present with an absent value** — distinct from not appearing at all. In arrays, absent elements occupy positional slots: `[1 _ 3]` has three elements. Higher parts that impose size or element constraints count all slots, and MAY restrict whether absence is permitted at element positions ([TSON-SCHEMA]).

Whether absent values at optional positions are encoded on the wire using `_` or omitted entirely is a serialisation context concern, not a document property. Implementations SHOULD provide a mechanism for controlling this at the encoder level.


## 3. Augmentation


Augmentation adds metadata, type information, and directives to values without modifying the structural grammar. All augmentation is optional and is expressed within the value rules (§2.3).

TSON supports three augmentation features: configuration directives (`!!`), annotations (`@`), and type annotations (`!name`). Directives are permitted only in the document header (§2.2) and scoped-value positions (§2.3); annotations and type annotations are available on every data value. Directives precede annotations in the grammar; augmentation attaches to the value that follows it.

Annotations are ordered in the token stream, and implementations MUST preserve their order.


### 3.1 Annotations


An annotation attaches metadata to a value without modifying the value itself: the special token `@` immediately followed (without whitespace) by an identifier forming the annotation name, optionally followed by `:` and a data value.

```
annotation = "@" identifier [ ":" data-value ]
```

The name is an unquoted token whose text matches the identifier grammar (§7.7); a token in name position that fails it is a parse error. Every identifier is expressible unquoted (§7.1), so the position loses nothing by admitting no quoted form.

**Adjacency and termination.** The `:` (when present) MUST be adjacent to the annotation name. When the `:` is absent, at least one whitespace character MUST follow the annotation name. These rules make annotation boundaries lexically determined by the single character after the name.

**Value scope.** When `:` is present, the annotation's value is exactly one `data-value`, which terminates at the end of its core value — and which may itself carry annotations. In `@a:@b:val target extra`, `@a`'s value is the data-value `@b:val target`: the core value `target`, annotated by `@b`, whose own value is `val` — and `extra` belongs to the surrounding context. (The shorter `@a:@b:val target` illustrates `@a`'s value in isolation but is not itself a complete data-value: once `@a` consumes `@b:val target`, the data-value containing `@a` still requires a core value of its own, and nothing remains to supply it.) Contrast `@a:@b val target` (no colon on `@b`): there `@b` is a valueless annotation on the core value `val`, so `@a`'s value is `@b val` and `target` belongs to the surrounding context — that form is complete as written. An annotation is never itself a value: `{ x: @a:@b:val }` is a parse error, because `@a`'s data-value still requires a core value after the annotation `@b:val`. Annotation values are always data values — concrete values, not type definitions.

Annotations precede the value they annotate — including either side of a map entry, where annotations on the key annotate the key and annotations on the value annotate the value. The parser preserves annotations in their authored positions.

**Multiplicity.** An annotation name MAY appear any number of times on a single value; all occurrences are preserved in source order.

At the data-format layer, annotations are preserved, ordered metadata with no further interpretation; [TSON-SCHEMA] defines their validation. A processor conforming only to this document MUST preserve annotations without validating them.


### 3.2 Type Annotations


A type annotation associates a named type with the following value: the prefix operator `!` immediately followed (without whitespace) by an identifier forming the type name.

```
type-ref = "!" identifier
```

As for annotation names, the type name is an unquoted token whose text matches the identifier grammar (§7.7); `!42x` is a parse error, not a reference to an undeclared type. At least one whitespace character MUST separate the type name from a following token; no separator is required before a structural delimiter. `!person { name: Alice }` and `!person{name:Alice}` are both valid; `!int32"5"` is a parse error — write `!int32 "5"`.

At the document level, a type annotation identifies the expected type of the document's contained value; within structural types, it identifies the type of the value that follows. A type annotation applies to the value, not its contents: `!person { name: Alice }` tags the record, not its fields.

In schemaless processing, the built-in type vocabulary (§5) resolves a fixed set of annotation names; [TSON-SCHEMA] defines resolution against declared schemas. A processor MUST preserve type annotations it does not resolve as uninterpreted markers attached to their values and MUST NOT reject a document because a type annotation is unresolved.

Type expression syntax is not available in data values: array brackets, type arguments, and the optional `?` suffix exist only within the [TSON-SCHEMA] type-definition grammar, and their appearance after `!` in a data value is a parse error.


### 3.3 Directives


A configuration directive provides pre-interpretation configuration: the `!!` compound token followed by a name, an adjacent `:`, and a single-line-token argument. Every directive in the series shares this lexical shape:

```
"!!" name ":" single-line-token
```

The `:` MUST be adjacent to the directive name. The argument is a single **single-line** quoted token (§7.2.2) — a multi-line token at a directive argument is a parse error; in every directive of this series the argument is a URI or file reference (RFC 3986). The restriction keeps every directive on one physical line — in particular the id line, whose terminator bounds the hash input (§2.2.1).

Directives appear only in the document header (§2.2) and in scoped-value positions (§2.3): record field values, map entry values, and array elements. A directive scopes to the document or value it prefixes. Directives are not permitted before map keys, field names, or annotation values: keys and names are identity-bearing, so a schema scope on a key would make identity scope-dependent (§2.6), and annotation values are metadata resolved against the governing target's namespace by the one-hop rule ([TSON-SCHEMA] §3.3.3), which a local scope switch would subvert.

Unlike an annotation, a directive is not strippable metadata: it affects how the value is interpreted. A Class 1 processor does not act on `schema` bindings — it preserves them for the consuming application — but it enforces directive grammar in full.

**Closed positional name set.** Directive names are fixed by the grammar. Each name is legal in exactly one kind of position, and order and cardinality are enforced by the productions (§2.2, §7.4) rather than by prose. Four names exist in the series:

| Name | Document kind | Placement | Argument | Operation |
|---|---|---|---|---|
| `id` | both | Header; first line; optional in the grammar — publishing a schema requires it ([TSON-SCHEMA]) | URI | Names the document (§2.2.1). The id line is excluded from content hashing. |
| `schema` | data | Header, at most once; field values; map entry values; array elements | URI | Binds the schema governing the document or value in scope. |
| `meta` | schema | Schema-document header; exactly once, first directive after the optional `id` | URI | Binds the meta-schema governing the schema's declarations. |
| `import` | schema | Schema-document header; after `meta`; repeatable | URI | Imports the named schema's declarations. |

Placements for all four names are enforced by this document's grammar (§2.2, §7.4); the `id` operation is defined in §2.2.1; the `schema`, `meta`, and `import` operations — and the schema body's `schema-map` production — are normative in [TSON-SCHEMA]. This document uses `meta` only for kind dispatch (§2.2).

Any other directive name, and any of these names outside its placement, is a parse error. There is no unknown-directive category and no directive extension mechanism: new capability arrives through the type system, not through the grammar (§1.2). Directive names on the wire are always the canonical names above; localized presentation is a tooling concern outside this series.

**No parse-time I/O.** Directive arguments are references; a processor conforming to this document never dereferences them. Parsing a TSON data document performs no I/O — the document's structural meaning is fully determined by its bytes (§9.3).


## 4. Base Type Resolution


Base type resolution is the Class 1 reading of an untyped token: it assigns one of three host base types — boolean, number, string — to an unquoted token by its shape, and the string type to every quoted one. It is total: every token resolves to something, and a resolver never refuses (§4.4).


### 4.1 Applicability


Base type resolution applies **only in schemaless documents** — a document whose header carries no `!!schema` (§2.2) — and only to a token that carries no built-in type annotation, since a built-in annotation overrides base resolution for its token (§5). Under a schema it does not apply at all: every value is typed by its position or by its tag, each declared atom type owns its own parsing contract, and there is no third way to read a token ([TSON-SCHEMA] §4.2, §7.1). The tokens `true` and `false` have special status only under base type resolution (§4.2). Resolution applies to **data values only**: field names are names (§2.5), and map keys are text, never resolved — in `{ "007": 007 }` the key is the name `007` and the value is a string by fallthrough (leading zeros fail the `integer` production, §4.3), and `07` and `7` are distinct map keys because key identity is textual at this layer (§2.6).

A Class 1 value is one of three things — an untyped token, a token carrying a built-in annotation from §5's vocabulary, or a container — and this section governs only the first. The vocabulary is the schemaless way to say what a token *means*: `!date 2025-03-13`, `!uuid 9f1c…`, `!int32 007` are typed and checked with no schema in scope. Base resolution is what a token gets when its author chose not to say.


### 4.2 Boolean


The tokens `true` and `false` (case-sensitive, lowercase only) resolve to boolean values. No other representations (yes, no, on, off, True, FALSE) are recognised as boolean.

These two are the notation's only keyword-like tokens, and they are kept for a reason that is not JSON's. [TSON-SCHEMA] §5.4 derives a choice's `disjoint` fact from the classes this section assigns, and BOOLEAN is a discrimination class *only because* the two tokens are matched ahead of the number grammar (§4.5): without that match the kernel's `boolean` — the enum `[true false]` — would be string-class, `( boolean | text )` would derive `disjoint: false`, and every boolean in an untagged choice would need a `!boolean` tag. So a Part 1 edit here would change a derived fact in Part 2, which is why the order is normative rather than a convenience. Secondarily, a boolean is a value a schemaless read produces and a consumer stores, and `true` against `"true"` is the same distinction `42` and `"42"` draw (§2.4). Under a schema neither token is special: `boolean` reads its member names as any enum does, and `true` is an identifier like any other (§7.7).


### 4.3 Numbers


An unquoted token resolves to a numeric value if and only if its complete text matches the `number` production (§7.6). Base type resolution recognises four numeric forms — special values, based integers, floats, and integers — which are pairwise disjoint; a token matching none falls through to string (§4.4).

- **Special values** — `.nan` and `.inf`/`.infinity` (infinity with optional sign). Lowercase only.
- **Floats** — signed decimal with fraction and/or exponent (`1.5`, `.5`, `6.02e23`, `-2e-3`). Digits MUST follow a decimal point; the integer part MAY be omitted. `5.` is not a number. The signed zeros `+0.0` and `-0.0` are floats whose sign MUST be preserved.
- **Integers** — signed decimal integers. Leading zeros MUST NOT be used except for the single digit zero. The rule is the format's own and not an inherited one: it is not octal ambiguity that excludes `007` — `0o377` spells octal explicitly — but the fact that a zero-padded token is data whose leading zeros are *significant*, a postcode, an identifier, a code. Reading `007` as `7` destroys information irrecoverably; reading it as the string `007` preserves exactly what was written. `007` is a string because it is one (§4.4).
- **Based integers** — hexadecimal, octal, and binary integers via the lowercase prefixes `0x`, `0o`, `0b` (`0xFF`, `0o755`, `0b1010`), with optional sign; hex digits may be either case.

These are the familiar decimal integer and float forms, with an optional leading `+`, leading-dot fractions (`.5`), underscore digit separators (`1_000_000` — permitted only between digits, enforced by the digit-sequence productions), arbitrary precision, based integers, and the special values. The two rules that read as restrictions — no leading zeros, and digits required after a decimal point (`5.` is not a number) — are both there so that a token which is not a number falls through as the string it is rather than being read as a number it is not. Richer numeric forms — rationals (`2/3`), complex numbers (`3+4i`), and hexadecimal floats — are **not** recognised by base type resolution; the built-in type vocabulary provides typed access to them under explicit annotation (§5.6). Complex and hex-float tokens are expressible unquoted and resolve as strings; rational content contains `/`, which is outside the unquoted profile (§7.1), so rational values are always quoted. A consequence of based-integer recognition: hex-shaped identifier data (a blockchain address such as `0x71C7656EC7ab88b098defB751B7401B5f6d8976F`) resolves as a number; authors who intend such a token as a string MUST quote it.

Numeric values are arbitrary precision; how values map to host-language numeric types is an implementation concern (see §9.1 for literal-length limits). Non-ASCII digit sequences do not match the number grammar and fall through to string.

**Equivalence and preservation.** Distinct representations of the same value — `255`/`0xFF`, `6.02e23`/`602e21`, `.5`/`0.5`, `1_000`/`1000`, `+42`/`42` — MUST resolve to equal values. Implementations that re-emit documents SHOULD preserve the representation as written.


### 4.4 String


Any quoted token resolves to a string value. Any unquoted token that does not match boolean or the `number` production resolves to a string value — including near-miss numeric forms such as `007`, `1.2.3`, `5.` and `1__0` (leading zeros, second dots, a trailing point and a doubled separator all fail the number grammar), the complex form `3+4i` (§4.3), and the token `null`, which is an ordinary string. There are no exceptions: every string-resolving token is one whose complete text failed the boolean and number rules. The bare tokens `-`, `+`, and `.` do not exist (§7.2.4); write the single-character strings quoted.

The fall-through is deliberate, and it is total on purpose. A rule sharp enough to refuse `007` as a near-miss would have to say why `2025-03-13`, a bare UUID, `192.168.0.1`, `2h30m` and `1.2.3` are not near-misses — every one of them begins with a digit and every one of them is a string a configuration file writes unquoted — and the only such rule is an enumeration of shapes, which is §5's vocabulary restated inside this section as exceptions. Refusing an untyped token also adds no information: the format already has two mechanisms that do know what a token means, a `!`-annotation from §5's vocabulary in a schemaless document (`!int32 007` is refused, `!date 2025-03-13` is checked) and a declared type under a schema ([TSON-SCHEMA]), and a token an author chose not to type is, by that choice, a string. The hazard worth naming is the reflex `null` or the mistyped `5.` landing silently as text at a position that wanted something else; the remedy is the annotation, or the schema, not a keyword.


### 4.5 Resolution Order


In a schemaless document (§4.1), the parser MUST attempt resolution in this order:

1. boolean — exact keyword match (`true`, `false`)
2. number — full-token match against the number grammar (§7.6)
3. string — fallback for everything else

To represent the strings `"true"`, `"false"` or `"42"` in schemaless TSON, use quotes (§7.1).

**Why three classes, and no more.** This section classifies *host base types*: what a schemaless read hands back with no library type and no ordered vocabulary behind it — a boolean, a number, a string. It does not classify *semantic* types. A date, a UUID, an address, or a duration is lexically recognisable and starts with a digit, and the number scanner has already inspected it and failed; recognising it here would cost nothing mechanically and would cost the format a great deal: [TSON-SCHEMA] §5.4 couples this order to derived disjointness, so a DATE class would narrow what a `text` variant admits untagged in every schema that never mentions a date, and would make §5's vocabulary non-additive — each new atom changing derived facts on choices that do not name it — until "string" meant "matched none of twenty ordered rules". A Class 1 document reaches a semantic type deliberately, through an annotation (§5), and a Class 2 document through a declaration; a choice over two text-form types is written as a labelled group ([TSON-SCHEMA] §5.11), which discriminates by label and needs no tag. That answers the same question for `uuid`, `uri` and every future addition without re-arguing each.


## 5. Built-in Type Vocabulary


The built-in type vocabulary is a fixed set of type annotations that extend base type resolution with binary, temporal, identifier, network, and precision-constrained numeric types, giving schemaless documents access to common typed values without a schema. This section assigns meaning to the annotation names listed below; it introduces no lexical or grammatical changes — every construct it interprets is a token the grammar already parses.


### 5.1 Applicability


The vocabulary applies **only in schemaless documents** — the same condition as base type resolution (§4.1): a document whose header carries no `!!schema`. A built-in annotation overrides base type resolution for the annotated token: the token is parsed by the named atom's contract (§5.2) instead of the §4 resolution order. Type annotations whose names are not in the vocabulary are preserved as uninterpreted markers (§3.2).

Under a schema ([TSON-SCHEMA]) the vocabulary does not apply: all type annotations resolve through the schema's type-name namespace, and schemas wanting these names import the core type library, whose entries denote the same parsing contracts and the same value spaces defined here ([TSON-SCHEMA] §5.5, §9).

**Annotation names are case-sensitive.** Only the exact names listed below are recognised; `!UUID` is not a built-in annotation.

**Scalar values only.** Built-in annotations apply to scalar values. A built-in annotation on a record, map, or array value is a resolver error. Element types are not expressed on a container; annotate elements individually: `[!int32 1 !int32 2]`.


### 5.2 The Atom Parsing Model


Each atom owns a **parsing contract**: which tokens it accepts, and what host value results. The contract applies to the token's content after escape processing; whether quoting is *required* is a lexical property of the content, not of the atom (§2.4). Content expressible unquoted may be written either way: `!date 2025-03-13` and `!date "2025-03-13"` are equivalent.

Host-value entries in the tables are informative; the precise representation is implementation-defined, but implementations MUST preserve the parsed value's information content (a `uuid` round-trips to the same 128 bits; a `number` preserves its digits).

**Parsing and validation are distinct.** Parsing takes a token to a host value; validation checks the parsed value against the atom's constraints. `twelve` under `!int32` is a resolver error — the integer grammar cannot interpret it; `9999999999` under `!int32` parses as an integer and then fails validation against the 32-bit range. A token the atom's grammar rejects "is a resolver error"; a parsed value violating the atom's range "is a validation error" (§8.1). The categorisation reflects where the check happens: the structural parser has already accepted the document as well-formed before any atom contract is consulted, so an atom-contract failure is a resolution failure, not a structural one (§8.1). Within this vocabulary, range constraints belong to the numeric atoms (§5.6) and the CIDR prefix rules (§5.5); the remaining atoms are pure format checks.


### 5.3 Binary Types


| Annotation | Format                | Host value     |
|------------|-----------------------|----------------|
| `!bytes`   | Base64 (RFC 4648 §4)  | octet sequence |

`!bytes` is the only binary annotation, and its spelling in this notation is base64. The value is the octets: an alphabet is a *spelling* of an octet sequence, not a kind of value, and a schemaless document has no schema to carry a selector, so there is nothing for a reader to consult and one alphabet is fixed for the whole class of text encodings. Under a schema the same type, `bytes`, carries an `encoding` selector that a schema sets to another RFC 4648 alphabet by declaring another type ([TSON-SCHEMA] §5.5, §9); `!bytes` names one type in both conformance classes rather than one in Part 2 and several in Part 1. Equality, identity and content addressing are over the octets and never over a spelling ([TSON-SCHEMA] §5.5).

A token that is not valid base64 is a resolver error. Padding is REQUIRED: a `!bytes` token MUST include the `=` pad characters RFC 4648 §3.2 requires, and a token whose length is not a multiple of four is a resolver error — implementations MUST NOT accept unpadded input merely because a host library tolerates it. Rejecting non-canonical padding *bits* (RFC 4648 §3.5) remains a MAY. Binary values SHOULD always be quoted.


### 5.4 Temporal Types


| Annotation  | Format               | Host value |
|-------------|----------------------|------------|
| `!date`     | RFC 3339 `full-date` | date       |
| `!datetime` | RFC 3339 `date-time` | instant    |
| `!time`     | RFC 3339 `full-time` | time of day (UTC) |
| `!duration` | RFC 3339 Appendix A `dur-date` / `dur-time` / `dur-week`, with no `Y` or month-`M` component; an optional leading `-`; a fraction on the seconds component only | duration (seconds) |
| `!period`   | `P` with a `Y` component, an `M` component, or both, and nothing else; an optional leading `-` | period (months) |

A token that does not match the named format is a resolver error. `date-time` and `full-time` values contain colons and MUST be quoted (§2.4); `full-date`, `duration` and `period` values are valid unquoted.

**One ISO 8601 duration, two atoms.** `!duration` is elapsed time — a signed exact decimal number of seconds — and `!period` is a calendar span — a signed integer number of months. The split is what makes each totally ordered: a month has no fixed length beside a second that has one, so the two are two value spaces rather than one partially ordered one, and `P1Y2M3DT4H5M6S` is an error under both. A span that is genuinely both is a record with a field of each. Three rules the grammar does not carry on its face:

- **The week form stands alone.** RFC 3339 Appendix A's `duration` is an alternation, `"P" (dur-date / dur-time / dur-week)`, so `P1W2D` is not a duration and neither is `P1WT1H` — a week with a time part has no production. (ISO 8601-2's relaxation admitting `P1W2D` is not adopted.)
- **A week is exactly 7 days and a day exactly 86400 s**, so `P2W`, `P14D` and `PT336H` are one value, and the week form belongs to `!duration` and not to `!period` for that reason: it has a fixed length. (`java.time` puts weeks on the calendar side; this format does not, because seven days is 604800 s only if every day is 86400 s, which is true of a duration and is not the claim a period makes.)
- **A text encoding emits `PTnHnMnS` and nothing else.** The value is a number of seconds, so a writer cannot recover that its input was written in weeks or in days: `P3W` round-trips as `PT504H` and `P9DT1H` as `PT217H`. `PnW` and `PnD` are reading conveniences, as `0x50` is for an integer — admitted on the way in, gone on the way out, and never a claim about how a value was spelled — and the round trip is not a defect.

**Fractional seconds and range.** The fraction on a seconds component — RFC 3339's `time-secfrac`, in `full-time`, `date-time` and `dur-time` alike — is at most **nine digits**: `"." 1*9DIGIT`, so `PT0.0000000001S` is not a token. No host runtime represents a duration or a timestamp finer than a nanosecond and several are coarser, so a value below that floor is not exactness anyone can use; a schema that needs finer writes `number` seconds, or its own atom, with the unit under its own control. A `!duration`'s magnitude does not exceed 2⁶³ − 1 nanoseconds (about 292 years), stated as a magnitude so that negating an admitted duration yields an admitted one: a processor MUST be able to represent every value in that range and MUST reject one outside it whether or not its own representation could hold it — a range that depends on which implementation read the document is not a range. A span longer than that is a calendar span and is a `!period`, or a physical quantity and is a `!number` in the unit the schema names. The ceiling is a value rule and the floor a lexical one, deliberately: a seconds count is summed from the `D`, `H`, `M` and `S` components, so `P400000D` overflows with no single component long enough to catch, where a fraction reaches the value through one component and is capped at the token ([TSON-SCHEMA] §5.5).

**Instants.** A `!datetime` is the instant on the UTC timeline, and a `!time` is the time of day in UTC on `[00:00:00, 24:00:00)`: the offset RFC 3339 makes mandatory is a *spelling*, so `2026-01-01T10:00:00+01:00` and `2026-01-01T09:00:00Z` are one value, `-00:00` (offset unknown, RFC 3339 §4.3) is the same instant as `Z`, and `23:30:00-02:00` is `01:30:00Z` — the price of a time with no date. This notation preserves the offset as written; equality, ordering and bounds compare the instant ([TSON-SCHEMA] §5.5). A wall clock with no offset, and an appointment for which the zone is data, are other value spaces and not these types.


### 5.5 Text, Identifier, and Network Types


| Annotation | Format | Host value |
|------------|--------|------------|
| `!text`    | any token content (Unicode code point sequence) | string |
| `!uuid`    | RFC 9562 | UUID |
| `!uri`     | RFC 3986 | URI |
| `!email`   | RFC 5322 `addr-spec`, restricted to the `dot-atom "@" dot-atom` core | email address |
| `!ipv4`    | IPv4 dotted-quad (RFC 3986 `IPv4address`) | IPv4 address |
| `!ipv6`    | IPv6 text representation (RFC 4291 §2.2) | IPv6 address |
| `!cidr4`   | IPv4 address `/` prefix length 0–32 (RFC 4632) | network |
| `!cidr6`   | IPv6 address `/` prefix length 0–128 | network |
| `!mac`     | EUI-48, colon- or hyphen-separated hex octets (RFC 9542) | MAC address |

`!text` is the unconstrained text atom: it accepts every token and its host value is the token's text. It adds nothing beyond what an unannotated token's base resolution to a string already provides (§4.4) — it exists so the string case can be asserted explicitly (a quoted numeric token under `!text` is unambiguously the string) and because it anchors the constraint family (`text_type`) on which the library's `uri`, `regex`, and `email` types build.

Every atom in this table is **string-class** under base type resolution (§4.5), as are `!bytes` and the temporal atoms: a schemaless read of the bare token yields a string, and only the annotation says which of them it is. Under a schema the same fact governs untagged choices — `( date | text )` and `( uuid | email )` are not disjoint, so every value in one carries its tag ([TSON-SCHEMA] §5.4) — and the shape that wants no tag is the labelled group, `{ ( on: date | note: text ) }`, which discriminates by label ([TSON-SCHEMA] §5.11).

`!email` is deliberately narrower than full RFC 5322 `addr-spec`: only the `dot-atom "@" dot-atom` core is accepted. Quoted local parts (`"a b"@example.com`), domain literals (`user@[192.0.2.1]`), and comments — all admitted by the full grammar — are rejected: they do not belong in an interchange scalar, and naming the RFC without scoping it would leave each implementation to pick its own subset. Email addresses contain `@` and MUST be quoted (§2.4).

A token that does not match the named format is a resolver error; a CIDR prefix length outside the address family's range is a validation error, as is an address whose host bits are nonzero under the stated prefix length — the host value is a network, and accept-and-mask would be lossy (§5.2). IPv6 zone identifiers (`fe80::1%eth0`, RFC 4007) are host-local and are not part of the `ipv6` or `cidr6` contracts. URIs with a scheme and IPv6 addresses contain colons, and CIDR values contain `/`; all of these MUST be quoted (§2.4). MAC addresses in the colon-separated form MUST be quoted; the hyphen-separated form is expressible unquoted.


### 5.6 Numeric Types


The numeric atoms are defined against the productions of the number grammar (§7.6). Each atom accepts the listed forms (parse), and where a range is listed, the parsed value MUST fit it (validation).

| Annotation | Accepted forms (§7.6) | Constraint | Host value |
|------------|----------------------|------------|------------|
| `!int8` `!int16` `!int32` `!int64` `!int128` `!int256` | `integer` / `based-integer` | *n*-bit two's-complement signed range | *n*-bit integer |
| `!uint8` `!uint16` `!uint32` `!uint64` `!uint128` `!uint256` | `integer` / `based-integer` | *n*-bit unsigned range | *n*-bit unsigned |
| `!positive_integer` `!non_negative_integer` `!negative_integer` `!non_positive_integer` | `integer` / `based-integer` | sign bound (`> 0`, `>= 0`, `< 0`, `<= 0`); unbounded precision | integer |
| `!number`  | `integer` / `float` | exact; scale is not part of the value (`1`, `1.0`, `1.00` are one value) | exact number |
| `!float32` | `integer` / `float` / `hex-float` / `special-value` | approximate, IEEE 754 binary32 grid | 32-bit float |
| `!float64` | `integer` / `float` / `hex-float` / `special-value` | approximate, IEEE 754 binary64 grid | 64-bit float |
| `!rational` | `rational` | exact; denominator nonzero (by grammar) | rational |
| `!complex` | `complex` / `float` / `integer` | components per type | complex number |

The atoms are the schemaless parsing primitives; the core type library builds its named types on the meta constructors over the same value sets — `!number` feeds `number` (the exact tier, `decimal_type`), `!float32`/`!float64` feed the approximate `float_type` binary formats, `!rational` feeds `rational`, `!complex` feeds `complex` (`complex_type`). The exact atoms (`!number`, `!rational`, and the integer atoms) preserve the value as written; the approximate atoms (`!float32`, `!float64`) round the parsed value onto the named IEEE 754-2019 grid, so precision may be lost — the atom-level statement of the exact/approximate split the type library records with `@exact` ([TSON-SCHEMA] §9).

The integer atoms — the full fixed-width ladder and the four bound-only refinements, matching the core type library's `integer_type` family one for one — accept based and signed forms uniformly (`!uint32 0xFF00_0000`, `!uint32 +10`); the range constraint, not the lexer, enforces unsignedness and sign bounds: `!uint32 -10` parses, then fails the unsigned range at validation — and the annotation is the only schemaless route to the rational, complex, and hex-float forms: complex and hex-float tokens resolve as strings under base resolution (§4.3), and rational content contains `/`, so rational values are always quoted (`!rational "2/3"`). The float atoms accept plain integer tokens and give the special values IEEE 754-2019 semantics (`.inf`, `.nan`, signed zeros, subnormals); `!number`, being exact, does not accept the special values. NaN payloads are not part of a value's information content: every NaN, however produced, denotes the canonical quiet NaN, so preservation (§5.2) holds by definition; applications that need payload bits should carry them as integers or binary values. Unannotated numeric tokens resolve through base type resolution alone.


## 6. TSON and JSON


TSON is JSON-*like* by design and is not a JSON superset. A JSON document is not a TSON document: the notation carries no `null` keyword (§4.4), treats field names as identifiers (§2.5), has no surrogate-pair escapes (§7.2.2), and admits forms JSON does not. What the two share — `"`-delimited strings, `[ ]` arrays, `{ name: value }` records, the `\n \r \t \\ \"` escapes, base type resolution as a mechanism, and the rule that an unadorned numeric token names the exact type `number` — is shared because each was a good idea on its own, and none of it rests on a compatibility claim.

A processor that reads JSON does so through a **JSON reader**, which is a second encoding of the same model rather than a mode of this notation. The reader maps a JSON object to a record where its keys are identifiers and to a map otherwise; a JSON array to an array; a JSON string to a string; a JSON number to `number` ([TSON-SCHEMA] §9); `true` and `false` to booleans; and JSON `null` to **absence**, where the position's state decides whether absence is admitted, exactly as `_` is judged (§2.9). Under a schema the reader validates on the same terms as this notation's Class 2 processing; schemaless, it reads on base type resolution's terms (§4). That is the JSON compatibility this series offers, and it is the reader's, not the notation's — which is what lets a JSON `null` land as absence in the model instead of as the string `null` at a `text` position, the one outcome a keyword in this notation could not have prevented.


## 7. Grammar Reference


This section is the sole normative grammar for the data format. It extends RFC 5234 ABNF with Unicode property references (`XID_Start`, `XID_Continue`, `Nd`, `Pattern_White_Space`, `Pattern_Syntax`) — character property sets defined in UAX #31 and the Unicode Character Database. String literals in double quotes match exact characters; code points are identified in comments using U+XXXX notation.

The lexer grammar in §7.3 is complete for the entire TSON series. The parser grammar in §7.4 covers data values only (§1.3).


### 7.1 Encoding, Normalization, and Media Type


TSON is a Unicode data format. The grammar is defined in terms of Unicode character properties, not byte sequences:

| Property / Spec       | Source  | Used for                          |
|-----------------------|---------|-----------------------------------|
| XID_Start             | UAX #31 | Unquoted token start characters   |
| XID_Continue          | UAX #31 | Unquoted token continuation       |
| Nd                    | UCD     | Decimal digits in all scripts     |
| Pattern_White_Space   | UAX #31 | Whitespace / token separation     |
| Pattern_Syntax        | UAX #31 | Special tokens / syntax operators |
| NFC                   | UAX #15 | Unquoted token normalization      |

`XID_Start`, `XID_Continue`, `Nd`, `Pattern_White_Space`, and `Pattern_Syntax` are stable — the Unicode Standard guarantees that characters are never removed from these sets — and `XID_Start`/`XID_Continue` are stable under NFC normalization, so normalizing a valid token always produces a valid token. Implementations MUST support these properties for their declared Unicode version and SHOULD document which Unicode version they support.

**Two profiles: tokens and identifiers.** TSON declares two profiles per UAX #31 requirement R1, one over the *spelling* of unquoted tokens and one over *names*. The **unquoted-token profile** governs what the lexer accepts as one unquoted token — a class that carries values (`{ name: Alice }` has two unquoted tokens) as well as names:

```
Token       Start    = XID_Start ∪ Nd ∪ { - + . }
            Continue = XID_Continue ∪ { - + . }
```

The **identifier profile** governs the decoded text of a name — a field name, type name, annotation name, parameter name, or enum member — however that text was spelled, and is the subject of the identifier grammar (§7.7):

```
Identifier  Start    = XID_Start
            Continue = XID_Continue ∪ { - }
```

The identifier profile is the token profile minus the extensions the number grammar requires: `Nd`, `-`, `+` and `.` at Start exist so that a *number* can be an unquoted token, and reach names only because names and values share one lexical class; an identifier therefore never begins with a digit, a sign, or a dot. `+` is dropped from Continue as an exponent-only character; `.` is dropped from Continue and reserved as a future identifier separator. Every part of the identifier profile lies inside the token profile, so **every identifier is a well-formed unquoted token** and no name ever needs quoting to be written — which is what lets the annotation and type-annotation positions, which admit no quoted form, cost nothing. That invariant is the one to preserve if the profile is ever widened.

The three extension characters are all `Pattern_Syntax` and therefore immutable, so both profiles are frozen in their non-property parts. The property-based components grow with the Unicode version: new scripts enter `XID_Start`/`XID_Continue` and new digits enter `Nd` as they are encoded. Growth is monotone — characters that were lexer errors become token characters, and valid documents remain valid under later versions. Underscore (U+005F) is in `XID_Continue` but not `XID_Start`: it may appear within or at the end of a token (`my_type`) but cannot start one. Token-initial underscore is reserved to the format and occupied by the absent sentinel `_` (§2.9). The identifier profile deliberately does not add `_` to Start either, so `_id` and `_` are not names at any layer: `{ "_id": 1 }` is a parse error like `{ _id: 1 }` (§2.5), and a key spelled that way belongs in a map. Admitting a leading underscore would be a change to the profile for every naming position at once, and not a cheap one — the lexer takes `_` greedily as the sentinel, so it would also cost the invariant that every identifier is a well-formed unquoted token.

**Profile boundaries.** Every extension character is required by a production of the number grammar (§7.6): `Nd` for digits, `-`/`+` for signs and exponent signs, `.` for the decimal point and `.inf`/`.infinity`/`.nan`. The extension characters remain token-profile members, but their bare single-character forms are claimed by the grammar or excluded: `-` alone is the subtraction operator ([TSON-SCHEMA] §5.9), `..` is the range token (§7.2.4), and bare `+` and `.` have no role — the single-character strings are written quoted (`"-"`, `"+"`, `"."`). Content kinds the profile cannot cover totally — paths, URIs with a scheme, monetary amounts, rationals, networks, percentages, ranges, and the temporal kinds that carry a clock time (RFC 3339 `full-time` and `date-time`, and any duration written with the colon forms) — are excluded entirely, so their quoting rule is *always*, never a per-character scan (full derivation in [TSON-GUIDE]). A calendar date (`2025-03-13`) lies wholly inside the profile and is spellable bare; a time never is, the colon being the one character that ends it.

**Quoting by kind.** The profile makes the quoting decision a property of what a value *is*, not of the characters it happens to contain. A generator's decision procedure is two clauses: quote if any character falls outside the profile, and quote if the bare token would resolve to something other than the intended string (`"true"`, `"42"`, `"0x71C7…"`, §4).

**Format characters and controls.** No character with General_Category `Cf`, and no control character, is in `XID_Continue`; none may appear in an unquoted token. This includes the bidirectional formatting controls U+061C, U+202A–U+202E and U+2066–U+2069, the soft hyphen, and the word joiner, and it is the rule the security considerations of §9.4 rest on. Host-language identifier predicates frequently compute `ID_*` rather than `XID_*`, and frequently admit the identifier-ignorable characters on top; they are not substitutes for the properties named here. The two exceptions are the joining controls ZWNJ (U+200C) and ZWJ (U+200D), which **are** `XID_Continue` — UAX #31 made them default identifier characters when it withdrew its former contextual requirement, relocating the safety rule to UTS #39 — and which the token profile therefore admits. They are ordinary spelling in the scripts that use them (a ZWNJ separates the morphemes of a Persian word; both control conjunct formation in Indic scripts) and invisible only where they do no shaping work. The identifier grammar (§7.7) applies UTS #39's contextual rule for joining controls, which admits them where they have a shaping effect and refuses them where they are invisible, so `ad<ZWNJ>min` is not an identifier while `کتاب‌ها` is. The two rules land together: a token profile that admits the joiners is safe only because the identifier layer checks their context.

TSON documents are encoded in Unicode. UTF-8 is RECOMMENDED; UTF-16 and UTF-32 are permitted. Content-addressed documents MUST be UTF-8 (§2.2.1). A byte sequence that is not valid in the document's encoding is a lexer error (§8.1), reported at the byte offset of the offending sequence's first byte; a decoder MUST NOT substitute replacement characters (U+FFFD) and continue. For UTF-8, overlong encodings, encoded surrogate code points, and values above U+10FFFF are not valid sequences and are rejected on the same terms — a platform decoder's tolerance of any of them is not licence to accept them.

**Byte order mark.** A single U+FEFF at the very start of a document is an encoding artifact: decoders MUST accept it and discard it before lexing — it is not whitespace and is not part of any token. This is an encoding courtesy of this section's own, owed to the editors that still emit one, and rests on no compatibility claim (RFC 8259 §8.1, where the posture comes from, has it as a MAY). U+FEFF anywhere else outside a quoted token is an unrecognised character and a lexer error (§7.2.6) — it is `Cf`, and the format-character rule above already excludes it; within a quoted token it is ordinary content. Encoders using UTF-8 SHOULD NOT emit a byte order mark; for UTF-16 and UTF-32 the byte order mark belongs to the encoding scheme and is consumed by decoding.

TSON documents use the media type `application/tson` (intended for IANA registration). Version information is not encoded in the media type; if disambiguation is needed in HTTP contexts, implementations MAY use `application/tson; version=1`. File extensions carry the same distinction the media-type parameter does. The unversioned extension **`.tn`** makes no stability claim: it is the extension of the 2026 revision series — this document's own bundled schemas use it — and remains appropriate for any document published without a frozen-version guarantee. The extension **`.tn1`** is a positive claim of TSON version 1 stability and is reserved for the version 1 freeze: it MUST NOT be used before that release, and future major versions use correspondingly numbered extensions (`.tn2`, …). Renaming a document from `.tn` to `.tn1` at the freeze changes its identifying URI and therefore its canonical identity (§2.2.1); references pinned during the revision series do not carry over and are re-pinned against the frozen identities. Whether a TSON file is a data document or a schema document is determined by its header (§2.2), not its extension: classification requires at most two directives of lookahead and no value parsing, so streams, previews, and content sniffers can classify a document from its opening bytes.


### 7.2 The Lexer


The lexer produces a stream of tokens from the input, classifying each token by its start character:

1. **Whitespace** — Characters with the `Pattern_White_Space` property are consumed and not emitted as tokens. The set is immutable and has eleven members, which UAX #31 requirement R3a-1 divides into three groups with three treatments:
   - **Line terminators** — U+000A (LF), U+000B (VT), U+000C (FF), U+000D (CR), U+0085 (NEL), U+2028 (LINE SEPARATOR), U+2029 (PARAGRAPH SEPARATOR). A sequence of one or more is one or more end of line; each also separates tokens.
   - **Horizontal space** — U+0009 (TAB) and U+0020 (SPACE). Each separates tokens.
   - **Ignorable format controls** — U+200E (LRM) and U+200F (RLM). These are bidirectional marks, not visual whitespace, and UAX #31 requires that their insertion have no effect on meaning. They are consumed and contribute nothing — they neither separate nor join tokens — and are admitted only where a token boundary already exists: adjacent to horizontal space or a line terminator, at the start or end of a line, or between two tokens that a structural delimiter or special token already separates. A run of them standing where the characters on either side would otherwise continue a single unquoted token is a **lexer error** naming the character and its position: `[1<LRM>2]` and `ad<LRM>min` are refused rather than read as `[1, 2]` and `admin` — a document whose bytes and rendering disagree must not resolve silently. The one carve-out is the range token: `1<LRM>..` stands at a boundary, since §7.2's rule 3 terminates the token before consecutive dots regardless. Inside a quoted token an LRM or RLM is ordinary content, which is where an author corrects the plain-text display of a bidirectional name (UTS #55 §3.2).

2. **Quoted token** — `"` begins a quoted token. If the next two characters are also `"`, the lexer enters multi-line mode and emits a `multi-line-token`; otherwise single-line mode, emitting a `single-line-token` — two distinct kinds in the stream (§7.4). This is the first of the lexer's lookahead rules; §7.2.4 defines the others.

3. **Unquoted token** — A character in the unquoted start set of the profile (§7.1) begins an unquoted token; the lexer consumes characters while they match the continuation set, with one termination rule: a `.` whose immediately following character is also `.` is not consumed — the token ends before the first dot, which then begins a range token (§7.2.4). Consecutive dots never appear inside an unquoted token.

4. **Structural delimiter** — One of `{` `}` `[` `]` `:` `,` is emitted as a single-character structural delimiter token. The colon is the field separator in records and in annotation and directive arguments; the comma is the optional value separator. Parentheses `(` `)` are **not** structural delimiters — they are special tokens (§7.2.5).

5. **Absent sentinel** — The underscore `_` is emitted as a single-character absent token.

6. **Compound special token** — `=`, `!`, `.`, `-`, and `+` trigger lookahead **before** unquoted token mode or special token mode is attempted (§7.2.4).

7. **Special token** — One of the fourteen characters `!` `@` `&` `<` `>` `?` `~` `=` `|` `;` `(` `)` `^` `-` is emitted as a single-character special token. This set is closed (§7.2.5).

8. **Unrecognised character** — Any other character is a lexer error (§7.2.6).

Every input character falls into exactly one category. The lookahead rules (quotation mark, equals sign, exclamation mark, full stop, hyphen-minus, plus sign) and the boundary test for the ignorable format controls (rule 1, which looks at the characters on either side of a run of them) are the only cases where the lexer examines more than one character to determine a token.

**Token positions.** Every token carries its source position. The parser uses position adjacency to enforce no-whitespace rules: the prefix operators `!`, `@`, and `!!` MUST be adjacent to their operand. See §7.5.


#### 7.2.1 Normalization


Unquoted tokens MUST be in Unicode Normalization Form C (NFC) in the source text: an unquoted token that is not NFC-normalized is a lexer error, and encoders MUST emit unquoted tokens in NFC. The lexer never alters token text — a document's bytes are authoritative, so byte identity and semantic identity coincide for unquoted tokens, which content-hash references depend on. Quoted tokens are not subject to this requirement — they preserve their exact Unicode content.

**Identifier positions normalise before they match.** Quoted tokens that occupy identifier positions — record field names (§2.5), and any position a higher part designates as an identifier — are NFC-normalised before the decoded text is matched against the identifier grammar (§7.7) and before identity comparison, so the quoted spelling is never stricter than the unquoted one the lexer already normalises. String-typed positions are not normalised. Consequently, `"café"` (decomposed) and `"café"` (precomposed) collide as duplicate field names, while two string *values* with the same difference remain distinct strings.


#### 7.2.2 Quoted Tokens and Escape Processing


A single-line quoted token is delimited by `"` and may contain any character from U+0020 upward except the quotation mark, the backslash, and the line terminators NEL (U+0085), LINE SEPARATOR (U+2028), and PARAGRAPH SEPARATOR (U+2029); these three MAY be included via their escape sequences. A single-line token is genuinely single-line: every line terminator of §7.2 rule 1 ends it, and the three above are excluded raw for that reason and no other. A literal TAB is below U+0020 and MUST be written as `\t`. Multi-line tokens differ: they admit literal tabs (§7.2.3). The single-character escapes are:

| Escape | Code point | Description           |
|--------|------------|-----------------------|
| `\"`   | U+0022     | Quotation mark        |
| `\\`   | U+005C     | Reverse solidus       |
| `\b`   | U+0008     | Backspace             |
| `\f`   | U+000C     | Form feed             |
| `\n`   | U+000A     | Line feed             |
| `\r`   | U+000D     | Carriage return       |
| `\t`   | U+0009     | Character tabulation  |
| `\s`   | U+0020     | Space                 |

A solidus needs no escape and has none: `\/` is an invalid escape. The `\s` escape's primary use is preserving intentional trailing whitespace in multi-line tokens (§7.2.3).

**Character escapes.** A character is escaped by its scalar value in one of two spellings — four hexadecimal digits, or one to six hexadecimal digits in braces:

```
"\u" ( 4HEXDIG / "{" 1*6HEXDIG "}" )
```

with one constraint: **the value denoted MUST be a Unicode scalar value** — in range, and not a surrogate code point (U+D800–U+DFFF). `\u0041` and `\u{41}` are two spellings of one character; `\u{1F600}` names a supplementary character directly, and so does `\u{E0100}`, a variation selector an ASCII-safe generator could otherwise express only by embedding the invisible character. There are no surrogate pairs: an escape names a character or it names nothing, so `\uD83D\uDE00` is two lexer errors rather than one emoji, and a TSON string is a well-formed sequence of scalar values by construction rather than by a rule the lexer enforces. The `{` after `u` decides the spelling at the first character, so the two forms never conflict; a brace form with no digits, more than six, or an unclosed brace is a lexer error.


#### 7.2.3 Multi-line Tokens


Multi-line tokens use triple double quotation marks as delimiters. The opening delimiter is `"""` followed by optional spaces and tabs and a line terminator; the closing delimiter is `"""`, optionally followed by spaces and tabs, on its own line, preceded only by optional whitespace — trailing spaces and tabs after the closing delimiter are permitted and ignored, symmetric with the opening rule.

A single `"` or `""` inside a multi-line token is literal content; only `"""` on its own line closes the block. To include a literal `"""` sequence, escape at least one quotation mark: `\"""`. The same escape sequences apply as in single-line tokens. Unlike single-line tokens, multi-line content admits literal TAB characters, so tab-indented text can be embedded without escaping.

Multi-line tokens follow these whitespace rules:

1. The content begins on the line following the opening delimiter. Any non-whitespace characters on the same line as the opening delimiter MUST NOT appear; trailing spaces and tabs after the opening delimiter are permitted and ignored.
2. Common leading whitespace is removed. The common prefix is the longest sequence of spaces and tabs that begins every **non-blank** content line and the closing delimiter line, compared **character by character** — a tab never matches a space, and no tab width is assumed. Blank lines do not participate in the calculation. The prefix is then removed from the start of every line: removal strips the longest leading portion of the line that matches the prefix character by character, so a line shorter than the prefix, or one whose whitespace stops matching partway (a blank line is both by construction), loses only the matching portion — possibly nothing — and removal is a no-op past the point of mismatch. Prefix removal is never an error. Lines whose indentation mixes tabs and spaces inconsistently simply shorten the common prefix; this too is never an error.
3. Trailing spaces and tabs on each line are stripped. To preserve intentional trailing whitespace, use an escape at the end of the line (`\s`, `\u0020`, `\t`).
4. The line terminator before the closing delimiter is not included in the token value.
5. Escape sequences are processed after whitespace stripping.


#### 7.2.4 Compound Token Lookahead Rules


**Map arrow.** On `=` at a token boundary, the lexer checks for `>`; if present, both are consumed and emitted as the single map arrow token `=>`. Otherwise `=` is emitted as a special token.

**Directive.** On `!` at a token boundary, the lexer checks for a second `!`; if present, both are consumed and emitted as the single directive token `!!`. Otherwise `!` is emitted as a special token (the type prefix).

**Range.** On `.` at a token boundary, the lexer checks the next character: another `.` — both are consumed and emitted as the single range token `..`; a character in the continuation set — an unquoted token begins (`.5`, `.inf`, `.nan`); anything else — a lexer error: a bare `.` has no grammar role.

**Sign characters.** On `-` or `+` at a token boundary, the lexer checks the next character: a character in the continuation set — an unquoted token begins (`-42`, `+0.5`; a mid-token `-` as in `a-b` is consumed by the continuation scan, so this rule fires only at boundaries); anything else — `-` is emitted as a single-character special token (the subtraction operator, [TSON-SCHEMA] §5.9) and `+` is a lexer error: a bare `+` has no grammar role.

```
map-arrow-token = "=" ">"
directive-token = "!" "!"
range-token     = "." "."
```

The termination rule of §7.2 pairs with the range rule: `1..100` lexes as `1`, `..`, `100`, and `.5..2` as `.5`, `..`, `2`. No production of the number grammar (§7.6) contains consecutive dots, so no numeric, temporal, or version-shaped token changes its lexing. The range token has no role in data values; content containing `..` is quoted (§7.1).


#### 7.2.5 Special Tokens


The special-token set is **closed**: a character is emitted as a single-character special token if and only if it has a grammar role somewhere in the TSON series. Fourteen characters qualify, all of them `Pattern_Syntax`; since `Pattern_Syntax` is immutable, the set of characters that can serve as TSON syntax operators is stable across all Unicode versions.

Two special characters have grammar roles in data values:

```
!     — type prefix (type annotation); also first character of !! lookahead
@     — annotation prefix
```

The remaining twelve — `&` `<` `>` `?` `~` `=` `|` `;` `(` `)` `^` `-` — are reserved by the schema grammar of [TSON-SCHEMA] and have no role in data values; in a data value, each is a parse error. (`-` reaches special-token mode only through the boundary rule of §7.2.4: followed by a continuation-set character it begins an ordinary unquoted token, so negative numbers and hyphenated names are unaffected.)


#### 7.2.6 Unrecognised Characters


Any character that falls into no token-producing category is an **unrecognised character**, and its appearance outside a quoted token is a lexer error. This includes control characters, unassigned code points, currency symbols (`$` `€` `¥` …), and every `Pattern_Syntax` character outside the special-token set — among them `/` `#` `%` `*` `'` `` ` `` `\` — which are deliberately unused anywhere in the series (within quoted tokens, `\` is the escape character). A bare `+` or bare `.` that the §7.2.4 dispatch cannot classify is likewise a lexer error. Content requiring any of these — `$19.99`, `10%`, `2/3`, `/usr/bin`, `#tag`, `"+"`, `"."` — is written as a quoted token.


### 7.3 Lexical Grammar


Every token is a single character except quoted tokens, unquoted tokens, and the compound tokens (map arrow, directive).

```
token-stream  = *( ws / single-line-token / multi-line-token
                 / unquoted-token
                 / structural-delimiter / absent-token
                 / map-arrow-token / directive-token
                 / range-token / special-token )

; ── Quoted tokens (two distinct token kinds; the grammar
; discriminates them where form matters, e.g. directives) ──

single-line-token = DQUOTE *char DQUOTE
multi-line-token  = TDQUOTE ws-indent line-term
                    ml-content ws-indent TDQUOTE

TDQUOTE       = DQUOTE DQUOTE DQUOTE
line-term     = LF / CR LF / CR / NEL / LS / PS
ml-content    = *( ml-char / line-term )
ws-indent     = *( SP / HTAB )

; ── Single-line character rules ───────────────────────────

char          = unescaped
              / BSLASH ( DQUOTE / BSLASH
                       / "b" / "f" / "n" / "r" / "t" / "s"
                       / unicode-escape )

unicode-escape = "u" ( 4HEXDIG / "{" 1*6HEXDIG "}" )
                ; the value denoted MUST be a Unicode scalar
                ; value: in range and not U+D800-U+DFFF (§7.2.2);
                ; there are no surrogate pairs

unescaped     = ; U+0020 through U+10FFFF, excluding:
                ;   U+0022 (DQUOTE)
                ;   U+005C (BSLASH)
                ;   U+0085 (NEL)
                ;   U+2028 (LINE SEPARATOR)
                ;   U+2029 (PARAGRAPH SEPARATOR)

; ── Multi-line character rules ────────────────────────────

ml-char       = ml-unescaped
              / BSLASH ( DQUOTE / BSLASH
                       / "b" / "f" / "n" / "r" / "t" / "s"
                       / unicode-escape )

ml-unescaped  = ; HTAB (U+0009), and
                ; U+0020 through U+10FFFF, excluding:
                ;   U+005C (BSLASH)
                ;   U+0085 (NEL)
                ;   U+2028 (LINE SEPARATOR)
                ;   U+2029 (PARAGRAPH SEPARATOR)
                ; DQUOTE is permitted — only """ closes the block
                ; (single-line tokens do NOT admit literal HTAB)

LF            = ; U+000A  LINE FEED
CR            = ; U+000D  CARRIAGE RETURN
NEL           = ; U+0085  NEXT LINE
LS            = ; U+2028  LINE SEPARATOR
PS            = ; U+2029  PARAGRAPH SEPARATOR
LRM           = ; U+200E  LEFT-TO-RIGHT MARK
RLM           = ; U+200F  RIGHT-TO-LEFT MARK

; ── Unquoted tokens (Unicode UAX #31) ─────────────────────

unquoted-token = unquoted-start *unquoted-char
unquoted-start = XID_Start / Nd
               / "-" / "+" / "."
unquoted-char  = XID_Continue
               / "-" / "+" / "."

; ── Structural delimiters ─────────────────────────────────

structural-delimiter = "{" / "}" / "[" / "]"
                     / ":" / ","

; ── Absent sentinel ───────────────────────────────────────

absent-token = "_"

; ── Compound tokens (lookahead) ───────────────────────────

map-arrow-token    = "=" ">"
directive-token    = "!" "!"
range-token        = "." "."
                   ; unquoted tokens terminate before
                   ; consecutive dots (§7.2, rule 3)

; ── Special tokens ────────────────────────────────────────

special-token = special-char
special-char  = "!" / "@" / "&" / "<" / ">" / "?"
              / "~" / "=" / "|" / ";" / "(" / ")"
              / "^" / "-"
                ; the closed special-token set (§7.2.5).
                ; In data values: ! (type prefix), @ (annotation).
                ; The other twelve are reserved by [TSON-SCHEMA]
                ; and are parse errors in data values. "-" and
                ; "." reach the lexer's special and compound
                ; modes only via the boundary dispatch of §7.2.4.
                ; Any character matching no token rule is an
                ; unrecognised character — a lexer error (§7.2.6).

; ── Whitespace ────────────────────────────────────────────

ws  = *( horizontal-space / ws-line-term / ignorable-format )
ws1 = *ignorable-format 1*( horizontal-space / ws-line-term / ignorable-format )
    ; separation requires at least one real space or line
    ; terminator somewhere in the run; an ignorable format
    ; control on either side of it contributes nothing and
    ; never separates by itself

horizontal-space = SP / HTAB
ws-line-term     = line-term / VT / FF
    ; the seven line terminators of §7.2 rule 1; line-term
    ; alone is the set the quoted-token grammar recognises
ignorable-format = LRM / RLM
    ; U+200E, U+200F — admitted only at an existing token
    ; boundary (§7.2 rule 1); interior to a token, a lexer error
    ; Pattern_White_Space = horizontal-space / line-term /
    ;                       ignorable-format  (11 characters)

separator = ws "," ws / ws1

; ── Other terminals ───────────────────────────────────────

DQUOTE        = ; U+0022  QUOTATION MARK
BSLASH        = ; U+005C  REVERSE SOLIDUS (backslash)
SP            = ; U+0020  SPACE
HTAB          = ; U+0009  HORIZONTAL TAB
VT            = ; U+000B  LINE TABULATION
FF            = ; U+000C  FORM FEED
HEXDIG        = ; 0-9 / A-F / a-f

; ── Unicode properties (normative references) ─────────────

; XID_Start          UAX #31 — letters and letter-like numbers
; XID_Continue       UAX #31 — XID_Start + digits + combining marks + connector punctuation
; Nd                 General Category "Decimal Number"
; Pattern_White_Space  UAX #31 — immutable whitespace (11 chars)
; Pattern_Syntax       UAX #31 — immutable syntax characters
```


### 7.4 Data Grammar


The parser consumes the token stream and produces a document tree. The `document` rule dispatches on the header (§2.2); values use two rules: `scoped-value` (record field values, map entry values, array elements) and `data-value` (everywhere a value occurs). Adjacency requirements that ABNF concatenation cannot express are enforced via source-position comparison; see §7.5.

```
document        = [ id-directive ] ws ( data-doc / schema-doc )

data-doc        = [ schema-directive ws ] data-value ws
schema-doc      = meta-directive ws *( import-directive ws )
                  schema-map ws
                ; schema-map — the schema document's annotated,
                ; braced declaration map — is defined in
                ; [TSON-SCHEMA]; a Class 1 processor rejects
                ; schema documents (§1.5, §8.1).

id-directive     = "!!" "id"     ":" single-line-token
schema-directive = "!!" "schema" ":" single-line-token
meta-directive   = "!!" "meta"   ":" single-line-token
import-directive = "!!" "import" ":" single-line-token
                ; ":" MUST be adjacent to the directive name (§7.5).
                ; "!!" whose name is not followed by an adjacent ":"
                ; is a parse error (§1.3). String literals match
                ; exact characters (§7.3): directive names are
                ; case-sensitive. Any other directive name is a
                ; parse error (§3.3).

data-value      = *annotation [type-ref] core-value

type-ref        = "!" identifier
                ; identifier — an unquoted token whose text
                ; matches the identifier grammar (§7.7)

core-value      = record / map / array
                / empty-brace / absent / token

record          = "{" ws field *( separator field )
                  [ ws "," ] ws "}"
field           = field-name ws ":" ws scoped-value

map             = "{" ws map-entry
                 *( separator map-entry ) [ ws "," ] ws "}"
map-entry       = data-value ws "=>" ws scoped-value

array           = "[" ws [ scoped-value
                 *( separator scoped-value ) [ ws "," ] ] ws "]"
                ; a comma may follow a value (§2.4); a comma
                ; following nothing, or a comma, is a parse error

scoped-value    = [ schema-directive ws ] data-value

; ── Shared terminals ──────────────────────────────────────

annotation      = "@" identifier [ ":" data-value ]
token           = unquoted-token / single-line-token
                / multi-line-token
field-name      = unquoted-token / single-line-token
                ; an identifier position (§2.5): the decoded,
                ; NFC-normalised text is matched against §7.7;
                ; the multi-line form is not admitted
empty-brace     = "{" ws "}"
absent          = "_"
identifier      = ; §7.7 — matched against a token's complete
                  ; decoded text, as `number` is (§7.6); at
                  ; type-ref and annotation the spelling is an
                  ; unquoted token, at field-name either spelling
```


### 7.5 Adjacency Rules


ABNF concatenation does not express "no whitespace permitted here." The following adjacency requirements are enforced by the parser via source-position comparison. [TSON-SCHEMA] extends this table for the operators of its type-definition grammar.

| Operator | Type | Context | Rule |
|---|---|---|---|
| `!` | prefix | type annotation | MUST be adjacent to the following unquoted-token (type name) |
| `!!` | prefix | directive | MUST be adjacent to the following unquoted-token (directive name) |
| `@` | prefix | annotation | MUST be adjacent to the following unquoted-token (annotation name) |
| `:` | separator | record field | whitespace optional on both sides |
| `:` | separator | annotation value, directive argument | MUST be adjacent to the preceding name; whitespace optional after |
| (none) | trailing | annotation without value | at least one whitespace character MUST follow the annotation name |
| (none) | trailing | type annotation | at least one whitespace character MUST separate the type name from a following token; none required before a structural delimiter |
| `=>` | separator | map entry | whitespace optional (compound token from lexer) |


### 7.6 Number Grammar


The number grammar applies to the complete text of a token; it is not part of the token-stream grammar. The `number` production is the base type resolution entry (§4.3): every character it uses is in the unquoted token profile (§7.1), so a candidate token is first produced by the lexer, then matched — in full — against it; its four alternatives are pairwise disjoint. The extended forms below it are recognised only through the numeric atoms of the type vocabulary (§5.6) and, like all atom contracts, match token *content* after escape processing (§5.2): `hex-float` and `complex` are expressible unquoted, while `rational` contains `/`, which is outside the profile, so rational values are always quoted.

```
; ── Base type resolution entry (§4.3) ─────────────────────

number          = special-value / based-integer
                / float / integer

sign            = "+" / "-"

digits          = DIGIT *( ["_"] DIGIT )
                ; separator "_" only between digits
decimal-natural = "0" / ( nonzero-digit *( ["_"] DIGIT ) )
                ; no leading zeros
nonzero-digit   = %x31-39                           ; 1-9

integer         = [sign] decimal-natural

based-integer   = [sign] ( "0x" hex-digits
                         / "0o" octal-digits
                         / "0b" binary-digits )
                ; prefixes are lowercase
hex-digits      = HEXDIG *( ["_"] HEXDIG )
octal-digits    = ODIGIT *( ["_"] ODIGIT )
binary-digits   = BDIGIT *( ["_"] BDIGIT )

float           = [sign] decimal-float
decimal-float   = decimal-natural "." digits [ exponent ]
                / "." digits [ exponent ]
                / decimal-natural exponent
exponent        = ( "e" / "E" ) [sign] digits

special-value   = [sign] infinity
                / ".nan"
infinity        = ".inf" / ".infinity"

; ── Extended forms (type vocabulary, §5.6) ────────────────

rational        = [sign] decimal-natural "/" denominator
denominator     = nonzero-digit *( ["_"] DIGIT )

hex-float       = [sign] "0x" hex-digits [ "." hex-digits ]
                  hex-exponent
                / [sign] "0x" "." hex-digits hex-exponent
hex-exponent    = ( "p" / "P" ) [sign] digits

complex         = [sign] magnitude sign magnitude imag-unit
                / [sign] magnitude imag-unit
magnitude       = decimal-natural [ "." digits ] [ exponent ]
                / "." digits [ exponent ]
imag-unit       = "i" / "j"

; ── Terminals ─────────────────────────────────────────────

DIGIT           = %x30-39                           ; 0-9
ODIGIT          = %x30-37                           ; 0-7
BDIGIT          = "0" / "1"
```

String literals in this grammar match exact characters (§7): the base prefixes and the special-value names are lowercase only, while `e`/`E` and `p`/`P` are given explicitly and `HEXDIG` admits both cases.


### 7.7 Identifier Grammar


An **identifier** is a name: the decoded text of a token — after unquoting, escape processing, and normalization — occupying a naming position. Like the number grammar (§7.6), the identifier grammar applies to the complete text of a token and is not part of the token-stream grammar: the lexer produces a token, and the position that knows it holds a name then matches the token's decoded text — in full — against the production below. A name is constrained however it was spelled; which spellings a position admits is the position's own grammar rule, and a position may admit fewer spellings without admitting a different set of names.

```
identifier          = identifier-start *identifier-continue
identifier-start    = XID_Start
identifier-continue = XID_Continue / "-"
                    ; the identifier profile of §7.1; every
                    ; identifier is also a well-formed
                    ; unquoted token
```

Three rules apply on top of the production:

1. **NFC.** An identifier's text MUST be in Unicode Normalization Form C. For an unquoted spelling this is already the lexer's rule (§7.2.1); a quoted spelling at a naming position is NFC-normalised by the resolver before identity comparison (§7.2.1), and it is the normalised text that the production is matched against. Identity between identifiers is byte identity of the NFC text, and is case-sensitive.
2. **Joining controls.** ZWNJ (U+200C) and ZWJ (U+200D) are `XID_Continue` and so are admitted by the production, but only in the contexts UTS #39 §3.1.1.1 defines for joining controls — conditions A1, A2, and B on the `Joining_Type`, `Canonical_Combining_Class`, and `Indic_Syllabic_Category` of the neighbouring characters, under the two global conditions that the identifier be in NFC and be single-script (ignoring Common and Inherited). A joining control outside those contexts makes the text not an identifier. The rule admits the joiners where they have a shaping effect (Persian `کتاب‌ها`, an Indic conjunct) and refuses them where they are invisible — every position in a Latin name — and it is what makes the token profile's admission of the joiners (§7.1) safe. An implementation MUST implement all three conditions; the Arabic condition alone admits Persian and refuses Malayalam, which is the wrong line.
3. **No reserved words.** The grammar excludes nothing by name: there is no keyword list, and `true` and `false` are identifiers like any other — the two words that have a special reading under base type resolution (§4.2) are, under a schema, the members of the kernel's `boolean` enum `[true false]` ([TSON-SCHEMA] §7.4), and `null` is an ordinary string (§4.4). The one lexical reservation the format makes — the token-initial underscore, occupied by the absent sentinel (§7.1) — is not a reservation on names at all: `_` is `XID_Continue` only, so no identifier begins with it, and `_` and `_id` are not identifiers at any layer (§2.5).

A token at a naming position whose decoded text fails this grammar is a parse error at every position the data grammar marks `identifier` (§7.4 — field names, annotation names and type-annotation names) and at every naming position of the schema grammar ([TSON-SCHEMA] §12.1). Map keys are values, not names, and are never matched against it (§2.6).

The grammar is built only on properties the Unicode Standard has frozen (`XID_Start`, `XID_Continue`, NFC, and the properties the joining-control contexts read), so every implementation at every Unicode version returns the same verdict on the same text, and a content-addressed schema's validity (§2.2.1) rests on nothing that a Unicode Character Database refresh can change. The name-hygiene mechanisms of §8.2, which do depend on unstable data, are deliberately kept out of validity for that reason.


## 8. Processing Requirements


### 8.1 Errors and Reporting


Errors fall into four categories corresponding to the processing layers. The categories are defined here for the whole series; the resolver and validation categories are populated mainly by the higher parts.

- **Lexer errors** — Malformed input below the token layer: byte sequences invalid in the document's encoding (§7.1), unterminated quoted or multi-line tokens, invalid escapes, a character escape denoting no scalar value (§7.2.2), unrecognised characters, unquoted tokens that are not NFC-normalized.
- **Parser errors** — Structural mismatches: unclosed brackets, adjacency violations, unexpected tokens, missing separators, `!!` without an adjacent colon form, a directive name outside the closed positional set or outside its placement (§3.3), a comma that follows nothing or follows a comma (§2.4), a token at a field-name, annotation-name or type-annotation-name position whose decoded text is not an identifier (§2.5, §7.7).
- **Resolver errors** — Reference and resolution failures. At the data-format layer: an absent sentinel in map key position; a duplicate field name in a record (§2.5) or a duplicate key in a map (§2.6) under the textual and decoded-value layers of key identity — one category for one rule, whichever of those two layers detects a given pair; a duplicate that only a *declared* key type relates is the schema layer's and is a Class 2 validation error ([TSON-SCHEMA] §7.7); a built-in type annotation on a container value (§5.1); a token that a built-in atom's parsing contract rejects (§5.2) — the structural parser has already accepted the document before an atom contract is consulted, so contract failures resolve, they do not parse. [TSON-SCHEMA] adds unresolved type names, schema resolution failures, and schema compilation failures: every error that makes a schema fail to load or ingest — incoherent constraint values, invalid defaults, refuted assertions, failed ingest checks — is a resolver error, however value-like the violated rule, because it is detected while resolving the schema. Validation errors are reserved for data checked against a successfully loaded schema.
- **Validation errors** — Type and constraint violations. At the data-format layer: range violations by the numeric atoms and CIDR prefix lengths (§5). [TSON-SCHEMA] generalises validation to author-declared constraints.

**Canonical phrasing.** Normative rules throughout this series refer to errors using one of four canonical phrasings, each mapping unambiguously to a category: "is a lexer error", "is a parse error", "is a resolver error", "is a validation error". Where conformance language appears without an explicit category, the layer that detects the violation determines the category. **A conforming TSON processor has one severity**: every diagnostic this series requires is an error in one of these categories, and no rule anywhere in the series asks for a warning. An implementation MAY emit advisory notices of its own, but nothing normative is satisfied, relaxed, or deferred by one.

**Refusals are a fifth outcome, not a verdict.** Two kinds of check refuse a document without making it invalid: the name-hygiene mechanisms of §8.2, and the resource limits of §9.1. A refusal means *this processor declined* — under its stated policy, data version and limits — and never that the document is wrong: the same document may be well-formed, valid, and accepted in full by the next processor along, and a conforming processor may legitimately not refuse at all. A refusal therefore MUST be distinguishable from the four categories above and MUST name the rule or limit that refused, since the three hygiene mechanisms and each limit want different remedies and the rule is what a consumer routes on. It is reported in the *same* report as the four categories: a consumer reads a report to repair a document, and a repair channel split in two is repaired in two passes, so nothing here asks for a refusal to travel apart from the errors it arrives with. What the report carries about the policy is stated in §8.2 and §9.1. The distinction is what lets two conforming processors legitimately disagree on a refusal while never disagreeing on validity.

Implementations MUST include source position (line, column, and byte offset) in all error reports, SHOULD include expected-vs-found information for token and structural mismatches, and SHOULD continue processing after an error to report multiple issues in a single pass.

**Schema-document diagnostics.** A Class 1 processor encountering `!!meta` in the header MUST report the document as a TSON schema document that this processor does not support (§1.5) — a categorized diagnostic, not a generic unexpected-token error.


### 8.2 Name Hygiene


The identifier grammar (§7.7) decides which texts are names; it does not decide whether two names that are both well-formed can be told apart by a reader. Two visually identical names — Latin `admin` and a Cyrillic-`а` `аdmin` — are two identifiers, and NFC does not relate them. This section defines the **name-hygiene** mechanisms that address that surface. They are a second layer with a different character from validity, and the difference is deliberate:

- **They are not validity.** Each depends on Unicode data that the Unicode Consortium declines to freeze — `confusables.txt`, `IdentifierStatus.txt`, and the script-based restriction levels of UTS #39 — so a verdict can change under a routine Unicode Character Database refresh. A content-addressed document (§2.2.1) must mean the same thing forever, so nothing here may decide whether a document is valid. A document that fails a check is **refused by this processor**, reported as §8.1's fifth outcome.
- **The policy and the data version are properties of the report, not of the refusal.** The Unicode data version is a property of the processor — the tables compiled into it — and the policy is a property of the deployment; neither belongs to the problem that was found, and twenty refusals in one document cannot carry twenty different versions. A conforming processor MUST make available, with any report that contains a refusal, the **UCD version** of the data files it judged by and the **identifier policy** and **token policy** (below) it judged under, and SHOULD make both available independently of any report, so that a sender can learn what will be accepted *before* writing rather than one round trip after. The version names the tables, which is what explains a disagreement between two processors at different releases; the policy names the level, unit and permitted scripts, which is what explains the far commoner disagreement between two processors at the same release. The version is the UCD's — `confusables.txt` and `IdentifierStatus.txt` are published as part of the Unicode Character Database and carry its version, not UTS #39's own revision number, which identifies the prose describing the mechanisms and is stable across exactly the refreshes this section exists to make visible.
- **They are implemented everywhere and enforced by default.** A conforming processor MUST implement all three mechanisms below, MUST enforce the first two by default, and SHOULD default the third to the level named below.
- **Relaxation is a code decision.** A processor MUST allow a deployment to relax any of the three through the implementation's own configuration and MUST NOT allow that relaxation to be silent: a security policy read from the environment is ambient authority, invisible at the call site and absent from review; an opt-out expressed in code is greppable, attributable, and scoped to the processor instance that holds it. An implementation MUST NOT offer a report-but-accept mode for the levels of mechanism 3 — the levels are the severity, and each is a conforming position.
- **The policy is not a property of a schema, and no schema carries one.** Two reasons, neither of them orthogonality. *Self-certification:* if a schema declared its own strictness, the artifact being checked would choose the check, and a homograph-laden schema would declare the level that admits it — a policy the subject selects is a preference. *Immutability:* a published schema is immutable and hash-pinned ([TSON-SCHEMA] §3.5, §2.2.1) while strictness must move — `confusables.txt` updates, threat models change — and raising a policy would mint a new identity that every document pinning the old one would never see. A third reason is mechanism 1's own: skeleton distinctness does not compose across `!!import` (§8.3), so the policy is a property of the merged namespace at the importing site, which no one schema is in a position to declare. Where a policy lives — a deployment's own configuration, or an artifact of a kind this series does not yet define — it is named at the call site and never resolved by identity.
- **The specification pins no Unicode version.** Pinning one would freeze the format to a UCD release and make every later script a breaking change.

**Names and scopes.** Mechanisms 2 and 3 are **per-name**: each judges one identifier on its own and needs no scope, so they reach every identifier position — field names, annotation names, type-annotation names, and every naming position of the schema grammar — in one walk. Mechanism 1 is a relation over a set and operates over **named scopes** — the closed sets of names the series already defines. At this layer there is one: the field names of one record. [TSON-SCHEMA] §11.4 adds the schema-layer scopes (the members of one enum, the field names of one record definition, the declared names of one schema, and the merged namespace at `!!import`). A data document under a schema needs no scope of its own — a data field name is valid only if it matches a declared one, so it inherits the declaration's verdict — but the record scope applies to schemaless data, where no declaration stands behind a field name. Because a field name is an identifier at every layer (§2.5), a schemaless record's field names meet all three mechanisms exactly as a declared name does; there is one walk, and no position is policed differently by conformance class. Profile extension characters (`-`, §7.1) are not `XID_Continue`, carry no `Identifier_Status`, and do not participate in any mechanism below.

**Mechanism 1 — skeleton distinctness (default on).** No two names in one scope may have equal UTS #39 `skeleton()` mappings. This is a relation over a set, so it fires only on a colliding pair and never refuses a lone name: whole-script confusables (Latin `pass` beside Cyrillic `раѕѕ`, `aec` / `аес`), the `l`/`I`, `O`/`0` and `rn`/`m` pairs, and every homograph pair are caught, and a lone name, however it is spelled, is not. The data is `confusables.txt`. A pair that isolates this mechanism has to be two names each single-script — a within-word mixed-script homograph such as `аdmin` is refused by mechanism 3 before this one has a pair to compare, so a test written the obvious way passes for the wrong reason. It is the mechanism that cannot be validity for a second reason: it does not compose — two independently published schemas, each fine alone, can collide when one imports the other ([TSON-SCHEMA] §11.4) — and it has pure-ASCII false positives (`comer` / `corner` share a skeleton through `m → rn`), which is a sound basis for a default and an unsound one for a rule.

**Mechanism 2 — `Identifier_Status` (default on).** Every character of a name that is `XID_Continue` MUST be `Identifier_Status=Allowed` (UTS #39 §3.1). This removes obsolete, technical, and limited-use characters from names — a per-character rule with no cross-script judgement in it, so it refuses no ordinary compound. The data is `IdentifierStatus.txt`. It applies to names only: an unquoted *value* in a historic script remains legal.

**Mechanism 3 — restriction level (default Highly Restrictive, whole name).** A name MUST satisfy one of the six restriction levels of UTS #39 §5.2, applied to a **unit** that is either the whole name or each `_`/`-` delimited segment of it. The level is a UTS #39 name, so two implementations agree on it without reading this document; the unit is this series' one refinement. The RECOMMENDED default is Highly Restrictive over the whole name. The relaxation to reach for first is the **unit**, not the level: applied per segment, Highly Restrictive still refuses every within-word homograph (`аdmin`, `id_аdmin`) while admitting the compounds that mix a Latin abbreviation with a name in another script (`id_пользователя`, `url_адрес`, `alpha_α`) — the common case for an author working outside Latin script. A deployment that knows what it is MAY instead name an additional permitted script set (`Latin + Cyrillic`), the mechanism UTS #39 itself uses for its augmented Latin-plus-East-Asian sets. The two loosest levels differ and MUST be offered apart: Minimally Restrictive drops the script rule and keeps the identifier profile; Unrestricted drops the profile too, taking mechanism 2 with it, and is a diagnostic setting rather than a deployment one. One asymmetry is inherited from UTS #39 and worth stating so an author does not infer it from a rejection: the augmented sets admit `日本語id` without a separator, while a Cyrillic or Greek compound needs one (`пользователь_id`) — Han, Kana, and Hangul share no confusables with Latin, and Cyrillic and Greek are full of them.

**The two policies.** A processor's configuration for this section has two parts, and the series names them so that two implementations reporting them (above) agree on what they are called. The **identifier policy** is mechanisms 1 and 2, and the level and unit of mechanism 3 — with any additional permitted script set — applied at identifier positions (§7.7). The **token policy** is a restriction level applied to every token off the stream. Everything above constrains names; a value is data and may legitimately be anything, so the token policy's default is Unrestricted and no scan runs — an implementation SHOULD nevertheless let a deployment that renders or matches untrusted values set one. Because a token check runs before anything knows which tokens are names, a token policy stricter than the identifier policy subsumes it — a name is a token — and an implementation's documentation SHOULD say so. The per-segment unit belongs to the identifier policy only: `_` and `-` are word separators by convention in an identifier and ordinary characters in a value.

**On detection.** A refused pair is reported at the second occurrence's position, in the manner of §2.6's duplicate-key diagnostic — a confusable pair is that defect in disguise. Every rule in this section is decidable from the document plus a fixed table, so a conformance suite MAY carry vectors for it, labelled with the UCD version they were computed against; a processor carrying a different version may legitimately skip such a vector.


### 8.3 Conformance Summary of the Two Layers


| Layer | Rule | Data | Stable across Unicode versions | Composes across `!!import` | Status |
|---|---|---|---|---|---|
| 1 | identifier grammar (§7.7) | `XID_*`, NFC, joining-control contexts | yes | yes | **MUST** — validity |
| 2 | `Identifier_Status=Allowed` | `IdentifierStatus.txt` | no | yes | MUST implement, MUST default on — policy |
| 2 | skeleton distinctness | `confusables.txt` | no | **no** | MUST implement, MUST default on — policy |
| 2 | restriction level | `Script` | no | yes | MUST implement, SHOULD default Highly Restrictive — policy |


## 9. Security Considerations


### 9.1 Denial of Service


A document can ask a processor for more than it should spend: nesting that exhausts a stack, tokens and literals that exhaust memory, containers whose set operations (§2.5's unique field names, §2.6's key identity, [TSON-SCHEMA] §7.5's element uniqueness) are superlinear in their own size, a total that no per-container bound implies, and — specific to this format — scope pushes that make a processor fetch a schema per value ([TSON-SCHEMA] §7.8). A conforming processor bounds all of these under one **limits policy**, on the terms this section states once rather than per limit.

**The terms.** Every limit below has a default. A processor MUST enforce each limit at its default or at a configured value; the limit MUST be configurable or, where configuration is impractical, the implementation MUST document its enforced value. A processor that exceeds a limit MUST report a clear refusal naming the limit and the configured threshold rather than failing with an out-of-memory condition, a stack overflow, or any other host-language fault — and the refusal is counted where the resource is *counted*, not where it is spent: nesting depth is checked as containers open in the token stream, before any reader descends. A limit refusal is §8.1's fifth outcome: the document may be well-formed, valid and accepted in full by the next processor along, so a refusal is not a verdict, MUST be distinguishable from the four categories, and MUST NOT be reported as a validity error — the fallback an unspecified category invites, and the one a sender must not receive about a document that is not wrong. The limits policy is reported beside the identifier and token policies of §8.2, on the same terms: with any report that carries a refusal, and SHOULD be reachable with no document in hand, so that a generator can emit a document that fits. The two policies stay apart — what a processor will *read* and what it will *admit as a name* are different questions, and a deployment that has changed one has said nothing about the other — but one report carries both.

**The limits and their defaults.** A default is a portability claim: a document that fits the tightest limit in common use fits every processor above it, where a generous default would make "a conforming document" a property of whoever received it. The defaults are therefore set at the tight end, and a deployment raises them.

| Limit | Applies to | Default |
|---|---|---|
| nesting depth | containers open at once | 64 |
| token length | one token's decoded text, in code points | 1,048,576 |
| decoded text length | one value's text after escape processing, in code points | 1,048,576 |
| numeric literal length | digits in one numeric token, annotated or not | 4,096 |
| decoded binary size | one `!bytes` value's octets (§5.3) | 16,777,216 |
| document size | the document's bytes | 16,777,216 |
| elements | one array or set | 1,048,576 |
| entries | one map | 1,048,576 |
| fields | one record | 65,536 |
| annotations | on one value (§3.1 lets a name repeat any number of times) | 64 |
| total values | all values in one document, containers and scalars alike | 16,777,216 |
| foreign schemas | distinct schemas a document's scope pushes may load ([TSON-SCHEMA] §7.8) | 16 |

Three of these want stating outright. **The total is not bounded by the parts:** ten thousand arrays of ten thousand elements sits inside every per-container limit and is 10⁸ values, so the aggregate counter is a separate mechanism from the per-position check, and a processor that bounds depth and container size has bounded neither the work nor the allocation. **Decoded sizes are bounded separately from encoded ones:** a token-length limit bounds the input, and the decoded octets or text may be larger or smaller than it; a limit on the input alone does not bound allocation. **The numeric-literal limit applies to annotated numeric tokens (`!number`, `!rational`) exactly as to unannotated ones.**

**Schemas.** This section speaks of documents. A schema is untrusted input too wherever it is accepted over the wire or reached through `!!import`, and its own limits — the import closure, the entries in one schema, reference and supertype chain lengths, template materialisation depth — are stated in [TSON-SCHEMA] §11.5 on the same terms; the document-side counters above apply to a schema document as a document.

**Regular expressions** are not a limit. The `regex` atom is pinned to RFC 9485 I-Regexp ([TSON-SCHEMA] §9), a language chosen so that matching is linear-time by construction; the pattern-complexity vector every other format warns about is closed here by the choice of dialect rather than by a bound.


### 9.2 Absence of Type Guarantees


TSON documents processed at the data-format layer carry no structural type guarantees — base type resolution (§4) and the built-in vocabulary (§5) check token formats and numeric ranges only, not field presence, container shapes, or cross-field constraints. Applications processing untrusted TSON input SHOULD validate against a schema ([TSON-SCHEMA]) before use and SHOULD NOT treat built-in annotations as a substitute for schema validation.


### 9.3 Directive Security


Directives are a control channel that affects interpretation. The directive name set is closed and positional (§3.3): there is no unknown-directive category, and therefore no channel for carrying unprocessed configuration through a conforming parser. Parsing a TSON document performs no I/O: directive arguments are references that a data-format processor never dereferences (§3.3), so a document's structural meaning is fully determined by its bytes. Dereferencing is defined by [TSON-SCHEMA] and performed under application policy. Applications processing untrusted TSON input SHOULD restrict which schema bindings are honoured when handing documents to Class 2 processors.


### 9.4 Confusable Characters


Unicode identifiers introduce visually confusable names — Latin `a` (U+0061) and Cyrillic `а` (U+0430) are different characters and different identifiers, and NFC normalization does not address this. The series answers the surface in two layers: the identifier grammar (§7.1, §7.7) excludes every format and control character from names and constrains the joining controls by context, and the name-hygiene policy (§8.2) — skeleton distinctness within each named scope, `Identifier_Status`, and a restriction level — is implemented by every conforming processor and enforced by default. Values are not names and carry no such default; a service that renders or matches untrusted values applies a token-level restriction level (§8.2) knowingly.


### 9.5 Bidirectional Formatting Characters


The bidirectional formatting characters need no rule of their own. The embedding, override, and isolate controls (U+061C, U+202A–U+202E, U+2066–U+2069) are `Cf` and outside every profile, so outside a quoted token each is a lexer error (§7.1, §7.2.6). The two marks in `Pattern_White_Space`, U+200E (LRM) and U+200F (RLM), are ignorable format controls under UAX #31 requirement R3a-1 and are treated as such (§7.2 rule 1): admitted where a token boundary already exists, where their insertion cannot change meaning, and a lexer error where they would stand inside a token — so `[1<LRM>2]` cannot silently read as two elements. Within quoted tokens all of them are ordinary content, which is where an author corrects a bidirectional name's plain-text display (UTS #55 §3.2).


## 10. References


### 10.1 Normative References


| Reference | Title | URL |
|-----------|-------|-----|
| RFC 2119 | Key words for use in RFCs to Indicate Requirement Levels | https://www.rfc-editor.org/rfc/rfc2119 |
| RFC 5234 | Augmented BNF for Syntax Specifications (ABNF) | https://www.rfc-editor.org/rfc/rfc5234 |
| RFC 3339 | Date and Time on the Internet: Timestamps | https://www.rfc-editor.org/rfc/rfc3339 |
| RFC 3986 | Uniform Resource Identifier (URI): Generic Syntax | https://www.rfc-editor.org/rfc/rfc3986 |
| RFC 4291 | IP Version 6 Addressing Architecture | https://www.rfc-editor.org/rfc/rfc4291 |
| RFC 4632 | Classless Inter-domain Routing (CIDR) | https://www.rfc-editor.org/rfc/rfc4632 |
| RFC 4648 | The Base16, Base32, and Base64 Data Encodings | https://www.rfc-editor.org/rfc/rfc4648 |
| RFC 9485 | I-Regexp: An Interoperable Regular Expression Format | https://www.rfc-editor.org/rfc/rfc9485 |
| RFC 9542 | IANA Considerations and IETF Protocol and Documentation Usage for IEEE 802 Parameters (EUI-48) | https://www.rfc-editor.org/rfc/rfc9542 |
| RFC 9562 | Universally Unique IDentifiers (UUIDs) | https://www.rfc-editor.org/rfc/rfc9562 |
| IEEE 754-2019 | Standard for Floating-Point Arithmetic | https://ieeexplore.ieee.org/document/8766229 |
| UAX #15 | Unicode Normalization Forms (NFC) | https://www.unicode.org/reports/tr15/ |
| UAX #31 | Unicode Identifier and Pattern Syntax | https://www.unicode.org/reports/tr31/ |
| UTS #39 | Unicode Security Mechanisms (joining-control contexts, §7.7; name hygiene, §8.2) | https://www.unicode.org/reports/tr39/ |
| UTS #55 | Unicode Source Code Handling | https://www.unicode.org/reports/tr55/ |
| UCD | Unicode Character Database (`confusables.txt`, `IdentifierStatus.txt`, `Scripts.txt`, `DerivedCoreProperties.txt`; the version a processor reports under §8.2) | https://www.unicode.org/ucd/ |


### 10.2 Series References


| Reference | Title | URL |
|-----------|-------|-----|
| TSON-SCHEMA | TSON Part 2: Type System and Schema | https://tson.io/2026/35/tson-part2-schema |
| TSON-GUIDE | TSON Developer Guide (non-normative) | https://tson.io/2026/35/tson-guide |


### 10.3 Informative References


| Reference | Title | URL |
|-----------|-------|-----|
| RFC 8259 | The JavaScript Object Notation (JSON) Data Interchange Format (the notation TSON resembles; not a subset relation, §1.1, §4.1) | https://www.rfc-editor.org/rfc/rfc8259 |
| RFC 8820 | URI Design and Ownership | https://www.rfc-editor.org/rfc/rfc8820 |
| ISO 8601-1:2019 | Date and time — Representations for information interchange (the informative source behind RFC 3339 Appendix A's duration grammar, §5.4) | https://www.iso.org/standard/70907.html |
| ISO/IEC 11404:2007 | General-Purpose Datatypes (GPD) | https://www.iso.org/standard/39479.html |


## Authors


- David Ryan
