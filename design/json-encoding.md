# The JSON encoding

Design notes for `tson-json` — the implementation of [TSON-JSON] (`spec/tson-part3-json.md`), the JSON encoding of the
TSON schema system. Current form only; history lives in git. This note is the design rationale — the shared
configuration and atom vocabulary, the on-ramp the encoding is for, why the stack is separate all the way up, and the
packages; the layers themselves are in the sibling notes.

**Invariants**

- `tson-json` does not build on `tson-compiler`'s `TsonEventSource`: it has its own lexer, structural layer, tree and
  readers.
- Compiling JSON readers from a `TsonLinkedSchema` adds no dependency at all, and in particular none on `tson-compiler`.
- One `ProcessorConfig` value serves both front doors; a diagnostics receiver belongs to a read, not to a deployment.
- JSON has no schema documents of its own: `Json` has a way to *name* a schema (`withSchemas`), never a way to author one.
- A schema-directed tree read produces a `JsonValue`, never a `TsonValue`; `ValueReaderFactoryRegistry.atoms()` stays as
  what the parsing contract is asserted against.
- `CrossEncodingParityTest` asserts the same `Diagnostic.Code` and RFC 6901 pointer, never the message, over the rules that
  are actually written twice; a legitimate divergence is asserted as a divergence, and a defect is never pinned as agreed.
- `HostAtoms`' string-content index excludes every host class the family is not a function of, and the numeric families —
  a class declaring `BigInteger` must not turn `"123"` into one.
- The target is existing JSON validating **unchanged**; a converted schema outside the profile below breaks that.

Related: `design/json-lexer-stream-tree.md` (JEP 540 alignment, lexer, structural layer, tree),
`design/json-schema-directed-reading.md` (binding a document, the annotation object, the `reader` package),
`design/json-facades-binding-writing.md` (the front door, the two readers, the writers),
`design/json-unicode-policies.md` (the identifier and token policies), `design/readers-and-diagnostics.md`,
`design/facades-and-tree.md`.

## One configuration, two front doors

`Json.standard()` and `Json.of(ProcessorConfig)` are the whole construction surface, and they are `Tson`'s to the
letter:

| | TSON text | JSON |
|---|---|---|
| unconfigured | `Tson.standard()` | `Json.standard()` |
| configured | `Tson.of(config)` | `Json.of(config)` |
| readers | `treeReader()` / `objectReader()` | `treeReader()` / `objectReader()` |
| what it holds | `processorPolicy()` / `dataBindContext()` | `processorPolicy()` / `dataBindContext()` |

**The same `ProcessorConfig` value.** What a deployment states about reading TSON — what it will admit and spend,
where it may obtain a schema, which Java classes its types bind to — is one statement, and stating it twice
is two places for it to differ. That is why the configuration is a value in `tson-base` and construction is
not: `Tson.of` names the compiler's own registry and could never live there, but nothing about the
*settings* is an encoding's.

**One setting does not reach this encoding, and that is a division of labour rather than a gap.** The config's
schema access states how schema *text* is fetched, and turning that text into a resolved schema is the TSON
engine's — so `Json` names an already-resolved one through `withSchemas(TsonSchemaLoader)` instead, which holds a
`JsonCompiledSchemaRegistry` over the loader (`design/json-schema-directed-reading.md`).

**`Json` has no setter that restates the config.** A bind context or a policy is
`of(config.withDataBindContext(…))` or `of(config.withProcessorPolicy(…))`, and there is no
`Json.withDiagnostics`, as `Tson` has none: a receiver belongs to a **read**, not
to a deployment — two endpoints of one application legitimately differ on where problems go, and both
encodings' readers carry `withDiagnostics` for exactly that.

