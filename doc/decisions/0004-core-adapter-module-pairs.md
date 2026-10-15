# 0004: Hexagonal per module pair: -core holds domain and ports, the sibling holds adapters

Status: accepted · 2026-09 · Concept: mc-sandbox/agents/doc/concepts/06-slim-core.md, 15-module-split.md

## Decision
A capability of the agents area is two Maven modules: `mc-<x>-core` with
`domain`, `port` and the application `service`, and `mc-<x>` with the
adapters. Postgres adapters live under `adapter/postgres/mc-<x>-pg`. A
service depends on ports, never on an adapter; no adapter imports another
module's adapter; no `adapter` package inside a `-core` module. The two
engines (`mc-workflow`, `mc-task-queue`) are cores in the same sense.
Spring wiring lives in `springstarter/`, an `-app`, `-rest` or `-ui` module.

## Why
The core must be testable in milliseconds without a container, and an
embedding application must be able to take the core without Spring, JDBC or
Jackson databind. A module boundary is the one boundary the build can
enforce; a package boundary inside one module is not.

## Consequences
ArchUnit rules D1 and D2 (`common/mc-arch-rules`) check this on every build;
today's violations are frozen in the areas' `archunit_store/` and worked off
in tidy branches. A new capability starts as a pair.
