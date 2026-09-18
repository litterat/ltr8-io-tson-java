# Name hygiene on the read path

Where [TSON-DATA] §8.2's name-hygiene rules and the token policy run during a data read, what they cost, and how a
refusal interacts with the verdicts around it. Current form only; history lives in git.

**Invariants**

- The token policy is the stream's, not the context's: a context rewinds, a stream produces each token exactly once.
- The per-name rules run in `DefaultTsonReadContext`, only on a freshly pulled event — never on a replayed one.
- A refused name draws no verdict beside its refusal: both record readers checkpoint `ctx.reported()` across the pull
  and skip their `UNRECOGNIZED_FIELD`.
- Both surfaces are allocation-free when nothing is refused; the call sites test the `Optional` rather than passing a
  lambda to `ifPresent`.
- `Confusables.skeleton` must never skip the table for ASCII: `m → rn` and `1 → l` carry mappings.
- `withTokenPolicy` defaults to `unrestricted()`, `withIdentifierPolicy` to Highly Restrictive over the whole name; a
  relaxation is a method, never ambient.
- The restricted-character rule is gated on the level (`appliesIdentifierProfile()`), at both walks.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/scope-push.md`,
`design/record-dispatch.md`, `design/diagnostic-model.md`, `design/diagnostic-rules-and-messages.md`,
`design/processor-policy.md`, `design/schema-side-diagnostics.md`. The schema-side walk is
`design/name-hygiene-and-minted-names.md`.

## Name hygiene on the read path ([TSON-DATA] §8.2)

**The token policy is the stream's, not the context's.** Both `TsonDataStream` and `JsonStream` apply it as
an event leaves them; neither read context takes one, and `TsonReadContext.of` has no parameter for
it. The reason is that a context **rewinds** — an event consumed during
lookahead is delivered again, and a probe context can be built over events already seen — so a check there
reports one token once per lookahead that crossed it, where a stream produces each token exactly once. It is
the stream's own step rather than a decorator over it because both encodings' streams need the same rule: a
wrapper is a second place to forget to
apply it, and two encodings with one rule should not have two mechanisms. At `unrestricted()` — the default,
and every ordinary read — the check is a field read and a branch.

**A refused name draws no verdict beside its refusal.** Name hygiene runs inside `ctx.next()`, so a name
§8.2 refused is reported before the reader has looked it up — and then the reader does not look it up:
`RecordAbstractReader.readFields` and `DataClassObjectReader.bindRecord` both checkpoint `ctx.reported()`
across that one pull and skip their `UNRECOGNIZED_FIELD` when the delta is non-zero. Only one event is
consumed between the two reads and nothing but the hygiene check reports during it, so the delta is exactly
"this name was refused".

The reason is not tidiness. A homoglyph of a declared name would otherwise draw both the refusal *and* "unknown
field 'pаssword' — the type declares (password)", which instructs the sender to add a field that is already
there when the fix is one character. **A refused name was never read, so nothing downstream can hold a
verdict about it** — reporting it unrecognised claims to have looked it up, which the processor declined to
do. Both readers apply the rule because a document's verdict must not depend on which one read it.

Two consequences, both deliberate. A name that is refused *and* genuinely undeclared yields only the
refusal; the sender fixes the character and learns on the next round whether the field exists. And a
`FIELD_REQUIRED` for the field the homoglyph was reaching for still stands, because it is true and useful —
the pair reads coherently where the suppressed one contradicted it.

**The look-alike rule is not reached by this** and is left alone: `CONFUSABLE_NAMES` is a property of a
*set*, asked of a record's field names after the record is read, so there is no `next()` to checkpoint
around — and it produces no comparable misinstruction.

A Class 1 document carries two names — a type-ref name and an annotation name, the positions §7.4 marks
`identifier` — and §8.2's restricted-character and restricted-script rules apply to both, **on by default**. They
run in
`DefaultTsonReadContext` as the name's event is first pulled, that being the first point on the read path
holding the read's diagnostics receiver for names: what `TsonDataStream` raises of its own is a thrown
`ParseException`, so a name check there could only say
"invalid", which is the one thing a refusal is not. What stays in the stream is §7.7's grammar, where a
failure really is a parse error.

**Only on a freshly pulled event.** `lookingAhead` rewinds what it consumed and a reader replays it, and
`AnnotationCapture` builds a probe context over events already seen — so checking every event would report
one name once per lookahead that crossed it. `NameHygieneTest` counts every shape an annotation takes,
because that is the failure that would survive every other test.

**Not in `TsonDataStream`**, which is where the *token* surface's policy runs and gets exactly-once
for free by sitting upstream of the rewind. The two surfaces sit on opposite sides of the rewind because they
default differently: the token policy defaults *off* — `unrestricted()`, which is every ordinary read — so the
stream's check is a field read and a branch, where the name rules §8.2 defaults *on* have to run wherever a
name is actually delivered.

**Both surfaces are allocation-free when nothing is refused**, which is what makes the on-by-default one
affordable. `UnicodePolicy.violation` and `IdentifierProfile.hygiene` each scan and return
`Optional.empty()` — no split array, no script set, no stream — and the two call sites test that `Optional`
rather than passing a lambda to `ifPresent`. That last part is not a style preference: a lambda capturing
the name and the receiver allocates whether or not the `Optional` holds anything, and at one per rule per
name it is the whole measured cost of a check that is otherwise free — ~110 bytes per bound record and ~640
per read on the name surface, and most of what a raised *token* policy would add. `AllocationHarnessTest`
carries the figures and the ceiling that catches a return to them.

**The look-alike rule is the expensive one, and `Confusables.skeleton` is where that was spent.** It runs
per name per record on the schemaless tree path, so normalising, building and re-normalising for every name
whether or not it carries a confusable character is what it must not do. It scans first and returns the
decomposition untouched when nothing maps — no builder, no second normalisation, and none of the stream and
capturing lambda a `forEach` over `codePoints()` costs — which is worth ~2.4 KB of a ~27 KB tree read. **What it must never
do is skip the table for ASCII**: eight ASCII code points carry a mapping, `m → rn` and `1 → l` among them,
so `payment` and `payrnent` read alike without a single non-ASCII character. `ConfusablesTest`
pins that pair for exactly this reason.

**Two surfaces, two defaults, and §8.2 sets both.** `withTokenPolicy` defaults to `unrestricted()` because a
value is data and may legitimately be anything; `withIdentifierPolicy` defaults to Highly Restrictive over
the whole name. Relaxing either is a method rather than a setting on purpose — §8.2 requires a deployment be able to
relax any of the three rules and requires the relaxation not be silent, and a policy read from the
environment is
invisible at the call site and absent from review. The relaxation to reach for first is the *unit*
(`perSegment()`), which still refuses `id_pаy` while admitting `url_адрес`. A token policy stricter than the
identifier policy subsumes it: a name is a token — which §8.2 asks an implementation's documentation to say,
and this is where it is said. The two names are §8.2's own: it defines the **identifier policy** and the
**token policy** as the two parts of a processor's configuration for that section, precisely so that two
implementations reporting them agree on what they are called, and `ProcessorConfig` uses those names.

**Field** names see all three, being names at every layer (§2.5, §7.7): the two per-name rules in the read
context beside a type-ref's and an annotation's, and the look-alike rule in `SchemalessTreeReader`, which is
where it belongs because it is a property of a *set* rather than of a name. One consequence worth knowing when
reading a report: a within-word homograph in a field name is refused as a restricted script before the
look-alike rule has a pair to compare, so a corpus vector isolating that rule wants two names each of which is
single-script. A refusal reports one code per rule —
`CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT` — each a verdict on the document like any other in
that the caller must change it or relax the policy. What these codes carry that a validity error does not is
that another processor at another Unicode version may accept the same document.

**The restricted-character rule is gated on the level, as the restricted-script rule is.** §8.2's
Unrestricted "drops the
profile too", taking that rule with it, so `appliesIdentifierProfile()` guards the `IdentifierProfile.hygiene`
call at both walks — the read context's and the linker's. Every other level keeps the profile, the
restricted-script rule gating itself inside `violation()`.

**And the run names the policy and the data version it judged under** (`design/processor-policy.md`). §8.2
reports a refusal under a *stated policy and a stated data version* and makes naming the version a MUST,
because §8.3 marks all three rules unstable across Unicode releases. Both are stated once, off the reader
that judged, rather than on each refusal.
`PolicyRefusalTest`/`SchemaPolicyRefusalTest` pin the data and schema ends against each other.
