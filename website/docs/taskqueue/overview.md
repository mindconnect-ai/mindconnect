---
title: Overview
sidebar_position: 1
---

# Task queue

`mc-task-queue` is a small, dependency-free task queue for Java 21: durable
task records, virtual-thread workers, suspend/resume, retries, scheduling and
an optional Postgres backend. It is deliberately **agent-agnostic** — it lives
in its own `taskqueue/` tree, and the agent runtime is just one consumer (the
sub-agent engine runs on it).

The core jar depends on nothing but `slf4j-api` and ships **two independent
packages** (neither imports the other):

- `ai.mindconnect.taskqueue` — the **control plane**: durable task records,
  claim, suspend/wake, cancel.
- `ai.mindconnect.channel` — the **observation plane**: live event channels
  with replay, used for task views, SSE streams and cluster relays.

## Modules

| Module | What it is |
|--------|------------|
| `mc-task-queue` | Core: ports + local virtual-thread implementation (`LocalTaskQueue`, `InMemoryTaskStore`) and the channel package. |
| `mc-task-queue-schedule` | Cron addon: `TaskSchedule` definitions with a built-in, dependency-free cron parser; every firing becomes an ordinary task submission. |
| `mc-task-queue-jdbc` | Postgres-backed stores (plain JDBC on a `DataSource`, no Spring): `JdbcTaskStore`, `JdbcChannelStore`, `JdbcSharedStateStore`. |
| `mc-taskqueue-demo-app` | Single-process Spring Boot demo on **:9094** — a live task board streaming every event over SSE. |
| `mc-taskqueue-cluster-demo` | One jar, two roles (master **:9100** + worker JVMs) against a shared Postgres — "the cluster is the store, not the queue". |

## The contract

Three rules are binding for every worker, even where the local implementation
wouldn't force them — they are what keep the cluster path open:

1. **Payloads are data only** — ids and values, JSON-shaped. Never lambdas,
   handles or services; workers resolve everything through repositories.
2. **At-least-once** — a task may run more than once (retry, crash recovery).
   Workers are idempotent or mark progress in domain storage first.
3. **Cancel is cooperative** — a flag in the store; workers check
   `ctx.cancelRequested()`. The local queue interrupts the thread only as a
   best-effort accelerator.

## Design points

- **Workers are virtual threads, unbounded by default.** Resource limits belong
  on the resources (e.g. an LLM-call semaphore in a `TaskAdvisor`), not on the
  workers — a bounded pool deadlocks as soon as a parent task awaits a child.
- **Priority beats FIFO** (`priority = depth` by convention): child tasks
  overtake queued roots, so awaiting parents never starve.
- **The store owns the atomic operations** (`claimNext`, `requestCancel`,
  `finish`, …) because their atomicity is storage-specific: in-memory =
  `synchronized`, Postgres = `SELECT … FOR UPDATE SKIP LOCKED` + lease. A
  cluster implementation is a new *store*, not a new queue.
- **Startup sweep**: RUNNING tasks from a crashed process fail loudly with
  `"interrupted by restart"` — the record is the truth, silence is not.

## In this section

- **[Getting started](./getting-started.md)** — dependency, first worker,
  submissions, retry.
- **[Suspend & wake](./suspend-and-wake.md)** — the task lifecycle, child
  tasks, notifications, cancel.
- **[Observation: channels & listeners](./channels.md)** — live task views,
  `TaskListener` / `TaskAdvisor`, logging.
- **[JDBC persistence & clustering](./persistence-and-clustering.md)** — the
  Postgres stores, leases, the cluster demo.
- **[Cron scheduling](./scheduling.md)** — the `mc-task-queue-schedule` addon.
