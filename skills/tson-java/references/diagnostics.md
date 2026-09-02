# Diagnostics, exceptions, exit codes

## `Diagnostic.Code`

A **closed enum** (`io.ltr8.tson.compiler.Diagnostic.Code`) — a new code is an API change, not a new
string appearing in a message. Switch on it exhaustively; never match on `message` text.

| Code                        | Means                                                                                       |
| --------------------------- | --------------------------------------------------------------------------------------------- |
| `FIELD_REQUIRED`            | a required field was absent from the data                                                   |
| `FIELD_FIXED`               | a field the schema fixes carried a different value                                           |
| `TYPE_MISMATCH`             | the value's shape does not match the type in scope                                           |
| `WRONG_ARITY`               | a tuple or template application has the wrong element/argument count                         |
| `UNKNOWN_TYPE_REF`          | a `!type` annotation names a type the schema in scope does not declare                       |
| `ATOM_CONSTRAINT_VIOLATION` | a built-in atom's grammar or declared constraint was violated                                |
| `UNRECOGNIZED_FIELD`        | the data carried a field the type does not declare (§7.2 — records are closed, always)       |
| `DUPLICATE_MAP_KEY`         | two entries of one map share a key (§2.6)                                                    |
| `DUPLICATE_FIELD`           | two fields of one record share a name (§2.5)                                                 |
| `CONFUSABLE_NAMES`          | §8.2 refusal — two names in one scope read alike                                             |
| `RESTRICTED_CHARACTER`      | §8.2 refusal — a character outside the identifier profile                                    |
| `RESTRICTED_SCRIPT`         | §8.2 refusal — a script the restriction level does not admit                                 |
| `SCHEMA_ERROR`              | the governing schema itself is invalid or failed to resolve — it *was* obtained              |
| `UNKNOWN_TYPE`              | a type reference does not resolve within the linked schema                                   |
| `VALIDATION_ERROR`          | anything not covered by a more specific code — including a document that will not lex or parse |
| `NOT_IMPLEMENTED`           | **a library gap, not bad input**                                                             |
| `BIND_MISMATCH`             | a schema type and its bound class disagree about that type's fields                          |
| `SCHEMA_NOT_PERMITTED`      | policy refused the reference — not an allowed host, not a legal identity, no pin where required |
| `SCHEMA_NOT_FOUND`          | the location was reached and does not have it                                                |
| `SCHEMA_UNREACHABLE`        | the location could not be reached, or answered with something other than a document          |
| `SCHEMA_TIMEOUT`            | the location did not answer in time                                                          |
| `SCHEMA_TOO_LARGE`          | the location answered with more bytes than a schema document may be                          |

### The seven that are not verdicts on the document

`Code.verdict()` is the one statement of the set, so a consumer does not keep a private copy that can
drift: it is `false` for `NOT_IMPLEMENTED`, `BIND_MISMATCH` and the five `SCHEMA_*` fetch codes, and
`true` for everything else. Each of the seven says the document was not judged, and they differ in *who*
could not judge it — which is exactly what a caller picking an HTTP status or an exit code is asking.

- **`NOT_IMPLEMENTED`** is a gap in this library. It rides in the report located at the value it could
  not read, and costs that value a verdict and nothing else's — so a gap and an ordinary error in one
  document both get reported. Two exist today, both on a schema that loaded clean: `unknown` and
  `extern`.
- **`BIND_MISMATCH`** is a misconfiguration in the *reading application*, no more a verdict on the
  document than a gap is. It normally fails the bind-mode compile as an exception instead; it reaches a
  read as a diagnostic only for a schema compiled on demand.
- **The five `SCHEMA_*` fetch codes** are everyone else: no configured `TsonSchemaSource` would supply
  the schema the document names. Nothing is wrong with the document, and nothing may be wrong with the
  schema either — it was never obtained, so it was never read. **`SCHEMA_ERROR` vs the five** is the
  distinction a caller deciding whether to retry needs: `SCHEMA_ERROR` is a verdict, the schema *was*
  obtained and does not resolve. And "everyone else" is several people, which is why there are five:
  `SCHEMA_NOT_PERMITTED` and `SCHEMA_NOT_FOUND` mean the document named something this deployment will
  not fetch or nothing serves, `SCHEMA_TOO_LARGE` that it named something too big to accept, and
  `SCHEMA_UNREACHABLE`/`SCHEMA_TIMEOUT` that the reference was fine and the world did not answer — only
  those last two are worth a retry.

**The three refusal codes are verdicts**, though not validity ones. §8.2 says a refusal MUST NOT be
reported in any of §8.1's four error categories, because each rule reads Unicode data the UCD does not
freeze — but the processor looked and declined, and the sender holds the fix, so `verdict()` is `true`
and the CLI exits 1. One code per rule: the three want three different remedies, and the code is what a
consumer routes on.

