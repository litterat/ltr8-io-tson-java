# Structured output / LLM validation plan

The target-use-case plan for TSON in the LLM "structured output" ecosystem — TSON's stricter,
formally-specified schema/grammar design is well-suited to validating LLM-generated data, both as a
fast, localized-feedback validator (pydantic/pydantic-ai/Instructor-style) and, longer term, as a
constrained-decoding grammar source (outlines/xgrammar/guidance-style). See `BACKLOG.md` for the
general engineering backlog this document doesn't cover, and the `design/` notes for technical detail
behind specific items.

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
discriminator, unlike untagged `oneOf`), field presence answered one question per mark (`name?:` may be
omitted, `type?` admits null, `~`/`=` default or pin), and a compile-once/read-many
architecture (`TsonCompiledSchema`/`TsonCompiledSchemaRegistry`) that already fits a hot request loop.
Recursive/deeply-nested schemas remain a genuinely shared hard problem either way — not something
TSON magically avoids.

**Tier 1 (validate + fast feedback) is the near-term target.** Tier 1.5 (validate-and-rewind at
structural boundaries — an in-writer closed loop) and Tier 2 (constrain every token) are larger
future efforts, captured here at the design level; Tier 1.5 turns out to share Tier 2's constraint
backend.

### Tier 1 — validate + fast feedback

**What a retry loop gets today.** `Tson.validate(...)` returns every problem and never throws for a bad
document; `treeReader()`/`objectReader().withDiagnostics(receiver).read(...)` returns the value for a clean
document and `null` beside every problem for one that is not — all-or-nothing in every mode and both encodings,
since a partial value cannot say which of its parts to trust. A retry is built from the document the model sent
and the diagnostics' paths into it. Each `Diagnostic` (`design/diagnostic-model.md`) carries, in the order an LLM
retry loop needs them:

1. **Where** — `path`, an RFC 6901 JSON Pointer into the data, plus `dataPosition`, so the model never re-scans
   its own output. The schema end is one `SchemaLocation`: `schemaId`, a `schemaPointer` naming the field, and a
   `schemaPosition` at declaration grain (a field's diagnostic shows its enclosing type's line).
2. **What kind** — `code`, a closed `Diagnostic.Code` enum, so a system prompt or few-shot pattern can generalise
   a fix instead of parsing prose. Every atom-constraint violation is `ATOM_CONSTRAINT_VIOLATION`; there are no
   per-facet codes, the facet being named by `expected`.
3. **What was there** — `actual`: `99999`, `CANCELLED`, `(absent)`.
4. **What was expected** — `expected` is the *constraint that failed*, not the declared type's name: `<= 100`,
   `one of (PENDING, SHIPPED, DELIVERED)`, `at most 10 characters`. `AtomTypeException`'s Javadoc fixes the
   vocabulary at six shapes.
5. **A ready-to-paste `message`**, hand-composed at each site. It is deliberately not synthesised from 1–4: the
   structured fields already carry what a consumer acts on, and the sentence earns its keep doing what a
   template cannot — citing the spec, naming the fix. `code` does not determine the sentence either
   (`TYPE_MISMATCH` alone spans six situations).

**A wrong schema does not report `OK`.** A model that authors schemas as well as data reaches for JSON Schema's
vocabulary, and `!integer ^ { minimum: 1 }` is refused at load as an unknown member of `integer_type`
(`DefinitionResolverTest`), by the same rule that refuses one in data.

**Output shapes.** `tson validate --output json` writes each diagnostic flat, in `snake_case` with absences
omitted (`path`, `code`, `message`, `expected`, `actual`, `data_position`, `schema_id`, `schema_pointer`,
`schema_position`). It maps directly onto Pydantic v2's `ValidationError.errors()` — `code`↔`type`, `path`↔`loc`,
`message`↔`msg`, `actual`↔`input`, `expected`↔`ctx` — the shape pydantic-ai, Instructor and LangChain-style
output fixers feed back to a model; a caller wanting it exactly does that renaming themselves. `path` follows
JSON Schema 2020-12's output format (`instanceLocation`), so tooling that speaks it reads `path` untranslated.
`--output tson` writes a `validation_report` that `tson-cli`'s own `diagnostics.tn` declares, and
`OutputFormatTest` reads every rendered report back through that schema's compiled reader.

A validator in a hot request loop reads untrusted model output, so [TSON-DATA] §9.1's resource limits matter
here; they are `BACKLOG.md`'s ("The rest of [TSON-DATA] §9.1's resource limits").

- [ ] **Schema-to-prompt rendering** (a supporting need, not the main focus) — a way to render a
  *resolved schema* back out as compact, LLM-promptable text (TSON's equivalent of
  `.model_json_schema()`). Tier 1 implicitly assumes the model was told the target shape in the first place.
- [ ] **Finer `schemaPosition` grain**, if it turns out to matter: per field rather than per declaration, so the
  position agrees with the `schemaPointer` beside it.
- [ ] **Lexer errors are fail-fast** (`LexException`, unchecked), despite §8.1's "SHOULD continue processing to
  report multiple issues". It is the floor under schema-parse recovery: `TsonSchemaParser` reports every
  declaration's syntax error in one pass, but a schema whose first problem is an unterminated multi-line token
  reports one and stops. Recovery is harder to justify here than in the parser — an unterminated token leaves
  no reliable boundary to resume on — so this wants a specific case that bites before it becomes work.
