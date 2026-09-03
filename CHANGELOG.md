# Changelog

What changed, in the words of someone who has to decide whether to upgrade.

This is not the commit log — [the releases page][releases] has that, generated.
An entry here earns its place by telling a reader who *uses* this repo what is
different for them: a new endpoint, a behaviour that changed, a bug whose
symptom they may have been living with. The four areas — agents, workflow,
taskqueue, common — release together under one version, so say which one an
entry belongs to when it is not obvious.

The format is [Keep a Changelog][keepachangelog]; this project follows
[semantic versioning][semver], with the caveat that it is pre-1.0 and breaking
changes may land in a minor.

**Adding an entry:** put it under `## [Unreleased]`, in the section that fits.
The release workflow renames that heading to the version being cut and opens a
fresh empty one, so nothing has to be moved by hand at release time.

[releases]: https://github.com/mindconnect-ai/mindconnect/releases
[keepachangelog]: https://keepachangelog.com/en/1.1.0/
[semver]: https://semver.org/spec/v2.0.0.html

## [Unreleased]

## [0.2.1] - 2026-09-02

### Added

- **A response reviewer says that it is running, and what it decided.** The
  reviewers run *after* the answer is already on screen, so the conversation
  sat there apparently finished while another model call was still going —
  seconds of "AI is thinking" with nothing to explain them. The chat now shows
  a card naming the reviewer while it checks, which then turns into its verdict
  (passed, modified, blocked) with the reason where one was given. The events
  had been in the stream all along; only the log was listening.

### Fixed

- **The "connection lost" notice no longer appears while the server is fine.**
  It fired on any stream that ended while its page was mounted — but the
  framework also marks a stream that way when a turn finishes while you are on
  another page, and keeps it so for a few seconds after you come back. The
  notice now asks the server before claiming it is gone: a reachable server
  means nothing was lost, and a dead one cannot answer. And once it *is* gone,
  the tab heals itself: it keeps asking with a growing interval, and when the
  server answers again it re-requests the page — composer back to Send, stream
  re-attached, notice gone — without anybody clicking Reload.


- **Losing the server no longer leaves the chat silently stuck.** When the
  server went away mid-turn — a restart, a crash — the composer stayed on
  "AI is thinking" and a half-streamed reply froze in place, with nothing to
  say why: the browser noticed the connection drop and told no one. It now
  shows a notice with a Reload button. A notice rather than an automatic
  reload on purpose: a connection that keeps dropping would otherwise re-fetch
  the page in a loop, and a person pressing Reload cannot.
- **The chat no longer changes shape while it streams.** The reply spanned the
  full window as the tokens arrived and only snapped back into its column once
  the turn ended, taking the composer with it. Two causes, both to do with the
  live patches not matching what the finished page renders: the streaming reply
  and its list item shared one id, so the first token replaced the item instead
  of filling it, and the composer's width was tied to a selector that only its
  idle state matched.


- **A cancelled or failed turn no longer leaves the UI thinking it is still
  running.** The stream now belongs to the session and outlives the turn, so
  nothing closes it when a turn ends badly — and the error path forgot to say
  the turn was over at all. A client that saw a turn fail therefore stayed in
  streaming state for good, and the floating "agent running" indicator kept
  announcing work that had long since stopped, for that session and then for
  older ones as they piled up.

### Removed

- **The floating "still running" indicator is gone.** It existed so that a
  turn started in one place was not lost when you looked elsewhere. Since a
  session's stream is reattached the moment you open the conversation, there
  is nothing left to lose, and the indicator was reporting from what the
  browser last heard rather than from what the server knows — which is how it
  came to claim that finished turns were working.

## [0.2.0] - 2026-09-01

### Added

- **One slow tab no longer slows the chat for everyone else.** The SSE fan-out
  behind a chat session used to write to each connected client in turn, while
  holding the lock that publishing needs — so a client whose socket had stopped
  draining held up the agent's output for every other client on that session.
  It now runs on the task queue's `Channel`, which gives each subscriber its own
  bounded queue and drains it on its own thread: a reader that falls behind
  loses history, never the turn, and never anybody else's. Reconnects also
  reach further back, since the replay buffer grew from 200 events to 2048.

