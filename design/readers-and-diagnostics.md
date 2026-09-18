# Streaming readers and read context

Design notes for the compiled reader stack's read-time behavior — the pull cursor (`TsonReadContext`), the validation
rules every reader applies, the continuation policy, and what a read leaves behind. Current form only; history lives in
git.

**Invariants**

- The context holds no error policy: `report` hands a `Diagnostic` to the read's `DiagnosticsReceiver`, no reader
  branches on which, and a reader asks `reported()` (a count) to learn whether its children complained.
- `of(...)` is not a whole-document read; in `requireDocumentEnd` the pull past the root value, not the assertion after
  it, is the point.
- A stated FIXED value is checked, not obeyed, with the **pre-rebind** parser (`FixedCheck`); an omitted
  `OPTIONAL_FIXED` field stays absent while an omitted `REQUIRED_FIXED` one is injected.
- Collecting mode always keeps reading, a placeholder kept in place; bind mode is all-or-nothing (`ConstructionGuard`)
  while tree mode keeps everything it built.
- Reporting instead of throwing obliges a reader to skip (`EventSkip`), or the enclosing frame's next pull sees the
  value it declined.
- One `ValueIdentity` answers §7.5's duplicate rule, §5.2's FIXED check and §2.6's key identity: a `String` compares
  NFC, a `byte[]` as its octets.
- A map's count is validated in `MapAbstractReader.expectMapShape`, not in `readInto` — an empty brace never enters the
  entry loop.
- Records are closed under their type and a repeated field or map key is an error, on the schemaless path too.

Related: `design/reader-naming-and-schema-location.md`, `design/scope-push.md`, `design/record-dispatch.md`,
`design/name-hygiene-read-path.md`, `design/diagnostic-model.md`, `design/diagnostic-rules-and-messages.md`,
`design/processor-policy.md`, `design/schema-side-diagnostics.md`.

## Streaming readers and read context (`tson-compiler/.../reader/`)

Every reader in `reader` pulls `TsonEvent`s directly off a `TsonEventSource` via `TsonReadContext` — no
reader ever requires a materialized `DataValue` tree, so schema-validated reading and diagnostics can
begin before the whole document is parsed. (The schema pipeline itself is not streamed — a schema document
is small and parsed once.)

- **`TsonReadContext`** is the pull cursor: `peek()`/`next()` (over one shared `TsonEventSource`),
  `position()` derived live from the last event, `path()` (RFC 6901), `field(name)`/`index(i)` (push a
  path segment — a *node*, not a string, see
  `design/reader-naming-and-schema-location.md`), `at`/`withSchemaPosition`/`withPosition`,
  `report(code, message, expected, actual)`, `reported()`. One factory, `of(events, receiver)` (plus
  `throwing(events)` sugar), over one
  implementation. **The context holds no error policy**: `report` builds the `Diagnostic` from the path and
  positions it tracks and hands it to the read's **`DiagnosticsReceiver`**, which decides its fate —
  `throwing()` raises `ReadException` at the first problem, `collecting()` accumulates into a
  `DiagnosticsCollector`, and a caller's own `void report(Diagnostic)` can stream them anywhere. No
  reader branches on which. A reader needing to know whether its children complained asks `reported()` — a
  count, so it works for a receiver that keeps no list (the `int before = ctx.reported()` checkpoint idiom
  in `RecordBindReader`/`TupleBindReader`/`DataClassObjectReader`/`AnnotationCapture`).
    - **One event of lookahead, plus a rewind for the case that is not enough.**
      `TsonReadContext.lookingAhead(ctx, fn)` runs `fn` against the cursor and then puts back every event it
      consumed, so whatever reads next sees an untouched stream. `peek()` answers "what is here"; this
      answers "what is here *after the part that can repeat*". `*annotation [type-ref] core-value` puts the
      type-ref behind a run of any length, so everything keyed on that type-ref needs it: the facades
      selecting a root reader, and every dispatcher choosing a variant (`EventSkip.typeRefAhead` /
      `aheadOfValue`, `design/facades-and-tree.md`). Consumed events are replayed from a buffer, never
      re-lexed, so the cost is what was looked past rather than the document; `position()` is left where the
      lookahead reached, since a caller looks ahead in order to say something about what it found.
      Ordinary readers have no use for it — a reader knows its own shape from the schema.
