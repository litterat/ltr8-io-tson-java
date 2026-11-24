# The JSON encoding: schema-directed reading

`tson-json`'s schema-directed readers: `JsonSchemaCompiler`, `JsonTypeReader` and the unexported `reader` package — the
annotation object, the record, container, choice and declared readers. Binding a document at the facades and its
failure codes are `design/json-facades-binding-writing.md`'s; naming inside `reader` is the root `package-info`'s.
Section numbers are [TSON-JSON]'s unless prefixed.

**Invariants**

- A JSON document binds out of band: `Json.withSchemas` takes a `TsonSchemaLoader`, and failing to reach the schema is a
  diagnostic and never a verdict.
- A `$type` is matched after alias flattening at every position that compares a written name against a set, through
  the one index `AliasChain.namesMeaning`.
- Selectors lead their object (§3.3, §6.1.5, §6.1.6): every decision is a peek at the leading members
  (`TagMembers.lead`), bounded by the schema, and no reader scans an object.
- The concrete record reader looks ahead at nothing: its member loop judges every annotation member as it arrives.
- `$import` is refused at every value position (`Tags`, `SCOPE_NOT_ADMITTED`); a root led by one is declined as
  `NOT_IMPLEMENTED` (`InBandBinding`), since refusing it there is a verdict that changes once the in-band route is built.
- `CompiledReaders` is rebound exactly once, from the in-progress compilation to the finished schema.
- The map form is chosen by the factory from `K` and the record reader from `record.extension`, once at compile, never by
  inspecting a value.
- A choice value may omit its tag only when the choice is disjoint; no member-shape matching, no trying variants in
  order. A missing required tag is `TYPE_MISMATCH`.
- A kind, and a reference over a type that is no constructor, is an atom read by `AtomReader` through
  `AtomParsers.forRead`, and never a discrimination case (§5.6). `AtomParsers.forType` is the pre-link callers' and
  refuses a value reference as a gap, so no JSON atom position asks it. A tree read keeps the `JsonString` as written.
- A dependent field is read by `DependentReader` through its record, with TSON's rules
  (`design/readers-and-diagnostics.md`). The record keeps the sibling's written string, and the state, only where it has
  a dependent field. Bind mode binds the member to an `Object` component, as the TSON reader does.
- A dispatcher only selects: every reader it can select is wired when the schema compiles, it builds nothing, and one
  set serves every mode. The reader it selects validates in full.
- A wrapper's `$value` is no annotation object (§3.3): every wrapper's value is read through `TagMembers`, which refuses
  an object there leading with an annotation member before the selected reader sees it, unless the selected type is
  map-typed (`TagMembers.keyed`, fixed at compile on the `Route`, the boundary's `Crossing` and a cycle's
  `DeferredTypeReader`).
- A position an extern types is a boundary (`ExternReader`): its leading `$type` is the enclosing scope's, everything
  inside the foreign schema's, read by that schema's own compiled readers (`design/externs.md`).
- Every mode is all-or-nothing: no builder builds where anything was reported while its value was read.

Related: `design/json-encoding.md` (why the stack is separate, the parity guard), `design/json-lexer-stream-tree.md`,
`design/json-facades-binding-writing.md`, `design/json-unicode-policies.md`, `design/linking-and-compilation.md`,
`design/record-dispatch.md`, `design/declared-positions.md`, `design/cli-config-hashing.md`.

## Alias flattening has one index

Every position that compares a written `$type` against a set (record, abstract and sealed bases, choice variants, and
each one's subtypes) asks `AliasChain.namesMeaning`, built once per compile and held by `ValueReaderContext`
(`admitting`), so the set of positions that flatten cannot become whichever ones someone remembered. TSON text's peer
is `Subsumption.admitting`. It is load-bearing for an alias of a declared application, which a document may name as
the application's declaration itself ([TSON-SCHEMA] §8.3).

## The concrete record reader, and why it holds nothing back

`RecordReader` is reached only for its own type, so it needs no peek: a leading `$type` must restate the record
(`RecordPlan.admitsTag`), `$value` straight after it makes a wrapper read at the reader the enclosing position chose
(`ExactReader`, `Route`), and a misplaced annotation member refuses the object (`RecordPlan.refuseTagMember`).

Members read before a refusal have already reported, so an invalid document's diagnostics follow its member order —
accepted deliberately: holding them back would cost every valid document a buffer to tidy the answer for invalid ones.
The allocation harness measures the absent lookahead (`aSchemaDirectedRecordReadsWithoutLookingAhead`).

**One loop serves every record shape and every mode.** The per-field branch on field state is a predictable switch; a
loop per shape would multiply by the modes, so a split waits for a timing benchmark showing it pays. What differs by
mode is decided off the per-field path: the factory, once at compile (the reader each field is read at, the form a
stated value takes), and the builder, once per record.

**A bind-mode class is checked when the reader is built** (`BindMismatchException`), but a schema type with no bound
class is deferred (`MissingBindingException`, rethrown by an `ErrorReader` when first read), since a schema declares
types a given consumer never binds. Bridges exist only on
records and atoms, so `BindRecordBuilder` (a class bridged over a record) and `BridgedReader.ofRecord` (such a
component) are the whole of a structured bridge.

**Bind mode reads each type to its natural host value and re-reads for a more specific component** (`BindTargets`), so
an element of a `List<Long>` over `[int32]` is a `Long`, where `tson-compiler` reads it at the family's natural class.
A tuple binds by its component, not its schema name, because a tuple written in place has no entry name a binding map
could hold.

## Record families and choices

**A parametric record is no type** ([TSON-SCHEMA] §5.10): `DispatchFactories.over` answers its body with
`DispatchFactories.PARAMETRIC_RECORD`. A family declared through one is read at its base, and the selectors are stated
on the base (`tson-schema`'s `FamilySelectors`), so neither side of the module wall keeps its own opinion of them. A
subtype index holds no parametric record.

**A choice variant placed by `$type`** routes a sealed variant only by handing it the value, whose selectors a choice
position cannot bypass, and below an atom variant to the refinement's own reader, whose facets the variant's would not
check.

**A missing tag is `TYPE_MISMATCH`**, as in TSON text and the family readers' `tagRequired`: an absent tag establishes
no type, where `UNKNOWN_TYPE_REF` would claim a name denoted nothing.

**Member dispatch is not a choice mechanism** (§8.4): a choice position has no expected record type whose selectors a
decoder could know before reading, so member dispatch belongs to a sealed family (`DispatchMemberReader`).
`DiscriminationClass` duplicates the TSON reader's derivation, which is unexported there.

**What `CrossEncodingParityTest` pins as divergence from TSON text**: a sealed discriminator that does not lead is
missing, with JSON's wording; a tag disagreeing with the discriminator is caught by the selected member's FIXED check,
where TSON text compares tag and member in the dispatcher (`BACKLOG.md`); a choice cannot be a root type in text,
where the root type-ref is also the variant tag, while JSON binds out of band; and a tag refusal lands at
`/outline/$type` in JSON, where the tag is a member, against `/outline` in text. A field group's missing-member order
(`RecordPlan.GroupPlan`, option order, count last) is pinned to agree with `RecordAbstractReader`'s.
