- The conversation area no longer depends on `agents`: its stores take a
  `TenantScope` instead of a `ScopeSupplier`, and `conversation/pom.xml` builds
  the area on its own.