- **`TsonReadContext` is deliberately still exported.** `TsonTypeReader.read(TsonReadContext)` is the sole
  abstract method a consumer receives from `TsonCompiledSchema.get`, so hiding the parameter type would
  make that method uncallable and the interface unimplementable from outside — categorically worse than the
  accepted `ValueReaderFactoryResolver` `-Xlint:exports` warning, where the hidden type is only ever *returned*.
  What keeps the exported surface narrow is that it carries no policy: whether a read fails fast and where its
  diagnostics go are the receiver's, so the context offers no accessor for either.
- **`of(...)` is not a whole-document read.** It assumes and performs no framing. Consuming the leading
  `DocumentStart`, and pulling *past* the root value so a lazy `TsonDataStream`'s root frame actually
  rejects trailing content, belong to `TsonTreeReader`/`TsonObjectReader`. That second half is easy to lose,
  because nothing fails when you simply stop reading — `requireDocumentEnd`'s Javadoc in both facades
  records that the pull, not the assertion after it, is the point.
- **A FIXED field's value comes from the schema, and a document that states it is checked, not obeyed**
  (§5.2). `RecordAbstractReader.verifyFixed` decodes the written token and compares it to the schema's
  value: a contradiction is `FIELD_FIXED`, and the field still resolves to the *schema's*
  value. Skipping it unread would let a document say one thing and decode to another in
  silence. The comparison uses a raw parsed value and the **pre-rebind** parser (`FixedCheck`), because bind
  mode narrows `precomputedValue` in place and comparing across that narrowing would flag every conforming
  document. **The two FIXED states differ in exactly one thing:** §5.2's injection rule names
  `REQUIRED_DEFAULT` and `REQUIRED_FIXED` and *not* `OPTIONAL_FIXED`, so an omitted `OPTIONAL_FIXED` field
  stays **absent** while an omitted `REQUIRED_FIXED` one is injected. Reading it the other way makes the two
  states indistinguishable and the `?` decide nothing; §5.2 says it outright ("**OPTIONAL and OPTIONAL_FIXED
  fields are never injected**"). `_` is a validation error at
  `REQUIRED_FIXED`, fine at `OPTIONAL_FIXED`; a `= _` field (`OPTIONAL_FIXED` with no value) admits only
  omission or `_`. Nothing is pre-seeded: every field the document didn't state goes through
  one `valueForAbsentField` switch over all five states.
- **An array element's own state is the two-member `ElementState`, and an absent element occupies its slot.**
  Under `[T?]` (`state: OPTIONAL`) an element may be the absent sentinel `_`; under the default `REQUIRED` one
  is `FIELD_REQUIRED`. Either way `ArrayAbstractReader` consumes the `AbsentEvent` and advances the index, so
  `[a _ c]` has three elements and satisfies a `[T?; 3]` size constraint — §5.3's own stated equivalence,
  which falls out of counting rather than being checked for. Elements have no default/fixed concept at all
  (`ElementState` has two members where a record field's `FieldState` has five), so none of the
  `valueForAbsentField` machinery above has an array counterpart.
- **A name's identity is its NFC form, and normalising happens where a token becomes a name.** §2.5 and
  §2.6 define field-name and scalar-key identity by NFC-normalised text, whichever spelling produced it, so
  `café` precomposed and decomposed are one name, and §7.2.1 mandates it directly — "quoted tokens that
  occupy identifier positions … are NFC-normalised by the resolver before identity comparison. String-typed
  positions are not normalised." The axis is the **position**, not the quoting, and the lexer cannot draw it
  because at lex time a quoted token's position is not yet known. So it lives at the two places
  `TsonDataStream` emits a `FieldName` — one tier earlier than the "resolver layer" §7.2.1 names, which
  satisfies the requirement and makes the AST carry the normalised name. That is one chokepoint for all three record
  readers, and it means a name is stored in the form its identity is defined by, so an ordinary
  `record.get("café")` finds a decomposed one. A map **key** is a value and keeps its written content; only
  its comparison normalises (`ValueIdentity`), which is §2.6's "textual identity is the parser's minimum" read
  against a processor that decodes.
- **One equality contract answers all three rules that compare two decoded values** (`ValueIdentity`): §7.5's
  duplicate rule, §5.2's check of a stated FIXED value against its declared one, and §2.6's key identity.
  Each of those delegates to "the element type's equality contract" and none of them defines it, so a
  comparison stated at each call site is three opinions — a key that normalises beside a set element that does
  not, and `bytes` with no comparison at all, `byte[]` carrying Java's identity equality. That last one is the
  shape worth remembering: an absent comparison is usually a missing verdict and there it is an **inverted**
  one, a FIXED `bytes` field rejecting the only document it can accept. A `String` compares NFC, a `byte[]` as its octets,
  and a tree atom by its normalised value **beside its type-ref and annotations** — stripping those is §2.6's
  rule for a key, where the schema fixes the key type, and would merge `!cm 5` with `!inch 5` if it reached a
  set. **A `datetime` and a `time` compare as instants**, §5.5 making the mandatory offset a spelling: an
  `OffsetDateTime` reduces to its instant and an `OffsetTime` to its time of day in UTC, so
  `2026-01-01T10:00:00+01:00` and `2026-01-01T09:00:00Z` are one value and `23:30:00-02:00` is `01:30:00Z`.
  Java's own `equals` compares the offset on both, a narrower relation than the value space, which is why none
  of the three rules may use it; ordering needs nothing, `compareTo` already comparing the instant. §5.5 has
  TSON text preserve the offset as written, so this is an identity and never what a reader hands back.
  `Rendered` is the other half: `byte[]` inherits `Object.toString`, so a diagnostic naming one renders it
  through `Rendered` rather than as `[B@6d06d69c`.
- **A map entry's value may be `_` where the schema said so, and the entry counts either way.** `MapBody`
  carries an `ElementState` governing the value — `{K => V?}`, §5.3's own row and the `state` field the
  kernel gives `map` — so `MapAbstractReader.decodedValue` gives the array element's
  two answers: the sentinel under `OPTIONAL`, `FIELD_REQUIRED` under the default `REQUIRED`. It answers
  above the value's own reader, which is right to refuse the sentinel (`_` is a value of no atom type) —
  absence is the container's question, the same place `ArrayAbstractReader` asks it. The entry is present
  with an absent value (§2.9) whichever answer it gets, so it counts toward `min_items`/`max_items` and the
  refusal costs the value its verdict, not the entry its place; both subclasses already had the no-value
  form to put there — a `TsonAbsent` in tree mode, a `null` the bound `Map` really holds in bind mode. The
  **key** is the opposite and unconditional: §2.9 forbids the sentinel there whatever a declaration says,
  and the parser refuses a `?` on that side for the same reason. **The schemaless reader enforces it too**,
  which is where the rule most needs enforcing: §2.9 is a Part 1 rule, so Class 1 data is exactly the case
  it governs, and the map-entry production accepts any data-value in key position — no tier below the reader
  can refuse one. The tree read reports and keeps the entry (tree mode keeps what it built) and leaves the
  key out of the duplicate set, a second `_` being this same problem again rather than a repeat of a key the
  document meaningfully stated. §7.6's table states the same rule from the data side: a map entry value is
  `_` only when the map type's value state is OPTIONAL, and the entry counts toward the size bounds.
- **Continuation policy: always keep reading in collecting mode.** A failed field/element is recorded and
  a placeholder kept in place (so later indices stay accurate) — Java `null` in bind mode, `TsonAbsent` in
  tree mode, where the diagnostic, not the node, carries what went wrong; a shape mismatch reports
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
  `MapAbstractReader`/`DataClassObjectReader` asking whether one key bound, `verifyFixed` asking whether one
  token decoded, `AnnotationCapture`'s throwaway probe context — each brackets a single child read.
- **A family binds the target its component wants, where the reader is built** (`AtomType.boundTo`, reached
  through `AtomTypeReader.overAtom` from `RecordBindReader`'s field loop — the same seam a `value` slot's own
  specialisation takes). The answer is a reader for that class or nothing at all, so a read carries no target
  and a component the family cannot fill is a `BindMismatchException` at compile rather than a cast failing in
  a constructor. **Every family answers and there is no default**: one that admitted whatever it was handed
  would give the check nothing to work with, so the vocabulary states its targets and the check is definite
  across all of it. The three answers are `natural` (the value reaches the target unchanged), `asWrittenText`
  (a wire form that is text, so a component may keep the spelling the family validated — the `uri` rule, which
  every string-content family shares) and `bound`/`converting` (the reading is converted; the numeric families,
  which narrow from the token's text). A numeric family offers no text target: a number's wire form is a
  number, so its spelling would be a different reading rather than the same value. Two positions are excluded
  because something already specialised them — a `value` slot, whose atom is chosen from that same class, and a
  form-sensitive one (`Token`), where handing the family decoded text would lose the spelling that was the
  point of claiming it.
- **A bound component's own bridge is applied where the field is wired, not where the value is read**
  (`ElementBridging.wrap`, from `RecordBindReader`'s field loop and from the array and map readers). A
  schema-driven read is exactly the path that does not go through `tson-bind`'s binder, which is what
  applies a bridge as it collects a record's constructor arguments — `RecordBindReader` fills its own
  argument array from the compiled field readers, and a collection appends elements through an access
  bridge that converts nothing. So both wrap, and a consumer's `registerAtom(Money.class, bridge)` or
  `@Transparent` wrapper binds the same under a schema as without one. The wrapper is identity where the
  target carries no bridge, so both callers wrap unconditionally. **What is still not checked is the
  component that carries no bridge and cannot meet its family** — that is knowable at compile and belongs
  with the field-set agreement check; `BACKLOG.md` carries it, `AtomBoundClassUnderSchemaTest` states the
  boundary.
- **Every reader stamps its own schema position** first thing (`ctx.at(value).withSchemaPosition(...)`) so
  a diagnostic from inside an atom carries *that atom's* declared position. A record field never mentioned
  by the data can only be noticed after the record is consumed, so its `FIELD_REQUIRED` reports against
  the record's *opening* position (captured up front) via `withPosition`, not the live cursor.
- **Records are closed under their type** ([TSON-SCHEMA] §7.2, `RecordAbstractReader.readFields`): a field
  name the type doesn't declare is `UNRECOGNIZED_FIELD`, reported and then skipped, so a collecting pass
  finds every stray name and the value still comes back whole. The diagnostic carries the type's real field
  names in schema order (message *and* `expected`) — the information that turns a retry into a one-shot
  fix. **Not configurable under a schema**: §7.2 makes closure a MUST wherever a schema is in scope, so
  `ignoringUnknownFields()` relaxes the schemaless bind path (next bullet) and nothing here. **The same rule polices schema
  authoring**, through the same line: a constructor body is bound by replaying it through the governing meta's compiled
  reader, so `!integer ^ { minimum: 1 }` (JSON Schema's spelling of `min`) is rejected instead of compiling clean and
  constraining nothing — §7.2's "a constructor is a record-shaped type, so it validates a record against its constraint-field
  vocabulary". In bind mode a reported record still binds to `null` — the all-or-nothing rule above, not something closure
  chose.
- **Records are closed on the schemaless bind path too** (`UNRECOGNIZED_FIELD`), where the target class is the schema
  and a field it does not declare is reported rather than dropped, a later version's extra field being able to change
  what the fields a class does read mean; `ignoringUnknownFields()` is the derived opt-out on both encodings' readers.
- **A repeated record field name or map key is an error** (`DUPLICATE_FIELD`/`DUPLICATE_MAP_KEY`,
  §2.5/§2.6), reported at the repeat's own position, with the spec's "last value wins" recovery still
  running underneath: a single-pass pull stream can't know a name recurs without buffering, so every
  occurrence is decoded (hence validated) and a later one overwrites an earlier. [TSON-DATA] §2.5/§2.6 make
  both MUST NOT, with the diagnostic at the repeated occurrence, which leaves no shadowed-occurrence
  question — the repeat *is* the error, so whether its value was going to be used decides nothing.
  **The same rules hold on the schemaless path** (`SchemalessTreeReader`/
  `DataClassObjectReader`), these being Part 1 rules a document violates with or without a schema; a
  verdict that turned on whether a schema was in scope would be the interoperability failure §2.5's MUST
  NOT exists to prevent. In the schemaless object reader the seen-set is keyed on the *written* name, not the
  target-class slot, so a repeat of a name the class doesn't declare still counts.
- **A map key's identity is its structure and decoded values, with type-ref and annotations stripped** —
  §7.7's host-value equality, applied at every layer that decodes rather than only where a schema is in
  scope. So `0xFF` and `255` are one key, and `!person a`/`a` are one key. **This is a deliberate
  divergence from §2.6**, which defines key identity *textually* at the parser layer (`Alice`/`"Alice"`
  are duplicates, `1`/`1.0` are not) and leaves typed equality to §7.7's MAY — the series names no
  equality for the Class 1 *reader* in between, which has run §4 base resolution but has no declared
  types. §2.6 names that layer itself — "a processor that decodes values compares decoded values" — so a
  key realised as a host value is one key, which is what the host `Map` will do with it anyway.
  `SchemalessTreeReader.keyIdentity`
  does the stripping explicitly; the other two readers compare bound host values, which strips both by
  construction.
- **A written `_` at an `OPTIONAL` field is present with an absent value** (§2.9: "distinct from not
  appearing at all"), and tree mode keeps that: `{ x: _ }` reads with `x` a `TsonAbsent` where `{ }` reads
  with no `x` at all, and `TsonTreeWriter` writes the first back as `_`. It is the mode's own answer
  (`statedAbsentValue`, per subclass) because bind mode has nowhere to put it — a Java component has no third
  state between "set to nothing" and "never set", so both readings arrive as `null` there. A limit of the
  target rather than a reading of §2.9, and the reason the tree's answer is not aligned down to it. An array
  element and a tuple slot keep the same distinction, so the containers agree.
- **A written `_` at a `REQUIRED_DEFAULT` field is an error**, where plain omission still injects the
  default silently (`valueForStatedAbsentField` against `valueForAbsentField`). §5.2 makes an explicit `_` a
  validation error at every REQUIRED-family field — "`_` asserts absence at a position the schema always
  fills; at REQUIRED_DEFAULT the fix is to omit the field" — which is §7.6's table read down its own column.
  The default is still what the field decodes to — only the verdict changes, the same split `verifyFixed`
  makes for a contradicted FIXED value.
- **`{}` is the empty container of the position's own type, size rules included.** [TSON-DATA] §2.8 defers
  an empty brace to the resolver and resolves it to "the empty container of that type" once a schema
  supplies one, so at a map position it is a map with zero entries and `min_items: 1` rejects it. The count
  is validated in `MapAbstractReader.expectMapShape`, the one funnel every map reader passes through, and
  deliberately **not** in `readInto` — an empty brace never enters the entry loop, so a check placed there
  would miss `min_items` while `max_items` on the same declaration reported correctly. A record position
  reports each missing required field for an empty brace and an array's `[]` is an ordinary empty element
  list, so all three positions agree.
- **A reader names itself by what the author wrote, never by a content-derived entry name**, and as the *position*
  wrote it — `EntryDisplayName` and `UseSite`, both running where a reader is built, so neither costs a read anything.
  `design/reader-naming-and-schema-location.md` has both.
- **`EventSkip`** is the shared grammar-aware "consume and discard" utility (leading annotations + an
  optional type-ref as every reader's first step; a whole value; one core-value on a shape mismatch, to
  keep the stream correctly positioned). **Reporting instead of throwing obliges a reader to skip**: the
  fail-fast receiver never returns, so a reader that reports and yields nothing without consuming is correct
  only until a collecting receiver is handed the same document, at which point the value it declined is still
  pending and the enclosing frame's next pull sees it. At the document boundary that pull is
  `requireDocumentEnd`, whose belt-and-braces `IllegalStateException` then fires on ordinary caller input —
  which is how `DataClassObjectReader`'s unbindable-target report (a target class `tson-bind` cannot produce
  a descriptor for) reached a caller as an internal-invariant exception with the diagnostics they asked for
  lost inside it. Where the skip goes is a per-caller question, not `descriptorFor`'s: `read` has taken
  nothing and skips a whole `dataValue`, while `bindUnion` has already consumed the framing to find the
  member and skips the core-value alone. **`ListEventSource`** replays a pre-built event list — used for a
  schema default (`readSchemaDefault` wraps a literal `Token` as one synthetic event) and, via
  `DataValueEvents`, for replaying an already-resolved `DataValue` tree through a compiled reader (the one
  place `resolver` still has a `DataValue` in hand).

## What a read leaves behind: nothing

A read is transient by construction — the compiled schema, the reader graph and the bind descriptors are
built once and meant to live for the process, and a read holds no cache of its own. `AllocationHarnessTest`
(`tson/src/test/.../perf/`, `./gradlew :tson:allocationReport`) makes that a checked fact rather than a
belief, over the bind path:

- **Retention is 0 bytes per read**, measured as settled heap across 20,000 reads of one schema through one
  long-lived reader, and again across 10,000 reads through a reader *derived per read* (the shape a server
  writes). A cache keyed per document — the classic accidental leak, and the one thing that would make this
  design's "resolve at startup" advice a lie — shows up here and nowhere else in the test suite.
- **Every read result is collectable**, asserted with weak references rather than a measurement, so the
  answer carries no noise: 500 bound objects, all cleared once the collector has demonstrably run.
- **Transient bytes are reported per read**, with a ceiling that only a gross regression trips.
  `whereAReadsBytesGo` splits one read into event stream / schemaless tree / schema tree / bind, which is
  what turns "allocation went up" into "which stage". The lexer decodes UTF-8 from the `ByteSource` itself, so no
  JDK decoder sits on the read path, and a resident source is indexed with no block allocated at all.
