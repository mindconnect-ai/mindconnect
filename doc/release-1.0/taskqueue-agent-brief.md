# Brief: prepare the `taskqueue/` area for the 1.0 release

You are a coding agent working only on the `taskqueue/` area of the Maven
monorepo `mindconnect-ai/mindconnect`. Read `CLAUDE.md`, `AGENTS.md` and
`taskqueue/README.md` first. The full review is in
`doc/release-1.0/review.md` (section 4.6, the `TQ-` findings, is yours), the
overall plan in `doc/release-1.0/plan.md`. This brief is self-contained.

## Scope

Everything under `taskqueue/`: `mc-task-queue` (core: ports, local
implementation, the `channel` package), `mc-task-queue-schedule` (cron
addon), `mc-task-queue-jdbc` (Postgres stores), `mc-taskqueue-demo-app`,
`mc-taskqueue-cluster-demo`.

Not yours: `common/` (except where step 3.1 lets you depend on `mc-jdbc`)
and `agents/` (the sub-agent engine runs on the queue; when a rename breaks
a caller there, fix it mechanically in the same PR and say so in the PR
body; do not refactor it).

## Goal

The task queue is a dependency-free library (SLF4J only) with virtual-thread
workers, suspend/resume, retries, cron scheduling and a Postgres adapter.
The review rates its core the strongest code in the repository; keep it
that way. For 1.0: one responsibility per class, short methods (aim under
30 lines) and classes (aim under 300), names after the pattern a class
implements (Registry, Dispatcher, Supervisor, Policy, Converter, Renderer,
Store, Worker), singular class names, one constructor per class, and javadoc
that says what and why in one paragraph without references to private
design notes.

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
5. Javadoc: present tense, what and why, one paragraph. No "concept 11",
   no history. What is worth keeping as design rationale goes to
   `website/docs/taskqueue/`.
6. Imports, never inline fully qualified names.
7. One constructor per class unless a second one has a real second caller.
8. Build with `mvn -f taskqueue/pom.xml clean install` and read the
   `Tests run:` / `Skipped:` counts; the JDBC tests skip themselves without
   a Postgres on the configured port.
9. Changelog: a bug fix or a removal gets a fragment
   `changelog.d/<fixed|removed|changed>/<branch-slug>.md` with
   `- **taskqueue:** **what is different.** explanation…`. Structural work
   (renames, splits, moves) gets the PR label `no-changelog` instead.
10. Everything in the repository and on GitHub is English. No
    `Co-Authored-By` trailer.
11. Branch from `next` as `feature/taskqueue-<topic>`, run
    `./after-branch-creation.sh` once, open the PR against `next`, one PR
    per step below.

## Decisions already taken

- **Converter** is the name for a class that maps an object to a
  representation and back in one class. One direction only is a
  Serializer/Writer or a Parser/Reader; object to object is a Mapper;
  object to something a person looks at is a Renderer.
- The area's ports keep the word **Store** (`TaskStore`, `ChannelStore`,
  `ScheduleStore`, `SharedStateStore`) unless the maintainer answers Q-03
  otherwise.

## Questions to ask the maintainer before the step that needs them

Ask briefly, in one message, as soon as you start; continue with the steps
that do not depend on the answer.

- Q-27: May `mc-task-queue-jdbc` depend on `common/mc-jdbc` (it
  re-implements `Json`, `withConnection`, `inTransaction`, `initSchema`), or
  must the taskqueue area stay free of `common/` so it can move to its own
  repository?
- Q-28: Is `SharedLockStore` on the 1.0 surface? Then it needs the JDBC
  adapter it was written for; otherwise the port moves out until a consumer
  needs it. Same for `schema-schedule-postgres.sql`, which ships with no
  `JdbcScheduleStore`.
- Q-03: `Store` (this area) or `Repository` (agents area) as the one word
  for a persistence port?

## Work, in order

### Step 1: hygiene (one PR, several commits of one kind each, label `no-changelog`)

1. Inline fully qualified names → imports: `LocalTaskQueue` (:64, :325,
   :360, :416, :462, :536-583), `InMemoryTaskStore` (:81, :110, :241, :250),
   `JdbcTaskStore` (:257, :266, :395), the `TaskStore` port itself (:111,
   :125, :143, :149). (TQ-10)
2. Javadoc citing private design notes: "concept 1/3", "concept 11",
   "concept 12" in `Channel.java:15,21`, `ChannelRegistry.java:11`,
   `TaskListener.java:8`, `TaskOutcome.java:5`, `TaskQueue.java:12`,
   `LocalTaskQueue.java:41`. Replace each by the sentence the concept stands
   for, or a link to `website/docs/taskqueue/`. (TQ-08)
