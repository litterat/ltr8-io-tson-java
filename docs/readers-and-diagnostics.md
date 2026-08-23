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
  states indistinguishable and the `?` decide nothing (`SPEC-FEEDBACK.md` #39). `_` is a validation error at
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
  rather than invented, and unreachable, since a form with no spelling is a form nobody wrote.
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
  keep the stream correctly positioned). **`ListEventSource`** replays a pre-built event list — used for a
  schema default (`readSchemaDefault` wraps a literal `Token` as one synthetic event) and, via
  `DataValueEvents`, for replaying an already-resolved `DataValue` tree through a compiled reader (the one
  place `resolver` still has a `DataValue` in hand).

## Diagnostics (`Diagnostic`, root package)

`Diagnostic` is the structured value every `TsonDiagnosticsReceiver` receives, identical shape whichever
one is in play: a closed `Code` enum (`FIELD_REQUIRED`/`FIELD_FIXED`/`TYPE_MISMATCH`/`WRONG_ARITY`/
`UNKNOWN_TYPE_REF`/`ATOM_CONSTRAINT_VIOLATION`/`UNRECOGNIZED_FIELD`/`DUPLICATE_MAP_KEY`/`DUPLICATE_FIELD`
from readers;
`SCHEMA_ERROR`/`UNKNOWN_TYPE`/`VALIDATION_ERROR` for infrastructure-level failures, plus
`NOT_IMPLEMENTED`/`BIND_MISMATCH` — the two that are not a verdict on the document at all),
`message` (hand-composed per call site), `expected`/`actual` (machine-parseable), and **four location
components covering two ends** — the value in the data, and the rule in the schema.

**The four are JSON Schema 2020-12 §12's own output unit**, deliberately: `path` is `instanceLocation` (an
RFC 6901 pointer into the data), `schemaPointer` is `keywordLocation` (the path through the schema being
validated against, `/person/age`), `schemaId` plus `schemaPointer` are `absoluteKeywordLocation`, and
`dataPosition`/`schemaPosition` add the line/column/byte-offset TSON needs and
JSON Schema has no equivalent of. **One record rather than separate data- and schema-diagnostic types,
because the variation is locational, not categorical** — a value violating `int32` as core.tn declares it
populates both ends at once, and `javax.tools.Diagnostic`, LSP's `Diagnostic` and rustc's `DiagInner` all
model it the same way (rustc's `MultiSpan` being the mature form of the same idea).

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

**`schemaPosition` is per declaration, so it is one level coarser than the pointer** — `/person/age` carries
`person`'s own line, not the field's, because `RecordField` has no position (`BACKLOG.md`). It is populated
because `SchemaResolver.resolveSchema` threads `TsonSchemaParser.declarationPositions()` into
`DefinitionResolver.resolve` — **and because `SchemaDesugarer` re-registers the position of every declaration
it rebuilds.** That second half is not incidental: the position table is identity-keyed, and any record
holding a single `[T]` field is rewritten whole, so without it the common case resolves with no position at
all. A read with no schema behind it carries none of the three.

An atom's `AtomTypeException` is caught in `AtomTypeReader` and mapped to
`ATOM_CONSTRAINT_VIOLATION` — `AtomType`'s own signature is untouched, since it's shared with the
schemaless binder which has no read context. That code means exactly "the atom rejected this token" and
nothing finer; routing `AtomValidationException`'s own varieties apart is out of scope for now, as are
per-field schema positions. (Message synthesis from code + params is not a gap but a decision -- see below.)

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
three atom report sites (`AtomTypeReader`, `TypeRefCheck.violation`, `SchemalessObjectReader.bindBuiltin`)
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
in two formats, the second without a byte offset. `TsonReadException.toString()` does the same from its own
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
base-syntax failure now arrives as `TsonReadException` (carrying the diagnostic, position included) rather
than `TsonParseException`. The exception type is the whole cost of the change, and it buys `Tson.validate`
being a plain call with no catch at all. Only a fault in *this library* still propagates as itself:
`ofBaseSyntaxError` rethrows anything that is not one of §8.1's three.

## Schema-side diagnostics (`TsonSchemaParser`, `SchemaResolver`, `TsonSchemaLinker`, `Tson.validateSchema`)

A broken *schema* reports every independent problem in one pass, through the same
`TsonDiagnosticsReceiver` the read path uses. §8.1 asks for both halves of this: implementations MUST carry
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
  is a `TsonBindMismatchException` at bind-mode compile, which is startup for anything compiling its schemas
  once. **One rule: the class must be able to hold what the schema declares**, with a single exemption:
    - **Any non-FIXED field with no component** → refused at compile, optional ones included. Leaving
      OPTIONAL to the read that writes one is the tempting split and the worse trade: an optional field is
      exactly the one that works in development and fails the first time a caller sends it, so deferring it
      reports the hardest mismatch to find at the moment it has already gone wrong.
    - **REQUIRED_FIXED / OPTIONAL_FIXED** → exempt. The schema settles the value, so a component would hold
      a constant. **This exemption is what makes strictness possible at all**: 21 of the mismatches in this
      library's own bundled binding are FIXED fields (`access_pattern`, `size_type`, an atom's `spec`).
    - The rule bites the library first, which is the point: `datetime_type` declares `precision` and
      `require_timezone`, so `DateTimeType`/`TimeType` now carry them — and their parsers refuse a schema
      that *sets* one, rather than accepting it and ignoring the facet. Neither is enforced (`precision`'s
      exact-vs-maximum semantics are unsettled by the spec; `require_timezone: false` needs an offset-less
      parse path), and a gap saying so beats a constraint silently not applied.
    - The converse — a component no field fills — is refused at compile too: it reaches the constructor as
      `null` on every document. `@Unbound` is how a class says a component is its own and not the wire's,
      needed exactly once here (`TypeDefinition.position`, this implementation's own addition for
      diagnostics).
    - **Strict is the default because the two ways of being wrong are not symmetric.** A strict reader that
      is wrong says so at startup, once, naming both sides; a lenient one that is wrong drops a value from
      every document and surfaces later as a field mysteriously holding its default.
      `TsonConfig.lenientBinding` is the opt-out, the one path on which a field is dropped at all, and it is
      **silent**: reporting abandons the construction
      (`ConstructionGuard`), so a lenient reader that reported would return `null` for exactly the documents
      it exists to accept — and a diagnostic the guard is told to ignore is a severity axis under another
      name, which `SPEC-FEEDBACK.md` #41/#42 argued against.
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
      schema-diagnostics design exists for. `SchemaResolver.schemaProblem` is the one place that classifies;
      `TsonCli.exitCodeFor` is what the CLI's 1-vs-70 now rides on.
    - A gap that escapes some *other* way still throws and still exits 70 unchanged — compilation and the
      lexer are fail-fast, and `TsonCli.notImplemented` remains for anything that reaches it.
