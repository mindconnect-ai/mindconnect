- **workflow:** **moving to `mindconnect.persistence=postgres` keeps the workflows
  kept in files.** On Postgres, workflow definitions and suspended runs live in
  the `mc_workflow` and `mc_workflow_instance` tables — but what an installation
  had saved under `<data dir>/<namespace>/workflows` on file persistence stayed
  there, and the workflow list started out empty after the switch. The first
  time a namespace is used on Postgres, its definitions and runs are now
  imported from those files, when the tables have nothing for it yet. It
  happens once per namespace (recorded in `mc_workflow_import`), so a workflow
  or run deleted later does not come back on the next start; the files are
  left in place, and switching back to file persistence finds them as they were.
  The agent runtime, the admin UI and the standalone workflow admin all import.
