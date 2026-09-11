# The JSON encoding

Design notes for `tson-json` — the implementation of [TSON-JSON] (`spec/tson-part3-json.md`), the JSON
encoding of the TSON schema system. Current form only; history lives in git. `CLAUDE.md` holds the
one-paragraph orientation; this file holds the detail.

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

Not every setting reaches this encoding yet. The schema access waits on §5–§8's schema-directed decode,
which is the point at which a JSON document has a schema to obtain at all. Holding the whole value now is
what stops that arriving as another setter and the two front doors drifting again.

**What was removed to get here** was three methods that each said something the config already said:
`Json.using(context)`, `Json.withProcessorPolicy(policy)`, and `Json.withDiagnostics(receiver)`. The first
two are `of(config.withDataBindContext(…))` and `of(config.withProcessorPolicy(…))`. The third was an
asymmetry rather than a duplicate: `Tson` has no such method because a receiver belongs to a **read**, not
to a deployment — two endpoints of one application legitimately differ on where problems go, and both
encodings' readers carry `withDiagnostics` for exactly that.

**What stays different is deliberate.** `Json` keeps the static `parse`/`toDisplayString` that JEP 540
defines, because a consumer arriving from the JDK's API should find it; they are the schemaless door, where
an instance is the configured one. `Tson` keeps `resolve`, `validate` and the registries, because JSON has
no schema documents of its own — §3.4 binds out of band — so it will gain a way to *name* a schema, never a
way to author one.

## One atom vocabulary, both encodings

`AtomContext` lives in `tson-atom`, beside `HostAtoms` — the index from the same host classes back to
the family that produces each — and both front doors' defaults start from it: `Json.standard()`,
`JsonObjectReader.standard()`, `Tson.standard()`. [TSON-JSON] §5.1 is why that is right rather than merely
tidy: a string's content is handed to the atom's own parser exactly as a TSON quoted token's text would be,
so *which* families a reader can bind is a property of the type system and not of the encoding that carried
them. A consumer whose class has a `UUID` component must not have to discover that one front door treats it
as a scalar and the other takes it apart.

What the registration buys is that `tson-bind` treats each host type as a **scalar** — `CidrNetwork` is a
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
pair used to be the same fact from the other direction and is not any more: `cidr4` and `cidr6` have a host
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
  converted schema is class-stable** and §8.2's route 2 is available wherever the choice is disjoint.
- **Scoped positions.** `dynamic`/`extern` require an annotation object naming the type (§5.7, §8.5), which
  existing JSON does not carry. `additionalProperties: true` converts to the recursive `json` choice of §5.7,
  which is tag-free, never to `dynamic`.
- **In-band root binding.** Existing JSON has no `$schema`/`$type`, so the root binds by §3.4's out-of-band
  route — "the expected production route" in the spec's own words. `BACKLOG.md` carries the front-door and
  CLI surface that owes.

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
   reports.
4. **Enum members must be identifiers.** `"in-progress"` converts; `"not found"` does not. The fallback is a
   `text` refinement with a pattern, which costs the enum its discrimination class and so costs §8.2 route 2
   a variant it could have dispatched on.
5. **Required-but-nullable.** `required: [x]` beside `type: [X, "null"]` has no TSON spelling — present with
   an absent value is not a state (§7.3), and OPTIONAL would *weaken* the source contract. Drop-with-report,
   per the companion note's list D.
6. **`format` becomes binding.** JSON Schema's `format` is advisory; the atom it converts to is not. A
   document that passed with a malformed `format: email` value fails here. That is the point of converting and
   still a change of behaviour, so a converter should say so rather than let it surface as a first-request
   failure.

## The Unicode policies, and which reach JSON

[TSON-DATA] §8.2's two policies are one object (`ProcessorPolicy`) and reach this encoding
differently. §9.4 states the split: the **identifier policy** reaches member names read as field names and
every `$type`; the **token policy**, when a deployment sets one, reaches map keys and string values.

