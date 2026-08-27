package ai.mindconnect.webscraper;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(exclude = "title") //to not have the same links multiple times in a list
public class ScrapedLink {
    private String url;
    private String title;
}
