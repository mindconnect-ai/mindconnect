# mc-agent-protocol-mc-runtime

Backend adapter: the `mc-agent-protocol` surface implemented against the
**Mindconnect agent runtime** (`mc-agent-runtime`). Here the runtime is the
server side OpenAI cannot be: registered tools, sub-agents and memory run
inside the turn.

```java
var backend = new AgentRuntimeBackend(runtime.chatService(), runtime.sessionService(),
        runtime.agentDefinitions(), runtime.conversationManager(), "user-1")
        .withFiles(runtime.fileStore(), runtime::attachStored);   // optional: Files surface

Session s = backend.open("default", "travel-assistant");
Response r = backend.create(ResponseRequest.text(s.id(), "Find me a hotel in Lisbon"));
r.output();        // FunctionCall/Output pairs, AgentCall pairs, final Message
r.outputText();    // the answer
```

## Files: upload + ask (parity with the OpenAI backend)

The same caller code as the OpenAI example works here — upload via
`files().upload(...)`, then reference the id from a message's
`ContentPart.Document(MediaSource.FileId(id))`. The backend detail differs by
design: OpenAI stuffs the document into context; the runtime ingests it into
the session's vector store (chunk + embed via the attach pipeline) and the
agent retrieves with `vector_search` — visible as a normal tool-call item
pair. Wiring is two small ports (`FileStore` + `FileAttacher`), supplied by
the builder: `withFiles(runtime.fileStore(), runtime::attachStored)`.
Live example: `RuntimeFileQaExampleTest`.

## Web search & code execution (parity examples)

`RuntimeToolsExampleTest` mirrors the OpenAI backend's hosted-tools examples —
what OpenAI hosts, the runtime runs as REGISTERED tools of the agent
definition; the protocol shows both as the same item pairs:

| Scenario | OpenAI (hosted) | Runtime (registered) | Gate |
|---|---|---|---|
| web search | `web_search` | `mc-agent-tools-web`: `web_search` + `web_read` (Tavily) | `TAVILY_API_KEY` |
| code execution | `code_interpreter` | `mc-agent-tools-code`: `code_execute` (podman/docker session container) | container runtime |
| upload + ask | `input_file` in context | vector ingest + `vector_search` | — |
| upload + compute | container `file_ids` | retrieval CHAINED with `code_execute` (`tools=[code_execute, vector_search, …]`) | container runtime |

The examples run chat and embeddings on a **local LM Studio**
(`openai/gpt-oss-120b`, `text-embedding-nomic-embed-text-v1.5`) — no keys, no
cost — and additionally gate themselves on what they need (a Tavily key for
web search, a running container binary for code execution).

## How it maps

| Protocol | Runtime |
|---|---|
| `Sessions.open` | `AgentDefinitionRepository.findByName` + `AgentSessionService.openChat` |
| `AgentResponses.create` | `AgentChatService.submitChat` → `ChatTurnHandle`; blocking joins, `background` returns the running snapshot |
| output items + events | **`ResponseAssembler`**: translates the runtime's `StreamEvent`s — tokens accumulate into the pending assistant message, tool calls become `FunctionCall`/`FunctionCallOutput` pairs (ids synthesized, FIFO-paired), sub-agents become `AgentCall` pairs, `ResponseRevised` replaces the draft text |
| `subscribe(afterSeq)` | assembler buffer: replay + live, strictly increasing `seq` |
| `cancel` | `ChatTurnHandle.cancel()` (cooperative, cascades to sub-agents) |
| `Conversations.items` | `ConversationManager.loadHistory`, lossily mapped from the legacy `Message` format |

## v1 limitations (each one is a concept-doc pointer)

- `clientTools` rejected — the runtime has no per-request tools yet (K7).
- `Document` sources: `FileId` and `Inline` work; `Url` not yet.
- No `INCOMPLETE`/approvals — needs the item-native turn state (K5/K7).
- Sub-agent turns are not addressable child responses; `AgentCall.childResponseId`
  carries the sub-session id, child streams are folded into the parent (K8).
- Responses live in memory; the durable truth stays the conversation (K9).
- `Usage` is ZERO — token accounting comes with `LlmCallTrace` wiring.

The `ResponseAssembler` is a pure state machine — `ResponseAssemblerTest`
covers the full translation offline, no runtime or LLM needed.
