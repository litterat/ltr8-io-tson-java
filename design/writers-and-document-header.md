# Writers, document header, and `TsonDocumentPeek`

Design notes for `TsonTreeWriter`/`TsonObjectWriter` and the engines under them, the sinks they write to, the
document header a writer may emit (`TsonDocumentHeader`), and `TsonDocumentPeek`, which reads that header and
keeps the rest of the document. Current form only; history lives in git.

**Invariants**

- Annotations are written ahead of a value's type-ref (§7.4's `*annotation [type-ref] core-value`);
  `writeUnion` emits its member's annotations before its own type-ref and goes straight to `writeCore`.
- A sink is flushed and not closed; an `IOException` from it becomes an `UncheckedIOException`, deliberately
  not `WriteException`, and `TsonObjectWriter`'s two `catch (Throwable)` handlers must let it past.
- The byte path encodes UTF-8 itself (`Utf8Sink`) and refuses an unpaired surrogate where
  `OutputStreamWriter` silently writes `?`.
- A document header is off by default; the object writer takes the schema *and* the root type, the tree
  writer only the schema, and the root type-ref is not part of `TsonDocumentHeader`.
- `TsonDataEmitter.typeRef` refuses a second type-ref on one value — a writer cannot emit a document that
  will not read back.
- `TsonDocumentPeek` performs no second header scan and rewinds nothing; a continuing reader whose lexical
  policy (token policy, limits) disagrees with the peek's is refused.
- A peek is total: a malformed header yields nothing rather than throwing, never a schema the document does
  not name, and the failure it kept is reported by the read that continues.
- The resolver reaches for the `writer`-package engine (`DataClassObjectWriter`), never the facade.

Related: `design/facades-and-tree.md` (the read facades, annotation capture), `design/tree-model.md`
(`TsonValue`, `TsonDocument`, `TsonObjectDocument`), `design/front-door-and-config.md` (`Tson`),
`design/json-encoding.md`, `design/schema-resolution.md`.

## Writers: `TsonTreeWriter`/`TsonObjectWriter` (root package)

Wire annotations are captured by the reads `design/facades-and-tree.md` describes.

- **`TsonTreeWriter` re-emits them** — `TsonDataEmitter` gained `annotation`/`beginAnnotation`/
  `endAnnotation` (the valueless form's trailing space is load-bearing, §3.1) and `writeNode` writes a
  node's annotations ahead of its type-ref, per §7.4's `*annotation [type-ref] core-value` order, so a tree
  round trips with its metadata, not just its values. **`TsonObjectWriter` re-emits a carrier's too** —
  §7.4's order is why `write` splits into `write` (annotations, then the value) and `writeCore` (the shape
  switch): `writeUnion` writes a type-ref of its own, so it emits its member's annotations *before* that and
  goes straight to `writeCore`, rather than recursing and landing them after it. An annotation's value writes
  back in whichever form the read produced — a bound object like any other value, a structurally-kept
  `TsonValue` through `TsonTreeWriter`'s own node emission (package-private, both writers share a package).
