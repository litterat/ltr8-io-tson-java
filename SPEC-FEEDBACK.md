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

The five below are what Revision 34 leaves open, renumbered from #1; the fourteen it resolved of the
seventeen raised against Revision 33 are gone from here, because the spec now carries their rules and that
is where the answer belongs. **This file is the as-built record**, not a pointer to one: where an entry
proposes a design this implementation has built, the entry states the design, what is running, and what is
not, so that a reviewer editing the spec needs nothing beside it. **Cite the spec, not the argument that got
it there:** `docs/` and the Javadoc name the section that requires a behaviour, and a `SPEC-FEEDBACK.md #N`
citation is for an entry below, where there is no section to point at yet. When an entry closes, its
citations become spec citations and the entry is deleted — nothing here is an archive.

---

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
