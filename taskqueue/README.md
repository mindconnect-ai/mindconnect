<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="../.github/assets/logo-dark.svg">
    <img alt="Mindconnect" src="../.github/assets/logo-light.svg" width="160">
  </picture>
</p>

<h1 align="center">taskqueue</h1>

A **dependency-free task queue for Java 21** — durable task records, workers
on virtual threads, suspend/resume without holding a thread, retries with
backoff, and a channel package for live observation (replay + fan-out).
The core is ports + a local implementation; a Postgres store turns the same
queue into a cluster (`SELECT … FOR UPDATE SKIP LOCKED` + leases), because
**the cluster is the store, not the queue**.

This area stands on its own — it is deliberately agent-agnostic and does not
depend on `agents/` or `workflow/`. (The agents sub-agent engine runs on it,
but agent turns are just one caller among others.)

## Highlights

- **Suspend & wake** — a parent task parks without a thread or worker slot,
  is woken by its children or by notifications, and survives restarts
- **Task trees** — `submitChild` / `children()` / cascading cancel; priority
  beats FIFO so awaiting parents never starve
- **Scheduling & retry in one field** — `runAfter` covers delays, fixed
  points in time and the pause between attempts; retry policy per task
- **Channels** — ephemeral (token/delta streams, drop-oldest, never blocks)
  and persistent (store-backed seq, gap-free reconnect); transports like SSE
  are thin adapters on `subscribe`
- **Extension points** — `TaskListener` (observation, can break nothing) and
  `TaskAdvisor` (policy: validate, wrap, retry, rate-limit)
- **Cluster-ready contract** — data-only payloads, at-least-once delivery,
  cooperative cancel; a cluster implementation is a new store, not a new queue

## Modules

| Module | Purpose |
|--------|---------|
| [`mc-task-queue`](mc-task-queue/README.md) | Core: ports + local virtual-thread implementation, plus the channel (observation) package |
| `mc-task-queue-schedule` | Cron scheduling addon |
| `mc-task-queue-jdbc` | Postgres-backed stores: SKIP LOCKED claims, leases, durable channels |
| [`mc-taskqueue-demo-app`](mc-taskqueue-demo-app/README.md) | Runnable demo: live task board streaming every `TaskEvent` over SSE (port 9094) |
| [`mc-taskqueue-cluster-demo`](mc-taskqueue-cluster-demo/README.md) | Runnable cluster demo: master + worker processes on one shared Postgres store (port 9100) |

Start with the [mc-task-queue README](mc-task-queue/README.md) — it walks
through the whole model with code: suspend/wake, task trees, notifications,
retries, listeners/advisors and the live task view.

## Build & test

```bash
# Whole tree
mvn -f taskqueue/pom.xml clean install -DskipTests

# One module's tests
mvn -f taskqueue/mc-task-queue/pom.xml test
```

Java 21.
