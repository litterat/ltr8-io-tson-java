# Read facades: `TsonObjectReader` and `TsonTreeReader`

Design notes for the two consumer read front doors (`tson-compiler` root package): their two modes, the
Jackson-style derivations, the Unicode policy surfaces, and the rules a read applies around the value. Current
form only; history lives in git.

**Invariants**

- Each reader is dual-mode, fixed at construction: standalone is schemaless, obtained from a `Tson` it is
  schema-aware; a derived reader shares the original's compiled-schema registry, never rebuilding it.
- A failure reaching or resolving the schema is a diagnostic, not an exception — and a schema's own
  diagnostics go to the facade's receiver, never through `ctx.report`, which would stamp a data position on
  them.
- `TsonReadContext.of` takes the policy as a required parameter; the token policy is applied by the stream,
  not the context, because the context rewinds and a check there would report one token twice.
- A lookup of the root type-ref, and every dispatcher, looks ahead by rewinding (`EventSkip.typeRefAhead`
  over `TsonReadContext.lookingAhead`) and never consumes the framing — consuming it silently strips the
  value's annotations.
- The whole-document entry points own framing, including the `requireDocumentEnd` pull that makes the lazy
  stream check for trailing content.
- A schemaless read checks type-refs by `TypeRefCheck`'s three rules; `preservingUnknownTypeRefs()` opts out
  of rule 3 only, and an atom position takes `declares` (`@Typename` only), never the loose `names` match.
- Reporting never abandons the value: a reported type-ref still yields its node and its children are read.
- An annotation is checked wherever it is written and kept only where there is room (`capture()` vs
  `validating()`); a field the target class does not declare is `UNRECOGNIZED_FIELD`, with
  `ignoringUnknownFields()` the derived opt-out.

Related: `design/writers-and-document-header.md` (writers, `TsonDocumentHeader`, `TsonDocumentPeek`),
`design/tree-model.md` (`TsonValue`, `TsonDocument`), `design/front-door-and-config.md` (`Tson`,
`ProcessorConfig`, schema sources), `design/readers-and-diagnostics.md`, `design/linking-and-compilation.md`.

## Read facades: `TsonObjectReader`/`TsonTreeReader` (root package) + `TsonObjectWriter`

`TsonObjectReader` (to a bound Java object) and `TsonTreeReader` (to a `TsonValue` tree) are the two
consumer read front doors, named for what a consumer holds, matching Jackson's `ObjectReader`/`readTree`.
Each is **dual-mode, fixed at construction**: built standalone (`new TsonObjectReader(ctx)` / `new
TsonTreeReader()`) it's **schemaless** (Class 1 — the target class, or the wire, is the whole contract;
any `!!schema` the document declares is ignored, Jackson-style); obtained from a `Tson` facade
(`objectReader()`/`treeReader()`, carrying a configured `SchemaSource`) it's **schema-aware** — a
self-describing document is validated against its declared `!!schema` as it's read (the schema resolves
through the source, the root type-ref selects the type), else read schemalessly. `readWithoutSchema(...)`
forces the schemaless path on a schema-aware reader.

**These two are the whole document-reading surface**, and both derive Jackson-`ObjectReader`-style rather
than taking parameters, so source form, error policy and schema selection stay orthogonal instead of
multiplying overloads: `withDiagnostics(receiver)` swaps fail-fast for any other receiver,
`preservingUnknownTypeRefs()` relaxes the schemaless type-ref rules below,
`withTokenPolicy(policy)` applies UTS #39 §5.2 to every token the read pulls (see below), and
`withSchema(uri).readAs(source, typeName)` covers data that *isn't* self-describing — the caller supplies
what a `!!schema` plus a root type-ref would have said, and validation is identical either way. Each returns
a new reader **sharing** the original's compiled-schema registry, never rebuilding it. A
`TsonTypeReader` from a compiled schema is the layer underneath: a strict single-method interface that
reads one value at a cursor and polices nothing around it.

