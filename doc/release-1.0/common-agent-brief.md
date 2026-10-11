# Brief: prepare the `common/` area for the 1.0 release

You are a coding agent working only on the `common/` area of the Maven
monorepo `mindconnect-ai/mindconnect`. Read `CLAUDE.md`, `AGENTS.md` and
`common/README.md` first. The full review is in
`doc/release-1.0/review.md` (section 4.6, the `CM-` findings, is yours), the
overall plan in `doc/release-1.0/plan.md`. This brief is self-contained.

## Scope

`common/mc-common`, `mc-schema`, `mc-file-manager`, `mc-file-repo`,
`mc-webscraper`, `mc-pathaccessor`, `mc-script-mini`, `mc-initial-data`.

Not yours: `common/mc-jdbc` (its one finding, `DocumentTable`, is in the
agents-core brief because every Postgres adapter builds on it). Every other
area depends on `common/`; when a rename here breaks a caller there, fix it
mechanically in the same PR and say so in the PR body; do not refactor it.

## Goal

`common/` holds shared, dependency-light libraries that know nothing about
the areas above them. For 1.0 every module in it must be release-ready: no
personal paths, no customer data, no `main()` in main sources, no logging
backend as a compile dependency, no module nobody depends on. One
responsibility per class, short methods (aim under 30 lines) and classes
(aim under 300), names after the pattern a class implements (Walker,
Reader, Writer, Cipher, Policy, Converter, Seeder), singular class names,
one constructor per class, and javadoc that is contract, not history.

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
5. Javadoc: present tense, what and why, one paragraph. No "Originally…",
   no "is now reused by…", no 60-line syntax essay in a class header (it
   goes to the module README).
6. Imports, never inline fully qualified names.
7. One constructor per class unless a second one has a real second caller.
8. Build with `mvn -f common/<module>/pom.xml clean install`, then the root
   aggregator once before opening the PR (every area depends on you).
9. Changelog: a bug fix or a removed module gets a fragment
   `changelog.d/<fixed|removed|changed>/<branch-slug>.md` with
   `- **common:** **what is different.** explanation…`. Structural work
   (renames, splits, moves) gets the PR label `no-changelog` instead.
10. Everything in the repository and on GitHub is English. No
    `Co-Authored-By` trailer.
11. Branch from `next` as `feature/common-<topic>`, run
    `./after-branch-creation.sh` once, open the PR against `next`, one PR
    per step below.

## Decisions already taken

- **Converter** is the name for a class that maps an object to a
  representation and back in one class. One direction only is a
  Serializer/Writer or a Parser/Reader; object to object is a Mapper;
  object to something a person looks at is a Renderer.

## Questions to ask the maintainer before the step that needs them

Ask briefly, in one message, as soon as you start; continue with the steps
that do not depend on the answer.

- Q-30: Is `mc-file-manager` used by anything outside this repository (a
  deployment, the semantic-ui repository)? Nothing in this repository
  depends on it. If not, delete it before 1.0.
- Q-29: `mc-webscraper` contains a customer's URLs (`RailsWebScraperConfig`,
  `WebScraperMain`, `src/main/resources/*.txt`) and a personal path. Purge
  all of it?
- Q-03: `Store`, `Repo` or `Repository` as the one word for a persistence
  adapter? Decides what `mc-file-repo` and `FileRepo` are called.
- Does `mc-common` stay the home of `LoggingContext` (agent MDC keys) and
  `McEnv` (loads a repository-root `mc.env`), or do they move to
  `mc-agent-domain` and the demos as proposed?

## Work, in order

### Step 1: hygiene (one PR per module, several commits of one kind each, label `no-changelog`)

1. Inline fully qualified names → imports, whole area.
2. Javadoc: `Cancellation.java:5-16` ("Originally…", "is now reused by…",
   narrates `LlmGateway.chatStreaming` and the agent tool loop) becomes a
   contract; `MiniScriptEngine.java:9-69` (60-line syntax essay) moves to
   `common/mc-script-mini/README.md`; `PathAccessor.java:4-9` and
   `MapPathAccessor.java:4-6` explain `StringVariableReplacer`, which lives
   in `workflow/`: describe the accessor instead. Types with no javadoc at
   all get one sentence: `Page`, `PageRequest`, `SortOrder`,
   `DomainException`, `IWebScraper`, `WebScraperListener`. (CM-06, CM-09,
   CM-12)
