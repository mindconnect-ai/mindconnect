---
name: tidy-inspect
description: Read-only check of one module or package against doc/agent/conventions.md — duplicates, names, responsibilities, Javadoc, defensive redundancy, reinvented patterns — reported as a table, nothing changed. The report of one run is what /tidy takes a topic from.
---
Check `$ARGUMENTS` (a module directory, a package directory inside one, or a
class) against `doc/agent/conventions.md`. Read-only: no file changes, no
commits.

1. Read `doc/agent/conventions.md` and "Patterns with a name" in
   `doc/agent/architecture.md`.
2. Inventory: every main class in scope with lines, methods, constructor
   parameters, comment lines in percent, and its purpose in one sentence. A
   class whose purpose needs "and" is marked.
3. Look left and right: for each class, grep the repository for the same noun,
   the same method names and distinctive literals; list what exists elsewhere
   already, and blocks copied inside the scope or from a sibling module.
4. Read every class once for the judgement rules: names that say how instead
   of what, plurals, Helper/Util/Support/Commons/Holder/Manager; Javadoc with
   history, concept or step references, or that retells the body; null checks,
   try/catch and fallbacks that cannot trigger; a reinvented named pattern.
5. Frozen ArchUnit entries that name a class in scope:
   `grep -l <package> <area>/*-arch-tests/archunit_store/*`, one line per rule.

Report:

- the inventory table (class, lines, ctor params, comment %, purpose);
- a findings table (class, finding in one sentence, rule: the heading of
  `conventions.md` or the ArchUnit rule, size: small / one wave / large);
- the three findings you would fix first and why, in prose.

Do not fix anything. A finding the maintainer wants to keep goes to
`doc/known-gaps.md` with `/gap`; the fix is a `/tidy` run.