**What stays different is deliberate.** `Json` keeps the static `parse`/`toDisplayString` that JEP 540
defines, because a consumer arriving from the JDK's API should find it; they are the schemaless door, where
an instance is the configured one. `Tson` keeps `resolve`, `validate` and the registries, because JSON has
no schema documents of its own — §3.4 binds out of band — so it has a way to *name* a schema (`withSchemas`),
never a way to author one.

## One atom vocabulary, both encodings

`AtomContext` lives in `tson-base`'s `bind` package, and registers the host values the built-in atoms read to;
`tson-atom`'s `HostAtoms` is the index from the same host classes back to
the family that produces each. Both front doors' defaults start from `AtomContext`: `Json.standard()`,
`JsonObjectReader.standard()`, `Tson.standard()`. [TSON-JSON] §5.1 is why that is right rather than merely
tidy: a string's content is handed to the atom's own parser exactly as a TSON quoted token's text would be,
so *which* families a reader can bind is a property of the type system and not of the encoding that carried
them. A consumer whose class has a `UUID` component must not have to discover that one front door treats it
as a scalar and the other takes it apart.

What the registration buys is that `tson-bind` treats each host type as a **scalar** — `CidrInet4Network` is a
Java record and would otherwise bind as `{ prefix: … prefixLength: … }`, refusing the scalar `cidr4`/`cidr6`
actually carry. None of these registrations carries a bridge, so the string-to-host-value conversion is not
the registration's: it is the **family's**, and `HostAtoms.forStringContentHostType` is how a reader with no
type-ref reaches it. `JsonAtoms.fromString` asks that index, as `DataClassObjectReader` does on the text
side, so `"9f1c8e2a-…"` at a `UUID` component is `UuidParser`'s to accept or refuse under either encoding —
which is what §5.1 means by the string rule being the whole interface.

The index is deliberately **not total over the registered host types**, and what it excludes is every class
the family is not a function of. `mac`, `email` and `regex` read to `String`, so a `String` component cannot
say which of them (or `text`) it meant: the host class does not determine the family, picking one would be a
guess, and a position wanting those needs a schema to say so — which is what §5–§8's decode is for. The CIDR
pair is the same fact from the other direction: `cidr4` and `cidr6` have a host
type each (`CidrInet4Network`, `CidrInet6Network`), so a component naming one is answered here, and only the
sealed `CidrNetwork` supertype — which is genuinely ambiguous — stays out. The numeric families are excluded on a
different ground and one that matters more here: they read from a JSON **number**, so admitting them to a
*string*-content index would let a class declaring `BigInteger` turn `"123"` into one and overrule the
encoding's own kinds.

`SourcePosition`'s bridge could not travel: it names `tson-compiler`'s own `Position`, so that engine keeps
it in `config.ResolverBindContext`, applied on top of the shared list. The split is by what needs it — the
shared list is what a consumer's classes bind, the addition is what binding this library's own schema model
needs.

## What this is for, and what it constrains

The target is an **on-ramp**: someone holds a JSON Schema or an OpenAPI contract, converts it to a TSON
schema, and their existing JSON documents validate against it **unchanged**. Not "JSON that has been adjusted
for TSON" — the documents already in flight, byte for byte. That is the test the encoding has to pass before
any of its other virtues matter, because a format that asks a producer to change first has already lost the
audience it was converting for. §1.3's principle 2 is the same commitment from the spec's side: the common
case encodes to JSON "with no TSON-specific apparatus at all".

The goal is a real constraint, not a slogan: it decides what a converted schema may contain. A converter that
emits a schema outside the profile below produces one whose documents have to be rewritten, which is the one
outcome the exercise exists to avoid.

### What a converted schema will not contain

Each of these is a shape TSON has and a converted schema cannot reach — either because JSON Schema has no
source for it, or because reaching it would break documents already on the wire.

- **Non-text map keys.** JSON Schema's `additionalProperties`/`patternProperties` are string-keyed, so
  `{K => V}` with a compound `K` never arises. §6.5's **pairs form is therefore unreachable**, and with it one
  of §8.3's two class-stability leaks.
