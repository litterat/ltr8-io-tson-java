# The JSON encoding: schema-directed reading

Design notes for `tson-json`'s schema-directed reader stack — how a JSON document is bound to a schema, the annotation
object, how the unexported `reader` package is named, and the map, record and choice readers — plus the CLI's JSON
surface. Current form only; history lives in git.

**Invariants**

- A JSON document binds out of band: `Json.withSchemas` takes a `TsonSchemaLoader`, and failing to reach the schema is a
  diagnostic and never a verdict.
- A `$type` is matched after reference flattening at every position that compares a written name against a set, through
  the one index `ReferenceChain.namesMeaning`.
- Selectors lead their object ([TSON-JSON] §3.3, §6.1.5): every decision is a peek at the leading members
  (`ReservedMembers.lead`), bounded by the schema, and no reader scans an object.
- The concrete record reader looks ahead at nothing: its member loop judges every reserved member as it arrives, and
  `$schema` is refused everywhere this can reach.
- `CompiledReaders` is rebound exactly once, from the in-progress compilation to the finished schema.
- The map form is chosen by the factory from `K` and the record reader from `record.extension`, once at compile, never by
  inspecting a value.
- A choice value may omit its tag only when the choice is disjoint **and** class-stable; no member-shape matching, no
  trying variants in order. A missing required tag is `TYPE_MISMATCH`.
- A dispatcher only selects: every reader it can select is wired when the schema compiles, it builds nothing, and one
  set serves every mode. The reader it selects validates in full.
- Readers are named mode, then family, then form, and carry no `Json` prefix inside the unexported `reader` package; a
  dispatcher has no mode and carries none.

Related: `design/json-encoding.md` (why the stack is separate, the parity guard), `design/json-lexer-stream-tree.md`,
`design/json-facades-binding-writing.md`, `design/json-unicode-policies.md`, `design/linking-and-compilation.md`,
`design/cli-config-hashing.md`.

## What the stack compiles

The schema-directed reader stack — `JsonTypeReader`/`JsonCompiledSchema`/`JsonSchemaCompiler` — carries [TSON-JSON] §5's
atoms, the whole of §6's containers, §7's absence, §3.2's reserved namespace and §3.3's annotation object (so §6.1.5's
`$type` selects a subtype — the JSON spelling of `!employee` at a `person` field), and §8.2's discrimination predicate over
§8.3's class stability, all compiled in **tree mode**; §8.5's scoped positions reach a `NOT_IMPLEMENTED` reader.
**Bind mode** (`ValueReaderFactoryRegistry.bind`) compiles records, atoms and the dispatchers; its containers are
not built yet and compile to gaps.

## Naming inside `reader`: mode first, and no prefix

The schema-directed readers that differ by mode are named **mode, then family, then form** —
`TreeMapObjectReader`, `TreeMapPairsReader`, `TreeRecordBuilder` — so that bind mode lands as
`BindRecordBuilder` beside its peer and a reader's mode is the first thing about it. **A mode-free loop carries no
mode**: the record loop (`RecordReader`) fills slots and hands them to the mode's builder, so it is named family,
then form. That is the axis someone scans when adding a mode, and it is
the axis a file listing then sorts by. **A dispatcher has no mode to lead with** — it selects and builds nothing
— so it leads with what it is and then how it selects, and the family sorts together: `DispatchTagReader`,
`DispatchMemberReader`, `DispatchChoiceReader`, and `DispatchFactories` over them. `tson-compiler`'s peers keep
their record-first names (`RecordTagDispatchReader`) until that stack adopts the same design (`BACKLOG.md`).

**The `Json` prefix is dropped in `reader` and kept in the root**, which is `CLAUDE.md`'s rule applied rather
than an exception to it: a prefix earns its keep disambiguating a name a *consumer* writes, and `reader` is
unexported. The root package keeps it for exactly that reason — `JsonReadContext` beside a domain
`ReadContext`, `JsonTypeReader` beside `TsonTypeReader`.

