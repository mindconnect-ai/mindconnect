---
id: namespaces-roles-invitations-and-what-a-user-sees
area: namespaces
requires: [server-9090, keycloak, two-accounts]
duration: ~15 min
last-verified: never
---

# Two lists per namespace: an admin shapes it, a user chats in it

**Goal:** A namespace lists its people by e-mail in two lists. An admin creates
and changes what it holds and decides who else is in it; a user sees the chat
and nothing else, and every route that shapes the namespace answers `403` to
them — through the UI and through the API alike. Somebody can be invited before
they have ever signed in, and the invitation is waiting at their first sign-in.
Inviting is offered only where the caller is an admin.

## Preconditions

- An installation with **authentication on** (`MC_PROFILES=keycloak`), a realm
  with **two accounts that carry e-mail addresses** (otherwise: SKIPPED). This
  case is written against a local run at http://localhost:9090; against a
  deployed host, set `B` to it and use that host's two accounts.
- The default namespace has named admins (`MC_NAMESPACE_ADMINS`), so it is a
  namespace like any other. With the list empty it is open to everybody and
  steps 2–9 are SKIPPED.
- The first account (**the admin**) is in `MC_NAMESPACE_ADMINS`; the second
  (**the guest**) is in no namespace yet.
- `curl`, `jq`, a browser, and a second browser profile (or a private window)
  so both accounts can be signed in at once.

## Setup

```bash
B=http://localhost:9090
ADMIN_MAIL=admin@example.com      # the address of the first account
GUEST_MAIL=guest@example.com      # the address of the second
c() { printf '%-42s %s\n' "$1" "$(curl -s -o /dev/null -m 10 -w '%{http_code}' "${@:2}")"; }
```

Sign in as the admin in the first browser. Leave the second browser signed out
for now.

## Steps

1. As the admin, open `$B/admin/namespaces`.
   **Expected:** a card per namespace you are in. The default namespace is a
   card like the others — a **Member** / **Role** table listing your address
   with the role `creator`, an **Invite** form and a **Variables of …** table.
   It does **not** say "Every signed-in user".

2. Invite the guest as a user: **Invite** → the guest's address →
   **May: Use it — chat, and run its workflows** → **Invite**.
   **Expected:** the toast `<address> may now work in '<namespace>'.`; the
   member table has a second row, role `user`, and the address reads
   `<address> — not signed in yet` until they do.

3. Invite somebody who does not exist at all: **Invite** →
   `nobody@example.com` → **Invite**.
   **Expected:** accepted the same way, with a row of its own. Nothing is
   looked up — an address is listed as it is written, which is what makes
   inviting somebody before their first sign-in possible. Remove that row again
   with **Remove**.

4. A name instead of an address: **Invite** → `nobody` → **Invite**.
   **Expected:** it is taken as `nobody@<mindconnect.email-domain>` (the
   installation's domain, `local` unless configured). Remove the row again.

5. Now sign in as the guest in the second browser.
   **Expected:** the chat. In the header, the namespace button shows the
   namespace they were invited into.

6. **What a user sees.** In the guest's browser, look at the navigation.
   **Expected:** **Chat** and the version entry at the bottom — nothing else.
   No Agents, Tools, Skills, LLM Configs, Workflows, MCP Servers, Vector
   Stores, Install or API.

7. **What a user reaches.** Still as the guest, open each of
   `$B/admin/agents`, `$B/admin/llm-configs`, `$B/workflow-admin`,
   `$B/admin/vector-stores`.
   **Expected:** each answers `403`; none of them renders. The chat at
   `$B/chat` works, and so does `$B/admin/profile`.

8. **What a user's token reaches.** Create a token on the guest's profile page
   (**API tokens** → **New token**), then:
   ```bash
   T=<the token>
   c "list agents"    -H "Authorization: Bearer $T" $B/api/agents
   c "create an agent" -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
      -d '{"name":"user-made","description":"should not exist","systemPrompt":"hi","llmConfigName":"agent-default"}' \
      $B/api/agents
   ```
   **Expected:** `403` for both — a token carries its owner's rights, and a
   user of a namespace creates nothing in it.

9. **Promote, and it changes at once.** As the admin, on `$B/admin/namespaces`,
   press **Make admin** on the guest's row. Reload the guest's browser.
   **Expected:** the guest's navigation now has every entry, `$B/admin/agents`
   renders, and the same `POST /api/agents` from step 8 answers `200`. Delete
   the agent it created.

10. **Demote again** with **Make user** on the guest's row, and reload the
    guest's browser.
    **Expected:** back to the chat alone; `$B/admin/agents` is `403` again.

11. **Only an admin invites.** As the guest (a user again), open
    `$B/admin/profile` → **Namespaces**.
    **Expected:** the row actions are **Switch to**, **Leave** and **Delete** —
    there is **no Invite…** button, because they shape none of these
    namespaces. In the admin's browser the same table does offer it.

12. **And the server says so too**, whatever the screen offers:
    ```bash
    c "guest invites, by API" -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
       -d '{"email":"third@example.com","role":"ADMIN"}' $B/admin/api/namespaces/<namespace>/members
    ```
    **Expected:** `403`.

13. **The creator cannot be removed or demoted.** As the admin, on the member
    table, press **Make user** and then **Remove** on your own row.
    **Expected:** the toasts `The creator of '<namespace>' stays an admin` and
    `The creator of '<namespace>' cannot be removed`; the row is unchanged.

## Cleanup

```bash
# remove the guest from the namespace again (as the admin, in the browser:
# Remove on their row), and revoke the token created in step 8
```
