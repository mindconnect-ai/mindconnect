# Code review for the 1.0 release

Date: 2026-10-11. Branch `feature/prep-release`, base `next` at `4c7a50d`.
Main sources only (`src/main`), 1403 classes, ~142k lines, all four areas.
The plan that follows from this review is in [plan.md](plan.md).

## 1. Criteria

The maintainer's criteria for 1.0:

- **Hexagonal.** Domain, ports and adapters are separate; services depend on
  ports; nothing infrastructural (Spring, Jackson databind, JDBC, file IO)
  leaks into a `-core` module; wiring lives in the starters.
- **One responsibility per class.** No god classes, no mixing of concerns
  (rendering + persistence, parsing + IO, controller + business rule).
- **Simple.** Short methods, classes not too big, every method named for what
  it does.
- **Named after known patterns.** Strategy, Factory, Repository, Adapter,
  Builder, Visitor, Policy, Registry, Provider, Resolver, Renderer, Converter.
  **No plural class names.** No vague suffixes (Helper, Util, Support, Commons,
  Holder, catch-all Service).
- **Little javadoc.** The code speaks; a comment says *why*, never *what*,
  and never tells history.

Plus the four rules of Karpathy's `CLAUDE.md`
([forrestchang/andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills),
distilled from Karpathy's notes on LLM coding habits), applied here as review
criteria:

1. **Think before coding.** State assumptions; when several readings exist,
   ask instead of picking one silently. In this review: every ambiguous
   finding becomes an open question (section 6), not a silent decision.
2. **Simplicity first.** Minimum code for the problem. No speculative
   configurability, no single-use abstraction, no error handling for
   impossible cases, no unrequested features.
3. **Surgical changes.** Touch only what the task needs; match the surrounding
   style; mention unrelated dead code rather than deleting it in passing.
4. **Goal-driven execution.** Every step has a verifiable goal: a failing test
   before a bug fix, a green build and a diff that contains nothing else
   before a rename lands.

## 2. Numbers

| Measure | Value |
|---|---|
| Classes with ≥ 1000 lines | 5 (`ChatUiController` 2494, `WorkflowAdminUiController` 2101, `CliRunner` 1456, `VectorStoreUiController` 1152, `PumlWorkflowParser` 1008) |
| Classes 500–999 lines | 31 |
| Classes 300–499 lines | 61 |
| Methods ≥ 50 lines | 84 (longest: `CliRunner.run` 208, `CliRunner.streamAgentResponse` 184, `PumlWorkflowParser` body loop 178, `GlobTool.execute` 165, `GrepTool.execute` 158) |
| Lines inside javadoc blocks | ~29,000 of ~142,000 (20 %); five modules above 40 % |
| Files citing a private "concept NN" design note | 49 files, 62 references |
| Code lines using an inline fully qualified name (`java.util.Map`, `ai.mindconnect.…`) instead of an import | ~1,600 lines in 197 files |
| Class names ending in a plural `s` (excluding `-ss`, `Status`, `Settings` …) | ~120 |
| Classes named `*Service` / `*Manager` / `*Support` / `*Helper` / `*Holder` / `*Util` | 47 |
| `catch (Exception \| Throwable \| RuntimeException e) {` | 364 |
| Files that use `java.util.logging` while the rest uses SLF4J | 3 |
| Source files with a literal NUL byte (used as key separator) | 2 (`InMemoryMailIndexStore`, `InMemorySharedLockStore`) |
| Spring imports inside a `-core` module | 0 (good) |
| Jackson databind imports inside a `-core` module | 6 files, all in `mc-agent-runtime-core` |

The numbers say what the reading confirms: the architecture is sound and the
small classes are good; the debt sits in a few dozen large classes, in
copy-paste between siblings, in naming, and in javadoc that documents the
project's history instead of the code.

## 3. Cross-cutting findings

These patterns recur in every area. They are listed once here; the per-area
tables in section 4 give the file-level evidence.

| ID | Pattern | Evidence | Proposal |
|---|---|---|---|
| X-01 | **Javadoc tells history, not contract.** Essays of 15–60 lines on records and interfaces; "concept 16, step 5", "§5.2 of the concept", "Etappe 1a", "it used to live on …", "the email_… tools are gone". A 1.0 reader has none of those documents. | RT-11, GW-28, TL-29, WF-21, TQ-08, CM-12, SV-24 | One paragraph of *what and why* per type; move rationale and history to `agents/doc/`, `workflow/doc/` or the docs site; delete every concept number. Add a javadoc lint (orphaned blocks, blocks between `@Override` and method). |
| X-02 | **Plural class names hide what a class is.** `SessionTools`, `ToolCalls`, `LlmPrices`, `MailTools`, `VectorStores`, `WorkflowShapes`, `AtomicFiles`, `ActiveStreams` … ~120 names. Most are a Registry, Catalog, Resolver, Converter, Policy or a static bag that should be one of those. | Section 5 | Rename to the pattern the class implements. Rule for CLAUDE.md: a class name is singular and names its role. |
| X-03 | **Inline fully qualified names instead of imports.** `java.util.Map<String, java.util.Set<String>>`, `ai.mindconnect.agent.runtime.service.prompt.InstructionFiles instructions`. ~1,600 lines. | RT-12, GW-19, TQ-10, SV-25 | One mechanical sweep per area (IDE "optimize imports"), no other change in the same PR. |
| X-04 | **Telescoping constructors with only the fullest one used.** Nine runtime classes, `ProfilePage` (5), `AdminLayout` (4), `NamespaceService` (7), `ScopeBindingFilter` (3), 13 tool classes with three constructors each (two test-only). | RT-04, TL-09, GW-08, SV-18, SV-28 | One constructor per class before 1.0 freezes the API. Tests build the full argument list. |
| X-05 | **Error handling for impossible cases.** `try { prettyJson = mapper.writeValueAsString(nodeJustBuilt) } catch (Exception ignored) {}`, "should be unreachable" branches, serialising a `Map<String,Object>` guarded by a catch. 364 broad catches in total; many are legitimate (tool execution), many are not. | GW-26, RT-26 | Remove the catches on code that cannot fail; let the rest propagate or narrow the type. |
| X-06 | **Wiring and adapters inside core modules.** Spring `@AutoConfiguration`, `@Component`, `@RestController` and Postgres adapters sit in `core/` tool modules and in `mc-llm-gateway`; persistence ports live in an adapter module (`mc-mcp-gateway-local`); `mc-user-core` has an `adapter` package; `mc-credentials` has no core split; feature modules wire their own Pg beans. | GW-05, GW-12, GW-13, GW-27, TL-03, TL-05, TL-06, TL-24, TL-25, SV-10 | Rule: ports and domain in `-core`, adapters in the sibling or `adapter/postgres/`, Spring wiring only in `springstarter/` (or an `-autoconfigure` module of the area). Enforce with an ArchUnit test. |
| X-07 | **Two vocabularies for the driving side and for persistence.** `port.in` interfaces in llm and message, concrete `XxxService` classes in user, namespace, extension, registry, credentials. `Repository` (121) vs `Store` (41) vs `Repo` (1); fields named `approvalStore` typed `ToolApprovalRepository`; `mc-file-manager` vs `mc-file-repo`. | GW-15, RT-14, TQ, CM | Decide once (open questions Q-02, Q-03), then rename. |
| X-08 | **Copy-paste between siblings.** Four LLM gateways with the same 150-line streaming skeleton; two taskqueue demos 90 % identical; `AtomicFiles` = `FileWrites`; `BaseDirs` = `DocBaseDirs`; nine walks over the workflow step tree; three step-type tables; `dialog()` in 8 controllers; `userId(OidcUser)` in 10; attach-and-ingest twice; stream frame encode/decode on both sides by hand. | GW-01..04, TQ-01, TQ-04, CM-02, TL-01, WF-13, WF-14, SV-02, SV-06, SV-07, SV-21 | Extract the one class each copy wanted to be. |
| X-09 | **Dead code and speculative API.** Methods whose javadoc says "no caller", "backward-compat" overloads, ports with no adapter (`SharedLockStore`), DDL with no store, `HasNext` with no implementor, `Class.forName` for a class in a module already on the classpath. | RT-09, WF-08, WF-22, TQ-06, TQ-07, TL-30, SV-22, GW-16, GW-25 | Delete before 1.0; nothing deleted is lost, git has it. |
| X-10 | **Three logging and serialisation styles.** `java.util.logging` in three file adapters; five file adapters build their own `ObjectMapper` while the factory injects the runtime's; a second `ObjectMapper` bean in an adapter library. | RT-16, GW-05, GW-18, TQ-04 | SLF4J everywhere; one injected mapper. |
| X-11 | **Literal NUL characters in source as separators.** `user.value() + "<NUL>" + …` | `InMemoryMailIndexStore:40,48`, `InMemorySharedLockStore:84` | Write `'\0'` or use a composite key record. |

## 4. Findings per area

Effort: S (hours), M (a day or two), L (several days). Risk: how likely a
behaviour change slips in.

### 4.1 agents/core: domain, runtime-core, runtime, memory-strategies, tool-spi (RT)

Good: ports are interfaces, file/in-memory adapters live in `mc-agent-runtime`,
the turn loop (`AgentLoop`, `AgentRound`, `ToolCalls`) is small and queue-free,
typed ids are used consistently. Main smell: accretion at the service layer.

| ID | Where | Criterion | Problem | Proposal | Effort / risk |
|---|---|---|---|---|---|
| RT-01 | `runtime-core/service/AgentChatService.java` (770) | SRP | Submits turns, bookkeeps task futures, answers approvals (:509), cancels and supersedes (:441, :597), attaches reconnecting streams (:574), builds memory snapshots (:722), exposes UI tool definitions (:197). | `ChatTurnSubmitter`, `ApprovalService`, `SessionStreamAttachment`, `MemoryService`. | L / med |
| RT-02 | `service/AgentSessionService.java` (636) | SRP, hexagonal | Session CRUD mixed with working-directory policy (:151–310), a file-explorer factory (:192), parent-chain queries (:603–635), raw `Files.walk` deletion (:530–545). | `SessionDirectoryPolicy` / `UserHome`, `SessionChain`, `UserHome.deleteSessionDir`. | M / med |
| RT-03 | `ToolCallWorker.approvalGate` (:282–317), `AgentChatService.answerApproval` (:509–546), `AgentSessionService.isToolApproved` (:603) | SRP | Approval spread over three classes. | One `service/approval` package: `ApprovalGate` + `ApprovalService`. | M / med |
| RT-04 | `AgentChatService` 3 ctors, `AgentSessionService` 4, `AgentTurnWorker` 4, `ToolCallWorker` 2, `SessionTools` 3, `LlmChatProvider` 2, `DynamicToolActivations` 3, `WorkingMemoryBuilder` 3, `SystemPromptRenderer` 3 | simplicity | Only the fullest constructor is used (`AgentRuntimeBuilder:509–535`). | Delete the others. | S / low |
| RT-05 | `service/task/ToolCallWorker.execute` (:168–265) | simplicity | 98 lines: payload decoding, duplicate check, gate switch, run, cancel race, cap, append. `approvalGate` returns an enum but also writes the store and publishes cards. | `ToolTaskPayload` record, `runAndRecord`, a pure gate. | M / low |
| RT-06 | `round/ToolCalls:48`, `task/LlmChatProvider:76`, `round/TurnMessage:6`, `ToolCallWorker:271`, `MessageToLlmMessageMapper:57`, `SummarizingWindowStrategy:50,424`, `ToolPairSanitizer:72,145,157`; `FeatureContext:103` exposes `ObjectMapper` | hexagonal, duplication | Jackson databind inside core; five parsers of the same TOOL_CALL / TOOL_RESULT JSON. | One `ToolCallContent` / `ToolResultContent` codec next to `Message`; `objectMapper()` only for adapters. | M / med |
| RT-07 | `service/SessionDirectories.java` (536), `UserHome`, `WorkingDirBrowser`, `WorkingDirPolicy`, `prompt/InstructionFiles`, `skill/FileSkills`, `agents/ProjectAgents`, `feature/Persistence.inMemory():58` | hexagonal | `java.nio` and `ZipOutputStream` in `runtime-core/service`; `SessionDirectories` has `if (remote != null)` in every public method (:94, :132, :160, :193, :215, :334) and reaches into `FileRoots` inline (:236). | One implementation over `Map<String, WorkspaceFiles>` using `WorkspaceFiles.local(FileRoots.of(root))`; halves the class. See Q-05. | L / med |
| RT-08 | `memory-strategies`: `SummarizingWindowStrategy` vs `AutoCompactStrategy` | duplication | `renderSummariesAsUserMessage`, `appendSummaryBody`, `summarizedSequenceNumbers`, `effectiveText`, `summarise`, window-row building (:122–155 vs :103–130) duplicated; `permissiveBudget` repeated in four strategies with a 10-argument `SummarizingWindowConfig` (`NoMemoryStrategy:409–412`). | `SummaryRenderer` + `ConversationSummarizer`; `ContextTokenBudget.unlimited(...)`. | M / low |
| RT-09 | `SummarizingWindowStrategy:306,323`, `AgentTurnWorker.string:511`, `SessionTools.isInline:481`, `ToolCalls.inState/byId:115–121`, `ToolExecutor.Context` 4-arg ctor :259, `AgentTaskRunner.run` default :38 | dead code | Javadoc itself says "No caller" / "backward compatibility". | Delete. | S / low |
| RT-10 | `ToolExecutor:31`, `AgentTurnWorker:61`, `DynamicToolActivations:22`, `ResponseReviewerChain:34`, `SpiToolRegistry:433`, `LlmChatProvider:281`; orphaned double javadocs `AgentChatService:548–562, 645–651`, `AgentSessionService:302–303`, `AgentTurnWorker:424–426` | javadoc | Names removed classes and methods; blocks sit above the wrong declaration. | Fix; add a javadoc lint. | S / low |
| RT-11 | `ToolCallWorker:45–67`, `ToolCalls:19–44`, `QueuedAgentRoundToolExecutor:220–246`, `UserToolRoster:504–529`, `ToolPairSanitizer:23–68`, `DynamicToolActivations:89–116`, `SummaryWindowMessages:285–301`, `AgentSessionService.loadHistory:484–491` | javadoc | Essays, bug history, "concept 16, step 5". | Keep one paragraph of what; move why and history to `agents/doc/`. | M / low |
| RT-12 | `AgentSession` (48 FQNs), `AgentSessionService` (20), `AgentChatService` (31), `StatelessAgentSeeder` (13), `SpiToolRegistry` (10), `DynamicToolActivations` (9), `SubAgentCalls`, `WorkingDirPolicy` | style | Inline fully qualified names. | Imports. | S / low |
| RT-13 | ~28 plural names | naming | See section 5. | Rename. | M / low |
| RT-14 | `SkillRepository` in `skill/`, `UserMemoryRepository` in `usermemory/`, `TodoListRepository` in `tools/todo/`, `MemoryStrategy` under `memory/port/in`, `LlmMessageMapper` in `port/out` with its default in `service/`; `tool-spi/ToolRepository` stores settings; fields `approvalStore` | consistency | Port placement and naming differ inside one module. | Either all ports under `port/{in,out}` or every feature package gets its own `port/`; `ToolRepository` → `ToolSettingsRepository`; fields `approvalRepository`. | M / low |
| RT-15 | `mc-agent-runtime/pom.xml:65–68` | hexagonal | `spring-context` declared, never imported. | Remove. | S / low |
| RT-16 | `FileToolRepository:36` (`java.util.logging`); own `ObjectMapper` in `FileToolRepository:38`, `FileConversationSummaryRepository:34`, `FileLlmCallTraceRepository:67`, `FileTodoListRepository:36`, `FileWorkingMemoryRepository:32` while `FileAgentRepositoryFactory:26` injects the runtime's | consistency | Two logging and two mapper conventions in one adapter package. | SLF4J; inject the mapper. | S / low |
| RT-17 | `mc-agent-runtime/adapter/{repo/memory,file,filestore,rule,llm,token,local,prompt}` | consistency | Packages mix by-technology and by-port. | `adapter/file`, `adapter/inmemory`, `adapter/pebble`, `adapter/jtokkit`, `adapter/local`. | S / low |
| RT-18 | `AgentChatService:766`, `AgentTurnWorker:520`, `ToolCallWorker:417`, `SubAgentCalls:220` | duplication | `new SessionAgentResolver(definitionRepository).resolve(session)` plus the same 5-line javadoc four times. | Inject one resolver. | S / low |
| RT-19 | `ToolCallWorker:132,137`, `SubAgentCalls:56` (`volatile TaskQueue`) | design | Worker constructs its own `SubAgentCalls` and needs `attach(TaskQueue)` after construction. | Pass it in via the builder. | S / med |
| RT-20 | `domain/AgentDefinition.java` (458, 19 components, hand ctors :61–104), `domain/AgentSession.java` (409, 18 components, `@JsonCreator fromJson` :291–319) | simplicity | `fromJson` only re-wraps strings into id records; behaviour in the compact constructor. | Id records carry their own `@JsonCreator`; `with*`ers instead of ctor overloads; `DERIVED_TOOLS` filtering to the registry service. | M / med |
| RT-21 | `AgentTurnWorker.runTurn` (:295–415) | simplicity | 120 lines: assembles six collaborators, then four outcome branches with inline user-facing texts (:385–398). | `TurnAssembly`, `TurnEnding`. | M / low |
| RT-22 | `turn/WorkingMemoryBuilder` (static, not a Builder), `prompt/SystemPromptRenderer` (static, 8 params), callers `AgentChatService:726`, `AgentTurnWorker:473`, `LlmChatProvider:165` | SRP | The tuple (`PromptRenderer`, `InstructionFiles`, `SkillCatalog`, `PromptSections`) is threaded by hand through five classes. | One injectable `SystemPromptComposer`; `WorkingMemorySnapshot.of(...)` on top. | M / low |
| RT-23 | `tool-spi/ToolRegistry.java:17–113` | SRP | 11 default methods mix resolution with catalog/UI concerns; `source()` leaks the decorator chain. | `ToolCatalog` (read model) + `ToolRegistry` (resolve). | M / med |
| RT-24 | `AgentChatService:693` | hexagonal | `scope instanceof ThreadBoundScope` in a service. | `ScopeSupplier.runIn(Scope, Runnable)` with a no-op default. | S / low |
| RT-25 | `mc-agent-domain`: `NamespaceRouted` (dynamic proxy, static `ALL` :183), `ThreadBoundScope` (duplicated `runIn`/`callIn` :74–105), `NamespaceFirstUse`, `StartupScope` | hexagonal | Infrastructure in the vocabulary module. | Move to a `scope` package in runtime-core or `mc-agent-scope`. See Q-06. | M / low |
| RT-26 | `ToolCallWorker.approvalContent:324`, `LlmChatProvider.toolCallContent:273`, `ToolPairSanitizer.syntheticPlaceholder:185`, `ToolExecutor.invokeTool:393–403` | simplicity | Catches on code that cannot fail; hand-built JSON on failure. | Remove. | S / low |
| RT-27 | `SummarizingWindowStrategy:241`, `AutoCompactStrategy:11` → `StatelessAgentSeeder.CONVERSATION_SUMMARIZER`; `service.ContextTokenBudget` | hexagonal | Strategies reach into runtime-core `service`. | `BuiltInTask` next to `AgentTaskRunner`; `ContextTokenBudget` to `memory/domain`. | S / low |
| RT-28 | `tool-spi/ToolCallScope.java:207–223` | design | A second ambient context (`InheritableThreadLocal`) beside `ThreadBoundScope`. | Derive from `Scope` host attributes or document why two exist. See Q-07. | S / med |
| RT-29 | `ToolExecutor.execute` (:343), `SessionTools.liveTool` (:404), `AgentLoop:117–121` | simplicity | Name lookup repeated after it was already done; chain-building duplicated. | Take `Optional<Tool>`; extract `chain()`. | S / low |
| RT-30 | `tool-spi/SpiToolRegistry.java` (488) | SRP | ServiceLoader discovery, warm-up with retries (:163–251), catalog queries, decoration (:482–487). | Extract `ProviderWarmUp`. | M / low |

### 4.2 agents/core: llm-gateway, message-repository, protocol, credentials, user, namespace, extension, registry, Postgres adapters, mc-jdbc (GW)

Good: `domain` / `port.out` / `service` in a `-core` module, adapters in the
sibling, Postgres adapters uniformly on `DocumentTable`. `mc-jdbc` is small
and cohesive. Main smell: the four LLM provider adapters are one adapter
copied four times.

| ID | Where | Criterion | Problem | Proposal | Effort / risk |
|---|---|---|---|---|---|
| GW-01 | `llm/adapter/anthropic/ClaudeGateway:74–226`, `openai/AbstractOpenAiGateway:71–232`, `openai/OpenAiResponsesGateway:132–236`, `gemini/GeminiGateway:74–216` | duplication, SRP | `chatStreaming` (resolve config → build body → trace → OkHttp → non-2xx → SSE loop → cancel → listener → `Done`) four times; only `buildRequestNode`, the per-event parse and `parseFinishReason` differ. | One `SseChatGateway` template with a `ProviderWireFormat` strategy per provider. See Q-08. | L / med |
| GW-02 | `ClaudeGateway:327–349`, `AbstractOpenAiGateway:406–428`, `LlmGateway.ToolCallAccumulator:112–146`, `GeminiGateway` inline | duplication | Four ways to assemble a streamed tool call from fragments. | One `ToolCallAssembler` in core domain. | S / low |
| GW-03 | `fireListener` (13 params) ×4, `logWireRequest` ×3, `summary` ×3, `extractDataLine` ×3, `dataUrl` ×2 | duplication | Same trace plumbing in every gateway. | `LlmCallTraceRecorder`. | M / low |
| GW-04 | Non-2xx block in all four gateways | duplication | Log, parse Retry-After, throw — verbatim. | `ProviderHttpError.of(label, config, response).toException()`. | S / low |
| GW-05 | `openai/OpenAiCompatibleConfig:9–15`, `OpenAiCompatibleGateway:12`; `mc-message-repository/pom.xml:41` | hexagonal | Adapter library declares `@Configuration` beans (`OkHttpClient`, bare `ObjectMapper`) and `@Component`; nothing imports them; a host that scans `ai.mindconnect` gets a second `ObjectMapper` bean. | Delete; drop `spring-context` from both POMs. See Q-09. | S / low |
| GW-06 | `llm/port/out/LlmGateway.java:59–146` | hexagonal | The out-port carries 90 lines of implementation (`chat` default, accumulator, `new ObjectMapper()` per call :139); no production caller of `chat`. | Move to a small helper in the adapter module or a test fixture. | S / low |
| GW-07 | `llm/domain/LlmConfig.java:220–310, 393–463` | SRP | Twelve provider preset factories with hard-coded context windows plus four `resolved` overloads on the domain record; `EnvVarResolver` imported twice. | `LlmConfigPresets` in the adapter module; one `resolved(env, encryption)`. | M / low |
| GW-08 | `namespace/service/NamespaceService.java` (537, 7 ctors :70–107) | SRP | Membership, variables, lifecycle, identity, access policy in one class. | `NamespaceAccessPolicy`, `NamespaceMembership`, `NamespaceVariables`; one ctor with a `NamespaceSettings` record. | M / med |
| GW-09 | `registry/service/RegistryService.java` (562) | SRP | Source CRUD, browsing, the dependency walk with three anonymous `Visitor`s (:139, :312, :356, :420), `usedOutside` fixed point (:174–215), removal. | `PackageWalker`, `PackageUsageAnalyzer`. | M / low |
| GW-10 | `protocol/runtime/AgentRuntimeBackend.java` (523) | SRP | Four anonymous classes implement `Sessions`, `AgentResponses`, `Files`, `Conversations`; file attachment (:354–436), a JSON parse of `ToolApproval.content` (:141–157), mutable `withFiles` setters (:164–169), a static `ObjectMapper` from an undeclared dependency (:134). | `RuntimeSessionApi`, `RuntimeResponseApi`, `RuntimeFileApi`, `RuntimeConversationApi` sharing a `BackendContext`; files as a ctor argument. | M / low |
| GW-11 | `protocol/openai/OpenAiResponsesBackend.java` (411) | SRP | Same shape plus a delegation engine (`createLogical`, `delegate`, `rewriteToAgentCall`, `awaitTerminal` :227–358) with a hard-coded 700 ms poll. | Extract `AgentDelegationLoop`. | M / low |
| GW-12 | `mc-credentials` (`domain`, `port`, `service`, `oauth`, `adapter` in one module) | consistency | The only area module without a `-core` sibling; `OAuthConnections` (a service) in `oauth`. | Split like the others or document the exception; move to `service`. See Q-10. | M / low |
| GW-13 | `mc-user-core/.../adapter/tool/StoredUserToolRoster` | hexagonal | An `adapter` package inside a `-core` module. | Move to `mc-user`. | S / low |
| GW-14 | `mc-extension/.../runtime/ExtensionToolRegistry`, `ExtensionsFeature` | consistency | Adapters outside the `adapter.*` package. | `adapter.runtime`. | S / low |
| GW-15 | `message/port/in/ConversationManager` vs `llm/port/in/LlmChat`, `LlmTranscription`, `LlmEmbeddings`; no `port.in` in user, namespace, extension, registry, credentials | consistency | Three conventions for the driving side. | Pick one (Q-02). | M / low |
| GW-16 | `message/adapter/memory/InMemoryMessageStore` | dead code | Only two tests use it; javadoc names a `FileMessageStore` that does not exist. | Test fixture or delete. | S / low |
| GW-17 | `message/service/ConversationService.java:118–126` | correctness | `deleteMessages` loads a page of 500 to count matches; conversations with more than 500 messages are miscounted. | `MessageRepository.deleteBySequenceRange` returns the count. | S / low |
| GW-18 | `message/adapter/file/FileConversationRepository:14,29`, `FileMessageRepository:131,146` | consistency | `java.util.logging`. | SLF4J. | S / low |
| GW-19 | `openai/OpenAiResponsesGateway:152–153, 389, 429, 510, 105, 120, 175` | design | Reaches into the sibling's package-private `wire` logger and `OpenAiToolCallBuilder`; FQNs instead of imports. | Resolved by GW-02/03. | S / low |
| GW-20 | `llm/domain/LlmProvider.java:105–139, 141–149` | SRP | The provider enum carries admin-form concerns (`isADefaultBaseUrl` "the form uses it", `label()`, `AdditionalParamSpec` with UI help text). | Acceptable for 1.0; else `ProviderParamCatalog` in the adapter module. | M / low |
| GW-21 | `protocol/Response.java:290–302` | javadoc | Javadoc for `outputText()` attached to `openFunctionCalls()`. | Move. | S / low |
| GW-22 | `protocol/openai/OpenAiMapper.java:232–235` | simplicity | `incompleteReason(JsonNode n)` ignores its argument. | Read `incomplete_details.reason` or inline the constant. | S / low |
| GW-23 | `mc-llm-gateway-pg/PgLlmConfigRepository:55–57` (and others) | javadoc | Javadoc between `@Override` and the method. | Grep and fix across the pg modules. | S / low |
| GW-24 | All 17 Postgres repositories | consistency | Two javadoc sentences copied 17 times; some `final`, some not; table name as constant or inline. | `PgRepository` marker documented once; all `final`; one style. | S / low |
| GW-25 | `RetryingLlmGateway:313–316`, `ThrottlingLlmGateway:204–207`, `GeminiGateway:329–331` | simplicity | `delegate()` "mainly for tests"; a `"TOOL_CALL"` case that "guards against future API changes". | Remove. | S / low |
| GW-26 | `AbstractOpenAiGateway:97`, `ClaudeGateway:97`, `GeminiGateway:99`, `OpenAiResponsesGateway:151` | simplicity | `catch (Exception ignored)` on serialising a node the same mapper just built. | Goes with GW-03. | S / low |
| GW-27 | `mc-extension/spring/ExtensionAutoConfiguration:138–169`, `mc-agent-registry/spring/RegistryAutoConfiguration:93–127` | consistency | These two wire their own Pg beans (with FQN references to the `-pg` module); every other `-pg` adapter is wired by `mc-agent-starter-postgres`. | Move to the starter. See Q-11. | M / med |
| GW-28 | `LlmConfig:70–97`, `protocol/item/ConversationItem:6–33`, `event/ResponseEvent:203–230`, `oauth/OAuthProviderContribution:645–707`, `ThrottlingLlmGateway:164–189` | javadoc | 15–30 line essays with design history ("what Chat Completions had to do", "concept 7/9"). | One paragraph; rationale to `agents/doc/`. | S / low |
| GW-29 | `mc-jdbc/Sql.java:503` | style | `public final class  Sql` (double space). | Fix. | S |
| GW-30 | `llm/adapter/ProviderModelCatalog` (383), `lmstudio/LmStudioModelCatalog` (205) | duplication | Two catalogs with their own `Catalog` records, `normalise(baseUrl)`, `get`, `intOrNull`. | One catalog with LM Studio as a fourth request shape. | M / low |

### 4.3 agents: tool modules, office, mcp, vectorstore (TL)

Good: `MailStore`/`MailProvider`, `CalendarStore`/`CalendarProvider`,
`EmbeddingIndex`/`EntryStore`, `McpGateway`, `WorkspaceFiles`/`WorkspaceProvider`
are small, data-only ports. Main smell: tool modules are composition roots in
disguise.

| ID | Where | Criterion | Problem | Proposal | Effort / risk |
|---|---|---|---|---|---|
| TL-01 | `tools-document/DocBaseDirs:9–12, 44–69, 88–92` | duplication | Verbatim copy of `builtin/BaseDirs`, `FileRootedToolFactory`, `FileWalks.couldNotCheck` ("so each module remains independently deployable"); both already depend on `mc-agent-tool-spi`. | Move the three into `mc-agent-tool-spi` (`tool.workspace`); delete the copies. See Q-12. | S / low |
| TL-02 | `vector-store-tools/DefaultVectorStores:1–27, 117–170, 334–392, 491–533`; `VectorStores.fromEnvironment:41–43` | hexagonal, SRP | Tool module imports `PgEmbeddingIndex`, `PGSimpleDataSource`, `Sql`, `EncryptionHelper`, `LlmConfigRepository`, parses `ToolEnvironment`; the "port" calls its only implementation. One class does env parsing, DataSource creation, pgvector detection, legacy import, template lookup, embedding. | `VectorStoreConfiguration`, `EmbeddingIndexOpener`, `VectorStoreEmbedder`; Postgres half next to `mc-vector-store-pgvector`; no static on the interface. | L / med |
| TL-03 | `tools-workflow/WorkflowToolProvider:72`, `NamespacedWorkflowStoresAutoConfiguration`, `McAgentWorkflowStepsAutoConfiguration`, `registry/WorkflowRegistryAutoConfiguration`, `pom.xml` | hexagonal | A `core/` tool module instantiates `FileWorkflowDataRepository`, ships three `@AutoConfiguration`s, depends on `mc-workflow-persistence-pg` and `mc-workflow-admin-rest`. | Keep tool, provider, steps; move wiring to `springstarter/` or an `adapter/` module. See Q-13. | M / med |
| TL-04 | `tools-workflow/step/ToolInvokers:10–20`, `AgentInvokers:10–20` | hexagonal, naming | Static mutable service locators (`set`/`clear`/`require`). | Hand the invoker through the `WorkflowContext` attribute; else `ToolInvokerHolder`. | M / med |
| TL-05 | `mcp-gateway-local/McpServerRepository`, `McpDiscoveryStore`, `McpStoreFactory`; `mc-mcp-gateway-pg/pom.xml` | hexagonal | Persistence ports live in the adapter module, so the Postgres adapter depends on the file adapter. | Move the three interfaces to `mc-mcp-gateway-core`. | S / low |
| TL-06 | `mail-views/PgViewStore`, `mail-index/PgMailIndexStore`, both POMs | consistency | Postgres adapters beside the port instead of `adapter/postgres/`. | `mc-mail-views-pg`, `mc-mail-index-pg` (or one `mc-office-pg`). | S / low |
| TL-07 | `tools/builtin/BashTool.java` (618): `execute` :125–232, `detaches` + `LONG_RUNNING` :369–475, `Output` :551 | SRP | Process supervision, output capping, background start, a hand-written shell lexer, long-running heuristics. | `ShellCommandInspector`, `CappedOutput`, `ForegroundRun`. | M / med |
| TL-08 | `GlobTool.execute` :148–316, `suggestionsFor`/`levenshtein` :319–395; `GrepTool.execute` :113–270 | simplicity | 170 and 160 lines with `int[]`/`boolean[]` capture arrays, inline walkers, inline rendering. | `WalkBudget`, `GlobMatchCollector` / `GrepFileScanner`, `render…`, `ClosestNameSuggester` (also for `FileEditTool.closestPassage` :205–250). | M / low |
| TL-09 | 13 tool classes, e.g. `GlobTool:64–76`, `ReadDocumentTool:36–49`; `BashTool(File)`, `BashTool(FileRoots)` | dead code | Three constructors (`Path`, `FileRoots`, `WorkspaceFiles`), only the last used from main. | One constructor; tests call `WorkspaceFiles.local(FileRoots.of(dir))`. | S / low |
| TL-10 | `tools-document/DocumentReader.java`: PDF :93–207, DOCX heuristics :209–563, text :565–610, cache :612 | SRP | Three parsers, a heading-level heuristic by font size, and an LRU cache in one class. | `PdfDocumentParser`, `DocxDocumentParser` (+ `DocxHeadingHeuristic`), `TextDocumentParser`, `DocumentModelCache`; `DocumentReader` dispatches. | M / low |
| TL-11 | `tools-document/DocumentParser` (static Tika/HTML/URL) vs `DocumentReader` | naming | Two "parsers" with different jobs; compat overloads (:49, :70) with one caller. | `WebContentExtractor` or merge into `HtmlToMarkdown`; delete overloads. | S / low |
| TL-12 | `tools-document/HtmlToMarkdown`, `HtmlLinkExtractor`, `RelevantExcerpts`; `VectorIngestFileTool:118–129` (`Class.forName`) | hexagonal | Web helpers live in the document module so web and browser can depend on it; vector tools reach `DocumentReader` by reflection through an optional dependency. | `mc-document-core` (model, parsers, HTML helpers, no tools). | M / low |
| TL-13 | `tools-mail/MailTools.java:131–243, 436–489, 549–598` | SRP | `listing()` is a 110-line lambda; the class also owns index write-through and folder resolution. | One class per tool; `MailIndexWriteThrough`. See Q-14. | M / low |
| TL-14 | `tools-mail/OfficeTool.java:93–214` | SRP | Generic function-backed `Tool` plus a static JSON-schema DSL plus static argument parsers plus date formatting. | `ToolSchema` and `ToolArguments` in `mc-agent-tool-spi`; reuse by `GlobTool.parseLimit`, `GrepTool.intArg`, `FileReadTool.intArg`, `VectorTools.clamp/schema` :446–466. | S / low |
| TL-15 | `tools-calendar/pom.xml` → `tools-mail`; both declare `ai.mindconnect.office.tools` | consistency | Calendar depends on mail only for `OfficeTool`/`Accounts`; split package across two jars. | After TL-14 drop the dependency; own packages. | S / low |
| TL-16 | `mail-imap/MailboxConnectionProvider:23–30`, `calendar-caldav/CalDavConnectionProvider:16–29` | hexagonal | A connection card is published by implementing `MultiToolProvider` with `toolNames() = Set.of()` and `create() = Optional.empty()`. | `ConnectionProvider` SPI (`connectionSpec()`, `connectionTester()`) in `mc-agent-tool-spi`. | M / med |
| TL-17 | `mail-imap/Mailbox.java` (812): statics :101–214, instance ops :272–507, search :649–712, helpers :759–792 | SRP | A per-folder session, a connection-level helper set and MIME utilities in one class. | `ImapConnection`; `Mailbox` as the open-folder object; `addresses`/`explain` to `MailText`/`ImapErrors`. | M / med |
| TL-18 | `mail-core/ConnectedMailbox:54–60`, `calendar-core/ConnectedCalendar:25–32` | hexagonal | The open core hard-codes `"microsoft" → "Outlook"`, `"google" → "Gmail"`. | Provider answers `displayName()`. | S / low |
| TL-19 | `mail-core/pom.xml`, `calendar-core/pom.xml` → `mc-agent-tool-spi` | hexagonal | The port of a mailbox depends on the tool SPI for `ToolConnection`/`Connections`/`ConnectionSpec`. | Move connection vocabulary to `mc-agent-domain` or `mc-connections-core`. See Q-15. | M / med |
| TL-20 | `calendar-caldav/ICalendar.java` (622, all static), `CalDavClient:295–331` | SRP | Reader, writer and patcher in one static bag; XML helpers on the HTTP client. | `ICalendarReader`, `ICalendarWriter`, `ICalendarPatch`; `DavXml`. | M / low |
| TL-21 | `vector-store-tools/VectorTools.java` (471) | consistency | Outer static class with `BaseFactory`, three nested factories, three record tools, registered as `VectorTools$UpsertFactory`. | `VectorUpsertTool` + `VectorUpsertToolFactory` etc. with a shared `VectorToolFactory`. | S / low |
| TL-22 | `VectorStores`, `VectorStore`, `VectorStoreRegistry`, `VectorStoreInstance`, `VectorStoreTemplate`, `IndexDefinition` | naming | Six "store" nouns; `VectorStores` is a façade with 20 methods and defaults returning `"unknown"` (:33–41). | `VectorStoreCatalog`, `VectorStoreHandle`; drop the defaults. | S / low |
| TL-23 | `LegacyVectorStores`, `DefaultVectorStores:393–409` | simplicity | Pre-0.9 migration runs at every first namespace use. | One-off `migrate` command or a removal date. See Q-16. | S / low |
| TL-24 | `mcp-gateway-local/McpStartPolicy:3,37` | hexagonal | A record imports Spring `Environment`. | `from(Environment)` into the auto-configuration. | S / low |
| TL-25 | `tools-virtual-env/VirtualEnvToolsAutoConfiguration`, `OnBehalfJwksController` | hexagonal | Auto-config and a `@RestController` in a `core/` tool module. | Wiring to a starter. | S / low |
| TL-26 | `builtin/ToolPaths:68–78`, `code/CodeExecuteToolFactory:182–192`, `McpTargetEndpoints:141–147`; `BaseDirs`, `DocBaseDirs`, `VectorIngestFileTool:151–157` | duplication | Three copies of `~`/`$HOME` expansion, three of root resolution; `VectorIngestFileTool.Factory` ignores the `WorkspaceProvider`. | `HomePaths.expand()` in `mc-common`; `VectorIngestFileTool.Factory extends FileRootedToolFactory`. | S / low |
| TL-27 | `web/WebToolsHttpClient`, `browser/PlaywrightHolder`, `document/SharedDocumentReader`, `code/CodeExecuteToolFactory:76`, `PostgresVectorStores:22–27`, `DefaultVectorStores:66` | simplicity | Process-wide singletons because `ToolFactory` has no close hook. | `default void close()` on `ToolFactory`/`MultiToolProvider`; hold as factory fields. | M / low |
| TL-28 | `document_file_read`, `read_document`, `document_outline`, `document_sections`, `read_document_section`, `grep_document` | simplicity | Overlapping tools, identical 230-character description prefix; `ReadDocumentTool` vs `DocumentOutlineTool` naming. | `document_outline`, `document_read`, `document_grep`, `document_write`; `Document*Tool` class names. | M / med |
| TL-29 | `mail-core/MailStore:7–26`, `MailboxConnectionProvider:17–19`, `MailSender:9–11`, `MailText` (double javadoc), `McpGateway:13`, `McpTarget:13,51`, `McpSessionRegistry:23–31`, `GlobTool:23–44`, `WebToolsHttpClient:7–28` | javadoc | Changelog, "§5.2 of the concept moves it … into mc-mail-core" (it is already there), "v0 … v1+", a block that repeats `description()`. | Present tense, one paragraph, no concept pointers. | S / low |
| TL-30 | `MailListViews.standard(…)`, `MailStore.list(folderId, skip, limit, boolean, String)`, `DocumentParser.parseString` 4-arg, `VectorStores.indexLocation(ns)`/`systemDatabase()`, TL-09 ctors | dead code | Zero or one caller. | Delete before they become API. | S / low |

### 4.4 agents: server, client, builder, springstarter, demo (SV)

Good: server and client import ports from `-core`; the feature SPI
(`RuntimeFeature`, `ConfigurableFeature`, `FeatureContext`) is clean; the
starters are thin; `Page` + `Component` works where it is used. Main smell:
fat UI controllers that are the application layer.

| ID | Where | Criterion | Problem | Proposal | Effort / risk |
|---|---|---|---|---|---|
| SV-01 | `chat-ui-rest/.../controller/ChatUiController.java` (2494, 15 ctor deps) | SRP, hexagonal | Chat shell, settings dialog, directory picker (8 endpoints :323–449), tool and sub-agent pickers (:919–1160), session CRUD, the live SSE turn rendering (`runTurnStream` :1688–1987 with `LiveThinking`/`LiveTask` state machines :1987–2140), the chat's domain defaults (`DEFAULT_TOOLS`, `CHAT_SYSTEM_PROMPT` :651–677). | `ChatShellController`, `ChatSettingsController`, `WorkingDirPickerController`, `ChatToolPickerController`, `ChatTurnController`, a non-web `LiveTurnRenderer`; the override rules (`applySettings` :237–303, `replaceTools` :1196–1244, `pickTools` :710) into a `ChatAgentOverridePolicy`. | L / med |
| SV-02 | `api-rest/service/SessionFileService.ingest` (:264, 132 lines) vs `feature-file-upload/AttachSupport.attachStored` (:97) | duplication | Attach-and-ingest implemented twice and already diverging (builder reads `vectorStoreEmbeddingConfig`, REST hard-codes `"embeddings"` :300). | One `AttachmentIngestion` in the file-upload feature; the REST service delegates. See Q-17. | M / med |
| SV-03 | `admin-ui-rest/.../controller/VectorStoreUiController.java` (1152) | SRP, consistency | 89 `Ui*` constructions, no Page or Component; `list(tab)` (:92–231) builds four tables inline; `storeDetail` has four overloads (:772–862); bypasses `VectorStoreService` (:57–65). | `VectorStoreOverviewPage`, `TemplateFormComponent`, `IndexFormComponent`, `StoreDetailPage`, `StoredFilesComponent`, `FileUsageDialogComponent`; everything through `VectorStoreService`. | L / low |
| SV-04 | `mc-agent-cli/CliRunner.java` (1456): `run` :57–265 (208 lines), `streamAgentResponse` :519–715, `printToolCallHistory` :972–1106 | SRP | An if-chain over 18 slash commands; event rendering with array-boxed mutable flags. | `CliSession`, `SlashCommand` + `CommandRegistry`, `StreamRenderer`, `HistoryPrinter`, `CancelWatcher`. | M / low |
| SV-05 | `api-rest/controller/AgentApiController.java` (663) | SRP | Agents CRUD, session lifecycle, working directories, three SSE endpoints with hand-rolled framing (`streamTurn` :348, `sendFrame` :383, `endStream` :392), user stream, approvals. | `AgentDefinitionApiController`, `SessionApiController`, `SessionStreamApiController` (+ `SseFrameWriter` shared with `TranscriptionApiController.send` :214 and `ResponsesController.stream` :83), `ApprovalApiController`. | M / low |
| SV-06 | `ProfileUiController:453`, `UserToolUiController:294`, `NamespaceUiController:295`, `ConnectionUiController:273`, `LlmPriceUiController:203`, `UserMemoryUiController:115`, `ToolCatalogUiController:228`, `ChatUiController:1245` | duplication | Identical `dialog(...)`; 17 `"sui-dialogs"` literals. | `DialogPatch.open(id, title, body)` / `close(id)` in a shared `ui` package. | S / low |
| SV-07 | `userId(OidcUser)` in 10 controllers (`TaskMonitorUiController:67`, `ProfileUiController:414`, `ChatUiController:820`, …); ownership checks in `ChatUiController.ownedSession:535`, `ChatFilesUiController.requireOwned:177`, `ChatVoiceUiController.ownsSession:122`, `StreamController.owned:179`, `SessionFilesApiController.owned:145`, `AgentApiController.owned:660` | duplication | `SessionOwnership.userIdOf` and `agentrest/auth/SessionAccess` exist and are not used. | `@CurrentUser UserId` argument resolver for the UI controllers; `SessionAccess.requireOwned` everywhere. | S / low |
| SV-08 | `admin-ui-rest/.../LlmConfigUiController.java:296–320, 602–673`; `fieldGroups` :169–246; `LlmConfigFormComponent:140–620` | hexagonal, SRP | Form ↔ domain mapping and secret-handling rules in the controller; the form is split over two classes via `public static` factories. | `LlmConfigFormMapper`; `LlmConfigFormComponent` owns its groups; controller routes. | M / low |
| SV-09 | `LlmConfigUiController:58–59`, `LlmConfigFormComponent` (imports `llm.adapter.lmstudio.LmStudioModelCatalog`, `llm.adapter.ProviderModelCatalog`), `LlmConfigTestService:35` (`RoutingLlmChatService`), `LlmPriceUiController` (`llm.service.LlmPriceService`) | hexagonal | The only adapter imports in the server scope. | `ModelCatalog` port in `mc-llm-gateway-core`; `LlmChat` instead of the routing class. | S / low |
| SV-10 | `mc-agent-security/pom.xml` → `mc-agent-api-rest` | hexagonal | The security module depends on a REST module, only for `CurrentUserResolver`. | Move `CurrentUserResolver`/`CurrentUser` to `mc-user-core` or `mc-agent-web-auth`. | S / low |
| SV-11 | `chat-ui-rest/.../controller/FormBody.java:7` | consistency | Lives in the chat module's `controller` package, used by 12 admin-ui files. | Shared `ui.form` package (with SV-06 and `VersionedForms`). | S / low |
| SV-12 | `ProfileUiController:80,84` (injects `UserToolUiController`, `UserMemoryUiController`), `AgentUiController:65` (injects `MemoryAdminUiController`) | hexagonal | Controller-to-controller injection to render tabs. | `UserToolRowsComponent`, `MemoryTabComponent`. | S / low |
| SV-13 | `builder/AgentRuntimeBuilder.registerCore` (:438–560, 122 lines); typed shortcuts for feature-owned keys :335–371 | SRP | Wires 15 services with 10-argument constructors although `CoreFeature` exists; builder exposes `tavilyApiKey`, `codeExecutionRuntime`, `vectorStoreBackend` for optional features. | Turn-loop wiring into `CoreFeature` (or `TurnLoopFeature`); `feature(ToolsFeature.class).tavilyApiKey(...)`. See Q-18. | M / med |
| SV-14 | `starter-runtime/AgentRuntimeAutoConfiguration.java:113` (85 lines), :258–297 (38 re-exported beans, casts to `RoutingLlmChatService` :289, `LocalTaskQueue` :293) | simplicity | Property plumbing by hand; a new feature bean needs a starter edit. | `@ConfigurationProperties` records per feature; export whatever `RuntimeBeans` holds. See Q-19. | M / med |
| SV-15 | `adminui/InitialDataLoader` (73), `cli/InitialDataLoader` (206, own parsing and diff), `NamespaceSeeder`, `NamespaceSeeding`, `BundledSeeds`, `MigrationService` | duplication | Five overlapping seeding classes; the CLI does not use `BundledSeeds` nor `common/mc-initial-data`. | `BundledSeeds` + `NamespaceSeeder` next to `mc-initial-data`; CLI reuses them with a `SeedConflictPolicy`. See Q-20. | M / low |
| SV-16 | `adminui/service/MigrationService.java:67` (431) | SRP | `EntityType` enum plus `pendingXxx`/`applyXxx`/`bundledXxx` per kind (:149–356), JSON diffing (:391–430), secret masking (:359–390). | `SeedMigration` strategy per kind, `JsonFieldDiff`, orchestration only. | M / low |
| SV-17 | `adminui/service/TaskMonitor.java` | SRP | Its javadoc says "three parts, one class"; two telescoping ctors (:146, :153). | `TaskSnapshotPublisher`, `TaskOwnership`, `TaskMonitor` facade. | S / low |
| SV-18 | `adminui/ui/page/ProfilePage.java:115–176` (5 ctors, 17 fields), `public static` factories :237–553; same in `NamespacesPage:142–213`, `AdminLayout:84–118` | simplicity | A parameter bag and a utility bag at once. | `ProfileModel` record + `ProfilePage(ProfileModel)`; `ApiTokenFormComponent`, `NamespaceInviteFormComponent`, `EnvironmentFormComponent`. | M / low |
| SV-19 | `chat-ui-rest/.../page/ChatPage.java:44`, `adminui/ui/AdminPage.java` | consistency | Admin pages extend `AdminPage`; `ChatPage`, `NamespacesPage`, `DungeonPage` do not; `AdminPage` javadoc cites chat methods. | One base (or none) with the same contract in both modules. | S / low |
| SV-20 | `registry-admin-ui-rest/RegistryBrowseView.java:45` (+4 `*View`); `chatui/ui/component/MarkdownText`, `ModelLabel`, `Thoughts`, `ToolCallHistory` | naming | `*View` here, `*Component`/`*Page` everywhere else; some without suffix. | `RegistryBrowseComponent`, …; `ThoughtsComponent`, `ToolCallHistoryComponent`. | S / low |
| SV-21 | `cli/agentclient/RemoteAgentClient.decodeFrame` (:177–283) vs `agentrest/dto/StreamEventFrame.from` (:41–120) | duplication | Hand-decoding of what the other side hand-encodes; both switch over every `StreamEvent` kind. | `StreamEventFrameConverter` in a small `mc-agent-api-dto` module. See Q-17. | S / low |
| SV-22 | `ChatUiController.sendPatch` (:2385), orphaned javadoc at `ChatUiController:118–124, 611–632, 682–692`; `AdminSameUrlFilter:41` (`LEGACY_SECTIONS`) | dead code | No caller; comments whose methods moved; a migration the filter waits for. | Delete; finish the migration. See Q-21. | S / low |
| SV-23 | `AgentFormComponent.render` (:73, 138 lines), `ToolFormComponent:113` (76-line `if`), `StreamEventFrame:42` (78-line switch) | simplicity | Long render methods. | One private method per field group. | S / low |
| SV-24 | `BrandingProperties:11` (58-line block), `StreamEvents:15` (36), `SecurityConfig:54` (35), `NamespaceOnboarding:20` (32); `AgentClient:4–9` ("no longer extends…"), `ChatUiController:644–650` ("It used to live on…"), `VectorStoreUiController:196–209` | javadoc | History and essays; 40–46 % comment share in `StreamBus`, `MessageListComponent`, `ChatFormComponent`, `ChatPage`. | Contract in one sentence; rationale to `agents/doc/`. | S / low |
| SV-25 | `ChatUiController:66–77`, `VectorStoreUiController`, `AgentRuntimeBuilder` | style | Inline FQNs. | Imports. | S / low |
| SV-26 | `chatui/.../UploadLimitUiAdvice` vs `agentrest/.../UploadLimitAdvice` | consistency | Two `@ControllerAdvice`s for one exception, ordered HIGHEST/LOWEST, one delegating by URL sniffing. | One advice that picks JSON vs toast by `Accept`. | S / low |
| SV-27 | `feature-file-upload/FileUploadFeature.java:31` | consistency | The only feature implementing `RuntimeFeature` directly; others extend `ConfigurableFeature` even without settings. | One shape. | S / low |
| SV-28 | `starter-namespace/ScopeBindingFilter.java:46` (290 lines, 3 ctors, static path-rule lists :77, :87) | simplicity | Policy and filter in one. | `NamespaceRequestPolicy` + the filter. | S / low |

### 4.5 workflow (WF)

Good: `mc-workflow` is framework-free, the persistence ports are clean, the
snapshot/resume design is thoughtful. Main smell: the step tree has five
consumers and each walks it by hand; adding a step type touches twelve files.

| ID | Where | Criterion | Problem | Proposal | Effort / risk |
|---|---|---|---|---|---|
| WF-01 | `workflow/README.md:17,74`, `domain/IfData:27`, `VariableAssignment`, `DefaultWorkflowContextFactory:30`, `CompositeExpressionResolver:18`, `ExpressionResolver`, `PumlWorkflowParser:60`, `AssignDslStepBuilder:19`, `WorkflowSamples:92` | correctness, docs | `spel:` advertised; no SpEL resolver exists, a `spel:` condition evaluates to the literal string. | Replace the examples with `mini:`/`javascript:`; strip SpEL from the README. See Q-22. | S / low |
| WF-02 | `admin-rest/.../WorkflowAdminUiController.java` (2101) | SRP | CRUD, step dialogs, add-step, run form, a server-side file chooser (:648–734), SSE live run with inner `LiveRun`/`LiveRunView` (:736–1054), run-report rendering (:1212–1694), step inspection, a parameter-schema editor (:1746–2053). | `WorkflowListController`, `WorkflowStepController`, `WorkflowParamController`, `WorkflowRunController`, `LiveRunStreamController` + `LiveRunRegistry` + `LiveRunView`, `RunReportRenderer`, `FileChooserDialog`, `AdminDialogs`. | L / med |
| WF-03 | `dsl-puml/PumlWorkflowParser.java:184–371`, `applyTrailingNote` :470–524, `applyPropertyToStep` :556–572 | SRP | A 188-line `while` with 16 `if` branches mixing current syntax, three legacy `#directive` forms, legacy `sub/end sub`, note-driven for-each; per-type property knowledge that the `DslStepBuilder` SPI owns. | `PumlTokenizer`, one parser per construct (`NoteBlockParser`, `ActionBlockParser`, `IfParser`, `ForkParser`, `PartitionParser`), `Halt/Jump/CallDslStepBuilder`, `LegacyDirectiveParser` or delete. See Q-23. | L / med |
| WF-04 | `dsl-puml/PumlWorkflowSerializer.java:252–296` | simplicity | `Class.forName("…CodeData")` + reflective getters for a type in a module it depends on. | Pattern `switch` as at line 84; `DslStepBuilder.properties(StepData)`. | M / low |
| WF-05 | `domain/HttpCallData:3`, `scripting/ScriptExecutor:3–5`, `scripting/ScriptExpressionResolver:3` | hexagonal | `domain` imports `execution` (for a `{@link}`); `scripting` ↔ `execution` cycle. | `{@code}`; `CodeStepException` → `scripting.ScriptExecutionException`. | S / low |
| WF-06 | `execution/WorkflowContext:32` (`Object scriptExecutor`), `WorkflowContextFactory:21–28`, `CodeStep:37–41`, `getOrCreate` in all four `*WorkflowConfigurer` | dead code | `Object` typing and `instanceof` to avoid modules that no longer exist. | Type the field `ScriptExecutor`; delete the four copies, `CodeStep.buildFallbackProvider`, `ScriptEngineProvider`. | S / low |
| WF-07 | `mc-workflow-code-{beanshell,groovy,javascript,jython}` | duplication, docs | Four copy-pasted configurers differing only in aliases; JSR-223 engines are auto-registered anyway (`DefaultWorkflowContextFactory:47–51`); README says Nashorn, code registers GraalJS; `JythonWorkflowConfigurer:27–39` claims TCCL use and does not. | Abstract `ScriptLanguageConfigurer(aliases, factory)`, three-line subclasses; fix README. | S / low |
| WF-08 | `execution/HasNext`, `BaseStepContainerInstance:159` | dead code | No implementor; the `jumpTo(hasNext.getNext())` branch is dead. | Delete. | S / low |
| WF-09 | `execution/BaseStepInstance:65–105` vs `BaseStepContainerInstance:305–358` | duplication | `resolveExpression`, `getExpressionResolver`, `getStringVariableReplacer`, `logDebug` duplicated with different semantics (container short-circuits `${…}`, leaf has that commented out :67–69). | Container extends leaf base, or a `StepExpressionEvaluator`; delete the commented code. | M / med |
| WF-10 | `execution/CallWorkflowStep.java:13–25` | correctness | `init` mutates the registry's shared `WorkflowData` and appends to `config.getSteps()` on every instantiation; a `CallWorkflowData` run twice runs the callee twice. | Keep the callee as a field; `getSteps()` returns `List.of(callee)`; never touch `config`. | S / med |
| WF-11 | `execution/WorkflowExecutorService:89–92, 128–133, 142–152` | simplicity | `env` assigned twice; two identical `catch` branches; `@Data` setters beside `withEnvironment`. | Remove the first assignment; collapse; explicit getters. | S / low |
| WF-12 | `persist/WorkflowInstanceSnapshots.java` (321, all static) | SRP, naming | Capture, restore and `pendingHalt` dispatch on `instanceof ForEachStep / IfStep / Base…` (:77–106, :255–305). | `WorkflowInstanceSnapshotter` + `WorkflowInstanceRestorer`; a `Resumable` interface on the instances. | M / med |
| WF-13 | `edit/StepNames:107–125`, `edit/WorkflowMutator:225–247`, `persist/DefinitionFingerprint:47–62`, `WorkflowInstanceSnapshots:179–194`, `diagram/WorkflowDiagramBuilder:691–712`, `admin/ui/WorkflowUiBuilder:142–205`, `PumlWorkflowSerializer:109–138`, `swing/IfEditorPanel` | duplication | Nine hand-rolled walks over `conditions[].thenBlock + elseBlock + container.steps`. | One `StepTree` walker (or `StepData#childLists()`). | M / med |
| WF-14 | `admin/ui/StepMapper:40–79`, `diagram/app/StepFactory:30–88`, `swing/AddStepDialog:15–77`, `execution/StepInstanceFactory:63–74` | duplication | Three type-id → constructor tables and three label lists kept in sync by hand (`FormStepData` is in one of them). | `StepTypeRegistry` (id, label, `Supplier<StepData>`, `Supplier<StepInstance>`), ServiceLoader-extensible. | M / low |
| WF-15 | `diagram/BuildContext:117–166` vs `edit/StepNames:46–88`; `WorkflowDiagramBuilder:38–43` | duplication, hexagonal | Two auto-naming schemes; the diagram *renderer* renames domain objects. | Builder read-only; delete `reserveStepName`, `seedAutoSeqFrom`, `bumpAutoSeqToMatch`, `collectStepNames`. | M / low |
| WF-16 | `diagram/WorkflowDiagramBuilder` (713) + `BuildContext` (461); `activeFrameAbsoluteOrigin` :196–213 unused | SRP | Dispatch, layout maths, else-route waypoints, override application, jump wiring, extent sweep; context = ids, naming, cursor, LCA, a 97-line `runInContainerAt`. | `DiagramLayoutCursor`, `DiagramNodeEmitter`, `StepShapeMapper`, `LayoutOverrideApplier`, `JumpEdgeWirer`. | M / med |
| WF-17 | `admin/run/WorkflowRunService` (679); `SpiWorkflowContextFactory.create()` per run (:207, :238) | SRP, hexagonal | Runs, resumes, persistence decision, trace tree, own `ObjectMapper`, scope snapshots to depth 3, log mapping; a ServiceLoader scan and `new ScriptEngineManager()` on every click. | `WorkflowRunner`, `RunTraceRecorder`, `RunTraceJson`, `ScopeSnapshotter`; inject the factory. | M / med |
| WF-18 | `persistence-pg/PgWorkflowInstanceRepository:6`, `PgWorkflowRepositoryFactory:4–5`, `persistence/memory/InMemoryWorkflowRepositoryFactory:3` | hexagonal | Adapters import sibling adapters (`persistence.file.SnapshotSerializer`, `FileWorkflowDataRepository.directory`); package `persist` (core) vs `persistence` (modules). | `SnapshotSerializer` → `persistence.json`; one package name. | S / low |
| WF-19 | `jackson/JsonWorkflowConfigurer:26–52`; `JsonExpressionResolver.replaceVars` :82–94 | consistency | Javadoc says null-safe, code is unguarded; a "Json" configurer silently installs a registry and a mapper; a second variable replacer without path support. | Guard or document; split; reuse `StringVariableReplacer`. | S / low |
| WF-20 | `IfData` / `IfStep` / `StepInstance` / `WorkflowInstance` / `WorkflowInstanceSnapshot` | naming | Three vocabularies for two concepts: "Step" is the executor, "Data" the step; the interfaces (`StepData`, `StepInstance`) and the workflow level (`WorkflowData`, `WorkflowInstance`) already use Data : Instance. | **Decided: Definition : Instance**, the vocabulary the agents area already uses (`AgentDefinition`). `StepData` → `StepDefinition`, `IfData` → `IfStepDefinition`, `IfStep` → `IfStepInstance`, `BaseStepData` → `AbstractStepDefinition`, `BaseStepInstance` → `AbstractStepInstance`, `WorkflowData` → `WorkflowDefinition`, `WorkflowDataRepository` → `WorkflowDefinitionRepository`. No name changes meaning, so old code fails to compile instead of binding to the wrong class. Two prerequisites, each its own PR: (1) the JSON type id is the fully qualified class name today (`@JsonTypeInfo(use = Id.CLASS)`, `WorkflowObjectMapperFactory:69`), so every stored workflow would become unreadable; switch to `Id.NAME` with short aliases (`workflow`, `block`, `if`, `foreach`, `halt`, `jumpTo`, `assignVariables`, `callWorkflow`, `code`, `httpCall`, `form`), kept entirely in `mc-workflow-jackson`: the mixin uses `Id.NAME` + `As.EXISTING_PROPERTY` on the `type` field and a `StepTypeIdResolver` that asks the core `StepTypeRegistry` (WF-14) for id ↔ definition class; a `JsonNode` pre-pass maps legacy `@class` values to aliases so pre-1.0 files still load and are rewritten on save; a custom step then registers one `StepType` in its `WorkflowConfigurer` instead of relying on its class name. (2) `BaseStepData.getType()` derives the id from the class name (:19); the id becomes the `StepType` alias. | M / high |
| WF-21 | `ai.mindconnect.workflow.api.*` in `CodeData:19`, `HttpCallData:20`, `WorkflowConfigurer:10`, `JsonWorkflowConfigurer:34`, `SpiWorkflowContextFactory:17`; `mc-workflow-api` in `WorkflowObjectMapperFactory:15`; `PumlWorkflowParser:33,39`; "Etappe 1a/1b", "Phase 3a" in `WorkflowDiagramBuilder:29–35`, `LayoutStore:16`; README essays in `WorkflowConfigurer:20–34`, `WorkflowMutator:12–44` | javadoc | Stale module names, German phase labels, essays. | One sweep; essays to `workflow/doc/`. | S / low |
| WF-22 | `ScriptEngineProvider`, `StringVariableReplacer.resolveVars`, `JacksonJsonMapper` InputStream/Path/`getMapper`, `SpiWorkflowContextFactory.discoverConfigurers`, `VariableScope.VariableHistory` (grows per assignment, never read: a leak in long loops), `WorkflowContext.baseDir`, `WorkflowContext.scriptOutputWriter`, `WorkflowData.uid`, `ResourceUtil.writeString`, `WorkflowAdminUiController.ownVars:1628`, `LiveRun` 4/5-arg ctors :850–857, `PumlWorkflowParser.Cursor.nextNonEmpty` | dead code | Unused members. | Delete. | S / low |
| WF-23 | `scripting/ScriptExecutor:68–70, 106–116`; `ForEachStep.executeParallel` :127 | correctness | One cached `ScriptEngine` per language whose `ENGINE_SCOPE` is mutated by `eval`: not thread-safe under parallel for-each, which also creates an unbounded `newCachedThreadPool` per loop. | `engine.eval(expr, bindings)` as `CodeStep` does; a virtual-thread executor. | S / med |
| WF-24 | `StepInstanceFactory`, `StepMapper` ×3, `StepFormBuilder`, `WorkflowUiBuilder.iconFor/detailFor`, `WorkflowDiagramBuilder.shapeFor/labelFor`, `EditorPanelFactory` + panel, `AddStepDialog`, `StepFactory`, `PumlWorkflowSerializer`, a `DslStepBuilder`, `StepBlockRenderer`; `BaseStepData.getType()` derives ids from class names (`httpcall` vs DSL `http`, alias in `DslStepBuilderRegistry:34–39`) | consistency | Adding a step type touches twelve files. | One `StepType` descriptor in the `StepTypeRegistry` of WF-14. | L / med |
| WF-25 | `swing-ui/WorkflowDesignerFrame.java` (758) | SRP | Frame, file IO, `Preferences`, run executor, header/params table, key bindings; still edits deprecated `WorkflowData.paramDefaults`. | `WorkflowFileActions`, `WorkflowRunAction`, `WorkflowHeaderPanel`, `DesignerKeyBindings`; schema defaults. See Q-25. | M / low |
| WF-26 | `WorkflowAdminUiController:94–97, 111, 617, 831` | hexagonal | Controller news up `RunStore`, `StepFormBuilder`, `WorkflowMutator`; per-process state (`lastRunInputs`, `liveRuns`); an executor never shut down. | Inject; a bean with `@PreDestroy`. | S / low |
| WF-27 | `execution/HaltException`, `StepExecutionException` (`@Data @Builder` mutable exceptions), `HttpStepException`, `MultipleExceptionsException` | naming | Mutable exceptions; names off the pattern. | Immutable; `HttpCallException`, `ParallelExecutionException`. | S / low |
| WF-28 | `execution/WorkflowDefinitionRegistry` vs `persistence.port.WorkflowDataRepository` | consistency | Two ports answer "workflow by id" with different verbs; the only registry reads classpath JSON, so an admin-saved workflow cannot be called; `putWorkflowDefinition` unused. | `RepositoryWorkflowDefinitionRegistry` (read-only adapter over the repository); drop `put`. See Q-26. | S / med |
| WF-29 | `execution/JsonMapper` | naming | Clashes with Jackson's `JsonMapper`. | `JsonConverter`. | S / low |
| WF-30 | `WorkflowAdminService`, `WorkflowRunService`, `WorkflowExecutorService` | naming | "Service" means facade, runner and executor. | `WorkflowAdminFacade`, `WorkflowRunner`, `WorkflowExecutor`. | S / low |

### 4.6 taskqueue (TQ) and common (CM)

Good: the task-queue core is the strongest code in the repo: clean ports,
records own their transitions, the two stores differ only in locking,
SLF4J-only. `mc-jdbc` and `mc-file-repo` match it. Main smell: the two demos
are one app copied, the JDBC adapter re-implements `mc-jdbc`, and two
`common/` modules are prototypes.

| ID | Where | Criterion | Problem | Proposal | Effort / risk |
|---|---|---|---|---|---|
| TQ-01 | `mc-taskqueue-demo-app` vs `mc-taskqueue-cluster-demo` | duplication | `ScrapePageWorker`, `CountdownWorker`, `SpaForwardingFilter` byte-identical; `DemoTaskTypes`↔`TaskTypes`, `NewTaskController`, `TaskStreamController`, `TaskBoardController`↔`TasksController`, `TaskBoardRenderer`↔`TasksRenderer` (54 of 433 lines differ), `DemoLayoutAdvice`↔`ClusterLayoutAdvice`; form helper bag (`DemoTaskTypes:146–178`) repeated in `ScrapePageWorker:191`. | `mc-taskqueue-demo-ui` library; the cluster demo adds only `Cluster*`; a `FormValues` value object. | M / low |
| TQ-02 | `mc-task-queue/local/LocalTaskQueue.java` (590) | SRP | Dispatcher loop (:306–346), maintenance loop (:262–302), attempt lifecycle (:363–450), advisor chain (:452–460), listener fan-out (:462–471), inner `Context` (:514–589). | `TaskDispatcher`, `QueueMaintenance` (`LeaseKeeper` + `RetentionSweep`), `TaskAttempt`, `AdvisorChain`, `TaskListeners`, `LocalTaskContext`. | L / med |
| TQ-03 | `mc-task-queue-jdbc/JdbcTaskStore.java` (721) | SRP | DDL (:66–84), two hand-maintained 20-column bindings (`update` :505–528 vs `bindRecord` :530–551) + `read` (:560–583), connection/tx/retry plumbing (:598–690), lease recovery (:416–446), a tree purge (:361–392) duplicating `InMemoryTaskStore.purgeTerminal` (:81–123). | `TaskRowMapper`, `mc-jdbc` `Sql`, `LeaseRecovery`, `TaskTree.purgeable` in core used by both stores. | L / med |
| TQ-04 | `jdbc/Json`, second `ObjectMapper` (`JdbcChannelStore:16–20`), three `initSchema` copies, two `withConnection` copies | duplication | The JDBC adapter re-implements `common/mc-jdbc`. | Depend on `mc-jdbc`. See Q-27. | M / low |
| TQ-05 | `JdbcChannelStore:36–40, 71, 92, 103, 115, 128`; `JdbcTaskStore:82, 633, 689`; `JdbcSharedStateStore:117` | consistency | `IllegalStateException` vs `TaskQueueException`; `SQLException` cause discarded. | One exception type with the cause. | S / low |
| TQ-06 | `mc-task-queue/SharedLockStore.java:1–25` | speculative | A port justified by cross-process exclusion with only an in-memory adapter. | `JdbcSharedLockStore` or move the port out. See Q-28. | M / S |
| TQ-07 | `mc-task-queue-jdbc/.../schema-schedule-postgres.sql` | dead code | DDL with no `JdbcScheduleStore`. | Implement (five methods) or delete. | S / M |
| TQ-08 | `Channel:15,21`, `ChannelRegistry:11`, `TaskListener:8`, `TaskOutcome:5`, `TaskQueue:12`, `LocalTaskQueue:41` | javadoc | "concept 1/3/11/12". | The sentence the concept stands for. | S |
| TQ-09 | `LocalTaskQueue:101–121` | javadoc | Three blocks stacked on `withRetention`; two belong to `addListener` and `withRetryPolicy`. | Move. | S |
| TQ-10 | `LocalTaskQueue` (:64, 325, 360, 416, 462, 536–583), `InMemoryTaskStore` (:81, 110, 241, 250), `JdbcTaskStore` (:257, 266, 395), `TaskStore` port (:111, 125, 143, 149) | style | Inline FQNs in the showcase module. | Imports. | S |
| TQ-11 | `demo-app/ui/TaskBoardRenderer`, `cluster-demo/ui/TasksRenderer` | SRP, naming | A "Renderer" holding selection state (:70–76), subscribing in `@PostConstruct` (:85–93), busy-waiting with `Thread.sleep` (:121–132). | `BoardSelection`, `EventFeed`, `ChannelInspector`, a stateless renderer. | M / low |
| TQ-12 | `channel/ChannelRegistry.withIdleEviction:116–129` | correctness | Starts a sweeper with no way to stop it; `PersistentChannels` is `AutoCloseable`. | `AutoCloseable` or return the handle. | S |
| TQ-13 | `channel/memory/InMemoryChannelStore.lastSeq:73–76` | correctness | `lastSeq` derived from surviving events: a purge that drops everything resets the seq space to 0; `ChannelStore:21–32` promises it never restarts; the JDBC store keeps the head. | Keep `lastSeq` per channel. | S |
| TQ-14 | `schedule/TaskSchedule:50–90` | simplicity | Eight withers each re-list twelve components. | Private `copy(...)` or fewer withers. | S |
| TQ-15 | `cluster-demo/ClusterDashboardRenderer:50–51` (`JdbcTaskStore`, `JdbcChannelStore` for `countByStatus`), `WorkerEventPublisher`, `MasterSubmitNudger`, `ClusterNudgeController` (`LocalTaskQueue` for `nudge()`/`addAdvisor`) | hexagonal | Demo wired to adapters. | `countByStatus` on `TaskStore`; `nudge()` on `TaskQueue` or a `Nudgeable`. | S |
| CM-01 | `mc-script-mini/MiniScriptEngine.java` (874): essay :9–69, `Lexer` :135–265, AST :267–301, `Parser` :307–555, `Evaluator` :557–873 with reflection :787–842 and built-ins :753–785; `MiniScriptDemo.main` in main sources | SRP | A whole language in one file. | `MiniScriptLexer`, `MiniScriptParser`, `MiniScriptAst` (sealed `Node`), `MiniScriptEvaluator`, `ReflectiveInvoker`, `BuiltInFunctions`; essay to README; demo to a test. | M / low |
| CM-02 | `mc-common/util/AtomicFiles` = `mc-file-repo/FileWrites` | duplication | Identical bodies; 10 vs 1 callers. | One `AtomicFileWriter`. | S |
| CM-03 | `mc-webscraper`: `WebScraperMain:28` (`/Users/dbe/…`), `RailsWebScraperConfig:22–28` + `resources/*.txt` (a customer's URLs), `ApplicationConfig` (Pinecone/OpenAI env vars), three `main()`s, POM ships `logback-classic` **and** `slf4j-simple` plus Playwright for every consumer | hygiene | Not release-ready. | Delete `Rails*`, `ApplicationConfig`, the mains and resources; `slf4j-api`; Playwright `optional`. See Q-29. | S / low |
| CM-04 | `mc-webscraper`: `IWebScraper`, `ISingleWebPageScraper`; `CssSelector extends UrlPattern`; url predicates in `AbstractWebScraper:27–40`, `JsoupSingleWebPageScraper:131–150`, `PlaywrightSingleWebPageScraper:123–139`; `executeScrape` ~95 lines twice; `clone` hand-copies 15 fields | design, correctness | Two bugs: `WebScraperResults.addFailed` adds to `pages` (:17–19); `WebScraperOptions.clone` passes `removeHtml` twice instead of `removeNewLine` (:65–66). | `WebScraper` + `PageScraper`, `LinkFilter`, records, one template with jsoup/Playwright fetch strategies. | M / low |
| CM-05 | `common/mc-file-manager` | simplicity | No module depends on it; a `@SpringBootApplication` whose `@Service` throws web `ResponseStatusException` (:36–40); `FileManagerWithLockService` is a commented-out `//@Service`; `PathSanitizer.main()`; `SortDirection` duplicates `SortOrder`; name collides with `mc-file-repo`. | Remove before 1.0. See Q-30. | S / low |
| CM-06 | `mc-common/LoggingContext` (agent MDC keys), `Cancellation:5–16` (narrates `LlmGateway`), `McEnv` (loads `mc.env`, prints to `System.err` :35; second copy in `agents/demo`) | hexagonal | The base library knows about agents and ships a dev convenience. | `LoggingContext` → `mc-agent-domain`; rewrite `Cancellation` as a contract; `McEnv` → demos. | S |
| CM-07 | `mc-common/Page` (1-based) vs `PageRequest` (0-based) | consistency | Two paging conventions in one package. | Pick one; a test that `Page.of(all, req.page(), req.size())` agrees with `req.offset()`. | S |
| CM-08 | `mc-initial-data/InitialDataInstaller:17, 33–60` | naming | An interface with a public static `Logger` and a 30-line default template method; the module wraps Spring's resource resolver. | `InitialDataSeeder` over an `InitialDataTarget` port. | S |
| CM-09 | `mc-pathaccessor/PathAccessor:4–9`, `MapPathAccessor:4–6`; loop in `MapPathAccessor:17–30`, `RecordPathAccessor:20–33`, `BeanPathAccessor:33–50`; `PathSegment` public fields | duplication | Javadoc explains a consumer in another area; the segment walk is written three times. | `PathWalker` + a `PropertyReader` per type; `PathSegment` as a record. | S |
| CM-10 | `mc-jdbc/DocumentTable.java` (464): DDL :88–124, SQL assembly :337–397, CRUD, `Builder` :399–464 | SRP | Table definition, repository and builder in one. | `TableDefinition` (columns, indexes, `ddl()`, `upsertSql()`); `DocumentTable` as the repository over it. | M / low |
| CM-11 | `mc-common/util/encryption/EncryptionHelper` | naming, SRP | AES primitives that `throws Exception` (:129–136), `plain:`/`enc:` tag resolution (:35–70), map batch ops (:72–127). | `SecretCipher` + `TaggedSecret`. | S / M |
| CM-12 | `Cancellation:5–16`, `SharedLockStore:1–25`, `ScheduleStore:1–15`, `MiniScriptEngine:9–69`, `TaskStore.finish:78–96`; none on `Page`, `PageRequest`, `SortOrder`, `DomainException`, `IWebScraper`, `WebScraperListener` | javadoc | History and rationale where contract is missing. | One paragraph of contract per type; rationale to the docs site. | S |
| CM-13 | `mc-file-repo/FileRepo:48` | consistency | Cleanup virtual thread with no `close()`. | `AutoCloseable`. | S |

## 5. Renames

Consolidated from the six area reviews, grouped by the pattern the class
actually implements. Each rename is a separate mechanical commit with no
other change; IDE refactoring, then build and test the area.

**Registry / Catalog** (holds many, answers by key)
`SessionChannels` → `SessionChannelRegistry`, `UserChannels` → `UserChannelRegistry`,
`ActiveStreams` → `ActiveStreamRegistry`, `SessionStreams` → `SessionStreamRegistry`,
`TranscriptionChannels` → `TranscriptionChannelRegistry`, `PersistentChannels` → `PersistentChannelRegistry`,
`BackgroundProcesses` → `BackgroundProcessRegistry`, `MailAccounts` → `MailboxRegistry`,
`CalendarAccounts` → `CalendarAccountRegistry`, `ToolBundles` → `ToolBundleCatalog`,
`InlineAgentTools` → `DelegationToolCatalog`, `CodeLanguages` → `CodeLanguageCatalog`,
`VectorStores` → `VectorStoreCatalog`, `DefaultVectorStores` → `ConfiguredVectorStoreCatalog`,
`BundledSeeds` → `BundledSeedCatalog`, `TaskTypes` / `DemoTaskTypes` → `TaskTypeCatalog`,
`WorkflowSamples` → `SampleWorkflowCatalog`, `RuntimeBeans` / `DefaultRuntimeBeans` → `BeanRegistry` / `DefaultBeanRegistry`,
`Features` → `InstalledFeatures` (or fold into `FeatureRegistry`).

**Resolver / Lookup / Selector**
`DynamicToolActivations` → `EffectiveToolResolver`, `MediaTypes` → `MediaTypeResolver`,
`TimeZones` → `TimeZoneResolver`, `UserTimeZones` → `UserTimeZoneResolver`, `TokenCounters` → `TokenCounterResolver`,
`UserEmails` → `UserEmailLookup`, `McpTargetEndpoints` → `McpEndpointResolver`,
`ToolVariables` → `ToolVariableResolver`, `ToolConnections` → `ToolConnectionResolver`,
`HostNamespaces` / `BrandHostNamespaces` → `HostNamespaceResolver` / `BrandHostNamespaceResolver`,
`CurrentUsers` → `CurrentUserProvider`, `Accounts<A>` → `AccountSelector<A>`,
`RelevantExcerpts` → `RelevantExcerptSelector`, `BaseDirs` / `DocBaseDirs` → `FileRootResolver`,
`ToolNamePrefixes` → `ToolNamePrefixSuggester`, `LlmEmbeddings` (port) → `TextEmbedder`.

**Policy**
`SubAgentSupport` → `DelegationPolicy`, `UserPaths` → `SelfUserPathPolicy`, `VersionedForms` → `FormVersionPolicy`,
`FileWalks` → `FileWalkPolicy` + `TextDecoding`.

**Converter / Mapper / Renderer**
`ApprovalNotifications` → `ApprovalDecisionConverter`, `ProtocolParts` → `ProtocolPartMapper`,
`InputParts` → `InputPartMapper`, `StreamEvents` → `ResponsesStreamEncoder`, `McpRegistrationJson` → `McpRegistrationConverter`,
`StepJsonMapper` → `StepJsonConverter`, `JsonMapper` (workflow) → `JsonConverter`, `RunInstanceJson` → `StepInstanceJsonRenderer`,
`SummaryWindowMessages` → `SummaryRowRenderer`, `RegistryNotes` → `RegistryNoteRenderer`,
`StepMapper` → `StepFormMapper`, `TraceRedaction` → `TraceRedactor`.

**Reader / Writer / Builder / Factory**
`FileSkills` → `SkillFileReader`, `InstructionFiles` → `InstructionFileReader`, `ProjectAgents` → `ProjectAgentReader`,
`ClasspathManifests` → `ClasspathManifestLoader`, `AttachmentParts` → `AttachmentPartBuilder`,
`AtomicFiles` + `FileWrites` → `AtomicFileWriter`, `ParamFormFields` → `SchemaFieldFactory`,
`WorkingMemoryBuilder` (static, not a builder) → `WorkingMemorySnapshot.of(...)`,
`WorkflowInstanceSnapshots` → `WorkflowInstanceSnapshotter` + `WorkflowInstanceRestorer`,
`WorkflowInputs` → `RunInputPreparer`, `StepNames` → `UniqueStepNamer`, `ParamCoercion` → `ParamValueCoercer`,
`WorkflowFileImport` → `FileStoreImporter`, `SchemaJackson` → `SchemaJacksonModule`.

**Service / Installer / Dispatcher (verbs)**
`SubAgentCalls` → `SubAgentDispatcher`, `OAuthConnections` → `OAuthConnectionService`,
`OAuthProviderContributions` → `OAuthProviderContributionInstaller`, `InitialRegistrySources` → `InitialRegistrySourceInstaller`,
`NamespaceMembers` → `NamespaceMembershipService`, `MailListViews` → `MailListViewService`,
`OnBehalfTokens` → `OnBehalfTokenSigner`, `EmbeddingChecks` → `EmbeddingValidator`,
`WorkflowAgentCalls` → `WorkflowAgentCaller`, `AttachSupport` → `AttachmentIngestion`,
`LegacyVectorStores` → `LegacyVectorStoreMigration`, `PostgresVectorStores` → `PgVectorStoreRegistryOpener`,
`RefreshingConnections` / `ServiceConnections` → `TokenRefreshingConnectionSource` / `ConnectionServiceAdapter`,
`ChatHostLinks` / `AdminChatHostLinks` → `ChatHostLinkProvider` / `AdminChatHostLinkProvider`,
`WorkerProcessManager` → `WorkerProcessSupervisor`, `ClusterHttp` → `NudgeClient`.

**Value objects (singular noun)**
`ToolCalls` → `ToolCallFold`, `LlmPrices` → `PriceBook`, `LlmHttpErrors` → `ProviderHttpError`,
`OpenAiModels` (enum) → `OpenAiModel`, `UserCredentials` → `Credential` with `FormCredential`, `ApiKeyCredential`, `OAuth2UserCredential`,
`Preferences` → `PreferenceSet`, `Reminders` → `ReminderList`, `MetadataFields` → `MetadataFieldSet`,
`Vectors` → `VectorMath`, `Versions` → `OptimisticVersion`, `SeenChats` → `SeenChatLog`,
`WebScraperResults` → `ScrapeResult`, `WebScraperOptions` / `BaseWebScraperOptions` → `CrawlOptions` / `PageScrapeOptions`,
`WorkflowShapes` → `WorkflowShape` (enum), `PathLocks` → `StripedPathLock`, `Documents` → `FileDocumentStore`,
`SessionDirs` → `SessionMounts`, `ToolPaths` → `PathArguments`, `BoundConnections` → `ConnectionBinding`.

**Misnamed concepts**
`ToolRepository` → `ToolSettingsRepository`; `AuthenticationInfo` → `Caller`;
`ConversationManager` → `ConversationLog` (or drop for `ConversationService`, Q-02);
`AgentResponses` / `Sessions` / `Conversations` / `Files` (protocol API) → `ResponseApi` / `SessionApi` / `ConversationApi` / `FileApi`;
`OpenAiHttp` → `OpenAiHttpClient`; `FileDocuments` → `JsonDocumentDirectory`;
`DocumentParser` → `WebContentExtractor`; `FileRoots` → `DirectorySandbox`;
`IWebScraper` / `ISingleWebPageScraper` → `WebScraper` / `PageScraper`;
`InitialDataInstaller` → `InitialDataSeeder`; `EncryptionHelper` → `SecretCipher` + `TaggedSecret`;
`LoggingContext` → `MdcScope`; `McEnv` → `DotEnvFile`; `SessionUiCommons` → dissolve;
`HttpStepException` → `HttpCallException`; `MultipleExceptionsException` → `ParallelExecutionException`;
`RegistryBrowseView` and siblings → `*Component`; `Thoughts`, `ToolCallHistory` → `*Component`.

**Catch-all `*Service` names that become several classes** (sections 4.x)
`AgentChatService`, `AgentSessionService`, `NamespaceService`, `RegistryService`, `MigrationService`,
`WorkflowAdminService` → `WorkflowAdminFacade`, `WorkflowRunService` → `WorkflowRunner`,
`WorkflowExecutorService` → `WorkflowExecutor`, `SessionFileService` → `SessionAttachmentService`,
`LlmConfigTestService` → `LlmConfigProbe`, `ToolTestService` → `ToolProbe`, `TranscriptionJobService` → `TranscriptionJobRunner`.

**Package names**
`workflow.persist` (core) vs `workflow.persistence` (modules) → one name;
`mc-agent-runtime/adapter/{repo/memory, filestore, rule, llm, token, prompt}` → `adapter/{inmemory, file, pebble, jtokkit, local}`;
`mc-extension/.../runtime` → `adapter.runtime`;
`ai.mindconnect.office.tools` split across two jars → `office.mail.tools`, `office.calendar.tools`.

## 6. Open questions for the maintainer

Decisions that change what the plan does. Each one blocks the finding it
names; everything else can proceed.

| # | Question | Blocks |
|---|---|---|
| Q-01 | Is the Spring-free `AgentRuntimeBuilder` the only supported assembly for 1.0, so that the shorter constructors and overloads may go? | RT-04, TL-09, SV-13 |
| Q-02 | Driving side: `port.in` interfaces everywhere (as llm, message) or concrete `XxxService` classes everywhere (as user, namespace, extension, registry, credentials)? | GW-15, X-07 |
| Q-03 | One word for a persistence adapter: `Repository` (agents, 121 uses), `Store` (taskqueue, office, vectorstore, 41 uses) or `Repo` (`mc-file-repo`)? | X-07, RT-14 |
| Q-04 | May `core/` modules contain Spring auto-configuration, or is the rule "wiring only in `springstarter/`" (then `mc-agent-tools-workflow`, `mc-agent-tools-virtual-env`, `mc-mcp-gateway-local`, `mc-extension`, `mc-agent-registry` move their wiring)? | X-06, TL-03, TL-25, GW-27 |
| Q-05 | Is direct `java.nio` IO in `runtime-core` (`SessionDirectories`, `UserHome`, `InstructionFiles`, `ProjectAgents`, `FileSkills`) deliberate, or should all of it go through `WorkspaceFiles` like the virtual-env path? | RT-07 |
| Q-06 | Do `NamespaceRouted` and `ThreadBoundScope` stay in `mc-agent-domain` (every module needs them) or may the proxy and thread-local machinery leave the vocabulary module? | RT-25 |
| Q-07 | `ToolCallScope.CURRENT` (tool-spi thread-local) and `ThreadBoundScope` (domain): two ambient contexts on purpose? | RT-28 |
| Q-08 | Is the four-way copy in the LLM gateways deliberate ("each provider readable on its own"), or is a shared streaming skeleton acceptable? | GW-01..04 |
| Q-09 | Are `LlmGateway.chat(...)` and `OpenAiCompatibleConfig` public API someone depends on? | GW-05, GW-06 |
| Q-10 | Does `mc-credentials` get the `-core` split before 1.0 freezes the artifact set? | GW-12 |
| Q-11 | Should feature modules (`mc-extension`, `mc-agent-registry`) keep wiring their own Pg beans, or does all `-pg` wiring live in `mc-agent-starter-postgres`? | GW-27 |
| Q-12 | Is "each tool module independently deployable" (the reason given in `DocBaseDirs`) a hard requirement, or may shared helpers live in `mc-agent-tool-spi`? | TL-01, TL-14 |
| Q-13 | Same as Q-04 for `mc-agent-tools-workflow`, which additionally depends on `mc-workflow-admin-rest` and `-persistence-pg`. | TL-03 |
| Q-14 | Tool-per-lambda (`OfficeTool`, `MailTools`) or Tool + ToolFactory per class (built-in modules) as the one layout for multi-tool providers? | TL-13, TL-21 |
| Q-15 | Are `ToolConnection` / `Connections` / `ConnectionSpec` tool vocabulary or domain vocabulary? Decides whether `mc-mail-core` may depend on `mc-agent-tool-spi`. | TL-16, TL-19 |
| Q-16 | Will 1.0 still read pre-0.9 vector stores, or can `LegacyVectorStores` become a one-off migration command? | TL-23 |
| Q-17 | Does `chat-ui-rest` stay a sibling of `api-rest` over the same services, or will the chat UI call the REST API like the CLI? Decides shared services vs shared DTOs. | SV-02, SV-21 |
| Q-18 | Are the builder's `property()` string keys (`tavilyApiKey`, `codeExec*`, `vectorStore*`) public API for 1.0? | SV-13 |
| Q-19 | Must every runtime bean stay injectable for extensions, or can the starter stop re-exporting 38 beans? | SV-14 |
| Q-20 | Which seeding path is canonical for the CLI: the admin's `BundledSeeds` / `NamespaceSeeder`, or its own loader with interactive overwrite? | SV-15 |
| Q-21 | `AdminSameUrlFilter.LEGACY_SECTIONS`: is the "every section is an SPA route" migration still intended before 1.0? | SV-22 |
| Q-22 | `spel:` support planned for 1.0, or remove every reference? | WF-01 |
| Q-23 | May the PUML parser drop legacy syntax (`#params:`, `sub/end sub`, note-based for-each)? | WF-03 |
| Q-24 | ~~Rename the public `Data` / `Step` / `Instance` vocabulary before 1.0?~~ Decided 2026-10-11: yes, as `…StepDefinition` / `…StepInstance`, see WF-20. | WF-20 |
| Q-25 | Which workflow UIs ship in 1.0: Swing designer, `mc-workflow-ui-diagram-app`, admin UI all overlap. | WF-25, WF-14 |
| Q-26 | Should `CallWorkflowStep` resolve callees from the admin's `WorkflowDataRepository` (today only classpath JSON works)? | WF-28 |
| Q-27 | May `mc-task-queue-jdbc` depend on `common/mc-jdbc`, or must the taskqueue area stay free of `common/`? | TQ-04 |
| Q-28 | Is `SharedLockStore` on the 1.0 surface (then it needs the JDBC adapter), or can it wait? | TQ-06, TQ-07 |
| Q-29 | `mc-webscraper`: customer URLs and `RailsWebScraperConfig` are leftovers to purge? | CM-03 |
| Q-30 | Is `mc-file-manager` used by anything outside this repo? If not, delete before 1.0. | CM-05 |