- **Everyone watching a chat sees it live.** A chat's stream now belongs to the
  session rather than to one turn, so a second browser tab — or a colleague on
  the same session — receives the same tokens, tool cards and approval prompts
  as the person who typed, at the same moment. Clients attach when they open
  the session and stay attached across turns, which is what lets them see a
  turn somebody else starts; previously a client could only ever join a turn it
  had started itself. Only the running turn is streamed: joining a quiet
  session replays nothing and reads the persisted history, as before.
- **An agent can make an optional tool parameter mandatory for itself.** A
  `requiredParams` list in a tool binding's `overrides` adds those names to the
  schema the model is offered, and refuses a call that omits one before the
  tool runs. It is the counterpart to the existing `params` pinning: pinning
  takes a decision away from the model, requiring insists it make one. Needed
  because prose does not carry far enough — a description saying "always pass
  `query`" is followed by a large model and ignored by a small one perhaps half
  the time, and each omission costs the very context the parameter was meant to
  save. The seeded `url-reader` now requires `query` on both readers. Applied
  centrally in the tool registry, so it works for every tool source.

- **Web reads can say what they are looking for.** `web_read` and
  `web_read_browser` take a `query`, and return the passages of the page that
  bear on it instead of its first 20.000 characters. On a long page the figure
  you were after usually sits past that cut, so the old behaviour answered
  "not found" about pages that said it plainly — and the retry that provoked
  cost another read. Selection is lexical (BM25 over the page's own passages),
  so it costs no model call and no network: a 237.000-character Wikipedia
  article comes back as 6.000 characters. Passing no query keeps the previous
  behaviour.

- **`web_search` returns the relevant part of each result page.** Tavily
  already fetched those pages; taking its content costs one request, while
  re-fetching them ourselves costs a page load each and fails outright on the
  many sites that refuse an automated GET. Each result now carries the
  passages matching your query, filtered as above, in place of the snippet —
  the snippet was an extract of that same page, so printing both paid twice
  for one text. The provider's own generated answer is included too, marked as
  a lead rather than a citation. The result is no larger than before (measured
  on a Swiss price-comparison query: 7.098 characters against 7.438) while
  carrying page content the old output only pointed at. Set
  `include_content: false` for the old URL-list behaviour.

- **The "still running" indicator names the chat you left.** It used to say
  "Open chat", because semantic-ui drew it and had one guess for every
  application — a layer that moves bytes cannot know it is carrying a chat
  rather than a report or an import. 0.3.0 stopped drawing it and publishes
  stream state instead; the admin UI now renders it, so it says what the
  session is actually called (its title, or the agent's name while the chat is
  untitled) and offers "Watch" while the agent works, "Read it" once the answer
  is there. It also no longer appears for a chat where nothing is running,
  which the old one did as soon as a stream was merely connected.

- **A chat can run on a system prompt of its own.** The settings dialog gains
  the field, pre-filled with what the chat runs on today — its agent's prompt
  until you edit it. Editing it changes this one conversation; the agent, its
  tools and the agents it may call stay as they are, and the header marks the
  chat as running its own prompt so the agent's name on it is not misleading.
  Leaving the field alone stores no override, so the chat keeps tracking edits
  to the agent itself.


- **`code_execute` can be given a host directory to see.** The container used
  to have nothing but its session scratch space, so sandboxed code could not
  read the user's own files. A `mountDir` on the tool's agent binding — or the
  runtime-wide `codeExecMountDir` — mounts one directory at `/mnt/host`,
  read-only unless `mountWritable` says otherwise. The seeded `default-chat`
  mounts the user's home (`~`). The path comes from the operator, never from a
  tool argument: a directory chosen by model output is one that eventually
  reads `/`. Mounting nothing stays the default for every other agent, and the
  mount is part of the container's identity, so two bindings that disagree
  about it do not share one.


- **An agent can be given a roster of the agents it may delegate to.**
  `AgentDefinition.callableAgents` names them; the admin form offers the same
  multiselect the response reviewers use. `list_agents` then answers with that
  roster instead of the whole namespace, and a `run_agent` for anything else is
  refused with "agent 'x' is not available to you". Naming nobody is no
  restriction, not a ban — an agent without a roster reaches everyone, which is
  how every agent behaved before the field existed, so nothing on disk changes
  behaviour.
- **An agent carries a group and an icon.** `AgentDefinition` grows two
  fields. The group is the rubric the admin list files it under — the same
  idea as a tool's group, except an agent is configuration, so it carries it
  as data; the seeds use `assistants`, `sub-agents` and `utilities`. The icon
  is a Lucide name, drawn wherever the agent is named: the admin list, the
  chat header, the chat history. Both are optional; agents already on disk
  read as `general` with the generic bot icon and keep working untouched.
- **The agent form picks both instead of spelling them.** The group is a
  dropdown of the rubrics in use — read off the agents themselves, there is no
  group registry — with a button for naming a new one. The icon field opens a
  searchable grid of the whole Lucide set the framework ships. Both degrade to
  a plain field if scripting is off, and `GET /admin/api/icons` serves the
  icon names for anyone else who wants them.

### Changed

- **`default-chat` no longer runs a reviewer over every answer.** It was seeded
  with `answer-relevance-checker` in its `responseReviewers`, and a reviewer is
  a sub-agent that runs on each response — so every reply, however small, paid
  an extra LLM round-trip before you saw it. The agent itself stays bundled and
  can still be attached to any agent that wants it.

- **A running turn is no longer announced while you are on another page.** The
  floating status toast came from semantic-ui, which stopped drawing it in
  0.3.0 — rendering and wording belong to the application, since a layer that
  moves bytes cannot know it is carrying a chat. Nothing replaces it here yet,
  so a turn started in one tab runs unseen from another until you return to the
  conversation. The stream itself, its reconnect and its Stop button are
  unaffected. Restoring an equivalent is part of the session-stream work, using
  the `onStreamStateChange` hook 0.3.0 added for it.

- **A chat you did not start from an agent now runs on the seeded
  `default-chat` agent.** Its prompt, model, tools and the agents it may
  delegate to are configuration you can edit, instead of defaults compiled
  into the chat controller. The agent is the former "Assistant with tools",
  renamed; it may reach `web-researcher`, `file-finder` and `explorer` and
  nothing else, which is what stops a passing question from being handed to a
  research orchestrator that fans out and takes a minute. It also gains `code_execute`,
  so a calculation runs in a container rather than in the model's head. Tool
  search is on and the eight rarely-used tools (documents, Gmail, `file_write`)
  are deferred, so a turn carries twelve tool definitions instead of twenty.
  Installations seeded before this agent existed keep the built-in defaults
  until they install it.
- **The seeded orchestrators name the agents they delegate to.** `planner`,
  `research-lead` and `web-researcher` carry the roster their own prompts
  already described, so it is enforced rather than merely suggested. `planner`
  and `research-lead` also get 25 iterations instead of 10 — planning,
  delegating, verifying and synthesising in one loop did not fit in ten, and
  they ran out mid-plan.
- **The agent list is grouped and no longer paginated.** One collapsible
  section per rubric, closed by default, the way the tool catalog reads —
  assistants first, then sub-agents, then utilities. Paging cut across the
  grouping, so it is gone; `page` and `size` on `GET /admin/agents` are gone
  with it.
- **Amethyst is the admin UI's default theme.** It was Clody. Anyone who has
  picked a theme before keeps theirs — the choice lives in the browser, and
  only a browser that never made one sees the change.
- **The chat's agent picker offers assistants only.** A sub-agent expects a
  self-contained brief from an orchestrator and a utility answers in the one
  shape the runtime calls it for, so neither belongs in a list of things to
  chat with. A chat already bound to another agent keeps it — the agent stays
  in the picker so opening the dialog cannot silently reassign it.

### Changed

- **The seeded LM Studio configs no longer claim a context window nobody
  loaded.** `agent-default` and `lm-studio-default` shipped with 131.072
  tokens — the model's maximum, not what LM Studio actually loads, which
  defaults far lower. The runtime believed the larger number, so it never
  compacted and LM Studio silently cut the front off the prompt, taking the
  system prompt with it. Both now seed 32.768, and `maxOutputTokens` drops
  from 8.192 to 4.096 — output equal to the whole window left nothing for the
  prompt. Under-declaring the window is harmless; over-declaring is the bug.
  Existing installations keep their stored values: seeds are only imported
  when no config of that name exists.

- **A `gemma-reader` config ships alongside them.** A small local model, 16.384
  tokens, temperature 0.2 — meant for the reading and summarizing roles, where
  prefill dominates and a 120B model buys nothing. Point `url-reader` at it to
  keep the large model for planning and synthesis. Model and base URL are
  overridable via `GEMMA_MODEL` and `LM_STUDIO_BASE_URL`.

### Fixed

- **An agent pointed at an LLM alias was measured against the wrong model.**
  Memory strategies read the model name and context-window size straight off
  the config named by the agent — but an alias carries neither, so every agent
  on one was counted with the character-based fallback counter instead of the
  model's tokenizer, and sized against whatever window the alias record
  happened to hold rather than its target's. Compaction therefore triggered at
  the wrong point, in either direction. Alias resolution now lives in
  `LlmConfigRepository.findResolvedByName` and is used everywhere a model
  property is read.

- **A blocked page no longer provokes the same request again.** `web_read`
  answered "Error: HTTP 403" and left the model to invent a recovery, which
  was reliably the same URL under a different language path — blocked
  identically, one wasted round each time. The message now names the dead end:
  which retries will fail, and what to do instead.


- **Chat settings no longer discard what you changed — or what you didn't.**
  Applying the dialog dropped the model you had just picked, and quietly
  stripped every tool the tool registry could not offer: on a machine without
  Gmail credentials, opening the settings and pressing Apply cost the chat its
  three Gmail tools. The dialog now writes back only the settings that actually
  differ from the agent's, and leaves tools it never asked about alone.

- **The chat settings dialog sees the agent the chat is running on.** A chat
  opened from an agent carries only its `agentDefinitionId`; the dialog read
  the session-agent list, found it empty, and reported "no agent" — so pressing
  Apply detached the chat from the agent it was plainly running on, losing its
  tools and the agents it may call. It has always been wrong for chats started
  from an agent page; with `default-chat` it became the ordinary case.


- **The chat header names the conversation again.** Since the chat stopped
  rendering its own app-shell header, the header said which agent was
  answering but not which conversation you were in — and the history is a
  closed drawer, so there was nowhere else to look. The conversation's title
  leads it now, with the agent beside it as a badge; an untitled chat still
  shows the agent's name.

- **The admin UI's JSON views follow the theme.** Working memory, LLM traces
  and tool arguments were painted on a white slab whatever theme was on — a
  glaring hole in the middle of Dark, Amethyst and Gipiti. They now take the
  page's own code-slab colours, so a JSON view looks like every other code
  block in the app, and a theme that retunes its tokens is followed without a
  second edit.

- **The admin UI's header and sidebar stay put while a page scrolls.** Only the
  chat page and the API explorer capped themselves to the window; on every
  other screen a long list scrolled the whole document and carried the header
  and the navigation off the top. The shell is now capped once for all of them,
  which is what lets the content area do the scrolling — the behaviour the
  framework's own shell was already built for.

- **The navigation drawer is opaque on a narrow screen.** Below 768px the
  sidebar slides over the content instead of pushing it, and it had no
  background of its own — what reads as its colour on a wide screen is the
  shell showing through. The page was legible straight through the nav labels.
  The drawer now carries the sidebar's own colour and an edge shadow, in every
  theme; the pushed sidebar is unchanged.

## [0.1.0] - 2026-08-30

### Changed

- **The seeded assistant asks before sending e-mail.** `gmail_send_email` in
  the "Assistant with tools" binding now carries `needsApproval` — reading and
  searching mail stay unattended, but every send waits for a human yes (with
  "allow for this session" available on the approval card). Existing data
  directories keep their seeded binding; this affects fresh installs.

### Changed

- **The seeded `web-researcher` agent runs on `agent-default`.** It was the
  one agent pinned to `openai-default`, so a fresh install without an OpenAI
  key shipped an agent that could not start.

## [0.0.3] - 2026-08-30

### Changed

- **The seeded `donoreport-gen` workflow is now `wordreport-gen`.** Same
  workflow — pick sections from a Word template, research each against the
  ingested documents, write the report — under the generic name it deserved;
  the client-specific one leaked out of the project it was born in. Existing
  data directories keep whatever name they were seeded with.

### Changed

- **The admin UIs draw on semantic-ui 0.2.0.** The released sheet brings the
  spacing and type scale, list/table row alignment, one header height, calmer
  separators and view transitions that this cycle's UI work was built against
  (previously 0.1.3).
- **Workflow run pages carry the same header bar as every other screen.** The
  live progress view, the finished trace and the halted Resume/Log view all
  open with the workflow's icon, its name and the page actions (Back, Variable
  scope, Run again) — instead of a lone coloured title that all but vanished
  on the dark themes.
