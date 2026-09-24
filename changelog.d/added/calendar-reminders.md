- **agents:** **the calendar tools set reminders.** `calendar_create` and
  `calendar_update` take `reminders`, minutes before the start (`[10, 60]`):
  left out, a new entry gets the calendar's default and a changed one keeps
  its own; `[]` means none. `calendar_read` and `calendar_events` show an
  entry's reminders. CalDAV writes each as a `VALARM` and reads them back; an
  update that names reminders replaces the appointment's alarms. What a
  calendar can keep is the store's new `CalendarStore.reminders()`
  (`ReminderSupport`): too many or too far ahead is refused before anything is
  written, extras a one-reminder calendar cannot keep are dropped and named in
  the result, and a store that takes none — the default for a store that does
  not override it — gets its entry without them and a result that says so.
  `EventDraft` and `CalendarEvent` carry the new `reminders` component; their
  old constructors remain. The CalDAV reader also no longer takes an alarm's
  `DESCRIPTION`, `ATTENDEE` or `DURATION` for the appointment's own notes,
  attendees or length.
