# CLI, configuration, bundled schemas, and content hashing

Design notes for the outer ring: the bundled schema resources, content hashing, the `tson` command-line
application, and the configuration package. Current form only; history lives in git. `CLAUDE.md` holds the
one-paragraph orientation; this file holds the detail.

## Bundled schema documents (`tson-schema/TsonBundledSchemas.java`)

The published identities of the three bundled schemas (`META_KERNEL_ID`/`META_ID`/`CORE_ID`) **and** their
raw source text (`fetch(uri)`), off `tson-schema`'s classpath — the `.tn` resources are copied from
`spec/m/` at build time (`processResources`), so there's one copy on disk to keep in sync with the spec.
`fetch` doesn't implement `TsonSchemaSource` (that would need a `tson-compiler` dependency), but a
`tson-compiler` caller passes the method reference `TsonBundledSchemas::fetch` directly (it's a functional
interface of the same shape). Each schema also ships a published content digest
(`{META_KERNEL,META,CORE}_SHA256`), checked against the packaged resource on load.

## Content hashing (`TsonContentHash`) + `tson hash`

`TsonContentHash.sha256(byte[])` computes a document's content hash over every byte *past the first line's
terminator* (the `!!id` line, grammar-required first, is excluded so a document can carry its own hash on
its own id line without circularity; a leading BOM is stripped, never hashed). `TsonContentHash.verify(bytes,
referenceUri)` parses a reference's `?sha256=` pin (§2.2.1: only hash-algorithm parameters, never silently
retained — an unrecognized query parameter or malformed hex throws) and checks it, throwing
`TsonContentHashMismatchException` on mismatch. Both carry the `Tson` prefix as consumer-facing types whose
bare names (`ContentHash`, a `hash` mismatch) a consumer plausibly has their own version of — and "content
hash" is the spec's own term throughout §2.2.1/§10.2, never shortened to "hash".

- **The pin is verification metadata, not identity** — a pinned `!!id` resolves against a plain (or
  differently-pinned) reference because everything keys by canonical identity (scheme+query stripped).
- **Verification is wired through the loader:** every fetched reference carrying a pin is checked before
  use — the top `!!schema` and each transitive `!!import`/`!!meta`. The bundled chain is pinned end to end
  (meta.tn pins meta-kernel, core.tn pins meta.tn), so bootstrap verifies the whole chain. The one
  identity that can never carry a pin is meta-kernel's self-`!!meta` (its hash input would have to contain
  the pin) — its `!!id` is pinned, its self-`!!meta` stays plain, and identity comparison is canonical so
  the two still name one identity. `crossCheckId` additionally verifies a fetched document's embedded
  `!!id` equals the reference's identity (§2.2.1 — a source can't return content under the wrong
  identity).
- **`tson hash <file>`** stamps `?sha256=<hex>` onto the `!!id` in place (idempotent; the hashed bytes
  never change so the pin stays valid).

## CLI (`tson-cli`)

`tson validate [--output text|json|tson] <file|->...` takes a **flat list of files**, auto-classifies each
as schema or data (a header peek — `!!id`-carrying schema vs `DocumentStart` data), exposes the schema files
through a `TsonSchemaSource`, and validates each data document via `Tson.validate` — the `!!schema` URI
selects the schema, the root type-ref selects the type, no `!!schema` means schemaless. **Fully
self-describing: no `--type`.**

**`-` is standard input, at most once, and always a data document.** `ValidateInput` is the sealed argument
type (`OfFile`/`OfStdin`) that keeps this out of `Path`-with-a-magic-value territory; its `OfStdin.open()`
suppresses `close()`, since `System.in` belongs to the process rather than to one read. Piped input is never
*classified*, because classification opens a document a second time and a stream has nothing to reopen — so
schemas stay files, and that rule is a consequence of the design rather than a restriction bolted onto it. A
second `-` is a `UsageException`: one stream, consumed by the first read, so the second could only ever
report an empty document as valid. Only the bare argument matches, leaving `./-` for a file actually named
`-`. `cannotRead` names the failure kind (`NoSuchFileException` and friends carry it in the exception type,
not the message, so the obvious concatenation renders `cannot read x: x`). An unmatched `!!schema` lists
what the supplied files *declare* — matching is by embedded `!!id`, never filename (§2.2.1), so the common
failure is the right file with the wrong identity in it; `declaredIds` keeps the ids verbatim and in
argument order, deliberately apart from the lookup map whose keys are canonicalized.

