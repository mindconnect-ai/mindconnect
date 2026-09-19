- **agents:** **a tool can say which variables it needs from a user, and the
  installation asks them for it.** A `ToolFactory` or `MultiToolProvider`
  returns `userVariables()` — a list of `ToolVariable`s with a title, whether
  the tool can work without it and whether its value is a secret — and
  `ToolRegistry.declaredVariables()` aggregates them across everything on the
  classpath. On a user's first request of a session the Admin UI writes the
  ones that have a sensible default, lists all of them under *Your variables*
  on the profile with a *Set* action that opens the form ready-named, and
  raises a notification for every required one that neither the user, their
  namespace nor the process has a value for. Nothing is ever created empty: a
  blank user variable would answer the `${VAR}` lookup and cut off the
  namespace and the server behind it. A tool that declares nothing behaves
  exactly as before.
