- **chat:** **a chat turn runs on the runtime on the plugin kernel.** The chat
  page looks and streams as before, and its history is stored in the same
  conversations, in the same shape. Underneath, a turn is a task of the new
  runtime: it acts as the user who is signed in, a tool approved "for this
  session" is remembered in the session itself, and a tool result that was
  shortened to save context stays shortened. Sub-agents show one level below
  the turn, however deep they are nested.
