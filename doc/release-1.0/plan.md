# Plan for the 1.0 release

Companion to [review.md](review.md). Finding ids (`RT-01`, `Q-03`, …) refer
to it.

## Ground rules for every step

Taken from Karpathy's `CLAUDE.md` and from the review criteria. They apply to
every PR in this plan.

1. **One topic per PR.** A rename PR renames. A split PR splits one class. A
   bug-fix PR fixes one bug with its test. Never mix a rename into a split.
2. **A verifiable goal before the change.** A bug gets a failing test first.
   A split or rename gets the area's test suite green before and after, and a
   diff a reviewer can read in one sitting.
3. **Surgical.** Match the surrounding style. Do not reformat, do not
   "improve while here". Dead code found on the way is listed, not deleted,
   unless the PR is the dead-code PR.
4. **Minimum code.** No new abstraction for one caller. No configurability
   nobody asked for. No catch for a case that cannot happen.
5. **No behaviour change in a structural PR.** Splits, moves and renames keep
   every test as it is; a test that has to change is a sign that the PR
   changed behaviour.
6. **Javadoc: what and why, one paragraph, present tense.** No history, no
   concept numbers, no restating of the signature. If a class needs an essay
   to be understood, the essay is a finding, not a comment.
7. **Build the touched area**, run its tests, read the `Tests run:` and
   `Skipped:` counts (a green run may mean skipped).
8. **No changelog fragment** for structural work (label `no-changelog`); a
   fragment for every bug fix in phase 2 and every removal in phase 3, since
   a user may be living with the symptom or depending on the module.

## Phase 0: decisions and conventions

Nothing in later phases is blocked on all of these, but every rename and
every move depends on at least one. Collect the answers to the thirty
questions in review.md §6, then write the conventions into `CLAUDE.md` and
`AGENTS.md` so that every later PR (by a person or an agent) follows them:

- Module layout: `-core` = domain + ports (+ services, see Q-02); sibling =
  adapters; `adapter/postgres/mc-*-pg` = Postgres; `springstarter/` = all
  Spring wiring (Q-04). No `adapter` package inside `-core`.
- One word for persistence (Q-03) and one for the driving side (Q-02).
- Class names: singular, named after the role (Registry, Catalog, Resolver,
  Policy, Codec, Renderer, Factory, Repository, Adapter, Strategy, Worker).
  No plural, no Helper/Util/Support/Commons/Holder, `Service` only for an
  application service with one use case family.
- One constructor per class (Q-01). Overloads only for a real second caller.
- Imports, never inline fully qualified names.
- Javadoc rule from ground rule 6.
- Size guide: methods under ~30 lines, classes under ~300. Not a hard limit;
  a reason to look.

**Verification:** `CLAUDE.md` diff reviewed by the maintainer; the answers
recorded in review.md §6.

Effort: half a day of the maintainer's time plus the write-up.

## Phase 1: hygiene sweep (mechanical, low risk)

One PR per area (`agents/core`, `agents/server+client+builder`, `workflow`,
`taskqueue`, `common`), each a sequence of small commits of one kind:

1. Inline fully qualified names → imports (X-03; RT-12, GW-19, TQ-10, SV-25).
2. Stale, orphaned and misplaced javadoc (X-01 part 1; RT-10, GW-21, GW-23,
   TQ-09, TL-29, WF-21, SV-22 comments). A short script that finds a javadoc
   block not followed by a declaration, and one between `@Override` and a
   method; add it to CI once the sweep is done.
3. "Concept NN" references and history paragraphs (X-01 part 2; RT-11, GW-28,
   TQ-08, CM-12, SV-24): replace by the sentence the concept stands for; move
   what is worth keeping into `agents/doc/`, `workflow/doc/` or
   `website/docs/`.
4. Dead code (X-09; RT-09, GW-16, GW-25, WF-08, WF-22, TQ-07, TL-30, SV-22):
   delete, one commit per module.
5. Telescoping constructors (X-04; RT-04, TL-09, SV-18, SV-28): delete the
   unused ones; tests build the full argument list.
6. Impossible-case catches (X-05; GW-26, RT-26).
7. `java.util.logging` → SLF4J; one injected `ObjectMapper` (X-10; RT-16,
   GW-18); unused `spring-context` dependencies (RT-15, GW-05); the double
   space in `Sql` (GW-29); NUL literals (X-11).
8. Copy-pasted helpers with an obvious home: `dialog()` (SV-06), `userId()`
   and ownership checks (SV-07), `FormBody` location (SV-11),
   `SessionAgentResolver` injection (RT-18), `AtomicFiles`/`FileWrites`
   (CM-02), `BaseDirs`/`DocBaseDirs` (TL-01 after Q-12), home-path expansion
   (TL-26), the Pg javadoc and `final` (GW-24), the 17 `initSchema` sentences.