- **A read that cannot get its schema classifies the failure the same way (`SchemaFailure`).** Both facades
  reach their schema through one call that resolves, links *and* compiles, so every way any of those can
  fail arrives at a single `catch` — and coding them all `SCHEMA_ERROR` says "the author's schema is wrong"
  about two failures that are nothing of the kind. `TsonBindMismatchException` (its
  `TsonMissingBindingException` subclass included) is `BIND_MISMATCH`: the schema is fine, the class is
  fine, and the reading application pointed them at each other by mistake, so the message names one of
  *its* classes and the document may be perfectly valid. An `UnsupportedOperationException` is
  `NOT_IMPLEMENTED`, the same code a gap gets everywhere else. Everything else is `SCHEMA_ERROR`, and each
  branch carries the `expected` that matches its code.
    - **This is `NOT_IMPLEMENTED`'s argument one step further out**: a bind mismatch is no more a verdict on
      the document than a gap is, and once the failure arrives as a `Diagnostic` there is no exception type
      left for a consumer to classify on — only the code. A consumer choosing an HTTP status wants the three
      apart (the sender's problem, its own wiring, this library); one code gives it none of that, and
      matching on message text is the alternative it should not be pushed to.
    - **The default is a verdict rather than a rethrow, unlike `ofBaseSyntaxError`'s otherwise identical
      shape.** That classification ends `default -> throw e` on the rule that a library fault propagates as
      itself; this one cannot, because `TsonSchemaSource.fetch` mandates no exception type — a source may
      signal an unfetchable schema with any `RuntimeException`, `IllegalStateException` included, and
      rethrowing the types this library reserves for its own faults would turn a missing schema into a crash
      for any source that spells it that way. So a genuine fault in a resolve or a compile still reads as a
      problem with the schema; closing that means tightening the `fetch` contract, and is in `BACKLOG.md`.
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
