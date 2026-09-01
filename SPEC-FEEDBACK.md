# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation.
See `CLAUDE.md` for why this file exists and when to add to it. Spec quotes below are from
2026 Revision 34 — Part 1 (https://tson.io/raw/2026/34/tson-part1-data.md) unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a
suggested resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a
revision closes.** It is an input to the next revision's adjudication, so its numbering is the numbering
that revision's change log will answer against — a stable index of the open set, not an archive of
everything ever raised.

The thirteen below are what Revision 34 leaves open, renumbered from #1; the fourteen it resolved of the
seventeen raised against Revision 33 are gone from here, because the spec now carries their rules and that
is where the answer belongs. **This file is the as-built record**, not a pointer to one: where an entry
proposes a design this implementation has built, the entry states the design, what is running, and what is
not, so that a reviewer editing the spec needs nothing beside it. **Cite the spec, not the argument that got
it there:** `docs/` and the Javadoc name the section that requires a behaviour, and a `SPEC-FEEDBACK.md #N`
citation is for an entry below, where there is no section to point at yet. When an entry closes, its
citations become spec citations and the entry is deleted — nothing here is an archive.

---

## 1. Does `!duration` accept ISO 8601's `PnW` week form, or only `PnYnMnDTnHnMnS`?

**Section:** §5.4.

**Problem:** §5.4's table gives `!duration`'s format as "ISO 8601 duration (`PnYnMnDTnHnMnS`)" — a
parenthetical showing one specific designator sequence. ISO 8601-1:2019 (the spec `duration_type` itself
pins to, per meta.tn's `spec` field) also defines a second, mutually-exclusive alternative form for
expressing a duration in whole weeks: `PnW` (e.g. `P3W` for three weeks), which cannot be combined with
the `Y`/`M`/`D`/`H`/`M`/`S` designators in the same value. §5.4's parenthetical doesn't mention `W`
anywhere, and nothing in the surrounding prose says whether that's because the week form is deliberately
excluded from the schemaless `!duration` atom, or because the parenthetical is a representative example of
the ISO 8601 duration format rather than an exhaustive grammar (the same way, elsewhere in the document,
a parenthetical sometimes illustrates rather than fully specifies). Both readings are defensible: excluding
`W` would be consistent with `!duration`'s host value being modeled as year/month/day/hour/minute/second
components (a week doesn't decompose uniquely into those without picking a day-length, though `P3W` itself
carries no such ambiguity on its own terms); including it would be consistent with simply deferring to "the
ISO 8601 duration format" as a whole, of which `PnW` is a normal part.

**Interpretation chosen:** `DurationType`'s parser accepts only `P` followed optionally by `Y`/`M`/`D`
designators, optionally followed by `T` and `H`/`M`/`S` designators, matching §5.4's parenthetical
literally — `P3W` is rejected as a parse error, not specially recognized. This was the more conservative
reading available (implementing a format the annotation's own table doesn't show would be a bigger leap
than declining to implement one it might have intended by reference), but it's a real coin flip, not a
confident call.

**Suggested resolution:** State explicitly whether `PnW` is part of `!duration`'s accepted format or not.
If it is, the table's parenthetical should show it (`PnYnMnDTnHnMnS` / `PnW`) the same way §5.6's table
spells out multiple accepted grammar forms per numeric atom explicitly rather than by implication.

**Status against Revision 34:** open, carried across a third revision. §5.4's table is unchanged again —
`!duration` still reads `ISO 8601 duration (PnYnMnDTnHnMnS)` with no mention of `W`, and no prose anywhere
says whether the omission is a decision or an abbreviation. `DurationParser` still rejects `P3W`.

---

## 2. Content-hash pinning rides in the URI query (`?sha256=`), where a hash is neither a request parameter nor part of identity — external review suggests a fragment, or a structured `{ url, sha256 }` directive, instead

**Section:** Part 1 §2.2.1 (canonical identity / hash-pinned references), §10.2 (per-identity verification).

**Problem:** The spec pins a reference's integrity by appending a *query* parameter to its URI —
`!!import:"…/core.tn?sha256=<hex>"` — and then defines canonical identity by **stripping** that query, so a
pinned and a plain reference name the same identity. Two objections from external review:

1. **Query is the wrong URI component for a hash.** By URI semantics (RFC 3986 §3.4) a query is part of the
   *request* — data conveyed to the origin to identify/produce the resource — whereas a content hash is
   *verification metadata about the retrieved bytes*, evaluated entirely client-side and never meaningfully
   sent to a server. A *fragment* (`#sha256=<hex>`, §3.5) is the component that actually matches: it isn't sent
   in the request, is interpreted by the client, and is already outside what a server sees. That the spec must
   special-case *stripping* the query to recover identity is itself a symptom that the hash sits in a component
   whose native semantics it doesn't share.

2. **Integrity arguably shouldn't be in the URI at all.** A second reviewer proposes separating the locator
   from the integrity outright — a structured directive value rather than a hash smuggled into a string:

   ```
   !!schema: { url: "https://example.com/people.tn"  sha256: "c4d5e6f7…a2b3c4d5" }
   ```

   This drops URI-parsing of hash parameters, the canonical-identity stripping rule, and the "only
   hash-algorithm query parameters permitted, everything else rejected" special case; makes the algorithm an
   explicit, extensible field rather than a magic query key; and mirrors how lockfiles / package managers /
   Subresource Integrity separate *where* from *what it must hash to*. It is the larger change: directives
   currently take a bare URI string, so this is a directive-grammar change, and `!!id` (which today carries its
   own pin on its own line, excluded from the hash) would need an equivalent structured form.

**Interpretation chosen:** This implementation follows the spec as written — the query form. `TsonContentHash`
parses `?sha256=<hex>` off a reference (rejecting any other/unrecognized query parameter or malformed hex),
`CanonicalIdentity`/`TsonSchemaRegistry` strip the query to key everything by identity, `tson hash <file>`
stamps `?sha256=` onto the `!!id` line, and the bundled chain (meta.tn pins meta-kernel, core.tn pins meta.tn)
is pinned end-to-end this way. No change made — flagging the design, not diverging from it.

**Suggested resolution:** Choose among three. (a) Keep the query form — simplest, but semantically stretched
and dependent on the identity-stripping rule. (b) Move the pin to a fragment (`#sha256=<hex>`) — better matches
URI semantics, and identity can still ignore it by dropping the fragment; here a small, localized change (parse
`#` rather than `?` in `TsonContentHash`, and in `tson hash`). (c) Lift integrity out of the URI into a structured
directive (`{ url, sha256 }`) — the cleanest separation of locator from integrity, at the cost of a
directive-grammar change plus an `!!id` equivalent; here it touches the directive grammar, `TsonContentHash`,
canonical identity, `tson hash`, and every bundled `.tn`'s pin lines. If the query form stays, the spec should
at least justify why a hash lives in the query and name the identity-stripping as its deliberate consequence.

**Status against Revision 34:** open, carried deliberately. §2.2.1 is unchanged: the query form stays,
with the discipline Revision 33 tightened around it — the pin is "verification metadata, not identity", a
query MUST consist solely of hash parameters, and an unrecognised parameter name is an error rather than
something silently retained. The placement question itself has never been answered.

---

## 3. Every schema that writes a container sugar form inside a template mints its own copy of the same few templates

**Section:** Part 2 §5.3 (the lift rule), §8.2 (synthetic entry identity and content-derived naming), §9 (what
the kernel declares). Held bodies (§5.10) do not change this either way: a held body settles what an open
entry *is*, not how many schemas mint one.

**Problem:** a sugar form inside a template body lifts to an *open* synthetic entry — `<T> { a: [T] }` mints

```
array_p0_358380cd => <p0> !array { element_type: p0 }
```

and `box`'s field references it as `array_p0_358380cd<T>`. That entry is the same entry in every schema that
writes `[T]` inside a template, up to a content-derived name §8.2 already declares non-normative. The lift
rule mints it per schema because it has nowhere else to put it, so a fixed, tiny set of templates is
re-derived by every author who uses generics over a container.

The kernel already takes the other route one level down: rather than have every schema inline
`!set { element_type: identifier  min_items: 1 }`, §9 declares `enum_set` once and `enum.members`
references it. The same argument applies to the open forms, and nothing but availability decides it.

**Interpretation chosen:** mint per schema, as §5.3 specifies. `SchemaDesugarer` injects the lifted
declaration into the document being desugared, with `positionalNames`/`rename` alpha-normalising the
parameters so that two spellings of one form land on one entry within that document.

**Suggested resolution:** consider declaring the fixed-arity open forms in the kernel — `<T> !array
{ element_type: T }`, its `state: OPTIONAL` sibling, and the `map` pair — so that §5.3's lift targets a
declared name rather than an injection. Two things to weigh, both real:

1. **Only part of the family is fixed-arity.** The size specifier's variants differ by which bounds are
   present (`[T; 3]`, `[T; 1..]`, `[T; 1..2]` are three shapes, since an absent `max_items` is not a
   defaulted one), and `tuple` and `choice` are variadic, so `[T, U]` and `( T | error )` have no
   fixed-arity template at all. A kernel set would cover the commonest case and leave the lift rule in
   place for the rest, which is a smaller win than "declare them once" suggests.
2. **Availability is the hard part.** A schema's type-name namespace is its own declarations plus its
   `!!import`s (§3.3.1, §2.2.3); it does not include the namespace of the schema its `!!meta` names. So a
   kernel-declared `array_of` is not in scope for a schema that has not imported the kernel, and a lift
   targeting it would make desugaring — a phase whose whole virtue is being syntactic, consulting no
   governing meta and no namespace — depend on the import set. Either §5.3 would have to name these as
   always-available regardless of import (a new category of name), or they would have to live somewhere
   every schema already reaches.

Note this is **not** a proposal to re-parameterize `array`/`set`/`map`: those stay de-parameterized
constructors with `element_type` as an ordinary field, and what is proposed here is named templates *over*
them, which is the layer a user's own `box => <T> { ... }` lives in.

**Status against Revision 34:** open, unaddressed. §5.3's lift rule is restated in this revision's own
terms — a parameter-bearing form lifts to an open synthetic "whose body is the constructor application as
written, held until materialisation" — but it still mints per schema, and §9 declares no open container
templates. This implementation mints per schema and `ContainerSugarEndToEndTest` pins the resulting entry
sets.

---

## 4. §8.1 both forbids and specifies a parameter reference inside a `type_definition`

**Section:** Part 2 §8.1 (output records, "Reading parameter references"), §5.10 (the closed-entry rule),
§1.3 ("Resolved-output consumers").

**Problem:** §8.1 says of an open entry:

> An **open** entry is serialized as its declaration — `<params> !C core-value`, the held body written under
> §5.10's one-spelling rule — rather than as a `type_definition` value: its body is not read against any
> vocabulary until materialisation, so **no `type_definition` could carry it**, and a consumer of closed
> entries never meets one (§1.3).

Three things in the same series say the opposite, and one of them is measurable rather than a reading.

1. **A `type_definition` demonstrably can carry it.** `box => <T> { value: T }` writes as
   `{ kind: PRODUCT  parameters: [T]  body: !record { fields: [ { name: value  type: T } ] } }`, and this
   implementation reads that back against meta.tn without complaint: `type_ref.name` is typed `identifier`,
   `T` is one, and nothing in the kernel distinguishes a parameter from a type name at that position. The
   only thing standing in the way is a sentence.
2. **§8.1's own "Reading parameter references" specifies how to read one.** A `name` "in any `type_ref`, at
   any depth ... resolves against the enclosing entry's `parameters` list first", and "a consumer holding an
   entry with empty `parameters` interprets every name directly against the schema" — which is a rule about
   what a consumer does with an entry whose `parameters` are *not* empty. If no `type_definition` could carry
   a parameter reference, the precedence rule has no position to apply at and `type_definition.parameters`
   has nothing to be non-empty for.
3. **§5.10's closed-entry rule is stated as a rule on output.** "An entry whose `parameters` list is empty
   MUST contain no parameter references anywhere ... and its body is a binding record or a `!reference`,
   never a held application: a well-formedness rule **on resolver output** and an integrity check on ingest
   (§8.1)." A well-formedness rule on output that says what a *closed* entry may not contain presupposes
   output in which an open one appears.

So an implementation has to decide whether §8 output for a schema declaring templates omits those entries,
carries them as `type_definition` values with a non-empty `parameters` list, or carries them in a
declaration form the kernel's `schema => {type_name => type_definition}` does not type. §8.1's ingest
paragraph assumes the third — "an open entry, which ingest meets as a declaration rather than a
`type_definition` value, is re-resolved as source" — which would need `schema` to admit a second value shape,
and it does not.

**Interpretation chosen:** this implementation produces no §8 output at all, which §1.3 permits outright
("Serializing the resolved schema value as a data document is OPTIONAL"), so the question is unforced here.
What it does have is a value model in which an open entry's body is a `TemplateBody`/`HeldBody` — the
application as written, unread until materialisation — where the same text read back as a `type_definition`
binds an ordinary `RecordBody`. The two agree as §8 text and differ as values.

**What it costs, concretely.** The shared conformance corpus's `class2/schema/` layer compares the
resolver's own value against the vector's stated §8 output, read back through meta.tn. That works for every
construct except a template, where the two sides are the same document and different values, and nothing
here serializes the resolver's value to close the gap. So no `class2/schema/` vector declares a template,
and what one resolves to is stated only indirectly, at the corpus's `link/` layer, over the entries it
mints. Whether that gap is worth closing with a real §8 emitter depends on which of the three answers below
the spec gives.

**Suggested resolution:** drop "so no `type_definition` could carry it" and say which of the three shapes
output takes. The cheapest answer consistent with everything else in §8.1 is the second: an open entry is a
`type_definition` with a non-empty `parameters` list whose `body` is the held application's own binding
record, read under the parameter-precedence rule §8.1 already states — which needs no change to the kernel,
keeps `schema`'s value type as it is, and makes the closed-entry rule a check over a form that exists. The
`<params> !C core-value` spelling then belongs to schema *source*, which is where it is already written,
rather than to output.

**Status against Revision 34:** open, and new against this revision — §8.1's open-entry sentence and its
"Reading parameter references" paragraph are both Revision 34 text.

---

## 5. §11.4's scope list omits a template's parameters, so two parameters that read alike are accepted

**Section:** Part 2 §11.4 (name hygiene at the schema layer), [TSON-DATA] §8.2 (the three mechanisms).

**Problem:** §11.4 enumerates the schema layer's named scopes — "the members of one enum; the field names of
one record definition (member labels of its groups included, §5.11); the declared names of one schema; and
the merged namespace at `!!import`". A template's **parameters** are not among them, and a parameter is a
name: §5.10 says "a parameter declaration is a bare name", and §12.1 makes it a naming position matched
against §7.7's identifier grammar like any other.

The consequence is concrete rather than doctrinal. This is accepted:

```
box => <T, Т> { a: T  b: Т }
```

Latin `T` and Cyrillic `Т` (U+0422) are two parameters that render identically. A body referencing `T` binds
one of them, an application `box<text, integer>` fills both positionally, and no reader of the source can see
which slot either argument reaches. That is the substitution hazard §8.2 exists to refuse, and §11.4's own
argument for the enum-member scope — distinct strings, so the set's own uniqueness rule cannot see them —
applies to it word for word.

The two per-name mechanisms have the same gap for the same reason. §8.2 frames all three as operating "over
named scopes", which is strictly true only of mechanism 1: `Identifier_Status` and the restriction level
judge one name at a time and need no scope at all. But because §11.4 supplies the schema layer's scopes as a
closed list, a name in no scope is a name no mechanism is stated to reach — so a mixed-script or
restricted-character parameter name has no stated verdict either.

**Interpretation chosen:** this implementation treats **the parameter names of one template** as a fourth
schema-layer scope, checked by all three mechanisms in the same walk as the other three
(`TsonSchemaLinker.checkNames`; `ConfusableNameScopesTest` pins each). `<T, Т>` is refused as a confusable
pair, and a restricted or mixed-script parameter name is refused like any other name.

**Suggested resolution:** add the parameter names of one template to §11.4's list. It is the smallest change
that closes it, it needs no new mechanism, and the section already carries the reasoning under a different
scope. Worth considering alongside it: §8.2's "The mechanisms operate over named scopes" over-generalises
from mechanism 1, and saying so — that mechanisms 2 and 3 are per-name rules that apply wherever a name
occurs, while mechanism 1 is a relation needing a scope — would make a forgotten scope cost one missing
relation rather than three missing checks.

**Status against Revision 34:** open, and new against this revision.

---

## 6. §8.2 requires a refusal to name "the UTS #39 data version", which is not a version anything publishes

**Section:** [TSON-DATA] §8.2 (name hygiene), and its "On detection" note.

**Problem:** §8.2 makes a refusal reportable only "under a stated policy and a stated data version" and says
a conforming processor "MUST name the UTS #39 data version in the refusal". Its detection note asks the same
of a conformance suite: vectors "labelled with the UTS #39 version they were computed against". Neither
names a version that exists as such.

UTS #39 is a technical standard with its own revision number (revision 31, say), and the three files §8.2
actually depends on — `confusables.txt`, `IdentifierStatus.txt`, and the script data behind the restriction
levels — are not versioned by it. They are published as part of the Unicode Character Database and carry the
**UCD** version: `confusables.txt` for Unicode 16.0, not for UTS #39 revision 31. A processor asked for "the
UTS #39 data version" has two defensible answers that differ, and a suite vector labelled with one is
uninterpretable to a processor that reports the other.

They track in practice — a UTS #39 revision accompanies a UCD release — which is why this is a wording
defect rather than a design one. It still decides an interoperability question: the corpus's `refused`
vectors name a version, and the corpus's own `RUNNER.md` makes a version the processor does not carry a legitimate skip, so
whether two implementations skip or run the same vector rides on which number both chose.

**Interpretation chosen:** the **UCD version**. This implementation carries the tables for one UCD release,
verified against `DerivedCoreProperties.txt` for that release, and states it as `16.0` — reachable as
`TsonUnicodePolicy.dataVersion()` and carried on every refusal as `Diagnostic.unicodeDataVersion`.
The UCD version is the one that answers the question §8.2 asks it to answer: it identifies the tables, which
is what explains a disagreement between two processors, where a UTS #39 revision number would identify the
prose that describes the mechanisms — stable across exactly the refreshes §8.2 exists to make visible.

**Suggested resolution:** say "the Unicode Character Database version of the data files" (or "the UCD
version") in both places, rather than "the UTS #39 data version". If a UTS #39 revision is genuinely wanted
as well, ask for both and say so — but the one that must be there is the UCD version, since it is the one
that changes a verdict.

**Status against Revision 34:** open, and new against this revision.

---

## 7. `null` is a second spelling of absence that only Class 1 can see — proposal: remove it, leaving `_`

**Section:** §4.1 (null), §4.5 (resolution order), §2.9 (the absent sentinel), §7.7 rule 3 (no reserved words),
the JSON interoperability note under §9; Part 2 §4.2 (`value`, `void`), §7.3 (`null` at `void`-typed positions),
§5.4 (why a variant may not resolve to `void`), §9 (the `_`/`null` distinction restated).

**Problem:** §4.1 makes `null` a base value and insists it is "distinct from the absent sentinel `_`: null is a
value that can be stored and transmitted; `_` indicates that no value occupies a position." §2.9, Part 2 §5.4 and
Part 2 §9 each restate the distinction, §5.4 calling it one "the format draws deliberately". Read against Part 2
§7.3, the distinction is narrower than the prose suggests:

- **Schemaless**, and at a `value`-typed position (Part 2 §4.2), `null` resolves to the null base value and is
  distinct from `_`.
- **Under a schema**, `null` "has no special status" (§7.3): at a `text` position it is the string `null`, at an
  `int32` position it is a type error — **except** at a position whose type carries the `void` contract, where it
  is "accepted as an equivalent spelling of `_` and normalised to absence".

So the moment a schema is in scope, `null` is either ordinary text or a synonym for `_`. The value the four
sections defend exists only in Class 1 reads and in `value`-typed escape hatches, and a Class 2 processor — the
kind Part 2 exists to specify — never sees it. That is a concept costing four paragraphs of prose, a step in §4.5's
order, and a concession paragraph in §7.3 to deliver a distinction the format's main mode cannot observe.

Its justification is the JSON note under §9: "JSON `null` maps to the TSON null base type, not to the absent
sentinel." But under a schema that mapping does not hold either — a JSON document with `"name": null` for an
optional `text` field reads as the string `null`, silently, under Revision 34 as written. The JSON-superset
property `null` was kept for is therefore already a Class 1-only property. Part 1's own framing is one schema over
many formats, and a JSON reader is a separate stack in every implementation that has one; JSON compatibility is
that reader's job — JSON `null` maps to absence in the *model*, where the position's state decides whether absence
is admitted — and does not need the TSON notation to carry a keyword for it.

Two smaller consequences of keeping it:

- **A `null` map key is legal where a `_` key is not** (§2.9 forbids the absent sentinel as a key; `null` is a
  value, so `{ null => 1 }` is well-formed). An implementation that models the two as one node then cannot tell
  the keys apart — this one keys both on the same absent identity — and an implementation that models them as
  two has a node type whose only observable role is this key.
- **`null` is the one word §7.7 rule 3 has to explain away.** "There are no reserved words … `true`, `false`, and
  `null` are identifiers like any other" is true of names and false of Class 1 values, where §4.5 needs "to
  represent the string `"null"` in schemaless TSON, use quotes." `_` makes no such demand on names: it is
  `XID_Continue` only, so no identifier begins with it, and the reservation is lexical rather than a word.

**Interpretation chosen:** Revision 34 as written — this is a proposal, not a resolved ambiguity. `BaseTypeResolver`
runs §4.5's order with `null` first; `ValueParser` reads it at a `value`-typed position; `VoidReader` applies §7.3's
concession, for the unquoted token only; `TsonDataEmitter.nullValue()` writes it. What the implementation
*models* is the other half of the evidence: the read output has **one** no-value node, `TsonAbsent`, carrying `_`,
the `null` token where §4 applies, and a collecting-mode read failure alike, with no separate null node — because
no consumer of the tree had a use for the difference, so there was nothing to model. When the first
implementation quietly merges two things the spec calls distinct, the spec is describing a distinction it does not
have. What is **not** running is the removal itself: `null` is still resolved, still emitted, and the corpus's
`class1/resolver/valid/null-keyword` vector still expects the null base value.

**Suggested resolution:** remove `null` from the notation. Concretely:

- Part 1: delete §4.1; §4.5's order becomes boolean → number → string; drop the "distinct from … null" clauses in
  §2.9 and §4.4 and the "use quotes" sentence; §7.7 rule 3 then holds without qualification. The JSON note under §9
  changes from a mapping to a statement of scope: a JSON document containing `null` is not a TSON document, and a
  processor that reads JSON does so through a JSON reader that maps `null` to absence. That is a *softer* claim
  than the current SHOULD ("accept any valid JSON document"), and it should be made in those words rather than
  left as a silent narrowing.
- Part 2: `value` in §4.2 admits boolean, integer, float and string; `void`'s parenthetical and the §7.3 concession
  paragraph go, `void` admitting `_` alone; §5.4's rationale for refusing `(T | void)` loses the "absent-versus-null"
  clause and gets simpler, not weaker; §9's restatement goes with it.
- Nothing lexical moves: `null` was never a token class, so §1.3's lexer freeze holds and the unquoted token `null`
  is a string, as `frobnicate` is.

The one thing the removal changes for a document is that a bare `null` in schemaless data becomes the string
`null` rather than an error. It would be a mistake to guard that with a reserved word — a parse error on unquoted
`null` reintroduces exactly what §7.7 rule 3 removed, for the sake of one JSON habit that the JSON reader is the
right place to serve. The cost worth naming instead is the structured-output case: a model emitting `null` by JSON
reflex into a `text` position gets the string `null` silently, where an `int32` position refuses it loudly. That
case is already the behaviour under a schema in Revision 34, and it is the case that argues for routing model
output through a JSON reader rather than for a keyword in the notation.

**Status against Revision 34:** open, and new against this revision — a proposal rather than an ambiguity, and
one this implementation has not built ahead of the spec, since a base-resolution change is a Part 1 change and
Part 1 is frozen until the revision that makes it.

---

## 8. Removing `null` (#7) falsifies §6 and principle 5 — remove both, and the rules that exist only for them

**Section:** §1.2 principle 5 (JSON compatibility), §6 (TSON and JSON), §7.2.2 (the escape table and the surrogate
rules), §7.1 (byte order mark), §7.7 rule 3 (no reserved words); the JSON note under [TSON-SCHEMA] §9.

**Problem:** §6 states that "every valid JSON document outside those exceptions is a valid TSON document, and the
extensions are additive — no JSON construct changes meaning under TSON", and principle 5 that "valid JSON is a subset
of valid TSON at the structural level". Once `null` is a string (#7), both are false on the first JSON document that
contains one, and the SHOULD that follows them — "a TSON parser SHOULD accept any valid JSON document" — asks a
parser to accept a document it will silently misread. So §6 and principle 5 cannot survive #7 as written, and the
question this entry records is what else was there only because of them. Five rules cite JSON as their reason, or
have no other:

1. **The `\/` escape** (§7.2.2). The table itself labels it "(JSON compat)"; a solidus needs no escaping anywhere
   else in the format.
2. **`\uXXXX` and the surrogate-pair rules** (§7.2.2; §6 exception 2). A four-hex-digit escape cannot name a
   supplementary character, so the format inherits JSON's UTF-16 workaround — a surrogate pair — and then needs
   three MUST clauses to forbid the ill-formed halves JSON permits, plus §6's second exception to explain why TSON
   is stricter than RFC 8259 here. All of that is the escape form's own consequence. One escape naming a scalar
   value directly — `\u{1F600}`, the form Rust, JavaScript and Swift use — deletes the pairing rules outright:
   "TSON strings are well-formed Unicode scalar sequences" stops being a rule the lexer enforces and becomes a
   property the grammar cannot violate.
3. **§6 exception 1** (raw NEL/LS/PS inside a single-line token). Without §6 it is not an exception to anything —
   it is §7.2.2's rule, stated once.
4. **"Decoders MUST accept" a leading byte order mark** (§7.1, cited from §6 as JSON compatibility). RFC 8259 §8.1
   is where that posture comes from, and even there it is a MAY. Windows editors still emit one, so accepting it is
   the practical choice — but it should be stated as an encoding courtesy of §7.1's own, not carried by §6.
5. **`\b` and `\f`** (§7.2.2). JSON's, and written by hand approximately never. No consequence either way; listed
   because the table should be reviewed as a whole once `\/` and the surrogate form go.

Item 2 is the one that touches the lexer, which §1.3 declares "complete and frozen for the whole series". Principle
7 says a 2026-series revision "may change anything", so it is permitted — but it wants doing in the same revision
as #7, before anything is published against the frozen claim.

**Interpretation chosen:** Revision 34 as written, in full. `Lexer` decodes `\/`, `\b`, `\f`, `\s` and `\uXXXX`,
pairs surrogate escapes and refuses an unpaired one ("high surrogate escape not followed by a low surrogate
escape"), and discards a single leading U+FEFF without counting it toward any position. `TsonDataStream` accepts
commas and quoted field names as §2.4 and §2.5 admit them. Nothing here is built ahead of the spec.

**Suggested resolution:** delete §6 and principle 5. Replace the JSON note under [TSON-SCHEMA] §9 with a statement
of scope: a JSON document is read through a JSON reader, which maps JSON `null` to absence and JSON numbers to
`number`, and is not a TSON document. Then take items 1–3 as written, restate item 4 under §7.1 on its own
authority, and decide item 5 with the table in front of you. **What is JSON-shaped and should stay**, so that the
removal is not read as a mandate to look different: `"`-delimited strings; `[ ]` arrays; `{ name: value }` records;
the `\n \r \t \\ \"` escapes; base type resolution as a mechanism — Class 1 is a real mode (configuration, ad hoc
data) and only `null` was an accommodation; the `number` exact type and the rule that an unadorned numeric token
names it; and, on the implementation side, RFC 6901 pointers and JSON Schema 2020-12's output shape in diagnostics,
which are tooling interoperability and no part of the notation. The notation is JSON-*like* by design; what goes is
the claim to be a JSON *superset*, and the rules that only that claim required.

**Status against Revision 34:** open, and new against this revision — consequent on #7, and a proposal rather than
an ambiguity. Entries #9–#13 are the design choices JSON shaped that are worth a decision of their own once the
superset claim is gone; each is recorded separately because each can be answered separately.

---

## 9. A Class 1 field name is lexical for JSON's sake — proposal: a field name is an identifier at every layer

**Section:** §2.5 (record), §7.7 rule 3 (last clause), §7.7's "record field names are lexical at this layer", §8.2
(name hygiene, the field-name scope), §7.4 (`field-name = unquoted-token / single-line-token`).

**Problem:** §2.5 makes a field name at the data layer "lexical: any token the production admits names a field, and
`{ "first name": 1 }` is an ordinary record", with the identifier grammar constraining only *declared* names. The
reason is JSON: an object key is an arbitrary string, and a superset had to admit one. The design carries the cost
in three places. There are two name rules — a declared name is an identifier, a Class 1 field name is anything — and
the text has to say where each applies. Name hygiene runs differently by conformance class: §8.2's restricted-character
and restricted-script rules apply to identifiers, so a Class 1 record's field names see only the look-alike rule,
and an implementation has to know that a record's fields are policed under a schema and not without one. And §7.7
rule 3 needs a carve-out — "a schemaless record may still carry a field spelled `"_"` or `"_id"`, because Class 1
field names are lexical" — for names no declared field can bear.

The format already has the right answer to "a key that is not a name". A record's fields are the named members of
a shape, which is what makes them declarable; arbitrary string keys are what a **map** is for, and `{ "Content-Type"
=> "text/plain" }` is the honest spelling of that data today. Once no JSON object has to parse as a record, nothing
requires a record to admit a key a schema could never declare.

**Interpretation chosen:** Revision 34 as written. `TsonDataStream.isFieldNameTokenType` admits an unquoted or
single-line token as a field name and matches nothing against the identifier grammar there — deliberately, per
§2.5; `SchemalessTreeReader.reportConfusableFields` runs the look-alike rule over a schemaless record's field names
and nothing else, per §8.2. Under a schema, a field name conforms by construction, which is the half that would not
change.

**Suggested resolution:** make `field-name` an identifier position at every layer — the production keeps its two
spellings (quoting is still how a name containing nothing outside the profile is written when it would otherwise
resolve as a number, §7.1's "quoting by kind") and the decoded text is matched against §7.7 as an annotation name's
is. Consequences, all deletions: §2.5's "lexical" paragraph; §7.7's "record field names are lexical at this layer"
and rule 3's `"_"`/`"_id"` carve-out; §8.2's field-name distinction, so one walk polices every named scope and
[TSON-DATA] §1.5's Class 1 MUST ("the name-hygiene checks of §8.2 over each record's own field names") stops needing to
say which checks. A record whose key is not a name is a parse error, and the diagnostic can say what the author
wants: a map.

**Status against Revision 34:** open, and new against this revision — consequent on #8, a proposal rather than an
ambiguity, and the one of #9–#13 this implementation would recommend taking.

---

## 10. Optional commas and the trailing-separator ban are the JSON-superset shape — proposal: one consistent position

**Section:** §1.2 principle 4 (minimal required syntax), §2.4 ("Separators"), §7.4.

**Problem:** §2.4 admits whitespace, a comma, or both as a separator and forbids a trailing one: "`[1, 2, 3,]` and
`{ x: 1, }` are parse errors — and the rule applies throughout the series". Both halves are the superset's: the
comma is admitted so that JSON's separators parse (principle 4 calls commas "optional where the structure is
unambiguous", which is the superset's framing of a separator the format does not need), and the trailing ban is
RFC 8259's, inherited whole. The result is a position that is neither of the two consistent ones. Either the format
has a separator, whitespace, and no comma rule at all; or it has commas as a first-class separator, in which case
the one rule about them that every JSON author has wished away — no trailing comma — is a rule TSON chose to keep
with no JSON contract to honour.

**Interpretation chosen:** Revision 34 as written: `TsonDataStream.consumeSeparatorOrCloseCheck` accepts
whitespace, a comma or both, requires at least one between adjacent values, and refuses a separator before a
closing delimiter ("a trailing separator is not permitted before …").

**Suggested resolution:** take one of the two, and this implementation's preference is the first. **(a) Drop the
comma.** Whitespace is the separator, the trailing-separator rule has nothing to apply to, and §7.4 loses a
production. It is what principle 4 says it wants, reached by removing a rule rather than reversing one; what it
costs is a long inline array reading `[1 2 3 4]` rather than `[1, 2, 3, 4]`, which the format already admits and
which every other position in the format already reads. **(b) Keep the comma and permit a trailing one.** The
lesser change, and the one to make if the comma stays: the ban exists only where a comma is *the* separator and a
trailing one would be a missing element, which in TSON it never is. Either is better than the current shape, and
"the rule applies throughout the series" means the choice reaches [TSON-SCHEMA] §12.1's separators too.

**Status against Revision 34:** open, and new against this revision — consequent on #8, and a proposal.

---

## 11. `true` and `false` keep keyword status under base type resolution — does #7's argument reach them?

**Section:** §4.2 (boolean), §4.5 (resolution order), §7.7 rule 3; [TSON-SCHEMA] §7.3 and the `boolean` enum.

**Problem:** #7's argument against `null` applies to `true` and `false` in part. Under a schema they have no special
status either: `boolean` is the kernel's `!enum [true false]`, read as an identity check of the token's text against
the member names, and §7.3 says so. So they are keywords in exactly one mode, Class 1, and §7.7 rule 3 has to explain
that "there is no keyword list" while §4.5 keeps two exact keyword matches ahead of the number grammar.

The argument stops short, and the entry records where. A boolean is a value with a type that a Class 1 read
genuinely produces and a consumer genuinely stores, where `null` was a value nothing downstream could use; and the
distinction between `true` and `"true"` is the one place §2.4's "form is not meaning" makes form *mean* something —
"the string `true`, not the boolean" — which is the same distinction `42` and `"42"` draw and is not JSON's. Removing
them would leave Class 1 with no boolean at all, which is a loss, not a simplification.

**Interpretation chosen:** Revision 34 as written: `BaseTypeResolver` matches `true` and `false` before the number
grammar, and a schema-typed position hands the token to its declared type.

**Suggested resolution:** keep them, and say why in §4.2 in the terms above, so that #7's removal is not read as
half of a pattern. §4.5's order becomes boolean → number → string, and §7.7 rule 3 then has two words to explain
rather than three — a sentence, since both are members of a kernel enum and that is the whole of their status under
a schema.

**Status against Revision 34:** open, and new against this revision — recorded so that the decision is a decision;
this implementation recommends keeping them.

---

## 12. A near-miss numeric token falls through to string — should it be a Class 1 error instead?

**Section:** §4.3 (numbers, "leading zeros MUST NOT be used"), §4.4 (string, "including near-miss numeric forms such
as `007` and `1.2.3`"), §4.5, §7.6 (`decimal-natural`, "no leading zeros").

**Problem:** The leading-zero prohibition is RFC 8259's number grammar, and TSON's base resolution turned it — with
every other near-miss — into silent fallthrough: `007`, `1.2.3`, `5.` and `1__0` are strings. §4.4 is explicit that
"there are no exceptions: every string-resolving token is one whose complete text failed the null, boolean, and
number rules". The design is coherent — every token resolves to something, and a resolver never refuses — but the
outcome is the hazard #7 names for `null` at a `text` position, arriving on data that is common rather than
reflexive: a `007` postcode, a `1.2.3` version, a `5.` typo, each of which reads without complaint as the string the
author did not mean. With no JSON grammar to be a superset of, the question is open whether a token that *begins*
like a number and fails the grammar should be a string or a Class 1 resolver error, the way a token that begins
like a number and fails an atom's contract already is under a schema (§5.2).

The two answers are both defensible, which is why this is a decision to record rather than a proposal. Fallthrough
keeps §4 total and keeps `A-100`, `v1.2.3` and `2025-03-13` unquoted — a rule sharp enough to catch `007` has to say
why `v1.2.3` is not a near-miss, and "starts with a digit or a sign" is that rule's likely shape. An error makes the
common mistakes loud at the cost of that rule and of quoting `007` when the string is meant, which §4.5's "use
quotes" already asks for `null`.

**Interpretation chosen:** Revision 34 as written: `NumberScanner.decimalNatural` refuses a leading zero, the
`number` production fails, and `BaseTypeResolver` resolves the token to a string.

**Suggested resolution:** decide, and record the decision in §4.4 either way. If fallthrough stays, say in §4.4 that
it is deliberate and what a schema is for; if a near-miss becomes an error, define near-miss by the token's first
character (`Nd`, `+`, `-` or `.` at Start, the four extensions §7.1 admits for the number grammar's sake) so that
`v1.2.3` and `A-100` are untouched, and list `.` alone as the boundary case, since `.5` is a number and `.name` is
a string today.

**Status against Revision 34:** open, and new against this revision — a decision to record, with no recommendation
stronger than "not by default".

---

## 13. Records and maps share `{ }` because JSON objects do — is §2.8 worth its dispatch?

**Section:** §2.8 (brace disambiguation and empty braces), §2.5, §2.6; [TSON-SCHEMA] §7.7 ("Empty braces").

**Problem:** A record and a map share the brace form because a JSON object is `{ … }` and a record is what a JSON
object becomes. The whole of §2.8 exists to pay for that sharing: a parser consumes one data value and inspects the
next token to learn which structure it is in; the first field name is checked to be a bare token at that point and
nowhere else; an empty `{}` is neither and is "deferred to the resolver", where [TSON-SCHEMA] §7.7 resolves it by the
expected type; and a schema's own grammar imports the dispatch ([TSON-SCHEMA] §12.2 states its lookahead budget). A
distinct map delimiter would delete the section and the empty-brace concept with it.

**Interpretation chosen:** Revision 34 as written: `TsonDataStream` implements §2.8's dispatch with one consumed
token plus one of lookahead, `EmptyBrace` is a distinct event and AST node, and `RecordAbstractReader`,
`MapAbstractReader` and `TupleAbstractReader` each resolve it against their own type ([TSON-SCHEMA] §7.7), with
`SchemalessTreeReader` taking the empty record by default.

**Suggested resolution:** keep it, and this entry is the record that keeping it was decided rather than inherited.
`{ k => v }` reads well, the dispatch is one token deep and stated as such, and an empty brace resolving by expected
type is exactly right under a schema, which is the mode the format is for. The cost of a new delimiter — every map
in every document and schema, and a second bracket pair for authors to learn — is out of proportion to a section
that costs a parser a saved token. It is listed because it is the last place where JSON's shape is load-bearing in
the grammar, and the one item here where the recommendation is that the JSON-derived choice stands on its own
merits.

**Status against Revision 34:** open, and new against this revision — recorded as a decision to keep, pending the
author's confirmation.

---
