# CLI, bundled schemas, and content hashing

The outer ring: the bundled schema resources, content hashing and the commands built on it, the `tson`
command-line application, and the default bind contexts. Current form only; history lives in git.

**Invariants**

- A `?sha256=` pin is verification metadata, not identity: everything keys by canonical identity, and every fetched
  reference carrying a pin is checked before use.
- The content hash covers every byte past the `!!id` line's terminator; a leading BOM is stripped, never hashed.
- Editing a bundled schema moves the whole pinned chain bottom-up; `scripts/restamp-bundled-schemas.sh` does it and
  `--check` reports staleness and writes nothing.
- Files are classified as schema or data by the root's tag (`!schema`) and matched by embedded `!!id`, never
  filename; `-` is stdin, at most once, always data. `.json` is the one extension read.
- `validate` emits one `ValidationRun` envelope per invocation; run-level `errors` are exit 2, a document's own are
  in its `FileReport`.
- `TsonCli.exitCodeFor` reads diagnostic codes and nothing else, ranking `70 > 78 > 69 > 1 > 75`; 1 is a rejection,
  2 a usage error or a schema file that does not load, and the rest the run declining to give a verdict.
- A policy flag never means nothing: `--token-scripts` alone raises the token level to `SINGLE_SCRIPT`, a relaxation
  against a stated level that scans nothing is a usage error, and `--max-depth` or `--max-wait-depth` below 1 is
  refused rather than clamped.
- The two default bind contexts share one atom list (`AtomContext.hostTypes()`); the resolver's adds a name binder and
  the schema model's own three atom registrations, and nothing else.

