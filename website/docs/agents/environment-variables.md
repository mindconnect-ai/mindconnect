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
| `MC_NAMESPACE` | `local` | The namespace this process runs in: file data lives under `<base-dir>/<namespace>/`, Postgres rows carry it in their key. One process serves one namespace. |
| `MC_POSTGRES_URL` | `jdbc:postgresql://localhost:5432/mindconnect` | JDBC URL, used with `MC_PERSISTENCE=postgres`. Tables are created on start. |
| `MC_POSTGRES_USER` / `MC_POSTGRES_PASSWORD` | _(empty)_ | Database credentials. |
| `MC_UPLOAD_MAX_FILE_SIZE` / `MC_UPLOAD_MAX_REQUEST_SIZE` | `25MB` / `100MB` | Upload limits — one file, and all files of one upload request (the chat's attach dialog sends every selected file in one request). Bound to `spring.servlet.multipart.max-file-size` / `max-request-size`; an upload above either is answered with HTTP 413. The runtime sends a file inline to the model up to 20MB. |

## Runtime configuration (Spring properties)

These are Spring properties; thanks to relaxed binding each one can also be set
as an env var in `SCREAMING_SNAKE` form (e.g. `MINDCONNECT_DATA_BASE_DIR`).

| Property | Default | Notes |
|----------|---------|-------|
| `mindconnect.persistence` | `file` | `file` or `postgres` — bound to `MC_PERSISTENCE` in the apps' yaml. |
| `mindconnect.namespace` | `local` | The one namespace every repository is bound to — bound to `MC_NAMESPACE` in the apps' yaml. |
| `mindconnect.postgres.*` | — | `url`, `username`, `password`, `pool-size` (default 10) for `postgres` mode; bound to `MC_POSTGRES_*`. |
| `mindconnect.data.base-dir` | `data` | Root for **all** file persistence (definitions, configs, conversations, uploads, the users' homes); in `postgres` mode only the file-based side channels. |
| `mindconnect.users.home` | `<data.base-dir>/<namespace>/home/{user}` | Each user's directory on the server — a path with `{user}` in it. `{user}` is the user id when it consists of letters, digits, `.`, `-` and `_` and does not start with a dot; any other id is reduced to those characters and gets `+` and a short hash of the id appended (`alice@example.com` → `alice_example.com+…`), so no two users share a directory. A session opened without a working directory works in its own directory under there (`sessions/<id>`), its uploads are put in `sessions/<id>/uploads` for the file tools, and it is the default root a working directory must lie under. Blank turns it off. |
| `mindconnect.tools.base-dir` | user home | Base directory for `bash` and the file tools when a session has no working directory at all — sessions written before 0.5.2, a runtime without a users' home, tools run outside a session — security-relevant. |
| `mindconnect.tools.working-dir-root` | `users.home` | The root a session's working directory (`workingDir` on `POST /api/sessions`, the chat's directory chooser, `/cd` in the CLI) must lie under, and the tree the chat's directory picker shows. With `{user}` in it — `/srv/mindconnect/users/{user}` — every user gets a root of their own, created on first use, and sees nobody else's. The CLI sets `/`; the Admin UI app sets the user's home, or `MC_WORKING_DIR_ROOT` when given. |
| `mindconnect.tools.disabled` | — | Tools this installation does not offer at all, comma-separated (`bash,process_kill`). They leave every catalog and never resolve — an agent definition that names one goes without it, and the Admin UI's tool settings cannot switch it back on. `MC_TOOLS_DISABLED` in the `server` profile. |
| `mindconnect.working-dirs.choice` | `true` | Whether a user may choose a chat's directories. `false`: no folder button in the chat, `workingDir`/`additionalDirs` on `POST /api/sessions`, `PUT /api/sessions/{id}/working-dir` and `GET /api/directories` are refused (`/cd` and `/add-dir` in the CLI too), and every chat works in its own directory under `users.home`. |
| `mindconnect.agent.instructions.user-dir` | `~/.mindconnect` | Where a user's standing instructions live (`AGENTS.md`, `PROMPT.md` or `CLAUDE.md`), read into every session's system prompt beside the project's own file. The default suits a desktop: one person, one home. A server runs as one service account, so put `{user}` in the value and each user gets a directory of their own. `off` drops the user scope. |
| `mindconnect.agent.skills.user-dir` | `~/.mindconnect/skills` | Where a user's own [skills](./skills.md) live — `SKILL.md` files they can load in every project, beside the ones this installation stores and the ones a project keeps in `.mindconnect/skills/`. Same shape as the line above: the default suits a desktop, a server puts `{user}` in the value, `off` drops the user scope. `MC_SKILLS_USER_DIR` in the `server` profile. |
| `mindconnect.user.id` | app-specific | The user id that owns sessions and data (the CLI ships a hard-coded default). |
| `mindconnect.remote.url` | _(unset)_ | Points the CLI at a remote agent server instead of local mode. |
| `mindconnect.remote.token` | _(unset)_ | Bearer token the CLI sends to a remote agent server that requires authentication; also read from `MC_REMOTE_TOKEN`. |
| `mindconnect.code-exec.*` | — | Sandbox limits for `code_execute`: `runtime`, `network`, `languages`, `memory`, `cpus`, `timeout-seconds`, `idle-seconds`. |
| `mindconnect.vector-store.*` | — | Vector-store backend: `backend`, `url`, `user`, `password`, `embedding-config` (default `embeddings`). The `memory` backend keeps its files in `<data.base-dir>/<namespace>/vector-stores`. |
| `mindconnect.file-store.*` | — | File-store backend: `backend`. The `filesystem` backend keeps uploads in `<data.base-dir>/<namespace>/files`. |
| `mindconnect.cors.allowed-origins` | `*` | Origins that may call the REST endpoints from a browser (`/api`, `/chat/api`, `/v1`), comma-separated. `*` lets every origin call without credentials; a list of origins may also send the session cookie. Both server apps. |
| `mindconnect.agent.trace.max-per-session` | `50` | LLM call-trace retention per session. |

## Running on a server

The apps' defaults are for one person on their own machine. On a server several
users share, start them with the `server` profile (`--spring.profiles.active=server`,
plus `keycloak` for login). It sets:

- `mindconnect.tools.disabled: bash,process_kill` — `bash` runs as the server's
  account, with its environment and the whole file system, and nothing confines it to
  a chat's directory. The file and document tools stay: they are confined to the
  chat's directories.
- `mindconnect.working-dirs.choice: false` — nobody points a chat at a directory or
  browses the server; each chat works in its own directory under `users.home`, where
  its uploads are copied for the file and document tools. Deleting the chat removes it.
- `mindconnect.tools.working-dir-root` empty, so a user's root is their own home.
- `mindconnect.tools.base-dir: <data.base-dir>/tools` instead of the account's home.
- `mindconnect.agent.instructions.user-dir: off` — set a path with `{user}` for one
  `AGENTS.md` per user.
- `mindconnect.agent.skills.user-dir: off` — likewise, a path with `{user}` for one
  skills directory per user. The installation's own skills are unaffected.

Each value can be overridden (`MC_TOOLS_DISABLED`, `MC_WORKING_DIR_ROOT`,
`MC_TOOLS_BASE_DIR`, `MC_INSTRUCTIONS_USER_DIR`, `MC_SKILLS_USER_DIR`). Uploads need local disk either way:
on several nodes, `users.home` has to be shared storage for the file tools to find
a chat's copy.

## LLM providers

| Variable | Used by | Notes |
|----------|---------|-------|
| `ANTHROPIC_API_KEY` | `claude-default`, `claude-haiku-default` | Anthropic API key |
| `CLAUDE_MODEL` | `claude-default` | Override model id (default `claude-sonnet-4-6`) |
| `CLAUDE_HAIKU_MODEL` | `claude-haiku-default` | Override model id (default `claude-haiku-4-5`) |
| `OPENAI_API_KEY` | `openai-default`, and through the `agent-default` alias every bundled agent | OpenAI API key |
| `OPENAI_MODEL` | `openai-default` | Override model id (default `gpt-5.4-mini`) |
| `OPENAI_EMBEDDING_MODEL` | `openai-embeddings` | Override the embedding model (default `text-embedding-3-small`; `OPENAI_API_KEY` is the key) |
| `EMBEDDING_BASE_URL` | `embeddings` | Local embedding server (default `http://localhost:1234`, LM Studio) |
| `EMBEDDING_MODEL` | `embeddings` | Override the local embedding model (default `text-embedding-nomic-embed-text-v1.5`) |
| `AZURE_OPENAI_API_KEY` | `azure-openai-default` | Azure OpenAI key |
| `AZURE_OPENAI_ENDPOINT` | `azure-openai-default` | Azure resource endpoint URL |
| `AZURE_OPENAI_DEPLOYMENT` | `azure-openai-default` | Deployment name (default `gpt-4o`) |
| `GEMINI_API_KEY` | `gemini-default` | Google Gemini key |
| `GEMINI_MODEL` | `gemini-default` | Override model id (default `gemini-2.0-flash`) |
| `SPEECH_TO_TEXT_MODEL` | `speech-to-text` | Override model id (default `whisper-1`) |
| `SPEECH_TO_TEXT_BASE_URL` | `speech-to-text` | Where the transcription endpoint lives (default `https://api.openai.com`) — point it at Groq or a local Whisper server |

Local providers (`lm-studio-default`, `gemma-reader`, `embeddings`) need **no**
key — they talk to LM Studio at `http://localhost:1234`.

## Tools

| Variable | Used by | Notes |
|----------|---------|-------|
| `TAVILY_API_KEY` | `web_search` | Required for web search tools |

## Authentication (Admin UI / Keycloak)

See [Authentication](./authentication.md) for what the modes mean.

| Variable | Default | Notes |
|----------|---------|-------|
| `MC_AUTH_ENABLED` | `false` | `false` (default) runs the Admin UI without Keycloak. Keycloak login is enabled via the **`keycloak` Spring profile** (which sets this itself) — setting the variable alone is not enough. The agent server (`mc-agent-api-app`) has no login and is switched on by this variable alone: every request then needs a bearer token. |
| `MC_DEV_USER` | `mc_user` | Auto-login username used when auth is disabled |
| `KC_ISSUER_URI` | `http://localhost:8180/realms/mindconnect` | Keycloak realm issuer — the browser login (only with the `keycloak` profile) and the issuer bearer JWTs on `/api/**` and `/v1/**` are checked against. Agent server: no default; without it only API tokens are accepted. |
| `MC_JWT_AUDIENCES` | _(empty)_ | Comma-separated; when set, a bearer JWT must carry one of these audiences |
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
