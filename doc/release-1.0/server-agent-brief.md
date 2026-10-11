# Brief: prepare the agents server, client, builder and starters for the 1.0 release

You are a coding agent working only on the deployable side of the `agents/`
area of the Maven monorepo `mindconnect-ai/mindconnect`. Read `CLAUDE.md`,
`AGENTS.md` and `agents/README.md` first. The full review is in
`doc/release-1.0/review.md` (section 4.4, the `SV-` findings, is yours), the
overall plan in `doc/release-1.0/plan.md`. This brief is self-contained.

## Scope

- `agents/server/*`: `mc-agent-admin-ui-rest`, `mc-agent-admin-ui-app`,
  `mc-agent-chat-ui-rest`, `mc-agent-api-rest`, `mc-agent-api-app`,
  `mc-agent-api-responses-rest`, `mc-agent-security`,
  `mc-agent-registry-admin-ui-rest`
- `agents/client/mc-agent-cli`
- `agents/builder/*`: `mc-agent-runtime-builder` and every
  `mc-agent-runtime-feature-*`
- `agents/springstarter/*`
- `agents/demo/*`

Not yours: `agents/core/*`, `office/`, `mcp/`, `vectorstore/`,
`adapter/postgres/`. They have their own briefs. When a change in your
scope needs a port in a core module (steps 3.3, 3.4), coordinate through
the maintainer and make the core change its own small PR. When a rename
elsewhere breaks a caller in your scope, the other agent fixes it; when
your rename breaks a caller elsewhere, fix it mechanically in the same PR
and say so in the PR body.

## Goal

Controllers are thin adapters over application services and ports:
routing, request mapping, response rendering. Business rules (which agent
or tool override wins, how an attachment is ingested, what a seed does)
live in the application layer once, not in three controllers. A screen is
a `Controller` + a `Page` + `Component`s, the same shape in the admin UI,
the chat UI and the registry UI. For 1.0 the code must have one
responsibility per class, short methods (aim under 30 lines) and classes
(aim under 300), names after the pattern a class implements (Controller,
Page, Component, Policy, Converter, Renderer, Registry, Resolver, Probe,
Runner), singular class names, one constructor per class, and javadoc that
says what and why in one paragraph without history.

## Ground rules for every PR

1. One topic per PR. A rename PR renames. A split PR splits one class. A bug
   fix PR fixes one bug with its test. Never mix.
2. A verifiable goal before the change: a failing test for a bug; the
   module's test suite green before and after a structural change with the
   test sources untouched; for a UI split, the manual test session in
   `agents/doc/manual-tests` for the screen you touched.
3. Surgical. Match the surrounding style, do not reformat, do not improve
   unrelated code. Dead code you find on the way goes into the PR body as a
   note, unless the PR is the dead-code PR.
4. Minimum code. No abstraction for one caller, no configurability nobody
   asked for, no catch for a case that cannot happen.
5. Javadoc: present tense, what and why, one paragraph. No "It used to live
   on …", no "no longer extends … intentionally absent", no 58-line blocks
   on a properties class. Design rationale goes to `agents/doc/`.
6. Imports, never inline fully qualified names.
7. One constructor per class unless a second one has a real second caller.
8. Build with `mvn -f agents/pom.xml clean install` (or the touched module
   after its dependencies) and read the `Tests run:` / `Skipped:` counts.
   Run the admin UI (`mvn -f agents/server/mc-agent-admin-ui-app/pom.xml
   spring-boot:run`, needs `MINDCONNECT_ENCRYPTION_SECRET_KEY`) for every
   screen you split and click through it.
9. Changelog: a bug fix, a removal or a changed endpoint gets a fragment
   `changelog.d/<fixed|removed|changed>/<branch-slug>.md` with
   `- **agents:** **what is different.** explanation…`. Structural work
   (renames, splits, moves) gets the PR label `no-changelog` instead.
10. Everything in the repository and on GitHub is English. No
    `Co-Authored-By` trailer.
11. Branch from `next` as `feature/server-<topic>`, run
    `./after-branch-creation.sh` once, open the PR against `next`, one PR
    per step below.

## Decisions already taken

- **Converter** is the name for a class that maps an object to a
  representation and back in one class. One direction only is a
  Serializer/Writer or a Parser/Reader; object to object is a Mapper;
  object to something a person looks at is a Renderer.
- **Definition : Instance** is the vocabulary for "what is configured" vs
  "what runs".
- **Spring wiring lives in `springstarter/`** (pending Q-04). Wiring that
  other agents move out of core modules lands in your starters; expect
  those PRs and review them for the shape below.

## Questions to ask the maintainer before the step that needs them

