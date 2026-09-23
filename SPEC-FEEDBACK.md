# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation.
See `CLAUDE.md` for why this file exists and when to add to it. Spec quotes below are from
2026 Revision 35 — Part 1 (https://tson.io/raw/2026/36/tson-part1-data.md) unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a
suggested resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a
revision closes.** It is an input to the next revision's adjudication, so its numbering is the numbering
that revision's change log will answer against — a stable index of the open set, not an archive of
everything ever raised.

**Revision 35 closed thirty-two of the thirty-six open against Revision 34**, and #1–#4 below are what
survives; #5 onward were raised against Revision 35 itself. The closed entries are gone: the
spec now carries their rules, and that is where the answer belongs — the JSON-superset cluster (`null`, the
escape table, field names as identifiers, the trailing comma, and the four decisions kept with better
reasons), the `scoped` constructor, the `bytes` value space, the temporal split and its exclusive bounds,
`members` on the exact numeric tiers, the value-space clause, the
reference-is-a-hop change, the `~` marker's removal, the network emptiness rule, the one limits policy, and
the name-hygiene reporting shape all landed as proposed or better. **This file is the as-built record**, not a
pointer to one: where an entry proposes a design this implementation has built, the entry states the design,
what is running, and what is not, so that a reviewer editing the spec needs nothing beside it. **Where the
evidence is a consumer of this library rather than this library** — #1 and #2 were found building the HTTP
layer in `ltr8-io-tson-java-http`, and this register is the collection point for all of it — the entry says so
and states what is running there on the same terms.

**Part 3 is not in this register.** [TSON-JSON] is an early draft this implementation exists to validate, and
`spec/tson-part3-json.md` is edited **directly** as findings arise — so a Part 3 finding becomes a spec change
in the same session, with git history as its record, rather than an entry waiting for adjudication. Two
entries that were Part 3's (#6, §9.4's name-hygiene reach, and #9, §6.5's map-form test against the kernel's
`value`) are gone for that reason and their rules are in the document. **The numbers are not reused and the
gaps are left** — the register renumbers only when a revision closes, so that a citation written against the
open set stays valid until then. What stays here is Parts 1 and 2, whose current
revision is published and whose changes this implementation proposes rather than makes. An entry spanning both
stays, and says which half is which.

**Cite the spec, not the argument that got it there:**
`design/` and the Javadoc name the section that requires a behaviour, and a `SPEC-FEEDBACK.md #N` citation is
for an entry below, where there is no section to point at yet. When an entry closes, its citations become spec
citations and the entry is deleted — nothing here is an archive.

