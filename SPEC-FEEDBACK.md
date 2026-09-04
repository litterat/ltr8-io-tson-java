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

The thirty-six below are what is open against Revision 34 — the eight it left open, renumbered from #1,
and twenty-eight raised since; which of the two an entry is, its own `Status` line says, so the split stays
checkable rather than counted once. The fourteen Revision 34 resolved of the seventeen raised against Revision 33 are gone
from here, because the spec now carries their rules and that is where the answer belongs. **This file is the as-built
record**, not a pointer to one: where an entry proposes a design this implementation has built, the entry states
the design, what is running, and what is not, so that a reviewer editing the spec needs nothing beside it.
**Where the evidence is a consumer of this library rather than this library** — #16 through #19 were found
building the HTTP layer in `ltr8-io-tson-java-http`, and this register is the collection point for all of it —
the entry says so and states what is running there on the same terms. **Cite the spec, not the argument that got
it there:** `docs/` and the Javadoc name the section that requires a behaviour, and a `SPEC-FEEDBACK.md #N`
citation is for an entry below, where there is no section to point at yet. When an entry closes, its citations
become spec citations and the entry is deleted — nothing here is an archive.

---

## 1. §5.4's `duration` row shows two designators the type does not have and omits the one it does — proposal: the table states RFC 3339's own alternation, which is what the split already implements

**Section:** [TSON-DATA] §5.4 (the temporal annotation table); [TSON-SCHEMA] §5.5, §9 (meta's `duration_type`
and `period_type`); RFC 3339 Appendix A.

**Problem:** §5.4's table gives `!duration`'s format as "ISO 8601 duration (`PnYnMnDTnHnMnS`)". That
parenthetical is now wrong in both directions, and the table is missing a row:

- **`Y` and month-`M` are not `duration`'s.** #26's split moved them: `duration` is a signed number of
  seconds and `period` a signed number of months, because the thing that made one type partially ordered was
  a month with no fixed length beside a second with one. `P1Y2M3DT4H5M6S` is an error.
- **`W` is `duration`'s, and the table never mentions it.** `P3W` is 1814400 seconds.
- **There is no `!period` row at all**, so the annotation the calendar half needs is unlisted.

The original question here was whether the parenthetical excluded the week form deliberately or was
illustrating rather than specifying, and it was a coin flip on the text as written. It is not one any more,
and not because anybody chose: **RFC 3339 Appendix A settles it by construction.**

```
duration = "P" (dur-date / dur-time / dur-week)
```

An alternation. The week form is a third alternative to the date and time forms, never a component beside
them, so `P1W2D` is not a duration and neither is `P1WT1H` — a week with a time part has no production. ISO
8601-1:2019 agrees; only ISO 8601-2 relaxes it to admit `P1W2D`. Once the question is asked of the ABNF
rather than of the parenthetical, there is one answer and no latitude to record.

**Why the week form belongs to `duration` and not `period`, which is the half the ABNF does not answer.** A
week is exactly 7 days and a day exactly 86400 s, so `P2W`, `P14D` and `PT336H` are one value and the whole
form has a fixed length. A month does not, which is what put it on the other side of #26's split. `period`'s
own grammar already excludes `W` along with `D`, so that half needs no new text — but the placement is worth
stating, because **the JDK does the opposite and a reader arriving from Java will expect it:**

```
Duration.parse("P3W")    DateTimeParseException      — java.time refuses the week form outright
Period.parse("P3W")      P21D                        — and folds it to days on the calendar side
Period.parse("P1W2D")    P9D                         — mixing it, which is ISO 8601-2's rule, not 8601-1's
```

That is evidence for the placement rather than against it. `java.time` treats a week as seven *days*, a
calendar quantity, which is exactly the ambiguity the split removes: seven days is 604800 s only if every day
is 86400 s, which is true for `duration` and is not the claim `period` makes. Putting the week where its
length is fixed is what lets `P3W` be a value at all rather than a value-with-a-caveat.

**What a writer emits, which §5.4 does not say and should.** The value is a number of seconds, so a canonical
writer has no way to recover that its input was written in weeks — and, by the same argument, no way to
recover that it was written in days. So a text encoding emits the `PTnHnMnS` form and nothing else: `P3W`
round-trips as `PT504H` and `P9DT1H` as `PT217H`. `PnW` and `PnD` are *reading* conveniences, the way `0x50`
is for an integer — admitted on the way in, gone on the way out, and never a claim about how a value was
spelled. Saying so is what stops an implementation treating the round trip as a defect.

**What is running.** All of it. `P3W`, `P1W`, `P0W` and `P2W` read; `P1W2D` and `P1WT1H` are refused as parse
errors; `P2W`, `P14D` and `PT336H` are one value; `!period P1W` and `!period P1D` are refused; and
`DurationParser.write` emits `PTnHnMnS`, a day not being a distinct unit of the value. The corpus carries
`class1/vocabulary/valid/duration-week-form` (`P3W` → `1814400`, stated as seconds precisely because the
spelling is not canonical) and `class1/vocabulary/invalid/duration-combined-rejected`.

**Suggested resolution: the table follows what the ABNF and the split already require.** Replace §5.4's
`!duration` row and add the missing one —

| Annotation  | Format | Host value |
|-------------|--------|------------|
| `!duration` | RFC 3339 Appendix A `dur-time` / `dur-date` / `dur-week`, no `Y` or month-`M` | duration |
| `!period`   | `P` with a `Y` component, an `M` component, or both | period |

— and add three sentences the grammar does not carry on its face:

- The week form stands alone: `P1W2D` and `P1WT1H` are errors, the ABNF's alternation admitting no
  combination. Naming `P1WT1H` explicitly is worth the words, since a reader who has absorbed "stands alone"
  still tends to read it as being about the *date* designators.
- A week is exactly 7 days and a day exactly 86400 s, so `P2W`, `P14D` and `PT336H` are one value, and the
  week form belongs to `duration` rather than `period` for that reason.
- A text encoding emits the `PTnHnMnS` form; the week and day designators are accepted and not produced.

No change to §5.5, §9, or either constraint vocabulary: this is the annotation table catching up with a
value space the rest of the series already describes.

**Status against Revision 34:** open, and the *reason* it is open has changed. It is no longer a question
about which of two readings the spec meant — #26's split and RFC 3339's own alternation between them leave
one answer — but a table that still shows a form `duration` does not accept and omits two things it does. A
reader implementing from §5.4 alone builds the wrong type.

---

## 2. Content-hash pinning rides in the URI query (`?sha256=`), where a hash is neither a request parameter nor part of identity — recommendation: keep the query form, and say why

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

**Recommendation: keep the query form.** Both objections turn on one premise — that a hash is client-side
verification metadata with no business reaching the origin — and that premise is the wrong way round. Two costs
neither reviewer names.

1. **The fragment is already spoken for.** It is the natural home for an *intra-document* reference, which is the
   one thing this series will want next: a reference to a declaration inside a schema is spelled
   `…/core.tn#uuid`, or JSON Schema-style `…/core.tn#/uuid`, and no other component can carry it. Spending the
   fragment on the hash forecloses that, or makes the two share one component and invents a grammar for the
   sharing. The query has no such occupant — §2.2.1 already reserves it entirely for hash parameters and makes
   any other parameter an error — so the hash is in the component with nothing else to do, and the alternative
   is the component with something else to do.

2. **"Never meaningfully sent to a server" is backwards: sending it is the point.** A hash in the query reaches
   the origin, and an origin that stores revisions content-addressably can answer with the exact bytes a pinned
   reference asks for long after the unpinned URL has moved on. A cache can treat a pinned URL as immutable and
   keep it forever, because a pinned URL names bytes that cannot change. A fragment is never sent: the server
   sees the bare locator, serves whatever is current, and the client's only recourse when verification fails is
   an error — a pin that can detect drift and never repair it. **Objection 2's structured `{ url, sha256 }`
   fails on the same ground and for the same reason**, the fetch carrying the locator alone; it separates
   locator from integrity so cleanly that the integrity cannot reach the party able to satisfy it.

The placement is not novel. draft-sporny-hashlink defines a `?hl=` query parameter precisely so a hash can ride
an existing URL and reach existing infrastructure, and `magnet:` has carried `?xt=urn:sha1:…` for twenty years.
RFC 3986 §3.4 is also less hostile than objection 1 reads it: a query holds "non-hierarchical data that, along
with data in the path component, serves to identify a resource", and *these exact bytes* is an identification.
On that reading the identity-stripping rule stops being a symptom — the pin identifies a byte sequence where
the rest of the URI identifies a resource, and canonical identity strips it because the two answer different
questions. That is a consequence to state, not a smell to design away.

**Interpretation chosen:** This implementation follows the spec as written — the query form. `TsonContentHash`
parses `?sha256=<hex>` off a reference (rejecting any other/unrecognized query parameter or malformed hex),
`CanonicalIdentity`/`TsonSchemaRegistry` strip the query to key everything by identity, `tson hash <file>`
stamps `?sha256=` onto the `!!id` line, and the bundled chain (meta.tn pins meta-kernel, core.tn pins meta.tn)
is pinned end-to-end this way. No change made — flagging the design, not diverging from it.

**Suggested resolution: (a), and say why.** Keep `?sha256=` and add the two sentences §2.2.1 has never carried:
that the pin rides in the query so it reaches the origin and the cache, which is where a content-addressed
store can act on it rather than merely be checked against it; and that canonical identity strips it because a
pin identifies bytes where the rest of the URI identifies a resource. Record (b) and (c) as considered and
declined on the argument above — (b) spends the component an intra-document reference will need, and both leave
the origin holding only the bare locator.

If §2.2.1 is opened for the justification anyway, one addition is worth taking with it: say that a fragment is
no part of a schema identity and is **reserved**, so a later revision can spell an intra-document reference
there without a compatibility question.

**Status against Revision 34:** open as a question, closed as a proposal — the placement is right and what is
missing is only the justification for it. §2.2.1 needs no change: the query form stays, with the discipline
Revision 33 tightened around it — the pin is "verification metadata, not identity", a query MUST consist solely
of hash parameters, and an unrecognised parameter name is an error rather than something silently retained.
Adopting this entry costs the change log two sentences and a reservation, and nothing moves on this side:
`TsonContentHash`, canonical identity, `tson hash` and every bundled pin are the query form already.

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

**The open-entry sentence is the half that is right, and the other three places are what have to move.** A
held body is an *AST* — the application as written, with parameter references standing where types and values
will go — and a `type_definition`'s `body` is a resolved closed type. There is no ingest of the first into the
second: reading a held body against the kernel's own vocabulary is exactly what materialisation exists to
defer, and a consumer that did it would be resolving the template rather than holding it. So §8 output for a
schema declaring templates is a compromise however it is spelled, and the compromise this implementation makes
— comparing an open entry's body as wire form on both sides, which is what it is on both sides
(`ResolvedForm.heldBodies`) — is the one the value model allows.

**Suggested resolution:** keep "no `type_definition` could carry it" and fix the three places that assume
otherwise.

- §8.1's "Reading parameter references" paragraph has no position to apply at if the sentence stands. Either it
  is about schema *source* — where a held body really does carry parameter references and really is resolved
  against the enclosing entry's `parameters` — or it goes.
- §5.10's closed-entry rule is stated as a well-formedness rule **on resolver output**, which presupposes output
  in which an open entry appears. Restate it on the resolver's own value, where it is a real check and where
  this implementation applies it.
- §8.1's ingest paragraph needs `schema` to admit a second value shape for "an open entry ... re-resolved as
  source" to typecheck, and the kernel's `schema => {type_name => type_definition}` does not. Say what a
  conforming emitter writes for an open entry, or say that §8 output is defined over closed entries only and an
  open one is not serialized — which is the answer the sentence already implies.
- The parameter reference *does* type-check structurally today (`type_ref.name` is an `identifier`, and `T` is
  one), so nothing in the model enforces the sentence. **Typed template parameters** would close that — a
  parameter declared with the kind of slot it stands in, which would also give §4.2's value-route-only rule and
  §5.10's argument-kind rule the slot types a held body cannot supply. It is a larger change than this entry
  proposes and is named as the direction rather than the recommendation.

**Status against Revision 34:** open, and new against this revision — §8.1's open-entry sentence and its
"Reading parameter references" paragraph are both Revision 34 text.

---

## 5. §11.4's scope list omits a template's parameters — recommendation: no change, and this implementation stays deliberately stricter

**Section:** Part 2 §11.4 (name hygiene at the schema layer), [TSON-DATA] §8.2 (the three mechanisms).

**The gap is real.** §11.4 enumerates the schema layer's named scopes — "the members of one enum; the field
names of one record definition (member labels of its groups included, §5.11); the declared names of one
schema; and the merged namespace at `!!import`". A template's **parameters** are not among them, and a
parameter is a name: §5.10 says "a parameter declaration is a bare name", and §12.1 makes it a naming
position matched against §7.7's identifier grammar like any other. So this is accepted:

```
box => <T, Т> { a: T  b: Т }
```

Latin `T` and Cyrillic `Т` (U+0422) are two parameters that render identically. A body referencing `T` binds
one of them, an application `box<text, integer>` fills both positionally, and no reader of the source can see
which slot either argument reaches.

The two per-name mechanisms have the same gap for the same reason. §8.2 frames all three as operating "over
named scopes", which is strictly true only of mechanism 1: `Identifier_Status` and the restriction level judge
one name at a time and need no scope at all. But because §11.4 supplies the schema layer's scopes as a closed
list, a name in no scope is a name no mechanism is stated to reach.

**Recommendation: no change, on the grounds that closes it.** A scope earns its line in §11.4 by being a place
where names an author did not write together end up side by side. Every scope on the list is one: a record's
field names are matched against a document someone else sends, an enum's members outlive the schema that
declared them, and `!!import`'s merged namespace is the collision of two authors who never met. A template's
parameter list is none of that. It is one or two single letters, written by one author, in one line, visible
whole at the point of declaration — and the confusable pair has to be *deliberate*, because nobody reaches for
Cyrillic `Т` by accident while typing `<T, U>`. The hazard is real and the population is empty.

Against that, the cost of the text is not one line. A fourth scope is a fourth thing every implementation must
walk, a fourth thing a conformance suite must cover, and a fourth thing the next scope-shaped construct has to
be compared against. §11.4's list is short because a short list is reviewable; the case for growing it should
be a case someone has met.

The same answer disposes of the second half. §8.2's over-generalisation — "the mechanisms operate over named
scopes" — has exactly one observable consequence, which is that a parameter name has no stated verdict under
mechanisms 2 and 3. Leave the scope list alone and that consequence stays confined to the one construct nobody
writes badly, at which point correcting the sentence buys precision and no behaviour.

**This implementation is stricter, and will stay so.** It treats the parameter names of one template as a
fourth schema-layer scope, checked by all three mechanisms in the same walk as the other three
(`TsonSchemaLinker.checkNames`; `ConfusableNameScopesTest` pins each case). That is not a claim the spec
should follow: the walk here is one walk over a list, and adding the parameter list to that list was cheaper
than carving it out — "one place is the point", as this implementation's own note on the rule puts it, and a
walk with an exemption is the shape that grew the holes the walk was built to close.

The divergence is bounded, which is why it is tolerable. §8.2's refusal is a fifth outcome and explicitly not
one of §8.1's four categories, so a schema refused here is not a schema called invalid — the document's
verdict is *withheld*, not decided against it. What it does mean is that a schema this refuses may load
elsewhere, and that **no conformance vector can exist either way**: one asserting the refusal fails a
conforming processor, and one asserting acceptance fails this one. Those cases live in this repo's own tests
instead, and `class2/schema/refused/` deliberately carries none of them.

**Suggested resolution:** none. Record it as considered and declined, so the next revision's scope list stays
answerable as a whole rather than growing by accretion. If §11.4 is opened for another reason, the sentence
about mechanisms 2 and 3 being per-name is worth taking then — it costs nothing and makes a forgotten scope
cost one missing relation rather than three missing checks.

**Status against Revision 34:** open, with its recommendation reversed. The entry originally proposed adding
the scope; building it is what changed the argument — the check is cheap here and finds nothing, which is
evidence about the population rather than about the rule. Adopting this entry costs the change log one line
saying no.

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

**The bundled schemas carry it too, and that took a decision.** `meta-kernel.tn` documented `value`'s
inhabitants as "null, boolean, integer, float, string" and `void`'s prose named `null` as an accepted spelling;
both are gone, and `value`'s `@doc` now says what a value-typed field is — the token, uninterpreted, read by the
type the position hands it to. Editing them meant minting digests for documents nobody has published, which is
why this branch first moved all three to `/2026/35/` identities: an artifact named for the revision that
proposes it is not a competing edition of Revision 34's. `main` goes on serving the published ones. Every entry
below that reports built vocabulary rests on that move.

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

**Two §5.4 findings fell out of checking this, and are recorded as #22** — the `@disjoint` annotation's prose
describes a different property from the one the resolver derives, and a token discriminated to a variant that
refuses it gets no second chance. Neither is about `true`/`false`, and an adjudicator looking at §5.4 would not
find them here.

**Suggested resolution:** keep `true` and `false`, and say why in §4.2 in the terms above — the §5.4
dependency first, so that #7's removal is not read as half of a pattern whose other half would silently
change a derived fact. §4.5's order becomes boolean → number → string with #7, and §7.7 rule 3 then has two
words to explain rather than three, both members of a kernel enum, which is the whole of their status under a
schema.

**Status against Revision 34:** open, and new against this revision — a decision, recorded so that it is one.
This implementation recommends keeping them and has changed nothing.

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
   gives the application an identity. If that is the intended answer, §8.2 is the place to say so. This entry
   used to attach a diagnostic caveat here — a bad argument in the alias form was reported against the entry
   the template materialised, showing the author a synthetic name they had never seen — and **that caveat is
   gone**: `EntryDisplayName` renders a minted entry as the sugar or application that produced it, so
   `order_page => page<no_such>` now reports

   ```
   [SCHEMA_ERROR] /order_page: '[no_such]' element_type has an unresolved reference 'no_such'
   [SCHEMA_ERROR] /order_page: 'page<no_such>' source has an unresolved reference 'no_such'
   ```

   located at the author's own declaration and naming only text the author wrote. Option 2 therefore carries
   no cost this implementation can find, which strengthens it against option 1.
3. **Extend the sugar to data position.** Real ergonomics, and a real cost: `<` becomes meaningful in data, at
   exactly one slot type, decided by the governing schema. Probably not worth it — noted for completeness
   rather than recommended, and it would have to reach a record §8.1 argues must stay braced.

**Status against Revision 34:** open. This revision reworked §8.1 heavily — held bodies, `reference.target`
widened to a `type_ref` — and left the positional-form paragraph byte-identical. The one thing that has moved
is on this side: the diagnostic caveat option 2 used to carry is fixed, so the choice between options 1 and 2
is now between documenting the rule and recommending a spelling, and they are not exclusive — a revision may
take both.

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

**Status against Revision 34:** open, and **deliberately left open for this cycle: the shape needs further
investigation before anything is built against it.**

