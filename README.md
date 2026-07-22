# tson-java

A Java implementation of [TSON](https://tson.io) (Typed Schema Object Notation) — a schema system with
its own text notation, extending JSON with richer structural types, optional annotations and type
annotations, and a layered resolution model that separates structural parsing from semantic
interpretation.

This is one implementation of an open specification, not the canonical one — anyone can implement TSON.
Published under the [litterat](https://github.com/litterat) org, group id `io.ltr8`.

## Status

**Lexer and structural parser.** The lexer (§7) and structural parser (§2, §3, §7.4 — records, maps,
arrays, augmentation, directives) are implemented. Base type resolution (§4) and the built-in type
vocabulary (§5) are not yet implemented. See [CLAUDE.md](CLAUDE.md#architecture) for the current
architecture and design notes.

Built against TSON Part 1 (lexer + data format), a working draft: https://tson.io/raw/2026/32/tson-part1-data.md

This is the spec's first implementation. Issues and ambiguities found in the spec while implementing are
tracked in [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md).

## Requirements

- Java 25
- No external runtime dependencies. JUnit (Jupiter) is used for tests only.

## Build and test

```
./gradlew build
./gradlew test
```

## Related

- [ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite) — language-agnostic
  conformance test vectors for any TSON implementation, including this one.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
