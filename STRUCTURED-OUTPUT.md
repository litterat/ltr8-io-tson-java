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

**Tier 1 (validate + fast feedback) is the near-term target.** Tier 1.5 (validate-and-rewind at
structural boundaries — an in-writer closed loop) and Tier 2 (constrain every token) are larger
future efforts, captured here at the design level; Tier 1.5 turns out to share Tier 2's constraint
backend.

### Tier 1 — validate + fast feedback

- [x] **A structured `Diagnostic` value, not an exception** — landed as `io.ltr8.tson.compiler.Diagnostic`
  plus `TsonDiagnosticsReceiver`, the seam deciding where each one goes — `throwing()` (fail-fast),
  `collecting()` (a `TsonDiagnosticsCollector`), or a caller's own `void report(Diagnostic)`. Both
  library-level entry points exist: `Tson.validate(...)` returns every problem and never throws for a bad
  document, and `tson.treeReader()/objectReader().withDiagnostics(collector).read(...)` returns the
  (possibly partial) **value alongside** them — the shape a repair loop actually needs, which for a while
  no route offered. `tson-cli`'s `ValidateCommand` is now just a caller of the former. See
  `docs/readers-and-diagnostics.md` for the full design. Field by field, against the
  shape sketched below:
  - `path` — landed exactly as described, an RFC 6901 JSON Pointer accumulated by
    `TsonReadContext.field`/`index` as a read descends (that context is still the engine's cursor; it
    just no longer owns the throw-vs-collect decision).
  - `code` — landed as a real, closed `Diagnostic.Code` enum, not the exact starter set sketched
    below: `FIELD_REQUIRED`/`FIELD_FIXED`/`TYPE_MISMATCH`/`WRONG_ARITY`/`UNKNOWN_TYPE_REF`/
    `ATOM_CONSTRAINT_VIOLATION`
    are genuinely produced by a real reader today; `INTEGER_OUT_OF_RANGE`/`ENUM_MEMBER_NOT_RECOGNIZED`/
    `PATTERN_MISMATCH` never landed as their own codes — every atom-constraint violation maps to the
    one general `ATOM_CONSTRAINT_VIOLATION` instead, since `AtomValidationException` itself doesn't
    carry a structured code to route on yet (still open, see below); `UNRECOGNIZED_FIELD` now lands too,
    carrying the type's real field list in `expected` (§7.2's record closure — the one place `expected`
    already says what this bullet's item (4) asks for); `DUPLICATE_MAP_KEY` is produced for real, and
    `DUPLICATE_FIELD` joined it for the record half — §2.5/§2.6 make both MUST NOT, and §8.1 gives a
    conforming processor one severity, so there is no warn-shaped rule left to implement as anything else.
  - `message` — landed, **hand-composed at each call site, and staying that way**. Synthesizing it from
    `code` + params was reconsidered and dropped; see item (5) below for the reasoning.
  - `expected`/`actual` — landed, both carrying what they should. `actual` is the offending value
    (`99999`, `CANCELLED`, `(absent)`); `expected` is the **constraint that failed** (`<= 100`, `one of
    (PENDING, SHIPPED, DELIVERED)`, `at most 10 characters`, `an RFC 3339 date-time`), not the declared
    type's name, so a consumer never has to regex `message` to recover a bound or a member list.
    `AtomTypeException` carries it from the facet that rejected the value; its Javadoc fixes the
    vocabulary at six shapes, and `AtomTypeExceptionTest` pins every one.
  - `dataPosition` — landed, resolved via `TsonReadContext`'s own identity-keyed position table (a
    parser's `positions()`), not a fresh lexer-level `Position` thread. Infrastructure-level problems (an
    unresolvable `!!schema`, an unknown root type) now report through the same receiver rather than being
    thrown and re-wrapped, so they arrive in the list in document order like any other.
  - `schemaPosition` — landed, but narrower than sketched: declaration-level only (`TypeDefinition.position()`,
    from the positional-errors stripe), not the finer per-`RecordBody`/`ArrayBody`/`MapBody`/`TupleBody`/
    `ChoiceBody`/atom-constraint-family granularity this bullet originally proposed deciding between —
    a diagnostic for a specific field still shows its *enclosing type's* declared position, not the
    field's own line — one level coarser than the `schemaPointer` beside it, which *does* name the field
    (`/person/age`). Both plus `schemaId` are accumulated as one `SchemaLocation` as the read descends, so
    they always name the same document. Finer positions stay open if the grain turns out to matter.