**Verification:** every area builds and its tests pass with unchanged test
sources; `git diff --stat` shows only the kinds of change the commit names;
the javadoc script finds nothing.

Effort: two to three days across all areas. Risk: low.

## Phase 2: bugs found by the review

Each one: failing test, fix, changelog fragment under `changelog.d/fixed/`.

| Finding | Bug |
|---|---|
| WF-10 | `CallWorkflowStep.init` mutates the shared definition and appends steps on every instantiation; a called workflow runs twice on a second run. |
| WF-23 | `ScriptExecutor` shares one `ScriptEngine` per language and mutates its scope; not thread-safe under parallel for-each, which also opens an unbounded thread pool per loop. |
| WF-22 | `VariableScope.VariableHistory` grows on every assignment and is never read. |
| WF-01 | `spel:` is documented but evaluates to the literal string (after Q-22: remove or implement). |
| TQ-13 | `InMemoryChannelStore.lastSeq` restarts at 0 after a purge, against the port's contract. |
| TQ-12 | `ChannelRegistry.withIdleEviction` leaks its sweeper thread. |
| TQ-05 | JDBC stores discard the `SQLException` cause and mix exception types. |
| GW-17 | `ConversationService.deleteMessages` miscounts beyond 500 messages. |
| GW-22 | `OpenAiMapper.incompleteReason` ignores its argument. |
| CM-04 | `WebScraperResults.addFailed` adds to the success list; `WebScraperOptions.clone` copies `removeHtml` into `removeNewLine`. |
| CM-07 | `Page` is 1-based, `PageRequest` 0-based. |
| WF-19 | `JsonWorkflowConfigurer` is not null-safe although its javadoc says so. |

**Verification:** the new test fails on `next`, passes on the branch.

Effort: one to two days. Risk: low per fix; WF-23 needs a concurrency test.

## Phase 3: module seams (hexagonal)

Moves between modules and packages; no class changes shape. Each move is its
own PR. Order matters where a later move depends on an earlier one.

1. **Ports into core modules.** `McpServerRepository`, `McpDiscoveryStore`,
   `McpStoreFactory` → `mc-mcp-gateway-core` (TL-05).
   `StoredUserToolRoster` → `mc-user` (GW-13). `mc-extension` runtime
   adapters → `adapter.runtime` (GW-14). Memory-strategy references to
   runtime `service` → `BuiltInTask`, `ContextTokenBudget` to `memory/domain`
   (RT-27). Workflow `SnapshotSerializer` → `persistence.json`, one package
   name for persistence (WF-18). `CodeStepException` → `scripting` (WF-05).
2. **Postgres adapters into `adapter/postgres/`.** `PgViewStore`,
   `PgMailIndexStore` (TL-06). Feature-owned Pg wiring into the postgres
   starter (GW-27, after Q-11).
3. **Spring wiring out of core modules** (after Q-04): `mc-agent-tools-workflow`
   auto-configurations and registry installer (TL-03), virtual-env
   auto-configuration and JWKS controller (TL-25), `McpStartPolicy.from(Environment)`
   (TL-24), `OpenAiCompatibleConfig` (GW-05).
4. **Dependency direction.** `mc-agent-security` → `mc-agent-api-rest` only
   for `CurrentUserResolver`: move the resolver (SV-10). Server imports of
   adapter classes: a `ModelCatalog` port, `LlmChat` instead of
   `RoutingLlmChatService` (SV-09). Calendar tools' dependency on mail tools
   and the split package (TL-14, TL-15). Mail/calendar core's dependency on
   tool-spi (TL-19, after Q-15). Document helpers into `mc-document-core`
   (TL-12).
5. **`common/` cleanup.** Delete `mc-file-manager` (CM-05, after Q-30).
   Purge `mc-webscraper` of customer data, mains, logging backends (CM-03,
   after Q-29). `LoggingContext` → `mc-agent-domain`, `McEnv` → demos,
   `Cancellation` javadoc (CM-06). `mc-task-queue-jdbc` on `mc-jdbc`
   (TQ-04, after Q-27). `SharedLockStore` and the schedule DDL: ship the JDBC
   adapter or drop the port (TQ-06, TQ-07, after Q-28).
6. **`mc-credentials` core split** (GW-12, after Q-10).
7. **Scope machinery out of `mc-agent-domain`** (RT-25, after Q-06).

**Verification:** an ArchUnit test module (or tests in each `-core`) that
fails on: Spring or Jackson databind imports in a `-core` module; an
`adapter` package in a `-core` module; an adapter importing another adapter
module; a server module importing `*.adapter.*`. Write the test first, watch
it list today's violations, and make the list empty by the end of the phase.

