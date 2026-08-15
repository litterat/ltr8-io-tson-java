# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

---

## Resolution & linking generality

Every real schema resolved so far (meta-kernel, meta.tn, core.tn, and hand-built test fixtures)
happens to fit a narrow shape this pipeline already handles — declared in dependency order, with
callers hand-sequencing registration themselves. These items are what's missing for the *general*,
spec-required case, found by re-auditing Part 2 against the current source rather than CLAUDE.md's
own prose (which had gone stale on at least one of them):

- [ ] **Automatic reference-closure resolution and import-cycle detection** ([TSON-DATA] §2.2.3,
  [TSON-SCHEMA] §3.4.1) — no code collects a schema's transitive `!!meta`/`!!import` closure,
  topologically orders it, and resolves it dependencies-first; every caller (including this
  session's own `TinySchemaImportsCoreTn1Test`) has to already know and hand-sequence the correct
  registration order itself. A real import cycle is only caught incidentally today, as an opaque
  "not registered" error from `TsonSchemaLinker.mergeImports` — never with the spec's own required
  "import cycle" diagnostic naming the actual cycle path. Distinct from what
  `TsonCompiledMetaRegistry.withStandardLibrary` already does, which is scoped to just the three bundled
  schemas in a known order, not a general algorithm.
- [ ] **A choice type doesn't resolve at all** ([TSON-SCHEMA] §5.4) — the gap underneath every other choice
  item here, and not previously listed. `ChoiceBody` is **never constructed anywhere in main code**:
  `DefinitionResolver` recognizes `ChoiceRef` only far enough to *reject* it at a supertype position, and
  neither the `(a | b)` form nor `!choice { variants: [...] }` produces an entry. So no schema resolved from
  source has a choice entry at all.
  - The consequence is that two finished pieces of machinery have no live input. `ChoiceReader` is a
    complete factory — dispatching on `!typeName` via `NamedDispatchReader`, precomputing §5.4's untagged
    structural recovery where every variant occupies a distinct base-type class — and nothing can reach it.
    `ChoiceDisjointness` likewise derives `disjoint` for choice entries the linker never sees; both are
    exercised only by tests that hand-build a `ChoiceBody`.
  - So the two sub-items below are **not** the remaining work on choice, and were misleading as written:
    they refine a fact that is currently computed for nothing. Resolution has to come first.
  - Blocks the conformance-suite sidecar reshape too (see "Conformance test suite" below, where `!choice`
    is cited twice as the thing that would make a precise `outcome`-correlated shape expressible).
- **Choice disjointness derivation and untagged reading** ([TSON-SCHEMA] §5.4, §8.1) — the derivation code
  exists and is unit-tested, but per the item above it has no live producer until choice resolves:
  - [ ] **Two §5.4 "MAY" cases left absent:** record-set disjointness under composition, and pattern
    disjointness over `regex`-constrained atoms. The view on how far to go (recorded so it isn't
    relitigated): the `disjoint` *fact* is encoding-independent, but §5.4's Tagging rule makes TSON text
    recover a variant only by the single §4 base-type-resolution pass — class-level (null/boolean/
    number/string), never a type-directed inspection — so **two variants of the same base-type class are
    never TSON-text-discriminable, regardless of value-set disjointness** (`(email | uri)` is the spec's
    example). Same-family numeric-bound and regex-pattern disjointness both produce a same-base-class
    `true`, so neither buys TSON-text *untagged reading* anything; the cheap different-class rules
    (family/kind) are the only scalar disjointness the reader can use. Pattern disjointness is therefore
    **not** wired into the reading-path derivation — but it is *safe* to compute and belongs in the
    `@disjoint` checker below (an earlier "an innocuous pattern edit silently flips tag-requiredness"
    worry is void: schemas are immutable and hash-pinned, so a revision is a new content identity —
    existing data pins the old, still-disjoint one, and a disjointness-losing revision is a load-time
    error under `@disjoint`, never a silent flip). **The dependency-inversion seam this used to need is no
    longer required**: `ChoiceDisjointness` moved into `tson-compiler` with the linker, and that module
    already requires `tson-regex`, so `isDisjointFrom` is a direct call rather than an oracle injected from
    outside. See `SPEC-FEEDBACK.md` #23 for the load-bearing ambiguity underneath all of this.
  - [ ] **The `@disjoint` assertion check** — an author's `@disjoint` marker checked against the derived
    fact: proved (silent), refuted / provably-not (resolver error), unprovable (warning), absent (no
    check). This is where exact regex-pattern disjointness (`isDisjointFrom`, called directly — see above)
    pays off — turning an otherwise-"unprovable" pattern choice into a proved-or-refuted one. **No longer
    blocked**: a declaration's annotations now reach the resolved model, on `TypeDefinition` when written
    after `=>` and on the schema map's key when written before the name, so the linker can read a
    `@disjoint` marker where `disjoint` is derived. Nothing in the bundled schemas actually writes one yet,
    so the checker's own fixtures have to supply it.
- [ ] **§5.10 substitution into a template *body*.** Half of template application works. A template that
  refines a constructor — `array_ranged => <T, MIN, MAX> array<T> ^ { min_items: = MIN  max_items: = MAX }`,
  and therefore §5.3's sized sugar — instantiates per §8.2, because its resolved vocabulary carries the same
  `value_param` channels a constructor's does, so the arguments route by the same mechanism and the binding
  record is headed at the nearest `~` constructor. What is left is a template whose parameter appears as a
  **field type** (`box => <T> { v: T }`): instantiating that means rewriting the body with `T` replaced,
  which is substitution proper. Rejected at the application site today
  (`SchemaDesugarer.rejectIfTemplateApplication`), so it fails where it is written rather than at a read
  that may never happen.
  - Belongs in the same phase, as another `TemplateInstance`-producing path: the body comes from the
    template's own AST with parameters replaced, rather than from a vocabulary.
  - **Requires a termination guard.** Non-regular (polymorphic) recursion like
    `weird => <T> { next: weird<[T]>? }` / `use => weird<text>` grows its argument every level
    (`text` → `[text]` → `[[text]]` …). Every instantiation is structurally distinct, so the
    dedup-by-derived-name never fires and the walk never terminates. Distinct from `SPEC-FEEDBACK.md` #25
    (non-*productive* recursion — no finite *data* model): this is no finite *type* model.
  - **The rejection is narrower than the feature.** A head declared by the current document or present in
    the structure namespace is caught; a template declared by an **`!!import`** still slips through to the
    old read-time failure, because catching it needs the imported entries' resolved definitions rather
    than the name set the phase currently takes. Worth closing when substitution lands, if not before.
  - The scoping questions around generic heads — how precedence is worded, silent cross-namespace
    shadowing, whether parameters are eligible at a head, and when the `constructor: true` gate applies —
    are `SPEC-FEEDBACK.md` #28, which also records the answers this implementation currently gives.
- [ ] **The rest of §8.2's deferred value-level checks.** Materialisation "runs the value-level checks that
  open bounds deferred: family coherence rules whose operands were parameters". The array family's
  `min_items <= max_items` is done, in `SchemaDesugarer.checkBounds`, because it is the only rule the
  kernel's own templates route parameters into and the sugar (`[T; 5..3]`) is how an author reaches it. The
  kin §8.2 gestures at — "bounds within a width-derived range, and their kin" — belong with the constraint
  families that own them, next to `AtomNarrowing`, not in a syntax rewrite. Doing that properly probably
  means the check moves out of the desugarer entirely and `checkBounds` goes with it.
- [ ] **Resolved-form ingest** ([TSON-SCHEMA] §8.1/§10.1) — bringing an already-resolved
  `!type_definition` document into the library (not source text), with its own integrity checks:
  `subtypes`/`disjoint` recomputed and verified, the closed-entry parameter-free rule reverified, an
  instantiation entry checked against its own `source` by recomputation, a construction's binding
  record checked for parameter-slot agreement with its `source` application. Entirely unimplemented
  — "ingest" doesn't appear anywhere in the codebase. Note it would introduce a *second* way to build a
  `TsonSchema` — bound from a document rather than resolved from source — and the two would have to agree,
  including on where a declaration's annotations land (the name's on the map key, the definition's on the
  entry). Lower priority than the rest of this section: the spec marks this path explicitly **optional**
  ("MAY implement ingest"), not a MUST.

