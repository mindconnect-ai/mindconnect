#!/usr/bin/env bash
# D6: the number of @Test methods per module must not fall against a base ref.
#   .github/scripts/test-count.sh origin/main
set -uo pipefail
base=${1:-origin/main}
root=$(git rev-parse --show-toplevel); cd "$root"
status=0
for m in $(git diff --name-only "$base"...HEAD -- '*.java' | sed -E 's#/src/.*##' | sort -u); do
    [ -f "$m/pom.xml" ] || continue
    before=$(git grep -ho '@Test\b' "$base" -- "$m/src/test" 2>/dev/null | wc -l | tr -d ' ')
    after=$(grep -rho '@Test\b' "$m/src/test" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$after" -lt "$before" ]; then
        echo "D6: $m has $after @Test, $base had $before. Tests are fixed, not deleted."; status=1
    fi
done
if git diff "$base"...HEAD -- '*.java' | grep -qE '^\+\s*@Disabled\s*$|^\+\s*@Disabled\(\s*\)'; then
    echo "D6: @Disabled without a reason."; status=1
fi
[ $status -eq 0 ] && echo "test-count: ok"
exit $status
