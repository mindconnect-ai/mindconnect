#!/usr/bin/env bash
# Shared by the hooks. Sourced, not run.

repo_root() { git rev-parse --show-toplevel 2>/dev/null || pwd; }

# The branch's base: origin/main, or main when origin is not fetched.
base_ref() {
    if git rev-parse --verify -q origin/main >/dev/null; then echo origin/main; else echo main; fi
}

# Modules whose main or test sources changed against the base, one per line.
touched_modules() {
    local base; base=$(base_ref)
    { git diff --name-only "$base"...HEAD -- '*.java' '*.properties' '*.yml' '*.yaml' '*.json' '*.sql' 2>/dev/null
      git diff --name-only -- '*.java' '*.properties' '*.yml' '*.yaml' '*.json' '*.sql' 2>/dev/null
      git ls-files --others --exclude-standard -- '*.java' 2>/dev/null; } \
      | grep '/src/' | sed -E 's#/src/.*##' | sort -u \
      | while read -r d; do [ -f "$d/pom.xml" ] && echo "$d"; done
}

# @Test annotations in a module's test sources at a ref ("" = working tree).
test_count() {
    local ref=$1 module=$2
    if [ -z "$ref" ]; then
        grep -rho '@Test\b' "$module/src/test" 2>/dev/null | wc -l | tr -d ' '
    else
        git grep -ho '@Test\b' "$ref" -- "$module/src/test" 2>/dev/null | wc -l | tr -d ' '
    fi
}

# The slug of the current branch, as docs/specs and after-branch-creation.sh use it.
branch_slug() {
    git rev-parse --abbrev-ref HEAD 2>/dev/null | tr '/A-Z' '-a-z' | tr -c 'a-z0-9.\n-' '-'
}
