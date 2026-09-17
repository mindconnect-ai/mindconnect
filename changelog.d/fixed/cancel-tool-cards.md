- **agents:** **a stopped turn no longer leaves its tool spinning in the chat.** After
  **Stop** — or a turn that failed — the card of the tool or sub-agent that was running
  kept showing "running…" until the page was reloaded, and after the reload it was
  gone: the calls of a turn without an answer were not drawn once another message
  followed. The card now turns failed the moment the turn ends, and the history shows
  it, failed, above the next question.
