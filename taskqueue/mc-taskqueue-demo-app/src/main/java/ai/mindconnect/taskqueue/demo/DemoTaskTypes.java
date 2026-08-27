package ai.mindconnect.taskqueue.demo;

import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.demo.worker.CountdownWorker;
import ai.mindconnect.taskqueue.demo.worker.ScrapePageWorker;
import ai.mindconnect.ui.model.UiField;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The task types the demo offers. Parameter field ids carry a {@code p_}
 * prefix so they cannot collide with the {@code taskType} select in the flat
 * form payload; {@code toSubmission} reads the prefixed values and builds the
 * unprefixed task payload.
 */
@Component
public class DemoTaskTypes {

    private final Path outputRoot;
    private final int maxLinksPerPageDefault;
    private final List<DemoTaskType> types;

    public DemoTaskTypes(@Value("${mindconnect.taskqueue-demo.output-root:data/output}") String outputRoot,
                         @Value("${mindconnect.taskqueue-demo.max-links-per-page-default:5}") int maxLinksPerPageDefault) {
        this.outputRoot = Path.of(outputRoot).toAbsolutePath().normalize();
        this.maxLinksPerPageDefault = maxLinksPerPageDefault;
        this.types = List.of(crawlSite(), scrapePage(), countdown());
    }

    public List<DemoTaskType> all() {
        return types;
    }

    public Optional<DemoTaskType> byId(String id) {
        return types.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    private DemoTaskType crawlSite() {
        return new DemoTaskType("crawl", "Crawl website",
                "Follows links from a start URL and stores every page as Markdown. "
                        + "Every page spawns the pages it links to, so the task tree "
                        + "IS the link tree.",
                () -> List.of(
                        UiField.text("p_startUrl", "Start URL", "https://example.com")
                                .asEditable().asRequired(),
                        UiField.number("p_depth", "Link depth", 1)
                                .asEditable().asRequired().min("0")
                                .hint("0 = only the start page"),
                        UiField.text("p_outputDir", "Output directory", "crawl")
                                .asEditable().asRequired()
                                .hint("relative paths land under " + outputRoot),
                        UiField.number("p_maxLinksPerPage", "Max links per page", maxLinksPerPageDefault)
                                .asEditable().min("1")
                                .hint("how many links EACH page follows — the total "
                                        + "grows by this factor per depth level"),
                        UiField.bool("p_sameHostOnly", "Same host only", true)
                                .asEditable(),
                        UiField.number("p_sleepMs", "Sleep per page (ms)", 0)
                                .asEditable().min("0")
                                .hint("slows every page down — makes the distribution watchable")),
                values -> {
                    String url = httpUrl(str(values, "p_startUrl"));
                    var payload = new HashMap<String, Object>();
                    payload.put("url", url);
                    payload.put("title", "crawl " + url);
                    payload.put("outputDir", outputDir(values));
                    payload.put("depth", 0);                  // where this page sits
                    payload.put("maxDepth", intVal(values, "p_depth", 1));
                    payload.put("maxLinksPerPage",
                            intVal(values, "p_maxLinksPerPage", maxLinksPerPageDefault));
                    payload.put("sameHostOnly", boolVal(values, "p_sameHostOnly"));
                    payload.put("sleepMs", intVal(values, "p_sleepMs", 0));
                    return TaskSubmission.of(ScrapePageWorker.TYPE, payload);
                });
    }

    private DemoTaskType scrapePage() {
        return new DemoTaskType("page", "Scrape single page",
                "The very same worker with nothing to follow: one page, one Markdown file.",
                () -> List.of(
                        UiField.text("p_url", "URL", "https://example.com")
                                .asEditable().asRequired(),
                        UiField.text("p_outputDir", "Output directory", "pages")
                                .asEditable().asRequired()
                                .hint("relative paths land under " + outputRoot),
                        UiField.number("p_sleepMs", "Sleep (ms)", 0)
                                .asEditable().min("0")),
                values -> {
                    String url = httpUrl(str(values, "p_url"));
                    return TaskSubmission.of(ScrapePageWorker.TYPE, Map.of(
                            "url", url,
                            "title", url,
                            "outputDir", outputDir(values),
                            "depth", 0,
                            "maxDepth", 0,
                            "sleepMs", intVal(values, "p_sleepMs", 0)));
                });
    }

    private DemoTaskType countdown() {
        return new DemoTaskType(CountdownWorker.TYPE, "Countdown",
                "Counts n steps with a delay, streaming each step as task state. "
                        + "Set a fail step to watch the retry arc.",
                () -> List.of(
                        UiField.number("p_steps", "Steps", 10)
                                .asEditable().asRequired().min("1"),
                        UiField.number("p_delayMs", "Delay per step (ms)", 500)
                                .asEditable().min("0"),
                        UiField.number("p_failOnStep", "Fail on step", null)
                                .asEditable().hint("empty = never; first attempt fails there")),
                values -> {
                    var payload = new HashMap<String, Object>();
                    payload.put("steps", intVal(values, "p_steps", 10));
                    payload.put("delayMs", intVal(values, "p_delayMs", 500));
                    payload.put("failOnStep", intVal(values, "p_failOnStep", -1));
                    return TaskSubmission.of(CountdownWorker.TYPE, payload).withMaxAttempts(3);
                });
    }

    private String outputDir(Map<String, Object> values) {
        String raw = str(values, "p_outputDir");
        Path resolved = Path.of(raw);
        if (!resolved.isAbsolute()) {
            resolved = outputRoot.resolve(raw);
        }
        resolved = resolved.toAbsolutePath().normalize();
        if (!resolved.isAbsolute() || raw.contains("..")) {
            throw new IllegalArgumentException("Output directory must not contain '..': " + raw);
        }
        return resolved.toString();
    }

    private static String httpUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid URL: " + value);
        }
        if (uri.getHost() == null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("URL must be http(s) with a host: " + value);
        }
        return value;
    }

    private static String str(Map<String, Object> values, String key) {
        Object v = values.get(key);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Missing value: " + key.substring(2));
        }
        return s;
    }

    private static int intVal(Map<String, Object> values, String key, int fallback) {
        Object v = values.get(key);
        if (v == null || String.valueOf(v).isBlank()) return fallback;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: " + key.substring(2) + "=" + v);
        }
    }

    private static boolean boolVal(Map<String, Object> values, String key) {
        Object v = values.get(key);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
}