- **Annotations on data values.** No JSON carrier exists (§4.3) and JSON Schema has no source for one, so the
  encode-side refusal never fires. It stays implemented, for values that arrive from the text encoding.
- **Approximate atoms admitting the special values.** `.nan` and the infinities encode as JSON *strings*
  (§5.4) and no JSON document carries one, JSON having no spelling for them. A converted `type: number`
  should narrow `allow_nan`/`allow_infinity` to false — which closes §8.3's other leak, so **every type in a
  converted schema is class-stable** and §8.2's untagged route is available wherever the choice is disjoint.
- **Scoped positions.** `dynamic`/`extern` require an annotation object naming the type (§5.7, §8.5), which
  existing JSON does not carry. `additionalProperties: true` converts to the recursive `json` choice of §5.7,
  which is tag-free, never to `dynamic`.
- **In-band root binding.** Existing JSON has no `$schema`/`$type`, so the root binds by §3.4's out-of-band
  route — "the expected production route" in the spec's own words — through `Json.withSchemas` and
  `tson validate --schema --type`.

And two shapes that look like they belong on that list and do not: **tuples** convert from `prefixItems` and
are ordinary JSON arrays, and **defaults** convert and inject on decode (§6.1.3), so a document omitting a
defaulted member reads. Neither is friction.

### Where the on-ramp actually breaks

These are the ones that cost a producer a change, ordered by how often real JSON hits them. They are the
work, and the first is the largest single obstacle to the stated goal.

1. **A member name that is not an identifier.** `user-name`, `2fa_enabled`, `@type`, `first name`, `""` — all
   ordinary JSON, none a TSON field name. This is [TSON-DATA] §7.7's **grammar**, not §8.2's policy, so no
   configuration reaches it and no relaxation exists: kebab-case, JSON-LD's `@`-prefixed keys and OpenAPI's
   own `x-` extensions are simply unspellable as declared fields. The two available answers are both bad —
   collect them into an `@rest` map, which discards the typing that was the point of converting, or refuse.
   A projection annotation binding a wire spelling to a declared field would fit [TSON-SCHEMA] §6's licence
   exactly, on `@rest`'s own precedent; `SPEC-FEEDBACK.md` #5 states it.
2. **Records are closed and JSON Schema's are open.** `additionalProperties` defaults to *true*, so a
   converted record with no `@rest` field fails §6.1.1 on the first document carrying an extra member.
   **`@rest` is the default shape of a converted record**, not an optional refinement — which makes this
   encoding the annotation's first real consumer, as `BACKLOG.md` says.
3. **An untagged `oneOf` over object schemas.** All brace class, so non-disjoint; with no OpenAPI
   `discriminator` there is no in-band selector, and §8.2 requires the tag. JSON Schema validates such a union
   by *trying each branch*, which §8.2 forbids in as many words ("no trying variants in order"). Existing
   documents carry nothing to dispatch on, so this is a genuine wall: the converter finds a discriminator or
   reports. A `oneOf` that *does* carry one converts to a sealed record family rather than to a choice —
   §6.1.5, where member dispatch lives — which is the shape the contract already had.
4. **Enum members must be identifiers.** `"in-progress"` converts; `"not found"` does not. The fallback is a
   `text` refinement with a pattern, which costs the enum its discrimination class and so costs §8.2's untagged
   route a variant it could have dispatched on.
5. **Required-but-nullable.** `required: [x]` beside `type: [X, "null"]` has no TSON spelling — present with
   an absent value is not a state (§7.3), and OPTIONAL would *weaken* the source contract. Drop-with-report,
   per the companion note's list D.
6. **`format` becomes binding.** JSON Schema's `format` is advisory; the atom it converts to is not. A
   document that passed with a malformed `format: email` value fails here. That is the point of converting and
   still a change of behaviour, so a converter should say so rather than let it surface as a first-request
   failure.

