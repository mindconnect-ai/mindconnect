# mc-agent-protocol-openai

Backend adapter: the `mc-agent-protocol` surface implemented against the **real
OpenAI Responses + Conversations API** — no MindConnect runtime involved.
Proves the protocol is backend-neutral; also usable standalone as a thin,
typed OpenAI client.

```java
var backend = new OpenAiResponsesBackend(System.getenv("OPENAI_API_KEY"))
        .register(PseudoAgent.of("assistant", "gpt-5-mini", "Be brief.")
                .withHostedTool("web_search")                       // runs inside OpenAI
                .withTools(List.of(weatherToolDefinition)));        // client-executed

Session s = backend.open("demo", "assistant");

// Function tools answered automatically by the backend-agnostic ToolLoop:
Response r = new ToolLoop(backend.responses(), List.of(weatherHandler))
        .run(ResponseRequest.text(s.id(), "Weather in Lisbon? Then search the news."));
```

## Mapping decisions

| Protocol | OpenAI |
|---|---|
| `PseudoAgent` (name → model, instructions, tools) | there is no agent concept — per-request `model`/`instructions`/`tools`, filled from the registry |
| `Session.open` | `POST /conversations` + local binding |
| `ResponseRequest` | `POST /responses {conversation, input, tools, store, background}` |
| function tools | ALL client-executed (no runtime): open declared call ⇒ `INCOMPLETE(WAITING_FOR_TOOL_OUTPUT)` |
| hosted tools (`web_search`, `file_search`, `code_interpreter`, …) | pass-through; execute inside OpenAI; appear as call items whose names never match a declared function tool ⇒ never "open" |
| `subscribe(afterSeq)` | `GET /responses/{id}?stream=true&starting_after=n` (background responses only) |
| `Conversations.items` | `GET /conversations/{id}/items?order=asc` |
| String ids | OpenAI's own ids (`resp_…`, `conv_…`) travel verbatim — no mapping layer |

## Sub-agents (agent-as-tool)

OpenAI has no native sub-agent concept — the adapter implements concept 8's
option (a), the same pattern OpenAI's Agents SDK uses client-side:

```java
backend.register(PseudoAgent.of("poet", "gpt-5-mini", "Write a two-line rhyme."));
backend.register(PseudoAgent.of("lead", "gpt-5-mini", "Delegate poem requests to 'poet'.")
        .withAgentTools("poet"));
```

`withAgentTools` injects a `run_agent` function tool; the adapter runs the
delegation loop itself: each call spawns a **real child response** (OpenAI
background run, polled), the parent item is rewritten to `AgentCall` with the
`childResponseId`, and the child's answer feeds back as the tool output. The
child is individually addressable — `get`, `subscribe`, and best-effort
`cancel` work while it runs. Depth capped at 3, rounds at 8;
`background` on a delegating parent is not supported yet.
Live example: `OpenAiSubAgentsExampleTest`.

## Findings for the protocol (from building this)

1. **`get(id)` collides across the three interfaces** (different return
   types) — one class cannot implement Sessions + AgentResponses +
   Conversations together; this backend composes them (`sessions()`,
   `responses()`, `conversations()`).
2. ~~UUID ids are too narrow~~ — **resolved**: protocol ids are opaque
   Strings now; the whole `IdMap` layer was deleted.
3. The **ToolLoop belongs to the protocol, not to a backend** — the
   `WAITING_FOR_TOOL_OUTPUT` mechanic drives tools identically everywhere.

## Hosted capabilities — the four live examples

`OpenAiHostedToolsExampleTest` (runs when `mc.env`/env provides `OPENAI_API_KEY`):

1. **web_search** — `withHostedTool("web_search")`; the search shows up as a
   `web_search_call` item, executed inside OpenAI.
2. **code execution** — `withHostedTool({type: code_interpreter, container: auto})`;
   Python runs hosted, the result lands in the answer.
3. **upload + ask** — `backend.files().upload(...)` (new `Files` surface), then a
   message with `ContentPart.Document(MediaSource.FileId(id))` asks against the PDF.
4. **upload + analyze with code** — a CSV handed to the code_interpreter container
   via `file_ids`; the model computes over the file.

Tests: mapper tests run offline; the live tests gate themselves on a real key.