## The `Diagnostic` record

```java
package io.ltr8.tson.compiler;

public record Diagnostic(
        Optional<String> path,            // RFC 6901 into the DATA; "" is the root, not absence
        Optional<String> schemaPointer,   // RFC 6901 into the SCHEMA; same convention
        String schemaId,                  // canonical id; "" when unknown
        Code code,
        String message,
        String expected,                  // the CONSTRAINT that failed, never a type name
        String actual,
        Optional<SourcePosition> dataPosition,
        Optional<SourcePosition> schemaPosition) {

    Optional<String> schemaIdIfKnown();
    Optional<String> expectedIfStated();
    Optional<String> actualIfStated();
}
```

The four location components match JSON Schema 2020-12 §12's output unit — where in the data, where in
the schema — so one record renders both data-side and schema-side problems; the variation between them
is locational, not categorical.

**Every component is a location.** The one fact that is not — why a schema could not be obtained — is
carried by the `Code` rather than beside it. A fetch failure has five causes, and which one it was is a
*routing* question: a code is what a consumer routes on, so a field beside the code would be a second
carrier for the same fact, free to disagree with it. Consumers partition the five differently (a command
line by whether a rerun could help, an HTTP surface by whose doing it was), so there is one code per
cause and no partition is privileged. `TsonSchemaFetchException.Reason` remains the *throwing* channel's
vocabulary, and `Diagnostic.Code.of(reason)` is the single mapping between the two.

What earns a component at all is one rule: **a fact not recoverable from the document plus the schema,
and about the problem rather than about the processor**. Which is why an atom's failed bound (in the
schema), a duplicate key (in the document) and the rule that fired (the code) get none — and why a §8.2
refusal's Unicode data version and policy get none either: they are constant for the whole run, so they
are stated once beside the diagnostics (`TsonUnicodeProcessorPolicy`, `tson policy`, and the `policy` field on
every `tson-cli` envelope) rather than N times inside them.

**Two absence conventions, deliberately.** The two pointers are `Optional` because `""` is the *root*,
a location this really emits. `schemaId`/`expected`/`actual` use `""` and offer the three
`…IfStated()`/`…IfKnown()` accessors, so a renderer asks rather than remembering which applies where.

**`SourcePosition`** is `line` / `column` / `byteOffset` — line and column 1-based and column counted
in code points, the offset counting UTF-8 bytes from 0. Rendered `line:column:byteOffset`.

**The schema end is the path taken through your schema**, accumulated as the read descends, never the
leaf it resolves to: `/person/age`, not `/int32` in core.tn. The leaf names a file the author did not
write and never mentions the field they can edit. A record re-anchors id + position on itself.

## Receivers

The read stack holds no error policy of its own; it reports and keeps going, and the receiver decides
whether that is fatal. A fail-fast reader and a collecting one are the same read with different
receivers.

```java
public interface TsonDiagnosticsReceiver {
    void report(Diagnostic diagnostic);

    static TsonDiagnosticsReceiver throwing();     // first problem becomes a TsonReadException
    static TsonDiagnosticsCollector collecting();  // .diagnostics(), .isEmpty()
}
```

One method, so a caller wanting neither built-in implements it directly — capping, streaming to a log,
routing by code. It is called **as problems are found**, not at the end.

Attach one with `.withDiagnostics(receiver)` on either facade reader; it returns a *new* reader and
leaves the original fail-fast.

**Mode asymmetry, deliberate, not an inconsistency:** collecting mode always keeps reading, and **bind
mode is all-or-nothing** (a `ConstructionGuard` — a partially-filled object is worse than none) while
**tree mode keeps everything it built** (a `TsonAbsent` stands where a value failed).

## Exceptions

Every exception this library raises at read time is unchecked. There is no common `TsonException` root
— classification is by type, and the policy below is what picks it.

```
RuntimeException
├── TsonReadException              io.ltr8.tson.compiler  — .diagnostic(); what a fail-fast read throws
├── TsonParseException             io.ltr8.tson.compiler  — well-formed tokens, invalid document (§7.4)
├── TsonUnsupportedDocumentException  a well-formed document of a kind this parser does not implement
├── TsonWriteException             the write-side peer of TsonReadException
├── TsonBindMismatchException      a schema type and its bound class disagree
│   └── TsonMissingBindingException   a schema type with no bound class at all
├── TsonSchemaValidationException  io.ltr8.tson.schema — the author's schema is wrong and the spec says so
├── TsonSchemaFetchException       .uri(), .reason() — the ONLY exception a TsonSchemaSource may throw
├── TsonContentHashMismatchException  a ?sha256= pin did not match the fetched content
├── AtomTypeException              (sealed, internal package) .expected()
│   ├── AtomParseException         the token is not this atom's grammar
│   └── AtomValidationException    it parsed, then failed the atom's constraint
├── LexException                   (internal lexer package) malformed UTF-8, non-NFC unquoted token, …
├── TsonRegexSyntaxException       io.ltr8.tson.regex
├── UnsupportedOperationException  a gap: this library has not implemented that yet
└── IllegalStateException          an internal invariant broke — a bug here, not bad input
```

