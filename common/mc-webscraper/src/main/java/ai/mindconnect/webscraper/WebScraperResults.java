package ai.mindconnect.webscraper;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WebScraperResults {
    WebScraperOptions options;
    List<ScrapedPage> pages = new ArrayList<>();
    List<ScrapedPage> failedPages = new ArrayList<>();

    public void addSuccess(ScrapedPage page) {
        pages.add(page);
    }
    public void addFailed(ScrapedPage page) {
        pages.add(page);
    }

    public void add(WebScraperResults result) {
        pages.addAll(result.pages);
        failedPages.addAll(result.failedPages);
    }
}
