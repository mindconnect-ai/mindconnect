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

### Added

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
