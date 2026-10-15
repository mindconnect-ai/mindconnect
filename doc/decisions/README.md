# Decisions

One file per decision. A decision is something an agent or a contributor
would otherwise decide again, differently.

File name: `NNNN-short-slug.md`, numbered in order of creation, never renumbered.

```markdown
# 0003: Namespace lives at the adapter, not in the id

Status: accepted · 2026-10-02 · Concept: mc-sandbox/concepts/26-runtime-pro-namespace.md

## Decision
Ids, domain objects, ports and services carry no namespace. A persistence
adapter is bound to one namespace in its constructor; `NamespaceRouted`
picks the adapter for the namespace the `ScopeSupplier` names.

## Why
A namespace in every id leaks tenancy into every signature and every test.
Binding it at the adapter keeps the core unaware and lets a library embed
with `ScopeSupplier.fixed`.

## Consequences
New persistence adapters take the namespace as a constructor argument.
A type that needs the namespace at runtime asks the `ScopeSupplier`.
```

Rules:

- 10 to 30 lines. The reasoning in full stays in the concept it links to.
- Status is `accepted`, `superseded by NNNN` or `retired`. A decision is
  never deleted.
- English, present tense, no history ("it used to").
- An agent reads every decision of the area it works in before changing
  that area. `/adr` writes a new one in this format. Keep the title precise enough to be found by grep.
