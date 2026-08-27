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

The eight below are what Revision 33 leaves open, renumbered from #1; the 55 raised against Revision 32 that
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
   - A materialisation diagnostic must be **located at the template's own declaration**, with the application
     as context. Deferred checking is survivable only if the author is sent to the line they can edit.
     - **This is the one recommendation here that is a requirement rather than a report.** It is what the
       design owes an author in exchange for deferring the checks, and it is not yet met: this implementation
       locates a defect inside a held body at the *application*, `box => <T> !array { element_type: some_typo
       }` applied by `use` reporting at `/use` rather than `/box`, because the walk back to a positioned entry
       finds the application first. Reaching the declaration needs the minting phase to record which
       declaration each derived entry came from — a bookkeeping change, not a design one, which is why it is
       offered as a rule the spec should state rather than a cost of holding. Stated here rather than
       discovered later, since the rest of this entry is reporting what runs.

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

- **The value-conformance rule is a recommendation, not a report: it is not built here.** `{ first: int32 ~
  "nope" }` resolves, links and compiles clean, and the first read of that type fails inside the compiled
  reader. So the substitute this entry offers for the argument-kind rule is proposed on its merits rather
  than demonstrated, and §5.10 should not drop the kind rule without stating the replacement normatively in
  §5.2 — a resolver that drops the one and does not add the other loses both.

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
a parametric enum member (above), value conformance of a field's `~`/`=` against its declared type (above),
locating a held-body defect at the template's declaration (above), and the D6 merge that would make the two
lift channels agree (above). A parameterized `atom-refinement` is *deliberately* not on that list: it remains
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

## 8. `supertypes` is typed `[type_name]`, so composing against an open application has nowhere to record what it composed

**Section:** Part 2 §5.8 ("Parameterized references"), §5.7 (refinement), §8.1
(`type_definition.supertypes`/`subtypes`, `record.supertypes`), §9 (a slot holding a type reference MUST be
typed `type_ref`). **Read with #5 and #7** — same defect as #7, at a second slot, and reachable only because
#5's held form makes the shape resolvable at all.

**Problem:** `vip => <T> customer & box<T>` looks like it needs materialisation and does not. Composition
copies the source's field set (§5.8), and under a held body that field set is *already known* while the
application is still open: `box => <T> { v: T }` holds `!record { fields: [ { name: v type: T } ] }`, and
binding `box`'s parameter to the token `T` is the same token rewrite substitution performs everywhere else.
The result is an ordinary flattened record that still mentions `T` —

```
vip => <T> !record { fields: [ { name: id type: text } { name: v type: T } ] }
```

— which is exactly the shape a composition template already resolves to when its parameter sits in its own
body (`<T> base & { value: T }`, resolved and pinned here). Nothing about the *fields* needs closing.

What cannot be written is the other half of what composition produces. §5.8 makes `vip` a subtype of both
operands, and both slots that record it are names:

```
type_definition => { ... supertypes: [type_name]?  subtypes: [type_name]? ... }
record          => ~product & { ... supertypes: [type_name]? }
```

`box<T>` is not a `type_name`, and there is no entry to name instead — an open application denotes no entry,
and naming the bare template `box` would name something §5.10 says is not a type. So the composition can be
performed and cannot be recorded. Dropping the operand is not available either: it is what populates the
subtype lattice, and §5.4's discrimination rules consume it.

This is #7's finding at a second slot. §9 tells an extension meta-schema that "a slot holding a type
reference MUST be typed `type_ref`", and the kernel's own supertype slots are exactly that and are not.

**Interpretation chosen:** refused at the declaration, at both absorbing positions, as a stated library gap
(`NOT_IMPLEMENTED`, §5.8/§5.10) rather than an author error — the schema is well-formed and this
implementation cannot record its result. The refusal's *reason* is being corrected alongside this entry: it
read "an open application has no field set", which is the thing this entry shows to be false.

**Suggested resolution:** declare `supertypes: [type_ref]?` on both `type_definition` and `record`.

- **`subtypes` does not move.** It is derived at link time over registered entries, so it only ever names
  closed ones. Only the authored direction can hold an open operand.
- **Closed schemas are unaffected in practice**, a no-argument `type_ref` being spelled bare — but #5's
  one-spelling rule applies here too, and §8.1 should say that a supertype with no arguments is written
  positionally, so a serialiser cannot produce a second spelling of the same entry.
- **A refinement source needs nothing**, which is worth noting because it shows the gap is the slot and not
  the feature: `type_definition.source` is already `type_ref`, so `<T> box<T> ^ { ... }` can record what it
  refined — and is refused today only because refinement puts its source into `supertypes` as well.
- **What this does not settle** is whether `vip<text>` and a hand-written `customer & box<text>` land on one
  entry. They have the same fields, and their supertype lists agree only if the open one's `box<T>` closes to
  the same instantiation the closed one names. That is the D6 merge question #5 raises, arriving from a third
  direction.

**Status against Revision 33:** open, new against this revision. Not implemented here — the field-set half is
a token rewrite this codebase already performs, and the recording half is blocked on the kernel declaration
above, so building it before the slot widens would mean inventing a representation the spec does not have.
