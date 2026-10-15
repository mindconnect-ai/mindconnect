# Known gaps

Code findings that were out of scope for the branch they were found on.
Recorded by agents and the maintainer while working (`/gap`), closed by `/tidy` runs.
One line plus at most three sentences per entry; module, what, where found,
size (`small` under an hour, `one wave` one `/tidy` run, `large` several runs or a redesign),
and the rule it violates.

## Open

- [ ] **agents/core/mc-agent-runtime-core, mc-agent-memory-strategies** Jackson `ObjectMapper` is built and held as a static `MAPPER` in `MessageToLlmMessageMapper`, `ToolCalls`, `TurnMessage`, `LlmChatProvider`, `ToolCallWorker`, `SummarizingWindowStrategy` and `ToolPairSanitizer`; `FeatureContext` exposes one. The core is meant to know no Jackson databind: a codec port in `-core` with the Jackson adapter in `mc-agent-runtime`, one injected mapper per runtime. The gateway modules are done: the file repositories take the host's mapper, the port parses nothing.
  Found 2026-10-14 by the ArchUnit baseline (41 entries on 2026-10-15) and mc-sandbox refactorings.md. Size: one wave. Rule: D1, reuse.
- [ ] **agents/core/mc-agent-runtime-core** `Persistence.Postgres` takes a `javax.sql.DataSource`; a core type names a JDBC type.
  Found 2026-10-14 by the ArchUnit baseline. Size: small. Rule: D1.
- [ ] **agents/core/mc-agent-runtime, mc-message, workflow/mc-workflow** `java.util.logging` in six file adapters (`FileAgentDefinitionRepository`, `FileAgentSessionRepository`, `FileSkillRepository`, `FileToolRepository`, `FileConversationRepository`, `FileMessageRepository`) and two step classes (`BaseStepInstance`, `BaseStepContainerInstance`). SLF4J everywhere.
  Found 2026-10-14 by the ArchUnit baseline, recounted 2026-10-15. Size: small. Rule: SLF4J.
- [ ] **agents/server/core/mc-user-core** `StoredUserToolRoster` sits in an `adapter` package inside a `-core` module.
  Found 2026-10-14 by the ArchUnit baseline. Size: small. Rule: D1.
- [ ] **agents (libraries)** Spring wiring classes in library modules: `MindconnectSecurityAutoConfiguration` (mc-agent-security), `VirtualEnvToolsAutoConfiguration` and `OnBehalfJwksController` (mc-agent-tools-virtual-env), `McAgentWorkflowStepsAutoConfiguration`, `NamespacedWorkflowStoresAutoConfiguration` and `WorkflowRegistryAutoConfiguration` (mc-agent-tools-workflow), `McpGatewayAutoConfiguration` (mc-mcp-gateway-local); conventions put wiring in `springstarter/`.
  Found 2026-10-14 by the ArchUnit baseline, recounted 2026-10-15. Size: one wave. Rule: D1.
- [ ] **agents/server/ui/mc-agent-chat-ui-rest** `ChatUiController` has 2494 lines (2026-10-15), 83 methods, 20 fields: routing, page building, four dialogs, session-agent management and helpers in one class.
  Found 2026-10-14 by the baseline (classes over 300 lines). Size: large. Rule: D3, one responsibility.
- [ ] **workflow/mc-workflow-admin-rest** `WorkflowAdminUiController` has 2100 lines; same shape as the chat controller.
  Found 2026-10-14 by the baseline. Size: large. Rule: D3, one responsibility.
- [ ] **agents/client/mc-agent-cli** `CliRunner` has 1456 lines; one class per command would do.
  Found 2026-10-14 by the baseline. Size: large. Rule: D3, one responsibility.
- [ ] **agents/core/mc-agent-runtime-core** `AgentChatService` has 770 lines and 11 constructor dependencies plus three concurrent maps of turn state; turn start, turn watching, approvals, attachments and title generation are separate responsibilities.
  Found 2026-10-14 by the baseline. Size: large. Rule: D3, one responsibility.
- [ ] **all areas** 96 main classes over 300 lines (measured 2026-10-15) and 105 frozen entries for constructors with more than 7 parameters (`agents/mc-agents-arch-tests/archunit_store/`); each one is a tidy candidate in its own right.
  Found 2026-10-14 by the baseline. Size: large (many waves). Rule: D3.
