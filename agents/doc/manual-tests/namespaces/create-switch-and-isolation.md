---
id: namespaces-create-switch-and-isolation
area: namespaces
requires: [server-9090]
duration: ~10 min
last-verified: 2026-09-17 (commit a561e9ca, runs/2026-09-16-full-suite)
---

# A new namespace: created from the header, entered at once, and sealed off

**Goal:** A namespace created from the header's namespace switcher becomes the
current one and starts empty. What is created in it is invisible in the default
namespace and the other way round. Reserved and malformed ids are refused with
the reason. An API call chooses its namespace by header or path prefix only —
a malformed header is `400`, a namespace the caller is not a member of is `403`,
and without either it works in the default namespace, never in the one the
user last picked in the admin UI.

## Preconditions

- Admin UI running at http://localhost:9090 with authentication off (the
  default: every request is the dev user `mc_user`) (otherwise: SKIPPED)
- The default namespace is `local` (`mindconnect.namespace` unset)
- `curl`, `jq`
- Step 12 only: `OPENAI_API_KEY` set in the server's environment and valid
  (`start.sh` loads it from `mc.env`) — otherwise step 12 is SKIPPED

## Setup

```bash
B=http://localhost:9090
H='X-Mindconnect-Namespace: manual-ns'
c() { printf '%-34s %s\n' "$1" "$(curl -s -o /dev/null -m 10 -w '%{http_code}' "${@:2}")"; }
# a leftover manual-ns from an earlier run: delete it (answers 200 or 303)
curl -s -o /dev/null -X DELETE $B/admin/api/namespaces/manual-ns
# start from the default namespace
curl -s -o /dev/null -X POST $B/admin/api/namespaces/switch/local
```

In the browser, reload and pick `local` in the header's namespace menu if it
shows another one — the `curl` switch changes only the choice remembered on the
user, not the browser session's.

## Steps

1. Open http://localhost:9090/admin/agents and click the namespace button in
   the header (layers icon, labelled `local`).
   **Expected:** a menu with one entry per namespace you may work in — `local`
   ticked — then a divider, **New namespace…** and **Namespaces & members**.

2. **New namespace…**
   **Expected:** a dialog titled **New namespace** with the fields **Id**
   (hint: `Lower-case letters, digits, '-' and '_' — it names the directory and the URL prefix, and cannot change.`)
   and **Name**, and the buttons **Create and switch** and **Cancel**.

3. Reserved ids: type each of `system`, `ns`, `api`, `admin`, `v1` into **Id**
   and press **Create and switch**.
   **Expected:** the dialog stays open each time with the error
   `'system' is reserved; pick another id` (and likewise `'ns' is reserved; pick another id`, …).
   Machine check:
   ```bash
   for id in system ns api admin v1; do
     curl -s -X POST $B/admin/api/namespaces -H 'Content-Type: application/json' -d "{\"id\":\"$id\"}" \
       | grep -o "'$id' is reserved; pick another id"
   done
   ```
   prints five lines.

4. Malformed and taken ids: **Id** `Manual NS`, then `-manual`, then `local`.
   **Expected:** the dialog stays open with, in turn,
   `A namespace id is 1–64 lower-case letters, digits, '-' or '_', got 'Manual NS'`,
   `A namespace id is 1–64 lower-case letters, digits, '-' or '_', got '-manual'`,
   `Namespace 'local' already exists`. No namespace was created:
   `ls data/system/namespaces/` lists no new file.

5. **Id** `manual-ns`, **Name** `Manual NS` → **Create and switch**.
   **Expected:** the agents list of the new namespace: `Agents  (0)`,
   `No agents yet`. The header button now reads `Manual NS`; its menu lists
   `local`, then `Manual NS` ticked. http://localhost:9090/admin/llm-configs shows
   `LLM Configurations` with no entries; http://localhost:9090/admin/skills shows
   `No skills yet`. `cat data/system/namespaces/manual-ns.json` names
   `"createdBy": "mc_user"`.

6. Fill it through the API, naming the namespace in the header:
   ```bash
   curl -s -D - -o /dev/null -X POST $B/api/llm-configs -H "$H" -H 'Content-Type: application/json' -d '{
     "id": "manual-ns-openai", "name": "manual-ns-openai", "provider": "OPENAI",
     "model": "${OPENAI_MODEL:gpt-5.4-mini}", "baseUrl": "https://api.openai.com",
     "apiKey": "${OPENAI_API_KEY}", "defaultTemperature": 0.7, "maxOutputTokens": 4096,
     "contextWindowTokens": 128000 }' | grep -i -e '^HTTP' -e '^x-mindconnect-namespace'
   NSAG=$(curl -s -X POST $B/api/agents -H "$H" -H 'Content-Type: application/json' \
     -d '{"name":"manual-ns-agent","description":"lives in manual-ns","systemPrompt":"Answer in three words.","llmConfigName":"manual-ns-openai"}' | jq -r .id)
   echo $NSAG
   ```
   **Expected:** `HTTP/1.1 200` and `X-Mindconnect-Namespace: manual-ns` — the
   server names the namespace it worked in; an agent id is printed. Reload the
   admin UI (still in `Manual NS`): the agent `manual-ns-agent` and the config
   `manual-ns-openai` are listed. (The agents list groups agents by category in
   collapsed sections — `Agents  (1)` with `General (1)`; open the group to see
   the name.)

