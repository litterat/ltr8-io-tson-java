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
`members` on the exact numeric tiers, the checked `@discriminator` and `@rest`, the value-space clause, the
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
`docs/` and the Javadoc name the section that requires a behaviour, and a `SPEC-FEEDBACK.md #N` citation is
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


## 5. A JSON member name that is not an identifier has no home, and it is the commonest shape in real JSON

**Documents:** [TSON-JSON] §6.1, §6.2; [TSON-DATA] §7.7; [TSON-SCHEMA] §6, §12.1.
**Kind:** underspecification, against a goal the document states for itself.

**This entry is a proposal, not a report.** Nothing here is running: `tson-json` has its lexer and no record
reader yet. What is evidenced is the gap, which is a reading of the published documents rather than a finding
from a build.

[TSON-JSON] §1.2 makes this document "the normative JSON interoperability surface of the series", and §1.3's
principle 2 promises that the common case reaches the wire as plain JSON a consumer with no knowledge of TSON
reads as ordinary JSON. The conversion path both sentences imply — a JSON Schema or OpenAPI contract becomes a
TSON schema, and the documents already in flight validate against it unchanged — meets a wall the documents do
not address.

**A JSON member name is an arbitrary string; a TSON field name is an identifier.** Revision 35 settled the
second half deliberately (`field-name` is an identifier at every layer, [TSON-DATA] §7.7, §2.5), and §3.2's
reserved-namespace argument depends on it. But `user-name`, `2fa_enabled`, `@type`, `first name` and `""` are
all ordinary JSON member names and none is an identifier. This is §7.7's **grammar**, not §8.2's policy, so no
processor configuration reaches it and no relaxation exists — which is correct, and is exactly why the gap
needs an answer somewhere else.

Kebab-case is pervasive; JSON-LD's `@context`/`@type` and OpenAPI's own `x-` extensions are `@`- and
`-`-bearing by specification. A converter meeting one has two options today and both are bad:

- **Collect them into an `@rest` map** (§6.2). The members read, and lose every declared type, facet and
  field state — discarding the validation that was the reason to convert. A contract whose ten fields are
  kebab-case converts to a record with no fields.
- **Refuse.** The producer changes their wire format, which is the outcome §1.3's principle 2 exists to
  prevent, and the on-ramp ends.

Neither is a decision an encoding-rules document should leave to each converter, because the two produce
schemas that disagree about what the same JSON means.

**Suggested resolution — a projection annotation, on `@rest`'s own precedent.** [TSON-SCHEMA] §6 already
carries the licence: "An encoding-rules document MAY bind projection behaviour to a schema-side annotation
declared for it." `@rest` and `@discriminator` both walked through that door in this revision, and a wire-name
projection is the same shape — force in the encodings that carry it, none in the model, the declared field
keeping its identifier name everywhere the name is a name.

```
web_hook => {
  @json_name:"user-name"     user_name:     text
  @json_name:"2fa_enabled"   two_fa_enabled: boolean
}
```

The load checks follow the established pattern and are few: the argument is a non-empty string that is a
well-formed member name under §3.1's profile; it does not begin with `$` (§3.2's namespace is not spellable
from data); and it collides with no other declared field's own name or projection within one composed record.
Decode's binding order in §6.2 gains one step — reserved names, declared names, **declared projections**,
rest collection — and the encoder writes the projection where it has one.

Three alternatives were considered and are worse. **Relaxing §7.7** to admit `-` and `@` in field names
undoes a Revision 35 decision, breaks §3.2's collision-free argument, and changes the *model* to serve one
encoding. **A `patternProperties`-style key map** types the values but not the names, so `user-name` and
`usr-name` validate alike. And **leaving it to `@rest`** is the status quo, whose cost is stated above.

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
position never reaches.

**Suggested resolution.** Add `boolean` to §5's table, in the same row group as `text`, with the parsing
contract "the tokens `true` and `false`, case-sensitive; any other token is a validation error". If the
omission is instead deliberate — on the ground that base type resolution already recovers a boolean from an
unquoted `true`, so the annotation is never *needed* — then §5 should say so, because the same argument
would remove `text` (§4.4 already recovers a string) and §5.5 explicitly keeps that one: the annotation
exists to **assert** the case where it is in doubt, which is exactly what a quoted `"true"` at a boolean
position is.

---

## 10. `@discriminator` is scoped to choices, where its mechanism cannot reach; it belongs to subtype families

