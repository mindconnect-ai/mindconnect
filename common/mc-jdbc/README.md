# mc-jdbc

A very small JDBC helper for repositories that keep a domain object as a
JSON document next to the few columns a query needs. Plain JDBC on a
`DataSource`, Jackson for the documents, Postgres dialect (`jsonb`,
`ON CONFLICT`). No Spring, no ORM, no code generation.

```xml
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-jdbc</artifactId>
</dependency>
```

## What is in it

| Class | Role |
|-------|------|
| `Sql` | `query`, `queryOne`, `scalar`, `update`, `execute`, `executeResource`, `inTransaction` on a `DataSource`; binds `UUID`, `Instant`, enums and `Jsonb` by type; throws `JdbcException` |
| `Row` / `RowMapper` | Typed column access for the current row — `uuid`, `instant`, `enumValue`, `json(col, type)`, nullable where the column is |
| `Json` / `Jsonb` | The document codec around an `ObjectMapper` — yours, or `Json.defaults()` — and the wrapper that makes a parameter land as `jsonb` |
| `DocumentTable<T>` | One document per row: upsert, `findById`, `find(tail, params…)`, `count`, `delete`, and the idempotent DDL for the table, its columns and indexes |

## The document table

```java
Sql sql = Sql.of(dataSource, new Json(objectMapper));

DocumentTable<LlmConfig> configs = DocumentTable.of(LlmConfig.class)
        .table("mc_llm_config")
        .id("id", "UUID", LlmConfig::getId)
        .requiredColumn("name", "TEXT", LlmConfig::getName)
        .uniqueIndex("name")
        .build(sql);

configs.createSchema();                   // CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, CREATE INDEX IF NOT EXISTS
configs.save(config);                     // INSERT … ON CONFLICT (id) DO UPDATE
configs.findById(id);                     // Optional<LlmConfig>
configs.findOne("WHERE name = ?", name);
configs.find("WHERE namespace = ? ORDER BY name LIMIT ? OFFSET ?", ns, 20, 0);
configs.deleteById(id);
```

The document is the truth and the columns are the index: `save` renders both
from the object, a read deserializes `doc` alone. Declare a column only when
a query filters or sorts on it — everything else lives in the document, and
nothing has to be mapped by hand.

A query takes the SQL tail after `FROM <table>`, so the caller writes exactly
the SQL they mean. For anything the tail cannot express, `mapper()` maps the
`doc` column of a statement run through `Sql` directly. Note that pgjdbc
treats every `?` as a placeholder — write the jsonb `?` operator as `??`.

## Hand-written statements

```java
List<Message> page = sql.query(
        "SELECT doc FROM mc_message WHERE conversation_id = ? ORDER BY seq LIMIT ? OFFSET ?",
        messages.mapper(), conversationId, page.size(), page.offset());

sql.inTransaction(tx -> {
    tx.update("DELETE FROM mc_message WHERE conversation_id = ? AND seq BETWEEN ? AND ?", id, from, to);
    tx.update("UPDATE mc_conversation SET message_count = message_count - ? WHERE id = ?", n, id);
});
```

## Tests

The Postgres tests skip themselves unless a database answers on
`jdbc:postgresql://localhost:5433/postgres` (override with `MC_JDBC_TEST_URL`,
`MC_JDBC_TEST_USER`, `MC_JDBC_TEST_PASSWORD`):

```bash
podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie
mvn -f common/mc-jdbc/pom.xml test
```
