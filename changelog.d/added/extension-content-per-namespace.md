- **agents:** **every namespace gets the bundled agents, skills, workflows and
  LLM configs, and an extension's content wherever the extension is on — not
  only the start-up namespace.** Until now `initial-data/**` was imported into
  `mindconnect.namespace` (`local`) alone, so a namespace like a brand's or a
  personal one never saw, say, the agents an extension added later, unless an
  admin applied each one on Install → Migrations. Now a namespace gets them
  the first time it is used after a start (the first request, from an admin
  or a plain user, or the first task there). Only what the namespace never
  had is installed: a record that is there is never overwritten (a differing
  one stays a CHANGED migration), and one an admin deleted does not come back
  — each namespace remembers what it got
  (`<namespace>/system/installed-seeds.json`, table `mc_installed_seed`), and
  Migrations lists it as NEW to bring it back, skills included now. What an
  extension's manifest names under `contributes.content` is installed only
  where the extension is on; switching it on installs it right away. The
  first start records what each namespace has; a bundled record a namespace
  lacks at that moment is installed once. The demo's `dungeon-master` is no
  longer put into `local` while the demo is off.