**Documents:** [TSON-SCHEMA] §6 (`@discriminator`, `@rest`), §5.4 (choices, `@disjoint`), §5.7 (the refinement
transition table), §5.8 (composition), §5.11 (field groups), §7.2 (subsumption); [TSON-JSON] §6.1.5
(subsumption), §8.2 (the discrimination predicate), §8.4 (discriminated choices). Also OpenAPI 3.1's
`discriminator`, which the proposal is a conversion target for.
**Kind:** design proposal, plus two underspecifications in the check list §6 does state.

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

**The proposal: the target is a subtype family, not a choice.** The annotation moves to the field, in the base
type of a composed family, and is bare:

```
pet      => @sealed { @discriminator pet_type: text  name: text }
dog_type => pet & { pet_type: = "dog"  breed: text }
cat_type => pet & { pet_type: = "cat"  indoor: boolean }
```

A position typed `pet` — or `[pet]` — then admits any subtype under §7.2, and the `pet_type` member recovers
which. An explicit choice `( dog_type | cat_type )` is **out of scope** and keeps the variant tag it has today.

**The reason is structural, not a simplification.** `@discriminator` marks a *field*, so it can only exist where
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

1. **The mark becomes a bare marker on a field**, `@rest`'s shape rather than `@rest`'s standing — the
   `field_name` parameter disappears, and with it the only place in the series where an annotation names a field
   by string. Where the fact then *lives* is #11's question, and its answer is `record_field.discriminator`: the
   spelling is annotation-shaped and the resolver consumes it into the body, so the two marks §6 introduces
   together end up in different places, `@rest` genuinely an annotation and this one not.
2. **It is the shape converted contracts arrive in.** OpenAPI's `discriminator` sits on the *base* schema with
   subtypes composing it, which is this arrangement and not the choice one. A converted contract lands on the
   mechanism directly.
3. **The open-world argument strengthens §6's refusal to derive, and makes the mark constitutive.**
   A choice's variant list is closed and local, so a derivation over it can never be invalidated from outside.
   `subtypes` is **open** (§8.2): another schema may `!!import` `pet` and declare a third subtype. A derived
   discriminator would let that import silently flip the family from discriminated to not, breaking every
   existing producer with no diagnostic anywhere. Declared on the base, the mark is an obligation that
   propagates: a new subtype failing to pin the field is a resolver error **in the importing schema**, which is
   the schema that broke it. This makes `@discriminator` unlike `@disjoint` — it is not an assertion about a fact
   the resolver derives anyway, it is the declaration that creates the obligation.
4. **The mechanism is encoding-neutral, and both encodings claim it.** At a `pet`-typed field in text a value is
   likewise exactly `pet` without `!dog_type`, so structural recovery of the subtype is available to both
   encodings from one rule, and §6's "neither needed by TSON text" holds only for the choice framing. **In text
   the tag is not required at a SEALED position and must agree where it is written** — the same standing
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
| `discriminator.propertyName: petType` on the base | `@discriminator pet_type: text` on the base record |
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

**One shape does not convert, and the gap is worth stating.** OpenAPI also admits a `discriminator` on a schema whose
composition is `oneOf` with no shared base. There is no base record for the mark to stand on, so a converter must
either synthesise one — mint `pet => @sealed { @discriminator pet_type: text }` and compose each variant onto it, making
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
the annotation**, so taking it forces a kernel change; #11 is that change, and the base is SEALED there.

**On the name.** The field mark is not `@sealed`. It stands on a *field* — it is `@rest`'s shape, which is
consequence 1 — and `@sealed pet_type: text` describes the field rather than the family; moving it to the
declaration to fix that reintroduces either the field-name string this proposal removes or the derivation §6
refused. The two facts are separable in any case: a family may want the closed-set claim while its wire form stays
`$type`, which is why #11 carries them as distinct members of one enum, ABSTRACT and SEALED. `discriminator` is
also the word an author converting a contract will search for. The name is not idle: #11 spends it at the
*declaration*, where `@sealed` is the member's own mark and requires that this one appear in the body.

**The check list, restated for the family.** At the base: the declaration carries `@sealed` — the mark and the
mark on the field are each other's condition, below; the annotated field's declared type resolves, after its
reference chain, to an atom-family instance or an enum — *not* free here, because §5.2 grants that only to a field
carrying a value and the base's field carries none; its state is exactly REQUIRED, neither OPTIONAL (the base
could omit it), FIXED (nothing could override it) nor DEFAULT (a document could); and it is not a group member.
Which fields carry the mark is well defined over a composed chain by §5.8's restated-field rule, exactly as §6
already argues for `@rest` — what differs is that `@rest` admits one such field and a family admits several, the
tuple case below. Over the linked closure: every entry in `subtypes`, transitively, pins each marked field
`REQUIRED_FIXED`; and the pins are pairwise distinct.

