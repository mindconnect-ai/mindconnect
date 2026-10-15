# .claude

The Claude Code harness of this repository, deliberately small. It is
committed so that every clone and every contributor runs the same skills.

| Path | What | Committed |
| --- | --- | --- |
| `settings.json` | shared permissions | yes |
| `settings.local.json` | personal overrides | no |
| `skills/verify/` | `/verify [module...]`: tests of the touched modules, then the area's ArchUnit rules | yes |
| `skills/smells/` | `/smells <module>`: read-only check of a module against the conventions, as a table | yes |
| `skills/tidy/` | `/tidy <module>`: behaviour-preserving cleanup of one module or package, one topic per commit | yes |
| `skills/gap/` | `/gap <module> <finding>`: record a finding in `doc/known-gaps.md` | yes |
| `skills/rule/` | `/rule <criterion>`: add or sharpen a convention, as an ArchUnit rule when a tool can decide it | yes |
| `skills/adr/` | `/adr <decision>`: record a decision in `doc/decisions/` | yes |

```
/smells <module>  →  /tidy <module>  →  PR to next
   what is there       one topic per      the frozen store
   (read-only)         commit             shrinks after merge
/verify before every "done"; /gap, /rule, /adr record what the read-through taught.
```

The rules `/smells` and `/tidy` apply are in `doc/agent/conventions.md`; the hard rules
every session needs are in `AGENTS.md`. A fuller harness (hooks, reviewer
subagents, a spec loop, PMD) is parked on the branch `chore/harness-skeleton`;
`doc/agent/README.md` says when a piece of it comes back.
