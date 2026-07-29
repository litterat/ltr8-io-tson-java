# Structured output / LLM validation plan

The target-use-case plan for TSON in the LLM "structured output" ecosystem — TSON's stricter,
formally-specified schema/grammar design is well-suited to validating LLM-generated data, both as a
fast, localized-feedback validator (pydantic/pydantic-ai/Instructor-style) and, longer term, as a
constrained-decoding grammar source (outlines/xgrammar/guidance-style). See `BACKLOG.md` for the
general engineering backlog this document doesn't cover, and `CLAUDE.md`'s "Not yet implemented"
section for technical detail behind specific items.

---

## Target use case: LLM structured output

TSON's strictness and formal, unambiguous grammar (unlike JSON Schema, which has no equivalent)
make it a strong fit for validating LLM-generated structured output, in two distinct tiers:
**validate-after-generate** (pydantic/pydantic-ai/Instructor-style: the model emits a full
response, TSON validates it, and on failure returns fast, localized error feedback for a retry
loop) and **constrain-during-generate** (outlines/xgrammar/guidance-style: the schema is compiled
into a token-level automaton so the decoder can't emit invalid output at all). Real advantages
already in place: a formal ABNF grammar top to bottom, bounded/typed atoms instead of JSON
Schema's advisory `format`, tagged unions via `!C value` construction (the type-ref *is* the
discriminator, unlike untagged `oneOf`), exhaustively-resolved field state
(`REQUIRED`/`OPTIONAL`/`REQUIRED_DEFAULT`/`REQUIRED_FIXED`), and a compile-once/read-many
architecture (`TsonCompiledSchema`/`TsonCompiledRegistry`) that already fits a hot request loop.
Recursive/deeply-nested schemas remain a genuinely shared hard problem either way — not something
TSON magically avoids.

**Tier 1 (validate + fast feedback) is the near-term target.** Tier 2 is real but a separate,
much larger future effort, captured here only at a high level.

### Tier 1 — validate + fast feedback

- [ ] **A structured `Diagnostic` value, not an exception**, returned from a non-fail-fast
  `validate(...)` call (`List<Diagnostic>`, empty = valid) — this is the multi-error-collection
  need, now given a concrete shape:
  - `path` — location in the *data* document, as an RFC 6901 JSON Pointer (e.g.
    `/orders/3/total`). Reuses an existing IETF standard rather than inventing a TSON-specific path
    syntax, and matches the convention JSON Schema's own standardized output format already uses
    for `instanceLocation`.
  - `code` — a stable, machine-readable identifier from a **closed TSON vocabulary** (a real enum,
    the same modeling discipline `TypeKind`/`FieldState` already use — not a free string). Starter
    set: `FIELD_REQUIRED`, `TYPE_MISMATCH`, `INTEGER_OUT_OF_RANGE`, `ENUM_MEMBER_NOT_RECOGNIZED`,
    `PATTERN_MISMATCH`, `DUPLICATE_MAP_KEY`, `UNRECOGNIZED_FIELD`, `WRONG_ARITY`,
    `UNKNOWN_TYPE_REF`.
  - `message` — a rendered, human/LLM-readable sentence, generated *from* `code` + params, not
    hand-written per call site.
  - `expected` — the actual constraint, rendered concretely (e.g. "an integer between
    -2147483648 and 2147483647," "one of: PENDING, SHIPPED, DELIVERED" — never a vague "invalid
    value").
  - `actual` — the literal offending value/text, verbatim — critical for a retry loop: the model
    can't reliably fix what it can't see it wrote.
  - `dataPosition` — line/column/byte offset in the *submitted* document. The lexer already
    produces `Position`; this needs threading through the compiled-reader validation path, plus
    accumulating `path` as validation descends into nested structures.
  - `schemaPosition` — line/column of where the relevant type/field was *declared in the schema
    source*. This is the "store `Position` on `RecordBody`" idea (and the other `Top` variants —
    `ArrayBody`/`MapBody`/`TupleBody`/`ChoiceBody`/the atom constraint families have the identical
    gap), folded in here as the concrete reason it matters: it distinguishes "your data is wrong"
    from "the schema itself is the problem," and is something pydantic/JSON Schema tooling
    generally can't offer at all, since their schemas aren't parsed, positioned documents the way a
    TSON schema is. Decide the shape once (declaration-level `Position` on `TypeDefinition` vs.
    per-body-variant) before implementing it piecemeal.

- [ ] **Alignment with existing conventions** — no single industry standard exists, but two real
  conventions are worth deliberately aligning with:
  - **Pydantic v2's `ValidationError.errors()` shape** (`type`/`loc`/`msg`/`input`/`ctx`) — the de
    facto standard in the Python LLM-tooling ecosystem specifically (pydantic-ai, Instructor, and
    LangChain-style output-fixers all build on or feed this shape back to the model). This is the
    **primary alignment target**, since pydantic-ai is the named ecosystem — `Diagnostic` maps
    directly: `code`↔`type`, `path`↔`loc`, `message`↔`msg`, `actual`↔`input`,
    `expected`+params↔`ctx`.
  - **JSON Schema's own standardized output format** (draft 2020-12: `keywordLocation`/
    `instanceLocation`/`error`) — relevant specifically for the `path` field's RFC 6901 convention,
    so tooling that already speaks JSON Schema validation output can consume TSON's `path` with no
    translation.

