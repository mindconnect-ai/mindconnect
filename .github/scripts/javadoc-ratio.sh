#!/usr/bin/env bash
# D5 (advisory): files whose comment lines exceed 30 % of all lines. Javadoc says
# why or states an invariant; a file that is a third comment is restating code.
#   .github/scripts/javadoc-ratio.sh [files...]   default: main sources changed vs origin/main
set -uo pipefail
root=$(git rev-parse --show-toplevel); cd "$root"
if [ $# -eq 0 ]; then
    mapfile -t files < <(git diff --name-only origin/main...HEAD -- '*.java' | grep '/src/main/')
else
    files=("$@")
fi
for f in "${files[@]}"; do
    [ -f "$f" ] || continue
    total=$(grep -c '' "$f"); [ "$total" -gt 60 ] || continue
    comments=$(grep -cE '^\s*(\*|/\*\*|//)' "$f")
    pct=$((comments * 100 / total))
    [ "$pct" -gt 30 ] && echo "D5: $f is $pct% comment lines ($comments/$total)"
done
exit 0
