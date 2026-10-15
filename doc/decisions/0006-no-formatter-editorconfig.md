# 0006: No code formatter in the build; .editorconfig carries the shared settings

Status: accepted · 2026-10-14

## Decision
The build runs no formatter. `.editorconfig` holds indentation, line length,
import order and newline rules for IDEs and coding agents. Whether a
formatter (Spotless with a ratchet from `origin/main`) is needed is decided
after the first cleanup waves, from the diffs.

## Why
A repository-wide reformat is one commit that conflicts with every open
branch and takes over `git blame` for good, for a benefit that is unproven
here: agents and IntelliJ already produce consistent code, and formatting
has not been a review topic.

## Consequences
Formatting is not a reviewer finding. If diffs in the tidy branches show
formatting noise, Spotless is added with `ratchetFrom origin/main`, so only
touched files change.
