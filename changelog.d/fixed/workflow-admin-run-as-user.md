- **workflows:** **a workflow run from the admin screens or the REST API acts for the
  signed-in user.** Its tool-call steps ran as the fixed `workflow` user, so a
  step reaching for mail or calendars — `mail_list` in a morning briefing —
  failed with "No account of this kind is connected" although the user had
  theirs connected. A run now carries the request's user as its
  `ToolCallScope` — streamed or not, started or resumed — and a request
  without a user still runs on nobody's behalf.
