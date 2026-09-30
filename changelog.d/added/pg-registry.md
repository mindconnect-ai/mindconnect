- **agents:** **on `mindconnect.persistence=postgres` the configured registries
  live in Postgres.** `PgRegistrySourceRepository` (new module
  `mc-agent-registry-pg`, pulled in by `mc-agent-starter-postgres`) keeps one
  row per registry in `mc_registry_source`, keyed by `(namespace, id)` and
  routed per namespace like the other stores, so the registry no longer opens
  the file store and no longer takes its partition lock. A namespace that has
  no row yet imports its `system/registries/*.json` once on first use; the
  files are left where they are. The shipped registries and
  `mindconnect.registry.default-source` are seeded into each namespace on its
  first use. File persistence is unchanged.
