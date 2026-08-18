# CLAUDE.md

Orientation for Claude Code sessions in this repo. It describes the code **as it stands** — current
form, present tense. How it got here lives in git history and `BACKLOG.md`, not here; when a design
choice has a non-obvious *why*, the current rationale is stated directly rather than the sequence of
edits that produced it.

**This file is deliberately an overview.** The full per-phase design detail lives in `docs/` (map below)
and in class Javadoc. **Before working in an area, read its `docs/` note** — each note carries the
invariants, spec-feedback citations, and deliberate divergences for that area at the depth this file used
to. Trust but verify: the code is the source of truth if a note has drifted.

| Area | Design note |
|---|---|
| Lexer, Tier 2/3 data parsing, base type resolution, atom vocabulary | `docs/lexer-and-data-parsing.md` |
| Schema grammar, desugaring | `docs/schema-grammar-and-desugaring.md` |
| Schema resolution, meta-kernel bootstrap | `docs/schema-resolution.md` |
| Identity, linking, registry, Class 2 compilation, compiled registries | `docs/linking-and-compilation.md` |
| Streaming readers, read context, diagnostics (data- and schema-side) | `docs/readers-and-diagnostics.md` |
| Read facades, writers, tree model, `Tson` front door | `docs/facades-and-tree.md` |
| CLI, config package, bundled schemas, content hashing | `docs/cli-config-hashing.md` |

## Project

A from-scratch Java implementation of TSON (Typed Schema Object Notation), built directly against the TSON
spec series (2026 revision):

- Part 1 — lexer, structural grammar, base type resolution, built-in type vocabulary:
  https://tson.io/raw/2026/32/tson-part1-data.md
- Part 2 — schema grammar, type system, resolution, linking, compilation:
  https://tson.io/raw/2026/32/tson-part2-schema.md

The spec is a *working revision* that changes between revisions without compatibility guarantees. When in
doubt, **re-fetch the current URL** and check the revision number at the top rather than trusting a cached
copy. `spec/` holds local snapshots (revision 32) for quick reference: `spec/tson-part1-data.md`,
`spec/tson-part2-schema.md`, and `spec/m/{meta-kernel,meta,core}.tn` (the spec's own bundled schema
documents — the meta-kernel bootstrap layer, the meta-schema built on it, and the core type library built
on that) plus their non-normative `*-resolved.tn` resolver-output fixtures. Treat `spec/` as a cache, not
a source of truth.

**Status:** Part 1 is complete and frozen. Part 2's grammar, resolution, linking, and Class 2 compilation
all work: the three bundled schemas resolve/register/compile in full, user schemas governed by them
validate and read, and a `tson` CLI drives it end to end. Known gaps are listed under "Not yet
implemented".

**Hard constraints:**
- Java 25 only.
- No external runtime dependencies in main code. JUnit (Jupiter) is permitted for tests only.

## Spec feedback — this is the first implementation

This is the spec's first implementation, which makes it the first real test of whether the prose resolves
unambiguously to one behavior — valuable to the spec author precisely because it's still a draft. Actively
watch for and flag:

- **Ambiguity** — wording a careful reader could reasonably implement two ways.
- **Internal inconsistency** — two sections (or a grammar production and its prose) that disagree.
- **Underspecification** — a case the grammar/prose doesn't address where an implementation must still
  pick something.
