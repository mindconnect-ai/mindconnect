package ai.mindconnect.webscraper;

import java.util.List;

public interface WebScraperListener {
    void onScrapedPage(ScrapedPage page);
    void onFailedScrapedPage(ScrapedPage page);
    default void onFinished(WebScraperResults result) {
    }
}