Related: `design/diagnostic-model.md` (the `Diagnostic` model and the codes the exit codes ride on),
`design/compiled-registries.md` (pin verification per identity, `crossCheckId`), `design/front-door-and-config.md`,
`design/process.md` (the restamp procedure and Part 2 §13.2's hand-stamped table), `design/json-encoding.md`.

## Bundled schema documents (`tson-schema/TsonBundledSchemas.java`)

The published identities of the five bundled schemas (`META_KERNEL_ID`/`META_ID`/`CORE_ID`/`NET_ID`/`POLICY_ID`),
their raw text (`fetch(uri)`, off `tson-schema`'s classpath) and one held digest each (`*_SHA256`), checked against
the packaged resource on every load. The `.tn` resources are copied from `spec/m/` by `processResources`, so there
is one copy on disk. `fetch` is a static method rather than a `SchemaSource` instance; a caller needing a source
passes `TsonBundledSchemas::fetch`.

The digests are over the `spec/m/` bytes, which are the live artifacts. At Revision 38 they equal the pins the
published artifacts and Part 2 §13.2 carry; on a proposal branch they are this repo's own until the revision lands.

## Content hashing (`TsonContentHash`) and the commands on it

`TsonContentHash.sha256(byte[])` hashes every byte past the first line's terminator ([TSON-DATA] §2.2.1). The
first line is the `!!id` line, grammar-required first, so a document can carry its own hash on its own id line
without circularity. `verify(bytes, referenceUri)` parses the reference's `?sha256=` pin and throws
`ContentHashMismatchException` on a mismatch; an unrecognized query parameter or malformed hex throws rather than
being silently retained, since §2.2.1 admits only hash-algorithm parameters. "Content hash" is the spec's own term
(§2.2.1, [TSON-SCHEMA] §10.2), never shortened to "hash" in a type name.

- **The pin is not identity.** A pinned `!!id` resolves against a plain or differently-pinned reference because
  everything keys by canonical identity (scheme and query stripped).
- **Verification is wired through the loader**: a data document's `!!import`s and each transitive `!!import` carrying
  a pin are checked before use, and a schema registered from text is hashed by the call that registers it.
  `design/compiled-registries.md` has the per-identity rule. The bundled chain is pinned end to end — meta imports
  meta-kernel, core and net import meta, and policy imports core, each by a pinned reference — so bootstrap verifies
  all of it. Meta-kernel imports nothing; its own `!!id` carries its pin.
- **`tson hash <file>`** stamps `?sha256=<hex>` onto the `!!id` in place, replacing any prior query. It is
  idempotent: the hashed bytes never change, so the pin stays valid.
- **`tson strip [--keep-docs] <schema>`** prints a schema's reading form (`TsonSchemaStripper`) for a model to read,
  never to load. The `!!id`, header pins and `@doc`/`@title`/`@examples`/`@comment` go; a header reference to
  meta-kernel, meta or core shortens to `"39/core"`; each directive and declaration gets one line. `--keep-docs`
  (`stripKeepingDocs`) keeps the three written for the schema's readers and drops `@comment`, written for its
  maintainers. It never re-pins and never rewrites the file: the output claims no identity, because its bytes are
  not the published document's. **Whitespace collapses to one space and is never removed**, so tokens that touched
  still touch and adjacency (§7.2) reads the same. The output is re-lexed against the intended tokens and
  re-parsed, either failure being a fault here. Every other annotation stays, since several are checked.
- **`scripts/restamp-bundled-schemas.sh`** is that stamping applied to the whole chain, which no single-file
  command can do: an edit to meta.tn moves meta's `!!id`, every `!!import` pinning it, each of those documents' own
  `!!id`, the `TsonBundledSchemas` constants, and the getting-started example's pins in `README.md` and
  `InitCommand.java` — the example pins core, so its own digest moves with core's. It hashes with
  `tail -n +2 | shasum -a 256` rather than calling `tson hash`, so it runs when the tree does not compile. Only a
  reference already carrying `?sha256=` is re-stamped: pinning is optional, and an unpinned mention is not a stale
  pin. When to run it and what it does not cover are in `design/process.md`.

## `tson validate`

`tson validate [--output text|json|tson] <file|->...` takes a **flat list of files** and classifies each as schema
or data (`TsonDocumentPeek.of(…).isSchemaDocument()`: a root tagged `!schema`). The schema files become the run's
`SchemaSource`; each data document goes to `Tson.validate`, its `!!import`s selecting the schemas and its root
type-ref the type, no `!!import` meaning schemaless. A TSON data document is self-describing, so it takes no `--type`;
`--schema`/`--type` exist for JSON inputs alone ("JSON inputs" below). One `Tson` judges the whole run, so a schema's
declared names and a document's names answer to one policy.

**Every schema file loads before any document is read** (`Tson.validateSchema`), so a schema that does not load is
known to be the schema's fault. A document naming it is skipped unread and reported `UNDETERMINED` with the schema's
diagnostics — `REJECTED` would claim a check never made — while every other document is judged as usual. Such a run
exits 2 unless the others reach 70 or 78: the schema file is the runner's to fix before anything else means much.

**The run's source is the files on the command line**, its whole configuration, and nothing is fetched beyond them.
An `!!import` none of them declares is `SCHEMA_NOT_PERMITTED` — a refusal, outcome `REJECTED`, exit 69 — and the source
never answers `SCHEMA_NOT_FOUND`, which is a source that looks beyond its configuration and finds nothing. The
message lists what the supplied files *declare*: matching is by embedded `!!id`, never filename (§2.2.1), so the
common failure is the right file with the wrong identity in it. `declaredIds` keeps the ids verbatim and in argument
order, apart from the lookup map whose keys are canonical. A file declaring a bundled identity is ignored with a
note.

**`-` is standard input, at most once, always data.** `ValidateInput` is the sealed argument type
(`OfFile`/`OfStdin`); `OfStdin.open()` suppresses `close()`, since `System.in` belongs to the process. Piped input is
never classified, because classification opens a document a second time and a stream cannot be reopened — so
schemas stay files. A second `-` is a `UsageException`: the first read consumes the stream, so the second could only
report an empty document as valid. Only the bare argument matches; `./-` reaches a file named `-`.

`cannotRead` names the failure kind: `NoSuchFileException` and its siblings carry it in the type, not the message, so
the obvious concatenation renders `cannot read x: x`.

The read loop catches only `IOException` (an unreadable file *is* that file's verdict). A `RuntimeException`, which
`Tson.validate` raises only for a library fault (`TsonDiagnostics.ofBaseSyntaxError` rethrows anything not a
base-syntax failure), reaches `TsonCli`'s own handler and exit 70 rather than being folded into "invalid".
`UsageException` exists for the same reason one layer up: a bare `IllegalArgumentException` catch would relabel a
library fault as a bad command line, so only this CLI's argument parsing throws the type that means that.

Also: `tson compile <schema>` (resolves through compile, tree mode, and renders a bare `ValidationReport`), `tson
hash`, `tson strip`, `tson policy` (below), and `tson init-example [<dir>]` (writes a working
`person.tn`, a schema importing core.tn with root `!schema`, and `person-data.tn`, `!!import`ing it, refusing to
overwrite). The installed command is `tson`
(`application.applicationName`), launched on the classpath.

## Reports

**One `ValidationRun` envelope per invocation, whatever the file count** — `{ outcome, policy, files: [{ file,
outcome, errors }], errors }`, matching `diagnostics.tn`'s `validation_run` — so `--output json`/`tson` is one
parseable document with each filename inside it. Only `--output text` keeps a `# <file>` header, and only for more
than one file: a label outside the object suits a person and is exactly what makes a machine format unparseable.
Every report is collected before anything prints, since the run's outcome is taken across them: `REJECTED` if any
file is, else `UNDETERMINED` if any file is not `ACCEPTED`, else `ACCEPTED`.

**The two error lists say different things.** Run-level `errors` holds only what stops the invocation before any
document is read — an unreadable file during classification, a schema with no `!!id` or that fails to parse, no data
files, a bad `--schema`/`--type` binding — and is exit 2 with the run `UNDETERMINED`. A document that read reports in
its own `FileReport`. A consumer tells "your invocation was wrong" from "your document was" without reading messages.

**`outcome` answers "will this be accepted"** ([TSON-DATA] §8.1): `ACCEPTED`; `REJECTED` for an invalidity or a refusal
under this processor's §8.2 policy or its §9.1 or §11.5 limits, one rejection settling it whatever else went unjudged;
`UNDETERMINED` where nothing rejected the document but something could not be judged. Which kind a rejection was is each
diagnostic's code (`Code.verdict()`, `Code.isRefusal()`). It is an enum rather than a `valid` boolean so that
`if (!valid)` cannot read "undetermined" as "rejected".

**`CliDiagnostic` renders an absent field as absent.** `Diagnostic` spells "nothing to say" as `""` for
`schemaId`/`expected`/`actual`, narrowed here through `schemaIdIfKnown()`/`expectedIfStated()`/`actualIfStated()`.
The two RFC 6901 pointers are `Optional` at the source, because for a pointer `""` is the *root*: a document-level
schema problem or a base-syntax failure genuinely locates itself there. Both machine formats keep a present `""` and
an absent pointer apart; `--output text` renders them alike, since `": msg"` is noise to a person reading the whole
document either way.

**Both machine formats spell one report one way** — `snake_case` keys, an absent field omitted rather than written
`null`. `--output tson` binds through `CliDiagnostic`'s `@Field` names to what `diagnostics.tn` declares, and
`--output json` writes the same keys by hand. Nothing in §8.1 fixes a CLI's wire shape, so the schema settles it.
`diagnostics.tn` holds two hand-written enum copies, `diagnostic_code` and `outcome`, which `DiagnosticsSchemaTest`
checks against `Diagnostic.Code` and `Outcome` in both directions; it imports the spec's `core.tn`, and its
`policy.tn` for `policy`, whose `restriction_level` `PolicySchemaTest` checks against `RestrictionLevel`.

## Exit codes

**0 accepted, 1 rejected** (invalid, or refused under a §8.2/§9.1 setting the runner can change), **2 usage or
classification**, **69 (`EX_UNAVAILABLE`) a schema nothing would supply and a rerun would not either**, **75
(`EX_TEMPFAIL`) a schema that could not be reached, where a rerun might**, **78 (`EX_CONFIG`) a type the schema needs
with no Java class in this tool**, **70 (`EX_SOFTWARE`) the library failing to reach a verdict**. Everything above 2
is the run declining to give a verdict rather than giving a bad one, and each names who could not give it: 69/75
whoever serves the schema, 78 whoever wired the application, 70 this library.

**A gap usually arrives as a diagnostic, not an exception**, so `TsonCli.exitCodeFor` decides the run's code from
codes alone, each branch printing a one-line note on stderr and leaving stdout's report unchanged. A field beside the
code would be a second carrier for a question the consumer routes on.

**A mixed run is the normal path, so the order is a rule: rank by who must act first, with permanence breaking the
tie** between ranks where nobody present can act. That gives `70 > 78 > 69 > 1 > 75` — a library release, a
differently-wired application, an edit to a reference, an edit to the document, a rerun. 75 is returned only when
every code is a transient fetch failure; beside a rejection the run is 1, with a note. The rerun is last because
every other fix ends in one, which retries an unreachable schema for free. A gap outranks a rejection because nobody
present can act on it, and collecting it rather than throwing keeps the pass single: a schema with a gap in one
declaration and a mistake in another reports both. `exitCodeFor`'s Javadoc carries the per-rank reasoning, including
why a bind mismatch is 78 rather than 70; the note printed with 78 omits the diagnostic's own remedy
(`ProcessorConfig.withDataBindContext`, a `DataNameBinder`), which has no command-line surface.

**69 and 75 are both `SchemaFetchException`, reached two ways.** A data document's `!!import` that no source will
serve arrives through `SchemaFailure` as a read diagnostic; a schema document's own `!!import` that will not
load arrives through `Tson.validateSchema`'s catch as `TsonDiagnostics.ofSchemaUnavailable`, located at the root
pointer. So `validate` missing a schema file and `compile` on a schema importing something unfetchable land alike.
The code is one per `Reason`: `SCHEMA_NOT_PERMITTED`/`SCHEMA_NOT_FOUND`/`SCHEMA_TOO_LARGE` are 69 because no rerun
obtains them, `SCHEMA_UNREACHABLE`/`SCHEMA_TIMEOUT` 75 because one might.

**70 covers both non-verdict halves of the exception-classification policy, printed differently.** A gap
(`UnsupportedOperationException`) prints `not implemented yet: <message>` and nothing else: those messages routinely
name the way to write the thing today, and a trace and a bug-report banner would bury that line. A fault
(`IllegalStateException` and everything else) keeps the trace and the please-report-it framing, where it is news.
`TsonCli.notImplemented`/`internalError` are the two, the gap catch first.

## Policy: flags, the `policy` record, `tson policy`

**A §8.2 name-hygiene refusal is a diagnostic like any other**, told apart by its `code` alone
(`CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT`), so the wire carries no second discriminator.

**What §8.2 requires a refusal to name rides on the envelope**, as `policy` on both `validation_run` and
`validation_report`: `CliPolicy`, the shape of `policy.tn`'s `policy` — `identifier_policy` (level, `per_segment`,
`skeleton_distinctness`, `permitting` combinations with each script by its UAX #24 alias), `token_policy` (level,
`permitting`), `unicode_data_version`, and `limits` (all seventeen of §9.1's and §11.5's, `max_depth` and
`max_wait_depth` among them). It is on the envelope rather than each
diagnostic because it is a fact about the processor: constant for the run, and needed by a sender *before* it writes
a document, which a channel that opens only on failure cannot give. `limits` sits inside `policy` because the
envelope's one question is "what judged this run", and a limit refusal answers it as much as a name refusal. The
names are `ProcessorConfig`'s all the way to the wire, so what a deployment set and what its reports say are one
vocabulary.

**`tson policy` is the same record with no document in hand**, the surface that makes a refusal avoidable: a
generator that reads it first never writes the name, or the nesting, that would be refused. `--output text` prints
four lines; `json`/`tson` print the `policy` shape the envelopes carry. It always exits 0.

**`TEXT` prints the policy when it is load-bearing** — something was refused under it, or it is not the default
(`CliPolicy.isDefault()`). A person does not want a configuration dump on every clean run, but does want to know a
refusal came from this deployment's settings, and that a relaxed run relaxed something (§8.2: a relaxation is not
silent). The machine formats always carry it.

**`validate`, `compile` and `policy` take the policy flags**, not `hash`/`init-example`/`strip`. `PolicyOptions`
consumes them off the argument list before each subcommand's own loop, which then sees only `--output` and its
positionals. §8.2 asks that a relaxation not be *silent*; the rule targets ambient authority, and a flag written in
a CI file is the opposite. A CLI with no way to configure this would tell the runner which policy refused their
document and leave them unable to change it.

**The flags:** `--max-depth` takes §9.1's nesting bound and `--max-wait-depth` [TSON-SCHEMA] §11.5's wait depth;
`--identifier-policy`/`--token-policy` a UTS #39 §5.2 level, in the spelling a person types (`highly-restrictive`) or
the one `tson policy` prints (`HIGHLY_RESTRICTIVE`); `--identifier-per-segment` the unit;
`--identifier-allow-look-alikes` drops skeleton distinctness; and `--identifier-scripts`/`--token-scripts` a
`Latin+Cyrillic` combination, repeatable. Allow-look-alikes is no relaxation of the level, so it is valid under every
level. There is no `--token-per-segment`: a token policy is a `ScriptPolicy`, which has no unit.

**Three rules keep a flag from meaning nothing.** `--token-scripts` alone raises the token level from its
`UNRESTRICTED` default to `SINGLE_SCRIPT`, because `permitting(…)` is consulted only by a level that scans, and Single
Script is the level at which a list of combinations is the whole configuration. A relaxation (scripts, per-segment)
against a *stated* level that scans nothing is a usage error: `--token-policy unrestricted --token-scripts
Latin+Cyrillic` configures nothing, and accepting it would leave the caller believing a restriction is in force.
And `--max-depth` or `--max-wait-depth` below 1 is refused rather than clamped: reading under a bound the caller did
not ask for is the one outcome that leaves them unable to explain the result.

## JSON inputs (`--schema`, `--type`)

**A `.json` input is validated against a schema supplied out of band**: `--schema <file|uri> --type <name>` are one
statement binding every JSON input in the run ([TSON-JSON] §3.4, since a JSON document names neither for itself).
The encoding is read off the extension on §3.1's authority — the one filename this CLI reads, the axis being the
encoding where the `!!id` rule is about schema versus data. Stdin, having no name, is read as JSON when the binding
is given. Half a binding, a `.json` with none, a binding with no JSON to bind, a `--schema` naming a file that is not
a schema document, one that is neither a file nor an identifying URI, an identity no schema file declares, and a
`--type` the schema does not declare are all **usage errors** (exit 2): each is the command line's mistake, and the
binding is checked before any document is read so one typo prints once rather than once per file. One envelope, one
exit-code ranking and one policy field cover both encodings.

**`--schema` takes a file or an identity, and binds by identity either way.** A file joins the run's schemas and
contributes the `!!id` it declares, so a local draft or a downloaded copy binds wherever it sits. A value naming an
existing regular file is a path, anything else an identity; the test is the file system, not the value's shape,
since `C:\x.tn` parses as a URI with a scheme. The identity form serves a run whose schema files are already listed,
and its failures list the `!!id`s those files declare.

## Default bind contexts (`tson-base`'s `base.bind`, `tson-compiler/.../config/`)

`AtomContext` is `tson-base`'s, so a class binds the same under every encoding. **Two default contexts over one atom
list**, distinct because a `DataNameBinder` is fixed when a `DataBindContext` is built:

- `AtomContext.defaultContext()` — the built-in atoms' host types, **no name binder**. The consumer and schemaless
  default: what `ProcessorConfig.dataBindContext()` holds unless `withDataBindContext` states one, and the base a
  consumer layers a binder onto. `Tson.of` composes the schema model's binder and registrations onto whichever it is
  (`SchemaMetaNameBinder.over`), and `Tson.dataBindContext()` returns that composition.
  `registerAtoms(AtomContext.hostTypes())` adds the same list to any builder, so it lives in one place.
- `SchemaMetaNameBinder.defaultContext()` — the same atoms plus `ResolverBindContext.registerDefaults`' three
  registrations for the schema model (`schema.meta.Token`, one token on the wire; `SourcePosition`, whose
  `SourcePositionStringBridge` names `tson-compiler`'s `Position`; `schema.meta.Ref` through `RefNameBridge`), plus a
  `DataNameBinder` over `io.ltr8.tson.schema.meta` that answers only for the names meta-kernel.tn and meta.tn
  declare. The context of the kernel's bootstrap and of a registry built
  directly with no consumer classes, not a consumer default.
