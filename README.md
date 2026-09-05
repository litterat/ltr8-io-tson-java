# tson-java

A Java implementation of [TSON](https://tson.io) (Typed Schema Object Notation) — a schema system with immutable, hash-pinned
schemas whose definitions are themselves data. A document names its schema, the schema names its meta-schema;
one hash verifies the whole chain. The finishing touch, TSON's data format is a Unicode-first notation you'll
actually enjoy writing — JSON-like in shape, and not a superset of it.

> **Status:** first implementation of TSON, built against a working-draft spec — revision 34
> published, with this branch carrying the proposed 2026 revision 35 artifacts. Part 1 (data format) and most of Part 2 (schema layer) are implemented; the
> API and the format itself may still change. See [STATUS.md](STATUS.md) for the full
> checklist.

**Reads from a stream, not a string.** Every facade reader takes an `InputStream` and pulls events through
it (`TsonDataStream`), so memory is proportional to nesting depth rather than document size — worth naming
now that JSON is arriving in the JDK itself, where JEP 540 states the opposite as a deliberate choice: "We
assume that input JSON documents can fit in memory … if we were to allow JSON sources such as files or
network connections, issues such as insufficient memory would be possible with large documents." Streaming
is one of four things here that JEP 540 names as non-goals; schemas, binding and collected diagnostics are
the others.

**Requires Java 25.** No external runtime dependencies. **Not yet published to Maven Central** — to use it
from another project on the same machine, see [Use it from another project](#use-it-from-another-project).
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
including records, record groups, enums and some in-built types. The `2026/35` in the
URIs is the draft year/revision marker from the spec's release scheme.

```tson
!!id:"https://example.com/2026/35/getting-started/person.tn?sha256=cee2ba09e2fe88a375e6294a7f176dfcae3bbc1393c43fe37333d5459ddde3c7"
!!meta:"https://tson.io/2026/35/m/meta.tn?sha256=703d978e6f6a09b0f15be54ca36db7eb224ccab5e805d37b51f6ed89da839a4e"
!!import:"https://tson.io/2026/35/m/core.tn?sha256=9ff840296fb5995cb40a8ae2d77ddcb16135134395b387c5c1ac0476510f3faa"
@doc:"An example schema from `tson init-example` -- a short tour of TSON. Edit this file or person-data.tn, then re-run tson validate to see what changes."
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
!!schema:"https://example.com/2026/35/getting-started/person.tn"
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

If the schema is wrong, you get **every** problem in one pass — each naming the declaration it came from
and where that declaration is in the file, the same treatment `tson validate` gives a data document:

```
$ tson compile broken.tn
[SCHEMA_ERROR] /a (5:3:132): 'a' field 'x' has an unresolved reference 'no_such_type'
[SCHEMA_ERROR] /b (6:3:159): 'b' field 'y' has an unresolved reference 'also_missing'
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
| a data document | a queryable tree (validated if it self-describes) | **`tson.treeReader()`** | a `TsonValue` tree |
| a data document + a schema you hold | it validated as a named type | **`.withSchema(uri).readAs(…)`** on either reader | a tree / your object |
| a Java object | it as TSON text | **`tson.objectWriter()`** | a `String`, or written to a sink |
| a `TsonValue` tree | it as TSON text | **`tson.treeWriter()`** | a `String`, or written to a sink |
| either of those | it self-describing (`!!schema` + root type) | **`.describing(…)`** on either writer | a document that reads back on its own |
| a data document | every problem, not the value | **`tson.validate()`** | a `List<Diagnostic>` |
| a *schema* document | every problem with the schema itself | **`tson.validateSchema()`** | a `List<Diagnostic>` |
| a data document | the value **and** every problem | **`.withDiagnostics(…)`** on either facade reader | the value + a `List<Diagnostic>` |
| a data document | only what it *declares* — before reading it | **`TsonDocumentHeader.peek(…)`** | its `!!id` / `!!schema` (or `!!meta`) |
| a data document | a grammar-faithful AST | **`TsonDataParser`** | a `Document` AST |
| a data document | to pull events lazily | **`TsonDataStream`** | a `TsonEvent` stream |
| nothing yet | to emit TSON without building a tree or object first | **`TsonDataEmitter`** | text pushed to any `Appendable` |

`tson.treeReader()` / `tson.objectReader()` and their writer peers `tson.objectWriter()` /
`tson.treeWriter()` are the facade doors on a built `Tson`: the readers take a *self-describing* document
and validate it against its own `!!schema` as they read, falling back to a schemaless read when it
declares none — the object form also checking your target class against the schema's root type up front.
`readWithoutSchema(…)` opts a reader back out to a pure schemaless read. When your *data* isn't
self-describing but you hold the schema out of band, `withSchema(uri).readAs(source, type)` supplies what
the document didn't say. All of these stream their input — a large document is never fully buffered before
reading begins — and take a `String` or an `InputStream`. The writers mirror that: `write(value, out)` takes
an `OutputStream` or any `Appendable` and emits as it goes, with `toTson(…)` the same call over a buffer.

A schemaless read still holds a `!type-ref` to account, since it is the only contract on offer: a built-in
name (`!uuid`, `!int32`, `!date`) must sit on a scalar and satisfy that type, and any other name must name
the class you are binding to — one that names neither is reported, so a typo like `!Uuid` doesn't quietly
disable the check you asked for. Add `preservingUnknownTypeRefs()` when you *want* names carried through
uninterpreted: reading the raw wire of a document whose schema you're deliberately ignoring, or
round-tripping a tree back out through `tson.treeWriter()`.

**These two readers are the whole document-reading surface.** They own the `!!schema` decision, the
target-class check, and the framing that rejects trailing content. `tson.treeRegistry()`/`bindRegistry()`
and the `TsonTypeReader` they hand back for a named type are the layer underneath — useful for compiling a
schema once and inspecting it, but `TsonTypeReader` is a strict single-method interface (`T
read(TsonReadContext ctx)`) that reads *one value at a cursor* and polices nothing around it.

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
of stopping at the first, derive a reader with a collecting `TsonDiagnosticsReceiver` — you get the
(possibly partial) value back *alongside* the full list, rather than one or the other:

```java
var problems = TsonDiagnosticsReceiver.collecting();

Server server = new TsonObjectReader()
        .withDiagnostics(problems)
        .read("{ hostname: 1  address: nope }", Server.class);

for (Diagnostic d : problems.diagnostics()) {
    System.out.println(d.path().orElse("") + ": " + d.message());   // /hostname: …, /address: …
}
```

A receiver sees **every** problem with the document — a value the schema rejects, an unresolvable
`!!schema`, and a document that will not lex or parse — so a collecting read never throws for a bad
document. Only a fault in the library throws past it. (Fail-fast reads still throw at the first problem;
for a base-syntax failure that is a `TsonReadException` carrying the diagnostic, position included.)

`withDiagnostics` returns a *new* reader and leaves the original fail-fast; it works the same on
`TsonTreeReader` and on the schema-aware readers from `tson.treeReader()`/`objectReader()`.

The two built-ins are not the interesting part. `TsonDiagnosticsReceiver` is a plain `void
report(Diagnostic)` sink — one method — so a caller wanting neither behaviour implements it directly. It is
called as problems are found, not at the end, which is what makes streaming and capping possible at all:

```java
// Report as they arrive, and stop keeping them after twenty.
final class Capped implements TsonDiagnosticsReceiver {
    private final List<Diagnostic> kept = new ArrayList<>();

    @Override
    public void report(Diagnostic d) {
        if (kept.size() < 20) {
            kept.add(d);
            System.err.printf("%s %s: %s%n", d.code(), d.path().orElse("/"), d.message());
        }
    }

    List<Diagnostic> diagnostics() {
        return List.copyOf(kept);
    }
}
```

A `Diagnostic` locates itself at both ends — `path()` into the document, `schemaId()` with
`schemaPointer()` into the schema that rejected it, and a position for each — so a receiver has what it
needs to render, group or route without holding on to the document.

It composes with `withSchema(…)` too, so an out-of-band read (§4 below) collects the same way:

```java
var problems = TsonDiagnosticsReceiver.collecting();
var value = tson.treeReader().withSchema(SERVER_ID).withDiagnostics(problems).readAs(source, "server");
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

### 4. Validate against a schema you hold out of band — `withSchema`/`readAs`

This is where the *schema layer* comes in: a schema document declares types, and a data document is
validated against one of them. Section 5 covers the usual case, where the *document* names its own schema.
This one is for when it doesn't — you hold the schema yourself and say which type applies. `resolve`
registers the schema by its `!!id`; `withSchema` points a reader at it; `readAs` names the type:

```java
import io.ltr8.tson.Tson;
import io.ltr8.tson.tree.TsonValue;
Tson tson = Tson.builder().build();

String schema = """
        !!id:"https://example.com/2026/35/app/server-1.tn"
        !!meta:"https://tson.io/2026/35/m/meta.tn"
        !!import:"https://tson.io/2026/35/m/core.tn"
        {
            server => { hostname: text  port: int32 }
        }""";

tson.resolve(schema);

TsonValue value = tson.treeReader()
        .withSchema("https://example.com/2026/35/app/server-1.tn")
        .readAs("{ hostname: \"web-01\"  port: 8080 }", "server");

value.get("hostname").asString();          // Optional[web-01] — validated against the schema
value.get("port").asInt();                 // OptionalInt[8080] — a bad port would surface as a diagnostic
```

You supply exactly what a `!!schema` directive and a root type-ref would have said, and the validation is
identical either way. `tson.objectReader().withSchema(…).readAs(source, "server", Server.class)` is the
object-binding twin, and both compose with `withDiagnostics(…)` to collect instead of throwing.

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
        // Schemas you already hold, keyed by identity. Not `schemas::get` -- a source says "I cannot
        // supply that" by throwing, where a map returns null, for whichever identity the document names.
        .schemaSource(TsonSchemaSource.ofMap(                 // the `server` schema from §4
                Map.of("https://example.com/2026/35/app/server-1.tn", schema)))
        .build();

// Self-describing: it names its own schema and root type — no other arguments needed.
TsonValue server = tson.treeReader().read("""
        !!schema:"https://example.com/2026/35/app/server-1.tn"
        !server { hostname: "web-01"  port: 8080 }""");        // validated as it builds the tree

// No !!schema? The same reader reads schemalessly, straight off the wire.
TsonValue raw = tson.treeReader().read("{ hostname: \"db-01\"  port: 5432 }");
```

`tson.objectReader().read(doc, Server.class)` is the object-binding twin — it additionally checks your
target class against the schema's root type up front, before reading. `readWithoutSchema(…)` opts either
one back out to a pure schemaless read.

▶ Runnable: [`examples/SchemaAwareRead.java`](examples/SchemaAwareRead.java) — `java --module-path tson/build/modules --add-modules io.ltr8.tson examples/SchemaAwareRead.java`

### 6. Read only the header — `TsonDocumentHeader.peek`

Sometimes the routing decision comes *before* the read: which schema version governs this body, is this
upload a schema document or a data one, which handler gets it. [TSON-DATA] §7.1 is explicit that
classification "requires at most two directives of lookahead and no value parsing, so streams, previews,
and content sniffers can classify a document from its opening bytes" — `peek` is that door. It reads
`!!id` and `!!schema` (or `!!meta`, which means the document is a *schema* document) and stops before the
value:

```java
TsonDocumentHeader header = TsonDocumentHeader.peek(text);   // or an InputStream
if (header.isSchemaDocument()) { … }                       // it carries !!meta
String schemaUri = header.schema().orElse(DEFAULT_SCHEMA);  // route on what it names
```

It is **total**: a header it cannot read yields nothing rather than throwing, and what it never does is
answer with a schema the document does not name — a `!!schema` written *inside* the value is that value's
text. Whatever is wrong with the document is reported properly by the read that follows.

A peek reads from an `InputStream` without rewinding it, which is fine when the source can be opened twice.
When it can't — an HTTP request body, a socket — `peekResumable` hands the document back whole: the bytes
it buffered in front of the rest of the stream, so the reader that follows sees the document from its first
byte, header included.

```java
TsonDocumentPeek peeked = TsonDocumentHeader.peekResumable(request.getInputStream());
TsonValue value = tson.treeReader()
        .withSchema(versionFor(peeked.header()))
        .readAs(peeked.document(), "order");
```

Only the read-ahead is buffered (one decoder chunk), never the document — a 500 KB body still streams.

▶ Runnable: [`examples/DocumentRouting.java`](examples/DocumentRouting.java) — `java --module-path tson/build/modules --add-modules io.ltr8.tson examples/DocumentRouting.java`

### Writing TSON back out — `TsonObjectWriter` / `TsonTreeWriter`

`TsonObjectWriter` is the inverse of `TsonObjectReader`: a Java object to TSON text. Mainly a debugging
aid, not a guaranteed-lossless serializer (see [CONFORMANCE.md](CONFORMANCE.md) for exactly where it's
lossy). It throws unchecked `TsonWriteException` on failure, symmetric to the reader's
`TsonReadException` — no checked exceptions on either side of the object-binding pair:

```java
String text = new TsonObjectWriter().toTson(server);
// { hostname: "web-01" address: !ipv4 "192.0.2.10" id: !uuid "9f1c8e2a-…" deployedOn: !date "2026-01-15" }
```

`TsonTreeWriter` is the inverse of `TsonTreeReader`: a `TsonValue` tree back to TSON text. Because the
tree keeps each node's own type-ref, it's closer to lossless than the object writer — an
`TsonAtom(42, "int32")` writes back as `!int32 42`, so an integer width survives a read/edit/write round
trip (the object writer, holding only a bound `long`, has no way to recover it):

```java
String text = new TsonTreeWriter().toTson(node);
```

Both writers also take a sink, so a document never has to exist as a `String` — the write-direction mirror
of every reader taking an `InputStream`. The stream is written as UTF-8, flushed, and **not closed**: it is
the caller's, which is what makes an HTTP response body the natural case.

```java
new TsonObjectWriter().write(server, response.getOutputStream());   // or any OutputStream
new TsonTreeWriter().write(node, appendable);                       // or any Appendable
```

`toTson` is that method over a `StringBuilder`, for when you do want the whole document in hand.

**Self-describing output.** By default a writer emits a bare value. `describing(...)` adds the header the
readers already honour, so the document says what governs it and a receiver needs nothing out of band —
which is what makes a TSON response body self-describing in both directions:

```java
String body = tson.objectWriter().describing(schemaUri, "person").toTson(person);
// !!schema:"https://example.com/person.tn"
// !person { name: "Ada" age: 36 }

tson.validate(body);            // [] — resolves the schema and the type from the bytes alone
```

A bound object carries neither fact, so the object writer takes both: `!!schema` alone would produce a
document whose reader answers *"declares a !!schema but has no root type-ref to select a type"*. A
`TsonValue` already knows its own type, so `treeWriter().describing(schemaUri)` takes just the URI.
`identifiedBy(documentId)` adds `!!id`. All three are derivations — the writer you called them on is
unchanged, and default output is exactly what it was.

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
- **[CLAUDE.md](CLAUDE.md)** — orientation and conventions, with the detailed per-area design notes in [docs/](docs/)

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
2. **Desugar** (`SchemaDesugarer`) — an AST→AST rewrite turning every sugar form (`[T]`, `[T; 1..5]`,
   `[T, U]`, `{K => V}`, `(A | B)`) into the constructor application it denotes, off a fixed table. At a
   declaration's own position the form simply *is* that application; anywhere else it becomes an injected
   declaration plus a bare reference to it, so identical forms anywhere in the document share one entry.
3. **Resolve** (`TsonSchemaResolver`) — one declaration at a time, the AST becomes a concrete
   `TypeDefinition`: composition induces supertypes and flattens fields, refinement tightens an
   inherited field against a state-transition table, a constructor application (`!C value`) transfers
   its constructor's own kind, and so on. A reference to another declaration is still a bare, unverified
   name at this point.
4. **Link** (`TsonSchemaLinker`) — the whole-schema pass: merges `!!import`s, populates `subtypes`, derives
   choice disjointness, and validates that every reference in the schema actually resolves to something.
   Produces a `TsonLinkedSchema` — proof, in the type system, that this pass has run, not a runtime flag to
   remember to check.
5. **Register** (`TsonSchemaRegistry`) — stores a linked schema under its own canonical `!!id` (only
   ever accepts a `TsonLinkedSchema` — there's no way to register something that skipped linking), so a
   later schema can find it via its own `!!import`/`!!meta`. Once registered, a schema is locked: never
   mutated or removed.
6. **Compile** (`TsonSchemaCompiler`) — turns a registered schema's `Map<String, TypeDefinition>` into
   a `TsonCompiledSchema`: real Java object references between per-type parsers rather than further
   name lookups, built once, reused for every document read against it.
7. **Read** (`TsonTypeReader`) — the compiled schema validates and binds actual TSON *data* documents
   against one of its own types — the schema-validating reader (Class 2) that the schemaless
   `TsonObjectReader`/`TsonDataParser` don't attempt on their own.

`Tson.builder().build()` wires all of this together for the standard library and hands you a `Tson`
whose `resolve`/`compile` run the pipeline for you (as shown in the [reader table](#reading-and-writing-tson-choosing-an-entry-point)'s
schema example) — so you rarely touch the individual stages directly. Under the hood, resolving and
linking a schema both need its own *governing* schema already compiled, to resolve constructor names
like `!enum`/`!integer_type` against — including meta-kernel itself, whose own `!!meta` names *itself*
(§1.5's "one deliberate circularity in the series"), closed by pre-loading a hand-written bootstrap
(`MetaKernelBootstrapResolver`) rather than resolving it the ordinary way. See
[docs/schema-resolution.md](docs/schema-resolution.md) for the full walkthrough, including how
meta-kernel/meta.tn/core.tn are loaded and registered together.

There's no polished, single-call "load a *custom* governing chain" entry point yet (see
[BACKLOG.md](BACKLOG.md)) — `Tson` today assumes a schema governed by the standard
meta-kernel/meta.tn/core.tn library.

## Command-line interface

The `tson-cli` module is a small, zero-dependency CLI (ajv-cli-style) for checking TSON from the shell —
no Java to write. Four commands: **`init-example`** scaffolds an example schema + data file to start
from, **`validate`** checks data files (each against the schema its own `!!schema` names, or
schemalessly), **`compile`** checks that a
schema document itself resolves and compiles cleanly, and **`policy`** prints what this build would judge a
document by — the Unicode name/value policy and the resource limits, the two things that can make the same
document pass here and fail elsewhere.

Build and install it — the installed command is `tson`:

```
./gradlew :tson-cli:installDist
tson-cli/build/install/tson/bin/tson --help        # or -h, or `tson help`
```

(or, without installing, `./gradlew :tson-cli:run --args="compile schema.tn"`.) The examples below
write `tson` for that launcher path.

```
tson init-example [<dir>]
tson validate     [--output text|json|tson] [<policy options>] <file>...
tson compile      [--output text|json|tson] [<policy options>] <schema>
tson policy       [--output text|json|tson] [<policy options>]
tson hash         <file>

policy options (validate, compile, policy):
  --identifier-policy <level>   level for identifiers (default: highly-restrictive)
  --identifier-per-segment      apply it per _/- segment rather than the whole identifier
  --identifier-scripts <A+B>    admit one script combination over the level (repeatable)
  --token-policy <level>        level for values (default: unrestricted, which scans nothing)
  --token-scripts <A+B>         the same for values (repeatable)
  --max-depth <n>               how deeply a document may nest before this refuses it (default: 64)
```

**`tson policy`** prints what this build applies — the [TSON-DATA] §8.2 restriction level for names and for
values, whether it applies per `_`/`-` segment, any script combinations specially admitted, the Unicode data
version behind them, and §9.1's resource limits:

```
$ tson policy
identifier policy: HIGHLY_RESTRICTIVE
token policy:      UNRESTRICTED
unicode data:      16.0
max depth:         64
```

**A limit refusal is not a verdict on your document.** A document nested deeper than `--max-depth` is
reported as `LIMIT_EXCEEDED` with the run's outcome `NOT_CHECKED`: it was never read, and a processor
configured for more would read it in full. (`tson validate` still exits 1, because at a command line you hold
the fix — raise the bound, or send something shallower.)

Every `validate`/`compile` report carries the same record in its `policy` field, so a refusal is always
readable beside what produced it. The useful direction is the other one: read the policy *before* you
generate, and you never write the name that would be refused.

**The policy options change it.** A `<level>` is one of UTS #39 §5.2's six — `ascii-only`, `single-script`,
`highly-restrictive`, `moderately-restrictive`, `minimally-restrictive`, `unrestricted` — and the spelling
`tson policy` prints (`HIGHLY_RESTRICTIVE`) is accepted too, so its output is usable as its input. §8.2
requires that relaxing a rule not be *silent*, which a flag in a CI file satisfies and an environment variable
would not; `--output text` accordingly prints the policy on any run that configured one, not only on a refusal.

```
$ tson compile names.tn                                        # `id_адрес => text` refused: mixes scripts
$ tson compile --identifier-per-segment names.tn               # OK: each _-delimited segment is one script
$ tson compile --identifier-scripts Latin+Cyrillic names.tn    # OK: the combination is named
```

Reach for the *unit* or a named combination before dropping a level — both keep the rule everywhere else.
`--token-scripts` on its own raises the token level from `unrestricted` to `single-script`, since a list of
combinations is no configuration at all under a level that scans nothing; naming a level that scans nothing
*and* a relaxation is a usage error rather than a silent no-op. There is no `--token-per-segment`: `_` and `-`
are ordinary characters in a value, so the library refuses such a policy outright. The flags apply to one
`Tson` per run, so a schema's declared names and your data's names are judged alike.

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

**`-` reads one data document from standard input**, so a generator can pipe a candidate straight in
rather than writing a temp file per attempt:

```
$ printf '!!schema:"…/person-1.tn"\n!person { name: "Ada" age: 30 }\n' | tson validate person.tn -
OK
```

It reports under the name `-`, and only the bare argument `-` means stdin — a file really called `-` is
reachable as `./-`. Schemas must be files: classification opens a document a second time and a stream has
nothing to reopen, so piped input is always treated as data.

For a hand-written schema `person.tn` and a self-describing data file `ada.tn`:

```tson
!!id:"https://example.com/2026/35/app/person-1.tn"
!!meta:"https://tson.io/2026/35/m/meta.tn"
!!import:"https://tson.io/2026/35/m/core.tn"
{
    person => { name: text  age: int32 }
}
```

```
$ tson validate person.tn ada.tn      # ada.tn = !!schema:"…/person-1.tn" !person { name: "Ada" age: 30 }
OK

$ tson validate --output json person.tn bad.tn   # bad.tn = !!schema:"…/person-1.tn" !person { age: 30 }
{"outcome":"INVALID","policy":{"identifier_policy":{"level":"HIGHLY_RESTRICTIVE","per_segment":false,
  "permitting":[]},"token_policy":{"level":"UNRESTRICTED","per_segment":false,"permitting":[]},
  "unicode_data_version":"16.0"},
  "files":[{"file":"bad.tn","outcome":"INVALID","errors":[{"path":"/name",
  "schema_pointer":"/person/name","schema_id":"example.com/2026/35/app/person-1.tn",
  "code":"FIELD_REQUIRED","message":"missing required field 'name' for 'person'",
  "expected":"a value for 'name'","actual":"(absent)","data_position":"2:9:63",
  "schema_position":"5:5:145"}]}],"errors":[]}

$ tson compile person.tn
OK
```

- **`--output json`/`tson` is one document per invocation**, one file or twenty: a `files` array with each
  data file's own `file`/`outcome`/`errors`, wrapped in the run's own outcome. Nothing to reassemble, and
  no branch on file count. The top-level `errors` carries only what stopped the run before any document
  was read (exit 2); a document that read but didn't validate reports inside its own entry (exit 1).
  `--output text` keeps the human-facing `# <file>` headers instead.
- **`outcome` is `VALID`, `INVALID` or `NOT_CHECKED`**, not a `valid` boolean, because those are two
  questions and one bit cannot carry both: a document whose schema was never obtained, or whose types have
  no Java class in this tool, was never read at all, and reporting it `valid: false` asserts a verdict the
  run cannot make — which is exactly the assertion an agent acts on when it reads `if (!valid)`.
  `NOT_CHECKED` is precisely the set of codes that are not a verdict (`Diagnostic.Code.verdict`): the five
  `SCHEMA_*` fetch codes, `BIND_MISMATCH` and `NOT_IMPLEMENTED`. One of them anywhere in a file makes that
  file `NOT_CHECKED`, and one such file makes the run `NOT_CHECKED`; a §8.2 name-hygiene refusal is
  `INVALID`, the processor having looked and declined with the sender holding the fix.
- **Both machine formats spell one report one way** — `snake_case` keys, and a field with nothing to say
  left out rather than written `null`. That is what `tson-cli`'s own `diagnostics.tn` declares, what
  `--output tson` always emitted, and what the TypeScript CLI emits in both of its formats.
- **A diagnostic locates a problem at up to two ends** — the value in the data (`path`, `data_position`)
  and the rule in the schema (`schema_id`, `schema_pointer`, `schema_position`) — and either end may be
  absent. The schema end is the path taken through *your* schema — an `age: int32` field that violates its
  bound reports `/person/age`, not `/int32` in core.tn, because a pointer into a library file you didn't
  write and can't edit is not where you go to fix it (the constraint is still in `message` and `expected`).
  A field with nothing to say is omitted, never `""`, the two RFC 6901 pointers included: for a pointer
  `""` is the *root*, a real location a document-level problem genuinely carries, so a present `""` and an
  absent key stay apart there. A position is `line:column:byteOffset`, the first two
  1-based and the offset counting UTF-8 bytes from 0. **Every field is a location, and the one fact that is
  not — why no schema was obtained — rides the `code` rather than a field beside it**: `SCHEMA_NOT_PERMITTED`
  and `SCHEMA_NOT_FOUND` mean the document named something this deployment will not fetch or nothing serves,
  where `SCHEMA_UNREACHABLE`, `SCHEMA_TIMEOUT` and `SCHEMA_TOO_LARGE` mean the reference itself was fine — and
  of those, only the first two are worth retrying. Five codes rather than one plus a reason field because
  which one it is is a *routing* question, and a code is what a consumer routes on; consumers partition the
  five differently (this CLI by whether a rerun could help, an HTTP surface by whose doing it was), so one
  code per reason privileges no partition. A §8.2 name-hygiene refusal is an ordinary
  diagnostic told apart by its code — `CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER` or `RESTRICTED_SCRIPT`, one
  per rule — and carries nothing extra; what judged it rides on the envelope (below). The whole shape is
  declared as a real schema in `tson-cli`'s own `diagnostics.tn`, which `--output tson` is validated
  against.
- **Every report states the policy it was judged under**, once, in `policy`: the Unicode level applied to
  names and to values, whether it applies per `_`/`-` segment, any script combinations specially admitted,
  and the Unicode data version behind all three. §8.2's name rules read data the Unicode Consortium does not
  freeze, at a level *this* deployment chose, so the same document can be refused here and accepted
  elsewhere — and that is the only place the reason lives, being in neither your document nor your schema.
  `tson policy` prints the same record with no document in hand, which is the useful direction: read it
  first and you never write the name that would be refused. From the library it is
  `Tson.processorPolicy()`, or `processorPolicy()` on the reader that did the judging.
- **`expected` is the constraint that failed, not a type name** — `<= 100`, `one of (PENDING, SHIPPED,
  DELIVERED)`, `at most 10 characters`, `an RFC 3339 date-time` — so a consumer building its own message
  (an LLM repair loop, say) never has to parse `message` to recover a bound or a member list.
- **Schema selection** is entirely the data's own doing: its `!!schema` names the schema and its root
  type-ref (`!person`) names the type. The CLI itself does no URL *fetching* — schemas come from the files
  you list, and one it can't match is `SCHEMA_NOT_FOUND` (exit 69), not a verdict on your data. The
  library has fetching sources (`TsonHttpSchemaSource`, `TsonFileSchemaSource`); wiring one into the CLI is
  separate.
- **`--output`**: `text` (default, human-readable), `json` (for scripts/agents — the shape aligns with
  Pydantic's own `errors()`), or `tson` (the diagnostics rendered as a real, schema-validated TSON
  document — the CLI dogfooding the library).
- **Exit codes** are Unix-conventional: `0` everything checked and nothing reported, `1` **checked and
  rejected**, `2` a usage error (bad arguments, an unreadable file), `69` (`EX_UNAVAILABLE`) a schema not
  obtained and a rerun will not obtain it (you didn't pass the schema file, or the reference is one this
  deployment refuses), `75` (`EX_TEMPFAIL`) a schema not obtained because a host didn't answer or timed out —
  the one worth running again, `78` (`EX_CONFIG`) a type the schema needs has no Java class in this tool, and
  `70` (`EX_SOFTWARE`) `tson` failing to reach a verdict at all — either a gap (`not implemented yet: …`,
  whose message usually names the way to write the thing today) or a bug, which prints its stack trace and
  asks for a report. Everything above `2` is deliberately kept distinct from `1` so a script never reads a
  crash, a missing binding, or a schema it never fetched, as "your document is invalid" — so a script gets a
  clean pass/fail without parsing prose. `1` includes a §8.2 name-hygiene refusal: the processor looked and
  declined, and the sender holds the fix. A mixed run is lifted to whichever code is most permanent —
  **`70` > `78` > `69` > `75` > `1`** — since rerunning reaches a gap again. `validate` collects
  *every* problem in a file in one pass, not just the first.

## Use it from another project

Not on Maven Central (see [Status](#status)), but the build publishes locally, which is all a project on
the same machine needs:

```
./gradlew publishToMavenLocal
```

That installs every module into `~/.m2/repository` under `io.ltr8`, with sources and javadoc jars beside
each one. A consuming Gradle build then takes an ordinary dependency:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.ltr8:tson:0.35.0-SNAPSHOT")
}
```

`io.ltr8:tson` is the front door and pulls in the rest; depend on a single module directly
(`io.ltr8:tson-regex`, say) if that is all you want. **The jars carry real `module-info.class`es**, so a
consumer works either way — plain classpath, or a `module-info.java` of its own:

```java
module my.app {
    requires io.ltr8.tson;            // Tson, TsonConfig
    requires io.ltr8.tson.compiler;   // Diagnostic, the readers and writers
    requires io.ltr8.tson.tree;       // TsonValue
}
```

Nothing is published to a remote repository, deliberately: releasing needs signed artifacts and a fuller
POM, and that is a separate decision. For the command-line tool, `./gradlew :tson-cli:installDist` (see
[Command-line interface](#command-line-interface)).

## Requirements

- Java 25
- No external runtime dependencies. JUnit (Jupiter) is used for tests only.

## Build and test

```
./gradlew build     # compiles, tests, and builds the javadoc/sources jars, so doclint runs here too
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