## Validation correctness — cases that report OK when they shouldn't

Everything else in this file is work not done. These are places the library actively gives a **wrong
verdict**, which is a different and worse category: a gap costs a retry, a false OK ships a validator that
doesn't validate. Found by driving the CLI the way the LLM use case in `STRUCTURED-OUTPUT.md` would.

Record closure is done (both the unknown member in a refinement body and the unknown field in data — one
discard in `RecordAbstractReader.readFields`, and §7.2 settles it as a MUST rather than the policy choice
this list once called it). What is left:

- [ ] **A stray field in *bind* mode collapses the whole record to `null`.** `RecordBindReader` returns
  `null` whenever `ctx.reported()` moved while it was reading, because a bound constructor can't take a
  null argument for a primitive-typed parameter. That reasoning doesn't hold for a closure violation —
  every declared field is intact and the object is constructible — but the counter is per *read*, not per
  record, so any diagnostic anywhere below also collapses every record above it. Fixing it properly means
  distinguishing "one of my own fields failed" from "something in my subtree did", which is a change to
  the checkpoint idiom, not to closure. Tree mode keeps the value already.
- [ ] **`DUPLICATE_MAP_KEY` is on the `Code` enum but never produced.** Not the same shape as closure was:
  the parser resolves §2.6's "last value wins" before any reader sees a map, so detecting it needs a
  different mechanism rather than a report at an existing branch.
