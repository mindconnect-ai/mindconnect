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

## Branding the app

The Admin UI can carry your own name and look instead of Mindconnect's — set
`mindconnect.branding.*` and nothing has to be rebuilt:

```yaml
mindconnect:
  branding:
    title: ACME Assistants          # heading in the header, and the login page
    document-title: ACME Admin      # the browser tab; unset it follows title
    logo: /branding/acme.svg        # the mark beside the heading; "" for none
    logo-href: /admin/agents        # where a click on the brand leads
    favicon: /branding/favicon.png  # the tab icon; unset it follows the logo
    theme: default                  # the look it opens in (see below)
    stylesheets:                    # linked last, so their rules win
      - /branding/acme.css
    assets-dir: /etc/acme/branding  # served at /branding/**
```

Every setting is optional; unset, the app looks exactly as it ships.

**Where the assets come from.** An asset URL is used verbatim, so all three
forms work: a path into the app's own resources (`/img/logo.svg`), a path into
`assets-dir` (`/branding/acme.svg`), and an absolute URL on a CDN. `assets-dir`
is the one that keeps branding a deployment concern: point it at a directory
next to the running app, drop a logo and a stylesheet in, and the files are
served at `/branding/**` — no rebuild, no custom image. They are reachable
without a login, because the login page wears them.

**The stylesheet** is linked after every shipped one, so it only has to set the
framework's tokens — `--sui-color-primary`, `--sui-header-bg`, … — and every
control follows, the same way the bundled themes work. The login page takes the
same tokens, so it is branded along with the rest.

```css
:root {
    --sui-color-primary: #0a192c;
    --sui-color-action:  #0a192c;
    --sui-header-bg:     #0a192c;
}
```

**The theme picker** in the header offers every shipped theme unless
`style-picker` says otherwise — `disabled: true` takes it away entirely (and
then the look is genuinely fixed: neither the menu, nor `?theme=`, nor what
the browser remembers can change it), `themes:` narrows it to a list:

```yaml
mindconnect:
  branding:
    style-picker:
      themes: amethyst, default
```

