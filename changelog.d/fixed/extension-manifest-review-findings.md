- **agents:** **an extension's manifest can no longer claim a shipped screen,
  and a switched-off extension's tools stay hidden from `tool_search` too.**
  A route must lie under `/admin/<id>` or `/ext/<id>`; one anywhere else is
  reported on the Extensions page and ignored. `tool_search` now looks through
  the same decorated registry the chat uses, so it no longer finds the tools
  of an extension that is off in the namespace. Remote manifests found on the
  classpath are reported instead of wired, and `MC_EXTENSIONS_DISABLED` /
  `MC_EXTENSIONS_STRICT` are bound in the server profile.
