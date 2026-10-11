- **workflow:** **MiniScript has a restricted mode, `MiniScriptEngineFactory.restricted()`.**
  Its engines call methods only through the public API of strings, numbers,
  booleans, lists, sets and maps, and never `getClass` — the unrestricted
  engine reaches any class through reflection. Use it for scripts nobody vetted.
