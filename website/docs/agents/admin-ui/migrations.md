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
- a field-level diff table — *Before* (stored) vs. *After* (bundled), with an
  **Apply** button on every row,
- a per-item **Apply** button, plus **Apply all (n)** in the header.

The per-item **Apply** **overwrites the stored record with the bundled version**
(`createdAt`, `updatedAt` and `version` are ignored when diffing). The per-row **Apply** takes
**only that one field** from the bundle and leaves everything else as stored —
the way to pick up a new model name for an LLM config without losing the API
key that only the stored copy has. A field the bundle dropped is removed. NEW
records have no diff table and are always imported whole. When nothing is
pending the page shows "Everything is up to date".

An LLM config's `apiKey` is compared by what it resolves to: the store keeps
keys encrypted and seeds usually carry a `${ENV_VAR}` placeholder, so a stored
key that decrypts to the bundled value (or to what the placeholder expands to)
is not a difference. When the keys do differ, the row says so without showing
either value.