**The two marks are each other's condition, and that is a deliberate redundancy.** `@discriminator` on a field
requires `@sealed` on the declaration, and `@abstract` on a declaration forbids `@discriminator` anywhere in its
body. Neither check is load-bearing for the resolver — with one rule the other fact follows — and that is the
point: it is the author who is being checked, not the schema. A family is either tag-dispatched or
member-dispatched, the two admit different documents, and the difference is one word at the top of a declaration
and one word deep inside it. Requiring both means the two words cannot drift apart silently, in either direction:
adding the field to an `@abstract` base fails, and removing it from a `@sealed` one fails. The cost is a word an
author must write twice; what it buys is that the intent is stated where each half of it is read.

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
variants. And a second `@discriminator` on an intermediate type is the nesting escape hatch, which this proposal
deliberately leaves out of the first design.

**What is running:** nothing. This implementation has built [TSON-JSON] §8.2's route 2 (kind disjointness over
class stability) and §6.1.5's `$type` subtype selection in tree mode, and the load-time checks §6 states
have never had a consumer — which is what surfaced the scoping question before any of it was built. The
[TSON-JSON] half of the change is made: §6.1.5 now reads the untagged object by the position's own extension
fact, §8.2 is one condition rather than two, §8.4 states why a choice has no discriminator, and §1.6 records
the Part 2 dependency as proposed rather than landed.

**Suggested resolution.** Retarget §6's `@discriminator` from a choice declaration to a record field, as a bare
`void` marker beside `@rest`, with the check list above and the §5.7 arrangement stated; name the equality
relation for pin distinctness and add the group-member check; state the abstract-base rule as a consequence of
the mark, and say what the shape is for — a sealed hierarchy in a host language — since that is what makes totality
and abstractness rules rather than preferences. Leave §5.4 untouched but add a pointer from it, since an author
reaching for member dispatch at a choice is an author who wants the composed family or the labelled form of §5.11.

---

## 11. A record cannot say how it may be realised, and the discriminated base is the case that forces it

**Documents:** [TSON-SCHEMA] §4.1 (base kinds), §5.2, §5.4 (`disjoint` as a derived-and-recorded fact), §5.7, §5.8
(composition), §5.9 (removal), §5.10.1 (productivity and inhabitance), §6 (what an annotation may do), §7.2
(subsumption), §8.1 (resolved output), §8.2 (identity), §12.1 (the schema grammar); [TSON-JSON] §6.1.5, §8.4.
Reads with #10, and is useful without it.
**Kind:** design proposal — one kernel field and the enum it takes; one naming choice left open.

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

**The proposal.** `record` gains one field, `record_field` gains one, and the kernel one enum:

```
record_extension_type => [ABSTRACT SEALED FINAL OPEN]

record => product & {
  access_pattern:  product_access_type = NAMED
  size_type:       product_size_type = FIXED
  fields:          [record_field]
  groups:          [field_group]?
  extension:       record_extension_type ~ OPEN
  supertypes:      [type_name]?
}

record_field => {
  name:           field_name
  type:           type_ref
  state:          field_state ~ REQUIRED
  discriminator:  boolean ~ false
  value:          value?
}
```

**`record_field.discriminator` and not an annotation, on three arguments that converge.** The erasure test above
is the first. The second is compilation: a discriminator field is read as a dispatch key rather than as an
ordinary field, and is non-elidable on the wire, in *both* encodings — a property of the field in the type
system, not of one encoding's projection of it. The third is the one the design forces on itself: `extension` is
SEALED exactly when the record is abstract and some field is a discriminator, so a kernel body field's value
would otherwise be a function of the annotation channel. Beyond the layering inversion, §6 resolves an annotation
one hop against the governing meta — so a schema whose meta does not declare `discriminator` could never reach
SEALED, making a kernel member's reachable values depend on which meta-schema governs the document. With the
field in the body the derivation reads the body alone, and every schema can express it exactly as every schema
can already express `state` and `value`.

**`@rest` stays an annotation, and the contrast is now sharp.** §6 introduced the two together as checked
annotations; the erasure test separates them. Erase `@rest` and the text and CBOR encodings are untouched — they
write the map nested — so its force really is confined to the encodings that claim it, which is §6's bullet
working as written. `@discriminator` had that standing only while text declined the directive.

