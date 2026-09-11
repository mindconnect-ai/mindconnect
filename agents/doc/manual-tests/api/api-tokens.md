---
id: api-api-tokens
area: api
requires: [server-9099-keycloak]
duration: ~5 min
last-verified: 2026-09-11 (commit d85ccc8, branch feature/api-auth, runs/2026-09-11-api-tokens)
---

# API tokens: create, use, revoke — and only for their owner

**Goal:** A user creates a personal API token on the profile page, a program
calls `/api/**` and `/v1/**` with it as that user, the token never shows again,
another user's resources stay out of reach, and revoking ends it at once.

## Preconditions

- Admin UI of this branch with the `keycloak` profile at http://localhost:9099
  and two Keycloak users that can sign in (here `alice` and `bob`).
  Otherwise: SKIPPED.
- `curl`, `python3`.

## Setup

```bash
B=http://localhost:9099
c() { printf '%-28s %s\n' "$1" "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "${@:2}")"; }
```

## Steps

1. **No credentials.**
   ```bash
   c "agents, anonymous" $B/api/agents
   c "responses, anonymous" -X POST $B/v1/responses -H 'Content-Type: application/json' -d '{"input":"hi"}'
   ```
   **Expected:** `401` on both; the response carries `WWW-Authenticate: Bearer`
   and a JSON body — `curl -s $B/api/agents` prints
   `{"status":401,"error":"Unauthorized","message":"A bearer token is required: …"}`.
2. **Create a token as alice.** Sign in as `alice`, click the avatar in the
   header → the profile page opens at `/admin/profile`. Click **New token**,
   name `curl`, expiry *in 30 days*, **Create token**.
   **Expected:** a dialog shows the secret (`mct_…`) and a `curl` example; the
   table lists `curl` with the first characters of the token, *Last used: never*.
   Copy the secret into `A=` in the shell.
3. **The token works as alice and needs no CSRF token.**
   ```bash
   c "agents with token" -H "Authorization: Bearer $A" $B/api/agents
   AG=$(curl -s -H "Authorization: Bearer $A" $B/api/agents | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
   S=$(curl -s -X POST -H "Authorization: Bearer $A" -H 'Content-Type: application/json' -d "{\"agentId\":\"$AG\",\"userId\":\"bob\"}" $B/api/sessions | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["id"], d["userId"])')
   echo "$S"
   ```
   **Expected:** `200`; the session belongs to `alice` although the body says `bob`.
4. **The secret is not shown again.** Reload `/admin/profile`.
   **Expected:** the table shows the token's name and first characters only;
   *Last used* now has a time. Nothing on the page contains the full secret.
5. **Bob cannot reach alice's session.** Sign in as `bob` in a private window,
   create a token `B=` the same way.
   ```bash
   SID=${S%% *}
   c "bob: alice's history" -H "Authorization: Bearer $B" $B/api/sessions/$SID/history
   c "bob: alice's session list" -H "Authorization: Bearer $B" "$B/api/sessions?agentId=$AG"
   ```
   **Expected:** `404` for the history; bob's session list does not contain `$SID`.
6. **Revoke.** As alice, **Revoke** `curl` on the profile page and confirm.
   ```bash
   c "agents with revoked token" -H "Authorization: Bearer $A" $B/api/agents
   ```
   **Expected:** toast *Token revoked*; the row is gone; `401`.
7. **A forged token of the right shape.**
   ```bash
   c "forged token" -H "Authorization: Bearer mct_forged" $B/api/agents
   ```
   **Expected:** `401`.

## Not covered here

JWT bearer tokens (needs a Keycloak client that hands out access tokens to a
script) — pinned by `ApiFilterChainTest` in `mc-agent-security`.
