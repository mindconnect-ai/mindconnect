# mc-file-repo

The file counterpart of [`mc-jdbc`](../mc-jdbc/README.md): domain objects
stored as JSON documents in a directory tree, safe to read and write from
many threads at once. Plain JDK and Jackson — no Spring, no database.

```xml
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-file-repo</artifactId>
</dependency>
```

## What is in it

| Class | Role |
|-------|------|
| `FileRepo` | One partition (a namespace) of a data directory — one instance per real path in a JVM; refuses a second process on the same partition and cleans up after interrupted writes |
| `Documents<K, V>` | One JSON document per key: `find`, `findAll`, `create`, `createIfAbsent`, `update`, `put`, `delete` |
| `PathLocks` | JVM-wide write locks by file path, one per thread, with a timeout |
| `FileWrites` | Replace a file in one step: temporary file, then atomic move |

## Documents

```java
FileRepo repo = FileRepo.open(Path.of("data"), namespace);    // data/<namespace>/

Documents<SessionId, AgentSession> sessions = Documents.of(AgentSession.class)
        .path((SessionId id) -> "sessions/" + id.value() + "/session.json")
        .build(repo, objectMapper);

sessions.create(id, session);                              // DocumentExistsException if there is one
sessions.find(id);                                         // Optional<AgentSession>
sessions.update(id, s -> s.withTitle("Weekly report"));    // read, change, write — one writer at a time
sessions.findAll("sessions", "session.json");              // one document per subdirectory
sessions.delete(id);
```

The path of a document follows from its key alone; a key that would lead out
of the partition — into another namespace, say — is refused.

## What it guarantees

- **No half-written files.** Every write goes to a temporary file that is
  moved over the target in one step. A reader holds the old version or the
  new one, never a truncated file — so reading takes no lock.
- **No lost updates.** `update` reads, changes and writes under the
  document's write lock. Writers of one document take turns; writers of
  different documents do not wait for each other.
- **The lock is the path's, not the instance's.** Two `Documents` — or two
  whole stores — on the same directory serialize against each other.
- **An unreadable document throws.** It never passes for a missing one.

## Rules for callers

The functions given to `update` and `createIfAbsent` run under the lock. Build
the new value and nothing else: no model call, no tool, no network, no event.

A write started while the thread already holds a write lock throws
`NestedWriteException` — that is how deadlocks are ruled out, so it signals a
bug to fix, not a condition to retry. A lock that stays taken past the
timeout (10 s by default, `lockTimeout(…)` per `Documents`) throws
`LockTimeoutException` naming the thread that holds it.

A partition is served by one process. A second process opening the same
partition gets a `FileRepoException` — the operating-system lock on
`<partition>/.mc-partition.lock` says so, and is released when the holder ends,
crashes included. Other partitions of the same data directory stay free, so
one process per namespace can share a data directory. Several nodes serving
one namespace share a database instead.

## Tests

```bash
mvn -f common/mc-file-repo/pom.xml test
```
