# Process: branches, the spec-feedback register, the bundled schemas, project-owned identities

The reasons behind the working rules the root `CLAUDE.md` and `spec/CLAUDE.md` state, and the procedures too long for
them. A rule stated there is not restated here.

**Invariants**

- `main` implements the *published* revision; a proposal branch merges when the spec lands and not before.
- While a proposal branch is open, all work lands on it, a fix `main` could carry included.
- The bundled schemas carry the proposing revision's identities from the proposal's first commit.
- A Part 3 finding is a spec edit in the same session; a Part 1/2 finding is a register entry.
- The register holds what is open, renumbers from #1 when a revision closes, and is self-contained.
- Part 2 §13.2's artifact rows are the one digest pin no script writes.
- A project-owned schema's identity is fixed by a release, not by a change.

Related: `design/cli-config-hashing.md` (bundled schemas at run time), `design/conformance-suite.md`.

## Branches and revisions

**`main` is the reference implementation of the published revision.** Each published revision stays reachable by tag
(`r2026-NN`) at the point it was the whole of `main`, tagged just before the next proposal merges, in the corpus repo
too. A revision's work happens on `r2026-NN-proposal`, with a corpus branch of the same name and `SUITE_PIN` following
it. The branch is the argument: there the register's entries state what is *running*. It merges when the spec lands
and not before, since merging a divergence early costs `main` the one signal it exists to give.

**A proposal carries its revision's identities and version (`0.NN.0-SNAPSHOT`) from its first commit**, so a content
change lands on artifacts named for the revision proposing it and nothing is re-identified at the end. On the branch
all three parts in `spec/` are the proposed text, edited in place, with a change log beside them in the form of
`spec/tson-rev40-changelog.md`: `git diff main -- spec/` is the proposal, section numbers stay stable because the code
cites them, and every example is one the implementation accepts.

**While a proposal is open, every change branches off it and PRs into it**, a defect `main` also has included.
Splitting work between the two means merging `main` into the proposal after each fix, for a `main` nobody is releasing
from. Two consequences: `Closes #N` in a PR into a proposal does not close the issue (GitHub fires it only on the
default branch), so each is closed by hand; and CI runs on pull requests and on `main` only, so a commit pushed
straight to a proposal is verified by the local build alone.

**Correctness wins over stability** because a compatibility argument is only worth making about a real consumer, and
there are none: no release, no remote repository, every version `-SNAPSHOT`. The one binding rule is [TSON-SCHEMA]
§10's, which is about documents in the world, not Java signatures.

## The bundled schemas

**§13.2 is the pin that silently drifts.** `scripts/restamp-bundled-schemas.sh` writes every pin in the repo, in
dependency order (meta.tn; numeric.tn, temporal.tn and net.tn; core.tn; policy.tn), and the getting-started example,
which pins core and so has a digest that moves with it. It does not write Part 2 §13.2's artifact rows, which are
edited by hand so the table names the bytes beside it; its Part 1 and Guide rows stay at the revision those documents
are. `tson hash spec/m/<name>.tn` is the check. `TsonBundledSchemas`, `InitCommand` and `README.md` carry the
published values too.

**Restamp after each edit, not at the end.** The digests are verified on every load, so restamping per edit is what
lets a schema change land across several commits with the integrity checks left on.

**The `*-resolved.tn` fixtures are the only external statement of what a conforming resolver produces**, which is why
`ResolvedFixtureTest` compares them and why they move with the `.tn` beside them. Three things about the comparison
are easy to undo:

- **Key annotations are compared from the parsed text**, never through the bound document, which drops them: both
  sides would carry none and agree for the wrong reason.
- **`@doc` is not compared.** A fixture keeps its header note and leaves entry docs out, since a summary of a source
  doc only drifts from it.
- **A fixture states its own document's entries only**, never an import's members of the same map, and every fixture
  but the kernel's is read beside its source: a resolved form is read under its source's imports and not over its own
  entries, where [TSON-SCHEMA] §8.1 supplies nothing beside the document.