- [ ] **workflow/mc-workflow** `ResourceUtil` is a utility bag; its functions belong to the types they operate on.
  Found 2026-10-14 by the naming check. Size: small. Rule: D4.
- [ ] **doc** `agents/doc/manual-tests/README.md` is only a pointer to the private suite; the sentence belongs in `doc/README.md`, the folder can go.
  Found 2026-10-14 while moving knowledge. Size: small. Rule: –.
- [ ] **agents/core/mc-agent-memory-strategies** `SummarizingWindowStrategy` keeps two public methods without a caller (`compressUncompressedToolResults`, `recompressAllToolResults`); delete them.
  Found 2026-10-14 in mc-sandbox claude-todos.md, confirmed in code on 2026-10-15. Size: small. Rule: No defensive redundancy.
- [ ] **agents/client/mc-agent-cli** `RemoteAgentClient` (streaming frames, bearer header via `RemoteClientFactory`) has no test; a test against a stubbed HTTP server would cover the CLI's remote path.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: one wave. Rule: D9.
- [ ] **agents/core/mc-agent-runtime-core** `MemoryStrategy` takes an `AuthenticationInfo` in `buildWindow`, `compressEligibleToolResults`, `onAfterTurn` and `compress` next to the `AgentSession` that already carries it; drop the parameter.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: one wave. Rule: no defensive redundancy.
- [ ] **agents/core/mc-agent-runtime-core** `WorkingMemoryRepository` takes an `AuthenticationInfo` in all six methods that no adapter uses and whose own javadoc already describes as gone; drop it and fix the callers.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: small. Rule: no defensive redundancy.
- [ ] **agents/core/mc-agent-runtime-core** `AgentRegistryService.delete` removes only the `AgentDefinition` and leaves sessions, working memory and conversations with that id orphaned; cascade, block or soft-delete is undecided.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: large (needs a decision and a spec). Rule: –.
- [ ] **agents/core/mc-agent-runtime-core** `ReviewerAdvisor` replaces the answer before it is persisted; the original and the token count per version are lost and the admin UI cannot show what the reviewer changed. Needs a spec (a message type or metadata flag plus a filter in the memory strategy).
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: large. Rule: –.
- [ ] **agents/core/mc-agent-tool-spi** `Tool.parametersSchema()` and `ToolFactory.overridesSchema()` return `Map<String, Object>` although `mc-schema` is the typed model and `Acquisition` already uses it; changing the SPI touches every tool provider.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: large. Rule: reuse.
- [ ] **agents/core/mc-agent-tool-spi** `Tool.execute` gets no `Cancellation`; a long tool run can only be stopped by thread interrupt. Interface change that touches every `ToolFactory`.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: large. Rule: –.
- [ ] **agents/core/mc-agent-runtime-core** `LlmChatProvider.toAnswer` drops the assistant text of a round that also carries tool calls; only the final answer is persisted as `CHAT`, intermediate text is gone after a reload. Carry the text in the `TOOL_CALL` message and render it in the assembler.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: one wave. Rule: –.
- [ ] **agents/server/app/mc-agent-admin-ui-app** The seeded `initial-data/llm-configs/gemini-default.json` defaults its model to `${GEMINI_MODEL:gemini-2.0-flash}`, which Google no longer serves (HTTP 404 on `generateContent`); a fresh installation without `GEMINI_MODEL` gets a config that cannot answer. Google's 404 names `gemini-3.8-flash` as the successor; the live tests use `gemini-3.5-flash`, because the alias `gemini-flash-latest` answered 503 under load.
  Found 2026-10-15 in chore/llm-gateway-refactor by the agent, running the new `GeminiGatewayLiveTest`. Size: small. Rule: –.
- [ ] **agents/tools/core/mc-agent-tools-workflow** `RunWorkflowToolTest.anInputThatIsMiniScript_cannotReachAClass` fails on `next`: it expects a `mini:` input to be evaluated and refused with "Method not found", but since inputs are stored as data (58290de8) the text comes back verbatim; either the test or the guard it documents is stale.
  Found 2026-10-15 in chore/refactor-core by the agent, on the branch and on its base commit a920c763 with freshly built dependencies. Size: small. Rule: Logging, style, tests.
