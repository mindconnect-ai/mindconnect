# Security Policy

## Reporting a vulnerability

**Please do not report security issues in public issues or discussions.**

Instead, report them privately via GitHub's built-in
**[Security Advisories](https://github.com/mindconnect-ai/mindconnect/security/advisories/new)**
("Report a vulnerability"). This reaches the maintainer directly and keeps
the details private until a fix is available — no email needed.

When reporting, please include:

- The affected area (agent runtime, workflow engine, semantic-ui, …) and
  module/version or commit.
- A description of the issue and its potential impact.
- Steps to reproduce, a proof of concept, or a failing test if possible.

## What to expect

- Acknowledgement of your report as soon as possible.
- An assessment and, if confirmed, a fix coordinated with you.
- Credit in the advisory once resolved, if you wish.

## Scope notes

This project includes an agent runtime, tool execution (including script
execution and web/browser tools), credential storage, and authentication
code. Reports touching these areas are especially appreciated. Please test
only against your own instances — never against systems you don't own.

## Supported versions

The project is pre-1.0 and under active development; fixes are generally
applied to `main`.