**What is left is a coherent set rather than a remainder.** Each of #1–#4 is a place the series stops short
of stating a rule: where a deployment's policy lives (#1), how a document names its schema when its encoding
has no directive syntax (#2), whether a namespace should be a value (#3), and one under-exercised freedom
that costs a rule downstream (#4). None is a defect in a rule the spec states — the four that were, all about
the entry an open declaration resolves to, are in §4.1, §5.4, §5.10 and §8.1 now.

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

**Interpretation chosen:** all three policies are code calls on `ProcessorConfig`
(`withIdentifierPolicy`, `withTokenPolicy`, `withLimits`), with no artifact of any kind; `Tson.processorPolicy()`, `Tson.limitsPolicy()`,
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
published. That constraint is gone. This branch moved all three companion artifacts to `/2026/36/`
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


## 5. A JSON member name that is not an identifier has no home

**Documents:** [TSON-JSON] §1.3, §6.1, §6.1.1; [TSON-DATA] §7.7, §7.2.5; [TSON-SCHEMA] §6, §12.1.
**Kind:** underspecification, against a goal the document states for itself.

**This entry is a proposal, not a report.** Nothing here is running: `tson-json` reads records in tree and
bind mode, and a member name that is not an identifier is refused where it is read, with no projection to
declare. What is evidenced is the gap, which is a reading of the published documents rather than a finding
from a build.

[TSON-JSON] §1.2 makes this document "the normative JSON interoperability surface of the series", and the
conversion path that implies — a JSON Schema or OpenAPI contract becomes a TSON schema, and the documents
already in flight validate against it unchanged — meets a wall the documents do not address.

**No principle promises that path, and the document should say which way it means it.** §1.3's principle 2
("Plain JSON out") governs the *encode* direction: a TSON schema's documents reach the wire as ordinary JSON
that an OpenAPI-style contract describes. It says nothing about reading foreign JSON, and no other principle
does either. So the on-ramp is a goal the document is read as having and never states. Either it owes a
principle or it owes a sentence putting the on-ramp out of scope; the present silence is what lets each
reader assume the answer they need.

**A JSON member name is an arbitrary string; a TSON field name is an identifier — but the gap is narrower
than it looks.** Revision 35 settled the second half deliberately (`field-name` is an identifier at every
layer, [TSON-DATA] §7.7, §2.5), and §3.2's reserved-namespace argument depends on it. What that excludes is
less than it first appears: §7.7's `identifier-continue = XID_Continue / "-"` admits the hyphen, so
**kebab-case is unaffected** — `user-name`, `Content-Type` and OpenAPI's `x-` extensions are all valid TSON
field names, and §7.2.5 confirms the intent ("negative numbers and hyphenated names are unaffected").

What remains outside the grammar is the name that is `@`-initial (JSON-LD's `@context` and `@type`),
digit-initial (`2fa_enabled`), underscore-initial (`_id`, pervasive in MongoDB-derived documents),
space-bearing, or empty. This is §7.7's **grammar**, not §8.2's policy, so no processor configuration
reaches it and no relaxation exists — which is correct, and is exactly why the gap needs an answer somewhere
else.

A converter meeting one has a single option today: refuse, and the producer changes their wire format. The
second option the register used to record — collect the members into an `@rest` map — is gone with the
annotation (#20), and it was the worse of the two anyway: the members read, and lose every declared type,
facet and field state, so a contract whose ten fields are `@`-initial converted to a record with no fields.
The remaining answer is [TSON-JSON] §6.1.1's: type the position as a map throughout and forgo per-field
validation, which is honest and total but is not a conversion.

**Suggested resolution — a projection annotation.** [TSON-SCHEMA] §6 already carries the licence: "An
encoding-rules document MAY bind projection behaviour to a schema-side annotation declared for it." Nothing
uses it — #11 put the discriminator in the kernel and #20 retired `@rest` — so this would be the first
instance of §6's representation-directive category, and it is the shape the licence was written for: force
in the encodings that carry it, none in the model, the declared field keeping its identifier name everywhere
the name is a name.

```
web_hook => {
  @json_name:"@context"      context:        text
  @json_name:"2fa_enabled"   two_fa_enabled: boolean
}
```

The load checks follow the established pattern and are few: the argument is a non-empty string that is a
well-formed member name under §3.1's profile; it does not begin with `$` (§3.2's namespace is not spellable
from data); and it collides with no other declared field's own name or projection within one composed record.
Decode's binding order at §6.1.1 gains one step — reserved names, declared names, **declared projections** —
and the encoder writes the projection where it has one.

**If it lands, §6 owes a definition of a directive's class.** §6 says a directive "binds every encoding in
its class" and offers the text class — TSON text beside JSON — as the example, which is the one class a
JSON-only projection is not in. That was already the flaw that retired `@rest` (#20); a projection annotation
walks into it a second time, and this time the category would have a member, so the definition cannot be
deferred again.

Three alternatives were considered and are worse. **Relaxing §7.7** to admit `@` and a digit-initial form
undoes a Revision 35 decision, breaks §3.2's collision-free argument, and changes the *model* to serve one
encoding. **A `patternProperties`-style key map** types the values but not the names, so `user-name` and
`usr-name` validate alike. And **the map-typed position** is the status quo, whose cost is stated above.

The narrower question, if the annotation is not wanted: §6.1.1 should at least *say* what a member name that
is not an identifier does, since a reader today has to derive it from §7.7 and it is not obvious that the
answer is "nothing in this encoding can declare it".


## 7. §4.1 has no term for a position typed by a host type, so a binding processor has no rule to follow

**Documents:** [TSON-DATA] §4.1, §4.2, §4.4; [TSON-SCHEMA] §4.2; [TSON-JSON] §4.1, §5.7.
**Kind:** underspecification — a real and common processing mode the section's dichotomy does not name.

§4.1 divides the world in two:

> Base type resolution applies **only in schemaless documents** — a document whose header carries no
> `!!schema` (§2.2) — and only to a token that carries no built-in type annotation... Under a schema it does
> not apply at all: every value is typed by its position or by its tag, each declared atom type owns its own
> parsing contract, and there is no third way to read a token.

The division is over **documents**, and the third case is over **readers**: a processor reading a document
that carries no `!!schema` **into a declared host type** — a Java class, a Go struct, a Rust type. Nothing in
the document types the position and something else does. §4.1 has no term for it, so a processor either
applies §4 (the document has no schema) or treats the target as typing the position (the position *is*
typed), and the two give different answers for the same bytes.

**The answers differ on ordinary data, not on corner cases.** At a target declaring a 32-bit integer:

| document | §4 applied | target types the position |
|---|---|---|
| `{ i: 1.0 }` | float, then a narrowing failure | `int32` rejects a float form — a resolver error (§8.1) |
| `{ i: "12" }` | string, then a bind failure | `12`, since a typed position does not consult the form |
| `{ s: 12 }` at a text target | number, then a bind failure | `"12"`, the token's own content |
| `{ d: ".nan" }` at a float target | string, then a bind failure | `NaN` |

**Every row's right-hand column is what a schema declaring those same types already produces**, which is the
argument for it: a processor that reads `box => { i: int32 }` one way and a `Box` class with an `int` field
another way has two readings of one shape, and the difference is invisible to whoever wrote the document.
The left column also makes the *diagnostic* worse — a bind failure names a host type the sender has never
heard of, where the family's own refusal names the constraint they broke.

**This is not the same as letting a target override the document.** A type-ref still wins (§4.1's own
"a built-in annotation overrides base resolution"), and a target that names no built-in family — `char`, an
opaque object, a host type outside §5's vocabulary — still leaves the position untyped and still gets §4.
What changes is only the case where the target names a family the spec already defines.

**Where §4 then genuinely applies is a tree read**: a processor producing a document-shaped value with no
target at all — this implementation's `TsonValue`, and the `JsonValue` beside it — is the case §4.1
describes, and the only one left once binding is accounted for. That is a better statement of §4's scope than
"schemaless document", because it is about whether anything types the position rather than about what the
header says.

**[TSON-JSON] already had to answer this and answered it the same way**, which is the strongest evidence the
rule generalises: §4.1 there makes the position decide, and §5.7 says the decode "needs none of base type
resolution because JSON's grammar has already done the classifying". A JSON document carries no `!!schema`
either. If the JSON encoding's answer is that the position types the value, the text encoding's answer for
the identical processing mode should not be the opposite.

**The interpretation this implementation has taken** is that a position typed by a host type is a typed
position: `HostAtoms.forTypedPosition` maps a target class to the family it names, `DataClassObjectReader`
reads through it, and base type resolution is reached only where no family names the target.
`ClassTypedPositionTest` asserts every row above against the schema that declares the same types, so the
schema is the oracle rather than a literal.

**Suggested resolution.** Restate §4.1's applicability over what types the position rather than over the
header, roughly:

> Base type resolution applies to a token that carries no built-in type annotation, at a position **nothing
> types** — that is, in a document with no `!!schema` being read into no declared type, as when a processor
> produces a document-shaped value. Where the position is typed — by a schema, by a tag, or by a declared
> host type a processor is reading into — the type's own parsing contract reads the token and this section
> does not apply.

The sentence about `true`/`false` (§4.2) and the quoted-token rule (§4.4) follow it unchanged: both are
statements about base type resolution, so both stop at a typed position, which §4.2 already says of a schema
("Under a schema neither token is special").


## 8. `boolean` is missing from §5's built-in type vocabulary

**Documents:** [TSON-DATA] §5, §5.6, §4.2; [TSON-SCHEMA] §4.1.
**Kind:** error — an omission from a table the section states is complete.

§5's vocabulary is the schemaless way to say what a token means, and §4.1 calls it closed: "A Class 1 value
is one of three things — an untyped token, a token carrying a built-in annotation from §5's vocabulary, or a
container." Every type core.tn declares over an atom has a row: the integer ladder, `number`,
`float32`/`float64`, `rational`, `complex`, `text`, `uuid`, `bytes`, the temporal families, the network
families, `uri`, `regex`. **`boolean` has none.**

It is not an atom-family omission by accident of grouping. `boolean` is meta-kernel's own
`!enum [true false]`, and [TSON-DATA] §4.2 gives its two tokens special status *in base type resolution* —
matched ahead of the number grammar, and load-bearing for [TSON-SCHEMA] §5.4's derived `disjoint` fact. So
the notation privileges the two tokens and then offers no way to name the type they inhabit.

**Two things follow, and they are the same hole from opposite sides.** `!boolean true` is an unresolvable
annotation where `!int32 1` resolves — an author asserting the boolean case, as §5.5 says the vocabulary
exists to let them, has no name to assert it with. And a processor reading into a declared boolean host type
(#7) has no family to read the token, so the one type whose two tokens the notation names specially is the
one type a typed position cannot ask for.

**The interpretation this implementation has taken** is that the omission is an oversight: `boolean` is
registered in the built-in vocabulary, reading `true`/`false` to a host boolean and refusing anything else as
the enum-member violation it is (a validation error, as every other enum's member set gives). The two tokens
are case-sensitive and lowercase-only, per §4.2. A typed position does not consult the form, so `!boolean
"true"` and `!boolean true` are one value — §4.2's special status being a base-resolution rule, which a typed
position never reaches. **#21 disputes that last sentence** and would make the form decide, on the ground
that `boolean` is an enum and an enum's members are values; if it is adopted, the table row proposed below
reads "the unquoted tokens `true` and `false`" and this paragraph goes.

**Suggested resolution.** Add `boolean` to §5's table, in the same row group as `text`, with the parsing
contract "the tokens `true` and `false`, case-sensitive; any other token is a validation error". If the
omission is instead deliberate — on the ground that base type resolution already recovers a boolean from an
unquoted `true`, so the annotation is never *needed* — then §5 should say so, because the same argument
would remove `text` (§4.4 already recovers a string) and §5.5 explicitly keeps that one: the annotation
exists to **assert** the case where it is in doubt, which is exactly what a quoted `"true"` at a boolean
position is.

---

## 10. The discriminator belongs to a subtype family, and it is field syntax rather than an annotation

**Documents:** [TSON-SCHEMA] §6 (`@discriminator`), §5.4 (choices, `@disjoint`), §5.7 (the refinement
transition table), §5.8 (composition), §5.11 (field groups), §7.2 (subsumption); [TSON-JSON] §6.1.5
(subsumption), §8.2 (the discrimination predicate), §8.4 (discriminated choices). Also OpenAPI 3.1's
`discriminator`, which the proposal is a conversion target for.
**Kind:** design proposal, plus two underspecifications in the check list §6 does state. **All of it is
running**, in both encodings.

**What §6 already states, so that what is proposed against it is visible.** `discriminator => @annotation
field_name` stands on a **choice** declaration and names the field a member-dispatching encoding selects the
variant on. Four checks are stated: `field_name` is an `identifier`, from the annotation's own type; every
variant is a record declaring the named field; that field is `REQUIRED_FIXED` in every variant, never
`REQUIRED_DEFAULT`; and the fixed values are pairwise distinct. §6 also refuses to *derive* the field, because
"with two `REQUIRED_FIXED` fields in every variant a derivation picks arbitrarily or picks both, and either way
couples the wire to a pin the author fixed for an unrelated reason." **That refusal is right and this entry
does not reopen it** — the coupling is concrete, [TSON-JSON] §8.4 making the selected member non-elidable, so a
pin an author fixed as a version marker would silently become mandatory on the wire. What follows is a
proposal about the annotation's *target*, not about deriving it.

**The proposal: the target is a subtype family, and the spelling is a field modifier.** The mark leaves the
annotation channel entirely: the base's selector is written **`=?`**, beside §5.2's own `~` and `=`, and reads
as *pinned, but not here — the members pin it*:

```
pet      => abstract { pet_type: text =?  name: text }
dog_type => pet & { pet_type: = "dog"  breed: text }
cat_type => pet & { pet_type: = "cat"  indoor: boolean }
```

A position typed `pet` — or `[pet]` — then admits any subtype under §7.2, and the `pet_type` member recovers
which. An explicit choice `( dog_type | cat_type )` is **out of scope** and keeps the variant tag it has today.

**The reason is structural, not a simplification.** The mark is on a *field*, so it can only exist where
the position has an expected record type whose fields a decoder knows before it dispatches. At a choice position
there is no such type: recovering one would mean computing the variants' common supertype, a derivation the
language does not have and should not gain. §6's present spelling works around the absence by naming the field
on the choice instead — which is why the field name has to be written out at all, and why §6's check must speak
of "every variant ... declaring the named field": nothing in the choice framing relates the variants, so the field
may be declared independently in each, under a different type in each. Moving the mark to the base makes it one
declaration the subtypes refine, and the relation between them is what §5.8 already guarantees.

**What the shape is, in host-language terms.** A base that cannot be instantiated, a closed set of subtypes each
identified by one constant field, and a consumer that switches exhaustively over them: this is a **sealed**
**hierarchy** — Java's `sealed interface` over records, Kotlin's `sealed class`, Scala's `sealed trait`, Swift and
Rust enums with payloads, and closest of all TypeScript's discriminated union, where each member types the tag
field as a literal (`pet_type: "dog"`) and the compiler's exhaustiveness check *is* dispatch on the pin. Saying so
is not decoration: it settles two things the proposal otherwise leaves to taste.

- **The base is abstract**, because the target shape has no instance of it — a `sealed interface` is not a value.
- **The derived mapping must be total over `subtypes`**, because an exhaustive switch is sound only if every member
  of the family is reachable from the tag. That is why the check is "every subtype pins it" rather than "the pins
  that exist are distinct".

**Sealed relative to a governing schema, which is the unit that matters.** §7.2's subsumption and the derived
`subtypes` index are open *across* schemas: an importing schema may declare a further subtype. Within one governing
namespace they are not — a data document's vocabulary is its `!!schema`'s namespace, one hop (§3.3.4) — so the
family a document can name is fixed, and that is exactly the unit a code generator emits a `permits` clause from.
An importing schema adding a subtype is emitting a larger sealed hierarchy of its own, which is correct: a different
contract has a different exhaustive set, and the obligation it inherits is to pin the discriminator, enforced by the
resolver error of consequence 3.

**Five consequences, each an improvement on the present shape.**

1. **The mark becomes field syntax**, and with it the only place in the series where an annotation names a
   field by string disappears. `=` and `?` are both already in [TSON-DATA] §7.2.5's closed special-token set,
   so the frozen Class 1 lexer is untouched and §12.1 gains one alternative:
   `field-modifier = ws ("~" / "=") ws ( token / absent ) / ws "=" ws "?"`. Where the fact then *lives* is
   #11's question, and its answer is `record.discriminators` — so of the two marks §6 introduces together,
   neither survives as one: this becomes syntax and `@rest` is retired outright (#20).
2. **It is the shape converted contracts arrive in.** OpenAPI's `discriminator` sits on the *base* schema with
   subtypes composing it, which is this arrangement and not the choice one. A converted contract lands on the
   mechanism directly.
3. **The open-world argument strengthens §6's refusal to derive, and makes the mark constitutive.**
   A choice's variant list is closed and local, so a derivation over it can never be invalidated from outside.
   `subtypes` is **open** (§8.2): another schema may `!!import` `pet` and declare a third subtype. A derived
   discriminator would let that import silently flip the family from discriminated to not, breaking every
   existing producer with no diagnostic anywhere. Declared on the base, the mark is an obligation that
   propagates: a new subtype failing to pin the field is a resolver error **in the importing schema**, which is
   the schema that broke it. This makes the selector unlike `@disjoint` — it is not an assertion about a fact
   the resolver derives anyway, it is the declaration that creates the obligation.
4. **The mechanism is encoding-neutral, and both encodings claim it.** At a `pet`-typed field in text a value is
   likewise exactly `pet` without `!dog_type`, so structural recovery of the subtype is available to both
   encodings from one rule, and §6's "neither needed by TSON text" holds only for the choice framing. **In text
   the tag is not required at a member-dispatched position and must agree where it is written** — the same standing
   [TSON-JSON] §8.4 gives `$type`, so one rule serves both and the discriminator field is not a field the
   reference encoding declares and never reads. At an ABSTRACT position, which has no discriminator to dispatch
   on, the tag is required in both encodings. The dispatch mode is therefore a property of the type (#11), not a
   per-encoding latitude, and a compiler reads one enum member to know which reader to build.
5. **The hardest check becomes free.** Across a choice's variants the discriminator field's declared type must be
   *checked* uniform, because a decoder has to parse the arriving member before it knows the variant and so cannot
   let the parser depend on it — a check §6's list does not state, and which this proposal removes the need for
   rather than adds. Across a composed family the type is uniform **by construction**: it is the base's, reached
   through §5.7's elided type-ref (`pet_type: = "dog"`), and a subtype narrowing it narrows a subset. The decoder
   parses with the base's declared type, which is the one type it knows before dispatch, and the rule is total.

**The OpenAPI conversion, stated.** OpenAPI 3.1's `discriminator` is the most widely deployed polymorphic JSON
contract, and its inheritance-style usage maps onto this proposal mechanically:

| OpenAPI | TSON |
|---|---|
| `discriminator.propertyName: petType` on the base | `pet_type: text =?` on the base record |
| a subtype's `allOf: [$ref base, {…}]` | `dog_type => pet & { … }` |
| `mapping: { dog: '#/…/Dog' }` | `dog_type => pet & { pet_type: = "dog" }` |
| no `mapping` — the implicit value is the schema name | `pet_type: = "Dog"`, the name as written |
| the base abstract by convention | the base abstract by the mark |

**The mapping table evaporates into the pins**, which is the conversion's value rather than a side effect: OpenAPI's
table is a second artifact that can disagree with the schemas it names, and after conversion there is nothing left
to disagree. The direction back out is the same derivation run forwards — `mapping` is reconstructed from the pins —
so a TSON schema can serve an OpenAPI document without the table ever being authored.

Two properties make the conversion faithful rather than approximate. The pin is a **value**, so a mapping key that is
not an identifier survives (`"dog-v2"`, `"urn:acme:dog"`), where any route dispatching on the *type name* would have
to rename the wire; and the pin keeps the contract's own spelling, so `Dog` stays `"Dog"` on the wire while the TSON
type is named `dog_type` by the ordinary identifier conversion.

**Both layouts fall out of one mechanism, and a template spells the second.** The arrangement above puts a
subtype's own fields beside the discriminator — *internally tagged*, in the vocabulary serde's four shapes gave
the problem — and one base serves the *adjacently* tagged layout just as well, where each member carries the
tag and a single payload field:

```
msg    => abstract { kind: text =? }
msg_of => <T, V> msg & { kind: = T  body: V }
ping   => msg_of<"ping", ping_body>
pong   => msg_of<"pong", [pong_body]>
```

Dispatch is the same rule in both — read the discriminator, select the member, re-verify the pin as an ordinary
FIXED check — so this needs nothing added to the design; what the envelope buys is a payload that need not be a
record, `pong` carrying a list where the flat layout can only add fields. **This is running**: the family loads,
an untagged document places itself by `kind` in both encodings, the payload validates against the selected
member's own type, and a bound read yields the Java sealed hierarchy (`Channel[m=Ping[body=PingBody[seq=1]]]`).
Two things are worth stating in the prose rather than left to be discovered. The base **cannot require** the
payload field — its members have no common payload type, so nothing declarable at the base constrains them, and
`pet: top` is the candidate and #13's open question. And the template here is the subtype *factory* and never
the base: the base stays a closed declaration, which is what §5.10 requires today and what #13 proposes to
relax.

**One shape does not convert, and the gap is worth stating.** OpenAPI also admits a `discriminator` on a schema whose
composition is `oneOf` with no shared base. There is no base record for the mark to stand on, so a converter must
either synthesise one — mint `pet => abstract { pet_type: text =? }` and compose each variant onto it, making
explicit a relationship the contract left implicit — or fall back to `$type` and lose member dispatch. Synthesis is
the better answer and is mechanical, but a converter taking it should say so, since it adds a type the author did not
write. Two further contracts are refused rather than converted: a `mapping` not covering every variant (the pins must
be total, which is the drift the conversion removes), and a discriminator nested at a second level, which the
one-level rule below leaves out of the first design.

**§5.7 forces the arrangement into one shape.** The identity diagonal states that a `REQUIRED_FIXED` restatement
MUST NOT change the value, so the base cannot pin the field: were `pet` to declare `pet_type: text = "pet"`, no
subtype could pin its own value. The base therefore declares it `REQUIRED` and unpinned, and each subtype
tightens REQUIRED → REQUIRED_FIXED, which the table allows. There is no other arrangement, and the prose should
say so rather than leave an author to discover it from a transition-table error.

**The base is abstract, which #11 carries.** Because the base's field is unpinned (§5.7 forbids otherwise), `pet`
would be instantiable and its `pet_type` would admit any text, so `{ "pet_type": "dgo", … }` at a `pet` position
needs an answer. It is a validation error naming the received value and the pinned alternatives — what
[TSON-JSON] §8.4's draft already prescribes for choices — on three grounds: the target shape has no instance of
the base, a `sealed interface` not being a value; OpenAPI's bases are abstract by convention, so a converted
contract expects it; and the value of member dispatch is largely the diagnostic it produces when a producer gets
the tag wrong. The alternative was to read an unmatched value as a plain `pet`, which needs no new concept and is
usually caught downstream by record closure (§7.2) — but the diagnostic then names `breed` where the fault is one
character in `pet_type`, and a subtype adding no fields of its own is not caught at all. **The fact cannot ride
the annotation**, so taking it forces a kernel change; #11 is that change, and the base is ABSTRACT there, its
selectors named beside it.

**On the spelling, and what it costs.** `=?` is a *field* modifier because the fact is a field's: `~` says
defaulted here, `=` says pinned here, and `=?` says pinned, by the members. A declaration-level mark would
reintroduce either the field-name string this proposal removes or the derivation §6 refused. The precedent for
deriving a type-level property from a member-level mark is C++'s pure-virtual `= 0`, which makes its class
abstract without a keyword; what TSON borrows is the direction, not the symbol. **The cost is the word.**
`discriminator` is what an author converting an OpenAPI contract searches for, and punctuation is not
searchable — so the spec's index and every diagnostic should carry the word ("field `pet_type` is this
family's discriminator, written `=?`"), which is what this implementation's messages do. `?` also risks
reading as "optional", so §5.2 should state the reading outright; an optional type with `=?`
(`pet_type: text? =?`) is a resolver error, a selector that may be absent selecting nothing.

**The check list, restated for the family.** At the base: the declaration is ABSTRACT, derived from the
selector and optionally asserted, below; the
marked field's declared type resolves, after its
reference chain, to an atom-family instance or an enum — *not* free here, because §5.2 grants that only to a field
carrying a value and the base's field carries none; its state is exactly REQUIRED, neither OPTIONAL (the base
could omit it), FIXED (nothing could override it) nor DEFAULT (a document could); and it is not a group member.
Which fields carry the mark is well defined over a composed chain by §5.8's restated-field rule, which §6 states
for exactly this purpose; a family admits several marked fields, the tuple case below. Over the linked
closure: every entry in `subtypes`, transitively, pins each marked field `REQUIRED_FIXED`; and the pins are
pairwise distinct.

**`=?` implies `abstract`, and nothing states the dispatch twice.** A selector says the members pin it, so
the record is the base they are selected from and has no values of its own — a total consequence, there being
no reading in which such a record has direct instances, so ABSTRACT is derived rather than required.
`abstract` beside a selector asserts what the body says and is admitted on `@disjoint`'s terms; `final`
claims the opposite and is refused. That the family is *member*-dispatched rather than tag-dispatched is
likewise no mark: it is whether any field carries the spelling, read off the body (#11).

**What that gives up, stated rather than glossed.** An earlier draft of this entry paired the field mark with a
declaration mark so that neither could drift: removing a base's last selector would fail at the schema that
changed. Without the pair it does not — the family silently degrades to tag dispatch, the tag turns REQUIRED at
every position typed by the base, and every untagged document in the world stops validating. That is accepted:
an author deleting a discriminator is editing the family deliberately, and a redundant mark is a word written
twice on every base to catch one edit. An optional `@disjoint`-style assertion would buy the check back if the
case ever bites.

**Two underspecifications in the list §6 states today, which survive the move.**

- **"Pairwise distinct" does not say under what relation.** §5.5's value-space clause makes scale a spelling and
  [TSON-DATA] §4.3 makes `255` and `0xFF` one value, so `= 255` and `= 0xFF` are one pin and `= 1` and `= 1.0`
  are one pin, while text pins compare NFC-normalised (§7.5). An implementation comparing tokens accepts a schema
  whose dispatch table is not a function. The relation is the field type's own equality contract, and the rule
  should name it.
- **A group member can carry the mark, reachable only through refinement.** §5.11 makes the value modifiers parse
  errors on a member and flattens every member to `state: OPTIONAL`, so the source syntax cannot express it — but
  §5.11's refinement rules let an inherited member be tightened to a REQUIRED family, and explicitly contemplate
  one member of a group being there. So the check is needed and must consult the resolved `groups` list; an
  implementation reading the declaration concludes it is impossible.

**More than one discriminator field is admitted.** A record may mark several, and the checks generalise with no
special case: the pins are **pairwise distinct as tuples**, taken in the base's declaration order — well defined
precisely because the base declares the fields. A 2x2 family is then spellable, the decode is still one test (read
the marked members in any order, §6.1.6 giving member order no meaning, form the tuple, look it up), and a
combination the table does not hold is the ordinary unmatched-value error, so a partially covered matrix fails per
document rather than at load — the right place, the full cross product rarely being the intended family. What is
single-tag is the machinery built *over* it: OpenAPI carries one `propertyName`, so a multi-field family neither
converts from a contract nor to one, and a host sealed hierarchy switches on one tag where a tuple needs a record
pattern or a flattening step. Both are properties of those consumers and neither is a reason to narrow the model.

**Two consequences to state in prose rather than check.** A family discriminates **one level**: a subtype of a
subtype inherits its parent's pin and §5.7 forbids changing it, so it dispatches to its parent and relies on
§7.2 plus the encoding's tag for the rest — the same conclusion [TSON-JSON] §8.4 already reaches for subtypes of
variants. And a second selector on an intermediate type is the nesting escape hatch, which this proposal
deliberately leaves out of the first design.

**What is running:** the proposal, in both encodings, as #11 and #13 describe — `=?` on a base field, which
derives ABSTRACT and admits `abstract` as an assertion beside it, the closure checks with pins compared as
values, and member dispatch at such a position. Neither `@discriminator` nor `@sealed` is known to the
resolver at all: meta.tn declares neither, so either name written in a schema is the ordinary
unknown-annotation error (§3.3.3). The
group-member check is now two rules: §5.11 makes a value modifier a parse error on a member, so the spelling
cannot reach one, and the linker still refuses a member that acquires the mark by refinement. The [TSON-JSON]
half of the change is made: §6.1.5 now reads the untagged object by the position's own extension fact, §8.2 is
one condition rather than two, §8.4 states why a choice has no discriminator, and §1.6 records the Part 2
dependency as proposed rather than landed.

**Suggested resolution.** Retire §6's `@discriminator` and spell the selector in §12.1 instead — one
`field-modifier` alternative, `ws "=" ws "?"`, needing no lexical change — with the check list above and the
§5.7 arrangement stated; keep the word "discriminator" in the prose and the index, since the symbol is not
searchable; name the equality relation for pin distinctness and add the group-member check; state that `=?`
implies `abstract`, and say what the shape is for — a sealed hierarchy in a host language — since that is what makes
totality
and abstractness rules rather than preferences. Leave §5.4 untouched but add a pointer from it, since an author
reaching for member dispatch at a choice is an author who wants the composed family or the labelled form of §5.11.

---

## 11. A record cannot say how it may be realised, and the discriminated base is the case that forces it

**Documents:** [TSON-SCHEMA] §4.1 (base kinds), §5.2, §5.4 (`disjoint` as a derived-and-recorded fact), §5.7, §5.8
(composition), §5.9 (removal), §5.10.1 (productivity and inhabitance), §6 (what an annotation may do), §7.2
(subsumption), §8.1 (resolved output), §8.2 (identity), §12.1 (the schema grammar); [TSON-JSON] §6.1.5, §8.4.
Reads with #10, and is useful without it.
**Kind:** design proposal — two kernel fields, the enum one of them takes, and the grammar slot the author
writes it with. **All of it is running.**

**What forces it.** §6 gives the criterion for annotation-hood in its own words: "a schema with every annotation
erased admits exactly the same values." Run that erasure separately on the two halves of #10's mark and they come
apart.

*Member dispatch* fails it too, and the escape §6 offers has run out. The representation-directive bullet
licenses a mark whose force is "confined to the encodings that claim it" — "a document in a directed encoding may
not be readable without it; in every other encoding, and in the model, it changes nothing." That held while text
kept `!variant` at a discriminated position. It does not hold now that text reads a sealed position by the member
too ([TSON-JSON] §6.1.5): with every encoding claiming the directive there is no *other* encoding left for it to
change nothing in, and erasing the mark makes a document invalid wherever it is written. A mark that survives no
erasure in any encoding is not distinguishable from one that changes the model, whatever it does to the value
space.

*Instantiability is not readability.* Erase a mark that forbids direct instances and `pet` has instances again, in
every encoding and in the model: `{ "pet_type": "dgo", "name": "rex" }` stops being an error and becomes a valid
`pet`. That is the type's inhabitance changing, which no directive licenses and no encoding owns. A mark that
forbids *subtypes* falls to the same test from the other side: erase it and a position typed by that record admits
values it did not admit. **So the decision that an unmatched discriminator value MUST fail is the decision that
puts a fact in the kernel**, and nothing short of it does.

**A finding that stands on its own.** §6 claims `@discriminator` changes no value's validity, and argues it by
writing "a discriminated choice admits exactly the variants it admitted" — *variants*, where the criterion it is
answering is about *values*. Erasing the mark invalidates every untagged document at that
position, and once text dispatches on the member too there is no encoding in which it does not. The sentence
should be deleted rather than repaired: the mark's force is not in either of the two categories §6 admits, which
is the finding, and the fact belongs in the kernel where erasure cannot reach it.

**The proposal.** `record` gains two fields and the kernel one enum:

```
definition-mark = "abstract" / "final"

schema-map-entry = *( annotation ws ) type-name ws "=>" ws
                   *( annotation ws ) [ definition-mark ws ] type-def

record_extension_type => [ABSTRACT FINAL OPEN]

record => product & {
  access_pattern:  product_access_type = NAMED
  size_type:       product_size_type = FIXED
  fields:          [record_field]
  groups:          [field_group]?
  extension:       record_extension_type ~ OPEN
  discriminators:  [field_name]?
  supertypes:      [type_name]?
}

record_field => {
  name:           field_name
  type:           type_ref
  state:          field_state ~ REQUIRED
  value:          value?
}
```

**`record.discriminators` and not an annotation, on three arguments that converge.** The erasure test above
is the first. The second is compilation: a discriminator field is read as a dispatch key rather than as an
ordinary field, and is non-elidable on the wire, in *both* encodings — a property of the field in the type
system, not of one encoding's projection of it. The third is the one the design forces on itself: whether a
position is placed by its members is read off `discriminators`, so with the names in the annotation channel a
kernel-level reading would depend on it. Beyond the layering inversion, §6 resolves an annotation one hop
against the governing meta — so a schema whose meta did not declare the name could never dispatch on members at
all, making a kernel-level fact depend on which meta-schema governs the document. With the names in the body
the reading is of the body alone, and every schema can express it exactly as every schema can already express
`state` and `value`.

**`@rest` was the other mark §6 introduced, and it does not survive either.** The erasure test separates the
two — erase `@rest` and the text and CBOR encodings are untouched, so its force really was confined to the
encodings claiming it — but a directive whose class is "the encodings that claim it" defines nothing, and the
fact it carried was a wire spelling rather than one the model holds. #20 retires it rather than relocating it.

**The three members, and the fact that is not one of them.**

- **OPEN** — direct instances, and any schema in the closure may compose or refine onto it. Every record's
  behaviour today, and the default.
- **ABSTRACT** — no direct instances: no value's effective type is this record, and a position typed by it
  admits exactly its subtypes.
- **FINAL** — direct instances, and nothing may be a subtype: composition or refinement naming it is a resolver
  error, in the declaring schema and in any schema that imports it.

**How a subtype is selected is `discriminators`, not a fourth member.** An ABSTRACT record naming none is
selected by the tag (`$type` in JSON, `!dog_type` in text), which is therefore REQUIRED at the position; one
naming selectors is selected by reading them, and **the tag is then optional and MUST agree where written** —
in both encodings, the standing [TSON-JSON] §8.4 already gives `$type`. An earlier draft spent an enum member
(SEALED) on this. It said a second time what the body carries, which is the one arrangement in which the two
can disagree; the derivation is `choice.disjoint`'s shape and is read where it is needed.

**Two marks in, two kernel fields out.** The author writes exactly one of the words `abstract` and `final`
between `=>` and the type definition (`pet => abstract { … }`), or neither, and `=?` on each field the members
pin. The mark names its member and `extension` is a function of which was written — OPEN where none was; the
field spelling fills `discriminators`. One optional slot rather than two flags makes two marks on one
declaration *ungrammatical*, which is a rule the resolver no longer has to state or diagnose.

**The mark is grammar rather than an annotation, on §6's own criterion.** §6 fixes the home of a fact in as
many words — "an annotation is the right home exactly when the mark changes no value's validity" — and both
marks change it. Erase `abstract` and a direct instance becomes readable at every position typed by the record;
erase `final` and an importing schema may compose a subtype whose values that position then admits. The
annotation spelling an earlier draft used had to be carved out of §6 as an exception, and an exception to the
criterion §6 exists to state is a sign the fact is in the wrong channel rather than a sign the criterion needs
softening. Nothing about the kernel changes: `record.extension` was always the carrier, and the marks never
survived into §8.1's annotation channel. What changes is that the surface now agrees with the model.

**The words are marks at one position and ordinary identifiers everywhere else, so [TSON-DATA] §7.4 stands.**
"No reserved words. The grammar excludes nothing by name" holds unchanged: `abstract => { … }` still declares a
type of that name, `f: abstract` still references it, `abstract` is still available as an annotation name in a
meta that declares one. The sole reservation is that a type so named cannot be written as a *whole declaration
body* — `pet => abstract` is a declaration missing its definition. Reading the word unconditionally is what
buys that narrowness: a marked composition (`mid => abstract base & { … }`) and an alias differ only in what
follows the head, so a conditional reading would have to give one of them up, and an abstract link in a chain is
worth more than an alias to a type called `abstract`. §12.2's disambiguation summary gains the one line.

**Why a word and not a character.** `=?` earned its terseness by density — a field modifier is written on every
field, so the reader meets it constantly and the cost of learning it is paid once. A definition mark is written
once per family and appears nowhere in the meta-kernel, meta or core. The survey argues the same way: every
language that names this axis names it with words (`abstract`/`final`, `sealed`/`non-sealed`, Kotlin's
`open`/`abstract`/`final`), and the closed special-token set ([TSON-DATA] §7.2.5) has no character left that is
not already spoken for. Taking one back from §7.2.6's deliberately-unused list would spend the value of that
list being absolute on a mark used this rarely.

**The marks are stated and the selectors are derived, and the split is not arbitrary.** Instantiability is not
a fact of any field: nothing in an ordinary body says whether the author meant this record to have values of its
own, so `abstract` is written — except where a selector settles it, which is the one body that does say
(below). How the members are then *selected* is a fact of the body — whether any field carries the spelling —
so deriving it adds no claim an author could have made differently.

`@disjoint` is the nearest precedent for the derived half and the analogy is close: `disjoint` is computed from
the variants, and member dispatch is computed from the selectors. What differs is that `@disjoint` has an
optional assertion spelling and this has none, which is the redundancy #10 records as given up.

**A template may be abstract and may not be final, and the asymmetry is the marks' own.** §5.10 makes
a template not a type: only an application is, and each application mints its own entry. ABSTRACT constrains the
marked type alone — no direct instances — which is true of every instantiation identically and needs nothing else
known, so `result => abstract <T> { … }` with `ok => <T> result<T> & { … }` is meaningful and is the shape a host
language spells `abstract class Result<T>`. **This is running.** §5.10 holds an open entry's body as the
application written out, so the mark is stated inside that text and materialisation reads it back through the
`record` constructor's own reader: closing `result<text>` yields an ABSTRACT entry, and the family it is abstract
over is the one §5.8's reference-valued `supertypes` builds — `ok<text>` is a member of `result<text>`'s and not
of `result<int32>`'s. The mark is written **before** the parameter list, the slot sitting
between `=>` and the type-def that the parameters open. FINAL is a claim about *other* declarations —
that nothing composes onto this one — and a template has no set for such a claim to range over. `subtypes` is an index
over entries (§8.2), and an instantiation entry exists only where some schema writes
that application, so the claim's subject would be assembled from whichever applications a closure happens to
contain: `ok<T>` composing onto `result<T>` puts nothing in `result<text>`'s index unless someone also writes
`ok<text>`, and writing it in a fourth schema would change the family without touching its declaration. FINAL
therefore stays a resolver error on a template, and for the sharper reason #13 gives rather than the
set-membership one above: every application is a subtype of the base by construction, so the mark would forbid
the applications it exists alongside. **Member dispatch reaches a template, and #13 settles how**: a family
whose members *are* the applications is one family with one index, and its selectors are derived — a `=?`
field, or one pinned to a value parameter — under one condition, that a selector's declared type contain no
type parameter.

The disanalogy with the host language is worth stating, because the host language is where the intuition comes
from: Java's `sealed abstract class Result<T> permits Ok, Err` has one class carrying one permits list, and its
sealing is over classes rather than parameterisations. A TSON template has no such single carrier — there is no
entry for "the generic type", only one per application — which is the same fact from the other end.

**Why the enum carries instantiability and nothing else.** The two reading rules — tag required, or selectors
read and the tag optional — differ by whether `discriminators` is empty, which a compiler reads once when the
schema compiles and turns into the reader it builds. A member for it would be a second copy of that answer.
The combination the underlying questions admit but no member spells, no direct instances and no subtypes, is
therefore unrepresentable, which is §5.4's move with `disjoint` and not a rule a document could break.

**`~ OPEN` is the required state, not the optional one.** REQUIRED_DEFAULT injects (§5.2), so a consumer of
resolved output never meets an absent `extension` — the guarantee that asking for a required field is asking for
— while §8.1 omits a field at its default and the overwhelming majority of records, being OPEN, carry nothing.
An optional field is what this avoids: absence would be a second spelling of OPEN. `groups?` is the shape
precedent for the rest — a record-level fact carried in the body, which source syntax lowers into rather than the
author writing the constructor form.

**Subtraction is unaffected by FINAL, and the reason is worth stating** because it reads like an exception and is
not one. §5.9's removal clause empties the resulting entry's `supertypes`: the product of a subtraction is not a
subtype of its source and cannot stand at its positions. FINAL constrains the IS-A set, which subtraction never
joins, so `-` off a FINAL record is admissible and yields an unrelated type that happens to share a field list.
The same reasoning admits nothing else: composition and refinement both mint an IS-A edge and are both refused.

**FINAL says what `subtypes` cannot.** The derived `subtypes` index (§8.2) distinguishes no subtypes *here* from
no subtypes *ever*, because another schema may always import and extend. FINAL is that distinction, and it is the
fact a consumer needs before it treats a record as a leaf. It is also what a host generator needs: Java requires
every permitted subtype of a `sealed` interface to declare `final`, `sealed` or `non-sealed`, so a generator
emitting #10's hierarchy has to know which of those a leaf is and today has nowhere to read it from.

**Inhabitance must be left alone, and the tempting rule is wrong.** It reads well: a FINAL or OPEN record is
inhabited as any record is, an ABSTRACT one exactly when one of its subtypes is, and §5.10.1's least
fixed point then rejects an abstract base with no subtypes as it rejects any other uninhabited entry. **Do not
adopt it.** The case it refuses is the one an abstract base most exists for: a library schema declaring
`response => abstract { … }` and a field typed `response`, with every subtype supplied by the schemas that
import it. §3.3.4 makes `subtypes` open across schemas, so that family is empty in the declaring schema's own
closure and complete in each consumer's — and it is the *consumer's* documents that are written, never the
library's. Rejecting at load would make the library unpublishable for having deferred exactly what it meant to
defer.

So the rule stays as it is and `extension` adds no case to it. What an empty family gets instead is a
diagnostic at the position, when a document reaches one: **running**, and it names the remedy — no schema in
this closure declares a subtype of the base, so the schema that does is missing from the imports. What it
replaced offered "one of ()", an empty list presented as a choice, which is an instruction no sender can
follow.

**The member is never inherited, and there is no transition table.** A subtype states its own: `dog_type => pet &
{ … }` is OPEN by default whether `pet` is ABSTRACT or OPEN, and it must be — otherwise no concrete subtype of an
abstract base could exist, which is every subtype there is. §5.7's transition table governs *field states*, whose
values a refinement inherits and may only tighten; `extension` is a property of the declaration and nothing
propagates it. The whole of the rule is two lines: composing or refining onto a FINAL record is a resolver error,
and every other declaration states its own member, defaulting to OPEN.

**Identity and output.** `extension` participates in §8.2 identity — an abstract `pet`, a sealed one, a concrete
one and a final one admit different values and are different types — and in resolved output follows §8.1's
convention, omitted at its default.

**On the member names.** ABSTRACT and FINAL are Java's words in Java's senses: `abstract class` and `final`.
**C# spells FINAL's concept `sealed`**, so those two words collide across major languages; this picks one
language and uses it throughout rather than splitting the difference — one reason less to want a SEALED member,
the word meaning the opposite thing to half the audience. "Sealed family" survives as prose for the shape #10
describes, with its caveat: the family is sealed relative to a governing namespace and extensible by an
importing schema under the pinning obligation.

**The naming left open — the field and the enum type.** Proposed: `extension: record_extension_type ~ OPEN`,
matching `access_pattern: product_access_type` in shape (a short field name beside an `<owner>_<concept>_type`
enum). "Extension" covers all three members: ABSTRACT makes extension mandatory, OPEN permits it, FINAL
forbids it. Considered and not taken: `extensibility`, the same
sense but reading badly under the `_type` suffix; `derivation`, accurate but less current in the surrounding
vocabulary; `instantiation`, which names the ABSTRACT axis and says nothing about FINAL; and `record_kind`,
refused outright because §4.1 has already given "kind" to the four base kinds.

**All three spellings are §12.1 syntax, and the layer split that forced it is closed.** The selector is `=?`
(#10) and the definition marks are the words `abstract` and `final` at the type-def head. The annotation
spelling both marks passed through was legitimate only because the resolver *consumed* them — a mark that
lowers into the type is syntax wearing annotation clothing, and one left preserved in §8.1's author-annotation
channel would have been the erasure violation twice over. But consumption is also what made the arrangement
temporary: a construct the resolver reads, that is absent from output, and that no schema may redefine is a
construct §12.1 should spell. It now does.

What the move fixes beyond tidiness is a split across two layers. `record_extension_type` and the two fields
are the kernel's, since the body they sit in is; the marks were declared in the meta-schema, on `@doc`'s
reachability terms, so the enum a mark named lived one layer below the mark. Nothing broke — an annotation
resolves one hop against the governing meta (§3.3.3) and the meta imports the kernel — but a reader was
entitled to ask why a kernel fact was spelled by a meta-schema name, and worse, a schema whose meta omitted the
name could not have stated the fact at all. Grammar answers to no namespace, so the question stops arising.
The meta-schema's two annotation declarations went with the spelling they served.

**What is running:** the two kernel fields, the grammar slot and its two words, the field spelling, and the
lowering. The meta-schema declares neither name, so either written as an annotation is §3.3.3's ordinary
unresolved-name error — the footing `@sealed` and `@discriminator` are already on.
`record_extension_type => !enum [ABSTRACT FINAL OPEN]`, `record.extension: record_extension_type ~ OPEN`
and `record.discriminators: [field_name]?` are in this implementation's meta-kernel and bound by its value
model. meta.tn declares `abstract` and `final`, both `@annotation void`, and declares neither `sealed` nor
`discriminator` -- which is the whole of their removal: an annotation resolves one hop against the governing
meta (§3.3.3), so a schema writing either gets the unknown-name error any other undeclared annotation gets. **The
resolver consumes both marks into the body** — the definition mark once, after the body is built, so
a fresh record, a composition and a refinement cannot disagree about it, and both annotation positions lower
identically. They are matched by name and never resolved against the governing meta, which is what reserves
them: they lower under a meta declaring neither, where an ordinary unknown name is the author's error. `=?` is
never an annotation at all — it is §12.1 syntax, and `FieldModifiers` lowers it into `discriminators` while
leaving the field REQUIRED and unpinned. Refused while lowering: two definition marks on one declaration, a
mark carrying a value, a definition mark on a non-record, `=?` on an optional field, and `final` on a record
carrying one. **ABSTRACT is derived where a selector is present** (`withExtension`), `abstract` written
beside one being an assertion that agrees rather than a requirement. A template carrying a mark is lowered
into its held body and read back when that body closes (#13), `final` alone being refused there.

**The load-time checks run too**, one pass in the linker beside the disjointness derivation: nothing may compose
or refine onto a FINAL record while §5.9 subtraction stays admissible; a sealed record's selectors are REQUIRED,
no group member, and typed by an atom or an enum; every member of the closure pins each selector `REQUIRED_FIXED`;
and the pins are pairwise distinct as tuples, compared as *values* — `= 255` and `= 0xFF` collide, and so do `= 1`
and `= 1.0`. A family is re-judged whenever any part of it is local, so an importer adding an unpinned member, or
one colliding with an imported pin, is refused by the schema that added it.

**JSON reads a family**, which is the half this design exists for: `record.extension` picks the reader when the
schema compiles, so an ABSTRACT position naming no selector requires its tag and decodes no member, one naming
selectors places the value by reading them, and OPEN and FINAL share the concrete reader — a FINAL record's admissible
tag set is
empty by construction rather than by a check. The mapping is derived once at compile time and keyed by what the
pins compare as, so a schema pinning `= 0xFF` selects on a document writing `255`; a table keyed on tokens reads
that as unmatched. The selected member then re-reads the whole object, which re-verifies the pin as an ordinary
FIXED check and makes the dispatch read and the validation read agree by construction.

**The text encoding reads one too**, on the same terms and from the same rules: one dispatcher per position for
both read modes, the discriminator fields found by a rewinding lookahead because a record's fields have no
significant order, and the four refusals taken from the one `RecordExtensionDiagnostics` both stacks hold. A
cross-encoding parity test compares code, data pointer, `expected` and prose for every rule they share, and
passes — which is the evidence §9.4 asks for and not merely a claim that two readers were written from one
design. §8.2 identity does not carry `extension`, and needs not: only a template instantiation can mint an
entry holding a non-OPEN one — no synthetic is ever a record — and an instantiation is named from the
template and its arguments, never from the closed record's fields, so one template contributes one
`extension` and two entries differing only in that member cannot be minted. Measured, now that #13 admits the
mark on a template: `a => abstract <T> { v: T }` and `b => <T> { v: T }` applied to `text` mint
`a_text_d07d3ec8` (ABSTRACT) and `b_text_bf004549` (OPEN) — distinct because their heads are, not because the
member is weighed. The collision the rule prevents therefore stays unreachable, for this reason rather than
for the earlier one that a mark on a template is refused. The kernel's own three schemas resolve, link and
compile unchanged — every record OPEN, none naming a discriminator — which is the evidence that the fields
cost nothing where nothing uses them.

**Suggested resolution.** Add `extension: record_extension_type ~ OPEN` over `[ABSTRACT FINAL OPEN]` to
the kernel's `record`, and `discriminators: [field_name]?` to `record` and `template` (#13), stating the three
members' meanings; state that **how a subtype is selected is `discriminators` and not a member of the enum** —
tag where it is empty, the marked fields where it is not — and that the two reading rules follow from it;
state that a selector implies ABSTRACT, that the mark beside one is an admitted assertion and `final` a
refusal, and that **a family is open across schemas** (§3.3.4) — so a host language's own closed-set
construct, generated from one, is relative to a closure and not a promise the schema makes;
state #10's checks over the selector, the FINAL check over composition and refinement, the subtraction
exemption and why it is not one, the reason inhabitance gains no case for it, the absence of any transition
table, and the identity consequence; state that a template may be abstract and may not be final, with #13's
reasons; state that the marks are consumed rather than preserved and that their names are
reserved; correct
§6's validity claim; and settle the field and enum names, which is the
one part this entry does not.

---

## 12. §5.8's name-level supertype edge cannot place a closed subtype application, and `[type_name]` cannot carry one

**Documents:** [TSON-SCHEMA] §5.8 ("Parameterized references", and the *Resolution* paragraph under it), §5.9
(removal), §5.10 (a template is not a type), §7.2 (subsumption), §8.1 (`record.supertypes`,
`type_definition.supertypes`), §8.2 (identity of a minted instantiation).
**Kind:** defect in a stated rule, plus the one-word kernel change that fixes it. The design is built and
running.

**The rule as stated.** §5.8's *Parameterized references* paragraph says:

> The `supertypes` lists record the head names only (`[customer box]`) — they are name-level IS-A indexes
> (§8.1) — while the applied form, arguments included, lives in the entry's `source` and in the absorbed
> fields, which carry the parameters through ordinary type channels. Parameterized substitutability is
> therefore a two-part check: the name-level edge via `supertypes`, and binding agreement via the bodies.

**Why the head name cannot do the work.** Take the shape the rule is written for — a base template with
subtype templates over it, which is how a generic result or response type is written:

```
result => <T> { payload: T }
ok     => <T> result<T> & { note: text }
err    => <T> result<T> & { reason: text }
holder => { r: result<text> }
```

The position at `holder.r` is typed by the entry `result<text>` materialises to, because §5.10 makes a
template no type and §8.3 flattens the use site onto the instantiation. So the question §7.2 asks at that
position is *which entries are IS-A this instantiation*. A head name answers it twice over wrongly: `result`
is a name no position is ever typed by, so the edge points at nothing the check is asking about; and it holds
of every instantiation at once, so it cannot tell `ok<text>` — which belongs there — from `ok<int32>`, which
does not. The second half of the rule, "binding agreement via the bodies", is what would have to carry the
whole decision, at every position, by comparing argument lists at read time; and it is unimplementable at the
one place it matters, because by the time both sides are closed neither body mentions an argument list any
more. Both are ordinary records with substituted field types.

**And `[type_name]` cannot hold the alternative.** The edge that is wanted is to `result<text>`, which is an
application while the subtype template is open, and §8.1 types both supertype lists `[type_name]`. So the
information has nowhere to live: this implementation dropped the parent at `ok`'s declaration, where the
composition is flattened, and had nothing left to substitute when `ok<text>` closed. Both instantiations
resolved with empty `supertypes` and empty `subtypes` — two unrelated entries — and a document tagging an
`ok<text>` at a `result<text>` position was told the position's type "has no subtypes", one line after the
author declared two.

**What is running.** One kernel change: `record.supertypes` is typed `[type_ref]` rather than `[type_name]`.
That is the whole of it, and everything else follows from what a reference channel already gets.

- **The body keeps the application.** `ok`'s held body is `!record { supertypes: [ result<T> ] fields: [ … ] }`
  where before it was the flattened field list alone.
- **Substitution and closing reach it for free**, being the same walk that closes an application in a field
  slot: `result<T>` becomes `result<text>` and then the entry that mints, in the pass that closes the rest of
  the body. Reference validation and arity checking reach it on the same terms.
- **`type_definition.supertypes` is unchanged and stays `[type_name]`.** It is the derived transitive index,
  computed once every parent is a type, and a template never is. The closed parent's name and its own chain
  are folded into it when the instantiation closes.
- **A closed supertype writes as a bare token**, since an argumentless `type_ref` has that spelling
  everywhere. So resolved output for every schema that has no open parent is byte-identical to what
  `[type_name]` produced, the kernel's own three schemas included.

`ok<text>` is now IS-A `result<text>` and not `result<int32>`; the base instantiation indexes both subtype
instantiations; a chain of three accumulates; and a concrete ancestor of the base still arrives beside the
closed application.

**§5.9 is the one interaction.** Subtraction revokes IS-A for every parent while the body keeps its named
lineage, and a name kept there is inert. An *application* kept there is not — it would close into a live edge
one pass later — so a removal drops it rather than keeping it, and the closed entry gets the empty contract
§5.9 requires. That also settles how a resolver tells the two apart after closing, when an application has
been reduced to a bare name and looks like any other lineage entry: it reads which parents were applications
off the held body, whose elements are index-aligned with the closed ones.

**The identity rule needs no change.** §8.2 keys a minted instantiation on the application recorded in
`source`, so the parent moving into the body does not move the name; and two instantiations of one template
with different parents cannot arise, the parent being the template's and not the argument's.

**Suggested resolution.** Type `record.supertypes` `[type_ref]` in §8.1's kernel listing, and replace §5.8's
*Parameterized references* paragraph: a supertype is recorded as a type-ref, which carries arguments while the
composing declaration is open and is substituted and closed with the rest of its held body (§5.10); the
derived `type_definition.supertypes` records names, being computed once every parent is a type. State that
the edge a closed application mints is to the instantiation its own arguments name, so §7.2 needs no
second check and no comparison of bodies — the name-level index answers on its own, which is what every
other position already relies on. State the §5.9 interaction: a removal drops an open application from the
lineage it keeps for names, because the two behave differently once the entry closes. Drop the "two-part
check" sentence; there is no second part left for it to name.

---

## 13. A record-bodied template is the family base, and that is the position a template can safely fill

**Documents:** [TSON-SCHEMA] §5.10 (a template is not a type, and the closed-entry rule), §1.3 (what a
resolved-output consumer must support), §5.2 (`record.extension`, `record.discriminators`), §5.4, §5.7 (the
identity diagonal), §5.8 (composition), §7.2 (subsumption), §8.1 (resolved output), §8.2 (identity and
internal names), §3.3.1 (namespaces), §3.3.4 (`subtypes` open across schemas); [TSON-JSON] §6.1.5, §8.2, §8.4.
Reads with #10 and #11, and **corrects an argument #11 makes**.
**Kind:** design report — a body-shape boundary, two levels of one field, a kernel member moved, and one
§1.3 amendment. All of it is running.

**The asymmetry that showed the line was in the wrong place.** Two declarations differing in one mark got
opposite verdicts, and neither verdict was the useful one:

```
pet => @sealed   <N, T> { @discriminator type: text = N  pet: T }    ; refused at load
pet => abstract <N, T> {               type: text = N  pet: T }    ; loaded clean, and was inert
dog => pet<"dog", dog_type> & { … }
```

(Written in the marks of the day; both spell `pet => <N, T> { type: text = N  pet: T }` now, the selector
being derived from the parametric pin.) The first was refused because "`@sealed` states a closed set of
subtypes, which a template has none of". The second loaded and could not be used: no position could be typed `pet` at
all, so `holder => { a: pet }` was
refused by the same rule that refuses `holder => { a: box }`. One mark was refused loudly and the other was
accepted and useless. Both now do what the author meant.

**Why "a template is not a type" is right for `box<T>` and wrong for a base.** The rule's force is about
*elimination*: a position typed `box` would have to read a value against an element type nothing has fixed,
leaving a reader to infer arguments from the payload — not underspecified but ill-defined, since `[]`
determines nothing and two instantiations can accept one document. **None of that reaches an ABSTRACT or
family base, because no value is ever read against it.** A value there is a value of some member, selected by
a tag or by reading the selector fields, and every member is a closed entry with
every argument already fixed. The existential is eliminated by dispatch, never by inference. This is the
host-language shape the marks were taken from: `Pet<?>` is a declarable variable type and `new Pet<T>()` is
not, and §5.2 already forbids the second for an abstract record.

**The resolution: the template *is* the base.** An earlier draft of this entry had the resolver mint a
separate closed entry for the base, so that resolved output never named a template at a position. That is
**withdrawn**. The template is already an entry with a name, a source position and a place in the schema map,
and minting a second one beside it costs three things that were measured: a binder and a diagnostic get a
content-derived name where the author wrote `pet`; `subtypes` lives on the template or on the minted entry
depending on whether some position happens to name it, so the index stops being a function of the schema; and
the base's own entry is an artifact nothing else references. So a template carrying `extension` takes part in
the IS-A chain directly — it is what a type position names, what `subtypes` indexes, and what a host sealed
type binds to.

**The condition: the pre-dispatch contract must be parameter-free.** A decoder at a member-dispatched position parses the
discriminator fields *before* it knows the member, so their declared types cannot be parameters — while their
pins are exactly what the parameters supply. `type: text = N` satisfies this: the declared type is `text` and
only the pinned value is parametric. `pet: T` is unconstrained, because nothing reads it until the member is
selected and the member's own entry types it concretely. A discriminator whose declared type *is* a parameter
is refused. ABSTRACT needs no condition at all: the tag comes from the document and the base contributes
nothing to the read.

**Which templates have a base, and why the body shape is the boundary.** Only a **record**-bodied one. The
other shapes each fail for a reason the series already enforces:

- **A reference template** — `id => <T> T`, or §5.10's partial application `uuid_pair => <B> pair<uuid, B>`.
  A reference is indirection rather than a type of its own, so there is no contract for a base to hold.
  `id<text>` *is* `text`, so a base over its applications would be a supertype of every type ever applied to
  it: the universal type the series removed when `unknown` went ([TSON-JSON] §8.5). And `uuid_pair<text>` and
  `pair<uuid, text>` resolve to *one* entry, so `uuid_pair` would be a filter over another template's family
  rather than a base of its own — a host language spells that as a wildcard use (`Pair<UUID, ?>`), never as a
  named supertype.
- **A container template** — `arr => <T> [T]`, `<K, V> {K => V}`. Abstractness has nowhere to live:
  `record.extension` is the only such field, which §5.2 says in as many words, so `abstract <T> [T]` is
  refused with "only a record states how it may be realised". Putting `extension` on array, map and tuple
  bodies is a kernel change well past this entry, and it would buy a base whose members no host type
  distinguishes.
- **A constructor-application template** — `vector => <T, N> !array { … min_items: N  max_items: N }`.
  `vector<float32, 3>` and `vector<float32, 4>` differ only in a bound and erase to one host type, so the base
  has no host counterpart and a bound reader could not place a value within the family.
- **An atom template** — the question cannot arise: §12.1 refuses a parameterised atom refinement outright.

So the boundary is **not** "partially typed" against fully typed. A type parameter in a *field* is no
obstacle, which is what the labelled sum shows. What a base needs is a record body: fields to carry, an
`extension` to state, and members something can select — a discriminator, or a tag naming an entry.

**Two levels of one field, and they are read from different places.** A record template has a base *and*
instantiations, and conflating their `extension` is what made this entry's earlier drafts read as though one
mark governed both.

- **The template's own `extension` is the base's**, and is always ABSTRACT: never OPEN (nothing can write a
  value whose type is `pet` rather than some `pet<…>`) and never FINAL (its applications are subtypes by
  construction). Beside it, `template.discriminators` holds whichever selectors survive erasure, which is what
  makes the base member-dispatched rather than tag-dispatched. Both are derived in the manner of
  `choice.disjoint`: there is no claim for an author to state here and so nothing for a stated mark to
  contradict.
- **An instantiation's `extension` is stated**, by `abstract` *inside* the held text, and is OPEN unless the
  template says otherwise. That is how a **second-level** base is spelled: `result<text>` abstract over
  `ok<text>` and `err<text>`.

The two shapes this entry concerns use the levels differently, which is why one word cannot serve both. A
labelled sum's members *are* its instantiations, so they must stay concrete and the mark is simply not
written. `result`/`ok`/`err` puts a base at every argument, so the mark is exactly what makes `result<text>`
abstract. Measured: marked, `result<text>` closes ABSTRACT and a bare `{ payload: … }` there is refused,
naming the members; unmarked, it closes OPEN and the same document is accepted. **The selectors do not travel
to a member and ABSTRACT does**, which is not an inconsistency but the two facts' own scopes: ABSTRACT
constrains the marked type alone and holds of every application identically, where the selectors say *this
record's members are placed by reading these fields* — and closing has just pinned them, so a member naming
them would claim a family of its own that it does not have.

**What the base carries: whatever survives erasure.** Drop a value parameter's pin and the field stays
(`type: text = N` becomes `type: text`); omit a field whose type mentions a type parameter (`value: V`), since
nothing before dispatch reads it and the member's own entry types it. A base may therefore be **empty** —
`abstract { }` is legal and dispatches by tag — so erasure governs what a base holds rather than whether it
exists. Member dispatch is what has a floor: at least one selector must survive erasure, which is the
condition above, and a base claiming dispatch with none is refused for precisely that reason.

Faithful erasure is not required, and that is the point. The natural objection is that `<N, T> { type: text =
N  pet: T }` has no erasure: drop the parameters and `pet:` has no type. It needs none. What a base must carry
is the part read before dispatch plus the fact that it has no instances, and both survive. A base whose field
list is smaller than its members' is the ordinary case for an abstract record; a base whose field list omits
what it cannot type is the same thing one step further.

**Which fields dispatch is the record's own statement, not the field's.** The field spelling `=?` (#10)
lowers into `record.discriminators` on the enclosing record and `template.discriminators` on a template, and
`record_field.discriminator` is gone. §5.8 is the reason: it flattens a base's fields into every member, so a
per-field carrier arrives on each subtype's copy of the selector and has to be cleared there, where a member's
own declaration simply names none. The list is also what makes the next paragraph possible.

**The §1.3 amendment, which is the one thing this entry asks the spec to change.** §1.3 promises that a
consumer ingesting only resolved schema values "is fully conforming with no support for templates or
parameters … since every entry a data document's type can reach is closed by the closed-entry rule". A type
position naming a family base reaches a template entry, so the sentence is no longer literally true and should
say what such a consumer must do: **name a template entry at a type position and dispatch on its `extension`
and `discriminators`, and never read its held body.** Both halves matter. The first is the real change — the
closed-entry rule now has one deliberate exception, and it is safe for the elimination reason above. The
second is why the discriminators were moved onto the entry at all: the selector *names* used to live only in
the held body's text, which is `tson-compiler`'s to parse, so an encoding that depends on the schema
pipeline's output and never on its engine could not dispatch a sealed template family. Stating them
structurally means neither stack parses anything, and the promise that a resolved-output consumer never reads
a template's body holds exactly. This is the one spec change here that a conforming consumer can observe.

**Subtyping, and why the checks are #10's unchanged.** The edge runs from the member to the base, read off the
head of the application the member's body records, so `subtypes` of the base is its members directly — the
same flat index a hand-written family produces, and the same one §7.2 and every dispatcher already consult.
The sealed checks then read exactly as they do for hand-written members: every member pins each discriminator
`REQUIRED_FIXED` — automatic here, the pin being the argument — and the pins are pairwise distinct as tuples,
compared as values. An importing schema writing a further application is judged where it writes it, which is
#10's propagating obligation working unchanged. An empty family stays a read-time diagnostic and never a load
error, on #11's own reasoning about library schemas: a base whose importers supply the members has no dispatch
table, and a value there is refused for having no member to be.

**Where the arguments go, and why nothing is minted for them.** In `dog => pet<"dog", dog_type> & { … }` the
arguments are the *member's* own contribution and not a shared type's: `N = "dog"` fills `type: text = N` and
becomes a `REQUIRED_FIXED` pin in `dog`'s flattened field list, and `T = dog_type` fills `pet: T` and becomes
`dog`'s own field. Substituted where it stands, the declaration is exactly the hand-written member `dog => pet
& { type: = "dog"  pet: dog_type }`, and the one fact that outlives the substitution is that `dog` IS-A `pet`.
So an application standing at a **composition operand** is subsumed there — §5.8 already absorbs an open
operand's fields — and **mints no entry at all**. What the resolver used to mint was an artifact:
`pet_dog_dog_type_9e7bfc62` carried `subtypes=[dog]` and `extension=ABSTRACT`, nothing in the closure named
it, and a bind-mode compile never asked for it. An entry with one subtype, no referent and no reader is a
derivation step wearing an entry's clothes.

**Minting is keyed on naming, not on the mark.** An instantiation entry is minted where an application is
*named at a type position*, and nowhere else. Keying it on the mark instead — a marked template subsumes,
a plain one instantiates — reads simpler and costs a shape people want: `result => abstract <T> { … }`
with `ok => <T> result<T> & { … }` would leave `ok<text>` and `ok<int32>` both IS-A a flat `result`, and
`result<text>` would stop being writable at a position at all. Keyed on naming, the two coexist with no regime
flag: `pet<"dog", dog_type>` is only ever an operand and gets no entry, `result<text>` is written at positions
and gets one, and a schema that writes both gets both behaviours from one rule.

**A declaration body mints too, and what that buys is measured.** `a => box<text>` resolves to a REFERENCE
entry over a minted `box<text>` rather than to the closed entry itself, and the hop reads as ceremony until
the identity it carries is asked for. Three properties: a direct definition and a field use of one application
reach one entry; two declarations naming one application are two aliases over one entry, neither privileged;
and a schema writing `box<text>` that has never seen the schema declaring `a` names that same entry, so an
import merge unifies the two instead of splitting them. The third settles it — a content-addressed name is a
function of the form alone where an author's name is a fact about one namespace, and a field use in another
schema cannot name what it has not heard of (§3.3.4). What moves is which name is **shown**: a binding map is
keyed on the name the author wrote and a diagnostic prints it, the alias being collapsed when readers are
compiled (§8.3) so a read pays nothing for it.

**The correction to #11.** #11 refused member dispatch on a template because "a template has no set for such
a claim to range over … the claim's subject would be assembled from whichever applications a closure happens
to contain". That argument is sound where a *type* parameter multiplies the families — `ok<T>` composing onto
`result<T>` puts nothing in `result<text>`'s index unless someone writes `ok<text>` — and it does not reach a
family whose members *are* the applications, which is one family with one index. Nor does openness separate
the two cases: a closed base's family is already open across schemas (§3.3.4), and #10 answers that with the
pinning obligation. The argument has lost its target in any case: with the claim derived rather than marked,
there is no assertion to refuse — the selectors are there or they are not. **FINAL stays refused, and now for a better
reason**: its only
coherent reading on a template would forbid composition onto the base, and every application is a subtype of
that base, so the mark would forbid the applications it exists alongside — the claim is false of the
declaration before an author writes a second one.

**What a host binding gets, which is what the shape is for.** The base binds to an interface and each member
to its own class — `sealed interface Pet permits Dog, Cat` over records — and that is already how a
hand-written family binds. Two facts constrain the design: a member cannot bind to the base's class (it is not
record-shaped, and the strict field-to-component check would refuse it in any case), and the base's own
binding is never asked for, an abstract base having nothing to construct. So this changes what a *schema* can
say and leaves the binding contract where it is.

**What is running:** all of it, in both encodings. A template carrying `extension` is a family base: it is
named at a field or element position, it indexes its applications in `subtypes`, and it compiles to a
dispatching reader — `AbstractTemplateReader` in the text stack and `TreeTemplateAbstractReader` in the JSON
one, each delegating to the same record reader a closed base of the same shape uses, so one rule gets one
verdict in both (§9.4). Measured: `!holder { p: { type: "dog"  value: { breed: "lab" } } }` selects the dog
member by its pin, the cat member is selected by its own, and a pin no member states is refused naming the
base and offering the pins a document could write; an ABSTRACT base dispatches by tag over the aliases a
document can write. `final` is still refused on a template, and a selector typed by a parameter is refused
where it is written `=?` and passed over where it would only have been derived. The base's `extension` is
derived and is ABSTRACT for every record template — a labelled sum, a composition template, one with no
selector at all — and absent for a container, a constructor application and a reference template, each of
which is still refused at a type position along with a wrong argument count. Its `discriminators` is derived
too: a `=?` field, or, in a *fresh* record template, a field pinned to a value parameter, one value per
application being one per member. A refinement or composition template derives none — `array ^ {
element_type: = T }` pins a constructor's own facet and states nothing about a family. The parent-entry design is
withdrawn and nothing is minted for a composition operand. `record.discriminators` and `template.discriminators`
are in the meta-kernel and `record_field.discriminator` is gone; `FamilySelectors` is the one derivation both
encodings read, recovering a template base's selector *types* from any member — exact rather than a best
effort, since §5.7's identity diagonal forbids the base pinning what its members each pin differently and the
tightening table governs a field's state and never its type.

**Suggested resolution.** State in §5.10 that a **record-bodied** template is a family base which may be named
at a type position, with the reason — the position is never read against the template, only against the member
dispatch selects — and that a reference template and every non-record body are not, on the four grounds above.
State the two levels of `extension`, since one mark used to appear to govern both: the base's is **derived**
and always ABSTRACT — never OPEN, never FINAL — with `template.discriminators` beside it holding whatever
selectors survive erasure, and an instantiation's is **stated** (OPEN unless the template carries
`abstract`). State that the selectors do not travel to a member and ABSTRACT does, with the scopes that make
that consistent. State what a base carries — the fields that survive erasure, a field whose type mentions a
type parameter omitted, an empty base legal — and the condition on a selector: its declared type MUST contain
no type parameter, its pin being what a parameter supplies. State that a field pinned to a value parameter in
a fresh record template **is** a selector, one value per application being one per member, so a labelled sum
needs no spelling of its own. State that an application at a composition operand is subsumed where it stands and mints
nothing, that an instantiation entry is minted only where an application is named at a type position, and that
both rules are keyed on the position rather than on the mark. State that the member's body records the
application as written (§5.8's `[type_ref]` channel, #12) while the derived index records the base, and that
#10's closure checks apply to the members unchanged. Move the discriminator statement from `record_field` to
`record` and `template`, with §5.8's flattening as the reason. **Amend §1.3**: a resolved-output consumer must
support naming a template entry at a type position and dispatching on its `extension` and `discriminators`,
and must never need to read a held body — which is what stating the discriminators on the entry buys, and the
one observable change this entry asks for. Correct #11's blanket refusal: member dispatch is admissible on a
template whose members are its applications, FINAL is not, and the reason FINAL is not should be the one above
rather than the set-membership argument, which does not apply.

---

## 14. §8.2 makes a derived name the resolver's, and nothing says what a consumer may do with one

**Documents:** [TSON-SCHEMA] §8.2 (identity, internal names, `source`), §5.10 (materialisation), §8.1 (resolved
output), §8.3 (a reference is a hop); [TSON-JSON] §3.3 (`$type`). Reads with #13.
**Kind:** underspecification — one clearly stated fact whose consequences for consumers are unstated, where an
implementation must pick something and a wrong pick is invisible until someone tries to write a configuration.

**What §8.2 states.** A synthetic or instantiation entry's name is resolver-chosen, fresh by construction,
disjoint from declared names and unreachable from source; identity is keyed on the form, an instantiation's on
the application recorded in `source`. All of that is about the *resolver*. Two questions a consumer asks are
left open, and they are not hypothetical — this implementation answered both wrongly first.

- **May a consumer contract be keyed on one?** A binding map, a generated class table, a configuration file.
  It must not: the spelling is not stable across implementations, and within one it moves whenever the form's
  content does. What a contract keys on is a name some declaration gave the application — §8.3's alias — and
  a processor holding both SHOULD prefer the declared one wherever it shows a name or accepts one.
- **How does a consumer recover the applications of one template?** By `source`: an instantiation records the
  head and arguments, so the group is a walk over entries and needs no index. Stating it matters because it is
  what makes "a template is not a type" workable for a code generator — the family a host language spells
  `Result<T>` is exactly that group, and a generator that cannot recover it must either monomorphise blindly
  or give up.

**What the silence cost here, measured.** This implementation's derived names are content hashes
(`box_text_04117bb4`, `msg_of_ping_ping_body_d8846fd5`). A bind lookup asked for the minted name, so a binding
map for a schema using templates could not be written at all and a generator could not emit one; and a
schema-load diagnostic printed minted names, pointing an author at declarations they had never written
(`'pet_of_cat_int32_1c52dc45' and 'pet_of_cat_text_48ad744f' pin the discriminator of 'pet' …`). Both were read
as ordinary bugs rather than as one missing rule, which is the signature of an underspecification: each surface
that shows or accepts a name picks for itself, and they disagree.

**What is running:** both answers above. A lookup and a message both resolve under the name the author
wrote, falling back to the entry's own, and only for a *derived* entry — an entry with a source position was
declared, so an alias never redirects it. `source` grouping is what the measurements in #13 rest on: within one
schema a direct definition and a field use of one application reach one entry, and across schemas two documents
that never meet reach the same one, which is the property a content-addressed name exists for and the reason it
must stay internal rather than becoming a consumer's key.

**Suggested resolution.** In §8.2, after the internal-name rules, state the two consequences: no consumer
contract may require a derived name, the declared alias being what a processor shows and accepts where both
exist; and the applications of one template are recoverable from `source`, which is the provenance a generator
needs. Neither changes what a resolver produces — they say what may be done with what it produces, which is the
half §8.2 currently leaves to be guessed.

**Not proposed: refusing a derived name written in data.** A minted name is a valid `identifier` present in the
namespace a processor resolves against, so `!box_text_04117bb4` in text and `"$type": "box_text_04117bb4"` in
JSON both read, and this implementation accepts them. A rule forbidding it would buy little: such a document can
only have been written by reading one processor's output, which is a debugging act, and it fails as soon as it
meets another processor, whose spelling differs. [TSON-JSON] §3.3 states the non-portability and does not refuse.

---

## 15. A declaration that denotes a type is that type's entry, and a use site is what mints

**Documents:** [TSON-SCHEMA] §8.2 (identity, internal names, `source`), §5.3 (the container lift), §5.10
(templates and materialisation), §8.1 (resolved output), §8.3 (a reference is a hop), §3.3.4 (`subtypes` open
across schemas), §4.3 (both operator families), §5.2 (a discriminator and its pins); [TSON-JSON] §6.1.5
(`$type`). Reads with #13 and #14.
**Kind:** design report — one lift channel corrected, an asymmetry kept on purpose, and one cross-schema
property knowingly given up. **All of it is running except the merge key the resolution proposes.**

**The axis is construction against reference, not sugar against application.** That correction matters
because it locates the defect. `resolveTemplateApplication` ends by calling the *same* function a bare-name
alias reaches, differing only in whether the type-ref carries arguments — so `a => box<text>` was a
`REFERENCE` for exactly the reason core.tn's own `documentation => text` is one. Every declaration whose body
**constructs** (a sugar form, or the explicit `!array { … }` it denotes) became the entry; every declaration
whose body **references** became a hop. An application is a parameterised reference syntactically and a
construction semantically, and it was being filed by its syntax.

**What that cost, measured before the change:**

| Written | Resolved to |
|---|---|
| `text_list => [text]` | `text_list` **is** the entry — `kind: PRODUCT`, `source: array` |
| `holder => { f: [text] }` | a separate minted `array_text_4cc4a482`, same content as `text_list` |
| `a => box<text>` | `a` a `REFERENCE` to a minted `box_text_04117bb4` |
| `dogs => pet<dog_type>` | `pet.subtypes` held `[pet_dog_type_8b4b2b5c, …]`, never `[dogs, …]` |

§8.2's own split is what the third and fourth rows offend: "a declared entry's identity is its name … a
minted entry's identity is its canonical content, **since it has no declared name to be its identity**". A
declaration with a perfectly good name was given neither — an indirection to something else's identity — and
every surface that shows a name then had to follow it back. #14 exists to do that following.

**Neither shape is wrong on its own terms, which is what makes this a spec question.** §8.2's own split is
that "a declared entry's identity is its name … a minted entry's identity is its canonical content, since it
has no declared name to be its identity". Read against that, the sugar row is the rule working: `text_list`
is declared, so its identity is its name, and the use site's `[text]` has no declared name and gets a content
one. The application row is the odd one — a declaration with a perfectly good name is given neither identity
but an indirection to something else's.

**What the indirection bought, and it was cross-schema.** Three properties held before the change: a direct
definition and a field use of one application reach one entry; two declarations naming one application are two
aliases over one entry, neither privileged; and **a schema writing `box<text>` that has never seen the schema
declaring `a` names that same entry**, so an import merge unifies the two rather than splitting them. The third is the load-
bearing one, and §8.2 says so where it makes determinism a SHOULD: content-derived names "are what makes
independently-resolved namespaces agree on their internal names wherever their structures agree — which is
what lets the import merge unify rather than collide". A use site in another schema cannot name what it has
not heard of (§3.3.4), so an author's name cannot carry that property.

**What is running.** A declaration whose body is a fully-bound application **is** the instantiation entry:
`bx => box<text>` resolves to `bx => !record { … }` with `source` the canonical application, and no
`box_text_…` is minted beside it — **not minted and then collapsed, but never created**. The close runs
*into* the declared name: it derives no internal name, claims none, and publishes nothing, recording only
that this declaration owns that canonical application. A use site writing `box<text>` resolves to a
declaration that owns it rather than minting a parallel entry, so §8.2's "two fully-bound applications
denote the same entry" still holds within the schema — and it composes, since §4.3's chain walk is now
applied (the operand fix that made an alias of a record admissible). A **synthetic** is untouched by any of
this: a use-site sugar form has no author-written name for identity to key on, so it keeps its
content-derived one, which is §8.2's own split doing what it says.

What the change buys is visible where it was owed. `pet.subtypes` now reads `[dogs, cats]`; a colliding-pin
refusal names `'a'` and `'b'` rather than rendering `pet_of<cat, int32>` through `EntryDisplayName`; and a
read's `typeRef` reports `dogs` where it used to report `pet_dog_type_8b4b2b5c` — the hash reached the *data*
model, not only the schema.

**Two declarations of one application are two entries, and no tie-break is needed.** §8.2 says two such
declarations are "two aliases over one entry, **neither privileged**"; each closing into its own name keeps
that promise literally, where privileging the first in document order would break it. The pair is two entries
with one structure — which is exactly what `a => { v: text }` beside `b => { v: text }` already is, and
therefore what `t => <T> { v: T }` with `a => t<text>` and `b => t<text>` has to be as well: duplicate
definitions *by choice*, with the same structure. Nothing dedups the hand-written pair, and nothing should
dedup this one.

**Where the duplicate is not inert, the rule that catches it is already there.** `dogs` and `hounds` over one
`pet<"dog", dog_type>` are two family members pinning `"dog"`, which §5.2's pin-distinctness rule refuses —
the identical verdict `dogs => pet & { type: ="dog" }` beside `hounds => pet & { type: ="dog" }` gets. That
is the whole of it: the same thing has been authored twice, which is no part of naming or deduplication and
is a pin-distinctness catch. Outside a discriminated family the duplicate is ordinary — two entries with one
structure, as two records with one field list are — and both names stay usable as dispatch identifiers in
data, which is what the collapsing shape took away.

**A sugar form keeps minting, and that asymmetry is the design rather than a remainder.** `text_list =>
[text]` beside a use-site `[text]` is two entries with one content: the declaration is its own entry, and the
use site mints a synthetic keyed on its content. The two channels differ because the types differ in what their
identity has to carry. A non-record sugar form — an array, set, map or tuple — is the same definition wherever
it is written: nothing subtypes it, no tag dispatches to it, and a content-keyed synthetic is exactly the entry
every location writing `[text]` should reach, and a useful one to redirect to. A **record** is different: it
takes part in IS-A, a family's `subtypes` index and a tag both key on its entry, and so the declared name has to
*be* the entry for `pet.subtypes` to read `[dogs, cats]` and for `!dogs` to select it. Unifying the sugar
channel with the application one would buy nothing a consumer observes and would cost the shared,
content-identified target.

**What was given up, stated plainly.** A content-addressed name is a function of the form alone, which is
what lets two independently-resolved namespaces agree on it — §8.2's determinism SHOULD, and what "lets the
import merge unify rather than collide". A declared name cannot carry that: a schema writing `box<text>` that
has never seen this one mints the content name while this one calls it `bx`, so the merge now sees two names
for one form. Nothing in the implementation depends on it today, and no bundled schema or corpus vector
exercises it, but it is a property traded away rather than preserved.

**The conformance corpus carries the new shape already.** Two `class2/link` vectors used to assert the minted
entry exists — one `binds: [box text_box box_text_xxhash]`, the other a `subtypes` table keyed on
`result_text_xxhash`, with descriptions stating that closing an application "mints an entry keyed on the
application, which the declaration naming it aliases". They now read `binds: [box text_box]` and index `box`
as exactly `[text_box]`, where **the absence of the second name is what states the rule**. Those are
assertions in a shared language-agnostic corpus rather than this implementation's own tests, and the
corrective change here satisfies them unchanged — which is the evidence that *never minting* and *minting
then collapsing* agree on everything observable from outside the resolver.

**Suggested resolution.** State in §8.2 that a declaration whose body denotes a type **is** that type's
entry, in both lift channels, and that a use-site application resolves to such a declaration rather than
minting a parallel entry. Two things need saying with it. **That two declarations naming one application are
two entries**, neither privileged and neither collapsed — the section already says "neither privileged", and
this is what honouring it looks like. Where the pair is a discriminated family §5.2's pin-distinctness rule
refuses it, exactly as it refuses the hand-written pair; no relaxation of that rule is wanted, and none is
needed. And **how cross-schema unification survives**: most simply by keeping the
content-addressed name as a merge key alongside the declared entry, so §8.2's determinism SHOULD still has a
subject. §5.3 should then say why a use-site sugar form mints where a declared record application does not: a
non-record form is the same definition at every location and carries no IS-A, so a content-keyed entry is the
right one to share, where a record's declared name must be its entry for subtyping and tags to reach it.

---

## 16. §4.3's operand rule contradicts itself for a record template's instantiation

**Documents:** [TSON-SCHEMA] §4.3 (both operator families), §5.7 (refinement, which restates the same list),
§5.8 (composition), §5.6 (top-level constructor applications), §8.2 (identity, and the instantiation entry's
shape). Reads with #15, which is what made the collision reachable.
**Kind:** internal inconsistency — one sentence states a MUST and an exception, and the exception is either
redundant with the MUST or contradicts it, with no case where it does independent work. **The narrowing is
running.**

**The sentence, in §4.3:**

> **Both families consume vocabulary bodies.** The source of a refinement and every operand of a composition
> or subtraction MUST, after following its reference chain (§8.3), be a definition whose body is a `!record`
> — a shape with fields to tighten or merge. A definition whose body is a binding record — a top-level
> constructor application (§5.6), **a template instantiation (§8.2)**, or an alias resolving to either — is
> *finished* and admits neither operator.

**A record template's instantiation satisfies the MUST and is caught by the exception.** §8.2's own entry
shape is why: an instantiation's body is "the substituted binding record, headed by the applied constructor",
and for a record template that constructor is `record` — so the body literally *is* a `!record` with fields.
For every other template the two halves agree and the exception is redundant: `vector<text, 3>` closes to
`!array`, a map template to `!map`, a choice template to `!choice`, and the MUST already refuses each on its
own body. So the clause does no work except where it contradicts the clause beside it. §5.7 restates the same
list for its own source and inherits the same defect.

**Measured, over `box => <V> { item: V }`:**

| Written | Before |
|---|---|
| `sub => box<text> & { extra: text }` (application inline) | resolved — `item` + `extra` |
| `bx => box<text>` then `sub => bx & { extra: text }` | **refused** |
| `bx => { item: text }` then `sub => bx & { extra: text }` | resolved — `item` + `extra` |
| `bx => box<text>` then `sub => bx ^ { item: text = "x" }` | **refused** |

Rows 1 and 2 denote the same type and got opposite answers; rows 1 and 3 produce identical field sets. The
refusal protected nothing — the composition was already expressible, just not through a name.

**Why the MUST is the half to keep.** §8.2 says "what is canonicalised is identity, not provenance", and
after #15 a declared application *is* an ordinary `!record` entry — so the only thing left to discriminate on
is `source`, which is provenance. Gating `&` on it makes composition depend on how the author spelled a type.
§5.8 already admits the application written inline, and §5.7's own prose recommends it ("a composition
wanting one writes a trailing body, `method<order, order> & { … }`"), so refusing the *name* for that same
instantiation draws a line with no semantic content. And "whose bindings are already set" is covered where it
belongs: §5.7's per-field rule refuses re-fixing a `REQUIRED_FIXED` field to a different value, whoever wrote
it, so the blanket refusal is redundant with a rule that is sharper.

**What is running.** Both `source`-based discriminators are deleted, and §4.3's body test is the whole rule.
It gives the right verdict in every case on the operand's own body: a record instantiation composes and
refines, while `vector<text, 3>` (`!array`), a declared `{text => int32}` (`!map`) and a choice (`!choice`)
are each refused for having no fields — which is what keeps the "finished" idea intact where it was always
doing the work.

**Suggested resolution.** Drop "a template instantiation (§8.2)" from the finished list in **both** §4.3 and
§5.7, leaving "a top-level constructor application (§5.6), or an alias resolving to it". The MUST beside it
already refuses every instantiation that genuinely has nothing to merge, and does so by asking about the body
rather than about provenance. If instead the exception is meant to stand, §4.3 needs to say which half wins
for a record template and why an author may compose with `box<text>` written out but not with a name for it.

## 17. §5.4 makes `disjoint` sufficient to omit a tag, and in JSON it is not

**Documents:** [TSON-SCHEMA] §5.4 (the discrimination classes, the no-class list, and **Tagging**);
[TSON-JSON] §4.2, §8.2, §8.3 (the class-stability condition this entry would move into Part 2).
**Kind:** inconsistency between Parts 2 and 3 — Part 2 states a rule for every encoding that one encoding has to
break. **Part 3's stricter rule is running; the Part 2 change is a proposal.**

**The sentence, in §5.4:**

> The tag is REQUIRED when the choice is not disjoint, and MAY be omitted when it is: `disjoint` means precisely
> that the encoding's own form resolution […] recovers the variant

**For JSON that is false in two cases, and they are the only two.** A `float_type` instance still admitting
`.nan` or the infinities has values JSON spells as strings ([TSON-JSON] §5.4), so an untagged `"…"` at
`( float64 | text )` could belong to either variant; and a map whose key type is compound is spelled as a JSON
array of pairs ([TSON-JSON] §6.5), so an untagged `[…]` at `( {point => text} | [text] )` could belong to
either. Both choices are `disjoint: true` by §5.4, and in both the encoding's form resolution does not recover
the variant. Part 3 therefore adds a second condition — every variant *class-stable* (§8.3) — and a decoder
omitting the tag on §5.4's word alone would be non-conforming to Part 3. One schema then has two different
sets of untagged values depending on the encoding, which is the thing a derived, encoding-neutral fact exists
to prevent.

**Part 2 already has the mechanism, and uses it for the same reason.** §5.4 gives `rational` and `complex` no
discrimination class because their "typed forms straddle classes". The two JSON leaks are the same property in
another encoding: an approximate atom admitting non-finite values has forms in two classes, and so does a map
whose key type no single scalar token denotes. Neither is special to JSON in kind — any encoding without a
number spelling for the specials, or without a delimiter pair for compound-keyed maps, meets the same two.

**What is running.** [TSON-JSON] §8.2 omits the tag only where the choice is disjoint **and** every variant is
class-stable, computed once per choice when the schema compiles; §8.3 names the two leaks and closes the set.
Object-form maps are class-stable, which is what keeps §5.7's arbitrary-JSON declaration
(`( text | number | boolean | [json?] | {text => json?} )`) readable untagged, and §8.3.1's escape rule —
reduced to a test of the object's first member now that Part 3 puts `$type` first — settles the one reading it
leaves. TSON text omits the tag on `disjoint` alone, so `( float64 | text )` is untagged in text and tagged in
JSON.

**Suggested resolution.** Extend §5.4's no-class list with the two straddling kinds: *an approximate atom whose
`allow_nan` or `allow_infinity` is true, and a map whose key type, after following its reference chain, is not
a type a single scalar token denotes by its content — an atom family or an enum, the `unit` instances `value`
and `void` excepted*. `disjoint` then carries class stability itself, every encoding reads the one derived fact,
and a choice's untagged values are the same set in every encoding. Part 3's §8.3 reduces to a note explaining
why those two have no class, and §8.2's predicate to `disjoint` alone. The cost is on the text side:
`( float64 | text )` and a choice over a compound-keyed map need a tag there too, where today they do not — the
price of one answer across encodings, payable by narrowing `allow_nan`/`allow_infinity` or by tagging.

## 18. Value-space identity is defined for atoms, and a compound key or set element can only have the host's

**Documents:** [TSON-SCHEMA] §7.5 (set duplicates), §7.7 (type-aware duplicate keys), §5.5 (value spaces);
[TSON-DATA] §2.6 (key identity, layered).
**Kind:** underspecification — the rule is stated for every key and element type and is only implementable for
atoms. **The Suggested resolution is a proposal; what is running is described below it.**

**The sentences.** §7.5:

> Two values are duplicates if the element type's equality contract considers them equal, and that contract is
> the type's **value space** (§5.5)

and [TSON-DATA] §2.6, for a map key under a schema:

> under a schema identity is over the key type's *value space*, never its lexical space

**For an atom this is well defined and implemented.** §5.5 fixes each family's value space, and a processor
reduces a decoded atom to it: text under NFC, `number` by value (`199.90` is `199.9`), `bytes` by octets,
`datetime` as an instant (`2026-01-01T00:00:00Z` and `2026-01-01T01:00:00+01:00` are one member of a
`set<datetime>`).

**For a compound value — a record, array, map or choice as a map key or set element — it is not.** Nothing in
Part 2 defines a record's value space beyond its fields'. The natural reading is "structurally, each position by
its own value space", but no processor that hands values to a program can promise it. Identity at a compound
position is whatever the host language's equality and set implementation say about the value the processor
built:

- *A bound class decides for itself.* A Java `record` compares an `OffsetDateTime` component by offset and a
  `BigDecimal` by scale, so `{ at: "…T00:00:00Z" }` and `{ at: "…T01:00:00+01:00" }` are two keys. A `byte[]`
  component compares by reference, so two *identical* spellings are two keys — below even §2.6's textual floor.
  A class with a hand-written `equals` can make any pair equal or unequal. The processor cannot see inside it,
  and replacing its equality with one of the spec's would make a set of the program's own values disagree with
  the program.
- *A tree has only what its node model keeps.* A tree that keeps spellings compares compound nodes by spelling
  unless it re-derives every atom's value inside every key it compares.

TSON can reduce to a value space only the atoms it implements on the host. Past an atom, identity belongs to
the host.

**What is running.** Atom elements and atom keys compare by value space in every mode and both encodings — a
`set<datetime>` holding one instant spelled two ways is refused in TSON and JSON, tree and bind. Compound keys
and elements compare by host equality over what the mode built:

- TSON tree mode compares a record key by the tree's structural equality, whose atoms keep their decoded host
  values — so a `datetime` inside a key compares by offset and the two-spellings pair above is two keys.
- JSON tree mode reduces a compound node recursively: numbers by value, strings by NFC spelling, member order
  dropped — so a `datetime` inside a key compares by spelling.
- Bind mode, in both encodings, compares by the bound class's `equals`.

Every mode detects §2.6's textual identity for tree reads; a bound class's `equals` can fall below it.

**Suggested resolution.** Confine value-space identity to what the spec can define, and hand the rest to the
host explicitly. In §7.5, after the atom examples: *For an element type that is not an atom — a record, array,
map, or choice — two elements are duplicates if the processor's host representation of them is equal. Which
pairs that relates is implementation-defined: a processor detects at least the textual identity of [TSON-DATA]
§2.6 in a tree it builds itself, and where elements are bound to host types, those types' equality decides.*
§7.7 and [TSON-DATA] §2.6's "under a schema" sentence take the same qualifier for compound key types. A schema
author who needs portable duplicate detection over a compound key then knows to key by an atom — a derived
identifier or a canonical string — which is the only identity every encoding and every host can agree on.

## 19. A schema the processor cannot obtain has no outcome, and the one the category rule gives it is a verdict

**Documents:** [TSON-DATA] §8.1 (the four categories, "one severity", the fifth outcome); [TSON-SCHEMA] §10.1 (the
schema library, "the resolver reports an error"), §10.2 (a pin mismatch "is a resolver error"), §11.2 (fetch
policy); [TSON-JSON] §9.4.
**Kind:** underspecification — a common processing result that §8.1's closed set of outcomes does not name, so
the category rule assigns it one that says the wrong thing. **The interpretation is running; the resolution is a
proposal.**

**What the spec says.** §10.1: when a `!!schema`, `!!meta` or `!!import` reference is not in the library, "the
resolver reports an error — it does not attempt to fetch". No category is named, so §8.1's rule applies — "the
layer that detects the violation determines the category" — and the resolver detected it. §8.1 also closes the
set: a conforming processor "has one severity", every required diagnostic is one of four categories, and the only
exception is the fifth outcome, a refusal. So a document whose schema this processor does not hold is, read
literally, a document with a resolver error: **invalid**.

**That verdict is false, and §8.1's own reasoning for refusals says why.** A refusal is a fifth outcome because
"the same document may be well-formed, valid, and accepted in full by the next processor along". A missing schema
is exactly that case, more plainly than a name-hygiene refusal is: nothing has read the schema, so nothing is
known about whether the document conforms to it, and a processor whose library holds it will accept the document
unchanged. A consumer routing on the category — "resolver error: repair the document and resend" — repairs a
document that is not wrong. The same holds when fetching is enabled (§11.2) and fails: the host is not on the
allow-list, the location has nothing there, the network is down, the response is too large.

**The case the spec does settle shows where the line is.** §10.2 makes a pin mismatch a resolver error, and that
one is right: the schema *was* obtained, its bytes were read, and they are not the bytes the document's reference
commits to. That is a finding about the reference the document carries. What is unsettled is only the case where
nothing was obtained to find anything about.

**Interpretation chosen:** not obtaining a schema is **not a verdict**. Each fetch outcome has its own code —
`SCHEMA_NOT_PERMITTED` (§11.2's policy said no), `SCHEMA_NOT_FOUND`, `SCHEMA_UNREACHABLE`, `SCHEMA_TIMEOUT`,
`SCHEMA_TOO_LARGE` — and `Diagnostic.Code.verdict()` answers `false` for all five, beside `LIMIT_EXCEEDED`,
the §9.1 refusal. They ride in the same report as everything else, located at the reference, so a document with
both a missing import and an ordinary error reports both. The `tson` command maps them to exits that say "not
checked" rather than "invalid": 69 where a rerun cannot help (not permitted, not found, too large) and 75 where it
may (unreachable, timeout). A pin mismatch stays `SCHEMA_ERROR`, a verdict, per §10.2. The codes are one per
reason rather than one permanent/transient pair because consumers partition them differently — a command line by
whether a rerun could help, an HTTP surface by whose doing it was.

**Suggested resolution.** Widen §8.1's fifth outcome from "refused" to **"not judged"**, with two members: a
*refusal* (§8.2, §9.1 — the processor declined under its policy) and an *unavailable schema* (§10.1, §11.2 — the
processor could not obtain what it would judge against). Both are distinguishable from the four categories, both
share the property that makes the outcome a separate one — the next processor may accept the document in full —
and both are reported in the same report. §10.1's "the resolver reports an error" becomes "reports the schema as
unavailable". Keep §10.2 as it is and say why it differs: a mismatch is a finding about obtained bytes. The
alternative — state outright that a missing schema is a resolver error — is simpler and costs every consumer the
ability to tell "your document is wrong" from "I could not look", which is the one distinction a sender acting on
a report needs.


## 20. `@rest` is a representation directive whose class cannot be defined, and the capability it names is a map

**Documents:** [TSON-SCHEMA] §6 (the annotation rules and `@rest`), §7.2 (records are closed under their type),
§5.8 (the restated-field rule the per-chain count relies on), §8.1 (ingest re-checks); [TSON-JSON] §1.3
principle 6, §6.1.1, and the §6.2 this entry deletes; [TSON-DATA] §2.5, §2.6, §7.7.
**Kind:** internal inconsistency, and a directive with no definable scope.

**What is running.** `rest => @annotation void` is gone from `meta.tn`, `core.tn` and `meta-resolved.tn`, with
no replacement: a member matching no declared field is a closure error in both encodings, and open-ended data is
carried by a declared map-typed field. [TSON-JSON] §6.2 is deleted and §6.1.1 states the answer and the
migration. This is the as-built state, not a proposal — what is proposed is that [TSON-SCHEMA] §6 drop the
declaration and the paragraph introducing it.

**§6 cannot say what encoding class `@rest` binds.** §6's directive bullet reads: "it directs how a *class* of
encodings represents a value, and force is confined to the encodings that claim it. No encoding is privileged —
TSON text is one member of the text class, beside JSON — so a directive binds every encoding in its class."
`meta.tn` declared `@rest` for "an encoding that flattens". The two readings of *class* are both unusable:

- **The text class**, which is the only class §6 names and which names TSON text and JSON as co-members. Then
  `@rest` binds TSON text, text must flatten, and `{name: "x"  a: 1}` is a legal `config` — which contradicts
  §7.2's closure and the encoding rules as drafted.
- **The encodings that flatten**, which is what `meta.tn` wrote. Then the class is defined by the behaviour the
  directive directs, "binds every encoding in its class" reduces to "binds the encodings it binds", and the
  clause does no work in the one case it applies to. The class had exactly one member.

`@rest` was the category's only instance, so the definition was never tested against a second. It failed against
the first.

**[TSON-JSON] already forbids it in its own words.** §1.3 principle 6: "The wire rules consume what the resolver
derives — the `disjoint` fact in each choice body, a record's own extension fact, the value→variant mapping of a
sealed family reconstructed from its `REQUIRED_FIXED` pins — and never introduce wire-only declarations that
could drift from the schema." `@rest` is a wire-only declaration, and the principle's three examples are exactly
the three facts that stayed in the body. The document forbade it and then defined it.

**Relocating it into the kernel was considered and is worse than removal.** A `record.tail: field_name?`
naming an absorbing field — the shape `record.discriminators` takes, and spelled `extras => {text => value}` at
a record entry to match `=?`'s one-token dispatch — would satisfy principle 6 and the meta-hop argument of #11.
It buys nothing else. The value space is identical either way: `extras: {text => value}` already expresses
"declared structure plus an open tail", so the whole feature is one *spelling* for one encoding. Against that:
§7.2's closure would need a carve-out, the reference encoding would need a leading-selectors rule it has never
had (§5.8 lets a subtype tighten the tail, so an entry arriving before the selectors cannot be typed), and the
flatten cannot be made uniform anyway — a flat member is a field name and must be an identifier (§7.7), while a
map key is a value and need not be, so text could absorb `@context` nested and never flat.

**And the capability is one the ecosystem already declines.** Systems that genuinely carry an open tail nest it:
Stripe's `metadata`, Segment's and Mixpanel's `properties`, Kubernetes' `labels` and `annotations`. Each nests
for the same reason — a producer with reserved names beside custom ones needs to tell them apart — and each is
exactly the map-typed field TSON already has. The counter-pressure runs the same way: Kubernetes' structural
schemas prune unknown fields, with `x-kubernetes-preserve-unknown-fields` an explicit and discouraged opt-out.
Host-language support tells the same story, and tells it against the feature: Jackson's `@JsonAnySetter`,
C#'s `[JsonExtensionData]` and Go's hand-written two-pass `UnmarshalJSON` are each their platform's awkward
corner, and `serde(flatten)` forces the deserializer into a buffered representation, breaking zero-copy and
`deny_unknown_fields`. That is not a platform accident — a member whose type is unknown until the object closes
cannot be streamed into a typed slot, so something must hold it. This implementation's allocation harness pins
flat zero bytes retained per read, which a tail field cannot honour.

**What removal costs, stated plainly.** A producer that cannot be changed and sends declared and undeclared
members at one level cannot be validated field-by-field. JSON Schema's `additionalProperties` defaults to *open*,
so a contract converted without attention describes documents this encoding refuses — which is why §6.1.1 now
states the boundary rather than leaving it to inference. The remaining answer is a map-typed position, which
validates any JSON object and forgoes per-field types. That is a visible author decision rather than a
half-mechanism making it silently, and it is where [TSON-SCHEMA] §2.5 already points: *a key that is not a name
belongs in a map*.

**Suggested resolution.** Delete `rest => @annotation void` and §6's paragraph introducing it, leaving
`@disjoint` as the checked category's only member and the representation-directive category with none. State in
§7.2 that closure has no exception. If a projection annotation later lands (#5), §6 owes the class definition
this entry found missing before that category has a member again. The alternative — keep the declaration and
define the class as a property an encoding-rules document claims — preserves a feature with no consumer, no
modelling gain and a load-time check nobody has written, at the price of the closure carve-out and the ordering
rule above.

---

## 21. An enum models a vocabulary and a value set, and `enum_set` admits only the vocabulary

**Documents:** [TSON-SCHEMA] §7.4, §9, §5.4, §5.7, §11.4; [TSON-DATA] §7.7, §8.2.
**Kind:** limitation — a construct that spells one of the two things it models, with no way to say which was
meant. **The resolution below is built and running**, against the bundled schemas: `enum_set` is typed by
`text`, `enum` carries `profile: enum_profile ~ IDENTIFIER`, and the identifier rule is a coherence check on
the enum body rather than a property of the member set's element type. Conformance vectors cover both
profiles and the default's omission from the binding record.

### The two things an enum is

`!enum [OPEN ACTIVE DONE]` is a **vocabulary**: its members are names, they are written unquoted, they
generate host enum constants, and [TSON-SCHEMA] §11.4 makes them a named scope so [TSON-DATA] §8.2's
spoofing checks reach them.

`{"sedentary", "lightly active"}` is a **value set**: two values a document may carry. Nothing about it is a
name. It has no host-safe spelling, it is written quoted, and script-policing it would be a category error.

Both are enumerations, and §7.4 spells only the first: `enum_set => !set_type { element_type: identifier }`
(§9) makes every member a name, so the second has no spelling at all. The section says as much and treats it
as settled — "a display string is mapped at the boundary, as every comparable schema language requires" —
which holds for languages that generate code from a schema (protobuf, Avro, GraphQL and Thrift all require
identifier symbols) and not for languages that validate documents, which `xs:enumeration` and JSON Schema's
`enum` are and which is the use this implementation is built for. There is no boundary to map at when the
document is somebody else's.

### What it costs, measured

Measured over BFCL's AST pool — 2,351 tool-calling contracts carrying 4,171 enum declarations and 22,713
string enum members, of which 1,000 contracts are hand-authored by the BFCL team and 1,351 are contributed
real-world data:

**33.9% of enum declarations cannot be an `!enum`**, and 6,141 individual members fail the identifier rule.
The share is worse in the real-world half (34.4%) than in the hand-authored half (25.8%), and the
real-world half holds 94% of the enums — so the constraint rests on contributed data rather than on one
team's house style. (150 of these contracts were converted to TSON end to end; the enum counts are from the
whole pool.)

Where the failing members fall, by the profile that would admit them:

| | Members | Of all | Of failing |
|---|---:|---:|---:|
| `IDENTIFIER` — admitted today | 16,572 | 73.0% | — |
| `TOKEN` would admit — `2D`, `3DES`, `7z`, `C++`, `tar.gz`, `pm2.5` | 2,552 | 11.2% | **41.6%** |
| needs `TEXT`, i.e. quoting — `lightly active`, `Personal Info`, `<`, `>` | 3,589 | 15.8% | **58.4%** |

Of the 3,589 that need `TEXT`, **3,551 (98.9%) contain whitespace** and 38 carry other punctuation: the
`TEXT` case is multi-word display strings almost exclusively. The `TOKEN` row was measured against this
implementation, which accepts a bare token of letters, digits, `_`, `.`, `-` and `+`, and requires quotes
for everything else — `@ / : % & = < >` and any whitespace.

Enum sizes bear on the diagnostic argument below: median 4 members, p90 13, **max 29**, with 82 enums over
20. A message that names the member list stays readable at that scale.

Those declarations fall back to a pattern alternation. Accept/reject is exactly equivalent — TSON patterns
are implicitly anchored — so what is lost is the diagnostic, which is the reason to have the construct at
all:

```
!enum     'wizard' is not a member of this enum -- expected one of [admin, member, guest]
pattern   'sedentary' does not match the required pattern lightly active|moderately active|very active
```

The second asks a consumer to infer a member list from a regex. It also requires the converter to
regex-escape every member, a step that can silently go wrong.

### Proposed resolution: type the member set by `text`, and declare which kind of enumeration it is

```
enum_member_profile => !enum [IDENTIFIER TEXT]

enum_set => !set_type { element_type: text }

enum => atom & {
  members:         enum_set
  member_profile:  enum_member_profile ~ IDENTIFIER
}
```

`text` rather than `value`, and the distinction matters: `text` is the declaration type of the member
*list*, exactly as `integer_member_set` is typed by `integer` — it does not make the enum a text refinement,
and the enum's value space is still its member set. Typing the set by `value` was considered and rejected:
it makes an enum's **kind a derived fact**, so that `!enum [a b]` is a text type, `!enum [1 2]` a numeric
one and `!enum [1 a]` neither, and a consumer cannot know what an enum binds to without inspecting its
members and computing a shared class. It also leaves an identity question with no good answer (`!enum
[1 1.0]` — one member or two?) and puts `value` to a use its own kernel doc rules out: "the token,
uninterpreted, **read by the type the position hands it to**", where an enum has no other type to hand it
to. `text` makes the kind declared: every enum is a text value set, binds to text, always.

**Numbers are therefore never enums**, and that is the point rather than a gap: integer and decimal value
sets are `integer_type.members` and `decimal_type.members`, which Revision 36 already added. The partition
is clean and no rule is needed for choosing between two spellings of one thing.

### Why the profile is an enum and not a boolean, and why it defaults to IDENTIFIER

**"Profile" is [TSON-DATA]'s own word** for which lexical class a thing must lie in — §7.1's unquoted-token
profile, §7.7's identifier profile — so `member_profile: IDENTIFIER` reads in established vocabulary, and
does not spend `form`, which §2.4 has already committed to quoted-versus-unquoted.

**An enum rather than a boolean, because there is a real third point already named in the series.**
`IDENTIFIER ⊂ TOKEN ⊂ TEXT`, where TOKEN is §7.1's unquoted-token profile: it admits `2D`, `3DES`, `007`,
`192.168.0.1`, `tar.gz`, `C++` — everything writable without quotes — which is **41.6% of the failing
members** in the table above, and it preserves the terse unquoted spelling. This entry does not propose
shipping TOKEN: it buys spelling, not binding (`2D` is no more a host constant than `lightly active`) and
not hygiene, and `TEXT` subsumes the whole population at the cost of quotes. But the middle is a real and
sizeable one — 2,552 members, 11.2% of every string enum member measured — so the case for not shipping it
is that `TEXT` covers it, not that nobody writes it. Its existence is the argument for the shape. A
boolean forecloses it; an enum slots into §5.7's **selector facet** category, whose rule already reads
"may move under refinement only along the narrowing relation its members carry, which each family states"
— and the relation here is that chain, stated in one line. It also matches the kernel's existing
internal enums (`product_access_type`, `field_state`, `record_extension_type`, `scope_kind`), where the
kernel's three booleans (`signed`, `unordered`, `disjoint`) are all intrinsically two-valued facts and
this is not.

**IDENTIFIER by default**, for four reasons in order of weight:

1. **The kernel already defaults strict and makes latitude explicit.** `field_state` is REQUIRED until a `?`
   is written, `element_state` likewise, and `set_type` defaults `min_items` to 1 "so a set is non-empty
   unless a body writes `min_items: 0`". A TEXT default would be the first facet in the kernel where the
   safe reading costs a keystroke.
2. **Every existing enum survives untouched, in source and in resolved output.** §5.6: "a pin or default
   whose value is concrete in the head's own declaration comes from the vocabulary and **does not appear in
   the binding record**" — so `~ IDENTIFIER` is omitted at its own value and no resolved form moves. With a
   TEXT default, every internal enum in the kernel, meta and core would have to start declaring IDENTIFIER
   to keep what it has.
3. **Name hygiene stays on by default.** A TEXT default would silently drop §8.2's restricted-character and
   restricted-script checks from every enum in existence. Turning a spoofing check off should be written
   down.
4. **The binding guarantee is the default** (below), and an author who writes TEXT has recorded that they
   took the trade.

The diagnostic carries the cost and nearly does already — today a non-identifier member fails with
`'lightly active': U+0020 at index 7 cannot appear in an identifier`, which needs only to end by naming
`member_profile: TEXT` for the fix to be obvious where the failure is.

### What the profile gates, and what it does not touch

Three rules follow from the declaration instead of from inspecting members:

| | `IDENTIFIER` | `TEXT` |
|---|---|---|
| Hygiene | §11.4's scope and all three of §8.2's mechanisms, unchanged | mechanism 1 only — two members that read alike is still the hazard; the per-*name* restricted-character and restricted-script rules lapse, a value set carrying whatever its domain carries |
| Binding | every member is a host-safe name; host enum generation is guaranteed | host text, and the author declared it |
| Spelling | every member writable unquoted | quoted where the content requires it |

**What does not change, and this is most of §7.4.** §5.4's derivation stands verbatim — "an enum's class is
its members' shared class… read off each member's own token by [TSON-DATA] §4". §7.4's host-value rule
stands — "the resolved host value is determined by natural parsing of the matched token" — which is one
rule, not a carve-out, giving a host boolean at `boolean` and host text at `[OPEN ACTIVE]`. **`boolean`
stays `!enum [true false]`**: it is as much an enum as any other, and §7.4's sentence that "the member rule
constrains only how a member is *written*" becomes literally what `member_profile` controls. Matching stays
decoded-text identity at every enum. Uniqueness, the at-least-one-member rule, and the member-set
tightening of §5.7 are untouched.

**What changes** is §7.4's "Members are identifiers" sentence and the three replacements it sends numeric
and mixed enums to; `enum_set`'s element type in §9; [TSON-DATA] §7.7's identifier profile, whose list of
governed positions ("a field name, type name, annotation name, parameter name, or enum member") makes the
last conditional on the profile; and §11.4's enum-member scope, which gains the same condition.

### How an enum binds

A reviewer will ask this first, so §7.4 should answer it rather than leaving today's "or a host-language
enum value where the implementation provides a mapping". **An enum binds to the host type of the natural
parse of its members** — a host boolean at `boolean`, host text everywhere else — and `member_profile`
declares whether host *enum* generation is available. That is the contract this implementation already
runs: its enum atom binds to `String`, and a Java enum is reached through a replaceable bridge whose default
is name identity (`Enum.valueOf`), which is exactly the thing IDENTIFIER guarantees and TEXT withdraws.

The ecosystem answer for the TEXT case is settled and uniform — a name beside the value, never a restriction
on the value: `@XmlEnumValue("lightly active")` from `xs:enumeration`, `@JsonValue`/`@JsonCreator` from
jsonschema2pojo and openapi-generator, `#[serde(rename = "lightly active")]`, and OpenAPI's de-facto
`x-enum-varnames`. TypeScript and Python need no name at all (`type A = "sedentary" | "lightly active"`,
`Literal[...]`). So if TSON ever wants a codegen story, the additive move is a naming annotation in §6's
documentation category beside `title` and `deprecated` — which this proposal leaves open and the present
rule forecloses. **Names can be added to values later; values cannot be added to names.**

### The resulting three-way split, worth stating in §7.4 as one sentence each

| Intent | Spelling |
|---|---|
| a vocabulary of names | `!enum [OPEN ACTIVE DONE]` |
| a text value set | `!enum ["sedentary" "lightly active"]` with `member_profile: TEXT` |
| a value set on a type needed for its other facets | `!text ^ { length: 2  members: ["AU" "NZ"] }`, `!int16 ^ { members: [80 443] }` |

**The third row's text half is #22**, and it is a separate ask that stands whether or not this entry does.
With `enum` generalised it is no longer the answer to the 33.9% above — it becomes the narrower case of a
value set on a *constrained* text family, where the family's own parsing still applies.

### One question this entry does not settle

Under `TEXT`, what class does an *unquoted* numeric member carry — `!enum [80 443]`? The member is the text
`80`, but §5.4 reads the class off the token and gets number-class, which would make the enum number-class
while its members are text. The cleanest answer is that members under `TEXT` are written quoted, being text,
so the class is string and the case cannot arise; the spec should say which, since today the question has no
way to come up.

### A related Part 3 defect, already fixed

[TSON-JSON] §5.2 refused a JSON string at a `boolean` position, matching "booleans against their literals"
and strings only against identifier members. That contradicts §7.4 as published: a typed position is read by
its declared type and never by base type resolution, so `true` and `"true"` are one value at a `boolean`
field, and the form's special status is a §4.2 base-resolution rule "which a typed position never reaches".
The two encodings really did disagree — TSON text accepted `{ b: "true" }` where the JSON reader returned
TYPE_MISMATCH. Part 3 is drafted in this repository and has been corrected there; it is noted here because
the divergence is evidence that `enum` is under-specified at the seam this entry is about, and because the
JSON reader in this implementation still enforces the old rule and now needs the change.

---

## 22. `text_type` is the only tier with well-defined value identity and no member set

**Documents:** [TSON-SCHEMA] §7.4, §9, §5.7, §5.11.
**Kind:** omission — an asymmetry in the constraint vocabulary. **The resolution below is built and
running**: `text_member_set` beside `integer_member_set`, `members` a plain field on `text_type`, the
pattern/member coherence rule, and both facets settable once. `uri_type`, `regex_type` and `email_type`
inherit it through the composition they already use for the length rule.

### The asymmetry

Revision 36 put `members` on both exact numeric tiers — `integer_type` in the kernel, `decimal_type` in meta
— and correctly on neither approximate one, `float_type` and `rational_type` having no safe member equality.
So `members` sits on exactly the tiers where value identity is well defined. **Text identity is as well
defined as integer identity** — [TSON-DATA] §2.5's decoded-text equality, the same relation map keys and set
elements already use — and `text_type` carries `min_length`, `max_length`, `length` and `pattern` and no
member set. It is the only such tier without one.

The cost is the pattern fallback documented in #21: converting real schemas, a finite set of admitted
strings has to be written as an alternation, which is accept/reject-equivalent and diagnostically much
worse (`does not match the required pattern lightly active|moderately active|very active` against
`is not a member of this type -- expected one of […]`), and which obliges a converter to regex-escape every
member.

With `enum` generalised as #21 proposes, this is **not** the spelling for a bare text value set — that is
`!enum […]` with `member_profile: TEXT`. What it is for is a value set **on a type whose other facets are
also needed**, where the family's own parsing contract still applies: `!uri ^ { members: […] }` is three
admitted URIs that are still parsed as URIs, exactly as `!int16 ^ { members: [80 443] }` is a sixteen-bit
integer that happens to be sparse. The two entries are independent and either may land without the other.


### Proposed shape

```
text_member_set => !set_type { element_type: text }

text_type => atom & {
  min_length:  non_negative_integer?
  max_length:  non_negative_integer?
  length:      non_negative_integer?
  pattern:     regex?
  members:     text_member_set?
}
```

**A set, not an array**, for the three reasons the kernel already states for `integer_member_set`:
uniqueness comes from `set_type`'s own contract, non-emptiness from its `min_items` default (an empty member
set admits no value at all), and member identity is the family's own — "an array spelling … loses all
three".

**A plain field beside `pattern`, not a field group excluding it**, so that the shape mirrors
`integer_type`, where `members` composes with `size`, both bound groups and `multiple_of` and is checked
against them. Making text the one tier where a member set excludes a co-facet would be a line the
vocabulary does not otherwise draw — and it would draw it in the wrong place, because **a pattern is two
different things and nothing can tell them apart**. `a|b|c` is a value set, and beside `members` it is the
same statement twice; `[A-Z]{2}` is a *shape*, and beside `members` it composes exactly as a numeric range
does — narrow the space, then enumerate within it. Deciding which kind a given pattern is would be
regular-language containment, which the series decides nowhere. Excluding the pair would forbid the second
case to prevent the first; admitting it costs a reader one check that the coherence rule below has already
made for them.

**Coherence: the pattern MUST admit every member.** This is meta.tn's uniform `members` rule — "every
member satisfies the other facets on the same body or the schema fails to load" — applied, and the three
length facets take it unchanged. It is worth naming because it is the only member coherence check in the
series that needs a **regex match**: the others are comparisons. An implementation whose constraint model is
separated from its regex engine will feel the pull to split the rule in half by which engine each needs;
this one resolved it the other way, by having the constraint model depend on the engine, so that "every
member satisfies the other facets" is one rule checked in one place.

**Refinement: `pattern` and `members` are each settable once** — written in one body, thereafter restated
verbatim or left alone, never changed. A facet not yet set may still be set by a refinement, so the
narrowing case survives:

```
country_code => !text ^ { pattern: "[A-Z]{2}" }
nordic       => !country_code ^ { members: ["SE" "NO" "DK"] }     ; members set once, each matching [A-Z]{2}

nordic_core  => !nordic ^ { members: ["SE"] }                     ; resolver error -- members already set
latin_code   => !country_code ^ { pattern: "[a-z]+" }             ; resolver error -- pattern already set
```

The reason the pair shares one rule is that they occupy one logical position: both specify the admitted
value set, and `pattern` cannot be narrowed at all without a containment oracle. Giving the position two
refinement rules depending on which spelling the author reached for would make the narrowing relation an
artifact of notation.

### What this changes in §5.7, and the one asymmetry it introduces

**§5.7's facet-kind table has no category that covers `pattern` today.** It declares an ordered bound, a
step, a permission, a member set, a selector, and a fixed value, and `pattern` is none of them — so
"refinement can only restrict" currently has nothing to say about the one text facet that is not a count.
This entry proposes the missing kind: **settable once** — a facet a refinement may set if unset, restate
verbatim, or leave, and may never change. It sits one step looser than the relation §5.7 already states for
`bytes_type.encoding`, which "a refinement may neither set nor change". This implementation already runs
exactly this rule for `pattern` (`AtomNarrowing.checkSettableOnce`, whose diagnostic reads "whether one
narrows the other is not decided here, so a set pattern may be restated but not changed"), so the proposal
is to state what the gap was already being filled with.

**The asymmetry, stated plainly so a reviewer can refuse it if they disagree:** `text_type.members` would
be settable-once where `integer_type.members` and `decimal_type.members` shrink, under §5.7's member-set
rule that "a member set … may shrink to a subset, never grow or replace". §5.7 declares that rule once for
every family, and this would be the first family to depart from it. The reason it departs is local and does
not generalise: text is the only tier whose member set shares its position with a pattern, and the pattern
is the half that cannot be narrowed. A reviewer preferring uniformity would let `text_type.members` shrink
like the others — nothing breaks if it does, since a subset of a set whose members all matched the pattern
still does — at the price of the pair having two refinement rules.
