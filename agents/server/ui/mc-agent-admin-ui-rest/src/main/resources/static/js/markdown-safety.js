// Raw HTML in markdown is shown as text, not built into the page.
//
// The markdown extension hands its source to marked as trusted, and marked
// passes raw HTML straight through into innerHTML. Most markdown here is not
// trusted: an agent's answer, its thoughts, a tool's output, a file name. A
// reply quoting <img src=x onerror=…> outside a code block ran script; one
// quoting an unclosed <div> swallowed the rest of the conversation.
//
// The server escapes what it can (MarkdownText), but only marked knows for
// sure what it will treat as HTML — an escaper that guesses where code starts
// and ends is one parser disagreement away from a hole. So marked itself is
// told: every raw HTML token renders as its escaped text. The one exception is
// the exact markup of a sprite icon, which the chat writes into markdown
// itself (MessageComponent#icon) and which carries nothing but a sprite id.
//
// It is the same module instance the extension renders with: an ES module is
// loaded once per URL, and this is the extension's URL. Install it after the
// extension and before anything renders.

const MARKED_ESM_URL = "https://cdn.jsdelivr.net/npm/marked@12/lib/marked.esm.js";

/** The tags of a sprite icon, one at a time — marked hands inline HTML over tag by tag. */
const ICON_TAG = /^(?:<svg class="sui-icon" aria-hidden="true">|<use href="\/sui\/icons\.svg#[a-z0-9-]+">|<\/use>|<\/svg>)$/;

const HTML_ESCAPE = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, ch => HTML_ESCAPE[ch]);
}

/** Whether {@code html} is nothing but sprite icon tags. */
export function isIconMarkup(html) {
    const tags = String(html).trim().match(/<[^>]*>/g);
    if (!tags) return false;
    return tags.join("") === String(html).trim() && tags.every(tag => ICON_TAG.test(tag));
}

/** The text marked would have put into the page for an HTML token, made inert. */
export function renderHtmlToken(token) {
    // marked 12 passes the raw HTML as a string; later majors pass a token.
    const html = typeof token === "string" ? token : (token && (token.text ?? token.raw)) || "";
    return isIconMarkup(html) ? html : escapeHtml(html);
}

export async function installMarkdownSafety() {
    const { marked } = await import(/* @vite-ignore */ MARKED_ESM_URL);
    marked.use({ renderer: { html: renderHtmlToken } });
}