`DataBindException` (`io.ltr8.bind`) is the one **checked** exception, on the binding engine's own
descriptor API; the facade readers do not surface it.

### Exception classification is a policy, not a style choice

Across the schema pipeline:

- **`TsonSchemaValidationException`** — the author's schema is wrong and the spec says so.
- **`UnsupportedOperationException`** — this library has not implemented that yet.
- **`IllegalStateException`** — an internal invariant broke.

The test: **a schema error's verdict does not change when this library improves; a gap's does.** The
CLI's exit 1 vs. exit 70 rides on that distinction — carried by `Diagnostic.Code.NOT_IMPLEMENTED`, not
by the channel, so a gap thrown out of a phase that reports per declaration does not take every other
declaration's verdict with it.

`LexException` and `AtomTypeException` live in unexported packages and cannot be named in a `catch`
from another module. `Diagnostic.ofBaseSyntaxError(e)` is public for exactly that reason: it classifies
a base-syntax failure and **rethrows anything else**, which is what a caller driving a `TsonDataStream`
or `TsonDataParser` directly cannot do for themselves. The facade readers call it for you.

### Which exception comes out of where

| Call                                                    | Throws                                                                   |
| ------------------------------------------------------- | -------------------------------------------------------------------------- |
| `new TsonDataParser(…).parseDocument()`                 | `TsonParseException`, `TsonUnsupportedDocumentException`, `LexException`  |
| a fail-fast facade read                                 | `TsonReadException` for everything, base syntax included                 |
| a collecting facade read                                | nothing, for any bad document — a library fault still throws             |
| `Tson.validate` / `validateSchema`                      | nothing, for any bad document — a library fault still throws             |
| `Tson.resolve` on an already-registered `!!id`          | `TsonSchemaValidationException`                                          |
| `Tson.resolve` naming an unavailable `!!import`/`!!meta`| `TsonSchemaFetchException`                                               |
| a bind-mode compile whose class disagrees               | `TsonBindMismatchException` — at compile, not at first read              |
| the first read of a type with no bound class            | `TsonMissingBindingException`, thrown unwrapped from its `ErrorReader`   |
| a `TsonSchemaSource`                                    | `TsonSchemaFetchException` and nothing else — another type means a fault |

**`!!meta` in a document handed to the data parser** throws `TsonUnsupportedDocumentException`, not
`TsonParseException`: a schema document is unsupported there, not malformed.

## CLI exit codes

| Code | Meaning                                                                                          |
| ---- | -------------------------------------------------------------------------------------------------- |
| `0`  | everything was checked and nothing was reported (or an explicit `--help`)                        |
| `1`  | **checked and rejected** — the validity codes, plus a §8.2 refusal                               |
| `2`  | usage error — bad arguments, an unreadable file                                                  |
| `69` | `EX_UNAVAILABLE` — a schema was not obtained and a rerun will not obtain it: `SCHEMA_NOT_PERMITTED`, `SCHEMA_NOT_FOUND`, `SCHEMA_TOO_LARGE` |
| `75` | `EX_TEMPFAIL` — a schema was not obtained and a rerun may help: `SCHEMA_UNREACHABLE`, `SCHEMA_TIMEOUT` |
| `78` | `EX_CONFIG` — a type the schema needs has no Java class in this tool: `BIND_MISMATCH`             |
| `70` | `EX_SOFTWARE` — a library gap (`NOT_IMPLEMENTED`) or an uncaught fault; **no verdict reached**    |

`1` is a verdict on the input; everything above `2` is the absence of one, naming who could not give it.
A §8.2 name-hygiene refusal is a `1` and not a fifth code: §8.2's "not in any of the four categories" is
about which layer detected it, where an exit code answers what the caller should do now — and a refusal
was checked and declined, with the sender holding the fix.

`TsonCli.exitCodeFor` lifts a mixed run to one code, ranked by **who must act before anyone else's fix
counts**, permanence breaking the tie: **70 > 78 > 69 > 75 > 1**. 70 and 78 name nobody present (a
release of this library; an application wired to bind that type), 69 the runner editing the reference or
the allow-list it is checked against, 75 the runner simply rerunning, and 1 the runner editing the
document. Every non-verdict also rides in the report as its own code, with a note on stderr; the report
on stdout is unchanged, and its `outcome` reads `NOT_CHECKED` for all of them.

70's two halves print differently: a gap prints `not implemented yet: <message>`, whose text usually
names the workaround; a fault gets the please-report-it banner and its stack trace.
