# CLI, bundled schemas, and content hashing

The outer ring: the bundled schema resources (`TsonBundledSchemas`), content hashing (`TsonContentHash`) and the
commands on it, the `tson` command (`tson-cli`), and the two default bind contexts. The restamp procedure is
`design/process.md`'s; pin verification per identity is `design/compiled-registries.md`'s; the exit-code ranking's
reasons are `TsonCli.exitCodeFor`'s Javadoc. Each command's class Javadoc carries its own decisions.

**Invariants**

- A `?sha256=` pin is verification metadata, not identity: everything keys by canonical identity, and every fetched
  reference carrying a pin is checked before use. "Content hash" is the spec's term ([TSON-DATA] §2.2.1,
  [TSON-SCHEMA] §10.2), never shortened to "hash" in a type name.
- The content hash covers every byte past the `!!id` line's terminator; a leading BOM is stripped, never hashed.
- `TsonBundledSchemas`' digests are over the `spec/m/` bytes, the one copy on disk (`processResources` packages
  it), and are checked against the packaged resource on every load. Editing a bundled schema moves the whole pinned
  chain bottom-up; `scripts/restamp-bundled-schemas.sh` does it and `--check` reports staleness and writes nothing.
- Files are classified as schema or data by the root's tag (`!schema`) and matched by embedded `!!id`, never
  filename; `-` is stdin, at most once, always data. `.json` is the one extension read, on [TSON-JSON] §3.1's
  authority: the axis is the encoding, where the `!!id` rule is about schema versus data.
- `validate`'s source is the files on the command line and nothing beyond them: an `!!import` none of them declares
  is `SCHEMA_NOT_PERMITTED` (a refusal, exit 69), never `SCHEMA_NOT_FOUND`, which says a source looked beyond its
  configuration and found nothing.
- `validate` emits one `ValidationRun` envelope per invocation; run-level `errors` are exit 2, a document's own are
  in its `FileReport`.
- Only what is a file's own is a verdict on it. The read loop catches `IOException` alone, and only this CLI's
  argument parsing throws `UsageException`: a library fault reaches `TsonCli`'s handler and exit 70, never folded
  into "invalid" or "usage". A bound schema that does not load reports its own diagnostics, so a gap in it is 70.
- `TsonCli.exitCodeFor` reads diagnostic codes and nothing else, ranking `70 > 78 > 69 > 1 > 75`; 1 is a rejection,
  2 a usage error or a schema file that does not load, and the rest the run declining to give a verdict.
- Both machine formats spell one report one way, `snake_case` keys and an absent field omitted. `--output tson`
  binds through `CliDiagnostic`'s `@Field` names to what `diagnostics.tn` declares; `--output json` writes the same
  keys by hand, so a field added to one is added to the other. Nothing in §8.1 fixes a CLI's wire shape, so the
  schema settles it.
- A policy flag never means nothing: `--token-scripts` alone raises the token level to `SINGLE_SCRIPT`, a relaxation
  against a stated level that scans nothing is a usage error, and `--max-depth` or `--max-wait-depth` below 1 is
  refused rather than clamped.
- Two default bind contexts over one atom list (`AtomContext.hostTypes()`), distinct because a `DataNameBinder` is
  fixed when a `DataBindContext` is built. `AtomContext.defaultContext()` has no name binder: the consumer and
  schemaless default, what `ProcessorConfig.dataBindContext()` holds unless stated. `SchemaModelNameBinder.defaultContext()`
  adds the schema model's registrations (`ResolverBindContext.registerDefaults`) and its binder, and nothing else: the
  context of the kernel's bootstrap and of a registry built with no consumer classes, never a consumer default. How
  `Tson.of` composes the two is `design/front-door-and-config.md`'s.

Related: `design/diagnostic-model.md` (the codes the exit codes ride on), `design/compiled-registries.md`,
`design/front-door-and-config.md`, `design/process.md`, `design/json-encoding.md`.

## Decisions that lost

- **A `valid` boolean in the report.** `if (!valid)` reads "undetermined" as "rejected"; `Outcome` is a three-member
  enum so there is no falsy shortcut.
- **A field beside the code to say why a schema was not obtained, or which §8.2 rule refused.** A second
  discriminator could contradict the first; the consumer routes on `code`.
- **The policy on each diagnostic.** It is constant for the run and needed by a sender before it writes, which a
  channel that opens only on failure cannot give: it rides on the envelope, and `tson policy` prints it with no
  document in hand.
- **No policy flags, since §8.2 asks that a relaxation not be silent.** The rule targets ambient authority; a flag
  written in a CI file is the opposite, and without one the runner is told which policy refused them and cannot
  change it.
- **Classifying stdin.** Classification opens a document a second time and a stream cannot be reopened, so piped
  input is always data and schemas stay files.
- **`tson strip` re-pinning or claiming the identity.** Its bytes are not the published document's, so the output
  has no `!!id` and the file is never rewritten. Whitespace collapses to one space and is never removed, so
  adjacency (§7.2) reads the same.
