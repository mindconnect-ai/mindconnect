---
id: coding-bash-background-and-cancel
area: coding
requires: [server-9091, tool-model, jdk-21]
duration: ~6 min
last-verified: never
---

# bash: a server in the background, and nothing survives a stop or a timeout

**Goal:** `bash` starts a long-running process detached with a pid and a log
in the session's directory, `process_kill` lists and ends it with its whole
tree, and a foreground command that is stopped or runs past its timeout is
killed with everything it started.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `agent-default` points at a tool-capable model (otherwise: SKIPPED)
- `jwebserver` (ships with the JDK) is on the PATH of the shell the server
  was started from (otherwise: SKIPPED, or use the fallback in Notes)
- Port 8765 is free: `lsof -nP -iTCP:8765 -sTCP:LISTEN` prints nothing
- `mindconnect.users.home` is unset, so the chat has a directory of its own

## Setup

1. Copy the fixture as in `coding/read-edit-verify.md` Setup 1 (the `git`
   lines may be skipped).
2. Open http://localhost:9091/chat, **New chat**, **Model & tools** →
   **Agent** `coding-assistant` → **Apply**. Note the session id from the
   URL — `<session dir>` below is
   `<data.base-dir>/local/home/mc_user/sessions/<session-id>`.
3. Folder button → `~/mc-manual-tests/calc-project` → **Open** →
   **Use this folder**.

## Steps

1. Send: `Start a static file server for this directory in the background
   with: jwebserver -p 8765 -b 127.0.0.1 — then tell me whether it is up.`
   **Expected:** An **Approval required** card for `bash`.
2. Click **Allow for this session**.
   **Expected:** The `bash` card's result starts
   `Started in background: pid <n>`, has a line
   `Log: <session dir>/logs/bash-<digits>.log`, a line
   `Status: still running after …`, and a log excerpt containing
   `port 8765`. The answer says the server is up and names the pid.
3. Verify from a terminal:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8765/AGENTS.md
   ls <session dir>/logs/
   ```
   **Expected:** `200`; the log file named in step 2 is listed.
4. Send: `List this session's background processes.`
   **Expected:** A `process_kill` card called without a pid; its result has a
   line `pid <n> (running): jwebserver …`.
5. Send: `Stop the file server.`
   **Expected:** A `process_kill` card with pid `<n>`; its result starts
   `Killed pid <n>`. From a terminal, the `curl` of step 3 prints `000` and
   `lsof -nP -iTCP:8765 -sTCP:LISTEN` prints nothing.
6. Send: `Run sleep 300 with bash in the foreground, with a timeout of 600
   seconds, and wait for it to finish.`
   **Expected:** A running `bash` card, no approval card (allowed for the
   session). `pgrep -f 'sleep 300'` prints a pid.
7. Click **Stop** in the composer.
   **Expected:** The turn ends within a few seconds.
   `pgrep -f 'sleep 300' || echo gone` prints `gone`.
8. Send: `Run "sleep 30; echo done" with bash and a timeout of 2 seconds.`
   **Expected:** The `bash` result starts
   `Error: command timed out after 2 seconds` and does not contain `done`;
   `pgrep -f 'sleep 30' || echo gone` prints `gone`.

## Cleanup

- `pkill -f 'jwebserver -p 8765'` (in case step 5 failed)
- `rm -rf ~/mc-manual-tests/calc-project`
- Delete the chat.

## Notes

- Fallback without `jwebserver`: `python3 -m http.server 8765 --bind 127.0.0.1`;
  the log excerpt then shows the Python banner instead of `port 8765` once it
  is flushed.
- A command that looks like a dev server (`npm run dev`, `mvn spring-boot:run`,
  `docker compose up` without `-d`, …) run in the foreground without a
  timeout is refused with `Error: this looks like a server or watcher that
  keeps running` — worth a spot check.
- A command that would let a process go by itself — a `&` nothing `wait`s
  for, `nohup`, `setsid`, `disown` — is refused and pointed at `background`.
  The first run of this case found a model starting `jwebserver … & echo $!`:
  the server outlived the call, and `process_kill` never heard of it.
- Every background process still alive when the server stops is killed with
  it (shutdown hook); spot-check by restarting the server after step 2.
- Automated twin: `BashToolOutputTest` (mc-agent-tools).
