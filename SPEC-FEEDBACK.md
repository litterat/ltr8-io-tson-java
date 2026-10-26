# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation. See `CLAUDE.md`
for why this file exists and when to add to it. Spec quotes below are from 2026 Revision 38 — Part 1 and Part 2,
the working copies in `spec/`, which publish at https://tson.io/raw/2026/38/tson-part1-data.md and
https://tson.io/raw/2026/38/tson-part2-schema.md — unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a suggested
resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a revision
closes.** It is an input to the next revision's adjudication, so its numbering is the numbering that revision's
change log will answer against — a stable index of the open set, not an archive of everything ever raised.

**Revision 38 closed every entry open against Revision 37**, and the entries below are open against it, found by
verifying the adjudicated text against each closed entry before its publication. The closed entries are gone: the
spec now carries their rules — the network library `net.tn` with `hostname`, `host` and `media_type`; refused and
undetermined as two outcomes beside the four categories; an unknown schemaless annotation as a resolver error; an
identity's host as a `host` value; the `type` kind; `record.name_type` and `enum_type.name_type`; a record's fields
as a map; the qualified name and `ref<T>`; `type_definition` gone and `type_chain` in its place; the abstract
kernel entries; record templates as `record` with `parameters`, the three places a parameter stands, an application
nothing declares as its expansion, and the selector rules; `kind_type`; `namespace`; and the import level rule.
**This file is the as-built record**, not a pointer to one: where an entry proposes a design this implementation
has built, the entry states the design, what is running, and what is not, so that a reviewer editing the spec needs
nothing beside it. **Where the evidence is a consumer of this library rather than this library** — the HTTP layer
in `ltr8-io-tson-java-http` is one, and this register is the collection point for all of it — the entry says so and
states what is running there on the same terms.

**Part 3 is not in this register.** [TSON-JSON] is an early draft this implementation exists to validate, and
`spec/tson-part3-json.md` is edited **directly** as findings arise — so a Part 3 finding becomes a spec change in
the same session, with git history as its record, rather than an entry waiting for adjudication. **The numbers are
not reused and the gaps are left** — the register renumbers only when a revision closes, so that a citation written
against the open set stays valid until then. What stays here is Parts 1 and 2, whose current revision is published
and whose changes this implementation proposes rather than makes. An entry spanning both stays, and says which half
is which.

**Cite the spec, not the argument that got it there:** `design/` and the Javadoc name the section that requires a
behaviour, and a `SPEC-FEEDBACK.md #N` citation is for an entry below, where there is no section to point at yet.
When an entry closes, its citations become spec citations and the entry is deleted — nothing here is an archive.

---

## 1. [TSON-SCHEMA] §5.5 has no clause for `hostname`, `host` or `media_type`

