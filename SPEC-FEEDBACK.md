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

The nineteen below are what Revision 34 leaves open, renumbered from #1; the fourteen it resolved of the
seventeen raised against Revision 33 are gone from here, because the spec now carries their rules and that
is where the answer belongs. **This file is the as-built record**, not a pointer to one: where an entry
proposes a design this implementation has built, the entry states the design, what is running, and what is
not, so that a reviewer editing the spec needs nothing beside it. **Where the evidence is a consumer of this
library rather than this library** — #16 through #19 were found building the HTTP layer in
`ltr8-io-tson-java-http`, and this register is the collection point for all of it — the entry says so and
states what is running there on the same terms. **Cite the spec, not the argument that got
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

**Suggested resolution: leave it undefined, and this entry now recommends against the kernel declaration it
was opened to propose.** Declaring the lift targets would hard-code a resolver's internal template logic into
the shared type vocabulary. Leaving them undefined is what lets each implementation mint its internal
templates in the shape its own resolver requires — latitude the format grants today at no cost, and would be
spending for nothing.

The freedom being given up is real and §8.2 already grants it deliberately: synthetic names are
"resolver-chosen and fresh by construction ... and unreachable from source". An author never writes one,
never references one, and meets one only when reading resolved output. So the shape, arity and even the
existence of the lifted entry are an implementation's business today — this one alpha-normalises parameters
so two spellings land on one entry, and another may reasonably do something else. A kernel declaration makes
one implementation's bookkeeping normative for all of them.

Four reasons, in the order they bite:

1. **It is a category error.** The kernel is a type *vocabulary* — what types are, and what a constructor's
   fields hold. A lift target is *resolver machinery*: it exists because §5.3 needs somewhere to put a form
   the author wrote inline, and it describes no type the author was reasoning about. §9's `enum_set` is not
   the precedent it looks like, and the difference is the whole argument: `enum_set` is declared because
   `enum.members` — a kernel constructor's own field — must be typed something, so it is part of the
   vocabulary's shape and is referenced from inside the kernel. The proposed container templates would be
   referenced by nothing in the kernel and would exist only for user schemas to lift into.
2. **§10 makes it permanent.** The kernel is published, hash-pinned, and immutable at its identity. A
   template shape that turns out wrong — the wrong parameter order, the wrong treatment of `state`, a
   missing sibling — cannot be corrected without minting a new kernel version and re-stamping the digest
   chain through `meta.tn` and `core.tn`. That is a heavy price for an artifact no author writes, and it
   would be paid by every schema in existence.
3. **The family is not closed, so the mechanism would not be replaced, only doubled.** Only the fixed-arity
   forms could be declared: the size specifier's variants differ by which bounds are present (`[T; 3]`,
   `[T; 1..]`, `[T; 1..2]` are three shapes, an absent `max_items` not being a defaulted one), and `tuple`
   and `choice` are variadic, so `[T, U]` and `( T | error )` have no fixed-arity template at all. The lift
   rule stays for the rest, and a reader of resolved output must then know which forms are kernel-backed and
   which are minted — two mechanisms where there is one.
4. **Availability would need a new category of name.** A schema's type-name namespace is its own
   declarations plus its `!!import`s (§3.3.1, §2.2.3), and does not include the namespace its `!!meta`
   names. A kernel-declared `array_of` is therefore not in scope for a schema that has not imported the
   kernel, so either §5.3 names these as always-available regardless of import — a new scoping rule
   invented for an internal artifact — or desugaring stops being purely syntactic and starts depending on
   the import set, which is the property that makes it consult no governing meta and need no bootstrap
   special case.

Against all of that, the cost the problem statement names is bytes in resolved output and a little repetition
under a reader's eye. Nothing an author writes changes either way.

Note this was **never** a proposal to re-parameterize `array`/`set`/`map`: those stay de-parameterized
constructors with `element_type` as an ordinary field, and what was proposed was named templates *over* them.
That half of the design is settled and is not reopened by declining this.

