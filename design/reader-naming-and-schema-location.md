# Reader naming and schema location

How a compiled reader names itself in a message, and how a read locates itself in the schema: `EntryDisplayName`,
`UseSite`, the `PathStep` chain behind both RFC 6901 pointers, and `SchemaLocation`. Current form only; history lives
in git.

**Invariants**

- `displayName` is beside `name`, never instead of it: the entry name is what a type-ref resolves against and what a
  tree node carries as its `typeRef`.
- A missing source position is what tells a minted entry from an authored one — exactly, not by the name's shape.
- A position naming an alias gets a reader copy at compile time; a choice keeps naming the variant, since renaming
  there would allocate per read.
- Both pointers are rendered from the `PathStep` chain only when a diagnostic is built; concatenating per step is
  quadratic in depth.
- The pointer is the path taken (`/point/y`), not the leaf reached (`/int32` in core.tn).
- `schemaField` steps data and schema together; `field`/`index` step the data alone.
- `schemaId` and `schemaPosition` are always the same declaration's; `ValueReaderContext.locationOf` is the single
  construction site.
- A position table is identity-keyed, so every phase that rebuilds a node has to carry it over; `schemaField` takes the
  position as a parameter, not an overload.

Related: `design/readers-and-diagnostics.md`, `design/scope-push.md`, `design/record-dispatch.md`,
`design/name-hygiene-read-path.md`, `design/diagnostic-model.md`, `design/diagnostic-rules-and-messages.md`,
`design/processor-policy.md`, `design/schema-side-diagnostics.md`.

## A reader names itself by what the author wrote

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
      (`b<10>`). Without it a violation against `b<10>` would read `'integer_type_10_100_786fbcfb': …`.
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
    `inRecord` then keeps that pointer and re-anchors only identity and line; a non-alias root seeds exactly
    what the reader would have established.
  - **A declaration with no line of its own contributes none** (`SchemaLocation.anchoredOn`), leaving
    whatever the descent had established rather than replacing it with an absence. Entries without a line
    are exactly those nobody wrote, and taking their absence answered "which line do I open" with nothing
    for a document whose author has a perfectly good line: the alias they wrote, or the record whose field
    the application sits at. That half is independent of the seed: a template application at a *field* has
    the right pointer from the descent and needs only its line kept.

## The cursor's position is wrapped when it is asked for, not when it is set

`peek`/`next` record the event's own `SourcePosition` in the cursor as it is; `position()` wraps it in an
`Optional` on the way out. Wrapping on every pull would cost an allocation per event for a value the event
already carries and that only `report` ever reads. The same shape as the pointers below: build the object
where the diagnostic is built.

What it does *not* do is remove the `Position` from the event, which is the structural version of the same
question and a much larger change: every event holds one, so the sources (`TsonDataStream`'s queue, the
rewind buffer, `ListEventSource`'s replayed lists) would all have to carry line/column/offset alongside the
event instead — which is to say `TsonEventSource` becomes a cursor with accessors rather than a producer of
objects. It would matter more in a port where an object is not a pointer bump.

## Both pointers are built when a diagnostic is, not while descending

A step of the descent is one `PathStep` node linked to the step before it, and the RFC 6901 pointers are
rendered from that chain only when `report` (or a caller) asks. Concatenating each step onto the last
would be **quadratic in depth**: every level copies the whole prefix again, and a read that
reports nothing throws all of it away, which is every read of a valid document. `schemaToo` on each step is
what keeps the two pointers apart in one chain: every schema step is a data step, but an array index moves
through the document without moving through the schema, whose element type is declared once for the array.

The schema end keeps its identity and line beside the chain rather than in it, because a re-anchoring
record replaces those while the pointer keeps growing — `inRecord`/`underDeclaration` set them, the chain
does not. `SchemaLocation` is what `schemaLocation()` hands back, built on demand rather than once per
field.

`AllocationHarnessTest.nestingCostsTheSameAtEveryDepth` pins the shape rather than a byte count: it prices
a level of nesting in a shallow part of a document and in a deep one and requires the two to agree. The
per-level structural cost (events, tokens, a node, a context) is flat and large enough to hide the pointer
in an absolute measurement — eager building shows up as the *deep* level costing ~200 bytes more than the
shallow one, and more at greater depth.

## The read path's schema end: `SchemaLocation`

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
  a single `[T]` field is rewritten whole, so this is the common case, not an edge); `MetaRefs.mapRefs`, the
  reference walk a rename runs through, rebuilds every `RecordField` it visits to rewrite its type-ref. That
  last one is why
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
