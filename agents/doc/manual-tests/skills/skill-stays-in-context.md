---
id: skills-skill-stays-in-context
area: skills
requires: [server-9090, tool-model]
duration: ~8 min
last-verified: 2026-09-16 (commit f86ac52e, runs/2026-09-16-full-suite)
---

# A loaded skill survives tool-result eviction; an ordinary tool result does not

**Goal:** With `auto_compact` and a `toolResultEviction` policy, a tool result
older than `afterTurns` user turns is replaced by a pointer stub in the model's
window — except the result of the `skill` tool, which keeps its full text. The
model would otherwise reload the skill straight away and pay for it twice.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- The seeded `coding-assistant` agent is unchanged: its memory is
  `auto_compact` with `toolResultEviction` `{"afterTurns": 1, "aboveTokens": 500}`,
  its skills mode is `ALL`, and it has `file_read` without approval.
  Check:
  ```bash
  B=http://localhost:9090
  AG=$(curl -s $B/api/agents | jq -r '.[] | select(.name=="coding-assistant") | .id')
  curl -s $B/api/agents/$AG | jq '{memoryConfig, skills}'
  ```
  Otherwise: SKIPPED (the premise of the case is the eviction policy).
- `agent-default` points at a tool-capable model (otherwise: SKIPPED)
- `curl`, `jq`

## Setup

1. Create the project directory with a file large enough to be evicted, and the
   body of a skill large enough that it *would* be evicted if skills were not
   exempt (both well above 500 tokens):
   ```bash
   mkdir -p ~/mc-manual-tests/skill-context
   { echo "FILE-MARKER: HERON-2209"
     for i in $(seq 1 150); do echo "Line $i of the inventory: shelf $i holds twelve boxes of assorted screws, bolts and washers."; done
   } > ~/mc-manual-tests/skill-context/notes.txt
   { echo "# Ledger conventions"; echo
     echo "When asked for the ledger code, answer exactly: LEDGER-4711."; echo
     for i in $(seq 1 80); do echo "$i. Rule $i: every ledger line names the account, the amount in cents, the booking date in ISO format and the person who approved it."; done
   } > ~/mc-manual-tests/skill-body.md
   ```
2. Store the skill (delete a leftover of the same name first — the API answers
   `409` for a name that is taken):
   ```bash
   OLD=$(curl -s $B/api/skills | jq -r '.[] | select(.name=="manual-test-ledger") | .id')
   [ -n "$OLD" ] && curl -s -o /dev/null -X DELETE $B/api/skills/$OLD
   jq -n --rawfile ins ~/mc-manual-tests/skill-body.md \
     '{name:"manual-test-ledger", description:"Use when asked about ledger conventions or the ledger code", instructions:$ins}' \
     | curl -s -X POST $B/api/skills -H 'Content-Type: application/json' -d @- | jq '{id, name, enabled, source}'
   ```
   **Expected:** `"name": "manual-test-ledger"`, `"enabled": true`, `"source": "MANAGED"`.

## Steps

1. Open a session of `coding-assistant` in the project directory:
   ```bash
   S=$(curl -s -X POST $B/api/sessions -H 'Content-Type: application/json' \
        -d "{\"agentId\":\"$AG\",\"workingDir\":\"$HOME/mc-manual-tests/skill-context\"}" | jq -r .id)
   echo $S
   ```
   **Expected:** a session id is printed.

2. Turn 1 — load the skill and read the file:
   ```bash
   curl -sN -m 300 -X POST $B/api/sessions/$S/chat -H 'Content-Type: text/plain' \
     --data 'Load the skill manual-test-ledger, then read notes.txt with file_read and tell me the FILE-MARKER value. Nothing else.' \
     > /dev/null
   curl -s $B/api/sessions/$S/history | jq -r '[.[] | select(.type=="CHAT" and .senderType=="AGENT")] | last | .content'
   ```
   **Expected:** the stream ends by itself; the answer printed is `HERON-2209`.
   (Do not grep the stream for it: the answer arrives as separate tokens —
   `HER`, `ON`, `-`, … — and the marker a grep finds is the one inside the
   `file_read` result.) The
   history holds a `skill` call and a `file_read` call:
   ```bash
   curl -s $B/api/sessions/$S/memory | jq -r '.messages[] | select(.type=="TOOL_RESULT") | .content | fromjson | .toolName'
   ```
   prints `skill` and `file_read` (a model that reads the file first prints
   them the other way round — the order does not matter).

