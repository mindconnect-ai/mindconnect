---
title: Migrations
sidebar_position: 5
---

# Migrations

The **Migrations** section reconciles the **bundled seed data** on the classpath
(`initial-data/**`) with what is stored on disk. It does not touch any database
schema.

At startup the `InitialDataLoader` silently imports only *new* records (see
[Initial data](../initial-data.md)). When a bundled record **changes** in a
newer version of the app, the stored copy is left alone — this page is where an
admin reviews and applies those changes.

Three entity types are scanned: **LLM configs**, **agents** and **workflows**.

For each pending item the page shows:

- a **NEW** or **CHANGED** badge,
- a field-level diff table — *Before* (stored) vs. *After* (bundled),
- a per-item **Apply** button, plus **Apply all (n)** in the header.

Applying **overwrites the stored record with the bundled version**
(`createdAt`/`updatedAt` are ignored when diffing). When nothing is pending the
page shows "Everything is up to date".
