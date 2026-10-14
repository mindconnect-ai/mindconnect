# Naming, by example

The rule is in [conventions.md](conventions.md) ("Classes and methods"); this
is what it looks like in this repository. A reviewer cites I2 and points here.

## Classes: named after the pattern, in the singular

| Good, from the code | Why it works |
| --- | --- |
| `NamespaceRouted` | says what it does to what it wraps: routes by namespace |
| `ScopeSupplier`, `ThreadBoundScope` | the role (supplier) and the binding (thread-bound) |
| `TaskStore`, `JdbcTaskStore`, `InMemoryTaskStore` | the port and its adapters, one noun |
| `CronExpression`, `TaskSchedule` | the thing itself, not `ScheduleInfo` or `CronData` |
| `AgentDefinition` / `AgentSession` | configured versus running, the Definition : Instance pair |
| `MailProvider`, `CalendarProvider`, `MailListViewFactory` | the seam a new kind plugs into |
| `ToolCallWorker`, `PromptRenderer`, `LlmMessageMapper` | Worker, Renderer, Mapper: the pattern names the responsibility |

| Bad, found in the baseline | What the name hides | Better |
| --- | --- | --- |
| `ResourceUtil` (workflow) | a bag of functions that belong to the types they operate on | move each function to its type, or name the one role |
| `SessionTools`, `LlmPrices` (plural) | a plural says "several of something" and not what the class is | `SessionToolRoster`, `LlmPriceList` or the role: `LlmPricing` |
| `*Helper`, `*Support`, `*Holder`, `*Manager` | that the author did not find the role | Registry, Catalog, Resolver, Policy, Strategy, Factory, Builder, Repository, Adapter, Gateway |
| `*Data`, `*Info` | whether it is configured or running, a value or a view | Definition, Instance, or the plain noun |

## Methods: a verb that says what, not how

| Good | Why |
| --- | --- |
| `claimNext(Set<String> types)` | the atomic claim is the point; the name says it is one step |
| `insertIfAbsent(record)` | the condition is in the name; the javadoc explains why find-then-save cannot replace it |
| `requestCancel(id)` | request, because the task decides when |
| `markShipped()` on the aggregate | the rule lives where the state lives |

| Bad | Why |
| --- | --- |
| `processItems`, `handleRequest`, `doWork` | process, handle, do: a verb that says nothing; D4 fails them |
| `result2`, `tmpList` | a digit or a type as a name |
| `checkExpired()` returning boolean | booleans are `isExpired()`, `hasToken()`, `canResume()` |
| `getAndUpdateAndNotify()` | three verbs: three methods |

## Tests: a sentence

Test names are sentences in snake_case that state the behaviour, read from
the surefire report without opening the file:

```
a_due_task_does_not_overtake_an_undue_one_of_higher_priority
a_token_about_to_run_out_is_renewed_and_written_back
two_calls_that_find_the_same_token_running_out_refresh_it_once
```

Not `testRefresh`, not `shouldWork`, not `case3`.

## Javadoc: why, not what

Good, from `TaskStore.insertIfAbsent`: explains the invariant (why a
find-then-save pair cannot replace it) and the behaviour on the edge (what is
returned when the record exists). Nothing restates the signature.

Bad: a paragraph that walks through the method body, a "Concept 16, step 5"
reference, a history ("used to be synchronous"), a block between `@Override`
and its method.
