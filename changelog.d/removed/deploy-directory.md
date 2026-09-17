- **agents:** **the `deploy/` directory is gone from this repository.** The
  single-host Compose setup (Caddy, Postgres, Keycloak, the admin UI) was never
  part of a build or a release here — it described one installation, named its
  hosts and carried its Keycloak realm. It now lives with the rest of that
  installation's infrastructure. If you were using it as a starting point, the
  last version is in this repository's history
  (`git show <tag>:deploy/docker-compose.yml`); nothing about the application
  changed with it.
