# Example registry

A registry is a GitHub project: an index (`registry.json`) and the entity files
it points at. This directory is a working one, small enough to read in a minute.

```
registry.json                     the index — everything on offer
llm-configs/example-default.json  an LLM config, keyed from ${ANTHROPIC_API_KEY}
agents/changelog-writer.json      an agent, requiring the config above
workflows/greeting.json           a workflow, exactly as the store writes it
packages/release-kit.json         a package: all three, installed together
```

## Trying it

1. Push this directory to the root of a repository of your own.
2. In the admin UI, open **Registry → Add registry** and type `owner/repo`.
3. Open it, and import `Release kit`.

Three entities arrive: the LLM config first (the agent requires it), then the
agent and the workflow. Nothing runs; the changelog writer is in your agent list
and the workflow in the workflow admin.

The format is documented at
[website/docs/agents/registry.md](../../../website/docs/agents/registry.md).
