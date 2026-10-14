---
name: gap
description: Record a code finding in doc/known-gaps.md in its format, without having to know the format. Use when you notice something outside the current task.
---
Append one entry to the "Open" list of `doc/known-gaps.md`:

```
- [ ] **<module>** <what, one sentence; name the class and the smell>.
  Found <today> in <branch> by <you: agent, reviewer or the maintainer>. Size: <small|one wave|large>. Rule: <D1–D9, I1–I8 or –>.
```

Input: `$ARGUMENTS` (module and finding). If the size is not obvious, ask once with AskUserQuestion: small = under an hour of agent work, one wave = a tidy branch of its own, large = needs a spec. Check that an equivalent entry does not already exist. Do not fix the finding now.
