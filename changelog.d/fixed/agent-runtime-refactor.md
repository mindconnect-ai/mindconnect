- **agents:** A `bash` command that is stopped — by its timeout, by Stop in
  the chat or by `process_kill` — no longer goes on with the next command of
  its script. The shell was killed after its children, saw the running
  command die and could start the one after it, which then ran on unwatched.
