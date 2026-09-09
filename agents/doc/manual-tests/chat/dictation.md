---
id: chat-dictation
area: chat
requires: [server-9091, openai-key, microphone]
duration: ~4 min
last-verified: never (the browser path was verified with a stubbed recorder on 2026-09-09; a real microphone has not been through it)
---

# Speaking into the chat instead of typing

**Goal:** The composer's microphone records, the recording becomes text in the
input, and nothing is sent until the person who spoke presses Send.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- An LLM config named `speech-to-text` — the seeded one, or an alias of that
  name pointing at a speech-to-text config. Its key must work; test it once on
  its own detail page (otherwise: SKIPPED)
- A working microphone, and the page opened over `http://localhost` or HTTPS.
  Over plain HTTP to a host name the browser hands out no microphone: that is
  the browser's rule, and this case is then SKIPPED.

## Setup

1. Open http://localhost:9091/chat and start a session with any agent.

## Steps

1. Look at the composer.
   **Expected:** Between the **+** and the model button there is a microphone.
2. Type `Frage:` into the input, then click the microphone and allow the
   microphone if the browser asks.
   **Expected:** The microphone turns red and the input's placeholder counts
   seconds — *Recording… 1 s*, *2 s*, … The typed text stays.
3. Say one sentence, then click the microphone again.
   **Expected:** Within a few seconds the input reads `Frage:` followed by a
   space and what was said. The placeholder is back to *Ask anything …*, the
   microphone is no longer red. Nothing has been sent.
4. Press Send.
   **Expected:** The message is sent as it stands in the input — the agent
   answers the dictated sentence.
5. Turn `speech-to-text` into an alias: open it in the admin UI, tick **Is
   Alias**, set *Delegates To* to another speech-to-text config, save. Dictate
   again in the chat.
   **Expected:** Dictation still works, now through the config behind the
   alias. Undo the change afterwards.

## Cleanup

- Delete the test session from the chat's session list.
- Undo the alias from step 5 if it was left in place.

## Notes

- The transcript is what the model heard; judge by "the words match what was
  said", not by an exact string.
- Without a `speech-to-text` config the microphone answers with a toast naming
  the config to create — that is the error path working, not a FAIL.
- Recording holds the microphone until the second click. If a step fails
  mid-recording, reload the page to release it.
