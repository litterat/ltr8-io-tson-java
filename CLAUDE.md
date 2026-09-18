# CLAUDE.md

Orientation for Claude Code sessions in this repo: what the project is, the rules that apply to every change, and where
the detail lives. It describes the code **as it stands** — present tense; history is in git.

**This file is an index, and it stays short.** Design detail lives in `design/` (map below), in class Javadoc, and in
the `CLAUDE.md` inside each module directory, which loads when you touch that module. **Before working in an area, read
its `design/` note** — each opens with the invariants that are easy to break silently, then the why. Read the one or two
notes the task touches, not all of them. Trust but verify: the code is the source of truth if a note has drifted.

**When you learn something, put it in the area's note, not here.** A paragraph added to this file is read by every
session on every task; one added to a note is read by the sessions that need it. This file grows only when a rule
applies to all work in the repo.

## Design notes (`design/`)

Forty-odd notes, each under ~25 KB and opening with its invariants. Pick by the class you are about to touch.

| Area | Notes (`design/…`) |
|---|---|
| **Lexing and data parsing** | `lexer-and-data-parsing.md` (lexer, Tier 2 stream, Tier 3 AST) · `base-types-and-atom-vocabulary.md` (§4 resolution, `tson-atom`) |
| **Schema grammar, desugaring** | `schema-grammar.md` (`TsonSchemaParser`) · `schema-grammar-and-desugaring.md` (`SchemaDesugarer`, sugar table, lifts) · `desugaring-open-forms-and-templates.md` |
| **Resolution** | `schema-resolution.md` (`DefinitionResolver`, field states, groups, exception boundary) · `constructor-application.md` (`!C {…}`, which fields take `~`/`=`) · `atom-refinement-and-coherence.md` · `resolver-vocabulary-and-bootstrap.md` (`WireForm`/`MetaRefs`/`DerivedName`, reference hops, `@synthetic`, meta-kernel bootstrap) |
| **Templates (§5.10)** | `held-template-bodies.md` (`TemplateBody`, parameter kinds) · `template-materialisation.md` (`TemplateMaterialiser`, `SyntheticMerge`, regularity) |
| **Linking** | `linking-and-compilation.md` (registry, identity, import merge, inhabitance, `record.extension`) · `choice-disjointness.md` · `meta-layer-data-kind.md` (`Data`) · `name-hygiene-and-minted-names.md` (`checkNames`, `InternalName`) |
| **Compilation** | `class2-compilation.md` (`TsonSchemaCompiler`, `CompiledReaders`, `ErrorReader`, untagged choices) · `compiled-registries.md` (the two registries, `ForeignSchemas`, concurrency) |
| **Reading** | `readers-and-diagnostics.md` (`TsonReadContext`, the read rules, tree/bind asymmetry) · `record-dispatch.md` · `scope-push.md` (§7.8) · `reader-naming-and-schema-location.md` · `name-hygiene-read-path.md` |
| **Diagnostics and policy** | `diagnostic-model.md` (`Diagnostic`, `Code`, the component rule) · `diagnostic-rules-and-messages.md` (`base.diagnostics`, `expected`) · `schema-side-diagnostics.md` (reporting overloads, `validateSchema`, bind agreement) · `processor-policy.md` (`ProcessorPolicy`, `LimitsPolicy`) |
| **Facades, writers, tree** | `facades-and-tree.md` (the two read facades) · `writers-and-document-header.md` (writers, sinks, `TsonDocumentPeek`) · `tree-model.md` (`TsonValue`) · `front-door-and-config.md` (`Tson`, `ProcessorConfig`) |
| **JSON encoding** | `json-encoding.md` (rationale: why a separate stack) · `json-lexer-stream-tree.md` · `json-schema-directed-reading.md` · `json-facades-binding-writing.md` · `json-unicode-policies.md` |
| **Modules** | `modules.md` (every boundary and why) · `tson-base.md` (the shared vocabulary, package by package) |
| **CLI, bundled schemas, hashing** | `cli-config-hashing.md` |
| **Conformance, build, process** | `conformance-suite.md` (runner contract, §8.2 scope walks) · `build.md` (commands, allocation harness, publishing) · `process.md` (branches, spec-feedback register, backlog and identity rules at full length) |

`design/KNOWN-DRIFT.md` lists statements in the notes known to be stale. If a note contradicts the code, check there,
trust the code, and fix the note.

Other root documents: `BACKLOG.md` (outstanding work), `SPEC-FEEDBACK.md` (open spec issues against Parts 1 and 2),
`STRUCTURED-OUTPUT.md` (the target use case: LLM structured-output validation, JSON compatibility), `README.md`
(consumer-facing).

## Project

A from-scratch Java implementation of TSON (Typed Schema Object Notation), built directly against the TSON spec series
(2026 revision), and the spec's first implementation:

- Part 1 — lexer, structural grammar, base type resolution, built-in types: https://tson.io/raw/2026/36/tson-part1-data.md
- Part 2 — schema grammar, type system, resolution, linking, compilation: https://tson.io/raw/2026/36/tson-part2-schema.md
- Part 3 — the JSON encoding, **drafted in this repo**: `spec/tson-part3-json.md`

The spec is a working revision that changes without compatibility guarantees. When in doubt, **re-fetch the current URL**
and check the revision number rather than trusting a cached copy. `spec/` is a cache of Parts 1 and 2 (Revision 35, not
edited here) with **two exceptions that are live**: `spec/m/{meta-kernel,meta,core}.tn` are packaged from here at build
time, and `spec/tson-part3-json.md` is edited in place.

**Editing a bundled schema means re-stamping.** The library verifies the packaged bytes against
`TsonBundledSchemas`' digests on every load, so one stale constant fails `Tson.standard()` and most of the suite.
`scripts/restamp-bundled-schemas.sh` re-pins everything in dependency order (`--check` reports only); Part 2 §13.2's
table in `spec/` is the one pin it does not write. Keep `spec/m/*-resolved.tn` in step — `ResolvedFixtureTest` checks
them. `design/process.md` has the procedure.

**Branches.** `main` is the reference implementation of the *published* revision (35); published revisions are tags
(`r2026-32`, `r2026-34`). Work for a revision happens on a proposal branch, merged when the spec lands and not before.
**The open proposal is `r2026-36-proposal`**: PR branches come off it and merge into it, the bundled schemas carry
Revision 36 identities from the start, and the sibling corpus repo has a branch of the same name that `SUITE_PIN` follows.

**Nothing here is frozen, and nothing is owed to a user who does not exist.** No published releases, every version
`-SNAPSHOT`. So correctness wins over stability every time: a wrong rule gets fixed, a bad name changed rather than
deprecated, a wrongly-shaped public method deleted rather than wrapped. The one binding exception is §10's immutability
of a *published* schema `!!id`.

**Status.** Parts 1 and 2 work end to end — the bundled schemas resolve, link and compile; user schemas validate and
read in tree and bind mode; the `tson` CLI drives it. The JSON encoding has its own full stack in tree mode. What is
left is in `BACKLOG.md`.

**Hard constraints:** Java 25 only. No external runtime dependencies in main code; JUnit (Jupiter) for tests only.

## Pipeline and modules

Schema documents: **parse → desugar → resolve → link → register → compile → read** (`TsonSchemaParser`,
`SchemaDesugarer`, `TsonSchemaResolver`, `TsonSchemaLinker`, `TsonSchemaRegistry`, `TsonSchemaCompiler`,
`TsonTypeReader`). Data documents with no schema run lex → parse → base-type-resolve.

| Module | Holds |
|---|---|
| `tson-base` | Shared by every encoding: `Diagnostic`, policies, schema sources, host atom values, byte I/O, UCD tables |
| `tson-annotation` | Binding annotations and the `Annotations` carrier |
| `tson-bind` | Generic `DataValue`↔object binding engine; knows nothing of schemas |
| `tson-schema` | `schema.meta` resolved-schema value model, registry, `TsonBundledSchemas` |
| `tson-atom` | The built-in atom vocabulary, over `String`, shared by both encodings |
| `tson-tree` | `TsonValue` data tree model; depends on nothing |
| `tson-regex` | RFC 9485 I-Regexp engine; depends on nothing |
| `tson-compiler` | The engine: lexer, grammars, resolver, linker, compiler, readers, writers, facades |
| `tson` | The front door: `Tson` |
| `tson-json` | The JSON encoding, a separate stack with no dependency on `tson-compiler` |
| `tson-cli` | The `tson` command |

Dependencies run toward the value models: `tson-compiler` depends on `tson-schema` and `tson-tree`, never the reverse.
JPMS enforces it. `design/modules.md` has each boundary and why.

## Spec feedback — this is the first implementation

Being first makes this the real test of whether the spec's prose resolves to one behaviour. Watch for **ambiguity**,
**internal inconsistency**, **underspecification** and plain **errors**. When you find one, say so in conversation and
record it — never silently pick an interpretation.

- **Parts 1 and 2 → `SPEC-FEEDBACK.md`**: section, concrete description, the interpretation chosen and why, suggested
  resolution. The register holds only what is open against the current revision and renumbers from #1 when a revision
  closes; resolved entries are deleted. It is self-contained and is the as-built record that goes to the spec reviewer:
  an entry proposing a design states what is *running*, and says so where a recommendation is a proposal instead.
- **Part 3 → edit `spec/tson-part3-json.md` directly**, in the same session, and say so in the commit; it has no
  published revision to propose against. State the rule in the prose (and, where the choice was open, why the
  alternative lost). The document never mentions this codebase. Keep edits to what implementation forced a decision
  about; a section you build against and leave unedited is one you are asserting is right.
- **Cite the spec, not the argument.** Prose and Javadoc name the current section that requires a rule. A
  `SPEC-FEEDBACK.md #N` citation is only for an entry still open; when it closes, the citation becomes the section.

