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
| `SCHEMA_UNAVAILABLE`        | a reference no configured source would supply — **not** a verdict on the schema, never obtained |

### The four that are not verdicts on the document

`NOT_IMPLEMENTED`, `BIND_MISMATCH`, `SCHEMA_UNAVAILABLE` and the three refusal codes each say the
document was not judged, for a different reason:

- **`SCHEMA_ERROR` vs `SCHEMA_UNAVAILABLE`** is the distinction a caller deciding whether to retry
  needs: the first says the schema is wrong, the second that nothing was checked. `fetchReason` says
  by whose doing.
- **`NOT_IMPLEMENTED`** is a gap in this library. It rides in the report located at the value it could
  not read, and costs that value a verdict and nothing else's — so a gap and an ordinary error in one
  document both get reported. Two exist today, both on a schema that loaded clean: `unknown` and
  `extern`.
- **`BIND_MISMATCH`** is a misconfiguration in the *reading application*, no more a verdict on the
  document than a gap is. It normally fails the bind-mode compile as an exception instead; it reaches a
  read as a diagnostic only for a schema compiled on demand.
- **The three refusal codes** are §8.2's fifth outcome. §8.2 says a refusal MUST NOT be reported in any
  of §8.1's four error categories, because each rule reads Unicode data the UCD does not freeze. One
  code per rule — the three want three different remedies, and the code is what a consumer routes on.

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
        Optional<SourcePosition> schemaPosition,
        Optional<TsonSchemaFetchException.Reason> fetchReason,
        Optional<String> unicodeDataVersion) {

    Optional<String> schemaIdIfKnown();
    Optional<String> expectedIfStated();
    Optional<String> actualIfStated();
}
```

The four location components match JSON Schema 2020-12 §12's output unit — where in the data, where in
the schema — so one record renders both data-side and schema-side problems; the variation between them
is locational, not categorical.

**Two components are not locations**, and each carries a distinction the closed `Code` cannot and
`message` must not:

- `fetchReason` — `NOT_PERMITTED` / `NOT_FOUND` mean the document named something this deployment will
  not fetch or nothing serves; `TRANSPORT` / `TIMEOUT` / `TOO_LARGE` mean the reference was fine and
  only those are worth retrying.
- `unicodeDataVersion` — on the three refusal codes and nowhere else.

What earns a component at all is one rule: **a fact not recoverable from the document plus the
schema**. Which is why an atom's failed bound (in the schema), a duplicate key (in the document) and
the rule that fired (the code) get none.

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

| Code | Meaning                                                                  |
| ---- | -------------------------------------------------------------------------- |
| `0`  | valid / compiled cleanly (or an explicit `--help`)                       |
| `1`  | at least one data document is invalid                                    |
| `2`  | usage error — bad arguments, an unreadable file                          |
| `69` | `EX_UNAVAILABLE` — a schema nothing would supply; **nothing was checked** |
| `70` | `EX_SOFTWARE` — a library gap or an internal fault; **no verdict reached** |

`1` is a verdict on the input; `69` and `70` are the absence of one, naming who could not give it.
`TsonCli.exitCodeFor` lifts a run to whichever is most permanent — **70 over 69 over 1**, since
retrying reaches a gap again. Both non-verdicts also ride in the report as codes
(`NOT_IMPLEMENTED`, `SCHEMA_UNAVAILABLE`) with a note on stderr; the report on stdout is unchanged.

70's two halves print differently: a gap prints `not implemented yet: <message>`, whose text usually
names the workaround; a fault gets the please-report-it banner and its stack trace.
