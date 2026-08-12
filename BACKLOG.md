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
- **Wire-annotation capture** — complete for the tree, both read paths and the writer; the only piece still
  open is the object-binding writer, which is parked with the rest of the `tson-bind` annotation work:
  - [x] **Schemaless tree capture.** `SchemalessTreeReader` now captures `@name`/`@name: value` onto each
    node's own `annotations()`, at every position §3.1 permits one — root, record field value, array
    element, both sides of a map entry (a `MapNode.Entry` key is a node, so an annotated key keeps its
    own), and recursively an annotation's own value. The recursion needs no special case: an annotation's
    value events are buffered and replayed through the same reader via `ListEventSource`, so nested
    annotations fall out of the ordinary path. `EventSkip.typeRef` was split out of
    `annotationsAndTypeRef` so capture and discard share the second half of the framing.
    `SchemalessTreeAnnotationTest`. Note the model needed **no change** — every node type already had the
    slot, and `RecordNode`'s string-keyed `fields()` correctly mirrors §2.5's ban on annotating a field
    *name*.
  - [x] **Schema-driven tree capture.** The fix turned out to be
    *hoisting*, not signature widening: every reader that consumes the framing here **discards** the result
    (`RecordAbstractReader:159`, `MapAbstractReader:69`, `ArrayAbstractReader:68`, `TupleAbstractReader:68`,
    `AtomValueReader:136`, `VoidReader:33` — none assigns it), so a tree reader can capture the annotations
    *before* calling its base/delegate and leave that call a no-op. `RecordTreeReader`/`MapTreeReader`/
    `ArrayTreeReader`/`TupleTreeReader` do it before their shape check; `AtomNodeReader`/`AbsentNodeReader`
    (both tree-only wrappers) do it before delegating. **No shared signature changed and bind mode pays
    nothing** — better than the `ShapeResult`-widening this item used to propose, which would have made
    every mode carry a field only tree mode reads. `SchemaDrivenTreeAnnotationTest`.
  - [x] **Annotations are resolved and type-checked against the schema (§6).** A schema-driven tree read now
    resolves each annotation's name against the governing schema and reads its value with *that type's* own
    compiled reader, so a wrong-typed value fails for the ordinary reason any wrong-typed value fails — no
    separate validation pass. `AnnotationTypes` is the seam (`of(context)` for a compiled schema,
    `UNVALIDATED` for the schemaless path, which has no governing schema and so checks nothing, correctly).
    Read-time resolution is safe because `TsonSchemaCompiler.compile` is eager — every entry already has a
    reader — and the lookup is gated on the schema's own `entries()` because the resolver throws for an
    unknown name.
    - **An unresolvable name reports `UNKNOWN_TYPE_REF` and the annotation is still kept**, read
      structurally: [TSON-DATA] §1.5 requires preserving annotations a processor doesn't act on, so dropping
      it would trade one conformance rule for another.
    - **§6's bare form is checked, not assumed.** `@T` is shorthand for `@T:_`, so a synthetic absent is read
      through the resolved reader; a bare annotation on a type that doesn't admit it is a `TYPE_MISMATCH`.
    - Simplification that fell out: annotation values are now read **inline off the live cursor** rather than
      buffered and replayed. The grammar guarantees the matching `AnnotationEnd` follows the single
      data-value, so buffering bought nothing — and reading inline is what lets diagnostics reach the real
      context instead of a throwaway one.
    - Deliberately stricter than conformance requires — §1.3 imposes no annotation obligation at all
      (`SPEC-FEEDBACK.md` #29). The cost: a document whose annotations resolve nowhere now produces
      diagnostics where it previously read silently.
  - [x] **Annotations on a value at a dispatched position.** The last gap, and it needed a third mechanism
    rather than a variant of hoisting. A dispatcher (`NamedDispatchReader` for a choice,
    `VariantSchemaReader` for a record subtype) must consume the annotations to reach the `!typeName` it
    dispatches on — they precede it in `data-value = *annotation [type-ref] core-value` — so the reader that
    ends up building the node cannot see them. They are now **re-attached to the finished node** via
    `TsonNode.withAnnotations`, which puts them on the value they were written against.
    - **No context change and no interface change.** An earlier note here claimed this had to be decided
      alongside `TsonValueReader.read` because that interface "has nowhere to carry them". That was wrong,
      and the error was direction: `DiagnosticsReceiver` needs something to flow *out of* a reader beside
      its return value, which does constrain the return type; a dispatched annotation flows *in*, and the
      dispatcher can simply attach it to what came back. Unrelated problems.
    - `withAnnotations` is a pure `tson-tree` operation, and the first piece of the parked copy-on-write
      item to land — narrowly, for the one case a reader provably cannot handle as it builds.
    - **Bind mode is unaffected**, by construction: `reattach` only acts on a `TsonNode` result. To keep
      bind mode from validating annotations at just the few positions that route through a capturing
      reader, `AnnotationTypes` gained a third state — `DISCARDED` (consume and drop) alongside `of(...)`
      (check) and `UNVALIDATED` (keep). `ChoiceReader` now has a factory per mode, which is how
      record/array/map/tuple were already registered.
  - [x] **Write-side re-emission (tree).** `TsonDataEmitter` gained `annotation(name)` for the valueless
    form and a `beginAnnotation`/`endAnnotation` pair for the valued one; `TsonTreeWriter.writeNode` emits
    a node's annotations ahead of its type-ref, per §7.4's `*annotation [type-ref] core-value` order, which
    is why it runs once at the top of `writeNode` rather than inside each shape's own method. An
    annotation's value is written as an ordinary node, so a nested one needs no special case. The
    valueless form's trailing space is load-bearing, not cosmetic — §3.1 makes the character after the
    name the whole boundary rule. `SchemalessTreeAnnotationTest` round-trips every annotatable position.
  - [ ] **Write-side re-emission (object).** `TsonObjectWriter` still skips the `@Annotated` carrier field
    (`TsonObjectWriter:152`), so a bound object's captured annotations are dropped. The emitter half is
    now built, so this is just wiring — but it's on the `tson-bind` side of the annotation work, which is
    parked.

## Front door / ergonomics

- [ ] No `!!schema`-header auto-selection on the data side — given a data document, there's no
  "find the right compiled reader yourself" entry point; a caller always has to already know what
  schema position it's reading against.
- [ ] A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy — today the only
  `TsonSchemaSource` is `TsonSchemaSource.registeredOnly()` (nothing fetched); the bundled standard
  library is served internally by `TsonCompiledSchemaRegistry` from `TsonBundledSchemas`, not through
  a source.
- [ ] **A `DiagnosticsReceiver` seam; make `TsonReadContext` internal.** The read API leaks engine
  machinery: `TsonReadContext` (the pull cursor + RFC-6901 path tracker + diagnostics sink + the
  fail-fast-vs-collect *policy*, all in one) is exported and appears both in the signature a consumer
  holds from `compiled.get(name)` — `TsonValueReader.read(TsonReadContext)` — and in the low-level
  `TsonObjectReader`/`TsonTreeReader` `read(ctx, …)` overloads. A caller shouldn't have to touch it just
  to choose "throw" vs "collect every problem". Extract the one decision the engine already centralizes
  (`TsonReadContext.report(...)` *alone* decides throw-vs-collect) into a public `DiagnosticsReceiver`
  (`void report(Diagnostic)`); read methods take an optional receiver and return the (possibly partial)
  value — the value-plus-diagnostics shape the LLM repair loop wants — with `TsonReadContext` built from
  it internally and moved to an unexported package. This is also the natural home for the **collecting-mode
  object read** the facades don't offer yet (today only fail-fast; `Tson.validate` is tree-mode and
  value-less). Load-bearing cost: reworking `TsonValueReader.read(TsonReadContext)` — the central
  compiled-reader single-method interface — which ripples through the whole `reader` stack, so it's its
  own pass, not just an overload.

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

