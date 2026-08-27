package ai.mindconnect.agent.tools.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a document into a {@link DocumentModel} for the document tools.
 * Supports PDF (via PDFBox), .docx (via Apache POI), and plain text fallback.
 * <p>
 * Parses are cached by {@code (absolutePath, mtime, size)} key — re-parsing a
 * 1000-page PDF for every tool call would be unbearable. Cache is bounded and
 * dropped automatically when the file changes on disk.
 */
public class DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(DocumentReader.class);
    /** Pseudo-page size when synthesising pages for non-paged formats (Word, text). */
    private static final int CHARS_PER_SYNTHETIC_PAGE = 12_000;
    /** Hard cap on cache entries; drops oldest first. */
    private static final int MAX_CACHE_ENTRIES = 16;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public DocumentModel load(Path baseDir, Path absoluteFile) throws IOException {
        if (!Files.isRegularFile(absoluteFile)) {
            throw new IOException("not a file: " + absoluteFile);
        }
        long mtime = Files.getLastModifiedTime(absoluteFile).toMillis();
        long size = Files.size(absoluteFile);
        String key = absoluteFile + "|" + mtime + "|" + size;

        CacheEntry hit = cache.get(key);
        if (hit != null) {
            hit.lastAccess = System.currentTimeMillis();
            return hit.model;
        }

        DocumentModel model = parse(baseDir, absoluteFile);
        cache.put(key, new CacheEntry(model, System.currentTimeMillis()));
        evictIfFull();
        return model;
    }

    private DocumentModel parse(Path baseDir, Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String rel = baseDir.relativize(file).toString().replace('\\', '/');
        if (name.endsWith(".pdf")) {
            return parsePdf(rel, file);
        } else if (name.endsWith(".docx")) {
            return parseDocx(rel, file);
        } else {
            return parseText(rel, file);
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private DocumentModel parsePdf(String rel, Path file) throws IOException {
        long start = System.currentTimeMillis();
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            int pageCount = pdf.getNumberOfPages();
            List<DocumentModel.Page> pages = new ArrayList<>(pageCount);
            for (int i = 1; i <= pageCount; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(pdf);
                pages.add(new DocumentModel.Page(i, text));
            }
            List<DocumentModel.OutlineEntry> outline = pdfOutline(pdf, pageCount);
            log.debug("Parsed PDF {} ({} pages) in {} ms", rel, pageCount,
                    System.currentTimeMillis() - start);
            return new DocumentModel(rel, DocumentModel.Format.PDF, pages, outline,
                    pdfSections(pages, outline));
        }
    }

    /**
     * Flat sections from PDF bookmarks — page-granular: each section's content
     * is the text of the pages from its bookmark up to (excluding) the next
     * bookmark's page. No bookmarks → one section with the whole text.
     */
    private static List<DocumentModel.Section> pdfSections(List<DocumentModel.Page> pages,
                                                           List<DocumentModel.OutlineEntry> outline) {
        List<DocumentModel.Section> sections = new ArrayList<>();
        if (outline.isEmpty()) {
            StringBuilder all = new StringBuilder();
            pages.forEach(p -> all.append(p.text()).append('\n'));
            sections.add(new DocumentModel.Section(1, 0, "", all.toString().strip()));
            return sections;
        }
        int index = 1;
        if (outline.get(0).startPage() > 1) {
            sections.add(new DocumentModel.Section(index++, 0, "",
                    pageRangeText(pages, 1, outline.get(0).startPage() - 1)));
        }
        for (int i = 0; i < outline.size(); i++) {
            DocumentModel.OutlineEntry e = outline.get(i);
            int end = i + 1 < outline.size()
                    ? Math.max(e.startPage(), outline.get(i + 1).startPage() - 1)
                    : pages.size();
            sections.add(new DocumentModel.Section(index++, e.level(), e.title(),
                    pageRangeText(pages, e.startPage(), end)));
        }
        return sections;
    }

    private static String pageRangeText(List<DocumentModel.Page> pages, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (DocumentModel.Page p : pages) {
            if (p.number() >= from && p.number() <= to) {
                sb.append(p.text()).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** Extract bookmarks. Returns empty list if the PDF has none. */
    private List<DocumentModel.OutlineEntry> pdfOutline(PDDocument pdf, int totalPages) {
        List<DocumentModel.OutlineEntry> out = new ArrayList<>();
        var doc = pdf.getDocumentCatalog().getDocumentOutline();
        if (doc == null) return out;
        // First pass: collect (level, title, startPage) by walking the outline tree.
        collectOutline(doc, 1, pdf, out);
        // Second pass: derive endPage for each entry from the next entry at same-or-lower level.
        for (int i = 0; i < out.size(); i++) {
            DocumentModel.OutlineEntry e = out.get(i);
            int end = totalPages;
            for (int j = i + 1; j < out.size(); j++) {
                if (out.get(j).level() <= e.level()) {
                    end = Math.max(e.startPage(), out.get(j).startPage() - 1);
                    break;
                }
            }
            out.set(i, new DocumentModel.OutlineEntry(e.level(), e.title(), e.startPage(), end));
        }
        return out;
    }

    private void collectOutline(PDOutlineNode node, int level, PDDocument pdf,
                                 List<DocumentModel.OutlineEntry> out) {
        PDOutlineItem item = node.getFirstChild();
        while (item != null) {
            int page = 1;
            try {
                PDPage dest = item.findDestinationPage(pdf);
                if (dest != null) {
                    page = pdf.getPages().indexOf(dest) + 1;
                }
            } catch (IOException e) {
                log.debug("PDF outline: failed to resolve destination for '{}': {}", item.getTitle(), e.getMessage());
            }
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                out.add(new DocumentModel.OutlineEntry(level, title.trim(), page, page));
            }
            collectOutline(item, level + 1, pdf, out);
            item = item.getNextSibling();
        }
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────

    /**
     * Word has no real "pages" in the source XML — the page count depends on
     * rendering. We synthesise pseudo-pages from heading-bound chunks of
     * roughly {@link #CHARS_PER_SYNTHETIC_PAGE} characters each.
     * <p>
     * We iterate {@link XWPFDocument#getBodyElements()} rather than
     * {@code getParagraphs()} so that text inside tables is captured too —
     * many CVs and contracts use tables for layout, and the simpler API would
     * miss the bulk of their content.
     */
    private DocumentModel parseDocx(String rel, Path file) throws IOException {
        long start = System.currentTimeMillis();
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {
            // Collect (text, headingLevel) pairs in document order — paragraphs
            // and tables interleaved.
            List<Block> blocks = new ArrayList<>();
            collectDocxBlocks(doc.getBodyElements(), blocks);
            // Documents without a single style-based heading fall back to the
            // direct-formatting heuristic (bold + larger than the body text).
            if (blocks.stream().noneMatch(b -> b.headingLevel() > 0)) {
                blocks = applyHeadingHeuristic(blocks, defaultFontSizePt(doc));
            }
            // Build pseudo-pages by grouping blocks until we hit the size threshold.
            // Track (block-index → page) so we can compute outline page numbers.
            List<DocumentModel.Page> pages = new ArrayList<>();
            int[] blockToPage = new int[blocks.size()];
            StringBuilder current = new StringBuilder();
            int pageNum = 1;
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                if (current.length() + b.text().length() + 1 > CHARS_PER_SYNTHETIC_PAGE
                        && current.length() > 0) {
                    pages.add(new DocumentModel.Page(pageNum, current.toString()));
                    pageNum++;
                    current.setLength(0);
                }
                if (current.length() > 0) current.append('\n');
                current.append(b.text());
                blockToPage[i] = pageNum;
            }
            if (current.length() > 0) {
                pages.add(new DocumentModel.Page(pageNum, current.toString()));
            }
            // Outline = headings.
            List<DocumentModel.OutlineEntry> outline = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                if (b.headingLevel() > 0 && !b.text().isBlank()) {
                    outline.add(new DocumentModel.OutlineEntry(
                            b.headingLevel(), b.text().trim(), blockToPage[i], blockToPage[i]));
                }
            }
            // Derive endPage for each outline entry from the next same-or-shallower one.
            int total = pages.size();
            for (int i = 0; i < outline.size(); i++) {
                DocumentModel.OutlineEntry e = outline.get(i);
                int end = total;
                for (int j = i + 1; j < outline.size(); j++) {
                    if (outline.get(j).level() <= e.level()) {
                        end = Math.max(e.startPage(), outline.get(j).startPage() - 1);
                        break;
                    }
                }
                outline.set(i, new DocumentModel.OutlineEntry(e.level(), e.title(), e.startPage(), end));
            }
            log.debug("Parsed DOCX {} ({} blocks → {} pseudo-pages, {} headings) in {} ms",
                    rel, blocks.size(), pages.size(), outline.size(),
                    System.currentTimeMillis() - start);
            return new DocumentModel(rel, DocumentModel.Format.DOCX, pages, outline,
                    docxSections(blocks));
        }
    }

    /**
     * Flat sections from the block stream — exact boundaries: each heading
     * starts a new section, its content is every following block up to the
     * next heading (any level). Text before the first heading becomes an
     * untitled section.
     */
    private static List<DocumentModel.Section> docxSections(List<Block> blocks) {
        List<DocumentModel.Section> sections = new ArrayList<>();
        int index = 1;
        int level = 0;
        String title = "";
        StringBuilder content = new StringBuilder();
        boolean sawAnything = false;
        for (Block b : blocks) {
            if (b.headingLevel() > 0 && !b.text().isBlank()) {
                if (sawAnything && (content.length() > 0 || !title.isEmpty())) {
                    sections.add(new DocumentModel.Section(index++, level, title, content.toString().strip()));
                }
                level = b.headingLevel();
                title = b.text().strip();
                content.setLength(0);
                sawAnything = true;
            } else {
                if (content.length() > 0) content.append('\n');
                content.append(b.text());
                if (!b.text().isBlank()) sawAnything = true;
            }
        }
        if (sawAnything && (content.length() > 0 || !title.isEmpty())) {
            sections.add(new DocumentModel.Section(index, level, title, content.toString().strip()));
        }
        if (sections.isEmpty()) {
            sections.add(new DocumentModel.Section(1, 0, "", ""));
        }
        return sections;
    }

    /**
     * Walks the document body elements in order. Plain paragraphs become a single
     * Block; tables are flattened by joining cell text row-by-row, then emitted
     * as a single block (with heading level 0). Recurses into nested tables.
     */
    private static void collectDocxBlocks(List<IBodyElement> elements, List<Block> out) {
        for (IBodyElement el : elements) {
            if (el instanceof XWPFParagraph p) {
                String text = p.getText();
                if (text == null) text = "";
                out.add(new Block(text, headingLevelOf(p), isAllBold(p), maxFontSizePt(p)));
                // Text boxes anchor inside a run's drawing; POI's paragraph text
                // does not include their content, so pull it out explicitly.
                collectTextBoxBlocks(p, out);
            } else if (el instanceof XWPFTable t) {
                StringBuilder tableText = new StringBuilder();
                for (XWPFTableRow row : t.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        // Recurse — cells can contain paragraphs and nested tables.
                        List<Block> cellBlocks = new ArrayList<>();
                        collectDocxBlocks(cell.getBodyElements(), cellBlocks);
                        for (int i = 0; i < cellBlocks.size(); i++) {
                            if (i > 0) line.append(' ');
                            line.append(cellBlocks.get(i).text());
                        }
                        line.append('\t');  // tab-separate cells
                    }
                    if (line.length() > 0) {
                        tableText.append(line.toString().stripTrailing()).append('\n');
                    }
                }
                if (tableText.length() > 0) {
                    out.add(new Block(tableText.toString().stripTrailing(), 0, false, 0));
                }
            }
        }
    }

    /**
     * Emits the paragraphs inside any text boxes anchored in {@code p} —
     * report templates often put whole sections into text boxes, which
     * {@code getBodyElements()} does not descend into. Content inside an
     * {@code mc:Fallback} is skipped: AlternateContent carries the same text
     * box twice (drawing + legacy pict), and we want it once.
     */
    private static void collectTextBoxBlocks(XWPFParagraph p, List<Block> out) {
        try (org.apache.xmlbeans.XmlCursor cursor = p.getCTP().newCursor()) {
            cursor.selectPath(
                    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' "
                    + ".//w:txbxContent/w:p");
            while (cursor.toNextSelection()) {
                org.apache.xmlbeans.XmlObject obj = cursor.getObject();
                if (insideFallback(obj)) continue;
                // Inside a drawing the schema doesn't type these as CTP —
                // the cursor yields XmlAnyType, so re-parse the element.
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp =
                        obj instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP typed
                                ? typed
                                : org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP.Factory
                                        .parse(obj.xmlText());
                XWPFParagraph boxPara = new XWPFParagraph(ctp, p.getBody());
                String text = boxPara.getText();
                if (text == null || text.isBlank()) continue;
                out.add(new Block(text, headingLevelOf(boxPara), isAllBold(boxPara), maxFontSizePt(boxPara)));
            }
        } catch (Exception e) {
            log.debug("text-box extraction failed for a paragraph: {}", e.getMessage());
        }
    }

    /** True when the element sits inside an {@code mc:Fallback} (AlternateContent duplicate). */
    private static boolean insideFallback(org.apache.xmlbeans.XmlObject obj) {
        try (org.apache.xmlbeans.XmlCursor c = obj.newCursor()) {
            while (c.toParent()) {
                var name = c.getName();
                if (name != null && "Fallback".equals(name.getLocalPart())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the Word heading level (1..9) of a paragraph, or 0 if not a
     * heading. Checks, in order: an explicit outline level on the paragraph,
     * the style id ("Heading1", "Überschrift1", …), the style definition's
     * outline level, and the style's display name — real-world documents use
     * any of these.
     */
    private static int headingLevelOf(XWPFParagraph p) {
        var ppr = p.getCTP().getPPr();
        if (ppr != null && ppr.isSetOutlineLvl() && ppr.getOutlineLvl().getVal() != null) {
            return ppr.getOutlineLvl().getVal().intValue() + 1;
        }
        String styleId = p.getStyle();
        if (styleId == null) return 0;
        // Style IDs in Word are typically "Heading1", "Heading2", … or "Überschrift1".
        Matcher m = HEADING_PATTERN.matcher(styleId);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        try {
            var styles = p.getDocument().getStyles();
            var style = styles == null ? null : styles.getStyle(styleId);
            if (style != null) {
                var stylePpr = style.getCTStyle().getPPr();
                if (stylePpr != null && stylePpr.isSetOutlineLvl() && stylePpr.getOutlineLvl().getVal() != null) {
                    return stylePpr.getOutlineLvl().getVal().intValue() + 1;
                }
                String name = style.getName();
                if (name != null) {
                    Matcher n = HEADING_PATTERN.matcher(name);
                    if (n.find()) {
                        return Integer.parseInt(n.group(1));
                    }
                }
            }
        } catch (Exception ignored) {
            // styles part can be missing or malformed — not a heading then
        }
        return 0;
    }

    /**
     * True when the paragraph has visible text and every text run is bold —
     * counting bold set on the paragraph mark (direct paragraph formatting)
     * as bold for runs that don't say otherwise.
     */
    private static boolean isAllBold(XWPFParagraph p) {
        boolean markBold = paragraphMarkBold(p);
        boolean any = false;
        for (var run : p.getRuns()) {
            String text = run.text();
            if (text == null || text.isBlank()) continue;
            any = true;
            if (!run.isBold() && !markBold) return false;
        }
        return any;
    }

    /**
     * The paragraph's largest explicit font size in points — from its runs,
     * falling back to the paragraph mark's size (direct paragraph formatting).
     * 0 when nothing explicit is set.
     */
    private static double maxFontSizePt(XWPFParagraph p) {
        double max = 0;
        for (var run : p.getRuns()) {
            Double size = run.getFontSizeAsDouble();
            if (size != null) max = Math.max(max, size);
        }
        if (max == 0) {
            max = paragraphMarkFontSizePt(p);
        }
        return max;
    }

    /** Bold flag on the paragraph mark's run properties (w:pPr/w:rPr/w:b), if present. */
    private static boolean paragraphMarkBold(XWPFParagraph p) {
        try {
            var ppr = p.getCTP().getPPr();
            var rpr = ppr == null ? null : ppr.getRPr();
            if (rpr != null && rpr.sizeOfBArray() > 0) {
                var b = rpr.getBArray(0);
                return !b.isSetVal() || org.apache.poi.ooxml.util.POIXMLUnits.parseOnOff(b.xgetVal());
            }
        } catch (Exception ignored) {
            // malformed properties — treat as not bold
        }
        return false;
    }

    /** Font size on the paragraph mark's run properties (w:pPr/w:rPr/w:sz, half-points), or 0. */
    private static double paragraphMarkFontSizePt(XWPFParagraph p) {
        try {
            var ppr = p.getCTP().getPPr();
            var rpr = ppr == null ? null : ppr.getRPr();
            if (rpr != null && rpr.sizeOfSzArray() > 0) {
                var val = rpr.getSzArray(0).getVal();
                if (val instanceof java.math.BigInteger bi) {
                    return bi.doubleValue() / 2.0;
                }
                return Double.parseDouble(String.valueOf(val)) / 2.0;
            }
        } catch (Exception ignored) {
            // malformed properties — no explicit size then
        }
        return 0;
    }

    /** The document's default run font size in points (fallback: 11pt). */
    private static double defaultFontSizePt(XWPFDocument doc) {
        try {
            var styles = doc.getStyles();
            var defaultRun = styles == null ? null : styles.getDefaultRunStyle();
            Double size = defaultRun == null ? null : defaultRun.getFontSizeAsDouble();
            return size != null && size > 0 ? size : 11.0;
        } catch (Exception e) {
            return 11.0;
        }
    }

    private static final Pattern HEADING_PATTERN = Pattern.compile("(?i)(?:Heading|Überschrift)\\s*(\\d+)");

    private record Block(String text, int headingLevel, boolean bold, double fontSizePt) {

        Block withHeadingLevel(int level) {
            return new Block(text, level, bold, fontSizePt);
        }
    }

    /**
     * Fallback for documents that use direct formatting instead of heading
     * styles (many real-world templates): when no style-based heading exists
     * at all, treat short, fully bold paragraphs with a font size above the
     * document default as headings — the largest size becomes level 1, the
     * next level 2, and so on.
     */
    private static List<Block> applyHeadingHeuristic(List<Block> blocks, double defaultFontPt) {
        java.util.TreeSet<Double> headingSizes = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        for (Block b : blocks) {
            if (isHeuristicHeading(b, defaultFontPt)) {
                headingSizes.add(b.fontSizePt());
            }
        }
        if (headingSizes.isEmpty()) {
            return blocks;
        }
        List<Double> sizes = new ArrayList<>(headingSizes);
        List<Block> out = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            if (isHeuristicHeading(b, defaultFontPt)) {
                int level = Math.min(sizes.indexOf(b.fontSizePt()) + 1, 6);
                out.add(b.withHeadingLevel(level));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private static boolean isHeuristicHeading(Block b, double defaultFontPt) {
        String text = b.text().strip();
        return b.bold()
                && b.fontSizePt() > defaultFontPt + 0.5
                && !text.isEmpty()
                && text.length() <= 150;
    }

    // ── plain text ───────────────────────────────────────────────────────────

    private DocumentModel parseText(String rel, Path file) throws IOException {
        String content = Files.readString(file);
        // Synthesise pseudo-pages by splitting on chunk size.
        List<DocumentModel.Page> pages = new ArrayList<>();
        int n = content.length();
        int pageNum = 1;
        for (int i = 0; i < n; i += CHARS_PER_SYNTHETIC_PAGE) {
            int end = Math.min(n, i + CHARS_PER_SYNTHETIC_PAGE);
            pages.add(new DocumentModel.Page(pageNum++, content.substring(i, end)));
        }
        if (pages.isEmpty()) pages.add(new DocumentModel.Page(1, ""));
        return new DocumentModel(rel, DocumentModel.Format.TEXT, pages, List.of(),
                textSections(content));
    }

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** Flat sections from markdown-style headings; no headings → one untitled section. */
    private static List<DocumentModel.Section> textSections(String content) {
        List<DocumentModel.Section> sections = new ArrayList<>();
        int index = 1;
        int level = 0;
        String title = "";
        StringBuilder body = new StringBuilder();
        boolean sawHeading = false;
        for (String line : content.split("\r?\n", -1)) {
            Matcher m = MARKDOWN_HEADING.matcher(line);
            if (m.matches()) {
                if (sawHeading || body.toString().strip().length() > 0) {
                    sections.add(new DocumentModel.Section(index++, level, title, body.toString().strip()));
                }
                level = m.group(1).length();
                title = m.group(2).strip();
                body.setLength(0);
                sawHeading = true;
            } else {
                body.append(line).append('\n');
            }
        }
        if (sawHeading || sections.isEmpty()) {
            sections.add(new DocumentModel.Section(index, level, title, body.toString().strip()));
        }
        return sections;
    }

    // ── cache eviction ───────────────────────────────────────────────────────

    private void evictIfFull() {
        if (cache.size() <= MAX_CACHE_ENTRIES) return;
        // Remove least-recently-accessed entry.
        cache.entrySet().stream()
                .min((a, b) -> Long.compare(a.getValue().lastAccess, b.getValue().lastAccess))
                .ifPresent(e -> cache.remove(e.getKey()));
    }

    private static class CacheEntry {
        final DocumentModel model;
        volatile long lastAccess;
        CacheEntry(DocumentModel model, long lastAccess) {
            this.model = model;
            this.lastAccess = lastAccess;
        }
    }
}
