# llm — the LLM gateway

Talking to a model by the name of its config, and managing those configs. Usable
without the rest of mindconnect: nothing here depends on `agents/` or `common/`.

| Module | Depends on | What it is |
|--------|------------|------------|
| `mc-llm-runtime-api` | the JDK, SLF4J | calling a model: request, streamed answer, tool definitions and calls on the wire; `LlmChat`, `LlmEmbedding`, `LlmTranscription` (`port.in`), `LlmCallListener` (`port.out`) |
| `mc-llm-management-api` | the runtime API, SLF4J | managing models: `LlmConfig`, prices and the stores they are kept in (`port.out`) |
| `mc-llm-provider-spi` | the management API | what a provider implements, called with the config itself: `LlmGateway`, `EmbeddingGateway`, `TranscriptionGateway` (`port.out`) |
| `mc-llm-jackson` | the management API, Jackson | the stored JSON form of configs and prices, as a Jackson module |
| `mc-llm-demo` | the provider SPI, the OpenAI Java SDK | how a library user calls a model; a provider of one's own on the OpenAI SDK |
| `mc-llm-arch-tests` | — | the area's ArchUnit rules |

A caller needs the runtime API only: it names a config, never a provider, model
or key. The gateway service and the providers still live in `agents/` and move
here step by step.
