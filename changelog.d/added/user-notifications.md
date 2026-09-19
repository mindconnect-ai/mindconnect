- **agents:** **the Admin UI has a notification bell: what is waiting for you,
  not what the server is doing.** `Notification`, `NotificationRepository` (file
  and Postgres) and `NotificationService` keep a list per user, installation-wide
  beside their API tokens; the bell in the header carries the unread count and
  opens the panel, where each notice can carry a link to the place that clears
  it. A notice has a *key* — the condition it stands for — so a check that runs
  on every sign-in collapses onto the entry that is already there instead of
  piling up, raising nothing again for a condition the user dismissed, and a
  condition that goes away takes its notice with it without anybody tidying up.
  Anything can contribute one by implementing `SetupCheck` as a bean; the one
  shipped reports the tool variables a user still has to fill in. A host that
  assembles no `NotificationRepository` has no bell and is unaffected.
