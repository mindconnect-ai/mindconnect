---
id: mcp-tool-settings-override
area: mcp
requires: [server-9091, npx, lm-studio-tool-model]
duration: ~7 min
last-verified: 2026-09-11 (working tree on e462251, branch feature/mcp-support, runs/2026-09-11-mcp-support — OpenAI via agent-default)
---

# Override what a tool tells the model, and keep the original in view

**Goal:** An operator can replace a tool's description and a single parameter's
description; the replacement reaches the model; and the source's own text stays
visible so the replacement can be compared against what it replaced.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- The server `manualfs` from `mcp/register-stdio-server.md` is registered
  (run that case's Setup and steps 1–4 first, or use any registered MCP server
  and substitute its tool names below)
- LM Studio running with a tool-capable LLM loaded (for step 7; otherwise that
  step is SKIPPED, the rest still applies)

## Steps

1. http://localhost:9091/admin/tools, search `mfs_read_text_file`, open the row
   and click **Settings**.
   **Expected:** Dialog **Settings for mfs_read_text_file** with, in order:
   **Available to agents** (on), **What the tool says about itself**
   (read-only, holding the server's own text), **Description for the model**
   (empty), and one field per parameter.
2. Note the exact text in **What the tool says about itself**. Type into
   **Description for the model**:
   `Liest eine Textdatei. Nur unterhalb /tmp/mcp-manual erlaubt.`
   Click **Save**.
   **Expected:** Message "Saved." The dialog stays open.
3. **The regression that matters:** close the dialog and open **Settings**
   again.
   **Expected:** **What the tool says about itself** still holds the *server's*
   text from step 1 — **not** the override just saved. **Description for the
   model** holds the override. The two are different.
4. Into the parameter field **Parameter "path"** type:
   `Absoluter Pfad, z. B. /tmp/mcp-manual/note.txt`
   Click **Save**, then close the dialog.
   **Expected:** "Saved."
5. Back in the catalog, open the `mfs_read_text_file` row.
   **Expected:** The row's description is the override from step 2, and the
   parameters table shows the new text for `path`. Types and `required` are
   unchanged.
6. Check the store:
   `cat data/*/system/tool-settings.json`
   **Expected:** An entry `mfs_read_text_file` holding exactly `description`
   and `parameterDescriptions.path` — and **no** entry for any tool that was
   not touched. Only deviations are stored.
7. Bind `mfs_read_text_file` to an agent and ask it to read a file.
   **Expected:** The tool call happens, and the description the model was
   offered is the override — visible in the LLM call trace
   (agent session → **Traces**), not the server's original text.
8. Reopen **Settings** and click **Reset to defaults**, confirm.
   **Expected:** Message "Reset — the tool describes itself again." The
   catalog row shows the server's original description again, and the entry is
   gone from `data/*/system/tool-settings.json`.

## Cleanup

- Step 8 is the cleanup. If it was not reached, delete the tool's entry from
  `data/<namespace>/system/tool-settings.json` by hand, or use
  **Reset to defaults**.

## Notes

- Overrides **replace**, they never append. Appending could only grow, and a
  description is foreign text landing unfiltered in the model's context.
- The schema's structure is never touched — only `description` fields inside
  `properties`. Overriding types or `required` would build calls the server
  rejects.
- An agent's own description of a tool beats the operator's. Its parameter
  texts do **not** get dropped along with it — automated twin:
  `OverlayToolRegistryTest.an_agents_description_does_not_take_the_parameter_texts_with_it`.
- Step 3 is the regression for a defect where the dialog resolved through the
  overlay and showed the override back to itself.
