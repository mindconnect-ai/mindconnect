---
name: arch-check
description: Architecture and metrics report — ArchUnit against the frozen stores, PMD against the baseline, class sizes, constructor widths, comment ratios — for the touched modules or the whole repository. Use to see whether a change breaks a rule before /inspect, or to refresh doc/agent/baseline.md.
---
Report the state of the sensors. `$ARGUMENTS` is empty (touched modules against `origin/main`), a list of modules, or `all`.

- **Touched or listed modules:** run `./scripts/verify.sh <modules>` and read the output. Then for each touched area, show the frozen ArchUnit store sizes (`<area>/*-arch-tests/archunit_store/`, one line per rule with the count) and any violation the run reported that the store does not cover. For each touched main source file: lines, methods, constructor parameter count, comment percentage.
- **all:** run `./scripts/baseline.sh` and present `doc/agent/baseline.md`'s summary table, the ArchUnit table and the ten largest classes.

Present as tables. Say in one line whether anything is red. Do not edit stores, baselines or rules: a human refreshes them in a ratchet step (`scripts/baseline.sh --refresh-pmd`, `mvn test -Darchunit.freeze.store.default.allowStoreUpdate=true` in an arch-tests module), after a tidy branch merged.
