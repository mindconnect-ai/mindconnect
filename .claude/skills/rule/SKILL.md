---
name: rule
description: Add or change a quality criterion in doc/agent/conventions.md, and make it an ArchUnit rule in common/mc-arch-rules when a tool can decide it. Use when an agent keeps getting the same thing wrong, or a convention is missing or wrongly worded.
disable-model-invocation: true
---
Add or change the criterion described in `$ARGUMENTS`.

1. Read `doc/agent/conventions.md` and find where the criterion belongs: an
   existing line to sharpen, or a new line under the fitting heading. Keep the
   page's form: one rule per line, imperative, with a good and a bad example
   from this repository where the heading has an example table (find a real
   one with grep; invent nothing).
2. Decide whether a tool can decide it: an import, a package, a name pattern, a
   count. If so, add it as a frozen rule in
   `common/mc-arch-rules/src/main/java/ai/mindconnect/arch/McArchRules.java`:
   `FreezingArchRule.freeze(...)`, the `.as("D<n>: ...")` text as the rule's
   one-line statement, and a line in the table "What the build checks" of
   `conventions.md`. A rule text is the key into the frozen stores; changing an
   existing text means refreezing all four areas.
3. If the criterion is a matter of judgement, it stays a line in
   `conventions.md`, and `/smells` and `/tidy` apply it through that page.
   Nothing else needs changing.
4. Freeze and verify. For a new or changed ArchUnit rule:
   `mvn -q -f common/mc-arch-rules/pom.xml install`, then in each of
   `common/mc-common-arch-tests`, `taskqueue/mc-task-queue-arch-tests`,
   `workflow/mc-workflow-arch-tests`, `agents/mc-agents-arch-tests`:
   `mvn -q test -Darchunit.freeze.store.default.allowStoreUpdate=true`.
   Show the frozen entries the new rule recorded, per area, and read them: a
   rule that catches names a framework dictates (an override of a supertype
   method) needs an exemption, not a frozen list. Then run the four arch-tests
   modules again without the flag: green.
5. One commit: `rules: <criterion in five words>`; the rule, the page and the
   stores together. Do not push.

Report the criterion as it now reads, whether it is checked by the build or
by judgement, and the frozen count per area for a new ArchUnit rule.
