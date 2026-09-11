---
title: Authentication
sidebar_position: 9
---

# Authentication

Who may call an installation, and as whom. Both apps — the Admin UI
(`mc-agent-admin-ui-app`, which also serves the REST API and the OpenAI
Responses API) and the agent server (`mc-agent-api-app`) — know the same two
modes.

| Mode | Switched on by | Every request runs as |
|---|---|---|
| **Off** (default) | — | the dev user, `MC_DEV_USER` (default `mc_user`) |
| **On** | Admin UI: the `keycloak` Spring profile · agent server: `MC_AUTH_ENABLED=true` | the authenticated user; without authentication, nothing is answered |

The user id is the identity provider's `preferred_username` — for a browser
login, a JWT and an API token alike, so a user's sessions are the same whichever
way they come in.

## Browser login

The Admin UI signs users in with Keycloak (OIDC, authorization code). The
tokens stay on the server; the browser holds an `HttpOnly` session cookie, and
writing requests carry the `X-XSRF-TOKEN` header the UI sends on its own. See
[Deploying](https://github.com/mindconnect-ai/mindconnect/tree/main/deploy) for
setting up the realm.

## Programs: a bearer token

`/api/**` and `/v1/**` take a bearer token and nothing else. The Admin UI's
browser session does not count there, so they ask for no CSRF token either:

```bash
curl -H "Authorization: Bearer $TOKEN" https://app.example.com/api/agents
```

Without a token, or with one that is not accepted, the answer is `401` with
`WWW-Authenticate: Bearer` and a JSON body saying why:

```json
{"status":401,"error":"Unauthorized","message":"Invalid, expired or revoked API token"}
```

Swagger UI in the Admin UI's API section calls the API the same way: enter a
token under **Authorize**, and *Try it out* sends it.

Two kinds of token are accepted.

### Personal API tokens

Created by the user themself: click the avatar in the header → **API tokens** →
**New token**, give it a name and a lifetime (30 days, 90 days, a year, or no
expiry). The secret (`mct_…`) is shown **once**, in the dialog that follows;
the installation keeps only its SHA-256 hash and the first characters, so a
lost token cannot be displayed again — create a new one. A token acts with its
owner's rights and nobody else's; **Revoke** ends it at once.

Tokens are stored with the rest of the installation's data
(`<data>/<namespace>/system/api-tokens/` or the `mc_api_token` table), so an
agent server sharing the Admin UI's data directory or database — and namespace —
accepts the tokens created there.

### JWTs of the identity provider

An access token of the configured realm works too, as long as it carries a
`preferred_username` claim. The signature is checked against the realm's
published keys, as are the issuer and the lifetime; the realm's metadata is
fetched with the first token, so the app starts while Keycloak is still coming
up.

| Setting | Variable | Default |
|---|---|---|
| `mindconnect.auth.jwt.issuer-uri` | `KC_ISSUER_URI` | Admin UI: the login realm · agent server: _(none — JWTs rejected, API tokens still work)_ |
| `mindconnect.auth.jwt.audiences` | `MC_JWT_AUDIENCES` | _(empty — any audience)_ |

Keycloak puts `account` into `aud` by default. To accept only tokens meant for
this installation, add an audience mapper to the client that issues them and
list that audience in `MC_JWT_AUDIENCES`.

## What a caller can reach

Everything that belongs to a user answers only to that user: sessions and all
that hangs off them (chat, stream, history, memory, approvals, attached files),
the user's workspace files, uploaded files, transcriptions, and the responses
of the Responses API. Somebody else's id is answered exactly like an id that
does not exist — `404` — so an id seen in a URL or a log opens nothing.

The configuration of the installation — agents, LLM configs, vector stores,
workflows, MCP servers — is open to every authenticated user. Roles are not
part of this yet.

## The CLI

The CLI's remote mode authenticates with an API token:

```bash
MC_REMOTE_TOKEN=mct_… mvn -f agents/client/mc-agent-cli/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--mindconnect.remote.url=https://app.example.com
```
