# tson-java

A Java implementation of [TSON](https://tson.io) (Typed Schema Object Notation) — a schema system with immutable, hash-pinned
schemas whose definitions are themselves data. A document names its schema, the schema names its meta-schema;
one hash verifies the whole chain. The finishing touch, TSON's data format is a Unicode-first superset of JSON
you'll actually enjoy writing.

> **Status:** first implementation of TSON, built against a working-draft spec (2026
> revision 32). Part 1 (data format) and most of Part 2 (schema layer) are implemented; the
> API and the format itself may still change. See [STATUS.md](STATUS.md) for the full
> checklist.

**Requires Java 25.** No external runtime dependencies. **Not yet published to Maven Central.**
Need Java 25? Quick install via [SDKMAN!](https://sdkman.io) (`sdk install java 25-tem`) or an
[Adoptium](https://adoptium.net/temurin/releases/?version=25) build.

## Getting started

The quickest way to get a feel for the *schema layer* is the `tson` command-line tool.
Clone the repo, build and install the command:

```
git clone https://github.com/litterat/ltr8-io-tson-java.git
cd ltr8-io-tson-java
./gradlew :tson-cli:installDist
export PATH="$PWD/tson-cli/build/install/tson/bin:$PATH"
```
The `tson` command-line tool can be used to provide a working example, just run the
init-example command:

```
$ tson init-example
Wrote ./person.tn and ./person-data.tn.

Try it (the data names its own schema and type, so no --type is needed):
  tson validate ./person.tn ./person-data.tn
  …
```

Here's the `person.tn` schema created. It shows a few of the basic schema features,
including records, record groups, enums and some in-built types. The `2026/32` in the
URIs is the draft year/revision marker from the spec's release scheme.

```tson
!!id:"https://example.com/2026/32/getting-started/person.tn?sha256=1434c8c4c285ec9120500ef876dc3a2254f8a35534b922b1382990a9870fc79a"
!!meta:"https://tson.io/2026/32/m/meta.tn?sha256=983ad4da2ddf5b70b37da4af45e964290d24e6942776ef281c1e0d5942b46b07"
!!import:"https://tson.io/2026/32/m/core.tn?sha256=63912a45d5c7b12c92b4d32a596de3dbd875b04fd252f443827d6cf2cf5a385e"
{
    role => !enum [admin member guest]

    address => {
        street: text
        city: text
        country: text
    }

    person => {
        id: uuid
        name: text
        age: int32
        role: role
        joined: date
        email: text?
        address: address
        skills: [text]
        ( phone: text | mobile: text )?
    }
}
```

Every reference is *hash-pinned*. The `?sha256=…` on `!!id` is the schema's own content digest — its
identity is the URL, but its integrity is the hash, so this file *is* content-addressed. The digests
on `!!meta` and `!!import` pin the exact standard-library versions this schema was written against;
if a fetched `meta.tn`/`core.tn` doesn't hash to those, `validate`/`compile` refuse it rather than
resolve against something subtly different. Run `tson hash person.tn` any time to (re)compute the
content digest and stamp it onto `!!id` — the id line itself is excluded from the hash, so re-stamping
is stable. (Pinning is optional: drop the `?sha256=…` and a reference resolves by identity alone.)

And here's a corresponding `person-data.tn` *data* document. It's *self-describing*: the
`!!schema` header names the schema it conforms to, and the leading `!person` says which type:

```tson
!!schema:"https://example.com/2026/32/getting-started/person.tn"
!person {
    id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
    name: "Ada Lovelace"
    age: 30
    role: member
    joined: !date 1843-12-10
    address: {
        street: "12 Analytical Ave"
        city: "London"
        country: "UK"
    }
    skills: [ mathematics analysis "computing" ]
    mobile: "+44 20 7946 0958"
}
```

**`tson validate`** takes the files and validates the data. You pass both files; the data's `!!schema`
selects `person.tn` (by its `!!id`) and its root `!person` selects the type.

```
$ tson validate person.tn person-data.tn
OK
```

Now break something — open `person-data.tn`, change `age: 30` to `age: "thirty"`, delete the `name`
line, use a `role` that isn't one of the three, or add `phone: "…"` alongside `mobile` (the group
allows at most one) — and run it again. Every problem is reported at once, with a path and a reason:

```
$ tson validate person.tn person-data.tn
[ATOM_CONSTRAINT_VIOLATION] /age: 'thirty' is not a valid integer …
[FIELD_REQUIRED] /name: missing required field 'name' for 'person'
```

Then edit the *schema* `person.tn` — add a field, make one optional with `?`, change a type — and
**`tson compile`** checks it still resolves:

```
$ tson compile person.tn
OK
```

That's the whole loop, all from the shell: scaffold → edit → validate. See
[Command-line interface](#command-line-interface) below for the full command reference, and
[Reading TSON](#reading-and-writing-tson-choosing-an-entry-point) for reading TSON *from Java*.

---

## Reading and writing TSON: choosing an entry point

Two questions pick your reader: **what drives the interpretation** — the wire alone (schemaless), your
Java class, or a TSON schema — and **what you want out** — a generic tree/stream, or a bound Java object.
The write side is the mirror: a value in hand, TSON text out. The matrix:

| You have… | You want… | Use | You get |
|---|---|---|---|
| a data document + your Java class | it bound (validated if it self-describes) | **`tson.objectReader()`** | your object |
| a data document | a queryable tree (validated if it self-describes) | **`tson.treeReader()`** | a `TsonNode` tree |
| a TSON schema + a known type name | a reusable per-type reader | **`tson.treeRegistry()`/`bindRegistry()`** → **`TsonValueReader`** | a tree / your object |
| a Java object | it as TSON text | **`tson.objectWriter()`** | a `String` |
| a `TsonNode` tree | it as TSON text | **`tson.treeWriter()`** | a `String` |
| a data document | every problem, not the value | **`tson.validate()`** | a `List<Diagnostic>` |
| a data document | a grammar-faithful AST | **`TsonDataParser`** | a `Document` AST |
| a data document | to pull events lazily | **`TsonDataStream`** | a `TsonEvent` stream |

`tson.treeReader()` / `tson.objectReader()` and their writer peers `tson.objectWriter()` /
`tson.treeWriter()` are the facade doors on a built `Tson`: the readers take a *self-describing* document
and validate it against its own `!!schema` as they read, falling back to a schemaless read when it
declares none — the object form also checking your target class against the schema's root type up front.
`readWithoutSchema(…)` opts a reader back out to a pure schemaless read. When your *data* isn't
self-describing but you hold the schema out of band, compile it once through a registry
(`tson.treeRegistry()`/`bindRegistry()`) and reuse the per-type `TsonValueReader`. All of these stream
their input — a large document is never fully buffered before reading begins — and take a `String` or an
`InputStream`.

**No `Tson`?** The reader and writer classes construct directly for lightweight, schemaless (Class 1) use
with no standard-library bootstrap — `new TsonTreeReader()`, `new TsonObjectReader()`, `new
TsonObjectWriter()`, `new TsonTreeWriter()` — where a directly-built reader ignores any `!!schema` and
binds to the wire, or your Java class, alone (Jackson-style). That's what the examples below do.
`TsonDataParser` and `TsonDataStream` are always standalone and schemaless.

> **Try any of these without a project or build tool.** Each of the five numbered examples below is a
> runnable single-file Java 25 program in [`examples/`](examples/) that loads the library over the
> module system. Build the module path once — `./gradlew :tson:modules` — then run any of them with
> `java --module-path tson/build/modules --add-modules io.ltr8.tson examples/<File>.java`.

### 1. Bind to a Java class — `TsonObjectReader`

Schemaless (Class 1). Records, `Map<K, V>`, `List<E>`, tuples, plain enums, sealed-interface unions,
and the whole built-in vocabulary (`!uuid`/`!ipv4`/`!date`/`!uint8`/…) all bind with no custom code —
your Java class is the shape the data is checked against, no schema document involved:

```java
import io.ltr8.tson.compiler.TsonObjectReader;

import java.net.Inet4Address;
import java.time.LocalDate;
import java.util.UUID;

record Server(String hostname, Inet4Address address, UUID id, LocalDate deployedOn) {}

Server server = new TsonObjectReader().read("""
        {
            hostname: "web-01"
            address: !ipv4 192.0.2.10
            id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
            deployedOn: !date 2026-01-15
        }""", Server.class);
// Server[hostname=web-01, address=/192.0.2.10, id=9f1c8e2a-…, deployedOn=2026-01-15]
```

▶ Runnable: [`examples/ObjectBinding.java`](examples/ObjectBinding.java) — `java --module-path tson/build/modules --add-modules io.ltr8.tson examples/ObjectBinding.java`

`read` also takes an `InputStream`, streaming the file rather than buffering it whole:

```java
try (var in = Files.newInputStream(Path.of("server.tn"))) {
    Server server = new TsonObjectReader().read(in, Server.class);
}
```

On a mismatch it throws `TsonReadException` (fail-fast). To collect *every* problem in one pass instead
of stopping at the first, hand it a collecting context:

```java
var ctx = TsonReadContext.collecting("{ hostname: 1  address: nope }");
new TsonObjectReader().read(ctx, Server.class);
for (Diagnostic d : ctx.diagnostics()) {
    System.out.println(d.path() + ": " + d.message());   // /hostname: …, /address: …
}
```

### 2. Walk a generic tree — `TsonDataParser`

No schema, no target class: parse the document into an AST you can navigate. Good for tooling,
transformation, or when you don't know the shape ahead of time.

```java
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.ast.*;

Document doc = new TsonDataParser("{ name: \"Ada\"  tags: [a b c] }").parseDocument();
CoreValue root = doc.root().coreValue();   // RecordValue | MapValue | ArrayValue | TokenValue | …
```

▶ Runnable: [`examples/TreeWalk.java`](examples/TreeWalk.java) — `java --module-path tson/build/modules --add-modules io.ltr8.tson examples/TreeWalk.java`

### 3. Pull events lazily — `TsonDataStream`

The tier below the AST: a pull-based event stream (`RecordStart`, `FieldName`, `TokenEvent`,
`ArrayStart`, …), never materializing a tree. Memory held is proportional to nesting depth, not
document size — the right tool for very large documents or a custom consumer.

```java
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.stream.TsonEvent;

var stream = new TsonDataStream("{ name: \"Ada\" }");
while (stream.hasNext()) {
    TsonEvent event = stream.next();   // DocumentStart, RecordStart, FieldName, TokenEvent, …
}
```

▶ Runnable: [`examples/EventStream.java`](examples/EventStream.java) — `java --module-path tson/build/modules --add-modules io.ltr8.tson examples/EventStream.java`

### 4. Validate against a TSON schema — `TsonValueReader`

This is where the *schema layer* comes in: a schema document declares types, and a data document is
validated against one of them. `Tson.builder().build()` bootstraps the standard library
(meta-kernel/meta.tn/core.tn); `compile` turns your schema into fast, reusable per-type readers; then
you `get` the reader for a type and `read` data against it. **Tree mode** produces an immutable,
queryable `TsonNode` — structure-preserving (record vs map, array vs tuple), typed leaves, null-safe
navigation, and no Java class per schema type:

```java
import io.ltr8.tson.Tson;
import io.ltr8.tson.tree.TsonNode;
Tson tson = Tson.builder().build();

String schema = """
        !!id:"https://example.com/2026/32/app/server-1.tn"
        !!meta:"https://tson.io/2026/32/m/meta.tn"
        !!import:"https://tson.io/2026/32/m/core.tn"
        {
            server => { hostname: text  port: int32 }
        }""";

var compiled = tson.treeRegistry().compile(tson.resolve(schema));

TsonNode value = compiled.get("server").read("{ hostname: \"web-01\" port: 8080 }");
value.get("hostname").asString();          // Optional[web-01] — validated against the schema
value.get("port").asBigInteger();          // Optional[8080] — a bad port would surface as a diagnostic
```

▶ Runnable: [`examples/SchemaValidation.java`](examples/SchemaValidation.java) — `java --module-path tson/build/modules --add-modules io.ltr8.tson examples/SchemaValidation.java` (also shows collecting a diagnostic)

**Bind mode** (`TsonSchemaCompiler.bind(dataBindContext)`) produces bound Java objects the same way
`TsonObjectReader` does, but with the TSON schema — not your Java class — as the source of truth. The
full multi-stage pipeline behind this (parse → resolve → link → register → compile → read), and how a
schema governed by meta.tn/core.tn is assembled, is described under [Schema pipeline](#schema-pipeline)
below.

### 5. Read a self-describing document — `tson.treeReader()`

Sections 1–4 make *you* choose the reader; this one lets the **document** choose. A schema-aware
`TsonTreeReader` (or `TsonObjectReader`), obtained from a configured `Tson` facade rather than
constructed directly, reads a document that declares its own `!!schema`, resolves that schema through
your `TsonSchemaSource`, and validates against it — falling back to a schemaless read when the document
declares none. It's the value-returning peer of `tson.validate`:

```java
Tson tson = Tson.builder()
        .schemaSource(uri -> schema)       // the `server` schema from §4, served on demand by URI
        .build();

// Self-describing: it names its own schema and root type — no other arguments needed.
TsonNode server = tson.treeReader().read("""
        !!schema:"https://example.com/2026/32/app/server-1.tn"
        !server { hostname: "web-01"  port: 8080 }""");        // validated as it builds the tree

// No !!schema? The same reader reads schemalessly, straight off the wire.
TsonNode raw = tson.treeReader().read("{ hostname: \"db-01\"  port: 5432 }");
```

`tson.objectReader().read(doc, Server.class)` is the object-binding twin — it additionally checks your
target class against the schema's root type up front, before reading. `readWithoutSchema(…)` opts either
one back out to a pure schemaless read.

▶ Runnable: [`examples/SchemaAwareRead.java`](examples/SchemaAwareRead.java) — `java --module-path tson/build/modules --add-modules io.ltr8.tson examples/SchemaAwareRead.java`

### Writing TSON back out — `TsonObjectWriter` / `TsonTreeWriter`

`TsonObjectWriter` is the inverse of `TsonObjectReader`: a Java object to TSON text. Mainly a debugging
aid, not a guaranteed-lossless serializer (see [CONFORMANCE.md](CONFORMANCE.md) for exactly where it's
lossy). It throws unchecked `TsonWriteException` on failure, symmetric to the reader's
`TsonReadException` — no checked exceptions on either side of the object-binding pair:

```java
String text = new TsonObjectWriter().toTson(server);
// { hostname: "web-01" address: !ipv4 "192.0.2.10" id: !uuid "9f1c8e2a-…" deployedOn: !date "2026-01-15" }
```

`TsonTreeWriter` is the inverse of `TsonTreeReader`: a `TsonNode` tree back to TSON text. Because the
tree keeps each node's own type-ref, it's closer to lossless than the object writer — an
`AtomNode(42, "int32")` writes back as `!int32 42`, so an integer width survives a read/edit/write round
trip (the object writer, holding only a bound `long`, has no way to recover it):

```java
String text = new TsonTreeWriter().toTson(node);
```

---

## Status

This is the **first implementation** of TSON, built against a working-draft spec (Part 1 data format
and Part 2 schema layer, 2026 revision). Part 1 and most of Part 2 — schema grammar, resolution,
linking/registration, and a compiled schema-validating reader — are implemented; some Part 2 constructs
are still out of scope. Details live in dedicated docs rather than crowding this page:

- **[STATUS.md](STATUS.md)** — the full implemented / not-yet-implemented checklist
- **[CONFORMANCE.md](CONFORMANCE.md)** — edge-case behavior where a JDK parser and the RFC/ISO standard the spec cites disagree
- **[BACKLOG.md](BACKLOG.md)** — the actively-tracked engineering backlog
- **[STRUCTURED-OUTPUT.md](STRUCTURED-OUTPUT.md)** — the target-use-case plan (LLM structured-output validation, JSON compatibility)
- **[SPEC-FEEDBACK.md](SPEC-FEEDBACK.md)** — ambiguities and errors found in the spec while implementing
- **[CLAUDE.md](CLAUDE.md#architecture)** — architecture and design notes

## Schema pipeline

The [reader table](#reading-and-writing-tson-choosing-an-entry-point) above binds data against a plain Java class
with no schema involved — that's Class 1 (§1.5), TSON's schemaless mode. Part 2 layers a schema
*system* on top: a governing document that declares types, which a data document can then be validated
against. Turning schema source text into something a data reader can actually use goes through a
handful of well-defined stages, each with its own class, deliberately named after standard compiler
vocabulary — parse → resolve → link → register → compile → read:

1. **Parse** (`TsonSchemaParser`) — schema document text into a faithful AST (`SchemaDocument`):
   records, compositions (`&`), refinements (`^`), type references, and so on. No interpretation yet —
   a name is still just a name.
2. **Resolve** (`TsonSchemaResolver`) — one declaration at a time, the AST becomes a concrete
   `TypeDefinition`: composition induces supertypes and flattens fields, refinement tightens an
   inherited field against a state-transition table, a constructor application (`!C value`) transfers
   its constructor's own kind, and so on. A reference to another declaration is still a bare, unverified
   name at this point.
3. **Link** (`TsonSchemaLinker`) — the whole-schema pass: merges `!!import`s, flattens every
   argument-bearing type reference (e.g. an `array<token>` application) into a real, synthesized entry,
   and validates that every reference in the schema actually resolves to something. Produces a
   `TsonLinkedSchema` — proof, in the type system, that this pass has run, not a runtime flag to
   remember to check.
4. **Register** (`TsonSchemaRegistry`) — stores a linked schema under its own canonical `!!id` (only
   ever accepts a `TsonLinkedSchema` — there's no way to register something that skipped linking), so a
   later schema can find it via its own `!!import`/`!!meta`. Once registered, a schema is locked: never
   mutated or removed.
5. **Compile** (`TsonSchemaCompiler`) — turns a registered schema's `Map<String, TypeDefinition>` into
   a `TsonCompiledSchema`: real Java object references between per-type parsers rather than further
   name lookups, built once, reused for every document read against it.
6. **Read** (`TsonValueReader`) — the compiled schema validates and binds actual TSON *data* documents
   against one of its own types — the schema-validating reader (Class 2) that the schemaless
   `TsonObjectReader`/`TsonDataParser` don't attempt on their own.

`Tson.builder().build()` wires all of this together for the standard library and hands you a `Tson`
whose `resolve`/`compile` run the pipeline for you (as shown in the [reader table](#reading-and-writing-tson-choosing-an-entry-point)'s
schema example) — so you rarely touch the individual stages directly. Under the hood, resolving and
linking a schema both need its own *governing* schema already compiled, to resolve constructor names
like `!enum`/`!integer_type` against — including meta-kernel itself, whose own `!!meta` names *itself*
(§1.5's "one deliberate circularity in the series"), closed by pre-loading a hand-written bootstrap
(`MetaKernelBootstrapResolver`) rather than resolving it the ordinary way. See
[CLAUDE.md](CLAUDE.md#architecture) for the full walkthrough, including how meta-kernel/meta.tn/core.tn
are loaded and registered together.

There's no polished, single-call "load a *custom* governing chain" entry point yet (see
[BACKLOG.md](BACKLOG.md)) — `Tson` today assumes a schema governed by the standard
meta-kernel/meta.tn/core.tn library.

## Command-line interface

The `tson-cli` module is a small, zero-dependency CLI (ajv-cli-style) for checking TSON from the shell —
no Java to write. Three commands: **`init-example`** scaffolds an example schema + data file to start
from, **`validate`** checks data files (each against the schema its own `!!schema` names, or
schemalessly), and **`compile`** checks that a
schema document itself resolves and compiles cleanly.

Build and install it — the installed command is `tson`:

```
./gradlew :tson-cli:installDist
tson-cli/build/install/tson/bin/tson --help        # or -h, or `tson help`
```

(or, without installing, `./gradlew :tson-cli:run --args="compile schema.tn"`.) The examples below
write `tson` for that launcher path.

```
tson init-example [<dir>]
tson validate     [--output text|json|tson] <file>...
tson compile      [--output text|json|tson] <schema>
tson hash         <file>
```

**`tson hash`** computes a document's content hash ([TSON-DATA] §2.2.1 — SHA-256 of every byte after
the `!!id` line) and stamps it onto the `!!id` as `?sha256=<hex>`, in place. Requires an `!!id`; the id
line is excluded from the hash, so a document can carry its own. A pinned reference is then verified on
use: if a data file's `!!schema` (or a schema's `!!import`/`!!meta`) carries `?sha256=…`, `validate`
hashes the referenced content and errors on a mismatch (the pin is matched by canonical identity, so a
pinned reference and a plain one still resolve to the same schema).

**`validate` takes a flat list of files** and auto-classifies each as a schema (its header carries
`!!meta`) or a data document. A data file's own `!!schema` directive selects which schema it's
validated against and its root type-ref (`!person`) selects the type — the schema files are just made
available, so order doesn't matter and you can pass several of each. A data file with **no `!!schema`**
is checked *schemalessly*: base syntax plus any built-in type (`!uuid`/`!int32`/`!date`/…), with a
non-built-in type-ref reported as unknown.

For a hand-written schema `person.tn` and a self-describing data file `ada.tn`:

```tson
!!id:"https://example.com/2026/32/app/person-1.tn"
!!meta:"https://tson.io/2026/32/m/meta.tn"
!!import:"https://tson.io/2026/32/m/core.tn"
{
    person => { name: text  age: int32 }
}
```

```
$ tson validate person.tn ada.tn      # ada.tn = !!schema:"…/person-1.tn" !person { name: "Ada" age: 30 }
OK

$ tson validate --output json person.tn bad.tn   # bad.tn = !!schema:"…/person-1.tn" !person { age: 30 }
{"valid":false,"errors":[{"path":"/name","code":"FIELD_REQUIRED",
  "message":"missing required field 'name' for 'person'","expected":"a value for 'name'",
  "actual":"(absent)","dataPosition":"2:1:…","schemaPosition":null}]}

$ tson compile person.tn
OK
```

- **Schema selection** is entirely the data's own doing: its `!!schema` names the schema and its root
  type-ref (`!person`) names the type. If a data file's `!!schema` names a schema you didn't pass,
  that's a `SCHEMA_ERROR`. There's no URL *fetching* — schemas come from the files you list (a
  whitelisted-URI source is future work).
- **`--output`**: `text` (default, human-readable), `json` (for scripts/agents — the shape aligns with
  Pydantic's own `errors()`), or `tson` (the diagnostics rendered as a real, schema-validated TSON
  document — the CLI dogfooding the library).
- **Exit codes** are Unix-conventional: `0` valid/compiled, `1` a real validation/compile failure,
  `2` a usage error (bad arguments, an unreadable file) — so a script gets a clean pass/fail without
  parsing prose. `validate` collects *every* problem in a file in one pass, not just the first.

## Requirements

- Java 25
- No external runtime dependencies. JUnit (Jupiter) is used for tests only.

## Build and test

```
./gradlew build
./gradlew test
```

For the command-line tool, see [Command-line interface](#command-line-interface) above.

## Related

- [ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite) — language-agnostic
  conformance test vectors for any TSON implementation, including this one. If checked out as a sibling
  directory (`../ltr8-io-tson-test-suite`), `ConformanceSuiteTest` runs every vector against this
  implementation's real lexer and parser; it's skipped, not failed, if the sibling isn't present (CI
  doesn't check it out).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
