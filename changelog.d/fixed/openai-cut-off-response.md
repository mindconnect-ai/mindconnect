- **agents:** **a cut-off OpenAI response is no longer taken for a finished one.** A
  Responses stream that ends without its completed event (a proxy closing the
  connection) fails the call instead of storing half an answer as final; an answer cut
  off at `max_output_tokens` in the middle of a tool call reports `LENGTH` instead of
  running the call with broken arguments; and GPT models after 5 are treated as
  reasoning models.