- [x] **General forward-reference resolution within a schema** ([TSON-SCHEMA] §3.4.1). `SchemaResolver`
  now resolves declarations **on demand, following dependencies** rather than strict source order, so a
  declaration may compose or refine one declared later in the same schema. Only composition supertypes and
  refinement/atom-refinement sources create a resolution dependency (field/variant/element types are bare
  names, verified by the linker), so a cycle among just those (`a => b & {}` / `b => a & {}`) is rejected
  via a `resolving` set, while ordinary recursion through field references (`x => { y: y }` / `y => { x: x }`,
  a linked list, a tree) resolves fine. `ForwardReferenceResolutionTest`. **Not attempted** (a satisfiability
  property, not a resolution one — `SPEC-FEEDBACK.md` #25): rejecting a *non-productive* recursive type that
  has no finite model (required-recursive records).
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
    off — turning an otherwise-"unprovable" pattern choice into a proved-or-refuted one. **Blocked on
    general annotation gathering** (below): the `@disjoint` marker is parsed (it's in the AST as one of
    `SchemaMap.Declaration`'s annotation lists) but dropped at resolution, so the linker's disjointness
    pass never sees it. It needs the declaration's annotations available where `disjoint` is known.
- [ ] **Template application (§5.10) has no parameter substitution, and fails at read time rather than
  resolve time.** A generic head naming a real constructor is handled — `SchemaDesugarer` rewrites
  `map<text, X>` into a declaration plus a reference before resolution, uniformly for every constructor.
  A head naming a **local parameterized template** (`box => <T> { v: T }`, then `box<text>`) is passed
  through untouched, because substituting `T` is a genuine missing feature, not a rewrite. The result
  links and compiles, then dies at read with `'T' is referenced but not present in the schema`
  (`GenericApplicationHeadTest.aLocallyDeclaredTemplateCompilesButCannotRead` pins this).
  - Two things to do, separable: make the failure **honest** (reject the application where it is
    written, rather than deferring to a read that may never happen), and then implement real
    substitution. The first is cheap and worth doing on its own.
  - Substitution belongs in the same phase: it is the same shape of work — rewrite an application into a
    declaration — differing only in that the body comes from the template's own AST with parameters
    replaced, rather than from a constructor's vocabulary.
  - **Requires a termination guard.** Non-regular (polymorphic) recursion like
    `weird => <T> { next: weird<[T]>? }` / `use => weird<text>` grows its argument every level
    (`text` → `[text]` → `[[text]]` …). Every instantiation is structurally distinct, so the
    dedup-by-derived-name never fires and the walk never terminates. Distinct from `SPEC-FEEDBACK.md` #25
    (non-*productive* recursion — no finite *data* model): this is no finite *type* model.
  - The scoping questions around generic heads — how precedence is worded, silent cross-namespace
    shadowing, whether parameters are eligible at a head, and when the `constructor: true` gate applies —
    are `SPEC-FEEDBACK.md` #28, which also records the answers this implementation currently gives.
- [ ] **General annotation gathering — carry declaration annotations through resolution into the
  resolved model.** Author annotations on a schema declaration (`@disjoint`, `@doc`, `@alias`, §6) are
  parsed and reach the AST (`SchemaMap.Declaration` carries `nameAnnotations` and `typeDefAnnotations`),
  but `DefinitionResolver` drops them: `TypeDefinition` has no annotations slot (and neither does the
  kernel `type_definition` it mirrors — annotations are §3.1 wire *attachments*, not body fields). So
  nothing downstream can see which annotations an entry carried. The likely mechanism is an `annotations`
  field on `TypeDefinition` (carried as implementation metadata the way `position` already is — also not
  a kernel body field), or a parallel `name → annotations` map on `TsonSchema`, populated by the
  resolver. One gap blocking several consumers: the **`@disjoint` assertion check** (above), user-facing
  **`@doc`** documentation generation (see *Documentation*), and `@alias`. Get the general mechanism
  right once rather than bolting on a per-annotation path and revisiting every resolution site again.
- [ ] **Resolved-form ingest** ([TSON-SCHEMA] §8.1/§10.1) — bringing an already-resolved
  `!type_definition` document into the library (not source text), with its own integrity checks:
  `subtypes`/`disjoint` recomputed and verified, the closed-entry parameter-free rule reverified, an
  instantiation entry checked against its own `source` by recomputation, a construction's binding
  record checked for parameter-slot agreement with its `source` application. Entirely unimplemented
  — "ingest" doesn't appear anywhere in the codebase. Lower priority than the three items above:
  the spec marks this path explicitly **optional** ("MAY implement ingest"), not a MUST.

## Atom-refinement constraint validation

- [x] **Atom-refinement merging now checks that a refinement actually narrows its source** (§5.7).
  `Atom.constraintsCheck(Atom refined)` is a per-family rule returning the list of ways `refined`
  fails to narrow the receiver (empty means valid); `DefinitionResolver.resolveAtomRefinement` calls
  it on the source's own bound body against the merged result and throws
  `TsonSchemaValidationException` on any violation. The backlog's own example, `!uint8 ^ { min: -10
  max: 300 }`, is now rejected. Shape notes:
  - **The family owns the rule**, since only it knows what "more constrained" means for its own
    fields; `AtomNarrowing` (package-private in `schema.meta`) holds the shared mechanics — bound
    comparison over an inclusive/exclusive pair, floor/ceiling facets, permission flags, member-set
    subsetting — so a family only says which of its fields are which kind of facet.
  - **Comparing the merged result, not the refinement body alone**, is what makes an unmentioned
    facet a non-event: it still holds the source's value and tightens vacuously, so only what the
    author actually wrote can fail.
  - **A stated bound is judged against the source's *effective* range** (an integer folds its `size`
    in: `uint8` states no bounds but its width fixes 0..255). Deliberately *not* the refinement's own
    effective range — intersecting first makes every widening vacuous and nothing is ever rejected.
  - **Three deliberate holes**, each documented on the class: `pattern`-vs-`pattern` (regular-language
    containment, and `tson-schema` has no `tson-regex` dependency to decide it — the natural place an
    injected oracle would plug in, same seam the linker's pattern-disjointness gap needs);
    `duration_type`'s bounds (unparsed ISO 8601 text, and parsing lives in `tson-compiler`); and
    **selector facets** (`complex_type.component`, `float_type.format`, `binary.encoding`,
    `uuid_type.version`) — unchecked because core.tn's own prose calls `!complex ^ { component:
    FLOAT64 }` a narrowing of a `NUMBER` source, so rejecting a selector swap would reject a
    documented construct. See `SPEC-FEEDBACK.md` #27.
  - Regression tests: `DefinitionResolverTest`'s `atomRefinementRejectsBoundsThatWidenTheSource`,
    `atomRefinementAcceptsBoundsThatGenuinelyTighten`,
    `atomRefinementChecksTextLengthsThroughTheTextFamilysOwnRule`.
- [ ] **`DefinitionResolver`'s `TsonObjectWriter` dependency stays — the premise this item used to
  carry was wrong.** It read "a real narrowing check wouldn't need to round-trip through the generic
  binder at all"; the check landed and the round-trip is still load-bearing, because **the check and
  the merge are separate concerns**. The check compares two already-bound constraint objects; the
  merge is what *produces* the second one, and it has to run on the wire record *before* binding.
  The blocker is concrete: an object-level merge would have to bind the refinement body on its own
  first, and `float_type.format` (`format: ieee_format`, `REQUIRED` with no schema default) and
  `binary.encoding` have nothing to fall back on, so `!float32 ^ { min: 0.0 max: 1.0 }` would fail
  `FIELD_REQUIRED` on a field its source already fixes.
  `DefinitionResolverTest.atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed` pins that case
  so the constraint isn't rediscovered the hard way. Nor is there a cheaper substitution:
  `TsonObjectWriter` writes straight to a `TsonDataEmitter`, so there is no object→`DataValue` step
  to borrow that would skip the text round-trip. Removing it for real needs each family to own its
  own wire decoding (duplicating number-grammar handling — `0xFF`, `_` separators, quoted-vs-unquoted
  — and bypassing the compiled reader's own defaults), which costs more than the round-trip does.
  This is still *why* `TsonObjectReader`/`TsonObjectWriter` live in `tson-compiler`'s root package
  rather than the `tson` front-door module (see "Front door" above) — moving them to a module that
  depends *on* `tson-compiler` would create a cycle, since the resolution engine genuinely depends on
  the writer. So the "revisit moving them into `tson`" note on `Tson`'s own class Javadoc is blocked
  on a different, larger change than this item once assumed.

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
- [ ] Generic type-refs whose arguments are not simple names. A non-simple argument (`weird<[T]>`) is
  rejected outright ("only simple type arguments are resolved so far") — `SchemaDesugarer` reduces an
  argument that is itself an application to the name it was hoisted to, so ordinary nesting
  (`map<text, [integer]>`) works; what is left is an argument that does not reduce to a plain name. The
  `weird<[T]>` shape additionally sits inside a *parameterized* declaration, which the phase skips
  entirely. Lifting this is part of the template-application item above, including its termination guard.
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
- [ ] `@doc`-driven documentation generation (render a schema's own `@doc` annotations) — depends on
  **general annotation gathering** (see *Resolution & linking generality*): `@doc`, like `@disjoint`, is
  dropped at resolution today, so there's nothing to render from until declaration annotations are carried
  through into the resolved model. One mechanism unblocks both.
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

