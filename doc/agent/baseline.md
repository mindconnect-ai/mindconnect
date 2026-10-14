# Baseline

Measured 2026-10-14 at 8772348c by scripts/baseline.sh. Rewritten by the ratchet; the numbers may only fall.

## Summary

| Measure | Value |
| --- | --- |
| Main classes over 300 lines | 97 |
| PMD class/rule pairs in the baseline | 451 |
| PMD violations outside the baseline (last build) | 0 |
| ArchUnit frozen violations | 476 |
| Files over 30 % comment lines (of files over 60 lines) | 165 |
| @Test methods | 2898 |
| Known gaps open | 0 |

## ArchUnit, frozen per area

| Area | Rule | Violations |
| --- | --- | --- |
| agents | Conventions: SLF4J everywhere, never java.util.logging | 21 |
| agents | Conventions: one constructor per class | 224 |
| agents | D1: -core modules and engines know no framework | 43 |
| agents | D1: @Component, @Configuration, @RestController ... only in starters, apps, rest/ui modules and demos | 23 |
| agents | D1: a -core module holds domain and ports, adapters live in the sibling | 1 |
| agents | D2: an adapter does not import another module's adapter | 0 |
| agents | D2: domain <- port <- service <- adapter | 1 |
| agents | D3: a constructor takes at most 7 parameters | 105 |
| common | Conventions: SLF4J everywhere, never java.util.logging | 0 |
| common | Conventions: one constructor per class | 10 |
| common | D1: -core modules and engines know no framework | 0 |
| common | D1: @Component, @Configuration, @RestController ... only in starters, apps, rest/ui modules and demos | 1 |
| common | D1: a -core module holds domain and ports, adapters live in the sibling | 0 |
| common | D2: an adapter does not import another module's adapter | 0 |
| common | D2: domain <- port <- service <- adapter | 0 |
| common | D3: a constructor takes at most 7 parameters | 3 |
| taskqueue | Conventions: SLF4J everywhere, never java.util.logging | 0 |
| taskqueue | Conventions: one constructor per class | 8 |
| taskqueue | D1: -core modules and engines know no framework | 0 |
| taskqueue | D1: @Component, @Configuration, @RestController ... only in starters, apps, rest/ui modules and demos | 0 |
| taskqueue | D1: a -core module holds domain and ports, adapters live in the sibling | 0 |
| taskqueue | D2: an adapter does not import another module's adapter | 0 |
| taskqueue | D2: domain <- port <- service <- adapter | 0 |
| taskqueue | D3: a constructor takes at most 7 parameters | 4 |
| workflow | Conventions: SLF4J everywhere, never java.util.logging | 10 |
| workflow | Conventions: one constructor per class | 18 |
| workflow | D1: -core modules and engines know no framework | 0 |
| workflow | D1: @Component, @Configuration, @RestController ... only in starters, apps, rest/ui modules and demos | 1 |
| workflow | D1: a -core module holds domain and ports, adapters live in the sibling | 0 |
| workflow | D2: an adapter does not import another module's adapter | 0 |
| workflow | D2: domain <- port <- service <- adapter | 0 |
| workflow | D3: a constructor takes at most 7 parameters | 3 |

## PMD baseline, per rule (classes still allowed to violate it)

| Rule | Classes |
| --- | --- |
| CyclomaticComplexity | 115 |
| CommentSize | 61 |
| UnnecessaryImport | 61 |
| UnnecessaryFullyQualifiedName | 46 |
| ExcessiveParameterList | 40 |
| PreserveStackTrace | 38 |
| NcssCount | 28 |
| UnusedPrivateField | 11 |
| MethodNamingConventions | 11 |
| ClassNamingConventions | 9 |
| ReturnEmptyCollectionRatherThanNull | 8 |
| UnusedPrivateMethod | 8 |
| UnusedLocalVariable | 7 |
| TooManyFields | 4 |
| EmptyCatchBlock | 3 |
| AvoidCatchingThrowable | 1 |

## PMD baseline, packages with most entries

