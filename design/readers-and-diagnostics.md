# Streaming readers and read context

The compiled reader stack's read-time behaviour: the pull cursor (`TsonReadContext`), the rules every reader applies,
the continuation policy and binding. The rules themselves are [TSON-SCHEMA] §5.2, §5.11, §7.2, §7.6 and [TSON-DATA]
§2.5, §2.6, §2.9; this note holds where an implementation of them goes wrong silently.

**Invariants**

- The context holds no error policy: `report` hands a `Diagnostic` to the read's `DiagnosticsReceiver`, no reader
  branches on which, and a reader asks `reported()` (a count, so it works for a receiver that keeps none) to learn
  whether its children complained.
- `of(...)` is not a whole-document read; in `requireDocumentEnd` the pull past the root value, not the assertion after
  it, is the point: a lazy `TsonDataStream` rejects trailing content only when pulled, and nothing fails when a read
  simply stops.
- A stated FIXED value is checked, not obeyed, with the **pre-rebind** parser (`FixedCheck`): bind mode decodes
  `precomputedValue` through the rebound one, and comparing across that narrowing would flag every conforming
  document. What an omitted field yields is `RecordField.omitted`, derived once per field and shared by both
  encodings' readers.
- A record reader's constructor reads through no field's reader: a field's type may be an entry still on the build
  stack, a `DeferredTypeReader`. Defaults and pins are decoded by `settleHeldValues`, which runs once every reader of
  the compilation exists (`CompiledReaders.settle`), at load. It judges nothing — the linker's `checkFieldValue` is the
  one place a pin is judged — so a refusal through the built parser is an `IllegalStateException` (linker and reader
  disagree) and one through a rebound parser a `BindMismatchException`, both failing the compile.
- Collecting mode always keeps reading, so every problem surfaces; the value is all-or-nothing in both modes
  (`ConstructionGuard` per value, `CountingReceiver` at each facade) — a read that reported anything returns `null`.
- Reporting instead of throwing obliges a reader to skip (`EventSkip`). The fail-fast receiver never returns, so a
  reader that reports and consumes nothing passes every throwing test; under a collecting receiver the declined value
  is still pending, the enclosing frame's next pull sees it, and at the root `requireDocumentEnd`'s
  `IllegalStateException` fires on ordinary input with the diagnostics lost inside it.
- Every rule that compares two decoded values asks `ValueIdentity`, never Java `equals`: a `byte[]` without it inverts
  the verdict, a FIXED `bytes` field rejecting the only document it can accept. Its Javadoc has each host type's rule.
- A map's count is validated in `MapAbstractReader.expectMapShape`, not in `readInto` — an empty brace never enters the
  entry loop, so a check there misses `min_items` while `max_items` reports correctly.
- Records are closed under their type, and a repeated field or map key is an error, on the schemaless path too
  (`SchemalessTreeReader`, `DataClassObjectReader`): they are Part 1 rules, and a verdict that turned on whether a
  schema was in scope is the interoperability failure [TSON-DATA] §2.5's MUST NOT exists to prevent.
- `_` as a map key is refused by `SchemalessTreeReader` as well, and left out of its duplicate set: the map-entry
  production admits any data-value as a key, so no tier below the reader can refuse it, and a second `_` is the same
  problem again rather than a repeat of a key the document stated.
- A field name is NFC-normalised where `TsonDataStream` emits a `FieldName`, not in the lexer: [TSON-DATA] §7.2.1's
  axis is the position, not the quoting, and the lexer cannot know a quoted token's position. That is one chokepoint
  for every record reader, and the name is stored in the form its identity is defined by, so `record.get("café")`
  finds a decomposed one. A map key is a value and keeps its written content; only its comparison normalises.
- A dependent field is read through its record (`DependentFieldReader.readAgainst`), never on its own: the record
  knows the sibling's value in this instance. A record with none keeps no per-read state for it.
- A reader deciding by a tag reads it through `EventSkip.typeRefAhead`, an annotation's name is resolved in
  `AnnotationCapture` alone, and `RecordMemberDispatchReader` alone places a value by its pins: those are where a schema
  document's read stops for an entry its readers lack, so a reader that bypasses them refuses at a checkpoint what the
  full closure admits (`design/resolver-vocabulary-and-bootstrap.md`).
- A bound class refusing what a read hands it is a diagnostic, never an exception (`BindingDiagnostics`, both
  encodings). The `try` covers the host call and nothing else, so a nested read's own `ReadException` passes through,
  and a streaming container stops calling `put` once anything has been reported since its mark, a refused child
  travelling as `null`.

Related: `design/reader-naming-and-schema-location.md` (data path, schema location, `withPosition`),
`design/declared-positions.md`, `design/record-dispatch.md`, `design/name-hygiene-read-path.md`,
`design/diagnostic-model.md`, `design/diagnostic-rules-and-messages.md`, `design/processor-policy.md`,
`design/schema-side-diagnostics.md` (what is refused at compile), `design/facades-and-tree.md`, `design/build.md`
(the allocation harness: a read retains nothing).

## Decisions that lost

- **Hiding `TsonReadContext` from the module's exports.** `TsonTypeReader.read(TsonReadContext)` is the sole abstract
  method a consumer receives from `TsonCompiledSchema.get`; hiding its parameter type makes the interface uncallable
  and unimplementable from outside, categorically worse than the accepted `-Xlint:exports` warning on
  `ValueReaderFactoryResolver`, which a consumer never calls (`design/modules.md`). The exported surface stays narrow
  because it carries no policy: whether a read fails fast and where its diagnostics go are the receiver's, and the
  context offers no accessor for either.
- **Aligning the tree down to bind mode's `_`.** A written `_` at a voidable field is present with a void value
  (§2.9), and the tree keeps that: `{ x: _ }` reads with `x` a `TsonVoid` where `{ }` reads with no `x`, and
  `TsonTreeWriter` writes the first back as `_`. Bind mode cannot — a Java component has no third state between "set
  to nothing" and "never set", so both arrive as `null` — which is a limit of the target, not a reading of §2.9. Each
  mode answers through its own `statedVoidValue`; an array element and a tuple slot keep the same distinction.
- **Buffering a dependent field written before its free sibling.** No stream can say yet whether the sibling comes,
  so the value is skipped unjudged and refused when the sibling arrives, pinned where the field stood
  (`RecordDiagnostics.writtenBeforeItsSibling`). No lookahead and no held value is added. In a schema document the
  value is held as written and judged at link instead (`DependentFieldReader`, `design/linking-and-compilation.md`).
- **A refusal helper taking a lambda.** Each binding refusal is caught inline at its site: `put` runs per element and
  the constructor per record, a closure per call is bytes on the success path, and the `Refusal` is built only in the
  `catch`.
- **Binding through `ltr8-bind`'s binder under a schema.** A schema-driven read builds its constructor arguments
  itself, so the target is bound where the reader is built (`BindTargets`) and its bridge applied there
  (`ElementBridging`): a consumer's registered atom or `@Transparent` wrapper binds the same under a schema as without
  one (`AtomBoundClassUnderSchemaTest`), and a component the family cannot fill is a compile-time mismatch, never a
  cast failing in a constructor.