- **Errors** — plain mistakes (wrong cross-reference, grammar that doesn't parse its own examples).

When you find one: say so in conversation, and record it in `SPEC-FEEDBACK.md` (spec section, concrete
description, the interpretation this implementation chose and why, suggested resolution). Don't silently
pick an interpretation — a resolved ambiguity is invisible again three sessions later unless written down.
Several such findings are load-bearing and are cited by number (`SPEC-FEEDBACK.md` #N) throughout the
`docs/` notes.

## Conventions

**Javadoc documents current contract only, no change history.** Java source Javadoc describes an element's
*current* behavior — never dates, "renamed from X", "used to do Y, now does Z", "on the user's direction",
or similar changelog framing. If a design needs a WHY, state the current invariant and its rationale
directly. When you edit a class, clean up its Javadoc in the same edit — remove stale narrative (even if
you didn't write it), fix anything that no longer matches the code, tighten what's left. The `docs/` notes
and this file follow the same no-history rule; the dated log lives in git.

**Keep the `docs/` note current in the same session as the change.** When work alters behavior an area's
design note describes, update that note the way you'd update the class's Javadoc — same edit, not a
follow-up. A note that silently drifts is worse than no note.

**`Tson` is a prefix, never an infix.** A class name containing `Tson` must lead with it (`TsonSchema`,
`TsonDataParser`, `TsonCompiledSchema`) — never buried (`CompiledTsonSchema` is wrong). The prefix is
**not** applied to every class: most internal machinery is deliberately bare (`Lexer`,
`RecordAbstractReader`, `DeferredTypeReader`, `ChoiceDisjointness`, `SchemaResolver`,
`DefinitionResolver`). Reserve `Tson` for types a *consumer of this library* names in their own code — its
value is disambiguation at the call site (`TsonSchema` vs. a domain `Schema`). When adding a new public,
developer-facing type, ask "would a consumer plausibly have their own class with this bare name?" — if yes
and it's consumer-facing, prefix it; if it's internal machinery, leave it bare.

**Exception classification is a policy, not a style choice.** Across the schema pipeline:
`TsonSchemaValidationException` means *the author's schema is wrong and the spec says so*;
`UnsupportedOperationException` means *this library hasn't implemented that yet*; `IllegalStateException`
means an internal invariant broke. The classification test: **a schema error's verdict doesn't change when
this library improves; a gap's does.** Only the validation exception is ever collected into a `Diagnostic`
— a gap is not a verdict on the author's schema, and the CLI's exit 1 vs. exit 70 rides on the split.
`DefinitionResolver`'s Javadoc lists the exact current boundary.

**Project-owned schema `!!id`:** a schema this project authors (not the spec's own bundled artifacts) gets
`https://tson.io/2026/32/ltr8/<group>/<name>-<version>.tn` — `/2026/32` is the spec revision, `ltr8` the
publishing org, `<group>` the subsystem (`cli`), `<name>-<version>` the schema name with a trailing
integer version. Bump the version under a new name (`diagnostics-2.tn`, not an in-place edit) whenever the
shape changes (§10's immutability rule). **Use `.tn`, not `.tn1`** — `.tn1` is a stability claim §7.1
reserves for the eventual frozen "TSON version 1", which hasn't happened (see `SPEC-FEEDBACK.md` #20).

**Line wrapping:** wrap both comments and code to 125 characters.

## Modules and dependency direction

Package group is `io.ltr8` (reverse-DNS identifies who *publishes* the artifact — this is one
implementation of the spec published under the `ltr8.io` banner, not *the* tson.io-blessed one). Every
module has a real `module-info.java`; module names mirror each module's root exported package.

- **`tson-annotation`** — `@Typename`/`@Field`/`@Record`, the binding annotations, plus `Annotations`/
  `Annotation`, the wire-annotation carrier a bound class declares a component of. The carrier lives here
  rather than with the engine because it is the one module `tson-bind` (which analyses classes),
  `tson-schema` (whose `schema.meta` model is itself a bind target) and consumer code all see.
- **`tson-bind`** — the generic `DataValue`↔Java-object binding engine (`DataBindContext`, `DataClass`
  descriptors, `DataNameBinder`, bridges). Depends only on `tson-annotation`, whose annotations and carrier
  types it reads off a class under analysis.
- **`tson-schema`** — **only** `io.ltr8.tson.schema.meta` (the resolved-schema *value* model — pure
  records/sealed interfaces/enums, §8's `TypeDefinition` et al.) plus the schema registry (`TsonSchemaRegistry`
  /`TsonLinkedSchema`/`TsonSchemaLoader`/`TsonCanonicalIdentity`) and `TsonBundledSchemas`. **The linker is not
  here** — it is an engine, not a value model, so `TsonSchemaLinker`/`ChoiceDisjointness` live in
  `tson-compiler` with the rest of the pipeline; what stays is storage and the identity algorithm lookups
  compare by. Depends only on `tson-annotation`. **`tson-compiler` depends on `tson-schema`, not
  the reverse** — the opposite of what the names suggest, deliberately so the compiler's resolver can hold
  and consult `schema.meta` types directly. `schema.meta` names no `tson-compiler` type; where it needs
  one structurally it declares a local stand-in (`schema.meta.Token` mirrors `ast.TokenValue`/`TokenForm`;
  `schema.meta.SourcePosition` is an interface `tson-compiler`'s `Position` implements), converted at the
  one spot that needs it.
- **`tson-tree`** — **only** `io.ltr8.tson.tree` (the data-document *value* model — `TsonValue` and its
  pure immutable node types, structure-preserving and query-ergonomic, the read output of tree mode). A
  true leaf: depends on **nothing** (not even `tson-annotation` — the nodes aren't bind targets, they're
  assembled by hand-written readers). The data-tree counterpart to `tson-schema`'s `schema.meta`: same
  "pure value model in its own module, engine depends on it not the reverse" shape, so JPMS keeps the tree
  from ever coupling to compiler internals. `tson-compiler` depends on it; it names no `tson-compiler` type.
- **`tson-regex`** — **only** `io.ltr8.tson.regex`: a native RFC 9485 I-Regexp engine — `TsonRegex.parse`
  builds a `RegexNode` AST (or `TsonRegexSyntaxException`), `TsonRegex.matches` runs a Thompson-NFA/Pike-VM
  simulation (linear-time, no backtracking → ReDoS-safe; `\p{…}` via JDK `Character.getType`), and
  `TsonRegex.isDisjointFrom` decides whether two patterns share any string (exact — a symbolic product-NFA
  emptiness check over a `CodePointSet` interval algebra, the building block for §5.4 pattern disjointness).
  A true leaf — depends on **nothing**, I-Regexp being an external standard, not TSON-specific. The
  *engine* counterpart to `tson-bind` (a general dependency-free engine), not a value model like
  `tson-tree`; TSON pins its `regex` atom to I-Regexp (`regex_type`'s `REQUIRED_FIXED spec = rfc9485`), so
  this owns I-Regexp semantics rather than delegating to `java.util.regex` (a laxer superset).
  `tson-compiler`'s atom vocabulary depends on it; it names no `tson-compiler` type.
- **`tson-compiler`** — the engine: lexer, both grammars, base type resolution, the atom vocabulary,
  schema resolution, Class 2 compilation, the compiled reader stack, the schema-aware read facades
  (`TsonTreeReader`/`TsonObjectReader`) over their schemaless `reader`-package engines
  (`SchemalessTreeReader`/`SchemalessObjectReader`), the `TsonTreeWriter`/`TsonObjectWriter` writers, and
  config/wiring. Everything here is tightly coupled to the shared lexer/token-stream machinery, so it's
  one module. Root package `io.ltr8.tson.compiler`; exports the packages with real cross-module callers
  and keeps `reader`/`atom`/`base`/`lexer` internal.
- **`tson`** — the small front-door module (`Tson`/`TsonConfig`) over `tson-compiler`, the way Retrofit
  sits on OkHttp. Declares `tson-compiler`/`tson-schema`/`tson-bind`/`tson-tree` as `api` so a caller sees
  the real classes underneath.
- **`tson-cli`** — the `tson` command-line application. Depends on nothing depending on it (exports
  nothing).

**JPMS enforcement is real, not just convention.** An unexported package is genuinely unreachable from
other modules (verified by scratch-importing across a boundary and watching it fail). Internal dispatch
types kept in unexported packages but referenced by a public method signature produce an accepted
`-Xlint:exports` warning (e.g. `ValueReaderFactoryResolver`); this is deliberate, not a defect. No `opens`
directives — binding only ever touches public constructors/methods.

## Pipeline

The schema pipeline is **parse → desugar → resolve → link → register → compile → read**; the class
vocabulary follows it (`TsonSchemaParser`, `SchemaDesugarer`, `TsonSchemaResolver`, `TsonSchemaLinker`,
`TsonSchemaRegistry`, `TsonSchemaCompiler`, `TsonTypeReader`). Data documents (Class 1, no schema) run the
shorter lex → parse → base-type-resolve path. One paragraph per phase below; the depth is in the `docs/`
note named at the head of each.

### Lexer (`tson-compiler/.../lexer/`) — `docs/lexer-and-data-parsing.md`

`Lexer` is a single hand-written scanner producing `Token`s off `nextToken()` (never a batch), **complete
and frozen for the whole series** (§1.3). Constructed from an `InputStream`, code-point addressed (never
char-addressed), with `Position` tracking line / code-point column / UTF-8 byte offset. NFC normalization
applies to *unquoted* tokens only; Pattern_White_Space is the spec's fixed 11-character set, hardcoded.
`Character.isUnicodeIdentifierStart/Part` stands in for XID_Start/XID_Continue — a known, deliberate
approximation. Errors are fail-fast (`LexException`); multi-error recovery is deferred.

### Structural parsing: Tier 2 stream + Tier 3 AST — `docs/lexer-and-data-parsing.md`

One implementation of the data grammar, split by role: **`TsonDataStream`** (Tier 2) is the only thing that
walks source text — a lazy pull-based `TsonEventSource` over a sealed `TsonEvent` hierarchy, frame-stacked,
at most two tokens of lookahead; **`TsonDataParser`** (Tier 3) reduces the event sequence into the sealed
`CoreValue` AST and holds no grammar logic of its own. Whitespace is gone by token time — adjacency (§7.5)
and separators (§2.4) are checked via `Position` gaps. The layering is deliberately incomplete per §1.2:
neither tier dedupes fields/keys, resolves `EmptyBrace`, or interprets token text — those belong to later
layers. `!!meta` in the header throws `TsonUnsupportedDocumentException`, not `TsonParseException` (a
schema document is unsupported, not malformed).

### Base type resolution (`.../base/`) — `docs/lexer-and-data-parsing.md`

`BaseTypeResolver.resolve(TokenValue)` implements §4's fixed order (null → boolean → number → string) for
untyped tokens; `NumberGrammar.tryParse` recognizes the number production and extracts structure into
`NumberForm` **without** converting to a host type — binding decides the host type and enforces the
`255`/`0xFF` equivalences. Quoted tokens always resolve to `StringValue` (§4.4); form is consulted once,
here.

### Built-in atom vocabulary (`.../atom/`) — `docs/lexer-and-data-parsing.md`

`AtomType<T>` is a built-in atom's parsing contract; `BuiltinTypeVocabulary` is the fixed name→`AtomType`
table (§5). Each constructor splits into a constraint-values record in `schema.meta` (`IntegerType`, …)
plus a same-named `*Parser` in `atom` that holds one and does the work. Pattern facets stay `String`, not
`Pattern` — validated and matched via `tson-regex` (I-Regexp, ReDoS-safe), never `java.util.regex`.
`unit`'s three instances are three separate parsers dispatched on the declaration's own name
(`SPEC-FEEDBACK.md` #18).

### Schema grammar (`TsonSchemaParser`, `.../ast/schema/`) — `docs/schema-grammar-and-desugaring.md`

Parses a schema document body (Part 2 §12.1) into a `SchemaDocument`, grammar-only — no resolution, no
validation. `extends TsonDataParser` (same package) because §12.1 imports Part 1's grammar directly.
`SchemaMap.declarations` is a `LinkedHashMap` and duplicate names overwrite (grammar layer doesn't dedupe).
Two entry points: `parseSchemaDocument()` is fail-fast, `parseSchemaDocument(receiver)` reports each
declaration's syntax error and resyncs to the next.
Three spec defects are implemented per intent with `SPEC-FEEDBACK.md` entries (#14/#15/#16); the bracket
form is parsed twice per the spec's own overlapping productions (#31).

### Desugaring (`.../resolver/SchemaDesugarer.java`) — `docs/schema-grammar-and-desugaring.md`

An AST→AST rewrite between parsing and resolution: every sugar form (`[T]`, `[T; N..M]`, `(A | B)`) and
every generic application (`map<K, V>`) becomes a `!C value` construction — at declaration position it *is*
one; anywhere else it becomes an injected declaration plus a bare reference (**a known conformance
divergence** from §8.2's carried-structurally rule; `BACKLOG.md` has the account). So `DefinitionResolver`
only ever sees a bare reference or `!C value`. Routing is vocabulary-derived off the governing meta (which
is why the phase runs with the meta in hand), with a hand-written table for meta-kernel's own bootstrap;
`choice`/`tuple` take a variadic second path; a template application over a constructor is instantiated
into a `TemplateInstance` (record templates are rejected — real §5.10 substitution is unimplemented).
Bottom-up, so nesting needs no special case; injected names are `head_args_hash`, so structurally identical
applications collapse. Invalid sugar forms report per declaration via `DesugarFailureReporter` rather than
throwing.

### Schema resolution (`.../resolver/`) — `docs/schema-resolution.md`

`DefinitionResolver` (package-private) turns one declaration into a resolved `schema.meta.TypeDefinition`;
`TsonSchemaResolver` (public) resolves a whole `SchemaDocument`, merging `!!import` entries into the
namespace first. Namespace dependencies are constructor-fixed functional interfaces. Everything §5 defines
resolves — composition, refinement (`^`), constructor application (bound generically via the compiled meta
reader, no name→class table), atom refinement (which **merges with its source** via a `TsonObjectWriter`
round-trip and is checked to genuinely narrow), subtraction (which empties `type_definition.supertypes` on
purpose), group restatement, all six field-state spellings. Every atom body is checked twice over, by two
per-family rules asking different questions: `Atom.constraintsCheck` (over `AtomNarrowing`) that a refinement
tightens its source, and `Atom.coherenceCheck` (over `AtomCoherence`) that a single body's own facets admit
anything at all — `{ min: 10 max: 3 }` is the second one's, and meta.tn's own `@doc` calls it "a schema-load
check". The exception-classification policy under Conventions governs every rejection here;
`DefinitionResolver`'s Javadoc lists the exact boundary.

### Meta-kernel bootstrap (`MetaKernelBootstrapResolver`) — `docs/schema-resolution.md`

Meta-kernel's `!!meta` names itself (§1.5's one deliberate circularity), so ordinary resolution can't
bootstrap it. `getMetaKernelSchema()` resolves it in **two passes** (non-`Instance` declarations first,
deferred `Instance` declarations second) with a closed `instanceBody` switch instead of a compiled reader,
and `BOOTSTRAP_CONSTRUCTORS` hand-writes the desugar routing for the three constructors meta-kernel applies
to itself. The payoff: meta-kernel's linked form needs no materialization.

### Registry and linking (`TsonSchemaLinker`, `tson-schema/.../registry/`) — `docs/linking-and-compilation.md`

`TsonCanonicalIdentity.canonicalize` is §2.2.1's algorithm (exactly two reductions — strip scheme, strip
query — everything else must already be canonical), public API because `TsonSchemaLoader` keys on it.
`TsonSchemaLinker.link(schema, loader)` merges `!!import`s, populates `subtypes`, derives choice
`disjoint` (`ChoiceDisjointness` — total and two-valued: `true` iff every variant occupies a distinct
discrimination class, the same `DiscriminationClass` untagged reading dispatches on; `SPEC-FEEDBACK.md`
#47), and validates every reference — including choice-variant
distinctness after §8.3 flattening, rejection of a variant resolving to `void` (optionality is not choice,
`SPEC-FEEDBACK.md` #48), the author's `@disjoint` marker against the derived fact (`false` is
an error; no third outcome exists), and
constructor eligibility from both ends (§2.2.2, `SPEC-FEEDBACK.md` #19). The linker materializes nothing —
desugaring already did. `TsonSchemaRegistry.register` rejects duplicate identities (no overwrite: that plus
unmodifiable `entries()` *is* the "locked" guarantee). The linker lives in `tson-compiler` (a pipeline
stage, next to `Diagnostic` and `tson-regex`); the registry stays in `tson-schema` (storage over the value
model).

### Class 2 compilation (`TsonSchemaCompiler`, `.../reader/`) — `docs/linking-and-compilation.md`

`compile` turns a `TsonLinkedSchema` into a `TsonCompiledSchema` — one `TsonTypeReader` per entry, wired as
real Java references, **eager** so a broken entry surfaces at compile time. `TsonTypeReader<T>` is strictly
one method, `T read(TsonReadContext)`; framing and error policy live elsewhere. A `RuntimeException` while
building one entry becomes an `ErrorReader` (the schema compiles; reading that entry fails — a library-gap
marker, distinct from author errors). `TsonCompiledSchema` is `sealed permits TsonCompiledMetaSchema` (a
meta-layer schema can govern others). Two compile modes (governed / standalone) share one walk; two output
modes (tree / bind) share each `*AbstractReader` family, selected by which factory registry you hold.

### The compiled registries — `docs/linking-and-compilation.md`

`TsonCompiledMetaRegistry` is the shared meta/resolution core: compiles and caches **only** meta-layer
schemas, resolves/links/registers everything else (`resolveLinked`) without compiling it, owns content-hash
verification and the bootstrap. `TsonCompiledSchemaRegistry` (`dom(core)` / `bind(core, context)`) is a
per-mode registry of compiled user schemas — **the read mode is which registry you hold**, not a compile
parameter. Resolution is always bind-anchored (meta instances bind to `schema.meta.Top`), so every read
registry shares the one bind-mode core; core.tn is never compiled in the core, only inline in a read
registry.

### Streaming readers and read context — `docs/readers-and-diagnostics.md`

Every compiled reader pulls `TsonEvent`s through `TsonReadContext` — no reader requires a materialized
tree. The context holds **no error policy**: `report(...)` hands a `Diagnostic` to the read's
`TsonDiagnosticsReceiver` (`throwing()` / `collecting()` / caller's own), and readers ask `reported()` (a
count) when they need to know whether children complained. Load-bearing read rules, each detailed in the
note: a stated FIXED value is checked, not obeyed; an omitted `OPTIONAL_FIXED` field stays absent where
`REQUIRED_FIXED` injects (#39); collecting mode always keeps reading; **bind mode is all-or-nothing
(`ConstructionGuard`) while tree mode keeps everything it built** — deliberate asymmetry, not
inconsistency; records are closed under their type (§7.2, `UNRECOGNIZED_FIELD` — the same line polices
schema authoring through the meta's compiled reader); repeated fields/map keys are errors (#41/#42) with
last-value-wins recovery underneath; map-key identity is the decoded host value, type-ref and annotations
stripped (#43); a written `_` at `REQUIRED_DEFAULT` is an error where omission injects silently.

### Diagnostics — `docs/readers-and-diagnostics.md`

`Diagnostic` (root package) is one record for both data- and schema-side problems — the variation is
locational, not categorical: a closed `Code` enum, `message`, `expected`/`actual`, and four location
components matching JSON Schema 2020-12 §12's output unit (`path`, `schemaId`+`schemaPointer`, plus
`dataPosition`/`schemaPosition`). Both RFC 6901 pointers are `Optional<String>` because `""` is the *root*,
a location this really emits, not an absence. `expected` carries the **constraint that failed** — `<= 100`,
`one of (A, B, C)` — from `AtomTypeException`'s six-shape vocabulary, never the type's name; the name leads
`message` instead. The base-syntax exceptions keep their position out of `getMessage()` (it is in
`position()`, and in `toString()` for a stack trace) so a diagnostic states it once.
Schema-side reporting runs through the same receiver: `TsonSchemaParser`,
`SchemaResolver` and `TsonSchemaLinker` have reporting overloads that collect every independent problem in
one pass (a failed declaration leaves an answer-everything placeholder, javac-style), while
`Tson.validateSchema` owns the phase boundary — resolution runs only if the document parsed whole, linking
only if resolution was clean, and a schema that reported anything is never registered. A schema *syntax*
error reports per declaration too (`Diagnostic.ofSchemaSyntaxError`, located at the schema end, resyncing on
`name =>` at schema-map depth), naming the **construct** the position admits rather than the token class —
and the recovering parse hands back no document at all, since resolving a half-document reports every
reference to a dropped declaration on top of the real error. Namespace-level failures (unloadable
`!!import`, ineligible `!!meta`, `!!id` cross-check) still throw even with a receiver. Compilation, and the
lexer under everything, are still fail-fast.

### Read facades and writers — `docs/facades-and-tree.md`

`TsonObjectReader` (bound Java object) and `TsonTreeReader` (`TsonValue` tree) are the whole
document-reading surface, dual-mode fixed at construction: standalone = schemaless (Class 1,
Jackson-style); from a `Tson` facade = schema-aware (a self-describing document validates against its
`!!schema` as it's read). Jackson-`ObjectReader`-style derivation (`withDiagnostics`, `withSchema(uri)`,
`preservingUnknownTypeRefs`) keeps source form / error policy / schema selection orthogonal; derived
readers share the original's compiled-schema registry. Failures reaching or resolving the schema are
diagnostics, not exceptions. A schemaless read still checks type-refs (`TypeRefCheck`: built-in name →
must satisfy the atom; names-the-target → accepted, bind only; else `UNKNOWN_TYPE_REF` — a reader policy,
`SPEC-FEEDBACK.md` #7). Both tree paths capture wire annotations; a schema-driven read also type-checks
annotation *names* against the governing schema (#29). `TsonTreeWriter`/`TsonObjectWriter` re-emit
annotations in §7.4 order; `toTson` is mainly a debugging tool with documented losses. These live in
`tson-compiler`'s root package because `DefinitionResolver` depends on `TsonObjectWriter`.

### Tree model: `TsonValue` (`tson-tree`) — `docs/facades-and-tree.md`

A sealed `TsonValue` over seven pure immutable node types (`TsonRecord`/`TsonMap`/`TsonArray`/`TsonTuple`/
`TsonAtom`/`TsonAbsent`/`TsonMissing`), structure-preserving and annotation-aware. No `Node`
suffix (deliberate, against Jackson's names). `get`/`at` never throw — a `TsonMissing` carries the RFC 6901
pointer of the step that failed. **One no-value node, no separate null node**: `TsonAbsent` carries `_`, the
`null` token where §4 base resolution applies (schemaless data and `value` positions), and a collecting-mode
read failure. Under a schema `null` stays ordinary text — §7.3's concession is local to `void`, and lives in
`VoidReader`, never in the lexer or `TsonDataStream`.
Two accessor families with different questions: `as(Class)`/`asString`/…
**cast** ("what host type did the read produce?"), `asInt`/`asLong`/`asDouble` **convert** ("what number is
this?") — a test asserting which host type a reader produced must use `as(Class)`. Read-side only; no
builders or transforms yet.

### Front door: `Tson`/`TsonConfig` (`tson` module) — `docs/facades-and-tree.md`

`Tson.builder().build()` bootstraps meta-kernel/meta.tn/core.tn and returns an immutable `Tson`.
`resolve(schemaText)` registers a schema by its own `!!id` (no mode — resolution is always bind-anchored);
`treeRegistry()`/`bindRegistry()` pick the read mode; `objectReader()`/`treeReader()` return schema-aware
facades sharing this instance's registries, so a schema compiles once per `Tson`.
`validate(String|InputStream)` *is* `treeReader()` with a collecting receiver — returns `List<Diagnostic>`
(empty = valid) and never throws for a bad input document (a library fault still throws, deliberately).

```java
Tson tson = Tson.builder().build();
tson.resolve(schemaText);                      // registers the schema by its own !!id
TsonValue value = tson.treeReader().withSchema(schemaId).readAs(dataText, "my_type");
```

### CLI, config, bundled schemas, hashing — `docs/cli-config-hashing.md`

`tson validate [--output text|json|tson] <file|->...` auto-classifies a flat file list into schemas (by
embedded `!!id`, never filename) and data, and validates each data document via `Tson.validate` — fully
self-describing, no `--type`; `-` is stdin, at most once, always data. One `ValidationRun` envelope per
invocation. **Exit codes: 0 all valid, 1 any data file invalid, 2 usage/classification, 70 a library
fault** — the 1/2/70 split is load-bearing and rides on the exception-classification policy. Also `tson
compile`, `tson hash` (stamps a `?sha256=` pin idempotently), `tson init-example`.
`TsonBundledSchemas` serves the three bundled schemas' identities, text (copied from `spec/m/` at build
time) and published digests. `TsonContentHash` hashes every byte past the `!!id` line; pins are
verification metadata, not identity, checked through the loader on every fetched pinned reference. The
`config` package holds the two default bind contexts (consumer vs. internal `schema.meta` resolution),
differing by exactly the name binder.

## Traps — read before touching the class involved

Hard-won invariants that look like cleanup targets or are easy to break silently. Each is documented at
the class and (where noted) pinned by a test; the `docs/` notes carry the full why.

- **`TypeArgument` is a sealed interface (`Ref`/`Value`), never a plain record.** `TypeRef`/`TypeArgument`
  are mutually recursive and `tson-bind`'s record binder has no cycle protection — a plain record
  `StackOverflowError`s on first bind. Re-read its Javadoc before touching it.
- **`SchemaDesugarer` returns un-rewritten nodes by identity** — `declarationPositions()` is an
  `IdentityHashMap`, so an equal-but-rebuilt `Declaration` silently loses its source position and its
  diagnostics. `SchemaDesugarerTest` asserts `assertSame`.
- **`requireDocumentEnd`: the pull is the point, not the assertion after it.** Nothing fails if you simply
  stop reading a lazy `TsonDataStream`; pulling past the root value is what makes trailing content get
  rejected. Javadoc on both facades.
- **Lexer multi-line closing-delimiter detection strips leading whitespace *before* comparing against
  `"""`.** Backwards, every multi-line token is spuriously "unterminated". Happened once; guarded by
  `LexerTest`.
- **Never put literal BOM/NEL/LS/PS characters in source or tests** — use `\uXXXX` escapes; the invisible
  character is an editing hazard (§9.4's confusable risk).
- **`CompiledReaders` is rebound exactly once, from the in-progress `Compilation` to the finished
  `TsonCompiledSchema`** — handing readers `Compilation::resolve` would leak its mutable state past the
  compile. `CompiledReadersTest` pins the handover.
- **`verifyFixed` compares with the pre-rebind parser (`FixedCheck`)** — bind mode narrows
  `precomputedValue` in place, and comparing across that narrowing flags every conforming document.
- **A `schema.meta` bind target with more than one public constructor needs `@Record` on the canonical
  one**, or `DefaultRecordBinder` throws (`IntegerType`/`IntegerSize` hit this).
- **An atom body's components must mirror its constructor's *resolved* shape, not the composition that
  produced it** — every field flat, one component per schema field name. Composition flattens (§5.8) and a
  compiled `Record*Reader` fills a field, including a `REQUIRED_FIXED` field's schema-composed default,
  under its own schema field name, so a component nesting one (`specification: AtomSpecification` for
  `spec`) or omitting one silently binds `null` rather than failing. `UriType`/`RegexType` did both for a
  long time, invisibly, because their tests asserted against hand-written `UNCONSTRAINED` constants and
  `MetaKernelBootstrapResolver` hands those same constants back — only a schema resolved through the
  compiled meta reader shows it. `DefinitionResolverTest.resolvesRegexAndUriInstancesWithEveryComposedFieldBound`
  is the guard.
- **A desugar-reported declaration is replaced with `ABSORBED`, never passed through** — passing it through
  hands `DefinitionResolver` the very node the phase removes and turns a reported author error into an
  unreported abort. Injected declarations are never rolled back (later declarations may already reference
  them).
- **Atom refinement's `TsonObjectWriter` round-trip has no cheaper substitute** — the merge must run on the
  wire record before binding, or `REQUIRED`-no-default constructor fields fail `FIELD_REQUIRED`
  (`DefinitionResolverTest.atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed`). This dependency is
  also why the writers can't move out of `tson-compiler`.

## Conformance suite (`ConformanceSuiteTest`)

Separate from the fine-grained unit tests, this runs every vector in the sibling
[ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite) repo against the real
`Lexer`/`TsonDataParser` as JUnit 5 dynamic tests — a conformance/integration check against an external,
language-agnostic, spec-derived fixture set, to catch drift against the spec. It assumes the sibling repo
at `../../ltr8-io-tson-test-suite` and skips gracefully (via `Assumptions.assumeTrue`, reported *aborted*)
if absent. CI doesn't check the sibling out, so it always shows skipped there — expected. **Add
test-suite vectors in the same session as any lexer/parser/resolver work**, not after a nudge — with one
standing exception: the suite's `resolver` layer is Part 1 *base-type* resolution, and there is **no Part 2
layer at all** (no schema-resolution, linking or compilation vectors, and no sidecar schema for them). Part 2
work therefore has nowhere to put a vector today, and the honest move is to say so rather than wedge one into
a Part 1 bucket. Opening that layer is its own `BACKLOG.md` item.

## Build and test

No system Gradle — always use the wrapper:

```
./gradlew build
./gradlew test
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.lexer.LexerTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonDataParserTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.ConformanceSuiteTest"  # skipped unless ../../ltr8-io-tson-test-suite exists
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonSchemaLinkerTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonCompiledSchemaRegistryTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.resolver.DefinitionResolverTest"
./gradlew :tson-cli:installDist   # then tson-cli/build/install/tson/bin/tson validate ...
```

`BACKLOG.md` tracks the actively-maintained engineering backlog; `SPEC-FEEDBACK.md` records spec issues;
`STRUCTURED-OUTPUT.md` holds the target-use-case plan (LLM structured-output validation, JSON
compatibility).

## Not yet implemented

- **An array's element `?`** (`[T?]` — §5.3's `state: OPTIONAL` on the resolved array, absent elements
  occupying positional slots) — `SchemaDesugarer` builds no array position carrying one, so the declaration
  reaches `DefinitionResolver` as a `ContainerTypeDef` and throws, and so does any container enclosing it.
  The last piece of §5.3's declaration-level container syntax: the flat forms, the tuple position `?`, and
  nesting are all done.
- **Part 2 resolution gaps** — the identity-diagonal
  FIXED-value invariant, a generic type-ref whose argument is nested or a value rather than a plain name,
  and a parameterized supertype (`customer & box<T>`, which needs §5.10 substitution into the absorbed
  fields and so belongs with the item below). `DefinitionResolver`'s Javadoc is the exact current boundary,
  and `BACKLOG.md`'s "Remaining Part 2 resolution gaps" carries the full list — an audit of the ~34
  `UnsupportedOperationException` sites found nine genuine gaps that had no item, and that only about half
  of those throws are gaps at all: the rest are schema-author errors, or internal faults, wearing the
  wrong exception type. Six of the nine have since been reclassified rather than implemented; the
  composition path in particular turned out to be one real gap, not the five the backlog listed.
- **§5.10 parameter substitution into a template *body*** — a template that refines a constructor
  (`array_ranged`, and so §5.3's sized sugar) instantiates via argument routing; a *record* template
  (`box => <T> { v: T }`), whose parameter is a field type, is rejected at the application site instead.
  `BACKLOG.md` has the shape of the remaining work.
- **Undocumented atom constructors** — `unknown` (and `extern`, which has no core.tn declaration) has no
  compiled-parser factory, so it compiles to `ErrorReader` (a schema merely *declaring* one still compiles).
  Neither is an ordinary missing parser waiting to be written: `extern` is a whole absent mechanism and `unknown`
  is the universe of types, not a token shape. `complex`/`ipv4`/`ipv6`/`cidr4`/`cidr6`/`mac`/`email` do have
  parsers — the CIDR pair reusing the two address grammars, and validating §5.5's family-range and
  host-bits-zero rules on top. **`email` is
  seeded into `BuiltinTypeVocabulary` although §5.5's table has no row for it** — a known departure like the
  integer ladder, because core.tn groups it with its siblings identically and withholding it would only make
  the two read paths disagree (`SPEC-FEEDBACK.md` #5). Its format check is a documented subset of RFC 5322 —
  the `dot-atom` core, without quoted local parts, domain literals or comments.
- **Schema-side diagnostics, the remainder** — parsing, desugaring, resolution and linking all report
  through a `TsonDiagnosticsReceiver` now (see `docs/readers-and-diagnostics.md`); one thing is left.
  **A read-path diagnostic carries `schemaPosition` but no `schemaId`/`schemaPointer`**, which is blocked
  upstream of the reader stack: `mergeImports` discards which schema an imported entry came from, so the
  identity a reader could reach is the importing schema's, not the declaration's own. Throw-site
  classification is done across the whole schema pipeline. The lexer stays fail-fast on purpose and is the
  floor under schema-parse recovery — not a tracked gap; `STRUCTURED-OUTPUT.md` holds the open question.
- **Deferred design questions** — `REQUIRED_FIXED`/`OPTIONAL_FIXED` value validation, `value_param` real
  parameter substitution, thread-safety, and a general disk/HTTP-backed `TsonSchemaSource` (with
  whitelist/blacklist policy).
- **§9.1's numeric-literal length limit** (SHOULD, DoS-hardening) — not enforced.
- **JSON** — a future JSON reader is a whole separate stack (its own `JsonEventStream` and its own readers,
  deliberately not reusing the TSON readers). Not started, not backlogged.
