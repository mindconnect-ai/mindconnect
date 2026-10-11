# Brief: prepare the agents core libraries for the 1.0 release

You are a coding agent working only on the core libraries of the `agents/`
area of the Maven monorepo `mindconnect-ai/mindconnect`. Read `CLAUDE.md`,
`AGENTS.md` and `agents/README.md` first. The full review is in
`doc/release-1.0/review.md` (sections 4.1 and 4.2 are yours), the overall
plan in `doc/release-1.0/plan.md`. This brief is self-contained; the two
documents give more context if you need it.

## Scope

- `agents/core/mc-agent-domain`
- `agents/core/mc-agent-runtime-core`, `mc-agent-runtime`
- `agents/core/mc-agent-memory-strategies`
- `agents/core/mc-agent-tool-spi`
- `agents/core/mc-llm-gateway-core`, `mc-llm-gateway`
- `agents/core/mc-message-repository-core`, `mc-message-repository`
- `agents/core/mc-agent-protocol`, `mc-agent-protocol-openai`, `mc-agent-protocol-mc-runtime`
- `agents/core/mc-credentials`
- `agents/core/mc-user-core`, `mc-user`
- `agents/core/mc-namespace-core`, `mc-namespace`
- `agents/core/mc-extension-core`, `mc-extension`
- `agents/core/mc-agent-registry-core`, `mc-agent-registry`
- `agents/adapter/postgres/*` and `common/mc-jdbc`

Not yours: the tool modules (`mc-agent-tools*`), `office/`, `mcp/`,
`vectorstore/`, `server/`, `client/`, `builder/`, `springstarter/`. They get
their own briefs. When a rename in your scope breaks a caller there, fix the
caller mechanically in the same PR and say so in the PR body; do not
refactor it.

## Goal

The core libraries are plain Java, hexagonal: domain and ports in a `-core`
module, adapters in the sibling module or under `adapter/postgres/`, Spring
wiring only in `springstarter/`. For 1.0 the code must have one
responsibility per class, short methods (aim under 30 lines) and classes
(aim under 300), names after the pattern a class implements (Registry,
Catalog, Resolver, Policy, Converter, Renderer, Factory, Repository, Adapter,
Strategy, Worker, Gateway), singular class names, one constructor per
class, and javadoc that says what and why in one paragraph without project
history.

## Ground rules for every PR

1. One topic per PR. A rename PR renames. A split PR splits one class. A bug
   fix PR fixes one bug with its test. Never mix.
2. A verifiable goal before the change: a failing test for a bug; the
   module's test suite green before and after a structural change with the
   test sources untouched.
3. Surgical. Match the surrounding style, do not reformat, do not improve
   unrelated code. Dead code you find on the way goes into the PR body as a
   note, unless the PR is the dead-code PR.
4. Minimum code. No abstraction for one caller, no configurability nobody
   asked for, no catch for a case that cannot happen.
5. Javadoc: present tense, what and why, one paragraph. No "concept NN,
   step M", no "it used to", no `<h2>` essays, no javadoc that restates the
   signature. What is worth keeping as design rationale goes to
   `agents/doc/`.
6. Imports, never inline fully qualified names.
7. One constructor per class unless a second one has a real second caller.
   `AgentRuntimeBuilder` is the assembly; tests build the full argument list.
8. Build with `mvn -f agents/pom.xml clean install` (or the touched module
   after its dependencies) and read the `Tests run:` / `Skipped:` counts; the
   LM Studio and pgvector suites skip themselves without their service.
9. Changelog: a bug fix or a removal gets a fragment
   `changelog.d/<fixed|removed|changed>/<branch-slug>.md` with
   `- **agents:** **what is different.** explanation…`. Structural work
   (renames, splits, moves) gets the PR label `no-changelog` instead.
10. Everything in the repository and on GitHub is English. No
    `Co-Authored-By` trailer.
11. Branch from `next` as `feature/agents-core-<topic>`, run
    `./after-branch-creation.sh` once, open the PR against `next`, one PR
    per step below.