The consequence worth having is that **thirteen schema-directed classes share a bare name with their
`tson-compiler` counterpart**: `ErrorReader`, `OpenTemplateReader`, `EventSkip`, `CompiledReaders`,
`DiscriminationClass`, `ValueReaderFactory`, `ValueReaderContext`, `ValueReaderFactoryResolver`,
`ValueReaderFactoryRegistry`, `VoidReader`, `DeferredTypeReader`, `ReferenceChain`, `ValueIdentity` — and the
schemaless engines `SchemalessTreeReader` and `DataClassObjectReader` do too. The two stacks read as peers, and
which TSON class a JSON class answers to is visible at a glance — which is what a parallel implementation wants and what a
prefix would hide. Nothing imports both, neither package being exported, so the shared names cost nothing.

## Binding a document, which is the one thing JSON cannot do for itself

A TSON document names its own schema in its header and its own root type with a type-ref. A JSON document
can do neither — `!!schema` is TSON text syntax — so [TSON-JSON] §3.4 gives it two routes and this stack
implements the first: **out of band**, the application supplies both, and the document is then a bare value
read directly at that type. The spec calls it "the expected production route". The in-band route — a root
annotation object carrying `$schema` and `$type` — is not built (`BACKLOG.md`): the annotation object it rides on
is, below, but `$schema` is refused at every position this stack reads.

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

### The annotation object, and the peek at its leading members

TSON text attaches a type annotation beside a value; JSON has no beside, so §3.3's **annotation object** is
the carrier — wrapper (`{"$type": "age", "$value": 42}`) or inline (`{"$type": "employee", "name": "Ada"}`,
when the selected type reads the value as a record) — over §3.2's closed reserved set of `$schema`, `$type`
and `$value`. §6.1.5 is what it buys at a record position: a tag naming a subtype, validated in full, which
is the JSON spelling of `!employee` at a `person` field.