Effort: four to six days. Risk: medium (Maven coordinates change; users of
the moved classes need the new artifact).

## Phase 4: renames

After phase 3, so that a class is renamed once in its final module. One PR
per area, one commit per rename, IDE refactoring only, tests untouched. The
list is review.md §5; the order inside an area is: ports first (they appear
in the most signatures), then services, then adapters, then UI.

The workflow vocabulary is decided (WF-20): `…StepDefinition` for what the
user writes, `…StepInstance` for what runs, `WorkflowDefinition` /
`WorkflowInstance` at the top, the same Definition : Instance pair the agents
area uses. It lands in three PRs in this order: short JSON type aliases instead
of `Id.CLASS`, resolved through the core `StepTypeRegistry` by a
`StepTypeIdResolver` in `mc-workflow-jackson` (with a pre-pass for the old
`@class` values), `getType()` as the alias from the registry, then the rename itself (executors first, definitions
second, so that no name ever changes meaning).

The remaining public-API renames need the maintainer's yes: `ConversationManager` (Q-02),
`Repository` vs `Store` (Q-03), `AuthenticationInfo`, the protocol API
interfaces (`Sessions`, `Files`, …), `WorkspaceFiles`.

**Verification:** `grep -rn` for the old name returns nothing (code, docs,
`website/`); every area builds; the plural-name check (a script over
`find … -name '*s.java'` with the known exceptions) lists nothing new.

Effort: three to four days. Risk: low technically, high for API users: the
release notes list every renamed public type with its old name.

## Phase 5: god classes

One class per PR, in this order (value first, then risk):

| # | Class | Finding | Split into | Effort |
|---|---|---|---|---|
| 1 | The four LLM gateways | GW-01..04, GW-30 | `SseChatGateway` + `ProviderWireFormat` per provider, `ToolCallAssembler`, `LlmCallTraceRecorder`, `ProviderHttpError`; one model catalog | L |
| 2 | `AgentChatService` | RT-01, RT-03, RT-24 | `ChatTurnSubmitter`, `ApprovalService` (+ `ApprovalGate`), `SessionStreamAttachment`, `MemoryService` | L |
| 3 | `AgentSessionService` | RT-02 | `SessionLifecycle`, `SessionDirectoryPolicy`, `SessionChain` | M |
| 4 | `AgentTurnWorker`, `ToolCallWorker` | RT-05, RT-19, RT-21, RT-22 | `TurnAssembly`, `TurnEnding`, `ToolTaskPayload`, `SystemPromptComposer` | M |
| 5 | `SessionDirectories` | RT-07 (after Q-05) | one implementation over `WorkspaceFiles` | L |
| 6 | `ChatUiController` | SV-01 | five controllers, `LiveTurnRenderer`, `ChatAgentOverridePolicy` | L |
| 7 | `WorkflowAdminUiController` | WF-02, WF-26 | eight classes as listed | L |
| 8 | `VectorStoreUiController` | SV-03 | pages and components, all through `VectorStoreService` | L |
| 9 | `CliRunner` | SV-04 | `CliSession`, `SlashCommand` + registry, `StreamRenderer`, `HistoryPrinter` | M |
| 10 | `AgentApiController` | SV-05 | four controllers + `SseFrameWriter` | M |
| 11 | `PumlWorkflowParser` | WF-03, WF-04 (after Q-23) | tokenizer + one parser per construct | L |
| 12 | `WorkflowRunService`, `WorkflowDiagramBuilder` + `BuildContext` | WF-17, WF-16, WF-15 | as listed | M each |
| 13 | `LocalTaskQueue`, `JdbcTaskStore` | TQ-02, TQ-03 | as listed | L each |
| 14 | `MiniScriptEngine` | CM-01 | lexer, parser, AST, evaluator, invoker, built-ins | M |
| 15 | `BashTool`, `GlobTool`, `GrepTool` | TL-07, TL-08 | `ShellCommandInspector`, `CappedOutput`, `WalkBudget`, collectors, `ClosestNameSuggester` | M |
| 16 | `DocumentReader`, `Mailbox`, `ICalendar` | TL-10, TL-17, TL-20 | per-format parsers; `ImapConnection`; reader/writer/patch | M each |
| 17 | `DefaultVectorStores`, `VectorTools` | TL-02, TL-21 | configuration, opener, embedder; one tool + factory per class | L |
| 18 | `NamespaceService`, `RegistryService`, `AgentRuntimeBackend`, `OpenAiResponsesBackend` | GW-08..11 | as listed | M each |
| 19 | `LlmConfigUiController` + form, `ProfilePage`, `MigrationService`, `TaskMonitor`, `AgentRuntimeBuilder.registerCore`, the starter's bean export | SV-08, SV-18, SV-16, SV-17, SV-13, SV-14 | as listed | M each |
| 20 | `AgentDefinition`, `AgentSession`, `LlmConfig`, `ToolRegistry`, `SpiToolRegistry`, `DocumentTable`, `EncryptionHelper`, `WorkflowDesignerFrame`, `WorkflowInstanceSnapshots` | RT-20, GW-07, RT-23, RT-30, CM-10, CM-11, WF-25, WF-12 | as listed | M each |

