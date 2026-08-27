package ai.mindconnect.webscraper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BaseWebScraperOptions {

    String url;
    List<CssSelector> cssSelectors = new ArrayList<>();
    boolean ignoreErrors;
    boolean extractContent;
    boolean extractLinks = true;
    List<UrlPattern> includeLinksWithPattern = new ArrayList<>();
    List<UrlPattern> excludeLinksWithPattern = new ArrayList<>();
    // or removeHtml, both cannot be true
    boolean convertToMarkdown;
    boolean removeHtml;
    boolean removeNewLine;
    boolean extractYoutubeLinks;
    boolean showOriginalHtml;
    String saveToDirectory;

    public BaseWebScraperOptions clone(String pStartUrl) {
        return new BaseWebScraperOptions(
                pStartUrl,
                this.cssSelectors,
                this.ignoreErrors,
                this.extractContent,
                this.extractLinks,
                new ArrayList<>(this.includeLinksWithPattern),
                new ArrayList<>(this.excludeLinksWithPattern),
                this.convertToMarkdown,
                this.removeHtml,
                this.removeNewLine,
                this.extractYoutubeLinks,
                this.showOriginalHtml,
                this.saveToDirectory
        );
    }

    public void addCssSelector(String s) {
        this.cssSelectors.add(new CssSelector(s, null, null));
    }

    public UrlPattern addIncludePattern(String pattern, UrlPattern.Mode mode) {
        var urlPattern = new UrlPattern(pattern, mode);
        this.includeLinksWithPattern.add(urlPattern);
        return urlPattern;
    }
    public UrlPattern addExcludePattern(String pattern, UrlPattern.Mode mode) {
        var urlPattern = new UrlPattern(pattern, mode);
        this.excludeLinksWithPattern.add(urlPattern);
        return urlPattern;
    }
}
