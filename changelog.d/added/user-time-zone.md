- **agents:** **every user has a time zone, and the agents' tools and prompt
  use it instead of the server's.** On a server running in UTC, a train at
  16:16 in Zurich that an agent put into the calendar as `2026-09-25T16:16`
  was stored as 16:16 UTC and showed up at 18:16. Now a time without an
  offset — in `calendar_create`, `calendar_update`, `calendar_events`,
  `mail_list` — is read in the calling user's zone, asked on every call, and
  the times the tools show, `get_current_datetime` and the prompt's
  `current_date`/`current_time` are in it too. The system prompt ends with a
  `## Date and time` section — *It is Thursday, 24 September 2026, 17:36
  (Europe/Zurich, UTC+02:00)* — that says so. A user's zone is what their
  browser reports the first time it is seen (stored once, never over a
  choice), changeable under **Account → Time zone** on the profile page;
  until then `mindconnect.time-zone` (`MC_TIME_ZONE`) applies, else the JVM's
  zone. Tools reach it through the new `TimeZones` port in
  `mc-agent-tool-spi` (`TimeZones.of(env).zoneOf(scope.userId())`); an
  embedding sets one with `AgentRuntimeBuilder.timeZones(…)`. The container's
  `TZ` no longer has to be set to the users' zone.
