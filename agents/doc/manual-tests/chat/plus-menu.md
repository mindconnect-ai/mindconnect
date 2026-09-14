---
id: chat-plus-menu
area: chat
requires: [server-9091]
duration: ~6 min
last-verified: never
---

# The composer's "+": files, images, tools and sub-agents

**Goal:** The "+" opens a menu rather than the attach dialog. Each of its four
entries opens the picker that belongs to it; the tools and sub-agent pickers
change the chat while staying open, and what the menu says about the chat
(file count, tool count) is current after every change.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- At least two agents registered besides the chat's own — the seeded set has
  several under `sub-agents` (otherwise step 6 is SKIPPED)
- A small file to attach, any kind

## Steps

1. Open a new chat and click the **+** at the composer's left edge.
   **Expected:** A popover opens *above* the "+" (the composer sits at the
   bottom of the window) with four entries: **Upload files**, **Add images**,
   a separator, **Tools**, **Sub-agents**. **Tools** carries a badge with the
   number of tools the chat offers; a fresh chat has no file badge.
   Escape closes it, as does a click anywhere outside.

2. **+** → **Add images**.
   **Expected:** A dialog titled **Add images** whose drop zone says the
   images travel with the next message and whose button reads
   **Choose images…**. Opening the file chooser offers pictures only.
   Close it.

3. **+** → **Upload files** → **Attach…** → your file.
   **Expected:** An attach toast; the dialog lists the file. Close the dialog
   and open **+** again: **Upload files** now carries the badge `1`.

4. **+** → **Tools**.
   **Expected:** A dialog titled **Tools**: the groups (*Files*, *Web*, …)
   with `n of m` beside each name and an **Off | On | Search** switch on each
   group, and inside every group one row per tool with the same switch. No
   row for `run_agent`, `run_agents`, `list_agents` or `tool_search`. The
   groups that hold something on or searchable are already expanded.

5. Expand a collapsed group and set one of its tools to **On**, then
   **Search**, then **Off**.
   **Expected:** Each click answers on its own — the dialog stays open, the
   tinted segment moves to what you clicked, and the group's `n of m` and
   the header (`n on, n by search, n available`) follow. While a tool is on
   **Search** a line under the list says tool search is on; with nothing on
   Search the line is gone. Set the tool to **Search** once more, reload the
   page (F5) and open **+** → **Tools**: it is still on **Search**.

5a. On a group's own switch press **On**.
   **Expected:** Every tool in the group reads **On** and the group's switch
   tints **On**. Set one tool in it to **Off**: the group's switch shows no
   tint — its tools no longer share a state.

6. **+** → **Sub-agents**.
   **Expected:** A dialog titled **Sub-agents** with one row per agent — not
   the chat's own agent, not `title-generator` or the summarizers — each with
   its icon, its description, a **Test** button and **Off | On**. The ones the
   chat's agent brought are **On** (for `default-chat`: `url-reader` and
   `explorer`); the header reads `Sub-agents · n of m on`.

7. Switch an agent the chat's agent did not have to **On**, and one it had
   to **Off**.
   **Expected:** The dialog stays open and the header count follows. Switch
   every agent **Off**: the hint under the list says the chat calls no other
   agent. Open **+** → **Tools**: there is still no row for the delegation
   tools — they follow this picker, not the tool list.

8. One agent **On**, the rest **Off**. Close the dialog and ask the chat
   "Which agents can you call?".
   **Expected:** The chat calls `list_agents` and names only the agent that
   is on. Switch every agent **Off** and ask again: the chat has no
   `list_agents` or `run_agent` to call and says it cannot delegate.

8a. **+** → **Sub-agents** → **Test** on any agent.
   **Expected:** A dialog titled **Test <agent>** with a message field,
   **Send** and **Back**. Send with the field empty: a red line says to type
   a message first. Type "Say hello in one sentence" and Send: after the
   agent answers, a green-edged block shows the answer (rendered as
   Markdown) with the time it took, the form still above it. The chat
   history on the left gained no entry. **Back** returns to the Sub-agents
   picker.

9. Type half a sentence into the composer, then start a turn and — while it
   is still streaming — open **+** → **Tools** and toggle any tool.
   **Expected:** The composer keeps its **Stop** button; it does not turn
   back into a Send button mid-turn.

## Cleanup

- Delete the test chat.

## Notes

- Automated twin for the routes and the switch states:
  `ChatPlusMenuComponentTest` and `ChatPickerComponentsTest`
  (mc-agent-chat-ui-rest); the roster a chat sets over its agent's is pinned
  by `SessionAgentResolverTest` and `ListAgentsToolTest`. What they cannot
  check is the popover's placement and the flip-above, which is why step 1
  is a human step.
- The model button beside Send opens the settings dialog, which is the agent,
  the model and the system prompt — and nothing else. Its own case is
  `chat/settings-dialog.md`; the step that matters to this one is that
  applying it does not touch the tools switched on here.
