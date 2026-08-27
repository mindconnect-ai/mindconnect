<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="../.github/assets/logo-dark.svg">
    <img alt="MindConnect" src="../.github/assets/logo-light.svg" width="160">
  </picture>
</p>

<h1 align="center">common</h1>

Shared, dependency-light utility libraries used across the other areas
(agents, workflow, semantic-ui). Each module is independent and can be
used on its own — pull in just the one you need.

## Modules

| Module | Purpose |
|--------|---------|
| `mc-common` | Domain primitives and shared types |
| `mc-file-manager` | File storage / upload / download utilities |
| `mc-webscraper` | Web scraping and content extraction |
| `mc-pathaccessor` | Navigate and read/write nested object & JSON paths |
| `mc-script-mini` | Minimal embeddable script runner |

## Build

```bash
mvn -f common/mc-common/pom.xml clean install
```

Each module builds independently. Java 21.
