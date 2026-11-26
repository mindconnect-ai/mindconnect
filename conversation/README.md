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
| `mc-conversation-api` | the model, `ConversationManager`, the store ports and the channel SPI; no implementation. Its test-jar holds `ConversationStoreContract`, the test every store passes |
| `mc-conversation` | `ConversationService` and the stores in memory: enough to run conversations |
| `mc-conversation-file` | the store on files (`<namespace>/conversation-threads/<id>/`) |
| `mc-conversation-pg` | the store in Postgres (`mc_conversation_thread`, `mc_conversation_entry`) |
| `mc-conversation-arch-tests` | the shared ArchUnit rules over this area, that it knows no agents and that the API knows no implementation |

Write a channel or a store of your own against `mc-conversation-api` alone;
for a store, extend `ConversationStoreContract` in its test.

The area knows no agents — a rule in `mc-conversation-arch-tests` says so — and
builds on its own with the few `common` modules it needs:

    mvn -f conversation/pom.xml install

The stores ask `TenantScope` from `common/mc-scope` which namespace a call works
in; the agents' `ScopeSupplier` is one, so a host hands its own straight in, and
an embedding with one namespace takes `TenantScope.fixed("local")`.

`agents` will depend on `conversation`, never the other way round. The tables
`mc_conversation` and `mc_message` and the folder `conversations/` are where the
agent runtime kept its sessions before; only the migrations read them.
