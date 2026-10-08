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

**Revision 37 closed every entry open against Revision 36**, and the entries below are open against it. The closed
entries are gone:
the spec now carries their rules — a family member is declared and a family is judged over its closure, the cell
rule at every scoped position, `identifier` as a text family and `enum_type.type`, `value_type` and `void_type`,
typed template parameters with their bounds and the call-site check, `ordered` on every container, the empty set,
`tuple1<T>`, `uri` beside `uri_reference` and `iri_type`, the smaller core, meta's annotation vocabulary, `@doc` as
CommonMark, field-group options and `+`, `normalization` on `text_type`, the leap second, and `optional` against
`voidable`. **This file is the as-built record**, not a pointer to one: where an entry proposes a design this
implementation has built, the entry states the design, what is running, and what is not, so that a reviewer editing
the spec needs nothing beside it. **Where the evidence is a consumer of this library rather than this library** —
the HTTP layer in `ltr8-io-tson-java-http` is one, and this register is the collection point for all of it — the
entry says so and states what is running there on the same terms.

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

**Two directions Revision 37 carried open are not here**: a namespace as a value and a JSON member name that is not
an identifier are being reworked together, and will be raised against Revision 38 as entries of their own. The rest
of what was carried is settled in Revision 37's change log: the policy's home is `policy.tn`, the constructor bound
is declined, and the bounded type slot at a field is withdrawn as the wrong feature for now.

---

## 1. A refusal is a rejection by this processor, not "not judged"

**Section:** [TSON-DATA] §8.1 ("Not judged is a fifth outcome, not a verdict"), §8.2 ("reported as one member of
§8.1's fifth outcome"), §9.1 ("A limit refusal is §8.1's fifth outcome … a refusal is not a verdict"), and the
conformance bullets that cite them ([TSON-DATA] §1.5, [TSON-SCHEMA] §1.3). [TSON-JSON] §9.4 states the same outcome for
JSON and would follow.

**Kind:** defect — the outcome model answers a question no consumer asks, and merges two outcomes a consumer must
tell apart.

**The problem.** §8.1 groups two things as one outcome because both leave a document "without a verdict": a
**refusal** (this processor declined under its §8.2 policy or §9.1 limits) and an **unavailable schema** (this
processor could not obtain what it would judge against). For the consumer the series is built for — a developer, or
a model, checking a file or a request against a server's schema and policy — the question is *will this document be
accepted*, and the two give opposite answers:

- a refusal is a **certain rejection**: this processor will not accept the document, the report names the rule and
  the policy, and the sender holds the fix (rename the field, nest less, or ask for a relaxed policy);
- an unavailable schema is **no answer**: nothing was judged, and nobody present may be able to act.

Filing both under "not judged" makes the headline outcome ambiguous exactly where a client branches on it, and makes
a refused document indistinguishable, at that level, from one nobody could check. It is also inaccurate: a refusal
*was* judged — against the processor's policy — and the judgement was no.

What §8.1 is protecting is right and should stay: a refusal is not a finding about **validity**. Validity is a
property of the bytes and the schema, the same at every processor, and a content-addressed document must mean the
same forever; rules reading Unicode data the UCD declines to freeze cannot decide it. That is a statement about which
*kind* of finding a refusal is, not about whether the document was judged.

**Interpretation chosen.** The validity rule as written; the outcome restated around acceptance.

- `Diagnostic.Code.verdict()` is `false` for the three name-hygiene codes and `LIMIT_EXCEEDED`, as for the five
  schema-fetch codes, `BIND_MISMATCH` and `NOT_IMPLEMENTED`: none is a finding about validity. A second predicate,
  `Code.isRefusal()`, names the four refusals.
- The `tson` CLI's report states acceptance: `outcome` is `ACCEPTED` (nothing reported), `REJECTED` (any invalidity
  or any refusal) or `UNDETERMINED` (nothing rejected the document, but something could not be judged — a schema not
  obtained, a type with no binding, a construct not implemented). One rejection settles it whatever else went
  unjudged, since what was not judged cannot make a rejected document acceptable. Exit `1` is a rejection; `69`,
  `75`, `78` and `70` are undetermined, each naming who could not judge. Which kind a rejection was — portable or
  local — rides on each diagnostic's code, and the report states the policy and data version once (§8.2).

**Suggested resolution.** Keep the four categories and the rule that a refusal is never one of them; replace "not
judged is a fifth outcome" with two outcomes beside them:

- **Refused**: this processor declined the document under its stated policy, data version and limits. The document
  is not accepted *here*; a refusal is not a finding about validity, MUST be distinguishable from the four
  categories, and MUST name the rule or limit that refused — a conforming processor may legitimately not refuse.
- **Undetermined**: this processor could not obtain what it would judge against ([TSON-SCHEMA] §10.1, §11.2) and
  says nothing about whether the document conforms or would be accepted.

A report then answers acceptance as a summary — accepted, rejected (by a category error or a refusal), or
undetermined — with validity still carried by the categories alone. §8.1's closing sentence becomes: "two
conforming processors may legitimately disagree on whether they accept a document, while never disagreeing on
validity." §8.2 and §9.1 cite "refused" in place of "the fifth outcome".

**Status against Revision 37:** open. Running in this implementation as described: `Code.verdict()`,
`Code.isRefusal()`, and the CLI's `outcome`, declared in its `diagnostics.tn`.
