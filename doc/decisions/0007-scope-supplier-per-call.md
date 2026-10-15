# 0007: Repository adapters ask the ScopeSupplier for the namespace on every call

Status: accepted · 2026-10-15 · Supersedes 0003

## Context
Ids, domain objects, ports and services carry no namespace (0003), so the
namespace has to reach the adapter. One adapter per namespace behind a routing
proxy means a cache of instances in every store and hides which namespace a
call writes to.

## Decision
A persistence adapter — file, Postgres, in-memory, file store, vector store, MCP
gateway — takes the `ScopeSupplier` (`ai.mindconnect.agent`) in its constructor
and asks it on every call: one instance serves every namespace. A file adapter
resolves `<dataDir>/<namespace>/…` per call (`Documents`/`RecordLog` build on a
`Supplier<FileRepo>`), a Postgres adapter matches its `namespace` column, an
in-memory adapter keeps its entries in a `PerNamespace`. The workflow area, which
knows no namespace, takes a `Supplier<String>` partition. `AgentRuntimeBuilder`
holds the scope: `ScopeSupplier.fixed` for a library, the host's `ThreadBoundScopeSupplier`
(`mc-namespace-core`, `scope(…)`) for a server, bound per request; a queued task
resumes in its scope through `ScopeSupplier.callIn` (`ScopeTaskAdvisor`).

## Consequences
A new adapter takes a `ScopeSupplier`, never a `Namespace`, and never asks it in
its constructor: a strict scope is unbound at start-up. Code that builds an
adapter for one namespace passes `ScopeSupplier.fixed(namespace)`; tests use
`ScopeSupplier.local()`. There is no routing proxy and no per-namespace instance
to evict; what a store holds for a deleted namespace goes in its `NamespacePurge`,
work a namespace needs once per process in a `NamespaceFirstUse` (both
`mc-namespace-core`).
