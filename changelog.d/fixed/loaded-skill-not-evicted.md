- **agents:** **a loaded skill is no longer evicted from the context.** With
  `toolResultEviction` on, a skill's text was replaced by a stub after the user's
  next message, and the model reloaded it with `fetch_tool_result` straight away —
  paying for the skill twice.