## Decisions already taken

- **Converter** is the name for a class that maps an object to a
  representation and back in one class (both directions). One direction
  only is a Serializer/Writer or a Parser/Reader; object to object is a
  Mapper; object to something a person looks at is a Renderer.
- **Definition : Instance** is the vocabulary for "what is configured" vs
  "what runs" (`AgentDefinition` already follows it; the workflow area is
  being renamed to match).

## Questions to ask the maintainer before the step that needs them

Ask briefly, in one message, as soon as you start; continue with the steps
that do not depend on the answer.

- Q-01: Is `AgentRuntimeBuilder` the only supported assembly for 1.0, so
  that the shorter constructors and "backward-compat" overloads may go?
- Q-02: Driving side: `port.in` interfaces everywhere (as in
  `mc-llm-gateway-core`, `mc-message-repository-core`) or concrete
  `XxxService` classes everywhere (as in user, namespace, extension,
  registry, credentials)?
- Q-03: One word for a persistence port: `Repository` (121 uses) or `Store`
  (41 uses, taskqueue and office)?
- Q-05: Is direct `java.nio` IO in `runtime-core` (`SessionDirectories`,
  `UserHome`, `InstructionFiles`, `ProjectAgents`, `FileSkills`)
  deliberate, or should all of it go through `WorkspaceFiles` like the
  virtual-env path does?
- Q-06: May `NamespaceRouted`, `ThreadBoundScope`, `NamespaceFirstUse`,
  `StartupScope` leave `mc-agent-domain` (the vocabulary module) for a
  `scope` package in runtime-core or a `mc-agent-scope` module?
- Q-07: `ToolCallScope.CURRENT` (thread-local in tool-spi) and
  `ThreadBoundScope` (domain) are two ambient-context mechanisms. Intended?
- Q-08: Is the four-way copy of the streaming skeleton in the LLM gateways
  deliberate ("each provider readable on its own"), or is a shared skeleton
  acceptable?
- Q-09: Are `LlmGateway.chat(...)` and `OpenAiCompatibleConfig` public API
  someone depends on, or can they go?
- Q-10: Does `mc-credentials` get the `-core` split before 1.0?
- Q-11: Should `mc-extension` and `mc-agent-registry` keep wiring their own
  Postgres beans, or does all `-pg` wiring move to `mc-agent-starter-postgres`?

## Work, in order

### Step 1: hygiene (one PR per module group, several commits of one kind each, label `no-changelog`)

1. Inline fully qualified names → imports. Worst files: `AgentSession` (48),
   `AgentSessionService` (20), `AgentChatService` (31), `StatelessAgentSeeder`
   (13), `SpiToolRegistry` (10), `DynamicToolActivations` (9),
   `SubAgentCalls`, `WorkingDirPolicy`, `OpenAiResponsesGateway` (:105, :120,
   :175).
2. Javadoc that names removed things or sits on the wrong declaration:
   `ToolExecutor:31` (`ChatTurnContext.addMessage`), `AgentTurnWorker:61`,
   `DynamicToolActivations:22` (`AgentChatService.resolveTools`),
   `ResponseReviewerChain:34` (`runReviewers`), `SpiToolRegistry:433`
   (`dispatchSubAgent`), `LlmChatProvider:281`; orphaned double javadocs at
   `AgentChatService:548-562` (cancel doc above `record Attachment`) and
   `:645-651`, `AgentSessionService:302-303`, `AgentTurnWorker:424-426`,
   `protocol/Response.java:290-302` (the `outputText()` doc is attached to
   `openFunctionCalls()`), `PgLlmConfigRepository:55-57` (javadoc between
   `@Override` and the method; grep all pg modules for the pattern).
