# Brief: prepare the `workflow/` area for the 1.0 release

You are a coding agent working only on the `workflow/` area of the Maven
monorepo `mindconnect-ai/mindconnect`. Read `CLAUDE.md`, `AGENTS.md` and
`workflow/README.md` first. The full review is in
`doc/release-1.0/review.md` (section 4.5 is yours), the overall plan in
`doc/release-1.0/plan.md`. This brief is self-contained; the two documents
give more context if you need it.

## Scope

Everything under `workflow/`: `mc-workflow` (core engine), `mc-workflow-code-*`
(script addons), `mc-workflow-jackson`, `mc-workflow-persistence`,
`mc-workflow-persistence-pg`, `mc-workflow-step-form`, `mc-workflow-dsl-puml`,
`mc-workflow-admin-rest`, `mc-workflow-admin-app`, `mc-workflow-ui-diagram`,
`mc-workflow-ui-diagram-app`, `mc-workflow-swing-ui`, `mc-workflow-spi-lookup`,
`mc-workflow-test`.

Not yours: the agents area, even where it uses workflow classes
(`agents/core/mc-agent-tools-workflow`). When a rename in `workflow/` breaks
a caller in `agents/`, fix that caller mechanically in the same PR and say
so in the PR body; do not refactor it.

## Goal

`workflow/` is a small, fast engine embeddable as a plain library. For 1.0
the code must be: hexagonal (engine free of Jackson, Spring and UI; ports in
the core, adapters outside), one responsibility per class, short methods
(aim under 30 lines) and classes (aim under 300), named after the pattern
they implement (Registry, Factory, Converter, Renderer, Policy, Resolver,
Walker, Instance, Definition), singular class names, and javadoc that says
what and why in one paragraph without project history.

## Ground rules for every PR

1. One topic per PR. A rename PR renames. A split PR splits one class. A bug
   fix PR fixes one bug with its test. Never mix.
2. A verifiable goal before the change: a failing test for a bug; the area's
   test suite green before and after a structural change with the test
   sources untouched.
3. Surgical. Match the surrounding style, do not reformat, do not improve
   unrelated code. Dead code you find on the way goes into the PR body as a
   note, unless the PR is the dead-code PR.
4. Minimum code. No abstraction for one caller, no configurability nobody
   asked for, no catch for a case that cannot happen.
5. Javadoc: present tense, what and why, one paragraph. No "concept NN", no
   "Etappe 1a", no "it used to". No javadoc that restates the signature.
6. Imports, never inline fully qualified names.
7. One constructor per class unless a second one has a real second caller.
8. Build with `mvn -f workflow/pom.xml clean install` and read the
   `Tests run:` / `Skipped:` counts; a green run may mean skipped.
9. Changelog: a bug fix or a removal gets a fragment
   `changelog.d/<fixed|removed|changed>/<branch-slug>.md` with
   `- **workflow:** **what is different.** explanation…`. Structural work
   (renames, splits, moves) gets the PR label `no-changelog` instead.
10. Everything in the repository and on GitHub is English. No
    `Co-Authored-By` trailer.
11. Branch from `next` as `feature/workflow-<topic>`, run
    `./after-branch-creation.sh` once, open the PR against `next`, one PR
    per step below. Do not create a PR for several steps at once.

## Decisions already taken

**D-1 Vocabulary: Definition : Instance.** What the user writes (JSON,
PUML) is a `…Definition`; what runs is an `…Instance`. This is the pair the
agents area uses (`AgentDefinition`). Renames:

| Today | New |
|---|---|
| `StepData` / `StepInstance` | `StepDefinition` / `StepInstance` |
| `StepContainerData` / `StepContainerInstance` | `StepContainerDefinition` / `StepContainerInstance` |
| `BaseStepData` / `BaseStepInstance` | `AbstractStepDefinition` / `AbstractStepInstance` |
| `BaseStepContainerData` / `BaseStepContainerInstance` | `AbstractStepContainerDefinition` / `AbstractStepContainerInstance` |
| `WorkflowData` / `WorkflowInstance` | `WorkflowDefinition` / `WorkflowInstance` |
| `IfData` / `IfStep` | `IfStepDefinition` / `IfStepInstance` |
| `ForEachData` / `ForEachStep` | `ForEachStepDefinition` / `ForEachStepInstance` |
| `CodeData` / `CodeStep` | `CodeStepDefinition` / `CodeStepInstance` |
| `HttpCallData` / `HttpCallStep` | `HttpCallStepDefinition` / `HttpCallStepInstance` |
| `CallWorkflowData` / `CallWorkflowStep` | `CallWorkflowStepDefinition` / `CallWorkflowStepInstance` |
| `AssignVariablesData`, `BlockData`, `HaltData`, `JumpToData` and their `…Step` executors | `…StepDefinition` / `…StepInstance` |
| `FormStepData` / `FormStep` (step-form module) | `FormStepDefinition` / `FormStepInstance` |
| `WorkflowDataRepository` | `WorkflowDefinitionRepository` |
| `WorkflowDefinitionRegistry` | stays |

Rename executors first, definitions second, so that no name ever changes
meaning (today `IfStep` is the executor; after the rename nothing is called
`IfStep`).

**D-2 Step type aliases in the file format, resolved in the Jackson
module.** Today every stored step carries
`"@class": "ai.mindconnect.workflow.domain.IfData"`
(`@JsonTypeInfo(use = Id.CLASS)` in
`mc-workflow-jackson/.../WorkflowObjectMapperFactory.java:69`). The rename
in D-1 would make every stored workflow unreadable, so this comes first:

- In `mc-workflow` (no Jackson): a `StepType<D extends StepDefinition>`
  record (`id`, `definition` class, `Supplier<StepInstance<D>>`) and a
  `StepTypeRegistry` (`register`, `byId`, `byDefinition`, `all`). Built-in
  ids: `workflow`, `block`, `if`, `foreach`, `halt`, `jumpTo`,
  `assignVariables`, `callWorkflow`, `code`, `httpCall`. The step-form
  module registers `form` in its `WorkflowConfigurer`. `getType()` on a
  definition returns its registry id instead of deriving it from the class
  name (`BaseStepData.java:19`). `StepInstanceFactory` becomes a lookup over
  the registry or disappears.
- In `mc-workflow-jackson`: the mixin uses `Id.NAME`,
  `property = "type"`, `As.EXISTING_PROPERTY`, and a `StepTypeIdResolver`
  (`TypeIdResolverBase`) that asks the registry for id ↔ definition class.
  `WorkflowObjectMapperFactory.create(StepTypeRegistry)`;
  `JsonWorkflowConfigurer` gets the registry from the
  `WorkflowContextFactory`.
- Reading old files: a `JsonNode` pre-pass before deserialisation replaces
  `@class` by `type` through a table of old class names → aliases. Saved
  files come out in the new format. The table lives only in the Jackson
  module.
- A custom step registers one `StepType` in its `WorkflowConfigurer`
  instead of relying on its class name; say so in `workflow/README.md`.
- The same registry replaces the three hand-kept type tables (`StepMapper`,
  `StepFactory`, `AddStepDialog`) and ends the `httpcall` vs `http` alias in
  `DslStepBuilderRegistry:34-39`.

## Questions to ask the maintainer before the step that needs them

Ask briefly, in one message, as soon as you start; continue with the steps
that do not depend on the answer.

- Q-22: `spel:` is documented (README, `IfData`, `VariableAssignment`,
  `CompositeExpressionResolver`, `ExpressionResolver`, PUML samples) but no
  SpEL resolver exists; a `spel:` condition evaluates to the literal string.
  Remove every reference, or implement a resolver?
- Q-23: May the PUML parser drop legacy syntax (`#params:` directives,
  `sub/end sub`, note-based for-each metadata)?
- Q-25: Which UIs ship in 1.0: the Swing designer, the
  `mc-workflow-ui-diagram-app` harness and the admin UI overlap. Can the
  harness be dropped and the Swing designer moved to schema defaults?