`withLimits(limits)` derives the same way, so source form / error policy / schema selection / Unicode policy /
resource limits stay orthogonal.

### The two Unicode surfaces: `withTokenPolicy` and `withIdentifierPolicy`

**`withTokenPolicy` and `withIdentifierPolicy` are the two Unicode surfaces** ([TSON-DATA] §8.2's "Values"
and its three name-hygiene mechanisms). Both have to be reader axes rather than registry ones, because the
surface they guard includes the standalone schemaless constructors, which hold no registry at all — and a
Class 1 read is exactly where a value arrives least constrained.

**A `Tson` applies both to every reader it makes**, from the one `ProcessorConfig.withProcessorPolicy` it was built
with (`withIdentifierPolicy`/`withTokenPolicy`/`withLimits` are its components, each deriving from what is already
stated) — the identifier half riding the registry to the linker *as well*, so a schema's declared
names and a document's own type-ref and annotation names are judged under one setting. They are one processor,
and `Tson.processorPolicy()` reports one answer for it, which is only true if one answer is what both ends
use: a configured identifier policy that reached the linker alone would make that report name a policy no read
had applied, which is worse than reporting none. `SchemaPolicyRefusalTest` pins the read end.

**`TsonReadContext.of` takes the policy as a required parameter and installs the check itself**, so no context
can exist whose events went unchecked — the low-level API cannot skip the policy by saying nothing, which is
the property that makes it a policy rather than a facade convenience. Naming `unrestricted()` is a fine
answer and the right one for a synthetic source; it is just not one a caller gives by accident. The three
internal synthetic sites (`AnnotationCapture`, `RecordAbstractReader`, `SchemaResolver`) each pass it with the
reason written beside them: the first two replay events the real stream already delivered, so checking again
would report one author token twice, and the third reads a resolved schema value rather than document text.

The token policy is applied by the stream rather than inside the context, and the
reason is exactly-once: the context rewinds, and a probe context can be built over events already seen, so a
check there would report one token twice. The underlying stream produces each token once, so the decorator
needs no set of already-reported positions. `wrap` returns the source unchanged when the policy checks
nothing, which is the default — an ordinary read pays not even a predicate.

**A raised policy is not a per-token allocation either**, which is what makes it advisable to turn on. The
conforming path through `UnicodePolicy.violation` scans and returns `Optional.empty()`: no split array
(hand-segmented, since `"[_-]"` misses `String.split`'s single-character fast path and compiles a `Pattern`
per call), no script set (a single-script unit is decided without materialising one — only a genuinely mixed
token builds the set `covered` and the message need), no stream, and `isPresent`/`get` at the call rather than
a lambda that would capture three fields per token. What is left is the decorator, once per read:
`AllocationHarnessTest.aRaisedTokenPolicyCostsAlmostNothingPerRead` pins it at ~100 bytes per read, against
~2.3 KB before the scan paths were written this way.

The check sees the four events carrying text — a value, a field name, a type-ref, an annotation name — because
at that layer nothing yet knows which is which. **So a name is a token**, and a token policy stricter than the
identifier policy subsumes it: the name has already cleared the stricter rule by the time the name rule looks
at it. The setter is named for the surface rather than for the values it mostly affects so that this is
visible where it is configured. Document directives are not checked: a `!!schema`/`!!id` token is a URI naming
an external resource, §2.2.1 governs what an identity may be, and an IRI's scripts are the resource owner's
business. The diagnostic (`RESTRICTED_SCRIPT`) carries a position and no `path`, which is not an omission —
there is no path yet at the point the check runs. `perSegment()` is refused rather than ignored here: `_` and
`-` are word separators by convention in a name and ordinary characters in a value, so segmenting one would
admit UTS #39's own `Toys-Я-Us`.

### What a read does around the value

