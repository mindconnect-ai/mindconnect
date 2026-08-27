# Manual regression tests

Human-executable test scripts that double as precise instructions for an LLM
agent. They cover the flows that automated tests cannot force deterministically
(UI behaviour, model-dependent tool calling, look & feel) and serve as the
regression checklist after larger rework.

## Layout

```
manual-tests/
  README.md            ← this file: conventions + how to execute + how to report
  _template.md         ← copy for new test cases
  <area>/              ← one directory per feature area (approval/, cancel/, memory/)
    <case>.md          ← one file per test case
    <case>/            ← complex case: directory with test.md + fixtures/
  runs/
    <date>-<scope>/report.md   ← one report per executed run (see below)
```

Rules:

- **One file = one test case. One directory = one feature area.** A case that
  needs fixtures (agent JSONs, sample files) becomes a directory with
  `test.md` and `fixtures/`.
- **Test cases are stable like code; results never go into the test file** —
  except the `last-verified` stamp (below).
- Steps use **deterministic references**: exact URLs, exact button labels,
  exact expected texts. Never "open the settings".
- **Every step has its own Expected.** The executor verifies after each step,
  not only at the end.
- Assertions should be **machine-checkable where possible** — name the exact
  message type / metadata / text to look for, and offer a curl/CLI check as an
  alternative to eyeballing the UI.

## How to execute (human or LLM)

1. Check the case's **Preconditions**. If an environment precondition cannot
   be met (server down, no tool-capable model loaded in LM Studio), the result
   is **SKIPPED** with the reason — never FAIL.
2. Perform the **Steps strictly in order**. After each step, verify its
   **Expected** before moving on.
3. Record the result per case:
   - **PASS** — every Expected held.
   - **FAIL** — name the first failing step, what was expected, what was
     observed. Attach evidence (screenshot, curl output) when available.
   - **SKIPPED** — with reason (unmet precondition, or the case's own
     premise-skip rule fired).
4. Run the **Cleanup** section — also after a FAIL.
5. Write the run report (below). On PASS, update the case's `last-verified`
   stamp; leave it untouched otherwise.

An LLM executing a suite should treat the run report as its final deliverable
and must not mark a step passed without having verified its Expected.

## Run reports

Test case ≠ test run. Each executed run gets its own directory under `runs/`:

```
runs/2026-08-27-approval-suite/
  report.md
  artifacts/          ← optional: screenshots, curl outputs, log excerpts
```

`report.md` starts with frontmatter (`date`, `commit`, `executor`,
`environment`, `scope`), then a result matrix (test | result | note), then one
detail section per FAIL (step number, expected vs observed, artifacts,
follow-up reference). Failures become tickets/todos immediately — the report
records them, it does not track them.

Old runs may be pruned after a while; the `last-verified` stamps in the test
files remain as the condensed history.

## The last-verified stamp

Each test case carries in its frontmatter:

```yaml
last-verified: 2026-08-27 (commit 20667bf, runs/2026-08-27-approval-suite)
```

Updated **only on PASS**, pointing at the run that proved it. `grep
last-verified -r .` shows which cases have gone stale.
