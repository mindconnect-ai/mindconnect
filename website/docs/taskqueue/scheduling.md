---
title: Cron scheduling
sidebar_position: 6
---

# Cron scheduling

One-off delays (`after`, `at`) and retries are built into the core — see
[getting started](./getting-started.md#submissions). For **recurring** work
there is `mc-task-queue-schedule`: cron-style `TaskSchedule` definitions in
their own store, where every firing becomes an ordinary `TaskSubmission`.

```xml
<dependency>
  <groupId>ai.mindconnect</groupId>
  <artifactId>mc-task-queue-schedule</artifactId>
  <version>0.2.2</version>
</dependency>
```

## Defining and running schedules

```java
var scheduler = new TaskScheduler(queue, new InMemoryScheduleStore());
scheduler.schedule(TaskSchedule.of("nightly-report", "report", "0 0 3 * * *", payload)
        .named("Nightly report")
        .in(ZoneId.of("Europe/Berlin"))
        .withMaxAttempts(3));
scheduler.start();
```

`TaskSchedule` parses its cron eagerly (a typo fails at definition time, not at
3 a.m.) and carries `priority` / `maxAttempts` forward into each submission.
The scheduler sleeps until the next firing (`withMaxSleep` caps the nap);
`tick()` and a steerable `Clock` make it testable without waiting.

## Safe on every node

There is no leader election: the scheduler can run on **every** node, because
firing is a compare-and-set on the store — `ScheduleStore.claimFiring(id,
firedFor)` lets exactly one node win each occurrence; the others simply move
on. `recordFired(id, taskId)` links the schedule to the task it produced.

:::note Store implementations
Only `InMemoryScheduleStore` ships today. The Postgres DDL for a schedule
store exists in `mc-task-queue-jdbc`, but the `JdbcScheduleStore` itself is
not implemented yet — durable schedules need your own `ScheduleStore`.
:::

## Cron syntax

`CronExpression` is hand-written and dependency-free:

- 5 fields (`min hour dom mon dow`) or 6 (seconds first);
- names (`MON`, `JAN`), ranges, steps and lists (`1-5`, `*/15`, `1,15`), `?`;
- shortcuts `@hourly`, `@daily` / `@midnight`, `@weekly`, `@monthly`,
  `@yearly`;
- day-of-month OR day-of-week semantics (like classic cron), DST handled by
  `java.time`.
