- `common/mc-scope`: `Tenant`, `TenantScope` and `TenantMap` — which namespace,
  and for whom, the current call works in, for the areas that keep data per
  namespace without knowing the agents. The agents' `ScopeSupplier` is a
  `TenantScope`.