- **A run reads as the workflow it came from.** The run log uses the editor's
  own row grammar — step icon, type and name on one joined line, hairline
  separators, bracket guide lines around nested steps — annotated with the
  duration, a state pill when a step did not plainly succeed, and the
  assignment it produced (`→ variable = value`) right-aligned on the row. The
  live progress view shows the same rows while the run is still going.
- **The run view follows the theme.** Step rows, state pills, outcome banner
  and the log/JSON inspectors draw in theme tokens instead of a hard-coded
  light palette, so they no longer render as white islands on the dark themes.
  The standalone workflow admin app's stylesheet is synced to the same sheet.
- **The vector store detail page opens with the standard header** — the
  store's name with a back action, its template/backend/embedding settings as
  a quiet meta line — instead of a coloured all-in-one title sentence.

- **The chat reads as a conversation rather than as a list of records.** The
  message list drops its card, its row separators and its hover highlight; the
  agent's turns lose the grey bubble and are set as prose at a readable
  measure, the sender/timestamp line and the per-message actions appear on
  hover instead of taking a band of space on every turn, and the composer is a
  single rounded field with a placeholder that starts two lines tall instead of
  four. Markdown bullets are visible again — a list nested in a `UiList` was
  inheriting the list's own `list-style: none`.

### Fixed