- [ ] **The TEXT output drops `dataPosition`.** `OutputFormat.renderText` prints a data diagnostic's RFC
  6901 path and falls back to the schema pointer/position only when that path is empty — so a human sees
  no line:column for a value error, while `--output json` carries it. [TSON-DATA] §8.1 requires source
  position in all error reports; the data is already on the `Diagnostic` and only the renderer drops it.
- [ ] **`tson init-example <dir>` exits 70 on a directory that doesn't exist.** `InitCommand.run` lets a
  `NoSuchFileException` escape as an `UncheckedIOException`, so the "this is a bug in tson" banner and a
  stack trace print for what is either a usage error or a directory the command should create. It is the
  first command a new user runs.

## Remaining built-in types

- [ ] `cidr4`/`cidr6`/`unknown` — no compiled-parser factory yet (`ValueReaderFactoryRegistry` registers
  these constructors to `ErrorReader`). Pinned down exactly by
  `CoreSchemaImportTest.exactlyTheThreeUndocumentedAtomConstructorsCompileToErrorReaders`. `mac` and
  `email` left this set when their parsers landed; `cidr4`/`cidr6` compose on the existing
  `Ipv4Parser`/`Ipv6Parser` and are the natural next pair.
- [ ] `uri_type`/`regex_type` — don't bind correctly in object-binding mode. Their RFC-citation
  field is nested inside `specification: AtomSpecification` rather than flat, so it never receives
  a schema-composed default the way `email_type`'s own flat `spec` field does.
