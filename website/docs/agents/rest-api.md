---
title: REST API
sidebar_position: 8
---

# REST API

The agent server (`mc-agent-api-app`) exposes the same use cases the Admin UI
runs on, as a plain JSON API under `/api`. It is what the desktop chat client
talks to, and what your own front end would talk to.

```bash
mvn -f agents/server/mc-agent-api-app/pom.xml spring-boot:run
```

Interactive documentation ships with the server: **http://localhost:8080/swagger-ui.html**,
generated from the same annotations the endpoints carry. The pages below cover
the parts that need more explanation than a signature.

## Agents and sessions

| | |
|---|---|
| `POST /api/agents` | create an agent in a namespace |
| `GET /api/agents?namespace=` | list them |
| `GET /api/agents/{id}?namespace=` | one agent |
| `PUT /api/agents/{id}?namespace=` | partial update — absent fields keep their value |
| `PUT /api/agents/{id}/tools?namespace=` | replace the tool list |
| `POST /api/agents/{id}/copy?namespace=` | duplicate as `{name}-copy` |
| `DELETE /api/agents/{id}?namespace=` | delete |
| `POST /api/sessions` | start a session — body `{agentId, namespace, userId}` |
| `GET /api/sessions?agentId=&namespace=&userId=` | a user's sessions for one agent |
| `GET /api/sessions/{id}/history` | the persisted messages |
| `DELETE /api/sessions/{id}` | delete the session |

## Chat

A turn is streamed, not awaited. `POST` the user's message and read
Server-Sent Events until `done`:

```bash
curl -N -X POST http://localhost:8080/api/sessions/$SESSION/chat \
     -H 'Content-Type: text/plain' -d 'Which of our tools can read files?'
```

Each event is one JSON frame with a `type`:

| type | carries |
|---|---|
| `token` | `text` — a piece of the answer as it is generated |
| `asking_llm` | the loop is waiting on the model |
| `tool_call_started` | `toolName`, `arguments` |
| `tool_call_result` | `toolName`, `result`, `durationMs` |
| `tool_call_failed` | `toolName`, `error`, `durationMs` |
| `sub_agent_started` / `sub_agent_event` / `sub_agent_done` / `sub_agent_error` | `agentName`, `taskId`, `subSessionId`; `sub_agent_event` wraps the sub-agent's own frame in `inner` |
| `reviewing` / `reviewer_decision` / `response_revised` | the response-reviewer chain |
| `approval_requested` | a tool is waiting for a human — see below |
| `done` | the turn ended |
| `error` | the turn failed; `text` is the message. Only on this endpoint — it is the chat stream's way of reporting a broken turn, not a runtime event |

`DELETE /api/sessions/{id}/chat` cancels a running turn cooperatively: 204 when
a turn was signalled, 404 when none is running. The stream still ends with its
normal `done` once the loop reaches the next cancel check.

## Reconnecting: the session stream

A dropped connection used to mean a lost turn — the events had nowhere to go
back to. The session now carries the stream, and a client can attach to it at
any point, with or without a turn running:

```bash
curl -N "http://localhost:8080/api/sessions/$SESSION/stream?afterSeq=0"
```

The first frame is always the `attached` frame:

```json
{"type":"attached","firstBufferedSeq":1,"latestSeq":128,
 "liveTurnId":"6f1c…","liveRun":0}
```

It says what the buffer still holds and whether a turn is running
(`liveTurnId` is `null` when the session is idle). Then the buffered events
replay and the stream continues live, each frame carrying its cursor and turn
coordinates:

```json
{"seq":129,"turnId":"6f1c…","run":0,"event":{"type":"token","text":"…"}}
```

Three rules make a client robust:

**Remember `seq`.** It is what you hand back as `afterSeq` after a
disconnect. Everything after it is replayed.

**Check for a gap.** The buffer is finite. If `firstBufferedSeq` is greater
than your `afterSeq + 1`, events were evicted before you came back — reload
`GET /api/sessions/{id}/history` instead of trusting the tail.

**Filter by `turnId`** if you care about one turn only. The stream is the
session's, so it spans turns; it stays open across them until you disconnect
or the emitter times out. Reattaching with the last seen `seq` is the intended
loop, not an error path.

## Approvals

A tool marked "needs approval" suspends its task and the turn stops, waiting
for a human. The request arrives on the stream as `approval_requested` — but
only in the moment it is raised, so a client that connects later has to ask:

```bash
curl "http://localhost:8080/api/sessions/$SESSION/approvals"
```

That returns the still-open questions, oldest first — the ones raised by the
agent's own tools and the ones bubbled up from sub-agents alike. Each entry
carries the `callId` (the identity of the question), the `toolName`, and the
call JSON in `content`.

Answering is one call:

```bash
curl -X POST "http://localhost:8080/api/sessions/$SESSION/approvals/$CALL_ID?approved=true&scope=once"
```

`scope=once` allows exactly this call; `scope=session` makes it a standing
rule for that tool in this conversation, which also releases sibling calls of
the same tool that are already parked. 204 means the decision was delivered,
404 means the card was stale and its task is gone.

There is no new stream to open afterwards: the turn never ended. It is
suspended on the parked tool task and continues on its original stream the
moment the answer arrives — which is exactly why attaching first and then
loading the open approvals is the right order for a client.

## Working memory

| | |
|---|---|
| `GET /api/sessions/{id}/memory` | the prompt-assembly view: which messages are live, compressed or truncated, plus token accounting |
| `POST /api/sessions/{id}/compress` | summarise older turns to reclaim context |
| `DELETE /api/sessions/{id}/messages?fromSeq=&toSeq=` | drop a range of messages |

See [working memory](./memory.md) for what the strategies do.

## Beyond chat

The same server exposes LLM configurations, the file store, session
attachments and the vector stores under `/api` as well. Those follow plain
CRUD shapes and are best read in the Swagger UI.
