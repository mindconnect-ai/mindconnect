#!/usr/bin/env bash
# Gives this checkout its own Maven version suffix, derived from the branch.
#
# Every pom declares its version as ${revision}${sha1}${changelist}. This script
# writes the middle part into .mvn/maven.config, which Maven reads for every
# invocation inside this tree (start.sh included), so a branch builds and
# installs as 0.x.y-<branch>-SNAPSHOT — and parallel branches stop overwriting
# each other in the one shared ~/.m2. The file is git-ignored: the pom is
# identical on every branch, and nothing has to be undone before a merge.
#
# Run it once after creating (or checking out) a branch, from anywhere inside
# the repository. Safe to re-run. `main` gets an explicitly empty suffix, so a
# clone that moves back to main builds the plain version again.
#
#   ./after-branch-creation.sh              # suffix from the current branch
#   ./after-branch-creation.sh fix/thing    # suffix for a branch by name
set -euo pipefail

root=$(git rev-parse --show-toplevel)
branch=${1:-$(git -C "$root" rev-parse --abbrev-ref HEAD)}
if [ "$branch" = HEAD ]; then
    echo "after-branch-creation: detached HEAD — pass the branch name as an argument" >&2
    exit 1
fi

# The same slug the snapshot workflow derives from GITHUB_REF_NAME: prefixes
# such as fix/ or feature/ become part of it, '/' turns into '-', everything
# else that is not [a-z0-9.-] is folded to '-'. The dash sits last in the tr
# set on purpose — anywhere else GNU tr reads it as a range.
if [ "$branch" = main ]; then
    sha1=""
else
    sha1="-$(printf '%s' "$branch" | tr '/A-Z' '-a-z' | tr -c 'a-z0-9.\n-' '-')"
fi

config="$root/.mvn/maven.config"
mkdir -p "$root/.mvn"
# Replace only our own line; other options someone put there stay.
{ [ -f "$config" ] && grep -v '^-Dsha1=' "$config" || true; echo "-Dsha1=$sha1"; } > "$config.tmp"
mv "$config.tmp" "$config"

echo "branch:  $branch"
echo "written: ${config#"$root"/}  ->  -Dsha1=$sha1"
if version=$(cd "$root" && mvn -q -ntp help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null); then
    echo "version: $version"
fi