3. Essays and history: `ToolCallWorker:45-67`, `ToolCalls:19-44`,
   `QueuedAgentRoundToolExecutor:220-246`, `UserToolRoster:504-529`,
   `ToolPairSanitizer:23-68`, `DynamicToolActivations.effectiveRefs:89-116`,
   `SummaryWindowMessages:285-301` (history of a bug),
   `AgentSessionService.loadHistory:484-491` (a changelog),
   `LlmConfig:70-97`, `protocol/item/ConversationItem:6-33`,
   `event/ResponseEvent:203-230`, `oauth/OAuthProviderContribution:645-707`,
   `ThrottlingLlmGateway:164-189`. Keep one paragraph of what; move why to
   `agents/doc/`; delete every "concept N" reference.
4. Dead code: `SummarizingWindowStrategy.compressUncompressedToolResults:306`
   and `recompressAllToolResults:323` (javadoc says "No caller"),
   `AgentTurnWorker.string(Object):511`, `SessionTools.isInline:481`,
   `ToolCalls.inState/byId:115-121`, `ToolExecutor.Context` 4-arg
   "backward-compat" constructor :259, `AgentTaskRunner.run` default :38,
   `RetryingLlmGateway.delegate():313-316`,
   `ThrottlingLlmGateway.delegate():204-207`, the `"TOOL_CALL"` finish-reason
   case in `GeminiGateway:329-331` ("guards against future API changes"),
   `InMemoryMessageStore` (two tests use it; its javadoc names a
   `FileMessageStore` that does not exist: move to test fixtures),
   `OpenAiCompatibleConfig` and the `@Component` on `OpenAiCompatibleGateway`
   (after Q-09; nothing imports them, `CoreFeature` builds the gateway by
   hand).
5. Telescoping constructors (after Q-01): `AgentChatService` (3,
   :118-190), `AgentSessionService` (4, :64-133), `AgentTurnWorker` (4,
   :111-205), `ToolCallWorker` (2), `SessionTools` (3, :371-396),
   `LlmChatProvider` (2), `DynamicToolActivations` (3),
   `WorkingMemoryBuilder` (3 overloads, :191-217), `SystemPromptRenderer`
   (3), `NamespaceService` (7, :70-107). Keep the fullest.
6. Catches on code that cannot fail: `ToolCallWorker.approvalContent:324`,
   `LlmChatProvider.toolCallContent:273`,
   `ToolPairSanitizer.syntheticPlaceholder:185`,
   `ToolExecutor.invokeTool:393-403` ("Should be unreachable"), and the
   `try { prettyRequestJson = … } catch (Exception ignored) {}` in
   `AbstractOpenAiGateway:97`, `ClaudeGateway:97`, `GeminiGateway:99`,
   `OpenAiResponsesGateway:151`.
7. Logging and mappers: `FileToolRepository:36`,
   `FileConversationRepository:14,29`, `FileMessageRepository:131,146` use
   `java.util.logging` → SLF4J. Five file adapters build their own
   `ObjectMapper` (`FileToolRepository:38`,
   `FileConversationSummaryRepository:34`, `FileLlmCallTraceRepository:67`,
   `FileTodoListRepository:36`, `FileWorkingMemoryRepository:32`) while
   `FileAgentRepositoryFactory:26` injects the runtime's: inject everywhere.
8. POM hygiene: `spring-context` in `mc-agent-runtime/pom.xml:65-68` and
   `mc-message-repository/pom.xml:41` is never imported; remove.
   `AgentRuntimeBackend:134` uses a static `ObjectMapper` from a Jackson
   dependency the POM does not declare; declare or remove.
