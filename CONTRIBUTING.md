# Contributing to Mindconnect

Thanks for your interest in contributing! 🎉 This project is maintained by
David Beisert and welcomes contributions from everyone.

## Ground rules

- **`main` is protected.** Nobody pushes to it directly. All changes land
  through pull requests.
- Be respectful — see our [Code of Conduct](CODE_OF_CONDUCT.md).
- By contributing, you agree to the [Contributor License Agreement](CLA.md)
  and that your contribution is licensed under
  [Apache 2.0](LICENSE) (see "Licensing of contributions" below).

## How to contribute

1. **Fork** the repository and create a branch from `main`:
   ```bash
   git checkout -b feature/short-description
   ```
2. **Make your change.** Keep it focused — one topic per PR.
3. **Build and test** the area you touched:
   ```bash
   mvn -f agents/pom.xml      clean install   # or semantic-ui / workflow
   ```
4. **Commit** with a clear message (see below).
5. **Open a pull request** against `main`. Fill in the PR template.
6. A maintainer reviews and merges. Thank you! 🙏

## Commit messages

- Use clear, imperative subject lines: `fix(agents): handle null session id`.
- Group related changes; avoid "misc" commits.
- Reference issues where relevant (`Fixes #123`).

## Code style

- **Java 21**, Spring Boot 3.5. Follow the style of the surrounding code.
- Match existing naming, package layout (`ai.mindconnect.*`) and comment
  density. Don't reformat unrelated code.
- Add tests for new behaviour. Core modules use JUnit 5 + AssertJ.

## Scope of a good PR

- A bug fix with a regression test.
- A self-contained feature with tests and a short README note.
- Docs, examples, diagrams, typo fixes — all welcome.

For large or architectural changes, **open an issue first** to discuss the
approach before investing time.

## Licensing of contributions

This project uses an **inbound = outbound** model plus a lightweight
[CLA](CLA.md):

- Your contribution is licensed to the project and its users under the
  **Apache License 2.0** — the same license as the rest of the repo.
- You retain the copyright to your contribution; it stays part of the
  project's history.
- The CLA grants the maintainer the rights needed to keep the project
  sustainable (including potential future relicensing or dual-licensing).
  You confirm you have the right to contribute the code.

First-time contributors will be asked to accept the CLA on their first PR.

## Questions?

Open a [Discussion](https://github.com/mindconnect-ai/mindconnect/discussions)
or an issue. We're happy to help.
