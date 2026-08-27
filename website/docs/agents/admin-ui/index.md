---
title: Admin UI
sidebar_position: 3
---

# Admin UI

The **Agent Admin UI** (`mc-agent-admin-ui-app`) is the visual console for the
runtime. It runs on **http://localhost:9090**.

## Running it

By default the Admin UI runs **without authentication** — no Keycloak, no
Docker, no database. It starts immediately and logs you in as a fixed dev user
(`mc_user`). The only required setting is the encryption key for stored LLM
credentials:

```bash
export MINDCONNECT_ENCRYPTION_SECRET_KEY="change-me-to-a-32-char-secret!!!"
mvn -f agents/server/mc-agent-admin-ui-app/pom.xml spring-boot:run
```

The key must be **16, 24 or 32 characters** long (it is used directly as an AES
key) — any other length fails at the first encrypt/decrypt.

Open **http://localhost:9090** — you're in.

Under the hood this is the `mindconnect.auth.enabled=false` mode (the default):
all routes are permitted, CSRF is off, and no OIDC is wired. You can change the
auto-login username with `MC_DEV_USER`.

## Optional: Keycloak login (for deployments)

For a deployed instance you'll want real authentication. Activate the
**`keycloak` Spring profile** and the app logs users in through **Keycloak** —
the profile provides the OAuth2 client registration and flips `auth.enabled` on
together. (Setting `MC_AUTH_ENABLED=true` alone does **not** work: without the
profile there is no client registration and the app fails to start.) Keycloak
must be running **before** you start the app, or login will fail.

### 1. Start Keycloak first

```bash
cd agents/server/mc-agent-admin-ui-app
cp .env.docker.example .env.docker   # first time: fill in the passwords
./start-keycloak.sh
```

This brings up Keycloak (+ its Postgres) from `docker-compose.yml` via
**`podman compose`** (install podman first, or run the compose file with docker
yourself) and imports the `mindconnect` realm. Keycloak is then at
**http://localhost:8180** (realm: `mindconnect`, client: `mc-admin-ui`).

### 2. Start the Admin UI with the `keycloak` profile

```bash
MINDCONNECT_ENCRYPTION_SECRET_KEY="change-me-to-a-32-char-secret!!!" \
mvn -f agents/server/mc-agent-admin-ui-app/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--spring.profiles.active=keycloak
```

Open **http://localhost:9090** and log in via Keycloak.

### Keycloak users

The imported `mindconnect` realm contains these seed users (passwords are set
from the `KC_PASSWORD_MC_*` values in `.env.docker`):

| Username | Realm role |
|----------|------------|
| `mc_user` | `user` |
| `mc_admin` | `admin` |
| `mc_hr` | `hr` |
| `mc_dev` | `dev` |

### Fixing podman clock drift (`fix-podman-clock.sh`)

If you run Keycloak in a **podman** machine on macOS, the VM clock falls behind
whenever the Mac sleeps. Keycloak then signs JWTs with an expiry in the past and
**OIDC login fails with `Jwt expired at ...`**.

The cause: podman's default chrony config (`makestep 1.0 3`) only steps the
clock during the first few NTP updates after boot, then refuses large
corrections — so a long suspend leaves the VM permanently behind.

The fix is a one-time script:

```bash
cd agents/server/mc-agent-admin-ui-app/keycloak
./fix-podman-clock.sh
```

It patches the VM's chrony to `makestep 1.0 -1` (step at any time), restarts
`chronyd`, and forces an immediate resync. The change persists until
`podman machine reset`, after which clock drift self-heals within ~64s of each
future suspend.

:::info When to run it
Run it once per podman machine — or any time login starts failing with a
"Jwt expired" error after your Mac has been asleep.
:::

## The main sections

The navigation has seven top-level entries:

| Section | What you do there |
|---------|-------------------|
| **[Agents](./agents.md)** | Create, edit, delete and copy agents. In the detail view: configure tools and start or continue sessions. |
| **[Tools](./tools.md)** | Browse the available tools, inspect their schemas, and test a tool. |
| **[LLM Configs](./llm-configs.md)** | Create, edit, delete and **test** LLM configs. API keys come from environment variables. |
| **Workflows** | The embedded workflow admin UI (`mc-workflow-admin-rest`): edit, save and run workflows. |
| **[Vector Stores](./vector-stores.md)** | Manage vector-store templates and stores, upload files, run semantic searches. |
| **[Migrations](./migrations.md)** | Review and apply changes to the bundled seed data (agents, LLM configs, workflows). |
| **API** | Embedded Swagger UI for the REST API under `/api/**`. |

## Related

- [Environment variables](../environment-variables.md) — every variable you can
  set (API keys, models, Keycloak, …).
- [LLM configuration reference](../llm-configs-reference.md) — the bundled
  configs and their fields.
- [Bundled agents](../bundled-agents.md) — the agents that ship out of the box.
