---
name: tson-java
description: Read, validate, write and bind TSON (`.tn`) documents with the `io.ltr8:tson` Java library, and run its `tson` command line. Use this skill whenever Java code imports `io.ltr8.tson`, `io.ltr8.tson.compiler`, `io.ltr8.tson.tree` or `io.ltr8.bind`; whenever names like `Tson`, `TsonConfig`, `TsonTreeReader`, `TsonObjectReader`, `TsonValue`, `Diagnostic`, `TsonReadException`, `TsonSchemaSource`, `TsonCompiledSchema` or `TsonBundledSchemas` appear; whenever work happens inside the `ltr8-io-tson-java` repository; and whenever someone wants to check, compile or hash `.tn` files from a shell, a script, a Gradle task or a CI job — `tson validate`, a pre-commit hook, a lint step — whatever language the surrounding project is written in. For authoring TSON *data* documents use the tson-data skill; for *schema* documents use tson-schema. This skill is the Java implementation and its CLI, not the notation.
---

# `io.ltr8:tson` — the Java implementation

The **reference implementation** of TSON (Typed Schema Object Notation) — the first implementation of
the spec, and the one the TypeScript port and the shared conformance suite are checked against. **Java
25, no external runtime dependencies** (JUnit for tests only). It implements both spec parts — Class 1
(the text data format) and Class 2 (the schema layer) — and passes the shared conformance suite.

Eight JPMS modules, all published together as `io.ltr8:<module>`:

| Module           | Java module name          | What it is                                                     |
| ---------------- | ------------------------- | -------------------------------------------------------------- |
| `tson`           | `io.ltr8.tson`            | the front door: `Tson`, `TsonConfig`, the two schema sources    |
| `tson-compiler`  | `io.ltr8.tson.compiler`   | the engine: readers, writers, `Diagnostic`, lexer, both grammars |
| `tson-tree`      | `io.ltr8.tson.tree`       | `TsonValue` and its node types — the read output of tree mode   |
| `tson-schema`    | `io.ltr8.tson.schema`     | the resolved-schema value model + the schema registry           |
| `tson-bind`      | `io.ltr8.bind`            | the generic `DataValue`↔Java-object binding engine              |
| `tson-annotation`| `io.ltr8.annotation`      | `@Typename`/`@Field`/`@Record`/… and the `Annotations` carrier  |
| `tson-regex`     | `io.ltr8.tson.regex`      | a standalone RFC 9485 I-Regexp engine (no TSON dependency)      |
| `tson-cli`       | `io.ltr8.tson.cli`        | the `tson` command (`validate`, `compile`, `hash`, `init-example`) |