**Section:** [TSON-SCHEMA] §5.5 ("Facets with a stated meaning"), §5.7 (the permission and member-set facet kinds),
§13.1 (the RFC 3492, RFC 3987, RFC 5890 and RFC 6838 rows); [TSON-DATA] §5.5 (two citations of "[TSON-SCHEMA] §5.5,
§9").

**Kind:** underspecification — dangling citations, and three facets whose meaning only an artifact's `@doc` states.

**The problem.** The change log lists, for §5.5, "three clauses for `hostname`, `host` and `media_type`'s value
spaces". The text has none: §5.5's list runs `bytes`, `number`, text, URIs and IRIs, `time` and `datetime`,
`duration` and `period`, `precision`, and the network families' `within`/`excluding`, and `hostname` appears in
Part 2 only in §5.7's facet lists, §9's inventory and §13.1. What points at the missing clauses:

- [TSON-DATA] §5.5 sends a reader there twice: "Under a schema the same type carries an `allow_idn` selector …
  ([TSON-SCHEMA] §5.5, §9)", and "a schema position that takes parameters declares its own instance ([TSON-SCHEMA]
  §5.5, §9)".
- §13.1 cites §5.5 for RFC 3492 ("the A-label ↔ U-label mapping, `hostname_type`, §5.5"), RFC 3987 ("`iri_type`,
  `host_type`'s `ihost`, §5.5"), RFC 5890 ("`hostname_type`, §5.5") and RFC 6838 ("`media_type_type`, §5.5").
- `allow_idn` is defined only by [TSON-DATA] §5.5's one sentence ("whose `false` admits only a name with no
  internationalized label in either spelling"); Part 2 names it only as a permission in §5.7.
- What `allow_parameters: true` admits, and what `types` and `suffixes` admit, is stated only in `meta.tn`'s `@doc`
  on `media_type_type`: "`types` lists the top-level types admitted. `suffixes` lists RFC 6839 structured suffixes:
  a suffix admits a subtype ending `+` and the suffix, and the subtype that is the suffix itself." The spec text
  says of the two lists only that they are member sets that "may shrink to a subset, never grow or replace,
  compared by the family's own value identity" (§5.7).
- That comparison is open. `types` and `suffixes` are typed `[text]`, whose `normalization` is `NONE`, so as text
  values `JSON` and `json` are two; but [TSON-DATA] §5.5 says a media type's "type, subtype and parameter names
  compare without ASCII case". Whether `suffixes: [JSON]` admits `application/ld+json`, and whether `types: [Text]`
  refines `types: [text image]`, turns on which identity "the family's own" is — the list's element type's, or the
  media type's.

**Interpretation chosen.** The `@doc`'s rules, and the media type's own identity for both lists. Each `types` and
`suffixes` entry compares without ASCII case, at reading — a value's type, or its subtype, against the list — and
at refinement, where the subset check folds ASCII case; a suffix `s` admits a subtype ending `+s` and the subtype
`s` itself; each entry must be an RFC 6838 `restricted-name`, a suffix holding no `+`, and an empty list, which
admits nothing, is refused at load. `allow_parameters` is a permission, `false` by default; withheld, a value
carrying a parameter is a validation error. `allow_idn: false` admits only a name none of whose labels is a U-label
or an A-label.

**Suggested resolution.** Add three bullets to §5.5's list, after the URI bullet:

- "**`hostname`** is a domain name ([TSON-DATA] §5.5). The value is the name: the U-label and A-label forms of one
  name are one value, ASCII case is lexical, and the canonical text is the lowercase U-label form. `allow_idn` is a
  permission (§5.7); withdrawn, it admits only a name with no internationalized label in either spelling."
- "**`host`** is a host name or an IP address, never both ([TSON-DATA] §5.5). A name compares as a `hostname` value
  and an address numerically, and an IPv4 address never equals an IPv6 one. Brackets around an IPv6 address are a
  spelling; the canonical text is the member's, unbracketed. The type has no facets."
- "**`media_type`** is a type, a subtype and a map of parameters ([TSON-DATA] §5.5). `allow_parameters` is a
  permission, withheld by default; withheld, a value with a parameter is outside the type. `types` lists the
  admitted top-level types, and `suffixes` RFC 6839 structured-syntax suffixes, a suffix `s` admitting a subtype
  ending `+s` and the subtype `s` itself. Both lists hold RFC 6838 names and compare without ASCII case, as the
  type and subtype do, so `suffixes: [JSON]` admits `application/ld+json`, and a refinement's list is a subset
  under that comparison. The canonical text is lowercase, with `charset`'s value folded and parameters sorted by
  name."

With them, [TSON-DATA] §5.5's two citations and §13.1's four rows resolve as written.

**Status against Revision 38:** open. Running as described: the media-type reader matches each `types` and
`suffixes` entry, lowercased, against the value's lowercased type and subtype, the refinement check compares the
lists ignoring ASCII case, and the coherence check refuses an empty list, a name that is not RFC 6838's and a
suffix holding `+`. `meta.tn`'s `@doc` states the facet rules.

---

## 2. "Refused" is a term now: [TSON-DATA] §8.1's validity claim needs the Unicode version, and the word needs a sweep

**Section:** [TSON-DATA] §8.1 ("Validity is a property of the bytes and the schema, the same at every processor";
"never disagreeing on validity"), §7.7 ("A processor on an earlier version may refuse a name a later one accepts"),
§2.2.1 ("so an IPvFuture literal and a zone identifier are refused"; "a processor on an earlier Unicode version may
refuse an identity whose host uses a character it does not have"), §8.3's table, §1.5 (the name-hygiene bullet).

**Kind:** internal inconsistency.

**The problem.** Two things moved at once. §8.1 now defines **Refused** as an outcome beside the four categories:
"*this processor declined the document under its own configuration* … never a finding about validity". And §7.1,
§7.7 and §2.2.1 now say, correctly, that validity grows with the Unicode version: a name in a newly encoded script
is not an identifier at a processor on an earlier version. The sentences that join the two disagree:

- §8.1 says validity is "the same at every processor" and closes with two conforming processors "never disagreeing
  on validity". A processor on Unicode 15.1 and one on 16.0 disagree on the validity of a name in a script 16.0
  added; both conform, and the disagreement is about validity — the earlier one reports a lexer error.
- §7.7 and §2.2.1 say that processor "may refuse" the name or the identity. Under §8.1's term that makes it a
  refusal — distinguishable from the categories, never a finding about validity — which neither section means: §7.1
  makes a character outside `XID_Start`/`XID_Continue` at the declared version a lexer error. §2.2.1's "an
  IPvFuture literal and a zone identifier are refused" has the same problem with no Unicode version in it: both are
  outside the `host` grammar, and "A token that does not match the named format is a resolver error" (§5.5).
- §8.3's table gives the identifier grammar "Stable across Unicode versions: yes". It is monotone, which §7.7 now
  says.
- §1.5's hygiene bullet reports a refusal "never as a verdict on the document". §8.1 now makes a refusal "a certain
  rejection *here*" that settles the acceptance summary; what a refusal never is, is a finding about validity.
- Part 1 uses "refuse", "refuses" and "refused" some twenty more times for a contract failure that is a category
  error: `!boolean` "refuses any other as the enum-member violation it is", "*uppercase beyond ASCII is refused*",
  "a repeated name is refused" and "`!ipv6` itself still refuses it" (§5.5), a leap second "is refused" (§5.4), and
  the joining-control rule "refuses them where they are invisible" (§7.1, §7.7). Before §8.1 gave the word a
  meaning that was harmless; now a reader cannot tell which outcome a sentence means.

**Interpretation chosen.** Validity is monotone in the Unicode version and otherwise the same at every processor. A
character outside the declared version's `XID_Start`/`XID_Continue` in an unquoted token is a lexer error (§7.1);
an identity whose host carries a zone identifier, an IPvFuture literal, or a character this processor's IDNA2008
data does not have is a resolver error; each diagnostic names the Unicode version it was judged at. "Refused" in
this implementation means §8.1's outcome and nothing else: the name-hygiene codes, the limit code, and the codes
for a schema this deployment will not supply.

**Suggested resolution.**

- §8.1: "Validity is a property of the bytes and the schema, the same at every processor whose Unicode version
  holds the characters the text uses (§7.1, §7.7)", and the closing sentence "… while never disagreeing on validity
  at a Unicode version both support."
- §7.7: "A processor on an earlier version rejects such a name as no identifier, and its report names the version
  it validated against (§7.1)." §2.2.1: "so an IPvFuture literal and a zone identifier are resolver errors", and "a
  processor on an earlier Unicode version rejects, as a resolver error, an identity whose host uses a character it
  does not have".
- §8.3: layer 1's "Stable across Unicode versions" reads "monotone — grows, never shrinks".
- §1.5: "… distinguishable from them, and never as a finding about validity".
- Sweep Part 1's "refuse", "refuses" and "refused" where a category error is meant, to "rejects" or "is a … error",
  keeping the word for §8.1's outcome and the sections that report one (§8.2, §9.1, [TSON-SCHEMA] §10.1 and §11.2).

**Status against Revision 38:** open; wording only. The diagnostics are as described: `Xid.UNICODE_VERSION` (16.0)
is the version identifier properties are checked against and is named in the report.

---

## 3. [TSON-DATA] §7.7 calls the joining-control contexts' properties stabilised

**Section:** [TSON-DATA] §7.7 ("The grammar is built only on properties the Unicode Standard has stabilised
(`XID_Start`, `XID_Continue`, NFC, and the properties the joining-control contexts read) — properties a character
never leaves"), §8.3's table (layer 1, data "`XID_*`, NFC, joining-control contexts").

**Kind:** overclaim.

**The problem.** §7.7's rule 2 admits ZWNJ and ZWJ only in UTS #39 §3.1.1.1's contexts A1, A2 and B, "on the
`Joining_Type`, `Canonical_Combining_Class`, and `Indic_Syllabic_Category` of the neighbouring characters", under a
single-script condition. Unicode's stability policy covers `XID_Start` and `XID_Continue` (no character leaves
them), normalization (NFC) and `Canonical_Combining_Class`. It does not cover `Joining_Type`,
`Indic_Syllabic_Category`, `Script`, which the single-script condition reads, or `General_Category`, which the
conjunct contexts' letter and mark classes read. A revision of any of them for a character a name already uses can
take a joiner out of its context, so the claim that a valid text's validity "can be lost to no Unicode Character
Database refresh" does not hold for a name with a joiner in it. The joiner contexts are the part of the grammar
that guarantee rests on least.

**Interpretation chosen.** The contexts as UTS #39 §3.1.1.1 states them, at the declared version (Unicode 16.0). A1
reads `Joining_Type` — left- or dual-joining before, right- or dual-joining after, transparent characters skipped,
transparency read from `General_Category` with the explicit exceptions `ArabicShaping.txt` lists; A2 and B read
`Canonical_Combining_Class` (a virama, class 9, and the non-zero marks around it) and `General_Category` (the
letter before, the letter after for A2), and B reads `Indic_Syllabic_Category=Vowel_Dependent` after the joiner;
each matched span is single-script by `Script`, Common and Inherited ignored. A host name's U-label is judged by
RFC 5892's CONTEXTJ rules over `Canonical_Combining_Class` and `Joining_Type` — which [TSON-DATA] §2.2.1 already
calls "computed per Unicode version". The report names the version; nothing claims stability beyond it.

**Suggested resolution.** Replace the sentence's opening with: "The grammar is built on `XID_Start`, `XID_Continue`
and NFC, which the Unicode Standard has stabilised — properties a character never leaves, though new characters
enter them as scripts are encoded (§7.1) — so a text valid at one Unicode version is valid at every later one …",
and add after it: "The joining-control contexts are the exception: they read `Joining_Type`, `General_Category`,
`Indic_Syllabic_Category` and `Script`, which Unicode may revise, so a name a context admits at one version is
guaranteed at a later one only where its characters' values of those properties are unchanged." §8.3's layer-1 row
gains the same qualification. Dropping the parenthetical alone also removes the overclaim, at the cost of leaving
the exception unsaid.

**Status against Revision 38:** open; wording only. `JoiningControls` implements the three contexts over the
properties named above.

---

## 4. An undeclared field name: hygiene before closure ([TSON-SCHEMA] §5.2, §11.4 against [TSON-JSON] §9.4)

**Section:** [TSON-SCHEMA] §5.2 ("A document's field names are judged by the record that reads them": "whether or
not it is a value of the record's name type"; "Being no name, such a field meets no hygiene rule"), §11.4 (last
paragraph: "a name the record does not declare is unrecognised and meets no name rule"); [TSON-JSON] §9.4 (the
order at a record position).

**Kind:** internal inconsistency — between Part 2 and Part 3, Part 3's half stated and Part 2's to follow.

**The problem.** Part 2 makes every undeclared field name unrecognised and exempt from hygiene: §5.2 says such a
name is an unrecognised field "whether or not it is a value of the record's name type", and that, "Being no name,
such a field meets no hygiene rule"; §11.4 says the same. [TSON-JSON] §9.4 says the opposite, with the reason: "A
look-alike member name MUST be refused under [TSON-DATA] §8.2 and MUST NOT be reported as a closure violation … So
the order at a record position is: declared fields, then the name type over what is left, then hygiene over the
names among it". Take a record declaring `password` and a document sending `pаssword` with U+0430 CYRILLIC SMALL
LETTER A. Under Part 2's text it is an unknown field — a validation error, a verdict reached by way of a rule that
reads the UCD's unfrozen confusable data in exactly the case [TSON-DATA] §8.2 keeps out of validity. Under Part 3's
it is a hygiene refusal. A field name is a field name in both encodings, so the two cannot both be right, and Part
3's is: a name that is a value of the name type is a name, whether or not the record declares it.

**Interpretation chosen.** Part 3's order, in both encodings. A name matching a declared field carries the
declaration's verdict; an undeclared name that is a value of the record's name type meets [TSON-DATA] §8.2 under
that type's profile before closure, so the look-alike is refused and never reported as an unknown field, and
closure reports it only where the identifier policy admits it; an undeclared name that is no value of the name type
is unrecognised and meets no name rule, so `$ref` at a record whose names admit no `$` is an unknown field and not
a restricted character.

**Suggested resolution.** §5.2's bullet: replace "whether or not it is a value of the record's name type: one that
is not can be no declared field, since every declared name is one, so it is unknown on the same terms" with "after
the name type has judged it: one that is a value of the name type is a name, and meets the identifier policy
([TSON-DATA] §8.2) under the type's profile before it is reported as unrecognised, so a look-alike of a declared
name is refused and never reported as an unknown field; one that is not can be no declared field, since every
declared name is one, and is unrecognised without reaching the policy", and "Being no name, such a field meets no
hygiene rule" with "A field that is no name meets no hygiene rule". §11.4's sentence: "a name the record does not
declare is judged by the name type first: a value of it meets the identifier policy as a name before closure
reports it, and one that is not is unrecognised and meets no name rule".

**Status against Revision 38:** open; running in both encodings as described.

---

## 5. Kinds: what [TSON-SCHEMA] §4.2 leaves unstated or contradicts

**Section:** [TSON-SCHEMA] §4.1, §4.2 ("Kinds: the names of the types built with a constructor"), §3.3.1, §9.

**Kind:** underspecification and internal inconsistency — five points, and one of vocabulary. The change log
records the first as an open question; the text it says "states the running behaviour" does not state it.

**The problem.**

- **(a) A namespace entry and the namespace kinds.** §4.2: a kind's values are "the names of every type in the
  governing closure built with the constructor `of`"; §4.1: an application of a namespace type "builds a map value,
  and an entry whose value it is is no type". The one explicit sentence — "A namespace type is a member of
  `type_kind` and of the namespace kinds (`kind<namespace>`, `kind<interface>`) and of no other" — is about
  namespace *types*. Whether `myapi => !api { … }` is a value of `kind<api>` and of `kind<namespace>` is stated
  nowhere: "every type" excludes it, "built with" includes it.
- **(b) A namespace type's `!record` tag.** The membership walk — "the constructor its value applies is `C` or IS-A
  `C`" — would put a namespace type such as `interface`, whose value is `!record { … }`, into `kind<record>` and
  `kind<product>`. The bullet then says it is a member "of no other" and that "`kind<record>` and `kind<product>`
  exclude it", without saying which step of the walk excludes it.
- **(c) `kind<namespace>` and `namespace`.** "`kind<namespace>` holds `namespace` itself" is true only in a closure
  that holds `namespace`, which is a kernel-governed schema's; in an ordinary schema's closure `kind<namespace>`
  lists namespace entries only, if (a) is answered as running.
- **(d) `top`.** §3.3.1 lists `top` among the constructors "by that measure" — the IS-A-`top` predicate — and §4.1
  makes "IS-A `top` the predicate for being a constructor"; but `top` is not IS-A itself, and §4.2 says "so is
  `top` itself: a fresh abstract record IS-A nothing, so it is a member of every kind whose `of` is `record` or
  above it". The resolved kernel lists `top` in `type_kind`'s `members`. As written, `top` is a constructor by one
  section, not one by the predicate another states, and a member of a kind of types by a third, while §4.2 says a
  constructor whose instances are types is never a member.
- **(e) `type_name` slots.** §9: "`kind_type.of` is the one type-shaped slot typed `type_name`, since its values
  are constructors' names". It is not the one: `ref_type.of`, `kind_type.members`, and `type_chain.supertypes` and
  `subtypes` are typed `type_name` too (§4.1, §4.2). §4.2 gives `kind_type.of`'s reason and none for `ref_type.of`,
  whose value is a type of the writer's own namespace — which `type_kind` covers.
- **Vocabulary.** "Kind" names an entry's category — ATOM, PRODUCT, SUM, NAMESPACE, REFERENCE (§4.1, §5.5, §8.1) —
  and a `kind_type` instance, `kind<T>` (§4.2), in adjacent paragraphs. Editorial, but "an entry's kind" and "a
  kind's members" now mean unrelated things.

**Interpretation chosen.**

- (a) A namespace entry is listed by `kind<api>` and by `kind<namespace>`, its value being built with `api`, which
  IS-A `namespace`, and by no kind of types (`NamespaceEntryTest`; the corpus vector
  `class2/link/valid/the-kinds-of-a-namespace-constructor-hold-its-instances`).
- (b) For a namespace type the walk does not follow its `!record` tag: it counts as built with the namespace
  constructor it is or IS-A, and is a member of `type_kind` and of the namespace kinds.
- (c) As (a) gives it: `namespace` where the closure holds it, namespace types and namespace entries otherwise.
- (d) Two predicates, each asked where it belongs. Applicability and placement ask "IS-A `top`, or is the kernel's
  `top`", so `top` is a constructor — one whose direct application is refused as abstract. Kind membership reads
  only the chain, so `top`, whose chain is empty and whose value is `!record { … }`, is walked as the fresh
  abstract record it is: built with `record`, a member of `type_kind`, `kind<product>` and `kind<record>`.
- (e) As the kernel declares the slots.

**Suggested resolution.**

- (a) In §4.2's membership bullet: "A namespace entry (§4.1) is built with its namespace type, so `kind<api>` and
  `kind<namespace>` list `myapi => !api { … }`; it is no type, and no kind of types lists it."
- (b) After "the constructor its value applies is `C` or IS-A `C`": "— for a namespace type, the namespace
  constructor it is or IS-A, its `!record` tag not followed, since a value of it is a map —".
- (c) "`kind<namespace>` holds `namespace` itself where the closure holds it".
- (d) In §4.1: "**IS-A `top`, or being `top`, is the predicate for being a constructor**"; in §4.2: "membership
  reads construction and the chain, never applicability: `top`, a constructor by §3.3.1 whose own chain is empty,
  is walked as the fresh abstract record its value is".
- (e) §9: "`kind_type.of` is typed `type_name`, since its values are constructors' names, which no kind covers
  (§4.2); so is `ref_type.of`, whose type parameter `T` is a value parameter resolved in the type-name namespace,
  and the name lists `kind_type.members`, `type_chain.supertypes` and `subtypes`". §4.2 gains the same reason for
  `ref_type.of`.
- Vocabulary: call §4.1's categories an entry's **category** (or **kind category**) wherever they are meant, and
  keep "kind" for `kind_type`.

**Status against Revision 38:** open; running as described (`KindMembers`, `EntryKind.isConstructor`).

---

## 6. A record template at a kind-typed slot: [TSON-SCHEMA] §5.12 against §4.2 and §5.10

**Section:** [TSON-SCHEMA] §5.12 ("An open entry is no referent (`request: box` over `box => <T> { v: T }` is
refused, a template denoting no value until applied)"; "at a `type_kind` slot the name is one segment and must
resolve to a member of the kind"), §4.2 (`kind<record>` holds "a record template's family base"), §5.10 (the base
is "a type, a member of `kind<record>`").

**Kind:** internal inconsistency.

**The problem.** `request` in §5.12's example is typed `kind<record>`. §4.2 and §5.10 make a record template a
member of that kind, and §4.2 says a kind-typed slot may name a type "no value is read through until a member is
chosen" — an abstract record is admitted there. §5.12 refuses the record template at the same slot. A reader of
§4.2 expects `request: box` to be admitted, as `request: pet` over `pet => abstract { … }` is. Separately, §5.12's
"at a `type_kind` slot the name is one segment" states the rule for one kind where it holds of every kind-typed
slot, `request?: kind<record>` among them.

**Interpretation chosen.** Both, in order. The record template is listed among the kind's members
(`KindMembersTest`), and a name resolving to an open entry at a kind-typed slot is refused before membership is
judged (corpus vector `class2/link/invalid/a-name-resolving-to-an-open-entry`): a document names a type whose
values it means, and a template has no values until applied, so what a document names there is an application a
declaration names, `bt => box<int32>` then `request: bt`.

**Suggested resolution.** In §4.2's membership bullet, after "a record template's family base": "— listed, since
the walk reads construction; naming one at a kind-typed slot is nevertheless refused as an open entry (§5.12), a
document naming there the application a declaration gives it". In §5.12, "at a `type_kind` slot" reads "at a
kind-typed slot".

**Status against Revision 38:** open; running as described.

---

## 7. The positional form after `type_kind`: [TSON-SCHEMA] §5.6, §5.12, §8.1 and §4.2

**Section:** [TSON-SCHEMA] §5.6 ("In a schema document the positional form keeps a bare token where the positional
field is atom-typed … a bare token at a slot typed by a record is refused as no record"), §5.12 ("a bare token at a
record-typed slot in a schema document is refused as no record (§5.6)"), §8.1 ("Positional form"), §4.2 (the
placement paragraph), §5.9 rule 8.

**Kind:** internal inconsistency. The change log carries the first point and §8.1's consequence as editorial
readings awaiting the spec author; the text states both partly.

**The problem.**

- **(a) "Refused as no record" is wider than the rule it follows.** §5.6 keeps a bare token "where the positional
  field is atom-typed" and loses it "where that field is product- or sum-typed", then ends: "a bare token at a slot
  typed by a record is refused as no record". §5.12 repeats the last clause. But `record_field` and `type_ref` are
  records whose positional fields are atom-typed, and §8.1 says so of both: `type_ref`'s positional form "applies
  at every `type_ref`-typed position", and "`record_field.type` is `type_kind`, an atom, so a `record_field` has a
  positional form". By the closing clause `fields: { p => text }` in a schema document is refused; by the rule
  before it, and by §8.1, it is admitted.
- **(b)** §5.6 gives "a type name at a `type_kind` or `type_ref` slot" as its example of an atom-typed positional
  field. A `type_kind` slot holds an atom, which has no positional form; the example is the `type_ref` slot, whose
  positional field `name` is atom-typed.
- **(c)** §8.1: "a data document may write `fields: { x => text }` for `fields: { x => { type: text } }`". So may a
  schema document, by (a).
- **(d)** §4.2's placement paragraph: "an ordinary schema composing with, refining, or subtracting from a
  constructor has declared an entry that IS-A `top` and is refused for that". §5.9 rule 8: "A subtracted
  construction in a meta-schema whose head is a constructor is not one — its chain is empty, so it is IS-A
  nothing". A subtraction declares nothing IS-A `top`, so placement does not refuse it.

**Interpretation chosen.** In a schema document a bare token at a record position fills the record's positional
field exactly when that field is atom-typed; the spelling is refused where the positional field is product- or
sum-typed, or where the record has no positional form. So `!record { fields: { p => text } }` is admitted, and
`fields: { p => { type: text } }` is its explicit form (`PositionalNameTest`). A subtraction headed by a
constructor resolves with an empty chain, and `!foo` over it is not applicable.

**Suggested resolution.**

- (a) §5.6's closing clause: "a bare token at a record-typed slot whose positional field is product- or sum-typed,
  or at one whose record has no positional form, is refused as no record". §5.12's: "a bare token at a record-typed
  slot in a schema document fills the record's positional field where that field is atom-typed, and is refused as
  no record otherwise (§5.6)".
- (b) §5.6: "— a type name at a `type_ref` slot, an enum member, a literal —".
- (c) §8.1: "a document may write `fields: { x => text }`".
- (d) §4.2: "an ordinary schema composing with or refining a constructor".

**Status against Revision 38:** open; running as described (the record reader fills the positional field for an
atom-typed one).

---

## 8. Record templates: sentences left from the held-template reading

**Section:** [TSON-SCHEMA] §1.3, §5.2, §5.8, §5.10, §8.1, §8.2 ("Entry shape"), §12.1 (the `instance` production's
comment).

**Kind:** internal inconsistency. A record template is now a `!record` with `parameters`, read as vocabulary at its
declaration, its base ABSTRACT by construction; these sentences still read it the earlier way.

**The problem.**

- **(a) The stated `extension`.** §5.10: "A resolved-output consumer dispatches on the record template's
  `extension` and `discriminators` and never reads a field that carries a parameter (§1.3), which is why both are
  stated on the record"; and "its `extension` and `discriminators` stated" (Open bodies in output). §1.3: "with the
  family facts `extension` and `discriminators` stated on it". §8.1: "its `extension` and `discriminators` stated
  as the family's facts". But §5.10 also says "**The base's `extension` is ABSTRACT by construction** … and is not
  stated: the record's own `extension` is **each application's**". A consumer dispatching on the record's
  `extension` reads the members' fact, not the base's.
- **(b) What travels to a member.** §5.10: "The selectors do not travel to a member (closing has just pinned them)
  and ABSTRACT does (it holds of every application identically), which is each fact's own scope." With the base's
  ABSTRACT unstated, this now reads as the base's ABSTRACT reaching each member, against `bt => box<int32>` being
  OPEN. And closing pins only the selectors a parameter pins; a `=?` selector in a record template is pinned by no
  argument. §8.2's entry shape repeats it: "no `discriminators`, closing having just pinned them".
- **(c) Where `type_ref` stands.** §4.2 lists "`type_chain.parents`, `type_argument.name`, the `@application`
  annotation, held text, and `reference.target`", and §8.1 the same, and §8.1 says "A `type_kind` slot holds a bare
  name and nothing else". An open record's `record_field.type` holds an application: §5.10's own desugaring example
  writes `box => !record { fields: { a => { type: { name: array_t  arguments: [ { name: T } ] } } } … }`, and §8.1
  elsewhere says `arguments` appears "in a record template's fields and parents".
- **(d)** §12.1's `instance` comment: "With a parameter list the body is held unread until materialisation
  (§5.10)". `<T> !record { … }` is a record template, read as vocabulary at its declaration.
- **(e)** §5.2: "A base whose members are its own applications — a record template — is §5.10's case, with the same
  checks." Not quite the same: the selector's name mark is each member's, and the base reads a parameter-pinned
  selector required whatever its mark (§5.10), where §5.2 requires a selector's name unmarked.
- **(f)** §5.10, "Where a deferred diagnostic lands": "`box => <T> { v: T  w: no_such_type }` applied by `holder`
  belongs to `box`, which wrote the name". A record template is read at its declaration, so `no_such_type` is
  reported there and nothing is deferred to an application; the example needs a held template.
- **(g)** §5.8: "An alias to an *instantiation* (`place_order => method<order, order>`) has no vocabulary body to
  compose with (§4.3)". A declared application is no alias (§8.3: "Instantiation entries are not references"), and
  a record template's declared application is a `!record`, which composes.

**Interpretation chosen.** A record template's resolved form states no `extension` — the base is ABSTRACT by
construction — and each application states its own, OPEN unless the template wrote `abstract`; a declared member
states no `discriminators`. A consumer dispatches on `parameters` (the record is a base) and `discriminators`. An
open record's `record_field.type` holds a `type_ref` application where one is written, and only an open record's.
The corpus vector `class2/schema/valid/a-family-base-names-the-entries-its-members-name` states the resolved form.

**Suggested resolution.**

- (a) §5.10: "A resolved-output consumer recognises the base by its `parameters` and dispatches on its
  `discriminators`, and never reads a field that carries a parameter (§1.3); the base is ABSTRACT by construction,
  and the record's `extension` is each application's." §1.3 and §8.1, and §5.10's "Open bodies in output": "its
  `discriminators` stated as the family's, its `extension` each application's".
- (b) §5.10: "The selectors a parameter pins do not reach a member, closing having pinned them, and the base's
  ABSTRACT does not either, being the base's alone: a member's `extension` is the record's own." Say what a `=?`
  selector in a record template becomes in a member, and use the same words at §8.2's entry shape.
- (c) §4.2 and §8.1 add "an open record's `record_field.type`" to the list, and §8.1: "A `type_kind` slot of a
  closed entry holds a bare name and nothing else".
- (d) §12.1: "With a parameter list the body is held unread until materialisation (§5.10), except a `!record` body,
  which is a record template read as vocabulary".
- (e) §5.2: "… is §5.10's case, with the same checks, except that a selector's name mark is each member's (§5.10)".
- (f) §5.10: give the first half a held template, whose names are read when it closes — `m => <T> !map { key_type:
  T  value_type: no_such_type }` applied by `holder` belongs to `m` — or keep only the second half, where the
  offending token is an argument.
- (g) §5.8: "A declared application of a template whose body is not a record (`string_triple => vector<text, 3>`)
  has no vocabulary body to compose with; a record template's declared application is a `!record`, which composes
  like any other (§4.3, §5.7)."

**Status against Revision 38:** open; wording only, the behaviour described running.

---

## 9. Instantiation identity and `@application`: [TSON-SCHEMA] §5.10, §6, §8.1, §8.2 and §8.3

**Section:** [TSON-SCHEMA] §8.2 ("Entry shape" and "Two identities"), §8.3 ("Instantiation entries are not
references"), §6, §8.1 ("`@application` is the one fact the value does not state"; the body-patterns table), §5.10
(the `c3` paragraph), §5.10.1 ("Template materialisation").

**Kind:** internal inconsistency, and one omission: a verdict the change log calls a change appears nowhere in the
text.

**The problem.**

- **(a)** §8.2, right after declaring `hd_row => vector<pixel, 1920>`: "while `vector<pixel, 1920>` written at a
  use site, and the structurally identical form written directly as `[pixel; 1920]` anywhere in the schema, both
  land on one *closed synthetic* entry". The same section: "A use-site application resolves to a declaration that
  owns it where one exists". In the example a declaration owns it, so the use site lands on `hd_row`.
- **(b)** §8.2's first identity bullet: "**Declared instantiations and self-reaching instantiation entries** — two
  fully-bound applications denote the same entry if and only if their **canonical applications are structurally
  equal**". The paragraph above it: "Two declarations naming one application are **two entries**". The rule holds
  of self-reaching entries; of declared ones, only one direction does.
- **(c)** §8.3: "The self-reaching instantiation entry of §8.2 is the one `!reference` that carries
  `@application`." §6 and §8.1 list among `@application`'s carriers "the alias a template's application closes to"
  — `my => id<text>` over `id => <T> T` — whose closed value is a `!reference`.
- **(d)** §5.10's `c3`: "`c3` is the expansion, a closed synthetic keyed on its content where a use site wrote the
  application, or the declaring entry itself where a declaration did, with `@application:{ name: grid … }` on its
  key". The `with` clause reads as qualifying both; only the declaring entry carries `@application` (§8.2: the
  synthetic carries "no `@application`").
- **(e)** §5.10.1: "the resolver materialises one entry per distinct application: identity is the canonical
  application … and every further occurrence of the same application anywhere in the schema reuses the existing
  one". True of a self-reaching application; an application nothing declares is its expansion, keyed on content,
  and two declarations naming one application are two entries. §8.1's body-patterns row "Template application | the
  declaration naming it, with `@application` on its key; else its expansion, a closed synthetic (§8.2)" omits the
  self-reaching exception.
- **(f)** The change log records one verdict change for record templates: a minted record composing an application
  only a use site mints now loads — `u: vip<text>` over `vip => <T> box<T> & { … }`, beside `w: box<text>`. Part 2
  does not say it. §8.2 says a minted record whose chain "would name a declared type", or "a parent application a
  declaration owns", is an error, which leaves the use-site case to inference, and §8.1 says "a template's
  expansion carries whatever chain the expansion's own `^` or `&` gives it", which reads as giving the minted
  `vip<text>` a chain edge to `box<text>`'s synthetic.

**Interpretation chosen.** A use-site application resolves to the declaration that names it where one exists, else
to its content-keyed expansion; a self-reaching application keeps one instantiation entry per canonical
application; `@application` is carried by the entry a declaration closes — a record template's member, a namespace
entry, an alias that closes to a `!reference` — and by the self-reaching entry, never by a synthetic. A minted
record's parent application that nothing declares contributes no chain edge, so `u: vip<text>` beside `w:
box<text>` loads; one a declaration owns is the error.

**Suggested resolution.**

- (a) §8.2: "while `vector<pixel, 1920>` written at a use site lands on `hd_row`, and where no declaration names
  it, it and `[pixel; 1920]` land on one *closed synthetic* entry …".
- (b) Retitle the bullet "**Self-reaching instantiation entries**" and add: "A declared instantiation's identity is
  its name; a use site reaches it by the canonical application".
- (c) §8.3: "The self-reaching instantiation entry of §8.2, and an alias a template's application closes to, are
  the `!reference` entries that carry `@application`."
- (d) §5.10: "`c3` is the expansion — a closed synthetic keyed on its content where a use site wrote the
  application; where a declaration did, the declaring entry itself, which carries `@application:{ … }` on its key".
- (e) §5.10.1: "When a template application closes, it resolves to the declaration that names it, or to its
  expansion (§8.2); a self-reaching application keeps one instantiation entry per canonical application …". §8.1's
  row adds "; a self-reaching application's instantiation entry (§8.2)".
- (f) §8.2, after "A minted record is a member of nothing": "and a parent application nothing declares gives it no
  chain edge: `u: vip<text>` over `vip => <T> box<T> & { … }`, beside `w: box<text>`, loads, the minted record IS-A
  nothing". §8.1: "a template's expansion carries whatever chain the expansion's own `^` or `&` gives it, but a
  minted record takes no edge to an expansion (§8.2)".

**Status against Revision 38:** open; running as described.

---

## 10. Chains and `record_field`: wording against the fixtures

**Section:** [TSON-SCHEMA] §4.1, §5.5, §8.1 (the body-patterns table, `record_field`, "Positional form"), §9 (the
kernel row); the resolved fixtures `spec/m/*-resolved.tn`.

**Kind:** inconsistency with the companion artifacts.

**The problem.**

- **(a) "Empty chain" where only `supertypes` is empty.** §4.1: "constructor instances (`integer`, `value`,
  `dynamic`) and fresh records (`person`) carry empty chains". §5.5: "the result carries `C` as its tag and an
  empty chain". §8.1's table: "Constructor instance `!T {}` | … `!T {}`; no chain" and "Fresh record `{ fields }` |
  … no chain". The resolved kernel has `integer => !integer_type { chain: { subtypes: [non_negative_integer] } }`,
  and `atom_specification` a chain with `subtypes`: a construction or a fresh record has no supertypes, and has
  subtypes when something refines or composes it. §4.1's `type_chain` paragraph already says it right —
  "`supertypes` … empty for a fresh record, an instance and a subtracted record alike".
- **(b) Four facts.** §8.1: each `record_field` "carries the four facts a reader consults — `type` (a `type_kind`
  value), `optional` …, `voidable` …, `role` … and `value`" — five. §5.2 and §9 count `type` and four facts beside
  it.
- **(c)** §9's kernel row: "`record` carrying `fields: field_map`, `extension: record_extension_type ~ OPEN`". A
  default on an unmarked name is refused (§5.2); the kernel declares `extension?: record_extension_type ~ OPEN`.
- **(d) The tag on a field's value.** §8.1 says canonical output "writes the explicit record" for a `record_field`,
  not whether it is tagged. Every fixture writes `!record_field { type: … }`; §1.6's example writes `id => { type:
  uuid }`.

**Interpretation chosen.** As the fixtures: a construction or fresh record states only the `subtypes` the schema
gives it; `record_field` has `type` and four facts; the kernel's `extension?:`; and a field's value written
`!record_field { … }`.

**Suggested resolution.**

- (a) §4.1: "carry no supertypes and are not IS-A `top`"; §5.5: "the result carries `C` as its tag and no
  supertypes"; §8.1's two rows: "no supertypes".
- (b) §8.1: "carries its `type` (a `type_kind` value) and the four facts a reader consults — `optional` …".
- (c) §9: "`extension?: record_extension_type ~ OPEN`".
- (d) §8.1: "canonical output writes the explicit record, tagged `!record_field`" — or, if the untagged record is
  meant, say so and the fixtures follow; §1.6's example then matches whichever is chosen.

**Status against Revision 38:** open; wording only. The fixtures and `ResolvedFixtureTest` are as described.

---

## 11. Examples and sentences that are wrong or stale

**Section:** as each bullet gives.

**Kind:** errors.

**The problem, and the fix for each.**

- [TSON-SCHEMA] §5.10's selector condition: "`kind: bounded<9> = N` satisfies it … and `kind: bounded<M> = N` is
  refused at the template, since `pet<9, 3>` and `pet<5, 4>` would give the selector two types". The only `bounded`
  in Part 2 is §5.7's `bounded => <N> { attempts?: integer = N }`, a record template, and §5.10's in-paragraph
  `pet` is `pet => <N, T> { type?: text = N  pet: T }`, whose second argument is a type. Declare the names the
  example uses: `bounded => <M> !integer_type { max: M }` and `pet => <M, N> { kind: bounded<M> = N  name: text }`.
- [TSON-DATA] §2.5: "So, schemaless, `{ "first name": 1 }`, `{ _id: 1 }` and `{ 42x: 2 }` are resolver errors".
  §7.1: "`{ "_id": 1 }` is a resolver error in a schemaless document and `{ _id: 1 }` a parse error unquoted".
  Quote it: `{ "_id": 1 }`.
- [TSON-DATA] §8.2 ("Names and scopes"): "a soft hyphen in `continue_add` makes `pass­word` and `password` two
  names" carries a literal U+00AD in the first, which renders as nothing. Write it `pass\u{AD}word`, or name the
  code point in prose.
- [TSON-DATA] §2.2.1: "The host's **canonical text** is `host`'s: … an IPv6 address in RFC 5952 text, bracketed as
  the IRI grammar requires". `host`'s canonical text is unbracketed (§5.5). "The host's canonical text is `host`'s,
  with an IPv6 address bracketed as the IRI grammar requires".
- [TSON-SCHEMA] §5.5's URI bullet lists among identity's form rules "a lowercase host". A host's canonical text is
  lowercase U-labels, a dotted quad or RFC 5952 text: "a host in its canonical text, a `host` value ([TSON-DATA]
  §2.2.1)".
- [TSON-DATA] §5.5: "an `allow_idn` selector". §5.7 calls it a permission, and a selector is another facet kind
  there: "an `allow_idn` permission".
- [TSON-DATA] §5.5: "the hyphen-separated form is expressible unquoted, as are host names and dotted quads". A
  U-label may hold a character RFC 5892 admits by context that is not `XID_Continue` (U+05F3 HEBREW PUNCTUATION
  GERESH, CONTEXTO), and such a name needs quotes: "as are ASCII host names and dotted quads".
- [TSON-DATA] §5.1: "Each row of the tables below names its library". Only §5.5's table has a Library column;
  §5.3's, §5.4's and §5.6's do not. Add the column to each, or say "§5.5's table names each row's library; every
  other row is core's".
- [TSON-SCHEMA] §7.2: "Schemas wanting `uuid`, `bytes`, `datetime`, and the other built-in names import the core
  type library". The network names are `net.tn`'s: "import the library that declares them — core, or `net.tn` for
  the network names ([TSON-DATA] §5.5)".
- [TSON-SCHEMA] §11.2: "A reference the allowlist refuses is reported as a refusal ([TSON-DATA] §8.1, §10.1)". This
  reads as [TSON-DATA] §10.1, Part 1's normative references: "([TSON-DATA] §8.1; §10.1)".
- [TSON-SCHEMA] §9's `net.tn` row: "the schemaless annotations `!ipv4` … `!media_type` denote these entries
  ([TSON-DATA] §5.6)". Those rows are in [TSON-DATA] §5.5.
- [TSON-DATA] §8.1's resolver errors: "a type annotation outside the built-in vocabulary in a document with no
  `!!schema` (§3.2)". A schemaless document may carry a scoped `!!schema` on a field value (§2.5); §3.2's phrase is
  the right one: "in a document with no schema in scope".
- [TSON-DATA] §8.1's undetermined outcome cites "([TSON-SCHEMA] §10.1, §11.2)" for "a host that did not answer".
  §11.2 states the fetch rules, all refusals; only §10.1 covers a host that did not answer: "([TSON-SCHEMA]
  §10.1)".
- [TSON-SCHEMA] §6: "An annotation typed by an abstract entry — `@sum`, `@top`, `@type` (§4.1) — is a schema error
  in either position, a value at an abstract position naming no subtype". The rule's reason holds of any abstract
  entry naming no subtype, a meta layer's own abstract record included, so the sentence is sound; but its examples
  are the base kinds alone, which reads narrower than the rule. "An annotation typed by an abstract entry — a base
  kind (`@sum`, `@top`, `@type`, §4.1), or any abstract record — is a schema error …".
- [TSON-DATA] §10.1 lists RFC 6839, which Part 1 never cites. Drop the row, or cite it at `!media_type`, where a
  structured suffix is part of a subtype.
- [TSON-SCHEMA] §9 says "new to this revision" twice (the network constructors, and core's paragraph on `net.tn`).
  A revision's novelty is the change log's to state; drop both.
- [TSON-SCHEMA] §3.3.1: "A meta-schema MUST therefore import every schema whose entries it intends to expose".
  Since §2.2.3 confines an import to the importer's governing level, add "at its own level (§2.2.3)".

**Status against Revision 38:** open; wording only.
