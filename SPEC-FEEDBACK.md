# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation.
See `CLAUDE.md` for why this file exists and when to add to it. Spec quotes below are from
2026 Revision 36 — Part 1 (https://tson.io/raw/2026/36/tson-part1-data.md) and Part 2
(https://tson.io/raw/2026/36/tson-part2-schema.md) — unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a
suggested resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a
revision closes.** It is an input to the next revision's adjudication, so its numbering is the numbering
that revision's change log will answer against — a stable index of the open set, not an archive of
everything ever raised.

**Revision 36 closed seventeen of the twenty-one open against Revision 35**, and #1–#4 below are what
survives, all four carried open by that revision's change log. The closed entries are gone: the spec now
carries their rules — the three field slots, record extension and the `=?` selector, `record.supertypes` as
`type_ref`, the record-bodied template as a family base, a declaration naming an application as that
application's entry, the enum profile and `text_type`'s member set, class-stable `disjoint`, source order for
set elements, the host-type position in [TSON-DATA] §4.1, `!boolean`, the unavailable schema, and the
retirement of `@rest` and `@discriminator`. **This file is the as-built record**, not a pointer to one: where
an entry proposes a design this implementation has built, the entry states the design, what is running, and
what is not, so that a reviewer editing the spec needs nothing beside it. **Where the evidence is a consumer
of this library rather than this library** — #1 was found building the HTTP layer in `ltr8-io-tson-java-http`,
and this register is the collection point for all of it — the entry says so and states what is running there
on the same terms.

**Part 3 is not in this register.** [TSON-JSON] is an early draft this implementation exists to validate, and
`spec/tson-part3-json.md` is edited **directly** as findings arise — so a Part 3 finding becomes a spec change
in the same session, with git history as its record, rather than an entry waiting for adjudication. **The
numbers are not reused and the gaps are left** — the register renumbers only when a revision closes, so that
a citation written against the open set stays valid until then. What stays here is Parts 1 and 2, whose
current revision is published and whose changes this implementation proposes rather than makes. An entry
spanning both stays, and says which half is which.

**Cite the spec, not the argument that got it there:**
`design/` and the Javadoc name the section that requires a behaviour, and a `SPEC-FEEDBACK.md #N` citation is
for an entry below, where there is no section to point at yet. When an entry closes, its citations become spec
citations and the entry is deleted — nothing here is an archive.

**What is left is a set of directions rather than defects.** Each of #1–#4 is a place the series stops short
of a rule on purpose: where a deployment's policy lives (#1), whether a namespace should be a value (#2), how
a declared field carries a JSON member name that is not an identifier (#3), and how a declared application
keeps a content-derived identity across the import merge (#4). None is a defect in a rule the spec states.

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

**Interpretation chosen:** all three policies are code calls on `ProcessorConfig` (`withIdentifierPolicy`,
`withTokenPolicy`, `withLimits`), with no artifact of any kind; `Tson.processorPolicy()`, `Tson.limitsPolicy()`,
either read facade's, and `tson policy` are the no-document-in-hand surfaces §8.2 and §9.1 ask for. The
consuming HTTP project leaves them at this library's defaults with its position written down in prose rather
than expressed in a document — which is the gap this entry reports, met from the other side.

**Suggested resolution** (a proposal — nothing here is built): name the third artifact kind, say that it is
data rather than a schema and why, and make the second constraint normative beside the first — no `!!import`
of a descriptor and no document able to name one. Failing that, the placeholder sentence is a reasonable place
to stop, and this entry is content to be answered with "not this revision."

**Status against Revision 36:** open, carried unchanged. Revision 34 introduced the policy layer that had
nowhere to live; Revision 35 gave it everywhere to be *reported* and left where it lives undefined on purpose;
Revision 36 carries the entry open with both constraints recorded, and §8.2's closing sentence stands as the
placeholder it is. Adopting this entry is a new section; declining it costs nothing that is currently broken.

---

## 2. A namespace should be a value — the kernel's 2×2 has an empty cell

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

Measured under Revision 35's field syntax: `create_order` resolves with `supertypes: [place_order,
method<order, order>, http]` and `verb`, `path`, `status` pinned; `!create_order { request: { sku: A-100
quantity: 2 } }` reads as a valid value; the same value with `verb: GET` is refused. The operation IS-A its
method, the compiler checks the reference, and a plan step is a value of the method type — the thing a `data`
entry can never be. Revision 36's three field slots re-spell the defaulted and voidable fields
(`response?: Resp`, `safe?: boolean ~ false`, `errors?: [sku_not_found]`) and change nothing in the argument.

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

**Status against Revision 36:** open, and **deliberately held over a second cycle: the shape needs further
investigation before anything is built against it.**

Publishing is not what stands in the way, and saying so matters because the reason first given here was that
it was. It read: every route changes the meta-kernel, the kernel is a published hash-pinned artifact
([TSON-SCHEMA] §10, §13.2), and nothing can be built without minting digests for a document nobody has
published. That constraint is gone. This branch moved all three companion artifacts to `/2026/36/`
identities precisely so that a revision's own proposals could be built against artifacts named for it, and
every built proposal Revisions 35 and 36 adopted — the `scoped` constructor, the `bytes` redesign, the
three field slots, record extension and the selector, the enum profile — landed on that basis.

What stands in the way is the design. A namespace value is not one addition but a question about what the
kernel's 2×2 is for, and the entry above sketches a cell rather than settles one — so it is held over rather
than implemented ahead of an answer. That is a different state from the other entries here: each of those
is a gap with a known shape, where this one is a direction whose shape is the open question.

One thing Revision 35 changed on this side is worth recording, because it removes an objection rather than
answering the entry. The `~` marker and `type_definition.constructor` are gone, and applicability is IS-A
`top` (§3.3.1, §4.2) — so the modelling above, which was written "with no meta layer and no `~` at all" to
avoid the marker, is now simply how a constructor is declared. The measurement it rests on stands unchanged.

---

## 3. A JSON member name that is not an identifier can be carried only at a map-typed position

**Documents:** [TSON-SCHEMA] §6 (the representation-directive category), §12.1; [TSON-DATA] §7.7, §7.2.5;
[TSON-JSON] §6.1, §6.1.1.
**Kind:** proposal — the first member of a category Revision 36 defined and left empty.

**This entry is a proposal, not a report.** Nothing here is running: `tson-json` reads records in tree and
bind mode, and a member name that is not an identifier is refused where it is read, with no projection to
declare. What is evidenced is the gap, which is a reading of the published documents rather than a finding
from a build.

**What Revision 36 settled.** §6 now defines a representation directive's **class** — the encodings whose
encoding-rules documents declare, by the directive's name, that they consume it — and the category has no
member. That was the prerequisite this proposal named; what is left is the annotation itself.

**A JSON member name is an arbitrary string; a TSON field name is an identifier — but the gap is narrower
than it looks.** `field-name` is an identifier at every layer ([TSON-DATA] §7.7, §2.5), and [TSON-JSON]
§3.2's reserved-namespace argument depends on it. §7.7's `identifier-continue = XID_Continue / "-"` admits
the hyphen, so **kebab-case is unaffected** — `user-name`, `Content-Type` and OpenAPI's `x-` extensions are
all valid TSON field names, and §7.2.5 confirms the intent ("negative numbers and hyphenated names are
unaffected").

What remains outside the grammar is the name that is `@`-initial (JSON-LD's `@context` and `@type`),
digit-initial (`2fa_enabled`), underscore-initial (`_id`, pervasive in MongoDB-derived documents),
space-bearing, or empty. This is §7.7's **grammar**, not §8.2's policy, so no processor configuration
reaches it and no relaxation exists — which is correct, and is exactly why the gap needs an answer somewhere
else. The answer today is to type the position as a map throughout and forgo per-field validation, which is
honest and total but is not a conversion: a contract whose ten fields are `@`-initial converts to a record
with no fields.

**Suggested resolution — a projection annotation.** §6 carries the licence: "An encoding-rules document MAY
bind projection behaviour to a schema-side annotation declared for it." This would be the first member of
the representation-directive category, and it is the shape the licence was written for: force in the
encodings that claim it, none in the model, the declared field keeping its identifier name everywhere the
name is a name.

```
web_hook => {
  @json_name:"@context"      context:        text
  @json_name:"2fa_enabled"   two_fa_enabled: boolean
}
```

The load checks follow the established pattern and are few: the argument is a non-empty string that is a
well-formed member name under [TSON-JSON] §3.1's profile; it does not begin with `$` (§3.2's namespace is
not spellable from data); and it collides with no other declared field's own name or projection within one
composed record. Decode's binding order at [TSON-JSON] §6.1.1 gains one step — reserved names, declared
names, **declared projections** — and the encoder writes the projection where it has one. [TSON-JSON]
declares consumption of `json_name` by name, which is what §6's class definition asks of it.

Three alternatives were considered and are worse. **Relaxing §7.7** to admit `@` and a digit-initial form
undoes a Revision 35 decision, breaks [TSON-JSON] §3.2's collision-free argument, and changes the *model* to
serve one encoding. **A `patternProperties`-style key map** types the values but not the names, so
`user-name` and `usr-name` validate alike. And **the map-typed position** is the status quo, whose cost is
stated above.

**Status against Revision 36:** open — the annotation is not taken this revision; §6's class definition,
which it needed, is.

---
## 4. A declared application has no content-derived name, so the import merge cannot unify it

**Documents:** [TSON-SCHEMA] §8.2 (*Two identities*, determinism, *Non-exposure and the import merge*),
§2.2.3 (the transitive import merge), §3.3.4 (`subtypes` open across schemas).
**Kind:** proposal — the one property §8.2's declared-application rule gives up, and a way to keep it.

**What Revision 36 settled.** A declaration whose body is a fully-bound application **is** that
application's entry, closed in place under the author's name, with nothing minted beside it; a use-site
application resolves to a declaration owning it; and two declarations naming one application are two
entries (§8.2). That is what this implementation runs: `bx => box<text>` resolves to `bx => !record { … }`
with `source` the canonical application, and no `box_text_…` is ever created. `pet.subtypes` reads
`[dogs, cats]`, a colliding-pin refusal names the declarations, and a read reports `dogs` as the type — not a
hash-bearing minted name.

**What it gives up, which §8.2 now states.** A content-derived name is a function of the form alone, which
is what lets two independently resolved namespaces agree on it — §8.2's determinism SHOULD, and the merge by
structural identity its import-merge paragraph describes. A declared name cannot carry that: a schema writing
`box<text>` that has never seen the schema declaring `bx` mints the content name while that schema calls it
`bx`, so the merge sees two names for one form. A use site in another schema cannot name what it has not
heard of (§3.3.4), so no author's name can close the gap.

**Suggested resolution:** keep the content-derived name as a **merge key** beside the declared entry — not
an entry, not a name a use site may write, but the identity the import merge unifies on — so that an
importing schema's `box<text>` and an imported `bx` denote one entry, and §8.2's determinism SHOULD keeps
its subject across the merge. The declared name stays the entry's name for every surface a consumer sees;
the key is the resolver's.

**Interpretation chosen:** no merge key. Nothing in this implementation depends on cross-schema
unification of a declared application today, and no bundled schema or corpus vector exercises an import
that would need it — so the property is given up exactly as §8.2 says, and the proposal is unmeasured.

**Status against Revision 36:** open — the rule is taken, the merge key is carried until an import-merge
path exercises it.

