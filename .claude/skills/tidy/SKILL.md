---
name: tidy
description: Behaviour-preserving cleanup of one module or package against doc/agent/conventions.md — duplicates, names, responsibilities, comments, defensive redundancy — one topic per commit. Call with a module or package path; without one, the main sources this branch changed against origin/next.
disable-model-invocation: true
---
Tidy `$ARGUMENTS`: a module directory, a package directory inside one, or empty
for the main sources this branch changed against `origin/next`. Behaviour does
not change.

1. **Green first.** `/verify <module>` is green, or stop and say so.
   Note the module's `@Test` count and the frozen ArchUnit entries that name a
   class in scope (`grep -rl <package> <area>/*-arch-tests/archunit_store/`).
2. **Inventory before any edit.** Read `doc/agent/conventions.md` and "Patterns
   with a name" in `doc/agent/architecture.md`. List every main class in scope
   with lines, constructor parameters and its purpose in one sentence. Then
   look left and right:
   - classes in scope that do the same thing, or that exist elsewhere in the
     repository already (grep the noun, the method names, distinctive literals);
   - blocks copied between classes in scope or between sibling modules;
   - a named pattern from `architecture.md` that is reinvented.
   Show the inventory as a table and the planned topics as a numbered list,
   smallest first.
3. **One topic per commit**, in this order where it applies:
   1. merge duplicates into the class that owns the responsibility; callers move to it;
   2. rename what says how instead of what, ends in Helper/Util/Support/Commons/Holder/Manager,
      is a plural, or starts with process/handle/do;
   3. split a class that needs "and" to be described, along the responsibilities
      from the inventory; extract a method over ~50 lines into named steps;
   4. delete comments and Javadoc that restate the code, tell history or reference
      a concept or step; keep why, invariants and side effects;
   5. remove null checks, try/catch and fallbacks that cannot trigger; replace
      inline fully qualified names with imports.

   After each topic: `/verify <module>` green with the same `@Test`
   count, then commit as `tidy(<module>): <what, in five words>`.
4. **Limits.** Rename or move public API only when every caller is in this
   repository, and change the callers in the same commit. No new abstractions,
   interfaces, parameters, configuration or features. Test sources change only
   to follow a rename or move. A finding outside the scope goes to
   `doc/known-gaps.md` with `/gap`, not into the diff.
5. **Frozen store.** ArchUnit must not gain a violation. A frozen entry names a
   class, so renaming or moving a class that has one shows up as a new
   violation: fix the violation in the same topic, or leave the name and record
   a gap. Never edit `archunit_store/`; the maintainer shrinks it after the
   merge with `mvn test -Darchunit.freeze.store.default.allowStoreUpdate=true`
   in the area's arch-tests module.

Report at the end: the inventory table, then per commit what was merged,
renamed, split or deleted, the frozen entries that are gone, and the gaps
recorded. If nothing needed tidying, say so in one line.
