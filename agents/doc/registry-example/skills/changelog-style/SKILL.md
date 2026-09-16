---
name: changelog-style
description: Use when writing or reviewing a changelog entry, so it reads for someone deciding whether to upgrade
tools: file_read
---
# How a changelog entry is written here

An entry is for a reader who *uses* the software and has to decide whether
to upgrade — not for the commit log, which the releases page already has.

1. Lead with what is different for them: a new endpoint, a behaviour that
   changed, a bug whose symptom they may have been living with.
2. Name the area in bold when it is not obvious (`**agents:**`,
   `**workflow:**`).
3. One entry per change, a few lines each; no list of files touched.
4. Refactorings, tests, docs and build plumbing get no entry.
