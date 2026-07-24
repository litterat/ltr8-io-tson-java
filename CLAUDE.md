# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A from-scratch Java implementation of TSON (Typed Schema Object Notation), built directly against the
TSON spec series:

- Part 1 (lexer, structural grammar, base type resolution, built-in type vocabulary):
  https://tson.io/raw/2026/32/tson-part1-data.md
- Part 2 (schema grammar, type system) — grammar layer in progress (see `SchemaParser` below); resolution
  just started, one construct so far (see `SchemaResolver` below): https://tson.io/raw/2026/32/tson-part2-schema.md

The spec is a *working revision* (2026 series) and changes between revisions without compatibility
guarantees — re-fetch the current URL rather than trusting a cached copy of the text when in doubt, and
check the revision number at the top of the document.

`spec/` holds local snapshots fetched 2026-07-22 (Part 1) and 2026-07-23 (Part 2), revision 32, for quick
reference without re-fetching every session: `spec/tson-part1-data.md` (Part 1, verbatim),
`spec/tson-part2-schema.md` (Part 2, verbatim), and `spec/m/{core,meta,meta-kernel}.tn1` — the pre-loaded
meta-kernel bootstrap layer, the canonical meta-schema built on it, and the core type library built on
that (Part 2 schema documents, reachable via `!!meta`/`!!import` chaining from `core.tn1`) — plus their
non-normative resolver-output fixtures `spec/m/{core,meta,meta-kernel}-resolved.tn1` (Part 2 §1.5), the
target shape for the resolution layer once it exists. The three source `.tn1` files are what §5's
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

