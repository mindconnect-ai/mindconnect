#!/usr/bin/env bash
# Stop hook. The agent may not say "done" until the touched modules verify, no
# test disappeared, and the branch's progress file was updated this turn.
# Exit 2 blocks the stop and feeds the text back to the agent.
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)" || exit 0
source .claude/hooks/lib.sh
input=$(cat)

# Nothing changed in source: nothing to verify.
modules=$(touched_modules)
if [ -z "$modules" ]; then exit 0; fi

# Nothing changed since the last green run: do not verify the same state again
# (a chat-only turn on a long branch would otherwise pay for the full check).
marker=.claude/.verified
fingerprint=$( { git rev-parse HEAD; git diff; git diff --cached; git ls-files --others --exclude-standard; } | shasum | cut -d' ' -f1 )
if [ -f "$marker" ] && [ "$(cat "$marker")" = "$fingerprint" ]; then exit 0; fi

fail=""
log=$(mktemp)
if ! ./scripts/verify.sh $modules >"$log" 2>&1; then
    fail="verify.sh is not green for the modules you touched ($(echo $modules | tr '\n' ' ')):
$(grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])|FAIL|ERROR\]|PMD .* found|violat' "$log" | head -25)
Fix the cause; do not delete tests or edit baselines."
fi
rm -f "$log"

base=$(base_ref)
for m in $modules; do
    before=$(test_count "$base" "$m"); after=$(test_count "" "$m")
    if [ "$after" -lt "$before" ]; then
        fail="$fail
D6: $m has $after @Test methods, $base had $before. Tests are fixed, not deleted."
    fi
done
if git diff "$base" -- '*.java' | grep -qE '^\+\s*@Disabled\s*$|^\+\s*@Disabled\(\s*\)'; then
    fail="$fail
D6: @Disabled without a reason string."
fi

# A spec branch keeps its progress file current.
slug=$(branch_slug)
spec="doc/specs/$slug.md"; progress="doc/specs/$slug.progress.md"
if [ -f "$spec" ]; then
    if [ ! -f "$progress" ]; then
        fail="$fail
This branch has a SPEC ($spec) but no progress file. Create $progress (format: doc/specs/README.md) with Status, Next and one log line before you stop."
    else
        turn_start=$(printf '%s' "$input" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("turn_started_at",""))' 2>/dev/null)
        age=$(( $(date +%s) - $(stat -f %m "$progress" 2>/dev/null || stat -c %Y "$progress") ))
        if [ "$age" -gt 1800 ] || ! grep -q '^\*\*Next:\*\*' "$progress" || ! grep -q '^\*\*Status:\*\*' "$progress"; then
            fail="$fail
Update $progress before you stop: Status, Next (one sentence a fresh session can act on), one dated log line. Last update was $((age / 60)) minutes ago."
        fi
    fi
fi

if [ -n "$fail" ]; then printf '%s\n' "$fail" >&2; exit 2; fi
printf '%s' "$fingerprint" > "$marker"
exit 0
