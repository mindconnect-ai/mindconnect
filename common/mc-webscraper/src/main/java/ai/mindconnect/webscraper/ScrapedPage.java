package ai.mindconnect.webscraper;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Data
public class ScrapedPage {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentPartBySelector {
        private String cssSelector;
        private String originalHtml;
        private String convertedText;
        private String convertedTextFormat;
    }

    private List<ContentPartBySelector> contentPartsBySelector = new ArrayList<>();
    private String id = UUID.randomUUID().toString();
    private String url;
    private String title;
    private String error;
    @JsonIgnore
    private Exception exception;
    private Set<ScrapedLink> links = new LinkedHashSet<>();
    private String filePath;
    private LocalDateTime scrapedAt;

    public void setError(Exception exception) {
        this.exception = exception;
        this.error = exception != null ? exception.getMessage() : null;
    }

    public ContentPartBySelector addContentPart(String cssSelector, String html, String convertedText, String textFormat) {
        var c = new ContentPartBySelector(cssSelector,html,convertedText,textFormat);
        this.contentPartsBySelector.add(c);
        return c;
    }

    public String getConvertedTextForAllParts(String separator) {
        return this.contentPartsBySelector.stream().map(part -> part.getConvertedText())
                .collect(Collectors.joining(separator));
    }
}