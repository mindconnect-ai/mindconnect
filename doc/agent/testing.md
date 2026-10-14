# Testing, as done here

What the test reviewer (I8) and `/refine` assume. The repository has about
500 test classes; this is the style they share.

## Style

- **JUnit 5 and AssertJ.** Assertions read as sentences: `assertThat(record.status()).isEqualTo(RUNNING)`.
  JUnit's own assertions are not used.
- **Names are sentences** in snake_case stating the behaviour (see [naming.md](naming.md)).
  One behaviour per test; a test with "and" in its name is two tests.
- **Fakes over mocks.** Mockito appears in three files; the rest use small hand-written
  fakes and in-memory adapters (`InMemoryTaskStore`, `InMemoryAgentDefinitionRepository`,
  `FakeDav`, `FakeTokenEndpoint`, a fake clock). A fake keeps the test readable and
  checks the result, not the call. A test that asserts `verify(mock).refresh()` has
  checked nothing (I8).
- **The core is tested without infrastructure.** Domain and service tests in a
  `-core` module run in milliseconds with fakes behind the ports; adapters get their
  own tests against the real thing.
- **Tests that need a service skip themselves** with `assumeTrue(reachable(), "…skipping")`:
  Postgres on 5433, an LM Studio model, a mailbox. A green run may mean "skipped";
  read the `Skipped:` count. CI provides Postgres, not a model.
- **Full constructor arguments.** Tests call the one constructor with every argument;
  there is no "test constructor" (conventions: one constructor per class).

## Rules the build keeps

- D6: the number of `@Test` methods in a module does not fall against the base
  branch; `@Disabled` carries a reason. A failing test is fixed in the code.
- Test sources stay untouched by a rename, move or split: the tests prove the
  behaviour survived.

## Characterization tests before a refactoring

Before a class is split or moved, write tests that pin its current behaviour,
one per public entry point, named after what happens today, even where today
is wrong (record the wrong in `doc/known-gaps.md`, do not fix it in the same
branch). Run them green, then restructure, then run them again: same count,
same result. The reviewer treats a changed characterization test as a
behaviour change.

## What a spec's acceptance criterion names

Each acceptance criterion in `doc/specs/<branch>.md` says how it is checked:
`unit` (a `-core` test with fakes), `integration` (an adapter against its
service, may skip), or `manual` (a case in the maintainer's manual suite).
The implementing session writes the unit and integration tests first.
