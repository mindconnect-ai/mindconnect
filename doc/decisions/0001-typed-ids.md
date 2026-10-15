# 0001: Every entity is addressed by a typed id record

Status: accepted · 2026-09-11 · Concept: mc-sandbox/agents/doc/concepts/23-stabile-werkzeug-identitaet.md, commit ad9106a8

## Decision
Every entity has `record XId(String value) implements EntityId`, living in the
module that owns the entity. No port or service takes a bare `UUID` or
`String` for an id. In JSON an id is its plain value.

## Why
`findById(AgentId)` refuses a `SessionId` at compile time. A string id lets
any id reach any repository, and a bug of that kind shows up as a missing
record, not as a compile error.

## Consequences
A new entity starts with its id record. `mc-agent-domain` admits an id record
only under 0002; otherwise it stays with the entity.
