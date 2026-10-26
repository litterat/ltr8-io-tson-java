# Streaming readers and read context

The compiled reader stack's read-time behavior: the pull cursor (`TsonReadContext`), the validation rules every reader
applies, the continuation policy, and what a read leaves behind.

**Invariants**

- The context holds no error policy: `report` hands a `Diagnostic` to the read's `DiagnosticsReceiver`, no reader
  branches on which, and a reader asks `reported()` (a count) to learn whether its children complained.
- `of(...)` is not a whole-document read; in `requireDocumentEnd` the pull past the root value, not the assertion after
  it, is the point.
- A stated FIXED value is checked, not obeyed, with the **pre-rebind** parser (`FixedCheck`); what an omitted field
  yields is `RecordField.omitted`, derived once per field and shared by both encodings' readers.
- Collecting mode always keeps reading, so every problem surfaces; the value is all-or-nothing in both modes
  (`ConstructionGuard` per value, `CountingReceiver` at each facade) — a read that reported anything returns `null`.
- Reporting instead of throwing obliges a reader to skip (`EventSkip`), or the enclosing frame's next pull sees the
  value it declined.
- Every rule that compares two decoded values asks `ValueIdentity`, never Java `equals`: a `String` compares NFC, a
  `byte[]` as its octets, a `BigDecimal` without its scale, a `datetime`/`time` as an instant.
- A map's count is validated in `MapAbstractReader.expectMapShape`, not in `readInto` — an empty brace never enters the
  entry loop.
- Records are closed under their type and a repeated field or map key is an error, on the schemaless path too.

Related: `design/reader-naming-and-schema-location.md`, `design/scope-push.md`, `design/record-dispatch.md`,
`design/name-hygiene-read-path.md`, `design/diagnostic-model.md`, `design/diagnostic-rules-and-messages.md`,
`design/processor-policy.md`, `design/schema-side-diagnostics.md`, `design/facades-and-tree.md`.

## The read context

Every reader in `tson-compiler`'s `reader` package pulls `TsonEvent`s off a `TsonEventSource` through
`TsonReadContext` (the root package) — no reader requires a materialized `DataValue` tree, so validation and
diagnostics begin before the whole document is parsed. The schema pipeline itself is not streamed.

- **One cursor, many scoped copies.** `field`/`index`/`schemaField` return copies one step deeper, all over the same
  source and receiver, so `position()` is whichever event was last peeked or consumed on any copy. How the data path
  and the schema location are built — `schemaField`'s two names, `inRecord`/`underDeclaration` as every reader's first
  step, `withPosition` pinning a missing field's `FIELD_REQUIRED` to the record's opening — is
  `design/reader-naming-and-schema-location.md`'s.
- **Factories.** `of(events, receiver)` (§8.2's default identifier policy), `of(events, receiver, identifierPolicy)`,
  `throwing(events, identifierPolicy)` and `schemaDocument(events, identifierPolicy, heldByName, valueBody)`, over one
  implementation. The last is the resolver's replay of a value inside a schema document and the one read whose
  `readsSchemaDocument()` is true: the readers consult it for the one rule that is a schema document's alone (a bare
  name at a record position is a value by name, `design/record-dispatch.md`), and it alone **keeps** every name read at
  a kind-, reference- or `type_ref`-typed slot, handing it to `heldByName` with the slot's path and declared type
  (`TsonSchema.SlotType`) for `SchemaResolver` to keep per entry for the linker. Every other read drops it.
