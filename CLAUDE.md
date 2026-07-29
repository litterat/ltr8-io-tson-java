# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A from-scratch Java implementation of TSON (Typed Schema Object Notation), built directly against the
TSON spec series:

- Part 1 (lexer, structural grammar, base type resolution, built-in type vocabulary):
  https://tson.io/raw/2026/32/tson-part1-data.md
- Part 2 (schema grammar, type system) — grammar layer complete (see `TsonSchemaParser` below);
  resolution well underway (see `TsonSchemaResolver` below) — `meta-kernel.tn1`, `meta.tn1`, and
  `core.tn1` all resolve and register in full (49, 90-merged, 48 declarations respectively).
  `boolean => !enum [true false]`'s own once-permanent generic-binding limitation was fixed
  2026-07-26 when `bindAtomInstance` moved onto the compiled reader (verified:
  `DefinitionResolverTest.booleanInstanceResolvesCorrectlyViaTheCompiledReader`); core.tn1's own
  end-to-end *resolve-and-register* pass is covered by `CoreSchemaImportTest` (2026-07-28, see
  "Schema registry" below) — `CoreTn1Parser`/`CoreTn1ParserTest`/`CoreTn1CompiledEndToEndTest`
  themselves stay retired (see "Not yet implemented" below): https://tson.io/raw/2026/32/tson-part2-schema.md

The spec is a *working revision* (2026 series) and changes between revisions without compatibility
guarantees — re-fetch the current URL rather than trusting a cached copy of the text when in doubt, and
check the revision number at the top of the document.

