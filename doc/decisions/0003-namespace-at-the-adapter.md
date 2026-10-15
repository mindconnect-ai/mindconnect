# 0003: The namespace is bound at the adapter, not carried in the id

Status: superseded by 0007 · 2026-09-11 · Concept: mc-sandbox/agents/doc/concepts/26-runtime-pro-namespace.md (supersedes the id-carries-namespace idea in 24)

## Decision
Ids, domain objects, ports and services carry no namespace. A persistence
adapter (file, Postgres, file store, vector store) is bound to one namespace
in its constructor or configuration. Above the adapters, `NamespaceRouted`
picks the adapter for the namespace the `ScopeSupplier` names right now.
A library embeds with `ScopeSupplier.fixed`; a server binds a
`ThreadBoundScope` per request and per queued task (`ScopeTaskAdvisor`).
Work a namespace needs once per process is a `NamespaceFirstUse` registered
with `ThreadBoundScope.onBind`.

## Why
A namespace in every id leaks tenancy into every signature and every test.
Binding it at the adapter keeps the core unaware of tenants and lets the same
service code run embedded (one namespace) and served (many).

## Consequences
A new adapter takes the namespace as a constructor argument and is wrapped in
`NamespaceRouted` by the starter. Code that needs the namespace at runtime
asks the `ScopeSupplier`; it never reads it off an entity.
`mindconnect.namespace` is only the fallback while not every entry point binds.
