# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

---

## Tree model (`TsonNode`)

The `TsonNode` tree — an immutable, queryable, structure-preserving document model in its own pure-leaf
`tson-tree` module (`io.ltr8.tson.tree`) — is **built**: the node model + query API, both producers (the
schema-driven tree readers and the schemaless `TsonTreeReader`), the `TsonTreeWriter` back to text, and
the removal of the old throwaway `Map`/`List` DOM mode all landed. What's left:

- [ ] **Copy-on-write transforms + builders (parked).** The "new tree from old" editing half —
  `RecordNode.with(name, node)`/`without(name)`, `ArrayNode.with(i, node)`/`plus(node)`/`without(i)`,
  `RecordNode.builder()`, and a pointer-based `set("/a/b", node) → new tree`. All pure `tson-tree`
  operations (no compiler dependency), so they belong in that module. Deferred until there's a concrete
  produce/edit use case: `TsonTreeWriter` already closes the read→edit→write loop, so these have a real
  payoff when wanted, but block nothing now.
- [ ] **Wire-annotation capture in both read paths.** Every node's `annotations()` is empty today —
  neither the schema-driven readers nor `TsonTreeReader` capture `@name`/`@name: value` wire annotations
  (matching `TsonObjectWriter`/`TsonObjectReader`, which don't carry them either). Capturing them on the
  node, and having `TsonTreeWriter` re-emit them, is the annotation-aware half the model was designed for
  but doesn't yet exercise.

## Front door / ergonomics

- [ ] No `!!schema`-header auto-selection on the data side — given a data document, there's no
  "find the right compiled reader yourself" entry point; a caller always has to already know what
  schema position it's reading against.
- [ ] A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy — today the only
  `TsonSchemaSource` is `TsonSchemaSource.registeredOnly()` (nothing fetched); the bundled standard
  library is served internally by `TsonCompiledSchemaRegistry` from `TsonBundledSchemas`, not through
  a source.

## Layer boundaries / schema registry

- [ ] Add the missing other half of `TsonSchemaLinker`'s existing "constructor eligibility" check —
  a resolve/link-time *diagnostic*. That check already restricts *where* `constructor: true` can be
  declared (only a meta-kernel-governed schema, per §2.2.2); the missing half is restricting *where a
  schema can be used as a `!!meta` target* the same way, so an ordinary consumer schema can't
  accidentally govern another one, surfaced by the linker (with the spec's own wording) at
  resolve/link time. The 2026-08-02 `TsonCompiledSchemaRegistry` cleanup arc closed the *type-level*
  half of this — the meta/non-meta split, so a non-meta schema won't type-check where a governing
  meta is required — and now rejects a non-meta `!!meta` target at load time via
  `loadMeta`, but the linker-level validation itself is still absent.

## Resolution & linking generality

Every real schema resolved so far (meta-kernel, meta.tn1, core.tn1, and hand-built test fixtures)
happens to fit a narrow shape this pipeline already handles — declared in dependency order, with
callers hand-sequencing registration themselves. These items are what's missing for the *general*,
spec-required case, found by re-auditing Part 2 against the current source rather than CLAUDE.md's
own prose (which had gone stale on at least one of them):

- [ ] **General forward-reference resolution within a schema** ([TSON-SCHEMA] §3.4.1's Pass 1/Pass
  2) — `DefinitionResolver`/`TsonSchemaResolver.resolveSchema` are single-pass, strict source order;
  a declaration referencing another declared *later* in the same schema fails to resolve, outside
  `MetaKernelBootstrapResolver`'s own hand-built two-pass ordering, which is specific to
  meta-kernel's own bootstrap and not something any other schema can rely on. `DefinitionResolver`'s
  own Javadoc already says as much ("real forward references... need the full namespace population
  of §3.3.2/§3.4.1's Pass 1, not implemented here") but this had never been carried into a tracked
  item.
- [ ] **Automatic reference-closure resolution and import-cycle detection** ([TSON-DATA] §2.2.3,
  [TSON-SCHEMA] §3.4.1) — no code collects a schema's transitive `!!meta`/`!!import` closure,
  topologically orders it, and resolves it dependencies-first; every caller (including this
  session's own `TinySchemaImportsCoreTn1Test`) has to already know and hand-sequence the correct
  registration order itself. A real import cycle is only caught incidentally today, as an opaque
  "not registered" error from `TsonSchemaLinker.mergeImports` — never with the spec's own required
  "import cycle" diagnostic naming the actual cycle path. Distinct from the "single load-stdlib
  entry point" item above, which is scoped to just the three bundled schemas, not a general
  algorithm.
- **Choice disjointness derivation and untagged reading** ([TSON-SCHEMA] §5.4, §8.1) — landed in part:
  - [x] **The `disjoint` fact is derived** (`TsonSchemaLinker`'s `computeDisjointness` →
    `ChoiceDisjointness`, a namespace-wide pass alongside `computeSubtypes`). Three-valued
    `Optional<Boolean>` — `true` proved, `false` provably-not, absent neither — over the spec's cheap
    exact rules: different kind disjoint; different atom family disjoint; same-family integers by bound
    interval; IS-A ⇒ not disjoint. Also landed: `TsonRegex.isDisjointFrom` in `tson-regex` (exact
    I-Regexp intersection-emptiness).
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
    error under `@disjoint`, never a silent flip). Wiring `isDisjointFrom` into the fact needs a
    dependency-inversion seam, since `tson-schema` deliberately doesn't depend on `tson-regex` — a
    pattern-disjointness oracle injected into the linker, supplied by `tson-compiler`. See
    `SPEC-FEEDBACK.md` #23 for the load-bearing ambiguity underneath all of this.
  - [x] **Reader-side untagged structural recovery (scalars).** `ChoiceReader` now drops the `!variant`
    tag where the choice is proved disjoint *and* every variant is a scalar of a distinct base-type class
    (the TSON-text separability predicate): it precomputes a `class -> variant` map (`BaseTypeClass`) and
    `NamedDispatchReader` recovers an untagged token by `ValueParser`-classifying it. To classify variants a
    factory needs its enclosing schema, so `ValueReaderFactory.create` gained a `ValueReaderContext` (schema
    + child-reader resolver) — a reusable "level up" seam. **Still open:** *non-scalar* structural recovery
    — a `{...}` record variant vs a scalar is distinguishable by wire shape, but this cut only handles
    base-type-class scalars; a choice with any non-scalar variant keeps the tag.
  - [ ] **The `@disjoint` assertion check** — an author's `@disjoint` marker checked against the derived
    fact: proved (silent), refuted / provably-not (resolver error), unprovable (warning), absent (no
    check). This is where exact regex-pattern disjointness (`isDisjointFrom`, via the seam above) pays
    off — turning an otherwise-"unprovable" pattern choice into a proved-or-refuted one.
- [ ] **Resolved-form ingest** ([TSON-SCHEMA] §8.1/§10.1) — bringing an already-resolved
  `!type_definition` document into the library (not source text), with its own integrity checks:
  `subtypes`/`disjoint` recomputed and verified, the closed-entry parameter-free rule reverified, an
  instantiation entry checked against its own `source` by recomputation, a construction's binding
  record checked for parameter-slot agreement with its `source` application. Entirely unimplemented
  — "ingest" doesn't appear anywhere in the codebase. Lower priority than the three items above:
  the spec marks this path explicitly **optional** ("MAY implement ingest"), not a MUST.

## Atom-refinement constraint validation

- [ ] **Atom-refinement merging never checks that a refinement actually narrows its source** —
  `DefinitionResolver.mergeWithSource` (chained atom refinement, `!I ^ { values }`) re-serializes
  `I`'s own already-bound constraint object to wire form via `TsonObjectWriter`, then merges it with
  the new refinement's own `values` field by field: `merged.put(field.name(), field)` — a plain map
  override, explicit values simply win, with **no check that the new value is actually a valid
  narrowing** of what it replaces. Concretely: `!uint8 ^ { min: -10 max: 300 }` is not rejected, even
  though `uint8`'s own real range is 0..255 — the "refinement" *widens* rather than narrows, directly
  contradicting §5.7's whole premise (refinement tightens, it never loosens), and nothing catches it
  today. The right fix, per the user's own direction: each constraint-vocabulary class (`IntegerType`,
  `TextType`, `DecimalType`, `FloatType`, ...) should own a method like `constraintsCheck(A, B)`
  returning whether `B` is a valid narrowing of `A` — the type itself is the only thing that actually
  knows what "more constrained" means for its own fields (an integer's `min`/`max`, a text's
  `min_length`/`max_length`/`pattern`, and so on) — called during merge instead of the current blind,
  generic override.
- [ ] **Related cleanup, once the above lands**: `DefinitionResolver`'s own dependency on
  `TsonObjectWriter` (`private final TsonObjectWriter writer`, used only for this merge's own
  re-serialization step) should go away — a real narrowing check wouldn't need to round-trip through
  the generic binder at all. This is also *why* `TsonObjectReader`/`TsonObjectWriter` still live in
  `tson-compiler` (its root package, since the 2026-07-31 rename/move out of `.mapper`) rather than
  the `tson` front-door module (see "Front door" above) — moving them to a module that depends *on*
  `tson-compiler` would create a cycle, since `tson-compiler`'s own resolution engine genuinely
  depends on the writer. Once this dependency is gone, revisit moving them into `tson` — noted
  directly on `Tson`'s own class Javadoc too, so it isn't lost.

## I-Regexp engine (RFC 9485)

TSON pins its `regex` atom to I-Regexp (RFC 9485): meta-kernel's `regex_type` fixes `spec` to
`…/rfc9485` (a `REQUIRED_FIXED` field), and every `text_type`/`uri_type` `pattern:` field is typed
`regex?`. **The engine is built** (the `tson-regex` module) and fully wired: `regex` values validate as
I-Regexp, and `pattern:` constraints (`TextParser`/`UriParser`) now match through it rather than
`java.util.regex`, so this implementation defines I-Regexp semantics end to end.

- [x] **Piece 1 — parser + AST + subset validator.** Landed as the `tson-regex` module
  (`io.ltr8.tson.regex`): `TsonRegex.parse` → a `RegexNode` AST (or `TsonRegexSyntaxException`), a
  recursive-descent parser over the RFC 9485 ABNF whose grammar *is* the subset gate (`\d`/`\w`/`\s`,
  subtraction, back-references, lookaround, Unicode blocks all rejected), and `RegexParser` wired to it.
  The owned AST also unblocks **choice disjointness over `regex`-constrained atoms** ([TSON-SCHEMA] §5.4's
  "pattern disjointness", see "Resolution & linking generality") and the **Tier 2 constrained-decoding
  backend** (`regex` atom → its own automaton, `STRUCTURED-OUTPUT.md`).
- [x] **Piece 2 — Thompson-NFA matcher.** `TsonRegex.matches` compiles the `RegexNode` AST to a Thompson
  NFA and runs a Pike-VM simulation — full-match, linear-time, no backtracking, so no ReDoS blow-up (a
  `(a+)+b` that hangs a backtracking engine runs linearly; proven in `TsonRegexMatchTest`). `\p{…}` maps
  each `RegexCategory` to `Character.getType` (own the semantics, borrow the JDK's Unicode data, the same
  XID split the lexer makes). `TextParser`/`UriParser` `pattern:` matching is wired to it, replacing
  `java.util.regex` — closing the `SPEC-FEEDBACK.md` #22 non-conformance for both well-formedness and
  matching. (Remaining minor items: `TextParser`/`UriParser` re-parse the pattern per value rather than
  caching a compiled `TsonRegex`; a very large bounded quantifier `{0,N}` expands linearly in program size,
  capped at 200k instructions. Add I-Regexp `regex`-atom vectors to the sibling test-suite when it's
  checked out, per the test-suite habit.)

## Remaining built-in types

- [ ] `cidr4`/`cidr6`/`email`/`mac`/`unknown` — no compiled-parser factory yet
  (`ValueReaderFactoryRegistry` registers these constructors to `ErrorReader`). Pinned down exactly
  by `CoreSchemaImportTest.exactlyTheFiveUndocumentedAtomConstructorsCompileToErrorReaders`.
- [ ] `uri_type`/`regex_type` — don't bind correctly in object-binding mode. Their RFC-citation
  field is nested inside `specification: AtomSpecification` rather than flat, so it never receives
  a schema-composed default the way `email_type`'s own flat `spec` field does.
- [ ] `extern` ([TSON-SCHEMA] §7.8) — materially bigger than the four items above, which just need
  an ordinary atom parser. `Extern` (`schema.meta`) is a record-only placeholder with no
  parsing/validation behavior at all (its own Javadoc says so explicitly: "not to add real
  cross-schema reference resolution"); the real mechanism — a value at an extern-matched position
  carrying its own scoped `!!schema` plus a mandatory `!type` tag, switching schema scope
  mid-document — doesn't exist anywhere in the reader stack.

## Remaining Part 2 resolution gaps

- [ ] Subtraction.
- [ ] Elided field types outside a tightening entry.
- [ ] Restating a field group in a refinement body.
- [ ] Generic type-refs beyond a bare two-argument `map<K, V>` application or a refinement source.
- [ ] `= _` (absent) field modifier, and any `~`/`=` modifier on an already-`OPTIONAL` field —
  `DefinitionResolver.resolveField` rejects both today.
- [ ] Closed-entry parameter-free check (§5.10) — nothing validates that an entry with an empty
  `parameters` list truly contains no parameter references (`value_param` members, or a reference
  name resolving to a parameter) anywhere in its body, at any depth. Distinct from `value_param`
  *substitution* (tracked in `STRUCTURED-OUTPUT.md`) — this is a rejection rule for a malformed
  "closed" entry, not the substitution mechanism itself.
- [x] **`!choice { variants: [...] }` construction (§5.4) now resolves.** The bug: each bare variant
  binds to a positional-form `type_ref` whose OPTIONAL `arguments: [type_argument]?` field is absent,
  which the binder faithfully represents as `null` — and `schema.meta.TypeRef`'s constructor NPEd on
  `List.copyOf(null)`, so `bindAtomInstance` rewrapped it as an `UnsupportedOperationException` and no
  choice (this project's own or a consumer's) could be declared at all. Fixed at the source: `TypeRef`
  already documents "empty means no `<...>`" (it conflates absent and empty — there is no wire form for
  present-but-empty arguments), so its constructor now normalizes `null` arguments to the empty list,
  rather than a global binder policy that would reinterpret every `[T]?` as `[T; 0..]`. Regression:
  `ChoiceConstructionResolutionTest`. **Still open:** the `|` choice sugar (a `ChoiceRef` AST node) is a
  separate path `DefinitionResolver` doesn't handle at all yet — only the explicit `!choice { ... }`
  construction form resolves.

(All already named in `DefinitionResolver`'s own Javadoc and `CLAUDE.md`; carried here so
everything outstanding is tracked in one place.)

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has only the two schemaless writers and is
missing most of the mirror.

- [ ] **No schema-aware (Class 2) writer — `TsonValueWriter`.** Only the schemaless `TsonObjectWriter`
  (object → TSON) and `TsonTreeWriter` (`TsonNode` → TSON) exist, both with documented lossy spots
  (integer width, tuple-ness, `@Annotated`-captured wire-format annotations). A writer symmetric to the
  compiled reader stack (`TsonSchemaCompiler`/`TsonValueReader`) — checking output against a TSON schema
  and reporting what's wrong — is a whole missing half of the pipeline, and the natural home for
  round-tripping or producing guaranteed-conformant documents.
- [ ] **Writers are fail-fast only, no diagnostics.** They throw `TsonWriteException` at the first
  problem; there's no collecting mode symmetric to `TsonReadContext.collecting` → `List<Diagnostic>` on
  the read side. The `TsonValueWriter` above especially needs this, to report every schema violation in
  one pass the way the reader does.
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
  - `!choice { variants: [...] }` — doesn't resolve at all today (see the new item above); even once
    fixed, a schema built on it typically still needs a `!variant` tag or a discriminator convention
    to disambiguate on read (`ChoiceReader`'s own "no structural-recovery path" gap, "Resolution &
    linking generality" above) — not free once the construction bug itself is fixed.
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
- [ ] `@doc` annotations aren't carried through resolution into `TypeDefinition` at all right now —
  worth preserving if user docs/tooling will ever want to generate documentation from a schema,
  rather than bolting it on later and revisiting every resolution path again.
- [ ] The README entry-point table's schema rows say "Use `TsonValueReader`", but a caller never
  constructs one directly — they go `Tson.builder()` → `resolve` → `treeRegistry()`/`bindRegistry()` →
  `compile` → `get(type)`. The "Use" column names the interface you get *back*, not the thing you use.
  Best fixed by the self-describing read entry point (see *Front door / ergonomics*): a real `Tson.read`
  would be the honest single entry the table could then name.

## Miscellaneous

- [ ] Thread-safety — currently only `synchronized` on `TsonSchemaRegistry`/
  `TsonCompiledSchemaRegistry`'s own `register`/`get`/`getMeta` (its `load` deliberately isn't, to
  avoid serializing unrelated on-demand loads); everything else is an open design question.
- [ ] General resolver-layer structural rules as reusable primitives, rather than binding-time-only
  behavior — empty-brace resolution, the absent-vs-missing distinction.
- [ ] Annotation access on individual fields, array/tuple elements, and map keys/values — only a
  whole bound record's own annotations are reachable today, not its children's.
- [ ] Confusable-character and bidi-formatting-character warnings (§9.4-adjacent security
  hardening) — the sibling gap to the numeric-literal length limit tracked in
  `STRUCTURED-OUTPUT.md`'s Tier 1 section; neither is enforced anywhere yet.