Ask briefly, in one message, as soon as you start; continue with the steps
that do not depend on the answer.

- Q-17: Does `chat-ui-rest` stay a sibling of `api-rest` over the same
  services, or will the chat UI call the REST API like the CLI does? That
  decides whether attach-and-ingest and the stream frame become shared
  services or shared DTOs.
- Q-18: Are the builder's `property()` string keys (`tavilyApiKey`,
  `codeExec*`, `vectorStore*`) public API for 1.0? If yes they need a typed
  home before the release freezes them.
- Q-19: Must every runtime bean stay injectable for extensions, or can the
  starter stop re-exporting 38 beans one by one?
- Q-20: Which seeding path is canonical for the CLI: the admin's
  `BundledSeeds` / `NamespaceSeeder`, or its own loader with interactive
  overwrite?
- Q-21: `AdminSameUrlFilter.LEGACY_SECTIONS` keeps a list of ten admin
  sections. Is the "every section is an SPA route" migration still intended
  before 1.0, or is the filter permanent?
- Q-01: Is the Spring-free `AgentRuntimeBuilder` the only supported
  assembly for 1.0 (then `registerCore` may move into `CoreFeature` and the
  shortcuts for feature-owned keys may go)?

## Work, in order

### Step 1: hygiene (one PR per module group, several commits of one kind each, label `no-changelog`)

1. Inline fully qualified names → imports. Worst: `ChatUiController:66-77`
   (`ai.mindconnect.chatui.service.ActiveStreams activeStreams`,
   `java.util.LinkedHashMap<String, LiveTask>`), `VectorStoreUiController`,
   `AgentRuntimeBuilder`, `NamespacesPage:163`, `ProfilePage:97,494`,
   `LlmConfigFormComponent:290`, `UserToolsComponent:42`,
   `LlmConfigUiController:652`.
2. Orphaned and historical javadoc: blocks with no declaration after them at
   `ChatUiController:118-124`, `:611-632`, `:682-692`; "It used to live on
   the settings component…" `ChatUiController:644-650`; `AgentClient:4-9`
   ("no longer extends… intentionally absent");
   `VectorStoreUiController:196-209`; essays in `BrandingProperties:11`
   (58 lines), `StreamEvents:15` (36), `SecurityConfig:54` (35),
   `NamespaceOnboarding:20` (32); 40–46 % comment share in `StreamBus`,
   `MessageListComponent`, `ChatFormComponent`, `ChatPage`. Keep one
   sentence of contract; move rationale to `agents/doc/`. (SV-22, SV-24)
3. Dead code: `ChatUiController.sendPatch` (:2385, no caller); after Q-21
   `AdminSameUrlFilter.LEGACY_SECTIONS` (:41) and the migration it waits
   for. (SV-22)
4. Telescoping constructors: `ProfilePage` (5, :115-176), `AdminLayout` (4,
   :84-118), `NamespacesPage` (:142-213), `TaskMonitor` (2, :146, :153),
   `ScopeBindingFilter` (3, :60, :64, :68). Keep the fullest; a model record
   where the argument list is long (step 5.9). (SV-17, SV-18, SV-28)
5. Copied controller plumbing: the identical `dialog(...)` helper in
   `ProfileUiController:453`, `UserToolUiController:294`,
   `NamespaceUiController:295`, `ConnectionUiController:273`,
   `LlmPriceUiController:203`, `UserMemoryUiController:115`,
   `ToolCatalogUiController:228`, `ChatUiController:1245` (17
   `"sui-dialogs"` literals) → one `DialogPatch.open(id, title, body)` /
   `close(id)` in a shared `ui` package. `userId(OidcUser)` defined in ten
   controllers (`TaskMonitorUiController:67`, `ProfileUiController:414`,
   `ChatUiController:820`, …; some return `String`, some `UserId`) although
   `chatui/service/SessionOwnership.userIdOf` (:55) exists; session
   ownership re-implemented in `ChatUiController.ownedSession:535`,
   `ChatFilesUiController.requireOwned:177`,
   `ChatVoiceUiController.ownsSession:122`, `StreamController.owned:179`,
   `SessionFilesApiController.owned:145`, `AgentApiController.owned:660`
   although `agentrest/auth/SessionAccess` exists → a `@CurrentUser UserId`
   argument resolver for the UI controllers (as `api-rest` already has) and
   `SessionAccess.requireOwned` everywhere; delete the private copies.
   `chatui/.../controller/FormBody` is used by 12 admin-ui files → shared
   `ui.form` package together with `DialogPatch` and `VersionedForms`.
   (SV-06, SV-07, SV-11)
