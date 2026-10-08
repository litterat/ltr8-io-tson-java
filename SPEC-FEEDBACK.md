# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation.
See `CLAUDE.md` for why this file exists and when to add to it. Spec quotes below are from
2026 Revision 37 — Part 1 (https://tson.io/raw/2026/37/tson-part1-data.md) and Part 2
(https://tson.io/raw/2026/37/tson-part2-schema.md) — unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a
suggested resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a
revision closes.** It is an input to the next revision's adjudication, so its numbering is the numbering
that revision's change log will answer against — a stable index of the open set, not an archive of
everything ever raised.

**Revision 37 closed seventeen of the twenty-one open against Revision 36**, and #1–#4 below are what remains: two
of the four that revision's change log carries open, and the two open remainders of its typed template parameters.
The closed entries are gone: the spec now carries their rules — a family member is declared and a family is judged
over its closure, the cell rule at every scoped position, `identifier` as a text family and `enum_type.type`,
`value_type` and `void_type`, typed template parameters with their bounds and the call-site check, `ordered` on
every container, the empty set, `tuple1<T>`, `uri` beside `uri_reference` and `iri_type`, the smaller core, meta's
annotation vocabulary, `@doc` as CommonMark, field-group options and `+`, `normalization` on `text_type`, the leap
second, and `optional` against `voidable`. **This file is the as-built record**, not a pointer to one: where an
entry proposes a design this implementation has built, the entry states the design, what is running, and what is
not, so that a reviewer editing the spec needs nothing beside it. **Where the evidence is a consumer of this
library rather than this library** — #1 was found building the HTTP layer in `ltr8-io-tson-java-http`, and this
register is the collection point for all of it — the entry says so and states what is running there on the same
terms.

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

**#1–#4 are directions rather than defects.** Each is a place the series stops short of a rule on purpose:
where a deployment's policy lives (#1), a bounded type slot at a field, which lets one field's type depend on
another's (#2), a bound on the constructor an argument's type was built with (#3), and an edge between a core
sibling and its kernel original, which resolved output needs and the same-body rule cannot give it (#4).
**#5 was raised against Revision 37 itself**, in verifying its text against the closed entries: §5.2 exempts a
use-site application of a template family base, which mints exactly the unnameable member §5.2's rule removes. The
rest of what that verification found is in Revision 37's text and artifacts. **Two directions Revision 37 carried
open are not here**: a namespace as a value and a JSON member name that is not an identifier are being reworked
together, and will be raised against Revision 38 as entries of their own.
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

**Status against Revision 37:** open, carried unchanged. Revision 34 introduced the policy layer that had
nowhere to live; Revision 35 gave it everywhere to be *reported* and left where it lives undefined on purpose;
Revisions 36 and 37 carry the entry open with both constraints recorded, and §8.2's closing sentence stands as
the placeholder it is. Adopting this entry is a new section; declining it costs nothing that is currently broken.

---

## 2. A type slot cannot be bounded at a field, so a field cannot depend on another field's type

**Section:** [TSON-SCHEMA] §5.2 (value conformance), §5.7 (facet kinds under refinement), §5.10 (*What a
parameter does not carry*), §7.4 (`enum_type.type` and its members), §8.1 (`record_field`, the constructor type
slots); the grammar's `type-param` and `field-type` productions.

**Kind:** proposal — the field half of a mechanism whose template half Revision 37 took.

**What Revision 37 settled.** A template parameter carries a type and, as a type parameter, a nominal bound:
`<T: text>` is recorded as `template_param.bound` and checked at every application (§5.10). What is left is the
same bound at a field. Every type slot in the kernel is a field typed `type_ref` — `array.element_type`,
`map.key_type`, `record_field.type` — or, for an enum, `type_name`, and either names *any* type; and no field can
refer to the type another field names. §5.10 records both as deferrals: "a type slot cannot be bounded at a
*field* as it can at a template, so a dependency between two fields of one constructor — `record_field.value` on
`record_field.type` (§5.2), an enum's members on its `type` (§7.4) — stays a rule stated in prose rather than
structure."

**Where it is hit.**

- **`enum_type`.** `type: type_name` must name a text family, each member must be a value of it, and no two may
  be one value under its equality — three §7.4 rules the linker checks, because the slot cannot say them.
- **§5.2's value conformance**, which exists because `record_field.value: value` cannot point at
  `record_field.type`.
- **A consumer's meta layer.** `ltr8-io-tson-java-http`'s HTTP vocabulary types a path parameter with a
  `type_ref`, and a URL segment cannot carry a record; its schema records that nothing enforces the restriction.

**Suggested resolution: a bounded, binding parameter at a field's type.** §12.1's `type-param` already admits a
written type; one more alternative at `field-type` uses it:

```
field-type  = ( "<" ws type-param ws ">" / type-ref ) ["?"]
```

At a field's type, `<T: text>` declares a type slot — a field whose value is a reference bounded as a template's
`T` is — and binds `T` for the rest of the body:

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
  any element; no prose rule states it, and the duplicate rule is the set's own uniqueness in `T`'s form.
- **§5.2's value conformance is structural** by the same mechanism, unbounded.
- **A type slot's value has a place.** `enum => enum_type ^ { type?: = identifier }` pins a single type-name
  token on a type slot; every existing enum resolves unchanged.
- **Refinement gains one facet kind.** A type slot narrows along IS-A: a refinement may restate it only with a
  subtype of the source's value. `enum_type` does not need it, its `type` being fixed at construction (§5.7).
- **The bound follows IS-A edges only**, as §5.10's template bound does: `date` has a text form without being
  `text`.

**Why at the field.** Placing the parameter inside the constructor, rather than wrapping the constructor in a
template as `set => <T> !set_type { element_type: T }` does, is what lets one field refer to another: a
template's parameters are bound by the application, before the body is read, and a template cannot be mixed into
a type body. No alternative of `type-ref` begins with `<`, so a `<` at a field type is decided on one token.

**What it costs.**

1. **A dependent record, which the series has not had.** A template's `T` is bound by whoever applies it; this
   `T` is bound by a field value in the data — `!enum_type { type: currency_code  members: [USD EUR] }` — and a
   later field's type depends on it. §5.2 and §8.1 must say so.
2. **Field order.** A streaming reader of a schema document read as data would meet `members` before `type` if
   the author wrote it so, and would buffer it. Requiring the binding field to precede its uses removes the
   buffer; §5.4 already puts a dispatch-order requirement on selectors.
3. **A resolved form.** The sugar needs output the kernel can state: plausibly a lift (§5.3) to a synthetic
   entry of a bounded-reference constructor carrying `bound: type_ref`, and a record-level statement of which
   field binds which parameter. Using the field's own name as the binder (`members: set<type>`) saves the name
   but puts field names in the type namespace, where §5.10's shadowing rule does not reach.
4. **A meta-kernel change.** `record_field` and `enum_type` change shape.

**Open: what a bound may name.** `<T: text>` is a type bound. §5.2's other restriction — a value only on a field
typed by an atom-family instance or an enum — and the HTTP layer's "any scalar" are bounds on a **base kind**,
not on a type. If a bound may name a kind as well as a type, both become structural; if not, they stay in prose.
#3's constructor bound is the nearest thing the series has to a kind bound, and would serve both halves.

**Interpretation chosen:** the spec as written. No field declares a type slot; `enum_type.type` is a `type_name`
whose bound and member conformance the linker checks as §7.4 rules, and §5.2's conformance is enforced by the
resolver as prose requires.

**Status against Revision 37:** open, and **deferred to Revision 38** by the change log. The template bound it
would generalise is running and recorded, but no declaration in the bundled schemas writes a typed or bounded
parameter yet, so the shape is unproven; building the field half on it first would fix that shape twice.

---

## 3. A bound on the constructor: `<T: !C>`, for "any text-valued atom" and "any scalar"

**Section:** [TSON-SCHEMA] §5.10 (a bound names a type, and only a type; *What a parameter does not carry*),
§5.5 (construction transfers kind and no supertypes), §3.3.1 (the structure namespace), §8.1 (`template_param`).

**Kind:** proposal — an open remainder of typed template parameters, carried by the Revision 37 change log (§5).

**What Revision 37 settled.** A type parameter's bound is a `type_ref` into the type-name namespace, checked by
IS-A there, and a bound naming only a constructor is refused at the declaration: `<T: text_type> { a: T }` admits
nothing (§5.10). §5.10 records what that leaves out — "no bound on the *constructor* an argument's type was built
with — the spelling for 'any text-valued atom' or 'any scalar', which a nominal bound cannot say".

**Why a nominal bound cannot say it.** Construction transfers kind and no supertypes (§5.5), so
`identifier`, a kebab `!identifier_type { … }` profile, and `stock_code => !text_type {}` are IS-A nothing, and
`<T: text>`, `<T: atom>` and `<T: identifier_type>` all refuse them. That separation is right for values -- a
`stock_code` must not pass where a plain `text` is declared -- but a bound is a different question: what the body
can do with T, not which nominal type T is.
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

**Interpretation chosen:** the spec as written — `bound` only, and a bound naming a constructor refused.

**Status against Revision 37:** open; not running. Taking it is a kernel change (`template_param.constructor`)
and one spelling in §12.1's `type-param`.

---

## 4. A core sibling and its kernel original are one type by name and body, where an edge would say so

**Section:** [TSON-SCHEMA] §5.10 (*Where a type is read*), §3.3.5 (a core sibling remains a distinct type), §1.3
and §8.1 (`template_param.type` in resolved output).

**Kind:** proposal — an open remainder of typed template parameters, carried by the Revision 37 change log (§5).

**What Revision 37 settled.** Each derived parameter type is read where the held body wrote it: a slot's
declared type in the governing meta's vocabulary, and a routed default, a written type and a bound in the
schema's own namespace. The two can hold one name for two entries with no IS-A edge between them, so §5.10
states: "Two same-named entries with the same resolved body are one type wherever this section compares
parameter types … and for nothing else". Every check §5.10 makes is covered, and this implementation runs it so
(`ParameterTypes.isA`).

**What the sentence does not reach is resolved output.** `{ name: N  type: non_negative_integer }` reads the same
whether the kernel's type or a schema's copy was meant, and the only way to tell is the held text the recorded
type exists to make unnecessary. A consumer of resolved output (§1.3) reading `type` as a reference has no
same-body rule to fall back on.

**Suggested resolution:** an edge — a core sibling records its kernel original as a supertype, so that IS-A
answers every check, either reading of a recorded `type` gives the same answer, and the same-body sentence goes.
What it costs is §3.3.5's "a core sibling remains a distinct type", which the edge would make a subtype instead.

**Interpretation chosen:** the spec as written.

**Status against Revision 37:** open.

---

## 5. A use-site application of a template family base mints the unnameable member §5.2 refuses

**Section:** [TSON-SCHEMA] §5.2 (*A family member is declared*), §5.10 (a record-bodied template is a family
base), §8.1 (a template base "is indexed by its own name, its members being its applications"), §8.2.

**Kind:** defect — an exemption that reaches the case its rule exists for.

**The rule and its exemption.** §5.2 makes a use-site application whose result composes onto a record a
resolver error — "a family member is declared" — and "leaves every other use-site application alone: arrays,
sets, choices, and applications of a family's base, which compose onto nothing." For a base that is a record
entry, that is right: `pet<…>` where `pet` is a record is not an application at all. But since Revision 36 a
record-bodied **template** may itself be a family base (§5.10), and then "the template entry *is* the base: it is
what the position names, what `subtypes` indexes", its members being its applications (§8.1). An application of
that base composes onto nothing *and* is a member.

**What follows.** With `box => <T> { v: T }` and `holder => { any: box  k: box<text> }`, the use site `k:
box<text>` mints an entry under a content-derived name, and that entry is the family's member. This
implementation, following the text, admits the schema, and a value at `any` is then refused with: "'box' is
abstract and has no direct instances, so a value here must name its type -- one of (box_text_04117bb4)" — a name
no document may write (§8.2). With a selector on the base the minted member fails the pin rule instead, which
reports the minted name the same way. Both are the defect §5.2's rule removes, reached through its exemption.

**Suggested resolution:** narrow the exemption to applications that are not members: "applications of a
family's base, which compose onto nothing — except where the base is a record-bodied template, whose
applications are its members (§5.10), and a use-site application of which is the error above". Refusing every
use-site application of a record-bodied template would refuse `k: box<text>` where nothing names `box` bare, so
the narrower reading is the error only where the template is a family base in fact — named bare at a type
position somewhere in the closure, or declaring discriminators. Which of the two is the spec author's call; the
first is total and simpler, and refuses a schema that never dispatches on `box`.

**Interpretation chosen:** the text — the exemption holds, the schema loads, and dispatch at the base reports
the minted name.

**Status against Revision 37:** open.