- [ ] **The FIXED identity-diagonal invariant** (a restated fixed field's value must
  not change) is never checked. `design/schema-resolution.md` records it as a deferred design question.

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
`choice` disjointness, uniqueness/referential integrity, the FIXED identity-diagonal rule —
i.e. exactly what *can't* be a local automaton. That makes the rewind loop rare, and unifies the two
tiers: one schema→constraint backend, split by "is this constraint a local mask or not?" TSON is
unusually suited to this because the wire is explicitly typed — the writer always knows the expected
type at the current position (type-ref + schema position), so both "record complete, of what type" and
"which tokens are legal next" are decidable with no heuristics, unlike JSON.

Concrete items and decisions:

- [ ] **Engine-agnostic validating incremental writer + a `DecoderSession` SPI.** The library side
  streams tokens/events through the existing lexer→event→validator pipeline (reuse the streaming
  readers plus a **custom `DiagnosticsReceiver`** — the push seam this needs already exists, and a
  receiver is handed each `Diagnostic` as the read finds it rather than in a batch at the end, which is
  exactly the incremental delivery this tier is built on), hands back a checkpoint handle at each
  structural boundary, and returns `List<Diagnostic>` on completion. The engine binding is a tiny SPI —
  `DecoderSession { mark(); rollback(mark); applyMask(tokenMask); }` — implemented by a vLLM/llama.cpp
  adapter in a *separate* module (or `examples/`), never in core, so TSON stays zero-runtime-dependency
  like the leaf modules already are. Gated on the `TsonValueWriter` / streaming schema-aware validation
  in `BACKLOG.md`'s "Write side" — build that and Tier 1.5 is mostly wiring on top.
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

**This is Part 3's, and `design/json-encoding.md` holds the design.** [TSON-JSON] (`spec/tson-part3-json.md`) is
the normative JSON interoperability surface of the series, `tson-json` its implementation — a stack of its own,
not a front end over TSON's event stream — and `BACKLOG.md`'s "JSON encoding" section the engineering list. What
stays here is why it matters to this document's target use case, and the two walls it runs into.

**It is the most immediately practical path to Tier 1.** Every major LLM API's structured-output feature —
OpenAI, Anthropic, Gemini — constrains generation to plain JSON, not to an arbitrary custom format. TSON's
stronger validation applies to *today's* model output by reading a JSON document against a TSON schema, with no
decoder integration at all. It is the same reader the broader on-ramp needs — a JSON Schema or OpenAPI contract
converted to a TSON schema, validating documents already in flight unchanged — so the two use cases fund one
piece of work. `design/json-encoding.md` states that goal and what it rules out of a converted schema.

**A JSON member name that is not an identifier is refused where it is read.** A field name is an identifier at
every layer ([TSON-DATA] §2.5), so `{"first name": 1}` fails before any schema is consulted — the largest single
obstacle to the on-ramp. `SPEC-FEEDBACK.md` #5 states the proposal.

**Untagged unions.** Native TSON data announces a union member with `!typeName value` — the type-ref *is* the
discriminator — and bare JSON has no such mechanism. [TSON-JSON] §8.2 lets a tag be omitted by exactly two
routes and forbids extending them ("no trying variants in order", which is how JSON Schema validates a `oneOf`):
a declared discriminator, or a disjoint choice whose variants are class-stable. So:

- **A `oneOf` that carries a discriminator converts to a sealed record family**, not a choice: a `@sealed` base
  whose `@discriminator` fields each subtype pins (`= v`) to a distinct value, dispatched on the
  object's own members ([TSON-JSON] §6.1.5, `DispatchMemberReader`). That is the flat `kind`-plus-siblings shape
  hand-written discriminated JSON already has, and what OpenAPI's `discriminator.mapping` reaches for. The
  record-family placement is this implementation's, proposed against the spec's choice-level `@discriminator`
  in `SPEC-FEEDBACK.md` #10 and #11.
- **An untagged `oneOf` over object schemas has no answer**, and it is what an LLM emitting against a converted
  contract produces. All its variants are brace class, hence non-disjoint, and nothing in the document selects
  one: a conversion finds a discriminator or reports.
- **The shape not taken: an enum member carrying a per-member type**, with a sibling field typed by the
  discriminator's current value. It reaches the same wire shape, but only by letting one field's value type
  another — dependent typing in the kernel. The sealed family gets there with every field's type static within
  its subtype: the pinned values select the subtype, and the subtype declares the siblings.

- [ ] **A JSON Schema → TSON mapping reference, not an automated converter.** JSON Schema's looser semantics
  (`additionalProperties`, `patternProperties`, `if`/`then`/`else`, non-normative `format`, `$ref` cycles) don't
  map 1:1 onto TSON's stricter model, so a faithful automatic converter is a separate, much larger,
  questionable-value effort. What would help is a documented mapping — JSON Schema keyword → the nearest TSON
  construct, and which features have no honest TSON equivalent — to guide a human doing the conversion. It has
  to be honest about the places where conversion costs the *producer* a change, not merely the author one:
  `design/json-encoding.md` lists them, and a conversion that lands outside that profile produces a schema whose
  documents have to be rewritten — the one outcome the exercise exists to avoid.
