# Code conventions

The rules the 1.0 review measured the code against, as written by the
maintainer. They apply to every change, by a person or an agent. The numbered
form the build and the reviewers cite is [quality-catalog.md](quality-catalog.md);
this file is the prose behind the numbers.

Match them in new code; move existing code towards them when you touch it, in
a PR of its own.

## Working method

- **Think before coding.** State your assumptions. When a request allows
  two readings that lead to different code, ask instead of picking one
  silently. When a simpler approach exists, say so before building the
  asked-for one.
- **Simplicity first.** The minimum code that solves the problem. No
  abstraction for one caller, no configurability nobody asked for, no error
  handling for a case that cannot happen, no feature that was not requested.
- **Surgical changes.** Touch only what the task needs and match the
  surrounding style. Do not reformat or "improve while here". Dead code you
  notice is mentioned in the PR body, not deleted in passing. Remove only
  the imports and methods your own change orphaned.
- **Goal-driven.** Every step has a check: a failing test before a bug fix;
  the module's tests green, with test sources untouched, before and after a
  rename, move or split. One topic per PR: a rename PR renames, a split PR
  splits one class, a fix PR fixes one bug.

## Architecture

- **Hexagonal, per module pair.** A `-core` module holds the domain and the
  ports; the sibling module holds the adapters; Postgres adapters live under
  `adapter/postgres/mc-*-pg`. A service depends on ports, never on an
  adapter. No `adapter` package inside a `-core` module, no adapter
  importing another adapter module.
- **No framework in the core.** `-core` modules and the engines
  (`mc-workflow`, `mc-task-queue`) import neither Spring nor Jackson
  databind nor JDBC. Spring wiring (`@AutoConfiguration`, `@Component`,
  `@RestController`) lives in `springstarter/` or an `-app` module, not in a
  library.
- **Controllers are thin.** Routing, request mapping, rendering. A business
  rule lives once, in an application service, not in two controllers. A
  controller never injects another controller.
- **A file format never depends on a class name.** Persisted JSON carries a
  stable type id, not a fully qualified class name.

## Classes and methods

- **One responsibility per class.** If you need "and" to say what a class
  does, it is two classes. A class above ~300 lines and a method above ~50
  lines are a reason to look, not a hard limit.
- **Named after its pattern, in the singular.** Registry, Catalog, Resolver,
  Policy, Strategy, Factory, Builder, Repository, Adapter, Gateway, Worker,
  Renderer, Reader, Writer, Converter, Page, Component. A plural class name
  (`SessionTools`, `LlmPrices`) hides what the class is; find the role and
  name it. No `Helper`, `Util`, `Support`, `Commons`, `Holder`, `Manager`;
  `Service` only for an application service with one family of use cases.
- **Converter** means both directions in one class (object to a
  representation and back). One direction is a Serializer/Writer or a
  Parser/Reader; object to object is a Mapper; object to something a person
  looks at is a Renderer.
- **Definition : Instance** is the pair for "what is configured" and "what
  runs" (`AgentDefinition`, `WorkflowDefinition` / `WorkflowInstance`,
  `IfStepDefinition` / `IfStepInstance`). Not `Data`, not `Info`.
- **One constructor per class.** A second one needs a real second caller;
  "for tests" and "backward compatibility" are not callers. Tests pass the
  full argument list.
- **Speaking method names, short bodies.** A method does what its name says
  and nothing else. A static "utility bag" of unrelated functions is a
  smell; the functions belong to the type they operate on.
- **Imports, never inline fully qualified names** (`java.util.Map` in a
  signature).
- **No copy between siblings.** When two modules or two adapters need the
  same ten lines, extract the one class they both wanted; do not copy "so
  each module stays independent".

## Javadoc and comments

- The code speaks; the comment says **why**, never **what**, and never
  history. One paragraph per type, present tense. No "concept 16, step 5",
  no "it used to", no changelog in a class header, no essay that belongs in
  `doc/` or on the docs site.
- No javadoc that restates the signature. No javadoc block between
  `@Override` and its method, and none left orphaned above the wrong
  declaration when a method moves.
- If a class needs a long comment to be understood, the comment is a
  finding: split or rename the class instead.

## Logging, errors, style

- SLF4J everywhere; never `java.util.logging`. A library never ships a
  logging backend as a compile dependency.
- One injected `ObjectMapper` per runtime; a class does not build its own.
- An exception keeps its cause. `catch (Exception ignored)` only where the
  comment says what is ignored and why it cannot matter.
- No literal control characters in source; write the escape.