- [x] **Alignment with existing conventions** — realized in `tson-cli`'s own `--output json` shape
  (`OutputFormat.renderJson`), which emits every `CliDiagnostic` field (`path`/`code`/`message`/
  `expected`/`actual`/`dataPosition`/`schemaPosition`) as flat JSON, no library-level `type`/`loc`/
  `msg`/`input`/`ctx` remapping built on top yet — a caller wanting exact Pydantic-v2-shaped output
  still does that translation themselves for now. Two real conventions this was aligned with:
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
  single ready-to-paste `message`, which is what actually gets dropped into a retry prompt in practice.
  - **All five are present today.** (1)–(4) are structured fields; `expected` carries the violated
    constraint itself, so "too large" does read as `<= 100` without parsing prose. Note what is *not* a gap
    any more: a repair loop can obtain the diagnostics without giving up the value, so a retry can be built
    from the partial result rather than from scratch.
  - **(5) originally asked for `message` to be a *rendering* of 1–4 rather than hand-authored per site. That
    was reconsidered and dropped**, and should not be re-derived from this list. The premise was that the
    sentence and the structured fields say the same thing twice and can therefore drift; they don't. The
    structured half carries the facts a consumer acts on, and *that* is what closes the "don't make the model
    parse prose" need — items (1)–(4) alone. `message` is for a person, and earns its keep doing what a
    template cannot: `annotation '@since' is written bare, which §6 treats as '@since:_', but 'since' does
    not admit the absent sentinel` cites the spec, and `… or an explicit type annotation` names the fix.
    Synthesis would degrade both. It is also not mechanically available: `code` does not determine the
    sentence (`TYPE_MISMATCH` alone spans six unrelated situations). The real failure mode was a site
    leaving `expected`/`actual` blank, which was three facade-level diagnostics and is now fixed and
    structurally prevented — see `docs/readers-and-diagnostics.md`.
  - **Ranked above all five for a model that authors *schemas* as well as data**: a wrong schema must not
    report `OK`. An unknown member in a refinement body is silently ignored today, so JSON-Schema
    vocabulary (`minimum`/`maximum`) compiles clean and enforces nothing — see `BACKLOG.md`'s "Validation
    correctness". No amount of diagnostic quality helps when no diagnostic is emitted.

- [x] **A TSON-native idea worth pursuing** — landed as `tson-cli`'s own `diagnostics.tn`
  (`diagnostic`/`diagnostic_code`/`validation_report`) plus `DiagnosticsSchema`'s compiled reader:
  `--output tson` writes a real `ValidationReport` via the schemaless `TsonObjectWriter`, and
  `OutputFormatTest` reads every rendered report straight back through `diagnostics.tn`'s own compiled
  `validation_report` reader, proving the
  emitted text is genuinely valid against a real TSON schema, not just structurally similar to one
  (`OutputFormatTest`'s own round-trip tests, including one exercising real, non-empty positions).

- [ ] **Schema-to-prompt rendering** (a supporting need, not the main focus) — a way to render a
  *resolved schema* back out as compact, LLM-promptable text (TSON's equivalent of
  `.model_json_schema()`), distinct from the "write side" (writing *data*, tracked in
  `BACKLOG.md`). Tier 1 implicitly assumes the model was told the target shape in the first place.

- [ ] The same fail-fast gap exists one layer down, in the lexer (`LexException`, unchecked,
  despite §8.1's "SHOULD continue processing to report multiple issues"). Worth deciding whether
  lexer/parser errors eventually feed the same `Diagnostic` model, or stay a separate concern.
  **It is now the floor under schema-parse recovery**: `TsonSchemaParser` reports every declaration's
  syntax error in one pass, but resynchronising means reading the very tokens that don't lex, so a
  schema whose first problem is an unterminated multi-line token still reports one and stops.
  Recovery here is also harder to justify than it was in the parser — the grammar hands the parser
  real resync points, where an unterminated token leaves no reliable boundary to resume on — so this
  wants a specific case that bites before it becomes work.
- [ ] `REQUIRED_FIXED`/`OPTIONAL_FIXED` identity-diagonal invariant (a restated fixed field's value
  must not change) — never checked.
- [ ] §9.1's numeric-literal length limit (SHOULD, default 4096 digits, DoS hardening) — not
  enforced anywhere.

### Tier 1.5 — validate-and-rewind at structural boundaries

Between Tier 1 (validate a whole response, then retry) and Tier 2 (constrain every token) sits a
third mode: **validate at each completed structure and rewind within a single generation.** When a
record's closing `}` arrives, run the compiled validator over just that record; on failure, emit the
diagnostics, roll the generation back to where the record started, and resample. An in-writer closed
loop — finer-grained than a whole-response retry, coarser (and far cheaper to build) than per-token
masking, and able to enforce the *semantic* constraints token-masking can't reach (cross-field,
disjointness, uniqueness).

**The crux is the interface, not the validation.** Rewinding the *writer* is trivial — the streaming
readers already produce `Diagnostic`s incrementally (Tier 1), and the writer's own state machine pops
back to a checkpoint for free. Rewinding the *model* means resetting its KV cache to the token where
the record started, and that exists only if you own the decode loop. So the mode forks by inference
stack:

- **Own inference** (open weights: vLLM, SGLang, llama.cpp, HF Transformers, TensorRT-LLM) — the true
  in-writer loop: snapshot KV state at record-start → generate the record → validate at `}` → on
  failure `rollback(mark)` and resample. Structurally this is speculative decoding with the schema
  validator as the verifier instead of a larger draft model.
- **Hosted chat APIs** (Anthropic/OpenAI/Gemini) — no KV rollback available; degrades to "stop early →
  re-prompt with the validated prefix as prefill + the diagnostic," paying prefill each retry (prompt
  caching softens it). Same logical loop, coarser grain, no in-writer rewind.

