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
# Why revision and changelist are written as well, although the pom defines
# them: IntelliJ's Maven server interpolates <parent><version> in the raw pom
# BEFORE the parent is looked up, with only the properties it already has —
# maven.config and the module's own. With -Dsha1 alone that yields the half-
# resolved "${revision}-feature-x${changelist}", which matches neither the
# parent pom at relativePath nor anything in ~/.m2, and IntelliJ reports
# "Non-resolvable parent POM ... 'parent.relativePath' points at wrong local
# POM". Either none of the three placeholders is a user property, or all of
# them are — so main gets no lines at all, and a branch gets all three, with
# revision/changelist copied from the root pom. Re-run after pulling a version
# bump, or the copy goes stale. (An empty -Dsha1= would not do for main either:
# IntelliJ reads "-Dname=" as name=true.)
#
# Run it once after creating (or checking out) a branch, from anywhere inside
# the repository. Safe to re-run.
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

# revision and changelist as the root pom currently defines them.
pom_prop() { sed -n "s|.*<$1>\(.*\)</$1>.*|\1|p" "$root/pom.xml" | head -1; }
revision=$(pom_prop revision)
changelist=$(pom_prop changelist)
if [ -z "$revision" ]; then
    echo "after-branch-creation: no <revision> in $root/pom.xml" >&2
    exit 1
fi

config="$root/.mvn/maven.config"
mkdir -p "$root/.mvn"
# Replace only our own lines; other options someone put there stay.
{
    [ -f "$config" ] && grep -v -e '^-Dsha1=' -e '^-Drevision=' -e '^-Dchangelist=' "$config" || true
    if [ -n "$sha1" ]; then
        printf -- '-Drevision=%s\n-Dchangelist=%s\n-Dsha1=%s\n' "$revision" "$changelist" "$sha1"
    fi
} > "$config.tmp"
mv "$config.tmp" "$config"

echo "branch:  $branch"
if [ -n "$sha1" ]; then
    echo "written: ${config#"$root"/}  ->  -Drevision=$revision -Dchangelist=$changelist -Dsha1=$sha1"
    if [ -z "$changelist" ]; then
        echo "warning: <changelist> is empty in the root pom (release commit?); IntelliJ reads an empty -D value as 'true'" >&2
    fi
else
    echo "written: ${config#"$root"/}  ->  no version suffix (main)"
fi
if version=$(cd "$root" && mvn -q -ntp help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null); then
    echo "version: $version"
fi
