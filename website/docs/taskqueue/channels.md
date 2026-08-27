---
title: Observation — channels & listeners
sidebar_position: 4
---

# Observation: channels & listeners

The queue separates **policy** from **observation**:

- **`TaskAdvisor`** (policy): `beforeSubmit` validates/enriches/**rejects** a
  submission (throwing propagates to the submitter); `aroundExecute` wraps
  every worker run as an ordered chain (`chain.proceed(ctx)`) — timing, MDC,
  resource semaphores, or short-circuiting without running the worker. Advisor
  exceptions are real: they fail the submit or the execution.
- **`TaskListener`** (observation): `onSubmitted / onStarted / onStateChanged /
  onSuspended / onWoken / onTerminal` — after the fact, default no-ops,
  exceptions swallowed; may change nothing, can break nothing.

Both are registered on `LocalTaskQueue` (`addAdvisor`, `addListener`).

## Channels — the observation plane

`ai.mindconnect.channel` is an independent package for live event streams:

- **`Channel<E>`** (ephemeral): `ChannelRegistry.channel(id)` materializes
  lazily, `evictIdle` sweeps stale ones. `publish` **never blocks** — each
  subscriber has a bounded queue (default 4096) drained by a virtual thread,
  drop-oldest. `subscribe(afterSeq)` replays the ring-buffer tail (default
  2048) and continues live in total order; an optional `Predicate` filter does
  read-side *selection* (e.g. "no token deltas") without changing the seq.
- **`PersistentChannel`** (durable): publish appends to a `ChannelStore`
  *first* — the store assigns the seq, live delivery mirrors it, one seq space
  forever. `subscribe(afterSeq)` has the reconnect bridge built in: replay from
  the **store**, then attach live gap- and duplicate-free.
  `PersistentChannels.withRetention(maxAge, keepLastEvents, interval)` prunes.

There is deliberately **no publish-side advisor** on channels: mutating events
in flight would fork the truth against the store replay. Policy belongs at the
producer (`TaskAdvisor` / `ToolAdvisor`) or the transport edge. Transports
(SSE, WebSocket, the cluster relay) are thin adapters on `subscribe`; commands
never travel over channels.

## The live task view

`TaskChannelBridge` wires listener and channel together — admin tools and CLIs
get 0..n subscribers, replay for late joiners, and a bounded per-subscriber
queue that keeps a hanging viewer from slowing the queue:

```java
var channels = new ChannelRegistry<TaskEvent>();
queue.addListener(TaskChannelBridge.global(channels));   // or .routed(channels, TaskRecord::type)

channels.channel(TaskChannelBridge.ALL_TASKS)
        .subscribe(0, e -> render(e.value().type(), e.value().task()));
```

Events: `SUBMITTED · STARTED · STATE · SUSPENDED · WOKEN · TERMINAL`, each with
the record as it looked then. **`STATE` is the interesting one** — a worker's
`ctx.updateState(...)` fires it, so the view shows "round 3, calling
web_search" instead of a task that just says RUNNING for two minutes.
`mc-taskqueue-demo-app` renders exactly this stream over SSE on port 9094.

## Logging

Attach, don't instrument:

```java
queue.addListener(new LoggingTaskListener())
     .addAdvisor(MdcTaskAdvisor.withPayloadKeys("responseId", "sessionId"));
```

`LoggingTaskListener` turns the queue's events into log lines; because workers
write their progress into the task state, the whole narrative appears without a
log statement in the core. `MdcTaskAdvisor` puts the task's identity into the
MDC around every execution (an advisor, not a listener, because it must set
before and restore after — every task runs on its own virtual thread).
