- **agents:** **asking for the background processes lists them instead of killing
  one.** Listing was hidden in `process_kill` called without a pid, so a model asked
  for the session's processes ran `ps`, or called `process_kill` with a pid and ended
  the server. A read-only `process_list` tool now lists them; `process_kill` says it
  is for ending one and still lists without a pid. The bundled `coding-assistant`
  and `default-chat` bind `process_list` for new installations; an existing agent
  keeps its stored bindings until you edit it, and its seed shows as differing. The
  `server` profile disables `process_list` along with `bash` and `process_kill`.