The "LLM interface" is therefore a **decoder plugin, not an API call** — three touch points: a
per-token logits hook, a structural-boundary callback, and a KV rollback primitive. Which engines
expose all three is the real gating fact.

**Prevention beats correction — and folds this tier into Tier 2's backend.** The fastest feedback is
never emitting the wrong token; rewind is inherently generate-wrong-then-redo. A large fraction of a
TSON schema compiles to a *token mask*, not just the grammar: integer ranges → digit-bound automaton,
enums → alternation, `regex` atoms → the regex, text length → counting, field presence/order →
structural FSM (all Tier 2). So the architecture is: **mask everything maskable (prevention, zero
rewind); use record-boundary-validate-and-rewind only for the residue** — cross-field constraints,
`choice` disjointness, uniqueness/referential integrity, the `REQUIRED_FIXED` identity-diagonal rule —
i.e. exactly what *can't* be a local automaton. That makes the rewind loop rare, and unifies the two
tiers: one schema→constraint backend, split by "is this constraint a local mask or not?" TSON is
unusually suited to this because the wire is explicitly typed — the writer always knows the expected
type at the current position (type-ref + schema position), so both "record complete, of what type" and
"which tokens are legal next" are decidable with no heuristics, unlike JSON.

Concrete items and decisions:

- [ ] **Engine-agnostic validating incremental writer + a `DecoderSession` SPI.** The library side
  streams tokens/events through the existing lexer→event→validator pipeline (reuse the streaming
  readers plus a **custom `TsonDiagnosticsReceiver`** — the push seam this needs already exists, and a
  receiver is handed each `Diagnostic` as the read finds it rather than in a batch at the end, which is
  exactly the incremental delivery this tier is built on), hands back a checkpoint handle at each
  structural boundary, and returns `List<Diagnostic>` on completion. The engine binding is a tiny SPI —
  `DecoderSession { mark(); rollback(mark); applyMask(tokenMask); }` — implemented by a vLLM/llama.cpp
  adapter in a *separate* module (or `examples/`), never in core, so TSON stays zero-runtime-dependency
  like the leaf modules already are. Gated on the `TsonValueWriter` / streaming schema-aware validation
  that's the top item in `BACKLOG.md`'s "Write side" — build that and Tier 1.5 is mostly wiring on top.
- [ ] **Checkpoint granularity = the scope that owns the constraint, not the offending field.** A
  cross-field rule lives at the record containing both fields, so the rewind point is that record's
  start; nested records mean nested checkpoints, and a failed inner record whose real cause is an
  earlier sibling needs *escalation* to an outer boundary. Needs a per-checkpoint retry budget +
  escalation policy to guarantee termination.
- [ ] **A naive rewind resamples the same wrong output.** Retrying with identical logits repeats the
  mistake, so the retry must shift the distribution: (a) tighten the mask so the bad token is
  impossible — best, but only for maskable constraints (another reason to fold as much as possible into
  masks); (b) inject the diagnostic into context so the model conditions on it — the general fallback;
  (c) raise temperature — weak alone.
- [ ] **Sequencing.** Ship the API-portable degradation (stop-early + re-prompt) first — it needs only
  the streaming validator, no decoder integration. The true in-writer loop targets open-weights
  inference and shares Tier 2's constraint backend. Prior art for the adapter half: Outlines, XGrammar,
  guidance, llama.cpp GBNF, vLLM guided decoding, plus the backtracking/speculative-decoding literature
  for the rollback.

### Tier 2 — constrain-during-generate (kept high-level)

