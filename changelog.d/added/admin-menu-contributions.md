- **agents:** **a module can add its screen to the admin UI's sidebar.** A jar on
  the classpath that serves a page of its own had no way into the navigation —
  the menu was a fixed list, so the page was reachable by URL only. It now
  registers an `AdminMenuContribution` bean and says which entries to show,
  for an admin of the namespace and for a plain user of it separately, so a
  contribution never offers a viewer a route the server would refuse. The
  entries follow the shipped sections, before the Install group.
- **agents:** **a module on the classpath ships its own agents, skills and workflows.** The
  seeds were read from the app's own jar only: `initial-data/` in a second jar
  was never scanned, so a module that brings an agent had to install it by
  hand. The loaders — and the Migrations screen — now look through every jar
  on the classpath, so a module's `initial-data/agent-definitions/*.json`,
  `skills/*.md` and `workflows/*.json` are seeded and migrated exactly like the
  bundled ones.
