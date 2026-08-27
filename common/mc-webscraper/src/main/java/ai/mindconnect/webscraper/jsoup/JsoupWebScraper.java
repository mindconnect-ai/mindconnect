package ai.mindconnect.webscraper.jsoup;

import ai.mindconnect.webscraper.*;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class JsoupWebScraper extends AbstractWebScraper {

    @Override
    public List<WebScraperResults> scrape(List<WebScraperOptions> options) {

        List<WebScraperResults> resultsList = new ArrayList<>(options.size());

        for (var option : options) {
            JsoupWebScraperContext context = new JsoupWebScraperContext(option);
            if (getSaveFunction() != null) {
                context.setSaveFunction(this.saveFunction);
            }
            WebScraperResults mainResults = new WebScraperResults();
            mainResults.setOptions(option);
            WebScraperResults subResults = scrape(context, option, 0);
            mainResults.setPages(subResults.getPages());
            mainResults.setFailedPages(subResults.getFailedPages());
            resultsList.add(mainResults);
        }

        return resultsList;
    }


    public WebScraperResults scrape(
            JsoupWebScraperContext context,
            WebScraperOptions options,
            Integer linkLevel
    ) {
        WebScraperResults result = new WebScraperResults();


        if (isRequestToStop()) {
            log.info("Requested to stop ...");
            return result;
        }
        if (!context.shouldVisit(options.getUrl())) {
            return result;
        }
        ScrapedPage currentScrapedPage = null;
        try {
            if (context.shouldVisit(options.getUrl())) {
                currentScrapedPage = new ScrapedPage();
                currentScrapedPage.setUrl(options.getUrl());

                JsoupSingleWebPageScraper singleWebPageScraper = new JsoupSingleWebPageScraper(
                        options.clone(options.getUrl()),
                        context,
                        currentScrapedPage
                );
                singleWebPageScraper.executeScrape();
                context.visited(currentScrapedPage.getUrl());
                result.addSuccess(currentScrapedPage);
                fireEventScrapedPage(currentScrapedPage);
                // recursively visit links
                if (options.isIncludeChildren()) {
                    WebScraperResults subResults = visitLinks(
                            context,
                            options,
                            linkLevel,
                            currentScrapedPage
                    );
                    result.add(subResults);
                }
            }
        } catch (RuntimeException e) {
            log.error("Could not scrape: " + options.getUrl(), e);
            if (currentScrapedPage != null) {
                currentScrapedPage.setException(e);
            }
            fireEventFailedScrapedPage(currentScrapedPage);
            result.addFailed(currentScrapedPage);
            if (!context.getOptions().isIgnoreErrors()) {
                throw e;
            }
        }
        return result;
    }

    private WebScraperResults visitLinks(
            JsoupWebScraperContext context,
            WebScraperOptions options,
            Integer linkLevel,
            ScrapedPage currentScrapedPage
    ) {
        WebScraperResults result = new WebScraperResults();
        if (options.getLinkDepth() != null && options.getLinkDepth() > linkLevel) {
            // go through all links
            for (ScrapedLink link : currentScrapedPage.getLinks()) {
                if (isRequestToStop()) {
                    log.info("Requested to stop ...");
                    return result;
                }
                if (context.shouldVisit(link.getUrl())) {
                    WebScraperResults subResult = scrape(
                            context,
                            options.clone(link.getUrl()),
                            linkLevel
                                    + 1
                    );
                    result.add(subResult);
                }
                ;
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
