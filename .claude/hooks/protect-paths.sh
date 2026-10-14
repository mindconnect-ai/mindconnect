#!/usr/bin/env bash
# PreToolUse on Edit|Write. The harness is changed by a human in a PR, not by an
# agent in passing. Set MC_ALLOW_HARNESS_EDIT=1 in a session that works on the harness.
set -uo pipefail
input=$(cat)
file=$(printf '%s' "$input" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))' 2>/dev/null)
[ -n "$file" ] || exit 0
[ "${MC_ALLOW_HARNESS_EDIT:-0}" = 1 ] && exit 0
case "$file" in
    */.github/workflows/*|*/mc-java-parent/pom.xml|*/mc-java-parent/pmd-ruleset.xml|*/mc-java-parent/pmd-baseline.properties|*/archunit_store/*|*/doc/agent/review-policy.yml|*/.claude/settings.json|*/.claude/hooks/*)
        echo "Protected path: $file. The harness (build rules, baselines, hooks, review policy) is changed by a human in its own PR. Record what you need in doc/known-gaps.md and continue; or start the session with MC_ALLOW_HARNESS_EDIT=1 if this branch is about the harness." >&2
        exit 2;;
esac
exit 0