3. Before the next user message, nothing is evicted yet:
   ```bash
   curl -s $B/api/sessions/$S/memory | jq -r '.messages[] | select(.type=="TOOL_RESULT")
     | [(.content|fromjson|.toolName), .tokens, .compressed] | @tsv'
   ```
   **Expected:** two lines, both `false`; the `skill` line has `tokens` ≥ 500
   (otherwise the skill is too small to prove anything — re-check Setup 1).

4. Turn 2 — a plain follow-up that needs the skill's text:
   ```bash
   curl -sN -m 300 -X POST $B/api/sessions/$S/chat -H 'Content-Type: text/plain' \
     --data 'What is the ledger code? Answer from what you already have; do not call any tool.' \
     > /dev/null
   curl -s $B/api/sessions/$S/history | jq -r '[.[] | select(.type=="CHAT" and .senderType=="AGENT")] | last | .content'
   ```
   **Expected:** `LEDGER-4711`.

5. Inspect the window the next model call gets:
   ```bash
   curl -s $B/api/sessions/$S/memory | jq -r '.messages[] | select(.type=="TOOL_RESULT")
     | [(.content|fromjson|.toolName), .tokens, .compressed, ((.compressedContent // "")[0:40])] | @tsv'
   ```
   **Expected:**
   - the `skill` line: `compressed` = `false`, `tokens` unchanged from step 3,
     no stub;
   - the `file_read` line: `compressed` = `true`, and `compressedContent`
     starts with `[Tool result evicted from context — id=`. The full stub reads
     `[Tool result evicted from context — id=<message id>, originally ~N tokens. Call the `fetch_tool_result` tool with this id to reload the full content.]`.

6. The same in the admin UI: http://localhost:9090/admin/sessions/&lt;S&gt;/memory
   (the **Working Memory** view).
   **Expected:** in the **Window** list, the `file_read` result's entry reads
   `#N · AGENT · TOOL_RESULT · <few> tok · (compressed)`; the `skill` result's
   entry has no `(compressed)` and its token count is the large one. Clicking
   the skill entry → **LLM View** shows `# Skill: manual-test-ledger` and the
   rules; clicking the file entry → **LLM View** shows the stub.

7. Verify the model did not reload the skill in turn 2:
   ```bash
   curl -s $B/api/sessions/$S/memory | jq '[.messages[] | select(.type=="TOOL_RESULT") | .content | fromjson | .toolName] | map(select(.=="skill")) | length'
   ```
   **Expected:** `1`.

## Cleanup

- `curl -s -o /dev/null -X DELETE $B/api/sessions/$S`
- `curl -s -o /dev/null -X DELETE $B/api/skills/$(curl -s $B/api/skills | jq -r '.[] | select(.name=="manual-test-ledger") | .id')`
- `rm -rf ~/mc-manual-tests/skill-context ~/mc-manual-tests/skill-body.md`

## Notes

- The assertion that matters is step 5 (the `compressed` flag per tool name).
  Steps 2, 4 and 7 depend on the model: if it does not call `skill` in turn 1,
  re-send the message once; if it still does not, the case is SKIPPED for the
  model. A second `skill` call in step 7 is a model quirk, not a failure, as
  long as step 5 holds.
- Eviction is render-time only: the stored messages keep their full content
  (`content` in the memory JSON is still the whole result for both).
- `coding-assistant` has no `fetch_tool_result`, so the evicted file cannot be
  reloaded in this session; the case does not need it.
- Only `auto_compact` evicts tool results; `summarizing_window` compresses them
  instead (see `memory/tool-result-compression.md`).
- Automated twin: `AutoCompactEvictionStubTest.aLoadedSkillStaysInTheWindowWhereAnyOtherResultIsEvicted`
  (mc-agent-memory-strategies). The exemption is `AutoCompactStrategy.isSkill`,
  which matches the tool result's `toolName` metadata `skill`; what the unit
  test cannot show is that a real turn stores that metadata.
