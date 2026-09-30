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
#5, raised against Revision 36 itself, is one: two rules in §7.8 give one document two categories. #6–#9
are directions again: a bounded type slot, which lets one field's type depend on another's; `identifier`
as a text family, which carries name hygiene to identifier-typed map keys and builds on #6; a
constructor each for `value` and `void`, which retires `unit` and dispatch by name; and a recorded type
for each template parameter, derived from its uses, which checks an application at the call site and
gives #6's bound its home.

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
  its members typed by it.
- **A template parameter.** `<T>` admits any type, so a template meaning only text families cannot say so, and a
  wrong argument fails deep inside the materialised body rather than at the application.
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
enum => atom & {
  type?:   <T: text> ~ identifier
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
- **A type slot's default has a place.** `~ identifier` on a type slot is a single type-name token; §5.2 admits
  it on a field declared with a type-param, and every existing enum resolves unchanged, the default omitted as
  `profile`'s is today (§8.1).
- **Refinement gains one facet kind.** A type slot narrows along IS-A: a refinement may restate it only with a
  subtype of the source's value. For `enum` this replaces `profile`'s one-step chain `IDENTIFIER` inside `TEXT`
  (§5.7), which is the same relation spelled as a selector.
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
   `T` is bound by a field value in the data — `!enum { type: currency_code  members: [USD EUR] }` — and a later
   field's type depends on it. §5.2 and §8.1 must say so.
2. **Field order.** A streaming reader of a schema document read as data would meet `members` before `type` if
   the author wrote it so, and would buffer it. Requiring the binding field to precede its uses removes the
   buffer; §5.4 already puts a dispatch-order requirement on selectors.
3. **A resolved form.** The sugar needs output the kernel can state: plausibly a lift (§5.3) to a synthetic
   entry of a bounded-reference constructor carrying `bound: type_ref`, and a record-level statement of which
   field binds which parameter. Using the field's own name as the binder (`members: set<type>`) saves the name
   but puts field names in the type namespace, where §5.10's shadowing rule does not reach.
4. **A meta-kernel change.** `record_field` and `enum` change shape, which a published revision cannot carry.

**Open: what a bound may name.** `<T: text>` is a type bound. §5.2's other restriction — a value only on a field
typed by an atom-family instance or an enum — and the HTTP layer's "any scalar" are bounds on a **base kind**,
not on a type. If a bound may name a kind as well as a type, both become structural; if not, they stay in prose
and the mechanism serves `enum` and template parameters only.

**Interpretation chosen:** none — nothing is built. `enum` keeps `profile`, template parameters are unbounded,
and §5.2's conformance is enforced by the resolver as prose requires.

**Status against Revision 36:** open.

---

## 7. `identifier` should be a text family — which reaches identifier-typed map keys, and lets `enum` state its type

**Section:** [TSON-SCHEMA] §5.4 (discrimination class), §5.7 (refinement), §7.4 (enum member semantics, the
`identifier` primitive), §11.4 (name hygiene at the schema layer); [TSON-DATA] §2.6 (map keys are values), §7.7
(the identifier grammar), §8.2 (name hygiene); the meta-kernel's `unit`, `identifier`, `text_type`,
`enum_profile` and `enum`. Proposal 2 builds on #6.

**Kind:** proposal, with one gap in the current text underneath it.

**The gap.** [TSON-DATA] §8.2's mechanisms reach *declared names* — a schema's declarations, a record's fields,
an `IDENTIFIER` enum's members — and §11.4 lists the scopes the look-alike mechanism runs over. A map key is a
value (§2.6), so none of it reaches one. Two keys of one map with equal UTS #39 skeletons (`admin` and `аdmin`,
the second with U+0430), or a mixed-script key, are admitted, where the same two names as two fields of a record
or two declarations of a schema are refused under the default Highly Restrictive identifier policy. That holds
when the key type is the kernel's `identifier` or a role over it: the key must match §7.7's grammar, and nothing
else follows. **This implementation runs exactly that:** a value at an `identifier`-typed position is checked
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
- **One scope is added to §11.4:** the key set of a map whose key type is `identifier` or refines it. The
  look-alike mechanism is a relation over a set, so the type alone cannot carry it; this is the sentence that
  gives it the set, in the words §11.4 uses for an `IDENTIFIER` enum's members.
- **`core.tn` gains a sibling**, as it has one for `void`. The kernel's note that "Core declares no sibling of
  it" is why only a kernel-governed meta layer can type a key by it today; an ordinary schema should be able to
  write `{identifier => handler}`.
- **The discrimination class changes (§5.4).** §5.4 lists "the `unit` instances (`value`, `identifier`)" among
  the types with no class. As a text family, `identifier` is string-class, so a choice holding it can become
  disjoint — `( identifier | int32 )` from `false` to `true`, `( identifier | text )` staying `false` — and a
  resolver's recorded `disjoint` changes for such a schema. An identifier-keyed map also leaves §5.4's list of
  maps with no single key class, which changes how [TSON-JSON] spells one.

The cost to weigh: an identifier becomes a kind of string in the type system. What it adds over `text` — the
grammar and NFC — is what `spec` pins, which is the arrangement `uri_type` already has.

