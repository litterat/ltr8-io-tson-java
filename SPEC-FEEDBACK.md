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
core without the five, `TsonBundledSchemas.NET_ID` loaded by `Tson.standard()`, the schemaless `!hostname`
(`BuiltinTypeVocabulary.HOSTNAME`, checked equal to net.tn's resolved body), and corpus vectors for both the
schemaless and the schema-governed reads.

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

## 4. An identity is an IRI whose host is a `host` value: validity, NFC, and its URI spelling as the same identity

**Section:** [TSON-DATA] §2.2.1 (canonical identity: "lowercase host plus path"; "The argument is read as an
IRI-reference (§3.3), so a host or path may carry characters beyond US-ASCII as themselves; identity compares them as
written, and a percent-encoded spelling of the same characters is a different identity"), §3.3, §5.5; [TSON-SCHEMA]
§10.1, §11.2 (fetching and its allowlists); [TSON-JSON] §3.5 (the `TSON-Schema` header field); #5, #6.

**Kind:** underspecification, with a consequence that is a defect. Raised by the HTTP layer in
`ltr8-io-tson-java-http` (its `UPSTREAM.md` #3), whose fetch allowlist meets it, and confirmed here.

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

Restricting an identity to US-ASCII would close all four, at the price of the international names the IRI form
exists to admit. The resolution below keeps them, and it does so by defining the host once, as a type the series
already needs, rather than with rules of §2.2.1's own.

**Interpretation chosen.** A guess, recorded as one. `CanonicalIdentity` parses the reference as an RFC 3987
IRI-reference and requires `host.equals(host.toLowerCase(Locale.ROOT))` — Java's Unicode case mapping, a choice the
spec does not make — and checks nothing else beyond US-ASCII: no NFC, no IDNA validity, no relation to the URI
spelling. So `BÜCHER.example` is refused, while the precomposed and decomposed spellings of `bücher.example`, and
`xn--bcher-kva.example`, are three identities. An IPv6 host compares as written, so `[::1]` and `[0:0:0:0:0:0:0:1]`
are two.

**Suggested resolution.** The identity is the IRI, split as §2.2.1 already splits it, into a host and a path. The
host is defined by the vocabulary and the path by §2.2.1. Its URI spelling is accepted as the same identity, because
some carriers hold only US-ASCII, but it is never the identity: diagnostics, registries and every result name the
IRI, whichever spelling was written.

- **The host is a `host` value (#6).** RFC 3987's `ihost` is `IP-literal / IPv4address / ireg-name`. An identity
  takes the DNS profile of it, which is what `host` is: an `ireg-name` MUST be a `hostname` (#5), an `IPv4address`
  an `ipv4` and an `IP-literal` an `ipv6` (§5.5), so IPvFuture and zone identifiers are refused. RFC 3987 admits
  more (`_`, sub-delimiters, percent-encoding, any `ucschar`) because a registered name need not be DNS; an
  identity's is, since it is what a fetch looks up.
- **The host compares as a value**, so canonical identity's host is that value's canonical text: a name in its
  U-labels, an IPv4 address as its dotted-quad, an IPv6 address in RFC 5952 text. Validity, NFC and case are
  `hostname`'s rules and are not restated here. Three consequences, each the type's rule applied: `Example.COM`
  and `example.com` are one identity, so §2.2.1's "lowercase host" input rule is withdrawn for the host;
  `xn--bcher-kva.example` and `bücher.example` are one; and `[::1]` and `[0:0:0:0:0:0:0:1]` are one.
- **The path is NFC.** Characters beyond US-ASCII in the path MUST be in Normalization Form C, as RFC 3987 §5.3.2.2
  recommends; a path that is not is an error, not a candidate for normalization. A path needs no type of its own: an
  identity's path is not a file path or a route, and nothing but §2.2.1 would use one. The rest of §2.2.1's path
  rules stand.
- **The URI spelling of the path is read back** (RFC 3987 §3.2): each percent-encoded UTF-8 sequence of a character
  beyond US-ASCII to that character, so `https://xn--bcher-kva.example/sch%C3%A9mas/x.tn` and
  `https://bücher.example/schémas/x.tn` are one identity, and its canonical form is the second. The URI spelling
  stays one spelling per identity: a percent-encoding that is not the UTF-8 of an IRI character beyond US-ASCII
  (`%FF`, an encoded bidi control) has no IRI form and is an error; the encoded character must be NFC as the
  path's are; and lowercase hex is an error. A percent-encoded ASCII character keeps today's rule: an unreserved
  one is an error, and a reserved one (`%2F`) is part of the identity as written.
- **Nothing unstable decides it.** NFC is covered by Unicode's normalization stability policy, as the identifier
  grammar's use of it already relies on (§7.7), and `hostname`'s validity is IDNA2008's, which #5 shows stable in
  the way a content-addressed name needs.

How a processor spells an identity on a wire that needs US-ASCII — a fetch's request, an HTTP field — is its own
business, and the spec needs to say only that the URI spelling is accepted as the same identity. [TSON-JSON] §3.5
then needs a sentence: a reference beyond US-ASCII is carried in its URI spelling (RFC 3987 §3.1, the host by
ToASCII). A processor that reports a fetch it made in that spelling should name both — the identity, and the URI it
requested — since the first says which schema and the second is what DNS and a proxy saw.

**Status against Revision 37:** open; not built. The `toLowerCase` check above runs; the host as a `host` value, the
NFC requirement and the reading of the URI spelling do not. The HTTP layer's `deployment.tn` types `schema_hosts` as
`[hostname]`, so it allow-lists ASCII hosts only, pinned by
`UpstreamGapsTest.anIdentityWithANonAsciiHostCannotBeAllowListed`.

---

## 5. `hostname` is a domain name in either label form, not an ASCII pattern

**Section:** Revision 38's `net.tn` (#2); [TSON-DATA] §5.5's `!hostname` row as #2 proposes it; [TSON-SCHEMA] §5.5
(text normalization), §9; #4.

**Kind:** proposal — `hostname` as running is ASCII only, narrower than the identities it is needed to name.

**The problem.** `hostname` as #2 built it is an instance of `text_type`:

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

**Proposed (not built).** `hostname` becomes an atom, an instance of a new meta constructor:

```
hostname_type => atom & atom_specification & {
  spec?:      = "https://www.rfc-editor.org/rfc/rfc5890"
  allow_idn?: boolean ~ true
}
```

- **Lexical space:** labels that are LDH labels, A-labels or U-labels (RFC 5890), in any mix, ASCII letters in
  either case; each label at most 63 octets and the name at most 253 in its A-label form; no trailing dot; the last
  label not beginning with a digit, keeping `( hostname | ipv4 )` without a value in both. A U-label is RFC 5890
  §2.3.2.1's: NFC, every code point PVALID or meeting its contextual rule under RFC 5892, the name meeting RFC
  5893's bidi rule. An uppercase character beyond ASCII is therefore outside the lexical space, as IDNA2008 has it,
  rather than mapped. A percent-encoded spelling (`b%C3%BCcher.example`) is URI syntax and not a host name, as
  `[::1]` is not an address (#6), and is refused.
- **Value:** the domain name. `Example.COM` and `example.com` are one value, and so are `xn--bcher-kva.example` and
  `bücher.example`. Its two forms are views of the one value, each recoverable from the other: the U-label form for
  display and an IRI, the A-label form for DNS, a URI and HTTP; ToASCII and ToUnicode between them are Punycode
  (RFC 3492) over a valid label and need no table. A writer emits the U-label form, which is the form #4 takes for
  an identity's host, so an allowlist entry and an identity's host compare by name in either spelling.
- **`allow_idn`:** false admits only names with no internationalized label, in either spelling, so
  `xn--bcher-kva.example` is refused as `bücher.example` is: a property of the name, for a system that cannot carry
  one, and not a restriction on how a name is written. There is no facet choosing a spelling, since the value is the
  name, and none restricting scripts: script data is what [TSON-DATA] §8.2 keeps out of validity, and a homograph
  defence for host names would be the policy's, not the type's.
- **Stable enough to name a content-addressed document.** Nothing is mapped, so UTS #46's tables never enter.
  RFC 5892's property is computed per Unicode version, but it is built so that a code point once PVALID stays so: a
  newly assigned character can become valid, and a valid name never becomes invalid. That is what lets #4 make an
  identity's host a `hostname`, and what keeps this out of [TSON-DATA] §8.3's unstable data.
- **Host value:** a host-name type of its own (the Java reference would put it in `io.ltr8.net`, beside its address
  types), parsed from either spelling and exposing both forms. It is also `host`'s name member (#6), so one
  definition serves this atom, `host` and an identity's host (#4).

**Suggested resolution.** Meta gains `hostname_type` as above, and `net.tn` declares `hostname => !hostname_type {}`.
The [TSON-DATA] §5.5 row reads: "`!hostname` — a domain name (RFC 5890): LDH labels, A-labels or U-labels; equal by
name; host value a host name".

**Status against Revision 37:** open; proposal, not built. What runs on `r2026-38-proposal` is the `text_type` above
(#2).

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

**Proposed (not built).** `host` in `net.tn`, an instance of a new meta constructor:

```
host_type => atom & atom_specification & {
  spec?: = "https://www.rfc-editor.org/rfc/rfc3987"
}
```

- **Lexical space:** the union of `hostname`'s (#5), `ipv4`'s and `ipv6`'s, which do not overlap. One string-class
  atom, so `listener.host: host` takes `localhost`, `"127.0.0.1"` and `"::1"` untagged.
- **No brackets.** RFC 3987 brackets an IPv6 address in a host (`[::1]`) so that a URI can find the port after it;
  a value has no port to separate, and the address is the value. `[::1]` is URI syntax and refused, as a
  percent-encoded host name is (#5). Inside an identity the host is written with the brackets the IRI grammar
  requires, and its value is the address within them (#4).
- **Value:** a host name or an address, never both, with each member's equality: a name by name (#5), an address
  numerically, so `::1` and `0:0:0:0:0:0:0:1` are one value. A name never equals an address. Its canonical text is
  the member's: U-labels, a dotted-quad, RFC 5952 text — the form #4 takes for an identity's host, so an allowlist
  typed `[host]` matches identity hosts by the type's own equality.
- **No facets yet.** A position that wants only names, or only addresses, uses `hostname`, `ipv4` or `ipv6`.
- **Host value:** a host name or an address (the Java reference would type it in `io.ltr8.net`, beside the host-name
  type #5 adds and its address types).

**Suggested resolution.** Meta gains `host_type`, and `net.tn` declares `host => !host_type {}`. [TSON-DATA] §5.5
gains a row: "`!host` — a host name, IPv4 address or IPv6 address (RFC 3987 `ihost`, DNS names only, no brackets);
equal by its member's equality; host value a host name or an address".

**Status against Revision 37:** open; proposal, not built. The HTTP layer's `listener.host` is `text`.
