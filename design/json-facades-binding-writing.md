# The JSON encoding: facades, binding and writing

Design notes for `tson-json`'s front door — `Json` and its two readers — the schemaless bind engine under
`JsonObjectReader`, and the two writers. Current form only; history lives in git.

**Invariants**

- A document that will not parse is reported through the read's own receiver: a limit refusal is tried first, a syntax
  failure second, and anything else is rethrown as itself.
- A collecting tree read hands back the tree; bind mode is all-or-nothing and hands back `null`.
- A member the class does not declare refuses the document; `ignoringUnknownFields()` is the derived opt-out.
- The target picks the parser and the JSON kind decides only whether the content is admitted; `HostAtoms`' number-content
  and string-content indices are disjoint.
- Numbers convert exactly or error; `float`/`double` are the deliberate exception.
- A union target is refused on read and a choice on write, because nothing in a Java class states §8.2's condition.
- `JsonDataEmitter` owns every separator; every sink is UTF-8, each `write` flushes, and none is closed here.
- An absent field is left out rather than written `null`, and wire annotations are dropped, the reader producing neither.

Related: `design/json-encoding.md` (configuration, atom vocabulary, rationale), `design/json-lexer-stream-tree.md`,
`design/json-schema-directed-reading.md`, `design/json-unicode-policies.md`, `design/facades-and-tree.md` (the TSON
facades these mirror).

## The front door and its two readers

`Json` is the configuration a read is judged under — the `ProcessorPolicy`, the binding, and where problems
go — and hands out the two readers that apply it: `treeReader()` producing a `JsonValue`, `objectReader()`
producing a bound Java object. That is the shape `Tson` takes over `TsonTreeReader`/`TsonObjectReader`. It
holds schema registries only once one is named: `withSchemas(TsonSchemaLoader)` returns an instance holding two
`JsonCompiledSchemaRegistry`s over the one loader — tree mode, which its tree readers share, and bind mode over its
own `DataBindContext`, which its object readers share (`design/json-schema-directed-reading.md`). The two are
separate caches because the readers differ all the way down.

**JEP 540's entry points stay static on it** — `Json.parse(text)`, `Json.toDisplayString(value)` — over a
default configuration. They are the zero-ceremony path the API is named for, and what a consumer moving from
`jdk.incubator.json` will type. A caller needing a policy, a receiver or a binding builds an instance. What
they borrow is the spelling and not the contract: `parse` fails the way every read here fails, through the
default receiver.

**A document that will not parse is reported through the read's own receiver, not thrown past it.** Both
readers funnel their `String`/`ByteSource`/`InputStream` forms through one `read(JsonEventSource)` seam, so
one `try` there covers every entry point, and the failure goes to `readFailure`:

- a **syntax failure** is `JsonDiagnostics.ofBaseSyntaxError` — `VALIDATION_ERROR`, located, with `expected`
  naming the encoding that refused (`well-formed JSON`), because a caller routing on a diagnostic can hold
  documents in either and that word is the difference between reaching for the right writer and the wrong one;
- a **limit refusal** is `Diagnostic.ofLimitExceeded`, tried first — the read ends the same way but what is
  reported says this processor declined rather than that the document is malformed (§10.1, and `verdict()` is
  `false`);
- **anything else is rethrown**, so a fault in this library reaches the caller as itself. A bug is not a
  verdict on the document, and turning one into a diagnostic would bury the real failure behind a false one.

