# The `Diagnostic` model

The one record every `DiagnosticsReceiver` receives — its `Code` vocabulary, its four location components, and the
rule deciding when a fact earns a component. Current form only; history lives in git.

**Invariants**

- `Diagnostic` lives in `tson-base` and its classifiers do not: each encoding owns the switch over its own exceptions
  (`TsonDiagnostics`, `JsonDiagnostics`).
- One record for data- and schema-side problems: the variation is locational, not categorical.
- Every component is a location; a routing question belongs in the `Code` — a fetch failure is five codes, a §8.2
  refusal one code per rule, and neither carries a component of its own.
- `SchemaFetchException.Reason` is the single input to `Diagnostic.Code.of`, so the thrown and collected channels
  cannot disagree.
- `Code.verdict()` answers whether a code is a verdict on the document at all.
- Both pointers are `Optional<String>` because `""` is the root, a location this really emits; `schemaIdIfKnown()`/
  `expectedIfStated()`/`actualIfStated()` answer absence for the three plain strings, and nothing does for the pointers.
- A fact constant for a run (`ProcessorPolicy`, `LimitsPolicy`) is stated once per run, never per diagnostic.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/scope-push.md`,
`design/record-dispatch.md`, `design/name-hygiene-read-path.md`, `design/diagnostic-rules-and-messages.md`,
`design/processor-policy.md`, `design/schema-side-diagnostics.md`.

## Diagnostics

**`Diagnostic` lives in `tson-base`, and its classifiers do not.** The record, its `Code` enum, the three
receivers and `ReadException` are a module of their own, because [TSON-JSON] §9.4 makes a second
encoding report in the same four categories — one vocabulary by specification, not by convenience. The
`of*` factories that turn a thrown failure into a diagnostic live in `tson-compiler` as `TsonDiagnostics`,
because every one of them switches on an exception type this engine declares. What is shared is the shape of
an answer; classifying a failure is reading a document, and that is each encoding's own. (`Diagnostic`, root package)

`Diagnostic` is the structured value every `DiagnosticsReceiver` receives, identical shape whichever
one is in play: a closed `Code` enum (`FIELD_REQUIRED`/`FIELD_FIXED`/`TYPE_MISMATCH`/`WRONG_ARITY`/
`UNKNOWN_TYPE_REF`/`ATOM_FORM_INVALID`/`ATOM_CONSTRAINT_VIOLATION`/`UNRECOGNIZED_FIELD`/
`DUPLICATE_MAP_KEY`/`DUPLICATE_FIELD`
from readers; `CONFUSABLE_NAMES`/`RESTRICTED_CHARACTER`/`RESTRICTED_SCRIPT` for §8.2's three name-hygiene
rules;
`SCHEMA_ERROR`/`UNKNOWN_TYPE`/`VALIDATION_ERROR` for infrastructure-level failures, plus
`NOT_IMPLEMENTED`/`BIND_MISMATCH`/`LIMIT_EXCEEDED` and the five `SCHEMA_*` fetch codes — the members that are
not a verdict on the document at all, which `Code.verdict()` answers),
`message` (hand-composed per call site), `expected`/`actual` (machine-parseable) and **four location
components covering two ends** — the value in the data, and the rule in the schema. Every component is a
location; the one fact that is not, why a schema could not be obtained, is carried by the code itself.

**The four are JSON Schema 2020-12 §12's own output unit**, deliberately: `path` is `instanceLocation` (an
RFC 6901 pointer into the data), `schemaPointer` is `keywordLocation` (the path through the schema being
validated against, `/person/age`), `schemaId` plus `schemaPointer` are `absoluteKeywordLocation`, and
`dataPosition`/`schemaPosition` add the line/column/byte-offset TSON needs and
JSON Schema has no equivalent of. **One record rather than separate data- and schema-diagnostic types,
because the variation is locational, not categorical** — a value violating `int32` as core.tn declares it
populates both ends at once, and `javax.tools.Diagnostic`, LSP's `Diagnostic` and rustc's `DiagInner` all
model it the same way (rustc's `MultiSpan` being the mature form of the same idea).

**A fetch failure is five codes, not one code and a field.** `SCHEMA_NOT_PERMITTED` names a reference this
deployment will not fetch and `SCHEMA_NOT_FOUND` one nothing serves, both the document's to fix, where
`SCHEMA_UNREACHABLE`/`SCHEMA_TIMEOUT`/`SCHEMA_TOO_LARGE` say the reference was fine and the world was not.
That is the difference between telling a sender to correct its document and telling it to retry, and it is a
question consumers *route* on — so it lives where routing values live. A field beside the code would be a
second carrier for one fact, and would cost a `Diagnostic` component, a `TsonReadContext.report` overload
existing only to carry it, a `SchemaFailure` component, a `CliDiagnostic` component and a hand-copied enum
in `diagnostics.tn`.

**Five rather than two** (a "permanent" and a "transient" code) because consumers cut the same five
differently: a command line by whether a rerun could help, an HTTP surface by whose doing it was. One code
per reason keeps every partition derivable and privileges none. `SchemaFetchException.Reason` remains the
throwing channel's own vocabulary and the single input to `Diagnostic.Code.of`, so the two channels one fetch
failure travels on cannot disagree.
A consumer that resolves its schemas at startup sees `SchemaFetchException` thrown and reads
`reason()`; one that reads through a collecting receiver — the common path for a server validating request
bodies — sees a `Diagnostic` and never sees the exception at all. With the reason on the thrown channel
only, the same refused reference would be the sender's mistake read one way and an operator's read the other.
`SchemaFailure` carries it from the `catch` to the report as its `code` — mapped by `Code.of`, so the ordinary
four-argument `TsonReadContext.report` states it and there is no overload for it — and
`TsonDiagnostics.ofSchemaUnavailable` takes the exception rather than its message so the schema-document
channel states it too.

**A §8.2 refusal carries no component of its own**, by the same rule that puts a fetch failure's cause in
the code. §8.2 requires a refusal to name the Unicode data version it was computed against, which is
a fact about *this processor* rather than about the problem — see `design/processor-policy.md`, which is
where it and the policy are stated, once.

**Which rule refused is the code, and nothing beside it.** One code per §8.2 rule —
`CONFUSABLE_NAMES` for skeleton distinctness, `RESTRICTED_CHARACTER` for `Identifier_Status`,
`RESTRICTED_SCRIPT` for the restriction level — because the three want three different remedies: rename one
of a colliding pair, change the character, or relax the level or unit or name a script set.
`RESTRICTED_SCRIPT` says *a script this policy does not admit*, which is wider than a mix: a combination is
the usual finding, but at `ASCII_ONLY` a single-script name is refused with nothing mixed at all, so the code
names what the policy refused rather than what the text did. It pairs with `RESTRICTED_CHARACTER` as the two
halves of one identifier policy. The rule belongs in the code because the
code is what a consumer routes on; a second enum beside it would restate a fact the code already fixes and
would be free to contradict it. The names say what each rule *found* rather than how it works, §8.2's own
headings being exact and being jargon a consumer reading an error body cannot decode — the conformance
runners keep an explicit table from the corpus's spec-named spelling to these, since the two vocabularies
differ on purpose.

`RESTRICTED_SCRIPT` is also the one code a *value* can carry, a token having no identifier profile and no
scope to be distinct within.

**`Code.verdict()` answers the other question a consumer asks** — whether the code is a verdict on the document at
all, which the five fetch codes, `NOT_IMPLEMENTED`, `BIND_MISMATCH` and `LIMIT_EXCEEDED` are not.

## When a fact earns a component

Every component is a location, and the rule that keeps it that way is one line: **carry a fact as a
component when it is not recoverable from the document plus the schema, when it is a fact about the problem
rather than about the processor, and when it is not something the consumer routes on** — a routing question
belongs in the `Code`, which is what a consumer already switches over.

| Problem | Where the fact already is | Component? |
|---|---|---|
| Atom constraint violation | the bound is in the schema, at `schemaPointer` | no |
| `UNRECOGNIZED_FIELD` | the alternatives are the declared field list | no |
| `DUPLICATE_FIELD`/`DUPLICATE_MAP_KEY` | in the document, at `path` | no |
| §8.2 refusal: which rule | it is the `Code` | no |
| why a schema was not obtained | nowhere — it is about the world | no — it is the `Code`, one per reason |
| §8.2 refusal: the policy and the Unicode tables | nowhere — it is this processor's configuration | no — `ProcessorPolicy`, once per run |
| §9.1 refusal: which bound, and what it is | nowhere — it is this processor's configuration | no — `LimitsPolicy`, once per run; the bound itself rides in `expected`/`actual` |

Most diagnostics are about something the consumer is already holding, which is why `expected`/`actual` are
enough for them: a rendered `<= 100` is a convenience, and the authoritative copy is a file the consumer has.
The one exception is the case where the cause lives outside both documents. The last row is the second half
of the rule: the fact is unrecoverable *and* actionable, and it is still not a component — because it belongs
to the run rather than to the problem, so putting it here would be N copies of one answer.

`tson-cli`'s wire shape applies a second filter, whether the recipient can act on it.

Either end may be absent: a schema-side problem has no data, and a schemaless read has no schema. **Both
pointers are `Optional<String>`, and that is load-bearing** — RFC 6901 spells "the whole document" as `""`,
and this type emits it for real (a document-level schema problem such as an unloadable `!!import` points at
the schema root; a base-syntax failure points at the data root), so spelling "no such end" the same way would
make the two indistinguishable to a consumer *and* to a renderer. A present `""` is the root; an absence is
an absence. `schemaId`/`expected`/`actual` stay plain strings, where `""` carries no second meaning.

**That split is right at the source and useless at the sink**, so `schemaIdIfKnown()`/`expectedIfStated()`/
`actualIfStated()` say it once. Anything rendering a diagnostic onto a wire — the CLI's own `CliDiagnostic`,
an HTTP error body, anything downstream — wants a single answer to "is there anything here", and otherwise
has to know per component which of the two conventions applies. Nothing offers the same narrowing for the
pointers, deliberately: there `""` is a value, and a helper that swallowed it would erase the distinction the
paragraph above exists to keep. **The wire *shape* is not shared and should not be** — a CLI report and an
HTTP problem body are different envelopes with different audiences — but every renderer of one re-derives
this same absence rule, and that much belongs on the type.