- **Selects no longer tile their dropdown chevron across the field on the
  dark themes.** The themes restyled inputs with the `background` shorthand,
  which reset the `no-repeat` behind the framework's custom select arrow —
  the arrow repeated as a pattern over the whole control.
- **"Add parameter" no longer invents the description "null".** Untouched
  fields arrive from the SPA as JSON `null`; re-rendering the dialog after a
  type change stringified that into the literal text `null` (and, for a
  boolean parameter, a default of `false` the user never chose).

- **The Stores tab can create stores again.** Moving the Vector Stores actions
  into the page header had tied the button to the tab the page was opened on;
  switching tabs now swaps the header action with it, so Stores gets New Store
  and Templates gets New Template.
- **The chat history drawer stays opaque under the mouse.** A leftover rule
  from when the history was a side column turned the drawer transparent on
  hover. The drawer also opens below the page header now (with its open/close
  controls drawn as panel icons rather than arrows), the host's "…" menu
  (workspace, traces, memory, todos) is back in the chat header, and the
  token-usage gauge is no longer faded.

- **Tool and migration group headings are legible on the dark themes.** Their
  ink was a hard-coded slate instead of the theme's text token — invisible on
  Amethyst and Dark. The tool groups also drop their extra size and weight and
  read like the migration rows.

