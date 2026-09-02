#!/usr/bin/env bash
#
# Fetch the shared conformance test suite into the gitignored .references/ directory.
#
#   ltr8-io-tson-test-suite  pinned to SUITE_PIN so the corpus cannot move underneath a build
#
# The suite is pinned to a commit, never a branch: a vector added or reshaped upstream would
# otherwise turn this repo's CI red with no change here, and a corpus migration would break
# every consumer at once. Bumping the pin is a deliberate commit.
#
# Idempotent: re-running fetches only what changed. Pass --force to re-clone from scratch.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REF_DIR="$REPO_ROOT/.references"

SUITE_REPO="https://github.com/litterat/ltr8-io-tson-test-suite"
SUITE_PIN="93dce971741832fa4d4763668b171b2e7b1c4502"

if [ "${1:-}" = "--force" ]; then
  echo "==> --force: removing $REF_DIR"
  rm -rf "$REF_DIR"
fi

mkdir -p "$REF_DIR"

# fetch_pinned <dir> <url> <committish>
# Shallow-fetches exactly one commit into a checkout at <dir>.
fetch_pinned() {
  local dir="$1" url="$2" ref="$3"
  local path="$REF_DIR/$dir"

  if [ ! -d "$path/.git" ]; then
    echo "==> cloning $dir"
    rm -rf "$path"
    git init --quiet "$path"
    git -C "$path" remote add origin "$url"
  fi

  local head
  head="$(git -C "$path" rev-parse --verify --quiet HEAD || true)"
  if [ -n "$head" ] && [ "$head" = "$(git -C "$path" rev-parse --verify --quiet "$ref^{commit}" || true)" ]; then
    echo "==> $dir already at $ref"
    return
  fi

  echo "==> fetching $dir @ $ref"
  git -C "$path" fetch --depth 1 --quiet origin "$ref"
  git -C "$path" checkout --quiet --detach FETCH_HEAD
  echo "    $dir -> $(git -C "$path" rev-parse --short HEAD)"
}

fetch_pinned ltr8-io-tson-test-suite "$SUITE_REPO" "$SUITE_PIN"

echo
echo "References ready in .references/"
