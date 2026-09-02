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
- **`scripts/restamp-bundled-schemas.sh`** is that stamping applied to the whole bundled chain, which no
  single-file command can do: a digest is over a document's own bytes, so an edit to meta.tn moves meta's
  `!!id`, core.tn's `!!meta`, core's own `!!id`, `TsonBundledSchemas`' three constants, and the
  getting-started example's pins in `README.md`/`InitCommand.java` — the example pinning meta and core, its
  own digest moves with theirs. The script walks that order bottom-up; `--check` reports staleness and
  writes nothing. It hashes with `tail -n +2 | shasum -a 256` rather than calling `tson hash`, so it runs
  when the tree does not compile. Only a reference already carrying `?sha256=` is re-stamped: pinning is
  optional, and an unpinned mention in prose is not a pin that has gone stale.

## CLI (`tson-cli`)

`tson validate [--output text|json|tson] <file|->...` takes a **flat list of files**, auto-classifies each
as schema or data (`TsonDocumentHeader.peek` — a header carrying `!!meta` is a schema document), exposes the
schema files through a `TsonSchemaSource`, and validates each data document via `Tson.validate` — the
`!!schema` URI selects the schema, the root type-ref selects the type, no `!!schema` means schemaless.
**Fully self-describing: no `--type`.**

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

**`CliDiagnostic` renders an absent field as absent.** `Diagnostic` spells "nothing to say" as `""` for
`schemaId`/`expected`/`actual`, so those are narrowed to `Optional` here and render `null`. **The two RFC
6901 pointers need no narrowing** — they are `Optional` at the source, because for a pointer `""` is the
*root*, not an absence: `Tson.validateSchema` genuinely reports a document-level problem against it, and a
base-syntax failure genuinely locates itself at the data root. `--output json` and `--output tson` both keep
a present `""` and an absent pointer apart; `--output text` renders them alike, deliberately, since `": msg"`
is noise to a person looking at the whole document either way. The facade owns the whole per-document decision; the
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

