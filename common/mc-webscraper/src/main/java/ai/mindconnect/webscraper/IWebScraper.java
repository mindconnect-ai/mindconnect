package ai.mindconnect.webscraper;

import java.util.List;

public interface IWebScraper {
    void addListener(WebScraperListener listener);

    List<WebScraperResults> scrape(List<WebScraperOptions> options);

    boolean isRequestToStop();

    void setRequestToStop(boolean requestToStop);

    BaseWebScraperContext.SaveFunction getSaveFunction();
    void setSaveFunction(BaseWebScraperContext.SaveFunction function);
}
