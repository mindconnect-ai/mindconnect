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
