# Changelog fragments

One file per user-facing change. The release workflow collects them into
`CHANGELOG.md` under the version it cuts and deletes them — nothing here is
ever edited by hand at release time.

Why not write straight into `CHANGELOG.md`? Every PR appended its entry at the
same spot under `## [Unreleased]`, so each merge put every other open PR in
conflict. Separate files never collide.

## Writing an entry

Put a Markdown file into the folder of its [Keep a Changelog][kac] section,
named after your branch (without the `fix/`, `feat/` prefix):

```
changelog.d/fixed/cancel-tool-cards.md
```

The content is exactly the bullet you would have written under
`## [Unreleased]` — the area, a bold sentence saying what is different, then
the explanation for someone deciding whether to upgrade:

```markdown
- **agents:** **a stopped turn no longer leaves its tool spinning.** After
  **Stop** the card of the tool that was running kept showing "running…"
  until the page was reloaded. It now turns failed the moment the turn ends.
```

A file may hold more than one bullet; continuation lines are indented by two
spaces as usual. It must start with `- `.

| Folder | Use it for |
|---|---|
| `added/` | new features, endpoints, tools, settings |
| `changed/` | behaviour that is different now |
| `deprecated/` | things that still work but are going away |
| `removed/` | things that are gone |
| `fixed/` | bugs whose symptom someone may have been living with |
| `security/` | vulnerabilities |

Folders without an entry yet (`deprecated/`, `security/`) do not exist in git —
create them when you need them. Any other folder name is an error, so a typo
cannot drop an entry from a release.

Refactorings, tests, docs and build plumbing need no entry; label such a PR
`no-changelog` if it touches shipped Java.

## Seeing what the next release will say

```bash
.github/scripts/changelog-release.sh preview
```

prints the assembled notes — sections in the order above, fragments sorted by
file name within a section — without changing anything. Entries still written
directly under `## [Unreleased]` in `CHANGELOG.md` are merged in as well.

[kac]: https://keepachangelog.com/en/1.1.0/
