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
- **embedding:** **a runtime is built through `AgentRuntime.builder()` and made
  of plugins.** The builder in `mc-agent-sdk` takes the model, agents, tools,
  skills, guards and permissions; everything else — stores, the LLM gateway,
  the tool registry, skills from disk, the tokenizer — is a plugin handed to
  it, and a plugin of a kind the builder installs itself takes its place. A
  Spring Boot application gets the same runtime from the one starter
  `mc-agent-starter-runtime-engine`, with its services as beans, and adds to
  it by defining a `Plugin` bean. See the Embedding page.
- **extensions:** **an extension brings a runtime plugin instead of a runtime
  feature.** A class implementing `ai.mindconnect.plugin.Plugin`, registered in
  `META-INF/services/ai.mindconnect.plugin.Plugin` and, in a Spring host, a
  bean. The manifest still lists it under `contributes.features`.
- **persistence:** **`mindconnect.persistence` takes `memory` beside `file` and
  `postgres`:** nothing is kept past the process, for tests and demos.
- **memory:** **an agent's memory setting has four strategies** — `window` (the
  default), `compact`, `full` and `none` — under `memory` in its metadata. The
  five `memoryConfig` kinds of an agent file are read into them;
  `toolResultEviction` and the tool-result compression thresholds are no longer
  read. A summary is kept with its session and deleted with it. Summaries the
  earlier runtime wrote are not carried over: a long chat is summarised anew
  the next time it no longer fits.
- **tools:** **`fetch_tool_result` reads a result that memory replaced with a
  note, by the id the note names.** An old tool result is shown to the model as
  `[Tool result removed to save context: N tokens, id=…]` and stays in the
  session; a result is no longer summarised by a model on the way in.
- **sub-agents:** **the depth sub-agents may be nested to is a setting,**
  `mindconnect.agent.sub-agents.max-depth` (default 5). A call beyond it fails
  with an error the calling agent reads.
- Titles and summaries are written by the model
  `mindconnect.agent.stateless.llm-config-name` names; without the setting, by
  the model of the agent whose session it is.
- Token counts — the working-memory view, the budget memory fits a history
  into — come from one tokenizer for every model (`o200k_base`): exact for
  current OpenAI models, an estimate for the others.
- The Responses API and the CLI run on the new runtime. A response that reaches
  its round or output limit ends `incomplete` with the reason. The CLI in local
  mode answers a tool approval at the keyboard: `y` once, `a` for the session,
  `n` to deny.
- Deleting a session deletes its traces and its todo list with it.
- The admin UI answers an agent name another agent already has with a message
  and keeps the form, and deletes a session from an agent's Sessions tab only
  for the user it belongs to.
- Stopping a turn while a tool runs answers the open call with "Cancelled by
  user before the tool finished"; the chat takes the next message as before.
- "Callable by other agents" is back on the agent form: an agent switched off
  there is offered in no roster and `run_agent` refuses it. Seed files that
  say `callableByAgents: false` keep saying so.
- An agent chooses when old tool results are shown as a note, with
  `toolResultPruning` in its memory setting: `when_full` (default, only once
  the history no longer fits), `after_turn` (results of turns that are over,
  beyond a budget of the newest) or `off`. What the `skill` tool returned is
  never pruned. `toolResultEviction` of an agent file written before 1.0 is
  read as `after_turn`. A memory setting with an unknown key is refused when
  the agent form is saved.
- A long tool result reaches the model one page at a time (30,000 characters;
  `maxResultChars` per agent, agent form: *Max tool result chars*, or per tool
  as before). The page ends with a line naming the result's id and the next
  offset, and `fetch_tool_result` — now offered to every agent that has tools —
  reads on with `offset` and `limit`. No limit cuts the result any more, the
  runtime-wide cap of 100,000 characters included: the session keeps it whole.
- Agents a project keeps in `.mindconnect/agents/*.md` are found again:
  `list_agents` shows them first, `run_agent` calls them without a roster
  entry, and they may use no more than the agent calling them.
- A tool switched off in the tool catalog keeps its row, marked "(off)", so
  it can be switched on again there.
- A tool card in the chat draws the first 10,000 characters of a long result
  and links to *Download the full result*, which hands out all of it as a text
  file.
- The chat shows a card while a response reviewer reads the answer
  ("reviewing…") and its verdict afterwards — passed, modified or blocked.
- A chat that changed its model, its prompt, a tool or a sub-agent runs on
  settings of its own and no longer follows its agent. The header says so for
  every such chat (`own settings`, before only for a changed prompt), and the
  settings dialog names the state "this chat's own settings" instead of
  "no agent". Picking the agent again in the dialog makes the chat follow it.
- The working-memory view and `/memory` show a user message with the note
  about attached or removed files the model reads ahead of it.
