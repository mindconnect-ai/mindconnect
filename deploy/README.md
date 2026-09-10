# Single-host deployment

Caddy terminates TLS and routes by hostname; Postgres, Keycloak and the agent
apps sit on the internal Docker network with **no published port**. Nothing but
Caddy is reachable from the internet.

```
Internet ──:443──► caddy ──┬──► admin-ui:9090
                           └──► keycloak:8080
                                    │
                                    └──► postgres:5432
```

The admin UI is the only agent service: it embeds `mc-agent-api-responses-rest`
and therefore already serves the OpenAI-compatible API alongside its own UI.
`mc-agent-api-app` is not part of this deployment.

The host runs Docker and nothing else — no Java, no Maven, no nginx. Images are
built in CI (`.github/workflows/docker-images.yml`) and pulled from GHCR, so the
server never compiles and old versions occupy registry space rather than the
host's disk.

## Setup

```bash
scp -r deploy/ hetzner:/opt/mindconnect/
ssh hetzner
cd /opt/mindconnect
cp .env.example .env && chmod 600 .env && vi .env   # domains, passwords, key
docker compose up -d
```

Point the DNS A records at the host first — Caddy asks Let's Encrypt for
certificates on the first request and needs to be reachable on 80 and 443.

Keycloak starts empty apart from the bootstrap admin. Import the realm — it
carries the roles, the confidential `mc-admin-ui` client and one enabled user
without credentials, deliberately: no password is ever written into a file
here.

```bash
set -a; . ./.env; set +a
docker cp keycloak/mindconnect-realm.json mindconnect-keycloak-1:/tmp/realm.json
KC="docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh"
$KC config credentials --server http://localhost:8080 --realm master \
   --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD"
$KC create realms -f /tmp/realm.json
CID=$($KC get clients -r mindconnect -q clientId=mc-admin-ui --fields id --format csv --noquotes)
$KC get clients/$CID/client-secret -r mindconnect --fields value --format csv --noquotes
```

Put that secret in `KC_CLIENT_SECRET`, set each user's password in the admin
console at `https://<AUTH_DOMAIN>/admin`, and only then set
`MC_PROFILES=keycloak` and `docker compose up -d admin-ui`. Turning
authentication on before a user has a password locks everyone out.

The realm's redirect and post-logout URIs are absolute, so a different
`ADMIN_DOMAIN` means editing the JSON before importing.

Logout goes through Keycloak's end-session endpoint, which rejects any
`post_logout_redirect_uri` the client does not list. Verify it after importing
— and note that `kcadm get clients/<id> --fields attributes` renders nested
maps as `{}` whether or not they are set, so ask for the whole client instead:

```bash
$KC get clients/$CID -r mindconnect | grep post.logout
```

The endpoint itself can be checked without a session; a registered URI answers
302, anything else 400 "Invalid redirect uri":

```bash
curl -so /dev/null -w '%{http_code}\n' \
  "https://<AUTH_DOMAIN>/realms/mindconnect/protocol/openid-connect/logout?client_id=mc-admin-ui&post_logout_redirect_uri=https%3A%2F%2F<ADMIN_DOMAIN>%2F"
```

## Deploying a new version

```bash
docker compose pull && docker compose up -d
```

`MC_TAG` in `.env` selects what "new" means: `main` follows the branch, a git
SHA pins a version. Rolling back is that variable plus `up -d` — the old image
is still in GHCR.

Prune what accumulates, or 20 GB will not last:

```bash
docker image prune -af --filter "until=168h"
```

## The memory budget

**This is the tight part.** On a 4 GB host the limits in `docker-compose.yml`
add up to roughly:

| Service | Limit | Note |
|---|---:|---|
| caddy | 96 MB | |
| postgres | 512 MB | one instance, several databases |
| keycloak | 1 GB | JVM; the floor for it is around 700 MB |
| admin-ui | 1.6 GB | JVM plus Chromium when a browser tool runs |
| **sum** | **~3.2 GB** | leaving ~600 MB for the kernel and dockerd |

That fits, with nothing to spare. It does **not** fit alongside another
application such as the Cardmarket portal — that needs 8 GB. The admin UI's
limit is the one to raise if you ever get more memory; Chromium is what makes
its usage spiky.

Two things follow. First, the limits are not decoration: without them each JVM
sizes its heap against the host's total RAM and three of them will overcommit.
Under a limit of 1 GB or less the JVM also picks SerialGC by itself, which is
the right choice here — smaller footprint than G1 and these are not
latency-critical services.

Second, give the host swap. Hetzner Cloud images ship without it, and swap
turns a spike that would kill a container into one that merely gets slow:

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
sysctl -w vm.swappiness=10
```

## Backups

The Postgres volume holds everything except workspace files. Neither is backed
up by anything here yet:

```bash
docker compose exec -T postgres pg_dumpall -U mindconnect | gzip > dump-$(date +%F).sql.gz
```

`MINDCONNECT_ENCRYPTION_SECRET_KEY` belongs in a password manager, not only in
`.env`. Stored LLM credentials are encrypted with it and are unrecoverable
without it.

## Deploying without CI

Until the workflow has run on `main`, `ghcr.io/mindconnect-ai/mc-agent-admin-ui`
does not exist and `docker compose pull` fails. The image can be built on the
host instead — upload the jar and the Dockerfile, then build it under a tag of
your own:

```bash
docker build -f Dockerfile -t ghcr.io/mindconnect-ai/mc-agent-admin-ui:local .
sed -i 's/^MC_TAG=.*/MC_TAG=local/' ../.env
docker compose up -d admin-ui
```

Build it on the host, not on a developer machine: an Apple-silicon laptop
produces arm64 images and this is amd64.

The separate tag matters once CI does publish, because both paths would
otherwise write `:main`. A local build and CI's would be indistinguishable, a
stray `docker compose pull` would silently replace yours, and nothing would say
which is running. With `MC_TAG=local` that same `pull` fails loudly instead —
there is no `:local` in the registry — and `.env` answers "what is deployed
right now" in one line.

Going back is the same two settings in reverse:

```bash
sed -i 's/^MC_TAG=.*/MC_TAG=main/' .env
docker compose pull && docker compose up -d
docker builder prune -f
```

Keep the build directory. It holds a jar and a Dockerfile, and it is the
difference between a one-command detour and re-uploading 344 MB. Prune the
build *cache* freely — that is the part which grows.

## Known gaps

- The app exposes no health endpoint, so its container healthcheck only probes
  the TCP port. Adding `spring-boot-starter-actuator` and probing
  `/actuator/health` would make `depends_on: service_healthy` meaningful for
  the app as well.
- The realm is imported once by hand rather than on every start, so a change
  to `keycloak/mindconnect-realm.json` does not reach a running Keycloak by
  itself — re-import it or edit in the admin console.
- Nothing is backed up automatically yet; see above for the pg_dumpall line
  that belongs in a cron job.
