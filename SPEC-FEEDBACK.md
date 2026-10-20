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

## 2. The network types are a library of their own, `net.tn`

**Section:** [TSON-SCHEMA] §9 ("Core holds only what a schema cannot do without"), §2.2.3 (a local declaration may
not reuse a name the import closure binds), §13.2 and the companion-artifact count in §1; [TSON-DATA] §5.5's table
and §5.6's rule that the schemaless vocabulary leaves with core (`positive_integer`).

**Kind:** proposal — a cost §9 states but does not apply to the network families, and a home for a host name (#5).

**The problem.** Every name core declares is one no importing schema may declare (§9's own reasoning, from §2.2.3).
That is the right price for what nearly every schema needs, and a poor one for `ipv4`, `ipv6`, `cidr4`, `cidr6` and
`mac`: most schemas never use them, and `mac` is a plausible name for a schema's own type. The series also lacks a
host name type (#5), which has the same cost — `hostname` is a name schemas already declare for themselves — so
adding it to core would make the cost worse and break every schema that does.

**Interpretation chosen.** A fifth companion artifact, `net.tn` (`https://tson.io/2026/38/m/net.tn`), governed by
meta and importing nothing:

- **It holds the five network families**, moved from core unchanged — the same empty instances of meta's
  constructors, which stay in meta — **and `hostname`**, whose definition is #5's question.
- **Core no longer declares the network names.** A schema importing core alone may declare `mac` or `hostname`; one
  that wants the network types imports `net.tn` as well.
- **The schemaless vocabulary keeps every name, by library.** `!ipv4`, `!cidr4`, `!mac` and the rest stay
  schemaless annotations, and `!hostname` joins them, each denoting the type its library declares — so a document
  keeps its meaning when it moves under a schema that imports that library, which is §5.6's guarantee with the
  library named.

**Suggested resolution.** Publish `net.tn` as a companion artifact (§1's count and §13.2's table gain it) holding
the five network families and `hostname`; remove the five from core and from §9's list of what core declares,
adding a sentence that a library outside core is where a family most schemas never use belongs. In [TSON-DATA]
§5.5, say which library each row's name comes from and add the `!hostname` row #5 defines; restate §5.6's rule as
"a schemaless name denotes the type its library declares under that name, so a document moving under a schema that
imports the library keeps its meaning". Each meta constructor's "Instance is `ipv4` in core" becomes "in net".

**Status against Revision 37:** open; running on `r2026-38-proposal`. `spec/m/net.tn` and its resolved fixture,
core without the five, `TsonBundledSchemas.NET_ID` loaded by `Tson.standard()`, the schemaless `!hostname` and
`!host` (#5, #6), the schemaless `!media_type` (#8), and corpus vectors for both the schemaless and the
schema-governed reads.

---

## 3. A type annotation outside the built-in vocabulary is a resolver error, not a marker

**Section:** [TSON-DATA] §1.5 ("MUST preserve annotations, type annotations outside the vocabulary, and `schema`
directives it does not act on"), §3.2 ("A processor MUST preserve type annotations it does not resolve as
uninterpreted markers attached to their values and MUST NOT reject a document because a type annotation is
unresolved"), §5.1 ("Type annotations whose names are not in the vocabulary are preserved as uninterpreted markers"),
§5.6 ("such a name is an uninterpreted marker here"), §8.1 (the resolver errors at the data-format layer);
[TSON-SCHEMA] §7.1 ("any other type annotation is preserved unresolved — applications SHOULD treat unresolved type
annotations as informational").

**Kind:** design — the one open-world rule left in a series that is closed everywhere else.

**The problem.** Everywhere else a name can be checked, a name that does not resolve is an error:

- a field the record does not declare;
- a `!name` under a schema, which is "an unresolved-type error" ([TSON-SCHEMA] §7.2);
- an `@name` under a governing target ([TSON-SCHEMA] §6).

The schemaless type annotation is the exception. Its vocabulary is closed — §5's table, which §1.5 requires be
"implemented as a unit, so two conforming processors never disagree on whether a built-in name is meaningful" — yet a
name outside it is accepted silently. So `!uiid 550e…`, `!datetme "…"` and `!Date 2026-01-01` pass a check that
reports nothing and validates nothing. This is the failure the series exists to prevent. For the consumer it is built
for — model output checked before use — the document is accepted, and the typo surfaces only when a schema-bound
consumer rejects it, or never.

The reasons for the marker rule do not survive the rest of the design:

- **Forward compatibility.** The vocabulary does not grow. §5 is frozen with TSON version 1, and §1.2's principle 6
  rules out a later version — "There is no TSON 1.1 or TSON 2. New types are added through the type system" — so a
  new type arrives through a schema, never as a built-in name. There is no future name a current processor must
  tolerate.
- **Gradual adoption** ([TSON-GUIDE] §1.4). The path from "annotate the values that matter" to "bind a schema and pin
  it by hash" runs through the built-in annotations, which resolve in both modes and mean the same thing under the
  core library. It does not need `!order { … }` written before `order.tn` exists: an author with no schema leaves the
  tag off or binds one.
- **A stable verdict.** The vocabulary is a property of the revision, not of the deployment, as §1.5's own wording
  guarantees. `!nonesuch` names no type at every conforming processor, so rejecting it is a portable finding about
  the document: a category error, never a refusal (#1). Accept or reject stays the whole model, with no informational
  tier for a "SHOULD treat as informational" to land in.
- **The removed sign bounds** (§5.6). The marker rule is what let `positive_integer` leave the vocabulary without
  breaking schemaless documents that used it. There are no deployed documents to protect, and a rejection naming the
  alternative (`!integer`, or a bound the schema declares) guides the author better than silent acceptance.

**What stays is the `@` annotation.** §3.1's "preserved, ordered metadata with no further interpretation" is right
for a schemaless document, which has no namespace to validate one against. [TSON-SCHEMA] §6's sentence giving "the
preserved-uninterpreted treatment" to schemaless processing cites §3.1 and concerns `@` annotations only, so it
stands. This entry is about `!name`, whose vocabulary is closed.

**Interpretation chosen.** Rejection, ahead of the spec. A schemaless type annotation outside §5's vocabulary is
`UNKNOWN_TYPE_REF`, a resolver error: the document is invalid, and the diagnostic carries the annotation's position.
Both implementations word it the same way: "unknown type '!nonesuch' -- not a built-in type, and no schema is in
scope to define it".

**A host-typed read is no exception.** A host-typed read (§4.1) binds a schemaless document into a declared host
type, so a tag could be read as naming that type (`!order` read into an `Order`). It is not: the verdict on a
document is a property of its bytes, and one that changed with the class a caller binds into would let a bind accept
what `tson validate` rejects — two answers to "will this be accepted" for one document. So a schemaless bind reports
`!order` as `UNKNOWN_TYPE_REF` too.

**Host types as a schema is the caller's explicit choice.** Treating the bound classes as the definitions of a
document's tags is useful, and a schemaless bind into a sealed hierarchy needs it, since a tag naming the member
(`!circle` into a sealed `Shape`) is the only way to choose one. It is a schema in all but form, so the Java
reference makes it one the caller states: `TsonObjectReader.withHostTypes()` makes a tag naming the bound class link
to it and a tag naming a union member choose it, and without it such a tag is reported with a message naming the
control. That is the same move as binding a schema the document does not name — the caller supplies the definitions —
and it sits outside the spec's verdict, as reading a document wider than its class does. The spec needs no hook for
it.

**Suggested resolution.**

- §3.2: replace the last sentence with "A type annotation whose name is not in the built-in vocabulary (§5), in a
  document with no schema in scope, is a resolver error (§8.1): schemaless processing has no other source of type
  names, and a document that names its own types binds the schema that declares them."
- §1.5: "MUST preserve annotations, type annotations outside the vocabulary, and `schema` directives it does not act
  on (§3)" becomes "MUST preserve annotations and `schema` directives it does not act on, and MUST report a type
  annotation outside the vocabulary as a resolver error (§3)".
- §5.1: "Type annotations whose names are not in the vocabulary are preserved as uninterpreted markers (§3.2)"
  becomes "A type annotation whose name is not in the vocabulary is a resolver error (§3.2)."
- §5.6: "such a name is an uninterpreted marker here (§5.1)" becomes "such a name is a resolver error here (§5.1)".
- §8.1, among the resolver errors at the data-format layer: add "a type annotation outside the built-in vocabulary in
  a document with no `!!schema` (§3.2)".
- [TSON-SCHEMA] §7.1: "and any other type annotation is preserved unresolved — applications SHOULD treat unresolved
  type annotations as informational" becomes "and any other type annotation is a resolver error ([TSON-DATA] §3.2)".
- No change to §3.1, [TSON-SCHEMA] §6 or [TSON-GUIDE] §1.4.

**Status against Revision 37:** open; running on `r2026-38-proposal`. Both implementations already reject:

- the Java reference reports `UNKNOWN_TYPE_REF`, and the CLI exits 1, for a schemaless `!foo`, `!positive_integer` or
  `!order`; its object reader does the same into an `Order` unless the caller asks for `withHostTypes()`;
- the TypeScript port (0.37.0) rejects the same names;
- tson.io's live validator shows it in its "no schema (Class 1)" scenario.

Corpus vectors in `class1/reader` pin the rejection for `!foo`, `!positive_integer`, `!Uuid` and `!order`.

---

## 4. An identity is an IRI whose host is a `host` value: one spelling for `!!id`, any spelling in a reference

**Section:** [TSON-DATA] §2.2.1 (canonical identity: "lowercase host plus path"; "The argument is read as an
IRI-reference (§3.3), so a host or path may carry characters beyond US-ASCII as themselves; identity compares them as
written, and a percent-encoded spelling of the same characters is a different identity"), §3.3, §5.5, §7.1;
[TSON-SCHEMA] §10.1, §11.2 (fetching and its allowlists); [TSON-JSON] §3.5 (the `TSON-Schema` header field); #5, #6.

**Kind:** underspecification, with a consequence that is a defect. Raised by the HTTP layer in
`ltr8-io-tson-java-http` (its `UPSTREAM.md` #3), whose fetch allowlist, header and schema catalog all meet it, and
confirmed here.

**The problem.** Since Revision 37 an identity is an IRI-reference, so a host may be written in U-labels
(`bücher.example`, `ตัวอย่าง.ไทย`) and a path in characters beyond US-ASCII. That is right — an identity should be
writable in its publisher's language — but §2.2.1's rules were written for US-ASCII, and four things follow:

- **"Lowercase host" has no meaning beyond US-ASCII.** The input rule requires a lowercase host without saying by
  which case mapping, at which Unicode version.
- **Two encodings of one character are two identities.** `é` precomposed and `e` followed by U+0301 COMBINING ACUTE
  ACCENT read alike and compare unequal, in a host or a path, and nothing requires either.
- **One DNS host has two identities.** `bücher.example` and its A-label form `xn--bcher-kva.example` name one host,
  and a fetch reaches it by either; §2.2.1 makes them two documents, and nothing says a publisher must pick one.
- **It cannot be named over HTTP.** An RFC 9651 String, which [TSON-JSON] §3.5's `TSON-Schema` field is, holds
  printable US-ASCII only, so a reference beyond US-ASCII goes in the field as its URI spelling —
  `xn--bcher-kva.example`, `%C3%A9`. §3.5 requires the field and `!!schema` to agree by canonical identity, which
  compares as written, so the two never agree: a non-ASCII identity can be named in a document and in no header.
  A server publishing schemas at their identity paths meets the same gap from the other side: its request paths
  arrive in URI spelling, and nothing says which identity path one names.

Restricting an identity to US-ASCII would close all four, at the price of the international names the IRI form
exists to admit. The resolution below keeps them, and it does so by defining the host once, as a type the series
already needs, rather than with rules of §2.2.1's own.

**Interpretation chosen.** The resolution below, ahead of the spec. An identity that breaks a rule is a resolver
error, as a `!!id` that is no identity already is here; an IP literal with a zone (`[fe80::1%25eth0]`) is not an
IRI-reference at all (RFC 3986 has no zone; RFC 6874 adds one), so its directive fails to parse first. In the HTTP
layer, `TsonSchemaHeader.format` writes a reference as it stands, so a non-ASCII identity
yields an invalid field value, and `TsonSchemaCatalog` matches an `!!id`'s path, read through `java.net.URI`,
against the request path, so whether a percent-encoded request reaches a non-ASCII path is the JDK's decoding and
no rule's.

**Suggested resolution.** The identity is the IRI, split as §2.2.1 already splits it, into a host and a path.
Diagnostics, registries and every result name the IRI in its canonical form, whichever spelling was written.

- **The host is a `host` value, defined in Part 1.** RFC 3987's `ihost` is `IP-literal / IPv4address / ireg-name`.
  An identity takes the DNS profile of it, which is what §5.5's `!host` row is (#6): an `ireg-name` MUST be a
  `!hostname` (#5), an `IPv4address` an `!ipv4`, and an `IP-literal` an `!ipv6`, so IPvFuture and zone identifiers
  are refused. §2.2.1 cites those rows. They are the definitions, `net.tn`'s instances name the same types as its
  `ipv4` already does, and §2.2.1 — which every processor implements, with or without schema support — depends on
  nothing a schema imports. RFC 3987 admits more (`_`, sub-delimiters, percent-encoding, any `ucschar`) because a
  registered name need not be DNS; an identity's is, since it is what a fetch looks up.
- **Canonical identity is the host's canonical text plus the path**, compared byte-for-byte as now. The host's
  canonical text is `host`'s: a name in lowercase U-labels, an IPv4 address as its dotted-quad, an IPv6 address in
  RFC 5952 text, bracketed as the IRI grammar requires. Validity, NFC and case are `hostname`'s rules and are not
  restated here.
- **The path is NFC.** Characters beyond US-ASCII in the path MUST be in Normalization Form C, as RFC 3987 §5.3.2.2
  recommends; a path that is not is an error, not a candidate for normalization. A path needs no type of its own: an
  identity's path is not a file path or a route, and nothing but §2.2.1 would use one. The rest of §2.2.1's path
  rules stand.
- **`!!id` is written in canonical form, and only so.** §2.2.1's posture stays for a document's own name: an
  identifying reference MUST already be canonical — the host as above, the path NFC with no character beyond
  US-ASCII percent-encoded, a percent-encoded reserved character in uppercase hex, the rest of §2.2.1's form rules
  unchanged. Every published document then has one spelling of its own name, which is what serving it at its
  identity path relies on. `!!id:"https://Example.COM/x.tn"` and `!!id:"https://xn--bcher-kva.example/x.tn"` stay
  errors.
- **A reference may use any spelling of the same identity**, and is read to canonical form before comparison: a host
  in any spelling `host` admits (A-labels, ASCII case, an uncompressed or bracketed IPv6 address), and a path in its
  URI spelling read back (RFC 3987 §3.2), each percent-encoded UTF-8 sequence of a character beyond US-ASCII to that
  character and hex case ignored throughout, so `%c3%a9` and `%C3%A9` are one, as are `%2f` and `%2F`. So
  `!!schema:"https://xn--bcher-kva.example/sch%c3%a9mas/x.tn"` names the document whose `!!id` is
  `https://bücher.example/schémas/x.tn`. What has no IRI form stays an error: a percent-encoding that is not the
  UTF-8 of an IRI character (`%FF`, an encoded bidi control), and an encoded character that is not NFC. A
  percent-encoded unreserved ASCII character keeps today's rule and is an error. This covers every reference —
  `!!schema`, `!!import`, a schema library's keys, the `TSON-Schema` field — and a request path: a server publishing
  schemas at their identity paths reads a request path under this rule, and the result is the identity's path.
- **Unicode versions.** `hostname`'s validity is IDNA2008's (#5), computed per Unicode version and monotone:
  a code point once valid stays so. An older processor therefore refuses an identity whose host uses a character it
  does not have, exactly as §7.1 already has an identifier's validity grow with the declared Unicode version, and
  the report names the version the processor validated against (§7.1's "SHOULD document which Unicode version").
  NFC is covered by Unicode's normalization stability policy. The cost is real for every implementation: each needs
  RFC 5892's property to compare identities, and none can borrow a platform URL parser that applies UTS #46's
  mapping — the WHATWG URL parser, in JavaScript and every browser, does, and answers differently. The corpus
  should carry vectors over the property, pinned to a Unicode version, alongside this rule.

How a processor spells an identity on a wire that needs US-ASCII — a fetch's request, an HTTP field — is its own
business. [TSON-JSON] §3.5 then needs a sentence: a reference beyond US-ASCII is carried in its URI spelling (RFC
3987 §3.1, the host by ToASCII). A processor that reports a fetch it made in that spelling should name both — the
identity, and the URI it requested — since the first says which schema and the second is what DNS and a proxy saw.

**Status against Revision 37:** open; running on `r2026-38-proposal`. `CanonicalIdentity.canonicalize` reads a
reference as above (the host through `io.ltr8.net.Host`, #6), `CanonicalIdentity.validate` holds a schema's `!!id` to
the canonical form and names it in the refusal, and `CanonicalIdentity.toUri` gives the URI spelling. The fetching
sources key their hosts by the identity's host form, so a host is allowed by name in either label form, fetch an
origin by the URI spelling, and name both spellings when a fetch beyond US-ASCII fails. Corpus vectors in a new
`class1/identity` layer (RUNNER.md rule 3e) cover both directives: each canonical form, each spelling a reference may
use, and each refusal. The HTTP layer's `deployment.tn` types `schema_hosts` as `[hostname]`, which #5 now makes
international, pinned until it adopts it by `UpstreamGapsTest.anIdentityWithANonAsciiHostCannotBeAllowListed`.

---

## 5. `hostname` is a domain name in either label form, not an ASCII pattern

**Section:** Revision 38's `net.tn` (#2); [TSON-DATA] §5.5's `!hostname` row as #2 proposes it, §8.2; [TSON-SCHEMA]
§5.5 (text normalization), §9; #4, #6.

**Kind:** proposal — a host name type as wide as the identities it is needed to name.

**The problem.** No `text_type` can state a host name beyond ASCII. The nearest one is RFC 1123's:

```
hostname => !text_type {
  pattern: "([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?[.])*[a-z]([a-z0-9-]{0,61}[a-z0-9])?"
  max_length: 253
  normalization: ASCII_CASEFOLD
}
```

An RFC 1123 §2.1 host name: letter-digit-hyphen labels of 1–63 characters, no hyphen at a label's edge, the last
label starting with a letter (so a dotted-quad is never a host name and `( hostname | ipv4 )` has no value in both),
at most 253 characters, no trailing dot, compared without ASCII case. An international name is accepted only as its
A-labels, and as text, so its U-label spelling is a different value where it is accepted at all. That is narrower
than the series: identities admit U-labels (#4), so an allowlist typed `[hostname]` cannot name an identity's host
as the identity writes it, and an operator who types `bücher.example` into a configuration is refused. No
`text_type` can fix it: U-label validity is RFC 5892's property, which no pattern here states, and the equivalence
of a U-label and its A-label is Punycode, which no normalization reaches.

**Proposed, and running.** `hostname` is an atom, an instance of a new meta constructor, defined by [TSON-DATA]
§5.5's row and instanced in `net.tn`:

```
hostname_type => atom & atom_specification & {
  spec?:      = "https://www.rfc-editor.org/rfc/rfc5890"
  allow_idn?: boolean ~ true
}
```

- **Lexical space:** labels that are LDH labels, A-labels or U-labels (RFC 5890), in any mix, ASCII letters in
  either case; each label at most 63 octets and the name at most 253 in its A-label form. A U-label is RFC 5890
  §2.3.2.1's: NFC, every code point PVALID or meeting its contextual rule under RFC 5892, the name meeting RFC
  5893's bidi rule.
- **Boundaries an operator will meet,** each stated in the row so that none looks like a bug:
  - *Uppercase beyond ASCII is refused.* `EXAMPLE.com` is a host name and `BÜCHER.example` is not, because IDNA2008
    has no uppercase in a U-label, and mapping one would bring UTS #46 into validity. In a hand-written file that
    looks inconsistent, so the refusal names the fix: "write it in lowercase: `bücher.example`". Computing the
    suggestion is the message's business and never the verdict's.
  - *No trailing dot.* A fully qualified name pasted as `example.com.` is refused, and the refusal says to drop the
    dot.
  - *The last label does not begin with a digit*, keeping `( hostname | ipv4 )` without a value in both.
  - *Percent-encoding is refused.* `b%C3%BCcher.example` is URI syntax and not a host name.
- **Value:** the domain name. `Example.COM` and `example.com` are one value, and so are `xn--bcher-kva.example` and
  `bücher.example`. Its two forms are views of the one value, each recoverable from the other: the U-label form for
  display and an IRI, the A-label form for DNS, a URI and HTTP; ToASCII and ToUnicode between them are Punycode
  (RFC 3492) over a valid label and need no table. The canonical text is the lowercase U-label form, which is what
  a writer emits and the form #4 takes for an identity's host, so an allowlist entry and an identity's host compare
  by name in either spelling.
- **`allow_idn`:** false admits only names with no internationalized label, in either spelling, so
  `xn--bcher-kva.example` is refused as `bücher.example` is: a property of the name, for a system that cannot carry
  one, and not a restriction on how a name is written. There is no facet choosing a spelling, since the value is the
  name, and none restricting scripts: script data is what [TSON-DATA] §8.2 keeps out of validity.
- **Homographs are the allowlist's to stop, and it does.** A document may now name a host such as
  `schemаs.example.com` with a Cyrillic `а`. A host allowlist typed `[hostname]` compares by name and has no notion
  of look-alikes, so that host is refused unless the operator listed it — the property worth stating where the
  type meets a deployment. A look-alike defence for host names beyond that would be policy (§8.2), not the type.
- **Stable enough to name a content-addressed document.** Nothing is mapped, so UTS #46's tables never enter.
  RFC 5892's property is computed per Unicode version, but it is built so that a code point once PVALID stays so: a
  newly assigned character can become valid, and a valid name never becomes invalid — the same monotone growth
  [TSON-DATA] §7.1 states for identifiers. That is what lets #4 make an identity's host a `hostname`.
- **Binding:** a `String` component binds the canonical text, so a host's list of names stays `List<String>`. A
  host-name type of its own is an addition, not a requirement (the Java reference's is `io.ltr8.net.HostName`,
  beside its address types), parsed from either spelling and exposing both forms. It is also `host`'s name member
  (#6), so one definition serves this atom, `host` and an identity's host (#4).

**Suggested resolution.** Meta gains `hostname_type` as above, and `net.tn` declares `hostname => !hostname_type {}`.
The [TSON-DATA] §5.5 row reads: "`!hostname` — a domain name (RFC 5890): LDH labels, A-labels or U-labels, no
uppercase beyond ASCII, no trailing dot; equal by name; host value a host name, canonical text its lowercase
U-labels".

**Status against Revision 37:** open; running on `r2026-38-proposal`. Meta's `hostname_type` and net.tn's
`hostname => !hostname_type {}`, read by `tson-atom`'s `HostnameParser` through `io.ltr8.net.HostName`. Its
validity is `ltr8-unicode`'s `IdnaProperty`, RFC 5892's derived property computed by the RFC's rules at Unicode 16.0
and checked against IANA's last published table (Unicode 12.0) for every code point that version assigns, with no
difference, and `BidiRule`, RFC 5893's. Corpus vectors in `class1/vocabulary` (both label forms, a Thai name, and
each boundary) and `class2/validate` (`allow_idn`).

---

## 6. `host`: a host name or an IP address, one string-class atom

**Section:** Revision 38's `net.tn` (#2); [TSON-DATA] §5.5 (`!ipv4`, `!ipv6`, and `!hostname` as #5 proposes it);
[TSON-SCHEMA] §5.4 (choice disjointness); RFC 3987 `ihost`; #4.

**Kind:** proposal — a gap, raised by the HTTP layer in `ltr8-io-tson-java-http` (its `UPSTREAM.md` #3) and needed
by #4.

**The problem.** A host as a deployment writes one is a name *or* an address: a listener binds `localhost`,
`127.0.0.1` or `::1`, and an allowlist names hosts of all three kinds. There is no type for it.
`( hostname | ipv4 | ipv6 )` resolves, but §5.4 makes all three string-class, so the choice is not disjoint and
every value needs a tag — `host: !ipv4 "127.0.0.1"` in a file an operator writes by hand. The HTTP layer's
`listener.host` therefore stays `text`; at the choice, an untagged value is refused with *"'host' is a choice -- a
value at this position requires an explicit type annotation"*. The three value sets do not meet — a host name's
last label does not begin with a digit (#5), and only an IPv6 address has a colon — but §5.4 deliberately does not
prove disjointness from patterns, and that rule should stay. The answer is one type, not a choice.

#4 needs the same type: an identity's host is RFC 3987's `ihost` profiled to DNS, which is exactly a host name, an
IPv4 address or an IPv6 address.

**Proposed, and running.** `host`, defined by a [TSON-DATA] §5.5 row and instanced in `net.tn` from a new meta
constructor:

```
host_type => atom & atom_specification & {
  spec?: = "https://www.rfc-editor.org/rfc/rfc3987"
}
```

- **Lexical space:** the union of `hostname`'s (#5), `ipv4`'s and `ipv6`'s, which do not overlap, plus an IPv6
  address in brackets. One string-class atom, so `listener.host: host` takes `localhost`, `"127.0.0.1"`, `"::1"`
  and `"[::1]"` untagged.
- **Brackets are a second spelling.** RFC 3987 brackets an IPv6 address in a host so that a URI can find the port
  after it, and operators copy `[::1]` out of URLs and server configurations (`listen [::1]:80`). Since equality is
  by value, `[::1]` and `::1` are one value, as #5's two label forms are one name, and the canonical text is
  unbracketed. The bracketed spelling belongs to a host position: `ipv6` itself still refuses it, since an address
  on its own has no port to separate. It is also what makes an identity's host (#4) a `host` spelling exactly as
  the IRI writes it. Percent-encoding stays refused, being URI escaping rather than a spelling of the value.
- **Value:** a host name or an address, never both, each member keeping its own equality: a name by name (#5), an
  address numerically, so `::1` and `0:0:0:0:0:0:0:1` are one value. A name never equals an address, and the two
  address families never equal each other: `127.0.0.1` and its IPv4-mapped form `::ffff:127.0.0.1` are different
  values, the first an `ipv4` and the second an `ipv6`. The canonical text is the member's: lowercase U-labels, a
  dotted-quad, unbracketed RFC 5952 text — the form #4 takes for an identity's host, brackets aside, so an
  allowlist typed `[host]` matches identity hosts by the type's own equality.
- **No facets yet.** A position that wants only names, or only addresses, uses `hostname`, `ipv4` or `ipv6`. The
  likely first facet is `allow_zone`: zone identifiers (`fe80::1%eth0`, RFC 6874) are refused, which is right for an
  identity and for most hosts, but a listener binding a link-local address needs one.
- **Binding:** a `String` component binds the canonical text, as #5's does. A host value of its own — a host name or
  an address — is an addition, not a requirement (the Java reference's is `io.ltr8.net.Host`, a `HostName` or a
  `Host.Address`).

**Suggested resolution.** Meta gains `host_type`, and `net.tn` declares `host => !host_type {}`. [TSON-DATA] §5.5
gains a row: "`!host` — a host name, IPv4 address or IPv6 address (RFC 3987 `ihost`, DNS names only, IPv6 with or
without brackets, no zone identifier); equal by its member's equality, the two address families distinct; host
value a host name or an address, canonical text the member's, unbracketed".

**Status against Revision 37:** open; running on `r2026-38-proposal`. Meta's `host_type` and net.tn's
`host => !host_type {}`, read by `tson-atom`'s `HostParser` through `io.ltr8.net.Host`, with corpus vectors in
`class1/vocabulary` (each member, both IPv6 spellings, the zone refusal) and `class2/validate` (untagged at a field).
The HTTP layer's `listener.host` is `text` until it adopts the type.

---

## 7. §7.7 says identifier validity is the same at every Unicode version; §7.1 says it grows

**Section:** [TSON-DATA] §7.1 ("The property-based components grow with the Unicode version … Growth is monotone —
characters that were lexer errors become token characters, and valid documents remain valid under later
versions"; "Implementations MUST support these properties for their declared Unicode version"), §7.7 ("so every
implementation at every Unicode version returns the same verdict on the same text").

**Kind:** internal inconsistency.

**The problem.** §7.1 is right: `XID_Start` and `XID_Continue` are stable — no character ever leaves them — but they
are not frozen, and a newly encoded script enters them. A name in that script is a lexer error at a processor on an
older Unicode version and valid at a newer one. §7.7's sentence claims the stronger property, that the verdict is
the same at every version, which no grammar over these properties has. What §7.7 needs is the weaker one §7.1
states: a valid document stays valid under every later version, so a content-addressed schema's validity never
regresses. Name hygiene (§8.2) is kept out of validity for the property that difference turns on — its data can
move a name from valid to refused.

**Interpretation chosen.** §7.1's. This implementation declares Unicode 16.0 (`Xid.UNICODE_VERSION`, the version
its identifier properties are checked against), and a character outside `XID_Start`/`XID_Continue` at that version
is a lexer error.

**Suggested resolution.** In §7.7, replace "so every implementation at every Unicode version returns the same verdict
on the same text, and a content-addressed schema's validity (§2.2.1) rests on nothing that a Unicode Character
Database refresh can change" with "so a text valid at one Unicode version is valid at every later one (§7.1), and a
content-addressed schema's validity (§2.2.1) can be lost to no Unicode Character Database refresh; a processor on an
earlier version may refuse a name a later one accepts, and its report names the version it validated against". #4
relies on the same property for an identity's host.

**Status against Revision 37:** open; wording only.

---

## 8. `media_type`: a media type and its parameters, one atom in `net.tn`

**Section:** Revision 38's `net.tn` (#2); [TSON-DATA] §5.5 (a `!media_type` row); [TSON-SCHEMA] §9; RFC 6838
(media type names), RFC 6839 (structured suffixes), RFC 9110 §8.3.1 (parameters).

**Kind:** proposal — a gap. Raised by the HTTP layer in `ltr8-io-tson-java-http`, whose `acceptingJson` admits
`application/json` and any `+json` type.

**The problem.** A media type labels the format of content, wherever the content is: inline, as
`{ data: bytes  type: media_type }`, or linked, as `{ href: iri  type: media_type }`. That pair is how data names
external content without the processor fetching it, which is the line `!!include` was refused on. A type describes
the content and never follows the reference; a consumer fetches under its own policy. The label is everywhere: in
`Content-Type`, in JSON Schema's `contentMediaType`, in CloudEvents' `datacontenttype` and in ActivityPub's
`mediaType`. Written as `text`, it has the wrong equality. The type, subtype and parameter names compare without
case, a quoted parameter value equals its token form, and parameter order carries no meaning. A parameter value,
though, keeps its case — except `charset`'s. No fold over the whole text states that.

**Proposed, and running.** `media_type` in `net.tn`, an instance of a new meta constructor:

```
media_type_type => atom & atom_specification & {
  spec?:             = "https://www.rfc-editor.org/rfc/rfc6838"
  allow_parameters?: boolean ~ false
  types?:            [text]
  suffixes?:         [text]
}
```

- **Grammar:**
  - The type and subtype are RFC 6838 §4.2's `restricted-name`: at most 127 characters, the first a letter or
    digit, the rest letters, digits and `! # $ & - ^ _ . +`.
  - Parameters follow RFC 9110 §8.3.1: `;` with optional whitespace around it, a token name, `=`, and a token or
    quoted-string value.
  - Nothing beyond US-ASCII is admitted.
  - A media range (`*/*`, `text/*`) is a pattern over media types, `Accept`'s syntax, and is outside the grammar.
- **Value:** the type, the subtype and a map of parameters.
  - The type, subtype and parameter names are compared without ASCII case.
  - Parameters are a map, so order carries no meaning and a repeated name is refused.
  - A quoted value equals its token form.
  - A parameter value keeps its case, except `charset`'s, which folds (RFC 9110 §8.3.2). The list is closed and
    holds `charset` alone. "Unless its own registration says otherwise" would leave every processor to hold the
    registry, and two at different registry states would disagree.
- **Canonical text:** lowercase type, subtype and parameter names, and the `charset` value folded. Parameters are
  sorted by name, each value in token form where it is one and quoted-string otherwise, joined by `;` with no
  whitespace: `text/html;charset=utf-8`.
- **Facets**, permissions in `uri_type`'s manner, withdrawn and never granted back:
  - `allow_parameters`, false by default, so `media_type` is a media type proper, not a `Content-Type` value; a
    position that takes parameters declares its own instance, `!media_type_type { allow_parameters: true }`.
  - `types`, the top-level types admitted.
  - `suffixes`, RFC 6839's structured suffixes: a suffix `s` admits a subtype ending `+s`, and the subtype `s`
    itself, so `suffixes: [json]` admits `application/json` and `application/ld+json`.

  A value outside a facet is a constraint violation. A token outside the grammar is a parse failure.
- **Host value:** `io.ltr8.net.MediaType`. A `String` component binds the canonical text, as `hostname`'s does.
- **Where it lives:** `net.tn`, not core. An opt-in library is what keeps the name from colliding with a schema's
  own `media_type` — an OpenAPI conversion's Media Type Object is the likely one. None of the 497 conversions in
  `ltr8-io-tson-benchmarks` declares a type named `media_type`, `mime_type` or `content_type`; the concept
  appears there only as fields typed `text` (`content-type?: text`, `Content-Type?: text ~ "application/json"`).
  The name is RFC 6838's term; "MIME type" is the legacy one.

**Not proposed: `uri_template`.** An RFC 6570 template is not a value that names or describes anything but a small
program producing URIs. Validating one checks syntax alone, its equality is textual, and its meaning is in the
expansion, which belongs to an HTTP library. It is the line media ranges are refused on: a pattern over values is
not a value. A schema that needs one declares a pattern-constrained `text`.

**Suggested resolution.** Meta gains `media_type_type` as above, and `net.tn` declares
`media_type => !media_type_type {}`. [TSON-DATA] §5.5 gains a row: "`!media_type` — a media type (RFC 6838),
without parameters; type, subtype and parameter names compared without case, a `charset` value too; host value a
media type, canonical text lowercase with parameters sorted".

**Status against Revision 37:** open; running on `r2026-38-proposal`. Meta's `media_type_type` and net.tn's
`media_type`, read by `tson-atom`'s `MediaTypeParser` through `io.ltr8.net.MediaType`, with corpus vectors in
`class1/vocabulary` (forms, equality, refusals) and `class2/validate` (each facet).

---

## 9. The `type` kind: `atom`, `product` and `sum` IS-A `type`, and `data` does not

**Section:** [TSON-SCHEMA] §4.1 (the base kinds, each "composing with `top` via `top & {}`"), §8.1 (kind
determination, "the base kind … excluding `top`"; kind derivation), §13.2's `meta-kernel.tn` row.

**Kind:** proposal — the kernel can say "a type" only by exclusion.

**The problem.** §4.1 divides the kernel's kinds in two. `atom`, `product` and `sum` describe the shape of a data
value; `data` describes something that is not one, and "naming one where a type is expected is a resolver error".
"A type" is therefore defined only as "not `data`", and nothing in the kernel names it. A slot that must refer to a
type — a field's `type`, a choice's variants, an element type, a `reference`'s `target` — has nothing to say so with,
and every such constraint lives in a resolver's code. Once data is not the only non-type kind (a scope, a namespace
of names), "not `data`" is not even the right test.

**Proposed, and running.** A kernel entry for the shared kind, which the three base kinds of data values compose
with:

```
type    => top & {}
atom    => type & {}
product => type & { access_pattern: product_access_type  size_type: product_size_type }
sum     => type & {}
data    => top & {}
```

- **An entry is a type exactly when it IS-A `type`.** `data` IS-A `top` alone, and so do `reference` and
  `template`, which compose with `top` directly, as now.
- **`type` is not a base kind.** §8.1's kind determination reads `atom`, `product`, `sum` or `data` off the chain,
  "excluding `top`", and should say "excluding `top` and `type`". Kind derivation's branch (3) already names only
  `atom`, `sum` and `data`. No entry's kind changes. An entry composing with `type` alone is PRODUCT by the
  structural default, exactly as one composing with `top` alone is.
- **IS-A `top` is still the constructor predicate** (§3.3.1, §4.2), since `type` IS-A `top`.
- **What moves is complete supertype lists.** Every constructor under `atom`, `product` or `sum` gains `type` in
  its resolved `supertypes` (`integer_type`'s is `[atom top type]`), and `top`'s subtypes gain `type`. A schema's
  own declared supertypes are untouched.

This is the kernel half of typed references. A later rule can say "this slot names a type" as IS-A `type`, rather
than as one more exclusion in a resolver.

**Suggested resolution.** In §4.1: "The kernel defines `top` as the structural root, `type => top & {}` as the kind
of the kinds that describe data values, and four base kinds: `atom`, `product` and `sum`, each composing with `type`,
and `data`, composing with `top`. An entry describes a type exactly when it IS-A `type`." In §8.1, kind determination
excludes `top` and `type`. §13.2's table names `type` among the kernel's entries.

**Status against Revision 37:** open; running on `r2026-38-proposal`. `meta-kernel.tn` declares `type`, and the
three kinds compose with it; the resolved fixtures carry the entry and the longer chains (`ResolvedFixtureTest`), and
the bundled schemas are restamped. The Java value model mirrors it: `schema.meta.Type` is a sealed interface between
`Top` and `Atom`/`Product`/`Sum`, so a body is a type exactly when it is an `instanceof Type`. No check reads `type`
yet, so no verdict changes: the corpus passes unchanged.

---

## 10. A record's field names have a type: `record.name_type`

**Section:** [TSON-DATA] §1.5 (the Class 1 name MUST), §2.5 ("A field name is an identifier at every layer"), §2.8
(record detection: "field names are identifiers"), §7.1 (`{ "_id": 1 }` a parse error), §7.2.1, §7.7, §8.2;
[TSON-SCHEMA] §5.2 (`record`, `record.discriminators`), §5.4 (an enum's class read off its `type`), §5.5, §5.8,
§5.11 (`field_group`'s member slots), §7.4 (`enum_type.type`, `text_enum`), §7.7, §8.1 (the `template`
constructor's `discriminators`), §8.2's constructor-refinement table (`text_enum`), §10's meta-kernel inventory,
§12.1, §13.2 (the artifact pins); [TSON-JSON] §1, §3 (the annotation members), §6.1.1, §9.4.

**Kind:** proposal, under consideration for adoption — on the branch `record-name-type`, PR into
`r2026-38-proposal`.

**The problem.** A field name is an identifier at every layer, so `{ "@id": x }` and `{ "$ref": x }` are parse
errors, and §2.5's remedy is a map: "a key that is not a name belongs in a map". That serves TSON's own data. It
fails a schema translated from JSON-LD, OpenAPI or JSON Schema, whose objects are records — a fixed set of declared
members — with names such as `@context`, `@id` or `$ref`. A map loses the record's declared shape, and renaming the
member loses the round trip.

**The idea.** Copy `enum_type.type`, which already says which family an enum's members belong to. A record gains
`name_type?: type_name`, the family its field names belong to, and `enum_type.type` is renamed
`name_type` to match. The default is the series' own
`field_name`, an identifier, so nothing changes unless a record or a supertype says so. A record translated from JSON names a
relaxed type, defined by the translation and not by the series, such as
`json_name => !identifier_type { start_add: "@$" }`, or `!identifier_type { medial: " " }` for words.

**Decided so far:**

- **One field for one mechanism: `name_type`, on both.** An enum's members and a record's field names are names
  drawn from an identifier family, held to it by the same three rules — the type is an identifier family, every
  declared name is a value of it, no two are one value under its equality. So both constructors spell it
  `name_type`: `enum_type { name_type: type_name  members: enum_set }`, `enum => enum_type ^ { name_type?: =
  identifier }`, and `record`'s `name_type?: type_name ~ field_name`. The bare `type` said nothing about what it
  typed. Only a schema writing `!enum_type { … }` itself changes; `!enum [...]` pins it. Rejected: `label_type`,
  which fits an enum's members and is never what a field name is called.
- **A name type is an identifier family, on both, and every name meets §8.2.** Revision 37 lets an enum's `type`
  name any text family and drops the per-name rules where it is no identifier family (`text_enum`); a record's type
  would have inherited the same split. Both now name an identifier family — an instance whose constructor IS-A
  `identifier_type` — so every enum member and every field name is a name, judged under its type's profile, whose
  added characters are its own. What `text_enum` admitted needs no text family: `identifier_type`'s `medial` facet
  places a character only between two others and never beside another, so `!identifier_type { medial: " " }` admits
  `lightly active` and `first name` and refuses a leading, trailing or doubled space, with full hygiene. A closed
  set of text that is no name is no enum: it is the text family's own `members` facet, `!text_type { members:
  ["80" "443"] }`, string-class as every text atom is. So **`text_enum` is removed** from the kernel; the series
  supplies `enum` over its own identifier, and an author declares the identifier type their names need and an
  `enum_type` over it. The linking fact recording which enums were text-membered (`textEnums`) goes with it, an
  enum's discrimination class being read off its members as every identifier enum's already was. *Implemented on
  the branch.*
- **The default does not change.** A schemaless record's field names are identifiers. A field-name position admits
  any single-line token, and the identifier match moves from the parser to the record, so a schemaless
  `{ "first name": 1 }` is a resolver error rather than a parse error.
- **A document that names a schema has its field names judged by that schema, and only by it.** Part 1 §1.5 makes a
  Class 1 processor refuse a field name that is no identifier, and §1.5 also says a Class 1 processor does not act
  on schemas; under this proposal a document naming a schema may hold `"@id"` validly, so the two conflict. The
  rule running here: §2.5's identifier rule holds a field name in a document that names no schema; in one that
  names a schema, a processor reading it without that schema — a Class 1 processor, or a Class 2 one told to
  ignore it — holds field names to no name rule, judging what it can (syntax, type-refs and annotation names,
  which are identifiers at every layer, the built-in vocabulary, and the look-alike relation over a record's
  set). A Class 2 processor that cannot obtain or load the schema leaves the document unjudged, [TSON-DATA]
  §8.1's fifth outcome, rather than judging it by the schemaless rule. Part 1 §1.5's MUST would read "a field
  name that is not an identifier, in a document that names no schema". This keeps Part 1 a layer that Parts 2
  and 3 add to rather than modify: Part 1 decides which documents its own rule reaches, and leaves the rest to
  the layer that owns the type. *Implemented on the branch.*
- **Part 3's three names stay, and are used rather than reserved.** Part 3 reserved every `$`-initial member name,
  sound because no declared name could begin with `$`, which a `name_type` admitting `$` breaks: JSON Schema's and
  OpenAPI's own members are `$ref`, `$id` and `$defs`. The three annotation members keep their names — `$schema`,
  `$type`, `$value` sit closest to the vocabularies a JSON user already knows — and are now the only names Part 3
  gives a meaning: any other `$`-initial name is a field name, so a translated record's `$ref` reads as an ordinary
  member. A record that declares one of the three obscures it — a leading one is read as the annotation member, one
  elsewhere is misplaced — and Part 3 §3.2 says so and that a schema SHOULD NOT, rather than refusing the schema.
  Rejected: moving the three to an unused prefix (`!schema`), which would keep a reservation sound by construction
  at the price of spellings no JSON user expects. *Implemented on the branch*, in Part 3 §1.3, §3.2, §6.1.1 and
  §8.3.1 and in the JSON reader.
- **No shipped `json_name`.** A relaxed type is the translation's own and differs between sources, and the
  implementation needed nothing from the series to support one: `!identifier_type { start_add: "@$" }` is a
  complete definition, read, bound and written like any record's names. A member name no identifier profile can
  state — the empty string, a leading or doubled space — is a key, and belongs in a map, as §2.5 says.
- **A schema's field names are judged at load, by the record's type.** The schema grammar admits any single-line
  token as a declared field name too, and the linker holds every record to its `name_type`: the type names an
  identifier family, every name in `fields`, `discriminators` and the groups is a value of it, and no two fields are one
  value. A brace-form `{ "first name": text }` is therefore a resolver error rather than a parse error.
- **Inheritance keeps one name type, as the record constructor's rule.** A record's `name_type` is set where it is
  declared, `field_name` included, and a subtype cannot change it: a record with supertypes takes their one type when
  it states none, one that states a type beside supertypes must state theirs, and supertypes with different ones —
  `json_record & address`, where `address` says nothing and so is `field_name`'s — are a resolver error. The rule
  belongs to the constructor rather than to `&`: the kernel's `name_type?: type_name` carries no `~ field_name`
  default, since a default cannot depend on supertypes, and its doc states the rule, so the explicit `!record {
  supertypes: [json_record] … }` and a template's closed application are held to it exactly as `json_record & { … }`
  is. Taking the non-default type instead would re-judge `address`'s names by a type they were never declared under,
  and under a translation's type that must begin with `@` none of them would be a value of it; the record to compose
  is one declared over the same type, `address => json_record & { city: text }`. A refinement keeps its source's. So
  the brace form can restate an inherited relaxed name (`tightened => node ^ { "$ref": text }`).
- **A field name is matched in its name type's form.** The linker judges two declared names one field under the
  type's equality, so a reader matches a document's name against the declared ones in the type's `normalization`
  (§5.5), as an enum matches its members: under a case-folding name type `NAME` is the field `name`, the two
  spellings in one record are a duplicate field, and a family's selector is found however its name is cased. Both
  encodings do this; under the default, NFC, nothing changes. So a name type's form implies NFC, on a record and an
  enum alike: under `NONE`, two canonically equivalent spellings would be two names that render alike, and no
  reader could match a document's name without choosing a form the type never stated. `NONE` is refused at load,
  and `ASCII_CASEFOLD`, which leaves text beyond ASCII as written, is admitted only for a type whose every character
  is ASCII; `NFKC_CASEFOLD` is the fold for any other.
- **The brace form states a type by composition, and has no syntax of its own.** A relaxed name type is a
  translation's tool, not the go-to form for a TSON record, so the series adds no brace-form spelling for it: a
  translation declares its type once, on a fieldless abstract base, and its records compose it —
  `json_record => !record { name_type: json_name  extension: ABSTRACT  fields: {} }`, then
  `node => json_record & { "@id": text  label: text }`. This needs nothing beyond the inheritance rule above, and
  any identifier family serves. The cost is an IS-A edge to the base, which
  asks nothing of a position typed by a member: `node` reads untagged. Rejected: inferring `text` from a quoted
  name, which cannot state a narrower type; a mark at the type-def head (`names json_name { … }`), which reserves
  a word there as `abstract` does; a type before `;` inside the brace, which gives `;` a second meaning; an
  annotation, which would make metadata change what a record admits.
- **Hygiene follows the type, over a floor no profile lifts.** A record's field names and an enum's members meet
  §8.2's per-name rules under their type's own profile, whose added characters are its own (`json_name`'s `@` and
  `$`, `words`' medial space), and the collision relation over the scope. Since an added character meets no
  restricted-character rule, an `identifier_type` may not add one that renders as nothing or as another
  character: a Default_Ignorable_Code_Point (so every Bidi_Control), a control, or a space other than U+0020.
  Without the floor a soft hyphen in `continue_add` makes `pass\u{AD}word` and `password` two names no rule tells
  apart, and a converter deriving a profile from a hostile source's names would carry it in. U+0020 stays
  admissible, a medial space being how a name type holds words.
- **A document's field names are judged by the record that reads them.** A name the record does not declare is an
  unrecognised field (§7.2), at its own pointer, whether or not it is a value of the record's name type: one that is
  not can be no declared field, since every declared name is one, so it is unknown on the same terms. Under the
  default the message is the plain one any undeclared name draws; under a declared name type it names the type and
  its reason, since those are the rules the author has to look up. Schema-load refusals follow the same split: a
  default enum's member or record's field "is not an identifier", an author's type's "is not a value of its
  name_type 'json_name'". Being no name, it meets no §8.2 rule; a name of the type meets them under the
  type's profile first. Both encodings report the same code — a JSON member name has no grammar, so the one verdict
  JSON can give a name outside the type is the closure error, and TSON gives the same rather than a second code for
  one fact. A record nested in a relaxed one judges its own names, so the relaxation does not leak into a value it
  holds. A schemaless record has no declared fields to be unknown against, so there a field name that is no
  identifier is a resolver error, `ATOM_FORM_INVALID`, at its own pointer.
- **A lookahead does not judge a field name.** A sealed family's discriminator scan crosses a member's names before
  the member is chosen, and only the member knows its type, so a name the scan crosses is judged when the member
  reads it. The spec says nothing about when a name is judged relative to dispatch; this is the reading that
  reports a name once and by the right rule. A family's members compose its base, so they share its type, and the
  question would only bite a choice whose variants differ in theirs.
- **An ASCII base for an ASCII name.** A name compared the way ASCII protocols compare theirs — an HTTP field
  name, a URI scheme — had to list the alphabet in `start_add` and again in `continue_add` over a `NONE` base,
  since `ASCII_CASEFOLD` implies NFC only over ASCII. `identifier_base` gains `ASCII`, XID's ASCII members
  (Start `A-Z a-z`, Continue adding `0-9 _`), so an HTTP-style header name is one line —
  `!identifier_type { start: ASCII  continue: ASCII  medial: "-"  normalization: ASCII_CASEFOLD }` — and the
  kernel's `scheme_name` is `start: ASCII  continue: ASCII  continue_add: "+-."  exclude: "_"`. *Implemented on
  the branch.*
- **A name binds and writes as the record states it.** A host component binds a relaxed name through the name it
  states (`@Field("@id")`), as it binds any other, and a writer quotes a name that is no identifier, so the
  document reads back. A template's held body is written the same way, a declared name that is no identifier
  quoted, so a template declaring or composing relaxed names holds and closes.

- **Two name types are one by the entry they resolve to.** Composition compares name types, and it compares the
  entries at the ends of their reference chains, not their spellings: `jn => json_name` is `json_name`, and the
  kernel's `field_name` is its `identifier`, so a record stating `identifier` composes with one stating nothing.
  Comparing spellings would refuse a composition of one type under two names.
- **A name type a record states resolves in the author's schema first.** As an enum's `name_type` does: a schema
  declaring its own `field_name` has that one when a record writes `name_type: field_name`, and a record writing
  none has the kernel's. The resolved form keeps the difference, writing `name_type` exactly where it was stated.
- **A schema's own references to its fields match exactly.** A group's members, a refinement's or removal's
  restated field and a `discriminators` entry name a declared field by its declared spelling, under a folding name
  type too: the schema is one author's text, so a reference spelled differently is a mistake to report, and only
  a document's names, which another party writes, are matched in the type's form.
- **An undeclared field's value is skipped unjudged.** No type governs it, so the one problem is the field itself,
  reported once at its pointer; the names inside it meet no name rule, in either encoding.

**Breaking changes, against Revision 37:**

- `enum_type.type` is `enum_type.name_type`; a schema writing `!enum_type { type: … }` must rename the field
  (`!enum [...]` is unchanged).
- `text_enum` is removed; `!text_enum [...]` becomes `!text ^ { members: [...] }`, or an enum over an
  `identifier_type` admitting the names (`medial: " "` for words).
- An enum's `name_type` must be an identifier family, so an enum over `text` or another text family is refused.
- A name type's `normalization` must imply NFC: `NONE`, and `ASCII_CASEFOLD` over a profile reaching beyond ASCII,
  are refused, for enums as for records.
- An `identifier_type` may not add a Default_Ignorable_Code_Point, a control, or a space other than U+0020.
- `identifier_base` gains `ASCII`, and the kernel's `scheme_name` is restated over it (the same set).
- A field-name position admits any single-line token. A schemaless `{ "first name": 1 }` moves from parse error to
  resolver error; a schema-directed record's undeclared non-name, TSON or JSON, is a validation error
  (`UNRECOGNIZED_FIELD`), where JSON's undeclared `$foo` was a resolver error; and a document that names a schema,
  read without it, has field names no rule judges.
- Part 3: only `$schema`, `$type` and `$value` carry meaning; any other `$`-initial member is a field name or a
  key.
- Every bundled schema's `!!id` pin changes (§13.2).

**Proposed text for Parts 1 and 2** (the running behaviour; wording for the editor):

- [TSON-DATA] §1.5: the MUST to match every name against §7.7 reads "every type-annotation and annotation name,
  and every field name of a document that names no schema"; a document that names a schema has field names its
  schema judges ([TSON-SCHEMA] §5.2).
- [TSON-DATA] §2.5: "A field name is an identifier at every layer" becomes "A field name is a value of its
  record's name type ([TSON-SCHEMA] §5.2): an identifier where no schema says otherwise, and always in a
  schemaless document". The remedy "a key that is not a name belongs in a map" stays.
- [TSON-DATA] §2.8: a record's first field name is a bare token "that is an unquoted or single-line token"; the
  clause "whose decoded text is an identifier" and "field names are identifiers" move to §2.5's schemaless rule.
- [TSON-DATA] §7.1: the sentence making `{ "_id": 1 }` a parse error reads "a resolver error in a schemaless
  document, and a parse error unquoted"; `_` stays outside identifier Start.
- [TSON-SCHEMA] §5.2: `record` gains `name_type?: type_name`, stated with its rule — absent, the supertypes' one
  name type, `field_name` with none; stated beside supertypes, theirs; supertypes that disagree do not compose —
  and `discriminators?: [text; 1..]`.
- [TSON-SCHEMA] §5.4: an enum's class is read off its members under its `name_type`, an identifier family; the
  `text_enum` sentence goes.
- [TSON-SCHEMA] §5.11, §8.1 and §10's inventory: `field_group`'s and `template`'s field-name slots are `text`,
  `enum_type`'s field is `name_type`, `text_enum` and its member set go, `identifier_base` is `[XID ID ASCII
  NONE]`, and `record` carries `name_type?: type_name`.
- [TSON-SCHEMA] §7.4: `enum_type.type` is `name_type`, an identifier family whose `normalization` implies NFC;
  `text_enum` is removed and §7.4 points a closed set of text at `text_type`'s `members`.
- [TSON-SCHEMA] §8.2's constructor-refinement table drops `text_enum`.
- [TSON-SCHEMA] §13.2: the five artifact rows carry the re-stamped pins.

**Open:** the default for a JSON member name that is not an identifier; whether a template's field names are
judged at its declaration, where today they are judged at each application, under the closed record's name type.

**Status against Revision 37:** open; under consideration, implemented on `record-name-type`.

## 11. The guide and the schema skill should say what `record.name_type` makes possible, and when to use it

**Section:** [TSON-GUIDE] (a new section, beside its schema-authoring and gradual-adoption material); the
`tson-schema` skill published with the series (`skills/tson-schema/SKILL.md`: "Records and fields", "Enums",
"Converting from another schema language", "Pitfalls", and `references/converting.md`).

**Kind:** documentation, non-normative — follows #10.

**The problem.** #10 changes what a name can be at three positions an author meets constantly, and neither
document a schema author reads says so. The `tson-schema` skill still states the old rule ("names are
identifiers (no leading digit, no leading `_`)") and an enum spelling (`profile: TEXT`) the kernel no longer has,
and its conversion section gives no answer for JSON Schema's or JSON-LD's own member names. An author converting
an OpenAPI document reaches for a map, which loses the record, or renames the member, which loses the round
trip; a model following the skill does the same, at scale. #10 is the normative half; this is the half that
tells an author when to reach for it — and, as important, when not to, since a relaxed name type is a
translation's tool rather than the default for a new TSON schema.

**Proposed: one section in each, on the same outline.**

1. **What a name is.** Field names, enum members and identifier-typed map keys are values of a *name type*, an
   identifier family. The default is the series' identifier everywhere, so a schema that says nothing is
   unchanged. Type names, annotation names, template parameters and directives stay identifiers: they are the
   schema language's own names.
2. **What breaking out makes possible**, each with a one-line schema:
   - *Translating JSON vocabularies* — JSON-LD (`@id`, `@type`, `@context`), JSON Schema and OpenAPI (`$ref`,
     `$id`, `$defs`): `json_name => !identifier_type { start_add: "@$" }`, stated once on a fieldless ABSTRACT
     base the translated records compose.
   - *Words as names* — spreadsheet or CSV columns, form labels (`first name`): `!identifier_type { continue_add:
     "-"  medial: " " }`, which refuses a leading, trailing or doubled space.
   - *Names compared without case* — HTTP-style headers, case-insensitive configuration keys: `start: ASCII
     continue: ASCII  medial: "-"  normalization: ASCII_CASEFOLD`, or `NFKC_CASEFOLD` beyond ASCII; a document's
     `Content-Type` is the field `content-type`.
   - *A vocabulary with its own spelling rules* — kebab-case or other house styles as an enum's or a record's
     name type, so a misspelt member is refused at load.
3. **When it is the right tool.** The names are fixed by someone else — a standard, a wire format, an existing
   API — and the record's shape is known, so it should stay a record rather than become a map.
4. **When it is not.**
   - A new TSON schema whose names are yours: use identifiers; nothing gains from relaxing them.
   - An open or unbounded set of keys, or keys that are no names at all (empty, padded, invisible characters):
     a map, as [TSON-DATA] §2.5 always said.
   - A closed set of text values that are not names: `text_type`'s `members` facet, not an enum.
5. **What it costs**, so the choice is made knowingly.
   - Relaxed names are quoted in TSON text, and a host binding names them (`@Field("@id")`).
   - Records joined by inheritance share one name type, the default included, so a reused plain record is
     re-based on the translation's base. The SchemaStore conversion measured it: 369 records declare relaxed
     names, and 2,167 take the type.
   - In JSON, `$schema`, `$type` and `$value` still belong to the encoding; a record should not declare them.
   - A processor reading a document without its schema cannot judge relaxed names.
6. **Safety that stays on.** Every name is still judged by [TSON-DATA] §8.2's rules under its type's profile,
   look-alikes included, and a profile may not add an invisible character, a control or a space other than
   U+0020; the type's normalization must imply NFC.
7. **A worked conversion**: a small JSON-LD or OpenAPI fragment, its TSON schema over `json_record`, one TSON
   document and the same value in JSON, read back through a bound class.

**And in the skill specifically:** replace the identifier-only sentence in "Records and fields" with a pointer to
the new section; rewrite "Enums" for `enum_type { name_type: … }` and drop `profile: TEXT` (a closed text set is
`!text ^ { members: [...] }`); add the JSON-LD/JSON Schema/OpenAPI member-name row to `references/converting.md`
and the conversion section; and add pitfall rows for a map used where a relaxed record fits, and a plain record
composed with a translation's base.

**Status against Revision 37:** open; documentation for #10. The implementation's own consumer skill
(`skills/tson-java/SKILL.md`, "Field names that are not identifiers") carries the reading and binding half and
can serve as a model.

---

## 12. A record's fields are a map keyed by name: `record_field` carries no `name`

**Section:** [TSON-SCHEMA] §5.2 (`record`, the explicit `!record { fields: … }` form, and the rewrite of a bare record
body), §8.1 (`record_field`, `record`, and a record's resolved output), §13.2 (the artifact pins).

**Kind:** proposal — the one declaring slot in the kernel written as a list.

**The problem.** A field's name is its position in the record, the key a document writes it under; it is not a fact
about the field. Every other position in the kernel that declares names says so by its shape: a schema is
`{type_name => type_definition}`, the name the key and the definition the value, and an enum's members are a set.
`record.fields` is the exception, `[record_field]`, each element carrying `name: text` beside its four facts. So the
name is data the field holds rather than the place it sits, and two things follow. The explicit form
`!record { fields: [ { name: a … } { name: a … } ] }` is well-formed data, an array admitting repetition, so the first
thing a field set promises, that each name is declared once, has no shape behind it and is left to a resolver rule.
And a field can be carried detached from the record it is a position of, its name travelling with it as though it
were its own.

**Proposed, and running.** `fields` is an ordered map from a field's name to its facts, and `record_field` drops
`name`:

```
record_field => {
  type:      type_ref
  optional?: boolean ~ false
  voidable?: boolean ~ false
  role?:     field_role ~ FREE
  value?:    value
}
field_map => !map { key_type: text  value_type: record_field  ordered: true }
record    => product & { … fields: field_map … }
```

The explicit form is `point => !record { fields: { x => { type: int32 }  y => { type: int32  optional: true } } }`, and
a record's resolved output is `!record { fields: { x => !record_field { type: int32 } … } }`; a name that is no
identifier is a quoted key, `"@id" => { type: text }`. The brace form an author writes, `{ x: int32  y?: int32 }`, does
not change. A template's held body spells its record the same way: `<T> { x: T }` holds
`!record { fields: { x => { type: T } } }`.

**The decisions it makes, stated so none is read later as an accident:**

- **The map is ordered.** Declaration order is part of a record's value: resolved output states the fields in it, a
  diagnostic naming a record's fields lists them in it, and a composition's fields are its supertypes', in their order,
  then its body's. Two records declaring the same fields in different orders are therefore two values, where an
  unordered map would call them one, and a resolved-output comparison could not tell a resolver that kept the order
  from one that sorted it. The kernel states this with the explicit `!map { … ordered: true }`, since the
  brace-map sugar `{text => record_field}` has no place for the facet, and names it, `field_map`, because a type
  position admits no inline constructor application (§5.3) — as `enum_set` names its set.
- **The key type is `text`, not `field_name`.** Which names a record may declare is its `name_type`'s to say
  (SPEC-FEEDBACK.md #10): `json_name` admits `@id`, which `field_name` does not, and a key type of `field_name` would
  refuse it before the name type was consulted. `record.discriminators` is `[text; 1..]` and `field_group`'s member
  slots are `text` for the same reason; `fields` follows them. Each key is judged by the record's `name_type` at link,
  as every other name the record states is.
- **A duplicate is refused by the layer that can see it.** One spelling written twice in the explicit form is the
  map's duplicate key ([TSON-DATA] §2.6), refused where the map is read and before any name type is consulted: read
  as data governed by meta.tn it is a duplicate key at `/fields/x`, and written in a schema it is that refusal
  restated against the declaration, a resolver error by §8.1's phase rule. Two spellings that are one value of the
  record's name type — `name` and `NAME` under a case fold — are two keys the map admits, and remain the resolver
  error #10 states, judged by the name type's equality, which only a resolved type has. The brace form's own repeat
  stays the resolver's, which can say whether a field or a group member repeated the name. Running here: the first is
  the map reader's refusal, the second the linker's name-type check, and the third the resolver's; a test pins each.
  The split follows from the key type being `text`, and is where the implementation stands rather than where the
  rule ends: once `field_map` is keyed by the record's name type itself, the map's key equality is that type's, and
  the linker's check over two spellings becomes the map's own uniqueness.
- **Records only.** `template.parameters` and `template_param.name`, `tuple.elements` and `field_group` are unchanged,
  and this entry makes no claim about them.

**Breaking changes, against Revision 37:**

- `record_field.name` is removed and `record.fields` is `field_map`: a schema writing the explicit
  `!record { fields: [ { name: a  type: T } ] }` writes `!record { fields: { a => { type: T } } }`, and data typed
  `record_field` states no `name`.
- Every record's resolved output changes shape, and so does every held record body.
- The kernel gains `field_map` and loses the synthetic array of `record_field` that `[record_field]` lifted to.
- Every bundled schema's `!!id` pin changes (§13.2).

**Suggested resolution.**

- [TSON-SCHEMA] §5.2: "A record's fields are a map from each field's name to its `record_field`, in declaration
  order, which is part of the record's value. A bare record body `{ a: T … }` denotes `!record { fields: { a => {
  type: T } … } }`. A field name is a key of `fields`, `text`, and a value of the record's `name_type`: a name written
  twice is a duplicate key ([TSON-DATA] §2.6), and two names that are one value of the name type are a resolver
  error."
- [TSON-SCHEMA] §8.1: `record_field` without `name`; `field_map => !map { key_type: text  value_type: record_field
  ordered: true }` beside it; `record.fields: field_map`; the resolved-output examples written with the map.
- [TSON-SCHEMA] §13.2: the five artifact rows carry the re-stamped pins.

**Status against Revision 37:** open; running on `r2026-38-proposal`. `meta-kernel.tn` declares `field_map` and the
new `record_field`; the resolved fixtures carry both (`ResolvedFixtureTest`), and the bundled schemas are restamped.
The Java value model mirrors it: `RecordField` has no name, and `RecordBody.fields` is a `SequencedMap<String,
RecordField>` whose order `RecordBody.equals` compares. The resolver, the linker, held template bodies and both
encodings' readers key fields by name; the corpus restates every explicit record and every resolved output in the
map form and adds vectors for the explicit form, its duplicate key, and declaration order. Part 2's cached §13.2
table is not re-stamped here.

## 13. A value written by name: a qualified name at a product-typed position

**Section:** [TSON-SCHEMA] §5.6 (the positional form "general over schema-backed data"), §5.10 ("a type slot cannot
be bounded at a *field*"), §4.1 (`data`), §9 (extension meta-schemas); [TSON-DATA] §7.1 (`.` "reserved as a future
identifier separator").

**Kind:** proposal, running on the `qualified-name` branch of `r2026-38-proposal` as stated under "What runs" below.
It is the first of the two
directions the preamble carries open against Revision 38, a namespace as a value, cut to the part that needs no
kernel change. The evidence is a consumer, the HTTP layer: its meta-schema declares an interface as a named set of
methods, each a request record, a response type and the error records, and an HTTP binding that names the method
it serves. Today the method's request is `type_ref`, so a `uuid` passes where a record is wanted, and the binding
carries two bare identifiers, a method name and an interface name, that no resolver checks — the layer checks them
itself at startup, which is the check this entry moves into the schema.

**The problem.** A meta-schema can declare an interface today — `method => { request?: record  response?: type_ref }`
and `interface => data & { methods: {method_name => method} }` resolve, link and compile on `r2026-38-proposal` as
written — and a schema under it cannot write the one thing an interface is for. `request: place_order_request` is
refused: §5.6's positional form, applied at every data position, reads the token as `{ fields: place_order_request }`
and fails on `fields`. `request?: type_ref` accepts the name and checks nothing, so a `uuid` passes where a record is
wanted. And a binding that names a method, `method: orders.methods.place_order`, has no spelling at all, because a
method is a value inside an entry and nothing reaches in. The kernel keeps meeting the same gap under different
names — the dependent slot of §5.10, a constant pool, the HTTP layer's bindings — and it is one gap: **a slot that
holds a value declared elsewhere, written by its name.**

**Proposed.** Two rules, both outside the kernel.

*A bare name at a product-typed position in a schema document is a qualified name.* At a position inside a schema
document — a constructor's binding record, a data entry's body — whose declared type is PRODUCT-kind — a
constructor, or a record a schema declares — an unquoted token that is well-formed as a qualified name is one, and
the position's value is the value it names. The name resolves to a store element whose declared type IS-A the
position's type (§7.2), and is refused where nothing resolves or where what resolves is of another type: found
first, judged after, never a choice among candidates. `request: place_order_request` resolves to an entry whose body
is built by `record`; `request: user_id` resolves to an entry built by `uuid_type` and is refused for that reason,
with no bound written anywhere. The rule is a schema document's alone: a name resolves in a schema's namespace, and
a data document has none, so a data document reads every bare token as it does today. A token at an atom-typed
position stays a literal, as `verb: GET` is, and §5.6's positional form keeps a bare token exactly where it would
fill an **atom**-typed field — which is `type_ref`, whose `name` is `type_name`, so a type reference reads as it
does today — and loses it where it would fill a product-typed one in a schema document, which no canonical output
writes (`!record { fields: { p => x } }` for `p => { type: x }` is the one such spelling, and §8.1 writes the
explicit record). The positional form stays a spelling rule for data values; by name is the spelling, in a schema,
for a value that already has a name.

*A qualified name is segments joined by `.`.* The first segment is a type name and resolves as a `type_ref` does,
locally and through imports, alias hops followed (§8.3). Each further segment selects **in a value**: a field of a
record value, then a key of a map value, so `orders.methods.place_order` is an entry, a field of its body, and a key
of that field's map. Every hop is written; nothing is skipped and nothing is looked up lexically, so a name means the
same wherever it is written in a document. A segment is an identifier, judged by the key type of the collection it
selects in — the schema's type-name family, the record's name type, the map's key type — and a map whose keys are not
spellable as identifiers (a path template, a text) cannot be entered. A type entry admits no further segment:
projecting into a record type's fields (`order.sku`) is a different capability (a field is a declaration, and the
store for it is the record's `fields`) and is left for the entry that brings `discriminators` and `scope` under this
rule. [TSON-DATA] §7.1 reserved `.` for exactly this, and a dotted token already lexes as one unquoted token, so a
qualified name is a parse of a token the lexer accepts today, performed by the position's type.

**The two layers, and an example over the current schemas.** The slot's type is the meta-schema's to declare, and
the name is the governed schema's to resolve: `request?: record` is written in the meta, where `record` resolves to
the kernel's constructor, and `request: place_order_request` is written in a schema under that meta, where the name
resolves as every other name in that document does — locally, then through its imports, never in the meta (§2.2.2).
The judgement meets the two: the referent's constructor, found in the schema's chain, and the slot's type, found in
the meta's, are one entry by canonical identity, or are not. The three documents below are the probe; the meta
compiles today on `r2026-38-proposal`, and the two schemas are what this entry makes valid.

```
!!id:"https://example.com/meta-service.tn"
!!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
!!import:"https://tson.io/2026/38/m/meta.tn"
{
  method_name => identifier
  method      => { request?: record  response?: type_ref  errors?: [record] }
  interface   => data & { methods: {method_name => method} }
  http_verb   => !enum [GET POST]
  binding     => data & { method: method  verb: http_verb  route: text }
}
```

```
!!id:"https://example.com/orders.tn"
!!meta:"https://example.com/meta-service.tn"
!!import:"https://tson.io/2026/38/m/core.tn"
{
  order               => { sku: text  quantity: int32 }
  place_order_request => { order: order }
  orders => !interface { methods: {
    place_order => { request: place_order_request  response: order }
  } }
}
```

```
!!id:"https://example.com/orders-api.tn"
!!meta:"https://example.com/meta-service.tn"
!!import:"https://example.com/orders.tn"
{
  post_orders => !binding { method: orders.methods.place_order  verb: POST  route: "/orders" }
}
```

`request: place_order_request` resolves in `orders.tn` to an entry whose body is `!record { … }`; `record` is the
slot's type; accepted. `method: orders.methods.place_order` resolves `orders` in `orders-api.tn`'s imports to a
DATA entry, `methods` to a field of its body, `place_order` to a key of that map, whose declared type is `method`;
accepted. Each refusal names its own step:

| Written in a governed schema | Result |
|---|---|
| `request: order_ref`, `order_ref => { id: text }` | accepted: the referent's body is built by `record` |
| `request: req`, `req => order_ref` | accepted: the alias hop is followed (§8.3) |
| `request: order_v2`, `order_v2 => order & { note: text }` | accepted: a composition's body is built by `record` |
| `request: user_id`, `user_id => uuid` | refused: the hop reaches a body built by `uuid_type`, no `record` |
| `request: orders.methods.place_order` | refused: a `method`, where a `record` is wanted |
| `method: place_order` | refused: no entry `place_order`; a member is reached through its entry |
| `method: orders.place_order` | refused: `orders`'s body has no field `place_order`; the hop is `methods` |
| `method: orders.methods.cancel_order` | refused: `methods` has no key `cancel_order` |
| `method: place_order_request` | refused: a `record`, where a `method` is wanted |
| `response: orders` | refused: a DATA entry where a type is expected (§4.1, unchanged) |
| `request: { … }` | not decided here: a braced value at a constructor-typed slot is the later entry's (below) |

The last row is deliberate. This entry admits the by-name spelling at a constructor-typed slot and says nothing
about a braced value there: what it would mean, if anything, is exactly the question the two readings below answer
differently — a reference type (`ref<record>`) refuses it, substitution has to say what a `record`-value written in
place is — and settling it is more than this entry's PR. A slot typed by a record the meta declares is unaffected:
`method: { response: order }` is plain data of that record, inline or by name.

**The name, and what the standards do.** The dotted-names family, where each segment is a name and nothing is a
position, is the *qualified name*: Java's (JLS §6.5), XML's QName, Python's dotted name, protobuf's fully-qualified
name, TOML's dotted keys ([TOML v1.0.0](https://toml.io/en/v1.0.0): `site."google.com" = true`, a quoted segment
carrying the dot). Google's field masks ([AIP-161](https://google.aip.dev/161)) are the same family and say the thing
this entry says: a path may not address a repeated element by index — `authors.0` is invalid — because an element
has no name. Indexing belongs to the address family: [RFC 6901](https://www.rfc-editor.org/rfc/rfc6901) JSON Pointer
(`/authors/0/name`, quoted, which this implementation's diagnostics already use) and
[RFC 9535](https://www.rfc-editor.org/rfc/rfc9535) JSONPath (`$.authors[0].name`, a query language with wildcards and
filters, whose Normalized Path is its one-node form). The two families answer different questions — *which named
thing* against *which position* — and TSON has one of each already. So this entry's spelling is the **qualified
name**, names only, and it adds no `[n]`: an element of a list is not a thing a schema declares, `[` is structural in
the lexer (`x.y[0]` is a parse error today, two adjacent values), and a position wanted later is a JSON Pointer in
a quoted string, not a widening of the name grammar. Nor does a segment admit quotes: `api."/path/x".request` is three
adjacent values to the lexer, so admitting it would teach Part 1's data grammar a new value form in every document,
where a qualified name is otherwise a parse of a token the lexer accepts today; and a text key is data, declared
nowhere, so nothing under it was declared by name. TOML's quoted segment is the precedent to reach for if a map keyed
by text ever needs entering; it is not taken here.

**What the rule does not decide, and keeps open.** Resolved output writes the name back as written, as a reference
chain is written (§8.3), so the two readings a later entry may give it stay available: substitution, where a name
is replaced by its value at resolve and nothing downstream can tell the two spellings apart (the right reading for a
shared profile or constant); and identity, where the slot is typed by a reference type — `ref<method>` in the HTTP
layer's proposal — and the value *is* the name, with the referent's schema identity, entry and key as its identity,
which is what a coverage check ("every method of `orders` is bound") reads. Neither is in this entry: it is the
lookup, the judgement and the grammar, which both readings share, and a check that keys on how a value was spelled
is not a rule this entry admits — a fact a check reads has to be in the value.

**One shape for every reference, which is the proof point for `ref<T>`.** A by-name slot binds what a `type_ref`
slot binds: a `type_ref` value whose `name` is the token as written, dotted or not. `type_ref`, `type_name`, a
qualified name and the coming `ref<T>` are one thing — a reference that must resolve — and differ by slot only in
the judgement: at a `type_ref` slot the name is one segment and must resolve to a type, exactly as today (a dotted
name there is refused), and at a record-typed slot it may carry segments and must resolve to a value IS-A the slot's
declared type. `type_ref` is `ref<type>`, one segment because types live only in the schema map; `ref<method>` takes
segments because methods live in collections.

**Interim, and what closes it.** One rule runs here as interim, closed by an entry that lands on this proposal
branch before the branch goes to the reviewer: the host decides the spelling at a declared-record slot. An
implementation binding the slot to a reference holds a name, one binding it to the record holds a braced value, so
two hosts of one meta admit different documents; `ref<T>` closes it by making the spelling the slot's declaration.
What the host declares does not decide what is checked: the read records every reference it produces by name, with
its slot, and the linker judges that record, so a host that omits a name, or lists it among its type references,
changes nothing — with one host shape for every reference, the list a host filed one in would otherwise decide its
judgement. `data` is opaque to the type system, and its body is the host's to interpret, so a qualified name reaches
into a data-kind entry's body — `orders.methods` — only as the interim the namespace kind replaces; recording what
the read produced walks no body and interprets nothing.

**Against the Revision 37 change log.** §5.10 (and the preamble) record "a type slot cannot be bounded at a field"
as a deferral, and the bounded type slot at a field as withdrawn. This entry does not reinstate it: `request?:
record` is not a type slot and carries no bound. It is an ordinary field whose declared type is a constructor and
whose value is written by name, and "the referent must be a record" is §7.2's subsumption at that field, the same
judgement any field makes of its value. It delivers what the withdrawn feature was for by a route that adds no
syntax and no kernel entry. §4.1's refusal of a DATA entry where a type is expected is unchanged — `response: orders`
is still refused — because a product-typed position is not a type position.

**What runs.** The three documents above resolve and link, with a host class bound for `interface` and `binding`
(a DATA instance reads only through one, `design/meta-layer-data-kind.md`), and every row of the table gives the
result it states. The corpus carries them, with the cases around them, as `class2/link/`, `schema/` and `validate/`
vectors over a `meta-service.tn` fixture: names as list elements and map values, alias chains, a value of a strict
subtype of the slot's type, an enum-keyed map entered by a label, literals at atom-typed slots, the resolved output,
and refusals of a quoted name, malformed segments, a dotted name at a `type_ref` slot, a text-keyed map, a framed
name, the meta's own constructor named from a governed schema, an open entry, a segment through a list, and the
withdrawn positional spelling `!record { fields: { p => x } }`; a `validate/` vector pins that a data document's
positional fills, the product-typed one included, read as before. The pieces:

- *The value.* A by-name slot is bound to the host's type reference (`TypeRef` in this implementation), the token as
written in its `name`, the same shape a `type_ref` slot binds; nothing in the host model is specific to a qualified
name.
- *The read.* In a schema document — the resolver's replay of a binding record or a data body, which the read
context marks — §5.6's positional form keeps a bare token where the record's one positional field is atom-typed and
loses it where that field is a product or a sum; there an unquoted token is a name. A component bound to a type
reference reads it as written; the record's own reader refuses it as no record (`TYPE_MISMATCH`). A quoted token is
text, never a name. Data documents read as before, in tree and bind mode alike.
- *The resolver* keeps the name as written, collects each by-name reference its read produced with its slot, and
hands both to the linker; resolved output writes an argument-free type reference as its bare name, as §8.1 writes
it, so a name reads back as written.
- *The linker* parses each collected name into segments, each a [TSON-DATA] §7.7 identifier, resolves and judges it
as the table says; each refusal is its own resolver error naming the step. A by-name reference the host also lists
among its type references is judged by its slot and not resolved as a type; under `ref<T>` the two lists become one
judgement by the slot's declared type.

**Decided while building it**, each a place the proposal's prose admits more than one reading:

1. *A positional fill typed by a sum* loses the bare token as a product-typed one does, following the suggested
   §5.6 text ("unless the positional form would fill an atom-typed field"). The other reading, "only a product-typed
   fill loses it", would keep a choice-typed fill positional; no kernel record has one, so nothing distinguishes them
   today, and the suggested text is the simpler two-valued rule.
2. *"A PRODUCT-kind position — a constructor, or a record a schema declares"* is read as a **record**-typed position.
   An array-, map- or tuple-typed slot holds no value by name. The broader reading (every PRODUCT kind) would let a
   name stand for a list, which the segment rules never reach (no `[n]`), so a slot of that kind could only name a
   map or list entry by its key — a use the proposal does not make.
3. *The rule does not reach a data document.* §5.12's store is a schema's namespace, and a data document has none,
   so nothing there could resolve a name: a data document's bare token at a record position fills the positional
   field whatever its type, `p: x` at a `record_field` position included, exactly as before. The other reading —
   the rule over all schema-backed data, as §5.6's positional form is — would refuse in a data document a spelling
   that reads today, for a name nothing there can look up. A host component bound to a type reference at a
   record-typed slot still receives a name, unresolved, as it receives a type reference at a `type_ref` slot.
4. *An entry's declared type* is its `source` where that names a constructor of the declaring schema's meta layer,
   and otherwise the kernel constructor its body mirrors — so an enum built by a meta layer's `text_enum` is a
   `text_enum`, not only an `enum`. "Its body's constructor" alone would lose that.
5. *IS-A* is identity, or membership in the declared type's resolved `supertypes`, compared by canonical identity
   (declaring schema and entry), each type named in the namespace of the meta governing the schema that declares it.
6. *"A segment is an identifier, judged by the key type of the collection it selects in"*: a segment is judged by the
   §7.7 grammar wherever it stands, when the name is resolved, and a map is entered only where its key type is an
   identifier family or an enum; a key outside §7.7 under a wider key profile cannot be written as a segment.
   Judging each segment by its own key type would make the grammar depend on what the walk has reached so far.
7. *A framed name* (`@doc:"x" orders`, `!record x`) is refused: a name's type is its referent's, and an annotation on
   it would have nowhere to go.
8. *An imported entry's names* are judged once, when its own schema links, in its own namespace.
9. *An open entry is no referent*: `request: box` with `box => <T> { v: T }` is refused, though the template's
   `source` names `record`; a template denotes no value until it is applied. *A list has no named members*: a
   segment after a list-typed field is refused rather than read as an index.
10. *An empty segment cannot be written*: `orders..place_order` is three tokens under [TSON-DATA] §7.2's range rule,
    so it fails to parse before the name grammar is asked. A trailing or leading `.` lexes as one token and is
    refused by the segment grammar when the name is resolved.
11. *Part 3* needs no sentence yet: there are no JSON schema documents (§1), and the JSON stack reads no `data`
    body, so a qualified name has no JSON position. The suggestion's "a JSON string at the position" stands for when
    one exists.

**Breaking changes, against Revision 37:** in a schema document, the positional bare token at a product-typed fill is
withdrawn (no bundled schema or corpus vector writes one); data documents are unchanged; no kernel entry, pin or
resolved fixture changes.

**Suggested resolution.**

- [TSON-SCHEMA] §5.6, after "The positional form is general over schema-backed data": "A bare token at a position
  whose declared type is PRODUCT-kind, in a schema document, is a qualified name (§5.12) unless the positional form
  would fill an atom-typed field, in which case it fills it; there the explicit record is the only spelling of a
  product-typed fill. A data document has no namespace to resolve a name in, and reads every bare token positionally."
- [TSON-SCHEMA] new §5.12 "Values by name": the two rules above — the position, the store, the judgement, the segment
  grammar, resolution "as written" in output — with `orders.methods.place_order` as the example and the refusal table.
- [TSON-SCHEMA] §5.10: the sentence on a bound at a field notes that a product-typed field written by name needs none.
- [TSON-SCHEMA] §9: a slot holding a value by name is typed by the value's type; `type_ref` remains the type slot.
- [TSON-DATA] §7.1: `.` is the identifier separator of [TSON-SCHEMA] §5.12, no longer "future".

**Status against Revision 37:** open; running on the `qualified-name` branch of `r2026-38-proposal`, as stated above.

---

## 14. `type_definition` goes: a schema map's entry is its value, and a type carries its own chain

**Section:** [TSON-SCHEMA] §8.1 ("Output Records: `type_definition`, `type_ref`, `type_argument`"), §8 (resolver
output as a data document of `schema`), §4.1 (`type`, #9), §5.5 (an instance's `source` "is always the base
constructor"), §5.7 and §5.8 (`source` "records the refinement origin"), §5.9 (lineage in `record.supertypes`),
§7.2 (subsumption reads "the contract index only"), §10.1 (ingest of resolved values); `meta-kernel.tn`'s
`type_definition`, `record.supertypes` and `schema` entries.

**Kind:** proposal — a kernel simplification with no change to any verdict. Running (Status, below); sequenced before
the namespace kind and `ref<T>` (#13, "Interim, and what closes each"), which it simplifies, because it is a mechanical
change to the output shape and the host model and those two entries are written against that shape.

**The problem.** Resolver output wraps every entry of a schema map in a record that source never writes:
`person => !type_definition { source: record  supertypes: […]  subtypes: […]  body: !record { … } }`. The wrapper
exists to hang four facts on, and with `type` in the kernel (#9) each has a home of its own, three of them already
stated elsewhere in the same entry:

- `body: top` is the entry's value. `schema => {type_name => top}`.
- `supertypes` and `subtypes` are a fact about a type, so fields of `type`. Every type kind composes `type`, so
  every `!record`, `!integer_type`, `!array` and `!choice` body has the slots; `data`, `reference` and `template`
  compose `top` and have none — a value, a hop and an open body carry no IS-A, which is the rule now.
- `source`, for a construction, is the body's tag: `source: record  body: !record { … }` states one fact twice.
- `source`, for a refinement: §5.5 already makes an instance's `source` the base constructor, so the tag again; the
  refined-from entry is the chain's nearest member.
- `source`, for a template application, is the entry's identity under another name: §8.2 keys the instantiation
  entry on it, so that `int_box => box<int32>` and a later `box<int32>` are one entry, across an import as within
  one schema, and §5.8's edge from `ok<text>` to `result<text>` is found through it. It is the one fact the wrapper
  holds that nothing else states, and it moves rather than goes.
- `source`, for an open entry, is the constructor the held text applies. Under the one-spelling rule (§8.1) it is
  the text's first token, and §1.3's consumer never reads the held body.

`record.supertypes` is the same shape of duplication inside the body: §5.9 keeps it as "lineage" so that a
subtracted record names the parent it is no longer IS-A, and §7.2 says in the same breath that no check reads it.
Direct parents are the chain's minimal members, operand order is in the flattened `fields`, and `&` against `^` has
no consequence once the body is flat. Its one live use is not in output at all: inside a held template body a
parent may be an application, `<T> result<T> & { … }`, substituted at materialisation.

**Proposed.** `type_definition` and `record.supertypes` are removed; a type and a template carry their own chain in
one record; a schema map is a map from name to value.

```
type_chain => {
  parents?:     [type_ref]       -- the parents as written; held text only, one may be an application
  supertypes?:  [type_name]      -- closed: every entry this one IS-A, complete
  subtypes?:    [type_name]      -- the inverse, over this map only (§3.3.4)
}
chained     => { chain?: type_chain }
application => @annotation type_ref   -- on an entry's key: the application it is the instantiation of (§8.2)
type     => top & chained & {}
template => top & { parameters  template }   -- #17: a record template is a `record` with `parameters`
record   => product & { access_pattern? size_type? fields groups? extension? discriminators? profile? parameters? }
schema   => {type_name => top}
```

- **An entry resolves to its value.** `person => !record { fields: { … } }`,
  `integer => !integer_type {}`, `age => !integer_type { min: 0  chain: { supertypes: [integer] } }`,
  `type_name => !reference { target: identifier }`, `vec => <T> !template { … }`, and a DATA entry
  `my_op => !operation { … }`. Resolved output and source are one grammar: output is source with sugar expanded,
  bodies flattened and chains computed, and an entry written as a constructor application resolves to itself.
- **An entry's kind is its value's type's kind** (§5.5's transfer, unchanged): a body whose constructor IS-A
  `type` is a type entry; one whose constructor composes `data` is DATA; `!reference` is REFERENCE; `!template` is
  open. `type_ref` is a name in `schema` whose value is a type, and the DATA-at-a-type-position rule (§4.1) is
  "the value is not a type", with no wrapper to consult.
- **One record, three states named.** `parents` is what was written, and is the only list that ever holds an
  application; it is a record template's slot, kept after closing, since its parents are closed entries and a FINAL
  parent is refused there as anywhere (§5.2). `supertypes` is the closed chain, complete
  to a fixed point through applied parents (§5.8), every element a bare name, lexically sorted by the fixtures'
  convention; a fresh record's is empty, as §4.1 says. `subtypes` is unchanged: the entries of this map only.
  A record template is the ABSTRACT base its members are IS-A (#17), and it stands in its own parents' `subtypes` as
  any record does.
- **An entry's application is an annotation on its key.** `application` is the entry's identity as an
  instantiation (§8.2), and an entry of any kind can be one: a record template's member, a DATA entry
  closed from a templated instance, the alias a template's application closes to. It is a fact about
  the entry, not about a type, so it lives beside the entry as `@synthetic` does (§8.2): the resolver writes
  `@application:{ name: box  arguments: [{ name: int32 }] }` on the key wherever an entry closes an application,
  and reads it as stated on ingest, so an imported schema's instantiations unify with a later schema's. It is
  derived, as `@synthetic` is, and never written by a declaration: an author's `@application` is a resolver
  error, as `chain` is. Unlike `@synthetic` it is not recomputed on ingest, being the one fact the entry's value
  does not state.
- **A declaration never writes `chain`, and a constructor never declares it.** `!record { chain: { … } }` in
  source is a resolver error, as writing `choice.disjoint` is, and a meta-schema's `widget => atom & { chain?: … }`
  is refused where it is declared, the name being `chained`'s. On ingest the chain is read as stated, never
  recomputed, because a flat body and its chain cannot be recomputed from each other. That is §10.1's reading of
  resolved output already, and the record fixtures' `fields` stay flattened for the reason §1.3 gives: the field
  set is the type, and a consumer that reads values against it must not repeat the merge.
- **Provenance is not recorded.** Subtraction's lineage and a refinement's operator are what was written, nothing
  reads them after resolution, and the source document has them. §5.9's lineage sentences and its rule 10 (an open
  application dropped from lineage) go with `record.supertypes`; a subtracted record is a record with an empty
  `supertypes`. The application is not provenance, above.
- **Kind is judged by origin, not spelling.** A body's kind is read off the chain by which entry of the kernel it
  reaches, so a schema's own `data => { … }` composed into `payload => data & { … }` is PRODUCT: a value conforms
  to where it is defined.
- **The profile rule (#10) reads unchanged.** "Absent, it is its supertypes' one name type": every member of the
  chain agrees, each having inherited its own.

**What it costs.** The output shape of every entry, so every `*-resolved.tn` fixture, the corpus vectors that carry
resolved output, the bundled digests, and §8.1 rewritten around `type` rather than a wrapper. In the host model,
`schema.meta.TypeDefinition` goes, `TsonSchema` maps a name to a `Top`, and every type body record gains the two
chain components, because a body's components mirror its constructor's flattened shape. Mechanical throughout:
no data-document verdict changes, no diagnostic changes, and the read-side shape (`TsonCompiledSchema`) is a
different shape today and stays one.

**Breaking changes, against Revision 37:** the resolved-output format — every entry, every fixture. No source
schema changes; no verdict on a data document changes.

**Suggested resolution.**

- [TSON-SCHEMA] §4.1: `type_chain` and `chained` as above, `type` composing it, with the sentence
  "a type carries its IS-A chain; a declaration never writes it, and a constructor never declares its name";
  `application` declared with `synthetic` among the kernel's annotations, derived and never author-written.
- [TSON-SCHEMA] §8.1: retitled to `type_ref` and `type_argument`; an entry resolves to its value; the kind of an
  entry is its value's type's kind; `source` and `type_definition` deleted, the `source` sentences of §5.5, §5.7,
  §5.8 and §5.10 ("an open entry's `source` is the constructor the held body applies") rewritten to the tag, and
  §8.2's instantiation entry keyed on its `@application`.
- [TSON-SCHEMA] §5.9: the lineage sentences and rule 10 deleted; "`record.supertypes` (authorial lineage)" in its
  resolution paragraph becomes "the chain is empty".
- [TSON-SCHEMA] §7.2: "never the body's lineage `record.supertypes`" deleted, there being none.
- [TSON-SCHEMA] §13.2 and `meta-kernel.tn`: `type_definition` removed, `record.supertypes` removed, `type` as
  above, `schema => {type_name => top}`.

**Status against Revision 37:** open; running on the `entry-is-value` branch of `r2026-38-proposal`. `meta-kernel.tn`
declares `type_chain`, `chained`, `type` composing it, `record` without `supertypes`, and `schema =>
{type_name => top}`, with no `type_definition`; the resolved fixtures carry the nested chain (`ResolvedFixtureTest`), and
the bundled schemas are restamped. The Java model mirrors it: `schema.meta.TypeDefinition` is gone, `TsonSchema` maps a
name to a `Top`, and every type body record carries one `TypeChain chain` component, behind a
`Chained` interface whose accessors every IS-A question goes through. `supertypes` is sorted and closed to a fixed
point over every local chain, so an entry IS-A everything its supertypes are, an atom refinement of a refinement
included (`small => !int32 ^ { … }` carries `[int32 integer]`). A closed type keeps no `parents`; a record template
keeps its written ones, is IS-A the closed entries they reach and stands in their `subtypes`, so a template composing
or refining a final record is refused at its own declaration. `application` is a key annotation, written
wherever an entry closes one — a member of a record template, a DATA entry, the alias a template's application closes
to — and read through `TsonSchema.applicationOf`. Kind and constructor-hood are judged by where
`top` and the base kinds are defined. Writing `chain` is a resolver error on an explicit construction, a held
construction and an atom refinement alike, and so is a constructor declaring a field named `chain`. Beyond the
proposal as written: what a closed refinement's one written parent was is kept as an in-memory fact for one reader, a
tightened constructor binding as the one it tightens (`set_type => array ^ { … }`), since a closed type keeps no
`parents` and the chain cannot tell `^` from a composition adding nothing; and a later schema's application is not yet
unified with an imported library's declared one, ingest of resolved output being unimplemented. Two observable
differences from "no diagnostic changes" above: a template application with an unresolvable argument reports the field
holding it (`'box<some_typo>' field 'v' has an unresolved reference 'some_typo'`), and a resolved atom refinement's
chain is complete. The corpus restates every resolved output in the nested form and adds vectors for the refusals, an
emptied chain under subtraction, a complete chain, a chain completed through an applied parent, a template refused
against a final parent, and a schema's own `data`. Part 2's cached §13.2 table is not re-stamped here. #17 resolves
a record template as a `record` with `parameters`, which leaves the chain to `type` alone; that is the running form.
---

## 15. `top`, `type` and the base kinds are `abstract`

**Section:** [TSON-SCHEMA] §3.3.1 ("a base kind's own reader refuses a direct application by naming what would
satisfy it"), §4.1 (the base kinds), §5.2 and §12.1 (ABSTRACT: "no direct instances", "a tag naming the base itself
is refused", "a position typed by it admits exactly its subtypes"), §8 (resolved output as a value of `schema`).

**Kind:** proposal — one special sentence replaced by a mark the kernel already has. Sequenced with #14, which it
shares fixtures with.

**The problem.** §3.3.1 carries a rule of its own for six entries: `!top {}`, `!type {}`, `!atom {}`, `!product {}`,
`!sum {}` and `!data {}` are refused by "the base kind's own reader". That is ABSTRACT's definition in §12.1 stated
without the word. And under #14 a schema map is `{type_name => top}`, so every entry's value is a tagged value at a
`top`-typed position; the rule that reads it — dispatch by tag among the subtypes of a base that has no instances
of its own, the family open across schemas (§3.3.4) — is the rule every abstract record without discriminators
already has.

**Proposed.** The six are marked, each for itself since extensibility is never inherited (§5.2):

```
top     => abstract {}
type    => abstract top & chained & {}
atom    => abstract type & {}
product => abstract type & { access_pattern: product_access_type  size_type: product_size_type }
sum     => abstract type & {}
data    => abstract top & {}
```

- A direct application of any of the six is refused by the abstract rule, with the diagnostic naming the subtypes
  that would satisfy it, as the special sentence does today.
- Constructors stay OPEN: `!record { … }` and `!integer_type {}` are direct instances. `reference` and `template`
  have direct instances and stay OPEN. The supporting records (`record_field`, `type_ref`, `atom_specification`) are
  values everywhere and are not applicable for a different reason, not being IS-A `top`.
- `schema => {type_name => top}` reads each entry by its tag among `top`'s subtypes, `!operation { … }` from a
  meta-schema admitted through `data`'s chain. No verdict changes: nothing was ever read against a base kind.

**Breaking changes, against Revision 37:** none in behaviour; `extension: ABSTRACT` appears on the six entries in
resolved output.

**Suggested resolution.** [TSON-SCHEMA] §3.3.1: the "base kind's own reader" sentence deleted. §4.1: the six
declarations as above, with "the base kinds have no instances of their own: each is `abstract`, and a direct
application is refused as any abstract record's is (§12.1)". §12.1's abstract rows gain the six.

**Status against Revision 37:** open; running on the `entry-is-value` branch of `r2026-38-proposal`, with #14.
`meta-kernel.tn` marks the six `abstract` and the resolved fixture states `extension: ABSTRACT` on each. A construction
naming one passes the applicability gate — `top` answering for itself — and is refused as abstract in one sentence,
naming only the instantiable subtypes that would satisfy the position (`'!atom' names an abstract entry, which has no
direct instances (§5.2), so there is nothing for '!atom { ... }' to build -- construct one of its instantiable subtypes
instead: (value_type | void_type | integer_type | …)`); a `data` that nothing in scope composes says so. One verdict
moves: an annotation typed by a base kind (`@data:_`) was a bind mismatch,
the type having no class to build, and is now a schema error, a value at an abstract position naming no subtype. The
corpus carries a refusal vector for each of the six and a valid vector for `!record { … }` and `!integer_type { … }`.

## 16. A record template's parameters stand in three places, and a parameter is never an operand

**Section:** [TSON-SCHEMA] §5.10 ("Held bodies": "a parameter in a value slot (`min_items: N`), in a type slot
(`element_type: T`), inside a collection (`variants: [T error]`, `members: [a b M]`), or at any depth is a token like
any other"; "A record-bodied template is a family base"), §5.8 ("Parameterized references").

**Kind:** proposal — two refusals. The first step of separating a record template (a family, whose open form is a
type) from every other template (an abbreviation, whose open form denotes nothing); the kernel split follows as its
own entry.

**The problem.** §5.10 admits a parameter wherever a token stands, and that is right for an abbreviation:
`<N> !array { element_type: text  min_items: N }` needs it. A record template is a family base as well, and a base is
"what survives erasure": the fields a position typed by the template can rely on whichever member it holds. A
parameter in some slots of a `!record` body leaves no such shape. Each of these resolves today, in this
implementation and by §5.10's text:

| Slot | Declaration | `T<x>` gives |
|---|---|---|
| field name | `keyed => <K> { K: text }` | a field named by the argument |
| group member | `grouped => <A> { ( A: text \| b: text ) }` | a group over the argument |
| `record_field.optional` | `<B> !record { fields: { v => { type: text  optional: B } } }` | `v` optional or not |
| `record_field.voidable` | `<V> !record { fields: { v => { type: text  voidable: V } } }` | `v` voidable or not |
| `record_field.role` | `<R> !record { fields: { v => { type: text  role: R  value: "x" } } }` | `v` FREE, DEFAULT or FIXED |
| `record.extension` | `<E> !record { fields: { … }  extension: E }` | each member's extensibility |
| `record.name_type` | `<I> !record { fields: { … }  name_type: I }` | what the field names are |
| `record.discriminators` | `<D> !record { fields: { kind => … }  discriminators: [D] … }` | what the members are selected by |

The base of `keyed` cannot list the field; the base of `maybe` cannot say whether `v` may be missing; the base of
`fam` cannot dispatch. §5.10 never says which of a base's facts an argument may decide.

Separately, §5.8 lets an operand of `&` or `^` carry arguments, and says nothing of an operand that *is* a parameter:
`named => <T> T & { name: text }` (a mixin) and `<T> T ^ { … }`. Both read the operand's field set to flatten, and a
parameter has none until the template is applied, so neither body can be held in the flattened form §5.10 requires.

**Proposed.**

- In a record template a parameter stands only in a field's type (`v: T`), a field's default or fixed value
  (`w?: int32 ~ N`, `type: text = N`), or an argument of an application (`items: box<T>`, `result<T> & { … }`); sugar
  inside a field (`[T; N]`) lifts to an application and so is the third. Anywhere else in a `!record` body is a
  resolver error at the declaration, naming the slot.
- A parameter is never an operand of `&` or `^`; an operand applied to one (`result<T>`) is admitted. Read under the
  shadowing rule, so `<T> T & { … }` is refused even where the schema declares an entry named `T`.
- Templates over any other constructor are unchanged: `<V> !tuple { elements: [{ element_type: text  voidable: V }] }`
  still takes a value parameter in a scalar slot.

**Breaking changes, against Revision 37:** the eight slot forms above, all of which resolve today. Nothing in the
bundled schemas or the conformance corpus uses them. The two operand forms were already refused by this
implementation, as an unresolved operand name.

**Suggested resolution.** [TSON-SCHEMA] §5.10, "Held bodies": after "at any depth is a token like any other", add "— in
a template whose held body is a `!record`, only at a field's type, a field's default or fixed value, or an argument of
an application; anywhere else in a record body is a resolver error, since the family base (below) would have no shape
its members share". §5.8, "Parameterized references": "an operand may carry arguments, which may name the
declaration's parameters; an operand that is itself a parameter is a resolver error".

**Status against Revision 37:** open; running on `r2026-38-proposal`.
`ParameterTypes` refuses a parameter at any record slot but a field's type, reporting the slot (`parameter 'K' stands at
a key of record.fields -- a record template's parameters stand only in a field's type, a field's default or fixed value,
or an argument of an application, so that every application of it shares one shape (§5.10)`); `DefinitionResolver`
refuses a parameter operand before looking the name up (`supertype 'T' is a parameter of this declaration, and a
parameter is never an operand of '&' -- its fields are unknown until the template is applied, so there is nothing to
flatten (§5.10)`). The corpus carries a refusal vector for each.

## 17. A record template is a `record` with `parameters`; `template` holds every other open body

**Section:** [TSON-SCHEMA] §5.10 ("Held bodies": "a record template `<T> { x: T }` is normalised to `<T> !record {
… }` … and held"; "A record-bodied template is a family base, and may be named at a type position"; "The **two
levels** of `extension`"), §4.1 (`template` composing `top`), §8.1; `meta-kernel.tn`'s `template`, `record`,
`chained` and `type_chain`.

**Kind:** proposal — a kernel restructuring with no change to any verdict. Builds on #16.

**The problem.** §5.10 already separates two kinds of template by their body: a record-bodied template is a family
base, a type a position may name, ABSTRACT, IS-A its parents, with its members IS-A it; every other template denotes
nothing until applied. The kernel carries both in one constructor and encodes the difference as an optional field:
`template.extension` is present exactly when the body is a record, and when present it is always ABSTRACT ("absent
means this template is no type"). `template` composes `chained` only because a record template has a chain, and
carries `discriminators` only because a record template has selectors. Every one of those facts is a record's, and
the reason for holding a record body as text — that a parameter may stand in any slot, `min_items: N` included — no
longer applies to one: after #16 a record template's parameters stand only in a field's `type`, a field's `value` and
an application's arguments, all of which `record_field` and `type_ref` already hold as names or uninterpreted tokens.
So an open record is a value of the vocabulary as it stands.

The two kinds are the split C++ (alias template / class template), Haskell (`type` / `data`), ML (abbreviation /
`datatype`) and Scala (`type F[A]` / `class F[A]`) all make. Only a record can be a family base: a base is what
survives abstracting over the parameters, and residual shape, abstractness and dispatch are all record properties. A
parameterised atom refinement (`<N> !integer ^ { max: N }`) has IS-A edges, but from its `^`, and its open form is
just its source; a container has no IS-A at all.

**Proposed.**

```
record => product & {
  …
  parameters?: [template_param]
}

template => top & {
  parameters: [template_param]
  template:   text
}
```

- A record with `parameters` is a record template and a family base: ABSTRACT by construction, IS-A its parents as
  written (one may be an application naming a parameter), its declared applications IS-A it. Its own `extension`
  is each application's — the second of §5.10's "two levels" — so `result => abstract <T> { … }` makes `result<text>`
  abstract over its own members; the base's ABSTRACT is not stated.
- `record.discriminators` on a record template is the base's selector list, the `=?` fields and the fields a fresh
  record pins to a value parameter; closing drops a selector its pin fixes, as §5.10's "the selectors do not travel to
  a member (closing has just pinned them)" already says.
- `template` holds every other open body as text, composes `top` alone, and carries no chain, `extension` or
  `discriminators`: it is not a type, and an application of it is its expansion, with whatever chain the expansion's
  own `^` or `&` gives it.
- A record template named without arguments at an operand of `&` or `^` is a resolver error: a position may name the
  base, but an operand is flattened, and a template has fields only once its parameters are bound.

**Breaking changes, against Revision 37:** resolved output only. A record template is written
`!record { fields: { … }  parameters: [ … ] }` with its parameters in place, where it was
`!template { parameters: [ … ]  template: "!record { … }"  extension: ABSTRACT }`. No verdict changes, and no
bundled schema declares a record template.

**Suggested resolution.** [TSON-SCHEMA] §5.10, "Held bodies": a record template is not held; it is the `record` it
denotes, with `parameters`, and only a template over another constructor is held as text. "A record-bodied template
is a family base": the base is the record template entry, ABSTRACT by construction, and "The two levels of
`extension`" becomes "the base's is ABSTRACT by construction; the record's own `extension` is each application's".
§4.1 and `meta-kernel.tn`: as above.

**Status against Revision 37:** open; running on the `record-families` branch of `r2026-38-proposal`. `RecordBody`
carries `parameters`, and `schema.meta.OpenEntry` is where every phase asks whether an entry is open and whether it is
a family base. Closing builds the open record's tree (`WireForm.heldRecord`) and runs the one substitution every
other template closes by. `meta-kernel.tn` and its resolved fixture state the shapes above; the corpus vector
`class2/schema/valid/a-bounded-template-parameter-is-recorded-with-its-bound` states the new resolved form.

## 18. An application nothing declares is its expansion

**Section:** [TSON-SCHEMA] §8.2 ("Every application materialises"; "*Two identities*"; "Instantiation entries";
"*Entry shape*" and its `vector_pixel_af3` example), §5.10 ("every other constructor closes to a synthetic"), §8.1
("a fully-bound application of a non-constructor template resolves to a reference to its materialised instantiation
entry"), §8.3 ("Instantiation entries are not references"). Builds on #17.

**Kind:** proposal — resolved output and identity; no verdict changes.

**The problem.** §8.2 gives a minted application two identities at once. Its content is a closed synthetic, keyed on
the form, and the application is an instantiation entry, keyed on the canonical application. For a record template
that makes `box<int32>` and `crate<int32>`, over two templates with one field list, two entries for one record. For a
template over any other constructor it is two entries for one type: `s: set<text>` mints `set_type_text_…` (`@synthetic`)
and `set_text_…` (`@application`), and the second can only be a `!reference` to the first — which §8.3 says an
instantiation entry never is. The second entry records nothing a value can tell apart: it admits exactly the
synthetic's values. #17 makes the split this rests on explicit: a template that is not a record template is an
abbreviation, and an abbreviation's application is its expansion and nothing more.

**Proposed.** An application nothing declares is its expansion. A use site naming `set<text>` names the synthetic
`!set_type { element_type: text }` lands on, the entry `[text]`-style sugar or another template expanding to the same
form also lands on; a use site naming `box<int32>` names a closed synthetic record, keyed on its content, which
`crate<int32>` lands on too. No instantiation entry is minted, and nothing records the application. A declaration naming an
application is unchanged: it is that entry, under the author's name (§8.2, "A declaration whose body denotes a
type is that type's entry").

- **A self-reaching expansion is named by its application.** `nested => <T> ( T | [nested<T>] )` closed at
  `nested<text>` names itself inside its own body, so its content cannot be written without a name: the knot is tied
  on the application, which keeps its instantiation entry, a `!reference` to the synthetic, keyed on the canonical
  application as §8.2 states today. The rule is total: whether closing reached the application again decides it.
- **A minted record is a member of nothing.** One whose chain names a declared type — `k: dog_of<text>` over
  `dog_of => <T> pet & { … }`, or a parent application a declaration owns — would be a family member with no name a
  read, a tag or a binding could use, and is a resolver error at the declaration that wrote the application (§3.3.4's
  "a family member is declared", applied at the moment the application is in hand). It gains no IS-A edge later. A
  declaration's operand still reaches it: `vip => customer & box<int32>`, beside a use site's `box<int32>`, is IS-A
  that synthetic, and so stands where `crate<int32>` is expected, the two applications being one type.
- **A diagnostic names what the author wrote, and nothing for a form with no spelling.** A minted atom instance
  (`b<10>` over `b => <N> !integer_type { min: N  max: 100 }`) has no sugar spelling and no author-written name, so
  a message about it is the family's own (`'5' is less than the minimum 10`), located by its position, never the
  entry's content-derived key. A minted record is named as the body a record is written with
  (`unknown field 'nope' on '{ v: int32 }'`).

**Breaking changes, against Revision 37:** resolved output only. A minted instantiation entry and its
`@application` go; the use site names the synthetic, `@synthetic` at its key. The use-site family member refused at
linking is refused at resolution instead. One verdict changes: a minted record composing an application only a use
site mints (`u: vip<text>` over `vip => <T> box<T> & { … }`, beside `w: box<text>`) was a member of that minted entry
and refused; it is a member of nothing now, and loads.

**Suggested resolution.** §8.2, "Every application materialises": a fully-bound application of a template
materialises its expansion, a synthetic entry keyed on its content, and mints no instantiation entry unless the
expansion names the application itself. "*Two identities*", "Instantiation entries": declared, or minted for a self-reaching
expansion. The `vector_pixel_af3` example: `vector<pixel, 1920>` written at a use site lands on the closed synthetic
`[pixel; 1920]` lands on. §8.1's sentence on use-site applications and §5.10's "every other constructor closes to a
synthetic" say the same.

**Status against Revision 37:** open; running on `r2026-38-proposal`, the record half on its `rule4-records` branch
(`TemplateMaterialiser.instantiate`, `mintedRecord` and `refuseMintedMember`, its `expansions` and `tied` sets;
`EntryDisplayName.authored`, reading `@synthetic` through `TsonSchema.EntryFacts.synthetic`).
