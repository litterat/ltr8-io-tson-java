# Examples — run the library from a single `.java` file

← back to the [README](../README.md)

These are [single-file Java 25 programs](https://openjdk.org/jeps/512) (compact source files with an
instance `main`) that use the `tson` library loaded via the **module system** — no build tool, no
project, just `java SomeFile.java`.

## Run them

One-time: gather the library's runtime module jars into `tson/build/modules`:

```
./gradlew :tson:modules
```

Then run any example directly (from the repo root):

```
java --module-path tson/build/modules --add-modules io.ltr8.tson examples/ObjectBinding.java
java --module-path tson/build/modules --add-modules io.ltr8.tson examples/TreeWalk.java
java --module-path tson/build/modules --add-modules io.ltr8.tson examples/EventStream.java
java --module-path tson/build/modules --add-modules io.ltr8.tson examples/SchemaValidation.java
```

- `--module-path tson/build/modules` puts the five `tson` module jars on the module path.
- `--add-modules io.ltr8.tson` resolves the front-door module (and, transitively, `tson-compiler`,
  `tson-schema`, `tson-bind`, `tson-annotation`), making them readable from the program's own code.
- Inside each file, `import module io.ltr8.tson;` imports the front door and its transitive modules in
  one line; `IO.println` and `java.base` types (`UUID`, `LocalDate`, …) come for free from a compact
  source file's implicit `java.base` import.

Requires **Java 25** (see the main [README](../README.md#getting-started) for how to get it).

## What's here

These are the four numbered reader entry points from the main [README](../README.md#reading-tson-choosing-an-entry-point):

| File | Shows |
|---|---|
| [`ObjectBinding.java`](ObjectBinding.java) | Schemaless — bind a TSON document straight to a Java `record` (built-in types `!ipv4`/`!uuid`/`!date` map to `java.base` types). |
| [`TreeWalk.java`](TreeWalk.java) | Schemaless — parse into a navigable `Document`/`CoreValue` AST. |
| [`EventStream.java`](EventStream.java) | Schemaless — pull a lazy `TsonEvent` stream without materializing a tree. |
| [`SchemaValidation.java`](SchemaValidation.java) | Schema-driven — compile a TSON *schema* and validate data against a type, collecting every problem in one pass. |
