# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A from-scratch Java implementation of TSON (Typed Schema Object Notation), built directly against the
TSON spec series:

- Part 1 (lexer, structural grammar, base type resolution, built-in type vocabulary):
  https://tson.io/raw/2026/32/tson-part1-data.md
- Part 2 (schema grammar, type system) — not yet started: https://tson.io/2026/32/tson-part2-schema

The spec is a *working revision* (2026 series) and changes between revisions without compatibility
guarantees — re-fetch the current URL rather than trusting a cached copy of the text when in doubt, and
check the revision number at the top of the document.

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

Single Gradle module so far: `tson-parser`, holding both the lexer and (eventually) the structural
parser under one module — they're tightly coupled (the parser consumes the lexer's token stream
directly and nothing else depends on the lexer alone), so splitting them into separate Gradle modules
was judged not worth the build-graph overhead. Layers with real independent conformance meaning — e.g.
resolver + base-type/built-in-vocabulary resolution (spec's "Class 1 processor") vs. the schema layer
(spec's "Class 2 processor", Part 2) — are the natural points for future module boundaries.

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
- The built-in type vocabulary (§5 — `!uuid`, `!date`, `!int32`, etc.) is a separate, much larger piece of
  work, not started. `BaseTypeResolver` only implements the *default*, untyped resolution path.

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
```

## Not yet implemented

- Binding `NumberForm` to a Java numeric type (`long`/`double`/`BigInteger`/`BigDecimal`), including the
  spec's required equivalence between different representations of the same value (§4.3).
- The built-in type vocabulary (§5) — resolving `!uuid`/`!date`/`!int32`/etc. annotations. A separate,
  larger piece of work from base type resolution.
- Anything from Part 2 (schema grammar, type system) — not started.
