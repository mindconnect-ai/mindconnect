- **agents:** **OpenAI reasoning models work for organizations without reasoning
  summaries.** The Responses gateway asks for a summary by default, and OpenAI refuses
  one to an organization that is not verified — every call failed with HTTP 400 until
  `reasoning_summary=none` was set. The call is now repeated without the summary, and
  the gateway remembers that for the endpoint. A summary a config asks for explicitly
  still fails loudly.