**Verification per PR:** the area's tests unchanged and green; the old class
is gone or is a thin facade; no new class above ~300 lines and no method
above ~50 (the script from review.md §2 run on the diff); for the gateways,
the existing wire-JSON tests pass against the shared skeleton.

Effort: the bulk of the work, roughly four to six weeks for one person.
Risk: medium; items 1, 2, 6, 7, 13 touch behaviour users see every day and
want a manual test session each (`agents/doc/manual-tests`).

## Phase 6: duplication that is not a god class

| Finding | Consolidation |
|---|---|
| RT-06 | One `ToolCallContent` / `ToolResultContent` codec beside `Message`; remove the five ad-hoc parsers and the `ObjectMapper` from `FeatureContext`'s API. |
| RT-08 | `SummaryRenderer`, `ConversationSummarizer`, `ContextTokenBudget.unlimited`. |
| WF-13, WF-14, WF-24 | `StepTree` walker; `StepTypeRegistry` with one `StepType` descriptor; adding a step type touches the registry and the step. |
| WF-07, WF-06, WF-09 | `ScriptLanguageConfigurer` base; typed `ScriptExecutor` field; one expression evaluator for leaf and container steps. |
| SV-02 | One `AttachmentIngestion` (after Q-17). |
| SV-21 | `StreamEventFrameCodec` shared by server and CLI (after Q-17). |
| SV-15 | One seeding path (after Q-20). |
| SV-26 | One upload-limit advice. |
| TQ-01, TQ-11 | `mc-taskqueue-demo-ui`; stateless board renderer. |
| TQ-03 | `TaskTree.purgeable` shared by both task stores. |
| TL-14, TL-27 | `ToolSchema` / `ToolArguments` in the SPI; `close()` on `ToolFactory`, no static singletons. |
| TL-28 | Four document tools instead of six (prompt change: announce in the changelog). |
| CM-09 | `PathWalker` + `PropertyReader`. |
| TQ-14, TQ-15 | `TaskSchedule.copy`; `countByStatus` and `nudge()` on the ports. |

**Verification:** the duplicate is deleted, not deprecated; tests of both
former copies run against the one implementation.

Effort: two to three weeks. Risk: medium.

## Phase 7: release gates

Done last, kept forever:

1. **ArchUnit tests** from phase 3 in every area's test run.
2. **Javadoc lint** from phase 1 in CI.
3. **Size check** in CI as a warning, not a failure: methods over 50 lines,
   classes over 500 lines, reported on the PR.
4. **Plural-name check** from phase 4 as a warning on the PR.
5. **Docs in sync**: READMEs and `website/docs` grep clean for every renamed
   or removed type and module (the `spel:` examples, the Nashorn mention,
   `mc-workflow-api`, `mc-file-manager`).
6. **Release notes**: a "Renamed and removed in 1.0" section assembled from
   the rename commits (old name → new name, old artifact → new artifact).
7. **Version**: `revision` 0.8.5 → 1.0.0 in the root POM and
   `mc-java-parent`, `./after-branch-creation.sh` re-run on every checkout.

**Verification:** a fresh clone builds from the root aggregator with tests;
the changelog preview (`.github/scripts/changelog-release.sh preview`) lists
every fragment from phases 2 and 3; the docs site builds.

## Sequence and estimate

| Phase | Depends on | Effort (one person) |
|---|---|---|
| 0 Decisions | maintainer | 1 day |
| 1 Hygiene | 0 (partly) | 3 days |
| 2 Bugs | nothing | 2 days |
| 3 Seams | 0 | 5 days |
| 4 Renames | 3 | 4 days |
| 5 God classes | 3, 4 for the touched names | 4–6 weeks |
| 6 Duplication | 5 (partly) | 2–3 weeks |
| 7 Gates | all | 2 days |

Phases 1 and 2 can start today and run in parallel with phase 0. Phases 5
and 6 can be interleaved and split among several people once phase 4 is in,
one PR per class. If time runs short, the order inside phase 5 is the
priority order: the gateways, the two runtime services, and the three UI
controllers carry most of the value.