**`CliDiagnostic` renders an absent field as absent.** `Diagnostic` spells "nothing to say" as `""` for its
strings and as an empty `Optional` for its positions, so the output used to show both `""` and `null` for
one idea; `schemaId`/`expected`/`actual` are now `Optional` and render `null`. **`path` and `schemaPointer`
stay plain strings**, because for an RFC 6901 pointer `""` is the *root*, not an absence — `Tson.validateSchema`
genuinely reports a document-level problem against it, and a base-syntax failure genuinely locates itself at
the data root. That `""` means both things is a `Diagnostic`-level overload, not a rendering one, and is
tracked in `BACKLOG.md` rather than papered over here. The facade owns the whole per-document decision; the
CLI just classifies files into a source and calls it. Also `tson compile <schema>` (checks a schema
compiles, tree mode), `tson hash <file>`, `tson init-example [<dir>]` (writes a working
`person.tn`/`person-data.tn`). The installed command is `tson` (`application.applicationName`), launched on
the classpath.

**`validate` emits one `ValidationRun` envelope per invocation, whatever the file count** — `{ valid, files:
[{ file, valid, errors }], errors }`, so `--output json`/`tson` is a single parseable document with each
verdict's filename *inside* it. Only `--output text` keeps the `# <file>` header (and only when there's more
than one file): a label outside the object is right for a person and is exactly what made the machine
formats unparseable. **The two error lists are the two exit codes**: run-level `errors` holds only what
stops the invocation before any document is read (an unreadable file during classification, a schema with no
`!!id`, no data files at all) and is exit 2, while a document that read but didn't validate lands in its own
`FileReport` at exit 1 — so a consumer tells "your invocation was wrong" from "your document was" without
reading messages. `compile` renders a bare `ValidationReport` instead, having one schema and nothing to
name. Every file's report is collected before anything prints, since the envelope's verdict is the AND
across them.

**Exit codes: 0 all valid, 1 any data file invalid** (bad value / unresolvable `!!schema` / unknown type /
no root type-ref), **2 usage/classification** (no data files, an unreadable/`!!id`-less schema, a bad
flag), **70 (`EX_SOFTWARE`) a fault in the library**, whose stack trace prints to stderr. That fourth code
is what makes `Diagnostic.ofBaseSyntaxError`'s rethrow worth anything: the read loop catches only
`IOException` (an unreadable file *is* that file's verdict), so a `RuntimeException` — which `Tson.validate`
raises only for a bug, never for a bad document — reaches `TsonCli`'s own handler instead of being folded
into "invalid". `UsageException` exists for the same reason one layer up: a bare `IllegalArgumentException`
catch would relabel a library fault as "your command line is wrong", so only this CLI's own argument
parsing throws the type that means that.

## Configuration package (`tson-compiler/.../config/`)

Holds `TsonAtomContext`, `SchemaMetaNameBinder`, `SourcePositionStringBridge` — how a caller configures a
working binding environment. **Two distinct default contexts, differing by exactly the name binder** (a
`DataNameBinder` is fixed at `DataBindContext` construction and can't be added later):

- `TsonAtomContext.defaultContext()` — the library's built-in atom registrations (`UUID`/`byte[]`/
  `LocalDate`/`OffsetTime`/`OffsetDateTime`/`URI`/`Inet4Address`/`Inet6Address`/`SourcePosition`), **no
  name binder**. The consumer/schemaless default (`Tson.dataBindContext`, `objectReader`/`objectWriter`,
  and the base a consumer layers their own binder onto). `registerDefaults(builder)` applies the same atom
  list to any builder, so the list lives in one place.
- `SchemaMetaNameBinder.defaultContext()` — those same atoms **plus** a `DataNameBinder` scoped to the
  `io.ltr8.tson.schema.meta` namespace. The library's *internal* object-binding-mode resolution context
  (binds meta instances to `schema.meta` classes). Not a consumer default — a consumer binding their own
  classes supplies their own binder.

Removing `config` entirely (folding these elsewhere) is a `BACKLOG.md` item.
