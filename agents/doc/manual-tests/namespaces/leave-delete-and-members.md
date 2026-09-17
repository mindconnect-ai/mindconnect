---
id: namespaces-leave-delete-and-members
area: namespaces
requires: [server-9090, keycloak-two-users]   # the second only for steps 8–13
duration: ~15 min
last-verified: 2026-09-17 (commit a561e9ca, runs/2026-09-16-full-suite; steps 1–7 only, 8–13 skipped — no second user)
---

# Deleting a namespace, and who may invite, remove, leave and configure

**Goal:** The creator deletes a namespace with everything in it; whoever was
working in it lands in the default namespace, and a namespace of the same id
created later starts empty. Only the creator invites, removes members, sets
variables and deletes; any other member may leave, and loses access at once.
The default namespace can be neither deleted nor invited into.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- Steps 1–7: authentication off (the default; every request is `mc_user`)
- Steps 8–13: the admin UI started with the `keycloak` profile
  (`--spring.profiles.active=keycloak`) and two users, here `alice` and `bob`,
  who have each signed in once (a user unknown to the installation cannot be
  invited). Use two browsers or a private window. Otherwise steps 8–13 are
  SKIPPED — with authentication off there is only one user.
- File persistence (`mindconnect.persistence=file`, the default) for the
  `data/` checks; with Postgres skip those checks, not the steps.
- `curl`, `jq`

## Setup

```bash
B=http://localhost:9090
H='X-Mindconnect-Namespace: manual-del'
c() { printf '%-30s %s\n' "$1" "$(curl -s -o /dev/null -m 10 -w '%{http_code}' "${@:2}")"; }
curl -s -o /dev/null -X POST $B/admin/api/namespaces/switch/local
curl -s -o /dev/null -X DELETE $B/admin/api/namespaces/manual-del
```

In the browser pick `local` in the header's namespace menu.

## Steps — authentication off

1. **Create and fill.** Header namespace menu → **New namespace…** → **Id**
   `manual-del`, **Name** `Manual delete` → **Create and switch**. Then:
   ```bash
   curl -s -o /dev/null -X POST $B/api/llm-configs -H "$H" -H 'Content-Type: application/json' \
     -d '{"id":"manual-del-cfg","name":"manual-del-cfg","provider":"OPENAI","model":"gpt-5.4-mini","baseUrl":"https://api.openai.com","apiKey":"${OPENAI_API_KEY}","defaultTemperature":0.7,"maxOutputTokens":1024}'
   AG=$(curl -s -X POST $B/api/agents -H "$H" -H 'Content-Type: application/json' \
     -d '{"name":"manual-del-agent","systemPrompt":"x","llmConfigName":"manual-del-cfg"}' | jq -r .id)
   OLD_S=$(curl -s -X POST $B/api/sessions -H "$H" -H 'Content-Type: application/json' -d "{\"agentId\":\"$AG\"}" | jq -r .id)
   ls data/manual-del/ && ls data/system/namespaces/manual-del.json
   ```
   **Expected:** the agents list shows `manual-del-agent` (in the collapsed `General` group); the namespace has a
   directory under `data/` and a record under `data/system/namespaces/`.

2. **What the creator's card offers.** http://localhost:9090/admin/namespaces
   **Expected:** the card `Manual delete (manual-del) — current`: member row
   `mc_user` with role `creator`, the action **Delete namespace** and no
   **Leave**, an **Invite** field, a table **Variables of Manual delete**. The card of `local`
   lists `Every signed-in user — the default namespace is open to all.` and has
   no actions.

3. **Invitations that cannot work.** On the `manual-del` card, **Invite**
   `nobody-here` → **Invite**; then **Invite** `mc_user` → **Invite**.
   **Expected:** error toasts titled `Not invited`:
   `No user 'nobody-here' on this installation. A user appears after their first sign-in.`
   and `'mc_user' is already a member of 'manual-del'`.

4. **The creator cannot drop out.** Member row `mc_user` → **Remove** → confirm
   (`Remove this member from the namespace? Their sessions there stay.`).
   **Expected:** toast `Not removed`: `The creator of 'manual-del' cannot be removed`.
   Then http://localhost:9090/admin/profile → tab **Namespaces**, row
   `Manual delete (current)` → **Leave** → confirm.
   **Expected:** toast `Not left`: `The creator of 'manual-del' cannot be removed`.

5. **The default namespace is nobody's.** Profile → row `local` → **Invite…**
   **Expected:** info toast `Nobody to invite`:
   `The default namespace is open to every signed-in user.`
   Row `local` → **Delete** → confirm.
   **Expected:** toast `Not deleted`:
   `The default namespace 'local' is open to everyone; there is nothing to delete`.
   The row is still there.

6. **Delete the namespace you are in.** http://localhost:9090/admin/namespaces
   → card `manual-del` → **Delete namespace** → confirm
   (`Delete 'Manual delete' with everything in it — agents, sessions, files, workflows, vector stores, MCP servers? This cannot be undone.`).
   **Expected:** the agents list of `local` opens and the header reads `local`;
   its menu has no `Manual delete`. Then:
   ```bash
   ls data/manual-del 2>&1; ls data/system/namespaces/manual-del.json 2>&1
   c "api into deleted ns" -H "$H" $B/api/agents
   curl -s -D - -o /dev/null $B/admin/api/agents | grep -i '^x-mindconnect-namespace'
   curl -s $B/api/agents | jq -r '.[].name' | grep -c -x manual-del-agent
   ```
   `No such file or directory` twice; `403` (nobody is a member of a namespace
   that does not exist); `X-Mindconnect-Namespace: local` — the remembered
   choice moved to the default namespace too; `0`.

