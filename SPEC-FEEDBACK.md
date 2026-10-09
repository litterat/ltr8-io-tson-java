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

**Section:** [TSON-DATA] §8.1 ("Not judged is a fifth outcome, not a verdict"; an unavailable schema as one "its
policy or its network did not supply"), §8.2 ("reported as one member of §8.1's fifth outcome"), §9.1 ("A limit
refusal is §8.1's fifth outcome … a refusal is not a verdict"), [TSON-SCHEMA] §10.1 and §11.2 (an unavailable
schema; fetching's allowlists and size limits), and the conformance bullets that cite them ([TSON-DATA] §1.5,
[TSON-SCHEMA] §1.3). [TSON-JSON] §9.4 states the same outcome for JSON and would follow.

**Kind:** defect — the outcome model answers a question no consumer asks, and merges two outcomes a consumer must
tell apart.

**The problem.** §8.1 groups two things as one outcome because both leave a document "without a verdict": a
**refusal** (this processor declined under its §8.2 policy or §9.1 limits) and an **unavailable schema** (this
processor could not obtain what it would judge against, because "its policy or its network did not supply" it).
For the consumer the series is built for — a developer, or a model, checking a file or a request against a
server's schema and policy — the question is *will this document be accepted*, and the two give opposite answers:

- a refusal is a **certain rejection**: this processor will not accept the document, the report names the rule and
  the policy, and the sender holds the fix (rename the field, nest less, or ask for a relaxed policy);
- a schema that could not be obtained is **no answer**: nothing was judged, whoever holds the fix.

Filing both under "not judged" makes the headline outcome ambiguous exactly where a client branches on it, and makes
a refused document indistinguishable, at that level, from one nobody could check. It is also inaccurate: a refusal
*was* judged — against the processor's policy — and the judgement was no.

**And "its policy … did not supply" is a refusal too.** §8.1's unavailable schema covers a schema this processor
*would not* obtain as well as one it *could not*. The first is a decision of this deployment's configuration, as
deployment-specific as an identifier policy: a schema it does not hold and will not fetch (fetching is opt-in and
off by default, §11.2), a host off its allowlist, or a document past its size limit (§11.2). The sender's fix is to
name a schema this deployment will supply, a processor configured otherwise accepts the same document, and no rerun
changes the answer — a refusal by every property above. Only what this processor could not obtain (nothing at the
reference, a host that did not answer or timed out) leaves the document undetermined.

What §8.1 is protecting is right and should stay: a refusal is not a finding about **validity**. Validity is a
property of the bytes and the schema, the same at every processor, and a content-addressed document must mean the
same forever; rules reading Unicode data the UCD declines to freeze cannot decide it. That is a statement about which
*kind* of finding a refusal is, not about whether the document was judged.

**Interpretation chosen.** The validity rule as written; the outcome restated around acceptance.

- `Diagnostic.Code.verdict()` is `false` for the three name-hygiene codes and `LIMIT_EXCEEDED`, as for the five
  schema-fetch codes, `BIND_MISMATCH` and `NOT_IMPLEMENTED`: none is a finding about validity. A second predicate,
  `Code.isRefusal()`, names the refusals: the three name-hygiene codes, `LIMIT_EXCEEDED`, `SCHEMA_NOT_PERMITTED` (a
  schema this deployment does not hold and will not fetch — a closed library's miss, a host off the allowlist, an
  illegal identity, a missing required pin) and `SCHEMA_TOO_LARGE` (past its size limit).
- What could not be obtained is `SCHEMA_NOT_FOUND` (a source that looks beyond its configuration — an origin, a
  directory — and found nothing: nobody judged the document, though the sender most likely holds the fix, a reference
  with nothing behind it being most often a typo), `SCHEMA_UNREACHABLE` and `SCHEMA_TIMEOUT`. A closed library's miss
  is `SCHEMA_NOT_PERMITTED`, never `SCHEMA_NOT_FOUND`: the library is the deployment's whole configuration, so a
  schema outside it is one this deployment will not supply.
- The `tson` CLI's report states acceptance: `outcome` is `ACCEPTED` (nothing reported), `REJECTED` (any invalidity
  or any refusal) or `UNDETERMINED` (nothing rejected the document, but something could not be judged — a schema that
  could not be obtained, a type with no binding, a construct not implemented). One rejection settles it whatever
  else went unjudged, since what was not judged cannot make a rejected document acceptable. Which kind a rejection
  was — portable or local — rides on each diagnostic's code, and the report states the policy and data version once
  (§8.2).
- A fetch refusal names its rule — not held or not permitted, or the size limit — but the report does not state the
  fetch allowlist: telling an untrusted sender which hosts this processor will fetch from is reconnaissance for
  request forgery, where a name policy's statement serves the sender and costs nothing.

**Suggested resolution.** Keep the four categories and the rule that a refusal is never one of them; replace "not
judged is a fifth outcome" with two outcomes beside them:

- **Refused**: this processor declined the document under its own configuration — its §8.2 policy and data version,
  its §9.1 limits, or (Part 2 §10.1, §11.2) a schema it does not hold and will not fetch or one past its size
  limit. The document is not accepted *here*; a refusal is not a finding about validity, MUST be distinguishable
  from the four categories, and MUST name the rule or limit that refused — a conforming processor may legitimately
  not refuse.
- **Undetermined**: this processor could not obtain what it would judge against — nothing at the reference, or no
  answer — and the report says nothing about whether the document conforms or would be accepted. Nobody judged
  it; for nothing at the reference, the sender most likely holds the fix.