- **Workflow steps carry an icon, one type size, and their wiring.** Each
  editor row starts with an icon for the step's kind, type and name share one
  size (told apart by weight), a quiet detail says what makes the step itself
  (`file in files`, the code language, `GET <url>`), and `→ variable` shows
  where the result lands. Nesting guide lines are full-strength and close at
  the bottom like a bracket. The step Details dialog grew up with it: every
  populated property of the step — code, headers, variable assignments —
  shown under an icon-and-type head, not just name/type/var.

- **The workflow detail page wears the standard header, and its editor reads
  as a list.** The ad-hoc "Workflow: name" text-and-buttons row becomes the
  same icon + title + actions bar every other screen carries. Steps are no
  longer spaced-out cards with three buttons each: they are joined rows —
  type, name, and a "…" menu holding Details/Edit/Delete — with nesting
  shown by indentation along a guide line, and "+ Add step" as a quiet
  secondary button at its natural width.

- **The session tools in the admin UI work for a chat without an agent.**
  Working memory, traces, todos and the workspace dialog resolved the agent
  straight from the registry, so all four answered 404 for a chat whose agent
  lives in its session. They now resolve the same way the run does.

### Added

- **Every theme sets the same type size.** Clody, Gipiti, Sorbet and Amethyst
  were at 16px and the framework's own look at 15; they are all 15 now, so
  switching a theme changes how the app looks and not how big its text is.

