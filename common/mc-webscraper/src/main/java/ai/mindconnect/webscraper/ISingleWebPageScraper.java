package ai.mindconnect.webscraper;

public interface ISingleWebPageScraper {

    void executeScrape();

    BaseWebScraperOptions getOptions();

    ScrapedPage getScrapedPage();

}
