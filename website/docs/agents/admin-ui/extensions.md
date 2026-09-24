---
title: Extensions
sidebar_position: 6
---

# Extensions

An **extension** is a jar on the classpath that brings something of its own —
tools, agents, a screen in the sidebar, a decorator around a core port — and
says so in a **manifest**: `META-INF/mindconnect/extension.json` inside the
jar. The host reads every manifest at start, before anything is loaded from
the classpath, and the **Extensions** section (Install → Extensions) shows
what it found.

Per namespace, an admin can switch an extension **off** and back **on**. A
switched-off extension keeps running in the process — a jar cannot be
unloaded — but for that namespace its tools leave every catalog and never
resolve, and the sidebar entries it declared are left out. Nothing an agent
definition or a tool setting says brings them back; the decision is about the
whole extension.

Only decisions are stored (`<data.base-dir>/<namespace>/system/extensions/` on
files, `mc_extension_activation` in Postgres). A namespace that never decided
has the extension as its manifest says (`enabledByDefault`, true unless the
manifest says otherwise); **Back to default** forgets the decision.

## Three levels

Who decides, in order of precedence:

1. **The operator** — `mindconnect.extensions.disabled=acme-crm,other` (a
   property, `MC_EXTENSIONS_DISABLED` in the `server` profile) switches an
   extension off everywhere. Nobody below can switch it on; the screen says
   so and offers no switch.
2. **The brand**, when its decision is **locked** — holds in every namespace
   of the brand, whatever the namespace said.
3. **The namespace** — its own decision, as above.
4. **The brand**, unlocked — what the brand's namespaces have unless they
   decided for themselves.
5. **The manifest** — `enabledByDefault`.

A brand's decisions are taken from the brand's own namespace (the one whose
id is the brand, `erni` on `erni.mindconnect.ai`): there the screen offers
**Brand …: switch off / on**, **lock / unlock** and **back to default** beside
the namespace's own switches. They apply to every namespace that belongs to
the brand — its own and the personal ones its people made (`erni_david`).
Stored installation-wide, keyed by brand (`system/extension-brands/<brand>/`
on files, `mc_extension_brand_activation` in Postgres); deleting a namespace
of the brand leaves them alone.

Each extension's **About** table says where its state comes from: decided
here, as decided for the brand (overridable), locked for the brand, switched
off by the operator, or the manifest's default.

## What the screen shows

- **Extensions** — one entry per manifest: name, version and vendor, and,
  unfolded, two tables. **About** is what the extension is and how it stands
  in this namespace; **Brings** is everything the manifest declares (tools,
  agents, skills, workflows, menu entries, routes, assets, REST paths,
  decorated ports, replaced beans, persistence). Beside each tool pattern and
  provider class stands the classpath's verdict — `acme_* ✓ 3 tools`,
  `com.acme.Tools ✗ not on the classpath` — and the heading warns when not
  everything declared was found. The heading also says `(off)` for an
  extension that is off here. **Switch off** / **Switch on** decide for this
  namespace.
- **Problems** — what did not fit: a manifest that could not be read, two
  manifests with one id (the first is kept), two extensions replacing the
  same bean, a required extension that is not installed, a `remote` manifest
  found on the classpath.
- **Without a manifest** — jars that bring tools or runtime features through
  `ServiceLoader` without a manifest to answer for them. They are loaded
  exactly as before, but nothing here can switch them off. The host's own
  modules (Maven group `ai.mindconnect`) are not listed.

With `mindconnect.extensions.strict=true` a problem or an unmanaged jar
refuses the start instead — for an installation that wants nothing on its
classpath it has not been told about. The default is `false` while not every
module has a manifest yet.

## The manifest

