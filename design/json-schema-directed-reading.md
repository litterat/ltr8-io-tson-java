# The JSON encoding: schema-directed reading

Design notes for `tson-json`'s schema-directed reader stack — how a JSON document is bound to a schema, the annotation
object, how the unexported `reader` package is named, and the record, container, choice and scoped readers. The CLI's
JSON inputs are in `design/cli-config-hashing.md`.

**Invariants**

- A JSON document binds out of band: `Json.withSchemas` takes a `TsonSchemaLoader`, and failing to reach the schema is a
  diagnostic and never a verdict.
- A `$type` is matched after reference flattening at every position that compares a written name against a set, through
  the one index `ReferenceChain.namesMeaning`.
- Selectors lead their object ([TSON-JSON] §3.3, §6.1.5, §6.1.6): every decision is a peek at the leading members
  (`TagMembers.lead`), bounded by the schema, and no reader scans an object.
- The concrete record reader looks ahead at nothing: its member loop judges every annotation member as it arrives, and
  `$schema` is refused everywhere except a scoped position.
- A scoped position opens the scope by consuming `$schema` (`JsonReadContext.consumeLeadingMember`), so no reader
  below it has a second rule for the member; a foreign schema is looked up as the value arrives (`ForeignSchemas`).
- `CompiledReaders` is rebound exactly once, from the in-progress compilation to the finished schema.
- The map form is chosen by the factory from `K` and the record reader from `record.extension`, once at compile, never by
  inspecting a value.
- A choice value may omit its tag only when the choice is disjoint **and** class-stable; no member-shape matching, no
  trying variants in order. A missing required tag is `TYPE_MISMATCH`.
- A kind (`kind_type`) is an atom, read by `AtomReader` from a string naming a member, and never a discrimination case
  ([TSON-JSON] §5.6). Under a meta layer (`TsonLinkedSchema.governsSchemas`) it reads the name's grammar alone: the
  document is a schema, and its names are judged members where that schema links.
- A dispatcher only selects: every reader it can select is wired when the schema compiles, it builds nothing, and one
  set serves every mode. The reader it selects validates in full.
- Every mode is all-or-nothing: no builder builds where anything was reported while its value was read.
- Readers are named mode, then family, then form, and carry no `Json` prefix inside the unexported `reader` package; a
  dispatcher has no mode and carries none.

Related: `design/json-encoding.md` (why the stack is separate, the parity guard), `design/json-lexer-stream-tree.md`,
`design/json-facades-binding-writing.md`, `design/json-unicode-policies.md`, `design/linking-and-compilation.md`,
`design/scope-push.md`, `design/cli-config-hashing.md`. Section numbers below are [TSON-JSON]'s unless prefixed.

## What the stack compiles

`JsonSchemaCompiler` compiles a `TsonLinkedSchema` to `JsonTypeReader`s for §5–§8 entire. `ValueReaderFactoryRegistry`'s
`tree()` and `bind(binding)` register the same constructor set and share the dispatchers; they differ only in the
record and container builders and in the atom factories' wrapping.

## Naming inside `reader`: mode first, and no prefix

Classes that differ by mode are named **mode, then family, then form** (`TreeRecordBuilder`, `BindRecordBuilder`,
`TreeAtomKeyedReader`), so a listing sorts by the axis someone scans when adding a mode. A mode-free loop carries no
mode (`RecordReader`, `ArrayReader`, `MapPairsReader`). A dispatcher has no mode, so it leads with what it is, then how
it selects (`DispatchTagReader`, `DispatchMemberReader`, `DispatchChoiceReader`, `DispatchScopedReader`, and
`DispatchFactories` over the record ones). `tson-compiler`'s peers keep record-first names (`RecordTagDispatchReader`)
until that stack adopts the same design (`BACKLOG.md`).

**The `Json` prefix is dropped in `reader` and kept in the root** — `CLAUDE.md`'s rule applied, `reader` being
unexported. So most of the package shares a bare name with its `tson-compiler` counterpart (`CompiledReaders`,
`ErrorReader`, `ValueReaderContext`, `ReferenceChain`, `DiscriminationClass`, `BindTargets`, `ForeignSchemas`, …),
which shows at a glance which TSON class a JSON class answers to. `tson-json` does not depend on `tson-compiler`
(`module-info`), so nothing can name both.

## Binding a document

A JSON document can name neither its schema nor its root type. §3.4 gives two routes and this stack implements the
first, **out of band** — the application supplies both, "the expected production route". The in-band route (a root
annotation object carrying `$schema` and `$type`) is not built (`BACKLOG.md`).

