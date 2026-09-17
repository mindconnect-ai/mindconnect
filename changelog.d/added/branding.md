- **agents:** **the Admin UI can carry your own name, mark and stylesheet.**
  `mindconnect.branding.*` sets the heading in the header, the logo beside it
  and where it leads, the browser tab's title and icon, the look the shell
  opens in, and any number of stylesheets that are linked after the shipped
  ones so their rules win. `assets-dir` serves a directory next to the running
  app at `/branding/**`, so an installation brands itself by dropping files in
  rather than by building its own image; the login page follows the same
  settings. Unset, everything looks exactly as before — except the browser tab,
  which now says "Mindconnect Agent Runtime" instead of "Agent Admin". See
  [Branding the app](https://mindconnect-ai.github.io/mindconnect/docs/agents/admin-ui/#branding-the-app).