**The four members.**

- **OPEN** — direct instances, and any schema in the closure may compose or refine onto it. Every record's
  behaviour today, and the default.
- **ABSTRACT** — no direct instances: no value's effective type is this record, and a position typed by it admits
  exactly its subtypes, each selected by the tag (`$type` in JSON, `!dog_type` in text), which is therefore
  REQUIRED at the position. No field of it may be a discriminator: that is SEALED's case, and the two marks are
  each other's condition (#10).
- **SEALED** — ABSTRACT plus member dispatch: the record carries one or more discriminator fields (#10) and a
  position typed by it selects the subtype by reading them. **The tag is optional at a SEALED position and MUST
  agree where written** — in both encodings, which is the standing [TSON-JSON] §8.4 already gives `$type`.
- **FINAL** — direct instances, and nothing may be a subtype: composition or refinement naming it is a resolver
  error, in the declaring schema and in any schema that imports it.

**Four marks in, two kernel fields out.** The author writes exactly one of `@abstract`, `@sealed` and `@final` at
the definition (`pet => @sealed { … }`; §6 honours a checked annotation at either position, so the key spelling
lowers identically), or none, and `@discriminator` on a field. Each mark names its member, and `extension` is a
function of which mark was written — OPEN where none was. Two definition marks on one declaration is a load error,
whichever two.

**The member is written and the body must agree, which is not where this started.** An earlier draft derived
SEALED — ABSTRACT with at least one discriminator field, both facts of the body — in the manner of
`choice.disjoint` (§5.4). The two marks make that redundant, and deliberately: `@discriminator` on a field
**requires** `@sealed` on the declaration, and `@abstract` **forbids** a discriminator anywhere in its body, so
the mark and the body condition imply each other and either could be dropped. Neither is, and the reason is not
the resolver's. A family is tag-dispatched or member-dispatched, the two admit different documents, and under a
derivation the whole difference is one word buried in a field list. Requiring the pair means the two halves cannot
drift apart in either direction: adding the field to an `@abstract` base fails, and removing it from a `@sealed`
one fails. The cost is a word written twice.

`@disjoint` is the nearest precedent and the analogy is now partial, which is worth stating rather than glossing.
`disjoint` is genuinely computed and the assertion is pure — absent, present-and-verified, present-and-refuted, and
the fact is the same in all three. `@sealed` carries ABSTRACT as well as asserting, and its absence is not silent:
a discriminator field without it is an error, where a non-disjoint choice without `@disjoint` is ordinary. What the
two share is the shape of the value — the check can only agree or fire, and what it buys is that someone else's
later edit cannot quietly invalidate the intent.

What the pair catches that a derivation could not is the **base** losing its `@discriminator`. Under a derivation
the family would degrade SEALED → ABSTRACT: the tag turns REQUIRED at every position typed by it, every untagged
document in the world stops validating, and nothing fails in the schema that changed. The marks make that edit
fail where it is made. A subtype that forgets its pin needs no mark to be caught — the closure rule is
unconditional — so this is the one failure the redundancy is actually for.

**A template may be abstract and may not be sealed or final, and the asymmetry is the marks' own.** §5.10 makes
a template not a type: only an application is, and each application mints its own entry. ABSTRACT constrains the
marked type alone — no direct instances — which is true of every instantiation identically and needs nothing else
known, so `result => @abstract <T> { … }` with `ok => <T> result<T> & { … }` is meaningful and is the shape a host
language spells `abstract class Result<T>`. **This is running.** §5.10 holds an open entry's body as the
application written out, so the mark is stated inside that text and materialisation reads it back through the
`record` constructor's own reader: closing `result<text>` yields an ABSTRACT entry, and the family it is abstract
over is the one §5.8's reference-valued `supertypes` builds — `ok<text>` is a member of `result<text>`'s and not
of `result<int32>`'s. The mark is written **before** the parameter list, §12.1 putting a declaration's
annotations ahead of the type-def that the parameters open. SEALED and FINAL are claims about *other*
declarations — that every subtype pins distinctly, that nothing composes onto this one — and a template has no
set for such a claim to range
over. `subtypes` is an index over entries (§8.2), and an instantiation entry exists only where some schema writes
that application, so the claim's subject would be assembled from whichever applications a closure happens to
contain: `ok<T>` composing onto `result<T>` puts nothing in `result<text>`'s index unless someone also writes
`ok<text>`, and writing it in a fourth schema would change the family without touching its declaration. **Both
marks are therefore a resolver error on a template**, and `@abstract` is not.

The disanalogy with the host language is worth stating, because the host language is where the intuition comes
from: Java's `sealed abstract class Result<T> permits Ok, Err` has one class carrying one permits list, and its
sealing is over classes rather than parameterisations. A TSON template has no such single carrier — there is no
entry for "the generic type", only one per application — which is the same fact from the other end. An author
wanting a sealed generic family seals a closed declaration and parameterises below it.

**Why the member carries it rather than the derivation.** ABSTRACT and SEALED differ in the *reading rule* and not
only in bookkeeping — at ABSTRACT the tag is required, at SEALED it is optional and asserting — so a compiler
reads one member and knows which reader to build, where a three-member enum would leave that difference to be
recomputed at every position from the presence of a marked field. The fourth combination the two underlying
questions admit, no direct instances and no subtypes, has no member and is therefore unrepresentable, which is
§5.4's move with `disjoint` and not a rule a document could break.

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
inhabited as any record is, an ABSTRACT or SEALED one exactly when one of its subtypes is, and §5.10.1's least
fixed point then rejects an abstract base with no subtypes as it rejects any other uninhabited entry. **Do not
adopt it.** The case it refuses is the one an abstract base most exists for: a library schema declaring
`response => @abstract { … }` and a field typed `response`, with every subtype supplied by the schemas that
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
{ … }` is OPEN by default whether `pet` is SEALED or OPEN, and it must be — otherwise no concrete subtype of an
abstract base could exist, which is every subtype there is. §5.7's transition table governs *field states*, whose
values a refinement inherits and may only tighten; `extension` is a property of the declaration and nothing
propagates it. The whole of the rule is two lines: composing or refining onto a FINAL record is a resolver error,
and every other declaration states its own member, defaulting to OPEN.

**Identity and output.** `extension` participates in §8.2 identity — an abstract `pet`, a sealed one, a concrete
one and a final one admit different values and are different types — and in resolved output follows §8.1's
convention, omitted at its default.

**On the member names.** ABSTRACT, FINAL and SEALED are Java's words in Java's senses: `abstract class`, `final`,
and `sealed` for the closed set with exhaustive dispatch. **C# spells FINAL's concept `sealed`**, so the two words
collide across major languages; this picks one language and uses it throughout rather than splitting the
difference. SEALED carries #10's caveat — the family is sealed relative to a governing namespace and extensible
by an importing schema under the pinning obligation — which is written down there rather than implied here.

**The naming left open — the field and the enum type.** Proposed: `extension: record_extension_type ~ OPEN`,
matching `access_pattern: product_access_type` in shape (a short field name beside an `<owner>_<concept>_type`
enum). "Extension" covers all four members: ABSTRACT and SEALED make extension mandatory and differ in how the
extension is selected, OPEN permits it, FINAL forbids it. Considered and not taken: `extensibility`, the same
sense but reading badly under the `_type` suffix; `derivation`, accurate but less current in the surrounding
vocabulary; `instantiation`, which names the ABSTRACT axis and says nothing about FINAL; and `record_kind`,
refused outright because §4.1 has already given "kind" to the four base kinds.

**The spelling is provisional, and deliberately so.** The four marks are annotation-shaped for now — `@abstract`,
`@sealed` and `@final` at the definition, `@discriminator` on a field — and all four are **consumed by the resolver
into the body** rather than preserved in §8.1's author-annotation channel: the first three into `record.extension`,
the last into `record_field.discriminator`. Consumption is the whole of what makes the interim legitimate — a mark
that lowers into the type is syntax wearing annotation clothing, and none of the four is an annotation in §6's sense
once it lands. One of these *preserved* would be the erasure violation twice over. It is also what makes the
arrangement temporary, since a construct that the resolver reads, that is absent from output, and that no schema may
redefine is a construct §12.1 should eventually spell. Two things the interim needs stated: the four names are
**reserved** at their positions, so a schema cannot mean something else by them; and resolved output carries the body
member and not the mark, so there is one carrier for the fact and §8.1's no-hoisting question does not arise.

**The interim splits the fact across two layers, which syntax is what resolves.** `record_extension_type` and the
two fields are the kernel's, since the body they sit in is; the four marks are declared in the meta-schema, beside
`@discriminator`'s old home and on `@doc`'s reachability terms. So the enum a mark names lives one layer below the
mark. Nothing breaks — an annotation resolves one hop against the governing meta (§3.3.3) and the meta imports the
kernel, so a schema governed by either finds all four — but a reader is entitled to ask why a kernel fact is spelled
by a meta-schema name. The answer is that the spelling is the part that moves: §12.1 spelling the marks puts the
notation in the same document as the fields, and the split closes with the annotations rather than being repaired
where it stands. Moving the declarations into the kernel meanwhile would put author-written vocabulary beside
`synthetic`, which is the resolver's own, and buy nothing a reader can observe.

**What is running:** the two kernel fields, the four marks, and the lowering.
`record_extension_type => !enum [ABSTRACT SEALED FINAL OPEN]`, `record.extension: record_extension_type ~ OPEN`
and `record_field.discriminator: boolean ~ false` are in this implementation's meta-kernel and bound by its value
model. meta.tn declares `abstract`, `sealed`, `final` and `discriminator`, all four `@annotation void`;
`@discriminator` has moved off `field_name`, so its old choice-level spelling is refused at the annotation's own
type. **The resolver consumes all four into the body** — the definition mark once, after the body is built, so a
fresh record, a composition and a refinement cannot disagree about it, and both annotation positions lower
identically. They are matched by name and never resolved against the governing meta, which is what reserves them:
they lower under a meta declaring none of them, where an ordinary unknown name is the author's error. Refused
while lowering: two definition marks on one declaration, a mark carrying a value, and a definition mark on a
non-record. A template carrying one is a gap, its body being held until materialisation closes it.

**The load-time checks run too**, one pass in the linker beside the disjointness derivation: nothing may compose
or refine onto a FINAL record while §5.9 subtraction stays admissible; a sealed record's selectors are REQUIRED,
no group member, and typed by an atom or an enum; every member of the closure pins each selector `REQUIRED_FIXED`;
and the pins are pairwise distinct as tuples, compared as *values* — `= 255` and `= 0xFF` collide, and so do `= 1`
and `= 1.0`. A family is re-judged whenever any part of it is local, so an importer adding an unpinned member, or
one colliding with an imported pin, is refused by the schema that added it.

**JSON reads a family**, which is the half this design exists for: `record.extension` picks the reader when the
schema compiles, so an ABSTRACT position requires its tag and decodes no member, a SEALED one places the value by
reading its discriminators, and OPEN and FINAL share the concrete reader — a FINAL record's admissible tag set is
empty by construction rather than by a check. The mapping is derived once at compile time and keyed by what the
pins compare as, so a schema pinning `= 0xFF` selects on a document writing `255`; a table keyed on tokens reads
that as unmatched. The selected member then re-reads the whole object, which re-verifies the pin as an ordinary
FIXED check and makes the dispatch read and the validation read agree by construction.

**The text encoding reads one too**, on the same terms and from the same rules: one dispatcher per position for
both read modes, the discriminator fields found by a rewinding lookahead because a record's fields have no
significant order, and the four refusals taken from the one `RecordExtensionDiagnostics` both stacks hold. A
cross-encoding parity test compares code, data pointer, `expected` and prose for every rule they share, and
passes — which is the evidence §9.4 asks for and not merely a claim that two readers were written from one
design. §8.2 identity does not yet carry `extension`, and cannot yet be made to: only a template instantiation
can mint an entry that holds a non-OPEN one — no synthetic is ever a record — and a mark on a template is
refused or gapped, so the collision the rule prevents is currently unreachable. It becomes one line the moment
a template can be abstract. The kernel's own three schemas resolve, link and compile unchanged —
every record OPEN, every field not a discriminator — which is the evidence that the fields cost nothing where
nothing uses them.

**Suggested resolution.** Add `extension: record_extension_type ~ OPEN` over `[ABSTRACT SEALED FINAL OPEN]` to the
kernel's `record` and `discriminator: boolean ~ false` to its `record_field`, stating the four members' meanings and
their two reading rules, the derivation of SEALED and the two load errors it yields, #10's checks over the
discriminator field, the FINAL check over composition and refinement, the subtraction exemption and why it is not one,
the reason inhabitance gains no case for it, the absence of any transition table, and the identity consequence;
state that a template may
be abstract and may not be sealed or final, with the reason; state that the marks are consumed rather than
preserved and that their names are reserved; state the two marks as each other's condition —
`@discriminator` requiring `@sealed`, `@abstract` forbidding a discriminator — and say that the redundancy is for
the author rather than the resolver; correct §6's validity claim; and settle the field and enum names, which is the
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