## A stack of its own

`tson-json` does not build on `tson-compiler`'s `TsonEventSource`. The
argument for doing so is real — the compiled reader stack consumes that contract, so an encoding emitting those
events would reuse resolution, linking and every compiled reader unchanged. Two disagreements between the
formats defeat it, and both are the shape of the JSON-superset claim Revision 35 withdrew ([TSON-DATA] §1.1,
§6): a claim that looks like reuse and is paid for at every point where the two designs differ.

- **A brace does not say what it is.** TSON text tells a record from a map syntactically — `a: 1` against
  `k => v` — so `TsonDataStream` emits `RecordStart`/`FieldName` or `MapStart`/`MapArrow` and each compiled
  reader asserts which it got. JSON's `{"a": 1}` is one syntax for both, and §4.1 makes the *position* decide,
  never inspection of the value. A pull-only event source has no channel for the position to say so, and every
  way of giving it one — a hint call, a schema-walking stream, readers that accept either shape — puts the
  JSON encoding's problem inside the TSON reader stack.
- **`null` is two things.** In a plain JSON tree it is a value, and JEP 540 has a `JsonNull` for it. Under a
  schema it is the absent sentinel and nothing else (§7). Mapping it to `AbsentEvent` in the event layer
  settles that question one layer too early, and forces the schemaless reading to inherit a schema's answer.

So: `tson-json` has its own lexer, its own structural layer, its own tree, and its own readers.

## All the way up, and the seam is deferred rather than chosen

The schema-directed half of §5–§8 is `tson-json`'s own on the same terms: `JsonTypeReader`,
`JsonCompiledSchema`, `JsonSchemaCompiler` and its own factory registries, beside `tson-compiler`'s rather
than derived from them.

**The argument against the two disagreements above does not reach this layer, and that is worth saying
plainly** — because it is the reason this decision is a deferral rather than a conclusion. Both defeat a
*shared event source*, and both do so for one reason: a pull-only stream has no channel for the position to
speak. Above the schema that reason is gone. §4.1 makes the position decide, and a compiled reader **is** the
position — a record reader knows it is reading a record, a map reader a map, a reader at a `void` position
knows what `null` means there. The codebase already contains the precedent: TSON text's `{}` reaches the
reader as `EmptyBraceEvent` and is resolved from the position ([TSON-DATA] §2.8), not guessed at by the
stream. So a single compiled schema over an encoding-neutral read context, with the encoding pushed down into
a source the reader drives, is a real design and stays available.

