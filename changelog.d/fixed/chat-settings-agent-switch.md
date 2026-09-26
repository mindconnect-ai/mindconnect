- **agents:** **switching a chat's agent shows that agent's prompt and model
  at once.** In the chat's "Agent, model & prompt" dialog the prompt and model
  fields kept the previous agent's values after another agent was picked —
  Apply then used the new agent's prompt anyway, so the dialog showed a prompt
  that was not the one in force, and an edit made in the same step was
  dropped. Picking an agent now redraws both fields with what Apply will use:
  a different agent's own prompt and model, the chat's current settings for
  its own agent, and the fields as they are for "no agent".
