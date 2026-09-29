- **agents:** **A program in `code_execute` can call the agent's own tools.** It names them in
  the new `tools` argument, and calls them through a directory the host watches — so many tool
  calls happen inside one program, and only what the program prints reaches the model's context.
  The sandbox never sees a user or a credential: the runtime runs each tool in the session's own
  scope, through the same advisor chain a model-issued call takes. The declared list is what the
  approval card shows, so a tool that needs approval still asks before the program starts. Works
  the same against a remote workspace. Absent unless the runtime binds a `ScopedToolInvoker`, and
  then `code_execute` keeps exactly its old shape.
