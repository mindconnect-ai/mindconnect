package ai.mindconnect.webscraper;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractWebScraper implements IWebScraper{

    @Setter @Getter protected volatile boolean requestToStop = false;

    @Setter @Getter @JsonIgnore
    protected BaseWebScraperContext.SaveFunction saveFunction = null;
    @JsonIgnore
    private List<WebScraperListener> listeners = new ArrayList<>();

    @Override public void addListener(WebScraperListener listener) {
        listeners.add(listener);
    }

    protected void fireEventScrapedPage(ScrapedPage currentScrapedPage) {
        for (WebScraperListener listener : this.listeners) {
            listener.onScrapedPage(currentScrapedPage);
        }
    }

    protected void fireEventFailedScrapedPage(ScrapedPage currentScrapedPage) {
        for (WebScraperListener listener : this.listeners) {
            listener.onFailedScrapedPage(currentScrapedPage);
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
        URI url = URI.create(currentUrl);
        String result = url.resolve(relativeUrl).toString();
        return result;
    }
}
