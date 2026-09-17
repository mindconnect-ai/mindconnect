- **agents:** **a Chat Completions config no longer fails a turn when OpenAI refuses `reasoning_effort`.**
  The gateway only leaves the field out, or sends `none`, for the models it knows OpenAI refuses it
  on; gpt-5.4-mini together with function tools was not among them and answered HTTP 400. On such a
  400 the gateway now logs a warning and sends the same request once more without the field.
  Configs on the provider `OPENAI` (Responses API) were not affected.
