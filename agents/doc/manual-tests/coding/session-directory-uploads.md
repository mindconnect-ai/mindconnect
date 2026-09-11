---
id: coding-session-directory-uploads
area: coding
requires: [server-9091, tool-model, embedding-model]
duration: ~6 min
last-verified: never
---

# A chat's own directory: uploads land on disk and stay reachable

**Goal:** A chat opened without a working directory works in its own
directory under the user's home; a file attached to it is copied into that
directory's `uploads/`, named with its path in the prompt so the file tools
can open it, and stays reachable after the chat moves to a project.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `agent-default` points at a tool-capable model, and the `embeddings`
  llm-config reaches an embeddings model (see `chat/file-upload.md`;
  otherwise: SKIPPED)
- `mindconnect.users.home` is unset (default
  `<data.base-dir>/<namespace>/home/{user}`; with auth off the user is
  `mc_user`, the namespace `local`)
- `~/mc-manual-tests` lies under `mindconnect.tools.working-dir-root`

## Setup

1. Copy the fixture as in `coding/read-edit-verify.md` Setup 1 (the `git`
   lines may be skipped).
2. Create the upload:
   `printf 'Die Geheimzutat der Testsuppe ist Paprika.\n%.0s' 1 2 3 > /tmp/soup.md`
3. Open http://localhost:9091/chat, **New chat**, **Model & tools** →
   **Agent** `coding-assistant` → **Apply**. Note the session id from the
   URL.

## Steps

1. Look at the composer's folder button, then open
   http://localhost:9091/admin/sessions/<session-id>/memory — or, with
   authentication off (the app's default),
   `curl -s http://localhost:9091/api/sessions/<session-id>/memory | jq -r .systemPrompt`.
   **Expected:** The button reads the session id. The system prompt's
   `## Working directory` section says
   `You are working in \`<…>/home/mc_user/sessions/<session-id>\``, and
   `ls -d <that path>` shows the directory exists.
2. In the chat, click **Attach files** and attach `/tmp/soup.md`.
   **Expected:** A success toast ending `attached — the agent can now search
   it.`; the chip `soup.md` above the input;
   `ls <session dir>/uploads/` lists `soup.md`.
3. Reload the Memory page (or repeat the curl of step 1).
   **Expected:** An `## Attached files` section with the line
   `- soup.md (Markdown) — on disk at \`<session dir>/uploads/soup.md\`` and
   the sentence `A file with a path is also a file on disk`.
4. Send: `Open the attached soup.md with file_read and quote its first line.`
   **Expected:** A `file_read` card on `<session dir>/uploads/soup.md`; the
   quote is `Die Geheimzutat der Testsuppe ist Paprika.`
5. Folder button → type the absolute path of `~/mc-manual-tests/calc-project`
   → **Open** → **Use this folder**; then open the folder button again.
   **Expected:** The button reads **calc-project**; under
   **Additional directories** the chat's own directory
   `<…>/sessions/<session-id>` is listed with **Remove**.
6. Close the chooser (**Cancel**). Send: `Open the attached soup.md again by
   its absolute path and quote its first line.`
   **Expected:** The `file_read` succeeds with the same quote — the own
   directory stayed reachable after the move; no "outside the session's
   directories" error.
7. Remove the chip (×).
   **Expected:** The removal toast; `ls <session dir>/uploads/` no longer
   lists `soup.md`; on the Memory page the `## Attached files` section is
   gone.

## Cleanup

- `rm -rf ~/mc-manual-tests/calc-project /tmp/soup.md`
- Delete the chat. Its directory under `home/mc_user/sessions/` stays on
  disk; remove it by hand if wanted.

## Notes

- An image is not copied: it travels with the next message as an image part.
- A server that sets `mindconnect.users.home` blank turns session directories
  off; uploads are then searchable only, and the prompt says the files are
  NOT on the filesystem.
- Automated twins: `UserHomeTest`, `SystemPromptRendererWorkingDirTest`
  (mc-agent-runtime-core), `AgentRuntimeBuilderTest` (a session opened without
  a directory gets its own, and keeps it when moved).
