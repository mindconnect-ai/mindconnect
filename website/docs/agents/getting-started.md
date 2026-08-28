---
title: Getting started
sidebar_position: 1
---

# Getting started

## Requirements

- Java 21
- Maven 3.9+

That's it for local use — **no Docker, no Keycloak, no database** are needed to
run the Admin UI in its default mode.

## Run it without building

If you only want to *use* the Admin UI, take a runnable jar instead of a
checkout. The `snapshot` pre-release carries the current development build of
main as a single self-contained file:

```bash
curl -LO https://github.com/mindconnect-ai/mindconnect/releases/download/snapshot/mc-agent-admin-ui-app-0.0.3-SNAPSHOT-exec.jar
export MINDCONNECT_ENCRYPTION_SECRET_KEY="change-me-to-a-32-char-secret!!!"
java -jar mc-agent-admin-ui-app-0.0.3-SNAPSHOT-exec.jar
```

The file name carries the version, so it changes when a release opens the next
one. `snapshot.txt` next to it names the current build (version, commit, build
time) — that is the file to read before assembling a URL.

These are development builds, replaced in place whenever the snapshot workflow
runs. Released versions live on [Maven
Central](https://central.sonatype.com/namespace/ai.mindconnect).

Prefer not to touch a terminal at all? The [desktop
launcher](https://github.com/mindconnect-ai/mc-clients/releases/latest)
downloads either channel, edits the environment and starts and stops the
server for you.

## Build

```bash
# Build the agents sub-tree
mvn -f agents/pom.xml clean install -DskipTests
```

## Set the environment variables

Before starting, export the variables the app reads from your shell.

**Required** — the encryption key for stored LLM credentials. There is no
default on purpose (a hard-coded key would mean credentials are "encrypted" with
a publicly known value), so the app **fails to start** without it. The key is
used directly as an AES key and must be **16, 24 or 32 characters** long:

```bash
export MINDCONNECT_ENCRYPTION_SECRET_KEY="change-me-to-a-32-char-secret!!!"
```

**As needed** — an API key for whichever LLM provider you want to use, plus an
optional web-search key:

```bash
export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY / GEMINI_API_KEY / …
export TAVILY_API_KEY=tvly-...        # only if you use the web_search tool
```

See [environment variables](./environment-variables.md) for the full list.

## Run the Admin UI

The Admin UI runs **without authentication by default** — it starts straight
away and logs you in as a fixed dev user (`mc_user`). With the variables above
set:

```bash
mvn -f agents/server/mc-agent-admin-ui-app/pom.xml spring-boot:run
```

Then open **http://localhost:9090**.

See the [Admin UI](./admin-ui/index.md) page for what each section does, and for
how to enable Keycloak login when you deploy.

## Two ways to run

| | Best for | Auth |
|--|----------|------|
| **[Admin UI](./admin-ui/index.md)** | Visual operation: configure agents, inspect memory, traces, todos, workspace | None by default; optional Keycloak for deployments |
| **[CLI client](./cli.md)** | Fast local development in the terminal | None — runs the runtime locally |