- **The class-driven binding / tree-building mechanics live in the internal `reader` package**
  (`DataClassObjectReader`/`SchemalessTreeReader`, unexported); the public readers are thin facades that
  peek the `DocumentStart` for a `!!schema` and dispatch to either the compiled schema registry or the
  schemaless engine. The whole-document entry points (`read`/`readWithoutSchema`/`readAs`) own document
  framing — consuming the leading `DocumentStart`, and the `requireDocumentEnd` pull that makes the lazy
  stream check for trailing content; the low-level `read(TsonReadContext, …)` is frame-free (a value at the
  cursor, for a caller managing their own context) and always schemaless.
- **A failure reaching the schema is a diagnostic, not an exception.** An unresolvable `!!schema`, a missing
  root type-ref, a root type the target class can't hold: each reports through the receiver and skips the
  root value (so the stream still lands on `DocumentEnd`). Under `throwing()` that is indistinguishable from
  the old behaviour; under a collector they arrive as `Diagnostic`s, which is what lets `Tson.validate`
  delegate to `treeReader()` wholesale instead of re-deriving anything.
- **The root type-ref is found past the root value's annotations (`EventSkip.typeRefAhead`), not at the
  first event.**
  `data-value = *annotation [type-ref] core-value`, and §3.3 puts the two in that order deliberately —
  augmentation attaches to the value that follows it, and the type-ref is part of that value — so
  `@doc:"…" !api { … }` annotates and types one value and its root type-ref is `!api`. This is not a
  nicety: TSON has no comment syntax (§2.4, deliberately), so an annotation is the only way to put prose in
  a document, and a root that cannot carry one leaves configuration, fixtures and API descriptions unable
  to say what they are for. Reading against an explicitly named type (`readAs`) never had the problem — it
  needs no lookup — which is what shows the whole reader stack below has always handled this.
    - **Looked past by rewinding, not consuming** (`TsonReadContext.lookingAhead`, which records what a
      lookahead reads and replays it afterwards — the same primitive every dispatcher uses, below). The
      annotations belong to the root value, and the reader underneath builds them into what it returns — a
      `TsonValue`'s annotation list, a bound class's
      `Annotations` carrier. A lookup that consumed them to reach the type-ref would select the right reader
      and hand it a value stripped of the very prose this exists to allow: a silent loss, not a failure.
      Events are replayed from a buffer rather than re-lexed, so a lookahead holds only what it looked past.
    - An annotation *after* the type-ref stays a syntax error, correctly — the grammar admits one order.
- **A schema that *resolves* badly reports like one, even mid-read.** `tree.get(uri, receiver)` /
  `bind.get(uri, receiver)` (over `TsonCompiledMetaRegistry.resolveLinked(uri, receiver)`) resolve and link
  the named schema collecting, so validating a data document against a broken schema reports **every**
  declaration at fault — the same account `tson compile` gives, since the schema is equally broken either
  way. Those diagnostics go to the **facade's own receiver, not through `ctx.report`**, which would rebuild
  them from the *data* cursor: stamping a data position on a problem that is in a schema and discarding the
  `schemaPointer`. The registry caches nothing for a schema that reported, so a second read reports again
  rather than appearing to succeed. Distinct from the bullet above: a schema that can't be *reached* (no
  such URI, malformed, wrong `!!id`) is still one diagnostic, because there is nothing to enumerate.
- **A schemaless read checks its type-refs, and `TypeRefCheck` (in `reader`) states the rules once** for
  both engines. Given `!X` on a value: (1) `X` **is** a `BuiltinTypeVocabulary` name → it must sit on a
  token (`TYPE_MISMATCH` otherwise) and that token must satisfy the atom
  (`ATOM_FORM_INVALID` for a token of the wrong form, `ATOM_CONSTRAINT_VIOLATION` for a value out of
  range); (2) `X` **names the target** being bound → accepted, object-binding
  only, a tree read having no target; (3) otherwise it links to nothing → `UNKNOWN_TYPE_REF`.
  **Rule 3 is a reader policy, not a parsing one** — the parse step still preserves every marker per §5.1;
  what a reader *type-checking* a value does with one it can't link is the layer above, where a
  case-sensitive typo (`!Uuid`) silently disabling the author's intended validation is the worse failure
  (a reader policy: §7.1 asks only that an unresolved type annotation be treated as informational, and
  reporting it is the stricter reading). `preservingUnknownTypeRefs()` on either
  facade opts out of rule 3 only — built-in names stay checked — and is what round-tripping through
  `TsonTreeWriter`, or reading the wire of a document whose `!!schema` is deliberately out of scope, wants.
