- **agents:** **one repository adapter serves every namespace.** The file,
  Postgres and in-memory adapters take a `ScopeSupplier` instead of a `Namespace`
  and ask it on every call; `NamespaceRouted` and `NamespaceRouting` are gone. Code
  that builds an adapter itself passes `ScopeSupplier.fixed(namespace)` for one
  namespace.
  `FileStoreBackend.open` takes the scope as a second argument instead of a
  `namespace` config key. The workflow area's repositories take a
  `Supplier<String>` partition. `NamespaceFirstUse` and `NamespacePurge` moved to
  `mc-namespace-core` (`ai.mindconnect.namespace.service`, `…namespace.port.out`).
  The in-memory adapters take the scope too and keep one store per namespace
  (`PerNamespace`); a test that builds one passes `ScopeSupplier.local()`.
  `VectorStores` names no namespace any more: its methods work in the namespace of
  the call, and `PgEmbeddingIndex` and `PgVectorStoreRegistry` take a
  `ScopeSupplier` instead of a `Namespace`. `NamespaceFeature` and its module
  `mc-agent-runtime-feature-namespace` are gone: a host that binds the scope per
  call hands it to `AgentRuntimeBuilder.scope(…)`. `ScopeTaskAdvisor` takes that one
  scope and is always attached, so every task names its namespace; it binds the
  scope around an execution only when the scope is thread-bound.
  `ThreadBoundScope` is renamed `ThreadBoundScopeSupplier` and moves with
  `StartupScope` from `mc-agent-domain` to `mc-namespace-core`
  (`ai.mindconnect.namespace.service`); a host that binds the scope per thread
  imports them from there. The core binds through `ScopeSupplier.runIn` and
  `callIn`, which a fixed scope simply runs.
