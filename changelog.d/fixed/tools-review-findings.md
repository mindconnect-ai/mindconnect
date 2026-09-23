- **agents:** **a file tool no longer tells the model a file is missing when the
  workspace did not answer.** On a virtual environment workspace a refused or
  timed-out request (401, 403, 5xx) read as "file does not exist — use
  file_write", and the model overwrote the real file. `file_edit`, `file_read`,
  `file_list`, `grep`, `glob` and the document tools now say they could not
  check. For implementers: `WorkspaceFiles.exists`, `isDirectory` and
  `isRegularFile` now throw the `IOException` instead of answering `false`.
- **agents:** **`code_execute` on a virtual environment finds the session's
  files by the path the prompt names.** The session directory was mapped to
  `/workspace` in the command but not in the program sent on stdin, so a script
  naming it failed in the container.
- **agents:** **the chat escapes a tag after a code span that runs over a line
  break.** Backticks were paired line by line while the renderer lets a code
  span continue on the next line, so a tag the renderer showed as HTML could
  pass unescaped. Backticks now pair across the paragraph, and table rows are
  read cell by cell.
- **agents:** **a skill imported from a registry is found, and removed, by the
  entry's name.** It was stored under the name in its `SKILL.md` front matter
  while the registry screen and package removal looked it up by the entry's
  name; where the two differed the skill showed as not installed and could not
  be removed. It is now stored under the entry's name, and the import report
  mentions a front matter that says otherwise.
- **agents:** **the dungeon demo only forgets the player's own adventures.**
  `POST /forget/{adventureId}` deleted any adventure by id.
