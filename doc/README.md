# doc/

What is true and what applies, written for the people and the coding agents
that change this repository. Short, English, versioned with the code.

| Folder | Holds | Written by |
| --- | --- | --- |
| `agent/` | the rules: conventions with examples, architecture as it is | humans, in a PR |
| `decisions/` | one file per decision (ADR): what was decided and why, 10–30 lines | humans, or an agent on request |
| `known-gaps.md` | code findings that are out of scope for the branch they were found on, with module, size and rule | agents and the maintainer while working, with `/gap` |

What does not live here: the reasoning behind a decision, alternatives that
were considered, product ideas, run reports and measurements. Those stay in
the maintainer's private notes repository; a decision file links back to its
concept there.

The area folders `agents/doc/` and `workflow/doc/` keep their own material
(architecture diagrams, presentations) as before.
