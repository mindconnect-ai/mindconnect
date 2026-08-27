# mc-task-queue

Task queue ports with a local virtual-thread implementation — deliberately
agent-agnostic, which is why it lives in its own `taskqueue/` tree rather than
under `agents/`: agent turns today, workflow runs and ingestion jobs tomorrow,
a remote queue and a dispatcher UI as siblings. Concepts: [11-task-queue](../../agents/doc/architecture/concepts/11-task-queue.md),
[12-streams-and-tasks](../../agents/doc/architecture/concepts/12-streams-and-tasks.md).

Ships TWO independent packages in one jar (often used together, never
coupled — neither imports the other, so a later split is a `git mv`):

- `ai.mindconnect.taskqueue` — the **control plane**: durable task records,
  claim, suspend/wake, cancel.
- `ai.mindconnect.channel` — the **observation plane**, in two tiers:
  - **`Channel`** (ephemeral, the sub-channel for tokens/deltas):
    `ChannelRegistry.channel(id)` materializes lazily and `evictIdle` sweeps
    stale ones; `publish` NEVER blocks (per-subscriber bounded queue +
    virtual drain thread, drop-oldest); `subscribe(afterSeq)` replays the
    ring-buffer tail and continues live in total order.
  - **`PersistentChannel`** (the durable main channel): publish appends to a
    `ChannelStore` FIRST — the store assigns the domain seq, live delivery
    mirrors it, one seq space forever. `subscribe(afterSeq)` has the
    reconnect bridge built in: replay from the STORE (never the buffer, so
    eviction and crashes cost nothing), then attach live gap- and
    duplicate-free. **One truth rule**: where events already are domain data
    (agent items in the conversation store), the `ChannelStore` is an
    ADAPTER over that store — never a second table.

  Channels get two extension points of their own — and deliberately NOT a
  third: **subscription filters** (read-side SELECTION, e.g. "no token
  deltas" — the seq stays intact, clients still cursor on the real
  positions) and a **`ChannelLifecycleListener`** on the registry
  (materialized/evicted — ops metrics and leak visibility). There is NO
  publish-side advisor: mutating events in flight would fork the truth
  against the store replay (same seq, two contents) and break the reconnect
  bridge. Policy belongs at the producer (`TaskAdvisor`/`ToolAdvisor`) or
  the transport edge.

  Transports (SSE, WebSocket, the cluster event-bus bridge) are thin
  adapters on `subscribe`; commands never travel over channels.

```java
try (LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore())) {
    queue.register("echo", ctx -> TaskOutcome.done("echo: " + ctx.task().payload().get("text")));

    String id = queue.submit(TaskSubmission.of("echo", Map.of("text", "hi")));
    TaskRecord done = queue.await(id, Duration.ofSeconds(5));   // parks a virtual thread
}
```

## Suspend & wake (concept 11, variant b)

A worker that needs other tasks does not have to block — it suspends and is
woken, holding no thread and no worker slot while it waits:

```java
queue.register("parent", ctx -> {
    if (!ctx.isResumed()) {                                 // first delivery
        ctx.submitChild("child", Map.of());
        ctx.updateState(Map.of("phase", "await"));          // continuation cursor
        return TaskOutcome.suspendUntilChildren();          // → status SUSPENDED
    }
    return TaskOutcome.done("combined: " + ctx.children().get(0).result());
});
```

**`ctx.isResumed()`, not an empty state map.** Whether this is the first
delivery or a continuation is the queue's answer, not a guess — and it stays a
single method on purpose: a second `onResumed` entry point cannot be written as
a lambda, so a fan-out worker registered the usual way would silently spawn its
children again on every resume. `ctx.task().state()` is the continuation as it
was handed over (the claim-time snapshot), `ctx.state()` the live map that
`updateState` writes to.

`status` vs. `state`: `status` is the queue's lifecycle enum; `state` is the
worker's own map — **live progress while RUNNING** (`ctx.updateState(...)` is
immediately visible via `queue.get(id).state()`, which is what a task-manager
UI renders: "round 3/10, waiting for web_search"), the continuation cursor
across SUSPENDED, and post-mortem context on terminal records. Data-only and
small — an agent turn's real state is its transcript in domain storage.

Wake-up is a store transition: a SUSPENDED task survives restarts and can
resume on another node, and bounded worker pools become deadlock-free because
nobody blocks.

### The tree is API, not bookkeeping