- **Rule 2 is looser for a container than for an atom, deliberately.** `TypeRefCheck.names` (a `@Typename`,
  else the simple class name case-insensitively — the same match `bindUnion` gives union members) is what
  lets `!point { x: 3  y: 4 }` bind to a Java `Point` with nothing annotated. An atom position takes
  `TypeRefCheck.declares` (`@Typename` only), because the loose match would accept a `UUID`-targeted
  `!Uuid` on the strength of the class being *called* `UUID`. Consequence worth knowing: a collection
  target answers to no wire name, so `!tags [ "a" ]` into a `List<String>` is `UNKNOWN_TYPE_REF`.
- **Reporting never abandons the value.** A reported type-ref still yields its node/object and its children
  are still read, so one collecting pass finds everything; a leaf whose atom rejected the token becomes a
  `TsonAbsent` keeping its wire type-ref (the placeholder `AtomTreeReader` already uses). `SchemalessTreeReader`
  scopes `ctx.field`/`ctx.index` as it descends, so a diagnostic carries a real RFC 6901 path.
- **`TsonObjectReader`'s schema-aware `read` checks the target class up front** — the schema's root type
  already binds to a Java class via the name binder, so a class not assignable to that is a `TYPE_MISMATCH`
  reported *before* the value is read, not a cast failure after.
- **Both tree read paths capture wire annotations** onto each node's `annotations()`, at every position §3.1
  permits one (root, record field value, array element, both sides of a map entry, and recursively an
  annotation's own value). `AnnotationCapture` (in `reader`) is the shared helper: an annotation's value
  events are buffered and replayed through a schemaless tree read via `ListEventSource`, so the recursion
  needs no special case, and an annotation's value is *always* read schemalessly — its type resolves one hop
  against the governing namespace (§3.3.3), not against the compiled readers in scope. `EventSkip.typeRef`
  is split out of `annotationsAndTypeRef` so capture and discard share the framing's second half.
- **The schema-driven readers capture by hoisting, not by widening signatures.** A compiled tree reader
  shares its `*AbstractReader` base with the bind subclass, and the base consumes the framing where the node
  isn't built. Rather than thread annotations out of four shared shape-check methods (making bind mode carry
  a field only tree mode reads), each tree reader — and the `AtomTreeReader`/`AbsentTreeReader` wrappers —
  captures *first*, then calls the base/delegate, whose own framing call then finds nothing left. That's a
  no-op precisely because every one of those readers **discards** the framing result rather than using it.
  Bind mode is untouched.
- **A dispatcher reads the type-ref it decides on without consuming it** (`EventSkip.typeRefAhead`, over
  `TsonReadContext.lookingAhead`), so the reader it chooses is handed the whole data-value — annotations,
  type-ref and core-value — exactly as it would be if nothing had dispatched to it. `NamedDispatchReader`,
  `VariantSchemaReader` and `VariantBindReader` all work this way, and `ChoiceReader` is one factory for both
  modes rather than two, there being nothing left for a mode to differ about.
    - **Consuming the framing was the whole problem.** `data-value = *annotation [type-ref] core-value`, so
      reaching the `!typeName` meant eating the annotations, and the reader that then built the value never
      saw them. Tree mode papered over it by re-attaching to the finished node (`TsonValue.withAnnotations`);
      bind mode had no equivalent, so a variant class declaring an `Annotations` carrier got an empty one
      while the *same class* read where nothing dispatched got the annotation — a document's prose surviving
      or not according to how deep the value sat. Looking and rewinding makes both modes agree by
      construction instead of by two implementations staying in step, and deleted the re-attachment rather
      than growing it a bind-mode half.
    - The error paths moved with it: a dispatch that reports (an unknown variant, no tag where one is
      required) now discards with `EventSkip.dataValue`, framing included, since nothing else consumed it.
      Untagged recovery reads the value's discrimination class off `EventSkip.aheadOfValue` for the same
      reason — the value no longer starts at the cursor.