Source, issues and releases: **https://github.com/litterat/ltr8-io-tson-java** (Apache-2.0). The
TypeScript port is [ltr8-io-tson-typescript](https://github.com/litterat/ltr8-io-tson-typescript), and
the shared conformance vectors both are tested against are
[ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite).

**Versioning is `0.<spec revision>.<patch>`.** `0.34.x` implements the **2026 Revision 34** spec series.
A new revision moves the minor, and the spec is a working draft with no compatibility guarantee between
revisions — so a schema `!!id` pinned at `https://tson.io/2026/34/m/core.tn` is revision-specific and
must match the library's own revision.

> **Not on Maven Central.** Publishing needs signed artifacts and a fuller POM, and that is a separate
> decision — no remote repository is configured, deliberately. To use it from another project on the
> same machine: clone, then `./gradlew publishToMavenLocal`, which installs every module into `~/.m2`
> under `io.ltr8` with sources and javadoc jars beside each. A consuming Gradle build then adds
> `mavenLocal()` and takes an ordinary dependency on `io.ltr8:tson:0.34.0-SNAPSHOT` (the front door,
> which pulls the rest in). The jars carry real `module-info.class`es, so a consumer works on the class
> path or the module path.

## Workflow

1. **Decide whether you need a schema at all.** Reading one document with no schema is one
   constructor and one call — `new TsonTreeReader().read(text)`. `Tson.builder().build()` bootstraps
   meta-kernel/meta.tn/core.tn and is what you need only once a *schema* is in play.
2. **Pick the reader from the matrix below** — the two questions are *what drives the interpretation*
   (the wire alone, your Java class, or a TSON schema) and *what you want out* (a `TsonValue` tree, or
   a bound Java object).
3. **Build one `Tson` at startup and keep it.** A schema compiles once per instance, and concurrent
   *reads* through one instance are safe. Registering schemas concurrently is not.
4. **Choose fail-fast or collecting.** A reader throws `TsonReadException` at the first problem;
   `.withDiagnostics(collector)` returns the value *and* every problem, and `tson.validate(...)`
   returns the problems alone. Use a collecting read for anything that reports to a person.
5. **Switch on `Diagnostic.Code`** — a closed enum — rather than matching `message` text.

## Pick the entry point

| You have…                          | You want…                                     | Use                                       |
| ---------------------------------- | ---------------------------------------------- | ----------------------------------------- |
| a data document                    | a queryable tree                              | `tson.treeReader()` / `new TsonTreeReader()` |
| a data document + your Java class  | it bound                                      | `tson.objectReader()` / `new TsonObjectReader()` |
| a data document + a schema you hold| it validated as a named type                  | `.withSchema(uri).readAs(…)` on either reader |
| a data document                    | every problem, not the value                  | `tson.validate(…)` → `List<Diagnostic>`   |
| a *schema* document                | every problem with the schema itself          | `tson.validateSchema(…)` → `List<Diagnostic>` |
| a data document                    | the value **and** every problem               | `.withDiagnostics(…)` on either reader    |
| a data document                    | only what it *declares*, before reading it    | `TsonDocumentHeader.peek(…)`              |
| a `TsonValue` tree                 | TSON text                                     | `tson.treeWriter()` / `new TsonTreeWriter()` |
| a Java object                      | TSON text                                     | `tson.objectWriter()` / `new TsonObjectWriter()` |
| a data document                    | a grammar-faithful AST                        | `new TsonDataParser(text).parseDocument()` |
| a data document                    | to pull events lazily                         | `TsonDataStream`                          |

**The two facade readers are the whole document-reading surface.** They own the `!!schema` decision,
the target-class check, and the framing that rejects trailing content. Everything under them
(`TsonCompiledSchemaRegistry`, `TsonTypeReader`) reads *one value at a cursor* and polices nothing
around it.

**Constructed directly** (`new TsonTreeReader()`, `new TsonObjectReader()`, and the two writers) a
reader is schemaless, Jackson-style: it ignores any `!!schema` and binds to the wire, or your Java
class, alone — no standard-library bootstrap, no `Tson`. **Obtained from a `Tson`** it is schema-aware:
a document that declares its own `!!schema` is resolved and validated as it is read, and one that
declares none falls back to a schemaless read. `readWithoutSchema(…)` opts a schema-aware reader back
out.

## The front door

```java
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.tree.TsonValue;

Tson tson = Tson.builder().build();   // bootstraps meta-kernel, meta.tn and core.tn

String schema = """
        !!id:"https://example.com/2026/34/app/order-1.tn"
        !!meta:"https://tson.io/2026/34/m/meta.tn"
        !!import:"https://tson.io/2026/34/m/core.tn"
        {
          order => {
            order_id: int32
            customer: text
            placed:   date
            total:    number
          }
        }""";

tson.resolve(schema);                 // registers it under its own !!id

TsonValue value = tson.treeReader()
        .withSchema("https://example.com/2026/34/app/order-1.tn")
        .readAs("""
                { order_id: 1042  customer: "Ada Lovelace"  placed: !date 2026-07-01  total: 149.95 }""",
                "order");

value.get("customer").asString();     // Optional[Ada Lovelace]
value.get("order_id").asInt();        // OptionalInt[1042]
```

`withSchema(uri)` supplies what a `!!schema` directive would have said and `readAs(source, type)` what
a root type-ref would have. A **self-describing** document needs neither — `tson.treeReader().read(doc)`
resolves the schema the document names and picks the type from its own root `!order`.

**`Tson` methods that matter:**

| Call                          | Does                                                                       |
| ----------------------------- | -------------------------------------------------------------------------- |
| `resolve(schemaText)`         | parse → resolve → link → **register**, by the schema's own `!!id`; fail-fast |
| `validateSchema(schemaText)`  | the same, collecting — and **registers it too, when it is sound**           |
| `validate(String\|InputStream)` | a data document → `List<Diagnostic>`; empty means valid; never throws for bad input |
| `treeReader()`/`objectReader()` | schema-aware facades sharing this instance's compiled-schema cache         |
| `treeWriter()`/`objectWriter()` | the write-direction peers                                                  |
| `treeRegistry()`/`bindRegistry()` | the compiled registries — **the read mode is which one you hold**        |
| `schemaRegistry()`/`loader()` | the resolved-schema registry, and the on-demand loader underneath           |

`resolve` and `validateSchema` **both register**, so calling one after the other on the same text
throws `TsonSchemaValidationException` ("a schema is already registered under …"). Pick one.

`TsonConfig` (what `Tson.builder()` returns) carries: `schemaSource(…)` / `httpSchemas(hosts…)` /
`fileSchemas(host, dir)`, `bindings(Map<String, Class<?>>)` / `profile(name)` / `dataBindContext(…)`,
`metaNameBinder(…)`, `identifierPolicy(…)` / `tokenPolicy(…)`, and `lenientBinding()`.

## Reading into a tree

`TsonValue` is a sealed interface over seven pure immutable node types — `TsonRecord`, `TsonMap`,
`TsonArray`, `TsonTuple`, `TsonAtom`, `TsonAbsent`, `TsonMissing` (no `Node` suffix, deliberately).
**Every accessor is total — nothing throws.**

| Call                                                     | Answers                                                         |
| -------------------------------------------------------- | ---------------------------------------------------------------- |
| `get("name")` / `get(0)`                                 | one field/entry by name, or one element by index                |
| `at("/customer/name")`                                   | RFC 6901 JSON Pointer; `""` is the node itself                  |
| `fields()` / `elements()`                                | the children, empty for a leaf                                  |
| `isRecord()`/`isMap()`/`isArray()`/`isAtom()`/…          | which node this is                                              |
| `as(Class)`, `asString()`, `asBoolean()`, `asBigDecimal()` | **cast** — "what host type did the read produce?" `Optional`  |
| `asInt()`, `asLong()`, `asDouble()`                      | **convert** — "what number is this?" `OptionalInt`/…            |

A failed step yields a `TsonMissing` whose `missingPath()` is the pointer *up to and including the step
that failed* — `at("/nope/deeper").missingPath()` is `Optional[/nope]` — and every further `get`/`at`
returns that same node, so the first failure stays the informative one.

**`TsonMissing` (nothing there) is not `TsonAbsent` (the document wrote `_`).** There is one no-value
node and no separate null node: `TsonAbsent` carries `_`, the `null` token where §4 base resolution
applies, and a collecting-mode read failure.

**Casting and converting are different questions.** `as(Class)`/`asString`/`asBigDecimal` only ever
cast (`isInstance`), so a test asserting *which host type a reader produced* must use `as(Class)`.
`asInt`/`asLong`/`asDouble` convert and are exactness-checked, so `asInt()` on a `234.56E2` decimal
succeeds.

## Reading into your own classes

```java
public record Server(String hostname, Inet4Address address, UUID id, LocalDate deployedOn) {}

Server server = new TsonObjectReader().read("""
        {
            hostname: "web-01"
            address: !ipv4 192.0.2.10
            id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
            deployedOn: !date 2026-01-15
        }""", Server.class);
```

Records, `Map<K, V>`, `List<E>`, tuples, plain enums, sealed-interface unions and the whole built-in
vocabulary bind with no custom code — **the target class must be `public`**, since the library binds it
reflectively from another module. Under a schema, name the class for a schema type with
`TsonConfig.bindings(Map.of("order", Order.class))` and read with
`tson.objectReader().withSchema(uri).readAs(source, "order", Order.class)`.

**A schema and its bound class must agree**, checked at bind-mode compile — startup, not first read.
Any non-FIXED field with no component, or a component no field fills, raises
`TsonBindMismatchException`; optional fields are *not* exempt, since those are the ones that work in
development and fail on the first caller who sends them. `@Unbound` marks a component as the class's
own; `TsonConfig.lenientBinding()` opts out wholesale and is silent. The full annotation set and the
atom→Java type table are in `references/bindings.md`.

## Writing

```java
new TsonTreeWriter().toTson(node);      // a TsonValue tree back to text
new TsonObjectWriter().toTson(server);  // a Java object to text

new TsonObjectWriter().write(server, response.getOutputStream());  // or any Appendable
```

Both writers take a sink — `write(value, OutputStream|Appendable)`, UTF-8, **flushed and not closed**
(it is the caller's) — so a document never has to exist as a `String`; `toTson` is that call over a
buffer. `TsonTreeWriter` is closer to lossless than the object writer, because the tree keeps each
node's own type-ref: a `TsonAtom(42, "int32")` writes back as `!int32 42`, where the object writer
holds only a bound `long` and cannot recover the width. `toTson` is mainly a debugging tool —
`CONFORMANCE.md` documents exactly where it is lossy.

**Self-describing output** is off by default. `describing(…)` adds the header the readers already
honour, so the document reads back on its own:

```java
String body = tson.objectWriter().describing(schemaUri, "person").toTson(person);
tson.validate(body);   // [] — schema and type resolved from the bytes alone
```

The **object** writer takes the root type too, a bound object carrying neither fact; a `TsonValue`
already names its own type, so `treeWriter().describing(schemaUri)` takes just the URI.
`identifiedBy(documentId)` adds `!!id`. All three are derivations — the writer you called them on is
unchanged.

## Routing on the header alone

Whether a file is data or schema is a property of its header, not its extension, and §7.1 fixes the
cost at two directives of lookahead:

```java
TsonDocumentHeader header = TsonDocumentHeader.peek(text);   // or an InputStream
if (header.isSchemaDocument()) { … }                         // it carries !!meta
String uri = header.schema().orElse(DEFAULT_SCHEMA);
```

It is **total**: a header it cannot read yields nothing rather than throwing, and it never answers with
a schema the document does not name. A peek does not rewind its stream; when the source cannot be
reopened — an HTTP body, a socket — `peekResumable` hands the document back whole:

```java
TsonDocumentPeek peeked = TsonDocumentHeader.peekResumable(request.getInputStream());
TsonValue value = tson.treeReader().withSchema(versionFor(peeked.header()))
        .readAs(peeked.document(), "order");
```

Only the read-ahead is buffered (one decoder chunk), never the document.

## Fetching schemas

Out of the box a `Tson` serves only the three bundled schemas: `TsonSchemaSource.registeredOnly()` is
the default, so anything else must be registered first or reachable through a configured source. Two
fetching sources ship, plus a non-fetching third:

| Source                  | One-call form                    | Configure                                                                                       |
| ----------------------- | -------------------------------- | ------------------------------------------------------------------------------------------------ |
| `TsonHttpSchemaSource`  | `.httpSchemas("tson.io", …)`     | `allowHost`, `mapHost`, `maxDocumentBytes`, `timeout`, `maxCachedSchemas`, `requireContentHashPin`, `httpClient` |
| `TsonFileSchemaSource`  | `.fileSchemas(host, dir)`        | `mapHost(host, dir)`, `maxDocumentBytes`, `maxCachedSchemas`, `requireContentHashPin`           |
| `TsonSchemaSource.ofMap`| `.schemaSource(ofMap(map))`      | nothing — a lookup over schemas you already hold                                                |

**A schema reference is attacker-controlled** — a data document names its own schema, so on a server
that string came out of a request body. Both fetching sources **deny by default** and match a host
exactly: the HTTP one follows no redirects ever and caps against bytes delivered (SSRF), the file one
checks containment *after* `toRealPath` so `..` and symlink escape fall together. Neither verifies the
`?sha256=` pin or the fetched `!!id` — the loader does both.

`TsonSchemaSource` is a one-method interface (`String fetch(String uri)`) and **names its own failure
exception**: a source says "cannot supply this" with `TsonSchemaFetchException` and nothing else, whose
`Reason` is `NOT_PERMITTED` / `NOT_FOUND` / `TRANSPORT` / `TIMEOUT` / `TOO_LARGE`. Use
`TsonSchemaSource.ofMap(map)` rather than `schemas::get` — a `null` carries no `Reason` and is refused
as a fault.

## Diagnostics and errors

**The read stack holds no error policy.** Readers report to a `TsonDiagnosticsReceiver` — one method,
`void report(Diagnostic)` — and the receiver decides whether that is fatal. Fail-fast and collecting
are the same read with different receivers.

```java
var problems = TsonDiagnosticsReceiver.collecting();

Server server = new TsonObjectReader()
        .withDiagnostics(problems)
        .read("{ hostname: 1  address: nope }", Server.class);

for (Diagnostic d : problems.diagnostics()) {
    System.out.println(d.code() + " " + d.path().orElse("") + ": " + d.message());
}
```

`withDiagnostics` returns a *new* reader and leaves the original fail-fast. A receiver sees **every**
problem with the document — base syntax included — so a collecting read never throws for a bad
document; only a fault in the library throws past it. A fail-fast read throws `TsonReadException`,
which carries the `Diagnostic` on `.diagnostic()`.

A `Diagnostic` locates itself at both ends and carries one component that is not a location:

```java
record Diagnostic(Optional<String> path, Optional<String> schemaPointer, String schemaId,
                  Code code, String message, String expected, String actual,
                  Optional<SourcePosition> dataPosition, Optional<SourcePosition> schemaPosition,
                  Optional<TsonSchemaFetchException.Reason> fetchReason) {}
```

Two absence conventions, deliberately: the two RFC 6901 pointers are `Optional` because `""` is the
*root*, a location this really emits; `schemaId`/`expected`/`actual` use `""`, and offer
`schemaIdIfKnown()` / `expectedIfStated()` / `actualIfStated()` so a renderer asks rather than
remembering which convention each component uses.

**`expected` is the constraint that failed, never the type's name** — `<= 100`, `one of (A, B, C)`,
`at most 10 characters` — so a consumer building its own message (an LLM repair loop, say) never has to
parse `message`. The type's name leads `message` instead.

The **schema end is the path taken through *your* schema**: an `age: int32` field that violates its
bound reports `/person/age`, not `/int32` in core.tn, because a pointer into a library file you did not
write is not where you go to fix it.

`Diagnostic.Code` is a **closed enum** — switch on it exhaustively. Three of its members are not
verdicts on the document: `NOT_IMPLEMENTED` (a library gap), `BIND_MISMATCH` (your class and the schema
disagree), `SCHEMA_UNAVAILABLE` (nothing was checked — the schema was never obtained, and `fetchReason`
says by whose doing). Full list, the exception hierarchy and the exit codes: `references/diagnostics.md`.

## Name hygiene and token policy

§8.2's three name-hygiene rules — names that read alike, a character outside the identifier profile, a
script the restriction level does not admit — **refuse without making a document invalid**. Each reads
Unicode data that is not frozen, so none of them may decide validity, and a refusal must not be
reported in any of §8.1's four error categories. In this implementation a refusal is an ordinary
`Diagnostic` carrying `CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER` or `RESTRICTED_SCRIPT` — **one code per
rule, which is what a consumer routes on** — and nothing else.

**What judged it is stated once, not per refusal**: `TsonUnicodeProcessorPolicy` — `identifierPolicy` and
`tokenPolicy` (each a level, a unit, any `permitting` relaxations), under the same names that configured
them, plus the Unicode data version — from `tson.processorPolicy()`, from `processorPolicy()` on the
reader that judged, or from `tson policy` on the command line. Two deployments
can legitimately disagree about one name, and this is the only statement of why; read it *before*
generating and the disagreement never costs a round trip.

Two policies, defaulting opposite ways for the same reason in each case:

```java
Tson tson = Tson.builder()
        .identifierPolicy(TsonUnicodePolicy.highlyRestrictive().perSegment())  // declared names
        .tokenPolicy(TsonUnicodePolicy.unrestricted())                        // values
        .build();
```

`identifierPolicy` governs **declared names** and defaults to Highly Restrictive over the whole name
(§8.2's SHOULD). Reach for `perSegment()` before loosening the level — it is the first relaxation, and
what ordinary compounds need. `tokenPolicy` governs **every token a read pulls** and defaults to
`unrestricted()`, a value being data that may legitimately be anything; raise it when values are more
than payload (a service that renders what it reads into a UI). Levels: `asciiOnly()`,
`singleScript()`, `highlyRestrictive()`, `moderatelyRestrictive()`, `scriptsUnchecked()`,
`unrestricted()`, plus `permitting(scripts…)`. Either is also settable per reader with
`withIdentifierPolicy` / `withTokenPolicy`.

**This is a code path on purpose** — a security policy read from the environment is ambient authority,
invisible at the call site.

## CLI

```bash
./gradlew :tson-cli:installDist
export PATH="$PWD/tson-cli/build/install/tson/bin:$PATH"

tson init-example .                                   # writes person.tn + person-data.tn
tson validate person.tn person-data.tn                # OK
tson validate --output json person.tn bad-data.tn
tson compile person.tn                                # does the schema itself resolve and compile?
tson hash person.tn                                   # stamp ?sha256=… onto its own !!id, in place
```

|                       |                                                                                                     |
| --------------------- | ----------------------------------------------------------------------------------------------------- |
| Commands              | `validate`, `compile`, `hash`, `init-example`                                                       |
| Arguments             | **a flat file list** — each auto-classified as schema (its header carries `!!meta`) or data, by content, never by filename |
| Schema selection      | entirely the data's own: its `!!schema` names the schema, its root type-ref (`!person`) the type. There is no `--type`, and no `--schema` |
| `-`                   | reads one data document from stdin, at most once, always data (a file really named `-` is `./-`)     |
| `--output`            | `text` (default), `json`, `tson`                                                                    |
| Exit codes            | `0` valid · `1` a document is invalid · `2` usage · `69` a schema nothing would supply · `70` library gap or fault |

**The CLI fetches nothing** — schemas come from the files you list, and one it cannot match is
`SCHEMA_UNAVAILABLE` and exit 69, not a verdict on your data. `69` and `70` are deliberately kept apart
from `1`: `1` is a verdict on the document, the other two are the *absence* of one, naming who could not
give it. `TsonCli.exitCodeFor` lifts a run to whichever is most permanent — 70 over 69 over 1.
`validate` collects every problem in a file in one pass.

### Machine-readable output

`--output json` is one document per invocation, one file or twenty — a `files` array with each data
file's own `file`/`valid`/`errors`, wrapped in the run's verdict. **Its field names are `camelCase` and
an absent field is `null`, not omitted:**

```json
{"valid":false,"files":[{"file":"person-data.tn","valid":false,"errors":[
  {"path":"/age","schemaPointer":"/person/age","schemaId":"example.com/…/person.tn",
   "code":"ATOM_CONSTRAINT_VIOLATION","message":"'int32': 'thirty' is not a valid integer …",
   "expected":"an integer or based-integer form","actual":"thirty",
   "dataPosition":"5:8:154","schemaPosition":"17:5:677",
   "fetchReason":null}]}],"errors":[]}
```

Every envelope also carries `policy` — the §8.2 policy the run was judged under — between `valid` and
`files`. `tson policy` prints the same record on its own.

`--output tson` is the same record through the library's own writer and is **`snake_case` with absent
fields omitted** (`schema_pointer`, `data_position`) — the shape `tson-cli`'s own `diagnostics.tn`
declares, which that output is validated against. A position is `line:column:byteOffset`, the first two
1-based, the offset counting UTF-8 bytes from 0. The top-level `errors` carries only what stopped the
run before any document was read.

## Streaming and allocation

Every facade reader takes an `InputStream` as well as a `String` and pulls events through it, so memory
is proportional to **nesting depth**, not document size — nothing materialises a document to read part
of it, and the writers mirror it. Retention across 20,000 reads of one schema is a measured flat **0
bytes per read** (`AllocationHarnessTest`, `./gradlew :tson:allocationReport`), which is what the
"resolve every schema at startup, then read" design claims.

Feed an `InputStream` where you can: the reader decodes UTF-8 itself, and §7.1 requires malformed input
be rejected rather than substituted with U+FFFD, which a `String` round trip has already destroyed.

## Pitfalls

| You wrote                                                       | Problem                                                             | Do this instead                                             |
| --------------------------------------------------------------- | -------------------------------------------------------------------- | ------------------------------------------------------------ |
| `tson.validateSchema(s)` then `tson.resolve(s)`                 | both register; the second throws "already registered"               | pick one — `validateSchema` registers when the schema is sound |
| `new TsonTreeReader().read(doc)` on a self-describing document  | a directly-built reader ignores `!!schema` by design, so the root `!order` is an `UNKNOWN_TYPE_REF` | `tson.treeReader()`, from a built `Tson`  |
| `new TsonObjectReader().read(…, Server.class)` with a non-`public` record | binding is reflective across a module boundary            | make the target class `public`                              |
| a `Tson` built per request                                       | it re-bootstraps and recompiles every schema                        | build one at startup and keep it                            |
| registering schemas from several threads                        | only *reads* through one `Tson` are safe                            | resolve every schema at startup, then read                  |
| catching `TsonParseException` around a facade read              | a facade routes base syntax through the receiver                    | catch `TsonReadException`, or read `.diagnostic()`          |
| expecting a collecting read to throw on a syntax error          | it collects; an empty list is the only "valid"                      | check `problems.diagnostics().isEmpty()`                    |
| matching diagnostic `message` text                              | messages are not API                                                | switch on `Diagnostic.Code`                                 |
| `!type` on a schemaless read                                    | schemaless reads resolve built-ins only, and report the rest        | `.withSchema(uri)`, or `preservingUnknownTypeRefs()`        |
| treating `TsonMissing` and `TsonAbsent` as the same             | `TsonAbsent` was written (`_`); `TsonMissing` is a failed lookup    | `isAbsent()` / `isMissing()`, or `missingPath()`            |
| `asInt()` to assert which host type a read produced             | it converts; `234.56E2` answers too                                 | `as(Integer.class)`                                         |
| `schemaSource(schemas::get)`                                    | a `null` carries no `Reason`; refused as a fault                    | `TsonSchemaSource.ofMap(schemas)`                           |
| `httpSchemas()` with no host                                    | deny by default means nothing is permitted                          | name the hosts explicitly                                   |
| a `SCHEMA_UNAVAILABLE` read as "invalid document"               | nothing was checked                                                 | route on the code; `fetchReason` says whether to retry      |
| `NOT_IMPLEMENTED` read as "invalid document"                    | it is a verdict on this library                                     | treat it as a bug report; the CLI exits 70                  |
| a refusal code read as "invalid document"                       | §8.2 refusals are a fifth outcome, outside §8.1's four categories   | report it apart; relax `identifierPolicy` in code if intended |
| relaxing identifier policy from an env var                      | ambient authority, invisible at the call site                       | pass `identifierPolicy` explicitly                          |
| a hand-written or truncated `?sha256=`                          | pins are verified on every fetched pinned reference                 | `tson hash`, or `TsonContentHash.sha256`                    |
| `--output json` parsed as `snake_case`                          | JSON is `camelCase` with `null`s; only `--output tson` is `snake_case` | match the format you asked for                           |
| `!!id` pinned to a different spec revision than the library     | revisions are not compatible                                        | match the library's `0.<revision>.x`                        |
| `.tn1`                                                          | a stability claim §7.1 reserves for a frozen version 1              | `.tn`                                                       |

## Reference files

- `references/api.md` — the public surface module by module, with signatures.
- `references/bindings.md` — the binding layer: annotations, the atom→Java type table, strictness, profiles.
- `references/diagnostics.md` — every `Diagnostic.Code`, the exception hierarchy, CLI exit codes.

## Try it without a build tool

Each of the programs in [`examples/`](../../examples) is a runnable single-file Java 25 program that
loads the library over the module system. Build the module path once, then run any of them:

```bash
./gradlew :tson:modules
java --module-path tson/build/modules --add-modules io.ltr8.tson examples/ObjectBinding.java
```

`import module io.ltr8.tson;` at the top pulls in the front door and its transitive modules.

## Working on the implementation itself

`CLAUDE.md` at the repository root is the orientation for changing this code — the hard constraints
(Java 25, no external runtime dependencies), the module dependency direction, the pipeline phase by
phase, the exception-classification policy, and the traps that look like cleanup targets. The `docs/`
notes carry the per-area design detail, `BACKLOG.md` the outstanding work, `SPEC-FEEDBACK.md` the spec
issues still open against the current revision, and `STATUS.md` the implemented/not-yet checklist.
`references/` here documents the API as it stands, not how to extend it.

## Specification

- Part 1 — Text Data Format: https://tson.io/raw/2026/34/tson-part1-data.md
- Part 2 — Type System and Schema: https://tson.io/raw/2026/34/tson-part2-schema.md

Both are working revisions and change without compatibility guarantees until the spec freezes at
version 1. Re-fetch and check the revision number at the top rather than trusting a cached copy.