- Q-26: Should `CallWorkflowStep` resolve callees from the admin's
  `WorkflowDataRepository`? Today only the classpath registry works, so a
  workflow saved in the admin cannot be called by another.

## Work, in order

### Step 1: hygiene (one PR, several commits of one kind each, label `no-changelog`)

1. Inline fully qualified names → imports, whole area.
2. Stale javadoc: `ai.mindconnect.workflow.api.*` in `CodeData:19`,
   `HttpCallData:20`, `WorkflowConfigurer:10`, `JsonWorkflowConfigurer:34`,
   `SpiWorkflowContextFactory:17`; `mc-workflow-api` in
   `WorkflowObjectMapperFactory:15`; "`mc-workflow-code`/`mc-workflow-http`
   on classpath" in `PumlWorkflowParser:33,39`; "Etappe 1a/1b", "Phase 3a"
   in `WorkflowDiagramBuilder:29-35`, `LayoutStore:16`; the README essays
   in `WorkflowConfigurer:20-34` and `WorkflowMutator:12-44` move to
   `workflow/README.md` or a new `workflow/doc/`. (WF-21)
3. Dead code: `ScriptEngineProvider` (whole class),
   `StringVariableReplacer.resolveVars`, `JacksonJsonMapper`
   InputStream/Path/`getMapper` methods,
   `SpiWorkflowContextFactory.discoverConfigurers`,
   `WorkflowContext.baseDir`, `WorkflowContext.scriptOutputWriter`,
   `WorkflowData.uid`, `ResourceUtil.writeString` (then inline
   `ResourceUtil` into its one caller `JacksonWorkflowDefinitionRegistry`),
   `WorkflowAdminUiController.ownVars` (:1628), `LiveRun` 4/5-arg
   constructors (:850-857), `PumlWorkflowParser.Cursor.nextNonEmpty`,
   `execution/HasNext` and the dead `jumpTo(hasNext.getNext())` branch in
   `BaseStepContainerInstance:159`, `WorkflowDiagramBuilder.activeFrameAbsoluteOrigin`
   (:196-213), `WorkflowDefinitionRegistry.putWorkflowDefinition` (no
   caller). (WF-08, WF-22, WF-28 part)
4. `WorkflowContext.scriptExecutor` is typed `Object` with `instanceof`
   dances in `WorkflowContextFactory:21-28` and `CodeStep:37-41` to avoid
   modules that no longer exist; type it `ScriptExecutor`, delete the four
   identical `getOrCreate` copies in `mc-workflow-code-*/…Configurer` and
   `CodeStep.buildFallbackProvider`. (WF-06)
5. `WorkflowExecutorService:89-92,128-133,142-152`: `env` assigned twice,
   two identical `catch` branches, `@Data` setters beside `withEnvironment`;
   remove the first assignment, collapse, explicit getters. (WF-11)
6. `HaltException`, `StepExecutionException`: `@Data @Builder` mutable
   exceptions → immutable constructors. (WF-27)