6. Two `@ControllerAdvice`s for one exception (`chatui/UploadLimitUiAdvice`
   vs `agentrest/UploadLimitAdvice`, ordered HIGHEST/LOWEST, one delegating
   by URL sniffing) → one advice that picks JSON vs toast by the request's
   `Accept`. (SV-26)
7. `FileUploadFeature:31` is the only feature that `implements
   RuntimeFeature` directly; the others extend `ConfigurableFeature` even
   without settings (`SubAgentsFeature`). One shape. (SV-27)
8. Long render methods: `AgentFormComponent.render` (:73, 138 lines),
   `ToolFormComponent:113` (76-line `if`), `StreamEventFrame:42` (78-line
   switch) → one private method per field group or event kind. (SV-23)

Verification: every module builds and its tests pass with unchanged test
sources; the admin UI and chat UI click-through for the dialogs touched in
1.5.

### Step 2: bugs (one PR each, failing test first, changelog fragment under `fixed`)

1. Attach-and-ingest is implemented twice and already diverges:
   `api-rest/service/SessionFileService.ingest` (:264, 132 lines)
   hard-codes `"embeddings"` (:300) while
   `feature-file-upload/AttachSupport.attachStored` (:97) reads
   `vectorStoreEmbeddingConfig`. Until step 5.2 merges them, make the REST
   path read the configured embedding config too. (SV-02)

### Step 3: module seams (one PR each, label `no-changelog`)

1. `mc-agent-security/pom.xml` depends on `mc-agent-api-rest` solely for
   `agentrest.auth.CurrentUserResolver`: the security module depends on a
   REST module. Move `CurrentUserResolver` / `CurrentUser` to `mc-user-core`
   or a small `mc-agent-web-auth` module. (SV-10)
2. Controller-to-controller injection: `ProfileUiController:80` injects
   `UserToolUiController`, `:84` `UserMemoryUiController`;
   `AgentUiController:65` injects `MemoryAdminUiController` to render tabs.
   Extract `UserToolRowsComponent`, `MemoryTabComponent` that both use.
   (SV-12)
3. The only adapter imports in the server scope:
   `LlmConfigUiController:58-59` and `LlmConfigFormComponent` import
   `llm.adapter.lmstudio.LmStudioModelCatalog` and
   `llm.adapter.ProviderModelCatalog`; `LlmConfigTestService:35` uses
   `RoutingLlmChatService`; `LlmPriceUiController` uses
   `llm.service.LlmPriceService`. A `ModelCatalog` port in
   `mc-llm-gateway-core` (core PR via the agents-core agent) and `LlmChat`
   instead of the routing implementation. (SV-09)
4. `AgentRuntimeAutoConfiguration:258-297` re-exports 38 beans one line
   each with casts to implementations (`RoutingLlmChatService` :289,
   `LocalTaskQueue` :293); the runtime bean method (:113) is 85 lines of
   property plumbing. After Q-19: `@ConfigurationProperties` records per
   feature (`MindconnectToolsProperties`, `TaskQueueProperties`, …) and a
   `BeanDefinitionRegistryPostProcessor` that exports whatever
   `RuntimeBeans` holds, so a new feature bean needs no starter edit.
   (SV-14)
5. `ChatPage:44`, `NamespacesPage`, `DungeonPage` do not extend `AdminPage`
   while every other admin page does, and `AdminPage`'s javadoc cites chat
   methods (`chatTurnComplete()`, `streamToken(...)`). One base (or none)
   with the same contract in both UI modules. (SV-19)
6. `starter-namespace/ScopeBindingFilter:46` (290 lines): static path-rule
   lists (:77, :87) and the binding logic in one filter → a
   `NamespaceRequestPolicy` (which paths need a namespace, API prefixes) +
   the filter. (SV-28)

Verification: an ArchUnit test that fails on a server module importing
`*.adapter.*` from a core module and on a controller injecting another
controller.

### Step 4: renames (one PR per module group, one commit per rename, IDE refactoring only, tests untouched, label `no-changelog`)

Registry: `ActiveStreams` → `ActiveStreamRegistry`, `SessionStreams` →
`SessionStreamRegistry`, `TranscriptionChannels` →
`TranscriptionChannelRegistry`, `BundledSeeds` → `BundledSeedCatalog`.

Resolver / Provider / Policy: `ToolVariables` → `ToolVariableResolver`,
`ToolConnections` → `ToolConnectionResolver`, `CurrentUsers` →
`CurrentUserProvider`, `HostNamespaces` / `BrandHostNamespaces` →
`HostNamespaceResolver` / `BrandHostNamespaceResolver`, `ChatHostLinks` /
`AdminChatHostLinks` → `ChatHostLinkProvider` / `AdminChatHostLinkProvider`,
`UserPaths` → `SelfUserPathPolicy`, `VersionedForms` → `FormVersionPolicy`.

