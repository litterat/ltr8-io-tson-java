# CLAUDE.md

Orientation for Claude Code sessions in this repo. It describes the code **as it stands** — current
form, present tense. How it got here lives in git history and `BACKLOG.md`, not here; when a design
choice has a non-obvious *why* (and many do), this file states the current rationale directly rather than
the sequence of edits that produced it.

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
Several such findings are load-bearing and are cited by number (`SPEC-FEEDBACK.md #N`) throughout this
file.

## Conventions

**Javadoc documents current contract only, no change history.** Java source Javadoc describes an element's
*current* behavior — never dates, "renamed from X", "used to do Y, now does Z", "on the user's direction",
or similar changelog framing. If a design needs a WHY, state the current invariant and its rationale
directly. When you edit a class, clean up its Javadoc in the same edit — remove stale narrative (even if
you didn't write it), fix anything that no longer matches the code, tighten what's left. (This file,
`CLAUDE.md`, is the current-form orientation doc — it follows the same no-history rule; the dated log now
lives in git.)

**`Tson` is a prefix, never an infix.** A class name containing `Tson` must lead with it (`TsonSchema`,
`TsonDataParser`, `TsonCompiledSchema`) — never buried (`CompiledTsonSchema` is wrong). The prefix is
**not** applied to every class: most internal machinery is deliberately bare (`Lexer`,
`RecordAbstractReader`, `DeferredTypeReader`, `ChoiceDisjointness`, `SchemaResolver`,
`DefinitionResolver`). Reserve `Tson` for types a *consumer of this library* names in their own code — its
value is disambiguation at the call site (`TsonSchema` vs. a domain `Schema`). When adding a new public,
developer-facing type, ask "would a consumer plausibly have their own class with this bare name?" — if yes
and it's consumer-facing, prefix it; if it's internal machinery, leave it bare.

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
  The node types and their query API are described under "Tree model" below.
- **`tson-regex`** — **only** `io.ltr8.tson.regex`: a native RFC 9485 I-Regexp engine — `TsonRegex.parse`
  builds a `RegexNode` AST (or `TsonRegexSyntaxException`), `TsonRegex.matches` runs a Thompson-NFA/Pike-VM
  simulation (linear-time, no backtracking → ReDoS-safe; `\p{…}` via JDK `Character.getType`), and
  `TsonRegex.isDisjointFrom` decides whether two patterns share any string (exact — a symbolic product-NFA
  emptiness check over a `CodePointSet` interval algebra, the building block for §5.4 pattern disjointness).
  A true leaf — depends on **nothing**,
  I-Regexp being an external standard, not TSON-specific. The *engine* counterpart to `tson-bind` (a general
  dependency-free engine), not a value model like `tson-tree`; TSON pins its `regex` atom to I-Regexp
  (`regex_type`'s `REQUIRED_FIXED spec = rfc9485`), so this owns I-Regexp semantics rather than delegating
  to `java.util.regex` (a laxer superset). `tson-compiler`'s atom vocabulary depends on it; it names no
  `tson-compiler` type.
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
`-Xlint:exports` warning (e.g. `ValueReaderFactoryResolver`); this is deliberate,
not a defect. No `opens` directives — binding only ever touches public constructors/methods.

## Pipeline

The schema pipeline is **parse → desugar → resolve → link → register → compile → read**; the class
vocabulary follows it (`TsonSchemaParser`, `SchemaDesugarer`, `TsonSchemaResolver`, `TsonSchemaLinker`,
`TsonSchemaRegistry`, `TsonSchemaCompiler`, `TsonTypeReader`). Data documents (Class 1, no schema) run the
shorter lex → parse → base-type-resolve path. The subsections below follow this order.

### Lexer (`tson-compiler/.../lexer/`)

`Lexer` is a single hand-written scanner producing `Token`s, driven off `nextToken()` (never a
`tokenize()` batch). **Complete and frozen for the whole series** (§1.3: higher parts introduce no new
tokens, modes, or character-classification changes).

- **Constructed from an `InputStream`**, decoded UTF-8 and buffered a few code points of lookahead — never
  requires the whole document resident as a `String`. **Code-point addressed, not char-addressed**
  (surrogate pairs are never split; supplementary-plane identifiers per UAX #31 work). `Position` tracks
  line, code-point column, and a UTF-8 byte offset (§8.1 error reporting).
- **`Token` is a flat record of six raw `int` coordinates plus type/text**, not nested `Position` objects,
  to keep allocation off the high-throughput read path; `start()`/`end()` materialize a `Position` on
  demand.
- **`Character.isUnicodeIdentifierStart/Part` stands in for XID_Start/XID_Continue** (§7.1). The JDK
  doesn't expose the exact UAX #31 properties and building the table from scratch is out of scope — a
  known, deliberate approximation. Flag it if a lexing bug ever hinges on a script where Java's notion and
  true XID_* diverge.
- **NFC normalization** (`java.text.Normalizer`) applies to *unquoted* tokens only (§7.2.1) — quoted
  tokens preserve exact content. **Pattern_White_Space is the spec's fixed 11-character set**, hardcoded
  (not `Character.isWhitespace`). A single leading **BOM** is stripped; U+FEFF elsewhere falls through to
  "unrecognised character" naturally.
- **Multi-line common-prefix stripping** (§7.2.3) compares leading-whitespace prefixes character by
  character (a tab never matches a space). **Closing-delimiter detection checks the line content *after*
  removing leading whitespace against `"""`** — getting this backwards makes every multi-line token
  spuriously "unterminated"; this bug happened once and is guarded by `LexerTest`.
- When embedding BOM/NEL/LINE SEPARATOR/PARAGRAPH SEPARATOR in tests or source, use `\uXXXX` escapes — the
  literal invisible character is an editing hazard and exactly the confusable-character risk §9.4 warns
  about.
- Errors are **fail-fast** (`LexException`, unchecked), not the spec's "SHOULD continue to report multiple
  issues" recommendation (§8.1) — multi-error recovery is deferred.

### Structural parsing: Tier 2 stream + Tier 3 AST (`tson-compiler/.../`)

Two roles turn tokens into a `Document` (§2, §3, §7.4). There is exactly one implementation of the data
grammar, split by role, not duplicated:

- **`TsonDataStream` (Tier 2)** is the only thing that walks source text: a lazy, pull-based
  `TsonEventSource` (`stream` package — `hasNext()`/`next()`/`peek()` over a sealed `TsonEvent` hierarchy:
  `RecordStart`/`MapArrow`/`ArrayStart`/`TypeRef`/`SchemaRef`/`TokenEvent`/`AbsentEvent`/... each carrying
  its own `Position`). Driven off `Lexer.nextToken()` with an explicit frame stack (memory is proportional
  to open-container depth, never document size) and at most two tokens of lookahead (only to disambiguate
  `{}` record-vs-map).
- **`TsonDataParser` (Tier 3)** builds the full AST — the sealed `CoreValue` hierarchy in `ast`
  (`RecordValue`/`MapValue`/`ArrayValue`/`EmptyBrace`/`AbsentValue`/`TokenValue`) — by reducing the flat
  event sequence back into a tree. It holds no grammar logic of its own.

Key points:

- **Whitespace is invisible by the time tokens arrive** — the lexer discarded it, leaving only `Position`
  gaps. So `ws` in the grammar needs nothing special, and strict **adjacency** (`!`, `!!`, `@`, `:` to
  their operand, §7.5) is checked via `Position` equality between one token's end and the next's start.
  **Separator detection** (§2.4) works the same way: a real comma is optional evidence, a position gap is
  the other kind, and at least one is required unless the closing delimiter is immediately next.
- **Layering is deliberately incomplete, matching §1.2's division of labor.** Neither tier deduplicates
  record fields or map keys ("last value wins" is a resolver rule, §2.5/§2.6), NFC-normalizes field names,
  rejects `_` as a map key (§2.9), resolves `EmptyBrace` to a record/typed container (§2.8), or interprets
  `TokenValue` text as null/boolean/number/string (base type resolution, below). These are intentional
  gaps, not omissions.
- **`!!meta` in the header throws `TsonUnsupportedDocumentException`, not `TsonParseException`.** This is a
  Class 1 processor; a schema document isn't malformed input, it's a well-formed document of a kind this
  parser doesn't implement, and §8.1 requires that distinction be visible (a categorized diagnostic).
- **Nested annotation value-scope is right-recursive** and can legitimately leave an outer data-value
  without a core-value (`@a:@b:val`) — see `SPEC-FEEDBACK.md` #3; documented as intentional, not a bug.

### Base type resolution (`tson-compiler/.../base/`)

`BaseTypeResolver.resolve(TokenValue)` implements §4's fixed order (null → boolean → number → string,
§4.5) for untyped tokens. `NumberGrammar.tryParse` recognizes the `number` production (§7.6).

- **Identification is separate from binding to a host numeric type.** `NumberGrammar` decides which of the
  four grammar alternatives matches and extracts structural pieces into `NumberForm` — it does **not**
  convert to `long`/`double`/`BigInteger`/`BigDecimal`. The spec leaves that mapping to the implementation
  (§4.3); binding is where the required `255`/`0xFF`, `.5`/`0.5` equivalences get enforced, and different
  consumers want different host types. Each number alternative is its own anchored regex (Java forbids a
  named group repeating across alternation).
- **Quoted tokens always resolve to `StringValue`** regardless of content (§4.4) — form is consulted once,
  here. `"42"` and unquoted `42` differ even though their text is identical.
- **§9.1's numeric-literal length limit** (SHOULD, 4096 digits, DoS-hardening) is **not enforced** — noted
  so it isn't mistaken for an oversight.

### Built-in atom vocabulary (`tson-compiler/.../atom/`)

`AtomType<T>` is a built-in atom's parsing contract (§5.2): `read(TokenValue)` (its natural host value),
`read(TokenValue, Class<?>)` (narrow to a caller target), `write(T)`. `BuiltinTypeVocabulary` is the
fixed, closed name→`AtomType` table (§5).

- **Each constructor splits into two classes across two modules:** a pure constraint-*values* record in
  `io.ltr8.tson.schema.meta` (`IntegerType`, `TextType`, `RegexType`, `DateType`, …, matching the kernel's
  `*_type` shape) and a same-named `*Parser` in `atom` (`IntegerParser`, `TextParser`, …) that holds one
  and does the `read`/`write`/validate work. This is what lets `atom` consult `schema.meta` constraint
  records directly.
- **`RegexParser` returns `String`, and `TextType.pattern`/`UriType.pattern` are `Optional<String>`, not
  `Pattern`** — `regex` IS-A piece of text (§5.7), so its host value is `String` like every other
  text-composing atom; the text is validated as I-Regexp via `tson-regex`'s `TsonRegex.parse` (not
  `java.util.regex`, whose grammar is a superset — `regex_type`'s `spec` is `REQUIRED_FIXED` to RFC 9485),
  and the parsed form discarded once it's confirmed well-formed. Keeping these as plain equatable `String`
  (not a compiled matcher) is also what lets them bind generically with no `DataBridge`. **Matching** a value
  against a `pattern` constraint (`TextParser`/`UriParser`) runs through `tson-regex`'s `TsonRegex.matches` —
  a Thompson-NFA, linear-time and ReDoS-safe — not `java.util.regex`.
- **`unit`'s three instances are three separate parsers**, not one: `value` (runs base-type resolution to
  the natural host), `token` (raw NFC-normalized token text, unconstrained), `void` (`VoidReader`, accepts
  only the absent sentinel `_`). They resolve to the byte-identical `Unit` body — nothing in the *schema*
  distinguishes them — so dispatch is keyed on the declaration's own name (see `SPEC-FEEDBACK.md` #18).
- The full `int8`..`int256` width ladder is seeded, versus the four §5.6 explicitly lists — a known
  departure tracked in `SPEC-FEEDBACK.md`.

### Schema grammar (`tson-compiler/TsonSchemaParser.java`, `.../ast/schema/`)

`TsonSchemaParser` parses a schema document body (Part 2 §2.1, §5, §12.1) into a `SchemaDocument` — the
schema-grammar analogue of `Document`. It is **grammar-only**: no namespace resolution, no
materialization, no validation (those are the resolver's/linker's jobs).

- **`extends TsonDataParser`, same package** — §12.1 imports Part 1's `annotation`/`data-value`/directive
  grammar directly, so `TsonSchemaParser` calls straight into package-private methods rather than
  re-implementing them, adding only the schema-only tokens (`~ ^ & | ( ) < > ? ; -`).
- **`SchemaMap.declarations` is a `Map<String, Declaration>`** (a `LinkedHashMap`, insertion order
  preserved) — §3.4.1's Pass 1 shape and the schema's own `map<type_name, type_definition>`. A duplicate
  name overwrites, same "grammar layer doesn't dedupe" treatment the data grammar gives duplicate fields.
- **Three spec defects implemented per intent, not per letter** (each with an AST reshape and a
  `SPEC-FEEDBACK.md` entry): `instance`'s ABNF says `data-value` but the intended production is the
  narrower `core-value` (#16 — `Instance` wraps a `DataValue` with `typeRef` pre-set, no separate
  `target`); `construction-def`'s ABNF doesn't admit the implicit `&` before its trailing `record-def`
  (#14); `field-modifier`'s value is a bare token or the absent sentinel, not a full `data-value` (#15).
  `atom-refinement` has the same `data-value`-vs-`core-value` defect as `instance` but is left as-is (its
  own note in #16). An unquoted non-numeric type-argument always parses as a type reference, never a value
  literal — a deliberate grammar-layer deferral, classified at a later semantic layer.
- **The bracket form is parsed twice, per the spec** — `ArrayContainerDef`/`TupleContainerDef` at
  declaration position, `InlineArrayRef`/`InlineTupleRef` at type-ref position, with `[` at type-def
  position hard-coded to the container path (§12.1's prose tie-break; the two productions overlap and
  `type-def` is genuinely ambiguous without it). Four node types for two concepts, and `SchemaDesugarer`
  walks both to the same output — implemented per letter, and argued against in `SPEC-FEEDBACK.md` #31.

### Desugaring (`tson-compiler/.../resolver/SchemaDesugarer.java`)

An AST→AST rewrite between parsing and resolution. Every sugar form (`[T]`, `[T; N..M]`, §5.3) and every
generic application (`map<K, V>`, §5.6) becomes a `!C value` construction: at declaration position it simply
*is* that construction, and anywhere else (a field, an element, a variant) it becomes an **injected
declaration plus a bare reference to it**. So `DefinitionResolver` only ever sees two shapes: a bare
reference or `!C value`. §5.3/§5.6 already *describe* these forms as desugarings and §3.3.1 calls their
targets "the implicit desugar targets of the sugar forms" — this implements that literally instead of
splitting it across the resolver (declaration position) and the linker (field position), which is what it
replaces. A **sized** form desugars only as far as the size template it stands for (`[T; 1..5]` →
`array_ranged<T, 1, 5>`, `[T; 2..]` → `array_min`, `[T; ..9]` → `array_max`, `[T; 3]` → `array_ranged` with
the bound twice) — purely a change of spelling, which is why it belongs here, but those targets are
*templates*, not constructors, so the result stays an application. What it then resolves to is §5.10
substitution, which this phase does not answer.

- **It runs with the governing meta in hand, not context-free.** `SchemaResolver` calls it after acquiring
  `metaParser`, because turning `map<text, X>` into `!map { key_type: text  value_type: X }` needs `map`'s
  own `parameters()` to zip the arguments positionally and each vocabulary field's `value_param` to know
  which field a given argument fills. That routing is what makes it work for *every* constructor rather
  than the ones someone hand-wrote an assembler for.
- **The head is looked up in the structure namespace only** (the governing meta's entries) and must be
  `constructor: true` with matching arity. The precedence/shadowing consequences are `SPEC-FEEDBACK.md` #28.
- **A template application over a constructor is instantiated** (§8.2's one materialising form). §5.3's
  sized sugar is the case that matters: `[T; 1..5]` → `array_ranged<T, 1, 5>`, and `array_ranged` is a
  template (declared without `~`) whose resolved vocabulary carries the same `value_param` channels a
  constructor's does — so the *same* routing code handles it, with one difference: the emitted binding record
  is headed at the nearest `~` constructor in the source chain (`!array`, §5.6), not at the template. The
  result is a `TemplateInstance` AST node — no surface syntax corresponds to it — which `DefinitionResolver`
  completes with the two things a construction doesn't carry: §8.2's `source` (the flattened application) and
  the template's supertypes, unchanged, which is what makes a sized array IS-A `array`. §8.2's deferred
  `min_items <= max_items` check runs here too, at the materialising application.
- **Applying a *record* template is still rejected here**
  (`UnsupportedOperationException`) — `box => <T> { v: T }` puts its parameter in a *field type*, so
  instantiating it means rewriting the body, which is real §5.10 substitution and unimplemented. Rejecting at
  the application site beats the alternative: passing it through produced a schema that linked, compiled, and
  then failed on the first read reaching the field. Both namespaces a template can live in are checked — a
  head this document declares (via its grammar-layer `TypeDef`, the only place its parameters exist this
  early) and one in the structure namespace (via its resolved definition). Still uncaught: a template
  declared by an `!!import`, which needs the imported entries' resolved definitions rather than the name set
  the phase takes.
- **Bottom-up, so nesting needs no special case:** an inner application is hoisted first and the outer one
  is built from the already-flattened name (`map<text, [integer]>` works at any depth). The injected name
  is `head_args_hash`, derived from the application itself, so two structurally identical applications
  anywhere in the document collapse to one declaration for free (§8.2's structural-equality rule) — and an
  application an `!!import` already declares is **referenced, not redeclared**, which is why the phase takes
  the imported name set (meta.tn repeats several of meta-kernel's applications; redeclaring would be a
  local-vs-import collision).
- **Structural sharing is load-bearing, not an optimization.** Every node not being rewritten is returned
  by identity, because `TsonSchemaParser.declarationPositions()` is an `IdentityHashMap` — an
  equal-but-rebuilt `Declaration` silently loses its position, and the diagnostics that report against it.
  `SchemaDesugarerTest` asserts `assertSame` for exactly this reason.
- **A parameterized declaration is left entirely alone** — its body legitimately references its own
  parameters, and rewriting `array<T>` inside `array`'s own declaration would be nonsense.
- **The meta-kernel runs this phase too**, supplying its own hand-written routing table
  (`MetaKernelBootstrapResolver.BOOTSTRAP_CONSTRUCTORS`), since its governing meta is itself.

### Schema resolution (`tson-compiler/.../resolver/`)

`DefinitionResolver` (package-private) turns one grammar-layer `SchemaMap.Declaration` into a resolved
`TypeDefinition` (§4, §8, values from `schema.meta`). `TsonSchemaResolver` (public, root package, a thin
wrapper over `SchemaResolver`) resolves a whole `SchemaDocument`: header-directive validation, deriving
the structure namespace from the governing `!!meta`, merging `!!import` entries into the type-name
namespace *before* any local declaration resolves.

- **Three namespace dependencies are constructor-fixed, via functional interfaces**, not threaded per
  call: `DefinitionGetter getTypeDefinition(name)` (the accumulating type-name namespace — typically a
  method reference onto a caller's growing map), `DefinitionGetter metaDefinitions` (the structure
  namespace — the governing meta's entries, consulted *only* for a constructor-application target per
  §3.3.1), and `DefinitionMetaReader read(type, value)` (binds a constructor-application/atom-refinement
  value through the governing meta's compiled reader). A resolver with nothing to offer supplies always-
  throwing / always-null constants.
- **What resolves:** record construction; composition (`A & B & { ... }`, §5.8, with kind from the literal
  base-kind names in the transitive supertype chain, and tightening in the trailing body per §5.7); the
  `^` refinement operator (§5.7, copies the source's whole field set, admits no new fields); bare
  references (§8.3); constructor application (`!C value`, §5.5, binds generically via the compiled
  reader — no hand-rolled name→class table, `tson-bind`'s sealed-union resolution finds the `Top` leaf by
  `@Typename`); atom refinement (`!I ^ { ... }`, §5.5/§5.7); subtraction (`A & { ... } - { f }`, §5.9);
  restating a field group in a refinement or composition body (§5.11 — same member labels in the same order,
  types verbatim, state tightening OPTIONAL→REQUIRED only; only the *group's* state moves, since members
  flatten as `OPTIONAL` regardless).
- **All six of §5.2's field-state spellings resolve**, including `field: type? = _` — `OPTIONAL_FIXED`
  carrying *no* value, so §8.1 writes a `record_field` without a `value` member and the field must be
  omitted or written `_`. Its three resolver errors are enforced: `~ _` on any field, `= _` on a REQUIRED
  one, and `type? ~ value`. **Presence comes from the entry's own `?` when it restates a type, else from the
  field it tightens** — §5.2 makes `= _` valid on a field "declared with `?` *or inherited as OPTIONAL*",
  and a modifier-only entry has no `?` to read. That is why `resolveField` takes the whole inherited
  `RecordField`, not just its type. A consequence worth knowing: a modifier-only tightening moves only the
  *mutability* axis (§5.7's "only the value state changes"), so an inherited-OPTIONAL field pinned with
  `= 0` lands in `OPTIONAL_FIXED`, not `REQUIRED_FIXED` — promoting it to always-present takes restating the
  type (`min: integer = 0`). The exception is a **parametric** modifier, which §5.7's "Open modifiers" puts
  in a REQUIRED-family state whatever the presence axis says (that is what makes `array_min`'s `min_items:
  = MIN` mandatory), so the parameter branch sits ahead of the `OPTIONAL_FIXED` one.
- **§5.11's group presence rule is checked after every body**, refinement and composition alike: two members
  of one group both left in a REQUIRED-family state (`REQUIRED`/`REQUIRED_DEFAULT`/`REQUIRED_FIXED`) is a
  resolver error, because a group admits at most one member and nothing could satisfy the result. Only this
  declaration's own tightenings can trip it — members flatten as `OPTIONAL` when first declared, so by
  induction a source that passed hands on at most one always-present member. `= _` is deliberately *not*
  always-present (it lands in `OPTIONAL_FIXED`); forbidding one alternative's value is what §5.11 offers it
  for. The spec says "a refinement" but the paragraph is headed "Refinement and composition" and a
  composition body builds the identical unsatisfiable type, so both are checked.
- **Subtraction runs last and breaks IS-A on purpose** (§5.9). Supertypes merge, the body adds and tightens,
  *then* removals apply to the merged field set with no regard for which supertype contributed a field
  (rule 3 — the contract is already broken, so there is none left to violate). Two things are rejected:
  removing a name that isn't there (rule 2), and removing one this declaration's own body also states
  (rule 4 — adding-then-removing, or tightening-then-removing, says two incompatible things), checked in
  that order because a body-introduced field *is* in the merged set and "no such field" would be the wrong
  diagnosis. The output splits the two supertype lists §8.1 keeps apart: `type_definition.supertypes` is
  **emptied** (the contract — so §7.2's subsumption check won't let a subtracted type stand where its source
  is expected), while `record.supertypes` keeps the head's list as authorial lineage. `kind` still comes off
  the lineage chain. **Every** supertype goes, including one that contributed nothing to the removal — `A & B
  - { f }` with `f` from `A` drops IS-A with `B` too, though `B`'s fields all survive. That is §5.9's letter
  against §4.3's "composition grants IS-A per parent"; `SPEC-FEEDBACK.md` #37 has the per-ancestor
  alternative, and the workaround (subtract first, compose second) that makes an author's intent explicit.
  Groups follow §5.11: a removed member leaves `members`, a group down to one member is
  dissolved into a plain field taking the *group's* state (members flatten as `OPTIONAL` whatever the group
  says, so the survivor would otherwise silently lose a REQUIRED group's "exactly one"), and a group with no
  members left is dropped — the one arity §5.11 doesn't legislate (`SPEC-FEEDBACK.md` #36).
- **Chained atom refinement merges with the source, it does not replace it** (`SPEC-FEEDBACK.md` #17):
  `bounded => !int8 ^ { min: -100 }` must still carry `int8`'s own `size`. `mergeWithSource` re-serializes the
  source's bound value via `TsonObjectWriter` and merges field-by-field (explicit values win) — no
  per-atom-class merge logic needed. **The merge runs on the wire record, before binding, and has to**: a
  constructor field that is `REQUIRED` with no schema default (`float_type.format`, `binary.encoding`) is one
  a refinement body has no reason to restate, so binding the body alone would fail `FIELD_REQUIRED`
  (`DefinitionResolverTest.atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed` pins that case). This is
  why `DefinitionResolver` still holds a `TsonObjectWriter`, and in turn why `TsonObjectReader`/
  `TsonObjectWriter` can't move to the `tson` module. **The text round-trip has no cheaper substitute**:
  `TsonObjectWriter` emits straight to a `TsonDataEmitter`, so there is no object→`DataValue` step to borrow
  that would skip it. Removing it for real would mean each constraint family owning its own wire decoding —
  duplicating number-grammar handling (`0xFF`, `_` separators, quoted-vs-unquoted) and bypassing the compiled
  reader's own defaults — which costs more than the round-trip does.
- **A refinement must narrow, and this is enforced** (§5.7). After binding, `checkNarrows` asks the
  constraint family itself — `Atom.constraintsCheck(refined)`, one rule per `schema.meta` family over the
  shared `AtomNarrowing` mechanics — whether the merged result is a valid tightening of the source's own body,
  and throws `TsonSchemaValidationException` if not (`!uint8 ^ { min: -10 max: 300 }` is rejected). Comparing
  the *merged* result rather than the refinement body is what lets an unmentioned facet tighten vacuously; a
  stated bound is judged against the source's **effective** range, folding in a derived one like an integer's
  `size` (intersecting the refinement's own bounds first would make every widening vacuous). Unchecked by
  design, each documented on its class: `pattern` against `pattern` (regular-language containment, and
  `tson-schema` has no `tson-regex` dependency), `duration_type`'s text bounds, and **selector** facets
  (`component`/`format`/`encoding`/`version`) — core.tn's own prose calls a selector swap a narrowing, so
  rejecting one would reject a documented construct (`SPEC-FEEDBACK.md` #27).
- **Two exception types, and which one is deliberate.** `UnsupportedOperationException` means *this library
  hasn't implemented that yet* — the identity-diagonal FIXED-value invariant, a generic type-ref with a
  nested or value (non-simple) argument, a parameterized supertype.
  `TsonSchemaValidationException` means *the schema is wrong*, and the spec says so: a tightening outside
  §5.7's transition table, a refinement body field (or group) that adds rather than tightens, a
  modifier-only entry with nothing to elide toward (§5.7), a field name two supertypes both contribute or a
  body/group declares twice (§5.8/§5.11), a group restatement that reorders, retypes, changes membership or
  loosens REQUIRED→OPTIONAL (§5.11), a source or supertype whose body is a binding record and so has no
  vocabulary (§5.7's "finished"), a choice or bracketed form at a supertype position (`&` composes record
  types; §12.1 admits these only because `construction-def` draws its operands from `type-ref` where
  `refined-def` takes a name — `SPEC-FEEDBACK.md` #38), a name in a `!` position resolving in neither
  namespace (the plain typo, §3.3.1), a `!` form aimed at the wrong kind of target — refining a
  constructor, applying a non-constructor, refining a non-atom — each answered with the form the author
  probably meant, and **a body the constructor's own vocabulary rejects** (an unknown member, a wrong-typed
  one), which arrives from the compiled meta reader as a `TsonReadException` and is restated here rather
  than passed on in the reader's currency — the read `Diagnostic` itself is dropped, since it was produced
  against a `DataValueEvents` replay whose positions are all the `(0,0,0)` placeholder and whose `path`
  points into a synthetic body; the declaration's real position comes from `SchemaResolver`'s catch.
  Telling an author their correctly-rejected schema is
  unsupported sends them looking for the wrong fix, and now costs more than clarity: only the validation
  exception is collected into a `Diagnostic`, so a misfiled author error also aborts the run instead of
  joining the other problems. The useful test is that **a schema error's verdict doesn't change when this
  library improves; a gap's does.** The split is worth keeping honest —
  `IllegalStateException` is the third, for an invariant only a malformed `TypeDefinition` could break (a
  `constructor: true` entry with a non-record body, which §12.1's grammar makes unreachable).
  `DefinitionResolver`'s Javadoc lists the exact boundary.
- **`TypeArgument` is a sealed interface (`Ref`/`Value`), NOT a plain record — do not "simplify" it
  back.** `TypeRef`/`TypeArgument` are mutually recursive, and `tson-bind`'s record binder eagerly resolves
  every field descriptor with no cycle protection, so a plain-record `TypeArgument` deadlocks with
  `StackOverflowError` the moment a non-empty `arguments` list is bound. The sealed interface is the one
  shape that binds at all (union binding breaks the loop by member class). Re-read its Javadoc before
  touching it. The cost is a spurious `!ref`/`!value` tag on `toTson` output, documented.
- **`schema.meta` value model:** one Java type per kernel vocabulary record/enum. `Top`/`Atom`/`Product`/
  `Sum` replicate the kernel's composition chain (§4.1) as real Java subtyping — a consumer tests kind
  ancestry with `instanceof Product`. Body leaves are named `RecordBody`/`EnumBody`/etc. (not `Record`,
  which would collide with `java.lang.Record`). Multi-word fields carry `@Field("snake_case")`. **A
  `schema.meta` class used as a bind target with more than one public constructor needs `@Record` on the
  canonical one** or `DefaultRecordBinder` throws (`IntegerType`/`IntegerSize` hit this) — the annotation
  and the fallback both exist for exactly this case.
- **No hand-written writer** — resolved values serialize through `TsonObjectWriter.toTson` directly,
  deliberately, to prove the model is idiomatic Java `tson-bind` already binds. Documented textual
  divergences from the fixture (no outer `!type_definition` tag, quoted strings for enum members, empty
  lists written rather than omitted, full `TypeRef` form) are in `DefinitionResolverTest`'s Javadoc.

### Meta-kernel bootstrap (`tson-compiler/.../resolver/MetaKernelBootstrapResolver.java`)

Meta-kernel is special: its own `!!meta` names *itself* (§1.5's one deliberate circularity, closed by
pre-loading, not resolution). Ordinary resolution can't bootstrap it — resolving a constructor application
needs that constructor's vocabulary already known, and every constructor meta-kernel uses is defined
within meta-kernel.

`MetaKernelBootstrapResolver.getMetaKernelSchema()` (its only public method) produces the resolved
meta-kernel `TsonSchema` in **two passes** over its declarations: non-`Instance` declarations first
(ordinary `DefinitionResolver`), then the deferred `Instance` declarations (`value => !unit {}`, `boolean
=> !enum [true false]`, …) once every constructor they reference — including ones declared later in the
file — has an entry to transfer a kind from. `TsonSchemaResolver` alone is single-pass, strict source
order, so it can't handle `boolean` preceding `enum`; this two-pass ordering lives here.

- **Constructor-application binding goes through a closed `instanceBody` switch, not the generic path.**
  Meta-kernel instantiates constructors in exactly three shapes — a bare `{}` (each target's
  `UNCONSTRAINED` constant), a bare token array (`enum`, via `toEnumBody` reading `TokenValue.text()`
  directly), and the binding record `SchemaDesugarer` emits for an `array`/`set`/`map` application. No
  compiled reader is involved.
- **Why even a compiled-reader bootstrap can't read meta-kernel from its own in-progress state:**
  `integer_size => { bits: ... signed: boolean }` is a first-pass entry whose `signed` field already
  references `boolean`, which the *second* pass resolves — so there is no moment at which a reader could be
  compiled against a complete schema. Given how narrow and fixed meta-kernel's instance shapes are,
  hand-picking them is simplest — "the bootstrap can do whatever tricks it needs, including not compiling,
  just calling `new Xxx(...)`."
- **`BOOTSTRAP_CONSTRUCTORS` is the same trick one layer up.** The desugar phase needs a constructor's
  `parameters()` and its fields' `value_param` routing; for meta-kernel those would have to come from the
  entries this class is in the middle of producing, and declaration order rules out using the partial map
  (`record` applies `[record_field]` long before `array` is declared). So the routing for the three
  constructors meta-kernel applies to itself is written out by hand. The payoff is that meta-kernel's
  linked form needs no materialization either — its nine argument-bearing applications are ordinary
  declarations by the time the linker sees them.

### Schema registry and linking (`tson-compiler/TsonSchemaLinker.java`, `tson-schema/.../`, `.../registry/`)

Resolution handles one declaration at a time (references carried as unverified strings, `!!import` not
consulted). `TsonSchemaLinker`/`TsonSchemaRegistry` add the second stage. **They sit in different modules on
purpose:** the linker is a pipeline stage and lives in `tson-compiler` alongside parse/desugar/resolve/compile,
so every phase that will grow schema-side diagnostics is in one module with `Diagnostic`, and it can reach
`tson-regex` directly (what §5.4 pattern disjointness needs, with no injected-oracle seam); the registry is
storage over the `schema.meta` value model and stays in `tson-schema`, the leaf everything else depends on.

- **`TsonCanonicalIdentity.canonicalize(String)`** implements §2.2.1's canonical-identity algorithm — **not**
  general URI normalization. Exactly two reductions (strip scheme + `://`, strip query); everything else must
  already be canonical (lowercase host, no port, no dot-segments, no fragment, no percent-encoding of
  unreserved chars) or it's rejected. `http://` and `https://` resolve to the same identity; a `?sha256=`
  query is dropped, not validated. Two companions: `validate` runs the same checks and discards the result
  (so a caller checking a candidate `!!id` up front reads as such), and `sameIdentity(a, b)` canonicalizes
  both and compares — the recurring question, since a pin or a scheme never distinguishes two references.
  **Public API, not internal machinery**: `TsonSchemaLoader.load` takes a canonical identity as its
  argument, so anything implementing that seam or a `TsonSchemaSource` has to derive them the same way. It
  is the identity half of §2.2.1; `TsonContentHash` is the `?sha256=` half this one strips. Prefixed for
  the reason `TsonContentHash` is — a consumer plausibly has their own `CanonicalIdentity`.
- **`TsonSchemaLinker.link(schema, loader)`** is the pass-2 engine returning a `TsonLinkedSchema` (a thin
  wrapper that is a compile-time proof linking ran): (1) **merge `!!import`s** — each import's entries
  copied in as-is, keeping their home namespace, name collisions rejected; (2) **populate `subtypes`**
  (reverse of `supertypes`); (3) **derive `disjoint`** for every choice entry (`ChoiceDisjointness`, §5.4) —
  three-valued `Optional<Boolean>` over the cheap exact rules
  (different kind / different atom family disjoint; same-family integers by bound interval; IS-A ⇒ not
  disjoint); record-set and regex-pattern disjointness left absent (see `BACKLOG.md` for the "how far" view);
  (4) **validate** every reference
  resolves, with a type-parameter exception (a bare name valid if it's the entry's own declared parameter)
  and a **constructor-eligibility** check with two halves, the same §2.2.2 question asked from both ends
  (see `SPEC-FEEDBACK.md` #19): a locally-declared `constructor: true` entry is valid only if the schema's
  `!!meta` is exactly meta-kernel's identity, and a schema named as this one's **`!!meta` target** is valid
  only if *its* `!!meta` is — so an ordinary type library can't govern (naming core.tn as `!!meta` is the
  `!!import` confusion, and core.tn declares no constructors to supply). The target half is judged only when
  the loader actually produced the target; an unresolvable `!!meta` is left to whoever owns fetching, which
  is also what keeps meta-kernel's self-naming `!!meta` linkable mid-registration. In the shipped wiring
  `TsonCompiledMetaRegistry.loadMeta` reaches that verdict a phase earlier (it must *compile* the meta to
  resolve against it) and raises the linker's own `TsonSchemaLinker.notAMetaSchema` — one wording, one module,
  and a **`TsonSchemaValidationException` rather than an `IllegalStateException`**
  because a wrong `!!meta` is an authoring error, not a library fault (which is what lets the CLI keep exit 1
  and exit 70 apart). `source`
  validation additionally falls back to the governing meta's namespace (a `source` naming a constructor is
  one of §3.3.1's constructor roles); no other reference does. **The linker does not materialize anything** —
  `SchemaDesugarer` already turned every application into a real declaration, one phase earlier and in the
  module that can bind a constructor generically. The only argument-bearing `type_ref` it ever sees is a
  parameterized declaration's reference to its own parameter (`array<T>`), which is validated, not rewritten.
- **`TsonSchemaRegistry.register(TsonLinkedSchema)`** computes canonical identity from `!!id`, rejects a
  duplicate identity (no overwrite — this plus `entries()` being unmodifiable *is* the "locked" guarantee)
  and any self-referential `bootstrap()==true` schema, and stores it. `get(uri)` canonicalizes internally.
  `TsonSchemaLoader` (`Optional<TsonLinkedSchema> load(id)`) is the pluggable import/meta lookup hook,
  registered-only by default (nothing fetched). `TsonSchemaLinker.linkBootstrap` is the one sanctioned way
  to link meta-kernel's raw bootstrap output without registering it.

### Class 2 compilation (`tson-compiler/TsonSchemaCompiler.java`, `.../reader/`)

`TsonSchemaCompiler.compile` turns a `TsonLinkedSchema` into a `TsonCompiledSchema` — one `TsonTypeReader`
per entry, wired as real Java references rather than name lookups at read time (except where
`DeferredTypeReader` closes a cycle with one lazy lookup). `TsonTypeReader<T>` is the single-method front
door a caller holds -- **strictly one method**, `T read(TsonReadContext)`. Source form, document framing
and error policy are all the context's or the facades' concern, never overloads here.

**"Type", not "value", is the accurate half of that name.** A caller reaches one via
`TsonCompiledSchema.get(typeName)` -- it is the reader *for that declared type*, and there is exactly one
per schema entry. What it hands back is mode-dependent (`T` is a `TsonValue` in tree mode, a bound Java
object in bind mode), so naming it for its return type would be wrong in one mode or the other. It also
keeps `TsonValue` free for `tson-tree`'s own root type (`BACKLOG.md`).

- **Eager, not lazy** — `compile` walks and resolves every entry, so a caller reading only a few types
  still gets the assurance that every entry compiles, and a broken entry surfaces at compile time.
- **`CompiledReaders` is the name→reader handle every reader is given, and it is rebound once.** Name lookup
  is needed in two phases with different rightful sources: during the walk only the in-progress `Compilation`
  can answer, but a reader that resolves at *read* time (`NamedDispatchReader`/`VariantSchemaReader`/
  `VariantBindReader` picking a variant; `AnnotationTypes` resolving the type an annotation names) should be
  asking the finished, immutable `TsonCompiledSchema`. Handing readers `Compilation::resolve` directly — a
  *bound* method reference — keeps its mutable `finished`/`building` collections reachable for as long as any
  reader is, contradicting `Compilation`'s own "never escape a single compile invocation" invariant. So
  `compileWith` binds the handle to the compiled schema as its last step, **replacing** the compile-time
  delegate rather than falling back to it, which is what actually makes that invariant true.
  `CompiledReadersTest` pins the handover; a second `bind` is rejected.
- **`ErrorReader` makes eager building survive coverage gaps.** A `RuntimeException` while building one
  entry is caught and substituted with an `ErrorReader` wrapping it — the schema still compiles, only
  *reading* that entry fails, with the original message preserved. Real causes: a constructor with no
  registered factory (the undocumented atom families), or a factory that rejects one entry. `ErrorReader`
  throws unconditionally even in collecting mode (it's a library/schema-compile gap a caller can't fix by
  correcting data). A referenced-but-absent name is a stricter `TsonSchemaLinker` invariant violation and
  propagates uncaught.
- **`TsonCompiledSchema` is `sealed permits TsonCompiledMetaSchema`.** A meta-layer schema (its `!!meta` is
  meta-kernel) compiles to the `TsonCompiledMetaSchema` subtype — a compiled schema plus its governing
  constructor vocabulary — so it can go on to govern others; every other schema is a bare
  `TsonCompiledSchema`.
- **Two compile modes, both sharing one eager walk:** a **governed** compile (`compile(linked,
  TsonCompiledMetaSchema)`) dispatches each body's constructor scoped through the governing meta
  (`governedFactory`: the meta's declared vocabulary → the constructor the schema declares itself → else
  out of scope, an `IllegalStateException` deferred into an `ErrorReader`); a **standalone** compile
  (`compile(linked, ValueReaderFactoryResolver)`) dispatches through a factory set directly, no scoping —
  for reading an already-validated schema in a chosen mode.
- **Two output modes share each reader family** via a `*AbstractReader` base plus `*TreeReader`/`*BindReader`
  subclasses (`Record`/`Array`/`Map`/`Tuple`). Tree mode produces an immutable `tson-tree` `TsonValue`
  (structure-preserving, typed leaves); object-binding mode produces real bound Java objects via a
  `DataNameBinder` (`RecordBindReader` looks up each entry's `DataClass` and narrows values to the field's
  target type). `ValueReaderFactoryRegistry.tree()` /
  `.bind(DataBindContext)` are the two fixed factory tables; only `record`/`enum` (and, transitively, a
  record's container-typed fields) differ per mode. `ValueReaderFactoryResolver` (the `constructor
  name → factory` dispatch interface) lives in the unexported `reader` package — a consumer picks a mode
  by which registry they hold, never by naming it.

### The registries (`tson-compiler/{TsonCompiledMetaRegistry,TsonCompiledSchemaRegistry}.java`)

Two registries over one shared resolution core, the compiled-side counterparts to `tson-schema`'s
`TsonSchemaRegistry`.

- **`TsonCompiledMetaRegistry`** is the shared **meta/resolution core**, and *is* the on-demand
  `TsonCompiledSchemaLoader`. It owns the paired `TsonSchemaRegistry`, a bind-mode resolver, a
  `TsonSchemaSource`, content-hash verification, and the meta-kernel bootstrap. It compiles and caches
  **only meta-layer schemas** (meta-kernel, meta.tn — the name is literally accurate). Its loader
  interface is two honest methods: `loadMeta(uri) → TsonCompiledMetaSchema` (a governing meta, which must
  be compiled — its `!enum`/`!integer` instances are read into `schema.meta` objects during a governed
  schema's resolution) and `resolveLinked(uri) → TsonLinkedSchema` (an `!!import` target or a user schema
  — fetched/resolved/linked/registered but **never compiled** here). `withStandardLibrary(context,
  source)` builds a core with the three bundled schemas loaded; **core.tn is not a meta** (its `!!meta` is
  meta.tn) so it is resolve-only here — its readers are compiled per mode in a read registry when a user
  schema importing it is read, never standalone in the core.
- **`TsonCompiledSchemaRegistry`** is a **per-mode registry of compiled user schemas** over a core, built
  via `TsonCompiledSchemaRegistry.dom(core)` / `bind(core, context)`. **The read mode is which registry
  you hold**, not a compile parameter. `get(uri)` resolves through the core (`resolveLinked`) and compiles
  the linked form standalone in its own mode, cached by identity; `compile(linked)` is the uncached
  primitive.
- **Resolution is always bind-anchored, so it is delegated to the core regardless of read mode.** A
  schema's own `!enum`/`!integer` instances bind to `schema.meta.Top` objects — a tree reader's `TsonValue`
  can't stand in — so every read registry shares the one bind-mode core for resolution; only the final compile
  runs in the registry's mode (standalone: the schema's constructor usage was already validated at link
  time). The bind read registry takes the *caller's own* `DataBindContext` (their user-class name binder),
  deliberately distinct from the core's internal `SchemaMetaNameBinder`-based resolution context. A user
  schema importing core.tn gets core.tn's entries flattened into its own linked form (by `link`) and
  compiled inline, which is why the core never needs core.tn compiled.
- **Content-hash verification is per identity** (§10.2): the core records an identity's content hash on
  first resolution and checks every reference's `?sha256=` pin against it, on both fetch and cache-hit
  paths, so a conflicting pin errors rather than silently resolving to the cached instance. Verify-before-
  record, so a rejected fetch can't poison a later valid one.

### Streaming readers and read context

Every reader in `reader` pulls `TsonEvent`s directly off a `TsonEventSource` via `TsonReadContext` — no
reader ever requires a materialized `DataValue` tree, so schema-validated reading and diagnostics can
begin before the whole document is parsed. (The schema pipeline itself is not streamed — a schema document
is small and parsed once.)

- **`TsonReadContext`** is the pull cursor: `peek()`/`next()` (over one shared `TsonEventSource`),
  `position()` derived live from the last event, `path()` (RFC 6901), `field(name)`/`index(i)` (push a
  path segment), `at`/`withSchemaPosition`/`withPosition`, `report(code, message, expected, actual)`,
  `reported()`. One factory, `of(events, receiver)` (plus `throwing(events)` sugar), over one
  implementation. **The context holds no error policy**: `report` builds the `Diagnostic` from the path and
  positions it tracks and hands it to the read's **`TsonDiagnosticsReceiver`**, which decides its fate —
  `throwing()` raises `TsonReadException` at the first problem, `collecting()` accumulates into a
  `TsonDiagnosticsCollector`, and a caller's own `void report(Diagnostic)` can stream them anywhere. No
  reader branches on which. A reader needing to know whether its children complained asks `reported()` — a
  count, so it works for a receiver that keeps no list (the `int before = ctx.reported()` checkpoint idiom
  in `RecordBindReader`/`TupleBindReader`/`SchemalessObjectReader`/`AnnotationCapture`).
- **`TsonReadContext` is deliberately still exported.** `TsonTypeReader.read(TsonReadContext)` is the sole
  abstract method a consumer receives from `TsonCompiledSchema.get`, so hiding the parameter type would
  make that method uncallable and the interface unimplementable from outside — categorically worse than the
  accepted `ValueReaderFactoryResolver` `-Xlint:exports` warning, where the hidden type is only ever *returned*.
  What was removed instead is the conflation: `failFast()` (no callers) and `diagnostics()` (the receiver's
  job) are gone.
- **`of(...)` is not a whole-document read.** It assumes and performs no framing. Consuming the leading
  `DocumentStart`, and pulling *past* the root value so a lazy `TsonDataStream`'s root frame actually
  rejects trailing content, belong to `TsonTreeReader`/`TsonObjectReader`. That second half is easy to lose,
  because nothing fails when you simply stop reading — `requireDocumentEnd`'s Javadoc in both facades
  records that the pull, not the assertion after it, is the point.
- **A FIXED field's value comes from the schema, and a document that states it is checked, not obeyed**
  (§5.2). `RecordAbstractReader.verifyFixed` decodes the written token and compares it to the schema's
  value: a contradiction is `ATOM_CONSTRAINT_VIOLATION`, and the field still resolves to the *schema's*
  value. Skipping it unread — the old behaviour — let a document say one thing and decode to another in
  silence. The comparison uses a raw parsed value and the **pre-rebind** parser (`FixedCheck`), because bind
  mode narrows `precomputedValue` in place and comparing across that narrowing would flag every conforming
  document. **The two FIXED states differ in exactly one thing:** §5.2's injection rule names
  `REQUIRED_DEFAULT` and `REQUIRED_FIXED` and *not* `OPTIONAL_FIXED`, so an omitted `OPTIONAL_FIXED` field
  stays **absent** while an omitted `REQUIRED_FIXED` one is injected. Reading it the other way makes the two
  states indistinguishable and the `?` decide nothing (`SPEC-FEEDBACK.md` #39). `_` is a validation error at
  `REQUIRED_FIXED`, fine at `OPTIONAL_FIXED`; a `= _` field (`OPTIONAL_FIXED` with no value) admits only
  omission or `_`. There is no pre-seeding pass any more: every field the document didn't state goes through
  one `valueForAbsentField` switch over all five states.
- **Continuation policy: always keep reading in collecting mode.** A failed field/element is recorded and
  a `null` placeholder kept in place (so later indices stay accurate); a shape mismatch reports
  `TYPE_MISMATCH`/`WRONG_ARITY` and returns `null` so a caller doesn't also report every child as missing.
- **Every reader stamps its own schema position** first thing (`ctx.at(value).withSchemaPosition(...)`) so
  a diagnostic from inside an atom carries *that atom's* declared position. A record field never mentioned
  by the data can only be noticed after the record is consumed, so its `FIELD_REQUIRED` reports against
  the record's *opening* position (captured up front) via `withPosition`, not the live cursor.
- **Records are closed under their type** ([TSON-SCHEMA] §7.2, `RecordAbstractReader.readFields`): a field
  name the type doesn't declare is `UNRECOGNIZED_FIELD`, reported and then skipped, so a collecting pass
  finds every stray name and the value still comes back whole. The diagnostic carries the type's real field
  names in schema order (message *and* `expected`) — the information that turns a retry into a one-shot
  fix. **Not configurable**: §7.2 makes closure a MUST wherever a schema is in scope and exempts only
  schemaless records, which are read by `SchemalessObjectReader`/`SchemalessTreeReader` and never reach
  this code. **The same rule polices schema authoring**, through the same line: a constructor body is bound
  by replaying it through the governing meta's compiled reader, so `!integer ^ { minimum: 1 }` (JSON
  Schema's spelling of `min`) is rejected instead of compiling clean and constraining nothing — §5.5/§5.7
  never say so themselves, which is `SPEC-FEEDBACK.md` #40. In bind
  mode a reported record still binds to `null`, which is `RecordBindReader`'s standing rule for *any*
  diagnostic raised while it reads (`ctx.reported()` counts a whole read), not something closure chose.
- **Forward, single-pass, overwrite on a duplicate record field name** (`SPEC-FEEDBACK.md` #21) — a
  single-pass pull stream can't know a name recurs without buffering, so every occurrence is decoded
  (hence validated) and a later one overwrites an earlier (§2.5's "last value wins" by overwrite, not
  skip). A malformed *shadowed* occurrence therefore surfaces a diagnostic even though its value is
  discarded.
- **`EventSkip`** is the shared grammar-aware "consume and discard" utility (leading annotations + an
  optional type-ref as every reader's first step; a whole value; one core-value on a shape mismatch, to
  keep the stream correctly positioned). **`ListEventSource`** replays a pre-built event list — used for a
  schema default (`readSchemaDefault` wraps a literal `Token` as one synthetic event) and, via
  `DataValueEvents`, for replaying an already-resolved `DataValue` tree through a compiled reader (the one
  place `resolver` still has a `DataValue` in hand).

### Diagnostics (`Diagnostic`, root package)

`Diagnostic` is the structured value every `TsonDiagnosticsReceiver` receives, identical shape whichever
one is in play: a closed `Code` enum (`FIELD_REQUIRED`/`TYPE_MISMATCH`/`WRONG_ARITY`/`UNKNOWN_TYPE_REF`/
`ATOM_CONSTRAINT_VIOLATION`/`UNRECOGNIZED_FIELD` from readers; `SCHEMA_ERROR`/`UNKNOWN_TYPE`/
`VALIDATION_ERROR` for infrastructure-level failures; `DUPLICATE_MAP_KEY` reserved but unproduced),
`message` (hand-composed per call site), `expected`/`actual` (machine-parseable), and **four location
components covering two ends** — the value in the data, and the rule in the schema.

**The four are JSON Schema 2020-12 §12's own output unit**, deliberately: `path` is `instanceLocation` (an
RFC 6901 pointer into the data), `schemaPointer` is `keywordLocation` (an RFC 6901 pointer into the schema's
`map<type_name, type_definition>`, `/my_type`), `schemaId` plus `schemaPointer` are
`absoluteKeywordLocation`, and `dataPosition`/`schemaPosition` add the line/column/byte-offset TSON needs and
JSON Schema has no equivalent of. **One record rather than separate data- and schema-diagnostic types,
because the variation is locational, not categorical** — a value violating `int32` as core.tn declares it
populates both ends at once, and `javax.tools.Diagnostic`, LSP's `Diagnostic` and rustc's `DiagInner` all
model it the same way (rustc's `MultiSpan` being the mature form of the same idea).

Either end may be empty: a schema-side problem has no data, and a schemaless read has no schema.
**`schemaPosition` comes from `TypeDefinition.position()`**, which is
populated because `SchemaResolver.resolveSchema` takes `TsonSchemaParser.declarationPositions()` and passes
each declaration's own position into `DefinitionResolver.resolve` — so a value error points at both ends, the
value in the data and the type it violated in the schema. Every reader stamps its own position first, so the
one reported is the *atom's* declaration (`int32` in core.tn), not the enclosing record's. **The read path
populates `schemaPosition` but not `schemaId`/`schemaPointer`** — a reader knows the declaration position it
stamped, not which entry of which schema it came from, so which schema a read diagnostic's position refers to
is still implicit; the schema path populates all three (`BACKLOG.md`).
An atom's `AtomTypeException` is caught in `AtomTypeReader` and mapped to
`ATOM_CONSTRAINT_VIOLATION` — `AtomType`'s own signature is untouched, since it's shared with the
schemaless binder which has no read context. Out of scope for now: message synthesis from code + params,
fine-grained atom codes, `DUPLICATE_MAP_KEY` (detectable at `MapAbstractReader.readInto`, which sees every
entry — but §2.6 makes a duplicate key a SHOULD NOT that *warns*, and `Diagnostic` has no severity axis, so
reporting one would fail a conforming document; `BACKLOG.md` has the shape of that work), and per-field
schema positions.

### Schema-side diagnostics (`SchemaResolver`, `TsonSchemaLinker`, `Tson.validateSchema`)

A broken *schema* reports every independent problem in one pass, through the same
`TsonDiagnosticsReceiver` the read path uses. §8.1 asks for both halves of this: implementations MUST carry
source position in **all** error reports, and SHOULD "continue processing after an error to report multiple
issues in a single pass" — and it explicitly puts schema resolution/compilation failures in the *resolver
error* category, so this is the same layer, not a new one.

- **Two reporting overloads, `SchemaResolver.resolveSchema(document, positions, receiver)` and
  `TsonSchemaLinker.link(schema, loader, receiver)`.** The existing overloads are untouched and still throw
  at the first problem. **The fail-fast paths deliberately do not route through
  `TsonDiagnosticsReceiver.throwing()`** — that raises `TsonReadException`, and a schema that fails to
  resolve is not a read failure; the CLI's exit 1 against exit 70 turns on the distinction. They rethrow the
  original untouched.
- **The resolver catches inside its memoized `namespaceGetter`, not around the driving loop.** Resolution
  follows dependencies, not source order, so a failure usually happens inside a *nested* resolve; catching at
  the loop would attribute it to whichever declaration triggered it and then report the real one a second
  time. The memo makes it exactly once, against itself. Same shape as
  `TsonSchemaCompiler.Compilation.resolve` substituting an `ErrorReader` one phase later.
- **A failed declaration leaves an empty-record placeholder**, so its dependents still resolve. That is
  javac's error-type contract (it answers every question) rather than Swift's (every questioner must check
  first), and the choice is load-bearing: a `Sum`-bodied placeholder makes `parent => child & { ... }` fail
  *because* `child` did, reporting a consequence beside its cause. Swift's other half is kept — producing one
  means a diagnostic was already reported. It never escapes a reporting resolve, so it needs no `TypeKind`
  of its own.
- **`Tson.validateSchema(schemaText)` is the front door and owns the phase boundary** — the schema-side peer
  of `validate`, and the only caller that composes the two phases. Every declaration resolves before a
  verdict; linking runs only if resolution was clean, so a schema with a broken declaration *and* an
  unresolved reference reports the declaration alone (the reference may well resolve once the declaration
  does). This is where javac and Swift both draw it: javac attributes every entry before
  `shouldStopPolicyIfError` blocks the next phase, Swift never reaches SILGen after a Sema error. **A schema
  that reported anything is never registered.**
- **Only `TsonSchemaValidationException` becomes a diagnostic.** An `UnsupportedOperationException` is a
  library gap and keeps propagating — a gap is not a verdict on the author's schema. The test for which is
  which, from Swift's treatment of `expression_too_complex`: *a schema error's verdict doesn't change when
  this library improves; a gap's does.*
- **What still throws even with a receiver:** an `!!import` that won't load, or a `!!meta` that may not
  govern. Those make the namespace itself unusable rather than one entry wrong, and continuing would report
  a page of unresolved references that are all consequences of the one real problem. `Tson.validateSchema`
  catches them and reports against RFC 6901's root pointer (`""`), since they concern the document rather
  than any declaration.
- **Still fail-fast:** desugaring and compilation. Compilation already keeps going via `ErrorReader`, but
  that marks a *library gap* (an unregistered atom factory), which is a different question from an author
  error.

### Read facades: `TsonObjectReader`/`TsonTreeReader` (root package) + `TsonObjectWriter`

`TsonObjectReader` (to a bound Java object) and `TsonTreeReader` (to a `TsonValue` tree) are the two
consumer read front doors, named for what a consumer holds, matching Jackson's `ObjectReader`/`readTree`.
Each is **dual-mode, fixed at construction**: built standalone (`new TsonObjectReader(ctx)` / `new
TsonTreeReader()`) it's **schemaless** (Class 1 — the target class, or the wire, is the whole contract;
any `!!schema` the document declares is ignored, Jackson-style); obtained from a `Tson` facade
(`objectReader()`/`treeReader()`, carrying a configured `TsonSchemaSource`) it's **schema-aware** — a
self-describing document is validated against its declared `!!schema` as it's read (the schema resolves
through the source, the root type-ref selects the type), else read schemalessly. `readWithoutSchema(...)`
forces the schemaless path on a schema-aware reader.

**These two are the whole document-reading surface**, and both derive Jackson-`ObjectReader`-style rather
than taking parameters, so source form, error policy and schema selection stay orthogonal instead of
multiplying overloads: `withDiagnostics(receiver)` swaps fail-fast for any other receiver,
`preservingUnknownTypeRefs()` relaxes the schemaless type-ref rules below, and
`withSchema(uri).readAs(source, typeName)` covers data that *isn't* self-describing — the caller supplies
what a `!!schema` plus a root type-ref would have said, and validation is identical either way. Each returns
a new reader **sharing** the original's compiled-schema registry, never rebuilding it. A
`TsonTypeReader` from a compiled schema is the layer underneath: a strict single-method interface that
reads one value at a cursor and polices nothing around it.

- **The class-driven binding / tree-building mechanics live in the internal `reader` package**
  (`SchemalessObjectReader`/`SchemalessTreeReader`, unexported); the public readers are thin facades that
  peek the `DocumentStart` for a `!!schema` and dispatch to either the compiled schema registry or the
  schemaless engine. The whole-document entry points (`read`/`readWithoutSchema`/`readAs`) own document
  framing — consuming the leading `DocumentStart`, and the `requireDocumentEnd` pull that makes the lazy
  stream check for trailing content; the low-level `read(TsonReadContext, …)` is frame-free (a value at the
  cursor, for a caller managing their own context) and always schemaless.
- **A failure reaching the schema is a diagnostic, not an exception.** An unresolvable `!!schema`, a missing
  root type-ref, a root type the target class can't hold: each reports through the receiver and skips the
  root value (so the stream still lands on `DocumentEnd`). Under `throwing()` that is indistinguishable from
  the old behaviour; under a collector they arrive as `Diagnostic`s, which is what lets `Tson.validate`
  delegate to `treeReader()` wholesale instead of re-deriving anything.
- **A schema that *resolves* badly reports like one, even mid-read.** `tree.get(uri, receiver)` /
  `bind.get(uri, receiver)` (over `TsonCompiledMetaRegistry.resolveLinked(uri, receiver)`) resolve and link
  the named schema collecting, so validating a data document against a broken schema reports **every**
  declaration at fault — the same account `tson compile` gives, since the schema is equally broken either
  way. Those diagnostics go to the **facade's own receiver, not through `ctx.report`**, which would rebuild
  them from the *data* cursor: stamping a data position on a problem that is in a schema and discarding the
  `schemaPointer`. The registry caches nothing for a schema that reported, so a second read reports again
  rather than appearing to succeed. Distinct from the bullet above: a schema that can't be *reached* (no
  such URI, malformed, wrong `!!id`) is still one diagnostic, because there is nothing to enumerate.
- **A schemaless read checks its type-refs, and `TypeRefCheck` (in `reader`) states the rules once** for
  both engines. Given `!X` on a value: (1) `X` **is** a `BuiltinTypeVocabulary` name → it must sit on a
  token (`TYPE_MISMATCH` otherwise) and that token must satisfy the atom
  (`ATOM_CONSTRAINT_VIOLATION`); (2) `X` **names the target** being bound → accepted, object-binding
  only, a tree read having no target; (3) otherwise it links to nothing → `UNKNOWN_TYPE_REF`.
  **Rule 3 is a reader policy, not a parsing one** — the parse step still preserves every marker per §5.1;
  what a reader *type-checking* a value does with one it can't link is the layer above, where a
  case-sensitive typo (`!Uuid`) silently disabling the author's intended validation is the worse failure
  (`SPEC-FEEDBACK.md` #7, whose suggested resolution this is). `preservingUnknownTypeRefs()` on either
  facade opts out of rule 3 only — built-in names stay checked — and is what round-tripping through
  `TsonTreeWriter`, or reading the wire of a document whose `!!schema` is deliberately out of scope, wants.
- **Rule 2 is looser for a container than for an atom, deliberately.** `TypeRefCheck.names` (a `@Typename`,
  else the simple class name case-insensitively — the same match `bindUnion` gives union members) is what
  lets `!point { x: 3  y: 4 }` bind to a Java `Point` with nothing annotated. An atom position takes
  `TypeRefCheck.declares` (`@Typename` only), because the loose match would accept a `UUID`-targeted
  `!Uuid` on the strength of the class being *called* `UUID`. Consequence worth knowing: a collection
  target answers to no wire name, so `!tags [ "a" ]` into a `List<String>` is `UNKNOWN_TYPE_REF`.
- **Reporting never abandons the value.** A reported type-ref still yields its node/object and its children
  are still read, so one collecting pass finds everything; a leaf whose atom rejected the token becomes a
  `TsonNull` keeping its wire type-ref (the placeholder `AtomTreeReader` already uses). `SchemalessTreeReader`
  scopes `ctx.field`/`ctx.index` as it descends, so a diagnostic carries a real RFC 6901 path.
- **`TsonObjectReader`'s schema-aware `read` checks the target class up front** — the schema's root type
  already binds to a Java class via the name binder, so a class not assignable to that is a `TYPE_MISMATCH`
  reported *before* the value is read, not a cast failure after.
- **Both tree read paths capture wire annotations** onto each node's `annotations()`, at every position §3.1
  permits one (root, record field value, array element, both sides of a map entry, and recursively an
  annotation's own value). `AnnotationCapture` (in `reader`) is the shared helper: an annotation's value
  events are buffered and replayed through a schemaless tree read via `ListEventSource`, so the recursion
  needs no special case, and an annotation's value is *always* read schemalessly — its type resolves one hop
  against the governing namespace (§3.3.3), not against the compiled readers in scope. `EventSkip.typeRef`
  is split out of `annotationsAndTypeRef` so capture and discard share the framing's second half.
- **The schema-driven readers capture by hoisting, not by widening signatures.** A compiled tree reader
  shares its `*AbstractReader` base with the bind subclass, and the base consumes the framing where the node
  isn't built. Rather than thread annotations out of four shared shape-check methods (making bind mode carry
  a field only tree mode reads), each tree reader — and the `AtomTreeReader`/`AbsentTreeReader` wrappers —
  captures *first*, then calls the base/delegate, whose own framing call then finds nothing left. That's a
  no-op precisely because every one of those readers **discards** the framing result rather than using it.
  Bind mode is untouched.
- **A dispatched value gets its annotations re-attached afterwards.** `NamedDispatchReader`/
  `VariantSchemaReader` must consume the annotations to reach the `!typeName` they dispatch on, so the reader
  that builds the node never sees them; they're put back with `TsonValue.withAnnotations` (a pure `tson-tree`
  operation). Nothing flows through the context and `TsonTypeReader.read` is unchanged — a dispatched
  annotation flows *into* the delegate's result, which is the opposite direction from the
  `DiagnosticsReceiver` problem. Bind mode is unaffected by construction (re-attachment only acts on a
  `TsonValue`), and `AnnotationTypes.DISCARDED` keeps it from validating at just the positions that happen to
  route through a capturing reader.
- **A schema-driven read also type-checks annotations** (§6: an annotation *names a type*). `AnnotationTypes`
  resolves the name against the governing schema (§3.3.3's one hop — for a data document that's the
  `!!schema` target, i.e. the very schema the readers were compiled from) and the value is read by *that
  type's* compiled reader, so a wrong-typed value fails for the ordinary reason any wrong-typed value fails.
  Read-time resolution is safe because `compile` is eager; the lookup is gated on `schema().entries()`
  because the resolver throws for an unknown name. An unresolvable name reports `UNKNOWN_TYPE_REF` and the
  annotation is **still kept** (§1.5 requires preserving what a processor doesn't act on); §6's bare `@T` is
  checked as `@T:_` by reading a synthetic absent through the reader. The schemaless path checks no
  annotation *name* — no governing schema, no type to resolve against — but an annotation's **value** is a
  data-value, so the type-ref rules above reach into it: it is read by the enclosing reader itself, hence
  exactly as strictly. The one exception is the schema-driven *fallback* (a name the governing schema
  doesn't declare), which reads its value through a **preserving** reader — §1.5 already keeps an annotation
  nothing can interpret, and rejecting its innards would take that back. This is deliberately stricter than
  Class 2 conformance requires, which asks for nothing at all here (`SPEC-FEEDBACK.md` #29).
- **`TsonTreeWriter` re-emits them** — `TsonDataEmitter` gained `annotation`/`beginAnnotation`/
  `endAnnotation` (the valueless form's trailing space is load-bearing, §3.1) and `writeNode` writes a
  node's annotations ahead of its type-ref, per §7.4's `*annotation [type-ref] core-value` order, so a tree
  round trips with its metadata, not just its values. **`TsonObjectWriter` re-emits a carrier's too** —
  §7.4's order is why `write` splits into `write` (annotations, then the value) and `writeCore` (the shape
  switch): `writeUnion` writes a type-ref of its own, so it emits its member's annotations *before* that and
  goes straight to `writeCore`, rather than recursing and landing them after it. An annotation's value writes
  back in whichever form the read produced — a bound object like any other value, a structurally-kept
  `TsonValue` through `TsonTreeWriter`'s own node emission (package-private, both writers share a package).
- **`SchemalessObjectReader` streams events** (like the compiled readers), walking the descriptor in
  parallel — never materializing a tree first. Problems report through a `TsonReadContext` (fail-fast throws
  `TsonReadException`; collecting accumulates), and a `tson-bind` `DataBindException` while narrowing /
  applying a bridge / invoking a constructor is caught and re-reported through `ctx`, so a caller sees one
  uniform error model regardless of which layer noticed. **No positional form and no schema-composed
  defaults** — both are schema-layer concepts a class-driven bind has no equivalent for (a record must be
  braced; an absent required field is `FIELD_REQUIRED`).
- **`TsonObjectWriter.toTson` is mainly a debugging tool**, not a guaranteed-lossless serializer (integer
  width, tuple-ness, and captured wire annotations are documented write-side losses). Both throw unchecked
  (`TsonReadException`/`TsonWriteException`), so the pair is symmetric and a caller writes neither a
  `throws` clause nor a try/catch for the common path.
- These live in `tson-compiler`'s root package (not a separate module) because `DefinitionResolver`
  depends on `TsonObjectWriter` (atom-refinement merging) — a module depending *on* `tson-compiler`
  couldn't provide them without a cycle. `tson-bind` (what they're built on) has no such dependency.

### Tree model: `TsonValue` (`tson-tree` module)

What every tree read hands back — the compiled tree readers above and the schemaless `TsonTreeReader`
alike. A sealed `TsonValue` over eight pure immutable node types (`TsonRecord`/`TsonMap`/`TsonArray`/
`TsonTuple`/`TsonAtom`/`TsonNull`/`TsonAbsent`/`TsonMissing`), **structure-preserving** — TSON's
record-vs-map and array-vs-tuple distinctions survive into the model, where JSON's would collapse — and
annotation-aware, every node carrying its own `typeRef()` and `annotations()`.

- **The names are chosen against Jackson, not in a vacuum.** No node carries a `Node` suffix, because
  Jackson ships `ArrayNode`, `NullNode` and `MissingNode` — a consumer using both libraries in one file
  would otherwise fully qualify every one. The sealed shape independently matches JEP 540's
  `JsonValue`/`JsonObject`/`JsonArray`/`JsonNull` (Simple JSON API, incubating in JDK 28), with
  `TsonRecord` + `TsonMap` staying *more* precise than `JsonObject`, which cannot distinguish the two.
- **Navigation is lenient but not silent.** `get`/`at` never throw, and the `TsonMissing` they return
  carries `path()` — the RFC 6901 pointer of the step that *failed*, relative to the node navigation
  started from — so `at("/a/b/c")` distinguishes "no `b`" (`/a/b`) from "`b` had no `c`" (`/a/b/c`). Every
  missing comes from a navigation step, so there is no singleton and equality is by path; read it without a
  cast via `TsonValue.missingPath()`. The first failure sticks — stepping on past a missing returns the
  same node rather than extending its pointer. "Missing" (not in the tree), "null" (the `null` token) and
  "absent" (the `_` sentinel) stay three distinct kinds.
- **Two families of value accessor, and the split is the point.** `as(Class)`/`asString`/`asNumber`/
  `asBigInteger`/`asBigDecimal` only ever **cast** (`isInstance`), so they answer "what host type did the
  read produce?" — an `int32` field holding an `Integer` gives empty from `asBigInteger()`. `asInt`/
  `asLong`/`asDouble` (`OptionalInt`/`OptionalLong`/`OptionalDouble`, so a hot path doesn't box)
  **convert**, and answer "what number is this?" regardless of host type. Conversion is exact for the
  integral pair — an integral fractional part converts (`123.0`, `234.56E2`), a real one doesn't, and
  out-of-range yields empty rather than wrapping — while `asDouble` accepts nearest-double rounding
  (demanding exactness would reject `0.1`) but rejects a magnitude that can't be finite, so nothing ever
  reads back as `Infinity`. Text is never parsed: `"42"` is a string per §4.4. A test asserting *which*
  host type a reader produced must therefore use `as(Class)`, not `asInt()`.
- **Read-side only, deliberately.** There are no copy-on-write transforms and no builders; construction is
  the static `of(...)` factories. `TsonTreeWriter` already closes the read→edit→write loop, so an editing
  API waits on a concrete produce/edit use case (`BACKLOG.md`).

### Front door: `Tson`/`TsonConfig` (`tson` module)

A small module over `tson-compiler`, the consumer entry point. `Tson.builder().build()` bootstraps
meta-kernel/meta.tn/core.tn into a governed environment and returns an immutable `Tson`.

```java
Tson tson = Tson.builder().build();
tson.resolve(schemaText);                      // registers the schema by its own !!id
TsonValue value = tson.treeReader().withSchema(schemaId).readAs(dataText, "my_type");
```

- **The read mode is which registry you hold:** `treeRegistry()` (an immutable, queryable `TsonValue` tree)
  and `bindRegistry()` (real Java objects, bound via `dataBindContext()`), both over one shared bind-mode
  resolution core.
  `resolve(schemaText)` resolves/links/registers and takes *no* mode — resolution is always object-binding
  internally (it binds meta instances to `schema.meta.Top`), and only a registry's own `compile`/`get`
  picks a mode.
- **`validate(String|InputStream)` *is* `treeReader()` with a collecting receiver**, both halves of it —
  one try/catch over one call, no second implementation. The reader already works out whether a schema
  applies (a `!!schema` directive selects the schema through `TsonConfig.schemaSource`, compiled once in
  tree mode, and the root type-ref selects the type; with no `!!schema` it reads schemalessly, checking
  the wire's own type-refs), and reports every failure around all of that through the receiver. Validating
  is that read with the tree thrown away. Returns every problem as a `List<Diagnostic>` (empty means valid)
  and **never throws for a bad input document** — malformed syntax, a schema document handed in where data
  was expected, an unresolvable schema, an unknown type all come back as diagnostics.
  - **The `InputStream` overload is the body; the `String` one delegates into it.** The reader underneath
    decodes UTF-8 bytes, and the CLI already holds a stream. (The old direction buffered stream→`String`
    only because a separate schemaless branch had to be re-readable.)
  - Base-syntax failures are converted by **`Diagnostic.ofBaseSyntaxError`** — in the root package because
    two of the three exception types live in the unexported `lexer` package, so `Tson` in another module
    can't name them in a `catch`. It returns a `Diagnostic` and **rethrows anything else**: "never throws
    for a bad *document*" is not "never throws", and laundering a library fault into a diagnostic would
    report a false verdict and bury the stack trace. (`tson validate` puts that verdict back on — a
    `BACKLOG.md` item.)
- `objectReader()`/`treeReader()` return **schema-aware** `TsonObjectReader`/`TsonTreeReader` over this
  instance — the value-returning read peers of `validate`: a self-describing document is validated against
  its declared `!!schema` (schemaless when it declares none), the object form checking the target class up
  front. **Both are built over this instance's own `treeRegistry()`/`bindRegistry()`, so every reader shares
  one compiled-schema cache** — a schema compiles once per `Tson`, not once per reader. The readers take a
  `TsonCompiledSchemaRegistry` rather than a `TsonCompiledMetaRegistry` for exactly that reason; since the
  read mode isn't visible in the registry's type, each constructor checks a package-private `mode()` and
  rejects the wrong one up front instead of failing on a cast at the first value. `objectReader()`/`objectWriter()` bind to this instance's `dataBindContext` (configurable via
  `TsonConfig.dataBindContext`, default `TsonAtomContext.defaultContext()`). `schemaRegistry()`/`loader()`
  reach the underlying machinery.

### Bundled schema documents (`tson-schema/TsonBundledSchemas.java`)

The published identities of the three bundled schemas (`META_KERNEL_ID`/`META_ID`/`CORE_ID`) **and** their
raw source text (`fetch(uri)`), off `tson-schema`'s classpath — the `.tn` resources are copied from
`spec/m/` at build time (`processResources`), so there's one copy on disk to keep in sync with the spec.
`fetch` doesn't implement `TsonSchemaSource` (that would need a `tson-compiler` dependency), but a
`tson-compiler` caller passes the method reference `TsonBundledSchemas::fetch` directly (it's a functional
interface of the same shape). Each schema also ships a published content digest
(`{META_KERNEL,META,CORE}_SHA256`), checked against the packaged resource on load.

### Content hashing (`TsonContentHash`) + `tson hash`

`TsonContentHash.sha256(byte[])` computes a document's content hash over every byte *past the first line's
terminator* (the `!!id` line, grammar-required first, is excluded so a document can carry its own hash on
its own id line without circularity; a leading BOM is stripped, never hashed). `TsonContentHash.verify(bytes,
referenceUri)` parses a reference's `?sha256=` pin (§2.2.1: only hash-algorithm parameters, never silently
retained — an unrecognized query parameter or malformed hex throws) and checks it, throwing
`TsonContentHashMismatchException` on mismatch. Both carry the `Tson` prefix as consumer-facing types whose
bare names (`ContentHash`, a `hash` mismatch) a consumer plausibly has their own version of — and "content
hash" is the spec's own term throughout §2.2.1/§10.2, never shortened to "hash".

- **The pin is verification metadata, not identity** — a pinned `!!id` resolves against a plain (or
  differently-pinned) reference because everything keys by canonical identity (scheme+query stripped).
- **Verification is wired through the loader:** every fetched reference carrying a pin is checked before
  use — the top `!!schema` and each transitive `!!import`/`!!meta`. The bundled chain is pinned end to end
  (meta.tn pins meta-kernel, core.tn pins meta.tn), so bootstrap verifies the whole chain. The one
  identity that can never carry a pin is meta-kernel's self-`!!meta` (its hash input would have to contain
  the pin) — its `!!id` is pinned, its self-`!!meta` stays plain, and identity comparison is canonical so
  the two still name one identity. `crossCheckId` additionally verifies a fetched document's embedded
  `!!id` equals the reference's identity (§2.2.1 — a source can't return content under the wrong
  identity).
- **`tson hash <file>`** stamps `?sha256=<hex>` onto the `!!id` in place (idempotent; the hashed bytes
  never change so the pin stays valid).

### CLI (`tson-cli`)

`tson validate [--output text|json|tson] <file|->...` takes a **flat list of files**, auto-classifies each
as schema or data (a header peek — `!!id`-carrying schema vs `DocumentStart` data), exposes the schema files
through a `TsonSchemaSource`, and validates each data document via `Tson.validate` — the `!!schema` URI
selects the schema, the root type-ref selects the type, no `!!schema` means schemaless. **Fully
self-describing: no `--type`.**

**`-` is standard input, at most once, and always a data document.** `ValidateInput` is the sealed argument
type (`OfFile`/`OfStdin`) that keeps this out of `Path`-with-a-magic-value territory; its `OfStdin.open()`
suppresses `close()`, since `System.in` belongs to the process rather than to one read. Piped input is never
*classified*, because classification opens a document a second time and a stream has nothing to reopen — so
schemas stay files, and that rule is a consequence of the design rather than a restriction bolted onto it. A
second `-` is a `UsageException`: one stream, consumed by the first read, so the second could only ever
report an empty document as valid. Only the bare argument matches, leaving `./-` for a file actually named
`-`. `cannotRead` names the failure kind (`NoSuchFileException` and friends carry it in the exception type,
not the message, so the obvious concatenation renders `cannot read x: x`). The facade owns the whole per-document decision; the CLI just classifies files into a source
and calls it. Also `tson compile <schema>` (checks a schema compiles, tree mode), `tson hash <file>`, `tson
init-example [<dir>]` (writes a working `person.tn`/`person-data.tn`). The installed command is `tson`
(`application.applicationName`), launched on the classpath.

**`validate` emits one `ValidationRun` envelope per invocation, whatever the file count** — `{ valid, files:
[{ file, valid, errors }], errors }`, so `--output json`/`tson` is a single parseable document with each
verdict's filename *inside* it. Only `--output text` keeps the `# <file>` header (and only when there's more
than one file): a label outside the object is right for a person and is exactly what made the machine
formats unparseable. **The two error lists are the two exit codes**: run-level `errors` holds only what
stops the invocation before any document is read (an unreadable file during classification, a schema with no
`!!id`, no data files at all) and is exit 2, while a document that read but didn't validate lands in its own
`FileReport` at exit 1 — so a consumer tells "your invocation was wrong" from "your document was" without
reading messages. `compile` renders a bare `ValidationReport` instead, having one schema and nothing to
name. Every file's report is collected before anything prints, since the envelope's verdict is the AND
across them.

**Exit codes: 0 all valid, 1 any data file invalid** (bad value / unresolvable `!!schema` / unknown type /
no root type-ref), **2 usage/classification** (no data files, an unreadable/`!!id`-less schema, a bad
flag), **70 (`EX_SOFTWARE`) a fault in the library**, whose stack trace prints to stderr. That fourth code
is what makes `Diagnostic.ofBaseSyntaxError`'s rethrow worth anything: the read loop catches only
`IOException` (an unreadable file *is* that file's verdict), so a `RuntimeException` — which `Tson.validate`
raises only for a bug, never for a bad document — reaches `TsonCli`'s own handler instead of being folded
into "invalid". `UsageException` exists for the same reason one layer up: a bare `IllegalArgumentException`
catch would relabel a library fault as "your command line is wrong", so only this CLI's own argument
parsing throws the type that means that.

### Configuration package (`tson-compiler/.../config/`)

Holds `TsonAtomContext`, `SchemaMetaNameBinder`, `SourcePositionStringBridge` — how a caller configures a
working binding environment. **Two distinct default contexts, differing by exactly the name binder** (a
`DataNameBinder` is fixed at `DataBindContext` construction and can't be added later):

- `TsonAtomContext.defaultContext()` — the library's built-in atom registrations (`UUID`/`byte[]`/
  `LocalDate`/`OffsetTime`/`OffsetDateTime`/`URI`/`Inet4Address`/`Inet6Address`/`SourcePosition`), **no
  name binder**. The consumer/schemaless default (`Tson.dataBindContext`, `objectReader`/`objectWriter`,
  and the base a consumer layers their own binder onto). `registerDefaults(builder)` applies the same atom
  list to any builder, so the list lives in one place.
- `SchemaMetaNameBinder.defaultContext()` — those same atoms **plus** a `DataNameBinder` scoped to the
  `io.ltr8.tson.schema.meta` namespace. The library's *internal* object-binding-mode resolution context
  (binds meta instances to `schema.meta` classes). Not a consumer default — a consumer binding their own
  classes supplies their own binder.

Removing `config` entirely (folding these elsewhere) is a `BACKLOG.md` item.

### Conformance suite (`ConformanceSuiteTest`)

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
- **Undocumented atom constructors** — `unknown`/`cidr4`/`cidr6` (and `extern`, which has no core.tn
  declaration) have no compiled-parser factory, so they compile to `ErrorReader` (a schema merely
  *declaring* one still compiles). `complex`/`ipv4`/`ipv6`/`mac`/`email` do have parsers. **`email` is
  seeded into `BuiltinTypeVocabulary` although §5.5's table has no row for it** — a known departure like the
  integer ladder, because core.tn groups it with its siblings identically and withholding it would only make
  the two read paths disagree (`SPEC-FEEDBACK.md` #5). Its format check is a documented subset of RFC 5322 —
  the `dot-atom` core, without quoted local parts, domain literals or comments.
- **`uri_type`/`regex_type` object-binding** — their RFC-citation field is nested inside `specification:
  AtomSpecification` rather than flat, so it never receives a schema-composed default the way
  `email_type`'s flat `spec` field does. Subtype *dispatch* to them works; this is a narrower field-binding
  gap.
- **Schema-side diagnostics, the remainder** — resolution and linking report through a
  `TsonDiagnosticsReceiver` now (see "Schema-side diagnostics" above); three things are left. **Desugaring
  is still fail-fast**, so a sugar-form error aborts before resolution reports anything. **A read-path
  diagnostic carries `schemaPosition` but no `schemaId`/`schemaPointer`**, which needs the compiled schema's
  identity threaded down the reader stack. And **the throw-site classification is only done inside
  `DefinitionResolver`** — the same pass is still owed everywhere else. `BACKLOG.md` has the census.
- **Deferred design questions** — `REQUIRED_FIXED`/`OPTIONAL_FIXED` value validation, `value_param` real
  parameter substitution, thread-safety, and a general disk/HTTP-backed `TsonSchemaSource` (with
  whitelist/blacklist policy).
- **§9.1's numeric-literal length limit** (SHOULD, DoS-hardening) — not enforced.
- **JSON** — a future JSON reader is a whole separate stack (its own `JsonEventStream` and its own readers,
  deliberately not reusing the TSON readers). Not started, not backlogged.