`spec/` holds local snapshots fetched 2026-07-22 (Part 1) and 2026-07-23 (Part 2), revision 32, for quick
reference without re-fetching every session: `spec/tson-part1-data.md` (Part 1, verbatim),
`spec/tson-part2-schema.md` (Part 2, verbatim), and `spec/m/{core,meta,meta-kernel}.tn1` — the pre-loaded
meta-kernel bootstrap layer, the canonical meta-schema built on it, and the core type library built on
that (Part 2 schema documents, reachable via `!!meta`/`!!import` chaining from `core.tn1`) — plus their
non-normative resolver-output fixtures `spec/m/{core,meta,meta-kernel}-resolved.tn1` (Part 2 §1.5), the
target shape the resolution layer (`TsonSchemaResolver`, `MetaKernelBootstrapResolver`) now largely produces. The
three source `.tn1` files are what §5's
built-in type vocabulary formally resolves to when a schema *is* in scope — useful ground truth for
constraint details (e.g. `integer_type`'s bit-width bounds formula) even though a Class 1 (schemaless)
processor never parses or executes them. Treat this directory as a cache, not a source of truth: if a
revision bump is suspected, re-fetch from the URLs above rather than trusting these files.

**Hard constraints for this codebase:**
- Java 25 only.
- No external runtime dependencies (main code). JUnit (Jupiter) is permitted for tests only.

## Spec feedback — this is the first implementation

The spec is a working draft (2026 revision series, explicitly "subject to change without compatibility
guarantees"). This is its first implementation, which makes it the first real test of whether the spec's
prose actually resolves unambiguously to one behavior — that's valuable to the spec author precisely
*because* it's still a draft. When implementing against the spec, actively watch for and flag:

- **Ambiguity** — wording that a careful reader could reasonably implement two different ways.
- **Internal inconsistency** — two sections (or a grammar production and its surrounding prose) that say
  different things.
- **Underspecification** — a case the grammar/prose visibly doesn't address (an edge case, an empty-input
  variant, an interaction between two rules) where an implementation still has to pick *something*.
- **Errors** — anything that looks like a plain mistake (wrong section cross-reference, grammar that
  doesn't parse its own examples, etc.).

When you find one: say so in conversation when it comes up, and record it in `SPEC-FEEDBACK.md` at the
repo root (create it if it doesn't exist) — one entry per issue, with the spec section, a concrete
description of the problem, the interpretation this implementation chose and why, and a suggested
resolution if you have one. Don't silently pick an interpretation and move on without a record; a
resolved ambiguity is exactly the kind of thing that's invisible again three sessions later unless it's
written down. This applies to every layer as it gets built, not just the lexer.

## Architecture

### Javadoc conventions: current form only, no change history

Javadoc (on a class, field, or method, in the actual `.java` source) documents that element's
*current* contract and behavior — not how it got there. Do not write dates, "renamed from X",
"used to do Y, now does Z", "on the user's own direction", "an earlier version of this method",
"widened/narrowed on <date>", or similar changelog framing into source Javadoc. If a design choice
needs a WHY (and many genuinely do — e.g. why a namespace lookup is a `null`-returning interface
rather than a `Map`), state the current invariant and its rationale directly, not the sequence of
edits that produced it. A reader six months from now needs to understand the code as it stands, not
relive its editing history.

This is specific to Java source Javadoc — it does *not* apply to this file (`CLAUDE.md`), which is a
deliberately historical project log for future sessions and keeps its own dated,
decision-by-decision narrative style throughout.

**Whenever you edit a class, clean up its Javadoc as part of that edit** — remove stale historical
narrative you find along the way (even if you didn't write it), fix anything that no longer matches
the code (a Javadoc claim that's gone stale is worse than no comment at all), and tighten anything
left. Don't defer this to a separate pass; do it in the same edit that touches the class.

### Naming convention: `Tson` is a prefix, never an infix

If a class name contains `Tson` at all, `Tson` MUST be the leading word (`TsonSchema`, `TsonCompiledSchema`,
`TsonSchemaCompiler`, `TsonDataParser`, `TsonMapperReader`) — never buried in the middle (`CompiledTsonSchema`
is wrong; it was renamed to `TsonCompiledSchema` specifically to fix this, 2026-07-26). The prefix isn't
applied to every class in the library, either — most of `tson-parser`/`tson-schema`'s own internal machinery
is deliberately bare (`Lexer`, `RecordAbstractReader`, `DeferredValueReader`, `CanonicalIdentity`).
Reserve the `Tson` prefix for the classes a *consumer of this library* actually names in
their own code — its value is disambiguation at the call site (`TsonSchema` vs. a domain object also called
`Schema`, `TsonDataParser` vs. a domain-specific `DataParser`) and quick identification when skimming a
consumer's imports, not a house style to stamp on everything. When adding a new public, developer-facing type,
ask "would a consumer of this library plausibly also have their own class with this bare name?" — if yes, and
the type is meant to be used from outside this library's own internals, prefix it; if it's internal
machinery a consumer never names directly, leave it bare.

### Project-owned schema `!!id` convention

A schema this project authors itself (not one of the spec's own bundled `meta-kernel.tn`/`meta.tn`/
`core.tn` companion artifacts) gets a `!!id` of the shape
`https://tson.io/2026/32/ltr8/<group>/<name>-<version>.tn` — e.g. `tson-cli`'s own
`diagnostics.tn`: `https://tson.io/2026/32/ltr8/cli/diagnostics-1.tn`. Reading the path
left to right: `/2026/32` is the spec revision this schema is written against (the same version
segment the spec's own bundled schemas publish under); `ltr8` is the publishing org (this project's
own group id); `<group>` is a short name for the module/subsystem the schema belongs to (`cli` for
`tson-cli`'s own schemas); `<name>-<version>` is the schema's own name with a trailing integer
version — bump it, under a new name (`diagnostics-2.tn`, not an in-place edit), whenever the
schema's own shape changes, per §10's own "each published schema version is immutable, different
content hashes and different URLs" rule. A schema's own `@doc` annotation (see `diagnostics.tn`
itself) should point back to this section by name so the convention is discoverable from the schema
file alone, not just from here.

**`.tn`, not `.tn1`, for as long as the spec itself stays a pre-release, 2026-revision-series draft**
(both here and for the spec's own bundled `meta-kernel`/`meta`/`core` companion artifacts, per the
same reasoning) — `.tn1` is a positive stability claim §7.1 reserves for the eventual, frozen "TSON
version 1" release, which hasn't happened yet (see `SPEC-FEEDBACK.md` #20 for the full finding, and
why the spec's own pre-release material publishing under `.tn1` today is itself an open spec issue,
not settled precedent to follow). Renamed from `.tn1` to `.tn` throughout this project on 2026-07-29,
on the user's own explicit direction, once it was confirmed the live spec site could be updated to
match rather than being a fixed, unchangeable external identity.

`tson-parser` holds the lexer, the data-grammar structural parser, base type resolution, the built-in
type vocabulary, the Part 2 schema grammar (`TsonSchemaParser`, `ast.schema`), the schema resolver
(`io.ltr8.tson.parser.resolver.TsonSchemaResolver`, producing Class 2's resolved schema value), *and*
(moved here 2026-07-24, see "Mapper" below) the generic `DataValue`&lt;-&gt;Java-object binding layer
(`io.ltr8.tson.parser.mapper`) — every one of these is tightly coupled to the shared lexer/token-stream
machinery (the schema grammar reuses the data grammar's own `annotation`/`data-value`/directive-parsing
code directly, per Part 2 §12.1; the resolver consumes the grammar's own `SchemaMap`/`TypeDef` AST
directly; the mapper binds that same AST to Java objects), so splitting any of them into separate
Gradle modules was judged not worth the build-graph overhead, the same reasoning that already keeps
the lexer and structural parser together.

**`tson-schema` holds exactly one thing: `io.ltr8.tson.schema.meta`, the resolved-schema *value* model**
(§8's `TypeDefinition` et al.) — pure data (records, sealed interfaces, enums), no parsing, no
resolution logic, and (deliberately) no dependency on `tson-parser` at all. This is the reverse of the
dependency direction the module names might suggest: `tson-parser` depends on `tson-schema`, not the
other way around, precisely so that `tson-parser`'s own resolver (and, later, a schema-validating data
parser) can hold and consult `schema.meta` types directly. `schema.meta.Token` is the one place this
shows concretely: it structurally mirrors `tson-parser`'s own `TokenValue`/`TokenForm` (same field
names, same enum members) but is declared locally rather than imported, specifically so `schema.meta`
never needs to reference `tson-parser` at all; `TsonSchemaResolver` converts field-by-field at the one spot
that needs it (`resolveField`'s `toMetaToken`). `tson-bind`/`tson-annotation` are the separate
Java-object-binding layer (see their own package Javadoc; not detailed in this file yet) —
`tson-schema`'s own `schema.meta` classes depend on `tson-annotation` only (for `@Typename`/`@Field`),
never on `tson-bind`.

**There is no `tson-mapper` module anymore.** It originally held the `DataValue`&lt;-&gt;Java-object
mapper (`TsonMapper`, plus `AtomBinder`/`AtomWriter`/`TsonAnnotations`), depending on `tson-parser` +
`tson-schema` + `tson-bind`. Moved into `tson-parser` itself (2026-07-24) — split into
`TsonMapperReader`/`TsonMapperWriter` along the way (see "Mapper" below) — once `TsonSchemaResolver`'s own
generalized constructor-application/atom-refinement resolution needed exactly this generic binding
directly, which `tson-mapper`'s own dependency *on* `tson-parser` made impossible without a module
cycle. `tson-bind` itself has no dependency on `tson-parser`/`tson-schema` (a leaf module), so
`tson-parser` depending on it directly, in main scope, is clean — the `tson-mapper` module had nothing
left in it afterward and was deleted outright, not just deprecated.

Package: `io.ltr8.tson.parser.lexer` (group `io.ltr8`). The group is `io.ltr8`, not `io.tson`: reverse-DNS
package naming identifies who *publishes* the artifact (and is what Maven Central's domain-ownership
verification actually checks), not the subject matter. `tson.io` is the spec's home — anyone can
implement it — and this is one implementation of it, published under the `ltr8.io` banner, not a claim to
be *the* tson.io-blessed implementation.

### Lexer (`tson-parser/src/main/java/io/ltr8/tson/parser/lexer/`)

`Lexer` is a single hand-written scanner (`Lexer.java`) producing a stream of `Token`s. Key design
points, tied to specific spec sections:

- **Code-point addressed, not char-addressed.** The cursor advances by Unicode code point
  (`source.codePointAt`/`Character.charCount`) so supplementary-plane characters (valid in TSON
  identifiers per UAX #31) are never split across a surrogate pair. `Position.column` counts code
  points, not UTF-16 units.
- **Position tracks line, column, and a UTF-8 byte offset** (computed incrementally per code point),
  per the spec's §8.1 error-reporting requirement. This is currently the only place byte-offset
  awareness exists — nothing downstream consumes it yet.
- **`Character.isUnicodeIdentifierStart`/`isUnicodeIdentifierPart` stand in for the spec's XID_Start /
  XID_Continue** (UAX #31 properties, §7.1). The JDK doesn't expose XID_Start/XID_Continue directly, and
  building an exact Unicode property table from scratch is out of scope for a "no external libraries"
  lexer. This is a known, deliberate approximation — flag it if a lexing bug ever turns out to hinge on
  a script where Java's identifier notion and true XID_* diverge.
- **NFC normalization** (`java.text.Normalizer`, part of the JDK) is checked only for *unquoted* tokens,
  per §7.2.1 — quoted tokens are exempt and preserve their exact content.
- **Pattern_White_Space is hardcoded as the spec's fixed 11-character set**, not `Character.isWhitespace`
  (which doesn't match the spec's set exactly).
- **Escape decoding is unified** between single-line and multi-line tokens: both first extract a raw
  substring (single-line: scan to the unescaped closing `"`, skipping `\`+nextchar as a pair so an
  escaped quote can't terminate the token early; multi-line: split into raw lines by real line-terminator
  bytes, since backslash escapes never produce literal newline bytes so no such skip is needed there),
  then run the same `decodeAllEscapes`/`decodeEscapeSequence` pass over the extracted text. Surrogate-pair
  `\uXXXX` escapes are validated (lone high/low surrogate escapes are lexer errors, §7.2.2).
- **Multi-line common-prefix stripping** (§7.2.3) compares leading-whitespace prefixes *character by
  character* across all non-blank content lines plus the closing delimiter line — a tab never matches a
  space, blank lines are excluded from the comparison (but still have the resulting prefix stripped from
  them, best-effort). Closing-delimiter detection checks the line's content *after* removing its leading
  whitespace against `"""` (+ optional trailing spaces/tabs) — get this backwards (checking the leading
  whitespace substring itself, rather than what remains after it) and every multi-line token spuriously
  fails as "unterminated"; this exact bug happened once already during development, caught by the
  multi-line test group in `LexerTest`.
- **BOM handling** (§7.1): a single leading U+FEFF is stripped in the constructor; U+FEFF anywhere else
  falls through to "unrecognised character" naturally, with no special-casing needed. When writing tests
  or source that embeds BOM, NEL, LINE SEPARATOR, or PARAGRAPH SEPARATOR characters, use `\uXXXX` Java
  escapes rather than pasting the literal invisible character into source — the literal character is
  indistinguishable from a normal space/nothing when reading the file back, which is both an editing
  hazard and the exact confusable-character risk the spec itself warns about (§9.4).
- Errors are fail-fast (`LexException`, unchecked), not the spec's "SHOULD continue processing to report
  multiple issues" recommendation (§8.1) — error recovery/multi-error reporting is left for later.

The lexer is complete and frozen for the whole series per the spec itself (§1.3): "higher parts introduce
no new tokens, no new lexer modes, and no changes to character classification." Everything above it
changes; the lexer doesn't.

### Structural parser (`tson-parser/src/main/java/io/ltr8/tson/parser/`)

`TsonDataParser` (`TsonDataParser.java`) turns the lexer's token stream into a `Document` (§2, §3, §7.4). AST types live
in the `ast` subpackage as a sealed `CoreValue` hierarchy (`RecordValue`, `MapValue`, `ArrayValue`,
`EmptyBrace`, `AbsentValue`, `TokenValue`) built on Java 25 records, matching the grammar's own shape.
Key design points:

- **Whitespace is invisible by the time tokens reach the parser** — the lexer already discarded it,
  leaving only `Position` gaps as evidence it was there. Two consequences run through the whole class:
  (1) wherever the grammar shows `ws` between tokens, nothing special is needed — it's already permitted
  by default; (2) wherever the spec requires strict *adjacency* (`!`, `!!`, `@` to their operand, `:` to a
  preceding annotation/directive name, §7.5), the parser checks it explicitly via `Position` equality
  between one token's `end()` and the next's `start()`. The inverse comes up once too: a valueless
  annotation requires a whitespace *gap* to follow (§3.1) — checked as positions being *unequal*.
- **Separator detection (§2.4) works the same way.** Between record fields / map entries / array
  elements, "zero-width separation is a parse error" and "trailing separators are not permitted" are both
  implemented by comparing the end position of the previous element's last token against the start
  position of whatever comes next (`TsonDataParser.consumeSeparatorOrCloseCheck`) — a real comma token is
  optional evidence, a position gap is the other kind of evidence, and at least one of the two is
  required unless the closing delimiter is immediately next (which needs no separator at all,
  §2.4: structural delimiters create their own token boundary).
- **Layering is deliberately incomplete, on purpose, matching the spec's own division of labor (§1.2):**
  the parser does not deduplicate record fields or detect duplicate map keys ("last value wins" is a
  resolver-layer rule, §2.5/§2.6), does not NFC-normalize field names or reject `_` as a map key (both
  explicitly resolver-layer, §2.9/§7.2.1), does not resolve `EmptyBrace` to a record or typed container
  (explicitly deferred to the resolver, §2.8), and does not interpret `TokenValue` text as null/boolean/
  number/string (base type resolution, §4, not yet implemented). All of these are intentional gaps, not
  omissions — a resolver layer consuming `Document`/`DataValue` is the next natural piece of work.
- **`!!meta` in the header throws `TsonUnsupportedDocumentException`, not `TsonParseException`.** This is a Class 1
  (data-format-only) processor (§1.5); encountering a schema document isn't malformed input, it's a
  well-formed document of a kind this parser doesn't implement, and the spec requires that distinction be
  visible in how it's reported (§8.1: "MUST report the document as a TSON schema document that this
  processor does not support" — a categorized diagnostic, not a generic parse error).
- **Nested annotation value-scope is right-recursive and can legitimately leave an outer data-value
  without a core-value.** `@a:@b:val` fully consumes everything as `@a`'s nested value, all the way down
  — see `SPEC-FEEDBACK.md` #3 for the exact trace; `TsonDataParserTest.nestedAnnotationValueScopeAloneIsIncomplete`
  documents this as intentional, spec-derived behavior, not a bug, so don't "fix" it without re-reading
  that entry first.

### Base type resolution (`tson-parser/src/main/java/io/ltr8/tson/parser/base/`)

`BaseTypeResolver.resolve(TokenValue)` implements §4's fixed resolution order (null → boolean → number →
string, §4.5) for `TokenValue`s produced by the parser. `NumberGrammar.tryParse(String)` recognizes the
`number` production of §7.6 against a token's complete text.

- **Identification is deliberately separate from binding to a Java numeric type.** `NumberGrammar`
  determines which of the four grammar alternatives (special-value / based-integer / float / integer) a
  token matches and extracts the grammar's own structural components (sign, digit groups) as raw
  substrings into `NumberForm` — it does not convert into `long`/`double`/`BigInteger`/`BigDecimal`. The
  spec explicitly leaves that mapping open ("how values map to host-language numeric types is an
  implementation concern," §4.3) — different consumers legitimately want different host types (a fast
  `long`/`double` path vs. exact arbitrary-precision `BigInteger`/`BigDecimal`), and binding is where the
  spec's required equivalence between representations (`255`/`0xFF`, `.5`/`0.5`, `1_000`/`1000`) actually
  needs enforcing — none of that belongs in the recognizer. Binding is intentionally not built yet; it
  consumes `NumberForm`, not a replacement for it.
- **Each number-grammar alternative is its own small, anchored regex**, not one combined pattern — Java
  regex forbids a named capture group from repeating across alternation branches, and the three `float`
  alternatives and three `based-integer` radixes each want their own named groups for extraction. One
  pattern per ABNF alternative, tried in sequence, reads close to the grammar and sidesteps that
  restriction entirely.
- **Quoted tokens always resolve to `StringValue`, regardless of content** (§4.4) — `BaseTypeResolver`
  checks `TokenForm` first and only attempts null/boolean/number matching for `TokenForm.UNQUOTED`. The
  quoted string `"42"` and the unquoted token `42` must resolve differently even though `TokenValue.text()`
  is identical for both — form is consulted exactly once, here, per §2.4.
- **§9.1's numeric-literal length limit (SHOULD, default 4096 digits) is not enforced anywhere yet.** It's
  a DoS-hardening recommendation, not a grammar rule, and adding an unconfigurable limit now would be
  premature without a real configuration mechanism — noted here so it isn't mistaken for an oversight.
- `BaseTypeResolver` only implements the *default*, untyped resolution path — the built-in type
  vocabulary (§5 — `!uuid`, `!date`, `!int32`, etc.) is a separate implementation, `atom`
  (below), consulted only when a value actually carries a type-ref.

### Built-in type vocabulary (`tson-parser/src/main/java/io/ltr8/tson/parser/atom/`)

`AtomType<T>` is a built-in vocabulary atom's parsing contract (§5.2): `read(TokenValue)` (the
atom's own natural host value), `read(TokenValue, Class<?>)` (narrow directly to a caller-supplied
target, overridden by the numeric family to share `NumberNarrowing` with `io.ltr8.tson.parser.mapper` rather than
routing through an intermediate `Number`), and `write(T)` (the inverse). `BuiltinTypeVocabulary` is
the name → `AtomType` lookup table (§5's fixed, closed set — see its own Javadoc for which
`core.tn1`/`meta.tn1` instances it's seeded with, including known departures from §5's own
published table, e.g. the full `int8`..`int256` width ladder vs. the four §5.6 explicitly lists,
tracked in `SPEC-FEEDBACK.md`).

**Each constructor is split into two classes, one per module, not one flat class** (widened to all
implementations 2026-07-23, alongside the `tson-schema`/`tson-parser` dependency inversion below):
a pure constraint-*values* record in `io.ltr8.tson.schema.meta` (`IntegerType`, `TextType`,
`RegexType`, `DecimalType`, `FloatType`, `RationalType`, `UuidType`, `BinaryType`, `DateType`,
`TimeType`, `DateTimeType`, `DurationType`, `UriType` — no parsing/validation, matching the kernel's
own `*_type` constructor shape exactly, the same modeling `io.ltr8.tson.schema.meta` uses everywhere
else), and a same-named-but-suffixed `*TsonDataParser` class here in `atom` (`IntegerParser`,
`TextParser`, `RegexParser`, ...) holding one as `constraints` and doing the actual
`read`/`write`/validate work. `RegexType` (added 2026-07-23, after the initial split) is itself a
*composition*, not a flat record: `RegexType(TextType constraints, AtomSpecification specification)`
-- `regex_type` declares no field of its own beyond these two composed values (`text_type`'s
constraint vocabulary, held as a nested `TextType` rather than flattened field-by-field the way
`UriType` flattens it, plus `atom_specification`'s `spec`, fixed to RFC 9485, distinct from
`UriType`'s own RFC 3986 citation via the same mixin -- see `AtomSpecification`'s own Javadoc for
why `spec` is kept as real data rather than dropped as always-implied). `ComplexParser`/
`Ipv4Parser`/`Ipv6Parser` still have no separate `schema.meta` class at all — their constructors
declare no constraint fields of their own at all (`complex_type`/`ipv4_type`/`ipv6_type` have none
beyond a fixed component/RFC pin) — so there's nothing to split out. Each `*TsonDataParser` keeps
convenience constructors/static factories mirroring its pre-split
shape (e.g. `new IntegerParser(new IntegerSize(32, true))`, `IntegerParser.ofMin(...)`) so call
sites barely changed. `Rational`/`IsoDuration` (host *values*, not constraints, referenced by
`RationalType`/`DurationType`'s own bound fields) moved to `schema.meta` alongside them; `Complex`
stayed here (`complex_type` has no constraint fields referencing it). `IntegerSize` had a pre-
existing near-duplicate in each module (`vocab`'s used `int bits` for arithmetic; `schema.meta`'s
used `BigInteger bits` for kernel fidelity) — consolidated onto `schema.meta.IntegerSize` alone,
with an `int`-taking convenience constructor added so the width-ladder call sites keep their
literal `32`/`64`/etc. spelling; the `minValue`/`maxValue`/`hostType` *behavior* that used to live
on `vocab`'s copy moved into `IntegerParser` as private static helpers taking a `schema.meta.
IntegerSize`, since `schema.meta` stays pure data, no behavior.

**`RegexParser` returns `String`, not `java.util.regex.Pattern`, and `TextType.pattern`/
`UriType.pattern` are `Optional<String>`, not `Optional<Pattern>` (corrected 2026-07-23, on the
user's own observation).** `regex_type` composes with `text_type` (§5.7) -- a `regex` value IS-A
piece of text, so `AtomType<T>`'s "the atom's own natural host value" contract (above) means `T`
should be `String` here too, the same as every other text-composing atom; `RegexParser.read` still
compiles the text via `Pattern.compile` to validate it's well-formed, but discards the compiled
object rather than returning it. This also made `TextType`/`UriType`'s own `pattern` constraint
field a pure, equatable `String` value (matching every other field in those "pure constraint
values" records) instead of a compiled host object -- `TextParser`/`UriParser` compile it at
validation time instead of storing the compiled form. A useful side effect: this is what let
`text`/`uri`/`regex` (§5.5's `Instance` declarations, see "Meta-kernel bootstrap" below) actually
serialize via `TsonMapperWriter.toTson` at all -- before this change, `TextType.pattern`/`UriType.pattern`
being `Optional<Pattern>` made `MetaKernelBootstrapResolver`'s `text`/`uri`/`regex` entries throw
`DataBindException` (`tson-bind` has no built-in `Pattern` conversion), even with the field empty,
since record binding resolves every field's descriptor up front regardless of whether a value is
actually present. `tson-bind` separately gained `io.ltr8.bind.bridge.PatternStringBridge` (mirroring
`EnumStringBridge`'s shape, opt-in via `context.registerAtom(Pattern.class, new
PatternStringBridge())`) as a general capability for a caller who *does* want to keep `Pattern` as
their own field type -- not needed by this fix, but added and unit-tested (`PatternStringBridgeTest`)
regardless; see `tson-bind/README.md`'s "Under development" section for the full note.

**Why the split needed a dependency-direction flip.** `schema.meta` (§8's resolved-schema value
model, previously `tson-schema` depending on `tson-parser` for its own grammar-layer `SchemaMap`)
had to stop depending on `tson-parser` at all for a vocab class here to hold one of its records
without a module cycle. Two consequences: `TsonSchemaResolver`/`TsonSchema` (which *do* need
`tson-parser`'s grammar AST) moved into this module, at `resolver` (this package's own
sibling — see "Schema resolution" below); and `schema.meta.Token` was introduced as a local,
structurally-identical stand-in for `tson-parser.ast.TokenValue`/`TokenForm` (same `text`/`form`
fields, same three enum members) purely so `RecordField.value`/`TypeArgument.Value` (§8.1's literal-
value fields) don't need `tson-parser`'s own type — `TsonSchemaResolver` converts between the two at the
one spot that needs it (`resolveField`'s `toMetaToken`). `tson-schema`'s own module now holds
*only* `io.ltr8.tson.schema.meta` and depends on nothing but `tson-annotation` (for `@Typename`/
`@Field`); `tson-parser` depends on `tson-schema`, the reverse of before. This groundwork is for a
future schema-*validating* parser (Class 2): once one exists inside `tson-parser`, it can hold and
consult a resolved `TsonSchema`/`TypeDefinition` directly, the same way `atom` already
consults `schema.meta` constraint records — without `tson-schema` ever needing to import
`tson-parser` back.

**Now genuinely used, not just prepared for.** Part 2's own atom-refinement resolution (`!I ^ {
... }`, `TsonSchemaResolver.resolveAtomRefinement`, added 2026-07-24) binds a refinement's own values
straight onto one of these split-out classes (e.g. `int32 => !integer ^ { size: { bits: 32 signed:
true } } }` produces a real `IntegerType`) — see "Schema resolution" below. What was "purely an
internal reshaping of the existing Class 1 vocabulary, done in preparation for future use" is now
that future use.

**`unit`'s three real instances (`value`/`token`/`void`) are three separate parsers, not one shared
one** (split 2026-07-25, on the user's own observation; see `SPEC-FEEDBACK.md` #18 for the
underlying spec gap). `unit => ~atom & {}` has zero constraint fields, so every instance of it
resolves to the byte-for-byte identical `Unit` body — nothing in the *resolved schema* distinguishes
`value`/`token`/`void`; meta-kernel's own doc comment on `unit` says outright they're "distinguished
by name and prose-level parsing contract, not by schema shape." The pre-split single `UnitParser`
(renamed `TokenParser`, unchanged behavior: raw NFC-normalised token text, unconstrained — this is
`token`'s own real contract) was silently wrong for the other two: it accepted *any* token for
`void` (should accept only the absent sentinel `_`) and, worse, would have *rejected* `_` outright
had it ever reached one (`AtomValueReader`'s own adapter requires a `TokenValue` before ever calling
an `AtomType`, and `_` — `AbsentValue` — isn't one; this bug was latent, never actually exercised,
since nothing called compiled-parser reading against `void` before this session). Now: `ValueParser`
(`atom`) actually runs `BaseTypeResolver` and narrows to the natural host type (`null`/
`Boolean`/`BigInteger`/`BigDecimal`/`Double` for `.nan`/`.inf`/`String`) — `value`'s own doc: "the
result of base type resolution... applied to a source token." `VoidReader`
(`compiler`, not `atom` — its contract doesn't fit `AtomType.
read(TokenValue)` at all, since it needs to see the `DataValue`'s own `core-value` shape, not a
token) accepts only `AbsentValue` and reads to Java `null`. Dispatch is keyed on the *declaration's
own name* (`AtomValueReader.UNIT`'s factory switches on its `name` parameter, not on
`definition.body()` the way every other constant in that class does) — an unrecognized `unit`-
constructed name falls back to `TokenParser`'s behavior (the previous, pre-split default for
everything) rather than failing.

### Mapper (`tson-parser/src/main/java/io/ltr8/tson/parser/mapper/`)

Binds a parsed `DataValue` tree to a Java object given its `DataClass` descriptor from `tson-bind`,
and back — `TsonMapperReader`/`TsonMapperWriter`. Moved here from a separate `tson-mapper` module
(2026-07-24; see "Architecture" above for the module-cycle reasoning) once `TsonSchemaResolver`'s own
generalized constructor-application/atom-refinement resolution needed exactly this generic binding
directly, which the old module's own dependency *on* `tson-parser` made impossible to reach from
here without a cycle.

**Split into `TsonMapperReader`/`TsonMapperWriter`, not one `TsonMapper` class**, for readability —
the original already internally paired one `to*` method with one `write*` method per `DataClass`
kind (`toAtom`/`writeAtom`, `toRecord`/`writeRecord`, `toArray`/`writeArray`, `toMap`/`writeMap`,
`toTuple`/`writeTuple`, `toUnion`/`writeUnion`), so the split follows an already-present internal
seam rather than inventing a new one. `AtomBinder`/`AtomWriter` (the read/write pair `toAtom`/
`writeAtom` delegate to for values never bound through the built-in vocabulary at all) already had
this shape from the start. Both new classes' own no-arg constructors share one `DataBindContext`
factory (`TsonMapperContext.defaultContext()`) rather than duplicating the built-in-vocabulary atom
registration list (`UUID`/`byte[]`/`LocalDate`/`OffsetTime`/`OffsetDateTime`/`URI`/`Inet4Address`/
`Inet6Address`) across two classes that could drift apart.

`TsonMapperReader.toObject(DataValue, Class)`/`toObject(String, Class)` bind a parsed value onto a
target class via `tson-bind`'s `DataClass` descriptor; `TsonMapperWriter.toTson(Object)` is the
reverse, mainly useful as a debugging tool rather than a guaranteed-lossless serializer (the integer
family's exact width, a tuple's tuple-ness, and `@Annotated`-captured wire-format annotations are
all documented, deliberate write-side losses — see `toTson`'s own Javadoc). Atom binding checks for
a type-ref first (`BuiltinTypeVocabulary`, §5) before falling through to plain `BaseTypeResolver`
identification + `AtomBinder` binding for an untyped value — both paths share the same final
narrowing step (`NumberNarrowing`, in `resolver`) so a plain `42` and a `!uint8 42` bind identically
regardless of which path found them.

**No positional-form support** (§5.6: a record with exactly one `REQUIRED` field can be filled by a
bare, non-braced value at any schema-backed data position) — `toRecord` only accepts a `RecordValue`
or `EmptyBrace`, never a bare token/array. This is why `MetaKernelBootstrapResolver`'s own `!enum [...]`
handling (see "Meta-kernel bootstrap" below) stays hand-written rather than routing through
`TsonMapperReader` generically — a real, currently-unclosed gap for any future caller (e.g.
`TsonSchemaResolver`'s own generalized constructor-application resolution) that needs to bind a
positional-form value generically; wrapping the bare value into an equivalent one-field
`RecordValue` before delegating to ordinary record binding is the natural fix, not yet built.

### Schema grammar (`tson-parser/src/main/java/io/ltr8/tson/parser/TsonSchemaParser.java`,
`.../ast/schema/`)

`TsonSchemaParser` parses a schema document's body (Part 2 §2.1, §5, ABNF at §12.1) into a
`SchemaDocument`, the schema-grammar analogue of `Document`/`CoreValue`. AST types live in the
`ast.schema` subpackage (`TypeDef`, `TypeRef`, `RecordDef`, `ContainerDef`, etc.) alongside
`tson-parser.ast`'s data-grammar types, one sealed hierarchy per ABNF production family, mirroring
`ast`'s own shape.

- **Grammar-only, deliberately.** `TsonSchemaParser` builds a faithful AST from a schema document's source
  text and does nothing else itself — no namespace resolution (§3), no `type_definition`
  materialisation or desugaring (§8), no validation; see "Schema resolution" below for the module
  (`tson-schema`) that consumes this AST and does that work, kept out of `tson-parser` on purpose since
  it has real independent conformance meaning (Class 2 proper) the grammar layer itself doesn't.
- **`SchemaMap.declarations` is a `Map<String, Declaration>`, not a `List`** — keyed by name, insertion
  order preserved (a `LinkedHashMap`), exactly the shape §3.4.1's Pass 1 needs ("populated with skeleton
  `type_definition` records keyed by name") and the schema's own target type, `map<type_name,
  type_definition>`. A duplicate declaration name isn't rejected here (the later one overwrites the
  earlier map entry) — the same "grammar layer doesn't dedupe, resolver does" treatment [TSON-DATA]
  §2.5/§2.6 already give ordinary duplicate record fields and map keys.
- **`TsonSchemaParser extends TsonDataParser`, same package.** Part 2 §12.1 says the schema grammar imports
  `annotation`, `data-value`, and directive parsing directly from Part 1 §7.4 — the same tokens, the same
  adjacency/separator rules, the same `!!name:"..."` directive shape for `!!id`/`!!meta`/`!!import` as
  data documents use for `!!id`/`!!schema`. Rather than re-implementing that grammar a second time,
  `TsonDataParser`'s relevant fields and helper methods are package-private, not `private` (see its own Javadoc
  on why it isn't `final`), and `TsonSchemaParser` calls straight into them — `parseDataValue()` for
  atom-refinement values, `parseCoreValue()` for constructor-application (`instance`) values (see the
  next bullet for why these two aren't the same production), `parseAnnotation()`,
  `parseNamedDirective()` for all three header directives, `expectFieldNameToken()`, and the
  cursor/separator primitives. `TsonDataParser` itself is untouched in behavior — it still rejects `!!meta`
  documents exactly as before; only its own private-vs-package visibility changed, and only because
  `TsonSchemaParser` needed it, not because either class became part of a different module.
- **`instance`'s ABNF says `data-value`, but the intended production is the narrower `core-value`** —
  the literal grammar (`instance = "!" type-name ws data-value`) lets a constructor-application payload
  carry its own further annotations and a second, competing type-ref (`data-value = *annotation
  [type-ref] core-value`), which no real fixture ever does and which §5.5's own prose never implies;
  `refined-def` (the sibling `^`-operator production one level up, for record/map/array refinement)
  already uses the narrower `record-def` for the identical shape, corroborating this is a slip, not
  intentional. See `SPEC-FEEDBACK.md` #16. `ast.schema.Instance` was reshaped accordingly — no
  separate `target: String` field alongside a full `value: DataValue` (redundant: `DataValue` already
  has an `Optional<String> typeRef` that can carry the constructor name directly); `Instance(DataValue
  value)` wraps a `DataValue` built from the parsed `core-value` with `typeRef` pre-set and
  `annotations` always empty, and `target()` is a thin accessor over `value.typeRef()`. This is also
  exactly the shape `TsonSchemaResolver`'s generalized constructor-application resolution needs — `value`
  can go straight to `TsonMapperReader.toObject(value, Atom.class)` with no separate step to attach a
  type-ref. `atom-refinement` has the identical defect (its own ABNF also says `data-value`, and its
  own prose says the payload "MUST be a braced record") but is deliberately left unfixed for now — see
  `SPEC-FEEDBACK.md` #16's own note on why, and revisit alongside `TsonSchemaResolver`'s not-yet-built
  atom-refinement resolution.
- **`construction-def`'s ABNF doesn't parse its own worked example** (`address & contact & { ... }` needs
  an implicit `&` before the trailing `record-def` that alternative 1 as literally written doesn't admit)
  — implemented per the documented intent, not the letter; see `SPEC-FEEDBACK.md` #14 and
  `ConstructionDef`'s own Javadoc before touching `TsonSchemaParser.parseConstructionDefContinuation`.
- **`field-modifier`'s value is a bare token or the absent sentinel, not a full `data-value`** — §12.1's
  own introductory prose claims otherwise, but its ABNF and §5.2's prose agree on the narrower rule; see
  `SPEC-FEEDBACK.md` #15. `FieldDef.Modifier.Value` models exactly `token | absent`, reusing
  `tson-parser.ast`'s `TokenValue` rather than the full `DataValue`.
- **An unquoted, non-numeric type-argument always parses as a type reference, never a value literal** —
  `TsonSchemaParser.parseTypeArg`'s Javadoc explains why this is the grammar's own deliberate deferral (§12.1,
  §5.10: "settled against the applied signature's parameter kinds... not by the grammar"), not an
  implementation gap; classifying an enum-member-shaped argument as a value happens at a later, semantic
  layer not built yet.
- **Verified against the real fixtures, not just the spec's own short examples.** `TsonSchemaParserTest`
  parses `spec/m/meta-kernel.tn1`, `spec/m/meta.tn1`, and `spec/m/core.tn1` (read directly from this
  repo's own `spec/` directory, not the sibling test-suite repo) end-to-end with no exceptions — real,
  full-sized schema documents, not just the spec's illustrative snippets.

### Schema resolution (`tson-parser/src/main/java/io/ltr8/tson/parser/resolver/`)

`DefinitionResolver` turns the grammar-layer `SchemaMap` (same module, `io.ltr8.tson.parser.ast.schema`)
into resolved `TypeDefinition`s (Part 2 §4, §8) -- values from `tson-schema`'s `io.ltr8.tson.schema.meta`
(see "Architecture" above for why the resolver itself lives in `tson-parser`, not `tson-schema`, despite
producing `tson-schema` values). Started 2026-07-23, deliberately narrow, incrementally widened the same
day to a second construct:

**Split into `TsonSchemaResolver` (document-level, public) and `DefinitionResolver` (declaration-level,
internal) on 2026-07-27, on the user's own explicit direction: "remove the SchemaCoordinator out of
TsonSchemaResolver all together... rename TsonSchemaResolver to DefinitionResolver... create a new
TsonSchemaResolver which requires a SchemaCoordinator."** Two prior narrowing passes the same day
already cut the old, unified class's public surface from seven members to two (`resolveAll` renamed
`resolveSchema`; the two-argument declaration-level overload renamed `resolveBootstrapDefinition` to
name its own real caller, `MetaKernelBootstrapResolver`'s two-pass loop) -- but tracing exactly *why*
`SchemaCoordinator` was even a field on this class surfaced the real problem: it was consulted by
only two methods, `compiledMetaSchema` and `mergeImports`, and both were only ever reached from the
class's own `resolveSchema(SchemaDocument)`. That's a concrete sign "resolve one declaration" and
"resolve a whole document, given a way to fetch its governing meta-schema and imports" were two
different jobs sharing one file, not one job with an optional dependency.

- **`TsonSchemaResolver`** (public) now holds the `TsonCompiledSchemaLoader` -- **required** in its
  constructor, not optional (the old no-arg constructor, and the "loader may be `null`" branch
  every loader-touching method used to carry, are both gone: a document-level resolution that
  can't validate its own `!!id` or reach its own `!!meta` isn't a degraded version of this job, it's
  a different one). `resolveSchema(SchemaDocument)` -- header-directive validation (`!!id`/`!!import`),
  deriving the structure namespace via `compiledMetaSchema`, merging `!!import` entries via
  `mergeImports` -- and the two-argument `compiledMetaSchema`/package-private `TsonCompiledSchemaLoader`-
  consuming machinery are the only things left on it.
- **`DefinitionResolver`** (package-private now, no `Tson` prefix -- internal machinery a consumer of
  this library never names directly, per "Naming convention" above) never references
  `TsonCompiledSchemaLoader` at all. Holds `resolve(SchemaMap.Declaration)`/`resolveBootstrapDefinition
  (SchemaMap.Declaration, Map)`/`resolve(SchemaMap.Declaration, Map, Map)` (the declaration-level
  primitives) plus a batch `resolveSchema(SchemaDocument, Map)` convenience -- looping every
  declaration in a document with no validation and no import-merging, kept *here* rather than on
  `TsonSchemaResolver` specifically because it needs no loader: tying it to the loader-
  *requiring* class would force a caller who already has a structure namespace in hand, but no real
  loader, to fabricate one just to call it. `resolveBootstrapDefinition`'s own name still
  describes its real production caller (`MetaKernelBootstrapResolver`'s two-pass loop, one
  declaration at a time, in source order -- meta-kernel's own `!!meta` names itself, so it can never
  go through `resolveSchema` the ordinary way); test code resolving one hand-built declaration in
  isolation calls it too, for the identical underlying reason (no structure namespace
  available/wanted), not because those tests are themselves "about" bootstrapping.

**`compiledMetaSchema` made `private` and moved to the bottom of the class (same-day follow-up, on
the user's own explicit direction, once both `DefinitionResolver` and `TsonSchemaResolver` had each
settled on exactly one public method -- `resolve(SchemaMap.Declaration)` and
`resolveSchema(SchemaDocument)` respectively).** It was already a pure pass-through
(`return loader.load(document.meta())`), package-private only because nothing had tightened it
further; nothing outside `TsonSchemaResolver` ever legitimately needs to call it directly, since a
caller with its own `TsonCompiledSchemaLoader` in hand can just call `loader.load(uri)` itself.
`TsonSchemaResolverCompiledMetaSchemaTest`'s own tests that used to call `resolver.compiledMetaSchema(document)`
directly now hold onto the `loader` they built the resolver from and call `loader.load(document.meta())`
instead -- the identical operation `compiledMetaSchema` performs internally, since it was never
anything more than that one line.

**`compiledMetaSchema` removed outright (a further same-day follow-up, on the user's own explicit
direction: "I just deleted compiledMetaSchema because I realise it wasn't used by TsonSchemaResolver").**
Once it was `private` and a pure one-line pass-through, keeping it as a named method bought nothing
over inlining -- `resolveSchema`'s own `TsonCompiledSchema metaParser = compiledMetaSchema(document);`
became `TsonCompiledSchema metaParser = loader.load(document.meta());` directly. The method's own
Javadoc (the "same-module, cross-package reach from `resolver` up into `compiler`"
note, explaining *why* this class needs the full `TsonCompiledSchema` rather than the narrower
`DefinitionMetaReader` `DefinitionResolver` gets by with) was dropped along with it -- that reasoning
is still true of `resolveSchema` as a whole, just no longer anchored to a now-nonexistent method name.

**`TsonCompiledSchema` removed from every `DefinitionResolver` method parameter (same day, a
follow-up pass, on the user's own explicit direction: "remove TsonCompiledSchema metaParser from
DefinitionResolver... create an interface DefinitionMetaReader with one method read(String type,
DataValue value)... a required constructor parameter").** Splits what `TsonCompiledSchema` used to
provide into the two genuinely different things `DefinitionResolver` actually needed from it:

- A plain `Map<String, TypeDefinition>` for the structure namespace (what `resolveConstructorTarget`
  needs -- a `TypeDefinition` *lookup*, never a value read) -- at this point still threaded as an
  ordinary per-call parameter everywhere `metaParser` used to be (e.g. `resolve(SchemaMap.Declaration,
  Map, Map)`); moved to the constructor too in a same-day follow-up, see below.
- **`DefinitionMetaReader`** (new, one method: `Top read(String type, DataValue value)`) -- what
  `bindAtomInstance` uses to actually *bind* a constructor-application/atom-refinement value, now a
  **required constructor parameter** rather than threaded per call. `DefinitionResolver` has zero
  dependency on `compiler` now -- the one place this class used to reach into that
  package is gone; `TsonSchemaResolver` (which still needs the full `TsonCompiledSchema`, to derive
  *both* the structure namespace and the reader) is where that reach now lives instead, one layer up
  (see `compiledMetaSchema`'s own Javadoc).

**The structure namespace moved to the constructor too (same day, a further follow-up, on the
user's own explicit direction: "The Map<String, TypeDefinition> structureNamespace is fixed to the
meta definitions so should also be passed in on the constructor. Required, but empty map if not
being tested. Then remove anywhere structureNamespace is passed into the methods.").** Both of a
`DefinitionResolver`'s two real dependencies -- which compiled reader to bind an `Instance`/
`AtomRefinement` value against, and which structure namespace to fall back to for a
constructor-application target -- are properties of *which meta-schema governs this resolver*,
fixed for its whole lifetime, not something that legitimately varies call to call the way `resolved`
(the accumulating type-name namespace, still a per-call argument) genuinely does; once
`DefinitionMetaReader` had already made that move, leaving `structureNamespace` threaded separately
was the odd one out. `resolve(SchemaMap.Declaration, Map, Map)` collapsed to `resolve(SchemaMap.Declaration,
Map)`; `resolveSchema(SchemaDocument, Map)` collapsed to `resolveSchema(SchemaDocument)`;
`resolveConstructorTarget` (now a genuine instance method, not `static`) reads the field directly.
`resolveBootstrapDefinition` keeps its own distinct name (still describing its real production
caller, `MetaKernelBootstrapResolver`'s two-pass loop) even though its body is now a one-line
delegate to the two-argument `resolve` -- the two methods are behaviorally identical now that there's
no separate structure-namespace argument left to omit, but the name still documents *who calls it
and why*, matching this codebase's established preference for self-documenting call sites over
generic ones. A resolver with nothing to offer there (every caller that never reaches
`resolveInstance`, e.g. `MetaKernelBootstrapResolver`'s own first pass, or a test exercising an
unrelated construct) is constructed with `Map.of()`, the same way it already supplies a
throws-if-called `DefinitionMetaReader`. `TsonSchemaResolver.resolveSchema` passes
`metaParser.schema().entries()` as the second constructor argument alongside the reader lambda, both
now sourced from that call's own `metaParser`.

Since a single `TsonSchemaResolver` can resolve documents governed by *different* meta-schemas
across separate `resolveSchema` calls, it builds a fresh, cheap `DefinitionResolver` per call
(`new DefinitionResolver((type, value) -> (Top) metaParser.get(type).read(value),
metaParser.schema().entries())`) rather than holding one as a reused field -- both the reader and the
structure namespace have to be bound to *that call's* own `metaParser`, not fixed for the object's
whole lifetime. `MetaKernelBootstrapResolver` (which never actually reaches `bindAtomInstance` at all
-- every real `Instance` in meta-kernel's own document goes through `instanceBody` directly, see
"Meta-kernel bootstrap" below) supplies a reader that throws if ever invoked, rather than passing
something that could silently return nonsense if that assumption is ever wrong, and `Map.of()` for
the structure namespace it likewise never consults.

**`resolveConstructorTarget` narrowed to consult the structure namespace only, dropping the
type-name-namespace branch §3.3.1's own two-namespace lookup rule used to require (a fourth,
same-day follow-up, on the user's own direct edit and explicit reasoning: "This would suggest that a
schema could instantiate its own type defined by itself").** A constructor-application target
(`!C value`) is meta-schema vocabulary -- a `type_definition` with `constructor: true` (`integer_type`,
`enum`, ...) -- never something a schema legitimately declares about itself and, in the same pass,
constructs an instance of; a target found in the local, still-being-resolved type-name namespace
would mean exactly that. Every real fixture case already reached its constructor through the
structure namespace alone (the target is always declared in the *governing* meta-schema, one hop via
`!!meta`) -- confirmed by the full suite staying green with the local-lookup branch removed outright,
not just reasoned about. `resolveConstructorTarget`'s own error message narrowed to match (no longer
claims to have tried "either" namespace).

**The accumulating type-name namespace (`Map<String, TypeDefinition> resolved`, threaded as a bare
parameter through `resolveComposition`/`resolveRefinement`/`resolveAtomRefinement`/`resolve` itself)
moved to the constructor too (a fifth, same-day follow-up, on the user's own explicit direction,
immediately after the `resolveConstructorTarget` narrowing above: "Instead of passing the map in and
passing it through, let's follow the same as previously. Create a DefinitionGetter interface with
just getTypeDefinition(name) on it. Once again, make it a required constructor parameter.").** New
package-private functional interface, **`DefinitionGetter`** (one method: `TypeDefinition
getTypeDefinition(String name)`) -- every bare `Map<String, TypeDefinition> resolved` parameter is
gone from every method on `DefinitionResolver`; `resolveComposition`/`resolveRefinement`/
`resolveAtomRefinement` all read `this.definitionGetter` directly. `resolve(SchemaMap.Declaration,
Map)` collapsed to `resolve(SchemaMap.Declaration)`; `resolveBootstrapDefinition` similarly lost its
own `Map` parameter, keeping its established thin-delegate shape (see its own Javadoc for why the
name survives despite the two methods now being fully identical).

**Genuinely unlike `structureNamespace`/`definitionMetaReader`, a `DefinitionGetter` is NOT a
snapshot -- it's typically a method reference straight onto a caller's own growing map** (`entries::get`,
`namespace::get`), so a caller resolving declarations one at a time in a loop (every real caller:
`MetaKernelBootstrapResolver`'s own two-pass loop, `TsonSchemaResolver#resolveSchema`'s own
per-declaration loop) still `put`s each result into that same map itself, immediately visible to the
next `resolve` call through the lookup with no map ever threaded through `DefinitionResolver`'s own
API. `TsonSchemaResolver#resolveSchema` now builds its own local `namespace` map *before* constructing
that call's `DefinitionResolver` (reordered from the previous pass, where `metaParser` alone was
enough to construct it up front) specifically so `namespace::get` has something to close over.
**`DefinitionResolver.resolveSchema(SchemaDocument)`, the batch convenience added when `structureNamespace`
first moved to the constructor, was removed outright in this same pass** -- it needed to own a *fresh*
map per call to build its own `entries()`/return value, which is structurally impossible once a
resolver's own namespace lookup is fixed at construction; its two real callers (both in
`DefinitionResolverTest`) now run the equivalent loop directly, the same shape
`TsonSchemaResolver#resolveSchema`'s own production loop already used.

**`DefinitionResolverTest` gained a shared, per-test-instance `resolved` field (paired with `resolver`,
both now constructed together) rather than every test method declaring its own local `Map<String,
TypeDefinition> resolved`** -- safe specifically because JUnit 5's default `PER_METHOD` lifecycle
hands every `@Test` method a fresh instance of the test class, so the field starts empty at the top
of every test exactly like the local variable it replaces used to; roughly twenty test methods had
their own local `resolved` declaration deleted and their `resolve`/`resolveBootstrapDefinition` calls
dropped a now-redundant final argument. Tests that genuinely need a *different* starting namespace
than the shared field (a `metaKernelEntries`-seeded namespace for the chained-refinement test, an
always-`null` `EMPTY_NAMESPACE` constant for constructor-application/atom-refinement tests with
nothing resolved yet, a local `metaKernelBackedResolver` for the two atom-refinement rejection tests)
construct their own locally-scoped `DefinitionResolver` instead, the same way `definitionResolverFor`
(now taking a second `DefinitionGetter` parameter, since different callers need different namespaces
bound to the very same compiled meta-parser) already did for `Instance`/`AtomRefinement` binding.

**`structureNamespace` itself became a `DefinitionGetter` too (a sixth, same-day follow-up, on the
user's own explicit direction, immediately after independently renaming the earlier `definitionGetter`
field to `namespaceDefinitions`: "I just renamed definitionGetter to namespaceDefinitions in
DefinitionResolver. Now we've got the DefinitionGetter we should also use it to replace
structureNamespace. Replace it with DefinitionGetter metaDefinitions.").** The last bare `Map<String,
TypeDefinition>` field on `DefinitionResolver` is gone -- `resolveConstructorTarget` only ever needed
a single-name lookup in the first place (see its own Javadoc, and "Narrowed from §3.3.1's own
two-namespace lookup rule" above), the identical shape `DefinitionGetter` already existed to cover.
Two distinct `DefinitionGetter` fields now sit side by side on every instance -- `metaDefinitions`
(the structure namespace, consulted only by `resolveConstructorTarget`) and `namespaceDefinitions`
(the type-name namespace, the user's own rename once a second `DefinitionGetter` field existed and
the original bare name stopped being specific enough) -- genuinely different namespaces sharing one
lookup shape, not two names for the same thing (see `DefinitionGetter`'s own Javadoc for the full
namespace-by-namespace breakdown). Every caller that used to pass a fixed `Map<String, TypeDefinition>`
(always an already-fully-resolved compiled schema's own `entries()` -- unlike the type-name namespace,
the structure namespace is never still-growing when a resolver is constructed, since a governing
meta-schema is always compiled in full first) now passes `entries()::get` instead -- `TsonSchemaResolver`'s
own `metaParser.schema().entries()::get`, `DefinitionResolverTest`'s own `definitionResolverFor`
likewise, `MetaKernelBootstrapResolver`'s own `Map.of()` becoming a new shared `EMPTY_META_DEFINITIONS`
constant (`name -> null`, mirroring `NEVER_CALLED`'s own always-throws shape but for a lookup instead
of a reader -- meta-kernel governs itself, so its own bootstrap resolver never has a real structure
namespace to offer).

Verified behavior-preserving, not just compiling, at every step of all six passes: every real caller
(production and test) was traced before deciding what moved where, a clean compile succeeded across
every module on the first attempt for each step, and the test count stayed at the established
1131/0/0/0 bar throughout -- including the second pass, despite it touching roughly 30 call sites
across `DefinitionResolverTest` alone (most needed a locally-scoped `DefinitionResolver`, wrapping
that one test's own compiled meta-schema, in place of the shared no-op field the majority of other
tests still use), the third pass, which dropped the now-redundant third argument from roughly 20
more `.resolve(...)` call sites in the same file (several also lost a now-unused local
`Map<String, TypeDefinition> metaNamespace`/`metaKernelNamespace` variable, since that value moved
into `definitionResolverFor`'s own constructor call instead), the fifth pass, which touched
essentially every test method in the file for the `resolved`-field migration, and the sixth pass,
a small, targeted set of call sites (every `new DefinitionResolver(...)` construction, main and
test) once `structureNamespace`'s own type changed underneath them.

**`resolveBootstrapDefinition` removed outright (a seventh, same-day pass, on the user's own explicit
direction: "Now that is all cleaned up the resolveBootstrapDefinition method can be removed as
resolve does the same thing.").** Once `resolveBootstrapDefinition` became a pure one-line delegate
to `resolve(SchemaMap.Declaration)` (the previous pass's own end state), keeping both names bought
nothing further -- `resolve` is now the sole entry point every real caller (`MetaKernelBootstrapResolver`'s
own two-pass loop, every isolated-declaration test) uses directly. Every reference to
`resolveBootstrapDefinition` above, in this file's own narrative of how the class got here, is
historical -- the method itself no longer exists.

**Same-day, separately: a source-level Javadoc cleanup pass across every file this whole `DefinitionResolver`/
`DefinitionGetter`/`DefinitionMetaReader` refactor touched** (`DefinitionResolver`, `DefinitionGetter`,
`DefinitionMetaReader`, `TsonSchemaResolver`, `MetaKernelBootstrapResolver`, `DefinitionResolverTest`,
`PositionalFormTest`), on the user's own explicit direction, establishing a new, permanent convention
for this codebase — see "Javadoc conventions" under "Architecture" above. Every dated/"renamed from"/
"earlier version of this method" passage this whole refactor had accumulated in actual `.java` source
Javadoc was rewritten to describe only the code's current form; two genuinely stale factual claims
surfaced and got fixed in the process, not just reworded — `resolveInstance`'s own Javadoc still said
binding went through `TsonMapperReader.toObject(normalized, Top.class)` (it goes through
`bindAtomInstance`/`DefinitionMetaReader` now, since the 2026-07-26 compiled-reader swap), and
`MetaKernelBootstrapResolver`'s own class Javadoc still gave "`DefinitionResolver.resolveInstance`'s
own generic path binds via `TsonMapperReader`, identification-first" as a reason `instanceBody` hand-picks
meta-kernel's own instances -- also no longer true post-swap, leaving only the still-valid reason
(a compiled reader can't safely bootstrap meta-kernel from its own in-progress state). This file
(`CLAUDE.md`) is explicitly exempted from the new convention and keeps its own dated narrative style,
per that same "Javadoc conventions" note.

- **Record construction** -- a record (no supertypes, no type parameters) whose fields are simple
  type-refs, each REQUIRED or OPTIONAL (a `?` suffix; field *modifiers* -- default/fixed values --
  still aren't resolved) -- `integer_size`'s own shape, and, via a composition body (below),
  `integer_type`'s.
- **Composition** (`A & B & { ... }`, §5.8) -- copies each already-resolved supertype's own fields
  and groups into the result, left to right, checked for name overlap across supertypes; a
  trailing-body entry with no name collision is appended as new (a collision -- tightening an
  inherited field or group member, §5.7 -- isn't supported yet, reported explicitly rather than
  mishandled). `type_definition.supertypes` (the transitive IS-A chain) falls out by induction:
  each already-resolved supertype's own `supertypes()` is already *its* full transitive chain, so
  `direct-supertype + that supertype's own supertypes()`, deduplicated, is the new chain -- no
  separate graph walk needed. **Kind determination** (§4.1) checks the transitive chain for the
  kernel's three literal, fixed base-kind names (`atom`/`product`/`sum`, `top` never counts) --
  deliberately *not* "inherit the nearest ancestor's own resolved kind", which would be wrong:
  `atom` the entry is itself `kind: PRODUCT` (its own chain is just `[top]`, containing none of the
  three), so composing with `atom` correctly yields `ATOM` only via the literal-name check, not by
  copying atom's own (PRODUCT) kind. **`constructor`** is threaded straight from the source's own
  `~` marker (`StructuralTypeDef.constructor()`) into the result either way, fresh record or
  composition. **Field groups** (§5.11) flatten: each member becomes an ordinary field, state
  OPTIONAL regardless of the group's own state (a REQUIRED group still only guarantees *at most
  one* member, not which), and the group itself is recorded separately (state REQUIRED/OPTIONAL
  from the group's own `?`) -- modeled with `ElementState` (the two-member enum), not `FieldState`
  (five members), matching a modeling bug fix along the way (`FieldGroup.state` was wrongly typed
  `FieldState` before this). Verified by resolving `top`/`atom`/`product`/`sum`/`reference` (plain
  composition) and `integer_type` (`~`-marked composition with two OPTIONAL fields and two field
  groups) straight from the real `meta-kernel.tn1` fixture, in file order (composition only sees
  supertypes the caller has already resolved and handed back in -- real forward references and
  namespace population, §3.3.2/§3.4.1's Pass 1, are later work, not attempted yet). `subtypes` (the
  reverse index) is never populated -- it needs a whole-schema pass, not a per-declaration one.
  **Type parameters** (`<T, ...>`, §5.10) thread straight from `StructuralTypeDef.typeParams()` into
  `TypeDefinition.parameters` for both a fresh record and a composition -- no substitution into
  field types and no validation that a parameter is actually used anywhere in the body. `array`'s
  own `<T> ~product & {...}` shape resolves its `[T]` parameter fine but still throws overall, on a
  separate, still-unresolved gap: its body re-declares `access_pattern` (already inherited from
  `product`) with a fixed value, i.e. tightening (§5.7), not type parameters or field modifiers in
  isolation. A reference declaration's own type parameters (`text_keyed_map => <V> map<text, V>`, an
  open template application) are a different, not-yet-resolved case.
- **Bare type references** (`name => other_name`, §8.3) -- always resolve to a `REFERENCE`-kind
  entry regardless of what the referenced name itself resolves to (`type_name => token` is `kind:
  REFERENCE` even though `token` itself is `kind: ATOM`) -- no namespace lookup here either, the
  referenced name is carried through as a bare, unverified string, same as an ordinary field's
  type-ref. Verified against `type_name`/`field_name`/`param_name`/`annotation`/`documentation`/
  `doc`/`alias` from the real fixture -- `@annotation` on `annotation`'s own declaration is metadata
  on the type-def (`SchemaMap.Declaration.typeDefAnnotations`), not part of what this resolves, so
  it plays no role.
- **A field's inline array sugar `[T]`** (§5.3) resolves in place to the `type_ref` value `{ name:
  array  arguments: [ { name: T } ] } }` -- verified against `type_ref => { name: type_name
  arguments: [type_argument]? }` from the real fixture. The `@alias:field_name`-style annotation
  §8.3 would add when `T` is itself an aliased reference isn't produced yet, so the bare form is
  used instead. **A field's type-ref may also be an ordinary generic application** (`enum`'s own
  `members: set<token>`), resolved the same way a refinement source's arguments are -- only a
  simple (non-nested, non-value) argument is supported so far. **Declaration-level sized-array sugar**
  (`[T; N..]`/`[T; ..M]`/`[T; N..M]`/`[T; N]`,
  §5.3, §5.10) desugars to a `REFERENCE`-kind entry targeting `array_min`/`array_max`/
  `array_ranged` respectively (the bare-`N` form to `array_ranged<T, N, N>`, "two spellings of the
  same application") -- per §5.10/§8.2 `body.target` should point at a *materialised instantiation
  entry*, which this resolver doesn't create yet, so it reuses the application itself as a
  placeholder (see `TypeDefinition.reference(TypeRef)`'s own Javadoc). A size-less declaration-level
  array (`id_list => [text]`) is a top-level *constructor* application instead (§5.6), a different,
  not-yet-resolved case, rejected explicitly rather than mishandled. No real `meta-kernel.tn1`
  declaration uses this sugar; verified against §5.3's own worked examples
  (`score_list`/`order_batch`/`matrix9`) and §5.10's `string_triple` example directly.
- **A declaration's own fully-bound top-level application of the `map` constructor** (§5.6) --
  `schema => map<type_name, type_definition>`'s own shape -- resolves as a *construction*, not a
  reference: `kind: PRODUCT` (map's family), `source` the applied form (`{ name: map  arguments: [
  { name: type_name } { name: type_definition } ] }`), `body: !map { key_type: ...  value_type: ...
  }`, no supertypes (a constructor application transfers kind only, §5.5) -- unlike a
  non-constructor *template* application (`array_min<T, N>`), which resolves to `REFERENCE` instead
  (see above). Only `map` with exactly two simple type arguments is resolved so far; other
  constructors (`record`/`array`/`set`/`tuple`/`enum`/`choice`) and nested/value arguments aren't
  attempted yet, and a size-less declaration-level array (`id_list => [text]`, a top-level
  application of the `array` constructor) remains a separate, not-yet-resolved case. The
  `@alias:type_name`-style annotation §8.3 would add for `type_name` aliasing `token` is
  deliberately not produced, same deferral as the array-sugar cases above. Verified against
  `schema` itself from the real fixture.
- **Field modifiers** (`~`/`=`, §5.2, §5.10) on a REQUIRED field split two ways: a modifier token
  that names one of the *declaration's own* type parameters (`array`'s `element_type: type_ref = T`,
  `T` declared by `array => <T> ...`) is a parameter reference, recorded as `value_param` rather than
  `value` (§5.10's "labelled form", used uniformly whether the routed field is scalar or
  `type_ref`-typed) -- a parametric `=` leaves the field's state at its unmarked `REQUIRED` (nothing
  is actually fixed at declaration; the argument arrives at application), a parametric `~` still
  promotes to `REQUIRED_DEFAULT`, same as a literal default. Any other modifier token is an ordinary
  literal, recorded as `value` with state promoted to `REQUIRED_DEFAULT` (`~`) or `REQUIRED_FIXED`
  (`=`). An `Absent` modifier value (`= _`) and a modifier on an OPTIONAL field are both not resolved
  yet. Verified against the real fixture's `tuple_element`/`field_group` (both fresh records, so
  untangled from tightening -- see below) plus small hand-built snippets mirroring `array`'s own
  field shapes for the fixed/parametric cases in isolation.
- **Tightening** (§5.7), inside a composition's trailing body -- a body field naming an
  already-inherited field is no longer an automatic error: it's resolved and **replaces the
  inherited field in place** (§5.8's field-ordering rule; new fields still append after all
  inherited ones), gated by §5.7's own state-transition table (`REQUIRED` -> itself/`REQUIRED_DEFAULT`/
  `REQUIRED_FIXED`; `OPTIONAL` -> anything; `REQUIRED_DEFAULT` -> itself/`REQUIRED_FIXED`;
  `REQUIRED_FIXED`/`OPTIONAL_FIXED` -> only themselves) -- an invalid transition (e.g. `REQUIRED` ->
  `OPTIONAL`) is a resolver error. An elided type-ref in a tightening entry (`field: = value`, no
  type-ref restated) inherits the source field's type, per §5.7's own "Elided type-refs" rule. This
  is exactly what unblocks `array`/`map`: both compose with `product` and re-declare its
  `access_pattern`/`size_type` fields with fixed values, which now resolves as tightening
  (`REQUIRED` -> `REQUIRED_FIXED`) instead of throwing. The identity-diagonal value-invariant (a
  restated `REQUIRED_FIXED`/`OPTIONAL_FIXED` field's value MUST NOT change) isn't checked yet -- no
  real fixture declaration restates an already-fixed field. Verified against the real fixture's
  `array`/`map` end-to-end, plus hand-built snippets for a rejected invalid transition and an
  elided-type-ref tightening (adapting §5.7's own `production => config ^ { host: =
  "prod.example.com" } }` worked example to a composition body).
- **The `^` refinement operator** (§5.7, `RefinedDef`) -- `source ^ { ... }`, optionally
  `~`-marked and/or parameterized: `set`'s own `<T> ~array<T> ^ { state: = REQUIRED  unordered: =
  true  unique_items: = true }`. Unlike composition, a refinement copies the source's **entire**
  field set and admits **no new fields** -- every body entry MUST tighten an inherited field
  (reusing the same tightening machinery above), or it's a resolver error ("adding fields is a
  resolver error", §5.7). `source` is recorded verbatim as the result's own `source` field (a
  refinement always sets it, unlike composition, which never does) -- a bare name or, as in `set`'s
  case, a generic application (`array<T>`, `T` shadowing `set`'s own declared parameter of the same
  name) resolved the same way a top-level constructor application's arguments are. `supertypes`
  accumulates by the same induction as composition (`[sourceName] + source.supertypes()`); the
  body's own `record.supertypes` stays empty (that field records only direct `&` compositions as
  written, and a refinement has none). Verified end-to-end against the real fixture's `set`
  (refining `array`, tightening its `REQUIRED_DEFAULT` fields `state`/`unordered`/`unique_items` to
  `REQUIRED_FIXED`) and `array_min`/`array_ranged` (each routing an inherited OPTIONAL field --
  `min_items`/`max_items` -- to `REQUIRED` via its own value parameter, an `OPTIONAL` -> `REQUIRED`
  tightening per §5.7's table). Restating a field group in a refinement body, and a non-record
  refinement source, remain unresolved.

- **Structure-namespace threading** (added 2026-07-24, Phase B step 2) -- `resolve`/`resolveSchema`
  each gained a `structureNamespace` overload (`Map<String, TypeDefinition>`, defaulting to `Map.of()`
  on the existing overloads, so every pre-existing call site is unaffected). Per §3.3.1: a
  constructor-application target (`!C value`) resolves first against the type-name namespace
  (`resolved`, the entries already resolved so far in the *current* schema), then against the
  structure namespace (the governing meta-schema's own namespace, one hop via its own `!!meta`) --
  an atom-refinement source (`!I ^ {...}`) never consults the structure namespace at all. Pure
  plumbing on its own (nothing consumed it until step 4 below); confirmed inert with a dedicated
  test (`DefinitionResolverTest.structureNamespaceOverloadsAreInertUntilInstanceAtomRefinementDispatchExists`)
  before anything used it.
- **Constructor application** (`!C value`, `Instance`, §5.5/§5.6, `resolveInstance`, added
  2026-07-24, Phase B step 4) -- resolves `C` per the two-namespace lookup above, rejects a
  non-constructor target (`constructor: false`) with the spec's own suggested diagnostic ("did you
  mean atom refinement?"), normalizes `instance.value()` to record form (`PositionalForm`, below,
  using `C`'s own resolved field list), then binds generically via `TsonMapperReader.toObject(normalized,
  Top.class)` -- `instance.value().typeRef()` already names `C` (`Instance` itself was reshaped for
  exactly this, `SPEC-FEEDBACK.md` #16: no separate `target: String` field, since `DataValue.typeRef`
  already carries it), so `tson-bind`'s own union-member resolution finds the matching `Top` leaf by
  `@Typename` with no hand-rolled name→class table anywhere (the originally-planned
  `ConstructorVocabulary` class was never built, once `tson-bind`'s own sealed-union flattening bug
  was fixed first, `812a73f` -- see `Top`'s own Javadoc). **Binds against `Top.class`, not
  `Atom.class`** -- widened from an initially narrower scope once `UnknownType` (`unknown_type => ~sum
  & {}`) turned up as the first real constructor outside the atom family; confirmed safe, since
  `Top`'s union is a strict superset of `Atom`'s and union resolution is exact-`@Typename` matching,
  so no existing atom-family resolution changed behavior. Construction transfers only `C`'s `kind`
  (§5.5): no supertypes, no parameters, `constructor: false` on the result.
- **Positional form and schema-composed defaults** (`PositionalForm`, a small standalone,
  package-private helper class, added 2026-07-24 as Phase B step 3, widened the same day once
  `float32`/`float64` surfaced a second real need) -- normalizes an `Instance`'s value into the
  exact record shape its constructor implies, before binding, so nothing downstream needs any
  schema awareness at all. Two independent jobs, both needing the *resolved schema* (plain Java
  reflection on the bound class can't recover either):
  - **Positional form** (§5.6: "a record with exactly one REQUIRED field can be filled by a bare,
    non-braced value") -- `!enum [true false]` standing in for `!enum { members: [true false] } }`.
    Finds the constructor's sole bare-`REQUIRED` field (only bare `REQUIRED` counts -- `REQUIRED_DEFAULT`/
    `REQUIRED_FIXED`/`OPTIONAL`/`OPTIONAL_FIXED` never are, even if it's the only field present) and
    wraps the bare value into a synthetic one-field `RecordValue`; `EmptyBrace`/`RecordValue` pass
    through unchanged.
  - **Schema-composed defaults** (§5.2/§5.7's `~`/`=` field modifiers) -- fills in any
    `REQUIRED_DEFAULT`/`REQUIRED_FIXED` field the instance doesn't itself mention, using the literal
    `Token` value the schema modifier already carries, without overriding a field the instance *does*
    specify. Found empirically, not designed up front: `float32 => !float_type { format: BINARY32 }`
    never mentions `allow_nan`/`allow_infinity`/`allow_subnormal`/`allow_negative_zero` (all `boolean
    ~ true` in meta.tn1's real `float_type`), so this only resolves at all once defaulting exists --
    previously failed loudly ("missing required field 'allow_nan'"), not silently. **Runs on both the
    wrap and pass-through paths** -- neither `float32`'s nor `uri_type`'s own `{}` case ever touches
    the wrapping branch at all (both arrive already record-shaped), which is exactly why defaulting
    couldn't live *inside* the wrap-only code path the way it was first attempted.
  - **Does not fix `uri_type`/`regex_type`**, despite their own `spec` field being exactly this
    shape (`REQUIRED_FIXED`) -- their Java shape keeps it nested inside `specification:
    AtomSpecification`, not flat, so a synthesized flat `spec` entry matches nothing and is silently
    ignored. Every *new* atom-constraint class added since (below) deliberately keeps its own RFC
    citation as a flat `String spec` field instead, specifically so it *can* receive this defaulting
    -- see `Cidr4Type`'s own Javadoc for the two corrections that took (nested → flat, then
    `java.net.URI` → `String`, since an untyped string can't bind into `URI` without a `!uri`
    type-ref -- the same reason `TextType`/`UriType.pattern` are `String`, not a compiled `Pattern`).

  **`PositionalForm` deleted outright (2026-07-28, once the compiled reader made it redundant, not
  before).** Both jobs described above -- positional-form wrapping and schema-composed defaulting --
  are now handled uniformly by `RecordAbstractReader` itself (`dataFields`'s own
  `positionalFieldIndex` handling; `precomputedValue`/`defaultOrRequireNonFixed`/`readSchemaDefault`
  respectively -- see "Class 2 compilation" below), consulted on *every* compiled record read, not
  just `Instance` resolution. `resolveInstance` already requires the constructor's own body to be
  record-shaped before ever reaching `bindAtomInstance`, so every real call path is guaranteed to hit
  a compiled `Record*Reader` -- confirmed by removing the call, rerunning the full suite (still
  1130/1130), and only then deleting the class and its own dedicated test.
- **Atom refinement** (`!I ^ { values }`, `AtomRefinement`, §5.5/§5.7, `resolveAtomRefinement`,
  added 2026-07-24, Phase B step 5) -- resolves `I` against the type-name namespace *only* (never
  the structure namespace, unlike `C` above), rejects a constructor source and a non-atom-family
  source (`kind != ATOM`); the constructor `I` was built from is reached via `I`'s own `source`
  field, never a further name lookup, so refinement works even where the constructor itself isn't
  name-visible. Unlike `Instance`, `AtomRefinement.bindings`'s own `typeRef` is *not* pre-set --
  that grammar defect (`SPEC-FEEDBACK.md` #16) was deliberately left unfixed (narrowing it needs
  `AtomRefinement.bindings` retyped to a real `RecordDef`, a bigger change than `Instance`'s own
  reshape) -- so this attaches `I`'s constructor name as the value's type-ref itself, then binds
  through the same `Top.class` path `Instance` resolution uses. No positional-form wrapping needed
  (§5.5 guarantees a refinement body is always a braced record). **Merges with `I`'s own
  already-bound value; does not replace it** (`mergeWithSource`, corrected 2026-07-24 after the
  user caught a real spec-reading mistake, `SPEC-FEEDBACK.md` #17) -- an earlier version of this
  method read §5.6's "`!I ^ { values }` desugars by retargeting to the instance's source
  constructor" as a full replace (values carry over verbatim, nothing of `I`'s own binding
  survives), which is wrong for a *chained* refinement: given `int8 => !integer ^ { size: {...} }`,
  `big => !int8 ^ { min: -500 } }` MUST still carry `int8`'s own `size` -- `big` is declared as a
  *refinement*, a narrowing, of `int8`, and §5.7's own "Body materialisation" rule for the
  structurally analogous record-refinement case is explicit that inherited fields survive
  untouched ("appear with their pinned values even when the refinement did not refer to them").
  `mergeWithSource` re-serializes `I`'s own bound value back to wire form via `TsonMapperWriter`
  (writing a `Top`-typed value by its own runtime class never emits a type-ref, giving exactly the
  plain-record shape wanted -- no hand-written per-atom-class merge logic needed for any of the many
  constraint classes this has to work for) and merges it field-by-field with the new refinement's
  own `values` (explicit values win; anything only `I` had survives). A fresh/`UNCONSTRAINED` source
  serializes to an empty record, so the merge is a no-op there -- this recovers the previous,
  already-verified non-chained behavior exactly, not a separate code path (confirmed: `core.tn1`'s
  own resolution count was unchanged by this fix). Verified directly, not just reasoned about:
  `DefinitionResolverTest.chainedAtomRefinementMergesWithIntermediateBindingsInsteadOfDiscardingThem`
  chains two refinements deep (`int8` → `big` → `veryBig`) and confirms `size` survives both hops,
  `min` survives the second hop, and each level's own explicit override still wins over what it
  inherited. Per §5.5's own
  text (not the general composition/refinement induction of §5.7/§5.8): `source` is `I`'s own
  constructor, `supertypes` is the literal single-element `[I]`, not transitively chained. Verified
  against the concrete case the whole phase started from: `int32 => !integer ^ { size: { bits: 32
  signed: true } }`, resolved from the real `core.tn1` fixture, producing the exact `IntegerType`.

Every other construct (elided field types outside a tightening entry, an `Absent` modifier value or
a modifier on an OPTIONAL field, the identity-diagonal FIXED-value invariant, restating a field
group in a refinement body, subtraction, a generic type-ref with a nested or value (non-simple)
argument, and an inter-supertype field collision) throws `UnsupportedOperationException` rather
than silently mis-resolving -- `DefinitionResolver`'s own Javadoc lists exactly what's in scope.

**Status against the real fixtures (re-check with a throwaway probe before trusting these counts --
they change every time resolver coverage widens): `meta-kernel.tn1` resolves in full (49/49, via
`MetaKernelBootstrapResolver`'s own two-pass ordering -- `TsonSchemaResolver.resolveSchema` alone, single-pass,
strict source order, still can't handle `boolean => !enum [...]` preceding `enum`'s own
declaration); `meta.tn1` resolves and registers in full (31/31, `MetaSchemaImportTest`, up from
24/31 before generic `Instance` resolution existed).**

**`boolean => !enum [true false]` used to be a real, permanent limit of generic binding -- it no
longer is, fixed 2026-07-26 alongside the `bindAtomInstance` compiled-reader swap.** The old
`TsonMapperReader`-based `bindAtomInstance` bound each bare array element identification-first
(`BaseTypeResolver`'s own null → boolean → number → string check, run *before* the target field
was ever consulted), so `"true"`/`"false"` misidentified as real Java booleans before
`EnumBody.members: List<String>` ever saw them -- every *other* real enum instance across both
fixtures has ordinary identifier members that never collide this way. The compiled reader's own
atom-family dispatch is schema-driven (looked up by the constructor's own name, fixed at compile
time via `ValueReaderFactoryRegistry`), not token-identification-driven, so this collision simply
doesn't happen there -- verified directly: `DefinitionResolverTest
.booleanInstanceResolvesCorrectlyViaTheCompiledReader` resolves `boolean` from a real-fixture-shaped
schema and gets back `EnumBody(members=["true", "false"])`, no exception. This also retired
`CoreTn1Parser`'s own hand-picked `boolean` bypass entirely (see "Bundled schema documents" above)
-- core.tn1's own local `boolean => !enum [true false]` redeclaration now resolves the same
generic way. **`core.tn1`'s own end-to-end declaration count is pinned down by
`CoreSchemaImportTest` (`io.ltr8.tson.parser.resolver`, 2026-07-28)** -- `CoreTn1Parser`/
`CoreTn1ParserTest`/`CoreTn1CompiledEndToEndTest` stay deleted (superseded by
`DefaultTsonCompiledSchemaLoader`'s own generic path, "Compiled schema registry" above), but this
new test covers the same ground with the current pipeline: registers meta-kernel then meta.tn1 then
core.tn1 via `loader.load`, exactly the sequence `TsonBundledSchemas`'s own class Javadoc
documents, and confirms all 48 of core.tn1's own declarations resolve and register. A second test,
`exactlyTheFiveUndocumentedAtomConstructorsCompileToErrorReaders`, goes one step further -- compiling
the registered result (a side effect of `TsonCompiledRegistry#register`, reached via the same
`loader.load` calls) -- and confirms *exactly* `cidr4`/`cidr6`/`email`/`mac`/`unknown` compile to
`ErrorReader` (constructed via `cidr4_type`/`cidr6_type`/`email_type`/`mac_type`/`unknown_type`,
five of the six constructors `ValueReaderFactoryRegistry` registers to `ErrorReader` outright -- the
sixth, `extern`, has no core.tn1 declaration), and every other entry compiles to a genuinely usable
reader. This resolves the open question an earlier probe attempt had left here (a `RecordBindReader
.Factory` validation error, not yet root-caused at the time) -- it wasn't a bug: the "validation
error" was always this same already-documented missing-factory gap, just not yet distinguished from
a genuine regression.

`duration` was a second real generic-binding failure until 2026-07-24, when `DurationType.min`/
`max` were retyped from `Optional<IsoDuration>` to `Optional<String>` (matching the
`TextType.pattern`/`UriType.pattern` precedent) specifically so the field binds generically with no
`DataBridge` -- `DurationParser`'s own `parseDuration` is the one place that text is turned into an
`IsoDuration`, on demand, not `DurationType` itself. (An earlier abandoned attempt at this same fix
tried registering an explicit `DataBridge<String, IsoDuration>` on a custom `DataBindContext`; the
flat-`String`-field approach below was simpler and is what actually landed.)

**A real recursion trap, found and fixed along the way -- read before touching `TypeArgument`.**
`TypeRef`/`TypeArgument` are mutually recursive (`TypeRef.arguments: List<TypeArgument>`, and a
reference argument wraps a `TypeRef` right back -- a genuine shape, e.g. nested size sugar like
`grid => <T, N> [[T; N]; N]` desugars to `array_ranged<array_ranged<T, N, N>, N, N>`). `TypeArgument`
was first modeled as a plain record with two `Optional` fields (the literal translation of the
kernel's own field-group shape, `{ (name: type_ref | value: value) }`) -- and every test in this
module immediately started failing with `StackOverflowError` the moment `array_min` resolution (the
first real user of a non-empty `arguments` list) exercised it: `tson-bind`'s record resolution
(`DefaultRecordBinder`) eagerly resolves every field's descriptor while building a record's own, with
no cycle protection, so the mutual recursion loops forever. `DefaultUnionBinder` exists precisely to
avoid this -- its own code comment says it deliberately does not resolve member descriptors up front,
"by using the actual member classes the resolution loop is broken." So `TypeArgument` is a sealed
interface (`Ref`/`Value`) instead, not a stylistic choice but the one shape that lets a
mutually-recursive pair like this bind at all today -- at the cost of a spurious `!ref`/`!value`
type-ref `toTson` writes that the kernel's own tag-less form doesn't have (documented in
`TypeArgument`'s own Javadoc and `DefinitionResolverTest`'s array-sugar assertions). If a future session
is tempted to "fix" this back to a plain record, re-read `TypeArgument`'s Javadoc first.

- **`io.ltr8.tson.schema.meta`** holds the resolved-value model -- one Java type per meta-kernel
  vocabulary record/enum, named to match: `TypeDefinition`, `TypeKind`, `FieldState`, `ElementState`,
  `ProductAccessType`, `ProductSizeType`, `RecordField`, `FieldGroup`, `IntegerSize`, `TupleElement`,
  `TypeRef`/`TypeArgument`, and the `Top` variants `RecordBody`, `Reference`, `Unit`, `EnumBody`,
  `ChoiceBody`, `ArrayBody`, `MapBody`, `TupleBody`. Not called `RecordBody.Record` or similar to match the
  kernel's own `record` constructor name exactly -- a Java class literally named `Record` would collide,
  confusingly, with `java.lang.Record` (the language feature every type in this model is built from); see
  `RecordBody`'s own Javadoc. `TypeRef` here shares a name with (but is a different package and a different
  concept from) `tson-parser`'s grammar-layer `io.ltr8.tson.parser.ast.schema.TypeRef` -- a source-text
  reference vs. a resolved one, the same overload the kernel itself makes. Every multi-word field carries
  an explicit `@io.ltr8.annotation.Field("snake_case_name")` -- `tson-bind` otherwise writes the bare Java
  component name verbatim (camelCase), and the kernel's own field names are snake_case throughout.
  - Covers every *structurally simple* meta-kernel shape (product/sum/reference bodies, and the
    supporting records used as field types elsewhere), plus every atom constraint-vocabulary family
    with optional bound groups (`integer_type`/`text_type`/`uri_type`/`regex_type`, added 2026-07-23;
    `decimal_type`/`float_type`/`rational_type`/`uuid_type`/`binary`/`date_type`/`time_type`/
    `datetime_type`/`duration_type`, the remaining nine constraint-bearing families, added
    2026-07-24) once it turned out each one's own fields are all `Optional`, so none actually needed
    the harder "represent a field-group's mutual exclusion in a *bound instance*" design work in the
    first place. **`Cidr4Type`/`Cidr6Type`/`EmailType`/`MacType`/`Ipv4Type`/`Ipv6Type`/`ComplexType`
    (all `Atom`) and `UnknownType` (`Sum`, not `Atom` -- `unknown_type => ~sum & {}`, the first real
    constructor found outside the atom family) joined the same day, record-only, deliberately with
    no `atom` parser at all** -- added specifically so their own real `core.tn1` instances
    resolve (`cidr4`, `email`, `unknown`, ...), not to add real CIDR/email/MAC/complex-number
    validation; each one's own Javadoc says so explicitly. Their RFC-citation field is a flat
    `String spec`, not nested `AtomSpecification` and not `java.net.URI` -- see `Cidr4Type`'s own
    Javadoc for the two corrections that took to get there (both explained in "Schema resolution"
    above's `PositionalForm` bullet). `DefinitionResolver` now resolves most atom-constraint-family
    instances directly via its own generic `Instance`/`AtomRefinement` resolution (see "Schema
    resolution" above) -- `MetaKernelBootstrapResolver` only still hand-binds three of meta-kernel's own six
    real instances (`uri_type`, `regex_type`, `enum`; see "Meta-kernel bootstrap" below for exactly
    why each one can't retire). Verified each constraint-bearing class actually round-trips through
    `TsonMapperReader`/`TsonMapperWriter` bound against `Top` (not just that it compiles) -- all of
    the above do except `DurationType`, by design, not a regression: its `IsoDuration` field pairs
    `java.time.Period`/`java.time.Duration`, and
    `TsonMapperContext`'s own Javadoc already documents why that pairing isn't force-bound by the
    *default* context, matching `Rational`/`Complex`'s identical treatment -- a caller needing it
    registers their own `DataBridge` rather than the library assuming one opinionated wire shape.
  - **`Top`/`Atom`/`Product`/`Sum`** (added 2026-07-23, `Top` promoted to `TypeDefinition.body`'s own
    declared type and the separate `TypeBody` interface deleted 2026-07-24) replicate the kernel's own
    composition chain (`atom => top & {}`, `product => top & { ... }`, `sum => top & {}`, `reference
    => top & { target: type_name }`, §4.1) as real Java subtyping: `Atom`/`Product`/`Sum` each
    `extends Top`, and every resolved-body leaf record implements whichever it IS-A (`Unit`/`EnumBody`/
    the atom constraint-vocabulary families → `Atom`; `RecordBody`/`ArrayBody`/`MapBody`/`TupleBody` →
    `Product`; `ChoiceBody` → `Sum`; `Reference` → `Top` directly, since `reference` composes with
    `top` only, not through one of the three base kinds). Lets a consumer test kind ancestry with
    `instanceof Product`/`instanceof Atom` instead of switching on `TypeKind` by hand.
    **`Top` used to sit alongside a second, separate single-level sealed union (`TypeBody`)** that
    `TypeDefinition.body`/`tson-bind`'s generic writer actually bound against -- kept apart only
    because `tson-bind`'s `DefaultUnionBinder` didn't recurse into a permitted subclass that was
    itself sealed, so binding directly against a multi-level hierarchy like `Top` didn't work; `Top`
    existed purely for `instanceof`-based kind checks on the side. Once that binder bug was fixed
    (2026-07-24, `812a73f` -- flattens a multi-level sealed hierarchy to its concrete leaves,
    verified against a hand-built two-level fixture in `tson-bind`'s `NestedSealedUnionTest` before
    being relied on here), the separation no longer bought anything: `TypeBody` was deleted outright
    and `TypeDefinition.body`/`TypeDefinition.product` retyped to `Top` directly. Verified in
    `TopKindHierarchyTest` (renamed from `TypeBodyKindHierarchyTest`, its old "every variant is both
    a TypeBody and a Top" case dropped since there's only one hierarchy now) -- note that a
    *negative* check like `!(unit instanceof Product)` doesn't need asserting at all: `Product`'s own
    `permits` list not naming `Unit` (a `final` record) makes the compiler reject that `instanceof` as
    provably impossible at compile time, a stronger guarantee than a runtime assertion.
  - **`AtomSpecification`** (added 2026-07-23) covers `atom_specification => { spec: uri }`, the
    mixin composed into `uri_type`/`regex_type` (`uri_type => ~text_type & atom_specification & {
    spec: = "https://www.rfc-editor.org/rfc/rfc3986" ... }`, `regex_type => ~text_type &
    atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc9485" }`). Unlike `access_pattern`/
    `size_type` (fixed per constructor *and* carrying no distinguishing information, so omitted
    entirely from `RecordBody`/`ArrayBody`/etc.), `spec`'s fixed value genuinely differs between the
    two composing constructors (RFC 3986 vs. RFC 9485), so it's kept as real, explicit data: `UriType`
    gained a `specification: AtomSpecification` field, and the newly-added `RegexType` (see "Built-in
    type vocabulary" above) holds one alongside its own `TextType constraints`. Verified in
    `UriParserTest`/`RegexParserTest` that the two cite the correct, different RFC.
- **No hand-written writer -- resolved values go through plain `TsonMapperWriter.toTson`
  (`io.ltr8.tson.parser.mapper`) directly**, deliberately, to validate the model is built from
  ordinary, idiomatic Java that `tson-bind`'s generic introspection already knows how to bind, not
  a shape that only worked because a bespoke writer papered over it. This confirmed the `Top`
  sealed-interface design is exactly right: each variant's own `@Typename` plus `tson-bind`'s
  automatic sealed-interface-as-union detection is *all* it takes to get
  `!record`/`!reference`/`!unit`/`!enum`/`!choice`/`!array`/`!map`/`!tuple` written correctly -- no special
  casing anywhere, for precisely the "body: top" polymorphism the kernel itself describes. It also
  surfaced concrete, worth-knowing limits of generic binding versus the fixture's own hand-authored
  style -- none of them wrong, all textual, and all documented in `DefinitionResolverTest`'s own class Javadoc:
  no outer `!type_definition` tag (plain records, unlike union members, never self-announce a type-ref);
  quoted strings where the fixture uses bare tokens (an enum's bridge yields a `String`, and `TsonMapperWriter`
  always quotes strings -- pre-existing, already-documented behavior, not new); every empty-list/`false`/
  at-default-enum field written out rather than omitted (`Optional.empty()`/`null` are the only things
  generic binding omits -- `tson-bind` doesn't support `Optional<List<T>>` yet, so an empty list can't opt
  into the same omission an `Optional<TypeRef>`/`Optional<Boolean>` field gets for free); and `TypeRef`
  always in its full `{ name: ... arguments: [...] }` form, never §5.6's positional bare-token spelling (a
  schema-specific encoding rule a Part-1-only binder has no reason to know about).
- **`TsonSchema`** (`io.ltr8.tson.schema.TsonSchema`, in `tson-schema`'s own main package, not
  `.meta` -- moved back there 2026-07-23; it had briefly lived alongside `TsonSchemaResolver` in
  `tson-parser` purely for organizational convenience, but needs no `tson-parser` dependency at
  all) is the resolved-schema wrapper -- the kernel's own `schema` type, `map<type_name,
  type_definition>` (§9), plus the governing-chain header directives its own document carried
  (`id`/`meta`/`imports`, §2.2), plus a `bootstrap` flag. **A plain `record`** -- an earlier version
  was a plain class specifically so a dedicated `MetaSchema` subtype could `extend` it directly to
  mark, in the type system, meta-kernel's own pre-loaded result; that subtype was removed
  2026-07-26 once `bootstrap` (a real, stored boolean -- `true` for exactly one object in the whole
  system, `MetaKernelBootstrapResolver.getMetaKernelSchema()`'s own output) covered the same distinction more
  simply, and once *linked-vs-unlinked* already needed its own type-level distinction
  (`TsonLinkedSchema`, see "Schema registry" below) -- a second subtype for "did this come from the
  bootstrap reader" was redundant once that existed. `TsonSchemaResolver.resolveSchema(SchemaDocument)`
  builds one from a whole document (not just its body), resolving each entry independently in
  source order and carrying the header straight through; most entries of an arbitrary real schema
  still throw via this path alone, since most constructs aren't resolved yet -- `DefinitionResolver
  .resolve(SchemaMap.Declaration)` resolves a single named entry and is the one to reach for against
  a real fixture until more constructs are supported (or use `MetaKernelBootstrapResolver`, below, for
  meta-kernel specifically).

### Meta-kernel bootstrap (`tson-parser/src/main/java/io/ltr8/tson/parser/resolver/MetaKernelBootstrapResolver.java`)

Meta-kernel is special: its own `!!meta` names *itself* (§1.5's "one deliberate circularity in the
series, closed by pre-loading rather than by resolution: implementations ship the kernel's resolved
structure, and this document describes it"). Ordinary schema resolution can't bootstrap it from
nothing either way: resolving a constructor-*application* instance (`!C value`, §5.5 -- e.g.
`integer => !integer_type {}`) needs `C`'s own vocabulary already known, and every `C` meta-kernel
uses is defined within meta-kernel itself.

`MetaKernelBootstrapResolver` is a stateless parser/resolver -- the same shape as `TsonSchemaParser`/
`DefinitionResolver` -- **producing a plain `TsonSchema` (`bootstrap() == true`) from
`getMetaKernelSchema()`, its only public method,** rather than being one itself (an even earlier
version of this class extended `TsonSchema` directly; then briefly held a dedicated `MetaSchema`
subtype for the result, since removed 2026-07-26 in favor of the plain `bootstrap` flag -- see
"Schema registry" above). **Deliberately locked down to exactly one public method, taking no
arguments** (narrowed 2026-07-26, on the user's own explicit direction) -- this class exists to
bootstrap *the* real meta-kernel document (`TsonBundledSchemas#META_KERNEL_ID`), nothing else. An
earlier version also exposed `parse(String source)`, letting a caller resolve arbitrary
meta-kernel-shaped text through this same two-pass machinery -- unused anywhere in this codebase and
removed outright, not just deprecated, since keeping it around invites exactly the kind of "just
reuse the bootstrap parser for this other thing" misuse this lock-down exists to prevent. It
resolves in two passes over meta-kernel's 49 declarations:

1. Every declaration whose `TypeDef` is **not** an `Instance` goes through `DefinitionResolver` exactly
   as normal, in source order (36 of the 49, everything `DefinitionResolver`'s own Javadoc already
   documents as in scope).
2. The 13 deferred `Instance` declarations (`value => !unit {}`, `boolean => !enum [true false]`,
   `integer => !integer_type {}`, and friends) are resolved in a **second pass**, once every
   constructor they reference -- including ones declared *later* in the file, e.g. `enum` itself
   isn't declared until long after `boolean` uses it -- has an entry to transfer a kind from (§5.5:
   "construction transfers only the constructor's kind; the result records source: C with empty
   supertypes"). This two-pass ordering is still needed and still lives here, not in
   `TsonSchemaResolver` itself -- `TsonSchemaResolver.resolveSchema` alone is single-pass, strict source order,
   and still can't handle a forward reference like `boolean` preceding `enum`.

**Constructor-application binding goes entirely through `MetaKernelBootstrapResolver.instanceBody`, a
package-private (for its own defensive-path tests) closed switch -- not `DefinitionResolver`/
`TsonMapperReader`, and not any schema-driven compiled reader either.** Widened 2026-07-25 from an
earlier version ("Phase B step 6") that routed `unit`/`integer_type`/`text_type` through
`DefinitionResolver.resolve`'s ordinary `Instance` path and hand-picked only `uri_type`/`regex_type`/
`enum` -- that split turned out to be an unnecessary halfway point. Originally pulled out into its
own `BootstrapMetaKernelCompiler` class the same day, then merged back into `MetaKernelBootstrapResolver`
itself a few hours later, on the user's own observation: it had exactly one caller, produced a `Top`
*value* (a resolution-stage output) rather than a compiled/`TsonCompiledSchema`-shaped artifact, and
"Compiler" in its name collided with what that word now means elsewhere in this codebase (now the
real `TsonSchemaCompiler`) -- reserving "Compiler" for a class that actually produces a compiled
reader keeps the vocabulary unambiguous. The full case against letting *any* meta-kernel `Instance`
reach generic binding: `DefinitionResolver.resolveInstance`'s own generic path binds via
`TsonMapperReader`, which is identification-first (a token is classified null/boolean/number/string
*before* the target field is even consulted) -- exactly why `boolean => !enum [true false]` needs
hand-picking at all (`"true"`/`"false"` misidentify as real booleans before `EnumBody.members` ever
sees them) and why `uri_type`/`regex_type`'s own schema-composed RFC-citation default never lands
(nested inside `specification: AtomSpecification`, past what generic defaulting fills in). Rather
than deciding case by case which targets are safe for the generic path, `instanceBody` hand-picks
all six of meta-kernel's own real constructor targets uniformly -- confirmed directly against the
real fixture, meta-kernel only ever instantiates them in exactly two shapes: a bare `{}` (`unit`,
`integer_type`, `text_type`, `uri_type`, `regex_type` -- each target's own `UNCONSTRAINED` constant,
or a bare `new Unit()` for `unit` itself, is already exactly right) or a bare array of tokens
(`enum` -- this class's own `toEnumBody`, package-private for its own test accessibility, reading
`TokenValue.text()` directly, bypassing base-type identification entirely). No compiled reader
(`TsonSchemaCompiler`/`TsonCompiledSchema`), no `ValueReaderFactoryRegistry`, no materialization, no
`TsonMapperReader` involved anywhere in meta-kernel's own bootstrap now. (The grammar-layer
`TsonSchemaParser` -- an unrelated class, despite the similar name, see "Naming convention" above --
*is* used, straightforwardly, to parse meta-kernel's own source text into a `SchemaDocument` before
any of this runs.)

**Why even a *compiled*-reader-based bootstrap can't safely read meta-kernel from its own
in-progress state either -- this is why `instanceBody` doesn't attempt one:** `enum => ~atom & {
members: set<token> } }`'s own field type is *argument-bearing* (`set` + the `token` argument), and
the compiled-parser layer's own `RecordAbstractReader`/`TsonSchemaCompiler.Compilation` assume every
field type is already a bare, materialized name -- true only after `TsonSchemaLinker`'s own materialization pass has
run, which meta-kernel (the thing that pass would normally run *over*) has never been through while
it's still being produced. Solving that would mean either running a whole-schema materialization
pass over a knowingly-incomplete map (checked directly: doesn't work -- `integer_size => { bits:
... signed: boolean }` is itself a first-pass entry whose `signed` field already references
`boolean`, unresolved until the *second* pass) or building a scoped, validation-free materialization
step just for bootstrap's own benefit. Given meta-kernel's own instance shapes are this narrow and
fully known in advance, that complexity buys nothing -- this class's own guiding principle here:
"the bootstrap compiler can do whatever tricks it needs to... that includes not even compiling, just
calling `new Xxx(...)`." (This is also why `DefinitionResolver.bindAtomInstance` -- which *does* now
bind every other meta-kernel/meta.tn1/core.tn1 `Instance` and atom refinement through a real
compiled reader, see "Schema resolution" above -- was never a candidate for meta-kernel's own
bootstrap specifically; the two problems look similar but aren't the same one.)

**Every `Instance` declaration in the real fixture is registered** (`unit`, `integer_type`, `text_type`,
`uri_type`, `regex_type`, `enum`) -- all 49 declarations resolve; a declaration whose target isn't
registered would simply be left out of the result entirely rather than failing the whole bootstrap,
but that path is unexercised against the real fixture today. Verified both end to end
(`MetaKernelSchemaRegistryTest`/`MetaKernelEndToEndTest`, unchanged counts before/after both the
original hand-picking widening and the later class merge -- 49/49 resolved, 58 once linked)
and in isolation (`MetaKernelBootstrapResolverTest`'s own `unrecognizedInstanceTargetCompilesToEmpty`/
`aNonEmptyBodyForAnEmptyBodiedTargetThrows`, absorbed from the deleted `BootstrapMetaKernelCompiler`
class's own test the same way the production code was).

**`meta-kernel.tn` is packaged as a classpath resource, not read from a filesystem path.**
`getMetaKernelSchema()` fetches its own source text via `TsonBundledSchemas.fetch
(TsonBundledSchemas.META_KERNEL_ID)` (`tson-schema` -- see "Bundled schema documents" below) rather
than reading the classpath resource itself directly, as an earlier version of this class did -- one
place now owns "how this library's own bundled schema documents get their raw text," not two.
`tson-schema/build.gradle.kts` wires its own `processResources` task to copy
`meta-kernel.tn`/`meta.tn`/`core.tn` straight from the repo's own `spec/m/` snapshots at build time,
so there is exactly one copy of each file on disk to keep in sync with the spec, but the bootstrap
still works from a built jar (e.g. published to a repository), not only from a repo checkout.

**`IntegerType`, then `TextType`/`UriType`/`RegexType`, all became real `Atom` variants for this
(2026-07-23)** -- the four atom constraint-vocabulary families the old `TypeBody`'s own Javadoc used
to list as "deliberately not modeled yet", now all modeled: `IntegerType` needed no
field-group-in-a-bound-instance design work (mutual exclusion between `min`/`exclusiveMin` and
`max`/`exclusiveMax` is already enforced by its own compact constructor), and `TextType`/`UriType`/
`RegexType` needed none either -- every field across all three is `Optional`, so each already had
(or gained) its own `UNCONSTRAINED` constant for exactly this empty-body case. Each gained
`@Typename` (`text_type`/`uri_type`/`regex_type`) and multi-word fields gained `@Field`
(`min_length`/`max_length`), matching the convention every other `Atom` variant already
follows -- `IntegerType`/`TextType` now bind generically via `DefinitionResolver` (see above);
`UriType`/`RegexType` don't (still hand-picked, above), but keep the same annotations for
consistency and in case something else (e.g. a `toTson` call on one of these) needs them. The
remaining nine atom constraint-vocabulary families (`DecimalType`/`FloatType`/`RationalType`/`UuidType`/`BinaryType`/
`DateType`/`TimeType`/`DateTimeType`/`DurationType`) joined the same way on 2026-07-24 -- see
"Schema resolution" above's `io.ltr8.tson.schema.meta` bullet for what does/doesn't actually bind
by default among them.

**A real `tson-bind` gotcha, surfaced by the now-superseded `TsonMapper`-based version of this
bootstrap but still true and worth knowing for any other `schema.meta` class's first use as a bind
target:** a class with more than one public constructor needs `@io.ltr8.annotation.Field`'s sibling
`@io.ltr8.annotation.Record` on the canonical one, or `DefaultRecordBinder.getConstructor` throws
`CodeAnalysisException` ("Could not find constructor"). `getConstructor` only auto-picks a class's
sole constructor when exactly one exists; with two (a canonical plus any convenience overload --
`IntegerType`'s and `IntegerSize`'s own `IntegerSize(int, boolean)` convenience constructor both
qualify), it looks for one annotated `@Record` and fails if none is. This isn't a `tson-bind` bug
(the annotation and the fallback logic both exist specifically for this case) -- it's just that
neither class had ever been a bind *target* before (every earlier use just constructed them
directly in Java), so nothing had surfaced it. Fixed by writing out the canonical constructor
explicitly (compact, for `IntegerType`; empty-bodied, for `IntegerSize`, which didn't have one
written out at all before) with `@Record` attached -- both annotations stay in place even though
`MetaKernelBootstrapResolver` itself no longer needs generic binding, since `DefinitionResolverTest`'s own
`toTson` verification (below) still binds through `TsonMapperWriter`.
- **Verified against the real fixture, not just a hand-written snippet.** `DefinitionResolverTest` resolves
  `integer_size` both from a small inline schema and from the real `spec/m/meta-kernel.tn1`, and asserts
  the exact real `toTson` output -- structurally equivalent to (per the divergences above, not a content
  difference from) `meta-kernel-resolved.tn1`'s own `integer_size` entry, and, via hand-built `Reference`/
  `Unit`/`EnumBody`/etc. values exercising shapes `DefinitionResolver` doesn't produce yet, `type_name`'s/
  `value`'s/`boolean`'s own entries too.

### Schema registry (`tson-schema/src/main/java/io/ltr8/tson/schema/`, `.../registry/`)

`DefinitionResolver`/`MetaKernelBootstrapResolver` (both in `tson-parser`) resolve each declaration
*individually* — no whole-schema consistency checking, references carried through as bare,
unverified strings, `!!import` parsed but never consulted, and a `type_ref` with arguments (e.g.
`enum`'s own `members: set<token>` field, or any field using §5.3's `[X]`/`[X]?` array sugar) left
exactly as written. `TsonSchemaLinker`/`TsonSchemaRegistry` add the missing second stage on top:
internal-consistency validation, flattening every argument-bearing `type_ref` into a real named
entry, and — once satisfied — locking the schema into a registry keyed by its canonical `!!id`
identity. Added 2026-07-24, entirely in `tson-schema` (no dependency on `tson-parser`, preserving
the established one-way direction).

**Linking and registering are two separate stages, not one** (`link` -> `register`, split
2026-07-27 on the user's own explicit direction, borrowing standard compiler vocabulary for the
whole pipeline: parse -> resolve -> link -> register -> compile -> read — see "Naming convention"
above). `TsonSchemaLinker.link(TsonSchema, TsonSchemaLoader)` is the pass-2 engine described below,
returning a `TsonLinkedSchema` — a thin wrapper (`record TsonLinkedSchema(TsonSchema schema)`)
that exists purely as a compile-time proof that linking already ran, replacing an earlier runtime
`materialised` boolean flag on `TsonSchema`. `TsonSchemaRegistry#register` now only accepts a
`TsonLinkedSchema`, so "has this been linked" is answered by the type system, not by a flag every
caller has to remember to check. `TsonSchema` also carries a `bootstrap` flag (unrelated to
linking) — `true` for exactly one object in the whole system, `MetaKernelBootstrapResolver`'s own pre-loaded
output (see "Meta-kernel bootstrap" below); `TsonSchemaRegistry#register` refuses any self-referential
schema (its own `!!meta` names its own `!!id`) with `bootstrap() == true`, whether linked or not —
meta-kernel's own identity can only ever be registered via a schema resolved *ordinarily*
(`TsonSchemaResolver.resolveSchema`, which never sets `bootstrap`), never the bootstrap-produced form
directly. There used to be a separate `MetaSchema` subtype of `TsonSchema` for marking meta-kernel's
own pre-loaded result; replaced by the plain `bootstrap` flag (2026-07-26) once linking already
needed its own type-level distinction, so a second one for "did this come from the bootstrap reader"
was redundant.

**Package split, user-facing vs. internal-by-convention (per explicit user direction):**
`io.ltr8.tson.schema` holds the public surface — `TsonSchemaRegistry`, `TsonSchemaLoader`,
`TsonSchemaValidationException`, `TsonSchema`, `TsonLinkedSchema`, and `TsonSchemaLinker`.
`io.ltr8.tson.schema.registry` holds only `CanonicalIdentity`, genuinely internal-by-convention --
it was never a named pipeline stage. **`TsonSchemaLinker` was renamed from `SchemaValidator`
(alongside the `link`/`validate` rename) and moved out of `.registry` into this package directly
(2026-07-27), once its own publicness was settled** — a caller orchestrating the pipeline calls
`TsonSchemaLinker.link` directly and deliberately, same as `parse`/`resolve`/`compile`, including
from *other* modules (e.g. `tson-parser`'s own `TsonCompiledRegistry`, which links a schema before
registering it); living inside `.registry`, a package whose own docs describe its contents as
"private pass-2 machinery nothing outside this module calls directly," was the one thing still
contradicting that. **Now genuinely enforced, not just package-naming discipline** — `tson-schema`
gained a real `module-info.java` (2026-07-29, see "Module system (JPMS)" near the end of this file)
that exports `io.ltr8.tson.schema`/`io.ltr8.tson.schema.meta` but deliberately not `.registry` —
`CanonicalIdentity` stays `public` (still needed cross-package by `TsonSchemaRegistry`/
`TsonSchemaLinker`, in the same module), but a *different* module (e.g. `tson-parser`) importing it
now fails to compile outright ("package io.ltr8.tson.schema.registry is not visible ... does not
export it"), confirmed by trying it directly, not just reasoned about.

- **`CanonicalIdentity.of(String)`** implements `[TSON-DATA] §2.2.1`'s canonical-identity algorithm
  exactly — **not** general URI normalization. The spec performs exactly two reductions (strip
  scheme + `://`, strip query) and requires everything else already be canonical — lowercase host,
  no userinfo, no port (default or otherwise), no percent-encoding of *unreserved* characters
  (`A-Za-z0-9-._~`; encoding anything else is fine), no dot-segments, no fragment — rejecting
  (`TsonSchemaValidationException`) rather than fixing up an identifier that isn't. E.g.
  `"https://tson.io/2026/32/m/meta-kernel.tn1"` → `"tson.io/2026/32/m/meta-kernel.tn1"`; `http://`
  and `https://` resolve to the same identity; a `?sha256=...` query is dropped, not validated.
- **`TsonSchemaLoader`** (`@FunctionalInterface Optional<TsonLinkedSchema> load(String canonicalIdentity)`)
  — the pluggable-with-a-default hook for resolving a `!!import`/`!!meta` target, matching Part 2
  §10.1's precedence order (pre-loaded/registered authoritative, "fetched" opt-in and disabled by
  default). `TsonSchemaRegistry` implements `TsonSchemaLoader` itself (a thin delegation to `get`), purely so
  a caller can write `TsonSchemaLinker.link(schema, registry)` directly, passing the registry as its own
  lookup source. `TsonSchemaRegistry`'s own no-arg constructor supplies a default loader that only ever
  finds an *already-registered* schema — nothing is fetched from anywhere.
- **`TsonSchemaLinker.link(TsonSchema, TsonSchemaLoader)`** — the actual pass-2 engine:
  1. **Merge `!!import`s** (Part 2 §2.2.3) — every import, in declaration order, looked up via
     `loader` by canonical identity, its entries copied in *as-is* ("merged entries keep their home
     namespace" — an imported `TypeDefinition` is never re-resolved or re-materialized against the
     importer; only the importer's own new material gets that treatment). **Shallow** — only the
     imported schema's own `entries()` are read, never its own `imports()` — falls out for free here
     since `loader` always hands back an already-registered, already-flattened `TsonLinkedSchema`. A
     name collision — between two imports, or between an import and a local declaration — is a
     resolver error, checked as each stage merges in, not after the fact. An import whose identity
     isn't found via `loader` is a `TsonSchemaValidationException` (e.g. the importer needs to have been
     registered into the *same* `TsonSchemaRegistry` first — the default loader is registered-only).
  2. **Materialize** — walks every entry's `Top` body (deliberately *not* `TypeDefinition.source` —
     see below) for any `TypeRef` with non-empty `arguments`, bottom-up (a nested argument that's
     itself argument-bearing materializes first, so an outer synthesized name is built from an
     already-flattened application). **Uniform** — *any* argument-bearing `type_ref` gets a
     synthesized entry, regardless of whether the applied name is itself a constructor (`set`) or a
     genuine non-constructor template — a deliberate simplification confirmed with the user,
     narrower/simpler than Part 2 §8.2's literal text (constructor applications "never materialise
     entries" per the spec; here they do too, uniformly). Deduped via a `Map<TypeRef, String>` keyed
     by the flattened application (`TypeRef`'s own record equality is exactly §8.2's "flattened
     applications are structurally equal" test) — first occurrence creates the entry, later
     structurally-identical occurrences reuse it. **A synthesized entry is real construction for
     `array`/`set`** (`instantiate`/`instantiateArray`, e.g. `array<field_name>` from `[field_name]`
     sugar materializes to a genuine `!array { element_type: field_name }` body, not a
     self-referential placeholder) — falls back to `TypeDefinition.reference(TypeRef)`'s own
     placeholder shape for everything else this doesn't (yet) know how to build directly
     (`map`/`tuple`/`record`/`choice`/any atom-family constructor, or an arity mismatch). A
     synthesized name is `head_arg1..._hash` (§8.2's own non-normative "readable head plus
     structural hash" guidance; not conformance-relevant, free to refine). Every `Top` variant has
     its own rewrite case, written as an **exhaustive switch over the (multi-level) sealed
     interface** (no `default`) so a future new variant is a compile error here, not a silent miss --
     `switch`/pattern matching checks exhaustiveness across the whole `permits` graph transitively.
  3. **Populate `TypeDefinition.subtypes`** — the reverse of `supertypes`, for *this schema's own
     merged view* of every entry it can see, imported or local (`computeSubtypes`) — never done
     anywhere before this stage; falls out transitively for free since `supertypes` is already the
     full transitive IS-A chain.
  4. **Validate** — over the now-expanded map (originals + synthesized), every reference must
     resolve: `TypeDefinition.source`/`supertypes`/`subtypes`, and every `TypeRef` reachable through
     the same exhaustive `Top` switch (`RecordBody.fields`, `Reference.target`,
     `MapBody.keyType`/`valueType`, `ArrayBody.elementType`, `TupleBody.elements`,
     `ChoiceBody.variants`). **Type-parameter exception** (load-bearing for every parameterized
     declaration — `array`, `set`, `map`, `array_min`, `array_max`, `array_ranged`): a bare name is
     valid if it resolves in the namespace *or* is one of the checked entry's own declared
     `parameters` — e.g. `set => <T> ~array<T> ^ {...}` resolves its own `source` (`array<T>`) where
     `T` is `set`'s own parameter, not a real entry. `RecordBody.groups[].members` gets a bonus
     check against a different namespace (sibling field names within the same record, not type
     names). Any failure throws `TsonSchemaValidationException` naming the offending entry/reference.
     **Constructor eligibility** (added 2026-07-27, on the user's own explicit direction, per §2.2.2's
     own "chaining to meta-kernel.tn directly is a meta-programming case" — see `SPEC-FEEDBACK.md`
     #19 for why this isn't stated as a spec MUST): a locally-declared entry with `constructor: true`
     is only valid if `schema`'s own `!!meta` is *exactly* `TsonBundledSchemas.META_KERNEL_ID`
     (`https://tson.io/2026/32/m/meta-kernel.tn`) — checked once, lazily, on the first such entry
     found (`isMetaKernelGoverned`). **A fixed identity, not a structural "is this schema
     self-referencing" test** (an earlier version of this check was structural, tightened the same
     day on the user's own further direction): every resolved `TypeDefinition.body` and every
     `!instance` construction is interpretable only because a matching constructor is declared in
     *this specific* meta-kernel — `ValueReaderFactoryRegistry`/`AtomValueReader`/`RecordDomReader`/
     `RecordBindReader` (in `tson-parser`) are Java code hard-wired to this one meta-kernel's own fixed vocabulary, not to
     "whatever schema happens to be self-referencing"; a library supports one meta-kernel version at a
     time. No loader lookup needed at all now — comparing `schema.meta()` directly against the fixed
     constant works uniformly for meta-kernel itself (whose own `!!meta` literally is that constant)
     and for meta.tn (governed one hop below it) alike. `TsonBundledSchemas` (`tson-schema`, see
     "Bundled schema documents" below) is the one shared source this check and every `tson-parser`-side
     consumer both reference — neither declares its own copy. Scoped to locally-declared entries only — an imported
     `constructor: true` entry (e.g. meta.tn importing meta-kernel's own `record`/`array`/...) was
     already validated when *its own* home schema was linked, matching this class's own "merged
     entries keep their home namespace,
     never re-validated against the importer" principle. Confirmed against the real fixtures, not just
     reasoned about: meta-kernel.tn (9 constructors) and meta.tn (18, one hop below meta-kernel) both
     link cleanly; core.tn (governed by meta.tn, zero constructors of its own) was already compliant
     before this check existed. Two test fixtures needed a real fix, not a workaround — both were
     hand-built schemas that legitimately declare their own constructor vocabulary
     (`EnumDomReaderTest`'s copy of meta-kernel's own entries; `TsonSchemaLinkerTest`'s
     synthetic `array`/`set` stand-ins for testing materialization in isolation) but had a placeholder
     `!!meta` value; pointed at the real meta-kernel identity instead, which is what they actually use.
  5. Returns a new `TsonLinkedSchema` wrapping a `TsonSchema` — even when the input carried
     `bootstrap() == true`; once linked/registered, provenance no longer matters to anything
     downstream.

  **Scope note on `source`:** `TypeDefinition.source` is *provenance* (how an entry was itself
  derived — composition/refinement/construction), not a field consuming another type, so it's
  validated but never itself materialized into a further synthetic entry, even when it carries
  arguments (`set`'s own `source: array<T>` is exactly this case) — materializing it would create a
  synthetic entry with no standalone meaning, tied only to `set`'s own identity.

  **`source` validation also falls back to the governing meta-schema's own namespace, one hop via
  `!!meta` — every other reference (field/key/value/element types, supertypes, subtypes, choice
  variants) does not.** Surfaced by core.tn1 (see "Meta-kernel bootstrap" below): core.tn1 declares
  no `!!import` of its own, only `!!meta:"...meta.tn1"`, yet `void => !unit {}`'s own `source: unit`
  names a meta-kernel constructor — perfectly valid per Part 2 §3.3.1 ("`!C value` ... `C` resolves
  first against the type-name namespace ... and then against the structure namespace"), since a
  `source` naming a constructor is exactly one of §3.3.1's three enumerated *constructor roles*
  (constructor-application targets, generic-application heads, sugar-form desugar targets). **The
  fix is deliberately narrow, not a blanket namespace merge** — §3.3.2 is explicit that the
  structure namespace is "NOT extended" to ordinary type-refs ("field types... composition
  targets... never as type-refs"), only to the constructor roles §3.3.1 lists. `link`'s own
  `structureNamespace` (loaded via `loader.load(CanonicalIdentity.of(schema.meta()))`, empty if not
  yet registered — e.g. meta-kernel's own self-referential `!!meta`, mid-registration) is used in
  exactly two places: materialization's own constructor lookup in `instantiate` (every
  argument-bearing `type_ref` reaching it is, by construction, a generic-application head or
  sugar-desugar target — both structure-namespace-eligible), and `validateEntry`'s own `source`
  check specifically (via a small `sourceLookup` merge, local/imported entries always winning on
  collision) — never in `validateBody`'s field-type/supertype/subtype checks, which stay
  `merged`-only (type-name namespace, imports + locals), per §3.3.2. `!!meta` itself never merges
  anything into a schema's own returned `entries()` — that stays exactly what `!!import` produces,
  unchanged.
- **`TsonSchemaLinker.linkBootstrap(TsonSchema)`** — the one sanctioned way to turn meta-kernel's raw
  bootstrap output into a `TsonLinkedSchema` without registering it, needed so a caller (e.g.
  building an object-binding-mode `ValueReaderFactoryRegistry`) can get a genuinely linked result to
  validate against, without the registry's own self-referential-bootstrap guard getting in the way.
  Moved here from `TsonSchemaRegistry` (2026-07-27, on the user's own observation that it belonged
  with the verb it performs, not with a registry it deliberately never stores its own result in) —
  **takes no `TsonSchemaLoader`**, unlike `link` itself, since meta-kernel's own document (the only
  real caller) never has any `!!import`s — it's the base of the whole governing chain — so `link`'s
  own `loader == null` handling (an empty structure namespace) is already exactly right, and
  `mergeImports` is never reached for an empty `imports()` list regardless. `TsonSchemaRegistry
  #register` still refuses the result outright, since it's still `bootstrap() == true` — the one way
  meta-kernel's own identity ever actually gets registered is resolving its document a second time,
  ordinarily (never setting `bootstrap`), against a loader seeded from the one-off linked
  bootstrap result (see "Meta-kernel bootstrap" below).
- **`TsonSchemaRegistry`** — `register(TsonLinkedSchema)` computes the canonical identity from the
  wrapped schema's own `!!id` (throwing if absent), rejects the self-referential-bootstrap case
  above, rejects a duplicate identity outright (no overwrite — together with `TsonSchema.entries()`
  already being an unmodifiable map, this rejection *is* the "locked, no mutations allowed"
  guarantee), and stores the result. `get(String uri)` takes a *raw* URI and canonicalizes
  internally — callers never need to call `CanonicalIdentity` themselves for either method. A pure
  store now — no linking convenience method of its own; see `TsonSchemaLinker.linkBootstrap` above
  for meta-kernel's own one-off case, which `register` still refuses regardless (still `bootstrap()
  == true`) — the one way meta-kernel's own identity ever actually gets registered is resolving its
  document a second time, ordinarily (never setting `bootstrap`), against a loader seeded from
  the one-off linked bootstrap result (see "Meta-kernel bootstrap" below).

**Verified against the real fixture, not just hand-built schemas.** `MetaKernelSchemaRegistryTest`
(in `tson-parser`, not `tson-schema` — that module has no dependency on `tson-parser`/
`MetaKernelBootstrapResolver` at all, so this is the one place both are available) links
`MetaKernelBootstrapResolver.getMetaKernelSchema()`'s real output end-to-end: 49 raw declarations, 58 once
linked — 9 synthesized entries, not the 1 (`set_token_*`) a naive `<...>` grep of the source
predicts: `[X]`/`[X]?` array-sugar field types elsewhere in the fixture (`arguments:
[type_argument]?`, `fields: [record_field]`, `groups: [field_group]?`, `supertypes`/`subtypes`/
`parameters: [type_name]?`/`[param_name]?`, `elements: [tuple_element]`, `variants: [type_ref]`,
`members: [field_name]`) desugar to `array<X>` applications too (§5.3) — three separate
`[type_name]?` uses across different declarations correctly dedup to a single `array_type_name_*`
entry, confirmed against the real data, not just a hand-built case.

**`!!import` merging verified against the real `meta.tn1` fixture too — registers in full.**
`MetaSchemaImportTest` (`tson-parser`, same reasoning as above for why it lives there) registers the
real meta-kernel schema first, then meta.tn1's own declarations (`!!import:"...meta-kernel.tn1"`),
confirming meta-kernel's own entries (`atom`, `text_type`, ...) are visible and correctly referenced
from meta.tn1's own composition-based declarations (`date_type => ~atom & atom_specification &
{...}`). meta.tn1 resolves all 31 of its own declarations in a single source-order pass (its own
declaration order already places each dependency before its use), and the merged, linked
registration succeeds outright.

### Class 2 compilation (`compiler/{TsonSchemaCompiler,TsonCompiledSchema,TsonCompiledMetaSchema,ValueReaderFactory,ValueReaderFactoryRegistry,ValueReaderResolver,ErrorReader}.java`, `config/ValueReaderFactoryResolver.java`, `TsonValueReader.java`)

`TsonSchemaCompiler.compile(TsonLinkedSchema, TsonCompiledMetaSchema)` is the "compile" stage of
parse -> resolve -> link -> register -> compile -> read; `TsonCompiledSchema` is the noun it
produces, one `TsonValueReader` per resolved entry, wired together as real Java object
references rather than further name lookups at read time (except at the edges that close a
cycle, where `DeferredValueReader` does one lazy lookup). `TsonValueReader` (root package,
alongside `TsonDataParser`/`TsonSchemaParser`) is the single-method front door a caller actually
holds after compiling (`TsonCompiledSchema#get`) -- `T read(DataValue value)`, no `throws` clause,
matching this whole read/parse stack's unchecked-only convention.

**`TsonCompiledSchema` is a plain, already-built value** -- two final fields (`linkedSchema`, an
immutable `Map<String, TsonValueReader<?>>`), no build logic of its own, matching the verb/noun
split this project's own pipeline vocabulary uses everywhere else (`TsonSchemaLinker`/
`TsonLinkedSchema`, `TsonSchemaResolver`/its own resolved `TsonSchema`). `get(name)` reads *any*
entry, unscoped; `schema()` unwraps to the bare `TsonSchema` for the common case of reading resolved
`entries()`. All the actual compile-time work -- the eager walk, cycle detection, per-entry
build-failure deferral -- lives in `TsonSchemaCompiler` itself, in a private nested `Compilation`
helper: one instance per `compile` call, holding that call's own mutable `finished`/`building`
state, discarded once `compile` returns and hands back an immutable snapshot.

**Eager, not lazy** -- `compile` walks every one of `linkedSchema.schema().entries()` and resolves
each before returning, so a caller that only ever reads a handful of a large schema's own types
still gets the same assurance that every other entry compiles too, and any genuinely broken entry
is discovered at compile time rather than piecemeal whenever some future caller happens to `get` it.

**`ErrorReader`** (package-private) is what makes eager building survive real coverage gaps without
weakening that guarantee: `Compilation#resolve` catches *any* `RuntimeException` thrown while
`build`ing one specific entry and substitutes an `ErrorReader` wrapping it, rather than letting one
bad entry abort the whole eager walk. The schema as a whole still compiles in full; only actually
`read`ing a value against that specific entry fails, and only then, with the original exception's
message preserved (a deferral, not a swallow). Real causes seen against real fixtures: no
`ValueReaderFactory` registered for a constructor at all (`ValueReaderFactoryRegistry#resolve`
throwing -- e.g. core.tn1's own `cidr4`/`email`/`ipv4`/... atom families, which have no compiled
parser yet), and a factory that *is* registered still rejecting one particular entry (e.g.
`RecordBindReader.Factory` on a record that has both real fields of its own and subtypes -- see
"Object-binding mode" below). A missing/absent *referenced* name (`build`'s own target not present
in `schema.entries()` at all) is a different, stricter case -- a genuine `TsonSchemaLinker` invariant
violation, not "this build doesn't support constructor X yet" -- and still propagates immediately,
uncaught, not deferred into an `ErrorReader`. **The catch was briefly narrowed, mid-refactor, to two
hardcoded entry names (`atom_specification`/`type_argument`) while debugging the object-binding
rewrite below -- restored to the general catch-all on the user's own direction once it became clear
that was the right design all along, not a workaround** (confirmed: restoring it took the failing
suite straight to 1130/1130, since every other entry it was catching was *supposed* to defer, not
abort).

**`ValueReaderResolver` is package-private, alongside the eight `*DomReader`/`*BindReader` classes
(`Array`/`Map`/`Record`/`Tuple` × Dom/Bind, "Object-binding mode" below) -- narrowed in an
API-surface pass once `Tson`/`TsonConfig` gave this codebase a real front door to check against.**
All nine were `public` purely as a leftover of an earlier refactor stage (mid-refactor cross-package
test access, not a real external caller) -- confirmed by checking every actual reference before
touching anything: none is ever named from outside `compiler`'s own package, by any of the `tson`/
`tson-cli` modules or by a cross-package test. `TsonCompiledMetaSchema`/`TsonCompiledSchema`/
`TsonSchemaCompiler`/`ValueReaderFactory`/`ValueReaderFactoryRegistry` stayed public -- each has a
genuine cross-package or cross-module caller (`ValueReaderFactory` specifically because `config
.ValueReaderFactoryResolver#resolve` returns one across the `compiler`/`config` package boundary).

**`ValueReaderFactory`/`ValueReaderFactoryResolver`/`ValueReaderResolver`** are the three small
interfaces the dispatch runs through: `ValueReaderFactory.create(name, typeDefinition, resolver)`
builds one entry's own reader; `ValueReaderFactoryResolver.resolve(constructorName)` finds the
factory for a constructor name; `ValueReaderResolver.resolve(typeName)` is what a composite reader's
own child-field resolution calls (`RecordAbstractReader.buildFields`, `ArrayAbstractReader`'s own
element-reader resolution, ...) -- in practice always `Compilation::resolve` itself, so a child
reference recurses back into the same eager, cycle-safe machinery.

**`ValueReaderFactoryRegistry`** (`.dom()`/`.bind(DataBindContext)`) is a *fixed*, non-extensible
`constructor name -> ValueReaderFactory` table -- unlike an earlier, buildable registry design, there's
no `.builder()`/`.register(name, factory)` API; `dom()` and `bind(context)` are the two instances a
caller actually wants, each wiring the same closed vocabulary of meta-kernel/meta.tn1 constructors to
either DOM-mode or object-binding-mode factories (see "Object-binding mode" below for exactly which
constructors differ per mode, and which don't have a compiled parser at all yet, registered to
`ErrorReader` so a schema merely *declaring* one still compiles).

**`TsonCompiledMetaSchema`** is both halves a `!!meta`-governed schema needs from its own governing
meta: `reader(name)` reads an instance of one of the governing meta-schema's own declared
constructors (scoped to exactly what that meta-schema declares -- the structure namespace's own
rule, §3.3.1 -- built lazily on first call, not in the constructor, since a throwaway instance
wrapping a not-yet-compiled placeholder is only ever consulted via `create`, never `reader`); `create
(name, typeDefinition, resolver)` builds a compiled reader for some *other* schema's own declaration
during `TsonSchemaCompiler#compile`, dispatched by the resolved body's own constructor name against
the *full, global* `ValueReaderFactoryResolver` this meta-schema was built with -- deliberately not
scoped to `reader`'s own narrower set, since a schema governed by this meta-schema is free to declare
constructors the governing meta itself never mentions (meta.tn1, governed by meta-kernel, declares
`float_type` -- one of meta-kernel's own 12 doesn't include it). `bootstrap(TsonLinkedSchema,
ValueReaderFactoryResolver)` is the one deliberate circularity's own escape hatch (§1.5): a throwaway
meta-schema wrapping an *empty* placeholder `TsonCompiledSchema` stands in as `TsonSchemaCompiler
.compile`'s own required parameter while meta-kernel compiles against itself -- safe, since `create`
never reads anything from the wrapped schema, only from `resolver`, the same `resolver` either way.

### Compiled schema registry (`tson-parser/src/main/java/io/ltr8/tson/parser/compiler/TsonCompiledRegistry.java`)

Pairs one-to-one with `TsonSchemaRegistry` (`tson-schema`) but stores *compiled* meta-schemas
(`TsonCompiledMetaSchema`) instead of resolved ones — for every schema `TsonCompiledRegistry`
registers, it also compiles the registered result and keeps the compiled reader around, so a caller
never recompiles the same governing chain twice. This is what an application actually wants at
startup: bootstrap meta-kernel (`TsonCompiledMetaSchema#bootstrap`, outside this class entirely — it
needs no registry at all), register it (against its own bootstrap result, since nothing governs
meta-kernel but itself), then meta.tn1 (against meta-kernel's own freshly-registered compiled
meta-schema), then core.tn1 (against meta.tn1's own) — each step's own return value is exactly what
the next step needs as its own `governingMeta` argument. Any user-defined schema governed by one of
these reuses whatever's already sitting in the registry rather than recompiling its own governing
chain from scratch.

- **`register(TsonSchema schema, TsonCompiledMetaSchema governingMeta)`** — links `schema` via
  `TsonSchemaLinker.link` (using the paired `TsonSchemaRegistry` itself as the `!!import`/`!!meta`
  lookup source), registers the linked result via `TsonSchemaRegistry#register` (ordinary
  collision/reference-validation rules apply unchanged), compiles the *registered* result against
  `governingMeta` (never the raw input — `TsonSchemaCompiler` needs linking already done, and needs
  a real governing meta to dispatch constructor names against), wraps the result as this schema's
  own `TsonCompiledMetaSchema`, stores it keyed by the schema's own raw `!!id` string, and returns
  the wrapped result directly (so the very next schema in a governing chain, which needs *this*
  return value as its own `governingMeta` argument, doesn't have to immediately call `get` right
  back). `governingMeta` is always a previous call's own return value, except meta-kernel's own case
  (see `TsonCompiledMetaSchema#bootstrap` above).
- **Keyed by raw `!!id`, not a canonicalized identity** — deliberately. `CanonicalIdentity`
  (`tson-schema.registry`) is internal-by-convention to `TsonSchemaRegistry`/`TsonSchemaLinker` (confirmed via
  its own class Javadoc: "a caller outside this module should go through `TsonSchemaRegistry` instead of
  depending on this class directly") — reaching into it from `tson-parser`, a different module,
  would be exactly the cross-module layering violation this project otherwise avoids -- and, since
  `tson-schema` gained a real `module-info.java` (see "Module system (JPMS)" near the end of this
  file), that reach now genuinely fails to compile, not just a convention this class's own Javadoc
  asks nicely for. The cost: two
  differently-spelled-but-equivalent URIs for the same schema won't find each other here the way
  they would through `TsonSchemaRegistry.get` — acceptable for the one real caller today (always
  registers and looks up using each schema's own exact `!!id` string), a real narrower guarantee,
  not an oversight.
- **One shared `ValueReaderFactoryResolver` across every schema it compiles** — `resolver()` exposes
  it directly, e.g. so a caller can compile a one-off reader (such as meta-kernel's own bootstrap)
  with the same factories, without registering or caching it here. `ValueReaderFactoryRegistry.dom()`/
  `.bind(context)` are the two real instances a caller actually wants (see "Class 2 compilation"
  above) — every DOM-mode or object-binding-mode test that needs a full factory table calls one of
  these directly rather than hand-assembling its own.
- **Verified against real fixtures, not hand-built ones** — `MetaTn1CompiledEndToEndTest` loads
  meta-kernel and meta.tn1 through a single `TsonCompiledRegistry`/`DefaultTsonCompiledSchemaLoader` pair
  and compiles/reads against the result; several other tests (`TsonSchemaResolverCompiledMetaSchemaTest`,
  `MetaKernelSchemaRegistryTest`, `MetaSchemaImportTest`) exercise `TsonCompiledRegistry` the same
  way for their own scenarios.

**Not yet wired into a real "load the standard library" entry point** — a permanent, production
version of the register-meta-kernel/meta.tn1/core.tn1 sequence (and where it should live) is still
open.

**`TsonCompiledSchemaLoader`/`DefaultTsonCompiledSchemaLoader`** (`tson-parser/src/main/java/io/ltr8/tson/parser/resolver/{TsonCompiledSchemaLoader,DefaultTsonCompiledSchemaLoader,TsonSchemaSource}.java`)
— `TsonSchemaResolver` holds a `TsonCompiledSchemaLoader`, not a bare registry. The reason a plain
registry reference isn't enough: resolving *meta-kernel's own* document means resolving *its own*
`!!meta`, which names itself (Part 2 §1.5's "one deliberate circularity"). A "look it up, throw if
missing" registry has no way to close that loop on its own — it would need meta-kernel already
registered before it could ever register meta-kernel. `TsonCompiledSchemaLoader.load(String uri)` is
the fix: given any schema's URI, it returns a *compiled meta-schema* (`TsonCompiledMetaSchema`),
fetching/resolving/registering/compiling on demand rather than requiring everything to pre-exist.

**Renamed from `SchemaCoordinator`/`DefaultSchemaCoordinator` (same-day follow-up, on the user's own
explicit direction, after independently confirming the rename target from an earlier analysis:
"Yes, rename to TsonCompiledSchemaLoader").** The old name didn't say what the interface actually
returns (a compiled schema, given a URI) or read like anything else in this codebase's own
`*Loader`/`*Source` vocabulary (`TsonSchemaLoader`, `TsonSchemaSource`). Every field/parameter/local
previously named `coordinator` is now `loader`, matching `TsonSchemaLoader`'s own established
variable-naming convention throughout this codebase.

**The method itself, `resolve(String uri)`, was renamed to `load(String uri)` in a further same-day
follow-up (the user's own direct edit)** -- once the interface itself was `*Loader`-named, `resolve`
was the one thing left that didn't match `TsonSchemaLoader.load(String canonicalIdentity)`'s own
sibling shape.

- **`DefaultTsonCompiledSchemaLoader.load(uri)`** — three cases, in order: (1) already compiled?
  `registry.get(uri)` — a plain cache hit. (2) meta-kernel's own well-known identity
  (`BundledSchemaSource.META_KERNEL_ID` — moved here from `DefaultTsonCompiledSchemaLoader` itself
  2026-07-26, so `BundledSchemaSource`, not the loader, owns "what URI does each of this
  library's own bundled schemas live at")? Resolved via `MetaKernelBootstrapResolver.getMetaKernelSchema()`,
  linked one-off via `TsonSchemaLinker#linkBootstrap` (no registry involved at all), then compiled
  directly — *never* through this same loader's own
  generic path in case (3), which would recurse forever (that path builds a `TsonSchemaResolver(this)`
  and resolves a document via `resolveSchema`, which itself calls back into `resolve` for *that*
  document's own `!!meta` target — fine for any real schema, wrong for meta-kernel specifically,
  whose `!!meta` points at itself). Checked *before* case (3) is ever reached, so the loop never
  starts. Compared by exact string, not canonical identity (`CanonicalIdentity` stays
  internal-by-convention to `tson-schema`, same reasoning as `TsonCompiledRegistry`'s own raw-id
  keying) — a real, narrower guarantee, not an oversight. (3) otherwise, fetch `uri`'s own source
  text via this loader's own `TsonSchemaSource`, parse it, resolve it via a fresh
  `TsonSchemaResolver(this)` (so *that* document's own `!!meta`/`!!import` targets resolve the same way,
  recursively, cache-then-bootstrap-then-fetch all the way down), then look up its own already-cached
  `governingMeta` (a second `load(document.meta())` call — a cache hit by this point, not a second
  compile) and register+compile via `TsonCompiledRegistry.register(resolved, governingMeta)` —
  *this* result genuinely is cached, so the *next* request for the same non-bootstrap `uri` is a
  cache hit.
  **Case (2) is deliberately the one exception — never cached, on the user's own explicit direction**:
  the one-off linked-and-compiled meta-kernel reader is never passed to `TsonCompiledRegistry.register`
  — so every call for `META_KERNEL_ID` that isn't already a cache hit re-bootstraps, re-links, and
  re-compiles from scratch, every time. (The linking itself is genuine, not skipped — the returned
  reader has the full, materialized entry set, same as a registered meta-kernel would.) The
  *permanent*, shared registry entry for meta-kernel is meant to come from a separate, deliberate
  "load it and register it" step, done once, elsewhere — not implicitly, silently, the first time
  anything happens to ask for meta-kernel's own `!!meta`/`!!import` target. **One real, load-bearing
  consequence remains**: skipping `TsonCompiledRegistry.register` means meta-kernel is never persisted
  under its own identity in the *shared* `TsonSchemaRegistry`, so any *other* schema that `!!import`s
  meta-kernel (every real one does) still fails its own registration with `"!!import '...' is not
  registered"` unless meta-kernel has been registered *separately* first — `TsonSchemaLinker`'s own
  import-merging (run inside `TsonSchemaRegistry.register`) resolves an import via `TsonSchemaRegistry`'s own
  registered-only `TsonSchemaLoader`, which knows nothing about this loader or its one-off bootstrap
  case. In practice, a caller resolving anything beyond meta-kernel itself must register meta-kernel
  explicitly first — resolved *ordinarily* via `TsonSchemaResolver.resolveSchema` against a loader whose
  own bootstrap branch supplies the structure namespace (never the raw/one-off bootstrap form
  directly — `TsonSchemaRegistry#register` refuses any self-referential schema with `bootstrap() == true`,
  see "Schema registry" above) — before asking this loader for anything that transitively imports
  it.
- **`TsonSchemaSource`** — the pluggable fetch hook, and the natural home for the policy the user asked
  for explicitly: "we can control whitelists or blacklists for resolution... we don't allow HTTP
  requests and just load from disk, or only HTTP requests to certain hosts." `TsonSchemaSource
  .registeredOnly()` is the default (mirrors `TsonSchemaRegistry`'s own no-arg-constructor default and
  `TsonSchemaLoader`'s own precedent) — nothing is ever fetched from anywhere unless a caller opts in.
  `TsonBundledSchemas::fetch` (below -- `tson-schema`, not implementing this interface directly since
  that module has no dependency on `tson-parser`, but matching its single-method shape exactly) is
  the one real fetch capability wired up so far; a general disk/HTTP-backed `TsonSchemaSource` (with
  whatever whitelist/blacklist policy) is deliberately not built yet.
- **`TsonSchemaResolver(TsonCompiledSchemaLoader)`** replaces the earlier `TsonSchemaResolver(TsonCompiledRegistry)`
  constructor. `loader.load(document.meta())` (inlined directly into `resolveSchema`, not a separate
  method) returns a `TsonCompiledMetaSchema` (not an `Optional`) and *throws* if it can't be
  resolved — with a real loader behind it, "not available" is a genuine, nameable failure (the
  loader is supposed to make it available, fetching/bootstrapping as needed), not a normal "maybe
  try again" outcome the way a bare registry miss used to be.

**`resolveSchema(SchemaDocument)` validates `!!id`/`!!meta`/`!!import` and derives `structureNamespace`
from the loader, when this resolver has one** — previously this method always resolved against
an empty structure namespace, regardless of what `!!meta` said, and never looked at `!!id` at all.
Now, with a `TsonCompiledSchemaLoader`, up front, before any declaration is resolved: (1) `document.id()`
must be present — required by policy for a publishable schema (§2.2.1); (2) that `!!id` must be a
well-formed canonical-identity candidate, via `TsonSchemaRegistry.validateIdentity(String)` (a thin,
one-line public wrapper around `CanonicalIdentity.of`, so a caller outside `tson-schema` never has to
reach into the internal-by-convention `registry` package directly just to run this one check); (3)
every `!!import` URI is validated the same way; (4) `document.meta()` is resolved via
`loader.load(...)` — fetched/bootstrapped/compiled by the loader if it wasn't already
available — and its entries become the structure namespace. (1) throws `IllegalStateException`; (2)/
(3) throw `TsonSchemaValidationException` (the same exception `TsonSchemaRegistry.register` itself would
eventually throw for the same reason, surfaced earlier); (4) throws whatever the loader itself
throws. With no loader (the no-arg constructor), behavior is byte-for-byte unchanged.

**`!!import` is genuinely merged into the type-name namespace, not just validated** — unlike the
structure namespace (`!!meta`, consulted only for constructor-application targets), an import's own
entries feed the *type-name* namespace — the same `resolved` map `resolveComposition`/
`resolveRefinement`/`resolveAtomRefinement` look a supertype/refinement-source straight up in, with
no fallback at all (confirmed by reading those methods directly: each does exactly one
`resolved.get(name)`). So imports are genuinely required *during resolution itself*, not only at
`TsonSchemaLinker`'s later, separate registration-time merge/collision-check pass — meta.tn1's own
`date_type => ~atom & atom_specification & {...}`, composing with two meta-kernel entries it only has
via its own `!!import`, would fail to resolve at all without them. `resolveSchema` resolves each import
via the same `TsonCompiledSchemaLoader`, merging its entries into a working namespace *before* any local
declaration is resolved (`mergeImports`, a private helper), generalized to any loader-aware
resolver and any number of imports. Collision handling mirrors `TsonSchemaLinker.mergeImports`'s own
established rule exactly: a name declared by more than one import, or by an import *and* a local
declaration, throws `TsonSchemaValidationException` with the same wording, checked at the earliest point
the collision becomes knowable. **Merged entries keep their home namespace** (same principle as
`TsonSchemaLinker`'s own note on this) — copied in exactly as their own schema resolved them, never
re-resolved against the importer. **The result's own `entries()` is local-only** — imported entries
are visible *during* resolution but never appear in what this method itself returns; the merged whole
is what `TsonSchemaRegistry.register` produces later, same as always.

Verified in `TsonSchemaResolverCompiledMetaSchemaTest` — small hand-built documents cover: a composition
(`my_type => unit & {}`, meta-kernel imported) proving entries are genuinely reachable via the
type-name namespace, not merely URI-validated (a bare reference wouldn't have proven this: §8.3 bare
references carry an unverified name through regardless of whether it exists anywhere, composition's
`resolved.get(name)` lookup is what actually needs the merge to have happened); both collision modes;
id/import validation failures; and `!!meta` resolution failures. `DefaultTsonCompiledSchemaLoader`'s own
bootstrap behavior gets its own dedicated cases: resolving meta-kernel's own well-known identity from
a completely empty registry completes (rather than recursing forever) and produces a genuinely usable,
fully linked 58-entry compiled reader; a second request for the same identity returns a genuinely
*different*, freshly re-bootstrapped instance, confirmed never registered into either
`TsonCompiledRegistry` or its own `TsonSchemaRegistry`; a non-bootstrap URI with the default `TsonSchemaSource`
throws clearly; and a custom `TsonSchemaSource` handing back meta.tn1's own real bundled source text —
after meta-kernel is registered *explicitly* first — resolves it end to end through the fully generic
path (fetch → parse → resolve → register → compile). `TsonSchemaRegistryTest` covers `validateIdentity`
directly too (accepts a well-formed candidate silently; rejects no-scheme and carries-a-port cases).

**A real, named layering exception, not an oversight.** Every other note about these packages
describes `compiler` sitting *on top of* `resolver`'s own resolution. `TsonCompiledSchemaLoader`/
`DefaultTsonCompiledSchemaLoader` (in `resolver`, alongside `TsonSchemaResolver`) reach the opposite
direction, importing `TsonCompiledSchema`/`TsonCompiledMetaSchema`/`TsonSchemaCompiler`/
`ValueReaderFactoryRegistry` from `compiler` and `TsonCompiledRegistry` from `config` (see "Configuration
package" below) — the one place in `resolver` that does either. Not a cycle (nothing in `compiler`'s
own *main* code imports back from `resolver`, and `config` sits above both), and all three packages
are in the same module regardless (no Gradle/JPMS boundary to violate), but a deliberate exception to
the general framing, made because bootstrapping/fetching/compiling a governing schema is exactly the
one place lower-layer resolution genuinely needs the higher layer's own compiled output, not just its
resolved one. `TsonCompiledMetaSchema` itself carries a `schema()` accessor for this (its own resolved
`TsonSchema`) — mirroring `TsonCompiledSchema.schema()`'s own precedent.

### Configuration package (`tson-parser/src/main/java/io/ltr8/tson/parser/config/`)

Holds the classes that configure/wire together a working compiled-reader environment, as distinct
from `compiler`'s own eager-compile *mechanics* -- `TsonCompiledRegistry` (orchestration:
link+register+compile a schema against a governing meta), `SchemaMetaNameBinder` (the
object-binding-mode naming convention, schema type name → `io.ltr8.tson.schema.meta` class), and
`ValueReaderFactoryResolver` (the small dispatch interface `ValueReaderFactoryRegistry` implements).
Moved out of `compiler` on the user's own observation that they "set out the configuration of the
library" rather than being compiler mechanics -- confirmed before moving anything: none of the three
touches a package-private compiler class directly, so the move needed zero forced visibility changes
elsewhere. `ValueReaderFactoryRegistry` itself deliberately **stayed** in `compiler`, despite reading
the same way at first glance -- it's the literal wiring table binding constructor names to concrete
reader implementations (`AtomValueReader`, `BooleanReader`, `ChoiceReader`, `VariantBindReader`,
`VariantSchemaReader`, `VoidReader`, `ErrorReader`), every one of them deliberately package-private
per this file's own "Naming convention" note above; moving the registry out would have forced all of
them public just so it could keep referencing them, a real, unwanted expansion of internal surface,
not a free move. (An earlier version of `Tson`'s own builder briefly lived here too, the same day,
before moving one step further out to the `tson` module -- see "Front door module" below.)

**`BundledSchemaSource` briefly joined this package too, moved from `resolver`, before being deleted
outright the same week.** It moved here once its only real in-`tson-parser` consumer
(`MetaKernelBootstrapResolver.getMetaKernelSchema`) was confirmed to be its sole caller; then, once
`TsonBundledSchemas` (`tson-schema`) already held the one canonical copy of the three bundled
schemas' own identities, the user pointed out the class itself was "exactly the same thing sitting in
config of the wrong package" — its `fetch` method and the bundled `.tn` resource files moved into
`TsonBundledSchemas` too, and `BundledSchemaSource` was deleted, not just relocated again. See
"Bundled schema documents" below for the full reasoning, including why `MetaKernelBootstrapResolver
.getMetaKernelSchema()` itself deliberately stayed zero-argument throughout all of this rather than
accepting an injected `TsonSchemaSource`.

**`TsonAtomContext` joined this package too, moved from `base`** (2026-07-29, alongside the JPMS
lockdown pass -- see "Module system (JPMS)" near the end of this file) -- the built-in-vocabulary
atom registrations (`UUID`/`byte[]`/`LocalDate`/`OffsetTime`/`OffsetDateTime`/`URI`/`Inet4Address`/
`Inet6Address`) every `DataBindContext` consumer in this library needs, shared by `mapper
.TsonMapperContext` and this package's own `SchemaMetaNameBinder`. It was `base`'s only genuine
external (cross-module) caller -- the rest of `base` (`BaseTypeResolver`, `NumberGrammar`, ...) is §4
base-type-resolution machinery nothing outside `tson-parser` itself ever references -- and `config`
is where "how a caller configures a working environment" already lives, so this was the same kind of
move `BundledSchemaSource`'s own relocation here had been, at the time. `mapper` reaching into
`config` for it is a new, harmless dependency edge (`config` has no dependency back on `mapper`, so no
cycle).

### Front door module (`tson/src/main/java/io/ltr8/tson/`)

A small module sitting *on top of* `tson-parser` (and, transitively via `api` dependencies,
`tson-schema`/`tson-bind`), the way Retrofit sits on OkHttp or Apache HttpClient5 sits on HttpCore5
-- `tson-parser` itself stays as-is (superseding an earlier `tson-parser` → `tson-core` rename idea
discussed and dropped the same day: simpler to add a small module on top than rename the engine
underneath it). `tson-parser`/`tson-schema`/`tson-bind` are declared `api`, not `implementation`, in
`tson/build.gradle.kts` specifically so a caller depending on just `tson` still sees the real classes
underneath on their own compile classpath (`TsonCompiledMetaSchema`, `TsonLinkedSchema`,
`TsonMapperReader`, `DataBindContext`, ...) -- confirmed by compiling against it, not assumed:
`ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext())` inside `TsonConfig`
needs `io.ltr8.bind.DataBindContext` visible even though it never appears in this module's own public
signatures, which is exactly the case `api` (rather than `implementation`) exists for.

Holds two classes so far, a real object plus its builder -- not a bag of static factories:

- **`Tson`** -- the actual front door, a real, immutable, instance-based object: `resolve(schemaText)`,
  `compile(linked, mode)`/`compile(schemaText, mode)`, `mapperReader()`, `mapperWriter()`,
  `dataBindContext()`, plus `schemaRegistry()`/`compiledRegistry()`/`loader()` for a caller that needs
  to reach past the front door into the underlying `tson-parser`/`tson-schema` machinery directly.
  Constructed only via `Tson.builder()` (returning a fresh `TsonConfig`), never directly -- its own
  constructor is package-private. `resolve`/`compile` replace the `TsonSchemaRegistry`/
  `TsonCompiledRegistry`/`DefaultTsonCompiledSchemaLoader` wiring `TinySchemaImportsCoreTn1Test`/
  `CoreSchemaImportTest` (and `tson-cli`'s own, now-deleted internal `StandardLibrary` helper) used to
  hand-roll -- `resolve` parses/resolves/links/registers a caller-supplied schema governed by
  meta-kernel/meta.tn1/core.tn1, `compile` then compiles an already-linked (or, via the convenience
  overload, not-yet-resolved) schema in a caller-chosen mode. **`resolve` takes no mode parameter at
  all -- only `compile` does**, reflecting a real, non-obvious constraint found while building this:
  resolving an `Instance`/`AtomRefinement` declaration (`DefinitionResolver.bindAtomInstance`) always
  needs a real, object-binding-mode governing-meta reader, regardless of what mode the *final* compiled
  schema wants, since it casts its governing meta-schema's own reader output straight to
  `schema.meta.Top` -- a DOM reader's plain `Map`/`List` output fails that cast outright. Only
  *compiling* an already-resolved, already-linked schema (`TsonCompiledMetaSchema.bootstrap`) is free
  to pick a different mode, since it just dispatches an already-built `Top` body tree to a factory by
  constructor name and never re-runs resolution. `mapperReader()`/`mapperWriter()` are thin instance
  factories (`new TsonMapperReader(dataBindContext)`/`new TsonMapperWriter(dataBindContext)`) bound to
  this `Tson`'s own configured `DataBindContext`, not the static, context-less convenience
  constructors `TsonMapperReader`/`TsonMapperWriter` also still expose for a caller who wants Class 1
  reading with no schema/front-door involvement at all. `tson-cli` was refactored to build on `Tson`
  directly (`ValidateCommand`/`CompileCommand`/`DiagnosticsSchema`), making it real, working proof the
  front door is usable, not just a design on paper.
- **`TsonConfig`** -- `Tson`'s own builder, reached only via `Tson.builder()` (its own constructor is
  package-private too). The one configurable option so far is `dataBindContext(DataBindContext)`
  (defaulting to `TsonAtomContext.defaultContext()`, the same default `TsonMapperReader`/
  `TsonMapperWriter`'s own no-arg constructors use) -- purely what the *built* `Tson`'s own
  `mapperReader()`/`mapperWriter()` bind against; it never affects the separate, fixed,
  object-binding-mode context `build()` always uses internally to resolve meta-kernel/meta.tn1/
  core.tn1 themselves, which isn't configurable at all. `build()` bootstraps meta-kernel → meta.tn1 →
  core.tn1 in one call and returns the assembled `Tson`. Split out from an earlier, single
  `TsonStandardLibrary` class that mixed both jobs (builder config *and* the real, useful post-build
  operations) once it became awkward for `mapperReader()`/`mapperWriter()` to be non-static, bound to
  a real `DataBindContext`, while still living on what was nominally "just a builder" -- `TsonConfig`
  now only ever configures and builds; every operation a caller actually performs afterward
  (`resolve`/`compile`/`mapperReader`/`mapperWriter`) lives on `Tson` itself.

**`TsonMapperReader`/`TsonMapperWriter` deliberately did *not* move here, and can't yet.**
`tson-parser`'s own `DefinitionResolver` has a real, current dependency on `TsonMapperWriter`
(`private final TsonMapperWriter writer`, used by `mergeWithSource` to re-serialize an atom
refinement's source during chained-refinement merging) -- moving them to a module that depends *on*
`tson-parser` would recreate the exact module cycle that caused `tson-mapper` to be merged *into*
`tson-parser` in the first place, historically (see "Mapper" elsewhere in this file). `BACKLOG.md`'s
own "Atom-refinement constraint validation" section tracks removing that dependency (the merge
should validate that a refinement genuinely narrows its source, e.g. via a per-constraint-type
`constraintsCheck(A, B)`, rather than blindly overriding fields through a generic
serialize-then-merge round trip) -- revisit moving `TsonMapperReader`/`TsonMapperWriter` here once
it's gone, noted directly on `Tson`'s own class Javadoc too so it isn't lost.

### Bundled schema documents (`tson-schema/src/main/java/io/ltr8/tson/schema/TsonBundledSchemas.java`)

The real, published identities of this library's own three bundled schema documents — meta-kernel,
meta, core — **and** their raw source text, straight off `tson-schema`'s own classpath (the same
resources `tson-schema/build.gradle.kts`'s own `processResources` task copies in from `spec/m/`,
mirroring `MetaKernelBootstrapResolver`'s identical one-file-to-keep-in-sync reasoning). `META_KERNEL_ID`/
`META_ID`/`CORE_ID` are each schema's own `!!id`; `fetch(uri)` returns the matching document's own raw
text.

**Both halves live in `tson-schema` now, in one class — not split across two modules the way they
used to be.** `TsonBundledSchemas` originally held only the three identities (2026-07-29, moved there
from two previous, split homes — `META_KERNEL_ID` on `tson-schema`'s own `TsonSchemaLinker`, needed
there for `isMetaKernelGoverned`'s constructor-eligibility check, with `tson-parser`'s own
`BundledSchemaSource` defining its own copy in terms of that one; `META_ID`/`CORE_ID` had no canonical
source at all, only `BundledSchemaSource`'s own literal copies). The same day, on the user's own
further, explicit direction ("I realise that the schemas should be bundled there and BundledSchemaSource
is exactly the same thing sitting in config of the wrong package. So move the fetch method directly
into TsonBundledSchemas and bundle the schemas in tson-schema. Then BundledSchemaSource can be
deleted"), `fetch` and the bundled `.tn` resource files themselves moved here too, and
`BundledSchemaSource` (`tson-parser.config`) was deleted outright — there was nothing left for a
separate `tson-parser`-side class to do once both halves of "what these documents are" and "where
their content lives" could sit in the one module that's the single canonical source for both a
`tson-parser`-side consumer and `tson-schema`'s own `TsonSchemaLinker`, since `tson-schema` has no
dependency on `tson-parser` (only the reverse).

**`fetch` deliberately doesn't implement `tson-parser`'s own `TsonSchemaSource` interface** — that
would require a dependency `tson-schema` doesn't have — **but a `tson-parser`-side caller needing a
real `TsonSchemaSource` passes the method reference `TsonBundledSchemas::fetch` directly**, with no
adapter class on either side: `TsonSchemaSource` is `@FunctionalInterface`, a single `String
fetch(String uri)` method, exactly `TsonBundledSchemas.fetch`'s own shape, so Java's own method-
reference conversion satisfies it for free. Every `tson-parser`/`tson` call site that used to pass
`BundledSchemaSource.INSTANCE` as a `TsonSchemaSource` (e.g.
`new DefaultTsonCompiledSchemaLoader(registry, BundledSchemaSource.INSTANCE)`) now passes
`TsonBundledSchemas::fetch` instead; every direct-fetch call site (`BundledSchemaSource.INSTANCE
.fetch(...)`) now calls the static `TsonBundledSchemas.fetch(...)` directly.

**Verified the resources genuinely moved, not just the Java code** — `unzip -l` on the built jars
confirms `meta-kernel.tn`/`meta.tn`/`core.tn` are present in `tson-schema`'s own jar and absent from
`tson-parser`'s.

**Replaces two now-deleted, standalone classes, `MetaTn1Parser`/`CoreTn1Parser`** — each used to
hand-roll its own fetch-parse-resolve-register-compile sequence for one schema specifically; the
general version of that sequence is exactly what `DefaultTsonCompiledSchemaLoader#load(String)`'s own
generic branch already does for *any* URI, given a `TsonSchemaSource` that knows how to fetch it.
`TsonBundledSchemas` is that source for all three well-known identities — nothing more.

**A caller wanting a working environment now uses `Tson` directly** (see "Front door module" above)
rather than assembling `TsonSchemaRegistry`/`TsonCompiledRegistry`/`DefaultTsonCompiledSchemaLoader`
by hand — `TsonBundledSchemas` is still exactly what powers it underneath, just no longer something
a caller needs to reach for directly:

```java
Tson tson = Tson.builder().build(); // meta-kernel + meta.tn + core.tn
TsonCompiledMetaSchema compiled = tson.compile(schemaText, ValueReaderFactoryRegistry.dom());
```

The hand-assembled sequence above still exists — `TsonConfig#build`'s own implementation is exactly
it — but a caller no longer needs to write it out themselves.

**That `META_KERNEL_ID` entry in `TsonBundledSchemas`'s own internal resource table is still never
actually reached through `DefaultTsonCompiledSchemaLoader#load`**, though — that method special-cases
it and resolves it via `MetaKernelBootstrapResolver#getMetaKernelSchema()` directly, before
`TsonBundledSchemas.fetch` is ever consulted (see "Compiled schema registry" above for why:
meta-kernel's `!!meta` names itself, and falling through to the generic
fetch-then-`TsonSchemaResolver(this)` path would recurse forever). It's included here anyway so this
class is a complete, uniform "fetch any of this library's own bundled schema documents" utility on its
own terms, and safe for any *other* `TsonCompiledSchemaLoader` implementation that doesn't
special-case meta-kernel the way `DefaultTsonCompiledSchemaLoader` does.

### Object-binding mode (`compiler/{RecordBindReader,ArrayBindReader,MapBindReader,TupleBindReader,VariantBindReader,VariantSchemaReader}.java`, `config/SchemaMetaNameBinder.java` + `tson-bind`'s own `DataNameBinder`/`DataParameterizedType`)

A second output mode for the compiled reader, alongside DOM mode's plain `Map<String, Object>` --
`RecordBindReader` produces a real, bound `schema.meta` Java object (a real `IntegerType`, not a map
of its field names). This is what `TsonSchemaResolver.bindAtomInstance` binds through (see "Schema
resolution" above), and what every real meta-kernel/meta.tn1/core.tn1 self-compilation exercise
described below and in "Meta-kernel bootstrap" ultimately depends on.

**Lives directly in `compiler`, symmetric with DOM mode, not in a separate package (2026-07-28, once
the whole `compiler` package was rewritten from its earlier `RecordParser`/`ArrayParser`/.../
`TsonParserFactoryRegistry` shape).** The earlier design (a standalone `io.ltr8.tson.parser.binder`
package holding `TsonObjectBinding`/`TsonObjectBinder`/`TsonBoundSchema`/`ObjectRecordShapeFactory`,
depending one-way on `compiler`) was deleted outright, not migrated -- every `*Parser` class it
depended on (`RecordParser`, `AtomTypeParser`, `TsonParserFactoryRegistry`, ...) was itself replaced.
Each structural kind now splits into a shared `*AbstractReader` base (`RecordAbstractReader`,
`ArrayAbstractReader`, `MapAbstractReader`, `TupleAbstractReader` -- the compiled-field list, absent/
default handling, unwrapping the incoming `DataValue`) plus two concrete subclasses, `*DomReader`
(plain `Map`/`List`) and `*BindReader` (real bound Java objects) -- the DOM/bind split that used to
live *between* two separate packages (`compiler` vs `binder`) now lives *within* each reader family
instead, as sibling classes sharing one base. `ValueReaderFactoryRegistry.dom()`/`.bind(DataBindContext)`
build the two complete factory tables (see "Class 2 compilation" above); only the `record`/`enum`
constructor slots (and, transitively, whatever a record's own array/map/tuple-typed fields resolve
to) actually differ per mode -- every atom-family factory is shared verbatim between both.

**`SchemaMetaNameBinder`** (now in `config`, alongside the other configuration/wiring classes -- see
"Configuration package" above; no longer implementing an interface of its own) is the schema-type-name
→ Java-`Class` binding object-binding mode needs, backed by
`tson-bind`'s own `io.ltr8.bind.DataNameBinder` (`resolve(String) -> Class<?>`, throwing
`DataBindException`). **Deliberately a plain name → `Class.forName` lookup with a caller-supplied
naming convention, not a scan of `Top`'s own sealed union** -- there is no reflection API to
enumerate "every class in a package," and a union-scan approach is a real, discovered dead end: it
made `integer_type` itself uncompilable in object mode at all (its own `size: integer_size?` field
eagerly resolves `integer_size` at compile time regardless of whether any given value populates
`size`, and `IntegerSize` was never a `Top` member) -- a plain name lookup has no such restriction,
since it resolves by name, not by hierarchy membership. Its own convention: fixed namespace
`io.ltr8.tson.schema.meta`, a snake_case-to-PascalCase mangle, with one confirmed alias table
(`record`/`array`/`map`/`tuple`/`choice`/`enum` → their own `*Body` class, since meta-kernel's own
description of a composite constructor's shape is structurally identical to the class representing a
*bound instance* of that constructor, but that class's own `@Typename` is the bare name, not
`_body`-suffixed; `set`/`array_min`/`array_max`/`array_ranged`/`vector` → `ArrayBody`, since
refinement never adds or removes fields, so their own field set is identical to `array`'s).
`SchemaMetaNameBinder.defaultContext()` is the one place this actually gets wired into a real
`DataBindContext` -- `TsonAtomContext.registerDefaults` (the same built-in-vocabulary atom
registrations `TsonMapperContext.defaultContext()` also applies -- `UUID`/`byte[]`/`LocalDate`/
`OffsetTime`/`OffsetDateTime`/`URI`/`Inet4Address`/`Inet6Address`) applied to
`DataBindContext.builder().nameBinder(SchemaMetaNameBinder.INSTANCE).build()`.

**`RecordBindReader`** -- built once `super(name, body, resolver)` (`RecordAbstractReader`'s own
constructor) resolves each field's schema-driven child reader by name, `RecordBindReader`'s own
constructor looks up each field's real `DataClassField` from `descriptor.fields()` (`descriptor`
itself resolved by `RecordBindReader.Factory` via `context.getDescriptor(name)`), narrows every
precomputed default/fixed value to that field's target type, and -- **new, 2026-07-28** -- rebuilds
any field whose target `DataClassField.dataClass()` is itself a `DataClassArray`/`DataClassMap`
against that real target directly (`rebindContainerIfNeeded`), discarding whatever the schema-driven
first pass built for that field. This closes a real gap: a *synthesized*, materialized array/map
entry (e.g. `enum`'s own `members: set<token>` field, materialized by `TsonSchemaLinker` into a
hash-suffixed name like `set_token_9a29ae06`) has no Java class registered under that name and never
will, since nothing legitimately names a schema-internal linking artifact -- `ArrayBindReader.Factory`/
`MapBindReader.Factory` resolving a class purely by the *child entry's own* name can never work for
it. The consuming *field* always knows its own real target type independently, though (reflection on
the record's own real generic field type, e.g. `List<String>`, already resolved a genuine
`DataClassArray` when `descriptor` itself was built) -- so `RecordBindReader` rebuilds the array/map
reader using that, reusing the schema-driven build's own `ArrayBody`/`MapBody`/element-reader (`body`/
`elementParser`, read directly off the discarded reader -- package-private fields, same package) and
swapping in only the target Java container type. Only `Array`/`Map` are covered this way today --
`TupleBindReader` has no equivalent rebind, and no equivalent problem yet either, since no real
fixture materializes a synthesized tuple.

**`RecordBindReader.Factory` splits a subtypes-bearing record three ways, not two.** When
`typeDefinition.subtypes()` is empty, `name` resolves as an ordinary record, no dispatch wrapper at
all. When it's non-empty, what `name` itself resolves to decides the rest: a `DataClassUnion` is a
pure marker root (`top`/`atom`/`product`/`sum` -- an empty record body with a huge subtype list,
bound to a Java sealed interface with nothing instantiable of its own), where `ownParser` is a
stand-in that unconditionally throws, since there's no real Java object "just `top`" could
construct. A `DataClassRecord` instead means the declaration is directly instantiable *and* composed
on top of -- `text_type` is the one real fixture case (`uri_type`/`regex_type`/`email_type` all
compose on top of it, but `text_type` itself is a plain, real `TextType`) -- so `ownParser` is a real,
reachable `RecordBindReader` for the declaration's own body. Either way, dispatch to a named subtype
is bounded by the schema's own `subtypes()` list, not by any Java type, via `VariantSchemaReader`
(renamed from `VariantDomReader` once its own dispatch logic -- schema-name-validated, never bounded
by a Java union -- turned out to be exactly what both cases need, not just DOM mode's own
"unconditionally, ownParser always reachable" one). An explicit `!uri_type {...}` value at a
`text_type`-typed position now correctly dispatches to `uri_type`'s own compiled reader, producing a
real `UriType`, not a `TextType` -- confirmed directly, not just reasoned about, by
`RecordBindReaderTest.constructorFlaggedTypeWithRealSubtypesDispatchesToTheNamedSubtype` (a
hand-built schema mirroring `text_type`/`email_type`'s real shape, since `uri_type`/`regex_type`
themselves have a separate, pre-existing binding gap of their own -- their RFC-citation field is
nested inside `specification: AtomSpecification` rather than flat, so it doesn't fill from a
schema-composed default the way `email_type`'s own flat `spec` field does; unrelated to subtype
dispatch itself, not fixed by this change).

**`ArrayBindReader.Factory`/`MapBindReader.Factory` always target `List`/`Map` (never a Java array
or a more specific collection), but resolve the schema's own element/key/value type name to a real
bound Java class where one exists** (`context.getDescriptor(schemaTypeName)`), falling back to
`String` only when it doesn't (confirmed against the real fixture: `ipv4_type`'s own
`[value]`-sugared field is the real case that hits the fallback -- `schema.meta` has no `Value`
class). `token` is a known, accepted imprecision the *other* direction, found by probing it
directly rather than assumed: it resolves without falling back, but to `schema.meta.Token` (the raw
literal wrapper §5.2/§5.10 field modifiers use), not `String`, `token`'s own actual natural host
type -- left as-is rather than special-cased, since (below) the declared element type here never
affects what a real read decodes anyway. `DataParameterizedType` (moved into `tson-bind`, `io.ltr8
.bind`, as a small, generic, reusable `ParameterizedType` builder -- `tson-bind`'s own array/map
binders need a genuine `ParameterizedType` to recover an element/key/value type past erasure, and
neither factory has a real generically-typed Java field to reflect one off of) is what builds the
target `List<X>`/`Map<K, V>` by hand -- covered on its own terms in `tson-bind`'s
`DataParameterizedTypeTest` (accessors, defensive-copy of `getActualTypeArguments()`, nesting,
`equals`/`hashCode` including agreement with a genuine JDK-reflected `ParameterizedType`, and two
`DataBindContext.getDescriptor` integration cases proving it actually drives `DataClassArray`/
`DataClassMap`'s own element/key/value resolution, not just that it satisfies the interface). **Still
a known, accepted fragility, not a full fix**: the
declared element/key/value type built here -- real, `String` fallback, or `token`'s own mismatch --
is never actually consulted by either reader's own `read()` (only `constructor()`/`iterator()`/
`put()` are; decoded values come from the schema-level element/key/value reader independently), and
`List.add(Object)`/`Map.put(Object,Object)` accept any value at runtime regardless of declared
generics (type erasure) -- so correctness today quietly depends on `tson-bind` never adding runtime
type-checking against a `DataClassArray`/`DataClassMap`'s own declared element type. A caller reading
through a record field is unaffected by any of this either way, real or synthesized element type,
since `RecordBindReader`'s own rebind step (above) discards this build entirely and rebuilds against
that field's own real target. A more complete fix would mean `RecordBindReader`'s rebind step
becoming the *only* path that ever builds a genuine array/map reader in bind mode -- a bigger change
than this pass attempts, left as an open direction.

**`AtomValueReader.ENUM_OBJECT_MODE`/`BooleanReader`** -- object mode's own `"enum"` factory
dispatches name-keyed, not shape-keyed: every enum instance *except* the schema's own `boolean`
entry (`product_access_type`, `field_state`, `binary_encoding`, ...) reads its member token's own
text as a plain `String` via the ordinary `atom.EnumParser` (through `AtomValueReader.ENUM`) --
exactly right for an arbitrary, user-defined enum label. `boolean` specifically (`boolean => !enum
[true false]`) routes to `BooleanReader` instead, producing a real Java `Boolean` -- its two members
are meant to *be* the two Java boolean values, not the strings `"true"`/`"false"`. DOM mode is
deliberately untouched (`AtomValueReader.ENUM` keeps producing `String` for `boolean` there too).

**No standalone "eagerly report every unresolvable entry at once" step exists anymore.** The earlier
design's `TsonObjectBinder.bind` walked every `record`-shaped entry as its own dedicated pre-pass,
batching every binding failure into one report before compilation ever started. That's gone --
binding now happens as a side effect of `TsonSchemaCompiler.compile`'s own eager per-entry walk (via
`RecordBindReader.Factory.create`), and each entry's own build failure is individually caught and
deferred into an `ErrorReader` (see "Class 2 compilation" above) rather than batched with any
sibling failures or surfaced at schema-load time. A caller wanting "does the whole schema bind
cleanly, and what breaks if not" now has to walk `compiled.get(name)` for every entry name itself
(never throws) and separately try reading each one to find out.

Verified in `RecordBindReaderTest` against a mix of the real meta-kernel fixture and small,
self-contained schemas built to isolate one behavior at a time (the narrowing test specifically uses
a hand-built, subtype-free schema, to isolate that behavior from the subtype-dispatch one below):
`text_type` narrows a real `BigInteger` down to `Integer` with a genuine `equals()` match;
`integer_type` itself compiles and reads correctly with `size` left absent in the data
(`IntegerSize.signed` is deliberately left unexercised there rather than proven either way -- a
primitive `boolean` field narrowed through the same generic, reflective binding path, a separate,
still-open question from this test's own actual scope); a hand-built schema mirroring `text_type`/
`email_type`'s real shape proves the own-body path still reads a plain own-type value correctly *and*
an explicit subtype type-ref now dispatches to the named subtype's own real class, not the parent's;
the whole real meta-kernel schema compiles cleanly in bind mode, including the five pure marker roots
(`atom`/`product`/`sum`/`top`/`type_argument`), which get a real compiled reader (via
`VariantBindReader`, dispatching to a `DataClassUnion`) but throw if actually read without an
explicit type-ref, since there's no Java object "just a top" could construct.

### Module system (JPMS)

Every module now has a real `module-info.java` (2026-07-29) — `tson-bind`/`tson-annotation` already
did; `tson-schema`, `tson-parser`, `tson`, and `tson-cli` gained one in this pass, once the "API-surface
pass" above had already trimmed each module's own accidental public surface down to what's genuinely
meant to be consumer-facing. Doing the trim first mattered: `module-info.java` exports are
package-grained, not class-grained, so exporting a package exposes *every* public class in it — trying
this before the surface pass would have forced exporting packages (`compiler` in particular) that still
mixed real API with mid-refactor leftovers, telling you nothing about what should actually be hidden.

**Module names mirror each module's own root exported package** (`io.ltr8.bind`, `io.ltr8.annotation`,
`io.ltr8.tson.schema`, `io.ltr8.tson.parser`, `io.ltr8.tson`, `io.ltr8.tson.cli`), the same convention
`tson-bind`/`tson-annotation` already established.

- **`tson-schema` exports `io.ltr8.tson.schema`/`io.ltr8.tson.schema.meta`, deliberately not
  `io.ltr8.tson.schema.registry`** — the first real enforcement of the "internal-by-convention" split
  that package's own Javadoc already described (see "Schema registry" above) but had no way to actually
  hold to before this. Confirmed genuinely enforced, not just declared: a scratch file added temporarily
  to `tson-parser` importing `io.ltr8.tson.schema.registry.CanonicalIdentity` fails to compile outright
  (`package io.ltr8.tson.schema.registry is not visible ... does not export it`), then was deleted —
  this was a real experiment, not just writing the module-info and assuming it worked. `requires
  io.ltr8.annotation` (not `transitive`) since no `schema`/`schema.meta` public method signature ever
  exposes an `@Typename`/`@Field`/`@Record` annotation type directly, only applies them as declaration
  annotations `tson-bind` reads reflectively later.
- **`tson-schema/build.gradle.kts` needed `implementation(project(":tson-annotation"))` promoted to
  `api`, plus its own `id("java-library")` plugin block** — a real, non-obvious Gradle/JPMS friction
  point, not a design choice: `tson-schema`'s `module-info.java` requires `io.ltr8.annotation` to
  physically exist on the module path for *any* downstream compilation that transitively depends on
  `tson-schema` (`tson-parser`, `tson`), regardless of whether that module's own source ever names an
  `io.ltr8.annotation` type directly — Gradle's `implementation`/`api` split controls compile-classpath
  *type visibility*, a separate concern from whether the module graph can even resolve. `tson:compileJava`
  failed with `module not found: io.ltr8.annotation` before this fix; `requires io.ltr8.annotation` itself
  stayed non-`transitive` in `tson-schema`'s own module-info (the Gradle promotion doesn't change what
  `tson-parser`/`tson` are actually allowed to *read* — module readability is still `tson-schema`-only).
- **`tson-parser` exports every package with a real cross-module caller**
  (`io.ltr8.tson.parser`/`.ast`/`.ast.schema`/`.compiler`/`.config`/`.mapper`/`.resolver`), confirmed
  against actual `tson`/`tson-cli` imports rather than assumed, and leaves `.atom` unexported (nothing
  outside `tson-parser` itself ever references it). `requires transitive io.ltr8.bind` (`DataBindContext`
  appears directly in `TsonMapperReader`'s own public constructor, and `DataBindException` in a
  `throws` clause) and `requires transitive io.ltr8.tson.schema` (`schema`/`schema.meta` types are
  pervasive throughout `resolver`/`compiler`/`config`'s own public signatures) — both `transitive`,
  since a caller of `tson-parser` unavoidably needs to read both directly too.
- **`.lexer` and `.base` both went from "exported by accident" to genuinely internal (2026-07-29,
  same-day follow-up, on the user's own explicit direction).** Both had exactly one thing forcing the
  export, and both got fixed at the source rather than left exported:
  - **`Position` moved out of `.lexer` into the root package** (`io.ltr8.tson.parser`, right alongside
    `TsonParseException`/`TsonUnsupportedDocumentException`, the two classes whose own public
    `position()` accessor was the *only* reason `.lexer` needed exporting at all — confirmed by
    `javac -Xlint:exports` flagging exactly this leak when `.lexer` was first left unexported, per the
    "Module system (JPMS)" section's own original pass). `Lexer`/`Token`/`TokenType`/`LexException`
    (genuine scanner internals, "frozen for the whole series," never referenced outside `tson-parser`
    itself) gained a same-module `import io.ltr8.tson.parser.Position;` in place of the free same-package
    reference they used to have, and `.lexer` was dropped from `module-info.java`'s own `exports` list.
  - **`TsonAtomContext` moved from `.base` into `config`** — it was `.base`'s only genuine external
    caller (`tson`'s own `TsonConfig`, `tson-cli`'s own `DiagnosticsSchema`); `BaseTypeResolver`/
    `NumberGrammar`/`NumberForm`/etc. (§4 base-type-resolution internals) never had one. `config` is
    where it conceptually belongs anyway — "how a caller configures a working environment," the same
    framing `SchemaMetaNameBinder`/`BundledSchemaSource`/`TsonCompiledRegistry` already have — and the
    move creates no cycle (`mapper.TsonMapperContext`, `.base`'s other real consumer, gained a new
    `mapper` → `config` edge, but `config` has no dependency back on `mapper`). `.base` was dropped from
    `module-info.java`'s own `exports` list once this left it with zero external callers.
  - **Both fixes verified the same way the original pass verified `.registry`** — not just "the build is
    green," a scratch file added temporarily to `tson` importing `io.ltr8.tson.parser.lexer.Lexer` and
    `io.ltr8.tson.parser.base.NumberGrammar` failed to compile outright (`package ... is not visible ...
    does not export it`) before being deleted.
- **`ValueReaderResolver` (narrowed package-private in the API-surface pass above) still triggers a
  `javac -Xlint:exports` warning** on `ValueReaderFactory.create`/`TsonCompiledMetaSchema.create`'s own
  signatures ("is not accessible to clients that require this module") — expected, not a regression:
  that parameter type was deliberately made unreachable from outside `compiler`'s own package in the
  API-surface pass, and the warning is `javac` correctly noticing a *public method* still mentions an
  inaccessible type in its signature. No external caller ever legitimately invokes `.create(...)`
  directly (see "Class 2 compilation" above), so left as-is rather than papering over a correct warning.
- **`tson` exports only `io.ltr8.tson`** (its one package), `requires transitive` all three of
  `io.ltr8.bind`/`io.ltr8.tson.parser`/`io.ltr8.tson.schema` — `Tson`'s own public methods return types
  from all three directly (`DataBindContext`, `TsonMapperReader`, `TsonCompiledMetaSchema`,
  `TsonSchemaRegistry`, `TsonLinkedSchema`, ...), so a caller depending on just `tson` needs to read all
  three too, matching the Gradle-level `api` dependencies already in place.
- **`tson-cli` exports nothing** (an application module — nothing depends on it), `requires`
  `io.ltr8.bind`/`io.ltr8.tson`/`io.ltr8.tson.parser` directly (not just transitively through `tson`),
  matching its own `build.gradle.kts`'s existing direct dependencies (needed for DOM mode/custom
  binders alongside the front door, see "Front door module" above).
- **Verified both ways, not just "it compiles"**: a full `./gradlew clean build` across every module
  stays green (compile + the whole test suite, still on the classpath for test source sets, which have
  no `module-info.java` of their own), *and* the real installed CLI binary
  (`tson-cli/build/install/tson-cli/bin/tson-cli validate ...`) still runs correctly end to end — the
  `application` plugin's default distribution launches on the classpath, not the module path, so this
  also confirms module-path enforcement at compile time and the existing classpath-based runtime
  distribution coexist without conflict.
- **Not attempted**: a `jlink` custom runtime image, `opens` directives (nothing reflects into a
  non-public member anywhere in this codebase — `tson-bind`'s own binding only ever touches public
  constructors/methods, see `DefaultRecordBinder.getConstructor`'s own note elsewhere in this file),
  and `Automatic-Module-Name` for a hypothetical future non-modular consumer.

### Conformance suite integration (`ConformanceSuiteTest`)

Separate from `LexerTest`/`TsonDataParserTest` (fine-grained unit tests) is `ConformanceSuiteTest`, which runs
every vector in the sibling [ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite)
repo against the real `Lexer`/`TsonDataParser` as JUnit 5 dynamic tests (one per vector, named
`<layer>/<bucket>/<slug>`). This is a conformance/integration test against an external, spec-derived,
language-agnostic fixture set — it exists to catch drift against the spec, not to pinpoint which internal
rule broke.

It assumes the sibling repo is checked out at `../../ltr8-io-tson-test-suite` relative to this module's
directory (Gradle's and most IDEs' default test working directory) and skips gracefully via
`Assumptions.assumeTrue` — reported as *aborted*, not failed — if it isn't there. CI deliberately doesn't
check the sibling repo out, so this always shows as skipped in CI; that's expected, not a problem to fix.

## Build and test

No system Gradle — always use the wrapper:

```
./gradlew build
./gradlew test
./gradlew test --tests "io.ltr8.tson.parser.lexer.LexerTest"
./gradlew test --tests "io.ltr8.tson.parser.TsonDataParserTest"
./gradlew test --tests "io.ltr8.tson.parser.base.NumberGrammarTest"
./gradlew test --tests "io.ltr8.tson.parser.base.BaseTypeResolverTest"
./gradlew test --tests "io.ltr8.tson.parser.ConformanceSuiteTest"   # skipped unless ../../ltr8-io-tson-test-suite exists
./gradlew test --tests "io.ltr8.tson.parser.lexer.LexerTest.multilineBasicIndentStripping"
./gradlew :tson-schema:test --tests "io.ltr8.tson.schema.TsonSchemaRegistryTest"
./gradlew :tson-schema:test --tests "io.ltr8.tson.schema.TsonSchemaLinkerTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.resolver.MetaKernelSchemaRegistryTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.resolver.MetaKernelBootstrapResolverTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.resolver.MetaSchemaImportTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.compiler.MetaKernelEndToEndTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.compiler.MetaTn1CompiledEndToEndTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.compiler.CompiledSchemaDomReadTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.resolver.TsonSchemaResolverCompiledMetaSchemaTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.mapper.TsonMapperReaderTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.mapper.TsonMapperWriterTest"
```

## Not yet implemented

See `BACKLOG.md` at the repo root for the actively-tracked engineering backlog this section feeds
into, and `STRUCTURED-OUTPUT.md` for the target-use-case plan (LLM structured output validation,
JSON compatibility) — the notes below are the technical detail behind specific items, not a task
list to work through in order.

- Part 2 schema resolution: subtraction, elided field types outside a tightening entry, restating a
  field group in a refinement body, the identity-diagonal FIXED-value invariant, and generic
  type-refs beyond a bare two-argument `map<K, V>` application or a refinement source — see
  `DefinitionResolver`'s own Javadoc (under "Schema resolution" above) for the exact, current boundary
  of what resolves. (Template/instantiation-entry *materialization* itself is now handled — see
  "Schema registry" above — just not per §8.2's precise constructor-vs-template split;
  materialization is uniform. Constructor-application `Instance` and atom-refinement resolution are
  both now generalized — see "Schema resolution" above — not listed here anymore.)
- `boolean => !enum [true false]` used to be a permanent limit of generic binding; no longer is,
  fixed 2026-07-26 — see "Schema resolution" above's status paragraph for what changed and why.
  `duration` no longer fails either (`DurationType.min`/`max` retyped to `Optional<String>`,
  2026-07-24 — see "Schema resolution" above). `complex_type`'s own `component` field binds fine
  (`ComplexType`, added 2026-07-24); `unknown_type` too (`UnknownType`). No real fixture declaration
  remains unresolved in `meta-kernel.tn1`/`meta.tn1`/`core.tn1` (all 48 of core.tn1's own resolve and
  register, `CoreSchemaImportTest`, 2026-07-28 — see "Schema resolution" above's status paragraph).
- The schema-validating data parser (Class 2) — `io.ltr8.tson.parser.compiler`
  (`TsonSchemaCompiler`/`TsonCompiledSchema`/`TsonCompiledMetaSchema`, `ValueReaderFactoryRegistry`,
  `Record`/`Array`/`Map`/`Tuple{DomReader,BindReader}`, `VariantSchemaReader`/`VariantBindReader`,
  `ChoiceReader`, `AtomValueReader` + the vocab-family parsers) — now has dedicated coverage above
  ("Class 2 compilation", "Compiled schema registry", "Bundled schema documents", "Object-binding
  mode"). Known, still-open gaps within it: no `!!schema`-header auto-selection (a caller must
  already know which schema-known position it's reading against -- there's no single "read this
  string, pick the right compiled reader automatically" entry point); five real core.tn1 declarations
  (`unknown`/`email`/`cidr4`/`cidr6`/`mac`, constructed via `unknown_type`/`email_type`/`cidr4_type`/
  `cidr6_type`/`mac_type`) plus a sixth constructor with no core.tn1 declaration at all (`extern`)
  have no compiled-parser factory yet, registered to `ErrorReader` so a schema declaring one still
  compiles -- confirmed exactly (not just asserted) by `CoreSchemaImportTest
  .exactlyTheFiveUndocumentedAtomConstructorsCompileToErrorReaders` (`complex`/`ipv4`/`ipv6` *do*
  now have one, wired to the existing `atom.ComplexParser`/`Ipv4Parser`/`Ipv6Parser`); `uri_type`/
  `regex_type` still don't bind correctly in object-binding mode (their own RFC-citation field is
  nested inside `specification: AtomSpecification` rather than flat, so it never receives a
  schema-composed default the way `email_type`'s own flat `spec` field does -- see "Object-binding
  mode" above; subtype *dispatch* to them is fixed, this is a separate, narrower, still-open gap in
  their own field binding); a permanent standard-library "load meta-kernel/meta.tn1/core.tn1 and
  register them" entry point doesn't exist yet (see "Compiled schema registry" above);
  `REQUIRED_FIXED`/`OPTIONAL_FIXED` value validation,
  `value_param` real parameter substitution, and thread-safety are all still deferred design
  questions; a general disk/HTTP-backed `TsonSchemaSource` (with whitelist/blacklist policy) is
  deliberately not built yet either (see "Bundled schema documents" above).
- §9.1's numeric-literal length limit (SHOULD, default 4096 digits, DoS-hardening) — not enforced.