The reason first given here was the wrong one, and saying so matters because several entries below relied on
it. It read: every route changes the meta-kernel, the kernel is a published hash-pinned Revision 34 artifact
(§10, §13.2), and nothing can be built without minting digests for a document nobody has published. That is no
longer a constraint. This branch moved all three companion artifacts to `/2026/35/` identities precisely so
that a revision's own proposals can be built against artifacts named for it, and #7, #23, #24, #25, #26 and #29
are all running on that basis. Publishing is not what stands in the way.

What stands in the way is the design. A namespace value is not one addition but a question about what the
kernel's 2×2 is for, and the entry above sketches a cell rather than settles one — so it is held over rather
than implemented ahead of an answer. That is a different state from the rest of the register, and worth naming:
every other open entry here is either a defect with a known fix or a proposal this implementation runs. This
one is neither yet.

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
`bytes`, the address families — the one `STRING` class.

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

## 22. §5.4 asserts one property and derives another, and a discriminated variant that refuses the value has no fallback

**Section:** [TSON-SCHEMA] §5.4 (discrimination classes, derived disjointness, the `@disjoint` assertion),
§5.11 (field groups); [TSON-DATA] §2.4 (form is not meaning), §4 (base type resolution).

**Problem:** two findings about one section, both surfaced while checking #11 and neither about the question
that entry asks.

**1. The assertion and the derivation are different claims.** §5.4 fixes the derivation exactly —
"a choice is `disjoint: true` if and only if every variant has a class and no class appears twice ... a
resolver MUST record exactly this — it MUST NOT prove more (value-set separation such as disjoint numeric
bounds or disjoint patterns does not make a choice disjoint) or less" — and that derivation is about
**discriminability**. But the same section describes the annotation as recording "the intent that its variants
are **mutually exclusive**", which is about **inhabitance**. The two come apart on any choice holding a
string-class atom, because [TSON-DATA] §2.4's *form is not meaning* makes `text` admit every token: a declared
`text` position takes `42` and `true` unquoted. So `42` inhabits both variants of `( int32 | text )` — which
this implementation accepts under `@disjoint`, correctly, because it is discriminable.

An author reading §5.4 writes `@disjoint` believing they have asserted their variants cannot both match a
value. They have asserted something weaker and more useful, and the section never says so.

**Suggested resolution:** state the assertion in the derivation's own terms — the author asserts that the
variants are *distinguishable by the encoding's form resolution*, which is what is checked — and add that
overlap in inhabitance is ordinary and expected, `text` beside any scalar being the common case. The
verification outcomes are unchanged; only the sentence describing what was verified moves.

**2. A variant that refuses a value it was discriminated to has no fallback, and nothing warns.** §4's
`number` production admits `0xFF`, `0b1010`, `0o377`, `.inf` and `.nan`. core.tn's `number` atom is
decimal-only and refuses all five. So in `( number | text )` those tokens classify as NUMBER, dispatch to
`number`, and fail — they cannot reach `text`, because §5.4 requires the variant be "recovered from the
value's class, never by a second, type-directed inspection of the value's form; the once-only reading of form
([TSON-DATA] §2.4) is preserved".

Measured: `( number | text )` given `0xFF` reports *"'number': '0xFF' is not a valid number"* rather than
reading it as text. The behaviour is exactly as specified and the once-only rule is worth keeping — what is
missing is that nothing tells an author their choice has a hole. `( number | text )` reads as "a number or
anything" and accepts `0xFF` in neither variant.

**Suggested resolution:** a sentence in §5.4, and a cross-reference from §5's vocabulary: **a constructor
narrower than its base-type class leaves values of that class unreachable in any untagged choice it appears
in.** It applies to `number` against §4's production, and to every refined atom — `int32` in
`( int32 | text )` refuses `99999999999999` the same way, and a pattern-refined `text` beside another
string-class variant refuses everything the pattern excludes. This is not a rule to add but a consequence to
name, and naming it is what stops an author reaching for a choice where §5.11's labelled form is the shape
they want.

**Interpretation chosen:** Revision 34 as written, in both halves. `ChoiceDisjointness` derives class
distinctness and nothing else; `TsonSchemaLinker` checks `@disjoint` against it; `ChoiceReader` recovers an
untagged value to the variant of its own class and does not retry another on failure.

**Status against Revision 34:** open, and new against this revision — split out of #11, where they were
recorded first and would not have been found by anyone reading for §5.4. Finding 1 is a wording defect;
finding 2 is a consequence the section leaves for an author to discover from a read failure.

---

## 23. `unknown` has no reader for an untyped value, and `extern` names one schema where a position admits a set — proposal: no base type resolution under a schema, and one `scoped` constructor over the namespaces a value's type may come from

**Section:** [TSON-SCHEMA] §7.8 (cross-schema references, the typed-position restriction), §7.1 (the unannotated
root, the permissive-type list), §4.1 (the sum kind: `choice`, `extern`, `unknown_type`), §5.2 (which fields may
carry a value), §5.4 (classless variants), §8.1 (`disjoint` absent on the non-choice sums), §9 (meta.tn's
contents); meta.tn's `extern` and `unknown_type`; core.tn's `unknown`; [TSON-DATA] §2.3 (scoped values), §3.3
(directive positions), §4 and §4.5 (when base type resolution applies), §5.1 (when the built-in vocabulary
applies).

**Problem:** three findings about the two sums that are not `choice`, found asking what a reader for each would do.
Neither has one in this implementation, and the reason is that the spec does not say.

**1. An untyped value at an `unknown` position has no parsing contract.** meta.tn defines `unknown_type` as "an
empty sum whose instance, `unknown`, accepts any well-formed value of any type", and §7.8 calls `unknown` "a sum
instance with universe membership". That names the membership and not the reader. For a *tagged* value the reader
is clear: `!name` resolves in the governing namespace (§7.2) or, after a scoped `!!schema`, in the foreign one. For
an untagged `42` there is none. [TSON-DATA] §4 applies base type resolution "only when no declared type
information is in scope", and at an `unknown` position it is — the position's type is `unknown`; [TSON-DATA] §5.1
switches the built-in vocabulary off under any schema; and `unknown_type` supplies no contract of its own. Read
literally, the token has no reader, and an implementation must either invent one or refuse the value, and the
prose supports neither.

The regime such a value would need does exist, at exactly one position. §7.1 makes an unannotated root under
`!!schema` "legal but vocabulary-only" — untagged tokens by base resolution, validation engaging only where
annotations appear within the value — and then requires a validator to report it, "with no root type there is
no contract to check". So the spec has a third reading regime beside *typed by position* and *typed by tag*,
describes it once, at the root, forbids claiming validation for it there, and leaves `unknown` — the one position
where an author has explicitly asked for it — with no statement at all.

**2. `extern` names one schema, and the space it sits in has five points, two of them unspellable.** An `extern`
carries one `schema` and an optional `types` narrowing. A position admitting values from *several* foreign schemas
— §7.8's own example, an attachments array holding an insurance claim and a radiology report — is written
`[extern]`, each element carrying its own `!!schema`, and the schema cannot say which schemas are welcome there:
`types` narrows within one schema, and there is no way to list two. That is the small half. The larger half is
what the constructor is a point in. Under a schema a value's type comes from a namespace, and there are two
independent questions about a position: whether the governing namespace (locals and imports, §2.2.3) is admitted,
and which foreign schemas are. Two answers by three, less the combination that admits nothing:

| Governing namespace | Foreign schemas | Revision 34 | Meaning |
|---|---|---|---|
| no | specific | `extern` | the current `extern`, one schema at a time |
| no | any | — | any foreign schema (the `[extern]` element) |
| yes | none | — | any type this schema declares or imports |
| yes | any | `unknown` | either, from anywhere |
| yes | specific | — | this schema's types, or a named partner's |

Two constructors, unrelated to each other, spell two rows; the third row has no spelling, and the fifth — an
envelope schema admitting its own event types or a claim from one named partner — cannot be recovered with a
choice, since a choice dispatches on a variant name and a foreign value's tag is not one.

**3. §7.8's permissive list is a list, and one entry on it is not a sum.** A nested `!!schema` is a resolver
error "unless the outer type is one of the permissive types: `extern`, `value`, `unknown`, or a container
thereof". `extern` and `unknown` are sums, and admitting a scope push is what their membership means. `value` is
the kernel's unit instance — a single token, read by whichever declared type the position hands it to (§5.2) — and
a scope push at a single-token position has nothing to push a scope *for*: the pushed schema's namespace is
consulted by `!name` annotations, and a token position carries none that the enclosing schema did not already
give it. The list is a list because the property is not derived from anything; a constructor that states which
namespaces a position admits would make it a derived fact, the way `disjoint` is (§8.1).

**Interpretation chosen — a proposal, not yet built.** Three decisions, each stated as what the rule would be.

*No base type resolution under a schema.* Under `!!schema` every value is typed by its position or by its tag,
and there is no third way to read a token. This closes finding 1 by removing the regime rather than specifying
it: [TSON-DATA] §4's applicability clause becomes "base type resolution applies only in schemaless documents",
with no "declared type information in scope" hedge to interpret; §7.1's "legal but vocabulary-only" root becomes
a plain error in every mode, the root having no position to be typed by, so the MUST that follows it has nothing
left to guard; and "validated" always means "checked against a named type". It also removes `value` from the
permissive list, closing finding 3's odd entry: `value` is a token position, not a scope. (The kernel's `@doc`
on `value` describes it as "the result of base type resolution applied to a source token" and names `null`;
that sentence is already false under #7 and is false a second way here, and wants rewording to "the token,
uninterpreted, read by the type the position hands it to".)

*One constructor in meta.tn, replacing `extern` and `unknown_type`, naming the namespaces a value's type may come
from:*

```
scoped => ~sum & {
  scope:   !set { element_type: scope_cell  min_items: 1 }
  schemas: {uri => [type_name; 1..]?; 1..}?
}
scope_cell => !enum [LOCAL EXTERN]
```

`scope` says which namespaces are admitted; `schemas` narrows the foreign ones — absent is any foreign schema, a
keyed map is those schemas, and a key's absent value (`{K => V?}`, §5.3) is every type the schema declares where a
list is those types. The map is keyed by schema identity so one schema cannot be listed twice, compared by §2.2.1's
canonical identity so a pinned key and an unpinned `!!schema` in the data match, each pin verified by the loader on
its own. One coherence rule, of the family a resolver already runs over `min_items`/`max_items`: `schemas` requires
`EXTERN` in `scope`. The state that admits nothing is unspellable rather than refused — `min_items: 1` on both
collections — which is why the set spelling beats a boolean beside an empty map: the empty map was the only reason
the narrowing field carried an admission fact, and it was also the ugly spelling.

*Three instances in core.tn*, one per subset, so that the five rows are three names plus one per-use form:

```
declared => !scoped { scope: [LOCAL] }
extern   => !scoped { scope: [EXTERN] }
dynamic  => !scoped { scope: [LOCAL EXTERN] }
```

`extern` survives as the instance the spec has always meant by the word; `declared` is "a type this schema declares
or imports"; `dynamic` is the widest, named for what it says — the *data* decides the type, from any namespace — and
not `any`, which every language a reader arrives from uses for a value that is *unchecked*, where this one is
validated in full against the type it names. `unknown` goes, deliberately: its meaning was "any well-formed value,
typed or not", and the untyped half is what the first decision removed — keeping the name over the narrower meaning
would be the silent kind of change, and the new name marks it. meta.tn loses two constructors and gains one; §4.1's
sum kind lists `choice` and `scoped`.

*Two templates in core.tn, so that naming one schema, or one type in it, is an application rather than a
declaration:*

```
extern_of   => <S>    !scoped { scope: [EXTERN]  schemas: { S => _ } }
extern_type => <S, T> !scoped { scope: [EXTERN]  schemas: { S => [T] } }
```

A field then writes `attachment: extern_type<"https://…/claim.tn", claim>` and declares nothing, which is meta.tn's
own line about an intent that deserves a name earning a template. Both are ordinary §5.10 partial applications over
a meta constructor, so the kernel does not move; §8.2's instantiation identity makes two schemas writing one
application land on one entry; and the parameter kinds fall out of the slots — `S` stands in a `uri`-typed key and
`T` inside `[type_name]`, both atoms, so both are value parameters and the arguments are the scalars §5.10 admits.
(`T` arrives on the reference channel as a bare name; §5.6 spells a no-argument reference positionally, as its own
token, so it lands in the `type_name` slot as the identifier it is — this implementation's substitution already
does exactly that.) Two limits, stated so they are not discovered later: a value parameter is one scalar, so the
template form reaches one schema per application and one type per schema, and the multi-type and multi-schema rows
keep the instance form or a named declaration in the user's schema; and an application's identity is the argument
as written, so a pinned and an unpinned `S` are two applications where the data-side match is canonical — #2's
question about pins in identity, met again, not a new one.

*The data rule is one paragraph, and the value's own shape picks the cell.* At a position whose type resolves to a
`scoped` instance: a value carrying `!!schema` is `EXTERN` — its identity must be a key of `schemas`, or any if
`schemas` is absent, and its `!type` must be in that key's list, or any of the schema's types if the list is absent;
a value carrying `!type` alone is `LOCAL`, resolved in the governing namespace; a value carrying neither is a
validation error, "a value at a scoped position must name its type". A cell the instance's `scope` does not hold
refuses the value it would have taken. The read of an `EXTERN` value is then §7.8's scope push exactly as written:
load the named schema through the ordinary loader, so a schema nothing would supply is one of the five
`SCHEMA_*` codes and never a verdict; push the scope; resolve `!type` in it; validate in full; pop. §7.8's
permissive list becomes a derived fact: a position admits a nested `!!schema` exactly when its type resolves to a
`scoped` instance whose `scope` holds `EXTERN`, containers descending as §7.8 already says.

The distinction the constructor draws is against `choice`. Both are sums in which the value names its own type; a
choice is *closed*, enumerating its variants, and `scoped` is *open*, naming the namespaces its variants are drawn
from. §5.4's classless-variant list and §8.1's "`disjoint` is absent on the non-choice sums" both keep their meaning
with `scoped` in place of the two names they list.

**What is running, and what is not.** The vocabulary is built and the reader is not. meta.tn declares
`scope_kind => !enum [LOCAL EXTERN]` and `scoped => ~sum & { scope: set<scope_kind>  schemas: {uri =>
[type_name; 1..]?; 1..}? }`; `extern` and `unknown_type` are gone, and so are `schema.meta.Extern` and
`UnknownType`, replaced by one `Scoped` record whose own coherence rule is the one named above (`schemas`
requires `EXTERN` in `scope`). core.tn declares `declared`, `extern` and `dynamic` over the three named
subsets, plus the two templates — which resolve, materialise and link, `extern_type<S, T>` included.

**One correction the entry owes itself**: it writes `scope: !set { element_type: scope_cell  min_items: 1 }`
inline, which §5.2 prohibits — a `!` constructor application at a field position, the exact rule meta-kernel's
own `@doc` on `enum_set` cites. The resolver refuses it. It is spelled `set<scope_kind>` here, a §5.10 template
application over a parameterless `set_type` constructor, which is not a construction and so is admitted where
one is not. (The member's own name moved from `scope_cell` to `scope_kind`: `cell` named a cell of this
entry's own 2×3 table, which is the argument rather than the thing, where every other enum in the meta layer
names what its values *are* — `type_kind`, `field_state`, `binary_encoding`.)

**The reader is built, and it is one reader.** `ScopedReader` serves every instance — core's `declared`,
`extern` and `dynamic`, and every narrowing `extern_of`/`extern_type` materialises — because what separates
them is two constraint values rather than a shape, which is what this constructor bought over the two it
replaced. The value's own shape picks the cell as the data rule above states it; LOCAL resolves through the
governing schema's own compiled readers, fixed when the entry is compiled, and EXTERN loads the named schema
through the ordinary loader as the value arrives, so a schema nothing would supply is one of the five
`SCHEMA_*` codes and never a verdict. `DiscriminationClass` treats a scoped instance as classless, per §5.4.

**Two things building it settled that the proposal above had not.** First, §7.8's typed-position restriction
needed somewhere to live: every container used to consume a `SchemaRef` and discard it, so a document could
push a scope its schema never opted into and be read as though it had not. The refusal now sits at the one
place that knows both the position's declared type and the reader standing there. Second, §7.8's "the
discriminant is required" is a **validation** error and not a resolver one — nothing failed to resolve — and
that is what §8.1's categories make of it at both cells, not only at the extern one §7.8 names.

**And one this implementation had wrong, which only a read could show.** `extern_of<S>` and
`extern_type<S, T>` are the first templates in the meta layer whose body puts a parameter inside a **map**
(`schemas: { S => [T] }`), and three walks over the wire form did not descend into one. Substitution left
the parameter name standing where the argument belonged, so the closed body carried the literal `S` as a
schema identity. Kind inference never observed the parameter at all, so `T` — a value parameter, standing
inside `[type_name]` — stayed on §12.1's reference channel and failed as an unresolved reference to a type
called `claim` in the *applying* schema. And §8.2's derived name rendered the whole map as one unknown-value
mark, so two bindings differing only inside a map hashed alike; the readable half of the name masked that,
which is why the guard is now on the canonical rendering directly. Nothing in the spec is at fault; it is
recorded here because the shape this entry proposes is what reached all three, and an implementation
adopting it will reach them too.

**Suggested resolution:** concretely, by section.

- [TSON-DATA] §4: "Base type resolution applies only in schemaless documents" — one sentence replacing the
  "declared type information in scope" clause; §5.1 already says the same of the vocabulary.
- §7.1: delete the "legal but vocabulary-only" reading and the validator's MUST that qualifies it. Under `!!schema`
  the root names its type or the document is invalid, in every mode. Replace the permissive-type list with the
  derived rule above.
- §7.8: replace `extern`'s description with `scoped`'s, the five-row table, and the data rule; keep the scope-push
  paragraphs and the example, whose attachments field becomes `[extern]` over core's new instance, or a narrowed
  `scoped` listing both schemas, which is the case the example exists to show.
- §4.1, §5.2, §5.4, §8.1, §9: `scoped` where `extern` and `unknown_type` are named; `dynamic` where `unknown` is.
- meta.tn: `scoped` and `scope_cell` in place of `extern` and `unknown_type`. core.tn: `declared`, `extern`,
  `dynamic`, `extern_of` and `extern_type` in place of `unknown`. meta-kernel.tn: reword `value`'s `@doc`.

**Status against Revision 34:** open, and new against this revision — a proposal, and like #7–#10 one this
implementation now runs end to end. The two constructors it replaces are gone; `scoped` reads, in both modes,
against the conformance corpus's own vectors for §7.8. What building it corrected is above: the entry's own
inline `!set` at a field position is not spellable, and its data rule owes §8.1 a category.

---

## 24. §7.4 rules out a numeric enum and names two replacements, neither of which expresses a sparse value set — proposal: a `members` facet on the exact numeric constraint vocabularies

**Section:** [TSON-SCHEMA] §7.4 (enum member semantics; constraint fields typed as `value`), §5.5 (constructor
application and atom refinement), §5.4 (discrimination classes and derived disjointness), §5.7 (value tightening
per facet kind), §9 (meta-kernel's `integer_type` and `enum_set`, meta's `decimal_type` and `float_type`);
[TSON-DATA] §4.3 (numeric equivalence).

