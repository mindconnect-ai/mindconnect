---
name: gap
description: Record a code finding in doc/known-gaps.md in its format, quickly and without having to know the format. Use when the maintainer or an agent notices something outside the current task, e.g. during a review read-through.
---
Append one entry to the "Open" list of `doc/known-gaps.md`:

```
- [ ] **<module>** <what, one sentence; name the class and the smell>.
  Found <today> in <branch> by <the maintainer or the agent>. Size: <small|one wave|large>. Rule: <ArchUnit rule D1–D4, a heading of doc/agent/conventions.md, or –>.
```

Input: `$ARGUMENTS`, the module (or a class or path, from which you find the
module) and the finding, often in a few words. Fill in the rest yourself:
look at the class to name the smell precisely, and estimate the size (small:
under an hour of agent work; one wave: one `/tidy` run; large: several runs or
a redesign). Do not ask back; say in your reply what you assumed. Skip the
entry and say so when an equivalent one exists. Do not fix the finding now,
and do not commit.