7. **The same id again starts empty.** Header menu → **New namespace…** →
   **Id** `manual-del` → **Create and switch**.
   **Expected:** `Agents  (0)`, `No agents yet`; http://localhost:9090/admin/llm-configs
   has no `manual-del-cfg`. And:
   ```bash
   c "old session in new ns" -H "$H" $B/api/sessions/$OLD_S/history
   curl -s -H "$H" $B/api/llm-configs | jq length
   ```
   `404`; `0`. Now switch to `local` in the header menu, open
   http://localhost:9090/admin/profile → tab **Namespaces** → row `manual-del` → **Delete** → confirm.
   **Expected:** toast `Deleted`:
   `Namespace 'manual-del' and everything in it are gone.` — deleting a
   namespace you are not in keeps you on the page.

## Steps — authentication on, two users

8. **Alice creates and invites.** As `alice`: header menu → **New namespace…**
   → **Id** `manual-team` → **Create and switch**. Create one agent there
   (**New Agent**, any LLM config the namespace has — create one first if the
   list is empty). http://localhost:9090/admin/namespaces → card
   `manual-team — current` → **Invite** `bob` → **Invite**.
   **Expected:** toast `Invited`: `<bob's name> may now work in 'manual-team'.`
   The card lists `bob` with role `member`.

9. **Bob is in, but not in charge.** As `bob`: reload; header menu → `manual-team`.
   **Expected:** bob sees alice's agent. On http://localhost:9090/admin/namespaces
   the card `manual-team — current` has **Leave** but no **Delete namespace**,
   no **Invite** field and no **Variables of manual-team** table. Then:
   - member row `alice` → **Remove** → confirm → toast `Not removed`:
     `Only the creator of 'manual-team' removes other members`;
   - http://localhost:9090/admin/profile → tab **Namespaces** → row `manual-team` → **Invite…** →
     toast `Not yours to invite into`: `Only the creator of 'manual-team' invites.`;
   - same row → **Delete** → confirm → toast `Not deleted`:
     `Only the creator deletes 'manual-team'`;
   - open http://localhost:9090/admin/api/namespaces/manual-team/environment/new
     in bob's browser: the JSON carries the toast `Not yours to configure`,
     `Only the creator of 'manual-team' sets its variables.`

10. **Bob's API token follows his membership.** As `bob`: profile → **New
    token** → create, copy the secret into `BT=`.
    ```bash
    c "bob, member"  -H "Authorization: Bearer $BT" -H 'X-Mindconnect-Namespace: manual-team' $B/api/agents
    ```
    **Expected:** `200`.

11. **Alice removes bob.** As `alice`: card `manual-team` → row `bob` →
    **Remove** → confirm.
    **Expected:** toast `Removed`: `bob is no longer a member of 'manual-team'.`
    As `bob`: reload any admin page → the header reads the default namespace
    and its menu has no `manual-team`.
    ```bash
    c "bob, removed" -H "Authorization: Bearer $BT" -H 'X-Mindconnect-Namespace: manual-team' $B/api/agents
    ```
    `403`.

12. **Bob leaves by himself.** As `alice`: invite `bob` again. As `bob`: switch
    to `manual-team`, http://localhost:9090/admin/namespaces → card
    `manual-team` → **Leave** → confirm
    (`Leave 'manual-team'? You will need a new invitation to come back.`).
    **Expected:** bob lands on the agents list of the default namespace (he
    left the one he was in). Alice's card no longer lists `bob`. The token call
    from step 11 answers `403` again.

13. **Deleting a namespace someone else is in.** As `alice`: invite `bob`; as
    `bob`: switch to `manual-team`. As `alice`: **Delete namespace** → confirm.
    **Expected:** alice lands in the default namespace. As `bob`: the next
    reload shows the default namespace, and `manual-team` is gone from his menu
    and his profile's **Namespaces** table.

## Cleanup

```bash
curl -s -o /dev/null -X POST $B/admin/api/namespaces/switch/local
curl -s -o /dev/null -X DELETE $B/admin/api/namespaces/manual-del
```

- With authentication on: as `alice`, delete `manual-team` if a FAIL left it;
  as `bob`, **Revoke** the token of step 10.

## Notes

- Deleting the namespace you are in answers with a redirect to the agents list,
  so no `Deleted` toast shows in that case (step 6); deleting another one shows
  the toast (step 7).
- A member removed or a namespace deleted while someone works in it takes
  effect on that user's next request: the request filter drops a session choice
  the user may no longer use and falls back to the remembered one, which the
  removal has already moved to the default namespace.
- The `403` for a namespace that exists but is not yours needs a second user
  (step 11); with authentication off, `manual-del` after its deletion (step 6)
  shows the same code for a namespace that does not exist.
- Step 7 is also a regression check for a namespace re-created under the id of
  a deleted one: nothing of the first may show through.
- Automated twins: `NamespaceServiceTest` (mc-namespace-core),
  `NamespaceUiControllerTest` (mc-agent-admin-ui-rest).