7. Small renames with no meaning change: `execution/JsonMapper` →
   `JsonConverter` (clashes with Jackson's `JsonMapper`), `HttpStepException`
   → `HttpCallException`, `MultipleExceptionsException` →
   `ParallelExecutionException`, `WorkflowAdminService` →
   `WorkflowAdminFacade`, `WorkflowRunService` → `WorkflowRunner`,
   `WorkflowExecutorService` → `WorkflowExecutor`, `StepJsonMapper` →
   `StepJsonConverter`, `WorkflowShapes` → `WorkflowShape` (enum),
   `WorkflowSamples` → `SampleWorkflowCatalog`, `WorkflowInputs` →
   `RunInputPreparer`, `StepNames` → `UniqueStepNamer`, `ParamCoercion` →
   `ParamValueCoercer`, `ParamFormFields` → `SchemaFieldFactory`,
   `RunInstanceJson` → `StepInstanceJsonRenderer`, `WorkflowFileImport` →
   `FileStoreImporter`, `SchemaJackson` → `SchemaJacksonModule`, `RunStore`
   → `RunReportCache`; package `workflow.persist` (core) vs
   `workflow.persistence` (modules) → one name. (WF-29, WF-30, review §5)

Verification: `mvn -f workflow/pom.xml clean install` green with test
sources untouched; a grep for each old name returns nothing in code and
docs.

### Step 2: bugs (one PR each, failing test first, changelog fragment under `fixed`)

1. `execution/CallWorkflowStep.java:13-25`: `init` calls
   `workflowData.setAssignResultToVar(...)` on the registry's shared
   definition and `config.addSteps(workflowData)` on every instantiation, so
   a `CallWorkflowData` instantiated twice (loop body, resume, second run of
   the same definition object) runs the callee twice. Keep the callee as a
   field, override `getSteps()` to return `List.of(callee)`, never mutate
   `config` or the registry's object. (WF-10)
2. `scripting/ScriptExecutor.java:68-70,106-116`: one cached `ScriptEngine`
   per language whose `ENGINE_SCOPE` is mutated by `eval`; not thread-safe
   under `ForEachStep.executeParallel`, which also creates an unbounded
   `newCachedThreadPool` per loop (:127). Use `engine.eval(expr, bindings)`
   as `CodeStep` does and a virtual-thread executor. Test: a parallel
   for-each with a script step and distinct variables per iteration. (WF-23)
3. `VariableScope.VariableHistory` grows on every assignment and is never
   read: a leak in long loops. Remove it. (WF-22)
4. `jackson/JsonWorkflowConfigurer:26-52`: javadoc promises null-safe
   composite handling, code calls `factory.getExpressionResolver().add(...)`
   unguarded; `JsonExpressionResolver.replaceVars` (:82-94) re-implements
   `StringVariableReplacer` without path support. Guard; reuse the
   replacer. (WF-19)
5. After Q-22: remove every `spel:` reference (README:17,74 and the seven
   code samples) or implement the resolver. Either way a test that an
   unknown prefix fails loudly instead of returning the literal string.
   (WF-01)
6. Four `mc-workflow-code-*` modules: README says "JavaScript (Nashorn)",
   code registers GraalJS; `JythonWorkflowConfigurer:27-39` claims to use
   the thread-context class loader and does not. Fix the docs and the
   comment. (WF-07 part)

### Step 3: module seams (one PR each, label `no-changelog`)

1. `domain/HttpCallData:3` imports `execution` for a `{@link}` → `{@code}`.
   `scripting` ↔ `execution` cycle (`CodeStep` → `ScriptExecutor`;
   `ScriptExecutor` → `CodeStep`, `CodeStepException`,
   `WorkflowContextFactory`): move `CodeStepException` to `scripting` as
   `ScriptExecutionException`, drop the back references. (WF-05)
2. `persistence-pg/PgWorkflowInstanceRepository:6`,
   `PgWorkflowRepositoryFactory:4-5`,
   `persistence/memory/InMemoryWorkflowRepositoryFactory:3`: adapters
   import sibling adapters (`persistence.file.SnapshotSerializer`,
   `FileWorkflowDataRepository.directory`). `SnapshotSerializer` is Jackson,
   not file-specific: move to `persistence.json`; expose the directory
   convention from the port side. (WF-18)
3. `WorkflowAdminUiController:94-97,111,617,831` news up `RunStore`,
   `StepFormBuilder`, `WorkflowMutator`, keeps per-process state
   (`lastRunInputs`, `liveRuns`) and an executor that is never shut down.
   Inject the collaborators; move live-run state into a bean with
   `@PreDestroy`. (WF-26)
4. After Q-26: `WorkflowDefinitionRegistry` becomes a read-only adapter over
   `WorkflowDataRepository` (`RepositoryWorkflowDefinitionRegistry`) so an
   admin-saved workflow can be called. (WF-28)

### Step 4: step type registry, aliases, rename (three PRs, in this order)

1. **`StepTypeRegistry` and aliases (D-2).** Core record and registry,
   built-in registrations, `getType()` from the registry, Jackson mixin on
   `Id.NAME` with the `StepTypeIdResolver`, the `@class` pre-pass with the
   legacy table, README note for custom steps. Replace the three type tables
   (`admin/ui/StepMapper:40-79`, `diagram/app/StepFactory:30-88`,
   `swing/AddStepDialog:15-77`) and `execution/StepInstanceFactory:63-74`
   with registry lookups; delete the `http` alias in
   `DslStepBuilderRegistry:34-39`. Tests: every bundled sample workflow and
   every file under `src/test/resources` round-trips; a file in the old
   `@class` format loads and saves in the new format; an unregistered
   `type` fails with a message naming the id. Changelog fragment under
   `changed` (file format changes, old files still load). (WF-14, WF-24
   part, WF-20 prerequisite)
2. **Executors → `…StepInstance`** (D-1, first half). IDE rename only,
   tests untouched, callers in `agents/` fixed mechanically. Label
   `no-changelog`.
3. **Definitions → `…StepDefinition`, `WorkflowData` →
   `WorkflowDefinition`, `WorkflowDataRepository` →
   `WorkflowDefinitionRepository`** (D-1, second half). Same rules. Update
   `workflow/README.md` and `website/docs/workflow/**`. A changelog fragment
   under `changed` listing every renamed public type, old → new.

### Step 5: god classes and duplication (one PR per class)

In this order; stop after each and let the maintainer review.

1. **`WorkflowAdminUiController`** (2101 lines, `mc-workflow-admin-rest`).
   Split into `WorkflowListController` (CRUD), `WorkflowStepController`
   (step dialogs, add step), `WorkflowParamController` (the parameter-schema
   editor :1746-2053), `WorkflowRunController` (run form),
   `LiveRunStreamController` + `LiveRunRegistry` + `LiveRunView` (the SSE
   live run, today inner classes :736-1054), `RunReportRenderer`
   (`renderRun` :1212-1694, `runStepNode`, `scopeTree`, `outcomeBanner`),
   `FileChooserDialog` (:648-734), `AdminDialogs` (`openDialog`,
   `closeDialog`). (WF-02)
2. **`PumlWorkflowParser`** (1008 lines, `mc-workflow-dsl-puml`). `parseBody`
   (:184-371) is a 188-line `while` with 16 `if` branches mixing current
   syntax, three legacy `#directive` forms, legacy `sub/end sub` and
   note-driven for-each metadata; `applyTrailingNote` (:470-524) and
   `applyPropertyToStep` (:556-572) hard-code per-type property knowledge
   that the `DslStepBuilder` SPI exists to own. Split into `PumlTokenizer`
   (`Cursor` + `tokenizeLines`), one small parser per construct
   (`NoteBlockParser`, `ActionBlockParser`, `IfParser`, `ForkParser`,
   `PartitionParser`), move `buildHalt`/`buildJump`/`buildCall` into
   `HaltDslStepBuilder`/`JumpDslStepBuilder`/`CallDslStepBuilder` so
   `buildStep` has no built-in fallback; legacy syntax into a
   `LegacyDirectiveParser` or deleted (Q-23). `PumlWorkflowSerializer:252-296`
   uses `Class.forName("…CodeData")` + reflective getters for a type in a
   module it depends on: use the pattern `switch` already at line 84 and
   give `DslStepBuilder` a symmetric `Map<String,String> properties(StepDefinition)`
   so parse and serialise of one type live in one class. (WF-03, WF-04)
3. **Step tree walker.** Nine hand-rolled walks over
   `conditions[].thenBlock + elseBlock + container.steps`:
   `edit/StepNames:107-125`, `edit/WorkflowMutator:225-247`,
   `persist/DefinitionFingerprint:47-62`,
   `persist/WorkflowInstanceSnapshots:179-194`,
   `diagram/WorkflowDiagramBuilder:691-712`,
   `admin/ui/WorkflowUiBuilder:142-205`, `PumlWorkflowSerializer:109-138`,
   `swing/IfEditorPanel`. One `StepTree` walker (or
   `StepDefinition#childLists()` returning labelled child lists) in the core;
   the others built on it. (WF-13)
4. **`WorkflowInstanceSnapshots`** (321 lines, all static). Capture,
   restore and `pendingHalt` dispatch on `instanceof ForEachStep / IfStep /
   BaseStepContainerInstance` (:77-106, :255-305); a new container step type
   cannot be suspended without editing it. Split into
   `WorkflowInstanceSnapshotter` and `WorkflowInstanceRestorer`; move the
   per-kind knowledge onto the instances via a `Resumable` interface
   (`haltedChild()`, `rebuildChild(FrameSnapshot)`,
   `attachHaltedChild(...)`). (WF-12)
5. **`BaseStepInstance` vs `BaseStepContainerInstance`.** `resolveExpression`,
   `getExpressionResolver`, `getStringVariableReplacer`, `logDebug`
   duplicated (:65-105 vs :305-358) with different semantics: the container
   short-circuits `${…}`, the leaf has that code commented out (:67-69).
   Make the container base extend the leaf base, or extract a
   `StepExpressionEvaluator`; delete the commented code; a test that leaf
   and container resolve `${…}` the same way. (WF-09)
6. **`WorkflowRunService`** (679 lines, `admin/run`). Runs, resumes, decides
   persistence, assembles a trace tree, (de)serialises traces with its own
   `ObjectMapper`, snapshots scopes to depth 3, maps logs; calls
   `SpiWorkflowContextFactory.create()` per run (a ServiceLoader scan and a
   `new ScriptEngineManager()` on every click, :207, :238). Split into
   `WorkflowRunner`, `RunTraceRecorder`, `RunTraceJson`, `ScopeSnapshotter`;
   inject the `WorkflowContextFactory`. (WF-17)
7. **`WorkflowDiagramBuilder`** (713) + **`BuildContext`** (461). Builder =
   dispatch + layout maths + else-route waypoints + override application +
   jump wiring + extent sweep; context = ids + naming + cursor + LCA +
   container sizing (a 97-line `runInContainerAt`). Split into
   `DiagramLayoutCursor`, `DiagramNodeEmitter`, `StepShapeMapper` (`shapeFor`,
   `labelFor`, `WorkflowShape`), `LayoutOverrideApplier`, `JumpEdgeWirer`.
   The renderer must not rename domain objects: delete `reserveStepName`,
   `seedAutoSeqFrom`, `bumpAutoSeqToMatch`, `collectStepNames`
   (`BuildContext:117-166`, the second auto-naming scheme beside
   `edit/StepNames:46-88`); `WorkflowAdminService.require` already
   normalises names before rendering; throw on blank or duplicate names
   instead. (WF-15, WF-16)
8. **Script addons.** Four copy-pasted configurers in
   `mc-workflow-code-{beanshell,groovy,javascript,jython}` differing only in
   their alias list, while `DefaultWorkflowContextFactory:47-51` already
   auto-registers every JSR-223 engine on the classpath. An abstract
   `ScriptLanguageConfigurer(aliases, factory)` in the core, each module a
   three-line subclass. (WF-07)
9. **`WorkflowDesignerFrame`** (758, Swing), after Q-25. Frame + file IO
   (json/puml) + `Preferences` + run executor + header/params table + key
   bindings; still edits the deprecated `WorkflowData.paramDefaults` while
   the admin uses schema defaults. Extract `WorkflowFileActions`,
   `WorkflowRunAction`, `WorkflowHeaderPanel`, `DesignerKeyBindings`; switch
   to `Schema` defaults. `WorkflowStore` in `mc-workflow-ui-diagram-app`
   duplicates `InMemoryWorkflowDataRepository`: delete. (WF-25)

Verification for every split: the module's tests unchanged and green; the
old class is gone or a thin facade; no new class above ~300 lines and no
method above ~50; for the admin UI and the diagram, the manual check that
the sample workflows render and run as before.

### Step 6: docs

Keep `workflow/README.md` and `website/docs/workflow/**` in sync with every
rename and removal in the same PR. At the end: the README lists the step
type aliases, how a custom step registers its `StepType`, and the
Definition : Instance vocabulary.

## When you are unsure

Ask the maintainer in one short message with the options and your
recommendation. Do not pick silently where two readings lead to different
code. Do not widen a PR to fix something you noticed; note it in the PR body.