`tson-parser` holds the lexer, the data-grammar structural parser, base type resolution, the built-in
type vocabulary, the Part 2 schema grammar (`SchemaParser`, `ast.schema`), the schema resolver
(`io.ltr8.tson.parser.resolver.schema.SchemaResolver`, producing Class 2's resolved schema value), *and*
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
never needs to reference `tson-parser` at all; `SchemaResolver` converts field-by-field at the one spot
that needs it (`resolveField`'s `toMetaToken`). `tson-bind`/`tson-annotation` are the separate
Java-object-binding layer (see their own package Javadoc; not detailed in this file yet) —
`tson-schema`'s own `schema.meta` classes depend on `tson-annotation` only (for `@Typename`/`@Field`),
never on `tson-bind`.

**There is no `tson-mapper` module anymore.** It originally held the `DataValue`&lt;-&gt;Java-object
mapper (`TsonMapper`, plus `AtomBinder`/`AtomWriter`/`TsonAnnotations`), depending on `tson-parser` +
`tson-schema` + `tson-bind`. Moved into `tson-parser` itself (2026-07-24) — split into
`TsonMapperReader`/`TsonMapperWriter` along the way (see "Mapper" below) — once `SchemaResolver`'s own
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

`Parser` (`Parser.java`) turns the lexer's token stream into a `Document` (§2, §3, §7.4). AST types live
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
  position of whatever comes next (`Parser.consumeSeparatorOrCloseCheck`) — a real comma token is
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
- **`!!meta` in the header throws `SchemaDocumentException`, not `ParseException`.** This is a Class 1
  (data-format-only) processor (§1.5); encountering a schema document isn't malformed input, it's a
  well-formed document of a kind this parser doesn't implement, and the spec requires that distinction be
  visible in how it's reported (§8.1: "MUST report the document as a TSON schema document that this
  processor does not support" — a categorized diagnostic, not a generic parse error).
- **Nested annotation value-scope is right-recursive and can legitimately leave an outer data-value
  without a core-value.** `@a:@b:val` fully consumes everything as `@a`'s nested value, all the way down
  — see `SPEC-FEEDBACK.md` #3 for the exact trace; `ParserTest.nestedAnnotationValueScopeAloneIsIncomplete`
  documents this as intentional, spec-derived behavior, not a bug, so don't "fix" it without re-reading
  that entry first.

### Base type resolution (`tson-parser/src/main/java/io/ltr8/tson/parser/resolver/`)

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
  vocabulary (§5 — `!uuid`, `!date`, `!int32`, etc.) is a separate implementation, `resolver.vocab`
  (below), consulted only when a value actually carries a type-ref.

### Built-in type vocabulary (`tson-parser/src/main/java/io/ltr8/tson/parser/resolver/vocab/`)

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
else), and a same-named-but-suffixed `*Parser` class here in `resolver.vocab` (`IntegerParser`,
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
beyond a fixed component/RFC pin) — so there's nothing to split out. Each `*Parser` keeps
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
being `Optional<Pattern>` made `MetaKernelParser`'s `text`/`uri`/`regex` entries throw
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
without a module cycle. Two consequences: `SchemaResolver`/`TsonSchema` (which *do* need
`tson-parser`'s grammar AST) moved into this module, at `resolver.schema` (this package's own
sibling — see "Schema resolution" below); and `schema.meta.Token` was introduced as a local,
structurally-identical stand-in for `tson-parser.ast.TokenValue`/`TokenForm` (same `text`/`form`
fields, same three enum members) purely so `RecordField.value`/`TypeArgument.Value` (§8.1's literal-
value fields) don't need `tson-parser`'s own type — `SchemaResolver` converts between the two at the
one spot that needs it (`resolveField`'s `toMetaToken`). `tson-schema`'s own module now holds
*only* `io.ltr8.tson.schema.meta` and depends on nothing but `tson-annotation` (for `@Typename`/
`@Field`); `tson-parser` depends on `tson-schema`, the reverse of before. This groundwork is for a
future schema-*validating* parser (Class 2): once one exists inside `tson-parser`, it can hold and
consult a resolved `TsonSchema`/`TypeDefinition` directly, the same way `resolver.vocab` already
consults `schema.meta` constraint records — without `tson-schema` ever needing to import
`tson-parser` back.

**Not yet done:** the `schema.meta` constraint-values split hasn't been *used* for anything schema-
related yet (Part 2's own atom-refinement resolution — `!I ^ { ... }` — doesn't exist; see "Schema
resolution" below) — today it's purely an internal reshaping of the existing Class 1 vocabulary,
done in preparation for that future use, not a new capability by itself.

### Mapper (`tson-parser/src/main/java/io/ltr8/tson/parser/mapper/`)

Binds a parsed `DataValue` tree to a Java object given its `DataClass` descriptor from `tson-bind`,
and back — `TsonMapperReader`/`TsonMapperWriter`. Moved here from a separate `tson-mapper` module
(2026-07-24; see "Architecture" above for the module-cycle reasoning) once `SchemaResolver`'s own
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
or `EmptyBrace`, never a bare token/array. This is why `MetaKernelParser`'s own `!enum [...]`
handling (see "Meta-kernel bootstrap" below) stays hand-written rather than routing through
`TsonMapperReader` generically — a real, currently-unclosed gap for any future caller (e.g.
`SchemaResolver`'s own generalized constructor-application resolution) that needs to bind a
positional-form value generically; wrapping the bare value into an equivalent one-field
`RecordValue` before delegating to ordinary record binding is the natural fix, not yet built.

### Schema grammar (`tson-parser/src/main/java/io/ltr8/tson/parser/SchemaParser.java`,
`.../ast/schema/`)

`SchemaParser` parses a schema document's body (Part 2 §2.1, §5, ABNF at §12.1) into a
`SchemaDocument`, the schema-grammar analogue of `Document`/`CoreValue`. AST types live in the
`ast.schema` subpackage (`TypeDef`, `TypeRef`, `RecordDef`, `ContainerDef`, etc.) alongside
`tson-parser.ast`'s data-grammar types, one sealed hierarchy per ABNF production family, mirroring
`ast`'s own shape.

- **Grammar-only, deliberately.** `SchemaParser` builds a faithful AST from a schema document's source
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
- **`SchemaParser extends Parser`, same package.** Part 2 §12.1 says the schema grammar imports
  `annotation`, `data-value`, and directive parsing directly from Part 1 §7.4 — the same tokens, the same
  adjacency/separator rules, the same `!!name:"..."` directive shape for `!!id`/`!!meta`/`!!import` as
  data documents use for `!!id`/`!!schema`. Rather than re-implementing that grammar a second time,
  `Parser`'s relevant fields and helper methods are package-private, not `private` (see its own Javadoc
  on why it isn't `final`), and `SchemaParser` calls straight into them — `parseDataValue()` for
  constructor-application/atom-refinement values, `parseAnnotation()`, `parseNamedDirective()` for all
  three header directives, `expectFieldNameToken()`, and the cursor/separator primitives. `Parser` itself
  is untouched in behavior — it still rejects `!!meta` documents exactly as before; only its own
  private-vs-package visibility changed, and only because `SchemaParser` needed it, not because either
  class became part of a different module.
- **`construction-def`'s ABNF doesn't parse its own worked example** (`address & contact & { ... }` needs
  an implicit `&` before the trailing `record-def` that alternative 1 as literally written doesn't admit)
  — implemented per the documented intent, not the letter; see `SPEC-FEEDBACK.md` #14 and
  `ConstructionDef`'s own Javadoc before touching `SchemaParser.parseConstructionDefContinuation`.
- **`field-modifier`'s value is a bare token or the absent sentinel, not a full `data-value`** — §12.1's
  own introductory prose claims otherwise, but its ABNF and §5.2's prose agree on the narrower rule; see
  `SPEC-FEEDBACK.md` #15. `FieldDef.Modifier.Value` models exactly `token | absent`, reusing
  `tson-parser.ast`'s `TokenValue` rather than the full `DataValue`.
- **An unquoted, non-numeric type-argument always parses as a type reference, never a value literal** —
  `SchemaParser.parseTypeArg`'s Javadoc explains why this is the grammar's own deliberate deferral (§12.1,
  §5.10: "settled against the applied signature's parameter kinds... not by the grammar"), not an
  implementation gap; classifying an enum-member-shaped argument as a value happens at a later, semantic
  layer not built yet.
- **Verified against the real fixtures, not just the spec's own short examples.** `SchemaParserTest`
  parses `spec/m/meta-kernel.tn1`, `spec/m/meta.tn1`, and `spec/m/core.tn1` (read directly from this
  repo's own `spec/` directory, not the sibling test-suite repo) end-to-end with no exceptions — real,
  full-sized schema documents, not just the spec's illustrative snippets.

### Schema resolution (`tson-parser/src/main/java/io/ltr8/tson/parser/resolver/schema/`)

`SchemaResolver` turns the grammar-layer `SchemaMap` (same module, `io.ltr8.tson.parser.ast.schema`)
into resolved `TypeDefinition`s (Part 2 §4, §8) -- values from `tson-schema`'s `io.ltr8.tson.schema.meta`
(see "Architecture" above for why the resolver itself lives in `tson-parser`, not `tson-schema`, despite
producing `tson-schema` values). Started 2026-07-23, deliberately narrow, incrementally widened the same
day to a second construct:

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

Every other construct (elided field types outside a tightening entry, an `Absent` modifier value or
a modifier on an OPTIONAL field, the identity-diagonal FIXED-value invariant, atom refinement
(`!I ^ { ... }`, a distinct grammar form from `RefinedDef`), **constructor application / atom
instances** (`!C value`, `Instance` -- `value => !unit {}`, `boolean => !enum [true false]`, and
every other atom-family bootstrap instance in the real fixture use this and are not dispatched at
all yet in `resolveTypeDef` -- see below), restating a field group in a refinement body, subtraction,
a generic type-ref with a nested or value (non-simple) argument, and an inter-supertype field
collision) throws `UnsupportedOperationException` rather than silently mis-resolving --
`SchemaResolver`'s own Javadoc lists exactly what's in scope.

**Status against the real `meta-kernel.tn1` fixture: 36 of its 49 declarations resolve via
`resolveAll` as of this writing; every one of the remaining 13 fails for the exact same reason** --
each is `Instance` (constructor application, above), the one remaining construct standing between
here and loading the whole file. Re-check with a throwaway `resolveAll` probe before assuming this
count is still current -- it changes every time resolver coverage widens.

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
`TypeArgument`'s own Javadoc and `SchemaResolverTest`'s array-sugar assertions). If a future session
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
    `datetime_type`/`duration_type`, the remaining nine, added 2026-07-24) once it turned out each
    one's own fields are all `Optional`, so none actually needed the harder "represent a field-group's
    mutual exclusion in a *bound instance*" design work in the first place. `SchemaResolver` itself
    still doesn't resolve anything to one of these thirteen via ordinary schema-grammar resolution --
    `MetaKernelParser` binds its five real-fixture instances (all bare `{}`) by hand instead; the
    other eight (`decimal_type` and friends) aren't instantiated anywhere in meta-kernel itself, only
    in `core.tn1`, which no resolver reaches yet (see "Not yet implemented" below). Verified each new
    class actually round-trips through `TsonMapperReader`/`TsonMapperWriter` bound against `Atom`
    (not just that it compiles) -- eight of the nine do; `DurationType` doesn't, by design, not a
    regression: its `IsoDuration` field pairs `java.time.Period`/`java.time.Duration`, and
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
  style -- none of them wrong, all textual, and all documented in `SchemaResolverTest`'s own class Javadoc:
  no outer `!type_definition` tag (plain records, unlike union members, never self-announce a type-ref);
  quoted strings where the fixture uses bare tokens (an enum's bridge yields a `String`, and `TsonMapperWriter`
  always quotes strings -- pre-existing, already-documented behavior, not new); every empty-list/`false`/
  at-default-enum field written out rather than omitted (`Optional.empty()`/`null` are the only things
  generic binding omits -- `tson-bind` doesn't support `Optional<List<T>>` yet, so an empty list can't opt
  into the same omission an `Optional<TypeRef>`/`Optional<Boolean>` field gets for free); and `TypeRef`
  always in its full `{ name: ... arguments: [...] }` form, never §5.6's positional bare-token spelling (a
  schema-specific encoding rule a Part-1-only binder has no reason to know about).
- **`TsonSchema`** (`io.ltr8.tson.schema.TsonSchema`, in `tson-schema`'s own main package, not
  `.meta` -- moved back there 2026-07-23; it had briefly lived alongside `SchemaResolver` in
  `tson-parser` purely for organizational convenience, but needs no `tson-parser` dependency at
  all) is the resolved-schema wrapper -- the kernel's own `schema` type, `map<type_name,
  type_definition>` (§9), plus the governing-chain header directives its own document carried
  (`id`/`meta`/`imports`, §2.2). **A plain class, not a record** -- deliberately, so `MetaSchema`
  (below) can `extend` it directly. `SchemaResolver.resolveAll(SchemaDocument)` builds one from a
  whole document (not just its body), resolving each entry independently in source order and
  carrying the header straight through; most entries of an arbitrary real schema still throw via
  this path alone, since most constructs aren't resolved yet -- `resolve(SchemaMap.Declaration)`
  resolves a single named entry and is the one to reach for against a real fixture until more
  constructs are supported (or use `MetaKernelParser`, below, for meta-kernel specifically).
- **`MetaSchema`** (`io.ltr8.tson.schema.MetaSchema`, alongside `TsonSchema`) is a distinct
  `extends TsonSchema` subtype marking, in the type system, meta-kernel's own resolved schema --
  it adds no fields or behavior of its own. Kept separate from a plain `TsonSchema` purely for
  identity: the value it wraps had to be pre-loaded (below), not resolved the ordinary way, and
  a caller holding a `MetaSchema` knows that without re-deriving it.

### Meta-kernel bootstrap (`tson-parser/src/main/java/io/ltr8/tson/parser/resolver/schema/MetaKernelParser.java`)

Meta-kernel is special: its own `!!meta` names *itself* (§1.5's "one deliberate circularity in the
series, closed by pre-loading rather than by resolution: implementations ship the kernel's resolved
structure, and this document describes it"). Ordinary schema resolution can't bootstrap it from
nothing either way: resolving a constructor-*application* instance (`!C value`, §5.5 -- e.g.
`integer => !integer_type {}`) needs `C`'s own vocabulary already known, and every `C` meta-kernel
uses is defined within meta-kernel itself.

`MetaKernelParser` is a stateless parser/resolver -- the same shape as `SchemaParser`/
`SchemaResolver` -- **producing a `MetaSchema` from `parse()`/`parse(String)`, not extending
`TsonSchema` itself** (an earlier version of this class did extend it directly; corrected
2026-07-23 once the `MetaSchema` subtype existed to hold the result properly instead). It resolves
in two passes over meta-kernel's 49 declarations:

1. Every declaration whose `TypeDef` is **not** an `Instance` goes through `SchemaResolver` exactly
   as normal, in source order (36 of the 49, everything `SchemaResolver`'s own Javadoc already
   documents as in scope).
2. The 13 deferred `Instance` declarations (`value => !unit {}`, `boolean => !enum [true false]`,
   `integer => !integer_type {}`, and friends) are resolved in a **second pass**, once every
   constructor they reference -- including ones declared *later* in the file, e.g. `enum` itself
   isn't declared until long after `boolean` uses it -- has an entry to transfer a kind from (§5.5:
   "construction transfers only the constructor's kind; the result records source: C with empty
   supertypes").

**Constructor-application binding is done by hand, not through generic object binding** (revised
2026-07-23, replacing an earlier version that used `TsonMapper.toObject` and lived in the separate
`tson-mapper` module purely to reach it -- that module no longer exists at all today; see "Mapper"
below for why) -- deliberately, so this class needs nothing beyond `tson-parser`/`tson-schema`
(both already main-scope dependencies of this module). An `Instance`'s `value` is already a parsed `DataValue`
(the schema grammar reuses Part 1's own data-value parsing directly), and every registered target's
shape is simple enough to check directly against the AST rather than bind generically: `unit` and
`integer_type` are only ever instantiated as a bare `{}` in the real fixture (every field
`IntegerType` has is `Optional`, so an empty body is exactly `IntegerType.UNCONSTRAINED`) --
verified by requiring the value actually be an `EmptyBrace` node, not just assumed, the same
"validate, don't just trust the shape" treatment `enum` already got. `enum` needs a small
hand-written converter regardless of binding style: `!enum [true false]`'s value is a bare array
(§5.6's positional form for a single-field constructor), not `{ members: [...] }`, which generic
`tson-bind` record binding has no positional-form support for anyway. **Every `Instance` declaration
in the real fixture is registered** (`unit`, `integer_type`, `text_type`, `uri_type`, `regex_type`,
`enum`) -- all 49 declarations resolve; a declaration whose target isn't registered would simply be
left out of the result entirely rather than failing the whole bootstrap, but that path is
unexercised against the real fixture today.

**`meta-kernel.tn1` is packaged as a classpath resource, not read from a filesystem path.**
`MetaKernelParser.parse()` (no args) reads `/meta-kernel.tn1` off the classpath via
`Class.getResourceAsStream` -- `tson-parser/build.gradle.kts` wires its `processResources` task to
copy the file straight from the repo's own `spec/m/meta-kernel.tn1` snapshot at build time (`from
(rootProject.layout.projectDirectory.dir("spec/m")) { include("meta-kernel.tn1") }`), so there is
exactly one copy of the file on disk to keep in sync with the spec, but the bootstrap still works
from a built jar (e.g. published to a repository), not only from a repo checkout. `parse(String
source)` remains available for parsing arbitrary/custom source text.

**`IntegerType`, then `TextType`/`UriType`/`RegexType`, all became real `Atom` variants for this
(2026-07-23)** -- the four atom constraint-vocabulary families the old `TypeBody`'s own Javadoc used
to list as "deliberately not modeled yet", now all modeled: `IntegerType` needed no
field-group-in-a-bound-instance design work (mutual exclusion between `min`/`exclusiveMin` and
`max`/`exclusiveMax` is already enforced by its own compact constructor), and `TextType`/`UriType`/
`RegexType` needed none either -- every field across all three is `Optional`, so each already had
(or gained) its own `UNCONSTRAINED` constant for exactly this empty-body case. Each gained
`@Typename` (`text_type`/`uri_type`/`regex_type`) and multi-word fields gained `@Field`
(`min_length`/`max_length`), matching the convention every other `Atom` variant already
follows, even though `MetaKernelParser` itself binds none of them generically -- kept consistent in
case something else (e.g. a future `toTson` call on one of these) needs it. The remaining nine atom
constraint-vocabulary families (`DecimalType`/`FloatType`/`RationalType`/`UuidType`/`BinaryType`/
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
`MetaKernelParser` itself no longer needs generic binding, since `SchemaResolverTest`'s own
`toTson` verification (below) still binds through `TsonMapperWriter`.
- **Verified against the real fixture, not just a hand-written snippet.** `SchemaResolverTest` resolves
  `integer_size` both from a small inline schema and from the real `spec/m/meta-kernel.tn1`, and asserts
  the exact real `toTson` output -- structurally equivalent to (per the divergences above, not a content
  difference from) `meta-kernel-resolved.tn1`'s own `integer_size` entry, and, via hand-built `Reference`/
  `Unit`/`EnumBody`/etc. values exercising shapes `SchemaResolver` doesn't produce yet, `type_name`'s/
  `value`'s/`boolean`'s own entries too.

### Schema registry (`tson-schema/src/main/java/io/ltr8/tson/schema/`, `.../registry/`)

`SchemaResolver`/`MetaKernelParser` (both in `tson-parser`) resolve each declaration
*individually* — no whole-schema consistency checking, references carried through as bare,
unverified strings, `!!import` parsed but never consulted, and a `type_ref` with arguments (e.g.
`enum`'s own `members: set<token>` field, or any field using §5.3's `[X]`/`[X]?` array sugar) left
exactly as written. `SchemaRegistry` adds the missing second stage on top: internal-consistency
validation, flattening every argument-bearing `type_ref` into a real named entry, and — once
satisfied — locking the schema into a registry keyed by its canonical `!!id` identity. Added
2026-07-24, entirely in `tson-schema` (no dependency on `tson-parser`, preserving the established
one-way direction).

**Package split, user-facing vs. internal-by-convention (per explicit user direction):**
`io.ltr8.tson.schema` holds the public surface — `SchemaRegistry`, `SchemaLoader`,
`SchemaValidationException` — alongside `TsonSchema`/`MetaSchema`. `io.ltr8.tson.schema.registry`
holds `CanonicalIdentity` and `SchemaValidator`, the private pass-2 machinery nothing outside this
module calls directly. **Note on enforcement:** `tson-schema` has no `module-info.java` (unlike
`tson-bind`/`tson-annotation`, which do), so there's no JPMS boundary to truly hide `.registry`
behind — both classes are `public` purely because `SchemaRegistry` needs to call them
cross-package; "private" here means internal-by-convention/package-naming only, a deliberate,
confirmed tradeoff rather than adding a module descriptor now.

- **`CanonicalIdentity.of(String)`** implements `[TSON-DATA] §2.2.1`'s canonical-identity algorithm
  exactly — **not** general URI normalization. The spec performs exactly two reductions (strip
  scheme + `://`, strip query) and requires everything else already be canonical — lowercase host,
  no userinfo, no port (default or otherwise), no percent-encoding of *unreserved* characters
  (`A-Za-z0-9-._~`; encoding anything else is fine), no dot-segments, no fragment — rejecting
  (`SchemaValidationException`) rather than fixing up an identifier that isn't. E.g.
  `"https://tson.io/2026/32/m/meta-kernel.tn1"` → `"tson.io/2026/32/m/meta-kernel.tn1"`; `http://`
  and `https://` resolve to the same identity; a `?sha256=...` query is dropped, not validated.
- **`SchemaLoader`** (`@FunctionalInterface Optional<TsonSchema> load(String canonicalIdentity)`) —
  the pluggable-with-a-default hook for resolving a `!!import` target, matching Part 2 §10.1's
  precedence order (pre-loaded/registered authoritative, "fetched" opt-in and disabled by default).
  `SchemaRegistry`'s own no-arg constructor supplies a default that only ever finds an
  *already-registered* schema — nothing is fetched from anywhere.
- **`SchemaValidator.validate(TsonSchema, SchemaLoader)`** — the actual pass-2 engine:
  1. **Merge `!!import`s** (Part 2 §2.2.3, added 2026-07-24) — every import, in declaration order,
     looked up via `loader` by canonical identity, its entries copied in *as-is* ("merged entries
     keep their home namespace" — an imported `TypeDefinition` is never re-resolved or
     re-materialized against the importer; only the importer's own new material gets that
     treatment). **Shallow** — only the imported schema's own `entries()` are read, never its own
     `imports()` — falls out for free here since `loader` always hands back an already-registered,
     already-flattened `TsonSchema`. A name collision — between two imports, or between an import
     and a local declaration — is a resolver error, checked as each stage merges in, not after the
     fact: import entries first, then the importer's own resolved/synthesized entries (checked
     against what's already merged), matching the exact ordering the user specified. An import
     whose identity isn't found via `loader` is a `SchemaValidationException` (e.g. the importer
     needs to have been registered into the *same* `SchemaRegistry` first — the default loader is
     registered-only).
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
     structurally-identical occurrences reuse it. Each synthesized entry is exactly
     `TypeDefinition.reference(TypeRef)`'s existing shape (that method's own Javadoc already flagged
     this gap: "this resolver doesn't materialise instantiation entries yet ... until that exists").
     A synthesized name is `head_arg1..._hash` (§8.2's own non-normative "readable head plus
     structural hash" guidance; not conformance-relevant, free to refine). Every `Top` variant
     has its own rewrite case, written as an **exhaustive switch over the (multi-level) sealed
     interface** (no `default`) so a future new variant is a compile error here, not a silent miss --
     `switch`/pattern matching checks exhaustiveness across the whole `permits` graph transitively, so
     switching directly on `Top`'s own concrete leaves (rather than needing a case per intermediate
     `Atom`/`Product`/`Sum` level) is exhaustive the same way it was when this switched over the old,
     single-level `TypeBody` (deleted 2026-07-24 in favor of switching on `Top` directly — see
     "Schema resolution" above's `io.ltr8.tson.schema.meta` bullet).
  3. **Validate** — over the now-expanded map (originals + synthesized), every reference must
     resolve: `TypeDefinition.source`/`supertypes`/`subtypes`, and every `TypeRef` reachable through
     the same exhaustive `Top` switch (`RecordBody.fields`, `Reference.target`,
     `MapBody.keyType`/`valueType`, `ArrayBody.elementType`, `TupleBody.elements`,
     `ChoiceBody.variants`). **Type-parameter exception** (load-bearing for every parameterized
     declaration — `array`, `set`, `map`, `array_min`, `array_max`, `array_ranged`): a bare name is
     valid if it resolves in the namespace *or* is one of the checked entry's own declared
     `parameters` — e.g. `set => <T> ~array<T> ^ {...}` resolves its own `source` (`array<T>`) where
     `T` is `set`'s own parameter, not a real entry. `RecordBody.groups[].members` gets a bonus
     check against a different namespace (sibling field names within the same record, not type
     names). Any failure throws `SchemaValidationException` naming the offending entry/reference.
  4. Returns a new plain `TsonSchema` — even when the input was a `MetaSchema`; once
     validated/registered, which resolver produced it no longer matters.

  **Scope note on `source`:** `TypeDefinition.source` is *provenance* (how an entry was itself
  derived — composition/refinement/construction), not a field consuming another type, so it's
  validated but never itself materialized into a further synthetic entry, even when it carries
  arguments (`set`'s own `source: array<T>` is exactly this case) — materializing it would create a
  synthetic entry with no standalone meaning, tied only to `set`'s own identity.
- **`SchemaRegistry`** — `register(TsonSchema)` computes the canonical identity from the schema's
  own `!!id` (throwing if absent), rejects a duplicate identity outright (no overwrite — together
  with `TsonSchema.entries()` already being an unmodifiable map, this rejection *is* the "locked, no
  mutations allowed" guarantee), then runs `SchemaValidator` and stores the result. `get(String uri)`
  takes a *raw* URI and canonicalizes internally — callers never need to call `CanonicalIdentity`
  themselves for either method. (Its private `lookupByCanonicalIdentity` helper, which expects an
  *already*-canonical identity, is the piece actually shared with `SchemaLoader`'s own contract, and
  is what the default loader delegates to — `get`'s public, raw-URI-taking form can't be reused
  directly there.)

**Verified against the real fixture, not just hand-built schemas.**
`MetaKernelSchemaRegistryTest` (in `tson-parser`, not `tson-schema` — that module has no dependency
on `tson-parser`/`MetaKernelParser` at all, so this is the one place both are available) registers
`MetaKernelParser.parse()`'s real output end-to-end. It produces **9** synthesized entries, not the
1 (`set_token_*`) a naive `<...>` grep of the source predicts: `[X]`/`[X]?` array-sugar field types
elsewhere in the fixture (`arguments: [type_argument]?`, `fields: [record_field]`, `groups:
[field_group]?`, `supertypes`/`subtypes`/`parameters: [type_name]?`/`[param_name]?`, `elements:
[tuple_element]`, `variants: [type_ref]`, `members: [field_name]`) desugar to `array<X>`
applications too (§5.3) — three separate `[type_name]?` uses across different declarations
correctly dedup to a single `array_type_name_*` entry, confirmed against the real data, not just a
hand-built case.

**`!!import` merging verified against the real `meta.tn1` fixture too, with an honest limit.**
`MetaSchemaImportTest` (`tson-parser`, same reasoning as above for why it lives there) registers the
real meta-kernel schema first, then meta.tn1's own declarations (`!!import:"...meta-kernel.tn1"`),
confirming meta-kernel's own entries (`atom`, `text_type`, ...) are visible and correctly referenced
from meta.tn1's own composition-based declarations (`date_type => ~atom & atom_specification &
{...}`). **meta.tn1 can't be registered in full yet** — 4 of its 31 declarations (`binary_encoding`,
`ieee_format`, `complex_component`, `ordered`) are `!enum [...]` constructor-application `Instance`s,
a construct `SchemaResolver` doesn't resolve generically outside `MetaKernelParser`'s own hand-rolled
meta-kernel bootstrap — a separate, pre-existing gap, unrelated to import merging itself. Three more
declarations (`binary`, `float_type`, `complex_type`) reference one of those four as a field type, so
attempting to register them too correctly *fails* reference validation — the test proves this
directly (a dedicated assertion registering just `binary` and catching the expected
`SchemaValidationException`), rather than silently working around it. The other 24 declarations,
whose own dependency closure is otherwise complete, register cleanly.

### Conformance suite integration (`ConformanceSuiteTest`)

Separate from `LexerTest`/`ParserTest` (fine-grained unit tests) is `ConformanceSuiteTest`, which runs
every vector in the sibling [ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite)
repo against the real `Lexer`/`Parser` as JUnit 5 dynamic tests (one per vector, named
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
./gradlew test --tests "io.ltr8.tson.parser.ParserTest"
./gradlew test --tests "io.ltr8.tson.parser.resolver.NumberGrammarTest"
./gradlew test --tests "io.ltr8.tson.parser.resolver.BaseTypeResolverTest"
./gradlew test --tests "io.ltr8.tson.parser.ConformanceSuiteTest"   # skipped unless ../../ltr8-io-tson-test-suite exists
./gradlew test --tests "io.ltr8.tson.parser.lexer.LexerTest.multilineBasicIndentStripping"
./gradlew :tson-schema:test --tests "io.ltr8.tson.schema.SchemaRegistryTest"
./gradlew :tson-schema:test --tests "io.ltr8.tson.schema.registry.SchemaValidatorTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.resolver.schema.MetaKernelSchemaRegistryTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.resolver.schema.MetaSchemaImportTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.mapper.TsonMapperReaderTest"
./gradlew :tson-parser:test --tests "io.ltr8.tson.parser.mapper.TsonMapperWriterTest"
```

## Not yet implemented

- Part 2 schema resolution: atom refinement (`!I ^ { ... }`), subtraction, elided field types
  outside a tightening entry, restating a field group in a refinement body, and generic type-refs
  beyond a bare two-argument `map<K, V>` application or a refinement source — see `SchemaResolver`'s
  own Javadoc (under "Schema resolution" above) for the exact, current boundary of what resolves.
  (Template/instantiation-entry *materialization* itself is now handled — see "Schema registry"
  above — just not per §8.2's precise constructor-vs-template split; materialization is uniform.)
- **Constructor-application `Instance` generalization** (`!C value`, §5.5) — `SchemaResolver` still
  only dispatches this for `MetaKernelParser`'s own hand-rolled meta-kernel bootstrap targets
  (`unit`/`integer_type`/`text_type`/`uri_type`/`regex_type`/`enum`), not generically for an
  arbitrary schema. This is the actual blocker keeping `meta.tn1` from registering in full today —
  see "Schema registry" above's `MetaSchemaImportTest`: 4 of its 31 declarations are `!enum [...]`
  Instances (`binary_encoding`/`ieee_format`/`complex_component`/`ordered`), and 3 more
  (`binary`/`float_type`/`complex_type`) reference one of those as a field type, so registering
  them too correctly fails reference validation until this generalizes.
- A schema-validating data parser (Class 2) that consults a resolved `TsonSchema`/`TypeDefinition`
  while parsing data — the built-in vocabulary's `schema.meta`/`resolver.vocab` split (see "Built-in
  type vocabulary" above) is groundwork for this, not this itself.
- §9.1's numeric-literal length limit (SHOULD, default 4096 digits, DoS-hardening) — not enforced.
