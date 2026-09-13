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
  path segment — a *node*, not a string, see below), `at`/`withSchemaPosition`/`withPosition`,
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
      `aheadOfValue`, `docs/facades-and-tree.md`). Consumed events are replayed from a buffer, never
      re-lexed, so the cost is what was looked past rather than the document; `position()` is left where the
      lookahead reached, since a caller looks ahead in order to say something about what it found.
      Ordinary readers have no use for it — a reader knows its own shape from the schema.
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
  value: a contradiction is `FIELD_FIXED`, and the field still resolves to the *schema's*
  value. Skipping it unread — the old behaviour — let a document say one thing and decode to another in
  silence. The comparison uses a raw parsed value and the **pre-rebind** parser (`FixedCheck`), because bind
  mode narrows `precomputedValue` in place and comparing across that narrowing would flag every conforming
  document. **The two FIXED states differ in exactly one thing:** §5.2's injection rule names
  `REQUIRED_DEFAULT` and `REQUIRED_FIXED` and *not* `OPTIONAL_FIXED`, so an omitted `OPTIONAL_FIXED` field
  stays **absent** while an omitted `REQUIRED_FIXED` one is injected. Reading it the other way makes the two
  states indistinguishable and the `?` decide nothing; §5.2 says it outright ("**OPTIONAL and OPTIONAL_FIXED
  fields are never injected**"). `_` is a validation error at
  `REQUIRED_FIXED`, fine at `OPTIONAL_FIXED`; a `= _` field (`OPTIONAL_FIXED` with no value) admits only
  omission or `_`. There is no pre-seeding pass any more: every field the document didn't state goes through
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
  Each of those delegates to "the element type's equality contract" and none of them defines it, so with the
  comparison at each call site the three disagreed — a key normalised and a set element did not, and `bytes`
  had no comparison at all, `byte[]` carrying Java's identity equality. That last one is the shape worth
  remembering: an absent comparison is usually a missing verdict and here it was an **inverted** one, a FIXED
  `bytes` field rejecting the only document it can accept. A `String` compares NFC, a `byte[]` as its octets,
  and a tree atom by its normalised value **beside its type-ref and annotations** — stripping those is §2.6's
  rule for a key, where the schema fixes the key type, and would merge `!cm 5` with `!inch 5` if it reached a
  set. **A `datetime` and a `time` compare as instants**, §5.5 making the mandatory offset a spelling: an
  `OffsetDateTime` reduces to its instant and an `OffsetTime` to its time of day in UTC, so
  `2026-01-01T10:00:00+01:00` and `2026-01-01T09:00:00Z` are one value and `23:30:00-02:00` is `01:30:00Z`.
  Java's own `equals` compares the offset on both, a narrower relation than the value space, so all three
  rules were wrong here at once; ordering needed nothing, `compareTo` already comparing the instant. §5.5 has
  TSON text preserve the offset as written, so this is an identity and never what a reader hands back.
  `Rendered` is the other half: `byte[]` inherits `Object.toString`, so a diagnostic naming one said
  `[B@6d06d69c` until these comparisons could reach a value at all.
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
  document meaningfully stated.
  Note that §7.6 still describes the permission as *not* schema-conditional, and §5.3 still says neither
  side admits a `?`. Those two contradicted each other, which is what #12 is about; this is the reading that
  makes them consistent, built ahead of the revision that would state it.
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
  fix. **Not configurable**: §7.2 makes closure a MUST wherever a schema is in scope and exempts only
  schemaless records, which are read by `DataClassObjectReader`/`SchemalessTreeReader` and never reach
  this code. **The same rule polices schema authoring**, through the same line: a constructor body is bound
  by replaying it through the governing meta's compiled reader, so `!integer ^ { minimum: 1 }` (JSON
  Schema's spelling of `min`) is rejected instead of compiling clean and constraining nothing — §7.2's "a
  constructor is a record-shaped type, so it validates a record against its constraint-field vocabulary". In bind
  mode a reported record still binds to `null` — the all-or-nothing rule above, not something closure chose.
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
  types. §2.6 now names that layer itself — "a processor that decodes values compares decoded values" — so a
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
  element and a tuple slot already kept the distinction; the record was the one container of the four that
  dropped it.
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
  deliberately **not** in `readInto` — an empty brace never enters the entry loop, which is exactly how the
  rule went missing while `max_items` on the same declaration reported correctly. The record position was
  never affected (an empty brace there reports each missing required field) and an array's `[]` is an
  ordinary empty element list, so this closed the one position where the three disagreed.
- **A reader names itself by what the author wrote, not by its entry name** (`EntryDisplayName`, threaded to
  every reader that puts its own name in a message as a `displayName` beside `name`). Sugar lifts each form
  to an entry of its own and every §5.10 application materialises one, both named content-derived (§8.2), so
  the entry name for `[order; 1..]` is `array_order_1_e9777a39` — a string in neither the author's file nor
  the spec, and now travelling to readers who cannot open the schema at all. **A missing source position is
  what tells the two apart**, exactly rather than by guessing at the name's shape: a parsed declaration
  carries its own name token's position and a minted entry has none. So `tag_list => [text; 1..2]` keeps
  `tag_list` and the anonymous form inside it renders as `[order; 1..]`, a map as `{text => order}`, a tuple
  as `[text, int32]`, a choice as `(text | int32)`, and an instantiation entry as the application its
  `source` records (`paged<order>`). Anything with no sugar spelling falls back to the entry name — honest
  rather than invented.
- **And it names itself as the *position* wrote it, not as the entry it resolved to** (`UseSite`). A field
  declared `c: pct` over `pct => small` reads with `small`'s reader, that being the type at the end of the
  chain, and a message from it would otherwise name a declaration the author did write but did not write
  *here*. §8.3 makes a reference a hop and forbids collapsing it in resolved output, so a position reaches its
  child by resolving the name it names and nothing more — no carrier on the type-ref, and nothing added to
  `TsonReadContext`. The renaming happens one level up instead: the `REFERENCE` entry's own compile names its
  target's reader for the entry doing the referring (`TsonSchemaCompiler`, through `UseSite.named`), which is
  the collapse §8.3 permits *after linking, when a processor compiles for reading*. It runs where a composite
  reader **wires its children**, which is compile time: a position naming an alias gets a copy differing only
  in its name, every other position gets the shared reader back unchanged and allocates nothing
  (`AllocationHarnessTest` is the guard).
    - **A materialised application is named the same way**, and it is why the mechanism sits on the entry
      rather than on the position. That entry's body is a `Reference`, so it compiles to its target's reader —
      and `TsonSchemaCompiler` names it for the entry doing the referring, whose `source` is the application
      (`b<10>`). Without it a violation against `b<10>` reads `'integer_type_10_100_786fbcfb': …`, the one
      shape that made `EntryDisplayName`'s fallback reachable.
    - **A choice keeps naming the variant**, deliberately: it dispatches by name inside `read`
      (`VariantSchemaReader`/`NamedDispatchReader`/`VariantBindReader`), so renaming there would allocate per
      read — and the variant that rejected the value is the informative name anyway.
  - **`displayName` is beside `name`, never instead of it.** The entry name is what a type-ref resolves
    against (`VariantSchemaReader` dispatches on it) and what a tree node carries as its own `typeRef`, so
    substituting the display name would break dispatch and round-tripping alike.
  - **The pointer roots at the name the read entered through**, which is a different mechanism for the same
    principle. A compiled reader is shared by every name that reaches it — `order_response => paged<order>`
    compiles to the instantiation entry's own reader — so the root cannot come from the reader and comes
    from the facade, which seeds `ctx.underDeclaration(compiled.rootDeclaration(name))` before the read.
    `inRecord` then keeps that pointer and re-anchors only identity and line, which is the interaction those
    two methods were already written for; a non-alias root seeds exactly what the reader would have
    established, so nothing else changes.
  - **A declaration with no line of its own contributes none** (`SchemaLocation.anchoredOn`), leaving
    whatever the descent had established rather than replacing it with an absence. Entries without a line
    are exactly those nobody wrote, and taking their absence answered "which line do I open" with nothing
    for a document whose author has a perfectly good line: the alias they wrote, or the record whose field
    the application sits at. That half is independent of the seed — a template application at a *field*
    already had the right pointer and was still losing its position.
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

### The cursor's position is wrapped when it is asked for, not when it is set

`peek`/`next` record the event's own `SourcePosition` in the cursor as it is; `position()` wraps it in an
`Optional` on the way out. The reverse — wrapping on every pull — cost an allocation per event for a value
the event already carries and that only `report` ever reads, which measured 2.6 KB of a ~23 KB read. The
same shape as the pointers below: build the object where the diagnostic is built.

What it does *not* do is remove the `Position` from the event, which is the structural version of the same
question and a much larger change: every event holds one, so the sources (`TsonDataStream`'s queue, the
rewind buffer, `ListEventSource`'s replayed lists) would all have to carry line/column/offset alongside the
event instead — which is to say `TsonEventSource` becomes a cursor with accessors rather than a producer of
objects. Worth roughly another 1 KB here and much more in a port where an object is not a pointer bump; see
the porting notes.

### Both pointers are built when a diagnostic is, not while descending

A step of the descent is one `PathStep` node linked to the step before it, and the RFC 6901 pointers are
rendered from that chain only when `report` (or a caller) asks. Concatenating each step onto the last —
what this did — is **quadratic in depth**: every level copies the whole prefix again, and a read that
reports nothing throws all of it away, which is every read of a valid document. `schemaToo` on each step is
what keeps the two pointers apart in one chain: every schema step is a data step, but an array index moves
through the document without moving through the schema, whose element type is declared once for the array.

The schema end keeps its identity and line beside the chain rather than in it, because a re-anchoring
record replaces those while the pointer keeps growing — `inRecord`/`underDeclaration` set them, the chain
does not. `SchemaLocation` is unchanged and is still what `schemaLocation()` hands back; it is simply built
on demand instead of once per field.

`AllocationHarnessTest.nestingCostsTheSameAtEveryDepth` pins the shape rather than a byte count: it prices
a level of nesting in a shallow part of a document and in a deep one and requires the two to agree. The
per-level structural cost (events, tokens, a node, a context) is flat and large enough to hide the pointer
in an absolute measurement — eager building shows up as the *deep* level costing ~200 bytes more than the
shallow one, and more at greater depth.

### The scope push ([TSON-SCHEMA] §7.8): `ScopedReader`, and `ScopePush` deciding who may open one

meta.tn's `scoped` constructor is the open sum: the value names its own type, and the instance's `scope`
names the namespaces that name may be drawn from (`LOCAL`, the governing schema and its imports; `EXTERN`, a
foreign schema the value names for itself). `schemas` narrows the foreign side. Core's three named instances
— `declared`, `extern`, `dynamic` — are the three subsets that have a name, and `extern_of<S>`/
`extern_type<S, T>` are per-use narrowings a schema applies rather than declares. **One reader serves all
five**, because what separates them is two constraint values and not a shape.

**The value's own shape picks the cell.** A value carrying a nested `!!schema` is EXTERN; one carrying a
bare type-ref is LOCAL; one carrying neither names no type, and a position that is open has nothing to infer
one from — a **validation** error, not a resolver one, and §7.8 says so outright of the extern cell ("the
discriminant is required"), the same holding at the other. A cell the instance's `scope` does not hold
refuses the value it would have taken, which is how `declared` refuses a push and `extern` requires one from
the one reader rather than from three.

**LOCAL is fixed at compile time and EXTERN is not.** A `scoped` entry belongs to exactly one schema, so
"the governing namespace" is that schema's and resolves through the same `TsonTypeReaderResolver` every other
dispatch uses. Which foreign schema an EXTERN value names is the document's choice, so it is looked up as the
value arrives, through `ForeignSchemas` — a read registry hands its own `get` to every compile it performs,
so a pushed schema shares that cache, that loader and that read mode with the schema that admitted it. A
schema nothing would supply is one of the five `SCHEMA_*` codes and never a verdict: it was never read.

**There is no scope stack, because the scope pops by returning.** The reader for the foreign type *is* the
foreign schema's compiled reader, wired to that schema's entries, so everything below the pushed value
resolves there by construction and everything after it resolves in the governing namespace again. Nothing
tracks a current scope, and nothing can leak one.

**Who may open a scope is `ScopePush`, and it is one decision rather than four.** `TsonDataStream` emits a
`SchemaRef` ahead of a record field value, a map entry value and an array element — the three positions
[TSON-DATA] §2.3 admits a directive at — and each of those containers used to consume it and throw it away,
which is how a document could push a scope its schema never opted into and be read as though it had not. They
now leave the event where it stands and ask `ScopePush.notAdmitted` on behalf of the position's own reader:
a scoped one keeps it, and everything else has it consumed and refused (§7.8's typed-position restriction —
"cross-schema acceptance is authored intent, not accident"). The check costs a document nothing, everything
in it being guarded by "is the next event a directive at all", which for every value in almost every document
it is not. The refusal is reported against the position's own context rather than the container's, so it
names the field or the index; `notAdmitted` and `refuse` are split for exactly that reason, so a scoped copy
of the context is built only where there is something to locate.

**A schemaless document opens no scope at all** (`ScopePush.refuseSchemaless`), which is §7.8's own rule: "a
nested `!!schema` in a document with no `!!schema` of its own is a validation error naming the directive". The
typed-position restriction exists because acceptance is authored intent, and a
Class 1 document states no intent to opt in to anything; honouring the directive there turns a Class 1 read
into a Class 2 read halfway down a document with nothing on the document saying so. Both refusals report and
keep reading — the directive is consumed and the value read as it would have been without one — so a stray
directive costs one diagnostic rather than a value.

**Tree mode keeps the push and bind mode cannot.** `scoped` is where a document says which schema a value
belongs to, and a tree that dropped it could not be written back, so tree mode wraps an EXTERN value in a
`TsonScopedValue`. A bound object has nowhere to carry a URI and inventing somewhere would change what a
consumer's own class means, so bind mode hands the object back as it is — the same asymmetry `TsonAbsent`
already makes for [TSON-DATA] §2.9.

### What a read leaves behind: nothing

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
  what turns "allocation went up" into "which stage". Today the token stream is over half of a small
  document's cost and most of *that* is one `InputStreamReader` per read — fixed cost, unrelated to
  document size, tracked in `BACKLOG.md`.

## A record position gets the reader its extension fact earns (`RecordDispatch`)

`record.extension` ([TSON-SCHEMA] §5.2) decides how a position typed by a record is read, and it is decided
**once, when the schema compiles** — `RecordDispatch.over` decorates whichever mode's record factory is in
play, so ABSTRACT and SEALED positions never reach it.

- **OPEN and FINAL** are the mode's own reader, unchanged. They read identically; the difference is which
  names a tag may carry, and a FINAL record's subtype set is empty by construction rather than by a check.
- **ABSTRACT** is `RecordTagDispatchReader`: the `!name` annotation is the only selector and is required, and
  the failure lands before the record's shape is consulted.
- **SEALED** is `RecordMemberDispatchReader`: the discriminator fields are read and the value looked up.

**One reader for both modes**, on `ChoiceReader`'s reasoning — dispatch reads the type-ref without consuming
it, so the member's own reader takes the whole data-value and does with it whatever that mode does everywhere
else. Neither dispatcher carries a field list, a group list or a default table, because neither decodes a
field.

**Named for the dispatch rather than the member**, because `*AbstractReader` is already this package's name
for the shared base of a kind's two mode readers, four classes deep; `*DispatchReader` is the established
name for a reader that resolves another and hands the value on (`NamedDispatchReader`).

**Both are `Subsumption.Applied`, and that is load-bearing.** `Subsumption.guard` wraps every Atom or Product
reader in a `VariantSchemaReader` so §7.2 reaches every position — and wrapping a dispatcher puts a second
dispatcher in front of it, which wins. A sealed position read through the guard takes `!cat` and hands the
value to `cat` before the members are consulted; the marker is what stops it. The family's rule is *stricter*
than §7.2 in any case: a sibling's tag is admissible under §7.2 and still wrong, the members having already
said which member this is.

**Both compare a written tag against flattened names** (`Subsumption.admitting`), §7.2 comparing "after
reference flattening of both": the base's own set, which decides the `tagNamesTheBase` refusal, and each
member's, which decides selection. Skipping it is not a lost nicety but a family nothing can name — a family
whose base is a **template** has a minted entry for the base and for every member, and §8.2 makes a minted
name non-normative, so an alias is the only spelling either end has. It was skipped: the concrete record
readers were flattened and the two dispatchers were not, so a subtype-template family read in JSON and was
refused in TSON text — the same schema, the same document, two answers. `CrossEncodingParityTest` carries both
ends of it now.

**`NamedDispatchReader` is not reused**, close as the shape is. Its verdicts are a choice's, and
[TSON-JSON] §9.4 binds a family's to the ones the JSON stack gives — which is what `base.diagnostics`'
`RecordExtensionDiagnostics` holds, and what `CrossEncodingParityTest` compares.

**§7.2's own refusal is shared on the same terms** (`base.diagnostics`' `SubsumptionDiagnostics`), and it had
to be: the two encodings were giving one rule two codes — `UNKNOWN_TYPE_REF` here against `TYPE_MISMATCH` in
JSON — which put it in two of §8.1's categories, `resolver` against `validation`, for one verdict. There was
no parity case for it, and that absence is how they drifted. It sits beside `RecordDiagnostics` rather than
inside it because the rule governs every atom and product position — an array, a map and a tuple refuse a
wrong annotation on these same terms. **The refusal is located at the value, not at the tag**: TSON's
annotation has no pointer step of its own, and §9.4 wants one pointer for a rule they share, so JSON reports
at the value too.

**Which is one instance of a line that now runs through the whole vocabulary.** `UNKNOWN_TYPE_REF` means the
written name *denotes nothing* — the schemaless reader's own check (`TypeRefCheck`) and an annotation naming
no type the governing schema declares (`AnnotationCapture`), which are the only three sites left. Everything
where a name resolves and is merely not admissible is `TYPE_MISMATCH`: §7.2 subsumption, a choice's variant
membership, a union's member test, a type-ref naming a template. So is a position where a **required**
selector is absent, since no type is established either way — which is what the family readers already gave
`tagRequired`, and what makes "a required tag is missing" need no member of its own. The distinction is what
a consumer routes on: one says *correct the name*, the other says *this name means nothing here*. It also
decides §8.1's category, `validation` against `resolver`, so getting it wrong misfiles the verdict as well as
misnaming it — which is exactly what had happened, twice, in two different pairs of readers that each agreed
internally.

**The pin table is derived at construction**, keyed by what the pins compare as (`ValueIdentity`), and both
sides go through one parser: a schema pinning `= "dog"` matches an unquoted `dog`, and `= 0xFF` matches `255`.
The scan for the selectors is a `lookingAhead` and rewinds, because a record's fields have no significant
order — the selector may arrive after the fields it selects.

## Name hygiene on the read path ([TSON-DATA] §8.2)

**An unbindable target class is `BIND_MISMATCH`, not `SCHEMA_ERROR`.** A class `tson-bind` cannot analyse
is a misconfiguration in the reading application and says nothing about the document — the distinction
`Code.verdict()` exists to carry, and the same line `TsonBindMismatchException` draws at compile time.
Reporting it as `SCHEMA_ERROR` told a caller routing on the answer that the document was wrong when nothing
had looked at it. Both encodings' class-driven readers report it the same way.

**The token policy is the stream's, not the context's.** Both `TsonDataStream` and `JsonStream` apply it as
an event leaves them; neither read context takes one, and `TsonReadContext.of` no longer has a parameter for
it. The reason is unchanged from when this was a decorator: a context **rewinds** — an event consumed during
lookahead is delivered again, and a probe context can be built over events already seen — so a check there
reports one token once per lookahead that crossed it, where a stream produces each token exactly once. It
stopped being a decorator when the JSON stream needed the same rule: a wrapper is a second place to forget to
apply it, and two encodings with one rule should not have two mechanisms. At `unrestricted()` — the default,
and every ordinary read — the check is a field read and a branch.

**A refused name draws no verdict beside its refusal.** Name hygiene runs inside `ctx.next()`, so a name
§8.2 refused is reported before the reader has looked it up — and then the reader does not look it up:
`RecordAbstractReader.readFields` and `DataClassObjectReader.bindRecord` both checkpoint `ctx.reported()`
across that one pull and skip their `UNRECOGNIZED_FIELD` when the delta is non-zero. Only one event is
consumed between the two reads and nothing but the hygiene check reports during it, so the delta is exactly
"this name was refused".

The reason is not tidiness. A homoglyph of a declared name previously drew both the refusal *and* "unknown
field 'pаssword' — the type declares (password)", which instructs the sender to add a field that is already
there when the fix is one character. **A refused name was never read, so nothing downstream can hold a
verdict about it** — reporting it unrecognised claims to have looked it up, which the processor declined to
do. Both readers apply the rule because a document's verdict must not depend on which one read it.

Two consequences, both deliberate. A name that is refused *and* genuinely undeclared yields only the
refusal; the sender fixes the character and learns on the next round whether the field exists. And a
`FIELD_REQUIRED` for the field the homoglyph was reaching for still stands, because it is true and useful —
the pair reads coherently where the suppressed one contradicted it.

**The look-alike rule is not reached by this** and is left alone: `CONFUSABLE_NAMES` is a property of a
*set*, asked of a record's field names after the record is read, so there is no `next()` to checkpoint
around — and it produces no comparable misinstruction.

A Class 1 document carries two names — a type-ref name and an annotation name, the positions §7.4 marks
`identifier` — and §8.2's restricted-character and restricted-script rules apply to both, **on by default**. They
run in
`DefaultTsonReadContext` as the name's event is first pulled, that being the first point on the read path
holding a diagnostics receiver: `TsonDataStream` throws and holds none, so a check there could only say
"invalid", which is the one thing a refusal is not. What stays in the stream is §7.7's grammar, where a
failure really is a parse error.

**Only on a freshly pulled event.** `lookingAhead` rewinds what it consumed and a reader replays it, and
`AnnotationCapture` builds a probe context over events already seen — so checking every event would report
one name once per lookahead that crossed it. `NameHygieneTest` counts every shape an annotation takes,
because that is the failure that would survive every other test.

**Not in `TsonDataStream`**, which is where the *token* surface's policy runs and gets exactly-once
for free by sitting upstream of the rewind. That decorator skips itself entirely at its default —
`unrestricted()`, which is every ordinary read — so a rule §8.2 defaults *on* cannot live behind it
without making the wrapper unconditional and putting a switch per token back into the cost of a read that
has no policy at all.

**Both surfaces are allocation-free when nothing is refused**, which is what makes the on-by-default one
affordable. `UnicodePolicy.violation` and `IdentifierProfile.hygiene` each scan and return
`Optional.empty()` — no split array, no script set, no stream — and the two call sites test that `Optional`
rather than passing a lambda to `ifPresent`. That last part is not a style preference: a lambda capturing
the name and the receiver allocates whether or not the `Optional` holds anything, and at one per rule per
name it was the whole measured cost of a check that is otherwise free — ~110 bytes per bound record, ~640
per read, and ~670 of the ~770 a raised *token* policy used to add. `AllocationHarnessTest` carries the
figures and the ceiling that now catches a return to them.

**The look-alike rule is the expensive one, and `Confusables.skeleton` is where that was spent.** It runs
per name per record on the schemaless tree path, and it normalised, built and re-normalised for every name
whether or not the name carried a confusable character. It now scans first and returns the decomposition
untouched when nothing maps — no builder, no second normalisation, and none of the stream and capturing
lambda a `forEach` over `codePoints()` costs — which is ~2.4 KB of a ~27 KB tree read. **What it must never
do is skip the table for ASCII**: eight ASCII code points carry a mapping, `m → rn` and `1 → l` among them,
so `payment` and `payrnent` read alike without a single non-ASCII character. `ConfusablesTest`
pins that pair for exactly this reason.

**Two surfaces, two defaults, and §8.2 sets both.** `withTokenPolicy` defaults to `unrestricted()` because a
value is data and may legitimately be anything; `withIdentifierPolicy` defaults to Highly Restrictive over
the whole name. Relaxing either is a method rather than a setting on purpose — §8.2 requires a deployment be able to
relax any of the three rules and requires the relaxation not be silent, and a policy read from the
environment is
invisible at the call site and absent from review. The relaxation to reach for first is the *unit*
(`perSegment()`), which still refuses `id_pаy` while admitting `url_адрес`. A token policy stricter than the
identifier policy subsumes it: a name is a token — which §8.2 asks an implementation's documentation to say,
and this is where it is said. The two names are §8.2's own: it defines the **identifier policy** and the
**token policy** as the two parts of a processor's configuration for that section, precisely so that two
implementations reporting them agree on what they are called, and `ProcessorConfig` uses those names.

**Field** names see all three, being names at every layer (§2.5, §7.7): the two per-name rules in the read
context beside a type-ref's and an annotation's, and the look-alike rule in `SchemalessTreeReader`, which is
where it belongs because it is a property of a *set* rather than of a name. One consequence worth knowing when
reading a report: a within-word homograph in a field name is refused as a restricted script before the
look-alike rule has a pair to compare, so a corpus vector isolating that rule wants two names each of which is
single-script. A refusal reports one code per rule —
`CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT` — each a verdict on the document like any other in
that the caller must change it or relax the policy. What these codes carry that a validity error does not is
that another processor at another Unicode version may accept the same document.

**The restricted-character rule is gated on the level, as the restricted-script rule is.** §8.2's
Unrestricted "drops the
profile too", taking that rule with it, so `appliesIdentifierProfile()` guards the `IdentifierProfile.hygiene`
call at both walks — the read context's and the linker's. Every other level keeps the profile, the
restricted-script rule gating itself inside `violation()`.

**And the run names the policy and the data version it judged under** (`ProcessorPolicy`, below). §8.2
reports a refusal under a *stated policy and a stated data version* and makes naming the version a MUST,
because §8.3 marks all three rules unstable across Unicode releases. Both are stated once, off the reader
that judged, rather than on each refusal.
`PolicyRefusalTest`/`SchemaPolicyRefusalTest` pin the data and schema ends against each other.

## Diagnostics

**`Diagnostic` lives in `tson-base`, and its classifiers do not.** The record, its `Code` enum, the three
receivers and `ReadException` are a module of their own, because [TSON-JSON] §9.4 makes a second
encoding report in the same four categories — one vocabulary by specification, not by convenience. The ten
`of*` factories that turn a thrown failure into a diagnostic stayed in `tson-compiler` as `TsonDiagnostics`,
because every one of them switches on an exception type this engine declares. What is shared is the shape of
an answer; classifying a failure is reading a document, and that is each encoding's own. (`Diagnostic`, root package)

`Diagnostic` is the structured value every `DiagnosticsReceiver` receives, identical shape whichever
one is in play: a closed `Code` enum (`FIELD_REQUIRED`/`FIELD_FIXED`/`TYPE_MISMATCH`/`WRONG_ARITY`/
`UNKNOWN_TYPE_REF`/`ATOM_FORM_INVALID`/`ATOM_CONSTRAINT_VIOLATION`/`UNRECOGNIZED_FIELD`/
`DUPLICATE_MAP_KEY`/`DUPLICATE_FIELD`
from readers;
`SCHEMA_ERROR`/`UNKNOWN_TYPE`/`VALIDATION_ERROR` for infrastructure-level failures, plus
`NOT_IMPLEMENTED`/`BIND_MISMATCH` and the five `SCHEMA_*` fetch codes — the members that are not a verdict
on the document at all, which `Code.verdict()` answers),
`message` (hand-composed per call site), `expected`/`actual` (machine-parseable) and **four location
components covering two ends** — the value in the data, and the rule in the schema. Every component is a
location; the one fact that is not, why a schema could not be obtained, is carried by the code itself.

**The four are JSON Schema 2020-12 §12's own output unit**, deliberately: `path` is `instanceLocation` (an
RFC 6901 pointer into the data), `schemaPointer` is `keywordLocation` (the path through the schema being
validated against, `/person/age`), `schemaId` plus `schemaPointer` are `absoluteKeywordLocation`, and
`dataPosition`/`schemaPosition` add the line/column/byte-offset TSON needs and
JSON Schema has no equivalent of. **One record rather than separate data- and schema-diagnostic types,
because the variation is locational, not categorical** — a value violating `int32` as core.tn declares it
populates both ends at once, and `javax.tools.Diagnostic`, LSP's `Diagnostic` and rustc's `DiagInner` all
model it the same way (rustc's `MultiSpan` being the mature form of the same idea).

**A fetch failure is five codes, not one code and a field.** `SCHEMA_NOT_PERMITTED` names a reference this
deployment will not fetch and `SCHEMA_NOT_FOUND` one nothing serves, both the document's to fix, where
`SCHEMA_UNREACHABLE`/`SCHEMA_TIMEOUT`/`SCHEMA_TOO_LARGE` say the reference was fine and the world was not.
That is the difference between telling a sender to correct its document and telling it to retry, and it is a
question consumers *route* on — so it lives where routing values live. A field beside the code was a second
carrier for one fact, and it cost a `Diagnostic` component, a second `TsonReadContext.report` overload
existing only to carry it, a `SchemaFailure` component, a `CliDiagnostic` component and a hand-copied enum
in `diagnostics.tn`.

**Five rather than two** (a "permanent" and a "transient" code) because consumers cut the same five
differently: a command line by whether a rerun could help, an HTTP surface by whose doing it was. One code
per reason keeps every partition derivable and privileges none. `SchemaFetchException.Reason` remains the
throwing channel's own vocabulary and the single input to `Diagnostic.Code.of`, so the two channels one fetch
failure travels on cannot disagree.
A consumer that resolves its schemas at startup sees `SchemaFetchException` thrown and reads
`reason()`; one that reads through a collecting receiver — the common path for a server validating request
bodies — sees a `Diagnostic` and never sees the exception at all. With the reason on the classification
only, the same refused reference was the sender's mistake read one way and an operator's read the other.
`SchemaFailure` carries it from the `catch` to the report (`TsonReadContext.report`'s five-argument form,
which the facades alone reach — no reader in the compiled stack can have one to state, a schema that could
not be fetched having no compiled readers to run), and `Diagnostic.ofSchemaUnavailable` takes the exception
rather than its message so the schema-document channel states it too.

**A §8.2 refusal carries no component of its own**, by the same rule that puts a fetch failure's cause in
the code. §8.2 requires a refusal to name the Unicode data version it was computed against, which is
a fact about *this processor* rather than about the problem — see `ProcessorPolicy` below, which is
where it and the policy are stated, once.

**Which rule refused is the code, and nothing beside it.** One code per §8.2 rule —
`CONFUSABLE_NAMES` for skeleton distinctness, `RESTRICTED_CHARACTER` for `Identifier_Status`,
`RESTRICTED_SCRIPT` for the restriction level — because the three want three different remedies: rename one
of a colliding pair, change the character, or relax the level or unit or name a script set.
`RESTRICTED_SCRIPT` says *a script this policy does not admit*, which is wider than a mix: a combination is
the usual finding, but at `ASCII_ONLY` a single-script name is refused with nothing mixed at all, so the code
names what the policy refused rather than what the text did. It pairs with `RESTRICTED_CHARACTER` as the two
halves of one identifier policy. The rule belongs in the code because the
code is what a consumer routes on; a second enum beside it would restate a fact the code already fixes and
would be free to contradict it. The names say what each rule *found* rather than how it works, §8.2's own
headings being exact and being jargon a consumer reading an error body cannot decode — the conformance
runners keep an explicit table from the corpus's spec-named spelling to these, since the two vocabularies
differ on purpose.

`RESTRICTED_SCRIPT` is also the one code a *value* can carry, a token having no identifier profile and no
scope to be distinct within.

## `ProcessorPolicy` — the configuration, stated once

**And threaded as one value.** Every constructor and derivation that used to take a `UnicodePolicy`, a
second `UnicodePolicy` and a `LimitsPolicy` — or, on the JSON side, a bare `int maxDepth` — takes the policy
instead: both streams, both TSON facades, and `JsonObjectReader`. Three parameters that always travelled
together is how a caller comes to pass a bound from one policy beside a token surface from another, and a
bare `int` beside a `UnicodePolicy` is the same hazard with less to grep for.

`withIdentifierPolicy`/`withTokenPolicy`/`withLimits` stay, each changing exactly one component; `ProcessorPolicy`
grew the matching three so a reader's derivation is one call rather than a rebuild. `withProcessorPolicy` is
the whole-value form, and what `Tson.objectReader()`/`treeReader()` now use — they chained all three before,
which was three chances to state two and forget the third.

**The front door states it the same way.** `ProcessorConfig.withProcessorPolicy` takes the whole value and is the
setter to reach for; `withIdentifierPolicy`/`withTokenPolicy`/`withLimits` are its components, each deriving from
whatever is already stated rather than replacing it, so a piecewise configuration and a composed one reach
the same processor and neither clobbers the other. `Tson` holds the one value, so `Tson.processorPolicy()`
is an accessor: the identifier half is handed to the schema registry at construction because the linker
judges declared names, and that is a use of the policy rather than a second home for it. A policy
reassembled on demand from components living in three places is one a caller can state and a report can
contradict. It is also what lets one policy configure both encodings — `Json.withProcessorPolicy` takes this
same value, and a deployment stating its constraints twice has two places to get them wrong.

**A token policy is never per-segment, and `ProcessorPolicy` is what refuses one.** `_` and `-` are word
separators by convention in a name and ordinary characters in a value, so segmenting a value admits UTS
#39's own `Toys-Я-Us` — the spoof a strict token policy exists to refuse. That is a property of what a token
policy can *mean*, not of any one way of stating one, so the compact constructor holds it and every route
that assembles a policy passes through there: the named setters, the withers, and a value a caller composes
itself. A check on each setter instead is a check every new route has to remember, and one route that
forgets accepts what all the others refuse.

**It carries three settings, not two.** The identifier policy, the token policy and the limits, plus the UCD
version the first two were computed against. The limits sat beside it while it was named
`ProcessorPolicy` — correctly, since a nesting bound has no business inside a *Unicode* policy —
and the rename is what made the grouping coherent rather than a reversal of that reasoning. A deployment
states one policy; the three components stay independent, and changing one still says nothing about the
others.

The two §8.2 policies (`identifierPolicy` and `tokenPolicy` — `ProcessorConfig`'s own names for them, so a
configuration and the report it produces are one vocabulary; each a level, a unit, and any `permitting`
relaxations) and the
UCD version the rules were computed against, as one value: `Tson.processorPolicy()`, either facade's
`processorPolicy()`, and `tson policy` on the command line, which prints it as text, JSON, or a TSON
document. Every `tson-cli` envelope carries one in its `policy` field.

**It is not a diagnostic component, and the three reasons are the shape of the whole design.**

- **Cardinality.** It is constant for the life of a process. Twenty refusals in one document would carry
  twenty copies of a string that cannot differ, and a consumer given twenty copies has to decide what a
  disagreement between them would mean.
- **Time.** A component on a refusal exists only once something has been refused. What a sender needs in
  order *not* to be refused is the same fact before it writes the document — which is why the standalone
  surface matters more than the envelope one, and why `tson policy` exists at all. A generator that reads
  the policy first never writes the name that would be refused.
- **Direction.** A version says what refused you; a level says what would be accepted. `16.0` is not
  something a caller acts on, where `ASCII_ONLY` is. Two processors at one UCD version routinely disagree,
  because the level is a local choice; two at different versions rarely do — so the half §8.2 requires is
  the half that explains less.

**Read off the reader that judged**, not rebuilt from a configuration object: a derived reader
(`withIdentifierPolicy`, `withTokenPolicy`) is exactly where the two can differ, and a response quoting the wrong
one is worse than quoting none. `UnicodePolicy.dataVersion()` remains the version as a static accessor;
the constant behind it (`Xid.UNICODE_VERSION`) is in the unexported `lexer` package and unreachable
otherwise. §8.2 requires exactly this shape: the policy and the data version are properties of the *report*,
not of the refusal, and a processor MUST make both available with any report containing one and SHOULD make
them available with no document in hand.

## `LimitsPolicy` — §9.1's bounds, on the same terms

What this processor will *spend* reading a document, where the policy above is what it will *admit as a
name*: `Tson.limitsPolicy()`, either facade's `limitsPolicy()`, `TsonTreeReader.withLimits`, `tson policy`,
and a `limits` record inside every `tson-cli` envelope's `policy` field. **Beside the Unicode policy, not
inside it** — the two answer different questions, and a deployment that changed one has said nothing about
the other. The three arguments above transfer whole: a bound is constant for a run, a sender needs it before
it writes, and a number a caller can act on beats a refusal after the fact.

**Only nesting depth is bounded**, at §9.1's own default of 64. §9.1 states the whole set as one table with a
default each — eleven more on the document side — and [TSON-SCHEMA] §11.5 adds five on the schema side under
the same policy and the same reporting surfaces; `BACKLOG.md` carries what is left and where each is counted.
It is a record with one component so each lands on it rather than beside it.

**Counted in the token stream, not in the readers.** `TsonDataStream.advance` already tracked bracket depth
for the schema parser's error recovery, and that is the one place every token is consumed — so the check is
one comparison per opening bracket and the refusal happens *before* any reader descends. That ordering is the
whole point: the stream is iterative and never overflows, while every reader over it recurses
(`SchemalessTreeReader.readNode` → `readArray` → `readNode`), and `EventSkip` recurses through values no
reader keeps and no context path steps. A limit enforced at the readers would have to be enforced at each of
them; enforced at the counter it also reaches schema documents, which are untrusted input wherever one is
fetched or `!!import`ed, through the same code.

**What it replaced.** A document a few thousand containers deep — about 10 KB, an ordinary request body —
exhausted the Java stack. A `StackOverflowError` is an `Error`, so it passed through every
`catch (RuntimeException)` in the reader stack and in `TsonCli.run` alike: no report on stdout, a JVM stack
trace on stderr, and exit 1, the code meaning *your document is invalid*. `LimitsPolicyTest` pins that the
same document now reports.

**The refusal is not a verdict** (`Diagnostic.Code.LIMIT_EXCEEDED`, `verdict()` false): the document may be
well-formed, valid, and read in full by the next processor along. It has its own classifier
(`Diagnostic.ofLimitExceeded`) rather than a case inside `ofBaseSyntaxError`, because a base-syntax failure is
a verdict every processor repeats and this one is a statement about the reader's configuration; both facades
catch it ahead of the `RuntimeException` that reaches the other. The CLI still **exits 1** — its envelope says
`NOT_CHECKED`, the truth about the document, while the exit code answers what the runner should do now, and
here they can act (`--max-depth`, or a smaller document). It is the one place the two diverge, and
`TsonCli.exitCodeFor` says so.

### Where a rule is stated: `base.diagnostics`

A `Diagnostic` has nine components and they come from two places. Four are the **rule's** — which rule fired,
what it says, the constraint that was not met, and what the document held instead. Five are the **read's** —
the RFC 6901 path, the schema identity and pointer, and both positions. No rule knows the second set and no
read knows the first, which is why `io.ltr8.tson.base.diagnostics` holds the rule half as a value (`Refusal`)
and every reader supplies where it happened.

**Why it is shared, and why that is an obligation rather than a tidiness.** [TSON-JSON] §9.4 gives both
encodings one diagnostic vocabulary and adds no category of its own, so a document wrong in one encoding is
wrong in the other for the same stated reason. The `code` and the machine-readable `expected` are what a
consumer routes on — and before this, two independently written record readers agreed about them only because
one had been copied from the other. Nothing held them there.

**The prose is the schema's vernacular, not the format's.** A record has *fields* in both encodings, even
though JSON's own word for what carries one is a member; absence is *absent* rather than `_` or `null`. The
reason is consistency about the thing being described: it is the **schema** that refused the document, so the
schema's nouns explain it, and a reader who moves between encodings learns one vocabulary. The encoding's own
spelling is not lost — it rides in `actual`, which echoes what the document literally held and is data rather
than prose.

That split tells the parity test exactly what to compare: **code, path, `expected` and `message`** for a
shared rule, and never `actual`, where `_` on one side and `null` on the other is correct.

**What the sharing found, on the first run.** For absence written at a REQUIRED field the two readers were
choosing *different rules* for one document: JSON said "'name' on 'person' admits no absence", TSON said
"missing required field 'name'". Same code, same pointer, same `expected` — and TSON's prose told an author
they had forgotten a field they could see themselves writing. §5.2's rule is that `_` asserts absence at a
position the schema always fills, so the document stated something and is not missing it. TSON's fall-through
to the missing-field message was the defect; it now reports the rule the document actually broke.

**Five families state their rules here**: records, arrays and sets (one class, a set being an array that
refuses a repeat), tuples, and maps. Choices wait for [TSON-JSON] §8.5 — `tson-compiler` states its dispatch
diagnostics parameterised over a "candidate noun" so one class serves a choice *and* a scoped position, and
aligning before the JSON side has the second position would be aligning against a shape about to change.

**What the wider pass taught, beyond the record family's finding.** Two boundary calls turned out to be
finer than "the schema's nouns":

- **"The absent sentinel" is the schema's noun and stays in the prose**; only the *spelling* is the format's.
  The first draft removed both and had to be walked back — [TSON-DATA] §2.9 names the concept, and a message
  that will not say it loses the word the spec uses for the thing it is refusing.
- **A message about a value renders the value, not the wire form.** A set duplicate reads `'a'` and not
  `'"a"'`. That is harder on the JSON side than it sounds, because tree mode discards the host value by
  design — so `Nodes.rendered` answers from the node instead, which is the same question the TSON reader's
  `Rendered.value` answers from the host value.

**A type names itself by what the author wrote, in both encodings.** `EntryDisplayName` lives in
`schema.meta` — beside the model it renders, and depending on nothing else — so both stacks reach it. A
resolver-minted entry shows as the sugar or application that produced it (`[text]`, `box<text>`), told apart
from an authored one by having no source position. Before the move it was `tson-compiler`'s, and a JSON
diagnostic named the synthetic entry by its content-derived hash: `'array_text_4cc4a482'`, a name in neither
the author's schema nor the sender's document. The record reader carries the display name *beside* its own
name rather than instead of it, because a `$type` resolves against the entry while a message names the
author's spelling — one place the two genuinely differ.

**What is deliberately not shared** is any rule one encoding has and the other has not: JSON's reserved member
namespace (§3.2) and TSON's positional record form have no counterpart across the wire, so each stays with the
reader that owns it. A shared class that grew those would be a second switch responsible for rules it cannot
name — the same failure `TsonDiagnostics`/`JsonDiagnostics` were split to avoid.

### When a fact earns a component

Every component is now a location, and the rule that keeps it that way is one line: **carry a fact as a
component when it is not recoverable from the document plus the schema, when it is a fact about the problem
rather than about the processor, and when it is not something the consumer routes on** — a routing question
belongs in the `Code`, which is what a consumer already switches over.

| Problem | Where the fact already is | Component? |
|---|---|---|
| Atom constraint violation | the bound is in the schema, at `schemaPointer` | no |
| `UNRECOGNIZED_FIELD` | the alternatives are the declared field list | no |
| `DUPLICATE_FIELD`/`DUPLICATE_MAP_KEY` | in the document, at `path` | no |
| §8.2 refusal: which rule | it is the `Code` | no |
| why a schema was not obtained | nowhere — it is about the world | no — it is the `Code`, one per reason |
| §8.2 refusal: the policy and the Unicode tables | nowhere — it is this processor's configuration | no — `ProcessorPolicy`, once per run |
| §9.1 refusal: which bound, and what it is | nowhere — it is this processor's configuration | no — `LimitsPolicy`, once per run; the bound itself rides in `expected`/`actual` |

Most diagnostics are about something the consumer is already holding, which is why `expected`/`actual` are
enough for them: a rendered `<= 100` is a convenience, and the authoritative copy is a file the consumer has.
The one exception is the case where the cause lives outside both documents. The last row is the second half
of the rule: the fact is unrecoverable *and* actionable, and it is still not a component — because it belongs
to the run rather than to the problem, so putting it here would be N copies of one answer.

Either end may be absent: a schema-side problem has no data, and a schemaless read has no schema. **Both
pointers are `Optional<String>`, and that is load-bearing** — RFC 6901 spells "the whole document" as `""`,
and this type emits it for real (a document-level schema problem such as an unloadable `!!import` points at
the schema root; a base-syntax failure points at the data root), so spelling "no such end" the same way would
make the two indistinguishable to a consumer *and* to a renderer. A present `""` is the root; an absence is
an absence. `schemaId`/`expected`/`actual` stay plain strings, where `""` carries no second meaning.

**That split is right at the source and useless at the sink**, so `schemaIdIfKnown()`/`expectedIfStated()`/
`actualIfStated()` say it once. Anything rendering a diagnostic onto a wire — the CLI's own `CliDiagnostic`,
an HTTP error body, anything downstream — wants a single answer to "is there anything here", and otherwise
has to know per component which of the two conventions applies. Nothing offers the same narrowing for the
pointers, deliberately: there `""` is a value, and a helper that swallowed it would erase the distinction the
paragraph above exists to keep. **The wire *shape* is not shared and should not be** — a CLI report and an
HTTP problem body are different envelopes with different audiences — but every renderer of one re-derives
this same absence rule, and that much belongs on the type.

**The read path's schema end is one value, `SchemaLocation`** — `schemaId` + `schemaPointer` +
`schemaPosition`, accumulated as the read descends rather than claimed by whichever reader is innermost.

**The pointer is the path taken, not the leaf reached.** A `y: int32` field violating its bound reports
`/point/y` in the author's own schema, *not* `/int32` in core.tn. Naming the leaf sends a reader to a file
they did not write, at a line past the end of the four-line schema their data named, and never mentions the
field they can edit — and it makes two identical mistakes tell different stories, since a field typed by a
local declaration would have named that instead. This is JSON Schema 2020-12 §12.3's `keywordLocation`, which
likewise follows the validation path rather than naming the dereferenced target, and it crosses a declaration
boundary the same way `keywordLocation` crosses a `$ref`: `/person/home/city` where `city` belongs to
`address`. Read the schema document as written — `{ point => { y: int32 } }` — and `/point/y` is a literal
RFC 6901 pointer into it. The constraint is not lost with the leaf: `message` still names `int32` and
`expected` carries `>= -2147483648 and <= 2147483647`.

**Two descent rules produce it**, both on `TsonReadContext`:

- `schemaField(name)` steps the data path *and* the schema pointer — the one descent the schema has its own
  name for. `field(name)`/`index(i)` step the data alone: the schema says one thing about every entry of a
  map, so `/person/tags` is the schema location of every `/tags/<key>`, and an *unrecognized* field names
  nothing in the schema at all, so extending the pointer with it would invent a location that does not exist.
- `inRecord(declaration)` / `underDeclaration(declaration)` decide the anchor. A **record** re-anchors
  `schemaId`/`schemaPosition` on itself, because it declares the field the pointer now ends with — and seeds
  the pointer with its own name only if nothing has yet, which is what makes the outermost record the path's
  root. **Everything else** offers its declaration only as a seed, taken when nothing encloses it, so a
  root-level `!int32` still locates itself in core.tn while the same atom inside `person` leaves person.tn's
  anchor alone.

The upshot is that `schemaId` and `schemaPosition` are always the *same* declaration's and can never disagree
about which file to open. The seed for a non-record comes from `TsonLinkedSchema.originOf`, not from the
schema being read against, which is what keeps that true for an imported declaration; `ValueReaderContext.locationOf`
is the single construction site, so there is nowhere else for a mismatched pair to come from.

**`schemaPosition` descends with the pointer** — `/person/age` carries `age`'s own line and column, not the
enclosing declaration's, because `RecordField` carries a position of its own beside `TypeDefinition`'s. Both
are populated because `SchemaResolver.resolveSchema` threads `TsonSchemaParser.schemaPositions()` down to
`DefinitionResolver` — one `SchemaPositions` carrier rather than a parameter per kind.

**Inside a declaration's own body the position stays the declaration's, and that is the granularity, not a
gap.** A choice variant and a supertype have no position of their own, so `'method' lists the variant 'card'
twice` is located at `method`'s head line rather than at the second `card`. The schema-side *pointer* names
the same unit for the same reason — `/method`, the failing declaration, because a schema problem is about the
declaration itself and no validation path led to it — so the two agree, and a finer position without a finer
pointer would make them disagree. What locates the author inside the declaration is the message, which names
the offending token.

- **A position table is identity-keyed, so every phase that rebuilds a node has to carry it over**, and
  three do. `SchemaDesugarer` re-registers a rebuilt *declaration* and a rebuilt *field* (any record holding
  a single `[T]` field is rewritten whole, so this is the common case, not an edge); §8.3's use-site
  flattening rebuilds every `RecordField` in the schema to rewrite its type-ref. That last one is why
  `RecordField.withType`/`withState` exist: a rebuild naming components positionally silently drops the ones
  it does not mention, and **no test comparing resolved values can catch it**, since position is excluded
  from equality on `annotations`' own footing.
- **`RecordField.position` is `@Unbound`**, on `TypeDefinition.position`'s precedent — §8.1's `record_field`
  declares no such field, so nothing fills it and strict binding would call it a mismatch. Deliberately not
  carried in the annotation channel, which is the opposite kind of thing: annotations are schema data, they
  resolve one hop against the governing meta (§6, and an unresolvable name is the author's error), and they
  round-trip into resolver output checked against the `*-resolved.tn` fixtures.
- **The reader takes it at the one descent that knows the field** — `ctx.schemaField(name, position)`, whose
  absent case leaves the enclosing record's position in place, which is the honest answer for a document
  whose source this resolver never saw (a hand-built one, or the bootstrap). No allocation changes:
  `schemaField` already builds a context per declared-field descent and a ternary only chooses which
  position it carries.
    - **A parameter, not an overload**, and the read path is why. Every caller has the field in hand, so a
      no-position `schemaField(String)` beside it would be a second way to take the same descent — and the
      one that silently reports the enclosing record's line would be the shorter, more tempting call. One
      method makes the compiler name every site that has to answer for a position.
    - **There is no compile-time route for this, unlike the name beside it** (`UseSite`). A name is composed
      by the reader, so a per-use-site reader copy can hold a different one; a position is consumed by the
      *context* when it builds the diagnostic. Pushing one from a reader would need a second
      `SchemaLocation` on every reader and a second context method to apply it — more surface, not less —
      and a reader copy for **every field of every record**, where the naming fix copies only for an alias.
      That would defeat reader sharing across the whole record family, which is the retained-memory cost
      the resolve-at-startup design exists to keep flat.

A read with no schema behind it carries none of the three.

**An atom refuses in two categories, and carries two codes.** [TSON-DATA] §5.2 splits the refusal — "a token
the atom's grammar rejects is a parse error; a parsed value violating the atom's range is a validation
error" — and §8.1 files the halves apart, a contract rejection being a *resolver* error ("the structural
parser has already accepted the document before an atom contract is consulted, so contract failures resolve,
they do not parse") where a range violation is a *validation* error. So `ATOM_FORM_INVALID` is what an
`AtomParseException` becomes and `ATOM_CONSTRAINT_VIOLATION` what an `AtomValidationException` does. One code
for both put a resolver error in the validation category, which is not something a consumer could correct
for: the two messages are equally "the atom said no", and the code is the only thing carrying which rule
fired.

**`AtomRefusal` is where that mapping lives, and it is the one place.** `AtomType`'s own signature is
untouched — it is shared with two schemaless binders that have no read context, and with the JSON stack,
none of which can be handed a `Diagnostic`. What `AtomRefusal` carries is the four non-locational components
(code, message, `expected`, `actual`); the reader adds the location, since only it has one. It lives in
`tson-atom` rather than on `Diagnostic` for the reason the rest of the classifying half stayed with each
encoding: `Diagnostic`'s ten `of*` factories each switch on an exception an *encoding* declares, and
`AtomTypeException` is the vocabulary's own and neither encoding's — so a factory for it on `Diagnostic`
would make the base depend on the vocabulary, while a copy per reader is how two encodings come to disagree
about one token. They already had: before the merge, a target that cannot represent a family's value was a
bind problem on one path and a type mismatch on the other, and nothing said which was right. Four readers go
through it now — `AtomTypeReader`, `TypeRefCheck`, `DataClassObjectReader` and the JSON stack's
`JsonAtoms` — and `AtomTypeException` is sealed to exactly two subtypes, so the switch is exhaustive rather
than a guess. Per-field schema positions are a separate matter, below. (Message synthesis from code + params
is not a gap but a decision — see below.)

**A broken FIXED field is `FIELD_FIXED`, not an atom code.** `field: type = value` (§5.2) is a field-state
rule, so a value contradicting it has satisfied its atom's grammar and every facet — it is simply not the
one value permitted. `FIELD_FIXED` sits beside `FIELD_REQUIRED` for that reason: the two §5.2 field-state
rules a document can break, neither of them about the field's type. All three ways to break one report it
(`RecordAbstractReader.verifyFixed`): a stated value contradicting `= value`, a `REQUIRED_FIXED` field
written `_`, and a value written where `= _` fixes the field to absent. The contradiction message also
names the fix — `=` reads as "default" to anyone arriving from JSON Schema, so `priority: priority = medium`
is a plausible mis-spelling of `~ medium`, and without the hint the author discovers it only by watching
every differing document get rejected.

**`expected` carries the constraint that failed, never the type's name.** `AtomTypeException` holds an
`expected` alongside its message, filled at each throw site from the facet that rejected the value, and all
three atom report sites (`AtomTypeReader`, `TypeRefCheck.violation`, `DataClassObjectReader.bindBuiltin`)
pass it straight through. Naming the type there — the old `a value satisfying quantity_t` against a message
reading `'99999' is greater than the maximum 100` — made the structured half carry strictly *less* than the
prose, so a consumer wanting the bound had to regex the sentence. That exception's own Javadoc fixes the
vocabulary at six shapes and no site invents a seventh:

| shape | example |
|---|---|
| an ordering bound | `<= 100`, `> 1`, `>= -128 and <= 127` |
| a membership | `one of (PENDING, SHIPPED, DELIVERED)` |
| a length | `exactly 4 characters`, `at most 10 bytes` |
| a pattern | `matching [A-Z]{3}` |
| a grammar (parse failures only) | `an RFC 3339 date-time`, `an integer or based-integer form` |
| a prohibition | `not NaN`, `a finite value` |

The declaring type name leads the *message* instead (`'my_percentage': '500' is greater than the maximum
100`) — it is what an author wrote and can act on, so giving up `expected` must not drop it from the
diagnostic entirely. `AtomTypeExceptionTest` pins all six shapes against the real parsers, because the
field's value is that it is one vocabulary across atoms, not a per-parser phrasing.

**`message` and the structured fields do different jobs, and neither is derived from the other.** The
structured half — `code`, `path`, `expected`, `actual`, the positions — carries the *facts*, and is what a
machine consumer acts on; it must be complete at every report site, including the facade-level ones
(`TsonObjectReader`/`TsonTreeReader`'s `abandon`, which no longer offers an overload that omits them, because
that overload is how three diagnostics ended up with a blank structured half). `message` is for a person, and
is free to do what a template could not: cite the spec, or name the fix.

```
annotation '@since' is written bare, which §6 treats as '@since:_', but 'since' does not admit the absent sentinel
'contact' has no variant matching this untagged value -- expected a value of one of
    (email, phone), or an explicit type annotation
```

Neither of those is a restatement of `expected`/`actual`, and synthesizing them from `code` plus parameters
would make them worse. **So there is deliberately no message-synthesis layer here**, and one should not be
added: `code` does not determine the sentence (`TYPE_MISMATCH` alone covers a wrong shape, a wrong token, a
wrong cardinality, a bare annotation, an unmatched variant and a host-binding failure), and the sentences
differ because the situations do. The failure mode worth guarding is a site that forgets `expected` — which
the missing overload now makes hard — not a site that writes a sentence a template wouldn't have.

**A base-syntax diagnostic states its position once, structurally.** `TsonParseException`, `LexException`
and `TsonUnsupportedDocumentException` keep the location in `position()` and out of `getMessage()`;
`toString()` appends it, so a stack trace still says where while the `Diagnostic` built from one carries
`dataPosition` as the single copy. Repeating it in the message made every renderer print the location twice,
in two formats, the second without a byte offset. `ReadException.toString()` does the same from its own
diagnostic, which is what keeps a stack trace informative now that a base-syntax failure reaches a fail-fast
caller through *it* rather than as the parse exception itself.

**A base-syntax failure goes to the receiver, like every other problem with the document.** Both facades'
whole-document entry points catch it and report `Diagnostic.ofBaseSyntaxError(e)`, so a collecting read
never throws for a bad *document* — it hands back nothing (no tree, `null` bind) and the collector holds why.
Three reasons this is the receiver's business rather than the caller's:

- **The stream is lazy**, so a base-syntax failure surfaces *mid-read*, after any earlier value-level
  problem has already been reported. Throwing past the receiver left a caller holding a populated collector
  *and* an exception, with nothing saying the two belonged to one document — and `Tson.validate` resolved
  that by discarding the collector and returning the syntax error alone, losing what it had already found.
- **It is the same shape the facade already used** for an unreachable `!!schema` (`readAgainstSchema`):
  report once, abandon the value. "Nothing can continue past a document that will not parse" is an argument
  for not continuing, not for not reporting.
- **A caller could not classify it themselves**: `LexException` is in the unexported `lexer` package, so
  `ofBaseSyntaxError` had to be public for anyone to write the `catch` — the library conceding the
  classification is required while making every caller ask for it.

**Fail-fast is unchanged in kind, changed in type.** `throwing()` still throws at the first problem, but a
base-syntax failure now arrives as `ReadException` (carrying the diagnostic, position included) rather
than `TsonParseException`. The exception type is the whole cost of the change, and it buys `Tson.validate`
being a plain call with no catch at all. Only a fault in *this library* still propagates as itself:
`ofBaseSyntaxError` rethrows anything that is not one of §8.1's three.

## Schema-side diagnostics (`TsonSchemaParser`, `SchemaResolver`, `TsonSchemaLinker`, `Tson.validateSchema`)

A broken *schema* reports every independent problem in one pass, through the same
`DiagnosticsReceiver` the read path uses. §8.1 asks for both halves of this: implementations MUST carry
source position in **all** error reports, and SHOULD "continue processing after an error to report multiple
issues in a single pass" — and it explicitly puts schema resolution/compilation failures in the *resolver
error* category, so this is the same layer, not a new one.

- **Parsing reports too, per declaration** — `TsonSchemaParser.parseSchemaDocument(receiver)` is the
  recovering entry point beside the fail-fast `parseSchemaDocument()`; without a receiver nothing changes.
  Panic-mode recovery: a failed declaration is reported, its wreckage is skipped, and parsing resumes at the
  next declaration.
  - **A declaration start is `name =>` back at schema-map depth, and nothing looser.** Two tokens decide it
    unambiguously, which is exactly the lookahead `TsonDataStream` keeps. A bare name is most of a broken
    declaration's own wreckage and a leading `@` is equally a field annotation, so neither resyncs. The cost
    is that an annotated declaration resyncs at its *name* and the recovered node loses its annotations —
    harmless, since the document is discarded whole.
  - **Depth is the cursor's (`TsonDataStream.nesting()`), not a counter the recovery keeps.** A declaration
    failing inside a record body leaves the cursor on *that record's* closing brace; a local counter starting
    at zero reads it as the schema map's own and stops one declaration in. The stream counts bracket pairs as
    tokens are consumed, being the one place every token goes through. `<`/`>` are not counted — a stray one
    is skipped harmlessly where a miscount would not be.
  - **A parse that reported anything hands back no document at all** (`Optional.empty()`), even though the
    declarations around the broken ones did parse. Resolving a half-document reports every reference to a
    dropped declaration as unresolved, on top of the syntax error that is the real problem; §8.1's categories
    are per layer precisely so a layer's verdict isn't second-guessed by the next. The surviving nodes exist
    only to keep parsing going.
  - **Two failures stay fail-fast.** A malformed *header* has no following construct to resync on — the
    schema map hasn't started. And the **lexer is the floor**: a token that won't lex raises `LexException`
    from underneath the recovery, since resynchronising means reading the very tokens that don't exist
    (`STRUCTURED-OUTPUT.md` tracks that layer).
- **A schema syntax error locates itself at the schema end** (`Diagnostic.ofSchemaSyntaxError`), the
  schema-side peer of `ofBaseSyntaxError`: `path`/`dataPosition` empty, the token's position in
  `schemaPosition` beside a `/name` pointer, so a syntax error and a resolution error against the same
  declaration render identically. The code stays `VALIDATION_ERROR` — *where* the problem is found is what
  the four location components are for. The `schemaId` is the document's own `!!id` canonicalized so it
  matches every later phase's, **falling back to the id as written** when it doesn't canonicalize: that is a
  real error but the resolver's to report, and raising it from the grammar layer would swap a syntax
  diagnostic the author can act on for a different complaint about a different line.
- **A parse failure names the construct the position admits, not the token class.** `TsonDataStream.expect`
  takes that construct in the author's voice (`"a record field's ':'"`), and `describe` prints the written
  token without its `TokenType` — `expected UNQUOTED (a type reference), found '!' (BANG)` spent both halves
  on parser vocabulary. The construct and the written token also become the diagnostic's `expected`/`actual`
  (via `TsonParseException`), which were the useless constant pair `well-formed TSON`/`a base-syntax error`
  before; a throw site stating a *rule* rather than a substitution — an adjacency violation, a trailing
  separator — leaves both `""` and nothing invents a pair. **One position names the fix outright:** `!` at a
  type-ref position (`quantity: !integer ^ { min: 1 }`, the natural first attempt) is rejected by name with
  the hoist-and-reference correction, the same shape as the size-spec and element-`?` rejections beside it.
- **Both callers parse this way**, so `tson validate` and `tson compile` give the same account of the same
  broken schema: `Tson.validateSchema` and `TsonCompiledMetaRegistry.resolveLinked(uri, receiver)` — the
  latter being how a *data* read reports on the schema its `!!schema` names.
- **Two reporting overloads, `SchemaResolver.resolveSchema(document, positions, receiver)` and
  `TsonSchemaLinker.link(schema, loader, receiver)`.** The existing overloads are untouched and still throw
  at the first problem. **The fail-fast paths deliberately do not route through
  `DiagnosticsReceiver.throwing()`** — that raises `ReadException`, and a schema that fails to
  resolve is not a read failure; the CLI's exit 1 against exit 70 turns on the distinction. They rethrow the
  original untouched.
- **The resolver catches inside its memoized namespace getter (`SchemaResolver.OnDemand`), not around the
  driving loop.** Resolution follows dependencies, not source order, so a failure usually happens inside a
  *nested* resolve; catching at the loop would attribute it to whichever declaration triggered it and then
  report the real one a second time. The memo makes it exactly once, against itself. Same shape as
  `TsonSchemaCompiler.Compilation.resolve` substituting an `ErrorReader` one phase later.
- **A failed declaration leaves an empty-record placeholder**, so its dependents still resolve. That is
  javac's error-type contract (it answers every question) rather than Swift's (every questioner must check
  first), and the choice is load-bearing: a `Sum`-bodied placeholder makes `parent => child & { ... }` fail
  *because* `child` did, reporting a consequence beside its cause. Swift's other half is kept — producing one
  means a diagnostic was already reported. It never escapes a reporting resolve, so it needs no `TypeKind`
  of its own.
    - **It keeps the declaration's own type parameters**, which is the one declaration-specific thing it
      carries. Answering every question is not answering them all with nothing: with the arity dropped, an
      application `bl<int32>` of a broken template `bl => <T> …` was told that `bl` "declares no type
      parameters … drop the argument list" — a fix that would break the schema further, the real one being
      upstream. With the arity intact the application closes against the empty body and says nothing.
- **A template condemned by `TemplateRegularity` is replaced before materialisation**, on the same terms.
  `check` hands its caller the names it rejected and `SchemaResolver` substitutes a placeholder in both the
  entry map and the namespace (the two are read by different halves — `materialise` walks the first, an
  application's head resolves through the second). Left in place, an application of one ran to
  `MAX_CLOSING_DEPTH` and reported the same defect a second time, against whichever entry applied it and
  carrying a 64-link chain of synthetic names the author never wrote. **The depth guard itself does not
  stand down**: what it guards is a hole in the static check, not a template the check already condemned.
- **A defect a held body deferred is reported against the declaration whose text wrote it**
  (`TsonSchemaLinker.heldDeclarationNaming`). A template's references cannot be settled until an application
  supplies arguments, so nothing checks them at the declaration; the verdict arrives on the entry
  materialisation minted, and the walk to a positioned entry finds the *applier*. So
  `box => <T> { v: T  w: no_such_type }` was reported against `holder => { b: box<text> }` — a line that is
  not wrong and does not contain the name — once per applier, each naming a different application. Deferred
  checking is what holding buys, and it is survivable only if the author is sent to the line they can edit.
    - **The offending name is the evidence, not the entry.** Walking a derived entry's own lineage cannot
      answer this: a sugar lift's `source` is the bare constructor it applies, and an alias composes its
      argument into someone else's application (`half => <B> pair<no_such_type, B>` closes to a `pair`
      instantiation, and `pair` is faultless). Asking which *held body mentions the name* reaches `half`
      directly — and reaches nothing when the name came from the argument list, so `box<3>` and
      `box<some_typo>` keep their verdict at the application, which is where those two mistakes are.
    - **The subject moves with the location**, which is why `UnresolvedReference` carries the sentence in
      parts (subject, trail, name) rather than finished: `'box<text>' field 'w'` states the mistake against
      an application that is itself correct. It is linker-internal and never escapes — re-stated as a
      `TsonSchemaValidationException`, whose classification it shares — because that type is deliberately
      `final` and lives in `tson-schema`, which holds no pipeline machinery.
    - **Both filters are load-bearing.** Only a *derived* entry is retargeted, or a closed declaration's own
      typo would be blamed on any template that happens to name it; and only a `TemplateBody` declaration is
      a candidate, since a defect no held body deferred is already located correctly. `HeldBody.names()`
      cannot tell a type reference from a field name, which is why it is asked only about a name already
      known to resolve to nothing — a field of that name is the one remaining way to mislead it, and it
      misleads no worse than naming the applier does.
    - **One mistake gets one diagnostic**, however many declarations apply the template: each application
      mints its own entry and each fails identically, so the count would otherwise be a property of the
      schema's callers rather than of the defect.
- **`Tson.validateSchema(schemaText)` is the front door and owns the phase boundary** — the schema-side peer
  of `validate`, and the only caller that composes all three phases. Every declaration parses before a
  verdict and a document that didn't parse whole is not resolved at all; every declaration then resolves
  before a verdict, and linking runs only if resolution was clean, so a schema with a broken declaration
  *and* an unresolved reference reports the declaration alone (the reference may well resolve once the
  declaration does). This is where javac and Swift both draw it: javac attributes every entry before
  `shouldStopPolicyIfError` blocks the next phase, Swift never reaches SILGen after a Sema error. **A schema
  that reported anything is never registered.**
- **A schema and the class bound to it must agree about a type's fields**, and the check is where they meet
  rather than where a document is read — both halves are fixed by the time a reader is built, so a mismatch
  is a `BindMismatchException` at bind-mode compile, which is startup for anything compiling its schemas
  once. **One rule: the class must be able to hold what the schema declares**, with a single exemption:
    - **Any non-FIXED field with no component** → refused at compile, optional ones included. Leaving
      OPTIONAL to the read that writes one is the tempting split and the worse trade: an optional field is
      exactly the one that works in development and fails the first time a caller sends it, so deferring it
      reports the hardest mismatch to find at the moment it has already gone wrong.
    - **REQUIRED_FIXED / OPTIONAL_FIXED** → exempt. The schema settles the value, so a component would hold
      a constant. **This exemption is what makes strictness possible at all**: 21 of the mismatches in this
      library's own bundled binding are FIXED fields (`access_pattern`, `size_type`, an atom's `spec`).
    - The rule bites the library first, which is the point: `datetime_type` declares `precision`, so
      `DateTimeType`/`TimeType` carry it and their parsers enforce it — §5.5 makes it an upper bound on the
      fractional-second digits of the token *as written*, checked textually because the atoms are exact and
      nothing is ever truncated to satisfy a facet (`FractionalSeconds`).
    - The converse — a component no field fills — is refused at compile too: it reaches the constructor as
      `null` on every document. `@Unbound` is how a class says a component is its own and not the wire's,
      needed exactly once here (`TypeDefinition.position`, this implementation's own addition for
      diagnostics).
    - **Strict is the default because the two ways of being wrong are not symmetric.** A strict reader that
      is wrong says so at startup, once, naming both sides; a lenient one that is wrong drops a value from
      every document and surfaces later as a field mysteriously holding its default.
      `DataBinding.lenient()` is the opt-out, the one path on which a field is dropped at all, and it is
      **silent**: reporting abandons the construction
      (`ConstructionGuard`), so a lenient reader that reported would return `null` for exactly the documents
      it exists to accept — and a diagnostic the guard is told to ignore is a severity axis under another
      name, and [TSON-DATA] §8.1 states there is no such axis: a conforming processor has one severity.
- **A gap becomes a diagnostic too, under its own code.** Both `TsonSchemaValidationException` and
  `UnsupportedOperationException` are reported per declaration; the code is what tells them apart —
  `SCHEMA_ERROR` for the author's mistake, `NOT_IMPLEMENTED` for a construct beyond this library. The test
  for which is which is unchanged, and is from Swift's treatment of `expression_too_complex`: *a schema
  error's verdict doesn't change when this library improves; a gap's does.* What changed is only its
  consequence for the pass.
    - **Why the channel stopped being the distinction.** Throwing a gap out of a phase that reports per
      declaration takes every other declaration's verdict with it: one unimplemented construct, and a
      document with three ordinary mistakes reported none of them, so the author fixed one thing per run.
      The policy's substance is that a gap is not a verdict on the author's schema, and a code carries that
      as well as a channel did — while letting the pass stay single, which is the property the whole
      schema-diagnostics design exists for. `SchemaResolver.Problems` is where the schema pipeline
      classifies, and `TsonCli.exitCodeFor` is what the CLI's exit code rides on.
    - **It classifies three ways, not two.** A `BindMismatchException` is neither an author error nor a
      gap, and reaches `ofSchemaBindMismatch` — the same answer `SchemaFailure` gives a read, for the same
      reason. Both throw sites keep it clear of their catch-alls (`bindAtomInstance` and
      `bindAnnotationValue`, which carry the same arm): relabelling it `UnsupportedOperationException`
      rebuilds the shape `MissingBindingException` exists to retire, a missing line of wiring reading as
      *this library cannot do that*. An annotation naming a type the consumer never bound — the kernel's own
      `data` among them — is the reachable case, and `BindMismatchClassificationTest` pins it in both §6
      positions plus the constructor case beside them.
    - A gap that escapes some *other* way still throws and still exits 70 unchanged — compilation and the
      lexer are fail-fast, and `TsonCli.notImplemented` remains for anything that reaches it.
- **A read that cannot get its schema classifies the failure the same way (`SchemaFailure`).** Both facades
  reach their schema through one call that resolves, links *and* compiles, so every way any of those can
  fail arrives at a single `catch` — and coding them all `SCHEMA_ERROR` says "the author's schema is wrong"
  about two failures that are nothing of the kind. `BindMismatchException` (its
  `MissingBindingException` subclass included) is `BIND_MISMATCH`: the schema is fine, the class is
  fine, and the reading application pointed them at each other by mistake, so the message names one of
  *its* classes and the document may be perfectly valid. An `UnsupportedOperationException` is
  `NOT_IMPLEMENTED`, the same code a gap gets everywhere else. A `SchemaFetchException` is one of the
  five `SCHEMA_*` codes, by its own `Reason`: no source would supply the schema, so it was never read, and
  whether it would even have resolved is unknown — `SCHEMA_ERROR` would claim a verdict on a document
  nothing here has seen. A
  `ContentHashMismatchException` *is* `SCHEMA_ERROR`, and the pair marks the line: something arrived,
  and it is not what the reference named. Each branch carries the `expected` that matches its code ("a
  schema that can be obtained", "a schema matching its `?sha256=` pin", "a resolvable schema"), and the
  fetch branch carries the exception's own `Reason` besides — the classification is the last place that
  still holds the exception, so what it drops is dropped for every collecting read.
    - **This is `NOT_IMPLEMENTED`'s argument one step further out**: a bind mismatch is no more a verdict on
      the document than a gap is, and once the failure arrives as a `Diagnostic` there is no exception type
      left for a consumer to classify on — only the code. A consumer choosing an HTTP status wants the three
      apart (the sender's problem, its own wiring, this library); one code gives it none of that, and
      matching on message text is the alternative it should not be pushed to.
    - **Every branch is a positive verdict and the default rethrows**, the same rule `ofBaseSyntaxError`
      ends on: a library fault propagates as itself. What makes that possible is `TsonSchemaSource.fetch`
      naming `SchemaFetchException` as the one way a source says "cannot supply this" — with no mandated
      type, an `IllegalStateException` arriving here is equally a source's miss or a broken invariant, and
      either every fault reads as a bad schema or every source that spells a miss that way crashes the read.
      A source failing any other way is that source malfunctioning, and surfaces as the exception it threw:
      `Tson.validate` promises a bad *document* never throws, and a bad *source* is not a document.
    - **Seven codes are not a verdict on the document** (`Code.verdict()`), and they differ in *who* could
      not give one: `NOT_IMPLEMENTED` (this library), `BIND_MISMATCH` (the reading application), and the five
      `SCHEMA_*` codes (whoever was to serve the schema). The CLI's exit codes follow — 70, 78, and 69 or 75
      by whether a rerun could help — and a mixed run ranks by who must act first, permanence breaking the
      tie between ranks where nobody present can act: 70 > 78 > 69 > 75 > 1.
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
  an `absorbed` stand-in (a zero-field record keeping the declaration's type parameters, the AST-level
  twin of `SchemaResolver.unresolved`) — **not passed
  through**, which would hand `DefinitionResolver` the very `ContainerTypeDef` the phase exists to remove and
  draw an `UnsupportedOperationException` the resolver deliberately doesn't catch, turning a reported author
  error into an unreported abort. Injected declarations are never rolled back: names are derived from the
  application, so §8.2's structural sharing means a later declaration may already reference one.
- **Still fail-fast:** compilation, and the lexer under everything. Compilation already keeps going via
  `ErrorReader`, but that marks a *library gap* (an unregistered atom factory), which is a different question
  from an author error.
