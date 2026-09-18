# Process: branches, the spec-feedback register, the backlog, project-owned identities

The working rules `CLAUDE.md` states in brief, at full length with their reasons. Current form only; history lives in
git.

**Invariants**

- `main` implements the *published* revision; a proposal branch merges when the spec lands and not before.
- The bundled schemas carry the proposing revision's identities from the start.
- A Part 3 finding is a spec edit in the same session; a Part 1/2 finding is a register entry.
- The register holds what is open, renumbers from #1 when a revision closes, and is self-contained.
- Cite the spec section; cite `SPEC-FEEDBACK.md #N` only while the entry is open.
- `BACKLOG.md` holds outstanding work only — not what was done, decided against, or might matter later.
- A project-owned schema's version is bumped on a release, not on a change.

Related: `design/cli-config-hashing.md` (bundled schemas, restamping), `design/conformance-suite.md`.

## The spec cache and the bundled schemas

doubt, **re-fetch the current URL** and check the revision number at the top rather than trusting a cached
copy. `spec/` holds local snapshots of the current revision for quick reference: `spec/tson-part1-data.md`,
`spec/tson-part2-schema.md`, and `spec/m/{meta-kernel,meta,core}.tn` (the spec's own bundled schema
documents — the meta-kernel bootstrap layer, the meta-schema built on it, and the core type library built
on that) plus their non-normative `*-resolved.tn` resolver-output fixtures. Treat `spec/` as a cache, not a
source of truth — with **two** standing exceptions. The three `.tn` schemas are **packaged from here at build
time**, so they are the live copies rather than a snapshot. And **`spec/tson-part3-json.md` is editable in
place**: see "Part 3 is drafted here" below. On this branch they carry **Revision 36 identities** —
`https://tson.io/2026/36/m/*.tn`, the proposing revision's own, per the from-the-start rule below. `spec/` holds
**Revision 35** of Parts 1 and 2, which are the published cache and are not edited here; what moved in Part 2 is
§13.2's three artifact rows alone, so the table still names the bytes beside it and stays checkable, while its
Part 1 and Guide rows stay at the revision those documents actually are. **§13.2 is a fourth pin to move**
whenever the artifacts change.
`scripts/restamp-bundled-schemas.sh` does not know about it: the script covers the repo's own pins, and the
spec document is a cache it does not write, so §13.2 is the one that has to be re-stamped by hand and is
therefore the one that silently drifts. `tson hash spec/m/<name>.tn` is the check. The divergences earlier
revisions carried are all in the spec now — `reference.target` typed `type_ref`, no
`instance_template`/`template_argument`/`value_param` (§5.10's held bodies replaced the quoted open-body
vocabulary), and `map`'s `state` field behind `{K => V?}` (§5.3). The open-entry shape is the spec's now too:
an open entry's body is an instance of the kernel's `template` constructor — the parameter names and the
application as text (§8.1) — so `type_definition` has lost `parameters` to that body, `disjoint` to the choice
body it is derived over (§5.4), and `kind` altogether, the kernel losing `type_kind` with it because a kind is
derived from an entry's own supertypes and body (§4.1, §8.1's four-branch rule). `TypeDefinition.kind`
survives as an `@Unbound` component: computed at resolution for this resolver's own use, never written.
**Changing them means re-stamping all three digests bottom-up**, moving the matching `*-resolved.tn`
entries, and updating `TsonBundledSchemas`, `InitCommand` and `README.md`, which carry the published
values. `scripts/restamp-bundled-schemas.sh` does the digest half — every pin in the repo, in dependency
order, plus the getting-started example, which pins meta and core and so has a digest of its own that moves
with them; `--check` reports staleness and writes nothing. **The digests are not a test-only concern**: the
library verifies the packaged bytes against `TsonBundledSchemas`' held digest on every load, so one stale
constant fails `Tson.standard()` and with it most of the suite. Restamping after each edit is what
lets a schema change land across several commits with the integrity checks left on.


## Branches and revisions

`@doc` — "Parse the source schema, run the resolver, canonicalise, compare" — and `ResolvedFixtureTest`
does it: every entry must read back into `schema.meta` and have a counterpart here, and what may still
differ is pinned per schema. They are the only external statement of what a conforming resolver produces,
so a change that moves those counts wants looking at rather than renumbering. Keep them in step with the
`.tn` beside them; both have drifted before.

