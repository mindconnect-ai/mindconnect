# Brief: prepare the tool modules, office, MCP and vector store for the 1.0 release

You are a coding agent working only on the tool-facing modules of the
`agents/` area of the Maven monorepo `mindconnect-ai/mindconnect`. Read
`CLAUDE.md`, `AGENTS.md` and `agents/README.md` first. The full review is in
`doc/release-1.0/review.md` (section 4.3, the `TL-` findings, is yours), the
overall plan in `doc/release-1.0/plan.md`. This brief is self-contained.

## Scope

- `agents/core/mc-agent-tools`, `mc-agent-tools-code`, `mc-agent-tools-document`,
  `mc-agent-tools-web`, `mc-agent-tools-web-browser`, `mc-agent-tools-workflow`,
  `mc-agent-tools-virtual-env`
- `agents/office/*`: `mc-mail-core`, `mc-mail-imap`, `mc-mail-views`,
  `mc-mail-index`, `mc-agent-tools-mail`, `mc-calendar-core`,
  `mc-calendar-caldav`, `mc-agent-tools-calendar`
- `agents/mcp/*`: `mc-mcp-gateway-core`, `mc-mcp-gateway-local`,
  `mc-mcp-proxy`, `mc-agent-tools-mcp`, `mc-mcp-gateway-admin-ui-rest`
- `agents/vectorstore/*`: `mc-vector-store`, `mc-vector-store-pgvector`,
  `mc-vector-store-tools`, `mc-file-store-core`, `mc-file-store`
- `agents/adapter/postgres/mc-mcp-gateway-pg`, `mc-file-store-pg`, and the
  new `-pg` modules you create in step 3

Not yours: `mc-agent-tool-spi` belongs to the agents-core agent; when you
need something added there (steps 3.2, 5.6, 5.7) coordinate through the
maintainer and make the SPI change its own small PR. `server/`, `builder/`,
`springstarter/` have their own brief. When a rename in your scope breaks a
caller elsewhere, fix it mechanically in the same PR and say so in the PR
body; do not refactor it.

## Goal

A tool module ships tools and depends on ports, never on adapters or on
Spring wiring. A provider seam (`MailProvider`, `CalendarProvider`,
`WorkspaceProvider`, `McpGateway`) stays small and data-only. For 1.0 the
code must have one responsibility per class, short methods (aim under 30
lines) and classes (aim under 300), one layout for a tool module (one
`Tool` + one `ToolFactory` per tool, a `MultiToolProvider` where a module
ships several), names after the pattern a class implements (Registry,
Catalog, Resolver, Policy, Converter, Renderer, Factory, Reader, Writer,
Validator), singular class names, one constructor per class, and javadoc
that says what and why in one paragraph without project history.

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
5. Javadoc: present tense, what and why, one paragraph. No "concept 21 §…",
   no "the email_… tools are gone", no "v0 … v1+", no javadoc that repeats
   the tool's `description()`.
6. Imports, never inline fully qualified names.
7. One constructor per class unless a second one has a real second caller.
8. Build with `mvn -f agents/pom.xml clean install` (or the touched module
   after its dependencies) and read the `Tests run:` / `Skipped:` counts;
   the pgvector and IMAP suites skip themselves without their service.
9. Changelog: a bug fix, a removal or a changed tool name gets a fragment
   `changelog.d/<fixed|removed|changed>/<branch-slug>.md` with
   `- **agents:** **what is different.** explanation…`. Structural work
   (renames of Java classes, splits, moves) gets the PR label `no-changelog`.
   A renamed **tool** (the name the model calls) is user-facing and always
   gets a fragment.
10. Everything in the repository and on GitHub is English. No
    `Co-Authored-By` trailer.
11. Branch from `next` as `feature/tools-<topic>`, run
    `./after-branch-creation.sh` once, open the PR against `next`, one PR
    per step below.

## Decisions already taken

- **Converter** is the name for a class that maps an object to a
  representation and back in one class. One direction only is a
  Serializer/Writer or a Parser/Reader; object to object is a Mapper;
  object to something a person looks at is a Renderer.