- [ ] **Most important information, ranked** (for an LLM retry loop specifically): (1) **where** —
  path/position in the data, without which the model has to re-scan its own output; (2) **what
  kind of problem, categorized** (`code`) — lets a system prompt/few-shot pattern generalize a fix
  instead of parsing free text; (3) **what was actually there** (`actual`) — closes the loop; (4)
  **what was expected**, concretely (`expected`) — turns "too large" into "must be ≤ 100"; (5) a
  single ready-to-paste `message` synthesized from 1–4, which is what actually gets dropped into a
  retry prompt in practice, but should be a *rendering* of the structured fields, not
  hand-authored per site.

- [ ] **A TSON-native idea worth pursuing**: since a diagnostic list is itself structured data,
  define it as a real TSON type (a small new "diagnostics" vocabulary) and validate it against its
  own schema — dogfooding the library for its own error output, and giving any TSON-aware tool a
  typed, validated way to consume TSON's own validation errors for free.

- [ ] **Schema-to-prompt rendering** (a supporting need, not the main focus) — a way to render a
  *resolved schema* back out as compact, LLM-promptable text (TSON's equivalent of
  `.model_json_schema()`), distinct from the "write side" (writing *data*, tracked in
  `BACKLOG.md`). Tier 1 implicitly assumes the model was told the target shape in the first place.

- [ ] The same fail-fast gap exists one layer down, in the lexer (`LexException`, unchecked,
  despite §8.1's "SHOULD continue processing to report multiple issues"). Worth deciding whether
  lexer/parser errors eventually feed the same `Diagnostic` model, or stay a separate concern.
- [ ] `REQUIRED_FIXED`/`OPTIONAL_FIXED` identity-diagonal invariant (a restated fixed field's value
  must not change) — never checked.
- [ ] `value_param` — parametric field modifiers are recorded but never substituted at application
  time.
- [ ] §9.1's numeric-literal length limit (SHOULD, default 4096 digits, DoS hardening) — not
  enforced anywhere.

### Tier 2 — constrain-during-generate (kept high-level)

- [ ] A schema→grammar/automaton compilation backend — a new compiler backend symmetric to
  `TsonValueReader`, but targeting a CFG/regex/automaton instead of a reader. TSON's tagged unions
  (`!C value`) and bounded atoms (exact bit-width integers) compile more cleanly than JSON Schema's
  untagged `oneOf`/arbitrary `minimum`/`maximum` — but recursive schemas still need a real PDA/CFG,
  not a flat FSM, same as JSON Schema's recursive `$ref`; not a free win.
- [ ] Incremental/streaming matching (can this partial prefix still complete validly?) — a
  fundamentally different mode from the current batch parse-then-validate pipeline, since
  generation happens token-by-token.
- [ ] Tokenizer-vocabulary compilation (mapping a character-level grammar onto a specific model's
  token vocab) is explicitly **out of scope for TSON core** — a downstream adapter's job, the way
  outlines/xgrammar already do this for JSON Schema today.

### JSON compatibility

Worth calling out as arguably the **most immediately practical** path to Tier 1, not just a nice
interop feature: most LLM APIs' own "JSON mode"/"structured output"/tool-use features (OpenAI,
Anthropic, Gemini) constrain generation to plain JSON today, not to an arbitrary custom format —
Tier 2 native-TSON constrained decoding is a real future direction, but TSON's stronger validation
could apply to *today's* LLM output the moment JSON documents can be read against a TSON schema at
all, with no decoder integration required.

- [ ] **A dedicated JSON parser (RFC 8259), not a restricted mode of the TSON lexer** — JSON's
  grammar (double-quote-only strings with a fixed, narrower escape set; no multi-line/unquoted
  tokens; a single unified number grammar) is different enough from TSON's own that toggling
  options on the existing `Lexer`/`TsonDataParser` isn't the right shape. A small, separate parser
  (naming per this project's own `Tson`-prefix convention — `JsonParser` is exactly the kind of
  bare name a consumer plausibly already has, e.g. Jackson/Gson/org.json all ship one, so this
  would be `TsonJsonParser`) that produces the *same* `DataValue`/`CoreValue` AST
  `TsonDataParser` does is what lets everything downstream — resolution, the compiled Class 2
  reader stack — be reused completely unchanged.
