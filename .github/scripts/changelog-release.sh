#!/usr/bin/env bash
#
# Assembles the changelog fragments in changelog.d/ into CHANGELOG.md.
#
# Every PR used to append its entry at the same spot under `## [Unreleased]`,
# so every merge left all other open PRs in conflict. Now each change is its
# own file, changelog.d/<section>/<slug>.md, and only a release turns them into
# a section of CHANGELOG.md — see changelog.d/README.md for how to write one.
#
# Usage (from anywhere inside the repository):
#   changelog-release.sh preview
#       Print the notes the next release would get. Changes nothing. Empty
#       output means there is nothing to release.
#   changelog-release.sh freeze <version> <date> <notes-file>
#       Replace `## [Unreleased]` with `## [<version>] - <date>` holding the
#       assembled notes, write the same notes to <notes-file>, and delete the
#       consumed fragments.
#   changelog-release.sh reopen <version>
#       Put an empty `## [Unreleased]` back above `## [<version>]`.
#
# Plain bash 3.2 + POSIX awk, no sed -i: runs on the ubuntu runner and on a
# stock macOS alike, so a release can be rehearsed locally.
#
# Environment (for tests): CHANGELOG (default CHANGELOG.md), FRAGMENTS
# (default changelog.d), both relative to the repository root.

set -euo pipefail
# Bytes in, bytes out: no locale may reinterpret the em dashes on the way.
export LC_ALL=C

cd "$(dirname "$0")/../.."
CHANGELOG=${CHANGELOG:-CHANGELOG.md}
FRAGMENTS=${FRAGMENTS:-changelog.d}

# Keep a Changelog's order. A fragment folder must be one of these (lower
# case); anything else is an error rather than an entry that silently never
# makes it into a release.
SECTIONS="Added Changed Deprecated Removed Fixed Security"

die() { echo "changelog-release: $*" >&2; exit 1; }

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# Drops leading and trailing blank lines, keeps everything in between as is.
trim_blank_lines() {
  awk '{ line[NR] = $0 } NF { if (!first) first = NR; last = NR }
       END { if (first) for (i = first; i <= last; i++) print line[i] }' "$@"
}

require_unreleased() {
  [ -f "$CHANGELOG" ] || die "$CHANGELOG not found"
  local n
  n=$(grep -c '^## \[Unreleased\][[:space:]]*$' "$CHANGELOG" || true)
  [ "$n" = 1 ] || die "$CHANGELOG needs exactly one '## [Unreleased]' heading, found $n"
}

# Lists the fragment files, sorted by path so the order is the same on every
# machine. Fails on anything that looks like a misplaced entry.
list_fragments() {
  [ -d "$FRAGMENTS" ] || return 0
  local path name
  for path in "$FRAGMENTS"/* "$FRAGMENTS"/.[!.]*; do
    [ -e "$path" ] || continue
    name=${path##*/}
    if [ -f "$path" ]; then
      case "$name" in README.md|.gitkeep) continue ;; esac
      die "$path: a fragment belongs in a section folder, e.g. $FRAGMENTS/fixed/$name"
    fi
    case " $(lower "$SECTIONS") " in
      *" $name "*) ;;
      *) die "$path: unknown section folder (use one of, lower case: $(lower "$SECTIONS"))" ;;
    esac
  done
  local section file first dir
  for section in $SECTIONS; do
    dir="$FRAGMENTS/$(lower "$section")"
    [ -d "$dir" ] || continue
    for file in "$dir"/* "$dir"/.[!.]*; do
      [ -e "$file" ] || continue
      case "${file##*/}" in
        .gitkeep) continue ;;
        *.md) ;;
        *) die "$file: fragments are Markdown files ending in .md" ;;
      esac
      [ -f "$file" ] || die "$file: not a file"
      first=$(trim_blank_lines "$file" | head -n 1)
      [ -n "$first" ] || die "$file: empty fragment"
      case "$first" in
        "- "*) ;;
        *) die "$file: must start with a bullet ('- **area:** **what changed.** ...')" ;;
      esac
      printf '%s\n' "$file"
    done
  done | sort
}

