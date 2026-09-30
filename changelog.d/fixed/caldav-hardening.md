- **agents:** **changing a CalDAV appointment no longer turns a series into a
  single appointment or misses files named by other clients.** A change
  rewrote the whole file from title, time, place, notes and attendees, which
  dropped the recurrence rule, exception dates, alarms and the attendees'
  answers; it now patches the file the server holds and keeps everything it
  does not change (moving a recurring series is refused rather than done
  wrong). Appointments whose file is not `<UID>.ics` — as Apple, Outlook and
  DAVx5 write them — are found by their UID, and changes and deletes are sent
  with `If-Match`, so an edit made elsewhere in between is reported instead of
  overwritten. Listing a period now shows a recurring appointment on the days
  it occurs rather than on the day the series began, and an appointment that
  gives a `DURATION` instead of an end is read with its real length.