**Status against Revision 34:** open as an observation, closed as a proposal — the repetition is real and the
kernel declaration is the wrong fix for it. A revision that wants to say something here should say that
synthetic entries are an implementation's own, which §8.2 already implies and could state outright: their
names are resolver-chosen, their shape is unconstrained beyond producing the required resolved output, and a
processor is free to mint, share or normalise them however it likes. This implementation mints per schema and
`ContainerSugarEndToEndTest` pins the resulting entry sets.

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
`TsonUnicodePolicy.dataVersion()`, and carried on the run rather than on each refusal (#14).
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

**Interpretation chosen — and built.** This implementation has removed `null`, and the description below is
of running code rather than of a design. `BaseTypeResolver` runs boolean → number → string; the unquoted token
`null` resolves to the string `null`; `VoidReader` admits `_` and nothing else; `ValueParser` has no null
inhabitant in either direction; `TsonDataEmitter` has no `null` to write, and a host `null` with no field to be
omitted from writes `_`. Nothing lexical moved: `null` was never a token class, so the lexer is untouched and
`_` keeps the token type, event and AST node it always had — which is also the sharpest form of the argument
below, since it means absence was never in the resolution order to begin with and removing `null` shortens the
order rather than replacing one entry in it.

What the implementation *models* is the other half of the evidence, and it needed no change at all: the read
output has **one** no-value node, `TsonAbsent`, carrying `_` and a collecting-mode read failure, with no
separate null node — because no consumer of the tree had a use for the difference, so there was nothing to
model. When the first implementation quietly merges two things the spec calls distinct, the spec is describing
a distinction it does not have.

One deletion is worth reporting because it was invisible until the change forced the question.
`DiscriminationClass` — the §5.4 discrimination classes untagged choice recovery dispatches on — carried a
`NULL` member that nothing could produce: a `unit` type has no class at all, so `void` never reached it, and
the only other route was a host `null` from base resolution. **No disjointness fact rested on it**, and
absence cannot be a discrimination class in any case, §5.4 refusing a `void` variant outright. §4 has three
scalar classes, and it turns out the fourth was never doing anything.

**Suggested resolution:** remove `null` from the notation. Concretely:

- Part 1: delete §4.1; §4.5's order becomes boolean → number → string; drop the "distinct from … null" clauses
  in §2.9 and §4.4 and the "use quotes" sentence; §7.7 rule 3 then holds without qualification. The JSON note
  under §9 changes from a mapping to a statement of scope: a JSON document containing `null` is not a TSON
  document, and a processor that reads JSON does so through a JSON reader that maps `null` to absence. That is
  a *softer* claim than the current SHOULD ("accept any valid JSON document"), and it should be made in those
  words rather than left as a silent narrowing.
- Part 2: `value` in §4.2 admits boolean, integer, float and string; `void`'s parenthetical and the §7.3
  concession paragraph go, `void` admitting `_` alone; §5.4's rationale for refusing `(T | void)` loses the
  "absent-versus-null" clause and gets simpler, not weaker; §9's restatement goes with it.

The one thing the removal changes for a document is that a bare `null` in schemaless data becomes the string
`null` rather than an error. It would be a mistake to guard that with a reserved word — a parse error on
unquoted `null` reintroduces exactly what §7.7 rule 3 removed, for the sake of one JSON habit that the JSON
reader is the right place to serve. The cost worth naming instead is the structured-output case: a model
emitting `null` by JSON reflex into a `text` position gets the string `null` silently, where an `int32`
position refuses it loudly. That case was already the behaviour under a schema in Revision 34, and it is the
case that argues for routing model output through a JSON reader rather than for a keyword in the notation.

**What is not built**, and is not this implementation's to build: the bundled schemas. `meta-kernel.tn`
documents `value`'s inhabitants as "null, boolean, integer, float, string" and `void`'s prose names `null` as
an accepted spelling, and both are now false — but the three schemas are Revision 34's published artifacts
with published digests, and stamping new ones ahead of the revision would mint digests for documents nobody
has published. They are untouched, and the divergence is behavioural rather than declared.

**Status against Revision 34:** open, and new against this revision — a proposal, and one this implementation
has now built and is running. It is built on a branch (`r2026-35-proposal`) rather than on `main`, which stays
the reference implementation of the published revision; the shared corpus has a branch of the same name,
carrying a resolver vector for `null` resolving to a string and a validate vector for `null` refused at a
`void` position.

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

**Interpretation chosen — and built.** This implementation has removed the superset claim and the four rules
that carried it, and the description below is of running code. `Lexer` no longer decodes `\/`; `\b`, `\f` and
`\s` stay; and the two `\u` forms are checked by one rule. A leading BOM is still discarded, now on §7.1's own
authority. §6 exception 1 needs nothing here — the lexer always implemented §7.2.2's rule, the exception being
prose about why TSON differs from RFC 8259 rather than a behaviour.

**Item 2, as built: both escape forms, one rule.** `\uXXXX` stays and is restricted to non-surrogate scalars;
`\u{1*6HEXDIG}` is added. They are two spellings of one number, and the check is the same for both — *the value
denoted must be a Unicode scalar value*. All three surrogate MUST clauses and every line of pairing logic are
gone: an escape names a character or it names nothing, and a document spelling an emoji as a surrogate pair now
gets two errors rather than one character.

The choice between adding the braced form and merely restricting the four-digit one is worth recording, because
minimality argues for the second and this implementation took the first. **Both delete the pairing rules**, so
this entry's own complaint cannot decide between them. What separates them is that restricting `\uXXXX` alone
*removes a capability Revision 34 has*: with no braced form and no pairs there is no way to escape a
supplementary character at all, only to embed the literal one. The concrete cost is plane 14 — the variation
selectors (U+E0100–U+E01EF) and tag characters (U+E0020–U+E007F) are invisible, legitimate document content,
and a four-hex-only format can express them only by embedding the invisible character. An ASCII-safe generator
loses the same ability, which pairs give it today.

**The braced form costs a production and no rule**, which is the shape worth putting to a reviewer: Revision 34
has one spelling plus three MUST clauses about how two escapes combine, and this has two spellings and one
predicate. The grammar gets simpler while gaining a form. There is no ambiguity — the `{` decides at the first
character after `u`. And two spellings of one scalar is not #7 in miniature: `null` and `_` had different
resolution paths and were answerable differently by a schema, where `\u0041` and `\u{41}` decode to the identical
scalar and nothing above the lexer can tell them apart — the relationship §4.3 already requires between `255`
and `0xFF`.

**Suggested resolution:** delete §6 and principle 5. Replace the JSON note under [TSON-SCHEMA] §9 with a
statement of scope: a JSON document is read through a JSON reader, which maps JSON `null` to absence and JSON
numbers to `number`, and is not a TSON document. Then:

- **Item 1**: delete `\/` from §7.2.2's table.
- **Item 2**: state the escape as `"\u" ( 4HEXDIG / "{" 1*6HEXDIG "}" )` with one constraint — the value denoted
  is a Unicode scalar value. §7.2.2's three surrogate MUST clauses and §6's second exception go with it.
  "TSON strings are well-formed Unicode scalar sequences" stops being a rule a lexer enforces and becomes a
  property the grammar cannot violate.
- **Item 3**: fold §6 exception 1 into §7.2.2, where it is the rule and not an exception.
- **Item 4**: restate BOM acceptance under §7.1 as an encoding courtesy on its own authority. RFC 8259 §8.1,
  where the posture comes from, has it as a MAY.
- **Item 5**: `\b` and `\f` stay. Dropping them is one more thing an existing document can trip over for no
  benefit, and the table was reviewed as a whole to say so.

**What is JSON-shaped and should stay**, so the removal is not read as a mandate to look different:
`"`-delimited strings; `[ ]` arrays; `{ name: value }` records; the `\n \r \t \\ \"` escapes; base type
resolution as a mechanism — Class 1 is a real mode (configuration, ad hoc data) and only `null` was an
accommodation; the `number` exact type and the rule that an unadorned numeric token names it; and, on the
implementation side, RFC 6901 pointers and JSON Schema 2020-12's output shape in diagnostics, which are tooling
interoperability and no part of the notation. The notation is JSON-*like* by design; what goes is the claim to
be a JSON *superset*, and the rules that only that claim required.

**Status against Revision 34:** open, and new against this revision — consequent on #7, and one this
implementation has now built and is running, on the `r2026-35-proposal` branch. It is the first change to rely
on principle 7 against §1.3's lexer freeze, which is why it wanted doing in the same revision as #7 rather than
after something is published against the frozen claim. Entries #9–#13 are the design choices JSON shaped that
are worth a decision of their own once the superset claim is gone; each is recorded separately because each can
be answered separately.

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

**Interpretation chosen — and built.** This implementation has made `field-name` an identifier position at
every layer, and the description below is of running code. `TsonDataStream.requireFieldName` matches the
decoded text against the identifier profile at both the record dispatch and the brace-disambiguation
lookahead, so `{ "first name": 1 }`, `{ "_id": 1 }` and `{ 42x: 2 }` are parse errors. The schema grammar
shares the production, so a schema's own field name meets the same rule at the same layer — where it used to
reach `record_field.name`'s declared type one phase later.

**The two spellings stay, and are two spellings of one name.** §7.4's production keeps `unquoted-token /
single-line-token`; what quoting buys is the lexical accidents of the unquoted form — a name that would
otherwise resolve as a number — and not a different set of names. The diagnostic names the remedy the format
already has: *a key that is not a name belongs in a map*, which is the one place this rule meets an author.

**Normalisation runs before the match, and the entry above does not say so.** `identifier` requires NFC as a
*form* and would refuse a decomposed name outright, where §2.5 gives a field name its identity by
NFC-normalised comparison — a decomposed spelling is the same name, so the pair is a duplicate-field error and
not a malformed one. The lexer already normalises the unquoted spelling, so requiring the form here would make
the quoted spelling the stricter of the two, which is the asymmetry this change exists to remove. **A
revision taking #9 should say which of the two rules governs**, since a reasonable implementer reads
"a field name is an identifier" as importing the form.

**Two consequences worth stating, because both change what a conforming processor refuses:**

- **`_id` stops being expressible in a record.** Identifier-Start is `XID_Start`, which excludes `_`, so a
  leading underscore was a Class 1 field name and never a declared one; after this it is neither. That is
  accepted deliberately rather than paid for by admitting `_` at Start: the profile is what every naming
  position in the series shares, and bending it in one position to keep one spelling writable is the wrong
  shape of answer. It is not the cheap road either — `_` is §2.9's absent sentinel and the lexer takes it
  greedily, so admitting it as a name also costs the rule that every identifier is a well-formed unquoted
  token. If a leading underscore is wanted it is a change to the profile for every naming position at once,
  argued on its own merits.
- **A Class 1 field name now meets all three §8.2 rules, not one.** It was the look-alike rule alone
  *because* the name was lexical; once it is a name, the restricted-character and restricted-script rules
  reach it exactly as they reach a type-ref or annotation name. **This refuses §8.2's own illustration**:
  `id_пользователя` is a compound mixing Latin and Cyrillic, so at the Highly Restrictive default the
  restriction level refuses it whole-name even though nothing collides with it — the section offers it as the
  lone name mechanism 1 does *not* catch, which stays true and is now beside the point. The per-segment unit,
  which §8.2 names as the first relaxation to reach for, admits it. **A revision taking #9 should either
  choose a single-script example or say that the relaxation is what the example assumes.**

The second of those has a testing consequence any implementation will meet: a vector isolating the look-alike
rule over field names needs two names each of which is single-script (`pass` against Cyrillic `раѕѕ`), because
a within-word homograph is refused by the restriction level before mechanism 1 has a pair to compare. A pair
written the obvious way passes for the wrong reason — a processor implementing only the script rule satisfies
it.

**Suggested resolution:** make `field-name` an identifier position at every layer — the production keeps its
two spellings, and the decoded text is matched against §7.7 as an annotation name's is, after NFC
normalisation. Consequences, all deletions: §2.5's "lexical" paragraph; §7.7's "record field names are lexical
at this layer" and rule 3's `"_"`/`"_id"` carve-out; §8.2's field-name distinction, so one walk polices every
named scope and [TSON-DATA] §1.5's Class 1 MUST stops needing to say which checks. A record whose key is not a
name is a parse error, and the diagnostic can say what the author wants: a map.

**Status against Revision 34:** open, and new against this revision — consequent on #8, and one this
implementation has now built and is running on the `r2026-35-proposal` branch. It was the one of #9–#13 this
implementation recommended taking, and building it turned up the two questions above, neither of which is
visible from the proposal alone.

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

**Interpretation chosen — and built.** This implementation keeps the comma and permits a trailing one, which
is (b) below rather than (a), the preference this entry used to state. `consumeSeparatorOrCloseCheck` now
answers *is there another element?* and the three container frames close on `false`, so a comma before a
closing delimiter ends the container instead of being refused.

**Why (a) was wrong, and it is a fact about the grammar rather than a matter of taste.** The comma is not
only a value separator: it is the delimiter in every type expression [TSON-SCHEMA] §12.1 writes —
`pair<uuid, B>`, `[text, int32]`, `<T, N>`, `vector<float32, 3>` — and those are not a second construct.
§12.1 parses tuple elements and type-argument lists through the *same* separator rule §2.4 states for a
record's fields, so `pair<uuid B>` also parses today and the comma everyone writes in a type expression is
§2.4's optional separator wearing a different hat. Dropping the comma therefore breaks §5.10's own worked
example, `uuid_pair => <B> pair<uuid, B>` — which meta-kernel.tn quotes verbatim in its own `@doc`. (a) has
to become (a′), *drop it as a value separator and keep it as a type-expression delimiter*, which is two rules
where §2.4 has one, and the token has to stay either way: a `,` removed from the token vocabulary makes a
pasted JSON array fail as "an unrecognised character" rather than with advice.

**The rule, as built: a comma may follow a value.** One clause decides every case and replaces both halves of
§2.4's current wording. `[1, 2, ]` is admitted (the comma follows a value; nothing follows it, and nothing
needs to); `[, 1]` and `[1, , 2]` are refused (a comma following nothing, and a comma following a comma). The
two refusals need no rule of their own and get none — a comma is not a value, so they fail as a missing one.

**A trailing comma cannot mean an absent element, which is why admitting it is safe here and is not safe in
JSON.** Absence is spellable and occupies a slot: `[1, 2, ]` is two elements and `[1 2 _]` is three, so there
is nothing for a stray comma to be confused with. RFC 8259's ban exists because that grammar has elision —
JavaScript's `[1, , 2]` is three elements with a hole, which makes a trailing comma genuinely ambiguous
between two elements and three. TSON has no elision, so the ambiguity the ban prevents cannot arise. The ban
was inherited from a grammar whose problem this format does not have. **That is the argument to put in §2.4**,
rather than authorial convenience.

The same fact settles the other three shapes, and it is worth stating that they were considered: the coherent
opposite position is *a comma is ignorable punctuation, admitted anywhere between values*, and it is refused
because it is not simpler (the whitespace-separation requirement stays either way, so it is a second concept
beside one rather than a replacement for one), because only the trailing position has an editing story —
appending a line — while nothing produces `[, 1]` as a byproduct of an edit that should have kept working,
and because a doubled comma is far more likely a lost element than deliberate noise. In a format whose whole
purpose is validating generated output, reading `[1, , 2]` as two elements is exactly the silent failure it
exists to catch, and in a format where the lost element is spellable as `_`, accepting it is what would make
`_` meaningless.

**Two defects in §2.4's wording, independent of the decision:**

- It says "a trailing **separator**", and no implementation can enforce that. A whitespace-only separator
  before a closing delimiter is already legal — `[1 2 ]` parses under Revision 34 — because a container's
  close check runs before the separator check. It is a trailing *comma* rule and should say so.
- "The rule applies throughout the series" is doing more work than it looks: it is what carries the decision
  into §12.1's tuple elements and type-argument lists, so `[text, int32, ]` and `pair<uuid, B, >` are legal
  under (b). Odd-looking, and accepted deliberately — the alternative is a comma meaning something different
  by position.

**Suggested resolution:** keep the comma, delete the trailing-separator ban, and state §2.4 as *values are
separated by whitespace, a comma, or both, and a comma may follow a value*. §7.4 is unchanged.

**Status against Revision 34:** open, and new against this revision — consequent on #8, and one this
implementation has now built and is running on the `r2026-35-proposal` branch. The entry's original
preference for (a) is withdrawn; the type-expression finding above is why, and is the part a revision needs
whichever way it goes.

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

**Interpretation chosen:** Revision 34 as written, and kept: `BaseTypeResolver` matches `true` and `false`
before the number grammar, and a schema-typed position hands the token to its declared type.

**The keywords are load-bearing for §5.4, which is the argument this entry was missing.** BOOLEAN is a
discrimination class only because §4.5 matches the two tokens ahead of the number grammar. Strip that and
`boolean`'s members resolve to strings, so by §5.4's own rule — "an enum's class is its members' shared class
(`[true false]` is boolean-class)" — the kernel's `boolean` becomes string-class and `( boolean | text )`
derives `disjoint: false`. Every boolean in an untagged choice would need a `!boolean` tag, and a derived
fact §5.4 requires every resolver to record would change under a Part 1 edit. That is demonstrable today,
since a word-valued enum is exactly what a keyword-less `boolean` would be: `status => !enum [OPEN DONE]`
beside `text` is refused — *"two of them occupy the same discrimination class ... every value keeps its
!variant tag"*. So the reasons this entry gave (a boolean is a value a consumer stores; `true` against
`"true"` is the distinction `42` and `"42"` already draw) are true but secondary.

**Base type resolution is not a disjoint choice, and the attempt to write it as one is instructive.**
`( boolean | number | text )` derives `disjoint: true` and reads every §4 shape, so it looks like a model of
Class 1 reading. It is not one, for two reasons:

- **A Class 1 value is one of three things** — an untyped token, a token carrying a built-in type annotation
  from §5's vocabulary, or a container — and §4 governs only the first, by its own applicability clause. The
  choice refuses the other two: a schemaless `!uuid "9f1c…"` is *"not a declared variant"*, and `{ a: 1 }`
  has *"no variant matching this untagged value"*.
- **It is circular.** §5.4 defines `disjoint` as "the encoding's own form resolution ... recovers the
  variant", and TSON text's form resolution *is* §4. The choice does not model base resolution; it consumes
  it, reproducing §4's partition because it is built from it and leaving the resolution order inside the
  class function where it started.

**Two §5.4 findings fell out of checking this, neither about `true`/`false`:**

1. **The derived fact is discriminability; the `@disjoint` prose says mutual exclusivity.** §5.4 requires the
   derivation be exactly class-distinctness and "MUST NOT prove more (value-set separation ... does not make
   a choice disjoint)". But it describes the annotation as recording "the intent that its variants are
   mutually exclusive", which is inhabitance. The two come apart on any choice containing a string-class
   atom: `text` admits every token (§2.4's *form is not meaning* means a declared `text` position takes `42`
   and `true` unquoted), so `42` inhabits both variants of `( int32 | text )` — which is nonetheless accepted
   as `@disjoint`, correctly. **Suggested:** §5.4's `@disjoint` paragraph should say the author asserts the
   variants are *distinguishable by the encoding's form resolution*, which is what is checked, and note that
   overlap in inhabitance is ordinary and expected.
2. **A token can be discriminated to a variant that refuses it, with no second chance.** §4's `number`
   production admits `0xFF`, `0b1010`, `0o377`, `.inf` and `.nan`; core.tn's `number` atom is decimal-only
   and refuses all five. So in `( number | text )` those tokens classify as NUMBER, dispatch to `number`, and
   fail — they cannot reach `text`, because §5.4's once-only rule forbids "a second, type-directed inspection
   of the value's form". The behaviour is exactly as specified and the trap is real: the natural reading of
   `( number | text )` is "a number or anything", and `0xFF` matches neither. Worth a sentence in §5.4 or in
   core.tn's own `@doc` — a constructor narrower than its base-type class is a footgun in any untagged
   choice, not only this one.

**Suggested resolution:** keep `true` and `false`, and say why in §4.2 in the terms above — the §5.4
dependency first, so that #7's removal is not read as half of a pattern whose other half would silently
change a derived fact. §4.5's order becomes boolean → number → string with #7, and §7.7 rule 3 then has two
words to explain rather than three, both members of a kernel enum, which is the whole of their status under a
schema.

**Status against Revision 34:** open, and new against this revision — a decision, recorded so that it is one.
This implementation recommends keeping them and has changed nothing. Finding 1 above is a §5.4 wording
question rather than a `true`/`false` question and may want an entry of its own once the revision opens.

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

**Interpretation chosen:** Revision 34 as written, and kept: `NumberScanner.decimalNatural` refuses a
leading zero, the `number` production fails, and `BaseTypeResolver` resolves the token to a string.

**The boundary rule this entry proposed does not survive contact with real tokens, which is what turns "no
recommendation" into one.** Defining near-miss by the token's first character catches far more than the
typos it was aimed at — eight of these ten begin with a digit, and all ten resolve to string today:

| token | digit-initial | what it is |
|---|---|---|
| `2025-03-13` | yes | a date |
| `9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09` | yes | a UUID |
| `192.168.0.1` | yes | an IPv4 address |
| `2h30m` | yes | a duration |
| `1.2.3` | yes | a version |
| `007` | yes | a postcode |
| `5.` | yes | a typo |
| `1__0` | yes | a typo |
| `v1.2.3` | no | a version |
| `A-100` | no | a part number |

A date, a bare UUID, an address and a duration written unquoted would all become Class 1 errors — and those
are the unquoted forms the format encourages, the ones §5's vocabulary exists to type when a schema is in
scope. `2025-03-13` settles it: no digit-initial rule can admit the most ordinary unquoted token in
configuration data while refusing `007`. Sharpening it means enumerating shapes, which is §5's vocabulary
restated inside §4 as exceptions, and §4.4's "there are no exceptions" is worth more than four typos.

**And Class 1 already has a way to say what a token means: the annotation.** A schemaless document writes
`!date 2025-03-13`, `!uuid 9f1c…`, `!int32 007` — §5's vocabulary, available with no schema and checked. So
the fall-through is not the format's answer to "what is this token?"; it is the default for a token the
author chose not to annotate, and refusing it adds no information. The same observation is why base
resolution cannot be modelled as a choice (#11): an untyped token is one of three things a Class 1 value can
be, and §4 governs only that one.

**The third option, and the one worth stating now that §6 is gone: should the leading-zero rule be dropped
rather than kept, so `007` is the number 7?** That is the widening question the superset's removal invites,
and the answer is no — but the *reason* changes, and it is the one thing here a revision should edit. JSON's
reason is C-style octal ambiguity, and TSON has no such ambiguity: `0o377` spells octal explicitly, so the
inherited justification is gone. The rule survives on a better one. **A zero-padded token is data whose
leading zeros are significant** — a postcode, an identifier, a zero-padded code — so reading `007` as `7`
destroys information irrecoverably, where reading it as the string `007` preserves exactly what was written.
That is also what makes the fall-through *correct* rather than merely total: `007` is a string because it is
one. Nothing else in the production is a JSON debt — `+255`, `.5`, `1_000`, `0xFF`, `0b1010`, `0o377`,
`.inf`, `.nan` and `.infinity` are all admitted already and all exceed RFC 8259 — so "no leading zeros" and
"digits required after `.`" are the only two rules the superset's removal puts in question, and both should
stay.

**Suggested resolution:** keep the fall-through and say in §4.4 that it is deliberate, naming the two
mechanisms that do know what a token means — a `!`-annotation from §5's vocabulary in Class 1, a declared
type in Class 2 — so a reader meets the remedy where they meet the hazard. Restate §4.3's leading-zero
prohibition on its own authority, in the terms above, rather than as an inherited number-grammar rule: it is
the sentence that explains why `007` resolves to a string instead of reading as an accident of the order.

**Status against Revision 34:** open, and new against this revision — now a recommendation rather than a
decision to record. This implementation has changed nothing; what changed is the evidence, and the boundary
table above is the part a revision would otherwise have to discover for itself.

---

## 13. Records and maps share `{ }` because JSON objects do — is §2.8 worth its dispatch?

**Section:** §2.8 (brace disambiguation and empty braces), §2.5, §2.6; [TSON-SCHEMA] §7.7 ("Empty braces").

**Problem:** A record and a map share the brace form because a JSON object is `{ … }` and a record is what a JSON
object becomes. The whole of §2.8 exists to pay for that sharing: a parser consumes one data value and inspects the
next token to learn which structure it is in; the first field name is checked at that point and nowhere else; an
empty `{}` is neither and is "deferred to the resolver", where [TSON-SCHEMA] §7.7 resolves it by the expected type;
and a schema's own grammar imports the dispatch ([TSON-SCHEMA] §12.2 states its lookahead budget). A distinct map
delimiter would delete the section and the empty-brace concept with it.

**Interpretation chosen:** Revision 34 as written, and kept. `TsonDataStream` implements §2.8's dispatch with one
consumed token plus one of lookahead, `EmptyBrace` is a distinct event and AST node, and `RecordAbstractReader`,
`MapAbstractReader` and `TupleAbstractReader` each resolve it against their own type ([TSON-SCHEMA] §7.7), with
`SchemalessTreeReader` taking the empty record by default.

One detail of the dispatch moved with #9 and is worth stating, since this entry is where its cost is accounted:
the first field name is now matched against the identifier profile at that position rather than merely checked to
be a bare token. The lookahead budget is unchanged — the same one consumed token plus one — and so is §12.2's
statement of it; what the dispatch does with the token it already holds is a little more.

**Suggested resolution:** keep it. `{ k => v }` reads well, the dispatch is one token deep and stated as such, and
an empty brace resolving by expected type is exactly right under a schema, which is the mode the format is for. The
cost of a new delimiter — every map in every document and schema, and a second bracket pair for authors to learn —
is out of proportion to a section that costs a parser a saved token.

It is listed because it is the last place where JSON's shape is load-bearing in the grammar, and it is the one item
of #9–#13 where the recommendation is that the JSON-derived choice stands on its own merits. That is worth stating
positively rather than as an omission: the other four were kept or removed on reasons that survived the superset
claim, and this one is kept because a shared brace was a good idea independently of where it came from. **A
revision should say so in §2.8**, so that a later reader does not find the last JSON-shaped rule in the grammar and
assume it was missed.

**Status against Revision 34:** closed against this implementation, open as spec feedback — a decision to keep,
now confirmed, with nothing built and nothing to build. The whole of the JSON cluster #8 opened is answered:
#9 and #10 changed the grammar, #11 and #12 kept it with better reasons, and this keeps it unchanged and unargued
against.

---

## 14. §8.2 puts the data version on each refusal and §8.1 puts a refusal on a channel of its own — both bill the sender for a round trip

**Section:** [TSON-DATA] §8.2 (name hygiene: "a conforming processor MUST name the UTS #39 data version in the
refusal"), §8.1 (the four error categories, and the fifth outcome §8.2 requires be kept out of them).

**Problem:** §8.2 asks a refusal to carry a fact that does not belong to it. The Unicode data version is a property
of the *processor* — the tables compiled into it — not of the problem it found. Three things follow, and each one
is a cost paid by the party the report exists to help:

- **Cardinality.** The version is constant for the life of a process. Twenty refusals in one document carry twenty
  copies of a string that cannot differ, and a reader given twenty copies of one value has to decide what it would
  mean if they ever disagreed.
- **Time.** A component on a refusal exists only once something has been refused. What a sender needs in order not
  to be refused is the same fact *before* it writes the document. §8.2 mandates the copy that arrives too late and
  says nothing about the one that would have arrived in time.
- **Direction.** The version says what refused you; it does not say what would be accepted. `16.0` is not something
  a generator can act on. `ASCII_ONLY`, or `HIGHLY_RESTRICTIVE per segment permitting [Latin+Cyrillic]`, is — and
  §8.2 requires none of it, though it is the half that explains a disagreement between two deployments. Two
  processors at one UCD version routinely disagree, because the level is a local choice; two at different versions
  rarely do.

The fifth outcome is the same cost in the other channel. §8.2 says a refusal MUST NOT be reported in any of §8.1's
four categories, which is right about the taxonomy — the four sort by which layer found a problem, and a refusal is
not found by a layer — but a consumer reads a report to repair a document, and a repair channel split in two is
repaired in two passes. For the use TSON is being built for, an agent generating a document against a schema, that
is a second round trip bought with nothing: the refusal and the ordinary errors want the same edit pass, and the
distinction §8.2 is protecting is carried perfectly well by *which rule refused*, which the report has to state
anyway.

**Interpretation chosen:** a refusal is reported like any other rejection, and the configuration is stated once.

- **The refusal is an ordinary diagnostic**, told apart by its code — `CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER`,
  `RESTRICTED_SCRIPT`, one per §8.2 rule, since the three want three different remedies and the code is what a
  consumer routes on. It carries no component of its own, and it reaches the caller in the same single list, in the
  same pass, as every other problem with the document. §8.2's separation survives where it is a claim about
  *validity*: nothing here says a refused document is malformed, and the conformance corpus's `refused` vectors
  still assert that a refused document reports nothing under the four categories.
- **The version and the policies are one value** — level, unit (whole-name or per `_`/`-` segment), the script
  combinations admitted over and above the level, for each of the two surfaces §8.2 defines, plus the UCD version
  (#6) — stated once on the run or response that carries the diagnostics, and reachable with no document in hand at
  all: `Tson.processorPolicy()`, either read facade's `processorPolicy()`, and `tson policy` on the command line,
  which prints it as text, JSON, or a TSON document governed by this project's own schema.

That last surface is the one that changes the economics, and it is why this entry is not merely tidying. A sender
that reads the policy before it writes never writes the name that would be refused. A sender that learns it from a
refusal has already spent the round trip the format exists to avoid.

**Suggested resolution** (a proposal — the implementation above is running, the spec wording below is not):

1. Restate §8.2's MUST as a property of the *report* rather than of the refusal: a processor MUST make available,
   with any report containing a refusal, the data version and the policy under which it was computed, and SHOULD
   make both available independently of any report. Requiring the policy is the substantive addition: it is what a
   sender acts on, and today §8.2 requires only the half that a sender cannot.
2. Drop the MUST NOT in §8.1. Let a refusal be reported alongside the four categories, distinguished by the rule
   that refused, and keep the normative content that actually matters — that a refusal is not a claim that the
   document is invalid, and that a conforming processor may legitimately not refuse at all.

**Status against Revision 34:** open, and new against this revision.

---

## 15. §8.2 coins "name policy" for a thing §7.7 already calls an identifier — and neither it nor "token policy" is defined

**Section:** [TSON-DATA] §8.2 (name hygiene, the "Values" paragraph), §7.7 (identifier grammar).

**Problem:** §8.2's "Values" paragraph uses two terms as though they had been defined: "a token policy stricter
than the name policy subsumes it — a name is a token — and an implementation's documentation SHOULD say so."
Neither *name policy* nor *token policy* appears anywhere else in the series. §8.2 otherwise speaks of three
**mechanisms**, a **level** and a **unit**; it never names the configurable object those settings belong to,
although the sentence that coins the terms places a SHOULD on implementations to document a relation *between two
such objects*. An implementation obeying that SHOULD has to name them, and the only naming the specification
offers is a phrase used once and defined nowhere.

The pairing is also drawn from two different axes. §7.7 opens: "An **identifier** is a name: the decoded text of a
token — after unquoting, escape processing, and normalization — occupying a naming position." So *identifier* is
the defined term for a name in a naming position, and *token* is the defined term for the other surface; *name* is
the informal gloss inside that definition. "Name policy" beside "token policy" therefore takes one term from a
gloss and its sibling from a lexical category, when the specification already has two lexical categories that pair
exactly.

**This is not a request to flatten §8.2's own distinction**, which is load-bearing and correct: "The identifier
grammar (§7.7) decides which texts are names; it does not decide whether two names that are both well-formed can
be told apart by a reader." Mechanisms 1–3 constrain *names* and should keep saying so. At issue is only the pair
of terms given to the two configurable policies.

**Interpretation chosen:** *identifier policy* and *token policy*, one vocabulary from configuration to wire.
`TsonConfig.identifierPolicy`/`tokenPolicy` configure them; `TsonTreeReader`/`TsonObjectReader` derive them with
`withIdentifierPolicy`/`withTokenPolicy`; `TsonUnicodeProcessorPolicy(identifierPolicy, tokenPolicy,
unicodeDataVersion)` reports them; and the CLI's own `policy` record spells them `identifier_policy` and
`token_policy`. §8.2's documentation SHOULD is met in `docs/readers-and-diagnostics.md`, which states the
subsumption and records that the specification's word there is "name".

**Suggested resolution** (a proposal; the naming above is running code, the wording below is not): define both
terms where the "Values" paragraph first uses them, and prefer *identifier policy* for the first — the term §7.7
already defines for exactly that surface. Something of the shape: "A processor's configuration for this section
has two parts: the **identifier policy** — mechanisms 1 and 2, and the level and unit of mechanism 3, applied at
identifier positions (§7.7) — and the **token policy**, a restriction level applied to every token off the stream.
Because such a check runs before anything knows which tokens are names, a token policy stricter than the identifier
policy subsumes it." Every other use of "name" in §8.2 stands.

**Why it is worth the edit rather than being left to implementations:** #14 proposes that a report state the policy
it was judged under. If that lands, these two terms stop being prose and become field names on a wire that two
implementations are meant to agree about — and each will have picked its own, from a phrase the specification used
once and never defined.

**Status against Revision 34:** open, and new against this revision.

---

## 16. §8.2's policy has no artifact, and the two obvious homes are both wrong

**Section:** [TSON-DATA] §8.2 (name hygiene), with consequences for [TSON-SCHEMA] §3.5 (schema immutability)
and [TSON-DATA] §2.2.1 (canonical identity).

**Problem:** Revision 34 makes name hygiene a policy layer that MUST be implemented and is enforced by
default, with a restriction level, a unit, and an optional script set — and says nothing about where that
configuration lives or how a counterparty learns it. The series now has a security control with no artifact.
That would be a reasonable thing for a data format to leave alone, except that §8.2 also makes a refusal a
fifth, distinguishable outcome reported "under a stated policy and a stated data version", which presumes the
policy is something nameable. It is worth saying what it may not be, at least.

**It may not be the schema**, and orthogonality is not the reason. Two stronger ones:

- **Self-certification.** If a schema declared its own strictness, the artifact being checked would choose the
  check, and a homograph-laden schema would declare the level that admits it. A policy the subject selects is
  a preference.
- **Immutability.** §3.5 makes a published schema immutable and §2.2.1 lets it be hash-pinned, while strictness
  must move — `confusables.txt` updates, threat models change, a service starts rendering values it used only
  to log. Raising a policy would mint a new identity, and every document pinning the old one would keep the old
  policy for good. Nobody raises a control that costs that.

A third reason is specific to §8.3's own table: **skeleton distinctness does not compose across `!!import`**.
The policy is therefore not a property of one schema at all but of the merged namespace at the importing site,
and no schema is in a position to declare it.

**Nor an API description**, which in the consuming project is itself a schema governed by a meta layer and so
inherits both objections whole. It also puts policy in a *contract*: raising a token policy would mean
publishing a new description, which is the friction that gets a control switched off.

**What is missing is a third artifact kind, and it already has a homeless occupant.** §2.2.1 evicted the port
from identity — "no port (default or otherwise)" — and never said where location went. A **deployment
descriptor** is what that has been trying to be: location, fetch allow-lists and host mappings, and the two
§8.2 policies. It should be **data, not a schema**, and that line is worth stating in the series: an API
description must be a schema because `request: order` is a type reference the resolver resolves (§4.1's `data`
kind, §9's `type_ref` rule), where a deployment descriptor references no types — a level is an enum member, a
host is text, and even a per-schema policy holds *identities*, which are URIs.

| Artifact | Kind | Shared with counterparties | Immutable |
|---|---|---|---|
| Schema | schema | yes, by identity | yes (§3.5) |
| API description | schema (holds type refs) | yes, by identity | yes |
| Deployment descriptor | **data** (holds no type refs) | no — see discovery below | **no** |

**Two constraints would have to be normative, or self-certification returns by the back door.** *Named at the
call site, never discovered* — a runtime that loads whatever descriptor is on its path lets a container image
swap change a security policy with no code diff. And *never resolvable by identity* — no `!!import` of a
descriptor and no document able to name one, since the moment a document can point at one it selects its own
enforcement level.

**Discovery is the half a format can usefully standardise.** A counterparty has a legitimate question — what
will this endpoint accept? — and three answers with different standing. **The refusal is the authority**, being
the only report that cannot be stale, which is presumably why §8.2 puts the policy there. **A `.well-known`
path (RFC 8615) for the origin's acceptance profile** is the neat one: in this series everything with an
identity is served at its identity's path, and a deployment descriptor is precisely the artifact that must
*not* have an identity, so a well-known path is the right shape for it for the same reason it is the wrong
shape for a schema — but what is published there must be a *projection*, since fetch allow-lists and host
mappings are internal topology. **Not the API description**, which advertises a mutable policy from an
immutable artifact. Per-endpoint policy is the awkward case, a well-known document being origin-scoped: the
honest answer is probably that the profile advertises the origin's default and the refusal reports what
actually applied.

**Interpretation chosen:** both policies are code calls on `TsonConfig` (`identifierPolicy`, `tokenPolicy`),
with no artifact of any kind, and the consuming HTTP project leaves them at this library's defaults with its
position written down in prose rather than expressed in a document. **The reporting half is no longer open
here**: what §8.2 requires a refusal to name is now a machine-readable value on the run or response that
carries the diagnostics (`TsonUnicodeProcessorPolicy`, and `policy` on the CLI's own envelopes) rather than
prose in a message — #14 has that argument and what it changed.

**Suggested resolution** (a proposal — the reporting half above is running, the artifact below is not): name
the third artifact kind, say that it is data rather than a schema and why, and make the two constraints
normative. Failing that, at minimum say in §8.2 that the policy is *not* a property of a schema and not
carried by one, which is the half that stops an implementer reaching for the wrong home.

**Status against Revision 34:** open, and new against this revision — Revision 34 is what introduced the
policy layer that has nowhere to live.

---

## 17. A document that cannot carry `!!schema` has no way to name the schema that governs it

**Section:** [TSON-DATA] §6 (JSON compatibility) and §7.1 (encoding, normalization, and media type), with
§2.2.1 (canonical identity) for the conflict rule.

**Problem:** §6 makes every valid JSON document a valid TSON document, and the format's stated target use is
validating generated structured output against a schema. But `!!schema` is TSON directive syntax and a JSON
document cannot carry one — so across the entire JSON-compatible surface there is no in-band way to say which
schema governs the document. §7.1 already legislates for HTTP (`application/tson; version=1`, "if
disambiguation is needed in HTTP contexts") and stops exactly before the parameter that would answer this.

**A stronger reason turned up than JSON compatibility**, building version routing: an intermediary routing
between two servers by schema cannot parse the body to find out which one. nginx, Envoy, API gateways and CDNs
route on headers and paths and none of them parse bodies — that is a layering violation before it is anything
else — and `Content-Encoding: gzip` makes it impossible rather than merely rude. The honest limit is that a
header does not save the *origin* from peeking, since if header and body can disagree the endpoint must still
read the directive to check; the saving is at the network, and at a JSON body, where the header is the only
possible source and there is nothing to check against. CloudEvents is the precedent: `dataschema` is a context
attribute that its HTTP binding maps to a `ce-dataschema` header precisely so intermediaries can handle a
message without opening it.

**Interpretation chosen:** the consuming HTTP project implements the header as `TSON-Schema` and treats it as
a *projection* of `!!schema` rather than an alternative to it — an RFC 9651 structured field whose Item is an
**sf-string**, so the value is quoted, which also matches `!!schema`, whose argument must be quoted for the
same reason (a URI contains `:` and `/` and falls outside §7.1's unquoted-token profile). It may appear
alongside the directive, and the two must then agree by canonical identity (§2.2.1 — scheme and any `?sha256=`
pin do not count). It is defined for a body of any media type, which is what gives a JSON payload a channel at
all. A body naming no schema by either channel stays schemaless Class 1 and valid TSON; rejecting one is
**endpoint policy**, not a property of the media type. `TsonSchemaVersions` refuses a document that names no
version rather than guessing one. A companion `TSON-Accept-Schema` — an sf-list of sf-strings with `;q=`,
`Accept` to the first field's `Content-Type` — carries which versions a client can read *back*, a second field
rather than a second meaning because one message routinely asks both at once.

**Suggested resolution:** define the field in the series, or say why not. Four points are worth carrying
whatever is decided:

1. **The conflict rule has a precedent in this same spec and should follow it.** §2.2.1 on content hashes:
   "two that declare different hashes are in conflict — at most one describes the real bytes — and a consumer
   that observes both MUST report an error rather than choosing between them." A header and a directive naming
   different schemas is the same situation, and silent precedence is how a document gets validated against a
   schema nobody intended.
2. **sf-string, not sf-token, and the quotes are load-bearing in a way testing will not reveal.** RFC 9651's
   `sf-token` production is `( ALPHA / "*" ) *( tchar / ":" / "/" )`, which an unpinned `https://` URL
   satisfies completely — so a loosely defined field parses fine in every test anyone writes, and then someone
   pins a schema: `?sha256=…` contains `?` and `=`, neither a tchar, and the unquoted form stops parsing for
   exactly the references §2.2.1 encourages as the strongest integrity control.
3. **Naming has a defined procedure**: RFC 9110 §16.3's field-name registry, which admits *provisional*
   registration on expert review — suitable for a working revision — and RFC 6648, which rules out
   `X-TSON-Schema` as a BCP rather than a style opinion. `Content-Schema` claims general-purpose territory for
   a whole-industry concern; `ce-dataschema` asserts the message is a CloudEvent, which a plain TSON request
   is not. Registering a field name alongside the `application/tson` media type the spec already intends to
   register is coherent rather than extra machinery.
4. **What it must not become**: a way to validate a document against a schema its author did not choose. The
   field states what the *sender* claims governs the body; it is not an instruction to the receiver to apply a
   schema of its own choosing to an unmarked document, which is how a payload gets interpreted under a
   contract nobody agreed to.

**Status against Revision 34:** open. This revision left §6 alone and rewrote §7.1 around the identifier layer
rather than the media type, so neither gained a way to name a governing schema out of band.

---

## 18. No shorthand for a template application at a `type_ref` slot in data

**Section:** [TSON-SCHEMA] §5.6 (the positional form) and §8.1 (`type_ref`'s canonical form).

**Problem:** The meta-kernel's `type_ref` is explicit and this implementation matches it: at a `type_ref`-typed
slot a bare token fills `name`, and a braced record is the explicit form, canonical output using the bare token
whenever `arguments` is absent. So a *schema* can write `page<order>`, but a **data** payload at a `type_ref`
slot — an `!operation { … }` governed by a consumer's meta layer — must write

```tson
body: { name: page  arguments: [ { name: order } ] }
```

because `page<order>` in that position is a *parse* error (`adjacent values must be separated by whitespace, a
comma, or both`), `<` never being data syntax.

**This is by design and the spec is not wrong.** What is worth raising is whether the design is intended to
cost this much at the one place it now shows up. §5.6's positional form was written for the argument-free case,
and the `data` base kind has since created a class of documents — data-in-a-schema, describing types — where
the *with-arguments* case is routine rather than exotic. An API description applying `page<order>` at four
endpoints writes the braced form four times, or names four aliases.

Worth reading alongside it, because it answers a neighbouring question and is easily mistaken for this one:
§8.1 explains why the *arguments* are braced — `type_argument` has no REQUIRED field, so a bare token cannot
self-classify as reference or literal and its braced record is load-bearing rather than ceremony. That is
sound, and it is one level down from the cost reported here, which is the **application** at the `type_ref`
slot, where `name` is REQUIRED and the positional form does apply in a schema and cannot be written in data.

**Interpretation chosen:** the explicit braced record, as the kernel requires. Measured in the consuming HTTP
project, whose `UpstreamGapsTest.aTemplateApplicationAtATypeRefSlotInDataNeedsTheBracedForm` asserts both
spellings — the braced record resolves, the sugar does not parse.

**Suggested resolution**, in preference order:

1. **Leave it, and say so.** Add a sentence to §8.1 noting that the sugar is schema syntax only, so a
   data-position reference with arguments uses the explicit record. Costs nothing and stops the next
   implementer discovering it by parse error, which is how it was found.
2. **Recommend the alias.** `order_page => page<order>` is one line, reads better than either alternative, and
   gives the application an identity. If that is the intended answer, §8.2 is the place to say so. One
   diagnostic point comes with it: a bad argument in the *alias* form is reported against the entry the
   template materialised (`'array_no_such_eb84587b' element_type has an unresolved reference 'no_such'`) where
   the inline form names the operation — so if (2) is the recommended spelling, that message is the one to
   improve, the author having written `order_page => page<no_such>` and been shown a synthetic name they have
   never seen.
3. **Extend the sugar to data position.** Real ergonomics, and a real cost: `<` becomes meaningful in data, at
   exactly one slot type, decided by the governing schema. Probably not worth it — noted for completeness
   rather than recommended, and it would have to reach a record §8.1 argues must stay braced.

**Status against Revision 34:** open. This revision reworked §8.1 heavily — held bodies, `reference.target`
widened to a `type_ref` — and left the positional-form paragraph byte-identical.

---

## 19. A namespace should be a value — the kernel's 2×2 has an empty cell

**Section:** [TSON-SCHEMA] §2.1 (the schema body is `map<type_name, type_definition>`), §2.2.3 (the flat
namespace), §4.1 (kinds, and the `data` kind's motivating case), §5.7–§5.9 (the three operators), §5.10
(templates), §8 (resolver output); [TSON-DATA] §2.6 (map keys are values), §7.7 (identifier grammar).

**This is a proposal, not a defect report.** Everything below is a design the author may well not take; it is
recorded because it was arrived at by measurement, it explains several open items at once, and the argument is
easier to weigh written down than reconstructed. The spec is internally consistent on every point it touches.

**The hit.** A service wants to declare a method once, on an interface, and bind it to HTTP in a separate
declaration — possibly a separate document — that *refers* to it:

```
orders-1.tn      place_order  => !method { request: order  response: order }
orders-api-1.tn  create_order => !binding { method: place_order  verb: POST  path: "/orders" }
```

That second line needs one entry to name another, and §4.1 makes a `kind: DATA` entry something that can be
declared and applied but never named — field type, element type, variant, argument, composition operand,
refinement source, all refused. So the kind introduced for exactly this case (§4.1: "an HTTP operation binding
request and response types by name is the motivating case") has no reference form, and the binding can only
name its method as a `type_name` token the resolver treats as data: `method: plaec_order` resolves clean and is
caught by nothing but the consumer.

**A method is better as a type, and that is the first sign.** Modelled under plain meta.tn, with no meta layer
and no `~` at all:

```
service-1.tn   method => <Req, Resp> { request: Req  response: Resp?  safe: boolean ~ false  idempotent: boolean ~ false }
               http   => { verb: http_verb  path: text  status: status_code ~ 200 }
orders-1.tn    place_order  => method<order, order> & { errors: [sku_not_found]? }
orders-api.tn  create_order => place_order & http & { verb: = POST  path: = "/orders"  status: = 201 }
```

Measured: `create_order` resolves with `supertypes: [place_order, method<order, order>, http]` and `verb`,
`path`, `status` as `REQUIRED_FIXED`; `!create_order { request: { sku: A-100  quantity: 2 } }` reads as a valid
value; the same value with `verb: GET` is refused. The operation IS-A its method, the compiler checks the
reference, and a plan step is a value of the method type — the thing a `data` entry can never be. One rule met
on the way is correct and worth a sentence in §5.8: `place_order => method<order, order>` alone is an alias to
an instantiation and has no vocabulary body to compose with; it needs a trailing `& { … }`.

So the motivating case for `data` is served *better* by a record type. Either the kind needs a reference form,
or the case does not need the kind — and the second reading opens onto something larger.

**The missing primitive, in a 2×2 the kernel already three-quarters fills:**

| | values are **data** | values are **declarations** |
|---|---|---|
| keys are **names** | record — `{ name: value }` | schema — `{ name => type }` |
| keys are **data** | map — `{ key => value }` | **empty** — `{ "/orders" => type }` |

What a service description wants is the fourth cell: a **keyed set of declarations whose keys are values**.
The primitive is one thing — **a namespace is a value**, with a key type, a member bound, and a scope, of which
`schema` is the instance with key type `type_name`, member bound `top`, and the document as its scope. Then
`interface => !namespace { member: method }` and `api => !namespace { key_type: route member: resource }` —
OpenAPI's paths → verbs → operation structure arrived at from the key types rather than copied. A body would be
a record, a binding, a choice, *or a namespace*: a new body kind, not a new entry kind.

**Four things fall out, and together they are the argument.**

1. **Referenceability follows the key type, not the kind.** A member of a `type_name`-keyed namespace is a type
   one can name; a member of a route-keyed one is anonymous and does not need a name — HTTP addresses it by
   route. That removes the invented operation name beside the method, and dissolves the question of minting an
   identifier from a path: a key that is data was never required to be an identifier.
2. **The three operators already mean the right things.** `&` on records is "merge disjoint keyed sets, then
   add" — on namespaces that is `extends`. `^` is "tighten members in place" — pin `idempotent` across an
   interface. `-` is "remove members" — a subset exposure that today has no spelling at all. When all three
   acquire an obvious, useful meaning on a construct without being redefined, the construct is usually right.
   A record is the namespace whose key type is `field_name`.
3. **Templates over namespaces are the payoff at the right level.** `crud => <T> !interface { create =>
   method<T, T>  get => method<id, T> }` and `orders => crud<order>` — legal because the members are types and
   the application materialises a namespace. The repetition an API description suffers is per *interface*, and
   that is where the template belongs.
4. **The `data` kind may have nothing left to do.** With methods and operations as types and groupings as
   namespaces, the one case §4.1 names for `data` is covered. Worth confirming as a consequence rather than
   assuming as a premise — the part of this most likely to be wrong.

**The costs, each a decision only the author can make.** A **third grammar recursion point**: §1 says the
schema grammar imports the value grammar at exactly two points, deliberately, and a constructor payload
admitting a declaration block is a third, in the other direction — worth stating as a principle change rather
than letting in quietly. **Scoping**: lexical resolution outward, qualified names inward, which [TSON-DATA]
§7.7 does not admit today (`identifier-continue = XID_Continue / "-"`, no `.`), so a `qualified-name`
production at type-ref and `!name` positions is the small version; §2.2.3's flat rule becomes "one qualified
name denotes one type", and §8.2's skeleton distinctness becomes per-scope, which §8.3 already half-says by
declining to compose it across `!!import`. **Imports flat or named**: the minimal design keeps `!!import` flat
and scopes only declared blocks, where the full design makes every import a named namespace, which is a module
system and a separate decision. **Resolver output goes recursive**: keep the nesting, since a router iterating
a route-keyed map *is* the point, and §1.3's closed-entry guarantee holds per scope as it holds per document
today. **What is a route key**: a structured key (§2.6 already admits any value) or two nested levels with
simple key types — nested is cleaner, matches how HTTP is organised, and means an `http` record loses `verb`
and `path` as fields because the keys carry them, which answers the one smell the method-as-type measurement
showed: schema facts declared as fields are injected into every instance, and a plan step should not carry its
own URL.

**Interpretation chosen:** nothing that presumes the answer. The consuming project's description stays a schema
under a `~data &` meta layer, a two-declaration binding names its method by `type_name` with the reader
checking it at startup, and the method-as-type shape is measured and kept as a probe rather than adopted.

**Suggested resolution:** none requested — a direction rather than a request, filed so the 2×2 and the operator
argument are on record where the next revision is designed. The two are what make the primitive look inevitable
rather than added.

**Status against Revision 34:** open, and new against this revision.

---

## 20. §5.10 makes an ungrounded parameter an error, but its kind is forced rather than unknown

**Section:** [TSON-SCHEMA] §5.10 ("Two parameter kinds, inferred by use"), §8.1 (`reference.target` is a
`type_ref`), meta-kernel's `type_argument`.

**Problem:** §5.10 infers a parameter's kind from where it is used, and then adds:

> a parameter whose kind is grounded only in mutual recursion between templates, with no concrete
> kind-determining use, is likewise a resolver error.

The premise of that rule is that such a parameter has no kind. It has exactly one. §5.10 defines a value
parameter as one "used in value positions — routed or defaulted into a field, or standing in a scalar slot of
a held constructor body", and a concrete slot is precisely what grounding is. So a parameter with no concrete
use anywhere in its cycle **cannot** be a value parameter, and TYPE is the only assignment consistent with
every occurrence. The rule refuses a schema that has one reading rather than none.

The case that shows it is not a corner is one the spec's own vocabulary makes unavoidable. A **reference**
template's body *is* the application (§8.1 types `reference.target` as a `type_ref`), so there is no second
slot a concrete use could occupy:

```
loop => <T> loop<T>
```

`T` is passed only to the parameter it is. Under §5.10 as written this is refused for having an ungrounded
parameter — which is both true and useless, because what is wrong with the declaration is that it applies
itself forever and denotes no type. The ungrounded verdict displaces the diagnosis the author needs, and no
rewriting of the declaration can avoid it: grounding `T` here is not possible, only abandoning the shape.

**What this implementation does:** an undetermined parameter is grounded as a type parameter
(`ParameterKinds`), on the argument above, and the declaration is then judged on what is actually wrong with
it. Nothing else changes: an argument bound to such a parameter keeps the reference channel §12.1 gives it,
which is what it would have had anyway.

**Suggested resolution:** drop the rule, and state the consequence instead — a parameter with no
kind-determining use is a type parameter, since a value parameter is one that stands in a scalar slot. If the
rule is kept because an ungrounded parameter is *suspicious* (every application of the template denotes the
same type, so it is probably a mistake), then it is the same observation §5.10 already makes for "a declared
parameter the body never references" and should be stated as that rule's sibling, with the reference-template
case excepted — it is refused on the loop, not on the parameter.

**Status against Revision 34:** open, and new against this revision.

---

## 21. Should base type resolution recognise `date`, now that JSON is not the reason it does not?

**Section:** §4.1–§4.5 (base type resolution and its order), §5 (the built-in type vocabulary), §7.6 (the
number production); [TSON-SCHEMA] §5.4 (discrimination classes and derived disjointness), §5.11 (field
groups).

**Problem:** §4 resolves three classes — boolean, number, string — and every other built-in type is reached
by an annotation (`!date 2025-03-13`) or by a declared type. A date is lexically unmistakable and starts with
a digit, so the number scanner already inspects it and fails; recognising it there would cost nothing
mechanically. The reason it was left out is JSON, whose value space is exactly those three classes plus null
and the containers — and with §6 and principle 5 gone (#8), that reason is gone with them. So the question is
open on its own terms for the first time, and it should be asked before a revision settles §4 for good.

**The gain is real and is not about Class 1.** [TSON-SCHEMA] §5.4 derives `disjoint` from §4's partition, so
`date` is string-class and **`( date | text )` is not disjoint**: a date beside a free-form string carries
`!date` on every value. That is a common shape and an ergonomic wart. A DATE class would remove the tag.

**The cost lands on schemas that never mention a date.** §5.4 couples the two directions — `disjoint` "means
precisely that the encoding's own form resolution ... recovers the variant", and TSON text's form resolution
*is* §4 — so a DATE discrimination class requires §4 to recognise dates, and §4 recognising dates narrows
what a `text` variant catches untagged. Measured against this implementation: `( text | int32 )` today
accepts `2025-03-13` and a bare UUID through its `text` variant. Give dates a class of their own and those
tokens classify as DATE, match neither variant, and an existing schema stops reading a document it used to.
There is no version of the change that takes the gain and leaves the cost.

Three consequences beyond that one:

1. **§5's vocabulary stops being additive.** Today a new built-in atom changes no existing choice's derived
   `disjoint`. Under the proposal, adding one changes the fact on choices that do not mention it — a poor
   property for a registry meant to grow.
2. **`date` alone is immediately arbitrary.** With `date` DATE-class and `datetime` still string-class,
   `( date | text )` reads untagged and `( datetime | text )` does not, though the second shape is at least
   as common. The first addition demands the second, and `uuid`, `uri`, `email` and the two address families
   are all lexically distinguishable too — at which point `text` in Class 1 means "matched none of twenty
   ordered rules", which is unstable in the way §4's three classes are not, and makes the resolution order
   normative over the whole vocabulary rather than over three cases.
3. **The gap already has a spec-endorsed answer.** §5.4 names this exact case: "the labelled form is the
   recommended resolution wherever the tag would otherwise be mandatory: a choice whose variants share a
   base-type class ... is often better written as a single-group record". `( date | text )` is that choice,
   and `{ ( on: date | note: text ) }` discriminates by label with no disjointness required and no tag.

**Interpretation chosen:** Revision 34 as written. `BaseTypeResolver` resolves boolean, then number, then
string; `DateParser` and the rest of §5's vocabulary are reached by annotation in Class 1 and by declaration
in Class 2, and `DiscriminationClass.classify` gives every text-form family — `text`, `uuid`, `date`,
`binary`, the address families — the one `STRING` class.

**Suggested resolution:** leave §4 at three classes, and state the reason on its own authority now that the
JSON one is gone: **§4 classifies host base types** — what a schemaless read hands back with no library type
and no ordered vocabulary behind it — where §5 classifies **semantic types**, which a Class 1 document reaches
deliberately through an annotation rather than by shape inference. That sentence belongs in §4.5 beside the
order, and it is what answers the same question for `uuid`, `uri` and every future addition without
re-arguing each. §5.4's own recommendation of the labelled form (§5.11) covers the ergonomics the proposal was
aimed at, and is worth a cross-reference from §5 for the text-form families, whose choices are where the
missing tag is felt.

**Status against Revision 34:** open, and new against this revision — consequent on #8, a question rather
than a defect, and one this implementation recommends answering *no* and recording, since #8 removed the
reason the answer used to be obvious. Nothing here is built: the recommendation is the status quo, and what
is proposed is the sentence that justifies it.

---
