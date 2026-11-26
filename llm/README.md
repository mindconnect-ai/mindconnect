# llm — the LLM gateway

Talking to a model by the name of its config, and managing those configs. Usable
without the rest of mindconnect: nothing here depends on `agents/`. The APIs, the
provider SPI and the providers need nothing of `common/` either; the gateway and
the stores take the light shared libraries (`mc-common`, and for the
stores `mc-file-repo` or `mc-jdbc`), as the conversation area does.

| Module | Depends on | What it is |
|--------|------------|------------|
| `mc-llm-runtime-api` | the JDK, SLF4J | calling a model: request, streamed answer, tool definitions and calls on the wire; `LlmChat`, `LlmEmbedding`, `LlmTranscription` (`port.in`), `LlmCallListener` (`port.out`) |
| `mc-llm-management-api` | the runtime API, SLF4J | managing models: `LlmConfig`, prices, the stores they are kept in and how a stored config's secrets are resolved (`port.out`: `LlmConfigRepository`, `LlmPriceRepository`, `LlmRepositoryFactory`, `SecretResolver`) |
| `mc-llm-provider-spi` | the management API | what a provider implements, called with the config itself: `LlmGateway`, `EmbeddingGateway`, `TranscriptionGateway` (`port.out`) |
| `mc-llm-providers` | the provider SPI, okhttp, Jackson, SLF4J | our providers: OpenAI (Chat Completions and Responses), Azure OpenAI, the OpenAI-compatible servers, Claude, Gemini, embeddings, transcription and the model catalogs; each receives a resolved config; `StandardProviders` hands out one per provider family |
| `mc-llm-gateway` | the provider SPI, `mc-common`, SLF4J | the gateway itself: routes a call by config name through aliases and fallbacks, resolves the config's secrets and calls the provider, with retry and rate limiting; implements the runtime API's ports and has none of its own. Also the stores in memory and `LlmConfigResolver`, the `SecretResolver` from environment variables and the encryption key. `LlmRuntime.builder()` assembles all of it for a library user, with the stores in memory and secrets from the process environment as defaults |
| `mc-llm-store-file` | the management API, `mc-common`, `mc-file-repo` | configs and prices as JSON files, one directory per namespace |
| `mc-llm-store-pg` | the management API, `mc-common`, `mc-jdbc` | configs and prices in Postgres, one JSONB document per row |
| `mc-llm-jackson` | the management API, Jackson | the stored JSON form of configs and prices, as a Jackson module |
| `mc-llm-demo` | the provider SPI, the OpenAI Java SDK | how a library user calls a model; a provider of one's own on the OpenAI SDK |
| `mc-llm-arch-tests` | — | the area's ArchUnit rules |

A caller needs the runtime API only: it names a config, never a provider, model
or key. A store asks the management API's `LlmScopeSupplier` for the namespace of each
call; the agents hand theirs in as `() -> scope.namespace().value()`, a library
user takes `LlmScopeSupplier.local()`. The file and
Postgres stores serialise with the host's mapper, which needs `mc-llm-jackson`'s module.