- **The Vector Stores screen is built like the others.** Its tables had no
  titles, so each tab's header bar held one lone button in an otherwise empty
  band, while the page carried a heading no other screen has. The tables have
  their titles back — name left, action right, as on Agents and Tools — and
  the page keeps its own above the tabs.

- **The chat's header is the app shell's header.** It had been dressed down
  into something in between, with its own height, padding and type size, so
  it agreed neither with the bar above it nor with the Agents screen. Same
  metrics now; the burger sits where a list header's icon sits and is sized
  like one.

- **Vector Stores gets the Agents header.** Icon, title and the primary
  action in one bar at the top of the page content, with the tabs below it.
  The action follows the tab you are on, so no table needs a header bar of
  its own holding nothing but a button.

- **The chat's history drawer works.** Opening it is a floating button just
  under the header on the left, where the drawer comes from; closing it is an
  × in the drawer's own top-right corner, or a click on the scrim. The drawer
  is opaque, which it was not — it slid over the conversation transparently
  and was unusable.

- **The composer looks like an input again.** Its 20px corner was a pill's
  radius on a text field; it takes the same corner every other input in the
  app has, and so does the send button.

- **The chat is a page like the others, not an app inside the app.** It had
  its own app-shell header — agent, session title, overflow — inside the
  host's, so the screen carried two title bars where every other one carries
  a single list header. That header is gone: the conversation's own header
  names the agent, with the icon first, the same 17px title, the same
  padding, the same hairline and the same white surface as the Agents list,
  spanning the same width. The history is a drawer behind the button in that
  header rather than a permanent second sidebar, so the layout is nav,
  content, and nothing else.

- **The chat is one surface, not a frame inside a frame.** The chat renders
  its own app shell — it also runs standalone — and nested in the admin shell
  that showed as a panel with its own edges floating in the page. It fills
  the shell exactly now, and the conversation and the history sit on two
  different planes (plain surface and tinted) so the sidebar is actually
  separated from what you are reading.

- **The workflow admin and the agent badge are on the type scale.** The
  workflow screens wrote seven sizes of their own, from 11px to 18px
  including a 13.5px, which is why they measured differently from every other
  page.

- **The LLM-config badge on an agent row is a column.** Its width followed
  its own text, so the left edge landed on a different pixel in almost every
  row and the eye had no line to follow. Fixed width, one edge.

