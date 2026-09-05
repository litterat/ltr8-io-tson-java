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

**Revision 35 closed thirty-two of the thirty-six open against Revision 34**, and #1–#4 below are what
survives, renumbered from #1; #5–#7 were opened against Revision 35 itself. The closed entries are gone: the
spec now carries their rules, and that is where the answer belongs — the JSON-superset cluster (`null`, the
escape table, field names as identifiers, the trailing comma, and the four decisions kept with better
reasons), the `scoped` constructor, the `bytes` value space, the temporal split and its exclusive bounds,
`members` on the exact numeric tiers, the checked `@discriminator` and `@rest`, the value-space clause, the
reference-is-a-hop change, the `~` marker's removal, the network emptiness rule, the one limits policy, and
the name-hygiene reporting shape all landed as proposed or better. **This file is the as-built record**, not a
pointer to one: where an entry proposes a design this implementation has built, the entry states the design,
what is running, and what is not, so that a reviewer editing the spec needs nothing beside it. **Where the
evidence is a consumer of this library rather than this library** — #1 and #2 were found building the HTTP
layer in `ltr8-io-tson-java-http`, and this register is the collection point for all of it — the entry says so
and states what is running there on the same terms. **Cite the spec, not the argument that got it there:**
`docs/` and the Javadoc name the section that requires a behaviour, and a `SPEC-FEEDBACK.md #N` citation is
for an entry below, where there is no section to point at yet. When an entry closes, its citations become spec
citations and the entry is deleted — nothing here is an archive.