**A `$type` is matched after reference flattening, on both sides** ([TSON-SCHEMA] §7.2's own words), and that
reaches every position here that compares a written name against a set: a plain record's own name and its
subtypes, an abstract base's subtypes, a sealed base's own name and the deeper names each dispatched member
admits, and a choice's variants and their subtypes. One index answers it — `ReferenceChain.namesMeaning`,
built once per compile and held by `ValueReaderContext`, the inverse of the chain walk beside it — because
the alternative is each reader deciding for itself and the set of positions that flatten becoming whichever
ones someone remembered. §9.4 makes the agreement with TSON text obligatory rather than tidy: the same alias
names the same type in both, and `Subsumption.admitting` is the peer function on that side. What it is
load-bearing for is a template instantiation, whose entry name is minted and non-normative (§8.2) — an alias
is the only name a document has for one.

**The class is `ReservedMembers`, not the spec's own noun, and the divergence is deliberate.** In this
codebase `Annotation` means an `@name` annotation and nothing else — two dozen types say so, from the
`tson-annotation` module through `Annotations`, `TsonAnnotation` and the `AnnotationStart`/`AnnotationEnd`
events — and those have **no JSON carrier at all**: §4.3 declines one for v1 and makes encoding a value that
carries them an encode error. A type named for §3.3 would be the single place the word meant something else,
so it is named for the §3.2 namespace it reads and cites §3.3 throughout. The spec's noun is right for the
spec, where `@name` annotations are §3.1's and no reader is looking at a Java identifier to tell them apart.

**Recognising one is a bounded peek, because the selectors lead.** A reader cannot read an object's members
until it knows which reader owns them, and §3.3 puts what decides that first: `$schema` where present, then
`$type`, and §6.1.5 puts a sealed position's discriminators next, in any order among themselves.
`ReservedMembers.lead` reads those members and stops at the first that is none of them, and
`JsonReadContext.lookingAhead` replays what it consumed. It holds a scalar value per selector and nothing
else — a selector whose value is not a scalar stops the peek — so what a reader holds before dispatch is a
count the schema fixes, never one the document chooses (§10.1 gives the attack this closes). A valid wrapper's
members are the reserved ones only, so its `$value` directly follows them and the peek sees it there; a
`$value` anywhere else is an extra member of an invalid object, refused by whoever reads it.

**The concrete record reader does no lookahead at all.** It is reached only for its own type, so its member
loop judges reserved members as they arrive: a `$type` in first place must restate the record; `$value`
straight after it makes the object a wrapper, read at the reader the dispatcher chose for it (`ExactReader`,
`Route`), since the value inside may carry a tag of its own; a misplaced `$type` or `$value`, any `$schema`,
and a name outside the closed set refuse the object, and the rest of it is skipped. Members read before a
refusal have already reported, so an invalid document's diagnostics follow its member order — accepted
deliberately: holding them back would cost every valid document a buffer to tidy the answer for invalid ones.
The allocation harness measures what the peek saved (`aSchemaDirectedRecordReadsWithoutLookingAhead`).

- **`$schema` is refused everywhere this can reach.** §8.5 admits it only where the effective type is a
  `scoped` instance holding EXTERN, and §3.3 makes it a resolver error anywhere else. That is the correct
  verdict at every position built so far, and the scoped reader is what will admit it.

`CompiledReaders` is how a factory reaches another entry's reader, and carries `tson-compiler`'s own hazard: it is
**rebound exactly once**, from the in-progress compilation to the finished schema, because handing readers the
compilation's resolve would leak its mutable state past the compile. No reader consults it at read time: every
edge, a dispatcher's included, is an object reference wired at compile, and a cycle closes through
`DeferredTypeReader`.

### The map form is chosen by the factory, not re-asked per value

§6.5 selects between the object and pairs forms **by `K`, never by inspecting the value**, and that selection
is therefore made once: `TreeMapReader` is a sealed base over `TreeMapObjectReader` and
`TreeMapPairsReader`, and the factory returns whichever the key type names. Neither subclass carries the
other's state or a branch it never takes, and §4.1's "nothing is read speculatively" is structural rather than
a thing the read remembers to honour. What stays on the base is what both forms share and nothing else: §6.5's
entry-value rule, the size facets, and the test that picks between them — which §8.3 also asks, to judge
whether a map is class-stable.

### A record position gets the reader its extension fact earns

§6.1.5 gives an untagged object three readings, decided by the position's own `record.extension`
([TSON-SCHEMA] §5.2) — so `DispatchFactories` picks a reader **once, when the schema compiles**, and no value
pays for a branch it will never take. It decorates a mode's concrete record factory, because the concrete
reading is the only one that differs by mode: every other reading places the value and hands it on.

- **A record with no subtypes** gets the concrete reader (below) and nothing else.
  That is every FINAL record, by construction, and every OPEN one without subtypes, which the loaded schema
  cannot grow. The concrete reader is reached only for its own type, so a `$type` it sees can only restate it;
  it never redirects to another reader partway through a value.
- **OPEN with subtypes** gets `DispatchTagReader` in front of the concrete reader, which takes the
  untagged value and an inline restatement.
- **ABSTRACT** gets `DispatchTagReader` with no concrete reader behind it: `$type` is REQUIRED, the failure
  lands before the object's shape is consulted, and the base itself is not admissible — a tag naming it is an
  error where a concrete position would take one as a redundant restatement.
- **SEALED** gets `DispatchMemberReader`, which reads the discriminator members and looks the value up.
- **A family-base template** gets the ABSTRACT or SEALED dispatcher over the template itself, through the
  registry's `template` constructor like any other entry. A template carrying `extension` is a type by the only
  test that matters — a value can stand at it, being a value of one of its instantiations (`SPEC-FEEDBACK.md`
  #13) — so `{ b: box }` admits
  `{"$type": "int_box", "v": 1}` and refuses an untagged object, exactly as TSON text does. Every member of
  such a family is minted, so the alias is the only name a document has for one, which is what makes the
  flattening above load-bearing here rather than merely consistent.
  - **Both readings, on the terms a closed base gets them.** ABSTRACT dispatches on `$type`; a SEALED template
    base hands its value to `DispatchMemberReader`. The discriminator names are stated structurally on the entry
    (`template.discriminators`, read through `tson-schema`'s `FamilySelectors`) rather than only in the held body's
    *text*, which `tson-compiler`'s `HeldBody` parses: this module depends on the schema pipeline's output and
    never on its engine, and a second walk of that text would put two opinions about which fields select a family
    on either side of a module wall, which §9.4 makes a specification failure rather than untidiness. A template
    carrying no `extension` reaches `OpenTemplateReader`.

**Every reader a dispatcher can select is wired at compile** (`Route`). A base's `subtypes` is its whole family,
not only its children, so a tag naming a type any number of levels down reaches that type's reader in one step,
and an alias reaches the same reader. A route holds two readers because §3.3's forms want two: the inline form
is the selected type's own value and goes to the reader for exactly that type — an OPEN record's concrete
reader, not the dispatcher in front of it — while the wrapper's `$value` is read at the type's entry reader,
which dispatches again if the value names a subtype of its own. A choice routes through a variant placed by
`$type` the same way, and through a sealed variant only by handing it the value, whose selectors a choice
position cannot bypass.

**The pin mapping is derived once and never at read time.** Each member's pins are decoded at construction, at
the fields' declared types *in the base* — the one set known before a member is selected — and keyed by what
they compare as (`ValueIdentity`). So a read is one map lookup, and both sides of the comparison went through
the same parser: a schema pinning `= 0xFF` selects on a document writing `255`, which §4.3 makes the same
integer. A table keyed on tokens would read that as unmatched.

**The leading members, and no more.** `ReservedMembers.lead` captures the reserved members *and* the
discriminators from the front of the object, as many members as the family declares discriminators. One that
follows another member is missing to the dispatcher, and the refusal says where it has to be — JSON's own
wording of the shared rule, which `CrossEncodingParityTest` pins as the one difference from TSON text's.

**The selected member re-reads the whole object**, which is what makes the dispatch read and the validation
read agree by construction: the pin is re-verified as an ordinary FIXED check rather than trusted from the
peek. The discriminators are still required beside a tag, and a tagged value goes where its tag names — a
deeper `$type` is §6.1.5's "deeper than one level" — so a tag disagreeing with the discriminator meets a pinned
field the document contradicts, refused by the selected reader. TSON text still compares the tag against the
dispatched member in the dispatcher; the parity test pins that divergence until `BACKLOG.md`'s port closes it.

**What is specialised beyond the dispatch** is what the compiler already knows and the reader was re-deriving:
a record with no field group skips the group pass entirely (§5.11's groups are the exception, and the pass
indexes every member of every group).

### A concrete record: one loop for every mode, and a mode's factory and builder

§6.1's rules do not depend on what a read builds, so they are stated once, in `RecordReader`, and read by every
mode. What differs by mode is decided in two places, neither of them on the per-field path:

- **The factory**, once, when the schema compiles. It builds a `RecordPlan` — what the schema fixes: the NFC field
  names and the name index, each field's state and schema reader, the stated values, the group slots, the
  diagnostics — then decides the mode's own two things: the reader each field is read at, and the form a stated
  value takes. Tree mode (`TreeRecordBuilder.FACTORY`) keeps the schema readers and injects a stated value as the
  JSON node that spells it. Bind mode (`BindRecordBuilder.factory`) resolves the bound class, binds each atom field's
  reader to what its component holds (`AtomReader.boundTo`, through the component's bridge where it has one),
  converts each stated value to the component's class, and checks the class against the schema.
- **The builder**, once per record: the loop fills one slot per field and hands the slots over. `TreeRecordBuilder`
  builds a `JsonObject` in declaration order; `BindRecordBuilder` calls the class's constructor.

**A slot says what the document did.** Null is unstated and non-null is stated — which is what the duplicate
check, the group count and the absent-field pass ask, so there is no second array — and three `RecordReader`
markers carry what a value cannot: stated-as-absent, null kept at `OPTIONAL_FIXED = _`, and a child's refusal.
Each builder decides what they become. The rules are methods on the plan and in the loop, not a superclass, so the
loop is the whole of what a record read does.

**Bind mode is all-or-nothing, and says so in one place.** A tree keeps what it built — a record missing a field is
a coherent value beside the diagnostics — but a bound object is application data whose promise is that the
document was good. The loop tells the builder whether anything was reported while the record was read, and
`BindRecordBuilder` constructs nothing where it was: the same rule `tson-compiler`'s bind mode keeps.

**The class is checked against the schema when the reader is built.** A non-FIXED field with no component, a
component no field fills (unless `@Unbound`), an atom field whose component binds structurally, and an atom field
whose family cannot produce the component's class each fail the compile with one `BindMismatchException` naming
them all — a wiring mistake is cheapest found before any document. A schema type with no bound class at all is
deferred (`MissingBindingException`), since a schema declares types a given consumer never binds; `ErrorReader`
rethrows it when that type is first read. JSON carries no annotations (§4.3), so an annotations carrier component
is filled with none.

**An OPEN record with subtypes may bind to a union** — a sealed interface whose implementations are the subtypes'
classes, the natural Java shape for a family. The record has no class of its own to build, so the bind factory
returns, in place of a concrete reader, one that refuses an untagged value (or one tagged with the record itself) as
having no data of its own; the dispatcher in front of it sends every tag naming a subtype to that subtype's class.
Each subtype's class is checked against the union when the reader is built (`DataClassUnion.isMemberType`, which
also admits an implementation of an open member), so a tag the dispatcher follows always lands on a class the
position can hold. `tson-compiler` checks membership on every read instead; with the subtype's class known at
compile, the check can be made once.

**One loop, not one per shape.** A plain loop for records with no default, pin or group was built and measured, and
cost the same per record; the per-field branch it saved is a predictable switch on the field state. A loop per
shape would multiply by the modes, so a split waits for a timing benchmark that shows it pays.

### Discrimination: one condition, and the table is built at schema load

§8.2's predicate is the rule [TSON-SCHEMA] §5.4 requires each encoding to state over the resolver-derived
`disjoint` fact, and it is closed: a value may omit its tag by exactly one condition and "MUST NOT be extended by
implementation cleverness — no member-shape matching among record variants, no value-set separation, no trying
variants in order." `DispatchChoiceReader` implements the whole of it — disjoint plus class-stable, selecting on
the arriving value's kind. §8.2 has one condition and no second route: **member dispatch is not a choice
mechanism**, a choice position having no expected record type whose selector fields a decoder could know
before reading. It belongs to a sealed record family (§6.1.5), where the position does, and
`DispatchMemberReader` above is where it is built — so a choice of records
requires the tag, which is the correct verdict rather than a quiet approximation of a route.

**The verdict is computed once per choice, at compile time**, which is what §8.3 asks for in so many words —
"the wire decision is then a table hit, not a per-value derivation". The table is empty exactly when the tag is
required, so a read has one question to ask and the answer is a map lookup.

**Both halves of the condition are needed and neither implies the other.** `disjoint` guarantees at most one variant
per class; class stability guarantees the arriving kind actually lands in its variant's class. Without the
second, a `float64` variant still admitting `.nan` receives a JSON *string* (§5.4) and would dispatch as though
a string variant had been chosen. The unstable set is closed to two members — that leak, and a map forced into
pairs form — so the test is two cases rather than a survey, and narrowing `allow_nan`/`allow_infinity` restores
the untagged route, which is the checkable reason §8.3 gives an API author to narrow.

**`DiscriminationClass` duplicates the TSON reader's derivation**, which lives in an unexported package.
That is the parallel stack's cost showing up where it matters most, because the fact is derived from the schema
alone — a choice dispatching one way in text and another in JSON would be pure drift. The parity test carries
the cases.

**A missing tag is `TYPE_MISMATCH`**, the code the TSON reader gives the same document. The closed `Code`
enum has no member for "a required tag is missing" and needs none: a tag that is required and absent
establishes no type, which is what the code says, where `UNKNOWN_TYPE_REF` would claim a name denoted nothing
and there is no name at all. That is also what the family readers give `tagRequired`, so one rule
has one code across both encodings and both readings of a record position.

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

**An unknown `--type` is answered with the schema's own declarations first.** An `!!import` merges the imported
entries *first*, so a schema declaring one type over core.tn listed in namespace order would show
"(void | boolean | integer | ... and 44 more)" and none of the author's own. `JsonCompiledSchema.unknownTypeMessage`
leads with the entries this schema declares and the author wrote, filtering by the origin index and by
having a source position (the same test that tells a minted entry from an authored one). Imported and minted
names remain usable root types; the count that follows covers them.