- **Definition : Instance** is the vocabulary for "what is configured" vs
  "what runs".
- **Spring wiring lives in `springstarter/`**, pending Q-04; until the
  maintainer answers, do not add any new `@AutoConfiguration` to a module in
  your scope.

## Questions to ask the maintainer before the step that needs them

Ask briefly, in one message, as soon as you start; continue with the steps
that do not depend on the answer.

- Q-04 / Q-13: May `core/` modules contain Spring auto-configuration, or is
  the rule "wiring only in `springstarter/`"? `mc-agent-tools-workflow`
  ships three `@AutoConfiguration`s and depends on `mc-workflow-admin-rest`
  and `mc-workflow-persistence-pg`; `mc-agent-tools-virtual-env` ships an
  auto-configuration and a `@RestController`; `mc-mcp-gateway-local` reads
  Spring's `Environment` in a record.
- Q-12: Is "each tool module independently deployable" (the reason given
  in `DocBaseDirs`) a hard requirement, or may shared helpers
  (`BaseDirs`, `FileRootedToolFactory`, `FileWalks.couldNotCheck`,
  `ToolSchema`, `ToolArguments`) live in `mc-agent-tool-spi`?
- Q-14: Tool-per-lambda (`OfficeTool`, `MailTools`, `CalendarTools`) or one
  `Tool` + `ToolFactory` class per tool (built-in modules) as the one layout
  for every multi-tool provider?
- Q-15: Are `ToolConnection`, `Connections`, `ConnectionSpec` tool
  vocabulary or domain vocabulary? Decides whether `mc-mail-core` and
  `mc-calendar-core` may depend on `mc-agent-tool-spi`, and where a
  `ConnectionProvider` SPI lives.
- Q-16: Will 1.0 still read pre-0.9 vector stores (`LegacyVectorStores`
  runs at every first namespace use), or can that be a one-off migration
  command?
- Q-03: `Store` or `Repository` as the one word for a persistence port
  (`MailStore`, `CalendarStore`, `ViewStore`, `MailIndexStore`, `EntryStore`,
  `FileStore`, `McpDiscoveryStore` vs `McpServerRepository`)?

## Work, in order

### Step 1: hygiene (one PR per module group, several commits of one kind each, label `no-changelog`)

