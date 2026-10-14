#!/usr/bin/env bash
# The one check. The Stop hook, a human and CI run this and nothing else, so
# what is green here is green everywhere.
#
#   ./scripts/verify.sh <module-dir> [<module-dir>...]   e.g. agents/core/mc-agent-runtime-core
#   ./scripts/verify.sh --area agents|workflow|taskqueue|common
#
# Per module: compile, unit tests, Jacoco report, PMD against the frozen
# baseline (the `sensors` profile, see mc-java-parent/pom.xml). Then, once per
# touched area, the area's ArchUnit rules against its frozen store.
set -uo pipefail
root=$(git rev-parse --show-toplevel)
cd "$root"

if [ "${1:-}" = "--area" ]; then
    area=$2
    if [ "$area" = common ]; then
        mapfile -t modules < <(grep -o '<module>common/[^<]*</module>' pom.xml | sed -E 's#</?module>##g' | grep -v arch-tests)
    else
        mapfile -t modules < <(grep -o '<module>[^.<][^<]*</module>' "$area/pom.xml" | sed -E 's#</?module>##g' | sed "s#^#$area/#" | grep -v -E 'parent|arch-tests')
    fi
else
    modules=("$@")
fi
[ ${#modules[@]} -eq 0 ] && { echo "usage: $0 <module-dir>... | --area <area>" >&2; exit 2; }

status=0
areas=""
for m in "${modules[@]}"; do
    m=${m%/}
    [ -f "$m/pom.xml" ] || { echo "== $m: no pom.xml, skipped"; continue; }
    echo "== $m"
    if ! mvn -q -ntp -f "$m/pom.xml" verify -DskipITs 2>&1 | tail -60; then
        status=1
    fi
    area=${m%%/*}
    case " $areas " in *" $area "*) ;; *) areas="$areas $area";; esac
done

for area in $areas; do
    arch=$(ls -d "$area"/*arch-tests 2>/dev/null | head -1)
    [ -n "$arch" ] || continue
    echo "== arch $area"
    if ! mvn -q -ntp -f "$arch/pom.xml" test -Dpmd.skip -Djacoco.skip 2>&1 | tail -40; then
        status=1
    fi
done

[ $status -eq 0 ] && echo "verify: green" || echo "verify: RED"
exit $status