- **A schema-driven read also type-checks annotations** (§6: an annotation *names a type*). `AnnotationTypes`
  resolves the name against the governing schema (§3.3.3's one hop — for a data document that's the
  `!!schema` target, i.e. the very schema the readers were compiled from) and the value is read by *that
  type's* compiled reader, so a wrong-typed value fails for the ordinary reason any wrong-typed value fails.
  Read-time resolution is safe because `compile` is eager; the lookup is gated on `schema().entries()`
  because the resolver throws for an unknown name. An unresolvable name reports `UNKNOWN_TYPE_REF` and the
  annotation is **still kept** (§1.5 requires preserving what a processor doesn't act on); §6's bare `@T` is
  checked as `@T:_` by reading a synthetic absent through the reader. The schemaless path checks no
  annotation *name* — no governing schema, no type to resolve against — but an annotation's **value** is a
  data-value, so the type-ref rules above reach into it: it is read by the enclosing reader itself, hence
  exactly as strictly. The one exception is the schema-driven *fallback* (a name the governing schema
  doesn't declare), which reads its value through a **preserving** reader — §1.5 already keeps an annotation
  nothing can interpret, and rejecting its innards would take that back. §1.3's Class 2 list requires the
  resolution and validation; the preserving reader is how the two rules meet.
    - **Checked wherever it is written, kept only where there is room** — the two are different questions and
      `AnnotationTypes` now separates them (`capture()` vs `validating()`; `discarding()` is the vocabulary
      that drops its result and checks it anyway). Whether an annotation has somewhere to land is a fact
      about the bound Java class — a record declaring an `Annotations` component, or a bound scalar with no
      slot at all — and a document does not conform any better for being read by a class that throws its
      annotations away. Conflating them made the *carrier decide the verdict*: one document, one schema, one
      mode, reported for `Carrier` and silently accepted for `Plain`. `DISCARDED` survives for the one case
      where dropping and not checking really are the same decision — no governing schema at all.
    - A consequence worth stating: bind mode is all-or-nothing, so an annotation a reader was going to
      discard can now fail the whole read. That is the point — the document is invalid, and it was being
      accepted for a property of the reading application rather than of itself.
- **`DataClassObjectReader` streams events** (like the compiled readers), walking the descriptor in
  parallel — never materializing a tree first. Problems report through a `TsonReadContext` (fail-fast throws
  `ReadException`; collecting accumulates), and a `tson-bind` `DataBindException` while narrowing /
  applying a bridge / invoking a constructor is caught and re-reported through `ctx`, so a caller sees one
  uniform error model regardless of which layer noticed. **No positional form and no schema-composed
  defaults** — both are schema-layer concepts a class-driven bind has no equivalent for (a record must be
  braced; an absent required field is `FIELD_REQUIRED`).
- **A field the target class does not declare is `UNRECOGNIZED_FIELD`**, the same code and the same
  treatment a schema-driven read gives one — reported, then the value discarded unread, so one collecting
  pass finds every stray name. The reason is not tidiness: **a field added in a later version can change
  what the fields this class does read mean** — a `currency` beside an `amount`, a `unit` beside a
  `quantity`, an `encoding` beside a `payload`. A reader that drops it has not read a subset of the
  document; it has read a different document and cannot tell. The class is the schema on this path, and a
  closed reading is what makes that claim mean anything. `TsonObjectReader.ignoringUnknownFields()` is the
  opt-out, and it is deliberately the derived reader rather than the default — the safe reading should be
  the one nobody has to know to ask for, which is the same argument `BindMismatchException` makes at
  compile time about a schema and a class that disagree.
