---
name: refine
description: Turn an idea, a todo or a concept into a SPEC the agent can implement alone — interview the maintainer, write doc/specs/<branch>.md and its progress file. Run before implementing a feature or a refactoring.
disable-model-invocation: true
---
Write the SPEC for this branch: `doc/specs/<branch-slug>.md` (slug = branch name, `/` → `-`, lower case). Format and frontmatter: `doc/specs/README.md`. Input: $ARGUMENTS (a sentence, and usually a link to a todo in mc-sandbox or a concept).

1. **Read before asking.** The linked todo or concept, `doc/agent/architecture.md` for the area, every decision in `doc/decisions/` that names the area, the open entries of `doc/known-gaps.md` for the modules you expect to touch, and the modules themselves (read only). If the todo is German, the SPEC is English.
2. **Interview with AskUserQuestion**, five to ten questions, the hard ones first: the behaviour at the edges, what is out of scope, which modules change and which must not, how each acceptance criterion is checked (unit, integration, manual), what "done" looks like end to end. No question the code or the documents already answer.
3. **Gaps.** Show the open known-gaps entries for the touched modules and ask which one the maintainer wants closed first in this branch as its own `tidy:` commit (expansion and contraction alternate). If a touched module has more than five open gaps, say that the next branch should be a `tidy/` branch and let the maintainer decide.
4. **Refactoring?** If the request is structural ("split X", "move Y", "no behaviour change"): read the class, list its responsibilities one sentence each, ask where each goes, and write the SPEC with the target structure (new classes, one sentence each), the rule "no behaviour change", the characterization tests to write first, the waves (each a diff under 400 lines) and the numbers to read at the end (lines, methods, fields, tests before and after).
5. **Write the SPEC**: frontmatter (`id`, `title`, `touches`, `depends-on`, `blocks`, `risk` from `doc/agent/review-policy.yml`, `source`), Behaviour, Out of scope, Touches, Acceptance (numbered, each with its check), Verify (the exact `./scripts/verify.sh` line).
6. **Write the progress file** `doc/specs/<slug>.progress.md`: Status `planned`, Next = the first acceptance criterion to implement test-first, Done = the acceptance criteria unchecked, one log line.
7. Hand the SPEC to the maintainer to correct in the editor. Then recommend a fresh session for implementation: "Implement doc/specs/<slug>.md. Test first per acceptance criterion. Run ./scripts/verify.sh on every touched module before you report done. Keep the progress file current."

You change no code.
