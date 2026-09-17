package ai.mindconnect.adminui.branding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One look this installation can wear: the heading, the mark, the tab, the
 * theme, the stylesheets, and what the theme picker offers.
 *
 * <p>Every value is optional and means "inherit" when unset — a
 * {@code switch} entry says only what makes it different, and the top-level
 * branding says the rest. {@link BrandingProperties} extends this class, so
 * the top-level settings and a variant take exactly the same keys.
 *
 * <p>A value set to the empty string is not "unset" but "none":
 * {@code logo: ""} drops the logo rather than inheriting one, which is the
 * only way to ask for a header with the wordmark alone.
 */
public class BrandingVariant {

    /**
     * The host this variant is for, as a glob: {@code app.mindconnect.ai},
     * {@code *.acme.*}, {@code acme.*}. Matched against the request's host
     * name — no port, no path — ignoring case. Only meaningful inside
     * {@code switch}; the top-level branding is what applies when no entry
     * matches.
     */
    private String urlPattern;

    private String title;
    private String documentTitle;
    private String logo;
    private String logoHref;
    private String favicon;
    private String theme;
    private List<String> stylesheets = new ArrayList<>();
    private StylePicker stylePicker = new StylePicker();

    /**
     * The namespace this brand works in, and who shapes it; null for a brand
     * that has none. Never inherited — see {@link BrandingNamespace}.
     */
    private BrandingNamespace namespace;

    public BrandingNamespace getNamespace() {
        return namespace;
    }

    public void setNamespace(BrandingNamespace namespace) {
        this.namespace = namespace;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public void setUrlPattern(String urlPattern) {
        this.urlPattern = urlPattern;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }

    public void setDocumentTitle(String documentTitle) {
        this.documentTitle = documentTitle;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getLogoHref() {
        return logoHref;
    }

    public void setLogoHref(String logoHref) {
        this.logoHref = logoHref;
    }

    public String getFavicon() {
        return favicon;
    }

    public void setFavicon(String favicon) {
        this.favicon = favicon;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public List<String> getStylesheets() {
        return stylesheets;
    }

    public void setStylesheets(List<String> stylesheets) {
        this.stylesheets = stylesheets == null ? new ArrayList<>() : stylesheets;
    }

    /**
     * {@code stylesheet: brand.css} for the common case of exactly one. It is
     * the same setting as {@link #setStylesheets(List)} — the last of the two
     * to be bound wins, so a configuration should use one or the other.
     */
    public void setStylesheet(String stylesheet) {
        this.stylesheets = stylesheet == null || stylesheet.isBlank()
                ? new ArrayList<>() : new ArrayList<>(List.of(stylesheet));
    }

    public StylePicker getStylePicker() {
        return stylePicker;
    }

    public void setStylePicker(StylePicker stylePicker) {
        this.stylePicker = stylePicker == null ? new StylePicker() : stylePicker;
    }

    /** The stylesheet URLs, blanks dropped. */
    public List<String> stylesheetUrls() {
        return stylesheets.stream().filter(BrandingVariant::isSet).map(String::trim).toList();
    }

    /**
     * Whether this variant is the one for {@code host}. False when it names no
     * pattern, so an entry that forgot its {@code url-pattern} is simply never
     * chosen rather than chosen for everybody.
     */
    public boolean matchesHost(String host) {
        return isSet(urlPattern) && isSet(host) && hostPattern(urlPattern.trim()).matcher(host.trim()).matches();
    }

    /**
     * The glob as a regex: {@code *} stands for any run of characters, dots
     * and all, so {@code *.acme.*} covers {@code agents.acme.com} as well as
     * {@code ai.acme.co.uk}. Everything else is literal.
     */
    private static Pattern hostPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        int from = 0;
        for (int star = glob.indexOf('*'); star >= 0; star = glob.indexOf('*', from)) {
            if (star > from) regex.append(Pattern.quote(glob.substring(from, star)));
            regex.append(".*");
            from = star + 1;
        }
        if (from < glob.length()) regex.append(Pattern.quote(glob.substring(from)));
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** The host name, lower case and without the port — what a pattern is written against. */
    static String normalizeHost(String host) {
        if (!isSet(host)) return null;
        String out = host.trim().toLowerCase(Locale.ROOT);
        int colon = out.lastIndexOf(':');
        // Leave an IPv6 literal alone; its colons are part of the address.
        if (colon > 0 && out.indexOf(':') == colon) out = out.substring(0, colon);
        return out;
    }
}