It is not taken now because it is an abstraction designed from one implementation. The seam between what two
encodings genuinely share and what each owns is worth **finding** from two working stacks, not guessed at
from one and then discovered wrong through the one consumer that has to bend around it. Consolidating two
implementations that both pass their tests is cheap and safe; unpicking a shared contract that was wrong is
neither. The same discipline governs `@rest`, which stays unbuilt until a consumer has shown what the
directive's stated shape has to survive (`BACKLOG.md`): a member matching no declared field is §6.1.1's closure
error. Member dispatch over a sealed record family is built (`DispatchMemberReader`), reading the
discriminator fields the base record declares (`SPEC-FEEDBACK.md` #10, #11).

**It also costs nothing structurally, which is what makes the deferral free.** `TsonLinkedSchema` is a record
in `tson-schema`, a module requiring only `tson-base`, and `tson-atom` already re-exports it — so compiling
JSON readers from a linked schema adds **no dependency at all**, and in particular no dependency on
`tson-compiler`. The resolve → link → register pipeline that *produces* a linked schema is `tson-compiler`'s
and stays there; what crosses to this module is its output, which is a value model.

### Tree mode hands back JSON, and that settles what a schema-directed read is for

A schema-directed tree read produces a `JsonValue`, never a `TsonValue`. The read runs each family's parser —
which **is** the validation, [TSON-JSON] §5.1's contract boundary applied — and then discards the host value
it produced. `tson-cli` validating a JSON document against a TSON schema is the caller this exists for, and it
keeps only the diagnostics.

That is not a limitation worked around; it is what the two modes are *for*. Tree mode answers **does this
document conform**, and the answer is the diagnostics plus the document. Bind mode answers **give me the
value**, and a class is what says what to build. Converting an encoding is a third operation and belongs to
neither. A `TsonValue`-producing JSON read would be that third thing wearing the first one's clothes.

It also keeps the JEP 540 alignment true of the whole module rather than only its schemaless half, and it
keeps `TreeAtomReader` trivial: peek the event, let the family's reader consume and judge it, hand back
the node the document carried.

**What it costs is one kind of test.** With the host value discarded, "did `date` really parse this?" is
unobservable from a clean tree read — the evidence is only the diagnostic on a *bad* value. So
`ValueReaderFactoryRegistry.atoms()` stays: §5's vocabulary with no mode over it, which is what
`JsonAtomReadTest` compiles against to assert what each parser produced. It is what the modes are built over,
not a mode of its own.

### The risk this keeps, and the guard that holds it

Two copies of the field-state rules — §5.2's six field states, `REQUIRED_FIXED` injection, the FIXED check,
duplicate members, closure — can drift apart, and [TSON-JSON] §9.4 makes one diagnostic vocabulary across both
encodings a **specification obligation** rather than a tidiness. Duplication here does not cost maintenance
so much as it costs the guarantee that one schema yields one verdict over both encodings.

That guarantee does not need shared code. It needs to be checked, and to go red when it breaks:
`CrossEncodingParityTest` is one schema, the equivalent document in TSON text and in JSON, asserting the same
`Diagnostic.Code` and the same RFC 6901 pointer into the data — the two components a consumer routes on, never
the message, which is each reader's own prose. A local test rather than a corpus vector, because the corpus has
no way to state a fact about two encodings at all.

**What it pins are the codes two readers most easily give differently**: a size-facet violation is a constraint
code and never `TYPE_MISMATCH`, and an absent element or an absent tuple slot is `FIELD_REQUIRED`. Where the two
disagree the JSON reader is the one that moves — the TSON side is the incumbent, and one closed vocabulary means
the newcomer conforms.

**Its scope is the rules that are actually written twice**, and the boundary is worth stating because it is not
obvious. The field-state machine is duplicated: closure, duplicate members, §5.2's six states, injection, the
FIXED check, group multiplicity, container size and arity. The **atom vocabulary is not** — `tson-atom` is one
implementation both encodings call, so its acceptance sets and its split between contract rejection and
constraint violation cannot drift, and asserting them here would test the shared code twice.

**The guard runs both ways: a disagreement may be the incumbent's defect.** Value identity is the case the suite
carries — [TSON-SCHEMA] §5.5 and [TSON-JSON] §5.3 both put scale outside the value, so both stacks' `ValueIdentity`
compare the exact tier without it: a `number`-keyed map takes `1` and `1.0` as one key, a `set` of `number` as one
element, and a field `= 1.0` admits a document writing `1`. A disagreement that is a defect is fixed on the side
that has it and never pinned as expected divergence — pinning a defect as agreed behaviour is how it becomes
permanent.

**Between those two sits one legitimate divergence, and the test asserts it as a divergence.** JSON has six
value *kinds* where TSON text has tokens: `name: 42` at a `text` field is the unquoted token `42`, whose
content `text`'s contract accepts ([TSON-DATA] §5.2), while JSON's `42` is of the number kind and §5.6 admits
only strings there. Neither reader is wrong. §5.1 makes *which kinds reach a family's parser* each encoding's
own — it is the whole of what `AtomForm` decides — so this difference is by specification, and pinning it
is what stops a later change quietly "fixing" it into agreement.

## Packages

Three exported, layered the way the module reads a document, and the split is the one `tson-compiler` and
`tson-tree` already draw between a front door, a value model and an engine:

| Package | Holds |
|---|---|
| `io.ltr8.tson.json` | `Json` (the front door); `JsonTreeReader`, `JsonObjectReader`; `JsonTreeWriter`, `JsonObjectWriter`, `JsonDataEmitter`; `JsonReadContext`, `JsonPosition`, `JsonSchemaLocation`, `JsonDiagnostics`; the schema-directed surface — `JsonTypeReader`, `JsonCompiledSchema`, `JsonCompiledSchemaRegistry`, `JsonSchemaCompiler` |
| `io.ltr8.tson.json.tree` | `JsonValue` and its six node types, `JsonText`, plus `JsonValueException` |
| `io.ltr8.tson.json.stream` | `JsonEvent`, `JsonEventSource`, `JsonStream` |
| `io.ltr8.tson.json.reader` | internal — the two schemaless engines (`SchemalessTreeReader`, `DataClassObjectReader`); the schema-directed readers, the mode-free container loops, each over a plan and handing its slots to the mode's builder (`RecordReader`/`RecordPlan`, `ArrayReader`/`ArrayPlan`, `TupleReader`/`TuplePlan`, `MapObjectReader`/`MapPairsReader` over a `MapPlan` with `MapEntries`; `Tree*Builder`/`Bind*Builder` for each), `TreeAtomReader`, `BindTargets` binding a position to a component, `BridgedReader`, and `Slots`' markers; the dispatchers every mode shares (`DispatchFactories` over `DispatchTagReader`/`DispatchMemberReader`, `DispatchChoiceReader`, with `Route` and `ExactReader`) with `AtomReader`, `AtomForm`, `VoidReader`, `ValuePositionReader`, `DeferredTypeReader`, `OpenTemplateReader`, `ErrorReader`; the factory registries (`ValueReaderFactory`, `ValueReaderFactoryRegistry`, `ValueReaderFactoryResolver`, `ValueReaderContext`, `TypeReaderResolver`, `CompiledReaders`); and what they share — `ReservedMembers`, `Tags`, `NameHygiene`, `ReferenceChain`, `DiscriminationClass`, `ValueIdentity`, `FieldValue`, `Nodes`, `EventSkip` |
| `io.ltr8.tson.json.writer` | internal — the two write engines, `TreeValueWriter` and `DataClassObjectWriter` |
| `io.ltr8.tson.json.atom` | internal — `JsonAtoms`: one JSON leaf into one host value at a bound class's atom position |
| `io.ltr8.tson.json.lexer` | internal — `JsonLexer`, `JsonToken`, `JsonTokenType`; a consumer names a value, an event or a reader, never a token or a parser |

`tree` is the JSON counterpart of `io.ltr8.tson.tree` and stands in the same relation to its front door:
`Json.parse` returns a `JsonValue` as `Tson`'s tree reader returns a `TsonValue`. `JsonObjectReader` sits
in the front door beside `Json` for the same reason `TsonObjectReader` sits beside `Tson` — a reader is a
front door, not a layer of one; the two writers sit there on the same terms, over the unexported `writer`
package's engines. `atom` is unexported and holds the schemaless bind path's leaf conversion: §5.1 hands a
string's content to the atom's own parser exactly as a TSON quoted token's text would be, which does not belong
in a reader that walks structure. The schema-directed peer is `reader`'s `AtomReader`. `stream` is exported for
the reason `tson-compiler` exports its own — `Json.parse` takes a `JsonEventSource`, so it is a real contract
rather than an internal dispatch type, and JEP 540 excludes streaming as a non-goal, so a caller who needs it
has nowhere else to go.

The rendering lives on `JsonValue.toDisplayString(indent)` rather than only on `Json`, because the string
quoting it needs is `tree`'s and package-private there. `Json.toDisplayString(value, indent)` is JEP 540's
spelling of the same call and delegates.
