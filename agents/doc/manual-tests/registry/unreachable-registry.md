---
id: registry-unreachable
area: registry
requires: [server-9091]
duration: ~4 min
last-verified: never
---

# A registry that cannot be read says why, and costs nothing else

**Goal:** A wrong repository, a wrong ref or a missing token produces a readable
screen with a way out — and leaves the other registries, and everything already
imported, alone.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)

## Steps

1. http://localhost:9091/registry → **Add registry**, **Repository**
   `mindconnect-ai/this-does-not-exist`, **Save**, then open the row.
   **Expected:** A screen headed with the registry's name saying it cannot read
   `mindconnect-ai/this-does-not-exist@main`, naming the missing file and
   mentioning that a private repository needs a token. Three buttons: **Try
   again**, **Edit registry**, **All registries**. No stack trace.
2. Click **Edit registry**, set **Branch, tag or commit** to `no-such-ref`,
   **Save**, open the row again.
   **Expected:** The same screen, now naming `@no-such-ref`.
3. Type `owner` (no slash) into a new registry's **Repository** field and save.
   **Expected:** The form comes back with everything typed still in it and a
   message that this is not a registry — expected `owner/repo[@ref][:index-path]`.
4. Add a registry pointing at a repository that exists but has no index, e.g.
   `mindconnect-ai/mindconnect`, and open it.
   **Expected:** Cannot read … `registry.json`. The other registries in the list
   are unaffected.
5. Go to http://localhost:9091/admin/agents.
   **Expected:** Unchanged — a registry that cannot be read imports nothing and
   breaks nothing.

## Cleanup

- Remove every registry added above.

## Notes

- Step 1's message differs by one clause depending on whether the repository is
  private or absent: GitHub answers 404 for both, on purpose, and the screen
  says so rather than guessing.
