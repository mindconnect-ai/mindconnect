- **workflow:** **inputs and step results are no longer run as scripts.** A
  workflow stored its input parameters and every step's result through the
  expression resolver, so any string starting with the name of a registered
  script language (`mini: …`, `javascript: …`) was evaluated. A workflow
  exposed as an agent tool (`workflow_<id>`) receives its inputs from the
  model, and a tool, HTTP or agent step returns whatever a web page or mail
  said — a prompt injection could run code on the server, and MiniScript
  reaches any Java class through `"x".getClass().forName(…)`. Inputs, resume
  parameters, step results and the variables `callWorkflow` passes on are now
  stored as data; only expressions written in the workflow definition are
  evaluated.
  A `${…}` placeholder inside a script expression (`mini: "${text}"`) is no
  longer pasted into the script's text before it runs, where a value
  containing a quote became code. Read the variable by name instead
  (`mini: text`); `${…}` keeps working in plain text values.
