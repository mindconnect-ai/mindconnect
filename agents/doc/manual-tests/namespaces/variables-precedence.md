---
id: namespaces-variables-precedence
area: namespaces
requires: [server-9090, openai-key, internet]
duration: ~10 min
last-verified: 2026-09-17 (commit a561e9ca, runs/2026-09-16-full-suite; step 5 checked with a fake user value and a local key-classifying endpoint instead of the valid key)
---

# Variables: yours first, then the namespace's, then the server's

**Goal:** A `${VAR}` in an LLM config's API key resolves from the signed-in
user's own variables first, then from the variables of the namespace the
request works in, then from the server's environment. A namespace's variables
are set by its creator on **Namespaces & members**; the default namespace has
none of its own. Values are stored encrypted and never shown again.

**How it is made observable:** the config's **Test** sends one message. A
valid key passes (`✓ OK`), a bogus one fails (`✗ Failed`). The same variable
name is set at each level in turn, valid or bogus, so the outcome shows which
level won.

## Preconditions

- Admin UI running at http://localhost:9090 with authentication off (the dev
  user `mc_user`) (otherwise: SKIPPED)
- The server was started with a valid `OPENAI_API_KEY` in its environment
  (`start.sh` loads it from `mc.env`) (otherwise: SKIPPED)
- The same key at hand in the shell (`K=<the key>`) to paste into a variable
- `mc_user` has no variable `OPENAI_API_KEY` yet: http://localhost:9090/admin/profile
  → tab **Your variables** does not list it (otherwise remove it first)
- `curl`, `jq`

## Setup

```bash
B=http://localhost:9090
NS=manual-vars
H="X-Mindconnect-Namespace: $NS"
curl -s -o /dev/null -X POST $B/admin/api/namespaces/switch/local
curl -s -o /dev/null -X DELETE $B/admin/api/namespaces/$NS
curl -s -o /dev/null -X POST $B/admin/api/namespaces -H 'Content-Type: application/json' -d "{\"id\":\"$NS\",\"displayName\":\"Manual vars\"}"
curl -s -o /dev/null -X POST $B/api/llm-configs -H "$H" -H 'Content-Type: application/json' -d '{
  "id": "manual-vars-openai", "name": "manual-vars-openai", "provider": "OPENAI",
  "model": "${OPENAI_MODEL:gpt-5.4-mini}", "baseUrl": "https://api.openai.com",
  "apiKey": "${OPENAI_API_KEY}", "defaultTemperature": 0.7, "maxOutputTokens": 4096,
  "contextWindowTokens": 128000 }'
t() { curl -s -X POST $B/api/llm-configs/manual-vars-openai/test -H "$H" -H 'Content-Type: application/json' -d '{"message":"Say OK."}' | jq -c '{ok, errorMessage}'; }
```

In the browser, reload and pick `Manual vars` in the header's namespace menu
(a `curl` call has no browser session, so it does not move the browser); the
header button reads `Manual vars`.

**UI test, used in the steps below:** http://localhost:9090/admin/llm-configs →
`manual-vars-openai` → **Test** → dialog `Test manual-vars-openai` → **Message**
`Say OK.` → **Send**. The result under the form starts with `✓ OK · … ms` or
`✗ Failed · … ms`. `t` in the shell is the same check over REST.

## Steps

1. **Server environment only.** Run the test.
   **Expected:** `✓ OK`; `t` prints `{"ok":true,"errorMessage":null}`.

2. **The namespace's variables.** http://localhost:9090/admin/namespaces.
   **Expected:** the card `Manual vars (manual-vars) — current` with the member
   row `mc_user`, role `creator`, the actions **Delete namespace**, an
   **Invite** form, and a table **Variables of Manual vars** (columns **Name**, **Value**)
   with the action **Add variable…**. The card of `local` has no variables
   table but the note
   `The default namespace has no variables of its own: a ${VAR} here takes your own value, else the server's environment. A namespace you create can carry variables for everyone in it.`

3. **A refused name.** **Add variable…** → dialog `Add variable to Manual vars`
   → **Name** `1_KEY`, **Value** `x` → **Save**.
   **Expected:** the dialog stays open with
   `'1_KEY' is not a variable name: letters, digits and '_', not starting with a digit`.
   (Re-rendered, its title names the id — `Add variable to manual-vars` — not
   the display name; a cosmetic difference, not a FAIL.)

4. **The namespace beats the server.** In the same dialog: **Name**
   `OPENAI_API_KEY`, **Value** `sk-manual-bogus-namespace-key` → **Save**.
   **Expected:** toast `Variable saved`:
   `Configs in 'manual-vars' referring to ${OPENAI_API_KEY} now use this value.`
   The table lists `OPENAI_API_KEY` with the value `••••••••`. Run the test:
   `✗ Failed` (the provider refuses the key); `t` prints `"ok":false`.

