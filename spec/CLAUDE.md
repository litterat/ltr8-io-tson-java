# spec/ — the spec cache, the bundled schemas, the Part 3 draft

`tson-part1-data.md` and `tson-part2-schema.md` are a cache of the published revision: read here, never edited, bar
Part 2 §13.2's artifact rows. The published text is at `https://tson.io/raw/2026/40/`; when in doubt, re-fetch and
check the revision number. `tson-rev40-changelog.md` is the closed revision's change log, which keeps every register
entry Revision 40 answered under its number. Three things here are live:

- **`m/*.tn` are the bundled schemas, packaged from here at build time.** Editing one means re-stamping: the library
  verifies the packaged bytes against `TsonBundledSchemas`' digests on every load, so one stale constant fails
  `Tson.standard()` and most of the suite. `scripts/restamp-bundled-schemas.sh` re-pins everything in dependency order
  (`--check` reports only). Part 2 §13.2's table is the one pin it does not write: move it by hand.
- **`fixtures/*-resolved.tn` stay in step with `m/`.** `ResolvedFixtureTest` checks them.
- **`tson-part3-json.md` is drafted here and edited in place.** A finding is an edit in the same session, said in the
  commit. State the rule in the prose and, where the choice was open, why the alternative lost. The document never
  mentions this codebase. Keep edits to what implementation forced a decision about; a section you build against and
  leave unedited is one you are asserting is right.

`design/process.md` has the procedure, and the rules for what a bundled schema's `@doc` may say.
