- **chat-ui:** **the history drawer of a page that hosts its own kind of conversation lists those, not the chats.**
  A feature that opens sessions of its own type in the chat page — a builder —
  got the chat's drawer: the user's chats, "New chat" and links into `/chat`.
  The drawer now lists the user's conversations of the session's type with
  the same agent, and a feature can hand in its own title, start action and
  row links (`ChatShellComponent.History`) so the drawer stays inside it.
