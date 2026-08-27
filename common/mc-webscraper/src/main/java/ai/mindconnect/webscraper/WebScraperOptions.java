package ai.mindconnect.webscraper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor

public class WebScraperOptions extends BaseWebScraperOptions {

    private boolean includeChildren;
    private Integer linkDepth;

    public WebScraperOptions(
            String url,
            List<CssSelector> cssSelectors,
            boolean ignoreErrors,
            boolean extractContent,
            boolean extractLinks,
            List<UrlPattern> includeLinksWithRegex,
            List<UrlPattern> excludeLinksWithRegex,
            boolean convertToMarkdown,
            boolean removeHtml,
            boolean removeNewLine,
            boolean extractYoutubeLinks,
            boolean showOriginalHtml,
            String saveToDirectory,
            boolean includeChildren,
            Integer linkDepth
    ) {
        super(
                url,
                cssSelectors,
                ignoreErrors,
                extractContent,
                extractLinks,
                includeLinksWithRegex,
                excludeLinksWithRegex,
                convertToMarkdown,
                removeHtml,
                removeNewLine,
                extractYoutubeLinks,
                showOriginalHtml,
                saveToDirectory
        );
        this.includeChildren = includeChildren;
        this.linkDepth = linkDepth;
    }

    public WebScraperOptions clone(String pStartUrl) {
        return new WebScraperOptions(
                pStartUrl,
                this.cssSelectors,
                this.ignoreErrors,
                this.extractContent,
                this.extractLinks,
                new ArrayList<>(this.includeLinksWithPattern),
                new ArrayList<>(this.excludeLinksWithPattern),
                this.convertToMarkdown,
                this.removeHtml,
                this.removeHtml,
                this.extractYoutubeLinks,
                this.showOriginalHtml,
                this.saveToDirectory,
                this.includeChildren,
                this.linkDepth
        );
    }
}
