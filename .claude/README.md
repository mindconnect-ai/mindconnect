# .claude

The Claude Code harness of this repository, deliberately small. It is
committed so that every clone and every contributor runs the same skills.

| Path | What | Committed |
| --- | --- | --- |
| `settings.json` | shared permissions | yes |
| `settings.local.json` | personal overrides | no |
| `skills/verify/` | `/verify [module...]`: tests of the touched modules, then the area's ArchUnit rules | yes |
| `skills/tidy-inspect/` | `/tidy-inspect <module>`: read-only check of a module against the conventions, as a table | yes |
| `skills/tidy/` | `/tidy <module> <topic>`: behaviour-preserving cleanup of one module or package, one topic, nothing committed | yes |
| `skills/gap/` | `/gap <module> <finding>`: record a finding in `doc/known-gaps.md` | yes |
| `skills/rule/` | `/rule <criterion>`: add or sharpen a convention, as an ArchUnit rule when a tool can decide it | yes |
| `skills/adr/` | `/adr <decision>`: record a decision in `doc/decisions/` | yes |

```
/tidy-inspect <module>  →  /tidy <module> <topic>  →  commit, PR to next
   what is there          one topic, in the        the frozen store
   (read-only)            working tree             shrinks after merge
/verify before every "done"; /gap, /rule, /adr record what the read-through taught.
```

The rules `/tidy-inspect` and `/tidy` apply are in `doc/agent/conventions.md`; the hard rules
every session needs are in `AGENTS.md`. A fuller harness (hooks, reviewer
subagents, a spec loop, PMD) is parked on the branch `chore/harness-skeleton`;
`doc/agent/README.md` says when a piece of it comes back.
