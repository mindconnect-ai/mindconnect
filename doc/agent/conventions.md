# Code conventions

The rules every change follows, by a person or an agent, with examples from
this repository. Match them in new code; `/tidy` moves existing code towards
them, one module at a time, `/smells` reports without changing anything. The
lines a tool can decide are ArchUnit rules (see the last section); the rest is
judgement. `/rule` adds or sharpens a line here.

## Before you write: look left and right

Most redundancy comes from not looking. Before you create a class or a
method:

- grep for the noun and the verb you are about to use (`grep -rn "class .*Price" --include=*.java`);
  a `LlmPriceList` next to an `LlmPricing` is a finding;
- read "Patterns with a name" in [architecture.md](architecture.md): typed ids,
  `NamespaceRouted`, `RuntimeFeature`, installers in the owning module, one
  injected `ObjectMapper`;
- when two modules need the same ten lines, extract the one class they both
  wanted; do not copy "so each module stays independent";
- a business rule lives once, in an application service, never in two
  controllers.

## Classes

- **One responsibility.** If you need "and" to say what a class does, it is two
  classes. Over ~300 lines or more than 7 constructor parameters is a reason
  to look.
- **Named after its pattern, in the singular.** Registry, Catalog, Resolver,
  Policy, Strategy, Factory, Builder, Repository, Adapter, Gateway, Worker,
  Renderer, Reader, Writer, Converter, Mapper. `Service` only for an
  application service with one family of use cases.
- **Converter** is both directions in one class. One direction is a
  Serializer/Writer or a Parser/Reader; object to object is a Mapper; object
  to something a person looks at is a Renderer.
- **Definition : Instance** is the pair for "what is configured" and "what
  runs" (`WorkflowDefinition` / `WorkflowInstance`). Not `Data`, not `Info`.
- **One constructor.** A second one needs a real second caller; "for tests"
  and "backward compatibility" are not callers.

| Good, from the code | Why it works |
| --- | --- |
| `NamespaceRouted` | says what it does to what it wraps: routes by namespace |
| `TaskStore`, `JdbcTaskStore`, `InMemoryTaskStore` | the port and its adapters, one noun |
| `CronExpression`, `TaskSchedule` | the thing itself, not `ScheduleInfo` or `CronData` |
| `ToolCallWorker`, `PromptRenderer`, `LlmMessageMapper` | the pattern names the responsibility |

| Bad | What the name hides | Better |
| --- | --- | --- |
| `ResourceUtil` | a bag of functions that belong to the types they operate on | move each function to its type |
| `SessionTools`, `LlmPrices` | a plural says "several of something", not what the class is | `SessionToolRoster`, `LlmPriceList` |
| `*Helper`, `*Support`, `*Holder`, `*Manager` | that the author did not find the role | the pattern names above |
| `*Data`, `*Info` | whether it is configured or running, a value or a view | Definition, Instance, or the plain noun |

## Methods

A verb that says what, not how; a method does what its name says and nothing
else. A static bag of unrelated functions belongs to the types it operates on.

| Good | Bad |
| --- | --- |
| `claimNext(types)`: the atomic claim is the point | `processItems`, `handleRequest`, `doWork`: a verb that says nothing |
| `insertIfAbsent(record)`: the condition is in the name | `result2`, `tmpList`: a digit or a type as a name |
| `requestCancel(id)`: request, because the task decides when | `checkExpired()` returning boolean: write `isExpired()` |
| `markShipped()` on the aggregate, where the state lives | `getAndUpdateAndNotify()`: three verbs, three methods |

## Javadoc and comments

The code says what; a comment says **why**, states an invariant or a side
effect. One paragraph, present tense, no history, no concept or step
references, nothing that restates the signature.

```java
// Bad: history, a concept reference, and the body retold
/**
 * Introduced in concept 16, step 5; used to be synchronous.
 * Takes the record, checks whether it exists and saves it.
 */

// Good: the invariant and why the obvious alternative is wrong (TaskStore)
/**
 * Atomically inserts {@code record} unless a task with its id already exists.
 * A find-then-save pair cannot replace this: two concurrent submits of the
 * same id would both pass the find and the second save would overwrite a
 * possibly RUNNING record.
 */
Optional<TaskRecord> insertIfAbsent(TaskRecord record);
```

No Javadoc between `@Override` and its method. A class that needs a long
comment to be understood is a finding: split or rename it instead.

## No defensive redundancy

No null check, try/catch or fallback at a place that cannot fail; no error
handling for a case that cannot happen; no abstraction for one caller; no
configuration nobody asked for. `catch (Exception ignored)` only with a
comment that says what is ignored and why it cannot matter. An exception keeps
its cause.

## Architecture

- **Hexagonal, per module pair.** A `-core` module holds domain and ports, the
  sibling the adapters, Postgres adapters live under `adapter/postgres/mc-*-pg`.
  A service depends on ports, never on an adapter.
- **No framework in the core.** `-core` modules and the engines (`mc-workflow`,
  `mc-task-queue`) import neither Spring nor Jackson databind nor JDBC. Spring
  wiring lives in `springstarter/`, an `-app`, `-rest` or `-ui` module.
- **Controllers are thin**: routing, request mapping, rendering. A controller
  never injects another controller.
- **A file format never depends on a class name.** Persisted JSON carries a
  stable type id.

## Logging, style, tests

- SLF4J everywhere, never `java.util.logging`. One injected `ObjectMapper` per
  runtime.
- Imports, never inline fully qualified names. No literal control characters
  in source.
- Test names are sentences in snake_case that state the behaviour
  (`a_due_task_does_not_overtake_an_undue_one_of_higher_priority`), not
  `testRefresh`. Test sources stay untouched by a rename, move or split.

## Working method

Think before coding and ask when two readings lead to different code. The
minimum code that solves the problem. Surgical changes: touch only what the
task needs, one topic per PR, no "improve while here"; a finding outside the
task goes to `doc/known-gaps.md` with `/gap`. Every step has a check: a failing
test before a fix, the module's tests green before and after a rename.

## What the build checks

ArchUnit rules in `common/mc-arch-rules`, run by each area's arch-tests module.
Today's violations are frozen in `<area>/*-arch-tests/archunit_store/`; a new
one fails the build, and the store only shrinks.

| Rule | Checks |
| --- | --- |
| D1 | no framework in a `-core` module or an engine; no `adapter` package in a `-core` module; Spring wiring only at the edge |
| D2 | `domain` ← `port` ← `service` ← `adapter`; an adapter does not import another module's adapter |
| D3 | a constructor takes at most 7 parameters |
| D4 | no class ending in Helper, Util(s), Support, Commons, Holder, Manager; no method starting with process, handle, do unless a supertype names it |
| Conventions | one constructor per class; SLF4J, never `java.util.logging` |