3. Misplaced javadoc: `LocalTaskQueue.java:101-121` stacks three blocks on
   `withRetention`; "Observation hook…" belongs to `addListener` (:126),
   "What happens after a failed attempt…" to `withRetryPolicy` (:121).
   Essays that are rationale, not contract: `SharedLockStore.java:1-25`,
   `ScheduleStore.java:1-15`, `TaskStore.finish` (:78-96); move to the docs
   site, keep one paragraph. (TQ-09, CM-12)
4. `InMemorySharedLockStore.java:84` builds a key with a literal NUL
   character in the source (`id + "\0" + key` written as a raw byte).
   Write the escape, or use a small key record.
5. `TaskSchedule.java:50-90`: eight `with…` methods each re-list all twelve
   components. A private `copy(...)` or fewer withers. (TQ-14)
6. Dead DDL (after Q-28): `mc-task-queue-jdbc/src/main/resources/ai/mindconnect/taskqueue/jdbc/schema-schedule-postgres.sql`
   has no `JdbcScheduleStore`; implement the store (the port is five
   methods) or delete the file. (TQ-07)

Verification: `mvn -f taskqueue/pom.xml clean install` green with test
sources untouched.

### Step 2: bugs (one PR each, failing test first, changelog fragment under `fixed`)

1. `channel/memory/InMemoryChannelStore.java:73-76`: `lastSeq` is derived
   from the surviving events, so a `purgeOlderThan` that drops everything
   resets the seq space to 0, while `ChannelStore.java:21-32` promises "the
   head survives, the seq space never restarts" and `JdbcChannelStore`
   keeps the head in `mc_channel`. Keep a `lastSeq` per channel like the
   JDBC store. Test: append, purge everything, append again, the new seq is
   greater than the old. (TQ-13)
2. `channel/ChannelRegistry.withIdleEviction:116-129` starts a sweeper
   thread with no way to stop it, unlike `PersistentChannels`
   (`AutoCloseable`, :85-92), `LocalTaskQueue` and `TaskScheduler`. Make
   `ChannelRegistry` `AutoCloseable` or return the sweeper handle. (TQ-12)
3. JDBC exceptions: `JdbcChannelStore` throws `IllegalStateException`
   (:36-40, :71, :92, :103, :115, :128) while the sibling stores throw
   `TaskQueueException`; everywhere the `SQLException` cause is discarded
   (`"…" + e.getMessage()` at `JdbcTaskStore:82, 633, 689`,
   `JdbcSharedStateStore:117`). One exception type with the cause attached
   (`TaskQueueException(String, Throwable)`). (TQ-05)

### Step 3: module seams (one PR each, label `no-changelog`)

1. After Q-27: `mc-task-queue-jdbc` depends on `common/mc-jdbc` and drops
   its own `jdbc/Json`, the second `ObjectMapper` in
   `JdbcChannelStore.java:16-20`, the three `initSchema` copies
   (`JdbcTaskStore:66-84`, `JdbcChannelStore:26-41`,
   `JdbcSharedStateStore:16-28`) and the two `Work`/`withConnection` copies
   (`JdbcTaskStore:598-648`, `JdbcSharedStateStore:107-118`) in favour of
   `Sql.executeResource`, `Sql.inTransaction`, `Json`, `Row`. Only the
   SKIP LOCKED and lease SQL stays here. (TQ-04)
2. After Q-28: `JdbcSharedLockStore` for `SharedLockStore`
   (`mc-task-queue/.../SharedLockStore.java:1-25` justifies itself entirely
   by cross-process exclusion yet only `InMemorySharedLockStore` exists), or
   the port moves out of the core. (TQ-06)
3. The cluster demo is wired to adapters, not ports:
   `ClusterDashboardRenderer.java:50-51` takes `JdbcTaskStore` /
   `JdbcChannelStore` for `countByStatus` (`JdbcTaskStore.java:448-461`, a
   public non-port method); `WorkerEventPublisher`, `MasterSubmitNudger`,
   `ClusterNudgeController` take `LocalTaskQueue` for `nudge()` /
   `addAdvisor`. Add `countByStatus` to `TaskStore` (trivial in memory) and
   put `nudge()` on `TaskQueue` or a `Nudgeable` interface. (TQ-15)

### Step 4: renames (one PR, one commit per rename, IDE refactoring only, tests untouched, label `no-changelog`)

