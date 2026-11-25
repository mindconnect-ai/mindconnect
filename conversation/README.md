# conversation

Conversations between people and bots: a customer and the support, a group in
a chat, an AI assistant taking part — on the own chat page or in a channel such
as WhatsApp or Teams. A conversation holds what its participants wrote for each
other; how an agent arrived at its answer is the agent's session, in `agents/`,
not here.

## The model

- **`Conversation`** — topic, status, the `channel` it happens in (null for
  none but this installation), its participants and metadata.
- **`Participant`** — a `PERSON`, a `BOT` or the `SYSTEM`, with `refId` (who it
  is where it comes from: a user's or agent's id, a phone number, a Teams id),
  display name and metadata such as its `role` (customer, support).
- **`Message`** — its place in the conversation, the participant it is `from`,
  its content parts (text, image, file) and metadata. Who wrote it says what it
  is. A message an agent wrote carries `agent.sessionId` and `agent.turnId` in
  its metadata; the conversation never interprets them.
- **`ConversationChannel`** — the SPI of a channel: `post` hands a message
  written here to the conversation's channel, `receive` stores one that came in
  through it without handing it back.

## Modules

| Module | What |
|---|---|
| `mc-conversation-core` | the model, `ConversationManager`, `ConversationService`, the store ports and the channel SPI |
| `mc-conversation` | the stores in memory and on files (`<namespace>/conversation-threads/<id>/`) |
| `mc-conversation-pg` | the store in Postgres (`mc_conversation_thread`, `mc_conversation_entry`) |
| `mc-conversation-arch-tests` | the shared ArchUnit rules over this area, and that it knows no agents |

`agents` will depend on `conversation`, never the other way round. The tables
`mc_conversation` and `mc_message` and the folder `conversations/` are where the
agent runtime kept its sessions before; only the migrations read them.
