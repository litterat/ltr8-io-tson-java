# Streaming readers and diagnostics

Design notes for the compiled reader stack's read-time behavior — the pull cursor, validation rules,
continuation policy — and the diagnostics model on both the data and schema sides. Current form only;
history lives in git. `CLAUDE.md` holds the one-paragraph orientation; this file holds the detail.

## Streaming readers and read context (`tson-compiler/.../reader/`)

Every reader in `reader` pulls `TsonEvent`s directly off a `TsonEventSource` via `TsonReadContext` — no
reader ever requires a materialized `DataValue` tree, so schema-validated reading and diagnostics can
begin before the whole document is parsed. (The schema pipeline itself is not streamed — a schema document
is small and parsed once.)

- **`TsonReadContext`** is the pull cursor: `peek()`/`next()` (over one shared `TsonEventSource`),
  `position()` derived live from the last event, `path()` (RFC 6901), `field(name)`/`index(i)` (push a
  path segment), `at`/`withSchemaPosition`/`withPosition`, `report(code, message, expected, actual)`,
  `reported()`. One factory, `of(events, receiver)` (plus `throwing(events)` sugar), over one
  implementation. **The context holds no error policy**: `report` builds the `Diagnostic` from the path and
  positions it tracks and hands it to the read's **`TsonDiagnosticsReceiver`**, which decides its fate —
  `throwing()` raises `TsonReadException` at the first problem, `collecting()` accumulates into a
  `TsonDiagnosticsCollector`, and a caller's own `void report(Diagnostic)` can stream them anywhere. No
  reader branches on which. A reader needing to know whether its children complained asks `reported()` — a
  count, so it works for a receiver that keeps no list (the `int before = ctx.reported()` checkpoint idiom
  in `RecordBindReader`/`TupleBindReader`/`SchemalessObjectReader`/`AnnotationCapture`).
- **`TsonReadContext` is deliberately still exported.** `TsonTypeReader.read(TsonReadContext)` is the sole
  abstract method a consumer receives from `TsonCompiledSchema.get`, so hiding the parameter type would
  make that method uncallable and the interface unimplementable from outside — categorically worse than the
  accepted `ValueReaderFactoryResolver` `-Xlint:exports` warning, where the hidden type is only ever *returned*.
  What was removed instead is the conflation: `failFast()` (no callers) and `diagnostics()` (the receiver's
  job) are gone.
- **`of(...)` is not a whole-document read.** It assumes and performs no framing. Consuming the leading
  `DocumentStart`, and pulling *past* the root value so a lazy `TsonDataStream`'s root frame actually
  rejects trailing content, belong to `TsonTreeReader`/`TsonObjectReader`. That second half is easy to lose,
  because nothing fails when you simply stop reading — `requireDocumentEnd`'s Javadoc in both facades
  records that the pull, not the assertion after it, is the point.