| Package | Class/rule pairs |
| --- | --- |
| ai.mindconnect.agent.tools.document | 20 |
| ai.mindconnect.agent.tool | 16 |
| ai.mindconnect.chatui.ui.component | 16 |
| ai.mindconnect.llm.adapter.openai | 16 |
| ai.mindconnect.adminui.ui.controller | 15 |
| ai.mindconnect.agent.runtime.service | 14 |
| ai.mindconnect.agent.tools.builtin | 14 |
| ai.mindconnect.agent.runtime.service.task | 13 |
| ai.mindconnect.workflow.admin.ui | 11 |
| ai.mindconnect.office.tools | 10 |
| ai.mindconnect.chatui.ui.controller | 9 |
| ai.mindconnect.mail.imap | 9 |
| ai.mindconnect.adminui.ui.component | 8 |
| ai.mindconnect.calendar.caldav | 8 |
| ai.mindconnect.script.mini | 8 |

## Classes over 300 lines

| Lines | File |
| --- | --- |
| 2450 | agents/server/mc-agent-chat-ui-rest/src/main/java/ai/mindconnect/chatui/ui/controller/ChatUiController.java |
| 2101 | workflow/mc-workflow-admin-rest/src/main/java/ai/mindconnect/workflow/admin/ui/WorkflowAdminUiController.java |
| 1456 | agents/client/mc-agent-cli/src/main/java/ai/mindconnect/cli/CliRunner.java |
| 1152 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/controller/VectorStoreUiController.java |
| 1008 | workflow/mc-workflow-dsl-puml/src/main/java/ai/mindconnect/workflow/dsl/puml/PumlWorkflowParser.java |
| 874 | common/mc-script-mini/src/main/java/ai/mindconnect/script/mini/MiniScriptEngine.java |
| 812 | agents/office/mc-mail-imap/src/main/java/ai/mindconnect/mail/imap/Mailbox.java |
| 770 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/service/AgentChatService.java |
| 758 | workflow/mc-workflow-swing-ui/src/main/java/ai/mindconnect/workflow/swing/ui/WorkflowDesignerFrame.java |
| 721 | taskqueue/mc-task-queue-jdbc/src/main/java/ai/mindconnect/taskqueue/jdbc/JdbcTaskStore.java |
| 713 | workflow/mc-workflow-ui-diagram/src/main/java/ai/mindconnect/workflow/ui/diagram/WorkflowDiagramBuilder.java |
| 689 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/component/LlmConfigFormComponent.java |
| 679 | workflow/mc-workflow-admin-rest/src/main/java/ai/mindconnect/workflow/admin/run/WorkflowRunService.java |
| 673 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/controller/LlmConfigUiController.java |
| 663 | agents/server/mc-agent-api-rest/src/main/java/ai/mindconnect/agentrest/controller/AgentApiController.java |
| 636 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/service/AgentSessionService.java |
| 628 | agents/core/mc-agent-tools-document/src/main/java/ai/mindconnect/agent/tools/document/DocumentReader.java |
| 622 | agents/office/mc-calendar-caldav/src/main/java/ai/mindconnect/calendar/caldav/ICalendar.java |
| 618 | agents/core/mc-agent-tools/src/main/java/ai/mindconnect/agent/tools/builtin/BashTool.java |
| 617 | agents/builder/mc-agent-runtime-builder/src/main/java/ai/mindconnect/agent/builder/AgentRuntimeBuilder.java |
| 605 | agents/core/mc-llm-gateway/src/main/java/ai/mindconnect/llm/adapter/openai/AbstractOpenAiGateway.java |
| 598 | agents/office/mc-agent-tools-mail/src/main/java/ai/mindconnect/office/tools/MailTools.java |
| 590 | taskqueue/mc-task-queue/src/main/java/ai/mindconnect/taskqueue/local/LocalTaskQueue.java |
| 569 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/page/ProfilePage.java |
| 564 | agents/vectorstore/mc-vector-store-pgvector/src/main/java/ai/mindconnect/vectorstore/pgvector/PgEmbeddingIndex.java |
| 562 | agents/core/mc-agent-registry-core/src/main/java/ai/mindconnect/agent/registry/service/RegistryService.java |
| 540 | agents/core/mc-llm-gateway/src/main/java/ai/mindconnect/llm/adapter/anthropic/ClaudeGateway.java |
| 537 | agents/core/mc-namespace-core/src/main/java/ai/mindconnect/namespace/service/NamespaceService.java |
| 536 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/service/SessionDirectories.java |
| 533 | agents/vectorstore/mc-vector-store-tools/src/main/java/ai/mindconnect/vectorstore/tools/DefaultVectorStores.java |
| 532 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/controller/AgentUiController.java |
| 527 | agents/core/mc-llm-gateway/src/main/java/ai/mindconnect/llm/adapter/openai/OpenAiResponsesGateway.java |
| 523 | agents/core/mc-agent-protocol-mc-runtime/src/main/java/ai/mindconnect/agent/protocol/runtime/AgentRuntimeBackend.java |
| 508 | agents/server/mc-agent-chat-ui-rest/src/main/java/ai/mindconnect/chatui/ui/component/MessageListComponent.java |
| 507 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/service/task/AgentTurnWorker.java |
| 506 | agents/core/mc-agent-memory-strategies/src/main/java/ai/mindconnect/agent/memory/strategy/SummarizingWindowStrategy.java |
| 488 | agents/core/mc-agent-tool-spi/src/main/java/ai/mindconnect/agent/tool/SpiToolRegistry.java |
| 474 | agents/server/mc-agent-chat-ui-rest/src/main/java/ai/mindconnect/chatui/ui/component/TaskCardComponent.java |
| 471 | agents/vectorstore/mc-vector-store-tools/src/main/java/ai/mindconnect/vectorstore/tools/VectorTools.java |
| 464 | common/mc-jdbc/src/main/java/ai/mindconnect/jdbc/DocumentTable.java |
| 464 | agents/core/mc-llm-gateway-core/src/main/java/ai/mindconnect/llm/domain/LlmConfig.java |
| 461 | workflow/mc-workflow-ui-diagram/src/main/java/ai/mindconnect/workflow/ui/diagram/BuildContext.java |
| 460 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/controller/ProfileUiController.java |
| 458 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/domain/AgentDefinition.java |
| 447 | agents/core/mc-llm-gateway/src/main/java/ai/mindconnect/llm/adapter/gemini/GeminiGateway.java |
| 445 | agents/core/mc-agent-tools/src/main/java/ai/mindconnect/agent/tools/builtin/FileEditTool.java |
| 444 | taskqueue/mc-taskqueue-cluster-demo/src/main/java/ai/mindconnect/taskqueue/clusterdemo/ui/TasksRenderer.java |
| 442 | agents/server/mc-agent-chat-ui-rest/src/main/java/ai/mindconnect/chatui/ui/page/ChatPage.java |
| 433 | taskqueue/mc-taskqueue-demo-app/src/main/java/ai/mindconnect/taskqueue/demo/ui/TaskBoardRenderer.java |
| 431 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/AdminLayout.java |
| 431 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/service/MigrationService.java |
| 421 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/service/task/ToolCallWorker.java |
| 415 | agents/office/mc-agent-tools-calendar/src/main/java/ai/mindconnect/office/tools/CalendarTools.java |
| 411 | agents/core/mc-agent-protocol-openai/src/main/java/ai/mindconnect/agent/protocol/openai/OpenAiResponsesBackend.java |
| 409 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/domain/AgentSession.java |
| 408 | agents/core/mc-agent-tools/src/main/java/ai/mindconnect/agent/tools/builtin/GlobTool.java |
| 402 | agents/server/mc-agent-api-rest/src/main/java/ai/mindconnect/agentrest/service/VectorStoreService.java |
| 397 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/service/TaskMonitor.java |
| 396 | agents/server/mc-agent-api-rest/src/main/java/ai/mindconnect/agentrest/service/SessionFileService.java |
| 396 | agents/builder/mc-agent-runtime-feature-core/src/main/java/ai/mindconnect/agent/runtime/feature/core/CoreFeature.java |
| 392 | agents/server/mc-agent-admin-ui-app/src/main/java/ai/mindconnect/adminui/SecurityConfig.java |
| 390 | common/mc-file-repo/src/main/java/ai/mindconnect/filerepo/RecordLog.java |
| 385 | agents/client/mc-agent-cli/src/main/java/ai/mindconnect/cli/agentclient/RemoteAgentClient.java |
| 383 | agents/office/mc-agent-tools-mail/src/main/java/ai/mindconnect/office/tools/MailListTools.java |
| 383 | agents/core/mc-llm-gateway/src/main/java/ai/mindconnect/llm/adapter/ProviderModelCatalog.java |
| 382 | agents/core/mc-agent-memory-strategies/src/main/java/ai/mindconnect/agent/memory/strategy/AutoCompactStrategy.java |
| 374 | agents/office/mc-calendar-caldav/src/main/java/ai/mindconnect/calendar/caldav/CalDavCalendarStore.java |
| 367 | agents/server/mc-agent-registry-admin-ui-rest/src/main/java/ai/mindconnect/agent/registry/admin/ui/RegistryBrowseView.java |
| 360 | agents/server/mc-agent-registry-admin-ui-rest/src/main/java/ai/mindconnect/agent/registry/admin/ui/RegistryUiController.java |
| 359 | workflow/mc-workflow/src/main/java/ai/mindconnect/workflow/execution/BaseStepContainerInstance.java |
| 358 | workflow/mc-workflow-ui-diagram-app/src/main/java/ai/mindconnect/workflow/ui/diagram/app/DiagramController.java |
| 350 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/page/SessionFilesPage.java |
| 350 | agents/office/mc-mail-imap/src/main/java/ai/mindconnect/mail/imap/ImapMailStore.java |
| 347 | workflow/mc-workflow-dsl-puml/src/main/java/ai/mindconnect/workflow/dsl/puml/PumlWorkflowSerializer.java |
| 346 | agents/server/mc-agent-chat-ui-rest/src/main/java/ai/mindconnect/chatui/ui/component/ChatFormComponent.java |
| 346 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/service/task/LlmChatProvider.java |
| 344 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/controller/ToolCatalogUiController.java |
| 342 | agents/core/mc-agent-tools/src/main/java/ai/mindconnect/agent/tools/builtin/GrepTool.java |
| 339 | agents/core/mc-extension-core/src/main/java/ai/mindconnect/extension/service/ExtensionService.java |
| 336 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/controller/SessionUiController.java |
| 335 | agents/office/mc-calendar-caldav/src/main/java/ai/mindconnect/calendar/caldav/CalDavClient.java |
| 334 | agents/server/mc-agent-registry-admin-ui-rest/src/main/java/ai/mindconnect/agent/registry/admin/ui/RegistryEntryView.java |
| 334 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/service/task/SubAgentCalls.java |
| 333 | agents/core/mc-agent-tools-web-browser/src/main/java/ai/mindconnect/agent/tools/browser/WebReadBrowserTool.java |
| 329 | agents/core/mc-agent-protocol-mc-runtime/src/main/java/ai/mindconnect/agent/protocol/runtime/ResponseAssembler.java |
| 325 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/service/MessageToLlmMessageMapper.java |
| 322 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/AdminLayoutFactory.java |
| 321 | workflow/mc-workflow/src/main/java/ai/mindconnect/workflow/persist/WorkflowInstanceSnapshots.java |
| 309 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/controller/UserToolUiController.java |
| 309 | agents/core/mc-credentials/src/main/java/ai/mindconnect/credentials/service/ConnectionService.java |
| 308 | agents/mcp/mc-mcp-gateway-admin-ui-rest/src/main/java/ai/mindconnect/mcp/gateway/admin/ui/McpGatewayUiController.java |
| 305 | taskqueue/mc-task-queue-schedule/src/main/java/ai/mindconnect/taskqueue/schedule/CronExpression.java |
| 305 | agents/springstarter/mc-agent-starter-runtime/src/main/java/ai/mindconnect/agent/starter/runtime/AgentRuntimeAutoConfiguration.java |
| 304 | agents/core/mc-agent-tools-document/src/main/java/ai/mindconnect/agent/tools/document/RelevantExcerpts.java |
| 302 | common/mc-file-repo/src/main/java/ai/mindconnect/filerepo/Documents.java |
| 302 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/controller/NamespaceUiController.java |
| 301 | agents/server/mc-agent-admin-ui-rest/src/main/java/ai/mindconnect/adminui/ui/component/AgentFormComponent.java |

