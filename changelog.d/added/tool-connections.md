- **agents:** **a user attaches their own account to a tool, and may attach
  several.** What a tool can do is the installation's business; whose mailbox
  it does it with is the user's. A tool source declares a `ConnectionSpec` —
  a provider key, a title and a `Schema` for the form — and implements
  `ConnectedTool`; the registry then hands each call the account of whoever it
  runs for. `Connection` / `ConnectionRepository` (file, Postgres, in-memory)
  / `ConnectionService` keep them per user and installation-wide beside the
  API tokens, with the schema deciding what is a secret: a `Format.PASSWORD`
  field is encrypted at rest and never shown again, everything else stays
  readable so a typo can be corrected. The profile has a **Connections** tab
  rendered entirely from the schema — no tool ships a screen — and a missing
  connection becomes a notification on the next sign-in. A user with several
  accounts gets an `account` parameter whose values are exactly their own
  connection keys; with one, nothing appears at all. An agent can pin it
  (`overrides: {"params": {"account": "arbeit"}}`) and so carry the same tool
  twice under two names. Nothing changes for a tool that declares no
  connection.
