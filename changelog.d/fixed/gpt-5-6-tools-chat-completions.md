- **agents:** **gpt-5.6 with tools works on `OPENAI_CHAT_COMPLETIONS`.** From gpt-5.6
  on, OpenAI refuses function tools together with reasoning on Chat Completions,
  and those models reason by default — every turn of an agent with tools ended in a
  "Streaming error" (HTTP 400). A config that stays on Chat Completions now sends
  `reasoning_effort: none` to these models when tools are offered; a configured
  effort still applies to turns without tools. For reasoning and tools together,
  use provider `OPENAI` (the Responses API).
