package ai.mindconnect.webscraper.playwright;

import ai.mindconnect.webscraper.*;
import ai.mindconnect.webscraper.ScrapedPage.ContentPartBySelector;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;

/**
 * Responsible for parsing a single URL
 */
@RequiredArgsConstructor
@Slf4j
@Data
public class PlaywrightSingleWebPageScraper implements ISingleWebPageScraper{

    private final BaseWebScraperOptions options;
    private final PlaywrightWebScraperContext context;
    private ScrapedPage scrapedPage = null;

    public void executeScrape() {
        String url = options.getUrl();
        scrapedPage = new ScrapedPage();
        scrapedPage.setUrl(url);
        try {
            Page page = this.getContext().getBrowserContext().newPage();
            log.info("Scraping {} ...", url);
            page.navigate(url);
            String title = page.title();
            scrapedPage.setTitle(title);
            if (options.isExtractContent()) {
                // content chunks
                readContent(page);
            }

            if (options.isExtractLinks()) {
                buildLinks(page, scrapedPage);
            }
            scrapedPage.setScrapedAt(LocalDateTime.now());
            if (options.getSaveToDirectory()!=null) {
                context.save(options.getSaveToDirectory(), scrapedPage);
            }
        } catch (RuntimeException e) {
            log.error("Could not scrape: " + url, e);
            scrapedPage.setException(e);
            if (!getOptions().isIgnoreErrors()) {
                throw e;
            }
        }
    }

    private void readContent(Page page) {
        List<ContentPartBySelector> contentPartsBySelector = new LinkedList<>();
        scrapedPage.setContentPartsBySelector(contentPartsBySelector);

        if (options.getCssSelectors() != null
                && options.getCssSelectors().size() > 0) {
            for (var cssSelector : options.getCssSelectors()) {
                if (cssSelector != null && !cssSelector.getSelector().isBlank()) {
                    if (cssSelector.getPattern() == null) {
                        extractContentFromSelector(page, cssSelector, contentPartsBySelector);
                    }
                    //only use selector if url pattern matches
                    else if (cssSelector.matches(options.getUrl())) {
                        extractContentFromSelector(page, cssSelector, contentPartsBySelector);
                    }
                }
            }
        } else {
            String part = page.content();
            String cleaned = HtmlCleaner.cleanHtml(part);
            contentPartsBySelector.add(new ContentPartBySelector(
                    "html",
                    options.isShowOriginalHtml() ? part : null,
                    cleaned,
                    "html"
            ));
        }
        if (options.isConvertToMarkdown()) {
            var converter = FlexmarkHtmlConverter.builder().build();
            for (var part : scrapedPage.getContentPartsBySelector()) {
                //Converted Text is cleaned therefore better for markdown
                String md = converter.convert(part.getConvertedText());
                part.setConvertedText(md);
                part.setConvertedTextFormat("markdown");
            }
        }
    }

    private void extractContentFromSelector(
            Page page,
            CssSelector cssSelector,
            List<ContentPartBySelector> contentPartsBySelector
    ) {
        String part = getPageContent(page, cssSelector.getSelector());
        String cleaned = HtmlCleaner.cleanHtml(part);
        contentPartsBySelector.add(new ContentPartBySelector(
                cssSelector.getSelector(),
                options.isShowOriginalHtml() ? part : null,
                cleaned,
                "html"
        ));
    }

    private String getPageContent(Page page, String selector) {
        String html = page.content();
        try {
            return page.innerHTML(selector);
        } catch (RuntimeException re) {
            log.error("could not resolve selector:" + selector, re);
            return html;
        }
    }

    public boolean isAnchorLink(String url) {
        return url.startsWith("#");
    }

    public boolean isTelLink(String url) {
        return url.startsWith("tel:");
    }

    public boolean isHttpUrl(String url) {
        return url.startsWith("http");
    }

    public String convertToAbsoluteUrl(String relativeUrl, String currentUrl) {
        URI url = URI.create(currentUrl.trim());
        String result = url.resolve(relativeUrl.trim()).toString();
        return result;
    }

    private void buildLinks(
            Page browserPage,
            ScrapedPage currentScrapedPage
    ) {
        List<Locator> linkLocators = browserPage.locator("a").all();
        for (Locator linkLocator : linkLocators) {
            String address = linkLocator.getAttribute("href");
            String absoluteAddress = address;
            if (address != null) {
                String linkTitle = linkLocator.innerText();

                //
                if (isTelLink(address) || isAnchorLink(address)) {
                    continue;
                }
                if (!isHttpUrl(address)) {
                    absoluteAddress = convertToAbsoluteUrl(address, options.getUrl());
                }

                ScrapedLink link = new ScrapedLink();
                link.setTitle(linkTitle.trim());
                link.setUrl(absoluteAddress);
                boolean exclude = false;
                if (options.getIncludeLinksWithPattern().size() > 0) {
                    exclude = true;
                    for (UrlPattern pattern : options.getIncludeLinksWithPattern()) {
                        if (pattern.matches(absoluteAddress)) {
                            exclude = false;
                            break;
                        }
                    }
                }
                //check excludes
                if (!exclude) {
                    for (UrlPattern pattern : options.getExcludeLinksWithPattern()) {
                        if (pattern.matches(absoluteAddress)) {
                            exclude = true;
                            break;
                        }
                    }
                }
                if (!exclude && !currentScrapedPage.getLinks().contains(link)
                        && context.shouldVisit(link.getUrl())) {
                    currentScrapedPage.getLinks().add(link);
                }
            }
        }
        if (log.isInfoEnabled()) {
            log.info("\t\tFound links:");
            for (var l : currentScrapedPage.getLinks()) {
                log.info("\t\t\t-{}:{}", l.getTitle(), l.getUrl());
            }
        }
    }
}
