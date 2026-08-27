# mc-agent-protocol

The agent protocol vocabulary — **pure types, zero runtime dependencies (JDK only)**.
Every transport (in-memory, HTTP, SSE, WebSocket) and the runtime core speak these
exact objects; serialization lives in transport modules, never here.
Concepts: [07](../../doc/architecture/concepts/07-responses-protocol.md),
[08](../../doc/architecture/concepts/08-sub-agents.md),
[09](../../doc/architecture/concepts/09-conversations.md).

## Surface

```
ai.mindconnect.agent.protocol
│  Response            one execution: lifecycle + output items (+ parent/spawnedBy for sub-agents)
│  ResponseStatus      QUEUED → IN_PROGRESS → COMPLETED | INCOMPLETE | FAILED | CANCELLED
│  IncompleteReason    WAITING_FOR_APPROVAL | MAX_ROUNDS | CONTEXT_OVERFLOW
│  Conversation        durable append-only item log (the state behind responses)
│  Session             the binding: conversation ↔ named agent (definition stays admin-domain)
│  Usage, ResponseError
│
├─ item                the atom — turn, storage, stream and API format in one
│    Item              sealed: Message · Reasoning · FunctionCall · FunctionCallDispatched
│                              FunctionCallOutput · AgentCall · ApprovalRequest
│                              ApprovalResponse · Deleted
│    ItemRef           durable identity (opaque String id + per-conversation seq)
│    ItemEnvelope      ItemRef + Item
│    Role
    ContentPart        sealed: Text · Image(detail) · Audio · Document
    MediaSource        sealed: Url · FileId · Inline(base64)   — one choice, shared by all media parts
│
├─ event               projections of the transcript, per-response seq
│    ResponseEvent     sealed: Created · InProgress · Completed · Incomplete · Failed · Cancelled
│                              OutputItemAdded · OutputItemDone
│                              OutputTextDelta · ArgumentsDelta · ReasoningDelta   (live only)
│
└─ api                 the two port interfaces every transport adapts
     Sessions          open · openOn · get                      (bind a conversation to an agent)
     Files             upload · get                             (files that content and tools reference)
     AgentResponses    create · get · cancel · subscribe        (commands — the only inbound surface)
     Conversations     create · get · append · items            (state)
     ResponseRequest, SubscribeRequest, Subscription, ToolDefinition (client-side tools)
```

## Naming

A `ConversationItem` is **not** a message — a message is one KIND of item,
next to function calls, their outputs, reasoning blocks and approval
requests. That generalization is exactly what Chat Completions lacked (tool
calls crammed into assistant messages, results as `role: "tool"`).

The name says where they live: the **conversation** owns the durable log, a
response only appends to it — a user's message exists before any response
runs. Identity and position travel beside the payload in one
`ConversationItemRecord(id, seq, item)`: two equal items are equal values, two
appends are two entries.

## The five rules encoded here

1. **Items are append-only.** "Mutation" is a later item referring back
   (output closes call, tombstone hides, approval answers).
2. **Openness is derived.** Call without output = open → re-executed on resume;
   `FunctionCallDispatched` guards non-idempotent tools against double execution.
3. **Streams are projections.** Replay from `afterSeq=0` re-renders stored items;
   only deltas are live-only. The stream is never the truth.
4. **Streams are flat, the protocol is recursive.** A sub-agent is a child
   Response (`AgentCall.childResponseId`); aggregation is a subscription option.
5. **Commands are calls, not events.** Four methods on `AgentResponses`; every
   transport is a thin adapter onto them.
6. **Ids are opaque Strings.** Backends carry their native ids verbatim
   (`resp_…` at OpenAI, UUIDs in our runtime) — the protocol never assumes a
   format.
