- **agents:** **HTML in a conversation no longer breaks the chat.** An answer, a
  thought, a tool's output or a user message that contained HTML or JSX outside a
  code block was rendered as real elements — an unclosed `<div>` swallowed the rest
  of the conversation and the layout fell apart, and markup like `<img onerror>`
  could run script. Such text now shows as text; code blocks and inline code render
  as before, and tool output that itself contains a code fence stays inside its
  block. The admin UI also tells the markdown renderer itself to show raw HTML as text
  (only its own icon markup passes), so no reply can build elements in the page
  even where the escaping and the renderer read the markdown differently.
