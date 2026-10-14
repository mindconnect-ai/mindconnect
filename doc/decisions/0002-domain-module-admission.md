# 0002: mc-agent-domain holds only what two independent modules share

Status: accepted · 2026-09-11 · Concept: mc-sandbox/agents/doc/concepts/15-module-split.md

## Decision
`agents/core/mc-agent-domain` (package `ai.mindconnect.agent`) is the area's
shared vocabulary: `Namespace`, `EntityId`, `UserId`, `AuthenticationInfo`,
`AgentId`, `SessionId`. A type is admitted only when two modules that do not
depend on each other need it. It depends on `jackson-annotations` only.

## Why
A shared domain module attracts everything; after a year it is the module
every other module depends on and nothing can move out. The admission rule
keeps it a vocabulary, not a dumping ground.

## Consequences
An id or value type that one module owns lives in that module
(`ConversationId` in the message repository, `LlmConfigId` in the gateway).
Adding a type to `mc-agent-domain` names the two modules in the PR.