**Problem:** §7.4 forbids a numeric enum and names what to write instead — "`!enum [1 2 3]` is a schema-load
error, and the intent is an `!integer ^ { min: 1  max: 3 }` or a choice". The rule is right and its reasons hold:
`enum_set` is a set of `identifier`, an enum member is a name, and a display string is mapped at the boundary.
What the sentence does not cover is the case its own example is the easy half of. `min`/`max`/the exclusive
bounds denote a contiguous range and `multiple_of` an arithmetic progression; a **sparse** set — `[80 443 8080]`,
an HTTP status subset, a protobuf enum's explicit numbers — is neither, and no combination of the facets
`integer_type` carries denotes it.

Running here, the enum half refuses exactly as §7.4 requires, through the meta's own compiled reader:

```
port => !enum [80 443 8080]
[SCHEMA_ERROR] /port: 'port': the body is not valid data for 'enum', the constructor's own constraint
vocabulary -- 'identifier': '80': U+0038 at index 0 cannot start an identifier
```

**The choice half is the one that costs something, and §5.4 is explicit about why.** The derivation names this
exact shape: a choice "whose variants are separated only by value sets — bounded numerics, disjoint patterns,
disjoint enum member sets — derives `false`". So the replacement is three single-value refinements, three names
an author never wanted, and — because the choice is not disjoint — a tag on every value:

```
p80 => !integer ^ { min: 80  max: 80 }   …   port => ( p80 | p443 | p8080 )

!server { p: 443 }        [UNKNOWN_TYPE_REF] /p: 'port' is a choice -- a value at this position requires an
                          explicit type annotation (!typeName) naming one of its declared variants: [p80, …]
!server { p: !p443 443 }  OK
```

An identifier enum in the same position needs no tag, no per-member declaration and no name per value. The
suggested replacement is strictly worse than the construct it replaces, for a case every schema language this
one converts from treats as ordinary — JSON Schema's `enum: [80, 443, 8080]`, a protobuf enum's numbers, an
OpenAPI document's status list.

**The gap is a facet, not a constructor.** A `numeric_enum` constructor is the shape that suggests itself and is
the wrong one, on §5.5's own rule: "construction creates siblings, not subtypes". A fresh constructor mints a
family with no IS-A into the numeric tiers and no facet composition, where `!integer ^ { members: [80 443 8080] }`
inherits both — it is an `integer`, it refines further, and its bounds and `members` compose as facets of one
body. The facet also lands where the family's other constraints already live, so the tightening, coherence and
identity rules it needs are the ones already stated for its neighbours.

**§5.7 already declares the facet kind, and already anticipates a non-enum member set.** Its per-kind table gives
"a **member set** (an enum's `members`, a pattern alternation authored as a set) may shrink to a subset, never
grow or replace" — the parenthetical's second item is not an enum, so the kind was never enum-specific. No new
tightening kind is needed, and this implementation's shared facet machinery already carries the comparison
(`AtomNarrowing.checkSubset`, today serving `enum.members` alone).

**Three things the landing shape has to settle, and the enum side answers all three.**

1. **A set, not an array — and it needs a named entry.** `enum_set` is `!set { element_type: identifier
   min_items: 1 }`, which buys uniqueness from `set`'s contract and non-emptiness from `min_items`; an array
   spelling admits both `[80 80]` and `[]`, the second being a body that admits no value at all. The array is
   also what an author gets by *default*, which is the trap: meta-kernel's own `@doc` on `enum_set` records that
   it is "a named entry rather than an inline form, because `!` constructor applications remain prohibited at
   field positions ([TSON-SCHEMA] §5.2) and `set` has no sugar of its own" — so `members: [integer]?` written
   inline is an array because array sugar is the only inline container, not because anyone chose one.
2. **Coherence against the facets beside it.** `{ members: [80 443]  max: 100 }` is `{ min: 10  max: 3 }`'s exact
   twin: two individually legal facets whose conjunction admits nothing. §5.7 does not reach it (each facet
   tightens fine on its own), so it belongs with the schema-load coherence rule meta.tn's own `@doc` already
   calls for — here, `Atom.coherenceCheck` over `AtomCoherence`. The rule is per member and needs no set
   arithmetic: every member MUST satisfy the body's other facets, including the range `integer_type.size`
   derives rather than stores, on which `{ members: [443]  size: { bits: 8 } }` fails the same way a stated
   bound outside the width does.
3. **Identity is [TSON-DATA] §4.3's, and it is already the rule.** `0x50` and `80` are one member, so
   `[80 0x50]` is a duplicate — which the set catches and the array silently keeps, tying this back to (1).
   §8.2 already states the shape ("recorded as written and compared as the value denoted") and `NumericIdentity`
   already implements it for type arguments; the facet reuses it rather than adding a rule. **The base-type
   line does not survive the slot's own type, and it is the one thing this entry had wrong.** `1` and `1.0`
   are two base types under §4 and one member of a `decimal_type` set: `members` is typed `set<value>`, so §4
   is what an element is decoded *by*, never what it is compared *as*, and the atom the member stands in is
   `number`. Comparing them as §4 left them makes the set's own uniqueness rule hold an integer beside a
   decimal and find them distinct — a claim nobody would write down, and one meta.tn's `@doc` already
   contradicts.

**What the facet buys beyond the enum itself.** A mixed-scalar source enum (`["AUTO", "MANUAL", 80, 443]`, the
shape a converter meets constantly) becomes a choice over an identifier enum and a member-set numeric, which is
**disjoint by class and therefore tag-free** — an enum's class is read off its members' own tokens per §7.4, so
the identifier enum is string-class and the refined integer number-class. Verified here on the bounded stand-in
the facet would replace: `@disjoint setting => ( mode | ports )` links, and `!box { s: 443 }` reads untagged.
The converter output is better structure than its source, which is where a strict mapping wants to land.

**The exact tier only.** `float_type` takes no `members`, on the rationale meta.tn already states for the facet
it likewise omits: "a step constraint cannot hold on a binary grid (e.g. 0.05 is not a binary32 value, so nothing
is exactly a multiple of it); use the exact tier (`decimal_type`) when a step is required". A member set is the
same claim about the same grid.

**Interpretation chosen — the facet, and it is running.** meta-kernel declares `integer_member_set => !set_type
{ element_type: integer }` beside `enum_set`, with `members: integer_member_set?` on `integer_type`; meta
declares `members: set<value>?` on `decimal_type`, and `float_type` takes none. `!enum [80 443 8080]` is still
refused at schema load, as §7.4 requires, and the author writes `!integer ^ { members: [80 443 8080] }` instead
of three single-value refinements and a tagged choice.

All three of the entry's under-determined points landed as it argues them. **The set, not an array**: both are
`!set_type` instances, so uniqueness comes from the set's own contract and non-emptiness from `min_items`, which
`set_type` now defaults to 1 — an empty member set is a body admitting no value at all and is unspellable rather
than refused. **Coherence**: `AtomCoherence.checkMembers` requires every member to satisfy the body's other
facets, including the range `integer_type.size` derives rather than stores, so `{ members: [443]  size: { bits:
8 } }` fails the way a stated bound outside the width does. **Identity is §4.3's**: members compare as the value
denoted, so `[80 0x50]` is a duplicate the set catches, and `[1 1.0]` is one in the decimal family once each
member is read under the atom it constrains. §5.7's member-set kind needed no new text —
`AtomNarrowing.checkSubset` was generalised from `enum.members` and now serves both, taking the family's own
identity where `equals` is not it.

**The read applies it too, and building that half is where the third point earned its keep.** `IntegerParser`
and `DecimalParser` check the member set beside the bounds and `multiple_of`, so `!integer ^ { members:
[80 443 8080] }` refuses `22` and admits `0x50`. The decimal family needed one thing first, and it is a note
for §7.4 rather than for the facet: `decimal_type.members` is `set<value>`, so its elements arrive as whatever
§4 resolved them to — `1` an integer beside `2.50` a float — and the set's own uniqueness rule, running on
those, sees two host types and compares neither. Reading each member under the constrained atom before the set
is formed is what makes `1` and `1.0` one member, and it is also what the schema-load coherence check needs:
comparing an unread member against a bound is a type error, not a verdict. meta.tn's own `@doc` already says
the resolver does this; the sentence is load-bearing and worth keeping in whatever text the facet lands in.

**Suggested resolution:** ordinary constraint vocabulary, in the family each tier's bounds already sit in.

- meta-kernel.tn: `integer_member_set => !set { element_type: integer  min_items: 1 }` beside `enum_set`, and
  `members: integer_member_set?` on `integer_type` — `integer`-typed as that constructor's bounds are, §7.4's
  own "exception that proves the rule" about which tier can name its own constrained atom.
- meta.tn: the same pair for `decimal_type`, `value`-typed as its bounds are, under §7.4's constraint-fields-
  typed-as-`value` rule and the bootstrap ordering behind it. `float_type` takes none.
- §7.4: extend the sentence that rules the enum out, so the replacements it names cover the space — a contiguous
  range is `min`/`max`, an arithmetic progression is `multiple_of`, a sparse set is `members`, and a choice is
  for alternatives that are not one family. As written, the sentence sends the sparse case to the choice.
- §5.7, §5.4, §8.1, §8.2: no change. The member-set kind, the class derivation and the identity rule all already
  say what this needs.
- Coherence: one sentence wherever the schema-load coherence check is stated — every member must satisfy the
  body's other facets, including a range the family derives rather than stores.
- Identity, for the `value`-typed set only: one sentence saying that a member is read under the atom it
  constrains before the set is formed, so the set's uniqueness rule and §5.7's tightening compare decimals
  rather than whatever §4 decoded each element to. meta.tn's `@doc` on `decimal_type` already carries it; §7.4
  is where a processor implementing a `value`-typed constraint field would look for it, and it is not there.
  Without it a conforming processor may read `[1 1.0]` as two members and then have nothing to compare a
  member against a bound with.

Being a kernel edit, this cannot land as a patch: it re-stamps all three companion digests bottom-up and moves
the `*-resolved.tn` fixtures with them.

**Status against Revision 34:** open, and new against this revision — a proposal, and like #23 one this
implementation now runs. What the entry contributes beyond the shape is the three under-determined points, and
building it confirmed why they matter: writing the facet the obvious way (`members: [integer]?`) gets an array
where the enum precedent gets a set, and loses uniqueness, non-emptiness and §4.3's identity in one stroke.

---

## 25. A schema cannot name its own discriminator field or its own rest field — proposal: two checked annotations in meta.tn, and the three rules §6 needs before a checked annotation can carry either

