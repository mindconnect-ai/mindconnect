package ai.mindconnect.webscraper.playwright;

import ai.mindconnect.webscraper.*;
import com.microsoft.playwright.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class PlaywrightWebScraper extends AbstractWebScraper{

    public List<WebScraperResults> scrape(List<WebScraperOptions> options) {

        List<WebScraperResults> resultsList = new ArrayList<>(options.size());

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions();

            try (Browser browser = playwright.chromium().launch(launchOptions)) {

                Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

                BrowserContext browserContext = browser.newContext(contextOptions);

                for (var option : options) {
                    PlaywrightWebScraperContext context = new PlaywrightWebScraperContext(browserContext, option);
                    if (getSaveFunction() != null) {
                        context.setSaveFunction(this.saveFunction);
                    }
                    WebScraperResults results = new WebScraperResults();
                    results.setOptions(option);
                    List<ScrapedPage> pages = scrape(context, option, 0);
                    results.setPages(pages);
                    resultsList.add(results);
                }

                browserContext.close();
            }
        }

        return resultsList;
    }

    public List<ScrapedPage> scrape(
            PlaywrightWebScraperContext context,
            WebScraperOptions options,
            Integer linkLevel
    ) {
        List<ScrapedPage> results = new ArrayList<>();
        if (requestToStop) {
            log.info("Requested to stop ...");
            return results;
        }
        if (!context.shouldVisit(options.getUrl())) {
            return results;
        }
        ScrapedPage currentScrapedPage = null;
        try {
            if (context.shouldVisit(options.getUrl())) {
                PlaywrightSingleWebPageScraper singleWebPageScraper = new PlaywrightSingleWebPageScraper(
                        options.clone(options.getUrl()),
                        context
                );
                singleWebPageScraper.executeScrape();
                currentScrapedPage = singleWebPageScraper.getScrapedPage();
                context.visited(currentScrapedPage.getUrl());
                results.add(currentScrapedPage);
                fireEventScrapedPage(currentScrapedPage);
                // recursively visit links
                if (options.isIncludeChildren()) {
                    List<ScrapedPage> subResults = visitLinks(
                            context,
                            options,
                            linkLevel,
                            currentScrapedPage
                    );
                    results.addAll(subResults);
                }
            }
        } catch (RuntimeException e) {
            log.error("Could not scrape: " + options.getUrl(), e);
            if (currentScrapedPage!=null) {
                currentScrapedPage.setException(e);
            }
            if (!context.getOptions().isIgnoreErrors()) {
                throw e;
            }
        }
        return results;
    }

    @Nullable
    private List<ScrapedPage> visitLinks(
            PlaywrightWebScraperContext context,
            WebScraperOptions options,
            Integer linkLevel,
            ScrapedPage currentScrapedPage
    ) {
        List<ScrapedPage> result = new ArrayList<>();
        if (options.getLinkDepth() != null && options.getLinkDepth() > linkLevel) {
            // go through all links
            for (ScrapedLink link : currentScrapedPage.getLinks()) {
                if (requestToStop) {
                    log.info("Requested to stop ...");
                    return result;
                }
                if (context.shouldVisit(link.getUrl())) {
                    List<ScrapedPage> subPages = scrape(
                            context,
                            options.clone(link.getUrl()),
                            linkLevel
                                    + 1
                    );
                    result.addAll(subPages);
                };
            }
        }
        return result;
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


}