- **`TsonObjectWriter.toTson` is mainly a debugging tool**, not a guaranteed-lossless serializer (integer
  width, tuple-ness, and captured wire annotations are documented write-side losses). Both throw unchecked
  (`ReadException`/`WriteException`, both `tson-base`'s and shared by every encoding), so the pair is
  symmetric and a caller writes neither a
  `throws` clause nor a try/catch for the common path.
- **Both writers take a sink, and `toTson` is that method over a `StringBuilder`.** `write(value,
  OutputStream)` / `write(value, Appendable)` mirror every reader taking an `InputStream`: `TsonDataEmitter`
  holds an `Appendable` rather than its own `StringBuilder`, so nothing between the object graph and the
  sink accumulates the document — memory is the sink's business plus the emitter's scope stack. The stream
  is UTF-8 ([TSON-DATA] §9.1), **flushed and not closed**: unflushed, the encoder's own buffer swallows a
  short document whole, and closing would end the HTTP response body this exists for. An `IOException` from
  the sink becomes an `UncheckedIOException` — the same treatment `Lexer` gives a failing `InputStream`, and
  deliberately *not* `WriteException`, which means "this value cannot be written in this encoding". That
  distinction needs `TsonObjectWriter`'s two `catch (Throwable)` handlers to let it past, or an IO fault
  surfaces blaming the object. Both writers take `write(value, ByteSink|OutputStream|Appendable)`, so a
  document never has to exist as a `String`.
- **A writer can emit a document header, and it is off by default.** `TsonDataEmitter` gained `documentId`/
  `schemaRef` (the two of §3.3's four directive names that belong to a *data* document; `meta`/`import` are a
  schema document's and this emitter does not write one), and both writers a `describing(...)` derivation
  over a shared `TsonDocumentHeader` carrier that knows §2.2's order — `!!id` first when both are present.
  **A bare value is the default**, because that is what a writer is usually asked for — not to protect
  output already in the world, of which there is none. A caller who wants a self-describing document says so
  (`describing(schemaUri[, rootType])`/`identifiedBy`).
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
- **The byte path encodes UTF-8 itself.** `TsonDataEmitter` takes a `ByteSink` as well as an `Appendable`,
  and the sink form runs through `base.io`'s `Utf8Sink` rather than an `OutputStreamWriter` — the mirror of
  the lexer decoding UTF-8 off a `ByteSource`. Three things follow: a document reaches a `ByteBuffer` or a
  channel and not only an `OutputStream`; the block is `ByteSink.block()`'s to size rather than the JDK
  encoder's; and an unpaired surrogate is **refused** where `OutputStreamWriter` silently writes `?`, which
  is a character nobody wrote appearing in a document whose identity may be a hash of its bytes. The
  `Appendable` path is unchanged and is what `toTson` uses — chars into a `StringBuilder` have nothing to
  encode.
  - **A sink is closed by whoever built it, and closing is not flushing.** `write(value, OutputStream)`
    builds a `ByteSink` and closes it (a no-op — the stream is the caller's); `ByteSink.of(Path)` closes the
    stream it opened. The flush is separate and always explicit, because a sink cannot tell a caller who
    finished from one who abandoned the document part-written — so it never pushes on their behalf.
- **`quotedString` escapes with a comparison, not a `Pattern`.** The escape loop runs once per character of
  every string a writer emits, and asking `c <= 0x1f` through a compiled `Pattern` cost a `String`, a
  `Matcher` and the matcher's own internals *per character* — 188 bytes against 3.7 for the whole write,
  measured, and 13% of sampled allocation in a demo server's profile. `isControl(char)` is that comparison
  and the pattern is gone; `AllocationHarnessTest.writingAQuotedStringDoesNotAllocatePerCharacter` fails at
  anything approaching the old cost. `String.format("\\u%04x", …)` on the branch it guards stays — that
  branch is genuinely rare, and the loop around it is what mattered.
- **Each writer is a facade over an engine in the unexported `writer` package** — `TsonTreeWriter` over
  `TreeValueWriter`, `TsonObjectWriter` over `DataClassObjectWriter` — the split the readers already have,
  and for the reason `design/json-facades-binding-writing.md` states: a front door owns the *document* (its header, its root
  type-ref, the sinks it writes to) where an engine owns one value and contributes no framing. `AstWriter`
  and `AtomWriter` are engines too and live there beside them.
- **The resolver reaches for the engine, never the facade.** `DefinitionResolver`'s atom-refinement merge
  and `HeldBody`'s held template body both write a wire record and parse it straight back, where a header
  would be content the parse would then have to strip — so what they want *is* the engine's contract. It is
  also what keeps the direction honest: a resolver naming `TsonObjectWriter` would make `tson-compiler`
  depend on a front door built over itself, which is what used to pin the facades to this module.

## Reading a header: `TsonDocumentPeek`

- **`TsonDocumentPeek` reads a header and stops, and keeps the rest of the document** — [TSON-DATA] §7.1's
  "at most two directives of lookahead and no value parsing, so streams, previews, and content sniffers can
  classify a document from its opening bytes". A caller routing to the right schema version has to know what
  a document names *before* choosing how to read it, and every other public entry point reads the whole
  document to answer that. It runs the same `Lexer`/`TsonDataStream` cursor the real read runs — pulling the
  stream's first event, `DocumentStart`, which carries all three of §2.2's directives — and never touches the
  value: the stream fills only until it has an event, and the header alone produces one. **There is no second
  header scan**; the header is a projection of the one the stream performs for every reader. It is reached
  through `Tson.begin(…)`, or `TsonDocumentPeek.of(…)` standalone. `TsonDocumentHeader` itself is a pure value
  with no way to obtain one — reading a header means running the lexer, which is the stream's job.
  - **Nothing is rewound, because nothing is re-read.** The peek holds the live stream, positioned just past
    the header, and a reader continues on it: `objectReader().read(peek, Invoice.class)`, `treeReader()
    .read(peek)`, `readAs(peek, type)`. That is what makes it work on a source that cannot be read twice —
    an HTTP request body, a socket, a pipe — which is the case the whole surface exists for. There is no
    resumable/non-resumable pair, because every peek continues; a caller who only wants to classify takes
    `header()` and drops the peek, which is what `tson validate` does per file.
  - **Which reader continues is the caller's, and that is the point.** A schema version names a bind context,
    a bind context names a compiled registry, so v1 and v2 are two readers rather than one reconfigured
    (`PeekThenReadTest` drives exactly that, over a stream that fails the test if anything rewinds it). What
    may *not* differ is the lexical half of the policy — §8.2's token policy and §9.1's limits were applied to
    the tokens the header is made of — so a reader that disagrees is **refused**, not quietly obeyed. The
    receiver *does* move: the peek reads the header with a throwing receiver of its own and the continuing
    read re-points the token surface at its own, or a collecting read would throw at the first problem.
  - **`!!meta` classifies rather than fails**, at every layer. The stream reports the directive, and the
    Class 1 verdict is taken by whoever asked for a data read — `TsonDataParser.parseDocument` and both
    facades' `requireDataDocument`. A peek exists precisely to say "schema document" and answers with
    `meta()` present (§12.1 requires exactly one, so `isSchemaDocument()` is that question). `tson
    validate`'s file classification is this call.
  - **What it will not do is guess — and that makes it total.** A malformed *value* is not its business and
    still yields a header; a malformed *header* yields **nothing at all** rather than throwing — the header is
    built once, when the whole of it has been read, so a break part-way leaves no partial answer to hand back
    — and a directive §2.2 does not admit there stops the scan. The one answer it must never give is a schema
    the document does not name, so a `!!schema` written inside the value or after it is that value's text and
    nothing more — `TsonDocumentPeekTest` is adversarial about it. **The failure is kept, not discarded**: the
    read that continues reports it through that reader's own receiver, so a caller who peeks and then reads
    gets exactly the diagnostic a caller who only read would have, and `begin` is safe to call on arbitrary
    bytes. An `UncheckedIOException` does propagate: the *source* failed, which is not a verdict on the
    document.
  - **The header alone is buffered, not the document** — a test pins the pull under 64 KB for a 500 KB body,
    and then reads that body off the same peek.
