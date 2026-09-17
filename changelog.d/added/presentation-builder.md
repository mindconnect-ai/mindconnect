- **agents:** **`presentation-builder`, a sub-agent for PowerPoint decks**, bundled
  with the `pptx-builder` skill and callable from `default-chat`. The chat hands it
  a brief — the outline and facts, or the path of a deck to revise — and gets back
  the path and the slide titles; the planning, the spec and any failed attempts stay
  in the sub-agent's own context. It saves the spec beside the deck as
  `<name>.spec.json`, which is how a later brief changes the deck. New installations
  get both; an existing one imports the agent and the skill on start, while its
  stored `default-chat` keeps its roster until you add the agent there.