9. Small consistency: `common/mc-jdbc/Sql.java:503` has
   `public final class  Sql` (double space). All 17 Postgres repositories:
   the two copied javadoc sentences ("Runs the idempotent DDL…", "Share a
   Sql…") become one `PgRepository` marker documented once; all classes
   `final`; table name always a `TABLE` constant. Fields named
   `approvalStore` for a `ToolApprovalRepository` (`AgentChatService:94`,
   `AgentSessionService:55`, `ToolCallWorker:90`) → `approvalRepository`.
10. `new SessionAgentResolver(definitionRepository).resolve(session)` plus
    the same 5-line javadoc in `AgentChatService:766`, `AgentTurnWorker:520`,
    `ToolCallWorker:417`, `SubAgentCalls:220`: inject one resolver.

Verification: every module builds and its tests pass with unchanged test
sources; `git diff --stat` shows only the kinds of change the commit names.

### Step 2: bugs (one PR each, failing test first, changelog fragment under `fixed`)

1. `mc-message-repository-core/.../ConversationService.java:118-126`:
   `deleteMessages` loads a page of 500 to count matches before delegating
   the delete; conversations with more than 500 messages are miscounted.
   Let `MessageRepository.deleteBySequenceRange` return the count. (GW-17)
2. `mc-agent-protocol-openai/.../OpenAiMapper.java:232-235`:
   `incompleteReason(JsonNode n)` ignores its argument and always returns
   `CONTEXT_OVERFLOW`. Read `incomplete_details.reason`, or inline the
   constant and say so. (GW-22)
3. `ToolCallWorker:132,137` and `SubAgentCalls:56`: the worker constructs
   its own `SubAgentCalls` and needs `attach(TaskQueue)` after
   construction (`volatile TaskQueue`), a wiring cycle surfaced as public
   API. Pass `SubAgentCalls` with its queue in via the builder. (RT-19)

### Step 3: module seams (one PR each, label `no-changelog`)

1. `mc-user-core/.../adapter/tool/StoredUserToolRoster`: an `adapter`
   package inside a `-core` module. Move to `mc-user` (`adapter.tool`).
   (GW-13)
2. `mc-extension/.../runtime/ExtensionToolRegistry`, `ExtensionsFeature`:
   adapters outside `adapter.*`. Rename the package to `adapter.runtime`.
   (GW-14)
3. `mc-agent-runtime` adapter packages mix by-technology and by-port
   (`adapter/repo/memory` vs `adapter/file`, `adapter/filestore`,
   `adapter/rule`, `adapter/llm`, `adapter/token`, `adapter/local`,
   `adapter/prompt`) → `adapter/file`, `adapter/inmemory`, `adapter/pebble`,
   `adapter/jtokkit`, `adapter/local`. (RT-17)
4. `mc-agent-memory-strategies` reaches into runtime-core `service`:
   `StatelessAgentSeeder.CONVERSATION_SUMMARIZER`
   (`SummarizingWindowStrategy:241`, `AutoCompactStrategy:11`) and
   `service.ContextTokenBudget`. Move the built-in task names to a
   `BuiltInTask` next to the `AgentTaskRunner` port, `ContextTokenBudget` to
   `memory/domain`. (RT-27)
5. Port placement inside runtime-core: `SkillRepository` in `skill/`,
   `UserMemoryRepository` in `usermemory/`, `TodoListRepository` in
   `tools/todo/`, `MemoryStrategy` under `memory/port/in` (an internal
   strategy, not a use case), `LlmMessageMapper` in `port/out` with its
   default in `service/`. Pick one rule (all ports under `port/{in,out}`,
   or every feature package gets its own `port/`) and apply it. (RT-14)
6. `AgentChatService:693` branches on `scope instanceof ThreadBoundScope`.
   Add `ScopeSupplier.runIn(Scope, Runnable)` with a no-op default for
   fixed scopes. (RT-24)
7. After Q-06: scope machinery out of `mc-agent-domain`: `NamespaceRouted`
   (dynamic proxy with a process-wide static `ALL` :183), `ThreadBoundScope`
   (duplicated `runIn`/`callIn` bodies :74-105), `NamespaceFirstUse`,
   `StartupScope`. The domain keeps ids, `Scope`, `ScopeSupplier`. (RT-25)
8. After Q-11: the Postgres wiring in
   `mc-extension/spring/ExtensionAutoConfiguration:138-169` and
   `mc-agent-registry/spring/RegistryAutoConfiguration:93-127` (with FQN
   references to the `-pg` modules) moves to `mc-agent-starter-postgres`
   like every other `-pg` adapter. (GW-27)
9. After Q-10: split `mc-credentials` into `mc-credentials-core`
   (`domain`, `port`, `service`) and `mc-credentials` (`adapter.env`,
   `adapter.file`, `adapter.memory`, `adapter.tool`, the HTTP `OAuthFlow`);
   `OAuthConnections` moves from `oauth` to `service`. (GW-12)
10. After Q-09 and Q-02: `LlmGateway.java:59-146` carries 90 lines of
    implementation in the out-port (`chat` default, `ToolCallAccumulator`,
    `new ObjectMapper()` per call :139) with no production caller of `chat`.
    Move `chat` into a small helper in the adapter module or a test fixture;
    the port keeps `chatStreaming`. (GW-06)

Verification: an ArchUnit test (new module `agents/mc-agent-architecture-test`
or a test in each `-core`) that fails on: `org.springframework` or
`com.fasterxml.jackson.databind` imports in a `-core` module (the six
databind users in `mc-agent-runtime-core` are handled in step 5.3); an
`adapter` package in a `-core` module; an adapter module importing another
adapter module; a `-core` service importing `*.adapter.*`. Write it first,
let it list today's violations, and make the list empty by the end of this
step.

### Step 4: renames (one PR per module group, one commit per rename, IDE refactoring only, tests untouched, label `no-changelog`)

Ports first (they appear in the most signatures), then services, then
adapters.

Registry / Catalog: `SessionChannels` → `SessionChannelRegistry`,
`UserChannels` → `UserChannelRegistry`, `ToolBundles` → `ToolBundleCatalog`,
`InlineAgentTools` → `DelegationToolCatalog`, `RuntimeBeans` /
`DefaultRuntimeBeans` → `BeanRegistry` / `DefaultBeanRegistry`, `Features` →
`InstalledFeatures` (or fold into `FeatureRegistry`).

Resolver / Lookup: `DynamicToolActivations` → `EffectiveToolResolver`,
`MediaTypes` → `MediaTypeResolver`, `TimeZones` → `TimeZoneResolver`,
`UserTimeZones` → `UserTimeZoneResolver`, `TokenCounters` →
`TokenCounterResolver` (adapter `TokenCounterRegistry` stays), `UserEmails` →
`UserEmailLookup`, `LlmEmbeddings` (port) → `TextEmbedder`.

Policy: `SubAgentSupport` → `DelegationPolicy`.

Converter / Mapper / Renderer: `ApprovalNotifications` →
`ApprovalDecisionConverter`, `ProtocolParts` → `ProtocolPartMapper`,
`SummaryWindowMessages` → `SummaryRowRenderer`, `TraceRedaction` →
`TraceRedactor`.

Reader / Builder / Loader: `FileSkills` → `SkillFileReader`,
`InstructionFiles` → `InstructionFileReader`, `ProjectAgents` →
`ProjectAgentReader` (nested `ProjectAgent` stays), `AttachmentParts` →
`AttachmentPartBuilder`, `ClasspathManifests` → `ClasspathManifestLoader`,
`WorkingMemoryBuilder` (static, not a builder) → `WorkingMemorySnapshot.of(...)`,
`FileDocuments` → `JsonDocumentDirectory`.

Service / Installer / Dispatcher: `SubAgentCalls` → `SubAgentDispatcher`,
`OAuthConnections` → `OAuthConnectionService`, `OAuthProviderContributions`
→ `OAuthProviderContributionInstaller`, `InitialRegistrySources` →
`InitialRegistrySourceInstaller`, `NamespaceMembers` →
`NamespaceMembershipService`, `RefreshingConnections` / `ServiceConnections`
→ `TokenRefreshingConnectionSource` / `ConnectionServiceAdapter`.

Value objects: `ToolCalls` → `ToolCallFold`, `LlmPrices` → `PriceBook`
(instance methods instead of statics), `LlmParams` → fold into
`LlmRequest.paramsWith(LlmConfig)`, `LlmHttpErrors` → `ProviderHttpError`,
`OpenAiModels` (enum) → `OpenAiModel` with the static heuristics in
`OpenAiModelRules`, `UserCredentials` (sealed) → `Credential` with
`FormCredential`, `ApiKeyCredential`, `OAuth2UserCredential`, `Preferences`
→ `PreferenceSet`, `BoundConnections` → `ConnectionBinding`, `Connections`
(port) → `ConnectionRepository`, `SessionTools` → `SessionToolset`,
`SessionDirectories` → `SessionFileBrowser`, `PromptSections` →
`CompositePromptSection`, `FileRoots` → `DirectorySandbox`,
`LocalWorkspaceFiles` / `WorkspaceFiles` → `LocalWorkspaceFileSystem` /
`WorkspaceFileSystem` (only if the tool-module brief agrees; otherwise
leave).

Misnamed: `ToolRepository` (tool-spi; stores settings, "deliberately not a
list of tools" :437) → `ToolSettingsRepository`, `AuthenticationInfo` →
`Caller`, `ConversationManager` → `ConversationLog` (or drop in favour of
`ConversationService`, after Q-02), protocol API `AgentResponses` /
`Sessions` / `Conversations` / `Files` → `ResponseApi` / `SessionApi` /
`ConversationApi` / `FileApi` (`Files` shadows `java.nio.file.Files`),
`OpenAiHttp` → `OpenAiHttpClient`.

After Q-03: `Repository` ↔ `Store` made uniform across the scope.

Verification: a grep for every old name returns nothing in code, `agents/doc`
and `website/docs`; every module builds. A changelog fragment under
`changed` listing every renamed public type, old → new, in the last rename
PR.

### Step 5: god classes and duplication (one PR per class, in this order; stop after each for review)

1. **The four LLM gateways** (after Q-08). `ClaudeGateway:74-226`,
   `AbstractOpenAiGateway:71-232`, `OpenAiResponsesGateway:132-236`,
   `GeminiGateway:74-216` each carry the same `chatStreaming` skeleton
   (resolve config → build body → trace → OkHttp call → non-2xx handling →
   SSE block loop → cancellation → listener → `Done`); only
   `buildRequestNode`, the per-event parse and `parseFinishReason` differ.
   Build in four commits: (a) `ProviderHttpError.of(label, config, response)`
   with `toException()` replacing the verbatim non-2xx block (GW-04);
   (b) `ToolCallAssembler` in core domain replacing `ClaudeGateway:327-349`,
   `AbstractOpenAiGateway:406-428`, the port's `ToolCallAccumulator` and
   Gemini's inline builder (GW-02); (c) `LlmCallTraceRecorder` replacing the
   13-parameter `fireListener`, `logWireRequest`, `summary`,
   `extractDataLine`, `dataUrl` copies (GW-03); (d) an `SseChatGateway`
   template with a `ProviderWireFormat` strategy per provider
   (`Request request(config, body)`, `ObjectNode body(config, request)`,
   `void onEvent(type, data, StreamState, handler)`) (GW-01). The existing
   wire-JSON tests must pass unchanged against the shared skeleton.
   `OpenAiResponsesGateway`'s reach into the sibling's package-private
   `wire` logger and `OpenAiToolCallBuilder` (:152-153, :389, :429, :510)
   disappears with (b) and (c). Then fold `LmStudioModelCatalog` (205) into
   `ProviderModelCatalog` (383) as a fourth request shape with one `Catalog`
   record (GW-30).
2. **`AgentChatService`** (770 lines). Submits turns, bookkeeps task futures
   (`awaitingTurns`, `awaitingTitles`, `resumeCursors`), answers approvals
   (:509), cancels and supersedes (:441, :597), attaches reconnecting
   streams (:574), builds memory snapshots (:722), exposes UI tool
   definitions (:197). Split into `ChatTurnSubmitter` (submit, startTurn,
   handleFor, turnEnded), `ApprovalService` (approve, deny, answerApproval,
   openApprovals), `SessionStreamAttachment` (attach), `MemoryService`
   (memorySnapshot, compressMemory). Together with it the approval concern
   that is spread over `ToolCallWorker.approvalGate` (:282-317),
   `AgentChatService.answerApproval` (:509-546) and
   `AgentSessionService.isToolApproved` (:603): one `service/approval`
   package with `ApprovalGate` (pure decision) + `ApprovalService`; the
   worker calls the gate and does the side effects itself. (RT-01, RT-03)
3. **Tool call content.** Jackson databind inside core and five parsers of
   the same TOOL_CALL / TOOL_RESULT JSON: `round/ToolCalls:48`,
   `service/task/LlmChatProvider:76`, `round/TurnMessage:6`,
   `ToolCallWorker:271`, `MessageToLlmMessageMapper:57`,
   `SummarizingWindowStrategy:50,424`, `ToolPairSanitizer:72,145,157`;
   `FeatureContext:103` exposes `ObjectMapper` as API. One
   `ToolCallContent` / `ToolResultContent` converter next to `Message` in
   `mc-message-repository-core`; the per-class mappers go; `objectMapper()`
   stays only for adapters. (RT-06)
4. **`AgentSessionService`** (636). Session CRUD mixed with
   working-directory policy and validation (:151-310), a file-explorer
   factory (:192), parent-chain queries (:603-635), raw `Files.walk`
   deletion (:530-545). Split into `SessionLifecycle`,
   `SessionDirectoryPolicy` (with `UserHome`), `SessionChain`;
   `UserHome.deleteSessionDir` for the physical deletion. (RT-02)
5. **`AgentTurnWorker` and `ToolCallWorker`.** `AgentTurnWorker.runTurn`
   (:295-415, 120 lines) assembles six collaborators then four outcome
   branches with inline user-facing texts (:385-398): extract
   `TurnAssembly` and `TurnEnding`. `ToolCallWorker.execute` (:168-265, 98
   lines): extract a `ToolTaskPayload` record and `runAndRecord(...)`. The
   tuple (`PromptRenderer`, `InstructionFiles`, `SkillCatalog`,
   `PromptSections`) threaded by hand through `WorkingMemoryBuilder`
   (static), `SystemPromptRenderer` (static, 8 params), `AgentChatService:726`,
   `AgentTurnWorker:473`, `LlmChatProvider:165` becomes one injectable
   `SystemPromptComposer` with `WorkingMemorySnapshot.of(...)` on top.
   (RT-05, RT-21, RT-22)
6. **`SessionDirectories`** (536, after Q-05). `java.nio` and
   `ZipOutputStream` in `runtime-core/service`; `if (remote != null)` in
   every public method (:94, :132, :160, :193, :215, :334); reaches into
   `FileRoots` inline (:236). One implementation over
   `Map<String, WorkspaceFiles>` using `WorkspaceFiles.local(FileRoots.of(root))`
   (already used at :236) removes the local branch and halves the class.
   Consider the same for `ProjectAgents`, `FileSkills`, `InstructionFiles`.
   (RT-07)
7. **Memory strategies.** `SummarizingWindowStrategy` and
   `AutoCompactStrategy` duplicate `renderSummariesAsUserMessage`,
   `appendSummaryBody`, `summarizedSequenceNumbers`, `effectiveText`,
   `summarise` and the window-row building (:122-155 vs :103-130);
   `permissiveBudget` is repeated in four strategies with a 10-argument
   `SummarizingWindowConfig` (`NoMemoryStrategy:409-412`). Extract
   `SummaryRenderer` + `ConversationSummarizer`; add
   `ContextTokenBudget.unlimited(...)`. (RT-08)
8. **`NamespaceService`** (537): membership (`invite`, `promote`, `demote`,
   `removeMember`, `leave`), variables (`setEnvironment`, `putVariable`,
   `removeVariable`), lifecycle (`create`, `ensure`, `delete`), identity
   (`actor`, `address`), access policy (`role`, `isAdmin`, `canAccess`).
   Split into `NamespaceAccessPolicy`, `NamespaceMembership`,
   `NamespaceVariables`; lifecycle stays; one constructor taking a
   `NamespaceSettings` record. (GW-08)
9. **`RegistryService`** (562): source CRUD, browsing, the dependency walk
   with three anonymous `Visitor`s (:139, :312, :356, :420), the
   `usedOutside` fixed point (:174-215), removal. Extract `PackageWalker`
   (walk + Visitor) and `PackageUsageAnalyzer` (`usedOutside`). (GW-09)
10. **`AgentRuntimeBackend`** (523) and **`OpenAiResponsesBackend`** (411).
    Four anonymous classes implement `Sessions`, `AgentResponses`, `Files`,
    `Conversations`; file attachment (:354-436) and a JSON parse of
    `ToolApproval.content` (:141-157) inline; mutable `withFiles` setters
    (:164-169). Named classes `RuntimeSessionApi`, `RuntimeResponseApi`,
    `RuntimeFileApi`, `RuntimeConversationApi` sharing a `BackendContext`;
    files as a constructor argument. The OpenAI backend additionally gets
    its delegation engine (`createLogical`, `delegate`, `rewriteToAgentCall`,
    `awaitTerminal` :227-358, hard-coded 700 ms poll) extracted into
    `AgentDelegationLoop`. (GW-10, GW-11)
11. **`LlmConfig`** (464): twelve provider preset factories with hard-coded
    context windows (:220-310) and four `resolved` overloads (:393-463) on
    the domain record. `LlmConfigPresets` in the adapter module; one
    `resolved(env, encryption)`. `LlmProvider` (:105-139, :141-149) carries
    admin-form text; acceptable for 1.0, else `ProviderParamCatalog` in the
    adapter module. (GW-07, GW-20)
12. **`AgentDefinition`** (458, 19 components, hand-written constructors
    :61-104) and **`AgentSession`** (409, 18 components, a `@JsonCreator
    fromJson` :291-319 that only re-wraps strings into id records). Let the
    id records' own `@JsonCreator` do the wrapping (drop `fromJson`),
    replace constructor overloads with `with*`ers, move `DERIVED_TOOLS`
    filtering to the registry service. (RT-20)
13. **`ToolRegistry`** (tool-spi, :17-113): 11 default methods mix
    resolution with catalog/UI concerns (`toolNamesByGroup`,
    `declaredVariables`, `connectionSpecs`, `overridesSchema`,
    `subgroupOf`); `source()` leaks the decorator chain. Split `ToolCatalog`
    (read model) from `ToolRegistry` (resolve). **`SpiToolRegistry`** (488)
    mixes ServiceLoader discovery, background warm-up with retries
    (:163-251), catalog queries and decoration (:482-487): extract
    `ProviderWarmUp`. `ToolExecutor.execute` (:343) repeats a name lookup
    `SessionTools.liveTool` (:404) already did; chain-building duplicated
    with `AgentLoop:117-121`: take `Optional<Tool>`, extract `chain()`.
    (RT-23, RT-30, RT-29)
14. **`DocumentTable`** (`common/mc-jdbc`, 464): DDL (:88-124), SQL assembly
    (:337-397), CRUD, a nested `Builder` (:399-464). Extract
    `TableDefinition` (columns, indexes, `ddl()`, `upsertSql()`,
    `insertSql()`, `columnList()`); `DocumentTable` stays the repository over
    it. (CM-10)
15. After Q-07: `ToolCallScope.CURRENT` (tool-spi :207-223) derived from
    `Scope` host attributes, or a one-paragraph javadoc saying why two
    ambient contexts exist. (RT-28)

Verification for every split: the module's tests unchanged and green; the
old class is gone or a thin facade; no new class above ~300 lines and no
method above ~50; the manual chat test session in `agents/doc/manual-tests`
for items 2, 3, 5 and 6 (they touch what a user sees on every turn).

### Step 6: docs

`agents/README.md`, `CLAUDE.md`'s module list and `website/docs/agents/**`
updated in the same PR as every rename, move or removal. At the end,
`agents/doc/` holds the design rationale that left the javadoc, one page
per topic, linked from the README.

## When you are unsure

Ask the maintainer in one short message with the options and your
recommendation. Do not pick silently where two readings lead to different
code. Do not widen a PR to fix something you noticed; note it in the PR body.
