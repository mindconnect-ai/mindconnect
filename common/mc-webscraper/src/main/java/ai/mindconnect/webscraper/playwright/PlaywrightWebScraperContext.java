package ai.mindconnect.webscraper.playwright;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.webscraper.BaseWebScraperContext;
import ai.mindconnect.webscraper.BaseWebScraperOptions;
import com.microsoft.playwright.BrowserContext;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class PlaywrightWebScraperContext extends BaseWebScraperContext {

    private BrowserContext browserContext;

    public PlaywrightWebScraperContext(BrowserContext browserContext, BaseWebScraperOptions options, JsonMapperFactory json) {
        super(options, json);
        this.browserContext = browserContext;
    }
}