**`theme`** picks which shipped look the shell opens in (`amethyst` — the
default —, `clody`, `gipiti`, `sorbet`, `compact`, `dark`, or `default` for the
framework's bare one). A theme is a class on `<html>` and therefore wins on
specificity over a `:root` rule, so a branding stylesheet written as above
wants `theme: default` underneath it. The theme picker in the header still
overrides the setting per browser.

A palette that several installations share is better off as a **theme** than
as a branding stylesheet: a theme is a class on `<html>`, so it can restyle
components (the nav rail's active row, the tabs, the toasts) and not just set
tokens, and it shows up in the header's theme picker. A theme is one file in
`static/css/` in `mc-agent-admin-ui-rest`, linked in `index.html` and listed
in `js/theme-switch.js` — the shipped ones are the worked examples. A branding
stylesheet is then left with what a theme cannot reach: the login page, which
has no app shell and therefore no theme class.

A whole branded instance is usually a Spring profile: put the settings above
into `application-<name>.yaml`, point `assets-dir` at a directory beside the
app, and start it with `--spring.profiles.active=<name>`.

## One process, several brands

A profile decides the brand when the process starts, which means one process
per brand. `switch` decides it per request instead, from the host in the
address bar — the same deployment behind two names wears two brands:

```yaml
mindconnect:
  branding:
    # what every other host gets
    title: Mindconnect Agent Runtime
    assets-dir: /etc/mindconnect/branding
    switch:
      acme:
        url-pattern: "*.acme.*"
        title: ACME AI
        logo: acme.svg              # a bare name: a file in assets-dir
        stylesheet: acme-sui.css    # "stylesheets:" for more than one
        style-picker:
          disabled: true            # this brand's look is not up for discussion
      mindconnect:
        url-pattern: app.mindconnect.ai
        logo: mindconnect-logo.svg
        style-picker:
          themes: amethyst, default
```

Each entry takes the same keys as the block above it, plus the `url-pattern`
that claims a host. What to know:

- **The pattern is a glob over the host name** — no port, no path, case
  ignored. `*` stands for any run of characters, dots included, so
  `*.acme.*` covers `agents.acme.com` and `ai.acme.co.uk` alike.
- **The first matching entry wins**, in the order they are declared. Write the
  specific pattern above the wildcard.
- **What an entry leaves unset comes from the top level**, and what the top
  level leaves unset is what the app ships. An entry says only what makes it
  different.
- **No match is not an error.** A host nobody claims — `localhost`, a health
  check, an internal name — gets the top-level branding.
- **A bare file name is a file in `assets-dir`**, which is what keeps a
  `switch` block from repeating `/branding/` on every line. A path
  (`/img/logo.svg`) or an absolute URL is used as written.

Since the assets of every brand are served from the same `/branding/**`, give
them distinct names (`acme.svg`, `mindconnect-logo.svg`) rather than a
directory each.

## Who may work where

A namespace holds everything — agents, LLM configs, workflows, skills,
sessions, files, vector stores — and it says who may do what in it, in two
lists of **e-mail addresses**:

| | Admin | User |
|---|---|---|
| Chat | yes | yes |
| Agents, LLM configs, workflows, skills, MCP servers, vector stores | yes | no |
| The namespace's variables | yes | no |
| Invite, promote, demote, remove | yes | no |
| Rename | yes | no |
| Delete the namespace | its creator only | no |
| Leave | yes | yes |

The creator is one of the admins and the one entry that cannot be taken out of
that list — a namespace without an admin would be nobody's to clean up. They
are also the only one who may delete it: an admin they promoted shapes the
namespace, but does not throw away everyone's work.

**People are listed by address, not by account.** An address exists before an
account does, so somebody can be invited before they have ever signed in — the
membership is waiting at their first sign-in, with nothing to send and nothing
pending. A name without an `@` gets `mindconnect.email-domain` appended, so an
installation whose accounts are all `<name>@company.example` invites by name; a
guest is invited with their own address.

### Being signed in is not being let in

`mindconnect.namespace-admins` names who shapes the default namespace
(`mindconnect.namespace`, `local` unless configured), and **naming anybody
closes it**:

```yaml
mindconnect:
  namespace: local
  namespace-admins: chief@company.example, david@company.example
  email-domain: company.example
```

From then on an account this installation lists nowhere is shown a page saying
so, with the way to sign out — not an empty app — and the API answers `403`
rather than falling back into the default namespace. Nobody is put into a
namespace by arriving under a host: an admin has to invite them.

Left unset, the default namespace stays open to every signed-in user, who is an
admin there. That is what a single-user installation and the dev mode run on,
and it is why nothing changes for them.

### A namespace per brand

A `switch` entry may name the namespace its hosts work in:

```yaml
    switch:
      acme:
        url-pattern: "*.acme.*"
        title: ACME AI
        namespace:
          admins: [chief@acme.example, david@acme.example]
```

The namespace's id is the entry's own name (`acme`) unless `id:` says
otherwise, its display name is the brand's `title`, and the first admin is its
creator. `creator: <address>` is the short form of a single admin.

It is created the first time somebody arrives under one of that brand's hosts,
and **never changed from configuration afterwards** — who is in a namespace is
its admins' business, not that of a file edited later. Somebody listed in it
lands there on their first sign-in; somebody listed anywhere also gets an empty
namespace of their own, with them as its admin.

## The main sections

The navigation has eight top-level entries:

| Section | What you do there |
|---------|-------------------|
| **[Agents](./agents.md)** | Create, edit, delete and copy agents. In the detail view: configure tools and start or continue sessions. |
| **[Tools](./tools.md)** | Browse the available tools, inspect their schemas, and test a tool. |
| **[Skills](./skills.md)** | Create, edit and delete the instruction packs agents load on demand. |
| **[LLM Configs](./llm-configs.md)** | Create, edit, delete and **test** LLM configs. API keys come from environment variables. |
| **Workflows** | The embedded workflow admin UI (`mc-workflow-admin-rest`): edit, save and run workflows. |
| **[Vector Stores](./vector-stores.md)** | Manage vector-store templates and stores, upload files, run semantic searches. |
| **[Migrations](./migrations.md)** | Review and apply changes to the bundled seed data (agents, LLM configs, workflows). |
| **API** | Embedded Swagger UI for the REST API under `/api/**`. |

## The task manager

The header carries a small badge that says what the task queue is doing right
now — `3 running · 2 waiting`, or `idle`. It is live: the page attaches to the
user's own server-sent event stream (`/admin/api/stream`) once, and the stream
stays attached while you navigate, so the count is current on every page
without polling.

The same stream carries what happens in your sessions while you are not
looking at them: a turn started or finished, a tool waiting for your answer, a
chat that got its title. The chat's history reflects it — a conversation with
a turn in flight says `running` where its age would be, one with an approval
card open says `needs input` — and it does so in every tab, whichever one
sent the message.

One thing to know about tabs: over HTTP/1.1 a browser keeps at most six
connections open per site, and every stream the app holds is one of them —
the user stream on every page, plus the session stream on a chat. A few chats
in a few tabs reach that limit, and from then on every request queues behind
the streams and pages stop loading. The tabs count their streams together and
warn you once the sum comes within one of the limit; close the tabs you no
longer need and the notice goes away. Served over HTTP/2 there is no such
limit and no notice. To see what one tab holds, type `console.table(mc.streams())`
into the browser console: a chat page lists `user-stream` and one
`msg-list-…`, any other page only `user-stream`.

A click on the badge opens the task queue, the admin UI's Task Manager:

- **Running now** — the live tasks as a tree, the way the queue links them:
  a chat turn, under it the tool calls it dispatched, and under a `run_agent`
  call the sub-agent's turn. Every row shows the agent or tool, what it is
  doing (session title, round), the status (`running`, `queued`, `suspended`
  while a turn waits on its sub-tasks, `cancelling`), the user it belongs to
  and how long it has been at it.
- **Finished recently** — the last tasks that completed, failed or were
  cancelled; a failed one opens to show the reason.
- **Cancel** — the ban icon on a row. Cancel is cooperative (a running task
  stops at its next checkpoint) and cascades: cancelling a turn takes its
  tool calls and sub-agents with it. It is offered for **your own tasks
  only** — the tasks whose session belongs to the signed-in user; other
  users' tasks are visible but not cancellable.

The dialog updates itself over the same stream while it is open.

## Notifications

Beside the task badge — which says what the *server* is doing — the header
carries a bell, which says what is waiting for *you*. It shows the number of
notices you have not read; a click opens the panel, and opening it is what
marks them read.

A notice is one line, sometimes a paragraph under it, and often a link to the
place that settles it: "Still to set up: Mail server → Set it up". **Dismiss**
takes one off the list, **Dismiss all** clears it.

Where they come from: an installation is rarely finished the moment your
account exists. A tool may want a mailbox configured, a provider your own API
key — and you cannot be expected to guess that `MC_EMAIL_HOST` is the name it
looks up. On the first request after you sign in, the installation looks at
what the installed tools [declare they need](../creating-a-tool.md#asking-the-user-for-something-uservariables),
fills in what has a sensible default, and raises one notice per value that is
still missing.

Two things follow from how they are stored, and both are deliberate:

- **A notice does not pile up.** The check runs on every sign-in and says the
  same thing every time; each notice carries the *condition* it stands for, so
  the second sign-in finds the entry that is already there — read or unread —
  and leaves it alone. Dismiss it and it is not raised at you again.
- **A notice clears itself.** Set the value and the next sign-in stops
  reporting it; the entry disappears without your having to tidy up. Should
  the condition come back — a variable removed, a key revoked — you get a
  fresh notice rather than silence.

For hosts: anything can contribute a notice by implementing `SetupCheck` as a
Spring bean and returning `Notification.Draft`s for whatever is missing right
now. `NotificationService` handles the collapsing, and `UserSetup` clears what
a check has stopped reporting. Where no `NotificationRepository` is assembled
there is no bell and nothing is raised.

## Related

- [Environment variables](../environment-variables.md) — every variable you can
  set (API keys, models, Keycloak, …).
- [Creating a tool](../creating-a-tool.md#asking-the-user-for-something-uservariables) —
  how a tool declares the variables it needs from each user.
- [LLM configuration reference](../llm-configs-reference.md) — the bundled
  configs and their fields.
- [Bundled agents](../bundled-agents.md) — the agents that ship out of the box.
