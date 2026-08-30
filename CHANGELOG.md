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

## [0.0.3] - 2026-08-30

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
