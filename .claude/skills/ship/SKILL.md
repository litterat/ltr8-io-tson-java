---
name: ship
description: Take finished code work in this repo from working tree to an open, CI-green pull request - the pre-PR
  checklist (tests, build, line length, design note, BACKLOG sweep, conformance vectors, restamp), then issue, branch,
  commit, PR, and a CI result verified against the HEAD commit. Use when the user says "ship it", "same flow", "issue,
  branch, PR", or asks to open a PR for a change touching Java sources, tests or bundled schemas.
---

# Ship a change

The flow for **code work** — anything touching Java sources, tests, or `spec/m/*.tn`. A small doc-only tidy
(`BACKLOG.md`, `SPEC-FEEDBACK.md`, `design/`, `CLAUDE.md`) skips all of it: commit directly on the working branch
when asked to commit.

Two stops are built in, and neither is optional:

- **Stop before each commit.** Report what was verified and wait for the word. An earlier "commit" covered the earlier
  commit.
- **Stop at the open PR.** Green CI is the moment to report and stop, never the signal to merge. A merge needs the
  user's instruction *for that PR*; previous merges, a plan you narrated, or "waiting on CI" authorise nothing. Write
  "ready to merge on your word", not "I'll merge when CI passes".

## 1. Before the commit

Run these and report the results; fix what fails.

1. **Tests for the area, then `./gradlew build`.** `build` runs javadoc, so a dangling `{@link}` fails here.
2. **A fix's test fails without the fix.** Stash or disable the main-code change, rerun the new test, watch it fail,
   restore. Say in the report that you did. A test that passes either way guards nothing.
3. **Conformance run really ran.** A missing corpus aborts every vector and reads green. Confirm vectors executed
   (`TSON_REQUIRE_TEST_SUITE=1` makes absence a failure).
4. **`scripts/check-line-length.sh`** — added lines over 125 *characters*. Pre-existing long lines in touched files are
   not this change's to fix.
5. **`scripts/restamp-bundled-schemas.sh --check`** if anything under `spec/m/` moved; Part 2 §13.2's table is the one
   pin the script does not write.
6. **Javadoc of every edited class is current-form** — no history, no dates; stale narrative removed.
7. **The area's `design/` note says what the code now does.** Same session, not a follow-up.
8. **Sweep `BACKLOG.md`.** Re-read the relevant section and ask of *each* entry "does this branch make it false?" —
   not only the entry the work started from. Remove settled entries whole, and a heading left empty with them. Watch
   for an entry this branch itself wrote earlier.
9. **Spec findings recorded** — `SPEC-FEEDBACK.md` for Parts 1 and 2, an in-place edit for Part 3. Citations name the
   spec section unless the entry is still open.
10. **Conformance vectors added** for lexer/parser/resolver behaviour, in the corpus repo, on its branch of the same
    name. The corpus merges first; `SUITE_PIN` follows as a commit hash.

## 2. Issue, branch, commit, PR

1. `gh issue create` — the defect or the goal, with the reproduction. The issue is the durable record of *why*.
2. Branch off the branch the work belongs to — currently `r2026-36-proposal`, not `main`.
3. One commit (or the cadence the user set); message ends `Closes #N`, then the co-author line.
4. `git push -u origin <branch>`, then `gh pr create --base r2026-36-proposal`.

## 3. CI, verified against HEAD

`gh pr checks --watch` can return at once with the *previous* commit's finished run. Match the run to the commit:

```
SHA=$(git rev-parse HEAD)
gh run list --branch "$(git branch --show-current)" --json headSha,databaseId,status,conclusion \
  --jq ".[] | select(.headSha == \"$SHA\")"
gh run watch <id> --exit-status        # never piped: a pipe reports the last command's status
gh run view <id> --json headSha,status,conclusion
```

Report the PR link and the conclusion for that SHA. Then stop.

## 4. On the user's word to merge

`gh pr merge <n> --merge` — a merge commit, which keeps the branch visible in history. Then check the issue closed.
