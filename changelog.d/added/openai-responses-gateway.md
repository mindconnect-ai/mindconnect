- **agents:** **OpenAI goes through the Responses API.** Configs with provider `OPENAI`
  now talk to `/v1/responses` instead of `/v1/chat/completions` — the only endpoint
  where gpt-5.6 and later take tools and reasoning together. Reasoning survives tool
  rounds (replayed as encrypted items, nothing stored at OpenAI), and its summary
  shows up in the chat as the thought. New `additionalParams.reasoning_summary`
  (`auto` default, `concise`, `detailed`, `none`) — set `none` if OpenAI refuses
  summaries to your organization. Other OpenAI-compatible providers and Azure are
  unchanged. A config that has to stay on Chat Completions picks the new provider
  `OPENAI_CHAT_COMPLETIONS`.