**The set divides in two.** #1–#4 are places the series stops short of stating a rule: where a deployment's
policy lives (#1), how a document names its schema when its encoding has no directive syntax (#2), whether a
namespace should be a value (#3), and one under-exercised freedom that costs a rule downstream (#4). None is
a defect in a rule the spec states.

**#5–#8 are, and #5–#7 are one defect seen from three sides.** Those three are about the entry an open
declaration resolves to; #8 is the same shape of problem one field over — a derived fact recorded where
nothing can enforce the rule it needs. §8.1 says an open entry's `body` is "typed by the kernel's `schema`
without a second value shape"; it is not, and §5.10's own `vector` example is the counterexample (#5).
`type_definition.kind` is derived at resolution but taken as unverified input at ingest, so an entry can state
a kind its own body contradicts (#6). And `source` has three incompatible definitions for an open entry across
§5.10 and §8.1 (#7). They are separable — each can be adopted alone — but #5's shape depends on #7's answer,
and #6 is what makes #5's new `kind: TEMPLATE` cost nothing. **The evidence in all three is measured output
from this build.** All four are now running, three of them partly: the bundled `meta-kernel.tn` declares the
`template` constructor, `type_definition` has lost `parameters` to the held body and `disjoint` to the choice
body, `type_kind` has gained `TEMPLATE`, and `source` never carries a parameter — so the artifacts are ahead
of the spec text on all four. What is left of each is the spec edit, plus #6's reclassification of `kind` as
derived, which is the one recommendation with no implementation behind it. Each entry's Status paragraph
states exactly which half is built and which is not.

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


## 5. An open entry's resolved `body` is not a `top`, and §8.1 says it is

**Section:** [TSON-SCHEMA] §8.1 (the `type_definition` field list; the open-entry paragraph; Ingest), §5.10
("Held bodies", "One spelling", "Open bodies in output"), §1.3 (a resolver MUST produce a resolved schema
value, and §8 fixes its serialization), §9 (meta-kernel's `type_definition`).

**Problem:** §8.1 makes an open entry an ordinary value of the kernel's `schema` type:

> An **open** entry is a `type_definition` like any other: its `parameters` list is non-empty and its `body`
> is the held application in wire form under §5.10's one-spelling rule (`set => !type_definition { kind:
> PRODUCT  source: set_type  parameters: [T]  body: !set_type { element_type: T } }`), typed by the kernel's
> `schema` without a second value shape, since a parameter reference is an `identifier` where a type name is.

The premise — "a parameter reference is an `identifier` where a type name is" — holds only where every
parameter stands in a `type_ref` slot. §5.10 does not confine them there, and says so in as many words: "a
parameter in a value slot (`min_items: N`), in a type slot (`element_type: T`), inside a collection ... or at
any depth is a token like any other". So the claim fails for exactly the case the section introduces the
mechanism for, and it fails on §5.10's own headline example.

**Measured, both ways.** Each document below is the resolved form §8.1 prescribes, validated as data against
`meta-kernel.tn`.

`vector => <T, N> !array { element_type: T  min_items: N  max_items: N }` — §5.10's own example — **does not
validate**:

```
[ATOM_CONSTRAINT_VIOLATION] /vector/body/min_items  'non_negative_integer': 'N' is not a valid integer
[ATOM_CONSTRAINT_VIOLATION] /vector/body/max_items  'non_negative_integer': 'N' is not a valid integer
```

That is a direct contradiction between §8.1 and §5.10, and §1.3 makes producing conforming output a MUST, so
it is not a matter of convention.

**Where it does validate, it validates as something else.** These two are accepted, silently:

```tson
extern_of => !type_definition { parameters: [S]  body: !scoped { scope: [EXTERN]  schemas: { S => _ } } }
e         => !type_definition { parameters: [M]  body: !enum { members: [a b M] } }
```

`S` binds as a relative URI naming a schema; `M` binds as a third enum member literally spelled `M`. Both are
core.tn-shaped forms — `extern_of` is core.tn's, verbatim. An ingest implementation following §8.1 accepts
them and builds a wrong schema with no diagnostic anywhere. This is the worse half: the failing case at least
fails.

**Recommendation — a kernel constructor whose instances *are* held bodies.** This is running (below):

```tson
template => top & {
  parameters:  [param_name]
  template:    text
}
```

so that

```tson
set => !type_definition {
  kind:    PRODUCT
  source:  set_type
  body:    !template { parameters: [T]  template: "!set_type { element_type: T }" }
}
```

with `type_definition.parameters` deleted and `type_kind` gaining `TEMPLATE` (#6). It composes with `top`
directly, like `reference` and `data`: it describes no value's shape, and nothing is ever typed by it. The
gains are four:

1. **`body: top` becomes true with no exception**, so §8.1's "body values are annotated with the
   structurally-appropriate type" holds universally and the open-entry paragraph's caveat goes.
2. **Both failure modes above disappear.** There is nothing left to misread, because nothing is read.
3. **"Open" is one question with one answer.** §5.10's "Closed entries are parameter-free" is today a MUST
   over two fields that must agree, which §8.1 asks ingest to verify. With `parameters` inside `!template`
   the invariant is structural and unstatable-as-violated.
4. **Text is what "held" already means.** §5.10 defines a held body as "the constructor application as
   written, held and *unread*", and §8.1's Ingest paragraph already treats it as source — "an open entry ...
   has its held body **re-resolved as source**". Text is the exact representation of that; the present form
   writes a parsed thing back out and asks readers not to parse it.

**Two things the resolution must state.**

**The comparison is of the parsed form, not the text.** Otherwise §5.10's "One spelling" silently becomes a
canonical-*whitespace* requirement, which is stricter and which the series has no emitter contract to
support. With the parsed form normative, "One spelling" survives unchanged and whitespace is free.

**Which answers "when is a held body ever compared?" — three times, and the first is load-bearing.**
*Identity*: an open synthetic's entry name is a content hash of its held binding record with the parameters
renamed positionally (`p0`, `p1`, ...), so two spellings of one open form must reduce to one name or `<T> {
a: [T] }` written twice mints two entries for one type. That is what "One spelling" exists for, and it is a
comparison of the parsed form by construction. *Ingest*: §8.1 already requires that an open entry's "wire
form MUST equal the one-spelling form §5.10 requires". *Conformance*: two resolvers' outputs, or one against
the published `*-resolved.tn`.

**What this costs, stated plainly.** A resolved document stops being fully inspectable by a generic TSON
reader: an open body becomes a blob only a schema parser can open. §1.3 bounds the blast radius — "a consumer
of closed entries never meets one" — and §8.1's Ingest already requires a schema parser for open entries, so
no consumer needs a capability it did not already need. The alternative that avoids it, modelling Part 1's
data grammar as kernel vocabulary, is the complete version of the `instance_template`/`template_argument`/
`value_param` approach Revision 35 has just removed, and is a large surface for a form §1.3 says most
consumers never see.

**This proposal depends on #7.** Moving `parameters` inside `body` is only sound if no parameter reference
can appear outside a held body — that is, if `source` never holds an open application. §8.1's alias paragraph
says it does not; §5.10's "Open bodies in output" and §8.1's "Reading parameter references" say it does.

**Status against Revision 35: the constructor and the held-as-text body are running here.** The bundled
`meta-kernel.tn` declares `template`, `schema.meta.TemplateBody` carries `parameters` and the application as
text, and both `*-resolved.tn` fixtures write open entries in the new form. `vector` above now validates and
`extern_of` reads back as the application it holds, both asserted in `OpenEntryResolvedFormTest` — the same
two cases that produced the measurements above.

What is **not** running, and is the remaining ask of the spec rather than of this implementation:

- **`type_definition.parameters` is still written**, alongside the list inside `!template`, so an open entry
  states its parameters twice. Deleting the outer one waits on #7.
- **`type_kind` has no `TEMPLATE` member yet** (#6), so an open entry still takes its kind from the
  constructor its held body applies.
- **Nothing checks that a held body *re-resolves* as source.** The round trip that is pinned is narrower:
  the text a resolver writes parses back to the tree it was written from (`HeldBodyTest`). §8.1's Ingest rule
  asks for more — that the parsed application resolve against a namespace — and this library has no ingest
  path at all, so no implementation evidence stands behind that half of the recommendation.

Two things the change cost less than expected, worth recording for whoever adopts it. Adding the constructor
needed no reader factory and minted no new synthetic: `template.parameters: [param_name]` landed on the entry
`type_definition.parameters` had already lifted, which is §8.2's schema-wide form identity with no special
case. And it **removed** roughly sixty lines of comparison machinery that existed only because an open entry
could not be compared like any other entry.


## 6. `type_definition.kind` is derived at resolution and taken as unverified input at ingest

**Section:** [TSON-SCHEMA] §8.1 (the `type_definition` field list; `supertypes`/`subtypes`; `disjoint`;
Ingest), §4.1 (kinds), §9 (meta-kernel's `type_kind`).

**Problem:** §8.1 sorts `type_definition`'s fields into two classes and puts `kind` in neither.
`subtypes` is "a cache: fully derivable, always recomputable, never trusted"; `disjoint` is
resolver-derived and "on ingest ... MUST be discarded and recomputed"; `supertypes` is "taken as input,
with the transitive closure recomputed and integrity verified", and §8.1 explains at length why it is not
recomputable for the atom family. `kind` gets one clause — "(ATOM, PRODUCT, SUM, DATA, or REFERENCE —
§4.1)" — and appears nowhere in the Ingest paragraph's list of what must be discarded, recomputed or
verified.

So it is a REQUIRED field, derived by every resolver from §4.1's rules, restating what the entry's own
`supertypes` and `body` already determine, with nothing anywhere checking that the three agree. A resolved
document stating `kind: SUM` over a `!record` body is accepted by a conforming ingest. That is the same
class of defect as #5 — a field saying something the record around it already says, with no rule that they
must match — and it is the smaller and cheaper of the two to close.

**It is derivable. Measured: 264 closed entries, 0 mismatches.** Over meta-kernel.tn, meta.tn, core.tn and a
user schema exercising every declaration form (construction, atom refinement, alias, enum, record,
composition, array/map/choice/tuple sugar, template, partial application, nested instantiation), this rule
reproduces `kind` exactly:

1. the body is held (an open entry) → **TEMPLATE** (see below);
2. else the body's constructor head is `reference` → **REFERENCE**;
3. else the entry IS-A `top` — it is a constructor — → the base-kind name among its own `supertypes`
   (`atom`/`sum`/`data`), or PRODUCT if none;
4. else → the `kind` of the entry the body's constructor head names.

Branch 3 is §4.1 applied to a constructor, whose kind states what its *instances* will be rather than what
its own body is: `integer_type => atom & { ... }` has a `!record` body and `kind: ATOM`. Branch 2 is the one
§4.1 already calls out — an alias's REFERENCE is "a type_kind and not a base kind", and the kernel's
`reference` constructor is itself PRODUCT, so the head lookup would give the wrong answer.

**And yet it should stay, for a reason the derivation itself exposes.** Branch 4 is a namespace lookup, and
**97 of those 264 entries need a head that is not in their own schema's namespace** — it is in the governing
meta. core.tn's `integer` is `!integer_type {}` and `integer_type` lives in meta.tn; `extern_of` is
`!scoped { ... }` and `scoped` lives in meta.tn. A consumer holding core.tn's resolved output and nothing
else cannot derive `kind` for 97 of its entries. `kind` is precisely the field that makes an entry
classifiable **without the governing meta chain in hand**, which is the position §1.3's closed-entry
consumer is in. Deleting it would push a four-branch rule with two special cases and a cross-namespace
lookup onto every consumer, to save one enum-valued field.

**What it is not doing, so the value is not overstated.** It is consumed at exactly two places in this
implementation's pipeline: an atom-instance eligibility test for `^` (which needs `kind == ATOM` *and*
`supertypes` not containing `top`, so kind alone does not decide it), and materialisation, which reads a
template's kind to give the closed instantiation its own. `TsonSchemaLinker` does not consult it at all —
its §4.1 DATA refusal tests the body (`body instanceof Data`), and its constructor-eligibility test asks
`supertypes.contains("top")`. Reader compilation ignores it entirely, dispatching on the body. And it does
not enter identity: §8.2's derived names hash the binding record, never the kind.

**Interpretation chosen:** derived at resolution from §4.1 and threaded through every later phase as data.
Nothing here re-derives or verifies it after resolution, which is exactly the hole this entry describes —
this implementation would accept a forged `kind` in an ingested resolved document today.

**Suggested resolution: reclassify rather than remove.** Move `kind` into the same class as `subtypes` and
`disjoint` — resolver-derived, **discarded and recomputed on ingest** — and state the derivation rule above
in §8.1 so an ingest implementation has one. The field stays REQUIRED in output and stays a one-hop read for
consumers; what changes is that it is no longer trusted from a document, which closes the hole with a
sentence in the Ingest paragraph rather than a vocabulary change.

While that paragraph is open, §8.1 currently describes derivation three different ways — `subtypes` as "a
cache ... never trusted", `disjoint` as "MUST be discarded and recomputed", `supertypes` as "taken as input
... integrity verified". A single statement of what *derived* means, with `kind` joining it and `supertypes`
named as the one deliberate exception, is the tidier outcome and costs nothing.

**Add `TEMPLATE` to `type_kind`.** This is #5's fourth gain and the reason the two entries belong together.
Under #5's `!template` body, an open entry's kind is the one case branches 2–4 cannot answer locally, and
`kind: TEMPLATE` answers it — "open" becomes visible in the same field every other classification lives in,
matching `type_kind`'s existing precedent that not every member is a base kind (`REFERENCE` is not, and
§4.1 says so). Two consequences worth stating rather than discovering:

- **It is not free.** Materialisation currently takes the closed instantiation's kind from the template's
  own `kind` field. Under TEMPLATE that source is gone and the closed entry's kind must come from its own
  closed body instead — by branches 2–4, which apply to it like any other closed entry. Contained (both
  sites already hold the closed body and the constructor head), but real.
- **"What will `set<text>` be?" stops being answerable from `set`'s entry alone.** It is answerable from the
  instantiation, which carries `kind: PRODUCT` derived from its own closed `!set_type { element_type: text }`
  body — so a consumer asking about a *type* loses nothing, and only one asking about a *template* does.
  Adding a `produces: type_kind` field to `!template` would answer it, and should not be done: it is a
  channel for a question nobody has asked, and it would be the same unverified restatement this entry is
  about.

**Status against Revision 35: `TEMPLATE` is running; the reclassification is still a proposal.**

`type_kind` carries a sixth member here and every open entry takes it, so the ask of the spec on that half is
to describe what the artifacts already do. It cost what the entry said it would and paid for itself twice
over. Materialisation stopped being able to take the closed entry's kind from the template's own field, and
what replaced it is the derivation this entry states — `kindOfClosed` reads the branch of `Top` the
substituted body occupies, which is §4.1's "construction transfers kind" asked of the construction rather
than inherited from an entry that is not a type. Two conflations fell out with it: `DefinitionResolver`
carried an `alias` flag whose only job was reading an open alias's kind off its head, and a test asserted
`REFERENCE` for an entry its own comment called "a template until it closes". Both are gone.

**And the derivation is now total**, which is the argument for stating it in §8.1. An open entry was the one
case that could not be answered from what the entry states — its kind came from the constructor its held body
applies, which needs the governing meta — and `TEMPLATE` answers it locally.
`OpenEntryResolvedFormTest.kindAgreesWithWhatTheEntryAlreadyStates` runs the whole four-branch rule over
every entry of every schema, and is the rule §8.1's Ingest paragraph should carry.

**What is not running is the reclassification**, which is this entry's actual ask: `kind` is still a REQUIRED
input that nothing verifies, and §8.1's Ingest paragraph still never names it. That needs no artifact change
— prose in §8.1 plus a `@doc` line — and it has no implementation evidence behind it here for the same reason
#5's ingest half has none: this library has no ingest path, so the check would have nowhere to live but a
test.


## 7. `source` has three incompatible definitions for an open entry

**Section:** [TSON-SCHEMA] §5.10 ("Open bodies in output", "Partial application"), §8.1 (the `reference` and
`record_field` records; "Reading parameter references"; "`source` is structured provenance"), §8.3
(references).

**Problem:** three passages say three different things about whether an open entry's `source` carries the
application, and therefore about whether a parameter reference can appear outside a held body.

**(a) §5.10, "Open bodies in output"** — it does:

> ... whose `body` is the held application in wire form under the one-spelling rule ... with the open
> application recorded in the entry's `source` where the body is a pure application (§8.1).

**(b) §8.1, the `reference` and `record_field` paragraph** — it does not, and this is the whole reason
`reference.target` was widened to a `type_ref` in this revision:

> ... and an application with its arguments, in `type_ref`'s record form, where the alias is still open
> (`<B> !reference { target: { name: pair  arguments: [ { name: uuid }  { name: B } ] } }`, §5.10), so that
> a partial application states the arguments it binds and `source` is never asked to hold them.

**(c) §8.1, "Reading parameter references"** — it can:

> ... so a `name` — in any `type_ref`, at any depth, including `type_argument` name members,
> `reference.target`, and the `source` field — resolves against the enclosing entry's `parameters` list
> first, then the schema's type-name namespace ...

(a) and (c) agree with each other and contradict (b). The contradiction is not cosmetic: it decides whether
a parameter can occur outside `body`, which is what #5's relocation of `parameters` into the `!template`
constructor depends on. Under (b) an open entry is self-contained and the move is clean. Under (a)/(c) the
list declaring `B` sits two levels inside `body` while a use of `B` sits in `source` beside it, and §8.1's
own reading rule — "resolve against the enclosing entry's `parameters` first" — becomes a conditional
descent into a sibling field, evaluated before a consumer can tell whether `B` names a parameter or a type.

**What this implementation emits.** Every open form, resolved and printed. `source` is the bare constructor
head in every case — no arguments, no parameter anywhere outside the held body:

| declaration | `body` | `source` |
|---|---|---|
| `pair => <A, B> { first: A  second: B }` | `!record { fields: [...] }` | `record` |
| `boxes => <T> [T]` | `!array { element_type: T }` | `array` |
| `text_keyed_map => <V> {text => V}` | `!map { key_type: text  value_type: V }` | `map` |
| `vec => <T, N> !array { ... }` | `!array { element_type: T  min_items: N ... }` | `array` |
| `uuid_pair => <B> pair<text, B>` | `!reference { target: { name: pair  arguments: [...] } }` | `reference` |

So this implementation follows (b): the application lives in `reference.target` and `source` never holds an
open one. Note that `source` does already carry *closed* applications — a materialised instantiation records
`vector<text, 3>` — so the objection is not that `source` cannot hold an application, only that it should
not hold an **open** one.

**A second divergence falls out of the same table, and needs settling either way.** A **closed** alias
records its *target*: `closed_alias => text` gives `source: text`, and the published fixture's `annotation`
gives `source: void`. An **open** alias records the *constructor*: `uuid_pair` gives `source: reference`.
The same field means "the type I alias" when closed and "the constructor that built my body" when open, and
the open reading matches none of (a), (b) or (c) — (a) wants `pair<text, B>`, the closed convention would
want `pair`, and this gives `reference`. This is a genuine divergence here rather than a chosen
interpretation: it is what falls out of an alias template resolving through the same held-instance path as
every other open form, and no rule was consulted.

**Interpretation chosen:** (b), by construction rather than by decision — `source` is set to the head of the
constructor the held body applies, so no parameter reaches it. The linker nevertheless validates `source`
against the entry's own `parameters`, which is (c)'s rule implemented against a case that never arises.

**Suggested resolution: adopt (b) and delete the other two.**

1. In §5.10's "Open bodies in output", strike "with the open application recorded in the entry's `source`
   where the body is a pure application". An open alias's application is in `reference.target`, which is
   what §8.1's alias paragraph and the widening of `reference.target` to a `type_ref` were both for.
2. In §8.1's "Reading parameter references", drop `source` from the list of positions resolved against
   `parameters`. State the invariant positively instead: **a parameter reference appears only inside a held
   body**, and every other `type_ref` in an open entry — `source` included — names a type. That is the
   sentence #5 needs in order to move `parameters` into the body, and it is worth stating for its own sake:
   it tells a consumer that `source` can be read without knowing whether the entry is open.
3. Settle what an open entry's `source` *is*, since it is now free of the application. Two coherent answers,
   and the closed/open alias divergence above disappears under either: **the constructor the held body
   applies** (`record`, `array`, `map`, `reference` — what this implementation emits, and what makes
   `source` mean the same thing for the four non-alias forms), or **the head the entry aliases** for the
   alias form specifically (`pair`), matching what a closed alias records. The first is more uniform; the
   second keeps `source` answering one question — "what did this come from?" — across both alias forms. The
   first also leaves `source: reference` saying nothing a reader could not see from the body, which argues
   for the second.

**Status against Revision 35: (b) is running, and the divergence in the table is fixed.**

`TsonSchemaLinker` no longer passes the entry's parameters when validating `source`, so a parameter reaching
that position is an unresolved reference — the verdict every other reference form gives it
(`aParameterStandingInSourceIsUnresolved`). Nothing produces one: an open entry records the constructor its
held body applies, and a partial application states the arguments it binds in its own `reference.target`,
written through `WireForm.heldReference`. The closed/open alias divergence is gone with the factory that
caused it — `TypeDefinition.reference(target, parameters)` built an open entry with a bare `Reference` body,
which the model now forbids outright, since an open entry's body is held whatever shape it takes.

**Point 3 is therefore settled the first way**: an open entry's `source` is the constructor its held body
applies, uniformly across all five open forms, and the alias is no longer the exception. What remains is the
spec edit — striking `source` from (a) and (c), and stating the invariant positively: a parameter reference
appears only inside a held body, so `source` can be read without knowing whether the entry is open.

The passages themselves are untouched. Passages (a) and (c) are Revision 35 text; (b) is new in Revision 35,
introduced with the `reference.target` widening, which is why the three now disagree — the widening did the
work that made `source`'s role obsolete without the other two passages being updated to match. What is
running here is (b), and the alias `source` divergence in the table, which is unchosen and should be fixed
here once the spec says which of the two answers in point 3 is right. #5's proposal to move `parameters`
into the `!template` body is contingent on this entry resolving to (b).


## 8. `disjoint` is a fact about a variant list, recorded where nothing has one

**Section:** [TSON-SCHEMA] §8.1 (the `type_definition` field list; "`disjoint` is a resolver-derived fact
over choices"; Ingest), §5.4 (choice types, discrimination-class distinctness), §7.2, §9 (meta-kernel's
`type_definition` and `choice`).

**Problem:** §8.1 puts `disjoint` on `type_definition` and then spends a paragraph saying where it is not:

> For every choice definition — every entry whose body is a `!choice` binding record, declared or synthetic —
> the resolver records `disjoint: true` or `false` ...; the field is absent on every other definition, the
> non-choice sums (the `scoped` instances, §7.8) included, since they have no variant list to derive it over.

That last clause is the whole argument. The fact is derived from a **variant list**, and `choice` is the only
body that has one — so "absent on every other definition" is a rule the model needs only because the field
sits somewhere that cannot enforce it. It is stated in prose, it must be verified on ingest, and it is
forgeable: a `type_definition` with an `!enum` body and `disjoint: true` is well-formed against §9's
`type_definition` and means nothing.

The same paragraph has to name the awkward case explicitly — a `scoped` instance is a SUM with no variants —
which is the shape of a field in the wrong place: the exceptions are enumerated rather than impossible.

**Recommendation — move it onto `choice`.** This is running (below):

```tson
choice => sum & {
  variants:  [type_ref]
  disjoint:  boolean?
}
```

so a choice entry resolves to

```tson
shape => !type_definition {
  kind:   SUM
  source: choice
  body:   !choice { variants: [circle square]  disjoint: false }
}
```

Three things follow, and none needs prose:

1. **"Absent on every other definition" stops being a rule.** An entry with no variants has nowhere to put
   the fact, so the §7.8 `scoped` carve-out disappears rather than being restated.
2. **It cannot be forged onto a non-choice.** §7.2's closed-record rule refuses `disjoint` on any other body,
   which is the same check that already polices every other field.
3. **The fact sits beside the thing it is derived from.** A consumer reading a `!choice` has the variant
   list and the verdict over it in one record, where before it had to look up one level for a field whose
   presence depended on the body it was looking at.

`type_definition` keeps `subtypes`, whose scope really is every entry, so the two derived indexes end up
distinguished by what they are indexes *over* rather than sitting together because both are derived.

**Status against Revision 35: running.** The kernel declares it on `choice`,
`TypeDefinition.disjoint()` is a derived read off the body, and `TsonSchemaLinker.computeDisjointness` writes
the body it derives from. Both consumers — §5.4's `@disjoint` assertion check and `ChoiceReader`'s untagged
structural recovery — were untouched by the move, which is the measure of how contained it is: the fact
changed address, not meaning.

**What it cost, for whoever adopts it.** One conformance vector, `class2/schema/valid/choice-of-two-records`,
which compares a whole resolved document. The `class2/link/` disjointness vectors state the fact as a
`{name, value}` pair rather than as a document and needed nothing — a small confirmation that the corpus's
layers are cut where `RUNNER.md` says. `ChoiceBody` gained a second public constructor (the authoring form,
with the fact absent) and therefore `@Record` on its canonical one.

**Its scope is exactly §5.4's, and no wider.** This says nothing about `subtypes`, which is an index over the
whole namespace, and nothing about the separate question #6 raises of whether a derived field should be
verified on ingest at all — `disjoint` is already stated to be discarded and recomputed, and moving it does
not change that.