`Json.withSchemas(loader)` takes a `TsonSchemaLoader` (`tson-schema`'s interface): obtaining a linked schema is the TSON
engine's job (`design/json-encoding.md`), so an application reading both encodings resolves once through `Tson` and
hands `tson.schemaRegistry()` over. `JsonCompiledSchemaRegistry` caches compiled readers per canonical identity, and
`Json.validate(source, schemaUri, rootType)` is the collecting door the CLI runs through. Failing to reach the schema is
`SCHEMA_NOT_PERMITTED` for an identity the loader holds none of and `UNKNOWN_TYPE` for a root type the schema does not
declare; `Code.verdict()` separates these from anything the document did.

**A pinned schema reference is not verified.** `JsonCompiledSchemaRegistry` keys on canonical identity and its
loader takes nothing else, so a `?sha256=` pin — out of band or on a pushed `$schema` — is dropped before anything
could check it (`BACKLOG.md`).

## The annotation object, and the peek at its leading members

§3.3's **annotation object** carries what TSON text attaches beside a value — wrapper (`{"$type": "age", "$value":
42}`) or inline (`{"$type": "employee", "name": "Ada"}`, when the selected type reads the value as a record) — over
§3.2's three members `$schema`, `$type`, `$value`. They are the only names the encoding gives a meaning: any other
member name, `$`-initial or not, is a field name (`$ref` under a record whose `name_type` admits it) or a key. The
class is `TagMembers`, not the spec's noun, because `Annotation` in this codebase means an `@name` annotation, which
has no JSON carrier (§4.3); its Javadoc has the reasoning.

**A `$type` is matched after reference flattening, on both sides** ([TSON-SCHEMA] §7.2), at every position here that
compares a written name against a set: a plain record's own name and its subtypes, an abstract base's subtypes, a sealed
base's own name and the deeper names each member admits, and a choice's variants and their subtypes. One index answers
it — `ReferenceChain.namesMeaning`, built once per compile and held by `ValueReaderContext` (`admitting`) — so the set
of positions that flatten cannot become whichever ones someone remembered. TSON text's peer is `Subsumption.admitting`.
It is load-bearing for a template instantiation, whose entry name is minted and non-normative ([TSON-SCHEMA] §8.2): an
alias is the only name a document has for one.

**Recognising one is a bounded peek, because the selectors lead** (§4.1). `$schema` where present, then `$type`, then
a sealed position's discriminators in any order among themselves (§6.1.5). `TagMembers.lead` reads those members and
stops at the first that is none of them, and `JsonReadContext.lookingAhead` replays what it consumed. It holds a
scalar value per selector and nothing else — a selector whose value is not a scalar stops the peek — so what a reader
holds before dispatch is a count the schema fixes, never one the document chooses (§10.1). A valid wrapper's members
are the three only, so its `$value` directly follows them and the peek sees it there; a `$value` anywhere else is an
extra member of an invalid object, refused by whoever reads it.

**The concrete record reader does no lookahead at all.** It is reached only for its own type, so `RecordReader`'s
member loop judges annotation members as they arrive: a `$type` in first place must restate the record
(`RecordPlan.admitsTag`); `$value` straight after it makes the object a wrapper, read at the reader the enclosing
position chose (`ExactReader`, `Route`), since the value inside may carry a tag of its own; a misplaced `$type` or
`$value`, or any `$schema`, refuses the object and the rest of it is skipped (`RecordPlan.refuseTagMember`).
A member name is matched in NFC and then in the record's name type's form (`TsonLinkedSchema.nameForm`, held by
`RecordPlan`; §6.1.1), so under a case-folding name type `NAME` is the field `name`; `TagMembers.lead` puts a sealed
base's selectors in the same form. A member matching nothing is judged by that name type (`RecordPlan.unmatched`), as
TSON's record reader judges it: one that is no value of the type is `UNRECOGNIZED_FIELD` with the type's reason and
meets no hygiene rule, being no name; one that is meets [TSON-DATA] §8.2 under the type's profile, and only then is
unknown (§9.4).

Members read before a refusal have already reported, so an invalid document's diagnostics follow its member order —
accepted deliberately: holding them back would cost every valid document a buffer to tidy the answer for invalid
ones. The allocation harness measures the absent lookahead (`aSchemaDirectedRecordReadsWithoutLookingAhead`).

**`$schema` is refused everywhere a scoped reader does not stand.** §8.5 admits it only where the effective type is
a `scoped` instance holding EXTERN, and [TSON-SCHEMA] §7.8 makes it a resolver error elsewhere (`SCOPE_NOT_ADMITTED`,
as TSON text's `!!schema` there). The scoped reader consumes it, so a record reader never meets one it should admit.

**`CompiledReaders` is how a factory reaches another entry's reader**, and it is **rebound exactly once**, from the
in-progress compilation to the finished schema, because handing readers the compilation's resolve would leak its
mutable state past the compile. No reader consults it at read time: every edge, a dispatcher's included, is an object
reference wired at compile, and a cycle closes through `DeferredTypeReader`.

## The map form is chosen by the factory

§6.4 selects the object or pairs form **by `K`, never by inspecting the value**, so the selection is made once:
`MapPlan` records which form the key type names and the factory returns that form's loop, `MapObjectReader` or
`MapPairsReader`, neither carrying the other's branch. §4.1's "nothing is ever read speculatively" is then structural.
`MapEntries` holds what both share — the entry-value rule, the size facets, the wrong-shape refusal — and the form
test is `MapPlan.isObjectForm`, which §8.3's class-stability judgement also asks. Both loops resolve a repeated key
the same way in every mode: reported, and the value filed under the first spelling.

## A record position gets the reader its extension fact earns

§6.1.5 gives an untagged object three readings, decided by the position's own `record.extension` ([TSON-SCHEMA]
§5.2), so `DispatchFactories.over` picks a reader **once, when the schema compiles**. It decorates a mode's concrete
record factory, because the concrete reading is the only one that differs by mode.

- **A record with no subtypes** — every FINAL record, and every OPEN one without subtypes, which the loaded schema
  cannot grow — gets the concrete reader and nothing else. A `$type` it sees can only restate it.
- **OPEN with subtypes** gets `DispatchTagReader` in front of the concrete reader, which takes the untagged value and
  an inline restatement.
- **ABSTRACT** gets `DispatchTagReader` with no concrete reader behind it: `$type` is REQUIRED, the failure lands before
  the object's shape is consulted, and a tag naming the base is refused, the base having no direct instances.
- **ABSTRACT naming discriminators** (SEALED) gets `DispatchMemberReader`, which reads those members and looks the
  value up. In the wrapper form the discriminators are inside `$value`, so the tag places the value and is required.
- **A family-base template** ([TSON-SCHEMA] §5.10) gets whichever dispatcher its body asks for, through
  `DispatchFactories.TEMPLATE`, which `JsonSchemaCompiler` picks for every open entry (`OpenEntry.isOpen`): `{ b: box }`
  admits `{"$type": "int_box", "v": 1}` and refuses an untagged object, as TSON text does. The selectors are stated on
  the entry (`tson-schema`'s `FamilySelectors`), so neither side of the module wall keeps its own opinion of them. A
  record template IS-A the base is no member, so `DispatchMemberReader` skips an open subtype. Every other template
  reaches `OpenTemplateReader`.

**Every reader a dispatcher can select is wired at compile** (`Route`). A base's `subtypes` is its whole family, so a
tag reaches a type any levels down, or an alias of one, in one step. A route holds two readers because §3.3's forms want
two: the inline form goes to the reader for exactly that type (an OPEN record's concrete reader, not its dispatcher),
the wrapper's `$value` to the type's entry reader, which dispatches again on a tag of its own. A choice routes through a
variant placed by `$type` the same way, and through a sealed variant only by handing it the value, whose selectors a
choice position cannot bypass.

**A sealed position** decodes its pins once, at the fields' declared types in the base, keyed by `ValueIdentity` (so
`255` selects the member pinned `= 0xFF`). A discriminator that does not lead is missing, with JSON's own wording,
which `CrossEncodingParityTest` pins as the one difference from TSON text's. The selected member re-reads the whole
object and re-verifies the pin as an ordinary FIXED check; a tagged value goes where its tag names (§6.1.5's "deeper
than one level"), so a tag disagreeing with the discriminator meets a pinned field the document contradicts. TSON
text compares tag and member in the dispatcher; the parity test pins that divergence (`BACKLOG.md`).

**A field group is compiled once to slot arrays** (`RecordPlan.GroupPlan`), and a record with none skips the pass.
Each chosen option reports the members it lacks, in option order, and the count is judged last (§6.1.4);
`RecordAbstractReader` does the same on the TSON side, and the parity test pins the two to one order.

## A concrete record: one loop for every mode, and a mode's factory and builder

§6.1's rules do not depend on what a read builds, so they are stated once, in `RecordReader`. What differs by mode is
decided in two places, neither on the per-field path:

- **The factory**, once, at compile. It builds a `RecordPlan` (what the schema fixes), then decides the reader each
  field is read at and the form a stated value takes. Tree mode (`TreeRecordBuilder.FACTORY`) keeps the schema readers
  and injects a stated value as the JSON node that spells it; bind mode (`BindRecordBuilder.factory`) binds each atom
  field's reader to its component (`AtomReader.boundTo`), converts each stated value to the component's class, and
  checks the class against the schema.
- **The builder**, once per record: `TreeRecordBuilder` builds a `JsonObject` in declaration order;
  `BindRecordBuilder` calls the class's constructor.

**A slot says what the document did.** Null is unstated and non-null is stated — what the duplicate check, the group
count and the absent-field pass ask, so there is no second array — and two `Slots` markers carry what a value cannot:
stated-as-absent, and a child's refusal. A stated absence is `JsonNull` in a tree and `null` in a bound object, so
**the tree keeps a member written null apart from one never written** (§7.2) and bind mode collapses the two. One loop
serves every record shape: the per-field branch on field state is a predictable switch, and a loop per shape would
multiply by the modes; a split waits for a timing benchmark showing it pays.

**All-or-nothing.** The loop tells the builder whether anything was reported while the record was read, and neither
builder builds where it was: a bound object promises the document was good, and a tree's placeholder for a refused
value would be the same node as a real `null`, so a partial tree could not say which parts to trust. `tson-compiler`
keeps the same rule; each facade applies it once more to the whole document (`CountingReceiver`).

**The class is checked against the schema when the reader is built.** A non-FIXED field with no component, a
component no field fills (unless `@Unbound`), an atom field whose component binds structurally, and an atom field
whose family cannot produce the component's class each fail the compile with one `BindMismatchException` naming them
all — a wiring mistake is cheapest found before any document. A schema type with no bound class is deferred
(`MissingBindingException`, rethrown by an `ErrorReader` when the type is first read), since a schema declares types a
given consumer never binds. An annotations carrier component is filled with none (§4.3).

**An OPEN record with subtypes may bind to a union** (a sealed interface over the subtypes' classes). Having no class
of its own, its concrete reading refuses an untagged value, or one tagged with the record itself, as having no data;
the dispatcher sends each subtype tag to that subtype's class, checked against the union at compile
(`DataClassUnion.isMemberType`), so a followed tag always lands on a class the position can hold.

**A record with no subtypes binds to a union only as a labelled choice** (`BindGroupUnionBuilder`): one REQUIRED
group of one-field options over every field, each member of the sealed interface a one-component record whose wire
name is the field it labels — the shape of the kernel's `type_argument`, `{ ( name: type_ref | value: value ) }`.
The ordinary loop reads it, the group rule admits one member, and the builder constructs the member whose field
arrived. A near-miss is a `BindMismatchException` naming it, not a guess.

**A bound class is always what a record reader builds.** A class bridged over a record (`ToData`, or `@Transparent`
over one) has its data form built and passed through the bridge by `BindRecordBuilder`; a component of such a class
over a field's record is met in `BindTargets` (`BridgedReader.ofRecord`). Bridges exist only on records and atoms, so
these two cases are the whole of a structured bridge.

## Every container reads on the record's terms

Arrays and sets, tuples and maps read as records do: a plan of what the schema fixes (`ArrayPlan`, `TuplePlan`,
`MapPlan`), one mode-free loop per form, and the mode's builder called once. A set's duplicates are judged on the
element's value through `ValueIdentity`; a tree node keeps only a spelling, so a set's atom element is read at
`TreeAtomKeyedReader`, which carries the parsed value's identity beside the node (`ValueIdentity.Identified`) — two
spellings of one instant are one element. A compound element or key compares by host equality over what the mode built,
which is all any mode can promise ([TSON-SCHEMA] §7.5).

**Bind mode compiles each type to its natural reading, and a component declares something more specific.** A
standalone array or tuple binds to an unmodifiable `List` of its values' natural host values and a map to an
unmodifiable `Map`; a record field declaring `long[]`, `List<Long>`, `Set<UUID>`, an `@Tuple` class or
`Map<LocalDate, BigDecimal>` reads the same position again for that class. A tuple binds by its component and not by
its schema name because an inline tuple's entry name is minted, and no binding map can hold it. `BindTargets` is the
one place that meets a component — an atom through `AtomReader.boundTo`, a `value` slot through
`ValuePositionReader.boundTo`, a container rebuilt over the component's class with every element, key and value
bound in turn, a record checked against it — and every disagreement joins the record's one `BindMismatchException`.
So an element of a `List<Long>` over `[int32]` is a `Long`, where `tson-compiler` reads it at the family's natural
class.

## Discrimination: one condition, and the table is built at schema load

§8.2's predicate is the rule [TSON-SCHEMA] §5.4 requires each encoding to state over the resolver-derived `disjoint`
fact, and it is closed: a value may omit its tag by exactly one condition, and it "MUST NOT be extended by
implementation cleverness". `DispatchChoiceReader` implements the whole of it — disjoint plus class-stable, selecting
on the arriving value's kind. **Member dispatch is not a choice mechanism** (§8.4): a choice position has no expected
record type whose selector fields a decoder could know before reading, so a choice of records requires the tag, and
member dispatch belongs to a sealed family (§6.1.5), in `DispatchMemberReader`.

**The verdict is computed once per choice, at compile** (§8.3's "table hit"); the table is empty exactly when the tag is
required. **Both halves are needed and neither implies the other.** `disjoint` guarantees at most one variant per class;
class stability guarantees the arriving kind lands in its variant's class. The unstable set is closed to two members
(`DiscriminationClass.stable`): an approximate atom still admitting `.nan` or infinity, whose values arrive as JSON
strings (§5.4), and a map forced into pairs form (§6.4). Narrowing `allow_nan`/`allow_infinity` restores the untagged
route, which is the checkable reason §8.3 gives an API author to narrow.

`DiscriminationClass` duplicates the TSON reader's derivation (unexported there); the parity test carries the cases.

**A missing tag is `TYPE_MISMATCH`**, as in TSON text and as the family readers give `tagRequired`: an absent tag
establishes no type, where `UNKNOWN_TYPE_REF` would claim a name denoted nothing.

**Two divergences the parity test pins as structural.** A choice cannot be a root type in text, where the root
type-ref is also the variant tag (`!scalars "hi"` names the choice), while JSON binds out of band (§3.4). And a tag
refusal lands at `/outline/$type` in JSON, where the tag is a member, against `/outline` in text.

## A scoped position: the open sum, read off the leading members

`DispatchScopedReader` serves every `scoped` instance (§8.5), what separates them being two constraint values,
`scope` and `schemas`, not a shape. It is `ScopedReader`'s peer; `design/scope-push.md` has the model, and what
follows is what the JSON carrier changes.

**The leading members pick the cell** (`TagMembers.lead`, which keeps `$schema`'s string beside `$type`'s). `$schema`
leading is EXTERN, `$type` alone is LOCAL, and anything else — a bare scalar, an array, an object leading with none of
them, a wrapper with no `$type` — names no type: a validation error in every mode. A cell the instance's `scope` does
not hold refuses the value, which is also how a `$schema` at a `declared` position is refused ([TSON-SCHEMA] §7.8's
cell rule).

**LOCAL is wired at compile.** Every name the governing namespace holds is resolved to a `Route` when the scoped entry
compiles, so a LOCAL read is one lookup and a route, as at a tagged record position. A name the namespace does not
hold is `UNKNOWN_TYPE`, after name hygiene.

**EXTERN is looked up as the value arrives, and the scope opens by consuming `$schema`.** Which schema a value names
is the document's choice, so the compile is handed `ForeignSchemas`: `JsonCompiledSchemaRegistry` passes its own
`get`, so a pushed schema compiles once, in the registry's mode, into its cache; a standalone compile passes
`ForeignSchemas.none()`, whose every lookup is `SCHEMA_NOT_PERMITTED`. A schema nothing supplies, or one that is
wrong, is reported by `ForeignSchemas.get` and never a verdict on the document. With the foreign
reader in hand, `JsonReadContext.consumeLeadingMember` drops `$schema` from the stream and leaves the object open: what
is left is `{"$type": …}` in the foreign namespace, and the foreign type's route reads it as any tagged value. A
`$schema` nested inside the pushed value is still refused where it stands. There is no scope stack: the foreign
reader is wired to the foreign schema's entries, and the scope pops by returning.

**Every mode hands back what the selected reader built**: a JSON tree keeps no tag of any kind, so it drops the scope
too, where TSON text's tree keeps the push (`TsonScopedValue`).

## An unknown root type names the author's declarations first

An `!!import` merges the imported entries first, so a list in namespace order would show core's names and none of the
author's. `JsonCompiledSchema.unknownTypeMessage` (the CLI's `--type` refusal, and `UNKNOWN_TYPE` at the facades)
leads with the entries this schema declares and the author wrote — by the origin index and by having a source
position, the test that tells a minted entry from an authored one. Imported and minted names remain usable root
types; the count that follows covers them.