- [ ] `extern` ([TSON-SCHEMA] §7.8) — materially bigger than the two items above, which just need
  an ordinary atom parser or a binding fix. `Extern` (`schema.meta`) is a record-only placeholder with no
  parsing/validation behavior at all (its own Javadoc says so explicitly: "not to add real
  cross-schema reference resolution"); the real mechanism — a value at an extern-matched position
  carrying its own scoped `!!schema` plus a mandatory `!type` tag, switching schema scope
  mid-document — doesn't exist anywhere in the reader stack.

## Remaining Part 2 resolution gaps

- [ ] Generic type-refs whose arguments are not simple names. A non-simple argument (`weird<[T]>`) is
  rejected outright ("only simple type arguments are resolved so far") — `SchemaDesugarer` reduces an
  argument that is itself an application to the name it was hoisted to, so ordinary nesting
  (`map<text, [integer]>`) works; what is left is an argument that does not reduce to a plain name. The
  `weird<[T]>` shape additionally sits inside a *parameterized* declaration, which the phase skips
  entirely. Lifting this is part of the template-application item above, including its termination guard.
- [ ] Closed-entry parameter-free check (§5.10) — nothing validates that an entry with an empty
  `parameters` list truly contains no parameter references (`value_param` members, or a reference
  name resolving to a parameter) anywhere in its body, at any depth. Distinct from `value_param`
  *substitution* (tracked in `STRUCTURED-OUTPUT.md`) — this is a rejection rule for a malformed
  "closed" entry, not the substitution mechanism itself.

- [ ] **A parameterized supertype reference** (`vip => <T> customer & box<T> & { ... }`, §5.8's
  "Parameterized references"). The only genuine gap left in the composition path, and it is not independent:
  §5.8 says the applied form's arguments reach "the absorbed fields, which carry the parameters through
  ordinary type channels", so composing with `box<T>` means substituting into a record template's body —
  the §5.10 item above, termination guard and all. Worth doing together, not before.
- [ ] **A field/element type that is not a simple name, a generic application, or an inline array.**
  `resolveFieldType`'s catch-all ("only simple (non-generic) type-refs, generic applications of one, and
  inline arrays of one are resolved so far"). Overlaps the template-substitution item above, but is
  reachable without templates.

Only genuine gaps are listed above — a throw that means "your schema is wrong" is not one, and
`DefinitionResolver`'s have been separated out already. The remaining classification work is tracked under
"Schema-side diagnostics", which carries the current census.

- [ ] **A FIXED-value contradiction reports as `ATOM_CONSTRAINT_VIOLATION`**, which is the closest code in
  the closed vocabulary and not an accurate one — a document contradicting `field: type = value` (§5.2) has
  violated a schema-level field constraint, not an atom's own parsing contract. Wants its own code, together
  with the "fine-grained atom codes" note under Diagnostics.

## Schema-side diagnostics

Resolution and linking report every independent problem in one pass through a `TsonDiagnosticsReceiver`
(issue #3, PR #4), whether reached by `tson compile`/`Tson.validateSchema` or by a *data* read whose
`!!schema` names a schema that doesn't resolve — both give the same account of the same broken schema.
`CLAUDE.md`'s "Schema-side diagnostics" section describes the shape and the decisions behind it. What is
left:

- [ ] **Desugaring is still fail-fast**, so a sugar-form error (`[T; 5..3]`, an unsupported template
  application) aborts before resolution reports anything. The same per-declaration treatment applies and
  `SchemaDesugarer` has the declaration in hand, so this is the cheapest remaining piece.
- [ ] **The read path carries `schemaPosition` but not `schemaId`/`schemaPointer`** — a reader knows the
  declaration position it stamped, not which entry of which schema produced it, so a value error reports
  `110:3:4858` with nothing saying that is core.tn's line for `int32`. Threading the compiled schema's
  identity down the reader stack is what closes it.
- [ ] **Finish classifying the throw sites outside `DefinitionResolver`.** Census across the schema pipeline
  (parser, desugarer, resolvers, linker, compiler, registries, `TsonCanonicalIdentity`): 13
  `UnsupportedOperationException` (*library gaps*), 52 `TsonSchemaValidationException` (author errors —
  though 11 are `TsonCanonicalIdentity` `!!id` string checks, inherently positionless), 16
  `IllegalStateException` (invariants/faults), 3 `TsonParseException` (schema syntax, already positioned).
  Only a validation exception is collected into a `Diagnostic`, so a misfiled author error both aborts the
  run and tells the author their correct schema is this library's fault. The test, from Swift's treatment of
  `expression_too_complex`: **a schema error's verdict doesn't change when the library improves; a gap's
  does.** `DefinitionResolver`'s `!`-position sites and `TsonSchemaSource.registeredOnly` are done;
  everywhere else still wants the pass.
- [ ] **A schema *parse* error is still fail-fast, and names a token class rather than a construct.**
  Everything downstream of parsing now reports many problems; parsing itself reports one and stops, so an
  author fixes a syntax error, re-runs, and meets the next. The message is also pitched at the wrong level
  for the person reading it: an atom refinement written inline at a field position (`quantity: !integer ^ {
  min: 1 }`, a natural thing to try) gives

  ```
  [VALIDATION_ERROR] expected UNQUOTED (a type reference), found '!' (BANG) at line 8, column 15
  ```

  which is accurate about tokens and silent about the fix — hoist the refinement to its own declaration and
  reference it. Naming the construct that *is* admissible at the failing position would turn a guess into a
  one-shot correction. Related: `STRUCTURED-OUTPUT.md` tracks the same fail-fast gap one layer further down,
  in the lexer.
- Granularity ceiling to know before starting any of these: positions are **per declaration**, from the
  declaration's own name token. Sub-declaration positions (which field, which supertype) do not exist and
  would be their own parser work — visible today as a diagnostic pointing at `/my_type` when the problem is
  one of its fields.

## Diagnostic quality for machine consumers

- [ ] **`expected` names the type, not the constraint that failed**, so the structured fields carry strictly
  less than the prose does — the opposite of the intent. Measured on real output:

  | field | value |
  |---|---|
  | `message` | `'99999' is greater than the maximum 100` |
  | `expected` | `a value satisfying quantity_t` |
  | `actual` | `99999` |

  Same for an enum: the message lists `[PENDING, SHIPPED, DELIVERED]`, `expected` says `a value satisfying
  status`. A consumer reading the structured fields has to fall back to regexing `message` to recover the
  bound or the member list, which is exactly what `Diagnostic` exists to avoid, and what the JDK's own
  `javax.tools.Diagnostic` is criticised for in issue #3's research notes.
  `STRUCTURED-OUTPUT.md`'s Tier 1 asks for precisely this — *"turns 'too large' into 'must be ≤ 100'"* — and
  records the field as landed, which is optimistic: the field exists, the content doesn't.
  - Cheapest useful version: have each atom's constraint check populate `expected` from the facet it
    violated. That is where the bound already is; nothing needs to be plumbed to reach it.
  - The fuller version is the standing `message`-synthesis item (compose the sentence *from* `code` +
    params rather than hand-writing it per call site), which would make the two consistent by construction
    instead of by discipline.

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has only the two schemaless writers and is
missing most of the mirror.

- [ ] **No schema-aware (Class 2) writer — `TsonValueWriter`.** Only the schemaless `TsonObjectWriter`
  (object → TSON) and `TsonTreeWriter` (`TsonValue` → TSON) exist, both with documented lossy spots
  (integer width, tuple-ness). A writer symmetric to the
  compiled reader stack (`TsonSchemaCompiler`/`TsonTypeReader`) — checking output against a TSON schema
  and reporting what's wrong — is a whole missing half of the pipeline, and the natural home for
  round-tripping or producing guaranteed-conformant documents.
- [ ] **Writers are fail-fast only, no diagnostics.** They throw `TsonWriteException` at the first
  problem, with nothing symmetric to the read side's `TsonDiagnosticsReceiver`. The `TsonValueWriter`
  above especially needs it, to report every schema violation in one pass the way the reader does — and
  the seam already exists and is write-direction-agnostic (`Diagnostic` carries a data path and both
  positions; nothing about `void report(Diagnostic)` assumes reading), so this is a matter of threading a
  receiver through the emitter, not designing a second error model.
- [ ] **Writers materialize a `String`, not a stream.** `toTson(...)` returns the whole document in
  memory — asymmetric with the readers, which accept an `InputStream` and never fully buffer. Writers
  should also accept an `OutputStream`/`Writer`/`Appendable` and emit incrementally; the internal
  `TsonDataEmitter` already builds into a `StringBuilder` and could target any `Appendable` instead.
  (Same streaming theme as `Tson.validate(InputStream)` buffering the whole document to a `String`.)
- [ ] **No public push/event writer.** The read side exposes a pull `TsonDataStream` (→ `TsonEvent`);
  the only emitter, `TsonDataEmitter`, is internal. A public event-driven writer would let a caller emit
  TSON without first building a whole tree or object — the write-direction peer of `TsonDataStream`.
- [ ] A JSON writer (TSON data → valid JSON text) — the write-direction companion to
  `STRUCTURED-OUTPUT.md`'s "JSON compatibility" section, tracked here alongside the general writer
  since it's the same underlying gap (no schema-aware writer exists at all yet).

## Conformance test suite

- [ ] Build out `ltr8-io-tson-test-suite` well beyond its current 110 vectors (grown from the 38 this
  note originally cited — it's picked up a fourth `vocabulary` bucket alongside `lexer`/`parser`/
  `resolver` since). Still Part 1 (lexer/parser/§5 vocabulary) only — Part 2 (resolution, linking,
  compilation) has no conformance-suite coverage at all yet, only this repo's own unit/integration
  tests.
- [ ] **Run the JSON front-end against the established JSON Parsing Test Suite** when it lands (the
  `TsonJsonParser` tracked in `STRUCTURED-OUTPUT.md`). JEP 540 commits to exactly this for the JDK's own
  parser — its own unit tests *plus* that external corpus, "which contains numerous edge-case inputs" —
  and the reasoning is the same one this repo's sibling suite exists for: an external, language-agnostic
  fixture set catches drift a self-authored suite agrees with. Cheap, since the corpus is pass/fail on
  parse and the front-end's whole job is RFC 8259 conformance.

- [ ] **Retrofit the ~110 existing sidecars with a real `!!schema` directive** pointing at the new
  per-layer schemas above, and fix whatever real shape mismatches that validation surfaces —
  explicitly *not* done in the same pass that wrote the schemas (see the sibling README's own note
  on this), so today the schemas are resolver-verified documentation, not live validation of the
  actual fixtures. **Worth doing sooner rather than later, per the user's own explicit reasoning**
  (2026-07-29): only ~110 files exist today, cheap to migrate; that stops being true once the suite
  grows into the thousands, so if the wire format itself is going to change (see the next item), now
  is the cheap moment to do it, not after the corpus is large.
- [ ] **A precise, `outcome`/`kind`-correlated sidecar shape needs a real design decision first,
  not just a retrofit** — the flat, all-optional modeling these schemas use today (`core_value`'s
  `kind` plus every variant's fields all `OPTIONAL` side by side) doesn't enforce that `kind: token`
  implies `form`/`text` present and `fields`/`entries`/`elements` absent, and so on. Three ways to
  actually get that enforcement, discussed with the user 2026-07-29, in order of how much the real
  wire format would have to change:
  - `!choice { variants: [...] }` — blocked on "A choice type doesn't resolve at all" under
    "Resolution & linking generality" above. Even once that lands, a schema built on it needs a
    `!typeName` tag unless every variant occupies a distinct base-type class, which `ChoiceReader`
    already precomputes — so `core_value`'s variants, all records, would still be tagged on the wire.
  - **Field groups** (§5.11) — confirmed empirically to work today, including with a record-typed
    member (`core_value => { ( token: core_value_token | record: core_value_record | ... ) }`,
    resolves cleanly, each member individually `OPTIONAL`, mutual exclusivity captured in the
    entry's own `groups`). The real cost: the group member's own *name* is what discriminates, so
    each variant's fields would have to move under a keyed sub-object (`{ token: { form: ...
    text: ... } }`) instead of today's flat `{ kind: token form: ... text: ... }` — a genuine wire
    format break for every existing sidecar, not just a schema change.
  - **Dependent typing at the meta-schema level — the user's own proposed direction, already on
    their own "not yet" list before this discussion, and the one that best fits this specific case**:
    `kind` stays exactly as it is today (a plain enum field, flat, no wire change at all) — the new
    mechanism is a meta-schema-level association from an enum's own *member* to a type, so a
    companion field's actual type is resolved from whichever member `kind` currently holds, rather
    than from the field's own static declared type or its own name. Lets a schema designer decouple
    "which type validates this value" from "what this field happens to be named" — relevant well
    beyond this one schema, including for JSON-facing discriminated unions (see
    `STRUCTURED-OUTPUT.md`'s own "The real sharp edge: untagged unions" item, extended with this
    same mechanism). This is real, unstarted design work at the meta-kernel/meta.tn vocabulary level,
    not a `DefinitionResolver` bug fix — tracked here and in `STRUCTURED-OUTPUT.md` so it isn't lost,
    not scoped or planned yet.
- [ ] **`vocabulary-sidecar.tn`'s own `value` field is a known simplification** — typed as plain
  optional `text`, which doesn't capture the two atom families (`complex`, `duration`) that actually
  write `value` as a small nested record on the wire. Revisit once `!choice` construction resolves
  (a precise per-family shape would need a discriminator on `type-ref` itself — an open ~30-name
  vocabulary, not a small closed enum like `outcome`/`kind`, so even with `!choice` working this one
  isn't a completely free win the way the `outcome`-discriminated shapes are).

## Documentation

- [ ] User-facing documentation on how to use the library — today only `CLAUDE.md`'s own dense,
  session-oriented internal narrative exists.
- [ ] AI skills for using the library.
- [ ] `@doc`-driven documentation generation (render a schema's own `@doc` annotations). **Unblocked**: all
  104 `@doc` strings across the three bundled schemas now survive resolution and linking, reachable as
  `schema.entries().getAnnotations(name).value("doc", String.class)` — every one of core.tn's 48
  declarations is documented. What's missing is the renderer, not the data.
- [ ] **State the streaming contrast in README positioning.** JEP 540 makes its in-memory limit an explicit
  design decision, not an omission: "We assume that input JSON documents can fit in memory, as either a
  `String` or a `char` array... if we were to allow JSON sources such as files or network connections,
  issues such as insufficient memory would be possible with large documents." That is the cleanest
  available statement of what this library's reader stack buys — `TsonDataStream` pulls events, every
  facade reader takes an `InputStream` and never fully buffers, and memory is proportional to nesting
  depth rather than document size. Worth a line, because when JSON ships in the JDK the question becomes
  "why not just use that?", and the honest answer is a short list: schema, binding, streaming, collected
  diagnostics — each of which JEP 540 names as a non-goal.

- [ ] Documentation for the *diagnostics* story specifically — `TsonDiagnosticsReceiver` is the seam a
  consumer implements to route problems anywhere (a formatter writing to stdout as they arrive, a capped
  collector, a metrics sink), and only the two built-ins are shown anywhere today. The README covers the
  collector; a worked custom receiver is the obvious missing example, and it is what
  `STRUCTURED-OUTPUT.md`'s Tier 1.5 streaming consumer will be built on.

## Build

- [ ] **`./gradlew javadoc` fails, and has for a while.** 20 errors across three modules: 2 in
  `tson-schema` (`RecordBody`, a bare `&` in Javadoc), 15 in `tson-compiler` (mostly `{@link}`s to members
  that don't exist — `TsonCompiledMetaSchema#bootstrap`, several in `TsonTreeReader`/`TsonObjectReader` —
  plus a bare `` `array<T>` `` read as a tag and an unterminated inline tag in `TsonDataStream`), 3 in
  `tson-bind` (malformed HTML). Individually trivial; the reason they accumulated is worth knowing:
  **`javadoc` is not part of `build`**, and when run directly Gradle stops at the first failing module, so
  `tson-compiler`'s 15 were invisible behind `tson-schema`'s 2 until someone ran the modules separately.
  Fixing the errors without also wiring `javadoc` into CI just resets the clock.

## Miscellaneous

- [ ] General resolver-layer structural rules as reusable primitives, rather than binding-time-only
  behavior — empty-brace resolution, the absent-vs-missing distinction.
- [ ] **Annotations are still discarded at a dispatched position.** A dispatcher (`VariantBindReader` for a
  union, `VariantSchemaReader` under bind mode) must consume the leading annotations to reach the
  `!typeName` it dispatches on -- they precede it in `data-value = *annotation [type-ref] core-value` -- so
  the reader that ends up building the value never sees them. Tree mode solved this by re-attaching to the
  finished node (`TsonValue.withAnnotations`); bind mode would do the same through `DataClassAnnotated`'s
  `constructor` handle, wrapping what came back.
  - Not reachable today: a union member is not a boxed position, so its carrier is always empty rather than
    wrong. Worth closing when a boxed variant becomes expressible, not before.


# Lower Priority

## Front door / ergonomics

- [ ] A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy — today the only
  `TsonSchemaSource` is `TsonSchemaSource.registeredOnly()` (nothing fetched); the bundled standard
  library is served internally by `TsonCompiledSchemaRegistry` from `TsonBundledSchemas`, not through
  a source.

## Tree model (`TsonValue`)

The tree model itself is built and described in `CLAUDE.md`'s "Tree model" section. What's left:

- [ ] **Copy-on-write transforms + builders (parked).** The "new tree from old" editing half —
  `TsonRecord.with(name, value)`/`without(name)`, `TsonArray.with(i, value)`/`plus(value)`/`without(i)`,
  `TsonRecord.builder()`, and a pointer-based `set("/a/b", value) → new tree`. All pure `tson-tree`
  operations (no compiler dependency), so they belong in that module. Deferred until there's a concrete
  produce/edit use case: `TsonTreeWriter` already closes the read→edit→write loop, so these have a real
  payoff when wanted, but block nothing now.
    - **Nothing to copy from JEP 540, and that is the useful part.** It ships no transformation API and no
      builders at all — construction is static `of(...)` factories, which `tson-tree` already matches — and
      its Risks section defers the area outright: "During the incubation period, we will gather more
      information about use cases involving generating and transforming JSON documents, in order to evolve
      these areas of the API." The JDK reached the same "wait for real use cases" conclusion independently,
      which turns this item's deferral from a shrug into a decision.

## Miscellaneous

- [ ] Thread-safety — currently only `synchronized` on `TsonSchemaRegistry`/
  `TsonCompiledSchemaRegistry`'s own `register`/`get`/`getMeta` (its `load` deliberately isn't, to
  avoid serializing unrelated on-demand loads); everything else is an open design question.
- [ ] Confusable-character and bidi-formatting-character warnings (§9.4-adjacent security hardening) —
  the sibling gap to the numeric-literal length limit tracked in `STRUCTURED-OUTPUT.md`'s Tier 1 section;
  neither is enforced anywhere yet. `SPEC-FEEDBACK.md` #34 is the fuller treatment: which UTS #39 mechanism
  applies where, the comparison scopes TSON can actually name, and why a normative requirement would oblige
  every implementation to ship UCD data the JDK does not expose.
