package ai.mindconnect.adminui.branding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How this installation names and dresses itself: the heading in the header,
 * the logo beside it, the browser tab's title and icon, the theme the shell
 * opens in, any stylesheet that should win over the shipped ones, and what
 * the theme picker offers.
 *
 * <p>Everything has a default, so an installation that sets nothing looks
 * exactly as it ships.
 *
 * <pre>{@code
 * mindconnect:
 *   branding:
 *     title: ACME Assistants
 *     logo: /branding/acme.svg
 *     stylesheets:
 *       - /branding/acme.css
 *     assets-dir: ./branding
 * }</pre>
 *
 * <h2>One process, several brands</h2>
 *
 * <p>{@code switch} makes the look a property of the host in the address bar
 * rather than of the process: one deployment behind two names wears two
 * brands. Each entry takes the same keys as the block above plus a
 * {@code url-pattern}; the first entry whose pattern matches the request's
 * host wins, and whatever it leaves unset falls back to the top-level
 * settings. Nothing matching is not an error — that is what the top level is.
 *
 * <pre>{@code
 * mindconnect:
 *   branding:
 *     switch:
 *       erni:
 *         url-pattern: "*.erni.*"
 *         title: ERNI AI
 *         logo: erni.svg
 *         stylesheet: erni-sui.css
 *         style-picker:
 *           disabled: true
 *       mindconnect:
 *         url-pattern: app.mindconnect.ai
 *         title: Mindconnect Agent Runtime
 *         logo: mindconnect-logo.svg
 *         style-picker:
 *           themes: amethyst, default
 * }</pre>
 *
 * <p>Write the more specific pattern first: entries are tried in the order
 * they are declared, so {@code app.mindconnect.ai} above {@code *} rather
 * than below it.
 *
 * <h2>Where assets come from</h2>
 *
 * <p>An asset URL is used as it is written, so all of these work: a path into
 * the app's own resources ({@code /img/logo.svg}), an absolute URL on a CDN,
 * and a bare file name ({@code erni.svg}), which is taken to be a file in
 * {@link #getAssetsDir() assets-dir} and served at {@code /branding/**}.
 */
@Component
@ConfigurationProperties(prefix = "mindconnect.branding")
public class BrandingProperties extends BrandingVariant {

    /** The heading when nothing is configured — what the header showed before this was a setting. */
    public static final String DEFAULT_TITLE = "Mindconnect Agent Runtime";
    /** The shipped logo, in this module's {@code static/img}. */
    public static final String DEFAULT_LOGO = "/img/logo.svg";
    /** Where the brand leads: the agent list, the admin UI's home. */
    public static final String DEFAULT_LOGO_HREF = "/admin/agents";
    /** The look the shell falls back to; one of the themes the SPA ships (see index.html). */
    public static final String DEFAULT_THEME = "amethyst";
    /** URL prefix the {@link #getAssetsDir() assets directory} is served under. */
    public static final String ASSETS_PATH = "/branding";

    /** What each host gets, in the order the entries are declared. */
    private Map<String, BrandingVariant> variants = new LinkedHashMap<>();

    private String assetsDir;

    /** The {@code switch} block: named variants, each with its own {@code url-pattern}. */
    public Map<String, BrandingVariant> getSwitch() {
        return variants;
    }

    public void setSwitch(Map<String, BrandingVariant> variants) {
        this.variants = variants == null ? new LinkedHashMap<>() : variants;
    }

    /**
     * A directory on disk served at {@code /branding/**}, or null for none.
     * This is what makes branding a deployment concern rather than a build
     * one: drop the logos and the stylesheets of every brand this process
     * serves next to the app, and nothing has to be rebuilt.
     */
    public String getAssetsDir() {
        return isSet(assetsDir) ? assetsDir.trim() : null;
    }

    public void setAssetsDir(String assetsDir) {
        this.assetsDir = assetsDir;
    }

    /** The branding for a request that names no host — the top-level settings. */
    public Branding resolve() {
        return resolve(null);
    }

    /**
     * The branding for {@code host}: the first matching {@code switch} entry
     * over the top-level settings over what the app ships.
     */
    public Branding resolve(String host) {
        BrandingVariant variant = variantFor(host);
        String title = firstSet(variant == null ? null : variant.getTitle(), getTitle(), DEFAULT_TITLE);
        String documentTitle = firstSet(variant == null ? null : variant.getDocumentTitle(),
                getDocumentTitle(), title);
        // The logo answers "none" as well as "inherit", so it cannot use
        // firstSet: an empty string is an answer, and getLogo() returning null
        // for a blank value is what carries it.
        String logo = pick(variant == null ? null : variant.getLogo(), getLogo(), DEFAULT_LOGO);
        String favicon = pick(variant == null ? null : variant.getFavicon(), getFavicon(), logo);
        return new Branding(title, documentTitle,
                asset(logo), firstSet(variant == null ? null : variant.getLogoHref(), getLogoHref(), DEFAULT_LOGO_HREF),
                asset(favicon), firstSet(variant == null ? null : variant.getTheme(), getTheme(), DEFAULT_THEME),
                stylesheets(variant), pickerDisabled(variant), pickerThemes(variant));
    }

    /** The first entry whose pattern matches, or null for the top-level branding. */
    BrandingVariant variantFor(String host) {
        String name = normalizeHost(host);
        if (name == null) return null;
        return variants.values().stream().filter(v -> v.matchesHost(name)).findFirst().orElse(null);
    }

    private List<String> stylesheets(BrandingVariant variant) {
        List<String> sheets = variant == null ? List.of() : variant.stylesheetUrls();
        if (sheets.isEmpty()) sheets = stylesheetUrls();
        return sheets.stream().map(BrandingProperties::asset).toList();
    }

    private boolean pickerDisabled(BrandingVariant variant) {
        Boolean variantSays = variant == null ? null : variant.getStylePicker().getDisabled();
        if (variantSays != null) return variantSays;
        Boolean topLevel = getStylePicker().getDisabled();
        return topLevel != null && topLevel;
    }

    private List<String> pickerThemes(BrandingVariant variant) {
        List<String> themes = variant == null ? List.of() : variant.getStylePicker().themeIds();
        return themes.isEmpty() ? getStylePicker().themeIds() : themes;
    }

    /**
     * A bare file name is a file in {@link #getAssetsDir()}; a path or an
     * absolute URL is left alone. Without that rule every entry in a
     * {@code switch} block would repeat the same prefix.
     */
    static String asset(String url) {
        if (!isSet(url)) return null;
        String value = url.trim();
        if (value.startsWith("/") || value.contains("://") || value.startsWith("data:")) return value;
        return ASSETS_PATH + "/" + value;
    }

    /** The first value that is set, blanks counting as unset. */
    private static String firstSet(String... values) {
        for (String value : values) {
            if (isSet(value)) return value.trim();
        }
        return null;
    }

    /**
     * Like {@link #firstSet} but for a setting whose empty value means "none":
     * the first value that is non-null decides, and a blank one answers null.
     */
    private static String pick(String... values) {
        for (String value : values) {
            if (value == null) continue;
            return value.isBlank() ? null : value.trim();
        }
        return null;
    }
}
