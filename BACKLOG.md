# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

---

## Tree / DOM model (`TsonNode`)

A queryable, **immutable** document model — the useful result a read hands back — replacing DOM mode's
throwaway `Map`/`List` (which is lossy: it collapses record-vs-map and array-vs-tuple, drops type-refs
and annotations, and is discarded during validation anyway). Like Jackson's `JsonNode`, but structure-
preserving (TSON distinguishes record/map and array/tuple, which JSON conflates) and annotation-aware.
The data AST (`ast.CoreValue`) stays a pure, schemaless parse tree — the tree is a distinct, consumer-
facing type set (settled: separate concepts, and the tree carries typed leaves + query ergonomics the
AST deliberately doesn't).

**Settled design decisions:**
- **Module** `tson-tree` (`io.ltr8.tson.tree`) — its own pure-leaf value-model module, the data-tree
  counterpart to `tson-schema`'s `schema.meta`; `tson-compiler` depends on it, not the reverse.
- **Immutable, "new from old"** — records + builders + copy-on-write helpers (`RecordNode.with(name,
  node)`, `ArrayNode.with(i, node)`); no mutation. A pointer-based `set("/a/b", node) → new tree` is a
  later nicety.
- **Model:** `sealed interface TsonNode` carrying `Optional<String> typeRef()` + `List<Annotation>
  annotations()` on every node. Concrete: `RecordNode` (ordered name→node), `MapNode` (ordered
  (keyNode, valueNode)), `ArrayNode`, `TupleNode` (schema-driven only), a **single** `AtomNode(Object
  value, Optional<String> typeRef)` (not per-atom node classes — §5's vocabulary is too large), plus
  `NullNode` and `AbsentNode`. Token form (quoted/unquoted) is dropped (captured by the resolved leaf
  type). `TsonNode` carries the prefix; subtypes are bare (Jackson's `JsonNode`/`ObjectNode` precedent).
- **Query API:** `get(String)`/`get(int)`/`at(jsonPointer)` (reuse the RFC 6901 machinery from the
  diagnostics layer)/`path(...)`; `isRecord()`/`isAtom()`/… kind tests; typed accessors (`asString`/
  `asBigInteger`/`asInt`/`asBoolean`/`asDecimal`/`asUuid`/`asLocalDate`/…, plus generic `as(Class<T>)`
  and raw `value()`); `fields()`/`entries()`/`elements()`.

**Two producers, one node set:**
- **Schema-driven** — rework the `*DomReader` family (`Record`/`Array`/`Map`/`Tuple`) into `*TreeReader`
  building `TsonNode` (same streaming plumbing, now keeping type-refs/annotations and splitting tuple
  from array). `ValueReaderFactoryRegistry.dom()` → `.tree()`; `TsonCompiledSchemaRegistry.dom(core)` →
  `.tree(core)`; `Tson.domRegistry()` → `treeRegistry()`; `validate` uses tree mode (still discards the
  result). Plain `Map`/`List` output goes away (`TsonNode` can offer `asMap()`/`asList()` views).
- **Schemaless `readTree`** — walk the `DataValue`/`CoreValue` AST, base-resolve each token (+
  `BuiltinTypeVocabulary` for a `!builtin` tag), map to the same nodes. Surfaced as `Tson.readTree(...)`.
  No tuple distinction (grammar has none); `EmptyBrace` **defaults to an empty `RecordNode`** (settled).

**Phasing:** (1) node model + query API + the schema-driven `*DomReader` → `*TreeReader` rework + rewire
(factory/registry/`Tson`/`validate` + ~17 reader-test updates — the bulk); (2) schemaless `readTree` +
copy-on-write transform helpers; (3) `TsonNode.toTson()` serialization, then a JSON producer (the future
JSON stack emits the same `TsonNode`).

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
  meta is required (see Done) — and now rejects a non-meta `!!meta` target at load time via
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
- [ ] **Choice disjointness derivation and untagged reading** ([TSON-SCHEMA] §5.4, §8.1) —
  `TypeDefinition.disjoint` is a real `Optional<Boolean>` field in the model, but every
  `DefinitionResolver` construction site passes `Optional.empty()` for it — nothing computes it, at
  all. `ChoiceReader` correspondingly always requires an explicit `!variant` type-ref tag to
  disambiguate a union member, with no structural-recovery path for a choice a schema author has
  declared (or that could be proven) disjoint. The `@disjoint` author-assertion annotation
  (proved/refuted/unprovable/absent) has nothing to check against until this lands.
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
  `I`'s own already-bound constraint object to wire form via `TsonMapperWriter`, then merges it with
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
- [ ] **`!choice { variants: [...] }` construction (§5.4) fails to resolve — a genuine bug, not just
  an unimplemented case, confirmed empirically (2026-07-29) while designing the sibling
  `ltr8-io-tson-test-suite` repo's own sidecar-format schemas (see "Conformance test suite" below):
  a tagged union would have modeled `core_value`/`base_value`/each layer's own `outcome`-discriminated
  sidecar shape far more precisely than the "one record, kind enum, every other field optional"
  design those schemas actually ended up using, so this was worth pinning down exactly rather than
  left as a guess. `DefinitionResolver.bindAtomInstance` throws `UnsupportedOperationException`
  wrapping a `NullPointerException` ("Cannot invoke `Collection.isEmpty()` because `coll` is null"),
  from `TypeRef`'s own constructor via `List.copyOf`, reached through
  `RecordBindReader.read`/`ArrayBindReader.read` while binding `choice`'s own `variants: [type_ref]`
  field — each variant is written as a bare, unadorned type name (`!choice { variants: [text
  integer] }`), and something in that array-of-bare-type-ref binding path isn't defaulting
  `TypeRef.arguments` before construction the way the equivalent single-field case already does
  elsewhere. Narrow, likely a short fix once someone's in that code path — but real, and blocks any
  schema (this project's own or a consumer's) from declaring a genuine tagged union at all today, not
  just from getting `disjoint` computed for one (see "Choice disjointness derivation" under
  "Resolution & linking generality" above, which assumes a choice already resolved).

(All already named in `DefinitionResolver`'s own Javadoc and `CLAUDE.md`; carried here so
everything outstanding is tracked in one place.)

## Write side

- [ ] No schema-aware (Class 2) writer exists at all. Only `TsonMapperWriter` does (Part-1-only,
  generic, with documented lossy spots: integer width, tuple-ness, `@Annotated`-captured wire-format
  annotations). A validating writer symmetric to the compiled reader stack (`TsonSchemaCompiler`/
  `TsonValueReader`) is a whole missing half of the pipeline if round-tripping or producing
  conformant documents is ever a goal.
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