7. And one thing in the default namespace, through the API without a header:
   ```bash
   LAG=$(curl -s -X POST $B/api/agents -H 'Content-Type: application/json' \
     -d '{"name":"manual-local-agent","description":"lives in local","systemPrompt":"Answer in three words.","llmConfigName":"agent-default"}' | jq -r .id)
   ```
   **Expected:** an id is printed; the admin UI in `Manual NS` still lists only
   `manual-ns-agent`.

8. Isolation, both ways:
   ```bash
   echo "manual-ns sees:";  curl -s -H "$H" $B/api/agents | jq -r '.[].name'
   echo "local sees ns agent?";  curl -s $B/api/agents | jq -r '.[].name' | grep -c -x manual-ns-agent
   echo "local sees ns config?"; curl -s $B/api/llm-configs | jq -r '.[].name' | grep -c -x manual-ns-openai
   c "ns agent by id, from local" $B/api/agents/$NSAG
   c "local agent by id, from ns" -H "$H" $B/api/agents/$LAG
   c "ns agent by path prefix"   $B/ns/manual-ns/api/agents/$NSAG
   ```
   **Expected:** manual-ns sees exactly `manual-ns-agent`; the two counts are
   `0`; `404`, `404`, `200`.

9. Switch back in the UI: header button → `local`.
   **Expected:** the agents list of `local` — the seeded agents and
   `manual-local-agent` (under the `General` group), no `manual-ns-agent`; LLM Configs has no
   `manual-ns-openai`. Switch to `Manual NS` again, then back to `local` once
   more: each list follows.

10. The header and the prefix, refused:
    ```bash
    c "malformed header"         -H 'X-Mindconnect-Namespace: Manual NS' $B/api/agents
    c "malformed header (dots)"  -H 'X-Mindconnect-Namespace: ../local' $B/api/agents
    c "header, not a member"     -H 'X-Mindconnect-Namespace: manual-nobody' $B/api/agents
    c "prefix, not a member"     $B/ns/manual-nobody/api/agents
    c "header, default ns"       -H 'X-Mindconnect-Namespace: local' $B/api/agents
    ```
    **Expected:** `400`, `400`, `403`, `403`, `200`. (`manual-nobody` does not
    exist, so nobody is a member of it — with authentication off this is the
    way to see the `403`.)

11. The API does not follow the admin UI's choice. Switch the UI to
    `Manual NS` (header menu), then:
    ```bash
    curl -s -D - -o /dev/null $B/api/agents       | grep -i '^x-mindconnect-namespace'
    curl -s -D - -o /dev/null $B/admin/api/agents | grep -i '^x-mindconnect-namespace'
    ```
    **Expected:** `X-Mindconnect-Namespace: local` for `/api/…` and
    `X-Mindconnect-Namespace: manual-ns` for `/admin/api/…` — the admin UI
    follows the choice remembered on the user even without a browser session;
    the REST API never does.

12. Helpers are created on first use, in the namespace (needs the
    `OPENAI_API_KEY` precondition, otherwise SKIPPED):
    ```bash
    S=$(curl -s -X POST $B/api/sessions -H "$H" -H 'Content-Type: application/json' -d "{\"agentId\":\"$NSAG\"}" | jq -r .id)
    curl -sN -m 120 -X POST $B/api/sessions/$S/chat -H "$H" -H 'Content-Type: text/plain' --data 'Say hello.' > /dev/null
    sleep 5
    curl -s -H "$H" $B/api/agents | jq -r '.[] | [.name, .llmConfigName] | @tsv'
    ```
    **Expected:** besides `manual-ns-agent`, a `title-generator` on
    `manual-ns-openai` — the namespace's only config, since it has no
    `agent-default`. `local` is unchanged by it (its own `title-generator`, if
    any, is on `agent-default`).

## Cleanup

```bash
curl -s -o /dev/null -X POST $B/admin/api/namespaces/switch/local
curl -s -o /dev/null -X DELETE $B/admin/api/namespaces/manual-ns
curl -s -o /dev/null -X DELETE $B/api/agents/$LAG
```

Reload the admin UI; the header reads `local` and its menu has no `Manual NS`.

## Notes

- The header's switch is a `POST` answered with `303` to `/admin/api/agents`;
  the shell is rendered again in the new namespace.
- Precedence of the request's namespace: the `/ns/{id}/` prefix, then the
  `X-Mindconnect-Namespace` header, then (admin UI only) the session's and the
  remembered choice, then the default namespace.
- The response bodies of the `400`/`403` in step 10 are Spring's error JSON; the
  reason (`Not a namespace: '…'`, `You are not a member of namespace '…'`) is
  passed to `sendError` but only shows in the body when the server includes
  error messages. Only the status codes are asserted.
- A second user and a real `403` for a namespace that exists but is not yours
  need authentication on — see `namespaces/leave-delete-and-members.md`.
- Automated twins: `ScopeBindingFilterTest` and `NamespacePathFilterTest`
  (mc-agent-starter-namespace), `NamespaceServiceTest` (mc-namespace-core),
  `NamespaceUiControllerTest` (mc-agent-admin-ui-rest).
