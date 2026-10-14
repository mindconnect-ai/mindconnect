#!/usr/bin/env bash
# PostToolUse on Edit|Write. Compiles the module the file belongs to and hints
# when a file is mostly comment. Never blocks (the tool already ran); exit 2
# only puts the message in front of the agent.
set -uo pipefail
input=$(cat)
file=$(printf '%s' "$input" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))' 2>/dev/null)
case "$file" in *.java) ;; *) exit 0;; esac
[ -f "$file" ] || exit 0
module=${file%%/src/*}
[ -f "$module/pom.xml" ] || exit 0

msg=""
case "$file" in
    */src/main/*) goal=compile;;
    */src/test/*) goal=test-compile;;
    *) goal=compile;;
esac
if ! out=$(mvn -q -ntp -o -f "$module/pom.xml" "$goal" -Dpmd.skip -Djacoco.skip 2>&1); then
    msg="Compile error in $module after editing $file:
$(printf '%s' "$out" | grep -E 'ERROR.*\.java|error:' | head -15)"
fi

case "$file" in */src/main/*)
    total=$(grep -c '' "$file"); comments=$(grep -cE '^\s*(\*|/\*\*|//)' "$file")
    if [ "$total" -gt 60 ] && [ $((comments * 100 / total)) -gt 30 ]; then
        msg="$msg
D5: $file is $((comments * 100 / total))% comment lines ($comments of $total). Javadoc says why or states an invariant; it never restates the code (doc/agent/conventions.md)."
    fi;;
esac

if [ -n "$msg" ]; then printf '%s\n' "$msg" >&2; exit 2; fi
exit 0