**Section:** [TSON-SCHEMA] §6 (annotations as types; the two declaration positions; the field-def position),
§5.4 (`@disjoint`'s verified-or-error precedent), §5.2 (field states, `REQUIRED_FIXED`), §5.7 (modifier-only
tightening entries), §5.8 (supertype composition), §7.2 (records are closed), §8.1 (`record_field`, preserved
annotations), §9 (meta.tn's contents); [TSON-DATA] §7.7 (identifiers), §3.1 (annotation preservation).

**Problem:** two facts a schema already fixes but has no way to *state*, both needed by an encoding that
dispatches on members rather than on a type tag, and neither needed by TSON text. Recording them together
because they raise one question — what a schema-side annotation is allowed to do — and because §6's answer is
missing in the same three places for both.

**1. The discriminator.** The pins are ordinary Part 2 and already discriminate:

```
cat => animal & { pet_type: text = cat  lives: int32 }
dog => animal & { pet_type: text = dog  breed: text }
pet => ( cat | dog )
```

Every variant is record-class, so the choice is not disjoint and TSON text asks for its native selector — which
is correct and stays:

```
!box { p: { name: Tom  pet_type: cat  lives: 9 } }
[UNKNOWN_TYPE_REF] /p: 'pet' is a choice -- a value at this position requires an explicit type annotation
(!typeName) naming one of its declared variants to disambiguate: [cat, dog]
```

What the resolved schema does not carry is that `pet_type` is the field a member-dispatching encoding selects
on. Deriving it is possible and wrong on its own terms: with two `REQUIRED_FIXED` fields in every variant
(`pet_type` and, say, a pinned `schema_version`), derivation either picks arbitrarily or picks both, and
whichever it picks it has coupled the wire format to a pin the author fixed for an unrelated reason. That is
the well-known-field-name objection in structural clothing — the wire acquires a dependency on a declaration
nobody wrote for it.

**2. The rest field.** §7.2 makes a record closed under its type, verified here:

```
cfg => { name: text  extra: {text => text} }
!cfg { name: n  a: 1 }   [UNRECOGNIZED_FIELD] /a: unknown field 'a' on 'cfg' -- a record is closed under its
                         type (§7.2), whose fields are (name | extra)
!cfg { name: n  extra: { a => 1  b => 2 } }   OK
```

So "these declared fields plus arbitrary extras" is modelled as a map-typed field, and the map nests on the
wire — again right for TSON text, and again a fact the schema cannot express is which field, if any, an
encoding that flattens is meant to flatten. Nothing in `record` or `map` designates one.

**Both are annotations, and there is a criterion for saying so rather than a preference.** Neither mark changes
what any value's validity: a choice with a designated discriminator admits exactly the variants it admitted, and
a record with a designated rest field admits exactly the values §7.2 already admits. A kernel constructor field
would put an encoding's projection concern into every processor's type model, including every processor that
implements only the text encoding. **An annotation is the right home exactly when the mark changes no value's
validity** — that sentence is what §6 is missing, and it decides this case and the next one without re-arguing.

**Three things §6 has to say. The first is already true and unstated; the third is independent of whether
either annotation lands, and is the one settled below.**

**(a) A checked annotation is a category §6 does not describe.** §6 accounts for three kinds — validated against
its own type, advisory (`@annotation`, "carries no runtime force"), and resolver-derived (`@alias`,
`@synthetic` — and #32 proposes removing `@alias`, which would leave that kind one member). It never says an
annotation may be checked against a fact the resolver derives and make the
schema fail to load. §5.4 nonetheless makes `@disjoint` do exactly that, and this implementation implements it
(`TsonSchemaLinker.checkDisjointAssertions`: `disjoint: true` verifies silently, `disjoint: false` is a resolver
error, no third outcome). One such annotation reads as a special case; three make a category, and §6 is where it
belongs — an annotation carries no decode force, and may carry load-time force where the section defining it
states the check and its outcomes.

**(b) Which position a checked declaration annotation is honoured at.** §6 gives a declaration two annotation
positions with two meanings and forbids hoisting between them. An assertion is an assertion in either, so this
implementation consults both for `@disjoint` — and that was a decision it had to make alone, since §5.4 does not
say. `@discriminator:pet_type` sits in the same two places and inherits the same silence; a processor consulting
one position ignores the other spelling with no diagnostic. Worth stating once in §6 for the category rather
than once per annotation.

**(c) Whether a field annotation survives composition — the rule `@rest` actually depends on, and the one this
implementation got wrong by never deciding.** §6 puts an annotation on a `record_field` and §8.1 preserves it;
§5.8 flattens a composition's inherited fields and §5.7 lets a body entry restate one. Neither says what happens
to the annotation, and a resolver's two paths answered it two ways:

- an **inherited** field is copied whole (`DefinitionResolver.absorb`: `fields.add(field)`), annotations
  included;
- a **restated** field was rebuilt with `Annotations.empty()` and then given exactly what the restatement wrote,
  so the inherited annotations were dropped.

The consequence is that §5.7's modifier-only entry — `extra: ?`, which names no type and is defined to tighten
presence and nothing else — silently un-marks the field. Written out, one type shows the whole of it:

```
account => { @doc:"Pre-2020 registry identifier."  @deprecated  legacy_id: text?
             @doc:"Display name."                                name: text }

premium_account  => account & { legacy_id: = _ }                                  a modifier-only tightening
archived_account => account & { @todo:"drop in v3"  legacy_id: = _ }              a tightening that writes one
```

Before the rule below, that resolved to `legacy_id` carrying nothing on `premium_account` and `@todo` alone on
`archived_account`, while `name` — inherited, not restated — kept its `@doc` in both. So the same record shows
both answers at once, and which one a field gets depends on whether a subtype happened to tighten it.

**The rule, which this implementation now runs:** a restated field's annotations are **the restatement's own,
in source order, followed by the inherited ones, in source order**. Nothing is dropped, no name is treated as a
key, and the two halves are what the rest of the series already requires:

- **Concatenation, not replacement by name**, because [TSON-DATA] §3.1 already makes an annotation name
  repeatable on one value — "an annotation name MAY appear any number of times on a single value; all
  occurrences are preserved in source order". Annotations are a list, not a map. "The inherited `@doc` is
  replaced" needs an identity the model does not give them, and has no defined answer where the source carries
  two. A restated field carrying two `@doc`s is the same shape as a field an author wrote two on directly.
- **Nearer first**, because order is already the precedence mechanism. Every first-occurrence lookup reads it
  that way, `@bytes_encoding`'s own nearest-first resolution included, so leading with the restatement makes the
  nearer declaration win at every such site without any of them knowing about composition.

The cost is that a subtype rewriting an inherited `@doc` leaves the field carrying both, which is what
"annotations are not removable" buys and what §3.1 accepts everywhere else. The alternative that would remove it
is not per-name replacement but a **cardinality** on a declared annotation — `@annotation` gaining an
at-most-one-per-value form, at which point replacement has a defined meaning and a schema-load check to enforce
it. That is a real addition to §6 and is named here as the option rather than the recommendation.

**Interpretation chosen — both annotations are declared, and neither is checked.** meta.tn carries
`discriminator => @annotation field_name` and `rest => @annotation void`. They resolve one hop against the
governing meta (§3.3.3) and round-trip into resolved output like any author annotation, so a schema may state
both facts and a member-dispatching encoding may read them. Everything else here is unchanged: `@disjoint` is
still the only annotation with load-time force, a pinned-record choice still derives `disjoint: false` and is
tagged in text, and a map-typed field still nests.

**The three rules are what is missing, and they are the entry's real content.** (a) and (b) are prose the spec
owes — the checked-annotation category, and which of §6's two declaration positions honours it — and neither is
written. Nor are the checks themselves: nothing verifies that a discriminator's every variant is a record
declaring the named field, that the field is `REQUIRED_FIXED` in each, or that the fixed values are pairwise
distinct; nothing verifies that a `@rest` field's type resolves to a text-keyed map. So both annotations are
today what §6 calls advisory, carrying no load-time force, where the design says they carry it.

**(c) is fixed here and is the one part of this entry that is not waiting on the spec.**
`DefinitionResolver.resolveField` merges the restatement's annotations over the inherited ones, one path serving
§5.7's refinement and §5.8's composition alike, and `RestatedFieldAnnotationsTest` pins each case — the
modifier-only entry, the tightening that writes its own, a repeated name kept twice with the nearer one first,
and the refinement spelling getting the same answer as the composition spelling. It unblocks the `@rest` check:
"at most one per composed chain" is now a count along a chain no restatement can sever.

**Two things building it settled that stating it did not.** First, **the rule has read-side force, and that is
what makes its ordering half more than a convention.** `@bytes_encoding` on a field is resolved nearest-first by
first occurrence, so a restated field is read in whichever alphabet its merged annotations lead with:

```
envelope        => { @bytes_encoding:HEX  digest: bytes? }
sealed_envelope => envelope & { digest: bytes }        tightens presence, writes no annotation of its own

!sealed_envelope { digest: "deadbeef" }
    was   75e69d6de79f   six octets, base64 — the restatement dropped the directive with everything else
    now   deadbeef       four octets, hex   — the restatement inherits it
```

The old answer was a *different value*, not an error: no diagnostic, no length complaint, six octets where the
schema says four. Inherited-first ordering produces the same class of defect from the other end, handing a field
restated under its **own** `@bytes_encoding` the alphabet of the type it tightens instead. Restatement-first is
the only order under which both come out right, which is why the ordering is forced rather than chosen.

Second, **the three bundled schemas are unaffected, and verifiably so**: none of meta-kernel, meta.tn or core.tn
writes a field-position annotation at all, so nothing there can inherit one. No `*-resolved.tn` fixture moves and
no digest is re-stamped — a change to resolved output that touches none of the published artifacts.

**No conformance vector can carry it, for #5's reason rather than a new one.** §5.8 and §8.1 are silent, so a
processor that replaces is as conforming as one that merges: a vector asserting either fails a conforming
implementation of the current revision. The cases live in this repo's tests until the rule is stated, and the
corpus's `class2/schema/` layer stays silent on it.

**A second shape is on the table, and this entry deliberately does not choose between them.** Everything above
puts the mark on a *choice* declaration. The alternative puts it on a **field of a base record**, and lets the
subtypes carry the fixed values:

```
pet  => { @discriminator pet_type: text }
dog  => pet & { pet_type: text = dog  breed: text }
cat  => pet & { pet_type: text = cat  lives: int32 }
pets => [pet]
```

Measured here, that resolves as written: `pet.subtypes` is `[dog, cat]`, the annotation survives resolution on
`pet`'s `pet_type` `record_field`, and each subtype's restatement lands `REQUIRED_FIXED` carrying its token. The
tagged form already validates — `!pets [ !dog { pet_type: dog  breed: lab } ]` — on §8.2's subtype index and
nothing new. Untagged, `{ pet_type: dog  breed: lab }` is `UNRECOGNIZED_FIELD` against `pet`, a record being
closed under its type (§7.2). So the carrier works and the dispatch is the whole of what is missing.

What the shape changes:

- **(b) is answered, and more simply than the choice-based shape answers it.** A field has one annotation
  position, so §6's two-positions silence never arises and nothing has to say which spelling is honoured.
- **(a) is changed rather than answered.** Dispatching an untagged record at a `pet` position is decode force in
  the reference encoding, which the proposal above explicitly denies ("text keeps `!variant` at every
  non-disjoint choice") and which the criterion for annotation-hood does not admit: a record invalid at a `pet`
  position today would become valid, so the mark *does* change a value's validity. Either the criterion needs
  restating for a mark that adds an admissible spelling without adding a value, or the fact belongs in the
  kernel after all.
- **The base is inhabited, and the choice-based shape never meets this.** `!pets [ { pet_type: anything } ]` is
  valid today — a bare `pet`. Under untagged dispatch an untagged record whose `pet_type` matches no subtype's
  fixed value is either a `pet`, or an error, or evidence that a discriminated base must be uninhabitable, and
  the three are three different formats. A choice has no values of its own and poses none of it.
- **The mechanism is `subtypes`, not `variants`.** §5.4's derived disjointness is not involved at all; the check
  would run over the subtype index, and would owe an answer for a subtype that leaves the field unfixed and
  makes dispatch ambiguous after the fact.

**Suggested resolution:** stated below for the choice-based shape, which is what is running. The field-based
shape is not excluded and would move the first two bullets.

- meta.tn, not the kernel: `discriminator => @annotation field_name` and `rest => @annotation void`, on the
  criterion above. `field_name` is an `identifier`, so the discriminator names a field the way every other
  naming position in the series names one, and a non-name spelling fails at the annotation's own type.
- `@discriminator:pet_type` on the choice declaration, `@rest` on the designated field. Checks on §5.4's
  verified-or-error precedent, both at schema load, both two-outcome: for the discriminator, every variant is a
  record declaring the named field, that field is `REQUIRED_FIXED` in every variant (never `REQUIRED_DEFAULT` —
  a default is omissible and so cannot dispatch), and the fixed values are pairwise distinct; for the rest
  field, its type resolves to a text-keyed map and at most one field per composed chain carries the mark.
- §6: the three sentences of (a), (b) and the criterion. Plus the licence the two exist for — an annotation
  carries no force in the model or in the reference encoding, and an encoding-rules document may bind
  projection behaviour to a schema-side annotation declared for it. Force stays confined to the encoding that
  claims it; text keeps `!variant` at every non-disjoint choice, discriminated or not, so §5.4's tagging rule
  needs no change.
- §5.8/§8.1: the field-annotation rule of (c), which is owed whether or not `@rest` lands — *a restated field's
  annotations are the restatement's own, in source order, followed by the inherited field's, in source order; a
  restatement adds and never removes.* One sentence, and it is what makes "at most one `@rest` per composed
  chain" a rule that can be checked.
- §8.1: otherwise no change. Both ride the existing author-annotation preservation channel, which already keeps
  declaration and field annotations through output and ingest.

**Status against Revision 34:** open, and new against this revision — a proposal, and like #23 and #24 one this
implementation now runs in part: the two annotations are declared, (a) and (b) are unwritten, the two checks are
unwritten, and (c) is built. That split is the entry's own point made concrete. Declaring an annotation is nearly
free and is worth nothing on its own — (a) and (b) are already load-bearing for `@disjoint` and unstated — and the
vocabulary being present while the force is absent is exactly the state §6 has no words for. (c) was found by
asking what a checked field annotation would have to survive, and it is the part that could be settled without
the spec, because a resolver has to do *something* and doing nothing was itself an answer.

**Which shape the mark belongs to is held open for this cycle, on purpose.** It is not a question the schema
layer settles by itself: both shapes resolve, and choosing between them wants validation against real documents
and against how each maps to classes in a binding implementation — which is where an untagged-dispatch design
either pays for itself or does not. So (a)'s criterion, the position, and the checks all stay unresolved pending
that work, and the answer will move a later revision rather than this one. (c) is unaffected by that hold and is
owed either way: it is a rule about field annotations, not about discriminators, and it is stated above as a
report rather than a request.

---

## 26. The constraint and annotation vocabulary is incomplete in three places and over-complete in a fourth — and the sharp one is an asymmetry, not an omission: the temporal families have no exclusive bounds

**Section:** [TSON-SCHEMA] §5.11 (field groups), §5.7 (the ordered-bound facet kind), §5.5 (bodies are closed;
the temporal `precision` facets), §6 (an unresolvable annotation is a resolver error), §9 (the companion
artifacts); meta.tn's `date_type`/`time_type`/`datetime_type`/`duration_type`/`float_type`, meta-kernel's
`array` and `integer_type`.

**Problem: 1 — every numeric family states its bounds as a field group and no temporal family can state an
exclusive bound at all.** The four numeric vocabularies write each side as §5.11's group, so at most one bound
per side holds by shape:

```
integer_type  =>  ( min: integer | exclusive_min: integer )?   ( max: integer | exclusive_max: integer )?
decimal_type / float_type / rational_type  =>  the same pair, value-typed
```

The four temporal vocabularies write neither the group nor the exclusive facet:

```
date_type      => ~atom & atom_specification & { spec: = …  min: value?  max: value? }
time_type      => …  min: value?  max: value?  precision: integer?
datetime_type  => …  min: value?  max: value?  precision: integer?
duration_type  => …  min: value?  max: value?
```

So the commonest range shape there is — a half-open interval — has no spelling on a date:

```
window => !date ^ { min: "2025-01-01"  exclusive_max: "2026-01-01" }
[SCHEMA_ERROR] /window: the body is not valid data for 'date_type', the constructor's own constraint
vocabulary -- unknown field 'exclusive_max' on 'date_type' -- a record is closed under its type (§7.2),
whose fields are (spec | min | max)
```

**This reads as an oversight rather than a decision, and the section's own habits are the evidence.** §5.7
declares the ordered-bound facet kind once, "for every family", and names `min`, `max` and "exclusive bounds"
together in it. Where a family really does omit a facet on purpose, the spec says why at the family: meta.tn's
`float_type` carries a paragraph explaining that it has no `multiple_of` because "a step constraint cannot hold
on a binary grid", and §5.5 explains the temporal `precision` facets and the *absence* of a timezone facet in
its own text. The missing exclusive bounds are explained nowhere, and a date's value space is discrete and
totally ordered — the one shape where `exclusive_max` is exactly `max` minus a day and the omission costs an
author an off-by-one they have to compute themselves.

**And the bound the temporal families do have raises a question of its own for `duration`.** §5.7 classes
`min`/`max` as ordered bounds, which presumes the value space is ordered; an ISO 8601 duration is not, since a
calendar-based duration has no fixed length to compare against a clock-based one (`P1M` against `P30D`). This
implementation therefore parses `duration_type.min`/`max` and enforces neither — `DurationParser` states it
outright: "No min/max bound validation is performed yet … not `Comparable`". That is a real gap here, and it is
downstream of a real one in the spec: `duration_type` declares two ordered bounds over a partial order and
§5.7's rule for the kind cannot be applied to them. Either the comparison is stated (a total order over the
calendar-independent seconds, say, with the calendar components refused in a bound) or the facets go; adding
`exclusive_min`/`exclusive_max` here without answering it would double an unenforceable pair.

**Problem: 2 — `array` has no `contains` family.** "At least one element satisfying T" is not expressible:

```
needs => !array { element_type: text  min_contains: 1 }
[SCHEMA_ERROR] /needs: unknown field 'min_contains' on 'array' -- a record is closed under its type (§7.2),
whose fields are (access_pattern | size_type | element_type | state | unordered | unique_items | min_items |
max_items)
```

Purely additive as vocabulary, but **not** as §5.7 text: existential quantification over elements is a facet
kind the taxonomy does not have. §5.7 declares five — ordered bound, permission, member set, selector, fixed —
and `contains` is none of them, while `min_contains`/`max_contains` are ordered bounds over a count the
`contains` facet defines. So the addition owes §5.7 a sixth kind and its refinement direction (tightening
raises the floor and lowers the ceiling; narrowing the `contains` type itself tightens). It is the one item in
this entry with resolver-semantics wording to write rather than a field to add.

**Problem: 3 — `unique_items` on an ordered array is already there, and a stale queue says it is not.** Worth
recording because the cost of a stale entry is a revision cycle spent rediscovering it. `array` carries
`unordered: boolean ~ false` and `unique_items: boolean ~ false` as *separate* facets, and `set` is the
refinement that fixes both true — so an ordered-but-unique array is ordinary and needs nothing:

```
tags => !array { element_type: text  unique_items: true }        OK
```

**Problem: 4 — the documentation annotations stop at `doc`.** meta-kernel declares `annotation`,
`documentation`, `doc`, `alias`, `synthetic`; meta.tn adds `ordered`, `bounded`, `exact`, `numeric`,
`disjoint`, `deprecated`, `since`, `todo`, `lang`. There is no `title`, `examples`, `read_only` or
`write_only` — the four every schema language carrying documentation has, and the four a converter from one of
them must drop. **The absence is a hard stop rather than a nicety**, because §6 makes an annotation whose name
does not resolve a resolver error, with no preserved-uninterpreted fallback under a governing schema:

```
@title:"X" thing => { a: text }
[SCHEMA_ERROR] /thing: '@title' does not name a type in the governing meta-schema's namespace, which is the
whole annotation namespace of a schema document (one hop through !!meta, §3.3.3)
```

An author cannot work around it locally either: §3.3.3 confines the annotation namespace to the governing
meta, so a name the schema declares or imports itself is not usable as an annotation within that schema.

**Interpretation chosen — three of the four are running, and problem 1 got a better answer than it asked for.**

**Problem 1 is closed by splitting the type, not by adding facets to it.** The entry frames the duration half
as a question to answer either way: state the comparison, or drop the facets. The third option is the one taken
— `duration` is elapsed time, a signed number of *seconds* (RFC 3339 Appendix A's grammar with a leading sign,
a fraction on the seconds component, omissible components, and no Y or month-M), and `period` is a calendar
span, a signed number of *months* (Y and M only, no D, W or T part). Neither value space is partially ordered,
because the thing that made the old one partial — a month with no fixed length beside a second with one — is
now a different type. A span that is genuinely both is a record with a field of each.

That is what makes the facets enforceable rather than declared: both families carry `( min | exclusive_min )?`
and `( max | exclusive_max )?` as §5.11 groups, plus `multiple_of` (strictly positive, sign ignored when
testing) and, on `duration`, `precision`. `date_type`, `time_type` and `datetime_type` gained the same bound
groups. `AtomCoherenceTest` used to carry a test named `durationBoundsAreLeftUnjudged`, pinning the gap
deliberately; it now asserts the opposite.

**Problem 4 is running**: `title`, `examples`, `read_only` and `write_only` are declared in meta.tn beside
`deprecated`/`since`/`todo`/`lang`. `read_only`/`write_only` are void presence markers rather than booleans —
a bare mark is the same statement as `true` and admits no second reading.

**Problem 3 stands as written**: nothing changed, because nothing needed to.

**Problem 2 is withdrawn.** `contains`/`min_contains`/`max_contains` were built and backed out — see the
resolution below.

**Suggested resolution:**

- meta.tn: `( min: value | exclusive_min: value )?` and `( max: value | exclusive_max: value )?` on
  `date_type`, `time_type` and `datetime_type`, matching the numeric families' shape exactly so §5.11 does the
  work and §5.7 needs no new text. `duration_type` waits on its ordering question, which wants answering
  either way.
- meta-kernel: **not** `contains`/`min_contains`/`max_contains`, withdrawn after being built. JSON Schema's
  `contains` is an *applicator* — a subschema tried against each element, counting successes — and that rests on
  "does this instance validate against S" being a total, side-effect-free predicate, which is what a validation
  language has and this one does not. Here a type is a *reader*: `ChoiceReader` dispatches on a precomputed
  discrimination class and keeps the tag where two variants share one, rather than trying a variant and falling
  back, and §5.4's whole disjointness apparatus exists to make dispatch decidable by class instead of by trial.
  An unrestricted `contains` reintroduces trial-and-error per element and needs a speculative-read mode or
  per-element buffering, both against the pull-based streaming model. A **restricted** form does translate and
  is worth writing up: require `contains` to resolve to a type `element_type` already admits — a refinement of
  it, or one of its variants when it is a choice — and the check becomes a constraint over the value the single
  read already produced. "At least one element is variant `primary`" is the common real use and lands inside
  it. Three things want settling first: this entry asserts the refinement direction is monotone and it is not
  (narrowing the `contains` type tightens against `min_contains` and loosens against `max_contains`, which is
  one facet doing two jobs); `min_contains: 0` is JSON Schema's own wart, vacuous and awkward against
  `max_contains`; and the implicit `minContains: 1` wants writing out.
- meta.tn: `title => @annotation text`, `examples => @annotation [value]`, `read_only => @annotation boolean`,
  `write_only => @annotation boolean` beside `deprecated`/`since`/`todo`.
- Nothing on `unique_items`; the queue is what needs the edit, not the schema.
- §5.7: one sentence saying that an ordered-bound facet requires its family's value space to be totally
  ordered, which is what `duration_type` is currently the counter-example to.

Being kernel and meta edits, this re-stamps the companion digests bottom-up and moves the `*-resolved.tn`
fixtures with them.

**Status against Revision 34:** open, and new against this revision. Problem 1 was the sharpest of the four and
is now the most changed: a defect against §5.7's own per-kind rule, answered by splitting the family rather than
by either of the two options this entry offered, and running. Problem 4 is running. Problem 3 is a correction to
a queue rather than to the spec. Problem 2 is **withdrawn** — the one item here that asked for a facet kind
§5.7 does not have, and the one that turned out to be asking this format to be a validation language.

---

## 27. §7.5 leaves set element order implementation-defined for exactly one field in the whole meta layer, and requires a comparison rule nothing else in the series wants

**Section:** [TSON-SCHEMA] §7.5 (sets; element order; the comparison MUST), §1.3 (a resolver MUST produce a
resolved schema value; output MUST conform to §8), §8 (the serialization contract), §7.4 (enum member
semantics), §9 (`enum_set`); [TSON-DATA] §8.1.

**Problem:** §7.5 says element order in a set is implementation-defined, then puts a MUST on everyone who
compares resolver outputs:

> Sets are unordered; the materialised representation uses array syntax, but element order is
> implementation-defined. Implementations comparing resolver outputs MUST compare set-typed fields as sets, not
> ordered lists; fixture-comparison tools SHOULD canonicalise set-typed fields (e.g. lexical sort) before
> byte-comparison.

Three things make that rule cost more than it buys.

**1. It applies to one field.** `enum_set` is the only `!set` in meta-kernel, meta.tn and core.tn combined, and
its only use is `enum.members`. Every other list in §8's output — `supertypes`, `subtypes`, `variants`,
`elements`, `fields`, `groups`, `parameters` — is an array whose order is either significant or, for the two
§8.2 calls "name-level indexes", already free by that section's own words. So the whole of §7.5's
implementation-defined order and the MUST that compensates for it exist for enum members and nothing else.

**2. It puts an obligation on the wrong side of the comparison.** §1.3 makes producing a resolved schema value
a MUST and fixes its serialization in §8, so resolved output is the artifact the series checks against — the
companion `*-resolved.tn` documents are exactly that, byte-fixed and published. §7.5 then makes those bytes one
conforming output among many, and moves the burden onto every consumer to know which of §8's fields are
secretly sets. A structural comparison of two resolved documents cannot be written from §8 alone; it has to
carry a table of set-typed positions read out of §9.

**3. Nobody wants the freedom, and this implementation is on the wrong side of the MUST.** `EnumBody` holds
`List<String> members` in source order, and `ResolvedForm.canonical` — shared by `ResolvedFixtureTest` and the
Class 2 conformance runner — normalises `supertypes`/`subtypes` and states that "nothing else is normalised; a
difference anywhere else is a real one". So enum members are compared as an ordered list, in the two places
this implementation compares resolved output. That is a conformance gap against §7.5's MUST and it has never
surfaced, because source order is what every producer emits and what §7.4's own reading of an enum makes
natural. The rule is a freedom nobody exercises, guarded by a MUST nobody keeps.

**The direction worth considering is the opposite one.** §7.4 already ties an enum's discrimination class to
its members' own tokens and describes members as declared names; source order is meaningful to a reader, and a
binary or ordinal-based encoding needs it to be canonical rather than free — an encoding assigning members
ordinals cannot do so from a set whose order the spec refuses to fix. Making member order significant in
resolved output costs nothing anyone has, since every implementation preserves it already, and turns §7.5's
comparison MUST into a rule that needs no table: two resolved documents are compared as §8 writes them.

**Interpretation chosen:** Revision 34's *representation* as written, and — stated plainly because it is a
divergence — not §7.5's comparison MUST. `EnumBody` preserves source order, `!enum [OPEN OPEN]` is refused by
the set's uniqueness contract, and both comparison sites treat `members` as an ordered list. Nothing here
canonicalises a set-typed field before comparing, and no set-typed field other than `enum.members` exists to
canonicalise.

**Suggested resolution:** in §7.5, replace the implementation-defined order with a stated one, and delete the
comparison MUST and the fixture-tooling SHOULD that exist only to absorb it. Two candidates, and the choice
should be made on what §8's output is for rather than on set theory:

- **Source declaration order is canonical** for `enum.members` (and for any future set-typed field, by the same
  rule). It is what every producer already emits, it keeps §8's output comparable as written, and it is what an
  ordinal-assigning encoding would need. §7.5's "sets are unordered" stays true of the *value* — uniqueness and
  set equality are unchanged — and becomes a statement about semantics rather than about bytes.
- **Bytewise-ascending order of the elements' canonical encodings**, if a canonical form is wanted that two
  producers reach independently from differently-ordered sources. This is the stronger determinism guarantee
  and the one a content-addressed or binary encoding would want; it costs the author's declaration order, which
  §7.4 gives a reader a reason to care about.

Either way §7.5 keeps its duplicate-handling paragraph and its uniformity sentence unchanged, and the entry's
point stands under both: the field's order should be *stated*, not left free and then compensated for by a rule
on everyone downstream.

**Status against Revision 34:** open, and new against this revision. Unlike #24–#26 this is not a proposal for
new vocabulary but a rule to remove: what it asks is that one under-exercised freedom be spent, so that the
comparison MUST it necessitates can go. It records a divergence rather than a build — this implementation does
not implement §7.5's comparison rule, in either of the two places it compares resolved output.

---

## 28. §7.5 and §2.6 both delegate to "the element type's equality contract", and no atom whose value space differs from its lexical space has one — so each processor invents it, and this one has now had to

**Section:** [TSON-SCHEMA] §7.5 (sets; the duplicate rule and its equality delegation), §5.2 (FIXED values),
§5.5 (constructor application; what an instance denotes), §9 (`bytes_type`, the temporal families, `uuid_type`,
`rational_type`), §10.2/§10.3 (hash pinning and canonical identity); [TSON-DATA] §2.6 (map key identity is the
decoded value), §4.3 (numeric equivalence — the one family that *does* have the rule). Companion to #29, which
replaced the four `binary` spellings this entry was first opened against.

**Problem:** three separate rules in the series compare two decoded values, and each delegates the comparison
to a contract the type is supposed to own:

- §7.5: "Two values are duplicates if the element type's **equality contract** considers them equal (name
  identity for a set of `identifier`, value equality for a set of `integer`)."
- [TSON-DATA] §2.6: key identity is the decoded host value, type-ref and annotations stripped.
- §5.2: a stated FIXED value is checked against the declared one.

For exactly one family the contract is written down: [TSON-DATA] §4.3 states numeric equivalence, so `255`,
`0xFF` and `+255` are one value and `1` and `1.0` are two. §7.5's two examples are the two easy families —
`identifier` (NFC text) and `integer` (§4.3). **Every family whose value space differs from its lexical space
is left without one**, and `bytes` is the case where that is not a corner.

**`bytes` is one value space with four spellings, and #29 is what made that plain rather than what caused it.**
Under that entry's design there is one `bytes` type over octet strings and `@bytes_encoding` names the alphabet
a text encoding reads and writes it in, so the spelling is no longer a *type* — which is the half #29 fixed.
What it does not fix is this one: nothing states whether two spellings of one octet string are one **value**.
Within a single alphabet the question is unavoidable, because both spellings land at the same position:
`"abcd"` against `"ABCD"` under `@bytes_encoding:HEX`, hex being case-insensitive by every definition of it,
and the type's own `@doc` saying only what the alphabet is. Across two alphabets — `"4869"` under HEX against
`"SGk="` under BASE64 — the directive makes one alphabet apply per position, so the two meet only across
positions, documents or encodings, which is exactly where §10's content addressing lives. The same silence
reaches base64's padding and its two alphabets.

**Measured here, and the implementation had to answer before the spec did.** A set over `bytes` used to accept
every duplicate there is, `BytesParser` being `AtomType<byte[]>` and Java's `byte[]` carrying identity
equality, so no two decoded binary values were ever equal:

```
keys => !set { element_type: bytes }
!holder { k: [ "SGk=" "SGk=" ] }   OK        (the same spelling, twice)

ts => !set { element_type: text }
!h { k: [ "a" "a" ] }   [TYPE_MISMATCH] /k/1: 'ts' requires unique elements, 'a' appears more than once
```

That was this implementation's own bug and is fixed: one `ValueIdentity` answers all three rules, a `byte[]`
compares as its octets, and a set, a map key and a FIXED field agree. The FIXED case was the sharp one and
worse than "never fires" — a `bytes` field with a fixed value rejected every document including the only one it
can accept.

**Fixing it did not answer the spec question; it forced an answer.** Two spellings of one octet string now
compare equal here — `"abcd"` and `"ABCD"` under `@bytes_encoding:HEX` are one value, because the decoder is
case-insensitive and the comparison is over what it produced. **That is a decision this implementation made
rather than one it read.** Nothing in the series says they must be, and an implementation whose hex decoder
rejected uppercase would be equally conforming and would disagree. There is no way to write the comparison
without deciding it, which is this entry's point restated as running code rather than as a gap.

That is also why **the three vectors this added to the corpus all state one value in one spelling, twice**: a
value equals itself under any contract there could be, so those are safe, and a vector turning on two
spellings would fail a processor answering the open question the other way. The case that most wants a vector
is the one that cannot have one until §7.5 says what equality is.

**The stake is larger than duplicate detection, and §10 is where it bites.** §10.3 defines canonical identity
and §10.2 pins content by hash. If equality is over the spelling rather than the value, then one octet string
has four hashes and a content-addressed reference is encoding-dependent — the property hash pinning exists to
provide. That consequence follows from the same undefined contract, and it is why this wants a general clause
rather than a sentence about sets.

**The general shape, and the families it decides.** The clause is one sentence: **a type denotes a value space;
an encoding defines a lexical space and one canonical form per value; equality, ordering, refinement,
disjointness and content addressing are defined over value spaces only.** Applied to Revision 34's vocabulary
it settles a list that is currently settled nowhere:

- `bytes` — octet strings; the alphabet is a spelling (#29), and case in hex and padding in base64 are lexical.
- the temporal families — RFC 3339 admits `Z` and `+00:00` for one instant, and `2025-01-01T00:00:00Z`
  against `2024-12-31T19:00:00-05:00` is one instant in two spellings.
- `uuid` — canonical lower-case hex is a form, not the value.
- `rational` — `"2/4"` and `"1/2"`, where meta.tn's `rational_type` `@doc` explicitly says constraints
  "operate on the value, not the written token: tokens are not normalized", which is the right rule stated for
  one family and generalisable to all of them.
- `text`/`identifier` — already answered (NFC), and the answer is the same shape.

**Interpretation chosen: no general contract, because there is none to read — so six families are answered six
ways, and two of the answers are accidents of a host type.** What each compares as here:

| Family | Compares as | Where the answer came from |
|---|---|---|
| `text`/`identifier` | NFC-normalised text | §2.6, and stated |
| the numeric tiers | §4.3 equivalence, through `NumericIdentity` | stated |
| `rational` | the value — `"2/4"` and `"1/2"` are one | meta.tn's own `rational_type` `@doc` |
| `bytes` | the octets, through `ValueIdentity` | **decided here**; the series is silent |
| `uuid` | the value, `UUID.fromString` being case-insensitive | the host type, and it lands where the list above wants it |
| `time`/`datetime` | local time **and offset**, `OffsetDateTime.equals` | the host type, and it lands opposite |

The last row is what building the `bytes` answer turned up. `2025-01-01T00:00:00Z` and `2024-12-31T19:00:00-05:00`
are one instant and two values here, because `OffsetDateTime`/`OffsetTime` compare the offset and nothing
normalises before them. (`date` is unaffected: `LocalDate` has no offset and RFC 3339's `full-date` has one
spelling per value.) That is not a considered position — it is what the host type does when nothing has told it
otherwise, and it points the opposite way from the `bytes` row, where the same absence produced value equality.
One processor, one missing clause, two contradictory answers is what a delegation with no target costs.

**Suggested resolution:** the clause above, placed where §5.5 defines what a constructor's instance denotes,
and a cross-reference from §7.5's duplicate rule and [TSON-DATA] §2.6 so the delegation has a target. Then one
line per family in §9's own `@doc`s naming the value space and the canonical form, which is what `rational_type`
already does and what every other family is missing. §5.7 needs no change, and #29 is why: with the alphabet a
directive rather than a selector facet, no facet kind is left in a position to be misread as narrowing a value
space. What is worth stating in its place is the same rule aimed at the directive — **`@bytes_encoding` picks a
spelling, so it must not be able to change what two values compare as** — beside the directive's own definition.

**Status against Revision 34:** open, and new against this revision. It is not a proposal for new vocabulary,
and #29's redesign has removed the last of the structure it might have needed: there is one `bytes` type over
one value space, and the alphabet is a directive that no encoding but a text one reads. What that leaves is
exactly the missing sentence — what the element type's equality contract *is* for an atom whose value space
differs from its lexical space — and it is now the whole of the entry rather than half of it.

**This implementation is now a counter-example rather than a demonstration, which is the stronger evidence.**
When the entry was opened, `bytes` compared by array identity and the gap showed as a check that never fired: a
bug, and easy to read as one implementation's oversight rather than as the spec's silence. It is fixed, and
fixing it meant choosing octet equality with nothing in the series to choose it from — while the temporal
families took the opposite answer from the same silence, and nobody chose that at all. A contract the series
declines to state is one each processor writes anyway, differently. Nothing here waits on this entry any
longer: the comparison is implemented, the corpus vectors it added are the ones that hold under any contract,
and what a revision would change is which of the six answers above are right, not whether there are any.

---

## 29. `binary`'s encoding is a reference-encoding spelling promoted to a type name: there is no type for the value space, and the distinction the four names exist to make cannot survive a non-text encoding

**Section:** [TSON-SCHEMA] §5.5 (constructor application: "construction creates siblings, not subtypes"; the
`!C value` positional form), §5.7 (the selector facet kind), §5.4 (discrimination classes), §4.2
(de-parameterised constructors), §8.2/§10.3 (instantiation identity and canonical identity), §9 (meta.tn's
`binary`, core's four instances); [TSON-DATA] §7 (the text encoding as the reference encoding).

**Problem:** meta.tn declares `binary` as a constructor whose `encoding` field is REQUIRED with no default:

```
binary_encoding => !enum [BASE64 BASE64URL BASE32 HEX]
binary => ~atom & atom_specification & { spec: = "…rfc4648"  encoding: binary_encoding
                                          min_length: integer?  max_length: integer? }
```

and core declares four instances of it and no bare one — `base64 => !binary BASE64`, `base64url`, `base32`,
`hex`. §5.5 makes construction produce siblings: "This form does NOT establish IS-A: construction transfers
only the constructor's `kind`; the result records `source: C` with empty `supertypes`." The published resolved
output shows exactly that, and shows the whole of what separates them:

```
base64 => !type_definition { kind: ATOM  source: binary  body: !binary { encoding: BASE64 } }
hex    => !type_definition { kind: ATOM  source: binary  body: !binary { encoding: HEX } }
```

Four types over one value space — octet strings — differing in one selector facet, with no IS-A between them
and nothing above them. **The distinction is nominal**: `encoding` is a facet in the body, but because
construction founds siblings rather than subtypes, the facet's four settings become four unrelated type names
with no common parent.

**Consequence 1: an author who does not care about the spelling cannot say so.** There is no `bytes`. The
constructor cannot be applied without choosing an alphabet, because `encoding` is REQUIRED and undefaulted:

```
anybytes => !binary { min_length: 4 }
[SCHEMA_ERROR] /anybytes: missing required field 'encoding' for 'binary'
```

and a choice does not recover it, the four sharing a discrimination class:

```
@disjoint anyb => ( base64 | hex )
[SCHEMA_ERROR] /anyb asserts @disjoint, but its variants [base64, hex] are not disjoint (§5.4) -- two of them
occupy the same discrimination class … so no encoding's single form-resolution pass can tell them apart and
every value keeps its !variant tag
```

So "an octet string of at least four bytes" — a statement about the value space with nothing to say about
spelling — is unspellable, and the nearest approximation costs a `!variant` tag on every value.

**Consequence 2: the distinction cannot survive an encoding whose values are octets.** An alphabet describes
how the *reference* encoding writes bytes as text. A binary encoding writes the bytes. A writer emitting a
`base64`-typed field and a `hex`-typed field there produces identical output and is right to — there is
nothing in the octets for the alphabet to be a property of, so such an encoding does not so much ignore the
selector as have no place to put it. But the *type identity* is `base64` or `hex`, and identity is what §8.2
keys instantiation on and §10.3 canonicalises. Two schemas differing only in the alphabet therefore describe
byte-identical binary streams while being different types, and a hash pinned over the schema differs for
documents that are indistinguishable on the wire.

**Consequence 3: a round trip through such an encoding is value-preserving and not text-preserving, and
nothing says so.** Going text → binary → text, the alphabet has to be recovered from the schema, since the
intermediate carried none. That is fine and probably desirable, but it means a document's own spelling is not
its own: a schema saying `hex` re-spells a base64 document's bytes as hex, and the series currently
frames the alphabet as part of what the type *is*, which invites the opposite expectation.

**The constraint that produced this design is real, and it is why the obvious fix is not obvious.** A generic
`bytes` position cannot be read from text: `"abcd"` is well-formed hex *and* well-formed base64, and decodes to
different octets. The alphabet must be known at the position or the reference encoding cannot read the value at
all. So the selector is not gratuitous. What is questionable is satisfying that need through **nominal type
identity**, which exports a reference-encoding concern into the type model — where §5.4's classes, §8.2's
identity and every other encoding then have to carry it, and only one of them can act on it.

**Interpretation chosen — a design this implementation now runs, and the third of three: not the entry's own
first proposal, and not the directive that replaced it.** The four sibling types are gone. There is one type,
`bytes`, whose value is the octets, and the alphabet is a **selector facet** of it (§5.7).

```
meta.tn:  bytes_encoding => !enum [BASE64 BASE64URL BASE32 HEX]
          bytes_type     => ~atom & { encoding: bytes_encoding ~ BASE64
                                      length: non_negative_integer?
                                      min_length: non_negative_integer?  max_length: non_negative_integer? }
core.tn:  bytes => !bytes_type { encoding: BASE64 }   — and no spelled subtypes at all
```

An author who wants another alphabet declares another type —
`hexdigest => !bytes_type { encoding: HEX  length: 4 }`, a fresh instance of the constructor rather than a
refinement of `bytes` (below). `bytes_type` composes with `atom` alone and carries no
`spec`: RFC 4648 governs spellings, not octets, so an octet sequence has no specification to name. The length
facets count decoded octets, so `length: 32` is a 32-byte digest whether it arrives as 64 hex characters, 44
base64 characters or 32 raw bytes.

**The directive was built, run, and replaced — and what replaced it is what the entry's own reasoning implies
once "type" is read carefully.** The argument for a directive was that the alphabet is lexical metadata of a
text encoding and so no part of the type. That is true *of values* — the octets are the value, and a selector
never changes what two values compare as. But a type does two jobs: it names a value space, **and** it says
which documents are valid. In a text encoding the alphabet decides the second, so it is part of the type's
contract even though it is no part of its value space. The directive design equivocated between the two.

**What settles it is the container element.** `[hexdigest]` can only work if the element's own type carries
the alphabet — an element has no annotation position, and none of the container readers consults one — so an
annotation can never express "an array of hex-spelled digests" without new syntax at every container. Carried
as a facet it costs nothing, because a type is exactly what an element names. The same argument reaches a
template argument: §8.2 dereferences a pure rename in an application's argument (#32), so an alphabet carried
outside the type is normalised away at `box<hexdigest>` — measured, four hex octets read as six base64 ones,
silently. As a facet, `box<hexdigest>` and `box<bytes>` are different types and the question does not arise.

**The cost, stated plainly.** An octet-valued encoding sees `encoding: HEX` in the type and must ignore it —
the only facet some encodings disregard entirely, where `length` and `pattern` constrain values and every
encoding honours them. That is the objection the directive design was built to avoid, and it is real. It buys:
the alphabet reaching every position a type reaches, no annotation-force category needed to make identity
behave, and two types differing only in alphabet being two types — which is what a reader of either needs them
to be.

Part 1's built-in vocabulary follows: **`!bytes` is the only binary tag, and it is base64.** A schemaless
document has no schema to carry a directive, so there is nothing for a reader to consult and no way one
spelling could be more right than another; fixing base64 also means `!bytes` names one type in both
conformance classes rather than one in Part 2 and four in Part 1.

**This entry's own two-level resolution was built first, and abandoned — that is the finding.** `bytes` plus
the four as *refinements* resolves, links and gives the IS-A the entry asks for, and it fails on two counts
that only appear once it runs:

- **The IS-A is degenerate.** All four alphabets have the *same* value space — every octet string is writable
  in base64, base64url, base32 and hex alike — so `hex ^ bytes` narrows nothing at the value level. §5.7 says
  a refinement tightens; that one tightens only the *lexical* space. The four `subtypes` partition nothing,
  and a consumer reading §8.2's index sees four subtypes of `bytes` that all denote one set. The complaint
  this entry opens with — "four types over one value space" — reappears inside its own fix.
- **A refinement of `bytes` becomes unreadable.** `anybytes => !bytes ^ { min_length: 4 }` resolves and then
  no value can be written at it: `!hex "abcd"` is refused, correctly, because `hex` is not a subtype of
  `anybytes`. The entry only ever says "a type-ref at an *unrefined* `bytes` position", so it never meets
  this case. Refining from a spelled type instead is choosing an alphabet again, which is consequence 1.

Underneath both: **resolving the ambiguity with `!hex "abcd"` makes the spelling a type claim**, which is the
conflation the entry exists to complain about. A directive is the shape that says what the entry means.

**Suggested resolution.** The first half stands as written and is now stated in the artifacts themselves
(`bytes_type`'s and `bytes`'s own `@doc`): the alphabet is lexical metadata of a text encoding; an encoding
whose values are octets neither carries nor contradicts it; equality, identity and content addressing are
over the octets and never over the spelling (#28's clause, of which this family is the sharpest case); a
round trip through a non-text encoding preserves the value and re-spells it from the schema.

The second half is **withdrawn twice over**. Not four sibling types over one value space, which is what the
entry opened against; and not an annotation, which was built and replaced for the reasons above. Concretely,
for §5.5, §9 and Part 1 §5: **one `bytes_type` constructor whose facets are `encoding` (a §5.7 selector,
defaulting to BASE64) plus the three lengths**; a `bytes_encoding` enum naming RFC 4648's four alphabets; no
spelled types in core; and `!bytes` as Part 1's single binary tag, base64, a schemaless document having no
type to carry a selector. §5.4's classes carry nothing about spelling, and §8.2's identity carries it exactly
where it should — two types with different alphabets are two types.

**And `encoding` is not refinable**, which is the rule that keeps the facet from rebuilding the very defect
this entry opened against. An alphabet narrows nothing — every octet string is writable in every one of them —
so `hexbytes => !bytes ^ { encoding: HEX }` would claim `hexbytes` IS-A `bytes` while narrowing no value, and
a hex-spelled document is not readable at a base64 position. That is the degenerate IS-A the four sibling
types were removed for; permitting the refinement would let an author rebuild it by hand. Another alphabet is
another **type**, declared as its own instance — `hexdigest => !bytes_type { encoding: HEX  length: 4 }` —
and refining for *length* is unaffected and inherits the alphabet, `sha256 => !bytes ^ { length: 32 }` being
a base64 sha256.

**This is not a rule about selectors, and §5.7 already has one that nearly covers it.** Its selector clause
reads: *"a **selector** facet (an encoding or format discriminant, such as `binary`'s `encoding`, a width
selector, or `complex`'s component kind) may be set where the source leaves it at the constructor's default …
and is thereafter identity-only: a refinement may restate a source-bound selector, never change it."* Three
things are wrong with it here, and two of them are one deletion.

1. **Its own exemplar is the counter-example.** It offers `binary`'s `encoding` as the selector to think of —
   the one facet that must never be settable by a refinement at all.
2. **Set-from-default is precisely the hole.** Core writes `bytes => !bytes_type { encoding: BASE64 }`, so
   `bytes` is *source-bound* and the existing clause already forbids changing it — which is why stating the
   alphabet explicitly in core is load-bearing rather than cosmetic. But nothing stops a schema declaring
   `mybytes => !bytes_type {}`, leaving it at the default, and then `hex => !mybytes ^ { encoding: HEX }`.
   That is permitted by the clause as written and rebuilds the degenerate IS-A.
3. **The kind is not homogeneous.** A width selector is one where narrowing is a real relation — an `int8`
   value space is a subset of an `int16`'s, which is why §5.7 can refuse `!int8 ^ { size: { bits: 16 } }` as
   widening. `encoding` has no such relation for any pair of members: every octet string is writable in every
   alphabet. The property that separates them is **whether the change narrows the value space**, and for a
   spelling facet no change ever does.

**A third case sits under the same clause and is worse than either, which this entry raises rather than
solves.** `complex_type.component` narrows for *some* pairs and not others: its five members are a partial
order, not a chain — `INTEGER ⊂ NUMBER ⊂ RATIONAL` and `FLOAT32 ⊂ FLOAT64`, with the exact and approximate
families incomparable, since `FLOAT64` carries ±inf and NaN that no exact decimal represents. core.tn
documents both kinds in one breath: `!complex ^ { component: INTEGER }` for Gaussian integers, a genuine
narrowing with a sound IS-A, and `!complex ^ { component: FLOAT64 }` for floating-point complex, which is not
a narrowing and whose IS-A is unsound in exactly the way `hexbytes ^ bytes` would be. §5.7's set-from-default
test admits both, so the clause is not merely unenforceable as `BACKLOG.md` records — **as written it permits
an unsound IS-A even where it is enforced**, because whether a change narrows depends on which pair of members
it moves between and not on whether the source had written the facet. What that case needs is a stated subset
relation among the members and a rule over it; it is out of scope here, and named so that the spelling kind
below is not mistaken for a fix to it.

**Concretely, §5.7 wants a sixth facet kind and one deletion.** Add to the kind list:

> a **spelling** facet (a discriminant choosing among lexical forms of one value space, such as `bytes`'s
> `encoding`) is never set or changed by a refinement: it may only equal the source's own value. Unlike a
> selector it has no value-space consequence, so a refinement that changed it would produce an IS-A carrying
> no narrowing — a `hexbytes` claiming to be a `bytes` at positions no base64 reader can honour. A different
> lexical form is a different type, declared as its own instance of the constructor
> (`hexdigest => !bytes_type { encoding: HEX }`).

and strike `binary`'s `encoding` from the selector clause's examples, leaving the width selector — where
narrowing is a real relation and the set-from-default permission is sound. `complex`'s component kind should
come out of that list too, or the clause acquires the third case above as an example of a rule that does not
cover it.

**Two things worth knowing while deciding.** The new kind **arrives enforced where the old one is still
owed**: §5.7's selector rule turns on whether the source *wrote* the facet or took the default, and resolved
output cannot tell those apart — which is why `complex_type.component` is unchecked here and sits in
`BACKLOG.md`. A spelling facet needs no such distinction, comparing effective values, which is what
`BytesType.constraintsCheck` does. And the **remedy clause is doing real work**: without "a different lexical
form is a different type", the rule refuses and leaves the author no route. That sentence is already in
meta.tn's own `@doc` and in the error message, so the spec would be catching up to what runs rather than
inventing.

**What is running, and what is not.** All of the above is running and pinned — `BytesEncodingSelectorTest`
covers one value spelled three ways, the base64 default, every position such a type reaches (field, array
element, map value, tuple element, alias, template argument), that two alphabets are two types, that
`encoding` is refused at a refinement, and that refining for length inherits the source's alphabet. What is
not: the matching schema-load checks for `@rest` and `@discriminator` (#25), which no longer have a
`@bytes_encoding` sibling to be done alongside. And this implementation's binary equality is settled, not
open — `ValueIdentity` compares octets, which is #28's answer for this family whatever the alphabet.

**Status against Revision 34:** open, and new against this revision. Companion to #28 and a different
question: #28 asks what equality over a binary value *is*; this asks what the type is and what part of it is
an artifact of one encoding. Unlike #23–#26 this entry reports a design that **replaced its own first
proposal after that proposal was built** — which is the strongest form the register can take, and the reason
the recommendation above is a report rather than a sketch.

---

## 30. §7.8 lets a schemaless document push a schema scope, which is the one place its own "authored intent" rule has nobody to author it

**Section:** [TSON-SCHEMA] §7.8 (the typed-position restriction, final sentence), §7.1 (a document with no
`!!schema` has no type vocabulary); [TSON-DATA] §2.3 (scoped values), §3.3 (where a directive may appear),
§1.2 (conformance classes).

§7.8 ends its typed-position restriction with:

> Schemaless outer documents have no type expectations and always permit nested `!!schema` directives.

The sentence before it gives the rule the whole restriction exists for:

> The outer schema must opt in to receiving foreign values at each position where schema switching is
> permitted — cross-schema acceptance is authored intent, not accident.

The two do not sit together. A schemaless document has no outer schema, so there is nobody to opt in — and
the exemption is granted on the strength of the same absence that makes the opt-in unavailable. "No type
expectations" is why the restriction *cannot* be checked there; §7.8 reads it as why it need not be.

**Three concrete consequences.**

**1. A Class 1 document becomes a Class 2 document halfway down.** §1.2 classes a document by whether a
schema governs it, and §7.1 says a document with no `!!schema` has no type vocabulary at all: base type
resolution applies, and any type annotation outside the built-in vocabulary is preserved unresolved. A nested
`!!schema` under that heading asks a Class 1 read to fetch a schema, resolve a `!name` in it, and validate a
subtree against it — every Class 2 obligation, entered without the document ever having said it was one, and
with §7.1's own sentence still true of the value beside it. A processor asked to read Class 1 either declines
mid-document or silently becomes a Class 2 processor; neither is a position §1.2 describes.

**2. It is the one directive with no cost ceiling.** Every other schema fetch a document can provoke is
named in the header, where a processor sees it before reading anything, and there is exactly one. Here the
count is the number of scoped-value positions the document has, discovered as it is read, in a document
nothing has agreed to validate. §9.1's resource limits are about the document's own size and depth and say
nothing about how many schemas reading one may fetch.

**3. It has no verdict to give.** §7.8's scope push exists so that a value can be *validated* against the
foreign schema. Under a schema, the surrounding document's own verdict is what the pushed value's verdict
joins. Schemaless, there is no such verdict, so a conforming processor has to fetch a schema, resolve a type
and validate a subtree in order to report the result of validating one value in a document it is otherwise
not validating at all — or to ignore what it found, which is the outcome the rule reads as permitting.

**What this implementation does:** refuses it. A nested `!!schema` in a document with no `!!schema` of its
own is a validation error naming the directive, reported once per occurrence; the directive is then consumed
and the value it prefixed is read schemalessly, so one stray directive costs one diagnostic rather than a
value, and a `!name` inside it gets the ordinary Class 1 treatment (`UNKNOWN_TYPE_REF`, §7.1's "preserved
unresolved" surfaced as a reader policy). `ScopePush.refuseSchemaless` is the one place it happens, beside
the typed-position refusal, and `ScopedReadTest.aSchemalessDocumentOpensNoScope` pins it.

**Suggested resolution:** replace the sentence with its converse — *a schemaless document opens no schema
scope: a nested `!!schema` in a document with no `!!schema` of its own is a validation error.* One sentence,
and it makes §7.8's restriction total rather than exempting the case that cannot satisfy it. §1.2's classes
then stay decidable from the header, which is what makes `TsonDocumentHeader`-style routing possible at all:
a reader knows from the first two lines which obligations it has taken on.

The alternative resolution — keep the permission and say what it *means* — is available and worse: it has to
say whether such a read is Class 1 or Class 2, what a processor that will not fetch schemas does with the
directive, and where the pushed value's verdict goes. Three questions where the converse asks none, for a
capability whose motivating example (§7.8's own heterogeneous attachments array) is a schema-governed
document in every line of it.

**What is running, and what is not.** The refusal is running, in both read modes and on both facades.
Deliberately **not** in the conformance corpus: §7.8 as written says the opposite, so a vector either way
would fail a conforming processor, and this repo's own tests carry it instead — the same treatment #5's
template-parameter scopes get.

**Status against Revision 34:** open, and new against this revision. Companion to #23, which rewrites §7.8
around the `scoped` constructor: this is the one sentence of that section #23 does not touch, and it is the
sentence that has to move whether or not #23 is adopted, the conflict being with §7.8's own reasoning rather
than with either vocabulary.

---

## 31. `duration`'s value space is stated as rational and bounded at neither end — proposal: exact decimal seconds, with both ends fixed at a signed 64-bit count of nanoseconds

**Section:** [TSON-SCHEMA] §5.5 (`duration`'s lexical form and value space; `time`/`datetime`'s fractional-second
component), §9 (meta's `duration_type`, `period_type`); [TSON-DATA] §4.3 (numeric equivalence).

Two problems, one of them a wrong word and the other the pair of rules that word was hiding.

**1. The value space is not rational, and saying so costs the facets their definitions.** meta's
`duration_type` says "the value is a signed rational number of seconds". The lexical form permits a decimal
fraction on the seconds component only, so no non-terminating fraction is writable at all — `PT1/3S` is not a
token, and no combination of the admitted components produces a value that is not a terminating decimal. Every
duration is a signed **exact decimal number of seconds**: any magnitude, any number of fractional digits,
nothing rounded. That is `number`'s value space, measured in seconds.

Saying it that way is not only more accurate, it is shorter, because two facets stop being rules of their own:

- `precision: N` is `fraction_digits: N` on the seconds count — the value is a whole number of 10⁻ᴺ seconds,
  `precision: 0` whole seconds, `precision: 9` the nanosecond grid of most hosts. The base type has no
  precision, as `number` has no `fraction_digits`, so a host-bound schema states one.
- `multiple_of` is `number`'s `multiple_of` under the header's uniform rule: `PT15M` admits only quarter-hour
  values, and `-PT30M` is a multiple of `PT15M`.

`period_type`'s matching sentence — "a signed integer number of months" — is already right, and for the same
kind of reason: its grammar admits no fraction anywhere, so the integer claim is one the lexical form
guarantees rather than one the prose asserts.

**2. Exactness with no floor names a value nothing can hold.** RFC 3339's `time-secfrac` is `"." 1*DIGIT` with
no upper bound, so `PT0.0000000001S` — one hundred picoseconds — is a token the grammar admits, and under an
exact value space it denotes exactly that. No mainstream runtime has a type for it:

| Runtime | Type | Finest unit |
|---|---|---|
| Java | `java.time.Duration` | 1 ns (`long` seconds + `int` nanos) |
| Go | `time.Duration` | 1 ns (`int64` nanoseconds) |
| Rust | `std::time::Duration` | 1 ns (`u64` seconds + `u32` nanos) |
| JavaScript | `Temporal.Duration` | 1 ns |
| .NET | `TimeSpan` | 100 ns (`int64` ticks) |
| Python | `datetime.timedelta` | 1 µs |
| PostgreSQL | `interval` | 1 µs |

A nanosecond is the *finest* resolution any of them offers and several are coarser. A value space below the
finest host is not exactness anyone can use: a processor either rounds, which makes every bound comparison
beneath it lie, or refuses, which makes a token the spec admits an error. Both are worse than the spec saying
where the floor is, and the second is what this implementation does today because there is nothing else to do.

**The finer case is not lost — it is spelled, and better.** A schema that genuinely needs picoseconds writes
`number` for it, which is the same value space with the unit under the schema's own control, or declares its
own atom. That is where sub-nanosecond belongs: the unit becomes a statement the schema makes rather than a
fixed second nobody can carry, and nothing pretends the value will survive a round trip through a host's
duration type.

**3. The other end is unbounded too, and there the hosts do not agree.** The seconds component is `1*DIGIT`,
so `PT99999999999999999999S` is a token. Here the floor's argument does not carry over, because the ceilings
differ by nine orders of magnitude:

| Runtime | Type | Representation | Ceiling |
|---|---|---|--:|
| Go | `time.Duration` | int64 nanoseconds | ±292 y |
| .NET | `TimeSpan` | int64 × 100 ns ticks | ±29,228 y |
| PostgreSQL | `interval` | int64 µs (plus months, days) | ±292,277 y |
| Python | `datetime.timedelta` | ±999,999,999 days | ±2.74 My |
| Rust | `chrono::TimeDelta` | i64 milliseconds | ±292 My |
| Java | `java.time.Duration` | int64 seconds + int nanos | ±292 Gy |
| Rust | `std::time::Duration` | u64 s + u32 ns, unsigned | 0 … 585 Gy |

The repeated 292 is 2⁶³ in different units, and Go is three orders of magnitude below the next entry.

**Proposal: the ceiling is 2⁶³ − 1 nanoseconds, and it is normative in both directions.** A conforming
processor MUST carry a value space of *at least* a signed 64-bit count of nanoseconds — so no implementation
may claim conformance with a narrower type — and MUST reject a value whose magnitude exceeds it *even where
its own type could hold one*. Java's `Duration` reaches ±292 billion years; under this rule it still refuses
`PT400000D`. A ceiling only some processors enforce is not a ceiling, and an interchange format whose admitted
range depends on which implementation read the document has not specified one.

**Magnitude, not the signed range.** 2⁶³ − 1 rather than the asymmetric int64 range, so that negating an
admitted duration always yields an admitted duration. The one value that costs — −2⁶³ ns — is representable in
Go and Java and is not worth the rule that `-PT9223372036.854775808S` is legal while its own negation is not.

**Why 292 years is not as tight as it reads: the long-span case already has a better type.** A span of
centuries is a *calendar* span, and `period` carries it as a count of months with no such limit. A clock-based
count of SI seconds beyond that is a physics quantity, and `number` seconds — or a schema's own atom, in its
own unit — is what it wants. That is the same escape hatch the floor uses, which makes the two ends one
argument rather than two unrelated restrictions: **outside the range a host duration type can carry, the value
is not a duration, it is a number with a unit.**

**The ceiling is a value rule where the floor is a lexical one**, and deliberately: the seconds count is
summed from the D, H, M and S components, so `PT999999999999D` overflows with no single component long enough
to catch, where a fraction only ever reaches the value through one component and can be capped at the token.

**The floor belongs in the lexical form, not the value space.** `time-secfrac` restricted to `"." 1*9DIGIT`
makes `PT0.0000000001S` not a token, so the refusal is a parse error at the character, before any arithmetic,
and it is one rule rather than a lexical form plus a value-level "must be a whole number of nanoseconds" that
disagree with it on trailing zeros. It also settles a facet bound for free: `precision` may not exceed 9, which
becomes an ordinary schema-load coherence error rather than a facet that silently admits nothing.

**It is one rule for three families, not one for `duration`.** `time` and `datetime` carry the same unbounded
`time-secfrac`, so the same token is admitted there and the same nothing can hold it. Stating the cap once, on
the production, reaches all three.

**What is running, and what is not.** The nanosecond floor is running, for all three families, and by three
different routes rather than one: `DurationParser` computes the seconds count as a `BigDecimal` and refuses a
value finer than a nanosecond by name, while `TimeParser` and `DateTimeParser` get the same cap as a side
effect of `java.time`'s own parser and report it as a shape error carrying a JDK message about a character
index — the rule is enforced and never named. Unifying that is this implementation's own work and waits on the
rule existing to name.

**All of it is running.** The wording is meta.tn's, so the `@doc` this entry quotes is the one the schema now
carries and the three digests re-stamped with it. Both ends are enforced against the value space rather than
against the host: `PT0.0000000001S` is refused for its tenth digit and `P400000D` for its magnitude, though
`java.time.Duration` would take a span three orders wider. `precision` is `fraction_digits` on the seconds
count and may not exceed nine. A bound is checked at both ends too — from a schema by the parser that reads
its token, and from Java by `coherenceCheck`, so a `DurationType` built programmatically cannot carry one
either. And `duration_type`'s bounds bind at all now, which they did not when this entry was written: every
`value`-typed constraint field in the meta layer arrived as the string §4 made of it, so nothing here had
experience with a duration bound to report.

**The ceiling turned out to close the overflow rather than sit beside it.** `multiple_of` tests on
`Duration.toNanos()`, which throws `ArithmeticException` past ±292 years — a legal schema and a legal document
producing a library fault. 2⁶³ − 1 nanoseconds is exactly the range `toNanos` has, so a value that would have
hit it is now refused before the facet is asked, and the overflow is unreachable rather than merely unlikely.
That is a small piece of evidence for the number: an implementation that picks the tightest host's limit finds
its own arithmetic already agrees with it.

**One thing found while building it, and it is not about durations.** §5.5 and §7.4 both make these facets
constraints on the *value* — `time_type`'s `@doc` gives the worked example, "a text encoding may spell an
admitted value with trailing zeros (`12:00:00.500` under `precision: 1`)", and `decimal_type`'s says "scale is
not part of the value". This implementation counted written digits in all four places that carry such a facet
(`time`, `datetime`, `duration`, and `number`'s own `total_digits`/`fraction_digits`), and so refused the
spec's own example. Fixed here, and worth noting in the register because the prose is already unambiguous and
an implementation still got it wrong four times: the rule reads like a spelling rule, and only the parenthetical
example says otherwise.

**In the conformance corpus**, on the branch that carries this revision's proposals. Seven vocabulary vectors
for the two ends and two validate-layer vectors for `precision`. Three of them turn on the ceiling being the
*format's* and not the reader's: `P400000D` is about 1095 years written in days, so no single component is
large and several runtimes' duration types would hold it comfortably; and
`-PT9223372036.854775808S` is the one value a signed 64-bit count holds and a magnitude rule does not, so a
processor bounding by casting to its own `int64` passes every other vector here and fails that one. The floor's
vectors pin rather than newly enforce — this implementation already refused a tenth digit, having nowhere to
put it — which is the shape of the whole entry: the floor was forced by the hosts and the ceiling is a choice.

**Suggested resolution:**

- §5.5 and meta.tn's `duration_type` `@doc`: replace the value-space sentence with — *The value is a signed
  exact decimal number of seconds — `number`'s value space, in seconds: any magnitude, any number of fractional
  digits, nothing rounded — so `PT90M`, `PT1H30M` and `P0DT5400S` are one value, `PT0S`, `P0D` and `-PT0S` are
  one value, and ordering is TOTAL. Bounds compare the value, not the token. `precision: N` is
  `fraction_digits` on that count: the value is a whole number of 10⁻ᴺ seconds, `precision: 0` whole seconds,
  `precision: 9` the nanosecond grid of most hosts — the base type has no precision, as `number` has no
  `fraction_digits`, so a host-bound schema states one. `multiple_of` is a duration under the header's uniform
  rule: `PT15M` admits only quarter-hour values, and `-PT30M` is a multiple of `PT15M`.*
- §5.5's fractional-second production, once for `duration`, `time` and `datetime`: `time-secfrac` is `"."
  1*9DIGIT`. One sentence saying why — no host runtime represents finer, and a schema needing finer uses
  `number` seconds or its own atom — so the restriction does not read as arbitrary.
- §5.5's `precision` facet: at most 9, falling out of the production above rather than stated twice.
- §5.5's value space, one sentence for the ceiling: *a `duration`'s magnitude does not exceed 2⁶³ − 1
  nanoseconds (about 292 years). A processor MUST be able to represent every value in that range, and MUST
  reject one outside it whether or not its own representation could hold it.* A span longer than that is a
  calendar span and is a `period`, or a physical quantity and is a `number` in the unit the schema names.
- `period_type`: no change. Its integer claim is what its own grammar already guarantees, and its own
  magnitude question is separate — a count of months has no host consensus to appeal to and no equivalent of
  `time.Duration` to be tightest.
- §9.1: no change. The ceiling is a value-space rule, not a resource limit: it is the same for every processor
  and is not the kind of bound an implementation may set for itself.

**Status against Revision 34:** open, and new against this revision. Independent of the other open entries: it
touches no constructor's shape and adds no facet. It does narrow what a conforming document may say, at both
ends — below, a token no processor could honour stops being one; above, a token some processors could honour
does too, which is the price of the range being the same everywhere rather than a property of whoever read the
document.

---

## 32. §8.3's use-site flattening is a second representation of a walk that happens anyway — proposal: remove it and `@alias`, and settle identity as nominal for a declaration and canonical for an application

**Section:** [TSON-SCHEMA] §8.3 (use-site reference flattening and `@alias`), §8.2 (instantiation identity),
§7.2 (a value's type annotation at a typed position), §5.7 (refinement), §6 (annotation positions);
meta-kernel's `alias` annotation, meta.tn's `bytes_encoding` (#29's directive).

**Problem:** a declaration's annotations are reachable from a use site only while the use site names that
declaration, and §8.3 is what stops it. The same intent, written the two ways the grammar offers, got two
answers — `@bytes_encoding:HEX digest_refined => !bytes ^ { length: 4 }` read hex, and the same directive over
`digest_alias => bytes` read base64, a different value with no diagnostic.

**The first fix carried the annotations along §8.3's walk. Building it found that the walk is the only thing
worth keeping.** §8.3 requires the chain stay walkable and leaves `reference.target` alone; the entries stay in
the namespace; and four passes walk a chain independently of flattening — the reader compile,
discrimination-class classification, inhabitance, and the linker's own choice-variant distinctness. So
flattening was a *second* representation of a walk that happens anyway, and `@alias` was a **lossy summary** of
it: §8.3 keeps only the source-site name, so in `digest_chain => digest_alias => bytes` it records the hop that
carried nothing and drops the one that carried the directive. The carry re-derived, in the annotation channel,
what the summary had discarded.

**Interpretation chosen — flattening and `@alias` are removed, and this implementation runs without them.**
Resolved output states the chain as written; a type position naming a `REFERENCE` entry keeps that name; the
chain is collapsed when readers are compiled, after linking, once per entry, where a `REFERENCE` entry's reader
*is* its target's, named for the entry doing the referring. Net −235 lines, `ReferenceFlattener` deleted
entire, and the one thing `@alias` was still buying — a diagnostic naming the alias the author wrote rather
than the type at the end of its chain — comes free from naming the reader where the reference compiles.

**What the removal exposed, and what §8.2 actually needs.** §8.3 claims flattening is "what makes instantiation
identity a single-level comparison". It never delivered that: `box<id>` and `box<uuid>` minted two entries even
with flattening, because it ran after materialisation and §8.2 keys identity on `source`. Removing it made the
question answerable instead of merely wrong, and the answer is **two rules, not one**:

**Identity is nominal for a declaration.** Each declared name is its own type entity, and an identical body
does not merge two of them — which core.tn's own `void` already states: *"A fresh sibling of the meta-kernel's
`void` under the same name — the same `!unit {}` construction and contract, a distinct type entity."* Measured:
`stock_id => !uuid_type {}` and `other_id => !uuid_type {}` have byte-identical resolved bodies (`UuidType`,
unconstrained, no supertypes) and are two types — `!other_id` is refused at a `stock_id` position, and so is
`!uuid`. A purely structural identity rule would collapse all three and destroy a distinction §7.2 currently
enforces correctly.

**Identity is canonical for an application.** A minted entry has no declared name to be its identity, so §8.2's
content-derived naming governs — and *content* must name each argument by its type rather than by a name for
it. That is the rule this entry asks for, and it follows from §7.2 rather than being new:

> An application's arguments are compared after following reference chains. `box<user_id>` over
> `user_id => uuid` denotes the same type as `box<uuid>`, and mints the same entry.

**Because the three ways to name a type after another are three different things**, and only the first is a
rename. All three are running, and the difference is exactly what a reader enforces:

| spelling | relation to `uuid` | `!uuid` at its position | a sibling's tag |
|---|---|---|---|
| `user_id => uuid` — **reference** | the same type | accepted | accepted |
| `user_id => !uuid ^ {}` — **refinement** | IS-A `uuid`; usable at a `uuid` position | refused | refused |
| `user_id => !uuid_type {}` — **fresh type** | unrelated in either direction | refused | refused |

Without the dereference the model said the arguments of the first row were the same type while the
applications were not — interchangeable at a scalar position, refused one layer of application up. With it,
`box<user_id>`, `box<uuid>` and `box<stock_id>` are one entry, while `box<!uuid ^ {}>` and `box<!uuid_type {}>`
keep their own. **What is normalised is identity, not provenance**: the minted `source` becomes the canonical
application, and the name the author wrote survives where they wrote it — at the use site, which states it as
written. Removing flattening is what made that division available; before it, the use site had been rewritten
and provenance had nowhere to live but `@alias`.

**The one case that was left unresolved here is closed, and not by an identity rule.** A reference carrying
`@bytes_encoding` was not a pure rename — values at its positions were spelled in another alphabet — so
dereferencing it lost the directive, and `box<hexdigest>` read four hex octets as six base64 ones. That is
fixed at the source: the alphabet is now `bytes_type`'s own `encoding` selector (#29), so it is part of the
type, an author writes `hexdigest => !bytes ^ { encoding: HEX }`, and a refinement is not a rename. Nothing
here has to decide what a directive on a reference means, because the meta layer no longer declares one.

**That is the shape of the general answer, and worth stating as such.** Dereferencing a rename is safe exactly
when a rename carries nothing that changes how values read. Rather than giving identity a rule for telling
load-bearing aliases from transparent ones, the fix was to stop a rename from being able to carry such a thing
— by putting what changes reads into the type, where a type is what every position already names.

**Suggested resolution:**

- **§8.3: delete use-site flattening and `@alias`.** State instead that a reference is a hop: resolved output
  records the chain as written, and **a processor MAY collapse a chain after linking, when it compiles for
  reading** — not in resolved output, which is what two conforming processors compare.
- **§8.2: two sentences where there is now one.** *A declared entry's identity is its name; two declarations
  are two types however alike their bodies.* And: *a minted entry's identity is its canonical content, an
  application's arguments compared after following reference chains.* §8.2's "identity is structural" is true
  of the minted half only, and reading it over declarations is what makes it wrong.
- **§8.3 or §5.7: name the three spellings and what each buys.** An author writing `user_id => uuid` and
  expecting a `stock_id` to be refused gets no protection and nothing tells them the other two forms exist.
  While that is written, say that an empty refinement (`!uuid ^ {}`) is legal — §5.7 says a refinement narrows,
  and it is the *only* nominal-subtype spelling in the language, so a revision tightening that into a MUST
  would delete it without anyone connecting the two changes.
- **meta-kernel and core: drop the `alias` annotation declaration.** Nothing derives it any more.
- **§6 still owes the checked category** (#25(a)) for `@rest` and `@discriminator`. It is no longer needed
  for identity: #29 moved the one decode-affecting annotation into the type, so no annotation the meta layer
  declares changes how a value reads.

**What is running, and what is not.** All of the above is running except the directive case, which is running
wrongly and knowingly. `ReferenceChainTest`, `BootstrapReferencesTest`, `FieldValueConformanceTest` and
`AliasedArgumentIdentityTest` pin the chain, the two resolution routes agreeing, the linker's own walk, and the
three spellings minting one entry, two entries and two entries respectively. The bundled schemas and their
`*-resolved.tn` fixtures are re-stamped, and the corpus's `alias-flattens-at-the-use-site` vector is now
`a-reference-is-stated-as-written`.

**Status against Revision 34:** open, and new against this revision — a removal and a rule, proposed after
building the thing removed, the fix that would have preserved it, and the rule that replaced both. §8.3's
flattening paragraph, its `@alias` example, and §8.2's single-level-comparison sentence are the text this asks
the revision to delete; the two identity sentences and the three-spellings table are what it asks for instead.

---

## 33. §9.1 gets the shape of a resource limit right once and generalises it to none of the others — proposal: one policy of limits, with defaults, reported the way §8.2's is

**Section:** [TSON-DATA] §9.1 (denial of service), §8.1 (the four error categories), §8.2 (name hygiene — the
fifth outcome, and the policy shape this entry asks §9.1 to copy); [TSON-SCHEMA] §2.2.3 (the import closure),
§7.8 (cross-schema scope push).

**Problem.** §9.1 names five limits and treats them two ways. **Numeric literal length** gets the full
treatment — a suggested default (4096 digits), a MUST that it be configurable or documented, and a MUST that
exceeding it report the threshold "rather than failing with an out-of-memory condition". **Nesting depth,
token length and document size** get one SHOULD sentence between them: no defaults, no configuration
requirement, no reporting requirement. **Decoded binary size** gets a SHOULD and nothing else.

So the section already demonstrates what a well-specified limit looks like and then applies it to one of the
five. That is the defect, and it is worth fixing as a set rather than a sixth paragraph, because a limit
nobody can discover is a limit a sender cannot write against.

**The list is also incomplete, and the gaps are not exotic.** What §9.1 names bounds *bytes* and *depth*;
nothing bounds *shape*.

| | bounded by §9.1 today |
|---|---|
| nesting depth | yes |
| token length | yes |
| document size in bytes | yes |
| numeric literal digits | yes |
| decoded binary size, per value | yes |
| **elements in one array or set** | no |
| **entries in one map** | no |
| **fields in one record** | no |
| **annotations on one value** | no — §3.1 permits a name to repeat "any number of times" |
| **total values in a document** | no |
| **decoded text length after escape processing** | no — §9.1 makes this point for binary and not for text |
| **foreign schemas loaded by one document** | no |

Three of those want saying out loud.

**The total is not bounded by the parts.** Ten thousand arrays of ten thousand elements sits inside any
per-container limit and is 10⁸ values. A processor that bounds depth and per-container size and believes
itself safe has bounded neither the work nor the allocation. The aggregate counter is a different mechanism
from the per-position check, and only one of them is implied by the current text.

**Per-container limits are where the superlinear work lives.** §2.5's unique field names, §2.6's key identity
and §7.5's element uniqueness are all *set* operations over a container's own contents. Bounding the document
in bytes does not bound them, because the pathological input is small.

**§7.8 gives a document a way to make a processor fetch.** A nested `!!schema` pushes a scope and loads a
foreign schema as the value arrives; a document carrying a thousand distinct ones asks for a thousand loads.
That is a fetch amplification vector no byte limit describes, and it is specific to this format.

**And §9.1 is Part 1, so it speaks only of documents.** A schema is untrusted input too wherever one is
accepted over the wire or reached through `!!import`, and nothing bounds an import closure, a schema's entry
count, a reference chain, a supertype chain, or template instantiation depth. This implementation's one
existing limit of any kind is exactly there — `TemplateMaterialiser.MAX_CLOSING_DEPTH = 64`, a bare constant
guarding non-regular recursion — which is evidence the need is real and currently met by whatever each
implementation happens to hard-code.

**What §9.1 does not say at all, and §8.2 already does.** The parallel is close enough to be the proposal:
§8.2 is a policy with a level, a default, a requirement that it be settable in code, and a rule that a
refusal under it is **not** one of §8.1's four categories. Every one of those has a counterpart here.

1. **A limit refusal is not a verdict on the document.** The document may be well-formed, valid, and accepted
   in full by the next processor along; what happened is that *this* deployment declined to spend the
   resources. That is §8.2's fifth outcome exactly, and §9.1 says nothing about which category it falls in —
   so an implementation will reach for one of the four, and the sender will read "your document is invalid"
   about a document that is not.
2. **A sender needs the limits before writing, not after.** #14's argument transfers verbatim: a limit
   reported only on failure arrives one round trip after the point it would have been useful. A generator
   that can read the limits emits a document that fits.
3. **Configurable without defaults is not portable.** If every deployment picks its own, a document's
   acceptability is a property of who received it. Defaults make "a conforming document" mean something —
   and §9.1 already supplies one for the single limit it specifies properly.

**Interpretation chosen: none of them are enforced, and the omission has a wrong answer rather than a missing
one.** A document a few thousand containers deep overflows the stack inside `TsonDataStream`, and a
`StackOverflowError` is an `Error`: it passes through every `catch (RuntimeException)` in the reader stack and
in the CLI alike, so `tson validate` prints a bare JVM stack trace to stderr, nothing to stdout, and **exits
1** — the code that means *your document is invalid*, which is the one verdict this case must not get. That
is the sharpest argument for point 1 above: with no category stated, the fallback is the wrong one.

**Suggested resolution.**

- **One policy, not six paragraphs.** Name the limits as a set — the five §9.1 has plus the shape limits
  above — each with a default, each configurable, in the way §8.2 names its level and unit. The natural
  implementation is one value beside §8.2's, reported by the same surfaces (`Tson.limitsPolicy()` beside
  `processorPolicy()`, either facade's, and `tson policy` on the command line), which is what makes point 2
  real rather than aspirational. **Beside and not inside**: the two answer different questions — what this
  processor will *read*, and what it will *admit as a name* — and a deployment that has changed one has said
  nothing about the other, which is the same argument §8.2 already makes for keeping its own two surfaces
  apart. One envelope field carries both, since "what judged this run" is one question to a consumer.
- **Say the refusal is not one of §8.1's four**, on §8.2's own terms: a limit refusal means the processor
  declined, not that the document is wrong, and it MUST be distinguishable from a verdict. §8.2 already
  carries the sentence that does this; §9.1 needs the same one.
- **Add the aggregate limit explicitly**, because it is the one a careful implementer will otherwise
  reasonably believe is implied by the per-container ones and is not.
- **Say that the schema is subject to the same treatment**, or say in [TSON-SCHEMA] where its limits live.
  Part 1's §9.1 cannot be the whole answer for a format whose schemas are fetched over the network.
- **Keep the numeric-literal paragraph as the model** and lift its three requirements — a default, MUST
  configurable-or-documented, MUST report the threshold — to the whole set.

**What is running: the shape, and one limit in it.** `TsonLimitsPolicy` is the policy value this entry asks
§9.1 for — configurable in code (`TsonConfig.limits`, `TsonTreeReader.withLimits`), reported with no document
in hand (`Tson.limitsPolicy()`, either facade's, `tson policy`, and a `limits` field inside every report's
`policy` record), and carrying a **nesting depth** with a stated default. The other four §9.1 limits and the
shape limits above are not built; the record is where each lands, which is why it is a record with one
component rather than an `int`. `BACKLOG.md` carries the rest.

**Building the first one settled three things the proposal could not.**

1. **A default is a portability claim, so it should be the tightest in common use, not the most generous.**
   64, against `serde_json`'s 128 and Jackson's 1000. A document that fits the tightest common limit fits every
   processor above it; the reverse choice makes "a conforming document" a property of whoever received it,
   which is the failure mode point 3 above names. §9.1's own existing default (4096 numeric digits) reads like
   a generous bound rather than a portable one, and is worth revisiting on the same argument.
2. **The refusal has to be raised where depth is *counted*, not where it is spent.** The token stream here is
   iterative and never overflows; every reader over it descends by recursion, and `EventSkip` recurses through
   a value no reader is even keeping. Counting in the stream as containers open — one comparison per opening
   bracket — is what stops the read before any of them descends, and it reaches a schema document through the
   same counter, which is the "schemas are subject to the same treatment" bullet met for free rather than by a
   second mechanism.
3. **`Code.verdict()` carries the non-verdict; the exit code answers a different question.** A limit refusal is
   `LIMIT_EXCEEDED` with `verdict()` false, and the CLI envelope says `NOT_CHECKED` — but it exits **1**, not
   one of the no-verdict codes, because at a command line the runner can act (`--max-depth`, or a smaller
   document) and 1 is that CLI's "you hold the fix". It is the one place `NOT_CHECKED` and exit 1 meet. A
   revision adopting this entry should say the refusal is not one of §8.1's four categories and stop there:
   what a *transport* does with it is that transport's mapping, and §8.2 is already the precedent for a fifth
   outcome whose HTTP status nobody legislates.

**Not in the conformance corpus, deliberately.** A depth vector encodes one processor's configured bound, and a
conforming implementation with a different one would fail it — the same reason §8.2's `refused` vectors have to
name the data version they were computed against. If a revision fixes defaults, the vector becomes writable and
should be written.

What is *not* a gap is regex: TSON pins its `regex` atom to RFC 9485 I-Regexp and `tson-regex` is a
Thompson-NFA/Pike-VM simulation, linear-time by construction, so the ReDoS vector every other format's §9.1 has
to warn about is closed here by the choice of language rather than by a limit.

**Status against Revision 34:** open, and new against this revision — a proposal, and now one this
implementation runs in part: the policy shape, and nesting depth in it. It is not a proposal for new vocabulary
in the type system — it asks that a section which already knows the right shape apply it consistently, name
the limits that bound shape rather than size, and settle the one question that decides what a sender is told:
whether being too large for this processor is a verdict on the document. It is not.

---

## 34. §5.5 gives four families a `within`/`excluding` pair and never says a pair must admit a value — proposal: state the emptiness rule, and say that a network family's prefix bounds are part of it

**Section:** [TSON-SCHEMA] §5.5 (`ipv4_type`, `ipv6_type`, `cidr4_type`, `cidr6_type`); §5.7 (facet kinds);
meta.tn's `@doc` for the four constructors; RFC 4632.

**Problem:** §5.5 declares `within` and `excluding` on all four network families and states what each does to
a *value* — an address must lie inside some `within` network and inside no `excluding` one; a network must be
a subnet of some `within` and must not overlap any `excluding`. It never states the schema-load question:
**must the two facets between them admit anything?**

Every other bounded family in the spec has that question answered. meta.tn's own header `@doc` says
"value-level coherence (the lower bound not exceeding the upper) remains a schema-load check", and `cidr4_type`'s
`@doc` adds the family range in the same voice — "bounds outside that range are invalid at the schema level".
So the register of what a body may not say is real and these two facets are simply not in it, which leaves

```
addr => !ipv4 ^ { within: ["10.0.0.0/8"]  excluding: ["10.0.0.0/8"] }
```

a type that loads, links, compiles, and refuses every document. It is `{ min: 10  max: 3 }` with a different
spelling, and unlike a pattern that matches no string it is a pair an author reaches by **editing** — copying
the `within` line to write the `excluding` one and forgetting to narrow it — rather than by trying.

**Two things make this worth stating rather than leaving to implementations.**

**1. It is decidable exactly, so there is no latitude to grant.** The natural worry — that deciding whether
several `excluding` entries between them cover a `within` entry is a set-cover problem — does not survive
contact with CIDR. Two blocks are nested or disjoint and never partly overlapping, so an exclusion meeting a
permitted block either contains it or lies wholly inside one of its halves; the walk descends on that and
terminates at the address width. `10.0.0.0/9` and `10.128.0.0/9` cover `10.0.0.0/8` by counting, not by
searching. A spec asking for this is asking for something total and two-valued, which is the shape this series
prefers.

**2. For a network family the prefix bounds are part of the question, and a reader will not guess that.**
`cidr4`/`cidr6` values are *blocks*, and §5.5 refuses a block for **overlapping** an exclusion rather than only
for being covered by one — the clause meta.tn glosses as "overlap, not containment, so a wider value cannot
smuggle an excluded block". That makes the network question different in kind from the address one:

```
net => !cidr4 ^ { within: ["10.0.0.0/24"]  excluding: ["10.0.0.5/32"]  max_prefix: 24 }
```

Every address in the range survives but one. But a value must be a subnet of `10.0.0.0/24`, so its prefix is
at least 24, and `max_prefix` says at most 24 — leaving `10.0.0.0/24` itself, which overlaps the hole. The
body admits **no network at all** while admitting almost every address. An implementation that judges the two
list facets on their own — the obvious reading of a section that never mentions the interaction — calls that
body coherent.

**Interpretation chosen: both, as one coherence rule per family.** `Atom.coherenceCheck` asks whether a single
body's own facets admit anything, which is where `{ min: 10 max: 3 }` is refused, so the pair is refused there
too and by the same mechanism — not by inhabitance, which is about an entry graph rather than one body's
facets. The rule is stated once, in `AtomCoherence`, and each family names only its own fields: the address
families pass their two lists, the network families pass their two lists **and** their prefix bounds. Folding
the bounds in is the same fold `integer` already performs with its `size`-derived range, which is what makes a
single stated bound incoherent there.

Two messages, because the two causes want different edits — `excluding covers every network within permits`,
and `the largest block they leave is a /25, and max_prefix is 24`, which names the number the author moves.
A malformed entry or an inverted bound suppresses the emptiness message rather than adding to it: those are
their own checks' to report, and the emptiness is their consequence.

**Suggested resolution.**

- **State the emptiness rule for all four families**, in the voice §5.5 already uses for the family range: a
  `within`/`excluding` pair that admits no value is invalid at the schema level.
- **Say that a network family's `min_prefix`/`max_prefix` participate in it**, with the worked case above or
  one like it. This is the half a careful implementer gets wrong, because it is invisible unless the overlap
  rule and the prefix bounds are read together.
- **Say the rule is exact**, rather than leaving room for a partial check. Prefix-tree cover is counting; an
  implementation that only refuses the pairwise case (`within` entry equal to or contained in an `excluding`
  entry) would be conforming under a looser wording and would miss the tiling case, which is the one that
  arises from a real edit.
- **Leave the narrowing direction alone here.** Whether a *refinement* may replace `within` with a strictly
  smaller network is #29's question — a set facet with no stated relation — and this entry is only about one
  body judged on its own.

**What is running.** All of it, in `AtomCoherence.checkAdmitsAValue` over `schema.atom.CidrNetwork`, called
from all four families' `coherenceCheck`. The address grammars and the network value live in `tson-schema`
precisely so that each family can judge its own `[value]`-typed entries without the linker or the resolver
holding one family's rule — the facets are typed `[value]` in meta.tn and must stay so, since they list
networks and meta declares no network instance to type them by (core.tn does, and core imports meta). Tests:
`AtomCoherenceTest`'s `within`/`excluding` block for the arithmetic, `NetworkFacetsTest` for the schema-load
path.

**Status against Revision 34:** open, and new against this revision. The rule is running here; what is asked
is that the spec state it, so that two implementations agree on which bodies load — a body that admits nothing
is the case where silence costs the most, since the divergence shows up as every document being refused rather
than as a schema failing to load.

---

## 35. §4.2's value-route-only rule survives the marker's removal, and its justifying clause is still false — proposal: delete it

**Section:** [TSON-SCHEMA] §4.2 (value-route-only parameters), §5.10 (templates, and what materialisation
substitutes), §8.2 (identity settles after Pass 2), §5.2 (which fields may carry a value).

**Problem.** §4.2's second rule reads:

> A constructor's parameters MUST occur only as value routes — the `= P` and `~ P` modifiers on its own fields
> (§5.7); a parameter of a `~` declaration occurring in any type-reference channel — a field type, element
> type, variant, or a non-routed argument of its source chain — is a resolver error at the declaration.
> Type-channel parameters are a template-only feature (§5.10): a value-routed parameter closes by routing an
> argument into a vocabulary slot, while a type-channel one could close only by rewriting the body — the
> materialisation constructors never get (§8.2).

**The justifying clause is not true.** Materialisation *is* body rewriting. §5.10 closes an open entry by
substituting its parameters away over the resolved form, and §8.2 keys the resulting entry on the closed
record — so a held body whose parameter stands in a field type is exactly what the machinery already handles.

**#36 changes the rule's subject but does not remove it.** With the `~` marker gone, "a constructor" is an
entry that IS-A `top`, so the rule reads: *an entry composing, transitively, with a base kind may not have a
parameter in a type channel.* That shape still exists and still resolves clean here:

```tson
ctor_box => <T> product & { value: T }      # supertypes [product, top] -- a constructor
flagged  => ctor_box<boolean>               # closes; resolver reports nothing
```

**The evidence this entry used to carry is withdrawn.** It measured `<T> ~base & { value: T }` over a plain
record `base => {}`. Without the marker that composes with an ordinary record, reaches no base kind, and is
therefore *not a constructor at all* — an ordinary §5.10 template, which the rule never governed. The
measurement was of the wrong shape, and it took removing the marker to see that: the marker had been
asserting constructor level over a chain that did not support it.

**And the "no legal spelling" argument is withdrawn with it.** It rested on §4.2's level discipline forcing
the marker onto anything composing with a constructor, so that value-route-only refused the marked spelling
and level discipline the unmarked one. Level discipline is gone (#36), and with it the dilemma.

**What replaces both.** The shape resolves, closes, and is applicable: `flagged` materialises to an alias of
the closed entry, and applicability follows a reference chain (§8.3), so `!flagged { … }` reaches the
constructor at the end of it. Checking that is what turned up a defect on this side — the chain was not being
walked at a construction head, so an alias to *any* constructor was refused — which is now fixed and pinned.
The rule therefore forbids a shape that resolves, closes and applies, for a stated reason that does not hold.

**The two channels the rule names beyond the field-type one are already answered elsewhere.**

- **A vocabulary slot typed `type_ref`** — `<T> array ^ { element_type: = T }` — is refused when it closes,
  because §5.2 admits a fixed or default value only on a field typed by an atom or an enum, and `type_ref` is
  a record. Measured, unchanged by the marker's removal. The corresponding legal form still closes normally:
  `<N> array ^ { max_items: = N }` with `my_bounded<3>`, `max_items` being atom-typed.
- **A non-routed argument of the source chain** is a §5.10 application like any other, closed by
  materialisation or refused by the arity and kind rules §5.10 already carries.

So of the rule's three channels, two are decided by other sections and one is forbidden for a stated reason
that is false.

**Recommendation: delete the value-route-only rule.** With #36 removing the marker, §4.2's three
declaration-time rules become one — placement, restated as who may declare an entry that IS-A `top`. If a
revision keeps the marker and rejects #36, the recommendation is unchanged and the rule still goes: its
justification is false either way, and the two channels it shares with §5.2 and §5.10 do not need it.

If §4.2 wants to keep a statement about the two parameter kinds, the true one is §5.10's: an argument is read
by the position it lands in, and a slot typed `type_ref` takes a type where an atom-typed slot takes a value.
That is a statement about *slots*, already load-bearing in §9's "a slot holding a type reference MUST be
typed `type_ref`".

**What is running.** The rule is not enforced, and after #36 the pipeline has no marker to enforce it from —
a parameterised entry composing with a base kind resolves, closes, and the closed result applies.

**Status against Revision 34:** open, and new against this revision — a deletion. It is the entry this cycle
most changed by another: #36's removal of the marker moved its subject, withdrew its worked example and
killed its second argument, leaving a narrower claim resting on the false justifying clause alone. Recorded
that way rather than restated at its old strength, because the register is read as evidence and the evidence
changed.

---

## 36. The `~` marker decides nothing that the type system does not already say — proposal: applicability is IS-A `top`, and `~` and `type_definition.constructor` are removed

**Section:** [TSON-SCHEMA] §3.3.1 (constructor application resolves against the structure namespace and the
entry MUST be a constructor), §4.1 (the base kinds, and IS-A `top`), §4.2 (the `~` marker), §5.6.

**Problem: the marker is the wrong predicate for applicability, and the kernel already contains the
counter-example.** §4.1 leaves `reference` unmarked, deliberately — it describes no value — and the language
nonetheless needs `!reference { target: T }` to work, since §8.1 makes a `reference` body how an alias is
written. Under §3.3.1 as stated that construction is a resolver error.

**This implementation had the defect in its sharpest form**: the template path carried a by-name exception
for `reference` and the closed path did not, so one construction had two answers —

```
r => <T> !reference { target: T }     resolved
r => !reference { target: int32 }     '!reference' does not resolve to a constructor (§3.3.1)
```

A rule that needs a hardcoded exception for one kernel name, and gets it in one of two places, is not
carrying the distinction it is supposed to carry.

**The predicate that does carry it is §4.1's own.** §4.1 already says every base kind IS-A `top` and every
constructor transitively so, and that **IS-A stops at construction** — `!T {}` transfers kind, not
supertypes, so instances and fresh records carry empty chains. So "IS-A `top`" is exactly "describes a type"
as against "describes a part of one", which is the question a construction site is asking.

**Measured over meta-kernel.tn, meta.tn and core.tn:** `constructor` is a strict subset of IS-A `top`, with
no constructor failing to be IS-A `top` in any of the three. The difference set is exactly the four base
kinds and `reference`:

| | admitted by IS-A `top` | admitted by `constructor` |
|---|---|---|
| `record`, `array`, `choice`, `integer_type`, … | yes | yes |
| `reference` | yes | **no** — the case that needed the exception |
| `atom`, `product`, `sum`, `data` | yes | no — harmless, see below |
| `record_field`, `type_ref`, `type_argument`, `tuple_element`, `field_group`, `integer_size`, `atom_specification`, `type_definition` | **no** | no |

**Admitting the base kinds costs nothing and reads better.** Each is an abstract union whose own reader
refuses a direct application by naming what would satisfy it — `!product {}` answers *"'product' has no data
of its own to bind — provide an explicit type annotation naming one of its subtypes [record, array, set_type,
map, tuple]"*, which tells an author what to write where "not a constructor" does not.

**Excluding the component set is the part that matters, and it is not merely cosmetic.** Those eight are
record-bodied like a constructor is; nothing in their *shape* separates them. Removing the check entirely
does not make them succeed — this implementation's body model is a sealed `Top`, and a `record_field` is not
a member — but it fails as a host-language cast error reported in the "could not be checked" category rather
than as a verdict on the schema. Any implementation whose model is less sealed would do worse. The rule is
what makes the refusal a spec-level one.

**What the marker keeps.** This is not a proposal to remove `~`. It still marks constructor level, which is
what §4.2's level discipline reads (an entry composing with, refining or subtracting from a constructor MUST
itself be `~`), and §8.1 still records it. What it stops deciding is applicability.

**Interpretation chosen, and running.** `!C { … }` and `<…> !C { … }` both require `C` IS-A `top`; the
by-name `reference` exception is deleted from the eligibility question; the meta-schema's applicable-head
table is built on the same predicate, so a head the gate admits has a reader. **Admitting `reference` closed
carries a second obligation** worth stating for anyone implementing this: `!reference { target: X }` is the
explicit form of `X` (§8.3), so it must denote that alias — `kind: REFERENCE` with `X` as source and body —
and not the head's own kind with `reference` as source, which is what a construction of every other head
yields. §4.1 already requires it (an alias's kind is a `type_kind`, not one a supertype chain can give); it is
simply unreachable while the closed spelling is refused. `ApplicabilityIsIsATopTest` pins the closed/open symmetry,
the component refusal and the base-kind self-refusal.

**One consequence worth stating, because it is a behaviour change and not only a re-spelling.** A
meta-schema's own `data`-kinded extension no longer needs the marker: `operation => data & { … }` composes
with a base kind, so it IS-A `top`, and a governed schema may write `!operation { … }`. That is §2.2.2's
extension point becoming reachable without a marker whose other meaning (constructor level) the author may
not want. `MetaLayerDataConstructorTest` asserts it.

**The marker's other readers fell away as this was built, which is what turns one fix into a removal.** Every
question `~` was asked turned out to be answerable from the type system, and better:

| Asked the marker | Asks now | Why the new question is the right one |
|---|---|---|
| may `!C { … }` apply `C`? | `C` IS-A `top` (§4.1) | separates a type from a part of one; admits `reference`, which the marker refused |
| may `!I ^ { … }` refine `I`? | `I` is ATOM-kinded and not itself applicable | §5.5 asks whether there is an atom value to narrow; the marker answered something else, and kind alone cannot separate an atom constructor from its instances |
| does the schema being compiled declare `C`? | `C` IS-A `top` | one predicate throughout, so a construction that resolved reaches a factory |
| does this record template rewrite at desugar? | *nothing* — every one does | the marked route deferred the identical rewrite to `holdIfOpen` one phase later; measured, both produce the same held body |

**What is left reading it, and what each becomes without it.** Two, and neither needs the marker to survive:

- **§2.2.2 eligibility** — who may declare a constructor. Becomes *only a schema whose own `!!meta` names the
  meta-kernel may declare an entry that IS-A `top`*, which is the same rule stated in the same vocabulary as
  everything above. It broadens slightly, to an unmarked composition with a base kind, which is the same act
  by another spelling.
- **§4.2 level discipline** — an entry deriving from a constructor must itself be one. This one does not
  restate; it **dissolves**. Composition already propagates the supertype chain, so an entry composing with
  something IS-A `top` is IS-A `top`: the level is inherited rather than declared, and there is nothing left
  to refuse. That is the one place the proposal changes meaning rather than spelling, and it is the part a
  revision has to adjudicate — see below.

**Proposal: remove the marker and the field.** `~` leaves §12.1's grammar; `constructor` leaves §8.1's
`type_definition`. What replaces them is nothing: a constructor becomes *an entry that IS-A `top`*, which is
what §4.1 has said all along, and "constructor level" becomes a position in the IS-A chain rather than a
property an author asserts.

**The one thing a revision must weigh, now measured rather than guessed.** §4.2's level discipline exists so
that "the two IS-A relations never mix: types relate to types, and constructors relate to constructors and
kinds". Under this proposal there is one relation, and `composed => c & { extra: identifier }` over a
constructor `c` is simply IS-A `top` and therefore applicable — where under Revision 34 it is a resolver
error. Building it settled the question the entry could not: **in an ordinary schema the declaration is
refused anyway**, by §2.2.2 eligibility, which asks the same IS-A `top` question of every declared entry; and
**in a meta-schema it is exactly what the author is doing**, extending a constructor's vocabulary. The
separation the rule protected is protected by placement, one layer up, without a marker to repeat.

**What is running: all of it.** `~` is gone from the schema grammar and `constructor` from
`type_definition`. The three bundled schemas are written without the marker and their resolved companions
without the field; every digest is re-stamped. Applicability, atom refinement, the factory lookup and the
desugar route all ask the type system. §2.2.2 eligibility asks the linker whether the entry IS-A `top`, and
§4.2's level discipline is deleted rather than reimplemented.

**Two things fell out that the proposal did not predict, and both are arguments for it.**

1. **Eligibility strictly subsumes what level discipline protected.** The old rule refused an *unmarked*
   declaration deriving from a constructor. The eligibility rule refuses *any* declaration of an entry that
   IS-A `top` outside a meta-kernel-governed schema — so an ordinary type library cannot reach constructor
   level by composing its way there, which is the case level discipline existed for, and it is refused at the
   declaration rather than at the spelling. The reading this entry could not settle is settled by building
   it: nothing was lost.
2. **One test lost its subject, which is the removal working.** `box => <T> base & { value: T }` was asserted
   to be a constructor; without the marker it is not one, because composing with an ordinary record reaches
   no base kind. It is a §5.10 template, which is what it always was — the marker had been asserting
   something the type system did not agree with.

**In the conformance corpus**, on the branch carrying this revision's proposals: `class2/schema/invalid`
gains a vector whose subject writes `~`, which is the observable difference between a revision that has the
marker and one that does not. Its category is `resolver`, §8.1 making every error that stops a schema loading
one however early the phase that caught it.

**Suggested resolution:**

- §3.3.1: replace "the found entry MUST be a constructor" with *the found entry MUST be IS-A `top` (§4.1)*.
  One sentence of why: IS-A stops at construction, so the predicate separates a type from a part of one, and
  the base kinds and `reference` are types by that measure while `record_field` and its siblings are not.
  **Worth taking on its own**, whatever is decided about the marker.
- §5.5: state the refinement source as *an atom-kinded entry that is not itself applicable*. An atom
  constructor is ATOM-kinded exactly like its instances, so kind alone does not say it, and IS-A `atom` says
  the opposite of what a reader expects — it is true of the constructor and false of every instance, §4.1's
  "IS-A does not extend below construction" being why.
- §4.2: delete the `~` marker and its three rules. Placement becomes a rule about declaring an entry that
  IS-A `top`; level discipline goes, and §2.2.2's placement rule covers what it protected; the
  value-route-only rule is already proposed for deletion in #35.
- §12.1: remove `~` from `type-def`.
- §8.1: remove `constructor` from `type_definition`. It is derivable from `supertypes` where it is wanted at
  all, and the `*-resolved.tn` companions lose one field per entry.
- §4.1: no change. Everything above is read off what it already says, which is the argument for the removal
  rather than a coincidence of it.

**Status against Revision 34:** open, and new against this revision — a re-spelling that became a removal
while being built, and is now running end to end. It is the second entry in this cycle where enforcing a
neighbouring rule is what exposed the defect: §3.3.1's own by-name exception for `reference` had been
invisible until the open and closed paths were compared. What a revision adopting this gets is one fewer
production, one fewer field in §8.1's output, and three rules where there were five — with the two questions
that remain asked of the supertype chain, which §4.1 already fixes.

---