**`main` is the reference implementation of the published revision, which is Revision 35.** Each published
revision's implementation stays reachable at the point it was the whole of `main`, by tag: `r2026-32`,
`r2026-34`. The work for a revision happens on a proposal branch — `r2026-NN-proposal`, with a sibling corpus
branch of the same name and `SUITE_PIN` following it — where the register's entries state what is *running* rather
than what is *proposed*, the branch being the argument. It merges when the spec lands and not before, since
merging a divergence early costs `main` the one signal it exists to give. The bundled schemas carry the
revision's own identities from the start, so a content change lands on artifacts named for the revision
proposing it rather than being re-identified at the end.

**The open proposal is `r2026-36-proposal`, and this is it.** What takes the work off `main` is that the
discriminated-family design (`SPEC-FEEDBACK.md` #10, #11) needs two kernel fields — `record.extension` and
`record.discriminators` — so it is a meta-kernel change and no longer a Revision 35 feature. Work lands
here through ordinary PR branches off this one. `main` stays the Revision 35 reference until the spec catches
up, at which point this merges.

**Nothing here is frozen, and nothing is owed to a user who does not exist.** The spec is a working
revision, this is its first implementation, and the artifact has no published releases and no remote
repository configured — every version carries `-SNAPSHOT`. So **correctness wins over stability, every
time**: a wrong rule gets fixed rather than kept, a bad name gets changed rather than deprecated, a public
method that turned out to be the wrong shape gets deleted rather than wrapped. Where the spec itself is
wrong, `SPEC-FEEDBACK.md` is how that gets fixed too. A compatibility argument is only worth making about a
real consumer, and there are none — the one place any of this becomes binding is §10's immutability rule for

## Spec feedback — this is the first implementation

This is the spec's first implementation, which makes it the first real test of whether the prose resolves
unambiguously to one behavior — valuable to the spec author precisely because it's still a draft. Actively
watch for and flag:

- **Ambiguity** — wording a careful reader could reasonably implement two ways.
- **Internal inconsistency** — two sections (or a grammar production and its prose) that disagree.
- **Underspecification** — a case the grammar/prose doesn't address where an implementation must still
  pick something.
- **Errors** — plain mistakes (wrong cross-reference, grammar that doesn't parse its own examples).

When you find one: say so in conversation, and record it in `SPEC-FEEDBACK.md` (spec section, concrete
description, the interpretation this implementation chose and why, suggested resolution). Don't silently
pick an interpretation — a resolved ambiguity is invisible again three sessions later unless written down.

**Part 3 is drafted here, so edit `spec/tson-part3-json.md` directly as you go.** [TSON-JSON] is a very early
draft and this implementation exists to validate it, which makes the loop tighter than for Parts 1 and 2: a
finding becomes a **spec change in the same session**, in place, with git history as its record — not a
register entry waiting for someone else's adjudication. Parts 1 and 2 keep the register, because their current
revision is published and this implementation *proposes* changes to them rather than making them; Part 3 has
no published revision to be behind, so there is nothing to propose against.

What that changes in practice:

- **A Part 3 finding does not go in `SPEC-FEEDBACK.md`.** Fix §N and say so in the commit. An entry spanning
  Part 3 and an earlier part stays in the register, and says which half is which.
- **Edit the prose, not just a note beside it.** An underspecification is closed by stating the rule; an
  overclaim by correcting the sentence. Where the choice is genuinely open, state the rule *and* why the
  alternative was not taken, so the author is reading a decision rather than a shrug.
- **Cite the section, not this implementation.** The document never mentions this codebase, a Java type, or a
  test. What running code buys is confidence that a rule is implementable and that its consequences were
  followed; the document states the rule.
- **The obligation runs the other way too.** Implementing a section is when its wording gets its only real
  reading — so a section you build against and leave unedited is a section you are asserting is right.
- **Keep it to what implementation taught you.** A Part 3 edit should trace to something the code forced a
  decision about. Rewriting prose that no reader tripped over is churn in a document someone else is also
  editing.
A finding still open is cited by number (`SPEC-FEEDBACK.md` #N); once the spec carries the rule, the
citations name the section instead.

**The register holds what is open against the current revision, and renumbers from #1 when a revision
closes.** It is an input to the next revision's adjudication, so its numbering is what that revision's
change log will answer against. The evidence beside it is this implementation itself — an entry proposing a
design states what is running — which is why the shared corpus's `proposed/` bucket stays empty here: the
proposal is the code, not a vector another implementation is asked to fail. Entries whose resolution
landed are deleted; the closing revision's change log in `spec/` keeps all of them under *their* numbers.
**Cite the spec, not the argument that got it there.** Prose and Javadoc state the rule as built and name
the current section that requires it; a `SPEC-FEEDBACK.md #N` citation is for an entry still open, where
there is no section to point at yet. When
an entry closes, the citations to it become spec citations — the reasoning has served its purpose and the
spec now carries the rule.

**The register is the as-built record, and it is self-contained.** It is what goes to the spec reviewer, so
an entry proposing a design this implementation has built states the design, what is running, and what is
not, rather than pointing at a design document beside it. Where an entry's recommendation is a proposal
rather than a report, it says so at the point it makes it — a reviewer adopting a rule needs to know which
claims are running code. Working design documents are not kept in `spec/`: once a design lands, the entry
absorbs what survives of the argument and the document goes, git history keeping it.


## Conventions at full length

**Javadoc documents current contract only, no change history.** Java source Javadoc describes an element's
*current* behavior — never dates, "renamed from X", "used to do Y, now does Z", "on the user's direction",
or similar changelog framing. If a design needs a WHY, state the current invariant and its rationale
directly. When you edit a class, clean up its Javadoc in the same edit — remove stale narrative (even if
you didn't write it), fix anything that no longer matches the code, tighten what's left. The `design/` notes
and this file follow the same no-history rule; the dated log lives in git.

**`BACKLOG.md` is a clean list of outstanding work and nothing else.** Every entry names something someone
could pick up and do. Three things are therefore not entries, however true: **what was done** (an item that
ships comes out entirely — not annotated as complete, not kept as a record of how it was solved), **what was
decided against** (a won't-do is not work), and **what might become work later** (a standing note to revisit
something if conditions change is not actionable today, and sits in the list forever looking like a task).
Prose inside a live entry follows the same rule — say what is left and what constrains it; recounting which
halves already work turns an item into a status report that goes stale silently. Where one of those facts has
to survive its entry, it belongs in the `design/` note, the Javadoc, or the test that owns the area, where the
person who trips over it will be looking. Git history is the log.

**Keep the `design/` note current in the same session as the change.** When work alters behavior an area's
design note describes, update that note the way you'd update the class's Javadoc — same edit, not a
follow-up. A note that silently drifts is worse than no note.

**`Tson` is a prefix, never an infix.** A class name containing `Tson` must lead with it (`TsonSchema`,
`TsonDataParser`, `TsonCompiledSchema`) — never buried (`CompiledTsonSchema` is wrong). The prefix is
**not** applied to every class: most internal machinery is deliberately bare (`Lexer`,
`RecordAbstractReader`, `DeferredTypeReader`, `ChoiceDisjointness`, `SchemaResolver`,
`DefinitionResolver`). Reserve `Tson` for types a *consumer of this library* names in their own code — its
value is disambiguation at the call site (`TsonSchema` vs. a domain `Schema`). When adding a new public,
developer-facing type, ask "would a consumer plausibly have their own class with this bare name?" — if yes
and it's consumer-facing, prefix it; if it's internal machinery, leave it bare.

**Exception classification is a policy, not a style choice.** Across the schema pipeline:
`TsonSchemaValidationException` means *the author's schema is wrong and the spec says so*;
`UnsupportedOperationException` means *this library hasn't implemented that yet*; `IllegalStateException`
means an internal invariant broke. The classification test: **a schema error's verdict doesn't change when
this library improves; a gap's does.** A gap is not a verdict on the author's schema, and the CLI's exit 1
vs. exit 70 rides on that distinction — **carried by `Diagnostic.Code.NOT_IMPLEMENTED`, not by the channel**.
Both kinds are collected: a gap thrown out of a phase that reports per declaration took every other
declaration's verdict with it, so the schema pipeline reports it beside the ordinary problems and the code
keeps it apart. The exception classification itself is unchanged and is what picks the code.
`DefinitionResolver`'s Javadoc lists the exact current boundary.

**Project-owned schema `!!id`:** a schema this project authors (not the spec's own bundled artifacts) gets
`https://tson.io/2026/36/ltr8/<group>/<name>-<version>.tn` — `/2026/36` is the spec revision, `ltr8` the
publishing org, `<group>` the subsystem (`cli`), `<name>-<version>` the schema name with a trailing
integer version. **The version is bumped on a release, not on a change.** §10's immutability rule binds a
*published* identity: once a release ships carrying the schema, the document under that `!!id` is fixed and
a later shape change mints the next version (`diagnostics-12.tn`) rather than editing it. Between releases
— while the build version carries `-SNAPSHOT`, so nothing has published the identity — the schema is in
development and is edited in place. Bumping per change instead mints versions nobody ever consumed, one for
every field added during a development cycle. **Use `.tn`, not `.tn1`** — `.tn1` is a stability claim §7.1
reserves for the eventual frozen "TSON version 1", which hasn't happened.

**Line wrapping:** wrap both comments and code to 125 characters.

