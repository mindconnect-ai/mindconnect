package ai.mindconnect.agent.tools.document;

import java.util.List;

/**
 * In-memory representation of a parsed document, suitable for the document tools
 * (outline / read / grep). PDFs and Word are normalised onto the same shape so
 * downstream tools don't need to branch by format.
 * <p>
 * For PDFs each {@link Page#number} is the actual page number. For Word documents
 * the source format has no concept of pages — we synthesise pseudo-pages by
 * grouping paragraphs into ~3000-token chunks so callers can still ask for a
 * "page range" deterministically.
 */
public record DocumentModel(
        String relativePath,    // path relative to baseDir, used for display
        Format format,
        List<Page> pages,
        List<OutlineEntry> outline,   // may be empty if the document has no structure
        List<Section> sections        // flat, exact-content split at every heading; never empty
) {

    public enum Format { PDF, DOCX, TEXT }

    /** A single addressable unit of text. For PDFs = real page; for Word = chunk. */
    public record Page(int number, String text) {}

    /**
     * Hierarchical outline entry derived from the document's own structure
     * (PDF bookmarks, Word heading styles).
     */
    public record OutlineEntry(int level, String title, int startPage, int endPage) {}

    /**
     * A flat content split of the document: one section per heading, its
     * content running up to the next heading (any level). Text before the
     * first heading — or a document without headings — becomes a section with
     * an empty title. {@code index} is 1-based. For Word the boundaries are
     * exact (heading styles); for PDFs they are page-granular (bookmarks).
     */
    public record Section(int index, int level, String title, String content) {}

    public int totalPages() { return pages.size(); }

    /** Approximate total tokens — uses 4 chars per token rule of thumb. */
    public int approxTokens() {
        long chars = pages.stream().mapToLong(p -> p.text().length()).sum();
        return (int) (chars / 4);
    }
}
