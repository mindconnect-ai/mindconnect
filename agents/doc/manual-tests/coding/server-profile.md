---
id: coding-server-profile
area: coding
requires: [server-9091, tool-model, embedding-model]
duration: ~8 min
last-verified: never
---

# The server profile: no shell, no directory choice, uploads still work

**Goal:** With the `server` profile active, `bash` and `process_kill` exist
nowhere — not in the tool catalog, not for an agent that names them — no
user can point a chat at a directory, every chat works in its own directory
where attached files still land, and deleting a chat removes that directory.

## Preconditions

- Admin UI running at http://localhost:9091 with `server` added to the active
  profiles, e.g. `SPRING_PROFILES_ACTIVE=server` (next to any profile it
  already runs with); `MC_TOOLS_DISABLED` is unset (otherwise: SKIPPED)
- The startup log has the line
  `Tools switched off by configuration: [bash, process_kill]`
- `agent-default` points at a tool-capable model, and the `embeddings`
  llm-config reaches an embeddings model (see `chat/file-upload.md`;
  otherwise: SKIPPED)
- `mindconnect.users.home` is unset (default
  `<data.base-dir>/<namespace>/home/{user}`; with auth off the user is
  `mc_user`, the namespace `local`)

## Setup

1. Create the upload:
   `printf 'Die Geheimzutat der Testsuppe ist Paprika.\n%.0s' 1 2 3 > /tmp/soup.md`

## Steps

1. Open http://localhost:9091/admin/tools.
   **Expected:** Neither `bash` nor `process_kill` is listed; `file_read`,
   `grep` and the other file tools are. The shell's group is gone if nothing
   else was in it.
2. Open http://localhost:9091/chat, **New chat**, **Model & tools** →
   **Agent** `coding-assistant` → **Apply**. Note the session id from the
   URL.
   **Expected:** The composer has the attach, microphone, model and send
   buttons, but no folder button.
3. Send: `Run echo hello with bash.`
   **Expected:** No `bash` card and no approval card. The answer says there
   is no shell tool (it may offer the file tools instead).
4. Attach `/tmp/soup.md` with the paperclip and send:
   `What is the secret ingredient in the attached file?`
   **Expected:** The answer names Paprika. From a terminal,
   `ls <data.base-dir>/local/home/mc_user/sessions/<session-id>/uploads/`
   lists `soup.md`.
5. Try to choose a directory through the API:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:9091/api/directories?userId=mc_user"
   curl -s -o /dev/null -w '%{http_code}\n' -X PUT -H 'Content-Type: application/json' \
     -d '{"workingDir":"/tmp"}' http://localhost:9091/api/sessions/<session-id>/working-dir
   curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
     -d '{"agentId":"agent-default","userId":"mc_user","workingDir":"/tmp"}' http://localhost:9091/api/sessions
   ```
   **Expected:** `400` three times; the server log names
   `mindconnect.working-dirs.choice`. The same `POST` without `workingDir`
   answers `200`.
6. Delete the chat from step 2 in the chat list.
   **Expected:** `<data.base-dir>/local/home/mc_user/sessions/<session-id>`
   no longer exists; `ls <data.base-dir>/local/home/mc_user/sessions/` still
   lists the other sessions' directories.

## Cleanup

- Delete the session opened by the `POST` without `workingDir` in step 5
  (`curl -X DELETE http://localhost:9091/api/sessions/<id>`).
- `rm /tmp/soup.md`
- Restart the admin UI without the `server` profile before running the other
  `coding/` cases.

## Notes

- Step 3 depends on the model: some models claim they ran the command. What
  counts is that no `bash` card appears.
- `MC_TOOLS_DISABLED=` (empty) brings `bash` back on a server profile; that is
  the deployer's decision, not something the admin UI can change.