**Exit codes: 0 everything checked and nothing reported, 1 checked and rejected** (bad value / unknown type
/ no root type-ref, and a §8.2 refusal — the processor looked and declined, and the sender holds the fix),
**2 usage/classification** (no data files, an unreadable/`!!id`-less schema, a bad flag), **69
(`EX_UNAVAILABLE`) a schema nothing would supply and a rerun would not either**, **75 (`EX_TEMPFAIL`) a
schema that could not be reached, where a rerun might**, **78 (`EX_CONFIG`) a type the schema needs with no
Java class in this tool**, **70 (`EX_SOFTWARE`) the library failing to reach a verdict at all**. Everything
but 0, 1 and 2 is the run declining to give a verdict rather than giving a bad one, and each names who could
not give it: 69/75 whoever was to serve the schema, 78 whoever wired the reading application, 70 this
library. That is why 1 is not enough — and 70 in particular is what
makes `Diagnostic.ofBaseSyntaxError`'s rethrow worth anything: the read loop catches only
`IOException` (an unreadable file *is* that file's verdict), so a `RuntimeException` — which `Tson.validate`
raises only for a bug, never for a bad document — reaches `TsonCli`'s own handler instead of being folded
into "invalid". `UsageException` exists for the same reason one layer up: a bare `IllegalArgumentException`
catch would relabel a library fault as "your command line is wrong", so only this CLI's own argument
parsing throws the type that means that.

**A gap usually arrives as a diagnostic now, not as an exception**, and `TsonCli.exitCodeFor` is where the
run's code is decided, each branch with a one-line note on stderr and the report on stdout unchanged.

**A mixed run is the normal path**, so the order is a stated rule: **rank by who must act first, with
permanence breaking the tie** between ranks where nobody present can act. At a command line the actors are
the runner and their files, which gives `70 > 78 > 69 > 75 > 1` — a library release, a differently-wired
application, an edit to a reference or an allow-list, a rerun, an edit to the document. The ordinary problems
are real and still printed, but something was not checked at all, so "invalid" is a claim the run cannot
make, and exit 1 would tell a script the document had been judged and rejected. What this buys the author is
the pass staying single: a schema with a gap in one declaration and a mistake in another reports both, where
the throw used to take the second verdict with it.

**78 rather than 70 for a bind mismatch**, because `EX_CONFIG` is "found in an unconfigured or misconfigured
state" and unconfigured is what this is. 70 would say this library cannot do it, which is the reading
`TsonMissingBindingException` exists to prevent — and the note printed with 78 deliberately does not repeat
the diagnostic's own remedy (`TsonConfig.bindings`, a `DataNameBinder`), neither of which has a command-line
surface.

**69 and 75 are reached two ways each, and all of them are `TsonSchemaFetchException`.** A data document's
`!!schema` that no configured source will serve arrives through `SchemaFailure` as a read diagnostic; a
schema document's own `!!import`/`!!meta` that will not load arrives through `Tson.validateSchema`'s own
catch as `Diagnostic.ofSchemaUnavailable`, located at the root pointer. So `tson validate` missing a schema
file and `tson compile` on a schema importing something the CLI cannot fetch land alike: neither run read the
thing it needed. **Which way it failed is the code**, one per `Reason` —
`SCHEMA_NOT_PERMITTED`/`SCHEMA_NOT_FOUND`/`SCHEMA_TOO_LARGE` are 69 because no rerun obtains them, and
`SCHEMA_UNREACHABLE`/`SCHEMA_TIMEOUT` are 75 because one might. A field beside the code would have been a
second carrier for a question the consumer routes on, and `exitCodeFor` reads codes and nothing else because
of it.

**A [TSON-DATA] §8.2 name-hygiene refusal is a diagnostic like any other**, told apart by its `code` alone
(`CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT`, one per rule), so the wire carries no second
discriminator that could contradict it.

**What §8.2 requires a refusal to name rides on the envelope instead**, as `policy` — a `CliPolicy` on both
`validation_run` and `validation_report`, carrying `identifier_policy` and `token_policy` (each a level, a
`per_segment` unit, and any `permitting` relaxations) and the `unicode_data_version` the rules were computed
against. The two surfaces keep `TsonConfig`'s own names all the way to the wire, so what a deployment set
and what its reports say are one vocabulary. It is there rather than on each
diagnostic because it is a fact about the *processor*: constant for the whole run, so a per-refusal copy is N
copies of one string; and needed by a sender *before* it writes a document rather than after being refused,
which a channel that only opens on failure cannot give it. The level is also the half that actually explains
a disagreement — two deployments at one UCD version differ because one of them set `ASCII_ONLY`.

**Both machine formats spell one report one way** — `snake_case` keys, an absent field omitted rather than
written `null`. `--output tson` always did, being bound through `CliDiagnostic`'s `@Field` names to what
`diagnostics.tn` declares; `--output json` hand-wrote `camelCase` with `null`s, so a consumer parsing one and
then the other found neither key where it expected it, and the TypeScript CLI agreed with neither. Nothing in
[TSON-DATA] §8.1 fixes a CLI's wire shape, so the tie is broken by what a schema already describes and what
the other implementation emits. The distinction the two RFC 6901 pointers carry survives: a present `""` is
the root, an absent key means the diagnostic has no such end.

**`tson policy` is the same record with no document in hand**, which is the surface that makes a refusal
avoidable rather than merely explicable: a generator that reads it first never writes the name that would be
refused. `--output text` prints three lines; `json`/`tson` print the identical `policy` shape the envelopes
carry, so a consumer parses one thing either way.

**The three commands that judge a name take the policy flags** — `validate`, `compile`, `policy`, not
`hash`/`init-example`. `PolicyOptions` consumes them off the argument list before each subcommand's own loop
runs, so those loops still see only `--output` and their positionals; the pair then goes into one
`Tson.builder()` per run, which is what makes a schema's declared names and a data document's names answer to
one setting. §8.2 asks that a relaxation not be *silent*, and a flag written into a CI file satisfies that
where the environment variable it warns about would not — the point of the rule is ambient authority, not the
existence of configuration. Giving the CLI no way to configure this at all was the worse failure: it told the
person running it which policy refused their document and left them unable to change it, they being the
deployment the report describes.

**Two rules keep a flag from meaning nothing.** `--token-scripts` alone raises the token level from its
`UNRESTRICTED` default to `SINGLE_SCRIPT`, because `permitting(…)` is consulted only by a level that scans and
the list would otherwise be inert — Single Script being the level at which a list of combinations *is* the
whole configuration. And a relaxation named against a level the caller *stated* that scans nothing is a usage
error, not a no-op: `--token-policy unrestricted --token-scripts Latin+Cyrillic` configures nothing whatever,
and accepting it silently would leave the caller believing a restriction is in force. That is
`withTokenPolicy`'s own habit — it refuses a per-segment token policy rather than ignoring it — which is also
why there is no `--token-per-segment` flag: it could only ever be a usage error.

**`TEXT` prints the policy when it is load-bearing** — something was refused under it, or it was configured.
A person does not want a configuration dump on every clean run; they do want, at the moment a name is refused,
to be told the verdict came from this deployment's settings rather than from the file in front of them, and
they want a run that relaxed a rule to say so even when it passed. The machine formats always carry it.

That makes `restriction_level` a third hand-written enum copy beside `diagnostic_code` and `outcome` —
`DiagnosticsSchemaTest` checks all three against their Java enums in both directions.

**The envelope does not yet keep a refusal apart from a verdict**, and the codes are the only thing that
does: a run whose only problem is a refusal still sets `valid: false` and exits 1. `BACKLOG.md` carries it.

**70 covers both halves of the exception-classification policy's non-verdict side, printed differently.** A
gap (`UnsupportedOperationException` — *this library hasn't implemented that yet*) renders as `not
implemented yet: <message>` and nothing else: those messages routinely end with the way to write the thing
today (§8.1's collection-slot refusal names the workaround outright), and the bug-report banner plus a
25-frame trace buried the one line worth reading while asking for a report of something already known. A
fault (`IllegalStateException` and everything else — a broken internal invariant) keeps the trace and the
please-report-it framing, which is where it is actually news. `TsonCli.notImplemented`/`internalError` are
the two, ordered so the gap catch comes first.

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
