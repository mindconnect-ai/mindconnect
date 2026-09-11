---
id: coding-read-edit-verify
area: coding
requires: [server-9091, tool-model, jdk-21]
duration: ~6 min
last-verified: never
---

# Fix a bug: read first, edit exactly, prove it with the checks

**Goal:** The bundled `coding-assistant` reads before it touches a file,
changes code with `file_edit` (not a rewrite), runs the project's own check
through an approved `bash`, and leaves exactly the intended change on disk.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `agent-default` points at a tool-capable model — LM Studio or OpenAI
  (otherwise: SKIPPED)
- `javac -version` prints 21 or newer in the shell the server was started
  from; `bash` runs with the server's environment (otherwise: SKIPPED)
- `mindconnect.tools.working-dir-root` is unset (the app's default is the
  home directory) or contains `~/mc-manual-tests`

## Setup

1. From the repository root, copy the fixture to a fresh place (safe to
   repeat):
   ```bash
   rm -rf ~/mc-manual-tests/calc-project && mkdir -p ~/mc-manual-tests
   cp -R agents/doc/manual-tests/coding/fixtures/calc-project ~/mc-manual-tests/
   chmod +x ~/mc-manual-tests/calc-project/check.sh
   git -C ~/mc-manual-tests/calc-project init -q
   git -C ~/mc-manual-tests/calc-project add -A
   git -C ~/mc-manual-tests/calc-project -c user.name=manual-test \
       -c user.email=manual-test@example.invalid commit -qm fixture
   ~/mc-manual-tests/calc-project/check.sh
   ```
   The last command prints `FAIL subtract(7, 3): expected 4, got 10` and
   `1 CHECK(S) FAILED`.
2. Open http://localhost:9091/chat, click **New chat**, then **Model & tools**
   in the composer; under **Agent** pick `coding-assistant` and click
   **Apply**.
3. Click the composer's folder button, type the absolute path of
   `~/mc-manual-tests/calc-project` into the path field, click **Open**, then
   **Use this folder**. The folder button now reads **calc-project**.
   Note the session id from the URL (`/chat/sessions/<session-id>`).

## Steps

1. Send: `The checks fail. Find out why and fix it, then run the checks to
   prove it.`
   **Expected:** The agent may reproduce the failure first: an
   **Approval required** card for `bash` running `./check.sh` — click
   **Allow once**; its card prints `FAIL subtract(7, 3): expected 4, got 10`.
   Reading comes before any change — `grep`/`glob` and at least one
   `file_read` of `src/calc/Calculator.java`. Then a `file_edit` card whose
   result is a unified diff that turns `return a + b;` into `return a - b;`
   inside `subtract` and nothing else. Then an **Approval required** card
   for `bash` running `./check.sh`; the turn waits.
2. Click **Allow once**.
   **Expected:** The card disappears; the `bash` card shows
   `ALL CHECKS PASSED`; the answer names the bug (subtract added its
   operands) and says the checks pass.
3. Verify on disk:
   ```bash
   git -C ~/mc-manual-tests/calc-project diff --stat
   git -C ~/mc-manual-tests/calc-project diff
   ~/mc-manual-tests/calc-project/check.sh
   ```
   **Expected:** One file changed, `src/calc/Calculator.java | 2 +-`; the
   diff touches only the `subtract` line; the check prints
   `ALL CHECKS PASSED`.
4. Send: `Add multiply to Calculator, following the project's conventions.`
   **Expected:** `file_edit` cards for BOTH `Calculator.java` (a `multiply`
   method) and `CalculatorCheck.java` (a `multiply` check) — the convention
   comes from `AGENTS.md`. A NEW **Approval required** card for `bash`
   appears: "Allow once" did not stick.
5. Click **Allow for this session**.
   **Expected:** The checks run and print `ALL CHECKS PASSED`.
   `git -C ~/mc-manual-tests/calc-project diff --stat` lists both files, and
   `grep -n multiply ~/mc-manual-tests/calc-project/src/calc/CalculatorCheck.java`
   finds the new check.
6. Send: `Run the checks once more.`
   **Expected:** NO approval card this time; the `bash` card prints
   `ALL CHECKS PASSED`.

## Cleanup

- `rm -rf ~/mc-manual-tests/calc-project`
- Delete the chat (its menu → delete).

## Notes

- `coding-assistant` binds `bash` with **Needs approval**; the file tools run
  unasked. That is the seed's design, not a test setup.
- A model that rewrites `Calculator.java` with `file_write` instead of
  `file_edit` fails step 1 — the system prompt asks for exact edits, so this
  is a model-quality finding worth recording.
- Automated twins: `FileEditToolTest`, `GrepToolTest`,
  `FileReadToolPagingTest`, `BashToolOutputTest` (mc-agent-tools).