- [ ] A schema→grammar/automaton compilation backend — a new compiler backend symmetric to
  `TsonTypeReader`, but targeting a CFG/regex/automaton instead of a reader. TSON's tagged unions
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

**This is Part 3's now, and `docs/json-encoding.md` holds the design.** [TSON-JSON]
(`spec/tson-part3-json.md`) is the normative JSON interoperability surface of the series, `tson-json` its
implementation, and `BACKLOG.md`'s "JSON encoding" section the engineering list. What stays here is the part
those do not cover: why this matters to *this* document's target use case, and the one design question still
genuinely open.

**It is arguably the most immediately practical path to Tier 1.** Every major LLM API's structured-output
feature — OpenAI, Anthropic, Gemini — constrains generation to plain JSON today, not to an arbitrary custom
format. Tier 2 native-TSON constrained decoding is a real future direction, but TSON's stronger validation
applies to *today's* model output the moment a JSON document can be read against a TSON schema, with no
decoder integration at all. It is the same reader the broader on-ramp needs — a JSON Schema or OpenAPI
contract converted to a TSON schema, validating documents already in flight unchanged — so the two use cases
fund one piece of work. `docs/json-encoding.md` states that goal and what it rules out of a converted schema.

**Two of this section's former items were wrong by Revision 35**, and both were load-bearing assumptions
rather than details:

- **A JSON object whose keys are not identifiers no longer "reads as a record and fails only against a
  schema".** That rested on §2.5 leaving `field-name` lexical, which Revision 35 withdrew: a field name is an
  identifier at every layer. `{"first name": 1}` is now refused where the name is read, not reported as
  `UNRECOGNIZED_FIELD` later — a much harder wall, and the largest single obstacle to the on-ramp.
  `docs/json-encoding.md` has the consequences; `SPEC-FEEDBACK.md` #5 states the proposal.
- **A JSON front end producing the same `DataValue`/`CoreValue` AST is not the shape.** `tson-json` is a
  stack of its own; `docs/json-encoding.md` and `CLAUDE.md`'s "Not yet implemented" carry the two
  disagreements that decide it. Duplicate member names and JSON `null`-as-absence, which that plan got right,
  are §3.1 and §7 now — the implementation's obligations rather than this document's open questions.

- [ ] **The real sharp edge: untagged unions, and two candidate answers.** Native TSON data
  self-announces a union member with `!typeName value` — the type-ref *is* the discriminator (see "Target use
  case" above) — and bare JSON has no such mechanism. [TSON-JSON] §8.2 lets a tag be omitted by exactly two
  routes and forbids extending them ("no trying variants in order", which is how JSON Schema validates a
  `oneOf`): a declared discriminator, or a disjoint choice whose variants are class-stable. So an untagged
  `oneOf` over object schemas — all brace class, hence non-disjoint — is unresolvable, and it is precisely
  what an LLM emitting against a converted contract produces.

  **Two designs are in play and neither supersedes the other yet.** `@discriminator` ([TSON-SCHEMA] §6,
  `meta.tn`) is the mechanism in place: an annotation naming a field pinned `REQUIRED_FIXED` and pairwise
  distinct in every variant, with the value→variant mapping derived from the pins rather than declared. It
  has never had a consumer — TSON text tags natively and never dispatches on a member — so this encoding is
  its first, and whether dispatch on a flat pinned field carries the cases JSON actually presents is an open
  question rather than a settled one. Against it stands the dependent-typing shape below, which reaches the
  same place through the enum rather than through an annotation. `BACKLOG.md` tracks the implementation;
  what belongs here is the pairing.
  - **The dependent-typing alternative, from designing
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
- [ ] **Converting a JSON Schema to a TSON schema is a manual/guided process, not an automated
  converter** — JSON Schema's looser semantics (`additionalProperties`, `patternProperties`,
  `if`/`then`/`else`, non-normative `format`, `$ref` cycles) don't map 1:1 onto TSON's stricter
  model, so a faithful automatic converter is its own separate, much larger, questionable-value
  effort — not proposed here. What would genuinely help: a documented mapping reference (JSON
  Schema keyword → the nearest TSON construct, and which JSON Schema features have no honest TSON
  equivalent at all) to guide a human doing the conversion by hand. What such a reference has to be honest
  about is the six places where the conversion costs the *producer* a change, not merely the author one:
  `docs/json-encoding.md` lists them, and a converter that lands outside that profile produces a schema whose
  documents have to be rewritten — the one outcome the exercise exists to avoid.
- [ ] A JSON *writer* (TSON data → valid JSON text) is the natural write-direction companion to
  this whole section — tracked alongside the general schema-aware writer in `BACKLOG.md`'s "Write
  side" rather than designed separately here.
