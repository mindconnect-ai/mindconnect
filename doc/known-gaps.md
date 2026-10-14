# Known gaps

Code findings that were out of scope for the branch they were found on.
Recorded by agents and reviewers while working, closed by tidy branches.
One line plus at most three sentences per entry; module, what, where found,
size (`small` under an hour, `one wave` a tidy branch, `large` needs a spec),
and the rule it violates.

## Open

- [ ] **agents/core/mc-agent-runtime-core, mc-agent-memory-strategies, mc-llm-gateway-core** Jackson `ObjectMapper` is built and held as a static `MAPPER` in `MessageToLlmMessageMapper`, `ToolCalls`, `TurnMessage`, `LlmChatProvider`, `ToolCallWorker`, `SummarizingWindowStrategy`, `ToolPairSanitizer` and the port `LlmGateway`; `FeatureContext` exposes one. The core is meant to know no Jackson databind: a codec port in `-core` with the Jackson adapter in `mc-agent-runtime`, one injected mapper per runtime.
  Found 2026-10-14 by the ArchUnit baseline (43 entries) and mc-sandbox refactorings.md. Size: one wave. Rule: D1, I6.
- [ ] **agents/core/mc-agent-runtime-core** `Persistence.Postgres` takes a `javax.sql.DataSource`; a core type names a JDBC type.
  Found 2026-10-14 by the ArchUnit baseline. Size: small. Rule: D1.
- [ ] **agents (several)** 21 uses of `java.util.logging` in file adapters (`FileAgentDefinitionRepository`, `FileAgentSessionRepository`, `FileSkillRepository`, `FileConversationRepository`, `FileMessageRepository`, …); 10 more in workflow. SLF4J everywhere.
  Found 2026-10-14 by the ArchUnit baseline. Size: small. Rule: D8.
- [ ] **agents/core/mc-user-core** `StoredUserToolRoster` sits in an `adapter` package inside a `-core` module.
  Found 2026-10-14 by the ArchUnit baseline. Size: small. Rule: D1.
- [ ] **agents (libraries)** 23 Spring wiring classes in library modules: `RegistryAutoConfiguration` (mc-agent-registry), `MindconnectSecurityAutoConfiguration` (mc-agent-security), `VirtualEnvToolsAutoConfiguration` and `OnBehalfJwksController` (mc-agent-tools-virtual-env), `McAgentWorkflowStepsAutoConfiguration` (mc-agent-tools-workflow), …; conventions put wiring in `springstarter/`.
  Found 2026-10-14 by the ArchUnit baseline. Size: one wave. Rule: D1.
- [ ] **agents/server/mc-agent-chat-ui-rest** `ChatUiController` has 2417 lines, 83 methods, 20 fields: routing, page building, four dialogs, session-agent management and helpers in one class.
  Found 2026-10-14 by the baseline (classes over 300 lines). Size: large. Rule: D3, I1.
- [ ] **workflow/mc-workflow-admin-rest** `WorkflowAdminUiController` has 2100 lines; same shape as the chat controller.
  Found 2026-10-14 by the baseline. Size: large. Rule: D3, I1.
- [ ] **agents/client/mc-agent-cli** `CliRunner` has 1456 lines; one class per command would do.
  Found 2026-10-14 by the baseline. Size: large. Rule: D3, I1.
- [ ] **agents/core/mc-agent-runtime-core** `AgentChatService` has 770 lines and 17 constructor dependencies plus three concurrent maps of turn state; turn start, turn watching, approvals, attachments and title generation are separate responsibilities.
  Found 2026-10-14 by the baseline. Size: large. Rule: D3, I1.
- [ ] **all areas** 97 main classes over 300 lines and 105 constructors with more than 7 parameters are listed in `doc/agent/baseline.md`; each one is a tidy candidate in its own right.
  Found 2026-10-14 by the baseline. Size: large (many waves). Rule: D3.
- [ ] **workflow/mc-workflow** `ResourceUtil` is a utility bag; its functions belong to the types they operate on.
  Found 2026-10-14 by the PMD baseline (ClassNamingConventions). Size: small. Rule: D4.
- [ ] **doc** `agents/doc/manual-tests/README.md` is only a pointer to the private suite; the sentence belongs in `doc/README.md`, the folder can go.
  Found 2026-10-14 while moving knowledge. Size: small. Rule: –.
- [ ] **agents/core/mc-agent-memory-strategies** `SummarizingWindowStrategy` keeps two public methods without a caller (`compressUncompressedToolResults`, `recompressAllToolResults`); delete them.
  Found 2026-10-14 in mc-sandbox claude-todos.md, confirmed in code. Size: small. Rule: D8.
- [ ] **agents/client/mc-agent-cli** `RemoteAgentClient` (streaming frames, bearer header via `RemoteClientFactory`) has no test; a test against a stubbed HTTP server would cover the CLI's remote path.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: one wave. Rule: D9.
- [ ] **agents/core/mc-agent-runtime-core** `MemoryStrategy` takes an `AuthenticationInfo` in `buildWindow`, `compressEligibleToolResults`, `onAfterTurn` and `compress` next to the `AgentSession` that already carries it; drop the parameter.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: one wave. Rule: I4.
- [ ] **agents/core/mc-agent-runtime-core** `WorkingMemoryRepository` takes an `AuthenticationInfo` in all six methods that no adapter uses and whose own javadoc already describes as gone; drop it and fix the callers.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: small. Rule: I4.
- [ ] **agents/core/mc-agent-runtime-core** `AgentRegistryService.delete` removes only the `AgentDefinition` and leaves sessions, working memory and conversations with that id orphaned; cascade, block or soft-delete is undecided.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: large (needs a decision and a spec). Rule: –.
- [ ] **agents/core/mc-agent-runtime-core** `ReviewerAdvisor` replaces the answer before it is persisted; the original and the token count per version are lost and the admin UI cannot show what the reviewer changed. Needs a spec (a message type or metadata flag plus a filter in the memory strategy).
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: large. Rule: –.
- [ ] **agents/core/mc-agent-tool-spi** `Tool.parametersSchema()` and `ToolFactory.overridesSchema()` return `Map<String, Object>` although `mc-schema` is the typed model and `Acquisition` already uses it; changing the SPI touches every tool provider.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: large. Rule: I6.
- [ ] **agents/core/mc-agent-tool-spi** `Tool.execute` gets no `Cancellation`; a long tool run can only be stopped by thread interrupt. Interface change that touches every `ToolFactory`.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: large. Rule: –.
- [ ] **agents/core/mc-agent-runtime-core** `LlmChatProvider.toAnswer` drops the assistant text of a round that also carries tool calls; only the final answer is persisted as `CHAT`, intermediate text is gone after a reload. Carry the text in the `TOOL_CALL` message and render it in the assembler.
  Found 2026-10-14 in mc-sandbox claude-todos.md. Size: one wave. Rule: –.

## Closed

(none yet)