**Proposal 2 — `enum.profile` becomes `enum.type`, using #6.** Once `identifier` is a text family, `profile`
states a fact the type system can state itself. With #6's bounded, binding type slot:

```
enum => atom & {
  type?:   <T: text> ~ identifier
  members: set<T>
}
```

| §7.4 row | derived from `enum.type` |
|---|---|
| members | each member is a value of `type`, by the family's own parsing and facets — structural, through `set<T>` |
| hygiene | mechanisms 1–3 when `type` IS-A `identifier`; the look-alike mechanism alone otherwise |
| discrimination class | the members' shared class when `type` IS-A `identifier`; string otherwise |
| binding | host enum by name guaranteed when `type` IS-A `identifier`; host text otherwise |

It is more expressive than the selector: `type: currency_code`, where `currency_code => !text ^ { length: 3
pattern: "[A-Z]{3}" }`, checks every member against it, where a `TEXT` enum's members today are any text. The
default keeps every existing enum unchanged in source and in resolver output, omitted as `profile`'s is (§8.1).

Three rules stay with `enum` rather than moving to the type:

- **The class row is enum-specific.** `boolean => !enum [true false]` is boolean-class because §7.4 reads the
  class off the members' tokens. Under Proposal 1 the type `identifier` is string-class, so an `identifier`-typed
  map key holding `true` is string-class while the same member of an enum is boolean-class. The row cannot be
  inherited from `type`'s own class, and §7.4 must keep stating it.
- **The look-alike mechanism over the member set.** A `TEXT` enum's members are what a value is matched against,
  so two that read alike are a hazard whatever `type` is. That is a property of `enum`.
- **Refinement** narrows `type` along IS-A, #6's facet kind, which replaces `profile`'s one-step chain.

**The alternatives:**

- **Keep `profile`, defined by reference** — `IDENTIFIER` means each member is an `identifier` value, and the
  rules come from Proposal 1's type rather than being restated in §7.4. No change to `enum`'s shape and no
  dependence on #6: the minimum that removes the duplication, and the fallback if #6 is not taken.
- **Collapse `enum` into member sets** — `!identifier ^ { members: [...] }` and `!text ^ { members: [...] }`.
  Argued against: `enum` carries what a member set does not — the binding row, unquoted spelling, and a class of
  its own — and Revision 36 kept both deliberately.

**What is running.** On `main`, the current text: `identifier` is `!unit {}`, a value at an `identifier`-typed
position is checked against §7.7's grammar only, and no hygiene reaches a map key. A consumer that wanted the
rule could scan its own keys, but a security rule with a second implementation in each consumer is free to
drift lenient, so none does.

On `r2026-37-proposal`, Proposal 1's declaration, in the form above. A value is checked against §7.7's grammar
first — a failure is `ATOM_FORM_INVALID`, the grammar being the type's form — and then against the text facets,
whose failures are `ATOM_CONSTRAINT_VIOLATION`; so `!identifier_type { pattern: "[a-z][a-z0-9_]*" }` is a naming
convention and `!identifier_type { members: [north south] }` a closed vocabulary of names. §7.4's members rule
counts the grammar among the facets beside a member, so `members: [north "2nd"]` fails to load: no value could
ever be `2nd`, since the grammar refuses it before the member set is asked. The discrimination class is string.
Core declares its sibling, `identifier => !identifier_type {}`, so an ordinary schema writes
`{identifier => handler}` and refines `!identifier ^ { … }`. Not yet running there: §8.2's mechanisms at
identifier-typed values and §11.4's map-key scope, so a map key is held to the grammar and the look-alike gap
stays open. Proposal 2 is not taken.

**Status against Revision 36:** open. Proposal 1 stands alone and closes the map-key gap; Proposal 2 depends on
it and on #6.

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
  discriminators?: [field_name]
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
`non_negative_integer` twice and is fine; `<T> { a: set<T>  e: enum_of<T> }` derives `type_ref` and `<: text>`, and
takes the bounded one, which IS-A the other; `<T> { a: enum_of<T>  n: int_of<T> }` with bounds `text` and
`integer` is refused at the declaration, since no argument could satisfy both. The rule follows IS-A edges only,
as #6's bound does, so two sibling refinements — `int8` and `int32`, both `!integer ^ {…}` — are refused together
though a small integer satisfies both. That is the price of a total two-valued rule rather than a value-set
prover; the author declares the narrower type by name and uses it in both places. The alternative, a list of use
types checked one by one at application, is exact but moves the verdict from the declaration to every application
and gives a consumer a list to intersect.

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
compares core's `non_negative_integer` with the kernel's, which core declares as a fresh copy with no IS-A edge
between them. Following IS-A edges only would refuse the obvious spelling, and a core-importing schema could not
name the kernel's type at all. Two same-named entries with the same body are taken as one type, in the
declaration check and in the bound check alike. The spec needs a sentence to that effect, or a different way for
core's copies to relate to the kernel's.

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
parameter's type, or as the type an earlier parameter's argument names. An argument the check cannot judge — an
application, an unresolved name — is left to the substituted body, as before. Not yet running there: ingest's
verification of a recorded type, since this implementation re-resolves a schema from source rather than ingesting
resolved output.

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
