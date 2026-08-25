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
`preservingUnknownTypeRefs()` relaxes the schemaless type-ref rules below, and
`withSchema(uri).readAs(source, typeName)` covers data that *isn't* self-describing — the caller supplies
what a `!!schema` plus a root type-ref would have said, and validation is identical either way. Each returns
a new reader **sharing** the original's compiled-schema registry, never rebuilding it. A
`TsonTypeReader` from a compiled schema is the layer underneath: a strict single-method interface that
reads one value at a cursor and polices nothing around it.

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
  (`spec/tson-rev33-changelog.md` #7, whose suggested resolution this is). `preservingUnknownTypeRefs()` on either
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
  nothing can interpret, and rejecting its innards would take that back. This is deliberately stricter than
  Class 2 conformance requires, which asks for nothing at all here (`spec/tson-rev33-changelog.md` #29).
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
schemaless `TsonTreeReader` alike. A sealed `TsonValue` over seven pure immutable node types (`TsonRecord`/`TsonMap`/`TsonArray`/
`TsonTuple`/`TsonAtom`/`TsonAbsent`/`TsonMissing`), **structure-preserving** — TSON's
record-vs-map and array-vs-tuple distinctions survive into the model, where JSON's would collapse — and
annotation-aware, every node carrying its own `typeRef()` and `annotations()`.

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
- **There is one no-value node, `TsonAbsent`, and no separate null node.** Three things land on it: the
  `_` sentinel; the `null` token where §4 base type resolution applies (schemaless data and `value`-typed
  positions, the one place `null` still identifies as a value at all); and the placeholder a tree reader
  leaves where a read failed in collecting mode, whose story is carried by its diagnostic rather than by
  the node standing in for it. The model deliberately does **not** carry §4.1's null base type as its own
  kind: `TsonAbsent` is what a consumer asks about, and a JSON-shaped `null` reaching a tree read means
  the same thing `_` does. Two consequences worth knowing — a schemaless `null` re-emits as `_` through
  `TsonTreeWriter` (absence has one written form), and a `_` map key and a `null` map key share one
  identity in `SchemalessTreeReader`'s duplicate check, an extension of the decoded-vs-textual divergence
  `spec/tson-rev33-changelog.md` #43 already argues.
- **None of this reaches a schema-typed position.** [TSON-SCHEMA] §7.3: `null` "has no special status when
  a schema is in scope — [its] meaning is determined entirely by the position's type", so `null` at a
  `text` position is the string `"null"`. The sole exception is a `void`-typed position, where `VoidReader`
  accepts an unquoted `null` as a spelling of `_` (a single-inhabitant type loses no distinction by it);
  that acceptance lives in that one reader, deliberately, and not in the token stream, so no other atom
  contract — `enum`, `token`, a FIXED `text` — loses `null` as a legal value.
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
- **Read-side only, deliberately.** There are no copy-on-write transforms and no builders; construction is
  the static `of(...)` factories. `TsonTreeWriter` already closes the read→edit→write loop, so an editing
  API waits on a concrete produce/edit use case (`BACKLOG.md`).

## Front door: `Tson`/`TsonConfig` (`tson` module)

A small module over `tson-compiler`, the consumer entry point. `Tson.builder().build()` bootstraps
meta-kernel/meta.tn/core.tn into a governed environment and returns an immutable `Tson`.

```java
Tson tson = Tson.builder().build();
tson.resolve(schemaText);                      // registers the schema by its own !!id
TsonValue value = tson.treeReader().withSchema(schemaId).readAs(dataText, "my_type");
```

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
- **Two binding seams, never merged.** `TsonConfig.dataBindContext` binds the *data* a schema describes
  (`order` → `Order`); `TsonConfig.metaNameBinder` binds a governing meta's own *vocabulary*
  (`operation` → `Operation`, the `data` base kind's case — `docs/linking-and-compilation.md`). One
  namespace holding both would collide the first time a schema type and a meta-layer constructor shared a
  name. The meta binder is composed over `SchemaMetaNameBinder.INSTANCE` rather than replacing it, so what a
  consumer supplies adds names and gives up nothing: the standard library still compiles in object-binding
  mode, which is the thing the internal context is fixed to protect, and every kernel name still wins.