- **Compact, the framework's look at close range.** The one theme that
  changes no colour at all: smaller type, tighter rows, less padding
  throughout, so a list of agents shows more of itself and the eye travels
  less. Ten rows plus the pager where the default fits nine.

- **Sorbet, a pastel light theme.** The first look that uses more than one
  colour on purpose: a lilac ground, a mint badge, a peach warning and a rose
  error can share a screen because none of them is saturated. It is also the
  only one here that keeps its shadows — plum-tinted, not grey — since a
  hairline on a tinted page reads as a scratch. The tints stay in the
  backgrounds and the type is a deep plum-ink: every text-on-background pair
  clears WCAG AA for body text, the worst at 4.62.

- **Two dark looks.** *Dark* is the framework's own `sui-dark.css`, now
  linked and offered in the picker rather than reinvented. *Amethyst* is new:
  violet on a violet-cast dark ground, where the colour is the theme's
  character rather than an accent pointing at things.

- **The themes agree on type size.** Gipiti was a point smaller than Clody.
  A theme decides how the app looks, not how big its text is — switching one
  should not resize the screen you were reading — so all three of the themes
  shipped here now set the same 16px. *Dark* and *Default* are the
  framework's own sheets and keep its 15px base.

- **A second theme, Gipiti, and a way to switch.** Where Clody is a document
  — one warm plane, a clay accent — Gipiti is an application: achromatic
  neutrals with no temperature at all, a grey frame around a white working
  surface, near-black actions instead of an accent colour, and pill-shaped
  controls. Colour appears only where it means something.

  Switch with the palette button in the header, or with `?theme=clody`,
  `?theme=gipiti`, `?theme=default` on any admin URL. The choice is
  remembered per browser and applies without a reload — nothing about it
  reaches the server, so two people on the same login can disagree. Both
  stylesheets are always linked and each is scoped to its own class on
  `<html>`, so the inactive one costs bytes and nothing else.

- **Clody, a warm theme for the admin UI and the chat.** The admin app now
  ships a second look: bone-and-clay neutrals instead of the cool slate scale,
  a light header band, flat surfaces (no card shadows, no hover elevation),
  16px type at a reading line-height, and a single accent colour that every
  tint derives from. It is an overlay on the framework stylesheet, switched on
  by the `sui-theme-clody` class on `<html>` in `index.html` — remove the class
  and the app renders exactly as before. Change the three
  `--sui-color-primary*` values in `css/clody.css` to move the whole app onto
  another accent.

- **A chat that needs no agent.** A session can now carry its own agent:
  either a reference to one from the registry, or an inline one that lives
  only in that session — a model, a set of tools, and nothing in the
  registry. Pick a model and tools and start typing; the chat is not findable
  under any agent, because it belongs to none. Sessions written before this
  keep working unchanged: an empty `sessionAgents` list means the agent
  behind `agentDefinitionId` decides, exactly as before.
- **A user-facing chat UI, in its own module.** `mc-agent-chat-ui-rest`
  serves it under `/chat`: the conversation history down the left, the agent
  and session title across the top, model and tools behind a button on the
  composer. The admin UI embeds it — `Chat` is its first menu entry and the
  root now lands there — and depends on the new module for its own chat page,
  so a chat client does not have to ship agent CRUD, LLM configs and traces.
- **Session files over the REST API.** `GET /api/sessions/{id}/files` lists
  what is attached to a chat with the number of searchable chunks each
  produced, and `DELETE /api/sessions/{id}/files?file={fileId}` detaches one —
  removing its chunks from the session's vector store and the spooled copy, so
  the agent can no longer search it. The original in the file store stays; it
  may belong to other sessions too. Uploading was already possible; listing and
  detaching were only reachable from the admin UI.
- **The embedding facade can start a chat without an agent.**
  `AgentRuntime.openSession(llmConfigName, toolNames, userId)` (and an overload
  taking the prompt and the tool-search switch) opens a session that carries
  its own agent: a model, a set of tools, nothing in the registry and nothing
  to clean up afterwards. `ask(...)` does the same in one call.
