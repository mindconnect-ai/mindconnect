---
title: Migrations
sidebar_position: 5
---

# Migrations

The **Migrations** section reconciles the **bundled seed data** on the classpath
(`initial-data/**`) with what is stored on disk. It does not touch any database
schema.

Every namespace silently gets the bundled records it never had — the start-up
namespace at start, every other one on its first use (see
[Initial data](../initial-data.md)). When a bundled record **changes** in a
newer version of the app, the stored copy is left alone — this page is where an
admin reviews and applies those changes, in the namespace at hand. It is also
where a bundled record that was deleted comes back: the seeding does not
install it again, here it is listed as NEW.

Four entity types are scanned: **LLM configs**, **agents**, **skills** and
**workflows**. A skill is only ever NEW — a stored skill is somebody's prose
and is never compared with the shipped one.

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