A report then answers acceptance as a summary — accepted, rejected (by a category error or a refusal), or
undetermined — with validity still carried by the categories alone. §8.1's sentence on an unavailable schema keeps
"its network did not supply" and moves "its policy" to the refusal, and its closing sentence becomes: "two
conforming processors may legitimately disagree on whether they accept a document, while never disagreeing on
validity." §8.2 and §9.1 cite "refused" in place of "the fifth outcome".

**Status against Revision 37:** open. Running in this implementation as described: `Code.verdict()`,
`Code.isRefusal()`, the sources' and both encodings' readers reporting a closed library's miss as
`SCHEMA_NOT_PERMITTED`, and the CLI's `outcome`, declared in its `diagnostics.tn`. The HTTP layer in
`ltr8-io-tson-java-http` answers who acts, as the CLI's exit code does: its status follows the exit code, and parts
from `outcome` exactly where the exit code does — a rejection beside a gap or a binding mismatch is a 500 or 501
there and `REJECTED` here, as the CLI exits 70 or 78. It too keeps the fetch allowlist out of every response.

---

## 2. The network types are a library of their own, `net.tn`, with a host name beside them

**Section:** [TSON-SCHEMA] §9 ("Core holds only what a schema cannot do without"), §2.2.3 (a local declaration may
not reuse a name the import closure binds), §13.2 and the companion-artifact count in §1; [TSON-DATA] §5.5's table
and §5.6's rule that the schemaless vocabulary leaves with core (`positive_integer`).

**Kind:** proposal — a gap (no host name type), and a cost §9 states but does not apply to the network families.

**The problem.** Two, and one answer to both.

- **There is no host name.** Core has `ipv4` and `ipv6` but nothing for a DNS host name, so a host — a listener's,
  a schema identity's (§2.2.1 keys identities by host) — is `text`, or a pattern each schema writes for itself and
  gets slightly wrong: RFC 1123's labels admit `192.0.2.1`, and a fold chosen as `NFKC_CASEFOLD` admits a fullwidth
  spelling an ASCII type should refuse.
- **Every name core declares is one no importing schema may declare** (§9's own reasoning, from §2.2.3). That is
  the right price for what nearly every schema needs, and a poor one for `ipv4`, `ipv6`, `cidr4`, `cidr6` and `mac`:
  most schemas never use them, and `mac` and `hostname` are plausible names for a schema's own types. Adding
  `hostname` to core would make the cost worse, and break every schema that already declares one.

**Interpretation chosen.** A fifth companion artifact, `net.tn` (`https://tson.io/2026/38/m/net.tn`), governed by
meta and importing nothing:

- **It holds the five network families**, moved from core unchanged — the same empty instances of meta's
  constructors, which stay in meta — **and `hostname`**:

  ```
  hostname => !text_type {
    pattern: "([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?[.])*[a-z]([a-z0-9-]{0,61}[a-z0-9])?"
    max_length: 253
    normalization: ASCII_CASEFOLD
  }
  ```

  An RFC 1123 §2.1 host name: letter-digit-hyphen labels of 1–63 characters, no hyphen at a label's edge, the last
  label starting with a letter (RFC 1123's own disambiguation, so a dotted-quad is never a host name and
  `( hostname | ipv4 )` has no value in both), at most 253 characters, no trailing dot. The fold is `ASCII_CASEFOLD`,
  the member §5.5 names for DNS names: the value is folded before `pattern` judges it, so `NFKC_CASEFOLD` would turn
  a fullwidth or compatibility spelling into ASCII and admit it. U-labels are out; an internationalized name is
  written as its A-labels, and an IDN host type (UTS #46 mapping, not expressible as a pattern) is a separate
  question.
- **`hostname` needs no constructor of its own**: it is regular and has nothing to configure, so it is an instance
  of `text_type`, and a malformed host is a validation error, as any pattern-constrained text is.
- **Core no longer declares the network names.** A schema importing core alone may declare `mac` or `hostname`; one
  that wants the network types imports `net.tn` as well.
- **The schemaless vocabulary keeps every name, by library.** `!ipv4`, `!cidr4`, `!mac` and the rest stay
  schemaless annotations, and `!hostname` joins them, each denoting the type its library declares — so a document
  keeps its meaning when it moves under a schema that imports that library, which is §5.6's guarantee with the
  library named.

**Suggested resolution.** Publish `net.tn` as a companion artifact (§1's count and §13.2's table gain it) holding
the five network families and `hostname` as above; remove the five from core and from §9's list of what core
declares, adding a sentence that a library outside core is what a family most schemas never use belongs in. In
[TSON-DATA] §5.5, add the `!hostname` row (RFC 1123 §2.1 host name, ASCII, compared without case; host value text)
and say which library each row's name comes from; restate §5.6's rule as "a schemaless name denotes the type its
library declares under that name, so a document moving under a schema that imports the library keeps its meaning".
Each meta constructor's "Instance is `ipv4` in core" becomes "in net".

**Status against Revision 37:** open; running on `r2026-38-proposal`. `spec/m/net.tn` and its resolved fixture,
core without the five, `TsonBundledSchemas.NET_ID` loaded by `Tson.standard()`, the schemaless `!hostname`
(`BuiltinTypeVocabulary.HOSTNAME`, checked equal to net.tn's resolved body), and corpus vectors for both the
schemaless and the schema-governed reads.
