# The JSON encoding: facades, binding and writing

Design notes for `tson-json`'s front door — `Json` and its two readers — the class-directed bind engine under
`JsonObjectReader`, and the two writers. Current form only; history lives in git.

**Invariants**

- A document that will not parse is reported through the read's own receiver: a limit refusal is tried first, a syntax
  failure second, and anything else is rethrown as itself.
- A collecting read that reported anything hands back nothing, tree and bound alike (`CountingReceiver`, per facade).
- A facade owns the document (entry points, framing, configuration); an engine binds or writes one value and stops.
  The pull past the root value is what rejects trailing content, and only a facade makes it.
- A member the class does not declare refuses the document; `ignoringUnknownFields()` is the derived opt-out.
- The target picks the parser and the JSON kind decides only whether the content is admitted; `HostAtoms`' number-content
  and string-content indices are disjoint (`HostAtomsTest.no_host_type_is_in_both_indices`).
- Numbers convert exactly or error, each by its family's own contract; `float`/`double` round by theirs.
- A union target is refused on read and a choice on write, because nothing in a Java class states §8.2's condition.
- `JsonDataEmitter` owns every separator; every byte sink is UTF-8, each `write` to one flushes, and none is closed here.
- An absent field is left out rather than written `null`; an `Annotations` carrier is filled empty on read and not
  written.