**The identifier policy runs at schema load, and at exactly one place in a data read.** A converted schema's
declared names are judged once, when the schema links (`TsonSchemaLinker.checkNames`, §11.4's scopes) — so a
member name matching a declared field has already inherited that verdict and needs no second test, which is
what §9.4's parenthetical means. The one name that reaches the policy fresh is a member matching **no**
declared field in a record with **no** rest field, and it must be tested before it is reported: a homoglyph
(`pаssword`, Cyrillic а) would otherwise get `UNRECOGNIZED_FIELD` — a *verdict* — where §8.2 requires a
refusal reported in none of the four categories. That single case is the whole of the identifier policy's job
at the JSON data layer, and it is why the check cannot simply be dropped as redundant.

**A rest key is a map key, not a name.** §6.2 collects unmatched members into the rest map, parsed by its key
type; §9.4 puts map keys under the *token* policy, which defaults to `unrestricted()`. So the order matters —
declared fields, then rest collection, then hygiene — and a converted schema's `@rest` tail is what keeps
ordinary foreign JSON from meeting an identifier rule at all. That is the on-ramp working as intended, not a
hole: the names in a rest map were never declared, so nothing about them is a name.

**A JSON tree read with no schema applies neither policy.** There is no Class 1 in this encoding (§1.3
principle 1, §1.5) — a JSON document with no binding is just JSON, and its member names are data. Judging
them under an identifier policy would refuse ordinary JSON for a rule that exists only where names are
declared.

**When each arrives.** Neither belongs in the lexer or the event layer, for the reason
`DefaultTsonReadContext`'s Javadoc gives on the other side: a refusal needs a receiver, and a layer that can
only throw can only say "invalid", which is the one thing a policy refusal is not. Both arrive with the
schema-directed decode: the identifier policy in the record reader's unmatched-member path, the token policy
in the stream over keys and string values, the way `TsonDataStream` applies it on the TSON side.
§10.1's limits policy is different and arrives sooner — nesting depth is counted in the event layer, the one
place every token is consumed.

## A stack of its own

`tson-json` does not build on `tson-compiler`'s `TsonEventSource`. That was the plan `BACKLOG.md` carried, and
the argument for it was real — the compiled reader stack consumes that contract, so an encoding emitting those
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
neither. The same discipline governs `@discriminator` and `@rest`, which stay unbuilt until this reader is
what exercises them.

**It also costs nothing structurally, which is what makes the deferral free.** `TsonLinkedSchema` is a record
in `tson-schema`, a module requiring only `tson-base`, and `tson-atom` already re-exports it — so compiling
JSON readers from a linked schema adds **no dependency at all**, and in particular no dependency on
`tson-compiler`. The resolve → link → register pipeline that *produces* a linked schema is `tson-compiler`'s
and stays there; what crosses to this module is its output, which is a value model. This corrects the
long-standing prediction — in `BACKLOG.md`, in this module's build file and in `CLAUDE.md` — that §5–§8 is
where `tson-json` gains that dependency. It is not.

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

**It earned its place on the first run**, catching three disagreements before any of them could reach `main`: a
size-facet violation reported as a constraint code on one side and `TYPE_MISMATCH` on the other, and an absent
element and an absent tuple slot reported as `TYPE_MISMATCH` where the TSON reader says `FIELD_REQUIRED`. All
three were the JSON reader's to fix — the TSON side is the incumbent, and one closed vocabulary means the
newcomer conforms.

**Its scope is the rules that are actually written twice**, and the boundary is worth stating because it is not
obvious. The field-state machine is duplicated: closure, duplicate members, §5.2's six states, injection, the
FIXED check, group multiplicity, container size and arity. The **atom vocabulary is not** — `tson-atom` is one
implementation both encodings call, so its acceptance sets and its split between contract rejection and
constraint violation cannot drift, and asserting them here would test the shared code twice.

**It found a defect in the incumbent, which is the outcome a parity guard is least likely to be built for and
most valuable for.** `tson-compiler`'s `ValueIdentity` folded four host types into their value space and had
no `BigDecimal` case, so the exact tier compared with scale: a `number`-keyed map admitted `1` and `1.0` as
two keys, a `set` of `number` admitted both, and a field `= 1.0` turned away a document writing `1` —
refusing a conforming document rather than admitting a malformed one, which is the worse direction of the
two. [TSON-SCHEMA] §5.5 and [TSON-JSON] §5.3 both put scale outside the value. The JSON reader already had
the case, so the disagreement surfaced the moment maps landed.

The parity case was **held out of the suite until the TSON side was fixed**, rather than pinned as expected
divergence — pinning a defect as agreed behaviour is how it becomes permanent. It is in the suite now.

**Between those two sits one legitimate divergence, and the test asserts it as a divergence.** JSON has six
value *kinds* where TSON text has tokens: `name: 42` at a `text` field is the unquoted token `42`, whose
content `text`'s contract accepts ([TSON-DATA] §5.2), while JSON's `42` is of the number kind and §5.6 admits
only strings there. Neither reader is wrong. §5.1 makes *which kinds reach a family's parser* each encoding's
own — it is the whole of what `AtomForm` decides — so this difference is by specification, and pinning it
is what stops a later change quietly "fixing" it into agreement.

## Binding a document, which is the one thing JSON cannot do for itself

A TSON document names its own schema in its header and its own root type with a type-ref. A JSON document
can do neither — `!!schema` is TSON text syntax — so [TSON-JSON] §3.4 gives it two routes and this stack
implements the first: **out of band**, the application supplies both, and the document is then a bare value
read directly at that type. The spec calls it "the expected production route", and the in-band route needs
§3.3's annotation object, which arrives with §8.

`Json.withSchemas(loader)` is where a schema identity becomes resolvable, and it takes a `TsonSchemaLoader` —
`tson-schema`'s interface, so it costs no dependency. **Obtaining a schema is the TSON engine's job**, and
that is a division of labour rather than a gap: a schema document is TSON text whichever encoding the data
arrives in, so parsing, resolving and linking one belongs where that engine lives, and what crosses here is
the linked result. An application reading both encodings resolves once through `Tson` and hands
`tson.schemaRegistry()` over.

From there it is the TSON facades' shape: `treeReader().withSchema(uri).readAs(source, rootType)`, with
`JsonCompiledSchemaRegistry` caching the compiled readers per canonical identity, and `Json.validate(source,
schemaUri, rootType)` as the collecting door the CLI runs through. **Failing to reach the schema is a
diagnostic and never a verdict**: `SCHEMA_NOT_FOUND` for an identity the loader has none for, `UNKNOWN_TYPE`
for a root type the schema does not declare, and `Code.verdict()` separates the first from anything the
document did.

### The annotation object, and the lookahead it finally required

TSON text attaches a type annotation beside a value; JSON has no beside, so §3.3's **annotation object** is
the carrier — wrapper (`{"$type": "age", "$value": 42}`) or inline (`{"$type": "employee", "name": "Ada"}`,
when the selected type reads the value as a record) — over §3.2's closed reserved set of `$schema`, `$type`
and `$value`. §6.1.5 is what it buys at a record position: a tag naming a subtype, validated in full, which
is the JSON spelling of `!employee` at a `person` field.

**The class is `ReservedMembers`, not the spec's own noun, and the divergence is deliberate.** In this
codebase `Annotation` means an `@name` annotation and nothing else — two dozen types say so, from the
`tson-annotation` module through `Annotations`, `TsonAnnotation` and the `AnnotationStart`/`AnnotationEnd`
events — and those have **no JSON carrier at all**: §4.3 declines one for v1 and makes encoding a value that
carries them an encode error. A type named for §3.3 would be the single place the word meant something else,
so it is named for the §3.2 namespace it scans and cites §3.3 throughout. The spec's noun is right for the
spec, where `@name` annotations are §3.1's and no reader is looking at a Java identifier to tell them apart.

## Naming inside `reader`: mode first, and no prefix

The schema-directed readers are named **mode, then family, then form** — `TreeRecordReader`,
`TreeMapObjectReader`, `TreeMapPairsReader` — so that bind mode lands as `BindRecordReader` beside its peer
and a reader's mode is the first thing about it. That is the axis someone scans when adding a mode, and it is
the axis a file listing then sorts by.

**The `Json` prefix is dropped in `reader` and kept in the root**, which is `CLAUDE.md`'s rule applied rather
than an exception to it: a prefix earns its keep disambiguating a name a *consumer* writes, and `reader` is
unexported. The root package keeps it for exactly that reason — `JsonReadContext` beside a domain
`ReadContext`, `JsonTypeReader` beside `TsonTypeReader`.

The consequence worth having is that **thirteen of these now share a bare name with their `tson-compiler`
counterpart**: `ErrorReader`, `OpenTemplateReader`, `EventSkip`, `CompiledReaders`, `DiscriminationClass`,
`ValueReaderFactory`, `ValueReaderContext`, `ValueReaderFactoryResolver`, `ValueReaderFactoryRegistry`,
`VoidReader`, `DeferredTypeReader`, `ReferenceChain`, `ValueIdentity`. The two stacks read as peers, and which TSON class
a JSON class answers to is visible at a glance — which is what a parallel implementation wants and what the
prefix was hiding. Nothing imports both, neither package being exported, so the shared names cost nothing.

**Recognising one needs a rewindable lookahead, and this is the position the parallel-stack decision
predicted would need it.** §6.1.6 gives member order no meaning, so `$type` may sit anywhere in the object
and the opening brace settles nothing — a schema-directed reader must read into a value before it knows
which reader owns it. `JsonReadContext.lookingAhead` is the peer of the TSON context's: a probe runs against
the cursor and every event it consumed is replayed from a buffer rather than re-lexed. The scan reads member
*names* only, skipping values without materialising them.

**It costs a second pass over each record's events**, replayed from memory rather than the lexer, and the
scan runs before every record read because a redundant tag is admissible at any typed position (§8.1: "a tag
is never wrong"). There is no sound shortcut: peeking the first member cannot conclude, because order is
free. `BACKLOG.md` carries it as something to measure rather than something to assume.

Two rules fall out of the scan and are worth naming because they look like omissions:

- **The record reader passes over a reserved member silently.** By the time members are being read, the scan
  has already judged them — an unknown `$name` refused, a `$schema` refused, a `$type` resolved against the
  position. Passing over one is the decision already taken, not a decision skipped.
- **`$schema` is refused everywhere this can reach.** §8.5 admits it only where the effective type is a
  `scoped` instance holding EXTERN, and §3.3 makes it a resolver error anywhere else. That is the correct
  verdict at every position built so far, and the scoped reader is what will admit it.

`CompiledReaders` arrives with this, and carries `tson-compiler`'s own hazard: it is **rebound exactly
once**, from the in-progress compilation to the finished schema, because handing readers the compilation's
resolve would leak its mutable state past the compile. Only the edges that need a name at read time consult
it — a subtype named by `$type`, and whatever §8's dispatch reaches.

### The map form is chosen by the factory, not re-asked per value

§6.5 selects between the object and pairs forms **by `K`, never by inspecting the value**, and that selection
is therefore made once: `TreeMapReader` is a sealed base over `TreeMapObjectReader` and
`TreeMapPairsReader`, and the factory returns whichever the key type names. Neither subclass carries the
other's state or a branch it never takes, and §4.1's "nothing is read speculatively" is structural rather than
a thing the read remembers to honour. What stays on the base is what both forms share and nothing else: §6.5's
entry-value rule, the size facets, and the test that picks between them — which §8.3 also asks, to judge
whether a map is class-stable.

### Discrimination: two routes, and the table is built at schema load

§8.2's predicate is the rule [TSON-SCHEMA] §5.4 requires each encoding to state over the resolver-derived
`disjoint` fact, and it is closed: a value may omit its tag by exactly two routes and "MUST NOT be extended by
implementation cleverness — no member-shape matching among record variants, no value-set separation, no trying
variants in order." `TreeChoiceReader` implements route 2 — disjoint plus class-stable, selecting on the
arriving value's kind — and nothing more. Route 1, a declared `@discriminator`, is unbuilt, so a choice
carrying one falls through to *the tag is REQUIRED*: the correct verdict for a reader without the route, and
not a quiet approximation of it.

**The verdict is computed once per choice, at compile time**, which is what §8.3 asks for in so many words —
"the wire decision is then a table hit, not a per-value derivation". The table is empty exactly when the tag is
required, so a read has one question to ask and the answer is a map lookup.

**Both halves of route 2 are needed and neither implies the other.** `disjoint` guarantees at most one variant
per class; class stability guarantees the arriving kind actually lands in its variant's class. Without the
second, a `float64` variant still admitting `.nan` receives a JSON *string* (§5.4) and would dispatch as though
a string variant had been chosen. The unstable set is closed to two members — that leak, and a map forced into
pairs form — so the test is two cases rather than a survey, and narrowing `allow_nan`/`allow_infinity` restores
route 2, which is the checkable reason §8.3 gives an API author to narrow.

**`DiscriminationClass` duplicates the TSON reader's derivation**, which lives in an unexported package.
That is the parallel stack's cost showing up where it matters most, because the fact is derived from the schema
alone — a choice dispatching one way in text and another in JSON would be pure drift. The parity test carries
the cases.

**A missing tag is `UNKNOWN_TYPE_REF`**, the code the TSON reader gives the same document. Read literally it is
a poor fit — nothing unknown was written — and the closed `Code` enum has no member for "a required tag is
missing", which `BACKLOG.md` records. §9.4 gives both encodings one vocabulary, so agreeing matters more than
the name and the incumbent settles which member.

**Two divergences the parity test pins as divergences**, both structural rather than drift. A **choice cannot
be a root type in text**: the root type-ref is both the binding and, at a choice position, the variant tag, so
`!scalars "hi"` is refused for naming the choice rather than a variant — while JSON binds out of band (§3.4),
where a root type and a tag are separate statements. And a refusal about a tag lands at **different pointers**:
`/outline/$type` in JSON, where the tag is a member with a location of its own, against `/outline` in text,
where it is an annotation beside the value and the value's pointer is the nearest thing there is.

### The CLI reads one filename, and §3.1 is why

`tson validate` classifies TSON files by content and never by name — a header carrying `!!meta` is a schema.
A `.json` input is classified by its extension instead, which §3.1 licenses outright: "No file extension of
its own is defined: a JSON encoding of TSON data is a JSON file, and `.json` is its extension." The axis is
the *encoding*, and the `!!id` rule says nothing about it.

`--schema` and `--type` are one statement and are given together; they bind every JSON input in the run.
Three things are usage errors rather than verdicts, because each is the command line's mistake and not a
document's: half a binding, a `.json` input with no binding, and a binding with no JSON input to bind — the
last on the same habit `PolicyOptions` already applies to a relaxation that scans nothing. A mistyped
`--type` is one too, checked before any document is read, so one typo prints once at exit 2 rather than
once per file at exit 1 saying the documents were wrong.

Standard input has no name to classify by, so the binding is what marks it JSON. That is not a guess: the two
flags exist for nothing else, a `.tn` document naming its own binding in its header.

**One thing the CLI surfaced that the library owed.** `unknownTypeMessage` listed the namespace in order, and
an `!!import` merges the imported entries *first* — so a schema declaring one type over core.tn reported
"whose types are (void | boolean | integer | ... and 44 more)" and showed the author none of their own. It
now leads with the entries this schema declares and the author wrote, filtering by the origin index and by
having a source position (the same test that tells a minted entry from an authored one). Imported and minted
names remain usable root types; the count that follows covers them.

## Aligned with JEP 540 — and only in the tree

[JEP 540](https://openjdk.org/jeps/540) puts a simple JSON API in the JDK — `jdk.incubator.json`, JDK 28, not
available to build against now. The tree model follows its shape and its names: a sealed `JsonValue` over
`JsonObject`/`JsonArray`/`JsonString`/`JsonNumber`/`JsonBoolean`/`JsonNull`. A consumer moving between the two
learns one value model, and a bridge is later a mapping rather than a rewrite.

**The alignment is the `tree` package and its vocabulary. It is not a claim about anything else.** Reading,
writing and the exceptions either raises follow the **TSON side of this library** — `TsonTreeReader`,
`TsonObjectReader` and what they throw — because a consumer here holds documents in two encodings and must
route on one rule, not on which encoding happened to refuse. Where the JDK's shape and this library's
behaviour disagree, this library wins, and every JEP 540 mention elsewhere in the code should be read as
naming a value model rather than settling a behaviour.

Three consequences, all of them already true:

- **A fail-fast read throws `ReadException`, never a parse exception.** A syntax failure goes through the
  read's own receiver like every other problem ([TSON-DATA] §8.1 makes it a verdict the sender can act on),
  and for a fail-fast read the receiver is what throws — so `Json.parse("[1] 2")` raises `ReadException` with
  the position and `Diagnostic.Code` on `diagnostic()`, exactly as `new TsonTreeReader().read(…)` does. There
  is no `JsonParseException` in this library; the stack raises `tson-base`'s shared `ParseException` beneath
  the readers, and the readers classify it (`JsonDiagnostics`).
- **A collecting read never throws for a bad document.** It returns nothing and the collector says why —
  again the TSON readers' rule, not a JDK one, JEP 540 having no diagnostics model at all.
- **Diagnostics are one vocabulary across both encodings** ([TSON-JSON] §9.4), so a JSON problem is a
  `Diagnostic` with a `Code` from the same closed enum, never a JSON-specific report.

**`Json` leads a name here, on `Tson`'s own terms.** `CLAUDE.md`'s rule reserves a prefix for types a consumer
of this library names in their own code, and the names a consumer writes in this module are the JDK's — so
this module keeps them rather than minting a second vocabulary for one hierarchy.

Three places this must differ, each because §3.1 requires it:

- **It decodes UTF-8 itself, from bytes.** JEP 540 parses an already-decoded `String` or `char[]`. §3.1 makes
  the document MUST-be-UTF-8 and an invalid byte sequence an error rather than a U+FFFD substitution; a
  decoder handed characters cannot report what it never saw.
- **Every position carries a byte offset.** [TSON-DATA] §8.1 requires one of every error report and §9.4
  carries the requirement into this encoding. JEP 540 reports line and position only.
- **Numbers keep their digits.** §3.1 forbids rounding one silently and §5.3 preserves an exact number's
  digits and scale. JEP 540 reaches the same place by a different route (`new BigDecimal(number.toString())`),
  so this is alignment rather than divergence — but it is load-bearing here, where it is a courtesy there.

## Lexer (`tson-json/.../lexer/`)

`JsonLexer` is a single hand-written scanner over UTF-8 bytes read incrementally from an `InputStream` --
**bytes only, with no `String` entry point**, since §3.1 makes the document UTF-8 and a decoder handed
characters has already lost the malformed-sequence rule and the byte offset §8.1 requires. A caller holding
a string is one `getBytes(UTF_8)` away, and `Json.parse(String)`/`JsonObjectReader.read(String, …)` do it
there so one place re-encodes rather than every layer offering to. It is
code-point addressed, with one code point of lookahead — no JSON token needs more. `nextToken()` returns only
a `JsonTokenType`, the text and the six position coordinates read off separate accessors, so a token costs no
`JsonPosition` allocation unless a caller retains one; `tokenize()` materializes `JsonToken` snapshots and is
for tests and small inputs. It is `tson-compiler`'s `Lexer` in shape, and deliberately not in code: the two
grammars share no production.

**Grammar, not structure.** `"] : ,"` lexes clean; refusing it is the structural layer's job. Member names
are not deduped here either — §3.1 makes a repeat an error whose *category* follows the position's type, which
is a fact no lexer holds.

The §3.1 profile, at the points it reaches the lexical layer:

- **UTF-8, decoded here.** Overlong forms, encoded surrogates and out-of-range code points are refused by the
  decoder, and a malformed sequence reports the offset of its own first byte. Nothing is replaced. The
  smuggling case is the reason: two spellings of one character, one of which a validator upstream may not have
  seen — §10.2's concern, one layer down.
- **A single leading BOM is discarded** and counts toward neither line, column nor offset — which §3.1
  requires of a decoder in the same breath as forbidding an encoder to emit one. A BOM anywhere else is an
  ordinary character: content inside a string, and not the start of any JSON value between tokens.
- **Strings decode to Unicode scalar values.** A well-formed `😀` pair is one character — the
  pairing mechanism is JSON's and this profile accepts JSON's grammar. A surrogate escape with nothing to pair
  is refused, not repaired: `text`'s value set is scalar sequences ([TSON-DATA] §7.2.2), and a JSON text
  encoding data no value can hold is rejected. This is the I-JSON (RFC 7493) reading, which RFC 8259 permits.
- **`\/` stays.** [TSON-DATA] §7.2.2 dropped it from the text encoding, whose own table had labelled it "(JSON
  compat)" — a debt to this format that this format never owed itself. Here it is simply one of JSON's eight
  escapes.
- **A number is its exact source lexeme.** Nothing converts. `199.90` reaches the layer above as `199.90`,
  which is what §5.3 promises and what a `double` would have destroyed before any rule could keep it.
- **RFC 8259 exactly, tightened.** No comments, no trailing commas, no unquoted names, no leading `+`, no bare
  `.5` or `5.`, no hexadecimal, no `NaN`/`Infinity` — the special values reach a JSON document as *strings*
  and only where an approximate atom admits them (§5.4). JEP 540 refuses the same set, for the same reason.

Two lexical rules differ from the text encoding's and are worth naming, because both look like oversights:

- **Whitespace is four characters**, RFC 8259's, where the TSON lexer's Pattern_White_Space set is eleven. A
  U+00A0 between tokens is an error here.
- **Line breaks are LF, CR and CRLF.** NEL/LS/PS are line terminators to [TSON-DATA] §7.2 and ordinary
  characters to RFC 8259. Counting a line at one would put this lexer's positions out of step with every other
  JSON tool's, over a document that is otherwise byte-identical.

Errors are `tson-base`'s shared `ParseException`, thrown immediately — fail-fast, as the TSON lexer is. **The message states
what went wrong and never where**; `position()` is the location, so a diagnostic built from one carries it
structurally rather than by parsing prose. §9.4 splits what the exception covers across two of [TSON-DATA]
§8.1's categories — a lexer error for malformed UTF-8 and ill-formed strings, a parse error for grammar
violations — and that split is the schema-directed layer's to make when it classifies one into a `Diagnostic`.
Carrying it on the exception as well would be a second opinion about one fact.

## Structural layer (`tson-json/.../stream/`)

`JsonStream` is RFC 8259's grammar over the lexer, as a lazy pull-based `JsonEventSource`. It is the only
thing above the lexer that walks source text; it holds a frame stack and nothing else, so memory is
proportional to nesting depth rather than to document size, and the layers above consume events and hold no
grammar of their own. `JsonEvent` is a sealed hierarchy of ten records — the two container pairs,
`MemberName`, the four leaves, and `EndOfDocument`.

**No token lookahead at all.** JSON's grammar is LL(1) on already-lexed tokens and never needs a pushback:
after `{` the next token is either `}` or a member name and consuming it decides which. That is where
`TsonDataStream` spends two tokens, on the record-versus-map brace idiom — an ambiguity JSON does not have
because it does not draw the distinction. `peek()` holds back a produced event, not a token.

**There is no document-start event.** `JSON-text` is one value and carries no header, so there is nothing for
one to hold; the TSON counterpart exists only for `!!id`/`!!schema`. `EndOfDocument` does exist and is not
merely `hasNext() == false` — **producing it is what pulls past the root value, and so what rejects trailing
content**. `[1] 2` is refused there and nowhere else, the same trap the TSON facades keep under
`requireDocumentEnd`.

**`NullValue` is a value here.** §7 makes JSON null the absent sentinel's spelling *at a typed position*, and
this layer has none. Settling it in the event vocabulary is exactly the mistake a shared `TsonEvent` would
have forced.

**Grammar only**, on §3.1's own layering, and three things follow: member names are not deduped (§3.1 makes a
repeat an error whose *category* follows the position's type, which no grammar layer holds — the tree applies
the rule where JEP 540 does, the schema-directed decode applies it with a category); no value is interpreted;
and no member name is reserved, §3.2's `$`-namespace being a question about the position's type.

**One constructor**, `JsonStream(InputStream, ProcessorPolicy, DiagnosticsReceiver)`. A stream reads under a
policy and reports through a receiver, and both are always true, so neither is defaulted: a caller with
nothing particular to say forms `ProcessorPolicy.defaults()` and `DiagnosticsReceiver.throwing()` where they
can be seen, rather than picking them up from an overload that hides which defaults it chose. The bound comes
off the policy rather than as a number, so there is no way to hand the stream a depth `LimitsPolicy` would
have refused — that record refuses a bound below one, once, for every encoding.

**`withProcessorPolicy` is `JsonObjectReader`'s only policy derivation.** `ProcessorPolicy` already carries
`withIdentifierPolicy`/`withTokenPolicy`/`withLimits`, so a caller changing one component writes
`r.withProcessorPolicy(r.processorPolicy().withTokenPolicy(p))` — one method on the reader, and the component
derivations where the components live. The TSON facades carry all four for history; a new surface need not.

**Nesting depth is bounded here** (§10.1) — the one place every container opens, so a refusal lands before
any consumer descends, which matters because every consumer of this stream recurses where the stream itself
iterates. The bound arrives as an `int` because a stream needs a number rather than a policy, but **the
number and the refusal are the processor's, not this encoding's**: §10.1 makes it [TSON-DATA] §9.1's policy
"in JSON clothing, and the same policy applies with the same defaults", so the stream counts against
`LimitsPolicy.DEFAULT_MAX_DEPTH` and refuses with `LimitExceededException` — the same type the text
encoding refuses with, from `tson-base`. A deployment that raises the bound raises it for both encodings at
once, which is what one policy means.

That refusal type stays distinct from `ParseException`, and has to be: §10.1 makes a refusal §8.1's
fifth outcome, never a verdict, so it must be distinguishable or a configured bound reaches a consumer as a
syntax failure. `JsonDiagnostics` keeps them apart on the way out too — `Diagnostic.ofLimitExceeded` is
tried ahead of `ofBaseSyntaxError`, so the code a consumer routes on says which happened.

**Error messages name the construct the position admits**, not the token class found — "a member name is due"
where "expected STRING" would tell an author what a lexer calls the thing they already wrote.
`TsonSchemaParser` makes the same choice. One case escapes it and is pinned rather than papered over: `{a: 1}`,
the JS object-literal habit and the commonest of these mistakes, never reaches the grammar at all — `a` starts
no JSON token, so the lexer refuses it first and names the character. The grammar cannot improve on that
without the lexer knowing what position it is at, which is the layering.

## Tree (`tson-json/.../tree/`)

`JsonValue` is a sealed interface over six records — `JsonObject`, `JsonArray`, `JsonString`,
`JsonNumber`, `JsonBoolean`, `JsonNull` — with JEP 540's navigation (`get`/`tryGet`/`tryValue`),
conversions (`asString`/`asInt`/`asLong`/`asDouble`/`asBoolean`/`asMap`/`asList`), `of` factories, and
`Json.parse`/`Json.toDisplayString`. `Json.parse` reduces a `JsonEventSource` and holds no grammar; both
`String` and `InputStream` overloads exist, and the `InputStream` one is where §3.1's rules actually bite.

**Three divergences from JEP 540, each with a reason.**

1. **No source position on a node.** JEP 540 reports a navigation failure with "Location: line 13,
   position 19", which means its nodes know where they came from. These do not, so equality is over
   content and two parses of one document are equal — the shape `TsonValue` takes, and for the same
   reason: a value model holds values. It costs less than it looks like. A parse failure and a duplicate
   member are already reported with a `JsonPosition`, and the schema-directed decode streams events,
   which carry positions, rather than walking a tree. What is lost is a line number on a
   `JsonValueException`, which names the step and what is actually there instead.
2. **`JsonNumber` holds the lexeme, and equality is over it.** `1`, `1.0` and `1e2` are three distinct
   nodes. That looks wrong and is the honest layering: [TSON-SCHEMA] §5.5 makes equality a property of a
   *value space*, and a value space comes from a type, which this layer has none of. Under a schema the
   three are one `number`, and §5.3's own text says so. `toBigDecimal()` is the one call for a caller who
   means numeric comparison — added beside JEP 540's `new BigDecimal(number.toString())` advice rather
   than instead of it.
3. **`Json.parse(InputStream)`**, because §3.1 makes the document UTF-8 and puts a byte offset in every
   error report, and a decoder handed an already-decoded `String` has lost both.

**What the tree deliberately keeps from JEP 540** is the pair of behaviours a `TsonValue` user will find
surprising, and they are surprising in the right direction: **`get` throws** where `TsonValue.get` returns
a `TsonMissing`, and every throwing accessor has a non-throwing peer. One method name must not carry
opposite semantics on the two sides a consumer moves between, and that is the whole reason to align at all.

**§3.1's duplicate-member rule lands here**, not in the event stream: the stream is grammar and a repeat is
not a grammar error. §3.1 gives the *category* to the position's type, which a schemaless parse has none
of — so this refuses unconditionally, where JEP 540 does and for the reason §10.2 gives (two `$type`
members, one seen by a security filter and the other by the decoder). The schema-directed decode will
report the same fact with the category its position gives it. Names are compared **decoded**, so
`"ab"` and `"\u0061b"` are one name.

**`toString()` is compact RFC 8259 and `Json.toDisplayString` is the indented form.** Both parse back to
the value they came from, which is §9.2's round trip — "the conformance test, not a separate rule set".
`JsonText` is the only place this module writes a string: it escapes what RFC 8259 requires and nothing
more, leaving `/` alone and writing every other character as itself, since a UTF-8 document has no reason
to spell an ordinary character as an escape. A lone surrogate in a *hand-built* value is written as its own
`\u` escape rather than raw, which keeps the output well-formed UTF-8 — the value is still one §3.1
refuses on the way back in, and refusing it there is where the spec puts the rule.

## Packages

Three exported, layered the way the module reads a document, and the split is the one `tson-compiler` and
`tson-tree` already draw between a front door, a value model and an engine:

| Package | Holds |
|---|---|
| `io.ltr8.tson.json` | `Json` (the front door), `JsonTreeReader`, `JsonObjectReader`, `JsonPosition` |
| `io.ltr8.tson.json.tree` | `JsonValue` and its six node types, plus `JsonValueException` |
| `io.ltr8.tson.json.stream` | `JsonEvent`, `JsonEventSource`, `JsonStream` |
| `io.ltr8.tson.json.reader` | internal — `SchemalessTreeReader`, `DataClassObjectReader`, `JsonReadContext` |
| `io.ltr8.tson.json.atom` | internal — one JSON leaf into one host value, and where §5's per-family readers land |
| `io.ltr8.tson.json.lexer` | internal — a consumer names a value, an event or a reader, never a token or a parser |

`tree` is the JSON counterpart of `io.ltr8.tson.tree` and stands in the same relation to its front door:
`Json.parse` returns a `JsonValue` as `Tson`'s tree reader returns a `TsonValue`. `JsonObjectReader` sits
in the front door beside `Json` for the same reason `TsonObjectReader` sits beside `Tson` — a reader is a
front door, not a layer of one. `atom` is unexported and will grow: §5.1 hands a string's content to the
atom's own parser exactly as a TSON quoted token's text would be, so each family needs a reader there and
none of them belongs in a reader that walks structure. `stream` is exported for
the reason `tson-compiler` exports its own — `Json.parse` takes a `JsonEventSource`, so it is a real contract
rather than an internal dispatch type, and JEP 540 excludes streaming as a non-goal, so a caller who needs it
has nowhere else to go.

The rendering lives on `JsonValue.toDisplayString(indent)` rather than only on `Json`, because the string
quoting it needs is `tree`'s and package-private there. `Json.toDisplayString(value, indent)` is JEP 540's
spelling of the same call and delegates.

## The front door and its two readers

`Json` is the configuration a read is judged under — the `ProcessorPolicy`, the binding, and where problems
go — and hands out the two readers that apply it: `treeReader()` producing a `JsonValue`, `objectReader()`
producing a bound Java object. That is the shape `Tson` takes over `TsonTreeReader`/`TsonObjectReader`. It
holds no schema registry because there is nothing yet to register; §5–§8's decode is where one arrives, and
this is where it will live.

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
readFailure`), which is the point: the two encodings answered this differently for as long as the JSON readers
caught nothing, and a `ParseException` escaping a collecting read was the one failure a caller who asked for
every problem could not see coming.

**`JsonDiagnostics` is a peer of `TsonDiagnostics`, not a case inside it.** A `Diagnostic` is the shape of an
answer and is shared (§9.4 adds no category of its own); *classifying* a failure is reading a document, and
reading is where the two are separate stacks — one switch over both would be a switch responsible for
exceptions it cannot name. It has one case where the text encoding has three: §8.1 makes a lexer error and a
parse error separate categories and TSON text keeps them separate in the type, where this stack raises
`ParseException` for both and splits them here; and there is no counterpart to
`TsonUnsupportedDocumentException`, a JSON document declaring no conformance class to be refused for.

`Json` used to reduce events into a tree itself, which put an **engine in a front door's name** and left the
stack with no tree *facade* at all — a tree read could not be given a receiver, a policy or a path where a
bound read could. The reduction is `SchemalessTreeReader`'s now, under `JsonTreeReader`, and the two readers
are peers.

**The tree engine is the one place `Schemaless` is the right word.** `DataClassObjectReader` is driven by the
target class, which is in effect its schema; the tree reader is driven by nothing at all, so the name is
accurate here where it would have been wrong there. Both are named for what drives them and what they
produce.

**§3.1's duplicate-member rule became a diagnostic** in the move: `DUPLICATE_FIELD` with an RFC 6901 pointer,
reported rather than thrown, so a collecting read finds every repeat in one pass and a fail-fast one still
stops at the first. That is more faithful to §3.1 than the `ParseException` it replaced — the grammar accepts
the document, and §3.1 puts a repeat in the categories that follow the position's type rather than in the
parse category. JEP 540 calls it a parse error for want of anywhere else to put it; this has somewhere.

**A collecting tree read hands back the tree**, where a bound read hands back nothing: a `JsonObject` has
somewhere to put a partial answer and a Java record does not. Same asymmetry `tson-compiler` draws between
its own two readers.

## The identifier policy, and the one reader that can apply it

[TSON-DATA] §8.2 has two Unicode surfaces. The **token** policy reaches every JSON token and is
`JsonStream`'s, upstream of everything. The **identifier** policy reaches *names* — and in this encoding
only one reader can tell a name from a key.

**§4.1 is why.** `{"a": 1}` is one syntax for a record and a map, and the position decides which. So
nothing that merely reads a member name can say whether it read a field name (§2.5 makes that an
identifier at every layer) or a map key (data, which is the token policy's business). `tson-compiler` has
no such problem: TSON text spells the two apart (`a: 1` against `k => v`), so its read context checks every
`FieldName` event it delivers. That difference is the same one that made this a separate stack rather than
a front end over `TsonEventSource`.

**`JsonObjectReader` holds a position; `JsonTreeReader` does not.** The target class plays the schema's
part in this encoding, so a `DataClassRecord` position means the members are names and a `DataClassMap`
position means they are keys. `DataClassObjectReader.checkNameHygiene` applies the two per-name rules
there and `bindMap` applies nothing, deliberately — which is also what keeps a JSON-Schema conversion from
meeting a name rule on `additionalProperties`. The tree reader applies nothing at all: a `JsonObject`'s
members could be either, and guessing is what §4.1 forbids.

**Both defaults are on**, as §8.2 requires — the identifier profile MUST apply and a name's scripts SHOULD
be judged at Highly Restrictive over the whole name — and both relax through
`withProcessorPolicy(policy.withIdentifierPolicy(…))`, which §8.2 requires be code rather than ambient.
Every member name a record position carries is judged, declared or not: §8.2's scope is the names the
document wrote, and the undeclared case is the one that matters, a look-alike of a declared name arriving
where the class will not keep it.

**A refusal is a verdict but not an invalidity.** §8.2 says it MUST NOT be reported in any of §8.1's four
categories and §9.4 carries those categories here unchanged, so what keeps it apart is the *code* —
`RESTRICTED_CHARACTER` and `RESTRICTED_SCRIPT`, one per rule, because the two want different fixes.
`Code.verdict()` stays `true`: the processor looked and declined, and the sender holds the fix, which is
the question a consumer routes on.

**The third rule does not reach JSON, and that is the answer rather than a gap.** Names that read alike
is a property of a *set*, which `tson-compiler` asks of a record's field names in its schemaless tree
reader — where the grammar has already said those members are *fields*, so two that read alike are
unambiguously a problem. JSON cannot get there from either reader: a tree has no positions at all, and at
an object reader's `Map` position the members are keys, where two look-alike keys are two legitimately
distinct keys. **The dangerous case and the safe case are spelled identically**, which is §4.1 in one
sentence, so JSON must accept it.

A deployment that will not accept it has a surface that does reach these: the **token** policy, which
governs every JSON token including a map key, and which a stricter deployment raises. That is the honest
division — §8.2's identifier policy judges names, and where JSON cannot know that a member is a name, what
is left is the rule that judges data.

The record positions need nothing extra either: the admissible names are declared, so a member reading
alike to a declared one is undeclared and already reports `UNRECOGNIZED_FIELD`. The TSON bind path draws
the line in the same place, and drawing it elsewhere would make this encoding stricter than that one for a
rule §8.2 states once.

**It costs nothing measurable**, which took one edit rather than a design: both rule implementations are
allocation-free when a name passes, but `Optional.ifPresent` with a capturing lambda is not — it captures
and allocates whether or not the `Optional` holds anything. Two per member name was ~140 bytes per bound
record in `JsonAllocationHarnessTest`; tested rather than `ifPresent`-ed, it is back inside the noise.

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

- **a choice.** §8.2 admits an untagged one by a declared discriminator or by class-stable disjoint
  variants, both facts a *schema* states; a Java union states neither. This is the exact mirror of the
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
engine owns one value at one descriptor and stops**. That is also where the schema-directed decode of §5–§8
arrives: a second engine under the same door rather than a second door.

**The engine is named for both axes every reader in this family is named for** — what drives the read, and
what it produces. A `DataClass` descriptor drives this one and an object comes out. That has to stay in the
name or the family stops scaling: `JsonTreeReader` sits over a tree engine, and §5–§8's
schema-directed decode is a third engine under the same facade, so a name encoding only "what drives it"
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

**A union target is refused, with the reason.** §8.2 admits a tag-free choice by exactly two routes — a
declared discriminator, or a derived disjointness fact over class-stable variants — and forbids extending
them ("no trying variants in order", which is how JSON Schema validates a `oneOf`). A Java union states
neither, so there is nothing to dispatch on and inventing a rule would be inventing the one §8.2 rules
out. The message says so rather than failing obscurely.

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
`2147483648` at an `int` used to be one message — "not exactly representable" — because one `BigDecimal` was
asked both questions. They are now two: the first is not an integer *form* at all (§5.3's contract
rejection, exactly as the token `1.5` is in text, which the TSON side already got right through
`NumberNarrowing`), the second is an integer outside `int32`'s range. `200` at a `byte` reports
`>= -128 and <= 127` — `int8`'s own bound, from `AtomTypeException`'s vocabulary — where it reported "a value
that fits byte", which is the JVM's account of the same fact and not the schema's. And a refinement's
`allow_nan` or `multiple_of` now has somewhere to be honoured when the schema-directed decode lands.

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
It tracks the position and builds the RFC 6901 pointer by *stepping* (`field(name)`/`index(i)` return a
context one link deeper) rather than concatenating, since concatenating per step is quadratic in depth and
thrown away by every read that reports nothing. It is simpler than its TSON peer in three ways, each
because this read has less to say: no schema end (the class is the schema, and a class has no document to
point into), no lookahead or rewind (the engine pulls a value's opening event and passes it down), and no
name policy yet.

Codes come from the same closed vocabulary the TSON readers use — §9.4 adds no category of its own — so
`TYPE_MISMATCH`, `FIELD_REQUIRED`, `UNRECOGNIZED_FIELD`, `DUPLICATE_FIELD`, `DUPLICATE_MAP_KEY`,
`WRONG_ARITY`, `ATOM_FORM_INVALID`/`ATOM_CONSTRAINT_VIOLATION`, `UNKNOWN_TYPE_REF` for a union with no selector, and
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

## The token policy ([TSON-DATA] §8.2, reached by §9.4)

`JsonStream` applies it to every token it hands out — strings, numbers and member names — because at that
layer nothing yet knows which of those a member name will turn out to be. §9.4 names "map keys and string
values"; a member name read as a *field* name meets the identifier policy as well, where the position that
decides it is known, so **a token policy stricter than the identifier policy subsumes it**, exactly as on the
TSON side.

`JsonObjectReader.withTokenPolicy` is the surface, defaulting to `unrestricted()`: a value is data and may
legitimately be anything, so §8.2 scans none of it until a deployment says otherwise — and §8.2 requires that
saying so be code rather than ambient, which is what the method is.

**Built into the stream rather than wrapped around it**, and `TsonDataStream` now does the same — the
decorator that used to do this on the TSON side is gone. The property the check needs is that each token is
produced exactly once, which a stream gives and a read context does not (it rewinds). A wrapper bought that
property and cost a second place to forget to apply it; with two encodings needing one rule, the mechanism
should be one too.

A number's digits are ASCII so a number never trips the check, and it is checked anyway rather than exempted
— a rule with an exception nobody can state is a rule someone gets wrong when the exception stops holding.
