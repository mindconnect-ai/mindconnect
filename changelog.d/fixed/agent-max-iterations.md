- **agents:** **an agent's Max Iterations is now the round cap of a turn, and hitting it no longer ends the turn in silence.**
  The loop ran on a fixed cap of 10 tool-call rounds whatever the agent's
  `maxIterations` said, so an agent that lists, saves, runs and fixes in one go
  — the workflow builder, deep research — was cut off after nine rounds. At the
  cap the calls the model had just asked for stayed open in the history, a
  request every provider rejects, so the forced final answer failed and the
  chat simply stopped. The cap now comes from the agent definition (10 when
  it has none), refused calls are closed with an error result that names the
  cap, and a note in the chat says the cap was hit before the agent answers
  without tools.