3. `main()` methods in main sources: `MiniScriptDemo` (becomes a test or a
   README snippet), `StringWildcardMatcher:62`, `PathSanitizer`,
   `WebScraperMain`, `RailsWebScraperConfigCreator`. Delete. (CM-01, CM-03,
   CM-05)
4. `AtomicFiles` (`mc-common/util`) and `FileWrites` (`mc-file-repo`) have
   identical bodies (`write`, `writeString`); `FileWrites` adds
   `isTemporary`; 10 callers use the first, one the second. Keep one as
   `AtomicFileWriter` in `mc-common`, delete the other. (CM-02)
5. `FileRepo.java:48` spawns a cleanup virtual thread in the constructor
   with no `close()`. Make it `AutoCloseable`. (CM-13)
6. `mc-initial-data/InitialDataInstaller:17` declares a public static
   `Logger log` on an interface and a 30-line `default install(String)`
   template method (:33-60): an abstract class disguised as an interface.
   `InitialDataSeeder` (class: scan, skip existing, log) over an
   `InitialDataTarget` port (`exists`, `install`); the `FileCopy…` /
   `Import…` implementations become targets. (CM-08)

Verification: every module builds and its tests pass with unchanged test
sources; the root aggregator builds.

### Step 2: bugs (one PR each, failing test first, changelog fragment under `fixed`)

1. `mc-webscraper/WebScraperResults.addFailed` (:17-19) adds the page to
   `pages` instead of `failedPages`. (CM-04)
2. `mc-webscraper/WebScraperOptions.clone` (:65-66) passes `removeHtml`
   twice instead of `removeNewLine`. (CM-04)
3. `mc-common`: `Page` is 1-based (`hasPrev: page > 1`, `of` uses
   `(page-1)*size`) while `PageRequest` is 0-based (`offset = page*size`,
   `DEFAULT = (0, 20)`). Pick one convention, add the test that
   `Page.of(all, req.page(), req.size())` agrees with `req.offset()`; check
   every caller in `agents/` (the admin UI pages) for the off-by-one.
   (CM-07)

### Step 3: release hygiene of the prototype modules (one PR each)

1. **`mc-file-manager`** (after Q-30). No module depends on it; it is a
   `@SpringBootApplication` whose `@Service` throws web
   `ResponseStatusException` (`FileManagerService:36-40`);
   `FileManagerWithLockService` is a commented-out `//@Service` with
   commented-out locking and a different property key
   (`file-manager.base-directory` vs `mindconnect.file-manager.base-directory`);
   `SortDirection` duplicates `mc-common` `SortOrder`; the name collides
   with `mc-file-repo`. Delete the module, remove it from the root and
   `common/` aggregators and from `common/README.md`; fragment under
   `removed`. (CM-05)
2. **`mc-webscraper` purge** (after Q-29). Personal path
   `/Users/dbe/...` (`WebScraperMain:28`); customer URLs in
   `RailsWebScraperConfig:22-28`, `WebScraperMain:6` and the link lists in
   `src/main/resources/*.txt`; `ApplicationConfig` reads Pinecone / OpenAI
   environment variables unrelated to scraping; the POM ships
   `logback-classic` **and** `slf4j-simple` as compile dependencies (a
   library must not pick the logging backend) and Playwright for every
   consumer. Delete `Rails*`, `ApplicationConfig`, the resources; logging →
   `slf4j-api` only; Playwright `<optional>true</optional>`. Fragment under
   `changed` (consumers that relied on the transitive backend must add
   their own). (CM-03)
3. **`mc-common` knows about agents.** `LoggingContext` hard-codes agent
   MDC keys (`sessionId`, `agentName`, `subAgent`, `tool`) and cites
   `logback-spring.xml`; `McEnv` loads a repository-root `mc.env` and
   prints to `System.err` (:35), with a second copy in
   `agents/demo/.../McEnv.java`. After the maintainer's answer:
   `LoggingContext` → `mc-agent-domain` (as `MdcScope`), `McEnv` → the
   demos (as `DotEnvFile`), one copy. (CM-06)

### Step 4: renames (one PR, one commit per rename, IDE refactoring only, tests untouched, label `no-changelog`)

