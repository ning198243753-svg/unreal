#!/usr/bin/env bash
# Stage everything, commit with the given message, and push to origin/main.
#
#   scripts/commit.sh "M2: add continuous test-provider driver"
#
set -euo pipefail
cd "$(dirname "$0")/.."

MSG="${1:-update}"

git add -A
if git diff --cached --quiet; then
  echo "nothing to commit"
else
  git commit -m "$MSG"
fi
git push origin HEAD:main
echo "pushed: $MSG"
