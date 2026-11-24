- **chat:** **a chat turn runs on the runtime on the plugin kernel.** The chat
  page looks and streams as before, and its history is stored in the same
  conversations, in the same shape. Underneath, a turn is a task of the new
  runtime: it acts as the user who is signed in, a tool approved "for this
  session" is remembered in the session itself, and a tool result that was
  shortened to save context stays shortened. Sub-agents show one level below
  the turn, however deep they are nested.
- A chat either refers to a stored agent or runs on a definition of its own.
  A chat that refers to an agent follows what is changed on that agent; as soon
  as the chat chooses a model, tools, a prompt or sub-agents of its own, it
  keeps its own definition and no longer follows the stored agent.
- Files attached to a chat work on the new runtime: the next message announces
  what was attached or removed, an image or PDF goes to a model that reads it
  with the message that brought it, and `view_attachment` shows it again in a
  later turn. A model that cannot read a file is told about it in a note.
- The REST server keeps sessions and agents in the same stores as the admin UI:
  a chat started in one is found, with its history and attachments, in the
  other when both share their data.
- The REST API's chat turns run on the new runtime: sending a message,
  cancelling, reattaching to a session's stream, answering an approval with or
  without a continuation stream, and compressing a session's memory, which now
  summarises everything before the last turn for an agent that keeps a summary.
- **api:** **`/api/agents` answers with the agent as it is stored**, no longer
  with the flat shape of the earlier runtime: `{id, definition: {name,
  description, instructions, modelSettings, tools, maxRounds, metadata}, group,
  icon, welcomeMessage, status, createdAt, updatedAt, version}`. The requests
  take the fields they took. `/api/sessions` answers with `{id, userId,
  parentId, agentId, title, type, status, workingDir, additionalDirs,
  createdAt}`. A client that read `systemPrompt`, `llmConfigName` or
  `agentDefinitionId` from a response reads `definition.instructions`,
  `definition.modelSettings.model` and `agentId` instead.
- Agent files — seeds under `initial-data/agent-definitions`, registry entries —
  are read in the stored shape and, as before, in the flat one written before
  1.0. A migration compares and applies an agent field by field across its
  definition and its settings (`instructions`, `tools`, `memory`, `subAgents`, …).
- The task board shows the turns and tool calls of the runtime with their agent
  and their owner, and a namespace sees its own tasks only.