## Conventions

**Javadoc and notes document the current contract only.** No dates, no "renamed from", no "used to", no "on the user's
direction". If a design needs a why, state the invariant and its rationale, once, in proportion to its logic. When you
edit a class, clean its Javadoc in the same edit. `design/` and this file follow the same rule.

**Keep the `design/` note current in the same session as the change**, the way you would the Javadoc. A note that
silently drifts is worse than no note.

**`BACKLOG.md` is a list of outstanding work and nothing else.** Every entry is something someone could pick up. Not
entries: what was done (a shipped item comes out entirely), what was decided against, what might become work later. A
fact that must survive its entry goes in the note, the Javadoc or the test that owns the area.

**`Tson` is a prefix, never an infix** (`TsonCompiledSchema`, never `CompiledTsonSchema`), and only on types a consumer
names in their own code; internal machinery is bare (`Lexer`, `SchemaResolver`). `tson-base` drops it; `tson-json`
uses `Json` on the same terms.

**Exception classification is a policy.** `TsonSchemaValidationException`: the author's schema is wrong and the spec
says so. `UnsupportedOperationException`: this library has not implemented that yet. `IllegalStateException`: an
internal invariant broke. The test: *a schema error's verdict doesn't change when this library improves; a gap's does.*
A gap travels as `Diagnostic.Code.NOT_IMPLEMENTED`, and the CLI's exit 1 vs 70 rides on that code.

**Project-owned schema `!!id`:** `https://tson.io/2026/36/ltr8/<group>/<name>-<version>.tn`. The version is bumped on
a *release*, not on a change — between releases the schema is edited in place. Use `.tn`, never `.tn1`.

**Line wrapping:** 125 characters, comments and code. Count characters, not bytes (`scripts/check-line-length.sh`).

**Never put literal BOM/NEL/LS/PS characters in source or tests** — use `\uXXXX` escapes.

**Add conformance vectors in the same session as lexer/parser/resolver work.** The corpus is the sibling repo
`ltr8-io-tson-test-suite`; it moves first, then `SUITE_PIN`. `design/conformance-suite.md` has the runner contract.

## Traps

One line each; the class Javadoc and the note carry the why. Read before touching the class involved.

- `TypeArgument` is a sealed interface (`Ref`/`Value`), never a record with two `Optional`s.
- `SchemaDesugarer` returns un-rewritten nodes **by identity** — positions live in an `IdentityHashMap`.
- A desugar-reported declaration is replaced with an absorbing stand-in that **keeps its type parameters**; injected
  declarations are never rolled back.
- `requireDocumentEnd`: the pull past the root value is the point, not the assertion after it.
- Lexer multi-line close detection strips leading whitespace *before* comparing against `"""`.
- `Position` must keep the record's default `toString()` — `ResolvedForm` normalises positions by regex over it.
- `CompiledReaders` is rebound exactly once, from the `Compilation` to the finished `TsonCompiledSchema`.
- `verifyFixed` compares with the pre-rebind parser (`FixedCheck`).
- A `schema.meta` bind target with more than one public constructor needs `@Record` on the canonical one.
- An atom body's components mirror its constructor's *resolved* (flattened) shape, one component per schema field name —
  a nested or missing one binds `null` silently.
- Atom refinement merges through `writer.DataClassObjectWriter` on the wire record before binding — never through the
  `TsonObjectWriter` facade, and there is no cheaper substitute.

## Build and test

No system Gradle — always the wrapper. `build` also runs javadoc (doclint), so a dangling `{@link}` fails locally.

```
./gradlew build
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.lexer.LexerTest"      # one class
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.ConformanceSuiteTest" # class1 corpus
./gradlew :tson:test --tests "io.ltr8.tson.Class2ConformanceSuiteTest"             # class2 corpus
./gradlew :tson-base:test :tson-json:test
./gradlew :tson-cli:installDist      # then tson-cli/build/install/tson/bin/tson validate ...
./gradlew :tson:allocationReport     # allocation harness, numbers on stdout
./gradlew publishToMavenLocal        # io.ltr8:<module>:0.36.0-SNAPSHOT; no remote repository, deliberately
scripts/restamp-bundled-schemas.sh --check
```

**A skipped conformance run reads green.** The corpus is found as a sibling checkout, then the pinned copy in
`.references/` (`scripts/fetch-references.sh`); absent, the vectors abort through `Assumptions` unless
`TSON_REQUIRE_TEST_SUITE` is set, as CI does. Check the run actually executed vectors before calling it a pass.

**Allocation is measured, not assumed** (`AllocationHarnessTest`, `JsonAllocationHarnessTest`): retention is a flat 0
bytes per read, transient bytes have a loose ceiling. Treat the shape as the signal. `design/build.md`.

**Shipping a change** follows the `/ship` skill: issue → branch off the proposal branch → PR → CI green *for the HEAD
commit* → merge commit. A fix's test is shown to fail without the fix.