Converter / Renderer / Mapper: `InputParts` → `InputPartMapper`,
`StreamEvents` → `ResponsesStreamEncoder`, `ResponsesErrors` →
`ResponsesErrorHandler`, `RegistryNotes` → `RegistryNoteRenderer`,
`SessionUiCommons` → dissolve into `TextPreview` plus the shared
mapper/formatter inlined.

Service / Runner / Probe: `AttachSupport` → `AttachmentIngestion`,
`SessionFileService` → `SessionAttachmentService`, `LlmConfigTestService` →
`LlmConfigProbe`, `ToolTestService` → `ToolProbe`, `TranscriptionJobService`
→ `TranscriptionJobRunner`, `MigrationService` → `SeedMigrationService`,
`NamespaceMembers` → `NamespaceMembershipService`.

Value objects: `SeenChats` → `SeenChatLog`, `UserTools` (adminui/setup) →
`UserToolAccount`, `GuardedRepositories` → `NamespaceWriteGuarded`,
`VectorStoreUiController.FileUsages` → `FileUsageIndex`.

UI naming: `RegistryBrowseView` and its four `*View` siblings → `*Component`;
`chatui/ui/component/Thoughts`, `ToolCallHistory` → `ThoughtsComponent`,
`ToolCallHistoryComponent`.

Verification: a grep for every old name returns nothing in code and docs;
every module builds.

### Step 5: god classes and duplication (one PR per class, in this order; stop after each for review)

1. **`ChatUiController`** (2494 lines, 15 constructor dependencies). Owns
   the chat shell, the settings dialog, the directory picker (8 endpoints,
   :323-449), the tool and sub-agent pickers (:919-1160), session CRUD, the
   live SSE turn rendering (`runTurnStream` :1688-1987, ~300 lines, with the
   inner `LiveThinking` / `LiveTask` state machines :1987-2140) and the
   chat's domain defaults (`DEFAULT_TOOLS`, `CHAT_SYSTEM_PROMPT` :651-677).
   Split into `ChatShellController` (home, get, create, rename, delete),
   `ChatSettingsController` (settings, agent switch),
   `WorkingDirPickerController`, `ChatToolPickerController` (tools,
   sub-agents, sub-agent test), `ChatTurnController` (chat, regenerate,
   approval, cancel) and a non-web `LiveTurnRenderer` (turn events →
   `UiPatch`es on a `StreamBus`). The rules for which agent or tool override
   wins (`applySettings` :237-303, `replaceTools` :1196-1244, `pickTools`
   :710) move into a `ChatAgentOverridePolicy` in the application layer.
   Do it in several PRs, one controller extracted per PR, the original
   shrinking each time. (SV-01)
2. **Attach-and-ingest, once** (after Q-17). The flow (image shortcut,
   `"session-" + id` store, `chat-uploads` template, indexed-or-direct-or-
   workflow ingestion, tool activation, record on session) exists in
   `SessionFileService.ingest` and `AttachSupport.attachStored`. Keep one
   `AttachmentIngestion` in the file-upload feature (it is runtime
   behaviour, not REST); `SessionFileService` delegates; delete the second
   copy. (SV-02)
3. **`VectorStoreUiController`** (1152): 89 `Ui*` constructions, no Page or
   Component; `list(tab)` (:92-231) builds four tables, an upload zone and
   tab headers inline; `storeDetail` has four overloads (:772-862); it
   bypasses `VectorStoreService` and talks to `VectorStores`, `FileStore`,
   `AgentSessionRepository` directly (:57-65). Split into
   `VectorStoreUiController` + `VectorStoreOverviewPage`,
   `TemplateFormComponent`, `IndexFormComponent`, `StoreDetailPage`
   (entries, chunks, search), `StoredFilesComponent` +
   `FileUsageDialogComponent`; route every read and write through
   `VectorStoreService`. (SV-03)
4. **`CliRunner`** (1456): `run` (:57-265, 208 lines) is an if-chain over
   18 slash commands; `streamAgentResponse` (:519-715) renders every event
   kind with array-boxed mutable flags; `printToolCallHistory` (:972-1106)
   is 134 lines. Split into `CliSession` (loop + resume), a `SlashCommand`
   interface with one class per command in a `CommandRegistry`,
   `StreamRenderer` (ANSI output of `StreamEvent`s), `HistoryPrinter`,
   `CancelWatcher`. (SV-04)
