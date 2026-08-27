package ai.mindconnect.agent.tools.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Lazy, JVM-singleton {@link Playwright} + {@link Browser} pair.
 *
 * <p>The first call to {@link #browser()} starts Playwright and launches a
 * headless Chromium. Subsequent calls reuse the same browser; each tool
 * invocation opens its own {@link com.microsoft.playwright.BrowserContext}
 * (with isolated cookies / storage) so concurrent calls don't bleed state
 * into each other.
 *
 * <p>Cold start: ~1-2s after the Chromium download is already on disk;
 * the first-ever JVM start can pull ~150MB. We delay browser launch until
 * the first {@code web_read_browser} call rather than at module bind so
 * apps that load the tool but never use it pay nothing.
 *
 * <p>Shutdown is registered as a JVM hook — both the browser and the
 * {@link Playwright} engine get closed on normal exit.
 */
public final class PlaywrightHolder {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightHolder.class);

    private PlaywrightHolder() {}

    private static volatile Playwright playwright;
    private static volatile Browser browser;

    /**
     * Returns the shared headless Chromium browser, starting Playwright on
     * the first call. Thread-safe via double-checked locking.
     */
    public static Browser browser() {
        Browser local = browser;
        if (local != null) return local;
        synchronized (PlaywrightHolder.class) {
            if (browser != null) return browser;
            log.info("Starting Playwright + Chromium (first use)…");
            long t0 = System.currentTimeMillis();
            playwright = Playwright.create();
            // Headless Chromium. --disable-gpu and --no-sandbox keep the
            // launch viable inside locked-down containers / CI images.
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of(
                            "--disable-gpu",
                            "--no-sandbox",
                            "--disable-dev-shm-usage")));
            Runtime.getRuntime().addShutdownHook(new Thread(PlaywrightHolder::close,
                    "playwright-shutdown"));
            log.info("Playwright + Chromium ready in {} ms", System.currentTimeMillis() - t0);
            return browser;
        }
    }

    private static synchronized void close() {
        try {
            if (browser != null) browser.close();
        } catch (Exception e) {
            log.warn("Browser close failed: {}", e.getMessage());
        }
        try {
            if (playwright != null) playwright.close();
        } catch (Exception e) {
            log.warn("Playwright close failed: {}", e.getMessage());
        }
        browser = null;
        playwright = null;
    }
}
