package ai.mindconnect.agent.tools.browser;

/**
 * Init-script that masks the most common headless-Chromium tell-tales
 * before any page JavaScript runs. Injected via
 * {@link com.microsoft.playwright.BrowserContext#addInitScript(String)}
 * so it executes on every navigation (including iframes) in the context.
 *
 * <p>This is the well-known "puppeteer-extra-plugin-stealth" approach
 * boiled down to the few overrides that move the needle against
 * default-config bot-detectors (Akamai light, Cloudflare-default,
 * Imperva-default):
 * <ul>
 *   <li>{@code navigator.webdriver} — Playwright sets it to {@code true},
 *       this is the #1 single signal. We pretend it's undefined.</li>
 *   <li>{@code navigator.plugins} — headless Chromium returns an empty
 *       PluginArray; we synthesise a couple of plausible entries.</li>
 *   <li>{@code navigator.languages} — we set a realistic list rather
 *       than the single locale Playwright passes through.</li>
 *   <li>{@code window.chrome} — present in real Chrome, missing in
 *       Playwright by default. We attach a minimal stub.</li>
 *   <li>WebGL vendor/renderer — bot-checks query GPU strings; the
 *       headless default ("Google Inc.", "Google SwiftShader") is a
 *       giveaway. We swap in Intel iris values that match Macs in the
 *       wild.</li>
 *   <li>Permissions API — the {@code notifications} permission returns
 *       {@code denied} under automation but is normally {@code prompt}.
 *       We patch the query response.</li>
 * </ul>
 *
 * <p>This won't defeat aggressive setups (Cloudflare Turnstile,
 * DataDome, kasada). For those the {@link WebReadBrowserTool} returns a
 * "BLOCKED:" result so the researcher can move on.
 */
final class StealthScript {

    private StealthScript() {}

    static final String SOURCE = """
            // ── navigator.webdriver: must look undefined ─────────────────────
            Object.defineProperty(Navigator.prototype, 'webdriver', {
              get: () => undefined,
              configurable: true
            });

            // ── navigator.plugins: synthesise a plausible list ───────────────
            (() => {
              const fake = [
                { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: '' },
                { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: '' },
                { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: '' }
              ];
              const arr = Object.create(PluginArray.prototype);
              fake.forEach((p, i) => { arr[i] = p; arr[p.name] = p; });
              Object.defineProperty(arr, 'length', { get: () => fake.length });
              Object.defineProperty(Navigator.prototype, 'plugins', {
                get: () => arr, configurable: true
              });
            })();

            // ── navigator.languages: realistic list ──────────────────────────
            Object.defineProperty(Navigator.prototype, 'languages', {
              get: () => ['de-CH', 'de', 'en'],
              configurable: true
            });

            // ── window.chrome: minimal stub ──────────────────────────────────
            if (!window.chrome) {
              window.chrome = {
                runtime: {},
                loadTimes: () => ({}),
                csi: () => ({}),
                app: { isInstalled: false }
              };
            }

            // ── WebGL vendor / renderer ──────────────────────────────────────
            (() => {
              const orig = WebGLRenderingContext.prototype.getParameter;
              WebGLRenderingContext.prototype.getParameter = function (p) {
                // UNMASKED_VENDOR_WEBGL and UNMASKED_RENDERER_WEBGL
                if (p === 37445) return 'Intel Inc.';
                if (p === 37446) return 'Intel Iris OpenGL Engine';
                return orig.call(this, p);
              };
            })();

            // ── permissions API: notifications should report 'prompt' ────────
            (() => {
              const origQuery = navigator.permissions && navigator.permissions.query;
              if (!origQuery) return;
              navigator.permissions.query = (parameters) => {
                if (parameters && parameters.name === 'notifications') {
                  return Promise.resolve({ state: 'prompt', onchange: null });
                }
                return origQuery.call(navigator.permissions, parameters);
              };
            })();
            """;
}
