# llm — the LLM gateway

Talking to a model by the name of its config, and managing those configs. Usable
without the rest of mindconnect: nothing here depends on `agents/` or `common/`.

| Module | Depends on | What it is |
|--------|------------|------------|
| `mc-llm-runtime-api` | the JDK | calling a model: request, streamed answer, tool definitions and calls on the wire; `LlmChat`, `LlmEmbedding`, `LlmTranscription` (`port.in`), `LlmCallListener` (`port.out`) |
| `mc-llm-management-api` | the runtime API | managing models: `LlmConfig`, prices, the stores (`port.out`) and the provider SPI a gateway calls with a resolved config (`LlmGateway`, `EmbeddingGateway`, `TranscriptionGateway`) |
| `mc-llm-jackson` | the management API, Jackson | the stored JSON form of configs and prices, as a Jackson module |
| `mc-llm-arch-tests` | — | the area's ArchUnit rules |

A caller needs the runtime API only: it names a config, never a provider, model
or key. The gateway service and the providers still live in `agents/` and move
here step by step.
