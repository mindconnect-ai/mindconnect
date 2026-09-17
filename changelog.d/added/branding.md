- **agents:** **the Admin UI can carry your own name, mark and stylesheet.**
  `mindconnect.branding.*` sets the heading in the header, the logo beside it
  and where it leads, the browser tab's title and icon, the look the shell
  opens in, and any number of stylesheets that are linked after the shipped
  ones so their rules win. `assets-dir` serves a directory next to the running
  app at `/branding/**`, so an installation brands itself by dropping files in
  rather than by building its own image; the login page follows the same
  settings. Unset, everything looks exactly as before — except the browser tab,
  which now says "Mindconnect Agent Runtime" instead of "Agent Admin".
  `switch` takes it one step further: named variants with a `url-pattern` each,
  so one deployment behind two host names wears two brands, decided per request
  rather than per process. `style-picker` says what the header's theme picker
  may offer — every shipped theme, a named few, or none at all, and with none
  the look is genuinely fixed. See
  [Branding the app](https://mindconnect-ai.github.io/mindconnect/docs/agents/admin-ui/#branding-the-app).