- **A FIXED field's value comes from the schema, and a document that states it is checked, not obeyed**
  (§5.2). `RecordAbstractReader.verifyFixed` decodes the written token and compares it to the schema's
  value: a contradiction is `ATOM_CONSTRAINT_VIOLATION`, and the field still resolves to the *schema's*
  value. Skipping it unread — the old behaviour — let a document say one thing and decode to another in
  silence. The comparison uses a raw parsed value and the **pre-rebind** parser (`FixedCheck`), because bind
  mode narrows `precomputedValue` in place and comparing across that narrowing would flag every conforming
  document. **The two FIXED states differ in exactly one thing:** §5.2's injection rule names
  `REQUIRED_DEFAULT` and `REQUIRED_FIXED` and *not* `OPTIONAL_FIXED`, so an omitted `OPTIONAL_FIXED` field
  stays **absent** while an omitted `REQUIRED_FIXED` one is injected. Reading it the other way makes the two
  states indistinguishable and the `?` decide nothing (`SPEC-FEEDBACK.md` #39). `_` is a validation error at
  `REQUIRED_FIXED`, fine at `OPTIONAL_FIXED`; a `= _` field (`OPTIONAL_FIXED` with no value) admits only
  omission or `_`. There is no pre-seeding pass any more: every field the document didn't state goes through
  one `valueForAbsentField` switch over all five states.
- **Continuation policy: always keep reading in collecting mode.** A failed field/element is recorded and
  a `null` placeholder kept in place (so later indices stay accurate); a shape mismatch reports
  `TYPE_MISMATCH`/`WRONG_ARITY` and returns `null` so a caller doesn't also report every child as missing.
- **Bind mode is all-or-nothing; tree mode is not** (`ConstructionGuard`, which states the rule once for all
  nine bind-mode assembly sites). A value whose read reported *anything* — its own field's problem or a
  descendant's, whether or not it left an argument unfilled — is not assembled and binds to `null`, which
  propagates to the root. A tree read is the opposite: a `TsonValue` is inspectable structure a caller can
  hold beside the diagnostics, so both tree readers keep everything they built. The asymmetry is the point,
  not an inconsistency — a bound object is typed application data whose *existence* is the claim that the
  document was good, so handing one back for a document already known to be wrong is the failure binding
  exists to prevent. A stray field (`UNRECOGNIZED_FIELD`) or a repeat (`DUPLICATE_FIELD`) counts like any
  other diagnostic: the only question the rule asks is whether the document is wrong. `TsonObjectReader`
  applies the same rule once more at the **document boundary**, covering the two positions the per-value
  guard structurally cannot — the root value's own framing (no enclosing read brackets it) and a root
  array/map (a collection tolerates a `null` child where a constructor doesn't). **The mark goes after the
  framing, before the fields**, so a container type-ref's `UNKNOWN_TYPE_REF` belongs to the enclosing read
  that chose to look there. Narrower uses of the same `ctx.reported()` idiom are unrelated and stay put:
  `MapAbstractReader`/`SchemalessObjectReader` asking whether one key bound, `verifyFixed` asking whether one
  token decoded, `AnnotationCapture`'s throwaway probe context — each brackets a single child read.
- **Every reader stamps its own schema position** first thing (`ctx.at(value).withSchemaPosition(...)`) so
  a diagnostic from inside an atom carries *that atom's* declared position. A record field never mentioned
  by the data can only be noticed after the record is consumed, so its `FIELD_REQUIRED` reports against
  the record's *opening* position (captured up front) via `withPosition`, not the live cursor.
- **Records are closed under their type** ([TSON-SCHEMA] §7.2, `RecordAbstractReader.readFields`): a field
  name the type doesn't declare is `UNRECOGNIZED_FIELD`, reported and then skipped, so a collecting pass
  finds every stray name and the value still comes back whole. The diagnostic carries the type's real field
  names in schema order (message *and* `expected`) — the information that turns a retry into a one-shot
  fix. **Not configurable**: §7.2 makes closure a MUST wherever a schema is in scope and exempts only
  schemaless records, which are read by `SchemalessObjectReader`/`SchemalessTreeReader` and never reach
  this code. **The same rule polices schema authoring**, through the same line: a constructor body is bound
  by replaying it through the governing meta's compiled reader, so `!integer ^ { minimum: 1 }` (JSON
  Schema's spelling of `min`) is rejected instead of compiling clean and constraining nothing — §5.5/§5.7
  never say so themselves, which is `SPEC-FEEDBACK.md` #40. In bind
  mode a reported record still binds to `null` — the all-or-nothing rule above, not something closure chose.
- **A repeated record field name or map key is an error** (`DUPLICATE_FIELD`/`DUPLICATE_MAP_KEY`,
  §2.5/§2.6), reported at the repeat's own position, with the spec's "last value wins" recovery still
  running underneath: a single-pass pull stream can't know a name recurs without buffering, so every
  occurrence is decoded (hence validated) and a later one overwrites an earlier. Both spec rules are
  written as SHOULD-warn; `SPEC-FEEDBACK.md` #41/#42 makes them errors, which also dissolves #21's
  shadowed-occurrence question — the repeat *is* the error, so whether its value was going to be used
  decides nothing. **The same rules hold on the schemaless path** (`SchemalessTreeReader`/
  `SchemalessObjectReader`), these being Part 1 rules a document violates with or without a schema; a
  verdict that turned on whether a schema was in scope would be the interoperability failure #41 argues
  against. In the schemaless object reader the seen-set is keyed on the *written* name, not the
  target-class slot, so a repeat of a name the class doesn't declare still counts.
- **A map key's identity is its structure and decoded values, with type-ref and annotations stripped** —
  §7.7's host-value equality, applied at every layer that decodes rather than only where a schema is in
  scope. So `0xFF` and `255` are one key, and `!person a`/`a` are one key. **This is a deliberate
  divergence from §2.6**, which defines key identity *textually* at the parser layer (`Alice`/`"Alice"`
  are duplicates, `1`/`1.0` are not) and leaves typed equality to §7.7's MAY — the series names no
  equality for the Class 1 *reader* in between, which has run §4 base resolution but has no declared
  types. `SPEC-FEEDBACK.md` #43 has the table and the argument: a key realised as a host value is one key
  whatever §2.6 says, since that is what the host `Map` will do with it. `SchemalessTreeReader.keyIdentity`
  does the stripping explicitly; the other two readers compare bound host values, which strips both by
  construction.
- **A written `_` at a `REQUIRED_DEFAULT` field is an error**, where plain omission still injects the
  default silently (`valueForStatedAbsentField` against `valueForAbsentField`). §5.2 asks for a warning
  and an injection; #42 calls this its strongest case, since warn-and-inject answers "here is a value" to
  a document that said "absent". The default is still what the field decodes to — only the verdict
  changes, the same split `verifyFixed` makes for a contradicted FIXED value.
- **`EventSkip`** is the shared grammar-aware "consume and discard" utility (leading annotations + an
  optional type-ref as every reader's first step; a whole value; one core-value on a shape mismatch, to
  keep the stream correctly positioned). **`ListEventSource`** replays a pre-built event list — used for a
  schema default (`readSchemaDefault` wraps a literal `Token` as one synthetic event) and, via
  `DataValueEvents`, for replaying an already-resolved `DataValue` tree through a compiled reader (the one
  place `resolver` still has a `DataValue` in hand).

## Diagnostics (`Diagnostic`, root package)

`Diagnostic` is the structured value every `TsonDiagnosticsReceiver` receives, identical shape whichever
one is in play: a closed `Code` enum (`FIELD_REQUIRED`/`TYPE_MISMATCH`/`WRONG_ARITY`/`UNKNOWN_TYPE_REF`/
`ATOM_CONSTRAINT_VIOLATION`/`UNRECOGNIZED_FIELD`/`DUPLICATE_MAP_KEY`/`DUPLICATE_FIELD` from readers;
`SCHEMA_ERROR`/`UNKNOWN_TYPE`/`VALIDATION_ERROR` for infrastructure-level failures),
`message` (hand-composed per call site), `expected`/`actual` (machine-parseable), and **four location
components covering two ends** — the value in the data, and the rule in the schema.

**The four are JSON Schema 2020-12 §12's own output unit**, deliberately: `path` is `instanceLocation` (an
RFC 6901 pointer into the data), `schemaPointer` is `keywordLocation` (an RFC 6901 pointer into the schema's
`map<type_name, type_definition>`, `/my_type`), `schemaId` plus `schemaPointer` are
`absoluteKeywordLocation`, and `dataPosition`/`schemaPosition` add the line/column/byte-offset TSON needs and
JSON Schema has no equivalent of. **One record rather than separate data- and schema-diagnostic types,
because the variation is locational, not categorical** — a value violating `int32` as core.tn declares it
populates both ends at once, and `javax.tools.Diagnostic`, LSP's `Diagnostic` and rustc's `DiagInner` all
model it the same way (rustc's `MultiSpan` being the mature form of the same idea).

Either end may be empty: a schema-side problem has no data, and a schemaless read has no schema.
**`schemaPosition` comes from `TypeDefinition.position()`**, which is
populated because `SchemaResolver.resolveSchema` takes `TsonSchemaParser.declarationPositions()` and passes
each declaration's own position into `DefinitionResolver.resolve` — so a value error points at both ends, the
value in the data and the type it violated in the schema. Every reader stamps its own position first, so the
one reported is the *atom's* declaration (`int32` in core.tn), not the enclosing record's. **The read path
populates `schemaPosition` but not `schemaId`/`schemaPointer`** — a reader knows the declaration position it
stamped, not which entry of which schema it came from, so which schema a read diagnostic's position refers to
is still implicit; the schema path populates all three (`BACKLOG.md`).
An atom's `AtomTypeException` is caught in `AtomTypeReader` and mapped to
`ATOM_CONSTRAINT_VIOLATION` — `AtomType`'s own signature is untouched, since it's shared with the
schemaless binder which has no read context. Out of scope for now: message synthesis from code + params,
fine-grained atom codes, and per-field schema positions.

## Schema-side diagnostics (`SchemaResolver`, `TsonSchemaLinker`, `Tson.validateSchema`)

A broken *schema* reports every independent problem in one pass, through the same
`TsonDiagnosticsReceiver` the read path uses. §8.1 asks for both halves of this: implementations MUST carry
source position in **all** error reports, and SHOULD "continue processing after an error to report multiple
issues in a single pass" — and it explicitly puts schema resolution/compilation failures in the *resolver
error* category, so this is the same layer, not a new one.

- **Two reporting overloads, `SchemaResolver.resolveSchema(document, positions, receiver)` and
  `TsonSchemaLinker.link(schema, loader, receiver)`.** The existing overloads are untouched and still throw
  at the first problem. **The fail-fast paths deliberately do not route through
  `TsonDiagnosticsReceiver.throwing()`** — that raises `TsonReadException`, and a schema that fails to
  resolve is not a read failure; the CLI's exit 1 against exit 70 turns on the distinction. They rethrow the
  original untouched.
- **The resolver catches inside its memoized `namespaceGetter`, not around the driving loop.** Resolution
  follows dependencies, not source order, so a failure usually happens inside a *nested* resolve; catching at
  the loop would attribute it to whichever declaration triggered it and then report the real one a second
  time. The memo makes it exactly once, against itself. Same shape as
  `TsonSchemaCompiler.Compilation.resolve` substituting an `ErrorReader` one phase later.
- **A failed declaration leaves an empty-record placeholder**, so its dependents still resolve. That is
  javac's error-type contract (it answers every question) rather than Swift's (every questioner must check
  first), and the choice is load-bearing: a `Sum`-bodied placeholder makes `parent => child & { ... }` fail
  *because* `child` did, reporting a consequence beside its cause. Swift's other half is kept — producing one
  means a diagnostic was already reported. It never escapes a reporting resolve, so it needs no `TypeKind`
  of its own.
- **`Tson.validateSchema(schemaText)` is the front door and owns the phase boundary** — the schema-side peer
  of `validate`, and the only caller that composes the two phases. Every declaration resolves before a
  verdict; linking runs only if resolution was clean, so a schema with a broken declaration *and* an
  unresolved reference reports the declaration alone (the reference may well resolve once the declaration
  does). This is where javac and Swift both draw it: javac attributes every entry before
  `shouldStopPolicyIfError` blocks the next phase, Swift never reaches SILGen after a Sema error. **A schema
  that reported anything is never registered.**
- **Only `TsonSchemaValidationException` becomes a diagnostic.** An `UnsupportedOperationException` is a
  library gap and keeps propagating — a gap is not a verdict on the author's schema. The test for which is
  which, from Swift's treatment of `expression_too_complex`: *a schema error's verdict doesn't change when
  this library improves; a gap's does.*
- **What still throws even with a receiver:** an `!!import` that won't load, a `!!meta` that may not
  govern, or a reference whose target owns a different `!!id` than it was fetched under (§2.2.1's
  cross-check, `TsonCompiledMetaRegistry.crossCheckId`). Those make the namespace itself unusable rather
  than one entry wrong, and continuing would report a page of unresolved references that are all
  consequences of the one real problem. Each is a `TsonSchemaValidationException` — an authoring or
  publishing error, not a library fault, which is what lets `Tson.validateSchema` catch them and report
  against RFC 6901's root pointer (`""`), since they concern the document rather than any declaration, and
  what keeps the CLI's exit 1 apart from exit 70.
- **Desugaring reports too, and needs no gate of its own.** `SchemaDesugarer.desugar` takes a
  `DesugarFailureReporter` — a `(Declaration, TsonSchemaValidationException)` callback rather than a receiver,
  keeping the diagnostics vocabulary out of a phase whose whole shape is AST-in/AST-out, and keeping
  `Diagnostic.ofSchemaError` construction in `SchemaResolver`, which alone holds the canonical id and the
  identity-keyed position table. It needs no phase boundary because it runs *inside* `resolveSchema`, so
  whatever it reports is already behind the gate the caller checks. A reported declaration is replaced with
  `ABSORBED` (a fresh zero-field record, the AST-level twin of `SchemaResolver.unresolved`) — **not passed
  through**, which would hand `DefinitionResolver` the very `ContainerTypeDef` the phase exists to remove and
  draw an `UnsupportedOperationException` the resolver deliberately doesn't catch, turning a reported author
  error into an unreported abort. Injected declarations are never rolled back: names are derived from the
  application, so §8.2's structural sharing means a later declaration may already reference one.
- **Still fail-fast:** parsing (issue #29) and compilation. Compilation already keeps going via `ErrorReader`,
  but that marks a *library gap* (an unregistered atom factory), which is a different question from an author
  error.
