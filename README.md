# tson-java

A Java implementation of [TSON](https://tson.io) (Typed Schema Object Notation) — a schema system with
its own text notation, extending JSON with richer structural types, optional annotations and type
annotations, and a layered resolution model that separates structural parsing from semantic
interpretation.

This is one implementation of an open specification, not the canonical one — anyone can implement TSON.
Published under the [litterat](https://github.com/litterat) org, group id `io.ltr8`.

> **New to TSON?** It's a superset of JSON's data model with two things JSON lacks: *type annotations*
> on the wire (`!ipv4 192.0.2.10`, `!uuid …`, `!date 2026-01-15`) and a first-class *schema layer*.
> A value can carry its own type (`!circle { radius: 5 }`), and separators are whitespace *or* commas,
> so a record reads like `{ name: "Ada"  age: 30 }`. Everything below is about reading that into Java.

---

## Getting started

TSON's schema and data notation are new — the quickest way to get a feel for them is the `tson`
command-line tool, no Java required. Requires **Java 25**, no external dependencies. Clone the repo,
build and install the command, and put it on your `PATH`:

```
git clone https://github.com/litterat/ltr8-io-tson-java.git
cd ltr8-io-tson-java
./gradlew :tson-cli:installDist
export PATH="$PWD/tson-cli/build/install/tson/bin:$PATH"
```

Java 25 is recent — if you don't have it, [SDKMAN!](https://sdkman.io) (`sdk install java 25-tem`)
or an [Adoptium](https://adoptium.net/temurin/releases/?version=25) build is the quickest way to get
it. Gradle itself needs no separate install: the checked-in `./gradlew` wrapper downloads the pinned
version (Gradle 9.4.1) on first run.

**`tson init-example`** scaffolds a working example — a schema and a matching data file — to start from:

```
$ tson init-example
Wrote ./person.tn and ./person-data.tn.

Try it:
  tson validate --type person ./person.tn ./person-data.tn
  …
```

Here's the whole of `person.tn` — a TSON *schema*. If you've seen JSON, most of this reads the way you'd
guess; the parts that don't are exactly what TSON adds:

```tson
!!id:"https://example.com/2026/32/getting-started/person-1.tn"
!!meta:"https://tson.io/2026/32/m/meta.tn"
!!import:"https://tson.io/2026/32/m/core.tn"
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

Reading top to bottom, most of it is familiar and a few things are new:

- **`role => !enum [admin member guest]`** — a named enum type: `role` is one of three fixed labels.
- **`address => { … }`** — a named *record* type (a nested object shape), reused as a field type in
  `person` below. Records are declared once and referenced by name.
- **built-in types** — `uuid`, `text`, `int32`, `date` are part of TSON's own vocabulary (via the
  imported `core.tn`); no need to model "a UUID" or "a date" as a string and validate it yourself.
- **`email: text?`** — the `?` makes a field *optional*. Everything else is required.
- **`skills: [text]`** — an array of text. `[T]` is array-of-`T`.
- **`( phone: text | mobile: text )?`** — a *field group*: "at most one of these" (with `?`; drop the
  `?` and it's "exactly one"). A record-level either/or with no JSON equivalent.

And here's `person-data.tn`, a *data* document — an instance of that shape:

```tson
{
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

Two data-notation things to notice versus JSON: values can carry their own *type annotation* on the
wire (`!uuid …`, `!date …`), and separators are whitespace *or* commas — the array `[ mathematics
analysis "computing" ]` and the record fields need no commas at all. Note `email` is simply absent
(it's optional), and only `mobile` is given, not `phone` (the group allows at most one).

**`tson validate`** reads the data against a type of the schema:

```
$ tson validate --type person person.tn person-data.tn
OK
```

Now break something — open `person-data.tn`, change `age: 30` to `age: "thirty"`, delete the `name`
line, use a `role` that isn't one of the three, or add `phone: "…"` alongside `mobile` (the group
allows at most one) — and run it again. Every problem is reported at once, with a path and a reason:

```
$ tson validate --type person person.tn person-data.tn
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
[Reading TSON](#reading-tson-choosing-an-entry-point) for reading TSON *from Java*.

---

## Reading TSON: choosing an entry point

There are two questions that pick your reader: **what drives the interpretation** (nothing, your Java
class, or a TSON schema document), and **what you want out** (a generic tree/stream, or a bound Java
object). That's the whole matrix:

| You have… | You want… | Use | You get |
|---|---|---|---|
| a Java class | it bound, no schema | **`TsonObjectReader`** | your object |
| nothing (schemaless) | a navigable tree | **`TsonDataParser`** | a `Document` AST |
| nothing (schemaless) | to pull events lazily | **`TsonDataStream`** | a `TsonEvent` stream |
| a TSON schema | validation + generic output | **`TsonValueReader`** (DOM mode) | `Map`/`List` |
| a TSON schema | validation + a bound object | **`TsonValueReader`** (bind mode) | your object |

The two "bound object" rows are mirror images: `TsonObjectReader` checks the data against your Java
class *reflectively* (the class is the schema); `TsonValueReader` checks it against a real TSON *schema
document*. Both stream their input — a large document is never fully buffered before reading begins —
and both accept a `String` or an `InputStream`.

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

### 4. Validate against a TSON schema — `TsonValueReader`

This is where the *schema layer* comes in: a schema document declares types, and a data document is
validated against one of them. `Tson.builder().build()` bootstraps the standard library
(meta-kernel/meta.tn/core.tn); `compile` turns your schema into fast, reusable per-type readers; then
you `get` the reader for a type and `read` data against it. **DOM mode** produces plain `Map`/`List`:

```java
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.TsonSchemaCompiler;

Tson tson = Tson.builder().build();

String schema = """
        !!id:"https://example.com/2026/32/app/server-1.tn"
        !!meta:"https://tson.io/2026/32/m/meta.tn"
        !!import:"https://tson.io/2026/32/m/core.tn"
        {
            server => { hostname: text  port: int32 }
        }""";

var compiled = tson.compile(schema, TsonSchemaCompiler.dom());

@SuppressWarnings("unchecked")
Map<String, Object> value = (Map<String, Object>)
        compiled.compiledSchema().get("server").read("{ hostname: \"web-01\" port: 8080 }");
// { hostname=web-01, port=8080 } — validated against the schema; a bad port would surface as a diagnostic
```

**Bind mode** (`TsonSchemaCompiler.bind(dataBindContext)`) produces bound Java objects the same way
`TsonObjectReader` does, but with the TSON schema — not your Java class — as the source of truth. The
full multi-stage pipeline behind this (parse → resolve → link → register → compile → read), and how a
schema governed by meta.tn/core.tn is assembled, is described under [Schema pipeline](#schema-pipeline)
below.

### Writing TSON back out — `TsonObjectWriter`

The read-side inverse of `TsonObjectReader`: a Java object to TSON text. Mainly a debugging aid, not a
guaranteed-lossless serializer (see [Conformance](#conformance) for exactly where it's lossy). It
throws unchecked `TsonWriteException` on failure, symmetric to the reader's `TsonReadException` — no
checked exceptions on either side of the object-binding pair:

```java
String text = new TsonObjectWriter().toTson(server);
// { hostname: "web-01" address: !ipv4 "192.0.2.10" id: !uuid "9f1c8e2a-…" deployedOn: !date "2026-01-15" }
```

---

## Status

Built against TSON Part 1 (lexer + data format), a working draft: https://tson.io/raw/2026/32/tson-part1-data.md,
and Part 2 (schema grammar + type system), also a working draft: https://tson.io/raw/2026/32/tson-part2-schema.md

This is the spec's first implementation. Issues and ambiguities found in the spec while implementing are
tracked in [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md).

**Implemented:**

- [x] Lexer and structural parser — records, maps, arrays, annotations, directives (`!!id`/
      `!!schema`/`!!meta` arguments are validated as URIs, not just single-line tokens)
- [x] Base types — null, boolean, string, numbers (integer, float, hex-float, based-integer)
- [x] Integer types — `int8`–`int256`, `uint8`–`uint256`, `positive_integer` and siblings
- [x] Decimal/float types — `number`, `float32`, `float64`, `rational`, `complex`
- [x] Identifier/network types — `uuid`, `uri`, `ipv4`, `ipv6`
- [x] Binary types — `base64`, `base64url`, `base32`, `hex`
- [x] Temporal types — `date`, `time`, `datetime`, `duration`
- [x] Object binding — Java records, hand-written immutable classes, `Map<K, V>`, tuples, plain
      enums, sealed interfaces/unions
- [x] Wire-format annotation access — a bound record's own `@name[:value]` annotations, via an
      opt-in carrier component
- [x] Full document binding — TSON text straight to Java objects, dispatching into all of the above,
      and back again (`toTson`) — mainly a debugging tool, not a guaranteed-lossless round trip
      (e.g. the integer family's exact width isn't recoverable schemaless; see [Conformance](#conformance))
- [x] Streaming reads throughout — the lexer reads from an `InputStream`, and both the schemaless
      binder (`TsonObjectReader`) and the schema-validating reader (`TsonValueReader`) pull events one
      at a time rather than materializing a whole document tree first
- [x] Part 2 schema grammar — full schema document parsing into a faithful AST (records, compositions,
      refinements, generic/array-sugar type-refs, field groups, and more), verified end-to-end against
      the spec's own real `meta-kernel.tn`/`meta.tn`/`core.tn` fixtures
- [x] Part 2 schema resolution — composition (`&`), refinement (`^`) including tightening, bare and
      generic type references, field modifiers/defaults/fixed values, type parameters, array sugar,
      and generalized constructor-application/atom-refinement resolution (`!C value`); `meta-kernel.tn`
      resolves all 49 of its own declarations, `meta.tn` all 31, and `core.tn` all 48, all
      end-to-end (see [BACKLOG.md](BACKLOG.md) for the specific constructs still out of scope)
- [x] Part 2 schema linking and registration — validates a document's own `!!id`/`!!import` header
      directives during resolution, flattens argument-bearing type references into real named
      entries, merges `!!import`s, validates every reference in a schema actually resolves, and
      locks a schema into a registry keyed by its canonical `!!id`
- [x] A compiled, schema-validating data reader (Class 2, §1.5) — compiles a linked schema into real
      Java object references between per-type parsers (DOM or object-binding mode) for fast repeated
      reads against real TSON data documents (see [Schema pipeline](#schema-pipeline) below)
- [x] The full pipeline verified end-to-end, three schemas deep — an ordinary, user-defined schema
      can `!!import` `core.tn` (itself governed by `meta.tn`, governed by `meta-kernel.tn`) and
      compile cleanly with real, manually-registered Java classes bound against records composed
      from its imported vocabulary, reading real TSON data through the whole chain

**Not yet implemented:**

See [BACKLOG.md](BACKLOG.md) for the actively-tracked engineering backlog, and
[STRUCTURED-OUTPUT.md](STRUCTURED-OUTPUT.md) for the target-use-case plan (LLM structured-output
validation, JSON compatibility). One onboarding-relevant gap worth naming: there's no `!!schema`-header
auto-selection yet — every read above needs you to name the target class or schema type up front; a
data document can't yet drive its own reader from its own `!!schema` directive.

See [CLAUDE.md](CLAUDE.md#architecture) for architecture and design notes, and
[Conformance](#conformance) below for edge-case behavior worth knowing about.

## Schema pipeline

The [reader table](#reading-tson-choosing-an-entry-point) above binds data against a plain Java class
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
whose `resolve`/`compile` run the pipeline for you (as shown in the [reader table](#reading-tson-choosing-an-entry-point)'s
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

## Conformance

A handful of implementation choices are worth calling out on their own — not *what's* implemented (the
checklists above), but *how* it behaves at the edges, where a well-known JDK parser and the RFC/ISO
standard the spec cites don't quite agree.

**Stricter than the underlying JDK default, matching the cited RFC exactly.** Several built-in atoms
delegate to a JDK type for the bulk of parsing, but only after an explicit shape check of their own —
because the relevant JDK parser, checked empirically in each case rather than assumed, is consistently
*more lenient* than the RFC/ISO grammar the spec cites:

- `!uuid` requires RFC 9562's canonical 8-4-4-4-12 grouping; `UUID.fromString` alone accepts unpadded
  groups (`"1-2-3-4-5"` succeeds, silently reinterpreting where the groups fall).
- `!base64`/`!base64url` require padding; `Base64.getDecoder()` alone accepts it missing.
- `!date`/`!datetime`/`!time` reject ISO 8601's "extended year" form (a leading sign, more than 4 digits);
  `LocalDate`/`OffsetDateTime`/`OffsetTime.parse()` alone accept it, even though RFC 3339's `full-date`
  grammar requires exactly 4 digits and no sign.
- `!duration` requires uppercase designators and no leading sign; `Duration.parse`/`Period.parse` alone
  accept both.

See the relevant class's Javadoc for the specific check in each case.

**`!ipv4` doesn't delegate text parsing to the JDK at all, for a security reason, not just a
spec-fidelity one.** `InetAddress.ofLiteral` — the modern, no-DNS, literal-only entry point, confirmed
empirically before deciding this — is still far more lenient than RFC 3986's `IPv4address`/`dec-octet`
grammar: it accepts a leading zero (`"0177.0.0.1"`), the legacy BSD short/class-based form (`"1.2.3"`
→ `1.2.0.3`), and even a bare 32-bit integer literal (`"3232235521"` → `192.168.0.1`). That's not merely
looser than the cited RFC, it's the same leniency class behind real-world SSRF-filter-bypass techniques
(a validator and the actual network stack disagreeing about what address a string denotes). `Ipv4Parser`
validates the token against the RFC 3986 grammar itself, extracts the four octets directly from the
regex match, and constructs the address from raw bytes via `InetAddress.getByAddress(byte[])` — a pure
bytes-to-object call, never handing the original text to any JDK parser.

**`!ipv6` parses RFC 4291 §2.2's text representation itself too, for the same reason, plus a second,
unrelated JDK quirk.** Handing the token text to a JDK parser would reintroduce `!ipv4`'s exact
leniency gap through RFC 4291's IPv4-mapped alternative form (`x:x:x:x:x:x:d.d.d.d`, e.g.
`"::ffff:192.0.2.1"`), which embeds a dotted-quad tail. So `Ipv6Parser` parses the full grammar itself
— the 8-group preferred form, at most one `::` compression, and a dotted-quad tail checked against
the same strict `dec-octet` grammar `!ipv4` uses — and builds the address from raw bytes. Separately:
`InetAddress.getByAddress(byte[16])` itself was confirmed empirically to silently return an
`Inet4Address`, not an `Inet6Address`, for any 16-byte value in the IPv4-mapped range — the same
value ending up as a different, mutually non-`equals` Java type depending on which narrow sub-range
it falls in. `Ipv6Parser` uses `Inet6Address.getByAddress(String, byte[], int)` with `scope_id = -1`
instead (confirmed to behave like "no scope" and match the generic method's result for every
non-mapped address tried) to guarantee `!ipv6` always returns `Inet6Address`, regardless of the
address's value.

**One accepted, unfixable gap.** RFC 3339's grammar permits `time-second` up to `60` (leap-second
accommodation), but `java.time` has no leap-second concept at all — `!time`/`!datetime` reject a
spec-legal leap-second token as a parse error. There's no reasonable fix short of a from-scratch time
representation built solely for this one case, so it's documented (`TimeParser`'s Javadoc) rather than
solved.

**One accepted, different-revision gap.** `!uri` (§5.5) is the one atom here that does *not* get an
extra shape check ahead of the JDK type it delegates to — the opposite situation from the atoms above.
§5.5 cites RFC 3986, but `java.net.URI`'s own Javadoc states it implements RFC 2396 (as amended by RFC
2732), an older revision of the same standard, not a looser/stricter variant of the same grammar. There's
no simple shape to shim in front of `URI`'s constructor the way a four-group hex pattern works for UUID,
and writing an RFC 3986 validator from scratch isn't worth it at this stage, so `java.net.URI`'s behavior
is accepted as `!uri`'s actual contract for now. See `UriParser`'s Javadoc.

**`RegexParser` accepts `java.util.regex.Pattern`'s own syntax, not a real RFC 9485 (I-Regexp) validator.**
Not part of Part 1's published built-in vocabulary (`TextParser`/`RegexParser` are groundwork for Part 2,
which doesn't yet have anything that consumes a `regex` constraint), but the same kind of conformance
call as the `!uri` gap above: I-Regexp is a deliberately restricted, interoperable subset of a
different regex dialect (roughly ECMA-262) than `java.util.regex`'s own Perl-derived syntax, and
neither is a subset of the other. Writing an RFC 9485 validator from scratch is real, standalone work,
not worth doing before anything actually needs it. See `RegexParser`'s Javadoc.

**One open question.** Whether `!duration` accepts ISO 8601's alternative `PnW` week form is genuinely
ambiguous — §5.4's table shows only `PnYnMnDTnHnMnS`. This implementation rejects `PnW` as the more
conservative of the two readings, not a confident call — see [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md) #12.

**`toTson`'s round trip is intentionally lossy in a few specific, documented ways.** It's a debugging
tool, not a guaranteed-lossless serializer: a `!typeName` type-ref is only re-emitted where a value
wouldn't read back correctly without one (the built-in vocabulary's JDK-backed host types); anything
default value resolution (§4) already recovers on its own — the whole integer family, plain
`number`/`float32`/`float64` — is written bare, so a field bound from `!uint8 42` writes back as plain
`42`, indistinguishable from one that was never `!uint8`-typed at all. A schemaless writer has no
annotation to reach for any more than a schemaless reader has one to validate against. `byte[]` values
always write back as `!base64`, regardless of which of `base64`/`base64url`/`base32`/`hex` they were
originally decoded from — that information doesn't survive decoding, so `!base64` is an arbitrary but
reasonable default. Tuples write as plain arrays, with nothing marking them as tuples at all. Wire-format
annotations captured via `@Annotated` aren't re-emitted yet.

Ambiguities, inconsistencies, and errors in the spec text itself — as opposed to this implementation's own
behavior at the edges — are tracked separately in [SPEC-FEEDBACK.md](SPEC-FEEDBACK.md).

## Command-line interface

The `tson-cli` module is a small, zero-dependency CLI (ajv-cli-style) for checking TSON from the shell —
no Java to write. Three commands: **`init-example`** scaffolds an example schema + data file to start
from, **`validate`** reads a data file against one type of a schema, and **`compile`** checks that a
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
tson validate     --type <name> [--output text|json|tson] <schema> <data...>
tson compile      [--output text|json|tson] <schema>
```

`tson init-example` writes a ready-to-run `person.tn` + `person-data.tn` (see [Getting started](#getting-started)).
For a hand-written schema `person.tn` and a data file `ada.tn`:

```tson
!!id:"https://example.com/2026/32/app/person-1.tn"
!!meta:"https://tson.io/2026/32/m/meta.tn"
!!import:"https://tson.io/2026/32/m/core.tn"
{
    person => { name: text  age: int32 }
}
```

```
$ tson validate --type person person.tn ada.tn        # ada.tn = { name: "Ada"  age: 30 }
OK

$ tson validate --type person --output json person.tn bad.tn   # bad.tn = { age: 30 }
{"valid":false,"errors":[{"path":"/name","code":"FIELD_REQUIRED",
  "message":"missing required field 'name' for 'person'","expected":"a value for 'name'",
  "actual":"(absent)","dataPosition":"1:1:0","schemaPosition":null}]}

$ tson compile person.tn
OK
```

- **`--type`** (validate only) is required — a TSON schema declares many types, so you name which one
  the data is read against. There's no `!!schema`-header auto-selection yet (see [Status](#status)).
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
