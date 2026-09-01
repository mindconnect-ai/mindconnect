package ai.mindconnect.agent.tools.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the passages of a Markdown document that bear on a question.
 * <p>
 * The alternative — cutting the document at a fixed character count — is blind:
 * on a product page the price usually sits in the last third, so a head-truncated
 * page answers "not found" and provokes a retry. This scores passages against the
 * caller's question instead and returns the best ones in document order, which
 * both shrinks the result and makes what survives the part that was asked about.
 * <p>
 * Lexical only (BM25-style tf-idf over the document's own passages) — no model
 * call, no network, microseconds. That is the point: the expensive reader
 * downstream should receive a small input, not share the work of producing it.
 */
public final class RelevantExcerpts {

    /** Default character budget for the selected passages. */
    public static final int DEFAULT_BUDGET_CHARS = 6_000;

    /** Passages are grown to roughly this size before being split at a blank line. */
    private static final int TARGET_CHUNK_CHARS = 1_200;

    /**
     * A passage is split here even without a blank line. Reference lists and
     * navigation menus run for tens of thousands of characters without one, and
     * a single passage that large both defeats the ranking (it accumulates every
     * term in the document) and blows the budget on its own.
     */
    private static final int MAX_CHUNK_CHARS = 2_400;

    /**
     * Terms at least this long also match by prefix, so "Preis" hits "Preise"
     * and "Preisvergleich". German compounds make this worth more than exact
     * matching; shorter terms are too ambiguous to extend.
     */
    private static final int MIN_PREFIX_LEN = 4;

    /**
     * How far the text may fall SHORT of the query term and still match —
     * enough for an inflection ("threads" asked, "thread" written), not enough
     * for a different word. The other direction is unbounded on purpose: that
     * is the compound case, where the page legitimately says far more than the
     * query did.
     */
    private static final int MAX_INFLECTION_GAP = 3;

    /** Terms carried by a heading describe everything beneath it. */
    private static final double HEADING_WEIGHT = 1.5;

    /** BM25 term-frequency saturation. */
    private static final double TF_SATURATION = 1.2;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    /** Words too common to discriminate; dropped from the query, never from the text. */
    private static final Set<String> STOPWORDS = Set.of(
            // English
            "the", "a", "an", "of", "for", "and", "or", "to", "in", "on", "at", "is", "are",
            "was", "were", "be", "what", "which", "how", "much", "many", "does", "do", "did",
            // German — "in", "an" and "was" are shared with the list above
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "eines", "und",
            "oder", "von", "vom", "für", "mit", "auf", "im", "am", "zu", "zum", "zur",
            "ist", "sind", "war", "waren", "wie", "welche", "welcher", "viel");

    private RelevantExcerpts() {
    }

    /**
     * Returns the passages of {@code markdown} most relevant to {@code query},
     * in document order, separated by an elision marker where passages are not
     * adjacent. Returns the document unchanged when it already fits the budget
     * or when the query carries no usable terms — in both cases there is nothing
     * to gain and something to lose by cutting.
     */
    public static String select(String markdown, String query, int budgetChars) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        if (markdown.length() <= budgetChars) return markdown;

        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) return DocumentParser.truncate(markdown, budgetChars);

        List<Chunk> chunks = chunk(markdown);
        if (chunks.size() <= 1) return DocumentParser.truncate(markdown, budgetChars);

        double[] scores = score(chunks, terms);
        List<Chunk> picked = pick(chunks, scores, budgetChars);
        if (picked.isEmpty()) return DocumentParser.truncate(markdown, budgetChars);

        // The budget is a ceiling, not a target: the caller sized it against a
        // context window, and a result that overruns it defeats the whole point.
        return DocumentParser.truncate(render(picked, chunks.size()), budgetChars);
    }

    public static String select(String markdown, String query) {
        return select(markdown, query, DEFAULT_BUDGET_CHARS);
    }

    // ── passages ─────────────────────────────────────────────────────────────

    /**
     * A passage: the text plus the heading it sits under. The heading travels
     * with the passage so a selected fragment still says what it is about, even
     * when the heading itself was not selected.
     */
    private record Chunk(int index, String heading, String text) {
        int length() {
            return text.length() + (heading.isEmpty() ? 0 : heading.length() + 1);
        }
    }

    private static List<Chunk> chunk(String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String heading = "";
        int index = 0;

        for (String line : markdown.split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                index = flush(chunks, buffer, heading, index);
                heading = m.group(2).strip();
                continue;
            }
            // Split at a paragraph break once the passage is big enough — never
            // mid-sentence, so every excerpt stays quotable.
            if (line.isBlank() && buffer.length() >= TARGET_CHUNK_CHARS) {
                index = flush(chunks, buffer, heading, index);
                continue;
            }
            buffer.append(line).append('\n');
            // Blank lines can simply never come (a long reference list): split at
            // the line boundary rather than let one passage swallow the document.
            if (buffer.length() >= MAX_CHUNK_CHARS) {
                index = flush(chunks, buffer, heading, index);
            }
        }
        flush(chunks, buffer, heading, index);
        return chunks;
    }

    private static int flush(List<Chunk> chunks, StringBuilder buffer, String heading, int index) {
        String text = buffer.toString().strip();
        buffer.setLength(0);
        if (text.isEmpty()) return index;
        chunks.add(new Chunk(index, heading, text));
        return index + 1;
    }

    // ── scoring ──────────────────────────────────────────────────────────────

    private static List<String> queryTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String term = m.group();
            if (term.length() > 1 && !STOPWORDS.contains(term)) terms.add(term);
        }
        return List.copyOf(terms);
    }

    private static double[] score(List<Chunk> chunks, List<String> terms) {
        List<String> headings = new ArrayList<>(chunks.size());
        List<String> bodies = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            headings.add(c.heading().toLowerCase(Locale.ROOT));
            bodies.add(c.text().toLowerCase(Locale.ROOT));
        }

        Map<String, Double> idf = new HashMap<>();
        for (String term : terms) {
            int df = 0;
            for (int i = 0; i < chunks.size(); i++) {
                if (count(bodies.get(i), term) > 0 || count(headings.get(i), term) > 0) df++;
            }
            // A term present in every passage separates nothing; one present in
            // a handful is what makes those passages worth reading.
            idf.put(term, Math.log(1.0 + (chunks.size() - df + 0.5) / (df + 0.5)));
        }

        double[] scores = new double[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            double sum = 0;
            for (String term : terms) {
                double tf = count(bodies.get(i), term)
                        + HEADING_WEIGHT * count(headings.get(i), term);
                if (tf == 0) continue;
                sum += idf.get(term) * (tf / (tf + TF_SATURATION));
            }
            scores[i] = sum * (1.0 - LINK_PENALTY * linkDensity(chunks.get(i).text()));
        }
        return scores;
    }

    /** How far a passage made of pure link markup is pushed down the ranking. */
    private static final double LINK_PENALTY = 0.8;

    private static final Pattern MD_LINK = Pattern.compile("\\[[^\\]]*\\]\\([^)]*\\)");

    /**
     * Fraction of the passage taken up by Markdown links. Reference lists,
     * navigation and footers score well on term frequency alone — they mention
     * everything the page mentions — while carrying almost no prose to quote.
     * Link density is what separates them from the passage that answers.
     */
    private static double linkDensity(String text) {
        if (text.isEmpty()) return 0;
        int linkChars = 0;
        Matcher m = MD_LINK.matcher(text);
        while (m.find()) linkChars += m.end() - m.start();
        return Math.min(1.0, (double) linkChars / text.length());
    }

    /**
     * Occurrences of {@code term} in {@code lowerText}, matching whole words plus
     * near relatives — see {@link #matches(String, String)}.
     */
    private static int count(String lowerText, String term) {
        if (lowerText.isEmpty()) return 0;
        int hits = 0;
        Matcher m = WORD.matcher(lowerText);
        while (m.find()) {
            if (matches(m.group(), term)) hits++;
        }
        return hits;
    }

    /**
     * Whether a word on the page answers to a query term. Exact, or a prefix
     * relation in either direction — asking for "Preis" must find
     * "Preisvergleich", and asking for "threads" must find "thread". Without
     * the second direction a plural in the question silently misses a singular
     * on the page, which is how a well-written query ends up ranking the
     * navigation menu above the paragraph that answers it.
     */
    private static boolean matches(String word, String term) {
        if (word.equals(term)) return true;
        if (term.length() >= MIN_PREFIX_LEN && word.startsWith(term)) return true;
        return word.length() >= MIN_PREFIX_LEN
                && term.startsWith(word)
                && term.length() - word.length() <= MAX_INFLECTION_GAP;
    }

    // ── selection ────────────────────────────────────────────────────────────

    private static List<Chunk> pick(List<Chunk> chunks, double[] scores, int budgetChars) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (scores[i] > 0) order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());

        List<Chunk> picked = new ArrayList<>();
        int used = 0;
        for (int i : order) {
            int cost = chunks.get(i).length() + SEPARATOR.length();
            // The best-ranked passage is taken even when it alone overruns the
            // budget — the final render truncates it. Dropping it instead would
            // spend the whole budget on the runners-up, which is how a page ends
            // up represented by its navigation menu.
            if (used + cost > budgetChars && !picked.isEmpty()) continue;
            picked.add(chunks.get(i));
            used += cost;
        }
        picked.sort(Comparator.comparingInt(Chunk::index));
        return picked;
    }

    private static final String SEPARATOR = "\n\n[…]\n\n";

    private static String render(List<Chunk> picked, int totalChunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Relevance-filtered: ").append(picked.size()).append(" of ")
                .append(totalChunks).append(" passages selected for the query. ")
                .append("Omitted passages are marked […]. Re-read without a query to see the full page.]\n\n");

        int previous = -1;
        String lastHeading = null;
        for (Chunk c : picked) {
            if (previous >= 0 && c.index() != previous + 1) sb.append(SEPARATOR);
            else if (previous >= 0) sb.append("\n\n");
            if (!c.heading().isEmpty() && !c.heading().equals(lastHeading)) {
                sb.append("## ").append(c.heading()).append("\n\n");
                lastHeading = c.heading();
            }
            sb.append(c.text());
            previous = c.index();
        }
        return sb.toString();
    }
}
