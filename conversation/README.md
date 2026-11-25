# conversation

Conversations between people and bots: a customer and the support, a group in
a chat, an AI assistant taking part — and later the channels they happen in,
such as WhatsApp or Teams. A conversation holds what its participants wrote
for each other; how an agent arrived at its answer is the agent's session, in
`agents/`, not here.

| Module | What |
|---|---|
| `mc-conversation-core` | conversations, participants, messages; the ports and `ConversationService` |
| `mc-conversation` | the stores in memory and on files |
| `mc-conversation-pg` | the store in Postgres |
| `mc-conversation-arch-tests` | the shared ArchUnit rules over this area |

`agents` will depend on `conversation`, never the other way round.
