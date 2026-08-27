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
