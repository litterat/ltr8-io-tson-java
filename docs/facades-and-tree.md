# Read facades, writers, tree model, and front door

Design notes for the consumer-facing read/write surface: `TsonObjectReader`/`TsonTreeReader`, the writers,
the `TsonValue` tree model, and the `Tson`/`TsonConfig` front door. Current form only; history lives in
git. `CLAUDE.md` holds the one-paragraph orientation; this file holds the detail.

## Read facades: `TsonObjectReader`/`TsonTreeReader` (root package) + `TsonObjectWriter`

`TsonObjectReader` (to a bound Java object) and `TsonTreeReader` (to a `TsonValue` tree) are the two
consumer read front doors, named for what a consumer holds, matching Jackson's `ObjectReader`/`readTree`.
Each is **dual-mode, fixed at construction**: built standalone (`new TsonObjectReader(ctx)` / `new
TsonTreeReader()`) it's **schemaless** (Class 1 — the target class, or the wire, is the whole contract;
any `!!schema` the document declares is ignored, Jackson-style); obtained from a `Tson` facade
(`objectReader()`/`treeReader()`, carrying a configured `TsonSchemaSource`) it's **schema-aware** — a
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

**`withTokenPolicy` and `withIdentifierPolicy` are the two Unicode surfaces** ([TSON-DATA] §8.2's "Values"
and its three name-hygiene mechanisms). Both have to be reader axes rather than registry ones, because the
surface they guard includes the standalone schemaless constructors, which hold no registry at all — and a
Class 1 read is exactly where a value arrives least constrained.

**A `Tson` applies both to every reader it makes**, from `TsonConfig.tokenPolicy` and
`TsonConfig.identifierPolicy` — the latter riding the registry to the linker *as well*, so a schema's declared
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

`TokenPolicyEventSource` is a decorator on the event source rather than a check inside the context, and the
reason is exactly-once: the context rewinds, and a probe context can be built over events already seen, so a
check there would report one token twice. The underlying stream produces each token once, so the decorator
needs no set of already-reported positions. `wrap` returns the source unchanged when the policy checks
nothing, which is the default — an ordinary read pays not even a predicate.

**A raised policy is not a per-token allocation either**, which is what makes it advisable to turn on. The
conforming path through `TsonUnicodePolicy.violation` scans and returns `Optional.empty()`: no split array
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

- **The class-driven binding / tree-building mechanics live in the internal `reader` package**
  (`SchemalessObjectReader`/`SchemalessTreeReader`, unexported); the public readers are thin facades that
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
  (`ATOM_CONSTRAINT_VIOLATION`); (2) `X` **names the target** being bound → accepted, object-binding
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
- **`TsonTreeWriter` re-emits them** — `TsonDataEmitter` gained `annotation`/`beginAnnotation`/
  `endAnnotation` (the valueless form's trailing space is load-bearing, §3.1) and `writeNode` writes a
  node's annotations ahead of its type-ref, per §7.4's `*annotation [type-ref] core-value` order, so a tree
  round trips with its metadata, not just its values. **`TsonObjectWriter` re-emits a carrier's too** —
  §7.4's order is why `write` splits into `write` (annotations, then the value) and `writeCore` (the shape
  switch): `writeUnion` writes a type-ref of its own, so it emits its member's annotations *before* that and
  goes straight to `writeCore`, rather than recursing and landing them after it. An annotation's value writes
  back in whichever form the read produced — a bound object like any other value, a structurally-kept
  `TsonValue` through `TsonTreeWriter`'s own node emission (package-private, both writers share a package).
- **`SchemalessObjectReader` streams events** (like the compiled readers), walking the descriptor in
  parallel — never materializing a tree first. Problems report through a `TsonReadContext` (fail-fast throws
  `TsonReadException`; collecting accumulates), and a `tson-bind` `DataBindException` while narrowing /
  applying a bridge / invoking a constructor is caught and re-reported through `ctx`, so a caller sees one
  uniform error model regardless of which layer noticed. **No positional form and no schema-composed
  defaults** — both are schema-layer concepts a class-driven bind has no equivalent for (a record must be
  braced; an absent required field is `FIELD_REQUIRED`).
