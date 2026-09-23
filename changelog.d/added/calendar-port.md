- **agents:** **the calendar is a port too, and CalDAV comes with it.**
  `mc-calendar-core` has `CalendarStore` — the calendars, a period of
  appointments, reading one, creating, changing and deleting — and the
  `CalendarProvider` seam a kind of account plugs into. `mc-calendar-caldav`
  brings CalDAV, which nearly every mail provider and every self-hosted
  calendar speaks: a card with the CalDAV address, user and password, a Test
  button that signs in and says which calendars it found, and as much
  iCalendar as an appointment needs. `mc-agent-tools-calendar` brings
  `calendar_calendars`, `calendar_events`, `calendar_read`, `calendar_create`,
  `calendar_update` and `calendar_delete`, which name an account
  `provider.key` (`caldav.web`) or ask `all` — one set of tools whatever kind
  of calendar a user connected, and each change a tool of its own so a binding
  can make it ask for approval.
