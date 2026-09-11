---
id: chat-session-isolation
area: chat
requires: [server-9098]
duration: ~3 min
last-verified: 2026-09-11 (curl, dev-user mode, branch fix/session-ownership)
---

# Session isolation: another user's session id opens nothing

**Goal:** Every chat endpoint that is addressed by a session id — or by the
session's stream channel — answers 404 when the session belongs to another
user, and works for the owner. No LLM is needed: only the answer codes count.

## Preconditions

- Admin UI started with auth off and a fixed dev user `bob`, e.g. via
  `start.sh -Dspring-boot.run.arguments="--server.port=9098 --mindconnect.auth.enabled=false --mindconnect.auth.dev-user=bob"`
  (every chat request runs as `bob`). Otherwise: SKIPPED.
- `curl`, `python3` and a file to upload (`printf hello > up.txt`).

## Setup

Create one session for `alice` and one for `bob` through the REST API, which
takes the user from the request body (it is a backend-client API):

```bash
B=http://localhost:9098
AG=$(curl -s $B/api/agents | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
A=$(curl -s -X POST $B/api/sessions -H 'Content-Type: application/json' -d "{\"agentId\":\"$AG\",\"userId\":\"alice\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
BOB=$(curl -s -X POST $B/api/sessions -H 'Content-Type: application/json' -d "{\"agentId\":\"$AG\",\"userId\":\"bob\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
c() { printf '%-16s %s\n' "$1" "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "${@:2}")"; }
```

## Steps

1. **Alice's session, called as bob.**
   ```bash
   c "send"          -X POST $B/chat/api/sessions/$A/chat/stream -H 'Content-Type: application/json' -d '{"message":"hi"}'
   c "regenerate"    -X POST $B/chat/api/sessions/$A/messages/1/regenerate
   c "cancel turn"   -X DELETE $B/chat/api/sessions/$A/chat
   c "upload"        -X POST $B/chat/api/sessions/$A/chat-files -F chat-attach=@up.txt
   c "remove file"   -X DELETE "$B/chat/api/sessions/$A/chat-files?file=up.txt"
   c "file content"  $B/chat/api/sessions/$A/chat-files/file-x/content
   c "stream get"    $B/chat/api/streams/msg-list-$A
   c "stream sse"    $B/chat/api/streams/msg-list-$A/sse
   c "stream cancel" -X DELETE $B/chat/api/streams/msg-list-$A
   ```
   **Expected:** **404** on every line.
2. **Bob's own session.**
   ```bash
   c "upload"      -X POST $B/chat/api/sessions/$BOB/chat-files -F chat-attach=@up.txt
   curl -s -m 2 -D - -o /dev/null $B/chat/api/streams/msg-list-$BOB/sse | head -1
   c "stream get"  $B/chat/api/streams/msg-list-$BOB
   c "send"        -X POST $B/chat/api/sessions/$BOB/chat/stream -H 'Content-Type: application/json' -d '{"message":"hi"}'
   ```
   **Expected:** upload **200**; the SSE request answers `HTTP/1.1 200`
   (the connection stays open until `-m 2` cuts it); `stream get` **404**
   while no turn runs; send **200**.
3. **The stream list shows only bob's turns.** Right after step 2's send:
   ```bash
   curl -s $B/chat/api/streams
   ```
   **Expected:** no entry with `channelId` = `msg-list-$A`, even while one of
   alice's turns runs; bob's turn (`msg-list-$BOB`) appears while it streams.

## Not covered here

The rule that a chat's upload store (`session-<id>`) is reachable by the
`vector_*` tools only for the chat's user is a tool-level rule and is pinned by
`VectorToolsTest.aChatsUploadStoreIsReachableOnlyForItsUser`; that a workflow
run as an agent tool passes the caller's scope to its tool steps by
`WorkflowToolProviderTest.toolStepsRunOnBehalfOfTheCaller`.