```json
{
  "id": "acme-crm",
  "name": "Acme CRM",
  "version": "1.4.0",
  "description": "Contacts and leads from Acme, as tools and a screen.",
  "vendor": { "id": "acme", "name": "Acme GmbH", "homepage": "https://acme.example" },
  "runtime": "jar",   // a "remote" manifest found on the classpath is reported and not wired
  "requires": { "mindconnect": ">=0.8", "extensions": [ { "id": "mc-mail", "optional": true } ] },
  "enabledByDefault": true,
  "permissions": [],
  "contributes": {
    "tools":   { "providers": [ "ai.acme.CrmToolProvider" ], "names": [ "acme_*" ] },
    "features": [ "ai.acme.CrmFeature" ],
    "content": { "agents": [ "acme-sales" ], "skills": [], "workflows": [] },
    "ui": {
      "menu":   [ { "id": "nav-acme", "label": "Acme", "href": "/admin/acme-crm", "icon": "briefcase", "group": "nav-group-tools" },
                  { "id": "nav-my-acme", "label": "My Acme", "href": "/admin/acme-crm/mine", "roles": [ "ADMIN", "USER" ] } ],
      "routes": [ { "path": "/admin/acme-crm/**", "roles": [ "ADMIN" ] } ],
      "assets": [ "acme.css" ]
    },
    "rest": [ "/admin/acme-crm/**" ],
    "decorates": [ "LlmCallTraceRepository" ],
    "replaces": [],
    "persistence": { "schema": "ext_acme_crm" }
  }
}
```

| Field | Meaning |
|-------|---------|
| `id` | Lowercase letters, digits and hyphens, 2–64 characters, starting with a letter or digit. Global — use a vendor prefix. |
| `name`, `version`, `description`, `vendor` | What the screen prints. `name` falls back to the id, `version` to `0`. |
| `runtime` | `jar` (the default) — code on the classpath, loaded with the process. `remote` is reserved for extensions the host talks to over a protocol; a `remote` manifest on the classpath is a problem. |
| `requires.mindconnect` | The host version the extension was written for, as a range. Shown, not yet enforced. |
| `requires.extensions[]` | Other extensions this one builds on; a missing one is a problem unless `optional`. |
| `enabledByDefault` | Whether a namespace that never decided has the extension on. Default `true`. |
| `permissions[]` | What the extension asks for — shown to the admin; the host enforces nothing from it yet. |
| `contributes.tools.providers[]` | The `ToolFactory` / `MultiToolProvider` classes the classpath finds. A provider named here is managed wherever its jar is. |
| `contributes.tools.names[]` | The tool names the extension carries, as patterns (`acme_*`, `crm_export`). **This is what a namespace that switched the extension off stops seeing.** |
| `contributes.features[]` | `RuntimeFeature` classes, like the providers above. |
| `contributes.content` | Agents, skills and workflows the jar seeds (`initial-data/**`), by name. |
| `contributes.ui.menu[]` | Sidebar entries. With `label` and `href` the host renders the entry itself — into the shipped group `group` names (`nav-group-ai`, `nav-group-tools`, `nav-group-data`), or into a new group called `groupLabel` with the icon `groupIcon` (the first entry that names one gives the group its icon; a shipped group keeps its own); for admins of the namespace, and for its plain users too when `roles` names `USER`. An entry with only an `id` declares one the jar's own `AdminMenuContribution` registers; an id both declare is the bean's. **Either way an entry whose id a switched-off extension declares is left out of the menu** for that namespace. |
| `contributes.ui.routes[]` | The screens and the REST API the extension serves, as prefix patterns under one of the extension's own homes (see [Route homes](#route-homes)): `/admin/<id>/**`, `/ext/<id>/**` or `/api/<id>/**` — a route anywhere else is reported and ignored, so no manifest can claim a shipped screen or endpoint. The host answers **404** on every one of them where the extension is off. Admin-only unless `roles` names `USER`; then a plain user of the namespace may open the route, and menu entries with that role are shown to them. |
| `contributes.ui.assets[]`, `rest[]`, `decorates[]`, `replaces[]`, `persistence` | Declared and shown; the steps that enforce them follow. `replaces` is checked already: two extensions replacing one seam is a problem. |

