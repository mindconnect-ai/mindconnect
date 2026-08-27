---
title: Getting started
sidebar_position: 2
---

# Getting started

## Add the dependency

```xml
<dependency>
  <groupId>ai.mindconnect</groupId>
  <artifactId>mc-task-queue</artifactId>
  <version>0.0.2</version>
</dependency>
```

That's the whole queue. No Spring, no database — the in-memory store is built
in.

## First worker

A worker is a lambda: it gets a `TaskContext`, returns a `TaskOutcome`.

```java
try (LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore())) {
    queue.register("echo", ctx ->
            TaskOutcome.done("echo: " + ctx.task().payload().get("text")));

    String id = queue.submit(TaskSubmission.of("echo", Map.of("text", "hi")));
    TaskRecord done = queue.await(id, Duration.ofSeconds(5));   // parks a virtual thread
    System.out.println(done.result());
}
```

`LocalTaskQueue` is `AutoCloseable`; it runs two platform daemon threads
(dispatcher + maintenance) and executes every worker on its own virtual thread.
On construction it sweeps orphaned RUNNING tasks from a previous crash.

## Submissions

`TaskSubmission` is a fluent value object:

```java
queue.submit(TaskSubmission.of("report", payload));                         // now
queue.submit(TaskSubmission.of("report", payload).after(Duration.ofMinutes(5)));
queue.submit(TaskSubmission.of("report", payload).at(tomorrowAtNine));      // fixed time
queue.submit(TaskSubmission.of("flaky",  payload).withMaxAttempts(3));      // retries
queue.submit(TaskSubmission.of("urgent", payload).withPriority(10));        // beats FIFO
```

One field, `runAfter`, covers a delay, a fixed point in time and the pause
between two retry attempts. The due check lives **inside** the store's
`claimNext`, so a task whose time has not come never leaves `QUEUED` — and the
dispatcher sleeps until the next task is due instead of polling.

## Failure & retry

A worker that throws is done — the queue captures exception type, message and
stack trace into a `TaskFailure` on the record, so a UI can show what went
wrong without grepping logs.

What happens next is the `RetryPolicy` (configured on the queue, default
exponential backoff from 200 ms up to 5 min) — but **`maxAttempts` defaults to
1**, so nothing retries unless the submission asked for it. How often a job is
worth retrying is a property of the job, not a queue-wide setting.

## Configuring the queue

```java
var queue = new LocalTaskQueue(store)             // or (store, maxConcurrent); 0 = unbounded
        .withMaintenanceInterval(Duration.ofSeconds(20))
        .withRetention(Duration.ofHours(1))       // purge terminal records; null = keep forever
        .withRetryPolicy(RetryPolicy.exponentialBackoff(
                Duration.ofMillis(200), Duration.ofMinutes(5)))
        .addListener(new LoggingTaskListener())
        .addAdvisor(MdcTaskAdvisor.withPayloadKeys("responseId", "sessionId"));
```

Avoid a bounded `maxConcurrent` when parents await children — it deadlocks;
see [Suspend & wake](./suspend-and-wake.md) for the non-blocking pattern.

`queue.nudge()` is an external "look now" accelerator (used by the cluster
demo's HTTP nudges); correctness never depends on it.

## Testing without waiting

`new InMemoryTaskStore(clock)` takes a steerable `java.time.Clock`, so delayed
submissions and retries are testable without sleeping.

## Spring wiring

The queue is framework-free; in a Spring app it's just beans — this is the
demo app's actual wiring:

```java
@Bean
LocalTaskQueue taskQueue(TaskStore store, ChannelRegistry<TaskEvent> channels) {
    return new LocalTaskQueue(store)
            .withRetention(retention)
            .addListener(new LoggingTaskListener())
            .addListener(TaskChannelBridge.global(channels))
            .addAdvisor(MdcTaskAdvisor.withPayloadKeys("url", "startUrl"));
}
```

with an `ApplicationRunner` calling `queue.register(type, worker)` per worker.
See `mc-taskqueue-demo-app` for the complete example (including
`CountdownWorker`, a small worker that shows state updates, cancel checks and
planned failure + retry).
