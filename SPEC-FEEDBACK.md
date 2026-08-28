# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation.
See `CLAUDE.md` for why this file exists and when to add to it. Spec quotes below are from
2026 Revision 33 — Part 1 (https://tson.io/raw/2026/33/tson-part1-data.md) unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a
suggested resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a
revision closes.** It is an input to the next revision's adjudication, so its numbering is the numbering
that revision's change log will answer against — a stable index of the open set, not an archive of
everything ever raised.

The fourteen below are what Revision 33 leaves open, renumbered from #1; the 55 raised against Revision 32 that
it resolved are gone from here, because the spec now carries their rules and that is where the answer
belongs. **This file is the as-built record**, not a pointer to one: where an entry proposes a design this
implementation has built, the entry states the design, what is running, and what is not, so that a reviewer
editing the spec needs nothing beside it. **Cite the spec, not the argument that got it there:** `docs/` and
the Javadoc name the section that requires a behaviour, and a `SPEC-FEEDBACK.md #N` citation is for an entry
below, where there is no section to point at yet. When an entry closes, its citations become spec citations
and the entry is deleted — nothing here is an archive.

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

**Status against Revision 33:** open, carried deliberately. The change log records it as "likely
revisited in a later revision", and §5.4's table is unchanged — `!duration` still reads
`ISO 8601 duration (PnYnMnDTnHnMnS)` with no mention of `W`. `DurationParser` still rejects `P3W`.

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

**Status against Revision 33:** open, carried deliberately. §2.2.1 keeps the query form and now
states the surrounding discipline more firmly — the pin is "verification metadata, not identity", a query
MUST consist solely of hash parameters, and an unrecognised parameter name is an error rather than
something silently retained. The placement question itself is untouched.

---

## 3. §9.4 cites UTS #39 but names the one mechanism that cannot be applied to a document in isolation, and leaves the rest unmentioned

**Section:** [TSON-DATA] §9.4 (Confusable Characters), §7.1 (UAX #31 profile), §2.5 (field-name identity),
§7.2.1 (NFC normalization); [TSON-SCHEMA] §2.2.3 (`!!import` name disjointness).

**Problem:** §9.4 is one sentence of advice:

> Implementations processing untrusted TSON input SHOULD consider Unicode confusable detection (UTS #39)
> when field name identity is security-relevant.

Two things make that hard to act on.

**1. UTS #39 is several mechanisms, and §9.4 points at the one that needs context TSON has not defined.**
"Confusable detection" is the `skeleton()` mapping (UTS #39 §4, `confusables.txt`): two strings are
confusable iff their skeletons are equal. That is a *relation between strings*, so it answers nothing about a
single identifier — it requires a defined comparison set, and §9.4 names none. The mechanisms that *are*
decidable on one token, with no set and no context, go unmentioned:

- **Identifier_Status** (`IdentifierStatus.txt`, the General Security Profile, UTS #39 §3.1) — a per-character
  Allowed/Restricted partition. Composes directly with a UAX #31 profile and needs nothing but the token.
- **Restriction levels** (UTS #39 §5.2: ASCII-Only, Single-Script, Highly Restrictive, …) and **mixed-script
  detection** — properties of one identifier, computed from script extensions.

This matters because UAX #31 itself directs implementers to pair an identifier profile with UTS #39, and §7.1
already *declares* a UAX #31 profile, in a table, with a documented exclusion (ZWNJ/ZWJ) justified by exactly
this threat. That table is where an Identifier_Status requirement would go, and it is the one place a
conforming implementation could act without the application telling it anything.

**2. The comparison set §9.4 lacks is one TSON can actually name.** A general-purpose language cannot bound
"which identifiers might be confused with which"; a TSON document can, several times over:

- field names within one record — §2.5 already defines their identity and a duplicate rule
- keys within one map — §2.6, which already asks implementations to *warn* on textually identical keys
- `enum` members, and the variants of a choice ([TSON-SCHEMA] §5.4)
- the declared type names of one schema, and — sharpest — the merged set at an `!!import`, since
  [TSON-SCHEMA] §2.2.3 requires imported names be "disjoint from each other and from local entries".
  Disjointness there is exact equality, which a confusable pair passes by construction: two entries a
  reviewer reads as one name are, to the resolver, simply two names.

Each of those is a small, closed set at a well-defined point in processing. §9.4 could name them instead of
deferring to "when field name identity is security-relevant" — a judgement an implementation is not in a
position to make, since only the application knows.

**3. The strictness is inverted relative to the risk.** §7.2.1 makes NFC normalization a MUST, so two
canonically equivalent names *are the same name*. Confusability gets SHOULD-consider, so two visually
identical names *are different names*. The format takes a firm, testable position on the case that is a
convenience issue and none on the case that is the attack.

**4. The prescribed workaround reopens the surface it closes.** §7.1 excludes ZWNJ and ZWJ from the profile
and gives the reason: "They are invisible, which makes them confusable and spoofing surface (§9.4); names
whose orthography requires them MUST be quoted." But the §7.1 profile constrains *unquoted* tokens only, and
§2.5 makes a quoted name an ordinary field name. So the sanctioned route for a name needing ZWNJ is also an
unconstrained route for every character the profile excludes — the hardening is bypassable by the mechanism
the same sentence prescribes. Whatever §9.4 eventually requires needs to say whether it applies to quoted
names, and if it does not, §7.1 should not describe quoting as the remedy.

**5. Nothing here is conformance-visible.** As with the annotation-conformance entry Revision 33 accepted
(change log #29), a SHOULD-consider in Security Considerations makes
no implementation measurably better than one that ignores it, and there is no vector a test suite could
carry.

**Interpretation chosen:** NFC normalization of unquoted tokens (§7.2.1) is implemented; no part of UTS #39
is. Nothing in this implementation detects a confusable pair, a mixed-script identifier, or a Restricted
character, at either the data or schema layer — including at `!!import` merge, where the disjointness check
is exact string equality.

Worth recording *why*, because it bears on how a requirement here should be worded: this implementation
already approximates UAX #31, using the JDK's `Character.isUnicodeIdentifierStart`/`Part` in place of
XID_Start/XID_Continue, because the JDK exposes no UAX #31 properties and building the tables was out of
scope. The JDK likewise exposes no UTS #39 data at all — no `confusables.txt`, no Identifier_Status. So any
normative UTS #39 requirement obliges every implementation to ship UCD data, which is a materially larger ask
than the rest of the Unicode surface in this spec and should be a deliberate decision rather than a
side effect of tightening §9.4.

**Suggested resolution:** Split §9.4 by what an implementation can decide alone and what it cannot.

1. **Adopt the General Security Profile in §7.1**, where the UAX #31 profile is already declared: require or
   recommend that unquoted-token characters be Identifier_Status=Allowed. This subsumes the ad-hoc ZWNJ/ZWJ
   exclusion, which is currently a hand-picked instance of a rule UTS #39 states generally.
2. **State the comparison scopes** for skeleton-based detection — the record, the map, the enum, the choice,
   the schema namespace, the import merge — rather than leaving the set to the implementation. Detection
   within a closed set is a mechanical check; "is identity security-relevant here" is not.
3. **Say what a processor does on detection**: reject, warn, or emit a diagnostic. §2.6 already chose "SHOULD
   warn" for textually identical map keys; confusable names deserve at least the same treatment, and the two
   should not disagree.
4. **Decide whether a restriction level applies**, and if none does, say so — silence reads as an oversight
   rather than a decision.
5. **Address quoted names explicitly**, per point 4 above.
6. **Consider making the schema layer normative and the data layer advisory.** [TSON-SCHEMA] §2.2.3's
   disjointness check is a resolver-time operation over a closed, already-materialised set of names — the
   cheapest place in the whole series to make confusability an error rather than advice, and the place where
   a spoofed name does the most damage, since it changes which type a document is validated against.

**Status against Revision 33:** open, carried deliberately, and recorded as "needs further
investigation". §9.4 is unchanged: one SHOULD-consider sentence naming UTS #39 confusable detection, with
no Identifier_Status profile at §7.1, no scoped comparison set, and no stated action on detection.

---

## 4. A type argument's literal is called a bare token and typed `value`, and §8.2 identity depends on which

**Section:** Part 2 §5.10 (type arguments), §8.1 (`type_argument`), §8.2 (instantiation identity); Part 1 §4
(base type resolution), §7.6 (number). Related: change log #43, and D6 of the structure-templates CR, now
folded into Revision 33 as its baseline.

**Problem:** three statements about the same slot, which do not agree.

§5.10 and §8.1's prose describe a type argument's literal value as **a bare token** — "never annotated,
never typed, never a container". A bare token is text plus the form that produced it, unresolved.

meta-kernel types the slot as `value`:

```
type_argument => { ( name: type_ref | value: value ) }
```

and `value` is the escape-hatch primitive whose whole contract is that [TSON-DATA] §4 base type resolution
**has been applied** to it. A token and the value it denotes are not the same thing, and this slot is
declared as both.

§8.2 then keys an instantiation entry on "structural equality of the flattened, fully-bound application
recorded in `source`". Structural equality of *what*? The two readings differ, and §4 is where they part:
`255` and `0xFF` are the same number, so

- **as tokens**, `vector<float32, 255>` and `vector<float32, 0xFF>` are different applications, hence two
  entries;
- **as values**, they are one application and one entry.

This is not hypothetical. Reading the token, the two produce entries whose bodies are byte-identical:

```
vector<float32, 255>   → array_float32_255_255_5c5f53f7    ArrayBody[minItems=255, maxItems=255]
vector<float32, 0xFF>  → array_float32_0xFF_0xFF_462d33b7  ArrayBody[minItems=255, maxItems=255]
```

The bodies agree because `min_items` is declared `integer` and decodes through that atom; the identities
disagree because they are derived from the argument's token text. So the same schema holds two entries for
one type, and §8.2's one-entry-per-application rule is satisfied only under a reading of "application" that
§4 contradicts.

Note this is not an artifact of *how* the argument reaches the resolver. A declaration-level application
(`a => vector<float32, 255>`) never goes near a wire form and splits identically, because the split is in
what identity compares, not in how the argument was parsed.

**And the cost is a wrong verdict, not a redundant entry** — which is what moved this from a recorded
consequence to a fixed one. §5.4 requires a choice's variants to resolve to distinct types, and it can only
ask that of entry names, so two names for one type pass a check two spellings of one name fail:

```
u => ( [float32; 255] | [float32; 0xFF] )     accepted, disjoint=false
u => ( [float32; 255] | [float32; 255]  )     "'u' lists the variant 'array_float32_255_255_…' twice
                                               -- §5.4 requires each variant to resolve to a distinct type"
```

The accepted one is worse than untidy: a choice between two structurally identical variants, correctly
derived non-disjoint, that no untagged read can ever discriminate. Under the value reading a conforming
resolver must refuse it, and under the token reading it must not — one schema, opposite verdicts, decided by
a spelling §4 spends a paragraph making irrelevant.

**Interpretation chosen: option 3 below.** The slot stays a `Token` — §5.10's "bare token" is the only prose
that speaks to what is *recorded*, and resolved output still shows the author's spelling — and §4.3's
equivalence is applied where identity is derived, at both naming sites (`NumericIdentity`, consumed by
`SchemaDesugarer`'s lift and `TemplateMaterialiser`'s instantiation). This is a change of position: the split
was previously accepted and recorded here on the grounds that normalising would be inventing a rule. The §5.4
evidence above is what settles it — leaving the two apart is not neutrality between the readings, it is the
token reading, and it is the one that admits a schema no reader can use.

**The equivalence applied is exactly the one §4.3 states, and no wider.** Radix, digit separators and a
redundant sign fall away (`255`/`0xFF`/`0b1111_1111`/`0o377`/`+255`); a float's written scale does too
(`.5`/`0.5`, `1.0`/`1.00`/`1e0`); `.inf` and `.infinity` are one value. What does **not** fall away is the
base type: §4 resolves `1` to an integer and `1.0` to a float, so those stay two arguments even though one
magnitude covers both. A spec adopting option 3 should say that boundary out loud, since "the same number"
alone does not decide it.

**Suggested resolution:** say which, in §8.2, and make §8.1 agree with it.

1. **Identity compares token text and form.** Then §5.10's "bare token" is the operative reading, the kernel
   should type the slot `token` rather than `value`, and §8.2 should say plainly that two spellings of one
   number are two types — surprising enough to be worth stating, since §4 spends a paragraph making them one
   value.
2. **Identity compares the values the arguments denote.** Then the kernel's `value` typing is right, §5.10's
   "bare token" is about what may be *written* rather than what is *recorded*, and §8.1's `type_argument`
   holds a resolved value — which also settles what an implementation binds it to.
3. **Keep the token in the model and normalise before comparing** — the slot stays a token so the original
   spelling survives into §8 output, and identity applies §4 first. This is the only option that keeps both
   the written form and the equivalence, and it is the one that most needs saying out loud, because no
   implementation will arrive at it from the current text.

Option 2 is the smaller edit; option 3 is the better answer if resolver output is meant to round-trip what
the author wrote.

**Status against Revision 33:** open on the spec's side; **option 3 is built here**, so the recommendation
is a report rather than a proposal. §5.3 now says an unquoted token argument "is classified against the
applied signature's parameter kinds", which settles *what kind* of thing an argument is but not the identity
question this entry asks: whether `<255>` and `<0xFF>` are one application or two. This implementation says
one, and refuses the §5.4 choice above accordingly. §8.2 still does not say, and until it does an
implementation reading §5.10's "bare token" at face value gets a different entry set and the opposite verdict
on that choice — which is the interoperability cost of leaving it unstated.

**Coupled to #5's D6 merge, which is not obvious and is easy to decide by accident.** D6 says eagerly-lifted
synthetics that become "structurally identical under resolution" merge into one entry. Re-deriving a
synthetic's name from its *resolved* record — the natural implementation — normalises the value channel as a
side effect and settles this entry in favour of option 2/3 without anyone choosing it. The two splits live in
different channels of the same derived name (a reference argument that is itself an application, versus a
value argument's spelling), so an implementation that wants D6 without prejudging this one must re-derive
from resolved references while leaving value tokens as written.

---

## 5. §5.10's collection-slot boundary refuses what the kernel's own vocabulary licenses, and it excludes the sum-typed result envelope

**Section:** Part 2 §5.10 (the two parameter kinds, and the collection boundary), §8.1 (`template_argument`,
`type_ref`, `record_field.value_param`), §5.3 (the lift rule), §5.10.1 (regularity), §12.1
(`instance-template`). Supersedes the item declined at Revision 33 as #53. **Read with #7**, which widens
`reference.target` — the one open body this design could not otherwise spell, and what makes its
"every open entry is a constructor application" true rather than nearly true.

**Problem:** §5.10 says, plainly:

> Collection-valued slots are not parameterizable — a parameter inside a collection-typed slot (an enum's
> member list, a choice's `variants`, a tuple's `elements`, a record's `fields`) has no open representation,
> and a declaration writing one is a resolver error at the declaration; this is a deliberate boundary of this
> revision, and nesting goes through a second named template instead.

That is not ambiguous, and this entry is not an ambiguity report. What is inconsistent is that the kernel's
own vocabulary licenses exactly what the prose refuses. `type_ref`'s `@doc` in meta-kernel says `name` is
"the referenced type — or, within a template body, a parameter of either kind, read against the enclosing
definition's `parameters` list", and `choice` is declared

```
choice => ~sum & { variants: [type_ref] }
```

so every variant position is already a channel licensed to hold a parameter, at any depth. `!choice {
variants: [T error] }` is spellable in the vocabulary that describes resolved schemas and forbidden by the
prose that describes resolution.

The refusal traces to one place, and it is not the constructor vocabulary. `template_argument` is
`( param: param_name | value: value | type_ref: type_ref )` with no collection case, and §5.10's uniformity
clause requires every open instance body to be an `instance_template`. So a body the reference channel could
have carried unchanged must instead be re-expressed in a vocabulary that cannot hold it. The boundary is a
property of the chosen open representation, not of the problem being represented.

The asymmetry underneath it is worth stating on its own, because it decides how much mechanism the problem
actually needs: **a slot that holds names can hold a parameter for free, because a parameter is a name; a
slot that holds an immediate value cannot.** Type slots ride `type_ref` and never needed a spelling. Only
immediate value slots did — `min_items: N`, `format: F` — and `record_field.value_param` is that spelling,
already shipped, for exactly one node. §8.1's stated reason for quoting type slots anyway (a parameter in a
type slot "would have two spellings and body identity would depend on the choice") buys a property that is
obtainable by construction — make the body form a function of the body, and no entry has two spellings —
at the price of the boundary.

The cost is not theoretical. `result => <T> ( T | error )`, the sum-typed result envelope, is the likeliest
headline use of generic schemas and is inexpressible by rule. §5.10's declared workaround does not reach it:
there is no second template to name when the parameter *is* a variant, so the sum must be monomorphised by
hand at every use.

**Interpretation chosen:** implemented as written first — `SchemaDesugarer` refused a parameter in a
collection-valued slot at the declaration, classified as a schema-author error rather than a library gap,
the verdict being one that does not change as this implementation improves. That refusal is now **replaced**
by the design below, built here and running: `result => <T> ( T | error )` resolves, closing to an ordinary
`choice` body over `[text, error]`. This is a deliberate divergence from Revision 33, offered as evidence
rather than as a conformance claim.

**The alternative weighed and rejected** was the other coherent completion: grow the typed quotation until it
covers what the constructors can express — a collection case on `template_argument`, and a shadow spelling for
every slot kind a parameter can reach. It is rejected on proportionality, and the reason generalises: every
per-channel mechanism in the shipped design is compensation for binding too early, so completing the quotation
adds a spelling per constructor form in perpetuity, where holding removes the need for any. The one property
uniform quotation buys — body identity not depending on which spelling an author chose (§8.1's stated
rationale) — is obtainable by construction instead: make the body form a function of the body, and no entry
has two spellings. What that obligation becomes under holding is the one-spelling rule stated near the end of
this entry, which is a requirement on producers rather than a vocabulary.

**Suggested resolution: hold an open body rather than quoting it.** What follows is the design as built, so
that what is proposed and what is known to work are the same thing.

1. **An open entry's body is the constructor application as written, held and unread** until materialisation
   substitutes its parameters away. Not a typed quotation of the constructor vocabulary. Substitution is then
   **one rule at any depth** — rewrite a token whose text resolves into the entry's `parameters` (§8.1's
   shadowing rule) — uniform across type slots, value slots, collection elements and nesting alike, because
   nothing has been classified by slot kind. Materialisation binds against constructor vocabulary exactly
   once, at the only moment binding is decidable.
   - **A held token needs no channel label, and that is what removes the boundary.** `template_argument`
     needs `param` because a bare token in a value slot is otherwise always a literal; a held body is not
     read as that vocabulary until the parameters are gone, so a parameter in `variants` is a token inside an
     array like any other. Quoting is no part of it — a token's form is a schemaless-data concern (§4.4),
     which is why the rule is on the token's text.
   - The cost is shadowing's usual one: inside a template, a literal spelled like a live parameter is
     unreachable.
2. **§12.1's `instance` production takes a parameter list**, and the `instance-template` / `template-def` /
   `template-bind` productions delete with the vocabulary that motivated them. Open and closed share one
   production and one payload grammar, which is what admits a collection payload. A parameterized
   `atom-refinement` remains no form at all, as §12.1 already has it.
   - **What no payload can spell is an application**, in either form: `!array { element_type: box<text> }`
     does not parse, `box<text>` being schema grammar where `instance` takes a `core-value`. The line falls
     where the grammars already divide — a *type* position takes `box<text>` directly, while `!C value` takes
     data, so an application inside one is written in `type_ref`'s own record form, which is what the sugar
     expands to anyway. Revision 33 gave the open form a spelling the closed form never had; losing the
     asymmetry is part of the point.
3. **Lifting is unchanged, and open synthetic entries remain a category.** This is worth stating because the
   opposite is the obvious guess and it does not work: a template's body cannot simply hold everything
   nested inside it. A `type_ref` slot names a type and nothing else, so a sugar form inside a template body
   — `<T> { a: [T] }` — must still lift to an entry, and a lifted form naming a parameter lifts **open**.
   §5.3's lift rule therefore stands as written, and §8.2's identity-up-to-consistent-renaming of parameters
   is still required, since two open synthetics alike up to renaming must land on one entry.
   - What changes is only what an open synthetic's *body* is: held, not quoted. `<T> { a: [T] }` still
     injects `array_p0_… => <p0> !array { element_type: p0 }` and the field still reads `array_p0_…<T>`.
   - **Applications inside a held body close before its entry is named.** Desugar lifts innermost-first, so a
     form it writes already names the entry its inner form became; a form closed at materialisation must
     agree, or `[[pixel; 3]; 3]` written out and `grid<pixel, 3>` closed land on two entries for one type.
   - **Not built here, and a spec question rather than an oversight.** The two lift channels hash different
     things: a closed lift hashes the binding record at desugar, before its inner applications are rewritten,
     where the open lift hashes the closed record at materialisation. So `[box<text>]` written directly and
     `[box<T>]` closed with `T := text` land on two entries for one type in this implementation. The
     resolution is already in §8.2's own D6 — "eagerly-lifted synthetics that become structurally identical
     under resolution merge into one entry" — a merge pass at the end of resolution that re-derives each
     synthetic's name from its resolved record. It was never needed before, every form lifted closed having
     been concrete at desugar, and holding is what makes it reachable. §5.10 should say that the merge is
     required rather than incidental, because an implementation reading D6 as an optimisation will skip it and
     get a second entry for the same type.
4. **The resolved form of an open entry is its declaration**, not a `type_definition` value — which could not
   carry it in any case, the kernel declaring `body: top` REQUIRED with no `top` an open body could be. This
   keeps resolved output a valid schema document under §12.1, so it stays re-resolvable, and it **needs no
   new kernel vocabulary**: no new primitive, no `( body | template )` field group. §1.3 is unaffected, a
   conforming consumer of resolved output meeting only closed entries and instantiations.
   - **It does, however, retire three declarations**, which is a subtraction rather than an addition and is
     the other half of adopting this design. `instance_template` and `template_argument` exist only to quote
     an open body slot by slot; a held body has no producer for either. `record_field`'s
     `( value | value_param )?` group narrows to `value?` with them — a routed parameter rides `value`, told
     from a literal by §8.1's shadowing rule, and a held body is not read as this vocabulary until its
     parameters are gone, so the label has nothing left to disambiguate. All three are gone from the kernel
     shipped here, and §5.10/§8.1 should drop them when the design lands.
5. **Checking splits, and §5.10 should say where.** Two questions are answered at the declaration from the
   binding record's own field names, needing no stand-in values and so unable to fabricate a verdict: that
   each name is a field the constructor declares, and that every REQUIRED-without-default field is bound.
   §5.10's unreferenced-parameter rule is answered there too, from the tokens the held body names. Everything
   value-shaped waits for materialisation, where the whole body binds through the constructor's own reader.
   An **unapplied** template is checked no further and gets no verdict — not a warning, no verdict.
   - Checking an unapplied template by substituting stand-ins should be ruled out explicitly, because it
     manufactures false errors on exactly the slots this mechanism exists for: `<N> !integer ^ { min: N max:
     3 }` is correct for every argument anyone passes and fails under a stand-in of 10.
   - A materialisation diagnostic must be **located at the declaration whose text wrote the offending name**,
     with the application as context. Deferred checking is survivable only if the author is sent to the line
     they can edit. This is a requirement rather than a report — what the design owes an author in exchange
     for deferring the checks — and it runs here.
     - **The rule is not "the template".** `box => <T> { v: T  w: no_such_type }` applied by `holder` belongs
       to `/box`: `holder` is correct, does not contain the name, and would be blamed once per applier, each
       under a different subject (`'box<text>'`, `'box<int32>'`). But `box => <T> { v: T }` applied as
       `box<3>` belongs to `/holder`, which wrote the `3`. A blanket "locate at the template" sends that
       author to a declaration with no `3` in it, so the spec should state the rule over the **name**, not
       over the entry.
     - **What that costs an implementation is one lookup, not bookkeeping through the minting phase.** The
       offending name is the evidence: the declaration to blame is the open one whose held body mentions it,
       and there is none when the name arrived in the argument list. Tracking derived-entry lineage instead
       gets the alias case wrong — `half => <B> pair<no_such_type, B>` closes to an entry sourced on `pair`,
       which is faultless — so the name is both the cheaper and the more accurate key.
     - **One defect earns one diagnostic**, however many declarations apply the template; otherwise the count
       an author sees is a property of the schema's callers rather than of the mistake.

**What holding costs, and where §5.10 should say so.** A held body has no slot types — that is what it is for
— so every check keyed on which slot a thing sits in waits for materialisation, and one of them does not
survive the trip. §5.10's argument-kind rule ("a reference argument binds a type parameter, a literal binds a
value parameter") is enforced today by `record_field.value_param`, whose presence is what says *this slot
expects a value*; where a parameter stands in an ordinary value slot, a type name substituted there is a
token like any other. §5.10 should say which of its checks are declaration-time and which are
materialisation-time under an open form, rather than leaving an implementation to discover that one of them
is neither.

Built out, the loss is **half the rule, and the wrong half to worry about**. A literal applied where the body
uses the parameter as a *type* is still refused, because the substituted token stands in a type position and
nothing declares a type called `3` — the verdict arrives as an unresolved reference rather than as a kind
error, but it arrives. Only the converse escapes: a type name applied where the body routes the parameter into
a field's *value*, which `value` (§4's escape-hatch atom) accepts.

**And the converse is better closed by a rule §5.2 already states than by the kind rule.** meta-kernel's own
`@doc` on `value` says `record_field.value` holds "the type of fixed/default values, **which must be the
field's declared type** — a dependency the schema language does not express directly". Enforce that and
`retry => <N> { attempts: int32 ~ N }` applied as `retry<text>` fails because `text` is not an `int32` —
whether a parameter put it there or the author wrote it literally, and with no notion of a parameter's kind
involved. What it would not catch is a type name applied into a `text`-typed value slot, which is a value slot
holding a valid value: no error to give. **So the recommendation is to drop the argument-kind rule rather than
find a home for the slot's expectation**, and to state the value-conformance dependency §8.1 currently
describes as inexpressible. That removes the one check holding cannot carry, and removes it by strengthening a
rule the format wanted anyway.

- **The value-conformance rule is a report, not a recommendation: it is built and running here.** The
  linker checks a field's `~`/`=` value against the field's own resolved type, so `{ first: int32 ~ "nope" }`
  is refused at the declaration that wrote it, and `retry => <N> { attempts: int32 ~ N }` applied as
  `retry<text>` is refused identically — one rule, no notion of a parameter's kind, the same verdict whether
  a parameter or the author put the value there. The check runs the field's own reader parser, so it accepts
  a value exactly when a read would accept the same token in that position and cannot drift from the atom
  contracts §5 defines.
  - **Its boundary today is the field's type kind, not the parameter.** A field typed by an atom or an enum
    is checked; one typed by a record, container, tuple or choice is not, because checking a value against
    those needs the compiled reader and compilation runs after linking. That boundary is this
    implementation's, not the rule's: nothing in §5.2's dependency is atom-specific.
  - So §5.10 can drop the argument-kind rule, provided §5.2 states the value-conformance dependency
    normatively — a resolver that drops the one and does not add the other loses both.

**One position the loss does reach, and §5.10 should decide it.** §5.10 settles a parameter's kind from its
*use*, and there is a use no channel recognises: an **enum member**. `e => <M> !enum { members: [a b M] }`
applied as `e<c>` fails, because §12.1's `type-arg` rule sends an unquoted non-numeric argument down the
reference channel and `c` is a member name, not a type. It is the same root as the argument-kind loss above —
a held body has no slot types, so nothing says `members` is a value channel — but it is the case where the
loss produces a *wrong* verdict rather than a late one: the author is told `c` is an unresolved reference when
`c` is not meant to be a reference at all. The two want settling together, and the choice is between naming
`enum.members` a value channel in §5.10's own kind table (so an argument reaching it is read as a literal), or
saying that a parameter in a member list requires the quoted spelling `e<"c">`. Either is a sentence; leaving
it unsaid means every implementation that adopts holding hits this on its first parametric enum.

**Scope of what is built.** **Every** template shape holds its body, and one process closes them all: the sugar
forms, the explicit `<T> !C { … }`, record templates, and composition and refinement templates. §5.2 already
says a bare record body denotes `!record { fields: [ … ] }`, so `<T> { x: T }` is normalised to that and closes
the way `<T> [T]` does. A composition or refinement is resolved against its namespace first and the *flattened*
record is held — a §5.7 tightening entry states a modifier and no type-ref, so it is not a `record_field` until
the inherited field supplies one — which is the one reason those two are normalised a phase later than the rest.

**And what is not built**, gathered here so a reviewer has the boundary in one place rather than in footnotes:
a parametric enum member (above), locating a held-body defect at the template's declaration (above), and the
D6 merge that would make the two lift channels agree (above). Value conformance of a field's `~`/`=` against
its declared type — this entry's proposed replacement for the argument-kind rule — is **not** among them: it
is built, and #8 carries the one question it raises that this entry does not. A parameterized
`atom-refinement` is *deliberately* not on that list: it remains
no form at all, as §12.1 already has it, and holding does not change that. Nothing on the list is load-bearing
for the design — each is a check or a location, not a shape the held form cannot express — but a reviewer
adopting this should know which claims here are running code and which are recommendations.

**So `record_field.value_param` has no producer left**, and §5.10 can retire the channel along with
`instance_template` and `template_argument`. A routed parameter rides the ordinary `value` slot with §8.1's
shadowing rule to tell it from a literal, and §5.7's fixation moves to materialisation — where §5.7 already says
it belongs ("fixation happens downstream, where values are concrete"). The kernel's `record_field` group narrows
from `( value | value_param )?` to `value?`.

**One implementation note the spec should absorb, because it is a property of the design and not of this
codebase: the open form needs one spelling, however many phases produce it.** An open body is read by later
phases as wire form, and an entry's derived name is a hash of what is written, so two *spellings* of one form
are two entries for one type — and worse, a serialiser that states a no-argument `type_ref` in the explicit
record form where the sugar table states it positionally makes `type_argument` and `type_ref`
indistinguishable to a walk that reads neither against a vocabulary. The trap is concrete: an ordinary
canonical-explicit object writer is exactly the wrong producer, and not only for that reason — it quotes every
token, where a held body's whole parameter mechanism keys on a token being *unquoted*, so a written-out body
references no parameters at all. Two of the four shapes here genuinely cannot be normalised syntactically
(composition and refinement need a namespace to flatten against), so "produce it in one phase" is not
achievable; "produce it in one spelling" is, and is what §5.10 should require.

**If Revision 34 wants a smaller edit than all of that**, one scoped change resolves the flagship case
against Revision 33 as shipped: restate §5.10's uniformity rule so that an open entry carries an ordinary
constructor body whenever every parameter occurrence sits at a `type_ref` position, requiring
`instance_template` only where a value slot is parameter-bound, and narrowing the collection error to
parameters at *value* positions inside collections. Choice, tuple, `[T]` and `{K => V}` templates fall out
immediately. Note it is not free for implementations that shipped Revision 33: it changes the resolved output
of templates that already work, `<T> { v: [T] }` ceasing to lift an `instance_template`.

**Status against Revision 33:** open, new against this revision. The same gap was raised against Revision 32
as #53 and declined, §5.10 gaining the explicit boundary sentence and §8.1 the uniform-quotation rationale in
response. The design above is implemented here and passing: the flagship `result => <T> ( T | error )`,
`<T> [T, text]`, `<T> { v: (T | text) }` and nested sized forms all resolve, every template shape holds its
body, `value_param` has no producer left, and every schema that resolved before produces the same entries it
did. What is *not* built is listed under "And what is not built" above, and every recommendation this entry
makes that is a proposal rather than a report is marked as one where it is made.

---

## 6. Every schema that writes a container sugar form inside a template mints its own copy of the same few templates

**Section:** Part 2 §5.3 (the lift rule), §8.2 (synthetic entry identity and content-derived naming), §9 (what
the kernel declares). Related: #5, whose held-body proposal does not change this either way.

**Problem:** a sugar form inside a template body lifts to an *open* synthetic entry — `<T> { a: [T] }` mints

```
array_p0_358380cd => <p0> !array { element_type: p0 }
```

and `box`'s field references it as `array_p0_358380cd<T>`. That entry is the same entry in every schema that
writes `[T]` inside a template, up to a content-derived name §8.2 already declares non-normative. The lift
rule mints it per schema because it has nowhere else to put it, so a fixed, tiny set of templates is
re-derived by every author who uses generics over a container.

The kernel already takes the other route one level down: rather than have every schema inline
`!set { element_type: token }`, §9 declares `token_set` once and `enum.members` references it. The same
argument applies to the open forms, and nothing but availability decides it.

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

**Status against Revision 33:** open, new against this revision. This implementation mints per schema and
`ContainerSugarEndToEndTest` pins the resulting entry sets.

---

---

## 7. An alias to an application cannot state the arguments it binds, so `reference` is the one open body that is not a constructor application

**Section:** Part 2 §8.1 (`reference`), §5.10 (partial application), §8.2 (identity keyed on `source`), §8.3
(use-site flattening), §9 (a slot holding a type reference). **Read with #5**, whose held-body design this
completes: an alias is the one open entry that could not be written as a constructor application until
`target` widened, so #5's uniformity claim rests on this entry being adopted with it.

**Problem:** §5.10's partial application is an alias *to an application*:

```
uuid_pair => <B> pair<text, B>
```

The kernel gives it nowhere to say so:

```
reference => top & {
  target: type_name      ← a bare name; `pair<text, B>` does not fit
}
```

So an implementation has to keep the argument list somewhere else, and the only place available is the
entry's own `source` — which §8.2 keys instantiation identity on. That gives one component two jobs
depending on whether the entry is open, and it makes an alias with no recoverable target a representable
state: nothing in the vocabulary says a `reference` entry must carry a `source`, so nothing prevents one
that cannot say what it aliased.

It is also inconsistent with §9's own guidance for extension meta-schemas — "a slot holding a type reference
MUST be typed `type_ref`" — which `reference.target` plainly is and plainly is not.

The deeper cost is uniformity. Every other open entry is a constructor application, `<params> !C core-value`,
which is what lets substitution be one walk: rewrite the parameter tokens in a held `core-value`, then read
the result through the named constructor. A partial application is the one shape that cannot be written that
way, because `!reference { target: pair<text, B> }` is not spellable while `target` is a name.

**Interpretation chosen:** widened to `target: type_ref` here, and the alias states its own arguments. That
deletes the `source` double-duty, the guard against an alias with no recoverable target, and the special case
that kept a name-only body in step with `source` whenever materialisation rewrote it.

**Suggested resolution:** declare `reference => top & { target: type_ref }`.

- **A closed alias never carries arguments**, so resolved output is unaffected in practice: materialisation
  rewrites `text_box => box<text>` to name the entry it minted. An argument-bearing target appears only where
  an application is still open — inside a template.
- **§8.3 needs one sentence.** A use site is flattened past a REFERENCE entry; this slot is not, because the
  chain must stay walkable and an alias records where it points. The walk additionally stops *at* an
  argument-bearing target: that is an application, not a hop to another entry, and there is no entry at the
  end of it until materialisation mints one.
- **What it unlocks, and what is built here**, is writing the partial application as
  `<B> !reference { target: pair<text, B> }`. That brings the last template shape onto §12.1's one open-form
  production: every open entry is now `[type-params] "!" type-name ws core-value` with a held `core-value`
  body, so substitution is one token walk for all of them and a resolver tells the cases apart by the
  constructor head — `record` closes to the instantiation entry, `reference` composes and mints nothing
  (§5.10's "no intermediate entry per alias hop"), everything else closes to a synthetic.
- **Two kernel facts make `reference` a dispatched head rather than an ordinary one**, and §5.10 should say
  so if it adopts this. `reference` is deliberately not a `~` constructor — it describes no value — so the
  generic "`!C value` requires a constructor" rule refuses it; and §4.1 gives an alias `kind: REFERENCE`,
  which is a `type_kind` with no base kind in the composition hierarchy to supply it. Neither is a property
  of the alias form; both are the kernel's own, and an implementation has to special-case the head either
  way.

**Status against Revision 33:** open, new against this revision. Implemented here, which makes it the first
change to the bundled `spec/m/` artifacts' *content* — the three digests move with it.


---

## 8. §5.2 makes a field's fixed or default value a value of the field's declared type, but does not say which declared types can have one

**Section:** Part 2 §5.2 (`record_field.value`, the six field-state spellings), §5.6 (positional form),
§12.1 (`field-modifier`). Part 1 §7.4 (form is not meaning).

**Problem:** §12.1 admits only a bare token after `~`/`=` — writing `~ [ ... ]` or `~ { ... }` is a syntax
error, not another value — and meta-kernel's own `@doc` on `record_field.value` says the slot holds "the
type of fixed/default values, **which must be the field's declared type** — a dependency the schema
language does not express directly". Put together, those settle every scalar case. They do not settle
whether a field whose declared type is a **record** or a **choice** may carry one, because a bare token can
legitimately reach both:

```
point => { n: int32 }        rec => { p: point ~ 3 }        # §5.6 positional form
ch    => ( int32 | text )    rec => { c: ch ~ oops }        # discriminates to the text variant
```

Both read cleanly if a resolver admits them: §5.6 fills a record with exactly one bare `REQUIRED` field
from a bare value, and a choice discriminates a token by its §4 base-type class to a variant that accepts
it. Nothing in §5.2 says whether the "must be the field's declared type" test is satisfied by *a value the
type admits* or by *a type a token denotes directly*, and the two answers differ for exactly these two
kinds.

**What this implementation does:** refuses both. A fixed or default value is available on a field typed by
an **atom or an enum** and nowhere else — `TsonSchemaLinker` resolves the field's type, and a body that is
not a scalar is a resolver error at the declaration, whatever token stands beside it. `void`, `unknown` and
`extern` fall out of the same rule for their own reasons: the type with no value, the universe of types,
and a mechanism with no token shape.

**Why, and it is a cost worth naming.** Admitting the two cases makes "may this field have a default?"
depend on the referenced type's field count (exactly one bare `REQUIRED`) and on its variant list and their
discrimination classes. That is a rule an author computes rather than remembers, and it is computed against
a *different* declaration than the one they are editing — adding a second field to `point` would silently
invalidate a default written on a field somewhere else. The refusal costs two spellings that would have
worked and buys a rule that fits in one line. §5.6 is a spelling rule for *data values*; reading it as a
claim that a record **is** a token is what would carry it into a schema's own field modifiers, and it does
not say that.

**Recommendation:** §5.2 should state which declared types may carry a fixed or default value, in one
sentence, rather than leaving it to be derived from §12.1's token-only production plus §5.6. Either
answer is implementable; what costs an implementation is that the question is not asked. If the answer is
the permissive one, §5.2 should also say that a record's positional-form eligibility is part of its
contract — because a default written against it then breaks when an unrelated field is added to that
record, which authors will not expect from a change that is otherwise backward-compatible.

**Status against Revision 33:** open, new against this revision. The restrictive reading is what is built
and running here (`FieldValueConformanceTest`); the permissive one is what a resolver gets by deferring to
its reader, which is the shape an implementation falls into by accident.


---

## 9. `time_type`/`datetime_type` declare `precision` and `require_timezone`, and no prose anywhere says what either means

**Section:** Part 2 §9 (the bundled `meta.tn` artifact), §5.5/§5.7 (constraint vocabularies and tightening).
Part 1 §5.4 (the temporal atoms), §5.2 (an atom's parse/validate split).

**Problem:** `meta.tn` declares both facets, normatively:

```
time_type => ~atom & atom_specification & {
  spec:              = "https://www.rfc-editor.org/rfc/rfc3339"
  min:               value?
  max:               value?
  precision:         integer?
  require_timezone:  boolean?
}
```

`datetime_type` is identical. Neither field carries a `@doc`, and **neither name appears anywhere in Part 1
or Part 2's prose** — the only mention of `time_type`/`datetime_type` outside the artifact itself is the §9
table listing them as constructors. So the vocabulary a conforming schema may write is defined, and what
writing it *means* is not. An implementation must pick, and every pick is a different accept/reject set.

**`precision`, four ways to read it, and they are not equivalent:**

1. **Exactly N fractional digits, or at most N?** `precision: 3` against `12:00:00.12` — accept or reject?
2. **Does it constrain the written token or the value?** `12:00:00.100` and `12:00:00.1` are the same
   instant with different digit counts. A token-level reading separates them; a value-level one does not.
3. **Is it a validation constraint or a truncation instruction?** Part 1 §5.6 sets the precedent that the
   *approximate* atoms round onto their grid and "loss of precision is expected, not an error", so a reader
   could reasonably expect `precision` to truncate rather than reject. The temporal atoms are not on that
   split, which leaves the question open rather than answered.
4. **What is `precision: 0`?** No fractional part admitted, or unconstrained?

The choice is not cosmetic, because it decides whether the facet can participate in refinement at all
(§5.7). Under "at most N", a smaller N is a narrowing and `precision` behaves like every other bound. Under
"exactly N", two different values are *disjoint* rather than ordered, and a refinement tightening
`precision: 6` to `precision: 3` is neither a narrowing nor a widening — a shape this vocabulary has nowhere
else, and one an implementation's narrowing check cannot express.

**`require_timezone` is stranger, because the pinned `spec` appears to make it unusable.** Part 1 §5.4 maps
`!datetime` to RFC 3339 `date-time` and `!time` to `full-time`, and both productions make the offset
**mandatory**. So `require_timezone: true` constrains nothing that the atom's own format does not already
require, and `require_timezone: false` can only mean *accepting values the named format does not produce* —
`partial-time`, or a local date-time. That is a facet that **widens** an atom, against a `spec` field the
same record fixes to RFC 3339. Every other facet in this vocabulary narrows. Either the field means
something narrower than it reads, or it is the one place a constraint vocabulary relaxes its own
specification pin, and §5.5 should say which.

**What this implementation does:** neither facet is enforced, and a schema setting either is accepted at
load and **refused at the first read of that type**, with a message that names the ambiguity rather than
resolving it — `'datetime' does not enforce 'precision' yet … the spec does not say whether it bounds the
fractional-second digits exactly or at most, and this implementation will not guess`. That is deliberate:
the alternative is to pick silently, and a schema author who wrote `precision: 3` would then get whichever
of the four readings this implementation happened to choose, with no way to tell. Two of the six remaining
read-time gaps in this implementation are these two facets, and they are the only ones whose cause is a
question rather than unwritten code.

**Recommendation:** state both, in §5.5, beside the family they belong to. For `precision`, the useful
answer is almost certainly "at most N fractional digits, at the token level, a validation constraint" —
that is the only reading under which the facet orders, refines, and composes like the rest of the
vocabulary. For `require_timezone`, either delete it (RFC 3339 already requires the offset, so the facet is
vacuous) or say explicitly that `false` relaxes the atom to `partial-time` and reconcile that with `spec`
being FIXED. Deleting it is the smaller change and loses nothing a schema can currently express.

**Status against Revision 33:** open, new against this revision. Both facets are declared in the bundled
`meta.tn` this repo packages, so a schema can write them today and no implementation can agree with another
about what happens next.


---

## 10. §8.2 defers "family coherence rules whose operands were parameters — `min_items ≤ max_items` (§5.3) and their kin" without saying what the kin are

**Section:** Part 2 §8.2 (materialisation), §5.3 (size specifier), §5.5 (constraint vocabularies).

**Problem:** §8.2's sentence is normative and its subject is a set it does not enumerate:

> Materialisation also runs the value-level checks that open bindings deferred: family coherence rules whose
> operands were parameters — `min_items ≤ max_items` (§5.3) **and their kin** — and the typing of every
> substituted value (§5.10) are verified once concrete, and a violation is a resolver error reported at the
> materialising application.

One rule is named and the rest are gestured at. An implementer can build the named one and has no way to
know whether they have finished — "and their kin" is not a set anything can be checked against, and the
obvious reading (that it means the other bound pairs) understates it, since a family may state a coherence
rule that is not a bound pair at all (`min_prefix`/`max_prefix` against the address family's own width).

**What this implementation does, and it needs no list.** Every constraint family already owns its coherence
rule for the body an author writes literally — that is where `min_length: 10 max_length: 3` is refused, and
where §7.2 puts it ("family coherence between bindings (e.g. `min ≤ max`) is a compilation and ingest
concern"). The rule an application *closes onto* is the same rule over the same facets, so materialisation
does not need its own set: it asks every family the question it already answers. One call, at the phase that
sees every entry exactly once, covers `min_items ≤ max_items` and its kin together, and a family that gains
a rule later is covered without anything being added.

This is not hypothetical for the atom families. §12.1 refuses a parameterized `^` refinement, but the
constructor spelling is open, so an atom's own bounds reach materialisation as readily as a container's:

```
b => <N> !integer_type { min: N  max: 3 }      r => { x: b<10> }
b => <N> !text_type { min_length: N  max_length: 3 }   r => { x: b<10> }
b => <N> !cidr4_type { min_prefix: N  max_prefix: 8 }  r => { x: b<40> }
```

All three describe a type nothing can satisfy, none of them is `min_items ≤ max_items`, and all three are
the kin the sentence has in mind.

**Recommendation:** replace "and their kin" with the general rule, which is shorter than a list and cannot
go stale: *every family coherence rule §5.3 and §5.5 state applies again at materialisation, over the
operands that were parameters.* If a list is preferred, it must include the non-pair rules, which the
current phrasing reads past.

**One thing §8.2 gets exactly right and is worth keeping:** "a violation is a resolver error reported **at
the materialising application**". That is the only location an author can act on — the entry itself is
minted, content-named, and appears nowhere in their file — and it is also where §5.10's substituted-value
typing has to land for the same reason.

**Status against Revision 33:** open, new against this revision. The general rule is what is built and
running here (`ContainerBoundCoherenceTest`), across both base kinds and every family.

---

## 11. Part 1 §2.8 resolves an empty brace to "the empty container of that type"; Part 2 §7.7 enumerates two containers, and the series has four

**Section:** Part 1 §2.8 (brace disambiguation and empty braces), Part 2 §7.7 (resolver behaviours at typed
positions), §5.3 (the container sugar forms and their size specifiers).

**Problem:** the two parts state the same rule at different widths, and the wider one is the one that reads
like the general statement.

§2.8 defers an empty brace to the resolver and closes with: "In the absence of declared type information, an
empty-brace resolves to an empty record. When a higher part supplies an expected type ([TSON-SCHEMA]), it
resolves to **the empty container of that type**." Nothing there is limited to two kinds; "that type" is
whatever the position declares, and §5.3 gives the series four container kinds — record, map, array, tuple.

§7.7's "Empty braces" paragraph is the higher part supplying it, and it enumerates instead: "the resolver
transforms an empty-brace value into **an empty record or empty map** per the expected type, defaulting to an
empty record when the position is untyped." An array position is not in the list, and §7.7 does not say
whether that is a deliberate exclusion or an enumeration of the two cases the author had in mind.

Both readings are defensible and they differ observably on a one-line document. Under `holder => { tags:
[text] }`, the data `!holder { tags: {} }` is either an empty array (§2.8 read generally) or a type error
(§7.7 read as exhaustive). Neither part says which.

Three things make the narrow reading the more likely intent, none of them decisive:

1. **`{}` is brace-shaped and an array is bracket-shaped.** Every other empty container has a spelling of its
   own — `[]` for an array and a tuple — so nothing is unspellable under the narrow reading, where an empty
   *map* genuinely needs `{}` because it has no other empty form. The rule earns its keep only for the two
   kinds that share the `{...}` form and cannot be told apart when empty.
2. **§5.4's discrimination classes already treat `{}` as brace-class**, ambiguous between a record and a map
   and distinct from `bracket`. Admitting `{}` at an array position would make a value of brace class conform
   to a bracket-class type, which is a wrinkle in a table the spec is otherwise careful to keep total.
3. **A tuple would follow an array**, and there §7.7's silence is louder: a tuple's arity is fixed and exact
   (§5.3), so `{}` at a two-slot tuple would resolve to "the empty container of that type" and then fail
   arity — a two-step verdict for what the narrow reading calls one type error.

What the narrow reading costs is that §2.8's sentence is then wrong as written, in the part that defines the
concept, and an implementor reading Part 1 first will build the general rule.

**Interpretation chosen:** §7.7's enumeration, treated as exhaustive. `{}` reads as an empty record at a
record position and an empty map at a map position (facing `min_items`/`max_items` there like any other map —
the count is validated in `MapAbstractReader.expectMapShape`, the one funnel every map reader passes through);
at an array or tuple position it is a `TYPE_MISMATCH`, reported as "expected an array for '[text]', found
{}". Schemaless, and at any untyped position, it is an empty record, per §2.8's own first sentence.

**Suggested resolution:** make the two parts agree, in whichever direction, and say it in Part 1 as well as
Part 2 — the sentence an implementor builds from is §2.8's, and it is the one that currently overstates.

If the narrow reading is intended, §2.8's closing sentence wants replacing with something that does not
generalise past it: *"When a higher part supplies an expected type ([TSON-SCHEMA]), it resolves to the empty
record or the empty map according to that type"* — and §7.7 wants one clause saying an empty brace at any
other container position is a validation error, so the exclusion is stated rather than inferred from a list.

If the general reading is intended, §7.7's list wants replacing with §2.8's own phrasing, and two consequences
want stating outright, because both are places an implementation would otherwise diverge silently: an empty
brace at an array position is a zero-element array and **faces the size constraints** (`[text; 1..]` rejects
it, exactly as `{text => text; 1..}` rejects it today); and an empty brace at a tuple position resolves to a
zero-element tuple that then fails §5.3's exact-arity rule for any tuple with slots — unless §5.4's
brace/bracket classes are meant to exclude tuples and arrays from the rule after all, which would be the third
possible answer and is currently unstated.

**Status against Revision 33:** open, new against this revision. The narrow reading is what is built and
running here, across all four container positions.

---

## 12. §12.1's grammar admits `{K => V?}`, §5.3's prose forbids it, and `map` has no `state` field to bind it to

**Section:** Part 2 §5.3 (the container sugar forms), §7.6 (the absent sentinel under a schema), §12.1 (the
`map-type` and `element-type` productions), §9 (the kernel's `map` constructor); Part 1 §2.9.

**Problem:** four passages — three of prose and one of grammar — and they do not agree about whether `_`
means anything at a map entry value.

§5.3 refuses the marker and gives its reason:

> Neither side of `=>` admits `?`: the kernel's `map` has no `state` field, and **absence has no defined
> meaning for map values** (an absent key is already a resolver error, [TSON-DATA] §2.9).

§7.6's table gives that exact position a defined meaning, and the only unconditional permission in the table:

> | Map entry value (schema in scope) | yes | Entry present with an absent value ([TSON-DATA] §2.9); `map`
> carries no element-state facet, so the permission is not schema-conditional |

Part 1 §2.9 agrees with §7.6, twice and normatively: "`_` may occupy any data-value position: record field
values, **map entry values**, array elements, and the document's top-level value", and "A field or **entry**
set to `_` is **present with an absent value** — distinct from not appearing at all."

So §5.3's justification is contradicted by the section that states the rule and by the Part 1 clause both cite.
Taken together the two live passages produce an author-visible incoherence: the value **is** optional, and the
author has no way to say it is not.

**And §12.1's grammar is a fourth passage, which sides against §5.3.** The `map-type` production draws its
value from `element-type`, and `element-type` carries the marker:

```
map-type     = "{" ws map-key ws "=>" ws element-type
               [ ws ";" ws size-spec ] ws "}"

map-key      = type-name [ "<" type-args ">" ]
element-type = type-ref [ "?" ]
```

So the ABNF already admits `{K => V?}` and already forbids `{K? => V}` — `map-key` has no `?` to write. §5.3's
prose contradicts the production directly on the value half, and merely restates it on the key half. That
makes the defect statable at its sharpest: **the grammar produces a marker the model has no field to bind.**
A conforming parser built from §12.1 alone accepts `{K => V?}`, reaches the desugar table, and finds `map`
has no `state` slot to put it in; only §5.3's prose stops it, and an implementation that reads the grammar
first will not find that prose until it has already built the node. Two implementations of Revision 33
therefore disagree about whether the document parses, which is the practical cost of leaving this open.

It also settles what the fix costs: **§12.1 needs no change.** The recommendation below is one kernel field
plus two prose edits, with the grammar already saying what the kernel would then be able to express.

Two further problems with §5.3's argument, independent of which way the contradiction is settled. It reasons
**from the artifact to the rule** — "the kernel's `map` has no `state` field" — where the kernel is this
series' own bundled document and its field list is the thing in question, not evidence about it. And its
parenthetical concerns absent *keys*, which is §2.9's own unconditional rule and says nothing about values;
the sugar's key side needs no `?` for a reason that has never been in doubt.

**Interpretation chosen: the recommendation below, built.** `map` carries a `state` field here, so `_` at a
map entry value reads as an entry present with an absent value where the declaration wrote `{K => V?}`, and
is `FIELD_REQUIRED` under the default — the two answers an array element already gets. Either way the entry
counts toward `min_items`/`max_items` (§2.9 has higher parts count all slots): the refusal costs the value
its verdict, not the entry its place. In tree mode a permitted absence is a `TsonAbsent`; in bind mode the
bound `Map` holds the key against a `null`, which is as close as Java comes to the distinction and still
tells it from a key never stated.

This is a **deliberate divergence from the published Revision 33 kernel**, the third this implementation
carries, and it resolves the contradiction rather than picking a side of it: §7.6's permission survives, now
sayable; §5.3's refusal survives for the key, where it was never in doubt. Before it, both halves were
implemented as written and the incoherence was author-visible — the value could not be marked optional and
was optional anyway.

**Recommendation — give `map` the `state` field, and let the sugar spell it.** This is the reading that makes
the container family uniform, and it is a subtraction from the prose rather than an addition:

```
map => ~product & {
  access_pattern:  product_access_type = NAMED
  size_type:       product_size_type = VARIABLE
  key_type:        type_ref
  value_type:      type_ref
  state:           element_state ~ REQUIRED
  min_items:       integer?
  max_items:       integer?
}
```

`element_state ~ REQUIRED` already appears three times in the kernel — `array`, `tuple_element`,
`field_group` — so this reuses the enum and the default rather than introducing either. Then:

- §5.3's table gains one row, `{K => V?}`, beside the `[T?]` row it copies; the "neither side admits `?`"
  sentence keeps only its key half, where it was never in question.
- §7.6's map-entry-value row becomes `conditional` and reads **identically** to its array-element row, and
  the clause explaining why the map row is the exception comes out. The table stops having an exception.
- Naming needs no new word. A map key can never be optional (§2.9), so `state` on `map` can only govern the
  value, exactly as `state` on `array` governs the element.

The two prose edits in full, so adoption needs nothing beside this entry. §5.3's table gains a row directly
under the `[T?]` row it copies:

```
| `{K => V?}`, `{K => V?; …}`  | the corresponding form with `state: OPTIONAL` bound directly                   |
```

and §5.3's map paragraph replaces one sentence. Currently:

> Neither side of `=>` admits `?`: the kernel's `map` has no `state` field, and absence has no defined meaning
> for map values (an absent key is already a resolver error, [TSON-DATA] §2.9).

becomes:

> The value side admits `?`, marking the value OPTIONAL exactly as `[T?]` marks an array element (§12.1's
> `map-type` already draws its value from `element-type`). The key side does not: an absent key is a resolver
> error ([TSON-DATA] §2.9), and `map-key` has no `?` to write.

§7.6's map-entry-value row becomes its array-element row with the nouns changed. Currently:

> | Map entry value (schema in scope) | yes | Entry present with an absent value ([TSON-DATA] §2.9); `map`
> carries no element-state facet, so the permission is not schema-conditional |

becomes:

> | Map entry value (schema in scope) | conditional | Permitted only when the map type's value state is
> OPTIONAL, written `{K => V?}` (§5.3); the entry is then present with an absent value ([TSON-DATA] §2.9) |

The key row of §7.6 is unchanged, and so is every other row.

**Adopting this invalidates data that Revision 33 accepts**, which is worth stating plainly rather than
leaving an editor to notice: a document writing `_` at a map value validates today against any map type and
validates afterwards only where the schema wrote `?`. Revision 33 is a working draft with no compatibility
guarantee between revisions, so this is permitted; it is not, however, a pure clarification, and it is the
one part of this proposal with a cost outside the specification text. The direction of the break is the
conservative one — documents become invalid rather than silently changing meaning, and the fix in every case
is one character in the schema.

**The reason to prefer it over the status quo is that the default flipped.** Every other container defaults
strict and is loosened with `?` — `[T]` is REQUIRED, a tuple position is REQUIRED, a record field is
REQUIRED. `{K => V}` alone defaults permissive, and there is no marker to tighten it. So a schema author can
forbid an absent array element and cannot forbid an absent map value, which is not a distinction any of the
four passages sets out to draw; it falls out of a missing field. For a format whose stated use is validation
feedback, "this map has no absent values" is an ordinary thing to want to say and currently unsayable.

**The third option is closed, and worth recording as closed.** Reading §5.3's sentence at face value —
absence genuinely has no meaning at a map value, so `_` is refused there outright — would be coherent, and it
is what this implementation did before the §7.6 reading was applied. It requires amending Part 1 §2.9, which
lists map entry values explicitly and states the present-with-an-absent-value distinction for entries as well
as fields. Part 1 is frozen, so this option is not available without reopening it; that is a reason to rule
it out rather than merely to disfavour it.

**Status against Revision 33:** open, new against this revision. The `state` field, the sugar's `?` on the
value side, and the reader's two answers are built and running here; the spec still says otherwise in both
places, which is what this entry asks the next revision to settle.

---

## 13. `atom-refinement` takes a `record-def`, which cannot express a constraint binding — every atom refinement in the spec's own `core.tn` fails to parse

**Section:** Part 2 §12.1 (`atom-refinement`, `refined-def`, `record-def`, `field-def`), §5.5 (atom
refinement), §5.6 (canonical form), §9 (the bundled `core.tn`).

**Problem:** §12.1 gives the two `^` forms the same payload production:

```
refined-def     = type-name [ws "<" type-args ">"] ws "^" ws record-def
atom-refinement = "!" type-name ws "^" ws record-def
```

For `refined-def` — §5.7 record refinement — `record-def` is right. `production => config ^ { host: =
"prod.example.com" }` binds a *modifier* onto a field the source record already declares, which is exactly
`field-def`'s third alternative.

For `atom-refinement` it is wrong, because the body is not a record of field declarations. It is a record of
**values** filling the constructor's constraint vocabulary. `record-def` expands to `record-entry`, to
`field-def`:

```
field-def  = *annotation field-name ws ":" ws
             ( field-type field-modifier / field-type / field-modifier )
field-type = type-ref ["?"]
```

so whatever follows `name:` must be a `type-ref` — and `type-ref` is `paren-type / bracket-type / map-type /
type-name ["<" type-args ">"]`, with `type-name = unquoted-token`. A constraint value is none of those.

**This is not a theoretical mismatch: the spec's own core type library does not parse.** `core.tn` declares
17 atom refinements, and every one of them fails or misreads under this production:

- **12 carry a nested record** — `int8 => !integer ^ { size: { bits: 8  signed: true } }`, and the eleven
  other sized integer families. A braced record is not a `type-ref` at all: `map-type` is the only brace
  alternative and it requires `=>`, while a bare record body is explicitly unspellable at a type position
  (§5.2). There is no reading under which this parses. Hard failure.
- **5 carry a bare number** — `positive_integer => !integer ^ { min: 1 }`, `negative_integer => !integer ^
  { max: -1 }`, `non_empty_text => !text ^ { min_length: 1 }`, and the rest. Here the production *shapes*
  the text but assigns it the wrong meaning: `min: 1` becomes a field named `min` whose declared **type** is
  `1`. Whether that is also a parse error turns on whether `type-name`'s numeric restriction ("a declaration
  name whose text matches the number production ... is a parse error") reaches a reference position or only
  a declaration; either way the resolver is handed a field declaration where the author wrote a constraint.

§5.5's own worked examples are in the second group (`!integer ^ { min: 0  max: 150 }`), so the production
does not parse the examples of the section it implements. A vocabulary with a quoted-string facet — a
`pattern`, a `spec` — would land in the first group, since a quoted token is not an `unquoted-token`.

**The one alternative of `field-def` that carries a value is `field-modifier`, and it means something else.**
`field-name ":" field-modifier` admits `min: ~ 0` or `min: = 0` — a field with a *default* or a *fixed*
value and no declared type. No example anywhere writes a refinement that way, and it would not mean what a
refinement means: §5.5's body binds a value into the constructor's vocabulary, where `~`/`=` declare how a
field of a record under construction behaves.

**§5.6 makes the contradiction exact**, because it prints the two forms side by side and they are the same
characters:

> `!integer ^ { min: 0  max: 150 }`   →   `!integer_type { min: 0  max: 150 }`

The right-hand side is `instance`, whose payload production is `core-value`. So one production calls
`{ min: 0  max: 150 }` a record of field declarations and the other calls the identical text a data record
of bindings, and §5.6 says the desugar between them is a retargeting of the head — the body is carried
across untouched.

**§12.1's own prose already half-concedes it.** Its opening paragraph says `core-value` "appears at exactly
one point — the constructor-application payload (`instance`, **§5.5**–§5.6)", citing the atom-refinement
section for the production that does not use it, and then states the mismatch as though it were a design
choice: "an atom-refinement body is a braced `record-def` (§5.5)".

**Interpretation chosen:** the body is a `core-value`, restricted to the braced form. `TsonSchemaParser`
requires a `{` and then parses the payload with the **data** grammar — the same `parseCoreValue()` the
`instance` branch calls one block below — and hands the resolver a `DataValue`. `DefinitionResolver` then
merges it over the target instance's own bound values per §5.6 and binds the result through the
constructor's compiled reader, which is what makes `!integer ^ { size: { bits: 8  signed: true } }` work at
all. The brace requirement is enforced as its own diagnostic ("an atom refinement's body is a braced record
of constraint bindings (§5.5), never a bare value, a second type-ref or an annotation") rather than by
falling through to `instance`, since `^` has already committed the production.

This was not a deliberate divergence — the AST node's own Javadoc has always read `atom-refinement = "!"
type-name ws "^" ws data-value`, three lines below a comment quoting the production as `record-def`. The
grammar could not have been implemented as written; nothing that reads `core.tn` can.

**Suggested resolution — change one token in the production**, and the prose that describes it:

```
atom-refinement = "!" type-name ws "^" ws core-value
                ; atom refinement (§5.5): the constructor's own
                ; constraint bindings, the same payload `instance`
                ; takes; the target MUST resolve to an atom-family
                ; instance (§3.3.1)
```

§5.5 already supplies the restriction the production then needs — "the body MUST be a braced record of
constraint bindings" — so the positional form (§5.6) is excluded by prose that exists, and no second
production is required. `refined-def` keeps `record-def` unchanged; it was always the right payload there,
and the fix does not touch §5.7.

§12.1's opening paragraph then needs its count corrected: `core-value` appears at **two** points, the
constructor-application payload (`instance`, §5.6) and the atom-refinement body (§5.5) — which is the same
statement §5.6 already makes when it desugars one into the other.

**This is an error, not an open design question.** Unlike the entries above it, there is nothing here for a
revision to weigh: the production cannot parse the bundled artifact the same document publishes, and one
token fixes it. It is filed with them only because that is where findings against Revision 33 live.

**Status against Revision 33:** open, new against this revision. The `core-value` reading is what is built
and running here, and is the only reading under which `core.tn` loads.

---

## 14. §7.1's UAX #31 profile is unambiguous, and its most likely misimplementation admits bidi controls into identifiers — plus R1a (ZWJ/ZWNJ) is unaddressed

**Section:** Part 1 §7.1 (the UAX #31 profile, and the byte-order-mark rule), §9.4 (confusables and bidi),
§1.3 (the Unicode foundation principle).

**This entry is not a defect report.** §7.1's profile is correct, and its BOM rule is one of the clearest
sentences in the series. What is reported is (a) a gap the profile leaves an implementer to guess at, and
(b) an implementation hazard the reference implementation walked straight into, which the section is well
placed to warn about and currently does not. Both come from a second implementation — a TypeScript port —
diverging from this one and being right.

**(a) UAX #31 R1a is unaddressed.** §7.1 declares a profile "per UAX #31 requirement R1" and gives the set
algebra:

```
Start    = XID_Start ∪ Nd ∪ { - + . }
Continue = XID_Continue ∪ { - + . }
```

UAX #31's next requirement, R1a, is the one about ZWJ (U+200D) and ZWNJ (U+200C): a profile is expected to
state whether it permits them, because several scripts — Persian, and the Indic scripts generally — need
them to spell ordinary words, and neither is in `XID_Continue`. §7.1 says nothing, so the answer is
"excluded, by set membership". That is a defensible answer and very likely the intended one, but it is
currently reached by silence rather than stated, and §1.3's own promise — "Field names and values work in
all scripts without quoting" — invites exactly the question R1a exists to answer. An implementer serving
Persian or Devanagari data will look for the provision, not find it, and has to decide whether the omission
is a decision or an oversight. One sentence closes it either way.

**(b) The profile is set algebra over UAX #31 properties, and the obvious host predicate is a different
set — in a way that is security-relevant.** This is worth a conformance note because of what the divergence
contains rather than its size.

This implementation used `Character.isUnicodeIdentifierStart/Part` as the XID stand-in. That predicate is
`ID_Start`/`ID_Continue` **∪ everything `Character.isIdentifierIgnorable` covers**, which is every `Cf`
character plus the non-whitespace C0/C1 controls. Measured on JDK 25, `isUnicodeIdentifierPart` returns true
for all of:

```
U+00AD SOFT HYPHEN        U+200C ZWNJ    U+200D ZWJ       U+2060 WORD JOINER
U+FEFF ZWNBSP             U+061C ALM     U+0001 SOH       U+007F DELETE
U+202A..U+202E  (LRE, RLE, PDF, LRO, RLO)                 U+2066..U+2069  (LRI..PDI)
```

none of which is in `XID_Continue`. The consequence is that a lexer built on the host shortcut accepts
`ab<U+202E>c` as a single identifier — a Trojan Source payload inside a field name — while §9.4 elsewhere
discusses precisely that risk. The characters §9.4 is *about* are admitted by the profile's most convenient
misreading.

Three things make this worth the spec's ink rather than just the implementation's:

1. **The trap is not Java-specific.** JavaScript offers `\p{ID_Continue}` and `\p{XID_Continue}` one letter
   apart, and Python's `str.isidentifier()` is XID-based but carries its own underscore rule. Picking the
   wrong one is a plausible first move in every host language, and it fails silently: every ASCII test
   passes.
2. **§7.1 already anticipates one member of the set, which masks the rest.** Its byte-order-mark paragraph
   says "U+FEFF anywhere else outside a quoted token is an unrecognised character and a lexer error", and
   that is the *only* invisible character named. A reader checking "does this document handle invisible
   characters?" finds a precise rule, concludes the topic is covered, and never notices that U+202E is
   handled only by set membership — even though U+FEFF is the least harmful member and the bidi controls
   are the dangerous ones.
3. **The reference implementation is the evidence.** It implemented three of §7.1's four BOM positions
   correctly — leading BOM stripped, BOM between tokens a lexer error, BOM in a quoted token content — and
   got the fourth wrong, absorbing a mid-token BOM into an identifier. Its own class comment asserted the
   rule it was breaking ("a BOM anywhere else is left alone, falling through to 'unrecognised character'
   naturally"), which is true between tokens and false inside one. So the same document lexes U+FEFF as
   not-a-character at offset 0 and as an identifier character at offset 2. NFC is not a backstop: NFC
   preserves `Cf`, so every one of these tokens is already normalised.

**Suggested resolution.** Two additions to §7.1, both small:

- After the profile's set algebra, state R1a explicitly — e.g. *"This profile does not adopt UAX #31 R1a:
  ZWJ (U+200D) and ZWNJ (U+200C) are not permitted in unquoted tokens. Scripts requiring them use the quoted
  form."* If the intent is the opposite, the same sentence inverted, with the contexts R1a scopes.
- Extend the byte-order-mark paragraph, or add one beside it, so the rule generalises from the one character
  to the class: *"U+FEFF is not special in this respect. No `Cf` (format) character is in `XID_Continue`, so
  none may appear in an unquoted token — the bidi formatting controls (U+202A–U+202E, U+2066–U+2069, U+061C)
  included, which §9.4 depends on. Implementations SHOULD note that host-language identifier predicates are
  frequently `ID_*` rather than `XID_*` and frequently admit the identifier-ignorable characters, and so are
  not substitutes for the properties named here."*

The second is the one that would have prevented this bug, and it costs three sentences in the section that
already spends a paragraph on a single member of the class it describes.

**Interpretation chosen:** the profile as written — `XID_*`, format characters excluded, ZWJ/ZWNJ excluded
with them. That is not yet what this implementation does; the divergence is tracked in `BACKLOG.md` and is
the reason this entry exists. The TypeScript port already implements the profile as written, using real XID
tables, which is what surfaced the difference.

**Status against Revision 33:** open, new against this revision. Raised from a two-implementation
comparison rather than from reading, which is the first time in this register.
