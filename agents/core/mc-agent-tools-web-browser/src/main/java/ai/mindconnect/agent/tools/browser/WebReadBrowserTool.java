package ai.mindconnect.agent.tools.browser;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.document.HtmlLinkExtractor;
import ai.mindconnect.agent.tools.document.HtmlToMarkdown;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Browser-rendered counterpart to {@code web_read}. Loads {@code url} in
 * headless Chromium, waits for the SPA to settle, then returns the
 * rendered DOM as Markdown — inline {@code [text](url)} links in the
 * body give the agent everything it needs to drill into specifics.
 *
 * <p>Use this when the plain {@code web_read} returns an empty body or a
 * consent banner — modern travel / e-commerce sites are 95% JavaScript,
 * and the static HTML their server hands out is just a loading shell.
 *
 * <pre>
 * URL: ...
 * Title: ...
 *
 * &lt;body markdown with inline [anchor](href) links&gt;
 * </pre>
 *
 * <p>The body is capped at ~20k chars so a heavy SPA doesn't blow the
 * agent's context window.
 */
public class WebReadBrowserTool implements Tool {

    public static final String NAME = "web_read_browser";

    private static final Logger log = LoggerFactory.getLogger(WebReadBrowserTool.class);

    /** Hard ceiling on rendered body text returned to the LLM. */
    private static final int BODY_CHAR_LIMIT = 20_000;
    /**
     * Time we give {@code DOMCONTENTLOADED} — i.e. the initial HTML + first
     * JS pass. This is the minimum we need; everything after is bonus.
     */
    private static final double NAV_TIMEOUT_MS = 20_000;
    /**
     * Extra time we wait for the SPA to hydrate / fetch its data after
     * DCL. Best-effort — if it doesn't settle we just take what's there.
     */
    private static final double SETTLE_TIMEOUT_MS = 8_000;

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "Fetches a URL via a headless browser (JavaScript executed) and returns the rendered "
             + "page as Markdown with inline `[text](url)` links. Use this when web_read returns an "
             + "empty body, a cookie banner, or only the SPA loading shell. Much slower than web_read "
             + "(1-5 seconds per call) — always try web_read first.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of(
                                "type", "string",
                                "description", "The fully-qualified URL to load."
                        ),
                        "createLinkList", Map.of(
                                "type", "boolean",
                                "default", false,
                                "description", "Leave unset (false) for normal reading — "
                                        + "the inline `[text](url)` Markdown links in the rendered "
                                        + "body are sufficient for citing. Only set true when the "
                                        + "caller explicitly needs a verbatim machine-readable list "
                                        + "of every `<a href>` on the page (e.g. crawling all items "
                                        + "from a listing page)."
                        )
                ),
                "required", new String[]{"url"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String url = (String) arguments.get("url");
        if (url == null || url.isBlank()) return "Error: url is required";
        boolean createLinkList = Boolean.TRUE.equals(arguments.get("createLinkList"));

        log.info("web_read_browser url={} createLinkList={}", url, createLinkList);
        long t0 = System.currentTimeMillis();
        Browser browser = PlaywrightHolder.browser();
        // Fresh context per call: isolated cookies, no leak between requests.
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .setLocale("de-CH")
                .setTimezoneId("Europe/Zurich")
                .setViewportSize(1280, 800));
             Page page = newStealthPage(context)) {

            // Block tracking/ads/heavy media at the network layer. tui.ch,
            // booking.com, kayak — these sites pull in 200+ requests, half
            // of them to analytics endpoints that never finish loading and
            // would keep `load` / `networkidle` from ever firing. We don't
            // need that crap to read the body text.
            blockJunkResources(page);

            page.setDefaultTimeout(NAV_TIMEOUT_MS);
            // Stage 1 — DOMCONTENTLOADED only. The HTML is parsed and the
            // initial JS has run; for 95% of pages the article body is in
            // the DOM by this point. We DO NOT wait for `load` because
            // that needs every image/iframe/tracker pixel — on bloated
            // sites those keep loading 30+ seconds and we time out.
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(NAV_TIMEOUT_MS));
            } catch (Exception navEx) {
                // Even DCL failed — likely the server is slow or the URL is bad.
                long durMs = System.currentTimeMillis() - t0;
                log.warn("web_read_browser DCL failed for {} after {} ms: {}",
                        url, durMs, navEx.getMessage());
                return "Error reading page (browser, navigation timeout): " + navEx.getMessage();
            }

            // Stage 2 — best-effort settle. Wait up to SETTLE_TIMEOUT_MS for
            // `networkidle` so SPAs that fetch their content after DCL get
            // a chance to render. If it doesn't settle, we use whatever's
            // in the DOM right now — typically still enough text to work
            // with even if some lazy widgets haven't loaded.
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(SETTLE_TIMEOUT_MS));
            } catch (Exception ignore) {
                log.debug("networkidle timeout for {} — proceeding with current DOM", url);
            }

            String title = page.title();
            Document doc = parseRendered(page);
            String bodyMarkdown = doc == null ? "" : cap(HtmlToMarkdown.convert(doc));

            long durMs = System.currentTimeMillis() - t0;
            // Anti-bot pages are technically a 200 OK with HTML that says
            // "verify you are human". We sniff for the well-known signatures
            // and signal a BLOCKED result so the researcher knows to try
            // another URL instead of treating the captcha banner as content.
            String blockReason = detectBotBlock(title, bodyMarkdown);
            if (blockReason != null) {
                log.info("web_read_browser {} → BLOCKED ({}) in {} ms", url, blockReason, durMs);
                return "BLOCKED: " + blockReason + " for " + url + "\n"
                     + "Hint: try a different URL or a different site — repeated retries with the same URL will keep failing.";
            }
            String linkList = createLinkList && doc != null ? HtmlLinkExtractor.extract(doc) : "";
            log.info("web_read_browser {} → {} chars body, {} ms",
                    url, bodyMarkdown.length(), durMs);
            return format(url, title, bodyMarkdown, linkList);
        } catch (Exception e) {
            long durMs = System.currentTimeMillis() - t0;
            log.warn("web_read_browser failed for {} after {} ms: {}", url, durMs, e.getMessage());
            return "Error reading page (browser): " + e.getMessage();
        }
    }

    /**
     * Blocks resource types and hostnames that bring in nothing useful for
     * text extraction but routinely keep the page in "loading" state for
     * tens of seconds:
     *
     * <ul>
     *   <li><b>image / media / font</b> — visual only; not in the text body.</li>
     *   <li><b>stylesheet</b> — Jsoup doesn't render CSS anyway, and removing
     *       these is the single biggest win for fast settle.</li>
     *   <li>Known tracker / ads / consent CDN hosts — these are the worst
     *       offenders for never-finishing requests on travel/news sites.</li>
     * </ul>
     *
     * Document / script / xhr / fetch requests are <i>not</i> blocked — those
     * are what the SPA needs to actually populate the DOM with text.
     */
    private static void blockJunkResources(Page page) {
        page.route("**/*", route -> {
            String type = route.request().resourceType();
            if ("image".equals(type) || "media".equals(type)
                    || "font".equals(type) || "stylesheet".equals(type)) {
                route.abort();
                return;
            }
            String url = route.request().url();
            if (isTrackerHost(url)) {
                route.abort();
                return;
            }
            route.resume();
        });
    }

    /**
     * Substring match against a small list of well-known tracker / ads /
     * consent / RUM domains. Cheap and surgical — we don't try to be
     * exhaustive, just kill the chronic offenders.
     */
    private static boolean isTrackerHost(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("google-analytics.com")
            || u.contains("googletagmanager.com")
            || u.contains("googletagservices.com")
            || u.contains("doubleclick.net")
            || u.contains("googlesyndication.com")
            || u.contains("facebook.net") || u.contains("facebook.com/tr")
            || u.contains("connect.facebook.net")
            || u.contains("hotjar.com") || u.contains("hotjar.io")
            || u.contains("mouseflow.com")
            || u.contains("optimizely.com")
            || u.contains("segment.io") || u.contains("segment.com")
            || u.contains("mixpanel.com")
            || u.contains("amplitude.com")
            || u.contains("newrelic.com") || u.contains("nr-data.net")
            || u.contains("datadoghq.com") || u.contains("datadoghq-browser-agent")
            || u.contains("sentry.io") || u.contains("sentry-cdn")
            || u.contains("onetrust.com") || u.contains("cookielaw.org")
            || u.contains("trustarc.com")
            || u.contains("usercentrics.eu")
            || u.contains("tealium.com") || u.contains("tealiumiq.com")
            || u.contains("adobedtm.com") || u.contains("adobe.com/b/ss")
            || u.contains("demdex.net") || u.contains("omtrdc.net")
            || u.contains("criteo.com") || u.contains("criteo.net")
            || u.contains("taboola.com") || u.contains("outbrain.com")
            || u.contains("bing.com/bat") || u.contains("clarity.ms")
            || u.contains("linkedin.com/li") || u.contains("ads.linkedin.com")
            || u.contains("pinterest.com/ct") || u.contains("ct.pinterest")
            || u.contains("tiktok.com/i18n/pixel") || u.contains("analytics.tiktok");
    }

    /**
     * Opens a {@link Page} with the {@link StealthScript} pre-injected so
     * its overrides run before any page script (including bot-detection
     * fingerprinters) sees the navigator object.
     */
    private static Page newStealthPage(BrowserContext context) {
        context.addInitScript(StealthScript.SOURCE);
        return context.newPage();
    }

    /**
     * Returns a short reason string if {@code title}/{@code body} match a
     * known anti-bot interstitial; {@code null} when the page looks like
     * real content.
     */
    private static String detectBotBlock(String title, String body) {
        String t = title == null ? "" : title.toLowerCase();
        String b = body == null ? "" : body.toLowerCase();
        // Very short pages that look like a captcha banner: < 800 chars is
        // the typical anti-bot interstitial. Real content pages are way
        // longer, so we only fire the keyword check on small payloads.
        if (b.length() > 1500) return null;
        if (t.contains("bot or not")) return "site asked 'Bot or Not?' (Akamai)";
        if (t.contains("just a moment")) return "Cloudflare 'Just a moment...' challenge";
        if (t.contains("access denied")) return "access denied";
        if (b.contains("verify you are human")) return "human verification prompt";
        if (b.contains("cloudflare") && b.contains("challenge")) return "Cloudflare challenge";
        if (b.contains("show us your human side")) return "Akamai 'show us your human side'";
        if (b.contains("attention required") && b.contains("cloudflare")) return "Cloudflare attention-required";
        if (b.contains("enable javascript") && b.contains("continue")) return "JS-gate / cookie-wall";
        return null;
    }

    /**
     * Feeds Playwright's post-JS {@code page.content()} (the fully-rendered
     * HTML) into Jsoup. Returns {@code null} on any parse failure — callers
     * fall back to empty body / no links.
     */
    private static Document parseRendered(Page page) {
        try {
            String html = page.content();
            if (html == null || html.isEmpty()) return null;
            return Jsoup.parse(html, page.url());
        } catch (Exception e) {
            return null;
        }
    }

    private static String cap(String s) {
        if (s == null) return "";
        if (s.length() <= BODY_CHAR_LIMIT) return s;
        return s.substring(0, BODY_CHAR_LIMIT)
             + "\n…\n[truncated at " + BODY_CHAR_LIMIT + " chars]";
    }

    /**
     * Inline-link Markdown ({@code [text](url)}) in the body is the default
     * link surface — a trailing footer block of raw URLs is only appended
     * when the caller opted in via {@code createLinkList=true} (passed
     * down as a non-empty {@code linkList} string here).
     */
    private static String format(String url, String title, String bodyMarkdown, String linkList) {
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append('\n');
        if (title != null && !title.isEmpty()) sb.append("Title: ").append(title).append('\n');
        sb.append('\n').append(bodyMarkdown).append('\n');
        if (linkList != null && !linkList.isEmpty()) sb.append(linkList);
        return sb.toString();
    }
}
