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
