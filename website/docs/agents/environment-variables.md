---
title: Environment variables
sidebar_position: 10
---

# Environment variables

This page lists the environment variables the agents area reads. Most are
referenced from [LLM configs](./llm-configs-reference.md) as `${VAR}` /
`${VAR:default}` placeholders, resolved at use time — see
[how API keys resolve](./admin-ui/llm-configs.md#api-keys).

Set them in your shell before starting the app, e.g.:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export TAVILY_API_KEY=tvly-...
```

## Core

| Variable | Default | Notes |
|----------|---------|-------|
| `MINDCONNECT_ENCRYPTION_SECRET_KEY` | _(none)_ | **Required.** Encrypts stored LLM credentials. No default on purpose — the app **fails to start** without it. Must be **16, 24 or 32 characters** (used directly as an AES key). |
| `MC_PERSISTENCE` | `file` | `file` keeps everything under `mindconnect.data.base-dir`; `postgres` keeps it in the database — see [Persistence](./persistence.md#postgres). Since 0.3.0. |
| `MC_POSTGRES_URL` | `jdbc:postgresql://localhost:5432/mindconnect` | JDBC URL, used with `MC_PERSISTENCE=postgres`. Tables are created on start. |
| `MC_POSTGRES_USER` / `MC_POSTGRES_PASSWORD` | _(empty)_ | Database credentials. |

## Runtime configuration (Spring properties)

These are Spring properties; thanks to relaxed binding each one can also be set
as an env var in `SCREAMING_SNAKE` form (e.g. `MINDCONNECT_DATA_BASE_DIR`).

| Property | Default | Notes |
|----------|---------|-------|
| `mindconnect.persistence` | `file` | `file` or `postgres` — bound to `MC_PERSISTENCE` in the apps' yaml. |
| `mindconnect.postgres.*` | — | `url`, `username`, `password`, `pool-size` (default 10) for `postgres` mode; bound to `MC_POSTGRES_*`. |
| `mindconnect.data.base-dir` | `data` | Root for **all** file persistence (definitions, configs, conversations, workspaces); in `postgres` mode only the file-based side channels. |
| `mindconnect.tools.base-dir` | user home | Working/base directory for `bash` and the file tools — security-relevant. |
| `mindconnect.user.id` | app-specific | The user id that owns sessions and data (the CLI ships a hard-coded default). |
| `mindconnect.remote.url` | _(unset)_ | Points the CLI at a remote agent server instead of local mode. |
| `mindconnect.code-exec.*` | — | Sandbox limits for `code_execute`: `runtime`, `network`, `languages`, `memory`, `cpus`, `timeout-seconds`, `idle-seconds`. |
| `mindconnect.vector-store.*` | — | Vector-store backend: `backend`, `dir`, `url`, `user`, `password`, `embedding-config` (default `embeddings`). |
| `mindconnect.file-store.*` | — | File-store backend: `backend`, `dir`. |
| `mindconnect.workflow-admin.dir` | `data/workflows` | Where the workflow admin stores workflows. |
| `mindconnect.agent.trace.max-per-session` | `50` | LLM call-trace retention per session. |

## LLM providers

| Variable | Used by | Notes |
|----------|---------|-------|
| `ANTHROPIC_API_KEY` | `claude-default`, `claude-haiku-default` | Anthropic API key |
| `CLAUDE_MODEL` | `claude-default` | Override model id (default `claude-sonnet-4-6`) |
| `CLAUDE_HAIKU_MODEL` | `claude-haiku-default` | Override model id (default `claude-haiku-4-5`) |
| `OPENAI_API_KEY` | `openai-default` | OpenAI API key |
| `OPENAI_MODEL` | `openai-default` | Override model id (default `gpt-5.4-mini`) |
| `AZURE_OPENAI_API_KEY` | `azure-openai-default` | Azure OpenAI key |
| `AZURE_OPENAI_ENDPOINT` | `azure-openai-default` | Azure resource endpoint URL |
| `AZURE_OPENAI_DEPLOYMENT` | `azure-openai-default` | Deployment name (default `gpt-4o`) |
| `GEMINI_API_KEY` | `gemini-default` | Google Gemini key |
| `GEMINI_MODEL` | `gemini-default` | Override model id (default `gemini-2.0-flash`) |

Local providers (`lm-studio-default`, `agent-default`) need **no** key — they
talk to LM Studio at `http://localhost:1234`.

## Tools

| Variable | Used by | Notes |
|----------|---------|-------|
| `TAVILY_API_KEY` | `web_search` | Required for web search tools |

## Authentication (Admin UI / Keycloak)

| Variable | Default | Notes |
|----------|---------|-------|
| `MC_AUTH_ENABLED` | `false` | `false` (default) runs the Admin UI without Keycloak. Keycloak login is enabled via the **`keycloak` Spring profile** (which sets this itself) — setting the variable alone is not enough. |
| `MC_DEV_USER` | `mc_user` | Auto-login username used when auth is disabled |
| `KC_ISSUER_URI` | `http://localhost:8180/realms/mindconnect` | Keycloak realm issuer (only with the `keycloak` profile) |
| `KC_CLIENT_ID` | `mc-admin-ui` | OIDC client id |
| `KC_CLIENT_SECRET` | _(empty)_ | OIDC client secret, if your client is confidential |

## Keycloak container (`.env.docker`)

These are read by `docker-compose.yml` / `start-keycloak.sh` when bringing
Keycloak up — copy `.env.docker.example` to `.env.docker` and fill them in:

| Variable | Notes |
|----------|-------|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Keycloak's database |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin console login |
| `KC_PASSWORD_MC_USER` | Password seeded for the `mc_user` user |
| `KC_PASSWORD_MC_ADMIN` | Password seeded for the `mc_admin` user |
| `KC_PASSWORD_MC_HR` | Password seeded for the `mc_hr` user |
| `KC_PASSWORD_MC_DEV` | Password seeded for the `mc_dev` user |

See [Admin UI → Keycloak users](./admin-ui/index.md#keycloak-users) for the seed
users.

:::tip Keep secrets out of git
Put these in a local, git-ignored file (the repo uses `mc.env` / `.env.docker`,
both ignored) and source it — never commit real keys.
:::
