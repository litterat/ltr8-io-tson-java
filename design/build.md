# Build, measurement and publishing

The full command list, what the allocation harness measures, and what `publishToMavenLocal` produces. Current form
only; history lives in git.

**Invariants**

- No system Gradle — always the wrapper. `build` runs javadoc, so doclint failures are build failures.
- Retention across reads is a flat 0 bytes per read; transient-byte ceilings are loose on purpose — the shape is the
  signal, not the number.
- Per-value cost is measured as a difference between two document sizes, never as a whole-read total.
- No remote repository is configured, deliberately; every version carries `-SNAPSHOT`.

Related: `design/conformance-suite.md`.

## Commands

No system Gradle — always use the wrapper:

```
./gradlew build                   # also builds the javadoc/sources jars, so doclint runs under `build`
./gradlew test
./gradlew publishToMavenLocal     # installs every module into ~/.m2 as io.ltr8:<module>:0.36.0-SNAPSHOT
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.lexer.LexerTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonDataParserTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.ConformanceSuiteTest"  # class1; needs the corpus
./gradlew :tson:test --tests "io.ltr8.tson.Class2ConformanceSuiteTest"                 # class2, same corpus
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonSchemaLinkerTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonCompiledSchemaRegistryTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.resolver.DefinitionResolverTest"
./gradlew :tson-cli:installDist   # then tson-cli/build/install/tson/bin/tson validate ...
./gradlew :tson:allocationReport  # the allocation harness alone, numbers on stdout
./gradlew :tson-base:test         # the shared vocabulary: diagnostics, policies, identity, schema sources
./gradlew :tson-json:test         # the JSON encoding's own stack
```

**Allocation is measured, not assumed** (`AllocationHarnessTest`, `tson/src/test/.../perf/`, with
`JsonAllocationHarnessTest` the JSON stack's own; `AllocationProbe` is shared from
`tson-base/src/testShared`). Two separate questions over the bind read path: **retention** — settled heap
across 20,000 reads of one schema, plus a
weak-reference check that no read output stays reachable, both currently a flat **0 bytes per read**, which
is what the "resolve every schema at startup, then read" design claims and nothing else asserts — and
**transient bytes**, reported per read with a ceiling loose enough to survive a JDK upgrade and tight enough
to catch a 50x mistake (a `Pattern` per character was one, at 188 bytes per character written). Numbers move
with the JDK and the machine; treat the *shape* as the signal — `whereAReadsBytesGo` splits a read into
stream/tree/bind so a change says which stage moved. **Per-*value* work needs a difference, not a total**: a
per-record map is a fraction of a read and two whole-read figures land within noise of each other, so both
harnesses also report bytes per bound record, measured between a document of 4 records and one of 64. What
that costs is a ratchet here; the exact properties are pinned where they are cheap to state — that a name
index is built once per class in `tson-bind` (`DataClassRecordFieldIndexTest`), and that a repeated
*undeclared* field is still reported in each reader's own test, that being the one repeat a filled slot
cannot answer for — because a threshold tight enough to catch a tenth of a read is a budget the next JDK
breaks. `AllocationProbe`'s Javadoc has the Flight Recorder
flags for when the next question is "where".

**Publishing is packaging, not release.** Every subproject applies `maven-publish` with a `mavenJava`
publication (the `java` component plus sources and javadoc jars) and a POM carrying name/description/
url/licence, so `publishToMavenLocal` gives another project on the same machine an ordinary
`io.ltr8:tson:0.36.0-SNAPSHOT` dependency instead of an included build. **No remote repository is
configured, deliberately** — Maven Central needs signed artifacts and a POM with scm/developers, and
publishing under a name is not a decision the build should make quietly. The jars carry real
`module-info.class`es, so a consumer works on the class path or the module path; `tson-annotation` and
`tson-regex` land in a consumer's POM at runtime scope (they are `implementation` dependencies of the
modules that use them), which is enough for both, verified end to end against a real consuming build.

`BACKLOG.md` tracks the actively-maintained engineering backlog; `SPEC-FEEDBACK.md` records spec issues;
`STRUCTURED-OUTPUT.md` holds the target-use-case plan (LLM structured-output validation, JSON
