# `ProcessorPolicy` and `LimitsPolicy`

The configuration a report is read against — the two §8.2 Unicode policies, the UCD version and §9.1's limits — how it
is threaded and configured, why it is not a diagnostic component, and where the nesting bound is counted. Current form
only; history lives in git.

**Invariants**

- The policy is threaded as one value; `withIdentifierPolicy`/`withTokenPolicy`/`withLimits` each change exactly one
  component, deriving from what is already stated.
- The two surfaces have two types: an `IdentifierPolicy` (all three §8.2 mechanisms, a unit, a skeleton switch) and a
  token `ScriptPolicy` (a level over whole text). A per-segment token policy is unwritable, not refused.
- It is not a diagnostic component: constant for a run, needed before a document is written, and a level says more
  than a version.
- It is read off the reader that judged, not rebuilt from a configuration object.
- Only nesting depth is bounded, at §9.1's default of 64, counted in `TsonDataStream.advance` — the token stream, not
  the readers — so the refusal lands before any reader descends and reaches schema documents too.
- `LIMIT_EXCEEDED` is not a verdict and has its own classifier (`Diagnostic.ofLimitExceeded`), caught ahead of
  `ofBaseSyntaxError`; the CLI still exits 1.
- Its wire shape is the spec's: `spec/m/policy.tn` (a bundled companion artifact, `TsonBundledSchemas.POLICY_ID`)
  declares `policy` and its parts, and the CLI's `diagnostics.tn` imports it rather than restating it.
  `PolicySchemaTest` holds `restriction_level` to `RestrictionLevel`. No document selects the policy it is judged
  under ([TSON-DATA] §8.2), so nothing here reads a policy out of a document.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/scope-push.md`,
`design/record-dispatch.md`, `design/name-hygiene-read-path.md`, `design/diagnostic-model.md`,
`design/diagnostic-rules-and-messages.md`, `design/schema-side-diagnostics.md`.

## `ProcessorPolicy` — the configuration, stated once

**And threaded as one value.** Every constructor and derivation that needs an `IdentifierPolicy`, a
`ScriptPolicy` and a `LimitsPolicy` takes the policy
instead: both streams, both TSON facades, and `JsonObjectReader`. Three parameters that always travel
together is how a caller comes to pass a bound from one policy beside a token surface from another, and a
bare `int` beside a `ScriptPolicy` is the same hazard with less to grep for.

A reader's `withIdentifierPolicy`/`withTokenPolicy`/`withLimits` each change exactly one component, and
`ProcessorPolicy` has the matching three so a reader's derivation is one call rather than a rebuild.
`withProcessorPolicy` is the whole-value form, and what `Tson.objectReader()`/`treeReader()` use — chaining
all three is three chances to state two and forget the third.

**The front door states it the same way.** `ProcessorConfig.withProcessorPolicy` takes the whole value and is
the setter to reach for; `withIdentifierPolicy`/`withTokenPolicy`/`withLimits` are its components, each
deriving from whatever is already stated rather than replacing it, so a piecewise configuration and a composed
one reach the same processor and neither clobbers the other. `Tson` holds the one value, so
`Tson.processorPolicy()` is an accessor: the identifier half is handed to the schema registry at construction
because the linker judges declared names, and that is a use of the policy rather than a second home for it. A
policy reassembled on demand from components living in three places is one a caller can state and a report can
contradict. It is also what lets one policy configure both encodings — `Json.of(config)` reads this same value
off the `ProcessorConfig` that `Tson.of(config)` takes, and a deployment stating its constraints twice has two
places to get them wrong.

**The two surfaces have two types, because they have two shapes.** `IdentifierPolicy` is §8.2's identifier
policy: a `ScriptPolicy` level, a unit (whole name or `perSegment()`), and `withSkeletonDistinctness`, the switch
for mechanism 1 that no level reaches — UTS #39 ties `Identifier_Status` to Unrestricted, and nothing ties a relation
over a set to a level that judges one name. `ScriptPolicy` alone is the token policy: a level and any `permitting`
combinations over whole text. `_` and `-` are word separators by convention in a name and ordinary characters in a
value, so segmenting a value would admit UTS #39's own `Toys-Я-Us`; with no unit on the type, no route can state one.

**`IdentifierPolicy.judge(name, profile)` is the one place the per-name rules are applied** — the read contexts of
both encodings, the bind reader and the linker all call it, each reporting the violations it returns in its own
shape. It gates the restricted-character rule on the level, exempts the profile's own added characters, and divides
a per-segment unit at the profile's separators (`IdentifierProfile.separates`: `_` and whatever the profile adds that
is not `XID_Continue`). The scopes ask `appliesSkeletonDistinctness()` before they build one.

**It carries three settings, not two.** The identifier policy, the token policy and the limits, plus the UCD
version the first two were computed against. A nesting bound has no business inside a *Unicode* policy,
which is why the container is the processor's and the two Unicode policies are two of its components. A deployment
states one policy; the three components stay independent, and changing one still says nothing about the
others.

The two §8.2 policies (`identifierPolicy` and `tokenPolicy` — `ProcessorConfig`'s own names for them, so a
configuration and the report it produces are one vocabulary; each a level and any `permitting` relaxations, and the
identifier policy its unit and skeleton switch besides) and the UCD version the rules were computed against, as one
value: `Tson.processorPolicy()`, either facade's `processorPolicy()`, and `tson policy` on the command line, which
prints it as text, JSON, or a TSON document. Every `tson-cli` envelope carries one in its `policy` field.

**It is not a diagnostic component, and the three reasons are the shape of the whole design.**

- **Cardinality.** It is constant for the life of a process. Twenty refusals in one document would carry
  twenty copies of a string that cannot differ, and a consumer given twenty copies has to decide what a
  disagreement between them would mean.
- **Time.** A component on a refusal exists only once something has been refused. What a sender needs in
  order *not* to be refused is the same fact before it writes the document — which is why the standalone
  surface matters more than the envelope one, and why `tson policy` exists at all. A generator that reads
  the policy first never writes the name that would be refused.
- **Direction.** A version says what refused you; a level says what would be accepted. `16.0` is not
  something a caller acts on, where `ASCII_ONLY` is. Two processors at one UCD version routinely disagree,
  because the level is a local choice; two at different versions rarely do — so the half §8.2 requires is
  the half that explains less.

**Read off the reader that judged**, not rebuilt from a configuration object: a derived reader (`withIdentifierPolicy`,
`withTokenPolicy`) is exactly where the two can differ, and a response quoting the wrong one is worse than quoting none.
`ProcessorPolicy.dataVersion()` is the version as a static accessor over `Xid.UNICODE_VERSION`, so a caller holding a
policy has the data version beside it. §8.2 requires exactly this shape: the policy and the data version are properties
of the *report*, not of the refusal, and a processor MUST make both available with any report containing one and SHOULD
make them available with no document in hand.

**It is what makes a §8.2 divergence explainable**: the same bytes may be refused here and accepted elsewhere, and the
reason is in neither the document nor the schema.

## `LimitsPolicy` — §9.1's bounds, on the same terms

What this processor will *spend* reading a document, where the policy above is what it will *admit as a
name*: `Tson.limitsPolicy()`, either facade's `limitsPolicy()`, `TsonTreeReader.withLimits`, `tson policy`,
and a `limits` record inside every `tson-cli` envelope's `policy` field. **Beside the two Unicode policies
inside `ProcessorPolicy`, not inside either of them** — they answer different questions, and a deployment
that changed one has said nothing about the other. The three arguments above transfer whole: a bound is constant for a run, a
sender needs it before it writes, and a number a caller can act on beats a refusal after the fact.

**Only nesting depth is bounded**, at §9.1's own default of 64. §9.1 states the whole set as one table with a
default each — eleven more on the document side — and [TSON-SCHEMA] §11.5 adds five on the schema side under
the same policy and the same reporting surfaces; `BACKLOG.md` carries what is left and where each is counted.
It is a record with one component so each lands on it rather than beside it. One of the schema-side five
`TemplateMaterialiser.MAX_CLOSING_DEPTH` already enforces as a bare constant with nowhere to live. The default of 64 is
the tightest in common use, so a document that fits travels.

**It is a component of `ProcessorPolicy`** — `Tson.limitsPolicy()` is `processorPolicy().limits()` in one call. A
deployment states one policy, and the CLI envelope nests `limits` under `policy`. What the grouping keeps is the
independence, not the separation: the three components answer three questions, and changing one still says nothing about
the others.

**Counted in the token stream, not in the readers.** `TsonDataStream.advance` tracks bracket depth — the
schema parser's error recovery reads the same counter — and that is the one place every token is consumed — so the check is
one comparison per opening bracket and the refusal happens *before* any reader descends. That ordering is the
whole point: the stream is iterative and never overflows, while every reader over it recurses
(`SchemalessTreeReader.readNode` → `readArray` → `readNode`), and `EventSkip` recurses through values no
reader keeps and no context path steps. A limit enforced at the readers would have to be enforced at each of
them; enforced at the counter it also reaches schema documents, which are untrusted input wherever one is
fetched or `!!import`ed, through the same code.

**What the bound prevents.** A document a few thousand containers deep — about 10 KB, an ordinary request
body — would otherwise exhaust the Java stack. A `StackOverflowError` is an `Error`, so it passes through every
`catch (RuntimeException)` in the reader stack and in `TsonCli.run` alike: no report on stdout, a JVM stack
trace on stderr, and exit 1, the code meaning *your document is invalid*. `LimitsPolicyTest` pins that such
a document reports instead.

**The refusal is not a verdict** (`Diagnostic.Code.LIMIT_EXCEEDED`, `verdict()` false): the document may be
well-formed, valid, and read in full by the next processor along. It has its own classifier
(`Diagnostic.ofLimitExceeded`) rather than a case inside `ofBaseSyntaxError`, because a base-syntax failure is
a verdict every processor repeats and this one is a statement about the reader's configuration; both facades
catch it ahead of the `RuntimeException` that reaches the other. It is still a **rejection by this processor**
(`Code.isRefusal()`), as a §8.2 name refusal is: the CLI's envelope says `REJECTED` and it exits 1, the runner
holding the fix (`--max-depth`, or a smaller document).
