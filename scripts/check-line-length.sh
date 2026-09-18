#!/usr/bin/env bash
# Reports lines over 125 characters -- characters, not bytes, so a line holding a section sign or an em dash is
# measured as it reads.
#
#   scripts/check-line-length.sh              lines this working tree ADDS relative to HEAD (staged and unstaged),
#                                             plus every line of an untracked file
#   scripts/check-line-length.sh <base-ref>   lines added since <base-ref>, e.g. r2026-36-proposal
#   scripts/check-line-length.sh --files F... every line of the named files
#
# Markdown table rows and lines holding a URL are skipped: neither can be wrapped. Exits 1 if anything is reported.
set -euo pipefail
LIMIT=125

if [[ "${1:-}" == "--files" ]]; then
  shift
  perl -CSD -ne '
    chomp; next if /^\s*\|/ || m{https?://};
    if (length($_) > '"$LIMIT"') { print "$ARGV:$.: ", length($_), "\n"; $bad = 1 }
    close ARGV if eof;
    END { exit($bad ? 1 : 0) }' "$@"
  exit $?
fi

BASE="${1:-HEAD}"
status=0
untracked=$(git ls-files --others --exclude-standard -- '*.java' '*.md' '*.kts' '*.tn' '*.sh')
if [[ -n "$untracked" ]]; then
  echo "$untracked" | tr '\n' '\0' | xargs -0 "$0" --files || status=1
fi
git diff "$BASE" -U0 --no-color -- '*.java' '*.md' '*.kts' '*.tn' '*.sh' | perl -CSD -ne '
  chomp;
  if (m{^\+\+\+ b/(.*)}) { $file = $1; next }
  if (/^@@ -\S+ \+(\d+)/) { $line = $1; next }
  next unless /^\+/;
  $text = substr($_, 1);
  if (length($text) > '"$LIMIT"' && $text !~ /^\s*\|/ && $text !~ m{https?://}) {
    print "$file:$line: ", length($text), "\n"; $bad = 1
  }
  $line++;
  END { exit($bad ? 1 : 0) }' || status=1
exit $status
