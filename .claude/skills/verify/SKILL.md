---
name: verify
description: The one check before "done" — the tests of the touched modules, then each touched area's ArchUnit rules against its frozen store. Call with module directories, or empty for the modules this branch changed against origin/next.
---
Verify `$ARGUMENTS`: module directories (e.g. `agents/core/mc-agent-runtime-core`),
or empty for every module with main or test sources changed on this branch
against `origin/next` (`git diff --name-only origin/next...HEAD`, the module
is the nearest directory with a `pom.xml`).

1. Per module, in dependency order (a `-core` before its sibling, `common/`
   before the areas):

   ```bash
   mvn -q -ntp -f <module>/pom.xml install
   ```

   Read `Tests run:` and `Skipped:`: a module whose tests all skip (pgvector on
   5433, an LM Studio model absent) is not green, it is untested; say so.

2. Once per area the modules belong to (`common`, `taskqueue`, `workflow`,
   `agents`), the arch-tests module against its frozen store:

   ```bash
   mvn -q -ntp -f <area>/*-arch-tests/pom.xml test
   ```

   A failure names the rule and the class. A new violation is fixed in the
   code; a frozen one that moved because a class was renamed is fixed in the
   same change or recorded with `/gap`. `archunit_store/` is never edited by
   an agent; the maintainer shrinks it after the merge with
   `-Darchunit.freeze.store.default.allowStoreUpdate=true`.

Report one line per module (`green`, `red: <first failure>`, or `skipped: <n>
tests`) and one per area, then one line: green or red. Nothing else; the
Maven output stays in the terminal.
