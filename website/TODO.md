# Website TODOs

## Versioned docs (Docusaurus versioning)

Not enabled yet — deliberately. Turn it on with the first release users
actually pin (e.g. the first Maven Central release); until then a single
current doc set avoids double maintenance.

### How it works

Docusaurus versioning is snapshot-based:

- `website/docs/` is always the **current dev version** ("Next").
- At release time, snapshot it:

  ```bash
  cd website && npm run docusaurus docs:version <version>   # e.g. 0.1.0
  ```

  This copies `docs/` → `versioned_docs/version-<version>/`, the sidebar →
  `versioned_sidebars/version-<version>-sidebars.json`, and appends the
  version to `versions.json`.

- URLs after that: the **latest snapshot** serves under the normal paths
  (`/agents/overview`), older versions under `/<version>/agents/overview`,
  the working state under `/next/…`. Old versions automatically get a
  "no longer maintained" banner, next an "unreleased" banner.

### Config changes needed when enabling

1. Version dropdown in the navbar (`docusaurus.config.js`):

   ```js
   navbar: { items: [ { type: 'docsVersionDropdown', position: 'right' }, /* … */ ] }
   ```

2. Optional fine-tuning in the docs plugin options: `lastVersion`,
   per-version `label`/`path`, and `onlyIncludeVersions` to limit how many
   versions get built.

3. Automation: add a step to `.github/workflows/release.yml` after the
   version bump —

   ```bash
   cd website && npm run docusaurus docs:version $RELEASE
   git add website && git commit -m "docs: snapshot $RELEASE"
   ```

   `docs.yml` then deploys the site as usual.

### Costs to keep in mind

- Every version is a **full markdown copy** — repo size and build time grow
  linearly. Keep only the last 2–3 versions (`onlyIncludeVersions`, or delete
  old `versioned_docs/` folders).
- Fixes to released docs have to be applied **twice**: in `docs/` (next) and
  in the affected snapshot.
- Don't snapshot every patch release — near-identical copies pile up fast.
  Snapshot on meaningful (minor) releases only.
