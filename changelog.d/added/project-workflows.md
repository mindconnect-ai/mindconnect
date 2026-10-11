- **agents:** **a project can keep its own workflows in `.mindconnect/workflows/*.yaml`.**
  Next to `.mindconnect/agents` and `.mindconnect/skills`, a short YAML format
  (`tool`, `agent`, `code`, `set`, `if`, `foreach`, `block`) defines workflows
  the agent runs with the new `run_workflow` tool, offered whenever the
  session's working directory has one. A project workflow only reaches the
  calling agent's own tools and agents (none that ask for an approval), runs
  its code in the `code_execute` sandbox rather than in the server, and
  evaluates expressions with a restricted MiniScript.
