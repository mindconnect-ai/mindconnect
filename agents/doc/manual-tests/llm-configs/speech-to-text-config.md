---
id: llm-configs-speech-to-text
area: llm-configs
requires: [server-9091, openai-key]
duration: ~4 min
last-verified: 2026-09-11 (working tree on a2c12b5, branch chore/typed-ids, runs/2026-09-11-typed-ids — OpenAI via agent-default)
---

# A speech-to-text config transcribes a recording

**Goal:** A config of type `SPEECH_TO_TEXT` reaches the transcription
endpoint, and the admin UI's Test dialog turns an uploaded recording into
text.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `OPENAI_API_KEY` set in the environment the server was started with
  (otherwise: SKIPPED — or point Base URL at a local Whisper server and
  leave the key empty)
- A short audio file with speech. On macOS one can be made in two commands:

  ```bash
  say -o /tmp/speech.aiff "The quick brown fox jumps over the lazy dog."
  afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/speech.aiff /tmp/speech.wav
  ```

## Setup

1. Open http://localhost:9091/admin/llm-configs and click **+ New LLM Config**.

## Steps

1. Set **Type** to `Speech to text`.
   **Expected:** The settings group below the base fields changes its title to
   *Speech-to-text settings* and shows no temperature, output-token or
   capability fields.
2. Set **Provider** to `OPENAI`.
   **Expected:** An *OPENAI parameters* group appears with **Language**,
   **Prompt** and **Response Format**.
3. Fill in Name `whisper-test`, Model `whisper-1`, Base URL
   `https://api.openai.com`, API Key `${OPENAI_API_KEY}`, Language `en`,
   Response Format `verbose_json`. Save.
   **Expected:** The list shows `whisper-test` with `OPENAI · whisper-1`.
4. Open `whisper-test`.
   **Expected:** The detail view says **Type: Speech to text** and lists
   Language and Response Format. It shows no Temperature, Max Output Tokens,
   Context Window or Capabilities row.
5. Click **Test**.
   **Expected:** The dialog *Test whisper-test* opens with a drop zone
   labelled **Recording**, a **Record** button and the line *Or speak: Record
   starts, Stop transcribes.* — no message textarea.
6. Drop `/tmp/speech.wav` onto the zone.
   **Expected:** Within a few seconds the dialog shows a green line
   `✓ OK · <ms> ms · speech.wav · english · <n> s` and below it the spoken
   sentence as text.
7. Click **Record** and allow the microphone when the browser asks. Say a
   sentence, then click **Stop**.
   **Expected:** While recording, the button reads **Stop** and the status
   line counts seconds. After stopping, the same green line appears, this time
   naming `recording.webm`, with what was spoken as the transcript.
8. Verify the same call from the shell (replace `{id}` with the config's id
   from the URL):

   ```bash
   curl -s -X POST http://localhost:9091/admin/api/llm-configs/{id}/test-audio \
     -F "audio=@/tmp/speech.wav;type=audio/wav" | grep -o '"text":"[^"]*"' | tail -2
   ```

   **Expected:** The transcript appears in the response patch.

## Cleanup

- Delete the `whisper-test` config from its detail view.

## Notes

- The transcript is what the model heard: a synthesized voice may mangle
  proper names. Judge the step by "words came back that match the sentence",
  not by an exact string.
- With a wrong key the dialog shows a red `✗ Failed` line naming the HTTP
  status — that is the error path working, not a FAIL of this case.
- LM Studio serves no transcription models. Picking it as the provider leaves
  the model dropdown saying it lists none; that is expected.
- Recording needs a secure context. Over plain HTTP to a remote host the
  browser refuses the microphone and the status line says so — that is the
  browser, not a FAIL. Step 7 is then SKIPPED; the upload steps still apply.
- Safari records `audio/mp4`, Chrome and Firefox `audio/webm`. The file name
  in the result line follows, so `recording.m4a` there is equally correct.
