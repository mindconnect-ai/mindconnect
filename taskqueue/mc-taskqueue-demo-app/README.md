# mc-taskqueue-demo-app

Makes [mc-task-queue](../mc-task-queue/README.md) visible: pick a task type,
give it parameters, and watch tasks AND channels work in real time — a
semantic-ui SPA whose board streams every `TaskEvent` over SSE.

```bash
mvn -f taskqueue/mc-taskqueue-demo-app/pom.xml spring-boot:run
# → http://localhost:9094
```

## What it demonstrates

- **The live view is the library, not app plumbing.** `TaskChannelBridge`
  publishes onto a `Channel<TaskEvent>`; one SSE connection is one
  `channel.subscribe(lastSeq, …)` — replay, fan-out and bounded per-subscriber
  queues come from the channel, so there is no separate stream bus. The
  channels panel on the board shows the channel watching itself (seq,
  subscribers, idle).
- **A crawl is one worker applied to itself.** There is no coordinator task
  and no crawl type: `scrape-page` writes its page, then spawns one task of
  its own kind per link it found, right where it found them. The task tree
  that falls out IS the link tree — link depth 3 means four levels on the
  board. The two things a coordinator used to provide come from elsewhere:
  "when is the crawl done" from `suspendUntilChildren()`, which propagates
  finished from the leaves up until the ROOT page completes; and "who takes a
  URL two pages both link to" from one atomic
  `SharedStateStore.putIfAbsent` per URL — the visited set without anyone
  owning it. Cancel still cascades, now down the whole link tree.
- **Failure is data.** `countdown` with a fail step shows the
  FAILED → retry → COMPLETED arc; the recorded `TaskFailure` (with stack
  trace) is in the detail panel, not in some node's log.
- **The tree is the UI.** Tasks render as the parent/child tree the queue
  already maintains (`byParent`); clicking a node patches a detail panel next
  to it (payload/state/result/failure, cancel) — no separate page, and the
  stream keeps the selected panel live while the task runs. A task names
  itself: `state("title")` beats `payload("title")` beats the type, which is
  how every crawled page shows its URL in the tree.
- **Clearing the list is a store operation.** "Clear finished tasks" calls
  `TaskStore.purgeTerminal(now)` — whole finished trees are forgotten, running
  ones keep their whole family. The event feed is not touched: those events
  did happen, and the channel still holds them.

## Task types

| type | parameters | shows |
|---|---|---|
| `scrape-page` (as "Crawl website") | start URL, link depth, output dir, max links per page, same-host-only | a worker that spawns itself: nested tree, suspend/wake, shared claim, cascading cancel |
| `scrape-page` (as "Scrape single page") | URL, output dir | the same worker with depth 0 — one page, one `.md` file |
| `countdown` | steps, delay, fail-on-step | STATE streaming, cooperative cancel, retry with `TaskFailure` |

Pages are scraped with mc-webscraper's jsoup path (content → Markdown via
flexmark, link extraction built in) and written as `<safe-url-name>.md` under
the output directory (relative paths resolve against
`mindconnect.taskqueue-demo.output-root`, default `data/output`).

## Layout

- `TaskQueueDemoConfig` — `LocalTaskQueue` + `ChannelRegistry` + bridge wiring
- `worker/` — the two `TaskWorker`s (`scrape-page` crawls by recursion, `countdown`)
- `DemoTaskTypes` — task-type registry driving the dynamic submit form
- `ui/TaskBoardRenderer` — renders board page AND SSE patches (same node
  tree, replace-based, so replays and reconnects are idempotent); holds the
  process-wide selection that keeps the detail panel live
- `ui/TaskStreamController` — `GET /tasks/api/stream?lastSeq=N`, the SSE edge
