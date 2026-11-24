- **agents:** **spoken conversations with an agent over a WebSocket.** The new
  module `mc-agent-voice-ws` adds `/api/voice` to a server that depends on it
  (the admin UI app does): the client opens a conversation with `start`
  (agent or session to continue, language, audio format, voice), sends an
  utterance as binary frames closed by `audio.end` or types `text`, and gets
  the transcript, the agent's answer as `agent.token` frames and the answer
  spoken back sentence by sentence (`speech` plus one binary frame each);
  `cancel` stops the turn when the caller interrupts. The handshake is
  authenticated like any `/api` call and takes `X-Mindconnect-Namespace`.
  Spoken output comes from the new LLM config type `TEXT_TO_SPEECH`, reached
  through `LlmSpeech` in `mc-llm-audio-core` with an adapter for the
  OpenAI-compatible speech endpoint in `mc-llm-audio`; the admin UI offers
  the type in the LLM config form. Settings live under `mindconnect.voice.*`
  (`speech-to-text` and `text-to-speech` name the configs to use).