- `PersistentChannels` → `PersistentChannelRegistry`
- `TaskTypes` / `DemoTaskTypes` → `TaskTypeCatalog` (one class after
  step 5.1)
- `ClusterHttp` → `NudgeClient`
- `WorkerProcessManager` → `WorkerProcessSupervisor`
- `TaskBoardRenderer` / `TasksRenderer` → `TaskBoardView` (state) +
  stateless `TaskBoardRenderer` (done in step 5.2)
- the package-private `jdbc/Json` → gone with step 3.1
- After Q-03, if the answer is `Repository`: `TaskStore` →
  `TaskRepository` and so on for every port; the `ChannelStore` javadoc
  already says "the ChannelRepo".

Verification: a grep for each old name returns nothing in code and
`website/docs/taskqueue/`; the area builds.

### Step 5: god classes and duplication (one PR per class, in this order; stop after each for review)

1. **The two demos are one app copied.** `ScrapePageWorker`,
   `CountdownWorker`, `SpaForwardingFilter` are byte-identical between
   `mc-taskqueue-demo-app` and `mc-taskqueue-cluster-demo` (only the package
   differs); `DemoTaskTypes` ↔ `TaskTypes`, `NewTaskController`,
   `TaskStreamController`, `TaskBoardController` ↔ `TasksController`,
   `TaskBoardRenderer` ↔ `TasksRenderer` (54 of 433 lines differ),
   `DemoLayoutAdvice` ↔ `ClusterLayoutAdvice` differ only in names and
   property prefixes; the static form-helper bag `str` / `intVal` /
   `boolVal` / `httpUrl` (`DemoTaskTypes.java:146-178`) is repeated in
   `ScrapePageWorker.java:191`. Extract `mc-taskqueue-demo-ui` (workers,
   controllers, renderer, layout advice) that both apps depend on; the
   cluster demo adds only its `Cluster*` classes; the helper bag becomes a
   `FormValues` value object. (TQ-01)
2. **The board renderer is a stateful controller.**
   `TaskBoardRenderer` holds selection state (:70-76), subscribes to
   channels in `@PostConstruct` (:85-93), keeps two bounded logs and
   busy-waits with `Thread.sleep` in `selectChannel` (:121-132). Split into
   `BoardSelection` (state), `EventFeed` (subscription + deque),
   `ChannelInspector`, and a `TaskBoardRenderer` that is a pure function of
   the state. (TQ-11)
3. **`LocalTaskQueue`** (590 lines, `mc-task-queue/local`). Dispatcher loop
   (:306-346), maintenance loop with lease renewal, retention and recovery
   (:262-302), attempt lifecycle `run` / `finishRound` (:363-450), advisor
   chain (:452-460), listener fan-out (:462-471), the inner `Context`
   (:514-589). Split into `TaskDispatcher`, `QueueMaintenance` (or
   `LeaseKeeper` + `RetentionSweep`), `TaskAttempt` (run one round, persist
   the outcome), `AdvisorChain`, `TaskListeners` (exception-safe composite),
   `LocalTaskContext`; `LocalTaskQueue` keeps the port methods and the
   wiring. The existing queue tests must pass unchanged; add a test per
   extracted class only where it has behaviour of its own (retention cut-off,
   lease renewal interval). (TQ-02)
4. **`JdbcTaskStore`** (721 lines). DDL (:66-84), two hand-maintained
   20-column bindings that must stay in sync (`update` :505-528 vs
   `bindRecord` :530-551) plus `read` (:560-583), connection/transaction/
   retry plumbing (:598-690, gone after step 3.1), lease recovery
   (:416-446), and a tree purge (:361-392) that duplicates
   `InMemoryTaskStore.purgeTerminal` (:81-123). Extract `TaskRowMapper`
   (bind and read from one column list), `LeaseRecovery`
   (`recoverExpired`, `recoverLostWakes`), and a `TaskTree.purgeable(records,
   before)` in the core that both stores use. Needs a Postgres to verify;
   say in the PR body which tests ran against a real database. (TQ-03)

Verification for every split: the module's tests unchanged and green; the
old class is gone or a thin facade; no new class above ~300 lines and no
method above ~50.

### Step 6: docs

`taskqueue/README.md` and `website/docs/taskqueue/**` in sync with every
rename and removal in the same PR. At the end the docs site carries the
rationale that left the javadoc (channels and seq spaces, leases, retention,
shared locks), one page per topic.

## When you are unsure

Ask the maintainer in one short message with the options and your
recommendation. Do not pick silently where two readings lead to different
code. Do not widen a PR to fix something you noticed; note it in the PR body.
