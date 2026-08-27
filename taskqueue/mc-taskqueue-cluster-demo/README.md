# mc-taskqueue-cluster-demo

The task queue as a cluster — and the proof that **the cluster is the store,
not the queue**: workers run the unchanged `LocalTaskQueue` against a shared
Postgres `JdbcTaskStore`, and everything else is process management and
acceleration.

```bash
podman compose up -d          # Postgres on :5433 (or docker compose)
# macOS + podman: run agents/server/mc-agent-admin-ui-app/keycloak/fix-podman-clock.sh
# once — the VM clock drifts after host suspend, and a drifted DB clock breaks
# lease expiry and run_after scheduling in confusing ways.
mvn -f taskqueue/mc-taskqueue-cluster-demo/pom.xml package -DskipTests
java -jar taskqueue/mc-taskqueue-cluster-demo/target/mc-taskqueue-cluster-demo.jar
# → http://localhost:9100/tasks/dashboard   the cluster dashboard
# → http://localhost:9100/tasks              the tasks view (tree + detail)
```

One jar, two roles (`mindconnect.cluster.role`):

- **master** (:9100) — serves the board, submits tasks, **starts the workers
  as real OS processes** (`java -jar`, own port each, logs in
  `logs/worker-<port>.log`), watches them via `ProcessHandle` and restarts
  crashed ones. Registers no task types, so it never claims work.
- **worker** (:9101, :9102, …) — the unchanged `LocalTaskQueue` +
  `JdbcTaskStore`; claims via `FOR UPDATE SKIP LOCKED`, holds a lease per
  running task, renews it from the maintenance loop.

## The two planes across processes

**Control plane = the store.** Submit, claim, suspend/wake, cancel, retry —
all row transitions in `mc_task`. A parent on worker A parks on children that
run on worker B; the join lives in `waiting_for`, not in any process.

**Observation plane = the durable channel.** Workers publish every `TaskEvent`
through a `PersistentChannel` — the `JdbcChannelStore` assigns one gapless seq
per channel — and the master's `ChannelRelay` mirrors the store into its local
registry via `publishAt(seq, …)`. The board cannot tell a clustered channel
from a local one, which is why its SSE/replay/inspector code is byte-for-byte
the single-process demo's.

**REST nudges are accelerators, never the mechanism** (same rule as the
cancel interrupt): `POST /cluster/nudge` says "the queue changed, look now",
`POST /cluster/channels/nudge` says "the channel grew, read it". A lost nudge
costs one poll interval (500ms dispatcher tick, 3s relay pass) — never a task,
never an event.

## The dashboard

`/tasks/dashboard`, live over its own SSE stream: a tiny channel tile (the
durable channel's seq and subscriber count), the queues as tabs
(Waiting / Suspended / Completed), and one group per worker node showing what
runs there right now — the `lease_owner` column made visible. Every running
task has a Cancel; every node has Kill (SIGKILL), Stop (hands its running
tasks back to the store immediately), Start, and Delete (stopped slots only).
Task events and worker lifecycle changes push the same replace-based patch —
and the tab sections are never patch targets, so the tab you picked survives
every live update. Submitting a new task lands here.

## The demo worth showing

Open the dashboard, submit a long `countdown` or a `crawl`, and **Kill**
the worker that runs it (SIGKILL, no goodbye). For `lease` (default 30s) the
row keeps its dead owner — then the surviving worker's maintenance loop
reclaims it and the task reruns with `attempt+1`, visible on the board.
At-least-once, watchable:

```
SELECT type, status, attempt, lease_owner FROM mc_task;
  countdown | RUNNING | 1 | worker:9102     ← killed
  countdown | RUNNING | 2 | worker:9101     ← 30s later
```

A `crawl` also shows work sharing (pages split across `lease_owner`s) and the
cross-process visited set: the `JdbcSharedStateStore` claim
(`INSERT … ON CONFLICT DO NOTHING`) dedups URLs between workers.

## Layout

- `ClusterConfig` / `ClusterProperties` — the JDBC wiring; node id = role:port,
  readable in `lease_owner`
- `WorkerProcessManager` — start/watch/stop/kill; restart policy is ops,
  correctness is the lease
- `WorkerEventPublisher` — worker: queue events → durable channel + nudges
- `ChannelRelay` — master: durable channel → local registry (`publishAt`)
- `ClusterNudgeController` — `/cluster/nudge`, `/cluster/channels/nudge`,
  `/cluster/health`
- `ClusterDashboardRenderer` / `ClusterUiController` / `ClusterDashboardStreamController`
  — the `/tasks/dashboard` page, its verbs, and its SSE stream

Self-contained on purpose: UI, workers and task types were COPIED from
[mc-taskqueue-demo-app](../mc-taskqueue-demo-app/README.md) and evolve
independently — the two demos share the task-queue libraries, not each
other's code.