5. **`AgentApiController`** (663): agents CRUD, session lifecycle, working
   directories, three SSE endpoints with hand-rolled framing (`streamTurn`
   :348, `sendFrame` :383, `endStream` :392), user stream, approvals. Split
   into `AgentDefinitionApiController`, `SessionApiController`,
   `SessionStreamApiController` (+ an `SseFrameWriter` shared with
   `TranscriptionApiController.send` :214 and `ResponsesController.stream`
   :83), `ApprovalApiController`. (SV-05)
6. **Stream frame, once** (after Q-17). `RemoteAgentClient.decodeFrame`
   (:177-283) hand-decodes what `agentrest/dto/StreamEventFrame.from`
   (:41-120) hand-encodes; both switch over every `StreamEvent` kind. One
   `StreamEventFrameConverter` (encode + decode) in a small
   `mc-agent-api-dto` module used by server and CLI. (SV-21)
7. **`LlmConfigUiController`** (673) + **`LlmConfigFormComponent`** (689).
   Form → domain mapping (`retryFrom`, `additionalParamsFrom`,
   `capabilitiesFrom`, `rateLimitFrom`, `apiKeyFor` :296-320, :602-673) and
   the secret-handling rules sit in the controller; `fieldGroups`
   (:169-246) re-assembles the form through the component's `public static`
   factories (:140-620), so the form is split over two classes. Extract
   `LlmConfigFormMapper` (form ↔ `LlmConfig`); the component owns all its
   groups as instance methods; the controller routes. (SV-08)
8. **`AgentRuntimeBuilder.registerCore`** (:438-560, 122 lines; after
   Q-01, Q-18). Wires 15 services with 10-argument constructors although
   `CoreFeature` exists for "LLM + messages + agents"; :335-371 exposes
   typed shortcuts for string keys owned by optional features
   (`tavilyApiKey`, `codeExecutionRuntime`, `vectorStoreBackend`, …). Move
   the turn-loop wiring into `CoreFeature.installTurnLoop` (or a
   `TurnLoopFeature`); the builder stays factory + feature registry; replace
   the shortcuts by `feature(ToolsFeature.class).tavilyApiKey(...)`. (SV-13)
9. **Pages as parameter bags.** `ProfilePage` (569): five constructors over
   17 fields (:115-176) plus `public static` form and table factories
   (:237-553) that controllers call independently. A `ProfileModel` record
   + `ProfilePage(ProfileModel)`; the factories become
   `ApiTokenFormComponent`, `NamespaceInviteFormComponent`,
   `EnvironmentFormComponent`. Same shape in `NamespacesPage` (:142-213)
   and `AdminLayout` (:84-118). (SV-18)
10. **Seeding and migration** (after Q-20). Five overlapping classes:
    `adminui/InitialDataLoader` (73), `cli/InitialDataLoader` (206, its own
    parsing and diff, not using `BundledSeeds` nor `common/mc-initial-data`),
    `adminui/service/NamespaceSeeder`, `NamespaceSeeding`, `BundledSeeds`,
    plus `MigrationService`. `BundledSeeds` + `NamespaceSeeder` move next to
    `mc-initial-data`; the CLI reuses them (its confirm-overwrite callback
    becomes a `SeedConflictPolicy`). `MigrationService` (431): the
    `EntityType` enum plus a `pendingXxx` / `applyXxx` / `bundledXxx` trio
    per kind (:149-356), generic JSON diffing (:391-430) and secret masking
    (:359-390) → a `SeedMigration` strategy per kind (`LlmConfigMigration`,
    `AgentMigration`, `SkillMigration`, `WorkflowMigration`), a
    `JsonFieldDiff`, and `MigrationService` only orchestrating. (SV-15,
    SV-16)
11. **`TaskMonitor`** (397): its own javadoc says "three parts, one class"
    (observation, delivery, ownership). `TaskSnapshotPublisher` (listener +
    debounce + channel), `TaskOwnership` (may-cancel rules), `TaskMonitor`
    as facade. (SV-17)

Verification for every split: the module's tests unchanged and green; the
old class is gone or a thin facade; no new class above ~300 lines and no
method above ~50; the screen clicked through in the running app, the chat
test session in `agents/doc/manual-tests` for items 1, 2, 4 and 6.

### Step 6: docs

`agents/README.md`, the module list in `CLAUDE.md` and
`website/docs/agents/**` updated in the same PR as every rename, move or
removal; the REST API page after step 5.5, the CLI page after step 5.4.

## When you are unsure

Ask the maintainer in one short message with the options and your
recommendation. Do not pick silently where two readings lead to different
code. Do not widen a PR to fix something you noticed; note it in the PR body.
