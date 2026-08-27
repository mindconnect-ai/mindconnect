---
title: Suspend & wake
sidebar_position: 3
---

# Suspend & wake

## The lifecycle

A task moves through `TaskStatus`:

```
QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED
              ↘ SUSPENDED ↗   (parked, holding no thread)
```

Two different things are both called "state" — keep them apart:

- **`status`** is the queue's lifecycle enum above.
- **`state`** is the worker's own map: live progress while RUNNING
  (`ctx.updateState(...)` is immediately visible via `queue.get(id).state()` —
  what a task-manager UI renders), the continuation cursor across SUSPENDED,
  and post-mortem context on terminal records. Keep it small and data-only.

## Suspending instead of blocking

A worker that needs other tasks does not block — it suspends and is woken,
holding no thread and no worker slot while it waits:

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

**Use `ctx.isResumed()`, not an empty state map**, to tell first delivery from
continuation — that answer comes from the queue, not from a guess. A wake-up is
a store transition: a SUSPENDED task survives restarts and can resume on
another node.

The `TaskOutcome` variants: `done(result)`, `suspendUntil(ids…)`,
`suspendUntilChildren()`, `suspendUntilNotified()`.

## The task tree is API

`ctx.submitChild(type, payload)` sets the parent link (and bumps the child's
priority so a parked parent doesn't starve behind fresh roots). The relation is
queryable in both directions — `ctx.children()` / `queue.children(id)` down,
`record.parentTaskId()` up — so nothing has to be threaded through `state()` by
hand.

`suspendUntilChildren()` resolves the children when the task actually parks —
under the store lock, so a child submitted a heartbeat earlier still counts.
Naming ids explicitly (`suspendUntil(a, b)`) stays possible for waits that are
not parent/child.

**Cancel cascades along the same links**: `queue.cancel(parentId)` cancels the
children, their grandchildren, and QUEUED siblings that never started.

## Notifications

`suspendUntil(ids)` is a **join**: the store owns the waiting set
(`record.waitingFor()`), strikes out every awaited task that turns terminal,
and wakes the parent when the last one is gone. **A notification always wakes
too** — a message is a wakeup, full stop:

```java
queue.register("child", ctx -> {
    ctx.notifyParent(Map.of("child", name));                  // wakes the parent
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
            ? TaskOutcome.done(summary())
            : TaskOutcome.suspendUntil(open);
});
```

`waitingFor` never lies: it survives claims, retries and notification wakes, so
a parent woken by a message still sees its open children — it cannot mistake
"someone said something" for "everyone is done".

Notifications are not limited to children: `ctx.notifyTask(id, payload)`
reaches any task, `queue.notify(id, …)` reaches one from outside (a webhook,
an operator), and `suspendUntilNotified()` waits for a message with no awaited
set at all.

**A notification is a durable fact, not an event in flight** — which makes the
two races harmless: a message that arrives *before* the target parks waits in
the mailbox (and `suspend` refuses to park while the mailbox is non-empty), and
one that arrives *while* the target runs is delivered on the next round.

## Shared state across tasks

For several tasks working on one thing (e.g. a crawl's visited-URL set), the
`SharedStateStore` port provides a per-task-tree map with an atomic claim:
`putIfAbsent(id, key, value)` — plus `put`, `get`, `all`, `clear`. The
`SharedLockStore` port adds leased mutual exclusion (`acquire` / `renew` /
`release`), so a dead holder lets go.
