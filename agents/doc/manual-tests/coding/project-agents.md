---
id: coding-project-agents
area: coding
requires: [server-9091, tool-model, jdk-21]
duration: ~8 min
last-verified: never
---

# Project agents: found in .mindconnect/agents, narrowed tools, project wins

**Goal:** Sub-agents a project keeps in `.mindconnect/agents/` are listed
and callable from a chat working in that project; their tools are the
caller's own narrowed by the file (never widened, approvals kept), and a
project agent wins over a registered agent of the same name.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `agent-default` points at a tool-capable model (otherwise: SKIPPED)
- The seeded `coding-assistant` is unchanged: its roster
  (`callableAgents`) is `explorer`, its `bash` needs approval
- `javac -version` prints 21 or newer in the server's shell

## Setup

1. Copy the fixture as in `coding/read-edit-verify.md` Setup 1. It brings
   `checker`, `explorer` and `reader` in
   `~/mc-manual-tests/calc-project/.mindconnect/agents/`.
2. Open http://localhost:9091/chat, **New chat**, **Model & tools** →
   **Agent** `coding-assistant` → **Apply**; folder button → absolute path
   of `~/mc-manual-tests/calc-project` → **Open** → **Use this folder**.
   Note the session id from the URL.

## Steps

1. Send: `Call list_agents and show me its output verbatim.`
   **Expected:** A `list_agents` card whose output is exactly three lines,
   in this order:
   `- checker (this project): Runs ./check.sh and reports exactly what it printed.`
   `- explorer (this project): This project's own explorer — it outranks the registered agent of the same name.`
   `- reader (this project): Reads the code and answers questions about it. Read-only — it cannot change files.`
   The registered `explorer` is not listed a second time.
2. Open http://localhost:9091/admin/sessions/<session-id>/traces, open the
   most recent LLM call and look at its request body.
   **Expected:** The `run_agent` tool description ends with
   `Available to you: checker, explorer, reader.`
3. Send: `Ask the reader agent what subtract in Calculator.java does.`
   **Expected:** A `run_agent` card for `reader`; its answer says that
   subtract adds its operands and ends with the line `-- reader`.
4. Send: `Ask the reader agent to create a file NOTES.md containing "hello".
   Only ask the reader; do not write anything yourself.`
   **Expected:** The reader answers that it has no tool for writing.
   `test -e ~/mc-manual-tests/calc-project/NOTES.md || echo absent` prints
   `absent`. Opening the reader's sub-session from its card shows only
   `file_read`, `grep` and `glob` as its tools.
5. Send: `Ask the explorer agent which files are under src/.`
   **Expected:** The sub-agent's answer begins with `project-explorer:` —
   the project's `explorer`, not the registered one.
6. Send: `Ask the checker agent to run the checks.`
   **Expected:** An **Approval required** card for `bash` appears in THIS
   chat while the `run_agent` card stays running — the checker's `bash`
   keeps the approval the caller's binding gives it.
7. Click **Allow once**.
   **Expected:** The checker reports exit code 1 and
   `FAIL subtract(7, 3): expected 4, got 10`; no file changed
   (`git -C ~/mc-manual-tests/calc-project status --short` prints nothing).
8. Remove the project's explorer on disk, without restarting anything:
   `rm ~/mc-manual-tests/calc-project/.mindconnect/agents/explorer.md`
   Send: `Call list_agents and show me its output verbatim.`
   **Expected:** The `checker` and `reader` lines as in step 1, followed by a
   line for the registered explorer that starts `- explorer: ` (no
   `(this project)`).

## Cleanup

- `rm -rf ~/mc-manual-tests/calc-project`
- Delete the chat (its sub-sessions go with it).

## Notes

- A project agent answers outside the caller's roster on purpose: the roster
  curates the registry, and the project's agents come from the same
  directory whose instructions the caller already follows. What they may do
  is bounded by the caller's tools, which is what makes opening a strange
  repository safe.
- A name in a file's `tools:` that the caller does not have grants nothing.
- Automated twins: `ProjectAgentsTest`, `InlineAgentToolsTest`
  (mc-agent-runtime-core), `ListAgentsToolTest` (mc-agent-tools).
