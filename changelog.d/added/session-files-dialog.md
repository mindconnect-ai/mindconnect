- **agents:** **a Files dialog in the chat** — next to Working Memory, Traces and
  Todos. It lists the session's directories (the working directory, the
  additional ones and the session's own) and lets you open their folders, view a
  file and download it, so what the agent produced with `bash` or `code_execute`
  reaches you again, the way the Workspace dialog did before the workspace tools
  were removed. Only the session's owner sees anything, nothing outside those
  directories is reachable (`..` and symbolic links included), and HTML or SVG is
  shown as source rather than rendered.
