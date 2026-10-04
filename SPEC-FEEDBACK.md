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

**What is left is a set of directions rather than defects.** Each of #1–#4 is a place the series stops short of a
rule on purpose: where a deployment's policy lives (#1), whether a namespace should be a value (#2), how a
declared field carries a JSON member name that is not an identifier (#3), and where a family member may be
written, which keeps §8.2's duplicate forms harmless across the import merge (#4). None is a defect in a rule the
spec states. #5, raised against Revision 36 itself, is one: two rules in §7.8 give one document two categories.
#6–#9 are directions again: a bounded type slot, which lets one field's type depend on another's; `identifier` as
a text family, which carries name hygiene to identifier-typed map keys and gives `enum` a label type; a
constructor each for `value` and `void`, which retires `unit` and dispatch by name; and a recorded type for each
template parameter, derived from its uses, which checks an application at the call site and gives #6's bound its
home.
#10 makes order a facet every container states, which a map needs before #2's keyed sets can be maps.
#11 drops `set_type`'s non-empty default, so a set's bounds are an array's and the empty set is a set.
#12 adds `tuple1<T>` and `voidable_tuple1<T>` to core, the one-position tuple the bracket sugar cannot spell.
#13 is part defect and part proposal: `uri` cites RFC 3986's URI and was read as its URI-reference, so the two
become two atoms, `uri` and `uri_reference`, and `uri_type`'s facets take RFC 3986's other distinctions: a scheme
set in place of one scheme, and a fragment permission.
#14 adds RFC 3987's IRI as a family of its own, `iri_type`, with core's `iri` and `iri_reference`, holds `uri` to
US-ASCII, and reads a directive argument as an IRI-reference.
#15 keeps core to the types a schema cannot do without, since every name core declares is one no importing schema
may: `identifier`, the four sign-bound integers, `non_empty_text`, `annotation` and `documentation` leave it, and
the sign bounds leave [TSON-DATA] §5.6's built-in vocabulary with them. The kernel's `documentation` goes too, so
`doc` is `@annotation text` in both.
#16 reshapes meta's annotation vocabulary: `todo`, `since` and `lang` leave it, `deprecated` becomes a void marker,
and `comment` joins it for JSON Schema's `$comment`.
#17 makes `@doc`'s text CommonMark, without extensions and with raw HTML never executed.
#18 lets a field group's option hold several fields, with `+` as sugar for "at least one of", so that rule,
both-or-neither and one key requiring another have a spelling, and refuses any group that restates plain fields
or another group.
#19 moves `normalization` to `text_type` and makes it the form a value is put into, rather than one it must
already be in, with `ASCII_CASEFOLD` and `NFKC_CASEFOLD` so a case-insensitive naming system's names are one value
however they are cased.
#20 states what a leap second is, which [TSON-DATA] §5.4 leaves between RFC 3339's grammar and the time-of-day
interval, and rewords §5.5's `precision` sentence so reading and writing are told apart.
#21 gives the series' two kinds of nothing one name each — `optional` for a slot that may be missing, `voidable`
for one whose value may be void — retiring `element_state`, and makes the prose's "absent" the type's "void".

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

**Read against #6 and #7: filling the cell by reference, not by containment.** #6's bounded type slot and #7's
identifier-as-text-family change what the 2×2 asks for, and most of the entry can be had without a namespace
body kind — none of the third grammar recursion point, nested scoping or recursive resolver output.

- **The rows become the key's type.** Under #7, a key is a name exactly when its type is `identifier` or refines
  it, and meets §8.2 then; otherwise it is data. "Keys are names" against "keys are data" stops being record
  against map.
- **The columns become the type slot.** Under #6, a position whose values are declarations is a type slot, and
  the kernel has many: `record_field.type`, `choice.variants`, `map.value_type`. The empty cell is then a map
  with data keys whose values are bounded type slots — `{route => <: method>}` — which the kernel can state.
  This needs #6's bound in an **unnamed** form: a map value cannot bind a name, each entry naming a different
  type, so the bounded reference exists as its own type (`<: method>`, or a `type_of<method>` template) and
  `<T: X>` is the sugar that also binds `T`.

Three steps, each usable alone:

1. **The unchecked reference is #6 alone.** The hit above, `method: plaec_order`, is closed by typing the slot:

   ```
   binding      => { method: <M: method>  verb: http_verb  path: text }
   create_order => !binding { method: place_order  verb: POST  path: "/orders" }
   ```

   A name that does not resolve, or does not IS-A `method`, is a resolver error. No namespace and no `data` kind
   question is involved.

2. **An interface is a record type, reached by projection.**

   ```
   crud         => <T> { create: method<T, T>  get: method<id, T> }
   orders       => crud<order> & { cancel: method<id, order> }
   create_order => orders.create & http & { … }
   ```

   Points 2 and 3 above hold with no new body kind: `&` extends an interface, `^` pins across it, `-` is the
   subset exposure, a template is per interface, and the field names are already one of §11.4's scopes. What is
   added is a **projection type** at type-ref and `!name` positions — `orders.create`, the declared type of
   `orders`' field `create`, as TypeScript's `Orders["create"]` — which is the `qualified-name` production of the
   costs paragraph used to reach a field's type rather than a nested scope. The kernel reserves `.` in
   `identifier` "as a future identifier separator". One gap: an interface's own instances, a record holding one
   value per method, mean nothing, and `ABSTRACT` does not say so — it means "only subtypes are instances".

3. **The route table is data about types.**

   ```
   api => { route => { http_verb => <: method> } }
   ```

   The API description is a value of `api` — a data document, or a fixed value — whose leaves are bounded
   references (`orders.create`), resolved in the governing namespace as `scoped`'s LOCAL cell resolves a `$type`
   (§7.8), and checked against the bound. It answers the smell the method-as-type measurement showed: routing
   facts live in the table, not as fields injected into every instance, so a plan step does not carry its URL.
   And it keeps point 1: a route key is data, needs no hygiene, and a member addressed by its route needs no
   invented operation name, since the table names `orders.create` directly.

What this leaves open:

- **The `data` kind.** Methods as record templates, interfaces as records and routes as data cover §4.1's
  motivating case, which is point 4's consequence arrived at; it is still to be confirmed rather than assumed.
- **Containment.** By reference, every member has a name. For methods that costs nothing — the interface names
  them — but a declaration written inline at a route, with no name at all, still needs a namespace body. The
  open question narrows to whether an anonymous inline member is worth the third recursion point, everything
  else the entry wanted coming from #6, #7 and projection.

Dependencies: step 1 needs #6; step 2 needs only the projection production and no kernel change; step 3 needs
#6's unnamed bound and a rule that a type-slot value in a data document resolves in the governing namespace.

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
## 4. A family member applied at a use site has no name, which is where §8.2's duplicate forms stop being harmless

**Documents:** [TSON-SCHEMA] §8.2 (*Two identities*, determinism, *Non-exposure and the import merge*),
§2.2.3 (the transitive import merge), §3.3.4 (`subtypes` open across schemas), §5.2 and §5.7 (a sealed
family's pins), §6.1.5 (a tag names the selected member).
**Kind:** proposal — one rule that follows from §3.3.4 and §8.2, and one sentence on where a merged family is
judged.

**What Revision 36 settled.** A declaration whose body is a fully-bound application **is** that
application's entry, closed in place under the author's name, with nothing minted beside it; a use-site
application resolves to a declaration owning it; and two declarations naming one application are two
entries (§8.2). That is what this implementation runs: `bx => box<text>` resolves to `bx => !record { … }`
with `source` the canonical application, and no `box_text_…` is ever created. `pet.subtypes` reads
`[dogs, cats]`, a colliding-pin refusal names the declarations, and a read reports `dogs` as the type — not a
hash-bearing minted name.

**What it gives up, and where that matters.** A declared name is not a function of the form, so a schema
writing `box<text>` that has never seen the schema declaring `bx` mints the content name, and a schema
importing both holds two entries for one form. Outside a family that costs nothing a value can tell: both
entries admit exactly the same values, and a use site in the importer resolves to the declaration owning the
form. Inside a family it does. With `pet => abstract { pet_type: text =? … }` and `dog_of => <T> pet & {
pet_type?: = "dog"  breed: T }`, schema A declares `dogs => dog_of<text>` and schema B writes `kennel => { k:
dog_of<text> }`. Each links. A schema importing both holds two members of `pet` pinning `"dog"`: the pair §5.7
refuses inside one schema, and either refused across the merge — two correct schemas that cannot be imported
together — or dispatched to one of two. B's member is defective on its own as well: a `pet` position in B can
select it, and a read then reports a minted name, a tag cannot name it (§6.1.5, since a data tag is a bare
name and a minted one is unwritable), and a binding cannot map it. That is the hash-bearing name Revision 36
removed from declared applications, reached through a use site.

**Suggested resolution: extending a family is a declaration.** "A template application at a use site whose
result composes onto a record — so is a member of that record's family — is a resolver error; a family member
is declared." It is not a special case but what §3.3.4 and §8.2 already imply together: a member changes what
every position typed by its base does, in every schema that imports it, and is read, tagged and bound by name,
while a minted name may never be written. It is drawn at membership rather than at sealing, since a minted
member of an open family is unreachable too — an untagged value at its base needs a tag it cannot carry — and
it leaves every other use-site application alone: arrays, sets, choices, and applications of a family's base,
which compose onto nothing. With it, §8.2's duplicates are never family members, and the "accepted by-product"
reading of them holds without exception.

And one sentence for §3.3.4: **a family is judged over the closure that holds it**, so two members brought
together only by an import merge — each from a schema that linked cleanly — are the importing schema's error.
Judging only families with a member declared locally lets that schema load and dispatch on a mapping that is
not a function.

**Alternative considered: a merge key.** Keeping the content-derived name beside a declared entry as the
identity the import merge unifies on would make `dogs` and B's `dog_of<text>` one entry. It solves only the
duplicate, leaves B's member unnameable, and adds a second identity to every declared application; the rule
above removes the case the key existed for.

**Interpretation chosen:** both, as written. The linker refuses a minted entry that composes onto a record,
reported at the declaration that wrote the application (`kennel`, or `kt` for `kt => kennel_of<text>` where
`kennel_of => <T> { d: dog_of<T> }`), naming the application and the fix (`my_name => dog_of<text>`). Every
sealed family in the closure is checked, and a collision between two imported members is reported against the
importing schema with both origins named.

**Status against Revision 36:** open.

## 5. §7.8 gives a scope push at a `declared` position two categories

**Section:** [TSON-SCHEMA] §7.8 (the cell rule, and the typed-position restriction); the same two sentences
recur in [TSON-JSON] §3.3 and §8.5.

**Kind:** defect — internal inconsistency.

**The two rules.** The cell rule: "A cell the instance's `scope` does not hold refuses the value it would
have taken, as a validation error: nothing failed to resolve". The typed-position restriction: "A nested
`!!schema` directive is admitted at a position exactly when the position's type resolves to a `scoped`
instance whose `scope` holds `EXTERN` … Anywhere else it is a resolver error." A `!!schema` at a position
typed `declared` (`scope: [LOCAL]`) satisfies both: its type is a `scoped` instance whose scope does not hold
`EXTERN`, so the restriction makes it a resolver error, and it is a value in the EXTERN cell the instance does
not hold, so the cell rule makes it a validation error. [TSON-DATA] §8.1's categories are exclusive, so one
of the two is wrong for this document.

**Interpretation chosen:** a validation error. The cell rule is the specific one — it names exactly this case,
and its reason ("nothing failed to resolve") holds: the directive is well-formed and the position is one that
reads a scope's cells. The restriction then reaches only positions whose type is **not** a `scoped` instance —
a record, a choice, a `value` — where a push has no cell to be refused by. Both encodings run it that way:
`ScopedReader` in the text reader, and `DispatchScopedReader` for [TSON-JSON] §8.5's `$schema`, with
`CrossEncodingParityTest` pinning that the two agree.

**Suggested resolution:** scope the restriction to positions that are not scoped — "a nested `!!schema` at a
position whose type is not a `scoped` instance, or a container of one, is a resolver error; at a `scoped`
position the cell rule decides" — and state the cell rule's validation error as covering the push at a
`declared` position by name, as [TSON-JSON] §8.5 already does ("a `$schema` at a `declared` position").

**Status against Revision 36:** open. [TSON-JSON] §3.3 and §8.5 state the chosen reading.

---

## 6. A type slot cannot be bounded, and a field cannot depend on another field's type

**Section:** [TSON-SCHEMA] §5.2 (which fields may carry a value; value conformance), §5.3 (lifts), §5.7 (facet
kinds under refinement), §5.10 (template parameters), §7.4 (the enum profile), §8.1 (`record_field`, the
constructor type slots); the grammar's `type-params` and `field-type` productions.

**Kind:** proposal — one mechanism for two things the kernel states in prose today, and the syntax for it.

**The two gaps.** Every type slot in the series is a field typed `type_ref` — `array.element_type`,
`map.key_type`, `record_field.type` — and a `type_ref` names *any* type. There is no way to say *a type that
IS-A `text`*, at a field or at a template parameter (`<T>` is unbounded, §5.10). And no field can refer to the
type another field names, so a dependency between two fields of one constructor lives in prose: §5.2's value
conformance exists because `record_field.value: value` cannot point at `record_field.type`, and states the
dependency normatively for that reason.

**Where it is hit.**

- **`enum` stating its member type.** If `identifier` is declared as a text family — `identifier_type =>
  text_type & atom_specification & { spec?: = "<[TSON-DATA] §7.7>" }` and `identifier => !identifier_type {}`,
  the shape `uri_type` already has — then §7.4's profile table becomes derivable from one question, *is the
  member type `identifier` or a refinement of it?*, and `enum` could state that type in place of `profile`. An
  author could then write `type: currency_code`, where `currency_code => !text ^ { length: 3  pattern:
  "[A-Z]{3}" }`, and have every member checked against it, where a `TEXT` enum's members today are any text. The
  field wants a bound (IS-A `text`), a default (`identifier`, which §5.2 refuses on a record-typed field), and
  its members typed by it. #7's Proposal 2 now holds the type as a `type_name` field pinned by two tightenings,
  with the bound and member conformance as §7.4 rules; this slot would make them structure.
- **A template parameter.** `<T>` admits any type, so a template meaning only text families cannot say so, and a
  wrong argument fails deep inside the materialised body rather than at the application. #9 builds this half.
- **A consumer's meta layer.** `ltr8-io-tson-java-http`'s HTTP vocabulary types a path parameter with a
  `type_ref`, and a URL segment cannot carry a record; its schema records that nothing enforces the restriction.

**Suggested resolution: a bounded, binding parameter at a field's type.** One production, used in two places:

```
type-param = param-name [ws ":" ws type-ref]
type-params = "<" ws type-param *( separator type-param ) [ ws "," ] ws ">"
field-type  = ( "<" ws type-param ws ">" / type-ref ) ["?"]
```

At a template, `<T: text>` bounds the parameter: an argument must resolve to `text` or a type that IS-A it, or
the application is a resolver error. At a field's type, `<T: text>` declares a type slot — a field whose value
is a reference bounded the same way — and binds `T` for the rest of the body:

```
enum_type => atom & {
  type:    <T: text>
  members: set<T>
}

record_field => {
  type:   <T>
  value?: T
  …
}
```

What this settles:

- **Member conformance is structural.** `members: set<T>` checks each member against `type` as a set checks
  any element; no prose rule states it.
- **§5.2's value conformance is structural** by the same mechanism, unbounded.
- **A type slot's value has a place.** `enum => enum_type ^ { type?: = identifier }` pins a single type-name
  token on a type slot; §5.2 admits it on a field declared with a type-param, and every existing enum resolves
  unchanged, `type` injected as it is under #7 (§5.6).
- **Refinement gains one facet kind.** A type slot narrows along IS-A: a refinement may restate it only with a
  subtype of the source's value. `enum_type` does not need it: #7 fixes an enum's `type` at construction.
- **The bound follows IS-A edges only.** `date` has a text form without being `text`; a bound that followed a
  type's form or its discrimination class would admit it. The rule must say so.

**Why the colon, and why at the field.** The colon is the bound in Rust, Swift and Kotlin (`<T: Display>`),
and it reads as TSON's own field syntax does — `name: type`, the values of `type`. Several bounds compose with
`&`, as Java's `T extends A & B` does. The grammar has room: `type-params` holds bare parameter names today, and
no alternative of `type-ref` begins with `<`, so a `<` at a field type is decided on one token, the property
the `instance` production already relies on. Placing the parameter inside the constructor, rather than wrapping
the constructor in a template as `set => <T> !set_type { element_type: T }` does, is what lets one field refer
to another: a template's parameters are bound by the application, before the body is read, and a template
cannot be mixed into a type body.

**What it costs.**

1. **A dependent record, which the series has not had.** A template's `T` is bound by whoever applies it; this
   `T` is bound by a field value in the data — `!enum_type { type: currency_code  members: [USD EUR] }` — and a later
   field's type depends on it. §5.2 and §8.1 must say so.
2. **Field order.** A streaming reader of a schema document read as data would meet `members` before `type` if
   the author wrote it so, and would buffer it. Requiring the binding field to precede its uses removes the
   buffer; §5.4 already puts a dispatch-order requirement on selectors.
3. **A resolved form.** The sugar needs output the kernel can state: plausibly a lift (§5.3) to a synthetic
   entry of a bounded-reference constructor carrying `bound: type_ref`, and a record-level statement of which
   field binds which parameter. Using the field's own name as the binder (`members: set<type>`) saves the name
   but puts field names in the type namespace, where §5.10's shadowing rule does not reach.
4. **A meta-kernel change.** `record_field` and `enum_type` change shape, which a published revision cannot carry.

**Open: what a bound may name.** `<T: text>` is a type bound. §5.2's other restriction — a value only on a field
typed by an atom-family instance or an enum — and the HTTP layer's "any scalar" are bounds on a **base kind**,
not on a type. If a bound may name a kind as well as a type, both become structural; if not, they stay in prose
and the mechanism serves `enum` and template parameters only.

**Interpretation chosen:** the template half, through #9: `<T: text>` bounds a template parameter, recorded as
`template_param.bound` and checked at each application. The field half is not built: no field declares a type
slot, #7's Proposal 2 holds an enum's `type` as a `type_name` field and states its bound and member conformance
as §7.4 rules the linker checks, and §5.2's conformance is enforced by the resolver as prose requires.

**Status against Revision 36:** open, and **deferred to Revision 38.** The field half changes `record_field` and
`enum_type`, and the template half it would generalise has no use yet: no declaration in the bundled schemas
writes a typed or bounded parameter, so #9's `template_param.type` and `bound` are running but unproven as a
shape, and building a second mechanism on them first would fix that shape twice.

---

## 7. `identifier` should be a text family — which reaches identifier-typed map keys, and lets `enum` state its type

**Section:** [TSON-SCHEMA] §5.4 (discrimination class), §5.7 (refinement), §7.4 (enum member semantics, the
`identifier` primitive), §11.4 (name hygiene at the schema layer); [TSON-DATA] §2.6 (map keys are values), §7.7
(the identifier grammar), §8.2 (name hygiene); UAX #31 (R1); the meta-kernel's `unit`, `identifier`, `text_type`,
`enum_profile` and `enum`. Proposal 2 builds on Proposal 1.

**Kind:** proposal, with one gap in the current text underneath it.

**The gap.** [TSON-DATA] §8.2's mechanisms reach *declared names* — a schema's declarations, a record's fields,
an `IDENTIFIER` enum's members — and §11.4 lists the scopes the look-alike mechanism runs over. A map key is a
value (§2.6), so none of it reaches one. Two keys of one map with equal UTS #39 skeletons (`admin` and `аdmin`,
the second with U+0430), or a mixed-script key, are admitted, where the same two names as two fields of a record
or two declarations of a schema are refused under the default Highly Restrictive identifier policy. That holds
when the key type is the kernel's `identifier` or a role over it: the key must match §7.7's grammar, and nothing
else follows. **This implementation runs exactly that on `main`:** a value at an `identifier`-typed position is checked
against §7.7 and against no §8.2 mechanism.

The text is also not clear that this is intended. §8.2 says mechanisms 2 and 3 "reach every identifier position
— field names, annotation names, type-annotation names, and every naming position of the schema grammar"; §7.4
says "`identifier` is not used in data values". Yet `identifier`, `type_name` and `field_name` are ordinary
kernel types, and a meta-layer schema may type a map key by one. Whether a value at such a position is an
"identifier position" is decided by neither sentence.

**Why it matters.** A map keyed by names is a naming scope in every sense §8.2 means: names a reader must tell
apart, in one document, where confusing two of them is the attack. An interface's methods, or a service's
routes, kept as `{method_name => handler}` are that — and a design that moves members from record fields into
such a map, which is what a borrowed namespace looks like and what §4.1's `data` kind exists to make possible,
takes the spoofing surface with it and leaves the rules behind.

**Why a type and not a rule about maps.** At a map position `admin` and `аdmin` may be two legitimately distinct
keys, and nothing about the position says they are names; [TSON-JSON] accepts look-alike keys at every JSON map
position for that reason. A key whose declared type is an identifier is the schema saying they are names. So
the line runs through the key's type — `text` keys are data, judged by the token policy; identifier keys are
names, judged by the identifier policy — and what is missing is that `identifier` has no constraint vocabulary
for the rules to belong to. The kernel declares it `identifier => !unit {}`, and its grammar and §8.2's rules
reach the kernel's naming positions through §7.4's prose rather than through the type.

**Proposal 1 — `identifier` becomes a text family.** Declared the way `uri`, `regex` and `email` are, as a
`text_type` composition, but with no `atom_specification`: the grammar is the kernel's own, stated in its `@doc`,
and there is no external document for a pinned `spec` to name.

```
identifier_type => text_type & {}
identifier => !identifier_type {}
```

What follows:

- **`identifier` IS-A `text`.** §7.4's "`IDENTIFIER` is inside `TEXT`" becomes a subtype edge rather than a
  selector's declared order, so §5.7's narrowing follows IS-A as it does everywhere else.
- **It inherits the text facets** — `min_length`/`max_length`/`length`, `pattern` (a naming convention such as
  snake_case, inside §7.7's grammar) and `members`, so `!identifier ^ { members: [...] }` is a closed vocabulary
  of names, with §7.4's member-coherence rule unchanged.
- **The per-name mechanisms ride the type.** Every value whose type is `identifier` or refines it meets §8.2's
  mechanisms 2 and 3 under the **identifier** policy, at a map key and at a field value alike. That reverses
  §7.4's "`identifier` is not used in data values", and moves §8.2's split from *position* (declared names
  against data) to *type* (identifier-typed against everything else); §8.2's policy paragraph needs rewording to
  match. A document admitted today can be refused under it. Both are the point, and the revision should say so.
- **Two scopes are added to §11.4:** the key set of a map whose key type is `identifier` or refines it, and the
  element set of an array whose elements are unique (`unique_items`, a `set_type` among them) and whose element
  type is one. The look-alike mechanism is a relation over a
  set, so the type alone cannot carry it; this is the sentence that gives it the set, in the words §11.4 uses for
  an `IDENTIFIER` enum's members. A set of names is the data-side twin of those members; an array that admits
  repetition is not a scope, since nothing says two of its elements name two things.
- **An ordinary schema declares its own.** The kernel's note that "Core declares no sibling of it" is why only a
  kernel-governed meta layer can type a key by it today. As a constructor/instance pair the kernel's instance is
  one line, `identifier => !identifier_type { continue_add: "-" }`, which any schema may write to type
  `{identifier => handler}`; core declares no sibling and leaves the name free (#15).
- **The discrimination class changes (§5.4).** §5.4 lists "the `unit` instances (`value`, `identifier`)" among
  the types with no class. As a text family, `identifier` is string-class, so a choice holding it can become
  disjoint — `( identifier | int32 )` from `false` to `true`, `( identifier | text )` staying `false` — and a
  resolver's recorded `disjoint` changes for such a schema. An identifier-keyed map also leaves §5.4's list of
  maps with no single key class, which changes how [TSON-JSON] spells one.

The cost to weigh: an identifier becomes a kind of string in the type system. What it adds over `text` — the
grammar and NFC — is what `spec` pins, which is the arrangement `uri_type` already has.

**Proposal 2 — `enum.profile` becomes `enum_type.type`, a label type held as data.** An enum's members are
*labels drawn from a naming vocabulary*, and everything `profile` switches on is a property of that vocabulary
rather than of `enum`: which spellings are admitted (the UAX #31 profile, `pattern`), when two labels are one label
(`normalization`, and case folding if `identifier_type` gains it), whether hygiene runs over the member set, and
whether a member is spelled unquoted. `IDENTIFIER` and `TEXT` are the two-point projection of that — `identifier`
and `text` — and no enumeration of profiles can close: a schema describing protobuf's names, JSON Schema's property
names or HTTP's tokens declares its own `identifier_type` construction (Proposal 3) and wants an enum over it. So
`profile` is replaced by the type, held as a field:

```
enum_type => atom & {
  type:    type_name
  members: enum_set
}
enum      => enum_type ^ { type?: = identifier }
text_enum => enum_type ^ { type?: = text }
```

- **Nothing an author has written changes.** `!enum [OPEN DONE]` is the positional form it is today — `type` is
  pinned and injected, so `members` is the one unmarked field (§5.6) — `boolean` is `!enum [true false]`, and
  every enum instance records `source: enum`. `!text_enum ["new york" "los angeles"]` is the value set.
- **One body shape, one reader, one host class.** Members are held as text whatever `type` is, so every enum is
  `enum_type`'s shape. A tightening adds no field (§5.7), so a meta layer's `kebab_enum => enum_type ^ { type?: =
  kebab }` has an instance a processor binds as `enum_type`'s, with nothing new to map.
- **An ordinary schema enumerates its own vocabulary.** `type` is data an author may write, so `steps =>
  !enum_type { type: kebab  members: [make-tea drink-tea] }` needs no new constructor — which only a meta layer
  may declare (§2.2.2).
- **`type` resolves by who wrote it.** An author-written `type` names a type in the schema's own namespace; the
  value a constructor pinned was written in the governing meta and resolves there, so `!enum [A B]` names the
  kernel's `identifier` in a schema that imports no core. §2.2.3 already puts a merged entry's own references in
  its defining schema's namespace; this is that rule for a pinned value, and §7.4 must state it. The rule holds
  however the enum is reached — an entry instantiating `names => <M> !enum [a b M]` records the template as its
  `source`, and its `type` is still the pinned one — and an `identifier` the schema declares is a different type
  that does not displace it (#15).
- **`type` is fixed at construction.** A refinement narrows the member set and never restates `type`: narrowing it
  would re-judge members already admitted, widening it would admit labels the source's type refuses, and nothing
  needs either. The set-once rule for `identifier_type`'s profile facets (Proposal 3) is the precedent.
- **`type_name`, not `type_ref`.** A `type_ref` is a record, and §5.2 refuses `~`/`=` on a record-typed field;
  `type_name` is an `identifier` refinement, so the pins are ordinary. A label type written as an application is
  declared by name first. #6's type slot would lift the restriction without changing a source.

**What §7.4 states in place of the profile table.** `type` must name a **text family** — an atom-family instance
whose constructor IS-A `text_type`, one hop through its `source` — and each member must be a value of it, no two
members one value under its equality. These are rules, not structure: members held as `set<text>` are unique as
text, and a type whose equality is coarser than text (case folding) makes two of them one label. The per-type
rows:

| §7.4 row | derived from `type` |
|---|---|
| members | each a value of `type`, by the family's own parsing and facets; unique under its equality |
| hygiene | mechanisms 1–3 when `type`'s constructor IS-A `identifier_type`; the look-alike mechanism alone otherwise |
| discrimination class | the members' shared class under an identifier family; string otherwise |
| binding | a host enum by mapping, whatever `type` is (below) |

**Why a text family, and so no enum of integers.** An enum's members are labels, equal only to themselves under
their type's equality; an integer's equality is a value equality (`80` and `0x50` are one value, [TSON-DATA] §4.3),
and a value set on it is `integer.members`, where §7.4's "numbers are never enums" already puts it. The bound
cannot be written in the grammar today — a family instance is a construction and IS-A nothing, so it is not IS-A
`text`, and a bound resolves in the type-name namespace where a constructor cannot be named — so it is a rule of
§7.4 until #9's constructor bound can state it.

**§7.4's binding row overstates what `IDENTIFIER` buys.** "Every member is a host-safe name; host enum generation is
guaranteed" is not true of any host: §7.7 admits `in-progress`, the kernel's own `boolean` has `true` and `false`
for members, and both are refused as Java constants; Python and C# refuse other names. Binding is a mapping in
every case — `in-progress` to `IN_PROGRESS`, `"new york"` to `NEW_YORK` — owned by the binder, which reports a
mapping that collides or yields no legal constant; a host language is what a schema maps *to*, never a constraint
on it. The row should say that the members are names, and leave host safety to the implementation.

Two rules stay with the enum rather than moving to the type: **the class row** (`boolean => !enum [true false]` is
boolean-class because §7.4 reads the class off the members' tokens, where the type `identifier` is string-class),
and **the look-alike mechanism over the member set**, a property of the set whatever `type` is.

**The alternatives:**

- **`type` as a template argument, `enum_of => <T> atom & { members: set<T> }`.** Member conformance becomes
  structural, through `set<T>`, and `enum => enum_of<identifier>`. This implementation built it and reverted it:
  every application is a new constructor, and so needs a host class — or a binding fallback to the template's —
  for a shape that never changes; the kernel's bootstrap has to materialise a template before it can read its own
  enums; and only a meta layer can apply it, since an ordinary schema cannot declare a constructor. The bound on
  `T` is the same rule either way.
- **A defaulted field on `enum`, `type?: type_name ~ identifier`,** with `text_enum => enum ^ { type?: = text }`.
  One constructor fewer, but `enum` is then both the general form and the identifier one, and a refinement of an
  instance must be told apart from a re-typing. With `enum_type` constructible and `type` required there is one
  general form and two symmetric pins.
- **Keep `profile`, defined by reference** — `IDENTIFIER` means each member is an `identifier` value, and the
  rules come from Proposal 1's type rather than being restated in §7.4. The minimum that removes the duplication,
  and closed at two vocabularies.
- **Collapse `enum` into member sets** — `!identifier ^ { members: [...] }` and `!text ^ { members: [...] }`.
  Argued against: `enum` carries what a member set does not — unquoted spelling, a class of its own, and label
  equality rather than value equality — and Revision 36 kept both deliberately.

**Proposal 3 — `identifier_type` states its profile, as data.** Proposal 1 leaves §7.7's profile in prose,
fixed, so a meta layer describing an outside system — an API's operation names, a database's column names —
can narrow the kernel's names by `pattern` but never state a different naming rule. UAX #31's R1 syntax is
the standard parametrisation, and the kernel can declare it:

```
identifier_base => !enum [XID ID NONE]
normalization   => !enum [NONE NFC NFKC]

identifier_type => text_type & atom_specification & {
  spec?:          = "https://www.unicode.org/reports/tr31/"
  start?:         identifier_base ~ XID
  continue?:      identifier_base ~ XID
  start_add?:     text
  continue_add?:  text
  medial?:        text
  exclude?:       text
  normalization?: normalization ~ NFC
}

identifier => !identifier_type { continue_add: "-" }
```

An identifier is `Start Continue* (Medial Continue+)*`, with `Start = (start ∪ start_add) − exclude`,
`Continue = (continue ∪ continue_add) − exclude` and `Medial = medial`; each `text` is read as the set of code
points it holds, and the whole text must already be in the `normalization` form. `identifier`'s body is §7.7's
profile exactly, so every existing name reads as before. The family now composes `atom_specification`, which
Proposal 1 left out for want of an external document: UAX #31 is that document once the profile is data. What
the text must add:

- **The profile facets are fixed where the profile is constructed.** A refinement restates each or leaves it,
  and narrows only the text facets. §5.7's set-once rule would be unsound here: setting `start_add` on a
  source that left it unset *widens* the profile, so `!identifier ^ { start_add: "_" }` would admit `_x`
  without `_x` being an `identifier`. Narrowing a profile has no use a fresh `!identifier_type` does not serve
  better.
- **Two coherence rules.** A profile whose Start set is empty admits nothing, and a character that is both
  medial and Start or Continue leaves the placement rule unable to say where it may stand. Both refuse the
  body.
- **Join controls keep §7.7 rule 2's contexts under every profile**, an invisible joiner being as much a
  spoofing surface in an outside system's names as in the series' own.
- **Only `identifier`'s profile lies inside §7.1's unquoted-token profile.** A value under another profile may
  need quoting, which is harmless because the positions that admit no quoted form — type references and
  annotations — are typed by the kernel's roles, which stay on `identifier`.
- **Each profile is its own type.** Every `!identifier_type { … }` is a distinct type entity: IS-A between two
  identifier types comes from refinement alone, never from one profile admitting a subset of another's names.
  All are string-class (§5.4), so a choice over two of them is not disjoint.
- **A profile's own additions are exempt from §8.2's restricted-character rule.** The kernel's `-` is exempt because
  the profile adds it, and the same holds for whatever `start_add`, `continue_add` or `medial` names: a profile that
  admits `$` states that `$` belongs in these names, and the rule would otherwise refuse every name that uses it. The
  look-alike and mixed-script mechanisms still reach every character.
- **A per-segment unit divides a name at its profile's own separators.** §8.2 defines the unit as each `_`/`-`
  delimited segment, which is the kernel profile's answer: `_` by convention, and `-` because the profile adds it.
  Under another profile the boundaries should be that profile's — `_`, and every character it adds that is not
  `XID_Continue`, such as a `$` or a medial `.` — since a script change across a profile's own punctuation sits
  between words, where a homograph cannot. Stated that way, §8.2's unit is one rule for every profile, and reads
  exactly as it does today for the kernel's.
- **`medial` is new to the series.** The kernel keeps `-` as a Continue character, so `a-` and `a--b` stay
  names; moving it to `medial` would be a separate change to §7.7.

UAX #31's `NFKC_Casefold` form is left out: checking it needs full case folding, which is a Unicode table of
its own rather than a normalizer call, and it waits for a naming system that needs it. Case-insensitive
*comparison* is not covered here at all — SQL folds an unquoted name rather than requiring it folded — and
belongs with the look-alike and duplicate rules of Proposal 1, not with the profile. The profile's parameters
follow UAX #31's R1 shape; none has yet been validated against a consuming meta layer.

**What is running.** On `main`, the current text: `identifier` is `!unit {}`, a value at an `identifier`-typed
position is checked against §7.7's grammar only, and no hygiene reaches a map key. A consumer that wanted the
rule could scan its own keys, but a security rule with a second implementation in each consumer is free to
drift lenient, so none does.

On `r2026-37-proposal`, Proposal 1's declaration, in the form above. A value is checked against §7.7's grammar first —
a failure is `ATOM_FORM_INVALID`, the grammar being the type's form — and then against the text facets, whose failures
are `ATOM_CONSTRAINT_VIOLATION`; so `!identifier_type { pattern: "[a-z][a-z0-9_]*" }` is a naming convention and
`!identifier_type { members: [north south] }` a closed vocabulary of names. §7.4's members rule counts the grammar
among the facets beside a member, so `members: [north "2nd"]` fails to load: no value could ever be `2nd`, since the
grammar refuses it before the member set is asked. The discrimination class is string. Proposal 3 runs as written
above, with both coherence rules and the fixed-profile refinement rule; the lexer, schema parser, resolver and linker
hold §7.7's profile directly, since the kernel's own names are read before the kernel exists, and the bootstrap
refuses a kernel `identifier` whose body states any other. Core declares no sibling (#15): an ordinary schema declares
its own, `identifier => !identifier_type { continue_add: "-" }`, and then writes `{identifier => handler}` and refines
`!identifier ^ { … }`. §8.2's per-name mechanisms reach every data value whose type is an identifier family, in both
encodings, under the family's own profile — a character the profile adds meets no restricted-character rule, as §7.7's
`-` does not — and the keys of a map keyed by one, and the elements of a set of one, are a look-alike scope, refused
at the second; a refused value reads as nothing. Under a schema these are the data scopes, and like every other scope
it is skipped where the identifier policy switches skeleton distinctness off; a per-segment level divides such a value
at its profile's separators, as proposed above. A field's default or fixed value of such a type is judged the same way
when the schema links, since a default reaches every document that omits the field. So is a value a schema
writes in a payload whose types the schema layer's own scopes do not cover — a meta layer's `data` body, such as
`!iface { methods: {method_name => …} }`, and an annotation value — judged as it is in a data document, under the
same policy and codes; a kernel constructor's identifier-typed values are §11.4's scopes or references to a
declaration, and are judged there. The corpus states it at `class2/validate/refused/`, a bucket added for it, and
at `class2/schema/refused/`. Proposal 2 runs as written above:
the kernel declares `enum_type`, `enum` and `text_enum`, and `enum_profile` retires. The linker resolves each enum's
`type` — in the schema's namespace, or in the governing meta's for a pinned value — refuses one that is not a text
family, parses every member through the type's own parser and refuses a member it rejects or two it reads as one
value, and keys §8.2's per-name rules on whether the type's constructor IS-A `identifier_type`. A member refused under
an identifier family names `!text_enum [...]`, as §7.4 has the diagnostic name the fix. A processor binds a
constructor that tightens another as the one it tightens, so `text_enum` and a meta layer's `kebab_enum` need no class
of their own. The discrimination-class row runs as written: an enum whose type is not an identifier family is
string-class, so `(!text_enum ["80" "443"] | integer)` is disjoint and `(!text_enum ["80" "443"] | text)` is not.

**Status against Revision 36:** open. Proposal 1 stands alone and closes the map-key gap; Proposal 2 depends on
it alone, #6 and #9 only letting its rules become structure later; Proposal 3 depends on Proposal 1 alone.

---

## 8. `value` and `void` should each have a constructor, retiring `unit` and dispatch by name

**Section:** [TSON-SCHEMA] §4.2 (the `unit` atom constructor), §5.4 (discrimination class), §6 (bare annotations),
§7.3 (`void`), §9 and §13.2 (the bundled schemas); the meta-kernel's `unit`, `value` and `void`, and core's `void`
sibling. Follows from #7.

**Kind:** proposal — consistency of the meta-kernel, and the removal of the one place the series identifies a type
by its name.

**What §4.2 says today.** `unit => atom & {}` is the atom with no constraint vocabulary, and its instances
`value`, `identifier` and `void` are all `!unit {}`: "the resolved shapes are identical and deliberately
uninformative, so implementations MUST dispatch `value`, `identifier`, and `void` by their declared names". Every
other atom in the kernel is told apart by its constructor — `integer_type`/`integer`, `text_type`/`text`,
`uri_type`/`uri`, `regex_type`/`regex` — and #7 moves `identifier` to the same pattern, as
`identifier_type`/`identifier`. That leaves `unit` with two instances and the name rule with two subjects.

**Proposal.** Give each its own constructor, with an empty constraint vocabulary as `unit` has, and retire `unit`:

```
value_type => atom & {}
value      => !value_type {}

void_type  => atom & {}
void       => !void_type {}
```

and core's sibling becomes `void => !void_type {}`. Every atom in the kernel is then `X => !X_type {…}`, and each
contract belongs to a constructor rather than to a name.

What follows:

- **§4.2's name rule goes.** A processor recognises `void` and `value` by the constructor their resolved body
  names, as it recognises every other family. The resolved output carries the distinction structurally, where
  today it is "deliberately uninformative".
- **Core's `void` sibling is a `void` by construction.** Today it is "the same `!unit {}` construction and the
  same contract" (§4.2), and a processor must know that a second entity named `void`, in another schema, carries
  the kernel's contract. Under the proposal the contract comes with `!void_type`, and no second name needs
  recognising.
- **§4.2's "User schemas SHOULD NOT introduce additional unit instances without a documented parsing contract"
  goes.** `!unit {}` names no contract, which is why the SHOULD exists; with `unit` gone there is nothing to
  instantiate without one. A schema that wants its own `void` writes `!void_type {}` and gets the kernel's contract.
- **The prose that names "the `unit` instances" is rewritten to name `value` and `void`:** §5.4's list of types
  with no class, §5.4's exception for map key types, and §13.2's table row for the kernel.
- **Neither type gains a facet.** Both vocabularies are empty, as `unit`'s is, so neither becomes narrowable;
  `value`'s "is not narrowable" stays true.

**What is running.** On `main`, name dispatch, as §4.2 requires: both encodings register one reader factory for
`unit` and select `void` or `value` inside it by the entry's declared name, and the linker's refusal of a `void`
variant (§5.4) and the inhabitance check (§5.10.1) compare the terminal of a reference chain against the string
`"void"`.

On `r2026-37-proposal`, the proposal as written, with core's sibling `void => !void_type {}`. `unit` is gone; each
encoding registers a reader factory for `value_type` and one for `void_type`; the atom vocabulary maps a body to
its parser with no name in hand; and the `void` variant refusal and the inhabitance check ask whether a chain ends
at a `!void_type` body, so core's `void` and the kernel's are caught alike.

**The cost.** The kernel and core change content, so every bundled schema's pin changes, and a revision carries it.
Nothing else moves: `value` and `void` keep their names, positions and contracts, and a user schema that types a
field `void` or `value` is unchanged in source.

**Interpretation chosen:** the current text on `main`, and the proposal on `r2026-37-proposal`.

**Status against Revision 36:** open. Independent of #6; it completes #7, and is worth taking only with it, since
alone it leaves `identifier` the one `!unit {}`.

---

## 9. A template parameter should carry its type: `template_param => { name: param_name  type: type_ref }`

**Section:** [TSON-SCHEMA] §5.2 (value conformance), §5.10 (two parameter kinds, inferred by use; an argument read
by the position it lands in), §8.1 (open entries: `template.parameters`), §8.2 (materialisation's deferred checks
and where they are located), §10.1 (ingest), §12.1 (the argument channel by token shape); the grammar's
`type-params` production. Builds on #6.

**Kind:** proposal — record what the resolver already has to work out, and give #6's bound somewhere to live.

**What the spec says today.** An open entry's body is `!template { parameters: [param_name]  template: text … }`
(§8.1): the parameter list carries names and order, and nothing else. §5.10 infers each parameter's kind from its
use — a parameter in a type-reference position is a type parameter, one in a value position a value parameter —
but the kind is not recorded, and neither is anything finer. So:

- **An application can be checked only by substitution.** Arity is checked against `parameters`; everything else
  waits until the held body is substituted and read against the constructor's vocabulary. A literal applied where
  the body uses a type fails as `type_ref.name` rejecting a non-identifier; a type name routed into a value fails
  §5.2's value conformance. Both verdicts are real, but they are located inside the template body, with the
  application as context (§8.2), rather than at the argument that was wrong.
- **A consumer of resolved output cannot check an application at all** without parsing and re-resolving the held
  text, which §8.1 otherwise keeps it from needing: "a consumer dispatches on these two fields and never reads the
  text". An importing schema, a second resolver comparing output, or a host binder generating a generic type has
  the parameter's name and nothing to check an argument against.
- **§12.1's channel is decided by token shape**, so an unquoted non-numeric argument arrives as a reference. For
  `e => <M> !enum { members: [a b M] }` applied as `e<c>`, `c` is a member, and only the held body's use of `M`
  says so — which a consumer cannot see.
- **A parameter used in several places** is judged only on kind: "a parameter used in both kinds of position is a
  resolver error". Two value uses of different types, or two type uses with different bounds once #6 exists, have
  no rule.

**Proposal.** Record each parameter's type beside its name:

```
template_param => {
  name:   param_name
  type:   type_ref
  bound?: type_ref     -- only where type is type_ref: an argument must name a type that IS-A it
}

template => top & {
  parameters:      [template_param]
  template:        text
  extension?:      record_extension_type
  discriminators?: [field_name; 1..]
}
```

**One reading for both kinds.** `type` is the type an argument is read as, in the vocabulary of the constructor the
held body applies — the declared type of the slot the parameter stands in. For a count that is
`non_negative_integer`; for a type reference it is `type_ref`, which is the kernel's type of a type reference and not
a special case. So the kind is not recorded: it follows from `type` (below), and a separate field would state one
fact twice.

**Why a list of records.** Arguments are positional, so the order is part of the fact and a map loses it; a parallel
`parameter_types: [type_ref]` beside `parameters: [param_name]` is two lists free to drift. A record per parameter
keeps name and type together and leaves room for what a parameter may carry later — a default argument — without
changing shape again. The list stays inside `template` rather than moving to `type_definition`, where
`parameters` would be a second statement of what the held body already says.

**Every parameter's type is derived from the positions it stands in.** No parameter lacks one: §5.10 already
refuses a parameter the body never references, so every parameter has at least one position, and every position
has a declared type in the applied constructor's vocabulary:

- **A type slot** — a field declared `type_ref`, such as `array.element_type` or `map.key_type` — gives `type_ref`,
  or, once #6 lands, the bounded slot it declares (`<: text>`).
- **A value slot** gives the slot's declared type: `min_items: N` gives `non_negative_integer`, `enum.members`
  gives the member type, and a selector pinned `type: text = N` gives `text`.
- **A routed default or fixed value** (`w?: int32 ~ N`) gives the *field's* declared type, `int32`, not the
  declared type of `record_field.value`, which is `value` and says nothing. This is §5.2's value conformance, and
  it is the one derivation that reads a second field; #6's `record_field => { type: <T>  value?: T }` makes it
  structural. Where the field's type is itself a parameter — `box => <T, N> { w?: T ~ N }` — the derived type is
  that parameter: `N`'s `type` is `T` (see "A type may name an earlier parameter", below).
- **An argument to another template** gives that template's recorded `type` for the position, so derivation is a
  fixed point across templates that apply each other. A parameter the fixed point leaves undetermined gets
  `type_ref`, which is §5.10's rule that such a parameter is a type parameter.
- **A payload in §5.6's positional form** is walked as the one field it binds: `names => <M> !enum [a b M]` puts
  `M` in `enum.members`, exactly as `!enum { members: [a b M] }` does, and gives `text`. The derivation has to say
  so, since the positional payload names no field for a walk to match.

**Where a derived type is read.** Each rule reads its type where the held body wrote it, so the body decides the
namespace and the recorded name is never ambiguous. A slot's declared type belongs to the applied constructor's
vocabulary — `min_items: N` gives the governing meta's `non_negative_integer`, and core's `extern_type => <S, T>
!scoped { … schemas: { S => [T] } }` gives `T: type_name`, a kernel role core does not declare. A routed default's
type is the value of `record_field.type`, which is itself a `type_ref` slot, so it names a type in the schema's own
namespace by exactly the rule that makes `array.element_type: T`'s argument a local name: `counted => <C> { c?:
percent ~ C }` gives `C: percent`, which only the schema declares. A type carried from another template keeps the
reading it had there.

**A type may name an earlier parameter.** A routed default whose field is typed by a parameter has no fixed type to
record, since its type is whatever that parameter's argument turns out to be. The proposal records the parameter
itself — `parameters: [ { name: T  type: type_ref } { name: N  type: T } ]` — which needs one scoping rule: inside
`parameters`, a `type` may name a parameter of the same template declared before it, and nothing else in the
schema's namespaces by that name. #6 needs the same rule for `record_field => { type: <T>  value?: T }`, so it is not
a rule of this entry's own. At an application the argument for `N` is read as the type `T`'s argument names. The
alternative — record `value` for such a parameter — keeps `type` free of references to its siblings and gives up the
call-site check for exactly the parameters whose type depends on another argument.

**The kind follows from the type.** A parameter whose `type` is `type_ref` or a bounded type slot is a type
parameter; any other is a value parameter. §5.10's kind inference becomes a consequence rather than a rule of its
own, and "used in both kinds of position" becomes one case of the rule for several uses.

**Several uses must agree.** A parameter's `type` is the use type that IS-A every other; if the use types are not
ordered by IS-A, the declaration is a resolver error. `<N> !array { min_items: N  max_items: N }` derives
`non_negative_integer` twice and is fine; with `labels => <L: text> set<L>`, `<T> { a: set<T>  e: labels<T> }`
derives `type_ref` and `<: text>`, and takes the bounded one, which IS-A the other; with `counts => <N: integer>
set<N>`, `<T> { a: labels<T>  n: counts<T> }` with bounds `text` and `integer` is refused at the declaration, since
no argument could satisfy both. The rule follows IS-A edges only, as #6's bound does, so two sibling refinements —
`int8` and `int32`, both `!integer ^ {…}` — are refused together though a small integer satisfies both. That is the
price of a total two-valued rule rather than a value-set prover; the author declares the narrower type by name and
uses it in both places. The alternative, a list of use types checked one by one at application, is exact but moves
the verdict from the declaration to every application and gives a consumer a list to intersect.

**What this buys.**

- **An application is checked at the call site**, against `parameters` alone: each argument is read as its
  parameter's `type`, before substitution. `vector<pixel, 1920>` reads `1920` as `non_negative_integer` and
  `pixel` as a `type_ref`; `vector<pixel, "two">` is refused at the argument, not inside the body.
- **The channel ambiguity goes.** `e<c>` reads `c` as a member because the recorded type says so, and a consumer
  sees what the resolver saw.
- **Resolved output is checkable without the held text.** Import, comparison and ingest check an application
  against the entry, as §8.1 intends every consumer of an open entry to do.
- **#6's bound has a home.** `<X: T>` needs somewhere to record `T`; `template_param.type` is it.

**The restriction syntax.** #6's production is taken as it stands:

```
type-param  = param-name [ws ":" ws type-ref]
type-params = "<" ws type-param *( separator type-param ) [ ws "," ] ws ">"
```

It is one production for every form a declaration takes, so the written type belongs to the parameter and not to
the body: `boxed => <T: text> { a: T }`, `vec => <T: text, N> !array { element_type: T  min_items: N }` and
`rebox => <T: text> boxed<T>` all bound `T` the same way. The body decides only what the parameter derives.

**The written type is read by the kind the positions give**, as TSON's `name: type` reads as "the values of `type`"
for a field. The kind comes from the uses and never from the annotation, which could otherwise contradict them.

- **On a value parameter it narrows `type`.** `<N: int8>` records `type: int8`, and the written type must IS-A the
  type every use derives, or the declaration is a resolver error: `<N: text> !array { min_items: N }` is refused,
  since `text` is not a `non_negative_integer`.
- **On a type parameter it is `bound`.** `<T: text>` records `type: type_ref  bound: text`, and an argument must
  name `text` or a type that IS-A it; this is #6's template bound.
- **An unannotated parameter keeps what it derives**, so every existing template resolves unchanged.

**Why `bound` is its own field.** A type parameter carries two facts — its argument is a type reference, and the
reference is restricted to types that IS-A the bound — and a value parameter one. Narrowing `type` to `text` for a
type parameter loses the first: `<T: text> { a: T }` and `<M: text> !enum [a b M]` would both record `{ name: …
type: text }`, one a type and one a text value, and a consumer could not tell which argument each takes without
reading the held body. Folding the bound into `type` as `type_ref<text>` keeps one field by giving an application
on `type_ref`, which is not a template, a meaning of its own, and moves the second fact rather than removing it.
`bound?` states both with ordinary values, at the cost of one rule — present only where `type` is `type_ref` — and
is the shape #6 already sketches for a bounded field's resolved form.

**A bound is inherited through another template's argument list**, as a type is. `rebox => <T> boxed<T>` over
`boxed => <T: text> { a: T }` records `T: type_ref  bound: text`: left unbounded, `rebox<int32>` would pass its own
check and fail only inside `boxed`, which is the deep failure this entry removes. Several bounds agree by the same
rule as several types, and a written bound must IS-A every inherited one — `<T: non_empty_text> boxed<T>` narrows
it, and `<T: int32> boxed<T>` is refused at the declaration.

**A core copy and its kernel original are one type here.** A value parameter's written type names a type in the
schema's own namespace, and a slot-derived type is the meta's: `<N: non_negative_integer> !array { min_items: N }`
compares the schema's `non_negative_integer` with the kernel's — core declares none (#15), so the schema declares its
own, `!integer ^ { min: 0 }`, the kernel's body — with no IS-A edge between them. Following IS-A edges only would
refuse the obvious spelling, and a core-importing schema could not name the kernel's type at all. Two same-named
entries with the same body are taken as one type, in the declaration check and in the bound check alike. The spec
needs a sentence to that effect, or a different way for core's copies to relate to the kernel's.

**A bound names a type, and only a type.** `bound` is a `type_ref` into the schema's type-name namespace, checked by
IS-A there; a name found only among the governing meta's constructors is structure vocabulary (§3.3.1), which no
type argument can name. So `<T: text_type> { a: T }` admits nothing -- `box<text>` fails IS-A and `box<text_type>`
does not resolve -- and is refused at the declaration. The spec should say which namespace a written type resolves
in, since a slot-derived `type` is read in the other one.

**The recorded `type` does not say which namespace it names.** "Where a derived type is read" makes the held body
decide, which serves the resolver but not a consumer of resolved output: `{ name: N  type: non_negative_integer }`
reads the same whether the kernel's type or core's copy was meant, and the only way to tell is the held text this
entry exists to make unnecessary. The core-copy rule above is what makes the ambiguity harmless in practice. A
cleaner fix is for core's copies to relate to the kernel's by an edge rather than by name and body, after which
either reading gives the same answer.

**Proposal (not running): a bound on the constructor, `<T: !C>`.** A nominal bound cannot say "any text-valued
atom". Construction transfers kind and no supertypes (§5.5), so `identifier`, a kebab `!identifier_type { … }`
profile, and `stock_code => !text_type {}` are IS-A nothing, and `<T: text>`, `<T: atom>` and `<T: identifier_type>`
all refuse them. That separation is right for values -- a `stock_code` must not pass where a plain `text` is
declared -- but a bound is a different question: what the body can do with T, not which nominal type T is.
`box<stock_code>` and `box<text>` stay distinct entries, so admitting both mixes nothing.

The question lives in the structure namespace -- T's definition was built by `C` or by a constructor composing
`C` -- so it is a second field, never a second reading of `bound`:

```
template_param => {
  name:         param_name
  type:         type_ref
  bound?:       type_ref   -- type-name namespace: an argument must name a type that IS-A it
  constructor?: type_ref   -- structure namespace: an argument's definition must be built by it, or by a
                           -- constructor composing it
}
```

- **Spelling** `<T: !text_type>`: `!` already marks a structure-namespace head in `!C { … }`.
- **The check** follows the argument's reference chain to its definition, takes the constructor it was built
  with through any refinement, and asks IS-A among constructors -- one two-valued rule. `identifier_type` composes
  `text_type`, so `identifier` and every identifier profile pass.
- **What it buys:** `<T: !text_type>` for any text-valued atom; `<T: !atom>` for any scalar, which is what an
  unbounded `labels => <T> set<T>` would need to keep records out of a set of labels; `<K: !atom, V> { K => V }`,
  whose every application takes [TSON-JSON]'s object form, since that form is chosen by `K`.
- **Both fields may be present**: a parameter can inherit one from each of two uses, and each narrows on its own
  terms through the fixed point -- `constructor` along IS-A among constructors.
- **A constructor is named by identity in the structure namespace**, not by bare name, and only a schema its meta
  governs can name one. The kernel's are shared by every meta chain.

**What is running.** On `main`, the current text: this implementation derives each parameter's kind from the
declared type of the slot it stands in (`ParameterKinds`), refuses a parameter standing for a whole collection or
record or in both kinds of position, runs the derivation as a fixed point across templates with an undetermined
parameter a type parameter, and then discards the slot type. The output carries `parameters: [param_name]`, and an
argument is checked by substitution.

On `r2026-37-proposal`, the proposal as written here — the kernel declares `template_param` with `bound?` and
`template.parameters: [template_param]`, and `ParameterTypes` keeps what the walk finds: a slot's declared type,
a routed default's field type (an earlier parameter where the field is typed by one), a callee's recorded type
through the fixed point, `type_ref` where nothing grounds a parameter, and §5.6's positional form walked as the
field it binds. The kind is read from the type. Several uses must agree by IS-A, or the declaration is refused. A
held body is built before resolution has typed anything, so every parameter starts as `type_ref`, and the types are
stamped on each open entry the schema produced — declared and minted by materialisation alike — once everything has
closed. The restriction syntax is parsed on every declaration form; a written type narrows a value parameter's
`type` or is a type parameter's `bound`; bounds are inherited through the fixed point; and a core copy and its
kernel original are one type in both checks. Each application checks its arguments as it closes: a type argument
must name a type that IS-A the bound, following reference chains, and a value argument must parse as the
parameter's type, or as the type an earlier parameter's argument names. A written bound must name a type in the
schema or its imports; one naming only a constructor of the governing meta is refused at the declaration. An
argument the check cannot judge — an application, an unresolved name — is left to the substituted body, as before.
Not yet running there: ingest's verification of a recorded type, since this implementation re-resolves a schema
from source rather than ingesting resolved output.

**The proposal measured against what exists.** Every template this implementation's tests and the conformance
corpus declared before the change — 116 distinct ones — was walked with the use types recorded, and the running
derivation bears it out:

- **No template is newly refused.** One parameter has uses of more than one type, `both => <T> { a: T  b?: int32 ~
  T }`, which is refused today as standing in both kinds of position; it stays refused, with `type_ref` and `int32`
  unordered by IS-A as the reason.
- **One kind changes.** `my_set => <T> array ^ { element_type?: = T … }` routes `T` into a field typed `type_ref`,
  so the routed-default rule makes `T` a type parameter where today's rule, reading `record_field.value`'s own type
  `value`, makes it a value parameter. The routed rule is the accurate one; the change is invisible to every
  application, since an argument substitutes the same text on either channel.
- **Two templates need the earlier-parameter rule**, both of the form `<T, N> { w?: T ~ N }`.
- **One template recorded nothing without the positional-form rule.** `names => <M> !enum [a b M]` gave `M` no use
  at all, so `M` was never a value parameter and `names<c>` looked `c` up as a type — an error on `main` today,
  which the recorded type makes visible and the positional-form rule removes.
- **The derived value types are ordinary named types** — `non_negative_integer`, `integer`, `text`, `uri`,
  `type_name`. No resolver-minted synthetic name reaches a `type`.
- **The bundled schemas' templates record:** `set`'s `T: type_ref` (meta and core), `extern_of`'s `S: uri`, and
  `extern_type`'s `S: uri` and `T: type_name`.

**The cost.**

1. **A meta-kernel change.** `template.parameters` changes type and `template_param` is new, so every bundled
   schema's pin changes, and a revision carries it.
2. **A derived fact in resolved output, which ingest verifies rather than recomputes.** `type` is computed, like
   `subtypes` and `disjoint`, but unlike them it may also carry what an author wrote: `<X: T>` narrows it, and the
   annotation lives nowhere else. The held text is the body alone — `"!set_type { element_type: T }"` — and the
   parameter list is not in it, so an ingest (§10.1) that discarded `type` and recomputed it would silently drop
   every annotation. Ingest derives the type again from the held text and checks that the recorded `type` IS-A it,
   which is the rule an annotation already meets at the declaration: an annotated narrowing survives, and a stale
   or altered one is refused. The alternative is a second field, `declared?` beside a derived `type`, which splits
   one fact across two places.
3. **The fixed point is normative.** §5.10 states the kind rule for one template; the derivation across templates
   that apply each other must be stated, with its default.
4. **Two sentences on reading `type`.** A slot's declared type is read in the applied constructor's vocabulary and
   a `type_ref` slot's value in the schema's own namespace, which is the rule that already governs the held body;
   and a `type` may name an earlier parameter of the same template.

**Interpretation chosen:** on `main`, the current text — parameters are names, the kind is inferred and not
recorded, and an argument is checked by substitution. On `r2026-37-proposal`, this entry: the recorded type, the
restriction syntax, and the check at the application.

**Status against Revision 36:** open. The recorded type stands without #6; the bound syntax needs #6's production,
and #6's template bound needs this entry's `type` to be recorded. The earlier-parameter rule is shared with #6
rather than depending on it.

---

## 10. Order should be a facet every container states: `array.unordered` becomes `ordered`, and `map` gains it

**Section:** [TSON-SCHEMA] §4.2 (the container constructors), §7.5 (sets: element order), §8.1 (binding records:
`set`'s `unordered: = true` as the worked example of a vocabulary pin), §2.1's illustration of `set_type`;
[TSON-DATA] §2.6 (map).

**Kind:** proposal — a kernel change.

**What the spec says today.** `array` carries `unordered?: boolean ~ false`, which `set_type` pins `= true`, and
§7.5 says what it means for a set: unordered as a *value*, with resolved output keeping the order written. `map`
carries no such facet, and nothing says whether a map's entry order is part of its value or whether output keeps
it. Order matters to a map in practice: the schema map itself and every record's field list are keyed sets whose
written order output keeps (§5.8's field order, §7.5 for sets), and a map typed after them has no way to say so.

**Proposal.**

- **`array.unordered?: boolean ~ false` becomes `ordered?: boolean ~ true`**, and `set_type` pins
  `ordered?: = false`. The facet names the property rather than its absence, so `ordered: false` reads as one
  negation where `unordered: false` reads as two. Nothing else about `array` or `set_type` changes.
- **`map` gains `ordered?: boolean ~ false`.** A map is unordered unless it says otherwise, as a JSON object is, so
  every map written today keeps its meaning; `!map { … ordered: true }` declares one whose entry order is part of
  its value, which a host binds to a map that keeps its order.
- **One sentence of meaning for both**, beside §7.5's: `ordered` says whether two values differing only in order
  are one value; it never changes what a document may write, and output keeps the order written either way.
  §7.5's output rule then covers maps as it covers sets.

**What is running** (`r2026-37-proposal`): the kernel carries the facet on both constructors, the bundled schemas
are re-pinned, and resolved output names `ordered` only where it departs from the default (§8.1). No reader
consults the facet yet, so each mode honours one value of it: bind mode compares maps by an order-blind host
equality, and the tree compares a map's entries in order. §7.5's duplicate rule and §2.6's compound-key identity
are where it shows, and making both follow the facet, with an ordered map bound to an order-keeping host map, is
outstanding work here rather than a question for the spec.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open. It prepares #2's keyed sets, where a record's `fields` becomes a map and has
to say whether its order is part of its value.

---

## 11. A set's bounds should be array's: `set_type` drops its `min_items` default of 1

**Section:** [TSON-SCHEMA] §5.3 (type expressions: "`set_type` … defaults `min_items` to 1, so a set is non-empty
unless a body writes `min_items: 0`"), §7.4 (the `enum` atom: `enum_set` "non-empty by `set_type`'s default"), §9
(the meta layer's table: "`set_type` refining `array` with `min_items ~ 1`").

**Kind:** proposal — a kernel change.

**What the spec says today.** `set_type` refines `array` and, beside its pins, defaults `min_items` to 1. A set is
the one container whose empty value has to be asked for: `[T]` admits `[]` and `{K => V}` admits `{}`, while
`set<T>` refuses `[]` unless the author writes `!set_type { element_type: T  min_items: 0 }` — a spelling the
`set<T>` template does not offer. So `tags: set<text>` cannot hold
"no tags", and the author of a set that may be empty has to leave the template for a named entry. The kernel and
meta lean on the default instead of stating it: every member set (`enum_set`, `integer_member_set`,
`text_member_set`, meta's `decimal_type.members`) and `scoped.scope` are non-empty only because `set_type` says so.

**Proposal.**

- **`set_type` loses `min_items?: ~ 1`.** Its bounds are `array`'s: the empty set is a set, and a set that must be
  non-empty says `min_items: 1`.
- **Every set the kernel and meta rely on being non-empty says so.** `integer_member_set`, `text_member_set` and
  `enum_set` become `!set_type { element_type: … min_items: 1 }`. Meta gains two named entries for the same reason,
  `scope_set => !set_type { element_type: scope_kind  min_items: 1 }` and
  `decimal_member_set => !set_type { element_type: value  min_items: 1 }`, typing `scoped.scope` and
  `decimal_type.members`: §5.2 bars a `!C {…}` application at a field position, and the `set<T>` template cannot
  carry the bound, so a bounded set at a field is a named entry.
- **§5.3, §7.4 and §9 drop the default from their prose**; §9's table names `set_type` as refining `array` with
  `state`, `ordered` and `unique_items` pinned.

**What is running** (`r2026-37-proposal`): the kernel's `set_type` has no `min_items` default; the three kernel
member sets and meta's `scope_set` and `decimal_member_set` state `min_items: 1`; meta's two inline
`set<scope_kind>` and `set<value>` instantiations are gone from resolved output, replaced by the named entries; and
the bundled schemas are re-pinned. `set<text>` admits `[]`. Every set that was non-empty under the default is
non-empty still, so no member list, enum or scope changes what it admits.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.

---

## 12. A one-position tuple has no spelling: core should declare `tuple1<T>` and `voidable_tuple1<T>`

**Section:** [TSON-SCHEMA] §5.3 (tuple types: "A tuple requires at least two element type expressions … a single
type-ref with no semicolon is an unconstrained array — never a one-element tuple"), §9 (core's contents: "Core also
declares the `scoped` instances … and the templates `extern_of`, `extern_type`").

**Kind:** proposal — two core templates.

**What the spec says today.** The bracket sugar spells a tuple of two or more positions, and `[T]` is an array, so a
tuple of exactly one position has no sugar. The construction `!tuple { elements: [{ element_type: T }] }` denotes
one, but §5.2 bars a `!` construction at a field position, so an author must declare a named entry for each element
type. The need is rare in a schema written as TSON and real in one converted from JSON Schema, where
`prefixItems` with one entry and `items: false` (or `minItems`/`maxItems` of 1 beside it) is a one-position tuple,
and a converter needs a spelling it can emit at a field without minting declarations.

**Proposal.** Core declares two templates beside `set`:

```
tuple1          => <T> !tuple { elements: [{ element_type: T }] }
voidable_tuple1 => <T> !tuple { elements: [{ element_type: T  voidable: true }] }
```

`tuple1<text>` admits `[a]` and refuses `[]`, `[a b]` and `[_]`; `voidable_tuple1<text>` admits `[a]` and `[_]`.
The position is written in #21's vocabulary; under Revision 36's it is `state: OPTIONAL`, and the template
`optional_tuple1`.
Two templates rather than one because a type argument cannot carry the `?` that would make the position voidable
(`tuple1<int32?>` is not an argument), and a template application carries no facets an author could set instead.
Core rather than meta: a type-position name resolves in the schema's own namespace and its imports, so a template
in meta alone would be out of reach of every user field; and core alone rather than both, as `set` is, because no
meta-layer declaration needs one. §9's sentence on core's templates names the two beside `extern_of` and
`extern_type`.

**What is running** (`r2026-37-proposal`): both templates are in the bundled `core.tn`, the resolved fixture states
them as open entries, and both read as above in tree mode. Nothing else changes: `[T]` remains an array and the
sugar's two-position minimum stands.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.

---

## 13. `uri` should be RFC 3986's URI beside a `uri_reference`, and `uri_type`'s facets should follow RFC 3986

**Section:** [TSON-DATA] §5.5 (the table: "`!uri` | RFC 3986 | URI"), §3.3 (a directive argument "is a URI or file
reference (RFC 3986)"), §2.2.1 (an identifying URI is absolute); [TSON-SCHEMA] §9 (the meta layer's table:
"`uri_type`/`uri`"; core's same-named sibling).

**Kind:** defect and proposal — three kernel facets (one replacing `scheme`), a core atom, and a Part 1 vocabulary
entry.

**What the spec says today.** RFC 3986 names two productions with two value spaces: a **URI** (§3), which has a
scheme, and a **URI-reference** (§4.1), which is a URI or a relative reference (§4.2). §5.5's table cites RFC 3986
and names the first, and nothing in either part says which production `!uri` reads. The corpus pinned the second
(`!uri "foo/bar?x=1"` valid), and so did this implementation, by reading through a URI parser that accepts both.
`uri_type`'s facets cannot recover the URI from the reference: `scheme` pins one scheme and cannot require some
scheme, and a `pattern` doing it occupies the one position `pattern` has (§5.7, settable once), so a schema
converted from JSON Schema — whose `uri`, `iri` and `url` formats require a scheme and whose `uri-reference` does
not — loses its own pattern wherever it states one beside such a format. The kernel's own uses want the URI:
`atom_specification.spec` and the keys of `scoped.schemas` are identities, which §2.2.1 already requires to be
absolute, so the type admits values the identity rule then refuses. Two more of RFC 3986's distinctions have no
facet either. `scheme: text` states one scheme, where the common constraint is a set ("`http` or `https`" — Pydantic's
`allowed_schemes`, Joi's `scheme` list, class-validator's `protocols`), and §5.7 gives it no narrowing rule, so a
refinement may swap one scheme for another. And §4.3's absolute-URI, a URI with no fragment, is the form §2.2.1
demands of an identity, with no facet to state it.

**Proposal.**

- **`uri_type` gains a permission facet, `allow_relative?: boolean ~ true`.** Left at its default the value space
  is the URI-reference; withdrawn it is the URI. It narrows as `float_type`'s `allow_*` flags do (§5.7): a
  refinement may withdraw it and never grant it back.
- **`scheme: text` becomes `schemes?: scheme_set`**, over a kernel `scheme_set => !set_type { element_type: text
  min_items: 1 }`. Its members are compared case-insensitively, as §3.1 compares a scheme, and a value with no scheme
  is outside any set. It narrows as every member set does (§5.7): a refinement's set is a subset of its source's.
- **`uri_type` gains a second permission, `allow_fragment?: boolean ~ true`.** Withdrawn, it refuses a fragment
  (§3.5), an empty one included; withdrawn beside `allow_relative`, the value space is §4.3's absolute-URI.
- **The kernel's `uri` is `!uri_type { allow_relative: false }`.** `spec` and the `scoped.schemas` keys are then
  typed by the value space §2.2.1 already demands of them.
- **Core declares both**: `uri_reference => !uri_type {}` and `uri => !uri_reference ^ { allow_relative: false }`,
  so `uri` IS-A `uri_reference` — every URI is a URI-reference — and §5.4's derived facts see the containment
  rather than two unrelated text families.
- **[TSON-DATA] §5.5's table gains `!uri_reference`** (RFC 3986, URI-reference), and `!uri`'s row says the
  scheme is required. A relative reference under `!uri` is a validation error, not a parse error: the token is
  inside the family's lexical space and outside the atom's value space, as a negative integer is under `!uint32`.
- **§3.3's directive argument is a reference, not a URI**: its own text ("a URI or file reference") already admits
  a relative one, and #14 widens it to an IRI-reference. The identity rule of §2.2.1 is where absoluteness is
  demanded, unchanged.
- **[TSON-JSON]'s family table names `uri_reference` beside `uri`**, a string in both cases (edited in place in
  this repository's draft).

**What is running** (`r2026-37-proposal`): the kernel's `uri_type` carries `schemes`, `allow_relative` and
`allow_fragment`, and its `uri` withdraws `allow_relative`; core declares `uri_reference` and `uri` as above;
`!uri_reference` is in the built-in vocabulary; `!uri` refuses `foo/bar` as a validation error; a scheme outside
`schemes` and a fragment where `allow_fragment` is withdrawn are validation errors; a refinement that adds a scheme
or restores either permission is a resolver error; directive arguments are read as `iri_reference` (#14); a writer
names a `java.net.URI` `!uri` when it has a scheme and `!uri_reference` when it has none, so it reads back. The
bundled schemas are re-pinned, and the corpus moves its relative-reference vector to `!uri_reference`, adds the
refusal under `!uri`, and adds Class 2 vectors for each facet.

**Interpretation chosen:** on `main`, `!uri` reads the URI-reference, as the corpus pins. On `r2026-37-proposal`,
this entry.

**Status against Revision 36:** open.

---

## 14. `iri_type` for RFC 3987, `uri` held to US-ASCII, and a directive argument an IRI-reference

**Section:** [TSON-DATA] §5.5 (the table's `!uri` row, citing RFC 3986), §3.3 (a directive argument "is a URI or
file reference (RFC 3986)"); [TSON-SCHEMA] §9 (meta's atom constructors,
core's contents); [TSON-JSON] §5.6 (the text-form families).

**Kind:** proposal — a meta constructor, two core atoms, two Part 1 vocabulary entries, and a rule for `uri`.

**What the spec says today.** RFC 3986's URI is US-ASCII (§2); RFC 3987's IRI is the same structure with characters
beyond it (`ucschar`, and `iprivate` within the query), and every URI is an IRI. The vocabulary names the first only.
JSON Schema and OpenAPI name both — `iri` and `iri-reference` beside `uri` and `uri-reference` — so a schema
converted from either has no atom for the IRI pair and must either widen to `text` or narrow to `uri`. Nothing in
§5.5 says whether `!uri` admits a character beyond US-ASCII; an implementation reading through a lenient URI parser
admits it, which makes `uri` an IRI type by accident, and one reading strictly refuses it, so the same document
passes on one processor and fails on another (#13 reports the parallel case for relative references).

**Proposal.**

- **meta.tn declares `iri_type => text_type & atom_specification & { spec?: = "…/rfc3987"  schemes?: scheme_set
  allow_relative?: boolean ~ true  allow_fragment?: boolean ~ true }`** — a family of its own because its grammar is
  another RFC's, and `spec` names one grammar per constructor. Its facets are `uri_type`'s (#13), with the same
  meaning and the same narrowing rules.
- **Core declares `iri_reference => !iri_type {}` and `iri => !iri_reference ^ { allow_relative: false }`**, the
  pair #13 gives `uri`. `uri` is not IS-A `iri`: families do not relate, and §5.4's derived facts already treat
  every text-form family as overlapping, so nothing reads the containment.
- **§5.5's table gains `!iri` and `!iri_reference`** (RFC 3987), and **`!uri`'s row says a URI is US-ASCII**: a
  character beyond it is outside the URI grammar, a resolver error, not a validation one.
- **An IRI is judged through the URI it maps to** (RFC 3987 §3.1): each character beyond US-ASCII is a `ucschar`,
  or an `iprivate` inside the query, and the text with those characters percent-encoded as UTF-8 is a
  URI-reference. Value identity is the text's; no normalisation is applied (RFC 3987 §5.3.1's simple string
  comparison). §4's bidirectional-text rules are a SHOULD and stay one.
- **§3.3's directive argument is an IRI-reference** rather than a URI or file reference under RFC 3986: a schema
  published at, or a file named by, a path beyond US-ASCII is written as itself rather than percent-encoded, and
  every argument valid today stays valid. Canonical identity (§2.2.1) is untouched: it compares the text as
  written, and its restrictions on the form are §2.2.1's own.
- **[TSON-JSON]'s family table names `iri` and `iri_reference`**, strings like `uri` (edited in place in this
  repository's draft).

**What is running** (`r2026-37-proposal`): meta's `iri_type` and core's `iri` and `iri_reference` as above, with
`!iri` and `!iri_reference` in the built-in vocabulary; `!uri` and `!uri_reference` refuse a character beyond
US-ASCII as a resolver error; `!iri` refuses a relative reference as a validation error and a character outside
`ucschar` (or `iprivate` outside the query) as a resolver error; directive arguments are read, and written, as
`iri_reference`. A host `java.net.URI` holds any of the four atoms'
values; a writer names the narrowest that admits the value, so it reads back. The one departure from text identity
is a library limit, not a proposal: the few `ucschar` characters `java.net.URI` reads as spaces are held
percent-encoded, so the IRI is read rather than refused (`CONFORMANCE.md`). The bundled schemas are re-pinned, and
the corpus adds Class 1 vectors for the IRI grammar and the US-ASCII rule, and Class 2 vectors for the two core
atoms.

**Interpretation chosen:** on `main`, `!uri` admits what `java.net.URI` admits, characters beyond US-ASCII included.
On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.

---

## 15. Core should hold only what a schema cannot do without

**Section:** [TSON-SCHEMA] §9 (core's contents), §2.2.3 (a local declaration may not reuse a name its import
closure binds), §6 ("Core declares its own `doc`, `documentation`, and `annotation`", and meta "carries the
kernel's `doc`, `documentation`, and `annotation`"), §7.4 (an enum's `type`), §8.1 (the chain
`doc → documentation → text`), §13.1 (the kernel's annotation types), §1.6 (the complete example's
`title: non_empty_text`);
[TSON-DATA] §5.6 (the sign-bound row of the numeric table), §5.1 (the vocabulary as core's contracts).

**Kind:** proposal — core and the built-in vocabulary shrink — and one sentence for §7.4.

**What the spec says today.** §2.2.3 makes it a resolver error for a local declaration to reuse a name the import
closure binds, with no hiding. So every name core declares is reserved in every schema that imports it, and a
name core adds in a later revision breaks every schema already using it, found when that schema migrates. The
evidence is Revision 37's own draft: core's new `identifier` (#7) stopped twelve SchemaStore conversions and one
hand conversion compiling (`ltr8-io-tson-benchmarks`, `docs/tson-feedback.md` item 3), because `identifier` is a
name a schema reaches for its own type.

**Proposal.** Core declares a type a schema cannot reasonably do without — the atoms, the widths, the formats, the
`scoped` instances and the templates — and leaves to the schema that wants one any name that stands for a single
line it can write itself:

- **`identifier`** leaves core (Revision 37 draft only): `identifier => !identifier_type { continue_add: "-" }`
  is the kernel's own instance, and a schema writes it where it wants one (#7).
- **`positive_integer`, `non_negative_integer`, `negative_integer`, `non_positive_integer` and
  `non_empty_text`** leave core. A bound is a refinement written where it is wanted: `count: !integer ^ { min: 0 }`
  at a field, or a declaration of the schema's own.
- **`annotation` and `documentation`** leave core, and `doc => @annotation text` stays as its one documentation
  annotation, so a data document governed by a core-importing schema still writes `@doc`. §6's sentence names
  `doc` alone.
- **The kernel's `documentation`** goes with core's, and the kernel's `doc` becomes `@annotation text`. It was a
  hop in a chain nothing else named, and §3.3.5 has core's `doc` a sibling of the kernel's — the same
  construction — which a `doc` one hop shorter than the kernel's would not be. §13.1's row lists `annotation`,
  `doc` and `synthetic`; §6's meta sentence names `doc` and `annotation`; §8.1's chain example loses its middle
  entry and needs one of its own.
- **[TSON-DATA] §5.6's sign-bound row leaves the built-in vocabulary.** §5.1 states the vocabulary as core's
  entries' contracts; keeping the row would leave a schemaless `!positive_integer` that stops resolving the moment
  its document moves under a schema importing core, where the vocabulary does not apply. A name outside the
  vocabulary is an uninterpreted marker (§5.1).
- **§7.4 states where a pinned `type` resolves.** The value a constructor pins resolves in the governing meta
  however the enum is reached — directly, or through a template's held body, where the instantiating entry
  records the template as its `source` — and an entry of the same name in the schema's namespace does not
  displace it. While core declared `identifier` with the kernel's body the two readings agreed; a schema
  declaring its own `identifier` is where they part.

**The alternatives.** A shadowing rule — a local declaration hides an import — makes core's additions safe for
the schema that declares the name, but §2.2.3 checks collisions across the whole import closure, so a schema
importing that one still collides; it needs ambiguity reported at use rather than at import as well, a larger
change to name resolution than restraint in one library. An opt-in `core-extras` library reserves its names for
whoever imports it, and with these entries gone there is nothing yet to put in it.

**What is running** (`r2026-37-proposal`): the bundled `core.tn` without the eight entries (48 remain), the bundled
`meta-kernel.tn` without `documentation`, and their resolved fixtures; the built-in vocabulary without the four
sign bounds; and the pinned `type` resolved first in the governing meta, through a template's held body to the
constructor it applies. The corpus states a schema importing core declaring `identifier`, `positive_integer`,
`non_empty_text` and `documentation`; an enum applied through a template; and an enum in a schema that declares its
own `identifier`.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.

---

## 16. Meta's annotation vocabulary: what a schema states, and its maintainers' notes

**Section:** [TSON-SCHEMA] §6 (meta's list of annotation types; the advisory list), §13.1 (meta's row).

**Kind:** proposal.

**What the spec says today.** §6 has `meta.tn` declare `deprecated`, `since`, `todo`, `lang`, `title`, `examples`,
`read_only`, `write_only`, `ordered`, `bounded`, `exact`, `numeric` and `disjoint`. `todo` and `deprecated` are
`text`, so `@deprecated` must carry a value, and §6's form rule makes the bare `@deprecated` an author would write
a resolver error.

**Proposal.**

- **`todo` leaves meta.** It records work on the schema's source, not anything the schema states, and a published
  schema is immutable (§10), so a `@todo` in one is a note no one can act on.
- **`since` and `lang` leave meta.** Neither is a fact a processor or a converter acts on: `since` is untyped
  text whose versions mean whatever the author's release scheme does, and a schema's prose language is not a
  property of any one declaration. A schema that wants either declares it in a meta-schema of its own. §6's form
  example `@since:2025-01` needs another (`@title:"Order"`).
- **`comment => @annotation text` joins meta**, a note for the schema's maintainers rather than its readers:
  JSON Schema's `$comment`. `@todo` held part of that and goes; folding the rest into `@doc` would show
  maintainers' notes to everyone reading the documentation. A converter from JSON Schema meets it often — 1,004
  occurrences across 159 schema families in one SchemaStore conversion run (`ltr8-io-tson-benchmarks`).
- **`deprecated` becomes `@annotation void`**, a presence marker like `@numeric`. Deprecation is a yes-or-no fact
  — JSON Schema's `deprecated` is a boolean, and so is every host language's marker — and a reason, where there
  is one, is `@doc`'s to give.
- **`examples` becomes `@annotation [text]`.** `[value]` says each example is a token "read by the type the
  position hands it to", and an annotation's position hands it none, so nothing is read and a record's example
  has no spelling but a string anyway. `[text]` says what an example is — text shown to a reader, conventionally
  the value in TSON notation, never parsed or checked against the type it illustrates — rather than implying a
  validation no processor can perform. Checking examples would need an annotation value typed by its target,
  which §6 does not have.

**What is running** (`r2026-37-proposal`): the bundled `meta.tn` without `todo`, `since` and `lang`, with
`deprecated => @annotation void`, `comment => @annotation text` and `examples => @annotation [text]`, and its
resolved fixture. `tson strip` drops
`@comment` with `@doc`.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.

---

## 17. `@doc`'s text has no stated format

**Section:** [TSON-SCHEMA] §6 (`doc`, kernel and core), §9 (the kernel's and core's `doc` entries).

**Kind:** proposal.

**What the spec says today.** `doc => @annotation text`, and nothing says what the text is. Every renderer
picks: plain text, or whichever markdown dialect it knows. The bundled schemas' own docs are written in
markdown already — backticks, lists, emphasis — and a schema converted from OpenAPI carries `description` text
OpenAPI states is CommonMark, so the reading is ambiguous from both directions.

**Proposal.** `@doc`'s text is **CommonMark** (0.31.2), stated in the `doc` entry's contract in the kernel and
in core:

- **CommonMark, not "markdown".** Markdown has no specification; CommonMark is versioned, so two renderers agree
  on what a doc says. OpenAPI made the same choice for `description`.
- **No extensions.** GFM's tables, task lists and autolinks are one vendor's dialect over CommonMark; a doc
  that uses them reads as their literal text.
- **Raw HTML is never executed, and a renderer need not render it.** CommonMark passes HTML through, and a doc
  is written by whoever published the schema, so rendering one fetched from elsewhere must not become a way to
  run its author's code.
- **The rule refuses nothing.** Every string is valid CommonMark, so no schema stops loading and no processor
  checks anything: the rule says how the text is displayed, which is all §6 lets an advisory annotation do.
  Plain prose is almost unaffected — `_` inside a word (`record_field`) is not emphasis — and the occasional
  leading `#`, `*` or `<` takes a backslash or backticks.

`@title` stays plain text, being a name, and `@comment` is left unstated, as JSON Schema's `$comment` is.

**What is running** (`r2026-37-proposal`): the bundled kernel's and core's `doc` entries state the contract in
their own `@doc`. Nothing in this library renders a doc, so nothing else changes.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.

---

## 18. A field group's option should hold several fields, which spells "at least one of" and its neighbours

**Section:** [TSON-SCHEMA] §5.11 (field groups: "a bare group is REQUIRED — exactly one member MUST be present; a
group with `?` is OPTIONAL — at most one member MAY be present. These are the only group states", and "a `?` on the
member's *name* … [is a] parse error on a member"), §5.8 (removal, rule 7: "a group reduced to one member is
dissolved per §5.11"), §12.1 (`group-def`, `group-member`), and the meta-kernel's `field_group` and its doc;
[TSON-DATA] §7.2.4 and §7.2.5 (the special-token set: "Fourteen characters qualify").

**Kind:** proposal — a kernel change, a grammar extension, and a fifteenth special token in [TSON-DATA].

**What the spec says today.** A group admits exactly one member (REQUIRED) or at most one (OPTIONAL), and a member
is one field. "A contact needs an email, a phone, or both" — at least one of a set of keys — has no spelling, and
neither do the presence rules beside it:

- a REQUIRED group refuses both present;
- optional fields admit none present;
- a choice of records needs one variant per non-empty subset of the keys (3 for a pair, 7 for three, 31 for five).
  Every variant is brace-class, so under [TSON-JSON] each value needs a `$type`. That changes the data, so it is
  not a spelling.

The rule is a domain constraint, not a JSON artefact, and JSON Schema states it often: `anyOf` over `required`
lists, `dependencies`, `dependentRequired`.

**Evidence, from a consumer.** The SchemaStore census in `ltr8-io-tson-benchmarks`
(`corpus/schemastore/manual/union-with-siblings/`, SchemaStore at `bdbec4c`) hand-classifies the sites. The
classification rows overlap, so each site is counted once, by file and pointer. A site count weights a design by
how often SchemaStore copies it, so design counts are given where they are known.

| Shape | Sites | Note |
| --- | ---: | --- |
| at least one of a set of keys | 55 | 22 designs. 36 of the sites are 4 SARIF definitions, repeated across SchemaStore's 9 copies of that schema. The set has 2 keys at 38 sites, 3 at 11, 4 at 4 and 5 at 2. Every site has the same shape: a few keys always required (one site has any: feed's `id`), any non-empty subset of a set S, the rest free. |
| compound | 31 | 20 sites are one enonic rule: "`include` alone, or at least one of `name` and `type`". The rest are co-required pairs and similar conditions. |
| one key requires another | 27 | 26 draft-04 `dependencies`, 1 `dependentRequired`. Some name a schema rather than keys. |

**Proposal: an option of a group may hold several fields.** In the surface syntax, `|` separates options and
whitespace separates the fields of one option. A `?` on a member's name marks it optional *within its option*. A
group takes `?` for at most one option, as today, and `+` is sugar for "at least one of":

```
( include: I | name?: N  type?: T )
( email: E | phone: P )+
```

```
group-def    = *annotation "(" ws group-option *( ws "|" ws group-option ) ws ")" ["?" / "+"]
group-option = group-member *( separator group-member )
group-member = *annotation field-name ["?"] ws ":" ws type-ref ["?"]
```

The `~`, `=` and `=?` modifiers stay parse errors on a member, since a member is never supplied. `+`, like `?`,
is adjacent to the `)` it marks.

**`+` becomes [TSON-DATA]'s fifteenth special token.** §7.2.5's set is closed: a character is a special token
exactly when it has a grammar role somewhere in the series, and `+` has none today. §7.2.4 lexes a `+` followed by
an unquoted-continuation character as the start of an unquoted token, and a bare `+` is a lexer error. Under this
proposal `+` takes `-`'s boundary rule exactly: followed by a continuation character it begins an unquoted token,
so `+5` and `+0.5` are unchanged, and otherwise it is emitted as a special token. In a data value it is then
reserved by the schema grammar like the other twelve, so a bare `+` stays an error there, a parse error rather
than a lexer error. §7.2.5's list gains `+`, and its parenthetical on `-` covers both signs.

Three rules decide validity:

- An option is **chosen** when any of its members is present.
- A chosen option must contain every member not marked `?`.
- A bare group admits exactly one chosen option, and an optional group (`?`) at most one.

**`+` is sugar.** It is allowed only where every option is one unmarked member, and
`( email: E | phone: P )+` desugars to `( email?: E  phone?: P )`: a non-optional group of one option whose members
are all marked. That option must be chosen, and it is chosen when any of its members is present, so the group admits
any non-empty subset of its members. The desugared form is the kernel's, and `+` is the only way to write it.

The first example admits `include`, `name`, `type`, and `name` with `type`. It refuses an empty record, and `include`
beside either of the others. A voidable member written `_` is present and chooses its option, as §5.11 has it
today; JSON Schema's `required` counts a null-valued key the same way.

| Rule | Spelling | Today |
| --- | --- | --- |
| exactly one of `a` and `b` | `( a: A \| b: B )` | same text, same meaning |
| at most one of `a` and `b` | `( a: A \| b: B )?` | same text, same meaning |
| at least one of `email` and `phone` | `( email: E \| phone: P )+` | no spelling |
| `include` alone, or at least one of `name` and `type` | `( include: I \| name?: N  type?: T )` | no spelling |
| both `a` and `b`, or neither | `( a: A  b: B )?` | no spelling |
| `a` requires `b`; `b` may appear alone | `( b: B  a?: A )?` | no spelling |
| `a` and `c` each require `b` | `( b: B  a?: A  c?: C )?` | no spelling |
| either `host` and `port`, or `socket` | `( host: H  port: P \| socket: S )` | no spelling |

**Declaration rules,** checked at load. A group is refused wherever it restates what plain fields or another
group already state, so each presence rule has one spelling:

- A member's name is unique across the record's fields and every group's members, as today, so options are
  disjoint and a field sits in one option of one group.
- **The only member of an option takes no `?`.** The member is present exactly when its option is chosen, so the
  mark changes nothing: `( a?: A | b: B )` is `( a: A | b: B )`.
- **A written group of one option is optional, with at least two members and one of them unmarked**, as in
  `( b: B  a?: A )?`. Every other group of one option restates something:
  - bare with an unmarked member is plain fields: `( b: B  a?: A )` is `b: B  a?: A`;
  - bare with every member marked is what `+` desugars to, and is written that way: `( a?: A  b?: B )` is
    `( a: A | b: B )+`;
  - `?` with every member marked is optional fields: `( a?: A  b?: B )?` is `a?: A  b?: B`, and `( a: A )?` is
    `a?: A`.
- **`+` takes options of one unmarked member each.** `( a?: A | b: B )+` and `( host: H  port: P | socket: S )+`
  are refused.

These replace §5.11's "at least two members", and imply it. They were checked by enumerating every group over two,
three and four members: every split into options, every set of marks, bare, `?` and `+`. No accepted group equals
plain fields, splits into independent parts, or equals another accepted group. Every refused bare or `?` group
equals plain fields or an accepted group; a refused `+` group is a rule this proposal does not spell. Every option
can be chosen on its own, so a declared group is never unsatisfiable, and loading needs no search.

There is no form for any number of options. Options free to be chosen together are independent, so such a group
is always its options written as separate groups or plain fields.

**In the kernel:**

```
field_group => {
  members:           [[field_name; 1..]; 1..]
  optional_members?: [field_name; 1..]
  optional?:         boolean ~ false
}
```

- `members` holds one list per option, options and their members in source order. A group has an option and an
  option a field, so neither list is ever empty.
- `optional_members` names the members marked `?`, and is absent rather than empty where none is marked, so a group
  has one spelling.
- `optional` is the group's own `?`: the group may be missing as a whole. These are #21's names; under Revision 36's
  vocabulary the list is `optional` and the flag `state: element_state ~ REQUIRED`.
- Every member's `record_field` stays `optional: true`, as today.
- The group keeps two states. At least one is the desugared `+`, and the rest of the rules table comes from options
  holding several fields, not from a third state.

**Why the mark belongs to the group, not the field.** The alternative records the in-option `?` in each member's
`record_field.optional`. That saves a field and costs three things:

- **The field contract.** `record_field`'s doc defines `optional` as "the key may be omitted", and that stops being
  true of a member.
- **Failing safe.** A consumer that ignores groups would read `include` and `type` as required, and refuse
  `{ include: … }`. Today such a consumer reads every member as optional, which is loose but never refuses a valid
  document.
- **§5.11's principle.** A member's presence would no longer be the group's alone.

Kept in the group, the mark is presence logic stated where presence logic lives.

**Refinement, composition and removal.**

- **Refinement and composition.** A restated member stays a member, in its own option. It may drop its `?`, which
  narrows, and may not add one. A restated unmarked member narrowed to `void` makes its option unchoosable, and a
  marked one narrowed to `void` drops out of its option. A group with no choosable option is unsatisfiable, as a
  non-optional group with every member narrowed to `void` is today. A member of a `+` group is restated as it is
  written there, without a `?`, and that changes nothing. Anywhere else, dropping a `?` never yields a group the
  declaration rules refuse, since they only ever ask for an unmarked member or forbid a mark.
- **Removal (§5.8 rule 7).** A removed member leaves its option, and an emptied option leaves the group. A group
  the declaration rules would then refuse is rewritten as the spelling it equals, plain fields or an accepted
  group, which the enumeration shows always exists. Today's dissolution of a group of one member is the simplest
  case.

**Diagnostics stay local.** For example: "`port` chose (host, port), which needs `host`", "`include` and `type`
choose two options; exactly one allowed", and "none of (email, phone) is present; at least one is required".

**What it costs:**

- **`?` on a member's name gains a second reading:** optional once its option is chosen.
- **The resolved form shows at least one as a group of one option**, not as a state, as any sugar's kernel form
  differs from its surface.
- **Resolved form.** Every existing group's `members` becomes a list of one-member lists. So the resolved form and
  the pin change for every schema with a group, meta's ordered numeric families among them. Their text and value
  sets do not change.
- **Code generators.** An option is a variant whose fields are its members, and an all-marked option carries a
  not-empty check. §5.11's labelled-sum pattern still holds: a host binding that lowers a single REQUIRED group to
  a native sum gets record-shaped variants where an option holds several fields.
- **[TSON-JSON] §6.1.4** counts present members per group. It would count chosen options instead, a Part 3 edit
  made on adoption.

**Coverage, checked against each site's presence logic.** `presence_rule.py` (same directory) takes the presence
sets each site admits:
- union sites come from the census;
- dependency sites are built from `dependencies` and `dependentRequired`.

It then searches for a grouping that admits exactly those sets, no more and no fewer.

| Row | Sites | Spelled | Not spelled |
| --- | ---: | --- | --- |
| at least one | 55 | **55** | — |
| compound | 31 | **25**: enonic (20), bxci, mapehr, servicehub, warp-themes, and web-types `source` (`file offset \| symbol module?`) | 3 are "X, or else all of Y": minecraft-pack-mcmeta ×2 (`pack_format`, or both `min_format` and `max_format`) and odgs (`comment`, or all four others). 2 are irregular: github-action and ti8m. 1 is not checked: web-types `name-pattern`, 12 keys, past the search's limit of 10. |
| one key requires another | 27 | **17**: single-target and mutual dependencies, and schema dependencies that only require keys | 6 depend on a value (nodemon requires `exec` to be a string), or on several targets (the draft-07 metaschema). 3 are presence logic: the sarif-1.0.0 `stackFrame` chain, rust-toolchain, and tmlanguage, which is not yet classified among the shapes below. 1 is not resolved: netlify. |

So 97 of the 113 sites are spelled, and the at-least-one shape is spelled at every one of its 55. The search
tries bare and `?` groups only. Every shape it found that the declaration rules now refuse has an accepted equal:
the 55 at-least-one sites are the form `+` desugars to. So the counts stand.

**What it does not cover:**

- **A requirement triggered by a key's absence**, "X, or else all of Y" (3 sites). When X is present, Y's fields
  are free, so they would belong to both alternatives, and options are exclusive.
- **A field under two rules.** rust-toolchain makes `channel` exclude `path`, and also count toward `profile`'s
  at-least-one. A key requiring two others that are otherwise free (`a: [b, c]`) fails the same way, since a field
  sits in one option of one group.
- **A chain**: at sarif-1.0.0 `stackFrame`, `column` requires `line` and `line` requires `uri`.
- **Conditions on values**, such as JSON Schema's `if`/`then`. These stay sealed families or migration cost.
- **`+` over options of several fields.** One design, claude-code-launch's configuration, would be exact as
  `( runtimeExecutable: T  runtimeArgs?: A | program: P  args?: A | url: U )+`. `+` takes options of one field
  each, and lifting that would take a group state of its own rather than sugar, since such a group has no
  one-option form, and would add rules the one-spelling check would have to cover. One design does not carry it.

**Nested groups are not worth adding.** An option holding a group would spell the chain:
`( uri: U  uriBaseId?: B  ( line: L  column?: C )? )?`. That is the only site of the 113 that nesting gains. The
absence-triggered shape and the field under two rules need the same field in two places, which nesting does not
provide. Against one site, nesting costs a recursive grammar, a recursive kernel type, nested diagnostics, and
recursive sum types for code generators.

**What is running** (`r2026-37-proposal`):

- **Kernel.** `field_group` is `{ members: [[field_name; 1..]; 1..]  optional_members?: [field_name; 1..]
  optional? }`, in #21's names, and the bundled schemas are re-pinned. Every group the bundled schemas declare is
  options of one field, so what they admit is unchanged.
- **Lexer.** `+` is a special token under `-`'s boundary rule; a bare `+` in a data value is a parse error.
- **Grammar and resolver.** Options, a member's `?` and `+` parse; every restating shape is refused with the
  spelling it restates; `+` lowers to the one-option form; group and member restatement and removal follow the
  rules above, a group in a template body included.
- **Readers.** Both the TSON text reader and the [TSON-JSON] reader judge chosen options, reporting each chosen
  option's missing members before the count, and a parity test holds them to one verdict, pointer and message.
  Inhabitance reads a REQUIRED group as satisfiable when one option can be chosen, and a bind target takes a
  group as a labelled choice only when every option is one field.
- **Corpus.** The lexer, parser, schema and validate vectors on the corpus branch of the same name.

The coverage evidence is in `ltr8-io-tson-benchmarks`:
- the census and `presence_rule.py`, as above;
- ajv probes at 4 sites (`compile-commands-entry`, `gitleaks-allowlist`, `codex-plugin-interface`,
  `enonic-cms-form-fragment`), through spellings available before this proposal. Their only disagreements
  with ajv are the presence combinations this proposal decides.

The coverage table compares the rule with the presence logic read from the source, not with ajv on documents.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.

---

## 19. `normalization` should be `text_type`'s, put a value into its form, and offer two case folds

**Sections:** [TSON-SCHEMA] §5.5 (an atom's identity is over its value space), §5.7 (the facet kinds), §7.4 (the
`identifier` primitive: "the decoded text of a token, after unquoting, escape processing, and NFC normalisation", and
in the same paragraph "it rejects … non-NFC text"); [TSON-DATA] §2.6 (map-key identity under a schema), §7.2.1
(quoted tokens at identifier positions are NFC-normalised before they are matched); the meta-kernel's `text_type`,
`normalization`, `identifier_type`, `regex_type` and `uri_type`. Builds on #7 Proposal 3, whose `normalization`
facet this moves and redefines, and replaces that proposal's closing paragraph on case.

**Kind:** proposal — a kernel change.

**What #7 leaves.** `normalization` is `identifier_type`'s alone, and a *form requirement*: text not in the form is
refused, never rewritten. `NFKC_Casefold` is left out, and case-insensitive comparison is deferred to the look-alike
rules. Nothing in the kernel can say that `Content-Type` and `content-type` are one name. `uri_type`'s `@doc` says it
for one field, `schemes`, in prose.

**Evidence, from consumers.** The names of case-insensitive naming systems: RFC 9110 field names, URI schemes (RFC
3986 §3.1), DNS names (RFC 4343) and charset names. Each folds ASCII letters and nothing else -- RFC 4343 says so
outright, and the other three admit only ASCII characters. JSON Schema documents fake the same rule with patterns:
SchemaStore holds 1,330 written as `[Pp][Rr][Ee][Ss][Ee][Nn][Tt]`, each exactly an ASCII-folded member set. The
validation libraries that rewrite on input (below) fold across Unicode instead. The meta-service experiment in
`ltr8-io-tson-java-http` declares `header_name => !text ^ { pattern: "[!#$%&'*+.^_`|~0-9A-Za-z-]+" }`, under which
`Idempotency-Key` and `idempotency-key` are two values: two map keys, two set members, and a pin that refuses the
other spelling.

**How other systems state case.**
- **Schema languages refuse the other case.** JSON Schema, XSD, Avro, Protobuf and GraphQL compare case-sensitively,
  with a pattern as the escape hatch; Kubernetes requires lowercase DNS-1123 names; HTTP/2 and HTTP/3 require
  lowercase field names on the wire.
- **Databases make equality a property of the type:** SQL collations, PostgreSQL's `citext`.
- **Validation libraries rewrite on input:** Pydantic's `to_lower`, Zod's `toLowerCase`, Go's header
  canonicalisation, PRECIS's `UsernameCaseMapped` (RFC 8265).

**The series already rewrites.** An atom maps a spelling to a value: `0x10` is 16. [TSON-DATA] §7.2.1 normalises a
quoted token at an identifier position before matching and identity, and §7.4 defines an identifier as text after
NFC normalisation. A form *requirement* is the outlier, and §7.4 states both readings in one paragraph.

**Proposal.**
- **`normalization` moves to `text_type`**, so every text family inherits it, and it states the form a value is
  *put into*: a value is its token's text, unquoted, unescaped, then put into the form. Every facet judges the
  value and never the spelling — lengths, `pattern`, `members`, an identifier's profile, a URI's or an address's
  grammar — and so does every comparison of two values: pins, map keys, set uniqueness, the duplicate and
  look-alike rules.
- **`ASCII_CASEFOLD` joins the enum**: U+0041..005A mapped to U+0061..007A, and nothing else -- no Unicode
  normalization runs, so a composed and a decomposed `É` stay two values, as under `NONE`. It is the comparison
  the four naming systems state, and it needs no Unicode table.
- **`NFKC_CASEFOLD` joins it.** It is `toNFKC_Casefold`, UAX #31 §5's form for identifiers compared without
  case: NFKC, a full case fold, and the default ignorables removed. Over ASCII it lowercases, but it also folds
  compatibility characters *into* ASCII -- a full-width `Ｃ`, the Kelvin sign U+212A, the long `ſ` -- so it is
  looser than any of the four naming systems, and is the form for names compared without case across Unicode, as
  the libraries that rewrite on input compare them.
- **NFD and NFKD are not members.** Each is the same equivalence as its composed twin, stored decomposed, so it
  would change no verdict and only the spelling of the value; the series writes NFC (an unquoted token must be
  NFC, [TSON-DATA] §7.2.1), and a host that wants decomposed text decomposes it in its binding. The five members
  are five distinct equalities: code points, code points without ASCII case, canonical, compatibility, and
  compatibility without case.
- **Defaults:** `NONE` on `text_type`, where quoted text is otherwise kept as written; `NFC` on `identifier_type`,
  matching §7.2.1 and §7.4.
- **Fixed where the type is constructed**, like the profile facets: a refinement restates it or leaves it. §5.7's
  set-once rule would be unsound: turning folding on in a refinement changes what a token means, rather than
  narrowing which values are admitted.
- **`regex_type` fixes it to `NONE`**, since putting a pattern into another form changes what it matches
  (`[A-Z]` folds to `[a-z]`). **So do `uri_type`, `iri_type` and `email_type`**: past the scheme and the host a
  URI compares with case (RFC 3986 §6.2.2.1), as an IRI does (RFC 3987 §5.3.2.1) and a mailbox's local part does
  (RFC 5321 §2.4), so a form over the whole text would change what the value names. Left open, `!uri_type {
  normalization: NFKC_CASEFOLD }` constructs, admits a full-width scheme, and reads `/Path` as `/path`.
- **A schema's members and pins are values too**, decoded like data, as `= 0x10` is. Two members that are one
  value are refused.
- **`uri_type.schemes` becomes structure**: a set of an ASCII-folding scheme identifier in place of a comparison
  stated in prose for one field.

```
text_type => atom & {
  min_length?:    non_negative_integer
  max_length?:    non_negative_integer
  length?:        non_negative_integer
  pattern?:       regex
  members?:       text_member_set
  normalization?: normalization ~ NONE
}
normalization => !enum [NONE NFC NFKC NFKC_CASEFOLD ASCII_CASEFOLD]

identifier_type => text_type & atom_specification & { …  normalization?: normalization ~ NFC }
regex_type      => text_type & atom_specification & { spec?: = "…/rfc9485"  normalization?: = NONE }
uri_type        => text_type & atom_specification & { …  normalization?: = NONE }   # iri_type, email_type alike

header_name => !identifier_type { start: NONE  continue: NONE
  start_add: "abcdefghijklmnopqrstuvwxyz"  continue_add: "abcdefghijklmnopqrstuvwxyz0123456789-"
  normalization: ASCII_CASEFOLD }
```

Because the profile judges the value, `header_name`'s profile lists lowercase letters only and admits
`Content-Type`. It refuses a full-width `Ｃｏｎｔｅｎｔ-Ｔｙｐｅ` and a `Keep-Alive` spelled with the Kelvin sign, as RFC
9110 does; under `NFKC_CASEFOLD` both would fold into the profile and be admitted.

**What the text must add:**
- **A round trip writes the value.** `Content-Type` is written back as `content-type`, as `0x10` is written as
  `16`. A refusal quotes the token as written, then the value it was judged as -- `'PUT' (read as 'put' under
  NFKC_CASEFOLD)` -- since the written spelling is what a reader, or a repair of a generated document, has to find.
- **`NFKC_CASEFOLD`'s full fold is not lowercasing** beyond ASCII. `ß` folds to `ss`, so a length changes; the Cherokee
  syllabary folds to its uppercase letters; `İ` folds to `i` and a combining dot. These are the standard's
  comparison keys and arise only in profiles that admit the characters.
- **Join controls**: `NFKC_CASEFOLD` removes them, so §7.7 rule 2's context check has nothing to judge under that
  form.
- **`ASCII_CASEFOLD` runs no NFC**, on an `identifier_type` too, so a quoted decomposed spelling keeps its code
  points; the ASCII profiles the form exists for refuse it anyway.
- **§7.4's identifier paragraph** keeps "the decoded text … after NFC normalisation" and drops "rejects … non-NFC
  text": a quoted decomposed spelling at an identifier-typed position reads as its composed value.

**The alternatives.**
- **A form rule** — require one case, refuse the other — is the schema-language consensus, and a pattern states it
  today. It refuses the spellings HTTP/1.1 and OpenAPI documents write, and it does not fit BCP 47's mixed
  canonical case.
- **An equality facet that keeps the spelling** — compare folded, hold as written — has no precedent among schema
  languages, and needs a second comparison at every place values are compared: six, in each encoding's reader.
- **A separate `case` facet beside `normalization`** names a concept UAX #31 already places in its normalization
  forms, and a `CASEFOLD` member of it would need the same Unicode table.

**What is running** (`r2026-37-proposal`):
- **Kernel.** `text_type.normalization` with the five members, `identifier_type` defaulting to `NFC`, and
  `regex_type`, `uri_type`, `iri_type` and `email_type` fixing `NONE`. Every bundled schema is re-pinned, and
  every value they admit is unchanged.
- **`NFKC_CASEFOLD`** is derived from the JDK's normalizer and case mappings with three exceptions (dotless `ı`,
  and two Cherokee ranges, which fold to uppercase). It matches `DerivedNormalizationProps.txt`'s `NFKC_CF` for
  Unicode 16.0 at every one of the 1,112,064 non-surrogate code points.
- **Readers.** Every text parser puts the text into the form before judging it. Map keys, set elements, pins,
  hygiene and look-alike scopes judge the value in both the TSON text reader and the [TSON-JSON] reader, held to one
  verdict by a parity test. A [TSON-JSON] tree's node keeps the spelling that arrived, as it keeps an instant's
  offset; bind mode's value is the normalised text.
- **Refinement and coherence.** A refinement that moves `normalization` is refused; members are judged in the form,
  and two that are one value are refused.
- **Enums.** An enum matches its members in its label type's form, on both encodings' read paths, in the
  linker's checks of defaults and pins, and in a template's value argument.
- **Schemes.** `uri_type.schemes` is a set of the kernel's `scheme_name`, an `ASCII_CASEFOLD` identifier, so a
  scheme is held folded, `[HTTP http]` is a duplicate and a full-width `ＨＴＴＰ` is no scheme; `uri_type`'s prose
  comparison rule is gone.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.

---

## 20. A leap second has no stated value, and §5.5's `precision` sentence reads as a contradiction

**Sections:** [TSON-DATA] §5.4 (the temporal atoms: `!time` is RFC 3339 `full-time`, and under *Instants* the
time of day in UTC on `[00:00:00, 24:00:00)`); [TSON-SCHEMA] §5.5 (`precision` on the temporal families); core's
`time` and `datetime` and meta's `time_type`.

**Kind:** two defects of wording; no rule changes.

**A leap second.** §5.4 gives `!time` two statements that disagree at one point. RFC 3339's `time-second` admits
`60`, so `23:59:60Z` is a `full-time`; the value is a time of day on `[00:00:00, 24:00:00)`, which has no second
60. `!datetime` meets the same token as an instant on the UTC timeline, which counts no leap seconds. Nothing says
whether such a token is a value, what value it is, or that it is refused.

**`precision`.** §5.5 says `precision: N` constrains the value, and then that "a text encoding may spell an
admitted value with trailing zeros (`12:00:00.500` under `precision: 1`) and writes at most N digits". The two
halves are reading and writing, but the sentence does not say so, and "writes at most N digits" next to a
three-digit example reads as the example breaking the rule.

**Interpretation chosen.** A leap second is refused: it lies outside both value spaces, so `23:59:60Z` is a parse
error at `!time` and `!datetime`. `precision` is judged on the value, so `12:00:00.500` is admitted under
`precision: 1`, and a writer writes `12:00:00.5`.

**Suggested resolution.**
- §5.4, after *Instants*: "Second 60, which RFC 3339's grammar admits for a leap second, is neither a time of day
  on that interval nor an instant on the UTC timeline, and is refused."
- §5.5: "Reading admits any spelling of an admitted value, trailing zeros included, so `12:00:00.500` is admitted
  under `precision: 1`; a text encoding writing the value writes at most N fractional digits."

**What is running** (`main` and `r2026-37-proposal`): the leap-second refusal and the value-judged `precision`.
On `r2026-37-proposal`, core's `time` and `datetime` docs state the refusal and meta's `time_type` doc carries the
reworded sentence.

**Status against Revision 36:** open.

---

## 21. The series' two kinds of nothing should each have one name: `optional` for missing, `voidable` for void

**Sections:** [TSON-DATA] §2.9 (the absent sentinel: "present with an absent value — distinct from not appearing at
all"); [TSON-SCHEMA] §5.2 (the field's three slots, `name?: type? ~ value`), §5.3 (`[T?]`, `{K => V?}`, tuple
positions, `element_state`), §5.11 (field groups), §7.6 (where `_` is admitted), §8.1 (resolved binding records);
the meta-kernel's `element_state`, `tuple_element`, `array`, `set_type`, `map` and `field_group`; core's
`optional_tuple1`. Builds on #12 and #18, whose kernel shapes it renames.

**Kind:** proposal — a kernel change and a terminology change, no change to what any schema admits.

**What the spec says today.** A slot can be empty in two ways: its key is not written, or it is written as `_`. The
field syntax already keeps them apart, one mark each — the name's `?` and the type's `?` — and `record_field`
names them `optional` and `voidable`. Everywhere else one enum, `element_state`, serves both, under the word the
record uses for the other fact:

- at an array's element, a map's value and a tuple position, OPTIONAL means `_` may stand there, and nothing is
  ever left out — `record_field`'s `voidable`. `optional_tuple1<T>` admits `[_]` and refuses `[]`;
- at a field group, OPTIONAL means no option need be chosen — the group may be missing, `record_field`'s
  `optional`.

So a reader who learns `optional` from a record reads `[T?]` as "an element may be omitted", which it never means.
The prose has a second split: the value is "the absent sentinel" and "an absent value", the type is `void`, and
"absent" in plain English is what a missing key is, which is why §2.9 must say "present with an absent value".

**Proposal.** One name per fact, on every constructor where the fact can apply, and nowhere else:

- **`optional`: the slot may be missing.** A record field (the name's `?`), and a field group (the group's `?`).
- **`voidable`: the slot's value may be void**, `_` written in its place. A record field (the type's `?`), an
  array's element (`[T?]`), a map's value (`{K => V?}`) and a tuple position.
- **`element_state` is retired**, and each fact is a boolean, as `record_field` already states both.

```
tuple_element => { element_type: type_ref  voidable?: boolean ~ false }
array         => product & { element_type  voidable?: boolean ~ false  ordered?  unique_items?  min_items?  max_items? }
set_type      => array ^ { voidable?: = false  ordered?: = false  unique_items?: = true }
map           => product & { key_type  value_type  voidable?: boolean ~ false  ordered?  min_items?  max_items? }
field_group   => { members: [[field_name; 1..]; 1..]  optional_members?: [field_name; 1..]  optional?: boolean ~ false }
voidable_tuple1 => <T> !tuple { elements: [{ element_type: T  voidable: true }] }   # core; was optional_tuple1
```

A container's part is never missing, so a container has no `optional`; a map key is never void, so a map has no
key `voidable`. Both stay structural, and no rule has to refuse them. The group's in-option `?` list becomes
`optional_members`, since `optional` is now the group's own flag, and that `?` too means "may be missing".

**And the prose follows the type.** The value is **void** and the token **the void sentinel**: "a field set to the
void sentinel is present with a void value". A key is **missing** or present. That gives each fact one word in the
data model, the kernel and the prose alike — missing/`optional`, void/`voidable` — and retires "absent", whose
everyday sense is the other fact.

**The alternatives.**
- **Give `optional` to the value and rename the key fact** (`omittable`), as Swift's, Rust's, Java's and Python's
  `Optional` name a value that may be nothing. It is as consistent, and it loses on two counts. Schema systems use
  the other convention almost without exception — JSON Schema's `required`, JSON Type Definition's
  `optionalProperties` beside `nullable`, XML Schema's `minOccurs="0"` beside `nillable`, OpenAPI 3.0's `required`
  beside `nullable`, TypeScript's `a?:`, Zod's `.optional()` beside `.nullable()` — and [TSON-JSON] maps `required`
  onto `optional` directly. And it moves a word rather than retiring one: a binding record written today with
  `optional: true` would still read and would mean the other fact, where retiring `state: OPTIONAL` makes every
  stale record a refusal.
- **Make voidability part of the type**, `T?` as T's value set with void added, as JSON Schema's
  `type: [T, "null"]` spells it. It deletes `voidable` everywhere, and costs a synthetic choice entry per `T?`, a
  binding special case to read a choice with void as nullable, an IS-A rule between `T` and `T?`, and a rule
  refusing void map keys that is structural today. §2.9 also makes `_` presence with no value rather than a value
  of `T`.
- **`nullable`**, the JSON-facing word, names the concept after one encoding's spelling of it, the notation holding
  no null since Revision 35. `nillable` is XML Schema's alone; `empty` collides with empty text and empty
  containers.

**What the text must change.** [TSON-DATA] §2.9 and every use of "absent sentinel" and "absent value" in Parts 1
and 2 (about forty), [TSON-SCHEMA] §5.3's `element_state` and its `state` fields, §5.11's REQUIRED and OPTIONAL
groups, §7.6's table, §8.1's binding-record examples, and §9's core template name.

**What is running** (`r2026-37-proposal`):
- **Kernel and core** as above; the bundled schemas are re-pinned and the resolved fixtures restated. A minted
  name that rendered a state now renders the boolean (`…_true_…` for `…_OPTIONAL_…`).
- **Model and readers.** `ElementState` is gone: `ArrayBody`, `MapBody` and `TupleElement` carry `voidable`, and
  `FieldGroup` carries `optionalMembers` and `optional`, in both encodings' readers. The parser's `ElementType`
  flag is `voidable`, as a field type's already was.
- **Prose.** "Void sentinel" and "void value" throughout this implementation's Javadoc, diagnostics, design notes
  and [TSON-JSON]; quotations of Parts 1 and 2 keep their text.
- **Names.** The identifiers carry the new noun as well: the tree's `TsonVoid` and `isVoid()`, the AST's
  `VoidValue`, the stream's `VoidEvent`, the lexer's `TokenType.VOID`, and the `void…` diagnostic rules.

**Interpretation chosen:** on `main`, the current text. On `r2026-37-proposal`, this entry.

**Status against Revision 36:** open.
