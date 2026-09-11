---
id: chat-landing-and-new-chats
area: chat
requires: [server-9091]
duration: ~4 min
last-verified: never
---

# Coming back to the chat lands where you were; chats started elsewhere are marked new

**Goal:** Returning to the chat through the main menu opens the conversation
this browser last had on screen, not the one started last; a conversation
started elsewhere appears in the chat list at once, marked **new** until it
is opened.

## Preconditions

- Admin UI running at http://localhost:9091 with authentication off, the
  app's default (otherwise: SKIPPED — the REST steps act as the dev user
  `mc_user`)
- No model is needed: nothing is sent to one.

## Setup

1. Note the id of the seeded `default-chat` agent:
   `AID=$(curl -s http://localhost:9091/api/agents | jq -r '.[] | select(.name=="default-chat") | .id')`

## Steps

1. Open http://localhost:9091/chat and click **New chat** (in the empty state,
   or at the top of the chat list). Note the id from the URL as `<mine>`.
   **Expected:** The URL is `/chat/sessions/<mine>`. In the chat list (the
   history button left of the conversation's title) its row is selected and
   shows an age such as `1m`, not `new`.
2. Keep the chat list open. From a terminal, start a chat elsewhere:
   ```bash
   curl -s -X POST http://localhost:9091/api/sessions -H 'content-type: application/json' \
        -d "{\"agentId\":\"$AID\",\"userId\":\"mc_user\"}" | jq -r .id
   ```
   Note the printed id as `<elsewhere>`.
   **Expected:** Without a reload, a row `New chat` appears at the top of the
   list with the badge **new** in the success colour. The page stays on
   `<mine>`.
3. Click **Agents** in the main menu, then **Chat**.
   **Expected:** The URL is `/chat/sessions/<mine>` — not `<elsewhere>`,
   although `<elsewhere>` was started later. Its row still says **new**.
4. Click the `<elsewhere>` row.
   **Expected:** The URL is `/chat/sessions/<elsewhere>`; its row no longer
   says **new**.
5. Click **Agents**, then **Chat**.
   **Expected:** The URL is `/chat/sessions/<elsewhere>` — the chat last on
   screen.
6. Delete `<elsewhere>` (its row's **…** → **Delete**, confirm).
   **Expected:** The most recently started remaining chat opens; no error.

## Cleanup

- Delete `<mine>` (its row's **…** → **Delete**).

## Notes

- The record lives in the HTTP session. A new browser session — another
  browser, a private window — opens the most recently started chat and marks
  nothing as new: only chats started after it began count.
- Tabs of the same browser share the HTTP session. A chat started in one tab
  is marked new live in the other, and plainly once that tab renders again,
  since the first tab has had it on screen.
- Automated twins: `ChatLandingTest`, `SeenChatsTest`,
  `ChatShellComponentBadgeTest` (mc-agent-chat-ui-rest).