- **`TsonObjectWriter.toTson` is mainly a debugging tool**, not a guaranteed-lossless serializer (integer
  width, tuple-ness, and captured wire annotations are documented write-side losses). Both throw unchecked
  (`TsonReadException`/`TsonWriteException`), so the pair is symmetric and a caller writes neither a
  `throws` clause nor a try/catch for the common path.
- **Both writers take a sink, and `toTson` is that method over a `StringBuilder`.** `write(value,
  OutputStream)` / `write(value, Appendable)` mirror every reader taking an `InputStream`: `TsonDataEmitter`
  holds an `Appendable` rather than its own `StringBuilder`, so nothing between the object graph and the
  sink accumulates the document — memory is the sink's business plus the emitter's scope stack. The stream
  is UTF-8 ([TSON-DATA] §9.1), **flushed and not closed**: unflushed, the encoder's own buffer swallows a
  short document whole, and closing would end the HTTP response body this exists for. An `IOException` from
  the sink becomes an `UncheckedIOException` — the same treatment `Lexer` gives a failing `InputStream`, and
  deliberately *not* `TsonWriteException`, which means "this value cannot be written as TSON". That
  distinction needs `TsonObjectWriter`'s two `catch (Throwable)` handlers to let it past, or an IO fault
  surfaces blaming the object.
- **A writer can emit a document header, and it is off by default.** `TsonDataEmitter` gained `documentId`/
  `schemaRef` (the two of §3.3's four directive names that belong to a *data* document; `meta`/`import` are a
  schema document's and this emitter does not write one), and both writers a `describing(...)` derivation
  over a shared `TsonDocumentHeader` carrier that knows §2.2's order — `!!id` first when both are present.
  **Default output is unchanged**, deliberately: emitting a directive by default would rewrite every
  document this library has ever produced, `tson validate --output tson` included.
  - **The object writer takes the schema *and* the root type; the tree writer takes only the schema.** A
    bound object carries neither fact — the schemaless writer emits a type-ref only where a value would not
    read back without one — so `!!schema` alone yields a document whose own reader says "declares a
    !!schema but has no root type-ref to select a type". Half self-describing is not self-describing, so
    there is no one-argument form on `TsonObjectWriter` to get it half right. A tree records each node's
    type on a schema-driven read, so the root's own `!typeName` is written back with it; a root that has
    none is refused rather than half-written.
  - **The root type-ref is not part of `TsonDocumentHeader`**, however adjacent the two look on the wire: §2.2
    is explicit that header directives are properties of the *document*, and the root value's type
    annotation is not one of them. `TsonObjectWriter` holds it separately.
  - **`typeRef` now refuses a second type-ref on one value**, which is what makes the root type safe to
    declare: `data-value = *annotation [type-ref] core-value` admits exactly one, and a value that writes
    its own (a vocabulary host type, a union member) would otherwise produce a document that does not
    parse. The flag clears the moment a core-value starts, so nested values and annotation values are
    unaffected. Like the directive URI check beside it, this keeps "a writer cannot emit a document that
    will not read back" true.
- **`TsonDocumentHeader.peek` reads a header and stops**, which is the same carrier from the reading end —
  [TSON-DATA] §7.1's "at most two directives of lookahead and no value parsing, so streams, previews, and
  content sniffers can classify a document from its opening bytes". A caller routing to the right schema
  version has to know what a document names *before* choosing how to read it, and every other public entry
  point reads the whole document to answer that. `peek` takes a `String` or an `InputStream`, runs the same
  `Lexer`/`TsonDataStream` cursor the real read runs (`TsonDataStream.peekHeader`, static over a fresh
  stream — the scan leaves the cursor mid-document, so no half-read stream is ever handed back), and never
  touches the value.
  - **`!!meta` classifies rather than fails.** `ensureStarted` throws `TsonUnsupportedDocumentException` on
    it because a *data* stream cannot go on; a peek exists precisely to say "schema document" and answers
    with `meta()` present (§12.1 requires exactly one, so `isSchemaDocument()` is that question). `tson
    validate`'s file classification is this call.
  - **What it will not do is guess — and that makes it total.** A malformed *value* is not its business and
    still yields a header; a malformed *header* yields the directives read before it went wrong rather than
    throwing, and a directive §2.2 does not admit there stops the scan. The read that follows is where a
    malformed document earns a real diagnostic, so a peek loses nothing by staying silent, where a throw
    would force every caller sniffing arbitrary bytes to wrap it. The one answer it must never give is a
    schema the document does not name, so a `!!schema` written inside the value or after it is that value's
    text and nothing more — `TsonDocumentHeaderTest` is adversarial about it. An `UncheckedIOException` does
    propagate: the *source* failed, which is not a verdict on the document.
  - **A peeked `InputStream` is not rewound**, so a source that can be read twice is peeked on one and read
    on the other — which is what `tson validate` does, re-opening each file it classifies.
  - **`peekResumable` is the one-shot-stream form**, for an HTTP request body, a socket, a pipe: the routing
    decision needs the header, the body is gone once read, and there is no second stream to be had. It
    records every byte the peek pulls off the source and returns a `TsonDocumentPeek` whose `document()` is
    that prefix in front of the rest — the document from its **first** byte, header directives included, so
    the reader that follows sees exactly what it would have seen had no one peeked. Recording at the
    *source* rather than at the token cursor is what makes the replay exact: the decoder reads ahead in
    chunks, and bytes it pulled but never tokenised are recorded too. Memory is that read-ahead, not the
    document — a test pins the pull under 64 KB for a 500 KB body.
- **`quotedString` escapes with a comparison, not a `Pattern`.** The escape loop runs once per character of
  every string a writer emits, and asking `c <= 0x1f` through a compiled `Pattern` cost a `String`, a
  `Matcher` and the matcher's own internals *per character* — 188 bytes against 3.7 for the whole write,
  measured, and 13% of sampled allocation in a demo server's profile. `isControl(char)` is that comparison
  and the pattern is gone; `AllocationHarnessTest.writingAQuotedStringDoesNotAllocatePerCharacter` fails at
  anything approaching the old cost. `String.format("\\u%04x", …)` on the branch it guards stays — that
  branch is genuinely rare, and the loop around it is what mattered.
- These live in `tson-compiler`'s root package (not a separate module) because `DefinitionResolver`
  depends on `TsonObjectWriter` (atom-refinement merging) — a module depending *on* `tson-compiler`
  couldn't provide them without a cycle. `tson-bind` (what they're built on) has no such dependency.