Fields the host does not know are ignored, so a manifest written for a newer
host still loads on an older one.

The manifest is the allow-list of what a jar brings. Today the host still
loads what `ServiceLoader` finds whether a manifest names it or not — the
audit only reports it — so a module from before manifests existed keeps
working; give it a manifest and it appears here with a switch.

### Route homes

An extension owns three places, each named after its id:

| Home | For | What the host does there |
|---|---|---|
| `/admin/<id>/` | screens in the admin area | A section of the SPA: a browser navigation gets the shell, the shell fetches the page, and a `UiPage` answered there is wrapped in the admin layout whatever package served it. |
| `/ext/<id>/` | screens outside the admin area | The same as `/admin/<id>/`. |
| `/api/<id>/` | the extension's REST API | JSON for scripts. It sits under `/api/**`, so the bearer-token chain covers it and a script calls it with an API token (`Authorization: Bearer mct_…`), just like the shipped API. A browser there gets the JSON, never the shell, and nothing answered there is wrapped in the layout. |

Roles and the on/off switch work the same in all three: a route is an
admin's unless `roles` names `USER`, the most specific route covering a path
decides, and a namespace that switched the extension off gets **404** there.
So a manifest can open part of its API to the namespace's plain users and keep
the rest for admins:

```json
"routes": [
  { "path": "/admin/acme-crm/**",        "roles": [ "ADMIN" ] },
  { "path": "/api/acme-crm/contacts/**", "roles": [ "ADMIN", "USER" ] },
  { "path": "/api/acme-crm/admin/**",    "roles": [ "ADMIN" ] }
]
```

A route outside these homes (`/api/contacts/**`, `/chat/**`) is reported on
the Extensions page and ignored: it gates nothing, and the 404 does not reach
it. The shipped REST API stays closed to plain users, so an endpoint an
extension put there would be for admins only.

## A worked example

`agents/demo/mc-extension-demo` is a complete extension in one small jar — a
short fantasy adventure with the LLM as game master:

- the manifest, id `demo-dungeon`;
- a tool, `demo_dice` (registered through `META-INF/services`, named in the
  manifest), which the game master rolls for monsters and traps;
- an agent, `dungeon-master`, seeded from the jar's
  `initial-data/agent-definitions/`: it tells the story, offers choices and
  asks the player to roll;
- a runtime feature, `DemoFeature` (a bean of the jar's auto-configuration,
  and in `META-INF/services` for library hosts), which decorates the core's
  `LlmCallTraceRepository` to count LLM calls — `contributes.decorates` —
  and keeps a store of its own for every die rolled, per namespace, on files
  under `<namespace>/ext/demo-dungeon/` or in the Postgres schema
  `ext_demo_dungeon` — `contributes.persistence`;
- two screens, served by a controller the jar's auto-configuration
  registers: **Dungeon demo** (AI group) where you start an adventure, type
  what you do and roll a d20 or d6 with a button — the player's dice are
  rolled by the same tool, so they land in the store too — and **Dice
  statistics** (Data group) with a chart of how often each face came up per
  day and the day's most frequent face. The chart is the `chart` node of
  `mc-semantic-ui-ext-chart`, whose renderer reaches the page through the
  asset registry.

The admin UI app ships it **switched off** (`enabledByDefault: false`): it
is listed under Install → Extensions, and an admin switches it on for the
namespace at hand — then the menu entries, the tools and the screens appear;
off again, and they go (the screens answer 404). Any other host puts the jar
on its classpath the same way:

```bash
java -cp mc-agent-admin-ui-app-*-exec.jar \
     -Dloader.path=mc-extension-demo-*.jar \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

Related: [Initial data](../initial-data.md) for what a jar seeds,
[Environment variables](../environment-variables.md) for `mindconnect.extensions.strict`.
