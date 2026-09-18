#!/usr/bin/env bash
# PreToolUse hook for Bash: before a `git commit`, refuse staged lines over 125 characters. Exit 2 blocks the call and
# hands the report to the model; anything else lets it through.
input=$(cat)
printf '%s' "$input" | grep -Eq '"command"[[:space:]]*:[[:space:]]*"[^"]*git( -C [^ ]+)? commit' || exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
report=$(scripts/check-line-length.sh 2>&1) && exit 0
{ echo "Lines over 125 characters in this change (scripts/check-line-length.sh):"; echo "$report"; } >&2
exit 2