- [ ] **Numeric compatibility is one-directional and already free on read.** Every valid JSON
  number is a valid TSON number, so JSON numbers parse through `NumberGrammar`/`BaseTypeResolver`
  with no changes needed. The reverse isn't true: TSON-only extensions (hex/binary/octal
  `based-integer`s, `_` digit separators, `.nan`/`.inf` special float values) have no JSON
  equivalent — irrelevant for *reading* JSON (JSON simply can't produce them), but they matter for
  a JSON *writer* (see below) and for deciding whether a given TSON value is JSON-representable at
  all.
- [ ] **Object → record-vs-map disambiguation should already fall out of schema-position-driven
  dispatch** — `RecordBindReader`/`MapBindReader` already dispatch by the *schema's* own declared
  type at that position, not by data syntax, so a JSON `{}` reaching a record-typed field vs. a
  map-typed field should already resolve correctly through the existing machinery. Needs
  confirming against the new JSON front-end with real tests, not a new mechanism.
- [ ] **The real sharp edge: untagged unions.** Native TSON data can self-announce a union member
  via `!typeName value` (the type-ref *is* the discriminator — see "Target use case" above); bare
  JSON has no such mechanism. A `choice`/union-typed field reached from JSON input is unresolvable
  unless either (a) the schema simply avoids bare unions in JSON-facing positions — part of what
  makes a schema "restricted" enough to validate JSON at all, in the user's own phrasing — or (b) a
  discriminator-field convention is added (mirroring OpenAPI's `discriminator.propertyName` /
  JSON Schema's informal `discriminator` keyword), letting a schema author designate one field as
  the union-branch selector for JSON-only consumption. A real design decision, not just an
  implementation detail.
  - **The user's own proposed shape for (b), discussed 2026-07-29 while designing
    `ltr8-io-tson-test-suite`'s own sidecar-format schemas (see `BACKLOG.md`'s "Conformance test
    suite" section for the concrete case that surfaced it) — dependent typing at the meta-schema
    level.** Rather than the discriminator field's own *name* doing the selecting (a field group,
    §5.11, already resolves today but requires nesting the selected variant's data under a keyed
    sub-object) or a `choice`'s own member disambiguation, the discriminator field stays an ordinary
    `enum`, and each enum *member* carries an association to a type; a companion field elsewhere in
    the same record then resolves to whichever type the discriminator's current value names. `kind:
    record` on one field and `fields: [...]` on a plain, unrenamed sibling, with the schema itself
    enforcing that `fields`'s own real type follows `kind`'s value — the same flat, `kind`-plus-
    siblings shape every real sidecar (and plenty of real-world discriminated JSON) already uses, no
    wire-format change required to adopt it. Decouples "which type validates this value" from "what
    this field happens to be named," which is both what OpenAPI's `discriminator.mapping` /
    JSON Schema's informal `discriminator` keyword are themselves reaching for, and a closer match to
    how hand-written discriminated JSON is actually shaped in the wild than a field-group-based
    design would be. Unstarted: a genuine meta-kernel/meta.tn vocabulary addition (something in the
    shape of `enum`'s own constructor needing to carry a per-member type association, plus resolver
    support for reading a field's type dependently), not a `DefinitionResolver` bug fix — not
    designed or scoped beyond this note yet.
- [ ] **A "is this schema/type JSON-compatible" check**, surfaced during linking (or as a property
  on `TypeDefinition`) — so a schema author can tell which parts of their schema can validate JSON
  input at all, given the union-tagging and numeric-extension limits above, rather than discovering
  it only when a real JSON document fails to bind for an unclear reason.
- [ ] **Converting a JSON Schema to a TSON schema is a manual/guided process, not an automated
  converter** — JSON Schema's looser semantics (`additionalProperties`, `patternProperties`,
  `if`/`then`/`else`, non-normative `format`, `$ref` cycles) don't map 1:1 onto TSON's stricter
  model, so a faithful automatic converter is its own separate, much larger, questionable-value
  effort — not proposed here. What would genuinely help: a documented mapping reference (JSON
  Schema keyword → the nearest TSON construct, and which JSON Schema features have no honest TSON
  equivalent at all) to guide a human doing the conversion by hand. A human converting *from* JSON
  Schema naturally lands in the JSON-compatible subset described above anyway, since they have no
  reason to reach for TSON-only extensions their source schema never had.
- [ ] A JSON *writer* (TSON data → valid JSON text) is the natural write-direction companion to
  this whole section — tracked alongside the general schema-aware writer in `BACKLOG.md`'s "Write
  side" rather than designed separately here.
