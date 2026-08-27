---
title: JDBC persistence & clustering
sidebar_position: 5
---

# JDBC persistence & clustering

`mc-task-queue-jdbc` swaps the in-memory stores for Postgres — plain JDBC on a
`DataSource`, no Spring required. Because the **store owns all atomic
operations**, this is the whole cluster story: several JVMs pointing their
`LocalTaskQueue` at the same database *are* the cluster. There is no broker,
no leader election, no extra queue service.

```xml
<dependency>
  <groupId>ai.mindconnect</groupId>
  <artifactId>mc-task-queue-jdbc</artifactId>
  <version>0.0.2</version>
</dependency>
```

## The stores

| Store | Purpose |
|-------|---------|
| `JdbcTaskStore(dataSource, nodeId, lease)` | Task records. Claiming uses `SELECT … FOR UPDATE SKIP LOCKED`; a claimed task carries a **lease** (`lease_owner` / `lease_expires_at`) that the queue renews while the worker runs — `recoverExpired()` requeues tasks whose node died. |
| `JdbcChannelStore(dataSource, eventClass)` | Durable channel events (`append` / `readAfter` / `purge…`) — the store behind `PersistentChannel`, also usable as a cluster event relay. |
| `JdbcSharedStateStore(dataSource)` | Shared per-task-tree state; `putIfAbsent` is `INSERT … ON CONFLICT DO NOTHING`. |

Each store has an idempotent `initSchema()` (`CREATE TABLE IF NOT EXISTS`); the
DDL also ships as plain SQL files under
`ai/mindconnect/taskqueue/jdbc/` if you prefer migrations.

The `mc_task` table is one row per task — queue entry, status and audit trail
in one — with `payload` / `waitingFor` / `notifications` / `state` / `failure`
as JSONB and partial indexes for claiming, parent lookups, leases and the
suspend join.

:::note Not (yet) in JDBC
There is **no `JdbcScheduleStore`** (the schedule DDL ships, the implementation
doesn't — the [cron addon](./scheduling.md) currently persists in-memory only)
and **no JDBC `SharedLockStore`**.
:::

## The cluster demo

`mc-taskqueue-cluster-demo` is one Spring Boot jar with two roles:

- **master** (`:9100`) — submits work, serves the live board, supervises the
  worker processes (`WorkerProcessManager`, with an `OrphanWatchdog`).
- **worker** (`:9101+`) — registers the workers and claims from the shared
  store.

Both point at the same Postgres (a `docker-compose.yml` brings one up on
`:5433`). Configuration lives under `mindconnect.cluster.*` (role, worker
count, lease, retention, db url/user/password).

Two HTTP **nudges** (`POST /cluster/nudge`, `POST /cluster/channels/nudge`)
tell idle nodes "look now" so work starts without waiting for the next poll —
they are accelerators only; correctness never depends on them. Task events hop
between JVMs via a `ChannelRelay` over the `JdbcChannelStore`, so the master's
board shows what the workers do.

Run it:

```bash
docker compose -f taskqueue/mc-taskqueue-cluster-demo/docker-compose.yml up -d
mvn -f taskqueue/mc-taskqueue-cluster-demo/pom.xml spring-boot:run
```

The master spawns its worker JVMs itself; kill one mid-crawl and watch the
lease expire, the task requeue, and another worker pick it up.
