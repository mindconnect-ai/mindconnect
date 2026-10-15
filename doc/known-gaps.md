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
- [ ] **agents/core/mc-user-core** `StoredUserToolRoster` sits in an `adapter` package inside a `-core` module.
  Found 2026-10-14 by the ArchUnit baseline. Size: small. Rule: D1.
- [ ] **agents (libraries)** A dozen Spring wiring classes in library modules: `RegistryAutoConfiguration` (mc-agent-registry), `MindconnectSecurityAutoConfiguration` (mc-agent-security), `VirtualEnvToolsAutoConfiguration` and `OnBehalfJwksController` (mc-agent-tools-virtual-env), `McAgentWorkflowStepsAutoConfiguration`, `NamespacedWorkflowStoresAutoConfiguration` and `WorkflowRegistryAutoConfiguration` (mc-agent-tools-workflow), `ExtensionAutoConfiguration` (mc-extension), `McpGatewayAutoConfiguration` (mc-mcp-gateway-local); conventions put wiring in `springstarter/`.
  Found 2026-10-14 by the ArchUnit baseline, recounted 2026-10-15. Size: one wave. Rule: D1.
- [ ] **agents/server/mc-agent-chat-ui-rest** `ChatUiController` has 2494 lines (2026-10-15), 83 methods, 20 fields: routing, page building, four dialogs, session-agent management and helpers in one class.
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
- [ ] **agents/server/mc-agent-admin-ui-app** The seeded `initial-data/llm-configs/gemini-default.json` defaults its model to `${GEMINI_MODEL:gemini-2.0-flash}`, which Google no longer serves (HTTP 404 on `generateContent`); a fresh installation without `GEMINI_MODEL` gets a config that cannot answer. Google's 404 names `gemini-3.8-flash` as the successor; the live tests use `gemini-3.5-flash`, because the alias `gemini-flash-latest` answered 503 under load.
  Found 2026-10-15 in chore/llm-gateway-refactor by the agent, running the new `GeminiGatewayLiveTest`. Size: small. Rule: –.

## Closed

- [x] **agents/core/mc-llm-gateway-core** `LlmPrices` is a plural for a static bag of rules over price periods — the very example `conventions.md` gives — and its `modelLabel` and `period` render strings for the admin UI from the domain; `LlmPriceList` with the two renderers next to `LlmPricingComponent` would say what it is. Eleven callers in the gateway, Postgres and admin-UI modules plus `LlmPriceTest` follow the rename. The same smell in the adapter module: `LlmParams`, `LlmHttpErrors`, `OpenAiModels`.
  Found 2026-10-15 in chore/llm-gateway-refactor by the maintainer, reading the tidy-inspect report. Size: one wave. Rule: Classes (named after its pattern, in the singular). Closed 2026-10-15 in chore/llm-gateway-refactor: `LlmPriceList` holds the rules, `LlmPrice` renders its own label and period; `LlmParams` is `LlmParameterMap`, `LlmHttpErrors` is `LlmHttpError`.
