---
id: coding-working-dir-chooser
area: coding
requires: [server-9091, tool-model]
duration: ~6 min
last-verified: never
---

# Choose where a chat works: the folder button and its chooser

**Goal:** The composer's folder button opens a chooser that browses the
server's tree under the working-dir root and nothing beyond it, sets the
chat's working directory and additional directories, and the choice reaches
`bash` and the system prompt, survives a reload and a model switch.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- The `server` profile is not active — it removes the folder button and
  `bash` (see `coding/server-profile.md`; otherwise: SKIPPED)
- `agent-default` points at a tool-capable model (otherwise: SKIPPED)
- `mindconnect.tools.working-dir-root` is unset (the app's default is the
  home directory). Step 9 needs a root that does not contain `/tmp`; with a
  root of `/` that step is SKIPPED.
- `mindconnect.users.home` is unset, so a new chat gets a directory of its own

## Setup

1. Copy the fixture as in `coding/read-edit-verify.md` Setup 1 (the `git`
   lines may be skipped).
2. `rm -rf ~/mc-manual-tests/calc-project/scratch`
3. Open http://localhost:9091/chat, **New chat**, **Model & tools** →
   **Agent** `coding-assistant` → **Apply**. Note the session id from the
   URL (`/chat/sessions/<session-id>`).

## Steps

1. Look at the composer.
   **Expected:** The folder button reads the session id — the chat works in
   its own directory `<data.base-dir>/local/home/mc_user/sessions/<session-id>`.
2. Click the folder button.
   **Expected:** A **Working directory** dialog: the path field holds the
   chat's own directory, a **Folders in …** list below it, an
   **Additional directories** section that says `None yet.`, and the buttons
   **Use this folder** and **Cancel**.
3. Type `~/mc-manual-tests` into the path field and click **Open**.
   **Expected:** The field shows the absolute path of `~/mc-manual-tests`;
   the folder list contains `calc-project`.
4. Click `calc-project` in the list.
   **Expected:** The field shows `<home>/mc-manual-tests/calc-project`; the
   list shows `src` and no hidden folder (no `.mindconnect`, no `.git`).
5. Type `scratch` into the **New folder** field below the list and click
   **Create**.
   **Expected:** A toast titled **New folder** reading
   `Created <home>/mc-manual-tests/calc-project/scratch`; the field is now in
   `scratch`; `test -d ~/mc-manual-tests/calc-project/scratch && echo ok`
   prints `ok`.
6. Type `.hidden` into the **New folder** field and click **Create**.
   **Expected:** An error toast titled **Not applied** starting
   `Not a folder name: .hidden`;
   `test -e ~/mc-manual-tests/calc-project/scratch/.hidden || echo absent`
   prints `absent`.
7. Type `~/mc-manual-tests/calc-project`, click **Open**, then
   **Use this folder**.
   **Expected:** The dialog closes; a toast titled **Working directory**
   reading `Working directory: <home>/mc-manual-tests/calc-project`; the
   folder button reads **calc-project**.
8. Click the folder button again.
   **Expected:** The field holds the calc-project path. Under
   **Additional directories** the chat's own directory
   `<…>/sessions/<session-id>` is listed with **Remove** — moving to a
   project keeps it reachable.
9. Type `/tmp` into the field and click **Open**.
   **Expected:** An error toast titled **Not applied** containing
   `The working directory must lie under`; the field and the list stay as
   they were.
10. Open `~/mc-manual-tests/calc-project/src`, click **Add src**.
    **Expected:** A toast titled **Additional directories** reading
    `Added <home>/mc-manual-tests/calc-project/src`; `src` is listed under
    **Additional directories**.
11. Click **Remove** next to the `src` entry, then **Cancel**.
    **Expected:** A toast reading `Removed <home>/mc-manual-tests/calc-project/src`;
    the dialog closes; the folder button still reads **calc-project**.
12. Send: `Run pwd with bash and show me its output.` When the
    **Approval required** card appears, click **Allow once**.
    **Expected:** The `bash` card prints `<home>/mc-manual-tests/calc-project`.
13. Open http://localhost:9091/admin/sessions/<session-id>/memory, or from a
    terminal:
    `curl -s http://localhost:9091/api/sessions/<session-id>/memory | jq -r .systemPrompt | grep -A3 '^## Working directory'`
    **Expected:** The system prompt has a `## Working directory` section:
    ``You are working in `<home>/mc-manual-tests/calc-project`.`` followed by
    `You may also use these directories, by absolute path:` and a line with
    the chat's own directory — and no line for `src`.
14. Reload the chat page (F5).
    **Expected:** The folder button still reads **calc-project**.
15. If a second llm-config exists: **Model & tools** → pick another
    **Model** → **Apply**, then open the folder button. (With only one
    llm-config: SKIPPED for this step.)
    **Expected:** The button still reads **calc-project** and the own
    directory is still listed under **Additional directories** — a model
    switch is not a change of directory.

## Cleanup

- `rm -rf ~/mc-manual-tests/calc-project`
- Delete the chat.

## Notes

- The field takes `~` and `$HOME` as the home directory.
- On a server the root carries `{user}` (`/srv/mindconnect/home/{user}`):
  every user browses their own tree only.
- The same over REST, on this app's port as on the agent server
  (`mc-agent-api-app`): `GET /api/directories?userId=mc_user&path=~/mc-manual-tests`,
  `POST /api/sessions` with `workingDir`/`additionalDirs`,
  `PUT /api/sessions/{id}/working-dir`. The curl checks assume
  authentication is off, the app's default.
- Automated twins: `WorkingDirPolicyTest`, `WorkingDirBrowserTest`,
  `AgentSessionWorkingDirTest` (mc-agent-runtime-core),
  `DirectoryPickerComponentTest` (mc-agent-chat-ui-rest).
