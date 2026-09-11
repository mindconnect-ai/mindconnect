---
id: coding-project-instructions
area: coding
requires: [server-9091, tool-model]
duration: ~6 min
last-verified: never
---

# Standing instructions: AGENTS.md in the working directory reaches the prompt

**Goal:** The instruction file of the directory a chat works in lands in the
system prompt, read fresh every round, with `AGENTS.md` before `PROMPT.md`
before `CLAUDE.md`; only the working directory itself is searched, and the
user's own file comes first.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `agent-default` points at a tool-capable model (otherwise: SKIPPED)
- `mindconnect.agent.instructions.user-dir` is unset (default
  `~/.mindconnect`)
- `~/mc-manual-tests` lies under `mindconnect.tools.working-dir-root`

## Setup

1. Copy the fixture as in `coding/read-edit-verify.md` Setup 1 (the `git`
   lines may be skipped).
2. Note whether the user file exists — never change it:
   `test -f ~/.mindconnect/AGENTS.md && echo present || echo absent`
3. Open http://localhost:9091/chat, **New chat**, **Model & tools** →
   **Agent** `coding-assistant` → **Apply**. Do NOT choose a folder yet.
   Note the session id from the URL.
4. In a terminal, a shortcut for the prompt checks below (authentication
   off, the app's default; otherwise use the Memory page):
   ```bash
   SID=<session-id>
   prompt() { curl -s "http://localhost:9091/api/sessions/$SID/memory" | jq -r .systemPrompt; }
   ```

## Steps

1. Send: `Your standing instructions may name a codeword for this project.
   What is it right now? Do not use any tool. If none is named, say
   "unknown".` — "the question" in the steps below.
   **Expected:** The answer is `unknown` (the chat works in its own session
   directory, which holds no instruction file).
2. Open http://localhost:9091/admin/sessions/<session-id>/memory, or run
   `prompt | grep '^## '`.
   **Expected:** The system prompt has NO `## Project instructions` section.
   If Setup 2 said `present`, it has a `## User instructions` section
   starting `From \`AGENTS.md\` in \`<home>/.mindconnect\``; if `absent`, it
   has none.
3. Folder button → type the absolute path of `~/mc-manual-tests/calc-project`
   → **Open** → **Use this folder**. Send the question from step 1 again.
   **Expected:** The answer is `amber-kettle`, with no tool call.
4. Reload the Memory page from step 2 (or run
   `prompt | grep -A4 '^## Project instructions'`).
   **Expected:** A `## Project instructions` section starting
   `From \`AGENTS.md\` in the working directory. This is the project
   speaking` and containing the `## Codeword` paragraph of the fixture. When
   a `## User instructions` section is there, it comes BEFORE the project
   section.
5. Change the file on disk, without restarting anything:
   `perl -pi -e 's/amber-kettle/cobalt-harbor/' ~/mc-manual-tests/calc-project/AGENTS.md`
   Send the question again.
   **Expected:** `cobalt-harbor` — the file is read fresh every round.
6. Make the other two names compete:
   ```bash
   cd ~/mc-manual-tests/calc-project
   mv AGENTS.md CLAUDE.md
   printf 'When asked for the codeword, answer `prompt-wins`.\n' > PROMPT.md
   ```
   Send the question again.
   **Expected:** `prompt-wins`; the Memory page names
   `From \`PROMPT.md\``. Only one file is used — the `cobalt-harbor`
   paragraph of `CLAUDE.md` is not in the prompt.
7. `rm ~/mc-manual-tests/calc-project/PROMPT.md`, send the question again.
   **Expected:** `cobalt-harbor`; the Memory page names `From \`CLAUDE.md\``.
8. Folder button → open `~/mc-manual-tests/calc-project/src` → **Use this
   folder**. Send the question again.
   **Expected:** `unknown`; the Memory page has no `## Project instructions`
   section — parent directories are not searched.

## Cleanup

- `rm -rf ~/mc-manual-tests/calc-project`
- Delete the chat.

## Notes

- A model that answers step 3 from a `file_read` of `AGENTS.md` still shows
  that it found the file, but not that the prompt carried it; the Memory
  check in step 4 is the authoritative one.
- The answers of earlier steps stay in the chat, and a small model can
  anchor on them — `gpt-5.4-mini` kept saying `unknown` after step 1 even
  though every LLM call carried the codeword (checked in the call traces
  under `<data.base-dir>/<namespace>/conversations/<id>/traces/`). A wrong
  answer with a right prompt is a model finding, not a FAIL of this case;
  ask the question in a fresh chat on the same directory to tell the two
  apart. Phrasing it "from what you already know" makes the anchoring worse.
- A server sets `mindconnect.agent.instructions.user-dir` to a path with
  `{user}` in it for one file per user, or to `off`.
- Automated twin: `InstructionFilesTest` (mc-agent-runtime-core).