5. **The user beats the namespace.** http://localhost:9090/admin/profile →
   tab **Your variables** → **Add variable…** → dialog `Add variable` →
   **Name** `OPENAI_API_KEY`, **Value** the valid key → **Save**.
   **Expected:** toast `Variable saved`:
   `Configs referring to ${OPENAI_API_KEY} now use your value.`
   **Your variables** lists `OPENAI_API_KEY` with `••••••••`. Run the test:
   `✓ OK`.

6. **Values are not readable anywhere.**
   ```bash
   grep -rF "$K" data/ logs/ ; echo "exit=$?"
   grep -rF sk-manual-bogus-namespace-key data/ logs/ ; echo "exit=$?"
   jq .environment data/system/namespaces/manual-vars.json
   ```
   **Expected:** `exit=1` twice; the namespace's `OPENAI_API_KEY` value starts
   with `enc:`. Neither page nor the add dialog ever shows a value again (the
   **Value** field of a new dialog is empty).

7. **Remove yours: the namespace's value is back.** Profile → tab **Your
   variables** → row `OPENAI_API_KEY` → **Remove** → confirm
   (`Remove this variable? A config that refers to it falls back to the namespace's or the server's value.`).
   **Expected:** toast `Variable removed`:
   `Configs referring to ${OPENAI_API_KEY} fall back to the namespace's or the server's value.`
   Run the test: `✗ Failed` — no restart, no re-save of the config.

8. **Remove the namespace's: the server's value is back.**
   http://localhost:9090/admin/namespaces → **Variables of Manual vars** → row
   `OPENAI_API_KEY` → **Remove** → confirm
   (`Remove this variable? A config that refers to it falls back to the server's value, if there is one.`).
   **Expected:** toast `Variable removed`:
   `Configs in 'manual-vars' referring to ${OPENAI_API_KEY} fall back to the server's value.`
   The table is empty. Run the test: `✓ OK`.

9. **The namespace's variable stays in its namespace.** Add the bogus
   namespace variable again (step 4), then test a config in `local` that uses
   the same placeholder:
   ```bash
   OID=$(curl -s $B/api/llm-configs | jq -r '.[] | select(.name=="openai-default") | .id')
   curl -s -X POST $B/api/llm-configs/$OID/test -H 'Content-Type: application/json' -d '{"message":"Say OK."}' | jq -c '{ok}'
   t
   ```
   **Expected:** `{"ok":true}` for `openai-default` in `local`, `"ok":false`
   for `manual-vars-openai` — the variable reaches only its own namespace.
   (If this installation's `openai-default` does not use `${OPENAI_API_KEY}`,
   this step is SKIPPED.)

10. **The default namespace takes no variables.**
    ```bash
    curl -s $B/admin/api/namespaces/local/environment/new | grep -o "Only the creator of 'local' sets its variables."
    curl -s -X POST $B/admin/api/namespaces/local/environment -H 'Content-Type: application/json' \
      -d '{"name":"MANUAL_X","value":"y"}' | grep -o "has no variables of its own[^\"]*"
    ```
    **Expected:** the first prints the line (the toast `Not yours to configure`);
    the second prints
    `has no variables of its own — it belongs to the installation, so set them in the server's environment`
    (from `The default namespace 'local' has no variables of its own — …`).

## Cleanup

```bash
curl -s -o /dev/null -X POST $B/admin/api/namespaces/switch/local
curl -s -o /dev/null -X DELETE $B/admin/api/namespaces/manual-vars
```

- Profile → **Your variables**: remove `OPENAI_API_KEY` if a FAIL left it
  there — it applies in every namespace.
- `unset K`

## Notes

- A user's own value reaches a config's **API key only**; `model`, `baseUrl`
  and `name` resolve from the namespace's variables and the server's
  environment. This case varies the API key alone, so that rule does not
  interfere; a user variable in `baseUrl` would be ignored.
- Variables are read on every call — no cache, no restart; steps 7 and 8 rely
  on it.
- Only the creator of a namespace sees its **Variables of …** table (on
  **Namespaces & members** and on the profile tab **Namespace variables**) and may set
  them; the refusal for another member needs a second user — see
  `namespaces/leave-delete-and-members.md`.
- An executor that must not paste a real key (an LLM) can run step 5 with a
  fake user value (`sk-manual-bogus-user-key`) and see which level won from a
  second config `manual-vars-echo` whose `baseUrl` is a local HTTP listener
  that answers `401` and logs only whether the `Authorization` bearer equals
  the namespace fake, the user fake, or something else (the server's key):
  `USER` after step 5, `NAMESPACE` after step 7, other after step 8. The
  bogus-key test result alone cannot tell step 5 from step 4 — both fail
  with the same `401`.
- Automated twins: `NamespaceEnvVarResolverTest`, `UserEnvVarResolverTest`,
  `NamespaceServiceTest`.
