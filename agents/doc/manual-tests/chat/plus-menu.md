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
   **Expected:** A dialog titled **Tools**. On top a **Tool search** row with
   an **On**/**Add** switch; below it the groups (*Agents*, *Files*, *Web*, …)
   with `n of m` beside each name. The groups that hold something switched on
   are already expanded, the others are collapsed.

5. Expand a collapsed group and press **Add** on one of its tools, then press
   **On** on the same row again.
   **Expected:** Each click answers on its own — the dialog stays open, the
   row's button flips between **Add** and **On**, and the group's `n of m`
   and the list's header count follow. Close the dialog and open **+**: the
   **Tools** badge matches the header count you last saw. Reload the page
   (F5) and open **+** → **Tools**: the change is still there.

6. **+** → **Sub-agents**.
   **Expected:** A dialog titled **Sub-agents**: a **Hand work to other
   agents** row with a switch, then one row per agent the chat may call, each
   with its icon and its description. When the switch is off, every **Ask**
   button is disabled and says why.

7. Turn **Hand work to other agents** on, then press **Ask** on one agent.
   **Expected:** Turning it on enables the **Ask** buttons without closing
   the dialog. **Ask** closes the dialog and leaves the composer holding
   `Use the <agent> sub-agent to ` — nothing is sent. Anything you had
   already typed is still there, above the new line.

8. Type a task after the brief and press Send.
   **Expected:** An ordinary turn; the agent calls `run_agent` (a sub-agent
   card appears). Open **+** → **Tools** → *Agents*: `run_agent`,
   `run_agents` and `list_agents` are **On** — that is what the switch in
   step 7 did.

9. Type half a sentence into the composer, then start a turn and — while it
   is still streaming — open **+** → **Tools** and toggle any tool.
   **Expected:** The composer keeps its **Stop** button; it does not turn
   back into a Send button mid-turn.

## Cleanup

- Delete the test chat.

## Notes

- Automated twin for the routes and the switch states:
  `ChatPlusMenuComponentTest` and `ChatPickerComponentsTest`
  (mc-agent-chat-ui-rest). What they cannot check is the popover's placement
  and the flip-above, which is why step 1 is a human step.
- The model button beside Send opens the settings dialog, which is the agent,
  the model and the system prompt — and nothing else. Its own case is
  `chat/settings-dialog.md`; the step that matters to this one is that
  applying it does not touch the tools switched on here.