## Tree model: `TsonValue` (`tson-tree` module)

What every tree read hands back — the compiled tree readers (`docs/linking-and-compilation.md`) and the
schemaless `TsonTreeReader` alike. A sealed `TsonValue` over eight pure immutable node types (`TsonRecord`/
`TsonMap`/`TsonArray`/`TsonTuple`/`TsonAtom`/`TsonAbsent`/`TsonMissing`/`TsonScopedValue`),
**structure-preserving** — TSON's
record-vs-map and array-vs-tuple distinctions survive into the model, where JSON's would collapse — and
annotation-aware, every node carrying its own `typeRef()` and `annotations()`.

- **`TsonScopedValue` is a wrapper because the directive belongs to the position, not to the value.**
  [TSON-DATA] §2.3's grammar is `scoped-value = [ schema-directive ws ] data-value`: the same record means
  the same thing with or without one, so a nested `!!schema` ([TSON-SCHEMA] §7.8's scope push) attaches
  *around* the value rather than as a ninth component on each of the other seven. That is the argument
  `TsonDocument` makes at document level, and the reason the two are separate types rather than one — a
  document also carries `!!id`, is not itself a value, and cannot stand at a field position.
  - **Transparent to navigation.** Every kind predicate, accessor and step delegates to the value it
    governs, so `tree.at("/attachments/0/claim_id")` reads the same whether or not a scope was pushed, and
    a consumer that does not care about scopes never unwraps one. Asking for the scope is what surfaces it:
    `v instanceof TsonScopedValue s` then `s.schema()`.
  - **Only a genuine push produces one.** A value whose type came from the governing namespace carries no
    directive and is read as its own node. So a tree round-trips through `TsonTreeWriter` with its
    directives exactly where the author put them — the writer emits the scope first and then the value it
    governs, §2.3's own order, which is also why the scoped case sits ahead of `writeNode`'s switch rather
    than in it: every branch of that switch is already past the annotations, and a directive precedes them.
  - **Bind mode has no counterpart**, deliberately: a bound object has nowhere to carry a URI and inventing
    somewhere would change what a consumer's own class means. `TsonAbsent` makes the same asymmetry for
    §2.9.

- **`TsonDocument` is the model's document, and `TsonValue` stays a pure value.** [TSON-DATA] §2.2 —
  "Header directives are properties of the document, not of the body's root value" — is why the header is a
  wrapper rather than two more components on every node, and it makes the tree model the counterpart of the
  parser's own `ast.Document(id, schema, root)` rather than a value model with a hole where the document
  should be. It needs no dependency, so `tson-tree` still requires nothing.
  - **No `meta` component**, deliberately: a document carrying `!!meta` is a *schema* document, whose value
    model is `schema.meta`. `TsonDocumentHeader` is the type that holds all three, and it answers a different
    question — classifying a document from its opening bytes (`isSchemaDocument()`) before deciding how to
    read it. Same reason `ast.Document` carries only the two.
  - **`readDocument` sits beside `read`, and `read` is untouched.** The wrapper's one real cost was said to
    be changing what a read hands back and every caller with it; that is a cost of *replacing* `read`, not of
    the wrapper. `TsonTreeWriter.toTson(TsonDocument)` closes the loop from the other end, the document's own
    directives winning over the writer's component by component and only where it has one — so reproducing a
    document reproduces it, while a writer configured for something the document does not state still
    contributes it.
  - **`TsonObjectDocument<T>` is the object side's own**, and deliberately not the same type: it needs a
    fourth component, `rootType`, because a `TsonValue` carries its own `typeRef()` and a bound object
    carries nothing. Two arities are not siblings, so they are not named as such.
    - **What it carries is what the *read* established.** The class plus its bind context already fix which
      schema governs an object — one context per schema version is the design — so `schema` is the weakest
      of the three. The other two are not recoverable from anything the caller holds: `!!id` is
      per-document data (§2.2 makes it a property of the document, so a class modelling it as a field would
      misstate its own shape), and `rootType` is a name a `DataNameBinder` cannot hand back, mapping name to
      class where a profile lets one class serve several shapes.
    - **Which is why `describing(schemaUri, rootTypeName)` takes two arguments** where the tree writer's
      takes one. That is not residue of the reader dropping something — the name genuinely cannot be
      derived — but a document carries both, so `writer.toTson(document)` replaces restating at the call
      site what the read had just worked out.
    - A schemaless read leaves `rootType` empty rather than guessing from a wire type-ref it never checked,
      and a hand-assembled document naming a schema with no type is refused at write: the pair is what makes
      a document self-describing, and the directive alone leaves a reader with a schema and no way to pick a
      type from it.
- **`TsonAtom.toString()` renders its value alone, and that is load-bearing.** A reader reporting on a
  decoded value stringifies whatever it decoded, and in tree mode that is a `TsonAtom` — so the record's own
  default rendering would reach a `Diagnostic`'s `expected`/`actual`, the two fields that exist precisely so
  a consumer needn't parse the message, and the message itself wherever a reader interpolates a value
  (`RecordAbstractReader.verifyFixed`, `ArrayAbstractReader`'s `unique_items`). The type-ref and annotations
  stay reachable through the accessors. Composites keep the record default: rendering one as TSON text is
  `TsonTreeWriter`'s job, encoding and lossy spots and all.
- **The names are chosen against Jackson, not in a vacuum.** No node carries a `Node` suffix, because
  Jackson ships `ArrayNode`, `NullNode` and `MissingNode` — a consumer using both libraries in one file
  would otherwise fully qualify every one. The sealed shape independently matches JEP 540's
  `JsonValue`/`JsonObject`/`JsonArray`/`JsonNull` (Simple JSON API, incubating in JDK 28), with
  `TsonRecord` + `TsonMap` staying *more* precise than `JsonObject`, which cannot distinguish the two.
- **Navigation is lenient but not silent.** `get`/`at` never throw, and the `TsonMissing` they return
  carries `path()` — the RFC 6901 pointer of the step that *failed*, relative to the node navigation
  started from — so `at("/a/b/c")` distinguishes "no `b`" (`/a/b`) from "`b` had no `c`" (`/a/b/c`). Every
  missing comes from a navigation step, so there is no singleton and equality is by path; read it without a
  cast via `TsonValue.missingPath()`. The first failure sticks — stepping on past a missing returns the
  same node rather than extending its pointer. "Missing" (not in the tree) and "absent" (written, but
  holding no value) stay distinct kinds.
- **There is one no-value node, `TsonAbsent`, because there is one no-value spelling.** Two things land on
  it: the `_` sentinel, and the placeholder a tree reader leaves where a read failed in collecting mode,
  whose story is carried by its diagnostic rather than by the node standing in for it. `null` is not one of
  them — §4 resolves boolean, number and string, so the unquoted token is a `TsonAtom` holding the string
  `null`, schemaless and under a schema alike, and it round-trips through `TsonTreeWriter` as the string it
  is. A JSON document's `null` reaches absence through a JSON reader, which maps it in the model, where the
  position's own state decides whether absence is admitted at all.
- **A `void` position admits `_` and nothing else** (`VoidReader`), which is where a second spelling would
  be cheapest to admit — the type has one inhabitant, so conceding loses no distinction — and it is refused
  there too. Conceding would make absence's spelling depend on the position's type, a rule an author
  computes rather than remembers.
- **Two families of value accessor, and the split is the point.** `as(Class)`/`asString`/`asNumber`/
  `asBigInteger`/`asBigDecimal` only ever **cast** (`isInstance`), so they answer "what host type did the
  read produce?" — an `int32` field holding an `Integer` gives empty from `asBigInteger()`. `asInt`/
  `asLong`/`asDouble` (`OptionalInt`/`OptionalLong`/`OptionalDouble`, so a hot path doesn't box)
  **convert**, and answer "what number is this?" regardless of host type. Conversion is exact for the
  integral pair — an integral fractional part converts (`123.0`, `234.56E2`), a real one doesn't, and
  out-of-range yields empty rather than wrapping — while `asDouble` accepts nearest-double rounding
  (demanding exactness would reject `0.1`) but rejects a magnitude that can't be finite, so nothing ever
  reads back as `Infinity`. Text is never parsed: `"42"` is a string per §4.4. A test asserting *which*
  host type a reader produced must therefore use `as(Class)`, not `asInt()`.
- **Read-side only, deliberately, and deferred until required.** There are no copy-on-write transforms and
  no builders — `TsonRecord.with`/`without`, `TsonArray.with`/`plus`/`without`, `TsonRecord.builder()`, a
  pointer-based `set("/a/b", value)` — and construction is the static `of(...)` factories. All of it would be
  pure `tson-tree` work with no compiler dependency, so the module is ready for it; what is missing is a
  concrete produce/edit use case, and `TsonTreeWriter` already closes the read→edit→write loop without one.
  **JEP 540 reached the same conclusion independently**, which is what makes this a decision rather than a
  shrug: it ships no transformation API and no builders at all, construction being static `of(...)` factories
  as here, and its Risks section defers the area outright — "During the incubation period, we will gather
  more information about use cases involving generating and transforming JSON documents, in order to evolve
  these areas of the API."

## Front door: `Tson`/`TsonConfig` (`tson` module)

A small module over `tson-compiler`, the consumer entry point. `Tson.builder().build()` bootstraps
meta-kernel/meta.tn/core.tn into a governed environment and returns an immutable `Tson`.

```java
Tson tson = Tson.builder().build();
tson.resolve(schemaText);                      // registers the schema by its own !!id
TsonValue value = tson.treeReader().withSchema(schemaId).readAs(dataText, "my_type");
```

- **Two schema sources ship, and `TsonConfig` carries the short form of each.** `TsonHttpSchemaSource`
  fetches over HTTPS under a host allow-list; `TsonFileSchemaSource` reads from a directory. `httpSchemas(…)`
  and `fileSchemas(host, dir)` are the one-call forms, repeatable and accumulating into one source;
  `schemaSource(…)` stays the general seam and the three are mutually exclusive, on the precedent
  `bindings`/`dataBindContext` already set — each builds one source, so mixing them would drop one rather
  than compose it. A deployment needing both writes the composition itself, where the order it tries them in
  is stated rather than assumed.
  - **Identity is not location, and that is what makes two sources one design** ([TSON-DATA] §2.2.1). A
    reference's identity is its lowercase host plus path — the scheme "a transport hint, not part of the
    name", no port, no userinfo, no fragment — so `https://schemas.example.com/order-1.tn` may legitimately
    be served from a directory, and moving a schema between the two renames nothing. `SchemaReference`
    holds those rules once, for both: two sources enforcing them separately is two places for one to drift
    lenient, and this is a security check.
  - **The reference is attacker-controlled**, since a document names its own schema and in a server that
    string came out of a request body. Both deny by default and match a host exactly (a suffix test for
    `.example.com` also matches `evil-example.com`). Beyond that they guard different primitives: the HTTP
    one is an SSRF risk, so it never follows redirects and caps size against bytes delivered rather than
    `Content-Length`; the file one is an arbitrary-read risk, so containment is checked **after**
    `toRealPath`, which settles `..` and symlink escape together — checking the unresolved path is the usual
    way that control is defeated.
  - **`TsonSchemaFetchException` is the contract, and it lives in `tson-compiler`** beside the interface it
    belongs to rather than beside the two sources that throw it — `SchemaFailure`, which has to route on it,
    is in that module and cannot see a type declared in `tson`. A source signals "cannot supply this" with
    that and nothing else, so a read can tell an unfetchable schema from a broken invariant by type; anything
    else out of a source is that source malfunctioning and propagates as itself. `Reason` is the part worth
    acting on: `NOT_PERMITTED` is policy and no retry helps, where `TIMEOUT`/`TRANSPORT` say the reference
    was fine and the world was not.
  - **A `null` return is not a second way to say it, and is refused where the loader calls a source.** It
    carries no `Reason`, so a deployment refusing a reference and a host that did not answer would arrive
    indistinguishable — and unguarded it surfaced as a `NullPointerException` several frames inside the
    registry, nowhere near the source that caused it. `TsonCompiledMetaRegistry.fetch` raises an
    `IllegalStateException` naming the source and the rule, which keeps it a *fault*: a broken source is the
    deployment's bug, not a verdict on the document that happened to name the schema, so `SchemaFailure`'s
    default rethrows it. Treating `null` as a miss instead would make the wrong spelling work and hide every
    later one.
  - **`TsonSchemaSource.ofMap` is the third shipped source, and exists because the trap above has one
    author.** `schemaSource(schemas::get)` is the natural first implementation — it compiles, serves every
    identity in the map, and returns `null` for the rest, which the document chooses. `ofMap` is that lookup
    done to contract: a miss is `NOT_FOUND` (this source had somewhere to look, where `registeredOnly`'s
    `NOT_PERMITTED` means nothing was looked for), and lookup is **by canonical identity**, so a reference
    carrying a `?sha256=` pin finds the entry registered without one. That last part is the half a raw map
    lookup gets wrong silently: it fails only for documents that pin, which are the ones written where
    integrity is taken seriously. Two keys canonicalizing alike are refused rather than collapsed, and the map
    is copied.
  - **Neither verifies the `?sha256=` pin or the fetched document's `!!id`** — the loader does both, after a
    source returns, and a second implementation would only drift from it. What the loader cannot express is
    *requiring* a pin, since it verifies only one that is present; `requireContentHashPin` is that.
  - **Caching is by canonical identity and never re-checked**, which rests on §10's immutability rule rather
    than on the transport: a file edited in place is not seen, and under §10 editing it was the mistake. A
    cached entry survives its file being removed, which is why the file source's policy check touches no
    filesystem. Policy is re-checked on every reference, cached or not — a hit skips the fetch, never the
    allow-list.
- **The read mode is which registry you hold:** `treeRegistry()` (an immutable, queryable `TsonValue` tree)
  and `bindRegistry()` (real Java objects, bound via `dataBindContext()`), both over one shared bind-mode
  resolution core.
  `resolve(schemaText)` resolves/links/registers and takes *no* mode — resolution is always object-binding
  internally (it binds meta instances to `schema.meta.Top`), and only a registry's own `compile`/`get`
  picks a mode.
- **`validate(String|InputStream)` *is* `treeReader()` with a collecting receiver**, both halves of it —
  one try/catch over one call, no second implementation. The reader already works out whether a schema
  applies (a `!!schema` directive selects the schema through `TsonConfig.schemaSource`, compiled once in
  tree mode, and the root type-ref selects the type; with no `!!schema` it reads schemalessly, checking
  the wire's own type-refs), and reports every failure around all of that through the receiver. Validating
  is that read with the tree thrown away. Returns every problem as a `List<Diagnostic>` (empty means valid)
  and **never throws for a bad input document** — malformed syntax, a schema document handed in where data
  was expected, an unresolvable schema, an unknown type all come back as diagnostics.
  - **The `InputStream` overload is the body; the `String` one delegates into it.** The reader underneath
    decodes UTF-8 bytes, and the CLI already holds a stream. (The old direction buffered stream→`String`
    only because a separate schemaless branch had to be re-readable.)
  - Base-syntax failures are converted by **`Diagnostic.ofBaseSyntaxError`** — in the root package because
    two of the three exception types live in the unexported `lexer` package, so `Tson` in another module
    can't name them in a `catch`. It returns a `Diagnostic` and **rethrows anything else**: "never throws
    for a bad *document*" is not "never throws", and laundering a library fault into a diagnostic would
    report a false verdict and bury the stack trace. (`tson validate` puts that verdict back on — a
    `BACKLOG.md` item.)
- `objectReader()`/`treeReader()` return **schema-aware** `TsonObjectReader`/`TsonTreeReader` over this
  instance — the value-returning read peers of `validate`: a self-describing document is validated against
  its declared `!!schema` (schemaless when it declares none), the object form checking the target class up
  front. **Both are built over this instance's own `treeRegistry()`/`bindRegistry()`, so every reader shares
  one compiled-schema cache** — a schema compiles once per `Tson`, not once per reader. The readers take a
  `TsonCompiledSchemaRegistry` rather than a `TsonCompiledMetaRegistry` for exactly that reason; since the
  read mode isn't visible in the registry's type, each constructor checks a package-private `mode()` and
  rejects the wrong one up front instead of failing on a cast at the first value. `objectReader()`/`objectWriter()` bind to this instance's `dataBindContext` (configurable via
  `TsonConfig.dataBindContext`, default `TsonAtomContext.defaultContext()`). `schemaRegistry()`/`loader()`
  reach the underlying machinery.
- **`bindings(Map)`/`profile(String)` are the short form of `dataBindContext`**, and mutually exclusive with
  it (a profile is fixed when a context is built, so it cannot apply to one that arrives already built). The
  map becomes a `DataNameBinder` chained over `SchemaMetaNameBinder.INSTANCE` with
  `TsonAtomContext.registerDefaults` applied — the last being the step nothing reminds a caller of, and the
  reason the convenience earns its place. **The map authors the failure**: a name outside it reports
  `bindings(...) maps [...]` with the kernel's own account as the cause, because the chain is a backstop and
  letting the backstop speak reports a missing line of the caller's configuration as "not kernel vocabulary".
- **A `Tson` is one profile, and the schema being read never picks it.** Routing a document to the right
  profile stays the application's job. The alternative — the schema declaring its own profile through a
  meta-layer annotation — links a *coding* decision to a *format* one and buys less flexibility than it
  costs, since the application then cannot bind one schema two ways. Selection is by an opaque label for the
  same reason it is not by matching the schema's field set: no serialization library does that, and the
  parameter names it would need are not retained for a secondary constructor. Reconsider only if something
  needs to re-derive the binding without the application in between.
- **Two binding seams, never merged.** `TsonConfig.dataBindContext` binds the *data* a schema describes
  (`order` → `Order`); `TsonConfig.metaNameBinder` binds a governing meta's own *vocabulary*
  (`operation` → `Operation`, the `data` base kind's case — `docs/linking-and-compilation.md`). One
  namespace holding both would collide the first time a schema type and a meta-layer constructor shared a
  name. The meta binder is composed over `SchemaMetaNameBinder.INSTANCE` rather than replacing it, so what a
  consumer supplies adds names and gives up nothing: the standard library still compiles in object-binding
  mode, which is the thing the internal context is fixed to protect, and every kernel name still wins.
