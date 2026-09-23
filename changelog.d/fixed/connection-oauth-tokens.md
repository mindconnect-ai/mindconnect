- **agents:** **editing a signed-in connection no longer signs you out.** Saving
  the edit dialog of a connection made through "Connect with ..." threw away
  its OAuth token and the app registration it came from, so every tool using
  it failed until it was connected again. The dialog now offers the name
  only, and the store keeps the token and its settings whatever an edit says.
- **agents:** **two tool calls no longer expire a connection by refreshing it
  at once.** When parallel calls found the same token about to run out, both
  refreshed it; with a provider that rotates refresh tokens the second was
  refused and the connection marked expired. A refresh now runs once per
  connection and the waiting call uses the new token. A refresh also no
  longer writes over a rename that landed while it was running.