1. Inline fully qualified names → imports, whole scope.
2. Javadoc that is changelog, stale or internal: `mail-core/MailStore:7-26`
   ("whichever of the three providers", "§5.2 of the concept moves it …
   into a shared mc-mail-core": it is already there),
   `mail-imap/MailboxConnectionProvider:17-19` ("The email_… tools are
   gone"), `MailSender:9-11` (`MC_SMTP_HOST`), `MailText` (two consecutive
   javadoc blocks on `Extract`), `mcp-gateway-core/McpGateway:13`,
   `McpTarget:13,51` ("concept 21 §…"), `mcp-proxy/McpSessionRegistry:23-31`
   ("v0 deploys … v1+ concern"), `builtin/GlobTool:23-44` (repeats
   `description()`), `web/WebToolsHttpClient:7-28` (24 lines on why it is
   not a Spring bean). Present tense, one paragraph, no concept pointers.
   (TL-29)
3. Dead or test-only API: `MailListViews.standard(…)` (both overloads, no
   main caller), `MailStore.list(folderId, skip, limit, boolean, String)`
   default (no caller), `DocumentParser.parseString` 4-arg overloads (one
   caller) and its "backwards-compatible" overloads (:49, :70),
   `VectorStores.indexLocation(ns)` / `systemDatabase()` defaults returning
   `"unknown"` (:33-41), the three-constructor pattern on 13 tool classes
   (`Path`, `FileRoots`, `WorkspaceFiles`; e.g. `GlobTool:64-76`,
   `ReadDocumentTool:36-49`) and `BashTool(File)` / `BashTool(FileRoots)`:
   only the `WorkspaceFiles` constructor is called from main; keep it,
   tests call `WorkspaceFiles.local(FileRoots.of(dir))`. (TL-09, TL-30)
4. `InMemoryMailIndexStore.java:40,48` builds keys with a literal NUL
   character in the source. Write the escape or use a key record. (X-11)
5. Copied helpers (after Q-12): `tools-document/DocBaseDirs:9-12,44-69,88-92`
   is a verbatim copy of `builtin/BaseDirs`, `builtin/FileRootedToolFactory`
   and `FileWalks.couldNotCheck`; move the three into `mc-agent-tool-spi`
   (`tool.workspace`), delete the copies. `~`/`$HOME` expansion exists in
   `builtin/ToolPaths:68-78`, `code/CodeExecuteToolFactory:182-192`,
   `McpTargetEndpoints:141-147`; root resolution in `BaseDirs`,
   `DocBaseDirs`, `VectorIngestFileTool:151-157`: one `HomePaths.expand()`
   in `mc-common`, and `VectorIngestFileTool.Factory extends
   FileRootedToolFactory` (today it ignores the `WorkspaceProvider` and
   reads local files while every other file tool may be on a virtual env).
   (TL-01, TL-26)
6. `mail-core/ConnectedMailbox:54-60`, `calendar-core/ConnectedCalendar:25-32`
   hard-code `"microsoft" → "Outlook"`, `"google" → "Gmail"` in the open
   core. Let `MailProvider` / `CalendarProvider` answer `displayName()` and
   `MailAccounts.of()` fill `kind` from the provider. (TL-18)

Verification: every module builds and its tests pass with unchanged test
sources (except the constructor cleanup in 1.3, where tests switch to the
one constructor).

### Step 2: bugs and behaviour (one PR each, failing test first, changelog fragment)

1. `VectorIngestFileTool.Factory` ignores the `WorkspaceProvider` (see 1.5):
   a file ingested from a virtual-env chat is read from the server's disk.
   Fragment under `fixed`. (TL-26)
2. After Q-16: `LegacyVectorStores` and `DefaultVectorStores:393-409` run
   the pre-0.9 import (JSONL files, `vs_*` tables) at every first namespace
   use. Either a one-off `migrate` command or a documented removal date;
   fragment under `changed`. (TL-23)

### Step 3: module seams (one PR each, label `no-changelog`)

1. `mcp-gateway-local/McpServerRepository`, `McpDiscoveryStore`,
   `McpStoreFactory` are persistence ports living in the *adapter* module,
   so `mc-mcp-gateway-pg` depends on the file adapter. Move the three
   interfaces to `mc-mcp-gateway-core`. (TL-05)
2. `mail-views/PgViewStore`, `mail-index/PgMailIndexStore` and their POMs
   (`jdbc`, `postgresql`) sit beside the port; everywhere else Postgres
   adapters live under `adapter/postgres/mc-*-pg`. Create
   `adapter/postgres/mc-mail-views-pg` and `mc-mail-index-pg` (or one
   `mc-office-pg`), move the classes, drop the JDBC dependencies from the
   port modules. (TL-06)
3. `mcp-gateway-local/McpStartPolicy:3,37`: a plain record imports
   `org.springframework.core.env.Environment`. Move `from(Environment)`
   into `McpGatewayAutoConfiguration`. (TL-24)
4. After Q-04: `mc-agent-tools-workflow` keeps `WorkflowTool`,
   `WorkflowToolProvider` and the steps (port-only); its three
   `@AutoConfiguration`s (`NamespacedWorkflowStoresAutoConfiguration`,
   `McAgentWorkflowStepsAutoConfiguration`,
   `registry/WorkflowRegistryAutoConfiguration`), the registry installer,
   the `new FileWorkflowDataRepository` in `WorkflowToolProvider:72` and the
   dependencies on `mc-workflow-persistence-pg` and
   `mc-workflow-admin-rest` move to `springstarter/` or an `adapter/`
   module. Same for `mc-agent-tools-virtual-env`:
   `VirtualEnvToolsAutoConfiguration` and `OnBehalfJwksController` (and the
   web-mvc dependency) move to a starter; the tool and its client stay.
   (TL-03, TL-25)
5. `tools-workflow/step/ToolInvokers:10-20`, `AgentInvokers:10-20` are
   static mutable service locators (`set` / `clear` / `require`) because
   steps are built by ServiceLoader. Hand the invoker through the
   `WorkflowContext` attribute the steps already read; if a holder must
   stay, name it `ToolInvokerHolder`. (TL-04)
6. `tools-calendar/pom.xml` depends on `tools-mail` only for `OfficeTool`
   and `Accounts`, and both modules declare `ai.mindconnect.office.tools`
   (a split package across two jars). After step 5.6 the dependency
   disappears; give each module its own package (`office.mail.tools`,
   `office.calendar.tools`). (TL-15)
7. After Q-15: `mail-core/pom.xml` and `calendar-core/pom.xml` depend on
   `mc-agent-tool-spi` for `ToolConnection` / `Connections` /
   `ConnectionSpec`. If connections are domain vocabulary, they move to
   `mc-agent-domain` (or a small `mc-connections-core`) and the two core
   modules stop depending on the tool SPI. (TL-19)
8. `tools-document/HtmlToMarkdown`, `HtmlLinkExtractor`, `RelevantExcerpts`
   are web helpers living in the document module so that `-web` and
   `-web-browser` can depend on it; `mc-vector-store-tools` then needs an
   `<optional>` dependency plus `Class.forName`
   (`VectorIngestFileTool:118-129`) to reach `DocumentReader`. Introduce
   `mc-document-core` (document model, parsers, HTML helpers, no tools) that
   web, browser, document tools and vector tools depend on. (TL-12)

Verification: an ArchUnit test (shared with the agents-core agent's module
`agents/mc-agent-architecture-test` if it exists by then) that fails on:
Spring imports in a tool module; a `Pg*` class outside `adapter/postgres/`;
a `-core` module importing `*.tool.*`; `Class.forName` of a class in a
declared dependency.

### Step 4: renames (one PR per module group, one commit per rename, IDE refactoring only, tests untouched, label `no-changelog`)

Registry / Catalog: `MailAccounts` → `MailboxRegistry` (also removes the
clash with `mail.imap.MailAccount`), `CalendarAccounts` →
`CalendarAccountRegistry`, `BackgroundProcesses` →
`BackgroundProcessRegistry`, `CodeLanguages` → `CodeLanguageCatalog`,
`VectorStores` → `VectorStoreCatalog`, `DefaultVectorStores` →
`ConfiguredVectorStoreCatalog` (after step 5.8), `NamespacedMcpGateways` →
`McpGatewayPerNamespace`.

Resolver / Selector / Validator: `Accounts<A>` → `AccountSelector<A>`,
`McpTargetEndpoints` → `McpEndpointResolver`, `ToolNamePrefixes` →
`ToolNamePrefixSuggester`, `EmbeddingChecks` → `EmbeddingValidator`,
`RelevantExcerpts` → `RelevantExcerptSelector`, `BaseDirs` / `DocBaseDirs` →
`FileRootResolver` (after step 1.5).

Policy / Converter / Service: `FileWalks` → `FileWalkPolicy` (excluded
directories) + `TextDecoding` (`isBinary`, `decode`), `McpRegistrationJson`
→ `McpRegistrationConverter`, `MailListViews` → `MailListViewService`,
`OnBehalfTokens` → `OnBehalfTokenSigner`, `WorkflowAgentCalls` →
`WorkflowAgentCaller`, `LegacyVectorStores` → `LegacyVectorStoreMigration`,
`PostgresVectorStores` → `PgVectorStoreRegistryOpener`.

Value objects: `ToolPaths` → `PathArguments`, `SessionDirs` →
`SessionMounts`, `MetadataFields` → `MetadataFieldSet`, `Vectors` →
`VectorMath`, `Reminders` → `ReminderList`, `VectorStore` →
`VectorStoreHandle` (six "store" nouns today: `VectorStores`, `VectorStore`,
`VectorStoreRegistry`, `VectorStoreInstance`, `VectorStoreTemplate`,
`IndexDefinition`; after the two renames each says what it is).

Misnamed: `DocumentParser` (static Tika/HTML/URL) → `WebContentExtractor`
(or merge its HTML half into `HtmlToMarkdown`), `ToolInvokers` /
`AgentInvokers` → `ToolInvokerHolder` / `AgentInvokerHolder` if they survive
step 3.5, `PlaywrightHolder` / `SharedDocumentReader` / `WebToolsHttpClient`
→ factory fields after step 5.7 (else `PlaywrightBrowserProvider`,
`DocumentReaderProvider`, `WebHttpClientProvider`).

Tool class names: `ReadDocumentTool`, `ReadDocumentSectionTool`,
`GrepDocumentTool` → `Document*Tool` like `DocumentOutlineTool` (the model-
facing tool names change in step 5.9, not here).

After Q-03: `Store` ↔ `Repository` made uniform (`McpServerRepository` vs
`McpDiscoveryStore` is the one mixed module).

Verification: a grep for every old name returns nothing in code,
`agents/doc` and `website/docs`; every module builds.

### Step 5: god classes and duplication (one PR per class, in this order; stop after each for review)

1. **`BashTool`** (618 lines): `execute` (:125-232) with process
   supervision and the try/finally, output capping (`Output` inner class
   :551), background start, a hand-written shell lexer for `&` / `nohup` /
   heredocs and the long-running heuristics (`detaches` + `LONG_RUNNING`
   :369-475). Extract `ShellCommandInspector` (`detaches`,
   `looksLongRunning`, `Heredoc`), `CappedOutput`, `ForegroundRun`. The
   existing tests cover it well; they must pass unchanged. (TL-07)
2. **`GlobTool.execute`** (:148-316, 170 lines) and **`GrepTool.execute`**
   (:113-270, 160 lines): `int[]` / `boolean[]` capture arrays, inline
   anonymous walkers, inline result rendering. Extract `WalkBudget`
   (deadline + interrupt check, shared by both), `GlobMatchCollector` /
   `GrepFileScanner`, a `render…` method each, and a
   `ClosestNameSuggester` from `suggestionsFor` / `levenshtein` (:319-395)
   that `FileEditTool.closestPassage` (:205-250) can use too. (TL-08)
3. **`DocumentReader`** (628): PDF (:93-207), DOCX with heading levels
   decided by font size (:209-563), text (:565-610), an LRU cache (:612).
   Split into `PdfDocumentParser`, `DocxDocumentParser` (+
   `DocxHeadingHeuristic`), `TextDocumentParser`, `DocumentModelCache`;
   `DocumentReader` becomes the dispatcher. (TL-10)
4. **`Mailbox`** (812, `mc-mail-imap`): statics for probe (:101), folders
   (:128), trash / sent / special folders (:165-212), `saveSent` (:214);
   instance operations (:272-507); search and `filterLocally` (:649-712);
   address and explain helpers (:759-792). Extract `ImapConnection` (open
   store, folders, special folders, probe); `Mailbox` stays the open-folder
   object; `addresses` / `explain` go to `MailText` / `ImapErrors`. (TL-17)
5. **`ICalendar`** (622, all static) and `CalDavClient:295-331` (public
   static XML scraping): split into `ICalendarReader`, `ICalendarWriter`,
   `ICalendarPatch`; move `responses` / `between` / `has` to `DavXml`.
   (TL-20)
6. **`MailTools`** (598) and **`OfficeTool`** (after Q-14).
   `MailTools.listing()` (:131-243) is a 110-line lambda doing argument
   parsing, OR-term fan-out, index path, multi-mailbox merge and rendering;
   the class also owns write-through to the index (:436-489) and folder
   resolution (:549-598). One class per tool (`MailListTool`,
   `MailSendTool`, …) as every other module does, and a
   `MailIndexWriteThrough` collaborator. `OfficeTool` (:93-214) is a generic
   function-backed `Tool` plus a static JSON-schema DSL plus static argument
   parsers plus date formatting: keep `OfficeTool` as the command, move the
   DSL to `ToolSchema` and the parsers to `ToolArguments` in
   `mc-agent-tool-spi` (SPI PR via the agents-core agent), where
   `GlobTool.parseLimit`, `GrepTool.intArg`, `FileReadTool.intArg`,
   `VectorTools.clamp` / `schema` (:446-466) use them too. Then
   `CalendarTools` the same way. (TL-13, TL-14)
7. **Static singletons and the missing close hook.**
   `web/WebToolsHttpClient`, `browser/PlaywrightHolder`,
   `document/SharedDocumentReader`, `code/CodeExecuteToolFactory:76` (a JVM
   shutdown hook), `vectorstore/tools/PostgresVectorStores:22-27`,
   `DefaultVectorStores:66` hold process-wide state because `ToolFactory`
   has no close hook. Add `default void close()` to `ToolFactory` /
   `MultiToolProvider` (SPI PR via the agents-core agent; the registry
   already calls `bind` once) and hold these as factory fields. (TL-27)
8. **`DefaultVectorStores`** (533) and **`VectorTools`** (471).
   `DefaultVectorStores` (:1-27, :117-170, :334-392, :491-533) imports
   `PgEmbeddingIndex`, `PGSimpleDataSource`, `Sql`, `EncryptionHelper`,
   `LlmConfigRepository` and parses `ToolEnvironment` strings; the "port"
   `VectorStores.fromEnvironment` (:41-43) calls its only implementation.
   Split into `VectorStoreConfiguration` (environment → definitions),
   `EmbeddingIndexOpener` (file / pg), `VectorStoreEmbedder` (LLM side);
   move the Postgres half next to `mc-vector-store-pgvector`; drop the
   static on the interface. `VectorTools` is the only module that nests
   `BaseFactory`, three factories and three record tools in a static outer
   class and registers them as `VectorTools$UpsertFactory`: lay it out like
   the others, `VectorUpsertTool` + `VectorUpsertToolFactory` etc. with a
   shared `VectorToolFactory`. (TL-02, TL-21)
9. **Document tool overlap.** `document_file_read`, `read_document`,
   `document_outline`, `document_sections`, `read_document_section`,
   `grep_document` all start with the same 230-character description;
   `document_sections include_content=false` duplicates `document_outline`;
   `document_file_read` duplicates `file_read` + Tika. Collapse to
   `document_outline`, `document_read` (pages or section), `document_grep`,
   `document_write`. This changes what the model sees: fragment under
   `changed`, and update every bundled agent prompt that names a document
   tool. (TL-28)
10. **`ConnectionProvider` SPI.** `mail-imap/MailboxConnectionProvider:23-30`
    and `calendar-caldav/CalDavConnectionProvider:16-29` publish a
    connection card by implementing `MultiToolProvider` with `toolNames() =
    Set.of()` and `create() = Optional.empty()`. Add a `ConnectionProvider`
    SPI (`connectionSpec()`, `connectionTester()`) in `mc-agent-tool-spi`
    (SPI PR via the agents-core agent), have the registry load it, and let
    `MultiToolProvider` / `ToolFactory` keep only `connectionSpec()` for
    tools that are `ConnectedTool`s. (TL-16)

Verification for every split: the module's tests unchanged and green; the
old class is gone or a thin facade; no new class above ~300 lines and no
method above ~50; for `BashTool`, `GlobTool`, `GrepTool` and the document
tools the manual chat test in `agents/doc/manual-tests` (they run on every
file operation an agent does).

### Step 6: docs

`agents/README.md`, the module list in `CLAUDE.md` and
`website/docs/agents/**` updated in the same PR as every rename, move or
removal; the tool reference page lists the document tools after step 5.9.

## When you are unsure

Ask the maintainer in one short message with the options and your
recommendation. Do not pick silently where two readings lead to different
code. Do not widen a PR to fix something you noticed; note it in the PR body.
