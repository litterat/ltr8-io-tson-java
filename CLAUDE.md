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

Forty-odd notes, each under ~8 KB and opening with its invariants. Pick by the class you are about to touch.

| Area | Notes (`design/…`) |
|---|---|
| **Lexing and data parsing** | `lexer-and-data-parsing.md` (lexer, Tier 2 stream, Tier 3 AST) · `base-types-and-atom-vocabulary.md` (§4 resolution, `tson-atom`) |
| **Schema grammar, desugaring** | `schema-grammar.md` (`TsonSchemaParser`) · `schema-grammar-and-desugaring.md` (`SchemaDesugarer`, sugar table, forms written in place) · `desugaring-open-forms-and-templates.md` (forms over a parameter, template bodies refused) |
| **Resolution** | `references.md` (the forms a name takes, an application's argument list; which slot is typed `meta.type_ref` or a key) · `schema-resolution.md` (`DefinitionResolver`, field states, groups, exception boundary) · `constructor-application.md` (`!C {…}`, which fields take `~`/`=`) · `atom-refinement-and-coherence.md` · `resolver-vocabulary-and-bootstrap.md` (`WireForm`/`TypeSlots`/`CanonicalApplication`, alias hops, meta-kernel bootstrap) |
| **Templates (§5.10)** | `open-records.md` (an open record, `Param`, `ParamRef`, bounds) · `template-materialisation.md` (`TemplateMaterialiser`, where an application closes, regularity) |
| **Linking** | `linking-and-compilation.md` (registry, identity, import merge, link order, values by name) · `kinds-and-name-types.md` (`KindMembers`, `name_type`) · `inhabitance-and-record-extension.md` · `choice-disjointness.md` · `namespace-types-and-value-entries.md` (namespace types, value entries, `ValueEntry`) · `name-hygiene.md` (`checkNames`) |
| **Compilation** | `class2-compilation.md` (`TsonSchemaCompiler`, `CompiledReaders`, `ErrorReader`, untagged choices) · `compiled-registries.md` (the two registries, concurrency) |
| **Reading** | `readers-and-diagnostics.md` (`TsonReadContext`, the read rules, tree/bind asymmetry) · `record-dispatch.md` · `declared-positions.md` (§7.8) · `reader-naming-and-schema-location.md` · `name-hygiene-read-path.md` |
| **Diagnostics and policy** | `diagnostic-model.md` (`Diagnostic`, `Code`, the component rule) · `diagnostic-rules-and-messages.md` (`base.diagnostics`, `expected`) · `schema-side-diagnostics.md` (reporting overloads, `validateSchema`, bind agreement) · `processor-policy.md` (`ProcessorPolicy`, `LimitsPolicy`) |
| **Facades, writers, tree** | `facades-and-tree.md` (the two read facades) · `writers-and-document-header.md` (writers, sinks, `TsonDocumentPeek`) · `tree-model.md` (`TsonValue`) · `front-door-and-config.md` (`Tson`, `ProcessorConfig`) |
| **JSON encoding** | `json-encoding.md` (rationale: why a separate stack) · `json-lexer-stream-tree.md` · `json-schema-directed-reading.md` · `json-facades-binding-writing.md` · `json-unicode-policies.md` |
| **Modules** | `modules.md` (every boundary and why) · `tson-base.md` (the shared vocabulary, package by package) |
| **CLI, bundled schemas, hashing** | `cli-config-hashing.md` |
| **Conformance, build, process** | `conformance-suite.md` (runner contract, §8.2 scope walks) · `build.md` (commands, allocation harness, publishing) · `process.md` (branches, spec-feedback register, backlog and identity rules at full length) |

Other root documents: `BACKLOG.md` (outstanding work), `SPEC-FEEDBACK.md` (open spec issues against Parts 1 and 2),
`STRUCTURED-OUTPUT.md` (the target use case: LLM structured-output validation, JSON compatibility), and the
consumer-facing `README.md`, `STATUS.md` (the implemented checklist) and `CONFORMANCE.md` (edge-case behaviour).
`skills/` holds the consumer-facing Claude skills (`tson-java`), linked into `.claude/skills/`.

## Project

A from-scratch Java implementation of TSON (Typed Schema Object Notation), built directly against the TSON spec series
(2026 revision), and the spec's first implementation:

- Part 1 — lexer, structural grammar, base type resolution, built-in types: https://tson.io/raw/2026/39/tson-part1-data.md
- Part 2 — schema grammar, type system, resolution, linking, compilation: https://tson.io/raw/2026/39/tson-part2-schema.md
- Part 3 — the JSON encoding, **drafted in this repo**: `spec/tson-part3-json.md`

The spec is a working revision that changes without compatibility guarantees. When in doubt, **re-fetch the current URL**
and check the revision number rather than trusting a cached copy. On this branch `spec/` holds **Revision 40's proposed
text, edited in place**: Parts 1, 2 and 3, with `spec/tson-rev40-changelog.md` recording the change, so
`git diff main -- spec/` is the proposal. `spec/m/{meta,numeric,temporal,net,core,policy}.tn` are packaged from here at
build time.

**Editing a bundled schema means re-stamping.** The library verifies the packaged bytes against
`TsonBundledSchemas`' digests on every load, so one stale constant fails `Tson.standard()` and most of the suite.
`scripts/restamp-bundled-schemas.sh` re-pins everything in dependency order (`--check` reports only); Part 2 §13.2's
table in `spec/` is the one pin it does not write. Keep `spec/fixtures/*-resolved.tn` in step — `ResolvedFixtureTest`
checks them. `design/process.md` has the procedure, and the rules for what a schema's `@doc` may say.

**Branches.** `main` is the reference implementation of the *published* revision (39): identities `/2026/39/m/`,
version `0.39.0-SNAPSHOT`. Published revisions are tags (`r2026-32`, `r2026-34`, `r2026-35`, `r2026-36`, `r2026-37`,
`r2026-38`), each added when the next revision merges. **`r2026-40-proposal` is open**, with a corpus branch of the
same name, for work the published spec cannot carry; it carries Revision 40 identities (`/2026/40/m/`) and version
`0.40.0-SNAPSHOT`, and merges when the spec lands and not before. Proposal work branches off it and PRs into it; work
Revision 39 can carry still goes to `main`. Check the branch before quoting identities or the version.
`design/process.md` has the procedure.

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
| `tson-base` | Shared by every encoding: `Diagnostic`, policies, schema sources, host atom values, byte I/O |
| `ltr8-annotation` | Binding annotations and the `Annotations` carrier |
| `ltr8-bind` | Generic `DataValue`↔object binding engine; knows nothing of schemas |
| `tson-schema` | Resolved-schema value model (`schema.meta`, and a package per namespace), registry, `TsonBundledSchemas` |
| `tson-atom` | The built-in atom vocabulary, over `String`, and §7.7's name grammar, shared by both encodings |
| `tson-tree` | `TsonValue` data tree model; depends on nothing |
| `ltr8-regex` | RFC 9485 I-Regexp engine; depends on nothing |
| `ltr8-net` | Network text formats (`io.ltr8.net`: URIs/IRIs, IP addresses, CIDR, EUI-48, IDNA2008 host names, media types), each to its RFC; depends on `ltr8-unicode` |
| `ltr8-unicode` | Unicode properties and algorithms (`io.ltr8.unicode`: XID, normalization, UAX #31 profiles, UTS #39); depends on nothing |
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

**A comment says why, never what.** The code says what it does, a test's name says what must hold, and the spec
states the rule. A comment earns its place by saying what none of those can: the invariant a reader would break, the
reason the obvious alternative is wrong, or the section that requires the behaviour (`[TSON-SCHEMA] §5.10`).

- No comment restates the code beneath it, a signature or a type. A method whose name and types say it has no Javadoc.
- A comment describes its own class. Another class's mechanism is named by `{@link}`, never retold: when that class
  changes, this file should not have to.
- A fact is stated once, where it is enforced. Where an assertion, a sealed type or a test can hold the invariant,
  write that and not the sentence.
- Present tense, current contract: no dates, no "renamed from", no "used to", no "no longer".
- When you edit a class, delete the comments the edit makes untrue or unnecessary, in the same edit. Adding prose is
  never part of finishing a change.

The one place a comment says what is public API (`Tson`, the facades, `tson-base`): what a caller may pass and what
comes back.

**A design note holds what the code cannot.** One per area: the invariants that are easy to break silently, each
with its reason, and the decisions that lost and why. It does not walk through the code, list methods or restate the
spec. A note is under ~8 KB; one that needs more is describing the implementation. Update a note when an invariant
or a decision changes, not when code moves: a change that touches no invariant touches no note.

**The test for both:** if this sentence were deleted, could a reader recover it from the code, the tests or the
spec in a minute? If so, delete it.

**`BACKLOG.md` is a list of outstanding work and nothing else.** Every entry is something someone could pick up. Not
entries: what was done (a shipped item comes out entirely), what was decided against, what might become work later. A
fact that must survive its entry goes in the note, the Javadoc or the test that owns the area.

**`Tson` is a prefix, never an infix** (`TsonCompiledSchema`, never `CompiledTsonSchema`), and only on types a consumer
names in their own code; internal machinery is bare (`Lexer`, `SchemaResolver`). `tson-base` drops it; `tson-json`
uses `Json` on the same terms. A module that knows nothing of TSON is named `ltr8-<name>`, carries no prefix and lives
at `io.ltr8.<name>` (`ltr8-bind`, `ltr8-net`, `ltr8-regex`, `ltr8-unicode`), usable on its own.

**Exception classification is a policy.** `SchemaValidationException`: the author's schema is wrong and the spec
says so. `UnsupportedOperationException`: this library has not implemented that yet. `IllegalStateException`: an
internal invariant broke. The test: *a schema error's verdict doesn't change when this library improves; a gap's does.*
A gap travels as `Diagnostic.Code.NOT_IMPLEMENTED`, and the CLI's exit 1 vs 70 rides on that code.

**Project-owned schema `!!id`:** `https://tson.io/2026/40/io/ltr8/<group>/<name>.tn`. Between releases the schema is
edited in place; a release fixes the document under its identity (§10). Use `.tn`, never `.tn1`.

**Line wrapping:** 125 characters, comments and code. Count characters, not bytes (`scripts/check-line-length.sh`).

**Never put literal BOM/NEL/LS/PS characters in source or tests** — use `\uXXXX` escapes.

**Add conformance vectors in the same session as lexer/parser/resolver work.** The corpus is the sibling repo
`ltr8-io-tson-test-suite`; it moves first, then `SUITE_PIN`. `design/conformance-suite.md` has the runner contract.

## Traps

One line each; the class Javadoc and the note carry the why. Read before touching the class involved.

- A `TypeRef` is a name, never an application: an application is an `ApplyBody` written in place, whose arguments are
  names, compared by head and arguments. A constructor's application is no `ApplyBody`: it is rewritten to its
  construction before anything reads it (`ConstructionRewrite`).
- `SchemaDesugarer` returns un-rewritten nodes **by identity** — positions live in an `IdentityHashMap`.
- A desugar-reported declaration is replaced with an absorbing stand-in that **keeps its type parameters**.
- A type slot holds a `TypeExpr`: a `TypeRef`, or a `TypeInline` wrapping a body with its constructor. A `TypeInline`
  is no entry, so nothing looks one up by name, and a name and a `TypeInline` are never the same type.
- `requireDocumentEnd`: the pull past the root value is the point, not the assertion after it.
- Lexer multi-line close detection strips leading whitespace *before* comparing against `"""`.
- `Position` must keep the record's default `toString()` — `ResolvedForm` normalises positions by regex over it.
- `CompiledReaders` is rebound exactly once, from the `Compilation` to the finished `TsonCompiledSchema`.
- `verifyFixed` compares with the pre-rebind parser (`FixedCheck`).
- A schema-model bind target with more than one public constructor needs `@Record` on the canonical one.
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
./gradlew publishToMavenLocal        # io.ltr8:<module>:0.40.0-SNAPSHOT; no remote repository, deliberately
scripts/restamp-bundled-schemas.sh --check
```

**A skipped conformance run reads green.** The corpus is found as a sibling checkout, then the pinned copy in
`.references/` (`scripts/fetch-references.sh`); absent, the vectors abort through `Assumptions` unless
`TSON_REQUIRE_TEST_SUITE` is set, as CI does. Check the run actually executed vectors before calling it a pass.

**Allocation is measured, not assumed** (`AllocationHarnessTest`, `JsonAllocationHarnessTest`): retention is a flat 0
bytes per read, transient bytes have a loose ceiling. Treat the shape as the signal. `design/build.md`.

**Shipping a change** follows the `/ship` skill: issue → branch off `main` → PR → CI green *for the HEAD
commit* → merge commit. A fix's test is shown to fail without the fix.