So a collecting read never throws for a bad *document* — it returns nothing and the collector says why, with
whatever value-level problems it found first still in it — and a fail-fast read still throws, as
`ReadException`, because its receiver is what throws. That is the TSON readers' rule exactly (`TsonTreeReader.
readFailure`), which is the point: a `ParseException` escaping a collecting read would be the one failure a
caller who asked for every problem could not see coming.

**`JsonDiagnostics` is a peer of `TsonDiagnostics`, not a case inside it.** A `Diagnostic` is the shape of an
answer and is shared (§9.4 adds no category of its own); *classifying* a failure is reading a document, and
reading is where the two are separate stacks — one switch over both would be a switch responsible for
exceptions it cannot name. It has one case where the text encoding has three: §8.1 makes a lexer error and a
parse error separate categories and TSON text keeps them separate in the type, where this stack raises
`ParseException` for both and splits them here; and there is no counterpart to
`TsonUnsupportedDocumentException`, a JSON document declaring no conformance class to be refused for.

**`Json` reduces no events itself.** A reduction there would put an **engine in a front door's name** and leave
the stack with no tree *facade* — a tree read that could not be given a receiver, a policy or a path where a
bound read could. The reduction is `SchemalessTreeReader`'s, under `JsonTreeReader`, and the two readers
are peers.

**The tree engine is the one place `Schemaless` is the right word.** `DataClassObjectReader` is driven by the
target class, which is in effect its schema; the tree reader is driven by nothing at all, so the name is
accurate here where it would have been wrong there. Both are named for what drives them and what they
produce.

**§3.1's duplicate-member rule is a diagnostic**: `DUPLICATE_FIELD` with an RFC 6901 pointer,
reported rather than thrown, so a collecting read finds every repeat in one pass and a fail-fast one still
stops at the first. That is more faithful to §3.1 than a `ParseException` would be — the grammar accepts
the document, and §3.1 puts a repeat in the categories that follow the position's type rather than in the
parse category. JEP 540 calls it a parse error for want of anywhere else to put it; this has somewhere.

**A collecting tree read hands back the tree**, where a bound read hands back nothing: a `JsonObject` has
somewhere to put a partial answer and a Java record does not. Same asymmetry `tson-compiler` draws between
its own two readers.

## Writing: the two readers, inverted

`JsonTreeWriter` is the inverse of `JsonTreeReader` and `JsonObjectWriter` of `JsonObjectReader`, each a
facade over an engine in the unexported `writer` package (`TreeValueWriter`, `DataClassObjectWriter`) — the
same front-door/engine split the read side makes, and for the same reason: a front door owns the
*document* (the sinks it writes to, the form it writes in) where an engine owns one value and contributes
no framing.

**`JsonDataEmitter` owns the punctuation, and that is the whole reason it exists.** It is the push-based
peer of `JsonStream`'s pull: a caller says `beginObject()`, `member(name)`, `endObject()`, and the commas,
the colons and the newlines between them are the emitter's to place. Two walks placing their own commas is
two chances to place one wrongly, and the failure is a document that will not parse. One flag —
`expectingMemberValue` — is what keeps a comma out from between a name and its own value; everything else
is a per-scope count.

**The tree round trip is total, and that is a stronger claim than `TsonTreeWriter` can make.** TSON text
spells one value several ways and a tree does not record which, so `TsonTreeWriter` documents its losses
(integer width, tuple-ness). RFC 8259 has six kinds and one spelling each, and `JsonNumber` holds the
*literal* rather than a parsed number — so §5.3's digits and scale come back out, `199.90` is written
`199.90`, and `JsonTreeWriterTest` asserts equality of **text**, not merely of value. The only thing not
preserved is whitespace, which §9.3 does not make part of a value. That closes the half of §5.3 a read alone
could not test: the scale survived into a `BigDecimal` and nothing checked it came back.

**The object round trip is through the class that wrote it**, which is the accurate claim rather than a
weaker one. §4.1 makes reading schema-directed and the reader lets the target class play that part; writing
is that arrangement seen from the other end, so the descriptor decides whether a value is an object, an
array or a leaf and nothing inspects a value to guess. An `int`'s width and a tuple's tuple-ness live in the
class exactly as they live in a schema, so a document written here is self-describing only to a reader
holding one.

**One lookup answers both "is this a vocabulary atom" and "how is it spelled".** `VocabularyAtoms` is keyed
by the wire class, and every entry in it — a `UUID`, a `LocalDate`, a `byte[]` — encodes as a JSON string,
because the table holds exactly the host types the vocabulary reads to that are not numbers or booleans.
What falls through is the set §5.3 and §5.4 spell as JSON's own kinds. Dispatching on `instanceof Number`
instead is how `litterat-json`'s `JsonMapper.writeAtom` puts a `long` and a `BigInteger` down one branch and
writes neither by its family.

**Two things are refused rather than approximated, both because the reader could not take them back:**

- **a choice.** §8.2 admits an untagged one only by class-stable disjoint variants, a fact a *schema*
  states; a Java union states nothing of the kind. This is the exact mirror of the
  reader's own refusal, and writing one would produce a document this library cannot read.
- **a host value with no JSON spelling**, and a map whose key type a member name cannot spell — the write
  side of the reader's own `BIND_MISMATCH` on the same shape.

Non-finite doubles are **not** among them: §5.4's approximate families spell them `".inf"`, `"-.inf"` and
`".nan"` — [TSON-DATA] §7.6's own productions, so the parser that reads them back is the one that reads
every other number, and no third spelling of infinity enters the series.

**What is dropped is what the reader cannot produce.** Wire annotations: §3.3's annotation object belongs to
the schema-directed decode, and `DataClassObjectReader` binds every carrier to `Annotations.empty()`, so
writing them would emit members no reader here takes back. An absent field is left out rather than written
`null` — §7 makes JSON null the absent sentinel at a typed position, so the two say the same thing and the
shorter one is what a reader of any strictness takes.

**`WriteException` is `tson-base`'s**, shared by both encodings for `ParseException`'s reason: a value the
encoding cannot take is the *processor's* fact rather than one format's. `DataBindException` is the
different failure — a class that could not be taken apart — and it is wrapped rather than passed through, so
one unchecked type covers every way a write can fail. Both encodings' writers throw it, which is what puts
the difference in the message rather than in the type: each throw site names its own encoding ("cannot write
X as JSON"), so nothing about the exception has to.

**Every sink is UTF-8 and none is closed here.** `write(value, ByteSink)` encodes UTF-8 itself, so a
document reaches a `ByteBuffer`, a channel or a file without existing as a `String` first; each `write`
flushes what it emitted, because closing is the caller's and a sink cannot tell a caller who finished from
one who abandoned the write. `indented(indent)` is a derivation, not a setting — the compact form is the
default and the one to send, and the indented one is byte-identical to `JsonValue.toDisplayString(indent)`,
which is the same rendering reached from a value instead of from a writer.

## Binding: a facade over an engine

`JsonObjectReader` reads a document straight into a Java object, driven by the target class's own
`tson-bind` descriptor and streaming the event source rather than a tree.

**It is a facade over `DataClassObjectReader`**, which is what actually binds a value — the same split
`TsonObjectReader` makes over the reader of that name in `tson-compiler`, and for the same reason: **a front
door owns the document** (entry points, framing, and the configuration a read is judged under) where **an
engine owns one value at one descriptor and stops**. The schema-directed decode of §5–§8 takes the same shape
on both sides: the compiled readers are a second engine under each facade rather than a second door —
`JsonTreeReader.withSchema(uri).readAs(source, type)` in tree mode, and `JsonObjectReader.withSchema(uri)
.readAs(source, type, Target.class)` in bind mode, the peer of `TsonObjectReader.readAs`. `read(source, Class)`
stays class-directed: a JSON document names no schema of its own (§3.4's in-band route is not built), so reading
against one is always the caller's statement, made with `withSchema` and a root type.

**`readAs` in bind mode mirrors `TsonObjectReader`'s**, and is all-or-nothing at the document as every bind read
is: a document that reported anything binds to `null`. What stands in the way of a read is reported before any of
the document is touched, each with the code a consumer routes on — `SCHEMA_NOT_FOUND`, `UNKNOWN_TYPE`,
`BIND_MISMATCH` for a schema whose types the bound classes do not match (`JsonCompiledSchemaRegistry.get` compiles
it and the compile refuses), and `TYPE_MISMATCH` for a root type bound to a class the caller cannot hold. A schema
type with no bound class at all reaches the caller as `MissingBindingException`, the reading application's own
wiring, as it does on the TSON side.

**The engine is named for both axes every reader in this family is named for** — what drives the read, and
what it produces. A `DataClass` descriptor drives this one and an object comes out. That has to stay in the
name or the family stops scaling: `JsonTreeReader` sits over a tree engine, and §5–§8's
schema-directed decode is a further engine under a facade, so a name encoding only "what drives it"
would leave two readers sharing one.

`tson-compiler`'s engine carries the same name, because it is the same engine against the other encoding's
events. Neither is "schemaless": **the class *is* the schema** — that engine's own Javadoc says so of its own
target, "in effect the schema the data must satisfy" — so the word describes the one thing such a reader is
not short of. It stays accurate of a *tree* reader, which really is driven by nothing, and both encodings
keep `SchemalessTreeReader` on that basis.

**Frame-free is the property the split exists for**, and `DataClassObjectReaderTest` pins it: the engine binds
a value and leaves the source where that value ended, so a caller positioned mid-document gets one value out
of it. What refuses trailing content is neither the engine nor the facade but `JsonStream` — the pull past
the root value is what raises it, and the facade's contribution is only to make that pull happen.

Binding is JEP 540's other explicit non-goal, after streaming, and it is the one worth not inheriting: a
library whose point is validated typed data has no business handing back a tree and calling it done.

**The class is the schema, and that is the whole design.** §4.1 reads every JSON value at a typed
position and never by inspecting the value twice; here the type is a `DataClass` rather than a resolved
TSON schema. The brace question follows immediately: an object at a `DataClassRecord` is a record, at a
`DataClassMap` a map. That is the ambiguity a shared `TsonEvent` vocabulary could not express, answered
by the position exactly as §4.1 requires — the separate-stack decision paying for itself at the first
place it was predicted to.

Three rules are §7-shaped, as far as a Java class can express §7:

- **JSON null at a required component is refused**, exactly as `_` is at a REQUIRED field
  ([TSON-SCHEMA] §7.6). A component `tson-bind` marks required is a primitive, or one carrying
  `@Field(required = true)`.
- **JSON null anywhere else is the absence**, which a bound object spells `null`, having no third state —
  so an omitted member and a null member are indistinguishable in the result, which §6.1.2 says outright.
  It is the same treatment `DataClassObjectReader` gives `_`, which is the point: §7 makes the two
  spellings one concept.
- **An `Annotations` carrier is filled empty**, §4.3 giving the JSON wire no annotation channel. Nothing
  is dropped; there was nothing to drop.

**A member the class does not declare refuses the document**, which is the one place this reader is
stricter than a JSON consumer expects. The reason is not tidiness: **a member added in a later version can
change what the members this class does read mean** — a `currency` beside an `amount`, a `unit` beside a
`quantity`, an `encoding` beside a `payload`. A reader that drops it has not read a subset of the document;
it has read a different document and cannot tell. The class is the schema here, and a closed reading is
what makes that claim mean anything — it is also what [TSON-SCHEMA] §7.2 already says of a record under a
real schema, so the two paths agree, and `DataClassObjectReader` applies the same rule on the TSON side.
`ignoringUnknownFields()` is the opt-out, deliberately the derived reader: the safe reading is the one
nobody has to know to ask for. **It carries the TSON reader's name, not RFC 8259's word** — what is closed
is a bound class's *field set*, which is the same thing under both encodings, and one rule spelled two ways
is a rule the two readers can drift on.

The cost is real and worth stating, because it lands squarely on the on-ramp: a converted JSON Schema whose
`additionalProperties` defaults to *true* describes documents this reader refuses. That is §6.2's rest
field's job, and it fixes it properly — by **keeping** the extra members rather than ignoring them, which
is the difference between a document read wholly and one read partly. The accommodation belongs with the
schema-directed decode, where a schema can say which members are a tail and which are a mistake, and not
here, where the only two options are drop and refuse.

**A union target is refused, with the reason.** §8.2 admits a tag-free choice by exactly one condition — a
derived disjointness fact over class-stable variants — and forbids extending it ("no trying variants in
order", which is how JSON Schema validates a `oneOf`). A Java union states no such fact, so there is nothing
to dispatch on and inventing a rule would be inventing the one §8.2 rules out. The message says so rather
than failing obscurely.

**Numbers convert exactly or error** (§3.1's "error, never round silently"): every integral narrowing
runs off one `BigDecimal`, so `1.5` and `2147483648` both fail at an `int` rather than truncating or
wrapping. `float`/`double` are the exception and are meant to be — rounding onto the binary grid is the
approximate families' own contract (§5.4). An enum needs no rule here at all: `tson-bind` bridges every
plain Java enum through `EnumStringBridge`, so one arrives as a bridged `String` atom.

**The target picks the parser; the JSON kind decides only whether the content is admitted.** That is §4.1
read literally — there is no untyped position in this encoding and no base type resolution under it, §5.7
saying so outright — and it is why `bindTo` dispatches on the target and guards on the leaf kind, rather
than the other way round. `HostAtoms` supplies both halves: `forNumberContentHostType` for §5.3's exact tier
and §5.4's approximate one, `forStringContentHostType` for §5.6's families. The two indices are disjoint,
and a test says so — a family reading from a number must not be reachable from a string, which is what would
let `"123"` become a `BigInteger` because a field is declared one.

**The dispatch order is load-bearing, and §5.4 is why.** An approximate position admits a JSON number *and*
a JSON string: the two infinities and NaN have no JSON number spelling and travel as `".inf"`, `"-.inf"`,
`".nan"` — [TSON-DATA] §7.6's own productions, so one parser reads both kinds and no third spelling of
infinity enters the series. A switch on the leaf kind cannot express one family serving two kinds; a switch
on the target can, and both branches reach one call. Which families are approximate is *this encoding's*
question rather than the vocabulary's, so it is answered by the two Java types §5.4 names and not by asking
an `AtomType` about JSON kinds it should know nothing about.

What the routing buys over narrowing a host value the encoding chose is visible in the refusals. `1.5` and
`2147483648` at an `int` are two refusals where one `BigDecimal` asked both questions would give one — "not
exactly representable": the first is not an integer *form* at all (§5.3's contract
rejection, exactly as the token `1.5` is in text, which the TSON side reaches through
`NumberNarrowing`), the second is an integer outside `int32`'s range. `200` at a `byte` reports
`>= -128 and <= 127` — `int8`'s own bound, from `AtomTypeException`'s vocabulary — rather than "a value
that fits byte", which is the JVM's account of the same fact and not the schema's. And a refinement's
`allow_nan` or `multiple_of` is honoured by the same family parser under the schema-directed decode.

**The mapping from a Java type to a family is an interpretation**, stated once in `HostAtoms` as the inverse
of `IntegerParser.hostType`: `byte`→`int8` … `long`→`int64`, `BigInteger`→`integer`, `float`/`double`→
`float32`/`float64`, `BigDecimal`→`number` — **each primitive keyed beside its box**, since a component
declared `Integer` and one declared `int` are one position as far as a document is concerned, and a hole in
one half of a pair stays invisible until a caller happens to declare the other. `java.lang.Number` is
deliberately absent and is refused a layer earlier, by `tson-bind`: it is abstract and names no family, so
there is nothing for the index to answer with. Nothing says a Java `int` means `int32` rather than an
`integer` bounded to 32 bits — the two admit the same values — and the first is the one worth committing to because it
makes this read a preview of the schema-directed one: an `int` component reaches the reader an `int32` field
would. **The text encoding does not consult that index and must not**: [TSON-DATA] §4 makes base type
resolution normative for an untyped token there, so the token is classified first and the target is a
narrowing question afterwards. Both readings are right for their own encoding, which is why there are two
indices rather than one widened.

**Every problem goes through a `DiagnosticsReceiver`**, so a read's own receiver decides its fate exactly
as it does for the TSON readers: `throwing()` — the default — raises `ReadException` at the first, and
`withDiagnostics(DiagnosticsCollector)` gathers every problem in one pass and still returns. **One pass
finding everything is the point**: a reader that threw could only report the first disagreement, and a
sender fixing a document one round trip per mistake is the failure mode diagnostics exist to avoid.

`JsonReadContext` is what carries it — the peer of `TsonReadContext`, holding no error policy of its own.
It tracks the position and builds both RFC 6901 pointers by *stepping* (`field(name)`/`index(i)`, and
`schemaField` for the schema end, return a context one link deeper) rather than concatenating, since
concatenating per step is quadratic in depth and thrown away by every read that reports nothing — and a
schema-directed read steps both for every field of every record. A `JsonSchemaLocation` is rendered only when a
diagnostic is built. One context serves both engines, and the schemaless bind read uses the smaller half of it:
no schema end (the class is the schema, and a class has no document to point into), and no lookahead (the engine
pulls a value's opening event and passes it down, where a schema-directed dispatcher peeks at an object's leading
members through `lookingAhead`). The context also carries the read's identifier policy (`identifierPolicy()`), which
`DataClassObjectReader.checkNameHygiene` and `reader.NameHygiene` judge names under
(`design/json-unicode-policies.md`).

Codes come from the same closed vocabulary the TSON readers use — §9.4 adds no category of its own — so
`TYPE_MISMATCH`, `FIELD_REQUIRED`, `UNRECOGNIZED_FIELD`, `DUPLICATE_FIELD`, `DUPLICATE_MAP_KEY`,
`WRONG_ARITY`, `ATOM_FORM_INVALID`/`ATOM_CONSTRAINT_VIOLATION`, `TYPE_MISMATCH` again for a union with no selector, and
**`BIND_MISMATCH`** for a class this context cannot analyse or cannot receive a JSON object's keys into.
That last one is deliberately **not a verdict**: nothing about the document is being asserted by it, which
is what a caller routing on `Code.verdict()` needs to be able to tell.

**Bind mode is all-or-nothing.** A record, array, tuple or map whose contents reported is not constructed:
a Java record has nowhere to put a hole, so a collecting read hands back `null` rather than an object
nobody wrote. Tree mode keeps what it built and this cannot — the same deliberate asymmetry
`ConstructionGuard` draws on the TSON side, reached here by checkpointing `ctx.reported()` across the
container's own contents.

**What this reader is not** is validation against a TSON schema. Nothing here consults facets, field
states, defaults, fixed values, groups or the discrimination predicate, because a Java class declares
none of them. The honest name for what it checks is "does this document fit this class".