- [ ] **agents/server/core/mc-credentials** The module is named after its payload, not its thing: the aggregate is `Connection` (a user's link to an external service, with `ConnectionRepository`, `ConnectionService`, `OAuthConnections`), the credentials are one facet of it; `mc-connection` and `mc-connection-pg` with package `ai.mindconnect.connection` would say what it is. Four users (`mc-agent-starter-file`, `mc-agent-admin-ui-rest`, `mc-agent-security`, the PG module), the docs and the ArchUnit store entries naming the package follow the rename.
  Found 2026-10-15 in chore/refactor-core by the maintainer. Size: one wave. Rule: Classes (named after what it is for).
- [ ] **agents/vectorstore/mc-vector-store, mc-vector-store-pgvector, tools/vector/mc-vector-store-tools** The vector store is not hexagonal: `mc-vector-store` is one package, `vectorstore.embedding`, holding the ports (`EmbeddingIndex`, `EntryStore`), the domain (`EmbeddingChunk`, `EmbeddingQuery`, `MetadataFilter`, `EntityRef`) and the file-backed adapter (`FileEntryStore`, `MemoryEmbeddingIndex`) with Jackson, found through ServiceLoader instead of wired; and the tools module depends on `mc-vector-store-pgvector`, `mc-jdbc` and `org.postgresql` directly (`PostgresVectorStores`, `DefaultVectorStores`, `LegacyVectorStores`), so a tool knows a concrete backend. The shape of the other pairs: `mc-vector-store-core` with domain and ports, `mc-vector-store` with the file adapter, the Postgres one under `adapter/postgres/mc-vector-store-pg`, the tools against the ports only.
  Found 2026-10-15 in chore/refactor-core by the maintainer. Size: large. Rule: D1, D2 (Architecture: hexagonal, per module pair).
- [ ] **agents/core/mc-agent-runtime-core** `LlmCallTrace` copies the eleven fields of the gateway's `LlmCallEvent` one by one and adds a `TraceId` and the runtime's `TraceContext`; `LlmCallTraceRepository` stores and lists it, the admin UI reads it per session. The only runtime-specific part is the context, so either the record holds the event (`LlmCallTrace(id, context, LlmCallEvent call)`) and stays in the runtime, or the trace store is the gateway's own concern in `mc-llm-gateway-core` with the context as opaque metadata; a decision for the maintainer, the copy goes either way.
  Found 2026-10-15 in chore/refactor-core by the maintainer. Size: one wave. Rule: Look left and right (reuse), Architecture.
- [ ] **agents/core/mc-agent-domain, mc-agent-runtime, mc-message-repository, mc-llm-gateway, mc-file-store** `Namespace` is a core type: `Scope` carries it, `NamespaceRouted` routes by it, and nineteen adapter constructors in the core modules take a `Namespace` to bind one partition; yet a namespace is an installation concern of the server (`mc-namespace-core` under `server/core`). The core should know only a partition provider (`ScopeProvider` or `PartitionProvider`, name open) that an adapter asks for the partition it writes to, and the server implements it as the namespace; `Scope.namespace()` and the constructor parameters go. How to cut it, and what the default partition of a library embedding is, is still to be discussed before any move.
  Found 2026-10-15 in chore/refactor-core by the maintainer. Size: large. Rule: D1, D2 (Architecture: hexagonal, the core knows no installation).

## Closed

- [x] **agents/core/mc-llm-gateway-core** `LlmPrices` is a plural for a static bag of rules over price periods — the very example `conventions.md` gives — and its `modelLabel` and `period` render strings for the admin UI from the domain; `LlmPriceList` with the two renderers next to `LlmPricingComponent` would say what it is. Eleven callers in the gateway, Postgres and admin-UI modules plus `LlmPriceTest` follow the rename. The same smell in the adapter module: `LlmParams`, `LlmHttpErrors`, `OpenAiModels`.
  Found 2026-10-15 in chore/llm-gateway-refactor by the maintainer, reading the tidy-inspect report. Size: one wave. Rule: Classes (named after its pattern, in the singular). Closed 2026-10-15 in chore/llm-gateway-refactor: `LlmPriceList` holds the rules, `LlmPrice` renders its own label and period; `LlmParams` is `LlmParameterMap`, `LlmHttpErrors` is `LlmHttpError`.
