---
id: chat-settings-dialog
area: chat
requires: [server-9091]
duration: ~6 min
last-verified: never
---

# The chat's settings: agent, model, prompt — and nothing else it can lose

**Goal:** The dialog behind the composer's model button is three fields with no
tabs. Each of the three applies, including the two paths that used to drop
something silently: editing the prompt of a chat without an agent, and leaving
an agent behind.

**Why it is a manual case:** the guarantee worth checking is a *negative* one —
applying this dialog must not change the chat's tools. That is invisible in the
dialog itself; it shows up two clicks away, in the "+" menu's Tools picker.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- The seeded agents are present (`default-chat`, `Poet`); a new chat runs on
  `default-chat`
- Best run on a host WITHOUT Gmail credentials configured, which is the
  default — step 5 checks a tool the registry cannot resolve there

## Steps

1. Open a new chat, then **+** → **Tools** and write down the header count
   (`Tools · N on, M available`). Close the dialog.
   **Expected:** N is the number the "+" menu's **Tools** entry badges.

2. Click the **model button** in the composer (it names the chat's model,
   e.g. `agent-default`).
   **Expected:** A dialog titled **Agent, model & prompt** with exactly three
   fields, in this order: **Agent**, **Model**, **System prompt** — no tabs, no
   tool list, no tool-search checkbox. Below the last field a line reads
   "Tools and sub-agents are switched in the composer's "+" menu."
   The **Agent** options name each agent with what it is for
   (`Poet — A creative poet who …`); the **Model** options name a config with
   its provider and model, and an *alias* with what it points at
   (`agent-default → openai-default`) — never `(null / null)`.

3. Append a sentence to the **System prompt** and press **Apply**.
   **Expected:** The dialog closes and the chat is redrawn. Reopen it: the
   sentence is still there. **+** → **Tools**: the header count is still N
   from step 1.

4. Send `Which model are you and what is your first instruction?` and read the
   answer, then open the chat's Memory view (**…** → Memory) if the host
   offers one.
   **Expected:** The system prompt the model received carries your sentence.
   (Without a reachable model this step is SKIPPED; the prompt in step 3 is
   the assertion that matters.)

5. Reopen the dialog, set **Agent** to `— no agent: the model and prompt
   below —`, and press **Apply**.
   **Expected:** The chat detaches. **+** → **Tools**: the header count is
   STILL N — including any tool the registry cannot resolve on this host (with
   no Gmail credentials, `default-chat`'s three `gmail_*` tools are among
   them, so N does not drop by three). **+** → **Sub-agents**: the same roster
   as before, with the line about it being the agent's roster.

6. Reopen the dialog. Edit the **System prompt** again and press **Apply**,
   then reopen it once more.
   **Expected:** The second edit is there too. This is the path that used to
   accept the edit and throw it away: no agent behind the chat, no error, the
   old prompt back on the next open.

7. Reopen the dialog, choose **Poet** in **Agent**, press **Apply**, and open
   **+** → **Tools**.
   **Expected:** The chat is Poet's now: the composer's model button shows
   Poet's model and the tool count is POET's, not N. Switching to a different
   agent is the one case that replaces the tools — that agent's own tools are
   the point of switching.

8. Reopen the dialog and press **Cancel**.
   **Expected:** The dialog closes and nothing changed — the chat is still on
   Poet.

## Cleanup

- Delete the test chat.

## Notes

- Automated twin for the dialog's shape (three fields, no tabs, no tool
  controls): `ChatDialogActionUrlsTest` (mc-agent-chat-ui-rest). What it
  cannot check is what Apply does to the session, which is steps 3, 5, 6
  and 7.
- Steps 5 and 6 are regression steps for two bugs fixed together with the
  redesign; see the 0.8.2 changelog entries.
