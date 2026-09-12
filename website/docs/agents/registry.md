---
title: Registry
sidebar_position: 9
---

# Registry

A **registry** is a GitHub project holding an index of things you can import
into a Mindconnect installation: LLM configs, agents, workflows, and packages
that bundle all of them.

There is no registry server. A registry is a repository — fork it, send a pull
request, pin a tag — and everything a hosted catalogue would need (review,
history, ownership) is what Git already does. Reading one is a plain HTTPS GET
of raw files, so a private registry needs nothing but a token.

## Using one

`Registry` in the admin UI lists the registries this installation knows. Add one
by its `owner/repo`:

| Typed | Reads |
|-------|-------|
| `acme/agent-registry` | `main`, `registry.json` |
| `acme/agent-registry@v1.2.0` | the tag `v1.2.0` |
| `acme/agent-registry@main:catalog/index.json` | another index path |
| `https://github.com/acme/agent-registry` | same as the first row |

Pin a tag for a registry you do not control: a branch is whatever its owner
pushed last.

Open a registry to see what it offers — filed under *Agents*, *LLM configs*,
*Workflows* and *Packages* — search or filter by kind, and import an entry. Each
row says what this installation would do with it — *already here*, or a kind this
installation cannot install at all — before you press anything; its button reads
**Import** for something new and **Overwrite** for something already here.

A package opens on two tabs: its details, and *Contents* — everything importing
it would install, the entries its members require included, each marked *New*
or *Already here*. Every entry has an **Include** box, ticked by default; untick
one to leave it out. A left-out entry is not installed at all, not even as what
another entry requires, and the report lists it as skipped.

**Remove** takes a package out again: it deletes the included entries that are
here — workflows first, then agents, then the LLM configs they run on — and
leaves the unticked ones in place.

An entry that something outside the package still uses says so — *Used by Agent
'default-chat', Agent 'planner' and 13 more* — and starts unticked, so that
removing a package does not take the model away from every other agent. The
check follows the chain: when a shared alias stays, the config it delegates to
stays too. What counts as a use: an agent's LLM config, the agents it calls and
its reviewers, an alias's target, and a workflow's agent calls, inline agents
and called workflows. A reference kept anywhere else — a tool setting, an
application property naming a default agent — is not seen, so read the list
before you press Remove.

Importing has two modes:

- **Import** keeps what is already here under the same name, and installs the
  rest. The default; it cannot destroy anything.
- **Import and overwrite** replaces entities of the same name, keeping their
  local ids — so the agents, aliases and sessions pointing at them keep working.

Nothing is rolled back if one member of a package fails: the others are
installed, and the report says line by line what happened.

## What a registry repository looks like

```
registry.json               ← the index
llm-configs/
  default.json
agents/
  web-researcher.json
  research-lead.json
workflows/
  summarize.json
packages/
  research-kit.json         ← a package manifest
```

### `registry.json`

```json
{
  "schemaVersion": 1,
  "name": "Acme agent registry",
  "description": "Agents and workflows we use in-house",
  "entries": [
    {
      "id": "default-llm",
      "type": "llm-config",
      "name": "default",
      "description": "Claude Sonnet, our house default",
      "version": "1.0.0",
      "path": "llm-configs/default.json"
    },
    {
      "id": "web-researcher",
      "type": "agent",
      "name": "web-researcher",
      "description": "Searches the web and reports with citations",
      "version": "1.2.0",
      "path": "agents/web-researcher.json",
      "tags": ["research"],
      "author": "acme",
      "homepage": "https://github.com/acme/agent-registry",
      "requires": ["default-llm"]
    },
    {
      "id": "research-kit",
      "type": "package",
      "name": "Research kit",
      "description": "A research lead, its sub-agents and the model they share",
      "path": "packages/research-kit.json"
    }
  ]
}
```

| Field | Meaning |
|-------|---------|
| `id` | the entry's address inside this registry — what `requires` and packages refer to |
| `type` | `llm-config`, `agent`, `workflow` or `package` |
| `name` | the name the entity is installed under; the screen warns when it is taken |
| `path` | where the entity's file sits in the repository |
| `version`, `author`, `homepage`, `tags`, `description` | for the person deciding |
| `requires` | ids of entries installed **first**, in order |

Unknown fields are ignored, so an index written against a later schema still
reads.

### Entity files

An entity file is exactly what the installation stores — the same JSON the admin
UI shows under *Agents* and *LLM configs*, and the same file the workflow admin
writes. Two fields are taken away on the way in:

- **Ids.** A new entity gets a fresh local id; re-importing over an existing one
  keeps the local id, so nothing pointing at it breaks.
- **A literal API key in an LLM config.** A registry config is meant to carry
  `${ANTHROPIC_API_KEY}`, which resolves against *this* installation's
  environment at call time. A key that is not a placeholder is somebody else's
  credential — it is dropped, and the import report says so.

### Packages

A package manifest names everything that has to arrive together. A useful agent
is rarely one file: it points at an LLM config by name, calls two sub-agents, and
one of those runs a workflow.

```json
{
  "name": "Research kit",
  "description": "A research lead, its sub-agents and the model they share",
  "version": "1.0.0",
  "includes": ["default-llm", "web-researcher", "verifier"],
  "items": [
    {
      "id": "research-lead",
      "type": "agent",
      "name": "research-lead",
      "path": "agents/research-lead.json"
    }
  ]
}
```

`includes` are ids from the same index — reuse, so the same agent can be a member
of three packages and exist once. `items` are entries written into the manifest
itself, for parts that are only ever this package's. They install in that order,
each with its own `requires`, and an entry reached twice is installed once.

## Private registries

Set **Token variable** on the registry to the *name* of an environment variable
holding a GitHub token — the name, never the token. The installation reads it at
request time, so the store that holds the registry can never hold a secret.

```bash
export ACME_REGISTRY_TOKEN=ghp_…
```

## Configuration

| Property | Default | What it does |
|----------|---------|--------------|
| `mindconnect.registry.enabled` | `true` | `false` takes the whole feature away, screen included |
| `mindconnect.registry.default-source` | — | `owner/repo[@ref][:index-path]`, added on first start when no registry is configured yet |
| `mindconnect.registry.cache-ttl` | `PT10M` | how long a fetched index or file is reused; the screen's *Refresh* drops it |
| `mindconnect.registry.timeout` | `PT20S` | per-request HTTP timeout |

Registries are stored per namespace under
`<data-base-dir>/<namespace>/system/registries/<id>.json`, so an installation can
ship one the way it ships a seed agent: drop the file in.

## What importing means

A registry is somebody else's repository. Nothing runs by being imported, but
everything imported is here afterwards:

- **An agent** is a system prompt and a tool list somebody else wrote. Imported,
  it runs on your models with your tools. Read its prompt before you let it run.
- **A workflow** is executable — its steps call tools, agents and scripts.
- **An LLM config** names a provider and a base URL: the address this
  installation would send prompts to.

The import screen says this above the buttons, once, before the click.

## Modules

| Module | Purpose |
|--------|---------|
| `mc-agent-registry-core` | What a registry is: domain, the three ports, the import service that walks packages and dependencies |
| `mc-agent-registry` | The GitHub client, the file-backed store of registries, the installers for LLM configs and agents |
| `mc-agent-registry-admin-ui-rest` | The `/registry` screen |

Installers are contributed by the module that owns the entity, so an installation
that has no workflow engine simply has no workflow installer — its registry then
lists workflows it cannot import, and says so, instead of dragging the engine in
as a dependency. The workflow installer lives in `mc-agent-tools-workflow`.
