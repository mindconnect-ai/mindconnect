package ai.mindconnect.taskqueue.demo.worker;

import ai.mindconnect.taskqueue.SharedStateStore;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskWorker;
import ai.mindconnect.webscraper.BaseWebScraperOptions;
import ai.mindconnect.webscraper.ScrapedLink;
import ai.mindconnect.webscraper.ScrapedPage;
import ai.mindconnect.webscraper.jsoup.JsoupSingleWebPageScraper;
import ai.mindconnect.webscraper.jsoup.JsoupWebScraperContext;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scrapes ONE page, writes it as Markdown — and follows its links by spawning
 * one task of its own kind per link. A crawl is therefore not a plan someone
 * draws up front: it is this worker, applied to itself, and the task tree that
 * comes out IS the link tree.
 *
 * <p>Nothing coordinates it. The two things a central coordinator used to
 * provide come from the queue and one shared map instead:
 * <ul>
 *   <li><b>When is the crawl done?</b> A page that spawned children parks with
 *       {@code suspendUntilChildren()}, so "finished" propagates from the
 *       leaves up and the ROOT page completing means the whole crawl is over.</li>
 *   <li><b>Who scrapes a URL two pages both link to?</b> Whoever claims it
 *       first in the {@link SharedStateStore} — one atomic {@code putIfAbsent}
 *       per URL, which is the visited set without anybody owning it.</li>
 * </ul>
 */
@Component
public class ScrapePageWorker implements TaskWorker {

    public static final String TYPE = "scrape-page";

    private final SharedStateStore shared;

    public ScrapePageWorker(SharedStateStore shared) {
        this.shared = shared;
    }

    @Override
    public TaskOutcome execute(TaskContext ctx) throws Exception {
        Map<String, Object> payload = ctx.task().payload();
        String url = (String) payload.get("url");
        Path outputDir = Path.of((String) payload.get("outputDir"));
        int depth = intVal(payload, "depth", 0);
        int maxDepth = intVal(payload, "maxDepth", 0);
        int maxLinksPerPage = intVal(payload, "maxLinksPerPage", 5);
        boolean sameHostOnly = Boolean.parseBoolean(String.valueOf(payload.get("sameHostOnly")));
        // The page that started the crawl names it; everything below shares that id.
        boolean isRoot = payload.get("crawlId") == null;
        String crawlId = isRoot ? ctx.task().id() : (String) payload.get("crawlId");

        if (ctx.isResumed()) {                       // the pages below me are done
            int below = 0;
            for (TaskRecord child : ctx.children()) {
                below += intVal(child.state(), "subtreePages", 0);
            }
            return finish(ctx, isRoot, crawlId, outputDir, 1 + below);
        }

        if (ctx.cancelRequested()) {
            return TaskOutcome.done("cancelled before start");
        }
        // Demo throttle: slows every page down so the distribution across
        // workers is watchable instead of over in a blink.
        long sleepMs = intVal(payload, "sleepMs", 0);
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }
        shared.putIfAbsent(crawlId, url, ctx.task().id());   // nobody else takes my own url

        var options = new BaseWebScraperOptions();
        options.setUrl(url);
        options.setExtractContent(true);
        options.setExtractLinks(true);
        options.setConvertToMarkdown(true);
        // saveToDirectory stays null: its save function writes the page as
        // JSON — this worker writes the Markdown file itself.

        var page = new ScrapedPage();
        new JsoupSingleWebPageScraper(options, new JsoupWebScraperContext(options), page).executeScrape();
        if (page.getError() != null) {
            throw new IllegalStateException("scrape failed for " + url + ": " + page.getError());
        }

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(safeFileName(url) + ".md");
        String pageTitle = page.getTitle() == null || page.getTitle().isBlank() ? url : page.getTitle();
        Files.writeString(file, "# " + pageTitle + "\n\n" + page.getConvertedTextForAllParts("\n\n"));

        var state = new LinkedHashMap<String, Object>();
        state.put("url", url);
        // "title" would rename the task in the tree (it shows the URL from the
        // payload); the page's own <title> is data, so it gets its own key.
        state.put("pageTitle", pageTitle);
        state.put("outputFile", file.toString());
        state.put("depth", depth);
        state.put("links", page.getLinks().size());

        int spawned = depth < maxDepth
                ? followLinks(ctx, page, url, crawlId, outputDir, depth, maxDepth,
                              maxLinksPerPage, sameHostOnly)
                : 0;
        state.put("spawned", spawned);
        ctx.updateState(state);

        if (spawned > 0) {
            return TaskOutcome.suspendUntilChildren();
        }
        return finish(ctx, isRoot, crawlId, outputDir, 1);
    }

    /** One child per link worth following, in the order the page lists them. */
    private int followLinks(TaskContext ctx, ScrapedPage page, String url, String crawlId,
                            Path outputDir, int depth, int maxDepth,
                            int maxLinksPerPage, boolean sameHostOnly) {
        String host = URI.create(url).getHost();
        int spawned = 0;
        for (ScrapedLink scraped : page.getLinks()) {
            if (spawned >= maxLinksPerPage || ctx.cancelRequested()) break;
            String link = normalize(scraped.getUrl());
            if (link == null) continue;
            if (sameHostOnly && !host.equals(URI.create(link).getHost())) continue;
            // The claim IS the dedup: exactly one page gets to scrape this url.
            if (!shared.putIfAbsent(crawlId, link, ctx.task().id())) continue;

            var childPayload = new HashMap<String, Object>();
            childPayload.put("url", link);
            childPayload.put("title", link);           // names the task in the tree
            childPayload.put("outputDir", outputDir.toString());
            childPayload.put("depth", depth + 1);
            childPayload.put("maxDepth", maxDepth);
            childPayload.put("maxLinksPerPage", maxLinksPerPage);
            childPayload.put("sameHostOnly", sameHostOnly);
            childPayload.put("sleepMs", intVal(ctx.task().payload(), "sleepMs", 0));
            childPayload.put("crawlId", crawlId);
            ctx.submitChild(TYPE, childPayload);
            spawned++;
        }
        return spawned;
    }

    /** Records how big this subtree turned out and, for the root, tidies up. */
    private TaskOutcome finish(TaskContext ctx, boolean isRoot, String crawlId,
                               Path outputDir, int subtreePages) {
        var state = new LinkedHashMap<>(ctx.state());
        state.put("subtreePages", subtreePages);
        ctx.updateState(state);

        if (isRoot) {
            // The crawl is over, so the claims are too — nothing else can know that.
            shared.clear(crawlId);
            return TaskOutcome.done(subtreePages + " pages -> " + outputDir);
        }
        return TaskOutcome.done(String.valueOf(state.get("outputFile")));
    }

    /** Drops fragments and non-http(s) links; returns null for anything unusable. */
    private static String normalize(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) return null;
            int fragment = url.indexOf('#');
            String bare = fragment < 0 ? url : url.substring(0, fragment);
            return bare.isBlank() ? null : bare;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Scheme stripped, unsafe chars flattened, capped in length, hash keeps it unique. */
    static String safeFileName(String url) {
        String name = url.replaceFirst("^https?://", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        return name + "-" + Integer.toHexString(url.hashCode());
    }

    private static int intVal(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