## Highest comment share (files over 60 lines)

| Comment % | Comment / total lines | File |
| --- | --- | --- |
| 79 | 65 / 82 | agents/mcp/mc-mcp-gateway-core/src/main/java/ai/mindconnect/mcp/gateway/McpGateway.java |
| 76 | 51 / 67 | agents/core/mc-credentials/src/main/java/ai/mindconnect/credentials/oauth/OAuthProviderContribution.java |
| 75 | 95 / 126 | agents/core/mc-agent-tool-spi/src/main/java/ai/mindconnect/agent/tool/MultiToolProvider.java |
| 75 | 79 / 104 | agents/core/mc-agent-tool-spi/src/main/java/ai/mindconnect/agent/tool/ToolFactory.java |
| 73 | 66 / 90 | agents/core/mc-agent-registry-core/src/main/java/ai/mindconnect/agent/registry/port/out/RegistryInstaller.java |
| 73 | 54 / 73 | workflow/mc-workflow/src/main/java/ai/mindconnect/workflow/domain/CodeData.java |
| 69 | 111 / 160 | taskqueue/mc-task-queue/src/main/java/ai/mindconnect/taskqueue/TaskStore.java |
| 69 | 58 / 83 | agents/core/mc-llm-gateway-core/src/main/java/ai/mindconnect/llm/domain/LlmCallEvent.java |
| 68 | 128 / 188 | agents/office/mc-mail-core/src/main/java/ai/mindconnect/mail/MailStore.java |
| 68 | 80 / 117 | agents/vectorstore/mc-vector-store/src/main/java/ai/mindconnect/vectorstore/embedding/EmbeddingIndex.java |
| 65 | 81 / 123 | agents/core/mc-agent-tool-spi/src/main/java/ai/mindconnect/agent/tool/ToolAdvisor.java |
| 63 | 49 / 77 | taskqueue/mc-task-queue/src/main/java/ai/mindconnect/taskqueue/TaskQueue.java |
| 62 | 56 / 89 | workflow/mc-workflow/src/main/java/ai/mindconnect/workflow/domain/HttpCallData.java |
| 62 | 55 / 88 | taskqueue/mc-task-queue/src/main/java/ai/mindconnect/taskqueue/TaskContext.java |
| 61 | 47 / 77 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/port/in/AgentChatClient.java |
| 59 | 81 / 136 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/domain/StreamEvent.java |
| 59 | 67 / 113 | agents/core/mc-agent-tool-spi/src/main/java/ai/mindconnect/agent/tool/ToolRegistry.java |
| 59 | 38 / 64 | agents/core/mc-agent-protocol/src/main/java/ai/mindconnect/agent/protocol/item/ContentPart.java |
| 59 | 36 / 61 | workflow/mc-workflow/src/main/java/ai/mindconnect/workflow/persist/WorkflowInstanceSnapshot.java |
| 58 | 76 / 131 | agents/core/mc-agent-protocol/src/main/java/ai/mindconnect/agent/protocol/item/ConversationItem.java |
| 58 | 59 / 101 | agents/server/mc-agent-chat-ui-rest/src/main/java/ai/mindconnect/chatui/service/ActiveStreams.java |
| 58 | 44 / 75 | workflow/mc-workflow-jackson/src/main/java/ai/mindconnect/workflow/jackson/WorkflowObjectMapperFactory.java |
| 57 | 56 / 97 | agents/core/mc-agent-runtime-core/src/main/java/ai/mindconnect/agent/runtime/port/out/AgentSessionRepository.java |
| 57 | 38 / 66 | agents/core/mc-message-repository-core/src/main/java/ai/mindconnect/message/port/out/MessageRepository.java |
| 56 | 49 / 86 | workflow/mc-workflow-spi-lookup/src/main/java/ai/mindconnect/workflow/spi/SpiWorkflowContextFactory.java |

## Tests per area

| Area | @Test |
| --- | --- |
| agents | 2403 |
| common | 156 |
| taskqueue | 120 |
| workflow | 219 |
