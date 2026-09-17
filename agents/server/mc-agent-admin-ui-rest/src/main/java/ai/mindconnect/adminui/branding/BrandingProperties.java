package ai.mindconnect.adminui.branding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * How this installation names and dresses itself: the heading in the header,
 * the logo beside it, the browser tab's title and icon, and any stylesheet
 * that should win over the shipped ones.
 *
 * <p>Everything here has a default, so an installation that sets nothing looks
 * exactly as it did before. A value set to the empty string means "none" —
 * {@code logo: ""} drops the logo rather than falling back to the shipped one,
 * which is the only way to ask for a wordmark-only header.
 *
 * <pre>{@code
 * mindconnect:
 *   branding:
 *     title: ACME Assistants
 *     logo: /branding/acme.svg
 *     favicon: /branding/favicon.png
 *     stylesheets:
 *       - /branding/acme.css
 *     assets-dir: ./branding
 * }</pre>
 *
 * <p>Asset URLs are used verbatim in the page, so all three forms work: a path
 * into the app's own classpath ({@code /img/logo.svg}), a path into the
 * {@link #getAssetsDir() assets directory} served at {@code /branding/**}, and
 * an absolute URL on a CDN.
 */
@Component
@ConfigurationProperties(prefix = "mindconnect.branding")
public class BrandingProperties {

    /** The heading when nothing is configured — what the header showed before this was a setting. */
    public static final String DEFAULT_TITLE = "Mindconnect Agent Runtime";
    /** The shipped logo, in this module's {@code static/img}. */
    public static final String DEFAULT_LOGO = "/img/logo.svg";
    /** Where the brand leads: the agent list, the admin UI's home. */
    public static final String DEFAULT_LOGO_HREF = "/admin/agents";
    /** The look the shell falls back to; must be one of the themes the SPA ships (see index.html). */
    public static final String DEFAULT_THEME = "amethyst";
    /** URL prefix the {@link #getAssetsDir() assets directory} is served under. */
    public static final String ASSETS_PATH = "/branding";

    private String title = DEFAULT_TITLE;
    private String documentTitle;
    private String logo = DEFAULT_LOGO;
    private String logoHref = DEFAULT_LOGO_HREF;
    private String favicon;
    private String theme = DEFAULT_THEME;
    private List<String> stylesheets = new ArrayList<>();
    private String assetsDir;

    /** The heading beside the logo, and the login page's title. */
    public String getTitle() {
        return orDefault(title, DEFAULT_TITLE);
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /** The browser tab's title; unset it follows {@link #getTitle()}. */
    public String getDocumentTitle() {
        return isSet(documentTitle) ? documentTitle.trim() : getTitle();
    }

    public void setDocumentTitle(String documentTitle) {
        this.documentTitle = documentTitle;
    }

    /** The logo beside the heading, or null when this installation wants none. */
    public String getLogo() {
        return isSet(logo) ? logo.trim() : null;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    /** Where a click on the brand goes. */
    public String getLogoHref() {
        return orDefault(logoHref, DEFAULT_LOGO_HREF);
    }

    public void setLogoHref(String logoHref) {
        this.logoHref = logoHref;
    }

    /** The browser tab's icon; unset it follows the logo, which is an image the installation already has. */
    public String getFavicon() {
        return isSet(favicon) ? favicon.trim() : getLogo();
    }

    public void setFavicon(String favicon) {
        this.favicon = favicon;
    }

    /**
     * The theme the shell starts in — one of the looks the SPA ships
     * ({@code amethyst}, {@code clody}, {@code gipiti}, {@code sorbet},
     * {@code compact}, {@code dark}), or {@code default} for the framework's
     * bare one. A name nobody ships matches no stylesheet and renders as that
     * bare default. It is only the starting point: the theme picker in the
     * header still overrides it per browser.
     */
    public String getTheme() {
        return orDefault(theme, DEFAULT_THEME);
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    /**
     * Extra stylesheets, linked after every shipped one so their rules win.
     * Each is a URL as the browser will see it (see the class javadoc for the
     * three forms).
     */
    public List<String> getStylesheets() {
        return stylesheets;
    }

    public void setStylesheets(List<String> stylesheets) {
        this.stylesheets = stylesheets == null ? new ArrayList<>() : stylesheets;
    }

    /**
     * A directory on disk served at {@code /branding/**}, or null for none.
     * This is what makes branding a deployment concern rather than a build
     * one: drop a logo and a stylesheet next to the app, point the settings
     * above at {@code /branding/…}, and nothing has to be rebuilt.
     */
    public String getAssetsDir() {
        return isSet(assetsDir) ? assetsDir.trim() : null;
    }

    public void setAssetsDir(String assetsDir) {
        this.assetsDir = assetsDir;
    }

    /** The stylesheet URLs, blanks dropped — a YAML list easily carries one. */
    public List<String> stylesheetUrls() {
        return stylesheets.stream().filter(BrandingProperties::isSet).map(String::trim).toList();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return isSet(value) ? value.trim() : fallback;
    }
}
