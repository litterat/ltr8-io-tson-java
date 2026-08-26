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

The five below are what Revision 33 leaves open, renumbered from #1; the 55 raised against Revision 32 that
it resolved are gone from here, because the spec now carries their rules and that is where the answer
belongs. **Cite the spec, not the argument that got it there:** `docs/` and the Javadoc name the section
that requires a behaviour, and a `SPEC-FEEDBACK.md #N` citation is for an entry below, where there is no
section to point at yet. When an entry closes, its citations become spec citations and the entry is deleted
— nothing here is an archive.

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

This is not hypothetical. This implementation reads the token, and the two produce entries whose bodies are
byte-identical:

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

**Interpretation chosen:** the token, because the model has to fill a `Token`-typed component and §5.10's
"bare token" is the only prose that speaks to it (`schema.meta.Token`, `RawTokenParser`). The consequence
above is accepted and recorded rather than worked around: normalising numeric tokens before hashing would
get §4's equivalence back, but it would be this implementation inventing an identity rule the spec does not
state, and the resulting entry set would then disagree with any implementation that took the prose at face
value.

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

**Status against Revision 33:** open, carried deliberately — deferred to the next revision of the
structure-templates design. §5.3 now says an unquoted token argument "is classified against the applied
signature's parameter kinds", which settles *what kind* of thing an argument is but not the identity
question this entry asks: whether `<255>` and `<0xFF>` are one application or two. `RawTokenParser` still
keys identity on the spelling, so this implementation has two.

---

## 5. §5.10's collection-slot boundary refuses what the kernel's own vocabulary licenses, and it excludes the sum-typed result envelope

**Section:** Part 2 §5.10 (the two parameter kinds, and the collection boundary), §8.1 (`template_argument`,
`type_ref`, `record_field.value_param`), §5.3 (the lift rule), §5.10.1 (regularity), §12.1
(`instance-template`). Related: `spec/tson-cr-structure-templates-addendum.md`, which raises the same defect
against Revision 33 and proposes two endpoints for it; and the item declined at Revision 33 as #53.

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

**Interpretation chosen:** implemented as written. `SchemaDesugarer` refuses a parameter in a
collection-valued slot at the declaration, classified as a schema-author error (`TsonSchemaValidationException`)
rather than a library gap — the verdict does not change as this implementation improves, which is the test
this project's exception policy turns on, and reporting it as a gap would exit 70 over a construct the spec
itself refuses. Everything else in §5.10 resolves: record templates, open instances, partial application,
regular recursion, and closed container positions including nested arguments.

**Suggested resolution:** delete the boundary by holding the open body instead of quoting it. Three rules,
which together remove `instance_template`, `template_argument` and `value_param` rather than extending them:

1. **An open entry stores its body in the form it was written** — head plus core-value, sugar expanded but
   nothing lifted — not in a typed open vocabulary. Substitution is then one rule at every depth (rewrite
   tokens that resolve into `parameters`), uniform across type slots, value slots, collection elements and
   nesting, and materialisation binds against constructor vocabulary exactly once, at the only moment binding
   is decidable. §12.1's `instance-template`/`template-def`/`template-bind` productions delete and
   `[type-params]` is reinstated on the instance alternative; §5.3's open-lift case deletes (a template holds
   one body and lifts nothing; materialisation substitutes, then desugars-and-lifts the now-concrete forms
   innermost-out); open synthetic entries disappear as a category, taking §8.2's open-synthetic identity
   clause with them.
2. **The resolved form of an open entry is its declaration, round-tripped** — not a `type_definition`
   instance, which could not carry it in any case, since `body: top` is REQUIRED and an open entry has no
   `top` to put there. This keeps resolved output a valid schema document under §12.1's own grammar, so it
   stays re-resolvable, and it keeps the kernel unchanged: no `form` primitive, no `( body | template )` field
   group. It also makes the open form *better* for cross-schema use than a typed quotation would be — an
   importing schema materialising an imported template runs the ordinary desugar-and-bind pipeline over
   source-shaped content, with no second path from quotation to body. §1.3 is unaffected: a conforming
   consumer of resolved output meets only closed entries and instantiations.
3. **Checking moves with the body.** A template body is not resolved at its declaration, so an unapplied
   template gets **no verdict** — not a warning, no verdict; nothing in the document is wrong yet, and data
   validation is unaffected. Everything is checked at materialisation, where substitution resolves the whole
   body rather than a path through it. Two corollaries the spec should state rather than leave to be
   discovered: a materialisation diagnostic must be **located at the template's own declaration**, with the
   application as context, or the author (increasingly, a generator) edits the use site for a defect in the
   declaration; and §5.10.1's regularity rule and the inhabitance check move from declaration time to
   materialisation time, since the linker can no longer see references inside a held body.

The alternative to rule 3 — checking an unapplied template by substituting stand-ins — should be rejected
explicitly, because it manufactures false errors on precisely the slots this whole mechanism exists for:
`<N> !integer ^ { min: N max: 3 }` is correct for every argument anyone will pass and fails the `min <= max`
coherence check under a stand-in of 10. A deferred verdict is strictly better than a fabricated one.

**If Revision 34 wants a smaller edit than that**, one scoped change resolves the flagship case against the
shipped design: restate §5.10's uniformity rule so that an open entry carries an ordinary constructor body
whenever every parameter occurrence sits at a `type_ref` position, requiring `instance_template` only where a
value slot is parameter-bound, and narrowing the collection error to parameters at *value* positions inside
collections. Choice, tuple, `[T]` and `{K => V}` templates fall out immediately, and the body-identity concern
is answered by determinism rather than uniformity. Note that this is not free for implementations that shipped
Revision 33: it changes the resolved output of templates that already work today, since `<T> { v: [T] }` stops
lifting an `instance_template` and carries an ordinary body.

**Status against Revision 33:** open, new against this revision. The addendum in `spec/` records that the same
gap was raised against Revision 32 as #53 and declined, with §5.10 gaining the explicit boundary sentence and
§8.1 the uniform-quotation rationale in response. This implementation refuses the construct as the spec
requires; the held-body design above is being implemented here as proof for Revision 34 (`BACKLOG.md`, "Open
form: hold the template body").

---