### Writing the bundled schemas' docs

**A bundled schema's `@doc` serves someone looking at that one entry, reading or writing a schema, who has not read
[TSON-SCHEMA].** Nobody reads a schema language's specification before writing a schema, so the doc carries the basic
case and names where the rest is. A doc that restates a rule in full has to be kept in step with the spec by hand and
drifts.

- **What it is**, in a sentence, and **how it is written in the basic case**, with one example where the spelling
  is not obvious (`age => !integer ^ { min: 0  max: 150 }`). Every example is a line that loads.
- **What its declaration does not show about its values:** an atom family's value space where it differs from its
  lexical space, its canonical text and the quirks of its lexical form, which [TSON-SCHEMA] §5.5 delegates to the
  family's own `@doc`; what a facet means where its name does not say, with its unit; a rule over the body that no
  type states.
- **Where to read further:** the section that owns the rule, `([TSON-SCHEMA] §5.2)`, or the standard. A rule the
  spec states in full is cited, never restated.
- **Not in a doc:** how a refinement may move a facet (§5.7's), resolver, linker and reader behaviour, rationale,
  history, comparisons, and a file's contents retold in its header.
- **One place.** A family's value space and facets sit on its constructor; its instance says what it is and shows
  the refinement an author writes. A rule several families share is stated once in a file header.
- **Form.** Present tense, plain declarative sentences. A one-sentence doc is `@doc:"…"` on one line; a longer one
  is a `"""` block indented as the file does, filled to **80 columns, indentation included**; a backtick code span
  is never split across lines.
- **Shortening a rule never changes it.** A doc that contradicts its declaration or another doc is reported, never
  silently reconciled.

## Spec feedback

**Parts 1 and 2 keep a register and Part 3 does not**, because their revision is published and this implementation
*proposes* changes to it, while [TSON-JSON] has no published revision to be behind: a finding there is a spec change
in the same session, with git history as its record. An entry spanning Part 3 and an earlier part stays in the
register and says which half is which.

**The register renumbers from #1 when a revision closes** because it is an input to the next revision's adjudication:
its numbering is what that revision's change log answers against. Entries whose resolution landed are deleted, and the
closing revision's change log in `spec/` keeps all of them under their numbers. While a revision is open, numbers are
not reused and gaps are left, so a citation stays valid.

**The evidence beside an entry is this implementation**, which is why an entry states what is running and why the
shared corpus's `proposed/` bucket stays empty here: the proposal is the code, not a vector another implementation is
asked to fail. Working design documents are not kept in `spec/`: once a design lands, the entry absorbs what survives
of the argument and the document goes.

**A resolved ambiguity is invisible again three sessions later unless written down**, which is the reason for never
silently picking an interpretation, and the reason a claim about what the format admits is tried before it is
written: an entry derived from the prose alone has been wrong.

## Naming, exceptions, identities

**The `Tson` prefix exists for disambiguation at a consumer's call site** (`TsonSchema` against a domain `Schema`).
For a new public type, ask whether a consumer would plausibly have their own class with the bare name: if so and the
type is consumer-facing, prefix it; internal machinery stays bare.

**A gap is collected, not thrown past the phase.** A gap thrown out of a phase that reports per declaration takes
every other declaration's verdict with it, so the schema pipeline reports it beside the ordinary problems and
`Diagnostic.Code.NOT_IMPLEMENTED` keeps it apart; the exception's class is what picks the code.
`DefinitionResolver`'s Javadoc lists the boundary.

**A project-owned schema's `!!id`** is `https://tson.io/2026/40/io/ltr8/<group>/<name>.tn`: `/2026/40` the spec
revision, `io/ltr8` the publisher, `<group>` the subsystem (`cli`), `<name>` the schema. While the build version
carries `-SNAPSHOT` nothing has published the identity, so the schema is edited in place; once a release ships
carrying it, [TSON-SCHEMA] §10 fixes the document under that `!!id`. The extension is `.tn`: `.tn1` is a stability
claim §7.1 reserves for a frozen "TSON version 1".
