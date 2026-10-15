---
name: tidy
description: Behaviour-preserving cleanup of one module or package against doc/agent/conventions.md — one topic, usually a finding from /tidy-inspect, applied in the working tree, nothing committed. Call with the module or package and the topic.
disable-model-invocation: true
---
Tidy `$ARGUMENTS`: a module or package directory and one topic, usually a
finding from `/tidy-inspect`. Behaviour does not change. Nothing is committed.

1. The module's tests are green before you start; note the `@Test` count.
2. Do the topic and nothing else: merge the duplicate into the class that owns
   the responsibility and move the callers to it; rename; delete the comment;
   remove the check that cannot trigger. Rename or move public API only when
   every caller is in this repository, and change the callers in the same
   step. No new abstractions, interfaces, parameters, configuration or
   features. Test sources change only to follow a rename or move.
3. The module's tests are green with the same `@Test` count. When a class was
   added, renamed or moved, the area's ArchUnit rules too: `archunit_store/`
   is never edited, a frozen entry that moved is fixed or recorded with `/gap`.
4. A finding outside the topic goes to `doc/known-gaps.md` with `/gap`, not
   into the diff.

Report in three lines: what was merged, renamed, split or deleted; the test
count before and after; the commit message for the maintainer
(`tidy(<module>): <what, in five words>`).