# Entries still written straight under `## [Unreleased]` (the way it was done
# before changelog.d existed, or a hand edit) are released too, merged into
# the same sections — never under a second heading of the same name.
# Writes <work>/legacy.names (one section per line, first appearance order)
# and <work>/legacy.<n> (its lines).
split_legacy() {
  local work=$1
  awk -v work="$work" '
    /^## \[Unreleased\][[:space:]]*$/ { inside = 1; next }
    inside && /^## \[/ { exit }
    !inside { next }
    /^### / {
      name = substr($0, 5); sub(/[[:space:]]+$/, "", name)
      if (!(name in idx)) { idx[name] = ++count; print name > (work "/legacy.names") }
      current = idx[name]; blanks = 0; started = 0; next
    }
    # Blank lines only between lines of one block: a heading that appears
    # twice must not leave a gap in the middle of the merged section.
    /^[[:space:]]*$/ { if (started) blanks++; next }
    /^<!--.*-->[[:space:]]*$/ && !current { next }
    {
      if (!current) { print "text under [Unreleased] outside a ### section: " $0 > "/dev/stderr"; bad = 1; exit }
      for (; blanks > 0; blanks--) print "" > (work "/legacy." current)
      print > (work "/legacy." current); started = 1
    }
    END { exit bad }
  ' "$CHANGELOG" || die "fix the [Unreleased] section of $CHANGELOG"
  touch "$work/legacy.names"
}

section_names() {
  local section
  for section in $SECTIONS; do printf "%s\n" "$section"; done
  while IFS= read -r section; do
    case " $SECTIONS " in
      *" $section "*) ;;
      *) printf "%s\n" "$section" ;;
    esac
  done < "$1"
}

# The notes of the next release: one ### block per section that has entries,
# legacy entries first (they were there earlier), then fragments by file name.
assemble() {
  local work=$1 fragments=$2
  split_legacy "$work"
  local names section n file separator=""
  # Known sections in their order, then any other heading found under
  # [Unreleased] (e.g. an old "Documentation") in the order it appeared.
  names=$(section_names "$work/legacy.names")
  while IFS= read -r section; do
    : > "$work/section"
    n=$(awk -v s="$section" '$0 == s { print NR; exit }' "$work/legacy.names")
    if [ -n "$n" ] && [ -f "$work/legacy.$n" ]; then
      trim_blank_lines "$work/legacy.$n" >> "$work/section"
    fi
    while IFS= read -r file; do
      [ -n "$file" ] || continue
      case "$file" in "$FRAGMENTS/$(lower "$section")/"*) trim_blank_lines "$file" >> "$work/section" ;; esac
    done < "$fragments"
    if [ -s "$work/section" ]; then
      printf '%s### %s\n\n' "$separator" "$section"
      cat "$work/section"
      separator=$'\n'
    fi
  done <<< "$names"
}

cmd=${1:-}
case "$cmd" in
  preview)
    [ $# -eq 1 ] || die "usage: $0 preview"
    require_unreleased
    work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
    list_fragments > "$work/fragments"
    assemble "$work" "$work/fragments"
    ;;

  freeze)
    [ $# -eq 4 ] || die "usage: $0 freeze <version> <date> <notes-file>"
    version=$2 date=$3 notes=$4
    require_unreleased
    grep -qF "## [$version]" "$CHANGELOG" && die "$CHANGELOG already has a [$version] section"
    work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
    list_fragments > "$work/fragments"
    assemble "$work" "$work/fragments" > "$work/notes"
    # The notes are read from a file, not passed with -v: awk would expand
    # the backslashes in them.
    awk -v heading="## [$version] - $date" -v notes="$work/notes" '
      /^## \[Unreleased\][[:space:]]*$/ {
        print heading; print ""
        while ((getline line < notes) > 0) { print line; wrote = 1 }
        if (wrote) print ""
        skipping = 1; next
      }
      skipping && /^## \[/ { skipping = 0 }
      !skipping { print }
    ' "$CHANGELOG" > "$work/changelog"
    cat "$work/changelog" > "$CHANGELOG"
    cat "$work/notes" > "$notes"
    # Consumed: deleted in the working tree, so the release commit
    # (`git commit -a`, plus an explicit `git add` of the folder) removes them.
    while IFS= read -r file; do
      [ -n "$file" ] && rm -f "$file"
    done < "$work/fragments"
    echo "Froze $(grep -c '^- ' "$work/notes" || true) entries from $(grep -c . "$work/fragments" || true) fragments into [$version] - $date"
    ;;

  reopen)
    [ $# -eq 2 ] || die "usage: $0 reopen <version>"
    version=$2
    grep -q '^## \[Unreleased\]' "$CHANGELOG" && die "$CHANGELOG already has an [Unreleased] section"
    work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
    # index(), not a regex match: the heading contains [0.0.3], which as a
    # pattern is a character class and would never match the line it came
    # from — silently leaving main with nowhere to write the next entry.
    awk -v rel="## [$version]" '
      index($0, rel) == 1 && !done { print "## [Unreleased]"; print ""; done = 1 }
      { print }
    ' "$CHANGELOG" > "$work/changelog"
    grep -q '^## \[Unreleased\]' "$work/changelog" || die "no '## [$version]' heading to reopen [Unreleased] above"
    cat "$work/changelog" > "$CHANGELOG"
    ;;

  *)
    sed -n '3,25p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
    ;;
esac