- **A chat can only be opened, renamed or deleted by the user it belongs to.**
  Every endpoint under `/chat/api/sessions/{id}` now checks the session's
  owner before doing anything, and answers 404 otherwise. A session id travels
  in URLs, links and logs — it was never meant to be the only thing standing
  between two users' conversations.
- `AgentSessionRepository.findByUser(namespace, userId)` — every top-level
  session of one user, newest first. Sub-agent sessions are left out: they
  belong to the turn that spawned them, not to the user's history.
- **The workspace files an agent wrote are readable over the API.**
  `GET /api/workspaces/session/{id}/files`, `/api/workspaces/agent/{agentId}/user/{userId}/files`
  and `/api/workspaces/user/{userId}/files` list them, and appending
  `/{name}` returns the content. The scope is in the path because it decides
  which identifiers the request needs at all: a session workspace is addressed
  by its session, an agent workspace by agent and user, the user workspace by
  the user alone. Until now only the admin UI could see these files, by
  reaching into the store directly.
- **A chat client can reattach to a running turn.** The event stream now
  belongs to the session rather than to a single turn:
  `GET /api/sessions/{id}/stream?afterSeq=N` replays what the buffer still
  holds and then continues live. The first frame says whether a turn is
  running and where the buffer starts, so a client that lost its connection
  can tell the difference between "carry on" and "reload the history".
- **Approvals can be answered through the REST API.**
  `POST /api/sessions/{id}/approvals/{callId}?approved=&scope=once|session`
  delivers the decision to the parked tool task;
  `GET /api/sessions/{id}/approvals` lists the still-open questions, which is
  what a client needs after connecting late — the request event exists only in
  the moment it is raised. Before this, a client outside the admin UI could
  show an approval card but never answer it.
- **Runnable jars.** `mc-agent-admin-ui-app` and `mc-agent-cli` now also build
  a Spring Boot executable jar (classifier `exec`). The plain jar stays the
  main artifact, so nothing about the Maven Central publication changes.
- **A snapshot channel.** A manually triggered workflow publishes those jars
  to a rolling `snapshot` pre-release, which is the quickest way to run the
  admin UI without a checkout — see
  [Getting started](https://mindconnect-ai.github.io/mindconnect/agents/getting-started).

### Changed

- `TurnChannels` is now `SessionChannels`, keyed by session, and events travel
  as `SessionEvent(turnId, run, event)`. Code that subscribed to a turn keeps
  working through `subscribeTurn(...)`; the turn is a coordinate in the
  envelope now, not a channel of its own.

### Fixed

- **The agent server starts again.** `mc-agent-api-app` failed at startup with
  `required a bean of type 'ai.mindconnect.common.Namespace'`: the workflow
  tools resolve agents by name and need the namespace, which only the admin UI
  app defined. The agent server now declares it too.
- **Swagger on the agent server lists the REST API only.** The workflow
  admin's page controller arrives on the classpath with the workflow tools and
  mapped 40 `/workflow-admin` routes into the OpenAPI document. Those are
  server-rendered UI, not API, and their recursive `UiNode` schemas made
  Swagger UI crawl. The document is now scoped to `/api/**`.

### Documentation

- The REST API has a page of its own, including the stream and the approvals:
  <https://mindconnect-ai.github.io/mindconnect/agents/rest-api>.

## [0.0.2] - 2026-08-27

First release on Maven Central. Everything before this line lives in the
[commit history][releases].

### Added

- Admin UI to execute Chat, configure Agents, LLM Configs, Vector Stores, Migrations
- CLI interface to execute agents
- Rest Layer to manage the agent runtime including the chat session
- Tool approvals in the Admin UI
- OpenAI Responses API compatible library to use the agent runtime or switch to OpenAI directly
- Agent execution and tool execution is run on the TaskQueue and Channels to easily let tasks run distributed and persistent