`ctx.submitChild(type, payload)` sets the parent link (and bumps the priority so
a parked parent doesn't starve behind fresh roots). From there the relation is
queryable in both directions — `ctx.children()` / `queue.children(id)` downward,
`record.parentTaskId()` upward — so nothing has to be threaded through `state()`
by hand:

```java
queue.register("parent", ctx -> {
    if (ctx.state().isEmpty()) {
        ctx.submitChild("child", Map.of("name", "a"));
        ctx.submitChild("child", Map.of("name", "b"));
        ctx.updateState(Map.of("phase", "await"));
        return TaskOutcome.suspendUntilChildren();           // no ids to collect
    }
    return TaskOutcome.done(join(ctx.children()));          // results from the tree
});
```

`suspendUntilChildren()` closes the loop: the queue resolves the children when
the task actually parks — under the store lock, so one submitted a heartbeat
earlier still counts, and a task without children is simply requeued. Naming ids
stays possible (`suspendUntil(a, b)`) for waits that are not parent/child.

`cancel()` walks the same link, recursively: killing a parent cancels its
children, their grandchildren, and siblings that never even started — a QUEUED
child with no registered worker ends up CANCELLED like the rest.

### Reacting to each child, not only to the last

`suspendUntil(ids)` is a **join**: the store owns the waiting set, strikes out
every awaited task that turns terminal, and wakes the parent when the last one
is gone. **A notification always wakes too** — there is no flag to ask for it:
a message is a wakeup, full stop. The two facts stay separate and both stay
honest across every wake:

```java
queue.register("child", ctx -> {
    ctx.notifyParent(Map.of("child", name));                // wakes the parent
    return TaskOutcome.done("result-" + name);
});

queue.register("parent", ctx -> {
    if (!ctx.isResumed()) {
        spawnChildren(ctx);
        return TaskOutcome.suspendUntilChildren();
    }
    for (TaskNotification n : ctx.notifications()) react(n);  // what arrived
    Set<String> open = ctx.task().waitingFor();               // what is still open
    return open.isEmpty()
            ? TaskOutcome.done(summary())                     // all children done
            : TaskOutcome.suspendUntil(open);
});
```

`waitingFor` never lies: it is the not-yet-terminal remainder of the join,
kept by the store across claims, retries and notification wakes alike. A
parent woken by a message therefore still sees its open children — it cannot
mistake "someone said something" for "everyone is done".

Notifications are not limited to children: `ctx.notifyTask(id, payload)` reaches
any task, `queue.notify(id, ...)` reaches one from outside (a webhook, an
operator), and `suspendUntilNotified()` waits for a message with no awaited set
at all.

**A notification is a durable fact, not an event in flight** — which is what
makes the two races harmless:

- **It arrives before the target parks.** A fast child reports while the parent
  is still running. The message waits in the mailbox, and `suspend` refuses to
  park while the mailbox is non-empty, so the parent cannot sleep through a
  wakeup it already received.
- **It arrives while the target runs.** Nothing is interrupted. The mailbox is
  drained once per execution, so the message is delivered on the *next* round —
  the same rule seen from the other side.

The join stays authoritative for termination: `waitingFor` lives in the store
and survives a crash, so a parent never miscounts its children even if a
notification dies with a worker.

## Scheduling and retry — one field

`runAfter` says "not before this instant", and it covers a delay, a fixed point
in time and the pause between two attempts:

```java
queue.submit(TaskSubmission.of("report", payload).after(Duration.ofMinutes(5)));
queue.submit(TaskSubmission.of("report", payload).at(tomorrowAtNine));
queue.submit(TaskSubmission.of("flaky", payload).withMaxAttempts(3));   // retries
```

The due check lives **inside** `claimNext`, not above it: a task whose time has
not come must never leave QUEUED, or it would already be RUNNING by the time
anyone could put it back. That is also why it cannot be an add-on module.
The dispatcher sleeps until the next task is due instead of polling blindly.

**The queue records the failure, not the worker.** A worker throws and is done;
the queue captures exception type, message and stack trace into `TaskFailure`
on the record — so a dispatcher UI can show what went wrong instead of sending
someone to grep the logs of whichever node happened to run the attempt. The
failure stays on the record across a retry, so the reason is readable while the
task waits for its next attempt.

What happens after a failure is a `RetryPolicy`: give up, or come back after a
delay. The default backs off exponentially for as many attempts as the task
itself allows — and `maxAttempts` defaults to 1, so nothing retries unless the
submission asked for it. How often a job is worth retrying is a property of the
job, not a queue-wide setting.

Both are testable without waiting: `new InMemoryTaskStore(clock)` takes a
steerable clock.

## The contract (binding even where the local impl wouldn't need it)

1. **Payloads are data only** — ids and values, JSON-shaped. Never lambdas,
   handles or services: workers resolve everything through repositories.
   This is what keeps the cluster path open.
2. **At-least-once** — a task may run more than once (retry, crash recovery,
   `attempt` counts). Workers are idempotent or mark progress in domain
   storage first.
3. **Cancel is cooperative** — a status/flag transition in the store; workers
   check `ctx.cancelRequested()`. The local queue interrupts the thread as a
   best-effort accelerator, never as the mechanism.

## Design points

- **Workers are virtual threads, unbounded by default.** Resource limits
  belong on the resources (an LLM-call semaphore), not on the workers — a
  bounded pool deadlocks as soon as a parent task awaits a child. The
  `deepAwaitChain` test proves 20 levels of parent-awaits-child.
- **Priority beats FIFO** (`priority = depth` by convention): child tasks
  overtake queued roots, so awaiting parents never starve.
- **The store owns the atomic operations** (`claimNext`, `requestCancel`,
  `sweepRunning`) because their atomicity is storage-specific: in-memory =
  `synchronized`, Postgres = `SELECT … FOR UPDATE SKIP LOCKED` + lease. A
  cluster implementation is a new store, not a new queue.
- **Startup sweep**: RUNNING tasks from a crashed process fail with
  `"interrupted by restart"` — the record is the truth, silence is not.

## Extension points: Listener & Advisor

The concept-1 plane split as hooks (both registered on `LocalTaskQueue`):

- **`TaskListener`** (observation): `onSubmitted / onStarted / onStateChanged /
  onSuspended / onWoken / onTerminal` — after the fact, default no-ops, exceptions
  swallowed; may change nothing, can break nothing. Bridge to a `Channel`
  for fan-out, or hand off heavy work to your own executor.
- **`TaskAdvisor`** (policy): `beforeSubmit` validates/enriches/REJECTS a
  submission (throwing propagates to the submitter); `aroundExecute` wraps
  every worker run as an ordered chain — timing, MDC, retries
  (`chain.proceed` again), resource semaphores (the K11 LLM-call limit),
  or short-circuiting without running the worker. Advisor exceptions are
  real: they fail the submit or the execution.

## Live task view

A listener alone is one in-process callback on the transition's thread — fine
as the SOURCE, not enough as the delivery. `TaskChannelBridge` is both halves
wired together: it listens and publishes onto a channel, so admin tools and
CLIs get 0..n subscribers, replay for late joiners (`afterSeq`), and a
bounded per-subscriber queue that keeps a hanging viewer from ever slowing
the queue.

```java
var channels = new ChannelRegistry<TaskEvent>();
queue.addListener(TaskChannelBridge.global(channels));          // or .routed(channels, TaskRecord::type)

channels.channel(TaskChannelBridge.ALL_TASKS)
        .subscribe(0, e -> render(e.value().type(), e.value().task()));
```

Events: `SUBMITTED · STARTED · STATE · SUSPENDED · WOKEN · TERMINAL`, each
with the record as it looked then. **`STATE` is the interesting one** —
`ctx.updateState(...)` fires `onStateChanged`, so the view shows "round 3,
calling web_search" instead of a task that says RUNNING for two minutes.

## Logging

Attach, don't instrument: `LoggingTaskListener` turns the queue's events into
lines, and because workers write their progress into the task state
(`phase`, `round`, `tool`), the whole narrative appears without a statement
inside the core. `MdcTaskAdvisor` wraps every execution with the task's
identity in the MDC — an advisor, not a listener, because it must set before
and restore after (and every task runs on its own virtual thread, so nothing
is inherited).

```java
queue.addListener(new LoggingTaskListener())
     .addAdvisor(MdcTaskAdvisor.withPayloadKeys("responseId", "sessionId"));
```

The exception to "attach, don't instrument": failures a method SWALLOWS —
a broken subscriber, a filter that threw, a publish that failed — never reach
a listener, so those few places log directly at WARN.

## Ports

| | |
|---|---|
| `TaskQueue` | submit · get · cancel (cascading) · await · register |
| `SharedStateStore` | putIfAbsent (the atomic claim) · put · get · all · clear — the map several tasks working on one thing agree on |
| `SharedLockStore` | acquire · renew · release · heldUntil — mutual exclusion across nodes, leased so a dead holder lets go |
| `TaskStore` | save · find · byStatus · claimNext · requestCancel · suspend · **finish** (terminal write + wake, one atomic step) · notify · drainNotifications · retry · nextDueAt · updateState · byParent · sweepRunning · purgeTerminal |
| `TaskChannelBridge` | listener → channel: the live task view (`TaskEvent`) |
| `TaskWorker` / `TaskContext` | `execute(ctx)` → `TaskOutcome` (done / suspendUntil); `ctx.cancelRequested()`, `ctx.state()`, `ctx.updateState()` |
| `TaskRecord` | id · type · status (incl. SUSPENDED) · payload · priority · parentTaskId · attempt · waitingFor · state · result/error · timestamps |
