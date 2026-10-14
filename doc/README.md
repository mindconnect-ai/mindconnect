# doc/

What is true and what applies, written for the people and the coding agents
that change this repository. Short, English, versioned with the code.

| Folder | Holds | Written by |
| --- | --- | --- |
| `agent/` | the rules reviewers and hooks apply: architecture as it is, quality catalog, naming examples, review policy, baseline numbers | humans, in a PR |
| `decisions/` | one file per decision (ADR): what was decided and why, 10–30 lines | humans, or an agent on request |
| `specs/` | one SPEC per branch: what this branch builds and how it is verified; next to it the branch's progress file | `/refine` and the implementing session |
| `known-gaps.md` | code findings that are out of scope for the branch they were found on, with module, size and rule | agents and reviewers while working |

What does not live here: the reasoning behind a decision, alternatives that
were considered, product ideas, run reports and measurements. Those stay in
the maintainer's private notes repository; a decision file links back to its
concept there.

The area folders `agents/doc/` and `workflow/doc/` keep their own material
(architecture diagrams, presentations) as before.
