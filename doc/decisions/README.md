# Decisions

One file per decision. A decision is something an agent or a contributor
would otherwise decide again, differently.

File name: `NNNN-short-slug.md`, numbered in order of creation, never renumbered.

```markdown
# 0007: Repository adapters ask the ScopeSupplier for the namespace on every call

Status: accepted · 2026-10-15 · Supersedes 0003

## Context
Ids, domain objects, ports and services carry no namespace, so the namespace
has to reach the adapter.

## Decision
A persistence adapter takes the `ScopeSupplier` in its constructor and asks it
on every call: one instance serves every namespace.

## Consequences
A new adapter takes a `ScopeSupplier`, never a `Namespace`; code that needs one
namespace passes `ScopeSupplier.fixed(namespace)`.
```

Rules:

- 10 to 30 lines. The reasoning in full stays in the concept it links to.
- Status is `accepted`, `superseded by NNNN` or `retired`. A decision is
  never deleted.
- English, present tense, no history ("it used to").
- An agent reads every decision of the area it works in before changing
  that area. `/adr` writes a new one in this format. Keep the title precise enough to be found by grep.