Related: `design/json-encoding.md` (configuration, atom vocabulary, rationale), `design/json-lexer-stream-tree.md`
(the duplicate-member rule, `JsonDiagnostics`' classification), `design/json-schema-directed-reading.md`,
`design/json-unicode-policies.md`, `design/facades-and-tree.md` (the TSON facades these mirror).

## The front door and its two readers

`Json` is the configuration a read is judged under — the `ProcessorPolicy`, the binding — and hands out the readers
and writers that apply it: `treeReader()` producing a `JsonValue`, `objectReader()` producing a bound Java object, and
their inverses `treeWriter()` and `objectWriter()`. That is the shape `Tson` takes over `TsonTreeReader`/
`TsonObjectReader`. It holds schema registries only once one is named: `withSchemas(TsonSchemaLoader)` returns an
instance holding two `JsonCompiledSchemaRegistry`s over the one loader — tree mode, shared by its tree readers, and
bind mode over its `DataBindContext`, shared by its object readers (`design/json-schema-directed-reading.md`). They
are separate caches because the compiled readers differ all the way down. `validate(source, schemaUri, rootType)` is
a collecting schema-directed tree read returning its diagnostics, the peer of `Tson.validate`.

JEP 540's static entry points (`Json.parse`, `Json.toDisplayString`) stay on it over a default configuration
(`design/json-encoding.md`); `parse` fails the way every read here does, through the default receiver.

**`Json` reduces no events itself.** A reduction there would put an engine in a front door's name and leave the stack
with no tree facade — a tree read that could not take a receiver, a policy or a path where a bound read could. The
reduction is `SchemalessTreeReader`'s, under `JsonTreeReader`, and the two readers are peers.

**A document that will not parse is reported through the read's own receiver, not thrown past it.** Both readers
funnel their `String`/`ByteSource`/`InputStream` forms through one event-source seam per mode, so one `try` covers
every entry point and the failure goes to `readFailure`:

- a **limit refusal** (`LimitExceededException`) is `Diagnostic.ofLimitExceeded`, tried first — the read ends the same
  way, but what is reported says this processor declined rather than that the document is malformed (§10.1;
  `verdict()` is `false`);
- a **syntax failure** (`ParseException`) is `JsonDiagnostics.ofBaseSyntaxError` — one `VALIDATION_ERROR` for a lexer
  and a parse failure alike, located, its `expected` the refusal's own where it named a construct and `well-formed
  JSON` otherwise, so a caller holding documents in either encoding can tell which one refused;
- **anything else is rethrown**, so a fault in this library reaches the caller as itself. A bug is not a verdict on the
  document, and turning one into a diagnostic would bury the real failure behind a false one.

So a collecting read never throws for a bad *document* — it returns nothing and the collector says why, with whatever
value-level problems it found first still in it — and a fail-fast read still throws `ReadException`, because its
receiver is what throws. That is the TSON readers' rule (`TsonTreeReader.readFailure`): the lexer is fail-fast and the
stream lazy, so a syntax failure surfaces mid-read, and an exception escaping a collecting read would leave the caller
holding a populated collector and an exception with nothing to say they belong to one document.

`JsonDiagnostics` is a peer of `TsonDiagnostics`, not a case inside it: classifying a failure is reading a document,
and reading is where the two stacks are separate.

**A collecting read that reported anything hands back nothing**, as on the TSON side: a partial tree's placeholder is
the same node as a real `null`, so it cannot say which of its parts to trust, and a Java record has nowhere to put a
hole. Each facade enforces it once per read by running the read on a copy of itself whose receiver is a
`CountingReceiver`, which also catches a token refusal the stream reports straight to the receiver.

**Schema-directed reads are a second engine under each facade, not a second door** — `JsonTreeReader.withSchema(uri)
.readAs(source, type)` in tree mode, `JsonObjectReader.withSchema(uri).readAs(source, type, Target.class)` in bind
mode, the peer of `TsonObjectReader.readAs`. `withSchema` is available only on a reader from `Json.withSchemas(...)`
(`IllegalStateException` otherwise). `read(source, Class)` stays class-directed: a JSON document names no schema of its
own (§3.4's in-band route is not built), so reading against one is always the caller's statement. What stands in the
way of a `readAs` is reported before any of the document is touched, each with the code a consumer routes on:

- `SCHEMA_NOT_PERMITTED` — the loader holds no schema for the identity and has no source for it (not a verdict);
- `UNKNOWN_TYPE` — the schema declares no such root type;
- `BIND_MISMATCH` (bind mode) — `JsonCompiledSchemaRegistry.get` compiled the schema and the compile refused because
  the bound classes do not match its types;
- `TYPE_MISMATCH` (bind mode) — the root type binds to a class `Target` cannot hold, checked from the descriptor
  before the read where the type is bound by name, and on the produced value after it otherwise.

A schema type with no bound class at all reaches the caller as `MissingBindingException`, the reading application's
own wiring, as on the TSON side. Both `readAs` paths root the read's context at the declaration the caller named
(`underDeclaration`), so a diagnostic names the type the author wrote rather than what an alias resolves to.

## Binding: `DataClassObjectReader`

`JsonObjectReader.read` binds a document straight into a Java object, streaming events rather than building a tree,
driven by the target class's `ltr8-bind` descriptor. The facade is over `DataClassObjectReader`, the JSON peer of
`tson-compiler`'s engine of that name.

**Naming.** Every reader in this family is named for what drives it and what it produces. A `DataClass` descriptor
drives this engine and an object comes out; it is not "schemaless", because the class *is* the schema. The tree engine
is driven by nothing but the wire structure, so `SchemalessTreeReader` is accurate there, in both encodings.

**Frame-free.** The engine binds a value and leaves the source where that value ended, so a caller positioned
mid-document gets one value out of it (`DataClassObjectReaderTest`). The facade's `readDocument` pulls once past the
root value and `JsonStream` raises on trailing content; the engine never does.

**The class is the schema.** §4.1 reads every JSON value at a typed position and never by inspecting the value; here
the type is a `DataClass` rather than a resolved schema. An object at a `DataClassRecord` is a record, at a
`DataClassMap` a map — the brace question JSON cannot answer syntactically, answered by the position.

Three rules are §7-shaped, as far as a Java class can express §7:

- **JSON null at a required component is refused** (`FIELD_REQUIRED`), as `_` is at a REQUIRED field ([TSON-SCHEMA]
  §7.6). `ltr8-bind` marks a component required when it is a primitive or carries `@Field(required = true)`.
- **JSON null anywhere else is the void value**, which a bound object spells `null`, having no third state — so an
  omitted member and a null member are indistinguishable in the result, which [TSON-JSON] §7.2 states of bound
  output. It is the treatment `tson-compiler`'s engine gives `_`.
- **An `Annotations` carrier is filled with `Annotations.empty()`**, §4.3 giving the JSON wire no annotation channel.

**A member the class does not declare refuses the document** (`UNRECOGNIZED_FIELD`), the one place this reader is
stricter than a JSON consumer expects. A member added in a later version can change what the members this class does
read mean — a `currency` beside an `amount`, a `unit` beside a `quantity` — so a reader that drops it has read a
different document and cannot tell. A closed reading is what makes "the class is the schema" mean anything, and it is
what §6.1.1 says of a record under a real schema, so the two paths agree. `ignoringUnknownFields()` is the opt-out, as a
derived reader so the safe reading is the one nobody has to ask for; it carries the TSON reader's name because what is
closed is a bound class's field set under both encodings. The cost lands on the on-ramp: a converted JSON Schema whose
`additionalProperties` defaults to open describes documents this reader refuses. §6.1.1 gives undeclared members one
home, a map-typed field the schema declares — an accommodation a schema states and a class read without one cannot.

A repeated member is `DUPLICATE_FIELD` whether or not the class declares it — §3.1's rule is about the document — and
the last occurrence wins. A repeated key at a map position is `DUPLICATE_MAP_KEY`.

**A union target is refused, with the reason** (`TYPE_MISMATCH`). §8.2 admits a tag-free choice by exactly one
condition — the resolver-derived `disjoint` fact — and forbids extending it ("no trying variants in order"). A Java
union states no such fact, so there is nothing to dispatch on, and inventing a rule would be inventing the one §8.2
rules out.

**A map's key type must be an atom**, the object form of §6.4: each member name is the key's content, read by the key
type's contract. A key type a member name cannot spell is `BIND_MISMATCH`; the pairs form is not read here.

**Bind mode is all-or-nothing within the value too.** A record, array, tuple or map whose contents reported is not
constructed: each checkpoints `ctx.reported()` before its contents and returns `null` if the count moved, so no
constructor is handed nulls for components that never arrived — and an array is checked before allocation, since
storing a failed element's `null` into a primitive array throws out of `put`.

### Atoms: the target picks the parser

`JsonAtoms.bind` dispatches on the **target** and guards on the leaf kind, never the other way round. That is §4.1 read
literally — every position in this encoding is typed and base type resolution has no role (§4.1, §5.7) — and it is
the only order that expresses §5.4, where one approximate family admits a JSON number *and* a JSON string (`".inf"`,
`"-.inf"`, `".nan"`, [TSON-DATA] §7.6's productions) through one parser. Which families are approximate is this
encoding's question, so it is answered by the four Java types `float`/`double` and their boxes rather than by asking
an `AtomType` about JSON kinds.

`HostAtoms` supplies both halves: `forNumberContentHostType` for §5.3's exact tier and §5.4's approximate one,
`forStringContentHostType` for §5.6's string-content families. The two indices are disjoint so that `"123"` cannot
become a `BigInteger` because a field is declared one. The number index maps a Java type to the family of that width
(`int` → `int32`, `BigDecimal` → `number`, every primitive beside its box); its Javadoc states the mapping and why it
is an interpretation. **The text encoding does not consult it and must not**: [TSON-DATA] §4 classifies an untyped
token first there, and the target is a narrowing question afterwards (`AtomBinder`).

**Numbers convert exactly or error** (§3.1, "error, never round silently"), and routing to the family is what gives
each refusal its own reason. `1.5` at an `int` is not an integer *form* (§5.3's contract rejection, as the token `1.5`
is in text); `2147483648` is an integer outside `int32`'s range; `200` at a `byte` reports `>= -128 and <= 127`,
`int8`'s own bound, rather than the JVM's account of the same fact. `float`/`double` round onto the binary grid, which
is the approximate families' own contract (§5.4).

An enum needs no rule here: `ltr8-bind` bridges every plain Java enum through `EnumStringBridge`, so it arrives as a
bridged `String` atom, and a bridge's refusal is `ATOM_CONSTRAINT_VIOLATION`.

### Diagnostics

Every problem goes through a `DiagnosticsReceiver`: `throwing()` — the default — raises `ReadException` at the first,
and `withDiagnostics(DiagnosticsCollector)` gathers every problem in one pass and still returns, because a sender
fixing a document one round trip per mistake is the failure mode diagnostics exist to avoid.

`JsonReadContext` carries it, the peer of `TsonReadContext`, holding no error policy of its own. It builds both RFC 6901
pointers by stepping (`field`, `index`, `schemaField`) and renders them only when a diagnostic is built. One context
type serves every engine; the class-directed bind read uses the smaller half of it — no schema end (a class has no
document to point into) and no lookahead (the engine pulls a value's opening event and passes it down, where a
schema-directed dispatcher peeks through `lookingAhead`).

**Name hygiene.** `DataClassObjectReader.checkNameHygiene` applies [TSON-DATA] §8.2's restricted-character and
restricted-script rules to every member name at a record position, declared or not, under the identifier policy the
engine was constructed with (`policy.identifierPolicy()`, passed by `JsonObjectReader`). The class-directed read's
context carries `IdentifierPolicy.none()`; only a schema-directed read's context carries the read's policy, which
`reader.NameHygiene` judges under (`design/json-unicode-policies.md`). Map keys are data and get no name check, and the
look-alike rule has no scope here: under a class, a member that reads alike to a declared one is simply undeclared.

Codes come from the closed vocabulary the TSON readers use — §9.4 adds no category — so: `TYPE_MISMATCH` (wrong shape,
a union, a class that refused what it was handed), `FIELD_REQUIRED`, `UNRECOGNIZED_FIELD`, `DUPLICATE_FIELD`,
`DUPLICATE_MAP_KEY`, `WRONG_ARITY`, `ATOM_FORM_INVALID`/`ATOM_CONSTRAINT_VIOLATION`, `RESTRICTED_CHARACTER`/
`RESTRICTED_SCRIPT`, and `BIND_MISMATCH` for a class this context cannot analyse or whose keys a member name cannot
spell. `BIND_MISMATCH` and the two name refusals are not verdicts (`Code.verdict()` is `false`): nothing about the
document's validity is asserted by them. A constructor or collection that throws on what it is handed is
`TYPE_MISMATCH` here, the class being the contract; under a schema the same refusal is `BIND_MISMATCH`
(`BindingDiagnostics.rejectedByClass`/`rejectedUnderSchema`, shared with the TSON readers).

**What `read` is not** is validation against a TSON schema. Nothing here consults facets, field states, defaults, fixed
values, groups or the discrimination predicate, because a Java class declares none of them. The honest name for what it
checks is "does this document fit this class"; `readAs` asks whether it conforms.

## Writing: the two readers, inverted

`JsonTreeWriter` is the inverse of `JsonTreeReader` and `JsonObjectWriter` of `JsonObjectReader`, each a facade over an
engine in the unexported `writer` package (`TreeValueWriter`, `DataClassObjectWriter`), on the read side's split.

**`JsonDataEmitter` owns the punctuation, and that is the whole reason it exists.** It is the push-based peer of
`JsonStream`'s pull: a caller says `beginObject()`, `member(name)`, `endObject()`, and the commas, colons and newlines
are the emitter's to place. Two walks placing their own commas is two chances to place one wrongly. One flag —
`expectingMemberValue` — keeps a comma out from between a name and its value; everything else is a per-scope count.

**The tree round trip is text-identical.** RFC 8259 has six kinds and one spelling each, and `JsonNumber` holds the
*literal*, so §5.3's digits and scale come back out (`199.90` is written `199.90`) and `JsonTreeWriterTest` asserts
equality of **text**, not merely of value. Only whitespace is not preserved, which §9.3 does not make part of a value.
`TsonTreeWriter` can promise only value preservation, TSON text spelling one value several ways.

**The object round trip is through the class that wrote it.** The descriptor decides whether a value is an object, an
array or a leaf, and nothing inspects a value to guess. An `int`'s width and a tuple's tuple-ness live in the class, so
a document written here is self-describing only to a reader holding one.

**One lookup answers both "is this a vocabulary atom" and "how is it spelled".** `VocabularyAtoms` is keyed by the wire
class, and every entry — a `UUID`, a `LocalDate`, a `byte[]` — encodes as a JSON string, the table holding exactly the
vocabulary's host types that are not numbers or booleans. What falls through is what §5.3 and §5.4 spell as JSON's own
kinds; a `BigDecimal` writes its own digits. Dispatching on `instanceof Number` instead would put a `long` and a
`BigInteger` down one branch and write neither by its family. A map key's member name goes through the same lookup,
after its bridge.

**A `Namespace` writes as the map it holds**, its `entries` through their own descriptor: the schema position typing it
names the constructor, as at a TSON slot (`design/writers-and-document-header.md`).

**Refused rather than approximated** (`WriteException`), because no reader here could take it back:

- **a choice.** §8.2's one condition is a fact a schema derives; a Java union states nothing of the kind. The mirror of
  the reader's refusal.
- **a host value with no JSON spelling**, a map whose key type a member name cannot spell, and a `null` map key — the
  write side of the reader's `BIND_MISMATCH` on the same shapes.

Non-finite `double`s and `float`s are not refused: §5.4 spells them `".inf"`, `"-.inf"` and `".nan"`, the strings the
same family parser reads back.

**Left out of the document:**

- **an absent field** (a `null` component), rather than written `null` — §7 makes JSON null the void sentinel at a typed
  position, so the two say the same thing for every declaration that admits both (§6.1.2), and omission is what an
  encoder without the schema writes;
- **an `Annotations` carrier and an annotated box's annotations**, which the writer drops. §4.3 makes encoding a value
  that carries annotations an encode error, with stripping permitted only as explicit configuration; this writer strips
  unconditionally, and it is the class-directed reader's mirror, which fills every carrier empty.

**`WriteException` is `tson-base`'s**, shared by both encodings: a value the encoding cannot take is the processor's
fact rather than one format's, and each throw site names its encoding ("cannot write X as JSON"). `DataBindException`
— a class that could not be taken apart — is wrapped into it at the engine's entry, so one unchecked type covers every
way a write can fail; an `UncheckedIOException` from the sink passes through as itself.

**Every byte sink is UTF-8 and none is closed here.** `write(value, ByteSink)` encodes UTF-8 itself, so a document
reaches a `ByteBuffer`, a channel or a file without existing as a `String` first, and each such `write` flushes what it
emitted, because closing is the caller's and a sink cannot tell a caller who finished from one who abandoned the write.
`indented(indent)` is a derivation, not a setting; the compact form is the default and the one to send, and the
indented form equals `JsonValue.toDisplayString(indent)` (`JsonTreeWriterTest`).
