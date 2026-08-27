# Agent Runtime — Presentation

Marp deck for the agent runtime demo.

- `agent-runtime.md` — the slides (Marp Markdown)
- the embedded diagrams live in [`../images/`](../images/)

## Rendering

With the [Marp CLI](https://github.com/marp-team/marp-cli):

```bash
# HTML (browser preview)
npx @marp-team/marp-cli agent-runtime.md -o agent-runtime.html

# PDF
npx @marp-team/marp-cli agent-runtime.md --pdf --allow-local-files

# Presenter mode
npx @marp-team/marp-cli@latest -p --allow-local-files agent-runtime.md
```

### Live preview

The slides embed images from `../images/`, which sits *above* this folder, so
the dev server must be rooted one level up (`agents/doc/`) — otherwise it can't
serve those files and the images render blank:

```bash
# from agents/doc/
npx @marp-team/marp-cli -s .
# then open presentation/agent-runtime.md in the browser

# or straight from this folder, rooting the server one level up:
npx @marp-team/marp-cli -s ..
```

Or in VS Code with the **Marp for VS Code** extension (preview icon, top right).

## Updating the diagrams

The SVGs live in [`../images/`](../images/) and are generated from the PlantUML
sources under [`../architecture/`](../architecture/):

```bash
plantuml -tsvg -o ../images ../architecture/system-overview.puml
```