- **No error policy.** `report` builds the `Diagnostic` from the path and positions the context tracks and hands it to
  the receiver: `DiagnosticsReceiver.throwing()` raises `ReadException` at the first problem, `collecting()`
  accumulates into a `DiagnosticsCollector`, and a caller's own `report(Diagnostic)` can stream them anywhere.
  `reported()` is a count rather than a list so it works for a receiver that keeps none; the `int before =
  ctx.reported()` checkpoint brackets a single child read (`verifyFixed`, a map key in `MapAbstractReader` and
  `DataClassObjectReader`, a refused field name in `readFields`, `AnnotationCapture`'s throwaway probe) and is unrelated
  to `ConstructionGuard`'s mark, which uses the same counter.
- **One event of lookahead, plus a rewind.** `TsonReadContext.lookingAhead(ctx, fn)` runs `fn` and puts back every
  event it consumed, replayed from a buffer rather than re-lexed. `*annotation [type-ref] core-value` puts the type-ref
  behind a run of any length, so whatever is keyed on it — a facade selecting a root reader, a dispatcher choosing a
  variant (`EventSkip.typeRefAhead`/`aheadOfValue`) — needs it; `design/facades-and-tree.md` has the facade side. An
  ordinary reader knows its shape from the schema and never looks ahead.
- **`TsonReadContext` is exported.** `TsonTypeReader.read(TsonReadContext)` is the sole abstract method a consumer
  receives from `TsonCompiledSchema.get`, so hiding the parameter type would make the interface uncallable and
  unimplementable from outside. That is categorically worse than the accepted `-Xlint:exports` warning on
  `ValueReaderFactoryResolver`, an unexported type in the signature of `TsonSchemaCompiler.compile`'s factory overloads,
  which a consumer never calls (`design/modules.md`). The exported surface stays narrow because it carries no policy:
  whether a read fails fast and where its diagnostics go are the receiver's, and the context offers no accessor for
  either.
- **`of(...)` is not a whole-document read.** It assumes and performs no framing. Consuming the leading
  `DocumentStart`, and pulling *past* the root value so a lazy `TsonDataStream`'s root frame rejects trailing content,
  belong to `TsonTreeReader`/`TsonObjectReader`. The second half is easy to lose because nothing fails when a read
  simply stops.

## The read rules

### Fields

- **A FIXED field's value comes from the schema, and a document that states it is checked, not obeyed** (§5.2).
  `RecordAbstractReader.verifyFixed` decodes the written token and compares it to the schema's value: a contradiction
  is `FIELD_FIXED`, and the field still resolves to the *schema's* value, so a document cannot say one thing and decode
  to another in silence. The comparison uses the **pre-rebind** parser (`FixedCheck`), because bind mode narrows
  `precomputedValue` in place and comparing across that narrowing would flag every conforming document.
- **A field is four facts, and each decision reads its own** (`RecordField`: `optional`, `voidable`, `role`, `value`).
  A written `_` reads `voidable`; a written value reads `role` (FIXED means compare) and `value`; a field never written
  reads `RecordField.omitted`, derived from `optional` and `value` — `MISSING` (`FIELD_REQUIRED`) where not optional,
  `VALUE` (injected, whatever the role) where optional with a value, `NOTHING` otherwise. **A field group's member is
  never injected**, pin or not: its presence chooses the group's option, so injecting it would make present a member
  the document never wrote. Nothing is pre-seeded: every unstated field goes through one `valueForMissingField` switch
  over the three answers, and the JSON reader's `fillMissing` switches over the same three.
- **A group is judged after the field pass**: an option is chosen when any member appeared, a chosen option reports
  each member it needs and lacks (`optionNeeds`), and the chosen options are counted by the group's rule
  (`groupChoosesExactlyOne`, `groupChoosesAtMostOne`, `groupRequiresAtLeastOne` for `+`), all `FIELD_GROUP`.
- **A written `_` at a voidable field is present with a void value** (§2.9, "distinct from not appearing at all"), and
  tree mode keeps that: `{ x: _ }` reads with `x` a `TsonVoid` where `{ }` reads with no `x`, and `TsonTreeWriter`
  writes the first back as `_`. It is each mode's own answer (`statedVoidValue`) because bind mode has nowhere to put
  it — a Java component has no third state between "set to nothing" and "never set", so both arrive as `null`. That is
  a limit of the target, not a reading of §2.9, and the reason the tree is not aligned down to it. An array element and
  a tuple slot keep the same distinction.
- **A written `_` at a field that is not voidable is an error, whatever its modifier** (§7.6's table, `valueForVoidField`).
  At a defaulted field the report is the omission hint and the default is still what the field decodes to — only the
  verdict changes, the split `verifyFixed` makes for a contradicted pin; plain omission injects silently. A pinned
  field is never voidable (a pin on a voidable type is refused at resolve, `FieldModifiers`), so `_` at a FIXED field
  is always `FIELD_FIXED`, answered inside `verifyFixed`.
- **Records are closed under their type** (§7.2, `RecordAbstractReader.readFields`): a name the type does not declare
  is `UNRECOGNIZED_FIELD`, reported and then skipped unjudged (`TsonReadContext.skipUnjudged`), so a collecting pass
  finds every stray name. The diagnostic carries the type's field names in schema order, message *and* `expected` — the
  information that turns a retry into a one-shot fix. **Not configurable under a schema**, §7.2 making closure a MUST.
  The same line polices schema authoring: a constructor body is bound by replaying it through the governing meta's
  compiled reader, so `!integer ^ { minimum: 1 }` (JSON Schema's spelling of `min`) is rejected instead of compiling
  clean and constraining nothing (§7.2, "a constructor is a record-shaped type").
- **Records are closed on the schemaless bind path too**, where the target class is the schema: a field it does not
  declare is `UNRECOGNIZED_FIELD` rather than dropped, since a later version's extra field can change what the fields a
  class does read mean. `ignoringUnknownFields()` is the opt-out, on that path only, on both encodings' object readers.
- **A repeated record field or map key is an error** (`DUPLICATE_FIELD`/`DUPLICATE_MAP_KEY`, [TSON-DATA] §2.5/§2.6),
  reported at the repeat's own position, with "last value wins" running underneath: a single-pass pull stream cannot
  know a name recurs without buffering, so every occurrence is decoded (hence validated) and a later one overwrites an
  earlier. The repeat *is* the error, so whether its value would have been used decides nothing. **The same rules hold
  on the schemaless path** (`SchemalessTreeReader`, `DataClassObjectReader`): they are Part 1 rules, and a verdict that
  turned on whether a schema was in scope is the interoperability failure §2.5's MUST NOT exists to prevent. The
  schemaless object reader also counts a repeat of a name the class does not declare.

### Names and value identity

- **A field name's identity is its name type's form, and normalising happens where a token becomes a name.**
  [TSON-DATA] §7.2.1 has quoted tokens at identifier positions NFC-normalised before identity comparison, and string
  positions not normalised at that layer. The axis is the **position**, not the quoting, and the lexer cannot draw it,
  since a quoted token's position is not known at lex time. So `TsonDataStream` applies NFC at its two `FieldName`
  emission sites: one chokepoint for every record reader, and the name is stored in the form its identity is defined
  by, so `record.get("café")` finds a decomposed one. A record whose name type states another form (§5.5, a case fold)
  matches through `RecordAbstractReader`'s `matchIndex`, each declared name put into that form; under NFC the index is
  the declared one and nothing is allocated. A map **key** is a value and keeps its written content; only its
  comparison normalises.
- **One equality contract answers every rule that compares two decoded values** (`ValueIdentity`): §7.5's duplicate
  rule, §2.6's key identity, §5.2's check of a stated FIXED value, and a sealed family's pin distinctness. §5.5 is the
  contract they delegate to — equality is over the value space, never the spelling — and a comparison stated at each
  call site is several opinions. The failure worth remembering is `bytes`: `byte[]` carries identity equality, so an
  absent comparison there is an **inverted** verdict, a FIXED `bytes` field rejecting the only document it can accept;
  `BigDecimal.equals` does the same to a `= 1.0` field meeting `1`. A tree atom compares by its normalised value
  **beside its type-ref and annotations**: stripping those is §2.6's rule for a key, and reaching a set it would merge
  `!cm 5` with `!inch 5`. The identity is never what a reader hands back — TSON text preserves the offset and the digits
  as written. `Rendered` is the other half: a diagnostic naming a `byte[]` renders it through `Rendered`, not as
  `[B@6d06d69c`.
- **A map key's identity is its decoded value, with type-ref and annotations stripped, at every layer that decodes.**
  §2.6 layers key identity — textual at the parser, decoded values for "a processor that decodes values", a declared key
  type able to make more keys equal and never fewer — and strips annotations and the type annotation at every layer.
  So the schemaless readers relate `0xFF` and `255`, and `!person a` and `a`; `1` and `1.0` stay two keys until a
  `number`-keyed schema makes them one. `SchemalessTreeReader.keyIdentity` strips explicitly; the other two schemaless
  paths compare bound host values, which strips both by construction.

### Containers

- **An array element's one fact is `voidable`, and a void element occupies its slot.** Under `[T?]` an element may be
  `_`; under the default it is `FIELD_REQUIRED`. Either way `ArrayAbstractReader` consumes the `VoidEvent` and
  advances the index, so `[a _ c]` has three elements and satisfies a `[T?; 3]` size constraint (§7.6: "size
  constraints count all slots") by counting rather than by a check. Elements have no default or fixed concept, so the
  `valueForMissingField` machinery has no array counterpart.
- **A map entry's value may be `_` where the schema says so, and the entry counts either way.** `MapBody.voidable`
  governs the value (`{K => V?}`, §5.3), so `MapAbstractReader.decodedValue` gives the array element's two answers. It
  answers above the value's own reader, which is right to refuse the sentinel (`_` is a value of no atom type) —
  absence is the container's question. The entry is present with a void value (§2.9) whichever answer it gets, so it
  counts toward `min_items`/`max_items` (§7.6) and a refusal costs the value its verdict, not the entry its place.
- **The key is the opposite and unconditional**: §2.9 forbids `_` there whatever a declaration says, and the parser
  refuses a `?` on that side. **The schemaless reader enforces it too**, which is where it most needs enforcing — §2.9
  is a Part 1 rule, so Class 1 data is exactly the case it governs, and the map-entry production accepts any data-value
  in key position, so no tier below the reader can refuse one. `SchemalessTreeReader` reports it and leaves the key out
  of the duplicate set: a second `_` is this same problem again, not a repeat of a key the document stated.
- **`{}` is the empty container of the position's own type, size rules included.** [TSON-DATA] §2.8 resolves an empty
  brace to the empty record or the empty map by expected type, so at a map position it is a map with zero entries and
  `min_items: 1` rejects it. The count is validated in `MapAbstractReader.expectMapShape`, the funnel every map reader
  passes through, and **not** in `readInto`: an empty brace never enters the entry loop, so a check there would miss
  `min_items` while `max_items` reported correctly. A record position reports each missing required field for `{}`, and
  `[]` is an ordinary empty element list, so all three positions agree.

## Continuation, assembly and binding

- **Always keep reading in collecting mode.** A failed field or element is recorded and the read goes on, so every
  problem surfaces in one pass and later indices stay accurate; a shape mismatch reports `TYPE_MISMATCH`/`WRONG_ARITY`
  and returns `null`, so a caller does not also report every child as missing.
- **Every read is all-or-nothing, in both modes and both encodings** (`ConstructionGuard`, whose Javadoc states the
  rule and the reasons). A value whose read reported *anything*, its own problem or a descendant's, is not assembled
  and reads to `null`, which propagates to the root; a stray or repeated field counts like any other diagnostic. Each
  facade applies the rule once more at the **document boundary** through a per-read `CountingReceiver`, covering what
  the per-value guard cannot: the root value's own framing, a schemaless read, and a token refusal the stream reports
  straight to the receiver. **The mark goes after the framing, before the fields**, so a container type-ref's
  `UNKNOWN_TYPE_REF` belongs to the enclosing read that chose to look there.
- **A bound class refusing what a read hands it is a diagnostic, never an exception** (`BindingDiagnostics`, for both
  encodings). A constructor's own check, a bridge, an unboxing `NullPointerException` at a primitive, a collection
  refusing `null` meeting a void value: the `try` covers the host call and nothing else, so a nested read's own
  `ReadException` still passes through. **Written inline at each site, not through a helper taking a lambda**: `put`
  runs per element and the constructor per record, a closure per call is bytes on the success path, and the `Refusal`
  is built only in the `catch`. Under a schema the refusal is `BIND_MISMATCH` (the binding disagreeing with the schema,
  no verdict); with no schema the class is the contract and it is `TYPE_MISMATCH`. A streaming container stops calling
  `put` once anything has been reported since its mark, since a refused child travels as `null`.
- **A position's reader is bound to its Java target where the reader is built** (`BindTargets`, reached from
  `RecordBindReader`'s field loop; `tson-json` has its own over the same rule). An atom binds the target's wire class
  through `AtomType.boundTo`, which every family answers with no default, so a read carries no target and a component
  the family cannot fill is a compile-time mismatch, never a cast failing in a constructor. The target's own bridge is
  applied there too (`ElementBridging.wrap`, which the array and map bind readers also take for their elements),
  because a schema-driven read builds its constructor arguments itself rather than through `ltr8-bind`'s binder: a
  consumer's `registerAtom(Money.class, bridge)` or `@Transparent` wrapper binds the same under a schema as without one
  (`AtomBoundClassUnderSchemaTest`). What else is known before any document is refused at compile is
  `design/schema-side-diagnostics.md`'s.
- **A reader names itself by what the author wrote**, as the position wrote it (`EntryDisplayName`, `UseSite`, both
  built with the reader); `design/reader-naming-and-schema-location.md` has both.
- **`EventSkip` is the shared grammar-aware consume-and-discard** — leading annotations and an optional type-ref as a
  reader's first step, a whole value, or one core-value on a shape mismatch. **Reporting instead of throwing obliges a
  reader to skip**: the fail-fast receiver never returns, so a reader that reports and yields nothing without consuming
  is correct only until a collecting receiver meets the same document, when the declined value is still pending and
  the enclosing frame's next pull sees it — at the document boundary `requireDocumentEnd`, whose
  `IllegalStateException` then fires on ordinary caller input with the diagnostics lost inside it. Where the skip goes
  is the caller's question: `DataClassObjectReader.read` has taken nothing and skips a whole data value, while
  `bindUnion` has consumed the framing to find the member and skips the core-value alone.
- **`ListEventSource`** replays a pre-built event list: a schema default (`readSchemaDefault` wraps the literal `Token`
  as one synthetic event, under `IdentifierPolicy.none()` since the real stream already judged it), an annotation probe,
  and, via `DataValueEvents`, the resolver's replay of a resolved `DataValue` through a compiled reader.

## What a read leaves behind: nothing

A read is transient by construction: the compiled schema, the reader graph and the bind descriptors are built once and
live for the process, and a read holds no cache of its own. `AllocationHarnessTest` (`tson/src/test/.../perf/`,
`./gradlew :tson:allocationReport`) makes that a checked fact, over the bind path:

- **Retention is 0 bytes per read**, measured as settled heap across 20,000 reads through one long-lived reader and
  10,000 through a reader *derived per read* (the shape a server writes). A cache keyed per document — the classic
  accidental leak, and the one thing that would make "resolve at startup" advice a lie — shows up here and nowhere else.
- **Every read result is collectable**, asserted with weak references rather than a measurement: 500 bound objects,
  all cleared once the collector has demonstrably run.
- **Transient bytes are reported per read**, with a ceiling only a gross regression trips. `whereAReadsBytesGo` splits
  one document's read into event stream, schemaless tree, schema tree, schemaless bind and schema bind, which turns
  "allocation went up" into "which stage". The lexer decodes UTF-8 from the `ByteSource` itself, so no JDK decoder sits
  on the read path.
