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

## 10. A record's field names have a type: `record.name_type` (experimental)

**Section:** [TSON-DATA] §2.5 ("A field name is an identifier at every layer"), §7.2.1, §7.7, §8.2; [TSON-SCHEMA]
§5.2 (`record`), §7.4 (`enum_type.type`), §7.7, §12.1; [TSON-JSON] §3 (the annotation members).

**Kind:** proposal, experimental — on the branch `experiment/field-name-type`, not `r2026-38-proposal`.

**The problem.** A field name is an identifier at every layer, so `{ "@id": x }` and `{ "$ref": x }` are parse
errors, and §2.5's remedy is a map: "a key that is not a name belongs in a map". That serves TSON's own data. It
fails a schema translated from JSON-LD, OpenAPI or JSON Schema, whose objects are records — a fixed set of declared
members — with names such as `@context`, `@id` or `$ref`. A map loses the record's declared shape, and renaming the
member loses the round trip.

**The idea.** Copy `enum_type.type`, which already says which family an enum's members belong to. A record gains
`name_type?: type_name ~ field_name`, the family its field names belong to, and `enum_type.type` is renamed
`name_type` to match. The default is the series' own
`field_name`, an identifier, so nothing changes unless a record says so. A record translated from JSON names a
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
  `{ "first name": 1 }` is a resolver error rather than a parse error. That is the one verdict that moves.
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
- **Inheritance keeps one name type.** A composition takes the one `name_type` its record supertypes state beyond
  the default: a supertype with the kernel's `field_name` states none of its own, so a translation's record composes
  a plain one (`node => json_record & address & { … }`) and the plain record's names are then judged by the
  composition's type at load — `order-id` is an identifier but no `json_name`, and is refused there. Two supertypes
  stating different non-default types are a resolver error; a refinement keeps its source's. So the brace form can
  restate an inherited relaxed name (`tightened => node ^ { "$ref": text }`).
- **A field name is matched in its name type's form.** The linker judges two declared names one field under the
  type's equality, so a reader matches a document's name against the declared ones in the type's `normalization`
  (§5.5), as an enum matches its members: under a case-folding name type `NAME` is the field `name`, the two
  spellings in one record are a duplicate field, and a family's selector is found however its name is cased. Both
  encodings do this; under the default, NFC, nothing changes.
- **The brace form states a type by composition, and has no syntax of its own.** A relaxed name type is a
  translation's tool, not the go-to form for a TSON record, so the series adds no brace-form spelling for it: a
  translation declares its type once, on a fieldless abstract base, and its records compose it —
  `json_record => !record { name_type: json_name  extension: ABSTRACT  fields: [] }`, then
  `node => json_record & { "@id": text  label: text }`. This needs nothing beyond the inheritance rule above, and
  any identifier family serves. The cost is an IS-A edge to the base, which
  asks nothing of a position typed by a member: `node` reads untagged. Rejected: inferring `text` from a quoted
  name, which cannot state a narrower type; a mark at the type-def head (`names json_name { … }`), which reserves
  a word there as `abstract` does; a type before `;` inside the brace, which gives `;` a second meaning; an
  annotation, which would make metadata change what a record admits.
- **Hygiene follows the type.** A record's field names and an enum's members meet §8.2's per-name rules under their
  type's own profile, whose added characters are its own (`json_name`'s `@` and `$`, `words`' medial space), and
  the collision relation over the scope.
- **A document's field names are judged by the record that reads them.** A name its record's type refuses is a
  resolver error, `ATOM_FORM_INVALID`, and draws no "unrecognised field" beside it; a name of the type the record
  does not declare is unrecognised, as any other. A record nested in a relaxed one judges its own names, so the
  relaxation does not leak into a value it holds. A name read where no record governs it — a schemaless record, the
  value of an unrecognised field — is judged as an identifier.
- **A lookahead does not judge a field name.** A sealed family's discriminator scan crosses a member's names before
  the member is chosen, and only the member knows its type, so a name the scan crosses is judged when the member
  reads it. The spec says nothing about when a name is judged relative to dispatch; this is the reading that
  reports a name once and by the right rule. A family's members compose its base, so they share its type, and the
  question would only bite a choice whose variants differ in theirs.
- **A name binds and writes as the record states it.** A host component binds a relaxed name through the name it
  states (`@Field("@id")`), as it binds any other, and a writer quotes a name that is no identifier, so the
  document reads back.

**Open:** the default for a JSON member name that is not an identifier; a template's field name type.

**Status against Revision 37:** open; experimental, in progress on `experiment/field-name-type`.