- `PathLocks` → `StripedPathLock`
- `Documents` (`mc-file-repo`) → `FileDocumentStore` (the counterpart of
  `mc-jdbc`'s `DocumentTable`)
- `Versions` (`mc-common`) → `OptimisticVersion`
- `EncryptionHelper` → `SecretCipher` + `TaggedSecret` (step 5.3)
- `WebScraperResults` → `ScrapeResult`; `WebScraperOptions` /
  `BaseWebScraperOptions` → `CrawlOptions` / `PageScrapeOptions`;
  `IWebScraper` / `ISingleWebPageScraper` → `WebScraper` / `PageScraper`;
  `BaseWebScraperContext` + `Jsoup…Context` / `Playwright…Context` →
  `ScrapeSession` (+ `VisitedUrls`)
- `HtmlCleaner`, `StringWildcardMatcher`: names stay, `main()`s gone
  (step 1.3)
- After Q-03: `mc-file-repo` / `FileRepo` to the chosen word.

Verification: a grep for every old name returns nothing in code and docs;
the root aggregator builds.

### Step 5: god classes and duplication (one PR per class, in this order; stop after each for review)

1. **`MiniScriptEngine`** (874 lines, `mc-script-mini`): a whole language
   in one file. `Lexer` (:135-265), AST records (:267-301), `Parser`
   (:307-555), `Evaluator` (:557-873) with reflection (`invoke`,
   `findMethod`, `coerce` :787-842) and built-ins (:753-785). Split into
   `MiniScriptLexer`, `MiniScriptParser`, `MiniScriptAst` (a sealed `Node`
   hierarchy), `MiniScriptEvaluator`, `ReflectiveInvoker`,
   `BuiltInFunctions`; `MiniScriptEngine` stays the facade. Everything is
   package-private today, so the public API does not move. The existing
   script tests must pass unchanged. (CM-01)
2. **`mc-webscraper` design** (after step 3.2). `CssSelector extends
   UrlPattern` (`CssSelector.java:5`) although a selector is not a URL
   pattern; `isAnchorLink` / `isTelLink` / `isHttpUrl` /
   `convertToAbsoluteUrl` copied into `AbstractWebScraper:27-40`,
   `JsoupSingleWebPageScraper:131-150`,
   `PlaywrightSingleWebPageScraper:123-139`; `executeScrape` is ~95 lines
   in both single-page scrapers; `BaseWebScraperOptions.clone` +
   `WebScraperOptions.clone` hand-copy 15 fields. One `LinkFilter` holding
   the URL predicates, options as records (no `clone`), one `PageScraper`
   template with jsoup and Playwright fetch strategies. Two dependents in
   `agents/` (`mc-agent-tools-web`, the taskqueue demo workers); fix their
   calls in the same PR. (CM-04)
3. **`EncryptionHelper`** (`mc-common/util/encryption`): AES primitives
   that `throws Exception` (:129-136), `plain:` / `enc:` tag resolution
   (:35-70), map-level batch operations (:72-127). `SecretCipher` (AES, a
   typed exception) + `TaggedSecret` (prefix handling); the map operations
   move to the one caller that needs them. Callers in `agents/`
   (`mc-credentials`, the vector-store tools, the starters) adjusted in the
   same PR. (CM-11)
4. **`mc-pathaccessor`**: the segment-walking loop is written three times
   (`MapPathAccessor:17-30`, `RecordPathAccessor:20-33`,
   `BeanPathAccessor:33-50`) differing only in the per-step read;
   `PathSegment` exposes public fields (:23-29). One `PathWalker` with a
   `PropertyReader` strategy per type; `PathSegment` becomes a record.
   (CM-09)

Verification for every split: the module's tests unchanged and green; the
old class is gone or a thin facade; no new class above ~300 lines and no
method above ~50; the root aggregator builds.

### Step 6: docs

`common/README.md` lists only the modules that exist after step 3, one
sentence each on what the module is for and what it depends on; the
MiniScript syntax lives in `common/mc-script-mini/README.md`.

## When you are unsure

Ask the maintainer in one short message with the options and your
recommendation. Do not pick silently where two readings lead to different
code. Do not widen a PR to fix something you noticed; note it in the PR body.
