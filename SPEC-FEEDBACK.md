# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation.
See `CLAUDE.md` for why this file exists and when to add to it. Spec quotes below are from
2026 Revision 35 — Part 1 (https://tson.io/raw/2026/35/tson-part1-data.md) unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a
suggested resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a
revision closes.** It is an input to the next revision's adjudication, so its numbering is the numbering
that revision's change log will answer against — a stable index of the open set, not an archive of
everything ever raised.

**Revision 35 closed thirty-two of the thirty-six open against Revision 34**, and the four below are what
survives, renumbered from #1. The closed entries are gone: the spec now carries their rules, and that is
where the answer belongs — the JSON-superset cluster (`null`, the escape table, field names as identifiers,
the trailing comma, and the four decisions kept with better reasons), the `scoped` constructor, the `bytes`
value space, the temporal split and its exclusive bounds, `members` on the exact numeric tiers, the checked
`@discriminator` and `@rest`, the value-space clause, the reference-is-a-hop change, the `~` marker's
removal, the network emptiness rule, the one limits policy, and the name-hygiene reporting shape all landed
as proposed or better. **This file is the as-built record**, not a pointer to one: where an entry proposes a
design this implementation has built, the entry states the design, what is running, and what is not, so that
a reviewer editing the spec needs nothing beside it. **Where the evidence is a consumer of this library
rather than this library** — #1 and #2 were found building the HTTP layer in `ltr8-io-tson-java-http`, and
this register is the collection point for all of it — the entry says so and states what is running there on
the same terms. **Cite the spec, not the argument that got it there:** `docs/` and the Javadoc name the
section that requires a behaviour, and a `SPEC-FEEDBACK.md #N` citation is for an entry below, where there
is no section to point at yet. When an entry closes, its citations become spec citations and the entry is
deleted — nothing here is an archive.

**What is left is a coherent set rather than a remainder.** Three of the four are about artifacts and
channels the series has not yet named — where a deployment's policy lives (#1), how a document names its
schema when its encoding has no directive syntax (#2), and whether a namespace should be a value (#3) — and
the fourth is a single under-exercised freedom that costs a rule downstream (#4). None is a defect in a rule
the spec states; each is a place the series stops short of stating one.

---

## 1. §8.2's policy has no artifact, and the two obvious homes are both wrong

**Section:** [TSON-DATA] §8.2 (name hygiene, "The policy is not a property of a schema"), §9.1 (the limits
policy), with consequences for [TSON-SCHEMA] §3.5 (schema immutability) and [TSON-DATA] §2.2.1 (canonical
identity).

**What Revision 35 settled, so that what is left is visible.** §8.2 now names the two policies, makes them
properties of the *report* rather than of a refusal, requires a processor to state them with no document in
hand, makes relaxation a code decision rather than an ambient one, and says outright that the policy is **not
a property of a schema and no schema carries one** — with all three reasons: self-certification, immutability,
and mechanism 1's failure to compose across `!!import`. §9.1 does the same for the limits policy and reports
it through the same surfaces. Every half of this entry that was about *reporting* is closed.

**What is left is the artifact.** §8.2 ends on "a deployment's own configuration, or an artifact of a kind
this series does not yet define — it is named at the call site and never resolved by identity." That sentence
is exactly right and is a placeholder. Two policies and, in a real deployment, a fetch allow-list and a set of
host mappings have to live somewhere, and the series names no kind for them while naming a kind for everything
else it asks a deployment to hold.

**What is missing is a third artifact kind, and it already has a homeless occupant.** §2.2.1 evicted the port
from identity — "no port (default or otherwise)" — and never said where location went. A **deployment
descriptor** is what that has been trying to be: location, fetch allow-lists and host mappings, and the two
§8.2 policies beside §9.1's. It should be **data, not a schema**, and that line is worth stating in the
series: an API description must be a schema because `request: order` is a type reference the resolver resolves
(§4.1's `data` kind, §9's `type_ref` rule), where a deployment descriptor references no types — a level is an
enum member, a host is text, and even a per-schema policy holds *identities*, which are URIs.

| Artifact | Kind | Shared with counterparties | Immutable |
|---|---|---|---|
| Schema | schema | yes, by identity | yes ([TSON-SCHEMA] §3.5) |
| API description | schema (holds type refs) | yes, by identity | yes |
| Deployment descriptor | **data** (holds no type refs) | no — see discovery below | **no** |

**§8.2's closing constraint is one of the two that matter; the other is unstated.** *Named at the call site,
never discovered* is there — a runtime that loads whatever descriptor is on its path lets a container image
swap change a security policy with no code diff. *Never resolvable by identity* is half there: §8.2 says the
policy is never resolved by identity, which is the property, but nothing says a **descriptor** may not be
`!!import`ed or named from a document. The moment a document can point at one it selects its own enforcement
level, and self-certification returns by the back door the front one was just closed against.

**Discovery is the half a format can usefully standardise.** A counterparty has a legitimate question — what
will this endpoint accept? — and three answers with different standing. **The refusal is the authority**,
being the only report that cannot be stale, which is what §8.2's reporting rule now secures. **A
`.well-known` path (RFC 8615) for the origin's acceptance profile** is the neat one: in this series everything
with an identity is served at its identity's path, and a deployment descriptor is precisely the artifact that
must *not* have an identity, so a well-known path is the right shape for it for the same reason it is the
wrong shape for a schema — but what is published there must be a *projection*, since fetch allow-lists and
host mappings are internal topology. **Not the API description**, which would advertise a mutable policy from
an immutable artifact. Per-endpoint policy is the awkward case, a well-known document being origin-scoped:
the honest answer is probably that the profile advertises the origin's default and the refusal reports what
actually applied.

**Interpretation chosen:** all three policies are code calls on `TsonConfig` (`identifierPolicy`,
`tokenPolicy`, `maxDepth`), with no artifact of any kind; `Tson.processorPolicy()`, `Tson.limitsPolicy()`,
either read facade's, and `tson policy` are the no-document-in-hand surfaces §8.2 and §9.1 ask for. The
consuming HTTP project leaves them at this library's defaults with its position written down in prose rather
than expressed in a document — which is the gap this entry reports, met from the other side.

**Suggested resolution** (a proposal — nothing here is built): name the third artifact kind, say that it is
data rather than a schema and why, and make the second constraint normative beside the first — no `!!import`
of a descriptor and no document able to name one. Failing that, the placeholder sentence is a reasonable place
to stop, and this entry is content to be answered with "not this revision."

**Status against Revision 35:** open, and reduced to its artifact half. Revision 34 introduced the policy
layer that had nowhere to live; Revision 35 gave it everywhere to be *reported* and left where it lives
undefined on purpose. Adopting this entry is a new section; declining it costs nothing that is currently
broken.

---

## 2. A document whose encoding has no directive syntax has no way to name the schema that governs it

**Section:** [TSON-DATA] §6 (TSON and JSON), §7.1 (encoding, normalization, and media type), §2.2 (the
header), with §2.2.1 (canonical identity) for the conflict rule.

**Problem:** `!!schema` is TSON *text* syntax. §6 now says a JSON document is not a TSON document and is
"read through a JSON reader, which is one encoding of the same model", and [TSON-SCHEMA] §1 says the same of
every other encoding defined against the encoding-independent model. So the format has a growing family of
encodings and exactly one of them can say which schema governs a document. §7.1 already legislates for HTTP
(`application/tson; version=1`, "if disambiguation is needed in HTTP contexts") and stops exactly before the
parameter that would answer this.

Revision 35 sharpened this rather than settling it. Under Revision 34 the gap was a corollary of the
superset claim — a JSON document was a TSON document and could not carry a directive. With the claim gone the
gap is structural: an encoding of the model is a first-class citizen with no in-band channel at all, and the
series has stopped calling that a compatibility question without giving it an answer.

**A stronger reason than encodings turned up, building version routing:** an intermediary routing between two
servers by schema cannot parse the body to find out which one. nginx, Envoy, API gateways and CDNs route on
headers and paths and none of them parse bodies — that is a layering violation before it is anything else —
and `Content-Encoding: gzip` makes it impossible rather than merely rude. The honest limit is that a header
does not save the *origin* from peeking, since if header and body can disagree the endpoint must still read
the directive to check; the saving is at the network, and at a body in an encoding that has no directive,
where the header is the only possible source and there is nothing to check against. CloudEvents is the
precedent: `dataschema` is a context attribute that its HTTP binding maps to a `ce-dataschema` header
precisely so intermediaries can handle a message without opening it.

**Interpretation chosen:** the consuming HTTP project implements the header as `TSON-Schema` and treats it as
a *projection* of `!!schema` rather than an alternative to it — an RFC 9651 structured field whose Item is an
**sf-string**, so the value is quoted, which also matches `!!schema`, whose argument must be quoted for the
same reason (a URI contains `:` and `/` and falls outside §7.1's unquoted-token profile). It may appear
alongside the directive, and the two must then agree by canonical identity (§2.2.1 — scheme and any `?sha256=`
pin do not count). It is defined for a body of any media type, which is what gives a non-text encoding a
channel at all. A body naming no schema by either channel stays schemaless Class 1 and valid TSON; rejecting
one is **endpoint policy**, not a property of the media type. `TsonSchemaVersions` refuses a document that
names no version rather than guessing one. A companion `TSON-Accept-Schema` — an sf-list of sf-strings with
`;q=`, `Accept` to the first field's `Content-Type` — carries which versions a client can read *back*, a
second field rather than a second meaning because one message routinely asks both at once.

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

**Status against Revision 35:** open, with its premise changed by the revision that did not answer it. §6 was
rewritten around the removal of the superset claim and §7.1 around the identifier layer, and neither gained a
way to name a governing schema out of band. The entry is easier to answer now than it was: the question is no
longer "what do we owe JSON" but "what does an encoding of this model use, when it has no header of ours".

---

## 3. A namespace should be a value — the kernel's 2×2 has an empty cell

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
under a `data &` meta layer, a two-declaration binding names its method by `type_name` with the reader
checking it at startup, and the method-as-type shape is measured and kept as a probe rather than adopted.

**Suggested resolution:** none requested — a direction rather than a request, filed so the 2×2 and the operator
argument are on record where the next revision is designed. The two are what make the primitive look inevitable
rather than added.

**Status against Revision 35:** open, and **deliberately held over a second cycle: the shape needs further
investigation before anything is built against it.**

Publishing is not what stands in the way, and saying so matters because the reason first given here was that
it was. It read: every route changes the meta-kernel, the kernel is a published hash-pinned artifact
([TSON-SCHEMA] §10, §13.2), and nothing can be built without minting digests for a document nobody has
published. That constraint is gone. This branch moved all three companion artifacts to `/2026/35/`
identities precisely so that a revision's own proposals could be built against artifacts named for it, and
every built proposal Revision 35 adopted — the `scoped` constructor, the `bytes` redesign, the temporal
split, `members` on the numeric tiers, the two checked annotations — landed on that basis.

What stands in the way is the design. A namespace value is not one addition but a question about what the
kernel's 2×2 is for, and the entry above sketches a cell rather than settles one — so it is held over rather
than implemented ahead of an answer. That is a different state from the other three entries here: each of
those is a gap with a known shape, where this one is a direction whose shape is the open question.

One thing Revision 35 changed on this side is worth recording, because it removes an objection rather than
answering the entry. The `~` marker and `type_definition.constructor` are gone, and applicability is IS-A
`top` (§3.3.1, §4.2) — so the modelling above, which was written "with no meta layer and no `~` at all" to
avoid the marker, is now simply how a constructor is declared. The measurement it rests on stands unchanged.

---

## 4. §7.5 leaves set element order free for four fields nobody exercises it on, and pays for it with a comparison rule

**Section:** [TSON-SCHEMA] §7.5 (sets; element order; the comparison MUST), §1.3 (a resolver MUST produce a
resolved schema value; output MUST conform to §8), §8 (the serialization contract), §7.4 (enum member
semantics), §9 (`enum_set`, `integer_member_set`); [TSON-DATA] §8.1.

**Problem:** §7.5 says element order in a set is implementation-defined, then puts a MUST on everyone who
compares resolver outputs:

> Sets are unordered; the materialised representation uses array syntax, but element order is
> implementation-defined. Implementations comparing resolver outputs MUST compare set-typed fields as sets, not
> ordered lists; fixture-comparison tools SHOULD canonicalise set-typed fields (e.g. lexical sort) before
> byte-comparison.

Three things make that rule cost more than it buys.

**1. It applies to four fields, and to no list anyone compares.** The set-typed fields in meta-kernel, meta.tn
and core.tn combined are `enum.members` (`enum_set`), `integer_type.members` (`integer_member_set`),
`decimal_type.members` (`set<value>`) and `scoped.scope` (`set<scope_kind>`) — three member sets and one
two-element flag set. Every other list in §8's output — `supertypes`, `subtypes`, `variants`, `elements`,
`fields`, `groups`, `parameters` — is an array whose order is either significant or, for the two §8.2 calls
"name-level indexes", already free by that section's own words. So the whole of §7.5's implementation-defined
order, and the MUST that compensates for it, exist for member sets and a two-member enum set: positions where
source order is what every producer emits and where no producer has ever wanted the freedom.

**2. It puts an obligation on the wrong side of the comparison.** §1.3 makes producing a resolved schema value
a MUST and fixes its serialization in §8, so resolved output is the artifact the series checks against — the
companion `*-resolved.tn` documents are exactly that, byte-fixed and published. §7.5 then makes those bytes one
conforming output among many, and moves the burden onto every consumer to know which of §8's fields are
secretly sets. A structural comparison of two resolved documents cannot be written from §8 alone; it has to
carry a table of set-typed positions read out of §9.

**3. Nobody wants the freedom, and this implementation is on the wrong side of the MUST.** `EnumBody` holds
`List<String> members` in source order, and `ResolvedForm.canonical` — shared by `ResolvedFixtureTest` and the
Class 2 conformance runner — normalises `supertypes`/`subtypes` and states that "nothing else is normalised; a
difference anywhere else is a real one". So every set-typed field is compared as an ordered list, in the two
places this implementation compares resolved output. That is a conformance gap against §7.5's MUST and it has never
surfaced, because source order is what every producer emits and what §7.4's own reading of an enum makes
natural. The rule is a freedom nobody exercises, guarded by a MUST nobody keeps.

**The direction worth considering is the opposite one.** §7.4 already ties an enum's discrimination class to
its members' own tokens and describes members as declared names; source order is meaningful to a reader, and a
binary or ordinal-based encoding needs it to be canonical rather than free — an encoding assigning members
ordinals cannot do so from a set whose order the spec refuses to fix. Making member order significant in
resolved output costs nothing anyone has, since every implementation preserves it already, and turns §7.5's
comparison MUST into a rule that needs no table: two resolved documents are compared as §8 writes them.

**Interpretation chosen:** §7.5's *representation* as written, and — stated plainly because it is a
divergence — not its comparison MUST. `EnumBody` preserves source order, `!enum [OPEN OPEN]` is refused by
the set's uniqueness contract, and every comparison site treats a set-typed field as an ordered list. Nothing
here canonicalises one before comparing.

**Suggested resolution:** in §7.5, replace the implementation-defined order with a stated one, and delete the
comparison MUST and the fixture-tooling SHOULD that exist only to absorb it. Two candidates, and the choice
should be made on what §8's output is for rather than on set theory:

- **Source declaration order is canonical** for `enum.members` and for every other set-typed field, by the same
  rule. It is what every producer already emits, it keeps §8's output comparable as written, and it is what an
  ordinal-assigning encoding would need. §7.5's "sets are unordered" stays true of the *value* — uniqueness and
  set equality are unchanged — and becomes a statement about semantics rather than about bytes.
- **Bytewise-ascending order of the elements' canonical encodings**, if a canonical form is wanted that two
  producers reach independently from differently-ordered sources. This is the stronger determinism guarantee
  and the one a content-addressed or binary encoding would want; it costs the author's declaration order, which
  §7.4 gives a reader a reason to care about.

Either way §7.5 keeps its duplicate-handling paragraph and its uniformity sentence unchanged, and the entry's
point stands under both: the field's order should be *stated*, not left free and then compensated for by a rule
on everyone downstream.


**Status against Revision 35:** open and untouched — §7.5's paragraph is byte-identical to Revision 34's.
Unlike the entries this revision closed, this is not a proposal for new vocabulary but a rule to remove: what
it asks is that one under-exercised freedom be spent, so that the comparison MUST it necessitates can go. It
records a divergence rather than a build — this implementation does not implement §7.5's comparison rule, in
either of the two places it compares resolved output.

Revision 35 makes the ask sharper in one place and no harder anywhere. §5.5's value-space clause now states
that equality is defined over value spaces, and §5.7 states that a **member set** may shrink to a subset
"compared by the family's own value identity" — so set *membership* is settled and set *order* is the one
thing left free. And the revision added three set-typed fields where there was one: `members` on the exact
numeric tiers (§7.4) and `scoped.scope` (§7.8). That strengthens the case rather than weakening it — the table
a conforming comparison has to carry is now four rows long, still read out of §9 rather than out of §8, and
still describing a freedom no producer takes.
